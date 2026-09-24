# UI / MCP 双向路径清单（Issue #13）

依据 [Issue #13](https://github.com/tsunehimatoi/psd2live/issues/13) 和当前代码。这里的“可达”表示用户或 MCP 客户端能经公开入口完成该操作；交互形式不必相同。MCP 的公开工具以 [接口契约](MCP_AUTHORING.md) 为准，内部旧工具名不算公开入口。

## 快捷对照

| 范围 | UI 入口 | MCP 入口 | 共用结果 / 边界 |
| --- | --- | --- | --- |
| 项目与源图 | PSD 打开、图层导入、画笔、套索拆分 | `asset.psd/create/add/remove/split`、`paint` | 同一 `SourceArt` → 分析 / Rig 重建；拆分只分配现有 RGBA |
| 图层分类 | Layers 表格：类型、部件、侧别、参数关联、关联 ID | `layer` | 同一 `LayerClassificationOverride`；Issue #13 的核心字段已可写 |
| 生成配置 | 项目设置、单层网格设置 | `settings`、`layer_mesh` | 同一 `PipelineConfig`；网格覆盖写回 UI 状态与历史 |
| 参数 / 预览 | 参数面板、值和锁定 | `parameter`、`preview` | 定义编辑进历史；预览会话只改当前姿态 |
| Rig 结构 / 形变 | 层级树、画布工具、关键形、路径、物理 | `structure/appearance/canvas/form/deform/rig/path/physics` | 复用持久化编辑命令或同一 Rig 模型 |
| 检查 / 历史 | 画布预览、撤销树 | `inspect/view/revision` | `view` 可固定镜头批量采样；UI 适合交互查看 |
| 保存 / 导出 | 工程保存、模型导出、PSD 导出 | `revision.save`、`export`、`export_psd` | 使用项目编码、管线导出和 PSD 写入器 |

## 逐项清单

| # | 能力与核心算法 / 状态 | 人类 UI 路径 | MCP 路径 | 验收重点 |
| ---: | --- | --- | --- | --- |
| 1 | PSD 导入：`PSD2LivePipeline.buildPreview(Path)` | 打开 PSD、分析 | `asset.psd` | 从本地绝对路径进入空工作区，建立分析、Rig 和历史 |
| 2 | 自动语义识别：`Analyzer` / `RigBuilder` | 导入后自动分析 | `asset.psd` 或 `asset.create` 后生成 | 手动覆盖不应丢失自动推断之外的图层 |
| 3 | 图层类型：`LayerClassificationOverride.type` | Layers 表格“类型” | `layer.type` | preset / toggle / switch 触发模型重建 |
| 4 | 语义部件与侧别：`tag/side` | Layers 表格“部件 / 侧别” | `layer.role/side` | 省略字段保持当前值 |
| 5 | 差分关联：`parameter/switchId` | Layers 表格“参数关联 / 关联 ID” | `layer.parameter/switch_id` | 这是 Issue #13 的直接验收项；参数定义与图层分类是两条不同路径 |
| 6 | 源图拆分：`AgentWorkspaceDocument.splitSource` 多边形二元像素分配 | 画布套索后在图层菜单“按上次套索拆分源图层” | `asset.split` | 内外两块均须非空；不会生成遮挡区域的新像素 |
| 7 | 图层添加 / 软删除：`SourceArt` 和可见性层 | 层级菜单 PNG 导入 / 删除 | `asset.add/remove`；新增图可先 `reference/import/register/preview` | 新图位置、层级和可见性需复核 |
| 8 | 源图绘画：`LayerPaintEngine` | 画笔、橡皮、油漆桶、形状 | `paint` 五种模式 | 坐标为画布像素；清空后软删除该层，最后一层不可清空；MCP 编辑需在目标网格关键形 / Glue 前完成 |
| 9 | 全局与单层网格：`MeshSettings` / 自适应网格生成 | 项目设置、图层菜单网格设置 | `settings`、`layer_mesh` | 单层覆盖写入 UI、历史与重建配置；`inspect.layers` 可读有效值 |
| 10 | 图集与高清化：`PipelineConfig` / `TextureUpscale` | 项目设置与导出选项 | `settings.textureUpscale` 等字段 | 2/4 倍输出需要本机高清化模型配置 |
| 11 | 参数定义 CRUD：`RigParameterEdit` | 参数面板 | `parameter.create/update/delete` | 删除参数时在原默认值折叠关键形轴 |
| 12 | 参数文件夹 / XY 关联：持久结构编辑 | 参数树操作 | `structure` 中 param_group / link | 结构关系可保存、可历史恢复 |
| 13 | 当前预览值 / 锁定：`parameterValues/lockedParameters` | 参数滑杆、锁定 | `inspect.preview`、`preview.set/reset` | 只改预览会话，不产生关键形历史 |
| 14 | 关键形增删与复制：`RigAuthoringJournal` | 关键形面板、画布 | `form.seed/copy/set/delete` | 精确参数坐标，其他轴不应被意外覆盖 |
| 15 | 连续形变：Mesh / Warp 几何编辑 | 画布形变笔刷与变换 | `deform` | 明确绑定轴；操作作用于局部形状，父级运动继承 |
| 16 | 拓扑：`CanvasEdits` | 画布网格工具 | `canvas.topology` | 持久化后重新打开仍有效 |
| 17 | Warp：`CanvasEdits` / 拟合创建 | 画布创建 Warp | `canvas.warp`、`rig` | 父级和包含对象需符合现有 Rig 关系 |
| 18 | Rotation：`CanvasEdits` | 画布创建 Rotation | `canvas.rotation` | 保存旋转中心、父级与初始姿态 |
| 19 | 对象层级：`RigStructureEdits` | 层级树拖放 / 菜单 | `appearance`、`structure` | 移动后继承运动发生变化 |
| 20 | 静态属性 / 遮罩：`RigStructureEdits` | 属性面板与层级菜单 | `structure.static` | 与动画关键形通道分开验证 |
| 21 | Glue：`CanvasEdits` | 画布 Glue 工具 | `canvas.glue` | 两侧 Mesh 和权重需匹配 |
| 22 | 变形路径：路径编辑命令 | 画布路径工具 | `path` | 点位为 Mesh 局部坐标，区分预览与烘焙 |
| 23 | 物理：预设及 `RigPhysicsEdit` | 项目物理设置 | `settings`、`physics.put/delete` | 静态姿态无法证明摆锤动态正确 |
| 24 | 动作：导出设置与运行时采样 | 动作配置 / 预览 | `settings`、`view.motion`、`export` | 动作输出和时间序列预览分别验收 |
| 25 | 观察：模型渲染 / 覆盖检查 | 画布、预览、历史界面 | `view.model/layer/context/poses/coverage/compare/motion` | 比较时保持同一画布矩形与参数姿态 |
| 26 | 历史：`WorkspaceHistoryTree` | 历史树撤回 / 切换 | `revision.checkpoint/list/restore` | 从旧节点继续编辑保留分支，UI 套索拆分记为 user |
| 27 | 工程保存：工程状态编码 | 保存工程 | `revision.save` | 保存位置由当前 UI 工程决定 |
| 28 | 输出：`pipeline.run` / `PsdWriter.write` | 模型导出、PSD 导出 | `export`、`export_psd` | 返回实际文件；导出不推进编辑历史 |

## 共用规则和测试

- 写模型的 MCP 请求带当前历史 `state`；若 UI 同时改动导致过期，客户端应重读 `inspect`。预览参数不写模型历史。
- 源图绘画和拆分会改变网格拓扑。拆分在已有运动绑定、Glue 时拒绝；MCP 绘画在目标已有关键形、Warp 或 Glue 时拒绝。UI 绘画另有交互式网格迁移流程。
- `LayerClassificationIntegrationTest` 覆盖 Issue #13 分类字段、绘画像素和历史回退、单层网格、预览锁定、模型与 PSD 导出、PSD 再导入、套索对应的拆分算法及 user 历史归属。
- `AuthoringParityTest` 检查 20 个公开 MCP 工具的注册与分类字段合并。全量验证命令：`./gradlew test --offline`。
