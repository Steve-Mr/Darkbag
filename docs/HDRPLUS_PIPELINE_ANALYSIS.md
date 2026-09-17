# Darkbag HDR+ / 非 HDR+ 拍照管线：处理速度与色彩正确性分析

> 分析对象：`/home/maary/Build/Darkbag`（Camera2 API + RAW 的 Android 相机，HDR+ 由 Halide + hdr-plus 派生管线实现）
> 分析方式：主控 agent + 4 个并行 subagent（非 HDR+ 性能 / HDR+ 性能 / HDR+ 色彩 / 官方文档与标准），全部结论可回溯到源码 `path:line`、官方知识库 `kb://` 或标准原文。
> 代码未被修改，仅新增分析产物（见 §7）。

---

## 0. 结论摘要

### 问题一：HDR+ 与非 HDR+ 的处理速度如何提升？

**先纠正一个前提**：两条链路的瓶颈**不在同一个地方**，"统一优化"会浪费精力。

| | 非 HDR+（单帧） | HDR+（连拍） |
|---|---|---|
| 主瓶颈 | **Kotlin 层保存链路**（解码 + 重编码 + 多次全文件 I/O）与**串行挡在处理前的副作用**（DNG 落盘、Motion Photo await） | **Halide 对齐层 0 的穷举 SAD** + **导出/落盘段整体串行且无埋点** |
| 最贵的一次操作 | `BitmapFactory.decodeFile` + `compress(JPEG,95)`（前摄/变焦时必现） | 约 3.0×10⁹ 次"绝对差+累加"（对齐层 0，5 帧 / 12MP） |
| 首要动作 | 修 `ImageSaver` 的伪慢路径判定（1 行） | 修埋点 → 对齐降本 → 导出/落盘与 Halide 流水化 |

按"收益/成本"排序的**前 7 项**（详细见 §3）：

| # | 改动 | 位置 | 成本 | 预期收益 |
|---|---|---|---|---|
| 1 | 修 `ImageSaver` 伪慢路径（顺带消除**双镜像**） | `ImageSaver.kt:85` + `HdrPlusProcessingService.kt:190` | 1–2 行 | 前摄/变焦 **0.4–0.9 s/张** `[需实测]` |
| 2 | fast 档去掉无意义的 RGB→YUV→RGB 浮点往返 | `hdrplus_pipeline_generator.cpp:319-330` | 1 行 + 重新生成 | 日间常见档 **0.1–0.3 s/张** `[需实测]` |
| 3 | 补齐"导出段"埋点（当前恒为 0） | `exportHdrPlus` / `StandardTimingTracker` | ~30 行 | 0（但决定后续优化顺序） |
| 4 | "导出 + 落盘"移出 Halide 处理线程，形成两级流水 | `HdrPlusProcessingService.kt:123-199` | ~10 行 | 连拍 5 张 **1–3 s** `[需实测]` |
| 5 | ColorPipe 循环不变量提升 + 删死代码 | `ColorPipe.cpp:807,886` | ~5 行 | **0.05–0.2 s/张** `[需实测]` |
| 6 | 对齐层 0 抽样 / 增量 SAD | `hdr-plus/src/align.cpp:22` | 中 | **0.15–0.45 s/张** `[需实测]` |
| 7 | **修 release 实际只有 `-O2`**（被 `RelWithDebInfo` 的 `-O2` 覆盖 `-O3`） | `app/src/main/cpp/CMakeLists.txt:287-288` | 1 行 | 全 native 代码（Halide 生成的 `.a` 除外）；幅度 `[需实测]` |

配套项：LUT 走已有缓存（`get_cached_lut` 已存在却没人用）、RAW buffer 池化（去掉每张 24 MB `allocateDirect`）、
去掉 Halide 输出 72 MB 的**零初始化**、DNG 改 deflate + 直连 MediaStore fd、非 HDR+ 时移除无用的 YUV 分析流、
处理线程与 OpenMP 统一线程预算。

### 问题二：当前 HDR+ 处理管线的色彩处理是否正确？

**结论：主链骨架正确，但整体"不完全正确"。**

- ✅ **主链 `sensor → WB → CCM → XYZ(D65) → sRGB → tone → OETF` 结构正确、每一级只应用一次**
  （已逐级数值核验：不存在双重白平衡、双重 CCM、双重 gamma，也不存在 D50/D65 混用的经典陷阱）。
  Halide 侧 `white_balance()` 是显式空实现、`ccm` 输入与 `srgb()` 是死代码（`hdrplus_pipeline_generator.cpp:201-205,35,332-337`），
  WB/CCM 只在 `ColorPipe` 施加一次；CCM 的行列主序与 AOSP `COLOR_CORRECTION_TRANSFORM` 语义一致（无转置错误）；
  这一顺序也与 HDR+ 论文第 6 节的 13 步顺序自洽。
- ❌ **但有 5 项确定性缺陷（deterministic），会直接造成用户可见的错误**：

| 级别 | 缺陷 | 证据 | 用户可见症状 |
|---|---|---|---|
| 高 | **`BaselineExposure` 被截断到 0.5 EV**，而 `digitalGain` 常见 2–8× | `ColorPipe.cpp:1431-1432`（`clamp(log2(digitalGain),0,0.5)`）；`ExposureUtils.kt:18-21,160` | 同一张照片 JPEG 正常，LinearRaw **DNG 明显偏暗 1.5–2.5 EV**（最易复现） |
| 高 | **"Arri LogC3" 用的不是 ARRI Wide Gamut**：代码矩阵隐含 G(0.221,**0.760**)、B(0.136,**0.057**)，而 AWG3 是 G(0.221,**0.848**)、B(0.0861,**−0.102**)，与按 AWG3 推导的 XYZ→RGB 最大偏差 **0.3514**（主控已独立复算确认） | `ColorPipe.cpp:322-326,919`；`LutSurfaceProcessor.kt:86-90` | LogC3 素材套 AWG IDT 后整体偏色 |
| 高 | **13 个 Log 选项有 5 个未实现**（Canon Log2/3、N-Log、D-Log、Log3G10 → 落到 `default: srgb_oetf` + Rec709），且**取景器与导出结果不同**（取景器是 ACES fit，N-Log/D-Log 还选了 Rec2020） | `SettingsFragment.kt:983-997`；`ColorPipe.cpp:568-584,918-926`；`LutSurfaceProcessor.kt:698-700,142` | 选这些 Log 后画面与预期完全不符，套 LUT 严重偏色；预览与成片不一致 |
| 高 | **DNG 内嵌预览（512/2048）没有色彩矩阵**：只做 WB + `pow(x,1/2.2)`，而规范规定彩色预览默认为 sRGB | `ColorPipe.cpp:1317-1329,1469-1511`；DNG 1.7 p.53 | 系统相册/文件管理器缩略图偏色（打开进 Lightroom 却正常） |
| 高/中 | **RAW 域逐 CFA 通道非线性软肩压缩**（knee=50000）写进了声明为 `LinearRaw` 的 DNG | `hdrplus_pipeline_generator.cpp:178-187`；`ColorPipe.cpp:1371,1385-1389` | 高亮色相漂移、RAW 高光层次不可恢复；与规范"线性参考值"模型不符 |

- ⚠️ 另有 8 项一致性问题（中，见 §4.2 C6）：同一场景并存**三套色彩模型**（Camera2 CCM / LibRaw `cam_xyz` / `ForwardMatrix`）与
  四个渲染入口；**取景器输入是 ISP 预览 → 二次 tone mapping**，且 shader 没实现 contrast/saturation/HSWB；
  **除 DNG 外所有输出都没有 ICC / EXIF `ColorSpace`**（Log/宽色域导出会被当作 sRGB 解释）；
  两个 DNG 写出器策略不一致；静态黑电平未用 `SENSOR_DYNAMIC_*`；`COLOR_CORRECTION_GAINS` 未按 G 归一化。

