# HDR+ Pipeline Optimization Analysis

## 1. Denoise Stage Optimization
Current `Denoise` stage takes ~2.8s, which is the most significant bottleneck.

### Current Implementation
- **Bilateral Filter**: 7x7 spatial kernel on Chroma (U/V) channels. Exponential weight calculation per pixel.
- **Desaturate Noise**: Multiple passes of 15x15 Gaussian blur to identify and suppress chroma noise.
- **Bottleneck**: High computational complexity of the bilateral weight and multiple large-kernel blurs.

### Proposed Strategies
1. **Downsampled Chroma Filtering**: Since chroma noise is primarily low-frequency, we can downsample the U/V planes by 2x or 4x, apply the bilateral filter, and then upsample using joint-bilateral or simple bilinear interpolation. This could reduce the workload by 4x-16x.
2. **Fast Bilateral Approximation**: Use a "Bilateral Grid" or a permutohedral lattice approach. Alternatively, a simpler range-limited box filter can approximate the effect.
3. **GPU Acceleration**: Move the Bilateral and Desaturate passes to OpenCL/Vulkan. These filters are highly parallel and well-suited for GPU execution.
4. **Kernel Optimization**: Use lookup tables (LUT) for the `exp()` function in the bilateral filter to save CPU cycles.

## 2. Scheduling Optimization (Halide)
Current stages use `compute_root()`, which maximizes RAM bandwidth usage.

### Proposed Strategies
1. **Stage Fusion**:
   - Fuse `black_white_level` and `white_balance` into `demosaic`.
   - Fuse `srgb` into the final output stage.
   - This reduces the number of full-image intermediate buffers written to and read from RAM.
2. **Sliding Window / Line Buffers**:
   - For filters like `demosaic` and `bilateral`, use `compute_at` with `store_at` to keep intermediate results in cache/line buffers instead of full-image tiles.
3. **Auto-Scheduler Comparison**:
   - Use Halide's `Adams2019` or `Mullapudi2016` auto-schedulers to generate a baseline and compare against the manual schedule.
4. **Tile Size Tuning**:
   - Experiment with different tile sizes (e.g., 64x64 or 256x16) to better fit the L2/L3 cache of modern mobile CPUs (Snapdragon/Dimensity).

## 3. Workflow & IO
- The current implementation of **WorkManager-based background export** already addresses the perceived latency issue by moving heavy IO and final encoding out of the capture path.
- **Further Improvement**: JNI could produce a hardware-accelerated JPEG using `libjpeg-turbo` or Android's `MediaCodec` if even faster JPEG generation is needed.

## 4. Color Pipeline Stability
- All optimizations must maintain the `CCM -> XYZ -> OETF` transformation sequence to ensure color consistency.
- Any change in the `demosaic` or `denoise` stages should be verified with bit-to-bit comparison on the YUV/Linear-RGB output before the OETF stage.
