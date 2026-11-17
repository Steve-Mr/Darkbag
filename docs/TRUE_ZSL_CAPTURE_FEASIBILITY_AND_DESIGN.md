# Darkbag 真 ZSL（零快门延迟）拍摄：可行性评估与实施方案 v2.1

> **状态**：设计定稿（待批准后进入实施）。**本轮仍未改动任何代码**，仓库中唯一新增物是本文件（未提交）。
> **基线**：分支 `refactor/datastore-and-arch-hardening` @ `cdb8d52e`
> **口径**：`当前行为`（读代码得到的事实，带 `file:line`）与`建议改动`严格分开；无法由读代码证明的量标 `[需实测]` 并给出测法与判据。

## 版本变更（v1 → v2 → v2.1）

| # | 新增/修订 | 落点 |
|---|---|---|
| 1 | 所有实施在**新分支**上进行，阶段化原子提交 + 立即可推送 | §4 |
| 2 | **自动性能探测系统**（主动 8–10 s 自检 + 被动自调优），结论持久化并驱动开关 | §3.8、§3.9 |
| 3 | ZSL 作为**正交能力**与现有模式并行，收在 Experimental 分组，**前向采集永久兜底** | §3.7 |
| 4 | 官方最佳实践清单 + **implementer / reviewer 双 Agent 闭环** | §3.10、§6 |
| 5 | 每阶段**可考核目标 + 证据包 + 反半途而废清单 + 机器断言** | §5 |
| 6 | **高内聚低耦合**：端口/适配器、文件预算、依赖方向、禁止清单 | §3.1–§3.3 |
| **7（v2.1）** | **纠正前提**：不存在"相机侧设置面板"；设置页是独立全屏目的地，进入即关闭相机会话（证据 §1.10） | §1.10、§3.8.2 |
| **8（v2.1）** | 新增**双界面模型**（设置页控制面 + 相机页执行面）与**完整 Bento 规范落地**（托盘角色/规格/文案/三态/线框/文件预算/禁止项） | §3.12 |

---

## 0. 结论与执行摘要

**可行**，且应做成"**可探测、可实验、可回退**"的能力，而不是一次大改：

| 档位 | 内容 | 覆盖场景 | 难度 | 工作量 |
|---|---|---|---|---|
| **Z1** | 单帧 ZSL（RAW 环取最新帧） | 非 HDR+ 抓拍 | 低–中 | 5–8 人日 |
| **Z2** | 低照 ZSL + HDR+ 混合（环给前 K 帧 + 前向补齐） | 弱光/室内，**感知收益最大** | 中 | +4–6 人日 |
| **Z3** | 全场景 ZSL + HDR+（欠曝环 + 取景数字增益） | 白天到夜景全覆盖 | 高 | +6–10 人日（含画质 A/B） |

**四个决定成败的判断**

1. **下游零改动**：`StreamingBurstResult` + `StreamingBurstCaptureContext` 契约本身就是"一组带元数据的 RAW 帧"（`processor/HdrPlusStreamingBurst.kt:11-25`），ZSL 只替换"帧从哪来"；原生逐帧累加（`app/src/main/cpp/HdrPlusStreamingSession.cpp:153-303`）与 push 后立即回收（`HdrPlusJNI.cpp:1341-1370`）天然适配历史帧。
2. **硬件是唯一真正的未知数**：满幅 RAW 持续输出带宽、热、以及"预览请求同时挂 RAW target"是否掉帧。→ 用 §3.8 的**探测系统**在实施早期拿到量化结论（并作为 S2 的决策门 D1），而不是靠猜。
3. **最大隐性难点是曝光一致性**，不是环形缓冲：HDR+ 要欠曝（`utils/ExposureUtils.kt:93-141`），取景走 AE（`camera/session/CameraHardwareStreamController.kt:109-128`），且预览 GL 管线**没有任何增益 uniform**（`processor/LutGlShaderPipeline.kt`）。关键洞察：ZSL 真正有价值的暗光区间（ISO≥800）现有欠曝因子本就是 **1.0**，所以 **Z2 零取景改动即可拿到主要收益**，Z3 才需要 `uGain` + 画质 A/B。
4. **ZSL 只压缩 T1**（按下 → 帧到手），**不改善 T2**（帧到手 → 落盘，仍 1–3 s）。

**分阶段与门槛**

| 阶段 | 目标 | 门槛 |
|---|---|---|
| **S0** | 地基重构 + 遥测骨架（无行为变更） | 全量测试绿 + 请求构造单一化断言 |
| **S1** | 能力探测 + 主动自检 + 实验开关 UI | ≤10 s 出结论；两次运行结论一致 |
| **D1** | **go / no-go 决策门** | 至少一台目标机判为 `SUPPORTED` 或 `RECOMMENDED` |
| **S2** | Z1 单帧 ZSL | T1 P50 ≤ 30 ms、零崩溃、可熔断 |
| **M1** | 实验可用（用户可开） | S2 全绿 + 证据包齐 |
| **S3** | Z2 低照 HDR+ 混合 | T1 ↓≥60% 且 SNR 不劣化 |
| **S4** | Z3 全场景 | T1 P50 ≤ 40 ms + §8 画质判据通过 |
| **M2** | 默认推荐 | S4 全绿 + 10 min 热/功耗达标 |

---

## 1. 现状事实基线

### 1.1 采集是严格"后触发"（forward capture）

- 单帧：`SingleRawCaptureExecutor.takeSinglePictureCamera2()` 现造一个 `TEMPLATE_STILL_CAPTURE` 请求（`camera/dispatch/SingleRawCaptureExecutor.kt:63-67`），`session.capture(...)` 下发（`:123`）。按下快门之前 RAW 流**根本不在跑**。
- HDR+ 连拍：`HdrPlusBurstCaptureExecutor.triggerHdrPlusBurstCamera2()` 造 N 个 `TEMPLATE_PREVIEW` 请求（目标 = RAW 面 + 预览面，`HdrPlusBurstCaptureExecutor.kt:329-356`），`session.captureBurst(...)`（`:265`）。
- 因此 T1 = N ×（曝光 + 满幅读出 + HAL 延迟）+ 逐帧拷贝。曝光被压到 ≤8 ms（`utils/ExposureUtils.kt:118,127-141`），但**满幅 RAW 读出**与**未设置 `SENSOR_FRAME_DURATION`** 让帧间隔完全由设备决定。旧审计量级：5 帧 166–350 ms 采集 + 40–80 ms 拷贝 `[需实测]`（`.agents/teamwork_preview_explorer_photo_1/photo_pipeline_investigation.md:452-457`）。
- 全仓库**没有**任何 ZSL 相关用法：`TEMPLATE_ZERO_SHUTTER_LAG`、`CONTROL_ENABLE_ZSL`、`CONTROL_CAPTURE_INTENT`、`SYNC_MAX_LATENCY`、`getOutputMinFrameDuration()`、`prepare()`、`StreamUseCase` 全部 0 处引用（`grep` 核实）。

### 1.2 会话与流：RAW 面已在会话内，但只被 capture 目标使用

- 会话：`CameraHardwareStreamController.createCamera2CaptureSession()`（`camera/session/CameraHardwareStreamController.kt:298-423`）：RAW reader 满幅 `RAW_SENSOR`、`maxImages = 8`（`:310-312`）；分析流 4:3 最小档 YUV、`maxImages = 2`（`:319-322`，用于动态欠曝的高光裁剪统计 `:324-352`）；会话目标 = 预览 + RAW + 分析（`:366-369`）。
- **关键**：RAW 面从会话创建起即属于该会话，但**从不进入 repeating request**；预览 repeating 只挂预览 +（HDR+/手动时的）分析流（`:380-396`、`:157-181`）。
- 平台保证：`PRIV(PREVIEW) + YUV(PREVIEW) + RAW(MAXIMUM)` 是 RAW 能力设备的**保证组合**（"Preview with in-app processing and DNG capture"，附录 A5）——"预览同时持续出 RAW"在 API 契约层面合法，无需重建会话。

### 1.3 帧数据通路：每帧 24 MB 拷贝（既有模式，可复用）

- 12 MP RAW16 = 4000×3000×2 B = **24 MB/帧**。
- `HdrPlusStreamingBurst.addFrame()` 在 Camera2 回调线程把 Bayer 数据 `copyBayerWithStride` 进池化 direct buffer（`processor/HdrPlusStreamingBurst.kt:297-372`，拷贝 `:318-340`），随即 `image.close()`（`HdrPlusBurstCaptureExecutor.kt:237-239`）。KDoc 称 ~2 ms/帧（`:294-296`）`[需实测]`。
- 缓冲池上限 12 个（`processor/StreamingBufferPool.kt:14`），单 buffer 24 MB → 池化常驻上限 ~288 MB。

### 1.4 元数据：时间戳精确匹配可用，但缓存无上限

- `handleCaptureResult()` 按 `SENSOR_TIMESTAMP` 把 `TotalCaptureResult` 存入 `runtimeState.captureResults`（`CameraHardwareStreamController.kt:183-195` → `camera/state/CameraRuntimeStateHolder.kt:67-73`）。
- 匹配先精确命中、再按 5 ms 容差扫（`camera/dispatch/CameraCaptureMetadataExtractor.kt:59-63`，容差 `:303`）。5 ms < 30 fps 的 33 ms 帧间隔 → **不会串帧**，这是 ZSL 复用现有元数据链的前提。
- ⚠️ `captureResults` **无任何淘汰**，仅关会话时 `clear()`（`camera/session/CameraSessionArbiter.kt:172-183`）。RAW 环 30 fps 跑起来会持续膨胀 → 必须改有界 LRU。

### 1.5 处理管线接缝：只换"帧从哪来"

- 采集→处理的唯一握手是 `StreamingBurstResult(sessionHandle, frames)` + `StreamingBurstCaptureContext`（`processor/HdrPlusStreamingBurst.kt:11-25`）。
- 之后：`HdrPlusBurstProcessingEnqueuer.processStreamingHdrPlusBurst()`（`camera/dispatch/HdrPlusBurstProcessingEnqueuer.kt:148-`）→ `CaptureTaskSpec` → `HdrPlusRequest` → `HdrPlusRequestManager`（`Channel(16)`，`processor/HdrPlusRequestManager.kt:229-230`；600 MB 内存门限 `:271-285`）→ 前台服务 → 原生 `finish` + `ColorPipe` → `CaptureSink`（`pipeline/sink/CaptureSink.kt`）。

### 1.6 原生累加是逐帧增量式

- `HdrPlusStreamingSession` 构造时只预分配参考帧（`app/src/main/cpp/HdrPlusStreamingSession.cpp:77`），此后每 `pushFrame` 立即做一次 Halide 累加（`:153-303`）。
- `nativePushStreamingFrame` 同步读取 direct buffer（`HdrPlusJNI.cpp:1341-1370`）→ push 返回后 Java 侧 buffer 可立即回收（现有 worker 即如此，`HdrPlusStreamingBurst.kt:82-85`）。
- 另有低成本清晰度评分 `nativeComputeGcamFrameScore`（512×512 中心区，`HdrPlusJNI.cpp:1372-1460`），已被"自适应基准帧"复用（`HdrPlusStreamingBurst.kt:98-108,176-193`）→ ZSL 帧选择可直接继承。

### 1.7 仓库已有"持续 RAW 流"先例（最重要的去风险证据）

RAW 视频把 `rawImageReader.surface` 挂进 repeating request 持续出帧：`CameraVideoController.startRawVideoRecording()`（`camera/video/CameraVideoController.kt:126-135`）并**显式锁帧率**（`CONTROL_AE_TARGET_FPS_RANGE` + `SENSOR_FRAME_DURATION`，`:137-143`），每帧回调进原生环形缓冲（`:119-123` → `rawvideo/RawVideoSessionManager.kt:209-284`），目标 24 fps（`:65-66`）。→ 同一会话、同一 reader 已具备持续吃满幅 RAW 的工程路径 `[需实测：实际 fps 与掉帧率]`。

