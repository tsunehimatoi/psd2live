# 工程、运行时与导出边界

[文档目录](../../README.md) · [工程格式](PROJECT_FORMAT.md) · [实现概览](IMPLEMENTATION_COMPARISON.md)

按当前源码整理。解析器存在某个类型、保留原文件未知字段、能够在 UI 编辑，以及在目标软件中效果一致，是四种不同的支持程度。

## 数据流

```mermaid
flowchart LR
    PSD[PSD / 新素材] --> Source[SourceArt 与工程设置]
    Source --> Rig[RigBuilder + 编辑日志重放]
    Rig --> Model[PuppetModel]
    Model --> Preview[求值与预览]
    Model --> Export[格式转换与导出报告]
    Export --> CMO[CMO3]
    Export --> MOC[MOC3 与边车文件]
    Source --> Project[psd2live 归档]
    History[历史 / 编辑覆盖 / 素材记录] --> Project
    History --> Rig
```

| 层 | 主要对象 | 职责 |
| --- | --- | --- |
| 工程 | `SourceArt`、`WorkspaceDocument`、历史和素材库 | 保存源像素、分类、配置、结构与可重放编辑 |
| 运行时 | `PuppetModel` | 参数、Part、Warp / Rotation、ArtMesh、Glue、关键形、通道与绘制关系 |
| 格式 | `Cmo3Model`、`MocDocument` | 编辑器对象图与运行时文件结构 |
| 求值与交付 | CPU / 原生预览、转换器、Pipeline、Sidecars | 采样、渲染、输出文件族与损失诊断 |

PSD 导入与图片工程创建共用独立应用层 `WorkspaceSourceImporter`，读取前捕获状态，清除旧工程覆盖后重建并 CAS 安装新 ID/加载代次。GUI 分析负责可信调用和投影，MCP 返回进程任务；未保存切换默认拒绝、明确放弃后才允许替换，任务取消不能将已安装结果报告为回滚。

工程重开会重建 Rig 并重放编辑。新功能若只改 `PuppetModel` 而没有进入源工程或持久化编辑日志，重建后会丢失。画布、MCP 与保存流程都必须遵守这个边界。

### 工作区发布与界面会话

`WorkspaceRuntime` 是已提交文档、模型与历史的唯一权威。应用命令捕获起始 state，在隔离候选上按顺序重建和重放，成功后通过 CAS 发布并追加一个历史节点；失败或提交前取消不发布批量前缀，无变化不追加节点。读取、导出和保存使用捕获版本，后续编辑不改变进行中的任务输入。提交成功后发生的界面刷新错误不能被报告为编辑回滚。

界面状态按领域分给 `ModifierController`、`MaterialController`、`CanvasGestureController`、`TimelineGestureController` 和 `ProjectLifecycleController`。它们持有对话框、选择、手势草稿和请求代次等临时状态，正式操作仍进入同一应用命令。连续编辑在结束时提交一次；项目 ID、加载代次及请求序号限制迟到回调的适用范围，避免旧工程的保存或导航结果覆盖当前界面。

`WorkspacePersistenceCoordinator` 按目的地跟踪请求版本、实际完成版本、排队数量及失败。内存提交、恢复副本写入和工程文件保存是不同完成状态；已写入较旧版本不能把新修订显示为全部已保存。失败重试只重试持久化，不生成重复编辑历史。操作说明见[用户指南](../guide/USER_GUIDE.md)。

构建的可变缓存由 `ModifierBuildCacheOwner` 管理，已发布模型与活动读取快照保持不可变。关闭工程释放缓存和 worker 租约，但不能破坏仍被捕获读者使用的几何或资源。两类构建计划试点、预算、异步回收及性能工具的边界见[合成性能观测与缓存边界](SYNTHETIC_PERFORMANCE_BASELINES.md)；具体通过的验收范围见[实测记录](../STATUS.md)。

### Cubism 对应字段的权威格式

与 Cubism 一一对应的可编辑数据，以 Cubism 的单位、范围和语义作为唯一权威格式；创建设置、模型、编辑日志、检视面板与导出共享该格式。算法需要的局部坐标、归一化比例等只在计算边界临时转换，不作为另一份编辑状态保存。

变形路径的 `width` 直接对应 CMO3 `lineWidth`，是画布像素中的影响半径，默认 `50`；`hardness` 直接对应 `lineHardnessPercent`，范围 `0–100`，默认 `50`。创建过程不再根据网格尺寸乘百分比推算半径。闭合状态、角点和编辑层级保留其既有语义映射。

新路径日志带 `path_units: "cubism"`。读取不带该标记的旧日志时，将局部半径沿父变形器链转换为画布半径，将旧硬度比例乘以 `100`；重新编码后使用权威格式，避免重复转换。CMO3 导入与导出直接读写原生半径和硬度数值。

## 当前产品入口

主画布已提供选择、形变、网格拓扑、变形器创建、Glue、路径和绘画会话。参数、检视、动画和物理面板提供相应控制。

