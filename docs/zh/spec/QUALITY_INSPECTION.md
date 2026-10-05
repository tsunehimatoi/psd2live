# 质量检验等级与提交栅栏

本文规定模型作者流程的质量检验架构。已实现范围为几何候选检验；不代表所有导出、模拟或素材诊断都已迁移，也不代表模型视觉质量已得到证明。

## 分层与职责

1. **证据层**：`core/quality/RigGeometryDiagnostics` 测量三角形，`GeometryQualityCheck` 对比原模型与完整重建后的候选，产出稳定规则码、对象、坐标和证据。不直接抛出质量拒绝，不接受调用方的严格程度开关。
2. **规则与报告层**：`core/quality/QualityInspection.kt` 定义 `QualityRule`、`QualitySeverity`、`QualityCategory`、`QualityFinding` 和 `QualityReport`。规则表是默认等级的唯一来源；传输层从同一规则表生成严格 schema。
3. **栅栏层**：`QualityFence.AUTHORING_COMMIT` 根据报告决定放行。`application/WorkspaceCandidateQuality` 选择适用检查并在提交前执行栅栏。GUI journal、内部 typed 编辑、MCP 单项/批量及试运行使用同一入口。检测器、界面、操作处理器不自行改变等级或决定拒绝。

检查在运行时锁外执行，状态核对与 CAS 仍由工作区命令负责。信息和警告不弹确认、不要求用户豁免、不产生额外历史节点。取消、状态冲突、请求 schema、ID/引用有效性与资源预算等业务不变式使用各自的操作契约；它们不是可以随质量等级降级的美观检查。

## 统一等级

| 等级 | 含义 | 作者提交栅栏 |
| --- | --- | --- |
| `info`（信息） | 可正常制作出的形态变化，供观察者了解 | 放行 |
| `warning`（警告） | 值得检查的质量风险，或检查覆盖不完整 | 放行，并保留诊断 |
| `error`（错误） | 数据不满足求值与结构有效性要求 | 阻止提交 |

等级描述证据，栅栏决定动作；两者是不同概念。当前只实现作者提交栅栏，不能通过布尔参数临时启用一套隐藏的严格策略。将来若新增生成验收或导出栅栏，须在统一栅栏表、公开契约和规范中显式定义，不得改写现有证据等级。

| 稳定规则码 | 分类 | 等级 |
| --- | --- | --- |
| `GEOMETRY_NEW_FLIP` | `quality` | `info` |
| `GEOMETRY_NEW_COLLAPSE` | `quality` | `info` |
| `GEOMETRY_NEW_DEGENERATE` | `quality` | `warning` |
| `GEOMETRY_SAMPLING_LIMIT` | `coverage` | `warning` |
| `GEOMETRY_NON_FINITE` | `validity` | `error` |
| `GEOMETRY_INVALID_TOPOLOGY` | `validity` | `error` |

翻面及压缩可用于正常的镜像、布料折叠与姿态制作。有限坐标下的零面积或近零面积也是质量风险，不等同于文件损坏；NaN、无穷值、索引越界与几何维度不匹配仍是有效性错误。Cubism 的 Culling 默认关闭，允许双面绘制；变形路径也明确支持翻折效果。依据：[ArtMesh](https://docs.live2d.com/en/cubism-editor-manual/concept-of-artmesh/#culling)、[变形路径](https://docs.live2d.com/en/cubism-editor-manual/deformpath/)。本文阈值是本项目的诊断规则，不是 Cubism 的导出限制。

## 报告与兼容字段

统一报告位于几何诊断的 `quality` 字段：

- `version:1`：协议版本。
- `fence:"authoring_commit"`：采用的栅栏。
- `decision`：`accept`、`accept_with_diagnostics` 或 `reject`。
- `can_commit`：栅栏是否放行；不表示视觉质量合格。
- `complete`：是否完成声明范围内的检验；采样超预算为 false。即使 true，也不代表覆盖全部动画和视觉行为。
- `scope`：具体检查边界。
- `findings`：稳定 `code`、`severity`、`category`、`target` 及结构化 `evidence`。调用方不得从英文详情文本判断等级或业务行为。

原有 `safe` 保留为 `quality.can_commit` 的兼容别名；`violations` 只包含阻断的错误，`warnings` 只包含警告，新增 `information` 只包含信息。它们从同一证据和规则表派生，不维护另一套分级。试运行的 `would_commit` 还要求候选实际有变化。正式结果的 `geometry_diagnostics` 与试运行的 `diagnostics` 对同一输入及候选应完全一致。

## 几何覆盖与数值规则

检查受影响对象的父级局部普通关键点、混合形关键点及权重限制点组合，仅检查最终完整重建后的候选。原模型在候选坐标上采样，以区分既有和新增形态；拓扑变化时采用新参考网格。整表面的可逆仿射镜像与压缩豁免翻面和明显塌缩提示。

三角形面积比例不足参考形的 1% 为明显塌缩信息；有向面积绝对值不足 `1e-12` 或面积比例不超过 `1e-6` 为数值退化警告。同一个三角形退化时不再将极小负面积重复标为翻面。

每对象最多展开 16384 个关键点组合；超限对象先检查原始几何有效性，再跳过该对象的组合采样，返回 `GEOMETRY_SAMPLING_LIMIT`、对象级 `not_sampled` 和 `complete:false`。继续检查其他对象，不能用部分结果冒充全覆盖，也不能让预算超限隐藏原始数据错误。

这些检验不覆盖关键点之间的插值扫描、父级组合变形、Glue、遮罩、像素覆盖、物理或美观。新增检查必须明确范围与未覆盖项，保留可取消性、基线对比和稳定规则码，并补充统一规则及预演/提交契约测试。原始数值查询可以返回测量值；一旦表达质量等级或影响提交，必须进入本规范的规则与栅栏层。
