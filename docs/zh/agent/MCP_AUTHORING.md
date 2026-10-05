# MCP 使用与接口

[文档目录](../../README.md) · [设计与验收](AGENT_DESIGN.md) · [UI / MCP 双向清单](UI_MCP_PARITY_ISSUE_13.md) · [能力实测](../STATUS.md)

公开定义位于应用层 [WorkspaceAuthoringOperations.kt](../../../src/main/kotlin/io/github/psd2live/application/WorkspaceAuthoringOperations.kt) 、[WorkspaceDocumentBatch.kt](../../../src/main/kotlin/io/github/psd2live/application/WorkspaceDocumentBatch.kt)、[WorkspaceAuxiliaryOperations.kt](../../../src/main/kotlin/io/github/psd2live/application/WorkspaceAuxiliaryOperations.kt) 和 [WorkspaceOperations.kt](../../../src/main/kotlin/io/github/psd2live/application/WorkspaceOperations.kt)，MCP 绑定见 [AgentOperationTools.kt](../../../src/main/kotlin/io/github/psd2live/agent/AgentOperationTools.kt)。工具使用 `domain_operation` 名称；旧的分支工具已删除，没有兼容别名。先调用 `workspace_list_operations` 分页发现能力，再用 `workspace_get_operation` 读取单项完整 schema。

## 接入

1. 启动桌面应用，载入或创建工作区。
2. 打开 **工具 → MCP → MCP 连接与安装…**，复制宿主对应的配置。
3. 优先使用 Streamable HTTP 和界面提供的 Bearer Token。不要改用已废弃的 `/sse` 端点。
4. 仅支持 Stdio 的宿主使用 Python 3 运行根目录 `mcp_proxy.py`；代理支持 `PSD2LIVE_MCP_ENDPOINT` 和 `PSD2LIVE_MCP_TOKEN`。
5. 列出工具后调用 `workspace_inspect`，读取实际对象 ID、参数、当前 `project_id` 与 `state`。

Token 允许编辑当前工作区，应保留在本机宿主配置中。工具不提供图像生成模型；新增图片可来自已有像素、绘画或宿主的图像生成器。

## 公开工具速查

