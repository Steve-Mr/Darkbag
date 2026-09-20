# PR #301 独立审查报告 —— `perf: optimize camera capture processing pipeline & restore telemetry`

- PR：<https://github.com/Steve-Mr/Darkbag/pull/301>（作者 Steve-Mr，Jules 生成）
- 审查对象：head `f4abc18b`（branch `perf-optimization-quick-wins-382899872944456894`），base `origin/main` = `b89d3945`
- 规模：9 文件，+518 / −173
- 审查方式：主控 agent + 5 个并行 subagent（原生 C++ / Kotlin 服务与保存链 / CameraFragment 异步 DNG / 需求符合度矩阵 / Android 官方文档与库文档核验）。所有结论均可回溯到源码 `path:line`、编译产物实测、官方文档引用或 `[需实测]` 标注。
- 证据附件：`.review/A_native.md`、`.review/B_kotlin.md`、`.review/C_camera.md`、`.review/D_requirements.md`、`.review/E_docs.md`（`.review/00_lead_findings.md` 为主控独立复核记录）

---

# 第一部分 简明版（TL;DR）

## 结论

**不建议直接合并（Request changes）。** 方向正确、部分关键项确实落地，但存在 **4 项功能性/可靠性回归**、**1 项可永久卡死快门的竞态**、**描述与代码严重不符**（9 条声明里 6 条在最终 diff 中不存在），并且夹带了 CI 产物体系变更。

## 必看三句话

1. **实现度**：需求文档 `HDRPLUS_PIPELINE_ANALYSIS.md` §3.6 的 P0 清单 7 项中，真正由本 PR 完成 4 项（S1 伪慢路径、H4 导出段埋点、两级流水、H15 `-O3`）；1 项（H5 fast 档 YUV 往返）**在 base 上本来就已经不存在**（文档结论过时，已用 `llvm-nm` 复核）；2 项缺失（S5 LUT 缓存 + S7/S8 死代码与常量外提、H10 `-profile`）。**阶段四 0%，色彩缺陷 0%**。按文档自己的耗时口径，估计恢复 P0 预算的 ≈40–70%（[需实测]）。
2. **收益集中在非主流场景**：S1 的 0.4–0.9 s/张只对**前摄或变焦**生效；S2 的 100–300 ms 只对**单帧 + 存 RAW**生效；两级流水主要改善**连拍 5 张的 1–3 s 串行尾**。**后摄 1× 的常规 HDR+ 拍摄在计算段几乎没有改善** —— 最贵的对齐层 0（≈3.0×10⁹ 次 SAD、0.15–0.45 s）、未压缩 DNG I/O（216 MB/张、0.2–0.6 s）、Motion Photo await（0.75–2.5 s）全部未动。
3. **方向对、执行错**：先修埋点 → quick wins → 流水化，符合文档 §3.6 的排序；问题在于（a）把高风险改动（I/O 重写、异步解耦、CI 变更）**未在描述中声明**地夹带进来，（b）廉价且已声称完成的项（LUT 缓存、常量外提、死循环删除、`-profile`）实际没做，（c）新改动自身带回归。

## 必须修（合并前）

| # | 级别 | 问题 | 关键位置 |
|---|---|---|---|
| B1 | **BLOCKER** | Motion Photo 静默降级为静图 + 临时 mp4 永不删除（开启 Motion Photo 后，默认保存路径必现） | `HdrPlusProcessingService.kt:144-150,253-255,278-279`；`ImageSaver.kt:68-73,137,763` |
| B2 | **BLOCKER（竞态）** | `lifecycleScope` 在排队导出启动前被取消 ⇒ `onTaskFinished()` 永不执行 ⇒ `pendingTasksCount` 永久卡死 ⇒ **快门永久禁用（只能重启 App）**，同时泄漏 75 MB 原生缓冲 | `HdrPlusProcessingService.kt:134,289-301`；`HdrPlusRequestManager.kt:75,84,87-94`；`CameraFragment.kt:1450-1460`；`HdrPlusJNI.cpp:686-687,388-392` |
| B3 | **BLOCKER** | 处理阶段异常路径不再 `stopForeground/stopSelf` ⇒ 前台服务与通知永久残留（base 的 `finally` 覆盖了该路径） | `HdrPlusProcessingService.kt:303-309` vs base 的 `finally` |
| B4 | **BLOCKER 级逻辑漏洞** | `mediaStoreJpgUri` 与 `jpgFd` 双源独立推导：插入成功但 `openFileDescriptor` 返回 null 时，native 把真图写进 cache，而 `finishMediaStoreJpeg` 把**空的 pending 行发布出去** ⇒ 相册 0 字节 + 真照片变孤儿 | `HdrPlusProcessingService.kt:144-153,171,196,255` |
| B5 | **MAJOR** | `fwrite/fflush/fclose` 返回值全部未检查且恒返回 `true`，写出被截断的 JPEG 会被当作成功并发布（旧路径至少有尺寸校验） | `ColorPipe.cpp:55-61,1310-1317,1348`；`HdrPlusJNI.cpp:478` |

