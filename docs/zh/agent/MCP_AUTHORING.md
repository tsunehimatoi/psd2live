# MCP 接口契约：合并工具层与分支契约

本页描述 PSD2Live MCP 的接口契约。**当前默认工具面只对外暴露 10 个合并工具**（`inspect`、`deform`、`form`、`rig`、`view`、`parameter`、`asset`、`physics`、`appearance`、`revision`，用 `request.mode` 选择分支），完整清单与字段差异见第 5 节；第 1–4 节记录这些分支背后的细粒度契约，其中的工具名（`project_get_state`、`view_render_model`、`asset_import_png` 等）**已不再对外注册**，仅用于说明各分支的语义。写工具统一用 `state` 传并发边界，不暴露 `task_id`。后续设计见 [AGENT_DESIGN.md](AGENT_DESIGN.md)，变形器与参数规范见 [DEFORMER_AND_PARAMETER_SPEC.md](../spec/DEFORMER_AND_PARAMETER_SPEC.md)，PSD 图层规范见 [PSD_LAYER_SPEC.md](../spec/PSD_LAYER_SPEC.md)，工程格式见中文摘要 [PROJECT_FORMAT.md](../spec/PROJECT_FORMAT.md) 或英文正文 [PROJECT_FORMAT.md](../../en/spec/PROJECT_FORMAT.md)，使用流程见 [USER_GUIDE.md](../guide/USER_GUIDE.md)，历史调研见 [AGENT_RESEARCH_2026-09-13.md](archive/AGENT_RESEARCH_2026-09-13.md)。接口按组合使用，不存在固定制作顺序：改名不需要绘画流程，绘画不绑定某个生成器，几何合法不等于视觉合格。

## 1. 发现与视图

| 工具 | 关键参数 | 行为要点 |
|---|---|---|
| `project_get_state` | - | 工程、`revisionId`、持久化状态、历史 HEAD、任务与选择摘要 |
| `project_list_layers` | - | 稳定 ID、语义、层级、`bounds`、可见性、软删除状态；另含源层 `rasterWidth/rasterHeight` 与 `sourcePixelToCanvas` |
| `project_list_parameters` | - | 全部参数 ID、范围、默认值、当前值、类型 |
| `rig_list_objects` | `query`、`kind`、`offset`、`limit` | 名称、稳定 ID、父级、源图层 ID，不含几何；`kind` 仅 `mesh/warp/rotation/part`，`limit` 1–256 默认 64，翻页读 `nextOffset` |
| `object_get` | `kind`、ID | 读取 `mesh/warp/rotation/part/glue` 的层级、拓扑、几何、通道与现有 K 帧 |
| `view_render_layer` | `layer_id`、`background` | 从 RGBA 模型数据直出透明/棋盘 PNG，不截 UI |
| `view_render_context` | `layer_id`、`object_scale`、`aspect_ratio` | 以部件为中心观察上下文；`object_scale` 默认 0.65 |
| `view_render_model` | 见下 | 指定姿态与取景内合成一张 PNG 并标注部件 |
| `view_check_coverage` | `viewport`(必须 `canvas_rect`)、`include_layer_ids`、`alpha_threshold` | 测量选定图层对指定画布矩形的 alpha 覆盖 |
| `view_render_poses` | `poses`、`columns` | 1–9 个姿态拼为一张图 |

`view_render_model` 请求字段：`parameters`、`include_layer_ids`、`annotate_layer_ids`、`annotate_deformer_ids`、`annotate_path_ids`、`annotate_path_width`、`annotate_path_hardness`、`annotate_path_radius`、`point_indices`、`viewport`、`background`、`target_long_edge`、`max_bytes`。

