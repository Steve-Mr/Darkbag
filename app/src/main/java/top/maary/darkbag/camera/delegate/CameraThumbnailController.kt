package top.maary.darkbag.camera.delegate

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.View
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.navigation.Navigation
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.DownsampleStrategy
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.signature.ObjectKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.maary.darkbag.R
import top.maary.darkbag.databinding.CameraUiContainerBinding
import top.maary.darkbag.fragments.CameraFragmentDirections
import top.maary.darkbag.fragments.SettingsFragment
import top.maary.darkbag.modes.CaptureModeCoordinator
import top.maary.darkbag.rawvideo.RawVideoNative
import top.maary.darkbag.repository.ImageRepository
import top.maary.darkbag.utils.LutManager
import top.maary.darkbag.utils.MediaStoreUtils
import java.io.File
import java.nio.ByteBuffer

/** Host contract for the gallery thumbnail controller. */
interface CameraThumbnailHost {
    val hostContext: Context
    val hostActivity: FragmentActivity?
    val lifecycleScope: CoroutineScope
    val cameraUiContainerBinding: CameraUiContainerBinding?
    val activeCoordinator: CaptureModeCoordinator?
    val imageRepository: ImageRepository
    val isProcessing: Boolean
}

/**
 * Owns the gallery thumbnail: the "latest capture" URI/timestamp ordering rule, asynchronous
 * Glide / RAW-video frame decoding, and photo-view tap / long-press navigation.
 */
class CameraThumbnailController(private val host: CameraThumbnailHost) {

    private val mediaStoreUtils by lazy { MediaStoreUtils(host.hostContext) }

    var currentThumbnailUri: Uri? = null
        private set
    private var currentThumbnailTimestamp: Long = 0L

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
        host.lifecycleScope.launch {
            val thumbnailUri = mediaStoreUtils.getLatestAppImage()
            updateCurrentThumbnail(thumbnailUri)
            setGalleryThumbnail(thumbnailUri?.toString())
            kotlin.runCatching { host.imageRepository.getGroupedImages() }
        }
    }

    fun setGalleryThumbnail(filename: String?) {
        val photoViewButton = host.cameraUiContainerBinding?.photoViewButton ?: return
        photoViewButton.post {
            val shouldHide = (host.activeCoordinator?.shouldHideGalleryThumbnail() == true) || host.isProcessing
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

            host.lifecycleScope.launch(Dispatchers.Main) {
                val uri = try { Uri.parse(filename) } catch (_: Exception) { null }
                val isRawVid = filename.endsWith(".rawvid", ignoreCase = true) ||
                    uri?.toString()?.endsWith(".rawvid", ignoreCase = true) == true
                if (isRawVid && uri != null) {
                    val bmp = withContext(Dispatchers.IO) { decodeRawVideoThumbnail(uri) }
                    if (bmp != null) {
                        Glide.with(photoViewButton).load(bmp).apply(RequestOptions.centerCropTransform()).into(photoViewButton)
                        return@launch
                    }
                }
                val (loadTarget, lastModified) = withContext(Dispatchers.IO) {
                    val lm = try {
                        mediaStoreUtils.getFileLastModified(host.hostContext, Uri.parse(filename))
                    } catch (e: Exception) {
                        File(filename).let { if (it.exists()) it.lastModified() else 0L }
                    }
                    val file = File(filename)
                    val target: Any = if (file.exists()) file else filename
                    Pair(target, lm)
                }
                val thumbSizePx = host.hostContext.resources.getDimensionPixelSize(R.dimen.round_button_medium)
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

    /** Decodes frame 0 of a .rawvid file into a small preview bitmap (IO thread only). */
    private fun decodeRawVideoThumbnail(uri: Uri): Bitmap? = try {
        host.hostContext.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
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
                    SettingsFragment.LOG_CURVES.indexOf(header.activeLogName).takeIf { it >= 0 } ?: -1
                } else -1
                val lutDir = LutManager(host.hostContext).lutDir
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
        val button = host.cameraUiContainerBinding?.photoViewButton ?: return
        button.setOnLongClickListener {
            val activity = host.hostActivity ?: return@setOnLongClickListener false
            val nav = Navigation.findNavController(activity, R.id.fragment_container)
            if (nav.currentDestination?.id == R.id.camera_fragment) {
                nav.navigate(CameraFragmentDirections.actionCameraToDarkroomGallery(initialUri = currentThumbnailUri?.toString()))
            }
            true
        }
        button.setOnClickListener {
            host.lifecycleScope.launch {
                val uri = currentThumbnailUri ?: mediaStoreUtils.getLatestAppImage() ?: return@launch
                val context = host.hostContext
                val prefs = context.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
                val useInternalViewer = prefs.getBoolean(SettingsFragment.KEY_USE_INTERNAL_VIEWER, true)
                var externalViewerStarted = false
                if (!useInternalViewer) {
                    try {
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "image/*")
                            addCategory(Intent.CATEGORY_DEFAULT)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            prefs.getString(SettingsFragment.KEY_EXTERNAL_VIEWER_PACKAGE, "")
                                ?.takeIf { it.isNotEmpty() }?.let { setPackage(it) }
                        }
                        context.startActivity(intent)
                        externalViewerStarted = true
                    } catch (e: Exception) {
                        Toast.makeText(context, R.string.error_no_external_viewer, Toast.LENGTH_SHORT).show()
                    }
                }
                if (useInternalViewer || !externalViewerStarted) {
                    val activity = host.hostActivity ?: return@launch
                    Navigation.findNavController(activity, R.id.fragment_container)
                        .navigate(CameraFragmentDirections.actionCameraToImageViewer(uri.toString()))
                }
            }
        }
    }
}
