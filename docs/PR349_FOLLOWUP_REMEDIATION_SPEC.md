# PR #349 后续收口与 Motion Photo 暗部对齐实施规范

> [!IMPORTANT]
> 本文档为 `feat/default-color-engine-v2` 分支（PR #349）的**强制实施与验收规范**。Implementer 与 Reviewer 必须逐条对照本规范执行，不得擅自删减、偏离或跳过任何可验证门禁。

---

## 1. 背景与目标

基于对 PR #349 的代码审查以及 Moto XT2409-5 (`ZY22KMP2XK`) 的实机 `logcat` 与 Motion Photo 样张像素级分析，当前分支存在以下必须收口的问题：

1. **`ZY22KMP2XK` 等设备未上报 `TONEMAP_MODE_PRESET_CURVE` (4)**：PR #349 的 1B 在真机上静默回退到 `TONEMAP_MODE_FAST`，导致无论无 Log/LUT 还是 `F-Log2 + ASTIA`，取景器与 Motion Photo 仍承受「ISP 厂商 S 曲线 × 软件色彩管线」的双重暗部压黑。
2. **预览请求点漏网（PR #349 Blocker 2）**：`HdrPlusBurstCaptureExecutor`、`CameraVideoController`、`CameraFocusMeteringController`、`MultiCameraPreviewController.triggerFast3AWarmup`、`ConcurrentFrontCameraManager` 未应用预览 ToneMap 策略。
3. **CPU 多帧高光退饱和回归（PR #349 Blocker 1）**：`ColorPipe.cpp` 移除 `50000` knee 后遗留了 `if (!faithfulHighlights)` 的 `y_lum` 退饱和块，导致过曝像素在 `sensorBlown` 中和后变成 `~0.75x` 暗灰斑块。
4. **Sony Uchimura Toe 区退饱和泄漏（PR #349 Medium 4）**：`peak < 0.22f` 时 `uchimura_scalar(peak) < peak`，导致暗部出现轻微意外退饱和。
5. **Motion Photo 编码器独立瑕疵**：`MotionPhotoEncoder` 缺少 H.264 BT.709/Limited/SDR VUI 标签；`LutSurfaceProcessor` 初始默认 `currentColorEngineMode = 0` 与全局默认 `2` 不一致。

*(注：Mode 1 AgX 曲线的 `W_eff` 归一化与阶段四 4.2 的 `uIsToneMapBypassed` 软补偿不纳入本轮，待本规范真机实测后再行评估。)*

---

## 2. 分阶段技术规范

### 阶段一：三梯队 Preview ToneMap 策略 + 32 点 sRGB `TonemapCurve`

1. **抽出纯 Kotlin 无 UI 依赖的策略工具（可 JVM 单测）**：
   * 建议新建 `app/src/main/java/top/maary/darkbag/camera/session/PreviewToneMapPolicy.kt`（或置于同包合适文件中）：
   * 定义枚举 `PreviewToneMapStrategy`：
     * `PRESET_CURVE_SRGB`（需 `availableModes` 含 `TONEMAP_MODE_PRESET_CURVE`）
     * `CONTRAST_CURVE_SRGB`（需 `availableModes` 含 `TONEMAP_MODE_CONTRAST_CURVE` 且 `maxCurvePoints >= 16`）
     * `GAMMA_VALUE_22`（需 `availableModes` 含 `TONEMAP_MODE_GAMMA_VALUE`）
     * `FAST_FALLBACK`（兜底 `TONEMAP_MODE_FAST`）
   * 实现 `resolvePreviewToneMapStrategy(availableModes: IntArray?, maxCurvePoints: Int): PreviewToneMapStrategy`，严格按上述优先级决策。
   * 实现 `buildSrgbTonemapCurvePoints(maxCurvePoints: Int): FloatArray`（返回 `[x0, y0, x1, y1, ...]`，长度 `2 * N`，其中 $N = \text{maxCurvePoints.coerceIn}(2, 32)$）：
     * 采样横坐标：$x_i = \left(\frac{i}{N - 1}\right)^{2.2}$，$i \in [0, N-1]$（确保 $x_0 = 0.0f$，$x_{N-1} = 1.0f$，严格单调递增）；
     * 采样纵坐标：标准 sRGB OETF：$y_i = (x_i \le 0.0031308) ? (12.92 x_i) : (1.055 x_i^{1/2.4} - 0.055)$（确保 $y_0 = 0.0f$，$y_{N-1} = 1.0f$）。
   * 缓存生成后的 `TonemapCurve(pts, pts, pts)`（按 `N` 缓存，避免在 repeating request 构建路径重复分配）。

