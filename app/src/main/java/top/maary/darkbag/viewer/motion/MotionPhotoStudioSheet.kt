package top.maary.darkbag.viewer.motion

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.launch
import top.maary.darkbag.R
import top.maary.darkbag.databinding.BottomSheetMotionStudioBinding
import top.maary.darkbag.models.ImageGroup

/**
 * Bento BottomSheet for picking & exporting still frames (JPG with EXIF)
 * and saving/sharing the embedded MP4 from a Motion Photo.
 */
class MotionPhotoStudioSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetMotionStudioBinding? = null
    private val binding get() = _binding!!

    private var player: ExoPlayer? = null
    private var prepared: MotionPhotoExportHelper.PreparedMotionVideo? = null
    private var filmstripThumbnails: List<Bitmap> = emptyList()
    private var currentTimeUs: Long = 0L
    private var isExporting: Boolean = false

    companion object {
        const val TAG = "MotionPhotoStudioSheet"
        const val RESULT_KEY = "motion_photo_studio_result"
        const val RESULT_SAVED_URI = "saved_uri"
        private const val ARG_GROUP = "arg_group"
        private const val FRAME_STEP_US = 33_333L

        fun newInstance(group: ImageGroup): MotionPhotoStudioSheet {
            return MotionPhotoStudioSheet().apply {
                arguments = Bundle().apply {
                    putParcelable(ARG_GROUP, group)
                }
            }
        }

        fun show(fragmentManager: FragmentManager, group: ImageGroup) {
            newInstance(group).show(fragmentManager, TAG)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetMotionStudioBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        dialog?.window?.let { window ->
            WindowCompat.setDecorFitsSystemWindows(window, false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
            }
        }
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, nav.bottom)
            insets
        }

        val group: ImageGroup? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arguments?.getParcelable(ARG_GROUP, ImageGroup::class.java)
        } else {
            @Suppress("DEPRECATION")
            arguments?.getParcelable(ARG_GROUP)
        }
        if (group == null) {
            dismiss()
            return
        }

        setupControls(group)
        loadMotionMedia(group)
    }

    private fun setupControls(group: ImageGroup) {
        binding.btnPlayPause.setOnClickListener {
            val p = player ?: return@setOnClickListener
            if (p.isPlaying) {
                p.pause()
            } else {
                if (p.playbackState == Player.STATE_ENDED) {
                    p.seekTo(0L)
                }
                p.play()
            }
            updatePlayPauseIcon()
        }

        binding.filmstripScrubber.onScrubStartListener = {
            player?.pause()
            updatePlayPauseIcon()
        }
        binding.filmstripScrubber.onScrubListener = { fraction, fromUser ->
            val prep = prepared
            if (prep != null && fromUser) {
                currentTimeUs = (prep.durationUs * fraction).toLong().coerceIn(0L, prep.durationUs)
                player?.seekTo(currentTimeUs / 1000L)
                updateTimeBadge()
            }
        }
        binding.btnStepBack.setOnClickListener { stepBy(-FRAME_STEP_US) }
        binding.btnStepForward.setOnClickListener { stepBy(FRAME_STEP_US) }
        binding.btnJumpShutter.setOnClickListener {
            val prep = prepared ?: return@setOnClickListener
            player?.pause()
            updatePlayPauseIcon()
            seekToTimeUs(prep.stillPtsUs)
        }

        binding.btnSaveFrame.setOnClickListener { handleFrameExport(group, share = false) }
        binding.btnShareFrame.setOnClickListener { handleFrameExport(group, share = true) }
        binding.btnSaveVideo.setOnClickListener { handleVideoExport(group, share = false) }
        binding.btnShareVideo.setOnClickListener { handleVideoExport(group, share = true) }
    }

    private fun loadMotionMedia(group: ImageGroup) {
        val ctx = context ?: return
        binding.loadingIndicator.visibility = View.VISIBLE
        setExportButtonsEnabled(false)

        viewLifecycleOwner.lifecycleScope.launch {
            val prep = MotionPhotoExportHelper.prepareMotionVideo(ctx.applicationContext, group)
            if (_binding == null || !isAdded) return@launch
            if (prep == null) {
                Toast.makeText(ctx, R.string.motion_studio_load_failed, Toast.LENGTH_SHORT).show()
                dismiss()
                return@launch
            }
            prepared = prep
            currentTimeUs = prep.stillPtsUs

            val keyframeFrac = MotionFilmstripScrubberView.computeKeyframeFraction(prep.stillPtsUs, prep.durationUs)
            binding.filmstripScrubber.setKeyframeFraction(keyframeFrac)
            binding.filmstripScrubber.setProgressFraction(keyframeFrac)
            updateTimeBadge()

            setupExoPlayer(prep)
            setExportButtonsEnabled(true)

            val thumbs = MotionPhotoExportHelper.extractFilmstripThumbnails(prep.videoFile, prep.durationUs, count = 12)
            if (_binding == null || !isAdded) {
                thumbs.forEach { it.recycle() }
                return@launch
            }
            filmstripThumbnails = thumbs
            binding.filmstripScrubber.setThumbnails(thumbs)
            binding.loadingIndicator.visibility = View.GONE
        }
    }

    private fun setupExoPlayer(prep: MotionPhotoExportHelper.PreparedMotionVideo) {
        val ctx = context ?: return
        val exo = ExoPlayer.Builder(ctx).build().apply {
            setVideoTextureView(binding.texturePreview)
            setMediaItem(MediaItem.fromUri(Uri.fromFile(prep.videoFile)))
            repeatMode = Player.REPEAT_MODE_OFF
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (_binding != null) updatePlayPauseIcon()
                }
            })
            prepare()
            seekTo(prep.stillPtsUs / 1000L)
            pause()
        }
        player = exo
        updatePlayPauseIcon()
    }

    private fun stepBy(deltaUs: Long) {
        val prep = prepared ?: return
        player?.pause()
        updatePlayPauseIcon()
        seekToTimeUs((currentTimeUs + deltaUs).coerceIn(0L, prep.durationUs))
    }

    private fun seekToTimeUs(timeUs: Long) {
        val prep = prepared ?: return
        currentTimeUs = timeUs.coerceIn(0L, prep.durationUs)
        val frac = (currentTimeUs.toFloat() / prep.durationUs.coerceAtLeast(1L).toFloat()).coerceIn(0f, 1f)
        binding.filmstripScrubber.setProgressFraction(frac)
        player?.seekTo(currentTimeUs / 1000L)
        updateTimeBadge()
    }

    private fun updateTimeBadge() {
        val prep = prepared ?: return
        val rel = MotionPhotoExportHelper.formatRelativeTime(currentTimeUs, prep.stillPtsUs)
        val isShutter = rel == "0.00s"
        binding.tvTimeBadge.text = if (isShutter) "★ $rel" else rel
    }

    private fun updatePlayPauseIcon() {
        val playing = player?.isPlaying == true
        binding.btnPlayPause.setIconResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play_arrow)
    }

    private fun setExportButtonsEnabled(enabled: Boolean) {
        binding.btnSaveFrame.isEnabled = enabled
        binding.btnShareFrame.isEnabled = enabled
        binding.btnSaveVideo.isEnabled = enabled
        binding.btnShareVideo.isEnabled = enabled
    }

    private fun handleFrameExport(group: ImageGroup, share: Boolean) {
        val prep = prepared ?: return
        val ctx = context ?: return
        if (isExporting) return
        isExporting = true
        setExportButtonsEnabled(false)
        binding.loadingIndicator.visibility = View.VISIBLE

        viewLifecycleOwner.lifecycleScope.launch {
            val frame = MotionPhotoExportHelper.extractFrameAt(prep.videoFile, currentTimeUs)
            if (frame == null) {
                finishExportWithToast(R.string.motion_studio_export_failed)
                return@launch
            }
            val timeMs = currentTimeUs / 1000L
            try {
                if (share) {
                    val shareUri = MotionPhotoExportHelper.createShareableFrameUri(
                        ctx.applicationContext, frame, prep.sourceJpgUri, group.baseName, timeMs
                    )
                    if (shareUri != null && isAdded) {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "image/jpeg"
                            putExtra(Intent.EXTRA_STREAM, shareUri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        startActivity(Intent.createChooser(intent, getString(R.string.motion_studio_share_title)))
                    } else {
                        Toast.makeText(ctx, R.string.motion_studio_export_failed, Toast.LENGTH_SHORT).show()
                    }
                } else {
                    val savedUri = MotionPhotoExportHelper.saveFrameToGallery(
                        ctx.applicationContext, frame, prep.sourceJpgUri, group.baseName, timeMs
                    )
                    if (savedUri != null) {
                        setFragmentResult(RESULT_KEY, Bundle().apply { putString(RESULT_SAVED_URI, savedUri.toString()) })
                        Toast.makeText(ctx, R.string.motion_studio_frame_saved, Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(ctx, R.string.motion_studio_export_failed, Toast.LENGTH_SHORT).show()
                    }
                }
            } finally {
                frame.recycle()
                if (_binding != null && isAdded) {
                    isExporting = false
                    setExportButtonsEnabled(true)
                    binding.loadingIndicator.visibility = View.GONE
                }
            }
        }
    }

    private fun handleVideoExport(group: ImageGroup, share: Boolean) {
        val prep = prepared ?: return
        val ctx = context ?: return
        if (isExporting) return
        isExporting = true
        setExportButtonsEnabled(false)
        binding.loadingIndicator.visibility = View.VISIBLE

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                if (share) {
                    val shareUri = MotionPhotoExportHelper.createShareableMp4Uri(
                        ctx.applicationContext, prep.videoFile, group.baseName
                    )
                    if (shareUri != null && isAdded) {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "video/mp4"
                            putExtra(Intent.EXTRA_STREAM, shareUri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        startActivity(Intent.createChooser(intent, getString(R.string.motion_studio_share_title)))
                    } else {
                        Toast.makeText(ctx, R.string.motion_studio_export_failed, Toast.LENGTH_SHORT).show()
                    }
                } else {
                    val savedUri = MotionPhotoExportHelper.saveMp4ToGallery(
                        ctx.applicationContext, prep.videoFile, group.baseName
                    )
                    if (savedUri != null) {
                        setFragmentResult(RESULT_KEY, Bundle().apply { putString(RESULT_SAVED_URI, savedUri.toString()) })
                        Toast.makeText(ctx, R.string.motion_studio_video_saved, Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(ctx, R.string.motion_studio_export_failed, Toast.LENGTH_SHORT).show()
                    }
                }
            } finally {
                if (_binding != null && isAdded) {
                    isExporting = false
                    setExportButtonsEnabled(true)
                    binding.loadingIndicator.visibility = View.GONE
                }
            }
        }
    }

    private fun finishExportWithToast(msgRes: Int) {
        if (_binding != null && isAdded) {
            Toast.makeText(requireContext(), msgRes, Toast.LENGTH_SHORT).show()
            isExporting = false
            setExportButtonsEnabled(true)
            binding.loadingIndicator.visibility = View.GONE
        }
    }

    override fun onDestroyView() {
        player?.release()
        player = null
        filmstripThumbnails.forEach { if (!it.isRecycled) it.recycle() }
        filmstripThumbnails = emptyList()
        super.onDestroyView()
        _binding = null
    }
}
