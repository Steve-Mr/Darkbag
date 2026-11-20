package top.maary.darkbag.utils

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import org.json.JSONArray
import org.json.JSONObject
import top.maary.darkbag.models.CaptureMetadata
import top.maary.darkbag.models.EditConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal object ExifMetadataApplier {
    private const val TAG = "ExifMetadataApplier"

    fun writeStandardExif(exif: ExifInterface, captureMetadata: CaptureMetadata?) {
        captureMetadata?.let { meta ->
            meta.iso?.let { exif.setAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS, it.toString()) }
            meta.exposureTime?.let {
                val exposureInSec = it.toDouble() / 1_000_000_000.0
                exif.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, exposureInSec.toString())
            }
            meta.fNumber?.let { exif.setAttribute(ExifInterface.TAG_F_NUMBER, it.toString()) }
            meta.focalLength?.let { exif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH, it.toString()) }
            meta.focalLengthIn35mmFilm?.let {
                exif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, it.toString())
            }

            val sdf = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
            val subSecSdf = SimpleDateFormat("SSS", Locale.US)

            meta.dateTimeOriginal?.let {
                val dateStr = sdf.format(Date(it))
                val subSecStr = subSecSdf.format(Date(it))
                exif.setAttribute(ExifInterface.TAG_DATETIME, dateStr)
                exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, dateStr)
                exif.setAttribute(ExifInterface.TAG_SUBSEC_TIME, subSecStr)
                exif.setAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, subSecStr)
            }

            meta.dateTimeDigitized?.let {
                val dateStr = sdf.format(Date(it))
                val subSecStr = subSecSdf.format(Date(it))
                exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, dateStr)
                exif.setAttribute(ExifInterface.TAG_SUBSEC_TIME_DIGITIZED, subSecStr)
            } ?: meta.dateTimeOriginal?.let {
                val dateStr = sdf.format(Date(it))
                val subSecStr = subSecSdf.format(Date(it))
                exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, dateStr)
                exif.setAttribute(ExifInterface.TAG_SUBSEC_TIME_DIGITIZED, subSecStr)
            }

            meta.offsetTime?.let { exif.setAttribute(ExifInterface.TAG_OFFSET_TIME, it) }
            meta.offsetTimeOriginal?.let { exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, it) }
            meta.offsetTimeDigitized?.let { exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_DIGITIZED, it) }

            meta.make?.let { exif.setAttribute(ExifInterface.TAG_MAKE, it) }
            meta.model?.let { exif.setAttribute(ExifInterface.TAG_MODEL, it) }
            meta.lensModel?.let { exif.setAttribute(ExifInterface.TAG_LENS_MODEL, it) }

            meta.location?.let { loc ->
                try {
                    exif.setGpsInfo(loc)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to write GPS info to EXIF", e)
                }
            }
        }
    }

    fun applyDarkbagIdentity(
        exif: ExifInterface,
        isHdrPlus: Boolean,
        captureMetadata: CaptureMetadata?,
        imageDescriptionOverride: String? = null
    ) {
        val manufacturer = captureMetadata?.make?.takeIf { it.isNotBlank() } ?: DarkbagIdentity.normalizedManufacturer()
        val model = captureMetadata?.model?.takeIf { it.isNotBlank() } ?: DarkbagIdentity.normalizedModel()
        exif.setAttribute(ExifInterface.TAG_MAKE, manufacturer)
        exif.setAttribute(ExifInterface.TAG_MODEL, model)
        exif.setAttribute(ExifInterface.TAG_SOFTWARE, DarkbagIdentity.softwareString(isHdrPlus))
        exif.setAttribute(
            ExifInterface.TAG_IMAGE_DESCRIPTION,
            imageDescriptionOverride ?: DarkbagIdentity.imageDescription(isHdrPlus)
        )
    }

    fun buildEditConfigJson(editConfig: EditConfig?): JSONObject? {
        return editConfig?.let { cfg ->
            JSONObject().apply {
                put("log", cfg.log)
                put("lut", cfg.lut)
                put("exposure", cfg.exposure.toDouble())
                put("contrast", cfg.contrast.toDouble())
                put("saturation", cfg.saturation.toDouble())
                put("highlights", cfg.highlights.toDouble())
                put("shadows", cfg.shadows.toDouble())
                put("whites", cfg.whites.toDouble())
                put("blacks", cfg.blacks.toDouble())
                put("digital_gain", cfg.digitalGain.toDouble())

                cfg.adjustments?.let { adjs ->
                    val array = JSONArray()
                    adjs.forEach { adj ->
                        array.put(JSONObject().apply {
                            put("exposure", adj.exposure.toDouble())
                            put("contrast", adj.contrast.toDouble())
                            put("saturation", adj.saturation.toDouble())
                            put("highlights", adj.highlights.toDouble())
                            put("shadows", adj.shadows.toDouble())
                            put("whites", adj.whites.toDouble())
                            put("blacks", adj.blacks.toDouble())
                            put("digital_gain", adj.digitalGain.toDouble())
                            put("wb_mode", adj.wbMode)
                            put("wb_temp_offset", adj.wbTempOffset.toDouble())
                            put("wb_tint_offset", adj.wbTintOffset.toDouble())
                            put("wb_manual_temp_k", adj.wbManualTempK.toDouble())
                        })
                    }
                    put("adjustments", array)
                }
                put("show_timestamp", cfg.showTimestamp)
                put("flare_type", cfg.flareType)
                put("hf_layout", cfg.hfLayout)
                put("is_swapped", cfg.isSwapped)
                put("zoom_factor", cfg.zoomFactor.toDouble())
                put("color_engine_mode", cfg.colorEngineMode)
                put("wb_mode", cfg.wbMode)
                put("wb_temp_offset", cfg.wbTempOffset.toDouble())
                put("wb_tint_offset", cfg.wbTintOffset.toDouble())
                put("wb_manual_temp_k", cfg.wbManualTempK.toDouble())
            }
        }
    }

    fun writeMetadataToExifFile(file: File, editConfig: EditConfig?, captureMetadata: CaptureMetadata?) {
        try {
            val json = buildEditConfigJson(editConfig)
            val exif = ExifInterface(file.absolutePath)
            if (json != null) {
                exif.setAttribute(ExifInterface.TAG_USER_COMMENT, json.toString())
            }

            writeStandardExif(exif, captureMetadata)
            val isHdrPlus = (editConfig?.log ?: "").contains("HDR+", ignoreCase = true) ||
                file.name.contains("_HDRPLUS", ignoreCase = true)
            applyDarkbagIdentity(exif, isHdrPlus = isHdrPlus, captureMetadata = captureMetadata)

            exif.saveAttributes()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write EditConfig to EXIF file ${file.absolutePath}", e)
        }
    }

    fun writeMetadataToExif(context: Context, uri: Uri, editConfig: EditConfig?, captureMetadata: CaptureMetadata?) {
        try {
            val json = buildEditConfigJson(editConfig)
            context.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                val exif = ExifInterface(pfd.fileDescriptor)
                if (json != null) {
                    exif.setAttribute(ExifInterface.TAG_USER_COMMENT, json.toString())
                }

                writeStandardExif(exif, captureMetadata)
                val isHdrPlus = (editConfig?.log ?: "").contains("HDR+", ignoreCase = true) ||
                    uri.toString().contains("_HDRPLUS", ignoreCase = true)
                applyDarkbagIdentity(exif, isHdrPlus = isHdrPlus, captureMetadata = captureMetadata)

                exif.saveAttributes()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write EditConfig to EXIF for $uri", e)
        }
    }

    fun getExifOrientation(rotationDegrees: Int, mirror: Boolean): Int {
        return when (rotationDegrees) {
            90 -> if (mirror) ExifInterface.ORIENTATION_TRANSPOSE else ExifInterface.ORIENTATION_ROTATE_90
            180 -> if (mirror) ExifInterface.ORIENTATION_FLIP_VERTICAL else ExifInterface.ORIENTATION_ROTATE_180
            270 -> if (mirror) ExifInterface.ORIENTATION_TRANSVERSE else ExifInterface.ORIENTATION_ROTATE_270
            else -> if (mirror) ExifInterface.ORIENTATION_FLIP_HORIZONTAL else ExifInterface.ORIENTATION_NORMAL
        }
    }

    fun updateExifOrientation(context: Context, uri: Uri, orientation: Int) {
        try {
            context.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                val exif = ExifInterface(pfd.fileDescriptor)
                exif.setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                exif.saveAttributes()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update EXIF orientation for $uri", e)
        }
    }

    fun createFinalEditConfig(editConfig: EditConfig?, zoomFactor: Float): EditConfig? {
        return editConfig?.let {
            if (it.zoomFactor <= 1.05f && zoomFactor > 1.05f) it.copy(zoomFactor = zoomFactor) else it
        } ?: if (zoomFactor > 1.05f) EditConfig(zoomFactor = zoomFactor) else null
    }
}
