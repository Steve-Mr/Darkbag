package top.maary.darkbag.pipeline.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.maary.darkbag.models.CaptureMetadata
import top.maary.darkbag.models.EditConfig
import top.maary.darkbag.processor.HdrPlusRequest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class CaptureTaskSpecTest {

    @Test
    fun testDomainAggregates_BidirectionalConversionWithHdrPlusRequest() {
        val hw = HardwareProfile(
            lensId = "0",
            cfaPattern = 1,
            whiteLevel = 1023,
            blackLevelPattern = intArrayOf(64, 64, 64, 64),
            dynamicBlackLevel = floatArrayOf(64.1f, 64.2f, 64.0f, 64.3f),
            colorMatrix1 = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
            colorMatrix2 = floatArrayOf(1.1f, 0f, 0f, 0f, 1.1f, 0f, 0f, 0f, 1.1f),
            forwardMatrix1 = floatArrayOf(0.9f, 0f, 0f, 0f, 0.9f, 0f, 0f, 0f, 0.9f),
            forwardMatrix2 = floatArrayOf(0.95f, 0f, 0f, 0f, 0.95f, 0f, 0f, 0f, 0.95f),
            calibrationIlluminant1 = 21,
            calibrationIlluminant2 = 17,
            activeArray = intArrayOf(0, 0, 3072, 4096),
            noiseProfile = doubleArrayOf(0.001, 0.0002),
            useSensorColorMatrix = true
        )

        val frameMeta = CaptureFrameMetadata(
            timestamp = 1728394800000L,
            iso = 400,
            exposureTimeNs = 20_000_000L,
            lensShadingMap = floatArrayOf(1f, 1f, 1f, 1f),
            lensShadingRows = 2,
            lensShadingCols = 2,
            whiteBalance = floatArrayOf(2.1f, 1.0f, 1.0f, 1.6f),
            ccm = floatArrayOf(1.8f, -0.6f, -0.2f, -0.3f, 1.5f, -0.2f, 0.0f, -0.4f, 1.4f),
            ccmAlt = null,
            exportMatrixAB = false,
            neutralColorPoint = floatArrayOf(0.3127f, 0.3290f),
            postRawSensitivityBoost = 1.25f,
            captureMetadata = CaptureMetadata(iso = 400, exposureTime = 20_000_000L, dateTimeOriginal = 1728394800000L)
        )

        val recipe = RenderRecipe(
            targetLogIndex = 1,
            lutPath = "/data/user/0/top.maary.darkbag/files/luts/film.cube",
            digitalGain = 1.5f,
            exposure = 0.3f,
            contrast = 0.1f,
            saturation = -0.05f,
            highlights = -0.2f,
            shadows = 0.15f,
            whites = 0.0f,
            blacks = -0.1f,
            colorEngineMode = 2,
            faithfulHighlights = false,
            enableMemoryColor = true,
            editConfig = EditConfig(exposure = 0.3f, contrast = 0.1f, saturation = -0.05f)
        )

        val originalSpec = CaptureTaskSpec(
            taskId = "test-task-uuid-1234",
            width = 4096,
            height = 3072,
            orientation = 90,
            zoomFactor = 2.0f,
            mirror = false,
            isSingleFrame = false,
            hardwareProfile = hw,
            frameMetadata = frameMeta,
            renderRecipe = recipe,
            timing = null,
            dngCompressionMode = 1,
            rawOutputType = 0,
            fusionMode = 2,
            baseName = "DBAG_TEST_001",
            fullResJpgPath = "/cache/test_full.jpg",
            linearDngPath = "/cache/test_linear.dng",
            saveJpg = true,
            saveRaw = true
        )

        // Spec -> HdrPlusRequest
        val request = HdrPlusRequest.fromSpec(originalSpec)
        assertEquals("test-task-uuid-1234", request.requestId)
        assertEquals(4096, request.width)
        assertEquals(3072, request.height)
        assertEquals(90, request.orientation)
        assertEquals(1023, request.whiteLevel)
        assertEquals(1, request.cfaPattern)
        assertEquals(1.5f, request.digitalGain, 0.001f)
        assertEquals(2.0f, request.zoomFactor, 0.001f)
        assertEquals("/cache/test_full.jpg", request.fullResJpgPath)
        assertEquals(2, request.fusionMode)

        // HdrPlusRequest -> Spec (Round-trip)
        val roundTripSpec = request.toSpec()
        assertNotNull(roundTripSpec)
        assertEquals(originalSpec.taskId, roundTripSpec.taskId)
        assertEquals(originalSpec.width, roundTripSpec.width)
        assertEquals(originalSpec.height, roundTripSpec.height)
        assertEquals(originalSpec.hardwareProfile.whiteLevel, roundTripSpec.hardwareProfile.whiteLevel)
        assertEquals(originalSpec.hardwareProfile.cfaPattern, roundTripSpec.hardwareProfile.cfaPattern)
        assertEquals(originalSpec.renderRecipe.digitalGain, roundTripSpec.renderRecipe.digitalGain, 0.001f)
        assertEquals(originalSpec.renderRecipe.lutPath, roundTripSpec.renderRecipe.lutPath)
        assertEquals(originalSpec.frameMetadata.iso, roundTripSpec.frameMetadata.iso)
    }

    @Test
    fun testHardwareProfile_ValueEquality() {
        val hw1 = HardwareProfile(
            lensId = "0",
            cfaPattern = 0,
            whiteLevel = 1023,
            blackLevelPattern = intArrayOf(64, 64, 64, 64)
        )
        val hw2 = HardwareProfile(
            lensId = "0",
            cfaPattern = 0,
            whiteLevel = 1023,
            blackLevelPattern = intArrayOf(64, 64, 64, 64)
        )
        assertEquals(hw1, hw2)
        assertEquals(hw1.hashCode(), hw2.hashCode())
    }
}
