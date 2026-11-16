package top.maary.darkbag.zsl

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.maary.darkbag.camera.zsl.ZslTelemetry
import top.maary.darkbag.camera.zsl.ZslTelemetryFields

/**
 * 单行 JSON 遥测（S0-7）：字段集合固定、schema v1、单行输出、边界裁剪。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ZslTelemetryTest {

    @Test
    fun testCaptureSummaryCarriesAllFixedFields() {
        val json = JSONObject(
            ZslTelemetry.buildCaptureSummaryJson(
                mode = "HDR_BURST",
                frameCount = 5,
                t1Ms = 210L,
                fallbackReason = null,
                timestampMs = 1_700_000_000_000L
            )
        )

        assertEquals(ZslTelemetryFields.SCHEMA_VERSION, json.getInt("v"))
        assertEquals(1, json.getInt("v"))
        assertEquals("HDR_BURST", json.getString("mode"))
        assertEquals(5, json.getInt("frames"))
        assertEquals(210L, json.getLong("t1Ms"))
        assertEquals(1_700_000_000_000L, json.getLong("ts"))

        val expectedKeys = setOf("v", "mode", "frames", "t1Ms", "fallbackReason", "ts")
        assertEquals(expectedKeys, json.keys().asSequence().toSet())
    }

    @Test
    fun testCaptureSummaryIsSingleLine() {
        val raw = ZslTelemetry.buildCaptureSummaryJson(
            mode = "SINGLE_RAW",
            frameCount = 1,
            t1Ms = 88L,
            fallbackReason = "ring_empty"
        )

        assertFalse("遥测必须单行输出，实际: $raw", raw.contains("\n"))
        assertFalse(raw.contains("\r"))
        assertEquals(1, raw.lines().size)
    }

    @Test
    fun testNegativeT1IsClampedToZero() {
        val json = JSONObject(
            ZslTelemetry.buildCaptureSummaryJson(
                mode = "HDR_BURST",
                frameCount = 3,
                t1Ms = -42L
            )
        )

        assertEquals(0L, json.getLong("t1Ms"))
    }

    @Test
    fun testNullModeFallsBackToUnknown() {
        val json = JSONObject(
            ZslTelemetry.buildCaptureSummaryJson(
                mode = null,
                frameCount = 1,
                t1Ms = 0L
            )
        )

        assertEquals(ZslTelemetry.UNKNOWN_MODE, json.getString("mode"))
        assertEquals("UNKNOWN", json.getString("mode"))
    }

    @Test
    fun testNullFallbackReasonIsJsonNullAndOptStringIsSafe() {
        val json = JSONObject(
            ZslTelemetry.buildCaptureSummaryJson(
                mode = "HDR_BURST",
                frameCount = 2,
                t1Ms = 10L,
                fallbackReason = null
            )
        )

        assertTrue(json.isNull("fallbackReason"))
        // JSONObject.NULL 必须能被 optString 安全处理（不抛异常）
        assertNotNull(json.optString("fallbackReason"))

        val withReason = JSONObject(
            ZslTelemetry.buildCaptureSummaryJson(
                mode = "HDR_BURST",
                frameCount = 2,
                t1Ms = 10L,
                fallbackReason = "low_fps"
            )
        )
        assertEquals("low_fps", withReason.getString("fallbackReason"))
    }

    @Test
    fun testSchemaConstantsAreStable() {
        assertEquals(1, ZslTelemetryFields.SCHEMA_VERSION)
        assertEquals("ZslTelemetry", ZslTelemetryFields.TAG)
        assertEquals("ZSL_METRICS", ZslTelemetryFields.PREFIX)
        assertEquals("v", ZslTelemetryFields.FIELD_VERSION)
        assertEquals("mode", ZslTelemetryFields.FIELD_MODE)
        assertEquals("frames", ZslTelemetryFields.FIELD_FRAMES)
        assertEquals("t1Ms", ZslTelemetryFields.FIELD_T1_MS)
        assertEquals("fallbackReason", ZslTelemetryFields.FIELD_FALLBACK_REASON)
        assertEquals("ts", ZslTelemetryFields.FIELD_TIMESTAMP_MS)
    }
}
