package com.android.example.cameraxbasic.utils

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Log
import kotlin.math.sqrt

data class LensInfo(
    val id: String,          // The ID to use for CameraX binding (Logical or Independent Physical)
    val physicalId: String?, // The physical ID to set via Camera2Interop (if id is Logical)
    val sensorId: String,    // A unique identifier for the physical sensor (for deduplication)
    val name: String,
    val focalLength: Float,
    val equivalentFocalLength: Float,
    val multiplier: Float,
    val type: LensType
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

    fun enumerateCameras(): List<LensInfo> {
        val discoveredSensors = mutableMapOf<String, LensInfo>()
        val allIds = mutableSetOf<String>()

        // 1. Collect all possible IDs (Public + Probed)
        allIds.addAll(cameraManager.cameraIdList)
        for (i in 0..63) {
            allIds.add(i.toString())
        }

        Log.d(TAG, "Probing Camera IDs: ${allIds.sortedBy { it.toIntOrNull() ?: 999 }}")

        // 2. First pass: Find characteristics and identify relationships
        val idToChars = mutableMapOf<String, CameraCharacteristics>()
        val logicalToPhysical = mutableMapOf<String, Set<String>>()
        val physicalToLogical = mutableMapOf<String, String>()

        for (id in allIds) {
            try {
                val chars = cameraManager.getCameraCharacteristics(id)
                idToChars[id] = chars

                val capabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                    capabilities?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) == true) {
                    val physicalIds = chars.physicalCameraIds
                    logicalToPhysical[id] = physicalIds
                    for (pId in physicalIds) {
                        physicalToLogical[pId] = id
                    }
                    Log.d(TAG, "ID $id is LOGICAL. Physical IDs: $physicalIds")
                } else {
                    Log.d(TAG, "ID $id is PHYSICAL or Independent.")
                }
            } catch (e: Exception) {
                // Ignore IDs that don't exist
            }
        }

        // 3. Find the main wide camera for baseline multiplier
        var mainWideEqFocal = 24f
        val backCameraIds = idToChars.filter { (_, chars) ->
            chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }.keys

        // Usually ID 0 is the main one. If not, pick the one with "1.0x" focal length (~4-5mm)
        val mainId = if (backCameraIds.contains("0")) "0" else backCameraIds.firstOrNull()
        mainId?.let { id ->
            mainWideEqFocal = calculateEquivalentFocalLength(idToChars[id]!!)
            Log.d(TAG, "Baseline Main Camera ID: $id, Eq Focal: $mainWideEqFocal")
        }

        // 4. Create LensInfo for all discovered physical sensors
        // Strategy:
        // - For each logical camera, add its physical components.
        // - For each independent camera, add it if not already added as a physical component.

        fun addSensor(bindId: String, physicalId: String?, chars: CameraCharacteristics) {
            val sensorUniqueId = physicalId ?: bindId
            if (discoveredSensors.containsKey(sensorUniqueId)) return

            val eqFocal = calculateEquivalentFocalLength(chars)
            val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            val f = if (focalLengths != null && focalLengths.isNotEmpty()) focalLengths[0] else 0f
            val multiplier = eqFocal / mainWideEqFocal

            val type = when {
                eqFocal < 22f -> LensType.ULTRA_WIDE
                eqFocal < 35f -> LensType.WIDE
                else -> LensType.TELE
            }

            val name = String.format("%.1fx", multiplier)

            discoveredSensors[sensorUniqueId] = LensInfo(
                id = bindId,
                physicalId = physicalId,
                sensorId = sensorUniqueId,
                name = name,
                focalLength = f,
                equivalentFocalLength = eqFocal,
                multiplier = multiplier,
                type = type
            )
            Log.d(TAG, "Added Lens: $name (Bind=$bindId, Physical=$physicalId, Sensor=$sensorUniqueId, EqFocal=$eqFocal)")
        }

        // Add from logical cameras first (preferred binding)
        for ((lId, pIds) in logicalToPhysical) {
            if (idToChars[lId]?.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) continue
            for (pId in pIds) {
                try {
                    val pChars = idToChars[pId] ?: cameraManager.getCameraCharacteristics(pId)
                    addSensor(lId, pId, pChars)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to get characteristics for physical ID $pId under logical $lId", e)
                }
            }
        }

        // Add independent cameras
        for (id in backCameraIds) {
            // Only add if not already part of a logical camera we added, OR if it's the logical ID itself acting as a single camera
            if (!physicalToLogical.containsKey(id) && !logicalToPhysical.containsKey(id)) {
                addSensor(id, null, idToChars[id]!!)
            } else if (logicalToPhysical.containsKey(id) && discoveredSensors.none { it.value.id == id }) {
                // If it's a logical ID but we haven't added any sensors for it (unlikely), add it as-is
                addSensor(id, null, idToChars[id]!!)
            }
        }

        return discoveredSensors.values.sortedBy { it.equivalentFocalLength }
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
