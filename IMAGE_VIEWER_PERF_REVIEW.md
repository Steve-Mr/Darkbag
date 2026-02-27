# Image Viewer 性能与加载行为审查

## 结论（TL;DR）

- **当前实现具备“按需解码”能力**：在 `JPG` 模式且未修改参数时，会直接走 `uri`（JPG/TIFF）加载，不触发 DNG RAW 处理链。
- `DNG/TIFF/JPG` 的“资源发现”在仓库扫描阶段会被统一索引（按同名归组），但这一步主要是枚举文件与读取少量元数据，不会把 DNG 像素解码出来。
- **只要满足“非 RAW 模式 + 未修改 + 未强制 RAW”**，就不会读取 DNG 字节进入 `ColorProcessor.processRawToBitmap`；因此“在不涉及 DNG 的 JPG 查看场景”可做到不加载 DNG。
- 但在“JPG 已修改（调参/LUT）”场景中，当前逻辑会回到 DNG 重新渲染（设计如此），并且每次都会读完整 DNG 到内存，可能成为主要性能瓶颈。

## 关键代码路径

### 1) 什么时候会绕开 DNG

在 `DarkbagRawInterceptor` 中，存在明确短路：

- 条件：`!isRawMode && !isModified && !forceRaw`
- 行为：直接将请求数据改为 `data.uri`，交给 Coil 正常解码。

这意味着如果当前是 JPG/TIFF 正常查看，并且没有编辑改动，不会进入 RAW 处理。

### 2) 什么时候必须走 DNG RAW 处理

`shouldProcessRaw = dngUri != null && (isRawMode || isModified || forceRaw)`：

- `isRawMode = true`（DNG tab）
- `isModified = true`（JPG 但调参后）
- `forceRaw = true`（预留强制路径）

满足时会读取 DNG 文件字节并调用 `ColorProcessor.processRawToBitmap`。

### 3) Viewer 是否会“互相影响”

- 在 `ImagePagerAdapter` 中，`isRawMode` 只在 `currentFormat == "DNG"` 时为真；
- `isModified` 由当前 metadata 是否中性决定；
- 因此，**格式切换和参数修改会影响是否触发 DNG 重渲染**。

换言之：

- **JPG 未修改**：通常不受 DNG 解码开销影响；
- **JPG 已修改**：会受 DNG 解码影响（因为需要从 RAW 重建）；
- **DNG 模式**：一定走 RAW 处理。

### 4) “不涉及 DNG 时能否确保不加载 DNG？”

可以在当前逻辑下成立，边界条件为：

1. 当前不是 DNG 模式；
2. metadata 为中性（未编辑）；
3. 未启用 forceRaw；
4. `uri` 对应可直接解码格式（JPG/TIFF）。

在此条件组合下，请求会直接走 `chain.withRequest(newRequest).proceed()` 到目标 `uri`，不会触发 DNG 字节读取。

## 当前性能风险点

1. **RAW 路径每次全量读 DNG**
   - `openFileDescriptor + readBytes()` 会把整份 DNG 读入内存；
   - 调参连续拖动时可能反复触发，CPU/IO 压力大。

2. **RAW 处理全局串行**
   - `Semaphore(1)` 限制并发，避免资源争抢但也会导致请求排队，滑动/切图时可能有等待感。

3. **缩略图策略在 RAW 模式下仍有成本**
   - 非当前页会走 `isThumbnail=true` 分支，虽然只取缩略图，但依然需要访问 DNG。

4. **扫描阶段仍会枚举 DNG 文件**
   - 这不是“像素解码”，但在文件很多时仍有目录枚举成本。

## 建议的性能优化方案（按优先级）

### P0：保证“JPG 未修改”绝不回落 RAW

- 现状已经基本满足，建议加日志埋点和单元测试/集成测试，确保回归不破坏短路条件：
  - 统计 `shouldProcessRaw` 命中率；
  - 记录格式/是否修改/来源 URI。

### P1：为“JPG 已修改”引入结果缓存

- 按 `(dngUri, metadata, quality, targetSize)` 建 LRU 内存缓存（可叠加磁盘缓存）；
- 滑杆微调时做 **debounce（如 80~150ms）**，避免每个步进都触发完整 RAW 管线；
- 同时加入“同 key 进行中任务合并”，避免重复计算。

### P2：两阶段预览

- 先快速展示：DNG embedded thumbnail / 低分辨率 RAW 结果；
- 再异步替换为高质量结果；
- 能显著改善首屏和切图体感。

### P3：避免重复解析与重复 IO

- 缓存 DNG 的基础信息（宽高/方向）到内存映射表，避免每次重新 Exif 解析；
- 对 `readBytes()` 改为可复用缓冲或 mmap（若 JNI 接口允许），减少 GC 抖动。

### P4：任务调度优化

- 维持单图串行，但允许“前台当前图”抢占“后台邻近图预加载”；
- 切页时取消过期 RAW 任务（仅保留最新请求），降低尾延迟。

### P5：仓库扫描优化（大图库场景）

- 增量索引（基于 `date_added` / `lastModified`）；
- SAF 扫描结果持久化缓存；
- 分页加载而非一次性全量组装。

## 回答问题（逐条）

1. **当前 image viewer 能否高效浏览？**
   - 对“未编辑 JPG/TIFF 浏览”是较高效的（可直接走 Coil 解码，不做 RAW 重建）；
   - 对“编辑态 JPG / DNG 浏览”性能瓶颈明显（全量 DNG 读取 + RAW 处理）。

2. **dng / tiff / jpg 加载是否会互相影响？**
   - 会在“请求决策层”互相影响：同一张图切换格式、或 JPG 编辑后，会改变是否进入 DNG RAW 路径；
   - 但在“JPG 未编辑”场景下，DNG 不会参与像素解码。

3. **在不涉及 dng（不切 DNG tab、仅查看/改 JPG）时，能否确保不加载 dng？**
   - **仅查看 JPG（未修改）可以确保不加载 DNG。**
   - **改 JPG（有参数改动）当前实现会加载 DNG**，这是现有设计决定，用于从 RAW 重新渲染高质量结果。

