package top.maary.darkbag.camera.zsl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZslCapabilityEvaluatorTest {

    private fun facts(
        api: Int = 30,
        raw: List<Pair<Int, Int>> = listOf(4096 to 3072),
        readSensor: Boolean = true,
        manualPost: Boolean = true,
        lowRam: Boolean = false,
        ae: IntRange? = -6..6,
        fps: List<IntRange> = listOf(15..30),
        capabilities: Set<Int> = setOf(32)
    ) = ZslCameraFacts(
        apiLevel = api,
        hardwareLevel = 3,
        capabilities = capabilities,
        rawSizes = raw,
        readSensorSettings = readSensor,
        manualPostProcessing = manualPost,
        timestampSource = 1,
        aeCompensationRange = ae,
        fpsRanges = fps,
        isLowRam = lowRam,
        availableMemoryBytes = 4L * 1024L * 1024L * 1024L
    )

    @Test
    fun rawRingAt12MpIsAvailableAndHdrPlusCapable() {
        val report = ZslCapabilityEvaluator.evaluate(facts())
        assertEquals(ZslRoute.RAW_RING, report.route)
        assertTrue(report.supportsHdrPlusZsl)
        assertEquals(5, report.maxPreRollFrames)
        assertEquals(4096, report.rawWidth)
        assertEquals(3072, report.rawHeight)
        assertEquals(15..30, report.hdrFpsRange)
    }

    @Test
    fun apiBelow26IsRejected() {
        val report = ZslCapabilityEvaluator.evaluate(facts(api = 25))
        assertEquals(ZslRoute.NONE, report.route)
        assertEquals(ZslReason.API_TOO_LOW, report.reason)
    }

    @Test
    fun noReadSensorSettingsIsRejected() {
        val report = ZslCapabilityEvaluator.evaluate(facts(readSensor = false))
        assertEquals(ZslRoute.NONE, report.route)
        assertEquals(ZslReason.NO_READ_SENSOR_SETTINGS, report.reason)
    }

    @Test
    fun rawOnlyAboveBudgetIsRejected() {
        val report = ZslCapabilityEvaluator.evaluate(facts(raw = listOf(8000 to 6000)))
        assertEquals(ZslRoute.NONE, report.route)
        assertEquals(ZslReason.RAW_TOO_LARGE, report.reason)
    }

    @Test
    fun smallerBinnedRawDoesNotRescueAFullResReader() {
        val report = ZslCapabilityEvaluator.evaluate(
            facts(raw = listOf(8000 to 6000, 4096 to 3072))
        )
        assertEquals(ZslRoute.NONE, report.route)
        assertEquals(ZslReason.RAW_TOO_LARGE, report.reason)
    }

    @Test
    fun missingRawCapabilityIsRejected() {
        val report = ZslCapabilityEvaluator.evaluate(facts(capabilities = emptySet()))
        assertEquals(ZslRoute.NONE, report.route)
        assertEquals(ZslReason.NO_RAW, report.reason)
    }

    @Test
    fun lowRamIsRejected() {
        val report = ZslCapabilityEvaluator.evaluate(facts(lowRam = true))
        assertEquals(ZslRoute.NONE, report.route)
        assertEquals(ZslReason.LOW_RAM, report.reason)
    }

    @Test
    fun rawRingCanStillDoSingleWithoutHdrPlusPrerequisites() {
        val report = ZslCapabilityEvaluator.evaluate(
            facts(manualPost = false, ae = 0..0, fps = listOf(30..30))
        )
        assertEquals(ZslRoute.RAW_RING, report.route)
        assertFalse(report.supportsHdrPlusZsl)
        assertNull(report.hdrFpsRange)
    }

    @Test
    fun privateReprocessingToRawIsDetectedAsOptionalRoute() {
        val report = ZslCapabilityEvaluator.evaluate(
            facts(
                raw = emptyList(),
                capabilities = emptySet(),
                readSensor = false
            ).copy(
                inputFormats = setOf(34),
                reprocessOutputFormats = setOf(32)
            )
        )
        assertEquals(ZslRoute.REPROCESS_RAW, report.route)
        assertTrue(report.supportsRawReprocess)
    }

    @Test
    fun ringConfigFollowsBurstCountAndMemory() {
        val frame12Mp = 4096L * 3072L * 2L
        val hdr5 = ZslRingConfig.forHdrPlus(5, frame12Mp)
        assertEquals(5, hdr5?.depth)
        val hdr8 = ZslRingConfig.forHdrPlus(8, frame12Mp)
        assertEquals(5, hdr8?.depth)
        assertEquals(3, ZslRingConfig.forSingle(frame12Mp)?.depth)
        assertNull(ZslRingConfig.forHdrPlus(8, 96L * 1024L * 1024L))
        assertEquals(1, ZslRingConfig.forSingle(96L * 1024L * 1024L)?.depth)
        assertEquals(8, ZslRingConfig.maxByBudget(16L * 1024L * 1024L))
    }

    @Test
    fun routeKeysRoundTrip() {
        assertEquals(ZslRoute.RAW_RING, ZslRoute.fromKey("raw_ring"))
        assertEquals(ZslRoute.NONE, ZslRoute.fromKey("bogus"))
        assertEquals(ZslReason.NO_RAW, ZslReason.fromKey("no_raw"))
    }
}
