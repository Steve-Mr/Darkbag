# Darkbag HDR+ / 非 HDR+ 拍摄速度优化方案（实施版）

> **定位**：本文是 `docs/HDRPLUS_PIPELINE_ANALYSIS.md`（问题一：处理速度）与
> `docs/GUIDELINES_PERFORMANCE_OPTIMIZATION.md` 两份文档的**落地实施版方案**，
> 并把 `docs/GUIDELINES_COLOR_PIPELINE.md` 中的色彩不变量作为**不可破坏的约束**纳入。
> **基线**：`main` @ `b89d3945`（含 PR #298 / #299）。所有结论均已在当前 HEAD 上逐条复核，
> 凡与两份文档不一致处，本文以**当前代码事实**为准并显式标注（见 §1）。
> **本文不含代码改动**，仅为方案。所有耗时数字若无真机实测均为**估算**并标 `[需实测]`。

---

## 0. 目标与指标定义

优化必须先统一口径，否则"变快了"无法验证。建议以三个指标作为唯一验收语言：

| 指标 | 定义 | 埋点位置 | 现状 |
|---|---|---|---|
| **T1 快门延迟** | 用户按下快门 → UI 恢复可再次拍摄 | `shutterClick` → `processingSemaphore.release()` 后的 Main 线程回调 | 非 HDR+ 受 DNG 串行 + Motion Photo await 影响，波动大 `[需实测]` |
| **T2 出片时间** | 按下快门 → 首张成片在 MediaStore 可见 | `shutterClick` → `ImageSaver` 写库完成 | 埋点缺失，不可见 |
| **T3 连拍吞吐** | HDR+ N 帧 → 最终成片落盘完成 | `shutterClick` → 服务 `stopSelf()` | 埋点只覆盖 Halide 段 |

**建议目标（待 §9 实测校准后冻结）**：非 HDR+ 单帧 `T1 ≤ 300 ms`、`T2 ≤ 1.2 s`；
HDR+ 5 帧 `T3 ≤ 2.5 s`（当前实测值未知，先度量再定目标）。

> **前置结论**：本轮复核确认，**两条链路的瓶颈不在同一处**，必须分开治理。
> 非 HDR+ 的瓶颈在 **Kotlin 保存链路 + 串行副作用**；HDR+ 的瓶颈在 **Halide 对齐层 0 的穷举 SAD + 导出/落盘段无流水化**。

---

## 1. 复核结论：两份文档 vs 当前 HEAD

本节是方案的"事实底座"。文档里已经闭环的项**不再投入精力**，表述失真的项**先修正认知**。

### 1.1 已闭环（不要再改）

| 文档条目 | 当前事实 | 证据 |
|---|---|---|
| `GUIDELINES` P1-3 / `ANALYSIS` H5：fast 档 RGB→YUV→RGB 空跑 | **已修复**。生成器已有 `if (num_passes <= 0) return input;`；且实测编译产物中 `hdrplus_fast_pipeline.a` / `hdrplus_single_pipeline.a` 的 `rgb_to_yuv`/`yuv_to_rgb` 符号数 = **0**（high/raw 各 4，符合预期） | `hdrplus_pipeline_generator.cpp:369`；`nm` 实测 4 个变体 |
| `GUIDELINES` P1-4：`-O3` 被 `-O2` 覆盖 | **当前不成立**。实际编译命令末位优化标志为 `-O3 -ffast-math -fPIC`，`-O3` 生效 | `app/.cxx/RelWithDebInfo/.../build.ninja` 中 `ColorPipe.cpp.o` 的 `FLAGS` |
| `GUIDELINES` C1-3 / `ANALYSIS` C1：`BaselineExposure` 截断到 0.5 EV | **已修复**，上限已放宽到 4.0 EV | `ColorPipe.cpp:1614` |

### 1.2 表述需修正（避免按文档做出错误决策）

