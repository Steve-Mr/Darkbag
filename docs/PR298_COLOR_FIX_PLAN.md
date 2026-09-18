# Darkbag 色彩/曝光修复计划（基于第七、八、九轮审查）

> 依据：`docs/PR298_REVIEW_ROUND7.md`（GCam 对照）、`ROUND8.md`（HDR+ vs 非 HDR+ 同场景实测）、`ROUND9.md`（增益/归一化历史核查与建议更正）；
> 必要处引用 ROUND3–6 的实测（白墙均匀性、引擎验证、带状伪影）。
> 本文只给计划，**不含代码改动**；所有"依据"都可回溯到具体提交/文件行/实测数值。

---

## 0. 计划总览

| 阶段 | 目标 | 关键项 | 预期效果（可量化） |
|---|---|---|---|
| **P0**（1 周内，低风险） | 把"欠曝 + 增益 + 曲线"这条链调到自洽 | 欠曝默认值↓、曲线肩部↑、8bit 抖动、阈值随曝光归一 | 暗部 R/G 与非 HDR+ 差 <0.03；高光过渡码值跨度 ≥40；渐变无长平台 |
| **P1**（2–4 周） | 色彩路线对齐 GCam | 静态路径接入标定双光源模型、HDR+ DNG 补齐元数据、自己做 WB、DNG 去非线性 | 灰卡 ΔE00 改善；HDR+ DNG 与 Bayer DNG 渲染一致；场景残留偏色 <5% |
| **P2**（1–2 月，结构性） | 消除"两段式"与"所见非所得" | 曝光归一化统一、预览走自身管线、LUT 强度、回归断言 | 预览与成片一致；LUT 可调强度；发版前自动回归 |

**贯穿全程的验证资产**（已就绪，直接用）：
`.tmp/verify/dng_probe.py`（DNG 元数据+契约）、`dng_diag.py`（中性度随亮度/空间）、`wall_lsc_test.py`（白墙均匀性）、`jpeg_path_repro.py`（真实管线端到端复现）。

---

## P0：先让"HDR+ 的曝光链"自洽（1 周内）

### P0-1 降低默认欠曝 ⭐最高性价比
- **位置**：`app/src/main/java/top/maary/darkbag/utils/ExposureUtils.kt:18-21`（`FACTOR_EV_MINUS_3/2/1.5`）、`:67-91`（ISO 分档表）
- **依据**：ROUND8 —— 同场景非 HDR+（`digital_gain=1`）实测场区 R/G 0.892、最暗档 0.828、**≥250 占比 0.00%**；HDR+（gain 4.66）分别 0.816 / **0.520** / 4.82%。欠曝把黑电平残差、去马赛克量化等**加性误差放大约 5 倍**（ROUND8 §2）。
- **改法**：ISO≤40 档从 −3 EV 提到 **−1～−1.5 EV**（`0.125 → 0.35～0.5`），其余档同比例收敛；保留"削顶追加欠曝"逻辑（`clippingRatio` 驱动）作为动态兜底。
- **验收**：`digital_gain` 日志分布 P95 ≤ 2.5×；同场景 HDR+ 与非 HDR+ 的场区 R/G 差 <0.03；最暗档 R/G ≥0.75。
- **风险**：高光余量减少 → 必须与 P0-2 一起做，并复测 ROUND4 里"高光溢出"场景（灯泡/天空）。
- **工作量**：0.5 天（改常量+回归）。

### P0-2 让色调曲线的高光肩部匹配曝光余量
- **位置**：`app/src/main/cpp/ColorPipe.cpp` 的色调引擎分支（`apply_khronos_pbr_neutral / apply_pure_luma_filmic / apply_sony_uchimura / apply_aces_fit`，`process_pixel` 内）、以及引擎默认值（`CameraFragment.kt` 读 `KEY_COLOR_ENGINE_MODE` 的三处 + `SettingsFragment` 默认）
- **依据**：ROUND9 §3 —— 欠曝 2.2 EV 意味着"名义白 1.0 → 传感器饱和 4.66"这段要靠曲线表达：**Uchimura 只用 29 个 8bit 码值（场景≈3.0 即到 255），ACES fit 用 45 个（最高 250）**。
- **改法（两步）**：
  1. 立即：默认引擎切到 **ACES fit**（或让用户在设置里选，并把"高光余量"写进设置说明）；
  2. 中期：把曲线按**当次曝光余量**参数化（Uchimura 的 `P` 或等效肩部参数按 `effGain` 缩放），即 GCam 式"为余量留肩部"。