> **一个被推翻的假设（重要，防止误改）**：分析初期我曾判定 `ColorPipe.cpp:1414-1419`（把 `AsShotNeutral` 乘进
> `ColorMatrix1`）是"双重白平衡"缺陷，并建议删除那 6 行。**该结论已被推翻，切勿删除**：
> 该行缩放恰好满足 DNG 契约 `ColorMatrix1 · XYZ(校准光源白) = AsShotNeutral`；
> LibRaw 在 `cam_xyz_coeff()` 中会对相机矩阵**按行归一化**（行和恰为 `AsShotNeutral`），
> 与 `pre_mul = 1/AsShotNeutral` 精确抵消，最终渲染结果与 App 自身的"先 WB 再 CCM"链完全一致
> （主控独立数值复算：两条路径差 **2.2e-16**）。删除后反而会破坏 CM↔ASN 契约。详见 §4.5。

---

## 1. 分析基线与方法

- 环境：本机安装了官方 `android` CLI（`~/.local/bin/android`），并已装好官方 Android skill：
  `.agents/skills/camerax`（`android skills add camerax --project …`）。
- 官方文档获取方式（沙箱内 `~/.android` 只读，需要覆盖 `user.home`）：
  ```bash
  export HOME=/home/maary/Build/Darkbag/.tmp/androidhome
  export JAVA_TOOL_OPTIONS="-Duser.home=$HOME"
  android docs search "camera2 burst capture performance"
  android docs fetch  kb://android/media/camera/camera2/capture-sessions-requests
  ```
- 项目级 skill（本次新建，供后续同类审计复用）：`.dsh/skills/android-camera2-darkbag-audit/SKILL.md`。
- 标准原文：`DNG Specification 1.7.0.0`（Adobe，2023-06）第 6 章；AOSP `DngCreator` / `ColorSpaceTransform` 源码；
  本仓库内 LibRaw 与 hdr-plus 子模块源码。
- 团队分工：4 个 subagent 并行（非 HDR+ 性能、HDR+ 性能、HDR+ 色彩、官方文档/标准），
  各自产出独立报告（`.agents/audit/*.md`），主控 agent 独立复核关键论断并合并。

> **可测量性前提**：当前埋点本身是坏的 —— `StandardTimingTracker.jniDone` / `firstOutputWritten`
> 只声明未赋值（`CameraFragment.kt:456-457` 被 `:1982-1988` 读取），`exportHdrPlus` 根本没有 `debugStats` 参数，
> 所以服务日志里的 `C++ Post/ColorPipe`、`DNG Encode`、`JPEG Native Save` 恒为 0
> （`HdrPlusJNI.cpp:585` 写死 0；`HdrPlusProcessingService.kt:101-105` 传 `null`）。
> **因此本报告中所有耗时数字都标 `[需实测]`，且建议先修埋点（见 §3.5 的 P0-0）再谈优化排序。**

---

## 2. 两条链路的现状还原

### 2.1 非 HDR+（单帧 RAW）

| # | 阶段 | 位置 | 线程 | 数据量/格式 |
|---|---|---|---|---|
| 1 | 快门 → 分流 | `CameraFragment.kt:1439,1547-1551` | Main | — |
| 2 | `TEMPLATE_STILL_CAPTURE`，唯一 target = RAW `ImageReader` | `:4206,4217-4218`；reader 配置 `:3804`（RAW_SENSOR，`maxImages=8`） | Camera2Thread | 4000×3000×2 B ≈ 24 MB |
| 3 | `onImageAvailable` → `acquireLatestImage` → 拷贝到**新分配**的 direct buffer（逐行，因 rowStride≠width×2） | `:4223-4225,4239,4445-4473` | Camera2Thread | 24 MB memcpy + `getCameraCharacteristics` IPC `:4475` |
| 4 | 投递到 `processingChannel` | `:4246` → 消费者 `:876-897` | coroutine | — |
| 5 | `processImageAsync`：取 `CaptureResult` 元数据（WB/CCM/LSC/black/white level/CFA） | `:1716,1736,1757-1795` | IO | LSC 约 4×17×13 float |
| 6 | **Bayer DNG 由 Android `DngCreator` 写盘**（串行挡在入队前） | `:1855-1902`（`:1859,1881`） | IO | 压缩 DNG，写 cache |
| 7 | 入队 + 启动前台服务 | `:1970-1976` | IO/Main | — |
| 8 | 服务单线程消费 → `processSingleFrameRaw` → `processHdrPlus(numFrames=1)` | `HdrPlusProcessingService.kt:49-53,69`；`HdrPlusJNI.cpp:589-611` | **单线程** `HdrPlusProcessor` | — |
| 9 | Halide `hdrplus_single_pipeline`（跳过 align/merge/denoise） | `HdrPlusJNI.cpp:494`；`hdrplus_pipeline_generator.cpp:47-55,72-76` | Halide 线程池 | 输出 12 MP×3×u16 = 73 MB |
| 10 | `exportHdrPlus` → ColorPipe 逐像素（WB→CCM→色域→色调→OETF→JPEG q95/4:2:2） | `HdrPlusJNI.cpp:293-367`；`ColorPipe.cpp:850-1007,1127,1259` | OpenMP | 36 MB RGB8 → JPEG |
| 11 | `ImageSaver`：拷进 MediaStore → 再重开 URI 写 EXIF | `ImageSaver.kt:87-140,674-711` | IO | ≥3 趟全文件 I/O |

要点：非 HDR+ **不使用 LibRaw**（`processRaw` 只服务多摄/查看器，`native-lib.cpp:149`），RAW 只被解码一次。

### 2.2 HDR+（连拍）

| # | 阶段 | 位置 | 线程 | 数据量/格式 |
|---|---|---|---|---|
| 1 | 按 ISO/曝光算连拍参数，`burstSize` 默认 5 | `CameraFragment.kt:4304-4328`；`ExposureUtils.calculateHdrPlusExposure` | Main | — |
| 2 | `captureBurst(N 个 TEMPLATE_STILL_CAPTURE)`，`AE_MODE=OFF` + 固定 ISO/曝光 | `:4345-4359,4425` | Camera2Thread | 每帧 24 MB |
| 3 | 每帧 `acquireNextImage` → 逐行拷进 `megaBuffer`（池化，`MAX_POOL_SIZE=3`） | `:4385-4400`；`HdrPlusBurst.kt:58-84,146-200` | Camera2Thread | 5×24 MB ≈ 122 MB |
| 4 | 收齐 → `processHdrPlusBurst`（元数据 + 组请求） | `:4334-4335,3349-3387` | Dispatchers.IO | — |
| 5 | 入队 → 服务**单线程**取任务 | `HdrPlusProcessingService.kt:49-53` | 单线程 | — |
| 6 | `processHdrPlus`：零拷贝 direct buffer → Halide 输入 | `HdrPlusJNI.cpp:387-399` | Halide 线程池 `hw−2` | 122 MB 只读 |
| 7 | Halide：align（3 层金字塔 SAD）→ merge → black/white → LSC(+非线性肩) → **WB(no-op)** → demosaic → chroma denoise | `hdrplus_pipeline_generator.cpp:44-78`；`hdr-plus/src/align.cpp`、`merge.cpp` | Halide | 输出 73 MB |
| 8 | 分配 73 MB 输出 vector（**零初始化**）并存入 `g_sharedMemoryMap` | `HdrPlusJNI.cpp:405,551-557` | JNI | — |
| 9 | 释放 megaBuffer（注释称可省 168–208 MB） | `HdrPlusProcessingService.kt:116` | 单线程 | — |
| 10 | `exportHdrPlus`：写 72 MB **未压缩** linear DNG（+2 张内嵌 JPEG 预览）→ 再跑整条 ColorPipe 出 JPEG | `HdrPlusJNI.cpp:346-357`；`ColorPipe.cpp:1353-1515` | 单线程 + OpenMP | DNG 72 MB |
| 11 | `ImageSaver` 落盘（同上，但 `isAlreadyCropped=true`） | `HdrPlusProcessingService.kt:178-199` | IO | — |

