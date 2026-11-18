# ZSL 精细化执行方案（Review + Motion Photo 讨论收敛）

- **基线**：`feat-experimental-zsl` @ `f4ddeb37`，PR #342（Draft）
- **输入**：
  1. Antigravity review：2×P0、5×P1、2×P2；
  2. Antigravity Motion Photo / rawvid 讨论：ZSL+Motion 可共存，`.rawvid` 不替代常驻 Motion Photo，零拷贝 ring 可后续优化。
- **原则**：先修正确性，再开 Motion Photo；先单帧，再 HDR+；所有新路径保留前向回退。
- **不在本方案内**：B2 相机 JPEG、B1 RAW reprocess、HAL 私有 ZSL、`.rawvid` 替换常驻 Motion Photo。

---

## 实施进度（2026-11-18）

| 项 | 状态 | 提交/说明 |
|---|---|---|
| Phase 0 诊断归位 | 已完成 | `36863c9c`：debug 块移到性能诊断卡，ZSL 卡恢复两行 |
| Z1 焦段切换 | 已完成 | `1e9c93d6`：zoom 请求收敛，ring camera/lens/physical/zoom 身份校验 |
| Z2 AF 门禁 | 已完成 | `1e9c93d6`：扫描/未锁定帧拒绝，手动对焦豁免 |
| Z3 AE step/低光 | 已完成 | `1e9c93d6`：真实 `CONTROL_AE_COMPENSATION_STEP`、低光回退 |
| Z4 元数据竞态 | 已完成 | `1e9c93d6`：ring 锁内二次查询迟到结果 |
| Z5 预卷参考帧 | 已完成 | `1e9c93d6`：seed 首帧锁定 |
| Z6 状态/池/trim | 已完成（真机待验） | `36863c9c`：HdrPlusBurst 池统一、trySend/drain、MVI 状态复位、trim hook |
| Z7.1 单帧 + Motion | 已实现（设备待验） | 单帧/Instant Single 可与 Motion 共存；`00771139` 已把选中 RAW 的 sensor time 映射到 encoder monotonic PTS，真机 still PTS 待验证 |
| Z7.2 HDR+ + Motion | 已实现（设备待验） | encoder 使用 previewGain；HDR+ ZSL 只使用完整预卷（adaptive 3–5 帧，strict 必须满 burst），不补拍/不 disarm；still PTS 已映射，Motion 亮度/连续性待验证 |
| Z8 零拷贝 ring | 未开始 | 保留 `Image` 会占满 reader 槽位，需把生命周期传到 single/HDR+ 处理结束；当前不做未验证的 copy-on-take（峰值内存反而更高） |
| Adaptive ZSL | 已实现 | `66763ff4`：稳定 3–5 帧直接用实际帧数做 HDR+；无稳定段取最新有效 RAW 走非 HDR+ 单帧；严格模式必须满预卷，否则 forward；`hdrBurstCount` 为上限 |
| 快门反馈 | 已实现 | `67572b76`：ZSL 命中时轻触觉 + 快门短暂缩放脉冲，不增加相机主线程工作 |
| Pixel 3 验证 | 部分通过 | 已修复 Android 14 display NPE；Pixel 3 上 adaptive 5 帧和 3 帧（内存上限）ZSL 均成功出 DNG/JPG；Motion+ZSL、连续场景、still PTS 待测 |
| Z9 核心测试 | 部分完成 | `8ccf182f` 增加 trim hook、迟到 result 解析测试；`80688ba5` 增加 RawRouterState 的 IDLE/RING/CAPTURE、capture-start 锁存和 generation 重置测试 |
| 真机矩阵 | 部分开始 | Pixel 3 (`8BVX1FW0X`) 已连接并完成 adaptive ZSL 样张；moto 29.9fps ring；redroid 不作为相机设备 |

**剩余优先项**：Motion PTS 实测偏差；Z8 需要单独的所有权重构与真机槽位/热验证；完整真机矩阵。

---

## 0. 结论与决策