| 文档表述 | 更正 |
|---|---|
| "埋点完全损坏，C++ 段耗时恒为 0" | **收窄为"导出段无埋点"**。`processHdrPlus` / `processSingleFrameRaw` 的 `debugStats`（15 项）**已接通**；只有 `exportHdrPlus` 仍无 `debugStats` 参数，因此 `DNG Encode` / `JPEG Native Save` / `ColorPipe` 恒为 0。另外单帧的 `StandardTimingTracker.jniDone` / `firstOutputWritten` **确实从未赋值**（全仓无赋值语句），单帧 T1/T2 仍失真 |
| "Halide `white_balance()` 是空实现，色度降噪在未白平衡域" | `white_balance()` 仍是空实现，但**色度降噪内部现在自己做 WB/反 WB**（`wb_input` → 双边滤波 → `/wb` 反算）。这是文档未覆盖的**新增成本**（见 §3 O-7） |
| `GUIDELINES` H10：发布版移除 Halide target 的 `-profile` | **不能直接删**。当前 `HALIDE_TARGET` 含 `-profile`，而 Align/Merge/Demosaic/Denoise 的分级耗时正是 `halide_profiler_report()` 的输出源。删除即失去分级埋点。正确做法见 §3 O-3 |
| "DNG `ROWSPERSTRIP = 1` + `COMPRESSION_NONE`" | 事实成立，但行号已漂移到 `ColorPipe.cpp:1511/1517/1530` |

### 1.3 仍存在（本方案的主战场）

全部已在当前 HEAD 逐条确认，详见 §3 的表格与证据列。

---

## 2. 现状链路还原（当前 HEAD）

### 2.1 非 HDR+ 单帧关键路径

```
快门 → TEMPLATE_STILL_CAPTURE(RAW) → onImageAvailable
     → copyAndroidImageToHolder: ByteBuffer.allocateDirect(24MB) 且逐行拷贝   ← O-5
     → 投递 processingChannel(容量 2)
     → [Dispatchers.IO 每张独立 launch]
          ├─ DngCreator.writeByteBuffer → cache 文件（串行挡在入队前）        ← O-10
          ├─ ImageSaver.saveProcessedImage(Bayer DNG) → MediaStore
          ├─ Motion Photo: withTimeoutOrNull(2500){await()}                  ← O-11
          └─ enqueue → startForegroundService
     → HdrPlusProcessingService 单线程消费
          ├─ processSingleFrameRaw → Halide single pipeline（73MB 输出，零初始化）← O-13
          ├─ releaseBuffer
          ├─ exportHdrPlus → ColorPipe(OpenMP 逐像素) → cache JPEG           ← O-12
          └─ ImageSaver: cache 文件 → copyTo(MediaStore) → 重开 URI 改 EXIF   ← O-12
     → ImageSaver 快/慢路径判定：mirror 或 zoom>1.05 ⇒ 解码 + 重编码           ← O-1
```

### 2.2 HDR+ 连拍关键路径

```
快门 → 计算 burst 参数 → captureBurst(N × TEMPLATE_STILL_CAPTURE, AE_MODE_OFF)
     → 每帧 acquireNextImage → 拷进 megaBuffer（池化，MAX_POOL_SIZE=3）
     → 收齐 → enqueue
     → 服务单线程：
          ├─ processHdrPlus → Halide：
          │     align(3 层金字塔 SAD) ← 主成本，层 0 穷举 64 位移 × 16×16 tile  ← O-17
          │     → merge → black/white → demosaic → WB(no-op) → LSC(含 RAW 域软肩)
          │     → chroma_denoise（fanout: WB → 双边 → 反 WB，逐像素除法）
          ├─ 每张一次的 halide_profiler_report + std::regex 解析全量文本         ← O-3
          ├─ 73MB shared buffer（每次新分配 + 零初始化）                        ← O-13
          ├─ exportHdrPlus：load_lut（每次重新解析 .cube 文本）                ← O-4
          │     → write_dng（COMPRESSION_NONE + ROWSPERSTRIP=1，≈72MB/张）
          │     → ColorPipe 全图逐像素（pow 在像素循环内）                      ← O-7
          └─ ImageSaver（三趟 I/O）
```

### 2.3 结构性事实（三条，其余优化都建立在它们之上）

