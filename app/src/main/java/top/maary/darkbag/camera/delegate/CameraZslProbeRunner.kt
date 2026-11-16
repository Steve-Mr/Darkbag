package top.maary.darkbag.camera.delegate

import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.maary.darkbag.camera.session.zsl.ZslProbeSessionAdapter
import top.maary.darkbag.camera.zsl.probe.ZslProbeClock
import top.maary.darkbag.camera.zsl.probe.ZslProbeController
import top.maary.darkbag.camera.zsl.probe.ZslProbeFailure
import top.maary.darkbag.camera.zsl.probe.ZslProbeReport
import top.maary.darkbag.camera.zsl.probe.ZslProbeReportStore
import top.maary.darkbag.camera.zsl.probe.ZslProbeResult
import top.maary.darkbag.camera.zsl.probe.ZslProbeScheduler
import top.maary.darkbag.camera.zsl.probe.ZslProbeTargetFps
import top.maary.darkbag.repository.SettingsRepository

/**
 * 探测执行面的状态机（UI 只读，S1-B2 消费）。
 *
 * - [Idle]：从未运行 / 已中止（中止不保留结论，状态回"未检测"）；
 * - [Running]：正在运行，携带本次选择的 [Running.targetFps]（供 UI 显示进度口径）；
 * - [Finished]：终态结论（Completed / Failed）。
 */
sealed interface ZslProbeState {
    data object Idle : ZslProbeState

    data class Running(val targetFps: Int) : ZslProbeState

    data class Finished(val result: ZslProbeResult) : ZslProbeState
}

/**
 * ZSL 兼容性探测的编排 + 状态流 + 报告落库（S1-B1 §4.2，无 UI）。
 *
 * 依赖方向：本类位于 `camera/delegate`，只向上依赖 `camera/zsl`（探测核心）与 `camera/session`
 * （Camera2 适配器），**不** import 任何界面层类型（Fragment / ViewModel / DataBinding）；
 * 触发链的唯一入口是 [start]（由 `CameraControllerHub.onSessionConfigured()` 在消费
 * `zslProbePending` 后调用），因此设置页 ↔ 相机页之间没有第二套隐式路径。
 *
 * 线程：协程挂在 [env] 的 lifecycle scope 上，但**探测主体整体切到 [Dispatchers.Default]**。
 * 适配器的 `beginDualStream` / `endDualStream` 需要在相机 Handler 上提交请求并同步等待结果
 * （正常数十毫秒、超时上限 2 s），这类阻塞式等待绝不能出现在主线程（Invariant 4：
 * 零 UI 线程阻塞）；`_state.value` 从后台线程更新 `MutableStateFlow` 是线程安全的。
 *
 * @param env 相机环境网关（提供 lifecycleScope / runtimeState / hostContext）
 * @param applyControls 复用既有请求配置（手动 ISO/快门、对焦、OIS、crop）
 * @param restorePreviewRequest 恢复既有预览 repeating（由 hub 注入，投递到 camera2Handler）
 * @param reportStore 报告持久化端口（默认写 DataStore）
 */
class CameraZslProbeRunner(
    private val env: CameraEnvironmentHost,
    private val applyControls: (CaptureRequest.Builder) -> Unit,
    private val restorePreviewRequest: () -> Unit,
    private val reportStore: ZslProbeReportStore =
        ZslProbeReportStore(SettingsRepository.getInstance(env.hostContext))
) {

    private val _state = MutableStateFlow<ZslProbeState>(ZslProbeState.Idle)

    /** 探测状态流；UI（S1-B2）只读。 */
    val state: StateFlow<ZslProbeState> = _state.asStateFlow()

    private var job: Job? = null

    @Volatile
    private var controller: ZslProbeController? = null

    /**
     * 启动一次探测（立即返回：适配器构造、能力读取、7.5 s 窗口全部在后台线程完成）。
     *
     * @return `false` 表示已在运行（本次请求被忽略），`true` 表示已受理
     */
    fun start(): Boolean {
        if (job?.isActive == true) return false
        if (_state.value is ZslProbeState.Running) return false
        job = env.lifecycleScope.launch {
            val result = try {
                withContext(Dispatchers.Default) { runProbeOnBackground() }
            } catch (ce: CancellationException) {
                // 中止：不保留结论，状态回"未检测"（§3.12.2），取消原样上抛
                _state.value = ZslProbeState.Idle
                throw ce
            } catch (t: Throwable) {
                ZslProbeResult.Failed(
                    reason = ZslProbeFailure.CAPTURE_FAILED,
                    detail = t.message ?: t.javaClass.simpleName
                )
            }
            _state.value = ZslProbeState.Finished(result)
        }
        return true
    }

    /** 后台线程上的完整探测流程：能力 → 控制帧率 → 三窗口 → 报告落库。 */
    private suspend fun runProbeOnBackground(): ZslProbeResult {
        val adapter = ZslProbeSessionAdapter(env, applyControls, restorePreviewRequest)
        val targetFps = ZslProbeTargetFps.choose(readMaxRawFrameRate(adapter))
        _state.value = ZslProbeState.Running(targetFps)

        val probeController = ZslProbeController(
            session = adapter,
            scheduler = SystemProbeScheduler,
            clock = SystemProbeClock,
            deviceFingerprint = deviceFingerprint(),
            lensId = env.runtimeState.currentLens?.id ?: DEFAULT_LENS_ID
        )
        controller = probeController
        val result = try {
            probeController.run(targetFps)
        } finally {
            controller = null
        }

        if (result is ZslProbeResult.Completed) {
            // save() 只做内存快照 + 入队异步落盘（DataStore 写在 IO 线程），此处已在后台上下文
            reportStore.save(result.report)
        }
        return result
    }

    /**
     * 中止探测（UI 关闭 sheet / 探测期间来电切后台 / 切模式）。
     *
     * 两步都要做：先置控制器中止标志（分片等待会在 ≤100 ms 内退出并完成请求还原），
     * 再取消协程兜底任何卡在 IPC 上的路径（S1-B1 F3：取消不能只靠标志）。
     */
    fun abort() {
        controller?.abort()
        job?.cancel()
    }

    /** 最近一次落库的报告；无报告或已失效（解码失败）时返回 `null`。 */
    fun lastReport(): ZslProbeReport? = reportStore.load()

    /** 静态能力读取是 binder IPC，异常一律视为未知（交给 [ZslProbeTargetFps] 兜底 15 fps）。 */
    private fun readMaxRawFrameRate(adapter: ZslProbeSessionAdapter): Float? =
        try {
            adapter.capabilities.maxRawFrameRate
        } catch (_: AssertionError) {
            null
        } catch (_: RuntimeException) {
            null
        }

    private fun deviceFingerprint(): String =
        "${Build.MANUFACTURER}/${Build.MODEL}/${Build.VERSION.SDK_INT}"

    private companion object {
        const val DEFAULT_LENS_ID = "0"
    }
}

/** 生产调度器：真实挂起等待（Controller 负责分片与中止检查）。 */
private object SystemProbeScheduler : ZslProbeScheduler {
    override suspend fun pause(ms: Long) {
        delay(ms)
    }
}

/** 生产时钟：单调时基与 `SENSOR_TIMESTAMP` 同源（`elapsedRealtimeNanos`），墙钟用于报告时间戳。 */
private object SystemProbeClock : ZslProbeClock {
    override fun nowNanos(): Long = SystemClock.elapsedRealtimeNanos()

    override fun currentTimeMillis(): Long = System.currentTimeMillis()
}
