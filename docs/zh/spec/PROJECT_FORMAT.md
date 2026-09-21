# 工程格式 v1

[English](../../en/spec/PROJECT_FORMAT.md) · [文档目录](../../README.md) · [操作速查](../guide/USER_GUIDE.md)

`.psd2live` 是未加密 ZIP，JSON 使用 UTF-8，栅格资源为 PNG。保存后的工程包含继续编辑所需的源素材与历史，不依赖原 PSD 路径。导出报告 `.psd2live.json` 不是此格式。

## 归档布局

| 路径 | 内容 |
| --- | --- |
| `manifest.json` | 格式名、版本、工程 UUID、载荷 SHA-256 清单 |
| `source/original.psd` | 原始导入源文件 |
| `workspace.json` | 布局、镜头、选择、参数预览、历史注释和日志等持久 UI 状态 |
| `images/<hash>.png` | 日志图片 |
| `workspace/<projectId>/HEAD.json` | 当前节点与节点顺序 |
| `workspace/<projectId>/history/nodes/` | 不可变父链节点和元数据 |
| `workspace/<projectId>/history/snapshots/` | 源图层、配置、结构与编辑覆盖 |
| `workspace/<projectId>/blobs/` | 去重 RGBA 栅格，以 PNG 保存 |
| `workspace/<projectId>/assets/` | 暂存素材元数据 |
| `workspace/<projectId>/views/`、`view-images/` | 观察图的空间映射与图像 |
| `workspace/<projectId>/workflow/` | 素材参考包、注册 / 放置等辅助记录 |
| `workspace/<projectId>/tasks.json` | Agent 任务和事件记录 |

内部文件名可使用逻辑 ID 的哈希，不能由显示名推断。PNG 保留透明像素下的 RGB；不同快照共享栅格资源。辅助目录按是否使用相关功能出现。

## 保存和恢复

保存捕获不可变状态，按顺序写入同目录临时文件、校验清单，再原子替换目标。不支持原子替换时报告失败并保留旧工程。捕获后发生的新编辑仍属于未保存内容。

当前内容与 HEAD 相同时，普通保存不新增历史；显式 `checkpoint` 可在未变化时留点。保存失败不应被当作已持久化，需检查界面错误。

所有分支保留。撤销沿父节点，重做有多个后继时选择分支；从旧节点编辑会创建新分支。改标题、备注或隐藏分支不改写原始节点，不删除素材。

重开工程由源图、设置和编辑日志重建模型。原生句柄、网络连接、正在执行的任务和动画时钟不保存；保存的 Agent 任务是记录，不会自动恢复执行。

## 校验边界

打开时校验版本、清单、哈希、栅格、历史引用与 HEAD。拒绝重复条目、路径越界和不支持版本；解包限制为最多 1,000,000 条目、实际解压数据 64 GiB。不要依赖 ZIP 声明尺寸绕过限制。

可解压查看，但手工修改需同步全部引用与清单哈希。常规操作使用界面和历史工具。旧 `.rgba.gz` 恢复存储属于兼容读取，不是新工程的主写入格式。

## 入口

- 导入 PSD：`Ctrl+Shift+O`；打开工程：`Ctrl+O`。
- 保存 / 另存为：`Ctrl+S` / `Ctrl+Shift+S`。
- MCP：`revision` 的 `save/checkpoint/list/restore`；保存目的地先在 UI 选择。公开摘要不等于所有内部工程字段均可查询，见 [MCP 契约](../agent/MCP_AUTHORING.md)。

实现：[ProjectArchive](../../../src/main/kotlin/io/github/psd2live/project/ProjectArchive.kt) · [ProjectSession](../../../src/main/kotlin/io/github/psd2live/project/ProjectSession.kt) · [AgentWorkspaceStore](../../../src/main/kotlin/io/github/psd2live/agent/AgentWorkspaceStore.kt)。
