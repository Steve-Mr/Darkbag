# ZSL 运动自适应降帧、架构内聚度审查与设置联动分析报告

> **范围说明**：本报告基于当前工作区代码静态审计编写，**未对任何源代码或布局文件做实现修改**。所有现状描述均对应当前仓库源码及行号；改进方案均为后续实施前的架构设计提案。

---

## 目录

1. [议题一：场景快速变化 / 运动监测与 ZSL HDR+ 自适应降帧及单帧回退可行性](#1-议题一场景快速变化--运动监测与-zsl-hdr-自适应降帧及单帧回退可行性)
2. [议题二：当前 ZSL 改动的高内聚 / 低耦合与防超级类规范审计](#2-议题二当前-zsl-改动的高内聚--低耦合与防超级类规范审计)
3. [议题三：Experimental 设置中的 ZSL 聚合与 ZSL ↔ HDR+ 联动矩阵](#3-议题三experimental-设置中的-zsl-聚合与-zsl--hdr-联动矩阵)
4. [综合演进路线图（建议实施顺序）](#4-综合演进路线图建议实施顺序)

---

## 1. 议题一：场景快速变化 / 运动监测与 ZSL HDR+ 自适应降帧及单帧回退可行性

### 1.1 现状根因：为什么现有 ZSL 对“快速运动场景”盲视？

当前 ZSL 多帧选帧门禁位于 [ZslFrameSelector.kt](file:///home/maary/.gemini/antigravity/worktrees/Darkbag/review_zsl_implementation/app/src/main/java/top/maary/darkbag/camera/zsl/ZslFrameSelector.kt#L72-L96)，`stableRunIndices()` 与 `isStable()` 仅检查以下参数一致性：

- `hasResult == true` 且 `afFocused == true`（[ZslRawRing.kt](file:///home/maary/.gemini/antigravity/worktrees/Darkbag/review_zsl_implementation/app/src/main/java/top/maary/darkbag/camera/zsl/ZslRawRing.kt#L54-L65)）
- `iso` 波动不超过 `EXPOSURE_TOLERANCE`（5%）
- `exposureTimeNs` 波动不超过 `EXPOSURE_TOLERANCE`（5%）
- `cropKey` 一致
- `wb`（`COLOR_CORRECTION_GAINS` 四分量）波动不超过 `WB_TOLERANCE`（5%）

在**光线平稳的白天或室内场景**中，前景主体快速移动（如快速挥手、走动行人）或相机快速平移构图时，曝光、白平衡、对焦状态往往不会跳变。因此 `stableRun()` 依然判定整个预卷窗口“完全稳定”，照常触发满帧数（5~8 帧）的 HDR+ 多帧合成。

同时，[HdrPlusStreamingBurst.kt](file:///home/maary/.gemini/antigravity/worktrees/Darkbag/review_zsl_implementation/app/src/main/java/top/maary/darkbag/processor/HdrPlusStreamingBurst.kt#L103-L119) 在 ZSL 注入种子时设置了 `lockFirstFrameAsReference = true`，绕过了 `ColorProcessor.nativeComputeGcamFrameScore()`（直接赋 `Float.MAX_VALUE`）。虽然 Stage 5 已在 `merge.cpp`、`hdrplus_pipeline_generator.cpp` 与 Sabre 中加入 2×2 Quad 残差门控与双线性光流，显著消除了 32×32 方块撕裂重影，但面对**超出搜索半径的剧烈运动**或**大幅前景突变**时：
1. 强行做 5~8 帧配准与对齐属于无效算力浪费（增加发热与 `HdrPlusRequestManager` 队列排队时间）；
2. 若被选为基帧的第一帧自身带有运动模糊，或其余帧大部分像素被 `quad_gate` 剔除，多帧降噪增益几乎归零，甚至产生局部清晰度/噪点断层；
3. 在高光/常规光照下，直接减少参与合成的帧数或回退至单帧往往能获得更干净、更锐利的定格效果。

---

### 1.2 三类运动监测信号源的可行性对比

| 信号源 | 能否检测“相机静止 + 前景高速运动” | 能否检测“相机自身甩动” | 硬件/管线覆盖现状 | 计算耗时 & 内存开销 | 综合可行性 |
| :--- | :---: | :---: | :--- | :--- | :--- |
| **A. Camera2 `TotalCaptureResult`（OIS/AF/AE/Face）** | 仅人脸场景有限感知 | 需厂商上报 `STATISTICS_OIS_SAMPLES`（碎片化严重） | 当前 [CameraHardwareStreamController.kt](file:///home/maary/.gemini/antigravity/worktrees/Darkbag/review_zsl_implementation/app/src/main/java/top/maary/darkbag/camera/session/CameraHardwareStreamController.kt#L223-L242) 未采集 OIS 样本 | 极低 | **不适合作为主力**（大部分设备 OIS 元数据缺省，且无法感知无曝光变化的局部前景运动） |
| **B. `SensorManager` 陀螺仪（`TYPE_GYROSCOPE`）** | 无法感知 | **极准**（100~200Hz 角速度滑动积分） | 当前仅 [CameraShutterController.kt](file:///home/maary/.gemini/antigravity/worktrees/Darkbag/review_zsl_implementation/app/src/main/java/top/maary/darkbag/camera/delegate/CameraShutterController.kt#L29) 使用 `OrientationEventListener`，尚未接入陀螺仪 | 极低（后台监听几十微瓦，零图像拷贝） | **适合作为辅助快速甩动判定**，但不能单独解决前景快速移动 |
| **C1. 复用现有低分辨率 `analysisImageReader`（YUV_420_888）计算帧间块变化** | **完全支持** | **完全支持** | 已有基础设施！[CameraHardwareStreamController.kt](file:///home/maary/.gemini/antigravity/worktrees/Darkbag/review_zsl_implementation/app/src/main/java/top/maary/darkbag/camera/session/CameraHardwareStreamController.kt#L369-L408) 已创建最小 4:3 YUV 流，目前仅采样算 `lastClippingRatio` | **< 0.1 ms / 帧**（抽样为 16×12 网格仅 192 个数值，零堆分配） | **主力方案（推荐）**：同时兼顾 ZSL 与前向非 ZSL 连拍，且 `LAZY_IMAGE` 下不破坏零拷贝 |
| **C2. `ZslRawRing` 快门瞬间稀疏 Bayer Green 网格比对** | **完全支持** | **完全支持** | 仅 ZSL `RAW_RING` 可用；[ZslRawRing.kt](file:///home/maary/.gemini/antigravity/worktrees/Darkbag/review_zsl_implementation/app/src/main/java/top/maary/darkbag/camera/zsl/ZslRawRing.kt) 已有最近 3~8 帧缓存 | 仅在按下快门时算 1 次，约 **0.2~0.5 ms**（平时 0 开销） | **零平时开销补充方案**：在 `EAGER_COPY` 下甚至不依赖 `analysisImageReader` |

> **关键发现（已有资产可复用）**：
> 1. `CameraHardwareStreamController.kt` Lines 205–207 当前仅在 `(isManualExposure || env.isHdrPlusEnabled) && isMainLens` 时将 `analysisImageReader.surface` 加入 Repeating Request。若将其放宽为“所有支持单摄的镜头 + ZSL 或 HDR+ 启用时”（且仅提取 Y 平面 16×12 块均值），即可在不增加任何新 `ImageReader` 硬件流的前提下获得全镜头场景运动监测能力。
> 2. 现有 `ColorProcessor.nativeComputeGcamFrameScore()`（[HdrPlusJNI.cpp](file:///home/maary/.gemini/antigravity/worktrees/Darkbag/review_zsl_implementation/app/src/main/cpp/HdrPlusJNI.cpp#L1373)）评估的是**单帧锐度/噪声比**，不是帧间位移，但可用于在“回退单帧”时从最近 2 帧中挑出更清晰的一帧（前提是非 `LAZY_IMAGE` 全拷贝，或结合近邻帧选择）。

---

### 1.3 可落地的算法设计：归一化分块网格运动评估（Block-Grid Motion Metric）

之所以不能简单做“整帧全局平均亮度差”（手在局部快速挥动时整帧均值可能只变 1%~2%），而应做**空间分块（例如 16×12 = 192 个区块）+ 局部异常占比统计**：

1. **网格特征提取（每帧仅 ~192 字节/浮点，复用固定数组零 GC）**：
   - 将分析图（YUV 的 Y 平面，或 Bayer 帧间隔采样 Green 像素）划分为 `16 × 12` 网格；每个格子稀疏采样 `4 × 4` 个点求均值，得到特征向量 $M_t[0..191]$ 与整帧均值 $\mu_t$。
2. **亮度对齐残差（消除 ±5% AE 微波动干扰）**：
   - 对相邻帧（或待选帧 $t_i$ 与基准帧 $t_0$），计算全局亮度比例 $s = \mu_0 / \max(\mu_i, \epsilon)$。
   - 计算每个区块相对残差：
     $$d_k = \frac{|M_0[k] - s \cdot M_i[k]|}{\max(M_0[k], \sigma_{\text{floor}})}$$
     其中 $\sigma_{\text{floor}}$ 为低光防抖下限（YUV 8-bit 约 `8.0 ~ 12.0`），避免暗部噪声误触发运动警报。
3. **双指标判定（同时捕获局部前景高速运动与全局剧烈晃动）**：
   - **全局平均变化 `globalDelta`** = $\text{mean}(d_k)$：捕捉整体平移/摇镜/切场景；
   - **高变区块占比 `movingAreaRatio`** = $\text{count}(d_k > \tau_{\text{block}}) / 192$：捕捉**机位固定、手在画面 10%~40% 区域快速挥动**的典型痛点！
   - 对当前帧率/时间间隔 $\Delta t = t_0 - t_i$ 归一化后，可得到平滑后的**场景运动等级** `MotionLevel`：`STILL` / `MODERATE` / `FAST`。

---

### 1.4 在 ZSL HDR+ 下的分级回退策略与画质权衡（关键边界）

直接把快速运动全都无条件砍成单张是有风险的，必须结合**当前亮度（ISO/快门）**与 **ZSL 预览 EV 策略**一起联动：

1. **四档阶梯决策矩阵（仅在自适应策略允许时执行）**：
   - **`MotionLevel.STILL`（静止/慢速）**：维持满配目标帧数 `min(requestedBurst, ring.depth)`（例如 5~8 帧），获取最强动态范围与降噪。
   - **`MotionLevel.MODERATE`（中速局部变化 / 手持微晃）**：**收敛连拍时间窗至最短 3 帧（`MIN_HDR_FRAMES = 3`）**。
     - *原因*：在 30fps 下，5~8 帧跨越 `166~266 ms`，而只取紧邻 `t0` 的最新 3 帧仅跨越 **`~66 ms`**，帧间位移直接缩减 60% 以上！同时仍满足 HDR+ 管线最低 3 帧门槛（[ZslModels.kt](file:///home/maary/.gemini/antigravity/worktrees/Darkbag/review_zsl_implementation/app/src/main/java/top/maary/darkbag/camera/zsl/ZslModels.kt#L90)），保持多帧融合高光保护与低噪点。
   - **`MotionLevel.FAST` + 非中暗光（例如 ISO < 50% `isoUpper` 且快门未接近上限）**：**降级为单帧抓拍（`motion_single_fallback`）**，走 `captureFrameFromRing`（`SINGLE_RAW` 管线 + 携带当前 `hdrDigitalGain` 补偿）。
     - *原因*：高信噪比亮光环境下，单帧提取 1 stop 欠曝的亮度损耗轻微，完全避免多帧重影或拖慢排队，快门瞬间定格动作。
   - **`MotionLevel.FAST` + 中暗光（高 ISO 且 ZSL 当前运行在 `-1 EV` 欠曝预卷）**：**锁定降帧至 3 帧 HDR+（而非退单帧）**，或提示回退前向连拍（若需要正曝光单帧）。
     - *核心物理权衡（不可忽略）*：ZSL HDR+ 默认处于 `-1 EV` 预卷并依靠 `hdrDigitalGain = 2.0f` 拉亮，若在中暗光下退回单帧，`+1 EV` 增益会把单帧暗部噪声放大 1 档；而 3 帧（仅 66ms）配合 Stage 5 的 `2×2 quad_gate` 仍可抑制单帧热噪并把运动块自动丢弃退回参考帧。

2. **对非 ZSL（关闭 ZSL）前向 HDR+ 的同步收益**：
   - 如果运动监测放在通用流（方案 C1：`SceneMotionMonitor` 挂载在 `analysisImageReader` 上），则**关闭 ZSL 时前向 HDR+** 在快门按下瞬间也可读取当前场景的 `MotionLevel`，在 `FAST` 场景下自动把前向连拍目标帧数由 5/8 缩减为 3 帧（或在单帧允许模式下给出可选回退），直接呼应“关闭或打开 ZSL 均有快速运动重影风险”的实际体验。

---

## 2. 议题二：当前 ZSL 改动的高内聚 / 低耦合与防超级类规范审计

依据仓库 [AGENTS.md](file:///home/maary/.gemini/antigravity/worktrees/Darkbag/review_zsl_implementation/AGENTS.md)（五大架构红线 Invariants 1–5 与 `<code_quality>`），对当前所有 ZSL 相关文件进行静态度量与依赖关系审计。

### 2.1 代码规模与职责分布观测

| 文件 | 行数 | 架构评价 | 核心观察 |
| :--- | :---: | :--- | :--- |
| [ZslModels.kt](file:///home/maary/.gemini/antigravity/worktrees/Darkbag/review_zsl_implementation/app/src/main/java/top/maary/darkbag/camera/zsl/ZslModels.kt) | 242 | **优秀** | 纯 Kotlin 领域模型与策略（`ZslCapabilityEvaluator`, `ZslAdaptivePolicy`, `ZslRingConfig`），零 Android 依赖，完全可 JVM 单测 |
| [ZslFrameSelector.kt](file:///home/maary/.gemini/antigravity/worktrees/Darkbag/review_zsl_implementation/app/src/main/java/top/maary/darkbag/camera/zsl/ZslFrameSelector.kt) | 97 | **优秀** | 纯算法选帧（`latestValidIndex`, `nearestIndex`, `stableRunIndices`, `isStable`），无缓冲/Camera2 状态污染 |
| [RawFrameRouter.kt](file:///home/maary/.gemini/antigravity/worktrees/Darkbag/review_zsl_implementation/app/src/main/java/top/maary/darkbag/camera/zsl/RawFrameRouter.kt) | 280 | **良好** | 单点独占 `ImageReader.OnImageAvailableListener`，通过 `IDLE`/`RING`/`CAPTURE` 状态机防止偷帧 |
| [ZslDebugState.kt](file:///home/maary/.gemini/antigravity/worktrees/Darkbag/review_zsl_implementation/app/src/main/java/top/maary/darkbag/camera/zsl/ZslDebugState.kt) | 114 | **优秀** | 诊断快照与格式化逻辑分离，纯 Kotlin 可测 |
| [ZslRawRing.kt](file:///home/maary/.gemini/antigravity/worktrees/Darkbag/review_zsl_implementation/app/src/main/java/top/maary/darkbag/camera/zsl/ZslRawRing.kt) | 419 | **中等偏紧** | 兼管环形队列同步、Eager/Lazy 内存拷贝、`TotalCaptureResult` 延迟绑定与 `copyPlane` JNI 调用；整体仍内聚于“Ring 容器”边界，但已接近单类上限 |
| [ZslCaptureController.kt](file:///home/maary/.gemini/antigravity/worktrees/Darkbag/review_zsl_implementation/app/src/main/java/top/maary/darkbag/camera/zsl/ZslCaptureController.kt) | **848** | **⚠️ 超标（God Controller 趋势）** | **显著违反单一职责与防超级类规范**（详见 2.2 节） |
| [CameraHardwareStreamController.kt](file:///home/maary/.gemini/antigravity/worktrees/Darkbag/review_zsl_implementation/app/src/main/java/top/maary/darkbag/camera/session/CameraHardwareStreamController.kt) | **493** | **⚠️ 偏重** | 同时管理 Camera2 设备、CaptureSession、ZSL repeating 参数、YUV 高光直方分析，并在 `handleCaptureResult()` 中直接拼装 Pro 信息栏 UI |
| [SettingsFragment.kt](file:///home/maary/.gemini/antigravity/worktrees/Darkbag/review_zsl_implementation/app/src/main/java/top/maary/darkbag/fragments/SettingsFragment.kt) | **777** | **⚠️ 过重** | 比 `CameraFragment`（395 行）多出近一倍；且内含 ZSL 能力检查业务与与 Delegate 完全重复的状态格式化代码 |
| [SettingsBentoDelegate.kt](file:///home/maary/.gemini/antigravity/worktrees/Darkbag/review_zsl_implementation/app/src/main/java/top/maary/darkbag/fragments/settings/SettingsBentoDelegate.kt) | **525** | **⚠️ 偏重** | 与 `SettingsFragment` 重复实现 `zslStatusValue`，且硬编码了容量数字 |

---

### 2.2 明确的规范符合度与坏味道清单

#### ✅ 符合红线（Invariants 1–4）的部分
1. **Invariant 1（Pipeline-Agnostic Output）**：ZSL 路径严格通过传入 `CaptureSink` 执行输出，未接触 `MediaStore` / `ContentResolver` / `DngCreator`。
2. **Invariant 2（Domain Parameter Orthogonalization）**：单张 ZSL 仅生成 `RawImageHolder` 交给 `CameraCapturePipelineDispatcher.processImageAsync()` 组装 `CaptureTaskSpec`；连拍 ZSL 仅生成轻量 `ZslBurstSeed` 交给 `HdrPlusBurstCaptureExecutor`。
3. **Invariant 3（Shooting Mode Strategy Isolation）**：`CameraFragment` 无新增任何模式分支；模式互斥逻辑位于协调层之下。
4. **Invariant 4（Headless Camera Session & Zero UI Blocking）**：ZSL 全部环维护、路由切换、选帧和发拍操作全部序列化在 `camera2Handler` 与 `ZslRawThread` 上，未阻塞 UI 线程。

#### ❌ 违反高内聚 / 低耦合 / 避免超级类与避免重复规范的问题

1. **`ZslCaptureController`（848 行）承担了 7 种不相干职责，已滑向“ZSL 上帝类”**：
   - **① 生命周期与偏好监听**：`start()`/`stop()` 管理 `env.lifecycleScope` 协程与 `ZslPreferenceSnapshot`；
   - **② 运行时策略准入**：`evaluate()` 里杂糅了模式互斥、`PowerManager` 热节流、`limitByMemory()`、`ZslCapabilityDetector` 调用、`lutProcessor` 空检；
   - **③ Camera2 硬件配置驱动**：直接调用 `stream.setZslPreviewProfile(...)` 与 `stream.setZslRawTargetEnabled(...)`；
   - **④ 跨模块缓冲池内存配额篡改**：直接在 `evaluate`/`disarm`/`attemptRearm`/`trimListener` 中硬编码修改全局静态池 `StreamingBufferPool.setRetentionLimit(0/12)` 与 `HdrPlusBurst.setPoolLimit(0/3)`；
   - **⑤ 探测与重 Arm 状态机**：`runArmProbe()`, `scheduleRearm()`, `attemptRearm()`；
   - **⑥ Capture 执行编排与 UI 副作用越权**：`captureFrameFromRing()`（[ZslCaptureController.kt#L270-L326](file:///home/maary/.gemini/antigravity/worktrees/Darkbag/review_zsl_implementation/app/src/main/java/top/maary/darkbag/camera/zsl/ZslCaptureController.kt#L270-L326)）亲自构建 `RawImageHolder`、亲自维护 `HdrPlusRequestManager.onTaskStarted()` / `onTaskFinished()` 计数、亲自在失败时调用 `HdrPlusBurst.releaseBuffer()`、并直接切 `Dispatchers.Main` 调用 `ui.resetBurstUi()` / `ui.showProcessingAnimation()`；
   - **⑦ Debug 注册表发布**：`publishDebug()`, `noteCapture()`。
   - *风险*：如果直接在该类上继续追加“运动监测状态订阅、分级策略、与设置的深入联动”，文件体积将迅速膨胀超过 1,100 行，测试只能依赖复杂 Mock。

2. **`CameraModeController` ↔ `ZslCaptureController` 存在隐式共享可变属性耦合**：
   - 查看 [CameraModeController.kt#L343-L377](file:///home/maary/.gemini/antigravity/worktrees/Darkbag/review_zsl_implementation/app/src/main/java/top/maary/darkbag/camera/delegate/CameraModeController.kt#L343-L377)：调用 `parts.zsl?.tryCaptureHdrPlus(...)` / `tryCaptureSingle(...)` 返回 `Boolean` 后，紧接着通过外露的 `@Volatile` 字段 `zsl.lastSelectedSensorTimestampNs` 与 `zsl.lastTimestampRealtime` 读取本次选中的时间戳再喂给 `parts.motionPhoto.updatePendingStillTimestamp(...)`。
   - 这属于典型的**时间耦合（Temporal Coupling）与隐式副作用传递**：调用方必须知道“返回 true 之后立刻读这两个字段”，若未来发生并发或异步调度很容易读错批次。理想方式应由抓拍尝试直接返回密封结果对象（或通过回调函数注入选定时间戳）。

3. **`SettingsBentoDelegate` 与 `SettingsFragment` 存在直接的代码重复（违反 `<code_quality>`）**：
   - `zslStatusValue(state)` 在 [SettingsBentoDelegate.kt#L358-L380](file:///home/maary/.gemini/antigravity/worktrees/Darkbag/review_zsl_implementation/app/src/main/java/top/maary/darkbag/fragments/settings/SettingsBentoDelegate.kt#L358-L380) 和 [SettingsFragment.kt#L560-L581](file:///home/maary/.gemini/antigravity/worktrees/Darkbag/review_zsl_implementation/app/src/main/java/top/maary/darkbag/fragments/SettingsFragment.kt#L560-L581) 中**逐行重复了两次**。
   - 且两处都写死了魔数 `minOf(state.hdrBurstCount, 5)` 与 `3`，并在 `SettingsFragment` 中额外维护了一套独立的 `updateZslStatus()`（而 `SettingsBentoDelegate.renderPreferences()` 又会覆盖渲染一次）。

4. **多处重复解析 HDR+ EV 与争抢 Preview Gain 所有权**：
   - **EV 策略分散**：`resolveHdrTargetEv`（[ZslCaptureController.kt#L827-L831](file:///home/maary/.gemini/antigravity/worktrees/Darkbag/review_zsl_implementation/app/src/main/java/top/maary/darkbag/camera/zsl/ZslCaptureController.kt#L827-L831)）、`ExposureUtils.calculateHdrPlusExposure`、`CameraLutController` 各自解析 `"Off"` / `"-1 EV"` / `"-2 EV"` / `"Dynamic"` 字符串。
   - **Preview Gain 双头管理**：`env.sessionArbiter.lutProcessor?.setPreviewGain(...)` 同时被 `ZslCaptureController`（Arm/Disarm）和 `HdrPlusBurstCaptureExecutor`（前向连拍期间）直接读写，缺乏统一的 Gain 仲裁出口；若 ZSL 正在 `disarm("forward_capture")` 与前向 Burst 交替，仅靠消息队列执行顺序保证不被 `1.0f` 误重置。

### 2.3 建议的模块化拆解方案（收敛原则）

建议在后续阶段将 `ZslCaptureController`（848 行）沿边界解耦为 4 个内聚单元（每个 ≤ 220 行）：

1. **`ZslArmingPolicy`（纯 Kotlin，纳入 `ZslModels` 同级）**：合并能力报告、热节流、内存预算、运行模式互斥（Normal / 60fps / Manual / Flash / MultiCam）及运动等级阈值，供控制器和设置 UI **共同消费**，消除设置页与运行时的规则分叉。
2. **`ZslRingSupervisor`**：专职管理 `ZslRawRing` 生命周期、Arm Probe（2 秒帧率检测）、`scheduleRearm` 重试与 `RawFrameRouter` 挂载/卸载，以及对外汇报缓冲池目标保留上限。
3. **`ZslCaptureSelector` / `ZslCaptureFacade`（精简入口）**：只做快门命中判定，输出强类型结果（如 `ZslDispatchOutcome.Consumed(sensorTimestampNs, realtimeTimestamp)` vs `Fallback(reason)`），将 `captureFrameFromRing` 里组装 `RawImageHolder`、管理 `HdrPlusRequestManager` 与调度 `ui` 反馈的代码移回 `SingleRawCaptureExecutor`（实现与 `HdrPlusBurstCaptureExecutor` 对称的“Selector 只产 Seed，Executor 负责管线入队与生命周期记账”）。
4. **`SettingsFragment` 瘦身**：移除重复的 `zslStatusValue()` 与 `updateZslStatus()`，ZSL 状态卡完全由 `SettingsViewModel` 状态流驱动 `SettingsBentoDelegate` 单源渲染。

---

## 3. 议题三：Experimental 设置中的 ZSL 聚合与 ZSL ↔ HDR+ 联动矩阵

### 3.1 当前 `fragment_settings.xml` 中 Exp 区域的视觉与结构问题

查看 [fragment_settings.xml#L1586-L1815](file:///home/maary/.gemini/antigravity/worktrees/Darkbag/review_zsl_implementation/app/src/main/res/layout/fragment_settings.xml#L1586-L1815)，实验性实验室（`section_debug`）当前把所有控件平铺在同一个一级 `BentoTrayCard` 中：

1. `switch_force_60fps`（强制 60fps）
2. `switch_zsl`（零快门延迟主开关）
3. `card_zsl_status`（`ZSL ROUTE` 两行状态卡片）
4. `switch_zsl_lazy_ring`（零拷贝 RAW 环）
5. `switch_zsl_adaptive`（自适应抓拍）
6. **`btn_open_lens_calibration`（自定义镜头畸变标定 — 与 ZSL 无关的按钮强行插在中间！）**
7. 综合诊断卡（上半部分为通用 `tv_debug_stats`，下半部分为 `tv_zsl_debug`）
8. 复制日志 / 清空日志 / 退出实验室按钮

**违反 Bento Matrix 设计结构的问题**：
- 同文件的“高级设置（Advanced Settings）”为每一个功能域（`Compute Pipeline`、`Video Capture`、`Multi-Camera`、`DNG & Storage`、`Viewfinder UI`）都包裹了独立的**嵌套同心 `BentoTrayCard`（`cardCornerRadius="18dp"`，内嵌标题行与 Porcelain 白瓷瓦片）**；
- 而实验性实验室内的 **ZSL 设置与状态没有形成同心子托盘**：主开关、状态卡、子开关散落在外层 `errorContainer` 中，`tv_zsl_debug` 又被 `btn_open_lens_calibration` 和 `tv_debug_stats` 割裂到下方；
- 另外在 `section_debug` 中还残存少量硬编码文案（如 `fragment_settings.xml:1715` 的 `"自定义镜头畸变标定"` 及 `SettingsFragment.kt:453-463` 的日志操作英文 Toast），可在重组时按 Invariant 5 一并完成单语言资源化。

**建议重组结构（Exp 实验室内部子托盘化）**：
- 将 `section_debug` 内部拆成清晰的 3 个同心嵌套模块（与 Advanced 风格完全统一）：
  - **子托盘 1：Zero Shutter Lag（`card_tray_exp_zsl`，`cardCornerRadius="18dp"`，可随“生效/互斥/关闭”动态切换容器语义角色）**
    - 托盘小标题行：`ZERO SHUTTER LAG` + 实时状态胶囊 Badge（例如 `ARMED` / `MUTUAL EXCL.` / `OFF`）
    - Row 1：`switch_zsl`（主开关） + `card_zsl_status`（改为紧凑双行联动微遥测，同时展示路线/帧数与 HDR+ 联动约束）
    - Row 2（`zslEnabled` 展开区）：`switch_zsl_adaptive` + `switch_zsl_lazy_ring`（以及未来若引入运动监测，可在此聚合 `ZSL 运动降帧策略`）
    - Row 3（`zslEnabled` 展开区）：专属的 `tv_zsl_debug` 诊断白瓷卡（独立于通用 `tv_debug_stats`，彻底收拢在 ZSL 托盘底部）
  - **子托盘 2：实验性硬件与标定（`switch_force_60fps` + `btn_open_lens_calibration`）**
  - **子托盘 3：性能诊断日志（`tv_debug_stats` + Copy / Clear Logs）**

---

### 3.2 ZSL 与 HDR+ 及关联设置的全面联动盲区（对照源码逐项梳理）

当前系统把 **HDR+ 计算摄影参数**放在“高级设置 → Group A (`Compute & Merge`)”及取景器 Bento Deck，把 **ZSL 开关与策略**放在“实验性实验室（`section_debug`）”，两者在静态与动态联动上存在 **8 处显著断层**：

| 序号 | 联动关系 | 当前实际代码行为与缺陷 | 建议改进联动逻辑 |
| :---: | :--- | :--- | :--- |
| **1** | **`zslEnabled` ↔ `hdrPlusEnabled`（能力与状态不一致）** | 在设置页打开 `switch_zsl` 时，仅当 `hdrPlusEnabled == true && !supportsHdrPlusZsl` 才 Toast 阻止（[SettingsFragment.kt#L482-L489](file:///home/maary/.gemini/antigravity/worktrees/Darkbag/review_zsl_implementation/app/src/main/java/top/maary/darkbag/fragments/SettingsFragment.kt#L482-L489)）。若用户先关掉 HDR+ 成功打开 ZSL（走 `SINGLE_RAW`），再回到取景器打开 HDR+，ZSL 会在运行时静默 `disarm("hdr_unsupported")`，且设置页若事先把 `hdrPlusEnabled` 打开又关掉，状态提示不会清晰区分“硬件支持单帧 ZSL 但不支持 HDR+ ZSL”。 | 由持久化能力模型明确区分 `singleZslSupported`、`hdrPlusZslSupported`、`maxPreRollFrames`。当用户设备仅支持 Single ZSL 时，允许“单帧模式下启用 ZSL + HDR+ 自动走前向”，并在 ZSL 状态卡中明确提示当前是“仅单帧可用”还是“双模式就绪”。 |
| **2** | **`hdrBurstCount`（3~8 帧）↔ ZSL 环形深度与严格模式死锁** | ① [SettingsBentoDelegate.kt#L370](file:///home/maary/.gemini/antigravity/worktrees/Darkbag/review_zsl_implementation/app/src/main/java/top/maary/darkbag/fragments/settings/SettingsBentoDelegate.kt#L370) 与 [SettingsFragment.kt#L571](file:///home/maary/.gemini/antigravity/worktrees/Darkbag/review_zsl_implementation/app/src/main/java/top/maary/darkbag/fragments/SettingsFragment.kt#L571) 硬编码了 `minOf(state.hdrBurstCount, 5)`，而实际环深度是 `minOf(hdrBurstCount, 8, maxByBudget(frameBytes))`（[ZslModels.kt#L154](file:///home/maary/.gemini/antigravity/worktrees/Darkbag/review_zsl_implementation/app/src/main/java/top/maary/darkbag/camera/zsl/ZslModels.kt#L154)）。<br>② **隐性死锁**：当大底 RAW 使 `maxPreRollFrames = 5`，而用户在 HDR+ 设置里选 `hdrBurstCount = 8` 且关闭 `zslAdaptive = false`（严格模式）时，[ZslCaptureController.kt#L428-L432](file:///home/maary/.gemini/antigravity/worktrees/Darkbag/review_zsl_implementation/app/src/main/java/top/maary/darkbag/camera/zsl/ZslCaptureController.kt#L428-L432) 永远 `pre_roll_short`，**ZSL HDR+ 实际上 100% 不可用**，但界面无任何提醒！ | ① 将动态检测出的真实 `maxPreRollFrames` 写入 `DarkbagPreferences`（或 `ZslCapabilityState`），消除 UI 硬编码 `5`；<br>② 当 `zslEnabled && hdrPlusEnabled && !zslAdaptive && hdrBurstCount > maxPreRollFrames` 时，在 ZSL 托盘内显式提示“连拍帧数超出 ZSL 环上限（需开启自适应或调至 ≤N 帧）”，并在高级设置 `BURST FRAMES` 辅助摘要/Sheet 中标注 ZSL 环上限。 |
| **3** | **`zslAdaptive` ↔ HDR+ OFF（单帧模式）无上下文指示** | `switch_zsl_adaptive` 在 `Profile.HDR_PLUS` 时控制“允许少于 `hdrBurstCount` 的预卷（3..depth）以及快速场景/无稳定序列时回退 `instant_single`”，但在 `hdrPlusEnabled == false` 时该开关完全不参与任何判断，界面却未标明其作用于 HDR+ ZSL。 | 在 ZSL 托盘内将 `switch_zsl_adaptive` 文案/Badge 明确关联 HDR+ 自适应（包含未来运动降帧），当 `hdrPlusEnabled == false` 时降调或加注 `HDR+ 激活时生效`。 |
| **4** | **`hdrUnderexposureMode` ↔ ZSL 预览亮度、Dynamic 失效与运动防糊收益** | [ZslCaptureController.kt#L827-L831](file:///home/maary/.gemini/antigravity/worktrees/Darkbag/review_zsl_implementation/app/src/main/java/top/maary/darkbag/camera/zsl/ZslCaptureController.kt#L827-L831) 中 `Dynamic` 与 `-1 EV` 同等映射为 `-1f` 固定负曝光（原因见注释：`clipping feedback is unreliable on an underexposed preview`），并通过 LUT 提亮 `+1 EV` 补偿预览。用户在设置页或取景器切换为 `Dynamic` / `-1 EV` / `-2 EV` / `Off` 时，并不知晓它会改变 ZSL 预卷的底层快门速度与取景器噪点。 | 在 ZSL 聚合托盘的状态条中同步展示当前生效的预卷 EV（例如 `HDR+ · 5/5 · -1.0 EV`）；在 `showUnderexposureSheet()` 的描述或提示中注明在 ZSL 模式下 `Dynamic` 等价于恒定 `-1 EV` 预卷，选择 `Off` 则可消除 ZSL 提亮噪点但降低高光保留。 |
| **5** | **`force60Fps` ↔ `zslEnabled`（实验区内部静默互斥）** | 两者都在 `section_debug`，但打开 `force60Fps` 时 `switch_zsl` 不受约束、子开关也依然保持可交互，只有状态卡写着泛泛的“Inactive · current mode”（甚至看不出是 60 FPS 导致的！）。 | 将“当前阻塞原因”具体化（如拆分出 `Inactive · 60 FPS`）；开启 `force60Fps` 时在 ZSL 托盘上显示明确互斥徽章并禁用 ZSL 子选项交互（保留主开关可见并提示冲突）。 |
| **6** | **`halfFrameMode` / `multiCameraModeEnabled` ↔ ZSL 状态卡虚假显示** | [ZslCaptureController.kt#L514-L522](file:///home/maary/.gemini/antigravity/worktrees/Darkbag/review_zsl_implementation/app/src/main/java/top/maary/darkbag/camera/zsl/ZslCaptureController.kt#L514-L522) 在 `mode != CaptureMode.NORMAL`（含半格）与 `isMultiCameraMode` 时强制 `disarm`。但 [SettingsBentoDelegate.kt#L358-L380](file:///home/maary/.gemini/antigravity/worktrees/Darkbag/review_zsl_implementation/app/src/main/java/top/maary/darkbag/fragments/settings/SettingsBentoDelegate.kt#L358-L380) **未判断 `halfFrameMode` 与 `multiCameraModeEnabled`**，导致用户在设置页打开半格或双摄时，ZSL 状态仍误报 `HDR+ pre-roll · 5/5`。 | 提取跨层共用的 `ZslActivationPolicy`，让设置页的状态展示与运行时的 `evaluate()` 使用完全同一套阻塞条件判定（`force60Fps`、`multiCameraModeEnabled`、`halfFrameMode` 等）。 |
| **7** | **`hdrPlusOis` ↔ ZSL 预卷 OIS 语义偏差** | [CameraHardwareStreamController.kt#L89-L95](file:///home/maary/.gemini/antigravity/worktrees/Darkbag/review_zsl_implementation/app/src/main/java/top/maary/darkbag/camera/session/CameraHardwareStreamController.kt#L89-L95) 中只有前向 `buildBurstRequests` 传入 `isHdrBurst = true` 并遵循 `hdrPlusOis`；Repeating Request 始终传 `false` 保持 OIS 开启。因此即使用户在高级设置里关闭了 `hdrPlusOis`（常见于脚架长曝或测试防抖对比），ZSL HDR+ 抓到的帧仍然自带 OIS。 | 一般不建议关停实时取景器 OIS（会导致取景剧烈抖动，不利于用户构图），但应在设置描述/文档中明示该开关仅作用于前向突发连拍（或在 ZSL 脚架模式显式同步）。 |
| **8** | **`burstProcessingStrategy` & `hdrFusionMode` ↔ ZSL 连按排队背压** | ZSL 下 T1+T2 极短，用户很容易在 1 秒内连按多次快门；在默认 `Balanced` 策略（`maxQueue = 3`）下配合耗时较长的 `Sabre` 模式 + 5~8 帧，极易触顶 `queue_full`（[CameraModeController.kt#L298](file:///home/maary/.gemini/antigravity/worktrees/Darkbag/review_zsl_implementation/app/src/main/java/top/maary/darkbag/camera/delegate/CameraModeController.kt#L298)）。且一旦某次拍照进入 ZSL，第 1 节中的运动降帧也能直接缓解此背压。 | 运动监测减帧（5→3→1）会自然降低排队时长；设置侧可在 ZSL 聚合卡的状态或提示中感知 HDR+ 融合模式/帧数联动变化，确保用户知道当前 ZSL 正在驱动哪种计算管线参数。 |

---

## 4. 综合演进路线图（建议实施顺序）

遵循仓库的阶段原子提交与先重构后扩展原则，建议按以下 4 个小阶段推进实施：

1. **阶段 A — 架构瘦身与状态同源（高内聚低耦合前置）**：
   - 将 `ZslCaptureController`（848 行）拆分为 `ZslArmingPolicy`、`ZslRingSupervisor` 与精简的 `ZslCaptureFacade`；将单帧 `RawImageHolder` 入队与 `HdrPlusRequestManager` 记账归还给 `SingleRawCaptureExecutor`；消除 `lastSelectedSensorTimestampNs` 隐式属性传递与 `PreviewGain` 双头写入。
   - 消除 `SettingsFragment` 与 `SettingsBentoDelegate` 之间重复的 `zslStatusValue`，并将 `halfFrameMode` / `multiCameraModeEnabled` / 动态 `maxPreRollFrames` 纳入共享的 `ZslActivationPolicy`。
2. **阶段 B — Experimental ZSL Bento 子托盘聚合与 HDR+ 联动升级**：
   - 重构 `fragment_settings.xml` 的 `section_debug`：把 `switch_zsl`、`card_zsl_status`、`switch_zsl_adaptive`、`switch_zsl_lazy_ring` 与 `tv_zsl_debug` 聚合成独立的同心嵌套 `BentoTrayCard`，移出打断布局的 `btn_open_lens_calibration`，并清理硬编码字符串。
   - 补齐 `hdrBurstCount > maxPreRollFrames && !zslAdaptive`、`force60Fps`、`hdrPlusEnabled`、`hdrUnderexposureMode`（EV 透出）及 `halfFrame/multiCam` 的联动表现与约束提示。
3. **阶段 C — 轻量级场景运动监测器（`SceneMotionMonitor`）**：
   - 复用并扩展 `analysisImageReader`（4:3 最小 YUV_420_888）的 Y 平面采样，新增低成本 `16×12` 亮度归一化分块残差计算（`globalDelta` + `movingAreaRatio`），输出连续 `MotionLevel`（`STILL` / `MODERATE` / `FAST`）并可选叠加陀螺仪角速度辅助。
   - 附带纯 JVM 单元测试验证局部手部快速挥动、全局平移、低光噪声防误触及 AE ±5% 波动鲁棒性。
4. **阶段 D — ZSL HDR+ 与前向 HDR+ 运动自适应降帧集成**：
   - 在 `ZslAdaptivePolicy` 与 `ZslCaptureController` / `ZslFrameSelector` 中接入 `MotionLevel` 与光照门禁联合决策：`MODERATE` 时收缩至最近 3 帧（~66ms 窗口）；`FAST` 且亮光时回退 `motion_single_fallback`（单帧直出 + Gain 补偿）；中暗光维持 3 帧 + Stage 5 `2×2 quad_gate` 去重影；在 `tv_zsl_debug` 中可观测回退/降帧原因。

---

*报告生成完毕。当前分支代码树保持原状未作任何源码改动。*
