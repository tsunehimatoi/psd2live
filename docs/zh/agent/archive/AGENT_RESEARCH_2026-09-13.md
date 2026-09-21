# Agent 同类项目源码对比与优化建议

> 归档资料：本文记录 2026-09-13 的调研，保留当时上下文，不作为当前接口、功能状态或操作教程。现行文档见 [MCP 接口](../MCP_AUTHORING.md)、[设计与验收](../AGENT_DESIGN.md)和[文档目录](../../../README.md)。

> [!NOTE]
> **归档文档**：本文是 2026-09-13 的首轮调研与比较存档，保留原始结论以便溯源。其中的优先级判断与工具命名已被后续文档取代——现行设计见 [Agent 设计](../AGENT_DESIGN.md)，当前可调用接口见 [MCP 接口契约](../MCP_AUTHORING.md)，不要再按本文的排期或接口名执行。

调研日期：2026-09-13。PSD2Live 基线：`959d643bcf6c43440e13e231837d4c15d3dcde02`。

本次结论：PSD2Live 已有较完整的可编辑模型、历史、素材配准和几何工具。当前最值得投入的是把这些工具组合成**有作用域、有候选预演、有缺陷定位、有版本证据的部件级闭环**。外部项目提供了可参考的实现，但本次没有证据证明其中任何一个能稳定完成任意角色的头发拆分或精确变形。

本报告是源码研究及实施建议，未修改产品实现。现有能力以当前代码为准，设计文档和 README 中的历史状态不能替代源码核实。

后续方案收敛见 [Agent 设计](../AGENT_DESIGN.md)，当前可调用契约见 [MCP 接口契约](../MCP_AUTHORING.md)。本文保留首轮比较；后续优先级以这两份文档为准，不再以创建 Warp 代替完整关键形编辑流程。

## 1. 本地资料与验证范围

仓库统一克隆在 `D:/code/live2d/agent-research/`，与本项目并列，未把第三方源码混入本项目 Git。使用 `git clone --depth 1`，设置 `GIT_LFS_SKIP_SMUDGE=1`；未下载大模型权重或 LFS 实体，因此浅克隆不等于完整可运行环境。