1. **全链路单线程串行**：`ColorProcessor.imageProcessingDispatcher` 是单线程；Halide 计算、DNG 编码、ColorPipe、JPEG 编码、MediaStore 落盘首尾相接，**段间零重叠**。
2. **背压是假的**：`processingSemaphore` 的 `acquire()/release()/tryAcquire()` 全为空实现，真正背压只剩 `canAcceptNewTask` 的"可用内存 ≥ 600MB"经验门限；而请求队列是 `Channel.UNLIMITED`。
3. **内存账本未受控**：每张单帧 24MB 新分配（不进池）、Halide 输出 73MB 每次新分配且零初始化、megaBuffer 池常驻最多 3×122MB。这既是耗时也是内存门限频繁拒绝新拍摄的根因。

---

## 3. 优化项总表

优先级按"收益/成本/风险"排序。收益均为 `[需实测]` 估算。

### P0 — 快赢 + 恢复可观测性（建议 1–2 天内完成）

| ID | 项 | 位置 | 改法 | 成本 | 预期收益 | 风险 |
|---|---|---|---|---|---|---|
| **O-1** | 伪慢路径 + 二次镜像 | `ImageSaver.kt:85`、`:147`、`:162`、`:206/278`；`HdrPlusProcessingService.kt:214-235` | 判定加 `!isAlreadyCropped`；服务端既然 C++ 已镜像则传 `mirror=false`；需要方向信息时写 EXIF Orientation | 2–3 行 | 前摄/变焦 **0.4–0.9 s/张**；同时修掉"前后摄成片方向错误"的功能 bug | 低 |
| **O-2** | 补齐单帧埋点 | `CameraFragment.kt:451-457`、`:1995-2000`；`ColorProcessor.kt:154` + `HdrPlusJNI.cpp` `exportHdrPlus` | 在 JNI 返回与首个输出写完处赋值 `jniDone`/`firstOutputWritten`；给 `exportHdrPlus` 增加 `debugStats` 回传 ColorPipe/DNG/JPEG 三段 | ~30 行 | 0（但决定后续所有排序） | 低 |
| **O-3** | 每张一次 profiler 报告 + 正则解析 | `HdrPlusJNI.cpp:610-612`、`:86-115` | 默认关闭 `halide_profiler_report` + `parseHalideReport`，由调试开关按需开启；关闭时 `debugStats` 里对应分级字段填 -1 表示"未采集" | ~20 行 | 每张省一次多行文本构建 + `std::regex` 匹配 + 字符串堆分配，估 **5–30 ms/张**，并显著降低堆抖动 | 低（失去默认分级埋点，与 O-2 配合） |
| **O-4** | LUT 走已有缓存 | `HdrPlusJNI.cpp:422`、`:624` → 改用 `ColorPipe.cpp:682 get_cached_lut`；切 LUT 时 `clear_lut_cache()` | 2–4 行 | 20–400 ms/张（取决于 .cube 体积），同时消除重复解析的堆抖动 | 低（注意切换 LUT 必须清缓存） |
| **O-5** | 单帧 RAW buffer 池化 | `CameraFragment.kt:4480` → `HdrPlusBurst.acquireBuffer/releaseBuffer` | 复用现有池（`HdrPlusBurst.kt:40-84`），并加引用计数 | 低 | 省掉每张 24MB `allocateDirect` + 目标页缺页；使 600MB 门限更稳定 | 中（必须保证 release 时机，见 §8 红线） |
| **O-6** | 删除死代码全图归约 | `ColorPipe.cpp:871`、`:773-838`、`:1002-1014` | `calculate_adaptive_edge_comp` 的结果恒 `enabled=false`（全仓无 `enabled = true`），调用与函数体可整体删除 | ~1 行调用 + 函数体 | 每张省一次全图 1/step 采样统计（内存遍历） | 低 |
| **O-7** | 循环不变量与逐像素除法外提 | `ColorPipe.cpp:967`（`std::pow(2.0f, exposure)` 在像素路径内）；`hdrplus_pipeline_generator.cpp:387-393`（`max(0.0001f, wb.*)` 逐像素除法） | 把 `pow` 提到循环外；生成器侧预计算 WB 的倒数常量 | 3–5 行 | 50–200 ms/张 `[需实测]` | 低 |
| **O-8** | 连拍 buffer 深度与 `prepare()` | `CameraFragment.kt:3824`（`maxImages=8`）、连拍前 | `maxImages` 按 `burstSize+1` 配置；`captureBurst` 前调用 `session.prepare(reader.surface)`（官方明确：否则 burst 会变慢） | 低 | 连拍首帧延迟与内存占用 | 低 |

