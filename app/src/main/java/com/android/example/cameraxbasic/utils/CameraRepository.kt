package com.android.example.cameraxbasic.utils

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Log
import kotlin.math.sqrt

data class LensInfo(
    val id: String,
    val physicalId: String?,
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
        val availableLenses = mutableListOf<LensInfo>()
        val allIds = cameraManager.cameraIdList

        Log.d(TAG, "All Camera IDs: ${allIds.joinToString()}")

        // First pass: Find the main wide camera to use as 1.0x baseline
        var mainWideFocalLength35mm = 24f

        val backCameras = mutableListOf<Pair<String, CameraCharacteristics>>()
        for (id in allIds) {
            try {
                val chars = cameraManager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                    backCameras.add(id to chars)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error getting characteristics for camera $id", e)
            }
        }

        // Try to find the "main" camera (usually ID "0")
        val mainCamera = backCameras.find { it.first == "0" } ?: backCameras.firstOrNull()
        mainCamera?.let { (id, chars) ->
            mainWideFocalLength35mm = calculateEquivalentFocalLength(chars)
            Log.d(TAG, "Main Wide Camera ID: $id, Equivalent Focal Length: $mainWideFocalLength35mm")
        }

        for (id in allIds) {
            try {
                val chars = cameraManager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)

                if (facing != CameraCharacteristics.LENS_FACING_BACK) continue

                val capabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                val isLogical = capabilities?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) == true

                Log.d(TAG, "Camera ID: $id, Facing: $facing, Logical: $isLogical")

                if (isLogical && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val physicalIds = chars.physicalCameraIds
                    Log.d(TAG, "Logical Camera $id has physical IDs: $physicalIds")
                    for (pId in physicalIds) {
                        try {
                            val pChars = cameraManager.getCameraCharacteristics(pId)
                            val lensInfo = createLensInfo(id, pId, pChars, mainWideFocalLength35mm)
                            availableLenses.add(lensInfo)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error getting characteristics for physical camera $pId", e)
                        }
                    }
                } else {
                    // Non-logical or old API
                    // Check if we should add it (avoid duplicates if we somehow already added it)
                    if (availableLenses.none { it.physicalId == null && it.id == id }) {
                        val lensInfo = createLensInfo(id, null, chars, mainWideFocalLength35mm)
                        availableLenses.add(lensInfo)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing camera $id", e)
            }
        }

        // Sort lenses by focal length
        return availableLenses.sortedBy { it.focalLength }
    }

    private fun createLensInfo(id: String, physicalId: String?, chars: CameraCharacteristics, mainFocal35mm: Float): LensInfo {
        val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
        val focalLength = if (focalLengths != null && focalLengths.isNotEmpty()) focalLengths[0] else 0f
        val eqFocal = calculateEquivalentFocalLength(chars)
        val multiplier = eqFocal / mainFocal35mm

        val type = when {
            eqFocal < 20f -> LensType.ULTRA_WIDE
            eqFocal < 35f -> LensType.WIDE
            else -> LensType.TELE
        }

        val name = String.format("%.1fx", multiplier)

        return LensInfo(id, physicalId, name, focalLength, eqFocal, multiplier, type)
    }

    private fun calculateEquivalentFocalLength(chars: CameraCharacteristics): Float {
        val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
        val sensorSize = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)

        if (focalLengths == null || sensorSize == null || focalLengths.isEmpty()) return 24f

        val f = focalLengths[0]
        val sw = sensorSize.width
        val sh = sensorSize.height

        val diag = sqrt((sw * sw + sh * sh).toDouble()).toFloat()

        // Eq Focal = f * (43.27 / diag)
        return f * (43.27f / diag)
    }
}
