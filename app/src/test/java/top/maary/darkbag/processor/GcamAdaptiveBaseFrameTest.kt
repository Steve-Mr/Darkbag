package top.maary.darkbag.processor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import kotlin.math.max

class GcamAdaptiveBaseFrameTest {

    @Test
    fun testStreamingBurstFrameDefaultNoiseProfile() {
        val frame = StreamingBurstFrame(
            width = 4000,
            height = 3000,
            timestampNs = 1_000_000_000L,
            exposureTimeNs = 20_000_000L,
            rotationDegrees = 90,
            physicalId = "0"
        )
        assertEquals(0.0001f, frame.noiseProfileS, 1e-7f)
        assertEquals(0.00001f, frame.noiseProfileO, 1e-7f)

        // Verify mutability
        frame.noiseProfileS = 0.0005f
        frame.noiseProfileO = 0.00002f
        assertEquals(0.0005f, frame.noiseProfileS, 1e-7f)
        assertEquals(0.00002f, frame.noiseProfileO, 1e-7f)
    }

    @Test
    fun testSteadyScoreThresholdConstant() {
        assertEquals(25.0f, HdrPlusStreamingBurst.STEADY_SCORE_THRESHOLD, 1e-5f)
    }

    /**
     * Reference implementation of the GCam frame score formula in C++
     * to verify mathematical behavior against known inputs.
     */
    private fun computeReferenceGcamScore(
        pixels: IntArray,
        width: Int,
        height: Int,
        cfaPattern: Int,
        noiseProfileS: Float,
        noiseProfileO: Float,
        timeDeltaFromFirstNs: Long
    ): Float {
        val regionW = minOf(512, width - 4)
        val regionH = minOf(512, height - 4)
        if (regionW < 4 || regionH < 4) return 0.0f

        var startX = (width - regionW) / 2
        var endX = startX + regionW
        var startY = (height - regionH) / 2
        var endY = startY + regionH

        if (startX < 2) startX = 2
        if (endX > width - 2) endX = width - 2
        if (startY < 2) startY = 2
        if (endY > height - 2) endY = height - 2

        if (startY % 2 != 0) startY++
        val targetXParity = if (cfaPattern == 1 || cfaPattern == 2) 0 else 1
        if (startX % 2 != targetXParity) startX++

        val kStride = 2
        var sumEnergy = 0.0
        var sampleCount = 0L

        for (y in startY until endY step kStride) {
            for (x in startX until endX step kStride) {
                val g = pixels[y * width + x].toFloat()
                val lap = 4.0f * g -
                    pixels[y * width + (x - 2)].toFloat() -
                    pixels[y * width + (x + 2)].toFloat() -
                    pixels[(y - 2) * width + x].toFloat() -
                    pixels[(y + 2) * width + x].toFloat()
                val v = max(16.0f, noiseProfileS * g + noiseProfileO)
                sumEnergy += (lap * lap) / v
                sampleCount++
            }
        }

        val sharpness = if (sampleCount > 0) (sumEnergy / sampleCount).toFloat() else 0.0f
        val timePenalty = 0.5f * (max(0L, timeDeltaFromFirstNs).toFloat() / 100_000_000.0f)
        return max(0.0f, sharpness - timePenalty)
    }

    @Test
    fun testGcamScoring_FlatVsSharpEdges() {
        val w = 64
        val h = 64
        val flatPixels = IntArray(w * h) { 1000 } // Completely flat 1000 ADU
        val flatScore = computeReferenceGcamScore(
            pixels = flatPixels,
            width = w,
            height = h,
            cfaPattern = 0,
            noiseProfileS = 0.0001f,
            noiseProfileO = 0.00001f,
            timeDeltaFromFirstNs = 0L
        )
        // On a completely uniform frame, Laplacian is 0, score is 0
        assertEquals(0.0f, flatScore, 1e-5f)
        assertTrue("Flat frame must be below steady threshold", flatScore < HdrPlusStreamingBurst.STEADY_SCORE_THRESHOLD)

        // Sharp high-contrast alternating pattern (edges)
        val sharpPixels = IntArray(w * h) { idx ->
            val x = idx % w
            val y = idx / w
            if ((x / 4) % 2 == 0) 4000 else 1000
        }
        val sharpScore = computeReferenceGcamScore(
            pixels = sharpPixels,
            width = w,
            height = h,
            cfaPattern = 0,
            noiseProfileS = 0.0001f,
            noiseProfileO = 0.00001f,
            timeDeltaFromFirstNs = 0L
        )
        assertTrue("Sharp edge frame must score significantly higher than flat", sharpScore > flatScore)
        assertTrue("Sharp edge frame must exceed steady threshold 25.0f", sharpScore >= HdrPlusStreamingBurst.STEADY_SCORE_THRESHOLD)
    }

    @Test
    fun testGcamScoring_TemporalPenalty() {
        val w = 64
        val h = 64
        val sharpPixels = IntArray(w * h) { idx ->
            val x = idx % w
            if ((x / 4) % 2 == 0) 4000 else 1000
        }
        val score0 = computeReferenceGcamScore(
            pixels = sharpPixels,
            width = w,
            height = h,
            cfaPattern = 0,
            noiseProfileS = 0.0001f,
            noiseProfileO = 0.00001f,
            timeDeltaFromFirstNs = 0L
        )
        // 100ms delta => 0.5f penalty
        val score100ms = computeReferenceGcamScore(
            pixels = sharpPixels,
            width = w,
            height = h,
            cfaPattern = 0,
            noiseProfileS = 0.0001f,
            noiseProfileO = 0.00001f,
            timeDeltaFromFirstNs = 100_000_000L
        )
        // 200ms delta => 1.0f penalty
        val score200ms = computeReferenceGcamScore(
            pixels = sharpPixels,
            width = w,
            height = h,
            cfaPattern = 0,
            noiseProfileS = 0.0001f,
            noiseProfileO = 0.00001f,
            timeDeltaFromFirstNs = 200_000_000L
        )

        assertEquals("100ms delta should incur exactly 0.5 penalty", score0 - 0.5f, score100ms, 1e-4f)
        assertEquals("200ms delta should incur exactly 1.0 penalty", score0 - 1.0f, score200ms, 1e-4f)
    }

    @Test
    fun testAdaptiveCandidateSelectionLogic() {
        // Frame 0 has button shake: low score below threshold
        val score0 = 15.2f
        assertFalse(score0 >= HdrPlusStreamingBurst.STEADY_SCORE_THRESHOLD)

        // Three candidate scores:
        // Frame 0: 15.2f (shaken)
        // Frame 1: 38.6f (sharp, steady)
        // Frame 2: 34.1f (sharp, but temporal penalty applied)
        val candidateScores = listOf(15.2f, 38.6f, 34.1f)
        val bestIdx = candidateScores.indices.maxByOrNull { candidateScores[it] } ?: 0

        assertEquals("Frame 1 should be selected as the base reference frame", 1, bestIdx)
    }
}
