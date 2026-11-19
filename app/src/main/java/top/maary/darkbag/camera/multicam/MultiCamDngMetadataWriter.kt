package top.maary.darkbag.camera.multicam

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureResult
import android.media.Image
import android.util.Log
import android.util.Range
import android.util.Size
import top.maary.darkbag.camera.PhysicalCapturedFrame
import top.maary.darkbag.models.CaptureMetadata
import top.maary.darkbag.processor.ColorProcessor
import top.maary.darkbag.processor.SensorCalibrationHelper
import top.maary.darkbag.utils.CameraRepository
import top.maary.darkbag.utils.DarkbagIdentity
import top.maary.darkbag.utils.PhysicalLensInfo
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal class MultiCamDngMetadataWriter(
    private val context: Context,
    private val cameraManager: CameraManager
) {
    companion object {
        private const val TAG = "MultiCamDngWriter"
    }

    fun calculateDigitalGain(
        result: CaptureResult?,
        chars: CameraCharacteristics?,
        targetExposureProduct: Double = 0.0
    ): Float {
        val postRawBoost = result?.get(CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST)
        val curIso = result?.get(CaptureResult.SENSOR_SENSITIVITY) ?: 100
        val curExpTime = result?.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 10_000_000L
        val validIsoRange = chars?.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) ?: Range(100, 3200)
        val baseGain = if (postRawBoost != null && postRawBoost > 100) {
            postRawBoost / 100.0f
        } else if (curIso > validIsoRange.upper && validIsoRange.upper > 0) {
            curIso.toFloat() / validIsoRange.upper.toFloat()
        } else {
            1.0f
        }

        if (targetExposureProduct > 0.0 && curExpTime > 0L && curIso > 0) {
            val actualProduct = curExpTime.toDouble() * curIso.toDouble()
            if (actualProduct > 0.0) {
                val deficit = (targetExposureProduct / actualProduct).toFloat()
                if (deficit > 1.10f) {
                    val compensated = (baseGain * deficit).coerceAtMost(64.0f)
                    Log.i(TAG, "Applied exposure deficit compensation: deficit=${deficit}x, baseGain=$baseGain -> $compensated")
                    return compensated
                }
            }
        }

        return baseGain
    }

    fun writeDngToFile(
        rawImage: Image,
        chars: CameraCharacteristics,
        captureResult: CaptureResult?,
        orientationDegrees: Int,
        lens: PhysicalLensInfo,
        isHdrPlus: Boolean = false,
        metadata: CaptureMetadata? = null,
        digitalGain: Float = 1.0f
    ): String? {
        return try {
            if (captureResult == null) {
                Log.e(TAG, "Cannot create DNG: captureResult is null for lens ${lens.name}")
                return null
            }
            val tempFile = File(context.cacheDir, "dng_${lens.physicalId}_${System.currentTimeMillis()}.dng")
            val success = ColorProcessor.writeRawImageToDng(
                rawImage = rawImage,
                chars = chars,
                captureResult = captureResult,
                orientationDegrees = orientationDegrees,
                outputPath = tempFile.absolutePath,
                dngCompressionMode = 0, // Lossless JPEG 16-bit compression (Invariant 1)
                isHdrPlus = isHdrPlus,
                metadata = metadata,
                digitalGain = digitalGain
            )
            if (success) {
                Log.i(TAG, "Successfully written compressed DNG via native write_dng to ${tempFile.absolutePath} (${tempFile.length()} bytes)")
                tempFile.absolutePath
            } else {
                Log.e(TAG, "Native write_dng failed for lens ${lens.name}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write DNG file for lens ${lens.name}", e)
            null
        }
    }

    fun isRawSupportedForLens(lens: PhysicalLensInfo): Boolean {
        val chars = lens.characteristics ?: return false
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return false
        return map.outputFormats.contains(ImageFormat.RAW_SENSOR)
    }

    fun getOptimalOutputSize(lens: PhysicalLensInfo, format: Int): Size {
        val chars = lens.characteristics
        val map = chars?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(format)
        return sizes?.maxByOrNull { it.width * it.height } ?: Size(4000, 3000)
    }

    fun createMetadataFromCaptureResult(
        result: CaptureResult?,
        lens: PhysicalLensInfo,
        captureTime: Long = System.currentTimeMillis()
    ): CaptureMetadata {
        val chars = lens.characteristics
        val focalLength = lens.focalLength
        val focalLength35mm = lens.equivalentFocalLength.toInt()
        val iso = result?.get(CaptureResult.SENSOR_SENSITIVITY)
        val expTime = result?.get(CaptureResult.SENSOR_EXPOSURE_TIME)
        val fNumber = chars?.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.firstOrNull()
        val offset = SimpleDateFormat("XXX", Locale.US).format(Date(captureTime))

        return CaptureMetadata(
            iso = iso,
            exposureTime = expTime,
            fNumber = fNumber,
            focalLength = focalLength,
            focalLengthIn35mmFilm = focalLength35mm,
            dateTimeOriginal = captureTime,
            dateTimeDigitized = captureTime,
            offsetTime = offset,
            offsetTimeOriginal = offset,
            offsetTimeDigitized = offset,
            make = DarkbagIdentity.normalizedManufacturer(),
            model = DarkbagIdentity.normalizedModel(),
            uniqueCameraModel = DarkbagIdentity.uniqueCameraModel(lens.physicalId),
            software = DarkbagIdentity.softwareString(isHdrPlus = false),
            imageDescription = DarkbagIdentity.imageDescription(isHdrPlus = false),
            lensModel = "${lens.name} (${lens.type})"
        )
    }

    fun assembleFrame(
        lens: PhysicalLensInfo,
        jpegPair: Pair<ByteArray, Size>?,
        dngPath: String?,
        captureResult: CaptureResult?,
        orientationDegrees: Int,
        captureTimestamp: Long,
        targetExposureProduct: Double = 0.0
    ): PhysicalCapturedFrame {
        val chars = lens.characteristics ?: CameraRepository.getCharacteristics(cameraManager, lens.physicalId)
        val metadata = createMetadataFromCaptureResult(captureResult, lens, captureTimestamp)
        val digitalGain = calculateDigitalGain(captureResult, chars, targetExposureProduct)
        val lscTriple = SensorCalibrationHelper.extractLensShading(captureResult)
        return PhysicalCapturedFrame(
            lens = lens,
            jpegData = jpegPair?.first,
            tempDngPath = dngPath,
            width = jpegPair?.second?.width ?: 4000,
            height = jpegPair?.second?.height ?: 3000,
            orientation = orientationDegrees,
            captureMetadata = metadata,
            timestamp = captureTimestamp,
            digitalGain = digitalGain,
            lensShadingMap = lscTriple.first,
            lensShadingRows = lscTriple.second,
            lensShadingCols = lscTriple.third
        )
    }

    fun getLensCharacteristics(lens: PhysicalLensInfo): CameraCharacteristics {
        return lens.characteristics ?: CameraRepository.getCharacteristics(cameraManager, lens.physicalId)
    }
}