| 项目 | 固定提交 | 实际定位 | 本次验证 |
|---|---|---|---|
| [PuppetLoom](https://github.com/CheshireMew/PuppetLoom) | `772b32ffe09f96aa8d7e39a5f73fecb93d2029bd` | PSD 制作、结构化 Agent 规格、确定性部件建模、证据和导出 | 重点源码与测试阅读，未运行完整应用 |
| [StandRig](https://github.com/sayaka-aiart/StandRig) | `6256f55352eb161f1694adfc966ff06ecbfa7a26` | MCP/HTTP 编辑核心、自有模型及播放格式 | core/runtime/service 构建通过；4 个测试文件、15 项测试通过 |
| [cubism-api-bridge](https://github.com/sayaka-aiart/cubism-api-bridge) | `2e85e354852a77db3c8b145db6d73abaef838aed` | StandRig 与 Cubism Editor API 的桥接、同步和执行审计 | 源码与测试阅读，未连接真实 Editor |
| [Iki](https://github.com/zeikar/iki) | `1251412ee428c58229f813e9749b9121e44653ba` | 语义 PNG 合成、自动绑定、MCP、自有 `.iki` 格式 | MCP 测量/合成及自动绑定源码阅读 |
| [See-through](https://github.com/qingkong151/see-through-live2d) | `e4cb250dc69defe6f982168dab684aa461552b5b` | 图层生成、补全、深度估计、PSD 输出 | 推理入口与实现阅读，未跑 GPU 推理 |
| [live2d-automation](https://github.com/J621111/live2d-automation) | `4ad77c555165e1ba02bd68e0db7299d5f4fe0327` | MCP 流水线、检测后端、mock 中间包及外部自动化接口 | 源码及明确的 mock 契约核实 |
| [Bunraku](https://github.com/SparcAI-Inc/Bunraku) | `b9cc79049a50e36b1ba10db6140edb166f500e4e` | 论文项目占位仓库 | 此提交只有 `readme.md`，没有可比较的实现 |
| [Inochi Creator](https://github.com/Inochi2D/inochi-creator) | `dba60811cff224f8cc9ce367b1d9291bfa5f7640` | 补充研究的实际建模编辑器，非 Cubism 格式 | 参数镜像补形、多对象编辑、路径变形源码阅读，未编译运行 |

首轮新增克隆 5 个项目，后续补充 Inochi Creator；连同用户指定的两个，共 8 个仓库，7 个有源码。另尝试 `rin721/img2live2d`，GitHub 搜索仍有索引，但实际 clone 返回 `Repository not found`，未将其当作可用项目。

筛选时排除了只控制既有模型表情/播放的 MCP 项目：它们与“建模 Agent”距离较远。Bunraku 保留作后续研究线索，不把论文网页中的能力当作本地实现。

本地 `repositories.json` 记录 URL、提交、分支及跟踪文件数量；`standrig-selected-tests.log` 保存测试输出。运行命令：

```powershell
cd D:\code\live2d\agent-research\StandRig
npm.cmd ci --ignore-scripts --no-audit --no-fund
npm.cmd run build --workspace=@standrig/core
npm.cmd run build --workspace=@standrig/runtime
npm.cmd run build --workspace=@standrig/service
node --test tests/qa.test.mjs tests/target-constraints.test.mjs tests/write-contract.test.mjs tests/warp-keyform.test.mjs
```

首次仅构建 core 时，两个依赖 service 的测试文件无法加载；补齐 runtime/service 构建后，15 项全部通过。没有把环境准备失败计为项目算法缺陷，也没有把合成 fixture 测试通过解释为真实 PSD 端到端成功。

## 2. 本项目已经有什么，差距在哪里

| 领域 | 已核实的现有能力 | 真正值得补充的部分 |
|---|---|---|
| 工具发现 | `agent_get_workflow` 按任务加载，`rig_list_objects` 分页，`rig_inspect` 摘要/点分页 | 当前任务所需的能力摘要、增量状态和结构化失败分类 |
| 几何修改 | `rig_transform` 一次执行 1～32 个有序操作；选择器、局部范围、sway、landmarks | 把根/梢、目标幅度、保护部位和验收条件组成部件任务，而非再造相同算子 |
| 预演 | `rig_preview` 不推进历史，返回位移及三角形诊断 | 同一个候选跨对象、跨参数姿态渲染，并将 QA 与提交绑定 |
| 多姿态证据 | `view_render_poses`、带映射的 View、渲染日志 | 自动选取最差帧，固定前后相机与版本，建立证据清单 |
| 素材 | reference/import/register/composite、原图保留、锚点配准、独立放置记录 | 将质量问题分为定位、裁断、缺失覆盖、错误内容，避免一律重生图 |
| 覆盖 | `view_check_coverage` 统计所选矩形中透明像素 | 局部语义 mask、连接关系、多姿态/时序覆盖与误报处理 |
| 历史 | HEAD 乐观锁、追加式分支历史、恢复 | 多工具候选事务、请求级重复执行识别、修改影响范围约束 |
| 长任务 | `AgentTaskManager` 保存计划、进度、事件和 artifact ID，可恢复 | 可选的可执行部件规格、成本记录、已验收结果保护；它当前不是执行调度器 |
| 物理 | 自定义物理组、输出参数关键形、工程持久化与导出 | 驱动—释放—回正测试，输出饱和、根部漂移、最差画面关联 |

关键本地依据：

- `src/main/kotlin/io/github/psd2live/agent/AgentMcpService.kt`：工具注册，尤其 `rig_transform`、`view_render_poses`、`rig_preview`。
- `src/main/kotlin/io/github/psd2live/agent/ViewModelAgentWorkspace.kt`：`transformRigGeometry` 要求所有已有几何轴，并保留该坐标的已写通道；`editObjects` 是已有的批量对象编辑。
- `src/main/kotlin/io/github/psd2live/core/RigGeometryTools.kt`：实际几何采样与变换。
- `src/main/kotlin/io/github/psd2live/agent/AgentCoverage.kt`：明确假设矩形内每个像素均应覆盖，不推断发缝或头皮语义。
- `src/main/kotlin/io/github/psd2live/agent/AgentTaskManager.kt`：任务是外部 Agent 的检查点记录。
- `src/main/resources/mcp/workflows/rig-geometry.md`：已有 root-pinned sway 和 landmark 位移插值说明。

README 仍将精确变形列为不可靠，与接口存在并不矛盾。工具可用、工作流可执行、真实任务可靠是三种不同状态。

## 3. 各项目值得借鉴的具体实现

### 3.1 PuppetLoom：把自然语言收敛成可执行部件规格

`ModelAgentSpecification` 包含 `baseRevision`、`scope`、`goal`、`anatomy`、`parts`；不同部件分别表达 amplitude/response/stability、发束 lag/damping、衣物结构等。校验器检查范围和占位内容，不把自然语言直接变成任意脚本。Agent 决定目标，程序执行确定性提案。

前发实现会检测发根连续性，并尝试保护风险点或降低幅度。证据模块自动选取局部区域、生成前后对照和连续运动图，产物附 SHA-256。值得借鉴的是**有限参数 → 算法提案 → 几何检查 → 同版本证据**这一完整路径。

限制：降低形变幅度可能让几何检查通过，却没有达到用户要求的运动范围。本项目应同时记录 `requestedRange` 和 `achievedRange`，不足时返回部分完成或缺素材，不能静默把需求缩小。

也不能把它的 Cubism 导出当作完全独立的竞争实现：其第三方说明明确指出，原生导出使用固定 PSD2Live 0.4.0 分发中的 Umamo 库，且部分图集/物理实现来自 PSD2Live。这使 Agent 组织方式比“换导出器”更有借鉴价值。

源码证据：[结构化规格](https://github.com/CheshireMew/PuppetLoom/blob/772b32ffe09f96aa8d7e39a5f73fecb93d2029bd/packages/core/src/agent-spec.ts)、[前发提案及修复](https://github.com/CheshireMew/PuppetLoom/blob/772b32ffe09f96aa8d7e39a5f73fecb93d2029bd/packages/core/src/front-hair-agent.ts)、[证据生成](https://github.com/CheshireMew/PuppetLoom/blob/772b32ffe09f96aa8d7e39a5f73fecb93d2029bd/packages/core/src/agent-evidence.ts)、[来源声明](https://github.com/CheshireMew/PuppetLoom/blob/772b32ffe09f96aa8d7e39a5f73fecb93d2029bd/THIRD_PARTY_NOTICES.md)。

### 3.2 StandRig：把诊断、候选事务、工具契约放进服务端

最适合借鉴的四点：

1. `qaCheck.ts` 返回数值条目、失败姿态/区域和后续图片请求。`qaFailureImage.ts` 再生成前/后/差异图，减少重复传全身图片。
2. `modelingTarget.ts` 对多个选择条件取交集；按语义 role 选取时要求 confirmed；空选择不等于全选。已运行的测试验证无关部件不被修改。
3. `modelingRoutes.ts` 在 candidate 上执行操作、结构检查、物理检查和可选渲染 QA，再验证版本并保存。不同路由约束不完全相同，不能概括成“所有编辑都强制视觉通过”。
4. `physicsSettlingQa.ts` 驱动到极值再释放，输出峰值、0.5/1 秒幅度、残余量、回正帧与反转次数。这比只有静态端点截图更适合判断“回弹再小一点”。

它还实现 `exposureQa.ts`、`joinQa.ts`，分别扫描露底和部件连接；因此不应把它的 QA 简化为仅有全图覆盖率。但这些仍依赖区域、阈值和语义设置，不能自动理解所有交叉头发。

重要边界：通用 `runQaCheck` 默认只测 neutral；默认覆盖率下限很低，测试者必须显式给出任务姿态/区域。其 AGENTS.md 要求保存视觉目标和视觉审查，同时最后明确**服务端不会自动验证这些参考文件**。文档规范不等于强制执行机制。

关键点求解器以头部代理与离散姿态候选为基础，拟合带正则化的轴向平移/缩放并构造 mesh offsets；关键点仍由调用方给出。不能把它描述为通用图像匹配或完整非刚性逆向绑定求解器。

源码证据：[QA](https://github.com/sayaka-aiart/StandRig/blob/6256f55352eb161f1694adfc966ff06ecbfa7a26/packages/core/src/qaCheck.ts)、[事务路由](https://github.com/sayaka-aiart/StandRig/blob/6256f55352eb161f1694adfc966ff06ecbfa7a26/apps/service/src/routes/modelingRoutes.ts)、[目标选择](https://github.com/sayaka-aiart/StandRig/blob/6256f55352eb161f1694adfc966ff06ecbfa7a26/packages/core/src/modelingTarget.ts)、[物理回正](https://github.com/sayaka-aiart/StandRig/blob/6256f55352eb161f1694adfc966ff06ecbfa7a26/packages/core/src/physicsSettlingQa.ts)、[关键点求解](https://github.com/sayaka-aiart/StandRig/blob/6256f55352eb161f1694adfc966ff06ecbfa7a26/packages/core/src/correspondenceSolver.ts)、[操作规范的实现边界](https://github.com/sayaka-aiart/StandRig/blob/6256f55352eb161f1694adfc966ff06ecbfa7a26/AGENTS.md)。

### 3.3 Iki：把反复失败的视觉问题变成可测量指标

`compose.ts` 将生成部件裁透明边、缩放、镜像并按 layout 放到公共画布；调整位置可以重合成，无需重新生成图像。本项目已有更通用的 registration/composite，应扩展其诊断而不是替换掉。

`measure.ts` 测量 alpha 内容范围、质量中心、边缘不透明比例、内部长直切口、虹膜/眼白比例和睫毛偏移。文件注释明确记录实际失败如何转化成指标，以及早期过严阈值如何导致正确素材被反复重生成。

这给本项目一个很实用的方向：`asset_inspect` 不只报透明度，应返回“疑似裁断在第几行”“哪个眼部配对偏移”“建议先调 registration 还是补画”。

限制：默认布局假设正面居中角色，虹膜比例、3px/10px 等阈值有明显画风和分辨率依赖。移植思路时改为按角色基线/局部尺度归一化的 warning，并允许正常不对称；不要作为统一硬闸门。它输出自有 `.iki`，不是 Cubism `.moc3` 导出的替代品。

源码证据：[测量](https://github.com/zeikar/iki/blob/1251412ee428c58229f813e9749b9121e44653ba/packages/mcp/src/measure.ts)、[合成](https://github.com/zeikar/iki/blob/1251412ee428c58229f813e9749b9121e44653ba/packages/mcp/src/compose.ts)、[语义角色与自动绑定](https://github.com/zeikar/iki/blob/1251412ee428c58229f813e9749b9121e44653ba/packages/editor/src/auto-rig.ts)。

### 3.4 See-through：上游素材分解后端，不是完整建模 Agent

推理入口按序调用 LayerDiff、Marigold 和进一步图层提取，参数包含 seed、模型标识、分辨率和步数。实现保存部件图与深度信息，并组织 PSD 输出。它与“让通用图像生成器凭描述逐束画发”是不同的上游路线，值得作为候选后端做小规模比较。

建议只先实验“原整片头发 → 语义层候选 → 原参考框配准 → 当前 composite 验收”。生成失败时保留原图、模型版本、seed 和候选，不推进正式历史。深度结果用于建议遮挡关系，出现局部前后互换时仍需分段或 mask 表达。

限制：语义类别分解不等于任意自然发束拆分；遮挡补全的风格、边缘、细小饰品和运动余量需要本项目验收。本次没有下载权重或运行推理，不声称它会比现有生图链路更准确或更便宜。

源码证据：[推理入口](https://github.com/qingkong151/see-through-live2d/blob/e4cb250dc69defe6f982168dab684aa461552b5b/inference/scripts/inference_psd.py)、[实际推理/深度/提取逻辑](https://github.com/qingkong151/see-through-live2d/blob/e4cb250dc69defe6f982168dab684aa461552b5b/common/utils/inference_utils.py)。

### 3.5 cubism-api-bridge：明确区分已提交、已验证与状态未知

执行状态区分 `committed_unverified`、`committed_verified`、`verification_failed`、`state_unknown`；同步计划区分冲突、绑定缺失、能力不足、前置条件和依赖阻塞。还区分真实 Editor 验证、官方契约验证与 mock 验证。

PSD2Live 已要求 HEAD 校验及超时后查历史；增量价值是把这套恢复规则从提示词提升为结构化执行结果。例如超时返回请求 ID，后续查询明确是否已提交、提交在哪个 HEAD、验证是否完成，减少 Agent 盲目重复写入。

长期若做外部 Editor 双向修改，可参考三方快照比较与稳定绑定注册。当前应先改善本地执行结果，不必立即引入完整双向同步架构。此桥接的存在也不能证明 StandRig 的全部形变可无损导入 Cubism。

源码证据：[执行状态](https://github.com/sayaka-aiart/cubism-api-bridge/blob/2e85e354852a77db3c8b145db6d73abaef838aed/src/execution/execution-artifacts.ts)、[同步计划](https://github.com/sayaka-aiart/cubism-api-bridge/blob/2e85e354852a77db3c8b145db6d73abaef838aed/src/sync/sync-edit-plan.ts)、[未知状态测试](https://github.com/sayaka-aiart/cubism-api-bridge/blob/2e85e354852a77db3c8b145db6d73abaef838aed/tests/unit/state-unknown.test.ts)。

### 3.6 live2d-automation：学习失败说明，不学习其“完成”表象

README 明确将默认输出称为 mock intermediate package；`pipeline_services.py` 也明确相同边界。检测步骤贯穿返回 `detector_used`、`fallback_reason`、`confidence_summary`，可以知道到底跑了哪个后端及为何回退。

可用于改进本项目的素材/语义识别结果：记录真实后端和回退原因，区分测量可信度与审美判断。不应将“流程所有步骤执行完成”展示为“模型已可用”，也不能只凭存在 `.moc3` 文件就算导出成功。

源码证据：[流水线元数据](https://github.com/J621111/live2d-automation/blob/4ad77c555165e1ba02bd68e0db7299d5f4fe0327/mcp_server/pipeline_services.py)、[项目边界](https://github.com/J621111/live2d-automation/blob/4ad77c555165e1ba02bd68e0db7299d5f4fe0327/README.md)。

## 4. 优先级与落地位置

下面的名称均为**建议接口/组件名，当前不存在的部分不应直接调用**。改动规模是相对估计，不是工期承诺。

| 优先级 | 改动 | 现有落点 | 预期收益 | 主要验收 |
|---|---|---|---|---|
| P0 | 统一 `QaReport` 与失败区域证据 | `AgentCoverage`、`AgentViewRenderer`、`AgentMcpService` | 让修正有明确对象，减少全图反复检查 | 错误姿态/区域可复现；正常发缝不误判 |
| P0 | 单束头发高层提案 `rig_propose_sway` | `RigGeometryTools`、现有 Warp/参数/物理写入 | 用少量语义参数组织现有算子 | 根部稳定、达到要求摆幅、无关对象不变 |
| P0 | 候选跨工具事务与验收后单次提交 | `ViewModelAgentWorkspace`、历史持久化 | 避免半建好的参数/Warp/物理停留在正式结果 | 中途失败不推进 HEAD；旧候选不可提交 |
| P1 | 语义范围与已验收部位保护 | `AgentWorkspaceDocument`、rig 对象查询 | 避免改头发损坏眼口 | 越界影响可发现；显式扩大范围后才能覆盖保护项 |
| P1 | 素材裁断/配对/接缝诊断 | `AgentAssetTools`、`AgentPlacementValidation`、`AgentAlphaCleanup` | 优先用几何修复代替重生图 | 位置误差与缺失像素正确区分 |
| P1 | 物理时序 QA | 物理求解器、`AgentViewRenderer` | 能回答回正、过冲、脱根问题 | 多 FPS 同一输入轨迹，可定位最差帧 |
| P1 | 增量 context 与执行回执 | `AgentMcpService`、历史、`AgentTaskManager` | 减少重新读工程和不确定重试 | 分支切换/日志缺口要求 resync，重复请求不重复编辑 |
| P1 | Agent 基准集与成本日志 | examples、独立 eval harness | 用成功率及代价指导开发 | 固定任务、模型/宿主、完整失败记录 |
| P2 | See-through 可选候选生成后端 | 独立 worker + 现有 asset 契约 | 验证专用分解是否改善困难素材 | 对同一输入做质量/耗时/显存比较 |
| P2 | 经验证配方库与外部 Editor 对账 | 新配方存储、现有历史/导出 | 复用已成功经验和外部精修 | 跨角色验证通过，不能仅凭单样例入库 |

### 4.1 推荐先做的一个完整纵向切片

任务：**对已经独立成层的一束侧发建立独立摆动，保持发根固定、继承头部运动，并验证效果。** 先消除生图变量，更容易判断 Agent 交互设计是否真的进步。

1. 读取 HEAD、目标 Mesh/父 Warp、几何轴、根/梢锚点和保护对象；首次歧义记录为未解决，而非凭名称强行绑定。
2. 提案包含 source HEAD、目标 ID、root/tip、期望角度、root pin、摆幅/响应偏好、验证姿态与保护范围。
3. 服务端复用现有算子构建参数、Warp、两端 keyform 和物理组。先在候选快照中完成，不逐工具推进正式 HEAD。
4. 几何诊断覆盖中立、左右摆端、头角组合和中间插值；物理检查执行驱动—释放轨迹。检查未修改对象的数据与相关姿态表现。
5. 返回数值摘要、最差帧及固定相机的前后图。数值通过后仍需要观察正常观看尺寸的整体效果。
6. 接受候选时核对 HEAD/计划哈希，单次提交。无法达到要求幅度则返回实际幅度与原因，保留可用候选。

初版不需要通用自主调度器。用明确的小范围 domain command 复用现有后端，先测试是否减少工具调用和重试。

### 4.2 QA 契约应当如何设计

建议报告记录：`sourceHead`、`candidateHash`、`targetIds`、`pose/time`、`camera`、`renderer`、`metric`、`thresholdSource`、`severity`、`region`、`evidenceId`、`suggestedRepairClass`。

修复类别至少分成 placement / hierarchy / coverage / geometry / physics / artwork / unknown。`suggestedRepairClass` 是诊断建议，不是自动根因保证。

覆盖率改进不能只把矩形换成一个固定 mask：预期覆盖区域应与姿态、遮挡者和连接区关联。背景、正常轮廓、合法发缝需要排除；隐藏发束的重叠是必要补全，不能判为重复。允许人工或 Agent 给出语义假设，并把该假设保存到证据里。

几何自交、非有限值、无效拓扑可以硬失败；小色差、风格比例、遮挡下的轮廓差异应作为 warning。候选仍需视觉比较，不能用“所有指标绿灯”代替造型质量。

证据缓存键至少包含 HEAD/候选内容、素材内容哈希、姿态/物理时序、相机/裁切、渲染后端与版本。StandRig 通用 QA 的缓存直接哈希 rig/request，这个模式不能原样用于存在外部纹理变化的场景。

### 4.3 成本和停止条件

任务日志增加工具调用数、图像调用数、输入/输出图像字节、耗时、失败类型和最佳候选。模型 token/费用由宿主提供；拿不到时记 `unknown`，不能由 MCP 凭空推算。

预算按任务与用户目标配置，不重新引入“固定修两次必停”。连续修改没有改善时，先换根因假设或确定性修正方式；素材没错时不再次生图。预算耗尽保留最佳候选及未完成项。

增量状态参考 StandRig 的 `changes?since=`，但基于本项目持久化分支历史实现：跨分支、历史不可达、工程替换或缓存缺口都应明确 `resyncRequired`，不能把任何 HEAD 差异当作线性追加。

## 5. 三类用户任务的策略变化

| 任务 | 当前常见困难 | 建议闭环 |
|---|---|---|
| 添加发卡 | 生图后位置/朝向或遮挡不对 | 保留当前 reference/register/composite；新增候选多姿态试拼，先修位置和父级，再考虑重生图 |
| 拆三束刘海 | 可见轮廓切割与隐藏补全混淆，反复生成 | 先保存局部遮挡关系及运动覆盖目标；逐束候选导入，整体试拼；仅为确实缺少像素的束补画 |
| 调眼口形变 | 需要精确点操作但缺少可解释目标 | 保存角色关键点和保护范围；现有 landmarks/curve 提案；端点+中间值+组合姿态比较，不用统一眼型阈值硬套 |

头发遮挡关系应能表达同一逻辑发束的多个绘制段。不同区域前后顺序互换，单一全局 z-order 不够；这是素材/拓扑表达问题，不能期待增加 prompt 长度解决。

## 6. 如何验证优化真的有效

先使用本项目 `examples/tml` 与 `examples/ds` 作探索样本，再增加不同画风、倾斜角度、分层质量的有授权 PSD。两个样本不能支撑通用可靠性声明。

第一轮建议固定 4 类任务：发卡添加、已有发束独立摆动、指定眼部轻微修形、故意中断后的任务恢复。困难的三束刘海分离单独成组，避免把图像生成能力与工具编排能力混为一个分数。

比较条件：同输入、同初始工程、同用户目标、同模型/宿主/图像后端；记录版本和可固定的 seed。基线用当前工作流，实验组加新闭环，每条件重复多次；3 次可用于发现问题，不能据此宣称统计显著。

记录结果：

- 完成率与人工修复时间；预期运动范围是否实现。
- 根部位移/局部尺度、翻面与退化数量、连接区缺口的最差面积和持续帧数。
- 物理回正时间、过冲、输出饱和及不同 FPS 差异。
- 未授权对象/通道是否变化；断线重试是否产生重复编辑。
- 工具调用数、生图次数、耗时、token/费用（可得时）和放弃原因。
- 盲看前后图的造型一致性、运动自然度；保存失败和部分完成，不能只保留成功演示。

阶段成功的标准应是：相同任务下可靠性提高，或质量相当但成本下降，并且不牺牲要求的摆幅/角度。具体阈值在基线测量后确定；本次没有进行真实 Agent A/B 评测，因此不提供虚构的节省百分比。

## 7. 复用边界

本次只记录仓库自身许可文件/声明：PuppetLoom 当前为 AGPL-3.0-or-later；StandRig 主项目 Apache-2.0；cubism-api-bridge 和 Iki 为 MIT；See-through 代码 LICENSE 为 Apache-2.0；live2d-automation 的 pyproject 声明 MIT，但根目录未发现独立 LICENSE；Bunraku 当前只有 README，未发现许可文件。模型权重和示例美术需分别核对来源，不能从代码 LICENSE 推导。

本报告建议复用机制并在 PSD2Live 现有 Kotlin/Umamo 后端实现。若后续直接搬运源码，应另行核对对应文件的许可、来源和兼容要求；本次未复制第三方产品代码。

首轮以单束头发候选闭环为起点；后续深读发现需优先接通关键形目的地、多对象编辑与参数组合观察。当前开发顺序以 [Agent 设计](../AGENT_DESIGN.md) 为准。