**关键结构事实**：第 6–11 步全在**同一条单线程**（`ColorProcessor.imageProcessingDispatcher`，
`ColorProcessor.kt:16-28`）上串行；`HdrPlusRequestManager` 用 `Channel.UNLIMITED`
（`HdrPlusRequestManager.kt:60`），而 `processingSemaphore` 是空实现（`CameraFragment.kt:440-445`），
真正的背压只有 `canAcceptNewTask` 的"可用内存 ≥600 MB"门限（`HdrPlusRequestManager.kt:80-89`）。

---

## 3. 问题一：处理速度如何提升

### 3.1 结构性瓶颈（先看这三条，其它优化都建立在它们之上）

- **B-0 全链路单线程串行**：Halide 计算、导出（ColorPipe 逐像素）、DNG/JPEG 编码、MediaStore 落盘、EXIF 重写
  全排在一个线程上。Halide 内部虽用 `halide_set_num_threads(hw−2)`（`HdrPlusJNI.cpp:451-458`）、
  ColorPipe 用 OpenMP（`CMakeLists.txt:283-288` 已开 `-fopenmp -O3 -ffast-math`），
  但**段与段之间**没有任何重叠，连拍 N 张的总时间 ≈ N ×（计算 + I/O）。
- **B-0' 埋点缺失**：最贵的导出段完全不可见（见 §1 末尾）。**先补埋点**，否则下面的收益排序都只是估算。
- **B-0'' 内存与分配**：每张照片新 `allocateDirect` 24 MB（`CameraFragment.kt:4460`）、
  Halide 输出 73 MB **零初始化**（`HdrPlusJNI.cpp:405`，`std::vector<uint16_t>(n)` 会 memset）、
  输出 vector 每次 `make_shared` 后又被 `exportHdrPlus` 从 map 里 `erase` 掉（`:309-317`）→ 无法复用；
  megaBuffer 池最多留 3 个 × 122 MB ≈ **366 MB 常驻原生内存**（`HdrPlusBurst.kt:40`）。
  这既是耗时（memset/页错误）也是 `canAcceptNewTask` 600 MB 门限频繁拒绝新拍摄的原因。

### 3.2 非 HDR+ 单帧：瓶颈与优化

| 编号 | 瓶颈（证据） | 优化 | 预期收益 / 成本 |
|---|---|---|---|
| S1 | **伪慢路径**：`needsBitmapProcessing = rotationDegrees!=0 \|\| zoomFactor>1.05f \|\| inputBitmap!=null \|\| mirror`（`ImageSaver.kt:85`）→ 前摄/变焦时对 native 已处理好的全分辨率 JPEG 再做 `decodeFile`（`:147`）+ `compress(JPEG,95)`（`:206/278`）。而 `isAlreadyCropped=true` 只挡住裁剪（`:175`），native 侧其实已经镜像（`ColorPipe.cpp:1074`）与裁剪（`ColorPipe.cpp:1008-1014`）。文件自己的 KDoc 也写明"JNI 已镜像则传 false"（`ImageSaver.kt:36-37`），调用方却传了 `mirror=req.mirror`（`HdrPlusProcessingService.kt:190`） | 判定加 `!isAlreadyCropped`，服务端 `mirror=false`，需要翻转语义时写 EXIF 方向 | **0.4–0.9 s/张**；成本 1–2 行；同时消除**双镜像**功能隐患 `[需实测]` |
| S2 | Bayer DNG 串行挡在处理前（`:1855-1902` 完成才 `enqueue :1970`） | 抽成独立协程 + 给 `RawImageHolder` 加引用计数（避免与 `releaseBuffer` 竞争） | 100–300 ms；成本低 `[需实测]` |
| S3 | Motion Photo 开启时 `withTimeoutOrNull(2500){await()}` 在关键路径（`:1911`，`postDurationMs=750`） | 把 await 移到服务写 JPEG 前，或改回调补 mp4 路径 | 0.75–2.5 s/张；成本低 `[需实测]` |
| S4 | **无 3A 预热、无 ZSL**：still 请求前没有 `AE_PRECAPTURE`/`AF_TRIGGER`（`:4217-4221`），全仓库无 `TEMPLATE_ZERO_SHUTTER_LAG`/`PRIVATE_REPROCESSING`。仓库内有正例可抄：`MultiCameraCaptureManager.kt:593-620 triggerFast3AWarmup` | 先做 3A 预热（低风险）；ZSL/reprocess 作为独立里程碑（可复用 `motionphoto/CircularVideoRingBuffer.kt`、`rawvideo/RawVideoRecorder.h:74-88` 的环形缓冲/工作线程先例） | 快门延迟 50–300 ms（预热）；ZSL 可消除"等一帧曝光" `[需实测]` |
| S5 | LUT 每张重新从 `.cube` 文本解析（`HdrPlusJNI.cpp:339,519` 用 `load_lut`，`ColorPipe.cpp:587-617`），而 `get_cached_lut`（`:619-626`）已存在，只有 rawvideo 在用（`RawVideoGLRenderer.cpp:449`） | 改用 `get_cached_lut`；切换 LUT 时 `clear_lut_cache()` | 20–400 ms；成本极低 |
| S6 | 每张新 24 MB direct buffer，不走已有 `HdrPlusBurst.acquireBuffer` 池（`CameraFragment.kt:4460` vs `HdrPlusBurst.kt:58-83`） | 复用池；或把 rowStride 直接传进 native，用带 stride 的 Halide Buffer（当前 `HdrPlusJNI.cpp:399` 假定紧凑布局） | 去掉逐行拷贝 + 分配；成本低 |
| S7 | 死代码全图归约：`calculate_adaptive_edge_comp`（`ColorPipe.cpp:807` → `:710-778`）无条件把 `enabled=false`（`:772-775`）却仍跑一遍统计 | 删除调用与函数体 | 小；成本极低（1 行） |
| S8 | 每像素 `std::pow(2.0f, exposure)`（`ColorPipe.cpp:886`，`exposure` 是循环不变量）；u16 中间体 + 二次全图遍历（`:1069-1092` + `:1247-1258`） | 常量外提；JPEG-only 时直接生成 RGB8 跳过 u16 中间体 | 50–200 ms；成本极低 `[需实测]` |
| S9 | 非 HDR+ 时第三个 YUV 分析流仍常驻并每帧采样（`:3811-3846`），而 `lastClippingRatio` 只被 HDR+ 使用（`:4317,4928`） | HDR+ 关闭时重建会话，移除分析流 | 预览功耗/带宽；成本低 |
| S10 | 每张 2 次 `getCameraCharacteristics` IPC（`:4475`、`:1754`）、前台服务冷启动 + 通知每张一次（`:1971`、`HdrPlusProcessingService.kt:29-34,261-264`） | 缓存 characteristics；服务常驻或合并通知 | 小但恒定 |

### 3.3 HDR+ 连拍：瓶颈与优化

