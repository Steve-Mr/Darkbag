package top.maary.darkbag.viewer.metadata

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Computes normalized 256-bin R, G, B, and Rec.709 Luma histograms for preview cards.
 */
object HistogramCalculator {

    data class HistogramData(
        val red: FloatArray,
        val green: FloatArray,
        val blue: FloatArray,
        val luma: FloatArray
    )

    fun computeFromPixels(pixels: IntArray): HistogramData {
        val rBins = IntArray(256)
        val gBins = IntArray(256)
        val bBins = IntArray(256)
        val lBins = IntArray(256)

        for (px in pixels) {
            val r = (px ushr 16) and 0xFF
            val g = (px ushr 8) and 0xFF
            val b = px and 0xFF
            val luma = (0.2126f * r + 0.7152f * g + 0.0722f * b).roundToInt().coerceIn(0, 255)
            rBins[r]++
            gBins[g]++
            bBins[b]++
            lBins[luma]++
        }

        val rSmooth = smooth3Tap(rBins)
        val gSmooth = smooth3Tap(gBins)
        val bSmooth = smooth3Tap(bBins)
        val lSmooth = smooth3Tap(lBins)

        var maxVal = 0f
        for (i in 0 until 256) {
            maxVal = max(maxVal, max(max(rSmooth[i], gSmooth[i]), max(bSmooth[i], lSmooth[i])))
        }

        if (maxVal <= 0f) {
            return HistogramData(FloatArray(256), FloatArray(256), FloatArray(256), FloatArray(256))
        }

        for (i in 0 until 256) {
            rSmooth[i] = (rSmooth[i] / maxVal).coerceIn(0f, 1f)
            gSmooth[i] = (gSmooth[i] / maxVal).coerceIn(0f, 1f)
            bSmooth[i] = (bSmooth[i] / maxVal).coerceIn(0f, 1f)
            lSmooth[i] = (lSmooth[i] / maxVal).coerceIn(0f, 1f)
        }

        return HistogramData(rSmooth, gSmooth, bSmooth, lSmooth)
    }

    fun decodeSampledHistogram(context: Context, uri: Uri, maxEdge: Int = 320): HistogramData? {
        return try {
            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, boundsOpts)
            }
            if (boundsOpts.outWidth <= 0 || boundsOpts.outHeight <= 0) return null

            var sampleSize = 1
            val longEdge = max(boundsOpts.outWidth, boundsOpts.outHeight)
            while (longEdge / sampleSize > maxEdge * 2) {
                sampleSize *= 2
            }

            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bitmap = context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, decodeOpts)
            } ?: return null

            try {
                val w = bitmap.width
                val h = bitmap.height
                if (w <= 0 || h <= 0) return null
                val pixels = IntArray(w * h)
                bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
                computeFromPixels(pixels)
            } finally {
                bitmap.recycle()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun smooth3Tap(bins: IntArray): FloatArray {
        val out = FloatArray(256)
        for (i in 0 until 256) {
            val prev = if (i > 0) bins[i - 1] else bins[i]
            val curr = bins[i]
            val next = if (i < 255) bins[i + 1] else bins[i]
            out[i] = 0.25f * prev + 0.5f * curr + 0.25f * next
        }
        return out
    }
}
