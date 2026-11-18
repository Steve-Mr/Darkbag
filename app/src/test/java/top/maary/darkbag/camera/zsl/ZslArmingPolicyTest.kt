package top.maary.darkbag.camera.zsl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZslArmingPolicyTest {

    private fun sampleReport(
        available: Boolean = true,
        supportsHdr: Boolean = true,
        reason: ZslReason = ZslReason.NONE,
        frameBytes: Long = 24L * 1024 * 1024
    ) = ZslCapabilityReport(
        cameraId = "0",
        route = if (available) ZslRoute.RAW_RING else ZslRoute.NONE,
        supportsHdrPlusZsl = supportsHdr,
        reason = reason,
        rawWidth = 4000,
        rawHeight = 3000,
        frameBytes = frameBytes,
        minAeCompensationSteps = -6,
        aeCompensationStepEv = 0.33333334f,
        timestampRealtime = true
    )

    private fun sampleFacts(
        zslEnabled: Boolean = true,
        sessionActive: Boolean = true,
        isSupportedMode: Boolean = true,
        force60Fps: Boolean = false,
        isMultiCamera: Boolean = false,
        isManualExposure: Boolean = false,
        isFlash: Boolean = false,
        isThermal: Boolean = false,
        isHdrPlus: Boolean = true,
        report: ZslCapabilityReport? = sampleReport()
    ) = ZslArmingPolicy.ContextFacts(
        zslEnabled = zslEnabled,
        sessionActive = sessionActive,
        isSupportedCaptureMode = isSupportedMode,
        force60Fps = force60Fps,
        isMultiCamera = isMultiCamera,
        isManualExposure = isManualExposure,
        isFlashActive = isFlash,
        isPowerSaveOrThermalSevere = isThermal,
        isHdrPlusEnabled = isHdrPlus,
        capabilityReport = report
    )

    @Test
    fun testEvaluationExclusions() {
        assertEquals(ZslProfile.OFF, ZslArmingPolicy.evaluate(sampleFacts(zslEnabled = false)).profile)
        assertEquals("disabled", ZslArmingPolicy.evaluate(sampleFacts(zslEnabled = false)).disarmReason)

        assertEquals(ZslProfile.OFF, ZslArmingPolicy.evaluate(sampleFacts(sessionActive = false)).profile)
        assertEquals("session_closed", ZslArmingPolicy.evaluate(sampleFacts(sessionActive = false)).disarmReason)

        assertEquals(ZslProfile.OFF, ZslArmingPolicy.evaluate(sampleFacts(isSupportedMode = false)).profile)
        assertEquals("current_mode", ZslArmingPolicy.evaluate(sampleFacts(isSupportedMode = false)).disarmReason)

        assertEquals(ZslProfile.OFF, ZslArmingPolicy.evaluate(sampleFacts(force60Fps = true)).profile)
        assertEquals("force_60fps", ZslArmingPolicy.evaluate(sampleFacts(force60Fps = true)).disarmReason)

        assertEquals(ZslProfile.OFF, ZslArmingPolicy.evaluate(sampleFacts(isMultiCamera = true)).profile)
        assertEquals("multi_camera", ZslArmingPolicy.evaluate(sampleFacts(isMultiCamera = true)).disarmReason)

        assertEquals(ZslProfile.OFF, ZslArmingPolicy.evaluate(sampleFacts(isManualExposure = true)).profile)
        assertEquals("manual_exposure", ZslArmingPolicy.evaluate(sampleFacts(isManualExposure = true)).disarmReason)

        assertEquals(ZslProfile.OFF, ZslArmingPolicy.evaluate(sampleFacts(isFlash = true)).profile)
        assertEquals("flash", ZslArmingPolicy.evaluate(sampleFacts(isFlash = true)).disarmReason)

        assertEquals(ZslProfile.OFF, ZslArmingPolicy.evaluate(sampleFacts(isThermal = true)).profile)
        assertEquals("thermal", ZslArmingPolicy.evaluate(sampleFacts(isThermal = true)).disarmReason)
    }

    @Test
    fun testEvaluationCapabilityHandoff() {
        assertEquals("evaluating", ZslArmingPolicy.evaluate(sampleFacts(report = null)).disarmReason)

        val unavailReport = sampleReport(available = false, reason = ZslReason.NO_RAW)
        val unavailEval = ZslArmingPolicy.evaluate(sampleFacts(report = unavailReport))
        assertEquals(ZslProfile.OFF, unavailEval.profile)
        assertEquals("no_raw", unavailEval.disarmReason)

        val noHdrReport = sampleReport(available = true, supportsHdr = false)
        val noHdrEval = ZslArmingPolicy.evaluate(sampleFacts(isHdrPlus = true, report = noHdrReport))
        assertEquals(ZslProfile.OFF, noHdrEval.profile)
        assertEquals("hdr_unsupported", noHdrEval.disarmReason)

        val hdrEval = ZslArmingPolicy.evaluate(sampleFacts(isHdrPlus = true))
        assertEquals(ZslProfile.HDR_PLUS, hdrEval.profile)

        val singleEval = ZslArmingPolicy.evaluate(sampleFacts(isHdrPlus = false))
        assertEquals(ZslProfile.SINGLE_RAW, singleEval.profile)
    }

    @Test
    fun testMemoryLimiting() {
        val frameBytes = 20L * 1024 * 1024
        val config = ZslRingConfig.forHdrPlus(8, frameBytes, ZslRingMode.EAGER_COPY)!!

        // Not enough memory even for 1 frame above headroom (600MB)
        val limitedNull = ZslArmingPolicy.limitByMemory(config, 610L * 1024 * 1024)
        assertNull(limitedNull)

        // Memory for exactly 3 frames (600MB headroom + 65MB = 3 frames)
        val limited3 = ZslArmingPolicy.limitByMemory(config, 600L * 1024 * 1024 + 65L * 1024 * 1024)
        assertNotNull(limited3)
        assertEquals(3, limited3?.depth)

        // Abundant memory (e.g. 2GB) -> retains depth
        val abundant = ZslArmingPolicy.limitByMemory(config, 2048L * 1024 * 1024)
        assertNotNull(abundant)
        assertEquals(config.depth, abundant?.depth)
    }

    @Test
    fun testResolveHdrTargetEv() {
        val evMinus1 = ZslArmingPolicy.resolveHdrTargetEv("-1 EV", -6, 0.33333334f)
        assertEquals(-3, evMinus1.steps)
        assertTrue(evMinus1.digitalGain in 1.99f..2.01f)

        val evMinus2 = ZslArmingPolicy.resolveHdrTargetEv("-2 EV", -6, 0.33333334f)
        assertEquals(-6, evMinus2.steps)
        assertTrue(evMinus2.digitalGain in 3.99f..4.01f)

        val evDynamicExp = ZslArmingPolicy.resolveHdrTargetEv("Dynamic (Experimental)", -6, 0.33333334f)
        assertEquals(-3, evDynamicExp.steps)
        assertTrue(evDynamicExp.digitalGain in 1.99f..2.01f)

        val evZero = ZslArmingPolicy.resolveHdrTargetEv("Off", -6, 0.33333334f)
        assertEquals(0, evZero.steps)
        assertEquals(1.0f, evZero.digitalGain, 0.001f)
    }

    @Test
    fun testPlanArming() {
        val facts = sampleFacts(isHdrPlus = true)
        val abundantMem = 2048L * 1024 * 1024

        // Success HDR+
        val planHdr = ZslArmingPolicy.planArming(
            facts = facts, isRawReader = true, hasLut = true, lazyRing = true,
            lazyFailed = false, burstCount = 5, underexposureMode = "-1 EV", availMemBytes = abundantMem
        )
        assertEquals(ZslProfile.HDR_PLUS, planHdr.profile)
        assertNotNull(planHdr.ringConfig)
        assertEquals(ZslRingMode.LAZY_IMAGE, planHdr.ringConfig?.mode)
        assertEquals(-3, planHdr.evConfig?.steps)

        // Missing LUT Processor
        val planNoLut = ZslArmingPolicy.planArming(
            facts = facts, isRawReader = true, hasLut = false, lazyRing = true,
            lazyFailed = false, burstCount = 5, underexposureMode = "-1 EV", availMemBytes = abundantMem
        )
        assertEquals(ZslProfile.OFF, planNoLut.profile)
        assertEquals("lut_missing", planNoLut.disarmReason)

        // Missing RAW reader
        val planNoRaw = ZslArmingPolicy.planArming(
            facts = facts, isRawReader = false, hasLut = true, lazyRing = true,
            lazyFailed = false, burstCount = 5, underexposureMode = "-1 EV", availMemBytes = abundantMem
        )
        assertEquals(ZslProfile.OFF, planNoRaw.profile)
        assertEquals("reader_not_raw", planNoRaw.disarmReason)
    }
}