- **验收**：高光过渡（名义白→饱和）占用 **≥40 个 8bit 码值**；同场景 ≥250 占比不高于非 HDR+（以同一渲染亮度比较）。
- **风险**：ACES fit 是**逐通道**曲线（ROUND1 记录），高光可能轻微去饱和/偏色相 → 需要一组高饱和高光样张 A/B。
- **工作量**：默认值改动 0.5 天；参数化曲线 2–3 天。

### P0-3 8bit 输出加抖动（dithering）
- **位置**：`ColorPipe.cpp` 的 JPEG 写出路径（`write_jpeg` / `write_jpeg_turbo` 之前对 `processedImage`/`rgb8` 加噪）
- **依据**：ROUND5 §1 —— 用"Auto 风格强曲线"渲染同一 DNG 时出现 **210px 平台**（经典 8bit banding），而轻度渲染只有 10px。
- **改法**：量化前加 ±0.5 LSB 的三角分布噪声（确定性种子可选，避免破坏可重复性）。
- **验收**：平滑渐变区沿径向最长平台 **<5 px**；文件体积增幅 <2%。
- **工作量**：0.5 天。

### P0-4 色度降噪/高光阈值随曝光归一（**只缩放阈值，不缩放数据**）
- **位置**：`app/src/main/cpp/hdrplus_pipeline_generator.cpp` 的 `bilateral_filter`（`sig2=100`、`threshold=25000`）、`desaturate_noise`（`factor=1.4`、`threshold=25000`）；入口参数经 `HdrPlusJNI.cpp:493-503`（当前恒传 `1.0f, 1.0f`）
- **依据**：ROUND5/ROUND1 —— 3 EV 欠曝时信号缩小 8×，`|input| < 25000` 几乎恒真 → 去饱和/双边滤波在整幅画面上全强度生效，与调参工况完全不同。
- **改法**：把 `effGain`（或等价的欠曝比）作为**新的 Halide 输入**，仅用于把上述阈值**除以 effGain**（即 `threshold/effGain`），**绝不乘到数据上**。
  > ⚠️ 这正是 ROUND9 的历史教训：增益与数据一旦耦合，就会把 DNG 一起改亮并在 u16 里削顶。这里传的是"缩放因子"，语义必须写进参数名与注释（如 `threshold_scale`）。
- **验收**：同一暗场景在 `digital_gain≈4` 与 `≈1` 两档下，暗部色比差 <0.05（ROUND3 曾测到显著差异）。
- **工作量**：1–2 天（含 Halide 重新生成与回归）。

---

## P1：色彩路线对齐 GCam（2–4 周）

### P1-1 静态/HDR+ 路径接入"标定级双光源"色彩模型 ⭐最大结构性收益
- **位置（现状）**：`CameraFragment.kt:1773-1779`（单帧）、`:3420-3437`（HDR+）只读 `COLOR_CORRECTION_GAINS` + `COLOR_CORRECTION_TRANSFORM`；`:3439-3450` 的 `SENSOR_COLOR_TRANSFORM1` 分支被 `:3403` 的 `useSensorColorMatrix = false` 关成死代码。
- **位置（现成实现）**：`rawvideo/RawVideoSessionManager.kt:75-133` 已经把 `SENSOR_COLOR_TRANSFORM1/2` + `SENSOR_FORWARD_MATRIX1/2` + `SENSOR_REFERENCE_ILLUMINANT1/2` + `SENSOR_NEUTRAL_COLOR_POINT` 全部读出来并用于视频。
- **依据**：ROUND7 —— GCam 移植的 DNG 只带标准 Camera2 键，但写的是 **Bayer 原始帧 + ColorMatrix1/2 + ForwardMatrix1/2 + CalibrationIlluminant(A/D65) + AnalogBalance**；Darkbag 静态路径走的是 ISP 的 **as-shot CCM（偏好色）**。
- **改法**：
  1. 抽出共用模块（建议 `utils/ColorCalibration.kt`），由 `RawVideoSessionManager` 与静态路径共用；
  2. 在静态路径按 `SENSOR_REFERENCE_ILLUMINANT1/2` 做**双光源插值 + Bradford 色适应**，得到 sensor→XYZ(D50)→sRGB 的链路；
  3. 把 `useSensorColorMatrix` 从硬编码改为**设置项**（"标定 / 厂商"两模式），默认先保持厂商模式上线、提供切换再做 A/B 决定默认。
