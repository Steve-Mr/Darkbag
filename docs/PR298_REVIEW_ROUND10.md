# PR #298 第十轮：`ff94db80` 本地提交审查（1 个阻断项 + 3 项已验证有效）

> 审查对象：本地分支 `feat/color-pipeline-optimization` 的提交 `ff94db80`
> "feat(color): implement dual-illuminant DNG calibration, relax underexposure curve, and add TPDF dithering"
> 新样张：`DBAG_2026-09-18-21-02-35-375.dng`（ISO100 1/340s f/2.0，**非 HDR+ 的 Bayer DNG**）
> 只读分析，未改动任何既有产物。

---

## 0. 结论

| 项 | 判定 | 依据 |
|---|---|---|
| ① 放宽欠曝（−3→−1.5 / −2→−1.0 / −1.5→−0.5 EV） | ✅ 实现正确 | `ExposureUtils.kt` diff；单元测试已同步 |
| ② Uchimura 肩部随增益自适应 | ✅ **数值验证有效** | 真实 DNG 复算：硬削顶 **≥254 从 4.75% → 0.00%**，极大值 255→253；余量占用 29→**75** 个码值 |
| ③ TPDF 抖动 | ✅ **数值验证有效** | 合成渐变：平均平台 **32.8px → 2.2px**（最长 34→21px） |
| ④ DNG 补齐双光源标定 | ✅ 结构正确 | 平台分支写 CM1/CM2+光源+FM1/2+AnalogBalance **且不再乘 ASN**；ASN 优先取 `SENSOR_NEUTRAL_COLOR_POINT` |
| ⑤ 渲染路径的 `ccm` | ❌ **阻断（必须改）** | 把 `SENSOR_COLOR_TRANSFORM1`（**XYZ→sensor**）当成 sensor→线性 sRGB 使用 |

---

## 1. ❌ 阻断项：`ccm` 的矩阵语义被混用

### 现状（两处都用错，且都硬编码 true）

```kotlin
// 单帧路径 CameraFragment.kt:1916-1920
val singleCalib = extractSensorCalibration(chars, captureResult, wb)
if (chars.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1) != null) {
    ccm = singleCalib.colorMatrix1.copyOf()      // ← ccm 被覆盖成 XYZ→sensor
}
...
useSensorColorMatrix = true                      // :1933
ccm = ccm                                        // :1935 → HdrPlusRequest.ccm

// HDR+ burst 路径
val useSensorColorMatrix = true                  // :3415
val ccm = if (useSensorColorMatrix) ccmSensor else ccmCapture   // :3483
// ccmSensor ← SENSOR_COLOR_TRANSFORM1 逐元素拷贝（:3451-3458）
```

`extractSensorCalibration()` 是**逐元素原样提取**（无求逆、无色适应）——所以 `ccm` 现在等于 `SENSOR_COLOR_TRANSFORM1`。

### 为什么错

- Android 定义：**`SENSOR_COLOR_TRANSFORM1` 是 XYZ → 参考 sensor 空间**（本机 SDK `CameraCharacteristics` 文档；DngCreator 也正是把它写成 DNG 的 `ColorMatrix1`，而 DNG 的 `ColorMatrix1` 按规范就是 XYZ→camera）。
- 而 ColorPipe 的 `ccm` 语义是 **白平衡后的 sensor RGB → 线性 sRGB**（`ColorPipe.cpp` 的 `sourceColorSpace==1` 分支：`color = CCM · sensor`，随后 `sRGB→XYZ→Rec709`）。

### 后果（用真实样张的矩阵算）

| 输入（WB 后） | 正确 CCM 输出 | 当前实现输出 |
|---|---|---|
| 中性 (1,1,1) | (1.00, 1.00, 1.00) | **(0.42, 0.96, 0.73)** → R/G 0.44、B/G 0.76 |
| 中性 (0.5,0.5,0.5) | (0.5,0.5,0.5) | (0.21, 0.48, 0.37) 同上比例 |