| 编号 | 瓶颈（证据） | 优化 | 预期收益 / 成本 |
|---|---|---|---|
| H1 | **对齐层 0 的穷举 SAD 是绝对主成本**：16×16 tile、8 像素步长（`hdr-plus/src/align.h:3-5,13`；`align.cpp:22-23,120-121`）、层 0 为 1/2 分辨率（`util.cpp:13-31 box_down2`）→ 每像素被 4 个 tile 覆盖、每 tile 还要试 64 个位移各 256 点；5 帧 12 MP ≈ **3.0×10⁹ 次"绝对差+累加"**（估算 0.2–0.5 s，层 1/2 合计 <1.5%） | A：tile 内隔点抽样（≈4×）；B：增量 SAD（列和递推，理论 5–7×） | **0.15–0.45 s/张**；A 成本 1 行、B 中等；代价是暗部对齐精度，需 A/B 评估 `[需实测]` |
| H2 | 同 S1（HDR+ 服务同样传 `mirror=req.mirror`，`HdrPlusProcessingService.kt:190`） | 同 S1 | 0.4–0.9 s/张 |
| H3 | **未压缩 72 MB linear DNG**（`ColorPipe.cpp:1361` `COMPRESSION_NONE` + `:1374` `ROWSPERSTRIP=1` → 3000 次 strip 写），写 cache → 读出 → 写 MediaStore → 删（`HdrPlusJNI.cpp:346-350`；`ImageSaver.kt:321-360`）= 约 **216 MB I/O/张** | 改 `COMPRESSION_DEFLATE`；`ROWSPERSTRIP` 提到 64–256；native 直接写 MediaStore fd（`TIFFClientOpen`） | 体积/IO 各降约 50%；0.2–0.6 s/张；成本低–中 `[需实测]` |
| H4 | **导出段无埋点**（见 §1）：`exportHdrPlus` 没有 `debugStats`，服务的分段打印因此失真 | 给 `exportHdrPlus` 加 `debugStats`，回填 DNG/ColorPipe/JPEG 三段 | 0（决定排序） |
| H5 | **fast 档做无意义的 YUV 往返**：`denoise_passes=0` 时 `chroma_denoise` 仍 `rgb_to_yuv`（u16→f32，12 MP×3×4 B=144 MB 中间体）再 `yuv_to_rgb`（`hdrplus_pipeline_generator.cpp:319-330`；`util.cpp:291-311`）。已在编译产物 `hdrplus_fast_pipeline.a` 中用 `llvm-nm` 确认存在 `rgb_to_yuv_output`/`yuv_to_rgb_output` | `num_passes==0` 时直接返回 input | 0.1–0.3 s/张；成本 1 行 + 重新生成 `[需实测]` |
| H6 | 降噪档位只看 ISO（`HdrPlusJNI.cpp:479-481`：<400 fast、≥1600 high），与用户设置/画质诉求脱钩 | 暴露档位或按场景（夜景/运动）选择 | 画质/速度权衡 |
| H7 | 每像素 `pow`（同 S8） | 同 S8 | 50–200 ms |
| H8 | megaBuffer 池 3×122 MB 常驻，仅在"设置→清除缓存"时释放（`HdrPlusBurst.kt:40-52`） | 按需缩池、或按最大连拍帧数预分配单个 buffer | 内存；影响 600 MB 门限 |
| H9 | 每帧一次 Main 线程进度更新 + 通知/日志刷新（`:4402-4404`；`HdrPlusProcessingService.kt:36-47,261-264`） | 节流到每 2 帧或百分比变化时 | 小；成本低 |
| H10 | Halide target 串带 `profile`：`arm-64-android-opencl-vulkan-vk_int16-profile`（`CMakeLists.txt:137`）→ 发布版常开插桩，且每张还要 `halide_profiler_report` + 正则解析（`HdrPlusJNI.cpp:506-507,86-116`） | 发布版去掉 `-profile`，或仅 debug 变体保留 | 小–中；成本低 |
| H11 | 帧拷贝与预览回调共用 `Camera2Thread`；采集期内存流量 240 MB | 单独的 RAW 回调 HandlerThread | 采集期卡顿；成本低 |
| H12 | 三条流常开、无 `StreamUseCase`（`:3862`；对比 `MultiCameraCaptureManager.kt:248-268` 已用 `OutputConfiguration`） | 会话改用 `SessionConfiguration` + `setStreamUseCase`（preview=PREVIEW、RAW=STILL_CAPTURE），API 33+，需能力回退 | 设备相关；成本中 |
| H13 | 热与功耗完全没处理（无 `PowerManager` 热状态、无降级策略）；对照官方 `kb://android/agents/skills/camera/camerax/references/thermals` | 连拍/连续拍摄时按热状态降帧数或降档 | 长时间使用稳定性 |
| H14 | 采集节奏与设备能力脱钩：未设 `SENSOR_FRAME_DURATION`，也未使用任何延迟预算 API；未调用 `CameraCaptureSession.prepare()` 预分配 burst buffer | 设置帧间隔；用 `SYNC_MAX_LATENCY` / `REQUEST_PIPELINE_MAX_DEPTH` / `getOutputMinFrameDuration()` 估算并选择路径；`USECASE_LOW_LATENCY_SNAPSHOT`（官方唯一带数值承诺：≤200 ms）可用则以它为准；`captureBurst` 前 `prepare()` | 采集 0.2–0.6 s `[需实测]` |
| H15 | **发布版 native 实际以 `-O2` 编译**：`CMakeLists.txt:287-288` 写入 `-O3`，但 `RelWithDebInfo` 追加的 `-O2 -g -DNDEBUG` 在其后（已在 `app/.cxx/RelWithDebInfo/316t5s4b/arm64-v8a/build.ninja` 的 `FLAGS` 中核实：`… -fopenmp -O3 -ffast-math -O2 -g -DNDEBUG …`，Debug 变体反而是 `-O3`） | 把 `-O3` 放到 `CMAKE_CXX_FLAGS_RELWITHDEBINFO`，或用 `-O3` 覆盖 | 全部 native（ColorPipe/OpenMP 循环等）；1 行改动 |
| H16 | 连拍帧数的边际收益递减：每加 1 帧对齐成本约 +25%（N=5→8 时对齐 +75%），而多帧平均的 SNR 只按 √N 增长（+2.0 dB） | 让连拍帧数随 ISO/场景自适应，默认值可下调 | 速度/画质权衡 |
| H17 | `initMemoryPool` 是 native 空实现且无调用点（`HdrPlusJNI.cpp:248-256`），名字具有误导性；`debugStats` 的分段归类漏掉了 `rgb_to_yuv`/`yuv_to_rgb`/`output` 三段（`HdrPlusJNI.cpp:106-112`）——恰好 H5 的浪费藏在盲区里 | 删除或实现；补全 stage 归类 | 0（可观测性） |

### 3.4 跨链路共同项

1. **两级流水**：Halide 计算线程只做 `processHdrPlus` + `exportHdrPlus`，落盘/MediaStore/EXIF 交给专用单线程；
   注意 `onTaskFinished()`/`stopSelf()` 必须等落盘完成（`HdrPlusProcessingService.kt:208-223`）。
2. **JPEG/DNG 直连 MediaStore fd**：现在是"native 写临时文件 → Kotlin 复制 → 再重开改 EXIF"（≥3 趟）。
   改为把 `ParcelFileDescriptor` 传到 native（`TIFFClientOpen` / libjpeg 的 `FILE*`→fd），EXIF 在编码时一次性写入。
3. **线程预算统一**：`halide_set_num_threads(hw−2)` 与 OpenMP 默认线程池各自取满核，叠加 I/O 线程会超额订阅；
   建议统一为 `n−2` 并按热状态降级。
4. **JPEG 编码参数**：`jpegQuality=95`（`ColorPipe.cpp:1127`）+ `TJSAMP_422`（`:1259`）+ `TJFLAG_FASTDCT`（`:27`）。
   `FASTDCT` 是"用精度换速度"，在 q95 下性价比存疑；若降本可评估 q92 + 4:2:0，但**必须先做画质评估**。
5. **可观测性**：把 `debugStats` 扩到导出段，并给关键段加 `android.os.Trace` 打点，之后所有"需实测"都能一次跑清。

### 3.5 反面结论（先排除，避免走弯路）

审计中确认**不存在**的问题，不要在这些方向上投入：

- HDR+ 连拍**没有** LibRaw 解码、**没有**"写临时 DNG 再读回"的往返：输入是 `ImageReader` 的 RAW_SENSOR direct buffer，
  经 `Buffer<uint16_t>(rawDataPtr, w, h, n)` 零拷贝进 Halide（`HdrPlusJNI.cpp:387-399`）。
