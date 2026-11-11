package top.maary.darkbag.ui.viewer.image

import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.view.View
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.DrawableImageViewTarget
import com.bumptech.glide.request.transition.Transition
import com.bumptech.glide.signature.ObjectKey
import kotlinx.coroutines.*
import top.maary.darkbag.fragments.ImageViewerAdapter
import top.maary.darkbag.models.ImageGroup
import top.maary.darkbag.ui.viewer.derivative.ViewerDerivativeSelectorDelegate
import top.maary.darkbag.ui.viewer.lens.ViewerLensSelectorDelegate
import top.maary.darkbag.ui.viewer.playback.ViewerPagePlaybackController
import top.maary.darkbag.utils.ImageUtils

/**
 * 图像/海报帧异步加载与内存回收委托类 (ViewerImageLoaderDelegate).
 * 统一管理 DNG 缩略图、Glide 静态图、半格拼接图及 MP4 视频海报帧的加载、取消和位图回收。
 */
class ViewerImageLoaderDelegate(
    private val scope: CoroutineScope,
    private val videoDurationCache: MutableMap<Uri, Long>,
    private val formatVideoTime: (Long) -> String
) {

    fun setBitmapAndRecyclePrevious(holder: ImageViewerAdapter.ViewHolder, bitmap: Bitmap) {
        Glide.with(holder.binding.imageView).clear(holder.binding.imageView)
        val oldManual = holder.manualBitmap
        if (oldManual != null && oldManual !== bitmap && !oldManual.isRecycled) {
            oldManual.recycle()
        }
        holder.manualBitmap = bitmap
        holder.binding.imageView.setImageBitmap(bitmap)
    }

    fun clearCurrentBitmap(holder: ImageViewerAdapter.ViewHolder) {
        Glide.with(holder.binding.imageView).clear(holder.binding.imageView)
        holder.manualBitmap?.let { bitmap ->
            if (!bitmap.isRecycled) bitmap.recycle()
        }
        holder.manualBitmap = null
        holder.binding.imageView.setImageDrawable(null)
    }

    fun loadSelectedFormat(
        holder: ImageViewerAdapter.ViewHolder,
        group: ImageGroup,
        format: String,
        lensDelegate: ViewerLensSelectorDelegate,
        derivativeDelegate: ViewerDerivativeSelectorDelegate,
        playbackController: ViewerPagePlaybackController
    ) {
        if (group.isMultiCamera) {
            val lenses = lensDelegate.getMultiCameraLenses(group)
            val idx = lensDelegate.getSelectedLensIndex(group.baseName).coerceIn(0, (lenses.size - 1).coerceAtLeast(0))
            val lens = lenses.getOrNull(idx)
            when (format) {
                ImageViewerAdapter.FORMAT_JPG -> {
                    val uri = lens?.jpgUri ?: lens?.dngUri
                    uri?.let { loadImage(holder, it, version = group.lastModified) }
                }
                ImageViewerAdapter.FORMAT_DNG -> {
                    val uri = lens?.dngUri ?: lens?.jpgUri
                    uri?.let { loadImage(holder, it) }
                }
            }
            return
        }

        if (format == ImageViewerAdapter.FORMAT_DNG) {
            playbackController.stopMotionVideo(holder)
            val isRawVideo = group.isRawVideo || group.rawVideoUri != null
            if (isRawVideo) {
                group.rawVideoUri?.let { playbackController.loadRawVideoPosterFrame(holder, it, group) }
                return
            }

            if (group.isHalfFrame()) {
                loadHalfFrameDngs(holder, group, group.editConfig?.zoomFactor ?: 1.0f)
            } else {
                val isCinemaDngGroup = group.isCinemaDng || group.cinemaDngFolderUri != null || group.cinemaDngFirstFrameUri != null || group.cinemaDngFrameUris.isNotEmpty()
                val dngUri = if (isCinemaDngGroup) {
                    holder.activeCinemaDngFrameUri ?: group.cinemaDngFirstFrameUri ?: group.cinemaDngFrameUris.firstOrNull()
                } else {
                    group.dngUri ?: group.dngUri1 ?: group.dngUri2
                }
                dngUri?.let { loadImage(holder, it) }
            }
            return
        }

        // FORMAT_JPG
        val derivatives = derivativeDelegate.getDerivativeUris(group)
        val activeIndex = derivativeDelegate.getSelectedDerivativeIndex(group.baseName).coerceIn(0, (derivatives.size - 1).coerceAtLeast(0))
        val activeDerivativeUri = derivatives.getOrNull(activeIndex) ?: group.jpgUri ?: group.mp4VideoUri

        if (activeDerivativeUri != null) {
            val isMp4 = activeDerivativeUri.toString().endsWith(".mp4", ignoreCase = true) || (group.isMp4Video && group.rawVideoUri == null)
            if (isMp4) {
                holder.binding.videoView.visibility = View.GONE
                holder.binding.imageView.visibility = View.VISIBLE
                loadMp4PosterFrame(holder, activeDerivativeUri)
            } else {
                loadImage(holder, activeDerivativeUri, version = group.lastModified)
            }
        } else if (group.rawVideoUri != null) {
            playbackController.loadRawVideoPosterFrame(holder, group.rawVideoUri!!, group)
        }
    }

    fun loadImage(
        holder: ImageViewerAdapter.ViewHolder,
        uri: Uri,
        zoomFactor: Float = 1.0f,
        version: Long = 0L
    ) {
        if (holder.currentUri == uri && holder.currentVersion == version && holder.binding.imageView.drawable != null) {
            holder.binding.loadingIndicator.visibility = View.GONE
            return
        }
        holder.loadJob?.cancel()
        holder.binding.imageView.visibility = View.VISIBLE
        holder.binding.loadingIndicator.visibility = View.VISIBLE

        if (uri.toString().endsWith(".dng", ignoreCase = true)) {
            loadDngThumbnailOnly(holder, uri, zoomFactor)
        } else {
            loadWithGlide(holder, uri, version = version)
        }
    }

    fun loadDngThumbnailOnly(holder: ImageViewerAdapter.ViewHolder, uri: Uri, zoomFactor: Float = 1.0f) {
        val trackingUri = uri.buildUpon().appendQueryParameter("zoom", zoomFactor.toString()).build()
        if (holder.currentUri == trackingUri && holder.binding.imageView.drawable != null) {
            holder.binding.loadingIndicator.visibility = View.GONE
            return
        }
        holder.loadJob?.cancel()
        holder.currentUri = trackingUri
        holder.binding.imageView.visibility = View.VISIBLE
        holder.binding.loadingIndicator.visibility = View.VISIBLE

        holder.loadJob = scope.launch {
            var thumbnailBitmap: Bitmap? = null
            try {
                thumbnailBitmap = ImageUtils.decodeDngThumbnail(holder.binding.root.context, uri, zoomFactor)
                ensureActive()

                if (thumbnailBitmap == null) {
                    thumbnailBitmap = ImageUtils.renderDngBitmap(
                        holder.binding.root.context,
                        uri,
                        reqWidth = 1024,
                        reqHeight = 1024,
                        zoomFactor = zoomFactor
                    )
                    ensureActive()
                }

                if (thumbnailBitmap != null) {
                    setBitmapAndRecyclePrevious(holder, thumbnailBitmap)
                    thumbnailBitmap = null
                } else {
                    loadWithGlide(holder, uri)
                }
            } catch (e: CancellationException) {
                thumbnailBitmap?.recycle()
                throw e
            } catch (e: Exception) {
                Log.e("ViewerImageLoader", "Failed to load DNG thumbnail: $uri", e)
                thumbnailBitmap?.recycle()
                loadWithGlide(holder, uri)
            } finally {
                holder.binding.loadingIndicator.visibility = View.GONE
            }
        }
    }

    fun loadHalfFrameDngs(holder: ImageViewerAdapter.ViewHolder, group: ImageGroup, zoomFactor: Float = 1.0f) {
        val trackingUri = Uri.parse("hf://${group.baseName}?layout=${group.hfLayout}&zoom=$zoomFactor")
        if (holder.currentUri == trackingUri && holder.binding.imageView.drawable != null) {
            holder.binding.loadingIndicator.visibility = View.GONE
            return
        }
        holder.loadJob?.cancel()
        holder.currentUri = trackingUri
        holder.binding.imageView.visibility = View.VISIBLE
        holder.binding.loadingIndicator.visibility = View.VISIBLE
        holder.loadJob = scope.launch {
            var composite: Bitmap? = null
            try {
                composite = ImageUtils.generateHalfFrameComposite(
                    holder.binding.root.context,
                    group.dngUri1,
                    group.dngUri2,
                    group.hfLayout,
                    zoomFactor
                )
                ensureActive()
                if (composite != null) {
                    setBitmapAndRecyclePrevious(holder, composite)
                    composite = null
                } else {
                    clearCurrentBitmap(holder)
                    holder.binding.imageView.setImageResource(android.R.drawable.ic_menu_gallery)
                }
            } catch (e: CancellationException) {
                composite?.recycle()
                throw e
            } catch (e: Exception) {
                Log.e("ViewerImageLoader", "Failed to load half-frame DNGs", e)
                composite?.recycle()
                clearCurrentBitmap(holder)
                holder.binding.imageView.setImageResource(android.R.drawable.ic_menu_gallery)
            } finally {
                holder.binding.loadingIndicator.visibility = View.GONE
            }
        }
    }

    fun loadMp4PosterFrame(holder: ImageViewerAdapter.ViewHolder, uri: Uri) {
        val ctx = holder.binding.root.context
        scope.launch(Dispatchers.IO) {
            var bmp: Bitmap? = null
            var durMs: Long = 0L
            try {
                val retriever = android.media.MediaMetadataRetriever()
                if (uri.scheme == "content") {
                    ctx.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                        retriever.setDataSource(pfd.fileDescriptor)
                        bmp = retriever.getFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        durMs = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    }
                } else {
                    retriever.setDataSource(uri.path)
                    bmp = retriever.getFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    durMs = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                }
                retriever.release()
            } catch (e: Exception) {
                Log.w("ViewerImageLoader", "MediaMetadataRetriever failed for $uri", e)
            }

            if (durMs > 0) {
                videoDurationCache[uri] = durMs
            }

            withContext(Dispatchers.Main) {
                if (bmp != null) {
                    if (holder.binding.imageView.rotation != 0f) {
                        holder.binding.imageView.rotation = 0f
                    }
                    holder.binding.imageView.setImageBitmap(bmp)
                } else {
                    Glide.with(holder.binding.imageView)
                        .asBitmap()
                        .load(uri)
                        .apply(RequestOptions().frame(0).diskCacheStrategy(DiskCacheStrategy.RESOURCE))
                        .into(holder.binding.imageView)
                }
                if (durMs > 0 && !holder.isPlayingVideo) {
                    holder.binding.btnVideoDuration.text = formatVideoTime(durMs)
                }
            }
        }
    }

    fun loadWithGlide(holder: ImageViewerAdapter.ViewHolder, uri: Uri, version: Long = 0L) {
        if (holder.currentUri == uri && holder.currentVersion == version && holder.binding.imageView.drawable != null) {
            holder.binding.loadingIndicator.visibility = View.GONE
            return
        }
        val previousDrawable = holder.binding.imageView.drawable
        holder.currentUri = uri
        holder.currentVersion = version

        val model = if (version > 0) ObjectKey(version) else null

        Glide.with(holder.binding.imageView)
            .asDrawable()
            .load(uri)
            .let { if (model != null) it.signature(model) else it }
            .placeholder(previousDrawable)
            .into(object : DrawableImageViewTarget(holder.binding.imageView) {
                override fun onLoadFailed(errorDrawable: android.graphics.drawable.Drawable?) {
                    super.onLoadFailed(errorDrawable)
                    holder.binding.loadingIndicator.visibility = View.GONE
                }
                override fun onResourceReady(
                    resource: android.graphics.drawable.Drawable,
                    transition: Transition<in android.graphics.drawable.Drawable>?
                ) {
                    super.onResourceReady(resource, transition)
                    holder.binding.loadingIndicator.visibility = View.GONE
                }
            })
    }
}
