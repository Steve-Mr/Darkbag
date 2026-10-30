package top.maary.darkbag.pipeline.composer

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import top.maary.darkbag.utils.HalfFrameUtils

object HalfFrameComposer {
    private const val TAG = "HalfFrameComposer"

    fun compose(
        frame1: Bitmap?,
        frame2: Bitmap?,
        layout: String?,
        isSwapped: Boolean = false,
        showTimestamp: Boolean = false,
        flareType: Int = -1,
        time1: Long = System.currentTimeMillis(),
        time2: Long = System.currentTimeMillis(),
        downsample: Boolean = false
    ): Bitmap? {
        val b1 = if (isSwapped) frame2 else frame1
        val b2 = if (isSwapped) frame1 else frame2

        if (b1 == null && b2 == null) {
            return null
        }

        val t1 = if (isSwapped) time2 else time1
        val t2 = if (isSwapped) time1 else time2

        val isTB = layout == "TB" || layout == "Top-bottom" || layout == "上下排列" || layout?.contains("top", ignoreCase = true) == true
        val isSBS = !isTB

        var oriented1: Bitmap? = null
        var oriented2: Bitmap? = null
        var tempB1: Bitmap? = null
        var tempB2: Bitmap? = null
        var composite: Bitmap? = null

        try {
            oriented1 = b1?.let { HalfFrameUtils.ensureOrientation(it, isSBS) }
            oriented2 = b2?.let { HalfFrameUtils.ensureOrientation(it, isSBS) }

            val w1 = oriented1?.width ?: oriented2?.width ?: 0
            val h1 = oriented1?.height ?: oriented2?.height ?: 0
            val w2 = oriented2?.width ?: oriented1?.width ?: 0
            val h2 = oriented2?.height ?: oriented1?.height ?: 0

            if (w1 <= 0 || h1 <= 0 || w2 <= 0 || h2 <= 0) {
                return null
            }

            tempB1 = oriented1 ?: Bitmap.createBitmap(w2, h2, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
            tempB2 = oriented2 ?: Bitmap.createBitmap(w1, h1, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }

            composite = HalfFrameUtils.composeBitmaps(tempB1, tempB2, isSBS)

            if (downsample) {
                val scale = 0.707f
                val scaledW = (composite.width * scale).toInt().coerceAtLeast(1)
                val scaledH = (composite.height * scale).toInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(composite, scaledW, scaledH, true)
                if (scaled != composite) {
                    composite.recycle()
                    composite = scaled
                }
            }

            val finalComposite = HalfFrameUtils.addEffects(
                composite,
                dateStamp = showTimestamp,
                lightLeak = flareType >= 0,
                layout = layout ?: "SBS",
                time1 = t1,
                time2 = t2,
                flareType = flareType
            )
            if (finalComposite != composite) {
                composite.recycle()
            }
            return finalComposite
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OOM during HalfFrame composition", e)
            return null
        } catch (e: Exception) {
            Log.e(TAG, "Error during HalfFrame composition", e)
            return null
        } finally {
            if (tempB1 != null && tempB1 != oriented1) {
                tempB1.recycle()
            }
            if (tempB2 != null && tempB2 != oriented2) {
                tempB2.recycle()
            }
            if (oriented1 != null && oriented1 != b1) {
                oriented1.recycle()
            }
            if (oriented2 != null && oriented2 != b2) {
                oriented2.recycle()
            }
        }
    }
}