- 子模块里那套经典 CLI 路径（`hdr-plus/src/Burst.cpp`、`InputSource.cpp`、`LibRaw2DngConverter.cpp`、
  `halide_load_raw.h` 以及子模块自带的 `hdrplus_pipeline_generator.cpp`）**没有被 Android 构建引用**；
  Android 侧编译的是仓库根目录的 `app/src/main/cpp/hdrplus_pipeline_generator.cpp`（`CMakeLists.txt:144-201`）。
- HDR+ 的并行度本身**没问题**：Halide 已 `parallel`/`vectorize`（可在生成的 `.a` 中看到 `par_for` 符号），
  线程数为 `hw−2`（`HdrPlusJNI.cpp:451-458`），ColorPipe 已开 OpenMP。**收益来自算法与内存流量，不是"把它变并行"。**
- `ColorPipe` 里**没有**重复施加白平衡或 gamma：LSC 只在 Halide 侧施加一次（ColorPipe 内的 `lscX/lscY` 是死代码），
  WB/CCM 只在 ColorPipe 施加一次。
- CCM 的取值顺序**没有**转置错误（已用 AOSP `ColorSpaceTransform` 源码核对）。

### 3.6 建议路线图

- **P0（先做，1 天内，低风险）**：修埋点（S/H4、H17）→ S1 伪慢路径 + 双镜像 → H5 fast 档 YUV 往返 →
  S5/H9 LUT 缓存 → S7/S8 死代码与常量提升 → H15 release 恢复 `-O3` → H10 发布版去 `-profile`。
  **预期合计 0.5–1.5 s/张。**
- **P1（1–2 周，中低风险）**：S2 DNG 并行 → S3 Motion Photo await 出关键路径 → H3 DNG deflate + 直连 fd →
  H1-A 对齐抽样（带画质 A/B）→ H8/S6 缓冲池与零初始化 → H11 独立 RAW 回调线程。
- **P2（里程碑级，需真机数据支撑）**：S4/H14 3A 预热 → ZSL/reprocess（复用环形缓冲先例）→ H12 `StreamUseCase` →
  H6 画质档位策略 → 把色调曲线/CCM 搬进 Halide 做向量化（把 `process_pixel` 的标量逐像素变成每 8 像素向量）。

---

## 4. 问题二：HDR+ 色彩管线是否正确

### 4.1 色彩链路还原（先确认事实，再判对错）

**Halide 侧**（`hdrplus_pipeline_generator.cpp`，四个变体 fast/raw/high/single 都由它生成，见 `CMakeLists.txt:144-201`）：

| 阶段 | 位置 | 输出空间 |
|---|---|---|
| align + merge | `:47-50` | Bayer RAW |
| `black_white_level` | `:60,191-199` | 归一化到 16 bit 的 Bayer（`(v−bp)×65535/(wp−bp)`） |
| `apply_lsc` | `:61,144-189` | 同上 × LSC 增益，**含每通道非线性肩部压缩（knee=50000）** |
| `white_balance` | `:62,201-205` | **空实现（no-op）**，传入的 `wb_*` 完全没用上 |
| `demosaic` | `:65,212-245` | 未白平衡的线性 sensor RGB |
| `chroma_denoise` | `:71-76,319-330` | 同上（默认档：7×7 双边滤波 + 15×15 高斯） |
| `srgb()` / tone / gamma | `:332-337`（定义了但**未接入数据流**） | — |

**结论：Halide 输出 = LSC 已校正、黑白电平已归一化、已去马赛克、未白平衡的线性 sensor RGB。**

**ColorPipe 侧**（`process_and_save_image`，`sourceColorSpace=1`）：每像素依次做
`×wb`（`:863-867`）→ 高光去饱和（`:869-880`，阈值 80%）→ 归一化 + `digitalGain`（`:886-889`）→
`×CCM` → `sRGB_D65→XYZ`（`:914`）→ `XYZ→Rec709_D65`（`:925`）→ 色调曲线（ACES/PBR/Uchimura/Luma，`:930-945`）→
`sRGB OETF`（`:947-950`，查表 `:534-542`）→ 对比度/饱和度/HSWB（`:962-1001`）→ 可选 3D LUT。

所以 **JPEG 主路径在数学上是自洽的**：WB 一次、CCM 一次、色调曲线一次、sRGB OETF 一次；
由于 Rec709 与 sRGB 同基色，`sRGB→XYZ→Rec709` 是近似恒等（轻微数值往返，属可删的冗余，不是错误）。
`CameraFragment.kt:1776-1779` 的 CCM 取值顺序也已核对：`ColorSpaceTransform.getElement(column,row)`
在 AOSP 中即 `mElements[row*3+column]`，因此**没有转置错误**（这条曾是最可疑的候选，已排除）。

### 4.2 缺陷清单

#### C1【高】linear DNG 的 `BaselineExposure` 被截断，DNG 比 JPEG 暗 1.5–2.5 EV

**证据**：`ColorPipe.cpp:1431-1432`

```cpp
float safeBaselineExposure = std::clamp(baselineExposure, 0.0f, 0.5f);   // ← 上限 0.5 EV
TIFFSetField(tif, TIFFTAG_BASELINEEXPOSURE, safeBaselineExposure);
```

调用方传入的是 `baselineExposure = std::log2(digitalGain)`（`HdrPlusJNI.cpp:348,562`），
而 `digitalGain = 基准曝光 / 实际曝光`，其欠曝因子最小为 `FACTOR_EV_MINUS_3 = 0.125f`
（`ExposureUtils.kt:18-21,155-160`）→ **digitalGain 常见 2–8×（1–3 EV）**，极端更大。

**为什么错**：DNG 规范中 `BaselineExposure` 的单位是 EV，用于移动默认渲染的曝光零点（DNG 1.7 p.35：
"Positive values result in brighter default results"）。App 写入 DNG 的像素**没有**乘 `digitalGain`
（Halide 输出直接以 0..65535 线性写入，`HdrPlusJNI.cpp:349/563`），却只声明 ≤0.5 EV，
于是转换器只补 0.5 EV，而 JPEG 路径通过 `gain` 参数补足了 `log2(digitalGain)`。

**症状**：同一张照片，JPEG 正常而 LinearRaw DNG 在 Lightroom/ACR/rawpy 里明显偏暗（典型 −1.5～−2.5 EV），
用户会以为"RAW 拍废了"。**这是最容易复现、最影响交付的一项。**

**修复方向**：`clamp(baselineExposure, -2.0f, 4.0f)`，或按规范把逐张增益写进 `AnalogBalance`；
并与 `RawVideoExporter.kt` 的同类处理对齐。**验证**：拍同一场景的 JPEG+DNG，在参考转换器里比较亮度。

#### C2【高】"Arri LogC3" 使用的不是 ARRI Wide Gamut 矩阵

**证据**：`ColorPipe.cpp:322-326`（`M_XYZ_to_AlexaWideGamut_D65`，`targetLog==1` 时经 `:919` 使用），
取景器侧同一份数值复制在 `LutSurfaceProcessor.kt:86-90`。

**主控独立复算**（`python3` 反推矩阵隐含原色）：

| | 代码矩阵隐含 | ARRI Wide Gamut 3（LogC3 的配套色域） |
|---|---|---|
| R 原色 | (0.6840, 0.3130) | (0.6840, 0.3130) ✅ |
| G 原色 | (0.2210, **0.7600**) | (0.2210, **0.8480**) ❌ |
| B 原色 | (0.1360, **0.0570**) | (0.0861, **−0.1020**) ❌ |
| 与按 AWG3 原色推导的 XYZ→RGB 最大元偏差 | **0.3514**（第一行 1.9923 vs 1.7889） | — |

