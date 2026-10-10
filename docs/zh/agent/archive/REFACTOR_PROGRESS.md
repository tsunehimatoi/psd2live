# AI / MCP 重构验收进度

> 归档资料：本文是 2026-09 至 2026-10-05 应用层与 MCP 重构的阶段记录，该重构已随 PR #19（`0a9c3761`）合并。文中的操作计数、测试类名、日志路径和“未完成”说法均为当时状态，不代表当前版本。现行接口见 [MCP 接口](../MCP_AUTHORING.md)，设计见 [Agent 设计](../AGENT_DESIGN.md)，播放与跟踪会话见[画布渲染器](../../spec/CANVAS_RENDERER.md#11-播放与跟踪会话)，后续工作见[路线图](../../ROADMAP.md)。

这是进行中的实现记录，完整重构尚未完成。架构规则集中在 `CLAUDE.md`（该文件目前未纳入版本库，本文引用指向维护者本机副本），当前可调用接口见 [MCP_AUTHORING.md](../MCP_AUTHORING.md)。

用户要求处理上游冲突后暂停，准备 Claude 交接；之后工作已恢复，五个业务域已在 `e133d37` 前实现，`49a6e86..046a725` 关闭了其后记录的三项未结问题并修复 CI 暴露的异步竞态，PR CI 两平台全量通过，仍待桌面手动验收。当前源码、证据边界、未结问题和恢复步骤集中在 CLAUDE_HANDOFF.md（交接文件已在 `d4b6307c` 删除，未结事项见[路线图](../../ROADMAP.md)）；下面各阶段记录保留其当时的测试范围（包括当时的操作计数），不代表最新全量通过。

## 已确定的范围

- 面向外部 Agent，不内置聊天或模型服务；覆盖 GUI 的全部业务能力，不包含主题、布局及快捷键。
- 直接替换旧 API，不维护兼容期；最终工具采用 `domain_operation`，全部使用 `request` 包装。
- 应用层可脱离 Compose 运行与测试，本轮不增加独立无界面服务器。
- 文档编辑支持 1–128 项的单次原子批量、失败回滚、一个历史节点；不支持跨调用事务。
- 修改必须包含工程 ID、加载代次与持久版本组成的预期状态、请求 ID；读操作纯查询，帧更新不改变持久版本。
- 导入、重建、保存、导出、烘焙、时间采样使用进程内任务，提供查询、等待与取消；断线继续，进程重启不恢复。
- 切换工程默认拒绝未保存修改，由调用方先保存或明确丢弃；MCP 不触发 GUI 模态对话框。
- 保持 v1 工程格式、已有历史节点及重放 ID、GUI 辅助数据；生成图片工程也能保存和重开。

## 当前已经落地

### 当前检查点（第一组相关回归通过，第二组仍待完整复验）

