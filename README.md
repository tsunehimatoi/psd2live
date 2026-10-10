<div align="center">

<img src="docs/branding/psd2live-icon-mesh.png" width="112" alt="PSD2Live 图标">

# PSD2Live

**开源的 2D 角色绑定与动画编辑器**

从分层 PSD 自动搭出可动的初版，在同一个工作区里修形、绑骨骼、做物理与模拟、编动画，<br>
再导出到 Cubism、VTube Studio、自带运行时、网页、游戏引擎或视频。

[![Release](https://img.shields.io/github/v/release/tsunehimatoi/psd2live?label=%E4%B8%8B%E8%BD%BD)](https://github.com/tsunehimatoi/psd2live/releases/latest)
[![License](https://img.shields.io/badge/license-GPL--3.0%20%2F%20MIT-blue)](#许可)
[![Platform](https://img.shields.io/badge/platform-Windows%20%7C%20Linux-lightgrey)](#下载)
[![Cubism](https://img.shields.io/badge/Cubism-3.0%20%E2%80%93%205.3-ff6b9d)](#导出与播放)
[![MCP](https://img.shields.io/badge/MCP-180%2B%20ops-8a63d2)](#连接-agentmcp)

[English](docs/en/README.md) · [日本語](docs/ja/README.md) · [한국어](docs/ko/README.md)

[下载](https://github.com/tsunehimatoi/psd2live/releases/latest) · [快速开始](#快速开始) · [文档](docs/README.md) · [更新日志](docs/zh/CHANGELOG.md) · [路线图](docs/zh/ROADMAP.md)

</div>

PSD2Live 是一套完整的 2D 角色绑定工具链：从分层 PSD 自动生成网格、变形器、参数、动作与物理，在桌面编辑器中继续修改，再编译到 Cubism、自带运行时 p2lrt 和多种通用格式。它有独立的求值引擎、工程格式与分支历史；一键生成只是起点，所有修改都以可重放的编辑保存，重新生成后依然有效。

> [!NOTE]
> Cubism 是首要导出目标，而非能力边界。骨骼、2D 模拟等 Cubism 没有的功能，在导出时烘焙为原生变形器、参数、关键形与摆锤；交付的模型不依赖 PSD2Live。

## 适合谁

| 你是 | PSD2Live 能做的 |
| --- | --- |
| **零基础，只想要一个能动的模型** | 按命名规范分好图层，导入后选预设，几分钟得到带待机、眨眼、点头、头发物理的模型，直接导出给 VTube Studio 或网页 |
| **不熟悉建模，希望交给 AI** | 本地 MCP 服务让 Claude Code、Codex 等 Agent 直接读取和修改工程；每一步进入同一份历史，可在界面中检查和撤销 |
| **有经验，想省掉重复劳动** | 网格、变形器链、五官参数、物理一键生成，再在画布上逐项精修；手工修改以可重放的编辑保存，重新生成后依然有效 |
| **需要开源替代** | 编辑器、引擎和工程格式全部开源，不依赖官方 SDK 即可编辑、预览和导出 `.moc3` / `.cmo3` |
| **不满足于 Cubism 的功能** | 骨骼与 IK、2D 布料与头发模拟、自动骨架等 Cubism 没有的工具，导出时烘焙为原生结构 |
| **要把角色放进自己的程序** | 开源 Rust 运行时 p2lrt：C / C++、.NET、Android、网页与 Godot 均可接入，自带软件渲染器；也可导出视频、序列帧与 Spine、glTF（实验性） |

## 核心能力

| 模块 | 能力 |
| --- | --- |
| **自动生成** | 识别中 / 英 / 日 / 韩图层名；自适应网格、头身变形器链、五官参数、待机 / 眨眼 / 点头 / 摇头动作、头发物理与布料模拟 |
| **画布编辑** | 选择、变形、编辑、模拟、骨骼、绘画、预览七种模式；形变笔刷、网格拓扑编辑、图层拆分与前后分层、Warp / Rotation、Glue |
| **骨骼与模拟** | 自动骨架、FK / IK、预设动作；XPBD 布料与头发模拟；全部烘焙为 Cubism 原生结构 |
| **动画与物理** | 时间轴、关键帧与曲线；可视化摆锤编辑，求值与 Cubism Native Framework 对齐 |
| **纹理** | 纹理集排布、逐层纹理密度、可选 2× / 4× 高清化 |
| **编译与导出** | 所有目标从同一份中立绑定 IR 编译，逐次输出损失报告 |
| **p2lrt 运行时** | 开源 Rust 运行时：C ABI、软件渲染器、WebAssembly 网页播放器、Godot 4 节点、.NET 与 Android 构建；与编辑器求值逐姿势一致 |
| **Agent** | 本地 MCP 服务，180 余项公开操作，与界面共用编辑命令与历史，支持原子批量与试运行 |

## 能力验证

以下图片基于仓库内的两份示例 PSD（膝盖测试与身体运动动图另用一份长腿角色工程）：两份示例均在导入时选用「完整」预设、未经手工修改，头发与裙摆为烘焙后的模拟。测试图由开发工具与命令行导出结果生成，界面截图取自真实编辑器窗口的渲染帧。

### 编辑器

<table>
<tr>
<td width="50%" valign="top"><img src="docs/imgs/readme/workspace-animation.webp" alt="动画工作区：动作列表、预览画布、参数面板与动画编辑器中的参数轨道"><br><sub><b>动画</b>：动作列表、时间轴与曲线编辑，预览与参数随播放头同步</sub></td>
<td width="50%" valign="top"><img src="docs/imgs/readme/skeleton-ds.webp" alt="骨骼模式：自动推断的躯干、四肢和尾巴骨骼叠加在角色上"><br><sub><b>骨骼</b>：画布上调整关节、FK / IK 摆姿</sub></td>
</tr>
<tr>
<td width="50%" valign="top"><img src="docs/imgs/readme/workspace-texture.webp" alt="纹理工作区：纹理集页面上的图块排布、模型预览与纹理面板"><br><sub><b>纹理</b>：在纹理集上排布图块，逐层设定纹理密度</sub></td>
<td width="50%" valign="top"><img src="docs/imgs/readme/start-screen.webp" alt="开始界面：模型预设与可按网格拆分的图层列表"><br><sub><b>导入</b>：选择模型预设，按网格拆分画在同一层的左右部件</sub></td>
</tr>
</table>

### 物理与模拟

身体角度依次上下、左右、沿椭圆运动各一轮。头发、裙子、袖子与蝴蝶结由烘焙后的模拟带动，随起伏与转向滞后摆动，停下后回摆。左侧是各部件的 pin 权重：1 跟随身体，0 完全自由，中间值按比例牵引。

<p align="center"><img src="docs/imgs/readme/star-orbit.webp" alt="左：各模拟部件（后发、前发、裙子、袖子、蝴蝶结）的 pin 权重；右：身体角度驱动角色依次上下、左右、椭圆运动，头发与布料随之滞后摆动" width="785"></p>

<p align="center"><img src="docs/imgs/readme/sim-skirt.webp" alt="裙摆特写动图：同样的身体 X / Y 运动，左侧关闭模拟，裙摆僵硬跟随身体；右侧开启烘焙后的布料模拟，裙摆滞后并回摆" width="660"></p>

### 自动绑定

导入即得完整的头部与五官绑定：面部 Warp、五官平面、头发与身体跟随由同一条生成的变形器链驱动。

![头部参数空间：两个示例角色在 AngleX ±45、AngleY ±30 九个组合下的姿态，均为自动生成、未经手工修改](docs/imgs/readme/rig-head.webp)

![五官参数：中性、眨眼、半睁加视线、张嘴、微笑、皱眉六种状态](docs/imgs/readme/rig-face.webp)

### 自动 Mesh 拓扑

网格按纹理轮廓生成，孔洞、细桥与尖端各自成环，参数在全局与逐层两级可调。

![拓扑压力测试：一张合成图层包含 20 个圆孔、28 条径向槽、6 孔轮毂、12 根细辐条与 4 条渐细卷须，自动网格逐一贴合；右侧为局部特写](docs/imgs/readme/mesh-stress.webp)

![边缘层数与内部填充的参数矩阵：行为 1 / 2 / 3 层轮廓环，列为分级泊松、自适应四叉树、轮廓铺砌、三角分形四种填充，每格标注顶点数、三角形数与耗时](docs/imgs/readme/mesh-params.webp)

![包络参数：26 根间距 2–10 像素的发丝在包络宽度 0、3、8、20 下的网格，宽度越大，窄缝合并为同一轮廓，顶点越少](docs/imgs/readme/mesh-wrap.webp)

<table>
<tr>
<td width="50%" valign="top"><img src="docs/imgs/readme/mesh-real.webp" alt="编辑器中示例角色长卷发的自动网格"><br><sub><b>实际素材</b>：示例角色的长卷发，轮廓沿每一缕卷发分布</sub></td>
<td width="50%" valign="top"><img src="docs/imgs/readme/sim-weights-hair.webp" alt="模拟模式下后发的固定点权重，顶部红色为固定，向下渐变为蓝色"><br><sub><b>顶点权重</b>：模拟模式在同一网格上显示与刷写固定点权重（红为固定，蓝为自由）</sub></td>
</tr>
</table>

### 自动骨架

按图层标签推断躯干、四肢与尾巴，骨骼烘焙为原生变形器与关键形。膝、肘按人体关节塑形：外侧顶出膝盖轮廓，内侧折叠，大腿刚性段不随小腿漂移。同一组预设动作适配不同体型。

![膝关节弯折测试：长腿角色一侧小腿弯折 0°、60°、120°，以及膝盖在 0°、30°、120° 的特写，可见外侧膝盖轮廓与内侧折叠](docs/imgs/readme/rig-knee.webp)

![骨架预设姿态：两个示例角色在静止与挥手、欢呼、下蹲、重心转移、害羞、歪头六个预设动作最大幅度处的姿态](docs/imgs/readme/rig-body.webp)

### p2lrt 运行时一致性

Rust 运行时逐一重放编辑器的参考姿势与轨迹；几何、物理、模拟全部通过，四种文件编码读出相同结果。

![运行时与编辑器求值器的一致性：84 个绑定的最大顶点误差分布（对数坐标，均低于 0.02 像素上限或在自身灵敏度内），物理 16/16、模拟 12/12 通过](docs/imgs/readme/runtime-conformance.webp)

![混合模式在各宿主上的一致性：18 种颜色混合模式、乘算 / 屏幕色、遮罩与反向遮罩、隔离部件组和 5 种透明度合成模式，参考光栅器、软件渲染器、网页播放器与 Godot 节点的渲染及与参考的差异](docs/imgs/readme/runtime-blend.webp)

## 与 Cubism 的关系

PSD2Live 不依赖 Cubism Editor 或 SDK 运行，但与 Cubism 格式保持兼容。每项能力按它能否进入 Cubism 分三档：

| 档位 | 含义 | 例子 |
| :---: | --- | --- |
| **无损** | Cubism 原生支持，或只影响编辑过程 | 网格、变形器、参数、物理、动作；笔刷、拆分、自动生成 |
| **烘焙** | Cubism 没有，但能烘焙成关键形、参数或摆锤 | 骨骼与 IK、布料 / 头发模拟、摇摆生成 |
| **仅运行时** | 无法烘焙，只在自带运行时、网页、Godot 和光栅导出中生效 | 规划中的动态光照与风格化渲染，见[路线图](docs/zh/ROADMAP.md) |

导出 `.moc3` / `.cmo3` 时，后两档的内容按目标版本（Cubism 3.0 – 5.3）降级，并写进损失报告。

## 下载

在 [Releases](https://github.com/tsunehimatoi/psd2live/releases/latest) 下载最新版本，各版本的变化见[更新日志](docs/zh/CHANGELOG.md)。

| 平台 | 安装包 | 说明 |
| --- | --- | --- |
| Windows 10 / 11 x64 | 便携版 ZIP、EXE | 自带 Java 运行时，解压或安装后直接运行；每种都另有内置 ffmpeg 的 `-ffmpeg` 版本 |
| Windows 11 ARM64 | 便携版 ZIP、EXE | 自带 Java 运行时；不含 Cubism 原生预览（Live2D 未提供该平台的 Cubism Core），预览使用 PSD2Live 运行时 |
| Linux x86_64 / arm64 | Deb | 自带运行时与 Cubism 原生预览（arm64 为 Live2D 的实验性版本），需要 X11 / GLX（XWayland 可用） |
| macOS 及其他 | 暂无安装包 | 安装 JDK 21 后[从源码运行](#从源码构建) |

<details>
<summary>升级、卸载与 Linux 说明</summary>

- **升级**：安装到已安装的目录，无需再选路径；3.1.x 及更早的 EXE / MSI 安装会被自动移除并沿用原目录。
- **卸载**：默认保留设置与工作区数据；卸载时询问是否同时删除 PSD2Live 的数据，选「是」可一并删除。已保存的工程文件和你放在安装目录里的文件始终保留。
- **Linux**：原生预览不支持无 XWayland 的纯 Wayland 和 musl（如 Alpine），这些环境会自动回退到内置渲染。详见 [Cubism Native 预览](docs/zh/guide/CUBISM_SDK_SETUP.md)。

</details>

## 快速开始

1. **导入 PSD**：**文件 → 导入 → 从 PSD 新建工程…**（`Ctrl+Shift+O`），或把 PSD 拖进窗口。在「开始」界面选择模型预设（最小 / 默认 / 完整），并勾选需要按网格拆分的图层（如画在同一层的左右腿）。
2. **核对识别结果**：在「图层」表格中检查每层的部件类型、侧别与差分设置，识别错的直接改。
3. **预览与修改**：切到「预览」工作区检查动作和物理，再按需进入编辑、绑定、动画、物理、纹理工作区。
4. **保存与导出**：`Ctrl+S` 保存 `.psd2live` 工程；`Ctrl+G` 导出 `.moc3` / `.cmo3`，其他格式在 **文件 → 导出为** 中。

> [!TIP]
> **第一次使用，请打开「帮助 → 教程…」（`F1`）。** 程序内教程会高亮对应控件，并按你当前的快捷键提示操作，分为零基础（18 课）和 Cubism 经验者（13 课）两条路线。[操作速查](docs/zh/guide/USER_GUIDE.md)是它的文字版。

### 准备素材

自动生成的质量主要取决于 PSD 分层：

- 眼白、瞳孔、上睫毛分层，瞳孔保留被眼皮遮住的部分；
- 提供张嘴素材（或分出上牙、下牙、舌头）；
- 前发与后发分开，为遮挡区域留余量；
- 身体基本正立，图层效果与文字事先栅格化。

完整命名表见 [PSD 素材与命名](docs/zh/spec/PSD_LAYER_SPEC.md)。未识别的图层会保留，可在界面中手动指定类型。

## 导出与播放

| 去向 | 输出 |
| --- | --- |
| **Cubism Editor** | `.cmo3` 模型工程，在官方编辑器中检查与精修 |
| **Cubism 运行时** | `.model3.json` + `.moc3` + 纹理、物理、动作等文件族，从 `.model3.json` 加载 |
| **VTube Studio** | `.moc3` 文件族加 `.vtube.json`，面部跟踪映射到标准参数，每个动作一个热键 |
| **自己的程序 / 引擎 / 网页** | `.p2lrt` 模型，用于 C / C++、.NET、Unity、Godot、Android，或可直接在浏览器打开的 WebGL 播放页面 |
| **图片与视频** | PNG 序列帧、精灵表、GIF；APNG、WebP 动图、MP4、WebM、ProRes 4444（经 ffmpeg） |
| **PSD** | 按当前姿势输出分层 PSD，或导出含生成图层的源 PSD |
| **实验性** | Spine 4.2、DragonBones 5.5、glTF 2.0，只能经命令行导出 |

`Ctrl+G` 导出 Cubism 格式，目标版本可选 3.0 – 5.3（默认 5.0）；其余在 **文件 → 导出为** 中。详见[导出目标](docs/zh/spec/EXPORT_TARGETS.md)。

> [!IMPORTANT]
> 继续工作请保存 `.psd2live` 工程，它包含原始 PSD、素材、设置、全部编辑与历史分支；导出时附带的 `.psd2live.json` 只是诊断报告。导出成功也不等于在所有运行时中效果一致，正式交付前请在目标编辑器和运行环境中检查，支持范围见[运行时与导出边界](docs/zh/spec/RUNTIME_EXPORT_ARCHITECTURE_AND_GAPS.md)。

内置渲染无需任何官方 SDK；[Cubism Native 预览](docs/zh/guide/CUBISM_SDK_SETUP.md)是可选项，用于对照官方运行时的渲染与物理。

## p2lrt 运行时

[`runtime/`](runtime/) 是开源（MIT）的 Rust 运行时，播放导出的 `.p2lrt` 模型。编辑器的软件预览由同一个库求值。

| 项目 | 说明 |
| --- | --- |
| **核心求值** | 与 Cubism 等价的 Warp / Rotation 变形、混合形、Glue、部件与绘制顺序、透明度与颜色通道；与编辑器求值逐姿势一致 |
| **物理与动作** | 按 physics3.json 语义的摆锤物理，含风力与稳定；最多 16 层动作按优先级叠加，支持事件与部件 / 整体透明度曲线，淡入淡出与 Cubism 相同；多个表情可同时生效 |
| **程序化行为** | 眨眼、呼吸、视线跟随、口型（可由音频驱动）；宿主层可在动作之上叠加外部参数（如面捕） |
| **高级扩展** | 可选开启：关节运行时蒙皮、精确链接、XPBD 实时布料与头发及碰撞；关闭时结果与核心求值逐位一致 |
| **渲染** | 宿主按头文件中的绘制规则自行绘制，或调用内置软件渲染器 `p2l_render` 直接画进 RGBA 图像：全部颜色与透明度混合模式、遮罩、隔离组与剔除，与编辑器的参考光栅器一致；逐网格的变化标志让宿主只上传改动部分 |
| **宿主集成** | 一份只读模型供多个实例共享；分阶段更新、部件透明度与颜色覆盖、点击区域、日志与宿主内存分配器；内部错误只使该句柄失效，不会中止宿主 |
| **文件格式** | `.p2lrt` 2.0：分块容器，核心块与 moc3 一一对应，扩展块可选；支持 deflate / zstd 压缩，同一 IR 写出字节完全相同 |
| **接入方式** | C / C++ 头文件 [`p2l_runtime.h`](runtime/include/p2l_runtime.h) 与动态 / 静态库（可用于 Unreal）、.NET P/Invoke 绑定与 Unity 组件示例、Android（arm64-v8a、armeabi-v7a、x86_64）、WebAssembly 网页播放器、Godot 4 节点 `P2LCharacter`（[`runtime/godot/`](runtime/godot/)），见[其他语言与平台](runtime/bindings/README.md)（英文） |

<table>
<tr>
<td width="50%" align="center"><img src="docs/imgs/readme/runtime-web.webp" alt="网页播放器在浏览器中播放示例角色，下方有动作、表情和口型控件"><br><sub>网页播放器（WebAssembly + WebGL）</sub></td>
<td width="50%" align="center"><img src="docs/imgs/readme/runtime-godot.webp" alt="Godot 4 中的 P2LCharacter 节点显示示例角色"><br><sub>Godot 4 节点</sub></td>
</tr>
</table>

格式与求值规则见[运行时](docs/zh/spec/RUNTIME.md)与 [`.p2lrt` 2.0 格式](docs/zh/spec/P2LRT_V2.md)。

## 连接 Agent（MCP）

1. 保持 PSD2Live 运行，打开 **工具 → MCP…**，设置端口、访问令牌和发布的工具集（默认精简）。
2. 复制与你的宿主对应的命令或配置（Claude Code、Codex、通用 JSON）。只支持 Stdio 的宿主使用仓库根目录的 [`mcp_proxy.py`](mcp_proxy.py)。
3. 让 Agent 先用 `workspace_overview` 读取工程概况、`workspace_inspect` 查看单个对象；未列为工具的操作用 `workspace_list_operations` 查找、经 `workspace_call` 调用。

多项编辑可用 `workspace_apply_edits` 原子提交（全部成功或全部不生效），也可先用 `workspace_preview_edits` 试运行。接口说明见 [MCP 使用与接口](docs/zh/agent/MCP_AUTHORING.md)。

> [!NOTE]
> MCP 本身不生成图片，新增素材需要宿主具备图像生成能力。工具可调用不代表复杂建模任务已经可靠，[能力实测](docs/zh/STATUS.md)记录了真实任务的成功与失败样本。

## 从源码构建

需要 JDK 21，Gradle 使用仓库自带的 Wrapper。安装了 Rust（cargo）时会同时构建 Rust 运行时，没有时自动跳过，编辑器改用内置求值器。

```bash
# 启动 GUI（Windows：.\gradlew.bat run 或 run-gui.bat）
./gradlew run

# 命令行直接从 PSD 生成模型
./gradlew run --args="--input examples/tml/psd-input/tml.psd --output build/example-output"

# 把工程导出为其他格式，例如 GIF
./gradlew run --args="export model.psd2live --target gif --set clip=Nod"

# 运行测试 / 打当前平台安装包
./gradlew test
./gradlew packageDistributionForCurrentOS
```

源码构建默认使用内置渲染，官方 Cubism SDK 需自行取得并单独构建桥接库。完整 CLI 参数、打包方式与原生预览构建见[开发与命令行](docs/zh/guide/DEVELOPMENT.md)。

## 文档

| 上手 | 绑定与动画 | 参考 |
| --- | --- | --- |
| [操作速查](docs/zh/guide/USER_GUIDE.md) | [骨骼与姿态](docs/zh/guide/SKELETON.md) | [PSD 素材与命名](docs/zh/spec/PSD_LAYER_SPEC.md) |
| [画布编辑](docs/zh/guide/CANVAS_EDITOR.md) | [摇摆生成](docs/zh/guide/SWING.md) | [变形器与参数](docs/zh/spec/DEFORMER_AND_PARAMETER_SPEC.md) |
| [前后分层](docs/zh/guide/DEPTH_SPLIT.md) | [物理](docs/zh/guide/PHYSICS.md) | [导出目标](docs/zh/spec/EXPORT_TARGETS.md) |
| [纹理高清化](docs/zh/guide/TEXTURE_UPSCALE.md) | [模拟与烘焙](docs/zh/guide/SIMULATION.md) | [运行时](docs/zh/spec/RUNTIME.md) |
| [变形路径](docs/zh/guide/DEFORM_PATHS.md) | [模拟图文教程](docs/zh/guide/SIMULATION_TUTORIAL.md) | [运行时与导出边界](docs/zh/spec/RUNTIME_EXPORT_ARCHITECTURE_AND_GAPS.md) |
| [开发与命令行](docs/zh/guide/DEVELOPMENT.md) | [MCP 使用与接口](docs/zh/agent/MCP_AUTHORING.md) | [工程格式](docs/zh/spec/PROJECT_FORMAT.md) · [文档层](docs/zh/spec/DOCUMENT_LAYER.md) |

全部文档见[文档目录](docs/README.md)，示例 PSD 与输出见 [examples](examples/readme.md)，后续方向见[路线图](docs/zh/ROADMAP.md)。

## 参与贡献

欢迎提交 Issue、可复现的 PSD、文档修正与代码改进。

- **报告问题**：附上版本、操作系统、复现步骤、预期与实际效果，以及日志面板中的相关记录。
- **提交代码**：先运行与改动相关的测试（`./gradlew test`）；新的编辑功能需要能随工程保存并在重开后重放，见[开发与命令行](docs/zh/guide/DEVELOPMENT.md)。
- **Agent 案例**：请同时记录宿主、模型、返工次数与消耗，格式见[能力实测](docs/zh/STATUS.md)。

## 许可

| 部分 | 许可 |
| --- | --- |
| 应用本身、引擎 `umamo`、Cubism 与 PSD 导出目标 | [GPL-3.0](LICENSE) |
| 中立绑定 IR `format-model`、导出框架 `format-compile`、其余导出目标、Rust 运行时 `runtime/` | MIT，可单独复用 |

应用整体以 GPL-3.0 发布，各模块许可见其 `LICENSE` 与[导出目标](docs/zh/spec/EXPORT_TARGETS.md#模块)。第三方组件见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)，示例素材的使用条件见各自说明。

<sub>PSD2Live 是独立项目，与 Live2D Inc. 无隶属或赞助关系。本仓库不包含、也不分发 Live2D Cubism SDK 的专有组件；Live2D 和 Cubism 是 Live2D Inc. 的商标。</sub>
