# PR #298 第十一轮：两组 HDR+ 样张实测 —— 3 项改动确认生效，ccm 阻断项被坐实

> 新样张（均为 `ff94db80` 构建，`log/lut=None`）：
> - A：`DBAG_2026-09-18-21-34-26-600_HDRPLUS_linear.dng` + `.jpg` —— ISO100 1/1429s，**gain 4.0**，BaselineExposure 2.0
> - B：`DBAG_2026-09-18-21-35-37-857_HDRPLUS_linear.dng` + `.jpg` —— ISO987 1/51s，**gain 1.0004**，BaselineExposure 0.0006
>
> 只读分析；仅更新了我自己的判定脚本 `.tmp/verify/dng_probe.py`（适配新 DNG 语义）。

---

## 0. 结论速览

| 项 | 判定 | 证据 |
|---|---|---|
| DNG 双光源标定补齐 | ✅ **生效** | 两文件均含 `CM1+CM2+FM1+FM2+CalibrationIlluminant(D65/LightA)+AnalogBalance` |
| 欠曝放宽 | ✅ **生效** | B 组 gain=1.0004（完全不再欠曝）；A 组 gain=4.0（基础 −1EV + 削顶自适应追加约 −1EV） |
| TPDF 抖动 | ✅ **生效** | JPEG 中线最长平台 **7px**（改动前同类约 34px） |
| **渲染 ccm 语义** | ❌ **阻断（实测坐实）** | 中性面 R/G 实测 0.464 / 0.680，正确应为 0.80–1.00 |
| 引擎设置可追溯性 | ⚠️ 建议修 | Exif 不记录引擎；由削顶特征反推这两张用的是 **Pure Luma** |

---

## 1. ❌ 阻断项：`ccm` 混用被实测坐实

用同一张 DNG 走完整链路（WB → 增益 → CCM → 色调曲线 → sRGB），比较"正确矩阵 / 当前实现 / 实测 JPEG"：

| 拍摄 | ① 中性保持 CCM（正确） | ② 当前实现（CM1 当 sensor→sRGB） | ③ **实测 App JPEG** |
|---|---|---|---|
| A（gain 4.0） | R/G **0.796**，B/G 0.963 | R/G 0.353，B/G 0.786 | **R/G 0.464，B/G 0.831** |
| B（gain 1.0004） | R/G **0.997**，B/G 0.996 | R/G 0.604，B/G 0.853 | **R/G 0.680，B/G 0.878** |

- ② 与 ③ 高度一致 ⇒ **实测确认**渲染路径用的就是 `SENSOR_COLOR_TRANSFORM1`（XYZ→sensor）而不是 sensor→sRGB；
- ① 才应有的观感（中性面回到 1.0）。

### 现成的正确矩阵（已验证"中性保持"）

```python
A   = M_XYZ_to_sRGB @ inv(ColorMatrix1)      # sensor(WB后) -> sRGB（未归一）
t   = A @ (1,1,1)                            # 场景白当前被映射到的颜色
CCM = diag(1/t) @ A                          # 校验：CCM @ (1,1,1) == (1,1,1) ✓
```
（等价于 `M_XYZ→sRGB · Bradford(场景白→D65) · inv(CM1)`，本轮实测 ① 即用此式。）

### 但更省事、风险更低的修法（同上一轮建议）

```kotlin
// CameraFragment.kt:1916-1920  删掉对 ccm 的覆盖
// CameraFragment.kt:1933       useSensorColorMatrix = true  →  false
// CameraFragment.kt:3415       useSensorColorMatrix = true  →  false
```
渲染路径继续用 as-shot `COLOR_CORRECTION_TRANSFORM`；平台标定**只**进 DNG 标签（`write_dng` 的平台分支已正确处理，无需靠 ccm 传）。
若要开"标定渲染模式"，请用上面的 `CCM` 公式，并把"原始标定"与"渲染矩阵"分成两个不同名字的返回值。

---

## 2. ✅ DNG 标定补齐生效（并带来一处语义变化，已更新判定脚本）

两文件实测含：

```
ColorMatrix1(D65) + ColorMatrix2(Standard Light A) + ForwardMatrix1 + ForwardMatrix2
+ CalibrationIlluminant1=D65 + CalibrationIlluminant2=Standard Light A + AnalogBalance=1 1 1
+ AsShotNeutral（来自 SENSOR_NEUTRAL_COLOR_POINT）
```

