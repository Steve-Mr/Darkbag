package top.maary.darkbag.rawvideo

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.net.Uri
import top.maary.darkbag.models.EditConfig
import java.io.File
import java.nio.ByteBuffer

object RawVideoExporter {

    class PendingMuxerSample(
        val isAudio: Boolean,
        val data: ByteArray,
        val info: MediaCodec.BufferInfo
    )

    enum class FrameExportType {
        RAW_DNG_ONLY,
        GRADED_JPG_ONLY,
        RAW_AND_GRADED_PAIR
    }

    fun formatFrameBaseName(baseName: String, frameIndex: Int): String =
        CinemaDngFrameExporter.formatFrameBaseName(baseName, frameIndex)

    /**
     * Exports a single frame from a CinemaDNG / RAW video sequence:
     * - RAW_DNG_ONLY: exports DBAG_PHOTO_YYYYMMDD_HHMMSS_f{index}.dng
     * - GRADED_JPG_ONLY: exports DBAG_PHOTO_YYYYMMDD_HHMMSS_f{index}_graded.jpg
     * - RAW_AND_GRADED_PAIR: exports both files with matching baseName for ImageGroup pairing
     */
    suspend fun exportSingleFrameFromCinemaDng(
        context: Context,
        frameDngUri: Uri,
        baseName: String,
        frameIndex: Int,
        editConfig: EditConfig?,
        exportType: FrameExportType
    ): Pair<Uri?, Uri?> = CinemaDngFrameExporter.exportSingleFrameFromCinemaDng(
        context = context,
        frameDngUri = frameDngUri,
        baseName = baseName,
        frameIndex = frameIndex,
        editConfig = editConfig,
        exportType = exportType
    )

    /**
     * Exports a .rawvid clip into a standard Adobe CinemaDNG folder:
     * [ClipName]/
     *   ├── [ClipName]_%06d.dng
     *   └── [ClipName].wav
     */
    suspend fun exportToCinemaDng(
        context: Context,
        rawVideoUri: Uri,
        outputDir: File,
        clipName: String? = null,
        isCancelled: (() -> Boolean)? = null,
        onProgress: (current: Int, total: Int) -> Unit
    ): File? = CinemaDngFrameExporter.exportToCinemaDng(
        context = context,
        rawVideoUri = rawVideoUri,
        outputDir = outputDir,
        clipName = clipName,
        isCancelled = isCancelled,
        onProgress = onProgress
    )

    fun calculateMeasuredFps(
        totalFrames: Int,
        firstFrameNs: Long,
        lastFrameNs: Long,
        fallbackFps: Float
    ): Float = Mp4VideoMuxerPipeline.calculateMeasuredFps(
        totalFrames = totalFrames,
        firstFrameNs = firstFrameNs,
        lastFrameNs = lastFrameNs,
        fallbackFps = fallbackFps
    )

    fun calculatePtsUs(
        frameIndex: Int,
        frameTsNs: Long,
        firstFrameNs: Long,
        lastPtsUs: Long,
        fallbackIntervalUs: Long
    ): Long = Mp4VideoMuxerPipeline.calculatePtsUs(
        frameIndex = frameIndex,
        frameTsNs = frameTsNs,
        firstFrameNs = firstFrameNs,
        lastPtsUs = lastPtsUs,
        fallbackIntervalUs = fallbackIntervalUs
    )

    /**
     * Exports a .rawvid clip into a tone-mapped MP4 video with orientation hint and BT.709 colorimetry.
     */
    suspend fun exportToMp4(
        context: Context,
        rawVideoUri: Uri,
        outputFile: File,
        editConfig: EditConfig?,
        targetResolution: Int = 1080,
        isCancelled: (() -> Boolean)? = null,
        onProgress: (current: Int, total: Int) -> Unit
    ): Boolean = Mp4VideoMuxerPipeline.exportToMp4(
        context = context,
        rawVideoUri = rawVideoUri,
        outputFile = outputFile,
        editConfig = editConfig,
        targetResolution = targetResolution,
        isCancelled = isCancelled,
        onProgress = onProgress
    )

    internal fun writeDngFile(
        file: File,
        header: RawVideoNative.Header,
        meta: LongArray,
        bayerData: ByteBuffer,
        payloadSize: Int
    ) = CinemaDngWriter.writeDngFile(file, header, meta, bayerData, payloadSize)

    internal fun isExportSuccessful(muxerStarted: Boolean, videoEos: Boolean, outputFile: File): Boolean =
        Mp4VideoMuxerPipeline.isExportSuccessful(muxerStarted, videoEos, outputFile)

    internal fun bitmapToNv12(bitmap: Bitmap, argb: IntArray, nv12: ByteArray, width: Int, height: Int) =
        Mp4VideoMuxerPipeline.bitmapToNv12(bitmap, argb, nv12, width, height)
}
