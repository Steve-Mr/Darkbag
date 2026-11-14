package top.maary.darkbag.camera.dispatch

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.util.Log
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import top.maary.darkbag.fragments.SettingsFragment
import top.maary.darkbag.models.CaptureMetadata
import top.maary.darkbag.processor.SensorCalibrationHelper
import top.maary.darkbag.utils.CameraRepository
import top.maary.darkbag.utils.DarkbagIdentity
import top.maary.darkbag.utils.LensInfo
import top.maary.darkbag.utils.LocationHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.round

/**
 * Extracts Camera2 per-frame capture results, EXIF/DNG [CaptureMetadata],
 * [SensorCalibration] color matrices, Lens Shading Maps, and noise profiles.
 */
class CameraCaptureMetadataExtractor(
    private val context: Context,
    private val camera2Manager: CameraManager,
    private val captureResults: MutableMap<Long, TotalCaptureResult>,
    private val captureResultFlow: SharedFlow<TotalCaptureResult>,
    private val locationHelper: LocationHelper,
    private val currentLensProvider: () -> LensInfo?,
    private val isHdrPlusEnabledProvider: () -> Boolean
) {
    constructor(host: CameraCapturePipelineHost) : this(
        context = host.hostContext,
        camera2Manager = host.camera2Manager,
        captureResults = host.captureResults,
        captureResultFlow = host.captureResultFlow,
        locationHelper = host.locationHelper,
        currentLensProvider = { host.currentLens },
        isHdrPlusEnabledProvider = { host.cameraViewModel.uiState.value.isHdrPlusEnabled }
    )

    suspend fun findCaptureResult(timestamp: Long, tolerance: Long = DEFAULT_TOLERANCE_NS): TotalCaptureResult? {
        findCaptureResultSync(timestamp, tolerance)?.let { return it }
        return withTimeoutOrNull(3000) {
            captureResultFlow.first { res ->
                val ts = res.get(CaptureResult.SENSOR_TIMESTAMP)
                ts != null && abs(ts - timestamp) < tolerance
            }
        }
    }

    fun findCaptureResultSync(timestamp: Long, tolerance: Long = DEFAULT_TOLERANCE_NS): TotalCaptureResult? {
        synchronized(captureResults) {
            captureResults[timestamp]?.let { return it }
            return captureResults.entries.firstOrNull { abs(it.key - timestamp) < tolerance }?.value
        }
    }

    fun createCaptureMetadataFromTimestamp(
        timestamp: Long,
        capturedLens: LensInfo? = null
    ): CaptureMetadata {
        val lens = capturedLens ?: currentLensProvider()
        val result = findCaptureResultSync(timestamp)
        return createCaptureMetadata(
            iso = result?.get(CaptureResult.SENSOR_SENSITIVITY),
            exposureTime = result?.get(CaptureResult.SENSOR_EXPOSURE_TIME),
            fNumber = result?.get(CaptureResult.LENS_APERTURE),
            focalLength = result?.get(CaptureResult.LENS_FOCAL_LENGTH),
            captureTime = System.currentTimeMillis(),
            targetCharId = lens?.id,
            isHdrPlus = isHdrPlusEnabledProvider(),
            captureResult = result,
            capturedLens = lens
        )
    }

    fun createCaptureMetadata(
        iso: Int?,
        exposureTime: Long?,
        fNumber: Float?,
        focalLength: Float?,
        captureTime: Long,
        targetCharId: String?,
        isHdrPlus: Boolean,
        captureResult: CaptureResult?,
        capturedLens: LensInfo? = null
    ): CaptureMetadata {
        val offset = SimpleDateFormat("XXX", Locale.US).format(Date(captureTime))
        var focalIn35mm: Int? = null
        var finalFocalLength = focalLength
        val effectiveLens = capturedLens ?: currentLensProvider()
        try {
            val chars = targetCharId?.let { CameraRepository.getCharacteristics(camera2Manager, it) }
            if (chars != null) {
                focalIn35mm = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.let { focalLengths ->
                    val focal = captureResult?.get(CaptureResult.LENS_FOCAL_LENGTH) ?: focalLengths.firstOrNull() ?: 0f
                    val sensorWidth = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.width ?: 36f
                    (focal * 36f / sensorWidth).toInt()
                }
                if (effectiveLens?.isZoomPreset == true) {
                    val zoomRatio = effectiveLens.targetZoomRatio ?: 1.0f
                    if (zoomRatio > 1.0f) {
                        val baseF35 = focalIn35mm ?: round(effectiveLens.equivalentFocalLength ?: 24f).toInt()
                        focalIn35mm = round(baseF35 * zoomRatio).toInt()
                        val baseFocal = finalFocalLength
                            ?: chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
                            ?: 0f
                        finalFocalLength = baseFocal * zoomRatio
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to calculate focalLengthIn35mmFilm", e)
        }

        val saveLoc = try {
            context.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
                ?.getBoolean(SettingsFragment.KEY_SAVE_LOCATION, false) == true
        } catch (_: Exception) {
            false
        }
        val locationSnapshot = if (saveLoc) locationHelper.getCurrentLocation() else null

        return CaptureMetadata(
            iso = iso,
            exposureTime = exposureTime,
            fNumber = fNumber,
            focalLength = finalFocalLength,
            focalLengthIn35mmFilm = focalIn35mm,
            dateTimeOriginal = captureTime,
            dateTimeDigitized = captureTime,
            offsetTime = offset,
            offsetTimeOriginal = offset,
            offsetTimeDigitized = offset,
            make = DarkbagIdentity.normalizedManufacturer(),
            model = DarkbagIdentity.normalizedModel(),
            uniqueCameraModel = DarkbagIdentity.uniqueCameraModel(targetCharId),
            software = DarkbagIdentity.softwareString(isHdrPlus),
            imageDescription = DarkbagIdentity.imageDescription(isHdrPlus),
            location = locationSnapshot
        )
    }

    fun extractSensorCalibration(
        characteristics: CameraCharacteristics?,
        result: CaptureResult?,
        wb: FloatArray? = null
    ): SensorCalibration {
        val colorMatrix1 = extractColorTransformMatrix(
            characteristics?.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1)
        ) ?: FloatArray(9).apply { this[0] = 1f; this[4] = 1f; this[8] = 1f }

        val colorMatrix2 = extractColorTransformMatrix(
            characteristics?.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2)
        ) ?: colorMatrix1.copyOf()

        val forwardMatrix1 = extractColorTransformMatrix(
            characteristics?.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1)
        )
        val forwardMatrix2 = extractColorTransformMatrix(
            characteristics?.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2)
        )

        val calibrationIlluminant1 = (characteristics?.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1) ?: 21).toInt()
        val calibrationIlluminant2 = (characteristics?.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2) ?: 17).toInt()

        val neutral = result?.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)
        val neutralColorPoint = if (neutral != null && neutral.size >= 3) {
            FloatArray(3) { neutral[it].numerator.toFloat() / neutral[it].denominator.toFloat() }
        } else null

        val renderCcm = SensorCalibrationHelper.computeRenderCcm(
            forwardMatrix1 = forwardMatrix1,
            forwardMatrix2 = forwardMatrix2,
            calibrationIlluminant1 = calibrationIlluminant1,
            calibrationIlluminant2 = calibrationIlluminant2,
            neutralColorPoint = neutralColorPoint,
            wb = wb,
            colorMatrix1 = colorMatrix1,
            colorMatrix2 = colorMatrix2
        )

        return SensorCalibration(
            colorMatrix1 = colorMatrix1,
            colorMatrix2 = colorMatrix2,
            forwardMatrix1 = forwardMatrix1,
            forwardMatrix2 = forwardMatrix2,
            calibrationIlluminant1 = calibrationIlluminant1,
            calibrationIlluminant2 = calibrationIlluminant2,
            neutralColorPoint = neutralColorPoint,
            renderCcm = renderCcm
        )
    }

    private fun extractColorTransformMatrix(matrix: android.hardware.camera2.params.ColorSpaceTransform?): FloatArray? {
        if (matrix == null) return null
        val out = FloatArray(9)
        for (r in 0 until 3) {
            for (c in 0 until 3) {
                val rational = matrix.getElement(c, r)
                out[r * 3 + c] = rational.numerator.toFloat() / rational.denominator.toFloat()
            }
        }
        return out
    }

    fun extractLensShadingMap(result: CaptureResult?): Triple<FloatArray?, Int, Int> {
        val map = result?.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP) ?: return Triple(null, 0, 0)
        val rows = map.rowCount
        val cols = map.columnCount
        if (rows <= 0 || cols <= 0) return Triple(null, 0, 0)

        val planar = FloatArray(4 * rows * cols)
        fun idx(ch: Int, row: Int, col: Int): Int = ch * rows * cols + row * cols + col
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                planar[idx(0, row, col)] = map.getGainFactor(0, col, row)
                planar[idx(1, row, col)] = map.getGainFactor(1, col, row)
                planar[idx(2, row, col)] = map.getGainFactor(2, col, row)
                planar[idx(3, row, col)] = map.getGainFactor(3, col, row)
            }
        }
        return Triple(planar, rows, cols)
    }

    fun findLatestLensShadingMap(preferredResult: CaptureResult? = null): Triple<FloatArray?, Int, Int> {
        if (preferredResult != null) {
            val (lsc, r, c) = extractLensShadingMap(preferredResult)
            if (lsc != null && r > 1 && c > 1) return Triple(lsc, r, c)
        }
        synchronized(captureResults) {
            for (res in captureResults.values.reversed()) {
                val (lsc, r, c) = extractLensShadingMap(res)
                if (lsc != null && r > 1 && c > 1) return Triple(lsc, r, c)
            }
        }
        for (res in captureResultFlow.replayCache.reversed()) {
            val (lsc, r, c) = extractLensShadingMap(res)
            if (lsc != null && r > 1 && c > 1) return Triple(lsc, r, c)
        }
        return Triple(null, 0, 0)
    }

    fun extractNoiseProfile(result: CaptureResult?): DoubleArray? {
        val pairs = result?.get(CaptureResult.SENSOR_NOISE_PROFILE) ?: return null
        if (pairs.isEmpty()) return null
        return DoubleArray(pairs.size * 2).apply {
            for (i in pairs.indices) {
                this[i * 2] = pairs[i].first
                this[i * 2 + 1] = pairs[i].second
            }
        }
    }

    fun extractWbAndCcm(result: CaptureResult?): Pair<FloatArray, FloatArray> {
        val wb = floatArrayOf(2.0f, 1.0f, 1.0f, 1.5f)
        val ccm = floatArrayOf(2.0f, -1.0f, 0.0f, -0.5f, 2.0f, -0.5f, 0.0f, -1.0f, 2.0f)
        result?.get(CaptureResult.COLOR_CORRECTION_GAINS)?.let { wbVec ->
            wb[0] = wbVec.red
            wb[1] = wbVec.greenEven
            wb[2] = wbVec.greenOdd
            wb[3] = wbVec.blue
        }
        result?.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)?.let { ccmMat ->
            var idx = 0
            for (row in 0 until 3) {
                for (col in 0 until 3) {
                    ccm[idx++] = ccmMat.getElement(col, row).toFloat()
                }
            }
        }
        return Pair(wb, ccm)
    }

    fun extractBlackLevelPattern(chars: CameraCharacteristics?): IntArray {
        val bl = chars?.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
        return if (bl != null) {
            intArrayOf(
                bl.getOffsetForIndex(0, 0),
                bl.getOffsetForIndex(1, 0),
                bl.getOffsetForIndex(0, 1),
                bl.getOffsetForIndex(1, 1)
            )
        } else {
            intArrayOf(64, 64, 64, 64)
        }
    }

    fun extractActiveArray(chars: CameraCharacteristics?): IntArray? {
        val rect = chars?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return null
        return intArrayOf(rect.top, rect.left, rect.bottom, rect.right)
    }

    private companion object {
        const val TAG = "CameraMetaExtractor"
        const val DEFAULT_TOLERANCE_NS = 5_000_000L
    }
}