### 1.8 历史 "ZSL" 命名≠真 ZSL（避免重复劳动）

| 提交 | 内容 | 实质 |
|---|---|---|
| `ae973307` / `35b6ce62` | "ZSL fast preview" 用 480p YUV 出占位图 | **占位预览** |
| `804c469f` | "ZSL Dark / Bright" 曝光模式 | HDR+ 欠曝档位命名 |
| `704de22d` / `9cf1a6cf` | 占位图与产物的分组/去重 | 落盘逻辑 |
| `fast-exposure-zsl-experiment-*` | 软件 AE 闭环 + 短曝光 | 缩短曝光，非环缓冲 |

残留 `HdrPlusRequest.zslTargetUriStr` 恒为 null（`processor/HdrPlusRequestManager.kt:47,193`）。结论：**无可复用的真 ZSL 实现**，但有可复用的环形/生产者-消费者范式（`motionphoto/CircularVideoRingBuffer.kt:99-166`）。

### 1.10 设置页是独立全屏目的地，进入即关闭相机会话（决定 UI 方案的前提）

v2 里"相机侧设置面板"的提法有误，实际情况：

- 相机 Bento Deck 顶部的 `settings_button`（`res/layout/camera_ui_container.xml:683-700`，与 Playground 按钮同一行、各 `weight=1`）点击后执行 `actionCameraToSettings` 导航到**全屏** `SettingsFragment`（`fragments/CameraFragment.kt:371-374`），**没有**相机的设置 BottomSheet。
- 离开相机页时 `CameraControllerHub.onStop()` 会 `closeAllSessions(releaseSessionController = true)`（`camera/CameraControllerHub.kt:260-271`）→ `CameraDevice` 直接关闭。
- 相机页返回时 `onResume` 按 `requiresSessionRebind` 重新 `bindCameraUseCases()`（`CameraControllerHub.kt:323-338`），会话配置完成会回调 `CameraUiHost.onSessionConfigured()`（`camera/session/CameraHardwareStreamController.kt:398-400`）——**这是"回到相机后执行探测"的现成挂点**。

因此探测不能"在设置页用实时会话跑"，必须采用 §3.12 的**双界面模型**（设置页下指令 → 相机页执行）。

设置页现有 Bento 事实（用于新增 UI 对齐）：全屏 `fragment_settings.xml`（1749 行）由 `BentoTrayCard` 托盘组成，已用角色：startup=`surfaceContainerHigh`（`:95`）、capture=`secondaryContainer`（`:249`）、half_frame=`tertiaryContainer`（`:457`）、color=`tertiaryContainer`（`:599`）、advanced=`surfaceContainerLow`（`:677`，内含 primary/secondary/tertiary/surfaceContainer 子托盘）、about=`surfaceContainerHigh`（`:1481`）、debug/实验=`errorContainer`（`:1594`，默认 `visibility=gone`，开发解锁后可见，内含 `BentoSwitchTile`、`Widget.Darkbag.BentoTile.Button`、等宽统计卡）。

---

## 2. 路线选型

| | **A. 设备托管 ZSL** | **B. 应用自托管 RAW 环（主方案）** | **C. HAL 重处理（CameraX 同款）** |
|---|---|---|---|
| 机制 | `CONTROL_ENABLE_ZSL = true` + `CAPTURE_INTENT = STILL_CAPTURE`，HAL 用历史帧出图 | 自己把 RAW 面挂进 repeating，环存最近 K 帧，快门直接取 | `TEMPLATE_ZERO_SHUTTER_LAG` + `createReprocessableCaptureSession` |
| 前提 | 设备实现可选键（`getAvailableCaptureRequestKeys()`） | 满幅 RAW 可持续输出（§1.7 先例） | `PRIVATE_REPROCESSING`/`YUV_REPROCESSING` |
| 多帧 RAW | ❌ 每请求 1 帧，ZSL 缓冲多为 PRIVATE/YUV | ✅ 任意 K 帧原始 Bayer | ❌ 输出一般仅 JPEG/YUV |
| 与 HDR+ 兼容 | 仅能优化单帧路径 | ✅ 直接喂 `HdrPlusStreamingSession` | ❌ 进不了 HDR+ 原生管线 |
| 可控性 | 低（HAL 黑盒，"not guaranteed"） | 高 | 中 |
| 结论 | **作为 S1 探测项 + 可选加速** | **主方案（S2–S4）** | **不采用** |

官方口径一致：
- `TEMPLATE_ZERO_SHUTTER_LAG` = "intended for **application-operated** ZSL；device-operated 用 `CONTROL_ENABLE_ZSL`"，且只对 `PRIVATE_REPROCESSING`/`YUV_REPROCESSING` 设备**保证支持**（附录 A1）。
- `CONTROL_ENABLE_ZSL` 仅作用于 STILL_CAPTURE 意图，"may use images captured in the past"，且 **"not guaranteed"**（附录 A2）。
- `CONTROL_CAPTURE_INTENT_ZERO_SHUTTER_LAG` 官方描述正是方案 B：**"the application will stream full-resolution images and reprocess one or several later for a final capture"**（附录 A3）。

---

## 3. 架构设计（高内聚、低耦合）

### 3.1 硬性设计规则

**① 依赖方向（单向，禁止回流）**

```
fragments / ui / viewmodel            （只依赖 delegate + binding）
        ↓
camera/delegate (Camera*Controller, hosts)   （组装、状态、UI 适配）
        ↓
camera/dispatch (executors, enqueuer)        （采集编排放，不碰 Camera2 细节）
        ↓
camera/zsl (纯能力层)  ─────────────►  camera/session 的“端口接口”、processor、pipeline/model、repository
```

- `camera/zsl/**` **禁止** import：`fragments`、`ui`、`viewmodel`、`databinding`、`camera/delegate`、任何 Android UI 类。
- `camera/zsl/**` 与 Camera2/会话之间只通过**端口接口**通信（`ZslSessionPort`、`RepeatingRequestPort`、`RawFrameListenerPort`），由 `camera/session` 侧实现适配器。这样 zsl 可脱离 Robolectric 硬件桩单测。
- 反向注入（`delegate → zsl`）只允许在 `CameraControllerHub` 的装配处出现一次。

**② 端口与适配器（Ports & Adapters）**

| 端口（定义在 `camera/zsl`） | 生产实现（定义在 `camera/session`/`delegate`） | 测试替身 |
|---|---|---|
| `RepeatingRequestPort { apply(targets, sensor, fps, crop); restore() }` | `CameraSessionRepeatingAdapter`（包装 `CameraHardwareStreamController`） | `FakeRepeating` |
| `RawFrameListenerPort { start(onFrame); stop() }` | `RawImageReaderAdapter`（独立 RAW HandlerThread） | `FakeRawSource`（可编排时序/丢帧） |
| `ZslMetadataPort { resultFor(ts): TotalCaptureResult? }` | `CameraCaptureMetadataExtractor` 适配 | `FakeMetadata` |
| `ZslSchedulerPort { io(); compute() }` | 现有 `HdrPlusAccumulationDispatcher` + 专用 IO 线程 | 直调 |
| `ZslReportStore { load(); save() }` | `SettingsRepository`（DataStore 单键 JSON） | `InMemoryReportStore` |

**③ 文件与类预算（reviewer 强制检查）**

| 约束 | 阈值 |
|---|---|
| `camera/zsl/**` 单文件 | ≤ 300 行 |
| 任一新增类 | ≤ 250 行；单类 public 方法 ≤ 12 |
| 既有文件增长 | `CameraControllerHub` ≤ +25 行；`HdrPlusBurstCaptureExecutor` ≤ +45；`SingleRawCaptureExecutor` ≤ +30；`CameraHardwareStreamController` **必须净减少** |
| 预览请求构造 | **单相机会话预览 repeating** 请求只在 `PreviewRequestFactory` 内构造：`CameraHardwareStreamController`/`CameraLensController`/`CameraVideoController` 的 `TEMPLATE_PREVIEW` 计数为 0（对焦取消/触发、多摄、前摄 PiP 的请求不在此列） |
| 单测数量 | S2 起每阶段新增 ≥ 12 个行为测试；`zsl` 包每个 public 类型 ≥ 3 个 |

**④ 禁止清单（出现即打回）**

- ❌ `CameraFragment` / `CameraControllerHub` / `CaptureModeCoordinator` 内出现 `if (isZsl)`。
- ❌ 新增"上帝类"：把环、探测、治理、请求构造塞进同一个类；或让 `ZslStreamController` 负责 UI/持久化。
- ❌ 在 `CaptureTaskSpec` 里继续加原始布尔/数字参数（只允许 1 个 `inputPolicy` 值对象）。
- ❌ 环内做阻塞分配、`synchronized` 包裹拷贝、或在 UI 线程做任何 camera IPC。
- ❌ 以"实验特性"为由跳过测试、跳过回落路径、跳过证据包。

### 3.2 组件清单（新包 `camera/zsl/`）

| 文件 | 职责（单一） | 预估行 | 依赖 | 关键测试 |
|---|---|---|---|---|
| `ZslPolicy.kt` | 值对象：`ZslMode{Disabled,Auto}`, `ZslExposure{ AeContinuous, FixedBurstExposure }`, `RingConfig(depth,fps,minValidFrames)`；`fromReport(report)` | ~70 | 无 | 报告→策略映射 |
| `ZslCapability.kt` | 静态能力：硬件级别、RAW 支持与尺寸、`getOutputMinFrameDuration`、`SYNC_MAX_LATENCY`、`CONTROL_ENABLE_ZSL` 是否可用、capabilities 集合 | ~120 | `CameraCharacteristics`（只读） | 表驱动（各种能力组合） |
| `ZslRingBuffer.kt` | 固定深度环：`push(frame)`, `detach(): List<OwnedFrame>`, `invalidate(epoch)`, `stats`；**唯一所有权**，无 refcount | ~180 | `StreamingBufferPool` | 淘汰顺序/detach 后不可见/并发 push-detach/epoch 失效 |
| `ZslRingEpoch.kt` | 有效性标识（lensId、crop、mirror、曝光配置、orientation） | ~40 | 无 | 相等/变更判定 |
| `ZslStreamController.kt` | 武装/解除状态机、空闲计时、熔断、与视频/多摄互斥；**不含**测量与 UI | ~200 | 3 个端口 + `ZslPolicy` | 幂等 arm/disarm、互斥、冷却重试 |
| `ZslExposureGovernor.kt` | AE 结果 → 目标固定曝光；阈值/滞回；触发 `invalidate` | ~150 | `ExposureUtils`（纯函数）+ `ZslMetadataPort` | 迟滞、跳变、手动覆盖禁用 |
| `ZslFrameSource.kt` | 快门取帧：`acquire(burstSize): Acquisition{Ring(frames) / Fallback(reason)}`；决定"环给 K 帧 + 前向补 N−K" | ~180 | 环 + 策略 | 回落矩阵全枚举、K 边界 |
| `ZslMetrics.kt` | 计数器/分位统计 + 单行 JSON 发射（固定 schema） | ~110 | 无 | 分位算法、JSON schema 稳定 |
| `ZslTelemetryFields.kt` | 遥测字段常量与版本（防止各阶段字段漂移） | ~50 | 无 | schema 版本断言 |
| `probe/ZslProbeController.kt` | 主动探测编排（三个测量窗口、超时、中止、恢复） | ~220 | `ZslProbeSession` 端口 + verdict | 正常/丢帧/超时/中止 |
| `probe/ZslProbeMeasurements.kt` | 采样聚合与分位（纯函数） | ~90 | 无 | 分位/边界 |
| `probe/ZslProbeVerdict.kt` | 阈值评分 → `RECOMMENDED/SUPPORTED/UNSUPPORTED` + 失败原因 + 推荐环参数 | ~110 | 无 | 阈值边界表驱动 |
| `probe/ZslProbeReport.kt` | 报告数据模型 + JSON 编解码 + 设备指纹 + 版本 | ~110 | 无 | 往返、缺字段兼容 |
| `probe/ZslProbeSession.kt`（端口） | 探测所需的相机操作抽象 | ~40 | 无 | — |

