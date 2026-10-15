# Stack #310 综合审查与验收报告

> **目标对象**：Darkbag 拍摄管线优化体系 **Stack #310**（基于 `origin/main` @ `b89d3945`）  
> **包含范围**：**PR #303**、**PR #304**、**PR #306**、**PR #307**、**PR #309**（当前 HEAD `a368356d`）  
> **代码规模**：20 个文件，**+976 / −323 行**  
> **审查基准**：代码静态分析、编译构建测试、以及基于 Adobe 官方 SDK [`dng_validate`](file:///home/maary/Build/dng_sdk/_build/dng_sdk/source/dng_validate) 的实机规范符合性实证。

---

## 1. 执行摘要与评审结论

### 综合评级：⚠️ 条件性阻断（需修复阻断项后方可合流）

Stack #310 是一套完整且深度重构的 Android 计算摄影性能优化体系，系统性解决了快门延迟、跨进程 IPC 开销、Halide 对齐算力瓶颈与磁盘 I/O 冗余拷贝。其架构设计方向高度符合 `docs/CAPTURE_SPEED_OPTIMIZATION_PLAN.md` 的路线图。

然而，在栈顶集成与 DNG 规范落地阶段发现了 **2 个阻断级问题（Blocker）** 和 **3 个主要缺陷（Major）**。其中：
1. **DNG 规范违背**：使用 Adobe 官方工具 [`dng_validate`](file:///home/maary/Build/dng_sdk/_build/dng_sdk/source/dng_validate) 实测证实，**Mode 1 (Adobe Deflate) 被 Adobe 规范判定为非法**（DNG 规范严格禁止 16-bit 整数 RAW 使用 Deflate）；且内嵌预览图因缺失 `TIFFTAG_SUBIFD` 标签被写为多页 Chained IFD，被标准读取器完全忽略。
2. **状态与异常破坏**：Kotlin 服务层在 `finally` 块中硬编码 `success = true`，导致处理异常或写盘失败时将 0 字节/损坏文件强制发布至 MediaStore。
3. **设计确认项**：关于 Commit `a368356d` 将 `ImageSaver.kt` 中的 `writeExifMetadata` 调回 `!isFastPath`，已确认为**符合设计预期（Expected Behavior）**，旨在保持单帧快路径的极致保存性能，而仅在 Stage 2 PFD 导出终态写入 EXIF。

---

## 2. 栈内各 PR 交付物与成果核验

| PR | 提交 SHA | 核心优化目标 | 核验结论 |
|---|---|---|---|
| **PR #303** (Stage 1 & 2) | `de9e1626` | 1. 强制 `-O3 -ffast-math` 编译标志<br>2. 提取 ColorPipe 循环不变量 (`pow`/`eff_gain`)<br>3. Fast 模式 (ISO < 400) 短路 Halide 降噪 (0-pass)<br>4. 3D LUT 全局缓存管理器 (`LutCacheManager`)<br>5. 补齐 `StandardTimingTracker` 全链路时间戳与 JNI `debugStats` | ✅ **全部落地有效**<br>- 循环不变量外提与 LUT 内存命中实测生效；<br>- ISO < 400 准确路由至 `hdrplus_fast_pipeline`；<br>- 埋点覆盖 Shutter 至 MediaStore 耗时。 |
| **PR #304** (Stage 3) | `abf6a888` | 1. 计算与导出解耦的两级流水线 (`exportProcessingDispatcher`)<br>2. 预创建 MediaStore Pending 行进行流式直写<br>3. DNG Strip 结构优化 (`ROWSPERSTRIP = 64`) | ✅ **两级流水成功建立**<br>- 第 $N+1$ 帧 Halide 计算与第 $N$ 帧 Stage 2 导出重叠并行；<br>- Strip 64 降低了 LibTIFF I/O 系统调用开销。 |
| **PR #306** (Stage 4) | `3276a3be` | 1. Halide Layer 0 对齐半径缩减 (`search_radius = 2`, extent 4)<br>2. `copyBayerWithStride` JNI 多线程行拷贝加速<br>3. `CameraRepository` 缓存 `CameraCharacteristics`<br>4. 动态解挂 `analysisImageReader.surface` | ✅ **大幅降低前端开销**<br>- 消除每帧重采样与 Binder IPC 开销（节省 ~15–30ms）；<br>- Halide 对齐段 SAD 计算量显著降低；<br>- 行跨度直接内存对齐安全。 |
| **PR #307** (Pipeline Opt) | `39d94467` | 1. TurboJPEG 直写 FileDescriptor (`write_jpeg_turbo_fd`)<br>2. DNG 直写 FileDescriptor (`TIFFFdOpen`)<br>3. 引入 `MAX_CONCURRENT_STAGE2_EXPORTS = 2` 信号量限制峰值内存 | 🟡 **落地且带来性能跃升，但遗留缺陷**<br>- 规避了 ~75MB 中间暂存文件复制（单张节省 ~50–80ms）；<br>- 首次引入的 Deflate (Tag 32946) 引发第三方软件兼容灾难；<br>- `TIFFFdOpen` 失败存在文件句柄泄漏。 |
| **PR #309** (DNG & EXIF) | `0feaff78`<br>`7a69bda6`<br>`a368356d` | 1. 引入 16-bit Lossless JPEG (Mode 0, Tag 7) 编码器<br>2. 提供 DNG 压缩模式可配置项 (UI + Service + JNI)<br>3. PFD 直写路径补全 EXIF 元数据与 EditConfig JSON<br>4. 触发 `backgroundSaveFlow` 驱动相册缩略图流式即时更新 | ❌ **存在规范阻断与发布状态缺陷**<br>- Mode 0 (Lossless JPEG) 基础功能可用；<br>- Mode 1 违反 DNG 规范；<br>- 缩略图未挂接 SubIFD；<br>- `finally` 强制发布损坏文件。 |

---

## 3. 详细缺陷与技术风险清单

### [BLOCKER-1] `finally` 块硬编码 `success = true` 导致损坏文件入库

- **位置**：[`HdrPlusProcessingService.kt:209-235`](file:///home/maary/Build/Darkbag/app/src/main/java/top/maary/darkbag/processor/HdrPlusProcessingService.kt#L209-L235)
- **代码分析**：
  ```kotlin
  val exportRet = try {
      ColorProcessor.exportHdrPlus(...)
  } finally {
      if (pfdJpg != null) {
          top.maary.darkbag.utils.ImageSaver.finalizeMediaStorePendingPfd(
              context = this@HdrPlusProcessingService,
              pfdPair = pfdJpg,
              success = true, // ⚠️ 无论 export 成功或抛出异常，恒为 true！
              editConfig = req.editConfig,
              captureMetadata = req.metadata
          )
          top.maary.darkbag.processor.ColorProcessor.backgroundSaveFlow.tryEmit(...)
      }
      if (pfdDng != null) {
          top.maary.darkbag.utils.ImageSaver.finalizeMediaStorePendingPfd(
              context = this@HdrPlusProcessingService,
              pfdPair = pfdDng,
              success = true // ⚠️ 恒为 true！
          )
      }
  }
  ```
- **严重性与危害**：
  1. 当 Native 导出因磁盘满、OOM、或 DNG 写入失败返回非零，甚至 JNI 发生未捕获异常时，`finally` 强制执行并将 `IS_PENDING` 更新为 0，使 0 字节或半截损坏的照片出现在用户系统相册中。
  2. `finalizeMediaStorePendingPfd` 内的 `contentResolver.delete(uri, null, null)` 清理路径变为**不可达死代码**。
  3. 失败时依然发出 `backgroundSaveFlow`，导致 UI 尝试加载无效 URI。

---

### [BLOCKER-2] DNG Mode 1 (Adobe Deflate) 违反 Adobe 官方规范

- **位置**：[`ColorPipe.cpp:1703-1705`](file:///home/maary/Build/Darkbag/app/src/main/cpp/ColorPipe.cpp#L1703-L1705)
- **机制与实证**：
  PR 声明将 Mode 1 设定为 `COMPRESSION_ADOBE_DEFLATE = 8` 并将 `DNGBackwardVersion` 提升至 1.4.0.0。  
  使用系统上的 Adobe 官方验证工具 [`dng_validate`](file:///home/maary/Build/dng_sdk/_build/dng_sdk/source/dng_validate) 实测校验，报致命错误：
  ```text
  Validating "test_dng_deflate.dng"...
  IFD 0: Offset = 118, Entries = 20
  BitsPerSample: 16 16 16
  Compression: Deflate
  PhotometricInterpretation: LinearRaw
  ...
  *** Error: ZIP compression is limited to floating point, 32-bit integer, transparency masks, semantic masks, gain maps, and depth maps (IFD 0) ***
  ```
- **原因**：
  根据 Adobe DNG Specification 1.4.0.0 / 1.7.1.0 规范第 3 节（Compression 章节）：
  **Deflate (Tag 8) 压缩仅允许用于浮点 RAW（16/24/32-bit float）、32-bit 整型 RAW 以及辅助蒙版/深度图**。对于标准的 8–16 bit 整数 CFA 及 LinearRaw 数据，DNG 规范**严格禁止**使用 Deflate 压缩。  
  因此，所有严格遵循 DNG 规范的标准软件（Adobe Lightroom、Camera Raw、Snapseed LibRaw 等）在打开 Mode 1 DNG 时均会报错或判定为非合规文件。

---

### [MAJOR-1] DNG 预览图未写入 `TIFFTAG_SUBIFD`，沦为无效 Chained IFD

- **位置**：[`ColorPipe.cpp:1837-1863`](file:///home/maary/Build/Darkbag/app/src/main/cpp/ColorPipe.cpp#L1837-L1863)
- **现象**：
  代码虽包含注释 `// Pre-encode preview images first to calculate SubIFDs offsets`，但在构建主 IFD0 时**完全没有调用 `TIFFSetField(tif, TIFFTAG_SUBIFD, ...)`**。  
  后续通过循环调用 `TIFFWriteDirectory(tif)`，在 LibTIFF 中直接形成了 Multi-Page TIFF 的顶层链接（Chained IFD 1 / IFD 2）。
- **实证**：
  Adobe [`dng_validate`](file:///home/maary/Build/dng_sdk/_build/dng_sdk/source/dng_validate) 明确输出告警：
  ```text
  NextIFD = 4906
  Chained IFD 1: Offset = 4906, Entries = 12
  NewSubFileType: Preview Image
  ...
  *** Warning: This file has Chained IFDs, which will be ignored by DNG readers ***
  ```
- **后果**：
  DNG 解析器严格遵循 TIFF-EP 规范，只会解析主目录与 `TIFFTAG_SUBIFD` 指向的子目录，Chained IFD 中的缩略图被直接丢弃。C++ 中耗费 CPU 算力生成的 512px 和 2048px 预览图无法生效。

---

### [MAJOR-2] `HdrPlusJNI.cpp` 忽略 `write_dng` 错误码

- **位置**：[`HdrPlusJNI.cpp:461-468`](file:///home/maary/Build/Darkbag/app/src/main/cpp/HdrPlusJNI.cpp#L461-L468)
- **代码**：
  ```cpp
  if (outDngFd >= 0 || dng_path_cstr) {
      write_dng(...); // ⚠️ bool 返回值被无视，未赋给任何变量
  }
  ...
  bool saveOk = true;
  if (outJpgFd >= 0 || jpg_path_cstr) {
      saveOk = process_and_save_image(...);
  }
  return saveOk ? 0 : -2; // 仅取决于 saveOk
  ```
- **后果**：
  当 DNG 写入因空间不足或编码失败返回 `false` 时，`exportHdrPlus` 依然返回 0（成功），导致 DNG 写失败被静默掩盖。

---

### [MAJOR-3] `encode_lossless_jpeg16` 缺乏安全的 setjmp/longjmp 错误处理

- **位置**：[`ColorPipe.cpp:20-25`](file:///home/maary/Build/Darkbag/app/src/main/cpp/ColorPipe.cpp#L20-L25)
- **代码**：
  ```cpp
  struct jpeg_compress_struct cinfo;
  struct jpeg_error_mgr jerr;
  cinfo.err = jpeg_std_error(&jerr);
  jpeg_create_compress(&cinfo);
  ```
- **后果**：
  `jpeg_std_error` 的默认 `error_exit` 实现为输出日志后直接执行 `exit(1)`。在 Android JNI 进程中，若遭遇极端内存不足或 LibJPEG 内部校验错误，会导致整个 App 进程直接闪退退出，Kotlin 层的 try/catch 完全无法拦截。应当使用带 `setjmp`/`longjmp` 的自定义错误处理器。

---

### [MINOR-1] `TIFFFdOpen` 失败时 `dup_fd` 泄漏

- **位置**：[`ColorPipe.cpp:1642-1650`](file:///home/maary/Build/Darkbag/app/src/main/cpp/ColorPipe.cpp#L1642-L1650)
- **代码**：
  ```cpp
  int dup_fd = dup(outFd);
  if (dup_fd < 0) return false;
  ftruncate(dup_fd, 0);
  tif = TIFFFdOpen(dup_fd, "DNG_Stream", "w");
  if (!tif) return false; // ⚠️ 若 LibTIFF 初始化失败，dup_fd 句柄泄漏
  ```

---

### [MINOR-2] PFD 直写路径向 `BackgroundSaveEvent` 发送虚拟 `jpgPath`

- **位置**：[`HdrPlusProcessingService.kt:220`](file:///home/maary/Build/Darkbag/app/src/main/java/top/maary/darkbag/processor/HdrPlusProcessingService.kt#L220)
- **现象**：
  直写 PFD 分支下 Native 直接流向 ParcelFileDescriptor，`req.fullResJpgPath` 文件从未创建。
  事件中仍传递 `jpgPath = req.fullResJpgPath`。目前 CameraFragment 优先使用了 `targetUri` 因而未报错，但该虚拟路径对于任何依赖本地文件存在的下游订阅者是不安全的。

---

### [MINOR-3] DNG `BlackLevel` 标签计数不严密

- **位置**：[`ColorPipe.cpp:1743`](file:///home/maary/Build/Darkbag/app/src/main/cpp/ColorPipe.cpp#L1743)
- **现象**：
  ```cpp
  TIFFSetField(tif, TIFFTAG_BLACKLEVEL, 1, &black_level_val);
  ```
  对于 3 通道 LinearRaw，`dng_validate` 告警 `*** Warning: IFD 0 BlackLevel has unexpected count (1) ***`。应提供与通道数匹配的 3 元数值。

---

### [NIT-1] RAW 禁用时 DNG 压缩模式菜单依然常驻显示

- **位置**：[`SettingsFragment.kt:649-655`](file:///home/maary/Build/Darkbag/app/src/main/java/top/maary/darkbag/fragments/SettingsFragment.kt#L649-L655)
- **现象**：
  在 `updateStorageVisibility()` 中，当 `cbSaveRaw.isChecked == false` 时，`layoutRawStorage` 会被隐藏，但 `layout_dng_compression_menu` 未进行联动隐藏。

---

## 4. 专项确认：快路径 EXIF 设计行为复核

在 Commit `7a69bda6` 与 `a368356d` 之间，存在对 `ImageSaver.kt` 的改动调整：
- `7a69bda6` 曾试图在 `ImageSaver.kt` 快路径强制传入 `writeExifMetadata = true`；
- `a368356d` 随后将快路径调用恢复为 `writeExifMetadata = !isFastPath`，并将 EXIF 写入移至 `finalizeMediaStorePendingPfd` 中执行。

经用户确认，此回滚为**预期行为（Expected Behavior）**：
- **设计逻辑**：单帧快路径（Fast Path）的核心约束是控制极低的出片延迟，避免在快拍循环中引入 ExifInterface 的反复磁盘回读与标记解析开销；
- **职责划分**：Stage 2 的 HDR+ 导出走专用的后台 PFD 流水线，在 `finalizeMediaStorePendingPfd` 时统一追加 EXIF 和 EditConfig JSON，两套管线的性能契约分明。

---

## 5. 建议修复行动项（Action Items）

### 行动项 1：修正 Kotlin 状态判定与清理逻辑（解决 Blocker-1）
```kotlin
// HdrPlusProcessingService.kt
var exportSuccessful = false
try {
    val exportRet = ColorProcessor.exportHdrPlus(...)
    exportSuccessful = (exportRet == 0)
} finally {
    if (pfdJpg != null) {
        top.maary.darkbag.utils.ImageSaver.finalizeMediaStorePendingPfd(
            context = this@HdrPlusProcessingService,
            pfdPair = pfdJpg,
            success = exportSuccessful,
            editConfig = req.editConfig,
            captureMetadata = req.metadata
        )
        if (exportSuccessful) {
            top.maary.darkbag.processor.ColorProcessor.backgroundSaveFlow.tryEmit(
                top.maary.darkbag.processor.ColorProcessor.BackgroundSaveEvent(
                    baseName = req.baseName,
                    dngPath = if (req.saveRaw) req.linearDngPath else null,
                    jpgPath = null, // 直写路径无本地中间文件，传 null
                    targetUri = pfdJpg.second.toString(),
                    zoomFactor = req.zoomFactor,
                    orientation = req.orientation,
                    saveJpg = req.saveJpg
                )
            )
        }
    }
    if (pfdDng != null) {
        top.maary.darkbag.utils.ImageSaver.finalizeMediaStorePendingPfd(
            context = this@HdrPlusProcessingService,
            pfdPair = pfdDng,
            success = exportSuccessful
        )
    }
}
```

### 行动项 2：规范化 DNG 压缩模式选项（解决 Blocker-2）
从 UI 与底层彻底废除违反 DNG 规范的 16-bit 整数 Deflate 模式：
- **Mode 0**：`Lossless JPEG` (Compression = 7, DNGBackwardVersion = 1.1.0.0，默认，~4-5MB/帧)
- **Mode 1**：`Uncompressed` (Compression = 1, DNGBackwardVersion = 1.1.0.0，极致兼容性，~24-73MB/帧)

### 行动项 3：规整 DNG SubIFDs 树状目录结构（解决 Major-1）
在 LibTIFF 中正确使用 `TIFFTAG_SUBIFD` 与 `TIFFWriteCustomDirectory` 将预览图挂载在 IFD0 下，确保 Adobe Lightroom 与 OS 缩略图引擎识别。

### 行动项 4：JNI 校验 DNG 结果与 LibJPEG 安全包装（解决 Major-2 & Major-3）
1. 在 `HdrPlusJNI.cpp` 中将 `dngOk = write_dng(...)` 纳入最终返回值判定：`return (saveOk && dngOk) ? 0 : -2;`。
2. 在 `encode_lossless_jpeg16` 中通过 `setjmp`/`longjmp` 捕获 LibJPEG 错误，失败时清理并安全返回空字节流。
