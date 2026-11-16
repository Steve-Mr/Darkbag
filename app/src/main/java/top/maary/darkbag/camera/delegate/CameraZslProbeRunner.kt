package top.maary.darkbag.camera.delegate

import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.os.SystemClock
import android.util.Log
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
import top.maary.darkbag.camera.session.zsl.ZslProbeSystemReadings
import top.maary.darkbag.camera.zsl.RingConfig
import top.maary.darkbag.camera.zsl.ZslStaticCapabilities
import top.maary.darkbag.camera.zsl.probe.ZslProbeClock
import top.maary.darkbag.camera.zsl.probe.ZslProbeController
import top.maary.darkbag.camera.zsl.probe.ZslProbeFailure
import top.maary.darkbag.camera.zsl.probe.ZslProbePreconditionInput
import top.maary.darkbag.camera.zsl.probe.ZslProbePreconditions
import top.maary.darkbag.camera.zsl.probe.ZslProbeReport
import top.maary.darkbag.camera.zsl.probe.ZslProbeReportStore
import top.maary.darkbag.camera.zsl.probe.ZslProbeResult
import top.maary.darkbag.camera.zsl.probe.ZslProbeSample
import top.maary.darkbag.camera.zsl.probe.ZslProbeScheduler
import top.maary.darkbag.camera.zsl.probe.ZslProbeTargetFps
import top.maary.darkbag.camera.zsl.probe.ZslProbeVerdict
import top.maary.darkbag.repository.SettingsRepository
import top.maary.darkbag.utils.CameraRepository

/**
 * 探测执行面的状态机（UI 只读，S1-B3 消费）。
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
 * 预检（S1-B2 R12）：`start()` 的协程先求 [ZslProbePreconditions]，
 * - 环境不满足 → `Finished(Failed(PRECONDITION_FAILED, detail))`，**不构造适配器**、不碰硬件；
 * - 无 RAW 能力 → 直接落一份 `UNSUPPORTED / RAW_UNSUPPORTED / RingConfig.DISABLED` 的全零报告
 *   （S1-B2 R3），同样不构造适配器。
 *
 * @param env 相机环境网关（提供 lifecycleScope / runtimeState / hostContext）
 * @param applyControls 复用既有请求配置（手动 ISO/快门、对焦、OIS、crop）
 * @param restorePreviewRequest 恢复既有预览 repeating；返回**真实成败**（R1）
 * @param reportStore 报告持久化端口（默认写 DataStore，双键原子写）
 */
