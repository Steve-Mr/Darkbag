# PR #298 第七轮：能不能达到预览？GCam 为什么更好？

> 触发问题：*"是否可以认为目前的效果无法达到预览的效果了？但是 gcam port（应该同样获取不到更多的信息？）效果看起来要好很多"*
> 本文用本机现有的 **GCam 移植产物**（AGC / SGCAM / PXL 命名的 DNG+JPEG）+ Darkbag 代码做实证对比，只读分析，未改动既有产物。

---

## 0. 两句话结论

1. **"复刻预览"确实做不到**（预览的观感来自 ISP 的厂商调色，不在 Camera2 元数据里）；
   **但"做到不输给预览"完全可能** —— 因为 **GCam 用的就是同一套 Camera2 元数据，没有任何私有信息**（下面有逐条核对），差别全在**怎么用**。
2. **而且本仓库已经把 GCam 那套色彩模型写好了 —— 只用在 RAW 视频路径，没接到静态照片路径上。** 这是目前最值得做的一件事。

---

## 1. 证据一：GCam 的 DNG 里没有任何"额外信息"

三份 GCam 移植产出的 DNG（其中 `SGCAM_20260321_090408918.dng` 就是**同一台 motorola XT2409-5**）：

| 标签 | AGC (RMX2205) | SGCAM (**XT2409-5**) | Darkbag HDR+ linear |
|---|---|---|---|
| PhotometricInterpretation | **Color Filter Array（Bayer 原始帧）** | 同左 | Linear Raw（已去马赛克） |
| SamplesPerPixel | 1 | 1 | 3 |
| WhiteLevel / BlackLevel | 16368 / 1024×4 | 同左 | 65535 / 0 |
| ColorMatrix1 **和** 2 | ✅ 两个 | ✅ 两个 | ❌ 只有 1 |
| ForwardMatrix1 **和** 2 | ✅ | ✅ | ❌ 无 |
| CalibrationIlluminant1/2 | **Standard Light A / D65** | 同左 | 仅写 D65 |
| AnalogBalance | 1 1 1 | 1 1 1 | ❌ 无 |
| OpcodeList3 | WarpRectilinear | WarpRectilinear | ❌ 无 |
| AsShotNeutral | 有 | 有 | 有 |
| BaselineExposure | 0.44 | 0.18 | 2.22（欠曝 2.2EV） |

**结论**：GCam 写的是"**Bayer 原始帧 + 平台标定级双光源色彩模型**"，全部来自标准 Camera2 键
（`SENSOR_COLOR_TRANSFORM1/2`、`SENSOR_FORWARD_MATRIX1/2`、`SENSOR_REFERENCE_ILLUMINANT1/2`、`SENSOR_NEUTRAL_COLOR_POINT`、`LENS_DISTORTION`）。
**你的判断是对的：它拿不到更多信息；它只是"用对了信息"。**

---

## 2. 证据二：Darkbag 的视频路径已经在用 GCam 那套，静态路径没有

```kotlin
// ✅ 已经实现（RAW 视频）：RawVideoSessionManager.kt:75-133
SENSOR_COLOR_TRANSFORM1 / 2      → colorMatrix1 / colorMatrix2
SENSOR_FORWARD_MATRIX1 / 2       → forwardMatrix1 / forwardMatrix2
SENSOR_REFERENCE_ILLUMINANT1 / 2 → calibrationIlluminant1 / 2
SENSOR_NEUTRAL_COLOR_POINT       → 每次拍摄的白点
// 并且 RawVideoExporter.kt:1565-1586 会把这一整套写进 DNG

// ❌ 静态/HDR+ 路径（CameraFragment.kt:1773-1779、3420-3437）：
COLOR_CORRECTION_GAINS      → wb
COLOR_CORRECTION_TRANSFORM  → ccm        ← ISP 的 as-shot「偏好色」矩阵
// 3439-3450 有读取 SENSOR_COLOR_TRANSFORM1 的分支，但被 useSensorColorMatrix 关掉，
// 而 3403 行硬编码 useSensorColorMatrix = false → 该分支是死代码
```

差别就在这里：**GCam 用比色标定（双光源 + ForwardMatrix），Darkbag 静态路径用 ISP 的 as-shot CCM。**
两者不是"信息量"的差别，而是"用厂商偏好色还是用平台标定色"的路线差别。

---

## 3. 证据三：偏色到底发生在哪一环（前几轮的实测汇总）

| 环节 | 实测值 | 说明 |
|---|---|---|
| 场景本身（raw，按 ISP AWB 白平衡后） | 墙面/场区 R/G = **0.82–0.88** | 该场景白平衡后仍偏绿 12–18% |
| **Darkbag 预览流（ISP 出图）** | R/G = **0.923** | ISP 在预览通路里做了额外处理 → 最中性 |
| Darkbag App JPEG（PBR 引擎，旧） | R/G = 0.556–0.718（Δ 达 **−0.245**） | 显示域 PBR 暗部 toe 再放大 |
| **Darkbag App JPEG（Sony Uchimura，新）** | R/G 与 raw 差 **≤0.036** | ✅ 放大已消除（上一轮验证） |
| Snapseed 渲染同一 raw | R/G = 0.707 | 第三方转换器各自的 look |
| Lightroom 渲染同一 raw | 有同心彩环 | 强色调映射 + 8bit banding（已复现） |

