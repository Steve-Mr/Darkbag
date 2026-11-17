package top.maary.darkbag.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import top.maary.darkbag.R
import top.maary.darkbag.camera.delegate.CameraZslProbeRunner
import top.maary.darkbag.camera.delegate.ZslProbeState
import top.maary.darkbag.camera.zsl.probe.ZslProbeFailure
import top.maary.darkbag.camera.zsl.probe.ZslProbeReport
import top.maary.darkbag.camera.zsl.probe.ZslProbeResult
import top.maary.darkbag.camera.zsl.probe.ZslProbeSample
import top.maary.darkbag.camera.zsl.probe.ZslProbeVerdict
import top.maary.darkbag.databinding.BottomSheetZslProbeBinding
import top.maary.darkbag.databinding.ItemZslMetricRowBinding

/** ZSL 探测执行面 sheet（§3.12.4）：LIVE 跟随 [CameraZslProbeRunner.state]；关闭且仍在运行 = 中止（§3.12.2）。 */
class ZslProbeBottomSheet : BottomSheetDialogFragment() {
    enum class Mode { LIVE, RESULT, DETAILS }

    private var _binding: BottomSheetZslProbeBinding? = null
    private val binding get() = _binding!!
    private val ctx: Context get() = binding.root.context
    private val rows = ArrayList<ItemZslMetricRowBinding>(METRIC_LABELS.size)
    private var mode = Mode.RESULT
    private var report: ZslProbeReport? = null
    private var result: ZslProbeResult? = null
    private var runner: CameraZslProbeRunner? = null
    private var stageJob: Job? = null
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        _binding = BottomSheetZslProbeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViews(binding)
        binding.tvSheetTitle.setText(R.string.zsl_probe_title)
        when (mode) {
            Mode.LIVE -> observeRunner()
            Mode.RESULT -> bindResult()
            Mode.DETAILS -> report?.let { bindReport(it, canRetest = false) }
        }
        binding.btnZslPrimary.setOnClickListener { onPrimary() }
        binding.btnZslSecondary.setOnClickListener { onSecondary() }
        binding.btnZslTertiary.setOnClickListener { dismiss() }
    }

    internal fun bindViews(binding: BottomSheetZslProbeBinding) {
        _binding = binding
        val container = binding.containerZslMetrics
        container.removeAllViews()
        rows.clear()
        val inflater = LayoutInflater.from(container.context)
        METRIC_LABELS.forEach { labelRes ->
            val row = ItemZslMetricRowBinding.inflate(inflater, container, false)
            row.txtMetricLabel.setText(labelRes)
            rows.add(row)
            container.addView(row.root)
        }
    }

    internal fun bindRunning() {
        result = null
        report = null
        applyBadge(R.string.zsl_probe_badge_running)
        setButtons(R.string.cancel, null, null)
        bindMetrics(null)
    }

    /** 结论文案 + 六行指标；`canRetest=false` 是 DETAILS（只看已存报告，主按钮 Done）。 */
    internal fun bindReport(report: ZslProbeReport, canRetest: Boolean = true) {
        this.report = report
        val ring = report.ringConfig
        when (report.verdict) {
            ZslProbeVerdict.RECOMMENDED ->
                setVerdict(R.string.zsl_probe_badge_supported, R.string.zsl_state_supported, ring.targetFps, ring.depth)
            ZslProbeVerdict.SUPPORTED ->
                setVerdict(R.string.zsl_probe_badge_limited, R.string.zsl_state_limited, ring.targetFps, ring.depth)
            ZslProbeVerdict.UNSUPPORTED -> setVerdict(
                R.string.zsl_probe_badge_unsupported,
                R.string.zsl_state_unsupported,
                ctx.getString(ZslProbeReasonStrings.resId(report.failures.firstOrNull() ?: ZslProbeFailure.RAW_UNSUPPORTED))
            )
        }
        bindMetrics(report.sample)
        setButtons(
            if (canRetest) R.string.zsl_action_retest else R.string.zsl_action_done,
            R.string.zsl_action_copy_report,
            if (canRetest) R.string.zsl_action_done else null
        )
    }

    private fun setVerdict(badgeRes: Int, statusRes: Int, vararg args: Any) {
        applyBadge(badgeRes)
        binding.txtZslProgress.text = ctx.getString(statusRes, *args)
    }
    /** 测试缝：直接绑定期望的终态结果（LIVE 结论面），避免依赖真实 runner 状态流。 */
    internal fun bindFinished(finished: ZslProbeResult) {
        result = finished
        bindResult()
    }

    /** 结论面：Completed → Retest + Copy + Done；Failed → Retest + Done；Aborted → Done（显示"已取消"）。 */
    private fun bindResult() {
        when (val finished = result) {
            is ZslProbeResult.Completed -> bindReport(finished.report)
            is ZslProbeResult.Failed -> {
                report = null
                applyBadge(R.string.zsl_probe_badge_unsupported)
                binding.txtZslProgress.text = ctx.getString(ZslProbeReasonStrings.resId(finished.reason))
                bindMetrics(null)
                setButtons(R.string.zsl_action_retest, null, R.string.zsl_action_done)
            }
            ZslProbeResult.Aborted -> {
                report = null
                applyBadge(R.string.zsl_probe_badge_cancelled)
                binding.txtZslProgress.text = ctx.getString(R.string.zsl_state_aborted)
                bindMetrics(null)
                setButtons(R.string.zsl_action_done, null, null)
            }
            null -> Unit
        }
    }
    /** 三个动作位：结论面 Retest + Copy + Done，进行中只有 Cancel，DETAILS 是 Done + Copy。 */
    private fun setButtons(primaryRes: Int, secondaryRes: Int?, tertiaryRes: Int?) {
        binding.btnZslPrimary.setText(primaryRes)
        listOf(binding.btnZslSecondary to secondaryRes, binding.btnZslTertiary to tertiaryRes)
            .forEach { (view, textRes) ->
                view.visibility = if (textRes == null) View.GONE else View.VISIBLE
                textRes?.let { view.setText(it) }
            }
    }
    private fun bindMetrics(sample: ZslProbeSample?) {
        val unknown = ctx.getString(R.string.zsl_metric_unknown)
        val texts = sample?.let {
            listOf(
                "%.1f fps".format(it.rawFps), "%.1f ms".format(it.gapP95Ms), "%.1f ms".format(it.ageP95Ms),
                if (it.copyThroughputMeasured) "%.0f MiB/s".format(it.copyThroughputP5MiBps) else unknown,
                "%.0f%%".format(it.previewDropRatio * 100f),
                it.thermalStatusAfter?.toString() ?: unknown
            )
        } ?: List(METRIC_LABELS.size) { unknown }
        rows.forEachIndexed { index, row -> row.txtMetricValue.text = texts[index] }
    }
    private fun applyBadge(badgeRes: Int) {
        val badge = binding.tvSheetBadge
        val (background, colorAttr) = when (badgeRes) {
            R.string.zsl_probe_badge_supported ->
                R.drawable.bg_bento_badge_primary to android.R.attr.colorPrimary
            R.string.zsl_probe_badge_limited ->
                R.drawable.bg_bento_badge_secondary to com.google.android.material.R.attr.colorSecondary
            else -> R.drawable.bg_bento_badge_tertiary to com.google.android.material.R.attr.colorTertiary
        }
        badge.setText(badgeRes)
        badge.visibility = View.VISIBLE
        badge.setBackgroundResource(background)
        badge.setTextColor(MaterialColors.getColor(badge, colorAttr, badge.currentTextColor))
    }
    private fun observeRunner() {
        val activeRunner = runner ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                activeRunner.state.collect { state ->
                    when (state) {
                        ZslProbeState.Idle -> {
                            // 结论已被消费 / 已中止：LIVE sheet 自行关闭，避免残留陈旧界面（F4）
                            if (zslProbeActionFor(state) == ZslProbeAction.DISMISS) dismissAllowingStateLoss()
                        }
                        is ZslProbeState.Running -> {
                            bindRunning()
                            startStageTicker()
                        }

                        is ZslProbeState.Finished -> {
                            stageJob?.cancel()
                            result = state.result
                            bindResult()
                        }
                    }
                }
            }
        }
    }
    private fun startStageTicker() {
        stageJob?.cancel()
        val startedAt = SystemClock.elapsedRealtime()
        stageJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                val stage = ctx.getString(zslStageRes(SystemClock.elapsedRealtime() - startedAt))
                binding.txtZslProgress.text = ctx.getString(R.string.zsl_probe_progress, stage)
                delay(STAGE_TICK_MS)
            }
        }
    }
    private fun onPrimary() {
        if (mode == Mode.LIVE && runner?.state?.value is ZslProbeState.Running) {
            runner?.abort()
            dismiss()
            return
        }
        // Retest：结论面（Completed/Failed）关掉后重新开一轮；Aborted 与 DETAILS 只做 Done
        val canRetest = mode != Mode.DETAILS &&
            result != null && result != ZslProbeResult.Aborted
        dismiss()
        if (canRetest) runner?.start()
    }

    private fun onSecondary() {
        val report = report ?: return
        copyReport(report, requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
    }
    internal fun copyReport(report: ZslProbeReport, clipboard: ClipboardManager?) {
        clipboard?.setPrimaryClip(ClipData.newPlainText(ctx.getString(R.string.zsl_probe_title), report.toJson()))
    }

    override fun onDismiss(dialog: DialogInterface) {
        stageJob?.cancel()
        when (zslProbeActionFor(runner?.state?.value)) {
            ZslProbeAction.ABORT -> runner?.abort()
            ZslProbeAction.CONSUME -> runner?.consumeFinished()
            ZslProbeAction.DISMISS, ZslProbeAction.NONE -> Unit
        }
        super.onDismiss(dialog)
    }

    override fun onDestroyView() {
        stageJob?.cancel()
        rows.clear()
        _binding = null
        super.onDestroyView()
    }

    companion object {
        const val TAG = "zsl_probe_sheet"
        private const val STAGE_TICK_MS = 200L
        private val METRIC_LABELS = intArrayOf(
            R.string.zsl_metric_raw_fps, R.string.zsl_metric_gap_p95, R.string.zsl_metric_age_p95,
            R.string.zsl_metric_copy_throughput, R.string.zsl_metric_preview_drop, R.string.zsl_metric_thermal
        )

        fun showLive(fm: FragmentManager, runner: CameraZslProbeRunner) = show(fm, Mode.LIVE, runner = runner)

        fun showResult(fm: FragmentManager, result: ZslProbeResult, runner: CameraZslProbeRunner?) =
            show(fm, Mode.RESULT, result = result, runner = runner)

        fun showDetails(fm: FragmentManager, report: ZslProbeReport) = show(fm, Mode.DETAILS, report = report)

        /** 同一 tag 只允许一个实例，避免状态流重复弹栈。 */
        private fun show(
            fm: FragmentManager, mode: Mode, result: ZslProbeResult? = null,
            report: ZslProbeReport? = null, runner: CameraZslProbeRunner? = null
        ) {
            if (fm.findFragmentByTag(TAG) != null) return
            ZslProbeBottomSheet().apply {
                this.mode = mode
                this.result = result
                this.report = report
                this.runner = runner
            }.show(fm, TAG)
        }
    }
}
