# MCP 使用与接口

[文档目录](../../README.md) · [设计与验收](AGENT_DESIGN.md) · [UI / MCP 双向清单](UI_MCP_PARITY_ISSUE_13.md) · [能力实测](../STATUS.md)

本页以 [AgentAuthoringTools.kt](../../../src/main/kotlin/io/github/psd2live/agent/AgentAuthoringTools.kt) 的公开注册为准。当前是 **26 个工具**。`project_get_state`、`rig_transform`、`asset_import_png` 等名称属于内部适配层，不是公开工具，不能直接调用。

## 接入

1. 启动桌面应用，载入或创建工作区。
2. 打开 **工具 → MCP → MCP 连接与安装…**，复制宿主对应的配置。
3. 优先使用 Streamable HTTP 和界面提供的 Bearer Token。不要改用已废弃的 `/sse` 端点。
4. 仅支持 Stdio 的宿主使用 Python 3 运行根目录 `mcp_proxy.py`；代理支持 `PSD2LIVE_MCP_ENDPOINT` 和 `PSD2LIVE_MCP_TOKEN`。
5. 列出工具后调用 `inspect`，读取实际对象 ID、参数和当前 `state`。

Token 允许编辑当前工作区，应保留在本机宿主配置中。工具不提供图像生成模型；新增图片可来自已有像素、绘画或宿主的图像生成器。

## 公开工具速查

