package top.maary.darkbag.camera.delegate

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.View
import androidx.navigation.Navigation
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.DownsampleStrategy
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.signature.ObjectKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.maary.darkbag.R
import top.maary.darkbag.fragments.CameraFragmentDirections
import top.maary.darkbag.rawvideo.RawVideoNative
import top.maary.darkbag.repository.PreferenceKeys
import top.maary.darkbag.repository.SettingsRepository
import top.maary.darkbag.utils.GalleryThumbnailLoader
import top.maary.darkbag.utils.LutManager
import top.maary.darkbag.utils.MediaStoreUtils
import java.io.File
import java.nio.ByteBuffer

/**
 * Owns the gallery thumbnail: the "latest capture" URI/timestamp ordering rule, asynchronous
 * Glide / RAW-video frame decoding, and photo-view tap / long-press navigation.
 */
class CameraThumbnailController(
    private val env: CameraEnvironmentHost,
    private val ui: CameraUiHost,
    private val coordinator: CameraCoordinatorHost
) {
    private val mediaStoreUtils by lazy { MediaStoreUtils(env.hostContext) }

    var currentThumbnailUri: Uri? = null
        private set
    private var currentThumbnailTimestamp: Long = 0L
    private var thumbnailLoadJob: Job? = null
    private var lastBoundUri: Uri? = null
    private var lastBoundButton: View? = null
    private var loadGeneration: Long = 0L
    private var captureSequence: Long = 0L

    /** Updates the cached thumbnail URI; stale (older) timestamps never override a newer one. */
    fun updateCurrentThumbnail(uri: Uri?, timestamp: Long = System.currentTimeMillis()): Boolean {
        if (uri == null) {
            currentThumbnailUri = null
            lastBoundUri = null
            lastBoundButton = null
            return true
        }
        if (timestamp >= currentThumbnailTimestamp) {
            currentThumbnailUri = uri
            currentThumbnailTimestamp = timestamp
            captureSequence++
            return true
        }
        return false
    }

    /** Syncs thumbnail visibility without triggering a redundant image decode. */
    fun refreshThumbnailVisibility() {
        val photoViewButton = ui.cameraUiContainerBinding?.photoViewButton ?: return
        val shouldHideForMode = coordinator.activeCoordinator?.shouldHideGalleryThumbnail() == true
        photoViewButton.visibility = if (currentThumbnailUri == null) {
            if (shouldHideForMode || env.runtimeState.isProcessing) View.INVISIBLE else View.GONE
        } else {
            if (shouldHideForMode) View.INVISIBLE else View.VISIBLE
        }
        if (photoViewButton.visibility == View.VISIBLE) {
            photoViewButton.alpha = 1f
        }
    }

    /** Rebinds the currently tracked thumbnail URI on a newly inflated view without bumping its timestamp. */
    fun rebindCurrentThumbnail() {
        val uri = currentThumbnailUri ?: return
        setGalleryThumbnail(uri.toString(), timestamp = currentThumbnailTimestamp, forceReload = true)
    }

    /** Loads the latest app image off the UI thread, shows it, and warms the viewer cache. */
    fun loadLatestThumbnail() {
        val seqBefore = captureSequence
        val trackedUri = currentThumbnailUri
        env.lifecycleScope.launch {
            val trackedExists = trackedUri != null && mediaStoreUtils.uriExists(trackedUri)
            val thumbnailUri = mediaStoreUtils.getLatestAppImage()
            if (captureSequence != seqBefore) {
                return@launch
            }
            val now = System.currentTimeMillis()
            val lm = thumbnailUri?.let {
                minOf(mediaStoreUtils.getFileLastModified(env.hostContext, it), now)
            } ?: 0L
            if (trackedUri != null && !trackedExists) {
                currentThumbnailTimestamp = 0L
                setGalleryThumbnail(thumbnailUri?.toString(), timestamp = lm, forceReload = true)
            } else if (currentThumbnailUri == null || currentThumbnailUri == thumbnailUri) {
                setGalleryThumbnail(thumbnailUri?.toString(), timestamp = lm)
            }
            kotlin.runCatching { env.imageRepository.getGroupedImages() }
        }
    }

    fun setGalleryThumbnail(
        filename: String?,
        timestamp: Long = System.currentTimeMillis(),
        forceReload: Boolean = false
    ): Boolean {
        val shouldHideForMode = coordinator.activeCoordinator?.shouldHideGalleryThumbnail() == true
        val photoViewButton = ui.cameraUiContainerBinding?.photoViewButton

        if (filename == null) {
            thumbnailLoadJob?.cancel()
            thumbnailLoadJob = null
            loadGeneration++
            updateCurrentThumbnail(null)
            if (photoViewButton != null) {
                val hideNull = shouldHideForMode || env.runtimeState.isProcessing
                photoViewButton.post {
                    if (currentThumbnailUri == null) {
                        photoViewButton.setImageDrawable(null)
                        photoViewButton.visibility = if (hideNull) View.INVISIBLE else View.GONE
                    }
                }
            }
            return true
        }

        val parsedUri = try {
            Uri.parse(filename)
        } catch (_: Exception) {
            return false
        }

        if (!updateCurrentThumbnail(parsedUri, timestamp)) {
            return false
        }

        if (photoViewButton == null) {
            return true
        }

        photoViewButton.post {
            if (currentThumbnailUri != parsedUri) {
                return@post
            }
            val hideForModeNow = coordinator.activeCoordinator?.shouldHideGalleryThumbnail() == true
            photoViewButton.visibility = if (hideForModeNow) View.INVISIBLE else View.VISIBLE
            if (!hideForModeNow) photoViewButton.alpha = 1f
            photoViewButton.setPadding(0, 0, 0, 0)

            if (!forceReload &&
                parsedUri == lastBoundUri &&
                photoViewButton === lastBoundButton &&
                photoViewButton.drawable != null
            ) {
                return@post
            }

            thumbnailLoadJob?.cancel()
            val token = ++loadGeneration

            thumbnailLoadJob = env.lifecycleScope.launch(Dispatchers.Main) {
                fun canBind(): Boolean {
                    return coroutineContext.isActive &&
                        token == loadGeneration &&
                        currentThumbnailUri == parsedUri &&
                        env.isHostAdded &&
                        ui.cameraUiContainerBinding?.photoViewButton === photoViewButton &&
                        photoViewButton.isAttachedToWindow
                }

                val isRawVid = filename.endsWith(".rawvid", ignoreCase = true) ||
                    parsedUri.toString().endsWith(".rawvid", ignoreCase = true)
                if (isRawVid) {
                    val bmp = withContext(Dispatchers.IO) { decodeRawVideoThumbnail(parsedUri) }
                    if (bmp != null) {
                        if (canBind()) {
                            lastBoundUri = parsedUri
                            lastBoundButton = photoViewButton
                            Glide.with(photoViewButton).load(bmp).apply(RequestOptions.centerCropTransform()).into(photoViewButton)
                        }
                        return@launch
                    }
                }
                val isDng = filename.endsWith(".dng", ignoreCase = true) ||
                    parsedUri.toString().endsWith(".dng", ignoreCase = true) ||
                    withContext(Dispatchers.IO) {
                        try { env.hostContext.contentResolver.getType(parsedUri)?.contains("dng", ignoreCase = true) == true } catch (_: Exception) { false }
                    }
                if (isDng) {
                    val bmp = withContext(Dispatchers.IO) { GalleryThumbnailLoader.loadDngThumbnail(env.hostContext, parsedUri) }
                    if (bmp != null) {
                        if (canBind()) {
                            lastBoundUri = parsedUri
                            lastBoundButton = photoViewButton
                            Glide.with(photoViewButton).load(bmp).apply(RequestOptions.centerCropTransform()).into(photoViewButton)
                        }
                        return@launch
                    }
                }
                val (loadTarget, lastModified) = withContext(Dispatchers.IO) {
                    val lm = try {
                        mediaStoreUtils.getFileLastModified(env.hostContext, parsedUri)
                    } catch (e: Exception) {
                        File(filename).let { if (it.exists()) it.lastModified() else 0L }
                    }
                    val file = File(filename)
                    val target: Any = if (file.exists()) file else filename
                    Pair(target, lm)
                }
                if (canBind()) {
                    lastBoundUri = parsedUri
                    lastBoundButton = photoViewButton
                    val thumbSizePx = env.hostContext.resources.getDimensionPixelSize(R.dimen.round_button_medium)
                    val signatureKey = if (lastModified > 0L) {
                        ObjectKey(lastModified)
                    } else {
                        ObjectKey("$filename@$timestamp")
                    }
                    Glide.with(photoViewButton)
                        .load(loadTarget)
                        .override(thumbSizePx, thumbSizePx)
                        .downsample(DownsampleStrategy.CENTER_INSIDE)
                        .apply(RequestOptions.centerCropTransform())
                        .signature(signatureKey)
                        .into(photoViewButton)
                }
            }
        }
        return true
    }

    /**
     * Binds a pre-decoded thumbnail [bitmap] (e.g. from video recording) through the unified
     * timestamp-ordered thumbnail channel, cancelling any older pending photo decode job.
     */
    fun setGalleryThumbnailBitmap(
        uri: Uri,
        bitmap: Bitmap?,
        timestamp: Long = System.currentTimeMillis()
    ): Boolean {
        if (!updateCurrentThumbnail(uri, timestamp)) {
            return false
        }
        if (bitmap == null) {
            return setGalleryThumbnail(uri.toString(), timestamp = timestamp, forceReload = true)
        }
        thumbnailLoadJob?.cancel()
        thumbnailLoadJob = null
        loadGeneration++

        val photoViewButton = ui.cameraUiContainerBinding?.photoViewButton ?: return true
        photoViewButton.post {
            if (currentThumbnailUri != uri || !env.isHostAdded ||
                ui.cameraUiContainerBinding?.photoViewButton !== photoViewButton
            ) {
                return@post
            }
            val hideForModeNow = coordinator.activeCoordinator?.shouldHideGalleryThumbnail() == true
            photoViewButton.visibility = if (hideForModeNow) View.INVISIBLE else View.VISIBLE
            if (!hideForModeNow) photoViewButton.alpha = 1f
            photoViewButton.setPadding(0, 0, 0, 0)
            lastBoundUri = uri
            lastBoundButton = photoViewButton
            Glide.with(photoViewButton)
                .load(bitmap)
                .apply(RequestOptions.centerCropTransform())
                .into(photoViewButton)
        }
        return true
    }

    /** Decodes frame 0 of a .rawvid file into a small preview bitmap (IO thread only). */
    private fun decodeRawVideoThumbnail(uri: Uri): Bitmap? = try {
        env.hostContext.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            val handle = RawVideoNative.nativeOpenReaderFd(pfd.fd)
            if (handle == 0L) return@use null
            try {
                val header = RawVideoNative.readHeader(handle)
                if (header == null || header.width <= 0 || header.height <= 0) return@use null
                val swapDims = header.orientation == 90 || header.orientation == 270
                val thumbW = if (swapDims) (320 * header.height) / header.width else 320
                val thumbH = if (swapDims) 320 else (320 * header.height) / header.width
                val thumbBmp = Bitmap.createBitmap(thumbW, thumbH, Bitmap.Config.ARGB_8888)
                val buf = ByteBuffer.allocateDirect(header.width * header.height * 2)
                val read = RawVideoNative.nativeReadFrame(handle, 0, LongArray(3), buf)
                if (read <= 0) return@use null
                val targetLogIndex = if (header.activeLogName.isNotBlank() && header.activeLogName != "None") {
                    PreferenceKeys.LOG_CURVES.indexOf(header.activeLogName).takeIf { it >= 0 } ?: -1
                } else -1
                val lutDir = LutManager(env.hostContext).lutDir
                val lutPath = if (header.activeLutName.isNotBlank() && header.activeLutName != "None") {
                    File(lutDir, header.activeLutName).takeIf { it.exists() }?.absolutePath
                } else null
                RawVideoNative.nativeDebayerFrameToBitmap(
                    bayerBuffer = buf,
                    width = header.width,
                    height = header.height,
                    orientation = header.orientation,
                    cfaPattern = header.cfaPattern,
                    whiteLevel = header.whiteLevel,
                    blackLevel = header.blackLevel.firstOrNull() ?: 64f,
                    neutralPoint = header.neutralPoint,
                    targetLog = targetLogIndex,
                    lutPath = lutPath,
                    exposure = header.exposure,
                    contrast = header.contrast,
                    saturation = header.saturation,
                    outBitmap = thumbBmp
                )
                thumbBmp
            } finally {
                RawVideoNative.nativeCloseReader(handle)
            }
        }
    } catch (e: Exception) {
        null
    }

    /** Wires the photo-view button: tap opens the viewer, long-press opens the darkroom gallery. */
    fun bindPhotoViewButton() {
        val button = ui.cameraUiContainerBinding?.photoViewButton ?: return
        button.setOnLongClickListener {
            val activity = env.hostActivity ?: return@setOnLongClickListener false
            val nav = Navigation.findNavController(activity, R.id.fragment_container)
            if (nav.currentDestination?.id == R.id.camera_fragment) {
                nav.navigate(CameraFragmentDirections.actionCameraToDarkroomGallery(initialUri = currentThumbnailUri?.toString()))
            }
            true
        }
        button.setOnClickListener {
            env.lifecycleScope.launch {
                val uri = currentThumbnailUri ?: mediaStoreUtils.getLatestAppImage() ?: return@launch
                val context = env.hostContext
                val currentPrefs = SettingsRepository.getInstance(context).current()
                val useInternalViewer = currentPrefs.useInternalViewer
                var externalViewerStarted = false
                if (!useInternalViewer) {
                    try {
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "image/*")
                            addCategory(Intent.CATEGORY_DEFAULT)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            currentPrefs.externalViewerPackage
                                ?.takeIf { it.isNotEmpty() }?.let { setPackage(it) }
                        }
                        context.startActivity(intent)
                        externalViewerStarted = true
                    } catch (e: Exception) {
                        top.maary.darkbag.ui.DarkbagFeedback.showSnackbar(
                            ui.fragmentCameraBinding?.root,
                            R.string.error_no_external_viewer
                        )
                    }
                }
                if (useInternalViewer || !externalViewerStarted) {
                    val activity = env.hostActivity ?: return@launch
                    Navigation.findNavController(activity, R.id.fragment_container)
                        .navigate(CameraFragmentDirections.actionCameraToImageViewer(uri.toString()))
                }
            }
        }
    }
}
