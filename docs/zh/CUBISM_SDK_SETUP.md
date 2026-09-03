# Live2D Cubism SDK 配置与使用指南

[English](../en/CUBISM_SDK_SETUP.md) | [日本語](../ja/CUBISM_SDK_SETUP.md)

本指南旨在指引用户在需要时为 PSD2Live 配置官方 Live2D® Cubism® Native SDK 运行时环境，以启用与官方 Cubism 运行时**100% 忠实一致的渲染与动力学行为验证（Consistency & Ground Truth）**。

---

## 目录

- [核心价值：为什么需要官方 SDK（一致性而非单纯加速）](#核心价值为什么需要官方-sdk一致性而非单纯加速)
- [法律声明与非分发原则](#法律声明与非分发原则)
- [开箱即用说明 (无需 SDK)](#开箱即用说明-无需-sdk)
- [所需组件与文件清单](#所需组件与文件清单)
- [配置步骤指南](#配置步骤指南)
  - [第一步：获取官方 SDK](#第一步获取官方-sdk)
  - [第二步：提取 OpenGL 着色器文件](#第二步提取-opengl-着色器文件)
  - [第三步：获取或构建渲染动态库](#第三步获取或构建渲染动态库)
  - [第四步：部署到指定路径 (三种方式)](#第四步部署到指定路径-三种方式)
- [验证与状态识别](#验证与状态识别)
- [常见问题与故障排查](#常见问题与故障排查)

---

## 核心价值：为什么需要官方 SDK（一致性而非单纯加速）

> [!NOTE]
> **配置官方 SDK 的核心目的不在于“性能加速”，而在于“与官方环境的严格一致性（Consistency）”。**

在 Live2D 生产管线中，渲染效果与动力学往往受到复杂的底层算法制约：
1. **像素级着色与蒙版一致性 (Rendering Parity)**：
   - 官方标准着色器（FrameworkShaders）定义了专有的预乘 Alpha（Premultiplied Alpha）、正片叠底/线性减淡等混合模式运算，以及基于专用 FBO 的离屏裁切蒙版（Clipping Mask / Inverted Mask）采样算法。
   - 纯软件光栅化渲染器难免存在插值精度、抗锯齿过滤或色彩空间上的微小差异。配置官方 Native SDK 可以确保在 PSD2Live 视口中看到的画质、蒙版边界与色调，与官方 **Cubism Viewer** 及最终游戏客户端**像素级绝对一致**，杜绝蒙版杂边、黑边或半透明溢色。
2. **物理与动力学表现一致性 (Physics & Motion Parity)**：
   - 模型的发丝摆动、胸腔呼吸以及まばたき果冻眼效果，是由官方 `Live2D_Update` 内部的物理摆子计算模块驱动的。
   - 使用官方运行时驱动，能够保证导出的 `physics3.json` 在实际生产环境中的阻尼、重力响应和摆幅与预览完全一致，避免“编辑器预览与游戏实机不一致”的风险。
3. **交付成果的权威真值对照 (Ground Truth)**：
   - 官方 Native SDK 是检验导出的 `.moc3`、`.model3.json`、贴图集等文件是否符合官方规范的“试金石”。
   - 内置纯 CPU 软件渲染器用于环境未就绪时的快速预览，而官方 SDK 则是最终交付前所见即所得（WYSIWYG）的一致性验收基准。
   - *（硬件加速与流畅帧率只是调用原生 OpenGL 库带来的附带收益，保真度与一致性才是其核心使命。）*

---

## 法律声明与非分发原则

> [!IMPORTANT]
> **本项目严格遵守开源协议与 Live2D 官方专有许可政策：**
> 1. **非分发政策**：根据株式会社 Live2D（Live2D Inc.）的《Live2D 专有软件许可协议》（Live2D Proprietary Software License），Live2D Cubism Core 原生库与 SDK 官方二进制资产属于专有财产，**严禁任何第三方以任何形式重新分发**。
> 2. **代码库合规**：PSD2Live 项目源码仓库**不包含、不内置、亦不随版本发布分发**任何 Live2D 官方专有 SDK 库文件、二进制动态链接库（`.dll`）或受版权保护的着色器源文件。
> 3. **商标权属**：`Live2D`、`Cubism`、`.cmo3`、`.moc3` 等标识均为株式会社 Live2D 的注册商标或商标，在本项目中仅作为文件格式互操作性与标准规范的客观描述使用。

---

## 开箱即用说明 (无需 SDK)

**PSD2Live 完全可以独立运行，不依赖官方 SDK：**

- **全流程无障碍**：PSD 图层语义分类识别、连通域双侧拆分、自适应网格三角剖分、面部九轴经纬网变形器装配、眨眼/果冻眼动力学模拟、循环动作生成，以及最终导出可编辑的 `.cmo3` 编辑器工程与运行时 `.moc3` 文件族，**均由项目内置的独立算法流水线完成，100% 开箱即用**。
- **内置软件光栅化视口**：在未配置官方 SDK 或非 Windows 环境（macOS / Linux）下，GUI 预览面板会自动启用纯 CPU 高精度软件光栅化渲染器，支持实时的网格变形展示与参数滑块调试。

---

## 所需组件与文件清单

启用官方一致性预览共需以下文件（运行架构：`Windows x86-64`）：

```text
cubism/
└── windows-x86_64/
    ├── live2d_renderer.dll                     # 基于 Live2D Cubism Core 5-r.5 编译的原生渲染库
    └── FrameworkShaders/                       # 官方 Framework OpenGL 标准着色器 (共 22 个文件)
        ├── FragShaderSrc.frag
        ├── FragShaderSrcAlphaBlend.frag
        ├── FragShaderSrcBlend.frag
        ├── FragShaderSrcColorBlend.frag
        ├── FragShaderSrcCopy.frag
        ├── FragShaderSrcMask.frag
        ├── FragShaderSrcMaskBlend.frag
        ├── FragShaderSrcMaskInverted.frag
        ├── FragShaderSrcMaskInvertedBlend.frag
        ├── FragShaderSrcMaskInvertedPremultipliedAlpha.frag
        ├── FragShaderSrcMaskInvertedPremultipliedAlphaBlend.frag
        ├── FragShaderSrcMaskPremultipliedAlpha.frag
        ├── FragShaderSrcMaskPremultipliedAlphaBlend.frag
        ├── FragShaderSrcPremultipliedAlpha.frag
        ├── FragShaderSrcPremultipliedAlphaBlend.frag
        ├── FragShaderSrcSetupMask.frag
        ├── VertShaderSrc.vert
        ├── VertShaderSrcBlend.vert
        ├── VertShaderSrcCopy.vert
        ├── VertShaderSrcMasked.vert
        ├── VertShaderSrcMaskedBlend.vert
        └── VertShaderSrcSetupMask.vert
```

---

## 配置步骤指南

### 第一步：获取官方 SDK

1. 前往 Live2D 官方 SDK 下载中心：[Live2D Cubism SDK for Native 下载页面](https://www.live2d.com/en/sdk/download/native/)。
2. 阅读并同意《Live2D 专有软件许可协议》。
3. 下载 **Cubism 5 SDK for Native** 压缩包（例如 `CubismSdkForNative-5-r.5.zip`），解压至本地任意目录。

### 第二步：提取 OpenGL 着色器文件

1. 打开解压后的 SDK 文件夹，定位至：
   ```text
   CubismSdkForNative-5-r.5/Framework/src/Rendering/OpenGL/Shaders/Standard/
   ```
2. 该目录下包含 16 个 `.frag` 片段着色器文件与 6 个 `.vert` 顶点着色器文件。
3. 将上述 22 个着色器文件复制到目标配置位置的 `FrameworkShaders/` 文件夹中。

### 第三步：获取或构建渲染动态库

`live2d_renderer.dll` 是一个轻量级原生包装库，内部静态链接了官方 SDK 的核心库 `Live2DCubismCore_MD.lib` 与 Native Framework。

若需要自行从源码编译（要求安装 CMake 3.16+ 与 Visual Studio 2022 C++ MSVC 工具链）：
1. 确保 SDK 解压目录中的 `Core/lib/windows/x86_64/143/Live2DCubismCore_MD.lib` 存在。
2. 确保 `Samples/OpenGL/thirdParty/glew` 与 `stb` 存在。
3. 执行 CMake 构建：
   ```powershell
   cmake -G "Visual Studio 17 2022" -A x64 -B build
   cmake --build build --config Release --target live2d_renderer
   ```
4. 编译完成后，将生成的 `live2d_renderer.dll` 拷贝至目标配置目录。

### 第四步：部署到指定路径 (三种方式)

项目运行时按以下优先级自动检索 SDK 文件，您可选择最方便的一种方式部署：

#### 方式一：部署到项目内部资源目录 (推荐)
将 `live2d_renderer.dll` 和 `FrameworkShaders` 文件夹放入：
```text
psd2live/src/main/resources/cubism/windows-x86_64/
```
> [!NOTE]
> 该路径已在 `.gitignore` 中配置忽略，存放的文件仅在您的本地机器生效，**绝不会被误提交至 Git 仓库**。每次执行 Gradle 构建或一键启动时会自动打包进运行时环境。

#### 方式二：部署到项目根目录
直接在 `psd2live` 根目录下创建 `cubism` 文件夹：
```text
psd2live/cubism/windows-x86_64/
```
将 `live2d_renderer.dll` 和 `FrameworkShaders/` 放入其中。（此路径同样已被 `.gitignore` 忽略）。

#### 方式三：通过系统环境变量或 JVM 参数指定 (外部路径)
若您将上述文件集中放置在系统的独立工具目录（例如 `D:\SDK\live2d_cubism_runtime`）：
- **系统环境变量**：设置环境变量 `CUBISM_SDK_PATH` 或 `LIVE2D_SDK_PATH` 指向该目录。
  ```powershell
  [System.Environment]::SetEnvironmentVariable("CUBISM_SDK_PATH", "D:\SDK\live2d_cubism_runtime", "User")
  ```
- **JVM 启动参数**：在启动命令中附加参数 `-Dpsd2live.cubism.path="D:\SDK\live2d_cubism_runtime"`。

---

## 验证与状态识别

部署完成后，启动应用：
```powershell
.\run-gui.bat
```

载入任意模型或 PSD，观察“预览 (Preview)”面板左下角的状态胶囊徽标：
- **`原生 Cubism (实时物理)`** 或 **`原生 Cubism`**：表明官方 Native SDK 加载成功，正运行官方硬件加速离屏渲染！
- **`软件光栅化`**：表明未检测到 SDK 运行环境或非 Windows 系统，应用平稳降级为内置纯 CPU 渲染，功能不受损。
- 若加载过程发生异常（例如缺少特定着色器），视口左上角会以红色文本显示明确的缺失资源名称或诊断提示。

---

## 常见问题与故障排查

### Q1: 不配置 SDK 是否影响导出 `.cmo3` 和 `.moc3`？
**完全不影响基础生成与导出，但官方 SDK 提供了权威的一致性保证。** 所有模型几何运算、变形器层级装配、物理参数注入与模型文件序列化均由内部纯 Kotlin/Java 模块独立完成，导出的 `.cmo3` 可在官方 Live2D Cubism Modeler 中直接打开编辑。配置官方 SDK 的核心价值在于：在导出前能够以官方运行时的标准，100% 确保裁切蒙版透明度、预乘 Alpha、混合模式与发丝/眨眼物理摆动效果与最终游戏/应用环境绝对一致（Ground Truth）。

### Q2: 报错 `Missing Cubism SDK 5-r.5 runtime resource: live2d_renderer.dll`
请检查：
1. `live2d_renderer.dll` 是否已放置在上述三种配置路径之一；
2. 操作系统是否为 64 位 Windows；
3. 如果使用方式三，请检查 `CUBISM_SDK_PATH` 环境变量是否已正确加载（修改环境变量后请重启终端/IDE）。

### Q3: 为什么 Git 显示删除了 `src/main/resources/cubism/` 下的文件？
这是符合开源合规要求的预期行为。为了确保本项目完全遵守 Live2D 专有许可政策，官方二进制与着色器已被移出 Git 暂存区并加入 `.gitignore`。您本地磁盘上的文件完好无损，不会丢失。

