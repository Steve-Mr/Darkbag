# Darkbag Developer & Agent Architectural Guidelines

Welcome to Darkbag! This file serves as the definitive reference and strict architectural contract for all AI coding agents (Antigravity, Google Jules, and subagents) as well as human contributors working in this repository.

---

## 1. Core Architecture & Architectural Invariants (不可逾越的四大架构红线)

Darkbag has undergone a comprehensive architectural decoupling to eliminate God-Fragment antipatterns, primitive parameter explosion, and pipeline-mode coupling. When implementing features, refactoring code, or fixing bugs, **you MUST strictly adhere to the following four architectural invariants without exception**:

### Invariant 1: Pipeline-Agnostic Output (`CaptureSink` Contract)
* **Rule**: The computational photography and color pipelines (`HdrPlusProcessingService`, `ColorProcessor`, and native C++ `ColorPipe`) **MUST NEVER** have knowledge of output destinations.
* **Prohibitions**:
  - **NEVER** directly query or insert into `MediaStore` or `ContentResolver` inside processing services or the native pipeline.
  - **NEVER** hardcode mode-specific file branching (e.g., `if (isHalfFrame) ... else ...`) inside `HdrPlusProcessingService`.
* **Standard Pattern**:
  - Output handling is delegated entirely through the `CaptureSink` interface (`prepareTargets`, `onImageExported`, `onRawExported`, `onComplete`, `onError`).
  - Standard single/burst captures use `DirectMediaStoreSink` for zero-copy sub-second T2 delivery.
  - Staged captures (e.g., Half-Frame Frame 1) use `HalfFrameCacheSink` to stage temporary files without polluting `MediaStore`.
  - Custom output workflows must provide a dedicated `CaptureSink` implementation injected via `CaptureTaskSpec`.

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
  - Dynamic button rotation dispatches to `activeCoordinator?.getShutterDotRotation(deviceOrientationDegrees)`.
  - Mode switches and multi-step state machines (e.g., Half-Frame step 0 → step 1 → step 0) live entirely inside the respective coordinator (`NormalModeCoordinator`, `HalfFrameModeCoordinator`, `MultiCameraModeCoordinator`).
  - New modes must be added by implementing `CaptureModeCoordinator` and registering with `ModeCoordinatorFactory`.

### Invariant 4: Headless Camera Session & State-Driven MVI UI
* **Rule**: Camera2 hardware stream lifecycle is strictly encapsulated in `Camera2SessionController`. The UI is purely passive and state-driven.
* **Prohibitions**:
  - **NEVER** manipulate camera device opening/closing or session configuration directly from UI event handlers without concurrency synchronization.
  - **NEVER** mutate shared state directly from Fragment Views.
* **Standard Pattern**:
  - `Camera2SessionController` manages the dedicated background `HandlerThread`, Camera2 callbacks, and state transitions (`Closed`, `Opening`, `Configuring`, `Active`, `Closing`), protected by `openCloseLock` (Semaphore).
  - `CameraViewModel` exposes immutable `StateFlow<CameraUiState>` for persistent UI state and `SharedFlow<CameraEffect>` for transient one-shot events (e.g., shutter animation, toast notifications).
  - `CameraFragment` observes state using `viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED)` and sends user actions via `CameraUserIntent`.

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
3. **Architecture Audit**:
   - Verify that changes do not introduce regressions against the 4 Architectural Invariants.
   - For detailed design specifications, consult `docs/ARCHITECTURE_DECOUPLING_DESIGN.md`.
