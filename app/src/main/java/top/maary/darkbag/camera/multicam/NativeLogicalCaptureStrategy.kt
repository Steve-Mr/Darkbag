package top.maary.darkbag.camera.multicam

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Build
import android.util.Log
import android.util.Size
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import top.maary.darkbag.camera.MultiCameraCaptureResult
import top.maary.darkbag.camera.MultiCameraManualConfig
import top.maary.darkbag.camera.PhysicalCapturedFrame
import top.maary.darkbag.utils.CameraRepository
import top.maary.darkbag.utils.DarkbagIdentity
import top.maary.darkbag.utils.PhysicalLensInfo
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

@RequiresApi(Build.VERSION_CODES.P)
internal class NativeLogicalCaptureStrategy(
    private val cameraManager: CameraManager,
    private val scope: CoroutineScope,
    private val previewCtrl: MultiCameraPreviewController,
    private val dngWriter: MultiCamDngMetadataWriter,
    private val applyManualConfig: (CaptureRequest.Builder, Boolean, MultiCameraManualConfig?, Boolean) -> Unit
) {
    companion object {
        private const val TAG = "NativeLogicalCapture"
        private const val CAPTURE_TIMEOUT_MS = 8000L
    }

    fun capture(
        orientationDegrees: Int,
        isHdrPlusActive: Boolean = false,
        manualConfig: MultiCameraManualConfig? = null,
        onResult: (MultiCameraCaptureResult) -> Unit,
        onError: (String) -> Unit
    ) {
        val device = previewCtrl.cameraDevice ?: run { onError("Camera device is null"); return }
        val session = previewCtrl.captureSession ?: run { onError("Capture session is null"); return }

        if (previewCtrl.isCapturing) {
            onError("Capture already in progress")
            return
        }
        previewCtrl.isCapturing = true

        val captureTimestamp = System.currentTimeMillis()
        val baseName = DarkbagIdentity.FILE_PREFIX + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(captureTimestamp)
        val expectedLenses = previewCtrl.activeLenses.toList()
        val totalExpected = expectedLenses.size

        val collectedFrames = ConcurrentHashMap<String, PhysicalCapturedFrame>()
        val physicalCaptureResults = ConcurrentHashMap<String, CaptureResult>()
        val resultDeferredMap = ConcurrentHashMap<String, CompletableDeferred<CaptureResult>>()
        val targetExposureProductMap = ConcurrentHashMap<String, Double>()
        for (lens in expectedLenses) {
            resultDeferredMap[lens.physicalId] = CompletableDeferred()
        }

        val collectedJpegs = ConcurrentHashMap<String, Pair<ByteArray, Size>>()
        val collectedDngPaths = ConcurrentHashMap<String, String>()
        val rawFailedPaths = ConcurrentHashMap<String, Boolean>()
        val expectedRawCount = expectedLenses.count { previewCtrl.rawImageReaders[it.physicalId] != null }

        val completionJob = scope.launch(Dispatchers.Default) {
            withTimeoutOrNull(CAPTURE_TIMEOUT_MS) {
                while ((collectedFrames.size < totalExpected ||
                        (expectedRawCount > 0 && (collectedDngPaths.size + rawFailedPaths.size) < expectedRawCount)) && isActive
                ) {
                    delay(50)
                }
            }

            previewCtrl.isCapturing = false

            if (collectedFrames.isNotEmpty()) {
                val result = MultiCameraCaptureResult(
                    baseName = baseName,
                    captureTimestampMillis = captureTimestamp,
                    frames = collectedFrames.values.toList().sortedBy { it.lens.multiplier }
                )
                withContext(Dispatchers.Main) { onResult(result) }
            } else {
                withContext(Dispatchers.Main) { onError("Capture timed out without receiving frames") }
            }
        }

        try {
            val captureRequest = buildCaptureRequest(
                device = device,
                expectedLenses = expectedLenses,
                orientationDegrees = orientationDegrees,
                manualConfig = manualConfig,
                targetExposureProductMap = targetExposureProductMap
            )

            fun tryAssembleFrame(lens: PhysicalLensInfo) {
                val jpegPair = collectedJpegs[lens.physicalId]
                val dngPath = collectedDngPaths[lens.physicalId]
                if (jpegPair == null && dngPath == null) return
                collectedFrames[lens.physicalId] = dngWriter.assembleFrame(
                    lens = lens,
                    jpegPair = jpegPair,
                    dngPath = dngPath,
                    captureResult = physicalCaptureResults[lens.physicalId],
                    orientationDegrees = orientationDegrees,
                    captureTimestamp = captureTimestamp,
                    targetExposureProduct = targetExposureProductMap[lens.physicalId] ?: 0.0
                )
            }

            val cameraHandler = previewCtrl.cameraHandler
            for (lens in expectedLenses) {
                previewCtrl.jpegImageReaders[lens.physicalId]?.setOnImageAvailableListener({ r ->
                    val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                    try {
                        val buffer = image.planes[0].buffer
                        val data = ByteArray(buffer.remaining())
                        buffer.get(data)
                        collectedJpegs[lens.physicalId] = data to Size(image.width, image.height)
                        tryAssembleFrame(lens)
                    } finally {
                        image.close()
                    }
                }, cameraHandler)

                previewCtrl.rawImageReaders[lens.physicalId]?.setOnImageAvailableListener({ r ->
                    val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                    scope.launch(Dispatchers.IO) {
                        try {
                            val chars = dngWriter.getLensCharacteristics(lens)
                            val captureRes = withTimeoutOrNull(2500L) { resultDeferredMap[lens.physicalId]?.await() }
                            if (captureRes != null) {
                                val isPrimary = lens.physicalId == (previewCtrl.currentPrimaryLens?.physicalId ?: expectedLenses.first().physicalId)
                                val targetProduct = targetExposureProductMap[lens.physicalId] ?: 0.0
                                val digitalGain = dngWriter.calculateDigitalGain(captureRes, chars, targetProduct)
                                val metadata = dngWriter.createMetadataFromCaptureResult(captureRes, lens, captureTimestamp)
                                val tempPath = dngWriter.writeDngToFile(
                                    rawImage = image,
                                    chars = chars,
                                    captureResult = captureRes,
                                    orientationDegrees = orientationDegrees,
                                    lens = lens,
                                    isHdrPlus = isHdrPlusActive && isPrimary,
                                    metadata = metadata,
                                    digitalGain = digitalGain
                                )
                                if (tempPath != null) {
                                    collectedDngPaths[lens.physicalId] = tempPath
                                } else {
                                    rawFailedPaths[lens.physicalId] = true
                                }
                            } else {
                                rawFailedPaths[lens.physicalId] = true
                            }
                            tryAssembleFrame(lens)
                        } catch (e: Exception) {
                            rawFailedPaths[lens.physicalId] = true
                        } finally {
                            image.close()
                        }
                    }
                }, cameraHandler)
            }

            session.capture(captureRequest.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    val physResults = result.physicalCameraResults
                    for ((pid, presult) in physResults) {
                        physicalCaptureResults[pid] = presult
                        resultDeferredMap[pid]?.complete(presult)
                    }
                    for (lens in expectedLenses) {
                        if (!resultDeferredMap[lens.physicalId]!!.isCompleted) {
                            physicalCaptureResults[lens.physicalId] = result
                            resultDeferredMap[lens.physicalId]?.complete(result)
                        }
                        tryAssembleFrame(lens)
                    }
                }

                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                    Log.e(TAG, "Multi-camera capture request failed: reason=${failure.reason}")
                    completionJob.cancel()
                    previewCtrl.isCapturing = false
                    onError("Multi-camera capture request failed: reason=${failure.reason}")
                }
            }, cameraHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initiate native logical multi-camera capture", e)
            completionJob.cancel()
            previewCtrl.isCapturing = false
            onError(e.message ?: "Failed to initiate capture")
        }
    }

    private fun buildCaptureRequest(
        device: CameraDevice,
        expectedLenses: List<PhysicalLensInfo>,
        orientationDegrees: Int,
        manualConfig: MultiCameraManualConfig?,
        targetExposureProductMap: ConcurrentHashMap<String, Double>
    ): CaptureRequest.Builder {
        val lastPreview = previewCtrl.lastPreviewResult
        return device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            for (lens in expectedLenses) {
                previewCtrl.jpegImageReaders[lens.physicalId]?.surface?.let { addTarget(it) }
                previewCtrl.rawImageReaders[lens.physicalId]?.surface?.let { addTarget(it) }
            }
            set(CaptureRequest.JPEG_ORIENTATION, orientationDegrees)
            applyManualConfig(this, true, manualConfig, true)

            if (manualConfig == null || (!manualConfig.isManualIso && !manualConfig.isManualShutter)) {
                val primExpTime = lastPreview?.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 30_000_000L
                val primIso = lastPreview?.get(CaptureResult.SENSOR_SENSITIVITY) ?: 400
                val primChars = previewCtrl.currentPrimaryLens?.characteristics
                    ?: CameraRepository.getCharacteristics(cameraManager, device.id)
                val primApertures = primChars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
                val primAperture = lastPreview?.get(CaptureResult.LENS_APERTURE)
                    ?: primApertures?.firstOrNull() ?: 1.8f
                val primProduct = primExpTime.toDouble() * primIso.toDouble()

                for (lens in expectedLenses) {
                    val isPrimary = lens.physicalId == (previewCtrl.currentPrimaryLens?.physicalId ?: expectedLenses.first().physicalId)
                    if (isPrimary) {
                        targetExposureProductMap[lens.physicalId] = primProduct
                    } else {
                        val physChars = dngWriter.getLensCharacteristics(lens)
                        val physApertures = physChars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
                        val physAperture = physApertures?.firstOrNull() ?: primAperture
                        val apertureRatio = physAperture / primAperture
                        val lightScale = (apertureRatio * apertureRatio).toDouble()
                        val targetProduct = primProduct * lightScale
                        targetExposureProductMap[lens.physicalId] = targetProduct

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            val expRange = physChars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                            val isoRange = physChars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)

                            val maxExpTime = (expRange?.upper ?: 1_000_000_000L).coerceAtMost(40_000_000L)
                            val minExpTime = (expRange?.lower ?: 100_000L).coerceAtLeast(100_000L)
                            val targetExpTime = primExpTime.coerceIn(minExpTime, maxExpTime)
                            val calcIso = (targetProduct / targetExpTime.toDouble()).toInt()
                            val minIso = isoRange?.lower ?: 100
                            val maxIso = isoRange?.upper ?: 3200
                            val targetIso = calcIso.coerceIn(minIso, maxIso)

                            val finalExpTime = if (calcIso > maxIso && targetExpTime < maxExpTime) {
                                val extended = (targetProduct / maxIso.toDouble()).toLong()
                                extended.coerceIn(minExpTime, maxExpTime)
                            } else {
                                targetExpTime
                            }

                            setPhysicalCameraKey(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF, lens.physicalId)
                            setPhysicalCameraKey(CaptureRequest.SENSOR_EXPOSURE_TIME, finalExpTime, lens.physicalId)
                            setPhysicalCameraKey(CaptureRequest.SENSOR_SENSITIVITY, targetIso, lens.physicalId)
                            Log.i(
                                TAG,
                                "Native logical: transferred photometric exposure to ${lens.name} (${lens.physicalId}): " +
                                    "time=${finalExpTime / 1_000_000.0}ms, ISO=$targetIso (ratio=$apertureRatio)"
                            )
                        }
                    }
                }
            }
        }
    }
}
