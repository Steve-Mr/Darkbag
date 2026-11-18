package top.maary.darkbag.camera.multicam

import android.graphics.ImageFormat
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
import top.maary.darkbag.utils.LensType
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

        val primaryLens = previewCtrl.currentPrimaryLens
            ?: expectedLenses.find { it.type == LensType.WIDE }
            ?: expectedLenses.first()
        val isPrimaryRawSupported = previewCtrl.rawImageReaders[primaryLens.physicalId] != null &&
            dngWriter.isRawSupportedForLens(primaryLens)
        val shouldRunPrimaryHdr = isHdrPlusActive && isPrimaryRawSupported

        val primaryChars = dngWriter.getLensCharacteristics(primaryLens)
        val primaryHdrHelper = if (shouldRunPrimaryHdr) {
            MultiCamPrimaryHdrHelper(
                context = dngWriter.context,
                lens = primaryLens,
                chars = primaryChars,
                dngWriter = dngWriter,
                orientationDegrees = orientationDegrees,
                captureTimestamp = captureTimestamp
            )
        } else null

        val primaryExpSpec = primaryHdrHelper?.calculateExposure(previewCtrl.lastPreviewResult, manualConfig)
        val primaryRawSize = dngWriter.getOptimalOutputSize(primaryLens, ImageFormat.RAW_SENSOR)
        val isPrimaryHdrActive = if (primaryHdrHelper != null && primaryExpSpec != null) {
            primaryHdrHelper.startSession(primaryRawSize, previewCtrl.lastPreviewResult, primaryExpSpec)
        } else false

        val frameAssembleLock = Any()
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
                while (isActive) {
                    val allFramesReady = synchronized(frameAssembleLock) {
                        collectedFrames.size >= totalExpected &&
                            expectedLenses.all { lens ->
                                val expectsRaw = previewCtrl.rawImageReaders[lens.physicalId] != null
                                val frame = collectedFrames[lens.physicalId]
                                if (frame == null) false
                                else if (expectsRaw) {
                                    frame.tempDngPath != null || rawFailedPaths.containsKey(lens.physicalId)
                                } else true
                            }
                    }
                    if (allFramesReady) break
                    delay(50)
                }
            }

            previewCtrl.isCapturing = false

            val finalFrames = synchronized(frameAssembleLock) {
                collectedFrames.values.toList().sortedBy { it.lens.multiplier }
            }

            if (isPrimaryHdrActive && collectedDngPaths[primaryLens.physicalId] == null && !rawFailedPaths.containsKey(primaryLens.physicalId)) {
                primaryHdrHelper?.abort()
            }

            if (finalFrames.isNotEmpty()) {
                val result = MultiCameraCaptureResult(
                    baseName = baseName,
                    captureTimestampMillis = captureTimestamp,
                    frames = finalFrames
                )
                withContext(Dispatchers.Main) { onResult(result) }
            } else {
                primaryHdrHelper?.abort()
                withContext(Dispatchers.Main) { onError("Capture timed out without receiving frames") }
            }
        }

        try {
            val req0 = buildCaptureRequest(
                device = device,
                expectedLenses = expectedLenses,
                orientationDegrees = orientationDegrees,
                manualConfig = manualConfig,
                targetExposureProductMap = targetExposureProductMap,
                primaryBurstSpec = if (isPrimaryHdrActive) primaryExpSpec else null
            )

            fun tryAssembleFrame(lens: PhysicalLensInfo) {
                synchronized(frameAssembleLock) {
                    val jpegPair = collectedJpegs[lens.physicalId]
                    val dngPath = collectedDngPaths[lens.physicalId]
                    if (jpegPair == null && dngPath == null) return
                    val isPrimary = lens.physicalId == primaryLens.physicalId
                    val forcedGain = if (isPrimary && isPrimaryHdrActive) primaryExpSpec?.burstGain else null
                    collectedFrames[lens.physicalId] = dngWriter.assembleFrame(
                        lens = lens,
                        jpegPair = jpegPair,
                        dngPath = dngPath,
                        captureResult = physicalCaptureResults[lens.physicalId],
                        orientationDegrees = orientationDegrees,
                        captureTimestamp = captureTimestamp,
                        targetExposureProduct = targetExposureProductMap[lens.physicalId] ?: 0.0,
                        forcedDigitalGain = forcedGain
                    )
                }
            }

            val cameraHandler = previewCtrl.cameraHandler
            for (lens in expectedLenses) {
                val isPrimary = lens.physicalId == primaryLens.physicalId

                previewCtrl.jpegImageReaders[lens.physicalId]?.setOnImageAvailableListener({ r ->
                    val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                    try {
                        val buffer = image.planes[0].buffer
                        val data = ByteArray(buffer.remaining())
                        buffer.get(data)
                        synchronized(frameAssembleLock) {
                            collectedJpegs[lens.physicalId] = data to Size(image.width, image.height)
                            tryAssembleFrame(lens)
                        }
                    } finally {
                        image.close()
                    }
                }, cameraHandler)

                if (isPrimary && isPrimaryHdrActive && primaryHdrHelper != null) {
                    previewCtrl.rawImageReaders[lens.physicalId]?.setOnImageAvailableListener({ r ->
                        val image = r.acquireNextImage() ?: return@setOnImageAvailableListener
                        primaryHdrHelper.onFrameAvailable(image, physicalCaptureResults[lens.physicalId])
                    }, cameraHandler)

                    scope.launch(Dispatchers.IO) {
                        val fusedPath = primaryHdrHelper.awaitFusedDng(captureResultDeferred = resultDeferredMap[lens.physicalId])
                        synchronized(frameAssembleLock) {
                            if (fusedPath != null) {
                                collectedDngPaths[lens.physicalId] = fusedPath
                            } else {
                                rawFailedPaths[lens.physicalId] = true
                            }
                            tryAssembleFrame(lens)
                        }
                    }
                } else {
                    previewCtrl.rawImageReaders[lens.physicalId]?.setOnImageAvailableListener({ r ->
                        val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                        scope.launch(Dispatchers.IO) {
                            try {
                                val chars = dngWriter.getLensCharacteristics(lens)
                                val captureRes = withTimeoutOrNull(2500L) { resultDeferredMap[lens.physicalId]?.await() }
                                if (captureRes != null) {
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
                                    synchronized(frameAssembleLock) {
                                        if (tempPath != null) {
                                            collectedDngPaths[lens.physicalId] = tempPath
                                        } else {
                                            rawFailedPaths[lens.physicalId] = true
                                        }
                                        tryAssembleFrame(lens)
                                    }
                                } else {
                                    synchronized(frameAssembleLock) {
                                        rawFailedPaths[lens.physicalId] = true
                                        tryAssembleFrame(lens)
                                    }
                                }
                            } catch (e: Exception) {
                                synchronized(frameAssembleLock) {
                                    rawFailedPaths[lens.physicalId] = true
                                    tryAssembleFrame(lens)
                                }
                            } finally {
                                image.close()
                            }
                        }
                    }, cameraHandler)
                }
            }

            val captureCallback = object : CameraCaptureSession.CaptureCallback() {
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
                    primaryHdrHelper?.abort()
                    onError("Multi-camera capture request failed: reason=${failure.reason}")
                }
            }

            if (isPrimaryHdrActive && primaryExpSpec != null) {
                val primaryRawSurface = previewCtrl.rawImageReaders[primaryLens.physicalId]?.surface
                val req1 = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    primaryRawSurface?.let { addTarget(it) }
                    set(CaptureRequest.JPEG_ORIENTATION, orientationDegrees)
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                    set(CaptureRequest.SENSOR_EXPOSURE_TIME, primaryExpSpec.burstTimeNs)
                    set(CaptureRequest.SENSOR_SENSITIVITY, primaryExpSpec.burstIso)
                    setPhysicalCameraKey(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF, primaryLens.physicalId)
                    setPhysicalCameraKey(CaptureRequest.SENSOR_EXPOSURE_TIME, primaryExpSpec.burstTimeNs, primaryLens.physicalId)
                    setPhysicalCameraKey(CaptureRequest.SENSOR_SENSITIVITY, primaryExpSpec.burstIso, primaryLens.physicalId)
                }
                val req2 = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    primaryRawSurface?.let { addTarget(it) }
                    set(CaptureRequest.JPEG_ORIENTATION, orientationDegrees)
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                    set(CaptureRequest.SENSOR_EXPOSURE_TIME, primaryExpSpec.burstTimeNs)
                    set(CaptureRequest.SENSOR_SENSITIVITY, primaryExpSpec.burstIso)
                    setPhysicalCameraKey(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF, primaryLens.physicalId)
                    setPhysicalCameraKey(CaptureRequest.SENSOR_EXPOSURE_TIME, primaryExpSpec.burstTimeNs, primaryLens.physicalId)
                    setPhysicalCameraKey(CaptureRequest.SENSOR_SENSITIVITY, primaryExpSpec.burstIso, primaryLens.physicalId)
                }
                session.captureBurst(listOf(req0.build(), req1.build(), req2.build()), captureCallback, cameraHandler)
            } else {
                session.capture(req0.build(), captureCallback, cameraHandler)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initiate native logical multi-camera capture", e)
            completionJob.cancel()
            previewCtrl.isCapturing = false
            primaryHdrHelper?.abort()
            onError(e.message ?: "Failed to initiate capture")
        }
    }

    private fun buildCaptureRequest(
        device: CameraDevice,
        expectedLenses: List<PhysicalLensInfo>,
        orientationDegrees: Int,
        manualConfig: MultiCameraManualConfig?,
        targetExposureProductMap: ConcurrentHashMap<String, Double>,
        primaryBurstSpec: MultiCamPrimaryHdrHelper.ExposureSpec? = null
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
                val primExpTime = primaryBurstSpec?.burstTimeNs ?: (lastPreview?.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 30_000_000L)
                val primIso = primaryBurstSpec?.burstIso ?: (lastPreview?.get(CaptureResult.SENSOR_SENSITIVITY) ?: 400)
                val primChars = previewCtrl.currentPrimaryLens?.characteristics
                    ?: CameraRepository.getCharacteristics(cameraManager, device.id)
                val primApertures = primChars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
                val primAperture = lastPreview?.get(CaptureResult.LENS_APERTURE)
                    ?: primApertures?.firstOrNull() ?: 1.8f
                val baseline0EvProduct = if (primaryBurstSpec != null) {
                    primExpTime.toDouble() * primIso.toDouble() * primaryBurstSpec.burstGain.toDouble()
                } else {
                    primExpTime.toDouble() * primIso.toDouble()
                }

                for (lens in expectedLenses) {
                    val isPrimary = lens.physicalId == (previewCtrl.currentPrimaryLens?.physicalId ?: expectedLenses.first().physicalId)
                    if (isPrimary) {
                        targetExposureProductMap[lens.physicalId] = baseline0EvProduct
                        if (primaryBurstSpec != null) {
                            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                            set(CaptureRequest.SENSOR_EXPOSURE_TIME, primExpTime)
                            set(CaptureRequest.SENSOR_SENSITIVITY, primIso)
                            setPhysicalCameraKey(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF, lens.physicalId)
                            setPhysicalCameraKey(CaptureRequest.SENSOR_EXPOSURE_TIME, primExpTime, lens.physicalId)
                            setPhysicalCameraKey(CaptureRequest.SENSOR_SENSITIVITY, primIso, lens.physicalId)
                        }
                    } else {
                        val physChars = dngWriter.getLensCharacteristics(lens)
                        val physApertures = physChars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
                        val physAperture = physApertures?.firstOrNull() ?: primAperture
                        val apertureRatio = physAperture / primAperture
                        val lightScale = (apertureRatio * apertureRatio).toDouble()
                        val targetProduct = baseline0EvProduct * lightScale
                        targetExposureProductMap[lens.physicalId] = targetProduct

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            val expRange = physChars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                            val isoRange = physChars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)

                            val maxExpTime = (expRange?.upper ?: 1_000_000_000L).coerceAtMost(40_000_000L)
                            val minExpTime = (expRange?.lower ?: 100_000L).coerceAtLeast(100_000L)
                            val targetExpTime = (baseline0EvProduct / (lastPreview?.get(CaptureResult.SENSOR_SENSITIVITY) ?: 400).toDouble()).toLong().coerceIn(minExpTime, maxExpTime)
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
                                    "time=${finalExpTime / 1_000_000.0}ms, ISO=$targetIso (ratio=$apertureRatio, base0Ev=$baseline0EvProduct)"
                            )
                        }
                    }
                }
            }
        }
    }
}
