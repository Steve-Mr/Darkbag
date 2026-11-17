package top.maary.darkbag.fragments.settings

import android.content.Context
import android.widget.TextView
import com.google.android.material.color.MaterialColors
import top.maary.darkbag.R
import top.maary.darkbag.camera.zsl.probe.ZslProbeFailure
import top.maary.darkbag.camera.zsl.probe.ZslProbeReport
import top.maary.darkbag.camera.zsl.probe.ZslProbeReportStore
import top.maary.darkbag.camera.zsl.probe.ZslProbeVerdict
import top.maary.darkbag.databinding.FragmentSettingsBinding
import top.maary.darkbag.repository.DarkbagPreferences
import top.maary.darkbag.repository.SettingsRepository
import top.maary.darkbag.ui.ZslProbeReasonStrings

/**
 * 渲染后的托盘三态快照（纯数据，便于无 Robolectric 单测）。
 *
 * [statusArgs] 允许两种载荷：`Int`（帧率/环深度，直接交给 `getString`）与 [ZslProbeFailure]
 * （不支持原因，渲染期经 [ZslProbeReasonStrings] 解析），mapper 因此保持纯函数、不持有 Context。
 */
internal data class ZslProbeTileState(
    val statusRes: Int,
    val statusArgs: List<Any>,
    val badgeRes: Int,
    val switchEnabled: Boolean,
    val switchChecked: Boolean,
    val primaryActionRes: Int
)

/**
 * 报告 + 偏好 → 托盘三态（§3.12.3）。`RECOMMENDED`=支持档、`SUPPORTED`=受限档、
 * `UNSUPPORTED`=禁用开关并给出原因；`zslProbePending` 优先于已有报告（只有一条触发路径）。
 */
internal object ZslProbeTileStateMapper {
    fun map(prefs: DarkbagPreferences, report: ZslProbeReport?): ZslProbeTileState {
        // 探测已排队：开关锁定（不允许第二条触发路径），主按钮变为"取消"（F2）。优先于报告本身。
        if (prefs.zslProbePending) {
            return ZslProbeTileState(
                statusRes = R.string.zsl_state_pending,
                statusArgs = emptyList(),
                badgeRes = R.string.zsl_badge_experimental,
                switchEnabled = false,
                switchChecked = prefs.zslExperimentalEnabled,
                primaryActionRes = R.string.cancel
            )
        }
        if (report == null) {
            return ZslProbeTileState(
                statusRes = R.string.zsl_state_untested,
                statusArgs = emptyList(),
                badgeRes = R.string.zsl_badge_experimental,
                switchEnabled = true,
                switchChecked = false,
                primaryActionRes = R.string.zsl_action_run
            )
        }
        val ring = report.ringConfig
        return when (report.verdict) {
            ZslProbeVerdict.RECOMMENDED -> ZslProbeTileState(
                statusRes = R.string.zsl_state_supported,
                statusArgs = listOf(ring.targetFps, ring.depth),
                badgeRes = R.string.zsl_probe_badge_supported,
                switchEnabled = true,
                switchChecked = prefs.zslExperimentalEnabled,
                primaryActionRes = R.string.zsl_action_retest
            )

            ZslProbeVerdict.SUPPORTED -> ZslProbeTileState(
                statusRes = R.string.zsl_state_limited,
                statusArgs = listOf(ring.targetFps, ring.depth),
                badgeRes = R.string.zsl_probe_badge_limited,
                switchEnabled = true,
                switchChecked = prefs.zslExperimentalEnabled,
                primaryActionRes = R.string.zsl_action_retest
            )

            ZslProbeVerdict.UNSUPPORTED -> ZslProbeTileState(
                statusRes = R.string.zsl_state_unsupported,
                statusArgs = listOf(report.failures.firstOrNull() ?: ZslProbeFailure.RAW_UNSUPPORTED),
                badgeRes = R.string.zsl_probe_badge_unsupported,
                // 能力不支持时开关禁用：不静默启用一个必然无效的开关（§3.12.3）
                switchEnabled = false,
                switchChecked = false,
                primaryActionRes = R.string.zsl_action_retest
            )
        }
    }
}

/**
 * 设置页 ZSL 托盘（`card_tray_zsl`）绑定层：读报告 → 应用 [ZslProbeTileState] → 把用户动作转成
 * 回调；持久化一律由 Fragment 完成。`isRendering` 守卫防"程序写控件"被误当成"用户改偏好"。
 */
