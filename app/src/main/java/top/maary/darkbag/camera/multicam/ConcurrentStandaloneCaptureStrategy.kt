package top.maary.darkbag.camera.multicam

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
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
import top.maary.darkbag.utils.DarkbagIdentity
import top.maary.darkbag.utils.PhysicalLensInfo
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

@RequiresApi(Build.VERSION_CODES.P)
internal class ConcurrentStandaloneCaptureStrategy(
    private val scope: CoroutineScope,
    private val previewCtrl: MultiCameraPreviewController,
    private val dngWriter: MultiCamDngMetadataWriter,
    private val applyManualConfig: (CaptureRequest.Builder, Boolean, MultiCameraManualConfig?, Boolean) -> Unit
) {
    companion object {
        private const val TAG = "ConcurrentStandalone"
        private const val CAPTURE_TIMEOUT_MS = 8000L
    }

    fun capture(
        orientationDegrees: Int,
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
        val totalExpected = expectedLenses.size

        val collectedFrames = ConcurrentHashMap<String, PhysicalCapturedFrame>()
        val physicalCaptureResults = ConcurrentHashMap<String, CaptureResult>()
        val resultDeferredMap = ConcurrentHashMap<String, CompletableDeferred<CaptureResult>>()
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
                withContext(Dispatchers.Main) { onError("Concurrent standalone capture timed out") }
            }
        }

        try {
            val cameraHandler = previewCtrl.cameraHandler
            for (lens in expectedLenses) {
                val dev = previewCtrl.openDevices[lens.physicalId] ?: continue
                val sess = previewCtrl.openSessions[lens.physicalId] ?: continue
                val jpegReader = previewCtrl.jpegImageReaders[lens.physicalId]
                val rawReader = previewCtrl.rawImageReaders[lens.physicalId]

                fun tryAssembleFrame(l: PhysicalLensInfo) {
                    val jpegPair = collectedJpegs[l.physicalId]
                    val dngPath = collectedDngPaths[l.physicalId]
                    if (jpegPair == null && dngPath == null) return
                    val result = physicalCaptureResults[l.physicalId]
                    collectedFrames[l.physicalId] = dngWriter.assembleFrame(
                        lens = l,
                        jpegPair = jpegPair,
                        dngPath = dngPath,
                        captureResult = result,
                        orientationDegrees = orientationDegrees,
                        captureTimestamp = captureTimestamp
                    )
                }

                jpegReader?.setOnImageAvailableListener({ r ->
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

                rawReader?.setOnImageAvailableListener({ r ->
                    val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                    scope.launch(Dispatchers.IO) {
                        try {
                            val chars = dngWriter.getLensCharacteristics(lens)
                            val captureRes = withTimeoutOrNull(2500L) { resultDeferredMap[lens.physicalId]?.await() }
                            if (captureRes != null) {
                                val digitalGain = dngWriter.calculateDigitalGain(captureRes, chars)
                                val metadata = dngWriter.createMetadataFromCaptureResult(captureRes, lens, captureTimestamp)
                                val tempPath = dngWriter.writeDngToFile(
                                    rawImage = image,
                                    chars = chars,
                                    captureResult = captureRes,
                                    orientationDegrees = orientationDegrees,
                                    lens = lens,
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

                val req = dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    jpegReader?.surface?.let { addTarget(it) }
                    rawReader?.surface?.let { addTarget(it) }
                    set(CaptureRequest.JPEG_ORIENTATION, orientationDegrees)
                    val isPrimary = (lens.physicalId == previewCtrl.currentPrimaryLens?.physicalId)
                    val isMeteringPrimary = (lens.physicalId == (previewCtrl.resolveActivePreviewPhysicalId() ?: previewCtrl.currentPrimaryLens?.physicalId))
                    applyManualConfig(this, isPrimary, manualConfig, isMeteringPrimary)
                }

                sess.capture(req.build(), object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                        physicalCaptureResults[lens.physicalId] = result
                        resultDeferredMap[lens.physicalId]?.complete(result)
                        tryAssembleFrame(lens)
                    }

                    override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                        Log.w(TAG, "Concurrent capture failed on ${lens.physicalId}")
                    }
                }, cameraHandler)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initiating concurrent standalone capture", e)
            completionJob.cancel()
            previewCtrl.isCapturing = false
            onError(e.message ?: "Concurrent capture failed")
        }
    }
}