1. **路线 A（应用层 RAW 环）继续**：Camera2 标准路径无法稳定拿到 HAL HDR+ 预卷；当前方向是保住 RAW/HDR+/DNG 的唯一可行路径。
2. **Motion Photo 与 ZSL 不冲突**：两者的 ring 是不同数据（H.264 vs RAW），冲突来自预览曝光/编码增益、`disarmForShot()` 和时钟域，不是资源模型本身。
3. **不把常驻 Motion Photo 换成 `.rawvid`**：4.5s H.264 约 5.6 MB、硬件编码近零 CPU；`.rawvid` 的 12MP 环形缓冲需 900–1200 MB，2K binning 仍 180–270 MB，且持续压缩会触发温控并反向关闭 ZSL。`.rawvid` 只保留长按 RAW 视频模式。
4. **零拷贝 ring 是性能优化，不是正确性前置**：先修复 P0/P1，再以 feature flag 实验 `Image` 持有方案；未通过真机帧率/热/槽位验证前不默认开启。
5. **Motion Photo 共存分两步**：单帧 ZSL + Motion → HDR+ ZSL + Motion。每步都必须有独立回退。

---

## 1. 工作项总览

| ID | 优先级 | 工作项 | 主要文件 | 依赖 | 预估 |
|---|---|---|---|---|---|
| Z1 | P0 | 焦段/倍率切换请求收敛，ring 失效检测 | `CameraLensController.kt`、`ZslCaptureController.kt` | — | S |
| Z2 | P0 | AF 收敛门禁修复 | `ZslFrameSelector.kt`、`ZslRawRing.kt` | — | S |
| Z3 | P1 | AE step / EV / 低光回退 | `ZslCameraFacts`、`ZslCapabilityDetector`、`ZslCaptureController` | Z1 | M |
| Z4 | P1 | RAW 元数据 TOCTOU 竞态 | `RawFrameRouter.kt`、`ZslRawRing.kt` | — | S |
| Z5 | P1 | 预卷帧参考帧锁定/自适应路径 | `ZslFrameSelector.kt`、`HdrPlusStreamingBurst.kt` | Z4 | M |
| Z6 | P1 | 单帧 ZSL 状态、池所有权、onTrimMemory | `ZslCaptureController.kt`、`CameraShutterController.kt`、`MainActivity/MainApplication` | — | M |
| Z7 | P1 | Motion Photo 共存（单帧 → HDR+） | `LutSurfaceProcessor.kt`、`CameraMotionPhotoController.kt`、`MotionPhotoEncoder.kt`、`ZslCaptureController.kt` | Z1–Z6 | L |
| Z8 | P2 | ring 零拷贝实验（feature flag） | `ZslRawRing.kt`、`RawFrameRouter.kt`、`CameraSessionArbiter` | Z7 真机稳定 | L |
| Z9 | P2 | 诊断 UI 归位、核心状态机测试 | `SettingsFragment`、`fragment_settings.xml`、tests | Z1–Z6 | M |

---

## 2. Phase 0：基线与诊断（0.5 天）

### 目标
让后续每个修复都能用同一套诊断判断“是否真的生效”。

### 动作
1. 保留现有 `ZslDebugRegistry` / timing 报告；把多行 `tv_zsl_debug` 从 `card_zsl_status` 移到下方 `tv_debug_stats`（或 Debug Sheet），`card_zsl_status` 只保留两行 telemetry，满足 Invariant 5。
2. 固定测试设备：moto XT2409-5（4096×3072 RAW）、Pixel 3（若有）；redroid 只做构建/单测，不做相机验证。
3. 每次修复记录：
   - `ZSL armed/profile/ring fps`
   - 实际 `SENSOR_EXPOSURE_TIME` / `SENSOR_SENSITIVITY` / `CONTROL_AE_EXPOSURE_COMPENSATION`
   - `ZslFrameSelector` 选中的 base timestamp 与快门时刻
   - 前向 vs ZSL 的 T1/T2/T3。

### 验收
- debug 数据可在拍摄后复制；`card_zsl_status` 不再超过两行。
- 每次修改都能从 timing 报告区分 `route=raw_ring` 与 `fallback=...`。

---

