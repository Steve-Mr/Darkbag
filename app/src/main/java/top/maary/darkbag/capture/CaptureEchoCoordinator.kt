package top.maary.darkbag.capture

import android.graphics.Bitmap
import android.util.Log
import android.view.TextureView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.maary.darkbag.imaging.ImagingNative
import java.nio.ByteBuffer

/**
 * Coordinates immediate visual feedback upon shutter press using a dual-channel elastic fallback mechanism:
 * - Primary channel: Captures GPU rendered frame directly from [viewFinder] (<2ms, zero CPU overhead, WYSIWYG).
 * - Fallback channel: If viewFinder is null, unavailable, or frame capture fails, executes 256px Bayer
 *   strided subsampling fallback via [ImagingNative.nativeGenerateStridedThumbnail] (<4ms, zero off-heap alloc).
 */
class CaptureEchoCoordinator {

    companion object {
        const val ECHO_THUMBNAIL_SIZE = 256
        private const val TAG = "CaptureEchoCoordinator"

        private val defaultInstance by lazy { CaptureEchoCoordinator() }

        suspend fun captureEcho(
            viewFinder: TextureView?,
            fallbackParams: RawFallbackParams? = null
        ): Bitmap? = defaultInstance.captureEchoThumbnail(viewFinder, fallbackParams)
    }

    data class RawFallbackParams(
        val bayerBuffer: ByteBuffer,
        val width: Int,
        val height: Int,
        val rowStride: Int = width * 2,
        val cfaPattern: Int = ImagingNative.CFA_RGGB,
        val whiteLevel: Int = 1023,
        val blackLevel: Float = 64f,
        val whiteBalance: FloatArray? = null,
        val ccm: FloatArray? = null,
        val orientation: Int = 0
    )

    /**
     * Captures an immediate echo thumbnail bitmap.
     *
     * @param viewFinder Optional active TextureView/AutoFitTextureView
     * @param fallbackParams Optional parameters for native Bayer subsampling if primary channel fails
     * @return 256x256 Bitmap if capture succeeded, or null if both channels failed
     */
    suspend fun captureEchoThumbnail(
        viewFinder: TextureView?,
        fallbackParams: RawFallbackParams? = null
    ): Bitmap? {
        // 1. Primary channel: Try viewFinder GPU rendered frame
        if (viewFinder != null) {
            val gpuFrame = tryCaptureGpuFrame(viewFinder)
            if (gpuFrame != null) {
                return gpuFrame
            }
        }

        // 2. Fallback channel: Native Bayer strided subsampling
        if (fallbackParams != null) {
            return tryCaptureRawFallback(fallbackParams)
        }

        return null
    }

    /**
     * Primary channel: Captures GPU rendered frame directly from [TextureView].
     * Runs on Dispatchers.Main as required by Android UI toolkit.
     */
    private suspend fun tryCaptureGpuFrame(viewFinder: TextureView): Bitmap? {
        return withContext(Dispatchers.Main.immediate) {
            try {
                if (viewFinder.isAvailable) {
                    viewFinder.getBitmap(ECHO_THUMBNAIL_SIZE, ECHO_THUMBNAIL_SIZE)
                } else {
                    null
                }
            } catch (e: Exception) {
                Log.w(TAG, "Primary GPU frame capture failed, falling back to secondary", e)
                null
            }
        }
    }

    /**
     * Fallback channel: Runs native Bayer subsampling on Dispatchers.Default.
     */
    private suspend fun tryCaptureRawFallback(params: RawFallbackParams): Bitmap? {
        return withContext(Dispatchers.Default) {
            try {
                val outBitmap = Bitmap.createBitmap(
                    ECHO_THUMBNAIL_SIZE,
                    ECHO_THUMBNAIL_SIZE,
                    Bitmap.Config.ARGB_8888
                )
                val ok = ImagingNative.generateStridedThumbnail(
                    bayerBuffer = params.bayerBuffer,
                    width = params.width,
                    height = params.height,
                    rowStride = params.rowStride,
                    cfaPattern = params.cfaPattern,
                    whiteLevel = params.whiteLevel,
                    blackLevel = params.blackLevel,
                    whiteBalance = params.whiteBalance,
                    ccm = params.ccm,
                    outBitmap = outBitmap,
                    orientation = params.orientation
                )
                if (ok) {
                    outBitmap
                } else {
                    outBitmap.recycle()
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Secondary native Bayer thumbnail fallback failed", e)
                null
            }
        }
    }
}
