# HDR+/非 HDR JPEG 边缘发暗发绿问题分析（更新版）

> 新增事实：**非 HDR+ 管线下，RAW 正常，但保存出的 JPG 也出现类似边缘发暗发绿**。

## 结论（更新）
结合新增现象后，优先级应调整为：

1. **主问题很可能在“公共导出链路”而非仅 HDR+ merge 本身**。两条路径最终都走到 `process_and_save_image()` 生成 JPG。  
2. **Lens shading / 通道不均衡（边缘区域）未被正确补偿**仍是最符合症状的根因（中心正常、边缘偏绿偏暗）。
3. HDR+ 路径还叠加了两项次级风险：
   - 黑电平仅单值处理（不是 Bayer 4 通道）；
   - HDR+ DNG 元数据（`BlackLevel` / `AsShotNeutral`）写法不准确，可能导致 RAW 查看器进一步放大偏色。

---

## 关键代码证据

### A) 非 HDR+ 与 HDR+ 的 JPG 导出都走同一函数
- 非 HDR+：`native-lib.cpp::processRaw()` 在 LibRaw 解码后调用 `process_and_save_image(...)`。
- HDR+：`HdrPlusJNI.cpp::processHdrPlus()` 和 `exportHdrPlus()` 也调用 `process_and_save_image(...)`。

这解释了为什么你新增样例里“非 HDR+ JPG”也有类似问题：**它们共享后处理/导出链路**。

### B) HDR+ Halide 管线没有镜头阴影（LSC）校正节点
`hdrplus_pipeline_generator.cpp` 主流程是：
`align -> merge -> shift_bayer_to_rggb -> black_white_level -> white_balance -> demosaic -> denoise -> srgb(matrix)`，未见 lens shading map / gain map 输入与应用。

### C) HDR+ 黑电平为单值扣除
Kotlin 侧仅取 `SENSOR_BLACK_LEVEL_PATTERN` 的 `(0,0)`：
- `blackLevel = bl.getOffsetForIndex(0, 0)`

Halide 侧也用标量 `bp` 统一扣除：
- `output(x, y) = u16_sat((i32(input(x, y)) - bp) * white_factor)`

这在边缘低信号区会放大通道不平衡，常见就是绿偏。

### D) HDR+ DNG 元数据存在偏差
`write_dng()` 里：
- `BlackLevel` 被写成 0；
- `AsShotNeutral` 固定 `[1,1,1]`；

这会让 RAW 软件按错误前提解释图像。

---

## 为什么新增现象很关键
你新增的“**非 HDR+ RAW 正常，非 HDR+ JPG 异常**”说明：

- 问题并不只在 HDR+ 的对齐/融合；
- 更像是“进入 JPG 导出前后”的公共色彩/亮度处理差异；
- 或 RAW 查看器自动应用了镜头校正，而我们的导出链路未等价应用。

换句话说：**HDR+ 只是更严重地暴露了同一类问题，不是唯一来源。**

---

## 可落地修复方案（按优先级）

### 1) 先做定位分离（当天可完成）
在 `process_and_save_image()` 增加 debug 开关，导出以下中间结果：
- `A`: 输入线性 RGB（仅做 16->8 映射，不做色彩矩阵/Log/LUT）
- `B`: 色彩矩阵后（不做 Log/LUT）
- `C`: Log 后

如果 A 就有边缘发绿，问题在更前面（LSC/黑电平）；
如果 A 正常、B 开始异常，问题在矩阵路径；
如果 C 才异常，问题在 Log/LUT 映射。

### 2) 公共路径增加可控 LSC 补偿（优先）
- 从 Camera2 读取 `STATISTICS_LENS_SHADING_CORRECTION_MAP`（设备支持时）；
- 双线性上采样到 full-res；
- 在 Bayer/线性 RGB 早期乘通道增益（建议在线性阶段）；
- 做开关对照：`LSC off/on` 观察边缘绿色偏移与亮度均匀性。

> 这是最契合“中心正常、边缘异常”的结构化修复。

### 3) HDR+ 专项修复
- 黑电平改为 4 通道（R/G0/G1/B）；
- `black_white_level()` 按像素 CFA 位置选对应 black level；
- DNG 元数据改为真实值：`BlackLevel`、`AsShotNeutral` 至少与拍摄 WB 一致。

### 4) 非 HDR+（LibRaw）路径验证
目前 `processRaw()` 使用 LibRaw 的 ProPhoto 输出再进公共管线。
建议增加一个 A/B 验证：
- 分支1：维持现状（ProPhoto -> XYZ -> Target）；
- 分支2：直接输出 sRGB（或最少变换路径）用于定位。

若分支2显著减轻偏色，说明公共色彩变换链还有误配；
若两者都一样偏边缘，则更坐实 LSC/黑电平类问题。

---

## 预计修复效果
按上面顺序推进，通常可达到：
- 边缘亮度显著回升（暗角减轻）；
- 绿色偏移显著下降；
- HDR+/非 HDR+ JPG 在边缘表现趋于一致；
- RAW 与 JPG 的观感差距明显缩小。