### P1 — 结构性改造（建议 1–2 周）

| ID | 项 | 位置 | 改法 | 成本 | 预期收益 | 风险 |
|---|---|---|---|---|---|---|
| **O-9** | **两级流水化** | `HdrPlusProcessingService.kt:48-52` | 拆成 `computeDispatcher`（Halide）+ `exportDispatcher`（DNG/ColorPipe/JPEG），中间用**有界** `Channel(1)` 传递 73MB shared buffer 句柄 | ~80 行 | 连拍 5 张 **T3 降 30–50%（1–3 s）**；单张 T2 亦有改善 | 中（内存/线程预算，见 §5） |
| **O-10** | 单帧 Bayer DNG 出关键路径 | `CameraFragment.kt:1856-1902` | DNG 写出移到独立协程；`RawImageHolder` 加引用计数，Halide 与 DNG 两条读者都完成后才归还缓冲 | ~60 行 | **T1 降 100–300 ms/张** | 中（引用计数正确性） |
| **O-11** | Motion Photo await 出关键路径 | `CameraFragment.kt:1911`（另有 `:3305`、`:3565`） | 先 enqueue，把 mp4 路径解析延迟到服务"写 JPEG 前"（改为挂起提供者或回调补写）；或直接以 `Deferred` 随请求携带 | ~40 行 | **T1 降 0.75–2.5 s/张**（开 Motion Photo 时） | 低–中 |
| **O-12** | 单趟流式落盘 | `ColorPipe.cpp:1511-1530`；`HdrPlusJNI.cpp` export 路径；`ImageSaver.kt:621-711`、`:321-360` | ① DNG 改 `TIFFClientOpen`/fd 直写 MediaStore（`IS_PENDING=1` → 写 → `0`）；② `ROWSPERSTRIP` 提到 64–128；③ 评估 `COMPRESSION_DEFLATE`；④ JPEG 的 EXIF 在编码期写入（`jpeg_write_marker`）或同 fd 就地补丁，消除"写 cache → copyTo → 重开改 EXIF"三趟 | 中–高 | DNG I/O 从 ≈216MB/张降到 ≈40–80MB/张；**0.2–0.6 s/张** | 中（第三方 DNG 兼容性需抽检） |
| **O-13** | 输出 buffer 去零初始化与池化 | `HdrPlusJNI.cpp:496` | 用池化的 `shared_ptr<vector<uint16_t>>` 替代每次 `make_shared<vector>(n)`（后者会 memset 73MB）；导出完成即归还池 | 中 | 省一次 73MB memset + 页错误；`[需实测]` | 中（生命周期） |
| **O-14** | 统一线程预算 | `HdrPlusJNI.cpp:557-562` 与 ColorPipe 的 OpenMP | 流水化后 Halide 线程池与 OpenMP 会**超订**。固定"Halide = hw−2、OpenMP = 2"（或反之）并把总预算钉在 hw 以内 | ~10 行 | 避免流水化后反而变慢 | 中 |
| **O-15** | `CameraCharacteristics` 缓存 | `CameraFragment.kt:4402`、`:4495`、`:1754`；`CameraRepository.kt:60` | 进程级缓存设备静态元数据，阻断每次拍摄的重复 Binder IPC | 低 | 恒定小收益 + 降低快门路径抖动 | 低 |
| **O-16** | 动态流目标（非 HDR+ 去 YUV 分析流） | `CameraFragment.kt:3882`、`:3895`、`:4586`、`:4633` | 用**动态 target 调度**（`setRepeatingRequest` 换 target 列表）而非重建 session | 中 | 省 ≈93MB/s 常驻带宽与待机发热 | 中（见 §8 红线） |

### P2 — 里程碑（需真机数据与画质 A/B）

