# Darkbag Developer & Agent Architectural Guidelines

Welcome to Darkbag! This file serves as the definitive reference and strict architectural contract for all AI coding agents (Antigravity, Google Jules, and subagents) as well as human contributors working in this repository.

---

## 1. Core Architecture & Architectural Invariants (不可逾越的四大架构红线)

Darkbag has undergone a comprehensive architectural decoupling to eliminate God-Fragment antipatterns, primitive parameter explosion, and pipeline-mode coupling. When implementing features, refactoring code, or fixing bugs, **you MUST strictly adhere to the following four architectural invariants without exception**:

### Invariant 1: Pipeline-Agnostic Output (`CaptureSink` Contract) & Unified Compressed DNG
* **Rule**: The computational photography and color pipelines (`HdrPlusProcessingService`, `ColorProcessor`, and native C++ `ColorPipe`) **MUST NEVER** have knowledge of output destinations. Furthermore, all DNG exports across the entire codebase MUST route through the unified native `write_dng` engine.
* **Prohibitions**:
  - **NEVER** directly query or insert into `MediaStore` or `ContentResolver` inside processing services or the native pipeline.
  - **NEVER** hardcode mode-specific file branching (e.g., `if (isHalfFrame) ... else ...`) inside `HdrPlusProcessingService`.
  - **NEVER** instantiate platform `android.hardware.camera2.DngCreator` for saving RAW files. `DngCreator` writes uncompressed 24MB+ sensor data and lacks Darkbag's OpcodeList2 GainMap calibration.
* **Standard Pattern**:
  - Output handling is delegated entirely through the `CaptureSink` interface (`prepareTargets`, `onImageExported`, `onRawExported`, `onComplete`, `onError`).
  - Standard single/burst captures use `DirectMediaStoreSink` for zero-copy sub-second T2 delivery.
  - Staged captures (e.g., Half-Frame Frame 1) use `HalfFrameCacheSink` to stage temporary files without polluting `MediaStore`.
  - All RAW/DNG file exports (Single RAW, Burst, Half-Frame, Multi-Camera) MUST use `ColorProcessor.writeRawImageToDng` / native `write_dng` with 16-bit Lossless JPEG compression (`dngCompressionMode = 0`), halving storage size and disk I/O latency.

### Invariant 2: Domain Parameter Orthogonalization (`CaptureTaskSpec`)
* **Rule**: Inter-layer invocations (UI → Coordinator → Service → JNI → C++) must pass cohesive, orthogonal domain aggregates instead of loose primitive parameter lists.
* **Prohibitions**:
  - **NEVER** declare or expand functions taking 10+ primitive arguments (e.g. `whiteLevel`, `blackLevel`, `wb`, `ccm`, `lsc`, `lutPath`, etc.).
* **Standard Pattern**:
  - `HardwareProfile`: Static camera sensor constants (black level, white level, CFA, color matrices) cached per camera session.
  - `CaptureFrameMetadata`: Per-frame dynamic exposure, ISO, and optical calibration (LensShadingMap).
  - `RenderRecipe`: Artistic tone curves, film simulation profiles, 3D LUT paths, and output format switches (JPEG/DNG).
  - `CaptureTaskSpec`: Unified immutable execution descriptor combining the three parameter tiers, `CaptureSink`, and timing trackers.

### Invariant 3: Shooting Mode Strategy Isolation (`CaptureModeCoordinator`)
* **Rule**: All shooting mode behaviors (Normal, Half-Frame, Multi-Camera, etc.) must be encapsulated as isolated strategies implementing `CaptureModeCoordinator`.
* **Prohibitions**:
  - **NEVER** write mode-checking branches inside `CameraFragment` (e.g., `if (isHalfFrameMode) ... else if (isMultiCameraMode) ...`).
* **Standard Pattern**:
  - Shutter button taps dispatch directly to `activeCoordinator?.onShutterTriggered(timing)`.
  - All coordinators MUST trigger `host.showShutterVisuals()` and notify `HdrPlusRequestManager` (`onTaskStarted` / `onForegroundTaskFinished` / `onTaskFinished`) to guarantee synchronized shutter dot rotation, tactile locking, and gallery thumbnail loading animations.
  - Dynamic button rotation dispatches to `activeCoordinator?.getShutterDotRotation(deviceOrientationDegrees)`.
  - Mode switches and multi-step state machines (e.g., Half-Frame step 0 → step 1 → step 0) live entirely inside the respective coordinator (`NormalModeCoordinator`, `HalfFrameModeCoordinator`, `MultiCameraModeCoordinator`).
  - New modes must be added by implementing `CaptureModeCoordinator` and registering with `ModeCoordinatorFactory`.