## 3. Phase 1：正确性修复（P0/P1）

### Z1 焦段/倍率切换收敛（P0）

**问题**：`CameraLensController.updateZoomCamera2()` 自建 `TEMPLATE_PREVIEW` 请求，绕过 `applyZslPreviewProfile()` 和 `rawImageReader.surface`；切换 24→28/35mm 后 ring 断流、预览过曝。

**实现**
- 删除 `updateZoomCamera2()` 的手工请求构造，统一走 `coordinator.applyCameraControls()` / `CameraHardwareStreamController.updateCamera2RepeatingRequest()`；确保同时设置 `SCALER_CROP_REGION`、RAW target、ZSL preview profile。
- `ZslCaptureController.evaluate()` 的幂等判断增加 `(cameraId, zoomRatio, lensId)`：
  ```kotlin
  if (profile == targetProfile && activeConfig == limitedConfig &&
      ring != null && ring.matches(cameraId, zoom, lens?.physicalId)) return
  ```
- zoom 变化时 `disarm("zoom_changed")` 并等待新 ring 或直接重建 ring；旧帧 `maxAge` 不足 300ms，不能混用。
- `ZslRawRing` 记录 `zoomRatio`/`lensId`，`take` 前校验当前 ring 与 session 一致。

**测试**
- 单测：ring key 不匹配时 `nearest/stableRun` 返回空。
- 真机：1x/2x/28mm/35mm 切换后 300ms 内 ring 恢复；预览亮度不跳变；连续拍 3 张不 miss。

**验收**
- 焦段切换后第一次快门仍命中 ZSL（或明确 `fallback=zoom_changed`）。
- 无 RAW target 丢失；无预览过曝。

---

### Z2 AF 收敛门禁（P0）

**问题**：`isStable` 中 `abs(timestamp-base) > 2s` 在 300ms ring 内永远为 false；AF hunting 帧全部通过。单帧 `nearestIndex` 完全不看 AF。

**实现**
- `isStable`：要求 `base.afFocused == true`，且段内全部 `afFocused == true`；不再使用 2s 超时窗口。
- `nearestIndex`：过滤 `afFocused == false`；若手动对焦（`CONTROL_AF_MODE_OFF`）由 `ZslRawRing` 标记为 focused。
- `ZslRawRing.isAfFocused`：
  - `AF_MODE_OFF` → `true`（手动对焦豁免）。
  - `ACTIVE_SCAN` / `PASSIVE_SCAN` → `false`。
  - `FOCUSED` / `PASSIVE_FOCUSED` / `NOT_FOCUSED_LOCKED`（按设备行为）→ 仅当 `LENS_STATE_STATIONARY` 或锁定态；不要仅凭 `LENS_STATE` 绕过扫描态。
- AF timeout 常量改为“段内最大年龄”语义（`<= maxAgeNs`），不再用 2s。

**测试**
- 纯单测：全 hunting → stableRun 空；一帧 focused → 单帧可选中；手动 AF → 可选。
- 真机：AF 对焦窗外/拉风箱时按快门，应回退 forward 而不是出糊片。

**验收**
- 100 次 AF hunting 场景 0 次把扫描帧作为 base；对焦收敛后正常命中。

---

### Z3 AE step / EV / 低光回退（P1）

**问题**：`-3/-6` 步硬编码 1/3 EV；`hdrDigitalGain = 2^(-steps/3)` 在 1/2、1/6 step 设备上亮度偏差。无低光回退。

**实现**
- `ZslCameraFacts` 新增 `aeCompensationStep: Float`（由 `CONTROL_AE_COMPENSATION_STEP` 的 numerator/denominator 得出）；`ZslCapabilityReport` 暴露 `aeStepEv`。
- `resolveHdrEvSteps()` 按 `targetEv / aeStepEv` 计算步数，并 clamp 到 `CONTROL_AE_COMPENSATION_RANGE`；预览/成片 gain 用实际 EV 计算，而不是固定 `/3.0`。
- `applyZslPreviewProfile` 只写步数；gain 由同一个实际 EV 计算，避免“传感器实际 -0.67EV，gain 按 -1EV”的错位。
- `prepareHdrPlusSeed` 低光门禁：
  - `base.iso >= isoRange.upper * 0.8` 且 `base.exposureTimeNs >= min(66ms, exposureRange.upper)` → 返回 null，`fallback=low_light`；
  - 单帧 ZSL 不受此门禁（正常曝光）。
