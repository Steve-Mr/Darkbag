package com.android.example.cameraxbasic.utils

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Log
import kotlin.math.sqrt

data class LensInfo(
    val id: String,          // The ID to use for CameraX binding (must be known to CameraX)
    val physicalId: String?, // The physical ID to set via Camera2Interop (if locking a sensor)
    val sensorId: String,    // A unique identifier for the physical sensor (for deduplication)
    val name: String,
    val focalLength: Float,
    val equivalentFocalLength: Float,
    val multiplier: Float,
    val type: LensType,
    val isLogicalAuto: Boolean = false // True if this represents the system-controlled logical camera
)

enum class LensType {
    ULTRA_WIDE,
    WIDE,
    TELE,
    UNKNOWN
}

class CameraRepository(private val context: Context) {
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val TAG = "CameraRepository"

    fun enumerateCameras(cameraXIds: Set<String>): List<LensInfo> {
        val availableLenses = mutableListOf<LensInfo>()
        val idToChars = mutableMapOf<String, CameraCharacteristics>()

        // 1. Aggressive Probe to find all physical sensors and their characteristics
        val probeIds = mutableSetOf<String>()
        probeIds.addAll(cameraManager.cameraIdList)
        for (i in 0..63) probeIds.add(i.toString())

        for (id in probeIds) {
            try {
                idToChars[id] = cameraManager.getCameraCharacteristics(id)
            } catch (e: Exception) {}
        }

        // 2. Find baseline Eq Focal (Wide) for multipliers
        var mainWideEqFocal = 24f
        val backCameraIds = idToChars.filter { (_, chars) ->
            chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }.keys
        val mainId = if (backCameraIds.contains("0")) "0" else backCameraIds.firstOrNull()
        mainId?.let { id ->
            mainWideEqFocal = calculateEquivalentFocalLength(idToChars[id]!!)
        }

        // 3. For each ID CameraX knows about, extract its capabilities
        for (id in cameraXIds) {
            val chars = idToChars[id] ?: continue
            val facing = chars.get(CameraCharacteristics.LENS_FACING)
            if (facing != CameraCharacteristics.LENS_FACING_BACK) continue

            val capabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            val isLogical = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                    capabilities?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) == true

            val physicalIds = if (isLogical && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                chars.physicalCameraIds
            } else emptySet()

            if (isLogical) {
                // Add "Auto" lens for logical camera
                availableLenses.add(createLensInfo(id, null, chars, mainWideEqFocal, isAuto = true))

                // Add each physical component
                for (pId in physicalIds) {
                    val pChars = idToChars[pId] ?: try {
                        cameraManager.getCameraCharacteristics(pId)
                    } catch (e: Exception) { null }

                    if (pChars != null) {
                        availableLenses.add(createLensInfo(id, pId, pChars, mainWideEqFocal))
                    }
                }
            } else {
                // Independent Physical Camera
                availableLenses.add(createLensInfo(id, null, chars, mainWideEqFocal))
            }
        }

        // Deduplicate sensors by focal length if they have the same multiplier/name
        // (sometimes same sensor is exposed multiple times)
        val sortedLenses = availableLenses.sortedWith(compareBy({ !it.isLogicalAuto }, { it.equivalentFocalLength }))

        val uniqueLenses = mutableListOf<LensInfo>()
        for (lens in sortedLenses) {
            if (lens.isLogicalAuto) {
                uniqueLenses.add(lens)
            } else {
                // For physical lenses, try to avoid adding the same focal length twice if it belongs to the same bindId
                if (uniqueLenses.none { it.id == lens.id && it.physicalId == lens.physicalId && !it.isLogicalAuto }) {
                    uniqueLenses.add(lens)
                }
            }
        }

        return uniqueLenses
    }

    private fun createLensInfo(id: String, physicalId: String?, chars: CameraCharacteristics, mainFocal35mm: Float, isAuto: Boolean = false): LensInfo {
        val eqFocal = calculateEquivalentFocalLength(chars)
        val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
        val f = if (focalLengths != null && focalLengths.isNotEmpty()) focalLengths[0] else 0f
        val multiplier = eqFocal / mainFocal35mm

        val type = when {
            eqFocal < 22f -> LensType.ULTRA_WIDE
            eqFocal < 35f -> LensType.WIDE
            else -> LensType.TELE
        }

        val name = if (isAuto) "Auto" else String.format("%.1fx", multiplier)
        val sensorId = physicalId ?: id

        return LensInfo(id, physicalId, sensorId, name, f, eqFocal, multiplier, type, isAuto)
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