- 未给出的参数取模型默认值；越界值仍参与求值，只附范围诊断（便于检查超调与异常姿势）。
- `include_layer_ids` 省略时用当前可见图层，`[]` 表示不输出模型图层；`annotate_layer_ids` 只控制轮廓与标签，不改变叠加集合。
- `annotate_path_ids`：给路径 ID 列表或 `['*']`、`['L2']`、`['L3']`，在模型渲染上叠加变形路径的 Catmull-Rom 平滑曲线、控制手柄（圆形平滑点/菱形拐角点）与顶点编号，便于 Agent 结合视图直观调姿。
- `annotate_path_width`：布尔值（默认 `false`），开启后绘制各控制点的外层影响宽度（Width）虚线圆。
- `annotate_path_hardness`：布尔值（默认 `false`），开启后绘制各控制点的内层衰减硬度（Hardness）实体圆。
- `annotate_path_radius`：兼容别名，同时开启宽度与硬度圆圈显示。
- `viewport.mode=canvas_rect` 直接给画布单位 `left/top/width/height`；`mode=focus_layers` 用 `layer_ids`、`object_scale`、`aspect_ratio` 构造窗口。`object_scale=1` 为部件紧贴窗口，0.5 约两倍范围；比例只靠扩展取景适配，不拉伸角色。
- `target_long_edge` 控制返回 PNG 分辨率，与画布单位解耦；超过 `max_bytes` 时只降 PNG 分辨率，不改变所代表的画布区域。
- 服务端按绘制顺序合成**一张 PNG**；MCP 不返回 PSD，PSD 只属于明确的导入/导出工具。
- 每个 View 返回 `viewId`、`revisionId`、`objectIds`、PNG 尺寸、`canvasWidth/canvasHeight`、请求与实际 `viewRect`、`focusRect`、`canvasUnitsPerPixelX/Y`、可逆的 `pixelToCanvas` / `canvasToPixel` 与 SHA-256；不依赖屏幕坐标或像素尺寸猜测。

## 2. 多姿态拼图

```json
{
  "viewport":{"mode":"canvas_rect","left":300,"top":200,"width":400,"height":220},
  "parameters":{"ParamAngleX":15},
  "poses":[{"ParamEyeLOpen":1},{"ParamEyeLOpen":0.5},{"ParamEyeLOpen":0}],
  "columns":3,
  "target_long_edge":1024
}
```

- 示例坐标与参数 ID 必须换成当前工程实际值。`poses` 1–9 个按输入顺序排列，`columns` 1–3（省略为紧凑网格）；必须使用 `canvas_rect` 固定比较镜头。
- `parameters` 为公共值，单格 `poses` 覆盖同名值。`target_long_edge` 与 `max_bytes` 约束**整张拼图**，不是每格；低于最低可辨尺寸返回预算错误，该下限不是外观质量保证。小部件应改用局部镜头或减少姿态。
- 返回单个 image；元数据含公共 `revisionId`、`canvasRect`、`commonParameters` 与 `tiles`。每格 `parameters` 加上公共值构成该格记录的参数，另有 `id`、`viewId`、越界参数诊断。
- `imageRect=[x,y,width,height]` 是拼图内**不含标签**的图像区域；`canvasRect=[left,top,right,bottom]` 为公共画布范围。像素点 `(px,py)` 映射为 `left+(px-x)/width*(right-left)`、`top+(py-y)/height*(bottom-top)`，仅对落在该格图像内的点有效。
- `viewId` 仍引用原始单格 View；整张拼图不能作为素材导入的空间参考。放大时用该格参数与更小 `canvas_rect` 调用 `view_render_model`。旧 `views[] + 多个 image` 输出已替换，旧格式客户端需调整。
- 这是同版本的静态姿态比较，不做版本前后比较或物理仿真；采样间工程版本变化会返回错误。
- `view_sample_motion`：显式给出 `rect` 画布矩形、`frames`（2–32 个 `{time, parameters}`）、`samples`（1–9 个时间点）、`fps`（15–120）与 `target_long_edge`(128–4096)，驱动导出的原生模型按时间轴采样一张拼图；`time` 必须从 0 起严格递增且不超过 10 秒，参数 ID 必须已存在。
- `view_compare_history`：用 `states`（1–2 个历史节点）与 `poses`（1–4 个）在同一 `rect` 上交叉比较，用于判断某次编辑前后的差异。

**自然发束工作流的分工口径**（多工具组合，没有单一"精确分离发束"接口；知识来源是随应用打包的工作流文件，见第 6 节）：

