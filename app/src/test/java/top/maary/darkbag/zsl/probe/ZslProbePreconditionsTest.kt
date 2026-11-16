package top.maary.darkbag.zsl.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.maary.darkbag.camera.zsl.probe.ZslProbeFailure
import top.maary.darkbag.camera.zsl.probe.ZslProbePreconditionInput
import top.maary.darkbag.camera.zsl.probe.ZslProbePreconditions

/**
 * 探测前置条件（设计 §3.8.2 / §3.8.4 表末两行，S1-B2 R12）：**8 条规则 / 9 个判定分支**各一例
 * + 边界与未知量（F2：RAW 录制与普通视频保存在入口合并为 `isRecording` 一条规则）。
 *
 * 纯 JVM 测试：被测对象零 Android 依赖，不引 Robolectric。
 */
class ZslProbePreconditionsTest {

    private fun input(
        sessionActive: Boolean = true,
        isRecording: Boolean = false,
        isMultiCamera: Boolean = false,
        isFrontPip: Boolean = false,
        batteryPercent: Int? = 80,
        availableMemoryBytes: Long = 2L * 1024L * 1024L * 1024L,
        thermalStatus: Int? = 1,
        rawSupported: Boolean = true
    ) = ZslProbePreconditionInput(
        sessionActive = sessionActive,
        isRecording = isRecording,
        isMultiCamera = isMultiCamera,
        isFrontPip = isFrontPip,
        batteryPercent = batteryPercent,
        availableMemoryBytes = availableMemoryBytes,
        thermalStatus = thermalStatus,
        rawSupported = rawSupported
    )

    private fun evaluate(input: ZslProbePreconditionInput) = ZslProbePreconditions.evaluate(input)

    @Test
    fun testAllConditionsMetReturnsNull() {
        assertNull(evaluate(input()))
        assertNull(ZslProbePreconditions.describe(input()))
    }

    @Test
    fun testSessionNotActiveIsBlocked() {
        assertEquals(ZslProbeFailure.PRECONDITION_FAILED, evaluate(input(sessionActive = false)))
        assertEquals("session inactive", ZslProbePreconditions.describe(input(sessionActive = false)))
    }

    @Test
    fun testRecordingBlocksProbe() {
        assertEquals(ZslProbeFailure.PRECONDITION_FAILED, evaluate(input(isRecording = true)))
        assertEquals("recording in progress", ZslProbePreconditions.describe(input(isRecording = true)))
    }

    @Test
    fun testMultiCameraBlocksProbe() {
        assertEquals(ZslProbeFailure.PRECONDITION_FAILED, evaluate(input(isMultiCamera = true)))
        assertEquals("multi-camera active", ZslProbePreconditions.describe(input(isMultiCamera = true)))
    }

    @Test
    fun testFrontPipBlocksProbe() {
        assertEquals(ZslProbeFailure.PRECONDITION_FAILED, evaluate(input(isFrontPip = true)))
        assertEquals("front PiP active", ZslProbePreconditions.describe(input(isFrontPip = true)))
    }

    /** 口径：电量 <10% 拒绝，10% 通过（设计 §3.8.2）。 */
    @Test
    fun testBatteryBelowTenPercentIsBlockedAndTenPercentPasses() {
        assertEquals(ZslProbeFailure.PRECONDITION_FAILED, evaluate(input(batteryPercent = 9)))
        assertNull(evaluate(input(batteryPercent = 10)))
        // 读不到电量（未知）不判失败：未知不足以否定设备（与热状态口径一致）
        assertNull(evaluate(input(batteryPercent = null)))
    }

    @Test
    fun testAvailableMemoryBelowSixHundredMegabytesIsBlockedAndExactlySixHundredPasses() {
        val sixHundredMb = 600L * 1024L * 1024L
        assertEquals(ZslProbeFailure.PRECONDITION_FAILED, evaluate(input(availableMemoryBytes = sixHundredMb - 1)))
        assertNull(evaluate(input(availableMemoryBytes = sixHundredMb)))
        // 读取失败（0）按规格保守判失败：宁可不测，也不在内存未知时开双流
        assertEquals(ZslProbeFailure.PRECONDITION_FAILED, evaluate(input(availableMemoryBytes = 0L)))
    }

    @Test
    fun testThermalAboveModerateIsBlockedAndModeratePasses() {
        assertEquals(ZslProbeFailure.PRECONDITION_FAILED, evaluate(input(thermalStatus = 3)))
        assertNull(evaluate(input(thermalStatus = 2)))
        assertNull(evaluate(input(thermalStatus = 0)))
        // API < 29 / 读取失败 → 未知，不判失败
        assertNull(evaluate(input(thermalStatus = null)))
    }

    @Test
    fun testRawUnsupportedIsReportedAsRawUnsupportedNotPrecondition() {
        assertEquals(ZslProbeFailure.RAW_UNSUPPORTED, evaluate(input(rawSupported = false)))
        assertEquals("raw sensor not supported", ZslProbePreconditions.describe(input(rawSupported = false)))
    }

    @Test
    fun testEnvironmentFailureWinsOverRawUnsupportedToKeepRetryable() {
        // 同时不满足时先报可重试的 PRECONDITION_FAILED，避免把"电量低"写成"设备不支持 RAW"
        val blocked = input(sessionActive = false, rawSupported = false)

        assertEquals(ZslProbeFailure.PRECONDITION_FAILED, evaluate(blocked))
    }

    @Test
    fun testRulesAreEvaluatedInDocumentedOrder() {
        // 录制 + 电量不足 + 无 RAW：第一个命中的规则必须按文档顺序返回
        assertEquals(
            "recording in progress",
            ZslProbePreconditions.describe(
                input(isRecording = true, batteryPercent = 5, rawSupported = false)
            )
        )
        assertEquals(
            "battery below 10%",
            ZslProbePreconditions.describe(
                input(batteryPercent = 5, rawSupported = false)
            )
        )
    }

    @Test
    fun testThresholdConstantsMatchDesignDocument() {
        assertEquals(10, ZslProbePreconditions.MIN_BATTERY_PERCENT)
        assertEquals(600L * 1024L * 1024L, ZslProbePreconditions.MIN_AVAILABLE_MEMORY_BYTES)
        assertEquals(2, ZslProbePreconditions.MAX_THERMAL_STATUS)
        assertTrue(ZslProbePreconditions.MIN_AVAILABLE_MEMORY_BYTES > 0L)
    }
}
