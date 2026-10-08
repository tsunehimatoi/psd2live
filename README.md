# PSD2Live

[English](docs/en/README.md) · [日本語](docs/ja/README.md) · [한국어](docs/ko/README.md) · [下载](https://github.com/tsunehimatoi/psd2live/releases/latest) · [更新日志](docs/zh/CHANGELOG.md) · [文档](docs/README.md)

**从分层 PSD 自动生成 Live2D 模型，在同一个桌面工作区里完成修形、绑定、动画、物理与纹理，再导出到 Cubism、VTube Studio、网页或视频。**

![PSD2Live 编辑工作区：左侧层级树，中间画布在变形模式下显示前发网格，右侧为模型预设与图层分类表](docs/imgs/overview.webp)

PSD2Live 根据图层名称识别部件，自动生成网格、变形器层级、头身与五官参数、基础动作、物理和布料模拟。生成结果只是起点：你可以在画布上继续修形、拆分网格、绘制贴图、搭建骨骼、编辑动作曲线和调整纹理集，最后导出 Cubism Editor 可继续编辑的 `.cmo3`、可直接交付的 `.moc3` 文件族，或其他格式。

![同一模型在不同头部与身体角度下的预览：左转、右下、正面、右上、左下](docs/imgs/poses.webp)

<sub>上图为示例 PSD 导入后未经手工修改的自动生成结果，由头部与身体角度参数驱动。</sub>

## 功能

| 领域 | 能力 |
| --- | --- |
| 自动生成 | 中 / 英 / 日 / 韩图层名识别，成对部件自动拆分，自适应网格，头身变形器链，眼口开合、视线与眉毛参数，待机 / 眨眼 / 点头 / 摇头动作，前后发与果冻眼物理 |
| 画布编辑 | 选择 / 变形 / 编辑 / 模拟 / 骨骼 / 绘画 / 预览七种模式；形变笔刷、网格细分与切割、按网格或多边形拆分、前后分层、Warp / Rotation 创建、Glue、变形路径（实验性） |
| 骨骼 | 自动推断四肢、尾巴、翅膀骨架，FK / IK 摆姿；关节按人体关节姿态塑形；导出时烘焙为 Cubism 原生的变形器、参数与修正关键形，不依赖本程序运行 |
| 摇摆、物理与模拟 | 左右 / 上下摇摆及对应摆锤；可视化摆锤编辑、响应曲线、链式物理，求值与 Cubism Native Framework 对齐；布料与头发 2D 模拟，烘焙为参数、关键形与摆锤 |
| 素材与纹理 | 导入透明图片、开关差分与多选一差分、图层绘制与修边；纹理工作区逐层调整纹理密度，在纹理集上拖动和缩放图块；可选 2× / 4× 纹理高清化 |
| 动画 | 时间轴、关键帧与曲线编辑，实时预览；骨架可用时追加下蹲、挥手、欢呼等预设动作 |
| 导出 | Cubism 3.0 – 5.3 的 `.moc3` / `.cmo3`、VTube Studio、PSD2Live 运行时模型与网页播放器、PNG 序列帧、精灵表、GIF、APNG、WebP、视频、指定姿势的 PSD；每次导出附带损失报告 |
| 工程 | 单文件 `.psd2live` 工程，分支式历史，多标签页，八种工作区预设与空白布局，明暗主题，Photoshop / Blender / Cubism 快捷键预设 |
| Agent | 本地带认证的 MCP 服务，180 余项公开操作，覆盖观察、形状、素材、纹理、参数、骨骼、动作、物理、模拟、导出与历史；支持原子批量编辑与试运行 |

<table>
<tr>
<td width="50%"><img src="docs/imgs/skeleton.webp" alt="绑定工作区：骨骼标签页列出骨骼与绑定的网格，画布上用 IK 抬起双臂"></td>
<td width="50%"><img src="docs/imgs/physics.webp" alt="物理工作区：预览画布、参数面板与物理面板，摆锤画布和响应曲线"></td>
</tr>
<tr>
<td align="center"><sub>骨骼：自动推断骨架，拖动末端即可 IK 摆姿</sub></td>
<td align="center"><sub>物理：直接牵动摆锤，响应曲线实时更新</sub></td>
</tr>
</table>

![动画工作区：动作列表、预览画布、参数面板，下方动画编辑器显示待机动画的 9 条参数轨道与关键帧](docs/imgs/animation.webp)

## 下载

在 [Releases](https://github.com/tsunehimatoi/psd2live/releases/latest) 下载最新版本，各版本的变化见[更新日志](docs/zh/CHANGELOG.md)。

| 平台 | 安装包 | 说明 |
| --- | --- | --- |
| Windows 10 / 11 x64 | 便携版 ZIP、EXE、MSI | 自带 Java 运行时，解压或安装后直接运行；每种都另有内置 ffmpeg 的 `-ffmpeg` 版本。升级时安装到已安装的目录，无需再选路径 |
| Linux x86_64 | Deb | 自带运行时与 Cubism 原生预览，需要 X11 / GLX（XWayland 可用） |
| macOS 及其他 | 暂无安装包 | 安装 JDK 21 后[从源码运行](#从源码构建) |

Windows 卸载默认保留设置与工作区数据；在卸载向导中选择「Remove」并勾选「Also delete my PSD2Live data」可一并删除。已保存的工程文件始终保留。

Linux 原生预览不支持无 XWayland 的纯 Wayland、aarch64 和 musl（如 Alpine），这些环境会自动回退到内置渲染。详见 [Cubism Native 预览](docs/zh/guide/CUBISM_SDK_SETUP.md)。

## 快速开始

1. **导入 PSD**：**文件 → 导入 → 从 PSD 新建工程…**（`Ctrl+Shift+O`），或把 PSD 拖进窗口。在随后打开的「开始」界面选择模型预设（最小 / 默认 / 完整），并勾选需要按网格拆分的图层（如画在同一层的左右腿）。
2. **核对识别结果**：在「图层」表格中检查每层的部件类型、侧别与差分设置，识别错的直接改。
3. **预览与修改**：切到「预览」工作区检查动作和物理，再按需在编辑、绑定、动画、物理、纹理工作区中修改。
4. **保存与导出**：`Ctrl+S` 保存 `.psd2live` 工程；`Ctrl+G` 导出 `.moc3` / `.cmo3`，其他格式在 **文件 → 导出为** 中。

<img src="docs/imgs/import-split.webp" width="560" alt="按网格拆分图层：legwear、footwear、eyelash、front hair 各被识别为两个部件">

**第一次使用，请打开「帮助 → 教程…」（`F1`）。** 程序内教程会高亮对应控件，并按你当前的快捷键提示操作，分为零基础（18 课）和 Cubism 经验者（13 课）两条路线。[操作速查](docs/zh/guide/USER_GUIDE.md)是它的文字版。

## 准备素材

自动生成的质量主要取决于 PSD 分层。最重要的几条：

- 眼白、瞳孔、上睫毛分层，瞳孔保留被眼皮遮住的部分；
- 提供张嘴素材（或分出上牙、下牙、舌头）；
- 前发与后发分开，被遮挡的区域留出余量；
- 身体基本正立，图层效果与文字事先栅格化。

完整命名表与分层建议见 [PSD 素材与命名](docs/zh/spec/PSD_LAYER_SPEC.md)。未识别的图层会保留，可在界面中手动指定类型。

## 导出

**文件 → 导出 Live2D 模型…**（`Ctrl+G`）输出 Cubism 格式：

| 文件 | 用途 |
| --- | --- |
| `.psd2live` | 本程序工程：原始 PSD、素材、设置、全部编辑与历史分支。继续工作请保存它 |
| `.cmo3` | Cubism Editor 模型工程，用于在官方编辑器中检查与精修 |
| `.model3.json` + `.moc3` + 纹理、物理、动作等 | 运行时模型文件族，需一起交付，从 `.model3.json` 加载 |
| `.psd2live.json` | 导出诊断报告，不能代替工程 |

目标版本可选 Cubism 3.0 – 5.3（默认 5.0），不支持的功能按目标版本降级并在报告中提示。

**文件 → 导出为** 提供其他格式：

| 格式 | 说明 |
| --- | --- |
| VTube Studio | `.moc3` 文件族加 `.vtube.json`，面部跟踪映射到标准参数，每个动作一个热键 |
| PSD2Live 运行时模型 / 网页播放器 | `.p2lrt` 模型，或可直接在浏览器打开的 WebGL 播放页面，见[运行时](docs/zh/spec/RUNTIME.md) |
| PNG 序列帧、精灵表、GIF | 按动作采样渲染 |
| APNG、WebP 动图、MP4、WebM、ProRes 4444 | 经 ffmpeg 编码：Windows 的 `-ffmpeg` 包已内置，其他情况需自行安装（设置中指定或放入 `PATH`） |
| 指定姿势的 PSD、源 PSD | 按当前姿势输出分层 PSD，或导出含生成图层的源 PSD |

所有导出都经同一份中立绑定 IR 编译，并附带损失报告，列出目标格式无法保留的内容。Spine 4.2、DragonBones 5.5 与 glTF 2.0 为实验性目标，只能经命令行导出。详见[导出目标](docs/zh/spec/EXPORT_TARGETS.md)。

导出成功不等于在所有运行时中效果一致，正式交付前请在目标编辑器和运行环境中检查；支持范围见[运行时与导出边界](docs/zh/spec/RUNTIME_EXPORT_ARCHITECTURE_AND_GAPS.md)。内置渲染无需任何官方 SDK；[Cubism Native 预览](docs/zh/guide/CUBISM_SDK_SETUP.md)是可选项，用于对照官方运行时的渲染与物理。

## 在自己的项目中播放

[`runtime/`](runtime/) 是开源（MIT）的 Rust 运行时，播放导出的 `.p2lrt` 模型：参数驱动的变形、物理、动作片段，以及眨眼、呼吸、视线跟随与口型等程序化行为。它提供 C 接口，网页播放器使用它的 WebAssembly 构建，另有 Godot 4 节点 `P2LCharacter`（[`runtime/godot/`](runtime/godot/)）。编辑器的软件预览也由同一个库求值。详见[运行时](docs/zh/spec/RUNTIME.md)。

## 连接 Agent（MCP）

1. 保持 PSD2Live 运行，打开 **工具 → MCP…**。这里可以设置端口、访问令牌和发布的工具集（默认精简）。
2. 复制与你的宿主对应的命令或配置（Claude Code、Codex、通用 JSON）。只支持 Stdio 的宿主使用仓库根目录的 [`mcp_proxy.py`](mcp_proxy.py)。
3. 让 Agent 先用 `workspace_overview` 读取工程概况、`workspace_inspect` 查看单个对象；未列为工具的操作用 `workspace_list_operations` 查找、经 `workspace_call` 调用。所有写操作与界面编辑进入同一份历史，可在界面中撤销。

多项编辑可用 `workspace_apply_edits` 原子提交（全部成功或全部不生效），也可先用 `workspace_preview_edits` 试运行，检查几何问题而不改动工程。接口说明与调用示例见 [MCP 使用与接口](docs/zh/agent/MCP_AUTHORING.md)。MCP 本身不生成图片，新增素材需要宿主具备图像生成能力。工具可调用不代表复杂建模任务已经可靠，[能力实测](docs/zh/STATUS.md)记录了真实任务的成功与失败样本。

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

| 使用 | 参考 |
| --- | --- |
| [操作速查](docs/zh/guide/USER_GUIDE.md) · [画布编辑](docs/zh/guide/CANVAS_EDITOR.md) · [前后分层](docs/zh/guide/DEPTH_SPLIT.md) | [PSD 素材与命名](docs/zh/spec/PSD_LAYER_SPEC.md) · [变形器与参数](docs/zh/spec/DEFORMER_AND_PARAMETER_SPEC.md) |
| [骨骼与姿态](docs/zh/guide/SKELETON.md) · [摇摆生成](docs/zh/guide/SWING.md) · [物理](docs/zh/guide/PHYSICS.md) | [导出目标](docs/zh/spec/EXPORT_TARGETS.md) · [运行时](docs/zh/spec/RUNTIME.md) · [运行时与导出边界](docs/zh/spec/RUNTIME_EXPORT_ARCHITECTURE_AND_GAPS.md) |
| [模拟与烘焙](docs/zh/guide/SIMULATION.md) · [模拟图文教程](docs/zh/guide/SIMULATION_TUTORIAL.md) | [工程格式](docs/zh/spec/PROJECT_FORMAT.md) · [文档层与生成结果覆盖](docs/zh/spec/DOCUMENT_LAYER.md) |
| [纹理高清化](docs/zh/guide/TEXTURE_UPSCALE.md) · [变形路径](docs/zh/guide/DEFORM_PATHS.md) | [MCP 使用与接口](docs/zh/agent/MCP_AUTHORING.md) · [开发与命令行](docs/zh/guide/DEVELOPMENT.md) |

全部文档见[文档目录](docs/README.md)，示例 PSD 与输出见 [examples](examples/readme.md)。

## 参与贡献

欢迎提交 Issue、可复现的 PSD、文档修正与代码改进。

- **报告问题**：附上版本、操作系统、复现步骤、预期与实际效果，以及日志面板中的相关记录。
- **提交代码**：先运行与改动相关的测试（`./gradlew test`）；新的编辑功能需要能随工程保存并在重开后重放，见[开发与命令行](docs/zh/guide/DEVELOPMENT.md)。
- **Agent 案例**：请同时记录宿主、模型、返工次数与消耗，格式见[能力实测](docs/zh/STATUS.md)。

## 许可

应用整体以 [GPL-3.0](LICENSE) 发布，其中 Live2D 引擎 `umamo`、Cubism 与 PSD 导出目标及应用本身为 GPL-3.0；中立绑定 IR（`format-model`）、导出框架（`format-compile`）、其余导出目标与 Rust 运行时（`runtime/`）以 MIT 发布，可单独复用，见各模块的 `LICENSE` 与[导出目标](docs/zh/spec/EXPORT_TARGETS.md#模块)。第三方组件见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)，示例素材的使用条件见各自说明。

PSD2Live 是独立项目，与 Live2D Inc. 无隶属或赞助关系。本仓库不包含、也不分发 Live2D Cubism SDK 的专有组件；Live2D 和 Cubism 是 Live2D Inc. 的商标。
