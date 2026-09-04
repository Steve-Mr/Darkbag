package top.maary.darkbag.rawvideo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawVideoRecorderStrideTest {

    private fun validateFrameDimensions(
        dataSize: Long,
        width: Long,
        height: Long,
        rowStride: Long
    ): Boolean {
        if (dataSize <= 0 || width <= 0 || height <= 0) return false
        val packedRowBytes = width * 2L // 16-bit CFA
        if (rowStride < packedRowBytes) {
            return false // 步长小于紧密排列的单行字节，必非合法格式
        }
        val minRequiredSize = (height - 1) * rowStride + packedRowBytes
        if (dataSize < minRequiredSize) {
            return false // 缓冲区不足以容纳完整的帧行数
        }
        return true
    }

    @Test
    fun testValidateStride_normalDenseFrame() {
        val width = 1920L
        val height = 1080L
        val packedRowBytes = width * 2L // 3840
        val rowStride = 3840L
        val dataSize = packedRowBytes * height // 4,147,200

        assertTrue(validateFrameDimensions(dataSize, width, height, rowStride))
    }

    @Test
    fun testValidateStride_paddedRowStride() {
        val width = 1920L
        val height = 1080L
        val packedRowBytes = width * 2L // 3840
        val rowStride = 4096L // 对齐到 4KB 边界的 padding 步长
        val minSize = (height - 1) * rowStride + packedRowBytes // 4,420,096

        // 刚好达到最小有效尺寸
        assertTrue(validateFrameDimensions(minSize, width, height, rowStride))

        // 包含最后一行 padding 的完整分配
        assertTrue(validateFrameDimensions(rowStride * height, width, height, rowStride))

        // 仅缺少 1 字节时必须被严格拒绝
        assertFalse(validateFrameDimensions(minSize - 1, width, height, rowStride))
    }

    @Test
    fun testValidateStride_strideUnderflowRejection() {
        val width = 1920L
        val height = 1080L
        val rowStride = 3838L // 小于 1920 * 2 = 3840
        val dataSize = 5_000_000L

        assertFalse("步长小于行宽像素占用时必须拒绝", validateFrameDimensions(dataSize, width, height, rowStride))
    }

    @Test
    fun testValidateStride_zeroDimensionsRejection() {
        assertFalse(validateFrameDimensions(0, 1920, 1080, 3840))
        assertFalse(validateFrameDimensions(4147200, 0, 1080, 3840))
        assertFalse(validateFrameDimensions(4147200, 1920, 0, 3840))
    }
}
