package com.android.example.cameraxbasic.processor

import java.nio.ByteBuffer

object ColorProcessor {
    init {
        System.loadLibrary("native-lib")
    }

    /**
     * @param dngData Byte array containing the full DNG file.
     * @param targetLog Index of target log curve.
     * @param lutPath Path to .cube file.
     * @param outputTiffPath Output path for TIFF.
     * @param outputJpgPath Output path for JPEG.
     * @param useGpu Whether to use GPU acceleration.
     * @return 0 for GPU Success, 1 for CPU Success (Fallback or requested), -1 for Failure.
     */
    external fun processRaw(
        dngData: ByteArray,
        targetLog: Int,
        lutPath: String?,
        outputTiffPath: String?,
        outputJpgPath: String?,
        useGpu: Boolean
    ): Int

    /**
     * Loads a .cube LUT file into a flat float array (RGB interleaved).
     * @param lutPath Path to .cube file.
     * @return Float array of size N^3 * 3, or null if loading failed.
     */
    external fun loadLutData(lutPath: String): FloatArray?
}
