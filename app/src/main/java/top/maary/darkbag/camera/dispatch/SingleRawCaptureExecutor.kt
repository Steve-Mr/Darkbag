package top.maary.darkbag.camera.dispatch

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import top.maary.darkbag.MainApplication
import top.maary.darkbag.camera.delegate.CameraCoordinatorHost
import top.maary.darkbag.camera.delegate.CameraEnvironmentHost
import top.maary.darkbag.camera.delegate.CameraUiHost
import top.maary.darkbag.camera.zsl.ZslTelemetry
import top.maary.darkbag.fragments.HdrPlusBurst
import top.maary.darkbag.models.CaptureMetadata
import top.maary.darkbag.models.CaptureTimingMode
import top.maary.darkbag.models.StandardTimingTracker
import top.maary.darkbag.pipeline.sink.CaptureSink
import top.maary.darkbag.processor.ColorProcessor
import top.maary.darkbag.processor.HdrPlusRequestManager
import top.maary.darkbag.utils.CameraRepository
import top.maary.darkbag.utils.HalfFrameManager
import top.maary.darkbag.utils.LensInfo

internal class SingleRawCaptureExecutor(
    private val env: CameraEnvironmentHost,
    private val ui: CameraUiHost,
    private val coordinator: CameraCoordinatorHost,
    private val metadataExtractor: CameraCaptureMetadataExtractor,
    private val specAssembler: CameraTaskSpecAssembler,
    private val enqueueRawHolder: (MainApplication, RawImageHolder, StandardTimingTracker?) -> Unit,
    private val onJpegFallback: (ByteArray, Float, HalfFrameManager.Metadata?, CaptureMetadata?) -> Unit,
    private val onRecordCaptureResult: (TotalCaptureResult) -> Unit,
    private val onNotifySinkFailure: (android.content.Context, CaptureSink?, HalfFrameManager.Metadata?, Throwable) -> Unit,
    private val onAbortCapture: () -> Unit
) {
    companion object {
        private const val TAG = "SingleRawCaptureExec"
    }

    fun takeSinglePictureCamera2(
        sink: CaptureSink? = null,
        timing: StandardTimingTracker? = null,
        isFrame1Trigger: Boolean = false,
        hfMetadata: HalfFrameManager.Metadata? = null
    ) {
        val appContext = env.hostContext.applicationContext
        val device = env.sessionArbiter.camera2Device ?: run { onAbortCapture(); return }
        val session = env.sessionArbiter.camera2Session ?: run { onAbortCapture(); return }
        val reader = env.sessionArbiter.rawImageReader ?: run { onAbortCapture(); return }
        val handler = env.sessionArbiter.camera2Handler ?: run { onAbortCapture(); return }

        HdrPlusRequestManager.onTaskStarted()
        var taskStarted = true

        val capturedLens = env.runtimeState.currentLens
        val capturedZoom = specAssembler.resolveEffectiveZoom(capturedLens, null)
        val capturedOrientation = coordinator.getCombinedOrientation()
        val capturedLensId = capturedLens?.id

        try {
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader.surface)
                set(CaptureRequest.JPEG_ORIENTATION, capturedOrientation)
                coordinator.applyManualSettingsToRequest(this)
            }

            reader.setOnImageAvailableListener({ r ->
                timing?.recordFrameArrival()
                timing?.captureCallback = System.currentTimeMillis()
                val image = r.acquireLatestImage() ?: run {
                    if (taskStarted) {
                        taskStarted = false
                        HdrPlusRequestManager.onTaskFinished()
                    }
                    onAbortCapture()
                    return@setOnImageAvailableListener
                }
                try {
                    if (image.format == android.graphics.ImageFormat.RAW_SENSOR) {
                        val digitalGain = coordinator.getDigitalGainAndUpdateStep(
                            image.timestamp,
                            isFrame1Trigger,
                            timing?.shutterClick
                        )
                        val holder = copyAndroidImageToHolder(
                            image = image,
                            zoomRatio = capturedZoom,
                            combinedOrientation = capturedOrientation,
                            physicalId = capturedLensId,
                            halfFrameMetadata = hfMetadata?.copy(digitalGain = digitalGain),
                            sink = sink,
                            capturedLens = capturedLens
                        ).copy(timing = timing, digitalGain = digitalGain)
                        image.close()
                        onSingleFrameAccepted(timing, isFrame1Trigger)
                        enqueueRawHolder(appContext as MainApplication, holder, timing)
                    } else {
                        val buffer = image.planes[0].buffer
                        val data = ByteArray(buffer.remaining())
                        buffer.get(data)
                        val ts = image.timestamp
                        image.close()
                        timing?.captureMode = CaptureTimingMode.SINGLE_JPEG
                        onSingleFrameAccepted(timing, isFrame1Trigger)
                        val captureMetadata = if (hfMetadata == null) {
                            metadataExtractor.createCaptureMetadataFromTimestamp(ts, capturedLens)
                        } else null
                        onJpegFallback(data, capturedZoom, hfMetadata, captureMetadata)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to process Camera2 image", e)
                    image.close()
                    if (taskStarted) {
                        taskStarted = false
                        HdrPlusRequestManager.onTaskFinished()
                    }
                    onNotifySinkFailure(appContext, sink, hfMetadata, e)
                    onAbortCapture()
                }
            }, env.sessionArbiter.rawFrameHandler ?: handler)

            session.capture(request.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureStarted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    timestamp: Long,
                    frameNumber: Long
                ) {
                    if (isFrame1Trigger) {
                        coordinator.triggerAutoBurst()
                    }
                }

                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    onRecordCaptureResult(result)
                }
            }, handler)
        } catch (e: Exception) {
            Log.e(TAG, "Camera2 capture failed", e)
            if (taskStarted) {
                HdrPlusRequestManager.onTaskFinished()
            }
            onNotifySinkFailure(appContext, sink, hfMetadata, e)
            onAbortCapture()
        }
    }

    private fun onSingleFrameAccepted(timing: StandardTimingTracker?, isFrame1Trigger: Boolean) {
        timing?.recordShutterReady()
        ZslTelemetry.emitCaptureSummary(
            mode = timing?.captureMode?.name,
            frameCount = 1,
            t1Ms = (timing?.shutterReady ?: 0L) - (timing?.shutterClick ?: 0L)
        )
        env.runtimeState.isBurstActive = false
        env.cameraViewModel.setBurstActive(false)
        env.lifecycleScope.launch(Dispatchers.Main) {
            ui.resetBurstUi()
            if (!isFrame1Trigger) {
                ui.showProcessingAnimation()
            }
        }
    }

    fun copyAndroidImageToHolder(
        image: Image,
        zoomRatio: Float,
        combinedOrientation: Int,
        physicalId: String?,
        halfFrameMetadata: HalfFrameManager.Metadata? = null,
        sink: CaptureSink? = null,
        capturedLens: LensInfo? = null
    ): RawImageHolder {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = 2 // RAW16
        val width = image.width
        val height = image.height

        val sensorOrientation = try {
            CameraRepository.getCharacteristics(env.camera2Manager, physicalId ?: "0")
                .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        } catch (_: Exception) {
            0
        }

        val rowLength = width * pixelStride
        val capacity = rowLength * height
        val data = HdrPlusBurst.acquireBuffer(capacity)

        if (buffer.isDirect) {
            ColorProcessor.copyBayerWithStride(
                buffer, buffer.position(),
                data, data.position(),
                width, height, rowStride, pixelStride
            )
        } else {
            val oldPos = buffer.position()
            buffer.rewind()
            if (rowStride == rowLength) {
                if (buffer.remaining() == capacity) {
                    data.put(buffer)
                } else {
                    val oldLimit = buffer.limit()
                    buffer.limit(buffer.position() + capacity)
                    data.put(buffer)
                    buffer.limit(oldLimit)
                }
            } else {
                val oldLimit = buffer.limit()
                for (y in 0 until height) {
                    val rowStart = y * rowStride
                    if (rowStart + rowLength > buffer.capacity()) break
                    buffer.position(rowStart)
                    buffer.limit(rowStart + rowLength)
                    data.put(buffer)
                }
                buffer.limit(oldLimit)
            }
            buffer.position(oldPos)
        }
        data.limit(capacity)
        data.rewind()

        return RawImageHolder(
            data = data,
            width = width,
            height = height,
            timestamp = image.timestamp,
            rotationDegrees = sensorOrientation,
            combinedOrientation = combinedOrientation,
            zoomRatio = zoomRatio,
            physicalId = physicalId,
            halfFrameMetadata = halfFrameMetadata,
            sink = sink,
            capturedLens = capturedLens
        )
    }
}
