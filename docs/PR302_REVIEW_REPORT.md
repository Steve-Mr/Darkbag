# PR #302 验收报告 —— 对 PR #301 审查发现的修复情况核验

- PR：<https://github.com/Steve-Mr/Darkbag/pull/302>，head `46a6d564`，**base 是 PR #301 的分支**（`perf-optimization-quick-wins-382899872944456894`），即 PR #301 的叠加 PR
- 规模：5 文件，+143 / −63（`CMakeLists.txt`、`ColorPipe.cpp`、`HdrPlusJNI.cpp`、`CameraFragment.kt`、`HdrPlusProcessingService.kt`）
- 声明：commit message 称修复 B1–B5、M1–M5 以及补齐 S5/S7/S8/H10
- 核验方式：逐项读 PR #302 分支的最终代码（`git show pr302:<path>`），并用已有编译产物做实证（`llvm-nm`、重新运行 `hdrplus_generator` 生成对照 `.a`、`javap` 校验 `DngCreator`）

---

## 1. 结论

**是，修掉了大部分阻断项，但远未"完成"。** B1/B3/B4/B5 真正修好（且经代码链验证），B2 用一个有效但偏门的手法修好；M3 随 B4 一并修好；S5/S7/S8/H10 本次真正落地。**M1/M2 完全未动，M4 只做了边缘修补，还有 6 项以上我提出的问题未处理**，并且 H10 的修法引入了一个**新的可观测性副作用**（Halide 分段埋点永久归零）。

## 2. 逐项验收