GUI 与 MCP 共用应用层命令：参数公开 `parameter_create/update/delete`，骨架与动作片段使用 `skeleton_* / motion_*`，既有源图层分类使用 `layer_classify`，画布结构与拓扑使用 `canvas_* / rig_edit_structure`，工程设置与模型导出使用 `settings_update / project_export_model`，模型预设使用 `model_apply_preset`。素材支持 PSD 导入、CMO3 新建/替换导入、图片工程创建与源图绘画。源图拆分（`source_split_components/source_split_polygon/source_split_depth`）为后台任务和批量成员，与 GUI 共用候选/重建/CAS：生成的模型上部件以 `art_primitive` 记录取代原图层，携带原图层已有的关键形、通道、混合形、路径、顶点权重与 Glue；导入 CMO3 模型沿用旧记录：连通块与多边形拆分软删除原图层，深度拆分在原网格之上复制出前层（见[文档层](DOCUMENT_LAYER.md#拆分物化画元记录-art_primitive)）。另有逐图层网格配置、预览参数会话与 PSD 导出。低层对象方法不能自动视为公开接口。详见 [MCP 契约](../agent/MCP_AUTHORING.md)。

`org.umamo.edit` 的通用编辑会话也不是 PSD2Live 历史的唯一权威入口。接入底层编辑能力时，需要转换为工作区可保存、可重放的操作。

## 格式支持范围

| 内容 | 当前能力 | 需要区分的边界 |
| --- | --- | --- |
| 网格、Warp / Rotation、参数、普通关键形 | 核心模型、编辑与导出 | 跨父级换绑不等于全运动保真；源图 / 参数配置影响生成结果 |
| 颜色、透明度、绘制顺序、遮罩 | 模型与编辑通道 | 目标版本和格式表达能力可能带来降级 |
| Glue | 模型、可视创建、刷接、方向权重与关键形 | 配对修复与诊断工具仍有限 |
| Blend Shape、Part 绘制组等 | 底层模型与格式映射 | 完整产品编辑工作流仍有缺口 |
| 变形路径 | 工作区编辑、CMO3 控制器、MOC3 烘焙 | 单 ArtMesh；未宣称与官方编辑算法一致 |
| 物理 | 统一的物理组目录（预设 / 骨骼 / 摆动 / 自定义，可修改、关闭、恢复、调整计算顺序），多输入多输出与 1–16 个摆锤，计算 FPS，physics3.json 导入，输入与摆锤预置，面板可视化编辑，MCP `physics_put/delete/simulate/fit/config/import`、`physics_audition*` 试听与 `physics_preset_*` 预置，软件预览按 Cubism 求值 | 输入 / 输出类型限位置X 与角度（CMO3 支持的范围），不含风力等全局物理设置 |
| 动作 | 基础动作生成与播放；动画面板与 `motion_*` 编辑片段、参数曲线和时间线关键帧（插值类型、控制柄、键的移动 / 粘贴 / 替换） | 生成的基础动作须先转为同名覆盖片段（`motion_seed_builtin`）才能编辑；导出须启用 `exportMotions` |
| Expression / Pose / UserData | 工程数据（`core/ModelSidecars.kt`、`MotionClip.events`、网格用户数据），动画面板与 `expression_*` / `pose_*` / `motion_set_event` 编辑，导出 exp3 / pose3 / motion3 `UserData` / userdata3，见[导出目标](EXPORT_TARGETS.md#表情姿势与事件) | 编辑画布与预览不套用姿势（预览 bundle 不带 pose3）；动作没有部件透明度曲线，姿势只在加载时显示每组第一个部件；cmo3 / can3 不保存表情与姿势 |
| ArtPath、Motion Sync、扩展插值与部分编辑器元数据 | 类型或原对象图可能存在 | 不能据此宣称从 PSD 可创建或完整语义编辑 |

CMO3 新建及替换导入共用应用层 `WorkspaceCmo3Importer`：从文件准备可重建的源图和导入 Rig，不套用 PSD 自动 Rig 或动作预设；替换按 ID 更新、保留未出现对象，文档和姿态重置一次 CAS。GUI 与 MCP 调用同一源图能力，MCP 导入使用进程任务。支持范围仍受统一模型及转换器约束。

CMO3 的格式库可以保留原对象图中的部分未知信息，但从 PSD 新建 CMO3 时只能合成已建模的数据。这是格式库能力，不代表主程序可直接打开所有 CMO3 工程。MOC3 由统一模型合成，不能依赖同样的未知字段透传。

## 文件族与验收

`.moc3` 不包含全部运行配置。贴图、`.model3.json`、显示信息、物理和动作等由边车文件共同表达，是否输出取决于设置及模型参数。

流水线检查中立、头部姿态、方向性变形器及格式读回，输出诊断和警告。文件存在只说明写出成功；还需检查报告、目标版本剥离及实际视觉效果。正式交付应在目标运行时加载整个文件族，并在目标编辑器检查需要继续编辑的 CMO3。

## 后续工作

重点包括统一边车资产模型、完整 Blend Shape / Glue 编辑、扩展插值、更多物理结构、导出损失的一致展示与真实兼容性样本。产品计划在[路线图](../ROADMAP.md)维护，不在此复制未验证完成状态。

新增功能至少覆盖：领域数据 → 历史重放 → 工程保存恢复 → 目标版本处理 → 导出读回 → 视觉检查。只有测试实际覆盖的范围才能写成兼容性结论。

源码：[工程](../../../src/main/kotlin/io/github/psd2live/project/) · [核心流水线](../../../src/main/kotlin/io/github/psd2live/core/) · [运行时](../../../umamo/src/main/kotlin/org/umamo/runtime/) · [格式转换](../../../umamo/src/main/kotlin/org/umamo/interop/)。