→ **每一个中性面都会被染成明显的绿-青色**，整幅画面严重偏色。
受影响范围：**JPEG 主输出、预览位图（`process_and_save_image`）、以及 `write_dng` 的 fallback 分支**（平台分支优先，所以 DNG 标签本身没被污染 ✅）。
**不受影响**：取景器预览（走 ISP 流 + 引擎）、非 HDR+ 的 Bayer DNG（平台 DngCreator 写）。

### 修法

**A. 最小改动（建议立刻做）**：渲染路径恢复用 as-shot `COLOR_CORRECTION_TRANSFORM`，平台矩阵**只**用于 DNG 标签。
即：把 `:1933` 与 `:3415` 的 `useSensorColorMatrix` 改回 `false`，并删掉 `:1918` 对 `ccm` 的覆盖（`write_dng` 的平台分支已经能正确处理"有/无平台矩阵"两种情况，不需要靠 ccm 传）。

**B. 正解（= 第七轮建议的 GCam 式渲染，可作为 P1-1 的正式实现）**：
渲染用矩阵应为
```
CCM_eff = M_XYZ→sRGB · Bradford(场景白 → D65) · inv(CM1)
```
其中场景白可由 `inv(CM1)·ASN` 得到（本机样张实测 `inv(CM1)·ASN = (1.011, 1.042, 0.924)`，与 D65 `(0.950, 1, 1.090)` 不同，正说明场景光源≠D65，**必须做色适应**）；或走 ForwardMatrix 链路
`XYZ_D50 = FM1 · diag(1/ReferenceNeutral) · raw_wb`，再 `M_XYZ(D50)→sRGB`。

---

## 2. ✅ 已验证有效的三项（含复算数据）

### 2.1 增益自适应的 Uchimura 肩部（`CP = -C2/(P·max(1, gain·0.75))`）

用真实 HDR+ DNG（19:57，`digital_gain=4.66`）复算：

| 肩部 | ≥250 占比 | **≥254（硬削顶）** | 极大值 | 名义白→饱和占用的码值 |
|---|---|---|---|---|
| 旧（gain 不参与） | 4.79% | **4.75%** | 255 | 29 |
| **新（gain 自适应）** | 4.69% | **0.00%** | **253** | **75** |

→ **达到了 ROUND9 提出的目标**（我建议的 ACES fit 方案是 45 个码值，你们这条做到了 75，更好）。
建议：给 `0.75` 这个经验系数加注释说明来源，并用 gain ∈ {1, 2, 4.66, 8} 各回归一次（避免低增益档被过度拉长肩部）。

### 2.2 TPDF 抖动

合成平滑渐变（2000px 内 60 个码值）：

| | 最长平台 | 平均平台 |
|---|---|---|
| 无抖动 | 34 px | 32.8 px |
| **TPDF 抖动** | 21 px | **2.2 px** |

→ 带状伪影（ROUND5 的 210px 平台问题）在 App 自身输出上被消除 ✅。
覆盖范围：JPEG 主路径 + 预览路径 + DNG 内嵌预览 ✅（TIFF/BMP 导出未覆盖，属可选项）。

### 2.3 放宽欠曝

低 ISO 从 −3.0 EV 提到 **−1.5 EV**、ISO100 −1.0 EV、ISO400 −0.5 EV ✅ 与 ROUND8/9 的建议一致（最激进档甚至比建议更保守）。
建议补一条量化回归：**同场景 HDR+ 与非 HDR+ 的场区 R/G 差 <0.03**（ROUND8 实测现状是 0.816 vs 0.892）。

---

## 3. ✅ DNG 分支的正确性确认（这段写得好）

`ColorPipe.cpp:1536-1571`：