- 可选（Phase 3）：根据 `iso * exposure` 动态把 HDR+ 欠曝从 -2EV 降到 -1EV/0EV，先做门控，后做动态。

**测试**
- 单测：1/2、1/3、1/6 step 的 EV/gain 期望值；用户 EV + HDR EV clamp。
- 单测：低光事实 → fallback reason。
- 真机：同一场景 ZSL 与前向 HDR+ 的 JPEG 平均亮度差 ≤0.1EV。

**验收**
- 不同 step 设备 gain 误差 ≤0.1EV；低光不强行 ZSL。

---

### Z4 RAW 元数据竞态（P1）

**问题**：`offer()` 拷贝 24MiB 期间 `attachResult()` 找不到尚未入队的帧，该帧永久 `result=null`。

**实现**
- `ZslRawRing.offer(image, result, resultLookup)`：拷贝完成后在 `synchronized(lock)` 入队前执行 `result ?: resultLookup(image.timestamp)`；或在 ring 内保存 `pendingResult`，`attachResult` 与 offer 共用锁。
- 保证 `attachResult` 对已入队帧立即 binding；对未入队帧写入 `pendingResults[timestamp]`，offer 入队时消费。

**测试**
- 单测/纯逻辑：模拟 result 早到、晚到、与 offer 同时到；0 丢元数据。
- 真机 5 分钟 30fps：`result=null` 帧比例 0。

**验收**
- ring 有效帧率不因迟到 result 下降。

---

### Z5 预卷参考帧锁定（P1）

**问题**：预卷帧按时间倒序进入 `HdrPlusStreamingBurst`，adaptive path 的 `timeDelta` 为负被截 0，可能把旧帧当参考帧，成片时刻偏移。

**实现（二选一，优先 A）**
- A：`HdrPlusStreamingBurst` 增加 `lockFirstFrameAsReference`（seed 路径 true），直接把 `seed.frames[0]`（T0 基准帧）作为 reference 推入 native session，跳过 adaptive base selection。
- B：adaptive path 的 `timeDeltaNs` 使用 `abs(...)`，且对 ZSL seed 禁止 future/older 参考帧偏移；实现简单但不如 A 稳。
- 同步 `seed.baseResult` 与 reference frame 元数据，禁止 reference 与 metadata 解耦。

**测试**
- 单测：seed 顺序/首帧锁定参数。
- 真机：全预卷成片时间戳与 T0 差 ≤1 帧；不出现向过去偏移 66–100ms。

**验收**
- 全预卷 HDR+ ZSL 参考帧恒为 T0。

---

### Z6 单帧状态、池、trim memory（P1）

**问题**
- `tryCaptureSingle` 只 `ui.resetBurstUi()`，未 `cameraViewModel.setBurstActive(false)`；MVI 状态可能卡 true。
- `enqueueZslSingle` 绕过 processingChannel，未记录 timing。
- 单帧 buffer 从 `StreamingBufferPool` 获取，却可能走 `HdrPlusBurst.releaseBuffer` 跨池释放。
- `onTrimMemory` 未清 ZSL ring。

**实现**
- `tryCaptureSingle` 完成后调用 `cameraViewModel.setBurstActive(false)`、`showProcessingAnimation()`，与前向单帧一致；`enqueueZslSingle` 记录 `timing.recordFrameArrival`/`enqueued`，并通过 dispatcher 的 processingChannel 或等价的单任务串行队列。
- 为 ZSL buffer 增加所有权标识（或统一使用 `HdrPlusBurst` 池），释放路径按所有权归还；`ZslBurstSeed.release` 保持幂等。
- `MainApplication.onTrimMemory`/`MainActivity.onTrimMemory` 通过进程级回调（如 `ZslRuntimeHooks.onTrimMemory`）通知 controller `disarm("memory_trim")` + `clear`；不要直接持有 controller。
- `ZslRawRing.clear`/`take` 的 `Image`/buffer 释放路径统一。