class SettingsZslDelegate(
    private val context: Context,
    private val reportStore: ZslProbeReportStore = ZslProbeReportStore(SettingsRepository.getInstance(context))
) {
    var isRendering: Boolean = false
        private set

    /** 点击 `Run test` / 确认后启动探测（Fragment 负责写 `zslProbePending` 并返回相机页）。 */
    var onRequestProbe: () -> Unit = {}

    /** 无报告时点击开关：先弹"先检测"确认，确认后才走 [onRequestProbe]（§3.12.3）。 */
    var onConfirmProbe: ((onConfirmed: () -> Unit) -> Unit) = { it() }

    /** pending 态点击"取消"：由 Fragment 清除 `zslProbePending`（F2）。 */
    var onCancelProbe: () -> Unit = {}

    /** 点击 `Details`（仅在有有效报告时可用）。 */
    var onShowDetails: (ZslProbeReport) -> Unit = {}

    /** 用户在有效报告下切换开关（Fragment 负责 `setZslExperimentalEnabled`）。 */
    var onToggle: (Boolean) -> Unit = {}

    private var lastReport: ZslProbeReport? = null
    private var lastPending: Boolean = false
    private var listenersAttached: Boolean = false

    fun render(binding: FragmentSettingsBinding, prefs: DarkbagPreferences) {
        val report = reportStore.load()
        val state = ZslProbeTileStateMapper.map(prefs, report)
        lastReport = report
        lastPending = prefs.zslProbePending
        isRendering = true
        try {
            binding.txtZslStateValue.text = context.getString(state.statusRes, *resolveArgs(state.statusArgs))
            applyBadge(binding.txtZslTrayBadge, state.badgeRes)
            binding.switchZslEnabled.isEnabled = state.switchEnabled
            binding.switchZslEnabled.isChecked = state.switchChecked
            binding.btnZslRun.setText(state.primaryActionRes)
            binding.btnZslDetails.isEnabled = report != null
        } finally {
            isRendering = false
        }
        if (!listenersAttached) {
            listenersAttached = true
            attachListeners(binding)
        }
    }

    private fun attachListeners(binding: FragmentSettingsBinding) {
        binding.switchZslEnabled.setOnCheckedChangeListener { _, checked ->
            if (isRendering) return@setOnCheckedChangeListener
            val report = lastReport
            if (report != null && !lastPending) {
                onToggle(checked)
            } else if (checked) {
                // 无报告 / 探测排队中：回弹到已持久化的值，绝不静默启用未经检测的能力。
                // 只在"被点亮"时处理：回弹本身会再回调一次 checked=false，不做区分会重复触发检测。
                binding.switchZslEnabled.isChecked = false
                // pending 时开关已锁定，这里再守卫一次：不得产生第二条触发路径（F1）
                if (report == null && !lastPending) onConfirmProbe { onRequestProbe() }
            }
        }
        // pending 态主按钮是"取消"，其余是"运行/重新检测"
        binding.btnZslRun.setOnClickListener { if (lastPending) onCancelProbe() else onRequestProbe() }
        binding.btnZslDetails.setOnClickListener { lastReport?.let(onShowDetails) }
    }

    /** 失败码在渲染期解析成本地化短句，数字原样交给 `getString`。 */
    private fun resolveArgs(args: List<Any>): Array<Any> = args.map { arg ->
        if (arg is ZslProbeFailure) context.getString(ZslProbeReasonStrings.resId(arg)) else arg
    }.toTypedArray()

    /** 徽标随状态换色：支持=primary、受限=secondary、其余 tertiary。 */
    private fun applyBadge(view: TextView, badgeRes: Int) {
        view.setText(badgeRes)
        val (background, colorAttr) = when (badgeRes) {
            R.string.zsl_probe_badge_supported ->
                R.drawable.bg_bento_badge_primary to android.R.attr.colorPrimary
            R.string.zsl_probe_badge_limited ->
                R.drawable.bg_bento_badge_secondary to com.google.android.material.R.attr.colorSecondary
            else -> R.drawable.bg_bento_badge_tertiary to com.google.android.material.R.attr.colorTertiary
        }
        view.setBackgroundResource(background)
        view.setTextColor(MaterialColors.getColor(view, colorAttr, view.currentTextColor))
    }
}