| 项 | 声明 | 核验结论 | 证据 / 说明 |
|---|---|---|---|
| B1 | Motion Photo 绕过直写 | ✅ **已修复** | `HdrPlusProcessingService.kt:144-153` 新增 `isMotionPhoto` 判定并加入条件；motion photo 时 `directFdWrite=false`（`:192` 让 native 写 `fullResJpgPath`）→ `:281-282` 走回 `saveProcessedImage`，`:306-307` 把 mp4 透传给 `ImageSaver` → 正常 mux 并删除临时 mp4 |
| B2 | 计数/内存永不泄漏 | ✅ **已修复（手法偏门）** | `:134` 改为 `lifecycleScope.launch(exportProcessingDispatcher + NonCancellable)`。`NonCancellable` 作为 context 里的 Job 会**取代** scope 的 Job 成为父 Job（`NonCancellable.attachChild` 返回 `NonDisposableHandle` 且恒 active），因此该协程不再随 service 生命周期取消，`finally` 必执行 → 计数必回零、native 75 MB 结果必被 `exportHdrPlus` 取走释放。建议改成 `CoroutineScope(dispatcher).launch{}` 或显式 try/catch 更直白 |
| B3 | 异常路径不停服务 | ✅ **已修复** | 抽出 `finishTaskAndCheckStop()`（`:330-342`），导出协程 `finally`（`:318`）与外层 `catch`（`:321-327`）都调用它。两条路径互斥，无双减计数 |
| B4 | uri/fd 双源不一致 | ✅ **已修复** | 引入 `directFdWrite = (mediaStoreJpgUri != null && jpgFd >= 0)`（`:164`）；fd 打开失败时删除 pending 行并把 `mediaStoreJpgUri` 置 null（`:167-174`）；`jpgPath`（`:192`）、`jpgFd`（`:209`）、发布判断（`:217`）、回退判断（`:281`）全部改由该单一标志驱动。`openFileDescriptor` 改为 `"rwt"`（`:157`）且捕获异常 |
| B5 | 静默截断的 JPEG | ✅ **已修复** | `ColorPipe.cpp:55-75`：`ftruncate(dup_fd,0)`（`:57`）+ 检查 `fwrite`（`:59-62`）`fflush`（`:64-66`）`fclose`（`:68-70`）并 `return write_ok`（`:75`）；同类检查也补到了 `write_jpeg_turbo`（FILE* 路径）。失败链正确收敛：`write_jpeg_fd` → `process_and_save_image` 返回 false → JNI 返回 −2 → Kotlin `exportRet != 0` → 删行不发布 |
| M1 | 内存峰值/600 MB 门限 | ❌ **未处理** | 无 `ColorPipe` 暂存复用、无 `g_sharedMemoryMap` 上限、无池化、`HdrPlusRequestManager.kt` 与 `ColorProcessor.kt` 未被本 PR 触碰。仍在飞 `maxQueue`(3/8) × ~75 MB + 双线程 `thread_local` 临时缓冲（~108 MB@12MP） |
| M2 | CPU 超额订阅/优先级 | ❌ **未处理** | 无 `omp_set_num_threads`，导出线程优先级仍为 `THREAD_PRIORITY_BACKGROUND` |
| M3 | 失败时 pending 行泄漏 | ✅ **已修复** | `:236-243` `else if (exportRet != 0 && directFdWrite && mediaStoreJpgUri != null)` → `contentResolver.delete(...)`（`:239`） |
| M4 | 异步 DNG 完成/失败语义 | 🟡 **仅边缘修补** | 已做：`CameraFragment.kt:1871` `DngCreator.use{}`（`javap` 证实 `DngCreator implements AutoCloseable`，编译无虞）+ `:1910` `catch (e: Throwable)`。**未做**：与完成信号 join（UI 仍可能先报完成）、失败仍只打日志、`allocateDirect` 的 OOM 仍在 try 之外 |
| M5 | CMake 机制/注释 | 🟡 **注释已改，逻辑未动** | 注释改为 "AGP 8.x maps Gradle release build to `CMAKE_BUILD_TYPE=RelWithDebInfo`"（`CMakeLists.txt:286-287`）；`_RELEASE` 两行仍在（无害，因为 outcome 由 `_RELWITHDEBINFO` 保证）。注意：本仓库同时存在 `app/.cxx/Release/582f144j`（`CMAKE_BUILD_TYPE:STRING=Release`，FLAGS 亦为 `-O3`），说明该绝对化注释仍不严谨；两个分支都被覆盖，故 `-O3` 结论不变 |
| S5 | LUT 走缓存 | ✅ **真正落地** | `HdrPlusJNI.cpp:427`（export）与 `:663`（process）改为 `get_cached_lut` 优先、miss 才 `load_lut`；且 `LutCacheManager::get()` 确为 **miss 即加载并写缓存**（`ColorPipe.cpp:616-627`），因此不是"永远 miss"的假修复 |
| S7 | 跳过死扫描 | ✅ **落地且安全** | `ColorPipe.cpp:949-950` 不再调用；`AdaptiveEdgeComp` 有默认成员初始化（`enabled=false`，`:799-806`），故 `AdaptiveEdgeComp edgeComp;` 无未初始化读取。副作用：`calculate_adaptive_edge_comp`（`:847-912`）变成**死函数未删** |
| S8 | 外提循环不变量 | ✅ **落地** | `ColorPipe.cpp:952-954` 外提 `exp_gain/eff_gain`，`process_pixel` 为 `[&]` 捕获且定义在常量之后 → 语义与编译均正确 |
| H10 | 去掉 `-profile` | ⚠️ **实现，但有副作用** | `CMakeLists.txt:138` 已改为 `...-vk_int16`。我实测重跑 `hdrplus_generator` 对照：`halide_profiler_report/reset` 在去 profile 后**仍是 weak 定义**（`prof_without.a`）→ **不会链接失败**；但采样/插桩符号（`halide_profiler_set_current_func`、`_acquire_sampling_token`、`_incr/_decr_active_threads` 等 7 个）消失 → 不产生任何分段数据。而 `HdrPlusJNI.cpp:648-649,737` 仍无条件 `halide_profiler_report` 并用其结果填 `debugStats[7..14]`，`HalideStageStats` 默认全 0 → **HDR+ 报告里的 Align/Merge/Demosaic/Denoise/sRGB/BlackWhite/WB 将恒为 0 ms，且没有任何"profiling 已关闭"的标记**。这正是本 PR 上一轮刚恢复、也是验证 H1（最大剩余项）所必需的埋点。另外 `CMakeLists.txt:137` 的注释写的是 "in release builds"，但该改动是**对所有构建类型**生效的（Debug 也没有 profile），这正是副作用无条件发生的原因 |

## 3. 仍未处理（来自 PR #301 报告）