新增合计 ≈ 1,670 行（含测试另计），单文件全部 < 300 行。

**适配器（薄，放在既有包内或新目录 `camera/session/zsl/`）**

| 文件 | 内容 | 预估 |
|---|---|---|
| `RepeatingRequestPort` 实现 | 包装工厂，切换 RAW target / 帧率 / 还原 | ~80 |
| `RawFrameListenerPort` 实现 | 独立 `HandlerThread` + `ImageReader` 监听 + `acquireNextImage` + 关闭 | ~90 |
| `ZslReportStore` 实现 | `SettingsRepository` 单键 JSON | ~40 |

### 3.3 既有文件改动清单（含净行数预算）

| 文件 | 改动 | 预算 |
|---|---|---|
| `camera/session/PreviewRequestFactory.kt`（**新**） | 收敛 3 处重复的预览请求构造（见 R1） | ~130 新 |
| `camera/session/CameraHardwareStreamController.kt` | 改用工厂；**删掉**两段重复构造 | **净减少** |
| `camera/delegate/CameraLensController.kt` | 变焦改用工厂（否则丢 RAW target） | 净减少 |
| `camera/dispatch/HdrPlusBurstCaptureExecutor.kt` | 注入 `ZslFrameSource`：环帧优先 + 前向补齐 | ≤ +45 |
| `camera/dispatch/SingleRawCaptureExecutor.kt` | 单帧优先取环 | ≤ +30 |
| `camera/dispatch/CameraCapturePipelineDispatcher.kt` | 仅传参装配 | ≤ +15 |
| `camera/CameraControllerHub.kt` | 实例化 + 端口装配 + 模式策略查询 | ≤ +25 |
| `modes/CaptureModeCoordinator.kt` | `fun zslPolicy(): ZslPolicy = ZslPolicy.Disabled`（默认实现） | ≤ +6 |
| `modes/NormalModeCoordinator.kt` / `HalfFrameModeCoordinator.kt` | 覆写声明策略 | ≤ +8 / +8 |
| `camera/delegate/CameraModeController.kt` | 模式切换时通知 arm/disarm | ≤ +12 |
| `repository/PreferenceKeys.kt` / `DarkbagPreferences.kt` / `SettingsRepository.kt` | 3 个实验键 | ≤ +20 |
| `fragments/settings/SettingsBentoDelegate.kt` / `SettingsSheetDispatcher.kt` | 探测入口 tile + 详情 sheet | ≤ +120（拆到新文件更好） |
| `processor/HdrPlusStreamingBurst.kt` | `addOwnedFrame()`（零拷贝移交） | ≤ +25 |

### 3.4 状态机

```
Disarmed ──arm()──► Arming（等 AE 收敛 + 首帧） ──满 K──► Armed
   ▲                    │失败/超时/不支持                 │快门
   │                    ▼                                ▼
   └──disarm(reason)────┴────────── Recovering ◄──── Detached（交付环帧）
                                        │补满 K
                                        └──► Armed
```

- **Arming 前置门**（`ZslArmGate`）：会话 Active、AE 收敛（`CONTROL_AE_STATE == CONVERGED/LOCKED`）、非视频/多摄/PiP、非闪灯、内存充足、环配置与当前 epoch 匹配。
- **Recovering**：快门把环整体移交后为空；未补满前的第二次快门走"部分环 + 前向补齐"或全前向。
- **Disarmed(reason)** 枚举固定：`FlagOff / Unsupported / Video / MultiCam / FrontPip / Flash / LensChange / ZoomChange / Thermal / Memory / LowFps / SessionLost / Error`。每个 reason 都进遥测与熔断计数。

### 3.5 快门路径时序（今日 vs ZSL）

| 阶段 | 今日（forward） | ZSL |
|---|---|---|
| 点击 → 曝光决策 | `calculateHdrPlusExposure`（`HdrPlusBurstCaptureExecutor.kt:79-90`） | 武装时已算好并生效 |
| 构请求 + 下发 | N 个请求 + `captureBurst`（`:329-356,265`） | **无请求下发** |
| 等曝光/读出/回调 | N × 帧间隔（166–500 ms `[需实测]`） | **0** |
| 拷进池 | 每帧 24 MB（`HdrPlusStreamingBurst.kt:318-340`） | **0**（所有权移交） |
| 建原生会话 + push | 首帧到达后 | 立即（工作线程） |
| 取景器 | 连拍期间靠预览 target 勉强维持 + 黑场遮罩 | 不打断 |

**诚实说明**：ZSL 不能把帧变"更新"。按下瞬间最新帧必然已过"曝光→读出→传输"，落后 ≥1 个帧间隔（30 fps ≥33 ms，满幅 RAW 可能 66–100 ms）。准确语义是**"按下时刻或最多 1–2 帧之前"**；收益是把"等未来 200–500 ms"换成"取回过去 33–100 ms"。

### 3.6 曝光三档（技术核心）

现状：HDR+ 目标曝光按 ISO 欠曝（-1.5…0 EV）、快门锁 8 ms、不足则抬 ISO 至 4×min，再不足同时抬（`utils/ExposureUtils.kt:93-141`）；欠曝量作为 `digitalGain` 交给 ColorPipe（`HdrPlusBurstCaptureExecutor.kt:85-90`）。取景走 AE（`CameraHardwareStreamController.kt:109-128`）。预览 LUT 管线**无增益 uniform**（`LutGlShaderPipeline` 现有 uniform 仅 scale/texture/gamut/log/lut/colorEngine/focusPeaking/texelSize）。

| 档 | 环曝光 | 取景需改 | 合并合法性 | 场景 | 画质影响 |
|---|---|---|---|---|---|
| **Z1** | = 当前 AE 曝光（repeating 保持 `AE_MODE_ON`，只加 RAW target） | 否 | 单帧 | 非 HDR+ 抓拍 | 帧来自预览曝光，比今日 STILL_CAPTURE 暗约 1 档 `[需实测]` |
| **Z2** | = 计算出的 burst 曝光，**仅当 `digitalGain ≈ 1.0`（ISO≥800）才武装** | 否 | ✅ | 弱光/室内（正是快门延迟最痛、现有逻辑本就不欠曝处） | 合并 N 帧，SNR 优于今日单帧 |
| **Z3** | = 计算出的欠曝曝光（`AE_MODE_OFF` 定值） | **是**：GL 加 `uGain` 按 `1/digitalGain` 提亮 | ✅ | 全场景 | 高光保住；需画质 A/B |

- **曝光治理器**（Z2/Z3 必需）：~10 Hz 从 `captureResultFlow` 取最新 AE → 算目标 → 超 0.15 EV 阈值才重编程 → **重编程窗口作废环**；手动 ISO/快门时用用户值且禁止覆盖；闪灯不武装。
- **AWB**：环保持 AWB 自动（跟随场景），合并时用所选帧的结果里的 WB（与今日一致，`extractWbAndCcm`）；burst 内 AWB 锁定的做法保留给前向补齐段（`HdrPlusBurstCaptureExecutor.kt:353`）。

### 3.7 与现有模式并行 + Experimental 开关（要求 3）

**定位**：ZSL 是**正交的帧来源策略**，不是新模式、不改 UI 主流程；默认关闭，关闭时代码路径与基线**完全一致**。

| 模式/状态 | v1 支持 | 说明 |
|---|---|---|
| Normal（HDR+ 关） | ✅ Z1 | 单帧取环 |
| Normal（HDR+ 开） | ✅ Z2（S3）/ Z3（S4） | 低照优先，门控见 §3.6 |
| Half-Frame | ✅ 两步各自武装 | 帧1→帧2 有 800 ms 自动补满窗口（`CameraCapturePipelineDispatcher.kt:121-129`） |
| Motion Photo | ✅（静止帧取环） | 切片触发改用**被选中帧的 sensor 时间戳**（R-8） |
| 手动曝光（ISO 和/或快门手动） | ✅ FixedExposure | 用户值即定值，治理器不覆盖 |
| 闪灯（开或自动） | ❌ | 官方：闪灯下 ZSL 不成立（附录 A6） |
| Multi-Camera | ❌ v1 | 会话由 `MultiCameraCaptureManager` 掌管；保留 `zslPolicy()` 钩子待后续 |
| 前置 PiP | ❌ | 双设备并发 |
| RAW 视频 / MP4 视频 | ❌ 强制 Disarmed | 共用 `rawImageReader` |
| 变焦中 / 切镜头 / 方向变化 | ❌（epoch 变更） | 环立即作废并补满 |

**开关与文案（Invariant 5 合规）**

- 位置：**全屏 Settings** 新增顶层 Bento 托盘 `card_tray_zsl`（角色 `primaryContainer`、26dp、`strokeWidth=0`、内层 porcelain tile），插在 `card_tray_color` 与 `card_advanced_container` 之间；**不复用** `section_debug`（`errorContainer` 且默认隐藏，语义是调试）。完整规格、线框与文案见 §3.12。
- 探测执行面：相机页 `ZslProbeBottomSheet`；设置页只写 pending 并返回相机（§3.12.2）。
- 键：`zsl_experimental_enabled`（默认 `false`）、`zsl_probe_report`（JSON）、`zsl_probe_version`（int）、`zsl_probe_pending`（bool）。
- 三态呈现：`未检测`（可运行检测）/ `等待取景器`（pending）/ `支持`·`受限`·`不支持`（附推荐环参数或失败原因）。
- 文案：单语言字符串资源（`values/` + `values-zh-rCN/`），禁止双语拼接与冗余括注；遥测行 `maxLines=2` + `ellipsize=end`。
- **强制启用**：仅 `BuildConfig.DEBUG` 提供隐藏手势，用于工程验证，不写入用户可见 UI。

**前向采集永久兜底**：任一 `Disarmed(reason)` → 与今日完全相同的采集路径；开关关闭 → 基线等价（§5 有 parity 断言）。

### 3.8 自动性能探测系统（要求 2）

#### 3.8.1 目标

回答一个可执行问题：**"这台设备、这颗镜头、这个 RAW 尺寸，能否支撑 K 帧环，且不让取景器退化和过热？"** 一次点击 → ≤10 s → 结论 + 量化报告；结论可复用、可分享、可驱动开关；也可被 CI 之外的评审当作证据。

#### 3.8.2 入口与前置

- **双界面**（详见 §3.12）：控制面在**全屏 Settings** 的新 Bento 托盘 `card_tray_zsl`；执行面在**相机页**的 `ZslProbeBottomSheet`。设置页点"运行检测"只写 `zsl_probe_pending=true` 并返回相机，真正的探测由 `onSessionConfigured()` 挂点触发（原因此见 §1.10：设置页无活跃会话）。
- 前置：会话 `Active`、非录制/多摄/PiP、电量 **<10% 拒绝（10% 通过）**、`availMem ≥ 600 MB`、热状态 ≤ `MODERATE`；不满足即返回 `PRECONDITION_FAILED` 并给出原因（未知电量/热状态放行）。
- 全程遵守 Invariant 4：所有 camera IPC 在 camera handler；UI 只展示状态。

#### 3.8.3 测量序列（固定、可复现、可中止）

