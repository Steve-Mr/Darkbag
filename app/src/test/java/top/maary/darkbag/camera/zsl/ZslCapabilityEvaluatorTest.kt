package top.maary.darkbag.camera.zsl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.maary.darkbag.camera.motion.MotionLevel

class ZslCapabilityEvaluatorTest {

    private fun facts(
        api: Int = 30,
        raw: List<Pair<Int, Int>> = listOf(4096 to 3072),
        readSensor: Boolean = true,
        manualPost: Boolean = true,
        lowRam: Boolean = false,
        ae: IntRange? = -6..6,
        aeStep: Float = 1f / 3f,
        fps: List<IntRange> = listOf(15..30),
        capabilities: Set<Int> = setOf(3, 5, 2)
    ) = ZslCameraFacts(
        apiLevel = api,
        hardwareLevel = 3,
        capabilities = capabilities,
        rawSizes = raw,
        readSensorSettings = readSensor,
        manualPostProcessing = manualPost,
        timestampSource = 1,
        aeCompensationRange = ae,
        aeCompensationStepEv = aeStep,
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
    fun rawOutputSizesWithoutCapabilityBitAreAccepted() {
        // Realistic vendor-HAL fixture: READ_SENSOR_SETTINGS(5) and
        // MANUAL_POST_PROCESSING(2) are declared, but RAW(3) is missing while
        // RAW_SENSOR output sizes (and DNG capture) still work.
        val report = ZslCapabilityEvaluator.evaluate(
            facts(capabilities = setOf(5, 2))
        )
        assertEquals(ZslRoute.RAW_RING, report.route)
        assertTrue(report.supportsHdrPlusZsl)
    }

    @Test
    fun missingRawOutputSizesIsRejected() {
        val report = ZslCapabilityEvaluator.evaluate(
            facts(raw = emptyList(), capabilities = setOf(5, 2))
        )
        assertEquals(ZslRoute.NONE, report.route)
        assertEquals(ZslReason.NO_RAW, report.reason)
    }

    @Test
    fun rawCapabilityWithoutOutputSizesIsRejected() {
        val report = ZslCapabilityEvaluator.evaluate(
            facts(raw = emptyList(), capabilities = setOf(3, 5, 2))
        )
        assertEquals(ZslRoute.NONE, report.route)
        assertEquals(ZslReason.NO_RAW, report.reason)
    }

    @Test
    fun rawOutputSizesWithoutReadSensorSettingsAreRejected() {
        val report = ZslCapabilityEvaluator.evaluate(
            facts(capabilities = setOf(3, 2), readSensor = false)
        )
        assertEquals(ZslRoute.NONE, report.route)
        assertEquals(ZslReason.NO_READ_SENSOR_SETTINGS, report.reason)
    }

    @Test
    fun rawSizesEmptyWithCapabilityAndReprocessingUsesReprocessRoute() {
        val report = ZslCapabilityEvaluator.evaluate(
            facts(raw = emptyList(), capabilities = setOf(3, 5, 2)).copy(
                inputFormats = setOf(34),
                reprocessOutputFormats = setOf(32)
            )
        )
        assertEquals(ZslRoute.REPROCESS_RAW, report.route)
        assertTrue(report.supportsRawReprocess)
    }

    @Test
    fun lowRamIsRejected() {
        val report = ZslCapabilityEvaluator.evaluate(facts(lowRam = true))
        assertEquals(ZslRoute.NONE, report.route)
        assertEquals(ZslReason.LOW_RAM, report.reason)
    }

    @Test
    fun reportsActualAeStepAndUsesEvForHdrAdmission() {
        val oneStopHalfStep = ZslCapabilityEvaluator.evaluate(
            facts(ae = -4..4, aeStep = 0.5f)
        )
        assertEquals(0.5f, oneStopHalfStep.aeCompensationStepEv, 0.001f)
        assertTrue(oneStopHalfStep.supportsHdrPlusZsl)

        val tooLittleRange = ZslCapabilityEvaluator.evaluate(
            facts(ae = -1..1, aeStep = 0.5f)
        )
        assertFalse(tooLittleRange.supportsHdrPlusZsl)
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

class ZslAdaptivePolicyTest {
    @Test
    fun adaptiveUsesActualStablePreRollAsTarget() {
        assertEquals(4, ZslAdaptivePolicy.hdrTargetFrameCount(adaptive = true, stableFrames = 4, requestedBurst = 5))
        assertEquals(3, ZslAdaptivePolicy.hdrTargetFrameCount(adaptive = true, stableFrames = 3, requestedBurst = 8))
        assertEquals(0, ZslAdaptivePolicy.hdrTargetFrameCount(adaptive = true, stableFrames = 0, requestedBurst = 5))
    }

    @Test
    fun qualityModeKeepsConfiguredBurstTarget() {
        assertEquals(5, ZslAdaptivePolicy.hdrTargetFrameCount(adaptive = false, stableFrames = 4, requestedBurst = 5))
        assertEquals(8, ZslAdaptivePolicy.hdrTargetFrameCount(adaptive = false, stableFrames = 5, requestedBurst = 8))
        assertEquals(8, ZslAdaptivePolicy.hdrTargetFrameCount(adaptive = false, stableFrames = 1, requestedBurst = 8))
    }

    @Test
    fun instantSingleOnlyForAdaptiveStabilityFailures() {
        assertTrue(ZslAdaptivePolicy.shouldUseInstantSingle(true, "no_stable_run"))
        assertTrue(ZslAdaptivePolicy.shouldUseInstantSingle(true, "missing_metadata"))
        assertTrue(ZslAdaptivePolicy.shouldUseInstantSingle(true, "motion_fast_single"))
        assertFalse(ZslAdaptivePolicy.shouldUseInstantSingle(true, "pre_roll_short"))
        assertFalse(ZslAdaptivePolicy.shouldUseInstantSingle(true, "low_light"))
        assertFalse(ZslAdaptivePolicy.shouldUseInstantSingle(false, "no_stable_run"))
        assertFalse(ZslAdaptivePolicy.shouldUseInstantSingle(false, "motion_fast_single"))
    }

    @Test
    fun shouldFallbackSingleOnMotionPolicy() {
        // Fast motion in bright light triggers single fallback only when adaptive is enabled
        assertTrue(ZslAdaptivePolicy.shouldFallbackSingleOnMotion(MotionLevel.FAST, adaptiveEnabled = true, isLowLight = false))
        assertFalse(ZslAdaptivePolicy.shouldFallbackSingleOnMotion(MotionLevel.FAST, adaptiveEnabled = false, isLowLight = false))
        assertFalse(ZslAdaptivePolicy.shouldFallbackSingleOnMotion(MotionLevel.FAST, adaptiveEnabled = true, isLowLight = true))

        // Still and moderate motion never trigger single fallback
        assertFalse(ZslAdaptivePolicy.shouldFallbackSingleOnMotion(MotionLevel.STILL, adaptiveEnabled = true, isLowLight = false))
        assertFalse(ZslAdaptivePolicy.shouldFallbackSingleOnMotion(MotionLevel.STILL, adaptiveEnabled = true, isLowLight = true))
        assertFalse(ZslAdaptivePolicy.shouldFallbackSingleOnMotion(MotionLevel.MODERATE, adaptiveEnabled = true, isLowLight = false))
        assertFalse(ZslAdaptivePolicy.shouldFallbackSingleOnMotion(MotionLevel.MODERATE, adaptiveEnabled = true, isLowLight = true))
    }

    @Test
    fun resolveAdaptiveBurstCountRules() {
        // Strict mode (!adaptive): always requestedBurst (coerced at least MIN_HDR_FRAMES = 3)
        assertEquals(8, ZslAdaptivePolicy.resolveAdaptiveBurstCount(8, MotionLevel.STILL, adaptiveEnabled = false, isLowLight = false))
        assertEquals(8, ZslAdaptivePolicy.resolveAdaptiveBurstCount(8, MotionLevel.MODERATE, adaptiveEnabled = false, isLowLight = false))
        assertEquals(8, ZslAdaptivePolicy.resolveAdaptiveBurstCount(8, MotionLevel.FAST, adaptiveEnabled = false, isLowLight = false))
        assertEquals(5, ZslAdaptivePolicy.resolveAdaptiveBurstCount(5, MotionLevel.FAST, adaptiveEnabled = false, isLowLight = true))
        assertEquals(3, ZslAdaptivePolicy.resolveAdaptiveBurstCount(2, MotionLevel.STILL, adaptiveEnabled = false, isLowLight = false))

        // Adaptive mode:
        // STILL: requestedBurst
        assertEquals(8, ZslAdaptivePolicy.resolveAdaptiveBurstCount(8, MotionLevel.STILL, adaptiveEnabled = true, isLowLight = false))
        assertEquals(5, ZslAdaptivePolicy.resolveAdaptiveBurstCount(5, MotionLevel.STILL, adaptiveEnabled = true, isLowLight = true))

        // MODERATE: drops to 3 frames
        assertEquals(3, ZslAdaptivePolicy.resolveAdaptiveBurstCount(8, MotionLevel.MODERATE, adaptiveEnabled = true, isLowLight = false))
        assertEquals(3, ZslAdaptivePolicy.resolveAdaptiveBurstCount(5, MotionLevel.MODERATE, adaptiveEnabled = true, isLowLight = true))

        // FAST in low-light: drops to 3 frames (preserves SNR)
        assertEquals(3, ZslAdaptivePolicy.resolveAdaptiveBurstCount(8, MotionLevel.FAST, adaptiveEnabled = true, isLowLight = true))

        // FAST in bright light:
        // allowSingleFallback = false: drops to 3 frames
        assertEquals(3, ZslAdaptivePolicy.resolveAdaptiveBurstCount(8, MotionLevel.FAST, adaptiveEnabled = true, isLowLight = false, allowSingleFallback = false))
        // allowSingleFallback = true: drops to 1 frame
        assertEquals(1, ZslAdaptivePolicy.resolveAdaptiveBurstCount(8, MotionLevel.FAST, adaptiveEnabled = true, isLowLight = false, allowSingleFallback = true))
    }

    @Test
    fun lowLightGateRequiresHighIsoAndCappedExposure() {
        assertTrue(ZslAdaptivePolicy.isLowLight(iso = 2560, exposureTimeNs = 66_666_667L, isoUpper = 3200, exposureUpperNs = 200_000_000L))
        assertTrue(ZslAdaptivePolicy.isLowLight(iso = 3000, exposureTimeNs = 50_000_000L, isoUpper = 3200, exposureUpperNs = 50_000_000L))
        assertFalse(ZslAdaptivePolicy.isLowLight(iso = 2400, exposureTimeNs = 100_000_000L, isoUpper = 3200, exposureUpperNs = 200_000_000L))
        assertFalse(ZslAdaptivePolicy.isLowLight(iso = 3200, exposureTimeNs = 33_000_000L, isoUpper = 3200, exposureUpperNs = 200_000_000L))
        assertFalse(ZslAdaptivePolicy.isLowLight(iso = null, exposureTimeNs = 100_000_000L, isoUpper = 3200, exposureUpperNs = 200_000_000L))
    }
}
