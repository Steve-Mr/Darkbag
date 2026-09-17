# Darkbag 核心处理速度与性能优化指导意见 (Performance Optimization Directive)

> **目标定位**：本文档作为后续性能优化专项 Subagent（或工程开发团队）的架构设计与实施基石（Foundation Document）。
> **核心宗旨**：紧扣代码事实与 Android 官方最佳实践，彻底打破快门与处理时延瓶颈，杜绝无意义计算与重复 I/O，建立清晰的流水化架构。

---

## 0. 核心架构设计原则（Architectural Principles）

1. **快门关键路径非阻塞（Non-blocking Shutter Dispatch）**：
   从快门触发到准备好接收下一次拍摄，主线程与相机捕获线程（`Camera2Thread`）只负责下发请求与移交 RAW Buffer，严禁在关键路径上串行执行 DNG 序列化、磁盘落盘或重度元数据解析。
2. **热路径零拷贝与零冗余分配（Zero-Copy & Allocation-Free）**：
   图像处理主循环与高频帧回调严禁频繁调用大块 `ByteBuffer.allocateDirect`。充分利用 Halide 与 Native 驱动的 Stride 机制，消除 Java/Kotlin 层的逐行搬运与内核缺页中断。
3. **单趟流式存储（Single-Pass Streaming I/O）**：
   严格对齐 Android 14/15 官方 Scoped Storage 规范，采用 `IS_PENDING=1` + `FileDescriptor` 直写，消灭“写临时缓存 → 全文件拷贝 → 重开 URI 改 EXIF”的多次磁盘往返。
4. **两级流水化解耦（Two-Stage Pipeline Decoupling）**：
   将处理流水线划分为“CPU/GPU 密集计算段（Halide + ColorPipe）”与“I/O 密集落盘段（DNG/JPEG 编码与 MediaStore 写入）”，两级通过有界通道解耦，使连拍时的计算与落盘重叠执行。
5. **轻量化动态流控制（Dynamic Stream Targeting）**：
   动态控制取景器 Repeating Request 中的 Surface Target，禁止以“重建 CaptureSession”为代价进行节能切换。

---

## 1. 完整问题清单与根因定位（Problem Inventory）

### P1-1. ImageSaver 伪慢路径重编码与二次镜像（Double-Mirroring）
- **现象与代价**：前摄拍摄或数字变焦时，单张拍照额外增加 **400ms – 900ms** 耗时，瞬时产生 **48MB – 96MB** Java 堆抖动，且前摄照片被二次翻转导致最终镜像方向错误。
- **根本原因**：
  1. `ImageSaver.kt` 在判断 `needsBitmapProcessing` 时，硬性将 `mirror` 与 `zoomFactor > 1.05f` 判定为必须走慢路径，完全忽视了 JNI / ColorPipe 已完成裁剪与镜像的标记（`isAlreadyCropped`）。
  2. 跌入慢路径后，无条件调用 `BitmapFactory.decodeFile` 将 JNI 刚写出的 12MP JPEG 完整读入并解码，重新经由 `Matrix` 变换后再以 `compress(JPEG, 95)` 二次有损重编。
  3. C++ `ColorPipe.cpp` 在像素循环中已执行了水平翻转，Kotlin 慢路径再次调用 `matrix.postScale(-1f, 1f)`，负负得正，造成前摄照片方向彻底错误。

### P1-2. Halide 对齐层 0（Layer 0）穷举搜索算力黑洞
- **现象与代价**：5 帧连拍在 Layer 0 单独产生 **$3.04 \times 10^9$ 次绝对差累加（SAD）**，霸占了对齐阶段 **>93.5%** 的总计算量，连拍多张引发严重发热与 CPU 降频。
- **根本原因**：`hdr-plus/src/align.cpp` 中 Layer 0 采用 $16 \times 16$ tile 内 256 点、64 个候选位移的穷举扫描，未结合前一层（Layer 1）已有的对齐矢量进行时空域搜索半径收缩，亦无步长抽样或早停判定。