## 建议修（合入前或紧随其后）

| # | 级别 | 问题 | 关键位置 |
|---|---|---|---|
| M1 | MAJOR | 内存峰值显著抬高而准入阈值未动：导出/处理分线程后各自持有 `thread_local` 大缓冲（12 MP 约 +108 MB）、每个已完成未导出的请求常驻 ~75 MB Halide 结果（最多 maxQueue=3/8 ⇒ 225–600 MB）、单帧另加 24 MB `allocateDirect` 深拷贝 | `ColorPipe.cpp:921-922,1431,1461`；`HdrPlusJNI.cpp:126,686-687`；`CameraFragment.kt:1857,4491`；`HdrPlusRequestManager.kt:92` |
| M2 | MAJOR | Halide（cores−2）与导出线程的全核 OpenMP 并发 ⇒ CPU 超额订阅；且导出线程被设为 `THREAD_PRIORITY_BACKGROUND`，低于处理线程 `DEFAULT+2` —— 流水线收益未被证实，可能反而拉长“看到成片”的时间 | `HdrPlusJNI.cpp:584-591`；`ColorPipe.cpp:837,1200,1243,1440`；`ColorProcessor.kt:19,33` |
| M3 | MAJOR | 导出失败时新插入的 MediaStore pending 行不删除（同仓已有正确做法） | `HdrPlusProcessingService.kt:145,196,287-288` vs `ImageSaver.kt:791-794` |
| M4 | MAJOR | 异步 Bayer DNG 与完成信号无 join：UI/动画可能先报完成，DNG 失败仅打日志并静默丢失 | `CameraFragment.kt:1867,1909-1911`；对比 base 同步写 |
| M5 | MAJOR | CMake `-O3` 修复**机制错误**：AGP 8.7 把 release 映射为 `RelWithDebInfo`，`_RELEASE` 两行是死代码；注释“NDK release toolchain defaults to -O2”也是错的（`-O2` 来自 CMake `GNU.cmake:59` 的 RelWithDebInfo 默认值） | `CMakeLists.txt:287-297` |

## 范围外 / 描述不符（必须澄清）

- **CI 从 `assembleDebug` 改成 `assembleRelease`**（`.github/workflows/build.yml:45,50`）：产物更名、丢失 debug 变体的 CI 覆盖、新增 `lintVitalRelease` 失败面、secrets 缺失时产出未签名 release APK。**且实测证明 Debug 变体本来就是 `-O3`**（`app/.cxx/Debug/4j703035/arm64-v8a/build.ninja`），所以该 CI 变更对“性能可测性”并非必要。
- **PR 描述 9 条声明：3 条为真、1 条机制与描述不符、5 条在最终 diff 中不存在**（见 §2.2）。其中 `get_cached_lut` 未被采用、`-profile` 仍在、死循环仍在、`pow` 仍在逐像素调用、`StandardTimingTracker` 仍从未赋值。
- **整条原生 DNG-fd 通道是死代码**（`dngFd` 恒为 −1），约 40 行不可达代码，并内含一个 fd 泄漏。

## 需求实现度总表（按文档分组）

| 阶段 | 项数 | ✅ | 🟡 | ❌/➖ | 按文档耗时的价值覆盖 |
|---|---|---|---|---|---|
| 阶段一 quick wins | 4 | 2 | 0 | 1 ❌ + 1 ➖（H5 本就已具备） | ≈45–65% of 0.5–1.5 s/张 `[需实测]` |
| 阶段二 telemetry | 2 | 1 | 0 | 1 ❌ | 0 ms，但排障前提只满足一半 |
| 阶段三 pipelining/IO | 3 | 1 | 1 | 1 ❌ | ≈35–55% |
| 阶段四 native zero-copy / align | 3 | 0 | 1（既有） | 2 ❌ | 0% |
| `HDRPLUS` §3.6 P0 | 7 | 4 | 0 | 1 ➖ + 2 ❌ | ≈40–70% `[需实测]` |
| `HDRPLUS` §3.6 P1 | 6 | 1 | 0 | 5 ❌ | ≈5–10% |
| `HDRPLUS` §3.6 P2 | 5 | 0 | 0 | 5 ❌ | 0% |

---

# 第二部分 详细版

## 1. 审查范围与证据基线

