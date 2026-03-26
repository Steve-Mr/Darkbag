# ff6fceaa..HEAD Image Viewer 变更分析（聚焦 Camera Thumbnail -> Viewer）

## 1) 对比范围
- 基线提交：`ff6fceaa19fe666b61bb3b1aa71433f013294eef`（PR #143 的 merge commit）
- 当前提交：`HEAD`（当前分支最新）

## 2) ff6fceaa 实现了什么
从代码行为看，`ff6fceaa` 时期的 `ImageViewerFragment` 仍是**单一 Camera Gallery 模式**：
1. `loadImages()` 只调用 `repository.getGroupedImages()` 加载分组。
2. 无 `ViewerMode` 概念，无 `Studio/External` 分流。
3. 初始定位仅按 `jpgUri/dngUri/dngUri1/dngUri2` 完整 URI 精确匹配。
4. Viewer 的按钮与分页行为都围绕“真实分组”工作，不会创建“虚拟组”。

## 3) ff6fceaa..HEAD 新增了哪些功能（与本问题相关）
该区间引入了 Studio 及外部 RAW 支持，核心变化集中在 Viewer 入口与数据源：

### 3.1 模式分流（重大架构变化）
`ImageViewerFragment` 新增 `ViewerMode { CAMERA, STUDIO, EXTERNAL }`，并在 `loadImages()` 里根据参数/URI 推断模式。

### 3.2 三种数据源
- `CAMERA`：`repository.getGroupedImages()`
- `STUDIO`：`repository.getStudioGroups()`
- `EXTERNAL`：先空列表，再走“虚拟组”补齐

### 3.3 虚拟组机制
当 `targetUri` 不在当前分组列表中时，会构造 `ImageGroup`（包括外部 URI 或手动拼接 `u1|u2|layout`），再插入到列表首位。

### 3.4 Camera 入口参数变化
`CameraFragment` 的 thumbnail 跳转从旧版的 `actionCameraToImageViewer(uri)` 改为传参：
- `initialUri = uri.toString()`
- `onlyDarkbag = true`

## 4) 哪些修改最可能导致“Camera 进入后 viewer 基本不可用”

### 问题链路 A（最高概率）
1. Camera 入口传的是 MediaStore URI（通常形如 `content://media/external/images/media/<id>`）。
2. 新版 Viewer 曾使用 URI 字符串规则推断 `EXTERNAL`（`content://` 且不含 `darkbag`）。
3. 一旦进入 `EXTERNAL`，列表初始为空并依赖虚拟组，很多能力不再沿用 Camera 分组路径（分页、格式切换、组内行为都可能退化）。

> 这条链路解释了“进入后大部分功能不可用”的体感：Viewer 不是坏了，而是进了错误模式。

### 问题链路 B（中概率）
`onlyDarkbag=true` 后，Camera 路径会对 `getGroupedImages()` 结果做 `FILE_PREFIX` 过滤。
如果历史图片命名、导出路径或前缀策略不一致，可能出现：
- 列表只剩很少项/无项
- 初始定位失败后落入“虚拟组兜底”

### 问题链路 C（中低概率）
Studio 改造后，Viewer 内部状态机更复杂（编辑态、虚拟组、save-as/promote、studio delete 分支等），当 Camera 入口被误判到非 CAMERA 路径时，会触发与 Camera 预期不一致的控制分支。

## 5) 结论（先分析，不给过度冗余重构）
1. `ff6fceaa` 的 Viewer 是“单模式 + 真实分组”的稳定实现。
2. `ff6fceaa..HEAD` 的核心收益是“Studio/External 复用同一 Viewer”。
3. 当前问题不是单点 crash，而是**入口模式判定与数据源语义混用**导致的行为退化。
4. 需要把“入口意图（Camera/Studio/External）”与“数据装载策略（真实组/虚拟组）”解耦，避免互相覆盖。

## 6) 建议的最小化修复方向（避免冗余）
1. **模式来源只信显式参数**：`isStudioMode` / `onlyDarkbag` / `isExternalIntent`（新增），URI 只做辅助，不做一级判定。
2. **虚拟组仅作补丁，不改变主模式**：Camera 模式中即使出现虚拟组，也不能切换到 External 语义分支。
3. **把差异下沉到数据层**：用一个统一 `ViewerSource`（Camera/Studio/External）封装 group 查询与 fallback 规则，UI 层只消费 `List<ImageGroup>`。
4. **为 Camera 入口加回归用例**：
   - `content://media/...` + `onlyDarkbag=true` 必须走 CAMERA
   - 进入后可分页、可格式切换、可删除/返回（按 Camera 语义）