### P1-3. Fast 档 RGB $\to$ YUV $\to$ RGB 浮点空跑
- **现象与代价**：日光常见档（`ISO < 400`）每次拍摄白白产生 **288MB 内存带宽吞吐** 与约 $7 \times 10^7$ 次浮点计算，净增 **100ms – 300ms** 延迟并引入舍入误差。
- **根本原因**：`hdrplus_pipeline_generator.cpp` 在 `denoise_passes == 0` 时，未设置旁路短路分支，无条件执行 `yuv_to_rgb(rgb_to_yuv(input))`，在 DRAM 中物化并回读 144MB 的全图浮点数组。

### P1-4. 编译器优化标志静默退化（-O3 被 -O2 覆盖）
- **现象与代价**：包含大量 OpenMP 像素循环、ACES 拟合及 LUT 插值的核心 C++ 代码未释放全部向量化算力，损失约 **10% – 25%** 潜在性能。
- **根本原因**：`CMakeLists.txt` 设置的 `-O3` 排在前面，Android Gradle Plugin 默认的 `RelWithDebInfo` 构建规则将 `-O2 -g -DNDEBUG` 拼接在编译器命令尾部。依据 Clang 参数“后者覆盖前者”规则，编译器实际执行的是 `-O2`。

### P1-5. 全链路单线程严格串行（零流水化重叠）
- **现象与代价**：连续拍摄 5 张时，用户在快门后需额外等待 **1.0s – 3.0s**。
- **根本原因**：`HdrPlusProcessingService` 与 `ColorProcessor` 绑定在单一线程调度器（`imageProcessingDispatcher`）。Halide 密集计算段、72MB DNG 写盘、ColorPipe JPEG 编码以及 MediaStore 写入在同一个单线程上首尾串行相接，无法实现“上一张落盘、下一张计算”的流水线并行。

### P1-6. 耗时埋点损坏（监控数据全失真）
- **现象与代价**：日志中处理耗时显示为数十亿毫秒的负数，或 C++ ColorPipe/DNG 阶段耗时恒为 0，使性能优化丧失量化基准。
- **根本原因**：`StandardTimingTracker` 中的 `jniDone` 与 `firstOutputWritten` 仅声明从未赋值；`exportHdrPlus` 的 JNI 签名未支持 `debugStats` 数组回传。

### P1-7. 热路径内存分配与未压缩单行 DNG
- **现象与代价**：单帧拍摄频繁触发系统软缺页（6,144 次/帧）与 ART GC 回收压力；单张 DNG 产生 3,000 次独立磁盘 strip 写入与 216MB 磁盘吞吐。
- **根本原因**：
  1. 单帧链路每次新建 `ByteBuffer.allocateDirect(24MB)` 并在 Kotlin 逐行 memcpy。
  2. `ColorPipe.cpp` 写入 DNG 时采用 `COMPRESSION_NONE` 且 `TIFFTAG_ROWSPERSTRIP = 1`。

### P1-8. 频繁跨进程 Binder IPC 与常驻 YUV 分析流
- **现象与代价**：每次快门发生多次同步 IPC 挂起；取景状态下 ISP 持续向 DRAM 搬运未压缩 YUV 帧，产生约 **93 MB/s** 持续带宽开销与待机发热。
- **根本原因**：AOSP `CameraManager` 未对 `getCameraCharacteristics` 提供全局缓存；`analysisImageReader` 在不需要测光的普通预览时仍常驻挂载于 repeating request。

---

## 2. 优化思路与改进路线图（Strategic Roadmap）

### 阶段一：极速见效项（Quick Wins - 极小改动消除最大拖累）

1. **修正 `ImageSaver` 快慢路径判定与前摄镜像契约**：
   - 慢路径判定条件中引入 `!isAlreadyCropped` 保护；若 C++ 已完成裁剪缩放，直接复用 JNI 输出文件。
   - 遵循契约：若 JNI 已执行 `mirror`，传给 `ImageSaver` 的 `mirror` 标志必须强制置为 `false`，根除二次镜像缺陷。
2. **构建参数强制 Release 覆盖**：
   - 在 Gradle 的 `externalNativeBuild.cmake` 参数中显式注入 `-DCMAKE_BUILD_TYPE=Release`，确保 `-O3` 真正生效。