- 代码：`git diff origin/main..f4abc18b`（9 文件）。已确认本地分支与远端 PR head 同一 commit（`gh pr view 301 --json headRefOid` = `f4abc18b…`）。
- 需求文档：`docs/GUIDELINES_PERFORMANCE_OPTIMIZATION.md`（113 行，问题项 P1-1..P1-8 + 四阶段路线 + 红线）、`docs/HDRPLUS_PIPELINE_ANALYSIS.md`（490 行，结构性瓶颈 B-0/B-0′/B-0″ + S1–S10 + H1–H17 + §3.6 P0/P1/P2）。
- 编译产物实测：`app/.cxx/RelWithDebInfo/316t5s4b/arm64-v8a/build.ninja`、`app/.cxx/Debug/4j703035/arm64-v8a/build.ninja`（用于判定 `-O3` 是否生效）；`llvm-nm`（NDK r27）用于判定 fast 档是否仍做 YUV 往返。
- 官方文档：KB（MediaStore `IS_PENDING`/`openFileDescriptor`、权限、`ExifInterface`、`DngCreator`、`Image.close()`）+ libjpeg-turbo `turbojpeg.h`/`jcparam.c` + libtiff `TIFFOpen.3tiff`/`tif_open.c`/`tif_close.c`（逐条引用与原文摘录见 `.review/E_docs.md`，共 28 条引用）。

## 2. 需求实现度评估（问题二：是否实现要求？实现了多少？思路对吗？）

### 2.1 逐项落地情况

**已落地（有实证）**

| 文档项 | 内容 | 证据 |
|---|---|---|
| S1 | ImageSaver 伪慢路径 + 双镜像 | `ImageSaver.kt:88-90`；调用方 `isAlreadyCropped=true`（`HdrPlusProcessingService.kt:277`） |
| H2（=S1 的 HDR+ 侧） | 同上 | 同上 |
| S2 | Bayer DNG 抽出关键路径（改成独立协程 + 深拷贝） | `CameraFragment.kt:1855-1913`（base 同步写） |
| S/H4 + 阶段二② | `exportHdrPlus` 增加 `debugStats`，导出段可观测 | `HdrPlusJNI.cpp:378,466-472`；`ColorProcessor.kt:202-207` |
| B-0 / 阶段三① | 两级流水：导出移出 Halide 处理线程 | `ColorProcessor.kt:30-42`；`HdrPlusProcessingService.kt:133-134`（重叠性已证明：`launch` 不是子协程，`collect` 不等待） |
| 阶段三②（JPEG 半） | JPEG 经 `ParcelFileDescriptor` 直写 MediaStore | `ImageSaver.kt:626-688`；`HdrPlusProcessingService.kt:144-153,196-215`；`ColorPipe.cpp:18-61,1428-1460` |
| H15 | release 变体恢复 `-O3` | `CMakeLists.txt:287-297`；实测编译行 `… -fopenmp -O3 -ffast-math -O3 -g -DNDEBUG -O3 -ffast-math -fPIC`（无 `-O2`） |

**未落地（文档明确列入 P0/阶段一）**

| 文档项 | 内容 | 现状 |
|---|---|---|
| S5 | LUT 改走 `get_cached_lut`（20–400 ms/张，全文档最便宜的一处） | ✘ `HdrPlusJNI.cpp:425,652` 仍 `load_lut`；`get_cached_lut`（`ColorPipe.cpp:731`）仅被既有 rawvideo 路径使用 |
| S7 | 删除 `calculate_adaptive_edge_comp` 死循环 | ✘ 与 base **逐字节相同**（`ColorPipe.cpp:822-888`，`omp` 循环仍在 `:837-857`，仍无条件调用 `:924`，`enabled=false` 在 `:884`） |
| S8/H7 | 外提 `std::pow(2.0f, exposure)`/`eff_gain`（50–200 ms） | ✘ 仍在 `process_pixel` 内逐像素执行（`ColorPipe.cpp:1020-1021`，调用点 `:1216,:1258`） |
| H10 | 发布版去掉 Halide `-profile` | ✘ `CMakeLists.txt:137` 仍 `…-vk_int16_profile`，`halide_profiler_report` 仍每张调用（`HdrPlusJNI.cpp:639-640`） |
| 阶段二① | `StandardTimingTracker.jniDone/firstOutputWritten` 赋值 | ✘ 仍只声明未赋值（`CameraFragment.kt:456-457`，仅被 `:2006,2010,2011` 读取）⇒ 报告里这两行仍是负值/垃圾值 |
| 阶段三②（DNG 半）、③ | DNG 直写 fd / `ROWSPERSTRIP` / Deflate | ✘ DNG fd 通道不可达；`ROWSPERSTRIP` 仍为 1（`ColorPipe.cpp:1675`） |
| 阶段四①③、H1 | Native 带 stride 零拷贝、characteristics 缓存、动态 YUV target、对齐层 0 抽样 | ✘ 全部未动 |
| 色彩缺陷 C1–C7 | — | ✘ 0%（本 PR 未触及色彩数学，**这是好事**：未引入色彩回归） |

