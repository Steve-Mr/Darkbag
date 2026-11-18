# Darkbag 实验性 ZSL 实施计划（HDR+ ZSL + RAW 环形缓冲）

- **状态**：PROPOSED；本文件只描述方案，尚未修改任何拍摄代码。
- **基线**：`refactor/datastore-and-arch-hardening` @ `cdb8d52e`
- **分支**：`feat-experimental-zsl`（worktree：`.agents/worktrees/feat-experimental-zsl`）
- **目标读者**：后续实现 agent / reviewer。
- **一句话**：先做 A（应用层 RAW 环形缓冲），同时覆盖 **单帧 ZSL** 与 **HDR+ ZSL（GCam “HDR+ On” 等价物）**；B 只保留可选的 B1（RAW reprocess），删除 B2（相机 JPEG）；环深度与 HDR+ burst 数联动，硬上限 128 MiB。

> **实现前先读**：`AGENTS.md` 的五条架构红线、`docs/CAPTURE_SPEED_OPTIMIZATION_PLAN.md` §3/§8、`docs/HDRPLUS_PIPELINE_ANALYSIS.md` 的 S4/H14。本文所有行号以基线 commit 为准，实施时重新核对。

---

## 实现状态（2026-11-18）

| 阶段 | 状态 | 说明 |
|---|---|---|
| Stage 0：检测 + 设置 | 已实现 | `ZslModels.kt`、`ZslCapabilityDetector.kt`、DataStore 键、实验性 Bento 开关/双行状态、按下时刻记录、CaptureResult 有界缓存/精确查找、preview ZSL profile |
| Stage 1：A 单帧 ZSL | 已实现 | `RawFrameRouter`、`ZslRawRing`、`ZslCaptureController`，单帧从环取出后进入现有 `processImageAsync` |
| Stage 2：HDR+ ZSL | 已实现（代码） | 预卷稳定段选择、`ZslBurstSeed`、`HdrPlusStreamingBurst.enqueuePreloadedFrame`、补拍、viewfinder-only 增益、失败回前向 HDR+ |
| 运行时 arm 探针 | 最小版已实现 | arm 后 2s 检查环帧数与 sensor fps；<15fps 或无足够帧则 disarm。完整 preview-drop/copy P95 探针仍需真机标定 |
| RAW capability 误判 | 已修复 | 旧判定把 `ImageFormat.RAW_SENSOR`（32）当作 capability id；真实 `REQUEST_AVAILABLE_CAPABILITIES_RAW` 是 3，导致所有设备被误判为无 RAW。现以实际 RAW 输出尺寸为准，capability 仅用于诊断；ring 同时要求 reader/frame 为 `RAW_SENSOR`，否则回退 |
| 诊断信息 | 已实现 | 拍摄报告输出 ZSL route/pre/post/offset/fallback；实验性设置页显示真实 armed/profile/disarm reason、RAW 尺寸、ring 帧数/fps 和最近一次路径；控制器在 arm/disarm 时打结构化日志 |
| B1 | 仅检测 | 没有实验室设备证明 `getValidOutputFormatsForInput(PRIVATE)` 含 RAW，因此不写 session 路径 |
| 真机验证 | 未完成 | 当前连接的 `redroid11_x86_64` 无相机；画质/帧率/热/内存目标仍需设备矩阵 |

**当前质量门禁**：`testDebugUnitTest` 全绿；`:app:buildCMakeRelease[arm64-v8a]` 可链接（libraw 子模块在本 worktree 为本地复制，构建输出仍有 libraw 既有的 `NO_JPEG` 宏重定义 warning，不是本次改动引入）。没有设备，因此不声称已达到文档中的画质/延迟数值目标。

**Reviewer 记录**：首轮独立 review 为 `REVISE_REQUIRED`，已修复 P0（RAW router 用 `onCaptureStarted` 时间戳拒绝旧预览帧）、P1（burst 中禁止 disarm、seed 失败不重复触发前向 burst、router/helper 收尾、seed 释放）与若干 P2（generation token、AE clamp、pool 恢复、单帧 watchdog）。复审（source-only）为 `APPROVED`。仍未覆盖：设置状态与实际 arm 状态的一致性、真机画质/热/延迟。

---

## 0. 决策摘要（已锁定）

1. **保留 HDR+ ZSL，且列入本期**。它不是“HDR+ Enhanced”的替代，而是 GCam 的 “HDR+ On”：用按下快门前的环形帧快速合成；画质低于/接近前向 HDR+，低光或不稳定时自动回前向 HDR+。
2. **删除 B2（reprocess → 相机 JPEG）**。不引入绕过 Darkbag ColorPipe/LUT 的相机 JPEG 路径。
3. **B1（reprocess → RAW）只做可选路径**：仅当实验室设备真的在 `getValidOutputFormatsForInput(PRIVATE)` 中列出 `RAW_SENSOR` 时才实现；没有设备支持就只保留能力检测，不写 session 代码，避免无效开发。
4. **环深度与 `hdrBurstCount` 联动**：
   - HDR+ ZSL：`depth = min(hdrBurstCount, floor(128 MiB / frameBytes), 8)`；12 MP 时上限 5 帧（约 120 MiB）。
   - 单帧 ZSL：固定 3 帧（100 ms @30 fps）。
   - 不新增“ZSL 张数”设置；设置页只显示有效预卷数，如 `5/8 帧`。
5. **不重建 Session**：A 路线的 arm/disarm 只切换 repeating request 的 target；B1 的 reprocessable session 只在相机启动时配置一次。
6. **第一版不改 native 算法**：复用现有 `HdrPlusStreamingBurst` / `HdrPlusJNI`，只增加 Kotlin 侧“预卷帧注入”与预览 GLSL 的预览增益 uniform。
7. **PR 基线**：stacked 到当前 `refactor/datastore-and-arch-hardening`；该分支合入后再 retarget `main`。

---

## 1. 业界怎么实现 HDR+ ZSL

### 1.1 Google / Pixel

- HDR+ 原始论文的采集策略是 **恒定曝光、刻意欠曝的一串帧**（典型 2–15 帧），再对齐/合并；不是曝光包围。欠曝换取高光，合并换回暗部信噪比。
  - Hasinoff et al., *Burst photography for high dynamic range and low-light imaging on mobile cameras*, SIGGRAPH Asia 2016。
