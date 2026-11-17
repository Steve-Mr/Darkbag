package top.maary.darkbag.camera.session.zsl

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import top.maary.darkbag.camera.delegate.CameraEnvironmentHost
import top.maary.darkbag.camera.dispatch.CameraCaptureMetadataExtractor
import top.maary.darkbag.camera.session.PreviewRequestPlan
import top.maary.darkbag.camera.zsl.ZslStaticCapabilities
import top.maary.darkbag.camera.zsl.probe.ZslMetadataBinding
import top.maary.darkbag.camera.zsl.probe.ZslProbeFrameObserver
import top.maary.darkbag.camera.zsl.probe.ZslProbeSession
import top.maary.darkbag.processor.ColorProcessor
import top.maary.darkbag.utils.CameraRepository
import java.nio.ByteBuffer

/**
 * [ZslProbeSession] 的 Camera2 实现（S1-B1 §4.1）：**只**做"追加/移除 RAW target + 锁定帧率 +
 * 量拷贝耗时 + 上报帧事件"，复用 [PreviewRequestPlan] 与 S0 的单一请求构造点，不复刻会话创建。
 *
 * 所有硬件 IPC 都在 camera2Handler / rawFrameHandler 上执行（Invariant 4），调用方（探测协程，
 * 位于后台线程）只等待结果；UI 进度由 `CameraZslProbeRunner` 的状态流提供。
 * 帧龄口径：RAW `Image.timestamp` 与 [SystemClock.elapsedRealtimeNanos] 同源。
 *
 * @param env 相机环境网关（提供 arbiter / runtimeState / CameraManager）
 * @param applyControls 复用的既有请求配置（手动 ISO/快门、对焦、OIS、crop 等）
 * @param restorePreviewRequest 恢复既有预览 repeating 的入口（由 hub 注入；内部在 camera2Handler
 *   上**同步**构建并提交，返回真实成败，见 S1-B2 R1）
 */