| ID | 项 | 位置 | 说明 |
|---|---|---|---|
| **O-17** | 对齐层 0 降本（最大单项） | `hdr-plus/src/align.cpp:21-22`、`:114-116` | 层 0 为 16×16 tile、64 个候选位移、每位移 256 点全量 SAD（5 帧 12MP ≈ 3×10⁹ 次"绝对差+累加"，占对齐阶段 >93%）。先做 **tile 内隔点抽样**（≈4×，1 行级改动）并做画质 A/B；再评估 **增量 SAD**（列和递推） |
| **O-18** | 3A 预热 | `CameraFragment.kt:4236-4245`、连拍入口 | still 请求前补 `AE_PRECAPTURE` / `AF_TRIGGER` 并等待收敛；仓库已有正例可抄（`MultiCameraCaptureManager.kt:593-620`） |
| **O-19** | ZSL / Reprocess | 会话配置 | 消除"等一帧曝光"。仓库已有环形缓冲与原生工作线程先例（`motionphoto/CircularVideoRingBuffer.kt`、`rawvideo/RawVideoRecorder.h`） |
| **O-20** | ColorPipe 逐像素向量化 | `ColorPipe.cpp:850-1120` | 把 CCM/色调/OETF 搬进 Halide（向量化，每 8 像素）或至少在 JPEG-only 时直接产 RGB8，跳过 u16 中间体与二次全图遍历 |
| **O-21** | 热管理与动态降级 | 连拍入口 + 服务 | 参考官方 thermal 指引：热状态 → 下调 `burstSize` / 降噪档 |
| **O-22** | 降噪档位与画质解耦 | `HdrPlusJNI.cpp:585-587` | 当前仅按 ISO 选 fast/high，与用户画质诉求脱钩 |

---

## 4. 分阶段实施与完成判据（DoD）

### 阶段 0：可观测性（先做，否则无法排序）

- 交付：O-2、O-3。
- **DoD**：连拍 5 张的服务日志同时给出 `T1/T2/T3` 与 Halide 分级（Align/Merge/Demosaic/Denoise/BlackWhite/WB）、ColorPipe、DNG、JPEG 的可信毫秒数；单帧 `jniDone` / `firstOutputWritten` 非零。

### 阶段 1：快赢（P0 其余项）

- 交付：O-1、O-4、O-5、O-6、O-7、O-8。
- **DoD**：前摄单帧不再出现"解码 + 重编码"路径（可用日志或性能火焰图证明）；切 LUT 后首张与后续张的无 LUT 阶段耗时一致；单帧不再有 24MB 新分配的抖动尖峰。

### 阶段 2：流水化 + I/O 现代化

- 交付：O-9、O-10、O-11、O-12、O-13、O-14。
- **DoD**：连拍期间"计算段"与"导出段"在 Perfetto 时间轴上**重叠**；峰值原生内存 ≤ 预算（§5.3）；DNG 单张磁盘写入字节数与 strip 数显著下降；`IS_PENDING` 单趟落盘（不再有 cache → copy → 重开三趟）。

### 阶段 3：算法与调度

- 交付：O-15、O-16、O-17、O-18。
- **DoD**：对齐降本后通过画质 A/B（§9.4）；取景功耗下降；快门延迟下降。

### 阶段 4：里程碑

- 交付：O-19 ~ O-22。

---

## 5. 两级流水化详细设计（O-9 / O-13 / O-14）

### 5.1 线程模型

```
捕获线程(Camera2Thread)                    [保持现状：只下发请求 + 拷进 megaBuffer]
        │  有界队列：Channel(capacity = 1~2)
        ▼
计算线程 "HdrPlusCompute" ── Halide(线程池 hw-2) ──► 73MB shared buffer（池化）
        │  有界队列：Channel(capacity = 1)
        │  （上限信号量：in-flight export ≤ 2）
        ▼
导出线程 "HdrPlusExport" ── DNG(fd) + ColorPipe(OpenMP ≤ 2) + JPEG(fd)
        │
        ▼
收尾线程 "HdrPlusFinalize" ── EXIF / Motion Photo 关联、通知节流、UI 回调
```

要点：

