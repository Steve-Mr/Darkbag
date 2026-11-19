# ZSL 模式兼容性、多摄设置联动及拍后缩略图/图库刷新问题分析与修改计划

> **状态**：仅分析与规划阶段（尚未实施代码修改）。所有结论均基于当前工作区真实源码逐行核验。

---

## 目录

1. [议题一：ZSL 冲突判断、半格兼容性与多摄有限 HDR+/ZSL 架构审计](#1-议题一zsl-冲突判断半格兼容性与多摄有限-hdrzsl-架构审计)
2. [议题二：多摄同拍设置项“强制开启”开关联动与 Bento 规范审计](#2-议题二多摄同拍设置项强制开启开关联动与-bento-规范审计)
3. [议题三：拍后缩略图未正常显示与进入图库显示上一张照片（需刷新才出）的根因定位](#3-议题三拍后缩略图未正常显示与进入图库显示上一张照片需刷新才出的根因定位)
4. [分阶段实施修改计划（Roadmap）](#4-分阶段实施修改计划roadmap)

---

## 1. 议题一：ZSL 冲突判断、半格兼容性与多摄有限 HDR+/ZSL 架构审计

### 1.1 设置页 ZSL 冲突判断的逻辑错误（“功能可用”与“当前已切换”混淆）

#### 现象与根因
- **运行时状态是正确的**：[ZslCaptureController.kt#L226-L232](file:///home/maary/.gemini/antigravity/worktrees/Darkbag/review_zsl_implementation/app/src/main/java/top/maary/darkbag/camera/zsl/ZslCaptureController.kt#L226-L232) 中传给 `ZslArmingPolicy.evaluate()` 的是运行态实际激活的模式：
  - `isNormalCaptureMode = coordinator.effectiveCaptureMode == CaptureMode.NORMAL`
  - `isMultiCamera = env.sessionArbiter.isMultiCameraMode`
- **设置页冲突判断混淆了布尔位**：[SettingsBentoDelegate.kt#L385](file:///home/maary/.gemini/antigravity/worktrees/Darkbag/review_zsl_implementation/app/src/main/java/top/maary/darkbag/fragments/settings/SettingsBentoDelegate.kt#L385) 与 [SettingsBentoDelegate.kt#L445-L450](file:///home/maary/.gemini/antigravity/worktrees/Darkbag/review_zsl_implementation/app/src/main/java/top/maary/darkbag/fragments/settings/SettingsBentoDelegate.kt#L445-L450) 错误使用了：
  ```kotlin
  val hasConflict = state.force60Fps || state.halfFrameMode || state.multiCameraModeEnabled
  ```
  `state.halfFrameMode` 和 `state.multiCameraModeEnabled` 在 [CameraModeController.kt#L149-L154](file:///home/maary/.gemini/antigravity/worktrees/Darkbag/review_zsl_implementation/app/src/main/java/top/maary/darkbag/camera/delegate/CameraModeController.kt#L149-L154) 中的真实语义是**“将该模式加入取景器模式切换按钮的可用列表”**，而不是“当前已处于该拍摄模式”。真正代表当前所在模式的状态是 `state.activeCaptureMode`（即 `PreferenceKeys.ACTIVE_CAPTURE_MODE`：`normal` / `half_frame_sbs` / `half_frame_tb` / `multi_camera`）。
- 当用户在设置里打开半格或多摄以“允许切换”，但取景器仍停留在 `NORMAL` 模式时，设置页却错误地把 ZSL 卡片标红为 `CONFLICT` 并锁死 `switch_zsl_adaptive` / `switch_zsl_lazy_ring`。

---

### 1.2 半格模式（Half-Frame）与 ZSL 的兼容性分析

#### 结论：**完全可以且应当原生兼容 ZSL（包含单帧与 HDR+ 预卷）**
审阅 [HalfFrameModeCoordinator.kt#L119-L133](file:///home/maary/.gemini/antigravity/worktrees/Darkbag/review_zsl_implementation/app/src/main/java/top/maary/darkbag/modes/HalfFrameModeCoordinator.kt#L119-L133) 与底层管线：
1. 半格模式复用的正是单摄 `CameraHardwareStreamController`、`RawFrameRouter` 与 `CameraModeController.triggerHdrPlusBurst` / `triggerSinglePicture` 管线，唯一区别是注入 `HalfFrameCacheSink()`、传递 `isFrame1` 与 `hfMetadata`。
2. 当前唯一阻断兼容的人为门禁是 [ZslArmingPolicy.kt#L37](file:///home/maary/.gemini/antigravity/worktrees/Darkbag/review_zsl_implementation/app/src/main/java/top/maary/darkbag/camera/zsl/ZslArmingPolicy.kt#L37) 中的 `if (!facts.isNormalCaptureMode) return EvaluationResult(ZslProfile.OFF, "current_mode")`。

#### 解禁半格 ZSL 必须一并修复的两处隐蔽缺陷
如果直接放开半格模式的 ZSL 准入，当前代码在“半格 + ZSL 单帧/瞬间单帧”下会触发两处断链：
1. **缺陷 H1：`captureInstantSingle` 丢失 `isFrame1Trigger` 标志**：
   - 位置：[ZslCaptureController.kt#L151](file:///home/maary/.gemini/antigravity/worktrees/Darkbag/review_zsl_implementation/app/src/main/java/top/maary/darkbag/camera/zsl/ZslCaptureController.kt#L151) 与 [#L197](file:///home/maary/.gemini/antigravity/worktrees/Darkbag/review_zsl_implementation/app/src/main/java/top/maary/darkbag/camera/zsl/ZslCaptureController.kt#L197)。
   - 问题：`captureInstantSingle` 未接收 `isFrame1Trigger`，并在调用 `dispatchSingle` 时写死了 `isFrame1 = false`。当半格第 1 帧命中运动单帧回退（`motion_fast_single`）或无稳定序列回退（`instant_single`）时，`isFrame1` 状态会丢失。
2. **缺陷 H2：`CameraCapturePipelineDispatcher.dispatchZslSingleFrame` 缺失半格第 1 帧增益记账与自动连拍触发**：
   - 对比前向单帧 [SingleRawCaptureExecutor.kt#L84-L88, L158-L160](file:///home/maary/.gemini/antigravity/worktrees/Darkbag/review_zsl_implementation/app/src/main/java/top/maary/darkbag/camera/dispatch/SingleRawCaptureExecutor.kt#L84-L88) 与 ZSL 单帧 [CameraCapturePipelineDispatcher.kt#L152-L195](file:///home/maary/.gemini/antigravity/worktrees/Darkbag/review_zsl_implementation/app/src/main/java/top/maary/darkbag/camera/dispatch/CameraCapturePipelineDispatcher.kt#L152-L195)：
   - 前向单帧会在 `isFrame1Trigger == true` 时调用 `coordinator.getDigitalGainAndUpdateStep(...)`（写入 Frame 1 的 `digitalGain` 到 `HalfFrameSessionStore`）并调用 `coordinator.triggerAutoBurst()`（驱动 `halfFrameAutoBurst` 800ms 后自动拍第 2 帧）。
   - 而 `dispatchZslSingleFrame` 漏掉了这两步：一旦走 ZSL 单帧（HDR+ 关闭，或 HDR+ 运动单帧回退），`halfFrameAutoBurst` 将不会触发第 2 帧，且 Frame 1 若携带 `hdrDigitalGain = 2.0f` 时无法回写保存给 Frame 2 参考。
   - *注*：ZSL HDR+ 连拍路径走的 `HdrPlusBurstCaptureExecutor.kt#L118-L122, L274` 已经完整支持 `isFrame1Trigger` 写入增益与 `triggerAutoBurst()`。

---

### 1.3 多摄同拍（Multi-Camera）现有 HDR+ 逻辑重构后状态审计与 ZSL/有限 HDR+ 可行性

#### 一、现状核查：多摄 HDR+ 逻辑当前是否正常工作？
经逐行审计，**多摄同拍现有的 HDR+ 逻辑存在严重退化与隐性失效，并非真正的多帧 HDR+**，具体发现如下：

1. **Bug M1：冷启动直入多摄时 `isRawSupported` 恒为 `false`，静默关闭多摄 HDR+**：
   - [CameraMultiCaptureDispatcher.kt#L73](file:///home/maary/.gemini/antigravity/worktrees/Darkbag/review_zsl_implementation/app/src/main/java/top/maary/darkbag/camera/dispatch/CameraMultiCaptureDispatcher.kt#L73) 使用 `val isHdrPlusActive = env.isHdrPlusEnabled && env.runtimeState.isRawSupported`。
   - 然而 `env.runtimeState.isRawSupported` 默认为 `false`（[CameraRuntimeStateHolder.kt#L36](file:///home/maary/.gemini/antigravity/worktrees/Darkbag/review_zsl_implementation/app/src/main/java/top/maary/darkbag/camera/state/CameraRuntimeStateHolder.kt#L36)），且**仅**由单摄流控制器 [CameraHardwareStreamController.kt#L83](file:///home/maary/.gemini/antigravity/worktrees/Darkbag/review_zsl_implementation/app/src/main/java/top/maary/darkbag/camera/session/CameraHardwareStreamController.kt#L83) 赋值。当用户通过 `activeCaptureMode = MULTI_CAMERA` 直接打开 App 时，[CameraModeController.kt#L220](file:///home/maary/.gemini/antigravity/worktrees/Darkbag/review_zsl_implementation/app/src/main/java/top/maary/darkbag/camera/delegate/CameraModeController.kt#L220) 关闭/绕过了单摄 Session，导致 `isRawSupported` 永远停留在 `false`！
2. **Bug M2：多摄策略层当前并未做多帧 HDR+ 融合，仅写入了 EXIF/DNG 标记**：
   - 在 [NativeLogicalCaptureStrategy.kt#L159, L181](file:///home/maary/.gemini/antigravity/worktrees/Darkbag/review_zsl_implementation/app/src/main/java/top/maary/darkbag/camera/multicam/NativeLogicalCaptureStrategy.kt#L159) 与 [FastRelayCaptureStrategy.kt#L153, L167](file:///home/maary/.gemini/antigravity/worktrees/Darkbag/review_zsl_implementation/app/src/main/java/top/maary/darkbag/camera/multicam/FastRelayCaptureStrategy.kt#L153) 中，虽然接收了 `isHdrPlusActive`，但实际只向 Camera2 下发了 **1 次单帧 `session.capture(...)`**，既没有执行 `ExposureUtils.calculateHdrPlusExposure` 欠曝连拍，也没有调用 `HdrPlusStreamingBurst` / Halide 多帧对齐融合。
   - `isHdrPlus = isHdrPlusActive && isPrimary` 仅被传给 [MultiCamDngMetadataWriter.kt#L80-L90](file:///home/maary/.gemini/antigravity/worktrees/Darkbag/review_zsl_implementation/app/src/main/java/top/maary/darkbag/camera/multicam/MultiCamDngMetadataWriter.kt#L80-L90)，作用仅是在 `writeRawImageToDng` 里标记元数据；随后 `CameraMultiCaptureDispatcher` 再对该**单张 DNG** 调用一次 `ColorProcessor.processRaw(...)`（单帧解码）。
   - [ConcurrentStandaloneCaptureStrategy.kt#L42-L47](file:///home/maary/.gemini/antigravity/worktrees/Darkbag/review_zsl_implementation/app/src/main/java/top/maary/darkbag/camera/multicam/ConcurrentStandaloneCaptureStrategy.kt#L42-L47) 甚至完全未接收 `isHdrPlusActive` 参数。

#### 二、多摄预览主镜头启用 ZSL 与“有限的 HDR+”（3 帧）的方案设计
多摄同拍包含主摄（当前预览焦段镜头，用户正在用它构图对焦）与副摄（并发同步抓拍的其他物理镜头）。为避免多路并发全分辨率连拍撑爆 HAL 带宽与内存，应当采取**主镜头 ZSL / 有限 HDR+，副镜头单帧同步跟随**的非对称混合方案：

| 硬件多摄架构 | 主镜头（Preview Primary Lens）策略 | 副镜头（Secondary Lenses）策略 | 硬件与内存约束保护 |
| :--- | :--- | :--- | :--- |
| **`NATIVE_LOGICAL`** | 1. **有限 HDR+（建议上限 3 帧）**：若主镜头支持 `RAW_SENSOR`，以 3 帧连拍替代单帧。<br>2. **ZSL 主镜头（可选增强）**：在 `MultiCameraPreviewController` 创建 `SessionConfiguration` 时检测主物理镜头是否允许连续挂载 `rawImageReader`（`maxImages = 6`，环深固定上限 **3 帧**）；允许则启用 `ZslRawRing`，按下快门零延迟吐出 3 帧种子送入 `HdrPlusStreamingBurst`，副摄同批次 1 帧。 | 保持 1 帧 `STILL_CAPTURE`（对齐主镜头 `targetExposureProductMap` 光度参数）。 | 必须经过 `device.isSessionConfigurationSupported(sessionConfig)` 校验（[MultiCameraPreviewController.kt#L113](file:///home/maary/.gemini/antigravity/worktrees/Darkbag/review_zsl_implementation/app/src/main/java/top/maary/darkbag/camera/multicam/MultiCameraPreviewController.kt#L113)）：若 HAL 因多路并发拒绝主镜头持续 RAW Repeating，则自动降级为**前向主镜头 3 帧 `captureBurst` + 副镜头 1 帧**，不崩溃不切单摄。 |
| **`CONCURRENT_STANDALONE`** | 主镜头独享独立 `CameraDevice` 与 `CameraCaptureSession`（`openSessions[primaryId]`），物理上与单摄完全一致，可直接跑 3 帧 ZSL 环或 3 帧前向 HDR+ 连拍（`HdrPlusStreamingBurst`）。 | 其他已打开的独立 `CameraDevice` 同步触发 1 帧拍照。 | 环深硬上限限制为 `MIN_HDR_DEPTH = 3`（与单摄 128MiB 池共享配额，防止多设备并行 OOM）。 |
| **`FAST_RELAY_BURST`** | 在关闭主镜头切换副镜头（`previewCtrl.closeInternal()`）**之前**，主镜头已经处于稳定预览态：支持直接使用主镜头 3 帧 ZSL 预卷（若已武装）或 3 帧前向 HDR+ 连拍完成取帧，然后再关会话接力拍摄副镜头。 | 逐一打开副镜头各拍 1 帧。 | 主镜头接力期间需注意 `MultiCameraManualConfig` 锁定与预览恢复时序。 |

- **多摄主镜头 HDR+ 后处理落地路径**：
  - 将 `NativeLogicalCaptureStrategy` / `FastRelayCaptureStrategy` / `ConcurrentStandaloneCaptureStrategy` 中**主镜头**产出的 3 帧 RAW（或 3 帧 ZSL Seed）直接送入 `ColorProcessor.nativeCreateStreamingSession(..., frameCount = 3)` 走与单摄完全一致的 Halide 对齐/融合/导出管道（或通过 `PrimaryHdrFrameResult` 输出融合后的 `tempDngPath` + `renderedJpgPath` 给 `CameraMultiCaptureDispatcher`），从而真正获得多帧降噪与高光动态范围；副镜头继续走单帧 `write_dng` 压缩 DNG 路径。
- **修复 `isRawSupported` 校验**：
  - `CameraMultiCaptureDispatcher` 判断 `isHdrPlusActive` 不应依赖单摄遗留的 `env.runtimeState.isRawSupported`，而应直接检查 `env.isHdrPlusEnabled && primaryLens != null && dngWriter.isRawSupportedForLens(primaryLens)`（并在 `MultiCameraCaptureManager` 配置主镜头时同步回填 `runtimeState.isRawSupported`）。

---

## 2. 议题二：多摄同拍设置项“强制开启”开关联动与 Bento 规范审计

### 2.1 当前存在的问题（对比源代码与 Invariant 5）

审阅 [fragment_settings.xml#L949-L1080](file:///home/maary/.gemini/antigravity/worktrees/Darkbag/review_zsl_implementation/app/src/main/res/layout/fragment_settings.xml#L949-L1080)、[SettingsBentoDelegate.kt#L303-L315](file:///home/maary/.gemini/antigravity/worktrees/Darkbag/review_zsl_implementation/app/src/main/java/top/maary/darkbag/fragments/settings/SettingsBentoDelegate.kt#L303-L315)、[SettingsFragment.kt#L572-L615](file:///home/maary/.gemini/antigravity/worktrees/Darkbag/review_zsl_implementation/app/src/main/java/top/maary/darkbag/fragments/SettingsFragment.kt#L572-L615) 及 [CameraModeController.kt#L106, L138-L142](file:///home/maary/.gemini/antigravity/worktrees/Darkbag/review_zsl_implementation/app/src/main/java/top/maary/darkbag/camera/delegate/CameraModeController.kt#L106)：

1. **关闭多摄主开关后，`switch_multi_camera_force_enable` 孤立残留且逻辑越权**：
   - 在 `SettingsBentoDelegate.renderStylingAndVisibility`（`#L311-L314`）中：
     ```kotlin
     val mcEnabled = state.multiCameraModeEnabled
     binding.btnMenuMultiCameraCount.visibility = if (mcEnabled) View.VISIBLE else View.GONE
     binding.btnMenuMultiCameraDualPair.visibility = if (mcEnabled && ...) View.VISIBLE else View.GONE
     binding.tileMultiCameraSaveRaw.visibility = if (mcEnabled) View.VISIBLE else View.GONE
     ```
     **唯独没有控制 `binding.switchMultiCameraForceEnable`**！关闭 `switch_multi_camera_mode` 后，右侧的 `tileMultiCameraSaveRaw` 消失，而左侧的 `switchMultiCameraForceEnable`（"强制开启 / BYPASS"）依然亮着显示、可被打开。
   - **更严重的逻辑漏洞（[CameraModeController.kt#L106, L141](file:///home/maary/.gemini/antigravity/worktrees/Darkbag/review_zsl_implementation/app/src/main/java/top/maary/darkbag/camera/delegate/CameraModeController.kt#L106)）**：
     ```kotlin
     val isMultiCamPref = current.multiCameraModeEnabled || current.multiCameraForceEnable
     ```
     只要 `multiCameraForceEnable == true`，哪怕用户**明确关闭了** `multiCameraModeEnabled = false`，相机控制器依然认为启用了多摄同拍！这导致用户“在设置里明明关了多摄开关，相机里却仍然被强制打开多摄”。
2. **隐藏子控件导致 54dp 空行畸形留白**：
   - Row 2 的 `BentoRowLayout`（[fragment_settings.xml#L1005-L1031](file:///home/maary/.gemini/antigravity/worktrees/Darkbag/review_zsl_implementation/app/src/main/res/layout/fragment_settings.xml#L1005-L1031)）没有 `id`，且写死了 `android:layout_height="54dp"`。当子按钮全部设成 `View.GONE` 时，该 `BentoRowLayout` 依然占据 **54dp 空白高度**，形成大块空洞。反观半格卡片（`fragment_settings.xml#L520`）用独立的 `LinearLayout (id="@+id/layout_half_frame_options")` 包裹全部展开项，收起时整个容器 `GONE`，零留白！
3. **违反 `AGENTS.md` Invariant 5（Bento Matrix 设计规范）的多处细节**：
   - **容器语义角色错位与非动态响应**：多摄子托盘（`fragment_settings.xml#L949-L956`）写死了 `app:bentoContainerRole="tertiaryContainer"`（应是半格专用色），既不符合多摄在相机界面绑定的 `secondaryContainer` 语义色（[AGENTS.md Invariant 5](file:///home/maary/.gemini/antigravity/worktrees/Darkbag/review_zsl_implementation/AGENTS.md)），关闭状态下也未像半格那样动态切换为 `SURFACE_CONTAINER` / `SURFACE_CONTAINER_HIGH`。
   - **多余 1dp 边框**：`tile_multi_camera_save_raw`（`#L1055`）与 `tile_hf_save_raw`（`#L564`）使用了 `strokeWidth="1dp"` + `colorOutlineVariant`，而 Invariant 5 规定外层容器填充与白瓷内瓦片（`?attr/colorSurface`）通过对比度分层，不应使用 1dp 描边作为主视觉分隔。
   - **XML 硬编码双语/英文占位**：`btn_menu_multi_camera_count`（`#L1019`）与 `btn_menu_multi_camera_dual_pair`（`#L1030`）使用了 `android:text="CAMERA COUNT\nAuto (Max 3)"` 而非 `tools:text`。

### 2.2 符合设计规范的重构方案
1. **厘清 `Force Enable` 职责，修正 `CameraModeController` 与主/子开关关系**：
   - `switch_multi_camera_force_enable` 的唯一职责是“当设备未在 Camera2 标准接口声明物理多摄并发支持时，绕过硬件白名单以允许尝试打开多摄开关”（即解锁开关权限，**而不是替用户开启多摄模式**）。
   - 修复 [CameraModeController.kt#L106, L141](file:///home/maary/.gemini/antigravity/worktrees/Darkbag/review_zsl_implementation/app/src/main/java/top/maary/darkbag/camera/delegate/CameraModeController.kt#L106)：改为 `current.multiCameraModeEnabled && MultiCameraHelper.isMultiCameraSupported(env.hostContext, current.multiCameraForceEnable)`。彻底杜绝“主开关已关却因 Force Enable 残留导致相机强开多摄”。
   - 在设置联动中按硬件能力分类呈现（完全符合 Bento Matrix 渐进式披露）：
     - **情况 A（设备原生支持多摄，`MultiCameraHelper.isMultiCameraSupported(ctx, forceEnable = false) == true`）**：硬件已支持，无需绕过检测，`switch_multi_camera_force_enable` 直接设为 `View.GONE`（或随 `multiCameraModeEnabled` 收起时一并隐藏并重置为 `false`），避免用户困惑。
     - **情况 B（设备硬件不支持原生逻辑多摄，需 Bypass 解锁）**：`switch_multi_camera_force_enable` 允许留在页面（或在实验区/多摄状态栏下显示解锁入口），当用户关闭 `switch_multi_camera_mode` 时，其余多摄配置项（`layout_multi_camera_options`：镜头数、双摄组合、保存 RAW）全部 `View.GONE`；而若用户本就是关闭多摄且不再需要绕过，提供清晰状态或随主开关关闭时一并联动重置/置灰提示。更优实践：将 `switch_multi_camera_force_enable` 独立成解锁前置开关或放入 `layout_multi_camera_options` 仅对不支持的设备常驻底部且带 `enabled/alpha` 语义提示，而对已支持多摄的主流设备彻底隐藏 Bypass 开关。
2. **结构与视觉重构对齐 Invariant 5**：
   - 为多摄子托盘添加 `android:id="@+id/card_tray_adv_multicam"` 与展开容器 `android:id="@+id/layout_multi_camera_options"`；关闭 `switch_multi_camera_mode` 时整组 `layout_multi_camera_options.visibility = View.GONE`，彻底消除 54dp 空洞。
   - 多摄子托盘动态角色：开启时切换 `SECONDARY_CONTAINER`（标题用 `colorOnSecondaryContainer` / `colorSecondary`），关闭时切换 `SURFACE_CONTAINER_HIGH`（子级嵌套托盘保持与 `card_tray_exp_zsl` 一致的同心质感）。
   - 清理 `tile_multi_camera_save_raw` 的 `strokeWidth="1dp"` 为 `0dp`（纯白瓷内瓦片），并将 XML 硬编码 `android:text` 改为 `tools:text`。

---

## 3. 议题三：拍后缩略图未正常显示与进入图库显示上一张照片（需刷新才出）的根因定位

经全链路追踪（采集导出 -> 服务通知 -> 缩略图加载 -> 缓存失效 -> 查看器/画廊流），发现这是由 **4 条并发/时序/缓存链路的叠加 Bug** 共同引发的：

### 3.1 问题 1：为什么拍摄后缩略图经常没有正常出现（保持占位图标/空白/卡在旧图）？

1. **Bug 3.1（`HdrPlusProcessingService` 与 `DirectMediaStoreSink` / `ImageSaver` 之间的完成信号错位与竞态）**：
   - 观察 [HdrPlusProcessingService.kt#L233-L246, L324](file:///home/maary/.gemini/antigravity/worktrees/Darkbag/review_zsl_implementation/app/src/main/java/top/maary/darkbag/processor/HdrPlusProcessingService.kt#L233-L246) 与 [DirectMediaStoreSink.kt#L34-L100](file:///home/maary/.gemini/antigravity/worktrees/Darkbag/review_zsl_implementation/app/src/main/java/top/maary/darkbag/pipeline/sink/DirectMediaStoreSink.kt#L34-L100)：
   - **路径 A（开启 Dynamic Photo 动态照片，或设置了自定义 SAF 存储目录时）**：
     - `DirectMediaStoreSink.prepareTargets` 中第 34 行条件 `if (spec.saveJpg && spec.jpgFolderUri == null && spec.motionPhotoMp4Path == null)` 为 **`false`**，`pfdJpg` 为 **`null`**！
     - Stage 2 导出 JPG 结束后，第 233 行 `sink.onImageExported(...)` 因 `pfdJpg == null` **什么也不做（不发布 `backgroundSaveFlow`）**；紧接着第 241 行直接调用 **`notifyForegroundComplete()`**！
     - 这导致 `pendingForegroundTasksCount` 提前降为 `0`（[CameraControllerHub.kt#L398-L404](file:///home/maary/.gemini/antigravity/worktrees/Darkbag/review_zsl_implementation/app/src/main/java/top/maary/darkbag/camera/CameraControllerHub.kt#L398-L404)），**加载圆环提前消失，并用旧的 `currentThumbnailUri` 刷新缩略图**——而此时 DNG 导出（第 250 行）和 `sink.onComplete` 里的真实入库（第 324 行）甚至还没开始！要再等 1~3 秒后才由 `ImageSaver` 补发事件；如果用户在这期间切后台/进图库，`CameraFragment` 已因 `onDestroyView()` 取消订阅，`backgroundSaveFlow` 彻底丢失。
   - **路径 B（只保存 RAW 不保存 JPG + 自定义 SAF 目录）**：
     - 同样 `pfdDng == null`，走 `sink.onComplete` -> `ImageSaver.saveProcessedImage`。查看 [ImageSaver.kt#L258](file:///home/maary/.gemini/antigravity/worktrees/Darkbag/review_zsl_implementation/app/src/main/java/top/maary/darkbag/utils/ImageSaver.kt#L258)：
       `if (!isFastPath && finalJpgUri != null)` -> `finalJpgUri` 为 `null`，**彻底遗漏了 `finalRawUri`，`backgroundSaveFlow` 永远不会被触发！**
   - **路径 C（标准 MediaStore JPG，无动态照片时序竞态）**：
     - `onImageExported`（第 233 行）先 `tryEmit(backgroundSaveFlow)`，第 241 行才调用 `notifyForegroundComplete()`。若 `CameraFragment` 先收到 `onBackgroundSaveCompleted`，此时 `env.runtimeState.isProcessing` 仍为 `true`（`pendingForegroundTasksCount` 尚未收到更新），[CameraThumbnailController.kt#L67-L79](file:///home/maary/.gemini/antigravity/worktrees/Darkbag/review_zsl_implementation/app/src/main/java/top/maary/darkbag/camera/delegate/CameraThumbnailController.kt#L67-L79) 判定 `shouldHide == true`，把 `photoViewButton.visibility` 设为 `INVISIBLE` 并发起一次 Glide 加载；随后 `onForegroundTaskCountChanged(0)` 到达又二次触发 `setGalleryThumbnail`。

2. **Bug 3.2（`CameraThumbnailController` 不支持 DNG 解码 & Glide 同步时序问题）**：
   - 当用户仅开启 **保存 DNG（关闭保存 JPG）** 时，`targetUri` 指向的是 `.dng` 文件。查看 [CameraThumbnailController.kt#L84-L111](file:///home/maary/.gemini/antigravity/worktrees/Darkbag/review_zsl_implementation/app/src/main/java/top/maary/darkbag/camera/delegate/CameraThumbnailController.kt#L84-L111)：只判断了 `.rawvid` 视频帧解码，没有判断 DNG 并复用 [GalleryThumbnailLoader.loadDngThumbnail](file:///home/maary/.gemini/antigravity/worktrees/Darkbag/review_zsl_implementation/app/src/main/java/top/maary/darkbag/utils/GalleryThumbnailLoader.kt#L30)，直接把 DNG `content://` 字符串丢给 Glide，Glide 无法直接解码 16-bit Bayer DNG，导致加载失败并保留 `@drawable/ic_photo` 空白/图标。
   - **Glide 上下文 & 签名隐患**：`onBackgroundSaveCompleted` 刚调用完 `finalizeMediaStorePendingPfd`（更新 `IS_PENDING = 0`）就立即触发 Glide 读取 `getFileLastModified`：部分 ROM 的 MediaStore `DATE_MODIFIED` 刷新存在几十毫秒延迟，若返回 `0L` 且 `lastCaptureUri` 是异步 DataStore 写入（`SettingsRepository.setLastCaptureUri` 使用异步 `scope.launch`），若在写入落盘前切页重建 UI（`updateCameraUi()` -> `loadLatestThumbnail()`），会重新读出旧的 `lastCaptureUri`！

---

### 3.2 问题 2：为什么即使缩略图已出现，点击进入图库仍然显示“上一次”的照片，需要状态刷新后最近拍摄的才出来？

这条现象是 **Stale-While-Revalidate (SWR) 缓存机制 + `ImageViewerFragment` 首帧落点锚定 + `DarkroomGalleryFragment` 缺失事件刷新** 共同造成的精准死锁：

1. **Bug 3.3（致命主因：`ImageRepository.getGroupedImagesFlow` Stage 0 覆盖有效缓存并先把旧列表丢给 ViewPager2，Stage 1 到达后 `targetPos` / `hasNavigated` 竞争失败）**：
   追踪用户点击缩略图后的完整时序：
   - 拍完 `IMG_NEW` 时，`CameraControllerHub.onBackgroundSaveCompleted` 调用 `env.imageRepository.invalidateCache()`（**`clearStale = false`**）。
   - 查看 [GalleryCacheStore.kt#L47-L59](file:///home/maary/.gemini/antigravity/worktrees/Darkbag/review_zsl_implementation/app/src/main/java/top/maary/darkbag/repository/GalleryCacheStore.kt#L47-L59)：`invalidate(clearStale = false)` 会把**拍新照片之前的旧列表 `[IMG_PREV, ...]` 存入 `staleCachedGroups`**，并把 `cachedGroups` 置 `null`。
   - 用户点击缩略图 -> 导航到 `ImageViewerFragment(initialUri = IMG_NEW_URI)`。
   - `ImageViewerFragment`（`#L247`）调用 `loadImages(forceRefresh = false)` -> 调用 [ImageRepository.kt#L103-L122](file:///home/maary/.gemini/antigravity/worktrees/Darkbag/review_zsl_implementation/app/src/main/java/top/maary/darkbag/repository/ImageRepository.kt#L103-L122) 的 `getGroupedImagesFlow(targetUri)`：
     1. **Stage 0** 读取 `cacheStore.cachedGroups ?: cacheStore.staleCachedGroups` -> 命中 `[IMG_PREV, ...]`（**根本不包含 `IMG_NEW`！**）。
     2. **第 114 行 `emitCurrent()` 更直接把缺少 `IMG_NEW` 的旧列表重新写回了 `cacheStore.cachedGroups`！** 同时向 `ImageViewerFragment` 吐出第 1 帧数据 `[IMG_PREV, ...]`。
     3. **`ImageViewerFragment`（`#L354-L372`）收到第 1 帧数据（`isFirst = true`）**：
        - `IMG_NEW` 不在列表中，`targetPos = -1`；
        - **`attachInitialAdapter([IMG_PREV, ...])` 立即把 `ViewPager2` 挂载并展示第 0 项——也就是上一次拍的照片 `IMG_PREV`！**
     4. **随后 Stage 1 异步扫描结束并吐出第 2 帧 `[IMG_NEW, IMG_PREV, ...]`（`isFirst = false`，`#L373-L386`）**：
        - **情况 A（刚拍照完/Motion Photo/大目录 Stage 1 扫描瞬间）**：如果此时 `SettingsRepository.setLastCaptureUri` 刚更新但 `MediaStore` 刚解除 `IS_PENDING = 0` 或 SAF `DocumentFile` 列表未同步、或者 Stage 1 在子线程尚未把 `IMG_NEW` 纳入（尤其当 `targetUri` 来自 `DirectMediaStoreSink` 在 `onImageExported` 发出时而 SAF RAW / DNG 还未写完，或 `ImageUtils.getBaseName("content://media/...")` 取不到文件名而是取了数字 ID），`matchesTarget` 匹配不到，或者：
        - **情况 B（`AsyncListDiffer` 在第 0 位前置插入导致 ViewPager2 锚定偏移与 `setCurrentItem(0)` 失效/延迟）**：`DiffUtil` 在索引 `0` 前插 `IMG_NEW`，`RecyclerView`/`ViewPager2` 为了保住当前正在看的内容 `IMG_PREV`，会把 `currentItem` 自动推移到 `1`（仍然显示 `IMG_PREV`）。虽然 `#L377-L378` 检查 `!hasNavigated && targetPos in groups.indices` 并调 `setCurrentItem(0, false)`，但如果用户进入时带 `targetUri == null`（例如点击或从旧路径进入），**`targetPos == -1`**，就会执行 `#L380` 分支 **`val np = groups.indexOfFirst { it.baseName == curBase }`，把页面强行锁定回 `IMG_PREV`（索引 1）**！
        - **情况 C（用户在 `ImageViewerFragment` 内已打开或 `handleBackgroundSaveEvent` 触发）**：查看 [ViewerMediaPreloadDelegate.kt#L202-L212](file:///home/maary/.gemini/antigravity/worktrees/Darkbag/review_zsl_implementation/app/src/main/java/top/maary/darkbag/viewer/ViewerMediaPreloadDelegate.kt#L202-L212)，`handleBackgroundSaveEvent` 在 `adapter.updateGroups(newGroups)` 后**完全没有跳转逻辑**，新图被插到第 0 页，而 `ViewPager2` 留在第 1 页（即上一次拍的照片）上不动，直到用户手动左滑或点进暗房画廊刷新才看到最新照片！

2. **Bug 3.4（长按或切换进入 `DarkroomGalleryFragment` 同样撞上 `staleCachedGroups` 且完全不监听新图完成事件）**：
   - `DarkroomGalleryFragment` 调用 `DarkroomGalleryViewModel.loadImages()`，后者只收集 `repository.getGroupedImagesFlow()`，进入时先展示 `staleCachedGroups`（缺最新照片），且**完全没有订阅 `ColorProcessor.backgroundSaveFlow`**。若用户在照片后台落盘最后一刻（例如动态照片缝合 / DNG 压缩尚未完全退出期间）长按缩略图进入画廊，画廊列表停留在旧快照，必须触发删图/导入/重新进入才会显示最近拍摄的照片。

---

## 4. 分阶段实施修改计划（Roadmap）

建议严格沿用 `AGENTS.md` 规范，拆分为以下 3 个原子阶段实施并经独立审查闭环验收：

### 阶段 E：ZSL 冲突逻辑修正、半格兼容与多摄限制放宽（对应议题一 + 议题二）
1. **修正设置页与策略层语义**：
   - 修改 `ZslArmingPolicy.ContextFacts`：将 `isNormalCaptureMode` 语义替换为 `isSupportedCaptureMode = (mode == CaptureMode.NORMAL || mode.isHalfFrame)`，使**半格模式允许 Armed ZSL**。
   - 修改 [SettingsBentoDelegate.kt#L385, L445-L450](file:///home/maary/.gemini/antigravity/worktrees/Darkbag/review_zsl_implementation/app/src/main/java/top/maary/darkbag/fragments/settings/SettingsBentoDelegate.kt#L385)：改为检测真实生效模式 `val activeMode = CaptureMode.fromKey(state.activeCaptureMode)`，仅当 `activeMode == CaptureMode.MULTI_CAMERA`（若尚未完成主摄 ZSL 前）且处于真正互斥状态时才报告冲突；开启 `halfFrameMode` / `multiCameraModeEnabled` 开关本身**不再触发 ZSL 冲突提示**。
2. **修复半格 + ZSL 断链缺陷**：
   - 在 `ZslCaptureController.tryCaptureHdrPlus` -> `captureInstantSingle` 透传 `isFrame1Trigger`；
   - 在 `CameraCapturePipelineDispatcher.dispatchZslSingleFrame` 中补齐 `isFrame1Trigger` 时的 `coordinator.getDigitalGainAndUpdateStep(...)`（写入 Frame 1 数字增益与步进）及 `coordinator.triggerAutoBurst()`。
3. **修复多摄同拍设置项与逻辑（议题二）**：
   - 修改 [CameraModeController.kt#L106, L141](file:///home/maary/.gemini/antigravity/worktrees/Darkbag/review_zsl_implementation/app/src/main/java/top/maary/darkbag/camera/delegate/CameraModeController.kt#L106)：`multiCameraForceEnable` 仅作为硬件检测绕过参数，**必须同时满足 `current.multiCameraModeEnabled == true`**。
   - 重构 [fragment_settings.xml#L949-L1080](file:///home/maary/.gemini/antigravity/worktrees/Darkbag/review_zsl_implementation/app/src/main/res/layout/fragment_settings.xml#L949-L1080) 与 [SettingsBentoDelegate.kt](file:///home/maary/.gemini/antigravity/worktrees/Darkbag/review_zsl_implementation/app/src/main/java/top/maary/darkbag/fragments/settings/SettingsBentoDelegate.kt)：
     - 新增 `card_tray_adv_multicam` 与 `layout_multi_camera_options` 包裹展开区，关闭多摄时整组 `View.GONE` 消除 54dp 空行；
     - 当设备原生支持多摄时隐藏 `switch_multi_camera_force_enable`（或在关闭多摄时收起并同步关闭/禁用），彻底遵循 Bento Matrix 渐进式披露与 `SECONDARY_CONTAINER` 动态色彩角色规范，去掉 1dp 多余边框及 XML 硬编码占位。

### 阶段 F：多摄预览主镜头 ZSL + 有限 HDR+（3 帧融合）落地（对应议题一后半部分）
1. **修复冷启动 `isRawSupported` 盲区**：
   - `CameraMultiCaptureDispatcher` 通过 `dngWriter.isRawSupportedForLens(primaryLens)` 直接判断主物理镜头 RAW 能力，并在会话打开后回填 `runtimeState.isRawSupported`。
2. **打通主镜头 3 帧有限 HDR+ 与可选 ZSL 预卷**：
   - 在 `MultiCameraPreviewController` 与三种策略（`NativeLogicalCaptureStrategy`、`ConcurrentStandaloneCaptureStrategy`、`FastRelayCaptureStrategy`）中为主镜头增加 **3 帧连拍 / ZSL 预卷路径**：
     - 检测会话允许（`isSessionConfigurationSupported`）时，允许在主物理镜头启用上限 3 帧的 `ZslRawRing`（或前向 3 帧 `captureBurst` + `ExposureUtils.calculateHdrPlusExposure`）；
     - 主镜头 RAW 经 `HdrPlusStreamingBurst`（3 帧）完成真正的 Halide HDR+ 对齐融合后产出渲染 JPG 与压缩 DNG；副镜头同步拍 1 帧并维持现有的光度对齐与 `write_dng`。
     - 若 HAL 并发流带宽受限不支持主镜头持续 RAW Repeating，则优雅降级为前向 3 帧 HDR+（保证安全零崩溃）。
   - 同步更新 `ZslArmingPolicy` 与设置页状态文案：在多摄模式下且主镜头 ZSL 就绪时展示“主镜头 3 帧 ZSL 就绪”而非直接冲突。

### 阶段 G：拍后缩略图与图库/查看器刷新一致性修复（对应议题三）
1. **统一并修正前台完成信号与后台保存流时序（消除提前结束与漏发）**：
   - 在 [DirectMediaStoreSink.kt](file:///home/maary/.gemini/antigravity/worktrees/Darkbag/review_zsl_implementation/app/src/main/java/top/maary/darkbag/pipeline/sink/DirectMediaStoreSink.kt) 与 [HdrPlusProcessingService.kt](file:///home/maary/.gemini/antigravity/worktrees/Darkbag/review_zsl_implementation/app/src/main/java/top/maary/darkbag/processor/HdrPlusProcessingService.kt)：
     - 当走 `sink.onComplete` 二级导出（如 **动态照片 Motion Photo**、**SAF 自定义目录**、**半格第 2 帧拼接**）时，延迟 `notifyForegroundComplete()` 到 `ImageSaver.saveProcessedImage` 写入落盘并触发 `backgroundSaveFlow` **之后**再执行，彻底杜绝“加载圆环先灭、缩略图仍是旧图”。
   - 在 [ImageSaver.kt#L258](file:///home/maary/.gemini/antigravity/worktrees/Darkbag/review_zsl_implementation/app/src/main/java/top/maary/darkbag/utils/ImageSaver.kt#L258)：改为 `val finalTargetUri = finalJpgUri ?: finalRawUri`，使仅保存 SAF DNG 时也能正常触发 `backgroundSaveFlow`。
   - 将 `ColorProcessor.backgroundSaveFlow` 与缩略图刷新状态解耦出视图生命周期丢包风险，并把 `SettingsRepository.setLastCaptureUri` 的内存快照更新做成即时同步可见（先同步更新内存 `StateFlow` 缓存再异步写 DataStore），防止重进相机时 `loadLatestThumbnail()` 读到旧 URI。
2. **补齐 DNG 缩略图解码**：
   - 在 [CameraThumbnailController.kt](file:///home/maary/.gemini/antigravity/worktrees/Darkbag/review_zsl_implementation/app/src/main/java/top/maary/darkbag/camera/delegate/CameraThumbnailController.kt) 中，当检测到 `.dng`（或 `image/x-adobe-dng` MIME 类型）时复用 `GalleryThumbnailLoader.loadDngThumbnail()` 解码预览位图喂给 `Glide`，彻底解决只拍 RAW 时缩略图空白的问题。
3. **修复 `ImageRepository` / `ImageViewerFragment` / `DarkroomGalleryFragment` 缓存滞后问题**：
   - **彻底解决“进入后先看到上一张、刷新才出新图”**：
     - 当 `invalidateCache()` 因**新增拍摄产物**被调用时，若明确传入新产物 URI 或使用 `invalidateCache(clearStale = true)`（或主动把刚落盘的新照片信息前置注入缓存 / 在存在 `targetUri` 且不在 `staleCachedGroups` 时跳过 Stage 0 阻塞发射），严禁 `ImageViewerFragment` 用缺少最新照片的 `staleCachedGroups` 去初始化第 0 页再写回 `cachedGroups`！
     - 在 `ImageViewerFragment.loadImages` 当 `targetUri` 尚不在 Stage 0 列表时，等待 Stage 1 扫描完成或展示加载指示器而非直接把 `IMG_PREV` 绑定到第 0 页；并且在 `ViewerMediaPreloadDelegate.handleBackgroundSaveEvent` 收到当前刚拍的新图落盘事件时，自动平滑定位到新图（当用户尚处于首帧浏览刚拍完的照片时）。
     - 在 `DarkroomGalleryViewModel` 中订阅 `ColorProcessor.backgroundSaveFlow`，当暗房画廊处于活跃态且有新照片落盘完成时自动触发刷新，无需用户手动触发其他操作刷新状态。

---

*本文档作为下一阶段实现（Stage E / F / G）与代码审查的依据。确认计划无误后可按阶段逐步执行并逐阶段验收提交。*