| 窗口 | 时长 | 动作 | 采集指标 |
|---|---|---|---|
| W0 基线 | 1500 ms | 保持现状（预览 + 分析流，无 RAW target） | 预览帧率基线（**从 `captureResultFlow` 计数：W0 的 repeating 仍由生产回调驱动，且以订阅时 replay 尾部为截断点排除历史帧**）、热状态、内存 |
| W1 双流 | 4000 ms | 工厂重建 repeating：追加 RAW target，锁 `SENSOR_FRAME_DURATION = 1/fps` 与 `CONTROL_AE_TARGET_FPS_RANGE=[fps,fps]`；RAW 监听只计数并 `close()`，同时向**单个复用 scratch buffer** 拷贝以量拷贝耗时 | RAW 帧数/帧率/间隔 P50/P95/max/抖动、帧龄 `(elapsedRealtimeNanos−sensorTs)` P50/P95、拷贝耗时 P50/P95、元数据命中率与延迟、预览帧率、`onCaptureFailed` 数、曝光漂移（ISO/time 标准差） |
| W2 恢复 | 1000 ms | 还原原始请求与监听（RAW target 是否仍在会话配置中取决于 S0 现状，见 §1.2） | **S1 定位为 settle window**：不做数值判据；同时充当 `CaptureResult` 晚到的 grace window —— **元数据绑定在 W2 结束后统一回查**，恢复正确性由真机 `DUMP` 与冒烟验证；W0→W1 工厂重建的 1–3 帧可能被多计/少计（方向保守，不造成同帧双计） |
| W3 补测（可选） | 1000 ms | 重新武装到"环满 3 帧"计时 | 环预热耗时 `warmLoadMs` |

- 总时长 ≈ 7.5 s（含 W3）；**看门狗 15 s**，超时或状态变化（来电、切后台、模式切换）立即中止并还原。
- **不写任何用户可见产物**：不产生 MediaStore 记录、不落盘照片（断言：探测前后 MediaStore 计数不变）。
- 中止/异常路径必须执行与正常路径相同的 `restore()`（finally 保证）。

#### 3.8.4 判定模型（阈值表，纯函数实现）

| 指标 | RECOMMENDED | SUPPORTED | 失败原因码 |
|---|---|---|---|
| `rawFps` | ≥ 24 | ≥ 15 | `RAW_FPS_LOW` |
| 帧间隔 P95 | ≤ 1.5× | ≤ 1.8× 标称 | `RAW_JITTER` |
| 最大间隔 | ≤ 4× 标称 | ≤ 6× 标称 | `RAW_STALL` |
| 帧龄 P95 | ≤ 100 ms | ≤ 140 ms | `FRAME_AGE_HIGH` |
| 拷贝 P95 | ≤ 6 ms | ≤ 12 ms | `COPY_SLOW` |
| 预览掉帧 | ≤ 10% | ≤ 20% | `PREVIEW_DEGRADED` |
| 元数据命中（≤100 ms） | ≥ 99% | ≥ 97% | `METADATA_MISS` |
| 环预热 | ≤ 200 ms | ≤ 400 ms | `WARMUP_SLOW` |
| 热状态（结束） | ≤ `LIGHT` | ≤ `MODERATE` | `THERMAL_RISK` |
| `onCaptureFailed` | 0 | ≤ 1 | `CAPTURE_FAILED` |
| 预检（录制/多摄/PiP/电量<10%/availMem<600MB/热>MODERATE） | 通过 | 通过 | `PRECONDITION_FAILED` |
| RAW 能力 | 支持 | 支持 | `RAW_UNSUPPORTED` |

> **元数据命中口径（S1 补丁）**：`metadataHitRate` 由「W2 之后逐帧按 `SENSOR_TIMESTAMP` 回查（精确 + 5ms 容差）」得出，**不是** RAW 回调瞬间的表命中率。HAL 允许结果晚于图像投递，瞬时判定会把「晚到」误判为「缺失」（真机报告曾出现 2.5% 的假阴性）。

派生推荐：`ringDepth = 3 if warmLoad ≤ 200 else 2`；`ringFps = if (rawFps ≥ 24) 24 else max(5, floor(rawFps/5)*5)`（即 ≥24 → 24；15–23.9 → 15；S1-B1 复审 R6 定稿，避免实测 24 被降到 20）；`minValidFrames = 2`。
附加信息（不参与判定，只入报告）：`hasDeviceZslKey`、`supportsPrivateReprocessing`、`syncMaxLatency`、`pipelineDepth`、`hardwareLevel`、RAW 尺寸、`minFrameDurationMs`。

#### 3.8.5 结果持久化与生效

- `ZslProbeReport{ version, timestamp, deviceFingerprint(Build.MODEL+MANUFACTURER+SDK), lensId, rawSize, verdict, metrics{}, recommended{ringDepth,ringFps,minValidFrames}, reasons[] }` → JSON 存 DataStore 单键。
- **失效规则**：`version` 变化、`lensId` 变化、`rawSize` 变化、`deviceFingerprint` 变化 → 视为无报告（需重测）。
- **生效规则**：开关可用前提 = 报告 verdict ∈ {SUPPORTED, RECOMMENDED}；环参数默认取报告推荐值；用户不可手调（避免自伤）。
- **被动自调优**（§3.9）会持续修正运行时行为，但**不改写**报告，只记 `lastRuntimeVerdict`。

#### 3.8.6 "自动"的两层含义

| 层 | 触发 | 行为 |
|---|---|---|
| 主动探测 | 用户点击"运行兼容性检测" | 上述 7.5 s 流程 + 报告 |
| **首次启用自动探测** | 用户首次打开实验开关且无有效报告 | 自动在会话空闲时运行探测，UI 显示进度；失败则开关自动回退并提示原因（不静默） |
| 被动自调优 | 常驻 | 武装期间每 10 s 聚合 RAW 帧率/间隔/掉帧；连续两个窗口不达标 → 熔断解除 + 冷却 10 s + 按需降级（K−1 / fps−5）；`Disarmed` 原因进遥测 |

#### 3.8.7 可测试性（CI 无相机）

- `ZslProbeController` 只依赖 `ZslProbeSession` 端口 → 单测用 `FakeProbeSession` 编造帧序列（正常/稀疏/抖动/丢帧/超时/中止/请求失败），覆盖全部状态迁移。
- 判定与统计是纯函数（`ZslProbeVerdict`、`ZslProbeMeasurements`）→ 表驱动边界测试（每条阈值两侧各一例）。
- 报告编解码纯 Kotlin → 往返 + 缺字段兼容测试。
- **真机数字只能进证据包**（§6.5），不能造假：要求原始 `adb logcat` 片段 + 设备指纹 + 时间戳。

### 3.9 被动自调优与熔断

- 指标窗口：10 s 滑动；判定：`rawFps < 0.6×期望` 或 `帧间隔 P95 > 1.5×` 或 `预览掉帧 > 20%`。
- 动作序列（自动、可观测）：降级（fps−5 → K−1）→ 仍不达标则 `Disarm(LowFps)`，冷却 10 s 后重试一次 → 再失败则本次会话内 `Disarmed(Unsupported)` 并写 `lastRuntimeVerdict`（不影响存储的探测报告）。
- 热：`PowerManager.getCurrentThermalStatus() ≥ SEVERE` → 立即 disarm；冷却后按需重新武装。
- 空闲：5 s 无交互（无对焦/变焦/参数变更）→ disarm，省电；有交互 → 300 ms 内重新武装（`warmLoadMs` 已由探测给出上界）。

### 3.10 与架构不变量 + 官方最佳实践的对齐

| 项 | 落实 |
|---|---|
| 不变量 1（CaptureSink / 统一 DNG） | ZSL 不碰 sink；帧仍经 `StreamingBurstResult` → `Enqueuer` → `CaptureTaskSpec` → 原生 `write_dng` |
| 不变量 2（域参数正交化） | 武装参数收敛为 `ZslPolicy` 值对象；`CaptureTaskSpec` 仅增 1 个 `inputPolicy` |
| 不变量 3（模式策略隔离） | `CaptureModeCoordinator.zslPolicy()` 默认 `Disabled`；Normal/HalfFrame 自行声明；无 UI 分支 |
| 不变量 4（无头会话/零 UI 阻塞） | 全部 IPC 在 camera handler；原生会话创建/push 在既有累加线程；快门路径 O(1) |
| 不变量 5（Bento / M3 Expressive） | 实验 tray 用语义容器 + porcelain 内层 + 圆角/间距规范；单语言；遥测 `maxLines=2` |

**官方最佳实践对照**

| 实践 | 采用方式 | 依据 |
|---|---|---|
| 会话/流组合合法性 | 只复用既有 3 流组合，不新增流 | 保证组合表（附录 A5） |
| buffer 预分配 | **S0 不做**：公开 API 只有 `prepare(Surface)`，会按 `maxImages` 全量预分配（8×24MB≈192MB）；`prepare(int, Surface)` 是 `@hide`（javap 核对 api26–35 android.jar）。R4 顺延至 S2 环深度确定后重新评估 | 附录 A4 + §3.11 R4 |
| 帧率可控 | ring 请求设 `SENSOR_FRAME_DURATION` + `CONTROL_AE_TARGET_FPS_RANGE`（RAW 视频已用，`CameraVideoController.kt:137-143`） | 同左 |
| 逐帧元数据绑定 | 按 `SENSOR_TIMESTAMP` 精确匹配（5 ms 容差，`CameraCaptureMetadataExtractor.kt:59-63`） | 官方 `SENSOR_TIMESTAMP` 语义 |
| AE 稳定性 | 武装前要求 `CONTROL_AE_STATE` 收敛；固定曝光用 `AE_MODE_OFF` + 显式 ISO/时间（与现有 burst 一致） | 现有代码 + 官方键语义 |
| ZSL 意图语义 | 若启用设备托管 ZSL，仅对 `STILL_CAPTURE` 意图请求设置 `CONTROL_ENABLE_ZSL` | 附录 A2 |
| 热与功耗 | `PowerManager` 热状态 + 空闲 disarm + 熔断 | 官方热管理指引 |
| 开关式能力 | `StreamUseCase`（API 33+）作为**可选**优化，缺能力即跳过；不依赖不存在的常量 | 附录 A7 |
| 线程与生命周期 | `HandlerThread` 受控、`CameraDevice` 单所有者、异常回调兜底 | 现有 Invariant 4 |

### 3.11 前置重构（S0 交付，无行为变更）

| # | 现状 | 为什么必须先修 |
|---|---|---|
| R1 | 预览 repeating 在 3 处各自构造：`CameraHardwareStreamController.kt:157-181`、`:380-396`、`CameraLensController.kt:267-285` | 否则变焦/切镜头会静默丢掉 RAW target，环"假活" |
| R2 | `captureResults` 无淘汰（`CameraRuntimeStateHolder.kt:67-73`） | 30 fps 持续写 → 无界增长；改有界 LRU(300) |
| R3 | RAW 回调与预览结果共用 `camera2Handler` | 24 MB/帧拷贝挤占预览回调 → 独立 RAW 线程 |
| R4 | 未调 `session.prepare()` | **已裁决顺延（S0 不做）**：公开 API 仅 `prepare(Surface)`，会把 RAW ImageReader 的 `maxImages=8` 全量预分配（12MP≈192MB，50MP≈800MB），与 S0「零行为变更 / 不引入峰值内存」冲突；`prepare(int, Surface)` 在公开 SDK 不存在（`@hide`，javap api26–35 核对）。S2 在环深度确定后，按需把 RAW reader 改为 `maxImages = K + 2` 再评估是否 `prepare(Surface)` |
| R5 | burst 请求未设帧率 | 环帧间隔不可控 → 显式锁帧率 |
| R6 | `HdrPlusStreamingBurst.addFrame()` 强制再拷一次 | 快门路径多 3×24 MB → `addOwnedFrame()` |
| R7 | 遗留 `zslTargetUriStr`（恒 null） | 语义污染，顺手清理 |

---

### 3.12 UI 设计：Bento 规范落地（要求 6 + Invariant 5）

#### 3.12.1 前提纠正