### 2.2 PR 描述 vs 实际代码（真值表）

| # | 描述声明 | 判定 | 证据 |
|---|---|---|---|
| a | ImageSaver 判定引入 `isAlreadyCropped` | **TRUE** | `ImageSaver.kt:88-90` |
| b | 在 service 里把 `mirror` 置 `false` | **FALSE**（用改被调方语义替代） | service 中只有 `req.mirror`（`:85,115,177,271`），无 `mirror = false`；且 `ImageSaver.kt:36-37` 的 KDoc 仍要求调用方传 false ⇒ 契约与实现不一致 |
| c | 删除 `calculate_adaptive_edge_comp` 死循环 | **FALSE** | 与 base 逐字节相同（见 2.1） |
| d | 外提 `pow(2.0f, exposure)`/`eff_gain` | **FALSE** | `ColorPipe.cpp:1020-1021` |
| e | `-O3 -ffast-math` 强制生效 | **TRUE（机制错误）** | 生效的是 `_RELWITHDEBINFO` 两行；`_RELEASE` 两行是死代码；`-ffast-math` 在 base 就已生效，非本 PR 引入 |
| f | 从 `HALIDE_TARGET` 去掉 `-profile` | **FALSE（反向证据）** | `CMakeLists.txt:137` 与 base 一致；生成的 ninja 仍含 `-profile` |
| g | `load_lut` 改 `get_cached_lut` | **FALSE** | `HdrPlusJNI.cpp:425,652` |
| h | `exportHdrPlus` 增加 `debugStats` | **TRUE** | `HdrPlusJNI.cpp:378,466-472` |
| i | 更新 `StandardTimingTracker` 填时间戳 | **FALSE** | `CameraFragment.kt:456-457` 从未赋值；该文件在本 PR 中只有一个 hunk（`:1853-1915`） |

**3 TRUE / 6 FALSE。** 描述第 2 组 4 条子声明只有 CMake 一条为真，`-profile` 一条被构建产物直接反证。

### 2.3 文档本身的过时结论（审查中发现的额外事实，必须澄清）

1. **H5（fast 档 RGB→YUV→RGB，文档称 288 MB / 100–300 ms）在 base 就已不存在**：`hdrplus_pipeline_generator.cpp:369` 有 `if (num_passes <= 0) return input;`，且 fast 档由 `denoise_passes=0` 生成（`CMakeLists.txt:186-193`）；`llvm-nm` 实测 `hdrplus_fast_pipeline.a` 中 `rgb_to_yuv`/`yuv_to_rgb` 符号数均为 **0**（raw/high 各 4 个）。⇒ 该项应从文档 Top-7 中移除，也不应作为本 PR 的缺口。
2. **H1 的描述部分过时**：`align.cpp:30-32` 已用 `prev_alignment` 做层间先验中心（`prev_offset = DOWNSAMPLE_RATE * clamp(P(prev_alignment(...)))`），文档“未结合前一层矢量”不成立；但 ±4/尺寸 8（64 个候选位移）×256 像素的穷举仍在，≈3.0×10⁹ 次 SAD 的结论成立。
3. **C1（`BaselineExposure` 截断到 0.5 EV）在 base 已是 `0..4`**（`ColorPipe.cpp` base:1614 → head:1759）。色彩类结论未逐条复核，本 PR 未触及该域。

### 2.4 “思路正确吗？”

**方向正确、排序正确、执行不完整。**

- 优点：严格按文档 §3.6 的顺序推进（先埋点 → quick wins → 流水化），抓住了“值/成本”最高的 S1、两级流水，以及唯一的编译期红利 H15；并且**没有**去动文档自己推迟到 P1/P2 的高风险项（H1 对齐抽样需要画质 A/B、H3 需要 DNG 格式/兼容性验证）。
- 问题：真正的廉价高收益项（S5 20–400 ms、S7/S8 50–200 ms、H10）恰恰被漏掉，而它们在描述里**被声称已完成**；同时把更贵更危险的 I/O 重写与异步解耦**未申报地**塞进同一个 PR。
- 净效果：后摄 1× 常规 HDR+ 场景（最常见）在计算段几乎无改善；前摄/变焦与连拍尾段有可见改善（幅度 `[需实测]`）。

## 3. 代码质量、架构与正确性（问题一）

### 3.1 正确性 / 功能回归

