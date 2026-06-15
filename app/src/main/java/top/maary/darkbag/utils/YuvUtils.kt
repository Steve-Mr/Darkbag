package top.maary.darkbag.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import java.io.ByteArrayOutputStream

object YuvUtils {
    /**
     * Converts YUV_420_888 Image to an ARGB_8888 Bitmap using Android's built-in YuvImage.
     * This avoids RenderScript deprecation and handles interleaved/planar YUV correctly
     * without assuming buffer sizes match NV21 exactly.
     */
    fun imageToBitmap(context: Context, image: Image): Bitmap? {
        if (image.format != ImageFormat.YUV_420_888) {
            return null
        }

        val nv21Bytes = yuv420888ToNv21(image)
        val yuvImage = YuvImage(nv21Bytes, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 100, out)
        val jpegBytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
    }

    private fun yuv420888ToNv21(image: Image): ByteArray {
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        val width = image.width
        val height = image.height
        val nv21 = ByteArray(width * height * 3 / 2)

        // Copy Y plane
        val yRowStride = yPlane.rowStride
        var pos = 0
        for (row in 0 until height) {
            yBuffer.position(row * yRowStride)
            yBuffer.get(nv21, pos, width)
            pos += width
        }

        // Copy VU plane for NV21
        val uRowStride = uPlane.rowStride
        val vRowStride = vPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vPixelStride = vPlane.pixelStride

        if (vRowStride == uRowStride && vPixelStride == 2 && uPixelStride == 2 && vBuffer.position() == uBuffer.position() - 1) {
            // Highly optimized path: Interleaved VU (NV21) or UV (NV12)
            // It's NV21 if V is before U.
            val chromaWidth = width / 2
            val chromaHeight = height / 2
            for (row in 0 until chromaHeight) {
                vBuffer.position(row * vRowStride)
                vBuffer.get(nv21, pos, chromaWidth * 2)
                pos += chromaWidth * 2
            }
        } else {
            // Fallback path: Planar or other weird stride configs
            val chromaHeight = height / 2
            val chromaWidth = width / 2
            for (row in 0 until chromaHeight) {
                for (col in 0 until chromaWidth) {
                    val vPos = row * vRowStride + col * vPixelStride
                    val uPos = row * uRowStride + col * uPixelStride
                    nv21[pos++] = vBuffer.get(vPos)
                    nv21[pos++] = uBuffer.get(uPos)
                }
            }
        }
        return nv21
    }
}
