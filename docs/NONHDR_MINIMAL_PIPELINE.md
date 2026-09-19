# 非 HDR+「规范·极简」管线（本分支实现说明）

基线：`main`（PR #298 已合并）。分支：`feat/nonhdr-minimal-spec-pipeline`。

## 0. 定位与判据

非 HDR+ 路径 = **忠实、极简、可复现**；HDR+ 路径 = 观感优先。判据：

1. **可追溯**：每个阶段对应 Camera2/DNG 规范或平台标定；无法追溯的删除或默认关闭；
2. **点操作优先**：除去马赛克与 LSC（均有规范依据）外不引入邻域处理；
3. **不发明数据**：不重建、不外推、不做场景自适应；
4. **可复现**：参数写入 Exif（已有 `color_engine_mode`）。

## 1. 本分支改了什么（2 个提交）

### 提交 1 `refactor(nonhdr): keep the minimal single-frame path linear for blown highlights`

| 位置 | 改动 | 理由 |
|---|---|---|
| `hdrplus_pipeline_generator.cpp`（`apply_lsc`） | joint knee（`50000/15535`）仅在 `!single_frame_mode` 时施加 | knee 是显示取向的软肩部，不属于 Camera2/DNG 色彩模型；单帧路径应线性直到传感器白电平 |
| `ColorPipe.cpp`（`process_pixel`） | 同样的 knee 仅在多帧路径施加；最小路径改为**削顶中性化**（`max_ch ≥ 0.98×65535 → 三通道取 max_ch`，点操作、零参数） | 饱和像素颜色不可知 → 渲染为白；消除"逐通道削顶顺序"造成的品红边 |
| `ColorPipe.cpp`（高光去饱和） | 仅在多帧路径施加 | 该 ramp 含 `max(wb)/eff_gain`，在 `gain == 1` 时几乎失效（近削顶像素 desat ≈ 2%），对最小路径无益 |
| `ColorPipe.h` / `HdrPlusJNI.cpp` | 新增 `faithfulHighlights` 参数，由 `numFrames == 1` 推导 | 无需改 Kotlin/JNI 签名，单帧路径自动命中 |

### 提交 2 `docs(nonhdr): document minimal-path scope, acceptance and deferred items`
本文件。

## 2. 已完成的验证

| 验证 | 方法 | 结果 |
|---|---|---|
| 生成器编译 | 用缓存 Halide 21（x86-64 宿主）直接编译生成器 | ✅ 退出码 0 |
| Halide lowering | 分别以 `single_frame_mode=true/false` 运行生成器 | ✅ 两者均成功 |
| knee 确实移除 | 生成 `c_source` 后 grep 常数 | ✅ 单帧生成源中 `50000/15535` 出现 **0** 次（LSC 采样仍在） |
| C++ 编译 | 用项目自身 `compile_commands.json` 对 `ColorPipe.cpp`、`HdrPlusJNI.cpp` 做语法编译 | ✅ 退出码 0 |
| 行为影响 | 对真实 DNG 反解 knee 后对比新旧链路 | ✅ 品红/粉像素 **0.680% → 0.001%**；蓝晕 2.35% → 2.34%（如实保留）；高光区色比回到数据本身（R/G 0.972 → 0.869，与 DNG 实测 0.82–0.88 一致） |

**预期可见变化**：饱和区由"品红边"变为中性白；`≥254` 占比略升（5.2% → 6.0%，因为不再有 knee 软肩部，滚降交给显示变换）——这是"忠实"的必然结果。

## 2b. 评审后续修正（已在分支上追加提交）

代码评审指出两处问题，均已核实并修复：

1. **导出路径漏传标志**：真正落盘的 JPEG 由 `exportHdrPlus` 写出（`HdrPlusProcessingService.kt:137`；`processSingleFrameRaw` 传入的 jpg 路径为 `null`），而该 JNI 没有 `faithfulHighlights` 参数 ⇒ ColorPipe 侧的最小路径逻辑（knee / 去饱和 / 中性化）在**成片上未生效**（Halide 侧的 knee 移除仍然生效，因为它发生在 shared buffer 之前，导出的中间数据也因此是线性的）。
   **修复**：`exportHdrPlus` 末尾新增 `faithfulHighlights`（Kotlin / JNI 各一个、顺序一致），服务端传 `req.isSingleFrame`；落盘 JPEG 与 `processHdrPlus` 语义一致。
2. **中性化位置**：在**白平衡之前**把三通道置为相等，经逐通道增益与 16-bit 截断后并不相等（R/B 被 clamp 而 G 未 clamp），会在饱和过渡带留下品红细环。
   实测（真实 DNG，反解 knee 后）：过渡带（`0.98·WL ≤ max < WL`）共 **916 px（0.0092%）**，前置中性化在该带内 R/G = B/G ≈ **1.0073**（约 0.7% 偏品红）；完全削顶区（6.04%）本就中性。
   **修复**：**检测仍在传感器域**（判断是否削顶），但**中性化移到白平衡与 clamp 之后**，取三通道最大值置齐 ⇒ 该带内恒为完全中性（1.0000）。这比"把 raw 直接置 65535"更稳健：后者依赖 `wb[R], wb[B] ≥ 1`，对中性比比 G 更大的光源不成立。

> 说明：由于 ISP 的 as-shot CCM 不在 DNG 中，无法用样张精确复现"CCM 对带内色偏的放大倍数"；上述数值均为白平衡域（CCM 之前）的实测，保守可靠。

## 3. 设备侧验收清单（本分支未在真机验证）

| 项 | 判据 | 脚本 |
|---|---|---|
| 白墙/灰卡中性 | `R/G、B/G ÷ ASN = 1.00 ± 0.02` | `.tmp/verify/wall_lsc_test.py` |
| 空间均匀性 | 8×8 极差 ≤1.5% | 同上 |
| 高光粉边 | 品红像素占比（`R/G>1.02 且 B/G>1.02`）显著下降 | 同上（自定义判据） |
| 高光削顶 | `≥254` 不高于基线 +1% | 同上 |
| DNG 未受影响 | 非 HDR+ 仍为平台 Bayer DNG，标定标签完整 | `.tmp/verify/dng_probe.py` |
| 暗部 | σ 不劣化 >10% | — |

## 4. 明确未做（避免过度工程）

| 项 | 原因 |
|---|---|
| 显示变换/引擎选项调整（原 N5） | 已有 LUT 与四个引擎可选，暂不引入新的"Neutral"档 |
| 去马赛克换线性核（N4） | 会改变细节/伪色表现，风险高，需单独评估与开关 |
| 拍摄端高光优先曝光（N6） | 属产品决策（会改变用户拍照亮度）且需真机验证，待确认后单独提交 |
| 线性 16bit 输出（N7） | 新增交付物，非本分支目标 |
| 高光重建、去紫边、自适应/Auto、局部对比 | 与"不发明数据/点操作"判据冲突 |

## 5. 不改动的既有行为

- HDR+ 路径的 knee、去饱和、合并、降噪、显示变换：**保持不变**（共享代码改动均由 `faithfulHighlights`/`single_frame_mode` 隔离）；
- DNG 写出（平台 `DngCreator` + HDR+ 的线性 DNG 元数据）：不变；
- 所有 UI 与设置项：不变。
