package top.maary.darkbag.repository

import android.content.Context
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import top.maary.darkbag.models.BasicAdjustments
import top.maary.darkbag.models.EditConfig
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow

/**
 * Low-level binary TIFF and DNG seek parser for tag metadata extraction (e.g. Tag 50730 BaselineExposure),
 * decoupled from ImageRepository facade.
 */
object DngMetadataSeekParser {

    fun readDngBaselineExposure(context: Context, uri: Uri, isHalfFrame: Boolean, index: Int = 0): EditConfig? {
        return try {
            val baselineExposure = readBaselineExposureFromUri(context, uri) ?: 0.0
            val digitalGain = 2.0.pow(baselineExposure).toFloat()

            if (isHalfFrame) {
                val adjs = mutableListOf(BasicAdjustments(), BasicAdjustments())
                adjs[index] = BasicAdjustments(exposure = 0.0f, digitalGain = digitalGain)
                EditConfig(adjustments = adjs)
            } else {
                EditConfig(exposure = 0.0f, digitalGain = digitalGain)
            }
        } catch (e: Exception) {
            android.util.Log.e("DngMetadataSeekParser", "Failed to read baseline exposure from $uri", e)
            null
        }
    }

    fun readBaselineExposureFromUri(context: Context, uri: Uri): Double? {
        val bytes = try {
            if (uri.scheme == "file") {
                val file = File(uri.path ?: return null)
                if (!file.exists()) return null
                file.inputStream().use { stream ->
                    val header = ByteArray(minOf(file.length().toInt(), 65536))
                    var totalRead = 0
                    while (totalRead < header.size) {
                        val read = stream.read(header, totalRead, header.size - totalRead)
                        if (read <= 0) break
                        totalRead += read
                    }
                    if (totalRead == header.size) header else header.copyOf(totalRead)
                }
            } else {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val buf = ByteArray(65536)
                    var totalRead = 0
                    while (totalRead < buf.size) {
                        val read = stream.read(buf, totalRead, buf.size - totalRead)
                        if (read <= 0) break
                        totalRead += read
                    }
                    if (totalRead > 0) buf.copyOf(totalRead) else null
                }
            }
        } catch (e: Exception) {
            null
        }

        if (bytes != null) {
            val parsed = extractBaselineExposure(bytes)
            if (parsed != null) return parsed
        }

        return try {
            val exif = if (uri.scheme == "file") {
                ExifInterface(uri.path ?: return null)
            } else {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    ExifInterface(pfd.fileDescriptor)
                } ?: return null
            }
            if (exif.hasAttribute("BaselineExposure")) {
                exif.getAttributeDouble("BaselineExposure", 0.0)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    fun extractBaselineExposure(bytes: ByteArray): Double? {
        if (bytes.size < 8) return null
        val order = when {
            bytes[0] == 'I'.code.toByte() && bytes[1] == 'I'.code.toByte() -> ByteOrder.LITTLE_ENDIAN
            bytes[0] == 'M'.code.toByte() && bytes[1] == 'M'.code.toByte() -> ByteOrder.BIG_ENDIAN
            else -> return null
        }
        val buf = ByteBuffer.wrap(bytes).order(order)
        val magic = buf.getShort(2)
        if (magic != 42.toShort()) return null
        var ifdOffset = buf.getInt(4)
        while (ifdOffset > 0 && ifdOffset + 2 <= bytes.size) {
            buf.position(ifdOffset)
            val numEntries = buf.short.toInt() and 0xFFFF
            if (ifdOffset + 2 + numEntries * 12 > bytes.size) break
            for (i in 0 until numEntries) {
                val tag = buf.short.toInt() and 0xFFFF
                buf.short // type
                buf.int   // count
                val valueOrOffset = buf.int
                if (tag == 50730) { // BaselineExposure (0xC62A)
                    if (valueOrOffset + 8 <= bytes.size) {
                        buf.position(valueOrOffset)
                        val num = buf.int
                        val den = buf.int
                        if (den != 0) {
                            return num.toDouble() / den.toDouble()
                        }
                    }
                    return null
                }
            }
            val nextIfdOffset = buf.int
            if (nextIfdOffset <= 0 || nextIfdOffset == ifdOffset) break
            ifdOffset = nextIfdOffset
        }
        return null
    }
}