"相机侧设置面板"不存在（证据见 §1.10）：Settings 是独立全屏目的地，进入即 `closeAllSessions()`，且相机页返回时才 `bindCameraUseCases()` 重建会话。因此采用**双界面模型**，而不是在设置页硬跑探测（也不复制一套自制会话去"模拟"生产环境——那会让探测结论失真，并多出约 200 行会话代码）。

#### 3.12.2 双界面模型

| 界面 | 角色 | 需相机 | 实现 |
|---|---|---|---|
| 全屏 Settings（`fragment_settings.xml`） | 控制面：开关、状态、结论、重测入口、复制报告 | 否 | 新增顶层 Bento 托盘 `card_tray_zsl` + 新 delegate `SettingsZslDelegate` |
| 相机页之上（`CameraFragment`） | 执行面：探测进度与结论 | 是 | 新增 `ZslProbeBottomSheet`（DialogFragment） |

**触发链（唯一路径）**

```
设置页「运行检测」
  → 写 zsl_probe_pending = true
  → navigateUp() 回到相机
  → hub.onResume() → bindCameraUseCases()
  → 会话配置完成 → CameraUiHost.onSessionConfigured()（CameraHardwareStreamController.kt:398-400）
  → 消费 pending → 弹 ZslProbeBottomSheet → 运行探测
  → 写报告 + 决定开关是否生效
  → 用户返回设置页 → 托盘已显示结论
```

- 关闭 sheet = 中止探测（不后台偷跑），状态回 `未检测`，`zsl_probe_pending=false`。
- 探测期间来电/切后台/切模式 → 中止 + 还原请求 + 固定 reason（与 §3.8.3 一致）。

#### 3.12.3 设置页托盘规格（逐项对齐现有 Bento 体系）

- **插入位置**：`card_tray_color` 之后、`card_advanced_container` 之前（当前 `fragment_settings.xml` 第 668–673 行之间）。
- **容器角色**：`primaryContainer`。理由：顶层托盘已占用 `surfaceContainerHigh`（startup/about）、`secondaryContainer`（capture）、`tertiaryContainer`×2（half_frame/color）、`surfaceContainerLow`（advanced）、`errorContainer`（debug 专属）；`primaryContainer` 在顶层尚未使用，语义上适合"新能力"。禁用 `errorContainer`（不得与调试托盘混淆）。

| 元素 | 规格 | 依据 |
|---|---|---|
| 托盘 | `BentoTrayCard` + `style="@style/Widget.Darkbag.BentoTray"`；26dp 圆角、10dp 内边距、`app:strokeWidth="0dp"`、`app:bentoContainerRole="primaryContainer"` | `res/values/styles.xml:103-109`；Invariant 5（禁止 1dp 边框作主要区分） |
| 行间距 | 统一 8dp（`layout_marginBottom="8dp"`） | 同心几何规范 |
| 开关 | `BentoSwitchTile`，48dp 高；`app:bentoSwitchTitle="@string/pref_zsl_enable"`、`app:bentoSwitchBadge="EXPERIMENTAL"`、`app:bentoSwitchBadgeStyle="tertiary"` | `ui/bento/BentoSwitchTile.kt:180-232` |
| 操作按钮 | `Widget.Darkbag.BentoTile.Button`（14dp 圆角、`?attr/colorSurface` 底、`?attr/colorOnSurface` 字、`maxLines=2`、`ellipsize=end`） | `styles.xml:112-128` |
| 状态块 | porcelain 内块（`?attr/colorSurface`，14dp 圆角，8dp 内边距）：Line1 11sp 大写短标签 `PROBE`，Line2 12sp 状态值；`maxLines=2` + `ellipsize=end` | 两行遥测规范 |
| 徽标 | `bg_bento_badge_tertiary`（实验）/ `bg_bento_badge_primary`（支持） | 复用 `settings_badge_dev_unlocked` 用法 |
| 文案 | 全部走 `@string/`，`values/` 与 `values-zh-rCN/` 同步；副标签单行；禁止双语拼接与括注 | Invariant 5 |

**托盘线框（自上而下）**

```
┌ card_tray_zsl · primaryContainer · 26dp · stroke 0 ─────────────┐
│ ZSL 抓拍                                     [EXPERIMENTAL]     │  11sp bold + badge
│ 零快门延迟，依赖设备 RAW 吞吐                                     │  10sp 副标签（单行）
│ ┌─────────────────────────────────────────────────────────────┐ │
│ │ ◉  启用 ZSL 抓拍                                   [ZSL]    │ │  BentoSwitchTile 48dp
│ └─────────────────────────────────────────────────────────────┘ │
│ ┌─────────────────────────────────────────────────────────────┐ │
│ │ PROBE                                                       │ │  11sp 大写标签
│ │ 支持 · 30 FPS · 3 帧                                         │ │  12sp 值，maxLines=2
│ └─────────────────────────────────────────────────────────────┘ │  porcelain 内块 14dp
│ ┌──────────────┐ ┌───────────────────────────────────────────┐ │
│ │ 运行检测      │ │ 查看详情                                   │ │  BentoRowLayout 两枚 tile
│ └──────────────┘ └───────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────┘
```

**三态行为（状态机与 UI 一致）**

| 状态 | Line2 文案 | 开关 | 主按钮 | 说明 |
|---|---|---|---|---|
| 无有效报告 | `未检测` | 可点 → 进入"先检测"确认 | `运行检测` | 不静默禁用开关，避免用户不知为何点不动 |
| 待执行/检测中 | `等待取景器` / `检测中` | 锁定（不可再次触发） | `取消` | 只有一条探测路径 |
| 支持 | `支持 · 30 FPS · 3 帧` | 可用 | `重新检测` | 数字由报告运行时格式化，不写死 |
| 受限 | `受限 · 20 FPS · 2 帧` | 可用（自动降档参数） | `重新检测` | 取报告推荐环参数 |
| 不支持 | `不支持 · RAW 帧率不足` | 禁用 | `重新检测` | 原因码映射为单语言短句，"查看详情"给出全部指标 |

#### 3.12.4 探测 BottomSheet（执行面）

- 骨架复用 `bottom_sheet_bento_selection.xml`：`BottomSheetDragHandleView` + 标题行（标题 + badge）+ `NestedScrollView` 内容区。
- 标题 `ZSL 兼容性检测`，badge 随状态：`RUNNING` / `SUPPORTED` / `LIMITED` / `UNSUPPORTED` / `CANCELLED`（取消态）（对应 `bg_bento_badge_tertiary` / `bg_bento_badge_primary` / 警告色 / `?attr/colorError` 语义）。
- 内容 = 6 行指标：`RAW 帧率`、`帧间隔 P95`、`帧龄 P95`、`拷贝 P95`、`取景掉帧`、`热状态`；每行左 10sp 标签（`?attr/colorOnSurfaceVariant`）+ 右 12sp 数值（`?attr/colorOnSurface`），行距 8dp；超阈值项数值改用 `?attr/colorError`。
- 进行中显示三段进度（基线 0–1500ms / 双流 1500–5500ms / 恢复 ≥5500ms；W3 预热未实现，不设假阶段）。
- 结论态底部两枚 tile：`重新检测`、`完成`；结论态额外 `复制报告`（写入剪贴板，便于报障）。
- 关闭 = 中止（见 §3.12.2）。

#### 3.12.5 字符串清单（双语、单语言呈现）

> S1-B3 实测：主表 26 个 key 之外，另加 4 个 sheet badge、3 个进度阶段（基线/双流/恢复）、进度占位、`Unknown`、取消态（`zsl_state_aborted`/`zsl_probe_badge_cancelled`）与 3 个确认对话框 key，**共 40 个**（en/zh 完全 parity）。

| key | `values/`（en） | `values-zh-rCN/`（zh） |
|---|---|---|
| `settings_zsl_title` | ZSL capture | ZSL 抓拍 |
| `settings_zsl_sub` | Zero shutter lag, depends on device RAW throughput | 零快门延迟，依赖设备 RAW 吞吐 |
| `pref_zsl_enable` | Enable ZSL capture | 启用 ZSL 抓拍 |
| `zsl_badge_experimental` | EXPERIMENTAL | 实验 |
| `zsl_probe_label` | PROBE | PROBE |
| `zsl_state_untested` | Not tested | 未检测 |
| `zsl_state_pending` | Waiting for viewfinder | 等待取景器 |
| `zsl_state_supported` | Supported · %1$d FPS · %2$d frames | 支持 · %1$d FPS · %2$d 帧 |
| `zsl_state_limited` | Limited · %1$d FPS · %2$d frames | 受限 · %1$d FPS · %2$d 帧 |
| `zsl_state_unsupported` | Unsupported · %1$s | 不支持 · %1$s |
| `zsl_action_run` | Run test | 运行检测 |
| `zsl_action_details` | Details | 查看详情 |
| `zsl_action_retest` | Retest | 重新检测 |
| `zsl_action_copy_report` | Copy report | 复制报告 |
| `zsl_action_done` | Done | 完成 |
| `zsl_probe_title` | ZSL capability test | ZSL 兼容性检测 |
| `zsl_metric_raw_fps` | RAW frame rate | RAW 帧率 |
| `zsl_metric_gap_p95` | Frame gap P95 | 帧间隔 P95 |
| `zsl_metric_age_p95` | Frame age P95 | 帧龄 P95 |
| `zsl_metric_copy_p95` | Copy P95 | 拷贝 P95 |
| `zsl_metric_preview_drop` | Preview drops | 取景掉帧 |
| `zsl_metric_thermal` | Thermal | 热状态 |
| `zsl_reason_raw_fps_low` | RAW frame rate low | RAW 帧率不足 |
| `zsl_reason_preview_degraded` | Viewfinder degraded | 取景器掉帧 |
| `zsl_reason_thermal_risk` | Device thermal risk | 设备温度风险 |
| `zsl_reason_unsupported` | Not supported on this device | 此设备不支持 |

#### 3.12.6 文件与预算（避免超级文件）

| 文件 | 内容 | 预算 |
|---|---|---|
| `fragments/settings/SettingsZslDelegate.kt`（新） | 绑定托盘、三态渲染、开关/按钮事件、pending 写入 | ≤180 行 |
| `ui/ZslProbeBottomSheet.kt`（新） | DialogFragment：LIVE/RESULT/DETAILS 三模式 + 状态流 + 三段进度 + 6 行指标 + 剪贴板 + 关闭即中止 | **≤300 行**（实测 278；纯契约 `zslStageRes`/`zslProbeActionFor`/原因映射已外提到 `ui/ZslProbeSheetContract.kt` 37 行） |
| `res/layout/bottom_sheet_zsl_probe.xml`（新） | sheet 布局 | ≤150 行 |
| `res/layout/fragment_settings.xml` | 插入 `card_tray_zsl` | 既有文件 **+≤160 行**（S1-B3 实测修订：对齐同文件既有 tray 的多行属性风格） |
| `res/values/strings.xml`、`values-zh-rCN/strings.xml` | 26 主 key + 5 badge + 3 阶段 + 进度 + `Unknown` + 取消态 + 3 确认 = **40 个** | 各 +~46 行 |
| `fragments/SettingsFragment.kt` | 实例化 + 回调 + 确认对话框 + collect 内一行 render | **≤+25 行**（实测 +25） |
| `fragments/CameraFragment.kt` | 状态流 → sheet + onStop abort | **≤+25 行**（实测 +18） |
| `fragments/settings/SettingsBentoDelegate.kt` | **不改**（已 488 行，继续加会变超级文件） | 0 |
| `viewmodel/SettingsViewModel.kt` | 可选：暴露 `zslProbeReport` StateFlow | ≤+15 行 |

#### 3.12.7 禁止项（Bento / Invariant 5，命中即打回）