**B1（BLOCKER）Motion Photo 静默降级 + 临时文件泄漏**
新直写分支的进入条件是 `shouldSaveJpg && req.jpgFolderUri == null && req.hfMetadata == null`（`HdrPlusProcessingService.kt:144`）——**默认设置即满足**。该分支完全跳过 `ImageSaver.saveProcessedImage`（`:253-255`），因此：
- `motionPhotoMp4Path/motionPhotoStillPtsUs`（`:278-279`）不再被消费 ⇒ 视频轨不写入；
- `ImageSaver` 内部唯一的清理点（`ImageSaver.kt:68-73` 半幅模式下删除、`:137/763` 嵌入后删除）不会执行 ⇒ cache 里的临时 mp4 永久残留（仅清 App 数据可回收）。
Motion Photo 是用户可见开关（`SettingsFragment.kt:573,869`，默认 false），`RawImageHolder.motionPhotoMp4Path`（`CameraFragment.kt:472`）→ `HdrPlusRequest`（`:1982-1983`）链路是活的。**这是本 PR 最确定的功能回归。**

**B2（BLOCKER，竞态）`pendingTasksCount` 可永久卡死 ⇒ 快门永久禁用 + 75 MB 原生泄漏**
- 每个请求的**唯一** `onTaskFinished()`/`stopSelf()` 位于被 `launch` 出去的导出协程的 `finally`（`:289-301`）。
- `pendingTasksCount` 是进程级（`HdrPlusRequestManager.kt:75,84`）。若 `lifecycleScope` 在排队中的导出真正开始前被取消（服务销毁；窗口 = 前一个导出的整个时长 = 秒级），协程体永不执行 ⇒ 计数永不回零。
- 后果 1：`canAcceptNewTask`（`HdrPlusRequestManager.kt:87-94`）在 `pending >= maxQueue` 时恒为 false ⇒ `CameraFragment.kt:1450-1460` 直接 `return@setOnClickListener` 并弹 “Queue full: processing 1 photos, please wait...”，**任何后续拍摄都无法进行，只能重启 App**。
- 后果 2：Halide 结果向量的清理只在 `exportHdrPlus` 内发生（`HdrPlusJNI.cpp:388-392`），插入在 `:686-687`。导出永不执行 ⇒ 该 75 MB（`width*height*3*2`）共享块在 `g_sharedMemoryMap` 中永久驻留且无上限。
- 修复建议：把完成记账与 `launch` 解耦（`try { launch } catch { onTaskFinished() }`，或 `CoroutineScope(NonCancellable)`，或把 native map 的清理放到 `processHdrPlus` 的失败/超时路径上），并给 `g_sharedMemoryMap` 加容量上限。

**B3（BLOCKER）处理阶段异常 ⇒ 前台服务与通知永久残留**
base 把这些放在 `finally`，覆盖所有路径；新代码把 `stopForeground/stopSelf` 搬进导出协程的 `finally`，而外层 `catch`（`:303-309`）只做 `releaseBuffer` + `onTaskFinished()`。任何在 `launch` 之前发生的异常（native `bad_alloc`、JNI 异常等）都会让 “Processing 1 photo...” 通知常驻，且服务因 `START_STICKY`（`:58`）继续存活。

**B4（BLOCKER 级逻辑漏洞）`mediaStoreJpgUri` 与 `jpgFd` 双源独立推导**
`:144-150` 建行成功 ⇒ `mediaStoreJpgUri != null`；`:152-153` `openFileDescriptor` 返回 null ⇒ `jpgFd = -1` ⇒ `:171` 让 native 把真 JPEG 写到 `req.fullResJpgPath`；随后 `:196` 因 `mediaStoreJpgUri != null` 调 `finishMediaStoreJpeg`，**把 0 字节的 pending 行发布出去**（`IS_PENDING=0`，`ExifInterface` 对空文件报错被吞）；而 `:255` 又因 `mediaStoreJpgUri != null` 跳过 `saveProcessedImage` ⇒ 真图留在 cache 成为孤儿，用户看到一张空图。建议用一个布尔（`directFdWrite`）统一决定 `:171`/`:196`/`:255`。

**B5（MAJOR）截断的 JPEG 被当作成功发布**
`write_jpeg_turbo_fd` 不检查 `fwrite/fflush/fclose`，且恒 `return true`（`ColorPipe.cpp:55-61`）→ `:1348` → `HdrPlusJNI.cpp:478` 返回 0 → Kotlin 发布行。旧路径至少校验了落盘尺寸（base `ColorPipe.cpp:1257-1263`），新 fd 分支把这一步去掉了。建议检查 `fwrite` 返回值并在失败时返回 false；同时补 `ftruncate`（`fdopen(dup,"wb")` **不会**截断，`ContentResolver` 的 `"rw"` 也**不是** `"rwt"`——官方文档把 `"rwt"`/`O_TRUNC` 列为覆盖写场景；当前 `targetUri` 恒为 null，故属潜在缺陷）。

