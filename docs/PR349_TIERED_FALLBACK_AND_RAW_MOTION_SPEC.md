# PR #349 取景器分级回退体系与实验性 RAW 传感器动态照片实施规范

> [!IMPORTANT]
> 本文档为 `feat/default-color-engine-v2` 分支（PR #349）的**强制实施与验收规范**。
> `spec_implementer` 与 `design_and_code_reviewer` 必须逐条对照本文档执行，不得擅自删减、偏离或跳过任何要求与门禁。

---

## 1. 目标与决策收敛

### 1.1 核心痛点与解决思路
1. **彻底下线 `CONTRAST_CURVE_SRGB`**：
   - 真机实测（Moto XT2409-5 `ZY22KMP2XK`，上报 `availableModes=[0, 1, 2]`，`maxCurvePoints=101`）证明：高通及 Moto HAL 在接收 `TONEMAP_MODE_CONTRAST_CURVE` 后，会在自定义曲线之外叠加第二道硬件 Gamma 2.2 / sRGB 转换，导致预览流双重 sRGB 编码，画面对比度严重崩塌（灰蒙发白）。
   - 彻底删除 `CONTRAST_CURVE_SRGB` 枚举与 `TonemapCurve` 注入。
2. **构建确定性三级回退策略（3-Tier Deterministic Preview Fallback）**：
   - **Tier 1 (`PRESET_CURVE_SRGB`, `shaderInputDomain = 0`)**：`TONEMAP_MODE_PRESET_CURVE` + `TONEMAP_PRESET_CURVE_SRGB`（硬件标准 sRGB 传输，Shader 采用 `srgbToLinear` 解码为场景线性）。
   - **Tier 2 (`GAMMA_VALUE_22`, `shaderInputDomain = 2`)**：`TONEMAP_MODE_GAMMA_VALUE` + `TONEMAP_GAMMA = 2.2f`（硬件纯 Gamma 2.2 传输，Shader 采用 `pow(c, 2.2)` 精确线性化）。
   - **Tier 3 (`FAST_INVERSE_ISP`, `shaderInputDomain = 1`)**：最坏情况兜底（覆盖 Moto `ZY22KMP2XK` 及仅支持 `[0, 1, 2]` 的设备）。硬件层下发 `TONEMAP_MODE_FAST`，Shader 采用 **`applyInverseIspCurve`（暗部逆趾部 + 高光逆肩部解压缩）** 还原准场景线性信号。
3. **用户明确确认的三个约束（User Invariants）**：
   - **Mode 1 (`Natural Filmic / AgX`) 0 EV 白点保持原样**：保留 `A = 2.3134378` 与 `B = 0.8030934`，严禁修改。
   - **实验性开关位置**：放置在设置界面的 **实验性硬件特性 (`card_tray_exp_hardware`)** 托盘内，ID 为 `switch_exp_raw_motion_photo`。
   - **实验性 RAW 动态照片复用 `rawvideo` 基础设施且零磁盘写入**：
     - 复用 `app/src/main/cpp/rawvideo/BayerProcessor`（NEON 保相抽样）与 `app/src/main/cpp/rawvideo/ColorMath.h`（DNG 自适应色彩矩阵）。
     - **严禁向磁盘写入任何 `.rawvid` 或临时 RAW 文件**。
     - 直接在 `LutSurfaceProcessor` 现有的 `encoderEglSurface` 上，以 `GL_R16UI` 将抽样 Bayer 帧通过 `LutGlShaderPipeline`（2x2 超像素解拜耳 → 扣黑电平 → WB → DNG CCM → 四大色彩引擎 / Log+3D LUT → TPDF 抖动）直编进入 `MotionPhotoEncoder` 的 MP4 H.264 环形缓冲区。
     - 若 1500ms 内未收到 RAW 帧或处于不支持 RAW 的模式，自动且无感回退至预览流（`inputSurfaceTexture`，Tier 1/2/3）。

---

## 2. 详细技术实现规范

### 组件一：三级回退策略与 Shader 解析逆 ISP 曲线

#### 1. `app/src/main/java/top/maary/darkbag/camera/session/PreviewToneMapPolicy.kt`
- 更新枚举 `PreviewToneMapStrategy`：
  ```kotlin
  enum class PreviewToneMapStrategy(val shaderInputDomain: Int) {
      PRESET_CURVE_SRGB(0),
      GAMMA_VALUE_22(2),
      FAST_INVERSE_ISP(1)
  }
  ```