- 拆分或差分前，先用独立图与角色上下文判断局部深度、交叉与遮挡，并记录"谁在什么区域遮住谁"；刘海不总在侧发前，侧发也不总在刘海前。同一对发束若在不同区域交换前后，单一图层次序不足，应按实际使用已有遮罩，或在自然遮挡处拆成共用一个绑定的绘制段，仍计为一条逻辑发束。
- 每条发束都要有从合理发根到末梢的自身形状，允许在邻束下继续延伸、交叉与重叠；不得把被遮住的面积从后层挖掉，也不得把相交 alpha 范围直接判作重复。生成与修正通常一次处理一条逻辑发束，试拼时用 `view_render_model.include_layer_ids` 排除原始整片前发。
- 验收目标：角色正常观看尺寸下整体发量、走势、颜色协调、根部接合与局部遮挡自然，并在预期运动范围内不露缝、不脱根、不出现不合理穿插。轻微轮廓差异、埋在遮挡下的根部偏移和小幅色调变化可接受；孤立图、像素差和夸张姿态只用于诊断，不是严格边缘匹配的通关条件。
- 仅修正影响整体观感或运动的实际缺陷；先区分层序、坐标映射、遮挡余量与绑定问题，再决定是否重新生图。没有"两次修正失败即停止"的固定次数限制，但边际收益很小时应调整深度解释、参考图或修正方式，或接受无害差异后继续。

## 3. 素材定位与去背景 v2

流程：参考包 → 约定纯色生图 → 去底色 → 注册位置 → 暂存试拼 → 加入已有父 Warp → 修正位置 → 完成定位 → 按需独立绑定。**不存在未绑定图层模式**：拆出的前发 1/2/3 默认直接继承原前发的父 Warp，独立子 Warp 与物理仅在需要时添加。

| 工具 | 必填/关键参数 | 返回与行为 |
|---|---|---|
| `asset_prepare_reference` | `layer_id`、`piece_id`、`background_color`、`target_anchors`；可选 `occlusion`、`source_canvas_rect`、`target_long_edge`(64–4096) | 干净参考图 + 单独标注上下文、源图裁切、变换、版本与原父级 |
| `asset_import_png` | `png_base64` 或 `png_path`；新流程用 `reference_id` 与实际 `solid_background` | 返回 `assetId`，保留原 PNG、处理参数与内容范围，不自动定位 |
| `asset_register` | `asset_id`、`mode: frame/landmarks/absolute`；`target_anchors`、`generated_anchors`、`generated_pixel_rect`、`transform`、`mirror_x/mirror_y`、`allow_stretch` | 返回不可变放置记录 `id`、变换、朝向、拟合误差与预览 |
| `asset_inspect` | `asset_id` | 返回实际 PNG、映射、透明/半透明像素统计；`image_order` 为"处理后, 原图" |
| `asset_reprocess` | `asset_id`、`solid_background`；可选 `background_tolerance`(0–64)、`processing` | 始终读取原图重新处理，返回新素材，需重新注册 |
| `asset_preview_composite` | `placements`(画家序，含 `registration_id`、`insertion`、`reference_layer_id`)、`replace_layer_ids`；可选 `source_canvas_rect` | 不新增正式图层或历史节点；`replace_layer_ids` 用于排除原层 |
| `layer_add_from_asset` | `asset_id`、`name`、HEAD；`registration_id`（参考包素材必需） | 默认继承参考源层父级，可显式 `parent_deformer_id`；生成 Mesh/Rig 并追加一个历史节点 |
| `layer_set_placement` | `layer_id`、`registration_id`、HEAD | 设置绝对位置、尺寸、旋转与显式镜像；保留父 Warp，始终从未缩放的处理素材重算 |
| `layer_finalize_placement` | `layer_id`、HEAD | 验证中立几何后标记定位完成；不创建初始绑定，不清除动画 |

`asset_import_png` 其他参数：`spatial_reference_id`（兼容旧 View 导入）、`source_pixel_rect`、`background_tolerance` 默认 16、`require_transparency`（拆发时置 true，拒绝全不透明或空结果）。`png_path` 必须是已存在的绝对路径，文件 8 B–64 MiB。