class CameraZslProbeRunner(
    private val env: CameraEnvironmentHost,
    private val applyControls: (CaptureRequest.Builder) -> Unit,
    private val restorePreviewRequest: () -> Boolean,
    private val reportStore: ZslProbeReportStore =
        ZslProbeReportStore(SettingsRepository.getInstance(env.hostContext))
) {

    private val _state = MutableStateFlow<ZslProbeState>(ZslProbeState.Idle)

    /** 探测状态流；UI（S1-B3）只读。 */
    val state: StateFlow<ZslProbeState> = _state.asStateFlow()

    private var job: Job? = null

    @Volatile
    private var controller: ZslProbeController? = null

    /**
     * 启动一次探测（立即返回：预检、适配器构造、能力读取、7.5 s 窗口全部在后台线程完成）。
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

    /** 后台线程上的完整探测流程：预检 → 能力 → 控制帧率 → 三窗口 → 报告落库。 */
    private suspend fun runProbeOnBackground(): ZslProbeResult {
        // R12：预检在构造适配器之前；不通过就不碰任何硬件 IPC
        val precheck = preconditionInput()
        val blocked = ZslProbePreconditions.evaluate(precheck)
        if (blocked != null) {
            return when (blocked) {
                // R3：设备/镜头没有 RAW_SENSOR → 无需开窗，直接落"能力不支持"报告
                ZslProbeFailure.RAW_UNSUPPORTED -> {
                    val report = unsupportedReport()
                    reportStore.save(report)
                    ZslProbeResult.Completed(report)
                }
                else -> ZslProbeResult.Failed(
                    reason = blocked,
                    detail = ZslProbePreconditions.describe(precheck) ?: blocked.name
                )
            }
        }

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
            // R11：save() 在后台上下文挂起到"报告体 + 版本"单事务落盘完成（不占主线程、不阻塞 UI）
            reportStore.save(result.report)
        }
        return result
    }

    /**
     * 采集预检输入（R12）。会话状态取 `arbiter.camera2Session`；录制同时看 RAW 录制与普通视频保存；
     * 多摄取 `arbiter.isMultiCameraMode`；PiP 取 `runtimeState.isFrontPipActive`；
     * 电量/热/内存复用适配器的同一套系统读数（避免口径漂移）。
     */
    private fun preconditionInput(): ZslProbePreconditionInput {
        val state = env.runtimeState
        val lensId = state.currentLens?.id ?: DEFAULT_LENS_ID
        return ZslProbePreconditionInput(
            sessionActive = env.sessionArbiter.camera2Session != null,
            isRecording = state.rawVideoSessionManager.recording || state.isVideoSaving,
            isMultiCamera = env.sessionArbiter.isMultiCameraMode,
            isFrontPip = state.isFrontPipActive,
            batteryPercent = ZslProbeSystemReadings.batteryPercent(env.hostContext),
            availableMemoryBytes = ZslProbeSystemReadings.availableMemoryBytes(env.hostContext),
            thermalStatus = ZslProbeSystemReadings.thermalStatus(env.hostContext),
            rawSupported = resolveRawSupported(lensId)
        )
    }

    /**
     * 当前镜头的 RAW 能力（S1-B2 复审 F5）：优先读**最新**静态能力，而不是会话绑定时的快照
     * （快照可能在切镜头后过期）。只有读取本身失败才回落到快照——
     * "未知"绝不能落成 `UNSUPPORTED/RAW_UNSUPPORTED` 报告（那会把一次读取故障写成设备不支持 RAW）。
     * 读取失败不重抛：本值只影响预检分流，后续 `beginDualStream` 仍会二次校验真实 reader 格式。
     */
    private fun resolveRawSupported(lensId: String): Boolean {
        val latest = try {
            ZslStaticCapabilities.from(CameraRepository.getCharacteristics(env.camera2Manager, lensId))
        } catch (_: AssertionError) {
            null
        } catch (t: RuntimeException) {
            Log.w(TAG, "Failed to read RAW capability for lens $lensId, falling back to session snapshot", t)
            null
        }
        return latest?.rawSupported ?: env.runtimeState.isRawSupported
    }

    /** 无 RAW 能力时的"全零"报告（R3）：不声称任何指标，只声明不支持。 */
    private fun unsupportedReport(): ZslProbeReport = ZslProbeReport(
        version = ZslProbeReport.SCHEMA_VERSION,
        timestampMs = System.currentTimeMillis(),
        deviceFingerprint = deviceFingerprint(),
        lensId = env.runtimeState.currentLens?.id ?: DEFAULT_LENS_ID,
        rawWidth = 0,
        rawHeight = 0,
        verdict = ZslProbeVerdict.UNSUPPORTED,
        failures = listOf(ZslProbeFailure.RAW_UNSUPPORTED),
        sample = ZslProbeSample.empty(),
        ringConfig = RingConfig.DISABLED
    )

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
        const val TAG = "CameraZslProbeRunner"
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

/**
 * `zsl_probe_pending` 的唯一消费点（S1-B2 R10），与 [CameraZslProbeRunner] 同属触发链。
 *
 * 三个前提按序判定：**已 hydrate** → **pending=true** → 清标志并启动。DataStore 未 hydrate 时
 * `current()` 只是默认快照（pending=false），此时消费等于静默丢弃用户刚点下的探测请求，
 * 因此未 hydrate 时直接返回、不改任何状态，等 `onSettingsHydrated()` 再调一次。
 *
 * 抽成独立单元只为可测：hydration 是存储层属性，注入成谓词后无需在测试里与后台 hydration 竞速。
 */
internal class ZslProbePendingConsumer(
    private val isHydrated: () -> Boolean,
    private val settingsRepository: SettingsRepository,
    private val startProbe: () -> Boolean
) {
    /** @return `true` 表示本次调用消费了 pending 并启动了探测。 */
    fun consume(): Boolean {
        if (!isHydrated()) return false
        if (!settingsRepository.current().zslProbePending) return false
        settingsRepository.setZslProbePending(false)
        startProbe()
        return true
    }
}
