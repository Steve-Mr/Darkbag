package top.maary.darkbag.camera.session

import android.os.Handler
import android.os.HandlerThread
import android.util.Log

/**
 * 独立的 RAW 帧回调线程（线程名 `ZslRawThread`）。
 *
 * 迁移前 RAW `ImageReader` 的 `onImageAvailable` 与预览 `CaptureResult` 共用
 * `camera2Handler`（`Camera2Background`），单帧 24 MB 的 `copyBayerWithStride` 会挤占
 * 预览回调。本类把 RAW 回调搬到独立线程，预览回调线程保持不变。
 */
class RawFrameThread {
    companion object {
        private const val TAG = "RawFrameThread"
        private const val THREAD_NAME = "ZslRawThread"
        private const val JOIN_TIMEOUT_MS = 1000L
    }

    @Volatile private var thread: HandlerThread? = null
    @Volatile private var workerHandler: Handler? = null

    /** 当前 RAW 回调 Handler；未启动时为 null。 */
    val handler: Handler?
        get() = workerHandler

    /**
     * 启动 RAW 回调线程（幂等：已启动时直接返回现有 Handler）。
     *
     * @return 新线程上的 Handler；重复调用返回同一个 Handler。
     */
    @Synchronized
    fun start(): Handler? {
        thread?.let { existing ->
            if (existing.isAlive) {
                workerHandler?.let { return it }
            }
        }
        stop()
        val newThread = HandlerThread(THREAD_NAME).apply { start() }
        val newHandler = Handler(newThread.looper)
        thread = newThread
        workerHandler = newHandler
        Log.i(TAG, "RAW frame thread started: $THREAD_NAME")
        return newHandler
    }

    /** 停止 RAW 回调线程；可重复调用（未启动时为空操作）。 */
    @Synchronized
    fun stop() {
        val exiting = thread
        thread = null
        workerHandler = null
        if (exiting == null) return
        exiting.quitSafely()
        if (Thread.currentThread() === exiting) {
            // 从 RAW 线程自身关闭时不能自 join（否则会白等 JOIN_TIMEOUT_MS）
            return
        }
        try {
            exiting.join(JOIN_TIMEOUT_MS)
            Log.i(TAG, "RAW frame thread stopped: $THREAD_NAME")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Log.w(TAG, "Interrupted while joining $THREAD_NAME", e)
        }
    }
}