- 移除 `CONTRAST_CURVE_SRGB`、`MIN_CONTRAST_CURVE_POINTS`、`TARGET_CURVE_POINTS`、`buildSrgbTonemapCurvePoints` 以及 `curveCache`。
- `resolvePreviewToneMapStrategy(availableModes: IntArray?, maxCurvePoints: Int)` 决策逻辑：
  - 若 `availableModes?.contains(CaptureRequest.TONEMAP_MODE_PRESET_CURVE) == true` -> `PRESET_CURVE_SRGB`
  - 否则若 `availableModes?.contains(CaptureRequest.TONEMAP_MODE_GAMMA_VALUE) == true` -> `GAMMA_VALUE_22`
  - 否则 -> `FAST_INVERSE_ISP`
- `applyStrategyToRequest`：
  - `PRESET_CURVE_SRGB` -> `TONEMAP_MODE_PRESET_CURVE` + `TONEMAP_PRESET_CURVE_SRGB`
  - `GAMMA_VALUE_22` -> `TONEMAP_MODE_GAMMA_VALUE` + `TONEMAP_GAMMA = 2.2f`
  - `FAST_INVERSE_ISP` -> `TONEMAP_MODE_FAST`
  - 捕获 `IllegalArgumentException`，向下一级降级（`PRESET_CURVE_SRGB` -> `GAMMA_VALUE_22` -> `FAST_INVERSE_ISP`）。
  - 返回实际成功应用的 `PreviewToneMapStrategy`。

#### 2. `app/src/main/java/top/maary/darkbag/camera/session/CameraHardwareStreamController.kt`
- 在 `applyPreviewToneMapPolicy(request)` 中：
  - 应用策略后获取 `appliedStrategy`，通过 `env.sessionArbiter.lutProcessor?.setPreviewInputDomain(appliedStrategy.shaderInputDomain)` 同步通知 GL 渲染管线。

#### 3. `app/src/main/java/top/maary/darkbag/processor/LutSurfaceProcessor.kt`
- 新增 `@Volatile private var previewInputDomain = 0`。
- 新增公共方法 `fun setPreviewInputDomain(domain: Int) { handler.post { previewInputDomain = domain } }`。
- 在 `drawFrame()` 渲染时，向 `shaderPipeline.drawFrame` 传入 `previewInputDomain = previewInputDomain`。

#### 4. `app/src/main/java/top/maary/darkbag/processor/LutGlShaderPipeline.kt`
- 新增 uniform location `previewInputDomainLoc = GLES30.glGetUniformLocation(program, "uPreviewInputDomain")`。
- 在 `drawFrame` 中更新 uniform `uPreviewInputDomain`（0, 1, 2）。
- 在 fragment shader 中加入 `applyInverseIspCurve` 函数：
  ```glsl
  vec3 applyInverseIspCurve(vec3 c) {
      const float m = 0.22;
      const float invToePower = 0.7518797; // 1.0 / 1.33
      float luma = dot(c, vec3(0.2126, 0.7152, 0.0722));
      if (luma <= 1e-5) return vec3(0.0);
      float targetLuma = luma;
      if (luma < m) {
          float gate = smoothstep(0.0002, 0.0020, luma);
          float lifted = m * pow(luma / m, invToePower);
          targetLuma = mix(luma, lifted, gate);
      } else if (luma > 0.50) {
          float excess = min(luma, 1.0) - 0.50;
          targetLuma = luma + 0.30 * (excess * excess) / (1.08 - min(luma, 1.0));
      }
      return c * (targetLuma / luma);
  }
  ```
- 在 fragment shader `main()` 中解码预览输入：
  ```glsl
  vec4 src = texture(uTexture, vTexCoord);
  vec3 srcLinear;
  if (uPreviewInputDomain == 2) {
      srcLinear = pow(max(src.rgb, vec3(0.0)), vec3(2.2));
  } else if (uPreviewInputDomain == 1) {
      srcLinear = applyInverseIspCurve(srgbToLinear(src.rgb));
  } else {
      srcLinear = srgbToLinear(src.rgb);
  }
  srcLinear *= uPreviewGain;
  vec3 linearRgb = max(vec3(0.0), uWbPreviewMatrix * srcLinear);
  ```

---

### 组件二：实验性低分辨率/抽样 `RAW_SENSOR` 动态照片管线

#### 1. C++ / JNI 抽样与自适应色彩矩阵
- **`app/src/main/cpp/rawvideo/BayerProcessor.h` & `BayerProcessor.cpp`**：
  - 新增 `BayerProcessor::bayer4x4SubsampleNEON`：
    ```cpp
    static void bayer4x4SubsampleNEON(
        const uint8_t* srcBytes,
        uint32_t srcWidth,
        uint32_t srcHeight,
        uint32_t srcRowStrideBytes,
        uint16_t* dst
    );
    ```
  - 输出尺寸为 `dstWidth = ((srcWidth / 4) & ~1)`，`dstHeight = ((srcHeight / 4) & ~1)`。
  - 保持原 Bayer CFA 相对相位（例如对 RGGB，在每个 4x4 输入块中提取 R, Gr, Gb, B，或对同相位像素进行 $2\times 2$ 快速平均/抽样）。
