# MCP 编辑与知识设计

MCP 同时提供可组合的操作、证据和按需知识，不要求统一的制作顺序。简单改名不需要绘画流程，绘画不绑定某个生成器，几何合法不等于视觉合格。

后续接口设计见 [面向 Agent 的 MCP 统一设计方案](AGENT_MCP_DESIGN.md)。整体重构尚未实现；本页描述当前可调用接口，包括已落地的姿态拼图。

## 当前接口

| 意图 | 接口 | 行为 |
|---|---|---|
| 找对象 | `rig_list_objects` | 名称、稳定 ID、父级、源图层 ID；query/kind 过滤，offset/limit 分页 |
| 名称、显隐、层级 | `object_edit` | 1–128 条有序编辑，一个历史提交；任一失败不提交 |
| 了解形状 | `rig_inspect` | 默认摘要、局部几何诊断；按需读取控制点 |
| 试算形状 | `rig_preview` | 不修改工程，返回相对输入姿态的位移、翻折、塌缩指标 |
| 修改形状 | `rig_transform` | 按参数姿态组合平移、缩放、旋转、弯曲、平滑、根部固定的 sway 和对应点 landmarks |
| 多姿态查看 | `view_render_poses` | 1–9 个姿态按输入顺序拼为一张图；公共参数只返回一次，每格保留 View 引用与画布映射 |
| 区域覆盖 | `view_check_coverage` | 在明确应被覆盖的画布矩形内，测量选定图层的 alpha 覆盖 |
| 素材接入 | asset 工具 | 原图像素、SVG 栅格化、绘画或生图均可；最终输入 PNG |
| 按需知识 | `agent_get_workflow` | overview / geometry / hair / variants / face / assets |

已有参数、K 帧、物理、注册、保存和历史工具继续保留。`agent_get_workflow` 无参数时改为返回短入口；几何详情使用 `topic="geometry"`。对象发现现在默认分页，需要继续读取 `nextOffset`。

## 多姿态拼图

固定转头姿态，比较张眼、半闭、闭眼，仍使用原工具：

```json
{
  "viewport":{"mode":"canvas_rect","left":300,"top":200,"width":400,"height":220},
  "parameters":{"ParamAngleX":15},
  "poses":[{"ParamEyeLOpen":1},{"ParamEyeLOpen":0.5},{"ParamEyeLOpen":0}],
  "columns":3,
  "target_long_edge":1024
}
```

示例坐标及参数 ID 必须换成当前工程的实际值。`parameters` 是公共值，单格 `poses` 覆盖同名值；`columns` 可省略，默认紧凑布局。`target_long_edge` 与 `max_bytes` 约束整张拼图，不是每格；小部件应使用局部镜头或减少姿态，不能无限缩小图片。低于最低可辨尺寸会返回预算错误，该下限不是外观质量保证。

返回一个 image，元数据包含公共 `revisionId / canvasRect / commonParameters` 与 `tiles`。每格 `parameters` 加上公共值构成该格记录的参数；`imageRect=[x,y,width,height]` 是拼图内**不含标签**的图像区域，`canvasRect=[left,top,right,bottom]` 为公共画布范围。像素点 `(px,py)` 映射为 `left+(px-x)/width*(right-left)` 与 `top+(py-y)/height*(bottom-top)`，仅对落在该格图像内的点有效。

格子的 `viewId` 仍引用原始单格 View；整张拼图不是可用于素材导入的空间参考。需要放大时，用该格参数和更小 `canvas_rect` 调用 `view_render_model`。原先的 `views[] + 多个 image` 输出已替换，消费旧返回格式的客户端需调整。

这表示同版本的静态姿态比较，不是版本前后比较或物理仿真。越界参数诊断仍按格返回。实际图像 token 取决于宿主编码，尚未测得节省比例。

## 基础编辑

```json
{
  "expected_history_head_node_id": "当前 HEAD",
  "edits": [
    {"action":"rename","kind":"mesh","id":"hair-1","name":"左刘海"},
    {"action":"visibility","kind":"mesh","id":"hair-original","visible":false},
    {"action":"move","kind":"mesh","id":"hair-1","parent_id":"hair-part"}
  ]
}
```

