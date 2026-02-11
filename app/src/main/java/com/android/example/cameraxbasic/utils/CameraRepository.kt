package com.android.example.cameraxbasic.utils

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Log
import android.util.Range
import kotlin.math.sqrt

data class LensInfo(
    val id: String,          // The ID to use for CameraX binding
    val physicalId: String?, // The physical ID to set via Camera2Interop
    val sensorId: String,    // A unique identifier for the physical sensor
    val name: String,
    val focalLength: Float,
    val equivalentFocalLength: Float,
    val multiplier: Float,
    val type: LensType,
    val isLogicalAuto: Boolean = false,
    val zoomRange: Range<Float>? = null,
    val isZoomPreset: Boolean = false,
    val targetZoomRatio: Float? = null
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

        // 1. Aggressive Probe
        val probeIds = mutableSetOf<String>()
        probeIds.addAll(cameraManager.cameraIdList)
        for (i in 0..63) probeIds.add(i.toString())

        for (id in probeIds) {
            try {
                val chars = cameraManager.getCameraCharacteristics(id)
                idToChars[id] = chars
                Log.d(TAG, "Probed ID $id: Facing=${chars.get(CameraCharacteristics.LENS_FACING)}")
            } catch (e: Exception) {}
        }

        // 2. Baseline for Multipliers
        var mainWideEqFocal = 24f
        val backCameraIds = idToChars.filter { (_, chars) ->
            chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }.keys
        val mainId = if (backCameraIds.contains("0")) "0" else backCameraIds.firstOrNull()
        mainId?.let { id ->
            mainWideEqFocal = calculateEquivalentFocalLength(idToChars[id]!!)
        }

        // 3. Process CameraX IDs
        for (id in cameraXIds) {
            val chars = idToChars[id] ?: continue
            val facing = chars.get(CameraCharacteristics.LENS_FACING)
            if (facing != CameraCharacteristics.LENS_FACING_BACK) continue

            val capabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            val isLogical = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                    capabilities?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) == true

            val zoomRange = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            } else null

            if (isLogical) {
                // Add "Auto" lens
                availableLenses.add(createLensInfo(id, null, chars, mainWideEqFocal, isAuto = true, zoomRange = zoomRange))

                // Add common zoom presets for the logical camera
                val presets = listOf(0.7f, 2.7f, 3.0f)
                for (p in presets) {
                    if (zoomRange == null || (p >= zoomRange.lower && p <= zoomRange.upper)) {
                        availableLenses.add(createLensInfo(id, null, chars, mainWideEqFocal, name = "${p}x", isPreset = true, targetZoom = p))
                    }
                }

                // Add physical components
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    for (pId in chars.physicalCameraIds) {
                        val pChars = idToChars[pId] ?: try {
                            cameraManager.getCameraCharacteristics(pId)
                        } catch (e: Exception) { null }

                        if (pChars != null) {
                            availableLenses.add(createLensInfo(id, pId, pChars, mainWideEqFocal))
                        }
                    }
                }
            } else {
                availableLenses.add(createLensInfo(id, null, chars, mainWideEqFocal))
            }
        }

        return availableLenses.sortedWith(compareBy(
            { !it.isLogicalAuto },
            { !it.isZoomPreset },
            { it.targetZoomRatio ?: 0f },
            { it.equivalentFocalLength }
        ))
    }

    private fun createLensInfo(
        id: String,
        physicalId: String?,
        chars: CameraCharacteristics,
        mainFocal35mm: Float,
        isAuto: Boolean = false,
        name: String? = null,
        isPreset: Boolean = false,
        targetZoom: Float? = null,
        zoomRange: Range<Float>? = null
    ): LensInfo {
        val eqFocal = calculateEquivalentFocalLength(chars)
        val f = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 0f
        val multiplier = eqFocal / mainFocal35mm

        val type = when {
            eqFocal < 22f -> LensType.ULTRA_WIDE
            eqFocal < 35f -> LensType.WIDE
            else -> LensType.TELE
        }

        val finalName = name ?: if (isAuto) "Auto" else String.format("%.1fx", multiplier)
        val sensorId = physicalId ?: "$id-${targetZoom ?: 0f}"

        return LensInfo(id, physicalId, sensorId, finalName, f, eqFocal, multiplier, type, isAuto, zoomRange, isPreset, targetZoom)
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
