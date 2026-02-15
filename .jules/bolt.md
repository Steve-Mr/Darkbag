# BOLT'S JOURNAL - CRITICAL LEARNINGS ONLY

## 2025-05-14 - [Luminosity Analysis Optimization]
**Learning:** High-frequency per-frame operations (like `ImageAnalysis.Analyzer`) in Android are extremely sensitive to allocations. Using idiomatic Kotlin like `data.map { ... }.average()` on a 640x480 pixel array results in over 300,000 boxed `Integer` allocations per frame, leading to massive GC pressure (~140MB/s at 30fps).
**Action:** Always use direct `ByteBuffer` iteration or bulk `get`/`put` operations for per-frame image processing. Avoid `toByteArray()` and functional collection transformations in hot paths.

## 2025-05-14 - [Bulk ByteBuffer Operations]
**Learning:** `ByteBuffer.get(dst: ByteArray, offset: Int, length: Int)` and `ByteBuffer.put(src: ByteBuffer)` (with limit adjustments) are significantly more efficient than manual row copying with intermediate temporary arrays.
**Action:** Use bulk operations and temporary limit/position adjustments to copy data between buffers with different strides/padding.
