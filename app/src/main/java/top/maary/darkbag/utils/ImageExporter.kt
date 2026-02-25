package top.maary.darkbag.utils

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.maary.darkbag.persistence.ImageEntity
import top.maary.darkbag.processor.ColorProcessor
import java.io.File
import java.io.FileOutputStream

object ImageExporter {

    suspend fun exportImage(context: Context, image: ImageEntity, isSaveAs: Boolean): Uri? = withContext(Dispatchers.IO) {
        val resultPath = if (image.isStitched) {
            exportStitched(context, image)
        } else {
            exportSingle(context, image)
        }

        if (resultPath != null) {
            if (!isSaveAs && image.isImported) {
                File(resultPath).copyTo(File(image.path), overwrite = true)
                return@withContext Uri.fromFile(File(image.path))
            } else {
                val targetUri = if (isSaveAs) null else {
                    if (image.path.startsWith("content://")) Uri.parse(image.path) else null
                }
                return@withContext ImageSaver.saveProcessedImage(
                    context = context,
                    inputBitmap = null,
                    bmpPath = resultPath,
                    rotationDegrees = 0,
                    zoomFactor = 1.0f,
                    baseName = if (isSaveAs) "Darkbag_Edit_${System.currentTimeMillis()}" else image.id,
                    linearDngPath = null,
                    tiffPath = null,
                    saveJpg = true,
                    saveTiff = false,
                    saveRaw = false,
                    targetUri = targetUri,
                    isFastPath = false
                )
            }
        }
        return@withContext null
    }

    private suspend fun exportSingle(context: Context, image: ImageEntity): String? {
        if (!image.isRaw) return image.path

        val dngData = File(image.path).readBytes()
        val tempJpg = File(context.cacheDir, "export_${System.currentTimeMillis()}.jpg")

        val ret = ColorProcessor.processRaw(
            dngData, image.targetLog, image.lutPath, null, tempJpg.absolutePath, false, 0, false,
            image.exposure, image.contrast, image.highlights, image.shadows, image.whites, image.blacks, image.saturation
        )

        return if (ret == 0) tempJpg.absolutePath else null
    }

    private suspend fun exportStitched(context: Context, image: ImageEntity): String? {
        val path1 = if (image.isRaw) {
            val dngData = File(image.path).readBytes()
            val temp = File(context.cacheDir, "stitch_tmp_1.jpg")
            ColorProcessor.processRaw(dngData, image.targetLog, image.lutPath, null, temp.absolutePath, false, 0, false,
                image.exposure, image.contrast, image.highlights, image.shadows, image.whites, image.blacks, image.saturation)
            temp.absolutePath
        } else image.path

        val path2 = if (image.isRaw) {
            val dngData = File(image.secondPath!!).readBytes()
            val temp = File(context.cacheDir, "stitch_tmp_2.jpg")
            ColorProcessor.processRaw(dngData, image.targetLog, image.lutPath, null, temp.absolutePath, false, 0, false,
                image.exposure2, image.contrast2, image.highlights2, image.shadows2, image.whites2, image.blacks2, image.saturation2)
            temp.absolutePath
        } else image.secondPath!!

        val stitchedBitmap = HalfFrameUtils.stitchImages(
            path1, path2, image.layout ?: "Side-by-side", false
        ) ?: return null

        val finalBitmap = HalfFrameUtils.addEffects(
            stitchedBitmap, image.dateStamp, image.lightLeak, image.layout ?: "Side-by-side"
        )

        val exportFile = File(context.cacheDir, "stitched_export_${System.currentTimeMillis()}.jpg")
        FileOutputStream(exportFile).use { out ->
            finalBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, out)
        }

        finalBitmap.recycle()
        if (stitchedBitmap != finalBitmap) stitchedBitmap.recycle()

        return exportFile.absolutePath
    }
}
