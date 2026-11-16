package top.maary.darkbag.camera.session.zsl

import android.graphics.ImageFormat
import android.media.ImageReader

/**
 * R3：判定会话里挂的 RAW reader 是否真的是 `RAW_SENSOR`。
 *
 * `ImageReader.getImageFormat()` 自 **API 19** 起可用（官方 `api-versions.xml` 中该方法无 `since`，
 * 所属类 `since="19"`；S1-B2 复审 F1 修正）。因此这里**无条件**优先读真实格式：
 * 无 RAW 能力的设备上 `CameraHardwareStreamController.createCamera2CaptureSession` 挂的是
 * JPEG 占位 reader，必须被拦下，否则会把 JPEG 当 RAW 双流用。
 *
 * 只有格式读取本身抛异常（厂商实现异常 / 测试桩）才回落到 [rawSupportedFallback]——
 * 即会话绑定时的静态能力快照（创建 reader 与读取能力用的是同一条件，二者等价）。
 *
 * 抽成顶层 `internal` 函数是为了可单测：适配器本身依赖真实会话，无法在 CI 构造。
 */
internal fun isRawSensorReader(
    reader: ImageReader,
    rawSupportedFallback: () -> Boolean
): Boolean = try {
    reader.imageFormat == ImageFormat.RAW_SENSOR
} catch (_: AssertionError) {
    rawSupportedFallback()
} catch (_: RuntimeException) {
    rawSupportedFallback()
}