1. **计算线程绝不等待导出**：`processHdrPlus` 返回后立即 `releaseBuffer(megaBuffer)` 并取下一个任务（现状已 release，但紧接着就在同一线程做导出，这正是要拆开的点）。
2. **导出线程独占 OpenMP**：ColorPipe 内部用 OpenMP，若与 Halide 池同时满负荷会超订，必须按 O-14 钉死预算。
3. **收尾与导出可合并**（若实现复杂度优先），但通知/UI 更新必须节流（当前每帧一次 Main 线程进度更新 + 通知刷新）。

### 5.2 数据结构

```
ProcessingResult {
  requestId      // 同时是 g_sharedMemoryMap 的 key
  sharedBuf      // 73MB（池化句柄，导出后归还）
  meta           // 既有 HdrPlusRequest 中的元数据快照
  saveFlags      // saveJpg / saveRaw / motionPhoto…
}
```

`g_sharedMemoryMap` 已有互斥保护（`HdrPlusJNI.cpp:126-127`、`:385-389`、`:658-659`），跨线程传递是安全的；但**必须先明确所有权**：目前 `exportHdrPlus` 进入时即 `erase`，等于"导出接管 73MB"。流水化后同一时刻最多 `in-flight export` 份，需一并计入内存预算。

### 5.3 背压与内存预算

- 用**计数信号量**替代"可用内存 ≥ 600MB"的粗糙门限：`in-flight = {compute ≤ 1, export ≤ 2}`。
- 峰值原生内存估算：megaBuffer 池 3×122MB（可缩到 1–2）+ 73MB×(1 + export 上限)。
- 队列**必须有界**（现状 `Channel.UNLIMITED` + 空实现的 `processingSemaphore` 会让连拍请求无限堆积）。

### 5.4 失败回滚

导出失败（磁盘满 / fd 失效 / deflate 兼容性）时：先重试一次；仍失败则**降级**为仅写 JPEG，并保留 `requestId` 日志以便排查。绝不允许因为导出失败而丢失已完成的 Halide 结果（必要时落盘为临时 DNG）。

---

## 6. 单趟流式落盘详细设计（O-12）

### 6.1 现状（三趟 I/O）

`native 写 cache 文件` → `Kotlin FileInputStream.copyTo(MediaStore openOutputStream)` → `重开 URI "rw" 改 EXIF`。
DNG 还叠加 `COMPRESSION_NONE` + `ROWSPERSTRIP = 1`（≈72MB、约 3000 次 strip 写），单张总 I/O ≈ 216MB。

### 6.2 目标

1. Kotlin 侧创建带 `IS_PENDING=1` 的 MediaStore 条目，用 `openFileDescriptor(uri, "w")` 取得 fd。
2. 把 fd 交给 native：DNG 走 libtiff 的 fd 入口（`TIFFFdOpen` / `TIFFClientOpen`），JPEG 走 `fdopen` + `jpeg_stdio_dest`。
3. 写完 → 关闭 fd → `IS_PENDING=0`。
4. `ROWSPERSTRIP` 提到 64–128；`COMPRESSION_DEFLATE` 作为**可配置项**（第三方兼容性抽检通过后再默认开启）。

### 6.3 EXIF 的单趟策略

- **首选**：在 JPEG 编码阶段把 EXIF APP1 段作为 marker 写入（libjpeg `jpeg_write_marker`），彻底去掉"写后重开补丁"。
- **次选**：仍是两趟，但第二趟在同 fd 上原地补丁（省掉一次全文件路径往返）。
- 注意 `ImageSaver.writeMetadataToExif` 当前对 DNG 有专门的 OOM 保护（历史提交 `a2e8fa67`），改动时不要回退该保护。

### 6.4 验收

- 单张 DNG 磁盘写入字节数下降 ≥50%；strip 数下降 ≥10×。
- 系统相册 / 文件管理器 / Lightroom 三处均能正常打开（抽检至少 3 台设备或 3 个解码器）。

---

## 7. 对齐层降本详细设计（O-17）

### 7.1 成本来源

