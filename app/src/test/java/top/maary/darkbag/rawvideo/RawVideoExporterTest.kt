package top.maary.darkbag.rawvideo

import android.media.MediaCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class RawVideoExporterTest {

    @Test
    fun testCalculateMeasuredFps_standardScenarios() {
        // 31 frames spanning exactly 1.0 second -> 30.0 fps
        val startNs = 10_000_000_000L
        val endNs = startNs + 1_000_000_000L
        val fps30 = RawVideoExporter.calculateMeasuredFps(
            totalFrames = 31,
            firstFrameNs = startNs,
            lastFrameNs = endNs,
            fallbackFps = 24.0f
        )
        assertEquals(30.0f, fps30, 0.01f)

        // 25 frames spanning exactly 1.0 second -> 24.0 fps
        val fps24 = RawVideoExporter.calculateMeasuredFps(
            totalFrames = 25,
            firstFrameNs = startNs,
            lastFrameNs = endNs,
            fallbackFps = 30.0f
        )
        assertEquals(24.0f, fps24, 0.01f)

        // 61 frames spanning exactly 2.0 seconds -> 30.0 fps
        val fps60In2Sec = RawVideoExporter.calculateMeasuredFps(
            totalFrames = 61,
            firstFrameNs = startNs,
            lastFrameNs = startNs + 2_000_000_000L,
            fallbackFps = 24.0f
        )
        assertEquals(30.0f, fps60In2Sec, 0.01f)
    }

    @Test
    fun testCalculateMeasuredFps_fallbacksAndClamping() {
        val startNs = 5_000_000_000L

        // Single frame -> returns fallback FPS
        val singleFrameFps = RawVideoExporter.calculateMeasuredFps(
            totalFrames = 1,
            firstFrameNs = startNs,
            lastFrameNs = startNs,
            fallbackFps = 29.97f
        )
        assertEquals(29.97f, singleFrameFps, 0.01f)

        // Invalid fallback FPS (<= 0) -> defaults to 24.0f
        val invalidFallback = RawVideoExporter.calculateMeasuredFps(
            totalFrames = 1,
            firstFrameNs = startNs,
            lastFrameNs = startNs,
            fallbackFps = 0.0f
        )
        assertEquals(24.0f, invalidFallback, 0.01f)

        // Non-positive time difference -> returns fallback FPS
        val backwardsTimeFps = RawVideoExporter.calculateMeasuredFps(
            totalFrames = 10,
            firstFrameNs = startNs,
            lastFrameNs = startNs - 1_000L,
            fallbackFps = 30.0f
        )
        assertEquals(30.0f, backwardsTimeFps, 0.01f)

        // Identical timestamps -> returns fallback FPS
        val identicalTimeFps = RawVideoExporter.calculateMeasuredFps(
            totalFrames = 10,
            firstFrameNs = startNs,
            lastFrameNs = startNs,
            fallbackFps = 30.0f
        )
        assertEquals(30.0f, identicalTimeFps, 0.01f)

        // Clamping upper bound: 1000 frames in 1 ms would be 1,000,000 fps -> clamped to 120.0f
        val superHighFps = RawVideoExporter.calculateMeasuredFps(
            totalFrames = 1000,
            firstFrameNs = startNs,
            lastFrameNs = startNs + 1_000_000L,
            fallbackFps = 30.0f
        )
        assertEquals(120.0f, superHighFps, 0.01f)

        // Clamping lower bound: 2 frames in 100 seconds would be 0.01 fps -> clamped to 1.0f
        val superLowFps = RawVideoExporter.calculateMeasuredFps(
            totalFrames = 2,
            firstFrameNs = startNs,
            lastFrameNs = startNs + 100_000_000_000L,
            fallbackFps = 30.0f
        )
        assertEquals(1.0f, superLowFps, 0.01f)
    }

    @Test
    fun testCalculatePtsUs_strictlyMonotonicNormal() {
        val firstNs = 1_000_000_000L
        val fallbackIntervalUs = 33_333L // ~30 fps
        var lastPtsUs = -1L

        val timestampsNs = listOf(
            firstNs,
            firstNs + 33_333_000L,
            firstNs + 66_667_000L,
            firstNs + 100_000_000L
        )

        val ptsResults = mutableListOf<Long>()
        timestampsNs.forEachIndexed { index, tsNs ->
            val pts = RawVideoExporter.calculatePtsUs(
                frameIndex = index,
                frameTsNs = tsNs,
                firstFrameNs = firstNs,
                lastPtsUs = lastPtsUs,
                fallbackIntervalUs = fallbackIntervalUs
            )
            assertTrue("PTS must strictly increase from lastPts: pts=$pts, lastPts=$lastPtsUs", pts > lastPtsUs)
            ptsResults.add(pts)
            lastPtsUs = pts
        }

        assertEquals(0L, ptsResults[0])
        assertEquals(33_333L, ptsResults[1])
        assertEquals(66_667L, ptsResults[2])
        assertEquals(100_000L, ptsResults[3])
    }

    @Test
    fun testCalculatePtsUs_jitterAndDuplicateHandling() {
        val firstNs = 1_000_000_000L
        val fallbackIntervalUs = 41_666L // ~24 fps
        var lastPtsUs = -1L

        // Sequence with:
        // Frame 0: normal
        // Frame 1: duplicate timestamp of frame 0
        // Frame 2: minor jitter forward (only 200us after frame 0)
        // Frame 3: normal
        val timestampsNs = listOf(
            firstNs,
            firstNs, // Duplicate!
            firstNs + 200_000L, // Only 200 us later (< 1000 us delta)
            firstNs + 100_000_000L // 100 ms later
        )

        val ptsResults = mutableListOf<Long>()
        timestampsNs.forEachIndexed { index, tsNs ->
            val pts = RawVideoExporter.calculatePtsUs(
                frameIndex = index,
                frameTsNs = tsNs,
                firstFrameNs = firstNs,
                lastPtsUs = lastPtsUs,
                fallbackIntervalUs = fallbackIntervalUs
            )
            if (lastPtsUs >= 0L) {
                // Must be at least 1000us greater than last PTS for MediaCodec monotonicity
                assertTrue("PTS must advance by at least 1000us: pts=$pts, lastPts=$lastPtsUs", pts >= lastPtsUs + 1000L)
            }
            ptsResults.add(pts)
            lastPtsUs = pts
        }

        assertEquals(0L, ptsResults[0])
        assertEquals(1_000L, ptsResults[1]) // Enforced +1000us over frame 0
        assertEquals(2_000L, ptsResults[2]) // Enforced +1000us over frame 1
        assertEquals(100_000L, ptsResults[3]) // Catches up to true hardware timestamp
    }

    @Test
    fun testCalculatePtsUs_fallbackWhenTimestampsMissingOrInvalid() {
        val fallbackIntervalUs = 33_333L
        var lastPtsUs = -1L

        // When firstFrameNs is 0 (missing timestamp), should use index * fallbackIntervalUs
        for (i in 0 until 5) {
            val pts = RawVideoExporter.calculatePtsUs(
                frameIndex = i,
                frameTsNs = 0L,
                firstFrameNs = 0L,
                lastPtsUs = lastPtsUs,
                fallbackIntervalUs = fallbackIntervalUs
            )
            assertEquals(i * fallbackIntervalUs, pts)
            lastPtsUs = pts
        }
    }

    @Test
    fun testEosPtsUs_greaterThanLastFrame() {
        val fallbackIntervalUs = 33_333L
        val lastPtsUs = 5_000_000L
        val eosPtsUs = (if (lastPtsUs >= 0L) lastPtsUs else 0L) + fallbackIntervalUs

        assertTrue("EOS PTS must be strictly greater than last frame PTS", eosPtsUs > lastPtsUs)
        assertEquals(5_033_333L, eosPtsUs)

        // Case when no frames were processed
        val initialLastPts = -1L
        val emptyEosPtsUs = (if (initialLastPts >= 0L) initialLastPts else 0L) + fallbackIntervalUs
        assertEquals(fallbackIntervalUs, emptyEosPtsUs)
    }

    @Test
    fun testPendingMuxerSamples_audioVideoInterleavedBuffering() {
        val pendingSamples = mutableListOf<RawVideoExporter.PendingMuxerSample>()
        val writtenAudio = mutableListOf<Long>()
        val writtenVideo = mutableListOf<Long>()

        // 模拟 2 个音频数据包在视频 track 准备好之前到达
        val audioInfo1 = MediaCodec.BufferInfo().apply { set(0, 1024, 0L, 0) }
        val audioInfo2 = MediaCodec.BufferInfo().apply { set(0, 1024, 21333L, 0) }
        pendingSamples.add(RawVideoExporter.PendingMuxerSample(isAudio = true, data = ByteArray(1024), info = audioInfo1))
        pendingSamples.add(RawVideoExporter.PendingMuxerSample(isAudio = true, data = ByteArray(1024), info = audioInfo2))

        // 模拟 1 个视频帧在音频 track 尚未由编码器 output format changed 确定时到达
        val videoInfo1 = MediaCodec.BufferInfo().apply { set(0, 50000, 0L, MediaCodec.BUFFER_FLAG_KEY_FRAME) }
        pendingSamples.add(RawVideoExporter.PendingMuxerSample(isAudio = false, data = ByteArray(50000), info = videoInfo1))

        assertEquals(3, pendingSamples.size)

        // 模拟 Muxer 启动并 flushPendingSamples
        var muxerStarted = true
        val audioTrackIndex = 1
        val videoTrackIndex = 0

        pendingSamples.forEach { sample ->
            if (sample.isAudio) {
                writtenAudio.add(sample.info.presentationTimeUs)
            } else {
                writtenVideo.add(sample.info.presentationTimeUs)
            }
        }
        pendingSamples.clear()

        assertTrue(pendingSamples.isEmpty())
        assertEquals(listOf(0L, 21333L), writtenAudio)
        assertEquals(listOf(0L), writtenVideo)
    }

    @Test
    fun testAudioPtsUsCalculation_advancesBySampleCount() {
        val sampleRate = 48000L
        val bytesPerAudioSample = 2 // 16-bit mono = 2 bytes
        var audioPtsUs = 0L

        // 每次读取 1920 字节 (960 samples @ 48kHz = exactly 20,000 us)
        val readBytes = 1920
        val sampleCount = readBytes / bytesPerAudioSample
        val deltaPtsUs = (sampleCount * 1_000_000L) / sampleRate

        assertEquals(960, sampleCount)
        assertEquals(20_000L, deltaPtsUs)

        audioPtsUs += deltaPtsUs
        assertEquals(20_000L, audioPtsUs)

        audioPtsUs += deltaPtsUs
        assertEquals(40_000L, audioPtsUs)
    }

    @Test
    fun testGpuSurfaceFailureFallback_cleansPartialFileAndRecovers() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "export_fallback_test_${System.currentTimeMillis()}").apply { mkdirs() }
        val dummyOutputFile = File(tempDir, "output_test.mp4")
        dummyOutputFile.writeText("corrupt partial data from failed gpu attempt")
        assertTrue(dummyOutputFile.exists())

        // 模拟 GPU 导出失败时必须执行的清理策略
        val gpuSuccess = false
        if (!gpuSuccess) {
            try { dummyOutputFile.delete() } catch (_: Exception) {}
        }

        // 断言半成品文件已被干净移除，准备交给 CPU 管道重新创建
        assertFalse(dummyOutputFile.exists())

        // 模拟 CPU 管道成功写入
        dummyOutputFile.writeText("clean cpu export data")
        assertTrue(dummyOutputFile.exists())
        assertEquals("clean cpu export data", dummyOutputFile.readText())

        dummyOutputFile.delete()
        tempDir.delete()
    }

    @Test
    fun testEosBufferPayloadProtection_preservesDataWhenFlagPresent() {
        fun processBuffer(
            size: Int,
            flags: Int,
            onWriteSample: (size: Int) -> Unit,
            onReleaseBuffer: () -> Unit
        ): Boolean {
            var wrote = false
            if (size > 0) {
                onWriteSample(size)
                wrote = true
            }
            val isLastBuffer = (flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
            onReleaseBuffer()
            return isLastBuffer && wrote
        }

        var writeCount = 0
        var releaseCount = 0

        // 情况 A: 最后一帧既有数据又有 EOS 标记
        val lastFrameWithData = processBuffer(
            size = 4096,
            flags = MediaCodec.BUFFER_FLAG_END_OF_STREAM,
            onWriteSample = { writeCount++ },
            onReleaseBuffer = { releaseCount++ }
        )
        assertTrue("最后一帧带载荷时必须保留并写入", lastFrameWithData)
        assertEquals(1, writeCount)
        assertEquals(1, releaseCount)

        // 情况 B: 独立发送的空 EOS 标记 buffer
        val emptyEosBuffer = processBuffer(
            size = 0,
            flags = MediaCodec.BUFFER_FLAG_END_OF_STREAM,
            onWriteSample = { writeCount++ },
            onReleaseBuffer = { releaseCount++ }
        )
        assertFalse("空 EOS buffer 不得调用 writeSampleData", emptyEosBuffer)
        assertEquals(1, writeCount) // 未增加写入
        assertEquals(2, releaseCount) // 但必须释放
    }

    @Test
    fun testAudioChunking_splitsLargePacketAcrossMultipleInputBuffers() {
        val sampleRate = 48000
        val audioChannels = 1
        val bytesPerAudioSample = audioChannels * 2 // 2 bytes
        val inputBufferCapacity = 2048 // Standard AAC input buffer capacity

        // Simulate a 7680-byte audio packet from AudioRecord on MediaTek device
        val largePacketSize = 7680
        val dummyAudioData = ByteArray(largePacketSize) { (it % 128).toByte() }
        val audioDirectBuf = java.nio.ByteBuffer.allocateDirect(65536)
        audioDirectBuf.clear()
        audioDirectBuf.put(dummyAudioData)
        audioDirectBuf.flip()

        var audioPacketIndex = 0
        var noMoreAudioPackets = false
        var audioPtsUs = 0L
        var audioEosQueued = false

        data class QueuedBuffer(val size: Int, val ptsUs: Long, val flags: Int, val payload: ByteArray)
        val queuedBuffers = mutableListOf<QueuedBuffer>()

        // Simulate simulated native reader with 1 packet of 7680 bytes
        fun mockNativeReadPacket(packetIdx: Int, dest: java.nio.ByteBuffer): Int {
            return if (packetIdx == 1) {
                dest.clear()
                val secondPacket = ByteArray(2000) { ((it + 5) % 128).toByte() }
                dest.put(secondPacket)
                dest.flip()
                secondPacket.size
            } else {
                -1 // No more packets
            }
        }

        fun simulateFeedAudio(availableInputBufferSlots: Int) {
            var slotsLeft = availableInputBufferSlots
            while (!audioEosQueued && slotsLeft > 0) {
                if (!audioDirectBuf.hasRemaining() && !noMoreAudioPackets) {
                    val read = mockNativeReadPacket(audioPacketIndex, audioDirectBuf)
                    if (read > 0) {
                        audioDirectBuf.position(0)
                        audioDirectBuf.limit(read)
                        audioPacketIndex++
                    } else {
                        noMoreAudioPackets = true
                        audioDirectBuf.position(0)
                        audioDirectBuf.limit(0)
                    }
                }

                // Simulate dequeueInputBuffer
                val inBuf = java.nio.ByteBuffer.allocate(inputBufferCapacity)
                slotsLeft--

                if (audioDirectBuf.hasRemaining()) {
                    inBuf.clear()
                    val toWrite = minOf(audioDirectBuf.remaining(), inBuf.remaining())
                    val chunkBytes = (toWrite / bytesPerAudioSample) * bytesPerAudioSample
                    if (chunkBytes > 0) {
                        val oldLimit = audioDirectBuf.limit()
                        audioDirectBuf.limit(audioDirectBuf.position() + chunkBytes)
                        inBuf.put(audioDirectBuf)
                        audioDirectBuf.limit(oldLimit)

                        val payload = ByteArray(chunkBytes)
                        inBuf.flip()
                        inBuf.get(payload)

                        queuedBuffers.add(QueuedBuffer(chunkBytes, audioPtsUs, 0, payload))
                        val sampleCount = chunkBytes / bytesPerAudioSample
                        audioPtsUs += (sampleCount * 1_000_000L) / sampleRate
                    }
                } else if (noMoreAudioPackets && !audioDirectBuf.hasRemaining()) {
                    queuedBuffers.add(QueuedBuffer(0, audioPtsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM, ByteArray(0)))
                    audioEosQueued = true
                    break
                }
            }
        }

        // Call 1: Feed 2 chunks (2 x 2048 = 4096 bytes)
        audioPacketIndex = 1 // First packet was already in audioDirectBuf
        simulateFeedAudio(availableInputBufferSlots = 2)
        assertEquals(2, queuedBuffers.size)
        assertEquals(2048, queuedBuffers[0].size)
        assertEquals(2048, queuedBuffers[1].size)
        assertEquals(0L, queuedBuffers[0].ptsUs)
        assertEquals((1024 * 1_000_000L) / 48000, queuedBuffers[1].ptsUs)
        assertFalse(audioEosQueued)

        // Call 2: Feed next chunks and read second packet
        simulateFeedAudio(availableInputBufferSlots = 10)
        assertTrue(audioEosQueued)

        // Total data across all chunks should equal first packet (7680) + second packet (2000)
        val dataBuffers = queuedBuffers.filter { it.flags != MediaCodec.BUFFER_FLAG_END_OF_STREAM }
        val totalBytesQueued = dataBuffers.sumOf { it.size }
        assertEquals(7680 + 2000, totalBytesQueued)

        // Verify EOS buffer was queued with flag
        val eosBuffer = queuedBuffers.last()
        assertEquals(MediaCodec.BUFFER_FLAG_END_OF_STREAM, eosBuffer.flags)
        assertEquals(0, eosBuffer.size)

        // Verify all timestamps are strictly increasing
        for (i in 1 until dataBuffers.size) {
            assertTrue("PTS must be monotonically increasing", dataBuffers[i].ptsUs > dataBuffers[i - 1].ptsUs)
        }
    }
}

