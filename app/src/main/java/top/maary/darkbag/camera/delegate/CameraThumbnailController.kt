package top.maary.darkbag.camera.delegate

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.View
import android.widget.Toast
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

    /** Updates the cached thumbnail URI; stale (older) timestamps never override a newer one. */
    fun updateCurrentThumbnail(uri: Uri?, timestamp: Long = System.currentTimeMillis()) {
        if (uri == null) {
            currentThumbnailUri = null
            currentThumbnailTimestamp = timestamp
            return
        }
        if (timestamp >= currentThumbnailTimestamp) {
            currentThumbnailUri = uri
            currentThumbnailTimestamp = timestamp
        }
    }

    /** Loads the latest app image off the UI thread, shows it, and warms the viewer cache. */
    fun loadLatestThumbnail() {
        env.lifecycleScope.launch {
            val thumbnailUri = mediaStoreUtils.getLatestAppImage()
            updateCurrentThumbnail(thumbnailUri)
            setGalleryThumbnail(thumbnailUri?.toString())
            kotlin.runCatching { env.imageRepository.getGroupedImages() }
        }
    }

    fun setGalleryThumbnail(filename: String?) {
        thumbnailLoadJob?.cancel()
        thumbnailLoadJob = null

        val photoViewButton = ui.cameraUiContainerBinding?.photoViewButton ?: return
        photoViewButton.post {
            thumbnailLoadJob?.cancel()
            val shouldHide = (coordinator.activeCoordinator?.shouldHideGalleryThumbnail() == true) ||
                env.runtimeState.isProcessing
            if (filename == null) {
                updateCurrentThumbnail(null)
                photoViewButton.setImageDrawable(null)
                photoViewButton.visibility = if (shouldHide) View.INVISIBLE else View.GONE
                return@post
            }
            try {
                updateCurrentThumbnail(Uri.parse(filename))
            } catch (_: Exception) {}

            photoViewButton.visibility = if (shouldHide) View.INVISIBLE else View.VISIBLE
            if (!shouldHide) photoViewButton.alpha = 1f
            photoViewButton.setPadding(0, 0, 0, 0)

            thumbnailLoadJob = env.lifecycleScope.launch(Dispatchers.Main) {
                fun canBind(): Boolean {
                    return coroutineContext.isActive &&
                        env.isHostAdded &&
                        ui.cameraUiContainerBinding?.photoViewButton === photoViewButton &&
                        photoViewButton.isAttachedToWindow
                }

                val uri = try { Uri.parse(filename) } catch (_: Exception) { null }
                val isRawVid = filename.endsWith(".rawvid", ignoreCase = true) ||
                    uri?.toString()?.endsWith(".rawvid", ignoreCase = true) == true
                if (isRawVid && uri != null) {
                    val bmp = withContext(Dispatchers.IO) { decodeRawVideoThumbnail(uri) }
                    if (bmp != null) {
                        if (canBind()) {
                            Glide.with(photoViewButton).load(bmp).apply(RequestOptions.centerCropTransform()).into(photoViewButton)
                        }
                        return@launch
                    }
                }
                val isDng = filename.endsWith(".dng", ignoreCase = true) ||
                    uri?.toString()?.endsWith(".dng", ignoreCase = true) == true ||
                    (uri != null && withContext(Dispatchers.IO) {
                        try { env.hostContext.contentResolver.getType(uri)?.contains("dng", ignoreCase = true) == true } catch (_: Exception) { false }
                    })
                if (isDng && uri != null) {
                    val bmp = withContext(Dispatchers.IO) { GalleryThumbnailLoader.loadDngThumbnail(env.hostContext, uri) }
                    if (bmp != null) {
                        if (canBind()) {
                            Glide.with(photoViewButton).load(bmp).apply(RequestOptions.centerCropTransform()).into(photoViewButton)
                        }
                        return@launch
                    }
                }
                val (loadTarget, lastModified) = withContext(Dispatchers.IO) {
                    val lm = try {
                        mediaStoreUtils.getFileLastModified(env.hostContext, Uri.parse(filename))
                    } catch (e: Exception) {
                        File(filename).let { if (it.exists()) it.lastModified() else 0L }
                    }
                    val file = File(filename)
                    val target: Any = if (file.exists()) file else filename
                    Pair(target, lm)
                }
                if (canBind()) {
                    val thumbSizePx = env.hostContext.resources.getDimensionPixelSize(R.dimen.round_button_medium)
                    Glide.with(photoViewButton)
                        .load(loadTarget)
                        .override(thumbSizePx, thumbSizePx)
                        .downsample(DownsampleStrategy.CENTER_INSIDE)
                        .apply(RequestOptions.centerCropTransform())
                        .signature(ObjectKey(lastModified))
                        .into(photoViewButton)
                }
            }
        }
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
                        Toast.makeText(context, R.string.error_no_external_viewer, Toast.LENGTH_SHORT).show()
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