对象 ID 从发现接口获取。这里的改名是模型对象显示名，不会改源 PSD 的图层名称或重新触发语义分类。Part 的组织树用于归组和同绘制顺序时的排序；独立绘制顺序通道仍通过 K 帧接口编辑。

- `rename`：mesh、warp、rotation、part 的显示名。
- `visibility`：mesh、part 的静态可见性；表情切换使用参数下的 opacity K 帧。
- `move`：mesh/part 改变组织父级；warp/rotation 改变变形父级。可用 `before_id` 指定目标兄弟节点，组织树还需 `before_kind`。
- `bind`：将 mesh 绑定到另一变形器。

`parent_id:null` 表示根级。变形器移动和 mesh 绑定要求明确 `space:"local"`：保留局部形状和 K 帧，继承外观会改变。当前不声称支持跨父级的全运动保真换绑。

结构命令进入持久化日志，按顺序重放；新 Warp 创建也进入同一日志，避免“先换父级再创建子 Warp”恢复时顺序颠倒。旧工程没有日志时仍使用原 Warp 列表重建。

## 以附着和运动表达形状

```json
{
  "target":{"kind":"warp","id":"bang-sway"},
  "coordinate":{"ParamBangSway":1},
  "operations":[{
    "type":"sway",
    "root":[0.5,0.1],
    "tip":[0.6,0.95],
    "degrees":12,
    "softness":1.5,
    "root_pin":0.1
  }]
}
```

以上可提交给 `rig_preview`；实际编辑另加 HEAD 后使用 `rig_transform`。root/tip 是当前操作输入边界内的归一化点，计算在父级局部坐标中完成。正角度顺时针，root_pin 固定沿根梢方向的起始比例，softness 越大越集中弯曲梢部。选择区域可限制影响范围。

这是一种可调的形状操作，不是头发物理模拟或通用骨骼求解器。实际发片若只占父框的一小部分，应使用它自己的附着位置。原有 `warp_create` 仍使用与父网格对齐的恒等子框，避免裁小框导致继承变形重采样误差。

对应点操作 `landmarks` 接收 `from:[[u,v],...]` 与 `to:[[u,v],...]`，均相对当前输入边界。程序按父级局部距离插值位移；相同起终点可固定某处。对应关系由调用者提供，程序不自动识别图像特征，也不保证网格无翻折。

## 证据的含义

几何诊断比较父级局部的采样三角形。inspect 相对参数默认姿态，preview 相对修改前输入姿态。输出翻折、面积低于参考 1% 的塌缩和控制点位移。Warp 三角化是诊断近似，不能证明完整曲面或父级级联始终无异常。

覆盖检测需要调用者选择“这里应由头发覆盖”的矩形和头发层。下面的脸不参与检测，因此不会用不透明头皮掩盖发片缺口。输出未覆盖像素数、比例、包围框和带映射的预览。矩形内的合法发缝和轮廓外区域也会计入，不能把该数字当成审美评分；图像分辨率和 alpha 阈值同样影响结果。

多姿态渲染固定画布镜头，不执行物理时间模拟，不代表采样点之间均已验证。若工程版本在采样间改变，返回错误，避免比较不同版本。

## 素材与知识

省略 `solid_background` 保留 PNG 原生 alpha。显式传入实际底色才执行去底，参考图底色不会自动成为去底指令。需要严格透明检查时可使用 `require_transparency`。旧纯色输出调用应显式传底色。

`agent_get_workflow` 按任务返回内置短知识卡。头发卡强调完整根梢体积、相对运动覆盖、父级跟随与独立摆动；差分卡区分新绘画和显示关系；脸部卡解释姿态采样、结构和参考图的不确定性。知识文件随应用打包，由 MCP 直接提供，不需要宿主侧 Skill。

每次成功写入返回下一次可用的 HEAD，无需重复读取完整状态。超时或 HEAD 过期时才重新核对历史。长任务笔记可保存候选和假设，修正应有可指出的收益。时间和生成预算由用户决定，未暴露的宿主 token 消耗不估造。

当前没有自动图像到脸部 rig 的拟合器、完整物理链编辑器或全自动审美验收。这里重构的是编辑表达、知识入口和可验证的反馈，不将这些研究方向伪装为已实现能力。
