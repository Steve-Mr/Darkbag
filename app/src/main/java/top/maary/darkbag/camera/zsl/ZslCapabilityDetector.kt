package top.maary.darkbag.camera.zsl

import android.app.ActivityManager
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Log

/**
 * Reads CameraCharacteristics without opening the camera. The admission decision is delegated
 * to [ZslCapabilityEvaluator] so it can be tested without a device.
 */
object ZslCapabilityDetector {
    private const val TAG = "ZslCapabilityDetector"

    fun inspectDefaultBackCamera(context: Context): ZslCapabilityReport {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return ZslCapabilityReport(ZslRoute.NONE, ZslReason.NO_CAMERA)
        return try {
            val candidates = manager.cameraIdList.mapNotNull { id ->
                runCatching { manager.getCameraCharacteristics(id) to id }.getOrNull()
            }.filter { (chars, _) ->
                chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            }
            val best = candidates.maxByOrNull { (chars, _) ->
                chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?.getOutputSizes(ImageFormat.RAW_SENSOR)
                    ?.maxOfOrNull { it.width.toLong() * it.height } ?: 0L
            } ?: candidates.firstOrNull()
            if (best == null) {
                ZslCapabilityReport(ZslRoute.NONE, ZslReason.NO_CAMERA)
            } else {
                inspect(context, best.second, best.first, manager)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Static ZSL inspection failed", e)
            ZslCapabilityReport(ZslRoute.NONE, ZslReason.UNKNOWN)
        }
    }

    fun inspect(context: Context, cameraId: String): ZslCapabilityReport {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return ZslCapabilityReport(ZslRoute.NONE, ZslReason.NO_CAMERA)
        return try {
            val chars = manager.getCameraCharacteristics(cameraId)
            inspect(context, cameraId, chars, manager)
        } catch (e: Exception) {
            Log.w(TAG, "Static ZSL inspection failed for $cameraId", e)
            ZslCapabilityReport(ZslRoute.NONE, ZslReason.UNKNOWN)
        }
    }

    fun inspect(
        context: Context,
        cameraId: String,
        chars: CameraCharacteristics,
        manager: CameraManager
    ): ZslCapabilityReport {
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val rawSizes = map?.getOutputSizes(ImageFormat.RAW_SENSOR)
            ?.map { it.width to it.height }
            ?.sortedByDescending { it.first.toLong() * it.second }
            .orEmpty()
        val capabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?.toSet().orEmpty()
        val hardwareLevel = chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
            ?: CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY
        val inputFormats = map?.inputFormats?.toSet().orEmpty()
        val reprocessOutputs = if (ImageFormat.PRIVATE in inputFormats) {
            runCatching {
                map?.getValidOutputFormatsForInput(ImageFormat.PRIVATE)?.toSet().orEmpty()
            }.getOrDefault(emptySet())
        } else {
            emptySet()
        }
        val aeRange = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
            ?.let { it.lower..it.upper }
        val fpsRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.map { it.lower..it.upper }
            .orEmpty()
        val memoryInfo = ActivityManager.MemoryInfo().also { info ->
            (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
                ?.getMemoryInfo(info)
        }
        val isLowRam = (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
            ?.isLowRamDevice == true
        val timestampSource = chars.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)
            ?: CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN

        val facts = ZslCameraFacts(
            apiLevel = Build.VERSION.SDK_INT,
            hardwareLevel = hardwareLevel,
            capabilities = capabilities,
            rawSizes = rawSizes,
            readSensorSettings = CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_READ_SENSOR_SETTINGS in capabilities,
            manualPostProcessing = CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING in capabilities,
            timestampSource = timestampSource,
            aeCompensationRange = aeRange,
            fpsRanges = fpsRanges,
            isLowRam = isLowRam,
            availableMemoryBytes = memoryInfo.availMem,
            inputFormats = inputFormats,
            reprocessOutputFormats = reprocessOutputs,
            flashAvailable = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        )
        if (CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW !in capabilities && rawSizes.isNotEmpty()) {
            Log.i(
                TAG,
                "Camera $cameraId exposes RAW output sizes but omits the RAW capability bit; trusting output sizes"
            )
        }
        val report = ZslCapabilityEvaluator.evaluate(facts)
        // The ring reuses the session's existing RAW reader, which is the maximum RAW size.
        val candidate = rawSizes.firstOrNull()
        return report.copy(
            cameraId = cameraId,
            rawWidth = candidate?.first ?: report.rawWidth,
            rawHeight = candidate?.second ?: report.rawHeight,
            frameBytes = candidate?.let { it.first.toLong() * it.second * 2L } ?: report.frameBytes
        )
    }

    /** Best-effort lens id for the current session; most devices expose the main camera as "0". */
    fun cameraIdFor(lensId: String?): String =
        if (lensId.isNullOrBlank()) "0" else lensId
}