`align_layer` 对每个 tile 在 16×16 像素上、对 64 个候选位移做全量 L1（SAD）累加；层 0 为 1/2 分辨率，每个像素被 4 个 tile 覆盖，因此 5 帧 12MP 的量级 ≈ 3×10⁹ 次"绝对差 + 累加"，占对齐阶段 >93%。层 1/2 合计不到 1.5%，**不要动**。

### 7.2 两条可选路径（按风险递增）

1. **tile 内隔点抽样**：`RDom r0` 改成带步长的子集（如二维 `(0,16,2)` 隔点 → 4× 减少）。改动约 1 行，收益 ≈4×。
2. **增量 SAD**：利用列和递推复用相邻位移的中间结果（理论 5–7×），实现复杂度中等。

### 7.3 画质风险与门限

降本会改变**暗部与运动边缘**的对齐精度。建议先按 ISO / 曝光档位抽样启用（例如只在 ISO ≥ 800 或 3A 判定为动态场景时启用降本），再做全量 A/B。

### 7.4 A/B 判据（必须可复现）

- 同一组场景（静态细节 / 手持抖动 / 夜景高 ISO / 运动物体）各 20 张，新旧对齐结果对比。
- 指标：对齐后 tile 位移矢量差异分布、merge 后暗部 σ、运动边缘伪影目视评分。
- **门限**：暗部 σ 劣化 ≤10%、无新的可见重影。

---

## 8. 红线与保护约束

> [!CAUTION]
> **R1：严禁删除 `ColorPipe.cpp` 中 `ColorMatrix1` 行乘 `AsShotNeutral` 的代码。**
> 它满足 DNG 1.7 契约 `CM1 · XYZ(校准白) = AsShotNeutral`，删除会导致全 App 的 DNG 在 Lightroom 中剧烈偏色。

> [!CAUTION]
> **R2：严禁按 CFA 去重排 `LensShadingMap` 的 R/B 通道。** LSC 通道序写死为 `[R, Ge, Go, B]`，与物理 CFA 无关；历史上曾因此引入全图四角彩虹/紫边并被紧急回滚。只有 `BlackLevelPattern` 需要随 CFA 平移重排。

> [!CAUTION]
> **R3：严禁为切换流目标而重建 `CameraCaptureSession`（O-16）。** 必须用动态 target 调度；重建会引发 150–400 ms 黑屏并破坏 3A 收敛。

> [!WARNING]
> **R4：流水化后必须严格保证 buffer 生命周期。** 把 24MB / 73MB 缓冲交给后台线程前，必须确认前置计算不再读写，并用引用计数约束"Halide 读者 + DNG 读者 + 池归还"三者顺序（O-5、O-10、O-13）。

> [!WARNING]
> **R5：不得污染 `HdrPlusBurst` 的 megaBuffer 契约。** 单帧链路引入池化时，不要复用连拍专用的 `megaBuffer` 管理逻辑，且保持 `MAX_POOL_SIZE` 与帧数上限自洽（`HdrPlusBurst.kt:40`）。

> [!NOTE]
> **R6：保持单帧"忠实极简"语义。** 由 `numFrames == 1` 推导的 `faithfulHighlights` 隔离（knee 关闭、高光中性化在 WB 与 clamp 之后）必须在任何重构后仍然成立；导出路径与处理路径必须传同一语义（历史评审曾漏传导出路径）。

---

## 9. 度量与验收方法

### 9.1 指标采集

- 应用内：补齐 O-2 后，用现有 `DebugLogManager` 输出结构化一行 JSON（便于脚本聚合）：`T1/T2/T3`、`halide.align/merge/demosaic/denoise`、`colorpipe`、`dng.encode`、`jpeg.encode`、`mediaStore.write`、`peakRssMB`。
- 系统级：Perfetto（`camera`、`halide`、`binder_driver`、`freq`、`thermal`）+ 应用自定义 `Trace.beginSection`（建议加：`copyRaw`、`dngSerialize`、`enqueue`、`halide`、`export`、`save`）。
- 算法级：`simpleperf` 对比 Align 优化前后的热点占比（验证 O-17 是否真的改变了 CPU 分布）。

### 9.2 本轮必须回答的实测问题