- ❌ 用 1dp 边框作为托盘主要区分（新托盘 `strokeWidth=0`）。
- ❌ 双语拼接（`ZSL 抓拍 / ZSL Capture`）或冗余括注（`ZSL（零快门延迟）`）。
- ❌ 营销式长文案；副标签超过 1 行；按钮使用 `\n` 双行中英混排。
- ❌ 遥测换行到 3 行或溢出（必须 `maxLines=2` + `ellipsize=end`）。
- ❌ 在 `SettingsFragment` / `SettingsBentoDelegate` 内堆业务逻辑（必须落在 `SettingsZslDelegate`）。
- ❌ 新托盘使用 `errorContainer`（debug 专属语义）。
- ❌ 硬编码 `android:text` 字符串（预览用 `tools:text`）。

---

## 4. 分支、提交与交付策略（要求 1）

| 项 | 约定 |
|---|---|
| 分支 | 从 `refactor/datastore-and-arch-hardening` @ `cdb8d52e` 切出 **`feat/true-zsl-capture`**；禁止直接在重构分支/主干上开发 |
| 提交粒度 | 每阶段（S0…S4）至少 1 个原子提交；阶段内可拆多个语义提交；**不得把两个阶段混在一次提交** |
| 提交信息 | `feat(zsl): ...` / `refactor(zsl): ...` / `test(zsl): ...` / `docs(zsl): ...`，body 写根因、方案、门禁结果（AGENTS §5） |
| 推送 | 每次 `APPROVED` 后 `git push origin feat/true-zsl-capture` |
| 开关 | 所有功能默认 `false`；关闭时与基线**行为等价**（§5 parity 断言） |
| PR | 按阶段开 draft PR，body 附证据包链接与门禁表；S4 完成后转 ready |
| 回滚 | 单阶段可独立 revert（阶段间无隐式依赖，S0 除外） |

---

## 5. 分阶段计划与可考核目标（要求 5）

> 每阶段统一结构：**Scope Lock（允许改动的文件）→ 交付物 → 可考核目标（含证据类型）→ 反半途而废清单 → 退出条件**。
> 证据类型：`UT`=单测可通过；`LOG`=提交的 `adb logcat` 片段；`JSON`=单行遥测字段达标；`MEAS`=测量报告（CSV/MD）；`DUMP`=`dumpsys media.camera`/请求字段转储；`REVIEW`=reviewer 断言。

### S0 — 地基与遥测（无行为变更）

- **Scope Lock**：`camera/session/PreviewRequestFactory.kt`(新)、`camera/session/RawFrameThread.kt`(新)、`camera/state/BoundedCaptureResultCache.kt`(新)、`CameraHardwareStreamController.kt`、`CameraLensController.kt`、`CameraVideoController.kt`、`CameraSessionArbiter.kt`(仅字段与关闭钩子)、`CameraRuntimeStateHolder.kt`、`HdrPlusStreamingBurst.kt`、`HdrPlusRequestManager.kt`(删字段)、`processor/HdrPlusRequestManagerTest.kt`(删 1 行字段赋值，编译必需)、`camera/zsl/ZslTelemetry*.kt`(新)、`camera/dispatch/HdrPlusBurstCaptureExecutor.kt`(遥测钩子 + S0-4 RAW 回调线程切换，≤+8)、`camera/dispatch/SingleRawCaptureExecutor.kt`(遥测钩子 + S0-4 RAW 回调线程切换 + JPEG 兜底模式标注，≤+8)、对应测试。
- **交付物**：预览请求工厂；有界元数据 LRU；独立 RAW 线程；`addOwnedFrame()`；单行 JSON 遥测（固定 schema v1）。`prepare()` 已裁决顺延 S2（见 §3.11 R4）。

| ID | 可考核目标 | 证据 |
|---|---|---|
| S0-1 | 单相机预览 repeating 的四条路径（会话配置、变焦/参数更新、切镜头变焦、RAW 视频恢复）全部走工厂；上述 3 个文件中 `TEMPLATE_PREVIEW` 计数为 0，工厂内为 1 | `REVIEW` grep + `UT` |
| S0-2 | 目标与条件矩阵等价：analysis 附着规则 8 例真值表、目标顺序、null 面跳过、额外 target 透传；真实请求字段在 S1 设备证据包用 `DUMP` 复核（Robolectric 无法构造真实 `CaptureRequest`） | `UT` + `REVIEW` |
| S0-3 | `captureResults` 插入 1000 条后 size ≤ 300，且精确时间戳仍能命中、超界返回 null | `UT` |
| S0-4 | RAW 回调线程名独立（`ZslRawThread`），预览结果线程不受其阻塞 | `LOG` |
| S0-5 | **已修订**：S0 不引入 `prepare()`（公开 API 会按 maxImages 全量预分配，见 R4）；以「决策记录 + javap 证据」交付，S2 重新评估 | `REVIEW`（api 证据 + 文档裁决） |
| S0-6 | `addOwnedFrame()` 路径不含 `StreamingBufferPool.acquire`/`copyBayerWithStride`（结构性断言），准入/满员/关闭语义有单测 | `UT` + `REVIEW`(grep) |
| S0-7 | 每次拍摄输出 1 行 JSON，字段固定（含 `t1Ms`, `frames`, `mode`, `fallbackReason`） | `LOG` |
| S0-8 | 全量既有测试 100% 通过；无文件增长超预算 | `UT` + `REVIEW` |

- **反半途而废**：只抽工厂但变焦路径仍自建（S0-1 不过）；只加 LRU 常量但无淘汰测试（S0-3 不过）；遥测字段随阶段漂移（`ZslTelemetryFields` 版本未增 → 打回）。
- **退出**：S0-1…S0-8 全绿 + reviewer `APPROVED` + 提交推送。

### S1 — 能力探测与实验开关（含决策门 D1）

- **Scope Lock**：`camera/zsl/{ZslCapability,ZslPolicy,probe/*}.kt`、`camera/session/zsl/*`(适配器)、`repository/*`(3 键)、`res/layout/fragment_settings.xml`(新增托盘)、`res/layout/bottom_sheet_zsl_probe.xml`(新)、`fragments/settings/SettingsZslDelegate.kt`(新)、`ui/ZslProbeBottomSheet.kt`(新)、`fragments/SettingsFragment.kt`(≤+25 行)、`fragments/CameraFragment.kt`(仅状态流→sheet 与 onStop abort，≤+25 行)、`ui/ZslProbeSheetContract.kt`(新；纯契约 37 行)、`camera/CameraControllerHub.kt`(runner 装配 + pending 挂点 + hydration 守卫，≤+25 行)、`camera/delegate/CameraZslProbeRunner.kt`(新)、`camera/session/zsl/ZslProbeSessionAdapter.kt`(新；S1-B2 拆出 `ZslProbeBeginGate/HandlerDispatch/SystemReadings/RawReader` 四个 ≤70 行文件)、`camera/session/CameraHardwareStreamController.kt`(仅 R1 同步恢复入口，≤+35/−15)、`camera/zsl/probe/ZslProbe{TargetFps,ReportStore}.kt`(新)、`res/values*/strings.xml`、测试、`docs/zsl/evidence/S1_*.md`。**`SettingsBentoDelegate.kt` 禁止改动**。
- **交付物**：静态能力探测 + 主动自检（≤10 s）+ 报告持久化 + 设置页 Bento 托盘（三态）+ 探测 BottomSheet + 报告复制 + 触发链。

| ID | 可考核目标 | 证据 |
|---|---|---|
| S1-1 | 静态能力表覆盖 8 项（硬件级别/RAW/尺寸/minFrameDuration/deviceZslKey/reprocessing/capabilities/syncMaxLatency），对 3 类设备给出合理值 | `UT` + `MEAS` |
| S1-2 | 主动探测全流程 ≤10 s（看门狗 15 s），三窗口指标齐全 | `LOG`(JSON) + `MEAS` |
| S1-3 | 探测后请求字段与探测前**逐字段一致**；RAW 监听已移除；无 MediaStore 新增 | `DUMP` + `UT` |
| S1-4 | 中止路径（来电/切后台/切模式/超时/`onCaptureFailed`/关闭 sheet）均还原并返回固定原因码 | `UT`（6 条路径） |
| S1-5 | 判定函数对每条阈值两侧各 1 例，边界含等号 | `UT`（表驱动 ≥ 12 例） |
| S1-6 | 同一设备连续两次探测：verdict 一致，`rawFps` 偏差 ≤15% | `MEAS`（2 次） |
| S1-7 | 报告缺字段/版本升级可兼容读取，不崩溃 | `UT` |
| S1-8 | 开关三态呈现正确；无有效报告时开关可点但走"先检测"确认；不支持时禁用且给出原因；不静默 | `UT`(delegate) + `MEAS` |
| S1-9 | **触发链完整**：设置页「运行检测」→ 写 pending → 返回相机 → `onSessionConfigured` 消费 → sheet 出现 → 结论写回设置页；中途取消/返回不产生孤儿 pending | `UT` + `MEAS` |
| S1-10 | **Bento 合规**：新托盘 `strokeWidth=0` + `primaryContainer`；tile 用 `Widget.Darkbag.BentoTile.Button`；遥测 `maxLines=2`+`ellipsize=end`；26 个字符串双语齐全；无硬编码 text | `REVIEW` + `grep` 断言 |
| S1-11 | 探测 sheet 关闭即中止（不后台偷跑），状态与设置页一致 | `UT` + `MEAS` |
| S1-12 | 首次启用（无报告）自动走一次探测；失败自动回退开关并展示原因 | `MEAS` |
| **D1** | 至少一台目标机判为 `SUPPORTED`/`RECOMMENDED`；否则停止 Z2/Z3，仅保留设备托管 ZSL 单帧路线并重写方案 | `MEAS` |

- **反半途而废**：只弹 Toast 无真实测量；只测静态能力不测动态；报告不持久化；判定阈值写死在 UI（必须纯函数）；探测期间产生照片；UI 只有开关没有状态/详情；把逻辑塞进 `SettingsBentoDelegate`。
- **退出**：S1-1…S1-12 + D1 通过 + reviewer `APPROVED` + 提交推送。

### S2 — Z1 单帧 ZSL（里程碑 M1：实验可用）

- **Scope Lock**：`camera/zsl/{ZslRingBuffer,ZslRingEpoch,ZslStreamController,ZslFrameSource,ZslMetrics}.kt`、`camera/dispatch/SingleRawCaptureExecutor.kt`、`camera/dispatch/CameraCapturePipelineDispatcher.kt`、`camera/CameraControllerHub.kt`、`modes/*`(策略声明)、`camera/delegate/CameraModeController.kt`、测试、证据。
- **交付物**：环 + 武装/解除 + 单帧取环 + 实验开关生效 + 熔断/空闲/热联动。

| ID | 可考核目标 | 证据 |
|---|---|---|
| S2-1 | 环淘汰/detach/epoch 失效/并发 push-detach 语义正确（`detach` 后旧引用从环移除且不再被写入） | `UT` ≥ 8 |
| S2-2 | 状态机幂等：重复 `arm`/`disarm` 不产生多余请求；`Disarmed` 枚举全覆盖；与视频/多摄互斥 | `UT` ≥ 10 |
| S2-3 | 回落矩阵全枚举（§3.7 每个 `Disarmed(reason)` 一行）：环无效时帧路径与基线一致 | `UT` ≥ 12 |
| S2-4 | 排 50 次单帧：T1 P50 ≤ 30 ms、P95 ≤ 60 ms（同条件前向基线同步测量对照） | `MEAS` |
| S2-5 | 选中帧帧龄 P95 ≤ 140 ms；`zslRingDepthAtPress ≥ minValidFrames` 占 ≥95% | `LOG`(JSON) |
| S2-6 | 连拍 100 次：0 崩溃/0 ANR；`dumpsys meminfo` RSS 增长 < 80 MB 且稳定（无单调上升） | `MEAS` + `LOG` |
| S2-7 | 武装态取景预览掉帧 ≤10%，无黑屏；退出武装后恢复正常 | `MEAS` |
| S2-8 | 10 min 常驻武装：无 `SEVERE`；空闲 5 s 自动 disarm 并在交互后 300 ms 内恢复 | `MEAS` + `LOG` |
| S2-9 | **开关关闭 parity**：请求字段转储、日志路径、T1 分布与基线无系统性差异 | `DUMP` + `MEAS` |
| S2-10 | 文件预算达标：`camera/zsl/**` 每文件 ≤300 行；`CameraControllerHub` ≤+25 行；禁止清单 0 命中 | `REVIEW` |

