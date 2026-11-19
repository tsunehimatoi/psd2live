## v3.8.3 · 2026-11-19

### 改进与修复

- **PSD 导入向导**：创建工程前可预览并拆分断开的网格、检查图层绑定、选择修改器预设和比较头部转向风格。创建时可直接选择进入的工作区。
- **头部与嘴部绑定**：头部转向更贴合画面中的五官和头发。可在画布编辑自动生成的嘴部张开关键形，并单独调整闭嘴素材的透明度曲线。
- **模拟与权重**：支持绘制顶点权重，以及将物理模拟直接烘焙到关键形。
- **VTube Studio 导出**：导出模型选择图标，并使用工程中的动作与表情名称。未分配屏幕按钮的热键写入 `ScreenButton: -1`，修复旧导出可能导致表情编辑器无法打开的问题。([#90](https://github.com/tsunehimatoi/psd2live/issues/90))
- **桌面兼容性**：修复 Linux 打包应用的启动和工作进程问题、Windows 中文路径编码，以及 macOS 打包版命令行导出的工作目录。

<details>
<summary>English</summary>

## v3.8.3 · 2026-11-19

### Improvements and fixes

- **PSD import guide** — Preview and split disconnected meshes, review layer bindings, select a modifier preset, and compare head-turn styles before creating a project. Choose which workspace to open when creation finishes.
- **Head and mouth rigging** — Head turns fit the drawn features and hair more closely. Edit generated mouth-open keyforms on the canvas and adjust the closed-mouth artwork's opacity curve independently.
- **Simulation and weights** — Paint vertex weights and bake physics simulation directly into keyforms.
- **VTube Studio export** — Export a model-selection icon and use the project's motion and expression names. Write `ScreenButton: -1` for unassigned hotkeys to address an Expression Editor issue with older exports. ([#90](https://github.com/tsunehimatoi/psd2live/issues/90))
- **Desktop compatibility** — Fix packaged launcher and worker issues on Linux, encoding for Windows paths containing Chinese characters, and working-directory handling in the packaged macOS CLI.

</details>