- **`app/src/main/cpp/rawvideo/RawVideoJNI.cpp` & `top.maary.darkbag.rawvideo.RawVideoNative`**：
  - 新增 JNI 方法：
    `external fun nativeSubsampleBayer4x4(srcBuffer: ByteBuffer, srcWidth: Int, srcHeight: Int, srcRowStride: Int, dstBuffer: ByteBuffer): Int`
    （返回 `(dstWidth shl 16) or dstHeight`）。
  - 新增 JNI 方法：
    `external fun nativeComputeAdaptiveColorMatrix(neutralPoint: FloatArray, fm1: FloatArray?, fm2: FloatArray?, illum1: Int, illum2: Int, outColMajor: FloatArray): Boolean`
    内部调用 `darkbag::rawvideo::computeAdaptiveColorMatrix(neutralPoint, fm1, fm2, illum1, illum2, nullptr, outColMajor)`。

#### 2. `RawFrameRouter.kt` & `CameraHardwareStreamController.kt` 挂载
- **`app/src/main/java/top/maary/darkbag/camera/zsl/RawFrameRouter.kt`**：
  - 增加 `@Volatile var rawMotionTap: ((Image, TotalCaptureResult?) -> Unit)? = null`。
  - 在 `onImageAvailable` 中，获取 `image` 后，若 `image.format == ImageFormat.RAW_SENSOR` 且 `rawMotionTap != null`，先执行 `rawMotionTap?.invoke(image, resultLookup(image.timestamp))`。严禁在 tap 中关闭 `image`。
- **`app/src/main/java/top/maary/darkbag/camera/session/CameraHardwareStreamController.kt`**：
  - 增加 `@Volatile private var rawMotionPhotoTargetEnabled = false` 与 `fun setRawMotionPhotoTargetEnabled(enabled: Boolean)`。
  - 在 `updateCamera2RepeatingRequest` 与 `onConfigured` 中，当 `(zslRawTargetEnabled || rawMotionPhotoTargetEnabled) && env.runtimeState.isRawSupported` 时，将 `rawImageReader?.surface` 挂载至 repeating capture request。

#### 3. `RawMotionPhotoRelay.kt`
- 新建 `app/src/main/java/top/maary/darkbag/motionphoto/RawMotionPhotoRelay.kt`：
  - 分配固定的 2 个直接内存缓冲区（`ByteBuffer.allocateDirect(1024 * 768 * 2)`，共 ~3MB），双缓冲避免 GC 与重分配。
  - 帧率节流：最小间隔 `33_000_000L` 纳秒（最高 30fps）。
  - 接收 RAW 帧并读取：
    - Black level（4 元素 FloatArray：R, Gr, Gb, B，从 `SENSOR_DYNAMIC_BLACK_LEVEL` 或 `SENSOR_BLACK_LEVEL_PATTERN` 读取）。
    - White level（Float，从 `SENSOR_INFO_WHITE_LEVEL` 读取）。
    - CFA pattern（Int，从 `SENSOR_INFO_COLOR_FILTER_ARRANGEMENT` 读取）。
    - WB gains（3 元素 FloatArray，从 `COLOR_CORRECTION_GAINS` 读取）。
    - DNG CCM（9 元素 FloatArray，通过 `RawVideoNative.nativeComputeAdaptiveColorMatrix` 计算所得的列主序 3x3 矩阵）。
  - 调用 `RawVideoNative.nativeSubsampleBayer4x4` 抽样后，直接调用 `lutSurfaceProcessor.submitRawMotionFrame(...)`。

#### 4. `LutSurfaceProcessor.kt` & `LutGlShaderPipeline.kt` GPU 直编与平滑回退
- **`LutGlShaderPipeline.kt`**：
  - 支持 `uniform int uInputSource;`（`0 = OES_PREVIEW`, `1 = RAW_BAYER_R16UI`）。
  - 支持 RAW uniforms：`uRawBayerTexture` (`usampler2D`), `uRawImageSize` (`ivec2`), `uRawCfaPattern` (`int`), `uRawBlackLevel` (`vec4`), `uRawWhiteLevel` (`float`), `uRawWbGains` (`vec3`), `uRawCcm` (`mat3`)。
  - 当 `uInputSource == 1` 时：
    - 以 `texelFetch` 读取 2x2 超像素四通道 RAW 值；
    - 扣减 `uRawBlackLevel` 并归一化；
    - 乘以 `uRawWbGains * uPreviewGain`；
    - 乘以 `uRawCcm`；
    - 执行 `maxRaw > 0.95` 高光防偏色（Highlight Desaturation）；
    - 得到的 `linearRgb` 直接送入与 `uInputSource == 0` 完全相同的 ColorEngine（Mode 0/1/2/3）或 Log+3D LUT，并施加 TPDF 抖动。
  - 新增 `drawRawFrame(...)` 方法用于执行 `uInputSource = 1` 的绘制。
