# Darkbag vs hdr-plus Architectural Boundary Rule

When implementing features, fixing bugs, or refactoring in this workspace:
1. **hdr-plus repository boundary (`app/src/main/cpp/hdr-plus`)**:
   - MUST ONLY contain platform-agnostic, portable computational photography algorithms (multi-frame alignment, optical flow, motion rejection, burst accumulation, noise filtering, demosaicing algorithms like RCD, Sabre super-resolution core).
   - MUST NEVER depend on Android SDK/NDK specific classes (No EGL, No AHardwareBuffer, No MediaStore, No SurfaceTexture).
   - Must remain buildable and testable on standalone desktop Linux/macOS environments.

2. **Darkbag repository boundary (`app/src/main/`)**:
   - Owns camera hardware stream capture, buffer scheduling queues, and foreground services.
   - Owns GPU offscreen EGL pipelines, AHardwareBuffer zero-copy mappings, and hardware JPEG encoders.
   - Owns artistic darkroom color science (CCM, Log curves, 3D LUTs, OETF tone mapping).
   - Owns DNG / JPEG / EXIF file packaging and Android MediaStore persistence.

Always refer to `docs/REPO_ARCHITECTURE_BOUNDARIES_HDRPLUS_VS_DARKBAG.md` for full contract details.
