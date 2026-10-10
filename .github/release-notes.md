修复版本：崩溃后可恢复未保存的编辑；修复骨架与网格重建合并后手臂脱离肩膀等问题，并在画布上提示可修复的旧合并结果；GIF 颜色更准，分层 PSD 正确导出高清图层，内存占用与工程体积更小。

## 主要更新

### 新增

- **崩溃后恢复未保存的编辑**：程序崩溃或因内存不足被关闭后，下次启动会询问是否恢复上次未保存的编辑。
- **旧合并结果修复提示**：工程中有旧版本合并、可能导致骨骼表现异常（如弯曲小臂时手臂脱离肩膀）的生成结果时，画布顶部提示，可直接“更新生成结果”或“预览变化”，之后的编辑都会保留。
- **历史面板“回到最新”**。

### 改进

- 参数名称列更宽不再截断；物理预设按组记忆；窗口记住上次的大小与最大化状态；导出进度条在未报告进度时显示为不确定状态。
- 内存上限改为本机内存的一半，不再固定 8 GB；保存的工程不再带上 MCP 渲染的视图图片，体积更小。
- GIF 每个动作生成自己的调色板并默认抖动，不再偏色、出现色块。
- MCP：纹理超出预算时给出提示与处理方法；`view_sample_motion`、`model_apply_preset`、`motion_seed_builtin` 的失败与警告更清楚。

### 修复

- **骨架与再生成合并**：重建网格与创建骨架无论先后，手臂都不再脱离肩膀、袖子不再被重复带动；被骨架接管的变形器下的网格保持画布位置；“工具 → 更新生成结果”会按新规则修复旧合并。
- 分层 PSD 导出 2 倍分辨率图层时不再只显示左上四分之一。
- 闭嘴时不再多出张嘴图层的线条。
- 文本框快速输入不再丢字，退格不再需要按两次。
- 精灵图帧数不匹配时在渲染前就报错；从源码运行时 rustc 过旧不再导致构建失败；MCP 不再无限保留请求结果，对话框中的命令示例隐藏令牌。

<details>
<summary>English</summary>

A fix release: unsaved edits can be restored after a crash; arms no longer come off the shoulder after a mesh rebuild and a skeleton merge, and the canvas points out older merges that can be repaired; GIF colors are truer, layered PSD exports high-resolution layers correctly, and memory use and project size are smaller.

## Highlights

### New

- **Restore unsaved edits after a crash**: after a crash or an out-of-memory kill, the next start offers to restore the unsaved edits.
- **Repair hint for older merges**: when a project holds generated results merged by an earlier version that may misbehave (such as the arm leaving the shoulder when the forearm bends), the canvas shows a notice with Update generated rig and Preview changes; later edits are kept.
- **Latest button in the history panel**.

### Improvements

- The parameter name column is wider and no longer cuts names; physics presets are remembered per group; the window reopens at its last size and maximized state; the export progress bar is indeterminate until progress is reported.
- The memory limit is half the machine's memory instead of a fixed 8 GB; saved projects leave out the views MCP tools rendered and are smaller.
- GIFs use a palette from each motion's own frames with dithering on by default, without color shifts or blotches.
- MCP: texture budget overruns come with notices and remedies; failures and warnings of `view_sample_motion`, `model_apply_preset` and `motion_seed_builtin` say what happened.

### Fixes

- **Skeleton and regeneration merges**: whichever comes first, a mesh rebuild or the skeleton, the arm no longer leaves the shoulder and sleeves are not moved twice; meshes under deformers the skeleton takes over keep their place on the canvas; Tools → Update generated rig repairs older merges by the new rules.
- Layered PSD export no longer shows only the top-left quarter of a 2x layer.
- A closed mouth no longer shows the open-mouth layer's line.
- Typing fast in text fields no longer drops characters, and Backspace no longer needs two presses.
- Sprite sheets report a mismatched frame count before rendering; an old rustc no longer fails a source build; MCP no longer keeps every request result, and the dialog's command snippet hides the token.

</details>
