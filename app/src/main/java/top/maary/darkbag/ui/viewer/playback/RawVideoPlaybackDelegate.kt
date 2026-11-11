package top.maary.darkbag.ui.viewer.playback

import android.content.Context
import android.graphics.RectF
import android.net.Uri
import android.view.Surface
import android.view.TextureView
import android.view.View
import top.maary.darkbag.R
import top.maary.darkbag.fragments.ImageViewerAdapter
import top.maary.darkbag.models.EditConfig
import top.maary.darkbag.models.ImageGroup
import top.maary.darkbag.rawvideo.RawVideoPlayer
import java.util.concurrent.ConcurrentHashMap

/**
 * Handles RawVideoPlayer setup, rendering frames, seeking, surface listeners,
 * and adjustment updates for RAW video pages.
 */
class RawVideoPlaybackDelegate(
    private val context: Context,
    private val videoDurationCache: ConcurrentHashMap<Uri, Long>,
    private val formatVideoTime: (Long) -> String,
    private val updateBounds: (ImageViewerAdapter.ViewHolder, RectF) -> Unit,
    private val onPlaybackStateChanged: (ImageViewerAdapter.ViewHolder, Boolean, Long, Long) -> Unit
) {

    fun loadPosterFrame(holder: ImageViewerAdapter.ViewHolder, uri: Uri, group: ImageGroup) {
        val ctx = holder.binding.root.context
        holder.binding.videoView.visibility = View.GONE
        holder.binding.imageView.visibility = View.VISIBLE

        val player = holder.rawVideoPlayer ?: RawVideoPlayer(
            context = ctx,
            onFrameRendered = { bitmap, _ ->
                if (holder.binding.imageView.rotation != 0f) {
                    holder.binding.imageView.rotation = 0f
                }
                holder.binding.imageView.setImageBitmap(bitmap)
            },
            onPlaybackStateChanged = { isPlaying ->
                holder.isPlayingVideo = isPlaying
                if (isPlaying) {
                    holder.binding.btnVideoDuration.setIconResource(R.drawable.ic_pause)
                    holder.videoProgressRunnable?.let {
                        holder.binding.btnVideoDuration.removeCallbacks(it)
                        holder.binding.btnVideoDuration.post(it)
                    }
                } else {
                    holder.binding.btnVideoDuration.setIconResource(R.drawable.ic_play_arrow)
                    val cached = videoDurationCache[uri] ?: 0L
                    if (cached > 0) {
                        holder.binding.btnVideoDuration.text = formatVideoTime(cached)
                    }
                }
            }
        ).also { holder.rawVideoPlayer = it }

        if (!player.isVideoLoaded || holder.currentLoadedRawUri != uri) {
            holder.currentLoadedRawUri = uri
            player.load(uri)
        } else {
            player.seekTo(0)
        }
        val fps = player.fps.takeIf { it > 0 } ?: 24.0f
        val totalMs = ((player.frameCount / fps) * 1000).toLong()
        if (totalMs > 0) {
            videoDurationCache[uri] = totalMs
            if (!holder.isPlayingVideo) {
                holder.binding.btnVideoDuration.text = formatVideoTime(totalMs)
            }
        }
        player.updateAdjustments(group.editConfig)
    }

    fun play(holder: ImageViewerAdapter.ViewHolder, group: ImageGroup) {
        val uri = group.rawVideoUri ?: return
        val player = holder.rawVideoPlayer ?: RawVideoPlayer(
            context = holder.binding.root.context,
            onFrameRendered = { bitmap, _ ->
                if (holder.binding.imageView.rotation != 0f) {
                    holder.binding.imageView.rotation = 0f
                }
                holder.binding.imageView.setImageBitmap(bitmap)
            },
            onPlaybackStateChanged = { isPlaying ->
                holder.isPlayingVideo = isPlaying
                if (isPlaying) {
                    holder.binding.btnVideoDuration.setIconResource(R.drawable.ic_pause)
                    holder.videoProgressRunnable?.let {
                        holder.binding.btnVideoDuration.removeCallbacks(it)
                        holder.binding.btnVideoDuration.post(it)
                    }
                } else {
                    holder.binding.btnVideoDuration.setIconResource(R.drawable.ic_play_arrow)
                    val cached = videoDurationCache[uri] ?: 0L
                    if (cached > 0) {
                        holder.binding.btnVideoDuration.text = formatVideoTime(cached)
                    }
                }
                val p = holder.rawVideoPlayer
                val curMs = if (p != null && p.isVideoLoaded) ((p.currentFrame / (p.fps.takeIf { it > 0 } ?: 24.0f)) * 1000).toLong() else 0L
                val totalMs = if (p != null && p.isVideoLoaded) ((p.frameCount / (p.fps.takeIf { it > 0 } ?: 24.0f)) * 1000).toLong() else (videoDurationCache[uri] ?: 0L)
                onPlaybackStateChanged(holder, isPlaying, curMs, totalMs)
            }
        ).also { holder.rawVideoPlayer = it }

        if (!player.isVideoLoaded) {
            player.load(uri)
        }
        val fps = player.fps.takeIf { it > 0 } ?: 24.0f
        val totalMs = ((player.frameCount / fps) * 1000).toLong()
        if (totalMs > 0) {
            videoDurationCache[uri] = totalMs
        }
        player.updateAdjustments(group.editConfig)

        // Align videoView bounds immediately to match current imageView rect
        val iv = holder.binding.imageView
        val d = iv.drawable
        if (d != null && iv.width > 0 && iv.height > 0) {
            val rect = RectF(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
            iv.imageMatrix.mapRect(rect)
            rect.intersect(0f, 0f, iv.width.toFloat(), iv.height.toFloat())
            holder.lastDisplayRect = RectF(rect)
            updateBounds(holder, rect)
        }

        holder.binding.videoView.alpha = 1f
        holder.binding.videoView.visibility = View.VISIBLE
        if (holder.binding.videoView.isAvailable) {
            player.setSurface(Surface(holder.binding.videoView.surfaceTexture))
        } else {
            holder.binding.videoView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {
                    player.setSurface(Surface(surface))
                }
                override fun onSurfaceTextureSizeChanged(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {}
                override fun onSurfaceTextureDestroyed(surface: android.graphics.SurfaceTexture): Boolean {
                    player.setSurface(null)
                    return true
                }
                override fun onSurfaceTextureUpdated(surface: android.graphics.SurfaceTexture) {}
            }
        }

        player.play()
        holder.binding.btnVideoDuration.setIconResource(R.drawable.ic_pause)
    }

    fun stop(holder: ImageViewerAdapter.ViewHolder) {
        holder.rawVideoPlayer?.pause()
        holder.isPlayingVideo = false
        holder.binding.videoView.visibility = View.GONE
        holder.binding.videoView.alpha = 0f
        holder.binding.btnVideoDuration.setIconResource(R.drawable.ic_play_arrow)
    }

    fun updateAdjustments(holder: ImageViewerAdapter.ViewHolder, editConfig: EditConfig?) {
        holder.rawVideoPlayer?.updateAdjustments(editConfig)
    }

    fun release(holder: ImageViewerAdapter.ViewHolder) {
        stop(holder)
        holder.rawVideoPlayer?.release()
        holder.rawVideoPlayer = null
        holder.currentLoadedRawUri = null
    }
}
