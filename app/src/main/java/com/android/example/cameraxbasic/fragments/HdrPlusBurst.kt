package com.android.example.cameraxbasic.fragments

import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer

/**
 * Helper class to manage HDR+ burst capture.
 * Stores frames until the desired burst size is reached, then triggers processing.
 */
class HdrPlusBurst(
    private val frameCount: Int,
    private val onBurstComplete: (List<ImageProxy>) -> Unit
) {
    private val frames = mutableListOf<ImageProxy>()

    fun addFrame(image: ImageProxy) {
        if (frames.size < frameCount) {
            frames.add(image) // Note: Caller must NOT close image yet. We hold it.
            if (frames.size == frameCount) {
                onBurstComplete(frames.toList())
                // Frames should be closed by the consumer after processing
                frames.clear()
            }
        } else {
            image.close()
        }
    }

    fun reset() {
        frames.forEach { it.close() }
        frames.clear()
    }
}
