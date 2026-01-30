package com.android.example.cameraxbasic.processor

import java.nio.ByteBuffer

object ColorProcessor {
    init {
        System.loadLibrary("native-lib")
    }

    /**
     * @param rawBuffer Direct ByteBuffer containing 16-bit raw data (Bayer).
     * @param width Image width.
     * @param height Image height.
     * @param stride Row stride in bytes.
     * @param whiteLevel Sensor white level.
     * @param blackLevel Sensor black level (average).
     * @param cfaPattern 0=RGGB, 1=GRBG, 2=GBRG, 3=BGGR.
     * @param wbGains White balance gains [R, G_even, G_odd, B].
     * @param ccm Color Correction Matrix (3x3) transforming CameraRGB to XYZ.
     * @param targetLog Index of target log curve.
     * @param lutPath Path to .cube file.
     * @param outputTiffPath Output path for TIFF.
     * @param outputJpgPath Output path for JPEG.
     * @param useGpu Whether to use GPU acceleration.
     * @return 0 for GPU Success, 1 for CPU Success (Fallback or requested), -1 for Failure.
     */
    external fun processRaw(
        rawBuffer: ByteBuffer,
        width: Int,
        height: Int,
        stride: Int,
        whiteLevel: Int,
        blackLevel: Int,
        cfaPattern: Int,
        wbGains: FloatArray,
        ccm: FloatArray,
        targetLog: Int,
        lutPath: String?,
        outputTiffPath: String?,
        outputJpgPath: String?,
        useGpu: Boolean
    ): Int

    /**
     * Loads a 3D LUT from a file.
     * @param path Path to .cube file.
     * @return FloatArray where index 0 is size, and subsequent elements are RGB triplets. Returns null if loading failed.
     */
    external fun loadLutNative(path: String): FloatArray?
}
