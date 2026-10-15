# Darkbag Sabre 全焦段高保真多帧重构规范 (Immutable Specification)

**状态**：已批准 (Approved & Immutable)  
**基线分支**：`feature/sabre-full-fidelity-pipeline` (同步至 `origin/refactor/pipeline-mode-udf` @ `4ec9e9fe`)  
**相关论文**：Google Research / SIGGRAPH 2019: *"Handheld Multi-Frame Super-Resolution"* (Wronski et al.)

---

## 1. 核心定位与设计原则

本规范为 Darkbag 算法重构的**纲领性指导文件**。在后续开发周期中，目标与数学模型严格以此为准，不得任意漂移。

### 1.1 核心目标
将 Sabre 从原先仅用于高倍数裁切的数字变焦辅助算法，升级为**全焦段（包含 1x 广角）通用工业级多帧高保真图像重构引擎**：
1. **1x 焦段（广角物理全彩重构）**：利用手持天然微颤（Natural Hand Tremor）产生的亚像素相位分集，在传感器原始网格上聚齐该物理位置真实的 R、G、B 光子，实现**免空间插值的物理全彩重建（True Multi-Frame Demosaic）**，从根源上消除传统单帧去马赛克（如 RCD/AHD）在屏幕文字、摩尔纹等高频周期结构上的拉链伪彩与边缘虚化。
2. **变焦段（超分辨率重构）**：实现符合光学点扩散函数（PSF）逆约束的超分辨率放大（Super-Resolution Zoom）。
3. **屏幕文字与高频场景表现**：无论何种焦段，清晰度、微对比度与字符边缘锐度达到或超越单帧实拍水准，彻底消除毛刺、鬼影与虚化。

### 1.2 严格遵守四大架构红线 (Architectural Invariants)
1. **Invariant 1: CaptureSink 解耦与统一压缩 DNG**：
   - 算法层严禁感知输出介质，严禁使用系统 `DngCreator`。
   - 所有的 DNG 保存一律经过统一的 native `write_dng` 引擎（采用 16-bit Lossless JPEG 压缩，支持 OpcodeList2 GainMap 校准）。
   - 用户设置的 `KEY_RAW_OUTPUT_TYPE`（0=Bayer, 1=Linear）由现有 Sink 统一调度，Sabre 输出的 3 通道全彩数据在 Linear 模式下直接写出高质量 Linear DNG，在 Bayer 模式下经物理重排写出 Bayer DNG。
2. **Invariant 2: 参数正交化 (`CaptureTaskSpec`)**：
   - 严禁向 JNI 或 C++ 展开散落的原始参数，必须通过结构化领域模型传递。
3. **Invariant 3: 拍摄模式策略隔离 (`CaptureModeCoordinator`)**：
   - 严禁在 `CameraFragment` 内书写模式分流分支。
4. **Invariant 4: Headless Camera2 会话与零 UI 线程阻塞**：
   - 严禁在 UI 线程执行任何 GPU 读回或阻塞式计算。

---

## 2. 核心数学模型与算法规范 (100% Google SIGGRAPH 2019 对齐)

拒绝“简化版”与“启发式特判”，全流程基于连续物理与统计光学模型：

### 2.1 物理噪声驱动的严格去鬼影模型 (Noise-Gated Robust Deghosting)
* **根因修复**：彻底移除 `GpuSabreShader.h:285` 与 `SabreEngine.cpp:311` 中的硬底限 `w_motion = max(0.08, w_motion)`。对于运动错位帧，权重必须能严格降至 0.0，杜绝重影残留。
* **物理噪声阈值**：采用 Camera2 物理噪声模型 $\sigma^2(y) = S \cdot y + O$（$S$ 为散粒噪声标度，$O$ 为读取噪声基底）：
  $$\text{Mahalanobis Distance } D_m(p) = \frac{|I_k(p + u) - I_{\text{ref}}(p)|}{\sqrt{S \cdot I_{\text{ref}}(p) + O} + \epsilon}$$
* **自适应权重函数**：
  $$w_{\text{motion}}(p) = \exp\left( - \frac{\max(0, D_m(p) - \tau_{\text{noise}})^2}{2 \sigma_{\text{motion}}^2} \right)$$
  当局部残差显著大于 $3\sigma$ 时，权重平滑且严格收敛至 0。

### 2.2 空间光流场正则化 (Spatial Flow Regularization)
* 针对屏幕周期网格、文字规律排列导致的局部光流孔径问题（Aperture Problem）：
* 在瓦片块匹配（Tile Alignment）后，引入基于局部方差的 2D 中值滤波（Median Filter）与双边平滑正则化，抑制离群运动矢量，防止光流场撕裂导致的字符残缺。

