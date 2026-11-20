package top.maary.darkbag.camera.multicam

import android.annotation.SuppressLint
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Build
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import top.maary.darkbag.camera.MultiCameraCaptureResult
import top.maary.darkbag.camera.MultiCameraManualConfig
import top.maary.darkbag.camera.PhysicalCapturedFrame
import top.maary.darkbag.utils.DarkbagIdentity
import top.maary.darkbag.utils.LensType
import top.maary.darkbag.utils.LogicalMultiCameraInfo
import top.maary.darkbag.utils.PhysicalLensInfo
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executor

@RequiresApi(Build.VERSION_CODES.P)
internal class FastRelayCaptureStrategy(
    private val cameraManager: CameraManager,
    private val scope: CoroutineScope,
    private val executor: Executor,
    private val previewCtrl: MultiCameraPreviewController,
    private val dngWriter: MultiCamDngMetadataWriter,
    private val applyManualConfig: (CaptureRequest.Builder, Boolean, MultiCameraManualConfig?, Boolean) -> Unit,
    private val reopenRelayPrimarySession: suspend (LogicalMultiCameraInfo, Surface, PhysicalLensInfo?) -> Unit
) {
    companion object {
        private const val TAG = "FastRelayCapture"
    }

    fun capture(
        orientationDegrees: Int,
        isHdrPlusActive: Boolean = false,
        manualConfig: MultiCameraManualConfig? = null,
        onResult: (MultiCameraCaptureResult) -> Unit,
        onError: (String) -> Unit
    ) {
        if (previewCtrl.isCapturing) {
            onError("Capture already in progress")
            return
        }
        previewCtrl.isCapturing = true

        val captureTimestamp = System.currentTimeMillis()
        val baseName = DarkbagIdentity.FILE_PREFIX + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(captureTimestamp)
        val expectedLenses = previewCtrl.activeLenses.toList()

        scope.launch(Dispatchers.IO) {
            val collectedFrames = mutableListOf<PhysicalCapturedFrame>()
            try {
                val primaryLens = previewCtrl.currentPrimaryLens
                    ?: expectedLenses.find { it.physicalId == previewCtrl.cameraDevice?.id }
                    ?: expectedLenses.find { it.type == LensType.WIDE }
                    ?: expectedLenses.first()
                val primaryDev = previewCtrl.cameraDevice
                val primarySess = previewCtrl.captureSession
                val primaryJpegReader = previewCtrl.jpegImageReaders[primaryLens.physicalId]
                val primaryRawReader = previewCtrl.rawImageReaders[primaryLens.physicalId]

                var primaryCaptureResult: TotalCaptureResult? = null
                var primaryBurstSpec: MultiCamPrimaryHdrHelper.ExposureSpec? = null
                if (primaryDev != null && primarySess != null && primaryJpegReader != null) {
                    val (frame, res, burstSpec) = capturePrimaryLens(
                        primaryDev, primarySess, primaryJpegReader, primaryRawReader,
                        primaryLens, orientationDegrees, isHdrPlusActive, manualConfig, captureTimestamp
                    )
                    primaryCaptureResult = res
                    primaryBurstSpec = burstSpec
                    if (frame != null) collectedFrames.add(frame)
                }

                val remainingLenses = expectedLenses.filter { it.physicalId != primaryLens.physicalId }
                if (remainingLenses.isNotEmpty()) {
                    previewCtrl.closeInternal()

                    for (relayLens in remainingLenses) {
                        val relayFrame = captureSingleRelayLens(
                            lens = relayLens,
                            orientationDegrees = orientationDegrees,
                            primaryCaptureResult = primaryCaptureResult,
                            manualConfig = manualConfig,
                            primaryBurstSpec = primaryBurstSpec
                        )
                        if (relayFrame != null) collectedFrames.add(relayFrame)
                    }

                    val pSurface = previewCtrl.previewSurface
                    val info = previewCtrl.currentLogicalInfo
                    if (pSurface != null && info != null) {
                        reopenRelayPrimarySession(info, pSurface, primaryLens)
                    }
                }

                previewCtrl.isCapturing = false

                if (collectedFrames.isNotEmpty()) {
                    val result = MultiCameraCaptureResult(
                        baseName = baseName,
                        captureTimestampMillis = captureTimestamp,
                        frames = collectedFrames.sortedBy { it.lens.multiplier }
                    )
                    withContext(Dispatchers.Main) { onResult(result) }
                } else {
                    withContext(Dispatchers.Main) { onError("No frames captured in fast relay burst") }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in captureFastRelayBurst", e)
                previewCtrl.isCapturing = false
                withContext(Dispatchers.Main) { onError(e.message ?: "Relay capture failed") }
            }
        }
    }

    private suspend fun capturePrimaryLens(
        primaryDev: CameraDevice,
        primarySess: CameraCaptureSession,
        primaryJpegReader: ImageReader,
        primaryRawReader: ImageReader?,
        primaryLens: PhysicalLensInfo,
        orientationDegrees: Int,
        isHdrPlusActive: Boolean,
        manualConfig: MultiCameraManualConfig?,
        captureTimestamp: Long
    ): Triple<PhysicalCapturedFrame?, TotalCaptureResult?, MultiCamPrimaryHdrHelper.ExposureSpec?> {
        val resultDeferred = CompletableDeferred<TotalCaptureResult?>()
        val jpegDeferred = CompletableDeferred<Pair<ByteArray, Size>?>()
        val handler = previewCtrl.cameraHandler

        val primaryChars = dngWriter.getLensCharacteristics(primaryLens)
        val shouldRunHdr = isHdrPlusActive && primaryRawReader != null && dngWriter.isRawSupportedForLens(primaryLens)
        val primaryHdrHelper = if (shouldRunHdr) {
            MultiCamPrimaryHdrHelper(
                context = dngWriter.context,
                lens = primaryLens,
                chars = primaryChars,
                dngWriter = dngWriter,
                orientationDegrees = orientationDegrees,
                captureTimestamp = captureTimestamp
            )
        } else null

        val expSpec = primaryHdrHelper?.calculateExposure(previewCtrl.lastPreviewResult, manualConfig)
        val rawSize = dngWriter.getOptimalOutputSize(primaryLens, ImageFormat.RAW_SENSOR)
        val isHdrActive = if (primaryHdrHelper != null && expSpec != null) {
            primaryHdrHelper.startSession(rawSize, previewCtrl.lastPreviewResult, expSpec)
        } else false

        if (isHdrActive && primaryHdrHelper != null && expSpec != null) {
            try {
                primaryJpegReader.setOnImageAvailableListener({ r ->
                    val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                    try {
                        val buffer = image.planes[0].buffer
                        val data = ByteArray(buffer.remaining())
                        buffer.get(data)
                        jpegDeferred.complete(data to Size(image.width, image.height))
                    } finally {
                        image.close()
                    }
                }, handler)

                var lastResult: TotalCaptureResult? = null
                primaryRawReader?.setOnImageAvailableListener({ r ->
                    val image = r.acquireNextImage() ?: return@setOnImageAvailableListener
                    primaryHdrHelper.onFrameAvailable(image, lastResult)
                }, handler)

                val req0 = primaryDev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(primaryJpegReader.surface)
                    primaryRawReader?.surface?.let { addTarget(it) }
                    set(CaptureRequest.JPEG_ORIENTATION, orientationDegrees)
                    applyManualConfig(this, true, manualConfig, true)
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                    set(CaptureRequest.SENSOR_EXPOSURE_TIME, expSpec.burstTimeNs)
                    set(CaptureRequest.SENSOR_SENSITIVITY, expSpec.burstIso)
                }
                val req1 = primaryDev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    primaryRawReader?.surface?.let { addTarget(it) }
                    set(CaptureRequest.JPEG_ORIENTATION, orientationDegrees)
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                    set(CaptureRequest.SENSOR_EXPOSURE_TIME, expSpec.burstTimeNs)
                    set(CaptureRequest.SENSOR_SENSITIVITY, expSpec.burstIso)
                }
                val req2 = primaryDev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    primaryRawReader?.surface?.let { addTarget(it) }
                    set(CaptureRequest.JPEG_ORIENTATION, orientationDegrees)
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                    set(CaptureRequest.SENSOR_EXPOSURE_TIME, expSpec.burstTimeNs)
                    set(CaptureRequest.SENSOR_SENSITIVITY, expSpec.burstIso)
                }

                primarySess.captureBurst(listOf(req0.build(), req1.build(), req2.build()), object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, res: TotalCaptureResult) {
                        lastResult = res
                        if (!resultDeferred.isCompleted) {
                            resultDeferred.complete(res)
                        }
                    }
                    override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, failure: CaptureFailure) {
                        primaryHdrHelper.abort()
                        if (!resultDeferred.isCompleted) {
                            resultDeferred.complete(null)
                        }
                    }
                }, handler)

                val captureRes = withTimeoutOrNull(4000L) { resultDeferred.await() }
                val jpegDataPair = withTimeoutOrNull(4000L) { jpegDeferred.await() }
                val dngPath = primaryHdrHelper.awaitFusedDng(captureResultDeferred = resultDeferred, timeoutMs = 4500L)

                val frame = if (jpegDataPair != null || dngPath != null) {
                    dngWriter.assembleFrame(
                        lens = primaryLens,
                        jpegPair = jpegDataPair,
                        dngPath = dngPath,
                        captureResult = captureRes,
                        orientationDegrees = orientationDegrees,
                        captureTimestamp = captureTimestamp,
                        forcedDigitalGain = expSpec.burstGain
                    )
                } else null
                return Triple(frame, captureRes, expSpec)
            } catch (e: Exception) {
                primaryHdrHelper.abort()
                throw e
            }
        }

        val dngDeferred = CompletableDeferred<String?>()
        attachSingleCaptureReaders(
            jpegReader = primaryJpegReader,
            rawReader = primaryRawReader,
            lens = primaryLens,
            orientationDegrees = orientationDegrees,
            isHdrPlus = isHdrPlusActive,
            captureTimestamp = captureTimestamp,
            resultDeferred = resultDeferred,
            jpegDeferred = jpegDeferred,
            dngDeferred = dngDeferred
        )

        val req = primaryDev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            addTarget(primaryJpegReader.surface)
            primaryRawReader?.surface?.let { addTarget(it) }
            set(CaptureRequest.JPEG_ORIENTATION, orientationDegrees)
            applyManualConfig(this, true, manualConfig, true)
        }

        primarySess.capture(req.build(), object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, res: TotalCaptureResult) {
                resultDeferred.complete(res)
            }
            override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, failure: CaptureFailure) {
                resultDeferred.complete(null)
            }
        }, handler)

        val captureRes = withTimeoutOrNull(3000L) { resultDeferred.await() }
        val jpegDataPair = withTimeoutOrNull(3000L) { jpegDeferred.await() }
        val dngPath = if (primaryRawReader != null) withTimeoutOrNull(3000L) { dngDeferred.await() } else null

        val frame = if (jpegDataPair != null || dngPath != null) {
            dngWriter.assembleFrame(primaryLens, jpegDataPair, dngPath, captureRes, orientationDegrees, captureTimestamp)
        } else null
        return Triple(frame, captureRes, null)
    }

    @SuppressLint("MissingPermission")
    private suspend fun captureSingleRelayLens(
        lens: PhysicalLensInfo,
        orientationDegrees: Int,
        primaryCaptureResult: TotalCaptureResult? = null,
        manualConfig: MultiCameraManualConfig? = null,
        primaryBurstSpec: MultiCamPrimaryHdrHelper.ExposureSpec? = null
    ): PhysicalCapturedFrame? {
        var dev: CameraDevice? = null
        var sess: CameraCaptureSession? = null
        var jpegReader: ImageReader? = null
        var rawReader: ImageReader? = null

        return try {
            val openDeferred = CompletableDeferred<CameraDevice>()
            cameraManager.openCamera(lens.physicalId, executor, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    dev = device
                    openDeferred.complete(device)
                }
                override fun onDisconnected(device: CameraDevice) { dev?.close(); dev = null }
                override fun onError(device: CameraDevice, error: Int) {
                    if (!openDeferred.isCompleted) openDeferred.completeExceptionally(RuntimeException("Open err $error"))
                }
            })

            val device = openDeferred.await()
            val jpegSize = dngWriter.getOptimalOutputSize(lens, ImageFormat.JPEG)
            jpegReader = ImageReader.newInstance(jpegSize.width, jpegSize.height, ImageFormat.JPEG, 2)
            rawReader = if (dngWriter.isRawSupportedForLens(lens)) {
                val rawSize = dngWriter.getOptimalOutputSize(lens, ImageFormat.RAW_SENSOR)
                ImageReader.newInstance(rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, 2)
            } else null

            val outputConfigs = mutableListOf(OutputConfiguration(jpegReader.surface))
            if (rawReader != null) outputConfigs.add(OutputConfiguration(rawReader.surface))

            val sessionDeferred = CompletableDeferred<CameraCaptureSession>()
            val sessionConfig = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                outputConfigs,
                executor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        sess = session
                        sessionDeferred.complete(session)
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        sessionDeferred.completeExceptionally(RuntimeException("Relay session config failed on ${lens.name}"))
                    }
                }
            )
            device.createCaptureSession(sessionConfig)
            val session = sessionDeferred.await()

            val jpegDeferred = CompletableDeferred<Pair<ByteArray, Size>?>()
            val dngDeferred = CompletableDeferred<String?>()
            val resultDeferred = CompletableDeferred<TotalCaptureResult?>()
            val captureTime = System.currentTimeMillis()

            val chars = dngWriter.getLensCharacteristics(lens)
            val colorHarmonizationEnabled = top.maary.darkbag.repository.SettingsRepository.getInstance(dngWriter.context).current().expMultiCamColorHarmonization
            val primaryDevId = previewCtrl.resolveActivePreviewPhysicalId() ?: previewCtrl.currentPrimaryLens?.physicalId ?: "0"
            val primaryChars = previewCtrl.currentPrimaryLens?.characteristics
                ?: cameraManager.getCameraCharacteristics(primaryDevId)
            val harm = if (colorHarmonizationEnabled) {
                MultiCamColorHarmonizer.harmonize(
                    primaryChars = primaryChars,
                    primaryResult = primaryCaptureResult,
                    secondaryChars = chars
                )
            } else null

            attachSingleCaptureReaders(
                jpegReader = jpegReader,
                rawReader = rawReader,
                lens = lens,
                orientationDegrees = orientationDegrees,
                isHdrPlus = false,
                captureTimestamp = captureTime,
                resultDeferred = resultDeferred,
                jpegDeferred = jpegDeferred,
                dngDeferred = dngDeferred,
                overrideNeutralColorPoint = harm?.neutralPoint
            )

            val req = buildRelayCaptureRequest(
                device = device,
                jpegReader = jpegReader,
                rawReader = rawReader,
                chars = chars,
                lens = lens,
                orientationDegrees = orientationDegrees,
                primaryCaptureResult = primaryCaptureResult,
                manualConfig = manualConfig,
                primaryBurstSpec = primaryBurstSpec,
                harmonizedProfile = harm
            )

            session.capture(req.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
                    resultDeferred.complete(result)
                }
                override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, failure: CaptureFailure) {
                    resultDeferred.complete(null)
                }
            }, previewCtrl.cameraHandler)

            val captureRes = withTimeoutOrNull(3000L) { resultDeferred.await() }
            val jpegDataPair = withTimeoutOrNull(3000L) { jpegDeferred.await() }
            val dngPath = if (rawReader != null) withTimeoutOrNull(3000L) { dngDeferred.await() } else null

            if (jpegDataPair != null || dngPath != null) {
                dngWriter.assembleFrame(lens, jpegDataPair, dngPath, captureRes, orientationDegrees, System.currentTimeMillis())
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed single relay capture for ${lens.name}", e)
            null
        } finally {
            try { sess?.close() } catch (_: Exception) {}
            try { dev?.close() } catch (_: Exception) {}
            try { jpegReader?.close() } catch (_: Exception) {}
            try { rawReader?.close() } catch (_: Exception) {}
        }
    }

    private fun attachSingleCaptureReaders(
        jpegReader: ImageReader,
        rawReader: ImageReader?,
        lens: PhysicalLensInfo,
        orientationDegrees: Int,
        isHdrPlus: Boolean,
        captureTimestamp: Long,
        resultDeferred: CompletableDeferred<TotalCaptureResult?>,
        jpegDeferred: CompletableDeferred<Pair<ByteArray, Size>?>,
        dngDeferred: CompletableDeferred<String?>,
        overrideNeutralColorPoint: FloatArray? = null
    ) {
        val handler = previewCtrl.cameraHandler
        jpegReader.setOnImageAvailableListener({ r ->
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val buffer = image.planes[0].buffer
                val data = ByteArray(buffer.remaining())
                buffer.get(data)
                jpegDeferred.complete(data to Size(image.width, image.height))
            } finally {
                image.close()
            }
        }, handler)

        if (rawReader != null) {
            rawReader.setOnImageAvailableListener({ r ->
                val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                scope.launch(Dispatchers.IO) {
                    try {
                        val chars = dngWriter.getLensCharacteristics(lens)
                        val captureRes = withTimeoutOrNull(2500L) { resultDeferred.await() }
                        if (captureRes != null) {
                            val digitalGain = dngWriter.calculateDigitalGain(captureRes, chars)
                            val metadata = dngWriter.createMetadataFromCaptureResult(captureRes, lens, captureTimestamp)
                            val tempPath = dngWriter.writeDngToFile(
                                rawImage = image,
                                chars = chars,
                                captureResult = captureRes,
                                orientationDegrees = orientationDegrees,
                                lens = lens,
                                isHdrPlus = isHdrPlus,
                                metadata = metadata,
                                digitalGain = digitalGain,
                                overrideNeutralColorPoint = overrideNeutralColorPoint
                            )
                            dngDeferred.complete(tempPath)
                        } else {
                            Log.e(TAG, "CaptureResult timed out for lens ${lens.name}")
                            dngDeferred.complete(null)
                        }
                    } finally {
                        image.close()
                    }
                }
            }, handler)
        } else {
            dngDeferred.complete(null)
        }
    }

    private fun buildRelayCaptureRequest(
        device: CameraDevice,
        jpegReader: ImageReader,
        rawReader: ImageReader?,
        chars: CameraCharacteristics,
        lens: PhysicalLensInfo,
        orientationDegrees: Int,
        primaryCaptureResult: TotalCaptureResult?,
        manualConfig: MultiCameraManualConfig?,
        primaryBurstSpec: MultiCamPrimaryHdrHelper.ExposureSpec? = null,
        harmonizedProfile: MultiCamColorHarmonizer.HarmonizedProfile? = null
    ): CaptureRequest.Builder {
        return device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            addTarget(jpegReader.surface)
            rawReader?.surface?.let { addTarget(it) }
            set(CaptureRequest.JPEG_ORIENTATION, orientationDegrees)

            if (manualConfig != null && manualConfig.isLinked) {
                applyManualConfig(this, false, manualConfig, false)
            } else {
                val primExpTime = primaryCaptureResult?.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                val primIso = primaryCaptureResult?.get(CaptureResult.SENSOR_SENSITIVITY)
                val primAperture = primaryCaptureResult?.get(CaptureResult.LENS_APERTURE) ?: 1.8f
                val relayAperture = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.firstOrNull() ?: primAperture

                if (primExpTime != null && primIso != null && primExpTime > 0 && primIso > 0) {
                    val apertureRatio = relayAperture / primAperture
                    val baseline0EvProduct = if (primaryBurstSpec != null) {
                        primExpTime.toDouble() * primIso.toDouble() * primaryBurstSpec.burstGain.toDouble()
                    } else {
                        primExpTime.toDouble() * primIso.toDouble()
                    }
                    val targetExposure = baseline0EvProduct * (apertureRatio * apertureRatio)
                    val isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                    val expTimeRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                    val targetIso = primIso.coerceIn(isoRange?.lower ?: 100, isoRange?.upper ?: 3200)
                    val targetExpTime = (targetExposure / targetIso).toLong()
                        .coerceIn(expTimeRange?.lower ?: 100_000L, expTimeRange?.upper ?: 1_000_000_000L)

                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                    set(CaptureRequest.SENSOR_EXPOSURE_TIME, targetExpTime)
                    set(CaptureRequest.SENSOR_SENSITIVITY, targetIso)
                    Log.i(TAG, "Transferred photometric exposure to ${lens.name}: time=${targetExpTime / 1_000_000.0}ms, ISO=$targetIso")
                } else {
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START)
                }

                val focusDist = primaryCaptureResult?.get(CaptureResult.LENS_FOCUS_DISTANCE)
                if (focusDist != null && focusDist > 0f) {
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                    set(CaptureRequest.LENS_FOCUS_DISTANCE, focusDist)
                } else {
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
                }
            }

            if (harmonizedProfile != null) {
                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
                set(CaptureRequest.COLOR_CORRECTION_GAINS, harmonizedProfile.gains)
                harmonizedProfile.colorTransform?.let { transform ->
                    set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
                    set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, transform)
                }
            } else {
                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            }
        }
    }

    /**
     * Cross-camera CIE xy neutral point anchor helper (Section 3.7).
     * Maps primary sensor neutral to secondary sensor domain via device-independent CIE xy space.
     */
    fun anchorSecondaryNeutralViaCieXy(
        primaryNeutral: FloatArray,
        primaryCm1: FloatArray,
        primaryCm2: FloatArray?,
        primaryIllum1: Int,
        primaryIllum2: Int,
        secondaryCm1: FloatArray,
        secondaryCm2: FloatArray?,
        secondaryIllum1: Int,
        secondaryIllum2: Int
    ): Pair<FloatArray, FloatArray>? {
        return top.maary.darkbag.processor.SensorCalibrationHelper.mapNeutralAcrossCameras(
            mainNeutral = primaryNeutral,
            mainCm1 = primaryCm1,
            mainCm2 = primaryCm2,
            mainIllum1 = primaryIllum1,
            mainIllum2 = primaryIllum2,
            subCm1 = secondaryCm1,
            subCm2 = secondaryCm2,
            subIllum1 = secondaryIllum1,
            subIllum2 = secondaryIllum2
        )
    }
}
