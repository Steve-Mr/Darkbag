package top.maary.darkbag.ui.viewer.playback

import android.content.Context
import android.graphics.RectF
import android.net.Uri
import android.view.View
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import top.maary.darkbag.R
import top.maary.darkbag.fragments.ImageViewerAdapter
import java.util.concurrent.ConcurrentHashMap

/**
 * Handles ExoPlayer playback for standard MP4 and exported derivative videos.
 */
class Mp4VideoPlaybackDelegate(
    private val context: Context,
    private val videoDurationCache: ConcurrentHashMap<Uri, Long>,
    private val updateBounds: (ImageViewerAdapter.ViewHolder, RectF) -> Unit,
    private val onPlaybackEnded: (ImageViewerAdapter.ViewHolder) -> Unit
) {

    fun play(holder: ImageViewerAdapter.ViewHolder, uri: Uri) {
        val iv = holder.binding.imageView
        val d = iv.drawable
        if (d != null && iv.width > 0 && iv.height > 0) {
            val rect = RectF(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
            iv.imageMatrix.mapRect(rect)
            rect.intersect(0f, 0f, iv.width.toFloat(), iv.height.toFloat())
            holder.lastDisplayRect = RectF(rect)
            updateBounds(holder, rect)
        }

        holder.binding.videoView.alpha = 0f
        holder.binding.videoView.visibility = View.VISIBLE

        val player = holder.player ?: ExoPlayer.Builder(holder.itemView.context).build().also { p ->
            holder.player = p
            p.setVideoTextureView(holder.binding.videoView)
            p.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) {
                        stop(holder)
                        holder.binding.btnVideoDuration.setIconResource(R.drawable.ic_play_arrow)
                        onPlaybackEnded(holder)
                    } else if (playbackState == Player.STATE_READY) {
                        val dur = p.duration
                        if (dur > 0) {
                            videoDurationCache[uri] = dur
                        }
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    android.util.Log.e("Mp4VideoPlayback", "MP4 playback error", error)
                    stop(holder)
                    holder.binding.btnVideoDuration.setIconResource(R.drawable.ic_play_arrow)
                    onPlaybackEnded(holder)
                }

                override fun onRenderedFirstFrame() {
                    holder.binding.videoView.alpha = 1f
                }

                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    val innerIv = holder.binding.imageView
                    val innerD = innerIv.drawable
                    if (innerD != null && innerIv.width > 0 && innerIv.height > 0) {
                        val rect = RectF(0f, 0f, innerD.intrinsicWidth.toFloat(), innerD.intrinsicHeight.toFloat())
                        innerIv.imageMatrix.mapRect(rect)
                        rect.intersect(0f, 0f, innerIv.width.toFloat(), innerIv.height.toFloat())
                        holder.lastDisplayRect = RectF(rect)
                        updateBounds(holder, rect)
                    } else {
                        holder.lastDisplayRect?.let { rect ->
                            updateBounds(holder, rect)
                        }
                    }
                }
            })
        }

        val mediaItem = MediaItem.fromUri(uri)
        player.setMediaItem(mediaItem)
        player.repeatMode = Player.REPEAT_MODE_OFF
        player.seekTo(0)
        player.prepare()
        player.play()
        holder.isPlayingVideo = true
        holder.binding.btnVideoDuration.setIconResource(R.drawable.ic_pause)
    }

    fun stop(holder: ImageViewerAdapter.ViewHolder) {
        holder.player?.stop()
        holder.binding.videoView.visibility = View.INVISIBLE
        holder.binding.videoView.alpha = 0f
        holder.isPlayingVideo = false
        holder.binding.btnVideoDuration.setIconResource(R.drawable.ic_play_arrow)
    }

    fun release(holder: ImageViewerAdapter.ViewHolder) {
        stop(holder)
        holder.player?.release()
        holder.player = null
    }
}