**M4（MAJOR）异步 DNG 的完成/失败语义**
DNG 写入选在 detached `applicationScope`（`CameraFragment.kt:1867`），不与任何完成信号 join；UI 动画与 `backgroundSaveFlow` 可能先报完成；失败只 `Log.e`（`:1909-1911`）；`ImageSaver` 在源文件缺失时静默跳过 RAW（`ImageSaver.kt:328`）。另外 `allocateDirect` 的 OOM 是 `Error` 而非 `Exception`，不在任何 catch 内。**深拷贝本身是必要且正确的**（证据：holder 缓冲即 `request.megaBuffer`，`CameraFragment.kt:1933` → 服务 `:130` `HdrPlusBurst.releaseBuffer` → 进入 `bufferPool`（`HdrPlusBurst.kt:40-83`）可被后续连拍复用 ⇒ 异步读会与复用竞争）；但更省的等价做法是引用计数/池化移交（正是文档 S2/S6 的处方）。

### 3.2 并发、生命周期与内存

- **重叠成立**：`processRequest` 通过 `lifecycleScope.launch` 火忘（fire-and-forget），`collect` 不等待 ⇒ 第 N 张导出与第 N+1 张 Halide 并行。数据面安全（导出读的是每请求私有的 native 结果向量；requestId 为 UUID）。
- **M1 内存**：① 两阶段分线程后 `thread_local` 缓冲各自持有（`processedImage` 6 B/px + `rgb8` 3 B/px，`ColorPipe.cpp:921-922,1431,1461`，从不释放）⇒ 12 MP 约 +108 MB；② 每个“已算完未导出”的请求常驻 ~75 MB，上限 = `maxQueue`（3/8）⇒ 225–600 MB，而 base 最多 1 份；③ 单帧另加 24 MB `allocateDirect`（`:1857`），叠加既有的 24 MB（`:4491`）与 3×24 MB 池；④ `canAcceptNewTask` 的 600 MB 阈值（`HdrPlusRequestManager.kt:92`）未随之调整。⇒ 峰值 RSS 与“内存不足拒绝拍摄”的发生率都可能恶化 `[需实测]`。
- **M2 CPU/优先级**：Halide `cores-2`（`HdrPlusJNI.cpp:584-591`）与导出线程的全核 OpenMP（`ColorPipe.cpp:837,1200,1243,1440`，全仓无 `omp_set_num_threads`/`OMP_NUM_THREADS`）并发 ⇒ 超额订阅；导出线程是 `THREAD_PRIORITY_BACKGROUND`（`ColorProcessor.kt:33`），低于处理线程 `DEFAULT+2`（`:19`），OpenMP worker 的 nice 值又依赖首次创建者 ⇒ 解耦去掉了**串行**但保留了**争抢**，“总吞吐是变好还是变差”必须实测（Perfetto）。
- 其他：新增的 `exportProcessingDispatcher` 永不关闭（daemon 线程，可接受）；未引入 `GlobalScope`/`runBlocking`；`TIFFSetTagExtender` 为 libtiff 全局量、被两个线程写同一值（形式竞争，实际良性，建议提到 `JNI_OnLoad`）；`halideThreadsConfigured` 的 check-then-set 仍由单线程 dispatcher 保护。

### 3.3 原生与 I/O

- **编码参数零变化（好事）**：`write_jpeg_fd` 是 `write_jpeg` 的逐字节拷贝，`write_jpeg_turbo_fd` 是 `write_jpeg_turbo` 的拷贝；`TJSAMP_422`、preview q78 / 全分辨率 q95、`TJFLAG_FASTDCT`、`tjCompress2` pitch=0、RGB8 转换与 TPDF 抖动全部一致 ⇒ **文件大小/画质无回归**，风险只在 fd 与错误处理。
- **M5 CMake**：AGP 8.7 将 Gradle `release` 变体映射为 `CMAKE_BUILD_TYPE=RelWithDebInfo`（`.cxx/RelWithDebInfo/…` 目录名即证据；AGP `CreateCxxVariantModel`/`CxxAbiModelSettingsRewriter`），因此 `CMakeLists.txt:289-290/293-294`（`_RELEASE`）对本项目无效，真正起作用的是 `_RELWITHDEBINFO` 两行；注释“NDK release toolchain defaults to -O2”不成立（NDK r27 把 `CMAKE_C_FLAGS_RELEASE` 清空，`-O2` 来自 CMake `GNU.cmake:59` 的 RelWithDebInfo 默认值）。另外这些旗标**不影响** libtiff（子目录在 `:42` 早于该块加入）与 Halide 生成代码。
- **死代码**：`dngFd` 恒 −1（`HdrPlusProcessingService.kt:189`），`write_dng` 另一调用点省略该参数（`HdrPlusJNI.cpp:696` ⇒ 默认 −1），无 JNI 绑定 ⇒ `ColorPipe.cpp:1645-1653` 与 6 个 `tiff_fd_*` 回调（`:1591-1616`）全部不可达；其中 `TIFFClientOpen` 失败路径泄漏 dup 的 fd（libtiff 失败路径只调 `TIFFCleanup`，它不调 `closeproc`）。
- **静默 DNG 失败**：`write_dng` 返回值在两个调用点都被丢弃（`HdrPlusJNI.cpp:446,696`），`process_and_save_image` 也只返回 `jpgOk`（`tiffOk` 计算后即丢）⇒ DNG 写失败仍返回成功。
- **遥测口径**：`outColorPipeMs`（`ColorPipe.cpp:916→1295`）仍包含 `calculate_adaptive_edge_comp` 的全图扫描（`:924`）与输出缓冲分配/缺页；16→8 位转换在全分辨率分支计入 JPEG 桶（`:1440-1454`）、在 preview 分支计入 ColorPipe 桶（`:1218-1227`）⇒ 两个数跨分支不可比。`SetLongArrayRegion` 越界处理正确（`HdrPlusJNI.cpp:465-472`，数组 `LongArray(15)`）；但 slot 5 在原文档里是 “DNG Wait”（`ColorProcessor.kt:232`）现被复用为 “Total Export”，slot 6 “Total” 从不打印，且 RAW-only 导出会打印 “ColorPipe 0ms / JPEG 0ms”（`:452` 分支未进入）。

