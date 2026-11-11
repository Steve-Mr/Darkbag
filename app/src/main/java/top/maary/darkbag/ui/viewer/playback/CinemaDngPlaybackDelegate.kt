package top.maary.darkbag.ui.viewer.playback

import android.net.Uri
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import top.maary.darkbag.R
import top.maary.darkbag.fragments.ImageViewerAdapter
import top.maary.darkbag.models.ImageGroup

/**
 * Handles CinemaDNG 24fps frame stepping playback, filmstrip synchronization,
 * and frame selection.
 */
class CinemaDngPlaybackDelegate(
    private val loadCinemaDngFrame: (ImageViewerAdapter.ViewHolder, ImageGroup, Uri, Int) -> Unit,
    private val onCinemaDngFrameSelected: ((group: ImageGroup, index: Int, uri: Uri?) -> Unit)?,
    private val onPlaybackStateChanged: (position: Int, isPlaying: Boolean, currentMs: Long, totalMs: Long) -> Unit
) {

    fun play(holder: ImageViewerAdapter.ViewHolder, group: ImageGroup) {
        val frames = if (group.cinemaDngFrameUris.isNotEmpty()) group.cinemaDngFrameUris else listOfNotNull(group.cinemaDngFirstFrameUri)
        if (frames.isEmpty()) return

        holder.cinemaDngPlaybackJob?.cancel()
        holder.isPlayingVideo = true
        holder.binding.btnVideoDuration.setIconResource(R.drawable.ic_pause)
        val fps = 24.0f
        val frameDurationMs = (1000f / fps).toLong()
        val totalMs = ((frames.size / fps) * 1000).toLong()

        val pos = holder.bindingAdapterPosition
        val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        holder.cinemaDngPlaybackJob = scope.launch {
            var idx = holder.activeCinemaDngFrameIndex
            if (idx >= frames.size - 1) idx = 0
            while (isActive && idx < frames.size) {
                val uri = frames[idx]
                holder.activeCinemaDngFrameIndex = idx
                holder.activeCinemaDngFrameUri = uri
                loadCinemaDngFrame(holder, group, uri, idx)
                holder.binding.filmstripSeekbar.setProgress(idx, frames.size)
                val curMs = ((idx / fps) * 1000).toLong()
                onCinemaDngFrameSelected?.invoke(group, idx, uri)
                if (pos != RecyclerView.NO_POSITION) {
                    onPlaybackStateChanged(pos, true, curMs, totalMs)
                }
                delay(frameDurationMs)
                idx++
            }
            holder.isPlayingVideo = false
            holder.binding.btnVideoDuration.setIconResource(R.drawable.ic_play_arrow)
            if (pos != RecyclerView.NO_POSITION) {
                onPlaybackStateChanged(pos, false, totalMs, totalMs)
            }
        }
    }

    fun stop(holder: ImageViewerAdapter.ViewHolder, group: ImageGroup?) {
        holder.cinemaDngPlaybackJob?.cancel()
        holder.cinemaDngPlaybackJob = null
        holder.isPlayingVideo = false
        holder.binding.btnVideoDuration.setIconResource(R.drawable.ic_play_arrow)
        val pos = holder.bindingAdapterPosition
        if (pos != RecyclerView.NO_POSITION && group != null) {
            val totalMs = ((group.cinemaDngFrameUris.size / 24.0f) * 1000).toLong()
            val curMs = ((holder.activeCinemaDngFrameIndex / 24.0f) * 1000).toLong()
            onPlaybackStateChanged(pos, false, curMs, totalMs)
        }
    }

    fun selectFrame(
        holder: ImageViewerAdapter.ViewHolder,
        group: ImageGroup,
        frameIndex: Int,
        uri: Uri? = null
    ) {
        val targetUri = uri ?: group.cinemaDngFrameUris.getOrNull(frameIndex) ?: group.cinemaDngFirstFrameUri
        holder.activeCinemaDngFrameIndex = frameIndex
        holder.activeCinemaDngFrameUri = targetUri
        if (targetUri != null) {
            loadCinemaDngFrame(holder, group, targetUri, frameIndex)
        }
        holder.binding.filmstripSeekbar.setProgress(frameIndex, group.cinemaDngFrameUris.size.takeIf { it > 0 } ?: 1)
        onCinemaDngFrameSelected?.invoke(group, frameIndex, targetUri)
    }

    fun release(holder: ImageViewerAdapter.ViewHolder) {
        holder.cinemaDngPlaybackJob?.cancel()
        holder.cinemaDngPlaybackJob = null
        holder.isPlayingVideo = false
    }
}
