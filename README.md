# Darkbag

English | [简体中文](README_ZH.md)

**Darkbag** is an experimental Android computational photography camera and RAW darkroom app built with Kotlin, Camera2, OpenGL ES / EGL, and native C++ / Halide pipelines.

> [!WARNING]
> **Experimental & "AS IS" Notice**  
> Darkbag is an exploratory, unstable personal project provided **"AS IS"** without warranty of any kind. Because Android devices vary significantly in Camera2 HAL, RAW sensor metadata, OpenCL/Vulkan, and MediaCodec implementations, crashes, color shifts, or device-specific quirks may occur. Please do not rely on it as your primary camera for irreplaceable moments.

> [!NOTE]
> **Origin & AI-Assisted Development**  
> Darkbag evolved from a fork of Google's official [android/camera-samples](https://github.com/android/camera-samples) (`CameraXBasic`). Since that fork, virtually all architecture, features, native pipelines, and UI code have been developed almost entirely with LLMs and AI coding agents — including **Jules**, **Antigravity**, **DeepSeek**, and similar tools.

---

## Features

- **Computational Photography & Native Pipelines**
  - **HDR+ Multi-Frame RAW**: Burst RAW alignment and merging powered by Halide AOT kernels (`Fast`, `Standard`, `High`, and `Streaming` pipelines).
  - **Sabre & Digital-Crop Super-Resolution**: Multi-frame super-resolution and crop-centered 3A metering/processing.
  - **RCD Demosaicing & GPU Acceleration**: Ratio Corrected Demosaicing and EGL/OpenGL ES accelerated color grading and rendering.
- **Color Science & Output Formats**
  - **Log Curves & Color Engine**: Built-in color engine and Log curve profiles (such as F-Log, F-Log2, S-Log3, Canon Log, Arri LogC3, V-Log, etc.).
  - **3D LUTs**: Import `.cube` 3D LUTs with real-time viewfinder preview and offline rendering.
  - **16-bit Lossless Compressed DNG & JPEG**: Unified native DNG writer with 16-bit lossless JPEG compression and `OpcodeList2` GainMap calibration, plus optional JPEG output.
- **Shooting Modes & Video**
  - **Manual / Pro Controls**: ISO, shutter speed, manual focus peaking & dial, exposure compensation (EV), OIS, anti-banding, and Zero-Shutter-Lag (ZSL) ring buffer.
  - **Half-Frame Film Mode**: Side-by-side / top-bottom two-shot stitching, optional date stamp, and random light-leak effects.
  - **Multi-Camera Simultaneous Capture (Experimental)**: Logical/physical multi-lens capture with cross-camera color harmonization and collage export.
  - **RAW Motion Photo**: Live photo capture with synchronized preview gain and HEVC/H.264 MP4 muxing.
  - **RAW Video**: Custom LZ4-compressed RAW video container (`RAWVID`) with live GL debayer preview and MP4 / CinemaDNG export.
- **Darkroom, Playground & UI**
  - **Non-Destructive Darkroom / Playground**: Import external `.dng` files (including share intents and `DocumentsProvider`) and adjust exposure, dynamic range, tone, white balance, and LUT recipes.
  - **Material 3 Expressive Bento UI**: Modular Bento deck, customizable viewfinder tiers, edge-to-edge layout, and Android Predictive Back gesture support.

---

## Requirements

- **OS**: Android 8.0+ (`minSdk 26`, `targetSdk 35`)
- **ABI**: `arm64-v8a`
- **Hardware**: A device supporting Camera2 `RAW_SENSOR` (`Level 3` / `Full` with RAW capability) is required for HDR+, DNG, and RAW video features.

---

## Build

Darkbag uses Git submodules for `libraw` and `hdr-plus`, and CMake fetches `Halide`, `LibTIFF`, and `libjpeg-turbo` during native configuration.

```bash
git clone --recursive https://github.com/Steve-Mr/Darkbag.git
cd Darkbag

# Build Debug APK
./gradlew assembleDebug

# Build Release APK (R8 enabled)
./gradlew assembleRelease
```

---

## Open-Source Acknowledgments

Darkbag stands on the shoulders of the following open-source projects and libraries:

| Project / Library | Role in Darkbag | License |
| :--- | :--- | :--- |
| [android/camera-samples](https://github.com/android/camera-samples) (`CameraXBasic`) | Original upstream fork base | Apache-2.0 |
| [timothybrooks/hdr-plus](https://github.com/timothybrooks/hdr-plus) & [Steve-Mr/hdr-plus](https://github.com/Steve-Mr/hdr-plus) | HDR+ alignment & merge Halide kernels | MIT |
| [Halide](https://halide-lang.org/) | AOT image processing compiler & runtime | MIT |
| [LibRaw](https://www.libraw.org/) | RAW image decoding & metadata parsing | LGPL-2.1 / CDDL-1.0 |
| [LibTIFF](https://gitlab.com/libtiff/libtiff) | TIFF/DNG container writing | libtiff |
| [libjpeg-turbo](https://github.com/libjpeg-turbo/libjpeg-turbo) | High-speed JPEG encoding/decoding | IJG / BSD-3-Clause / Zlib |
| [LZ4](https://github.com/lz4/lz4) | Fast lossless compression for RAW video | BSD-2-Clause |
| [stb_image_write](https://github.com/nothings/stb) | Lightweight image writing utilities | Public Domain / MIT |
| [AndroidX](https://developer.android.com/jetpack/androidx) & [Media3 ExoPlayer](https://developer.android.com/media/media3) | Jetpack Camera/Navigation/Lifecycle/DataStore & video playback | Apache-2.0 |
| [Material Components for Android](https://github.com/material-components/material-components-android) | Material 3 Expressive UI & Motion transitions | Apache-2.0 |
| [Glide](https://github.com/bumptech/glide) | Asynchronous thumbnail & image loading | BSD / MIT / Apache-2.0 |
| [Robolectric](https://robolectric.org/) | Local JVM Android unit test framework | MIT |

---

## License

Darkbag is licensed under the [Apache License 2.0](LICENSE).
