package top.maary.darkbag.rawvideo

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.media.Image
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class RawVideoSessionManager {
    companion object {
        private const val TAG = "RawVideoSessionManager"
    }

    private var nativeHandle: Long = 0L
    private val isRecording = AtomicBoolean(false)
    private var audioRecorder: AudioRecorder? = null
    private var currentOutputPath: String? = null
    private var startTimestampNs: Long = 0L
    private var recordedFrames: Int = 0

    val recording: Boolean
        get() = isRecording.get()

    data class RecordingResult(
        val file: File,
        val frameCount: Int,
        val durationMs: Long
    )

    fun startRecording(
        outputPath: String,
        characteristics: CameraCharacteristics,
        initialResult: CaptureResult?,
        targetFps: Float = 24.0f,
        activeLutName: String? = null,
        activeLogName: String? = null,
        orientation: Int = 0
    ): Boolean {
        if (isRecording.get()) {
            Log.w(TAG, "Recording already in progress")
            return false
        }

        val rawSizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(android.graphics.ImageFormat.RAW_SENSOR)
            ?: characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(android.graphics.ImageFormat.RAW10)
        val selectedSize = rawSizes?.firstOrNull() ?: characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        val width = selectedSize?.width ?: 1920
        val height = selectedSize?.height ?: 1080

        val cfaArrangement = characteristics.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: 0
        val cfaPattern = when (cfaArrangement) {
            CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB -> RawVideoNative.CFA_RGGB
            CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GRBG -> RawVideoNative.CFA_GRBG
            CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GBRG -> RawVideoNative.CFA_GBRG
            CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR -> RawVideoNative.CFA_BGGR
            else -> RawVideoNative.CFA_RGGB
        }

        val whiteLevel = characteristics.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023
        val blackLevelPattern = characteristics.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
        val blackLevelArray = FloatArray(4) { idx ->
            blackLevelPattern?.getOffsetForIndex(idx % 2, idx / 2)?.toFloat() ?: 64.0f
        }

        val colorMatrix1 = FloatArray(9) { 0f }
        val cm1 = characteristics.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1)
        if (cm1 != null) {
            for (r in 0 until 3) {
                for (c in 0 until 3) {
                    val rational = cm1.getElement(c, r)
                    colorMatrix1[r * 3 + c] = rational.numerator.toFloat() / rational.denominator.toFloat()
                }
            }
        } else {
            colorMatrix1[0] = 1f; colorMatrix1[4] = 1f; colorMatrix1[8] = 1f
        }

        val colorMatrix2 = FloatArray(9) { 0f }
        val cm2 = characteristics.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2)
        if (cm2 != null) {
            for (r in 0 until 3) {
                for (c in 0 until 3) {
                    val rational = cm2.getElement(c, r)
                    colorMatrix2[r * 3 + c] = rational.numerator.toFloat() / rational.denominator.toFloat()
                }
            }
        } else {
            System.arraycopy(colorMatrix1, 0, colorMatrix2, 0, 9)
        }

        val forwardMatrix1 = FloatArray(9) { 0f }
        val fm1 = characteristics.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1)
        if (fm1 != null) {
            for (r in 0 until 3) {
                for (c in 0 until 3) {
                    val rational = fm1.getElement(c, r)
                    forwardMatrix1[r * 3 + c] = rational.numerator.toFloat() / rational.denominator.toFloat()
                }
            }
        } else {
            forwardMatrix1[0] = 1f; forwardMatrix1[4] = 1f; forwardMatrix1[8] = 1f
        }

        val forwardMatrix2 = FloatArray(9) { 0f }
        val fm2 = characteristics.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2)
        if (fm2 != null) {
            for (r in 0 until 3) {
                for (c in 0 until 3) {
                    val rational = fm2.getElement(c, r)
                    forwardMatrix2[r * 3 + c] = rational.numerator.toFloat() / rational.denominator.toFloat()
                }
            }
        } else {
            System.arraycopy(forwardMatrix1, 0, forwardMatrix2, 0, 9)
        }

        val calibrationIlluminant1 = (characteristics.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1) ?: 21).toInt()
        val calibrationIlluminant2 = (characteristics.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2) ?: 17).toInt()
        val baselineExposure = 0.0f
        val make = android.os.Build.MANUFACTURER
        val model = android.os.Build.MODEL

        val neutralPoint = FloatArray(3) { 1.0f }
        val np = initialResult?.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)
        if (np != null && np.size >= 3 && np[0].numerator > 0 && np[1].numerator > 0 && np[2].numerator > 0) {
            neutralPoint[0] = np[0].numerator.toFloat() / np[0].denominator.toFloat()
            neutralPoint[1] = np[1].numerator.toFloat() / np[1].denominator.toFloat()
            neutralPoint[2] = np[2].numerator.toFloat() / np[2].denominator.toFloat()
        } else {
            val wbGains = initialResult?.get(CaptureResult.COLOR_CORRECTION_GAINS)
            if (wbGains != null && wbGains.red > 0.1f && wbGains.blue > 0.1f) {
                val gAvg = (wbGains.greenEven + wbGains.greenOdd) * 0.5f
                neutralPoint[0] = 1.0f / (wbGains.red / gAvg)
                neutralPoint[1] = 1.0f
                neutralPoint[2] = 1.0f / (wbGains.blue / gAvg)
            } else {
                // Typical smartphone daylight neutral point fallback (prevents green cast)
                neutralPoint[0] = 0.50f
                neutralPoint[1] = 1.00f
                neutralPoint[2] = 0.60f
            }
        }

        nativeHandle = RawVideoNative.nativeStartRecording(
            outputPath = outputPath,
            width = width,
            height = height,
            bitDepth = 10,
            cfaPattern = cfaPattern,
            fps = targetFps,
            compressionType = RawVideoNative.COMPRESSION_NEON_DPCM_LZ4,
            audioSampleRate = 48000,
            audioChannels = 1,
            audioBitDepth = 16,
            whiteLevel = whiteLevel,
            blackLevel = blackLevelArray,
            colorMatrix1 = colorMatrix1,
            colorMatrix2 = colorMatrix2,
            forwardMatrix1 = forwardMatrix1,
            forwardMatrix2 = forwardMatrix2,
            neutralPoint = neutralPoint,
            lutName = activeLutName,
            logName = activeLogName,
            orientation = orientation,
            calibrationIlluminant1 = calibrationIlluminant1,
            calibrationIlluminant2 = calibrationIlluminant2,
            baselineExposure = baselineExposure,
            make = make,
            model = model
        )

        if (nativeHandle == 0L) {
            Log.e(TAG, "Failed to start native raw video recorder")
            return false
        }

        currentOutputPath = outputPath
        recordedFrames = 0
        startTimestampNs = System.nanoTime()

        // Start synchronized audio recorder
        audioRecorder = AudioRecorder(sampleRate = 48000) { pcmBuffer, size, timestampNs, sampleCount ->
            if (nativeHandle != 0L && isRecording.get()) {
                RawVideoNative.nativePushAudioPacket(
                    handle = nativeHandle,
                    pcmBuffer = pcmBuffer,
                    pcmSize = size,
                    timestampNs = timestampNs,
                    sampleCount = sampleCount
                )
            }
        }.apply { start() }

        isRecording.set(true)
        Log.i(TAG, "Raw video recording started: $outputPath (${width}x${height} @ ${targetFps}fps, orient=$orientation)")
        return true
    }

    fun onRawImageAvailable(image: Image, result: CaptureResult?): Boolean {
        if (!isRecording.get() || nativeHandle == 0L) {
            image.close()
            return false
        }

        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val dataSize = buffer.remaining()
            val width = image.width
            val height = image.height
            val rowStride = plane.rowStride

            val timestampNs = result?.get(CaptureResult.SENSOR_TIMESTAMP) ?: System.nanoTime()
            val exposureTimeNs = result?.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 10_000_000L
            val iso = result?.get(CaptureResult.SENSOR_SENSITIVITY) ?: 100
            val fNumber = result?.get(CaptureResult.LENS_APERTURE) ?: 0.0f
            val focalLength = result?.get(CaptureResult.LENS_FOCAL_LENGTH) ?: 0.0f

            val neutralPoint = FloatArray(3) { 1.0f }
            val np = result?.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)
            if (np != null && np.size >= 3 && np[0].numerator > 0 && np[1].numerator > 0 && np[2].numerator > 0) {
                neutralPoint[0] = np[0].numerator.toFloat() / np[0].denominator.toFloat()
                neutralPoint[1] = np[1].numerator.toFloat() / np[1].denominator.toFloat()
                neutralPoint[2] = np[2].numerator.toFloat() / np[2].denominator.toFloat()
            } else {
                val wbGains = result?.get(CaptureResult.COLOR_CORRECTION_GAINS)
                if (wbGains != null && wbGains.red > 0.1f && wbGains.blue > 0.1f) {
                    val gAvg = (wbGains.greenEven + wbGains.greenOdd) * 0.5f
                    neutralPoint[0] = 1.0f / (wbGains.red / gAvg)
                    neutralPoint[1] = 1.0f
                    neutralPoint[2] = 1.0f / (wbGains.blue / gAvg)
                } else {
                    neutralPoint[0] = 0.50f
                    neutralPoint[1] = 1.00f
                    neutralPoint[2] = 0.60f
                }
            }

            val pushed = RawVideoNative.nativePushVideoFrame(
                handle = nativeHandle,
                bayerBuffer = buffer,
                dataSize = dataSize,
                width = width,
                height = height,
                rowStride = rowStride,
                timestampNs = timestampNs,
                exposureTimeNs = exposureTimeNs,
                iso = iso,
                neutralColorPoint = neutralPoint,
                fNumber = fNumber,
                focalLength = focalLength
            )

            if (pushed) {
                recordedFrames++
            }
            return pushed
        } catch (e: Exception) {
            Log.e(TAG, "Error pushing raw video frame", e)
            return false
        } finally {
            image.close()
        }
    }

    fun stopRecording(): RecordingResult? {
        if (!isRecording.getAndSet(false) || nativeHandle == 0L) {
            return null
        }

        audioRecorder?.stop()
        audioRecorder = null

        val success = RawVideoNative.nativeStopRecording(nativeHandle)
        nativeHandle = 0L

        val path = currentOutputPath
        currentOutputPath = null
        val durationMs = (System.nanoTime() - startTimestampNs) / 1_000_000L

        if (success && path != null) {
            val file = File(path)
            Log.i(TAG, "Raw video saved: ${file.absolutePath} ($recordedFrames frames, $durationMs ms)")
            return RecordingResult(file, recordedFrames, durationMs)
        }
        return null
    }
}
