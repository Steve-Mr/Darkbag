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
    val targetZoomRatio: Float? = null,
    val useCamera2: Boolean = false
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

        // 3. Identify Anchor and All Physicals
        val backIds = idToChars.filter { (_, chars) ->
            chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }.keys

        // Anchor is the first back-facing ID that CameraX actually supports
        val anchorId = cameraXIds.find { id ->
            idToChars[id]?.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: backIds.firstOrNull() ?: return emptyList()

        val anchorChars = idToChars[anchorId] ?: return emptyList()
        val zoomRange = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            anchorChars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
        } else null

        // A. Add "Auto" (Logical)
        availableLenses.add(createLensInfo(anchorId, null, anchorChars, mainWideEqFocal, isAuto = true, zoomRange = zoomRange))

        // B. Add common zoom presets for the anchor logical camera
        val presets = listOf(0.6f, 0.7f, 2.0f, 2.7f, 3.0f, 5.0f)
        for (p in presets) {
            if (zoomRange != null && p >= zoomRange.lower && p <= zoomRange.upper) {
                // Only add if not redundant with 1.0x (Auto handles 1.0x)
                if (Math.abs(p - 1.0f) > 0.05f) {
                    availableLenses.add(createLensInfo(anchorId, null, anchorChars, mainWideEqFocal, name = "${p}x", isPreset = true, targetZoom = p))
                }
            }
        }

        // C. Add ALL back-facing physical sensors found during probe
        for (id in backIds) {
            val chars = idToChars[id] ?: continue

            // Strategy:
            // 1. If CameraX supports this ID directly, use CameraX (id=id, physicalId=null, useCamera2=false)
            // 2. If CameraX doesn't support it, but it's a component of anchorId, use CameraX with physical lock (id=anchorId, physicalId=id, useCamera2=false)
            // 3. Otherwise, use Direct Camera2 (id=id, physicalId=null, useCamera2=true)

            val isCameraXDirect = cameraXIds.contains(id)
            val isPhysicalComponent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                anchorChars.physicalCameraIds.contains(id)
            } else false

            val info = when {
                isCameraXDirect -> createLensInfo(id, null, chars, mainWideEqFocal, useCamera2 = false)
                isPhysicalComponent -> createLensInfo(anchorId, id, chars, mainWideEqFocal, useCamera2 = false)
                else -> createLensInfo(id, null, chars, mainWideEqFocal, useCamera2 = true)
            }

            // Avoid adding duplicates (e.g. if we already have a lens with this sensorId)
            if (availableLenses.none { it.sensorId == info.sensorId }) {
                availableLenses.add(info)
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
        zoomRange: Range<Float>? = null,
        useCamera2: Boolean = false
    ): LensInfo {
        val eqFocal = calculateEquivalentFocalLength(chars)
        val f = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 0f
        val multiplier = eqFocal / mainFocal35mm

        val type = when {
            eqFocal < 22f -> LensType.ULTRA_WIDE
            eqFocal < 35f -> LensType.WIDE
            else -> LensType.TELE
        }

        val finalName = name ?: when {
            isAuto -> "Auto"
            useCamera2 -> String.format("%.1fx (S2)", multiplier)
            physicalId != null -> String.format("%.1fx (S)", multiplier)
            else -> String.format("%.1fx (L)", multiplier)
        }
        val sensorId = if (useCamera2) "c2-$id" else (physicalId ?: "$id-${targetZoom ?: 0f}")

        return LensInfo(id, physicalId, sensorId, finalName, f, eqFocal, multiplier, type, isAuto, zoomRange, isPreset, targetZoom, useCamera2)
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
