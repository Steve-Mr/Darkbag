# PR #298 第八轮：非 HDR+ 同场景对照 —— HDR+ 的额外偏绿与高光削顶被定量定位

> 新增输入：
> - `DBAG_2026-09-18-20-05-28-098.dng`（**非 HDR+**：平台 DngCreator 写出的 **Bayer CFA** DNG，1 通道，未压缩，20MB）
> - `DBAG_2026-09-18-20-05-28-098.jpg`（同一次，`log/lut=None`，**`digital_gain = 1`**，ISO100 1/417s f/2.0）
> - 对照：`DBAG_2026-09-18-19-57-10-848_*`（HDR+，`digital_gain = 4.66`，ISO100 1/2500s，同一位置约 8 分钟前）
>
> 只读分析，未改动既有产物。

---

## 0. 结论：同场景下**非 HDR+ 客观更好**，且原因可定位到代码

| 指标（同场景、渲染亮度接近） | **HDR+**（gain 4.66） | **非 HDR+**（gain 1） |
|---|---|---|
| 硬削顶像素（G ≥ 250） | **4.82%**（≥254 占 4.77%，max 255） | **0.00%**（max 248） |
| 场区 R/G 中位 | 0.816 | **0.892** |
| 最亮环带 R/G（r=30–80px） | 0.892 | **1.000（中性）** |
| 最暗档 R/G（G 10–30） | **0.520** | **0.828** |
| 主亮度档 R/G（G 30–60） | 0.815 | 0.896 |

→ **HDR+ 相对非 HDR+：高光被硬削顶 4.8%，且全场更绿（暗部差距最大：0.52 vs 0.83）。**
→ 这也解释了你的直觉"非 HDR+ 看起来更好"，以及此前 DNG 偏暗、Lightroom 一抬就出问题。

---

## 1. 机制一：绝对 knee + 显示域 digitalGain ⇒ HDR+ 高光硬削顶（已定量对上）

代码路径（当前版本）：

```
Halide:  黑白电平 → apply_lsc(内含 knee=50000 的联合比例压缩) → 去马赛克 → 色度降噪 → 输出(未白平衡线性)
ColorPipe: WB → 高光去饱和 → min(v,65535) → norm = v/65535 × digitalGain(4.66) → CCM → 色调引擎(Uchimura) → sRGB OETF
```

- knee 是**绝对阈值**（50000/65535 ≈ 76%），它**按未增益前的信号**把高光压到 ≤57767（0.88 满量程）。
- 但 `digitalGain` 是**之后**才乘上去的：任何 pre-gain 值 > 65535/digitalGain = **14063（21.5% 满量程）** 的像素，
  乘完就 >1.0，而 Uchimura 的输出上限是 1.0 → **整片区域直接饱和**。
- 非 HDR+（gain = 1）时 knee 正常工作：被压到 0.88 后再过 Uchimura ≈ 0.85 → **不饱和** ✅

**实测完全吻合**：HDR+ 4.82% 削顶 / 非 HDR+ 0.00%。

> 换句话说：**knee 是为"正常曝光"设计的，一旦后面还要乘 4–7× 的显示域增益，它既挡不住削顶，也白丢了线性性**（而线性性正是 DNG 必须的）。

---

## 2. 机制二：欠曝 2.5 EV 让所有"加性误差"在暗部放大 ~5 倍

- 非 HDR+ 是 **1/417s**，HDR+ 是 **1/2500s**（≈2.5 EV 差，靠 `digitalGain 4.66` 补回亮度）。
- 两者渲染亮度接近（场区 G 中位 42 vs 47），但 HDR+ 的信号整体只有 1/4.66：
  黑电平残差、去马赛克/量化的负值整流、LSC 网格误差等**加性项**的相对影响全部 ×4.66。
- 直接表现就是我前面几轮反复测到的"越暗越偏"：**HDR+ 暗部 R/G 0.520 vs 非 HDR+ 0.828**。

---

