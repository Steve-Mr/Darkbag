# Darkbag 颜色管线与画质正确性指导意见 (Color Pipeline & Image Quality Directive)

> **目标定位**：本文档作为后续色彩科学与画质重构专项 Subagent（或工程开发团队）的架构设计与实施基石（Foundation Document）。
> **核心宗旨**：紧扣计算摄影色彩科学、Adobe DNG 1.7 规范契约及 Android Camera2 硬件抽象，彻底根治偏色、欠曝与取景撕裂，建立严密、可预测的色彩转换流水线。

---

## 0. 核心色彩架构原则（Color Science Principles）

1. **单次变换契约（Single-Application Invariant）**：
   主干色彩流水线遵循 `Sensor RAW → Black/White Level → LSC → WB → CCM → XYZ(D65) → Gamut Mapping → Tone Mapping → OETF`。
   白平衡、色彩校正矩阵（CCM）、色调曲线及光电转换函数（OETF）在整个生成链路中有且仅有一次生效，杜绝多重白平衡或嵌套伽马。
2. **严守 DNG 1.7.0.0 规范契约（DNG Specification Compliance）**：
   - DNG 文件内部存储的必须是传感器线性响应数据（Scene-Referred Linear Data）。
   - 色彩元数据严格遵守标准方程：$CM1 \cdot \text{XYZ}_{\text{illuminant}} = \text{AsShotNeutral}$。
   - 曝光补偿通过 `BaselineExposure` 反映物理/数码增益基准，严禁对高增益进行随意硬截断。
3. **空间与语义严格隔离（Strict Space Isolation）**：
   - 场景线性空间（Scene-referred Linear）：包含白平衡相乘、CCM 矩阵变换与色彩降噪。
   - 显示映射空间（Display-referred）：包含色调压缩（Tone Mapping）与 OETF 编码。
   - 任何非线性压缩、色相平滑与去饱和算法必须在其声明适用的物理空间执行。
4. **单数据源与两端一致性（Viewfinder & Export Parity）**：
   取景器（GLSL 片元着色器）、离线 JPEG 导出（C++ ColorPipe）与 RAW DNG 必须共享统一的色彩模型与色域定义，杜绝同一预设下取景与成片效果割裂。

---

## 1. 完整问题清单与根因定位（Problem Inventory）

### C1-1. 高光溢出偏粉（Pink Highlights Artifact）
- **现象与代价**：在户外日间、白云、烈日等高反差大光比场景下，高光边界与过曝区域呈现刺眼的品红/粉色色晕。
- **根本原因**：
  1. **防护与增益顺序颠倒**：`ColorPipe.cpp` 将高光去饱和（Highlight Desaturation）判定放在了乘以 `digitalGain` **之前**，且阈值写死为绝对值 $0.8 \times 65535 = 52428$。
  2. **欠曝保护击穿去饱和判定**：`ExposureUtils` 为了保留高光，在高光比场景下施加了 2–3 EV 欠曝（像素值缩减 4–8×），进入 `ColorPipe` 的未增益 raw 值远低于 52428，**导致高光去饱和判定永不触发**。
  3. **色调曲线通道失衡**：随后施加的 4–8× 增益将信号推至高光区。白平衡系数中放大倍数最大的 R 通道（~2.0×）与 B 通道（~1.5×）先于 G 通道（1.0×）进入非线性压缩区。逐通道色调映射把 R 与 B 压得比 G 更狠，破坏了中性高光的通道比例，形成粉色偏色。

### C1-2. 非 RGGB 传感器暗部偏绿（Green Shadows Artifact）
- **现象与代价**：在非 RGGB 传感器机型（如 GRBG、GBRG、BGGR）上，暗光或大光比阴影区域噪点严重偏向纯绿。
- **根本原因**：
  1. **几何坐标与颜色通道错位**：Android Camera2 的 `BlackLevelPattern` 规范定义的是**几何坐标** `[(0,0), (1,0), (0,1), (1,1)]`，各位置的物理通道取决于 CFA。
  2. Halide 生成器的 `shift_bayer_to_rggb` 将像素平移为逻辑 RGGB（例如 GRBG 的像素被平移，使得左上角变为红像素）。但 `black_white_level` 依然将几何 `(0,0)` 处的黑电平当作红通道黑电平减去。
  3. **加性误差与增益放大**：黑电平是加性偏置，在暗部信号微弱时，扣除错误的黑电平直接导致基底失衡，经 8× 增益与白平衡放大后呈现纯绿。
  4. **历史修复丢失**：当年专门解决此问题的提交 `d0b5ec28` 存在于孤立分支，**从未并入主干 HEAD**，主线当前处于“缺失黑电平动态映射”状态。