**为什么错**：LogC3 曲线只在与 ARRI Wide Gamut 原色配对时才有确定的色彩含义（ARRI 白皮书把 EI/中灰映射与色域绑定），
写错原色意味着 JPEG 里的三通道不是 AWG 坐标，任何按 LogC3/AWG 做的 IDT/转换都会偏色。
**修复**：换成按 AWG3 原色推导的矩阵
`1.78894 -0.48250 -0.20006 / -0.63988 1.39647 0.19444 / -0.04154 0.08235 0.87904`（D65，主控复算值），
并配合下方 C6 的 ICC 标注。同类但幅度小的问题：`M_XYZ_to_VGamut_D65`（`:334-338`）隐含 B 原色 y=−0.050，
公开 V-Gamut 为 (0.100, −0.030)，最大元偏差 0.0406。

#### C3【高】13 个 Log 选项中有 5 个未实现，且取景器与导出结果互不相同

**证据**：`SettingsFragment.kt:983-997` 的 `LOG_CURVES` 共 **13** 项（索引 0–12，其中 8=Canon Log 2、9=Canon Log 3、
10=N-Log、11=D-Log、12=Log3G10）；而 `ColorPipe.cpp:568-584` 的 `apply_log()` **只有 case 1..7**，
其余落到 `default: return srgb_oetf(x)`，色域分支 `:918-926` 也走 `default: M_XYZ_to_Rec709_D65`。
取景器侧 `LutSurfaceProcessor.kt:698-700` 的 `else` 却是 `applyAcesFit(...)`，且 `:142` 把 N-Log/D-Log 归到 **Rec2020**。

**症状**：选择这些 Log 时，导出得到的是"sRGB OETF + Rec.709"，取景器给的是"ACES fit + Rec2020"——两边都不对且互不相同，
按真实 Log 曲线设计的 LUT 套上去必然严重偏色。**修复**：补齐曲线与色域表（单一数据源），或先在 UI 隐藏未实现项。

#### C4【高】DNG 内嵌预览缺色彩矩阵、且用 2.2 幂而非 sRGB OETF

**证据**：`ColorPipe.cpp:1469-1511` 为 DNG 写两张内嵌 JPEG 预览（512、2048），像素来自 `make_preview_rgb8()`：
`ColorPipe.cpp:1317-1325` 只做 `clamp((sample*wb/65535)*gain,0,1)` 后 `pow(linear, 1/2.2)`——
**全程没有应用 CCM/色域矩阵**，也没有色调曲线。而 DNG 规范规定彩色预览的 `PreviewColorSpace` 默认值是 **sRGB**
（DNG 1.7 p.53），这里的像素却是"仅白平衡的相机原生 RGB"。

**症状**：系统相册、文件管理器缩略图、部分 DNG 阅读器的默认视图都会偏色，与 App 的 JPEG 不一致；
用户打开 Lightroom 又"正常"，容易误判为 DNG 损坏。
**修复**：预览走与主图相同的色彩链（`CCM → 线性 sRGB → 引擎 → srgb_oetf`），或显式写 `PreviewColorSpace` + 色彩矩阵。

#### C5【高/中】RAW 域逐通道非线性软肩压缩写进了声明为线性的 DNG

**证据**：`hdrplus_pipeline_generator.cpp:178-187`（`knee=50000.0f`，`compressed = knee + range*(excess/(excess+range))`），
发生在 LSC 之后、**去马赛克之前**、**逐 CFA 通道**；该数据随后被 `HdrPlusJNI.cpp:349/563` 直接写进 DNG，
而 DNG 声明 `PHOTOMETRIC_LINEAR_RAW`（`ColorPipe.cpp:1371`）、`WhiteLevel=65535/BlackLevel=0`（`:1385-1389`）。

**为什么错**：DNG 的处理模型要求 raw→线性参考值是线性的（DNG 1.7 §"Mapping Raw Values to Linear Reference Values"：
0.0=无光、1.0=饱和；Rescaling 之后只有 clipping，且建议早期阶段保留负值）。代码在 0.76 满量程以上引入非线性
（满量程处约 −0.182 EV，65535→57767.5），且通道间独立压缩 → 高亮区通道比例被改变（色相/饱和度漂移）；
中性场景下 G 通道数值最大、最先进入 knee，高光会偏品红。
**修复**：把软肩移到渲染域（`ColorPipe` 的色调环节），或至少在 DNG 路径输出未压缩的线性数据。

#### C6【中】一致性问题（同一 App 内多套色彩来源）

| 编号 | 问题 | 证据 |
|---|---|---|
| C6a | **三套色彩模型并存**：HDR+ 用 Camera2 `COLOR_CORRECTION_TRANSFORM`；DNG 回放/多摄/视频导出用 LibRaw `cam_xyz`；RAW 视频用 `ForwardMatrix` 插值 → 同一次拍摄的 HDR+ JPEG、单帧 JPEG、多摄、RAW 视频帧色彩不一致（肤色、天空尤其明显） | `ColorPipe.cpp:914`；`native-lib.cpp:203-209,281-305`；`rawvideo/ColorMath.h:23-114` |
| C6b | **取景器与导出不同源 → 二次 tone mapping**：`LutSurfaceProcessor` 的输入是 ISP 处理过的**预览**画面，shader 里先 `srgbToLinear` 再套引擎/Log/LUT；而导出是 raw 线性数据只做一次 tone mapping。且 shader 完全没有 contrast/saturation/highlights/shadows/whites/blacks 的实现（导出会应用这些调整） | `CameraFragment.kt:1184,1202,3853,3874`；`LutSurfaceProcessor.kt:719-771,544-788`；`ColorPipe.cpp:962-1001` |
| C6c | **除 DNG 外无 ICC / EXIF `ColorSpace`**（全仓 `TAG_COLOR_SPACE`/`ICC_PROFILE` 零命中，TIFF 连色彩标签都没有）。默认 sRGB 输出尚可，但选择 AWG/Rec2020/S-Gamut3.Cine/V-Gamut 或挂宽色域 LUT 后，文件里没有任何色域说明 → 查看器按 sRGB 解释必然严重偏色 | `ColorPipe.cpp:1158-1260`；`ImageSaver.kt:386-427` |
| C6d | **两个 DNG 写出器策略不一致**：自研 `write_dng` 只写 `ColorMatrix1`+`AsShotNeutral`；`RawVideoExporter` 写全 `ColorMatrix1/2 + CalibrationIlluminant1/2 + ForwardMatrix1/2`；平台 `DngCreator` 还会写 `BaselineExposure`（来自 `POST_RAW_SENSITIVITY_BOOST`）与动态黑电平 | `ColorPipe.cpp:1353-1422`；`RawVideoExporter.kt:1565-1586`；AOSP `DngCreator` JNI |
| C6e | **未用动态黑电平**：仍读静态 `SENSOR_BLACK_LEVEL_PATTERN`，平台 `DngCreator` 会优先使用 `SENSOR_DYNAMIC_BLACK_LEVEL`/`SENSOR_DYNAMIC_WHITE_LEVEL` → 暗部基座偏差 `[需验证]` | `CameraFragment.kt:3405-3414`；AOSP 元数据定义 |
| C6f | **`COLOR_CORRECTION_GAINS` 未按 G 归一化**：AOSP 只保证 [1,3] 不被裁剪、并未规定 G=1；若设备整体缩放增益，则亮度与高光阈值都会错位 `[需验证]`（建议补日志确认设备行为） | `HdrPlusJNI.cpp:411-413`；`ColorPipe.cpp:863-867` |
| C6g | **缺失 `COLOR_CORRECTION_TRANSFORM` 时使用人为兜底 CCM**（`2,-1,0 / -0.5,2,-0.5 / 0,-1,2`），而该 key 在 Camera2 中是 Optional → 缺失时会严重过饱和 `[需验证]` | `CameraFragment.kt:1760,3392-3396` |
| C6h | **`shift_bayer_to_rggb` 存在越界读/1 像素平移**：GRBG/GBRG/BGGR 分支用 `x+1`/`y+1` 且无边界处理 → 边缘 1 px 色边，且整体相位平移 1 px `[需验证]`（Halide 是报错还是静默读相邻内存取决于 target/断言） | `hdrplus_pipeline_generator.cpp:339-346` |

