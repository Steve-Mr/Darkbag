package top.maary.darkbag.imaging

import android.graphics.Bitmap
import java.nio.ByteBuffer

/**
 * Native bindings for imaging pipeline operations including burst evaluation,
 * in-place accepted frames compaction, and ultra-fast strided thumbnail generation.
 */
object ImagingNative {

    init {
        System.loadLibrary("native-lib")
    }

    const val CFA_RGGB = 0
    const val CFA_GRBG = 1
    const val CFA_GBRG = 2
    const val CFA_BGGR = 3

    data class BurstCompactResult(
        val anchorIndex: Int,
        val acceptedCount: Int,
        val acceptedIndices: IntArray
    )

    /**
     * Evaluates burst sharpness using green-channel gradient energy (sampling both Gr and Gb rows),
     * applies dynamic noise floor rejection, selects Gaussian-weighted anchor frame, and performs
     * zero-copy in-place compact rearrangement in [megaBuffer] so that slots 0 until acceptedCount
     * contain only sharp accepted frames with anchor at slot 0.
     *
     * @param megaBuffer Direct ByteBuffer containing all contiguous burst frames
     * @param numFrames Total number of frames in burst
     * @param width Frame width in pixels
     * @param height Frame height in pixels
     * @param rowStride Row stride in bytes (e.g. width * 2)
     * @param cfaPattern CFA arrangement (0: RGGB, 1: GRBG, 2: GBRG, 3: BGGR)
     * @param iso Sensor ISO sensitivity
     * @param triggerIndex Index of shutter trigger frame (default 0)
     * @param rejectionThreshold Relative sharpness cutoff ratio below which frames are discarded
     * @return IntArray where [0] is anchorIndex, [1] is acceptedCount, followed by acceptedIndices,
     *         or null on error.
     */
    external fun nativeEvaluateBurstAndCompact(
        megaBuffer: ByteBuffer,
        numFrames: Int,
        width: Int,
        height: Int,
        rowStride: Int,
        cfaPattern: Int,
        iso: Int,
        triggerIndex: Int = 0,
        rejectionThreshold: Float = 0.45f
    ): IntArray?

    /**
     * Ultra-fast shutter feedback fallback thumbnail generator (<4ms, 0 off-heap/heap allocations).
     * Directly samples 256x256 points from [bayerBuffer], performs 2x2 Bayer Quad single-point
     * debayering with black level subtraction, white balance, and CCM restoration directly into [outBitmap].
     *
     * @param bayerBuffer Direct ByteBuffer containing raw 16-bit Bayer frame
     * @param width Sensor raw width
     * @param height Sensor raw height
     * @param rowStride Row stride in bytes
     * @param cfaPattern CFA pattern
     * @param whiteLevel Sensor white level
     * @param blackLevel Sensor black level pedestal
     * @param whiteBalance WB gains (e.g. [R, Gr, Gb, B] or [R, G, B])
     * @param ccm 3x3 Color correction matrix (9 floats)
     * @param outBitmap Target Bitmap (ARGB_8888, typically 256x256)
     * @param orientation Display orientation (0, 90, 180, 270)
     * @return true if thumbnail was generated successfully
     */
    external fun nativeGenerateStridedThumbnail(
        bayerBuffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        cfaPattern: Int,
        whiteLevel: Int,
        blackLevel: Float,
        whiteBalance: FloatArray?,
        ccm: FloatArray?,
        outBitmap: Bitmap,
        orientation: Int = 0
    ): Boolean

    /**
     * Kotlin-friendly wrapper for [nativeEvaluateBurstAndCompact].
     */
    fun evaluateBurstAndCompact(
        megaBuffer: ByteBuffer,
        numFrames: Int,
        width: Int,
        height: Int,
        rowStride: Int = width * 2,
        cfaPattern: Int = CFA_RGGB,
        iso: Int = 100,
        triggerIndex: Int = 0,
        rejectionThreshold: Float = 0.45f
    ): BurstCompactResult? {
        val res = nativeEvaluateBurstAndCompact(
            megaBuffer = megaBuffer,
            numFrames = numFrames,
            width = width,
            height = height,
            rowStride = rowStride,
            cfaPattern = cfaPattern,
            iso = iso,
            triggerIndex = triggerIndex,
            rejectionThreshold = rejectionThreshold
        ) ?: return null

        if (res.size < 2) return null
        val anchor = res[0]
        val acceptedCount = res[1]
        val indices = if (res.size >= 2 + acceptedCount) {
            res.copyOfRange(2, 2 + acceptedCount)
        } else {
            intArrayOf(anchor)
        }

        return BurstCompactResult(
            anchorIndex = anchor,
            acceptedCount = acceptedCount,
            acceptedIndices = indices
        )
    }

    /**
     * Kotlin-friendly wrapper for [nativeGenerateStridedThumbnail].
     */
    fun generateStridedThumbnail(
        bayerBuffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int = width * 2,
        cfaPattern: Int = CFA_RGGB,
        whiteLevel: Int = 1023,
        blackLevel: Float = 64f,
        whiteBalance: FloatArray? = null,
        ccm: FloatArray? = null,
        outBitmap: Bitmap,
        orientation: Int = 0
    ): Boolean {
        return nativeGenerateStridedThumbnail(
            bayerBuffer = bayerBuffer,
            width = width,
            height = height,
            rowStride = rowStride,
            cfaPattern = cfaPattern,
            whiteLevel = whiteLevel,
            blackLevel = blackLevel,
            whiteBalance = whiteBalance,
            ccm = ccm,
            outBitmap = outBitmap,
            orientation = orientation
        )
    }
}