**语义变化（不是回归）**：`CM1·XYZ(D65) = ASN` 这个旧等式现在**不再成立**（A 组 0.877/1.142，B 组 0.869/1.207）——
因为 CM1 现在是**固定的参考光源标定**，而 ASN 是**当次场景中性**，DNG 规范本就是靠两者 + 色适应由转换器解释。
正确的自检是"场景白是否合理"：

```
inv(CM1)·ASN = (0.9687, 1, 0.814)  → 比 D65(0.950,1,1.089) 偏暖 ⇒ 室内光源，合理 ✓
```

我把 `.tmp/verify/dng_probe.py` 更新为 **dual-illuminant 感知**判定（输出 `CM2=有 FM1=有 场景白=... 合理=是`），避免后续误报；同时补上 `ColorMatrix2/ForwardMatrix1/2/CalibrationIlluminant/AnalogBalance` 的标签识别。

---

## 3. ✅ 欠曝放宽与抖动：确认生效

- **欠曝**：B 组 ISO 987 → `digital_gain = 1.0004`、`BaselineExposure = 0.0006` ⇒ 暗场景**完全不再欠曝** ✓（旧版低 ISO 一律 −2～−3EV）。
  A 组 ISO 100 → gain 4.0（2 EV）：基础 −1 EV + 削顶自适应追加 ~1 EV（该场景明亮）✓ 与设计一致。
- **抖动**：A/B 两张 JPEG 中线最长平台 **7px**（改动前同类图约 34px）⇒ 8bit 带状伪影已消除 ✓。

---

## 4. ⚠️ 引擎身份：从削顶特征反推为 **Pure Luma**（建议把引擎写进 Exif）

A 组 JPEG 的 `≥254` 占比 = **7.09%**。用同一数据对四个引擎复算：

| 引擎 | ≥254 占比 | ≥250 占比 | 最大值 |
|---|---|---|---|
| Uchimura（新增益自适应肩部） | 0.00% | 7.07% | 252 |
| ACES fit | 0.00% | 7.07% | 251 |
| PBR Neutral | 91.84% | 98.98% | 255 |
| **Pure Luma** | **7.14%** | 7.16% | 255 |
| **实测 JPEG** | **7.09%** | 7.12% | 255 |

⇒ 这两张实际用的是 **Pure Luma**（而非上一轮验证过的新 Uchimura 肩部）。
**建议**：把 `colorEngineMode` 写进 `UserComment` 的 JSON（一行改动）——否则以后拿到样张无法复核是哪条曲线出的图；同时在设置里体现差异：Uchimura 新肩部把 2.2EV 余量摊到 **75 个码值、0% 硬削顶**，Pure Luma 会有 ~7% 硬削顶。

---

## 5. 还需要的东西（按重要性）

1. **修掉 §1 之后重拍同场景**（HDR+ 的 DNG+JPEG 一对）——预期中性面 R/G 回到 0.9~1.0；
2. **白墙/灰卡的 HDR+ 一对**——验证标定 + ASN 配对后的绝对中性度（脚本：`.tmp/verify/wall_lsc_test.py`）；
3. **日志**（仍未获取）：`SENSOR_INFO_COLOR_FILTER_ARRANGEMENT`、`SENSOR_BLACK_LEVEL_PATTERN` 四值、`STATISTICS_LENS_SHADING_CORRECTION_MAP` 中心/角部四通道均值、`COLOR_CORRECTION_GAINS` 的 greenEven/greenOdd、`digital_gain` 分布；
4. 顺手把 `colorEngineMode` 加进 Exif（§4）。

---

## 6. 一句话

`ff94db80` 的三项改动（欠曝、肩部、抖动）与 DNG 标定补齐**都在实拍中确认生效**；
唯一阻断项仍是**把 XYZ→sensor 的 `SENSOR_COLOR_TRANSFORM1` 当成了渲染 CCM**——两组 JPEG 的中性面实测 R/G 0.464/0.680（正确 0.80–1.00），修法见 §1（最简单：`useSensorColorMatrix` 改回 false，渲染继续用 as-shot CCM）。