- **`LutSurfaceProcessor.kt`**：
  - 创建并管理 `rawBayerTextureId` (`GL_TEXTURE_2D`, `GL_R16UI`)。
  - 维护 `@Volatile private var rawMotionEnabled = false` 与 `@Volatile private var lastRawFrameNs = 0L`。
  - 新增 `submitRawMotionFrame(...)`：
    - 在 GLThread 上，若 `isEncoderActive && encoderEglSurface != EGL_NO_SURFACE`：
      - 上传数据至 `rawBayerTextureId`；
      - 切换 EGL 上下文至 `encoderEglSurface`；
      - 调用 `shaderPipeline.drawRawFrame(...)`；
      - 设置 `EGLExt.eglPresentationTimeANDROID(eglDisplay, encoderEglSurface, timestampNs)`；
      - 执行 `EGL14.eglSwapBuffers(eglDisplay, encoderEglSurface)`；
      - 切回取景器 `eglSurface` 并记录 `lastRawFrameNs = timestampNs`。
  - 在 `drawFrame()` 中：
    - 若 `rawMotionEnabled == true` 且 `(System.nanoTime() - lastRawFrameNs) < 1_500_000_000L`：
      - **跳过 Branch 2 预览流编码**（避免预览流与 RAW 流冲突或双重编码）。
    - 否则（RAW 停发或未开启）：
      - 保持现有的 Branch 2 预览流编码逻辑（Tier 1/2/3）。

---

### 组件三：设置持久化与 Bento UI（遵循 Invariant 5）

1. **`PreferenceKeys.kt`、`DarkbagPreferences.kt`、`SettingsRepository.kt`、`SettingsViewModel.kt`**：
   - 增加 `KEY_EXP_RAW_MOTION_PHOTO = "exp_raw_motion_photo"`，默认 `false`。
2. **`app/src/main/res/layout/fragment_settings.xml`**：
   - 在 `card_tray_exp_hardware` 托盘中增加 `switch_exp_raw_motion_photo`（BentoSwitchTile，badge 为 `"RAW"`，`app:bentoSwitchBadgeStyle="tertiary"`）。
3. **国际化字符串**：
   - `values/strings.xml`：
     `<string name="pref_exp_raw_motion_photo">RAW Sensor Motion Photo</string>`
     `<string name="pref_exp_raw_motion_photo_summary">Process Motion Photo directly from RAW sensor stream instead of ISP preview</string>`
   - `values-zh-rCN/strings.xml`：
     `<string name="pref_exp_raw_motion_photo">RAW 传感器动态照片</string>`
     `<string name="pref_exp_raw_motion_photo_summary">动态照片直接从 RAW 传感器流处理，跳过 ISP 预览</string>`
4. **`SettingsBentoDelegate.kt`**：
   - 绑定 `switch_exp_raw_motion_photo` 与 `viewModel.expRawMotionPhoto`。
5. **`CameraMotionPhotoController.kt`**：
   - 观察 `snapshot.expRawMotionPhoto` 与 `snapshot.motionPhotoEnabled`，同步启停 `RawMotionPhotoRelay`、`lutSurfaceProcessor.setRawMotionEnabled(...)` 与 `streamController.setRawMotionPhotoTargetEnabled(...)`。

---

## 3. 验收与质量门禁

1. **单元测试 100% 通过**：
   ```bash
   ANDROID_HOME=/home/antigravity/android-sdk ./gradlew :app:testDebugUnitTest --rerun-tasks --no-daemon
   ```
   - `PreviewToneMapPolicyTest`：必须验证 `[0, 1, 2]` 命中 `FAST_INVERSE_ISP`（`shaderInputDomain = 1`），且不存在 `CONTRAST_CURVE_SRGB`。
   - `ColorEngineMathTest`：验证 `applyInverseIspCurve` 单调性、暗部提升（`0.003` -> `~0.0087`）、中调 `[0.22, 0.50]` 恒等、高光 `1.0` -> `~1.85` 解压；验证 Mode 0/1/2/3 在最坏回退下梯度鲜明；验证 Mode 1 0 EV 白点保持原样。
2. **Kotlin 与 C++ 编译零警告零错误**：
   ```bash
   ANDROID_HOME=/home/antigravity/android-sdk ./gradlew :app:compileDebugKotlin --no-daemon
   ANDROID_HOME=/home/antigravity/android-sdk ./gradlew :app:buildCMakeRelease -Pandroid.injected.build.abi=arm64-v8a --no-daemon
   ```
3. **代码与架构审计通过**：
   - Reviewer 子代理审计确认无 Invariant 1~5 违背。
