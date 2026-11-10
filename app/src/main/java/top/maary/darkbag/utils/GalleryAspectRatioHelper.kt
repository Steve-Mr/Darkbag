package top.maary.darkbag.utils

import android.graphics.BitmapFactory
import android.util.Log
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Shared aspect ratio computation, formatting, and layout application helper.
 * Reused across Darkroom Gallery (ImageGroup / MediaStore) and Playground Gallery (PlaygroundItem / Sandbox).
 */
object GalleryAspectRatioHelper {

    private const val TAG = "GalleryAspectRatioHelper"
    private val fileRatioCache = ConcurrentHashMap<String, String>()

    /**
     * Formats width, height and orientation into an aspect ratio string (e.g. "4000:3000" or "3000:4000").
     */
    fun formatAspectRatio(width: Int, height: Int, orientation: Int): String? {
        if (width <= 0 || height <= 0) return null
        val swap = orientation == 90 || orientation == 270 ||
                orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
                orientation == ExifInterface.ORIENTATION_ROTATE_270 ||
                orientation == ExifInterface.ORIENTATION_TRANSPOSE ||
                orientation == ExifInterface.ORIENTATION_TRANSVERSE
        return if (swap) "$height:$width" else "$width:$height"
    }

    /**
     * Reads or parses aspect ratio from a File using ExifInterface / inJustDecodeBounds.
     */
    fun getOrCalculateAspectRatio(file: File): String? {
        fileRatioCache[file.absolutePath]?.let { return it }

        return try {
            val exifInterface = ExifInterface(file.absolutePath)
            var width = exifInterface.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
            var height = exifInterface.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)

            if (width == 0 || height == 0) {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, options)
                width = options.outWidth
                height = options.outHeight
            }

            if (width > 0 && height > 0) {
                val orientation = exifInterface.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                val ratio = formatAspectRatio(width, height, orientation)
                if (ratio != null) {
                    fileRatioCache[file.absolutePath] = ratio
                }
                ratio
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to calculate aspect ratio for ${file.name}", e)
            null
        }
    }

    /**
     * Applies aspect ratio to a ConstraintLayout-constrained View.
     */
    fun applyAspectRatio(view: View, aspectRatio: String?, fallbackRatio: String? = null) {
        val lp = view.layoutParams as? ConstraintLayout.LayoutParams ?: return
        val targetRatio = aspectRatio ?: fallbackRatio
        if (targetRatio != null) {
            lp.dimensionRatio = targetRatio
            lp.height = 0
        } else {
            lp.dimensionRatio = null
            lp.height = ConstraintLayout.LayoutParams.WRAP_CONTENT
        }
        view.layoutParams = lp
    }

    fun evict(path: String) {
        fileRatioCache.remove(path)
    }

    fun clearCache() {
        fileRatioCache.clear()
    }
}
