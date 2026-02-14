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

    // Static cache to persist across repository instances if necessary,
    // but here we use instance cache as it's owned by CameraFragment.
    private val idToCharsCache = mutableMapOf<String, CameraCharacteristics>()
    private var hasProbed = false

    private fun probeAllCameras() {
        if (hasProbed) return
        Log.d(TAG, "Performing one-time aggressive camera probe (0-63)")
        val probeIds = mutableSetOf<String>()
        probeIds.addAll(cameraManager.cameraIdList)
        for (i in 0..63) probeIds.add(i.toString())

        for (id in probeIds) {
            try {
                val chars = cameraManager.getCameraCharacteristics(id)
                idToCharsCache[id] = chars
            } catch (e: Exception) {}
        }
        hasProbed = true
    }

    fun enumerateCameras(cameraXIds: Set<String>, facing: Int = CameraCharacteristics.LENS_FACING_BACK): List<LensInfo> {
        probeAllCameras()

        val availableLenses = mutableListOf<LensInfo>()
        val idToChars = idToCharsCache.filter { it.value.get(CameraCharacteristics.LENS_FACING) == facing }

        // 2. Baseline for Multipliers
        var mainWideEqFocal = 24f
        val facingCameraIds = idToChars.keys
        val mainId = if (facingCameraIds.contains("0")) "0" else if (facingCameraIds.contains("1")) "1" else facingCameraIds.firstOrNull()
        mainId?.let { id ->
            mainWideEqFocal = calculateEquivalentFocalLength(idToChars[id]!!)
        }

        // 3. Identify Anchor and All Physicals
        val currentFacingIds = idToChars.keys

        // Anchor is the first facing-matched ID that CameraX actually supports
        val anchorId = cameraXIds.find { id ->
            idToChars[id]?.get(CameraCharacteristics.LENS_FACING) == facing
        } ?: currentFacingIds.firstOrNull() ?: return emptyList()

        val anchorChars = idToChars[anchorId] ?: return emptyList()
        val zoomRange = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            anchorChars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
        } else null

        // A. Add ALL facing-matched physical sensors found during probe
        // We prioritize physical sensors to give the user direct hardware access.
        for (id in currentFacingIds) {
            val chars = idToChars[id] ?: continue

            // Strategy: Prefer direct Camera2 for all physical/logical sensors to ensure consistent hardware control.
            // Only use CameraX if explicitly requested via settings (handled in CameraFragment).
            val isAnchor = id == anchorId

            val info = createLensInfo(
                id = id,
                physicalId = null,
                chars = chars,
                mainFocal35mm = mainWideEqFocal,
                isAuto = isAnchor,
                zoomRange = if (isAnchor) zoomRange else null,
                useCamera2 = true // Prefer Camera2 for everyone by default
            )

            // Avoid adding duplicates (e.g. if we already have a lens with this sensorId)
            if (availableLenses.none { it.sensorId == info.sensorId }) {
                availableLenses.add(info)
            }
        }

        // B. Ensure we have at least the anchor camera
        if (availableLenses.none { it.id == anchorId && it.physicalId == null }) {
            availableLenses.add(createLensInfo(anchorId, null, anchorChars, mainWideEqFocal, isAuto = true, zoomRange = zoomRange, useCamera2 = true))
        }

        // Sort by focal length multiplier
        return availableLenses.sortedBy { it.multiplier }
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
            else -> String.format("%.1fx", multiplier)
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
