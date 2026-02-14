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

    companion object {
        // Global cache to persist across repository instances and fragment recreations
        private val idToCharsCache = mutableMapOf<String, CameraCharacteristics>()
        private var hasProbed = false
        private val probeLock = Any()
    }

    private fun probeAllCameras() {
        if (hasProbed) return
        synchronized(probeLock) {
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

    /**
     * Returns a unified list of physical lenses and digital focal length presets (28mm, 35mm, 2.0x).
     * Used for settings and UI logic.
     */
    fun getFocalLengthPresets(cameraXIds: Set<String>, facing: Int = CameraCharacteristics.LENS_FACING_BACK): List<LensInfo> {
        val physicalLenses = enumerateCameras(cameraXIds, facing)
        val result = physicalLenses.filter { !it.isLogicalAuto }.toMutableList()

        // 2.0x virtual if no physical 2x exists (between 1.8x and 2.2x)
        val hasPhysical2x = physicalLenses.any { it.multiplier in 1.8f..2.2f }
        if (!hasPhysical2x) {
            val mainWide = result.find { it.multiplier in 0.95f..1.05f }
            if (mainWide != null) {
                result.add(mainWide.copy(
                    sensorId = "${mainWide.sensorId}-virtual-2x",
                    name = "2.0x",
                    multiplier = mainWide.multiplier * 2.0f,
                    isZoomPreset = true,
                    targetZoomRatio = 2.0f
                ))
            }
        }

        return result.sortedBy { it.multiplier }
    }

    /**
     * Returns the "main wide" (1.0x) lens for a given facing.
     */
    fun getMainWideLens(cameraXIds: Set<String>, facing: Int = CameraCharacteristics.LENS_FACING_BACK): LensInfo? {
        val lenses = enumerateCameras(cameraXIds, facing)
        return lenses.find { it.multiplier in 0.95f..1.05f && !it.isLogicalAuto }
            ?: lenses.find { it.isLogicalAuto }
            ?: lenses.firstOrNull()
    }

    /**
     * Returns sub-presets for the 1.0x lens (24mm, 28mm, 35mm).
     */
    fun get1xPresets(mainWide: LensInfo): List<LensInfo> {
        val result = mutableListOf<LensInfo>()
        val mainEqFocal = mainWide.equivalentFocalLength

        // 24mm (Base)
        result.add(mainWide.copy(
            sensorId = "${mainWide.sensorId}-24mm",
            name = "24mm",
            multiplier = mainWide.multiplier,
            isZoomPreset = true,
            targetZoomRatio = 1.0f
        ))

        // 28mm
        if (mainEqFocal <= 26f) {
            val targetEq = 28f
            val zoom = targetEq / mainEqFocal
            result.add(mainWide.copy(
                sensorId = "${mainWide.sensorId}-28mm",
                name = "28mm",
                multiplier = mainWide.multiplier * zoom,
                isZoomPreset = true,
                targetZoomRatio = zoom
            ))
        }

        // 35mm
        if (mainEqFocal <= 33f) {
            val targetEq = 35f
            val zoom = targetEq / mainEqFocal
            result.add(mainWide.copy(
                sensorId = "${mainWide.sensorId}-35mm",
                name = "35mm",
                multiplier = mainWide.multiplier * zoom,
                isZoomPreset = true,
                targetZoomRatio = zoom
            ))
        }
        return result
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
