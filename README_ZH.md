# Darkbag

[English](README.md) | 简体中文

**Darkbag** 是一款基于 Kotlin、Camera2、OpenGL ES / EGL 以及 C++ / Halide 原生管线构建的实验性 Android 计算摄影相机与 RAW 暗房应用。

> [!WARNING]
> **实验性与“按现状提供（AS IS）”声明**  
> Darkbag 是一个处于探索阶段的个人实验性项目，按 **“AS IS”（按现状提供）** 原则分发，不提供任何形式的稳定性或适用性担保。由于不同 Android 机型在 Camera2 HAL、RAW 传感器元数据、OpenCL/Vulkan 驱动及 MediaCodec 实现上差异巨大，应用可能出现崩溃、偏色或机型兼容问题。请勿将其作为不可重拍重要场合的主力相机。

> [!NOTE]
> **项目起源与 LLM 辅助开发声明**  
> 本项目最早 Fork 自 Google 官方的 [android/camera-samples](https://github.com/android/camera-samples)（`CameraXBasic`）示例仓库演变而来。自 Fork 之后，项目的绝大部分架构演进、功能实现、原生算法管线与界面代码几乎全部依靠大语言模型与 AI 编程工具完成，涉及的服务包括 **Jules**、**Antigravity**、**DeepSeek** 等。

---

## 功能概览

- **计算摄影与原生管线**
  - **HDR+ 多帧 RAW 融合**：基于 Halide AOT 算子的多帧 RAW 对齐与融合（支持 `Fast`、`Standard`、`High` 与 `Streaming` 管线）。
  - **Sabre 与数码裁切超分**：支持多帧超分辨率与裁切中心化的 3A 测光/处理。
  - **RCD 去马赛克与 GPU 加速**：RCD 去马赛克算法与基于 EGL / OpenGL ES 的色彩调色与渲染管线。
- **色彩科学与输出格式**
  - **Log 曲线与色彩引擎**：内置色彩引擎及多种 Log 曲线配置（如 F-Log、F-Log2、S-Log3、Canon Log、Arri LogC3、V-Log 等）。
  - **3D LUT 支持**：支持导入 `.cube` 3D LUT，提供取景框实时预览与离线冲印渲染。
  - **16-bit 无损压缩 DNG 与 JPEG**：统一的原生 DNG 编码器，支持 16-bit 无损 JPEG 压缩与 `OpcodeList2` GainMap 校准，并支持同步输出 JPEG。
- **拍摄模式与视频**
  - **专业手动控制**：支持 ISO、快门速度、手动对焦峰值与拨盘、曝光补偿（EV）、光学防抖（OIS）、抗频闪以及零快门延迟（ZSL）环形缓存。
  - **半格胶片模式（Half-Frame）**：支持横/竖拼图、可选日期印记与随机漏光效果。
  - **多摄同步拍摄（实验性）**：支持多镜头同步采集、跨摄色彩一致性校准与多图拼图导出。
  - **RAW 动态照片（Motion Photo）**：支持预览增益同步的实时动态照片采集与 HEVC / H.264 MP4 封装。
  - **RAW 视频录制**：支持基于 LZ4 压缩的自定义 RAW 视频容器（`RAWVID`）、实时 GL 解拜耳预览，以及 MP4 / CinemaDNG 导出。
- **暗房、Playground 与界面**
  - **非破坏性暗房 / Playground**：支持外部 `.dng` 导入（含系统分享与 `DocumentsProvider`），提供曝光、动态范围、影调、白平衡与 LUT 配方调节。
  - **Material 3 Expressive Bento 界面**：模块化 Bento 操控台、可定制取景器控件层级、Edge-to-Edge 全面屏适配与 Android 预测返回（Predictive Back）手势。

---

## 运行要求

- **系统版本**：Android 8.0+（`minSdk 26`，`targetSdk 35`）
- **指令集架构**：`arm64-v8a`
- **硬件要求**：需设备支持 Camera2 `RAW_SENSOR` 采集能力，方可使用 HDR+、DNG 及 RAW 视频等核心功能。

---

## 本地构建

本项目通过 Git Submodule 引入 `libraw` 与 `hdr-plus`，并在 CMake 配置阶段自动拉取 `Halide`、`LibTIFF` 与 `libjpeg-turbo`。

```bash
git clone --recursive https://github.com/Steve-Mr/Darkbag.git
cd Darkbag

# 构建 Debug APK
./gradlew assembleDebug

# 构建 Release APK（已开启 R8 优化）
./gradlew assembleRelease
```

---

## 开源项目致谢

Darkbag 的实现依赖于以下开源项目与第三方库：

| 项目 / 库 | 用途 | 开源协议 |
| :--- | :--- | :--- |
| [android/camera-samples](https://github.com/android/camera-samples)（`CameraXBasic`） | 项目最初的官方 Fork 起点 | Apache-2.0 |
| [timothybrooks/hdr-plus](https://github.com/timothybrooks/hdr-plus) & [Steve-Mr/hdr-plus](https://github.com/Steve-Mr/hdr-plus) | HDR+ 对齐与融合 Halide 核心算法 | MIT |
| [Halide](https://halide-lang.org/) | 高性能图像处理 AOT 编译器与运行时 | MIT |
| [LibRaw](https://www.libraw.org/) | RAW 图像解码与元数据解析 | LGPL-2.1 / CDDL-1.0 |
| [LibTIFF](https://gitlab.com/libtiff/libtiff) | TIFF / DNG 容器写入 | libtiff |
| [libjpeg-turbo](https://github.com/libjpeg-turbo/libjpeg-turbo) | 高速 JPEG 编解码 | IJG / BSD-3-Clause / Zlib |
| [LZ4](https://github.com/lz4/lz4) | RAW 视频流高速无损压缩 | BSD-2-Clause |
| [stb_image_write](https://github.com/nothings/stb) | 轻量级图像写入工具 | Public Domain / MIT |
| [AndroidX](https://developer.android.com/jetpack/androidx) & [Media3 ExoPlayer](https://developer.android.com/media/media3) | Jetpack 基础设施与视频播放 | Apache-2.0 |
| [Material Components for Android](https://github.com/material-components/material-components-android) | Material 3 Expressive 组件与动效 | Apache-2.0 |
| [Glide](https://github.com/bumptech/glide) | 异步缩略图与图片加载 | BSD / MIT / Apache-2.0 |
| [Robolectric](https://robolectric.org/) | 本地 JVM Android 单元测试框架 | MIT |

---

## 开源协议

本项目基于 [Apache License 2.0](LICENSE) 开源。