- Pixel 默认拍照是 ZSL：相机持续把帧放入环形缓冲，按下快门时把最近 9 或 15 帧送 HDR+/Super Res Zoom；为保证取景器至少 15 fps，单帧曝光被限制在约 66 ms。
  - 这是公开技术分析/报道的口径，不是 Android API 承诺；用于设计参考，不作为设备准入。
- Pixel 2/3 的 CameraX quirk 明确显示：**HDR+ 与 ZSL 是同一个开关**。`CAPTURE_MODE_MAXIMIZE_QUALITY` 打开 ZSL/HDR+；`CAPTURE_MODE_MINIMIZE_LATENCY` 关闭 ZSL，同时关闭 HDR+。
  - 来源：[ImageCapturePixelHDRPlusQuirk.java](https://android.googlesource.com/platform/frameworks/support/+/187e9a2088c3f281cfb617b9bedd94b9a3546d8b/camera/camera-camera2/src/main/java/androidx/camera/camera2/internal/compat/quirk/ImageCapturePixelHDRPlusQuirk.java)
- Pixel 5 之后的 “HDR+ with Bracketing” 增加了长曝光帧来改善暗部，已经不是纯 ZSL；Night Sight 走的是按下快门后的长曝光采集。
  - 来源：[HDR+ with Bracketing on Pixel Phones](https://research.google/blog/hdr-with-bracketing-on-pixel-phones/)

### 1.2 GCam 社区实现

- GCam 通常提供两档：
  - **HDR+ On / HDR+ Auto（ZSL）**：后台持续缓冲，按下即取旧帧；快，支持 ZSL/动态照片，但画质不总是最好。
  - **HDR+ Enhanced**：按下后重新曝光一组帧，慢，但低光/高动态更好。
- “Instantaneous HDR / fast HDR” 的已知代价：动态范围更差、发热/耗电、成片可能不是按下瞬间（旧帧延迟）。
  - 来源：[GCam Handbook](https://www.celsoazevedo.com/files/android/p/f/2021/05/GCam-Handbook.pdf)

### 1.3 官方应用层 ZSL 参考

- Google `zsldemo`：Camera2 应用层 ZSL 的官方 proof-of-concept。
  - 10 帧 PRIVATE 格式环形缓冲；
  - 选“AF/AE 已收敛、无需闪光、优先第 3 帧”的帧；
  - 通过 reprocess 出 JPEG；Pixel 上按快门到成片约 50–100 ms；
  - 入口：[google/zsldemo](https://github.com/google/zsldemo)
- 这证明 B 类路线（PRIVATE reprocess）在应用层可行，但产物是 JPEG；它不能直接产出 RAW/DNG。B1 是厂商可选的 RAW reprocess 扩展，不能当作通用能力。

### 1.4 对 Darkbag 的结论

1. **HDR+ ZSL 必须做，但不能照搬 Pixel 的 9/15 帧**。Pixel 的缓冲在 HAL/硬件层，应用层 RAW16 5 帧就已经约 120 MiB；9 帧 12 MP 是 216 MiB，15 帧是 360 MiB，超出本项目的内存红线。
2. **本期 HDR+ ZSL = GCam “HDR+ On” 等价物**：5 帧预卷（12 MP）、恒定欠曝、按下即取；不是“HDR+ Enhanced”的画质上限。
3. **保留前向 HDR+ 作为 Enhanced/Quality 路径**。低光、AE/WB 不稳定、曝光需要超过 66 ms、或画质门禁不通过时，自动回前向 HDR+。
4. **B2 删除**。应用层 ZSL 若只能出相机 JPEG，会绕过 LUT/log/ColorPipe，和 Darkbag 的成像契约冲突，收益不值得复杂度。

---

## 2. 当前代码事实（基线）

| 事实 | 位置 | 影响 |
|---|---|---|
| 会话已配置预览、最大分辨率 RAW Reader（`maxImages=8`）、YUV 分析流；预览 repeating request 没有 RAW target | `CameraHardwareStreamController.kt:307-317`, `:366-369`, `:382-396` | A 可复用 RAW surface；arm/disarm 只切 target，不重建 Session |
| 普通会话创建没有 input configuration | `Camera2SessionController.kt:137-184` | B1 需要新增 reprocessable session 创建方法 |
| 预览请求有两处重复构造 | `CameraHardwareStreamController.kt:157-181`, `:382-396` | 加 RAW target 前先收敛为单一 preview request factory |
| 每次拍摄 executor 直接设置 RAW listener | `SingleRawCaptureExecutor.kt:69-121`；`HdrPlusBurstCaptureExecutor.kt:212-263` | 需要单监听器 `RawFrameRouter`，否则环和拍摄互相偷帧 |
| 每个预览帧的 `TotalCaptureResult` 写入无上限 Map，匹配允许 5 ms 容差 | `CameraRuntimeStateHolder.kt:67-73`；`CameraHardwareStreamController.kt:183-194`；`CameraCaptureMetadataExtractor.kt:59-64` | ZSL 必须精确匹配 timestamp；顺手给结果缓存加有界淘汰 |
| HDR+ 连拍固定 AE OFF/ISO/快门，再 `captureBurst` | `HdrPlusBurstCaptureExecutor.kt:341-354`, `:265-273` | HDR+ ZSL 可以复用同一 native 管线，但帧必须来自同一恒定曝光段 |
| native streaming session 创建一次，`pushFrame` 只传 buffer，无逐帧曝光参数 | `HdrPlusJNI.cpp:1282-1369`；`HdrPlusStreamingBurst.kt:75-85` | 第一版不做逐帧曝光归一化；用“恒定曝光段”门禁保证一致性 |
| 快门时间只有 `System.currentTimeMillis()` | `CameraShutterController.kt:70-73`；`StandardTimingTracker.kt:14-16` | 需要增加 `elapsedRealtimeNanos()` 的按下时刻 |
| 内存准入是 `availMem ≥ 600 MB`；HDR+ 池最多 3×~123 MB | `HdrPlusRequestManager.kt:271-285`；`HdrPlusBurst.kt:40-83` | 环必须纳入预算，不能让拍摄被更频繁拒绝 |
| 实验性托盘默认隐藏，版本号 5 击解锁 | `fragment_settings.xml:1583-1744`；`SettingsFragment.kt:202-211`；`SettingsBentoDelegate.kt:341-343` | ZSL 开关放这里，复用 BentoSwitchTile + 双行 telemetry |
| `hdrBurstCount` 可选 3–8，默认 5 | `PreferenceKeys.kt:288`；`DarkbagPreferences.kt:20` | 环深度与其联动；12 MP 时上限 5，超出部分按下后补拍 |

**当前工作区注意**：主工作区有 3 个未跟踪的旧 ZSL 探针文件（`camera/zsl/ZslAdmission.kt`、`zsl/probe/ZslProbeScoring.kt` 及测试），引用了不存在的 `RingConfig`/`ZslProbeSample`/`ZslStaticCapabilities`，当前测试源集无法编译。本 worktree 从干净基线切出，没有这些文件；**不要把它们带进来**，也不要复用其判定模型。

---

## 3. 设计总览

### 3.1 ZSL Profile

| Profile | 触发条件 | 环深度 | 曝光策略 | 产物 |
|---|---|---|---|---|
| `SINGLE_RAW` | ZSL 开，HDR+ 关，A 可用 | 3（内存不足时降到 1–2；低于 1 则 OFF） | 正常 AE，不做欠曝/预览增益 | RAW → 现有单帧管线 → DNG/JPEG |
| `HDR_PLUS` | ZSL 开，HDR+ 开，A 可用 | `min(hdrBurstCount, maxByBudget, 8)`；12 MP 时 5 | 恒定欠曝 + 预览增益；曝光上限 66 ms | RAW 预卷 + 必要时补拍 → 现有 HDR+ 管线 |
| `REPROCESS_RAW`（B1，可选） | ZSL 开，HDR+ 关，B1 探测可用 | 5–10 帧 PRIVATE（不占 RAW16 内存） | 相机 ZSL 自动曝光 | reprocess 到 RAW → 现有单帧管线 |
| `OFF` | 其余情况 | — | — | 现有前向拍摄 |

> B2 不存在。

### 3.2 路线选择

```
if (!zslEnabled || mode != NORMAL || multiCam || video || manualExposure || flash) -> OFF
if (hdrPlusEnabled) {
    if (A.available && hdrPlusProfileViable) -> HDR_PLUS
    else -> OFF                      // 回前向 HDR+
}
if (B1.available && singleFrame) -> REPROCESS_RAW
if (A.available) -> SINGLE_RAW
else -> OFF
```

- HDR+ 开启时 **永远不用 B1**：B1 只做单帧，不能替代 HDR+；A 的 HDR+ ZSL 是唯一 HDR+ ZSL 路径。
- 用户切换 HDR+ 时：清环、切换 profile、切 repeating request；不重建 Session。
- 半格、多摄、视频、RAW 视频、Motion Photo、手动曝光、闪光灯：`OFF`。Motion Photo 可后续单独评估。

### 3.3 Session / Stream 策略

- A：复用当前会话和 `rawImageReader`；只通过 `setRepeatingRequest` 增删 RAW target。
- B1：仅在相机启动时、ZSL 开且 HDR+ 关且 B1 可用时，创建一次 reprocessable session（`InputConfiguration` + PRIVATE ZSL reader + JPEG/RAW reader）。
- 不为了 ZSL 重建 Session；不新增第二个 RAW Reader；不把 48 MP full-res RAW 塞进环。

---

## 4. HDR+ ZSL（核心）

### 4.1 为什么这条路能复用现有 HDR+，而不需要改 native

现有 HDR+ 的 native 管线本质是“同一曝光的一串 RAW → 对齐/合并”。它没有逐帧曝光归一化，因此只要保证送入的预卷帧：

- 来自同一段 **恒定曝光**（ISO/快门不发生 AE 跳变）；
- WB/CCM/LSC 基本一致；
- 曝光时间被限制在 ≤66 ms；

就可以直接复用 `HdrPlusStreamingBurst` 和 `HdrPlusJNI` 的现有合并路径。HDR+ ZSL 的新增工作只在“怎么持续拿到这段帧、怎么在按下时选中它、怎么把剩余帧补齐”。

### 4.2 Arm 前置条件

全部满足才 arm `HDR_PLUS`：

- ZSL 开、HDR+ 开、Normal 模式、单摄、非视频/Motion Photo、非手动曝光、闪光灯关闭、`force60fps` 关闭。
- A 静态可用：`RAW` 能力、`RAW_SENSOR` 输出、`READ_SENSOR_SETTINGS`、建议 `MANUAL_POST_PROCESSING`、`SENSOR_INFO_TIMESTAMP_SOURCE` 可用。
- 选中的 RAW 尺寸 `frameBytes ≤ 32 MiB`（约 ≤16 MP）；若最大 RAW 是 48 MP/96 MiB，A 全路线不可用（单帧也不用 48 MP RAW 做环），不做第二个 RAW Reader。B1 可用则单帧走 B1；否则 OFF。
- 可用内存：`availMem - ringBytes ≥ 600 MiB`。
- 热状态 ≤ `LIGHT`；电池/省电模式允许。
- `CONTROL_AE_COMPENSATION_RANGE` 支持所需负 EV；`CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES` 含 `[15,30]`（或 lower≤15、upper≥24 的等价范围）。
- 设备支持预览 GL 增益（本项目的 `LutSurfaceProcessor` 路径），否则 HDR+ ZSL 关闭，避免预览变暗。

### 4.3 曝光策略：恒定欠曝 + 预览增益

- 欠曝量 `hdrEv`：
  - `hdrUnderexposureMode == "Off"` → `0 EV`（允许，但状态标注“无欠曝”，高光保护弱）；
  - `-1 EV` → `-1 EV`；
  - `-2 EV` → `-2 EV`；
  - `Dynamic (Experimental)` → 本期固定 `-1 EV`。原因：动态模式依赖 `lastClippingRatio`，而欠曝后的分析流会让这个反馈失真；ZSL 先用固定值，后续再评估。
- 预览 repeating request：
  - `CONTROL_AE_MODE = ON`
  - `CONTROL_AE_EXPOSURE_COMPENSATION = clamp(userEvSteps + hdrEvSteps)`
  - `CONTROL_AE_TARGET_FPS_RANGE = [15,30]`（从可用范围里选最接近的；上限 30）
  - target = 预览 + `rawImageReader.surface`
  - AWB/AF 保持自动；OIS 按当前 HDR+ 策略
- 预览增益：
  - `previewGain = 2^(-hdrEv)`，例如 -1 EV → 2.0，-2 EV → 4.0；
  - 只作用于 **viewfinder pass**，encoder pass 强制 1.0（Motion Photo/视频不受影响）；
  - 在 `LutGlShaderPipeline` 的 fragment shader 中，把增益乘到线性 RGB（LUT/tonemap 之前）；
  - arm 时设置，disarm 时恢复 1.0；目标切换亮度误差 ≤0.1 EV。
- 如果 `previewGain > 4.0`、shader 不可用、或 `CONTROL_AE_COMPENSATION_RANGE` 不够：HDR+ ZSL 关闭，回前向 HDR+。
- 曝光上限 66 ms 来自 `[15,30]` 的 15 fps 下界；如果设备只支持 `[30,30]`，低光下无法维持，HDR+ ZSL 关闭。

### 4.4 环深度与内存

```
frameBytes = rawWidth * rawHeight * 2
maxByBudget = floor(128 MiB / frameBytes)
maxPreRoll = min(8, maxByBudget)
hdrDepth = min(hdrBurstCount, maxPreRoll)
singleDepth = min(3, maxByBudget)
```

| RAW 尺寸 | frameBytes | maxByBudget | 12 MP HDR+ 默认 5 帧 |
|---|---|---|---|
| 4096×3072（12.6 MP） | 24 MiB | 5 | 5 帧 = 120 MiB；6 帧 = 144 MiB，超 128 MiB |
| 4608×3456（16 MP） | 约 30 MiB | 4 | 4 帧 ≈120 MiB |
| 3264×2448（8 MP） | 约 15 MiB | 8 | 8 帧 ≈120 MiB |
| 8000×6000（48 MP） | 约 92 MiB | 1 | A 全路线不可用（单帧也不用 48 MP RAW 做环） |

- 环硬上限 128 MiB；ring 只存拷贝，不持有 `Image`。
- `availMem - ringBytes ≥ 600 MiB` 才能 arm；不足时降 depth（5→4→3），低于 3 则关闭 HDR+ ZSL。
- 与 `StreamingBufferPool` 共享预算：ZSL 活跃时把池缓存上限压到 2 帧或清空，避免“环 5 帧 + 池 12 帧”双份常驻。
- 12 MP、5 帧、默认 `hdrBurstCount=5`：按下时 5 帧全部来自预卷，无需补拍。
- `hdrBurstCount=8`、12 MP：预卷 5，按下后补 3 帧；快门到最后一帧约 100 ms @30 fps，仍显著优于全前向。
- `hdrBurstCount=3`：预卷 3，100 ms @30 fps。
- 非 HDR+ 单帧：固定 3 帧，不跟随 burst count（单帧不需要更多预卷，跟随只会无谓增加内存）。

### 4.5 按下快门：选段与元数据

1. UI 线程点击时记录 `shutterPressElapsedNs = SystemClock.elapsedRealtimeNanos()`；保留 `shutterClick` 用于文件名/EXIF。
2. 在 `zslRawHandler` 上从环快照中找“最长连续稳定段”：
   - 帧按时间升序；
   - `iso`、`SENSOR_EXPOSURE_TIME` 完全相同（或曝光差 ≤5%、ISO 差 ≤5%）；
   - `COLOR_CORRECTION_GAINS` 各通道差 ≤5%；
   - 相同 crop/lens/zoom/尺寸；
   - AF 已收敛（`CONTROL_AF_STATE` 为 FOCUSED/PASSIVE_FOCUSED，或 `LENS_STATE_STATIONARY`）；AF 正在搜索的帧不得作为基准；
   - 末端帧距 `T0` 不超过 1.5 个帧间隔；整段不超过 `hdrDepth`。
3. 选中段的有效帧数 `n`：
   - `n >= hdrBurstCount`：全预卷，截取最接近 `T0` 的 `hdrBurstCount` 帧；**不发起任何 capture**。
   - `3 <= n < hdrBurstCount`：用 `n` 帧预卷 + 补拍 `hdrBurstCount-n` 帧。
   - `n < 3`：不满足 HDR+ 最低帧数，立即回前向 HDR+，并记录 fallback 原因。
4. 基准帧：
   - 取选中段中距 `T0` 最近的帧为基准；
   - native session 的 WB/CCM/LSC/noise 元数据全部来自基准帧的 `TotalCaptureResult`；
   - 预卷帧按“基准帧第一、其余按距 `T0` 由近到远”入队；补拍帧按时间追加。
5. 元数据必须精确绑定：`image.timestamp == result.SENSOR_TIMESTAMP`；禁止 5 ms 容差；缺 ISO/曝光/WB 的帧直接丢弃。
6. 补拍帧请求：
   - `TEMPLATE_PREVIEW`，target = RAW + 预览；
   - `CONTROL_AE_MODE = OFF`；
   - `SENSOR_SENSITIVITY` / `SENSOR_EXPOSURE_TIME` = 基准帧值；
   - `CONTROL_AWB_LOCK = true`；
   - OIS 使用 HDR+ burst 策略；
   - `captureBurst`，帧数 = 剩余帧数。
7. `digitalGain`：`1 / 2^hdrEv`（-1 EV → 2.0，-2 EV → 4.0，0 EV → 1.0）。最终亮度回到用户 EV；EXIF/`CaptureMetadata` 使用基准帧的实际 ISO/曝光。

### 4.6 复用 HdrPlusStreamingBurst 的方式

不新增 native 算法，只给 Kotlin 侧加“预卷注入”：

- `HdrPlusStreamingBurst` 增加 `seedFrames: List<PreloadedFrame>`（或 `enqueuePreloadedFrame`）：
  - 每个预卷帧直接进入现有 `frameChannel`，不再次拷贝；
  - `StreamingBurstFrame` 带 `timestampNs`、`exposureTimeNs`、`noiseProfileS/O`、`rotationDegrees`、`physicalId`；
  - 帧数计入 `frameCount`；达到 `frameCount` 即完成，不需要 `captureBurst`。
- `HdrPlusBurstCaptureExecutor` 新增 `triggerHdrPlusBurstFromRing(...)`：
  - 用基准帧结果创建 `StreamingBurstCaptureContext`；
  - 创建 native streaming session（现有 `ColorProcessor.nativeCreateStreamingSession`）；
  - 创建 `HdrPlusStreamingBurst(frameCount = hdrBurstCount)` 并注入预卷帧；
  - 若 `remaining > 0`，设置 listener 并 `captureBurst`；否则跳过 capture；
  - 进度 UI：预卷帧数立即计入；补拍帧递增；
  - watchdog：预卷已满时只保留 native 处理超时保护，不启用“8 秒等不到帧”的 capture watchdog。
- 现有 `onStreamingBurstComplete` → `processStreamingHdrPlusBurst` → `CaptureTaskSpec` → `CaptureSink` 完全不变。
- 环 buffer 来自 `StreamingBufferPool`（或统一的 `RawBufferBudget`）；预卷帧所有权转移给 burst，burst 释放回池；环不再引用。

### 4.7 低光/不稳定/失败回退

任一条件成立，立即回前向 HDR+（当前 `triggerHdrPlusBurstCamera2`），不拍脑袋硬上 ZSL：

- 稳定段 <3 帧；
- 基准帧 ISO 达到 `maxIso` 的 80% 以上，且曝光已被 66 ms 上限卡住；
- 基准帧元数据缺失，或 native session 创建失败；
- 补拍请求失败/超时；
- 热状态 ≥`SEVERE`、内存不足、session 失效、用户切模式/镜头。
- 回退要保留已按下快门的事实：不能只弹错；前向 HDR+ 继续完成拍摄。

### 4.8 HDR+ ZSL 画质门禁

在受控场景（静态、灰卡/高光/暗部、运动主体）对比 HDR+ ZSL 与前向 HDR+：

- SSIM ≥0.98；
- 高光 `>=254` 像素占比增加 ≤1 个百分点；
- 暗部 σ 不劣化 >10%；
- 运动主体无新增明显鬼影；鬼影指标不高于前向 +5%；
- 不出现整幅亮度跳变（预览增益/`digitalGain` 正确）。

任一设备不达标：该设备 HDR+ ZSL 标记为不可用，持久化；用户仍可关闭 ZSL 用前向 HDR+。**不要为了“有 ZSL”而放宽画质门禁。**

---

## 5. 单帧 ZSL（HDR+ 关）

- 仅当 RAW 尺寸 `frameBytes ≤32 MiB`；否则 A 全路线不可用。
- 环深度 3；正常 AE，不做欠曝、不做预览增益。
- 选中距 `T0` 最近的有效帧；`|ts-T0|` 超过 2T 视为 miss，回前向单帧。
- 构造 `RawImageHolder`（带 `captureResult`、`timing`、lens/zoom/sink），直接投递到 `CameraCapturePipelineDispatcher.processImageAsync`。
- 不发起新的 capture；DNG/JPEG/LUT 走现有单帧管线。
- 目标：按下→选中 ≤8 ms；按下→入队 ≤20 ms；按下→快门 UI 就绪 ≤50 ms。
- B1 可用且 HDR+ 关时，优先 B1；否则 A；再否则 OFF。

---

## 6. B1：可选 RAW reprocess（本期不阻塞）

### 6.1 何时才实现

Stage 0/1 用实验室设备检查：

```
PRIVATE_REPROCESSING
+ getInputFormats()/getInputSizes(PRIVATE) 非空
+ getValidOutputFormatsForInput(PRIVATE).contains(RAW_SENSOR)
+ getOutputSizes(RAW_SENSOR) 非空
```

若没有任何设备满足 `RAW_SENSOR` 这一条：**只实现能力检测和“不可用”状态，不实现 reprocessable session**。不要为不存在的硬件写一条完整路径。

### 6.2 若可用

- 相机启动时创建一次 reprocessable session：PRIVATE 输入 + PRIVATE ZSL reader（5–10 帧）+ RAW reprocess reader + 预览。
- repeating request 用 `TEMPLATE_ZERO_SHUTTER_LAG`，target 预览 + ZSL reader。
- 首次使用先做一次“单帧 reprocess 到 RAW”的运行时探针：能出 RAW、尺寸/格式正确、耗时 ≤500 ms；失败则持久化 B1 不可用，回 A/OFF。
- 按下时选“AF/AE 收敛、无闪光、优先第 3 帧”的帧（参考 Google ZSLDemo），reprocess 到 RAW；把 RAW 当单帧输入现有管线。
- B1 只支持 HDR+ 关；HDR+ 开时走 A。
- B1 不产出相机 JPEG；B2 已删除。

---

## 7. 设置页与自动检测

### 7.1 UI

放在 `section_debug`（实验性实验室）内，`switch_force_60fps` 之后、镜头标定之前：

```
EXPERIMENTAL LAB
[零快门延迟                         (switch)]  EXP
[ZSL 路线
 HDR+ 预卷 · 5/8 帧               ]  ← 双行 telemetry
```

- 外层：`errorContainer` Bento tray，`strokeWidth=0`，26dp 圆角；
- 开关：`BentoSwitchTile`（标题 + `EXP` badge）；
- 状态：`MaterialCardView`（`?attr/colorSurface`，14dp 圆角，`?attr/colorOnSurface`），内部一个 `TextView`，用 `CameraBentoDeckView.formatProTelemetry(label, value)`；
- `maxLines=2`、`ellipsize=end`；label 9.5sp 大写，value 11.5sp；
- 所有新字符串同时写入 `values/strings.xml` 与 `values-zh-rCN/strings.xml`；布局用 `tools:text`，不写死中英文。

状态值（单语言，不拼接双语）：

| 条件 | 值 |
|---|---|
| HDR+ ZSL 可用 | `HDR+ 预卷 · 5/8 帧` |
| 单帧 A | `单帧 · 3 帧` |
| B1 | `RAW 重处理 · 单帧` |
| HDR+ 开启但 HDR+ ZSL 不可用 | `未生效 · HDR+` |
| 手动曝光/闪光/多摄/视频 | `未启用 · 当前模式` |
| 能力不足 | `不可用` |

具体原因通过 Toast（开启时一次）和复制诊断日志呈现，不占第三行。

### 7.2 静态检测（不打开相机）

`ZslCapabilityDetector` 只读 `CameraCharacteristics`，`Dispatchers.Default`，目标 ≤50ms：

- API ≥26（app minSdk）；`INFO_SUPPORTED_HARDWARE_LEVEL != LEGACY`；
- A：`RAW` + `RAW_SENSOR` 尺寸；`frameBytes ≤32 MiB`；`READ_SENSOR_SETTINGS`；建议 `MANUAL_POST_PROCESSING`；
- HDR+ ZSL 额外：`CONTROL_AE_COMPENSATION_RANGE` 支持负 EV；`CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES` 含 15–30；
- B1：`PRIVATE_REPROCESSING` + PRIVATE input/output + RAW reprocess 输出（可选）；
- 时间戳源：`REALTIME` 优先；`UNKNOWN` 允许但状态标注“近似”；
- 内存：`isLowRamDevice == false`；`availMem` 与 ring 预算；
- 模式：Normal、单摄、闪光灯关、非手动曝光。

结果持久化为 `ZslCapabilityReport`（按 `cameraId + Build.FINGERPRINT + appVersion`），只存路线/原因/时间戳。

### 7.3 首次 arm 探针

A 的首次 arm 在后台跑 2 秒 / 60 帧：

- RAW fps ≥15（推荐 ≥24）；
- 帧间隔 P95 ≤1.5T、最大 ≤6T；
- RAW 拷贝 P95 ≤0.8T；
- 预览掉帧 ≤10%（硬上限 20%）；
- 元数据命中 ≥99%；
- 热状态不升高到 `SEVERE`。

通过 2 次才标记可用；连续 3 次失败才持久化“该设备 A 不可用”；中间状态不自动翻转。探针期间用户按快门 → 直接前向拍摄，不阻塞。

### 7.4 热/内存/模式降级

- `MODERATE`：HDR+ 预卷 depth 5→3，fps 30→15；
- `SEVERE`/`CRITICAL`：立即 disarm，清环；
- `availMem` 低于 `600 MiB + ringBytes`：暂停环，拍摄完成后重试；
- 切镜头/半格/多摄/视频/闪光灯/手动曝光/`force60fps`：disarm；
- 用户关闭开关：立即 disarm，释放 buffer，预览增益恢复 1.0。

---

## 8. 红线与量化目标

### 8.1 红线（不满足不得合入）

| # | 红线 | 判定 |
|---|---|---|
| R1 | UI 线程零阻塞 | 主线程 ZSL 工作 ≤2ms/事件；无 Camera2 IPC、无 RAW 拷贝、无磁盘 IO；StrictMode/Perfetto |
| R2 | Image 生命周期 | 所有路径 `finally { image.close() }`；环只存拷贝；60s armed + 8 帧 burst 零 `maxImages` stall |
| R3 | 内存有界 | 环 ≤128 MiB；12 MP 默认 5 帧≈120 MiB；10min 原生内存增长 ≤8 MiB；`onTrimMemory` 立即释放 |
| R4 | 预览不退化 | armed P50 fps 下降 ≤10%；P95 帧间隔 ≤1.15× 基线；>20% 或 >2T 卡顿占比 >2% 时 2s 内自动 disarm |
| R5 | HDR+ 帧一致性 | 送入 native 的预卷帧必须来自同一稳定段：曝光差 ≤5%、ISO 差 ≤5%、WB 差 ≤5%；否则不得送入 |
| R6 | 元数据精确 | `Image.timestamp == SENSOR_TIMESTAMP`；ISO/曝光/WB 非空；crop/lens 一致；禁止 5ms 容差 |
| R7 | 零拍摄丢失 | ZSL 失败 100% 回前向；不重复弹窗；不因 ZSL 拒绝快门 |
| R8 | 不静默降画质 | HDR+ ZSL 只作为 “HDR+ On”；低光/不稳定自动回前向 HDR+；不达标设备持久化禁用 |
| R9 | 不重建 Session | A 只用 `setRepeatingRequest` 切 target；B1 每次相机启动最多 1 次配置 |
| R10 | 架构 | 无 CameraFragment 模式分支；无 10+ 原始参数；DNG 全走 `write_dng`；Bento/i18n |
| R11 | 预览增益隔离 | 只影响 viewfinder；Motion Photo/视频亮度不变（≤0.1 EV） |
| R12 | 质量门禁 | 单测 100%；`:app:buildCMakeRelease[arm64-v8a]` 0 warning；reviewer `APPROVED` |

### 8.2 目标

| 指标 | 目标 | 测量 |
|---|---|---|
| HDR+ ZSL 全预卷：按下→预卷入队/开始建 native session | ≤20ms P95 | trace |
| HDR+ ZSL 全预卷：按下→native 首帧 push | ≤50ms P95（含 session 创建） | trace |
| HDR+ ZSL 全预卷：按下→最后一帧可用 | 0ms 额外（全部已在环） | timing |
| 预卷不足：按下→最后一帧 | `remaining × T + 1T` | timing |
| 基准帧时刻误差 | P50 ≤0.5T，P95 ≤1.5T | timestamp |
| 单帧 ZSL：按下→选中/入队 | ≤8ms / ≤20ms P95 | trace |
| 单帧 ZSL：按下→快门 UI 就绪 | ≤50ms P95 | timing |
| RAW fps | ≥15，推荐 ≥24 | 60 帧探针 |
| 拷贝 P95 | ≤0.8T | trace |
| 预览掉帧 | 推荐 ≤10%，硬上限 20% | 双流对比 |
| 元数据命中 | ≥99% | 探针 |
| 环深度 12 MP | 5 帧（120 MiB）；硬上限 128 MiB | native heap |
| HDR+ ZSL 画质 | SSIM ≥0.98；clipping +≤1pp；暗部 σ +≤10% | 受控场景 |
| 预览增益亮度误差 | ≤0.1 EV | 灰卡 |
| 低光回退 | ISO ≥80% max 且曝光卡 66ms → 前向 | 设备矩阵 |
| 稳定性 | 50 次 arm/disarm + 100 次快门 0 crash/ANR | 压测 |
| 静态检测 | ≤50ms；首次 arm ≤2.5s | 设备矩阵 |

---

## 9. 架构不变量对齐

| 不变量 | 做法 |
|---|---|
| 1 CaptureSink / 统一 DNG | 单帧/HDR+ ZSL 都进入现有 `CaptureTaskSpec`/`CaptureSink`/`write_dng`；B1 也只产 RAW 后进现有管线；无 `DngCreator`；无 B2 |
| 2 参数正交化 | `ZslCapabilityReport`、`ZslRingConfig`、`ZslFrame`、`ZslRouteDecision`、`ZslBurstSeed` 聚合；只给 `StandardTimingTracker` 加 `shutterPressElapsedNs` 和 ZSL 诊断字段 |
| 3 模式隔离 | 不改任何 `CaptureModeCoordinator`；在 `CameraModeController` 的 `ModeExecutionContext` 宿主适配层插入 `ZslCaptureController`；`CameraFragment` 零模式分支 |
| 4 Headless / 零 UI 阻塞 | RAW 拷贝/选帧/`captureBurst`/`ImageWriter` 全在 `camera2Handler` 或专用 `zslRawHandler`；UI 只采样时间戳和观察状态 |
| 5 Bento / M3 / i18n | 实验托盘 + porcelain 状态卡 + 双行 telemetry；中英资源各一份；无双语拼接 |

关键新模块：

- `camera/zsl/ZslCapability.kt`：能力/报告/原因枚举。
- `camera/zsl/ZslCapabilityDetector.kt`：静态检测（纯函数 + Android characteristics reader）。
- `camera/zsl/ZslRouteSelector.kt`：profile/burst count/内存/模式选择（纯函数）。
- `camera/zsl/ZslRawRing.kt`：环 + 选段 + 预算。
- `camera/zsl/RawFrameRouter.kt`：单监听器，RING/CAPTURE/IDLE 状态机。
- `camera/zsl/ZslCaptureController.kt`：宿主适配层，单帧/HDR+/B1 入口。
- `camera/zsl/ZslHdrPlusCapture.kt`：预卷注入 + 补拍 + 回退。
- `camera/zsl/ZslTelemetry.kt`：诊断/状态。
- `camera/zsl/ZslPreviewGain.kt`：预览增益桥接（或直接放 `LutSurfaceProcessor`）。
- 测试：`app/src/test/java/.../zsl/`。

修改点：

- `CameraHardwareStreamController`：preview request factory、RAW target、router、profile 参数。
- `Camera2SessionController`：可选 reprocessable session 方法（B1）。
- `CameraSessionArbiter`：环/资源清理。
- `CameraModeController`：`triggerSinglePicture`/`triggerHdrPlusBurst` 先问 `ZslCaptureController`；不改 coordinator。
- `SingleRawCaptureExecutor` / `HdrPlusBurstCaptureExecutor`：通过 router 消费 RAW；新增 ZSL 路径。
- `RawImageHolder`：可选 `captureResult` / buffer lease。
- `CameraRuntimeStateHolder` / `CameraCaptureMetadataExtractor`：结果缓存有界 + 精确查找。
- `HdrPlusStreamingBurst`：预卷注入。
- `LutGlShaderPipeline` / `LutSurfaceProcessor`：预览增益。
- `StandardTimingTracker`：按下时刻 + ZSL 指标。
- settings：`PreferenceKeys`、`DarkbagPreferences`、`SettingsRepository`、`SettingsViewModel`、`SettingsFragment`、`SettingsBentoDelegate`、layout/strings。

---

## 10. 实施阶段、提交与 PR

> 目标是 2–3 周内完成 A + HDR+ ZSL，不做无硬件支撑的 B 全路径。每阶段完成即测试、reviewer、commit、push。

### Stage 0：检测 + 设置 + 可行性探针（约 3 天）

- 收敛 preview request factory（为 RAW target 做准备）。
- `captureResults` 加有界淘汰；精确 timestamp 查找。
- `StandardTimingTracker` 加按下时刻/诊断字段。
- `ZslCapabilityDetector` + `ZslRouteSelector` + 设置页开关/状态/持久化。
- 实验室探针：至少 2 台设备，跑 30s 常驻 RAW，记录 fps/拷贝/预览掉帧/内存；同时检查 B1 RAW reprocess 输出是否存在。
- **Go/No-Go**：若没有设备能在 12–16 MP 下维持 ≥15 fps RAW 且预览掉帧 ≤20%，停止 A 的后续开发，只保留检测/设置。
- 交付：`feat(zsl): add capability detection and experimental settings entry`。

### Stage 1：A 单帧 ZSL（约 4 天）

- `RawFrameRouter`、`ZslRawRing`、预算、专用拷贝线程。
- arm/disarm（RAW target 切换）、选帧、`RawImageHolder` handoff、单帧管线。
- 热/内存/模式降级、fallback。
- 单测：环淘汰、选帧、路由状态机、预算。
- 交付：`feat(zsl): add RAW ring buffer and single-frame ZSL capture`。

### Stage 2：HDR+ ZSL（约 5 天）

- 曝光/预览增益：AE compensation、FPS range、`uPreviewGain`（viewfinder-only）。
- 稳定段选择、基准帧、预卷注入、补拍、回退。
- 复用 `HdrPlusStreamingBurst`；不改 native 算法。
- 画质门禁：受控场景对比前向 HDR+；不达标设备持久化禁用。
- 单测/设备测试；reviewer。
- 交付：`feat(zsl): add HDR+ ZSL from pre-roll ring`。

### Stage 3：B1 可选（0–3 天，条件触发）

- 仅当 Stage 0 找到 `RAW_SENSOR` reprocess 输出的设备：
  - reprocessable session + PRIVATE ring + RAW reprocess + 单帧管线。
- 否则只保留检测状态，不写 session 路径。
- 交付（如有）：`feat(zsl): add optional RAW reprocess route`。

### Stage 4：文档 + PR（约 1 天）

- 记录设备矩阵、探针数据、Perfetto/trace、画质对比、已知限制。
- 更新本文件状态与结果。
- Draft PR（Stage 0 后开）→ Ready（Stage 2 后）。
- 交付：`docs(zsl): record device matrix and baseline measurements`。

### PR

- 标题：`feat(camera): experimental HDR+ zero-shutter-lag capture (B1→A→none)`
- 基线：当前 `refactor/datastore-and-arch-hardening`（stacked）；合并后 retarget `main`。
- 内容：A 单帧 + HDR+ ZSL 为必选；B1 为可选/条件路径；B2 明确不在范围。
- PR 描述必须包含：红线/目标表、设备矩阵、画质对比、设置页截图、已知限制（5 帧 vs Pixel 9/15；预览增益；低光回退）、回滚方式（关开关即回前向）。
- 按 `AGENTS.md` 双环流程：独立 reviewer `APPROVED` 后才能提交；`REVISE_REQUIRED` 必须逐项修复。

---

## 11. 测试矩阵

### 单元测试（JVM，无 Android）

- 能力矩阵 → profile/原因（A/B1/OFF；HDR+ 开/关；手动曝光/闪光/多摄/Low RAM）。
- 环深度：12/16/8/48 MP；burst count 3–8；128 MiB 上限；600 MiB 预留。
- 选段：曝光/ISO/WB 差阈值；<3 帧回退；跨 lens/crop 拒绝；时间戳精确匹配。
- 路由状态机：RING→CAPTURE→IDLE；并发 arm/disarm；无偷帧。
- 预览增益：viewfinder 2.0/4.0；encoder 1.0；disarm 恢复。
- 降级：MODERATE/SEVERE；内存不足；模式切换。

### 设备测试

- 至少 3 台：12 MP LEVEL_3/FULL、16 MP、LIMITED/低端；若可用，加入 48 MP 设备验证 A 禁用/B1 fallback。
- 50 次 arm/disarm；100 次快门（HDR+ 开/关混合）；8 帧 burst 与环并发；切镜头/模式/前后台。
- 10 分钟 armed 预览：内存、热、fps、掉帧。
- HDR+ ZSL vs 前向 HDR+：静态/高光/暗部/运动；SSIM/clipping/σ/鬼影。
- 预览增益：灰卡亮度；Motion Photo/视频前后亮度；LUT/log 预览不受影响。
- B1（如有）：reprocess RAW 成功、DNG 色彩、FOV、50–100ms 目标。

---

## 12. 风险与未决项

| 风险 | 影响 | 缓解 |
|---|---|---|
| 5 帧少于 Pixel 9/15 帧 | HDR+ ZSL 暗部/噪声弱于 Pixel | 明确是 “HDR+ On”；低光/高动态回前向；画质门禁 |
| 恒定曝光段太短 | 频繁回退，ZSL 命中率低 | AE 收敛后 arm；稳定段阈值；状态/诊断 |
| 预览欠曝 + 增益 | 预览噪声上升、AF 受影响 | 增益上限 4x；低光回退；AF 失败回退 |
| 66 ms 曝光上限 | 暗光噪声高 | ISO 80% 阈值回前向；Night/Enhanced 路径 |
| `[15,30]` 预览掉到 15fps | 取景流畅度下降 | 只在低光；状态提示；可关闭 ZSL |
| 48 MP RAW 96 MiB | A 全路线不可用（单帧/HDR+ 都禁用） | 不做第二 RAW Reader；B1 可选；或 OFF |
| 内存池重叠 | ring + streaming pool 峰值高 | 统一预算；ZSL 活跃时池上限 2；600 MiB 预留 |
| B1 设备缺失 | 无法验证 B 路线 | 只做检测；不写无效代码；A 为主 |
| 预览增益影响编码 | Motion Photo/视频变亮 | encoder pass 强制 1.0；专项测试 |

---

## 13. 参考

### 官方 / AOSP

- CameraX ZSL：`kb://android/media/camera/camerax/take-photo/zsl`
- Camera2 reprocess：`CameraDevice.createReprocessableCaptureSession`、`CameraCaptureSession.getInputSurface()`、`createReprocessCaptureRequest`
- `TEMPLATE_ZERO_SHUTTER_LAG`、`REQUEST_AVAILABLE_CAPABILITIES_PRIVATE_REPROCESSING`
- `SENSOR_INFO_TIMESTAMP_SOURCE`、`READ_SENSOR_SETTINGS`、`MANUAL_POST_PROCESSING`
- 热：`kb://android/agents/skills/camera/camerax/references/thermals`
- Google ZSLDemo：https://github.com/google/zsldemo
- Pixel HDR+ ZSL quirk：https://android.googlesource.com/platform/frameworks/support/+/187e9a2088c3f281cfb617b9bedd94b9a3546d8b/camera/camera-camera2/src/main/java/androidx/camera/camera2/internal/compat/quirk/ImageCapturePixelHDRPlusQuirk.java

### 论文 / 技术文章

- Hasinoff et al., *Burst photography for high dynamic range and low-light imaging on mobile cameras*, SIGGRAPH Asia 2016.
- Google Research, [HDR+ with Bracketing on Pixel Phones](https://research.google/blog/hdr-with-bracketing-on-pixel-phones/).
- Google Research, [Live HDR+ and Dual Exposure Controls on Pixel 4 and 4a](https://research.google/blog/live-hdr-and-dual-exposure-controls-on-pixel-4-and-4a/).
- [GCam Handbook](https://www.celsoazevedo.com/files/android/p/f/2021/05/GCam-Handbook.pdf).
- 公开分析：Pixel 默认 ZSL 环 9/15 帧、曝光上限约 66 ms（作为设计参考，不作为 API 承诺）。

---

## 14. 给后续 agent 的执行顺序

1. 读本文件 + `AGENTS.md`；确认基线 commit 和 worktree。
2. 先做 Stage 0；不要先写 HDR+ ZSL。
3. Stage 0 的 Go/No-Go 不过就停，不要硬写。
4. A 单帧通过后再做 HDR+ ZSL；HDR+ ZSL 先做全预卷路径，再做补拍。
5. B1 只在有设备支持时做；B2 永远不做。
6. 每阶段：单测 → 设备验证 → reviewer → commit → push。
7. 任何“为了 ZSL 而放宽画质/内存/线程红线”的改动，直接拒绝。
