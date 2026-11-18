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
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal class MultiCamDngMetadataWriter(
    internal val context: Context,
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
        val plane = rawImage.planes[0]
        return writeRawBufferToDng(
            rawBuffer = plane.buffer,
            width = rawImage.width,
            height = rawImage.height,
            rowStride = plane.rowStride,
            pixelStride = plane.pixelStride,
            chars = chars,
            captureResult = captureResult,
            orientationDegrees = orientationDegrees,
            lens = lens,
            isHdrPlus = isHdrPlus,
            metadata = metadata,
            digitalGain = digitalGain
        )
    }

    fun writeRawBufferToDng(
        rawBuffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
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
            val whiteLevel = chars.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023
            val cfaPattern = chars.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: 0
            val dynamicBl = captureResult.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)
            val blPattern = if (dynamicBl != null && dynamicBl.size >= 4) {
                dynamicBl
            } else {
                val staticBl = chars.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
                if (staticBl != null) {
                    floatArrayOf(
                        staticBl.getOffsetForIndex(0, 0).toFloat(),
                        staticBl.getOffsetForIndex(1, 0).toFloat(),
                        staticBl.getOffsetForIndex(0, 1).toFloat(),
                        staticBl.getOffsetForIndex(1, 1).toFloat()
                    )
                } else {
                    floatArrayOf(64f, 64f, 64f, 64f)
                }
            }

            val calib = SensorCalibrationHelper.extractCalibration(chars, captureResult)
            val lscTriple = SensorCalibrationHelper.extractLensShading(captureResult)
            val activeRect = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            val activeArea = if (activeRect != null) intArrayOf(activeRect.top, activeRect.left, activeRect.bottom, activeRect.right) else null
            val noisePairs = captureResult.get(CaptureResult.SENSOR_NOISE_PROFILE)
            val noiseProfile = if (noisePairs != null && noisePairs.isNotEmpty()) {
                val arr = DoubleArray(noisePairs.size * 2)
                for (i in noisePairs.indices) {
                    arr[i * 2] = noisePairs[i].first
                    arr[i * 2 + 1] = noisePairs[i].second
                }
                arr
            } else null

            val iso = captureResult.get(CaptureResult.SENSOR_SENSITIVITY) ?: 100
            val exposureTime = captureResult.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 10_000_000L
            val focalLength = captureResult.get(CaptureResult.LENS_FOCAL_LENGTH) ?: 5.0f
            val fNumber = captureResult.get(CaptureResult.LENS_APERTURE) ?: 1.8f
            val sensorPhysicalSize = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val focalLength35mm = if (sensorPhysicalSize != null && sensorPhysicalSize.width > 0f) {
                (focalLength * 36f / sensorPhysicalSize.width).toInt()
            } else 24

            val lensDistortion = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                chars.get(CameraCharacteristics.LENS_DISTORTION)
            } else null
            val intrinsicCalibration = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                chars.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)
            } else null
            val hwDistortionMode = top.maary.darkbag.pipeline.model.HardwareProfile(
                lensDistortion = lensDistortion,
                intrinsicCalibration = intrinsicCalibration,
                focalLength = focalLength
            ).getDistortionMode()

            val success = ColorProcessor.nativeWriteRawImageDng(
                rawBuffer = rawBuffer,
                bufferOffset = rawBuffer.position(),
                width = width,
                height = height,
                rowStrideBytes = rowStride,
                pixelStrideBytes = pixelStride,
                outputPath = tempFile.absolutePath,
                outFd = -1,
                orientationDegrees = orientationDegrees,
                whiteLevel = whiteLevel,
                blackLevelPattern = blPattern,
                cfaPattern = cfaPattern,
                colorMatrix1 = calib.colorMatrix1,
                colorMatrix2 = calib.colorMatrix2,
                forwardMatrix1 = calib.forwardMatrix1,
                forwardMatrix2 = calib.forwardMatrix2,
                calibrationIlluminant1 = calib.calibrationIlluminant1,
                calibrationIlluminant2 = calib.calibrationIlluminant2,
                neutralColorPoint = calib.neutralColorPoint,
                lensShadingMap = lscTriple.first,
                lensShadingRows = lscTriple.second,
                lensShadingCols = lscTriple.third,
                activeArea = activeArea,
                noiseProfile = noiseProfile,
                iso = iso,
                exposureTimeNanos = exposureTime,
                focalLength = focalLength,
                focalLength35mm = focalLength35mm,
                fNumber = fNumber,
                dngCompressionMode = 0,
                isHdrPlus = isHdrPlus,
                metadata = metadata,
                digitalGain = digitalGain,
                distortionMode = hwDistortionMode,
                distortionCoeffs = lensDistortion,
                intrinsicCalibration = intrinsicCalibration
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
        targetExposureProduct: Double = 0.0,
        forcedDigitalGain: Float? = null
    ): PhysicalCapturedFrame {
        val chars = lens.characteristics ?: CameraRepository.getCharacteristics(cameraManager, lens.physicalId)
        val metadata = createMetadataFromCaptureResult(captureResult, lens, captureTimestamp)
        val digitalGain = forcedDigitalGain ?: calculateDigitalGain(captureResult, chars, targetExposureProduct)
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