**测试**
- UI 状态单测：单帧 ZSL 后 `isBurstActive=false`。
- 内存：trim 后 ring/池清零，无 buffer double-release。
- 真机：连续单帧 ZSL 10 张，状态/缩略图/快门正常。

**验收**
- 0 卡死快门、0 跨池崩溃、trim 后 <100ms disarm。

---

## 4. Phase 2：Motion Photo 共存

### Z7.1 单帧 ZSL + Motion Photo（先做）

**前置**：Z1–Z6 完成，单帧 ZSL 真机稳定。

**实现**
1. 在 `ZslCaptureController.evaluate()` 中仅对 `Profile.SINGLE_RAW` 解除 `motionPhotoEnabled` 互斥；HDR+ 仍保持互斥直到 Z7.2。
2. 单帧 ZSL 不改曝光/gain，`MotionPhotoEncoder` 保持 `previewGain=1.0` 即可。
3. 统一时钟域：
   - 记录 `SENSOR_INFO_TIMESTAMP_SOURCE`；
   - 将 ZSL 选中帧的 sensor timestamp 映射到 `System.nanoTime`/encoder PTS；
   - `MotionPhotoEncoder.captureSnapshot` 接收到 `stillTimestampNs`（或允许在 mux 前更新），不要只用 `System.nanoTime()`。
4. 输出配对：`enqueueZslSingle` 等待 `pendingMotionPhotoTask`（复用 `CameraTaskSpecAssembler.awaitPendingMotionPhoto`），把 MP4/PTS 写进 `RawImageHolder`/spec。

**验收**
- Motion Photo 视频亮度与取景器一致；still PTS 与选中 RAW 帧差 ≤1 帧。
- 快门后 1.5s 视频无黑闪/掉帧（p95 帧间隔 <40ms）。
- 单帧 ZSL 仍命中，T2 不因等待 MP4 而阻塞照片落盘（视频可后完成）。

### Z7.2 HDR+ ZSL + Motion Photo

**实现**
1. `LutSurfaceProcessor` 编码分支使用 `previewGain`（不是 1.0），使视频与 viewfinder 亮度一致；focus peaking 仍强制关闭。
2. 全预卷命中（`seed.remainingCount == 0`）时**不调用 `disarmForShot()`**：保留 RAW target + preview profile；ring 取走 5 帧后 160ms 内自动回填，支持连续 ZSL。
3. 部分预卷仍需 `disarmForShot()`；补拍期间为 encoder 保持稳定 gain/AE：
   - 记录 shot 前的 `previewGain`/AE profile；
   - 补拍请求与 preview 的曝光策略一致，避免 1–2EV 跳变；
   - 若无法保证，直接回退 forward HDR+（明确 `fallback=post_roll_gain_unstable`）。
4. 解除 HDR+ + Motion Photo 互斥，但保留低光/热/内存/prereq 门控。
5. Motion Photo 的 `captureSnapshot` 在快门时启动，但 stillPTS 由 ZSL 选中帧延迟决定；MP4 mux 等待 ZSL 决策（最多 200ms）后在 1.6s 后完成。

**验收**
- 视频亮度跳变 ≤5%；still PTS 偏差 ≤1 帧；
- 快门后 1.5s 视频无明显掉帧；
- 全预卷连续 3 张 HDR+ ZSL 至少 2 张命中；
- 失败路径只出 1 张照片/1 个视频，无重复。

### Z7.3 不做的事
- 不用 `.rawvid` 替换常驻 Motion Photo（结论见 §0）。
- 不为了 Motion Photo 强行把 HDR+ 预卷改回正常曝光（会丢高光）。

---

## 5. Phase 3：性能与画质优化

### Z8 ring 零拷贝实验（P2，feature flag）

**问题**：当前每帧 24MiB `copyPlane`，30fps 约 720MB/s 读 + 720MB/s 写，导致发热/掉帧。

