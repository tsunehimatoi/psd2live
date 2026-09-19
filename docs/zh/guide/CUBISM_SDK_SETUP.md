# Live2D Cubism SDK 配置与使用指南

[English](../../en/guide/CUBISM_SDK_SETUP.md) | [日本語](../../ja/guide/CUBISM_SDK_SETUP.md)

本指南旨在指引用户在需要时为 PSD2Live 配置官方 Live2D® Cubism® Native SDK 运行时环境，以启用与官方 Cubism 运行时**100% 忠实一致的渲染与动力学行为验证（Consistency & Ground Truth）**。

---

## 目录

- [核心价值：为什么需要官方 SDK（一致性而非单纯加速）](#核心价值为什么需要官方-sdk一致性而非单纯加速)
- [法律声明与非分发原则](#法律声明与非分发原则)
- [开箱即用说明 (无需 SDK)](#开箱即用说明-无需-sdk)
- [推荐流程：一键构建并部署](#推荐流程一键构建并部署)
- [运行时文件与依赖特征](#运行时文件与依赖特征)
- [备选部署位置](#备选部署位置)
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
> 2. **代码库合规**：PSD2Live 项目源码仓库**不包含、不内置、亦不随版本发布分发**任何 Live2D 官方专有 SDK 库文件、二进制动态链接库（`.dll`）或受版权保护的着色器源文件。本仓库仅提供开源包装层源码（[`native/live2d_renderer/`](../../../native/live2d_renderer/)），由您在本地链接官方 SDK 自行编译。
> 3. **商标权属**：`Live2D`、`Cubism`、`.cmo3`、`.moc3` 等标识均为株式会社 Live2D 的注册商标或商标，在本项目中仅作为文件格式互操作性与标准规范的客观描述使用。

---

## 开箱即用说明 (无需 SDK)

**PSD2Live 完全可以独立运行，不依赖官方 SDK：**

- **全流程无障碍**：PSD 图层语义分类识别、连通域双侧拆分、自适应网格三角剖分、面部九轴经纬网变形器装配、眨眼/果冻眼动力学模拟、循环动作生成，以及最终导出可编辑的 `.cmo3` 编辑器工程与运行时 `.moc3` 文件族，**均由项目内置的独立算法流水线完成，100% 开箱即用**。
- **内置软件光栅化视口**：在未配置官方 SDK 或非 Windows 环境（macOS / Linux）下，GUI 预览面板会自动启用纯 CPU 高精度软件光栅化渲染器，支持实时的网格变形展示与参数滑块调试。

---

## 推荐流程：一键构建并部署

目标：用本仓库包装源码 + 您本地的官方 SDK，编出与历史验证版本**相同依赖特征**的 `live2d_renderer.dll`（静态 CRT `/MT`，不依赖 VC++ 运行库），并连同着色器一并部署。

### 前置条件

| 项目 | 要求 |
| :--- | :--- |
| 系统 | Windows x86-64 |
| 工具链 | CMake 3.16+、Visual Studio 2022 C++（MSVC toolset **143**） |
| 官方 SDK | 自行下载并解压 **Cubism 5 SDK for Native**（例：`CubismSdkForNative-5-r.5`） |
| 关键库文件 | `Core/lib/windows/x86_64/143/Live2DCubismCore_MT.lib`（与 `/MT` 配对；**不要**用 `*_MD.lib`） |

官方 SDK 下载：[Live2D Cubism SDK for Native](https://www.live2d.com/en/sdk/download/native/)（需同意专有许可协议）。

### 一条命令完成

在仓库根目录（PowerShell）：

```powershell
$env:CUBISM_SDK_ROOT = "D:\path\to\CubismSdkForNative-5-r.5"
.\native\build_live2d_renderer.bat -Clean -Deploy
```

脚本会：

1. 以 **`/MT` + `Live2DCubismCore_MT.lib`** 配置并编译 `live2d_renderer.dll`
2. 从 SDK 自动复制 22 个官方 OpenGL 着色器到产物旁的 `FrameworkShaders/`
3. `-Deploy` 再拷贝到 `src/main/resources/cubism/windows-x86_64/`（该路径已被 `.gitignore` 忽略）

> [!IMPORTANT]
> 若以前用 `/MD` 或 `Core_MD.lib` 配过 CMake，**必须**加 `-Clean`（或手动删除 `native/live2d_renderer/build`），否则缓存会继续产出依赖 `VCRUNTIME140.dll` 的错误 DLL。

更细的构建说明见 [`native/live2d_renderer/README.md`](../../../native/live2d_renderer/README.md)。

---

## 运行时文件与依赖特征

部署后的目录形态：

```text
cubism/windows-x86_64/          # 或 src/main/resources/cubism/windows-x86_64/
├── live2d_renderer.dll
└── FrameworkShaders/           # 22 个官方着色器（构建时从 SDK 复制，勿提交 Git）
```

**正确的 DLL 依赖（与历史验证产物一致）应仅为：**

- `OPENGL32.dll`
- `KERNEL32.dll`
- `USER32.dll`
- `GDI32.dll`

**不应出现** `VCRUNTIME140.dll`、`MSVCP140.dll`、`api-ms-win-crt-*.dll`。若出现，说明编成了 `/MD` 动态 CRT，请按上一节 `-Clean` 重编。

可用 Visual Studio 的 `dumpbin /DEPENDENTS live2d_renderer.dll` 自检。

---

## 备选部署位置

应用按以下优先级查找运行时文件（任选其一即可；推荐流程已写入方式一）：

1. **项目资源目录（推荐）**：`src/main/resources/cubism/windows-x86_64/`（`-Deploy` 默认目标，gitignore）
2. **仓库根目录**：`cubism/windows-x86_64/`（同样 gitignore）
3. **外部目录**：环境变量 `CUBISM_SDK_PATH` / `LIVE2D_SDK_PATH`，或 JVM 参数 `-Dpsd2live.cubism.path=...`，指向含 `live2d_renderer.dll` 与 `FrameworkShaders/` 的目录

---

## 验证与状态识别

```powershell
.\run-gui.bat
```

载入任意模型或 PSD，观察预览面板左下角状态胶囊：

- **`原生 Cubism (实时物理)`** / **`原生 Cubism`**：官方运行时已加载
- **`软件光栅化`**：未检测到运行时或非 Windows，已回退 CPU 渲染（功能不受损）
- 若缺着色器等资源，视口左上角会以红色文本给出诊断

---

## 常见问题与故障排查

### Q1: 不配置 SDK 是否影响导出 `.cmo3` / `.moc3`？
不影响基础生成与导出。官方 SDK 的价值是 Ground Truth 一致性预览，而非导出本身。

### Q2: 报错 `Missing Cubism SDK 5-r.5 runtime resource: live2d_renderer.dll`
1. 是否已执行 `-Deploy` 或手动放到三种路径之一；
2. 是否为 64 位 Windows；
3. 若用外部路径，环境变量是否已对当前终端/IDE 生效。

### Q3: 运行时提示缺少 `VCRUNTIME140.dll` / `MSVCP140.dll`？
说明 DLL 被编成了动态 CRT（`/MD`）。请确认使用本仓库当前的 `native/build_live2d_renderer.bat`，并带 `-Clean` 重编，使产物只依赖系统 OpenGL/GDI。

### Q4: 为什么 Git 显示 `src/main/resources/cubism/` 被忽略或删除？
合规预期行为：专有二进制与着色器不得进仓库。本地文件仍在磁盘上，不会因 ignore 而丢失。

### Q5: 能否手工只拷着色器、不跑构建脚本？
可以，但从 SDK 的 `Framework/.../Shaders/Standard/` 拷到部署目录的 `FrameworkShaders/`。推荐流程已自动完成，一般无需手拷。