### Invariant 4: Headless Camera Session & State-Driven MVI UI (Zero UI Thread Blocking)
* **Rule**: Camera2 hardware stream lifecycle is strictly encapsulated in `Camera2SessionController`. The UI is purely passive and state-driven.
* **Prohibitions**:
  - **NEVER** execute Camera2 hardware IPC (`session.capture`, `session.captureBurst`, or `createCaptureRequest`) synchronously on the UI / Main Thread.
  - **NEVER** execute synchronous GPU frame buffer readback (`viewFinder.bitmap` or full-resolution `TextureView.getBitmap()`) on the UI thread.
  - **NEVER** manipulate camera device opening/closing or session configuration directly from UI event handlers without concurrency synchronization.
  - **NEVER** mutate shared state directly from Fragment Views.
* **Standard Pattern**:
  - All capture requests (Single RAW, Burst, Multi-Camera) MUST be dispatched on the dedicated background `camera2Handler` thread (`Camera2Background`).
  - Intermediate viewfinder snapshots (e.g. Half-frame stage 1) must use downsampled dimensions (e.g. 1/4 resolution) or asynchronous `PixelCopy` to guarantee sub-millisecond execution.
  - `Camera2SessionController` manages the dedicated background `HandlerThread`, Camera2 callbacks, and state transitions (`Closed`, `Opening`, `Configuring`, `Active`, `Closing`), protected by `openCloseLock` (Semaphore).
  - `CameraViewModel` exposes immutable `StateFlow<CameraUiState>` for persistent UI state and `SharedFlow<CameraEffect>` for transient one-shot events.
  - `CameraFragment` observes state using `viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED)` and sends user actions via `CameraUserIntent`.

### Invariant 5: Mandatory Bento Matrix & Material 3 Expressive Design System (Bento 矩阵设计与 M3 Expressive 规范)
* **Rule**: All user interfaces across Darkbag (Camera Deck, Settings, Playground, ImageViewer, BottomSheets, and Dialogs) **MUST MANDATORILY ADOPT** the Bento Matrix design language and Material 3 Expressive container role specifications.
* **Prohibitions**:
  - **NEVER** use flat, monotonous 1dp borders as the primary distinction for major Bento tray containers.
  - **NEVER** introduce outer wrapping boxes or divider lines around floating Bento island headers (e.g., Playground top header).
  - **NEVER** write verbose, flamboyant, or marketing-heavy copywriting.
  - **NEVER** include redundant parenthetical explanations (e.g., `Sabre (Default 默认)`, `动态照片 (Motion Photo)`, `保存高质量 JPG (快速分享)`).
  - **NEVER** allow telemetry text to wrap to 3 lines or clip outside container boundaries.
* **Standard Pattern**:
  - **Container Roles**: Outer Bento trays MUST use semantic M3 Expressive container fills (`colorPrimaryContainer`, `colorSecondaryContainer`, `colorTertiaryContainer`, `colorErrorContainer`, `colorSurfaceContainerHigh`, `colorSurfaceContainer`) with `strokeWidth = 0` (or transparent stroke).
  - **Porcelain Inner Tiles**: Inner clickable blocks, switches, and tiles MUST use `?attr/colorSurface` (pure white porcelain in light theme) with `?attr/colorOnSurface` typography for crisp, high-contrast visual hierarchy.
  - **Dynamic Mode Reactivity**: Mode switches (Camera Bento Deck, Settings Half-Frame tray, Startup cards) MUST dynamically and immediately update their container background color to reflect active shooting modes (`colorTertiaryContainer` for Half-Frame, `colorSecondaryContainer` for Multi-Camera, `colorSurfaceContainer` for Normal).
  - **Concentric Geometry**:
    - Outer trays: 26dp (top-level cards) or 18dp (nested groups), standard 10dp contentPadding.
    - Inner blocks: 14dp standard radius, dynamic squircle morphing to 10dp with 1.5dp primary stroke when active/selected.
    - Uniform gutters: 8dp vertical row gap (`layout_marginBottom = "8dp"`), 8dp column gutter (`4dp + 4dp`).
  - **Plain & Professional Copywriting**:
    - Plain, concise, professional camera terminology (e.g., `保存 JPG`, `保存 DNG 底片`, `动态照片`, `记录地理位置`, `光学防抖`, `抗频闪`).
    - Two-line micro-telemetry: Line 1 short uppercase label (e.g., `BURST FRAMES`), Line 2 status/value (e.g., `5 Frames`), protected defensively by `android:maxLines="2"` and `android:ellipsize="end"`.
  - **Frameless Floating Islands**: Floating header cards MUST be standalone floating islands with elevation (e.g. 6dp) and `appBarLayout` set to transparent with 0 elevation and `translationZ`. Scrolling content (e.g. `RecyclerView`) MUST smoothly pass underneath the floating island without clipping.