1. **M1 内存**：双线程 `thread_local` 暂存重复（`ColorPipe.cpp:946-947,1459,1489`）、未导出结果常驻 ~75 MB × maxQueue、无 `g_sharedMemoryMap` 上限、600 MB 门限未重估。
2. **M2 线程**：Halide `cores−2` 与导出线程全核 OpenMP 并发；导出线程仍是 `THREAD_PRIORITY_BACKGROUND`。
3. **阶段二① 埋点**：`CameraFragment.kt:456-457` 的 `jniDone/firstOutputWritten` 仍从未赋值 ⇒ 报告 "JNI (Halide + FastJPG)" / "Total (to First Output)" 仍是负值垃圾。
4. **DNG fd 死代码 + fd 泄漏**：`dngFd = -1` 仍在（`:210`），`ColorPipe.cpp` 的 `TIFFClientOpen` 分支与 6 个回调仍不可达，`TIFFClientOpen` 失败仍泄漏 dup fd。
5. **静默 DNG 失败**：`write_dng` 返回值在 `HdrPlusJNI.cpp:455,714` 仍被丢弃。
6. **范围外 CI 变更**：`.github/workflows/build.yml` 未被触碰，`assembleDebug → assembleRelease` 仍在（丢 debug 覆盖、新增 `lintVitalRelease` 失败面、无 secrets 时产出未签名 release APK）。
7. **MINOR 未动**：`isAlreadyCropped` 与 `ImageSaver.kt:33-37` KDoc 语义冲突、慢路径 `ImageSaver.kt:160` 仍用原始 `mirror`（潜在二次镜像）、`IS_PENDING=0` 早于 EXIF 重写、JPEG 写出函数 2×2 重复、`lseek`/`ftruncate` 返回值未检查、H5/H1/H3/S3/H14 等大项（均属独立 PR 范围）。

## 4. 本次新引入 / 需注意

1. **H10 的可观测性副作用**（见上表）：建议按构建类型区分（release 不插桩、debug/perf 变体保留 `-profile`，例如用 `option()` 或 `if(CMAKE_BUILD_TYPE STREQUAL "Debug")`），或在报告里对空 section 打印 `n/a (profiling disabled)`，否则 0 ms 会被误读为"该段不耗时"。
2. **`isMotionPhoto` 谓词不一致**：`:144` 用 `exists()`，而 `ImageSaver` 用 `exists() && length() > 0`。0 字节 mp4 时会不必要地走慢路径（且该 mp4 不被删除——此点与 base 相同，非本次引入）。建议直接复用 `ImageSaver` 的判定。
3. **`catch (e: Throwable)`**：会吞掉 `CancellationException` 与 `Error`（OOM/StackOverflow）并仅记日志。作为"不让服务卡死"的兜底可以接受，但建议至少对 `CancellationException` 重新抛出。
4. **`calculate_adaptive_edge_comp` 成为死函数**：建议删除（连同匿名 namespace 里的辅助常量），否则编译告警与阅读负担常驻。
5. **`clear_lut_cache()` 全仓无调用者**：缓存永不失效。当前 LUT 导入/重命名都保证文件名唯一（`LutManager.kt:30-33,53-55`），且 `lutPath` 含文件名，故风险低；但若未来出现"同路径覆盖内容"，会出现旧 LUT 生效到进程重启为止的问题。
6. **未验证项**：Kotlin 编译未跑（沙箱禁止写 `~/.gradle`，Gradle wrapper 无法创建 lock 文件）；已逐项核验新构造而非依赖编译：`DngCreator implements AutoCloseable`（`javap` 于 `android-35/android.jar`）、`mediaStoreJpgUri` 为同一 lambda 内局部 `var` 无嵌套闭包（smart cast 合法）、`NonCancellable` 作为 context Job 的语义（kotlinx 标准语义）。原生侧 H10 的链接安全性已用重新生成的 `.a` 实证。

## 5. 建议

- **可以合并的前提**：把 H10 改为按构建类型 gate（或恢复 `-profile` 并接受开销），否则"埋点恢复"与"去掉 profile"互相抵消；其余阻断项已达标。
- **紧随其后**：M1/M2（内存与线程预算）与 `StandardTimingTracker` 仍应作为独立 PR 处理；M1 是当前最可能造成 OOM 与"内存不足拒绝拍摄"的一项。
- **证据附件**：`.review/pr302.diff`（被审 diff）、`.review/halidetest/prof_with.a` 与 `prof_without.a`（H10 对照产物，可用 `llvm-nm` 复现）。
