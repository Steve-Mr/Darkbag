package top.maary.darkbag.zsl.probe

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.maary.darkbag.camera.zsl.RingConfig
import top.maary.darkbag.camera.zsl.ZslMode
import top.maary.darkbag.camera.zsl.ZslPolicy
import top.maary.darkbag.camera.zsl.probe.ZslProbeFailure
import top.maary.darkbag.camera.zsl.probe.ZslProbeReport
import top.maary.darkbag.camera.zsl.probe.ZslProbeSample
import top.maary.darkbag.camera.zsl.probe.ZslProbeVerdict

/**
 * 报告编解码（§3.8.5）：JSON 往返、缺损字段容错（绝不抛）、版本/设备/镜头/尺寸失效判定。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ZslProbeReportTest {

    private fun sample(
        rawFrameCount: Int = 120,
        rawFps: Float = 29.5f,
        gapP50Ms: Float = 33.5f,
        gapP95Ms: Float = 45.25f,
        gapMaxMs: Float = 80f,
        ageP50Ms: Float = 40.5f,
        ageP95Ms: Float = 60.25f,
        copyP50Ms: Float = 2.5f,
        copyP95Ms: Float = 4.75f,
        previewFpsBaseline: Float = 30f,
        previewFpsDual: Float = 27.5f,
        previewDropRatio: Float = 0.083f,
        metadataHitRate: Float = 0.995f,
        warmupMs: Long? = 120L,
        thermalStatusBefore: Int? = 0,
        thermalStatusAfter: Int? = 1,
        memoryDeltaBytes: Long = -1_234_567L,
        captureFailures: Int = 1
    ) = ZslProbeSample(
        rawFrameCount = rawFrameCount,
        rawFps = rawFps,
        gapP50Ms = gapP50Ms,
        gapP95Ms = gapP95Ms,
        gapMaxMs = gapMaxMs,
        ageP50Ms = ageP50Ms,
        ageP95Ms = ageP95Ms,
        copyP50Ms = copyP50Ms,
        copyP95Ms = copyP95Ms,
        previewFpsBaseline = previewFpsBaseline,
        previewFpsDual = previewFpsDual,
        previewDropRatio = previewDropRatio,
        metadataHitRate = metadataHitRate,
        warmupMs = warmupMs,
        thermalStatusBefore = thermalStatusBefore,
        thermalStatusAfter = thermalStatusAfter,
        memoryDeltaBytes = memoryDeltaBytes,
        captureFailures = captureFailures
    )

    private fun report(
        version: Int = ZslProbeReport.SCHEMA_VERSION,
        verdict: ZslProbeVerdict = ZslProbeVerdict.SUPPORTED,
        failures: List<ZslProbeFailure> = listOf(ZslProbeFailure.RAW_JITTER, ZslProbeFailure.COPY_SLOW),
        sample: ZslProbeSample = sample(),
        ringConfig: RingConfig = RingConfig(3, 24, 2)
    ) = ZslProbeReport(
        version = version,
        timestampMs = 1_700_000_000_123L,
        deviceFingerprint = "Google/Pixel 8/14",
        lensId = "0",
        rawWidth = 4000,
        rawHeight = 3000,
        verdict = verdict,
        failures = failures,
        sample = sample,
        ringConfig = ringConfig
    )

    @Test
    fun testJsonRoundTripPreservesAllFields() {
        val original = report()

        val decoded = ZslProbeReport.fromJson(original.toJson())

        assertNotNull(decoded)
        assertEquals(original, decoded)
        assertEquals(ZslProbeReport.SCHEMA_VERSION, decoded!!.version)
        assertEquals(listOf(ZslProbeFailure.RAW_JITTER, ZslProbeFailure.COPY_SLOW), decoded.failures)
        assertEquals(1_700_000_000_123L, decoded.timestampMs)
        assertEquals(4000, decoded.rawWidth)
        assertEquals(3000, decoded.rawHeight)
    }

    @Test
    fun testWarmupNullRoundTripsAsNull() {
        val original = report(verdict = ZslProbeVerdict.UNSUPPORTED, sample = sample(warmupMs = null))

        val decoded = ZslProbeReport.fromJson(original.toJson())

        assertNotNull(decoded)
        assertNull(decoded!!.sample.warmupMs)
        assertEquals(original.sample, decoded.sample)
    }

    @Test
    fun testNullOrBlankJsonReturnsNull() {
        assertNull(ZslProbeReport.fromJson(null))
        assertNull(ZslProbeReport.fromJson(""))
        assertNull(ZslProbeReport.fromJson("   "))
    }

    @Test
    fun testMalformedJsonReturnsNullWithoutThrowing() {
        assertNull(ZslProbeReport.fromJson("{not-json"))
        assertNull(ZslProbeReport.fromJson("[]"))
        assertNull(ZslProbeReport.fromJson("null"))
        assertNull(ZslProbeReport.fromJson("{\"version\":1,\"verdict\":"))
    }

    @Test
    fun testVersionMismatchReturnsNull() {
        val future = report(version = ZslProbeReport.SCHEMA_VERSION + 1).toJson()

        assertNull(ZslProbeReport.fromJson(future))
    }

    @Test
    fun testMissingOrUnknownVerdictReturnsNull() {
        val missing = JSONObject()
            .put("version", 2)
            .put("sample", JSONObject())
            .toString()
        assertNull(ZslProbeReport.fromJson(missing))

        val unknown = JSONObject()
            .put("version", 2)
            .put("verdict", "MAYBE")
            .put("sample", JSONObject())
            .toString()
        assertNull(ZslProbeReport.fromJson(unknown))
    }

    @Test
    fun testMissingSampleObjectReturnsNull() {
        val raw = JSONObject()
            .put("version", 2)
            .put("verdict", "SUPPORTED")
            .toString()

        assertNull(ZslProbeReport.fromJson(raw))
    }

    @Test
    fun testMissingLeafFieldsFallBackToDefaults() {
        // 取 UNSUPPORTED：正向 verdict 缺合法 ring 会被判无效（F4），那样就测不到叶子字段兜底。
        val raw = JSONObject()
            .put("version", 2)
            .put("verdict", "UNSUPPORTED")
            .put("sample", JSONObject())
            .toString()

        val decoded = ZslProbeReport.fromJson(raw)

        assertNotNull(decoded)
        assertEquals(0, decoded!!.sample.rawFrameCount)
        assertEquals(0f, decoded.sample.rawFps, 0f)
        assertEquals(0f, decoded.sample.gapP95Ms, 0f)
        assertEquals(0L, decoded.sample.memoryDeltaBytes)
        assertNull(decoded.sample.warmupMs)
        assertTrue(decoded.failures.isEmpty())
        assertEquals(RingConfig.DISABLED, decoded.ringConfig)
        assertEquals("", decoded.deviceFingerprint)
    }

    @Test
    fun testUnknownFailureNamesAreIgnoredButKnownOnesSurvive() {
        val raw = JSONObject(report().toJson())
            .put("failures", org.json.JSONArray(listOf("RAW_STALL", "FUTURE_CODE", "THERMAL_RISK")))
            .toString()

        val decoded = ZslProbeReport.fromJson(raw)

        assertNotNull(decoded)
        assertEquals(
            listOf(ZslProbeFailure.RAW_STALL, ZslProbeFailure.THERMAL_RISK),
            decoded!!.failures
        )
    }

    @Test
    fun testIsApplicableAcceptsMatchingContext() {
        assertTrue(
            ZslProbeReport.isApplicable(
                report = report(),
                deviceFingerprint = "Google/Pixel 8/14",
                lensId = "0",
                rawWidth = 4000,
                rawHeight = 3000
            )
        )
    }

    @Test
    fun testIsApplicableRejectsFingerprintLensSizeAndVersionMismatches() {
        val report = report()

        assertFalse(
            ZslProbeReport.isApplicable(report, "Other/Device/13", "0", 4000, 3000)
        )
        assertFalse(
            ZslProbeReport.isApplicable(report, "Google/Pixel 8/14", "1", 4000, 3000)
        )
        assertFalse(
            ZslProbeReport.isApplicable(report, "Google/Pixel 8/14", "0", 4001, 3000)
        )
        assertFalse(
            ZslProbeReport.isApplicable(report, "Google/Pixel 8/14", "0", 4000, 3001)
        )
        assertFalse(
            ZslProbeReport.isApplicable(
                report.copy(version = ZslProbeReport.SCHEMA_VERSION + 1),
                "Google/Pixel 8/14",
                "0",
                4000,
                3000
            )
        )
        assertFalse(
            ZslProbeReport.isApplicable(null, "Google/Pixel 8/14", "0", 4000, 3000)
        )
    }

    @Test
    fun testToJsonIsSingleLineAndCarriesSchemaVersion() {
        val raw = report().toJson()

        assertFalse(raw.contains("\n"))
        assertFalse(raw.contains("\r"))
        val json = JSONObject(raw)
        assertEquals(ZslProbeReport.SCHEMA_VERSION, json.getInt("version"))
        assertEquals("SUPPORTED", json.getString("verdict"))
        assertTrue(json.has("sample"))
        assertTrue(json.has("ringConfig"))
        assertTrue(json.getJSONArray("failures").length() == 2)
    }

    // ---------------------------------------------------------------------
    // S1-B1 F4：正向 verdict 必须携带合法推荐环
    // ---------------------------------------------------------------------

    private fun withoutRing(report: ZslProbeReport): String =
        JSONObject(report.toJson()).apply { remove("ringConfig") }.toString()

    @Test
    fun testPositiveVerdictWithoutRingConfigDecodesToNull() {
        assertNull(ZslProbeReport.fromJson(withoutRing(report(verdict = ZslProbeVerdict.SUPPORTED))))
        assertNull(ZslProbeReport.fromJson(withoutRing(report(verdict = ZslProbeVerdict.RECOMMENDED))))
    }

    @Test
    fun testPositiveVerdictWithZeroedRingDimensionsDecodesToNull() {
        val zeroDepth = report(verdict = ZslProbeVerdict.SUPPORTED, ringConfig = RingConfig(0, 24, 2))
        val zeroFps = report(verdict = ZslProbeVerdict.RECOMMENDED, ringConfig = RingConfig(3, 0, 2))
        val zeroFrames = report(verdict = ZslProbeVerdict.SUPPORTED, ringConfig = RingConfig(3, 24, 0))

        assertNull(ZslProbeReport.fromJson(zeroDepth.toJson()))
        assertNull(ZslProbeReport.fromJson(zeroFps.toJson()))
        assertNull(ZslProbeReport.fromJson(zeroFrames.toJson()))
    }

    @Test
    fun testUnsupportedVerdictIsAllowedToCarryDisabledRing() {
        val unsupported = report(verdict = ZslProbeVerdict.UNSUPPORTED, ringConfig = RingConfig.DISABLED)

        assertNotNull(ZslProbeReport.fromJson(withoutRing(unsupported)))
        assertNotNull(ZslProbeReport.fromJson(unsupported.toJson()))
    }

    @Test
    fun testInvalidRingReportIsNotApplicableAndPolicyFallsBackToDisabled() {
        val invalid = report(verdict = ZslProbeVerdict.SUPPORTED, ringConfig = RingConfig(0, 24, 2))

        assertFalse(
            ZslProbeReport.isApplicable(invalid, "Google/Pixel 8/14", "0", 4000, 3000)
        )
        assertEquals(ZslPolicy.Disabled, ZslPolicy.fromReport(invalid, enabledByUser = true))
        assertEquals(ZslMode.DISABLED, ZslPolicy.fromReport(invalid, enabledByUser = true).mode)
        // 合法环仍被采信，确保上面的拒绝不是"一律禁用"
        assertEquals(
            ZslMode.AUTO,
            ZslPolicy.fromReport(report(verdict = ZslProbeVerdict.SUPPORTED), enabledByUser = true).mode
        )
    }

    @Test
    fun testThermalUnknownRoundTripsAsNull() {
        val original = report(
            verdict = ZslProbeVerdict.SUPPORTED,
            sample = sample(thermalStatusBefore = null, thermalStatusAfter = null)
        )

        val decoded = ZslProbeReport.fromJson(original.toJson())

        assertNotNull(decoded)
        assertNull(decoded!!.sample.thermalStatusBefore)
        assertNull(decoded.sample.thermalStatusAfter)
        assertEquals(original.sample, decoded.sample)
    }

    // ---------------------------------------------------------------------
    // S1-B2 R3：schema 升到 2（新增 PRECONDITION_FAILED / RAW_UNSUPPORTED 失败码）
    // ---------------------------------------------------------------------

    @Test
    fun testSchemaVersionIsTwoAndWrittenIntoSavedJson() {
        assertEquals(2, ZslProbeReport.SCHEMA_VERSION)

        val json = JSONObject(report().toJson())

        assertEquals(2, json.getInt("version"))
    }

    @Test
    fun testVersionOneJsonIsRejected() {
        val downgraded = JSONObject(report().toJson()).put("version", 1).toString()

        assertNull(ZslProbeReport.fromJson(downgraded))
    }

    @Test
    fun testPreconditionAndRawUnsupportedFailureCodesRoundTrip() {
        listOf(ZslProbeFailure.PRECONDITION_FAILED, ZslProbeFailure.RAW_UNSUPPORTED).forEach { code ->
            val original = report(
                verdict = ZslProbeVerdict.UNSUPPORTED,
                failures = listOf(code),
                ringConfig = RingConfig.DISABLED
            )

            val decoded = ZslProbeReport.fromJson(original.toJson())

            assertNotNull("$code must survive JSON round trip", decoded)
            assertEquals(listOf(code), decoded!!.failures)
        }
    }

    @Test
    fun testRawUnsupportedReportWithEmptySampleRoundTripsAsApplicable() {
        // Runner 的"无 RAW 能力"报告（R3）：全零 sample + DISABLED 环 + RAW_UNSUPPORTED
        val original = ZslProbeReport(
            version = ZslProbeReport.SCHEMA_VERSION,
            timestampMs = 1_700_000_000_123L,
            deviceFingerprint = "Google/Pixel 8/14",
            lensId = "0",
            rawWidth = 0,
            rawHeight = 0,
            verdict = ZslProbeVerdict.UNSUPPORTED,
            failures = listOf(ZslProbeFailure.RAW_UNSUPPORTED),
            sample = ZslProbeSample.empty(),
            ringConfig = RingConfig.DISABLED
        )

        val decoded = ZslProbeReport.fromJson(original.toJson())

        assertNotNull(decoded)
        assertEquals(original, decoded)
        assertTrue(decoded!!.sample.rawFps == 0f)
        assertEquals(0, decoded.sample.rawFrameCount)
        assertTrue(
            ZslProbeReport.isApplicable(decoded, "Google/Pixel 8/14", "0", 0, 0)
        )
    }
}