- **验收**：灰卡（D65 与 A 光源）ΔE00 对比现状改善 ≥30%；白墙 R/G÷ASN 在 ±2% 内；用户可切换且两种模式都有说明。
- **风险**：观感变化较大，需要给用户开关与迁移说明；不同厂商 HAL 的两光源数据质量不一（需按机型回退）。
- **工作量**：5–8 天（含标定数据缺失时的回退路径）。

### P1-2 HDR+ 的 DNG 补齐标定元数据
- **位置**：`app/src/main/cpp/ColorPipe.cpp:1353-1422`（`write_dng`，当前只写单一 `ColorMatrix1` + `AsShotNeutral` + `CalibrationIlluminant1=21`）
- **依据**：ROUND8 §3 —— **同一个 App 的非 HDR+ Bayer DNG（平台 DngCreator）反而带完整标定**（CM1/2 + FM1/2 + 双光源 + AnalogBalance + BlackLevel + CFAPattern），HDR+ 的 linear DNG 没有；ROUND7 §1 显示 GCam 也是完整标定。
- **改法**：照 `rawVideoExporter.kt:1565-1586` 的写法补齐 CM1/2、FM1/2、CalibrationIlluminant1/2、AnalogBalance；保留现有 `AsShotNeutral`（ROUND4 已确认契约正确，**不要动那段行缩放**）；可选补 `OpcodeList3` 畸变与 `ProfileToneCurve`。
- **验收**：同一灰卡拍摄，Lightroom/rawpy 渲染 HDR+ linear DNG 与 Bayer DNG 的中性度差 <2%；`CM1·XYZ(D65)/ASN` 仍 =1.000。
- **工作量**：2–3 天（含 TIFF 标签与第三方转换器抽检）。

### P1-3 自己做白平衡（或至少提供 Tint 修正）
- **位置**：Halide 合并输出之后 / `ColorPipe.cpp` 的 WB 步骤；UI 侧设置面板（现有 ISO/快门/对焦手动项旁）
- **依据**：ROUND3/ROUND6 —— 该场景 raw 白平衡后 R/G 仍 0.870（偏绿 13%），而 ISP 预览 0.923；GCam 的 AGC JPEG 在同一类"绿色反射"场景里白键帽 R/G=1.051（中性）。
- **改法（择一）**：
  1. 在合并后的 raw 上做 gray-world / white-patch 估计，与 ISP gains 融合（限幅，避免偏色失控）；
  2. 或提供 **WB/Tint 手动项**，让用户对齐预览；
  3. 或两者都做（自动为默认、手动可覆盖）。
- **验收**：典型室内混合光场景的残留偏色 <5%；手动 Tint 调节范围 ≥±20 mired。
- **工作量**：算法版 4–6 天；纯 UI 版 2 天。

### P1-4 raw 域 knee 从 DNG 路径移除（只按"线性性"取舍）
- **位置**：`hdrplus_pipeline_generator.cpp:178-187`（`apply_lsc` 内的联合比例肩部压缩，`knee=50000`）
- **依据**：ROUND5（DNG 声明为 LinearRaw 却含非线性，违反规范模型）；**ROUND9 §3 已明确：缩放/删除它并不能解决显示域高光饱和（4.79%→4.75%）**，因此这条只按"线性性"决定，别当高光修复。
- **改法**：DNG 输出纯线性；肩部只保留在显示链路（或按 P0-2 的参数化曲线处理）。
- **验收**：linear DNG 的 max 剖面无拐点；DNG 在 Lightroom 的高光层次不劣化。
- **工作量**：1 天（+回归）。

---

## P2：结构性（1–2 月）

### P2-1 曝光归一化统一（把"欠曝补偿"放到一个地方）
- **依据**：ROUND8（欠曝放大加性误差）、ROUND9（**不可**把增益乘进共享 Halide 输出：DNG 会削顶 + 与 `BaselineExposure` 重复补偿）。
- **改法**：用 `GeneratorParam`（如 `apply_exposure_scale`）区分两条管线：
  - **DNG 管线**：纯线性、无增益、无 knee；
  - **JPEG/显示管线**：在黑白电平之后、LSC/降噪/曲线之前施加一次曝光缩放，并让所有阈值随之归一。
- **验收**：`BaselineExposure` 与 JPEG 亮度一致（无重复补偿）；DNG 无削顶；JPEG 与非 HDR+ 观感一致。
- **工作量**：5–8 天。

