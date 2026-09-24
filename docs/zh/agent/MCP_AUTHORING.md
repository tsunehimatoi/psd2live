# MCP 使用与接口

[文档目录](../../README.md) · [设计与验收](AGENT_DESIGN.md) · [能力实测](../../../STATUS.md)

本页以 [AgentAuthoringTools.kt](../../../src/main/kotlin/io/github/psd2live/agent/AgentAuthoringTools.kt) 的公开注册为准。当前是 **20 个工具**。旧文档中的 `project_get_state`、`rig_transform`、`asset_import_png` 等是内部适配名称，不能直接当作当前公开工具调用。

## 接入

1. 启动桌面应用，载入或创建工作区。
2. 打开 **工具 → MCP → MCP 连接与安装…**，复制宿主对应的配置。
3. 优先使用 Streamable HTTP 和界面提供的 Bearer Token。不要将端点换成旧 `/sse` 地址。
4. 仅支持 Stdio 的宿主使用 Python 3 运行根目录 `mcp_proxy.py`；代理支持 `PSD2LIVE_MCP_ENDPOINT` 和 `PSD2LIVE_MCP_TOKEN`。
5. 列出工具后调用 `inspect`，读取实际对象 ID、参数和当前 `state`。

Token 允许编辑当前工作区，应保留在本机宿主配置中。工具不提供图像生成模型；新增图片可来自已有像素、绘画或宿主的图像生成器。

## 公开工具速查

| 工具 | 请求结构 | 用途 / 分支 |
| --- | --- | --- |
| `inspect` | 顶层 `scope` / `target` | `project`、`settings`、`preview`、`objects`、`layers`、`parameters`、`physics`、`paths`；图层摘要包含有效网格配置 |
| `layer` | 顶层 `state`、`layer_id` 及分类字段 | 更新既有源图层的类型、部件、侧别、参数关联和切换 ID；省略的字段保持原值 |
| `layer_mesh` | 顶层 `state`、`layer_id`、`changes` 或 `reset` | 逐图层覆盖或重置自适应网格参数；用 `inspect.layers` 读取当前值 |
| `paint` | `request.mode` | `brush/eraser/bucket/shape/clear`；画布像素坐标，一次手势一个历史节点 |
| `preview` | 顶层 `state`、`mode` | `set/reset`；修改当前预览参数值和锁定状态，不写关键形 |
| `settings` | 顶层 `state`、`changes` | 修改自动 Rig、网格、贴图、高清化、物理预设、动作和导出配置；先用 `inspect.settings` 读取 |
| `export` | 顶层 `state`、`output_directory` | 导出当前工程的模型文件族，返回文件与警告；目录需为绝对路径 |
| `export_psd` | 顶层 `state`、`path` | 使用 UI 的 PSD 写入器，支持 1/2/4 倍及生成层选项 |
| `deform` | 顶层 `state`、`changes` | 在明确参数键上编辑 Mesh / Warp 连续形状 |
| `form` | 顶层 `state`、`changes` | `op: seed/copy/set/delete`，编辑关键形集合、标量 / 颜色通道与旋转形状 |
| `rig` | 顶层 `state`、`name`、`targets` | 为共用父 Warp 的 Mesh 创建独立 Warp |
| `appearance` | 顶层 `state`、`edits` | 名称、显隐、结构等有序编辑 |
| `structure` | 顶层 `state`、`edits` | 静态对象属性、变形器删除与 Part 归属、参数文件夹和 XY 关联 |
| `canvas` | `request.mode` | `warp/rotation/glue/topology`，调用画布同源且可重放的几何命令 |
| `view` | `request.mode` | `model/layer/context/poses/coverage/compare/motion` |
| `parameter` | `request.mode` | `create/update/delete`；删除时在旧默认值处折叠关键形轴 |
| `asset` | `request.mode` | `psd/create/split/reference/import/register/preview/add/place/finalize/inspect/reprocess/remove`；`psd` 从本地绝对路径导入空工作区 |
| `physics` | `request.mode` | `put/delete`，创建、替换或删除自定义简化摆锤组 |
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

## 状态与历史

