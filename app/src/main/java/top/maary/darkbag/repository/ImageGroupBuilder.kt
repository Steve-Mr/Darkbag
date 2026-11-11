package top.maary.darkbag.repository

import android.content.Context
import android.net.Uri
import top.maary.darkbag.models.EditConfig
import top.maary.darkbag.models.ImageGroup
import top.maary.darkbag.models.MultiCameraLensItem
import top.maary.darkbag.utils.DarkbagIdentity
import top.maary.darkbag.utils.HalfFrameSessionStore
import top.maary.darkbag.utils.ImageUtils
import java.net.URLDecoder
import java.util.Locale
import kotlin.math.abs

internal class MultiLensItemBuilder(
    val lensTag: String,
    val multiplier: Float,
    var jpgUri: Uri? = null,
    var dngUri: Uri? = null
) {
    fun build(): MultiCameraLensItem = MultiCameraLensItem(
        lensTag = lensTag,
        multiplier = multiplier,
        jpgUri = jpgUri,
        dngUri = dngUri
    )
}

internal class ImageGroupBuilder(
    val baseName: String,
    private val context: Context? = null
) {
    var jpgUri: Uri? = null
    var jpgTime: Long = 0L
    var dngUri: Uri? = null
    var dngTime: Long = 0L
    var dngUri1: Uri? = null
    var dngUri1Time: Long = 0L
    var dngUri2: Uri? = null
    var dngUri2Time: Long = 0L
    var rawVideoUri: Uri? = null
    var rawVideoTime: Long = 0L
    var isRawVideo: Boolean = false
    var rawVideoFps: Float = 24.0f
    var rawVideoFrameCount: Int = 0
    var rawVideoDurationMs: Long = 0L
    var mp4VideoUri: Uri? = null
    var mp4VideoTime: Long = 0L
    var isMp4Video: Boolean = false
    val derivativeJpgUris = mutableListOf<Uri>()
    val derivativeMp4Uris = mutableListOf<Uri>()
    var isCinemaDng: Boolean = false
    var cinemaDngFolderUri: Uri? = null
    var cinemaDngFirstFrameUri: Uri? = null
    var cinemaDngAudioUri: Uri? = null
    var cinemaDngFrameCount: Int = 0
    val cinemaDngFrameUris = mutableListOf<Uri>()
    val multiLensBuilders = mutableMapOf<String, MultiLensItemBuilder>()
    var isMultiCamera: Boolean = false
    var hfLayout: String? = null
    var width: Int = 0
    var height: Int = 0
    var orientation: Int = 0
    var captureTime: Long = 0L
    var lastModified: Long = 0L
    var editConfig: EditConfig? = null
    var isMotionPhoto: Boolean = false
    var motionPhotoPtsUs: Long = 0L
    var motionPhotoVideoLength: Long = 0L
    var metadataLoaded: Boolean = false
    var isInProgress: Boolean = false
    var totalSizeBytes: Long = 0L

    fun addSize(sizeBytes: Long) {
        if (sizeBytes > 0L) {
            totalSizeBytes += sizeBytes
        }
    }

    fun applyFrom(group: ImageGroup): ImageGroupBuilder {
        jpgUri = group.jpgUri
        dngUri = group.dngUri
        dngUri1 = group.dngUri1
        dngUri2 = group.dngUri2
        rawVideoUri = group.rawVideoUri
        isRawVideo = group.isRawVideo
        rawVideoFps = group.rawVideoFps
        rawVideoFrameCount = group.rawVideoFrameCount
        rawVideoDurationMs = group.rawVideoDurationMs
        mp4VideoUri = group.mp4VideoUri
        isMp4Video = group.isMp4Video
        derivativeJpgUris.clear()
        derivativeJpgUris.addAll(group.derivativeJpgUris)
        derivativeMp4Uris.clear()
        derivativeMp4Uris.addAll(group.derivativeMp4Uris)
        isCinemaDng = group.isCinemaDng
        cinemaDngFolderUri = group.cinemaDngFolderUri
        cinemaDngFirstFrameUri = group.cinemaDngFirstFrameUri
        cinemaDngAudioUri = group.cinemaDngAudioUri
        cinemaDngFrameCount = group.cinemaDngFrameCount
        cinemaDngFrameUris.clear()
        cinemaDngFrameUris.addAll(group.cinemaDngFrameUris)
        isMultiCamera = group.isMultiCamera
        multiLensBuilders.clear()
        for (lens in group.multiCameraLenses) {
            multiLensBuilders[lens.lensTag] = MultiLensItemBuilder(
                lensTag = lens.lensTag,
                multiplier = lens.multiplier,
                jpgUri = lens.jpgUri,
                dngUri = lens.dngUri
            )
        }
        hfLayout = group.hfLayout
        width = group.width
        height = group.height
        orientation = group.orientation
        captureTime = group.captureTime
        lastModified = group.lastModified
        editConfig = group.editConfig
        isMotionPhoto = group.isMotionPhoto
        motionPhotoPtsUs = group.motionPhotoPtsUs
        motionPhotoVideoLength = group.motionPhotoVideoLength
        metadataLoaded = group.metadataLoaded
        isInProgress = group.isInProgress
        totalSizeBytes = group.totalSizeBytes
        return this
    }

    private fun isSameBaseFile(existingUri: Uri?, newUri: Uri, expectedExt: String): Boolean {
        if (existingUri == null) return false
        if (existingUri == newUri || existingUri.toString() == newUri.toString()) return true
        val existingName = existingUri.lastPathSegment?.let {
            try { URLDecoder.decode(it, "UTF-8") } catch (e: Exception) { it }
        }?.substringAfterLast("/")?.substringAfterLast(":") ?: ""
        val newName = newUri.lastPathSegment?.let {
            try { URLDecoder.decode(it, "UTF-8") } catch (e: Exception) { it }
        }?.substringAfterLast("/")?.substringAfterLast(":") ?: ""
        if (existingName.isNotEmpty() && newName.isNotEmpty() && existingName.equals(newName, ignoreCase = true)) {
            return true
        }
        val defaultName1 = "${DarkbagIdentity.FILE_PREFIX}$baseName.$expectedExt"
        val defaultName2 = "$baseName.$expectedExt"
        val defaultGraded1 = "${DarkbagIdentity.FILE_PREFIX}${baseName}_graded.$expectedExt"
        val defaultGraded2 = "${baseName}_graded.$expectedExt"
        val isExistingDefault = existingName.isEmpty() || existingName.equals(defaultName1, true) || existingName.equals(defaultName2, true) || existingName.equals(defaultGraded1, true) || existingName.equals(defaultGraded2, true)
        val isNewDefault = newName.isEmpty() || newName.equals(defaultName1, true) || newName.equals(defaultName2, true) || newName.equals(defaultGraded1, true) || newName.equals(defaultGraded2, true)
        return isExistingDefault && isNewDefault
    }

    fun mergeFrom(other: ImageGroupBuilder) {
        if (other.jpgUri != null) setJpg(other.jpgUri!!, other.jpgTime)
        for (uri in other.derivativeJpgUris) {
            if (!isSameBaseFile(jpgUri, uri, "jpg") && uri !in derivativeJpgUris) {
                derivativeJpgUris.add(uri)
            }
        }
        if (other.dngUri != null) setDng(other.dngUri!!, other.dngTime)
        if (other.dngUri1 != null) setDng1(other.dngUri1!!, other.dngUri1Time)
        if (other.dngUri2 != null) setDng2(other.dngUri2!!, other.dngUri2Time)
        if (other.rawVideoUri != null) setRawVideo(other.rawVideoUri!!, other.rawVideoTime)
        if (other.mp4VideoUri != null) setMp4Video(other.mp4VideoUri!!, other.mp4VideoTime)
        for (uri in other.derivativeMp4Uris) {
            if (!isSameBaseFile(mp4VideoUri, uri, "mp4") && uri !in derivativeMp4Uris) {
                derivativeMp4Uris.add(uri)
            }
        }
        if (other.isCinemaDng) {
            isCinemaDng = true
            if (other.cinemaDngFolderUri != null) cinemaDngFolderUri = other.cinemaDngFolderUri
            if (other.cinemaDngFirstFrameUri != null) cinemaDngFirstFrameUri = other.cinemaDngFirstFrameUri
            if (other.cinemaDngAudioUri != null) cinemaDngAudioUri = other.cinemaDngAudioUri
            if (other.cinemaDngFrameCount > cinemaDngFrameCount) cinemaDngFrameCount = other.cinemaDngFrameCount
            for (uri in other.cinemaDngFrameUris) {
                if (uri !in cinemaDngFrameUris) cinemaDngFrameUris.add(uri)
            }
        }
        for ((tag, lens) in other.multiLensBuilders) {
            val item = multiLensBuilders.getOrPut(tag) { MultiLensItemBuilder(tag, lens.multiplier) }
            if (lens.jpgUri != null) item.jpgUri = lens.jpgUri
            if (lens.dngUri != null) item.dngUri = lens.dngUri
        }
        if (other.isMultiCamera) isMultiCamera = true
        if (other.hfLayout != null) hfLayout = other.hfLayout
        if (other.width > 0) width = other.width
        if (other.height > 0) height = other.height
        if (other.orientation != 0) orientation = other.orientation
        if (other.editConfig != null) editConfig = other.editConfig
        if (other.isMotionPhoto) {
            isMotionPhoto = true
            motionPhotoPtsUs = other.motionPhotoPtsUs
            motionPhotoVideoLength = other.motionPhotoVideoLength
        }
        if (other.rawVideoFps > 0) {
            rawVideoFps = other.rawVideoFps
            rawVideoFrameCount = other.rawVideoFrameCount
            rawVideoDurationMs = other.rawVideoDurationMs
        }
        if (other.metadataLoaded) metadataLoaded = true
        if (other.isInProgress) isInProgress = true
        if (other.totalSizeBytes > 0L) {
            totalSizeBytes = maxOf(totalSizeBytes, other.totalSizeBytes)
        }
        updateTime(other.captureTime, other.lastModified)
    }

    fun setCinemaDngFolder(folderUri: Uri, firstFrameUri: Uri?, audioUri: Uri?, frameCount: Int, time: Long, modifiedTime: Long = time) {
        isCinemaDng = true
        cinemaDngFolderUri = folderUri
        if (firstFrameUri != null) cinemaDngFirstFrameUri = firstFrameUri
        if (audioUri != null) cinemaDngAudioUri = audioUri
        if (frameCount > 0) cinemaDngFrameCount = frameCount
        updateTime(time, modifiedTime)
    }

    fun addCinemaDngFrame(uri: Uri, frameIndex: Int, time: Long, modifiedTime: Long = time) {
        isCinemaDng = true
        if (frameIndex == 0 || cinemaDngFirstFrameUri == null) {
            cinemaDngFirstFrameUri = uri
        }
        if (uri !in cinemaDngFrameUris) {
            cinemaDngFrameUris.add(uri)
        }
        cinemaDngFrameCount = maxOf(cinemaDngFrameCount, cinemaDngFrameUris.size)
        updateTime(time, modifiedTime)
    }

    fun setCinemaDngAudio(uri: Uri, time: Long, modifiedTime: Long = time) {
        isCinemaDng = true
        cinemaDngAudioUri = uri
        updateTime(time, modifiedTime)
    }

    fun addMultiJpg(uri: Uri, fileName: String, time: Long, modifiedTime: Long = time) {
        isMultiCamera = true
        val tag = ImageUtils.extractMultiCameraLensTag(fileName)
        val mult = ImageUtils.extractMultiCameraMultiplier(fileName)
        val effectiveTag = if (tag.isNotEmpty()) tag else String.format(Locale.US, "%.1fx", mult)
        val item = multiLensBuilders.getOrPut(effectiveTag) { MultiLensItemBuilder(effectiveTag, mult) }
        item.jpgUri = uri
        updateTime(time, modifiedTime)
    }

    fun addMultiDng(uri: Uri, fileName: String, time: Long, modifiedTime: Long = time) {
        isMultiCamera = true
        val tag = ImageUtils.extractMultiCameraLensTag(fileName)
        val mult = ImageUtils.extractMultiCameraMultiplier(fileName)
        val effectiveTag = if (tag.isNotEmpty()) tag else String.format(Locale.US, "%.1fx", mult)
        val item = multiLensBuilders.getOrPut(effectiveTag) { MultiLensItemBuilder(effectiveTag, mult) }
        item.dngUri = uri
        updateTime(time, modifiedTime)
    }

    fun setRawVideo(uri: Uri, time: Long, modifiedTime: Long = time) {
        if (rawVideoUri == null) {
            rawVideoUri = uri
            rawVideoTime = time
            isRawVideo = true
        } else if (isSameBaseFile(rawVideoUri, uri, "rawvid")) {
            if (uri.authority?.contains("documents") == true || uri.scheme == "file") {
                rawVideoUri = uri
            }
            rawVideoTime = maxOf(rawVideoTime, time)
            isRawVideo = true
        } else if (time > rawVideoTime + 2000) {
            rawVideoUri = uri
            rawVideoTime = time
            isRawVideo = true
        }
        updateTime(time, modifiedTime)
    }

    fun setMp4Video(uri: Uri, time: Long, modifiedTime: Long = time) {
        if (mp4VideoUri == null) {
            mp4VideoUri = uri
            mp4VideoTime = time
            isMp4Video = true
        } else if (isSameBaseFile(mp4VideoUri, uri, "mp4")) {
            if (uri.authority?.contains("documents") == true || uri.scheme == "file") {
                mp4VideoUri = uri
            }
            mp4VideoTime = maxOf(mp4VideoTime, time)
            isMp4Video = true
        } else if (mp4VideoUri != uri) {
            if (uri !in derivativeMp4Uris) {
                derivativeMp4Uris.add(uri)
            }
            if (time > mp4VideoTime + 2000) {
                val old = mp4VideoUri
                if (old != null && old !in derivativeMp4Uris) {
                    derivativeMp4Uris.add(0, old)
                }
                mp4VideoUri = uri
                mp4VideoTime = time
            }
        }
        updateTime(time, modifiedTime)
    }

    fun setJpg(uri: Uri, time: Long, modifiedTime: Long = time) {
        if (jpgUri == null) {
            jpgUri = uri
            jpgTime = time
        } else if (isSameBaseFile(jpgUri, uri, "jpg")) {
            if (uri.authority?.contains("documents") == true || uri.scheme == "file") {
                jpgUri = uri
            }
            jpgTime = maxOf(jpgTime, time)
        } else if (jpgUri != uri) {
            if (uri !in derivativeJpgUris) {
                derivativeJpgUris.add(uri)
            }
            if (time > jpgTime + 2000) {
                val old = jpgUri
                if (old != null && old !in derivativeJpgUris) {
                    derivativeJpgUris.add(0, old)
                }
                jpgUri = uri
                jpgTime = time
            }
        }
        updateTime(time, modifiedTime)
    }

    fun setDng(uri: Uri, time: Long, modifiedTime: Long = time) {
        if (dngUri == null) {
            dngUri = uri
            dngTime = time
        } else if (isSameBaseFile(dngUri, uri, "dng")) {
            if (uri.authority?.contains("documents") == true || uri.scheme == "file") {
                dngUri = uri
            }
            dngTime = maxOf(dngTime, time)
        } else if (time > dngTime + 2000) {
            dngUri = uri
            dngTime = time
        }
        updateTime(time, modifiedTime)
    }

    fun setDng1(uri: Uri, time: Long, modifiedTime: Long = time) {
        if (dngUri1 == null) {
            dngUri1 = uri
            dngUri1Time = time
        } else if (isSameBaseFile(dngUri1, uri, "dng")) {
            if (uri.authority?.contains("documents") == true || uri.scheme == "file") {
                dngUri1 = uri
            }
            dngUri1Time = maxOf(dngUri1Time, time)
        } else if (time > dngUri1Time + 2000) {
            dngUri1 = uri
            dngUri1Time = time
        }
        updateTime(time, modifiedTime)
    }

    fun setDng2(uri: Uri, time: Long, modifiedTime: Long = time) {
        if (dngUri2 == null) {
            dngUri2 = uri
            dngUri2Time = time
        } else if (isSameBaseFile(dngUri2, uri, "dng")) {
            if (uri.authority?.contains("documents") == true || uri.scheme == "file") {
                dngUri2 = uri
            }
            dngUri2Time = maxOf(dngUri2Time, time)
        } else if (time > dngUri2Time + 2000) {
            dngUri2 = uri
            dngUri2Time = time
        }
        updateTime(time, modifiedTime)
    }

    fun updateTime(time: Long, modifiedTime: Long) {
        if (time > captureTime) captureTime = time
        if (modifiedTime > lastModified) lastModified = modifiedTime
    }

    fun build(buildContext: Context? = context): ImageGroup {
        var isInProgress = this.isInProgress
        var isPartial = false

        // Check for in-progress half-frame capture
        if (dngUri1 != null && dngUri2 == null && jpgUri == null && buildContext != null) {
            val sessionStore = HalfFrameSessionStore(buildContext)
            val sideFile = sessionStore.tempFileForProfile(HalfFrameSessionStore.PROFILE_HALF_SIDE)
            val topFile = sessionStore.tempFileForProfile(HalfFrameSessionStore.PROFILE_HALF_TOP)

            val sideMatch = sideFile.exists() && abs(sideFile.lastModified() - dngUri1Time) < 60000
            val topMatch = topFile.exists() && abs(topFile.lastModified() - dngUri1Time) < 60000

            if (sideMatch || topMatch) {
                isInProgress = true
            }
        }

        // Check for partial half-frame (JPG exists but exactly one DNG is missing)
        if (jpgUri != null && (hfLayout == "SBS" || hfLayout == "TB" || hfLayout == "Side-by-side" || hfLayout == "Top-bottom")) {
            if ((dngUri1 == null) xor (dngUri2 == null)) {
                isPartial = true
            }
        }

        // If it's a half-frame DNG group but no layout is specified, default to SBS
        var finalLayout = hfLayout
        if (finalLayout == null && dngUri1 != null && dngUri2 != null && jpgUri == null) {
            finalLayout = "SBS"
        }

        val lenses = multiLensBuilders.values
            .map { it.build() }
            .sortedWith(compareBy<MultiCameraLensItem> { it.multiplier }.thenBy { it.lensTag })

        val sortedJpgs = lenses.mapNotNull { it.jpgUri }
        val sortedDngs = lenses.mapNotNull { it.dngUri }

        val finalJpgUri = if (isMultiCamera && sortedJpgs.isNotEmpty()) {
            lenses.find { it.lensTag == "1.0x" || it.lensTag == "1x" }?.jpgUri
                ?: sortedJpgs.firstOrNull()
                ?: jpgUri
        } else jpgUri

        return ImageGroup(
            baseName = baseName,
            jpgUri = finalJpgUri,
            dngUri = dngUri,
            dngUri1 = dngUri1,
            dngUri2 = dngUri2,
            hfLayout = finalLayout,
            width = width,
            height = height,
            orientation = orientation,
            captureTime = captureTime,
            lastModified = if (lastModified > 0) lastModified else maxOf(jpgTime, dngTime, dngUri1Time, dngUri2Time, rawVideoTime, mp4VideoTime),
            editConfig = editConfig,
            isMotionPhoto = isMotionPhoto,
            motionPhotoPtsUs = motionPhotoPtsUs,
            motionPhotoVideoLength = motionPhotoVideoLength,
            metadataLoaded = metadataLoaded,
            isRawVideo = isRawVideo,
            rawVideoFps = rawVideoFps,
            rawVideoFrameCount = rawVideoFrameCount,
            rawVideoDurationMs = rawVideoDurationMs,
            rawVideoUri = rawVideoUri,
            mp4VideoUri = mp4VideoUri,
            isMp4Video = isMp4Video,
            derivativeJpgUris = derivativeJpgUris.toList(),
            derivativeMp4Uris = derivativeMp4Uris.toList(),
            isCinemaDng = isCinemaDng,
            cinemaDngFolderUri = cinemaDngFolderUri,
            cinemaDngFirstFrameUri = cinemaDngFirstFrameUri,
            cinemaDngAudioUri = cinemaDngAudioUri,
            cinemaDngFrameCount = cinemaDngFrameCount,
            cinemaDngFrameUris = cinemaDngFrameUris.toList(),
            isMultiCamera = isMultiCamera,
            multiCameraLenses = lenses,
            isInProgress = isInProgress,
            isPartial = isPartial,
            totalSizeBytes = totalSizeBytes
        )
    }
}