### C1-3. LinearRaw DNG 默认渲染严重偏暗 1.5–2.5 EV
- **现象与代价**：同一张照片的 JPEG 亮度正常，但导出的 DNG 在 Lightroom 或系统相册中默认漆黑一片，严重欠曝。
- **根本原因**：
  1. HDR+ 拍摄依靠 2–8× 增益补偿物理欠曝。DNG 像素存储的是纯传感器线性数据，成片测光亮度完全依赖元数据 `BaselineExposure` 指令解码器进行增益对齐。
  2. `ColorPipe.cpp:1431` 写入了 `std::clamp(baselineExposure, 0.0f, 0.5f)`，将补偿上限死死截断在 0.5 EV（仅允许 1.41× 补偿）。当实际增益为 8×（3.0 EV）时，DNG 默认渲染亮度缺少了 2.5 EV 的测光增益。

### C1-4. ARRI LogC3 伪矩阵（色域原色严重偏差）
- **现象与代价**：选用 Arri LogC3 拍摄的素材在专业调色软件（如 DaVinci Resolve）套用官方 ARRI LUT 时产生严重偏色。
- **根本原因**：`ColorPipe.cpp:322-326` 中的 `M_XYZ_to_AlexaWideGamut_D65` 矩阵来源错误。求逆推导出的原色坐标为 $G(0.2210, 0.7600), B(0.1360, 0.0570)$，而官方 AWG3 标准原色为 $G(0.2210, \mathbf{0.8480}), B(\mathbf{0.0861}, \mathbf{-0.1020})$，矩阵元素误差高达 **0.3514**。

### C1-5. 5 种 Log 预设缺失与两端映射撕裂
- **现象与代价**：设置中提供的 Canon Log 2/3、N-Log、D-Log、Log3G10 录制/导出结果发灰或削波，且取景器显示的色彩与最终 JPEG 严重不符。
- **根本原因**：
  1. `ColorPipe.cpp:574` 的 `apply_log` 仅实现了类型 1–7，8–12 全部落入 `default: return srgb_oetf(x)`，且色域被强制指定为 Rec.709。
  2. 取景器端的 GLSL 着色器却将 N-Log/D-Log 分流到 Rec.2020 并套用 ACES fit。同一预设在两端的传递函数与色域定义完全脱节。

### C1-6. DNG 内嵌缩略图缺少色彩校正
- **现象与代价**：在手机系统相册或文件管理器中浏览 DNG 缩略图时，色彩寡淡、暗部发灰，而导入 Lightroom 显影后又恢复正常。
- **根本原因**：`ColorPipe.cpp:1317` 的 `make_preview_rgb8` 生成缩略图时，仅对 Sensor Raw 施加了白平衡与 $pow(1/2.2)$ 幂函数，彻底跳过了相机 CCM 矩阵校准与色调映射，违背了 DNG 1.7 规范关于彩色预览默认符合 sRGB 的要求。

### C1-7. 色度降噪置于未白平衡域引入的暗部色度偏置
- **现象与代价**：高 ISO 场景暗部不仅偏绿，且存在色度块状污斑。
- **根本原因**：提交 `7279aad2` 将色度降噪前置到了未白平衡空间。在此空间下，中性灰白天然具备极大的负向色度偏置（偏绿矢量）。绝对值滤波阈值与欠曝信号失配，导致双边滤波破坏了暗部原本微弱的红蓝信号。

### C1-8. GBRG / BGGR 传感器的 LSC 偶/奇行绿通道翻转
- **现象与代价**：部分机型在画面四角边缘出现细微的网格状绿斑或暗角偏色。
- **根本原因**：`LensShadingMap` 定义通道 1 为物理偶数行绿（Geven）、通道 2 为物理奇数行绿（Godd）。当图像被垂直平移 1 行对齐 RGGB 时，物理奇偶行翻转，导致 G1 与 G2 的阴影校正系数颠倒。

---

## 2. 优化思路与改进路线图（Strategic Roadmap）

### 阶段一：极速闭环 P0 级核心画质缺陷（Critical Image Quality Fixes）

1. **重构高光去饱和时序与判定条件**：
   - 将去饱和判定移至应用 `digitalGain` **之后**，在显示前的统一线性空间进行；或者保持位置但将判定阈值归一化：
     `threshold = (65535.0f * 0.8f) / std::max(1.0f, gain);`
   - 确保无论曝光补偿为多少 EV，过曝高光都能稳定平滑地滚降至中性纯白，彻底消除粉色高光。
