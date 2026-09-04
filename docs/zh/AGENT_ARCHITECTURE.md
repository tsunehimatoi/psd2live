# psd2live Agent：产品与技术设计

状态：Phase 0 / 0.5 已落地；Phase 1 已完成参数 CRUD、对象级 K 帧编辑、持久化分支历史与桌面历史/日志界面，完整 Domain Command/Transaction 仍在扩展中

目标：把规范 PSD 转换为可继续精修、可重新导入、可复用的 60%～80% Live2D 工程，同时尽量消除建模师的重复劳动。

## 1. 产品边界

一句话定义：**LLM 负责理解、规划、选择工具和验收；确定性算法与可配置模板负责改图、Mesh、变形器、参数和物理；建模师保留审美判断与最终精修。**

本产品不是让模型模拟鼠标点击，也不是重新实现一个只能生成最终画面的 Live2D 编辑器。产物必须保留可编辑的 ArtMesh、Deformer、Parameter、Physics、源图和来源关系。只要自动化结果造成以下任一情况，就应判定任务失败并回滚：

- 原始素材无法恢复或重新导入；
- 图层、网格、变形器和参数之间失去稳定对应关系；
- 静态画面看似正确，但关键参数、组合角、物理或极限姿势不可修；
- 为修正自动结果所需工作量预计高于从原始素材重做；
- 依赖无法解释、无法重放的像素或几何修改；
- 违反 Cubism 的结构、性能或导出约束。

## 2. 从高级建模流程得出的硬约束

这部分不是风格偏好，而是产品验收条件。

### 2.1 素材分离决定上限