3. **Fast 档色彩空间转换短路**：
   - 在 Halide 生成器中增加守卫：`if (num_passes <= 0) return input;`，彻底跳过 `rgb_to_yuv` 与 `yuv_to_rgb`。
4. **循环不变量外提与启用已有 LUT 缓存**：
   - 提取 `ColorPipe.cpp` 逐像素循环中的 `std::pow(2.0f, exposure)`；接入现成但闲置的 `get_cached_lut` 函数，避免每次导出重复解析上万行文本。

### 阶段二：恢复可观测性（Telemetry Restoration）

1. **对齐 `StandardTimingTracker` 生命周期**：
   - 在 JNI 返回后与首个输出完成落盘时，正确更新 `jniDone` 与 `firstOutputWritten` 时间戳。
2. **打通 JNI 导出阶段耗时统计**：
   - 扩展 `exportHdrPlus` 签名，将 ColorPipe 渲染、DNG 编码与 JPEG 压缩细分耗时填入 `debugStats`，恢复端到端性能视图。

### 阶段三：两级流水化与现代化 I/O 重构（Pipelining & Scoped Storage）

1. **解耦 Halide 计算与 I/O 导出落盘**：
   - 引入生产者-消费者模型：Halide 处理完成后，将原始合成 Buffer 投递到后处理导出队列；处理线程立即释放并承接下一张 Burst 合成任务。
2. **基于 `FileDescriptor` 的单趟流式落盘**：
   - 废除“写私有 Cache 文件 $\to$ File.copyTo 拷进 MediaStore $\to$ ExifInterface 重开 URI 改写”的反模式。
   - Kotlin 创建带 `IS_PENDING=1` 的 MediaStore URI，将 `ParcelFileDescriptor` 整数句柄传入 Native。
   - C++ 侧通过 `TIFFClientOpen`（DNG）与 `jpeg_stdio_dest` 直接向 fd 写入数据与 EXIF Header。
   - 写入完毕直接关闭句柄并置 `IS_PENDING=0`，实现真正的一次性原子落盘。
3. **优化 DNG Strip 结构与轻量压缩**：
   - 将 DNG 的 `ROWSPERSTRIP` 由 1 调整为合理高度（如 64 或 128）；视 CPU 负载选用 Deflate 压缩。

### 阶段四：底层算力与内存零拷贝演进（Native Zero-Copy & Align Optimization）

1. **Native 端带 Stride 零拷贝**：
   - 移除 Kotlin 层的逐行 `allocateDirect` 搬运，直接将硬件 `ImageReader` 的原生 buffer 指针与 `rowStride` 传入 Native，使用 Halide 的自定义 Stride Buffer 直接封装，实现零拷贝直通。
2. **Halide Layer 0 层次化搜索优化**：
   - 利用 Layer 1 估计出的位移向量作为 Layer 0 的先验中心，将 Layer 0 的搜索范围缩减或采用棋盘格抽样评估 SAD，大幅度削减 30 亿次计算开销。
3. **单例缓存 CameraCharacteristics 与动态 Target 调度**：
   - 建立进程级 ConcurrentHashMap 缓存设备静态元数据，彻底阻断高频 Binder IPC。
   - 保持会话不变，仅在需要 HDR+ 测光时才将 YUV Surface 添加至 `setRepeatingRequest`，降低日常取景功耗。

---

## 3. 避坑红线与注意事项（Red Lines）

- **绝对禁止在运行时频繁重建 `CameraCaptureSession`**：切勿为了移除 YUV 分析流而调用 `createCaptureSession`，这会导致相机硬件重置、出现 150–400ms 黑屏闪烁并破坏 3A 收敛。必须使用动态 Target 调度。
- **严格保证异步解耦后的 Buffer 线程安全**：在将 Buffer 移交给后台 I/O 线程前，必须确保前置计算对其不再有并发读写，必要时引入严格的引用计数。
- **不得破坏与 `HdrPlusBurst` 内存池的兼容契约**：修改单帧 DirectBuffer 时，切勿污染连拍特有的 Native `megaBuffer` 管理逻辑。