```cpp
if (colorMatrix1 != nullptr) {                 // 平台标定优先
    TIFFSetField(tif, TIFFTAG_COLORMATRIX1, 9, colorMatrix1);
    ... COLORMATRIX2 / CALIBRATIONILLUMINANT1 / 2 / FORWARDMATRIX1 / 2 / ANALOGBALANCE
} else {                                       // 旧路径保留（自推矩阵 + ASN 行缩放）
    ... colorMatrix1Fallback ... *= as_shot_neutral[r] ...
}
```
- ✅ 平台矩阵**没有被再乘 `AsShotNeutral`**（正确：它是参考空间标定，中性由 ASN 单独承载）
- ✅ `AsShotNeutral` 改为优先取 `SENSOR_NEUTRAL_COLOR_POINT`（`:1513-1517`）——与平台矩阵配对正确，正是 DngCreator/GCam 的写法
- ✅ fallback 分支（自推矩阵 + ASN 缩放）原样保留，兼容性没问题

---

## 4. 新样张为什么验证不了这次改动 + 需要补的图

`DBAG_2026-09-18-21-02-35-375.dng` 是**非 HDR+ 的 Bayer DNG**（`Software` = 平台串，`SamplesPerPixel=1`，CFA=GRBG），它：
- 不走 `write_dng`（平台 DngCreator 写）→ 验证不了 ④
- 不欠曝（`digital_gain=1`）→ 验证不了 ①
- 不走 ColorPipe 渲染 → 验证不了 ②③，也**暴露不了 ⑤**

请补三样（同场景、一次拍完）：

| # | 内容 | 用来验证 |
|---|---|---|
| 1 | **HDR+ 的 `_HDRPLUS_linear.dng` + 同名 `.jpg` 一对** | ① 场区 R/G 是否贴近非 HDR+；② 高光 ≥254 是否为 0；③ JPEG 平滑无平台；⑤ **一眼就能看出 ccm 混用导致的整体发绿** |
| 2 | 一张**白墙/灰卡**（HDR+，DNG+JPEG） | ④ 平台标定 + ASN 配对后的中性度；⑤ 的定量确认 |
| 3 | 设备日志 | CFA、黑电平四值、LSC 四通道均值、`digital_gain` 分布 |

判定脚本（已就绪）：
```bash
python3 .tmp/verify/dng_probe.py <HDR+线性DNG>     # 契约 + 新增标签
python3 .tmp/verify/wall_lsc_test.py <白墙DNG>     # 中性度/空间均匀性
```

---

## 5. 其它小建议

1. `useSensorColorMatrix` 目前在两处**硬编码 true**；建议改为设置项（"标定 / 厂商"两模式），且在修好 §1 之前**默认必须是 false**。
2. `extractSensorCalibration()` 目前只做逐元素提取；若后续要在渲染端使用平台标定，函数内应直接产出**渲染用的 CCM**（含 `inv(CM1)` 与色适应），避免语义再次混用——建议把"原始标定"与"渲染矩阵"分成两个不同名字的返回值（如 `calibration.*` 与 `renderCcm`）。
3. 抖动覆盖到 TIFF/BMP 导出会更好（可选）。
4. `ExposureUtilsTest` 已同步 ✅；建议再加一条"欠曝档位 vs 暗部加性误差"的数值回归（见 §2.3）。

---

## 6. 一句话

`ff94db80` 的三项改动（欠曝放宽、增益自适应肩部、TPDF 抖动）与 DNG 标定补齐都**经复算验证有效/正确**；
但同一提交把 **`SENSOR_COLOR_TRANSFORM1`（XYZ→sensor）当成了渲染用的 sensor→sRGB 矩阵**，会让所有中性面变成绿青色——
**请把 `useSensorColorMatrix` 先改回 false（渲染路径继续用 as-shot CCM），平台标定只用于 DNG 标签**；等实现 `M_XYZ→sRGB·Bradford·inv(CM1)` 后再作为"标定模式"开启。