2. **捞取历史分支恢复 CFA 黑电平动态映射**：
   - 从历史提交 `d0b5ec28` 中提取黑电平通道重排逻辑，在 Halide 的 `black_white_level` 中根据 `cfa_pattern` 对 `bp_r, bp_g0, bp_g1, bp_b` 进行 RGGB 逻辑重排，根治非 RGGB 传感器暗部发绿。
3. **放宽 DNG BaselineExposure 截断上限**：
   - 将 `ColorPipe.cpp:1431` 的 `std::clamp(baselineExposure, 0.0f, 0.5f)` 上限放宽至 `4.0f`，允许 1–3 EV 的真实数字增益完整写入 DNG 元数据，恢复 RAW 后期显影的正确默认曝光。

### 阶段二：色域矩阵与 Log 预设对齐（Gamut Parity & Single Source of Truth）

1. **替换为官方标准 ARRI Wide Gamut 3 矩阵**：
   - 依据 ARRI 官方规范与 SMPTE 标准推导的标准 $M_{XYZ \to AWG3\_D65}$ 矩阵替换当前伪矩阵，对齐 DaVinci Resolve 的 LogC3 / AWG3 IDT 契约。
2. **对齐 13 种 Log 的端到端实现**：
   - 评估策略：若补齐 Canon Log 2/3、N-Log、D-Log、Log3G10 的传递函数及矩阵，则两端同步实现；若算力受限，则在 UI 中隐藏未实现的 5 种 Log，杜绝导出端落入无意义的 sRGB OETF。
   - 提取公共色域查找表，使取景器 GLSL 与 C++ ColorPipe 共用相同的矩阵常量。
3. **补齐 DNG 预览图色彩管线**：
   - 为 `make_preview_rgb8` 补充最简 CCM 变换与 sRGB OETF，使导出的 DNG 在系统相册和文件管理器中拥有正常健康的预览发色。

### 阶段三：底片纯净度与高级色彩处理演进（Pipeline Purity & Advanced Denoise）

1. **分离 LinearRaw DNG 与显示渲染流**：
   - 将 Halide 生成器中的 RAW 域膝点软肩压缩（knee=50000）仅作用于送往 ColorPipe 的显示通路；写入 LinearRaw DNG 的通道保留纯物理线性，维护 DNG 测光基准的严谨性。
2. **优化色度降噪色彩空间**：
   - 评估将色度降噪迁移至“已白平衡线性空间”或在未白平衡空间扣除固定中性色度偏置，避免低光照下的色度截断失真。
3. **修复 GBRG/BGGR 的 LSC 偶/奇绿通道翻转**：
   - 仅针对垂直平移了 1 行的 CFA 格式（GBRG、BGGR），在选取 LSC 绿通道时对调索引 1 与 2，消除角部极微弱的绿增益错配。

---

## 3. 避坑红线与核心保护约束（Red Lines & Invariants）

> [!CAUTION]
> **红线 1：绝对严禁删除 `ColorPipe.cpp:1414-1419`（ColorMatrix1 行乘 AsShotNeutral）！**
> - **历史教训**：曾有分析误判该处为“双重白平衡 Bug”并试图删除。
> - **数学事实**：DNG 1.7 规范严格要求 $CM1 \cdot XYZ(D65) = AsShotNeutral$；标准阅读器（LibRaw、Adobe ACR）内部会对矩阵行和做归一化，与该行缩放精确对消。
> - **严重后果**：一旦删除这 6 行代码，将直接破坏 DNG 契约，导致全 App 拍摄的 DNG 在 Lightroom 中出现剧烈的全图偏色！必须原样保留。

> [!WARNING]
> **红线 2：绝对严禁根据 CFA 去重排 `LensShadingMap` 的 R/B 通道！**
> - **历史教训**：早期提交（如 `35e638d6`, `4a524839`）误以为 LSC 通道是传感器的物理几何排列，强行交换了 R 通道与 B 通道，直接引发了全图四角严重的“彩虹/发蓝发紫暗角”，后被提交 `d0528cab` 紧急回滚。
> - **规范事实**：Android Camera2 的 `LensShadingMap` 通道定义**写死为 `[R=0, Ge=1, Go=2, B=3]`**，与物理 CFA 无关，HAL 层已经做好了映射。严禁改动 R/B 索引！

> [!NOTE]
> **红线 3：分清 BlackLevelPattern 与 LensShadingMap 的本质差异**：
> - `LensShadingMap`：固定颜色顺序（与 CFA 无关）。
> - `BlackLevelPattern`：固定几何位置 `(row, col)`（与 CFA 强相关）。
> - 只有黑电平需要随 CFA 平移进行重排，二者绝不可混为一谈。