- 姿态与时间线：姿态与自动打键同次 CAS、时间线键操作与动作生命周期、播放/跟踪时钟和实时物理通过相关回归。
- 生成迁移：冻结早期生成规则并保存有序过渡，使分类、嘴部派生层和生成模式切换保留作者编辑；普通、新建、分区与导入对象通过合成回归。
- 分区与 Glue：导入模型的多边形、连通块和深度拆分、实时切点插值、有 ID 的 Glue 通道及连续连接渲染路径通过相关回归。CMO3 导出保留跨网格对交错的实际 Glue 求值顺序；渲染设备适配回归不能替代 Umamo 原生 GL 实测。
- 拆分物化：生成模型上新的多边形、连通块和前后分层拆分写出 `art_primitive` 记录，部件取代原图层，原图层离开源图、纹理集与导出，“恢复全部”不再带回；旧 `canvas_source_partition/canvas_depth_split` 按原规则重放（旧版本保存的工程核对 IR 哈希）。导入 CMO3 模型的拆分仍按旧规则软删除原图层，显式迁移旧拆分的命令尚未提供。见[文档层](../../spec/DOCUMENT_LAYER.md#拆分物化画元记录-art_primitive)。
- 画布 Glue 与顶点权重：共享中立候选和严格公开工具覆盖刷接、方向权重、解绑、重连及形状/衰减/连通限制权重笔刷；重放、完整重开及导出回归通过。
- 物理 UI 与预设：面板创建、复制、预设、观察峰值拟合和导入已转共享应用命令；全局预设库使用稳定 ID 与独立状态令牌，新增 MCP 列表/保存/改名/删除/应用，相关回归通过。
- 导出：GUI/MCP 改为同一个捕获已提交版本的应用会话；文件族先暂存、发布失败恢复被覆盖文件，成功后立即保留终态。独立会话和 GUI/MCP 导出回归通过，其他领域的导出读回仍在验收。
- 会话迁移：摆动草稿/试听/播放及画笔多笔触撤销/重做/跳转/取消/一次提交已迁入应用层并通过相关回归；不把临时帧写入持久姿态。
- 最终验收：另做逐入口 UI/MCP 审计，统一代码审查、相关回归与两平台全量验证。下面的 888 项测试和图片证据属于此前通用网格阶段，不能证明这一轮并行改动已通过。

### 第二组已经实现、仍待完整验收的入口

- 实时 Sim preview：已实现独立应用会话及启动、重启、逐帧求解、读取和 PNG 观察；省略作者姿态等修复仍待完整复验。
- Mesh/Warp 变形笔刷：已共用平滑、膨胀、连通限制、多网格、接缝保持及 Ctrl 保留子对象算法；fixture 与导出比较修复待复验。
- Warp 拓扑与 Bezier：已实现行列重采样、分段数、锚点/手柄/重置的有序持久化与共享 GUI 候选，新增 7 项在首次全量通过。
- 绘制顺序：已实现共享 `layer_draw_order` 候选；显式覆盖动画顺序，重置恢复原生成或作者动画，新增 4 项在首次全量通过；连续输入控件值绑定已补修。
- 当前冻结并准备交接，最终两平台全量、其余归档/导出与桌面视觉仍未完成。

### 本轮首次集中验收（未通过）

三个实现组冻结后，产品与测试源码编译通过，日志为 `build/parallel-refactor-checkpoint-compile-3.log`。首次选择 48 个相关测试类：实际运行 227 项，53 项失败、无跳过；日志为 `build/parallel-refactor-checkpoint-tests.log`，原始 XML 保存在 `build/parallel-refactor-checkpoint-results/`，失败清单为 `build/parallel-refactor-checkpoint-failures.json`。本轮不能标记为验收通过。

接口契约测试确认当前注册 146 项公开操作、51 项后台操作、72 项批量成员。失败正在分组修复，涉及生成与分区迁移、导出进度、未配置完成的物理组、姿态持久规范化，以及尚未接入真实后端的旧 GUI 测试。编译后发现审计另抓到一个新增权重测试返回 `Path`，JUnit 没有发现它；必须修正并重新运行，不能以首次测试数代表完整新增覆盖。

### 本轮第二次集中验收（未通过）

冻结修复后重新编译并运行 50 个测试类，共 251 项，239 项通过、12 项失败，无错误或跳过；日志为 `build/parallel-refactor-checkpoint-tests-2.log`，原始 XML 在 `build/parallel-refactor-checkpoint-2-results/`，失败清单为 `build/parallel-refactor-checkpoint-2-failures.json`。编译产物审计确认 944 个带 Test 注解的方法全部返回 void。已修复导出进度、物理未完成配置、生成设置等价判断及重复姿态规范化；剩余失败涉及导入设置误触发生成迁移、分区导出、输入库存、姿态时钟，以及要求运行时附属文件却未开启运行时导出的测试条件。修复后的代码尚待再次验证。

### 本轮第三次集中验收及最终单类复验（相关回归通过）

第三次运行 50 个测试类、252 项测试，251 项通过，仅 Glue 对象的数组引用比较断言失败，无错误或跳过；日志 `build/parallel-refactor-checkpoint-tests-3b.log`，原始 XML 为 `build/parallel-refactor-checkpoint-3-results/`。改为逐字段内容比较后，单独复验 `WorkspaceCanvasWeightTest` 的 3 项全部通过；日志 `build/parallel-refactor-checkpoint-canvas-weight-final.log`，XML 为 `build/parallel-refactor-checkpoint-canvas-weight-final-results/`。两次运行共同验收该组，不能写成一次 252 项全通过或两平台全量通过。编译产物审计确认 945 个 Test 方法全部返回 void。

此组修复包括导入配置保持、生成输入及分区透明覆盖、通道默认参考、CMO3 交错 Glue 顺序、显式播放时钟及运行时导出测试条件。生成、分区、姿态及权重的合成 PNG 已目视检查；归档与导出读回由实际回归验证。之后新增的实时模拟、笔刷、Warp 控制与绘制顺序不在此证据范围内。

### 第二组首次全量验收（未通过）

修复接口接线及测试源码编译错误后，Windows 全量运行 196 个测试类、976 项：947 项通过、19 项失败、10 项按既有条件跳过，无错误。日志 `build/parallel-refactor-checkpoint-full-3.log`，原始 XML 为 `build/parallel-refactor-checkpoint-full-3-results/`，失败摘要为 `build/parallel-refactor-checkpoint-full-3-failures.json`。编译产物审计确认 976 个 Test 方法全部返回 void；368 个改动文本文件无行尾或尾随空格问题。

本次严格契约测试确认 158 项公开操作、58 项后台操作、79 项批量成员。绘制顺序 4 项和 Warp/Bezier 7 项新增回归通过；其合成重开 PNG 已目视检查。模拟会话的省略姿态处理、GUI 异步边界、导入/净无变化批量和形变手势 fixture 等失败已分组处理，并按要求冻结为草稿 PR 检查点；这些后续修复尚未运行回归，19 项失败仍是最近完整测试的基线。骨架首次创建后的异步模式续接和导入替换失败仍待处理。本次不代表当前组或完整重构已通过；后续五个审计缺口仍待实现。

冻结后的草稿 PR 源码通过 Windows / JDK 21 的 `gradlew.bat compileKotlin compileTestKotlin --offline`，日志为 `build/refactor-pr-compile.log`。此检查只确认编译，不替代修复后的回归与最终两平台验收。下面三张为合成测试模型的渲染证据，不代表桌面窗口验收：

![绘制顺序归档重开](images/refactor-checkpoint-draw-order.png)
![Warp 控制归档重开](images/refactor-checkpoint-warp.png)
![形变笔刷](images/refactor-checkpoint-deform.png)

### 上游冲突处理与暂停交接

已整合上游 `master` 的 20 个提交至 `f66636ce`，保留 GPU 渲染、局部画笔上传、指南/路径/ghost 与网格单位。修复旧接口和中立 RenderPoint 接线后，Windows / JDK 21 的生产及全部测试源码编译成功。专项 8 个类、27 项：26 通过、1 失败，无错误或跳过；日志 `build/refactor-merge-handoff-targeted-2.log`，XML `build/refactor-merge-handoff-targeted-2-results/`。GPU 8 项、网格单位/编解码 8 项、骨架入口 6 项和新试听核心 3 项通过；CMO3 类另一项通过，失败位于保存重开历史一致性（额外 `Save project` 节点），不是替换任务失败。1003 个 Test 方法均返回 void，不能将此发现数当作运行数。当前新试听核心尚未接 GUI/MCP，仍属后续缺口。

按用户要求，完成冲突后暂停，并通过交接文件（已删除）交接实际源码、证据和待办。最近完整测试仍是 976 项/19 失败基线，本次专项不能替代新全量；没有验收当前源码的 Ubuntu 或桌面窗口。恢复时先诊断保存差异并复验旧失败，再实施最后五域。

### 恢复后的五域实现（`2045d15..e133d37`，待验收）

当前注册表契约测试 `WorkspaceAuthoringContractsTest` 固定 **170 项公开操作、58 项后台操作、79 项批量成员**。新增 12 项均为会话或查询，不是后台任务或批量成员：`physics_audition / physics_audition_step / physics_audition_get`、`skeleton_draft_open / _list / _get / _edit / _preview_transfer / _commit / _cancel`、`canvas_visibility / canvas_visibility_get`。

- 保存差异：交接时 CMO3 专项多出的 `Save project` 节点来自桌面设置投影与规范编码的 `meshUnits` 键序不同；revision 对设置 JSON 文本取哈希。`a561f12` 改为规范键序，`WorkspaceSettingsCodecTest` 固定顺序，历史断言未放宽。
- 物理试听与实测拟合：`physics_fit` 接受 `observed_peaks` 并与面板 Fit 共用 `FitObserved` 候选；面板摆锤与新会话共用 `PhysicsAudition`，步进在副本上求解，读取不推进时钟，重开或组被删除后会话为 stale。
- 骨架草稿：`core/SkeletonDraftEdits.kt` 的类型化意图与纯处理器由 GUI 骨骼编辑工具和 `skeleton_draft_*` 共用；会话以自身姿态 CAS 重置 rest pose 并只在该谱系上提交。
- 局部显隐与层级：`CanvasVisibilityProcessor` 与辅助 CAS 只改所寻址的 workspace/canvas/mode 会话，不改文档、历史或导出；层级眼睛、solo、全部显隐/反转和变形器眼睛改走该处理器。文档图层可见性改为独立字段，GUI 眼睛不再改变导出可见性。层级拖放写一条 structure journal 的 bind/move（`space=local`，不重新拟合），旧 v1 parentOverrides 仍在构建时先应用。
- 设置联动：见下文 `WorkspaceSettingsIntent` 与 `WorkspaceSettingsPolicy` 三条；字段会话内的开关在 `e133d37` 时仍只走本地草稿，已在下一节修复。
- 多次输入画布草稿：`WorkspaceCanvasInputDraft` 在首个输入时捕获 state、范围、模型、pose、目标与映射，确认对同一捕获编译并写入；冲突时草稿保留可取消。GUI 的 Warp/Rotation 放置、knife 与 path 已接入。
- GUI 修复：导入 CMO3 模型可在 GUI 中深度拆分前后层，与 `source_split_depth` 一致；到达的 SDK 帧不再被读取了旧状态的软件预览 tick 覆盖。

新增非 GUI 回归：`SkeletonDraftEditsTest`、`WorkspaceSkeletonDraftSessionsTest`、`WorkspaceSkeletonDraftOperationsTest`、`WorkspaceCanvasVisibilityTest`、`WorkspaceHierarchyEditsTest`、`WorkspaceSettingsIntentTest`、`WorkspacePhysicsAuditionSessionsTest`、`WorkspaceCanvasInputDraftTest`，并扩展 `WorkspacePhysicsCommandsTest` 与 `WorkspaceSettingsCodecTest`。GUI 改动（`CanvasEditor`、`PSD2LiveViewModel`、`DesktopWorkspace`、`PendulumCanvas` 等）只由 PR CI 编译和运行测试，没有本机桌面手动检查。

### 五域之后的收口修复（`49a6e86..046a725`）

注册表计数不变（170 / 58 / 79）。这一段关闭了 `e133d37` 时记录的三项未结问题，并修复 CI 暴露的异步竞态：

- 字段会话设置开关（`1122a86`、`77270b9`）：字段会话内的 meshOnly、动作子项和 generatePhysics 开关仍更新本地草稿用于显示，同时按顺序记录；`WorkspaceDraftQueue` 改为准备文档加辅助数据草稿，`prepareEditorDraft` 先经 `WorkspaceSettingsIntent` 重放这些开关，再应用其余草稿差异，一次 CAS。关闭再开启同一开关也释放作者姿态；只含这类开关的会话同样提交。画布编辑运行中被拒绝的命令改为提示，不再静默丢弃。
- GUI 显隐写入（`c9ef355`、`539b978`）：深度拆分的新前层与导入图片改经 `editCanvasVisibility` 在命令提交后的 state 上显示，图片放置从该显示发布的 state 继续；`CanvasVisibilityProcessor.endSolo/endSolos` 让 CMO3 替换在携带重置姿态的同一辅助候选中结束已保存的 solo。
- 形变笔刷预览/提交一致性（`11e1352`、`648e684`、`046a725`）：预览改用与提交相同的 `RigAuthoringJournal` 编译，被无变化容差丢弃的命令也不进入返回模型；笔触采样落在 1/1024 px 网格上，指针笔触与等价命令提交的 UV 一致；笔触 journal 只捕获目标几何实际依赖的参数（关键形轴、混合形绑定及限制），不再把生成视图参数等无关值写入。比较容差未放宽，新增 `WorkspaceCanvasStrokePoseTest` 与 `CanvasDeformStrokeTest` 用例。
- 画布编辑姿态（`0b16237`、`082bef2`）：笔触解析姿态只保留模型现有参数并钳制到范围，播放、摆动或过期面板值不再使共享几何命令以 “Unknown or out-of-range parameter” 拒绝；拒绝信息带参数名。
- 异步竞态（`49a6e86`、`c98671a`、`bf79f04`、`762c285`）：Cubism 预览会话只为已挂载画布加载，迟到的加载结果不再覆盖已就绪帧；提交编辑草稿会作废进行中的本地预览重建，避免 “Editor draft changed before its commit”；动作循环的时钟帧只携带数值，不再把画布动画开关切回；暂停物理释放在状态锁内复查，接受的帧在同一锁内发布姿态与就绪状态；骨架草稿测试等待 rest pose 提交完成。
- 文档（`05b67df`、`5367f65`）：物理、摆动、模拟、路径指南和规格改用当前注册表操作名，`inspect` 简称统一为 `workspace_inspect`。

PR CI 在 `046a725` 上对同一源码运行 `gradlew test`，Ubuntu 与 Windows（JDK 21 Temurin，不含 Cubism SDK）均通过；CI 日志不输出用例计数，依赖 SDK 或 GPU 的用例在该环境中可能跳过。这是当前源码的两平台全量证据，但不包括桌面窗口、原生 GL 或 Cubism SDK 路径。仍未结的问题见[路线图](../../ROADMAP.md)。

### GUI 参数同步修复

`046a725` 时 GUI 改参数改为异步提交到运行时后，参数面板、编辑画布、预览和物理面板会短暂读到不同的值：松开滑块时临时值先清空、提交落地前回退到旧值；上一次提交未完成时，再次松开、重置或输入数值会被 `saveWorkspaceEdit` 拒绝并丢弃；吸附到关键帧的动画只写预览帧，面板和编辑画布不动；拖动时不计算骨骼约束，联动参数要等松手才跳过去。修复后：

- 作者姿态的修改立即写入 `parameterValues`，同时登记为待提交值；`parameterScrubPose` 把待提交值叠在播放/跟踪帧与作者姿态之上，所以预览、编辑画布、暂停物理与面板解析同一姿态。
- 姿态修改（数值、锁、重置、IK 目标、吸附）进入 ViewModel 自己的 FIFO 队列，不再因忙碌被拒绝。每项仍携带手势开始时的 state；队列只沿自己已落地的提交前进，期间任何其他修改仍按 `state_conflict` 拒绝，失败时撤销所有待提交值并回到运行时已提交的姿态（新增必需端口方法 `WorkspacePreviewPort.authoredPose`）。
- 提交投影（`projectAuthoredPose / applyPreviewSession / projectWorkspacePoses`）在已提交姿态上保留之后仍待提交的值，较早的提交不会把滑块拉回；投影不再比较作者姿态数值本身，模型、工作区、加载代次与锁的检查保留。排队期间切换工作区时，修改提交到它所属的工作区。
- 吸附动画每帧更新作者姿态（作为待提交值），结束时提交一次并等待落地后再继续画布操作；拖动滑块时与提交相同地求解骨骼约束。
- `workspaceEditBusy = canvasEditBusy || poseCommitBusy` 取代原先各处对 `canvasEditBusy` 的读取：其他编辑、撤销/重做、保存仍等待姿态队列，避免从过期 state 开始。

回归：`WorkspacePreviewPortTest` 新增 5 项（立即显示且较早提交不回拉、松手后保持值、失败回滚、吸附动画写作者姿态并只提交一次、拖动时跟随骨骼约束）。本机 Linux / JDK 21 全量 `gradlew test`：213 个类、1051 项，0 失败、0 错误、19 跳过（不含 Cubism SDK 与桌面窗口）；之后的两处小修正（项目重开后不提示过期姿态、后端不可用时撤回待提交值）复跑了姿态相关的 6 个测试类；包含这两处修正的 `a3fc546` 随后在 PR CI 的 Ubuntu 与 Windows 全量中通过。GUI 行为仍需桌面手动确认。

之后桌面检查仍发现两处不同步，均为本 PR 相对 master 的回退，已修复：

- 拖动滑块时编辑画布不动：拖动中的值只存在不可观察的临时变量里，只有 SDK 预览每帧读取；编辑画布、软件预览、辅助线与物理面板要等松手提交才更新。现在每个采样立即写入作者姿态（作为该次拖动的待提交值），松手只提交一次，取消则撤回并回到已提交姿态。
- 播放时滑条不动：时间线播放由运行时播放会话驱动，帧值只进入预览帧，滑条读不到；没有可见预览画布时播放也不推进；滑条只在聚焦预览画布时显示实时值。现在 `livePose` 由求值帧部分（动画、指针视线、暂停物理）与时间线部分（所开动作的曲线参数在播放头处的值）合成，滑条无论聚焦哪个画布都读它；有动作打开时没有预览画布也推进时钟；打开/关闭动作时同步运行时播放会话，关闭后滑条回到作者姿态。软件预览停止动画后清除最后一帧，隐藏全部预览时同样清除。

回归：`WorkspacePoseTest` 改写拖动用例（采样立即进入作者姿态与编辑画布、松手前不提交），新增取消拖动还原、无预览画布时播放头与播放驱动滑条和编辑画布、关闭动作后恢复。本机 Linux / JDK 21 全量：213 个类、1053 项，0 失败、19 跳过。

随后按同类问题排查并修复（`0a83d43` 之后）：

- 编辑画布不再读运行时播放帧（`processFrameValues` 只在时钟取帧时刷新，会滞后于作者姿态），而与滑条读同一个 `shownPose`：作者姿态（含待提交值）、`livePose`、正在拖动的值；编辑画布订阅 `livePose`，动画与时间线逐帧重绘。
- 参数面板的“物理驱动”标记与物理面板摆锤输入不再依赖聚焦的画布或作者模式下丢掉时间线曲线值。
- GUI 自己的修改落地时若界面已显示同一姿态，不再重置物理与指针动态，摆动不会在松手后从静止重新开始。
- 每次作者姿态提交后重置播放会话时，保留打开的动作及其播放头（暂停），曲线值不再在改动任一滑条后从画布与滑条上消失。

测试审查：按“断言不变/无断言/旧接口”检查测试，仅 `WorkspacePoseTest` 的旧拖动用例与本 PR 的 `WorkspacePreviewPortTest` 一处断言固定了错误行为，已改写；无断言的 4 项均调用带断言的辅助函数，`tools/` 下的开发工具默认跳过。删除依赖真实时间、按睡眠帧数等待摆锤静止的 `LivePoseTest.aPausedPreviewSwingsPhysicsFromTheParametersAndComesToRest`。新增：聚焦编辑画布时滑条与编辑画布跟随预览动画、软件预览停止动画后滑条回到作者姿态、动作打开时改其他滑条后曲线值仍保留。本机 Linux / JDK 21 全量：213 个类、1054 项，0 失败、19 跳过。

文档核对（`a3fc546` 之后）：用测试导出的注册表核对全部文档中的工具名，170 项均出现在 MCP_AUTHORING（补上 `warp_get_controls`）；改正 `revision.list` → `history_list`、`model_preset` → `model_apply_preset`、规格中的 `inspect` → `workspace_inspect`，并按当前代码更新 AGENT_DESIGN 与 UI/MCP 对照中关于 GUI 姿态提交和迁移状态的描述。

### 此前通用阶段的历史证据

- 独立网格设置更新、全局调整及重置已统一普通、分区、新建和导入 CMO3 网格的有序替换。新候选固定原生成输入、身份及网格设置，保留早期关键形/创建/拓扑日志，追加替换并迁移关键形、混合形、路径、顶点组、Glue 和已有模拟偏移。导入的画布基形首次替换显式保存中性父级坐标，重基后再插值；无关贴图地址不变。隐藏期间同样重生，恢复取得新拓扑；拓扑变粗后重置设置不恢复已丢失的高频偏移。当前不包括同时改变分类或生成模式的迁移。

- 普通已编辑源图的分区开始支持绑定迁移：原三角形沿 UV 多边形切分，保留重心来源；连通块保留原顶点和 Glue 顺序。新内部日志复制实际父级、关键形、通道、混合形、路径和权重；模拟目标、已有烘焙与 Glue 角色同步迁移。GUI 已移除已有编辑的统一拒绝。含绑定分区记录的工程在单层/全局网格更新及重置时已保存有序替换并迁移绑定、模拟偏移，删除恢复沿用新拓扑；多边形实时 Glue、导入模型分区及完整源图/分类/生成模式迁移仍待完成，当前不能声称完整分区迁移完成。共用内部候选计算已移到后台，UI 响应和提交前取消有实际回归。

- GUI 图片拖动、确认和取消已进入 `WorkspaceImagePlacementSession`；两项公开 `layer_set_bounds/layer_cancel_import` 为后台单项及原子批量成员。预览只投影模型，保存不捕获预览；确认按进程拥有的队列提交，仅推进本会话成功 state，取消 UI 等待不停止工作，外部编辑及重开拒绝旧会话。原像素保存为可选 `placementSource`，重复缩放不累积失真；保留网格 ID、实际父级及原生成帧，已有绘画/专属绑定/Glue/遮罩仍拒绝单层定位。旧基线和最后源图取消使用软删除，重复取消无变化。自建父级的网格重生与首次放置共用核心中性坐标转换，支持其他自建父级图层未重生的情况。

- 自建父变形器及导入模型的素材追加与图片导入共用 `WorkspaceLayerInsertionEdits.materialize`，中性几何转换读取实际父级；重新配准原位替换创建记录，保留对象 ID、父级及日志位置，透明占位不重复。后续专属绑定和遮罩依赖仍拒绝单层定位；归一化在像素分配前检查坐标及 16MP 预算，逐行检查原协程取消。单项/批量、保存重开、父级运动及导出读回已补验；连续放置已使用独立应用会话。

- GUI 文件图片追加与 `layer_import_images` 共用 `WorkspaceImageLayerCommands`，PNG、无损 WebP、TIFF 和 BMP 整批一次历史提交。文件及像素预算先于解码，裁剪和缩放检查取消；错误文件、冲突及投影拒绝不发布前缀。普通模型保留生成范围和网格身份；导入模型及自建父变形器保存显式创建几何，新网格继承实际父级运动。保存重开不依赖原文件。三项素材图层编辑同时新增原子批量与后台单项支持。当前公开操作 117 项、后台 47 项、批量 59 项；连续放置已使用共享定位/取消命令。

- `WorkspaceAssetSessions` 已接管素材准备、PNG 导入、配准和重处理；应用工作流在捕获的文档/模型及清单上准备私有候选，锁外压缩后使用辅助 CAS 发布文件及清单。失败、提交前取消和冲突不发布前缀，实际修改推进持久 state 而保留 Rig revision/历史；重复导入同内容及映射无变化。四项公开写入返回进程任务，成功后立即保留完整终态及可重复图片。素材查询冻结成员，保存排除后来素材，旧 v1 打开固定已有清单，新工程从空清单开始；头部像素预算检查先于解码，去底及文件阶段检查原协程取消。素材图层添加、配准定位与确认已由独立应用命令共用单项/批量候选；自建父级及导入模型的素材追加/重新配准已共用可重放网格创建；完整分类迁移仍待继续。

- 文档、DTO、源图/素材/空间数据、历史存储、设置编解码与 revision 计算已移到中立 `project` 包。
- 行为契约移到 `application/WorkspacePorts.kt`，按查询、源图、设置、参数、Rig、骨架、动作、预览、物理、模拟、摆动、渲染、素材、输出、历史及任务记录拆分；`WorkspaceBackend` 只组合必需接口，没有默认的不可用或空结果实现。文档批量、辅助数据和工程生命周期也成为必需能力，注册不再按可选下转型静默省略。架构检查同时禁止 `project` 反向依赖 `application`。
- 内部命令注册拆成 14 组按能力接口调用的消费者；源图、素材及观察注册各接收自己的窄接口，请求执行器只依赖状态读取。GUI 摆动、模拟、文档及 Rig 提交也接收相应接口。源图和观察目录可以仅绑定这两项能力运行，不要求完整桌面后端。
- GUI 保存和打开通过 `WorkspaceProjectLifecycle` 调用，与 MCP 共用桌面适配器持有的工程控制器；移除这些入口对 `DesktopWorkspace` 的下转型。GUI 在入口确认后明确放弃未保存修改，内部操作携带可信用户身份和起始状态；MCP 继续默认拒绝未保存切换。
- `ProjectRepository` 使用捕获的数据保存，独立读取归档；GUI 的 `ProjectController` 与状态编解码位于 `ui/state`。`project` 和 `application` 的架构测试禁止导入 GUI、Agent、Compose、MCP、Ktor。
- 生成图片工程保存根历史源图为 `source/original.psd`；保存后的像素、历史与辅助信息可脱离 GUI 往返。显式空骨架和旧格式缺失骨架分别处理，防止新工程重开后 revision 改变。
- `WorkspaceRuntime` 已成为桌面后端的已提交文档、模型及历史树所有者。PSD/图片创建、工程打开、GUI 编辑完成、MCP 文档编辑、历史切换和保存捕获已接入；查询不再初始化历史或触发自动恢复。GUI 仍持有手势草稿及辅助状态，全部命令/辅助状态统一尚未完成。
- `WorkspacePreviewBuilder` 在应用层执行重建与有序重放，不依赖 Compose。编辑先构建候选，运行时检查不透明内部状态，再验证界面投影并提交一个历史节点；投影拒绝、重建失败或提交前取消均保留原文档与历史。
- `WorkspaceReadPort.captureQueries` / `WorkspaceReadSession` 已将模型、源图、设置、历史、网格、骨架/动作和物理/模拟等纯查询移出桌面适配器。运行时原子捕获 aggregate 与历史；`workspace_inspect` 及骨架/动作复合读取只用一个独立会话，不混入并发提交或重开后的数据。参数值/锁来自已提交工作区姿态，求值帧与未提交 GUI 值不改变查询结果。软删除详情从源图恢复，保留旧拆分组件 ID/分类/透明边界，不使用桌面缓存；发现分页新增 total，并防止 offset 加法溢出。
- `WorkspacePreviewCommands` 已接管预览设置、重置和快照应用的合并与辅助 CAS，查询与修改共用旧姿态规范化；省略值、锁及快照未命中参数取已提交基线。逻辑无变化保留原记录、持久版本及 Rig 历史，但仍可校正界面并停止播放。GUI 完成姿态通过中立 `WorkspacePreviewPort` 交付当前工作区冻结值，复制工作区登记复制姿态；保存逐工作区投影捕获的持久值/锁，排除临时值及求值缓存，保留 v1 格式。GUI 手势准备、IK、自动关键帧及播放/跟踪尚未全部迁移。
- 观察使用同一次捕获的文档、模型和 revision；图片资源保存使用捕获时的工程及存储，不再写入 GUI 日志或发布未提交草稿。物理查询与源图修改解析也读取运行时模型。
- 绘制顺序已进入中立生成设置，深度切分在重建和历史切换后保持前后关系。较早 v1 工程缺失该设置时，从既有保存投影提供生成回退，不重写历史文档或节点。
- `WorkspaceOperationRegistry` 保存严格 schema、说明与操作元数据；全部公开操作直接绑定该注册表，旧 MCP 分支工具注册、缓存及处理器转调已删除。
- 全部 117 项公开操作必须声明严格输出契约，不能通过可空 resultSchema 跳过；业务结果在去重内部校验，失败为 `output_contract`，原请求重试不重复修改。47 项后台操作必须另声明终态业务 schema，非后台能力详情排斥该字段。共享任务启动器、正常完成和持久完成标记使用同一校验，任务状态分支限制 result/error 与 terminal 的组合，列表带 total。能力详情使用只解析本地的 `$defs/$ref` 描述任意深度 schema，包装保留根定义。MCP JSON 序列化边界保留 SDK 类型遗漏的顶层约束，实际 HTTP 比较完整输入/输出 schema 并校验返回包装；大图片返回先扫描字节，避免无关的二次 JSON 解析。inspect 用 scope 区分所有查询分支和空分页；设置读取补齐保存值的默认项并包含文档网格覆盖，旧字段仍保留在工程文档。素材详情组装下沉应用工作流，输出原始加工信息、参考、配准列表与方向诊断；模拟/预设输出覆盖烘焙质量、错误、陈旧状态和衣物诊断，不暴露顶点与关键形数组。
- 公开接口已拆为 `domain_operation`，例如 `skeleton_put`、`motion_set_key`、`source_paint_pencil`。`workspace_list_operations` 分页发现能力，`workspace_get_operation` 返回精确 schema 与字段说明。
- 工程 `project_open` / `project_save` / `project_save_as` 已接入进程任务：绝对路径、未保存默认拒绝/显式放弃、空工作区打开、原历史重开与新加载状态；读取前捕获状态，安装时再做 CAS。归档替换前检查取消，成功替换后记录持久结果，晚到的取消不会把已保存任务标为回滚。具体回归验证见下文。
- PSD 导入与图片创建已进入独立应用层 `WorkspaceSourceImporter`，GUI 分析与 MCP 共用源图能力接口；GUI 不再直接运行分析流水线或下转型通知后端。公开 `project_import_psd/project_create_artwork` 返回进程任务，支持读取/准备/重建/安装进度、空工作区创建、已加载工程切换与显式放弃。新工程清除旧 Rig/journal、图层/网格/绘制顺序覆盖、快照等辅助数据，重置各工作区的姿态与画布会话，使用新 ID 和加载代次；候选读取前后均保留起始状态，投影/并发/提交前取消不发布新工程，迟到取消保留已安装结果。
- CMO3 新建和替换已进入独立应用层 `WorkspaceCmo3Importer`，不依赖 GUI 或 MCP。两种适配器共用源图接口；MCP `project_import_cmo3` 返回进程任务并报告读取、候选准备、重建和提交进度。新建默认拒绝未保存切换、支持显式丢弃；替换保留未出现对象，文档与姿态重置同一次 CAS，保留快照及历史注释，并只追加一个历史节点。提交前取消或投影/并发拒绝保留原工程，提交后的迟到取消保留 completed 和实际新工程结果。
- `WorkspaceJobs` 已接入生产导出；应用持有实例，MCP 会话共用，支持查询/等待/取消/分页。相同请求 ID 和相同参数只创建一个导出任务。
- 所有公开修改操作共用 `request_id` 与进程内去重，工作区修改还共用 `project_id` 和不透明 `state`。注册表发布与执行同一上下文 schema；作者由适配器传入，工作区提交再次核对期望。重复调用共享一次执行及原成功/失败结果，等待断线不取消执行。文档提交返回自己的状态与历史节点，重开同一节点后的旧令牌会被拒绝。
- 即时调用与后台任务的错误分类已收敛到应用层 `WorkspaceFailure`；任务保留结构化 `error.code/message` 以及适用的字段、批量索引和冲突上下文。未保存切换与忙碌具有 `unsaved_changes/workspace_busy` 专用错误码，文件/权限问题为 `io_error/permission_denied`；取消仍是任务状态，已提交后的异常保留成功结果。
- 所有公开工具统一 `request` 包装，发布保留实际 `oneOf` / `const` 与字段说明；不再展平或裁剪。外层和业务对象均拒绝未知字段，数值开区间同样执行校验。
- 源图、素材配准、观察、路径和参数命令解析位于应用层；模型与图片直接返回中立结果。共享渲染、信息叠加和旋转引导坐标框架位于 `core`，GUI 与 MCP 共用，无 Compose 依赖。
- 骨架/动作编辑、源图创建/拆分、素材/抠图规则已下沉应用层。共享 `core/RasterPaintEngine` 不依赖 Compose；GUI 仅适配颜色和工具枚举，MCP 已接入铅笔及只读取色。源图创建使用 GUI 同一图片解码器，支持 PNG/WebP/TIFF/BMP。
- 实际文档批量 `workspace_apply_edits` 已接入共享 `WorkspaceDocumentCommands`：59 项纯候选命令覆盖设置/分类/网格及图层软删除/恢复、参数、Rig/关键形/结构/外观/顶点组、绘画、骨架、动作、画布/Glue/拓扑、路径、摇摆、物理配置/拟合及模拟/预设。单项入口共用候选函数；成员继承精确业务 schema，发现标注 `batchable`，支持顺序依赖、一个历史节点、失败/取消/冲突回滚、无变化与请求重试，生成对象返回句柄。成员重建失败也附错误索引。GUI 参数对话框及画布 journal 已进入同一命令边界，GUI 的其余业务准备及文档编辑还未全部迁移。批量后台重建已接入进程任务，逐项报告准备进度；CAS 后在应用命令内立即保留完整提交结果，迟到取消或刷新异常保留成功。共享任务启动器保持起始期望和可信作者，重试/断线取回原任务。
- 全局/逐层网格默认值及最小贴图尺寸计算下沉 `core/GenerationDefaults`，GUI 和应用候选共用。与已有定义相同的参数更新、与隐式默认相同的网格字段不创建覆盖或历史。
- 模拟创建/局部更新、删除、烘焙、清除烘焙及模型预设的准备已从桌面适配器移到 `WorkspaceSimulationEdits`，GUI/MCP 单项和原子批量共用。桌面只观察进度及取消；同步求解检查起始协程，即使自动烘焙核心返回失败诊断，取消仍回滚整批。预设恢复传统头发新增 `sway:false`，覆盖 GUI 的关闭摆动选项。烘焙提交按已有 v1 精度规范化偏移，防止内存关键形与保存重开不一致。五项单项操作已接入同一进程任务执行器；`WorkspaceSimulationCommands` 在 CAS 后立即保留完整结果和诊断，迟到取消及刷新异常保持成功。后台文档成员仍可在批量中直接准备候选。其他单项重建任务化仍待继续。
- `physics_simulate/simulation_simulate` 已成为只读进程任务，应用层 `WorkspaceSamplingJobs` 在调度前捕获一个独立查询会话并核对工程和状态。后台查询同样要求请求 ID、工程 ID 与 state 并使用进程去重；终态采样诊断携带捕获时的 project_id/state/revision，外部修改或重开不混入结果。校准、静置及实际采样帧均支持进度与取消，取消等待或断线不停止任务，不推进持久版本和历史。
- 物理 put/delete/config/import/fit 五项单项已接入进程任务，业务编排下沉 `WorkspacePhysicsCommands`，导入和拟合候选下沉 `WorkspacePhysicsEdits`。读取文件前核对原状态，读取后、候选重建及提交前继续检查取消和冲突；静置及逐帧拟合响应原协程取消。CAS 后立即保留完整终态，迟到取消或界面刷新异常不丢失成功；公开归一化保留此前遗漏的导入 ID、禁用组、缺失参数与 FPS。拟合也加入原子批量，共用前序候选并报告成员进度；已达到同一倍率的请求不追加历史，完全没有响应仍拒绝。物理面板手势等其余 GUI 草稿业务仍待迁移。
- 观察业务下沉独立 `WorkspaceObservationSession`：桌面只捕获模型/历史/可见图层/资源目标并提供原生求值和持久化回调。历史对比使用捕获的历史树，动作验证、导出动作生成、原生采样和拼图均在应用层组织。`view_sample_motion` 已成为只读进程任务，启动前核对工程与 state，终态保留捕获身份与 PNG，跨连接重试及 job_get/job_wait 可重复取图。原生排队/逐帧求值均响应取消，完成或取消后销毁临时模型和文件，不修改 GUI 实时物理/动画状态。
- 静态源图、上下文与模型准备下沉独立 `WorkspaceRenderSession`，历史及动作图像复用同一实现。五项静态观察操作各捕获一次会话；多姿态不再逐图读取桌面，也不再用实时 revision 拒绝旧快照。会话复制可见图层集合，保留透明度、层序与旧拆分组件的父级可见性覆盖；外部编辑、工程替换或运行时关闭后仍可渲染原快照，图片和空间引用保存到原工程存储。桌面只提供捕获和资源回调。
- 内部 typed 参数/模型编辑也通过共享候选/重建/CAS 编排；参数对话框组合定义、文件夹移动和参数关键点，一次提交一个用户历史节点，失败无前缀。画布手势、对话框、内部命令和 DTO 统一使用不透明 `state`，历史节点别名及内部请求改名转译已移除。GUI 摆动会话保留起始状态，模拟编辑携带可信用户作者；离线烘焙沿用准备前的状态并在原烘焙协程内提交，旧结果不能通过重新取令牌绕过冲突。
- 新参数定义写入有序 journal，删除在此前关键形与最后默认值更新之后重放。旧 v1 静态参数字段、快照 revision 与节点 ID 保持不变。修复纯通道或几何相同的通道修改被当作无变化而丢弃；透明度/颜色可保存，真正相同的重复捕获不追加历史。
- 保存捕获中的候选重建可挂起，在历史锁之外准备。GUI 字段完成已通过 `WorkspaceEditorDraftPort` 进入应用层 `WorkspaceDraftQueue`，后台调用运行时候选/重建/CAS，不再同步阻塞界面；开始编辑时捕获预期状态，连续排队的完成只承接本队列提交，外部编辑和重开仍拒绝旧状态。旧完成不覆盖后续已捕获的可见草稿；保存先等待队列并保留失败，显式放弃可以打开另一个工程。GUI 绘画和深度拆分等待实际完成后恢复画笔。GUI 预览草稿生成、未迁移业务及辅助状态仍须继续下沉。
- 参数快照和历史注释的 DTO / v1 编解码移到 `project`，编辑逻辑移到 `application`；`WorkspaceAuxiliaryPort` 由 GUI 与 MCP 共用，运行时 CAS 与投影校验后推进持久 state，不修改 Rig 历史。公开逐操作工具支持快照分页/详情/创建/改名覆盖/删除/应用、历史注释读写/删除；保存与重开保留原 ID、编号和数据。
- MCP 显式 pose 修改通过运行时辅助数据提交，返回自己的状态。GUI 参数值、锁、重置和插值结束会同步其持久版本；播放/物理求值帧与快照悬停排除。GUI/MCP 应用快照共用命令，保留锁、忽略删除轴并钳制旧范围，不受自动打关键帧开关影响。更广的 GUI 会话控制与全部 pose 所有权仍须统一。
- 设置编辑与嵌套合并下沉应用层，补齐全局填充算法、边界对角线抑制、自定义嘴型曲线、嘴型颜色及可空导出比例。
- 桌面适配器已移到 `ui/state/DesktopWorkspace`。迟到的保存完成回调和工程加载提交检查加载代次，避免覆盖重开后的工程。

- GUI 捕获画布 RGBA 和手势开始时的工程状态，经源图接口提交到共享 WorkspaceRasterEdits；六种 MCP 绘画与批量成员使用同一文档候选。裁剪、重打包及网格准备在 core/RasterPaintCommit 完成，临时预览不作为持久状态。默认保留网格与已有绑定；rebuild_mesh:true 迁移拓扑，解除已有关键形、Warp 或 Glue 的绘画限制。清空保留图层、最后一层及绑定，深度前层只改像素并保留矩形。GUI 等待成功提交后恢复画笔，失败或外部状态变化不发布旧手势。

- 可重放的网格替换集中在 core/RasterMeshJournal，内部 canvas_mesh_rebuild 记录替换几何、源画布纹理坐标、旧顶点来源、Glue 映射及可选 neutral_bounds。GUI/MCP 绘画共用迁移，顶点数变化也保留普通关键形和混合形；路径重绑定、顶点组插值，Glue 连接重定向且重合连接按各侧最大权重合并。重放检查旧几何、父级及源图身份，支持旋转/缩放/重新裁剪/重打包的 UV 和源图局部 UV。共享文档候选保存网格设置重生后的路径、权重及编辑调整。分类/源图改变的完整迁移仍待完成。

- WorkspaceDocument.generationSource 保存原始生成形状，meshSource 保存显式重建/首次创建的目标输入及生成嘴唇的轮廓/颜色；使用与当前源图相同的图层格式和去重 PNG blob。保留网格绘画不重新生成嘴唇，顶点不变但派生贴图输入改变时仍提交。RigGenerationSource 从原始栅格生成基础 Rig，再重映射当前贴图；收紧裁剪时补透明覆盖，完全透明也不隐式改变基础拓扑。预览、路径规范化与导出共用入口，GUI 显式捕获/投影；缺省时旧 revision 和节点不增加字段。CMO3 绘画只更新已绘画网格的贴图，保留其他网格的原图集、UV 和材质元数据；复杂导入模型及完整源图/分类迁移仍待验收。

- core/RasterMeshCreation 的内部 canvas_mesh_create 固定新网格身份，保存源图与覆盖边界、原父级及部件、材质、几何、画布 UV、生成参数/关键形/通道及路径。普通透明图层和嘴部首次可见绘画自动创建；重放重新解析图集，清空后保留网格和透明纹理覆盖。新网格设置从保存的 meshSource 更新创建几何、退休旧拓扑迁移记录，重绑并持久化路径及权重。单项绘画与批量返回生成对象句柄，重试不重复提交。复杂父级/分类切换及导入无网格对象仍须继续迁移。

- 六种公开绘画单项接入进程任务，GUI 冻结像素与 MCP 手势经独立 `WorkspaceRasterCommands` 使用同一候选/重建/CAS；桌面仅负责投影、持久通知及刷新。`WorkspaceRasterWork` 为单项和批量传递原协程取消及进度，栅格转换、笔触、填充遍历、裁剪、图集和网格准备检查取消。批量直接执行候选并报告成员进度，不嵌套单项任务。应用命令 CAS 后立即保留完整终态，迟到取消和刷新异常保留成功、状态及生成句柄；旧状态在内部绘画字段解析前拒绝。当前公开操作 117 项、后台操作 47 项、批量 59 项。

- GUI 删除和恢复全部经 `WorkspaceSourcePort` 进入独立 `WorkspaceLayerCommands`，MCP 新增 `layer_restore`，两项单项均使用进程任务并成为原子批量成员。指定/全部恢复和重复无变化使用共享纯候选，提交前取消、冲突和投影拒绝保留原历史；CAS 后立即保留完整终态，迟到取消、刷新失败及跨连接重试保留成功。含创建记录的工程通过 `RigLayerDeletion` 完整重放后过滤删除图层及派生嘴唇，清理活动模型中的遮罩、Glue、路径及顶点组；保留源像素、创建 ID 和原历史供恢复。删除期间网格设置更新也保存隐藏网格的新创建几何及重绑路径/权重，贴图下限从完整输入计算。普通已编辑图层、深度前后层及导入 CMO3 的边界已补验；新成员候选固定生成范围与身份，旧文档不自动升级，见下文。

- 设置、图层分类和逐层网格进入独立 `WorkspaceGenerationCommands`，单项 GUI/MCP 与批量共用纯候选；三项公开单项任务化，分类省略字段在起始状态检查后的捕获模型中合并，注册不读取实时桌面快照。准备/重建检查原协程取消并报告进度，CAS 后立即保留精确业务终态；刷新异常、迟到取消及请求重试保留成功状态。

- GUI 直接分类、全局/单层网格、重置和临时网格预览确认经窄接口调用共享命令。连续拖动、分类文字输入保留实时草稿，在结束时提交一次；生成设置/分类/网格差异草稿由 `prepareDraft` 转成同一纯候选，复合差异顺序重建，混合草稿仍先规范化网格编辑。临时网格预览确认先恢复基线，再准备正式持久候选。其余业务草稿与复杂分类迁移仍待完成。

- 设置联动与作者姿态进入中立 `WorkspaceSettingsIntent`：一次解析完整 patch 并校验，meshOnly 变化未显式给出 generateDeformers 时联动为 `!meshOnly`（导入 CMO3 除外），生成动作子项开关变化未显式给出 exportMotions 时联动为子项是否全开；显式字段优先。关闭 meshOnly 以外的来源时释放其驱动的作者姿态：meshOnly 开启释放全部参数，基础动作/待机/眨眼关闭释放对应标准参数，物理关闭释放物理组输出；Nod/Shake 只播放临时帧，不改作者姿态。释放按真实 `Parameter.default`，逐工作区跳过该工作区自己的锁，并保留每个工作区的持久姿态记录。
- 文档与辅助数据共用私有候选：`WorkspaceRuntime.executeDraft` 让每个成员在同一草稿上更新文档、模型与辅助数据，最后一次 CAS 发布；`workspace_apply_edits` 的 settings_update 成员、单项 settings_update 及 GUI 设置开关都走它。批量中关闭再开启同一开关仍释放姿态，最终设置相同则只发布辅助数据而不产生历史节点；任一成员失败不发布前缀。GUI 的 meshOnly、动作子项和 generatePhysics 开关在无字段会话时经 `WorkspaceSettingsPort` 提交，提交锁内投影各工作区姿态；启动预设的两个动作开关在字段会话前作为一次意图提交。字段会话中的开关先更新本地草稿，并按顺序记录，提交草稿时经同一意图重放后再应用其余差异，一次 CAS（`1122a86`、`77270b9`）。
- raw/effective 策略集中在 `project/WorkspaceDocument.kt` 的 `WorkspaceSettingsPolicy`：文档只存原始设置，`rawConfig()` 读取原样值，`config()` 才套用生效规则（meshOnly 关闭变形器、动作与物理；显式 generateDeformers=false 与 exportMotions=false 生效；v1 桌面在全部生成动作关闭时写入的 exportMotions=false 仍按旧规则导出自定义动作）。PSD/素材创建与 CMO3 导入/替换改为保存原始设置，不再把生效值写回文档；GUI `buildConfig()` 与文档共用同一规则。

- 源图多边形与网格连通块拆分进入独立 `WorkspacePartitionCommands` 和共享 `WorkspacePartitionEdits`，两项单项为后台任务并支持原子批量；新 `source_get_components` 使用独立查询捕获，返回相同版本的组件数量、排序和中心。GUI 检测保留原状态，确认经窄接口调用；多层决策顺序重建后一次 USER 提交，辅助版本变化也拒绝旧对话框，旧 GUI 候选准备已移除。拆分保留原层并软删除，提前固定新源图/Drawable ID，继承分类、父级、可见性、网格及绘制顺序覆盖，保留生成输入与无关对象编辑。任务 CAS 后立即保存精确结果，迟到取消及刷新失败保留成功；批量返回新 `layer:<id>` 与生成对象句柄，后续成员可引用指定的新 ID。像素/组件检测及重建检查原协程取消。普通已编辑目标的绑定迁移已继续补齐，见本页当前进度；多边形实时 Glue、导入模型分区及完整源图/分类/生成模式迁移仍待完成。
- 请求 schema 新增实际执行的 `uniqueItems`，数值按值比较，嵌套对象忽略字段顺序，数组保留顺序；递归规范化后使用哈希集合，避免大量组件名称/ID 的两两比较。HTTP 能力与请求发布同一约束。组件名称/ID 不设低于 GUI 能力的额外数量上限，数量必须匹配当前检测结果。

- GUI 深度拆分与新 `source_split_depth` 经 `WorkspacePartitionCommands` 共用独立 `WorkspaceDepthSplitEdits` 候选；单项为进程任务，也可作为原子批量成员。GUI 保留菜单、多选及对话框打开时的完整状态，一次 USER 提交后才进入绘画；旧确认不会绕过辅助版本冲突。提前固定源图/网格/Glue ID，保留生成输入、已删除像素及所选网格运动，嘴部只新增所选网格；前层可独立绘画，方向 Glue 保持后层运动，两层使用固定绘制顺序。纯候选/重建/CAS 与任务完成标记已有回归；导入 CMO3 模型同样支持，GUI 前后层入口自 `6abe11c` 起与之一致。详细架构规则见 `CLAUDE.md`。

- 普通已编辑图层、深度前后层及导入 CMO3 的删除/恢复已补齐共享重放边界。普通工程首次实际修改成员，在新候选固定当前生成范围和所有源图/Drawable 身份，追加内部 `layer_membership` 标记；旧文档不自动升级。重建与导出先重放所有编辑再过滤活动对象，恢复保留关键形、Warp、路径、顶点组、遮罩和 Glue；删除期间网格设置仍保存隐藏网格的重绑结果。GUI、MCP 单项及批量共用应用候选；旧状态、准备取消、外部辅助 CAS 和投影拒绝不发布固定 ID 或标记。详细规则见 `CLAUDE.md`，工程仍为 v1。

- 独立 Warp 创建进入 `WorkspaceWarpCommands`，单项 `rig_create_warp` 是进程任务，也成为第 54 项原子批量成员。共同父级从捕获候选解析，后项可以引用此前新建的 Warp；支持可选固定 ID、划分和 `fit_local`，取消和冲突不发布前缀。内部 typed 创建与公开请求共用有序 `warp` 日志，新编辑不写旧静态字段，旧 v1 记录仍可读取。CAS 后立即记录精确终态和生成句柄，迟到取消或刷新异常保留成功。核心拟合同时迁移普通关键形和混合形，以叠加混合形的保守包络裁剪父格点，保留默认形参考及标量通道；路径、权重和 UV 不变。自由放置的 GUI Warp 仍使用画布命令。

## 通用网格设置重生阶段的验证

普通模型、首次无绑定分区后追加顶点编辑、新建网格和导入 CMO3 共用 `MaterializedMeshRebuild`。网格设置实际变更在新候选固定生成范围、源图及网格基线，按序追加 `canvas_mesh_rebuild`；早期创建与替换记录不改写。导入 CMO3 的基础顶点是画布基形，不能再次套父级变换；首次替换保存 `previous_parent_points`，普通/混合关键形先重基，再按中性父级网格插值，路径沿原绑定三角形转换。目标纹理来源使用重放模型的身份，生成纹理只提供画布地址。模拟偏移共用顶点来源并更新顶点数、保留旧指纹；完全透明图层保留拓扑。

新增四项独立应用回归覆盖普通/导入模型 × 普通/混合模拟、路径/权重/Glue、重复设置无变化、历史回退、归档重开、CMO3 多姿态读回与可见 PNG；无绑定分区后编辑、后项失败和重建取消；隐藏期间全局调整与可见时一致、恢复、逐层覆盖及重置；深度前层身份与方向 Glue。扩展生产 GUI/MCP 回归验证同一顶点编辑及网格设置得到相同几何和关键形。旧回归改为检查创建记录不变、追加替换后实际网格改变，继续验证删除/恢复与可见分支完全一致。

最终专项 28 项全部通过，日志为 `build/mesh-regeneration-final-targeted-test.log`；实际 GUI 专项日志为 `build/mesh-regeneration-gui-test.log`。Windows 全量 `gradlew.bat test --offline --rerun-tasks` 成功：888 项、878 通过、10 跳过、无失败或错误，173 份 XML 在 `build/mesh-regeneration-windows-results/`，日志 `build/mesh-regeneration-windows-full-test.log`。Ubuntu 24.04.5 LTS / OpenJDK 21.0.12.1 同源码全量成功：888 项、876 通过、12 跳过、无失败或错误，173 份 XML 在 `build/mesh-regeneration-ubuntu-results/`，日志 `build/mesh-regeneration-ubuntu-full-test.log`。Ubuntu 使用独立根文件系统、JDK 和显式 AWT headless，未验收桌面窗口启动。两端 819 份代码与文案资源 SHA-256 完全一致；888 个 Test 注解方法均返回 void。274 份修改或新增文本通过 LF/尾随空白检查，`git diff --check` 通过。

普通/导入 × 普通/混合模拟共四组图片，各平台重开前后逐字节一致，对应组的跨平台 PNG 同样一致；验证可见像素且已目视检查。图片位于 `build/mesh-regeneration-visual/` 和 `build/mesh-regeneration-ubuntu-visual/`。公开操作 117 项、后台 47 项、批量 59 项不变；机器证据 `build/mesh-regeneration-proof.json` 的 `overall_complete` 为 false。

本阶段仍不表示完整重构完成。多边形实时 Glue、导入模型分区、完整源图/分类/生成模式迁移、剩余 GUI 手势/IK/自动关键帧/播放跟踪及完整业务矩阵继续保留为待验收项。

## 此前分区网格设置重生阶段的验证

已有绑定分区通过 `MeshGenerationBaseline` 固定此前生成网格设置，后续由 `MaterializedMeshRebuild` 在实际父级中性坐标追加网格替换，保留早期日志及其几何指纹。单层更新、全局调整、重置和删除后恢复均迁移关键形、混合形、路径、顶点组、Glue 和已有模拟偏移；烘焙顶点数更新，旧输入指纹保留。旧分区只在新候选固定设置，历史节点不改写；透明源图保留原拓扑。多阶段重建的任务进度已划分区间，避免实际公开调用因进度倒退失败。

Windows 全量 `gradlew.bat test --offline --rerun-tasks` 成功：884 项、874 通过、10 跳过，无失败或错误，172 份 XML 在 `build/partition-remesh-windows-results/`，日志 `build/partition-remesh-windows-full-test.log`。Ubuntu 24.04.5 LTS / OpenJDK 21.0.12.1 同源码全量成功：884 项、872 通过、12 跳过，无失败或错误，XML 在 `build/partition-remesh-ubuntu-results/`，日志 `build/partition-remesh-ubuntu-full-test.log`。Ubuntu 使用独立根文件系统、JDK 及显式 AWT headless，未验收桌面窗口启动。两端 818 份代码及文案资源 SHA-256 完全一致；884 个 Test 注解方法均返回 void。273 份修改或新增文本通过 LF/尾随空白检查，`git diff --check` 通过。

38 项专项通过，日志 `build/partition-remesh-targeted-test.log`。新增回归覆盖连续全局/单层调整、重置、隐藏网格重生和恢复、旧分区候选升级及取消，扩展普通/混合模拟偏移、实际自建父级、顶点组和 Glue 索引迁移、无变化历史与独立重放。生产 GUI/MCP 集成覆盖相同网格设置的几何与路径、重生后再次分区、严格任务终态/重试、带绑定的原子批量及失败无前缀、原历史、归档保存重开及 CMO3 多姿态读回。

图片位于 `build/partition-remesh-visual/` 和 `build/partition-remesh-ubuntu-visual/`，已目视检查；各平台重开前后 PNG 分别逐字节一致，且验证可见像素。跨平台有 109 处边缘像素差异，最大 alpha 及预乘 RGB 差值均为 1；不声明跨平台 PNG 完全一致。公开操作 117 项、后台 47 项、批量 59 项不变；机器证据 `build/partition-remesh-proof.json` 的 `overall_complete` 为 false。多边形实时 Glue、导入模型、完整源图/分类/生成模式迁移、其他生成路径中的顶点编辑迁移、剩余 GUI 手势/IK/自动关键帧/播放跟踪及完整业务矩阵仍须继续。

## 此前已编辑源图分区阶段的验证

最终源码的 Windows 全量 `gradlew.bat test --offline --rerun-tasks` 成功：882 项、872 通过、10 跳过，无失败或错误，172 份 XML 在 `build/bound-partition-windows-results/`，日志 `build/bound-partition-windows-full-test.log`。Ubuntu 24.04.5 LTS / OpenJDK 21.0.12.1 同源码全量成功：882 项、870 通过、12 跳过，无失败或错误，XML 在 `build/bound-partition-ubuntu-results/`，日志 `build/bound-partition-ubuntu-full-test.log`。Ubuntu 使用独立根文件系统、JDK 及显式 AWT headless，未验收桌面窗口启动。两端 816 份 Kotlin/Gradle Kotlin/文案资源 SHA-256 完全一致；882 个带 Test 注解的方法均返回 void。

新增 4 项几何回归、2 项分区应用回归及 1 项共享候选线程回归，并扩展生产 GUI/MCP 集成；25 项专项全部通过，日志 `build/bound-partition-targeted-test.log`。几何覆盖凹路径、交叉路径、原三角形面积守恒、共享切边焊接、顶点重心来源、未渲染的 Glue 顶点、空分区及取消。应用覆盖自建父级后普通关键形/通道/顶点权重与两种模拟写入方式的迁移及独立重放；模拟目标、烘焙顶点数、偏移、连接角色保持有效，历史回退恢复原目标。连通块保留逐连接求值顺序，包括不同分区交替拉动同一外部顶点的情况；多边形实时 Glue 和分区后网格设置拒绝不会发布候选或历史。共用内部候选在后台计算，单线程 UI 执行器保持响应，提交回调回到调用者线程，取消阻塞准备不发布修改。

生产集成在已有源图通道关键形和路径之后使用 GUI 连通块对话框与 MCP 后台任务，验证稳定几何/身份、请求去重、原历史切换、再次多边形分区、归档保存重开及 CMO3 多姿态几何/通道读回。增加可见像素断言，避免空图被误判为重开一致。两平台的重开前后四张 PNG 字节一致，位于 `build/bound-source-partition-visual/` 和 `build/bound-partition-ubuntu-visual/`，已目视检查。270 份修改或新增文本通过 LF/尾随空白检查，`git diff --check` 通过。公开操作 117 项、后台 47 项、批量 59 项不变；机器证据 `build/bound-partition-proof.json` 的 `overall_complete` 为 false。多边形实时 Glue、导入模型、分区后的网格设置重生、完整源图/分类迁移、剩余 GUI 手势/IK/自动关键帧/播放跟踪命令及完整 UI/MCP 业务矩阵仍须继续。

## 图片连续放置阶段的验证

最终源码的 Windows 全量重新执行 `gradlew.bat test --offline --rerun-tasks` 成功：875 项、865 通过、10 跳过，无失败或错误；日志 `build/image-placement-windows-full-test.log`，171 份 XML 在 `build/image-placement-windows-results/`。Ubuntu 24.04.5 LTS / OpenJDK 21.0.12.1 同源码全量同样成功：875 项、863 通过、12 跳过，无失败或错误；日志 `build/image-placement-ubuntu-full-test.log`，XML 在 `build/image-placement-ubuntu-results/`。Ubuntu 使用独立根文件系统及 JDK、显式 AWT headless，未包含桌面窗口启动验收。两端 813 份 Kotlin/Gradle Kotlin/文案资源 SHA-256 完全一致；875 个 Test 注解方法均返回 void。

新增 11 项独立应用回归与 1 项生产桌面/公开操作注册表集成，并扩展自建父级图片导入回归：重复缩放读取原像素，反向放置恢复 RGBA，原对象在三个参数姿态下不变；整批取消、重复无变化与撤销；错误批量后项、投影拒绝及旧状态不发布前缀；v1 可选原像素、旧 revision、原生成帧及删除文件后的归档重开；后续绑定、静态骨架/模拟及尺寸预算拒绝；迟到预览不能覆盖确认；会话仅承接自身成功状态，外部辅助修改及同工程重开拒绝旧操作；失败重试和取消 UI 等待不停止进程提交。两项公开任务在 CAS 后取消及刷新异常中保留成功终态。取消的批量结果包含移除的图层句柄。自建父级定位后的全局与单层网格重生保持身份、中性位置及父级运动，其他自建父级图层不用同步重生。36 项专项通过，日志 `build/image-placement-targeted-test.log`；自建父级专项日志 `build/image-placement-parent-test.log`。

集成覆盖 GUI 预览期间保存排除候选、USER 确认、历史切换释放面板和旧会话、AGENT 后台定位/取消的严格终态与请求去重、普通/导入模型删除原图片后的归档重开、CMO3 身份与中性几何读回、取消后的原图恢复及再次重开。普通与导入模型的保存前/重开后 PNG 各自逐字节一致，两端对应 PNG 也一致，位于 `build/image-placement-visual/` 和 `build/image-placement-ubuntu-visual/`，已目视检查。267 份修改或新增文本通过 LF/尾随空白检查，`git diff --check` 通过。当前公开操作 117 项、后台 47 项、批量 59 项；机器证据 `build/image-placement-proof.json` 的 `overall_complete` 为 false。复杂源图/分类迁移、其余 GUI 领域命令及完整 UI/MCP 业务矩阵仍须继续。

## 此前素材复杂父级阶段的验证

Windows 全量重新执行 `gradlew.bat test --offline --rerun-tasks` 成功：863 项、853 通过、10 跳过，无失败或错误；日志 `build/asset-parent-windows-full-test.log`，169 份 XML 在 `build/asset-parent-windows-results/`。Ubuntu 24.04.5 LTS / OpenJDK 21.0.12.1 同源码全量同样成功：863 项、851 通过、12 跳过，无失败或错误；日志 `build/asset-parent-ubuntu-full-test.log`，XML 在 `build/asset-parent-ubuntu-results/`。Ubuntu 使用独立根文件系统及 JDK、显式 AWT headless，不包含桌面窗口启动验收。两端 807 份 Kotlin/Gradle Kotlin/文案资源 SHA-256 完全一致；863 个 Test 注解方法均返回 void。

新增 6 项独立应用回归与 1 项生产桌面/公开操作注册表集成：自建父级下重复配准保留 ID 和继承运动，不重复创建网格或透明占位；导入模型追加素材后重新定位、归档重开；批量先建父级再添加素材使用前序候选，错误后项不发布前缀；后续画布 Warp 绑定阻止单层几何替换；旧超大素材在归一化分配前拒绝；归一化取消不发布像素或创建记录。集成覆盖 GUI 适配器 USER 作者、公开任务 AGENT 作者、严格终态及请求去重、普通/导入模型的定位确认、删除原图片后的归档重开、CMO3 导出读回的身份/父级/中性几何。自建父级归档和 CMO3 导出在三个参数姿态下保持全部网格运动。21 项专项全部通过，日志 `build/asset-parent-final-targeted-test.log`。

普通与导入模型的保存前/重开后 PNG 各自逐字节相同，两端对应 PNG 也相同，位于 `build/asset-parent-visual/` 和 `build/asset-parent-ubuntu-visual/`，已目视检查。261 份修改或新增文本通过 LF/尾随空白检查，`git diff --check` 通过。公开操作仍为 115 项、后台 45 项、批量 57 项；机器证据 `build/asset-parent-proof.json` 的 `overall_complete` 为 false。连续图片放置/取消、复杂源图/分类迁移、其余 GUI 领域命令和完整 UI/MCP 矩阵仍须继续。

## 此前图片追加与素材图层命令阶段的验证

Windows 重新执行全部任务 `gradlew.bat test --offline --rerun-tasks` 成功：856 项、846 通过、10 跳过、无失败或错误；日志为 `build/image-layer-windows-full-test.log`，168 份 XML 在 `build/image-layer-windows-results/`。Ubuntu 24.04.5 LTS / OpenJDK 21.0.12.1 同源码全量也成功：856 项、844 通过、12 跳过、无失败或错误；日志为 `build/image-layer-ubuntu-full-test.log`，XML 在 `build/image-layer-ubuntu-results/`。Ubuntu 使用独立根文件系统及 JDK、显式 AWT headless，尚未验证桌面窗口启动。两端 806 份 Kotlin/Gradle Kotlin/文案资源的 SHA-256 完全一致，856 个 Test 注解方法均返回 void。

本阶段新增 16 项回归：素材图层的固定身份、已有运动保持、配准绝对位置、确认无变化、原子批量错误后项回滚、投影拒绝/旧状态、迟到取消及归档重放；PNG/BMP 整批导入、重复名称、头部预算/全透明图片拒绝、缺失文件回滚、取消后的完成结果、删除输入文件后重开；导入模型及自建父变形器的网格创建、继承父级运动与独立重放；GUI 源图端口的可信起始状态/USER 作者；实际 GUI/MCP 的用户/Agent 历史作者、任务契约/去重、普通与导入模型的归档、CMO3 导出读回及渲染。首次 Ubuntu 完整编译发现测试宿主缺少新增端口实现，已补齐后重新验证两端全部任务；不以先前增量构建作为最终证据。

普通模型与导入模型各自的保存前/重开后 PNG 逐字节相同，两端对应图片亦相同；图片在 `build/image-layer-visual/` 和 `build/image-layer-ubuntu-visual/`，已目视检查。当前公开 115 项、后台 45 项、批量 57 项。机器可读证据为 `build/image-layer-proof.json`，其中 `overall_complete` 为 false；连续放置/取消、复杂源图/分类迁移及完整 UI/MCP 业务矩阵仍须继续。

## 此前素材辅助事务阶段的验证

Windows 全量 `gradlew.bat test --offline` 成功：840 项，830 项通过、10 项按既有条件跳过，无失败或错误；日志为 `build/asset-session-windows-full-test.log`，165 份 XML 在 `build/asset-session-windows-results/`。Ubuntu 24.04.5 LTS / OpenJDK 21.0.12.1 同源码全量 `gradlew test --offline --rerun-tasks` 成功：840 项，828 项通过、12 项跳过，无失败或错误；日志为 `build/asset-session-ubuntu-full-test.log`，XML 在 `build/asset-session-ubuntu-results/`。Ubuntu 使用 WSL 中的 Ubuntu 根文件系统和 JDK，显式 AWT headless；这验证用户环境和协议/业务测试，不代表 Ubuntu 桌面窗口启动。四项关键实现的源码 SHA-256 与 Windows 一致。

新增 12 项独立应用回归：四项写入只推进辅助状态，重复导入无变化；配准记录创建后栅格预览失败不留下文件；投影拒绝、准备后并发修改、提交前取消均保留原素材及历史；同工程重开使旧请求先于文件解析被拒绝；文件发布中途取消清除已发布前缀，清理拒绝工程之外的路径；迟到取消保留完整任务结果及可重复图片；旧/新清单查询排除后来配准；保存排除捕获之后的新参考；原始 PNG、处理像素和历史往返一致；PNG 巨大头部先于像素分配拒绝，去底循环检查取消；清单拒绝缺失/异工程成员及重复 ID，旧目录副本迁移保留 ID 并只重绑副本工程身份。素材、导入、归档和契约的 33 项专项全部通过（`build/asset-session-targeted-final-test.log`）；新增文件发布取消用例通过两端全量。

真实桌面/MCP 回归验证四项任务的严格启动/终态 schema、业务状态与原请求去重，参考/配准/检查/试拼/重处理图片，保存重开后的原始图片、配准详情与原历史；工程重开使用新加载状态。保存前及重开后的素材 PNG 在 Windows 和 Ubuntu 逐字节相同，位于 `build/asset-session-visual/` 与 `build/asset-session-ubuntu-visual/`，已目视检查；这些素材辅助操作不修改 Rig，完整导出矩阵仍在后续验收范围。全部 840 个 Test 注解方法返回 void，254 份修改或新增文本通过 LF/尾随空白检查，`git diff --check` 通过。公开操作 114 项、后台 41 项、批量 54 项；证据在 `build/asset-session-proof.json`。本阶段未提交代码或创建 PR，完整重构仍未完成。

## 此前阶段验证

此前 Windows 预览姿态阶段全量 `gradlew.bat test --offline` 成功：828 项，818 项通过、10 项按既有条件跳过，无失败或错误；日志为 `build/preview-command-final-full-test.log`，XML 在 `build/preview-command-windows-results/`。新增 6 项独立应用回归、3 项 GUI 窄接口回归及 1 项真实 GUI/MCP 集成：捕获姿态与未命中值/锁保留、逻辑无变化投影、旧姿态规范化且不自动升级、快照旧范围/删除参数、投影拒绝与并发辅助 CAS、同工程重开拒绝旧状态、冻结 GUI 值、复制工作区及时登记、不覆盖非活动工作区。实际提交即使与 GUI 已显示值相同也标记未保存修改；实际宿主注入未提交值及锁后，公开部分预览、快照应用、无变化重试均依据持久基线；保存排除活动及非活动工作区的临时值与求值缓存，重开保持姿态、快照和历史，CMO3 读回保留参数定义。保存前/重开后的 PNG 逐字节相同，位于 `build/preview-command-visual/` 并已目视检查；全部 828 个 Test 注解方法返回 void。公开操作 114 项、后台 37 项、批量 54 项保持不变。

此前 Ubuntu 24.04.5 LTS / OpenJDK 21 预览姿态阶段全量 `gradlew test --offline --rerun-tasks` 成功：828 项，816 项通过、12 项跳过，无失败或错误。使用[官方 Ubuntu 根文件系统](https://cloud-images.ubuntu.com/minimal/releases/noble/release/)及 SHA-256 校验，在 Debian WSL 内隔离 chroot，保留 WSL 内核；不是独立启动的 Ubuntu 桌面会话。最小镜像首次缺少 Java/Skia 字体依赖导致环境失败，补齐 Ubuntu 的 Fontconfig、FreeType、HarfBuzz 和 JRE 后全量重新编译及测试，未以改动产品代码规避。隔离环境无法连接继承的 WSL 显示地址，验证使用 `JAVA_TOOL_OPTIONS=-Djava.awt.headless=true`；不包含 Ubuntu 桌面启动验收。成功日志为 `build/preview-command-ubuntu-final-full-test.log`，XML 在 `build/preview-command-ubuntu-results/`；环境和四个关键实现哈希在 `build/preview-command-ubuntu-environment.txt` / `build/preview-command-ubuntu-code-hash.txt`，已核对与 Windows 相同。图片位于 `build/preview-command-ubuntu-visual/`，保存前/重开后各自逐字节一致并已目视检查。阶段汇总为 `build/preview-command-proof.json`。该阶段证据不代表剩余 GUI 手势、素材辅助状态、播放/跟踪和复杂源图迁移完成；完整业务落地后仍须最终全量验收。

此前 Windows 独立 Warp 阶段全量 `gradlew.bat test --offline` 成功：818 项，808 项通过、10 项按既有条件跳过，无失败或错误；日志为 `build/warp-command-full-test.log`。新增 5 项核心、6 项独立应用回归及 1 项真实 GUI/MCP 集成，专项 27 项全部通过（`build/warp-command-regression-test.log`），并扩展实际 HTTP 断线重连、去重和重复目标拒绝。验证三角/四边形父 Warp 的多姿态插值、普通默认形参考、两个混合形叠加/饱和/限制、局部及非局部拟合、旧格式重放、非法 ID/目标/超限格点。原子批量创建父级及两级子 Warp 只追加一个历史节点，返回全部生成句柄；错误后项、实际重建途中取消、并发辅助 CAS、旧状态和投影拒绝保留原文档与历史。单项任务在 CAS 后迟到取消或刷新失败仍保持准确终态及自动生成的 ID。真实 GUI 画布父 Warp 记为 USER，再经 MCP 和内部 typed 创建子 Warp，添加混合形与独立关键形后保存重开；CMO3 多姿态读回与保存前保持一致。保存前/重开后的 PNG 逐字节相同，位于 `build/warp-command-visual/`，已目视检查；全部 818 个 Test 注解方法返回 void。当前公开操作 114 项、后台 37 项、批量 54 项；其余 GUI 业务、素材/预览状态、复杂源图/分类迁移及 Ubuntu 验收仍待完成。

Debian 13 WSL / OpenJDK 21 同阶段全量 `gradlew test --offline --rerun-tasks` 成功：818 项，806 项通过、12 项跳过，无失败或错误；日志为 `build/warp-command-linux-test.log`，XML 在 `build/warp-command-linux-results/`，环境与三个关键实现的源码哈希在 `build/warp-command-linux-environment.txt` / `build/warp-command-linux-code-hash.txt`，已核对与 Windows 相同。额外两项原生 Cubism 动作观察在 Windows 实际通过。每个平台保存前/重开后的 PNG 各自逐字节一致，Linux 图片位于 `build/warp-command-linux-visual/`，已目视检查。该证据不替代要求的 Ubuntu 验收。

此前 Windows 已编辑图层删除/恢复阶段全量 `gradlew.bat test --offline` 成功：806 项，796 项通过、10 项按既有条件跳过，无失败或错误；日志为 `build/authored-layer-delete-full-test.log`。新增 7 项独立应用回归和 1 项真实 GUI/MCP 集成，专项 26 项全部通过（`build/authored-layer-delete-regression-test.log`）。回归先复现普通图层删除重新编号、深度后层删除导致日志引用失效，以及旧工程恢复图层重指已有编辑的问题，再验证修复。普通已编辑网格、深度前后层的部分/全部删除先重放后过滤，恢复保留多姿态运动、Warp、通道关键形、路径、顶点组、遮罩及 Glue；旧节点的文档、revision 和绑定不改写。旧工程首次删除或直接恢复固定当前可见身份；已绘画裁剪的源图保留生成坐标系，删除期间修改网格设置与可见时修改得到相同几何、重绑路径/权重。实际候选取消、外部辅助 CAS、旧状态和投影拒绝均不发布固定 ID 或标记。真实 GUI 删除及恢复记为 USER，公开单项/批量、任务重试、删除归档与全部删除归档重开、空模型导出、恢复后 CMO3 多姿态读回均通过；导入 CMO3 后新增关键形，再删除/重开/恢复/导出也保持运动。删除前及恢复后的 PNG 逐字节一致，图片在 `build/authored-layer-delete-visual/`，已目视检查；全部 806 个带 Test 注解的方法均返回 void。公开操作 114 项、后台 36 项、批量 53 项保持不变；完整重构仍有其余 GUI 业务、素材/预览状态、复杂源图/分类迁移及 Ubuntu 验收待完成。

已编辑图层删除/恢复阶段 Debian 13 WSL / OpenJDK 21 全量 `gradlew test --offline --rerun-tasks` 实际执行成功：806 项，794 项通过、12 项跳过，无失败或错误；日志为 `build/authored-layer-delete-linux-test.log`，XML 在 `build/authored-layer-delete-linux-results/`，环境及三项关键实现的源码哈希在 `build/authored-layer-delete-linux-environment.txt` / `build/authored-layer-delete-linux-code-hash.txt`，与 Windows 实现相同。额外跳过两项需原生 Cubism 的动作观察，这两项已在 Windows 实际通过。每个平台删除前与恢复后的 PNG 各自逐字节一致，删除归档重开 PNG 也由集成断言验证；Linux 图片在 `build/authored-layer-delete-linux-visual/`，已目视检查。跨平台 PNG 字节不同，没有据此声称两平台图像完全相同。Debian 证据不替代要求的 Ubuntu 验收。

此前深度拆分阶段 Windows 全量 `gradlew.bat test --offline` 成功：798 项，788 项通过、10 项按既有条件跳过，无失败或错误；日志为 `build/depth-split-command-full-test.log`。新增 8 项独立应用回归及 3 项真实 GUI/MCP 集成，并扩展实际 HTTP 断线重连、任务去重及重复中间对象拒绝；专项 33 项全部通过（`build/depth-split-command-regression-test.log`）。验证已有 Warp/通道关键形/路径/Glue 后拆分保留全部原网格运动，前层精确复制所选网格；嘴部及派生嘴唇只新增一个网格，清空后仍保留 ID/拓扑/运动。保留软删除像素、生成输入及逐层覆盖；批量后项可引用新层/网格/Glue，后项失败无前缀。像素复制和实际重建途中取消、外部辅助 CAS、投影拒绝均保留原状态；CAS 后迟到取消或刷新异常保留精确完成结果，原请求重试只取回同一任务。GUI 与 MCP 的同一拆分 PNG 一致，GUI 旧对话框在辅助版本变化后拒绝；历史回退/恢复、完整归档重开及 CMO3 多姿态/Glue 权重读回通过。修复生成阶段引用尚未重放的父级、已删除源图影响首次冻结范围及前层额外生成嘴唇三处问题。重开前后 PNG 逐字节一致，位于 `build/depth-split-command-visual/`，已目视检查；编译方法检查确认全部 798 项带 Test 注解的方法均返回 void。当前公开操作 114 项、后台 36 项、批量 53 项；其余 GUI 业务、复杂对象迁移、素材/预览边界及 Ubuntu 验收仍待完成。

此前深度拆分阶段 Debian 13 WSL / OpenJDK 21 全量 `gradlew test --offline --rerun-tasks` 实际执行成功：798 项，786 项通过、12 项跳过，无失败或错误；日志为 `build/depth-split-linux-test.log`，XML 在 `build/depth-split-linux-results/`，环境及关键源码哈希在 `build/depth-split-linux-environment.txt` / `build/depth-split-linux-code-hash.txt`，三个关键实现与 Windows 源码哈希相同。额外跳过的两项原生 Cubism 动作观察已在 Windows 实际通过。每个平台的 GUI/MCP 同一拆分和保存重开 PNG 各自逐字节一致，Linux 图片位于 `build/depth-split-linux-visual/` 并已目视检查；跨平台重开图像有 1030 个像素不同、最大通道差 4/255，未把跨平台字节相同作为验收结论。Debian 证据不替代要求的 Ubuntu 验收。

此前源图分区阶段 Windows 全量 `gradlew.bat test --offline` 成功：787 项，777 项通过、10 项按既有条件跳过，无失败或错误；首轮日志为 `build/source-partition-full-test.log`，最终复验为 `build/source-partition-final-full-test.log`。本阶段新增 8 项独立应用回归、3 项真实 GUI/MCP 集成与 1 项 schema 回归，并扩展实际 HTTP 任务/跨连接重试/重复名称校验。专项 40 项全部通过（`build/source-partition-regression-test.log`），之后增加的多层 GUI 候选和真实公开批量由全量验证。验证两种分区将原可见 RGBA 恰好分配一次，原源图不变；稳定 ID、继承覆盖、保留生成输入/无关关键形及变形器坐标；历史切换、独立重放、旧查询捕获和删除后查询；批量后续网格/参数/通道可引用新层，错误后项无前缀；实际像素、组件及重建途中取消、外部持久修改、投影拒绝保留原状态；两项后台在 CAS 后的取消或刷新异常保留完整成功终态。真实 GUI 与 MCP 连通块产生相同几何和变形器，旧对话框在辅助版本变更后拒绝；公开多边形/批量、原历史归档重开与 CMO3 多姿态读回通过。重开前后 PNG 逐字节一致，位于 `build/source-partition-visual/`，已目视检查。编译方法检查确认全部 787 项带 Test 注解的方法均返回 void。公开操作 113 项、后台 35 项、批量 52 项；剩余 GUI 业务、复杂对象迁移、素材/预览边界、其他单项重建及 Ubuntu 验收仍待完成。

Debian 13 WSL / OpenJDK 21 全量 `gradlew test --offline --rerun-tasks` 实际执行成功：787 项，775 项通过、12 项跳过，无失败或错误；日志为 `build/source-partition-linux-test.log`，XML 保留在 `build/source-partition-linux-results/`，环境与关键源码哈希保留在 `build/source-partition-linux-environment.txt` / `build/source-partition-linux-code-hash.txt`。比 Windows 多跳过两项需要原生 Cubism 运行时的动作观察用例，这两项已在 Windows 实际通过。两平台拆分保存重开前后的四张 PNG 逐字节一致，Linux 图片位于 `build/source-partition-linux-visual/`，已目视检查。该结果提供 Debian Linux 证据，不替代要求的 Ubuntu 验收。

此前生成命令阶段 Windows 全量 `gradlew.bat test --offline` 成功：775 项，765 项通过、10 项按既有条件跳过，无失败或错误；日志为 `build/generation-command-full-test.log`。本阶段新增 5 项独立应用回归及 1 项真实 GUI/MCP 集成，扩展实际 HTTP 断线重试和既有分类/请求回归。专项 21 项全部通过（`build/generation-command-regression-test.log`），之后增加的复合草稿和临时网格预览确认比较由本次全量验证。三项命令与批量生成相同候选，省略分类字段保留捕获值、无变化不追加历史；起始状态先于业务解析被拒绝，非法后续成员、并发持久修改、重建途中取消和投影拒绝保留原文档及历史。三项后台任务提交后遭遇取消或刷新异常仍保持精确成功终态，原请求取回同一任务，HTTP 跨连接设置修改只执行一次。真实 GUI 连续拖动只提交最终值和一个用户历史节点，分类文字往返原值不提交，临时网格预览不改状态而确认正式提交；工程保存重开保留历史，多姿态 CMO3 读回顶点与透明度一致。重开前后 PNG 逐字节一致，位于 `build/generation-command-visual/`，已目视检查。本阶段还修复 `WorkspacePortBindingsTest` 的非 Unit 返回类型，原方法此前未被 JUnit 发现；本次 XML 已实际执行该测试，编译方法检查确认全部 775 项带 Test 注解的方法均返回 void。此前能力接口阶段对该用例的验收表述以本次实际执行为准。公开操作 111 项、后台 33 项、批量 50 项；剩余 GUI 业务、复杂对象迁移、素材/预览边界、其他单项重建及 Ubuntu 验收仍待完成。

此前图层删除/恢复阶段 Windows 全量 `gradlew.bat test --offline` 成功：768 项，758 项通过、10 项按既有条件跳过，无失败或错误；日志为 `build/layer-membership-full-test.log`。本阶段新增 5 项独立应用回归及 1 项桌面/MCP 集成，扩展派生嘴唇及真实 HTTP 回归。首轮专项 52 项通过（`build/layer-membership-regression-test.log`），删除期间设置补验 5 项通过（`build/layer-membership-settings-test.log`），最终增加路径/权重比较后的版本由本次全量验证。验证新建网格上的关键形、路径、权重、遮罩及 Glue 在删除时从活动模型移除，恢复保留 ID 和全部绑定；完全删除后恢复、后项失败整批回滚、无变化不追加历史、旧节点不改写、并发持久修改、实际候选重建途中取消和投影拒绝。部分或全部图层删除期间修改网格设置，与可见时修改后得到相同持久数据和几何，并保存重绑路径/权重；修复全部删除后设置编辑误用空分析的贴图下限。两项任务在迟到取消或刷新异常后仍保留完整完成结果，真实 HTTP 断线重连取回同一恢复任务并只执行一次。真实 GUI 用户入口及 MCP 指定/全部恢复、删除归档重开、删除和恢复 CMO3 导出读回验证通过；多姿态顶点和透明度一致。删除前与恢复重开后的 PNG 逐字节一致，图片位于 `build/layer-membership-visual/`，已目视检查。公开操作 111 项、后台 30 项、批量 50 项；其他单项重建、复杂对象迁移、剩余 GUI 业务、素材/预览边界与 Ubuntu 验收仍待完成。

此前绘画命令阶段 Windows 全量 `gradlew.bat test --offline` 成功：762 项，752 项通过、10 项按既有条件跳过，无失败或错误；日志为 `build/raster-command-full-test.log`。本阶段新增 5 项独立应用回归及 1 项桌面/MCP 集成，并扩展真实 HTTP 回归。首批专项 33 项通过（`build/raster-command-regression-test.log`），最终专项 16 项通过（`build/raster-command-final-regression-test.log`）。验证 GUI 冻结像素与公开任务产生相同持久候选、六种绘画均保留正确终态契约、无变化不追加历史；真实洪水填充及裁剪途中取消、并发持久修改、投影拒绝均不发布像素或历史；批量前项新建参数在绘画成员取消时保持私有并报告成员进度。全部六种绘画在 CAS 后遭遇迟到取消或刷新异常时仍返回实际成功结果，原请求重试只取回同一任务。真实 HTTP 断线重连仅执行一次绘画并保留状态、历史节点和生成句柄。实际桌面/MCP 验证首次网格创建后绑定关键形、依次绘画/擦除/清空/重画及显式重建、原历史归档重开和 CMO3 导出读回；多姿态顶点与透明度一致。重开前后 PNG 逐字节相同，位于 `build/raster-command-visual/`，已目视检查。全量暴露并修复内部绘画解析先于过期状态检查的回归。公开操作 110 项、后台 28 项、批量 48 项；其余单项重建、创建后的对象迁移、剩余 GUI 业务、素材/预览边界及 Ubuntu 验收仍待完成。

此前网格创建阶段 Windows 全量 `gradlew.bat test --offline` 成功：756 项，746 项通过、10 项按既有条件跳过，无失败或错误；日志为 `build/raster-creation-full-test.log`。本阶段新增 6 项核心/应用/真实 MCP 回归，专项 40 项全部通过，日志为 `build/raster-creation-regression-test.log`。验证透明图层首次可见绘画自动创建网格、原模型的关键形与坐标不变、新网格随后关键形/路径/顶点权重编辑、显式拓扑扩展及网格设置更新、清空后再绘画和重复无变化。嘴部首次绘制同时创建嘴唇及生成关键形，清空即使传入重建标志也保留 ID、几何及透明覆盖；历史归档重开和 CMO3 读回的多姿态顶点与透明度保持一致。创建候选在后续成员失败、准备期间取消及外部持久状态修改时均不发布前缀。直接重放验证旋转/缩放/重新裁剪及两种 UV 地址，非法 ID、父级、源图、覆盖尺寸、索引、UV 和关键形坐标被拒绝。真实 MCP 返回新网格句柄，原请求重试只提交一次，历史回退和恢复保留身份；修复单项绘画丢弃生成句柄及非法 UV 数组未在转换前校验的问题。两组重开前后 PNG 逐字节相同，位于 `build/raster-creation-visual/`，已目视检查。公开操作仍为 110 项、后台 22 项、批量 48 项；创建后软删除、复杂父级/分类切换、导入无网格对象、剩余任务化和 GUI 业务、素材/预览边界及 Ubuntu 验收仍须继续完成。

此前共享绘画阶段全量 `gradlew.bat test --offline` 成功：750 项，740 项通过、10 项按既有条件跳过，无失败或错误；日志为 `build/shared-raster-full-test.log`。新增 8 项核心/应用/GUI 回归，并扩展真实 MCP 与深度前层绘画回归；专项 36 项全部通过，日志为 `build/shared-raster-regression-test.log`，实际 MCP 专项日志为 `build/shared-raster-mcp-test.log`。验证公开手势与捕获 GUI 像素生成相同候选、已有 Warp/普通及混合关键形/路径/权重/Glue 的保留与迁移、最后一层清空后再绘画和重复无变化；批量后续成员失败、重建期间取消及并发状态修改均不发布绘画前缀。生成嘴唇保留已编辑关键形，保存显式重建的轮廓和颜色，后续保留网格绘画不重新生成派生贴图；即使顶点不变，显式重建也能更新颜色输入。真实归档重开和 CMO3 读回在多组参数姿态下保持顶点及透明度一致。CMO3 绘画实际渲染新像素，保留未编辑网格的图集/UV 和逐字节相同的渲染，显式重建保留关键形；修复首次绘画重新采样无关贴图的问题。GUI 手势冻结像素、传入开始时的状态及 USER 身份，等待成功后恢复画笔，冲突保留原可见模型。深度前层通过真实工作区只提交一个历史节点并保留矩形、拓扑和 Glue。嘴唇和导入模型的重开前后 PNG 逐字节相同，位于 `build/shared-raster-visual/`，已目视检查。公开操作仍为 110 项、后台 22 项、批量 48 项；新生成网格创建、复杂导入模型绘画、剩余任务化和 GUI 业务、素材/预览边界及 Ubuntu 验收仍须继续完成。

此前生成输入阶段 Windows 全量 `gradlew.bat test --offline` 成功：742 项，732 项通过、10 项按既有条件跳过，无失败或错误；日志为 `build/generation-source-full-test.log`。新增 3 项核心/GUI 回归，专项 22 项全部通过，日志为 `build/generation-source-regression-test.log`。验证源图裁剪后生成坐标系和全部顶点保持一致、透明覆盖不采邻图、全部像素清空时基础拓扑仍可重建、画布维度不匹配拒绝；实际应用候选一次 CAS/一个历史节点、原始和绘画栅格 blob 去重、旧根节点/版本保留、撤销/重做、历史存储及真实 `.psd2live` 归档保存和独立重开。网格替换后的普通/混合关键形、透明度、路径、权重及 Glue 在六组参数姿态下，经重开和流水线 CMO3 导出读回保持一致；归档中的原始 PSD 保持根节点像素。重开前后 PNG 逐字节一致，图片位于 `build/generation-source-visual/`，已目视检查。GUI 捕获不从 mutable preview 推断输入，投影核对并可清空生成输入。公开操作仍为 110 项、后台 22 项、批量 48 项；完整绘画候选及 MCP 入口、其余 GUI 业务与素材/预览边界、Ubuntu 验收仍须继续完成。

此前网格替换阶段 Windows 全量 `gradlew.bat test --offline` 成功：739 项，729 项通过、10 项按既有条件跳过，无失败或错误；日志为 `build/raster-migration-full-test.log`。新增 4 项核心/应用回归，并扩展已有绘画重建回归：真正应用候选重建/CAS、一个历史节点、撤销/重做、无变化不提交、历史序列化与独立重开；普通关键形/混合形/路径/顶点组/Glue 的迁移；源图重新裁剪、旋转和非均匀缩放后的页 UV 与局部 UV；相同位置的重复顶点保持身份；错误维数、旧几何或父级不匹配拒绝且原模型不变；方向连接合并保留最大权重、无悬空索引；后续网格设置重生淘汰旧替换记录并保存可重放候选。CMO3 读回与重开在六组普通/混合参数姿态下保持全部顶点一致；重开前后 PNG 逐字节一致，图片保留在 `build/raster-migration-visual/` 并已目视检查。专项 22 项全部通过，日志为 `build/raster-migration-regression-test.log`。公开操作仍为 110 项、后台 22 项、批量 48 项；完整绘画提交及 MCP 拓扑迁移、其余 GUI 业务与素材/预览边界、Ubuntu 验收仍须继续完成。

此前绘画准备阶段 Windows 全量 `gradlew.bat test --offline` 成功：735 项，725 项通过、10 项按既有条件跳过，无失败或错误；日志为 `build/raster-commit-full-test.log`。新增 4 项独立核心回归覆盖编辑后保留网格的裁剪/重打包与全部 UV 画布地址、输入源像素不变；重建后旧变形器坐标系、路径重绑定与顶点组重采样；仅重建网格时保留源图像素以及错误输入拒绝；深度拆分前层强制保留拓扑、矩形、方向 Glue 与重放。架构检查包含共享绘画准备。专项 30 项全部通过，日志为 `build/raster-commit-regression-test.log`；现有 GUI 深度前层擦除、MCP 拆分、历史/保存重开/导出与 HTTP 契约通过本次全量。公开操作仍为 110 项、后台 22 项、批量 48 项；拓扑迁移持久化、其余 GUI 业务与素材/预览提交边界、Ubuntu 验收仍须继续完成。

此前物理命令阶段 Windows 全量 `gradlew.bat test --offline` 成功：731 项，721 项通过、10 项按既有条件跳过，无失败或错误；日志为 `build/physics-command-full-test.log`。物理命令阶段新增 5 项独立应用回归及 1 项桌面/MCP 集成，并扩展真实 HTTP 回归：导入保留禁用组和缺失参数等全部诊断；五项命令重放、无变化和原状态校验正确；拟合静置及逐帧求解途中取消、读取途中取消、并发修改和投影拒绝均保留原候选与历史；五项单项在提交后的取消或刷新失败中仍保留完整成功结果，原请求只取回同一任务；批量新建物理组后拟合使用前序候选，后项失败或求解取消整批回滚。真实 MCP 验证五项单项的任务/批量属性、历史切换、保存重开及 CMO3 输入/输出/段读回；保存重开的 PNG 逐字节一致，图片保留在 `build/physics-command-visual/`，合成条带已目视检查。真实 HTTP 跨连接重试只执行一次导入，终态保留导入 ID、禁用组、缺失参数、FPS 并符合发布契约。专项日志为 `build/physics-command-regression-test.log`（14 项全部通过）。后台操作增至 22 项、批量增至 48 项，公开操作仍为 110 项；其余单项重建、GUI 草稿及业务绑定、素材/预览提交边界与 Ubuntu 验收仍待完成。

此前静态渲染阶段全量 `gradlew.bat test --offline` 成功：725 项，715 项通过、10 项按既有条件跳过，无失败或错误；日志为 `build/render-session-full-test.log`。静态渲染阶段新增 5 项独立应用回归及 1 项桌面集成：五项观察入口各只捕获一次，多姿态逐图之间发生编辑及工程替换仍保留原版本；源图、上下文和模型的 PNG 与空间数据一致；显式空可见集合、冻结集合、参数校验、透明度和旧拆分可见性覆盖保持正确；运行时关闭后旧会话仍可读取；旧资源写入原工程而不污染新工程、GUI 模型或日志。专项日志为 `build/render-session-regression-test.log`，源图、上下文和模型图片保留在 `build/render-session-visual/` 并已目视检查。原生动作集成 2 项及 HTTP 契约回归同样实际执行通过。后台仍为 17 项、公开操作 110 项、批量 47 项；剩余单项重建、素材/预览业务提交边界、完整 GUI 业务绑定及 Ubuntu 验收仍待完成。

此前动作观察阶段全量 `gradlew.bat test --offline` 成功：719 项，709 项通过、10 项按既有条件跳过，无失败或错误；日志为 `build/motion-observation-full-test.log`。本阶段新增 5 项独立应用回归及 2 项真实原生集成，并扩展 HTTP 回归：动作采样在外部编辑/同工程重开后保持捕获的模型、revision 与 PNG；求值和逐图渲染中取消不发布成功结果或修改历史；取消等待后继续执行，重复请求取回同一图片；历史对比保留旧历史树，原生结果不完整时任务失败；非法上下文、帧字段及时间边界在调度前拒绝。两个原生用例在本机实际执行，验证导出动作驱动参数、保存重开拼图逐字节一致、帧循环和排队 future 取消、临时文件清理及会话恢复；拼图保留在 `build/motion-observation-visual/` 并已目视检查。实际 HTTP 发布完整请求/终态契约，断线重连后动作任务只执行一次，job_get/job_wait 返回同一 PNG 图片内容。专项日志为 `build/motion-observation-test.log`、`build/motion-native-test.log` 和 `build/motion-observation-regression-test.log`。后台操作增至 17 项，公开操作仍为 110 项、批量仍为 47 项。

此前只读物理/模拟采样阶段全量 `gradlew.bat test --offline` 成功：712 项，702 项通过、10 项按既有条件跳过，无失败或错误；日志为 `build/sampling-full-test.log`。本阶段新增 3 项独立应用回归，并扩展真实桌面/MCP 及 HTTP 回归：两种采样在外部提交及同工程重开期间仍只读取捕获版本；校准/静置和实际采样帧均可取消，原文档、历史及任务去重不变；取消等待后任务继续，重复请求返回同一结果，网格及固定权重数组不变。真实桌面/MCP 验证两项只读任务结果符合终态契约并保留捕获身份，采样前后 state、历史及 PNG 一致；实际 HTTP 跨会话取回同一个采样任务，执行一次并保留诊断和版本。专项日志为 `build/sampling-regression-test.log`。后台操作增至 16 项，公开操作仍为 110 项、批量仍为 47 项；剩余单项重建、动作时间采样、业务提交边界和 Ubuntu 验收仍待完成。

此前单项模拟任务阶段全量 `gradlew.bat test --offline` 成功：709 项，699 项通过、10 项按既有条件跳过，无失败或错误；日志为 `build/simulation-single-full-test.log`。本阶段新增 2 项独立应用回归，并扩展真实桌面/MCP 及 HTTP 回归：单项自动烘焙真实求解途中取消和并发持久修改均保留原候选/历史，结构化冲突保留起始状态；单项烘焙提交后迟到取消或刷新异常仍保留完整状态、历史节点、生成参数句柄及质量诊断，原请求重试只取回同一任务。真实 MCP 验证五项模拟/预设单项均有后台与批量属性、无变化、清除/重烘焙/删除、关闭传统摆动及单项烘焙保存重开 PNG 一致；真实 HTTP 发布完整输入/输出与终态契约，跨会话断线重试的单项烘焙只执行一次并保留诊断。专项日志为 `build/simulation-single-job-test.log` 和 `build/simulation-single-regression-test.log`。后台操作增至 14 项，批量仍为 47 项，GUI/MCP 单项提交编排下沉独立 `WorkspaceSimulationCommands`。其他单项重建、只读模拟/时间采样、剩余业务提交边界与 Ubuntu 仍待完成。

此前模拟候选阶段全量 `gradlew.bat test --offline` 成功：707 项，697 项通过、10 项按既有条件跳过，无失败或错误；日志为 `build/simulation-command-full-test.log`。本阶段新增 7 项回归：独立应用层顺序创建驱动参数/关键形/固定组/模拟并真实烘焙，清除/删除、无变化、重建、历史保存及切换；后续成员失败整批回滚；真实求解中取消（包含自动烘焙核心返回失败诊断）及并发持久修改保留原状态；头发预设与 `sway:false` 恢复；后台任务报告烘焙成员，取消及请求重试保留同一终态；真实 MCP 模拟批量只提交一个 Agent 历史节点，保存重开保持参数、基础网格、全部关键形、烘焙记录及 PNG，CMO3 读回保留参数/关键形和完整摆锤输入、输出及段。专项日志为 `build/simulation-command-regression-test.log`；图像保留在 `build/simulation-command-visual/`，重开前后逐字节一致且已目视检查。新回归暴露并修复烘焙偏移在内存与已有 v1 精度之间的差异；提交前规范化，保持归档格式。批量成员增至 47 项，模拟/预设业务准备下沉应用层。单项重建/烘焙/采样任务化、剩余业务提交边界与 Ubuntu 仍待完成。

此前完整输出契约阶段全量 `gradlew.bat test --offline` 成功：700 项，690 项通过、10 项按既有条件跳过，无失败或错误；日志为 `build/complete-output-contract-full-test.log`。本阶段新增 11 项回归：素材的旧空详情、原生 alpha 与抠图诊断、真实持久保存/重载/配准/合成/再处理与原图保留；真实 MCP 六项素材调用及返回图片、参考/配准列表和不增加 Rig 历史；实际时间模拟与烘焙输出、阶段输入类型、质量摘要和陈旧诊断、预设衣物测量与逐项烘焙错误；全部 inspect 分支、空分页/越界分页/未加载状态与只读捕获、稀疏 v1 设置补齐默认值和文档网格覆盖、物理/摆动/模拟非空可选字段；后台定义与能力详情不能遗漏或虚构终态契约。注册表要求所有 110 项操作的结果契约，实际 HTTP 发布完整输入/输出 schema；素材详情组装已移到应用层。专项日志为 `build/remaining-output-contract-test.log` 和 `build/asset-mcp-contract-test.log`。历史重放、保存重开、导出读回、并发/取消及 HTTP 认证契约通过本次全量；素材持久版本边界、全部 GUI 业务绑定、剩余批量/后台操作和 Ubuntu 仍待完成。

此前观察输出阶段全量 `gradlew.bat test --offline` 成功：689 项，679 项通过、10 项按既有条件跳过，无失败或错误；日志为 `build/observation-output-full-test.log`。观察输出阶段新增 1 项应用回归并扩展已有渲染及物理集成：真实源图/Rig 渲染的元数据符合严格契约，缺失六项像素到画布变换的错误维数拒绝；姿态与历史版本拼图保留各自的 revision 归属，矛盾的全局/逐图版本字段拒绝；覆盖率结果的像素范围和诊断字段可校验；物理保持为零或占满持续时间时，对应零长度阶段合法地返回空度量。公开操作已有 99 项声明输出，7 项观察及物理采样/历史读取均经同一注册表和 HTTP 发布；其余 11 项为复合 inspect、素材及模拟/预设输出。Agent/观察专项通过，日志为 `build/observation-contract-test.log` 与 `build/observation-contract-final-test.log`。历史重放、保存重开、CMO3 读回、并发/取消、真实 MCP/HTTP 认证契约均通过本次全量；Ubuntu、剩余领域输出及完整业务矩阵仍待最终验收。

此前文档/动画输出阶段全量 `gradlew.bat test --offline` 成功：688 项，678 项通过、10 项按既有条件跳过，无失败或错误；日志为 `build/authoring-output-full-test.log`。文档/动画输出阶段新增 4 项应用回归：全部 42 项批量成员均有可执行输出契约，公开操作已有 90 项声明输出；精简编辑保留省略成功与显式无变化，拒绝额外字段及无效空 changed；实际骨架序列化的连接、镜像、参数覆盖、权重、IK 目标及保存姿态能通过契约，错误 IK 字段被拒绝；实际动作序列化保留可省略的插值和 Bezier 控制柄，错误维数被拒绝。路径读取/预览、显式姿态、参数、Rig、绘画、层放置、物理/摆动编辑及历史切换等均接入注册表结果校验。Agent/应用专项通过，日志为 `build/authoring-contract-test.log` 与 `build/authoring-contract-final-test.log`。历史重放、保存重开、CMO3 读回、并发/取消、真实 MCP/HTTP 认证契约均通过本次全量；Ubuntu、剩余领域输出及完整业务矩阵仍待最终验收。

此前输出基础阶段全量成功：684 项，674 项通过、10 项按既有条件跳过，无失败或错误；日志为 `build/output-contract-full-test.log`。输出阶段新增 9 项应用回归：错误输出按内部契约错误分类且请求重试只执行一次；成功/错误包装互斥，额外字段与错误嵌套结果拒绝；可空注释与快照摘要精确校验；任务状态禁止泄漏结果或矛盾错误，错误终态结果在成功发布前拒绝，持久结果在迟到取消后保留；递归能力详情可校验自己的完整包装，本地定义在请求/结果包装中保持根作用域，缺失/外部/纯循环引用在注册时拒绝。真实 HTTP 对比完整输入与输出 schema，并使用客户端实际收到的 schema 校验快照、错误、能力自身详情、任务开始/重试/终态及导出文件。任务契约专项 38 项、能力契约专项均通过；日志为 `build/job-contract-test.log` 与 `build/capability-contract-test.log`。

此前查询阶段全量成功：675 项，665 项通过、10 项按既有条件跳过，无失败或错误；日志为 `build/query-full-test.log`。查询阶段新增 6 项独立应用回归：捕获后跨领域编辑仍保留原模型/设置/历史/姿态，空工作区与同工程重开保持独立，复合公开查询只捕获一次并拒绝活后端业务读取，整数极限分页不产生溢出游标，软删除源图与旧组件保留详情/身份且有效网格可发现。桌面集成还验证求值帧与未提交 GUI 姿态不改查询。全量暴露并修复任务结束回调无状态变化却更新时间戳的竞态，新增稳定复现通过；启动预设测试改为等待完整选择集合，避免读到第一个开关完成时的过渡状态。查询/任务专项及启动预设专项均通过，日志为 `build/query-job-test.log` 与 `build/query-final-target-test.log`。

此前批量任务阶段全量成功：668 项，658 项通过、10 项按既有条件跳过，无失败或错误；日志为 `build/batch-job-full-test.log`。批量任务阶段新增 3 项独立应用回归：后续成员重建时取消不发布前缀并报告项数/操作，准备期间的外部持久修改产生结构化状态冲突且重试保留原失败，成功提交后的取消或刷新异常保留完整新状态/历史节点/生成句柄及一个历史节点。既有真实 MCP 批量回归改为任务调用，验证精确成员 schema、断线重连、请求重试去重、结构化错误索引、128 项上限、无变化和整批像素回滚；保存重开 PNG 逐字节一致，CMO3 读回通过，图片保留在 `build/batch-job-visual/` 并已目视检查。共享任务启动器的导入/保存/导出以及 HTTP 认证/契约也通过全量回归。

此前源图导入阶段全量成功：665 项，655 项通过、10 项按既有条件跳过，无失败或错误；日志为 `build/source-import-full-test.log`。源图导入阶段新增 6 项回归：独立应用层 PSD/图片创建与重建、清除旧覆盖与辅助数据、已保存切换、读取前拒绝未保存修改、投影失败回滚、读取期间并发 CAS、取消前后边界及进度、GUI 窄接口绑定与可信起始状态，以及 GUI 分析和真实 MCP 创建/切换的 schema、任务重试/断线、保存重开、PSD 像素及 CMO3 读回。还验证各工作区的旧姿态/锁/播放控制和画布会话被重置，非整数位置及未知分类在任务创建前拒绝。重开前后模型 PNG 逐字节一致，图片保留在 `build/source-import-visual/` 并已目视检查。

此前后台错误阶段全量成功：659 项，649 项通过、10 项按既有条件跳过，无失败或错误；日志为 `build/job-failure-full-test.log`。后台错误阶段新增 3 项回归：即时与异步冲突使用相同结构，任务查询/列表/断线重连与原请求重试保留错误且不重复执行，文件读取失败保留机器可读代码和消息，成功提交后的刷新异常保留 completed 与实际结果。既有 CMO3 新建和工程打开回归还验证未保存拒绝的专用 `unsaved_changes` 代码。

Windows CMO3 导入阶段全量 `gradlew.bat test --offline` 成功：656 项，646 项通过、10 项按既有条件跳过，无失败或错误；日志为 `build/cmo3-import-full-test.log`。新增 9 项回归覆盖独立应用层新建/替换及历史重放、未保存拒绝/显式丢弃、投影失败回滚、读取前捕获与并发拒绝、提交前取消和进度、提交后取消保留成功、文档与辅助数据原子发布、旧 revision 的辅助提交，以及真实 MCP 的 schema/断线重试/保存重开/导出读回。重开前后 PNG 逐字节一致，图片保留在 `build/cmo3-import-visual/` 并已目视检查。

此前 GUI 字段完成阶段全量成功：647 项，637 项通过、10 项按既有条件跳过，无失败或错误；日志为 `build/editor-draft-full-test.log`。本轮新增 10 项回归：队列连续完成保留每个历史节点、外部修改在准备前后均触发冲突、取消等待不中断后台工作、等待包含后来排入的完成、投影拒绝不发布前缀及新草稿恢复、同工程重开拒绝旧完成、无变化不重建、不阻塞的 GUI 窄接口绑定及起始状态、保存等待后取得新的可信状态，以及真实字段完成后立即保存的历史/重开/渲染/CMO3 读回。重开前后模型渲染 PNG 一致，图片保留在 `build/editor-draft-visual/` 并已目视检查。绘画、深度拆分及 CMO3 替换导入的完成顺序也通过全量回归验证。

此前能力接口阶段全量成功：637 项，627 项通过、10 项跳过；日志为 `build/workspace-ports-full-test.log`。能力接口及 GUI 生命周期专项 92 项通过，日志为 `build/workspace-ports-lifecycle-test.log`；验证必需接口无默认行为、存储层依赖方向、无完整后端的源图/观察绑定，以及 GUI 生命周期的可信用户身份和起始状态。此前共享命令阶段全量成功：635 项，625 项通过、10 项跳过。此前共享命令阶段新增 7 项回归：有序参数定义/删除及最后默认值、旧 v1 参数覆盖和 revision/节点保持、相同几何下的透明度/颜色持久化及重复捕获无变化、GUI 参数定义/文件夹/关键点整批回滚、GUI journal 拒绝 HEAD 及旧加载状态、11 类内部 typed 命令拒绝状态别名、GUI 模拟/摆动的用户身份和起始状态。参数删除前的默认姿态与保存重开后的渲染逐像素一致，CMO3 读回保留删除结果及透明度；图片留在 `build/gui-command-visual/`。共享命令专项 90 项通过，日志为 `build/shared-command-test.log`；全量日志为 `build/shared-command-full-test.log`。此前新增 9 项实际批量回归：无 GUI 的画布/Glue/路径/物理顺序依赖与重放、参数/网格无变化、真实候选取消与并发 CAS、成员重建错误索引；桌面/MCP 的 128 项上限、像素与历史整批回滚、生成句柄和重试、一个历史节点、保存重开及 CMO3 导出读回。此前辅助数据专项 45 项通过，覆盖 GUI/MCP 快照与注释交叉编辑、无变化、范围/ID 拒绝、重复请求、保存重开、快照应用不自动打关键帧、pose 令牌及求值帧排除。此前新增回归覆盖投影失败时的提交/历史切换回滚、工程安装失败、无操作与作者/任务记录、保存历史版本捕获、查询隔离与不初始化恢复数据，以及旧 v1 绘制顺序重建。相关回归另有一次覆盖 `application`、`project`、`agent`、`ui.state`、共享绘画、旋转引导与网格渲染，164 项通过。额外专项测试通过无 GUI 源图 / Rig 渲染，以及真实 HTTP 的 Bearer 认证、精确 schema、非法字段拒绝、会话关闭后的导出任务查询与请求重试去重。尚未取得 Ubuntu 证据；生产运行时、全部批量成员及业务覆盖完成后仍须最终验收。

## 画布与动画面板共享播放会话（2026-10-05）

画布的播放及鼠标跟踪按钮现在进入应用层播放会话；打开动作时，画布播放按钮与动画面板共同播放/暂停该动作，没有动作打开时控制待机。GUI 投影会话的播放状态及自然结束，不再只改本地开关。鼠标事件只更新坐标，下个时钟帧求值；作者姿态提交后保留跟踪和暂停的播放头。

GUI 原生预览接收完整求值姿态并禁用 SDK 自有动作、拖拽和物理时钟，软件预览使用同一会话结果。时钟帧在物理合成后发布，迟到帧不能覆盖控制命令后的播放头或姿态，工程重开拒绝旧会话。详细约束见本机 `CLAUDE.md`。

新增回归覆盖画布与面板交叉暂停/恢复、动作自然结束、迟到帧丢弃、指针移动/释放、待机完整姿态及作者编辑后的会话重启和工程重开。本机 Windows 全量 `gradlew.bat test`：213 个类、1059 项，1048 通过、11 跳过，零失败、零错误。桌面交互与原生 SDK 实时播放仍待目视验收。

## 跟踪算法与编辑器播放状态修复（2026-10-05）

鼠标跟踪改为独立于待机和动作的会话合成步骤。默认即时映射；预览工具栏新增「平滑跟踪与身体 Y」选项，默认关闭，随工作区呈现保存，旧工程缺省关闭。选中动作不再切换跟踪算法，开关算法保留指针位置，关闭跟踪立即停止跟随。GUI 软件暂停物理不再另算一套指针姿态，原生与软件均消费会话的跟踪结果。

编辑后的预设继续使用面板的 preset ID，运行时覆盖 ID 映射回该身份，待机动作可由画布、面板或编辑器交叉暂停。最终渲染/物理姿态优先于时间线备用值；控制命令清除旧渲染帧，避免调整播放头后残留上次姿态。

新增四项回归，相关 51 项全部通过，覆盖算法独立性、跟踪关闭、呈现保存读回、编辑后待机暂停及最终渲染帧优先级。本机 Windows 全量测试：213 个类、1063 项，1052 通过、11 跳过，零失败、零错误。桌面交互与原生 SDK 的视觉效果尚待目视验收。

## 平滑跟踪响应优化（2026-10-05）

可选平滑跟踪使用独立的串联指数响应解析更新，头部响应率 18/s、身体 10/s。头部约 150 毫秒、身体约 350 毫秒达到固定目标的九成，身体保留较短的滞后；同样经过的时间在不同帧率下得到一致响应，零时间步保持原姿态。待机动画自身时钟沿用原算法。

新增回归覆盖响应速度、30/60/144 FPS 一致性、零时间步和突然反向时的范围。Windows 相关 25 项测试全部通过；本次调整未重跑全量测试，桌面手感仍需目视确认。

## 必须继续完成的验收项

逐入口源码审计已收口为以下五个实质缺口。五项均已实现（见上文「恢复后的五域实现」），下面保留原缺口描述，每项末尾记录当前状态；仍须与实时模拟、形变笔刷、Warp/Bezier 和绘制顺序组一起完整验收：

- 骨架编辑：批量变换、复制/镜像、细分/消解、链生成及手动权重绘制/清理/映射/转移仍由 GUI 准备；公开写最终骨架不等于可调用同一算法。骨架草稿应保留打开时 state，确认不能重取令牌绕过冲突。 状态：已实现，GUI 与 `skeleton_draft_*` 共用意图和会话；GUI 异步打开待手动检查。
- 局部画布显隐与层级：单层/全部/反转/隔离属于每 workspace/canvas/mode 的持久呈现状态，不能改变共享模型或导出，已有多画布回归证明这一边界。需中立 processor 与辅助 CAS/公开控制读回，保留原 v1 presentation 字段。GUI reparent 改为共用结构 journal，继续读取旧 v1 父级覆盖。 状态：已实现 `canvas_visibility / canvas_visibility_get` 与 journal reparent（`space=local`）；深度拆分前层、图片导入与 CMO3 替换的显隐写入已改经辅助 CAS（`c9ef355`、`539b978`）。
- 设置联动：meshOnly、动作及 generatePhysics 开关仍在 GUI 重置参数值或联动其他设置；需中立意图与一致的作者姿态边界。 状态：已实现；字段会话内的开关经记录与重放进入同一意图（`1122a86`、`77270b9`）。
- 物理选中组试听：面板另有 PhysicsEngine、PhysicsDrag、最大输出及重置时钟；现有 preview_physics 未覆盖它。公开拟合需能消费同一实测 peaks。 状态：已实现 `physics_audition*` 与 `physics_fit observed_peaks`，面板摆锤共用同一会话核心。
- 多次输入画布草稿：Warp/Rotation 放置、刀切及路径绘制保存旧局部坐标或顶点索引，却在确认时清除起始 state；应保留首点或放置开始的捕获并拒绝外部修改后的旧草稿。 状态：已实现 `WorkspaceCanvasInputDraft` 并接入 GUI。

其余主要域已核对公开入口及共享候选；主题、布局、快捷键和变形器辅助线呈现按范围排除。入口审计不替代运行时验收。完成上述业务后仍需源码审查、历史重放、归档重开、导出读回、视觉、并发/取消、HTTP 契约与认证，以及桌面窗口手动检查；同一源码的 Windows / Ubuntu 全量已由 `a3fc546` 的 PR CI 取得（不含 Cubism SDK、原生 GL 与桌面窗口），后续源码改动需重新取得。