1. 各阶段真实耗时占比（阶段 0 交付后即可回答）。
2. 单帧 `T1` 在"开/关 Motion Photo"与"前摄/后摄"下的差值（验证 O-1 / O-11 收益）。
3. 连拍的实际帧间隔（用相邻 `image.timestamp` 差值统计；当前未设 `SENSOR_FRAME_DURATION`）。
4. 流水化后的真实峰值 RSS（校准 §5.3 的预算与 `canAcceptNewTask` 门限）。
5. DNG `COMPRESSION_DEFLATE` 的第三方兼容性抽检结果。
6. 对齐降本对暗部 σ 与运动伪影的实际影响。

### 9.3 速度验收

- 每阶段结束，记录"同一机型 / 同一场景 / 同一设置"下的 `T1/T2/T3` 中位数与 P95，与上一阶段对比。
- 不接受"只看单次最快值"；连拍需连续 5 组取统计。

### 9.4 画质回归（速度优化不得踩的线）

- 灰卡/白墙中性：`R/G、B/G ÷ ASN = 1.00 ± 0.02`；8×8 空间极差 ≤1.5%。
- 高光：品红像素占比不升高（`R/G>1.02 且 B/G>1.02`）；`≥254` 占比不高于基线 +1%。
- 暗部：σ 不劣化 >10%。
- DNG：`CM1 · XYZ(D65) ≈ AsShotNeutral`；在参考转换器里默认渲染亮度与 JPEG 一致。
- 四种产物（HDR+ JPEG / HDR+ DNG / 单帧 JPEG / 单帧 DNG）色彩一致性回归。

---

## 10. 依赖、风险登记与投入估算

| 风险 | 影响 | 缓解 |
|---|---|---|
| 流水化后线程超订 | 反而变慢、发热 | O-14 固定线程预算，Perfetto 验证 CPU 占用 |
| 73MB 缓冲跨线程泄漏/野指针 | 崩溃或花屏 | 池化 + 引用计数 + ASan / libc 泄漏钩子回归 |
| DNG fd 直写兼容度 | 第三方解码器打不开 | deflate 作为可配置项；先灰度 |
| 对齐降本画质下降 | 暗部噪点 / 重影 | §7.3 场景门限 + §7.4 A/B 判据 |
| 埋点改造引入开销 | 抵消收益 | O-3：默认关闭 profiler 报告，仅调试开关开启 |

**投入估算**：阶段 0–1 约 2–3 人日；阶段 2 约 5–8 人日；阶段 3 约 4–6 人日；
阶段 4（ZSL / 对齐重写 / 向量化）为独立里程碑，需单独立项与真机数据支撑。

---

## 附：本方案与源文档的差异一览

| 源文档条目 | 本方案处理 |
|---|---|
| `GUIDELINES` §2 阶段一（4 项快赢） | 保留 O-1 / O-4 / O-6 / O-7；**删除**"补 `-O3`"（已生效）与"fast 档短路"（已闭环） |
| `GUIDELINES` §2 阶段二（埋点） | 收窄为 O-2（仅导出段与单帧计时），并新增 O-3（profiler 报告按需） |
| `GUIDELINES` §2 阶段三（流水化 / I/O） | 落为 O-9 / O-10 / O-11 / O-12，并补齐背压与内存预算设计 |
| `GUIDELINES` §2 阶段四（零拷贝 / 对齐 / 元数据） | 落为 O-13 / O-14 / O-15 / O-16 / O-17 |
| `GUIDELINES` H10（移除 `-profile`） | **修正**：`-profile` 是分级埋点数据源，改为按需开关（O-3） |
| `ANALYSIS` §3.6 路线图 | 保留 P0 / P1 / P2 骨架，重排为 O-1…O-22，并补齐每项证据、验收与风险 |

> 复现要点：本文所有代码引用均在 `main` @ `b89d3945` 上核验；`nm` 结论基于
> `app/.cxx/RelWithDebInfo/316t5s4b/arm64-v8a/generated/hdrplus_*_pipeline.a`；
> 编译标志结论基于同目录 `build.ninja` 中 `ColorPipe.cpp.o` 的 `FLAGS` 行。
> 本次未修改任何源码（`git diff` 为空）。
