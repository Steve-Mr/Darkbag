package top.maary.darkbag.ui.viewer.playback

import android.content.Context
import android.graphics.RectF
import android.net.Uri
import android.view.View
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.maary.darkbag.fragments.ImageViewerAdapter
import top.maary.darkbag.models.ImageGroup
import top.maary.darkbag.motionphoto.MotionPhotoInfo
import top.maary.darkbag.motionphoto.MotionPhotoReader

/**
 * Handles Motion Photo video extraction, ExoPlayer playback, and surface bindings
 * for viewer pages, isolated from adapter logic.
 */
class MotionPhotoPlaybackDelegate(
    private val context: Context,
    private val scope: CoroutineScope,
    private val updateBounds: (ImageViewerAdapter.ViewHolder, RectF) -> Unit,
    private val onPlaybackEnded: (ImageViewerAdapter.ViewHolder) -> Unit
) {

    fun play(
        holder: ImageViewerAdapter.ViewHolder,
        group: ImageGroup,
        isDngFormat: Boolean,
        onComplete: (() -> Unit)? = null
    ) {
        if (!group.isMotionPhoto || group.jpgUri == null || isDngFormat) {
            onComplete?.invoke()
            return
        }

        holder.extractJob?.cancel()
        holder.playCompletionCallback = onComplete

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

        // Make TextureView part of layout hierarchy with alpha 0 so its SurfaceTexture is allocated immediately
        holder.binding.videoView.alpha = 0f
        holder.binding.videoView.visibility = View.VISIBLE

        holder.extractJob = scope.launch {
            val videoFile = withContext(Dispatchers.IO) {
                val info = if (group.motionPhotoVideoLength > 0) {
                    MotionPhotoInfo(
                        videoLength = group.motionPhotoVideoLength,
                        presentationTimestampUs = group.motionPhotoPtsUs
                    )
                } else null
                val candidateUris = listOfNotNull(group.jpgUri) + group.derivativeJpgUris
                val motionUri = candidateUris.firstOrNull { uri ->
                    MotionPhotoReader.isMotionPhoto(context, uri)
                } ?: group.jpgUri
                MotionPhotoReader.extractVideoToCache(
                    context,
                    motionUri,
                    group.baseName,
                    info
                )
            }
            ensureActive()
            if (videoFile == null || !videoFile.exists()) {
                stop(holder)
                val cb = holder.playCompletionCallback
                holder.playCompletionCallback = null
                cb?.invoke()
                return@launch
            }

            val player = holder.player ?: ExoPlayer.Builder(context).build().also { p ->
                holder.player = p
                p.setVideoTextureView(holder.binding.videoView)
                p.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_ENDED) {
                            stop(holder)
                            val cb = holder.playCompletionCallback
                            holder.playCompletionCallback = null
                            cb?.invoke()
                            onPlaybackEnded(holder)
                        }
                    }

                    override fun onRenderedFirstFrame() {
                        holder.binding.videoView.alpha = 1f
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        android.util.Log.e("MotionPhotoPlayback", "Motion photo playback error", error)
                        stop(holder)
                        val cb = holder.playCompletionCallback
                        holder.playCompletionCallback = null
                        cb?.invoke()
                        onPlaybackEnded(holder)
                    }
                })
            }

            val mediaItem = MediaItem.fromUri(Uri.fromFile(videoFile))
            player.setMediaItem(mediaItem)
            player.repeatMode = Player.REPEAT_MODE_OFF
            player.seekTo(0)
            player.prepare()
            player.play()
            holder.isPlayingVideo = true
        }
    }

    fun stop(holder: ImageViewerAdapter.ViewHolder) {
        holder.extractJob?.cancel()
        holder.extractJob = null
        holder.player?.stop()
        holder.binding.videoView.visibility = View.INVISIBLE
        holder.binding.videoView.alpha = 0f
        holder.isPlayingVideo = false
    }

    fun release(holder: ImageViewerAdapter.ViewHolder) {
        stop(holder)
        holder.player?.release()
        holder.player = null
    }
}
