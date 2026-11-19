package top.maary.darkbag.ui.viewer.playback

import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.net.Uri
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import top.maary.darkbag.R
import top.maary.darkbag.fragments.ImageViewerAdapter
import top.maary.darkbag.models.EditConfig
import top.maary.darkbag.models.ImageGroup
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Coordinates viewer multimedia engines (Motion Photo, Raw Video, MP4, CinemaDNG),
 * tracking progress and managing surface transforms without adapter entanglement.
 */
class ViewerPagePlaybackController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val getRecyclerView: () -> RecyclerView?,
    private val getGroupAt: (Int) -> ImageGroup?,
    private val getBackingList: () -> List<ImageGroup>,
    private val radius: Float,
    private val loadCinemaDngFrame: (ImageViewerAdapter.ViewHolder, ImageGroup, Uri, Int) -> Unit,
    private val onCinemaDngFrameSelected: ((group: ImageGroup, index: Int, uri: Uri?) -> Unit)?,
    var onVideoPlaybackStateChanged: ((position: Int, isPlaying: Boolean, currentMs: Long, totalMs: Long) -> Unit)? = null
) {

    val videoDurationCache = ConcurrentHashMap<Uri, Long>()

    val motionPhotoDelegate = MotionPhotoPlaybackDelegate(
        context = context,
        scope = scope,
        updateBounds = { holder, rect -> updateVideoViewBounds(holder, rect) },
        onPlaybackEnded = { holder -> stopVideoProgressTracking(holder) }
    )

    val rawVideoDelegate = RawVideoPlaybackDelegate(
        context = context,
        videoDurationCache = videoDurationCache,
        formatVideoTime = ::formatVideoTime,
        updateBounds = { holder, rect -> updateVideoViewBounds(holder, rect) },
        onPlaybackStateChanged = { holder, isPlaying, curMs, totalMs ->
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                onVideoPlaybackStateChanged?.invoke(pos, isPlaying, curMs, totalMs)
            }
        }
    )

    val mp4VideoDelegate = Mp4VideoPlaybackDelegate(
        context = context,
        videoDurationCache = videoDurationCache,
        updateBounds = { holder, rect -> updateVideoViewBounds(holder, rect) },
        onPlaybackEnded = { holder -> stopVideoProgressTracking(holder) }
    )

    val cinemaDngDelegate = CinemaDngPlaybackDelegate(
        loadCinemaDngFrame = loadCinemaDngFrame,
        onCinemaDngFrameSelected = onCinemaDngFrameSelected,
        onPlaybackStateChanged = { pos, isPlaying, curMs, totalMs ->
            onVideoPlaybackStateChanged?.invoke(pos, isPlaying, curMs, totalMs)
        }
    )

    fun formatVideoTime(millis: Long): String {
        val totalSec = (millis / 1000).coerceAtLeast(0)
        val minutes = totalSec / 60
        val seconds = totalSec % 60
        return String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }

    fun updateVideoViewBounds(holder: ImageViewerAdapter.ViewHolder, rect: RectF) {
        val videoView = holder.binding.videoView
        val viewWidth = videoView.width.toFloat().takeIf { it > 0 }
            ?: holder.binding.imageView.measuredWidth.toFloat().takeIf { it > 0 }
            ?: return
        val viewHeight = videoView.height.toFloat().takeIf { it > 0 }
            ?: holder.binding.imageView.measuredHeight.toFloat().takeIf { it > 0 }
            ?: return

        if (rect.width() <= 0 || rect.height() <= 0) return

        val matrix = Matrix()
        val scaleX = rect.width() / viewWidth
        val scaleY = rect.height() / viewHeight
        matrix.setScale(scaleX, scaleY)
        matrix.postTranslate(rect.left, rect.top)
        videoView.setTransform(matrix)

        holder.videoOutlineRect.set(
            rect.left.toInt(),
            rect.top.toInt(),
            rect.right.toInt(),
            rect.bottom.toInt()
        )
        holder.videoOutlineRadius = radius
        videoView.invalidateOutline()
    }

    fun startVideoProgressTracking(holder: ImageViewerAdapter.ViewHolder) {
        stopVideoProgressTracking(holder)
        val binding = holder.binding

        val runnable = object : Runnable {
            override fun run() {
                val rawPlayer = holder.rawVideoPlayer
                val exo = holder.player

                if (rawPlayer != null && rawPlayer.isVideoLoaded) {
                    val frame = rawPlayer.currentFrame
                    val count = rawPlayer.frameCount
                    val fps = rawPlayer.fps.takeIf { it > 0 } ?: 24.0f
                    val curMs = ((frame / fps) * 1000).toLong()
                    val totalMs = ((count / fps) * 1000).toLong()

                    binding.btnVideoDuration.text = "${formatVideoTime(curMs)} / ${formatVideoTime(totalMs)}"
                    val isPlaying = holder.isPlayingVideo
                    binding.btnVideoDuration.setIconResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow)
                    val pos = holder.bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) {
                        onVideoPlaybackStateChanged?.invoke(pos, isPlaying, curMs, totalMs)
                    }
                } else if (exo != null) {
                    val curMs = exo.currentPosition.coerceAtLeast(0)
                    val totalMs = exo.duration.takeIf { it > 0 } ?: 0L

                    if (totalMs > 0) {
                        binding.btnVideoDuration.text = "${formatVideoTime(curMs)} / ${formatVideoTime(totalMs)}"
                    } else {
                        binding.btnVideoDuration.text = formatVideoTime(curMs)
                    }
                    val isPlaying = exo.isPlaying
                    binding.btnVideoDuration.setIconResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow)
                    val pos = holder.bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) {
                        onVideoPlaybackStateChanged?.invoke(pos, isPlaying, curMs, totalMs)
                    }
                }

                if (holder.isPlayingVideo) {
                    binding.btnVideoDuration.postDelayed(this, 100)
                }
            }
        }
        holder.videoProgressRunnable = runnable
        binding.btnVideoDuration.post(runnable)
    }

    fun stopVideoProgressTracking(holder: ImageViewerAdapter.ViewHolder) {
        holder.videoProgressRunnable?.let {
            holder.binding.btnVideoDuration.removeCallbacks(it)
        }
        holder.videoProgressRunnable = null
        val rawPlayer = holder.rawVideoPlayer
        val exo = holder.player
        val currentUri = holder.currentUri
        val cached = currentUri?.let { videoDurationCache[it] }
        val totalMs: Long
        val curMs: Long
        if (cached != null && cached > 0) {
            holder.binding.btnVideoDuration.text = formatVideoTime(cached)
            totalMs = cached
            curMs = 0L
        } else if (rawPlayer != null && rawPlayer.isVideoLoaded) {
            val count = rawPlayer.frameCount
            val fps = rawPlayer.fps.takeIf { it > 0 } ?: 24.0f
            totalMs = ((count / fps) * 1000).toLong()
            curMs = ((rawPlayer.currentFrame / fps) * 1000).toLong()
            holder.binding.btnVideoDuration.text = formatVideoTime(totalMs)
        } else if (exo != null && exo.duration > 0) {
            totalMs = exo.duration
            curMs = exo.currentPosition.coerceAtLeast(0)
            holder.binding.btnVideoDuration.text = formatVideoTime(exo.duration)
        } else {
            totalMs = 0L
            curMs = 0L
        }
        holder.binding.btnVideoDuration.setIconResource(R.drawable.ic_play_arrow)
        val pos = holder.bindingAdapterPosition
        if (pos != RecyclerView.NO_POSITION) {
            onVideoPlaybackStateChanged?.invoke(pos, false, curMs, totalMs)
        }
    }

    fun playMotionVideo(holder: ImageViewerAdapter.ViewHolder, group: ImageGroup, isDngFormat: Boolean, onComplete: (() -> Unit)? = null) {
        motionPhotoDelegate.play(holder, group, isDngFormat, onComplete)
    }

    fun stopMotionVideo(holder: ImageViewerAdapter.ViewHolder) {
        stopVideoProgressTracking(holder)
        holder.cinemaDngPlaybackJob?.cancel()
        holder.cinemaDngPlaybackJob = null
        motionPhotoDelegate.stop(holder)
        holder.binding.btnVideoDuration.setIconResource(R.drawable.ic_play_arrow)
        val pos = holder.bindingAdapterPosition
        if (pos != RecyclerView.NO_POSITION) {
            onVideoPlaybackStateChanged?.invoke(pos, false, 0L, getVideoDuration(pos))
        }
    }

    fun playMotionVideoForPosition(position: Int, isDngFormat: Boolean, onComplete: (() -> Unit)? = null) {
        val rv = getRecyclerView() ?: run { onComplete?.invoke(); return }
        val holder = rv.findViewHolderForAdapterPosition(position) as? ImageViewerAdapter.ViewHolder
        val group = getGroupAt(position)
        if (holder != null && group != null) {
            playMotionVideo(holder, group, isDngFormat, onComplete)
        } else {
            onComplete?.invoke()
        }
    }

    fun stopMotionVideoForPosition(position: Int) {
        val rv = getRecyclerView() ?: return
        val holder = rv.findViewHolderForAdapterPosition(position) as? ImageViewerAdapter.ViewHolder
        if (holder != null) {
            stopMotionVideo(holder)
        }
    }

    fun loadRawVideoPosterFrame(holder: ImageViewerAdapter.ViewHolder, uri: Uri, group: ImageGroup) {
        rawVideoDelegate.loadPosterFrame(holder, uri, group)
    }

    fun playRawVideo(holder: ImageViewerAdapter.ViewHolder, group: ImageGroup) {
        rawVideoDelegate.play(holder, group)
        startVideoProgressTracking(holder)
    }

    fun stopRawVideo(holder: ImageViewerAdapter.ViewHolder) {
        stopVideoProgressTracking(holder)
        rawVideoDelegate.stop(holder)
        val pos = holder.bindingAdapterPosition
        if (pos != RecyclerView.NO_POSITION) {
            onVideoPlaybackStateChanged?.invoke(pos, false, 0L, getVideoDuration(pos))
        }
    }

    fun toggleRawVideoForPosition(position: Int) {
        val rv = getRecyclerView() ?: return
        val holder = rv.findViewHolderForAdapterPosition(position) as? ImageViewerAdapter.ViewHolder
        val group = getGroupAt(position)
        if (holder != null && group != null) {
            if (holder.isPlayingVideo) {
                stopRawVideo(holder)
            } else {
                playRawVideo(holder, group)
            }
        }
    }

    fun updateRawVideoAdjustments(position: Int, editConfig: EditConfig?) {
        val rv = getRecyclerView() ?: return
        val holder = rv.findViewHolderForAdapterPosition(position) as? ImageViewerAdapter.ViewHolder
        if (holder != null) {
            rawVideoDelegate.updateAdjustments(holder, editConfig)
        }
    }

    fun playMp4Video(holder: ImageViewerAdapter.ViewHolder, uri: Uri) {
        mp4VideoDelegate.play(holder, uri)
        startVideoProgressTracking(holder)
    }

    fun toggleMp4VideoForPosition(position: Int, activeUri: Uri?) {
        val rv = getRecyclerView() ?: return
        val holder = rv.findViewHolderForAdapterPosition(position) as? ImageViewerAdapter.ViewHolder
        if (holder != null && activeUri != null) {
            if (holder.isPlayingVideo) {
                stopMotionVideo(holder)
                holder.binding.btnVideoDuration.setIconResource(R.drawable.ic_play_arrow)
            } else {
                playMp4Video(holder, activeUri)
            }
        }
    }

    fun playCinemaDng(holder: ImageViewerAdapter.ViewHolder, group: ImageGroup) {
        cinemaDngDelegate.play(holder, group)
    }

    fun stopCinemaDng(holder: ImageViewerAdapter.ViewHolder) {
        val pos = holder.bindingAdapterPosition
        val group = if (pos != RecyclerView.NO_POSITION) getGroupAt(pos) else null
        cinemaDngDelegate.stop(holder, group)
    }

    fun toggleCinemaDngForPosition(position: Int) {
        val rv = getRecyclerView() ?: return
        val holder = rv.findViewHolderForAdapterPosition(position) as? ImageViewerAdapter.ViewHolder
        val group = getGroupAt(position)
        if (holder != null && group != null) {
            if (holder.isPlayingVideo && holder.cinemaDngPlaybackJob?.isActive == true) {
                stopCinemaDng(holder)
            } else {
                playCinemaDng(holder, group)
            }
        }
    }

    fun selectCinemaDngFrame(position: Int, frameIndex: Int, uri: Uri? = null) {
        val rv = getRecyclerView() ?: return
        val holder = rv.findViewHolderForAdapterPosition(position) as? ImageViewerAdapter.ViewHolder
        val group = getGroupAt(position)
        if (holder != null && group != null) {
            cinemaDngDelegate.selectFrame(holder, group, frameIndex, uri)
        }
    }

    fun isVideoPlaying(position: Int): Boolean {
        val rv = getRecyclerView() ?: return false
        val holder = rv.findViewHolderForAdapterPosition(position) as? ImageViewerAdapter.ViewHolder ?: return false
        return holder.isPlayingVideo || holder.player?.isPlaying == true || holder.rawVideoPlayer?.isPlayingVideo == true || holder.cinemaDngPlaybackJob?.isActive == true
    }

    fun getVideoDuration(position: Int): Long {
        val list = getBackingList()
        if (position !in list.indices) return 0L
        val group = list[position]
        val rv = getRecyclerView()
        val holder = rv?.findViewHolderForAdapterPosition(position) as? ImageViewerAdapter.ViewHolder
        val rawPlayer = holder?.rawVideoPlayer
        if (rawPlayer != null && rawPlayer.isVideoLoaded) {
            val fps = rawPlayer.fps.takeIf { it > 0 } ?: 24.0f
            return ((rawPlayer.frameCount / fps) * 1000).toLong()
        }
        if (group.isRawVideo && group.rawVideoDurationMs > 0) {
            return group.rawVideoDurationMs
        }
        if (group.isRawVideo && group.rawVideoFrameCount > 0) {
            val fps = group.rawVideoFps.takeIf { it > 0 } ?: 24.0f
            return ((group.rawVideoFrameCount / fps) * 1000).toLong()
        }
        if (group.isCinemaDng || group.cinemaDngFrameUris.isNotEmpty()) {
            val count = group.cinemaDngFrameUris.size.takeIf { it > 0 } ?: group.cinemaDngFrameCount
            return ((count / 24.0f) * 1000).toLong()
        }
        val exo = holder?.player
        if (exo != null && exo.duration > 0) {
            return exo.duration
        }
        val uri = holder?.currentUri ?: group.mp4VideoUri ?: group.rawVideoUri
        return uri?.let { videoDurationCache[it] } ?: 0L
    }

    fun getVideoCurrentPosition(position: Int): Long {
        val rv = getRecyclerView() ?: return 0L
        val holder = rv.findViewHolderForAdapterPosition(position) as? ImageViewerAdapter.ViewHolder ?: return 0L
        val rawPlayer = holder.rawVideoPlayer
        if (rawPlayer != null && rawPlayer.isVideoLoaded) {
            val fps = rawPlayer.fps.takeIf { it > 0 } ?: 24.0f
            return ((rawPlayer.currentFrame / fps) * 1000).toLong()
        }
        val exo = holder.player
        if (exo != null) {
            return exo.currentPosition.coerceAtLeast(0)
        }
        if (holder.activeCinemaDngFrameIndex > 0) {
            return ((holder.activeCinemaDngFrameIndex / 24.0f) * 1000).toLong()
        }
        return 0L
    }

    fun stopAllVideos(itemCount: Int) {
        val rv = getRecyclerView() ?: return
        for (i in 0 until itemCount) {
            (rv.findViewHolderForAdapterPosition(i) as? ImageViewerAdapter.ViewHolder)?.let { holder ->
                stopMotionVideo(holder)
                stopRawVideo(holder)
                stopCinemaDng(holder)
            }
        }
    }

    fun onViewRecycled(holder: ImageViewerAdapter.ViewHolder) {
        stopMotionVideo(holder)
        rawVideoDelegate.release(holder)
        mp4VideoDelegate.release(holder)
        cinemaDngDelegate.release(holder)
    }

    fun onDetachedFromRecyclerView(recyclerView: RecyclerView, itemCount: Int) {
        stopAllVideos(itemCount)
        for (i in 0 until itemCount) {
            (recyclerView.findViewHolderForAdapterPosition(i) as? ImageViewerAdapter.ViewHolder)?.let { holder ->
                holder.filmstripFadeRunnable?.let { holder.binding.filmstripContainer.removeCallbacks(it) }
                holder.filmstripFadeRunnable = null
                rawVideoDelegate.release(holder)
                mp4VideoDelegate.release(holder)
                cinemaDngDelegate.release(holder)
            }
        }
    }
}