#### C7【低】细节与死代码

- 高光去饱和用 **Rec.601** 权重（`ColorPipe.cpp:876`），同文件饱和度用 Rec.709（`:970`）→ 权重体系不统一，且作用在**线性**值上。
- 四个色彩引擎的色相保证不一致：`apply_aces_fit` 是**逐通道**施加（`:471-477`），其余三个按亮度施加（`:361-389,403-423,443-463`）。
- `ColorPipe.h:64` 注释称 `ACES_FIT` 是 "Original Darkbag default"，但 Kotlin 默认是 0 = `PBR_NEUTRAL`（`CameraFragment.kt:1968/3611/4103`）→ 注释与实现不符。
- `enableMemoryColor` 是**完全死掉的参数**（Kotlin 三处调用点都传 `false`：`CameraFragment.kt:1967/3610/4102`；native 除日志外未使用）。
- `useSensorColorMatrix` 硬编码 `false`（`CameraFragment.kt:3403`）并在 JNI 被 `(void)` 丢弃（`HdrPlusJNI.cpp:379`）；`exportMatrixAB=false`（`:3473`）→ A/B 对比功能实际不可用。
- `sRGB→XYZ→Rec709` 是冗余往返（Rec709 与 sRGB 同基色）→ 可直接跳过。
- LOG/LUT 导出把**对数编码**数据写进没有色彩标记的 JPEG（靠 UserComment 里的 `editConfig` 供自家查看器还原），外部工具会看到"发灰"的图，建议文档化。

### 4.3 问题二的直接回答

> **"当前 HDR+ 处理管线的色彩处理是否正确？" → 主链正确，整体不完全正确。**
>
> - 结构层面**正确**：`WB → CCM → 线性 sRGB → XYZ(D65) → 目标色域 → 色调曲线 → sRGB OETF`，
>   每级恰好一次，白点自洽（D65 链），CCM 语义/行主序与 AOSP 一致，没有双重处理——这部分经多轮数值核验，
>   **可以认为骨架没问题**。
> - 实现层面**有 5 项确定性缺陷**（C1 `BaselineExposure` 截断、C2 AWG 矩阵错误、C3 5 个 Log 未实现且预览/导出不一致、
>   C4 DNG 内嵌预览缺矩阵、C5 RAW 域非线性压缩），其中 **C1/C2/C3 是纯代码即可判定、无需真机**的确定性错误，
>   且都会产生用户可见的亮度/色彩不符。
> - 另有 8 项一致性问题（C6）与若干死代码（C7），它们不一定"错"，但会让同一场景在 HDR+ / 单帧 / 多摄 / RAW 视频 /
>   取景器之间呈现不同色彩。
>
> **置信度**：代码级事实 **高**（关键结论均经主控独立复算或规范原文核对）；用户可见量级 **中**（无真机样张，
> C1/C2 的幅度可静态推算，C4/C5 的可见度依赖场景）。

### 4.4 如何复核（建议的真机验证清单）

1. **DNG 契约自检（无需看图，一行公式）**：对一张新拍的 HDR+ DNG 计算 `ColorMatrix1 · XYZ(校准光源白)`，
   应等于 `AsShotNeutral`（D65 时 `XYZ=(0.9505,1.0,1.0890)`）。当前实现满足该契约（见 §4.5）。
2. **曝光一致性（验证 C1）**：同一场景拍 JPEG+DNG，在 Lightroom/rawpy 里比较默认渲染亮度；
   预期 DNG 比 JPEG 暗 `log2(digitalGain) − 0.5` EV；把 `clamp` 上限放开后再比。
3. **ColorChecker**：D65 与 A 光源各一组，检查 6 个灰阶块中性度（a\*/b\*）与 ΔE00；
   对比 HDR+ JPEG / HDR+ DNG / 非 HDR+ DNG / 单帧 JPEG 四种产物（用于 C6a）。
4. **Log 模式（验证 C3）**：分别导出 Arri LogC3 / N-Log / Canon Log 2，与官方 LUT/IDT 对齐；
   同时截取取景器画面与导出帧比较（用于 C6b）。
5. **缩略图（验证 C4）**：把 DNG 放进系统相册，与 App 内 JPEG 对比缩略图颜色。
6. **高光（验证 C5）**：拍高饱和高光，比较 DNG 与 JPEG 的高光色相。

### 4.5 验证记录：一个被推翻的高危假设（重要，防止误改）

分析初期（主控侧）曾判定 `ColorPipe.cpp:1414-1419` 把 `AsShotNeutral` 乘进 `ColorMatrix1` 是"双重白平衡"缺陷，
并建议删除这 6 行。**该结论经独立复核后被推翻，请不要删除这段代码。** 记录如下：

- **规范契约（DNG 1.7 §6，p.98）**：`XYZtoCamera = AB * CC * CM`，`CameraNeutral = XYZtoCamera * XYZ`。
  即文件必须满足 `CM · XYZ(校准光源白) = AsShotNeutral`。当前实现恰好满足：
  `CM·XYZ(D65) = diag(ASN)·CCM⁻¹·M_XYZ→sRGB·XYZ(D65) = diag(ASN)·(1,1,1) = ASN`
  （因 Camera2 的 CCM 保白点：`CCM·(1,1,1) = (1,1,1)`）。主控数值复算：ASN=(0.6,1,0.31) → `CM·XYZ(D65) = (0.6,1.0,0.31)` ✅
- **读端为什么不会重复施加**：LibRaw 的 `cam_xyz_coeff()`（`libraw/src/utils/utils_dcraw.cpp:282-300`）会把
  `cam_rgb = cam_xyz·xyz_rgb` **按行归一化**，行和 `num = ASN`（因为 `CCM⁻¹` 的行和为 1），
  于是它派生的 `pre_mul = 1/num` 与 DNG 标签派生的 `pre_mul = 1/AsShotNeutral`（`tiff.cpp:1750-1755`）
  **恰好相等**，两个因子精确抵消；最终渲染 = `CCM·diag(1/ASN)·raw`，与 App 自身的"先 WB 再 CCM"链一致。
  主控独立复算：两条路径差 **2.2e-16**（`/home/maary/Build/Darkbag/.tmp/verify/asn.py`）。
- **反证**：若按当初的建议删除行缩放，则 `CM·XYZ(D65) = (1,1,1) ≠ ASN`，破坏规范契约，
  规范的无-FM 路径（读端反解白点后做色度适应）会给出偏色结果。
- **结论**：该处**不是缺陷**；`CalibrationIlluminant1=21`、`AsShotNeutral=1/wb`、行缩放三者必须同时成立、一起改。

> 教训（供后续审计复用）：DNG 的 `ColorMatrix1` 与 `AsShotNeutral` 是**一对耦合的契约**，
> 单独看 `ColorMatrix1` 的数值无法判断对错；必须用 `CM·XYZ(校准白) == ASN` 这条不变量去验证，
> 并且要看**读端实现**（dcraw/LibRaw 的行归一化会与 ASN 抵消），而不是只做纸面上的"矩阵 × 向量"推演。

## 5. 与最新 Android 规范/官方文档的对照

来源：本机 `android docs`（官方知识库），可按 `kb://` 复核。

- `kb://android/media/camera/camera2/capture-sessions-requests`
  - 会话创建后不可增删流；`Stream Use Case` 用于让 HAL 按用途优化（当前主路径未使用，见 H12）。
  - still 抓拍建议使用 `TEMPLATE_STILL_CAPTURE`（**已符合**，`CameraFragment.kt:4217/4348`）。
- `kb://android/media/camera/camerax/take-photo/zsl`
  - ZSL 依赖持续重复请求 + 环形缓冲 + reprocess 能力协商（当前完全没有，见 S4）。
