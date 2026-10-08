# Darkbag vs hdr-plus 仓库职责边界与架构准则

> **永久规则**: 本文档定义了宿主应用 `Darkbag` 与核心算法子模块 `hdr-plus` 的代码边界。所有开发者与 AI Subagent 必须严格遵守此边界规则，禁止跨边界乱入功能。

---

## 1. 核心定位与设计哲学

| 维度 | `hdr-plus` (独立算法子模块) | `Darkbag` (Android 宿主工程) |
| :--- | :--- | :--- |
| **定位** | **平台无关的计算摄影算法核心引擎**<br/>(Algorithmic Core) | **Android 相机与暗房摄影 App 全栈宿主**<br/>(Application Host) |
| **仓库地址** | `https://github.com/Steve-Mr/hdr-plus.git` (Git Submodule) | `https://github.com/Steve-Mr/Darkbag.git` (主仓库) |
| **技术栈特征** | 纯 C++17 / Halide / OpenCL / Vulkan Compute<br/>**严禁依赖 Android 平台特定类**，支持独立 Linux/macOS 编译与自动化测试 | Android SDK / NDK / Kotlin / EGL / AHardwareBuffer / MediaStore / libjpeg-turbo |
| **职责抽象** | **“算法内聚”**：RAW 图像的数理模型与滤波变换 | **“系统与风格内聚”**：硬件控制、暗房美学、GPU 硬件调度、文件落盘 |

---

## 2. 职责归属划分矩阵

```
                Camera2 传感器原生 RAW 帧
                           │
                           ▼
┌────────────────────────────────────────────────────────┐
│               hdr-plus 纯算法核心引擎                  │
│                                                        │
│  [1] 多帧 Tile-based 光流对齐 & 运动抑制 (Motion Rejection)
│  [2] 流式自适应加权融合 (Streaming Burst Accumulate)   │
│  [3] 基于 Noise Profile 的空间/色度去噪滤波             │
│  [4] Sabre 超分辨率重构核 (Super-Resolution Core)       │
│  [5] 高保真去马赛克 (RCD Demosaic / Compute Shader RCD) │
└──────────────────────────┬─────────────────────────────┘
                           │ 产物: 纯净线性 Planar 16-bit RGB / 原生 Bayer
                           ▼
┌────────────────────────────────────────────────────────┐
│                 Darkbag 宿主与暗房系统                 │
│                                                        │
│  [1] 相机硬件流控、帧率调度与并发熔断管理              │
│  [2] GPU 离屏渲染引擎、EGL 上下文与 AHardwareBuffer 映射│
│  [3] 独创胶片色彩科学 (CCM / 广色域 / Log / 3D LUT)    │
│  [4] DNG 容器封装 (TIFF tags, GainMap Opcode, EXIF)    │
│  [5] 高速落盘 (libjpeg-turbo / 硬件 JPEG 编码器)       │
│  [6] Android 相册写库 (MediaStore / ContentResolver)   │
└────────────────────────────────────────────────────────┘
```

---

## 3. 具体功能开发归属规范

### 3.1 必须在 `hdr-plus` 中实现的内容
1. **多帧对齐与融合算法 (Align & Merge)**：
   * 任何涉及帧间配准、光流估计、视差补偿、防止运动鬼影的数理算法；
   * `hdr-plus` 仓库中的分支 `fix/streaming-pure-optical-flow-and-motion-rejection` 专注于此。
2. **去马赛克算法核心 (Demosaicing Core)**：
   * 现存放在 Darkbag 中的 `RcdDemosaic.cpp` 属于阶段性过渡，**未来应迁移并沉淀进 `hdr-plus`**，作为其官方提供的高画质解算器选项；
   * 阶段二的 **GPU Compute Shader RCD** 算法核也应放在 `hdr-plus`。
3. **图像降噪与增强核函数**：
   * 基于传感器噪声模型（Sensor Noise Profile）的单通道与色度降噪。
4. **超分辨率算法数理模型**：
   * Sabre 亚像素残差加权融合与核回归模型。

### 3.2 必须在 `Darkbag` 中实现的内容
1. **GPU 调色与 3D LUT 引擎 (阶段一)**：
   * 包含：EGL 离屏上下文创建、`AHardwareBuffer` DMA-BUF 零拷贝映射、`LutSurfaceProcessor` GLSL 片段着色器；
   * 理由：这是 Darkbag 的独创暗房胶片体验，深度依赖 Android NDK/EGL，强塞进 `hdr-plus` 会破坏其平台独立性。
2. **DNG / TIFF 文件容器与元数据构建**：
   * 包含：LibTIFF 标签组装、Lens Shading GainMap Opcode 生成、1024px 高清预览图嵌入。
3. **相机传感器与线程流控调度**：
   * 包含：Camera2 `ImageReader` 捕获、`HdrPlusRequestManager` 队列限流、`HdrPlusProcessingService` 后台前台保活。
4. **硬件 JPEG 编码器系统对接 (阶段三)**：
   * 包含：`MediaCodec` / `ImageWriter` Surface 绑定与 MediaStore 相册入库。

---

## 4. 两仓跨仓库协作与接口契约 (The Contract)

1. **稳定的中间契约**：
   * `hdr-plus` 的输出必须保证为：未做白平衡/未做对数修饰的纯净线性传感器数据（`Planar 16-bit RGB` 或 `Raw Bayer`），并附带白平衡增益与黑白电平元数据；
   * `Darkbag` 从该稳定接口提取数据后，送入下游 `GpuColorPipe` 渲染落盘。
2. **独立验证能力**：
   * `hdr-plus` 的所有改动必须能在桌面端（Linux/macOS）通过命令行工具（利用 DNG/RAW 样本输入）独立编译与单测；
   * `Darkbag` 通过 Git Submodule 机制锁定 `hdr-plus` 的稳定 commit，通过 CMake 静态链接。
