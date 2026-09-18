# PSD2Live 工程文件格式（版本 1）摘要

> [!NOTE]
> 本页为中文摘要。完整规范（含全部字段语义与校验规则）目前仅有英文版：[工程格式完整规范](../../en/spec/PROJECT_FORMAT.md)。

`.psd2live` 是一个不加密的 ZIP 归档：普通解压工具即可展开，其中 JSON 为 UTF-8 缩进格式，栅格资源为无损 PNG。打开已保存工程时不依赖原始 PSD 路径，也不依赖其他机器的恢复缓存。

## 归档内容

| 条目 | 用途 |
| :--- | :--- |
| `manifest.json` | 格式名、版本、稳定工程 UUID，以及所有载荷文件的 SHA-256 清单 |
| `source/original.psd` | 导入时的原始源文件字节 |
| `workspace.json` | 持久化 UI 状态：布局、相机、选择、参数预览/锁定、历史批注与日志 |
| `images/<sha256>.png` | 日志条目引用的图像 |
| `workspace/<projectId>/HEAD.json` | 当前历史节点与显式节点插入顺序 |
| `workspace/<projectId>/history/nodes/*.json` | 不可变的父链节点：ID、修订、快照引用、摘要、执行者、任务与时间戳 |
| `workspace/<projectId>/history/snapshots/*.json` | 可编辑的源图层/组、可见性、删除标记、分类、层级、参数/关键形覆盖与生成设置 |
| `workspace/<projectId>/blobs/*-<width>x<height>.png` | 去重的 RGBA 图层/素材像素（含透明 alpha 之下的 RGB 值） |
| `workspace/<projectId>/assets/*.json` | 暂存 PNG 素材及其空间放置 |
| `workspace/<projectId>/views/*.json` | 已渲染视图的坐标映射 |
| `workspace/<projectId>/view-images/*` | 渲染出的视图图像与「视图 ID → 图像」记录 |
| `workspace/<projectId>/tasks.json` | Agent 计划、状态记录与追加式任务事件 |

历史记录与栅格引用的内部文件名是其逻辑 ID 的 SHA-256 键。同一份栅格在多个快照间共享，因此反复保存只增加轻量节点，不会重复存储像素。

## 保存与恢复

- 每次成功保存都会先追加一个检查点，再写入归档；失败保存保留检查点与脏状态，并保持原有文件不变。
- 写入流程为：关闭并刷新目标目录中的临时文件 → 校验完整清单 → 原子替换旧文件；不支持原子替换时报告失败而不覆盖。
- 格式保留全部分支且不自动裁剪。切换分支即改变 `HEAD`，此后的编辑追加为新的子节点。隐藏分支不会删除快照、素材或 MCP 可见性。
- 撤销沿父链回退；重做选择唯一子节点，或打开版本树在分支间选择。
- 运行期预览由已保存的可编辑源与各快照自身的设置重建；SDK 句柄、套接字、活动任务与动画时钟不参与序列化。已保存的 Agent 任务是可供显式继续的记录，不是可执行作业。

## 校验与手工编辑

打开工程时会先行校验格式/版本、文件清单、校验和、栅格尺寸与内容、历史 ID、父链、`HEAD` 与环。重复条目、路径穿越/绝对路径与不支持的版本会被拒绝；解压上限为 1,000,000 个条目与 64 GiB 实际未压缩字节，且不信任 ZIP 中声明的大小。

JSON 与 PNG 均可直接查看，但手工改动包内容必须同步更新 manifest 校验和并维持所有引用的 ID 与快照哈希；日常批注与分支操作请使用历史界面。未来的不兼容格式必须使用新的 manifest 版本号，而不是静默重新解释本规范。

## UI 与 MCP 入口

| 操作 | 入口 |
| :--- | :--- |
| 导入 PSD | `Ctrl+Shift+O`（可选自定义目录、PSD 所在目录或安装目录下的 `projects`；确认后立即写入首个工程） |
| 打开 / 保存 / 另存为 | `Ctrl+O` / `Ctrl+S` / `Ctrl+Shift+S` |
| 撤销 / 重做或选择分支 | `Ctrl+Z` / `Ctrl+Y` 或 `Ctrl+Shift+Z` |
| `project_save`（现为 `revision` 的 `save`） | 保存到应用中所选位置并返回检查点节点 ID；未选择目标时报错 |
| `history_checkpoint`（现为 `revision` 的 `checkpoint`） | 携带 `summary` 时显式追加节点（内容未变也追加）。这是唯一一条「无变化也开节点」的路径，其余写入在无变化时返回 `applied: false` 且不开节点 |
| `project_get_state`（现为 `inspect` 的 `scope: project`） | 额外报告 `projectFile`、`projectDirty`、`projectSaving`、`projectSaveError` |

> MCP 服务当前只暴露 10 个合并工具（`inspect`、`deform`、`form`、`rig`、`view`、`parameter`、`asset`、`physics`、`appearance`、`revision`），上表括号内是各分支的底层契约；完整工具面见 [MCP 接口契约](../agent/MCP_AUTHORING.md)。

改动模型的 MCP 调用在校验/重建成功后提交一个节点；只读调用不追加历史。暂存素材与记录 Agent 任务事件属于辅助记录，随工程保存一并持久化。
