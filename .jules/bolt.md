# BOLT'S JOURNAL - CRITICAL LEARNINGS ONLY

## 2025-05-14 - [Luminosity Analysis Optimization]
**Learning:** High-frequency per-frame operations (like `ImageAnalysis.Analyzer`) in Android are extremely sensitive to allocations. Using idiomatic Kotlin like `data.map { ... }.average()` on a 640x480 pixel array results in over 300,000 boxed `Integer` allocations per frame, leading to massive GC pressure (~140MB/s at 30fps).
**Action:** Always use direct `ByteBuffer` iteration or bulk `get`/`put` operations for per-frame image processing. Avoid `toByteArray()` and functional collection transformations in hot paths.

## 2025-05-14 - [Bulk ByteBuffer Operations]
**Learning:** `ByteBuffer.get(dst: ByteArray, offset: Int, length: Int)` and `ByteBuffer.put(src: ByteBuffer)` (with limit adjustments) are significantly more efficient than manual row copying with intermediate temporary arrays.
**Action:** Use bulk operations and temporary limit/position adjustments to copy data between buffers with different strides/padding.
## 2024-05-14 - [Replace notifyDataSetChanged with DiffUtil in LutManagementAdapter]
**Learning:** Using `notifyDataSetChanged()` in RecyclerView adapters causes inefficient full-list redraws, which can negatively impact performance even for relatively small lists like imported LUTs.
**Action:** When updating a RecyclerView adapter's dataset where items can be uniquely identified and compared for content changes, prefer using `DiffUtil` to dispatch granular update events (insertions, removals, changes). In this case, absolute file paths and active LUT status were used as identifying criteria in `LutManagementFragment.kt`.

## 2026-06-18 - [JNI Thread Thrashing with OpenMP and std::async]
**Learning:** Launching heavy CPU-bound tasks via `std::async(std::launch::async)` (like encoding DNGs) concurrently with highly parallelized OpenMP pixel processing loops inside a single JNI call severely degrades overall performance on Android CPUs due to thread thrashing and cache contention.
**Action:** Always serialize heavy native tasks or use `std::thread(...).detach()` with deep copied data to completely offload asynchronous work without blocking the primary Kotlin/JNI return path. Memory overhead (e.g., copying a 16-bit pixel array) is often a necessary trade-off to ensure immediate UI responsiveness.

## 2026-06-18 - [libtiff Data Race via detached threads]
**Learning:** `TIFFSetTagExtender` modifies global library state (`_TIFFextender`). If called concurrently without a global lock from detached threads, this causes critical data races, leading to undefined behavior or crashes when writing multiple DNG/TIFF files.
**Action:** When using third-party global-state C-libraries in multithreaded pipelines (like libtiff), ensure modifications to global configuration pointers are strictly synchronized using `std::mutex`.
