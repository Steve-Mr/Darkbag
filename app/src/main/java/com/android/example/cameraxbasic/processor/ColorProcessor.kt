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
        outputJpgPath: String?
    ): Boolean
}