| 工具 | 请求结构 | 用途 |
| --- | --- | --- |
| `workspace_list_operations` | 可选 `domain`、`kind`、`offset`、`limit` | 分页发现工具、业务类型和执行元数据 |
| `workspace_get_operation` | `id` | 读取单项 schema、字段说明与执行元数据 |
| `workspace_apply_edits` | `state`、`edits`（1–128 项），可选 `summary` | 后台有序编辑同一候选文档，全部成功后提交一个历史节点；返回任务句柄，成员支持情况见 `batchable` |
| `workspace_preview_edits` | `state`、`edits`（1–128 项），共用请求上下文 | 后台试运行几何作者编辑，返回候选 revision 与诊断；不发布文档、姿态、历史或资源 |
| `workspace_inspect` | `request` 内 `scope` / `target` | scope 为 `project/settings/preview/objects/layers/parameters/physics/swings/paths/simulations/vertex_groups`；图层摘要包含有效网格配置 |
| `layer_classify` | `request` 内 `state`、`layer_id` 及分类字段 | 后台更新既有源图层的类型、部件、侧别、参数关联和切换 ID；省略的字段保持捕获时的原值 |
| `layer_mesh_update` | `request` 内 `state`、`layer_id`、`changes` 或 `reset` | 后台逐图层覆盖或重置自适应网格参数；用 `workspace_inspect scope=layers` 读取当前值 |
| `source_sample_color` | `layer_id`、`x`、`y` | 读取源图层在画布整数坐标的 RGBA；不改变文档或历史 |
| `source_paint_brush / source_paint_pencil / source_paint_eraser / source_paint_bucket / source_paint_shape / source_paint_clear` | `request` | `brush/pencil/eraser/bucket/shape/clear`；画布像素坐标，一次手势一个历史节点 |
| `preview_set / preview_reset` | `request` 内 `state` | `set/reset`；修改当前预览参数值和锁定状态，不写关键形 |
| `snapshot_create / snapshot_update / snapshot_delete / snapshot_apply` | `state`，更新/删除/应用要求 `id`；创建/更新可给 `name`、`values` | 保存、改名/覆盖、删除及应用参数快照；应用保留锁、忽略已删除参数并按当前范围钳制；不写时间线关键帧 |
| `snapshot_get / snapshot_list` | 读取给 `id`；列表可给 `offset`、`limit` | 返回同一捕获的工程状态、快照 ID/编号；列表省略 values，详情返回 values |
| `history_annotation_put / history_annotation_delete / history_annotation_get` | `node_id`；写入还须 `title`、`note`、`hidden` | 管理历史节点的显示注释；辅助数据保存到工程，历史节点及 Rig revision 不变 |
| `settings_update` | `request` 内 `state`、`changes` | 后台修改自动 Rig、网格、贴图、高清化、物理预设、动作和导出配置；`rigTuning` 按字段合并 Rig 数值（头部转向、眼睛、眉毛、嘴巴、鼻子与耳朵、头发，以及身体的转身、上下、前后倾、大小变、身体 Z 与呼吸、立体与透视；单位与模型预设「Rig 数值」一致，超出范围或未知的字段会被拒绝）；`meshOnly` 变化而未显式给出 `generateDeformers` 时联动为 `!meshOnly`（导入 CMO3 除外），生成动作子项变化而未显式给出 `exportMotions` 时联动为子项是否全开，显式字段优先；关闭来源时把其驱动的作者姿态逐工作区释放到参数默认值（跳过锁定参数），与设置同次提交；文档保存原始设置；先用 `workspace_inspect scope=settings` 读取 |
| `project_import_psd` | `path`，可选 `discard_unsaved` | 从绝对 PSD 路径后台创建新工程；已加载时可切换，默认拒绝未保存修改 |
| `project_create_artwork` | `width`、`height`、`layers`，可选 `discard_unsaved` | 从本地 PNG/WebP/TIFF/BMP 按位置创建新工程，图层自底向顶；后台任务，切换须保存或显式放弃 |
| `project_import_cmo3` | `path`、`mode`（`new/replace`），可选 `discard_unsaved` | 后台导入绝对路径的 CMO3；新建默认拒绝未保存切换；替换按对象 ID 更新、保留未出现对象，并作为一次历史编辑提交 |
| `project_open` | `path`，可选 `discard_unsaved` | 后台打开绝对路径的工程归档；未保存默认拒绝，先保存或显式放弃；可从空工作区调用 |
| `project_save` / `project_save_as` | 保存无需额外字段；另存为要求 `path` | 后台保存工程；首次保存用另存为指定绝对路径，后续保存使用当前路径；无变更不追加历史节点 |
| `project_export_model` | `request_id`、`state`、`output_directory` | 后台导出模型文件族；返回任务句柄，目录须为绝对路径 |
| `project_export_psd` | `request_id`、`state`、`path` | 后台导出 PSD；支持 1/2/4 倍及生成层选项 |
| `job_get` / `job_wait` | `id`；等待可给 `timeout_ms`（0–30000） | 查询进度、终态及结果；取消等待不会取消任务 |
| `job_list` | 可选 `project_id`、`offset`、`limit` | 分页查询应用内任务 |
| `job_cancel` | `request_id`、`id` | 请求取消；已完成提交保留 completed 状态 |
| `rig_deform` | `request` 内 `state`、`changes` | 在明确参数键上编辑 Mesh / Warp 连续形状 |
| `keyform_apply` | `request` 内 `state`、`changes` | `op: seed/copy/set/delete`，编辑关键形集合、标量 / 颜色通道与旋转形状 |
| `rig_create_warp` | `name`、`targets`；可选 `id`、`rows/columns`、`fit_local` | 后台创建共用父 Warp 的 Mesh 的独立 Warp，也支持原子批量；终态返回 `target` |
| `object_edit_appearance` | `request` 内 `state`、`edits` | 名称、显隐、结构等有序编辑 |
| `rig_edit_structure` | `request` 内 `state`、`edits` | 静态对象属性、变形器删除与 Part 归属、参数文件夹和 XY 关联 |
| `canvas_warp / canvas_rotation / canvas_glue / canvas_topology` | `request` | `warp/rotation/glue/topology`。`glue` 必须同时给出两个不同的画元 `mesh_a` 与 `mesh_b` |
| `view_render_model / view_render_layer / view_render_context / view_render_poses / view_check_coverage / view_compare_history / view_sample_motion` | `request` | `model/layer/context/poses/coverage/compare/motion`；动作采样返回只读任务句柄，终态包含采样拼图与参数范围 |
| `parameter_create / parameter_update / parameter_delete` | `request` | `create/update/delete`；删除时按最后一次默认值选择最近的已写关键点切片并折叠轴 |
| `layer_import_images` | `request` | 从 PNG、无损 WebP、TIFF 或 BMP 文件一次导入多层源图，后台完成后返回图层与网格句柄 |
| `source_get_components / source_split_components / source_split_polygon / source_split_depth / asset_prepare_reference / asset_import_png / asset_register / asset_preview_composite / layer_add_from_asset / layer_set_placement / layer_finalize_placement / asset_inspect / asset_reprocess / layer_soft_delete / layer_restore` | `request` | 源图拆分与素材的参考、导入、配准、预览、添加、定位、确认、检查、重处理、软删除和恢复 |
| `swing_put / swing_delete` | `request` | `put/delete`，在 Warp 或 Mesh（自动包一层 Warp）上生成左右 / 上下摇摆及摆锤；`motions` 组合左右与上下，`parallel` 让多束头发平行摆动，`tilt` / `offset_along` / `offset_across` 旋转和平移摇摆矩形；`delete` 可 `bake` 为普通关键，见[摇摆生成](../guide/SWING.md) |
| `physics_put / physics_delete / physics_simulate / physics_fit / physics_config / physics_import` | `request` | `put/delete/simulate/fit/config/import`：按 ID 新建或局部修改任意物理组（含生成的预设、骨骼、摆动组）、删除自定义组或恢复生成值、后台只读阶跃采样，通过任务结果返回峰值与稳定时间、按标准晃动或 `observed_peaks` 实测峰值调整输出倍率、设置计算顺序与计算 FPS、导入 physics3.json，见[物理](../guide/PHYSICS.md) |
| `simulation_put / simulation_delete / simulation_simulate / simulation_bake / simulation_clear_bake` | `request` 内 `state`、`id` | `put/delete/bake/clear_bake` 返回进程任务句柄（终态含原编辑结果与烘焙诊断）；`simulate` 同样返回只读任务句柄，不改变工程。网格上的 2D 布料 / 头发模拟，只在编辑器内运行；`bake` 把它烘焙成 -30…30 的 `ParamSim<id>_<k>` 参数（`keys` 个关键点，`blend_shapes` 选择写法，默认自动）与拟合摆锤 `PhysicsSim_<id>`，点头、身体上下和前后倾另外烘焙成由平移输入的摆锤 `PhysicsSim_<id>_y` 驱动的 `ParamSim<id>_Y`，并返回在检验动作上的 R²、误差、参数用量、顶到极值的帧占比和急动度比；`exaggeration` 放大模态摆动且无需重新烘焙；`auto_bake` 开启（默认）时 `put` 在同一步内重新烘焙；新建未给 `inputs` 时写入默认输入，空数组即没有输入；`output_names` 重命名烘焙出的参数和摆锤；`input_ranges` 按输入设置训练范围，`vertical` 开关上下参数（`null` 为自动），`outputs` 按生成的模态参数 ID 改写出的 ID、范围（±1…±100）和增益（0…3），不需要重新烘焙；物理面板对模拟摆锤的覆盖在重新烘焙后三方合并，见[模拟与烘焙](../guide/SIMULATION.md) |
| `model_apply_preset` | `request` 内 `preset`、`state`、`layers`、`sway` | `front_hair/back_hair/clothing/auto_weights/classic_front_hair/classic_back_hair/remove_clothing`：后台生成权重组与预设物理体并烘焙，返回任务句柄，成功后只增加一个历史节点；头发预设移除该类头发的旧摆动参数、变形器与摆锤，`classic_*` 恢复（`sway:false` 则关闭传统摆动），`remove_clothing` 删除全部服装预设物理体；`clothing` 只模拟上衣、下装、领饰、袖子和腿部穿戴中宽松的部分，返回每张网格的部位（下装另含裙子 / 裤子判定与腰线、裆部、下摆）、宽松比例、悬垂起点与是否模拟，见[模拟与烘焙](../guide/SIMULATION.md#模型预设) |
| `vertex_group_update` | `request` 内 `state`、`target`、`name` | 按规则 `fill/outline/gradient/glue/region` 生成或 `delete` 仅本软件使用的顶点权重组（固定点、刚度等），不导出 |
| `skeleton_get / skeleton_propose / skeleton_auto / skeleton_put / skeleton_enable / skeleton_bone / skeleton_move / skeleton_bind / skeleton_remove / skeleton_pose` | `request` | `get/propose/auto/put/enable/bone/move/bind/remove/pose`：读取或推断骨架、提交完整骨架、编辑骨骼与绑定；`pose` 求 FK/IK 参数值，不写历史 |
| `motion_list / motion_get / motion_sample / motion_put / motion_delete / motion_seed_builtin / motion_set_key / motion_delete_key / motion_remove_curve` | `request` | `list/get/sample/put/delete/seed_builtin/set_key/delete_key/remove_curve`：读取插值姿态并持久化编辑动作片段、参数轨道和时间线关键帧 |
| `path_get / path_list / path_preview / path_put / path_delete / path_deform` | `request` | `get/list/preview/put/delete/deform` |
| `project_save / history_checkpoint / history_list / history_checkout` | `request` | 保存工程、创建检查点、读取历史或切换节点 |

表中列出业务字段；所有修改还须携带 `request_id`，工作区修改须携带 `project_id` 和 `state`。只读后台采样 `physics_simulate/simulation_simulate/view_sample_motion` 同样要求这三个字段，用于去重并固定采样版本。各项操作字段不同，调用前读取当前服务提供的 JSON Schema。所有公开工具统一使用 `{"request": {...}}` 包装。发布与校验保留同一份 `oneOf`、`const`、字段约束及说明，外层和业务对象都拒绝未知字段。结果统一为 `{"ok":true,"operation":"...","data":{...}}`；错误包含 `ok:false` 和 `error.code/message`，字段校验错误还带 `field`。PNG 以 MCP 图片内容返回。

全部 170 项公开操作（其中 58 项后台、79 项可批量）都必须声明并发布完整 `outputSchema`，能力详情中的 `output_schema` 与其一致；成功 data 和失败 error 严格互斥。注册表在执行及去重边界校验业务结果，MCP 校验完整返回包装，遗漏结果契约不能注册。后台操作必须另有终态 `job_result_schema`，非后台操作不允许该字段。能力详情通过本地 `$defs/$ref` 描述嵌套 schema，查询自己的 schema 也可校验；实际 HTTP 保留所有根约束。`output_contract` 表示服务实现的结果与声明不符，不能作为修改已回滚的证据。

图片追加使用 `layer_import_images`：必需 `state` 和 1–128 个绝对路径组成的 `paths`，可选 `parent_deformer_id` 指向已有父变形器；省略时绑定模型根。透明边缘裁剪后居中，仅在超过画布时缩小。单文件最多 64 MiB、16 百万像素，整批最多 32 百万像素。一次成功只追加一个历史节点，任意文件失败则整批不发布；它读取文件，不能作为原子文档批量成员。新增源图及网格句柄从任务终态 `affectedLayerIds/affectedObjectIds` 获取。原图像素写入工程，后续重开不依赖输入文件。

文件导入图层定位使用 `layer_set_bounds`：`layer_id/left/top/width/height` 为必需，`name` 可选；坐标是源画布像素，提交时四舍五入为整数，宽高必须为正且不超过 16MP。重复缩放从已保存在工程的导入像素计算，保留原网格 ID、父级及其他对象的运动；已绘画或有专属绑定、Glue/遮罩依赖时拒绝定位。`layer_cancel_import` 接收 1–128 个唯一 `layer_ids`，整批一次历史提交；普通新增图层移除，旧生成基线及最后源图保留像素并软删除，重复取消无变化。两项均返回后台任务，也可加入原子文档批量；任务终态返回 `affectedLayerIds/state/history_node_id`。它们针对文件导入图层，素材配准图层继续使用 `layer_set_placement`。


## 工程与导出任务

新增的交互业务入口同样由 `workspace_get_operation` 提供完整 schema：

| 工具 | 共用行为 |
| --- | --- |
| `preview_pose` | 作者姿态及选中动作的自动打键同次 CAS；临时播放帧不触发此编辑 |
| `preview_playback / preview_playback_get / preview_physics` | 进程拥有的播放、单次动作、动画、鼠标跟踪及物理求值；临时帧不写工程姿态或历史 |
| `canvas_deform_stroke` | brush/smooth/inflate、多网格、连通限制及 Glue 保持；采样为画布像素，edit 保留图像，deform 写精确关键形；整笔一次提交 |
| `canvas_glue_edit / vertex_group_paint` | Glue 刷接、方向权重、解绑、重连及模拟权重笔刷/渐变/反转；保留连续 Glue 顺序与动画通道 |
| `layer_draw_order` | 以 `target` 设置 0–1000 的显式绘制顺序；`order:null` 重置为原有生成或作者动画顺序 |
| `warp_get_controls` | 从一次已提交捕获读取 Bezier 分段数与编辑控制；`detail=summary` 不含控制点，`points` 按锚点、切线顺序分页（每页最多 256）；坐标为父级局部空间，`persisted=false` 表示控制由采样格点重建。CMO3 只保存采样几何与分段信息，不含这些编辑控制 |
| `warp_set_topology` | 后台重采样全部普通与混合关键形；返回父级局部空间的实际表面采样误差 |
| `warp_bezier_divisions / warp_bezier_anchor / warp_bezier_handle / warp_bezier_reset` | 后台保存分段数、锚点、切线或重置；控制坐标为父级局部空间，持久化完整编辑控制与采样格点 |
| `swing_preview / swing_preview_get / swing_preview_render / swing_preview_commit` | 私有摆动草稿、试听时钟与 PNG 观察；确认一次历史提交，取消不发布草稿 |
| `skeleton_draft_open / skeleton_draft_list / skeleton_draft_get / skeleton_draft_edit / skeleton_draft_preview_transfer / skeleton_draft_commit / skeleton_draft_cancel` | 与骨骼编辑工具共用的私有骨架草稿：打开时以自身姿态 CAS 回到静止姿态，返回的 `state` 为草稿谱系；`edit` 以 1–128 项类型化意图（批量变换、复制/镜像、细分/消解、尾/翼链、权重绘制/清理/清除/转移、`revert` 等）全有或全无地修改草稿；`commit` 只在该谱系上 CAS，之后的姿态/文档修改、重开工程或新草稿均冲突，未变骨架不产生历史节点 |
| `physics_audition / physics_audition_step / physics_audition_get` | 与物理面板摆锤共用的选中组试听会话，不改文档、历史或保存的姿态：`start` 以工作区作者姿态（可叠加 `values`）驱动 `group_id` 并结束该工作区之前的会话，`target` 拖动摆锤根部到 -1…1，`release/reset/reset_peaks/stop`；`step` 以 dt ≤ 0.1 秒推进 1–240 步，在副本上求解，被拒绝的请求不留前缀；`get` 不推进时钟。同一加载内的文档编辑会更新该组，重开工程或删除组后会话为 `stale`；返回的 `peaks` 可直接作为 `physics_fit` 的 `observed_peaks` |
| `canvas_visibility / canvas_visibility_get` | 按 `workspace_id/canvas_id/mode`（edit/preview）寻址的局部图层/变形器显隐与 solo，与层级树眼睛和 solo 共用处理器；`action` 为 `layers/deformers/all_layers/invert_layers/solo/unsolo`，修改推进 `state` 但不改文档、历史、其他画布或导出，无变化不推进；查询返回每个画布会话的显隐、solo 记录与 `hidden_layer_ids`，未编辑过的画布全部可见。导出可见性仍由 `object_edit_appearance` 修改 |
| `physics_preset_list / physics_preset_put / physics_preset_rename / physics_preset_delete / physics_apply_preset` | 稳定 ID 的全局输入/摆锤预设库，库修改使用 `library_state`，应用为可批量文档编辑 |
| `simulation_preview / simulation_preview_step / simulation_preview_get / simulation_preview_render` | 私有实时模拟场景；启动/重启/停止和显式 dt 步进为后台会话，读取/渲染不会推进时钟 |
| `paint_session_begin / paint_session_list / paint_session_control / paint_session_commit` | 私有多笔触草稿、撤销/重做/跳转/取色/PNG；确认前不修改工程像素，确认一次历史提交 |
| `motion_create / motion_duplicate / motion_rename / motion_properties / motion_pose / motion_move_keys / motion_delete_keys / motion_paste_keys / motion_replace_keys / motion_preset` | 动作生命周期、姿态打键、键移动/删除/粘贴/替换及生成预设；全部可参与原子批量 |

控制请求仍遵守修改去重及工作区令牌规则。会话可另外拥有自己的令牌；全局物理库使用独立库令牌。读取、渲染、播放帧和私有草稿不等同于文档提交；以 `kind/job_backed/batchable` 发现元数据区分执行方式。

GUI 和 MCP 的工程保存、打开调用同一应用层生命周期接口，由桌面适配器持有的工程控制器执行。GUI 入口可以先向用户确认未保存修改，再明确传入放弃标志；MCP 不调用确认对话框，默认拒绝未保存切换。GUI 调用保留开始时的工程和状态，并以可信用户身份提交。

GUI 字段完成在应用层队列中异步重建和提交，保存会先等待已排队的完成；被拒绝的草稿不能通过保存隐式覆盖工程。队列只承接自己的连续提交，外部修改或工程重开仍使旧状态失效。查询继续读取已提交捕获，不发布尚未完成的界面草稿。 `workspace_inspect` 及骨架/动作复合查询使用同一独立会话，返回的状态、模型、设置和历史属于同一次捕获；查询期间的提交或重开不混入这份结果。`scope=preview` 读取已提交姿态/锁；动画求值帧与尚未提交的 GUI 值不替代该姿态。列表发现返回 `items/total` 和适用的 `next`。inspect 结果带 `scope`，目标详情固定为 `object`，空分页仍能区分作用域。`scope=settings` 返回补齐默认项的已保存生成设置与文档网格覆盖；旧扩展字段继续保存在工程文档中。

`workspace_apply_edits`、`project_import_psd`、`project_create_artwork`、`project_import_cmo3`、`project_open`、`project_save`、`project_save_as`、`project_export_model` 与 `project_export_psd` 返回 `{"ok":true,"operation":"...","data":{"id":"job-...","status":"..."}}`。用 `job_wait` 或 `job_get` 读取 `data.result` 中的结果：工程操作返回 `project_id`、`state` 和 `history_node_id`，导出返回文件与警告。保存完成后的迟到取消保留 completed 及已写入结果；归档替换前的取消保留旧文件。任务归应用持有，断线重连后仍可查询；应用重启后不恢复。

上述任务的能力详情另含 `job_result_schema`，描述 completed 的业务结果；启动及查询的 outputSchema 同时描述任务状态。`queued/running/cancelling` 的 terminal 为 false，均没有 result/error；`cancelled` 没有 result/error，`failed` 要求结构化 error，`completed` 要求与 operation 对应的 result。任务列表返回 `items/total` 和适用的 next，省略成功 result 但保留失败 error。完成结果与持久完成标记均使用同一业务校验，提交后的取消仍保留实际成功。

`settings_update/layer_mesh_update/layer_classify` 同样返回进程任务句柄，要求 `request_id/project_id/state`；用 `job_get/job_wait` 的 `data.result` 取得新状态和历史节点。三项操作共用独立应用命令，准备与重建报告进度，提交前取消或并发冲突保留原文档与历史；CAS 后迟到取消、界面刷新失败及原请求重试保留完整成功终态。分类省略字段在起始状态检查后的捕获模型中合并，无变化不推进状态或追加历史。GUI 连续拖动和分类文字输入在会话结束时提交一次，单层网格临时预览不代替正式持久候选。

PSD 导入与图片创建共用独立应用层导入器。`project_import_psd` 要求绝对 `path`；`project_create_artwork` 给出画布 `width/height` 和 1–32 个 `layers`，每层要求绝对 `path` 与 `name`，可选整数画布位置 `x/y` 及枚举分类 `role/side`；坐标和枚举的发布 schema 与实际校验一致。两者从空工作区创建或切换已加载工程，默认拒绝未保存修改，先保存或明确给出 `discard_unsaved:true`。读取前捕获状态，重建后安装新工程 ID/加载代次，旧 Rig/journal、图层/网格/绘制顺序覆盖和辅助数据不带入新源图。任务成功结果还包含新源图层 `layers` ID。

CMO3 导入共用独立应用层导入器，GUI 入口确认后携带可信用户身份调用，MCP 不进入对话框。`mode=new` 使用新工程 ID 和加载代次；`mode=replace` 要求已加载工程，保留未出现的对象以及参数快照和历史注释，文档与姿态重置在同一次提交中发布。导入模型不套用 PSD 自动 Rig 或动作预设。任务进度依次覆盖文件读取、候选准备、重建及提交；提交前取消保留原工程，提交后取消保留完成结果。

同一工程内，同一 `request_id` 和相同操作、参数及作者重试返回原结果（导出为原任务句柄）；改动后必须使用新 ID。成功和失败结果均保留到应用退出。此去重在应用进程内共享，不依赖 MCP 连接。任务进度通过查询返回，不沿用已结束调用的 progress token。旧的 `export`、`export_psd` 名称已移除。

```json
{"request":{"request_id":"export-001","project_id":"project-id-from-inspect","state":"opaque-state-from-inspect","output_directory":"D:/exports/model"}}
```

所有修改操作都要求 `request_id`；工作区修改还要求 `project_id` 与 `state`。`workspace_inspect` 总是返回当前状态，包括未加载时的状态令牌。创建或导入空工作区时 `project_id` 为 `null`。`state` 是包含加载代次与持久版本的不透明令牌，重开同一历史节点后也会变化，不能填写历史 HEAD。文档写入返回自己的提交令牌及 `history_node_id`；无变化不建立节点。共享上下文由注册表发布并校验，提交端再次检查；请求等待断线不取消进程持有的执行。参数快照与历史注释已使用同一应用命令和持久版本，不追加 Rig 历史节点。GUI 和 MCP 的显式 pose 修改也推进状态，播放/物理求值帧及快照悬停不推进状态。GUI 改参数时先在本地显示，再按顺序提交；提交落地前 `scope=preview` 仍返回已提交的姿态。此时 MCP 先提交姿态或其他修改，GUI 排队中的修改会按冲突回滚到已提交姿态。剩余辅助状态迁移范围见 [重构验收进度](REFACTOR_PROGRESS.md)。

`physics_simulate/simulation_simulate` 启动前核对请求的工程与状态，并捕获一次独立查询会话。后续修改或重开工程不改变此次采样；completed 的 `result` 包含原采样诊断及捕获时的 `project_id/state/revision`。用 `job_get/job_wait` 查询，用 `job_cancel` 中断校准、静置及逐帧求解；取消等待或断线仍让任务继续，原请求重试取回同一任务。它们保持 `read_only:true`，不增加历史、不改变持久版本，也不能加入文档批量。`simulation_simulate` 的 `hold/release` 各为 0–20 秒。

```json
{"request":{"request_id":"sample-001","project_id":"project-id-from-inspect","state":"opaque-state-from-inspect","id":"simulation-id-from-inspect","hold":0.5,"release":1.5}}
```

## 错误与恢复

即时调用失败时工具返回 `ok:false` 和结构化 `error`。后台任务失败时，`job_get/job_wait` 查询本身仍返回 `ok:true`，须检查 `data.status`；`failed` 任务的 `data.error` 使用同一结构，并且没有成功 `result`。任务查询、列表、断线重连及原请求重试都保留原错误。修正问题后发起新尝试要使用新 `request_id`，复用旧 ID 会取得原结果。

| `error.code` | 恢复依据 |
| --- | --- |
| `invalid_request` | 读取当前 schema；`field` 定位字段 |
| `output_contract` | 服务实现返回了不符合声明的结果；`operation/field` 定位契约问题。先查询工程或任务的实际状态，不能据此假定修改已回滚或直接重做 |
| `invalid_argument` / `invalid_edit` | 修正参数或编辑；批量错误另带 `edit_index` 和 `edit_operation` |
| `state_conflict` | 重读工程；错误带 `expected_state` 和 `actual_state` |
| `project_conflict` | 确认目标工程；错误带可空的 `expected_project` 和 `actual_project` |
| `request_id_reused` | 同一 ID 被用于不同操作、参数或作者；使用新的请求 ID |
| `unsaved_changes` | 先保存，或明确传入支持的放弃标志 |
| `workspace_busy` | 等待正在进行的工作完成，再读取当前状态 |
| `io_error` / `permission_denied` | 根据 `message` 检查文件路径、访问权限或磁盘问题 |
| `invalid_state` / `operation_failed` | 根据 `message` 和当前工程状态诊断业务前提或执行问题 |

`cancelled` 是独立任务状态，不是错误码。成功提交后的迟到取消或刷新异常保留 `completed` 与已提交结果，不能据此声称修改被回滚。

`rig_create_warp` 在捕获的候选模型中解析 `mesh:<id>`，要求唯一目标和共同父 Warp。省略 `id` 时只分配一次；显式 `id` 可供同一批量后项引用，新 Warp 同时出现在批量 `changed`。`rows/columns` 默认 16，范围 1–32，实际格点对齐父格点，最终每轴不超过 64；以对象详情中的实际划分为准。`fit_local` 默认 true，以普通关键形和独立混合形叠加的保守包络裁剪，并同步重映射几何及混合形，保留 UV、标量通道、路径和顶点权重。包络超出父域时拒绝拟合，可先扩大父域或用 `fit_local:false` 保留完整父域。自由放置的画布 Warp 仍使用 `canvas_warp`。单项终态 `job_wait/job_get.result` 包含 `target/state/history_node_id`；提交前失败或取消不发布新 Warp，提交后刷新失败或迟到取消保留成功和句柄。

## 原子文档编辑

`workspace_apply_edits` 共用外层的 `request_id`、`project_id` 和 `state`。`edits` 中每项为 `{"operation":"...","request":{...}}`，内部 request 只填写该单项工具的业务字段，不重复上下文字段。成员使用单项工具发布的同一业务 schema；未知字段和不支持的成员在候选编辑之前被拒绝。

### 几何检查与试运行

`rig_deform`、`keyform_apply`、`rig_edit_structure`、`object_edit_appearance`、`canvas_warp/rotation/glue/topology`、`canvas_deform_stroke` 和 `path_deform` 的几何作者日志，在应用层完整重建后、正式 CAS 前接受几何检查；对应 GUI journal 与 MCP 使用同一规则。批量只检查最终候选，允许前项临时退化、后项修复。其他文档操作不因这项规则自动扩大检查范围。

`workspace_preview_edits` 使用与正式提交相同的有序候选准备、重建和检查，但不调用投影或 CAS，不改变 state、历史、未保存标志、姿态或资源。它是只读后台任务，仍要求 `request_id/project_id/state`，通过 `job_wait/job_get.result` 获取 `dry_run:true`、输入 `revision`、`candidate_revision`、`would_change`、`would_commit`、新建对象的 `changed` 句柄和 `diagnostics`。创建 Warp、Rotation 或 Glue 时须提供显式 `id`，之后在同一 state 上向 `workspace_apply_edits` 提交相同 edits；候选 revision 才可重复比较。试运行不预留对象 ID，也不授权绕过后续的状态冲突检查。几何以外的成员不接受试运行，支持范围以该操作的 schema 为准。

质量等级与提交栅栏统一定义在 [质量检验规范](../spec/QUALITY_INSPECTION.md)。检查覆盖受影响对象的父级局部几何，包含普通关键点、混合形关键点与权重限制点的组合。新增局部翻面与明显塌缩为 `info`，新增零面积或近零面积为 `warning`，均允许提交，无需确认。非有限坐标和非法拓扑为 `error`，由统一作者提交栅栏阻止提交，返回 `geometry_unsafe`。既有形态单独计数；整表面的可逆仿射镜像与压缩豁免形态信息。

`diagnostics.quality` 是统一报告，包含 `version/fence/decision/can_commit/complete/scope/findings`；每项 finding 明确 `code/severity/category/target/evidence`。`safe` 只作为 `can_commit` 的兼容别名，不表示美观合格。`violations` 只包含阻断错误，`warnings` 只包含警告，`information` 只包含信息，全部由同一规则表派生。正式提交的 `geometry_diagnostics` 保留与预演相同的报告；没有涉及几何对象时省略该字段。

每对象最多采样 16384 个关键点组合。超预算对象在原始数据有效性检查后跳过组合采样，返回覆盖警告 `GEOMETRY_SAMPLING_LIMIT`、`not_sampled` 与 `complete:false`，不阻断有效的作者提交。仍继续检查其他对象。面积不足参考形 1% 为塌缩信息；有向面积绝对值不足 `1e-12` 或比例不超过 `1e-6` 为退化警告，不重复报告极小负面积的翻面。

诊断只覆盖声明的采样点，不证明插值过程、父级组合变形、Glue、遮罩、像素覆盖、物理或视觉美观。试运行结果属于输入版本；后续修改或重开不会改变其依据。`would_commit` 表示候选有变化且栅栏放行，检查不完整时也可为 true，须同时读取 `quality.complete`。

GUI 参数定义、文件夹位置和参数关键点可组合为一次共享提交；画布 journal、内部 typed 编辑和字段完成队列也进入同一候选/重建/CAS 边界。GUI 摆动会话、字段编辑、模拟修改和离线烘焙保留开始时的状态，避免把旧结果提交到重开或已变更的工程；GUI 作者由可信适配器指定为 `user`。其余业务准备和状态所有权还在迁移。

参数定义现在按实际编辑顺序写入 journal，删除会读取此前关键形及最后一次默认值；旧工程静态参数覆盖保持兼容读取，不改写历史。透明度和颜色等纯通道修改也会持久化，重复捕获已存在且相同的关键点不追加历史。

当前 79 项支持批量（包含素材图层添加、配准定位、确认及图片定位/取消）：设置、图层分类/网格配置、源图多边形/连通块/深度拆分及软删除/恢复、参数定义、独立 Warp 创建、Rig 变形/关键形/结构/外观/顶点组、六种源图绘画、七种骨架修改、六种动作修改、四种画布编辑、路径 put/delete/deform、摇摆 put/delete、物理 put/delete/config/fit，以及 simulation put/delete/bake/clear_bake 和 model_apply_preset。设置、分类、网格配置、源图拆分、图片定位/取消、图层软删除/恢复、独立 Warp、绘画、物理、模拟与预设的单项调用使用后台任务；批量直接执行同一应用层候选，拆分、绘画、烘焙和拟合进度包含所在批量成员，准备期间可取消整批，不嵌套成员的单项任务；`classic_front_hair/classic_back_hair` 支持 `sway:false`，移除模拟后关闭该类传统摆动。发现工具返回 `batchable:true` 才表示支持；GUI 字段完成已接入异步候选队列，生成设置/分类/网格草稿转换为共享纯候选，其余业务准备和文档命令仍在迁移，见 [验收进度](REFACTOR_PROGRESS.md)。

素材图层添加支持导入模型及自建父变形器。应用候选将源画布几何转换到实际父级的中性坐标，并保存网格创建记录；重新配准使用原处理像素，原位替换创建几何而保留对象 ID 和父级。已有专属绑定或遮罩依赖仍拒绝单层定位。归一化分配前检查坐标及 16MP 像素预算，准备阶段响应取消；GUI 连续放置经独立应用会话，保存排除预览并等待正式确认。

每项在前一项的候选文档和重建模型上执行，因此可以先创建参数，再在同一批次绑定关键形或动作轨道；创建画布对象后也可在后续编辑中引用它。需要后续引用的对象应提供稳定 ID。省略可选 ID 时，结果 `changed` 包含生成对象的 `kind:id` 句柄；相同请求重试返回原句柄。

最终 CAS 成功才发布文档、模型及一个历史节点。成员编辑或重建失败、提交前取消及状态冲突均保留原工作区。无变化和回到原文档的批量不推进 state 或追加历史。成员执行错误返回 `invalid_edit`，附从 0 开始的 `edit_index` 和 `edit_operation`；schema 错误返回 `invalid_request` 与字段路径。会话修改、工程切换、资源 I/O、只读时间模拟（simulation_simulate）及嵌套批量不能作为成员。批量返回进程任务句柄，通过 `job_wait/job_get` 读取状态及 `data.result`；成功结果包含 `project_id/state/history_node_id/revision/applied/edit_count/changed`。逐项准备及烘焙时报告进度，提交前取消不发布候选；成功 CAS 后立即保留完整结果，晚到的取消或刷新失败仍返回 completed。业务失败在任务的 `error` 中保留上述分类和索引，schema 错误在创建任务前返回。断线或取消等待不停止任务，原请求 ID 重试取回同一任务；GUI 内部直接调用仍使用同一命令边界。

```json
{
  "request": {
    "request_id": "batch-001",
    "project_id": "project-id-from-inspect",
    "state": "opaque-state-from-inspect",
    "summary": "Create an axis and its motion key",
    "edits": [
      {"operation": "parameter_create", "request": {"parameter_id": "ParamAccessory", "name": "Accessory", "min": -1, "max": 1}},
      {"operation": "motion_put", "request": {"clip": {"id": "accessory", "name": "Accessory", "duration": 1}}},
      {"operation": "motion_set_key", "request": {"id": "accessory", "parameter": "ParamAccessory", "key": {"time": 0, "value": 1}}}
    ]
  }
}
```

## 参数快照与历史注释

`snapshot_create` 省略 `values` 时保存当前 authored pose；需要保存播放中的求值值时显式传入采样值。`snapshot_update` 可以只改名、只替换 values 或同时修改；省略字段保留原值。删除快照不改变其他快照的编号。GUI 快照栏和 MCP 共用中立编辑命令，数据从运行时捕获，保存/重开保留快照 ID、编号、参数值和历史注释。

`snapshot_apply` 保留已锁定参数的值，忽略快照中已删除的参数，并按当前参数范围钳制旧值。GUI 应用快照与 MCP 相同，不受时间线自动打关键帧开关影响；要记录时间线请另行调用动作编辑工具。无变化保留原 `state`；实际修改返回自己的 `state`、`project_id`、`history_node_id`。历史注释只改变节点的显示标题、说明与隐藏标记，节点内容和分支关系保持不变。

`preview_set` 的省略参数和锁保留运行时已提交姿态中的值；`snapshot_apply` 同样依据已提交锁，未命中的参数保持原值。动画求值帧和未提交 GUI 显示值不作为合并基线。`preview_reset` 恢复全部默认值并解除锁定。这三项共用独立应用命令及辅助 CAS，不追加 Rig 历史或自动关键帧；逻辑无变化不推进状态，但仍可恢复正确的界面姿态并停止播放。保存也逐工作区使用捕获的持久姿态和锁，复制工作区的姿态会立即登记；归档仍为 v1。

## 最小调用

以下 JSON 是对应工具的参数，不是 HTTP 或 JSON-RPC 外层。示例 ID、`state` 和坐标须替换为当前工程返回值。

`workspace_inspect`：

```json
{
  "request": {
    "scope": "project"
  }
}
```

图层分类先读 `workspace_inspect scope=layers` 返回的 `type/role/side/parameter/switch_id`，再按需更新。例如将已有素材改成切换差分：

```json
{
  "request": {
    "project_id": "project-id-from-inspect",
    "request_id": "unique-request-id",
    "state": "opaque-state-from-inspect",
    "layer_id": "actualLayerId",
    "type": "switch",
    "parameter": "expression",
    "switch_id": 1
  }
}
```

这三个字段对应 UI 图层表格；`parameter_update` 只修改 Cubism 参数定义，不改变源图层的分类或差分关联。

按需查询，再读单对象：

```json
{
  "request": {
    "scope": "objects",
    "query": "hair",
    "limit": 24
  }
}
```

```json
{
  "request": {
    "target": "mesh:actualMeshId"
  }
}
```

`view_render_model / view_render_layer / view_render_context / view_render_poses / view_check_coverage / view_compare_history / view_sample_motion` 的固定镜头姿态比较：

```json
{
  "request": {
    "viewport": {
      "mode": "canvas_rect",
      "left": 0,
      "top": 0,
      "width": 1000,
      "height": 1000
    },
    "poses": [
      {
        "ParamAngleX": -30
      },
      {
        "ParamAngleX": 0
      },
      {
        "ParamAngleX": 30
      }
    ],
    "columns": 3,
    "target_long_edge": 1024
  }
}
```

`rig_deform`：

```json
{
  "request": {
    "project_id": "project-id-from-inspect",
    "request_id": "unique-request-id",
    "state": "opaque-state-from-inspect",
    "changes": [
      {
        "target": "warp:actualWarpId",
        "key": {
          "ParamCustom": 1
        },
        "operations": [
          {
            "type": "translate",
            "delta": [
              0.02,
              0
            ]
          }
        ]
      }
    ]
  }
}
```

这里的 `key` 必须涵盖目标直接绑定的相关轴；不要把观察姿态里的全部父级参数照搬为对象绑定。完成后使用返回的新 `state`，再渲染实际模型检查。

## 状态与历史

- 推进模型历史的写调用需要最新 `state`，成功返回后续调用可用的状态。过期时重新检查并协调编辑，不盲目覆盖。
- 批内编辑先验证与重建，成功后提交；一次 `rig_deform` / `keyform_apply` 可包含 1–128 条更改，单条形变可含 1–16 个操作。
- 普通无变化写入不应制造历史节点；`checkpoint` 是显式留点的例外。恢复是写操作，会移动 HEAD；从旧节点继续编辑形成分支，原分支保留。
- `project_save_as` 用绝对路径指定首次保存或另存位置；`project_save` 保存到当前位置。`project_open` 打开工程归档，未保存时先等待保存任务成功，或明确给出 `discard_unsaved=true`。这些操作不会打开 GUI 对话框。`project_export_model` 写出交付文件，但不替代工程保存。暂存素材不等于已经加入模型，也不等于已保存到磁盘。
- 超时或断线后用 `workspace_inspect` 和 `history_list` 检查是否已提交，再决定下一步。
- 多工具跨调用事务、结构化 `history_diff` 和 `task_*` 执行控制未作为当前公开接口提供。

## 形状、路径与物理

### 骨骼与动作

先调用 `skeleton_propose` 检查按当前图层推断出的骨架；`skeleton_auto` 才将它写入工程。`skeleton_get` 返回完整 `spec`，包含骨骼坐标、层级、画元绑定、关节角度范围和关节带宽。`skeleton_put` 可用返回的 `spec` 整体替换；`bone` 合并单根骨骼的字段，`move` 同时移动相连关节，`bind` 把画元绑定到指定骨骼（省略 `bone_id` 则解绑），`remove` 删除非身体骨骼。画元 ID 从 `workspace_inspect scope=objects` 取得。写入使用最新 `state`，成功后重建并提交历史；无效骨架或不存在的画元会拒绝写入。

```json
{"request":{"mode":"propose"}}
```

```json
{
  "request": {
    "project_id": "project-id-from-inspect",
    "request_id": "unique-request-id",
    "state": "opaque-state-from-inspect",
    "bone_id": "actualBoneId",
    "end": "tail",
    "point": [
      420,
      610
    ]
  }
}
```

`skeleton_pose` 用画布像素坐标求骨骼朝向；`ik: true` 求末端及最多两级父骨骼的角度。返回的 `values` 是计算结果，可交给 `view_render_poses` 渲染检查（不移动用户的参数滑块），或写入 `motion_list / motion_get / motion_sample / motion_put / motion_delete / motion_seed_builtin / motion_set_key / motion_delete_key / motion_remove_curve` 的参数轨道；它不改骨架、关键形或历史。骨骼形状已烘焙为参数、变形器和网格关键形，形状修正仍用 `keyform_apply` / `rig_deform`，物理用 `physics_put / physics_delete / physics_simulate / physics_fit / physics_config / physics_import`。

```json
{
  "request": {
    "bone_id": "actualBoneId",
    "target": [
      560,
      440
    ],
    "ik": true
  }
}
```

`motion_list / motion_get` 读取持久的 `MotionClip`，`sample` 返回指定时刻按片段插值后的参数值。`seed_builtin` 将生成动作变为可编辑的同名覆盖片段；`put` 创建或整体替换片段，使用 `get` 返回的 JSON 可以往返编辑。片段可设置时长、循环、FPS、淡入淡出和参数曲线；键支持 `LINEAR`、`BEZIER`、`STEPPED`、`INVERSE_STEPPED` 及 `in` / `out` 控制柄。`set_key` 在指定时间写入或替换键，`delete_key` 和 `remove_curve` 删除键或整条轨道。参数必须存在，键值和时间必须落在参数与片段范围内。导出动作须启用 `settings_update exportMotions`；生成的基础动作（Idle、Blink、Nod、Shake）还须启用 `settings_update motionBasic`，骨骼预设还须启用 `settings_update motionSkeleton`。

```json
{
  "request": {
    "project_id": "project-id-from-inspect",
    "request_id": "unique-request-id",
    "state": "opaque-state-from-inspect",
    "clip": {
      "id": "wave_custom",
      "name": "WaveCustom",
      "duration": 2,
      "curves": [
        {
          "parameter": "ParamArmRA",
          "keys": [
            {
              "time": 0,
              "value": 0
            },
            {
              "time": 1,
              "value": 45
            },
            {
              "time": 2,
              "value": 0
            }
          ]
        }
      ]
    }
  }
}
```

### 通用形状与物理

`rig_deform` 操作包括 `translate`、`scale`、`rotate`、`arc`、`curve`、`landmarks`。坐标使用固定输入边界中的归一化约定，X 向右、Y 向下，角度以度表示。选区与衰减可限制作用范围；不接收任意网格逐点数组。

`keyform_apply op=seed` 在指定键采样已有形状；`copy` 复制明确来源键；`set` 写通道或旋转形状；`delete` 删除相应键数据。新建参数本身不会产生运动，需要形状与参数绑定。

`path_get / path_list / path_preview / path_put / path_delete / path_deform` 点使用 Mesh 局部坐标，支持只读查询、预览、绑定和烘焙关键形。路径是编辑辅助，兼容性见[变形路径](../guide/DEFORM_PATHS.md)。

物理组是 Cubism 摆锤：`inputs`（`parameter`、`weight` 0–100、`type` 为 `x` 位置X或 `angle` 倾斜重力、`reflect`）推动 1–16 节 `segments`（`length`、`mobility`、`delay`、`acceleration`），`outputs`（`parameter`、`vertex`、`scale`、`weight`、`reflect`）读取第 `vertex` 节末端相对上一节的角度，`scale` 是每弧度对应的参数值（Cubism 原生运行时不读取位移类型输出的倍率，这类输出恒为 0，因此只提供角度输出）。导出的 physics3.json 和 CMO3 声明工程的计算 FPS（默认 60）。`normalization.position/angle` 的 `min/default/max` 是输入范围映射的目标。

- `physics_put` 只修改给出的字段：已有 ID（含生成组）以当前组为基础，新 ID 以「头部与身体输入、一节长 10、无输出」为基础。列表字段整体替换；`length` 为整串总长并按比例缩放各节，`mobility/delay/acceleration` 与 `output_scale` 作用于全部节段 / 输出，`segment_count` 调整节数。只给 `enabled` 时只开关该组；生成组修改后替换生成版本，直到 `physics_delete`。旧版 `input_parameter` / `output_parameter` 仍按单输入单输出读取。
- 同一参数只能被一个生效组驱动；自定义组驱动生成组的输出时，生成组让位。`workspace_inspect scope=physics` 返回 `fps` 和按计算顺序排列的组，每组带 `origin`、`enabled`、`active`、`overridden`、`replaced_by` 与 `issue`。
- `physics_config`：`order` 列出要先计算的组 ID，其余组按原顺序排在后面；Cubism 按顺序计算，后面的组在同一步里读到前面组的输出。`fps` 是工程唯一的帧率（预览、参数刷新和物理共用），为 1–240 的整数，0 表示无限制（预览跟随显示器，导出不声明 `Fps`）。
- `physics_import`：`path` 为 physics3.json 的绝对路径。文件中的组成为自定义组（同 ID 替换已有组，生成组在 `physics_delete` 前保持被替换），按文件顺序排在现有组之后；驱动相同输出的其他自定义组被关闭；文件的 `Fps` 成为工程的计算 FPS。返回导入的 ID、被关闭的组和缺失参数。
- `physics_fit`：用面板响应曲线的标准晃动（向右牵动 1 秒后松开）运行该组，把每个输出的倍率调整到峰值恰好达到参数端点的 `target`%（默认 100）；不动的输出保持原倍率。给出 `observed_peaks`（键为输出序号 `"0"`、`"1"`…，值为 `physics_audition` 返回的到达比例，1 为参数端点）时改按这次实测响应拟合，与面板拖动摆锤后的「按最大值调整倍率」共用候选；序号非法、值不是非负数或没有任何输出移动时在提交前拒绝。

`physics_put/physics_delete/physics_config/physics_import/physics_fit` 返回进程任务句柄，均要求 `request_id/project_id/state`。使用 `job_wait/job_get` 读取提交后的状态和结果；导入结果还含 `imported`，以及适用的 `disabled/missing_parameters/fps`。读取、拟合、重建和提交报告进度；拟合静置及逐帧求解支持取消，提交前取消或状态冲突保留原工程，提交后迟到取消或界面刷新异常保留成功结果。断线不停止任务，原请求重试取回同一任务。拟合支持原子批量并读取前序候选；文件导入不支持批量。已达到目标倍率的再次拟合不创建历史节点，完全没有输出响应的组仍报错。
- 先为输出参数制作运动端点，再接物理。静态姿态拼图不包含时间推进；用 `physics_simulate` 以阶跃输入检查幅度、过冲与稳定时间，整体动作用 `view_sample_motion`（其原生采样环境需可用）。

## View 与空间映射

View 从模型数据渲染 PNG，不依赖桌面截图。`canvas_rect` 给出画布矩形，`focus_layers` 围绕对象取景；输出像素大小和模型画布尺寸是两个概念。

`view_render_layer/view_render_context/view_render_model/view_render_poses/view_check_coverage` 各读取一次捕获的工程版本。多姿态的每张图共用该模型和可见图层，后续编辑、重开或切换工程不会混入结果，也不会使已经捕获的查询失效。PNG、空间映射及 revision 保持一致，图片和空间引用保存在捕获时的工程中。

- `poses` 在同一版本、同一画布矩形内比较 1–9 个姿态，返回一张带标签拼图。总尺寸预算作用于整张图。
- 对比姿态在后台从捕获的已提交版本渲染，不读取、也不修改作者姿态与 GUI 参数滑块；不要用 `preview_set` 切换姿态来出对比图，它会改变用户的作者姿态并推进 `state`。
- 请求中未给出的参数按**参数默认值**渲染，而不是用户当前的作者姿态。要以当前姿态为基准，先用 `workspace_inspect scope=preview` 读取已提交的姿态，放入共享的 `parameters`，再在 `poses` 中只写差异。
- 省略 `include_layer_ids` 时使用当前活动画布的局部显隐：用户隐藏的图层、被隐藏变形器下的图层以及 solo 之外的图层都不会出现在图中。需要与用户画布无关的稳定结果时，显式传入图层 ID（可从 `workspace_inspect scope=layers` 取得）。
- `compare` 比较历史版本；`motion_list / motion_get / motion_sample / motion_put / motion_delete / motion_seed_builtin / motion_set_key / motion_delete_key / motion_remove_curve` 按时间采样；`coverage` 只测指定矩形和指定图层的 Alpha 覆盖。
- 使用返回的像素↔画布映射定位；多姿态整张拼图不能直接作为单张素材的空间参考。
- Alpha 覆盖、网格诊断和文件成功写出都不是美术质量分数，也不能证明未采样姿态正常。

`view_sample_motion` 返回只读任务句柄，也要求 `request_id/project_id/state`。`frames` 为 2–32 个严格帧对象（`time/parameters`），时间从 0 严格递增，最长 10 秒；参数省略时沿用此前值。`samples` 为动作时长内的 1–9 个时间点，`fps` 为 15–120（默认 60），结果时间按采样帧率向下量化。任务启动前捕获模型、可见图层和资源目标；后续修改、重开或 GUI 动画不会混入采样。原生 Cubism 求值、逐图渲染和拼图报告进度并支持取消，需已安装可选原生运行时。

用 `job_get/job_wait` 读取终态诊断与 PNG，重复读取返回同一图片；完成结果含 `project_id/state/revision`，已有拼图 `revisionId` 也指向同一捕获版本。原生采样使用临时模型，不更改 GUI 的实时动画和物理状态；取消排队任务不会执行，帧循环取消后释放模型与临时文件。该只读操作不支持文档批量。历史对比仍是即时查询，但也使用同一次捕获的历史树，工程重开不替换它的对比依据。

## 素材工作流

`asset_prepare_reference / asset_import_png / asset_register / asset_reprocess` 返回进程任务句柄，均要求 `request_id/project_id/state`。从 `job_wait/job_get` 的 `data.result` 读取业务结果及提交后的 `state/project_id/history_node_id`；参考图、配准预览及重处理图片可重复获取。断线或取消等待不停止任务，原请求重试取得同一任务。准备失败、提交前取消或状态冲突不发布素材；CAS 后的迟到取消保留 completed 和实际结果。

四项写入共用独立应用层素材会话：先准备私有候选，再在辅助 CAS 内发布不可变文件及素材清单。实际新增素材使旧 state 失效，但不改变 Rig revision 或追加历史；重复导入同一内容及映射属于无变化。检查和试拼使用捕获的文档与素材清单，不读取 GUI 草稿或后来配准。保存只复制捕获时属于工程的素材，归档仍为 v1；PNG 导入在分配像素内存前检查 16 兆像素上限。

对新增素材，通常使用 `reference → import → register → preview → add`，必要时 `place → finalize`。`project_create_artwork` 可从放置素材后台创建新工程，切换时默认拒绝未保存修改；`source_split_polygon` 按画布多边形拆成内部和余部，`source_split_components` 按当前网格的连通块分配现有像素；两者不会补画被遮挡内容。

`source_get_components` 是只读查询，传 `layer_id`，返回捕获时的 `project_id/state/revision`、`can_split/count` 与组件中心 `canvas_x/canvas_y`。组件按源图 Y、再 X 排序；使用该顺序给 `source_split_components` 的 `names` 命名，可选同长度的 `sides` 与唯一 `piece_ids`。名称去除首尾空格后须非空且唯一，数量必须匹配当前组件；省略侧别或使用 `none` 继承原层。组件检测使用查询捕获的当前网格；后续修改不改变旧查询结果，提交须携带该捕获状态。

`source_split_polygon/source_split_components` 要求 `request_id/project_id/state`，返回后台任务句柄；`job_get/job_wait` 的 `result` 保留新状态、历史节点与新源图 `layers` ID。多边形要求 3–32 个画布顶点和两个 `names`（内部、余部），两侧都须有可见像素；可选两个唯一 `piece_ids`。指定新 ID 后可在同一原子批量的后续成员中引用它们，批量 `changed` 同时包含新源图的 `layer:<id>` 句柄和生成对象句柄。准备/重建可取消，提交前冲突或失败保留原文档；提交后的迟到取消或刷新异常保留完整成功终态，原请求取回同一任务。

拆分保留原源图层并软删除，继承分类、父级、可见性、逐层网格及绘制顺序覆盖，保留原始生成输入和无关对象的编辑。普通目标已有关键形、通道、混合形、路径、顶点权重或模拟时，拆分保留绑定；连通块还保留 Glue 的顺序、强度和连接，模拟的目标、烘焙偏移与连接角色迁移到新分区。普通、分区、新建及导入 CMO3 网格在单层/全局网格更新及重置时通过有序替换保留绑定和已有模拟偏移，包含已删除网格的恢复；多边形实时 Glue 插值和导入模型拆分尚未完成，相关修改明确拒绝；已删除、遮罩或非普通混合源图也不能拆分。组件查询使用实际纹理地址，支持源图补边、旋转和缩放，已编辑的普通目标可以返回 `can_split:true`。GUI 网格连通块拆分对话框调用同一应用命令，保留检测时状态，多层决策一次用户历史提交；多边形操作目前是 MCP 入口，不能据此声称 GUI 有套索拆分菜单。

`source_split_depth` 对应 GUI 的深度拆分菜单、多选和对话框。传当前网格的原始 `source_id` 和非空、唯一的 `middle_ids`（不带 `mesh:` 前缀，且不包含 source）；可选 `names` 为后层、前层的两个非空唯一名称。它复制一份可独立绘画的前层，原后层保留运动，中间网格位于两层之间。前层复制当前日志位置的父级、几何、通道关键形、混合形、路径和顶点权重，方向 Glue 让前层跟随后层；两层采用固定绘制顺序，替换原绘制顺序动画。嘴部及派生嘴唇只复制所选网格，不生成额外嘴唇；前层擦空也保留拓扑。导入 CMO3 暂不支持。

单项同样要求 `request_id/project_id/state`，任务终态的 `layers` 只包含新前层 ID。可指定唯一的 `front_layer_id/front_mesh_id/glue_id`，供同一原子批量后续成员绘画或编辑新对象；批量 `changed` 返回 `layer:<id>/mesh:<id>/glue:<id>`。GUI 保留菜单或对话框打开时的状态，辅助数据修改也会使旧确认失效。准备可取消，提交后保留精确终态；历史重放、保存重开和 CMO3 读回保留运动及 Glue 连接权重。它复制现有内容，隐藏部分仍需绘画补充。

已有 PSD 使用 `project_import_psd` 从本地绝对路径打开。`source_paint_brush / source_paint_pencil / source_paint_eraser / source_paint_bucket / source_paint_shape / source_paint_clear` 六种绘画返回进程任务句柄，通过 `job_wait/job_get` 获取 `data.result` 中的提交状态、历史节点及生成句柄。能力详情标注 `job_backed:true`、`batchable:true` 并提供 `job_result_schema`。画笔、橡皮、油漆桶和形状使用 UI 的栅格算法；坐标为画布像素。默认 `rebuild_mesh:false` 保留已有网格及全部绑定，超出网格的新增像素只写入源图；需要网格覆盖新区域时传 `rebuild_mesh:true`，迁移关键形、混合形、路径、顶点组及 Glue，一次提交一个历史节点。已有关键形、Warp 或 Glue 不再阻止绘画。`clear` 或擦除全部像素保留图层与绑定，最后一层也可清空再重画；删除图层使用 `layer_soft_delete`。深度拆分前层始终保留拓扑。普通已编辑图层、深度前后层及导入 CMO3 的删除/恢复先重放编辑再过滤活动对象，保留恢复所需的 ID、关键形和绑定；删除期间修改网格设置仍保存隐藏网格的重绑结果。旧工程首次实际删除或恢复会在新候选固定身份和生成基线，避免现有编辑因图层恢复而改指其他网格；旧历史节点不被改写。`layer_mesh_update` 修改单层网格参数，重置后继承全局值。

普通透明图层首次绘制可见像素时自动创建网格，无须先传 `rebuild_mesh:true`。嘴部首次绘画也创建派生嘴唇及其生成关键形。单项和批量任务完成结果的 `changed` 包含新对象的 `mesh:<id>` 等句柄，可继续编辑；相同请求重试取回同一任务，不重复创建或追加历史。完全擦空即使传入重建标志也保留网格及绑定。

生成嘴唇跟随显式重建或首次创建时保存的轮廓和颜色，后续保留网格绘画不重新生成派生贴图。GUI 冻结像素与 MCP 手势通过独立 `WorkspaceRasterCommands` 共用文档候选、重建及状态检查；创建数据进入有序日志，保存重开和导出重新解析当前贴图。准备过程中报告进度并检查取消，提交前取消保留原像素和历史；成功提交后的迟到取消或刷新失败仍保留完整完成结果。断线或取消等待不会停止任务。新网格的单层设置更新同样写入文档，保留 ID、重绑路径及权重。复杂父级/完整分类迁移及导入无网格对象仍待完成，见 [验收进度](REFACTOR_PROGRESS.md)。

`layer_soft_delete` 与 `layer_restore` 都返回进程任务，支持原子批量；通过 `job_wait/job_get` 的 `result` 读取实际状态、历史节点和受影响图层。`layer_restore` 可传 1–256 个唯一 `layer_ids`，省略时恢复全部；恢复活动图层、重复删除及无删除内容时恢复属于无变化，不追加历史。GUI 删除和“恢复全部”使用同一应用命令。删除保留像素和编辑日志；含新建网格记录的工程先完整重放，再过滤已删除图层及派生嘴唇，当前模型清理其遮罩引用、Glue、路径和权重，恢复重新取得保存的绑定。删除期间的全局网格设置变更也持久化隐藏网格的新几何及重绑数据。提交前取消或冲突保留原状态，CAS 后的迟到取消或刷新失败保留成功；旧历史节点不改写。

全局网格单位使用 `settings_update` 的 `meshUnits`：`DOCUMENT` 以文档长边 2048 像素为参照，同一图像放大后使用相同密度；`PIXELS` 使用源像素长度。两种模式的单层参数仍表示所选单位内的长度。旧 v1 设置存在 `meshSpacing` 而没有单位时沿用 `PIXELS`，避免重开改变旧网格；新设置、生成基线与导出保存实际单位。GUI 单位开关和公开修改使用同一候选重生与拓扑迁移。

`import` 接受已有本地 PNG 绝对路径或图像字节；不要让模型逐字生成 Base64。省略 `solid_background` 保留原生 Alpha；需要去底时显式给出真实纯色。棋盘格截图不是透明素材。

`register` 可使用画框、对应锚点或绝对变换；锚点的目标位置是画布坐标，生成图坐标是原始完整 PNG 像素。镜像、非等比拉伸须明确声明。分辨率提高不应自动扩大模型中的占地。

先试拼并检查父级、绘制顺序、遮罩和运动余量，再正式加入或软删除替代层。已经有独立绑定、关键形或完成定位的素材可能拒绝整体重定位，以实际错误和 Schema 为准。

## 相关实现

- [公开 Schema 与适配](../../../src/main/kotlin/io/github/psd2live/application/WorkspaceAuthoringOperations.kt)
- [素材工具](../../../src/main/kotlin/io/github/psd2live/application/WorkspaceAssetCommands.kt)
- [路径工具](../../../src/main/kotlin/io/github/psd2live/application/WorkspacePathEdits.kt)
- [工程存储](../../../src/main/kotlin/io/github/psd2live/project/WorkspaceStore.kt)

本页记录接口，不据此升级[能力实测](../STATUS.md)的评价。完整效果仍需实际模型、宿主与任务样本验证。