Live2D 官方流程把素材处理放在第一步，并明确要求头发按前发、侧发、后发拆分；需要单独摆动的发束应独立成层；被原画遮住的区域必须补全。导入用 PSD 则要求一个部件最终对应一层，并保留清晰分组。参见 [Illustration Processing](https://docs.live2d.com/en/cubism-editor-tutorials/psd/) 和 [How to create PSDs to import](https://docs.live2d.com/en/cubism-editor-manual/reimport-psd/)。

因此：

- Agent 不得只按可见轮廓切三刀；必须生成具有合理重叠和遮挡补全的三个独立 RGBA 素材；
- 原 PSD 是不可变来源，所谓“删除原图层”只能是工作区软删除；
- 每个派生图层都要保存 `sourceAssetId + mask/vector path + generation settings + model/version + prompt hash`；
- 生成结果必须同时通过独立透明视图、整体上下文视图、边缘/接缝视图和运动视图。

### 2.2 PSD 导入和重新导入必须是一级能力

Cubism 会把 PSD 图层转成 ArtMesh，也支持追加或替换 PSD。官方文档同时指出，改名、复制名称或改变顺序可能导致重新映射错误；已经制作 keyform 后重新自动生成 Mesh 可能改变甚至重置形变。旧源图在 Cubism 项目内也可保留和切回。参见 [Import PSDs](https://docs.live2d.com/en/cubism-editor-manual/psd-import/)、[Re-import PSDs](https://docs.live2d.com/4.2/en/cubism-editor-manual/psd-re-import/) 和 [Automatic Mesh generator](https://docs.live2d.com/en/cubism-editor-manual/mesh-edit/)。

因此：

- 名称不能充当主键；所有对象使用永久 UUID，名称只是可本地化的显示字段；
- Import Manifest 要记录源 PSD 指纹、源层路径、像素边界、ArtMesh ID 和历史映射；
- Mesh 必须在添加 keyform 前冻结；之后默认只允许局部拓扑编辑与形变迁移，不允许静默重建；
- 每次外部 PSD 重导入先生成匹配报告：精确 ID、路径/名称、视觉指纹、歧义和未匹配项；
- 原图、导入用扁平层、生成层和 Cubism 当前源图不能混成一个不可追踪文件。

### 2.3 Mesh 不是统一密度铺网

自动 Mesh 的参数包含内外点间距、内外边界距、最小边距和透明阈值；半透明灰尘会导致异常网格。不同运动目标需要不同密度，轮廓转折、关节、嘴角和眼睑需要拓扑关注。官方还明确提示：绑定后自动重建 Mesh 会破坏既有形变。

因此 Mesh 工具必须输出：

- 像素 alpha 清理报告和孤立小区域报告；
- 轮廓环、内部点、三角形、UV、边缘距离和变形用途；
- 自交、退化三角形、未闭合边、纹理越界、极细三角形检查；
- 对眼睑、嘴、长发、刚性饰品分别选择策略，而不是全局 preset；
- 可手工移动/增删顶点且保留稳定顶点映射。

### 2.4 变形器层级既影响可修改性，也影响运行性能

Warp→Warp、Rotation→Warp、Rotation→Rotation、Warp→Rotation 各自适合不同运动。子对象越出父 Warp 范围虽然仍能运行，但会增加计算量，官方提供专门的验证与扩展功能。参见 [Combination of Parent-Child Hierarchy](https://docs.live2d.com/en/cubism-editor-manual/combintion-of-parent-child-relation/) 和 [Validate Deformer](https://docs.live2d.com/en/cubism-editor-manual/convenient-function-deformer/)。

因此每个层级变更都要验证：

- 无环、父类型合法、语义层级可解释；
- 所有 keyform 下子顶点均在父 Warp 安全范围内；
- Warp 分割数和范围与运动需要匹配，不制造巨大空 Warp；
- 公共跟随、局部修形、物理摆动分层，建模师可独立关闭或重调；
- 删除空 Deformer，阻止“一层一个模板”造成的无意义层级膨胀。

### 2.5 参数与物理必须先有运动，再有求解器

Cubism Physics 的输出对象是已经制作好摆动 keyform 的参数；输入、摆锤和输出编号需要一致。输出超过参数有效范围会产生卡顿，同一输出被多个组驱动时总影响不得超过 100%。左右或不同发束可以同组，也可以为独立表现分组。参见 [How to Set Up Physics](https://docs.live2d.com/en/cubism-editor-manual/physical-operation-setting/)、[About Physics](https://docs.live2d.com/en/cubism-editor-manual/physics-operation/) 和 [Standard Parameter List](https://docs.live2d.com/en/cubism-editor-manual/standard-parameter-list/)。

因此“独立物理”不是简单创建三个同名参数：

- 每片发束应有独立输出参数和局部摆动 keyform；
- 可共享头角度/身体角度输入，但每片具有可单调调节的延迟、阻尼、长度和输出比例；
- 验收要覆盖静止回正、快速左右转头、低/高 FPS、输入极值和多组叠加；
- 检查输出最大值、超调、抖动、穿插、接缝露底和影响总和。

## 3. 总体架构

```text
内置 Chat（Responses API / 可替换模型） ─┐
                                         ├─ Agent Runtime
ChatGPT/Codex/Gemini/其他 Agent ─── MCP┘   ├─ Skill Registry
                                              ├─ Planner / Workspace Authority
                                              ├─ Task Orchestrator
                                              └─ Tool Registry
                                                     │
                                           Domain Command Kernel
                              ┌──────────────────────┼─────────────────────┐
                         Read/View Tools       Asset Tools          Rig Tools
                              │                    │                    │
                         Project Graph     RGBA/Mask/Generator    Mesh/Deformer/
                              │                    │              Parameter/Physics
                              └──────── Append-only History Tree ────────────┘
                                                     │
                                PSD/KRA/CLIP import · CMO3/MOC3/JSON export
                                                     │
                                   optional Cubism External API Bridge
```

核心原则是“一套能力，两个入口”：内置 Chat 直接调用同一个 Tool Registry；外部 Agent 通过 MCP Adapter 调用。内置 Chat 不需要绕回本机 HTTP，避免两套权限、序列化和错误模型。外接 MCP 也不能直接操作 UI ViewModel，而是调用相同的 Domain Command Kernel。

### 为什么采用内置 API + 外部 MCP 的混合方案

- 外部 MCP：适合 ChatGPT Desktop、Codex、Claude Code 等已有 Agent，用户无需在软件中再次购买或配置模型能力；
- 内置 API：适合产品化的任务面板、进度、图像预览和断点续作；
- Tool/Skill/History 共用：同一任务可以从桌面 Agent 发起，在 psd2live 内查看和撤销；
- MCP 是控制平面，不是大文件传输协议。大图、PSD 和 checkpoint 存在工作区 Asset Store；Tool 返回短元数据、`ImageContent` 预览和有时效的本机资源引用。

当前桌面应用直接在 `127.0.0.1:23871/mcp` 提供带 Bearer Token 的 Streamable HTTP。ChatGPT Desktop / Codex 使用连接窗口生成的 TOML，Gemini / Antigravity 使用 HTTP JSON；其他 HTTP 宿主传递相同端点和 `Authorization` 请求头。只有宿主不支持 HTTP MCP 时才使用仓库根目录的 `mcp_proxy.py` 把逐行 stdio JSON-RPC 桥接到 HTTP。代理会维护 MCP Session、转发协议版本、在认证失败时重新读取 Token，并且只自动重试协议发现和只读 Tool；写调用超时后必须重新读取工程与历史，不能盲目重放。

顶部 **Agent / MCP → Agent / MCP 连接与安装…** 会显示在线状态、端点、Token、三类可复制配置和多宿主安装 Prompt。Token 由 Java Preferences 持久化；它代表当前本机工作区写权限，不应写入仓库或公开。服务端 `instructions` 与项目级 Skill 会成为跨工具约束。默认单工具超时为 60 秒，所以长任务必须通过检查点恢复。服务实现使用 [官方 MCP Kotlin SDK](https://github.com/modelcontextprotocol/kotlin-sdk)。

## 4. 工作区领域模型

```text
Project
├─ SourceDocument[]             原始 PSD/KRA/CLIP，不可变
├─ AssetRevision[]              RGBA、mask、补全图、生成来源
├─ LayerNode[]                  语义、左右、深度、遮挡、父子、显示名
├─ ArtMesh[]                    topology、UV、assetRevisionId
├─ Deformer[]                   Warp/Rotation、父子与用途
├─ Parameter[]                  ID、范围、默认值、keyform
├─ PhysicsGroup[]               input、pendulum、output
├─ ExportProfile[]              Cubism 目标版本与平台预算
├─ WorkspaceHead               当前可编辑状态所指向的历史节点
└─ HistoryTree                 追加式快照节点、分支、撤回和任务恢复
```

### 4.1 Agent 权限与不可变边界

通过 Bearer Token 完成认证的 Agent 被视为**当前工作区所有者**，而不是只能执行少量白名单动作的访客。只要能力已经由 Tool 暴露，Agent 均可直接调用，无需逐操作申请或等待审批，包括：

- 读取、增加、替换、移动、重命名、隐藏和删除工作区图层；
- 编辑 RGBA、mask、Mesh 顶点/拓扑/UV、Warp/Rotation Deformer 和父子关系；
- 创建、修改和删除任意参数、参数范围、Keyform（即 K 帧）和 Physics；
- 使用 Warp 笔刷、膨胀/腐蚀、变形、补全、模板拟合和低层批量操作；
- 启动、暂停、恢复和取消长任务，并在任务中自行选择工具与参数。

程序不以“审查 Agent 决策”为目标。它只拒绝无法形成合法工程状态的命令，例如引用不存在的对象、产生父子环、写入 NaN、生成损坏拓扑或违反目标格式硬约束；视觉质量检查以诊断信息返回，Agent 可以据此继续修正，而不是每一步等待用户批准。

唯一不可写边界是 **History Store 与 History Tree 本身**：

```text
HistoryNode {
  id, parentId, workspaceSnapshotHash,
  commandSummary, actor, taskId, createdAt
}

HistoryStore: append-only
WorkspaceHead: 可移动
```

- 每次成功的写命令或事务提交都追加新节点，永不覆盖、删除或改写已有节点；
- `history_checkout(nodeId)` 只把 `WorkspaceHead` 移到目标快照，不修改历史节点；
- 从旧节点继续编辑时自然产生新分支，原来的未来分支仍然保留；
- Agent 可读取完整树、比较任意两个节点、为节点加普通备注，并跳转到任意可达节点；
- Agent 没有直接写 History Store 的 Tool，无法伪造、删除或重排备份；
- 大型二进制资产按内容寻址保存在不可变对象库，历史节点只引用 hash，从而支持快速还原而不复制整份 PSD。

这与传统线性 Undo 不同：线性栈在撤回后继续编辑会丢弃 redo 分支，不满足本产品的恢复要求。现有编辑核心的不可变 `PuppetModel` 可以作为快照值复用，但外层必须由上述追加式树接管历史。

建议的语义字段：

```yaml
id: layer-uuid
type: hair
region: front
side: left
strandIndex: 1
strandCount: 3
lengthClass: medium
direction: down_left
depth: front
parentSemanticId: head
occludes: [forehead, eyebrow_left]
occludedBy: []
sourceAssetId: asset-original-bangs
confidence: 0.94
provenance:
  operation: hair_split
  sourceRevision: revision-123
  maskId: mask-456
```

显示名由命名策略生成，如 `hair_front_left_01`，但改名不会破坏引用。

## 5. Tool 设计

Tool 应小而可组合。Skill 负责组合顺序和判断，不把整个工作流固化为一个脚本；但安全与数据不变量必须写在程序中，不能只靠 Prompt。

### 5.1 读取和 Agent View

| Tool | 状态 | 作用 |
|---|---|---|
| `project_get_state` | 已实现 | 当前工程、revision、持久化状态、历史 HEAD、任务和选择摘要 |
| `project_list_layers` | 已实现 | 稳定 ID、语义、层级、bounds、可见性与软删除状态 |
| `project_list_parameters` | 已实现 | 全部参数 ID、范围、默认值、当前值与类型 |
| `object_get` | 已实现 | 读取 `mesh`、`warp`、`rotation`、`part`、`glue` 的层级、拓扑、几何、通道与现有 K 帧 |
| `view_render_layer` | 已实现 | 从 RGBA 模型数据直出透明/棋盘 PNG，不截 UI |
| `view_render_context` | 已实现 | 以指定部件为中心，按相对缩放观察周围上下文 |
| `view_render_model` | 已实现 | 在指定参数姿态和取景窗口内，合并指定图层并标注部件 |
| `graph_query` / `view_render_structure` | 规划中 | 图查询与 Mesh/Warp/旋转中心/父子边界标注 |
| `view_render_motion` / `validate_project` | 规划中 | 参数扫描、物理帧序列和工程完整性检查 |

`project_list_layers` 同时返回源层的 `rasterWidth/rasterHeight`、画布 `bounds` 和 `sourcePixelToCanvas`，明确区分源像素尺寸与模型中的实际尺寸。每个 View 返回 `viewId`、`revisionId`、`objectIds`、PNG 像素尺寸、完整画布尺寸、请求/实际取景 `viewRect`、`focusRect`、`canvasUnitsPerPixelX/Y`、可逆的 `pixelToCanvas` / `canvasToPixel` 仿射矩阵和 SHA-256。Agent 因而可以对精确对象采取下一步操作，不依赖屏幕坐标、截图或像素尺寸猜测。

`view_render_model` 的请求把姿态、标注和叠加内容分开表达。例如：

```json
{
  "parameters": {
    "ParamAngleX": 10,
    "ParamBodyAngleX": 2.5
  },
  "include_layer_ids": [
    "hair_front_left_01",
    "hair_front_center_01",
    "face"
  ],
  "annotate_layer_ids": [
    "hair_front_left_01",
    "hair_front_center_01"
  ],
  "viewport": {
    "mode": "focus_layers",
    "layer_ids": ["hair_front_left_01", "hair_front_center_01"],
    "object_scale": 0.65,
    "aspect_ratio": 1.0
  },
  "background": "transparent",
  "target_long_edge": 1024,
  "max_bytes": 4194304
}
```

- `parameters` 未给出的参数取模型默认值；值超出参数范围时仍允许求值，以便 Agent 检查超调和异常姿势，但结果会带范围诊断；
- `include_layer_ids` 省略时使用工作区当前可见图层，空数组表示不输出任何模型图层；
- `annotate_layer_ids` 只控制轮廓和标签，不隐式改变叠加集合；
- `viewport.mode=canvas_rect` 时 Agent 直接指定画布单位的 `left/top/width/height`；
- `viewport.mode=focus_layers` 时先取指定部件在当前参数姿态下的变形包围框，再构造观察窗口；`object_scale=1` 表示部件紧贴适配窗口，通常使用小于 1 的值观察周围，`0.5` 大致表示两倍范围；
- `aspect_ratio` 固定观察窗口比例，默认正方形；只通过扩展取景范围适配比例，不拉伸角色；
- `target_long_edge` 控制 Agent 实际看到的 PNG 分辨率，与画布单位解耦；超过 `max_bytes` 时只降低 PNG 分辨率，不改变所代表的画布区域；
- View Tool 先把 `include_layer_ids` 在服务端按绘制顺序合成为**一张 PNG**，MCP 不返回 PSD；PSD 只属于明确的导入/导出 Tool；
- 输出元数据回传实际采用的完整姿态、叠加图层、标注对象和空间变换，后续编辑不需要从像素反猜状态。

例如输出元数据中的空间部分：

```json
{
  "spatialReferenceId": "view-a1b2c3",
  "coordinateSpace": "canvas_top_left_y_down",
  "canvasWidth": 4096,
  "canvasHeight": 8192,
  "viewRect": {"left": 1220, "top": 410, "width": 960, "height": 960},
  "focusRect": {"left": 1430, "top": 610, "width": 520, "height": 430},
  "canvasUnitsPerPixelX": 0.9375,
  "canvasUnitsPerPixelY": 0.9375,
  "pixelToCanvas": [0.9375, 0, 1220, 0, 0.9375, 410]
}
```

### 5.1.1 Agent 生成 PNG 的回填与尺寸保持

“原本大小”是画布空间中的矩形与变换，不是 PNG 的像素宽高。Agent 可以把一个 1024×1024 View 编辑成 2048×2048 PNG；加入工作区时仍应占据同一个 `viewRect`，只会获得更高的像素密度。

当前 `asset_import_png` / `layer_add_from_asset` 使用以下默认契约：

```json
{
  "png_base64": "iVBORw0KGgo...",
  "spatial_reference_id": "view-a1b2c3",
  "source_pixel_rect": {"left": 0, "top": 0, "width": 1024, "height": 1024}
}
```

- 省略 `source_pixel_rect` 时把完整输出 PNG 映射回 View 的 `viewRect`，不使用 PNG 像素作为画布大小；
- 如果 Agent 只输出 View 中的一个子区域，必须同时给出该区域在原 View 中的 `source_pixel_rect`，程序通过 `pixelToCanvas` 换算位置；
- 默认严格拒绝长宽比不一致的图片，禁止悄悄拉伸；如确需改变比例，Agent 应重新请求合适长宽比的 View，或明确给出源 View 子区域；
- 对“刘海拆三片”，三张 PNG 可以具有不同像素分辨率，但应继承同一源 View 的空间参考或各自给出精确子区域，因此重新叠加时仍与原刘海严格对齐。
- 加层时先按画布矩形重采样，再在画布单位中裁掉透明边；使用预乘 Alpha 插值，避免透明边缘产生黑边或脏色。导出读取当前权威 SourceArt，不会重新读取 PSD 抹掉 Agent 图层。

### 5.2 素材与透明图层

| Tool | 状态 | 作用 |
|---|---|---|
| `asset_import_png` | 已实现 | 使用 View 的 `spatial_reference_id` 暂存透明 PNG；本身不移动历史 HEAD |
| `layer_add_from_asset` | 已实现 | 把暂存资产加入权威源图层，生成 Mesh/Rig 并追加历史节点 |
| `layer_soft_delete` | 已实现 | 从当前工作区移除图层但保留像素与历史恢复能力 |
| `selection_propose` / `selection_refine` | 规划中 | 根据语义和图像提出、细化 mask/路径 |
| `asset_split_preview` / `asset_inpaint_occlusion` | 规划中 | 内建切分预览与补全 Provider 接口 |
| `asset_export_import_psd` | 规划中 | 导出规范 RGB/8-bit/sRGB 导入 PSD 与 manifest |

透明图层的正确交付链路是：

1. 软件从 PSD 解码得到原生 RGBA，按 Agent 指定取景合成为 PNG，并通过 MCP `ImageContent` 给 Agent 看；
2. Agent 保留 View 的 `spatialReferenceId` 和像素↔画布映射，不使用 UI 截图坐标；
3. 只要任务会产生绘制差分、拆分边界、遮挡补全、重建像素或新 drawable，Agent 必须离开 PSD2Live Tool 链，实际调用宿主暴露的 Nano Banana Pro/NBP、GPT Image 2（`gpt-image-2`）或等效原生图片生成/编辑能力；
4. 禁止用 Python、PIL/Pillow、OpenCV、Matplotlib、SVG、Canvas、ImageMagick、脚本或手写多边形绘制替代素材。唯一无需生成器的情形，是所有输出 RGBA 样本均原样来自已可见源像素的严格裁剪/提取；生成后可做非创作性的 Alpha 清理；
5. Agent 把透明 PNG 以 Base64 或 data URI 传给 `asset_import_png`，再用 `layer_add_from_asset` 加入图层；若只输出 View 子区域，必须声明 `source_pixel_rect`；
6. Agent 用独立 View、上下文 View 和参数姿态 View 验证位置、边缘、遮挡和 Mesh，并在确认替代层有效后单独调用 `layer_soft_delete`；每个写入节点均可通过历史树恢复。

当前设计明确把图片生成路由留给宿主：PSD2Live MCP 负责权威模型取证、空间映射、素材暂存与工程写入，不假装暴露宿主私有的图像模型。若宿主没有任何可用的原生图片能力，Agent 必须停在最后一个可恢复状态并报告缺失能力。未来内置 Provider 仍须遵守相同来源、历史和验证契约。

### 5.3 Mesh、Warp 与绑定

| Tool | 状态 | 作用 |
|---|---|---|
| `parameter_create` / `parameter_update` / `parameter_delete` | 已实现 | 创建、修改或删除参数；参数编辑会持久化并随历史恢复与导出 |
| `object_get` | 已实现 | 按稳定对象 ID 读取拓扑、父子、bounds、几何与通道 K 帧 |
| `keyform_set` / `keyform_delete` / `keyform_copy` | 已实现 | 在精确 N 维参数坐标写入、删除或跨对象复制几何/通道 K 帧 |
| `rig_k_pose` | 已实现 | 在给定多参数姿态写入对象几何与通道，作为 K rig 便捷入口 |
| `mesh_generate_preview` / `mesh_move_vertices` / `mesh_brush` | 规划中 | 分部件 Mesh 草案、局部拓扑和笔刷变形 |
| `deformer_create_warp` / `deformer_fit_children` / `hierarchy_reparent` | 规划中 | Deformer 创建、范围适配与父子调整 |
| `keyform_interpolate` / `keyform_apply_template` | 规划中 | 中间形、组合角和标准模板拟合 |
| `physics_create_group` / `physics_simulate` | 规划中 | 物理组创建、输入扫描和质量诊断 |

模板必须是可解释的数据：适用部件、锚点、控制点、参数范围、keyform、允许缩放范围和失败条件。Cubism 官方模板同样需要先对齐布局，应用后再整理绘制顺序、父子层级与 ArtMesh；因此模板拟合低于阈值时应停止并交给人工，不能强行生成。参见 [How to Apply Model Templates](https://docs.live2d.com/en/cubism-editor-manual/applying-the-model-template/)。

参数与 K rig 的契约不能只提供“套预设”。Agent 必须可以访问底层值，例如：

```json
{
  "tool": "parameter_create",
  "arguments": {
    "expected_history_head_node_id": "node-123",
    "parameter_id": "ParamHairFrontLeft01",
    "name": "左前发 01 摆动",
    "min": -1,
    "default": 0,
    "max": 1,
    "kind": "normal",
    "repeat": false
  }
}
```

```json
{
  "tool": "keyform_set",
  "arguments": {
    "expected_history_head_node_id": "node-124",
    "target": {"kind": "warp", "id": "HairFrontLeft01_Warp"},
    "coordinate": {"ParamHairFrontLeft01": 1, "ParamAngleX": 10},
    "geometry": {
      "control_points": [120.0, 88.0, 146.5, 92.0, 171.0, 101.0]
    }
  }
}
```

`keyform_set` 允许 Agent 写入单参数或多参数组合角的精确坐标，当前 `target.kind` 为 `mesh`、`warp`、`rotation`、`part` 或 `glue`。几何字段按对象类型使用：ArtMesh 为扁平 `position_deltas`，Warp 为扁平 `control_points`，Rotation 为 `origin_x`、`origin_y`、`angle`、`scale`；通道包括 opacity、draw order、multiply/screen color、glue intensity 与翻转。`rig_k_pose` 使用同一底层写入机制，把显式或当前参数姿态记录为 Keyform。所有编辑保存为 `RigEditOverlay`，在 Pipeline 重建、历史检出、重启恢复和导出时重放。

程序仅检查结构不变量：参数 ID 唯一、`min ≤ default ≤ max`、坐标有限、顶点/控制点数量与目标拓扑一致、引用有效。诸如“这个摆幅是否好看”属于 Agent 与建模师的判断，不成为权限门。

### 5.4 事务与长任务

Agent 拥有完整写权限，但当前所有会追加工程历史节点的编辑工具都携带 `expected_history_head_node_id`：这不是审批，而是防止长任务把基于旧视图计算的结果覆盖用户或另一个任务刚完成的修改。调用方先从 `project_get_state` 或 `history_list` 取得 HEAD，每次成功后再使用响应中的新 `historyNodeId`。HEAD 不一致会返回 stale-head 错误，Agent 必须刷新工程并重新协调计划。`asset_import_png` 只暂存资产，`task_update` 只追加任务事件，二者不移动工程 HEAD。

单个工程编辑 Tool 当前就是一个原子提交：先在不可变工作副本上应用编辑并重建 Rig，成功后追加历史节点；失败不会留下半成品。下列多命令 Transaction 是后续扩展协议：

```text
transaction_begin(expectedHistoryHeadNodeId)
  → 多个 domain command
  → 可选 validate / view  # Agent 自主决定何时检查
  → transaction_commit(message) # 追加一个历史节点并移动 HEAD
  或 transaction_cancel
```

多命令事务将用于把“拆三层 + 生成 Mesh + 建 Warp + K 帧”等多步工作合并为一个可撤回节点；它尚未作为 MCP Tool 暴露。Agent 不需要先取得逐操作批准，但在当前版本中每个写 Tool 会分别产生历史节点。

历史 Tool 的最小契约为：

| Tool | 作用 |
|---|---|
| `history_list` | 已实现；返回 node、parent、revision、summary、task 和当前 HEAD，只读 |
| `history_checkout` | 已实现；把工作区 HEAD 切到指定节点并恢复其快照，不会删除任何分支 |
| `history_diff` | 规划中；比较两个节点的层、资产、Mesh、Rig、参数与 Physics 变化，只读 |

`history_checkout` 是工作区写操作，但不是 History Store 写操作。切换之后若 Agent 继续调用 `parameter_create` 或 `keyform_set`，提交节点的 `parentId` 就是所切换到的节点。

耗时任务不得占住一次 MCP 调用。当前已实现 `task_start`、`task_update`、`task_get`、`task_list`，用于保存 Agent 自己制定且可替换的计划、状态、进度、消息和产物 ID；它们是恢复检查点，不是审批或固定工作流。下列细粒度控制接口仍在规划中：

```text
task_events(taskId, cursor)                → 增量日志与待验收项
task_continue(taskId, decision)            → 接受、修改条件或继续
task_cancel(taskId)                        → 安全取消并回滚未提交事务
task_resume(taskId)                        → 从持久 checkpoint 恢复
```

当前任务状态由 Agent 通过 `task_update` 写入，并作为追加事件保留；Agent 可以替换动态计划，关联 View、Asset、Layer 与 History Node 等产物 ID。更完整的暂停、恢复、取消执行器仍属于后续阶段。`WAITING_FOR_USER` 只应用于确实缺少创作意图或外部输入，不是普通写操作的审批门。

## 6. Skill 与 Prompt 工程

Skill 是领域作业指导，不是固定脚本。它应声明：

- 开始前必须读取的结构与视图；
- 可选择的策略及判断条件；
- 禁止动作和失败条件；
- 必须调用的验证器；
- 可接受的质量阈值；
- 对用户的完成报告格式。

仓库当前提供 `.agent/skills/psd2live-rigging` 与 `.agent/skills/hair-separation`。连接窗口的“安装 Prompt”要求把它们复制到宿主官方 Skill 目录；任何 Agent 一旦调用 PSD2Live MCP，就先完整读取 `psd2live-rigging`，头发任务还要读取 `hair-separation`。两个 Skill 都强制执行宿主原生图片生成路由、最新 HEAD 并发边界、断线后查证和最终 View 验证；这些规则不影响不调用 MCP 的普通代码或文档工作。

例如“把刘海拆为三片并有独立物理”应由 Skill 引导 Agent 动态决定分割线、内外顺序、补全范围、Mesh 策略和物理参数；程序只强制不可变源、事务、alpha/拓扑/越界/物理约束。这样既保留模型判断力，也不把安全性寄托在 Prompt 是否听话上。

建议技能包：

- `psd-audit`：导入条件、素材缺失、命名歧义；
- `semantic-labeling`：部件、左右、前后和遮挡图；
- `hair-separation`：发束切分、补全、层级、Mesh 和独立物理；
- `face-rig`：面部 XY/Z、轮廓和五官协同；
- `eye-rig` / `mouth-rig`：眨眼、笑眼、口形与遮挡；
- `secondary-motion`：头发、衣物、饰品物理；
- `project-qc`：关键姿势、性能预算、命名和导出检查。

## 7. 示例任务的实际执行语义

用户：`把刘海拆分为三片，并且有独立物理`

1. 完整读取 `psd2live-rigging` 与 `hair-separation`，调用 `project_get_state`，并以 `task_start` 保存动态计划；
2. 查询前发候选层，获取透明独立 View、周围上下文 View，并用 `object_get` 检查现有 Mesh、Deformer 与 K 帧；
3. 判断素材是否完整，确定三片的根部、自然走向、交叠和缺失遮挡区；
4. 对每个需要新边界或隐藏像素的输出，实际调用宿主的 Nano Banana Pro/NBP、GPT Image 2 或等效原生图片编辑能力，生成保留风格与完整根部的透明 PNG；
5. 通过 `asset_import_png` 保留空间参考，再以最新 HEAD 分别调用 `layer_add_from_asset`；每次成功后把 View、Asset、Layer 与 History Node 写入 `task_update`；
6. 重新渲染独立和上下文 View，检查透明边、颜色、接缝、遮挡与整体还原度；确认三层正确后再单独软删除原层；
7. 分别生成用途匹配的 Mesh，检查退化/未闭合/过密；
8. 为三片创建独立局部 Warp，并挂到公共前发跟随 Warp 下；
9. 生成三个独立摆动参数/keyform，可共享物理输入但输出独立；已有目标的对象级 K 帧可用 `keyform_set` / `rig_k_pose` 写入；
10. 渲染中立、慢转、快转和极值，检查露底、穿插、超调、越界；
11. 记录最终 View 与节点 ID，并提供继续调节和 `history_checkout` 恢复入口。

当前公开 Tool 已覆盖步骤 1～6、已有对象的 K 帧编辑和姿态 View；步骤 7～9 中“新建/改 Mesh、创建 Deformer、创建 Physics”仍属于 Phase 2～3 的扩展能力。Agent 不得把尚未暴露的能力描述为已经完成。

如果图像、拓扑或极限姿态验证失败，任务停在最后一个可恢复节点，不对当前工程宣称完成。

## 8. Cubism 兼容策略

[CubismExternalEditMCP](https://github.com/nana7chi/CubismExternalEditMCP) 已证明 Cubism 5.4 Alpha 外部应用集成 API 可以封装成 MCP，覆盖结构查询与参数、部件、Deformer、ArtMesh、Glue 的事务式编辑。但它目前受 Alpha 版本、单模型和重启授权限制，主要暴露 Cubism 已有对象编辑，并不负责 PSD 像素分层、直接模型视图、资产来源和长任务。

psd2live 采用两层兼容：

1. `CubismBridge`：对官方外部 API 建立版本化 typed adapter；自动能力发现，并为已认证 Agent 保留 raw passthrough，确保新官方方法在 typed wrapper 完成前仍可访问；
2. `AutoLive Domain API`：提供官方 API 之上的 Asset Store、语义图、Agent View、像素/Mask 工具、模板拟合、质量验证、长任务和历史。

所有能力先在内部领域模型完成；导入 Cubism 后再做一次 ID 映射和 round-trip 验证。不能把 Alpha API 作为唯一数据真相，也不能让直接 Cubism 编辑绕过 psd2live 的 Command Log。

## 9. 桌面交互与未来 Chat

当前桌面端已完成：

- 顶级 **Agent / MCP** 菜单、在线状态徽标，以及连接配置/安装 Prompt 双页对话框；
- 主工作区 **History** 标签：绘制完整分支树，支持平移、缩放、搜索、节点详情、复制 ID 与检出恢复；
- 四个主视图下方的独立日志坞：折叠、拖动高度、按系统/Agent/图片筛选、清空和复制；
- MCP View 与导入资产的行内缩略图，以及带棋盘背景、尺寸/大小和复制功能的图片灯箱；
- GUI 与 MCP 共用同一历史快照和 `RigEditOverlay`，历史检出后重建当前可编辑模型。

内置 Chat 仍在规划中，并且不应只有消息气泡。后续产品界面包含：

- 对话区、动态计划卡与可取消/继续状态；
- Tool 时间线、Artifact 面板和结构/运动 Diff；
- 外部副作用提示：仅当任务要覆盖工作区外文件、发布或调用付费第三方服务时提示边界，不限制工作区内权限；
- Provider 设置：OpenAI API、自定义兼容 API 或“仅外部 MCP Agent”。

## 10. 分阶段实现

### Phase 0：已落地的垂直切片

- 应用启动时在 `127.0.0.1:23871/mcp` 启动 Streamable HTTP MCP；
- Bearer Token 持久化；顶部 Agent / MCP 菜单提供端点、Token、Codex TOML、Gemini/Antigravity HTTP JSON、通用 Stdio JSON 和多语言安装 Prompt；
- `mcp_proxy.py` 为仅支持 Stdio 的宿主维护 Session、协议版本和 Token 刷新，并只对安全只读请求作有限重试；
- `project_get_state`、`project_list_layers`、`project_list_parameters`；
- `view_render_layer` 透明/棋盘图层直出；
- `view_render_context` 支持按部件、相对缩放率和长宽比聚焦周围区域；
- `view_render_model` 支持显式参数姿态、图层叠加集合、部件标注、画布矩形或部件聚焦取景；
- 所有 View 合成为 PNG，并返回像素↔画布的可逆空间映射与压缩后实际分辨率；
- `hair-separation` MCP Prompt 和项目 Manifest Resource；
- 项目级 `psd2live-rigging` / `hair-separation` Skill 已落地，并强制把绘制差分、拆分和遮挡补全路由到宿主原生图片工具；
- 服务端 instructions 明确认证 Agent 的工作区所有者权限与不可改写历史边界；
- 使用官方 Kotlin MCP Client 做端到端握手与 Tool 测试。

### Phase 0.5：已落地的透明素材写入闭环

- View 空间参考在进程内登记，`asset_import_png` 只接受 PNG，并按完整 View 或 `source_pixel_rect` 计算画布位置；
- `layer_add_from_asset` 将任意生成分辨率规范化到画布单位、裁透明边、建立语义覆盖并通过正式 Pipeline 生成 Mesh/Rig；
- `layer_soft_delete` 不删除像素，原始层和派生层都可通过历史恢复；
- `history_list` 与 `history_checkout` 接入追加式、保留分支的 History Tree，写命令使用 `expected_history_head_node_id` 防止长任务覆盖并发修改；
- GUI 导出当前权威 SourceArt，派生层不会因重新读取原 PSD 而丢失。
- `task_start`、`task_update`、`task_get`、`task_list` 保存 Agent 自己生成且可动态替换的计划、阶段、进度、事件与产物引用；它不是审批或固定流程引擎。
- GUI 日志坞会接收系统、MCP、Agent 事件与图片；View/Asset 可以行内预览并打开图片灯箱。

历史树、暂存 Asset、任务检查点与 View 空间参考现已落到本机 Project Store：Windows 默认位于 `%LOCALAPPDATA%/PSD2Live/agent-workspaces`，也可用 JVM 属性 `psd2live.agent.store` 指定。历史节点、快照和 RGBA Blob 只创建不覆盖；RGBA 以原始字节 SHA-256 寻址并 GZIP 压缩去重，只有 HEAD 与任务检查点使用原子替换。关闭应用时先等待持久化队列排空。

Project ID 同时包含规范化 PSD 路径和加载时的文件签名，避免同路径 PSD 已被画师替换后自动套用旧工程。再次加载同一版本 PSD 时，程序在后台重建持久化 HEAD；如果建模师已在恢复期间修改结构，CAS 会拒绝覆盖，并把当前状态保留为新分支。`project_get_state.persistenceStatus` 返回 `ready`、`saving`、`restoring` 或 `error`。

### Phase 1：全权限 Domain Kernel 与不可变历史树（进行中）

- 已完成 History Tree、Asset、View 空间参考和长任务检查点持久化；继续把完整 Project Graph、Command 与多命令 Transaction 纳入同一格式；
- 已完成参数 `create/update/delete`，以及 `mesh/warp/rotation/part/glue` 的 `object_get`；
- 已完成 `keyform_set`、`keyform_copy`、`keyform_delete` 与 `rig_k_pose`，可持久化几何和可视通道编辑，并在 Pipeline 重建、历史检出、重启和导出时重放；
- 已完成桌面 History 分支树、节点详情与检出恢复；
- 把现有 UI 的可见性、重命名、父子修改、软删除迁移到 Command；
- 继续将所有写 Tool 统一到 `expected_history_head_node_id` 并发边界；
- 补齐 `history_diff`；现有 `history_list` / `history_checkout` 已支持撤回后继续编辑保留分支；
- 已支持重启后恢复工程和 Agent 任务；继续补迁移版本、存储维护与损坏恢复工具。

### Phase 2：透明素材闭环

- PSD Writer 与 Import Manifest；
- Mask/路径/Warp 笔刷、膨胀、腐蚀和羽化；
- 确定性切分、局部补全 Provider、alpha/接缝验证；
- `hair-separation` 完整写操作链路。

### Phase 3：结构自动化

- 分部件 Mesh 策略与编辑工具；
- Warp/Rotation 模板、父子越界验证；
- 参数/keyform 模板与组合角测试；
- 物理任务、仿真和质量指标。

### Phase 4：产品化 Agent

- 内置 Chat、模型 Provider、Skill 管理和 Eval；
- 长任务 UI、结构/运动 Diff、费用/Token/图像生成预算；历史树与日志/图片浏览已提前落地；
- CubismBridge 全能力矩阵与 round-trip 测试；
- 团队规范包、可观测性和匿名失败样本回收（明确 opt-in）。

## 11. 发布门槛与评测

每类自动任务都维护固定样本集与高级建模师盲评，不能只看最终静态图。指标至少包括：

- 静态还原：透明合成与原图像素差、接缝、边缘污染；
- 结构正确：对象可选、可命名、可重挂、可局部重做；
- 动态质量：关键姿势、组合角、物理回正、穿插和露底；
- 性能：顶点数、Deformer 数、越界顶点、参数组合和目标平台 FPS；
- 可维护性：高级建模师完成指定二次修改的时间；
- 自动化收益：人工精修时间必须显著低于从原 PSD 重做；
- 可恢复性：任意失败注入后工程 hash 与事务前一致；
- 重放性：同版本算法、模板和输入能得到相同结构结果。

最终是否“可用”的核心指标不是自动完成百分比，而是：**建模师在自动工程上完成真实返修所需的总时间，是否稳定低于手工重建。**
