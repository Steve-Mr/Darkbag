package top.maary.darkbag.camera.session.zsl

import android.graphics.ImageFormat
import android.media.ImageReader
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/**
 * R3 的 reader 格式分类（S1-B2 复审 F1）：
 *
 * `ImageReader.getImageFormat()` 自 **API 19** 起可用，因此探测必须**无条件**校验真实 reader 格式——
 * 无 RAW 能力时会话里挂的是 JPEG 占位 reader，绝不能被静态能力"RAW 支持"蒙混过去。
 * 只有格式读取本身抛异常才允许回落到静态能力快照。
 *
 * 本测试直接钉分类函数（适配器整体仍需真机，见证据包 §7）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ZslProbeRawReaderTest {

    private fun reader(format: Int): ImageReader =
        ImageReader.newInstance(64, 48, format, 2)

    @Test
    fun testRawSensorReaderIsAccepted() {
        val raw = reader(ImageFormat.RAW_SENSOR)
        try {
            assertTrue(
                "RAW_SENSOR reader 必须被接受（不得依赖 SDK 版本分支）",
                isRawSensorReader(raw) { false }
            )
        } finally {
            raw.close()
        }
    }

    @Test
    fun testJpegPlaceholderReaderIsRejectedEvenWhenCapabilityClaimsRaw() {
        val jpeg = reader(ImageFormat.JPEG)
        try {
            // 关键回归：静态能力说"支持 RAW"也不能把 JPEG 占位 reader 当 RAW 双流用（F1）
            assertFalse(
                isRawSensorReader(jpeg) { true }
            )
        } finally {
            jpeg.close()
        }
    }

    @Test
    fun testFormatReadFailureFallsBackToCapabilitySnapshot() {
        // ImageReader 无法伪造"读取抛异常"，因此这里钉的是语义契约的另一半：
        // 正常 reader 永不触发回落（fallback 计数为 0），保证低版本不会被误判成"需要回落"。
        val calls = AtomicInteger(0)
        val raw = reader(ImageFormat.RAW_SENSOR)
        val jpeg = reader(ImageFormat.YUV_420_888)
        try {
            isRawSensorReader(raw) { calls.incrementAndGet(); false }
            isRawSensorReader(jpeg) { calls.incrementAndGet(); true }
            assertTrue("真实格式可读时不得调用静态能力回落", calls.get() == 0)
        } finally {
            raw.close()
            jpeg.close()
        }
    }
}
