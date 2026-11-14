package top.maary.darkbag.ui.viewer.filmstrip

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.net.Uri
import android.view.View
import top.maary.darkbag.fragments.ImageViewerAdapter
import top.maary.darkbag.models.ImageGroup

/**
 * 胶片卷带与帧选择指示器委托类 (ViewerFilmstripDelegate).
 * 封装 CinemaDNG 序列帧胶片条底栏的帧列表加载、滑动拖拽监听 (onScrubStateChanged)、自动淡出调度以及可见性控制。
 */
class ViewerFilmstripDelegate {

    fun bindFilmstrip(
        holder: ImageViewerAdapter.ViewHolder,
        group: ImageGroup,
        isUiVisible: Boolean,
        isFormatSwitcherPersistentHidden: Boolean,
        onFrameSelected: (frameIndex: Int, uri: Uri?) -> Unit
    ) {
        val isCinemaDngGroup = group.isCinemaDng || group.cinemaDngFolderUri != null || group.cinemaDngFirstFrameUri != null || group.cinemaDngFrameUris.isNotEmpty()
        val shouldShow = isUiVisible && !isFormatSwitcherPersistentHidden

        if (!isCinemaDngGroup) {
            cancelFadeAnimation(holder)
            holder.binding.filmstripContainer.visibility = View.GONE
            holder.activeCinemaDngFrameIndex = 0
            holder.activeCinemaDngFrameUri = null
            return
        }

        holder.binding.filmstripContainer.visibility = if (shouldShow) View.VISIBLE else View.GONE
        holder.binding.filmstripContainer.alpha = if (shouldShow) 0.35f else 0f
        holder.binding.filmstripSeekbar.alpha = 1.0f

        val frameUris = if (group.cinemaDngFrameUris.isNotEmpty()) {
            group.cinemaDngFrameUris
        } else if (group.cinemaDngFirstFrameUri != null) {
            listOf(group.cinemaDngFirstFrameUri)
        } else {
            emptyList()
        }

        val currentActiveIndex = holder.activeCinemaDngFrameIndex.coerceIn(0, (frameUris.size - 1).coerceAtLeast(0))
        val currentActiveUri = frameUris.getOrNull(currentActiveIndex)
        holder.activeCinemaDngFrameIndex = currentActiveIndex
        holder.activeCinemaDngFrameUri = currentActiveUri

        holder.binding.filmstripSeekbar.setFrames(frameUris, currentActiveIndex)

        holder.binding.filmstripSeekbar.onScrubStateChanged = { isScrubbing ->
            cancelFadeAnimation(holder)
            if (isScrubbing) {
                holder.binding.filmstripContainer.animate().alpha(1.0f).setDuration(150).start()
            } else {
                val fadeRunnable = Runnable {
                    if (isUiVisible && !isFormatSwitcherPersistentHidden) {
                        holder.binding.filmstripContainer.animate().alpha(0.35f).setDuration(400).start()
                    }
                }
                holder.filmstripFadeRunnable = fadeRunnable
                holder.binding.filmstripContainer.postDelayed(fadeRunnable, 3000)
            }
        }

        holder.binding.filmstripSeekbar.onFrameSelected = { frameIndex, uri ->
            holder.activeCinemaDngFrameIndex = frameIndex
            holder.activeCinemaDngFrameUri = uri
            onFrameSelected(frameIndex, uri)
        }
    }

    fun updateFilmstripVisibility(
        holder: ImageViewerAdapter.ViewHolder,
        group: ImageGroup,
        isVisible: Boolean,
        isPersistentHidden: Boolean
    ) {
        val isCinemaDngGroup = group.isCinemaDng || group.cinemaDngFolderUri != null || group.cinemaDngFirstFrameUri != null || group.cinemaDngFrameUris.isNotEmpty()
        if (!isCinemaDngGroup) return

        val shouldBeVisible = isVisible && !isPersistentHidden
        if (shouldBeVisible) {
            holder.binding.filmstripContainer.visibility = View.VISIBLE
            holder.binding.filmstripContainer.animate().alpha(0.35f).setDuration(200).setListener(null).start()
        } else {
            cancelFadeAnimation(holder)
            holder.binding.filmstripContainer.animate().alpha(0f).setDuration(200)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (!isVisible || isPersistentHidden) holder.binding.filmstripContainer.visibility = View.GONE
                    }
                }).start()
        }
    }

    fun cancelFadeAnimation(holder: ImageViewerAdapter.ViewHolder) {
        holder.filmstripFadeRunnable?.let { holder.binding.filmstripContainer.removeCallbacks(it) }
        holder.filmstripFadeRunnable = null
    }

    fun onViewRecycled(holder: ImageViewerAdapter.ViewHolder) {
        cancelFadeAnimation(holder)
        holder.activeCinemaDngFrameIndex = 0
        holder.activeCinemaDngFrameUri = null
    }
}