- **反半途而废**：有环但没接进采集路径；接了但无回落/无熔断/无空闲解除；只做 arm 不做 disarm；遥测缺 `fallbackReason`；测试只覆盖 happy path。
- **退出**：S2-1…S2-10 + M1（用户可开、可关、失败自动回落）+ reviewer `APPROVED` + 提交推送。

### S3 — Z2 低照 ZSL + HDR+ 混合

| ID | 可考核目标 | 证据 |
|---|---|---|
| S3-1 | 混合帧数守恒：K∈[0..5] × burstSize∈[1..8] 的所有组合，最终推入帧数 == burstSize，无重复/丢失 | `UT` ≥ 24（参数化） |
| S3-2 | 治理器迟滞：静态场景 60 s 重编程 ≤ 2 次；场景跳变（EV≥1）后 ≤1 s 内重编程并作废环 | `UT` + `LOG` |
| S3-3 | 只读准备：`digitalGain≈1` 才武装；非该区间一律前向（日志含 reason） | `UT` + `LOG` |
| S3-4 | 弱光 T1 较基线下降 ≥60%（同场景同 ISO，N=50） | `MEAS` |
| S3-5 | 成片 SNR 不低于基线（灰卡 3 场景 × 5 次，σ 对比），无重影 | `MEAS` |
| S3-6 | 帧间中位亮度差 <2%（无亮度台阶），无可见闪烁 | `MEAS` |
| S3-7 | 手动曝光/闪灯/视频互斥矩阵全绿 | `UT` + `LOG` |

- **反半途而废**：混合但不守恒（多算/少算帧）；治理器无迟滞（抖动）；SNR 只写结论无原始图/数据；只测一种 scene。
- **退出**：全绿 + reviewer `APPROVED` + 提交推送。

### S4 — Z3 全场景 + 取景增益（里程碑 M2：默认推荐）

| ID | 可考核目标 | 证据 |
|---|---|---|
| S4-1 | `uGain` 生效：取景亮度与成片亮度差 ≤0.2 EV（灰卡 5 档曝光） | `MEAS` |
| S4-2 | 画质门禁通过 `docs/CAPTURE_SPEED_OPTIMIZATION_PLAN.md:320-326` 四项（中性 ±2%、高光品红不升、暗部 σ 不劣化 >10%、四产物一致） | `MEAS` |
| S4-3 | 白天/室内/夜景三档 T1 P50 ≤ 40 ms | `MEAS` |
| S4-4 | 10 min 武装取景：温升与耗电增幅 ≤15%；热降级路径触发正确 | `MEAS` + `LOG` |
| S4-5 | 设备托管 ZSL（若可用）作为可选加速：探测到则走，探测不到不影响主路径 | `UT` + `LOG` |
| S4-6 | 关闭开关后与基线 parity（同 S2-9） | `DUMP` + `MEAS` |

- **反半途而废**：只加 uniform 不做校准；画质只给一张样片；热只测 1 min。
- **退出**：全绿 + reviewer `APPROVED` + 提交推送 + PR ready。

### 每阶段统一"证据包"（缺一即视为未完成）

`docs/zsl/evidence/S<n>_<yyyyMMdd>.md`，至少含：
1. 设备指纹（`Build.MANUFACTURER/MODEL`、SDK、camera id、RAW 尺寸、固件版本）。
2. 原始 `adb logcat` 片段（含时间戳，覆盖每条 LOG 类目标）。
3. 测量表（N、P50、P95、min/max），附生成脚本或 CSV。
4. 请求字段转储（`DUMP`）。
5. 门禁结果表（逐项 PASS/FAIL + 证据行号）。
6. 已知偏差与未完成项（显式列出，不允许"未提及"）。

---

## 6. 实施与审查的双 Agent 闭环（要求 4）

### 6.1 角色

| 角色 | 由谁担任 | 职责 | 禁止 |
|---|---|---|---|
| **Orchestrator**（主 Agent） | 我 | 建分支、拆阶段、下发任务卡、维护状态表、在 `APPROVED` 后提交推送、处理升级 | 代替 reviewer 做裁决；跳过门禁 |
| **Implementer**（子 Agent，每阶段独立上下文） | 新 subagent | 按阶段任务卡实现 + 自测 + 产出证据包 | 自证完成；越界改文件；顺手重构；提交/推送 |
| **Reviewer**（子 Agent，每阶段独立上下文） | 新 subagent（与 implementer **不同实例**） | 独立复核证据、跑质量门禁、审架构/不变量/文件预算、裁 `APPROVED` 或 `REVISE_REQUIRED` | 只看 implementer 的结论不看证据；放行"部分实现" |
| **Victory Auditor**（里程碑专用） | 新 subagent | 在 M1/M2 复核整条证据链与跨阶段回归 | 抽样代替全量门禁 |

**信息隔离要求**：Implementer 与 Reviewer 各自拿到**同一份**"阶段任务卡 + 设计文档 + 检查清单"，但 Reviewer **不接收** implementer 的完成声明摘要作为依据（只接受文件/日志/测试输出）。

### 6.2 单阶段闭环（7 步）

1. Orchestrator 下发任务卡（附录 C），含 Scope Lock 与 DoD 表。
2. Implementer 实现 + 单测 + 在本机可跑的范围内自测 → 产出证据包草案。
3. Implementer 输出结构化报告：改动文件清单、DoD 逐项自评（仅作线索）、未完成项、风险。
4. Reviewer 独立审计（§6.3），必要时要求重跑测试/重取日志。
5. 若 `REVISE_REQUIRED`：Orchestrator 把 findings 原样转给 Implementer → 修复 → 回到 3（最多 3 轮；第 3 轮仍不过则由 Orchestrator 修订范围或判定该阶段 blocked，并如实上报）。
6. `APPROVED` → Orchestrator 执行原子提交 + 推送 + 更新状态表。
7. 里程碑阶段追加 Victory Auditor 复核后再进入下一阶段。

### 6.3 Reviewer 审查清单（四组，缺一组即不通过）

**A. 证据与门禁**
- 逐行核对 DoD 表：每项必须指到具体证据（测试名/日志行/测量行/转储行），禁止"已实现"式表述。
- 亲自重跑：`./gradlew testDebugUnitTest --rerun-tasks`（100% 通过）；若触及 native：`./gradlew :app:buildCMakeRelease[arm64-v8a]`。
- 抽样复核 ≥3 项测量数字的原始来源（logcat 时间戳是否连续、单位是否 ns/ms 混用）。

**B. 架构与不变量**
- 依赖方向：`camera/zsl` 是否 import 了 `fragments/ui/viewmodel/delegate/databinding`（0 容忍）。
- 不变量 1–5 逐条核对（§3.10 表）。
- 禁止清单（§3.1④）0 命中。

**C. 机器断言（可直接用命令验证）**

| 断言 | 命令/方式 |
|---|---|
| 预览请求构造单一化 | `grep -c TEMPLATE_PREVIEW` 在 `camera/session/CameraHardwareStreamController.kt`、`camera/delegate/CameraLensController.kt`、`camera/video/CameraVideoController.kt` 均为 0；`camera/session/PreviewRequestFactory.kt` 为 1 |
| 无 UI 分支 | `grep -rn "isZsl\|zslEnabled" app/src/main/java/top/maary/darkbag/fragments app/src/main/java/top/maary/darkbag/modes | wc -l` == 0 |
| 文件预算 | `find app/src/main/java/top/maary/darkbag/camera/zsl -name '*.kt' -exec wc -l {} +` 每项 ≤300 |
| 无占位测试 | `grep -rn "assertTrue(true)\|TODO()\|FIXME" app/src/test/java/top/maary/darkbag/{camera,processor} | wc -l` == 0 |
| 遥测 schema 版本 | 新增字段必须伴随 `ZslTelemetryFields.SCHEMA_VERSION` +1 |
| 测试数量 | 本阶段新增 ≥12 个 `@Test` 且有断言 |
| **Bento 托盘规格** | 新托盘含 `app:strokeWidth="0dp"`；`bentoContainerRole` ∈ {primaryContainer, secondaryContainer, tertiaryContainer, surfaceContainerHigh, surfaceContainer}；`errorContainer` 仅允许 debug 托盘 |
| **双语字符串齐全** | S1 新增 key 在 `values/strings.xml` 与 `values-zh-rCN/strings.xml` 均存在且数量一致 |
| **无硬编码文案** | 新增布局中 `android:text=` 计数为 0（仅允许 `tools:text`） |
| **遥测行数** | 新增遥测 TextView 必须含 `android:maxLines="2"` 与 `android:ellipsize="end"` |
| **设置逻辑不膨胀** | `wc -l SettingsBentoDelegate.kt` 与基线一致（488 行）；ZSL 逻辑只允许在 `SettingsZslDelegate.kt` |

**D. 反半途而废**
- 逐条对照该阶段"反半途而废清单"，任一条命中即 `REVISE_REQUIRED`。
- 检查是否只实现"主路径"而回落路径未测（最常见）。
- 检查证据包是否包含"已知偏差与未完成项"（无则视为隐瞒）。

### 6.4 裁决格式

```
VERDICT: APPROVED | REVISE_REQUIRED
Stage: S2
Gates: unitTests=PASS native=PASS architecture=PASS evidence=PASS antiPartial=PASS
Findings:
  F1 [BLOCKER] file:line — 问题 — 必须的修复 — 验证方式
  F2 [MAJOR] ...
Evidence verified: <条目 → 证据>
Sampled claims: <3 条抽检结果>
```

### 6.5 防造假与防"过早返回"

- 所有硬件类指标必须附**原始 logcat**（含 PID/时间戳）与设备指纹；只贴汇总数字视为无效。
- Reviewer 对**任意**一项无法复现的证据可要求重测，Implementer 必须重跑而非解释。
- 每阶段结束由 Orchestrator 汇总"未完成/降级项"清单，禁止把降级项悄悄带进下一阶段。
- 阶段任务卡里显式写"**本阶段若只能完成一部分，必须停止并上报，不得以部分实现声明完成**"。

---

## 7. 风险登记与需实测项

| ID | 风险 | 影响 | 缓解 | 探测/测法 |
|---|---|---|---|---|
| R-1 | 满幅 RAW 持续带宽不足 | ZSL 不成立 | 探测给结论；降帧率/降尺寸；D1 门 | S1 探测（`rawFps/间隔/帧龄`） |
| R-2 | 多流并发掉预览帧/发热 | 卡顿、烫 | 熔断 + 空闲 disarm + 热联动 | S1 预览掉帧；S2/S4 10 min 热测 |
| R-3 | 24 MB/帧拷贝争抢相机线程 | 预览抖动 | R3 独立 RAW 线程 | S0 线程隔离；S1 `copyP95` |
| R-4 | 定值曝光期间场景变化 | 亮度跳变 | 治理器 10 Hz + 0.15 EV 阈值 + 作废环 | S3 扫视测试 |
| R-5 | 帧间曝光不一致 | 亮度台阶 | 只武装 `digitalGain≈1`；Z3 定值 | S3 灰卡序列 |
| R-6 | 元数据串帧 | 色彩/EXIF 错 | 5 ms 容差 + 精确命中 | S0/S1 命中率 |
| R-7 | 内存放大（环+会话+池） | OOM/GC | 预算表 + 在飞上限 3 + 降 K | S2 RSS/100 张 |
| R-8 | Motion Photo 时间基准错位 | 动图静止帧不同步 | 用**选中帧 sensor 时间戳**做切片 | S2/S3 PTS 偏差 ≤1 帧 |
| R-9 | 半帧/多摄/视频互斥遗漏 | 状态错乱、崩溃 | 单点武装 + 互斥矩阵单测 | S2 互斥测试 |
| R-10 | HAL 因 RAW 流切换传感器模式 | FOV/噪点变化 | 开/关环对比取景 FOV 与亮度 | S2/S4 标尺图 |