---

## 2. Cross-Repository Boundaries (Darkbag vs. hdr-plus)

Refer to `.agents/rules/ARCHITECTURE_BOUNDARIES.md` and `docs/REPO_ARCHITECTURE_BOUNDARIES_HDRPLUS_VS_DARKBAG.md`:
1. **`app/src/main/cpp/hdr-plus`**:
   - Contains platform-agnostic computational photography algorithms (alignment, optical flow, merge, demosaic, Sabre).
   - **MUST NEVER** depend on Android SDK/NDK (no EGL, no `AHardwareBuffer`, no `SurfaceTexture`).
   - Must remain buildable and testable on desktop Linux/macOS.
2. **`app/src/main/`**:
   - Owns Android Camera2 lifecycle, GPU EGL pipelines, color grading, file serialization, and UI.

---

## 3. Verification & Quality Gates

Every code modification must satisfy the following gates before submission:
1. **Unit Tests**:
   - Run: `./gradlew testDebugUnitTest --rerun-tasks`
   - Must achieve **100% pass rate** across all test suites (including `pipeline`, `modes`, `viewmodel`, and `session` tests).
2. **Native Compilation**:
   - Run: `./gradlew :app:buildCMakeRelease[arm64-v8a]`
   - Zero compilation warnings or link errors.
3. **Architecture & Design Audit**:
   - Verify that changes do not introduce regressions against the 5 Architectural & Design Invariants.
   - For detailed design specifications, consult `docs/ARCHITECTURE_DECOUPLING_DESIGN.md`.

---

## 4. Multi-Agent Dual-Loop Workflow & Continuous Reviewer Sign-Off Policy

In any medium-to-large scale refactoring, cross-module UI overhaul, or architectural change, AI coding agents MUST strictly follow the dual-loop multi-agent review and continuous iteration process:

1. **Role Separation (实施与审查分离)**:
   - The primary implementer agent (or subagent) MUST NOT self-certify task completion.
   - A dedicated `design_and_code_reviewer` subagent MUST be defined and launched to evaluate the modifications before task conclusion.
2. **Dual-Perspective Audit Criteria (双重视角严格审查)**:
   - **Perspective 1: Design Fidelity & UX Quality**: Validates Material 3 Expressive container roles, porcelain contrast, concentric radii, spacing, frameless floating islands, and plain copywriting compliance (Invariant 5).
   - **Perspective 2: Code Quality & Architecture Invariants**: Validates compliance with Invariants 1-4 (CaptureSink, CaptureTaskSpec, CaptureModeCoordinator, Headless session zero UI blocking) and cross-repository boundaries.
   - **Perspective 3: Automated Quality Gates**: Executes `./gradlew testDebugUnitTest --rerun-tasks` (100% pass rate) and `./gradlew :app:buildCMakeRelease[arm64-v8a]` (0 errors).
3. **Continuous Revision Loop (闭环持续迭代直到 APPROVED)**:
   - If the reviewer subagent issues `REVISE_REQUIRED`, the implementer agent MUST address each finding with actionable code fixes.
   - The implementer then requests a re-audit from the reviewer subagent.
   - Changes may ONLY be committed and considered complete once the reviewer subagent issues an explicit **`APPROVED`** sign-off verdict.

---

## 5. Stage-Gated Atomic Commits & Remote Push Policy (分阶段原子性提交与即时推送规范)

To guarantee incremental traceability, branch safety, and guard against accidental progress loss, AI agents and contributors **MUST MANDATORILY ADOPT** the stage-gated atomic commit and push policy:

1. **Commit Upon Stage Completion (每完成一个阶段就立即提交)**:
   - Whenever a self-contained feature, refactoring phase, bug fix, or subagent iteration milestone is completed and verified by quality gates (tests pass, reviewer signs off), the agent **MUST promptly create a clean Git commit**.
   - **NEVER** accumulate multiple disparate development phases into a massive uncommitted working tree.
2. **Immediate Remote Synchronization (及时推送至远端)**:
   - Following each atomic stage commit, changes **MUST be pushed to the active remote branch** (`git push origin <branch>`) to maintain remote synchronization.
3. **Semantic & Clear Commit Copywriting (规范化语义提交信息)**:
   - All commits must follow semantic commit standards (`fix: ...`, `feat: ...`, `refactor: ...`, `test: ...`).
   - The commit message body must succinctly describe the root cause, architectural solution, and quality gate verification results.