### 2.3 基于物理噪声阻尼的 MTF 频域逆滤波 / 边缘保真反卷积 (MTF Restoration)
* **问题本质**：Sabre 的各向异性高斯核回归（$\sigma_0 \approx 0.85$）在平滑噪声的同时等效于低通滤波，必须执行 MTF 逆补偿。
* **通用物理方案**：严禁采用硬编码的 ISO 分段判定（如 `if (iso > 800)`）。反卷积阻尼直接由传感器物理噪声参数 $(S, O)$ 连续控制：
  - 采用正则化反卷积（Wiener-like Deconvolution / Regularized High-Boost）：
    $$H_{\text{restore}}(f) = \frac{H^*(f)}{|H(f)|^2 + \gamma \cdot \frac{S \cdot y + O}{\sigma_{\text{signal}}^2}}$$
  - **低 ISO / 充足光照**：$S \cdot y + O$ 极小，阻尼 $\to 0$，执行完全 MTF 逆滤波，彻底还原高频字符与发丝细节；
  - **高 ISO / 暗光阴影**：$S \cdot y + O$ 自然增大，正则化阻尼自适应增强，自动抑制散粒噪点放大；
  - **高频去噪门限 (Noise Coring)**：空域高频差值 $\Delta y = y - y_{\text{blur}}$ 施加物理噪声门限：
    $$\Delta y_{\text{boost}} = \text{sign}(\Delta y) \cdot \max(0, |\Delta y| - k \sqrt{S \cdot y + O})$$
    使阴影底噪完全不被放大，而文字高对比度跳变（$\Delta y \gg 3\sigma$）获得最大清晰度增强。

### 2.4 1x 焦段核回归自适应与 RCD 先验平滑融合 (Multi-Frame Demosaic at 1x)
* 在 $1\text{x}$ 焦段（即无放大倍率）：
  - 各向异性核足迹调整为紧致核尺寸（$\sigma_0 \approx 0.70$），避免跨像素过度模糊；
  - 协方差引导张量强化边缘方向敏感度，沿文字边缘拉伸、垂直边缘收缩；
  - 引入单帧 RCD 引导先验作为正则化基底（通过动态权重混合）：当局部手抖位移采样充分时，Sabre 物理全彩主导；当局部采样不足时，RCD 先验平滑兜底，实现无缝连续过渡。

---

## 3. 运行环境与模式契约

### 3.1 模式契约 (Mode Strategy)
* **Auto 模式 (`fusionMode == 0`)**：
  - **完全不修改**。保持现有限制：变焦 $\ge 1.25\text{x}$ 启用 Sabre，低倍率启用 Spatial + RCD。
* **仅 Sabre 模式 (`fusionMode == 2`)**：
  - **全焦段生效**：从 1x 广角至最大变焦倍率，全部路由至 Sabre 引擎；
  - **稳健 Fallback**：若 GPU Sabre 初始化失败或 Compute Shader 抛错，安全降级至 Spatial + RCD 处理，绝不发生崩溃或坏图。
* **Auto 切换计划**：
  - 待本分支所有功能与测试 100% 验证通过后，仅需更改设置项默认值即可平滑过渡。

### 3.2 算力与执行架构
* **主执行路径**：100% 聚焦于 `GpuSabreShader.h` 与 `GpuSabreEngine.cpp`（OpenGL ES 3.1+ Compute Shader），目标端到端处理耗时 $\le 50\text{ms}$。
* **CPU Sabre (`SabreEngine.cpp`)**：
  - 作为桌面端/CI 测试桩与数学基准，**不参与手机端实际捕获路径**（手机端遇 GPU 异常直接走 RCD fallback）。
* **三脚架场景**：
  - 不做任何针对三脚架的特判逻辑。连续的数学模型在 $\Delta \approx 0$ 处天然退化为纯噪声平滑与 RCD 引导先验融合，无需分支判断。

---

## 4. 实施阶段与交付里程碑 (Phased Roadmap)

* **Phase 1: 严格物理去鬼影模型与光流场平滑** (修复硬底限 `0.08`，消除文字周围重影与撕裂)
* **Phase 2: 物理噪声阻尼驱动的 MTF 反卷积 Compute Shader** (还原文字极限解析力与微对比度)
* **Phase 3: 1x 焦段核自适应与单帧 RCD 先验融合** (实现 1x 免插值物理全彩解算)
* **Phase 4: 全焦段模式路由与健壮 Fallback 闭环** (支持仅 Sabre 模式全焦段运行与降级防护)
* **Phase 5: 全套单元测试、画质基准验收与提交** (零警告编译、100% 单元测试通过)

---

## 5. 团队协作与 Review 闭环规范

* **Lead Architect (Antigravity)**：整体规划与调度。
* **Math & GPU Compute Shader Specialist**：负责 GLSL Compute Shader 与 C++ 数学内核。
* **Pipeline & Integration Specialist**：负责 JNI 与会话管理。
* **Reviewer & Quality Gatekeeper**：独立审计四大红线、运行 `./gradlew testDebugUnitTest` 与 CMake 编译，不达标打回迭代。
* 每个 Phase 完成并验收后，提交独立规范的 Git Commit。