**锚点与坐标**

- 统一左上原点、X 向右、Y 向下。`target_anchors` 为源画布坐标；`generated_anchors` 为**完整原始生成 PNG** 的像素坐标，透明裁切不改变该坐标。
- 至少给出根部与发梢两个对应点；第三个非共线侧向点帮助识别朝向冲突。
- 求解器不隐式镜像，必须显式 `mirror_x/mirror_y`；非等比拉伸必须 `allow_stretch`。绝对变换的 `x/y` 指原 PNG 原点，不是透明内容左上角。
- `mode=frame` 仅在生成器保留画框时可用，留边或裁切通过 `generated_pixel_rect` 与 `source_canvas_rect` 声明；内容被居中、裁紧或放大时应改用锚点模式。源图参考始终是原始栅格，带姿态的 View 仅提供造型上下文，不作为可逆定位依据。
- 参考包记录原角色图层集合；加入候选并隐藏/软删原层后，解剖基准仍从保留的源图层计算，避免草稿改变头部方向。

**去底与处理**

- 新流程统一使用明确 RGB 纯色背景（纯白 `#FFFFFF` / 纯黑 `#000000` 为可选默认，也允许彩色底）。只移除与边界连通的近色像素，不处理已烘焙的棋盘格；算法从边界及背景提示点确定底色区域，在窄边缘带估计 alpha 并去除底色混入，缩放使用预乘 alpha。
- `processing.foreground_points` 保护同色前景，`processing.background_points` 处理封闭孔洞，`edge_width` 默认 3、范围 0–8；提示点均使用**原 PNG 坐标**。
- 诊断区分非纯色背景、疑似孔洞与边缘污染；存在透明像素不代表合格。参考图底色不会自动成为去底指令，省略 `solid_background` 即保留 PNG 原生 alpha；旧纯色输出调用应显式传底色。微小边缘差异不构成自然拼合的否决条件。

**定位提交与持久化**

- 位置提交检查中立几何与纹理坐标的对应关系，可发现边界范围相同的反向问题；失败不提交历史。
- 已有专属 Warp、关键形、glue 或已完成定位的对象拒绝整体重定位，既有动画保留；普通继承的父 Warp 不构成拒绝理由。
- 参考包、原始/处理素材、放置实例与定位状态均持久化并随工程保存；暂存操作不移动 HEAD，正式图层编辑继续使用 HEAD 校验、历史与恢复。
- 旧 `spatial_reference_id` 导入方式保留原语义，只能用于兼容路径，不应用来推断新生成素材的位置。
- MCP HTTP 请求体默认上限 **96 MiB**（`AgentMcpConfig.maxRequestBodyBytes`），超限返回 HTTP 413；此前 SDK 默认 4 MiB 会在工具执行前拒绝较大的 Base64 图片。该限制针对完整请求体而非 PNG 文件大小，图片解码后 16,777,216 像素上限仍保留。Base64 与 `data:image/png;base64,...` 均支持；图片字节应由宿主程序传输，不要让模型逐字生成 Base64。

**回填与尺寸保持**

- "原本大小"是画布空间中的矩形与变换，不是 PNG 像素宽高。1024×1024 View 可编辑成 2048×2048 PNG，加入工作区时仍占同一 `viewRect`，只获得更高像素密度。
- 省略 `source_pixel_rect` 时把完整输出 PNG 映射回 View 的 `viewRect`；只输出 View 子区域时必须给出该区域在原 View 中的 `source_pixel_rect`，程序通过 `pixelToCanvas` 换算。默认严格拒绝长宽比不一致的图片，禁止悄悄拉伸；确需改比例应重新请求合适比例的 View 或明确给出子区域。
- 加层时先按画布矩形重采样，再在画布单位中裁掉透明边；使用预乘 Alpha 插值以避免黑边脏色。导出读取当前权威 SourceArt，不会重新读取 PSD 抹掉 Agent 图层。