## 3. 附带发现：**非 HDR+ 的 DNG 元数据本来就是"完整标定级"**

平台 DngCreator 写出的 Bayer DNG（同一台 XT2409-5）：

```
PhotometricInterpretation: Color Filter Array   SamplesPerPixel: 1   BlackLevel: 64  WhiteLevel: 1023
ColorMatrix1 (D65) + ColorMatrix2 (Standard Light A)
ForwardMatrix1 + ForwardMatrix2
CalibrationIlluminant1 = D65, CalibrationIlluminant2 = Standard Light A
AsShotNeutral = 0.4369, 1, 0.6928        （来自 SENSOR_NEUTRAL_COLOR_POINT）
CFAPattern = [Green,Red][Blue,Green]（GRBG）
```

而 **HDR+ 的 linear DNG 只有单一 `ColorMatrix1`（由 as-shot CCM 反推）+ AsShotNeutral**。
→ 这就是第七轮结论的**内部自证**：同一个 App 里，**非 HDR+ 的 DNG 才带有 GCam 式的完整标定**，HDR+ 的反而没有。
→ 建议把 HDR+ 的 `write_dng` 补齐到同一水准（现成实现见 `RawVideoExporter.kt:1565-1586`）。

（另注：`CFAPattern` 在不同样本里出现过 BGGR / GRBG 两种 —— 说明这台手机不同后摄传感器不同；App 是按 `LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID` 取对应 characteristics 的，处理正确，无需改。）

---

## 4. 最终建议（收敛版，按优先级）

| 优先级 | 改动 | 位置 | 解决什么 |
|---|---|---|---|
| **P0** | **把 `digitalGain` 移到 Halide 管线内、在 LSC/knee 之前施加**（或在显示域施加时同步缩放 knee/阈值） | `HdrPlusJNI.cpp` 的 pipeline 入参 → `hdrplus_pipeline_generator.cpp` 的黑白电平之后；`ColorPipe.cpp:886-889` 相应改为不再重复乘 | **HDR+ 高光硬削顶 4.8% → 0**；恢复 knee 的设计意图 |
| **P0** | **降低默认欠曝**（低 ISO 下 −3 EV → −1～−1.5 EV），或按场景自适应 | `ExposureUtils.kt:18-21,67-91` | 暗部加性误差不再 ×5；噪声/色偏/带状同时改善 |
| **P0** | HDR+ 的 DNG 补齐完整标定（CM1/2 + FM1/2 + 双光源 + AnalogBalance） | `ColorPipe.cpp:1353-1422`（可照抄 `RawVideoExporter.kt:1565-1586`） | 第三方转换器按比色法渲染，观感对齐 GCam |
| **P0** | 静态路径接入 `SENSOR_COLOR_TRANSFORM1/2` + `FORWARD_MATRIX1/2` + `NEUTRAL_COLOR_POINT`（现在只用在 RAW 视频） | 复用 `RawVideoSessionManager.kt:75-133`；`CameraFragment.kt:3420-3450` 已有骨架，`useSensorColorMatrix` 改默认 | 色彩路线与 GCam 一致 |
| P1 | 自己做 WB（合并后 raw 上的 gray-world / 对绿色小修正）+ WB/Tint 手动项 | ColorPipe / 合并后处理 | 消掉场景残留偏绿 12–18% |
| P1 | 8bit 输出加抖动；`BaselineExposure` 已修好无需再动 | ColorPipe JPEG 写出 | 带状伪影 |
| P2 | 预览走 App 自身管线（真正所见即所得） | `LutSurfaceProcessor` / 预览流 | 预览与成片一致 |

**一句话**：HDR+ 目前"更绿、更容易削顶"不是玄学，而是**"欠曝 + 显示域增益 + 绝对阈值 knee"三者的组合**；把增益的施加位置和默认欠曝改掉，再加上完整标定元数据，就能同时逼近 GCam 的观感并保住 HDR+ 的高光优势。
