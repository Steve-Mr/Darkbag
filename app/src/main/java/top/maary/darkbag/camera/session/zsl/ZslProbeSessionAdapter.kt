package top.maary.darkbag.camera.session.zsl

import android.app.ActivityManager
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import top.maary.darkbag.camera.delegate.CameraEnvironmentHost
import top.maary.darkbag.camera.dispatch.CameraCaptureMetadataExtractor
import top.maary.darkbag.camera.session.PreviewRequestPlan
import top.maary.darkbag.camera.zsl.ZslStaticCapabilities
import top.maary.darkbag.camera.zsl.probe.ZslProbeFrameObserver
import top.maary.darkbag.camera.zsl.probe.ZslProbeSession
import top.maary.darkbag.processor.ColorProcessor
import top.maary.darkbag.utils.CameraRepository
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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
 * @param restorePreviewRequest 恢复既有预览 repeating 的入口（由 hub 注入，内部投递到 camera2Handler）
 */
class ZslProbeSessionAdapter(
    private val env: CameraEnvironmentHost,
    private val applyControls: (CaptureRequest.Builder) -> Unit,
    private val restorePreviewRequest: () -> Unit
) : ZslProbeSession {

    @Volatile private var observer: ZslProbeFrameObserver? = null
    @Volatile private var begun = false

    /** 单次分配、所有帧复用的拷贝目标（RAW16 即 2 字节/像素）。 */
    @Volatile private var scratchBuffer: ByteBuffer? = null

    /** 元数据命中判定复用既有提取器（5 ms 容差），不重复实现时间戳匹配。 */
    private val metadataExtractor by lazy { CameraCaptureMetadataExtractor(env) }
    private val lensId: String
        get() = env.runtimeState.currentLens?.id ?: DEFAULT_LENS_ID

    override val capabilities: ZslStaticCapabilities by lazy {
        ZslStaticCapabilities.from(
            CameraRepository.getCharacteristics(env.camera2Manager, lensId)
        )
    }

    override fun setFrameObserver(observer: ZslProbeFrameObserver?) {
        this.observer = observer
    }

    override fun beginDualStream(targetFps: Int): Boolean {
        if (begun) return true
        val fps = targetFps.coerceAtLeast(1)
        val arbiter = env.sessionArbiter
        val device = arbiter.camera2Device ?: return false
        val session = arbiter.camera2Session ?: return false
        val handler = arbiter.camera2Handler ?: return false
        val previewSurface = arbiter.camera2PreviewSurface ?: return false
        val rawReader = arbiter.rawImageReader ?: return false
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

        val started = runBlockingOnHandler(handler, BEGIN_TIMEOUT_MS) {
            // 先挂 RAW 监听再提交 repeating：避免双流首帧落在"无监听"的空窗里
            rawReader.setOnImageAvailableListener({ reader -> handleRawImage(reader) }, rawHandler)
            val request = arbiter.previewRequestFactory.build(device, plan) { builder ->
                applyControls(builder)
                builder.set(CaptureRequest.SENSOR_FRAME_DURATION, frameDurationNs)
                // AE 关闭（手动曝光）时 AE_TARGET_FPS_RANGE 无意义，只靠 SENSOR_FRAME_DURATION 控速
                if (!env.runtimeState.isManualExposure) {
                    fpsRange?.let { builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                }
            }
            session.setRepeatingRequest(request, captureCallback(), handler)
            scratchBuffer = scratch
            begun = true
            true
        }
        if (!started) {
            scratchBuffer = null
            runCatching { rawReader.setOnImageAvailableListener(null, null) }
        }
        return started
    }

    override fun endDualStream(): Boolean {
        var ok = true
        try {
            env.sessionArbiter.rawImageReader?.setOnImageAvailableListener(null, null)
            val handler = env.sessionArbiter.camera2Handler
            if (handler == null) {
                restorePreviewRequest()
            } else {
                // 在 camera2Handler 上调用并等待，保证"移除 RAW target"先于/同步于恢复请求
                ok = runBlockingOnHandler(handler, END_TIMEOUT_MS) {
                    restorePreviewRequest()
                    true
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to end probe dual stream", t)
            ok = false
        } finally {
            // scratch 在监听注销之后才释放，避免释放瞬间仍有帧拷入
            scratchBuffer = null
            begun = false
        }
        return ok
    }

    /**
     * 热状态：API 29+ 读 [PowerManager.getCurrentThermalStatus]，其余/异常返回 `null`（未知）。
     * 未知必须与 `THERMAL_STATUS_NONE(0)` 区分，否则热读失败会被乐观判为 RECOMMENDED。
     */
    override fun thermalStatus(): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            val manager = env.hostContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
            manager?.currentThermalStatus
        } catch (_: AssertionError) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }

    override fun availableMemoryBytes(): Long = try {
        val activityManager =
            env.hostContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return 0L
        ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }.availMem
    } catch (_: AssertionError) {
        0L
    } catch (_: RuntimeException) {
        0L
    }

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

    /** 探测期间的预览回调：只喂有界元数据缓存（metadataHit 依赖）+ 上报帧事件，不改 UI 遥测。 */
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

    /** RAW 帧：acquire → 计时拷贝 → 元数据命中断言 → 关闭 → 上报。 */
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
            val copyMs = (SystemClock.elapsedRealtimeNanos() - copyStartedNs) / 1_000_000L
            val metadataHit = metadataExtractor.findCaptureResultSync(image.timestamp) != null
            observer?.onRawFrame(image.timestamp, copyMs, metadataHit)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to consume RAW probe frame", t)
        } finally {
            image.close()
        }
    }

    /**
     * 在 [handler] 上执行并同步等待结果；已在该 Handler 的线程上时直接执行（避免自死锁）。
     * 投递失败 / 等待超时 / 块内异常 → `false`。
     */
    private fun runBlockingOnHandler(handler: Handler, timeoutMs: Long, block: () -> Boolean): Boolean {
        if (Looper.myLooper() === handler.looper) {
            return try {
                block()
            } catch (t: Throwable) {
                Log.e(TAG, "Probe request block failed on handler thread", t)
                false
            }
        }
        val outcome = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        val posted = handler.post {
            try {
                outcome.set(block())
            } catch (t: Throwable) {
                Log.e(TAG, "Probe request block failed", t)
            } finally {
                latch.countDown()
            }
        }
        if (!posted) return false
        val finished = try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        return finished && outcome.get()
    }

    private companion object {
        const val TAG = "ZslProbeSession"
        const val DEFAULT_LENS_ID = "0"
        const val BYTES_PER_RAW_PIXEL = 2L
        const val BEGIN_TIMEOUT_MS = 2000L
        const val END_TIMEOUT_MS = 2000L
    }
}