**素材交付链路**：`view_render_layer`/`view_render_context` 提供 View（含 `spatialReferenceId` 与像素↔画布映射，素材由原图像素、SVG 栅格化、绘画或图像编辑器提供，MCP 只限定格式与空间映射）→ `asset_import_png` 暂存 → `layer_add_from_asset` 加入权威源图层 → 用独立 View、上下文 View 与姿态 View 验证位置、边缘、遮挡与 Mesh → 确认替代层有效后单独 `layer_soft_delete`。`asset_split_preview`、`asset_inpaint_occlusion`、`selection_propose` 等尚未实现。

## 4. 形状、参数与绑定

| 工具 | 关键参数 | 行为要点 |
|---|---|---|
| `rig_inspect` | `target{kind,id}`、`coordinate`、`detail`(summary/points)、`space`(local/canvas)、`offset`、`limit` | 默认摘要与局部几何诊断、成本估算、可用参数轴；`points` 分页最多 256。运行时只存采样位置，无原生 Bezier 锚点或手柄 |
| `rig_preview` | `target`、`coordinate`、`operations` | 不修改工程、不推进历史；相对**修改前输入姿态**返回位移、翻折、塌缩指标 |
| `rig_transform` | 同上加 HEAD | 1–32 个有序操作，一次历史提交；`selection`+`range` 与操作共用 |
| `warp_create` | 父级归一化 `rows`/`columns`、`mesh_ids`、HEAD | 在共同父 Warp 下创建独立恒等子 Warp；`rows`/`columns` 是最小分段数，按共同倍数细分父网格并保留父插值模式 |
| `parameter_create/update/delete` | `parameter_id`、`name`、`min`/`max`/`default`、`kind`、`repeat`、HEAD | ID 为 1–64 位 ASCII 字母/数字/下划线且以字母开头；编辑持久化并随历史恢复与导出 |
| `keyform_set` / `keyform_delete` / `keyform_copy` | `expected_history_head_node_id`、`target`、`coordinate`、`geometry`、`channels` | 在精确 N 维参数坐标写入、删除或跨对象复制几何与通道 |
| `rig_k_pose` | `target`、`parameters`/`coordinate`、`geometry`、`channels`、HEAD | 把显式或当前参数姿态记录为 Keyform |
| `physics_list` | - | 列出手工创建的独立物理组；内置前后发预设另行生成 |
| `physics_put` | ID、输入/输出参数、`length`/`mobility`/`delay`/`acceleration`/`output_scale`、HEAD | 创建/替换单 Angle 输入的双粒子摆锤组，并在同一历史提交中启用物理 |
| `object_edit` | `edits`、HEAD | 1–128 条有序编辑，一个历史提交；任一失败不提交 |

**几何操作**：`type` 为 `translate`、`scale`、`rotate`、`bend`、`curve`、`smooth`、`sway`、`landmarks`。`delta`/`amount`/`curve` 是宽高比例，`pivot` 归一化，`rotate` 正角度在 Y 向下局部坐标中为顺时针。`selection` 用稳定归一化静息域：`rect=[left,top,right,bottom]`、`indices`（最多 512）、`center+radius` 或 `line=[x0,y0,x1,y1]+radius`，`range.radius` 与 `range.feather`(0–0.5) 提供平滑径向衰减。

`sway` 示例（可提交给 `rig_preview`，实际编辑再加 HEAD 用 `rig_transform`）：

```json
{
  "target":{"kind":"warp","id":"bang-sway"},
  "coordinate":{"ParamBangSway":1},
  "operations":[{"type":"sway","root":[0.5,0.1],"tip":[0.6,0.95],"degrees":12,"softness":1.5,"root_pin":0.1}]
}
```

`root`/`tip` 是当前操作输入边界内的归一化点，计算在父级局部坐标中完成；`softness` 0–8（默认 1）越大弯曲越集中梢部，`root_pin` 0–0.95（默认 0）固定沿根梢方向的起始比例。这是可调的形状操作，不是头发物理模拟或通用骨骼求解器。实际发片若只占父框一小部分，应使用它自己的附着位置；`warp_create` 仍使用与父网格对齐的恒等子框，避免裁小框导致继承变形重采样误差。