class ZslProbeSessionAdapter(
    private val env: CameraEnvironmentHost,
    private val applyControls: (CaptureRequest.Builder) -> Unit,
    private val restorePreviewRequest: () -> Boolean
) : ZslProbeSession {

    @Volatile private var observer: ZslProbeFrameObserver? = null
    @Volatile private var begun = false

    /** 单次分配、所有帧复用的拷贝目标（RAW16 即 2 字节/像素）。 */
    @Volatile private var scratchBuffer: ByteBuffer? = null

    /** 元数据绑定回查复用既有提取器（5 ms 容差），不重复实现时间戳匹配。 */
    private val metadataExtractor by lazy { CameraCaptureMetadataExtractor(env) }

    /** W0 基线预览计数（生产回调只把结果发到 captureResultFlow，见 ZslProbeMeasurementsTap）。 */
    private val previewTap = ZslProbePreviewTap(
        env.runtimeState.captureResultFlow,
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
    ) { begun }
    private val lensId: String
        get() = env.runtimeState.currentLens?.id ?: DEFAULT_LENS_ID

    override val capabilities: ZslStaticCapabilities by lazy {
        ZslStaticCapabilities.from(
            CameraRepository.getCharacteristics(env.camera2Manager, lensId)
        )
    }

    override fun setFrameObserver(observer: ZslProbeFrameObserver?) {
        this.observer = observer
        if (observer == null) previewTap.stop() else previewTap.start(observer)
    }

    override fun beginDualStream(targetFps: Int): Boolean {
        if (begun) return true
        val rawReader = env.sessionArbiter.rawImageReader ?: return false
        // R3/F1：会话里挂的必须是真 RAW_SENSOR reader——`reader.imageFormat == ImageFormat.RAW_SENSOR`
        // （即 getImageFormat()）自 API 19 起可用，因此**无条件**校验、无 SDK 版本分支；
        // 仅当格式读取本身抛异常时才回落到静态能力快照（见 ZslProbeRawReader.kt）。
        if (!isRawSensorReader(rawReader) { rawSupportedFallback() }) {
            Log.w(TAG, "RAW reader is not a RAW_SENSOR reader, skip dual stream")
            return false
        }
        val fps = targetFps.coerceAtLeast(1)
        val arbiter = env.sessionArbiter
        val device = arbiter.camera2Device ?: return false
        val session = arbiter.camera2Session ?: return false
        val handler = arbiter.camera2Handler ?: return false
        val previewSurface = arbiter.camera2PreviewSurface ?: return false
        val rawSurface = rawReader.surface ?: return false

        val scratch = allocateScratch(rawReader) ?: return false
        val frameDurationNs = 1_000_000_000L / fps
        val fpsRange = resolveFpsRange(fps)
        val plan = PreviewRequestPlan.resolve(
            previewSurface = previewSurface,
            analysisSurface = arbiter.analysisImageReader?.surface,
            attachAnalysis = shouldAttachAnalysis(),
            extraTargets = listOf(rawSurface)
        )
        val rawHandler = arbiter.rawFrameHandler ?: handler

        // R4：本次启动的放弃闸门——等待超时后，**迟到**的 block 不得再提交双流请求
        val gate = ZslProbeBeginGate()
        val started = ZslProbeHandlerDispatch.runBlocking(
            handler = handler,
            timeoutMs = BEGIN_TIMEOUT_MS,
            onTimeout = { gate.abandon() }
        ) {
            if (gate.isAbandoned) return@runBlocking false
            // R2：先发布 scratch 与 begun，再挂监听，最后提交 repeating。
            // 顺序反了会让双流首帧落在"监听已挂、scratch 还是 null"的空窗里，首帧拷贝被静默跳过。
            scratchBuffer = scratch
            begun = true
            // 再挂 RAW 监听：避免双流首帧落在"无监听"的空窗里
            rawReader.setOnImageAvailableListener({ reader -> handleRawImage(reader) }, rawHandler)
            val request = arbiter.previewRequestFactory.build(device, plan) { builder ->
                applyControls(builder)
                builder.set(CaptureRequest.SENSOR_FRAME_DURATION, frameDurationNs)
                // AE 关闭（手动曝光）时 AE_TARGET_FPS_RANGE 无意义，只靠 SENSOR_FRAME_DURATION 控速
                if (!env.runtimeState.isManualExposure) {
                    fpsRange?.let { builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                }
            }
            if (gate.isAbandoned) {
                // 已被放弃：撤销本次注册与状态，绝不再 setRepeatingRequest（R4）
                abandonBegin(rawReader)
                return@runBlocking false
            }
            session.setRepeatingRequest(request, captureCallback(), handler)
            true
        }
        if (!started) {
            scratchBuffer = null
            runCatching { rawReader.setOnImageAvailableListener(null, null) }
            // R4：超时路径下 block 可能已经把 begun 置 true（请求可能已被改写），补偿还原，
            // 否则探测协程会带着"RAW 监听已挂但预览请求未提交"的孤儿状态继续跑
            gate.compensate(alreadyBegun = begun) { endDualStream() }
        }
        return started
    }

    override fun endDualStream(): Boolean {
        var ok = false
        try {
            // 顺序（S1-B2 R1）：① 先注销 RAW 监听（不再有帧进入 scratch）
            // ② 再在 camera2Handler 上同步恢复预览 repeating 并拿到**真实成效**
            // ③ 最后释放 scratch（finally），避免释放瞬间仍有帧拷入
            //
            // F6：注销监听本身可能抛异常（reader 已 close / 厂商实现异常），但**绝不能**因此跳过
            // 恢复——监听注销失败仍必须尝试还原 repeating，所以单独 runCatching 包住（与 abandonBegin 一致）。
            runCatching { env.sessionArbiter.rawImageReader?.setOnImageAvailableListener(null, null) }
                .onFailure { Log.w(TAG, "Failed to detach RAW listener during restore", it) }
            val handler = env.sessionArbiter.camera2Handler
            ok = if (handler == null) {
                // handler 已销毁（会话关闭）：直接在调用线程尝试恢复
                restorePreviewRequest()
            } else {
                ZslProbeHandlerDispatch.runBlocking(handler, END_TIMEOUT_MS) { restorePreviewRequest() }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to end probe dual stream", t)
            ok = false
        } finally {
            scratchBuffer = null
            begun = false
        }
        return ok
    }

    /** 放弃晚到 block 的收口：撤销监听与双流本地状态，不碰 repeating（从未提交）。(R4) */
    private fun abandonBegin(rawReader: ImageReader) {
        runCatching { rawReader.setOnImageAvailableListener(null, null) }
        scratchBuffer = null
        begun = false
    }

    /**
     * 静态能力兜底：**仅**在 `ImageReader.getImageFormat()` 读取本身抛异常时使用
     * （会话绑定时的能力快照与 reader 创建条件是同一来源，见 [isRawSensorReader]）。
     */
    private fun rawSupportedFallback(): Boolean = try {
        capabilities.rawSupported
    } catch (_: AssertionError) {
        false
    } catch (_: RuntimeException) {
        false
    }

    /**
     * 热状态：API 29+ 读 `PowerManager.getCurrentThermalStatus`，其余/异常返回 `null`（未知）。
     * 未知必须与 `THERMAL_STATUS_NONE(0)` 区分，否则热读失败会被乐观判为 RECOMMENDED。
     */
    override fun thermalStatus(): Int? = ZslProbeSystemReadings.thermalStatus(env.hostContext)

    override fun availableMemoryBytes(): Long =
        ZslProbeSystemReadings.availableMemoryBytes(env.hostContext)

    /** 分配单帧 scratch（宽×高×2）；尺寸非法返回 `null`。 */
    private fun allocateScratch(reader: ImageReader): ByteBuffer? {
        val bytes = reader.width.toLong() * reader.height.toLong() * BYTES_PER_RAW_PIXEL
        if (bytes <= 0L || bytes > Int.MAX_VALUE) return null
        return ByteBuffer.allocateDirect(bytes.toInt())
    }

    /**
     * 在可用 `CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES` 中挑包含 [fps] 的区间。
     * 优先精确 `[fps, fps]`，其次包含该帧率的固定/变帧率区间；无匹配则返回 `null`（不改请求）。
     */
    private fun resolveFpsRange(fps: Int): android.util.Range<Int>? {
        val ranges = try {
            CameraRepository.getCharacteristics(env.camera2Manager, lensId)
                .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
        } catch (_: AssertionError) {
            null
        } catch (_: RuntimeException) {
            null
        } ?: return null
        return ranges.firstOrNull { it.lower == fps && it.upper == fps }
            ?: ranges.firstOrNull { it.lower <= fps && fps <= it.upper }
    }

    /** 与 `CameraHardwareStreamController.shouldAttachAnalysis` 同一条规则（主摄 + 手动曝光或 HDR+）。 */
    private fun shouldAttachAnalysis(): Boolean {
        val lens = env.runtimeState.currentLens
        val isMainLens = lens == null || (lens.multiplier?.let { it in 0.95f..1.05f } ?: false)
        return PreviewRequestPlan.shouldAttachAnalysis(
            env.runtimeState.isManualExposure,
            env.isHdrPlusEnabled,
            isMainLens
        )
    }

    /** W1 双流期间的预览回调：喂有界元数据缓存（W2 后回查用）+ 直接上报预览帧。 */
    private fun captureCallback() = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            val timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
            env.runtimeState.captureResults[timestamp] = result
            observer?.onPreviewResult(timestamp)
        }

        override fun onCaptureFailed(
            session: CameraCaptureSession,
            request: CaptureRequest,
            failure: CaptureFailure
        ) {
            observer?.onCaptureFailure()
        }
    }

    /** RAW 帧：acquire → 计时拷贝 → 关闭 → 上报（元数据绑定在 W2 之后统一回查）。 */
    private fun handleRawImage(reader: ImageReader) {
        val image = try {
            reader.acquireNextImage()
        } catch (t: Throwable) {
            Log.e(TAG, "acquireNextImage failed", t)
            null
        } ?: return
        try {
            val plane = image.planes[0]
            val copyStartedNs = SystemClock.elapsedRealtimeNanos()
            scratchBuffer?.let { scratch ->
                try {
                    ColorProcessor.copyBayerWithStride(
                        plane.buffer, plane.buffer.position(),
                        scratch, 0,
                        image.width, image.height, plane.rowStride, plane.pixelStride
                    )
                } catch (t: Throwable) {
                    Log.w(TAG, "copyBayerWithStride failed", t)
                }
            }
            val copyNanos = SystemClock.elapsedRealtimeNanos() - copyStartedNs
            observer?.onRawFrame(image.timestamp, copyNanos)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to consume RAW probe frame", t)
        } finally {
            image.close()
        }
    }

    /** W2 settle 之后统一回查（调用方已留 1000 ms 让晚到的结果落地，见 ZslProbeMetadataBinder）。 */
    override fun metadataBinding(timestampsNs: List<Long>): ZslMetadataBinding =
        ZslProbeMetadataBinder.bind(timestampsNs) { metadataExtractor.findCaptureResultSync(it) != null }

    private companion object {
        const val TAG = "ZslProbeSession"
        const val DEFAULT_LENS_ID = "0"
        const val BYTES_PER_RAW_PIXEL = 2L
        const val BEGIN_TIMEOUT_MS = 2000L
        const val END_TIMEOUT_MS = 2000L
    }
}