补充一个 GCam 侧的对照样本（`AGC_20250329_225239010.jpg`，绿色切割垫 + 键盘键帽场景）：
**白色键帽 R/G = 1.051、B/G = 0.966 → 中性还原正常**（旁边就是一大片绿垫子，说明它对"绿色反射"场景的处理是稳的）。
—— 两个场景不同、不是严格 A/B，但方向与上面的机制解释一致。

---

## 4. 为什么 GCam 观感更好：五条可核对差异

1. **色彩模型**：标定级双光源模型（A/D65 + ForwardMatrix）vs ISP as-shot CCM（偏好色）。→ 同一台机器、同一场景，两者的"应该是什么颜色"就不同。
2. **白平衡**：GCam 在合并后的 raw 上自己定 WB（并记 `SENSOR_NEUTRAL_COLOR_POINT`）；Darkbag 直接用 ISP 的 AWB gains → 本场景残留偏绿 12–18%。
3. **曝光归一化的位置**：GCam 在 merge 内部按当次拍摄归一；Darkbag 用显示域 `digitalGain`(4–6.8×) + **绝对**的 knee/去饱和阈值 → "越暗越偏"（PBR 实测 ΔR/G −0.245；已换 Uchimura 修正）。
4. **DNG 形态**：GCam = Bayer 原始帧 + 完整标定（转换器按比色法渲染）；Darkbag = 去马赛克线性 RGB + 单一 as-shot 反推矩阵（转换器只能用偏好色，且 App 的 LSC/色度降噪已烘焙）。
5. **链路自洽性**：GCam 从 raw 到 JPEG 全程自己算；Darkbag 的 JPEG = ISP 的 AWB + App 的色调映射，两段式，任何一段的偏差都会留在成片上。

**所以"达不到预览"的真正含义是**：Darkbag 现在既没有复刻 ISP 的调色，也没有走完整的比色标定链路；而 GCam 走的是后者，所以它能**超过**预览而不是模仿预览。

---

## 5. 建议（按性价比，含代码位置）

| 优先级 | 改动 | 位置 | 预期 |
|---|---|---|---|
| **P0** | **把视频路径的标定模型接到静态/HDR+ 路径**：`SENSOR_COLOR_TRANSFORM1/2` + `SENSOR_FORWARD_MATRIX1/2` + `SENSOR_REFERENCE_ILLUMINANT1/2` + `SENSOR_NEUTRAL_COLOR_POINT`；把 `useSensorColorMatrix` 的默认改为 true（或做成"标定/厂商"两种色彩模式） | 读取逻辑可直接复用 `RawVideoSessionManager.kt:75-133`；静态路径已有骨架 `CameraFragment.kt:3420-3450` | 色彩路线与 GCam 对齐，这是最大的一步 |
| **P0** | **DNG 写出补全**：ColorMatrix1/2 + ForwardMatrix1/2 + CalibrationIlluminant1/2 + AnalogBalance（+ 可选 ProfileToneCurve/Opcode 畸变） | 现成实现见 `RawVideoExporter.kt:1565-1586`；静态侧 `ColorPipe.cpp:1353-1422` | 第三方转换器（Lightroom/Snapseed）按比色法渲染，观感立刻接近 GCam |
| **P0** | **自己做 WB**：在合并后的 raw 上做 gray-world/white-patch，或对绿色做一次小修正；并加 **WB/Tint 手动项** | 合并后的 Halide 输出处 / ColorPipe 的 WB 步骤 | 直接消掉那 12–18% 的场景残留偏绿 |
| P1 | 把 `digitalGain`/阈值从显示域移到 raw/merge 域（或阈值随当次曝光归一） | `ColorPipe.cpp:863-889`、`hdrplus_pipeline_generator.cpp:178-187` | 消除"阈值与欠曝错配"这一类问题 |
| P1 | 8bit 输出加抖动；默认欠曝 −3EV → −1～−1.5EV | `ColorPipe.cpp` 的 JPEG 写出 / `ExposureUtils.kt:18-21` | 消除带状伪影、降低对后期的依赖 |
| **P2** | **真正的"所见即所得"**：让预览也走 App 自己的管线（把处理结果渲染到预览 surface），或用"预览 vs 输出"的色差做实时 tint 校正 | `LutSurfaceProcessor` / 预览流 | 唯一能保证"预览=成片"的路径 |

> 已确认不必再做的：DNG 元数据契约/BaselineExposure/黑电平 CFA 重排/粉高光阈值（PR #298 已修好）、以及"把 LSC 移回 Bayer 域"（白墙实测推翻）。

---

## 6. 复现与证据

```bash
# GCam 与 Darkbag 的 DNG 元数据对比
exiftool -s -PhotometricInterpretation -SamplesPerPixel -WhiteLevel -BlackLevel \
  -ColorMatrix1 -ColorMatrix2 -ForwardMatrix1 -ForwardMatrix2 \
  -CalibrationIlluminant1 -CalibrationIlluminant2 -AnalogBalance -BaselineExposure \
  -AsShotNeutral -OpcodeList3 <file.dng>
# 代码位置
grep -rn "SENSOR_COLOR_TRANSFORM\|SENSOR_FORWARD_MATRIX\|SENSOR_REFERENCE_ILLUMINANT\|SENSOR_NEUTRAL_COLOR_POINT" app/src/main/java
```
本轮引用样本：`AGC_20241123_180044114.dng`、`SGCAM_20260321_090408918.dng`（同机型 XT2409-5）、`PXL_20250407_181316051.dng`、`AGC_20250329_225239010.jpg`。