**对应点**：`landmarks` 接收 `from:[[u,v],...]` 与 `to:[[u,v],...]`（各 1–64 对），均相对当前输入边界；程序按父级局部距离插值位移，相同起终点可固定某处。对应关系由调用者提供，程序不自动识别图像特征，也不保证网格无翻折。

**`object_edit` 词汇**：`{expected_history_head_node_id, edits:[{action, kind, id, ...}]}`，例 `{"action":"move","kind":"mesh","id":"hair-1","parent_id":"hair-part"}`。`parent_id:null` 表示根级；变形器移动与 mesh 绑定要求明确 `space:"local"`，保留局部形状与 K 帧但改变继承外观，当前不声称支持跨父级的全运动保真换绑。

| `action` | 语义 |
|---|---|
| `rename` | `mesh`/`warp`/`rotation`/`part` 显示名；不改源 PSD 图层名，也不重新触发语义分类 |
| `visibility` | `mesh`/`part` 静态可见性；表情切换改用参数下的 opacity K 帧 |
| `move` | `mesh`/`part` 改组织父级，`warp`/`rotation` 改变形父级；`before_id` 指定目标兄弟，组织树还需 `before_kind` |
| `bind` | 把 mesh 绑定到另一变形器 |

Part 组织树用于归组和同绘制顺序时的排序，独立绘制顺序通道仍通过 K 帧接口编辑；`kind` 仅 `mesh/warp/rotation/part`，`before_kind` 仅 `mesh/part`。

**Keyform 与通道**：`keyform_set` 支持单参数或多参数组合角；`target.kind` 为 `mesh/warp/rotation/part/glue`。几何字段按类型区分——ArtMesh 用扁平 `position_deltas`，Warp 用扁平 `control_points`，Rotation 用 `origin_x`、`origin_y`、`angle`、`scale`；通道包括 opacity、draw order、multiply/screen color、glue intensity 与翻转。所有编辑保存为 `RigEditOverlay`，在 Pipeline 重建、历史检出、重启恢复与导出时重放。程序只校验结构不变量：参数 ID 唯一、`min ≤ default ≤ max`、坐标有限、顶点/控制点数量与目标拓扑一致、引用有效；"这个摆幅是否好看"属于 Agent 与建模师的判断。

**物理与结构日志**：输入/输出参数必须已存在，每个独立组使用不同输出参数；自定义组与内置预设使用相同 ID 或输出参数时替代该预设，避免竞争同一输出。参数还需通过 `keyform_set` 绑定对应 Warp 的摆动形状、发根保持固定——创建物理对象本身不生成摆动关键形。设置保存在分支历史中，模型重建、项目恢复、`.physics3.json` 与可编辑 `.cmo3` 导出均使用这些设置；仅网格模式不导出物理。结构命令进入持久化日志并按顺序重放，新 Warp 创建也进入同一日志，避免"先换父级再创建子 Warp"恢复时顺序颠倒；旧工程没有日志时仍使用原 Warp 列表重建。

## 5. 合并工具层：当前唯一对外暴露的接口

活动 MCP 服务对外暴露的只有这一层合并工具，上文的细粒度名称是各分支背后的契约。写操作把公开的 `expected_history_head_node_id` 改名为 `state`，其余字段名不变；`task_id` 不暴露；PNG 导入用宿主本机绝对路径 `png_path`，不走 Base64。

