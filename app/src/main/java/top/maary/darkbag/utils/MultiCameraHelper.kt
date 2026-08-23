package top.maary.darkbag.utils

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Log
import kotlin.math.sqrt

enum class MultiCameraCountPreference(val key: String, val title: String) {
    AUTO_MAX("auto_max", "Auto (Max Available)"),
    DUAL("dual", "Dual Cameras (2)"),
    TRIPLE("triple", "Triple Cameras (3)");

    companion object {
        fun fromKey(key: String?): MultiCameraCountPreference {
            return entries.find { it.key == key } ?: AUTO_MAX
        }
    }
}

enum class DualLensPairPreference(val key: String, val title: String) {
    WIDE_ULTRAWIDE("wide_ultrawide", "Ultra-Wide + Wide (0.6x + 1.0x)"),
    WIDE_TELE("wide_tele", "Wide + Telephoto (1.0x + Tele)"),
    ULTRAWIDE_TELE("ultrawide_tele", "Ultra-Wide + Telephoto (0.6x + Tele)");

    companion object {
        fun fromKey(key: String?): DualLensPairPreference {
            return entries.find { it.key == key } ?: WIDE_ULTRAWIDE
        }
    }
}

data class PhysicalLensInfo(
    val physicalId: String,
    val name: String,
    val focalLength: Float,
    val equivalentFocalLength: Float,
    val multiplier: Float,
    val type: LensType,
    val characteristics: CameraCharacteristics? = null
)

data class LogicalMultiCameraInfo(
    val logicalCameraId: String,
    val isLogicalMultiCamera: Boolean,
    val syncType: Int, // SYNC_TYPE_CALIBRATED, SYNC_TYPE_APPROXIMATE
    val physicalLenses: List<PhysicalLensInfo>
)

object MultiCameraHelper {
    private const val TAG = "MultiCameraHelper"

    fun getLogicalMultiCameraInfo(context: Context, facing: Int = CameraCharacteristics.LENS_FACING_BACK): LogicalMultiCameraInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return null
        }

        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return null
        return try {
            val cameraIds = cameraManager.cameraIdList
            for (id in cameraIds) {
                val chars = cameraManager.getCameraCharacteristics(id)
                val lensFacing = chars.get(CameraCharacteristics.LENS_FACING)
                if (lensFacing != facing) continue

                val capabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
                val isMultiCam = capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)
                val physicalIds = chars.physicalCameraIds

                if (isMultiCam && physicalIds.isNotEmpty()) {
                    val syncType = chars.get(CameraCharacteristics.LOGICAL_MULTI_CAMERA_SENSOR_SYNC_TYPE)
                        ?: CameraCharacteristics.LOGICAL_MULTI_CAMERA_SENSOR_SYNC_TYPE_APPROXIMATE

                    // Calculate baseline focal length (e.g. from main wide)
                    var mainWideEqFocal = 24f
                    val physicalList = mutableListOf<PhysicalLensInfo>()

                    // First pass: extract equivalent focal length of all physical cameras
                    val tempInfos = mutableListOf<Pair<String, CameraCharacteristics>>()
                    for (physId in physicalIds) {
                        try {
                            val physChars = cameraManager.getCameraCharacteristics(physId)
                            tempInfos.add(physId to physChars)
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to get characteristics for physical camera $physId", e)
                        }
                    }

                    // Find 1.0x baseline lens (approx ~22-30mm eq focal length or closest)
                    val focalMap = tempInfos.map { (pid, pchars) ->
                        pid to calculateEquivalentFocalLength(pchars)
                    }
                    val mainPair = focalMap.find { it.second in 22f..30f } ?: focalMap.firstOrNull()
                    if (mainPair != null) {
                        mainWideEqFocal = mainPair.second
                    }

                    for ((physId, physChars) in tempInfos) {
                        val eqFocal = calculateEquivalentFocalLength(physChars)
                        val f = physChars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 0f
                        val multiplier = if (mainWideEqFocal > 0f) eqFocal / mainWideEqFocal else 1.0f

                        val type = when {
                            eqFocal < 22f -> LensType.ULTRA_WIDE
                            eqFocal < 35f -> LensType.WIDE
                            else -> LensType.TELE
                        }

                        val name = String.format(java.util.Locale.US, "%.1fx", multiplier)
                        physicalList.add(
                            PhysicalLensInfo(
                                physicalId = physId,
                                name = name,
                                focalLength = f,
                                equivalentFocalLength = eqFocal,
                                multiplier = multiplier,
                                type = type,
                                characteristics = physChars
                            )
                        )
                    }

                    physicalList.sortBy { it.multiplier }

                    return LogicalMultiCameraInfo(
                        logicalCameraId = id,
                        isLogicalMultiCamera = true,
                        syncType = syncType,
                        physicalLenses = physicalList
                    )
                }
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error checking logical multi camera info", e)
            null
        }
    }

    fun isMultiCameraSupported(context: Context): Boolean {
        return getLogicalMultiCameraInfo(context) != null
    }

    fun resolveActivePhysicalLenses(
        logicalInfo: LogicalMultiCameraInfo,
        countPref: MultiCameraCountPreference,
        pairPref: DualLensPairPreference,
        maxHardwareSupported: Int = 3
    ): List<PhysicalLensInfo> {
        val lenses = logicalInfo.physicalLenses
        if (lenses.isEmpty()) return emptyList()
        if (lenses.size <= 2) return lenses

        val effectiveCountLimit = when (countPref) {
            MultiCameraCountPreference.AUTO_MAX -> minOf(lenses.size, maxHardwareSupported)
            MultiCameraCountPreference.DUAL -> 2
            MultiCameraCountPreference.TRIPLE -> minOf(3, minOf(lenses.size, maxHardwareSupported))
        }

        if (effectiveCountLimit >= 3 && lenses.size >= 3) {
            // Select Ultra-wide, Wide, Tele
            val uw = lenses.find { it.type == LensType.ULTRA_WIDE } ?: lenses.first()
            val tele = lenses.findLast { it.type == LensType.TELE } ?: lenses.last()
            val wide = lenses.find { it != uw && it != tele } ?: lenses[1]
            return listOf(uw, wide, tele).distinct().sortedBy { it.multiplier }
        }

        // 2 lenses selection
        val uw = lenses.find { it.type == LensType.ULTRA_WIDE } ?: lenses.first()
        val wide = lenses.find { it.type == LensType.WIDE } ?: lenses.getOrNull(1) ?: lenses.first()
        val tele = lenses.findLast { it.type == LensType.TELE } ?: lenses.last()

        return when (pairPref) {
            DualLensPairPreference.WIDE_ULTRAWIDE -> listOf(uw, wide).distinct().sortedBy { it.multiplier }
            DualLensPairPreference.WIDE_TELE -> listOf(wide, tele).distinct().sortedBy { it.multiplier }
            DualLensPairPreference.ULTRAWIDE_TELE -> listOf(uw, tele).distinct().sortedBy { it.multiplier }
        }
    }

    private fun calculateEquivalentFocalLength(chars: CameraCharacteristics): Float {
        val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
        val sensorSize = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)

        if (focalLengths == null || sensorSize == null || focalLengths.isEmpty()) return 24f

        val f = focalLengths[0]
        val sw = sensorSize.width
        val sh = sensorSize.height

        val diag = sqrt((sw * sw + sh * sh).toDouble()).toFloat()
        return f * (43.27f / diag)
    }
}
