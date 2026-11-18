package top.maary.darkbag.camera.multicam

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.media.Image
import android.os.Build
import android.util.Log
import android.util.Range
import android.util.Size
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.withTimeoutOrNull
import top.maary.darkbag.camera.MultiCameraManualConfig
import top.maary.darkbag.models.CaptureMetadata
import top.maary.darkbag.processor.ColorProcessor
import top.maary.darkbag.processor.HdrPlusStreamingBurst
import top.maary.darkbag.processor.SensorCalibrationHelper
import top.maary.darkbag.processor.StreamingBurstResult
import top.maary.darkbag.utils.ExposureUtils
import top.maary.darkbag.utils.PhysicalLensInfo
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

@RequiresApi(Build.VERSION_CODES.P)
internal class MultiCamPrimaryHdrHelper(
    private val context: Context,
    private val lens: PhysicalLensInfo,
    private val chars: CameraCharacteristics,
    private val dngWriter: MultiCamDngMetadataWriter,
    private val orientationDegrees: Int,
    private val captureTimestamp: Long
) {
    companion object {
        private const val TAG = "MultiCamPrimaryHdr"
        const val HDR_FRAME_COUNT = 3
    }

    data class ExposureSpec(
        val burstIso: Int,
        val burstTimeNs: Long,
        val burstGain: Float
    )

    private var sessionHandle: Long = 0L
    private var burstHelper: HdrPlusStreamingBurst? = null
    private val burstDeferred = CompletableDeferred<StreamingBurstResult>()
    private val framesReceived = AtomicInteger(0)

    internal var fallbackBuffer: ByteBuffer? = null
    private var fallbackWidth: Int = 0
    private var fallbackHeight: Int = 0
    private var fallbackRowStride: Int = 0
    private var fallbackPixelStride: Int = 0
    private var fallbackResult: CaptureResult? = null
    private var previewResult: CaptureResult? = null

    private var burstGain: Float = 1.0f
    private var primaryWidth: Int = 0
    private var primaryHeight: Int = 0
    private var calibData: SensorCalibrationHelper.CalibrationData? = null
    private var cfaPattern: Int = 0
    private var whiteLevel: Int = 1023
    private var blackLevelPattern: IntArray = intArrayOf(64, 64, 64, 64)
    private var noiseProfile: DoubleArray? = null
    private var lensShadingMap: FloatArray? = null
    private var lensShadingRows: Int = 0
    private var lensShadingCols: Int = 0
    private var wbGains: FloatArray = floatArrayOf(1f, 1f, 1f, 1f)
    private var ccmMatrix: FloatArray = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

    fun calculateExposure(
        lastPreviewResult: CaptureResult?,
        manualConfig: MultiCameraManualConfig? = null
    ): ExposureSpec? {
        val isManualAe = manualConfig != null && (manualConfig.isManualIso || manualConfig.isManualShutter)
        if (isManualAe) {
            val iso = if (manualConfig.isManualIso) manualConfig.iso else (lastPreviewResult?.get(CaptureResult.SENSOR_SENSITIVITY) ?: 100)
            val expTime = if (manualConfig.isManualShutter) manualConfig.exposureTimeNanos else (lastPreviewResult?.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 10_000_000L)
            return ExposureSpec(burstIso = iso, burstTimeNs = expTime, burstGain = 1.0f)
        }
        if (lastPreviewResult == null) return null

        val curIso = lastPreviewResult.get(CaptureResult.SENSOR_SENSITIVITY) ?: 100
        val curTime = lastPreviewResult.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 10_000_000L
        val isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) ?: Range(100, 3200)
        val timeRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) ?: Range(100_000L, 1_000_000_000L)
        val exp = ExposureUtils.calculateHdrPlusExposure(curIso, curTime, isoRange, timeRange, "-1 EV", 0.0)
        return ExposureSpec(exp.iso, exp.exposureTime, exp.digitalGain)
    }

    fun startSession(
        rawSize: Size,
        previewResult: CaptureResult?,
        spec: ExposureSpec
    ): Boolean {
        this.previewResult = previewResult
        this.primaryWidth = rawSize.width
        this.primaryHeight = rawSize.height
        this.burstGain = spec.burstGain

        this.cfaPattern = chars.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: 0
        this.whiteLevel = chars.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023
        val staticBl = chars.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
        if (staticBl != null) {
            this.blackLevelPattern = intArrayOf(
                staticBl.getOffsetForIndex(0, 0),
                staticBl.getOffsetForIndex(1, 0),
                staticBl.getOffsetForIndex(0, 1),
                staticBl.getOffsetForIndex(1, 1)
            )
        }

        val dynamicBl = previewResult?.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)
        if (dynamicBl != null && dynamicBl.size >= 4) {
            this.blackLevelPattern = intArrayOf(
                dynamicBl[0].toInt(), dynamicBl[1].toInt(), dynamicBl[2].toInt(), dynamicBl[3].toInt()
            )
        }

        val np = previewResult?.get(CaptureResult.SENSOR_NOISE_PROFILE)
        if (np != null && np.isNotEmpty()) {
            val arr = DoubleArray(np.size * 2)
            for (i in np.indices) {
                arr[i * 2] = np[i].first
                arr[i * 2 + 1] = np[i].second
            }
            this.noiseProfile = arr
        }

        val lsc = SensorCalibrationHelper.extractLensShading(previewResult)
        this.lensShadingMap = lsc.first
        this.lensShadingRows = lsc.second
        this.lensShadingCols = lsc.third

        val gains = previewResult?.get(CaptureResult.COLOR_CORRECTION_GAINS)
        if (gains != null) {
            this.wbGains = floatArrayOf(gains.red, gains.greenEven, gains.greenOdd, gains.blue)
        }
        val transform = previewResult?.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)
        if (transform != null) {
            val arr = FloatArray(9)
            for (r in 0 until 3) {
                for (c in 0 until 3) {
                    val rat = transform.getElement(c, r)
                    arr[r * 3 + c] = rat.numerator.toFloat() / rat.denominator.toFloat()
                }
            }
            this.ccmMatrix = arr
        }

        this.calibData = SensorCalibrationHelper.extractCalibration(chars, previewResult, this.wbGains)

        sessionHandle = ColorProcessor.nativeCreateStreamingSession(
            width = primaryWidth,
            height = primaryHeight,
            orientation = orientationDegrees,
            whiteLevel = whiteLevel,
            blackLevelPattern = blackLevelPattern,
            lensShadingMap = lensShadingMap,
            lensShadingRows = lensShadingRows,
            lensShadingCols = lensShadingCols,
            whiteBalance = wbGains,
            ccm = ccmMatrix,
            cfaPattern = cfaPattern,
            noiseProfile = noiseProfile,
            fusionMode = 0,
            zoomFactor = 1.0f
        )
        if (sessionHandle == 0L) {
            Log.e(TAG, "Failed to create native streaming session for multi-camera primary lens")
            return false
        }

        burstHelper = HdrPlusStreamingBurst(
            sessionHandle = sessionHandle,
            frameCount = HDR_FRAME_COUNT,
            timing = null,
            cfaPattern = cfaPattern,
            lockFirstFrameAsReference = true,
            onBurstComplete = { res -> burstDeferred.complete(res) },
            onBurstFailed = { err ->
                Log.w(TAG, "Streaming burst failed: ${err.message}")
                burstDeferred.completeExceptionally(err)
            }
        )
        return true
    }

    fun onFrameAvailable(image: Image, captureResult: CaptureResult?) {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val count = framesReceived.getAndIncrement()

        if (count == 0) {
            try {
                val direct = ByteBuffer.allocateDirect(buffer.remaining())
                val dup = buffer.duplicate()
                direct.put(dup)
                direct.flip()
                fallbackBuffer = direct
                fallbackWidth = image.width
                fallbackHeight = image.height
                fallbackRowStride = plane.rowStride
                fallbackPixelStride = plane.pixelStride
                fallbackResult = captureResult
            } catch (e: Exception) {
                Log.w(TAG, "Failed to cache fallback frame buffer", e)
            }
        }

        val npS = noiseProfile?.getOrNull(0)?.toFloat() ?: 0.0001f
        val npO = noiseProfile?.getOrNull(1)?.toFloat() ?: 0.00001f
        val expTimeNs = captureResult?.get(CaptureResult.SENSOR_EXPOSURE_TIME)
            ?: previewResult?.get(CaptureResult.SENSOR_EXPOSURE_TIME)
            ?: 10_000_000L

        try {
            burstHelper?.addFrame(
                buffer = buffer,
                width = image.width,
                height = image.height,
                rowStride = plane.rowStride,
                pixelStride = plane.pixelStride,
                timestampNs = image.timestamp,
                exposureTimeNs = expTimeNs,
                rotationDegrees = orientationDegrees,
                physicalId = lens.physicalId,
                noiseProfileS = npS,
                noiseProfileO = npO
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error pushing frame $count to burstHelper", e)
        } finally {
            image.close()
        }
    }

    fun abort(clearFallback: Boolean = true) {
        try {
            burstHelper?.abort()
        } catch (e: Exception) {
            Log.w(TAG, "Error aborting burstHelper", e)
        }
        burstHelper = null
        if (clearFallback) {
            fallbackBuffer = null
        }
    }

    suspend fun awaitFusedDng(
        captureResultDeferred: Deferred<CaptureResult?>? = null,
        timeoutMs: Long = 4500L
    ): String? {
        val actualResult = withTimeoutOrNull(2500L) {
            try {
                captureResultDeferred?.await()
            } catch (_: Exception) {
                null
            }
        } ?: fallbackResult ?: previewResult

        val finalMetadata = dngWriter.createMetadataFromCaptureResult(actualResult, lens, captureTimestamp)

        val burstRes = withTimeoutOrNull(timeoutMs) {
            try {
                burstDeferred.await()
            } catch (e: Exception) {
                Log.w(TAG, "Streaming burst exception: ${e.message}")
                null
            }
        }

        if (burstRes != null) {
            val tempRawKey = "multicam_hdr_${lens.physicalId}_$captureTimestamp"
            val finishRes = ColorProcessor.nativeFinishStreamingSession(
                sessionHandle = burstRes.sessionHandle,
                tempRawPath = tempRawKey,
                outputBitmap = null,
                digitalGain = burstGain,
                targetLog = 0,
                lutPath = null,
                zoomFactor = 1.0f,
                mirror = false,
                enableMemoryColor = false,
                colorEngineMode = 0,
                fusionMode = 0,
                debugStats = LongArray(20)
            )

            if (finishRes >= 0) {
                val fusedFile = File(context.cacheDir, "fused_${lens.physicalId}_$captureTimestamp.dng")
                val calib = calibData
                val activeRect = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                val activeArea = if (activeRect != null) intArrayOf(activeRect.top, activeRect.left, activeRect.bottom, activeRect.right) else null
                val focalLength = actualResult?.get(CaptureResult.LENS_FOCAL_LENGTH) ?: lens.focalLength
                val lensDistortion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) chars.get(CameraCharacteristics.LENS_DISTORTION) else null
                val intrinsicCalibration = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) chars.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION) else null
                val hwDistortionMode = top.maary.darkbag.pipeline.model.HardwareProfile(
                    lensDistortion = lensDistortion,
                    intrinsicCalibration = intrinsicCalibration,
                    focalLength = focalLength
                ).getDistortionMode()

                val exportRes: Int
                try {
                    exportRes = ColorProcessor.exportHdrPlus(
                        tempRawPath = tempRawKey,
                        width = primaryWidth,
                        height = primaryHeight,
                        orientation = orientationDegrees,
                        digitalGain = burstGain,
                        targetLog = 0,
                        lutPath = null,
                        jpgPath = null,
                        dngPath = fusedFile.absolutePath,
                        ccm = ccmMatrix,
                        whiteBalance = wbGains,
                        zoomFactor = 1.0f,
                        mirror = false,
                        metadata = finalMetadata,
                        colorMatrix1 = calib?.colorMatrix1,
                        colorMatrix2 = calib?.colorMatrix2,
                        forwardMatrix1 = calib?.forwardMatrix1,
                        forwardMatrix2 = calib?.forwardMatrix2,
                        calibrationIlluminant1 = calib?.calibrationIlluminant1 ?: 21,
                        calibrationIlluminant2 = calib?.calibrationIlluminant2 ?: 17,
                        neutralColorPoint = calib?.neutralColorPoint,
                        dngCompressionMode = 0,
                        cfaPattern = cfaPattern,
                        blackLevelPattern = blackLevelPattern,
                        whiteLevel = whiteLevel,
                        noiseProfile = noiseProfile,
                        activeArray = activeArea,
                        lensShadingMap = lensShadingMap,
                        lensShadingRows = lensShadingRows,
                        lensShadingCols = lensShadingCols,
                        distortionMode = hwDistortionMode,
                        distortionCoeffs = lensDistortion,
                        intrinsicCalibration = intrinsicCalibration,
                        focalLength = focalLength
                    )
                } finally {
                    ColorProcessor.freeSharedRawMemory(tempRawKey)
                }

                if (exportRes == 0 && fusedFile.exists() && fusedFile.length() > 0) {
                    Log.i(TAG, "Multi-camera primary Halide 3-frame fusion succeeded: ${fusedFile.absolutePath} (${fusedFile.length()} bytes)")
                    fallbackBuffer = null
                    return fusedFile.absolutePath
                } else {
                    Log.w(TAG, "exportHdrPlus failed with code $exportRes, falling back to single frame")
                }
            } else {
                Log.w(TAG, "nativeFinishStreamingSession failed with code $finishRes, falling back to single frame")
            }
        } else {
            Log.w(TAG, "Streaming burst timed out or aborted, falling back to single frame")
            abort(clearFallback = false)
        }

        return try {
            writeFallbackSingleFrameDng(actualResult, finalMetadata)
        } finally {
            fallbackBuffer = null
        }
    }

    internal fun writeFallbackSingleFrameDng(
        actualResult: CaptureResult?,
        finalMetadata: CaptureMetadata
    ): String? {
        val buf = fallbackBuffer ?: run {
            Log.e(TAG, "Cannot write fallback DNG: fallbackBuffer is null")
            return null
        }
        val res = actualResult ?: fallbackResult ?: previewResult
        return dngWriter.writeRawBufferToDng(
            rawBuffer = buf,
            width = fallbackWidth,
            height = fallbackHeight,
            rowStride = fallbackRowStride,
            pixelStride = fallbackPixelStride,
            chars = chars,
            captureResult = res,
            orientationDegrees = orientationDegrees,
            lens = lens,
            isHdrPlus = false,
            metadata = finalMetadata,
            digitalGain = burstGain
        )
    }
}
