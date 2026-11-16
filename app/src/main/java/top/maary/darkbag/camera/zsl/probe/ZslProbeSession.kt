package top.maary.darkbag.camera.zsl.probe

import top.maary.darkbag.camera.zsl.ZslStaticCapabilities

/**
 * 探测期间的帧观察者端口。
 *
 * 实现方（S1-B 的 `camera/session/zsl/` 适配器）负责把真实的预览结果与 RAW 帧回调
 * 转成这里的三个事件；**回调必须轻量**（只计数/记录，不做拷贝或阻塞 IPC）。
 */
interface ZslProbeFrameObserver {
    /** 预览 repeating 产出一帧（[timestampNs] 为传感器时间戳）。 */
    fun onPreviewResult(timestampNs: Long)

    /** RAW 帧到达（[copyMs] 为单帧拷入 scratch buffer 的耗时，[metadataHit] 表示时长窗内命中元数据）。 */
    fun onRawFrame(timestampNs: Long, copyMs: Long, metadataHit: Boolean)

    /** 相机报告 `onCaptureFailed`。 */
    fun onCaptureFailure()
}

/**
 * 探测所需的相机操作抽象（端口）。
 *
 * S1-A 只定义端口与纯逻辑，不调用任何 Camera2 会话；真实实现放在 S1-B 的适配器中，
 * 以便探测编排可以在无相机的 CI 上完整单测。
 */
interface ZslProbeSession {
    /** 当前镜头/设备的静态能力（探测开始前读取一次）。 */
    val capabilities: ZslStaticCapabilities

    /** 追加 RAW target 并锁定帧率；成功返回 true。 */
    fun beginDualStream(targetFps: Int): Boolean

    /** 还原原始请求并移除 RAW target；必须幂等（异常/中止路径也会调用）。 */
    fun endDualStream(): Boolean

    /** 设置/清除帧观察者（`null` 表示停止回调）。 */
    fun setFrameObserver(observer: ZslProbeFrameObserver?)

    /** 当前热状态（`PowerManager.getCurrentThermalStatus()`）。 */
    fun thermalStatus(): Int

    /** 当前可用内存（`ActivityManager.MemoryInfo.availMem`）。 */
    fun availableMemoryBytes(): Long
}

/**
 * 窗口等待调度端口。
 *
 * 生产实现用 `delay(ms)`；测试注入立即返回的假实现，避免真实等待并保持时序可断言。
 */
interface ZslProbeScheduler {
    suspend fun pause(ms: Long)
}

/**
 * 时钟端口（时间来源可注入，便于测试帧龄/预热/时间戳）。
 */
interface ZslProbeClock {
    fun nowNanos(): Long

    fun currentTimeMillis(): Long
}
