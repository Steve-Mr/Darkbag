package top.maary.darkbag.utils

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Shared helper for collage stitching and MediaStore export.
 * Reused across Darkroom Gallery and batch image operations.
 */
object GalleryCollageHelper {

    private const val TAG = "GalleryCollageHelper"

    /**
     * Decodes two images from URIs, applies orientation alignment, composes into a side-by-side or
     * top-bottom collage bitmap, and exports as a new JPEG to MediaStore (Pictures/Darkbag).
     */
    suspend fun createAndSaveCollage(
        context: Context,
        uri1: Uri,
        uri2: Uri,
        isSideBySide: Boolean
    ): Uri? = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        var bmp1Raw: Bitmap? = null
        var bmp2Raw: Bitmap? = null
        var stitched: Bitmap? = null
        var bmp1: Bitmap? = null
        var bmp2: Bitmap? = null

        try {
            bmp1Raw = appContext.contentResolver.openInputStream(uri1)?.use { BitmapFactory.decodeStream(it) }
            bmp2Raw = appContext.contentResolver.openInputStream(uri2)?.use { BitmapFactory.decodeStream(it) }

            if (bmp1Raw == null || bmp2Raw == null) {
                Log.e(TAG, "Failed to decode one or both source bitmaps for collage")
                return@withContext null
            }

            bmp1 = HalfFrameUtils.ensureOrientation(bmp1Raw, isSideBySide)
            bmp2 = HalfFrameUtils.ensureOrientation(bmp2Raw, isSideBySide)
            stitched = HalfFrameUtils.composeBitmaps(bmp1, bmp2, isSideBySide)

            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val fileName = "${DarkbagIdentity.FILE_PREFIX}${timeStamp}_COLLAGE.jpg"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.DATE_ADDED, System.currentTimeMillis() / 1000)
                put(MediaStore.MediaColumns.DATE_MODIFIED, System.currentTimeMillis() / 1000)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Darkbag")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }

            val insertedUri = appContext.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                values
            )

            if (insertedUri != null) {
                appContext.contentResolver.openOutputStream(insertedUri, "wt")?.use { out ->
                    stitched.compress(Bitmap.CompressFormat.JPEG, 95, out)
                    out.flush()
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    appContext.contentResolver.update(insertedUri, values, null, null)
                }
            }

            insertedUri
        } catch (e: Exception) {
            Log.e(TAG, "Collage composition or saving failed", e)
            null
        } finally {
            stitched?.recycle()
            if (bmp1 != null && bmp1 != bmp1Raw) bmp1.recycle()
            if (bmp2 != null && bmp2 != bmp2Raw) bmp2.recycle()
            bmp1Raw?.recycle()
            bmp2Raw?.recycle()
        }
    }
}
