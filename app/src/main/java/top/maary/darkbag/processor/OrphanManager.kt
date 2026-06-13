package top.maary.darkbag.processor

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.FileInputStream
import java.io.DataOutputStream
import java.io.DataInputStream
import java.nio.ByteBuffer

object OrphanManager {

    private const val ORPHAN_DIR = "orphans"

    suspend fun dumpRequest(context: Context, request: ProcessingRequest): File? = withContext(Dispatchers.IO) {
        try {
            val dir = File(context.cacheDir, ORPHAN_DIR)
            if (!dir.exists()) dir.mkdirs()

            val orphanFile = File(dir, "orphan_${System.currentTimeMillis()}.bin")
            DataOutputStream(FileOutputStream(orphanFile)).use { out ->
                out.writeInt(request.width)
                out.writeInt(request.height)
                out.writeInt(request.combinedOrientation)
                out.writeInt(request.whiteLevel)

                out.writeInt(request.blackLevelPattern.size)
                for (b in request.blackLevelPattern) out.writeInt(b)

                out.writeBoolean(request.lensShadingMapData != null)
                request.lensShadingMapData?.let {
                    out.writeInt(it.size)
                    for (f in it) out.writeFloat(f)
                }

                out.writeInt(request.lensShadingRows)
                out.writeInt(request.lensShadingCols)
                out.writeBoolean(request.useSensorColorMatrix)

                out.writeInt(request.wb.size)
                for (f in request.wb) out.writeFloat(f)

                out.writeInt(request.ccm.size)
                for (f in request.ccm) out.writeFloat(f)

                out.writeBoolean(request.ccmAlt != null)
                request.ccmAlt?.let {
                    out.writeInt(it.size)
                    for (f in it) out.writeFloat(f)
                }

                out.writeBoolean(request.exportMatrixAB)
                out.writeInt(request.cfa)
                out.writeInt(request.targetLogIndex)

                out.writeBoolean(request.nativeLutPath != null)
                request.nativeLutPath?.let { out.writeUTF(it) }

                out.writeBoolean(request.tempJpgFile != null)
                request.tempJpgFile?.let { out.writeUTF(it) }

                out.writeBoolean(request.linearDngPath != null)
                request.linearDngPath?.let { out.writeUTF(it) }

                out.writeFloat(request.digitalGain)

                out.writeBoolean(request.debugStats != null)
                request.debugStats?.let {
                    out.writeInt(it.size)
                    for (l in it) out.writeLong(l)
                }

                out.writeFloat(request.currentZoom)
                out.writeBoolean(request.mirror)

                out.writeBoolean(request.fastDenoise)

                out.writeBoolean(request.targetUri != null)
                request.targetUri?.let { out.writeUTF(it) }

                // We'll skip metadata objects for simplicity if we can recreate them, or we can just JSON them.
                // We can't use Gson, so let's just use `org.json.JSONObject` or skip them.
                // Wait! We NEED captureMetadata to save EXIF.
                // Let's just create a dummy CaptureMetadata since this is a recovery scenario, or write its properties!

                // CaptureMetadata:
                // class CaptureMetadata(val iso: Int?, val exposureTime: Long?, val aperture: Float?, val focalLength: Float?, val captureTime: Long?, val offsetTime: String?, val focalLengthIn35mmFilm: Int?)
                out.writeInt(request.captureMetadata.iso ?: -1)
                out.writeLong(request.captureMetadata.exposureTime ?: -1L)
                out.writeFloat(request.captureMetadata.fNumber ?: -1.0f)
                out.writeFloat(request.captureMetadata.focalLength ?: -1.0f)
                out.writeLong(request.captureMetadata.dateTimeOriginal ?: -1L)

                out.writeBoolean(request.captureMetadata.offsetTime != null)
                request.captureMetadata.offsetTime?.let { out.writeUTF(it) }

                out.writeInt(request.captureMetadata.focalLengthIn35mmFilm ?: -1)

                // HalfFrameMetadata skip for now (or write it)
                out.writeBoolean(request.halfFrameMetadata != null)
                request.halfFrameMetadata?.let {
                    out.writeUTF(it.profile)
                    out.writeBoolean(it.dateStamp)
                    out.writeLong(it.captureTimeMillis)
                    out.writeBoolean(it.frame1BaseName != null)
                    it.frame1BaseName?.let { b -> out.writeUTF(b) }
                    out.writeBoolean(it.frame1TempPath != null)
                    it.frame1TempPath?.let { p -> out.writeUTF(p) }
                    out.writeLong(it.frame1CaptureTime)
                    out.writeFloat(it.digitalGain)
                    out.writeFloat(it.frame1DigitalGain)
                    out.writeInt(it.flareType)
                }

                out.writeInt(request.buffers.size)
                for (buf in request.buffers) {
                    val remaining = buf.remaining()
                    out.writeInt(remaining)
                    val pos = buf.position()

                    val array = ByteArray(remaining)
                    buf.get(array)
                    out.write(array)

                    buf.position(pos) // Restore position!
                }
            }
            return@withContext orphanFile
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    suspend fun recoverOrphans(context: Context): List<ProcessingRequest> = withContext(Dispatchers.IO) {
        val requests = mutableListOf<ProcessingRequest>()
        val dir = File(context.cacheDir, ORPHAN_DIR)
        if (!dir.exists()) return@withContext emptyList()

        dir.listFiles()?.forEach { file ->
            if (file.isFile && file.name.startsWith("orphan_")) {
                try {
                    DataInputStream(FileInputStream(file)).use { input ->
                        val width = input.readInt()
                        val height = input.readInt()
                        val combinedOrientation = input.readInt()
                        val whiteLevel = input.readInt()

                        val blpSize = input.readInt()
                        val blackLevelPattern = IntArray(blpSize) { input.readInt() }

                        val hasLsm = input.readBoolean()
                        val lensShadingMapData = if (hasLsm) {
                            val size = input.readInt()
                            FloatArray(size) { input.readFloat() }
                        } else null

                        val lensShadingRows = input.readInt()
                        val lensShadingCols = input.readInt()
                        val useSensorColorMatrix = input.readBoolean()

                        val wbSize = input.readInt()
                        val wb = FloatArray(wbSize) { input.readFloat() }

                        val ccmSize = input.readInt()
                        val ccm = FloatArray(ccmSize) { input.readFloat() }

                        val hasCcmAlt = input.readBoolean()
                        val ccmAlt = if (hasCcmAlt) {
                            val size = input.readInt()
                            FloatArray(size) { input.readFloat() }
                        } else null

                        val exportMatrixAB = input.readBoolean()
                        val cfa = input.readInt()
                        val targetLogIndex = input.readInt()

                        val nativeLutPath = if (input.readBoolean()) input.readUTF() else null
                        val tempJpgFile = if (input.readBoolean()) input.readUTF() else null
                        val linearDngPath = if (input.readBoolean()) input.readUTF() else null

                        val digitalGain = input.readFloat()

                        val hasStats = input.readBoolean()
                        val debugStats = if (hasStats) {
                            val size = input.readInt()
                            LongArray(size) { input.readLong() }
                        } else null

                        val currentZoom = input.readFloat()
                        val mirror = input.readBoolean()
                        val fastDenoise = input.readBoolean()
                        val targetUri = if (input.readBoolean()) input.readUTF() else null

                        val isoVal = input.readInt()
                        val expTimeVal = input.readLong()
                        val apVal = input.readFloat()
                        val flVal = input.readFloat()
                        val cTimeVal = input.readLong()
                        val offsetTime = if (input.readBoolean()) input.readUTF() else null
                        val fl35Val = input.readInt()

                        val captureMetadata = top.maary.darkbag.models.CaptureMetadata(
                            iso = if (isoVal == -1) null else isoVal,
                            exposureTime = if (expTimeVal == -1L) null else expTimeVal,
                            fNumber = if (apVal == -1.0f) null else apVal,
                            focalLength = if (flVal == -1.0f) null else flVal,
                            dateTimeOriginal = if (cTimeVal == -1L) null else cTimeVal,
                            offsetTime = offsetTime,
                            focalLengthIn35mmFilm = if (fl35Val == -1) null else fl35Val
                        )

                        val hasHf = input.readBoolean()
                        val halfFrameMetadata = if (hasHf) {
                            top.maary.darkbag.utils.HalfFrameManager.Metadata(
                                profile = input.readUTF(),
                                dateStamp = input.readBoolean(),
                                captureTimeMillis = input.readLong(),
                                frame1BaseName = if (input.readBoolean()) input.readUTF() else null,
                                frame1TempPath = if (input.readBoolean()) input.readUTF() else null,
                                frame1CaptureTime = input.readLong(),
                                digitalGain = input.readFloat(),
                                frame1DigitalGain = input.readFloat(),
                                flareType = input.readInt()
                            )
                        } else null

                        val numBuffers = input.readInt()
                        val buffers = Array(numBuffers) {
                            val remaining = input.readInt()
                            val array = ByteArray(remaining)
                            input.readFully(array)
                            val bb = ByteBuffer.allocateDirect(remaining)
                            bb.put(array)
                            bb.rewind()
                            bb
                        }

                        requests.add(ProcessingRequest(
                            buffers = buffers,
                            width = width,
                            height = height,
                            combinedOrientation = combinedOrientation,
                            whiteLevel = whiteLevel,
                            blackLevelPattern = blackLevelPattern,
                            lensShadingMapData = lensShadingMapData,
                            lensShadingRows = lensShadingRows,
                            lensShadingCols = lensShadingCols,
                            useSensorColorMatrix = useSensorColorMatrix,
                            wb = wb,
                            ccm = ccm,
                            ccmAlt = ccmAlt,
                            exportMatrixAB = exportMatrixAB,
                            cfa = cfa,
                            targetLogIndex = targetLogIndex,
                            nativeLutPath = nativeLutPath,
                            tempJpgFile = tempJpgFile,
                            linearDngPath = linearDngPath,
                            digitalGain = digitalGain,
                            debugStats = debugStats,
                            currentZoom = currentZoom,
                            mirror = mirror,
                            captureMetadata = captureMetadata,
                            fastDenoise = fastDenoise,
                            targetUri = targetUri,
                            halfFrameMetadata = halfFrameMetadata,
                            orphanFile = file
                        ))
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        return@withContext requests
    }
}