| 工具 | 模式 / 作用 |
|---|---|
| `inspect` | `scope` 为 `project/objects/layers/parameters/physics/paths`，或给 `target="kind:id"`：返回直接参数轴、通道、父级链与绑定的 `paths`（不含密集点数组）；`query`、`offset`、`limit` 1–64 |
| `path` | `mode`：`get`、`list`、`preview`、`put`、`delete`、`deform`。`get/list` 检视路径与坐标；`preview` 试算 MLS 位移指标并返回高清对比诊断图（ImageContent）；`put` 传 `[x, y]` 自动绑定三角面创建路径；`delete` 删路径；`deform` 拖动控制点并烘焙为目标参数角 `key` 的 `positionDeltas` 关键形 |
| `deform` | `state` + `changes`（1–128）：`target`、`key`、`operations`（1–16）、可选 `selection`；原子生效 |
| `form` / `parameter` | `form` 的 `op` 为 `seed`/`copy`/`set`/`delete`，写入通道或 Rotation 形，不写 Mesh 点数组；`parameter` 只有 `create`、`update`（`delete` 尚未暴露） |
| `rig` | `state`、`name`、`targets`（1–64 个 Mesh，须共享同一 Warp 父级）创建独立拟合 Warp，返回 `target` |
| `view` | `mode`：`model`、`layer`、`context`、`coverage`、`poses`、`motion`、`compare`。`model`/`poses` 支持 `annotate_path_ids` 叠加变形路径；`poses` 返回一张标注拼图；`motion` 按导出模型时间轴采样；`compare` 跨历史节点比较 |
| `asset` | `create`、`split`、`reference`、`import`、`register`、`preview`、`add`、`place`、`finalize`、`inspect`、`reprocess`、`remove` |
| `physics` / `appearance` / `revision` | 分别只有 `put`；一次有序编辑完成改名/显隐/重组；`save`、`checkpoint`、`list`、`restore` |

- `view` 的 `model` 分支继承 `view_render_model` 的 `parameters`/`include_layer_ids`/`annotate_layer_ids`/`annotate_deformer_ids`/`annotate_path_ids`/`annotate_path_width`/`annotate_path_hardness`/`annotate_path_radius`/`point_indices`/`viewport`/`background`/`target_long_edge`/`max_bytes`，`poses` 分支继承第 2 节全部约束；`inspect` 只给直接轴与父级链，不返回点数组。
- `path` 工具针对 ArtMesh 提供类似 Live2D 路径变形器的直觉控制点操作：
  - `put`：控制点数组 `points` 可直接给网格局部坐标 `[[x, y], ...]` 或 `[{"x": x, "y": y, "corner": false}, ...]`，服务端调用 MLS 自动投影并绑定至最近网格三角面；省略 `width` 时按网格局部包围盒尺寸自动设置（12% 跨度），`hardness` 默认 0.5，`level` 默认 2，`closed` 默认 false。
  - `preview`：根据 `moved_points: [[x, y], ...]` 试算 MLS 变形，支持传入可选的 `width` 与 `hardness` 试算不同影响范围与衰减硬度下的位移效果，支持 `show_width`（默认 true）与 `show_hardness`（默认 true）控制诊断图中的宽度虚线圆与硬度实体圆叠加；返回受影响顶点数、最大位移、平均位移、形变前后包围盒与 `width`/`hardness` 诊断指标，并默认渲染 640×640 诊断位移图（含原始灰网格、变形后青色网格、绿色路径曲线、控制点索引编号、位移向量箭头与宽度/硬度范围圈；可通过 `render: false` 关闭），不推进工程历史。
  - `deform`：必须包含目标参数角 `key`（需指定该网格上绑定的全部几何轴），移动控制点后通过 `workspace.authorRig` 将形变顶点位移原子化编译为标准 `positionDeltas` 关键形。
- `deform` 的 `selection` 支持 `rect`、`center`+`radius`、`line`+`radius`，另有 `feather` 与 `hardness`（宽平台），不提供 `indices` 白名单，需要逐点控制点数组时只能用细粒度接口；操作集是 `translate`、`scale`、`rotate`、`arc`、`curve`、`landmarks`（`arc` 对应细粒度接口的 `sway`，`root_pin` 语义相同）。目标上直接绑定的参数轴若未显式给出会报错，不会静默取默认值。

## 6. 工作流知识的现状

应用**不再提供** `agent_get_workflow` 工具与 `hair-separation` prompt，也不再打包 `src/main/resources/mcp/workflows/` 知识文件：`installAuthoringTools()` 在注册合并工具前会移除全部旧工具与 prompts，服务端也不再声明 prompts 能力。服务端只保留 `instructions` 这一层常驻约束。