**实现（仅实验）**
- `rawImageReader.maxImages` 提高到 12–14；`ZslRawRing` 持有 `Image` 引用（深度 3–5），按 `removeFirst().close()` 淘汰；`take`/`clear`/trim 立即关闭。
- 增加 `ZslRingMode.EAGER_COPY` / `LAZY_IMAGE`；默认仍 EAGER，真机对比后再决定。
- 监测 `ImageReader` 槽位、预览丢帧、HAL backpressure、ring 有效帧率；异常自动回 EAGER。
- 验证保留 `Image` 不会与现有 `rawImageReader` 的 forward capture/JPEG fallback 冲突。

**验收**
- 待机 CPU/内存带宽下降 ≥30%，30fps 丢帧不增加；
- 连续 10 分钟取景无槽位耗尽、无热降级提前；
- 不达标就删除实验，不合并。

### Z8.1 动态 EV / AF（可选）
- HDR+ ZSL 预览欠曝限制 ≤1EV；ISO 高时自动 0EV；AF 对焦稳定性单独统计。
- 需要 Z3 的 AE step 基础；不与 Motion 共存同批上线。

---

## 6. Phase 4：测试与发布门禁

### 单测（每次提交）
- `ZslFrameSelector`：AF hunting、手动 AF、时间排序、稳定段。
- `ZslRawRing`：容量淘汰、result 早到/晚到、take 所有权、clear。
- `RawFrameRouter`：RING→CAPTURE→IDLE、并发 install/begin、timestamp gate。
- `ZslCaptureController`（可抽纯逻辑）：arm/disarm 原因、zoom/lens 失效、低光门禁、full pre-roll。
- `ZslBurstSeed`：release 幂等/部分释放。
- EV step：1/2、1/3、1/6。
- Motion Photo：PTS 映射、单/双 ring 互斥、失败回退。

### 真机矩阵
| 场景 | 期望 |
|---|---|
| 1x/2x/28/35mm 切换 | ring 300ms 内恢复，无过曝 |
| AF hunting | 回退 forward，不出糊片 |
| 强光/室内/极暗 | 强光 HDR+ ZSL；暗光 forward |
| Motion off/on | 单帧共存；HDR+ 分阶段 |
| 连拍 3 张 | 全预卷不断开，≥2 张 ZSL |
| 热/低内存/trim | 及时 disarm，无崩溃 |
| DNG/JPEG/Motion 输出 | 文件配对、PTS 正确、无重复 |

### 量化红线
- 预览/成片曝光误差 ≤0.1EV；
- 全预卷成片参考帧 = T0（±1 帧）；
- ring 元数据缺失 0；
- 单帧 ZSL T1 ≤100ms（p50，设备相关）；HDR+ 全预卷不新增相机请求；
- Motion 视频亮度跳变 ≤5%，still PTS 偏差 ≤1 帧；
- ZSL ring ≤128MiB，trim 后 100ms 内释放。

### 提交策略
- 每个 Zx 独立 commit，测试/ reviewer 通过后 push。
- Phase 1 完成后 PR 转 Ready 候选；Motion 共存单独 commit/说明，仍保持 Draft 直到真机矩阵通过。
- 所有回退路径必须有 `zslFallbackReason`，timing/debug 可复现。

---

## 7. 风险与回退

| 风险 | 处理 |
|---|---|
| 保留 `Image` 导致 Reader 槽位耗尽 | feature flag，异常自动 EAGER；默认不启用 |
| Motion 视频仍黑闪 | 保留硬互斥，仅开放单帧；HDR+ 回退 forward |
| 低光门禁过严导致 ZSL 不可用 | 门限可配置，debug 显示 `low_light`；先保守 |
| EV step 设备差异 | 用 `CONTROL_AE_COMPENSATION_STEP`，单测覆盖 |
| 焦段切换 ring 失效 | `evaluate` key 校验 + 立即 disarm，宁可 fallback 不出错 |
| AF 门禁导致命中率下降 | 手动 AF 豁免；debug 统计 `no_stable_run` |

**最终原则**：任何情况下宁可行前向 HDR+，也不出错误帧、错误曝光或重复文件。