### 3.4 可维护性

- JPEG 写出函数 2×2 重复（`ColorPipe.cpp:18/64`、`:1428/1458`，各 30–40 行），仓库已有 `encode_rgb8_jpeg`（`:99`）可复用。
- `isAlreadyCropped` 语义被扩展为“已裁剪且已镜像”，与 `ImageSaver.kt:33-37` 的 KDoc 冲突；GUIDELINES:68-70 明确要求调用方传 `mirror=false`，PR 选择改被调方语义。且修复不完整：慢路径 `ImageSaver.kt:160` 仍用原始 `mirror` 而非 `effectiveNeedsMirror`（潜在双重镜像，需要 `rotationDegrees!=0 && mirror==true && isAlreadyCropped==true` 同时成立）。
- 新增第二条保存实现（`prepareMediaStoreJpegUri`/`finishMediaStoreJpeg` + 手动 `backgroundSaveFlow.tryEmit`），需要与 `ImageSaver` 长期同步；插入字段与 `saveJpegToMediaStore` 逐字段一致（`ImageSaver.kt:645-646`），但写 `WIDTH/HEIGHT` 属于官方文档标注为派生/只读的列（当前调用传 null，属死路径）。
- `IS_PENDING=0` 设在 EXIF 重写**之前**（`ImageSaver.kt:675-688`）⇒ provider 派生列可能快照到 EXIF 之前的文件 `[需实测]`。
- `DngCreator` 从未 `close()`（`CameraFragment.kt:1871`，既有问题；`MultiCameraCaptureManager.kt:1352` 有正确示范）。

## 4. 修改范围外的影响

| 变更 | 影响 | 判定 |
|---|---|---|
| CI `assembleDebug` → `assembleRelease`（`build.yml:45,50`） | 产物 `app-debug`→`app-release`（下游可能依赖名称）；丢失 debug 变体 CI 覆盖；release 组装会附跑 `lintVitalRelease`（新失败面，`app/build.gradle` 无 lint 配置）；secrets 缺失时产出未签名 release APK（glob 仍匹配，CI 仍绿）。keystore 在 PR 运行时本来就会落盘且 debug 变体也用它签名（`build.gradle:91-94`），故密钥暴露面增量有限。**实测证明 Debug 变体本来就是 `-O3`** ⇒ 与 `-O3` 修复无关。 | 范围外，建议独立 PR 或回退 |
| 新增 `ColorProcessor.exportHdrPlus` 形参（`jpgFd/dngFd/debugStats`） | 唯一调用方已同步；Kotlin↔C++ 形参逐位一致，无 ABI 问题 | 可接受 |
| 单帧 RAW 保存改为异步 | 失败从“同步抛出”变为“仅日志”；顺序保证丢失 | 需补错误上报 |
| 既有问题（非本 PR 引入，供作者参考） | manifest 无 `READ/WRITE_EXTERNAL_STORAGE`（`AndroidManifest.xml:29-36`）而 `minSdk=26` ⇒ Android 8/9 上的 MediaStore 插入可能 `SecurityException`（base 的 `saveJpegToMediaStore` 同样如此）；`DngCreator` 未 close | INFO，另开 issue |

## 5. 分阶段修复建议