### P2-2 预览走 App 自身管线（真正所见即所得）
- **位置**：`processor/LutSurfaceProcessor.kt`、预览 surface 绑定（`CameraFragment.kt` 的 session 配置）
- **依据**：ROUND6 §2 —— 预览是 ISP 成品（R/G 0.923），成片是 App 渲染（0.815/DNG 0.870），**两者天然不同**；ROUND7 §4 说明这是 Android 固有限制。
- **改法**：把低分辨率处理结果（Halide/ColorPipe 的 preview 输出，`HdrPlusJNI.cpp` 已有 preview 路径）送到预览 surface，替代"ISP 预览 + 叠加引擎"的二次色调映射。
- **验收**：预览与成片的灰卡/肤色色比差 <5%；预览帧率与功耗不劣化。
- **工作量**：10–15 天。

### P2-3 LUT 强度控制
- **位置**：`ColorPipe.cpp` 的 `apply_lut` 之后（lerp 回原色）
- **依据**：ROUND4 §1 —— "FLog2→Velvia" LUT 把 8.5% 的输入偏色放大到 **1.45–2.28×**；这是用户侧观感问题的主因之一。
- **改法**：加"LUT 强度"滑杆（0–100%），默认 100%；UI 提示高饱和 LUT 会放大场景偏色。
- **工作量**：2–3 天。

### P2-4 回归样张库 + 自动化断言
- **内容**：固定 4 组场景（白墙亮/暗、室内混合光、高光灯泡、色卡），每组采 HDR+/非 HDR+ 各一张；
  断言项：DNG 契约比值 =1.000±0.001、暗部 R/G ≥0.90、场区 R/G 差 <0.03、≥250 占比差 <1%、渐变最长平台 <5px、白墙空间极差 <2%。
- **工具**：直接用本轮已写的 4 个脚本（见 §0）。
- **工作量**：3–5 天（一次性）。

---

## 明确"不要做"（防止把已解决的问题改回去）

| 禁止项 | 原因（依据） |
|---|---|
| 删除 `ColorPipe.cpp:1414-1419` 的 `AsShotNeutral` 行缩放 | ROUND4 实测：它是 DNG 契约 `CM1·XYZ(D65)=ASN` 的必要条件（比值 1.000），删了就回到"Lightroom 发绿" |
| 以"消除同心色带"为理由把 LSC 移回 Bayer 域 | ROUND3 白墙实测推翻该假设（空间极差 0.5%/1.3%、无网格周期）；要移回只能出于语义整洁并单独立项评估 |
| 把 `digitalGain` 乘进共享 Halide 输出 | ROUND9 §2 历史教训（`<<2` 与归一化耦合导致回滚）+ DNG 会削顶 + `BaselineExposure` 重复补偿 |
| 指望缩放/删除 raw knee 解决显示域高光饱和 | ROUND9 §3 模拟：4.79%→4.75%，几乎无效 |
| 在未做画质 A/B 的前提下改 JPEG 质量/子采样 | ROUND1 记录 q95/4:2:2 + `TJFLAG_FASTDCT` 的组合需要评估后再动 |

---

## 一次性设备侧采集（做 P1 前先拿到）

1. `SENSOR_INFO_COLOR_FILTER_ARRANGEMENT`（已知不同后摄为 BGGR/GRBG 两种）与各物理摄的 `SENSOR_BLACK_LEVEL_PATTERN` 四值；
2. `STATISTICS_LENS_SHADING_CORRECTION_MAP` 中心区与角部四通道均值（判定 LSC 逻辑序/物理序，ROUND2 §4.2）；
3. `COLOR_CORRECTION_GAINS` 的 greenEven/greenOdd 是否相等（ROUND2 §4.5 的 `wb[2]` 未使用问题）；
4. 一段 `digital_gain` 日志分布（验证 P0-1 前后的变化）。

---

## 建议的提交切分（便于回滚与评审）

1. `perf/exposure-default-tuning`：P0-1（仅 `ExposureUtils.kt`）
2. `fix/tone-curve-highlight-shoulder`：P0-2（引擎默认 + 参数化曲线）
3. `fix/jpeg-dither`：P0-3
4. `fix/denoise-threshold-scaling`：P0-4（`hdrplus_pipeline_generator.cpp` + JNI 新入参）
5. `feat/color-calibration-still`：P1-1（+ `useSensorColorMatrix` 设置开关）
6. `feat/dng-full-calibration`：P1-2
7. `feat/awb-estimation-or-tint`：P1-3
8. `fix/linear-dng-no-knee`：P1-4
9. `feat/exposure-normalization-unified`：P2-1
10. `feat/preview-self-pipeline`：P2-2
11. `feat/lut-strength`：P2-3
12. `test/color-regression-suite`：P2-4

每个提交都附上本文对应条目的**验收判据**与脚本输出（可作为 PR 描述的一部分）。
