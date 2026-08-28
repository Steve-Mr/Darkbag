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

enum class MultiCameraHardwareType {
    NATIVE_LOGICAL,          // API 28+ Logical Multi-Camera with physicalCameraIds on single session
    CONCURRENT_STANDALONE,   // Android 11+ Concurrent Camera IDs (separate CameraDevice instances in parallel)
    FAST_RELAY_BURST,        // Near-instantaneous fast sequential relay across standalone cameras
    NONE
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
    val physicalLenses: List<PhysicalLensInfo>,
    val hardwareType: MultiCameraHardwareType = MultiCameraHardwareType.NATIVE_LOGICAL
)

object MultiCameraHelper {
    private const val TAG = "MultiCameraHelper"

    fun isConcurrentFrontBackSupported(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return false
        return try {
            val concurrentSets = cameraManager.concurrentCameraIds
            concurrentSets.any { set ->
                var hasBack = false
                var hasFront = false
                for (id in set) {
                    val facing = cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
                    if (facing == CameraCharacteristics.LENS_FACING_BACK) hasBack = true
                    if (facing == CameraCharacteristics.LENS_FACING_FRONT) hasFront = true
                }
                hasBack && hasFront
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to query concurrentCameraIds", e)
            false
        }
    }

    fun getLogicalMultiCameraInfo(
        context: Context,
        facing: Int = CameraCharacteristics.LENS_FACING_BACK
    ): LogicalMultiCameraInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return null
        }

        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return null

        // 1. First probe for native Logical Multi-Camera (API 28+)
        try {
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

                    var mainWideEqFocal = 24f
                    val physicalList = mutableListOf<PhysicalLensInfo>()

                    val tempInfos = mutableListOf<Pair<String, CameraCharacteristics>>()
                    for (physId in physicalIds) {
                        try {
                            val physChars = cameraManager.getCameraCharacteristics(physId)
                            tempInfos.add(physId to physChars)
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to get characteristics for physical camera $physId", e)
                        }
                    }

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
                        physicalLenses = physicalList,
                        hardwareType = MultiCameraHardwareType.NATIVE_LOGICAL
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Native logical multi camera probe exception", e)
        }

        // 2. Fallback: Synthesize MultiCamera from standalone physical cameras probed on the device
        try {
            val repository = CameraRepository(context)
            val allLenses = repository.enumerateCameras(facing)
            val standalonePhysicalLenses = allLenses.filter { !it.isLogicalAuto && !it.isZoomPreset }

            if (standalonePhysicalLenses.size >= 2) {
                var isConcurrentSupported = false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try {
                        val concurrentCombinations = cameraManager.concurrentCameraIds
                        val ids = standalonePhysicalLenses.map { it.id }.toSet()
                        isConcurrentSupported = concurrentCombinations.any { set ->
                            set.count { ids.contains(it) } >= 2
                        }
                    } catch (e: Exception) {
                        Log.d(TAG, "concurrentCameraIds query not supported or failed", e)
                    }
                }

                val physicalList = standalonePhysicalLenses.map { lens ->
                    val chars = try { cameraManager.getCameraCharacteristics(lens.id) } catch (e: Exception) { null }
                    val lensName = if (lens.name.endsWith("x") || lens.name.endsWith("mm")) {
                        lens.name
                    } else {
                        String.format(java.util.Locale.US, "%.1fx", lens.multiplier)
                    }
                    PhysicalLensInfo(
                        physicalId = lens.id,
                        name = lensName,
                        focalLength = lens.focalLength,
                        equivalentFocalLength = lens.equivalentFocalLength,
                        multiplier = lens.multiplier,
                        type = lens.type,
                        characteristics = chars
                    )
                }.sortedBy { it.multiplier }

                val hwType = if (isConcurrentSupported) {
                    MultiCameraHardwareType.CONCURRENT_STANDALONE
                } else {
                    MultiCameraHardwareType.FAST_RELAY_BURST
                }

                val mainId = physicalList.find { it.type == LensType.WIDE }?.physicalId ?: physicalList.first().physicalId

                Log.i(TAG, "Synthesized standalone multi-camera info (hardwareType=$hwType, count=${physicalList.size})")

                return LogicalMultiCameraInfo(
                    logicalCameraId = mainId,
                    isLogicalMultiCamera = false,
                    syncType = CameraCharacteristics.LOGICAL_MULTI_CAMERA_SENSOR_SYNC_TYPE_APPROXIMATE,
                    physicalLenses = physicalList,
                    hardwareType = hwType
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to probe standalone multi-camera fallback", e)
        }

        return null
    }

    fun isMultiCameraSupported(context: Context, forceEnable: Boolean = false): Boolean {
        val info = getLogicalMultiCameraInfo(context) ?: return forceEnable
        return info.physicalLenses.size >= 2 || forceEnable
    }

    fun getHardwareTypeDescription(context: Context): String {
        val info = getLogicalMultiCameraInfo(context) ?: return "未检测到多物理镜头 (Single Camera Only)"
        return when (info.hardwareType) {
            MultiCameraHardwareType.NATIVE_LOGICAL -> "硬件原生逻辑多摄 (Native Logical Multi-Camera)"
            MultiCameraHardwareType.CONCURRENT_STANDALONE -> "独立多摄并发模式 (Concurrent Multi-Camera)"
            MultiCameraHardwareType.FAST_RELAY_BURST -> "极速接力快拍模式 (Fast Relay Burst Mode)"
            MultiCameraHardwareType.NONE -> "未检测到多物理镜头 (Single Camera Only)"
        }
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
