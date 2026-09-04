# PSD2Live 用户操作指南 (User Guide)

[English](../en/USER_GUIDE.md) | [日本語](../ja/USER_GUIDE.md)

PSD2Live 提供桌面图形交互界面（GUI）、自动化命令行批处理工具（CLI），以及供外部 AI 宿主使用的本机 MCP 工作区。本指南介绍四个主工作区、独立日志坞、Agent 连接与恢复、视口交互、参数微调和导出流程。

---

## 目录

- [环境要求](#环境要求)
- [启动方式](#启动方式)
- [桌面 GUI 概览](#桌面-gui-概览)
- [工作区四大视图](#工作区四大视图)
  - [1. 层级视图 (Hierarchy)](#1-层级视图-hierarchy)
  - [2. 拓扑视图 (Topology)](#2-拓扑视图-topology)
  - [3. 预览视图 (Preview)](#3-预览视图-preview)
  - [4. 历史记录视图 (History)](#4-历史记录视图-history)
- [独立日志坞](#独立日志坞)
- [连接 AI Agent / MCP](#连接-ai-agent--mcp)
- [视口交互与常用快捷键](#视口交互与常用快捷键)
- [右侧检视器面板](#右侧检视器面板)
  - [导出控制区域](#导出控制区域)
  - [模型参数设置](#模型参数设置)
  - [图层管理表格](#图层管理表格)
  - [参数调试面板](#参数调试面板)
- [端到端生成工作流](#端到端生成工作流)
- [CLI 命令行参考](#cli-命令行参考)
- [常见问题与 FAQ](#常见问题与-faq)

---

## 环境要求

- **操作系统**：Windows 10/11 x64（推荐，支持官方 Cubism 5-r.5 原生着色器渲染引擎；Linux / macOS 自动降级为 CPU 软件光栅化渲染）。
- **Java 环境**：JDK 21 或更高版本（推荐 JetBrains Runtime / OpenJDK 21+）。
- **输入文件**：分层规范的 `.psd` 文件（8 位 RGB 颜色模式，带 Alpha 透明通道）。

---

## 启动方式

### 1. Windows 一键快速启动
双击项目根目录下的 `run-gui.bat` 脚本即可启动。

### 2. Gradle 命令行启动
```powershell
# Windows (PowerShell)
.\umamo\gradlew.bat -p .\psd2live run

# Linux / macOS (Bash)
../umamo/gradlew -p ./psd2live run
```

---

## 桌面 GUI 概览

应用主窗口采用 **左侧主工作区 + 右侧检视器面板 + 底部独立日志坞与状态栏** 布局。

- **顶部菜单栏**：
  - **文件 (File)**：`打开 PSD...` (`Ctrl + O`)、`重新分析` (`Ctrl + R`)、`打开输出目录`、`生成并导出` (`Ctrl + G`)、`导出到...` (`Ctrl + Shift + G`)、`退出`。
  - **语言 (Language)**：支持简体中文 (`zh`)、英文 (`en`)、日文 (`ja`) 实时无缝切换。
  - **Agent / MCP**：打开连接与技能配置，或直接切换到版本历史树；右上角状态徽标可再次打开连接窗口。
  - **帮助 (Help)**：版本信息、第三方组件与许可证说明。
- **工作区分隔条**：鼠标拖拽可自由调节左侧工作区与右侧检视器的宽度比例（`25% ~ 85%`）。

---

## 工作区四大视图

### 1. 层级视图 (Hierarchy)
- **变形器与画元拓扑树**：完整呈现 Live2D 变形器父子层级关系（`BodyXY` $\to$ `BodyZ_Breath` $\to$ `HeadRotation` $\to$ `HeadContainer` $\to$ `FaceNinePose` $\to$ 五官解耦 Warp $\to$ 画元 ArtMesh）。
- **VS Code 风格指引线**：具备清晰的缩进指引线与折叠/展开箭头。
- **侧边标签折叠**：点击顶栏折叠按钮可将层级树最小化为侧边紧凑标签，最大化画布视口面积。

### 2. 拓扑视图 (Topology)
- **自适应网格线框**：在半透明画元上方高亮显示基于受约束 Delaunay 剖分生成的网格拓扑。
- **画元选中高亮**：选中的 ArtMesh 以粗线框（2.2px）突出显示。
- **拓扑统计指示器**：右上角实时显示活跃画元总数、顶点总数、三角面总数以及当前视口缩放比例。

### 3. 预览视图 (Preview)
- **原生 Cubism 5-r.5 渲染引擎 (可选)**：在 Windows x86-64 环境下，支持通过 JNA 调用 Live2D 官方原生着色器离屏渲染库（`live2d_renderer.dll`），实现与官方 Cubism 运行时 **100% 忠实一致的像素渲染、蒙版裁切与物理动力学验证（Ground Truth）**。
  - *注：本项目严格遵守 Live2D 专有许可协议，源码与发布包中不包含且不分发官方 SDK 二进制。配置方法请参阅 [Live2D Cubism SDK 配置指南](CUBISM_SDK_SETUP.md)。*
- **内置纯 CPU 软件光栅化渲染器**：未配置官方 SDK 或非 Windows 环境（macOS / Linux）下自动无缝启用，完全无需任何配置即可开箱即用查看实时变形与参数调试。
- **交互功能**：
  - 实时鼠标视线注视追踪（Mouse Look）；
  - 6 秒无缝循环呼吸起伏与自然眨眼待机动作；
  - 眨眼速度物理驱动果冻眼挤压回弹（`ParamEyeBallForm`）。
- **状态指示徽标**：左下角常驻显示当前视口缩放比、底层渲染引擎类型（`原生 Cubism` 或 `软件光栅化`）以及物理计算状态。

### 4. 历史记录视图 (History)
- **追加式分支树**：显示系统、用户与 Agent 产生的全部不可变节点，绿色 `HEAD` 标记当前工作区版本；从旧节点继续编辑会产生新分支，原分支不会被删除。
- **导航与定位**：拖动画布平移，通过 `-` / `+` 缩放或重置视图，并按摘要、节点 ID 或操作者搜索。
- **检查与恢复**：点选节点可查看节点 ID、父节点、版本 Hash、操作者与时间；“恢复到此版本”会重建该节点对应的可编辑素材与 Rig。恢复不会删除任何节点。

## 独立日志坞

日志不再占用主工作区标签，而是常驻于层级、拓扑、预览和历史视图下方：

- 点击标题栏左侧箭头可折叠/展开；拖动上边缘可在 `80 ~ 450 px` 范围内调整高度。
- 可按“全部”“系统”“Agent / MCP”“仅图片”筛选，也可搜索消息、标签与详情并切换自动滚动；标题栏显示日志数与图片数。
- View 渲染和素材导入结果以内嵌缩略图显示，点击后可在带棋盘背景的灯箱中查看尺寸与文件大小，或复制图片。
- “清空”只清除当前界面日志，“复制日志”复制当前筛选后的文本，不会修改版本历史或任务记录。

## 连接 AI Agent / MCP

<p align="center">
  <img src="../imgs/agent.png" alt="PSD2Live AI Agent 素材生成、接入与多参数渲染流程" />
  <br>
  <em>从模型 View 取证，到宿主原生图片生成、图层回填和多参数姿态验证</em>
</p>

PSD2Live 启动时会在 `127.0.0.1:23871/mcp` 提供带 Bearer Token 的 Streamable HTTP MCP。应用必须保持运行；Token 视同本机工作区写权限，不要公开或提交到版本库。

1. 打开顶部 **Agent / MCP → Agent / MCP 连接与安装…**。
2. 在“连接配置”页复制对应配置；优先使用原生 Streamable HTTP：
   - ChatGPT Desktop / Codex：把 HTTP TOML 合并到 `~/.codex/config.toml`，或受信任项目的 `.codex/config.toml`，重启客户端后用 `/mcp` 检查。
   - Gemini / Antigravity：把 HTTP JSON 中的 `psd2live` 条目合并到 `~/.gemini/config/mcp_config.json`，刷新 MCP Servers。
   - 其他 HTTP 宿主：使用界面显示的端点，并发送 `Authorization: Bearer <Token>`；不要使用旧 `/sse` 地址。
   - 仅支持 Stdio 的宿主：复制 Stdio JSON，通过 Python 3 运行仓库根目录的 `mcp_proxy.py`。代理读取 `PSD2LIVE_MCP_ENDPOINT`（默认上述地址）、`PSD2LIVE_MCP_TOKEN` 和可选的 `PSD2LIVE_MCP_TIMEOUT`；Windows 上未设置 Token 时会尝试读取 PSD2Live 保存的凭据。
3. 在“安装 Prompt”页复制完整安装说明。需要领域工作流时，把 `.agent/skills/psd2live-rigging` 和 `.agent/skills/hair-separation` 复制到宿主官方 Skill 目录；连接后先列出工具并调用 `project_get_state`。

当前工具覆盖：

| 能力 | 工具 |
| :--- | :--- |
| 工程取证 | `project_get_state`、`project_list_layers`、`project_list_parameters`、`object_get` |
| 参数与 K 帧 | `parameter_create`、`parameter_update`、`parameter_delete`、`keyform_set`、`keyform_copy`、`keyform_delete`、`rig_k_pose` |
| 模型 View | `view_render_layer`、`view_render_context`、`view_render_model` |
| 透明素材与图层 | `asset_import_png`、`layer_add_from_asset`、`layer_soft_delete` |
| 恢复与长任务 | `history_list`、`history_checkout`、`task_start`、`task_update`、`task_get`、`task_list` |

每次会推进工程 `HEAD` 的编辑操作必须携带当前 `expected_history_head_node_id`，成功后使用返回的新节点作为下一次写入基准。暂存 PNG 与任务事件不会移动 `HEAD`。若请求超时、断线或会话失效，写入结果可能未知；重新连接后先检查 `project_get_state`、`history_list`、任务记录和目标对象，再决定是否重试。Stdio 代理只会自动重试只读调用，不会盲目重放编辑操作。

PNG View 来自模型数据而非 UI 截图，并携带可逆的像素↔画布映射。`asset_import_png` 会通过 `spatial_reference_id` 保留位置和尺寸；若只返回 View 的一个裁剪区域，还须声明 `source_pixel_rect`，长宽比不一致会被拒绝而不是拉伸。差分、部件拆分、遮挡补全或像素重建必须实际调用宿主原生的 Nano Banana Pro/NBP、GPT Image 2（`gpt-image-2`）或等效图片工具；PSD2Live MCP 只负责 View 与导入，不能用 Python、PIL/OpenCV、SVG 或 Canvas 绘制替代素材。

历史树、任务、空间参考和按 SHA-256 去重的 RGBA 素材会持久化。Windows 默认目录是 `%LOCALAPPDATA%/PSD2Live/agent-workspaces`，可通过 JVM 属性 `psd2live.agent.store` 修改。重新载入路径和文件签名均相同的 PSD 时，会恢复最后的 `HEAD`。

---

## 视口交互与常用快捷键

| 操作 | 快捷键 / 鼠标指令 | 功能说明 |
| :--- | :--- | :--- |
| **画布缩放** | 鼠标滚轮 (`Scroll`) | 以光标位置为中心的连续缩放 (`0.05x ~ 64.0x`) |
| **画布平移** | 鼠标中键拖拽 或 左键空白拖拽 | 平移视口画布 |
| **居中适配** | `F` / `Home` / `0` | 重置摄像机，居中完整显示模型 |
| **点选画元** | 鼠标左键单击画元 | 选中目标画元，层级树与图层表格同步高亮定位 |
| **打开 PSD** | `Ctrl + O` | 打开系统文件选择器加载 PSD |
| **重新分析** | `Ctrl + R` | 重新读取并评估当前已加载的 PSD 文件 |
| **生成并导出**| `Ctrl + G` | 执行全自动导出流程（输出到默认同级目录） |
| **导出到...** | `Ctrl + Shift + G` | 手动选择目标文件夹并执行完整导出 |

---

## 右侧检视器面板

### 导出控制区域
- 输出目录路径显示及“浏览”按钮；
- 导出格式开关勾选框：`物理模拟 (Physics)`、`CMO3 工程`、`MOC3 运行时`；
- 主操作按钮：`生成并导出模型`。

### 模型参数设置
- **贴图集尺寸 (Atlas Size)**：预设常用尺寸（`1024`、`2048`、`4096`、`8192`、`16384`）及数值微调步进器（`256 ~ 16384`）。
- **网格间距 (Mesh Spacing)**：连续滑块（`16 ~ 128 px`），提供 `32`、`64`、`96` 像素快速预设。
- **头部转动幅度 (Head Strength)**：九轴经纬网与五官透视形变系数（`0.0x ~ 4.0x`，默认 `1.0x`）。
- **身体动作幅度 (Body Strength)**：躯干动作与胸腔呼吸起伏幅度系数（`0.0x ~ 4.0x`，默认 `1.0x`）。
- **高级渲染参数**：图集图元间距（Padding，默认 `2px`）与 Alpha 二值化阈值（默认 `8`）。

### 图层管理表格
- 概览数据汇总：显示图层可见数/总数、识别命中数、未识别数；
- 批量控制：`显示全部`、`隐藏全部`、`反选`；
- 表格列属性：图层显示/隐藏眼睛图标、图层名称与序号、语义标签下拉选择（31 种核心标签）、侧别下拉选择（`NONE`/`LEFT`/`RIGHT`）。

### 参数调试面板
- 参数实时搜索框：支持按参数名称或参数 ID 实时过滤；
- 全局控制条：动画播放/暂停、鼠标视线追踪跟随开关、全部解锁、全部重置；
- 参数行组件：图钉固定锁定按钮、参数显示名称与参数 ID、数值滑动条、精确数值调节框、单项复位按钮。

---

## 端到端生成工作流

1. **载入 PSD**：将准备好的分层 `.psd` 文件拖入应用窗口，或使用 `Ctrl + O` 打开；
2. **审查与核对**：
   - 在图层表格中核验五官与部件语义分类，对未命中（`unknown`）图层进行手动重新指定；
   - 在拓扑视图中检查自动生成的三角剖分精度与边界贴合；
   - 在预览视图中测试鼠标视线跟随、面部九轴转动与物理摆动效果；
3. **可选 Agent 精修**：连接 MCP 宿主，用模型 View 取证并进行参数、K 帧或素材修改；在历史树中确认当前 `HEAD`，必要时恢复旧节点后从该处建立分支。
4. **导出模型**：配置贴图集尺寸与目标路径，点击 `Ctrl + G`（或“生成并导出模型”），系统将同步导出 `.cmo3` 编辑器工程与 `.moc3` 运行时资产族。

---

## CLI 命令行参考

```powershell
# 基础运行导出
.\umamo\gradlew.bat -p .\psd2live run --args="--input D:/models/character.psd --output D:/dist/character"

# 进阶参数调优
.\umamo\gradlew.bat -p .\psd2live run --args="--input D:/models/character.psd --output D:/dist/character --atlas 8192 --mesh-spacing 48 --head-strength 1.2 --lang zh"
```

| 参数 | 类型 | 默认值 | 说明 |
| :--- | :---: | :---: | :--- |
| `--input <path>` | 路径 | *(必需)* | 输入分层 PSD 文件路径 |
| `--output <path>` | 路径 | `PSD同级/psd2live-output` | 模型文件输出目录 |
| `--lang <zh\|en\|ja>` | 字符串 | 系统语言 | 界面与控制台日志语言（支持 `zh` / `en` / `ja`） |
| `--atlas <size>` | 整数 | `4096` | 纹理贴图集边长（`256 ~ 16384`） |
| `--mesh-spacing <px>` | 整数 | `64` | 网格采样基础间距（像素） |
| `--head-strength <val>` | 浮点数 | `1.0` | 头部九轴面部经纬网变形幅度（`0.0 ~ 4.0`） |
| `--body-strength <val>` | 浮点数 | `1.0` | 身体躯干动作与呼吸膨胀幅度（`0.0 ~ 4.0`） |
| `--no-physics` | 开关 | `false` | 跳过生成 `physics3.json` 与 CMO3 物理连线 |
| `--no-cmo3` | 开关 | `false` | 跳过导出 `.cmo3` 编辑器工程 |
| `--no-moc3` | 开关 | `false` | 跳过导出 `.moc3` 运行时文件 |
| `--help` / `-h` | 开关 | - | 显示 CLI 帮助文本 |

---

## 常见问题与 FAQ

- **Q: Agent 无法连接，或重新连接后返回会话已过期？**
  A: 确认 PSD2Live 保持运行，并从连接窗口重新复制当前端点和 Token。原生 HTTP 宿主应连接 `/mcp` 而非 `/sse`；Stdio 宿主应使用 `mcp_proxy.py`。会话失效后重新初始化，并在继续写入前读取 `project_get_state` 与 `history_list`。

- **Q: 恢复旧历史节点会删除后续修改吗？**
  A: 不会。历史节点只追加、不改写。恢复只移动工作区 `HEAD`；从旧节点继续编辑会建立新分支，原来的后续分支仍可查看与恢复。

- **Q: 启动时提示 Java 运行时版本错误？**  
  A: 请确保系统中安装了 JDK 21 或更高版本，且环境变量 `JAVA_HOME` 正确指向该 JDK 路径。

- **Q: 为什么部分图层被识别为 unknown？**  
  A: 未匹配到命名别名规则的图层会归为 unknown。系统会自动按空间包围盒中心归入头部或身体容器，您可以在右侧检视器的“图层”表格中手动下拉修改标签。

- **Q: 闭眼时为什么睫毛出现变形畸变、撕裂或重影？**  
  A: 请检查 `eyelash` 图层是否混入了下睫毛或完整下眼眶线。系统闭眼算法是提取上睫毛图层的 Alpha 权重中心线向下弯曲压缩为 U 形闭眼线；若图层包含下睫毛，Alpha 质心会被下拉至眼球中央，造成闭眼时上下睫毛相互挤压撕裂。**睫毛图层必须仅包含眼部上半部分（上睫毛）**，下睫毛请放置于 `facedetail` 或静态图层中。

- **Q: 为什么闭嘴时唇线模糊或色块发脏粘连？**  
  A: 原画立绘中的 mouth 必须为**最大张口状态**，且**嘴唇外边缘带有清晰描边/线稿效果更佳**。向心闭合算法在完全闭口时将上下唇向中线极度压缩，带有清晰描边的唇线能干净融合成自然的闭口缝；若缺乏描边而是纯厚涂渐变，极限压缩容易引起边缘色块虚化或与周围肤色混浊粘连。

- **Q: 角色原画立绘头部自带歪头倾斜，会被强制摆正吗？转动范围如何计算？**  
  A: 不会被强行摆正。系统会自动通过双眼基线和面部中轴特征估算头部的初始倾斜角（$\text{initialAngleZ}$），并将头部旋转变形器基准轴心与其对齐。面部九轴形变与转动范围（`ParamAngleZ` $\pm 30^\circ$）**会以原画的初始角度作为中立原点展开确认转动范围**，建议初始歪头控制在自然范围内（$\pm 25^\circ$ 以内）。

- **Q: 支持大角度横躺、俯卧或过于倾斜的身体原画吗？**  
  A: **过于倾斜的身体不受支持**。由于身体偏航（`ParamBodyAngleX`）与胸腔呼吸起伏（`ParamBreath`，以纵向 $v \approx 0.42$ 为起伏中心）均基于竖直画布坐标系解耦构建，身体过度倾斜会导致呼吸起伏方向横向错位以及非线性旋转扭曲撕裂。角色立绘身体应保持基本垂直正立。

- **Q: 为什么闭眼时瞳孔仍然可见？**  
  A: 瞳孔（`irides`）依赖眼白（`eyewhite`）作为剪切蒙版。请确认 PSD 中存在命名正确的 `eyewhite` 图层，以便闭眼时眼白收缩自动将瞳孔遮罩隐藏。

- **Q: 导出的 `.cmo3` 在 Live2D Cubism Modeler 中打开时提示“缺少原画”？**  
  A: 这是正常现象。PSD2Live 生成的 CMO3 基准网格直接构建于生成的纹理贴图集图元切片（`MissingSourceArt` 属于预期设计）。所有关键形态变形、变形器层级与参数绑定完全可正常编辑。

- **Q: 为什么预览视图显示“软件光栅化”？如何开启官方 Cubism 运行时渲染一致性验证？**  
  A: PSD2Live 严格遵守开源合规与 Live2D 专有许可协议，**源码与发行包中不包含且不分发 Live2D 官方专有 SDK 二进制**。未配置 SDK 时，系统默认采用内置的高精度纯 CPU 软件光栅化渲染器（这完全不影响模型分析、变形构建与工程导出）。若希望在 64 位 Windows 下启用官方着色器以获得与游戏客户端/Viewer **100% 像素级一致的渲染与物理表现（Ground Truth）**，请参阅专门的 [Live2D Cubism SDK 配置指南](CUBISM_SDK_SETUP.md) 进行配置。