需要作业指导时请查阅仓库文档：[Agent 设计](AGENT_DESIGN.md)（工具收敛、建模流程与验收）、[PSD 图层规范](../spec/PSD_LAYER_SPEC.md)、[变形器与参数规范](../spec/DEFORMER_AND_PARAMETER_SPEC.md)、[用户指南](../guide/USER_GUIDE.md)。

## 7. 事务、历史与长任务

- 所有会追加历史节点的编辑工具都携带 `state`（乐观并发边界，不是审批）：先从 `inspect`（`scope: project`）或 `revision`（`list`）取 HEAD，每次成功后用响应中的新 `state`。HEAD 不一致返回 stale-head 错误，必须刷新工程并重新协调计划。
- 单个编辑工具即一次原子提交：先在不可变工作副本上应用并重建 Rig，成功后追加历史节点，失败不留半成品；`asset`（`import`）只暂存资产、不移动 HEAD。多命令 `transaction_begin/commit/cancel` 尚未作为 MCP 工具暴露，当前每个写工具分别产生历史节点。
- 历史操作通过 `revision` 的 `save`/`checkpoint`/`list`/`restore` 完成：`list` 只读返回 node、parent、revision、summary、task 与当前 HEAD；`restore` 把 HEAD 切到指定节点并恢复快照，不删除任何分支（它是工作区写操作，不是 History Store 写操作）；`checkpoint` 在模型未变时也可追加显式检查点；`save` 保存完整工程并立即检查点，须先在 UI 选好文件。跨节点的差异比较用 `view`（`compare`）；更完整的结构 Diff（`history_diff`）尚未实现。
- History Store 追加式且对 Agent 不可写：成功提交只追加节点，`history_checkout` 只移动 `WorkspaceHead`，从旧节点继续编辑自然产生新分支，原未来分支保留。大型二进制资产按内容寻址保存在不可变对象库，节点只引用 hash。
- 任务记录（`task_*`）在源码中已实现，用于保存可替换的计划、状态、进度、消息与产物 ID（View、Asset、Layer、History Node），是恢复检查点而非审批或固定工作流；但**当前合并工具面未暴露 `task_*`**，相关的 `task_events`/`task_continue`/`task_cancel`/`task_resume` 同样尚未暴露。`WAITING_FOR_USER` 只用于确实缺少创作意图或外部输入。
- 默认单工具超时 60 秒，耗时任务不得占住一次 MCP 调用；超时或 HEAD 过期时才重新核对历史。写入成功即返回下一次可用的 HEAD，无需重复读取完整状态。
- 程序只拒绝无法形成合法工程状态的命令（引用不存在的对象、父子环、NaN、损坏拓扑、违反目标格式硬约束）；视觉质量以诊断返回，不逐操作等待批准。

## 8. 诊断口径与已知边界

- 几何诊断比较父级局部的采样三角形：inspect 相对**参数默认姿态**，preview 相对**修改前输入姿态**；输出翻折、面积低于参考 1% 的塌缩与控制点位移。Warp 三角化是诊断近似，不能证明完整曲面或父级级联始终无异常。
- 覆盖检测需调用者给出"这里应由头发覆盖"的矩形与头发层；下面的脸不参与检测，因此不会用不透明头皮掩盖发片缺口。输出 `pixelCount`、`uncoveredPixelCount`、`uncoveredFraction`、`alphaThreshold` 与未覆盖包围框。矩形内的合法发缝与轮廓外区域同样计入，该数字不是审美评分；图像分辨率与 alpha 阈值都会影响结果。
- 多姿态拼图固定画布镜头，不执行物理时间模拟，不代表采样点之间均已验证；`view`（`motion`）走导出模型的时间轴采样，不替代物理整定判断。
- 最终发束质量由整体试拼判断：`asset`（`inspect`）只做可用性与 alpha 快速诊断；孤立图、像素差与夸张姿态用于诊断，不是严格边缘匹配的通关条件。
- 支持任意图像模型一次精确输出不是承诺；当前没有自动图像到脸部 rig 的拟合器、完整物理链编辑器或全自动审美验收。