| 工具 | 请求结构 | 用途 / 分支 |
| --- | --- | --- |
| `inspect` | 顶层 `scope` / `target` | `project`、`settings`、`preview`、`objects`、`layers`、`parameters`、`physics`、`swings`、`paths`、`simulations`、`vertex_groups`；图层摘要包含有效网格配置 |
| `layer` | 顶层 `state`、`layer_id` 及分类字段 | 更新既有源图层的类型、部件、侧别、参数关联和切换 ID；省略的字段保持原值 |
| `layer_mesh` | 顶层 `state`、`layer_id`、`changes` 或 `reset` | 逐图层覆盖或重置自适应网格参数；用 `inspect.layers` 读取当前值 |
| `paint` | `request.mode` | `brush/eraser/bucket/shape/clear`；画布像素坐标，一次手势一个历史节点 |
| `preview` | 顶层 `state`、`mode` | `set/reset`；修改当前预览参数值和锁定状态，不写关键形 |
| `settings` | 顶层 `state`、`changes` | 修改自动 Rig、网格、贴图、高清化、物理预设、动作和导出配置；先用 `inspect.settings` 读取 |
| `export` | 顶层 `state`、`output_directory` | 导出当前工程的模型文件族，返回文件与警告；目录需为绝对路径 |
| `export_psd` | 顶层 `state`、`path` | 使用 UI 的 PSD 写入器，支持 1/2/4 倍及生成层选项 |
| `deform` | 顶层 `state`、`changes`、可选 `dry_run` | 在明确参数键上编辑 Mesh / Warp 连续形状；`dry_run:true` 走同一候选编译与几何 gate，但不写入 |
| `form` | 顶层 `state`、`changes`、可选 `dry_run` | `op: seed/copy/set/delete`，编辑关键形集合、标量 / 颜色通道与旋转形状；可零写入 dry-run |
| `rig` | 顶层 `state`、`name`、`targets` | 为共用父 Warp 的 Mesh 创建独立 Warp |
| `appearance` | 顶层 `state`、`edits` | 名称、显隐、结构等有序编辑 |
| `structure` | 顶层 `state`、`edits`、可选 `dry_run` | 静态对象属性、变形器删除与 Part 归属、参数文件夹和 XY 关联；结构候选按最终完整 batch 验证 |
| `canvas` | `request.mode`、可选 `request.dry_run` | `warp/rotation/glue/topology`。`glue` 必须同时给出两个不同的画元 `mesh_a` 与 `mesh_b`；dry-run 生成的 ID 可在 commit 时重用 |
| `view` | `request.mode` | `model/layer/context/poses/coverage/compare/motion` |
| `parameter` | `request.mode` | `create/update/delete`；删除时在旧默认值处折叠关键形轴 |
| `asset` | `request.mode` | `psd/create/split/reference/import/register/preview/add/place/finalize/inspect/reprocess/remove`；`psd` 从本地绝对路径导入空工作区 |
| `swing` | `request.mode` | `put/delete`，在 Warp 或 Mesh（自动包一层 Warp）上生成左右 / 上下摇摆及摆锤；`motions` 组合左右与上下，`parallel` 让多束头发平行摆动，`tilt` / `offset_along` / `offset_across` 旋转和平移摇摆矩形；`delete` 可 `bake` 为普通关键，见[摇摆生成](../guide/SWING.md) |
| `physics` | `request.mode` | `put/delete/simulate/fit/config/import`：按 ID 新建或局部修改任意物理组（含生成的预设、骨骼、摆动组）、删除自定义组或恢复生成值、按阶跃输入模拟并返回峰值与稳定时间、按标准晃动调整输出倍率、设置计算顺序与计算 FPS、导入 physics3.json，见[物理](../guide/PHYSICS.md) |
| `simulation` | 顶层 `mode`、`state`、`id` | `put/delete/simulate/bake/clear_bake`：网格上的 2D 布料 / 头发模拟，只在编辑器内运行；`bake` 把它烘焙成 -30…30 的 `ParamSim<id>_<k>` 参数（`keys` 个关键点，`blend_shapes` 选择写法，默认自动）与拟合摆锤 `PhysicsSim_<id>`，点头、身体上下和前后倾另外烘焙成由平移输入的摆锤 `PhysicsSim_<id>_y` 驱动的 `ParamSim<id>_Y`，并返回在检验动作上的 R²、误差、参数用量、顶到极值的帧占比和急动度比；`exaggeration` 放大模态摆动且无需重新烘焙；`auto_bake` 开启（默认）时 `put` 在同一步内重新烘焙；新建未给 `inputs` 时写入默认输入，空数组即没有输入；`output_names` 重命名烘焙出的参数和摆锤，见[模拟与烘焙](../guide/SIMULATION.md) |
| `model_preset` | 顶层 `preset`、`state`、`layers` | `front_hair/back_hair/clothing/auto_weights/classic_front_hair/classic_back_hair/remove_clothing`：一步撤销内生成权重组与预设物理体并烘焙；头发预设移除该类头发的旧摆动参数、变形器与摆锤，`classic_*` 恢复，`remove_clothing` 删除全部服装预设物理体；`clothing` 只模拟上衣、下装、领饰、袖子和腿部穿戴中宽松的部分，返回每张网格的部位（下装另含裙子 / 裤子判定与腰线、裆部、下摆）、宽松比例、悬垂起点与是否模拟，见[模拟与烘焙](../guide/SIMULATION.md#模型预设) |
| `vertex_group` | 顶层 `state`、`target`、`name` | 按规则 `fill/outline/gradient/glue/region` 生成或 `delete` 仅本软件使用的顶点权重组（固定点、刚度等），不导出 |
| `skeleton` | `request.mode` | `get/propose/auto/put/enable/bone/move/bind/remove/pose`：读取或推断骨架、提交完整骨架、编辑骨骼与绑定；`pose` 求 FK/IK 参数值，不写历史 |
| `motion` | `request.mode` | `list/get/sample/put/delete/seed_builtin/set_key/delete_key/remove_curve`：读取插值姿态并持久化编辑动作片段、参数轨道和时间线关键帧 |
| `path` | `request.mode` | `get/list/preview/put/delete/deform` |
| `revision` | `request.mode` | `save/checkpoint/list/restore` |

分支字段并不相同，调用前读取当前服务提供的 JSON Schema。顶层工具和 `request` 包装工具不能混用参数层级。

## 最小调用

以下 JSON 是对应工具的参数，不是 HTTP 或 JSON-RPC 外层。示例 ID、`state` 和坐标须替换为当前工程返回值。

`inspect`：

```json
{"scope":"project"}
```

图层分类先读 `inspect.layers` 返回的 `type/role/side/parameter/switch_id`，再按需更新。例如将已有素材改成切换差分：

```json
{"state":"current-history-head","layer_id":"actualLayerId","type":"switch","parameter":"expression","switch_id":1}
```

这三个字段对应 UI 图层表格；`parameter.update` 只修改 Cubism 参数定义，不改变源图层的分类或差分关联。

按需查询，再读单对象：

```json
{"scope":"objects","query":"hair","limit":24}
```

```json
{"target":"mesh:actualMeshId"}
```

`view` 的固定镜头姿态比较：

```json
{
  "request": {
    "mode": "poses",
    "viewport": {"mode":"canvas_rect","left":0,"top":0,"width":1000,"height":1000},
    "poses": [{"ParamAngleX":-30},{"ParamAngleX":0},{"ParamAngleX":30}],
    "columns": 3,
    "target_long_edge": 1024
  }
}
```

`deform`：

```json
{
  "state": "current-history-head",
  "changes": [{
    "target": "warp:actualWarpId",
    "key": {"ParamCustom":1},
    "operations": [{"type":"translate","delta":[0.02,0]}]
  }]
}
```

这里的 `key` 必须涵盖目标直接绑定的相关轴；不要把观察姿态里的全部父级参数照搬为对象绑定。完成后使用返回的新 `state`，再渲染实际模型检查。

先 dry-run 同一批形变：

```json
{
  "state": "current-history-head",
  "dry_run": true,
  "changes": [{
    "target": "warp:actualWarpId",
    "key": {"ParamCustom":1},
    "operations": [{"type":"translate","delta":[0.02,0]}]
  }]
}
```

返回的 `acceptedByGeometryGate`、`wouldChange`、`wouldCommit`、`currentState/currentRevision`、
`compiledCommandCount`、`affectedTargets/affectedCoordinates` 与 `candidateDiagnostics` 都来自和
真正 commit 相同的 ordered compile / validation 路径。dry-run 永远不推进 state、revision、
history 或 journal。拒绝 reason code 固定为 `GEOMETRY_NON_FINITE`、
`GEOMETRY_INVALID_TOPOLOGY`、`GEOMETRY_NEW_FLIP`、`GEOMETRY_NEW_DEGENERATE`、
`GEOMETRY_NEW_COLLAPSE`。既有 flip/degenerate/collapse 会报告为 `preexisting*Count`，但不会单独
阻挡没有新增结构失败的编辑。

这是 parent-local 的结构几何检查，不等同于视觉安全证明。它不覆盖父级组合变形、mask 与实际绘制
覆盖、物理效果或美学质量；这些仍须由相应的预览、运行时检查或人工审阅验证。

## 状态与历史

- 推进模型历史的写调用需要最新 `state`，成功返回后续调用可用的状态。过期时重新检查并协调编辑，不盲目覆盖。
- 批内编辑先顺序编译完整候选，再对受影响目标的 native key coordinates 跑结构几何 gate；只有全批通过才提交一个历史节点。一次 `deform` / `form` 可包含 1–128 条更改，单条形变可含 1–16 个操作。
- 普通无变化写入不应制造历史节点；`checkpoint` 是显式留点的例外。恢复是写操作，会移动 HEAD；从旧节点继续编辑形成分支，原分支保留。
- `revision.save` 保存到 UI 已选择的工程位置。`export` 写出交付文件，但不替代工程保存。暂存素材不等于已经加入模型，也不等于已保存到磁盘。
- 超时或断线后用 `inspect` 和 `revision.list` 检查是否已提交，再决定下一步。
- 多工具跨调用事务、结构化 `history_diff` 和 `task_*` 执行控制未作为当前公开接口提供。

## 形状、路径与物理

### 骨骼与动作

先调用 `skeleton.propose` 检查按当前图层推断出的骨架；`skeleton.auto` 才将它写入工程。`skeleton.get` 返回完整 `spec`，包含骨骼坐标、层级、画元绑定、关节角度范围和关节带宽。`skeleton.put` 可用返回的 `spec` 整体替换；`bone` 合并单根骨骼的字段，`move` 同时移动相连关节，`bind` 把画元绑定到指定骨骼（省略 `bone_id` 则解绑），`remove` 删除非身体骨骼。画元 ID 从 `inspect.objects` 取得。写入使用最新 `state`，成功后重建并提交历史；无效骨架或不存在的画元会拒绝写入。

```json
{"request":{"mode":"propose"}}
```

```json
{"request":{"mode":"move","state":"current-history-head","bone_id":"actualBoneId","end":"tail","point":[420,610]}}
```

`skeleton.pose` 用画布像素坐标求骨骼朝向；`ik: true` 求末端及最多两级父骨骼的角度。返回的 `values` 是计算结果，可交给 `preview.set` 检查姿态，或写入 `motion` 的参数轨道；它不改骨架、关键形或历史。骨骼形状已烘焙为参数、变形器和网格关键形，形状修正仍用 `form` / `deform`，物理用 `physics`。

```json
{"request":{"mode":"pose","bone_id":"actualBoneId","target":[560,440],"ik":true}}
```

`motion.list/get` 读取持久的 `MotionClip`，`sample` 返回指定时刻按片段插值后的参数值。`seed_builtin` 将生成动作变为可编辑的同名覆盖片段；`put` 创建或整体替换片段，使用 `get` 返回的 JSON 可以往返编辑。片段可设置时长、循环、FPS、淡入淡出和参数曲线；键支持 `LINEAR`、`BEZIER`、`STEPPED`、`INVERSE_STEPPED` 及 `in` / `out` 控制柄。`set_key` 在指定时间写入或替换键，`delete_key` 和 `remove_curve` 删除键或整条轨道。参数必须存在，键值和时间必须落在参数与片段范围内。导出动作须启用 `settings.exportMotions`；生成的骨骼预设还须启用 `settings.motionSkeleton`。

```json
{"request":{"mode":"put","state":"current-history-head","clip":{"id":"wave_custom","name":"WaveCustom","duration":2,"curves":[{"parameter":"ParamArmRA","keys":[{"time":0,"value":0},{"time":1,"value":45},{"time":2,"value":0}]}]}}}
```

### 通用形状与物理

`deform` 操作包括 `translate`、`scale`、`rotate`、`arc`、`curve`、`landmarks`。坐标使用固定输入边界中的归一化约定，X 向右、Y 向下，角度以度表示。选区与衰减可限制作用范围；不接收任意网格逐点数组。

`form.seed` 在指定键采样已有形状；`copy` 复制明确来源键；`set` 写通道或旋转形状；`delete` 删除相应键数据。新建参数本身不会产生运动，需要形状与参数绑定。

`path` 点使用 Mesh 局部坐标，支持只读查询、预览、绑定和烘焙关键形。路径是编辑辅助，兼容性见[变形路径](../guide/DEFORM_PATHS.md)。

物理组是 Cubism 摆锤：`inputs`（`parameter`、`weight` 0–100、`type` 为 `x` 位置X或 `angle` 倾斜重力、`reflect`）推动 1–16 节 `segments`（`length`、`mobility`、`delay`、`acceleration`），`outputs`（`parameter`、`vertex`、`scale`、`weight`、`reflect`）读取第 `vertex` 节末端相对上一节的角度，`scale` 是每弧度对应的参数值（Cubism 原生运行时不读取位移类型输出的倍率，这类输出恒为 0，因此只提供角度输出）。导出的 physics3.json 和 CMO3 声明工程的计算 FPS（默认 60）。`normalization.position/angle` 的 `min/default/max` 是输入范围映射的目标。

- `physics.put` 只修改给出的字段：已有 ID（含生成组）以当前组为基础，新 ID 以「头部与身体输入、一节长 10、无输出」为基础。列表字段整体替换；`length` 为整串总长并按比例缩放各节，`mobility/delay/acceleration` 与 `output_scale` 作用于全部节段 / 输出，`segment_count` 调整节数。只给 `enabled` 时只开关该组；生成组修改后替换生成版本，直到 `physics.delete`。旧版 `input_parameter` / `output_parameter` 仍按单输入单输出读取。
- 同一参数只能被一个生效组驱动；自定义组驱动生成组的输出时，生成组让位。`inspect scope=physics` 返回 `fps` 和按计算顺序排列的组，每组带 `origin`、`enabled`、`active`、`overridden`、`replaced_by` 与 `issue`。
- `physics.config`：`order` 列出要先计算的组 ID，其余组按原顺序排在后面；Cubism 按顺序计算，后面的组在同一步里读到前面组的输出。`fps` 是工程唯一的帧率（预览、参数刷新和物理共用），为 1–240 的整数，0 表示无限制（预览跟随显示器，导出不声明 `Fps`）。
- `physics.import`：`path` 为 physics3.json 的绝对路径。文件中的组成为自定义组（同 ID 替换已有组，生成组在 `physics.delete` 前保持被替换），按文件顺序排在现有组之后；驱动相同输出的其他自定义组被关闭；文件的 `Fps` 成为工程的计算 FPS。返回导入的 ID、被关闭的组和缺失参数。
- `physics.fit`：用面板响应曲线的标准晃动（向右牵动 1 秒后松开）运行该组，把每个输出的倍率调整到峰值恰好达到参数端点的 `target`%（默认 100）；不动的输出保持原倍率。
- 先为输出参数制作运动端点，再接物理。静态姿态拼图不包含时间推进；用 `physics.simulate` 以阶跃输入检查幅度、过冲与稳定时间，整体动作用 `view.motion`（其原生采样环境需可用）。

## View 与空间映射

View 从模型数据渲染 PNG，不依赖桌面截图。`canvas_rect` 给出画布矩形，`focus_layers` 围绕对象取景；输出像素大小和模型画布尺寸是两个概念。

- `poses` 在同一版本、同一画布矩形内比较 1–9 个姿态，返回一张带标签拼图。总尺寸预算作用于整张图。
- `compare` 比较历史版本；`motion` 按时间采样；`coverage` 只测指定矩形和指定图层的 Alpha 覆盖。
- 使用返回的像素↔画布映射定位；多姿态整张拼图不能直接作为单张素材的空间参考。
- Alpha 覆盖、网格诊断和文件成功写出都不是美术质量分数，也不能证明未采样姿态正常。

## 素材工作流

对新增素材，通常使用 `reference → import → register → preview → add`，必要时 `place → finalize`。`create` 可从放置素材建立空工作区；`split` 按画布多边形拆成内部和余部，不会补画被遮挡内容，也不应被描述成自动拆发建模。

已有 PSD 使用 `asset.psd` 从本地绝对路径打开。`paint` 的画笔、橡皮、油漆桶和形状使用 UI 的栅格算法；坐标为画布像素。`clear` 或擦除全部像素会软删除该层以便历史恢复；最后一个有效图层不可清空。绘画可能改变源图边界和网格拓扑，因此已有目标网格关键形、Warp 或 Glue 时会拒绝；应在这些绑定前完成源图像素修改。`layer_mesh` 修改单层网格参数，重置后继承全局值。

`import` 接受已有本地 PNG 绝对路径或图像字节；不要让模型逐字生成 Base64。省略 `solid_background` 保留原生 Alpha；需要去底时显式给出真实纯色。棋盘格截图不是透明素材。

`register` 可使用画框、对应锚点或绝对变换；锚点的目标位置是画布坐标，生成图坐标是原始完整 PNG 像素。镜像、非等比拉伸须明确声明。分辨率提高不应自动扩大模型中的占地。

先试拼并检查父级、绘制顺序、遮罩和运动余量，再正式加入或软删除替代层。已经有独立绑定、关键形或完成定位的素材可能拒绝整体重定位，以实际错误和 Schema 为准。

## 相关实现

- [公开 Schema 与适配](../../../src/main/kotlin/io/github/psd2live/agent/AgentAuthoringTools.kt)
- [素材工具](../../../src/main/kotlin/io/github/psd2live/agent/AgentAssetTools.kt)
- [路径工具](../../../src/main/kotlin/io/github/psd2live/agent/AgentPathTools.kt)
- [工程存储](../../../src/main/kotlin/io/github/psd2live/agent/AgentWorkspaceStore.kt)

本页记录接口，不据此升级[能力实测](../STATUS.md)的评价。完整效果仍需实际模型、宿主与任务样本验证。