**时间基准**：`SENSOR_TIMESTAMP` 是 `CLOCK_BOOTTIME` 纳秒，必须用 `SystemClock.elapsedRealtimeNanos()` 对齐按下时刻；现有链路用 `System.currentTimeMillis()`（`modes/NormalModeCoordinator.kt:30`、`StandardTimingTracker.shutterClick`），二者不可混用。

---

## 8. 总体验收标准

**功能**：武装 300 ms 内环满；100 次连拍无崩溃/泄漏；互斥矩阵全绿；任一回落有确定 reason 且行为退化为基线；开关关闭与基线 parity。

**时延**：Z1 T1 P50 ≤30 ms / P95 ≤60 ms；Z2 较基线 ↓≥60%；Z3 P50 ≤40 ms；帧龄 P95 ≤140 ms（Z3 ≤100 ms）。

**画质**：Z1 有量化差异报告；Z2 SNR 不劣于基线且无重影/台阶；Z3 通过四项一致性判据（`docs/CAPTURE_SPEED_OPTIMIZATION_PLAN.md:320-326`）。

**功耗/热**：10 min 常驻武装温升/耗电 ≤+15%；`SEVERE` 自动解除。

**工程**：`testDebugUnitTest` 100% 通过；触及 native 时 `:app:buildCMakeRelease[arm64-v8a]` 0 错误；`camera/zsl` 文件预算达标；依赖方向与禁止清单 0 违规；证据包齐全。

---

## 9. 工作量与里程碑

| 阶段 | 人日 | 累计 | 里程碑 |
|---|---|---|---|
| S0 地基 | 3–5 | 3–5 | — |
| S1 探测（含设置页 Bento 托盘 + 探测 sheet） | 5–7 | 8–12 | **D1 决策门** |
| S2 Z1 | 5–8 | 13–20 | **M1 实验可用** |
| S3 Z2 | 4–6 | 17–26 | — |
| S4 Z3 | 6–10 | 23–36 | **M2 默认推荐** |

含双 Agent 审查与证据包成本已计入；按 3 轮修订余量，实际上限约 36–42 人日。v2.1 相对 v2 的增量为 S1 的 UI 部分（约 +1 人日，Bento 托盘 + sheet + 双语字符串 + 三态渲染）。

---

## 10. 启动清单（批准后第一件事）

1. `git switch -c feat/true-zsl-capture refactor/datastore-and-arch-hardening`（基于 `cdb8d52e`）。
2. 建 `docs/zsl/README.md`（阶段状态表）+ `docs/zsl/evidence/` 目录。
3. 下发 **S0 任务卡**给 Implementer（附录 C 模板），Scope Lock = §5 S0 列表。
4. S0 完成后交 Reviewer；`APPROVED` 后提交并推送。
5. 依序 S1（含 §3.12 的 Bento 托盘与探测 sheet，UI 部分单独由 reviewer 按 §3.12.7 禁止项复核）→ **D1 决策门**（此处允许并鼓励"停下来重估"，不算失败）。

---

## 附录 A：官方文档

1. `CameraDevice.TEMPLATE_ZERO_SHUTTER_LAG`（application-operated ZSL；device-operated 用 `CONTROL_ENABLE_ZSL`；仅对 reprocessing 能力设备保证支持）— <https://developer.android.com/reference/android/hardware/camera2/CameraDevice#TEMPLATE_ZERO_SHUTTER_LAG>
2. `CaptureRequest.CONTROL_ENABLE_ZSL`（仅 STILL_CAPTURE 意图；可能用历史帧；不保证；结果时间戳反映源帧）— <https://developer.android.com/reference/android/hardware/camera2/CaptureRequest#CONTROL_ENABLE_ZSL>
3. `CameraMetadata.CONTROL_CAPTURE_INTENT_ZERO_SHUTTER_LAG`（"stream full-resolution images and reprocess one or several later"）— <https://developer.android.com/reference/android/hardware/camera2/CameraMetadata#CONTROL_CAPTURE_INTENT_ZERO_SHUTTER_LAG>
4. `CameraCaptureSession.prepare()`（预分配；须在 surface 首次作为 target 之前）— <https://developer.android.com/reference/android/hardware/camera2/CameraCaptureSession#prepare(android.view.Surface)>
5. `CameraDevice.createCaptureSession()` 保证流组合表（`PRIV(PREVIEW)+YUV(PREVIEW)+RAW(MAXIMUM)` = "Preview with in-app processing and DNG capture"）— <https://developer.android.com/reference/android/hardware/camera2/CameraDevice#createCaptureSession(java.util.List%3Candroid.view.Surface%3E)>
6. CameraX ZSL（3 帧环 + reprocess；需 `PRIVATE_REPROCESSING`；闪灯不可用）— <https://developer.android.com/media/camera/camerax/take-photo/zsl>
7. `OutputConfiguration.setStreamUseCase()`（可用 use case 枚举，无 `LOW_LATENCY_SNAPSHOT`）— <https://developer.android.com/reference/android/hardware/camera2/params/OutputConfiguration#setStreamUseCase(long)>

> javadoc 原文已用 AOSP `frameworks/base` `core/java/android/hardware/camera2/*.java`（`main` 分支）逐段核对。

## 附录 B：仓库证据索引

| 事实 | 位置 |
|---|---|
| RAW reader `maxImages=8`、满幅 | `camera/session/CameraHardwareStreamController.kt:310-317` |
| 会话目标 = 预览+RAW+分析 | `camera/session/CameraHardwareStreamController.kt:366-369` |
| 预览 repeating 不含 RAW（2 处） | `CameraHardwareStreamController.kt:157-181,380-396` |
| 变焦重复构造 repeating | `camera/delegate/CameraLensController.kt:267-285` |
| 单帧现造 STILL_CAPTURE | `camera/dispatch/SingleRawCaptureExecutor.kt:63-67,123` |
| 连拍 `captureBurst` + 请求构造 | `camera/dispatch/HdrPlusBurstCaptureExecutor.kt:265,329-356` |
| 逐帧 24 MB 拷贝 + 立即 close | `processor/HdrPlusStreamingBurst.kt:297-372`；`HdrPlusBurstCaptureExecutor.kt:237-239` |
| 缓冲池上限 12 | `processor/StreamingBufferPool.kt:14` |
| 元数据无淘汰 | `camera/state/CameraRuntimeStateHolder.kt:67-73`；`camera/session/CameraSessionArbiter.kt:172-183` |
| 5 ms 时间戳容差 | `camera/dispatch/CameraCaptureMetadataExtractor.kt:59-63,303` |
| 下游握手契约 | `processor/HdrPlusStreamingBurst.kt:11-25`；`camera/dispatch/HdrPlusBurstProcessingEnqueuer.kt:148-` |
| 队列 16 + 600 MB 门限 | `processor/HdrPlusRequestManager.kt:229-230,271-285` |
| 欠曝/ISO/8 ms 分解 | `utils/ExposureUtils.kt:93-141` |
| 预览 AE 与无增益 uniform | `camera/session/CameraHardwareStreamController.kt:109-128`；`processor/LutGlShaderPipeline.kt` |
| 原生逐帧累加 + 参考帧 | `app/src/main/cpp/HdrPlusStreamingSession.cpp:77,153-303` |
| push 后可回收 | `app/src/main/cpp/HdrPlusJNI.cpp:1341-1370` |
| 清晰度评分可复用 | `app/src/main/cpp/HdrPlusJNI.cpp:1372-1460` |
| RAW 视频持续流先例 | `camera/video/CameraVideoController.kt:119-143`；`rawvideo/RawVideoSessionManager.kt:209-284` |
| 快门/模式分派 | `camera/delegate/CameraShutterController.kt:70-73`；`modes/NormalModeCoordinator.kt:25-53`；`camera/delegate/CameraModeController.kt:328-340` |
| 控制器装配 | `camera/CameraControllerHub.kt:66-88,188` |
| 半帧自动补拍窗口 | `camera/dispatch/CameraCapturePipelineDispatcher.kt:121-129` |
| 既有 ZSL 命名历史 | `ae973307`、`804c469f`、`35b6ce62`、`9cf1a6cf` |
| 既有 ZSL 里程碑 | `docs/HDRPLUS_PIPELINE_ANALYSIS.md:160,224`；`docs/CAPTURE_SPEED_OPTIMIZATION_PLAN.md:141,320-326` |
| 相机设置按钮 → 全屏 Settings（无相机侧面板） | `res/layout/camera_ui_container.xml:683-700`；`fragments/CameraFragment.kt:371-374` |
| 离开相机即关会话 / 返回重建 | `camera/CameraControllerHub.kt:260-271`（onStop）、`:323-338`（onResume） |
| 会话配置完成回调（探测挂点） | `camera/session/CameraHardwareStreamController.kt:398-400` |
| 设置页托盘与容器角色 | `res/layout/fragment_settings.xml:95,249,457,599,677,1481,1594` |
| Bento 样式基准 | `res/values/styles.xml:103-109`（托盘）、`:112-128`（tile 按钮） |
| 设置逻辑体量（不得膨胀） | `fragments/settings/SettingsBentoDelegate.kt`（488 行） |
| BottomSheet 骨架 | `res/layout/bottom_sheet_bento_selection.xml`；`ui/BentoSelectionBottomSheet.kt` |

## 附录 C：阶段任务卡模板（下发给 subagent）

```markdown
# 任务：Darkbag 真 ZSL — <阶段号> <名称>

## 输入（必读，按序）
1. AGENTS.md（架构不变量 1–5 + 质量门禁 + 提交规范）
2. docs/TRUE_ZSL_CAPTURE_FEASIBILITY_AND_DESIGN.md（本文件，v2）
3. docs/zsl/evidence/ 既有证据包

## Scope Lock（只允许改这些文件）
<列文件/目录>

## 必须达成（DoD 表）
| ID | 判据 | 证据类型 |
<从 §5 抄本阶段表>

## 禁止
- 修改 Scope Lock 之外的文件（需 Orchestrator 批准并记录）
- 新增 >300 行文件 / >250 行类
- 在 fragments/ui/modes 引入 ZSL 分支
- 以"实验特性"为由跳过测试/回落路径
- 提交或推送（由 Orchestrator 执行）

## 交付
1. 代码 + 测试（本阶段新增 ≥12 个 @Test）
2. docs/zsl/evidence/S<n>_<date>.md（原始 logcat + 测量表 + DUMP + 门禁表 + 未完成项）
3. 结构化报告：改动文件、DoD 自评（仅线索）、未完成项、风险

## 若只能完成一部分
立即停止并说明缺口，**不得声明完成**。
```

## 附录 D：证据包模板

```markdown
# S<n> 证据包 — <日期>
## 1 设备指纹
MANUFACTURER/MODEL/SDK/cameraId/RAW 尺寸/固件
## 2 门禁表
| ID | 结果 | 证据（文件:行 / 命令输出 / 日志行） |
## 3 原始日志
<adb logcat 片段，含时间戳与 PID>
## 4 测量数据
| 指标 | N | P50 | P95 | min | max |
## 5 请求转储
<DUMP 前后对照>
## 6 已知偏差与未完成项
## 7 复现步骤
```
