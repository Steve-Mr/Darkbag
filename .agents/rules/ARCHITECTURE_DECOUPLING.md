# Darkbag Camera Pipeline & Mode Decoupling Architecture Rule

When implementing, modifying, or reviewing code in `app/src/main/` or related test suites, all agents MUST enforce the following four architectural invariants:

---

## 1. Pipeline-Agnostic Output (`CaptureSink`)
- **Location**: `top.maary.darkbag.pipeline.sink.*`
- **Contract**: The computational and rendering pipeline (`HdrPlusProcessingService`, `ColorProcessor`, C++ `ColorPipe`) is strictly agnostic of output destinations.
- **Rules**:
  1. All output targets and completions MUST be managed via `CaptureSink`.
  2. Direct interaction with `MediaStore`, `ContentResolver`, or raw file saving inside `HdrPlusProcessingService` is **STRICTLY PROHIBITED**.
  3. Single-shot / Burst standard captures MUST use `DirectMediaStoreSink`.
  4. Multi-frame staged captures (e.g. Half-Frame) MUST use `HalfFrameCacheSink` so intermediate frames are not prematurely inserted into `MediaStore`.
  5. Any new output requirement (e.g., cloud upload, raw burst stacking export, video frame dump) MUST be implemented as a new `CaptureSink`.

---

## 2. Parameter Lifecycle Orthogonalization (`CaptureTaskSpec`)
- **Location**: `top.maary.darkbag.pipeline.model.*`
- **Contract**: Functional parameters are decoupled into 3 orthogonal lifecycle tiers:
  1. `HardwareProfile`: Session-level static lens calibration (CFA pattern, black/white levels, CCM, sensor dimensions). Cached per camera ID.
  2. `CaptureFrameMetadata`: Per-frame dynamic exposure state (exposure time, ISO, LensShadingMap).
  3. `RenderRecipe`: User aesthetic intent (film simulation, 3D LUT, tone curves, DNG/JPEG output flags).
- **Rules**:
  1. Never introduce methods with 10+ primitive arguments across service, presenter, or JNI boundaries.
  2. All capture requests must be encapsulated within `CaptureTaskSpec`.

---

## 3. Mode Strategy Engine (`CaptureModeCoordinator`)
- **Location**: `top.maary.darkbag.modes.*`
- **Contract**: Shooting modes (Normal, Half-Frame, Multi-Camera) are completely isolated strategies.
- **Rules**:
  1. `CameraFragment` MUST NOT contain mode-specific branching logic (`if (isHalfFrame) ... else if (isMultiCam) ...`).
  2. Shutter click events and dot rotation angles MUST be forwarded to `activeCoordinator?.onShutterTriggered(timing)` and `activeCoordinator?.getShutterDotRotation(...)`.
  3. Mode transitions, step counters, and custom `CaptureSink` injection are entirely owned by the coordinator implementation.
  4. New modes MUST implement `CaptureModeCoordinator` and be registered in `ModeCoordinatorFactory`.

---

## 4. Headless Camera Session & State-Driven MVI UI
- **Location**:
  - Session Engine: `top.maary.darkbag.camera.session.Camera2SessionController`
  - ViewModel & State: `top.maary.darkbag.viewmodel.*`
- **Contract**: Camera2 hardware stream lifecycle is headless; UI is reactive and unidirectional.
- **Rules**:
  1. Camera opening, closing, and session configuration are strictly managed by `Camera2SessionController` on a dedicated background `HandlerThread`, guarded by `openCloseLock` (Semaphore).
  2. `CameraFragment` observes `CameraViewModel.uiState` (persistent state) and `CameraViewModel.effects` (transient events) via `repeatOnLifecycle(Lifecycle.State.STARTED)`.
  3. Fragment views MUST NOT directly mutate hardware state or bypass the ViewModel.

---

## References
- Full Blueprint: `docs/ARCHITECTURE_DECOUPLING_DESIGN.md`
- Cross-Repo Boundaries: `.agents/rules/ARCHITECTURE_BOUNDARIES.md`
