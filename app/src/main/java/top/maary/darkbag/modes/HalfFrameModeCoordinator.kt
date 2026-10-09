package top.maary.darkbag.modes

import top.maary.darkbag.fragments.SettingsFragment
import top.maary.darkbag.models.CaptureTimingMode
import top.maary.darkbag.models.StandardTimingTracker
import top.maary.darkbag.pipeline.sink.HalfFrameCacheSink
import top.maary.darkbag.utils.HalfFrameManager
import top.maary.darkbag.utils.HalfFrameSessionStore
import top.maary.darkbag.utils.ImageUtils
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Random

/**
 * 半格拍摄模式协调器。
 * 遵循半格模式的双帧契约状态机：
 * 帧 1：生成 group baseName，存入 SessionStore，标记 step=1，触发半格 UI 动画与动效，产物定向至本地磁盘暂存；
 * 帧 2：读取帧 1 暂存路径与参数，重置 step=0，触发全图拼接与排版，最终发布入库。
 */
class HalfFrameModeCoordinator(
    override val mode: CaptureMode,
    private val host: ModeExecutionContext
) : CaptureModeCoordinator {

    companion object {
        private const val FILENAME = "yyyyMMdd_HHmmss"
    }

    private val sessionStore = HalfFrameSessionStore(host.context)

    private val profile: String
        get() = if (mode == CaptureMode.HALF_FRAME_TB) {
            HalfFrameSessionStore.PROFILE_HALF_TOP
        } else {
            HalfFrameSessionStore.PROFILE_HALF_SIDE
        }

    override fun onActivated() {
        val layout = if (mode == CaptureMode.HALF_FRAME_TB) {
            SettingsFragment.HALF_FRAME_LAYOUT_TB
        } else {
            SettingsFragment.HALF_FRAME_LAYOUT_SBS
        }
        host.preferences.edit()
            .putString(SettingsFragment.KEY_HALF_FRAME_LAYOUT, layout)
            .putString(SettingsFragment.KEY_ACTIVE_CAPTURE_MODE, mode.key)
            .putBoolean(SettingsFragment.KEY_HALF_FRAME_MODE, true)
            .apply()
        host.updateHalfFrameUi(animate = false)
    }

    override fun onDeactivated() {
        // 模式切换时保留临时文件以便切回继续拍帧 2，但通知 UI 重置
        host.updateHalfFrameUi(animate = false)
    }

    override fun onShutterTriggered(timing: StandardTimingTracker) {
        if (!host.canTriggerCapture()) return

        // 立即截取取景器快照用于转场动效
        host.captureViewFinderSnapshot()

        val prefs = host.preferences
        val currentSession = sessionStore.readSession(profile = profile)
        val step = currentSession.step

        val isFrame1 = step == 0
        val isFrame2 = step == 1

        val hfGroupId = if (isFrame2) {
            currentSession.baseName
        } else {
            ImageUtils.getBaseName(SimpleDateFormat(FILENAME, Locale.US).format(timing.shutterClick))
        }

        var resolvedFlare = -1
        val flarePref = if (prefs.getBoolean(SettingsFragment.KEY_HALF_FRAME_LIGHT_LEAK, false)) 0 else -1
        resolvedFlare = if (flarePref == 0) Random().nextInt(2) + 1 else flarePref

        if (isFrame1) {
            sessionStore.clearProfile(profile)
            sessionStore.setBaseName(hfGroupId, profile = profile)
            sessionStore.markStep(1, timing.shutterClick, profile = profile, flareType = resolvedFlare)
            host.updateHalfFrameUi(animate = true)
            host.showProcessingAnimation()
        }

        val sessionForMetadata = sessionStore.readSession(profile = profile)
        val hfMetadata = HalfFrameManager.Metadata(
            profile = sessionForMetadata.profile,
            dateStamp = prefs.getBoolean(SettingsFragment.KEY_HALF_FRAME_DATE_STAMP, false),
            captureTimeMillis = timing.shutterClick,
            frame1BaseName = if (isFrame2) sessionForMetadata.baseName else null,
            frame1TempPath = if (isFrame2) sessionForMetadata.tempPath else null,
            frame1CaptureTime = if (isFrame2) sessionForMetadata.captureTimeMillis else 0L,
            frame1DigitalGain = if (isFrame2) sessionForMetadata.digitalGain else 1.0f,
            flareType = if (isFrame2) sessionForMetadata.flareType else resolvedFlare
        )

        if (isFrame2) {
            sessionStore.markStep(0, profile = profile)
            host.updateHalfFrameUi(animate = true)
            host.showProcessingAnimation()
            host.clearThumbnailPlaceholder()
        }

        timing.captureMode = CaptureTimingMode.HALF_FRAME
        val sink = HalfFrameCacheSink()
        host.showShutterVisuals()

        if (host.isHdrPlusEnabled && host.isRawSupported) {
            host.triggerHdrPlusBurst(
                sink = sink,
                isFrame1 = isFrame1,
                hfMetadata = hfMetadata,
                timing = timing
            )
        } else {
            host.triggerSinglePicture(
                sink = sink,
                isFrame1 = isFrame1,
                hfMetadata = hfMetadata,
                timing = timing
            )
        }
    }

    override fun onShutterLongPressed(): Boolean {
        val currentSession = sessionStore.readSession(profile = profile)
        if (currentSession.step == 1) {
            sessionStore.clearProfile(profile)
            sessionStore.markStep(0, profile = profile)
            host.updateHalfFrameUi(animate = false)
            return true
        }
        return false
    }

    override fun getEffectiveOrientation(deviceOrientationDegrees: Int): Int {
        val layout = host.preferences.getString(
            SettingsFragment.KEY_HALF_FRAME_LAYOUT,
            SettingsFragment.HALF_FRAME_LAYOUT_SBS
        )
        return if (layout == SettingsFragment.HALF_FRAME_LAYOUT_TB) 270 else 0
    }

    override fun getShutterDotRotation(deviceOrientationDegrees: Int): Float {
        val layout = host.preferences.getString(
            SettingsFragment.KEY_HALF_FRAME_LAYOUT,
            SettingsFragment.HALF_FRAME_LAYOUT_SBS
        )
        return if (layout == SettingsFragment.HALF_FRAME_LAYOUT_TB) {
            90f
        } else {
            0f
        }
    }
}