2. **状态持久化与能力读取**：
   * [`CameraHardwareStreamController.CameraCharacteristicsSnapshot`](file:///home/antigravity/Steve-Mr/Darkbag/app/src/main/java/top/maary/darkbag/camera/session/CameraHardwareStreamController.kt#L65-L80) 增加：
     * `val previewToneMapStrategy: PreviewToneMapStrategy`
     * `val tonemapMaxCurvePoints: Int`
     * `val availableToneMapModes: IntArray?`（或保留 `isPresetCurveToneMapSupported` 作为派生属性 `previewToneMapStrategy == PRESET_CURVE_SRGB` 以兼容现有单测或同步更新 `CameraRuntimeStateHolderTest`）。
   * `readCameraCharacteristics(targetId)` 读取 `CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES` 与 `CameraCharacteristics.TONEMAP_MAX_CURVE_POINTS`（默认 `0`）。
   * [`CameraRuntimeStateHolder`](file:///home/antigravity/Steve-Mr/Darkbag/app/src/main/java/top/maary/darkbag/camera/state/CameraRuntimeStateHolder.kt) 同步保存 `previewToneMapStrategy`、`tonemapMaxCurvePoints` 与 `currentToneMapCameraId`。
   * [`CameraControllerHub.kt`](file:///home/antigravity/Steve-Mr/Darkbag/app/src/main/java/top/maary/darkbag/camera/CameraControllerHub.kt#L495-L503) 将上述字段传入 `state.applyCharacteristics(...)`。

3. **运行时下发、显式日志与异常降级**：
   * [`CameraHardwareStreamController.applyPreviewToneMapPolicy(request)`](file:///home/antigravity/Steve-Mr/Darkbag/app/src/main/java/top/maary/darkbag/camera/session/CameraHardwareStreamController.kt#L187-L206)：
     * 根据 `env.runtimeState.previewToneMapStrategy` 下发对应 Camera2 参数：
       * `PRESET_CURVE_SRGB` → `TONEMAP_MODE_PRESET_CURVE` + `TONEMAP_PRESET_CURVE_SRGB`
       * `CONTRAST_CURVE_SRGB` → `TONEMAP_MODE_CONTRAST_CURVE` + `TONEMAP_CURVE`
       * `GAMMA_VALUE_22` → `TONEMAP_MODE_GAMMA_VALUE` + `TONEMAP_GAMMA = 2.2f`
       * `FAST_FALLBACK` → `TONEMAP_MODE_FAST`
     * 若 `request.set(...)` 抛出 `IllegalArgumentException`，捕获并自动降级到下一可用策略（例如 `PRESET_CURVE_SRGB` 失败且支持 `CONTRAST_CURVE` 则降级尝试 `CONTRAST_CURVE_SRGB`，否则回退 `TONEMAP_MODE_FAST`），同步更新 `runtimeState.previewToneMapStrategy` 避免每帧重复异常。
     * **显式日志约束**：每次镜头切换或 `readCameraCharacteristics` 后重置日志标记（按 `targetId + strategy` 去重），**无论命中哪一个策略（包括 `FAST_FALLBACK`）都必须打印一次 `Log.i(TAG, ...)`**，严禁静默。

---

### 阶段二：全仓预览请求点 100% 收口（防呆设计）

1. **统一预览 vs 静态拍照接口**：
   * 在 [`CameraHardwareStreamController`](file:///home/antigravity/Steve-Mr/Darkbag/app/src/main/java/top/maary/darkbag/camera/session/CameraHardwareStreamController.kt#L124-L211) 中，为 `applyFocusAndExposureSettingsToRequest` 与 `applyManualSettingsToRequest` 增加 `isPreviewStream: Boolean = false` 参数（或提供清晰的默认 `false` 静态模式 + `applyPreviewSettingsToRequest`）：
     * 当 `isPreviewStream == false`（如 `SingleRawCaptureExecutor` 的 `TEMPLATE_STILL_CAPTURE`）：保持 `TONEMAP_MODE_FAST`。
     * 当 `isPreviewStream == true`：在设置完基础 3A 后**内部自动调用 `applyPreviewToneMapPolicy(request)`**。
2. **必须覆盖的全部预览请求点**：
   * [`CameraHardwareStreamController.kt`](file:///home/antigravity/Steve-Mr/Darkbag/app/src/main/java/top/maary/darkbag/camera/session/CameraHardwareStreamController.kt)：`onConfigured` 的 `previewRequest` 与 `updateCamera2RepeatingRequest`（传 `isPreviewStream = true`）。
   * [`HdrPlusBurstCaptureExecutor.kt:480-484`](file:///home/antigravity/Steve-Mr/Darkbag/app/src/main/java/top/maary/darkbag/camera/dispatch/HdrPlusBurstCaptureExecutor.kt#L480-L484)：`buildBurstRequests` 中当 `previewSurface != null && previewSurface.isValid` 时（或统一通过 `applyManualSettingsToRequest(..., isHdrBurst = true, isPreviewStream = previewSurface != null && previewSurface.isValid)`）。
   * [`CameraVideoController.kt`](file:///home/antigravity/Steve-Mr/Darkbag/app/src/main/java/top/maary/darkbag/camera/video/CameraVideoController.kt#L128-L194)：`startRawVideoRecording` (`TEMPLATE_RECORD`) 与 `stopRawVideoRecording` (`TEMPLATE_PREVIEW` 恢复预览) 均传 `isPreviewStream = true`。
   * [`CameraFocusMeteringController.kt`](file:///home/antigravity/Steve-Mr/Darkbag/app/src/main/java/top/maary/darkbag/camera/delegate/CameraFocusMeteringController.kt#L96-L231)：`cancelRequest`（第 96、177 行）与 `triggerRequest`（第 219 行）均携带 `surface`，必须应用预览 ToneMap 策略（注意通过 `applyPreviewFocusAndExposureSettings` 或在单摄/多摄共用路径生效）。
   * [`CameraControllerHub.kt:477-482`](file:///home/antigravity/Steve-Mr/Darkbag/app/src/main/java/top/maary/darkbag/camera/CameraControllerHub.kt#L477-L482)：`applyPreviewFocusAndExposureSettings` 内部传 `isPreviewStream = true`。
   * [`MultiCameraPreviewController.kt:395`](file:///home/antigravity/Steve-Mr/Darkbag/app/src/main/java/top/maary/darkbag/camera/multicam/MultiCameraPreviewController.kt#L395)：`triggerFast3AWarmup` 必须调用 `previewRequestConfigProvider?.invoke(this)`；且在多摄打开或 `setPrimaryPreviewLens` 切换物理/逻辑预览镜头时，依据实际 `device.id` 的 `CameraCharacteristics` 更新 `runtimeState` 的 ToneMap 策略。
   * [`ConcurrentFrontCameraManager.kt:135`](file:///home/antigravity/Steve-Mr/Darkbag/app/src/main/java/top/maary/darkbag/camera/ConcurrentFrontCameraManager.kt#L135)：前摄 PiP `TEMPLATE_PREVIEW` 使用 `PreviewToneMapPolicy` 对前摄 `CameraCharacteristics` 探测并应用对应策略。

---

### 阶段三：色彩管道与数学缺陷修复

1. **移除 CPU 多帧 `y_lum` 退饱和回归（[`ColorPipe.cpp:1193-1207`](file:///home/antigravity/Steve-Mr/Darkbag/app/src/main/cpp/ColorPipe.cpp#L1193-L1207)）**：
   * 删除 `if (!faithfulHighlights) { ... y_lum desat ... }` 代码块，同时清理不再使用的局部变量 `desat_threshold` 与 `theoretical_max`（确保零未使用变量编译警告）。
   * 保留 `sensorBlown = (std::max({r, g, b}) >= kSensorWhiteLevel)` 检测与 WB + clamp 后的单点中和逻辑。
2. **修复 Sony Uchimura 暗部 Toe 退饱和泄漏**：
   * 在 [`ColorPipe.cpp`](file:///home/antigravity/Steve-Mr/Darkbag/app/src/main/cpp/ColorPipe.cpp)、[`GpuColorPipeShader.h`](file:///home/antigravity/Steve-Mr/Darkbag/app/src/main/cpp/gpu/GpuColorPipeShader.h) 和 [`LutGlShaderPipeline.kt`](file:///home/antigravity/Steve-Mr/Darkbag/app/src/main/java/top/maary/darkbag/processor/LutGlShaderPipeline.kt) 三处的 `apply_sony_uchimura` / `applySonyUchimura` 中，将 `compression` 修改为仅在高于锚点 `0.22` 时触发：
     * C++: `float compression = (peak > 0.22f) ? std::max(0.0f, peak - mapped_peak) : 0.0f;`
     * GLSL: `float compression = (peak > 0.22) ? max(0.0, peak - mappedPeak) : 0.0;`
3. **补全单元测试（[`ColorEngineMathTest.kt`](file:///home/antigravity/Steve-Mr/Darkbag/app/src/test/java/top/maary/darkbag/processor/ColorEngineMathTest.kt) & 新增 `PreviewToneMapPolicyTest.kt`）**：
   * `ColorEngineMathTest`：
     * 更新 `sonyUchimuraRaw` 镜像公式加上 `peak > 0.22` 条件；
     * 新增测试验证 Toe 区高饱和色彩（如 `(0.10, 0.02, 0.01)`）在 `apply_sony_uchimura` 下零退饱和（R:G:B 比例严格不变）；
     * 在 `threeSiteConstantsMatch` 中增加 Mode 0 (`PBR Neutral`) 常数校验（`0.003`、`20.8333`、`0.8 - kBlackOffset`）以及 Uchimura `0.22` compression 阈值校验。
   * `PreviewToneMapPolicyTest`：
     * 验证四梯队选择逻辑（包括模拟 Moto `ZY22KMP2XK` 仅报 `[0, 1, 2]` 且 `maxCurvePoints=64` 时命中 `CONTRAST_CURVE_SRGB`）；
     * 验证 32 点 sRGB 曲线端点 `(0,0)` / `(1,1)`、严格单调递增、且对真实 sRGB OETF 的最大绝对误差 `< 0.003`（< 1/255）。

---

### 阶段四 4.1：Motion Photo 独立瑕疵修复

1. **[`MotionPhotoEncoder.kt:59-67`](file:///home/antigravity/Steve-Mr/Darkbag/app/src/main/java/top/maary/darkbag/motionphoto/MotionPhotoEncoder.kt#L59-L67)**：
   为 `MediaFormat` 显式补充（对齐 `Mp4VideoMuxerPipeline.kt:239-241`）：
   ```kotlin
   setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
   setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
   setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
   ```
2. **[`LutSurfaceProcessor.kt:55`](file:///home/antigravity/Steve-Mr/Darkbag/app/src/main/java/top/maary/darkbag/processor/LutSurfaceProcessor.kt#L55)**：
   将初始值 `private var currentColorEngineMode = 0` 改为 `= 2`（与 `DarkbagPreferences` / `RenderRecipe` 全局默认值一致）。

---

## 3. 硬性可验证完成条件（Hard Verification Gates）

Implementer 完成全部改动后，**必须全部通过以下硬性门禁**方可提交审查，禁止任何形式的跳过或「部分通过」：

1. **JVM 单元测试全绿**：
   ```bash
   ./gradlew :app:testDebugUnitTest --rerun-tasks
   ```
   退出码必须为 `0`，且 `ColorEngineMathTest` 与 `PreviewToneMapPolicyTest` 所有新增用例 100% 通过。
2. **Native C++ 编译零警告/零错误**：
   ```bash
   ./gradlew :app:buildCMakeRelease -Pandroid.injected.build.abi=arm64-v8a
   ```
   退出码必须为 `0`。
3. **Kotlin 编译检查**：
   ```bash
   ./gradlew :app:compileDebugKotlin
   ```
   退出码必须为 `0`。
4. **代码门禁审计**：
   * `ColorPipe.cpp` 中不得再存在 `desat_threshold` / `theoretical_max` 或 `!faithfulHighlights` 退饱和块；
   * 全仓所有携带 `previewSurface` / `surface` 的 `TEMPLATE_PREVIEW` 与 `TEMPLATE_RECORD` 请求均已覆盖 `applyPreviewToneMapPolicy`（`TEMPLATE_STILL_CAPTURE` 仍保持 `TONEMAP_MODE_FAST`）；
   * `ColorPipe.cpp`、`GpuColorPipeShader.h`、`LutGlShaderPipeline.kt` 三处数学常数通过 `threeSiteConstantsMatch` 校验。
5. **独立 Reviewer 签字**：由 `design_and_code_reviewer` subagent 审计并明确给出 `APPROVED` 结论。
