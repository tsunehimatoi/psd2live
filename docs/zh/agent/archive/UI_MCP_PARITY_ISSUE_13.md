# UI / MCP 能力对照

[文档目录](../../../README.md) · [MCP 使用与接口](../MCP_AUTHORING.md) · [Agent 设计与验收](../AGENT_DESIGN.md)

> 归档资料：Issue #13 已于 2026-09-24 关闭，本表停止维护，其中的操作计数、测试类名和缺口描述为当时状态。现行公开操作以 [MCP 接口](../MCP_AUTHORING.md) 为准。

本页列出每项编辑能力在界面与 MCP 中的入口，以及两者共用的数据与验收重点，起源于 [Issue #13](https://github.com/tsunehimatoi/psd2live/issues/13)。「可达」指用户或 MCP 客户端能经公开入口完成该操作，交互形式不必相同。MCP 公开工具以 [MCP 使用与接口](../MCP_AUTHORING.md) 为准，内部适配名称不算公开入口。

逐入口审计收口的骨架编辑、局部画布显隐/层级、设置联动、物理组试听和多步画布草稿五个域已实现共享入口（`e133d37`），其后至 `046a725` 补齐字段会话设置开关、GUI 显隐写入的辅助 CAS 与形变笔刷预览/提交一致性；仍待桌面手动验收，GUI 部分只有 PR CI 证据。显隐/隔离的既有语义是每 workspace/canvas/mode 的持久呈现，不能改变共享模型或导出。下表区分已存在入口与完整业务验收；进度见 [重构进度](REFACTOR_PROGRESS.md)，未结问题见 CLAUDE_HANDOFF.md（交接文件已在 `d4b6307c` 删除，未结事项见[路线图](../../ROADMAP.md)）。

全部 170 项公开操作已有必需的严格输出契约，包括全部 79 项批量成员、复合 inspect、素材以及模拟/预设；58 项后台工具还要求终态契约。真实 HTTP 发布的完整 schema 与执行校验一致。素材准备、导入、配准及重处理已共用独立辅助 CAS 和文件发布边界；输出字段完整不能替代 GUI/MCP 全部业务绑定及双向业务验收。

`workspace_apply_edits` 已将 79 项实际文档修改组合成一个原子候选及一个历史节点，成员与单项调用共用业务 schema 和编辑函数。后续成员读取前序候选模型，包含新建物理组后拟合和创建父 Warp 后嵌套独立 Warp；后台任务报告逐项准备及求解进度；失败、提交前取消、冲突保留整批之前的状态。成功 CAS 后立即记录完整结果，迟到取消或刷新异常保留 completed；断线及原请求重试返回同一任务。以发现结果的 `batchable` 为准，GUI 参数对话框与画布 journal 已进入同一提交边界；字段完成已接入异步候选队列，其余业务准备和文档命令尚在迁移；详见 [接口说明](../MCP_AUTHORING.md#原子文档编辑) 和 [验收进度](REFACTOR_PROGRESS.md)。

## 快捷对照

| 范围 | UI 入口 | MCP 入口 | 共用结果 / 边界 |
| --- | --- | --- | --- |
| 项目与源图 | PSD 打开、图层导入、画笔、网格连通块及深度拆分对话框 | `project_import_psd / project_create_artwork / layer_add_from_asset / layer_soft_delete / layer_restore / source_get_components / source_split_components / source_split_polygon / source_split_depth`、`source_paint_brush / source_paint_pencil / source_paint_eraser / source_paint_bucket / source_paint_shape / source_paint_clear` | 同一 `SourceArt` → 分析 / Rig 重建；分区分配现有 RGBA；深度拆分复制所选网格及运动 |
| 拆分记录升级 | 工具 > 升级拆分记录 | `source_upgrade_split_records` | `WorkspaceSplitUpgradeCommands` 与纯候选 `WorkspaceSplitUpgradeEdits` 共用；MCP 进程任务与原子批量成员，GUI 升级全部版本 1 记录；一次可撤销历史节点，无升级时不追加，逐条结果含未升级原因 |
| CMO3 导入 | 新工程导入、替换导入 | `project_import_cmo3` | 独立应用层导入器与同一源图接口；MCP 进程任务，新建默认拒绝未保存切换，替换一次历史提交并保留未出现对象和辅助数据 |
| 图层分类 | Layers 表格：类型、部件、侧别、参数关联、关联 ID | `layer_classify` | 同一应用命令与纯候选；MCP 后台任务，省略字段取捕获值，GUI 文字会话结束时提交一次 |
| 生成配置 | 项目设置、单层网格设置 | `settings_update`、`layer_mesh_update` | 共享生成候选/重建/CAS；MCP 后台任务，GUI 连续拖动结束时提交，单层预览确认正式重建 |
| 参数 / 预览 | 参数面板、值和锁定 | `parameter_create / parameter_update / parameter_delete`、`preview_set / preview_reset` | 定义编辑进历史；预览设置、重置及快照应用共用独立命令，从已提交姿态/锁合并并使用辅助 CAS；GUI 完成姿态走中立端口，保存投影排除临时值；姿态/自动关键帧共用原子提交，播放/跟踪使用独立临时会话 |
| 参数快照 / 历史注释 | 参数快照栏、历史节点标题/说明/隐藏 | `snapshot_create / snapshot_update / snapshot_delete / snapshot_apply / snapshot_get / snapshot_list`、`history_annotation_put / history_annotation_delete / history_annotation_get` | 中立辅助数据与同一应用命令；修改推进持久 state，保留 Rig 历史；应用快照保留锁，不自动打时间线关键帧 |
| Rig 结构 / 形变 | 层级树、画布工具、关键形、路径、物理 | `rig_edit_structure / object_edit_appearance / canvas_* / keyform_apply / rig_deform / rig_create_warp / path_* / physics_*` | 复用持久化编辑命令或同一 Rig 模型；层级拖放写 structure journal 的 bind/move；Warp/Rotation 放置、knife 与路径草稿在首个输入捕获 state，确认不重取 |
| 局部画布显隐 | 层级眼睛、solo、全部显示/隐藏/反转、变形器眼睛 | `canvas_visibility / canvas_visibility_get` | 同一 `CanvasVisibilityProcessor` 与辅助 CAS，按 workspace/canvas/mode 寻址；不改文档、历史、其他画布或导出 |
| 骨架与姿态 | 骨骼标签页、姿态工具、骨骼编辑工具 | `skeleton_get / skeleton_propose / skeleton_auto / skeleton_put / skeleton_enable / skeleton_bone / skeleton_move / skeleton_bind / skeleton_remove / skeleton_delete / skeleton_pose`、`skeleton_draft_*`、`preview_set / preview_reset` | 同一 `SkeletonSpec`；FK/IK 求值不写历史，骨架编辑写历史并重建；编辑工具草稿与 `skeleton_draft_*` 共用 `SkeletonDraftEdits` 意图处理和应用会话 |
| 动作时间线 | 动画面板、动作编辑器 | `motion_list / motion_get / motion_sample / motion_put / motion_delete / motion_seed_builtin / motion_set_key / motion_delete_key / motion_remove_curve`、`view_sample_motion` | 同一 `MotionClip`；片段编辑写历史，动态采样另行验收 |
| 检查 / 历史 | 画布预览、撤销树 | `workspace_inspect / view_* / history_*` | `view_render_model / view_render_layer / view_render_context / view_render_poses / view_check_coverage / view_compare_history / view_sample_motion` 可固定镜头批量采样；UI 适合交互查看 |
| 保存 / 导出 | 工程保存、模型导出、PSD 导出 | `project_open`、`project_save`、`project_save_as`、`project_export_model`、`project_export_psd` | 使用项目编码、管线导出和 PSD 写入器 |

## 逐项清单

| # | 能力与核心算法 / 状态 | 人类 UI 路径 | MCP 路径 | 验收重点 |
| ---: | --- | --- | --- | --- |
| 1 | PSD 导入 / 图片创建：`WorkspaceSourceImporter` | 打开 PSD、分析 | `project_import_psd / project_create_artwork` | 共用应用导入器与源图接口，进程任务；从绝对路径创建/切换，默认拒绝未保存修改，新工程清除旧覆盖和辅助数据 |
| 1a | CMO3 导入：`WorkspaceCmo3Importer` / `Cmo3ModelImport` | CMO3 新建或替换 | `project_import_cmo3` | 新建使用新工程 ID；替换按 ID 更新、保留未出现对象并一次历史提交；保存重开、导出读回与渲染一致 |
| 2 | 自动语义识别：`Analyzer` / `RigBuilder` | 导入后自动分析 | `project_import_psd` 或 `project_create_artwork` 后生成 | 手动覆盖不应丢失自动推断之外的图层 |
| 3 | 图层类型：`LayerClassificationOverride.type` | Layers 表格“类型” | `layer_classify.type` | preset / toggle / switch 触发模型重建 |
| 4 | 语义部件与侧别：`tag/side` | Layers 表格“部件 / 侧别” | `layer_classify.role/side` | 省略字段保持当前值 |
| 5 | 差分关联：`parameter/switchId` | Layers 表格“参数关联 / 关联 ID” | `layer_classify.parameter/switch_id` | 这是 Issue #13 的直接验收项；参数定义与图层分类是两条不同路径 |
| 6 | 源图拆分：`WorkspacePartitionCommands` / `WorkspacePartitionEdits` | 网格连通块拆分对话框、导入后拆分建议 | `source_get_components / source_split_components / source_split_polygon` | GUI 连通块与 MCP 共用候选；两个修改为任务和批量成员；提前固定 ID、原层软删除、保存重开及导出读回一致；多边形当前无 GUI 菜单 |
| 6a | 深度拆分：`WorkspacePartitionCommands` / `WorkspaceDepthSplitEdits` | 所选网格上下文菜单、多选及深度拆分对话框 | `source_split_depth` | 同一候选、后台任务及原子批量；复制所选网格及运动、方向 Glue；固定前后顺序，新前层可独立绘画；GUI 保留起始状态并一次 USER 提交，普通和导入 CMO3 共用候选 |
| 7 | 图片追加 / 素材图层 / 软删除 / 恢复 | 层级图片导入 / 删除 / 恢复全部 | `layer_import_images / layer_set_bounds / layer_cancel_import / layer_add_from_asset / layer_set_placement / layer_finalize_placement / layer_soft_delete / layer_restore` | 文件图片追加与 GUI 共用独立应用命令及后台任务，整批一个历史节点；素材图层添加/配准定位/确认及删除/恢复支持原子批量。普通/导入模型图片及素材追加、自建父级下的配准与父级运动、保存重开和导出读回已验证；GUI 连续放置/取消已共用应用会话；复杂迁移仍待完成 |
| 8 | 源图绘画：`RasterPaintEngine` / `WorkspaceRasterCommands` / `WorkspaceRasterEdits` | 画笔、铅笔、橡皮、油漆桶、形状、清空 | `source_paint_brush / source_paint_pencil / source_paint_eraser / source_paint_bucket / source_paint_shape / source_paint_clear` 六种后台任务 | GUI 冻结像素与 MCP 手势共用应用命令和候选；默认保留网格和绑定，首次可见绘制创建网格，任务终态返回句柄；显式重建迁移拓扑，清空保留最后一层及绑定；准备可取消，CAS 后迟到取消仍保持成功 |
| 9 | 全局与单层网格：`MeshSettings` / `WorkspaceGenerationCommands` | 项目设置、图层菜单网格设置 | `settings_update`、`layer_mesh_update` | 直接操作及生成字段草稿共用纯候选；单层预览不推进状态，确认/重置进入正式应用命令；`workspace_inspect scope=layers` 可读有效值 |
| 10 | 图集与高清化：`PipelineConfig` / `TextureUpscale` | 项目设置与导出选项 | `settings_update textureUpscale` 等字段 | 2/4 倍输出需要本机高清化模型配置 |
| 10a | 纹理集图块：`WorkspaceTextureEdits` / `WorkspaceAtlasPlacements` | 纹理集页面拖动、角点缩放、旋转手柄、密度滑块，调整会话的“应用” | `atlas_check_placement`、`atlas_set_tile`（`pin.rotation`）、`layer_set_pixel_density`，组合时用 `workspace_apply_edits` | 视图的碰撞判断、`atlas_check_placement` 与提交（`requireKept`）是同一规则：按网格单元格、外扩间距、旋转外接框不出页；超出或相交时 GUI 标红不应用，MCP 返回 `tile_collides`，均不修改。会话“应用”即一个批量（先密度后位置），只检查最终布局，一个历史节点 |
| 10b | 纹理集排布与预算：`AtlasLayout.arrange` / `AtlasBudget` | 排布按钮与选项、自动排布开关、预算菜单 | `atlas_pack`、`atlas_set_budget`、`atlas_get`、`atlas_render_page` | 重新排布与改预算按自身规则重新布局，不受挤离检查；GUI 在调整会话未应用时禁用这些命令 |
| 11 | 参数定义 CRUD：有序 `RigAuthoringJournal` | 参数面板 | `parameter_create / parameter_update / parameter_delete` | 定义、文件夹及关键点可原子提交；删除在此前关键形之后按最后默认值最近的关键点折叠轴 |
| 12 | 参数文件夹 / XY 关联：持久结构编辑 | 参数树操作 | `rig_edit_structure` 中 param_group / link | 结构关系可保存、可历史恢复 |
| 13 | 当前预览值 / 锁定：`WorkspacePreviewCommands` / `WorkspacePreviewPort` | 参数滑杆、锁定、重置及工作区复制 | `workspace_inspect scope=preview`、`preview_set/reset`、`snapshot_apply` | 查询、合并与保存使用已提交姿态/锁；逻辑无变化保留版本及历史但可校正界面；GUI 冻结当前工作区姿态，不覆盖其他工作区；姿态/自动打键及播放/跟踪已使用中立命令和会话；GUI 修改先本地生效、按队列提交，失败回到已提交姿态 |
| 14 | 关键形增删与复制：`RigAuthoringJournal` | 关键形面板、画布 | `keyform_apply (op: seed/copy/set/delete)` | 精确参数坐标，其他轴不应被意外覆盖 |
| 15 | 连续形变：Mesh / Warp 几何编辑 | 画布形变笔刷与变换 | `rig_deform` | 明确绑定轴；操作作用于局部形状，父级运动继承 |
| 16 | 拓扑：`CanvasEdits` | 画布网格工具 | `canvas_topology` | 持久化后重新打开仍有效 |
| 17 | Warp：`CanvasEdits` / 拟合创建 | 画布创建 Warp | `canvas_warp`、`rig_create_warp` | 父级和包含对象需符合现有 Rig 关系；GUI 放置草稿经 `WorkspaceCanvasInputDraft` 在开始时捕获，冲突保留可取消草稿 |
| 18 | Rotation：`CanvasEdits` | 画布创建 Rotation | `canvas_rotation` | 保存旋转中心、父级与初始姿态 |
| 19 | 对象层级：`RigStructureEdits` / `WorkspaceHierarchyEdits` | 层级树拖放 / 菜单 | `object_edit_appearance`、`rig_edit_structure` | 移动后继承运动发生变化；GUI 拖放写一条 bind（Mesh）或 move（变形器）journal 编辑，`space=local`，不重新拟合；旧 v1 parentOverrides 继续在构建时先应用 |
| 20 | 静态属性 / 遮罩：`RigStructureEdits` | 属性面板与层级菜单 | `rig_edit_structure static` | 与动画关键形通道分开验证 |
| 21 | Glue：`CanvasEdits` | 画布 Glue 工具 | `canvas_glue` | 两侧 Mesh 和权重需匹配 |
| 22 | 变形路径：路径编辑命令 | 画布路径工具 | `path_get / path_list / path_preview / path_put / path_delete / path_deform` | 点位为 Mesh 局部坐标，区分预览与烘焙 |
| 23 | 物理：预设、骨骼、摆动与自定义组，计算顺序、计算 FPS、导入与倍率调整（`PhysicsCatalog`、`PhysicsAuthoring`） | 物理面板 | `settings_update`、`physics_put / physics_delete / physics_simulate / physics_fit / physics_config / physics_import`、`physics_audition / physics_audition_step / physics_audition_get`、`workspace_inspect scope=physics` | 静态姿态无法证明摆锤动态正确；`simulate` 与面板共用 Cubism 求值；面板摆锤与 `physics_audition` 共用 `PhysicsAudition`，其 peaks 经 `physics_fit observed_peaks` 与面板 Fit 共用候选 |
| 24 | 动作：片段、轨道、关键帧、导出设置与运行时采样 | 动作编辑器 / 预览 | `motion_list / motion_get / motion_sample / motion_put / motion_delete / motion_seed_builtin / motion_set_key / motion_delete_key / motion_remove_curve`、`settings_update`、`view_sample_motion`、`project_export_model` | 片段保存重开与导出，动态采样分别验收 |
| 25 | 观察：模型渲染 / 覆盖检查 | 画布、预览、历史界面 | `view_render_model / view_render_layer / view_render_context / view_render_poses / view_check_coverage / view_compare_history / view_sample_motion` | 比较时保持同一画布矩形与参数姿态 |
| 26 | 历史：`WorkspaceHistoryTree` | 历史树撤回 / 切换 | `history_checkpoint / history_list / history_checkout` | 从旧节点继续编辑保留分支，GUI 连通块拆分记为 user |
| 27 | 工程保存：工程状态编码 | 保存工程 | `project_save / project_save_as / project_open` | 进程任务；另存为指定绝对路径，切换默认拒绝未保存修改，显式放弃需请求字段 |
| 28 | 输出：`pipeline.run` / `PsdWriter.write` | 模型导出、PSD 导出 | `project_export_model`、`project_export_psd` | 返回实际文件；导出不推进编辑历史 |
| 29 | 骨架与 FK/IK：`SkeletonSpec` / `SkeletonPoseTool` | 骨骼标签页、姿态工具 | `skeleton_get / skeleton_propose / skeleton_auto / skeleton_put / skeleton_bone / skeleton_move / skeleton_bind / skeleton_remove / skeleton_enable / skeleton_pose` | 骨骼编辑写历史并重建，姿态求值只返回参数；检查绑定、参数端点和导出 |
| 29a | 骨骼编辑草稿：`SkeletonDraftEdits` / `WorkspaceSkeletonDraftSessions` | 骨骼编辑工具的批量变换、复制/镜像、细分/消解、链生成和手动权重 | `skeleton_draft_open / skeleton_draft_list / skeleton_draft_get / skeleton_draft_edit / skeleton_draft_preview_transfer / skeleton_draft_commit / skeleton_draft_cancel` | 同一类型化意图与应用会话；打开以自身姿态 CAS 重置 rest pose，提交只在该谱系上 CAS；GUI 异步打开待手动桌面检查 |
| 30 | 局部画布显隐 / solo：`CanvasVisibilityProcessor` | 层级眼睛、solo、全部显示/隐藏/反转、变形器眼睛 | `canvas_visibility / canvas_visibility_get` | 每 workspace/canvas/mode 的呈现状态经辅助 CAS 提交，推进 state 但不写历史；不改其他画布、文档可见性或导出；原 v1 presentation 位置原样读取。深度拆分前层与图片导入的显示也经同一辅助 CAS；CMO3 替换在同一提交中结束已保存的 solo |

## 共用规则和测试

- PSD/图片/CMO3 导入共用独立应用导入器，GUI 分析不直接运行流水线；MCP 返回可查询/等待/取消的进程任务，切换默认拒绝未保存修改，新工程使用新 ID/加载代次。
- GUI 与 MCP 的保存、打开共用应用层生命周期接口及工程控制器；GUI 入口负责未保存确认，MCP 默认拒绝且不触发对话框。GUI 内部入口已移除对桌面后端的下转型，并保留起始状态和可信用户身份。
- GUI 字段完成通过应用层队列异步进入候选/重建/CAS；保存等待完成，重开和外部编辑仍使旧状态失效。
- GUI 参数修改（滑块、数值、锁、重置、IK 目标、吸附到关键帧）先写入作者姿态并登记为待提交值，再经 ViewModel 的 FIFO 姿态队列提交；每项携带手势开始时的 state，队列只沿自己已落地的提交前进。较早的提交落地时保留之后的待提交值，失败时撤销全部待提交值并回到 `WorkspacePreviewPort.authoredPose`。拖动滑块时每个采样立即写入作者姿态（同一待提交值），松手只提交一次、取消即撤回，并与提交相同地求解骨骼约束。滑条显示拖动值，否则显示求值帧与所开动作在播放头处的曲线值（`livePose`），与聚焦哪个画布无关；编辑画布与滑条读同一 `shownPose`；作者姿态提交后保留打开的动作及播放头；其他编辑、撤销/重做和保存经 `workspaceEditBusy` 等待姿态队列。绘画和深度拆分在自己的提交完成后恢复画笔；查询只读取已提交捕获。
- 设置、分类和网格配置经 `WorkspaceGenerationCommands` 使用与批量相同的候选；MCP 单项返回后台任务，准备/重建可取消，提交后立即保留精确结果。分类省略字段在核对状态后的捕获模型中合并。GUI 连续全局网格拖动、分类文字输入保留实时草稿，结束时提交一次；生成差异草稿由应用层转换为纯候选。单层网格预览确认恢复基线后正式提交，重置也使用同一入口。真实 GUI/MCP 保存重开与多姿态导出读回已有回归，复杂分类迁移仍待验收。
- GUI 连通块拆分检测与确认保留同一起始状态，辅助修改即使不改变历史节点也使旧对话框失效。多层确认顺序重建后只提交一个 USER 节点，桌面不再准备拆分候选。`source_get_components` 使用独立捕获；公开连通块/多边形拆分及批量共用纯候选，保留原像素、分类/父级/可见性/网格/绘制顺序覆盖、稳定源图与网格 ID 及无关编辑；普通目标的关键形、通道、混合形、路径、顶点组和模拟通过有序日志迁移；连通块保留 Glue，多边形逐连接 Glue 插值和导入模型分区共用同一候选。独立应用回归与真实 GUI/MCP 回归验证取消/冲突、后项失败无前缀、历史重放、保存重开、CMO3 多姿态读回和 PNG 一致，见 `WorkspacePartitionCommandsTest` / `WorkspacePartitionIntegrationTest`。
- GUI 深度拆分与 `source_split_depth` 共用独立候选，菜单、多选及对话框保留打开时的完整状态，一次 USER 提交后才进入前层绘画。辅助数据变更也使旧确认失效；专项验证 GUI/MCP 像素和运动一致、嘴部只新增所选网格、取消/冲突及后项失败无前缀、任务断线重试、历史重放、保存重开和 CMO3 多姿态/Glue 读回。架构规则见 `CLAUDE.md`，用例为 `WorkspaceDepthSplitCommandsTest` / `WorkspaceDepthSplitIntegrationTest`。
- 设置开关经 `WorkspaceSettingsIntent` 一次解析：meshOnly 未显式给出 generateDeformers 时联动为 `!meshOnly`（导入 CMO3 除外），动作子项未显式给出 exportMotions 时联动为子项是否全开；关闭来源时按 `Parameter.default` 逐工作区释放其驱动的作者姿态并跳过该工作区的锁，文档、模型与辅助数据在一个草稿上一次 CAS。文档只存原始设置，`WorkspaceSettingsPolicy` 在读取时套用生效规则。GUI 字段会话内的开关仍先更新本地草稿用于显示，同时按顺序记录；提交草稿时先经同一意图重放这些开关（关闭再开启也会释放姿态），与其余草稿差异一次 CAS。
- GUI 内部 DTO 与 journal 同样使用不透明状态；摆动会话和离线模拟烘焙保留开始时的状态。模拟编辑通过可信用户上下文提交，避免作者误记为 Agent。参数对话框失败不发布定义、文件夹或关键点前缀；通道捕获和重开渲染/CMO3 读回已有回归。
- 公开修改携带唯一 `request_id`，工作区修改还带当前 `project_id` 和不透明 `state`。历史节点不能作为状态令牌；若 UI 改动或工程重开导致过期，客户端应重读 `workspace_inspect` 并使用新请求 ID。预览参数不写模型历史。
- GUI 删除和恢复全部经源图接口进入 `WorkspaceLayerCommands`，与 `layer_soft_delete/layer_restore` 共用纯候选和状态检查。两项单项为后台任务，也可作为原子批量成员；提交前取消、冲突及投影拒绝不发布历史，提交后保留完整终态。新建网格、普通已编辑图层、深度前后层及导入 CMO3 先重放后过滤，删除期间网格设置变更仍保存隐藏网格和重绑的路径/权重；恢复保留原 ID、关键形、遮罩和 Glue。普通工程新候选固定身份及生成基线，旧历史节点保持不变。真实 GUI/MCP、全部删除、普通/导入模型的删除归档重开、恢复后导出读回及像素一致性已验证，复杂迁移仍见验收记录。
- GUI 绘画像素与 MCP 栅格手势通过独立 `WorkspaceRasterCommands` 和共享 `WorkspaceRasterEdits` 候选提交；六种公开单项使用进程任务，批量直接执行候选并报告成员进度。洪水填充、裁剪、图集及网格准备响应原协程取消，提交前取消不发布像素或历史；CAS 后立即保留完整结果，迟到取消、刷新失败、断线重试不丢失状态或新对象句柄。默认保留网格，显式重建使用 `RasterMeshJournal` 迁移关键形、混合形、路径、顶点组及 Glue，已有绑定不再阻止绘画。普通透明图层及嘴部首次可见绘画通过 `RasterMeshCreation` 保存创建结果；新网格设置追加拓扑替换并重绑关键形、路径及权重，保留旧创建日志。生成输入与显式重建/首次创建的嘴唇轮廓和颜色保存到文档，完全擦空保留图层与绑定；保存重开及导出读回保持一致。GUI 传入手势开始时的工程状态，提交失败不发布预览。普通已编辑源图的多边形/连通块分区已支持关键形、通道、混合形、路径、顶点权重及模拟绑定迁移，连通块也保留 Glue 的连接顺序；普通、分区、新建及导入 CMO3 网格在单层/全局网格更新和重置时共用实际拓扑替换，保留关键形、路径、权重、Glue 和已有模拟偏移，删除后恢复同样重放；多边形逐连接 Glue 插值和导入模型拆分已通过相关回归；复杂父级/完整分类迁移及导入无网格对象尚待完成。
- `LayerClassificationIntegrationTest` 覆盖 Issue #13 分类字段、绘画像素和历史回退、单层网格、预览锁定、模型与 PSD 导出、PSD 再导入、多边形拆分算法及可信 user 历史归属。
- `AuthoringParityTest` 检查 公开 MCP 注册和严格请求 schema的注册与分类字段合并。全量验证命令：`./gradlew test --offline`。

素材图层添加、配准定位和确认已进入 `WorkspaceAssetLayerCommands` 与共享文档候选，三项均为后台单项及原子批量成员。追加前冻结生成范围与源图/Drawable 身份，重复名称不改变已有绑定。自建父级及导入模型的素材网格与文件图片共用创建记录，中性坐标转换读取实际父级；重新配准保留 ID 和父级，替换原记录，后续绑定或遮罩依赖仍拒绝单层定位。GUI 文件图片追加与 `layer_import_images` 共用独立应用命令；普通模型、导入模型及自建父变形器的图片追加均保存可重放结果。连续拖动预览、确认及取消已使用独立应用会话与共享图片定位/取消命令，保存排除预览并等待确认。复杂源图/分类迁移仍待继续。