- `kb://android/media/camera/camera2/multiple-camera-streams-simultaneously`
  - 并发流的 buffer 与内存开销随流数线性增长；`ImageReader` 应及时关闭/释放（当前 RAW reader `maxImages=8`
    且非 HDR+ 时仍常驻第三条分析流，见 S9、H8）。
- `kb://android/training/data-storage/shared/media`
  - `IS_PENDING` 用法正确（`ImageSaver.kt:650,658,701`）；但写完后重开文件改 EXIF 属额外往返（见 §3.4-2）。
- `kb://android/agents/skills/camera/camerax/references/thermals`
  - 长连拍/连续拍摄应结合热状态降级（当前无任何处理，见 H13）。
- AOSP `core/jni/android_hardware_camera2_DngCreator.cpp`
  - ColorMatrix1←`SENSOR_COLOR_TRANSFORM1`、AsShotNeutral←`SENSOR_NEUTRAL_COLOR_POINT`、
    CameraCalibration1←`SENSOR_CALIBRATION_TRANSFORM1`、CalibrationIlluminant1←`SENSOR_REFERENCE_ILLUMINANT1`
    → 自研 `write_dng` 应对齐这套语义（见 C1/C4）。
- Adobe **DNG 1.7.0.0 第 6 章**（本次已下载并逐句核对）
  - `XYZtoCamera = AB * CC * CM`；`CameraNeutral = XYZtoCamera * XYZ`；
    `D = Invert(AsDiagonalMatrix(ReferenceNeutral))` → 白平衡在色彩矩阵之外，**这是 C1 的判定依据**。
- **重要更正（来自文档专项审计）**：`TOTAL_CAPTURE_LATENCY` **在 android-33/34/35 的 SDK 源码与 NDK 头文件中均不存在**
  （0 命中），不要在任何实现或文档里引用它。真正可用的延迟量是 `SYNC_MAX_LATENCY`、`REQUEST_PIPELINE_MAX_DEPTH`、
  `REPROCESS_MAX_CAPTURE_STALL`、`StreamConfigurationMap.getOutputMinFrameDuration()`，
  以及唯一带数值承诺的 `USECASE_LOW_LATENCY_SNAPSHOT`（文档原话：延迟"does not exceed 200 ms"）。
- `kb://` / SDK 契约（来自文档专项审计，逐条可在本地 `android-35` sources 复核）：
  - `captureBurst` 官方保证"no other requests will be interspersed"——连拍必须用它（**当前代码已正确使用**，`CameraFragment.kt:4425`）。
  - `*_MODE_HIGH_QUALITY` 会让 stall 变得**不确定**，连拍期间必须避免（当前用 `AE_MODE_OFF` + 固定 ISO/曝光，**符合**）。
  - `ImageReader.acquireNextImage()` 的官方 Warning 直接点名"ever-increasing delay, followed by a complete stall"——
    连拍必须用它（**当前 HDR+ 路径已正确使用**，`CameraFragment.kt:4386`），但 `maxImages=8` 需要按连拍长度重新评估。
  - `CameraCaptureSession.prepare(Surface)` 可预分配输出 buffer，官方明确指出否则"bursts take longer than desired"（**当前未使用**）。
  - 保存链路：官方立场是顺序 I/O 下 direct path 与 MediaStore 性能相当、随机读写时 direct path 可能慢一倍，
    推荐 `IS_PENDING` + 单次 `openFileDescriptor(uri,"w")`——与当前"写临时文件→copyTo→重开 URI 改 EXIF"的三趟 I/O 相反。
- **HDR+ 论文（Hasinoff et al., SIGGRAPH Asia 2016）第 6 节 13 步顺序**（文档专项已用 `pdftotext` 提取官方 PDF 全文核对）：
  black-level → LSC → WB → demosaic → chroma denoise → **"Color correction converts the image from sensor RGB to
  linear sRGB using a 3x3 matrix supplied by the ISP"** → DR compression → dehaze → **"concatenating an S-shaped
  contrast-enhancing tone curve with the standard sRGB color component transfer function"** → CA → sharpen → hue/sat → dither；
  第 1–8 步全在线性域。→ 这**印证**了本管线"Halide 输出线性 sensor RGB + ColorPipe 里 WB 后接 CCM、再 tone、再 sRGB OETF"的结构。
  同时纠正常见误传：论文把 local Laplacian 列为"太贵而排除"，且全文没有 "2D LUT" 字样——不要引用这两个说法。
- AOSP `DngCreator` 的 `BaselineExposure` 取自 `log2(CONTROL_POST_RAW_SENSITIVITY_BOOST)`，黑电平优先使用
  `SENSOR_DYNAMIC_BLACK_LEVEL`——这正是自研 `write_dng` 应对齐、当前未对齐的地方（见 C1/C6d/C6e）。

- 与官方指引冲突/未采用的代码点（汇总）：未用 `StreamUseCase`；still 请求未带 preview target 也无 ZSL；
  `RAW ImageReader maxImages=8` 偏大；保存后重开文件改 EXIF；无热管理；burst 前未调用 `prepare()`。

---

## 6. 未决问题（读代码无法确认，需真机/实测）

1. 各阶段真实耗时占比（**先修埋点**，再用 Perfetto/simpleperf 定位）。
2. 前摄镜像的最终产品语义：native 已按 `mirror` 翻转（`ColorPipe.cpp:1074`），Kotlin 又翻一次
   （`ImageSaver.kt:155-172`）→ 成片究竟是否被镜像，需样张确认（这可能是一个**功能 bug**，不只是性能问题）。
3. 连拍帧间隔：burst 未设 `SENSOR_FRAME_DURATION`，设备在满分辨率 RAW + `TEMPLATE_STILL_CAPTURE` 下的实际帧率未知
   （可用 `image.timestamp` 相邻差统计，`CameraFragment.kt:4396`）。
4. `canAcceptNewTask` 600 MB 门限在多个连拍同时在飞时的真实峰值 RSS。
5. DNG deflate 的第三方兼容性抽检。
6. 对齐抽样/增量 SAD 对暗部信噪比与运动伪影的实际影响（需 A/B 样张）。
7. 高光非线性压缩（C3）造成的偏色幅度（需高光样张量化）。

---

## 7. 交付物与复现

| 文件 | 内容 |
|---|---|
| `docs/HDRPLUS_PIPELINE_ANALYSIS.md` | 本报告（合并后的最终结论） |
| `.agents/audit/perf_single_shot.md` | 非 HDR+ 单帧链路性能审计（阶段表 / 15 项瓶颈 / 15 项优化 / 官方依据 / 不确定项） |
| `.agents/audit/perf_hdrplus.md` | HDR+ 连拍链路性能审计（含耗时账本估算与 Top7 优先级） |
| `.agents/audit/color_hdrplus.md` | HDR+ 色彩正确性审计（独立结论，供交叉验证） |
| `.agents/audit/docs_reference.md` | 官方文档与标准参考（kb:// 与外部规范引用清单） |
| `.agents/audit/lead_evidence_notes.md` | 主控 agent 的原始证据笔记（⚠ 其中 E2 的 DNG 结论已被 §4.5 推翻，阅读时以本报告为准） |
| `.tmp/verify/asn.py` | 主控用于裁决 DNG `ColorMatrix1`↔`AsShotNeutral` 争议的独立数值复算脚本 |
| `.dsh/skills/android-camera2-darkbag-audit/SKILL.md` | 项目级分析 skill（含 `android docs` 在沙箱内的可用姿势），供后续复用 |
| `.agents/skills/camerax/` | 官方 Android camera skill（`android skills add camerax` 安装） |

复现要点：`android docs fetch kb://…` 需要
`export HOME=<workspace>/.tmp/androidhome; export JAVA_TOOL_OPTIONS=-Duser.home=$HOME`（沙箱内 `~/.android` 只读）。
本次未修改任何 app 源码（`git diff` 为空）。