- 推进模型历史的写调用需要最新 `state`，成功返回后续调用可用的状态。过期时重新检查并协调编辑，不盲目覆盖。
- 批内编辑先验证与重建，成功后提交；一次 `deform` / `form` 可包含 1–128 条更改，单条形变可含 1–16 个操作。
- 普通无变化写入不应制造历史节点；`checkpoint` 是显式留点的例外。恢复是写操作，会移动 HEAD；从旧节点继续编辑形成分支，原分支保留。
- `revision.save` 保存到 UI 已选择的工程位置。`export` 写出交付文件，但不替代工程保存。暂存素材不等于已经加入模型，也不等于已保存到磁盘。
- 超时或断线后用 `inspect` 和 `revision.list` 检查是否已提交，再决定下一步。
- 多工具跨调用事务、结构化 `history_diff` 和 `task_*` 执行控制未作为当前公开接口提供。

## 形状、路径与物理

`deform` 操作包括 `translate`、`scale`、`rotate`、`arc`、`curve`、`landmarks`。坐标使用固定输入边界中的归一化约定，X 向右、Y 向下，角度以度表示。选区与衰减可限制作用范围；不接收任意网格逐点数组。

`form.seed` 在指定键采样已有形状；`copy` 复制明确来源键；`set` 写通道或旋转形状；`delete` 删除相应键数据。新建参数本身不会产生运动，需要形状与参数绑定。

`path` 点使用 Mesh 局部坐标，支持只读查询、预览、绑定和烘焙关键形。路径是编辑辅助，兼容性见[变形路径](../guide/DEFORM_PATHS.md)。

`physics.put` 是简化摆锤配置。先为输出参数制作运动端点，再设置输入、输出及摆锤参数。静态姿态拼图不包含时间推进；动态观察用 `view.motion`，其原生采样环境需可用。

## View 与空间映射

View 从模型数据渲染 PNG，不依赖桌面截图。`canvas_rect` 给出画布矩形，`focus_layers` 围绕对象取景；输出像素大小和模型画布尺寸是两个概念。

- `poses` 在同一版本、同一画布矩形内比较 1–9 个姿态，返回一张带标签拼图。总尺寸预算作用于整张图。
- `compare` 比较历史版本；`motion` 按时间采样；`coverage` 只测指定矩形和指定图层的 Alpha 覆盖。
- 使用返回的像素↔画布映射定位；多姿态整张拼图不能直接作为单张素材的空间参考。
- Alpha 覆盖、网格诊断和文件成功写出都不是美术质量分数，也不能证明未采样姿态正常。

## 素材工作流

对新增素材，通常使用 `reference → import → register → preview → add`，必要时 `place → finalize`。`create` 可从放置素材建立空工作区；`split` 按画布多边形拆成内部和余部，不会补画被遮挡内容，也不应被描述成自动拆发建模。

已有 PSD 使用 `asset.psd` 从本地绝对路径打开。`paint` 的画笔、橡皮、油漆桶和形状使用 UI 的栅格算法；坐标为画布像素。绘画可能改变源图边界和网格拓扑，因此已有目标网格关键形、Warp 或 Glue 时会拒绝；应在这些绑定前完成源图像素修改。`layer_mesh` 修改单层网格参数，重置后继承全局值。

`import` 接受已有本地 PNG 绝对路径或图像字节；不要让模型逐字生成 Base64。省略 `solid_background` 保留原生 Alpha；需要去底时显式给出真实纯色。棋盘格截图不是透明素材。

`register` 可使用画框、对应锚点或绝对变换；锚点的目标位置是画布坐标，生成图坐标是原始完整 PNG 像素。镜像、非等比拉伸须明确声明。分辨率提高不应自动扩大模型中的占地。

先试拼并检查父级、绘制顺序、遮罩和运动余量，再正式加入或软删除替代层。已经有独立绑定、关键形或完成定位的素材可能拒绝整体重定位，以实际错误和 Schema 为准。

## 相关实现

- [公开 Schema 与适配](../../../src/main/kotlin/io/github/psd2live/agent/AgentAuthoringTools.kt)
- [素材工具](../../../src/main/kotlin/io/github/psd2live/agent/AgentAssetTools.kt)
- [路径工具](../../../src/main/kotlin/io/github/psd2live/agent/AgentPathTools.kt)
- [工程存储](../../../src/main/kotlin/io/github/psd2live/agent/AgentWorkspaceStore.kt)

本页记录接口，不据此升级[能力实测](../../../STATUS.md)的评价。完整效果仍需实际模型、宿主与任务样本验证。