**阶段 0（阻断项，合并前）**
1. B1：直写分支要么带上 Motion Photo（把 mp4 交给 native/后置 mux），要么在 `req.motionPhotoMp4Path != null` 时退回 `saveProcessedImage`；无论哪条都要保证临时 mp4 被删除。
2. B2：把 `onTaskFinished()/stopSelf` 与 `launch` 解耦；给 `g_sharedMemoryMap` 加清理/上限。
3. B3：把 `stopForeground/stopSelf` 恢复到覆盖所有路径的位置（等价于 base 的 `finally`）。
4. B4：用单一 `directFdWrite` 标志驱动 `:171/:196/:255`；失败时 `contentResolver.delete(uri)`（对齐 `ImageSaver.kt:791-794`）；`openFileDescriptor` 异常要回退到文件路径而不是丢图。
5. B5：检查 `fwrite` 返回值 + `ftruncate`；或直接复用 `ImageSaver` 的 `"wt"` 写入语义。

**阶段 1（收口与诚实性）**
6. 重写 PR 描述，删除 6 条不成立的声明；或补上 S5（`get_cached_lut` + 切换 LUT 时 `clear_lut_cache`）、S7、S8、H10 后再声称。
7. 修正 M5 的 CMake 块与注释（删掉无效的 `_RELEASE` 两行、修正原因说明），并在描述中说明只影响 `native-lib` 及其后的 libtiff。
8. 把 CI 变更拆成独立 PR。

**阶段 2（性能与内存）**
9. 内存：池化/引用计数替代 24 MB 深拷贝（S2/S6）；评估导出线程 `thread_local` 缓冲的复用（例如把 `process_and_save_image` 的暂存区改为按调用传入/全局带锁复用）；按新的在飞数量重估 600 MB 门限。
10. 线程：统一线程预算（`omp_set_num_threads` 与 `halide_set_num_threads` 协调），并重新考虑导出线程优先级。

**阶段 3（真正的大头，独立 PR + 画质 A/B）**
11. H1 对齐层 0 抽样/增量 SAD；H3 DNG Deflate + `ROWSPERSTRIP` 64–256 + DNG fd 落地（顺带删掉死代码或接通它）；S3 Motion Photo await 出关键路径；H14 `prepare()`/帧时长。

## 6. `[需实测]` 清单（决定数值结论）

1. 修复前后同一场景（后摄 1×、前摄、2×、单帧 RAW、5 张连拍）Perfetto trace：段耗时与重叠率；settle “流水化是否真的降低总时延”。
2. `-O2` vs `-O3` 同机同场景对比（隔离 Halide `.a` 不变）。
3. 峰值 RSS 与 `canAcceptNewTask` 拒绝次数（连拍 3/8 张压力测试）。
4. Motion Photo 开关下的 JPEG 是否含视频轨（`ffprobe`/Exif `MotionPhoto` XMP）。
5. 导出失败/中断场景下的 pending 行与通知状态；`IS_PENDING=0` 早于 EXIF 重写对派生列的影响。
6. 存储将满时的 JPEG 完整性（验证 B5 的实际影响）。

---

## 附录 A：证据索引（关键结论 → 证据）

- 重叠性成立：`HdrPlusProcessingService.kt:49-53,134`；数据面私有：`HdrPlusJNI.cpp:521-525,389-392,686-687`。
- 快门被卡死：`HdrPlusRequestManager.kt:75,84,87-94` + `CameraFragment.kt:1450-1460`。
- 深拷贝必要性：`CameraFragment.kt:1933` → `HdrPlusProcessingService.kt:130` → `HdrPlusBurst.kt:58-83`。
- `-O3` 实测：`app/.cxx/RelWithDebInfo/316t5s4b/arm64-v8a/build.ninja`（head 生效）vs `app/.cxx/Debug/4j703035/arm64-v8a/build.ninja`（Debug 本就 `-O3`）。
- H5 已具备：`hdrplus_pipeline_generator.cpp:369` + `CMakeLists.txt:186-193` + `llvm-nm hdrplus_fast_pipeline.a`（0 个 YUV 符号）。
- JPEG 参数不变：`ColorPipe.cpp:18-61` vs `:64-97`、`:1428-1460` vs `:1458-1486`。
- 官方文档核验：见 `.review/E_docs.md`（MediaStore pending/fd 生命周期 SUPPORTED；`"rw"` 无截断 NOT SUPPORTED；`saveAttributes()` 全文件重写 SUPPORTED；`DngCreator` 线程语义 UNCLEAR；libtiff 失败路径泄漏 fd NOT SUPPORTED）。

## 附录 B：审查产物

- `.review/00_lead_findings.md`（主控独立复核）
- `.review/A_native.md`（原生 C++/CMake）
- `.review/B_kotlin.md`（Kotlin 服务/保存链）
- `.review/C_camera.md`（CameraFragment 异步 DNG）
- `.review/D_requirements.md`（需求符合度矩阵，69 行）
- `.review/E_docs.md`（Android/库官方文档核验，28 条引用）
- `.review/pr301.diff`（被审 diff）
