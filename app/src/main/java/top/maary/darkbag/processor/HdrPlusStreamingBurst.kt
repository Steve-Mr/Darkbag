package top.maary.darkbag.processor

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import top.maary.darkbag.models.StandardTimingTracker
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors

data class StreamingBurstFrame(
    val width: Int,
    val height: Int,
    val timestampNs: Long,
    val exposureTimeNs: Long,
    val rotationDegrees: Int,
    val physicalId: String? = null,
    var noiseProfileS: Float = 0.0001f,
    var noiseProfileO: Float = 0.00001f
)

data class StreamingBurstResult(
    val sessionHandle: Long,
    val frames: List<StreamingBurstFrame>
)

/**
 * Manages streaming RAW frame capture for HDR+ bursts with O(1) constant memory.
 *
 * Uses a decoupled Producer-Consumer queue:
 * - Producer (`addFrame`): Runs on Camera2 handler thread, quickly copies incoming Bayer data
 *   into a pooled DirectByteBuffer (~2ms), allowing Camera2 hardware images to be closed immediately.
 * - Consumer (`workerJob`): Runs on a dedicated background thread, drives Halide's `hdrplus_accumulate_step`,
 *   and immediately recycles/frees each frame buffer as soon as that step finishes.
 */
class HdrPlusStreamingBurst(
    val sessionHandle: Long,
    private val frameCount: Int,
    private val timing: StandardTimingTracker? = null,
    val cfaPattern: Int = 0,
    private val onBurstComplete: (StreamingBurstResult) -> Unit,
    private val onBurstFailed: ((Throwable) -> Unit)? = null
) {
    companion object {
        private const val TAG = "HdrPlusStreamingBurst"
        const val STEADY_SCORE_THRESHOLD = 25.0f
    }

    private class QueuedFrame(
        val buffer: ByteBuffer,
        val frame: StreamingBurstFrame,
        var isReleased: Boolean = false
    )

    private val lock = Any()
    private val frameChannel = Channel<QueuedFrame>(capacity = maxOf(8, frameCount))

    @Volatile private var enqueuedCount = 0
    @Volatile private var isCompleted = false
    @Volatile private var isAborted = false

    private val processedFrames = mutableListOf<StreamingBurstFrame>()

    private val workerJob: Job = HdrPlusAccumulationDispatcher.scope.launch {
        timing?.accumulateStart = System.currentTimeMillis()
        try {
            if (frameCount <= 1) {
                for (item in frameChannel) {
                    if (isAborted) {
                        StreamingBufferPool.release(item.buffer)
                        continue
                    }

                    try {
                        val success = ColorProcessor.nativePushStreamingFrame(sessionHandle, item.buffer)
                        if (!success) {
                            Log.e(TAG, "Failed to push frame 0 to native session $sessionHandle")
                        }
                        synchronized(lock) {
                            processedFrames.add(item.frame)
                        }
                        Log.d(TAG, "Streaming frame 1/$frameCount accumulated (single frame)")
                    } finally {
                        StreamingBufferPool.release(item.buffer)
                    }

                    if (processedFrames.size == frameCount) {
                        break
                    }
                }
            } else {
                // Multi-frame burst with GCam Adaptive Base Frame Anchoring
                val item0 = frameChannel.receiveCatching().getOrNull()
                if (item0 != null) {
                    if (isAborted) {
                        StreamingBufferPool.release(item0.buffer)
                    } else {
                        val score0 = ColorProcessor.nativeComputeGcamFrameScore(
                            frameBuffer = item0.buffer,
                            width = item0.frame.width,
                            height = item0.frame.height,
                            cfaPattern = cfaPattern,
                            noiseProfileS = item0.frame.noiseProfileS,
                            noiseProfileO = item0.frame.noiseProfileO,
                            exposureTimeNs = item0.frame.exposureTimeNs,
                            timeDeltaFromFirstNs = 0L
                        )
                        Log.d(TAG, "Frame 0 GCam score: $score0 (threshold: $STEADY_SCORE_THRESHOLD)")

                        if (score0 >= STEADY_SCORE_THRESHOLD) {
                            // FAST PATH: Tripod / steady hand detected. Push Frame 0 directly as reference frame.
                            try {
                                val success = ColorProcessor.nativePushStreamingFrame(sessionHandle, item0.buffer)
                                if (!success) {
                                    Log.e(TAG, "Failed to push frame 0 to native session $sessionHandle")
                                }
                                synchronized(lock) {
                                    processedFrames.add(item0.frame)
                                }
                                Log.d(TAG, "Streaming frame 1/$frameCount accumulated (Fast Path steady base frame, score=$score0)")
                            } finally {
                                StreamingBufferPool.release(item0.buffer)
                            }

                            // Stream subsequent frames normally with zero buffering
                            for (item in frameChannel) {
                                if (isAborted) {
                                    StreamingBufferPool.release(item.buffer)
                                    continue
                                }

                                try {
                                    val success = ColorProcessor.nativePushStreamingFrame(sessionHandle, item.buffer)
                                    if (!success) {
                                        Log.e(TAG, "Failed to push frame ${processedFrames.size} to native session $sessionHandle")
                                    }
                                    synchronized(lock) {
                                        processedFrames.add(item.frame)
                                    }
                                    Log.d(TAG, "Streaming frame ${processedFrames.size}/$frameCount accumulated")
                                } finally {
                                    StreamingBufferPool.release(item.buffer)
                                }

                                if (processedFrames.size == frameCount) {
                                    break
                                }
                            }
                        } else {
                            // FALLBACK ADAPTIVE PATH: Button press shake detected on Frame 0.
                            // Buffer up to maxCandidates (Frames 0, 1, 2) and select highest-scoring candidate as reference.
                            val maxCandidates = minOf(3, frameCount)
                            val candidateItems = mutableListOf<QueuedFrame>()
                            val candidateScores = mutableListOf<Float>()

                            fun safeRelease(item: QueuedFrame) {
                                if (!item.isReleased) {
                                    item.isReleased = true
                                    StreamingBufferPool.release(item.buffer)
                                }
                            }

                            candidateItems.add(item0)
                            candidateScores.add(score0)
                            val firstFrameTimestampNs = item0.frame.timestampNs

                            try {
                                while (candidateItems.size < maxCandidates) {
                                    if (isAborted) break
                                    val nextItem = frameChannel.receiveCatching().getOrNull() ?: break
                                    if (isAborted) {
                                        candidateItems.add(nextItem)
                                        break
                                    }
                                    val timeDeltaNs = maxOf(0L, nextItem.frame.timestampNs - firstFrameTimestampNs)
                                    val score = ColorProcessor.nativeComputeGcamFrameScore(
                                        frameBuffer = nextItem.buffer,
                                        width = nextItem.frame.width,
                                        height = nextItem.frame.height,
                                        cfaPattern = cfaPattern,
                                        noiseProfileS = nextItem.frame.noiseProfileS,
                                        noiseProfileO = nextItem.frame.noiseProfileO,
                                        exposureTimeNs = nextItem.frame.exposureTimeNs,
                                        timeDeltaFromFirstNs = timeDeltaNs
                                    )
                                    candidateItems.add(nextItem)
                                    candidateScores.add(score)
                                    Log.d(TAG, "Candidate ${candidateItems.size - 1} score: $score (delta: ${timeDeltaNs / 1_000_000}ms)")
                                }

                                if (!isAborted && candidateItems.isNotEmpty()) {
                                    val bestIdx = candidateScores.indices.maxByOrNull { candidateScores[it] } ?: 0
                                    Log.i(TAG, "Adaptive base frame selected candidate $bestIdx (score ${candidateScores[bestIdx]}) out of ${candidateItems.size} candidates (score0=$score0, threshold=$STEADY_SCORE_THRESHOLD)")

                                    // Push best candidate first (becomes reference frame in HdrPlusStreamingSession)
                                    val bestCand = candidateItems[bestIdx]
                                    try {
                                        val success = ColorProcessor.nativePushStreamingFrame(sessionHandle, bestCand.buffer)
                                        if (!success) {
                                            Log.e(TAG, "Failed to push best candidate frame $bestIdx to native session $sessionHandle")
                                        }
                                        synchronized(lock) {
                                            processedFrames.add(bestCand.frame)
                                        }
                                        Log.d(TAG, "Streaming frame 1/$frameCount accumulated (Adaptive Best Base: candidate $bestIdx)")
                                    } finally {
                                        safeRelease(bestCand)
                                    }

                                    // Push remaining candidates in order
                                    for ((idx, cand) in candidateItems.withIndex()) {
                                        if (idx == bestIdx) continue
                                        if (isAborted) break
                                        try {
                                            val success = ColorProcessor.nativePushStreamingFrame(sessionHandle, cand.buffer)
                                            if (!success) {
                                                Log.e(TAG, "Failed to push candidate frame $idx to native session $sessionHandle")
                                            }
                                            synchronized(lock) {
                                                processedFrames.add(cand.frame)
                                            }
                                            Log.d(TAG, "Streaming frame ${processedFrames.size}/$frameCount accumulated (Candidate $idx)")
                                        } finally {
                                            safeRelease(cand)
                                        }
                                    }
                                }
                            } finally {
                                for (cand in candidateItems) {
                                    safeRelease(cand)
                                }
                            }

                            // Continue streaming any remaining frames normally
                            if (!isAborted && processedFrames.size < frameCount) {
                                for (item in frameChannel) {
                                    if (isAborted) {
                                        StreamingBufferPool.release(item.buffer)
                                        continue
                                    }

                                    try {
                                        val success = ColorProcessor.nativePushStreamingFrame(sessionHandle, item.buffer)
                                        if (!success) {
                                            Log.e(TAG, "Failed to push frame ${processedFrames.size} to native session $sessionHandle")
                                        }
                                        synchronized(lock) {
                                            processedFrames.add(item.frame)
                                        }
                                        Log.d(TAG, "Streaming frame ${processedFrames.size}/$frameCount accumulated")
                                    } finally {
                                        StreamingBufferPool.release(item.buffer)
                                    }

                                    if (processedFrames.size == frameCount) {
                                        break
                                    }
                                }
                            }
                        }
                    }
                }
            }

            val result = synchronized(lock) {
                if (!isAborted && !isCompleted && processedFrames.isNotEmpty()) {
                    isCompleted = true
                    timing?.accumulateDone = System.currentTimeMillis()
                    StreamingBurstResult(sessionHandle, processedFrames.toList())
                } else null
            }
            if (result != null) {
                onBurstComplete(result)
            } else if (!isAborted && !isCompleted) {
                abort()
                onBurstFailed?.invoke(IllegalStateException("No frames were accumulated for session $sessionHandle"))
            }
        } catch (e: CancellationException) {
            Log.d(TAG, "Streaming worker cancelled for session $sessionHandle")
        } catch (e: Throwable) {
            Log.e(TAG, "Error in streaming worker for session $sessionHandle", e)
            abort()
            onBurstFailed?.invoke(e)
        } finally {
            // Drain and return any remaining buffers in frameChannel
            while (true) {
                val item = frameChannel.tryReceive().getOrNull() ?: break
                StreamingBufferPool.release(item.buffer)
            }
        }
    }

    /**
     * Rapidly receives an incoming Camera2 RAW frame, copies it into a pooled DirectByteBuffer (~2ms),
     * enqueues it for background accumulation, and returns immediately so the caller can close the Image.
     */
    fun addFrame(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        timestampNs: Long,
        exposureTimeNs: Long,
        rotationDegrees: Int,
        physicalId: String? = null,
        noiseProfileS: Float = 0.0001f,
        noiseProfileO: Float = 0.00001f
    ): Boolean {
        synchronized(lock) {
            if (isCompleted || isAborted || enqueuedCount >= frameCount) {
                return false
            }

            val rowLength = width * pixelStride
            val dataLength = rowLength * height

            val cleanBuffer = StreamingBufferPool.acquire(dataLength)
            cleanBuffer.position(0)
            cleanBuffer.limit(dataLength)

            if (buffer.isDirect && cleanBuffer.isDirect) {
                ColorProcessor.copyBayerWithStride(
                    buffer, buffer.position(),
                    cleanBuffer, 0,
                    width, height, rowStride, pixelStride
                )
            } else {
                val oldPos = buffer.position()
                val oldLimit = buffer.limit()
                for (y in 0 until height) {
                    val rowStart = y * rowStride
                    if (rowStart + rowLength > buffer.capacity()) break
                    buffer.position(rowStart)
                    buffer.limit(rowStart + rowLength)
                    cleanBuffer.put(buffer)
                }
                buffer.limit(oldLimit)
                buffer.position(oldPos)
            }

            cleanBuffer.position(0)
            cleanBuffer.limit(dataLength)

            val frame = StreamingBurstFrame(
                width = width,
                height = height,
                timestampNs = timestampNs,
                exposureTimeNs = exposureTimeNs,
                rotationDegrees = rotationDegrees,
                physicalId = physicalId,
                noiseProfileS = noiseProfileS,
                noiseProfileO = noiseProfileO
            )

            val sendResult = frameChannel.trySend(QueuedFrame(cleanBuffer, frame))
            if (!sendResult.isSuccess) {
                Log.e(TAG, "Failed to enqueue frame to frameChannel (capacity full or closed)")
                StreamingBufferPool.release(cleanBuffer)
                return false
            }

            enqueuedCount++
            Log.d(TAG, "Streaming frame $enqueuedCount/$frameCount enqueued (fast copy)")

            if (enqueuedCount == frameCount) {
                frameChannel.close()
            }

            return true
        }
    }

    /**
     * Flushes whatever frames have been captured so far.
     * Closes the frame channel so the worker stops waiting for future frames,
     * finishes accumulating what is already enqueued, and invokes onBurstComplete.
     * This is non-blocking and safe to call from the UI thread.
     */
    fun flush() {
        synchronized(lock) {
            if (isCompleted || isAborted) return
            frameChannel.close()
        }
    }

    /**
     * Aborts the streaming session, cancels background accumulation, and releases all resources.
     */
    fun abort() {
        synchronized(lock) {
            if (isAborted) return
            isAborted = true
            isCompleted = true
            frameChannel.close()

            // Drain any pending frames waiting in channel
            while (true) {
                val item = frameChannel.tryReceive().getOrNull() ?: break
                StreamingBufferPool.release(item.buffer)
            }
            workerJob.cancel()

            if (sessionHandle != 0L) {
                try {
                    ColorProcessor.nativeAbortStreamingSession(sessionHandle)
                } catch (e: Throwable) {
                    Log.w(TAG, "Error aborting session $sessionHandle", e)
                }
            }
        }
    }
}
