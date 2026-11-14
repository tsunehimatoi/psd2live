# 合成性能观测与缓存边界

本页说明开发工具和内部缓存契约。工具只用程序生成的输入，输出位于 `build/tools/`；正式运行及实测边界见 [2026-11-14 验收记录](../STATUS.md#2026-11-14-架构优化验收)。

## 测量入口

继续使用 `RigBuildProfile`。原有阶段汇总和 legacy 模板计数保留；`measure` / `measureSuspending` 返回单次操作样本，关联任务、起始修订、候选、场景和模型规模。挂起构建传播同一协程上下文，worker executor 显式传播样本。profiler 不持有历史样本或模型结果。

`WorkspaceEditObservation.subscribe` 为开发测量按需启用真实编辑观察：公开批量入口在等待编辑锁前记录请求，隔离候选的每次构建携带同一 editId 和递增 ordinal，CAS 后记录最终采用的 state/revision。候选 revision 与采用 revision 分别保存。观察结果不进入文档、历史或缓存键；回调在提交锁和宿主编辑锁释放后发送，失败或取消不能伪造成功发布。`WorkspaceCapture.observation` 经严格模型身份和加载代次校验传给呈现令牌，窗口工具再核对 GPU 完成及实际像素。详见[首次正确帧测量](../guide/FIRST_CORRECT_FRAME_MEASUREMENT.md#真实公开编辑请求到窗口正确帧)。

`algorithm-api` 的 `ModifierAlgorithmStages` 只提供同步阶段回调，不聚合数据，也不引用宿主。宿主开启 `RigBuildProfile.recording` 后将阶段归入同一 Operation，关闭则移除回调；禁用路径不采时。观察器失败不改变算法的返回值、异常或取消。真实 worker 不安装该宿主回调，本轮不增加计时 wire 字段。

三部件阶段名统一为 `algorithm stage: part <eye|brow|mouth> <base|sub>: <algorithm-type>`，例如 `algorithm stage: part eye sub: eye.wide`；计数表示实际执行次数，求值缓存命中而未调用算法时不会产生该阶段。

worker 记录排队、输入序列化、便携模型转换、请求编码、写入、读取与 worker 等待、响应解码与应用，以及几何命中、失效、淘汰和传输载荷字节。`read and worker` 是合并阶段，不应称为纯算法时间；载荷字节不包括协议 framing。嵌套阶段不能直接相加当作总耗时。

`SyntheticWorkerBaselineTool` 是协议 2 的真实子进程微基准。三档规模为 4×64、16×256、64×1024（网格数×每网格顶点数）。每档分别记录新 worker 首次请求、5 次预热、至少 30 次热请求与单字段变更、至少 1,000 次几何变更，以及 5 次 worker 关闭重开。使用实际 worker 几何缓存并断言字节预算，最后一个租约释放后断言进程池中不再保留该 worker。

`SyntheticWorkspaceBaselineTool` 是无窗口工作区工具，经真实 PSD 导入、文档命令、候选重建、CAS、历史、归档和导出。三档为 4×64²、16×128²、64×256² 的合成图层；实际输出网格／顶点数写入样本。每档记录导入、预热、至少 30 次字段修改、拓扑编辑与撤销重做、两个 motion 修改器的至少 30 次重排、至少 1,000 次正式编辑、保存、5 次关闭重开及 CMO3 导出。重排与冷重建逐次比较完整 RigIR。保留所有正式编辑及拓扑分支，不丢弃历史来降低内存；每次重开恢复完整 history、辅助状态，断言节点元数据、HEAD 和叶分支身份不变。解压目录在其惰性历史仍被工作区持有期间保持有效。

`SyntheticPartBaselineTool` 复用合成眼、眉、嘴输入和部件等价验收设计，以 meshSpacing 40／20／10 形成三档并报告实际网格／顶点数。每档对眼睁大、眉形、嘴描边记录冷执行、预热、至少 30 次热命中和至少 30 次单个子配置变化，逐次比较展开栈、折叠栈和真实 worker 的完整 RigIR，并与冷重放对照。夹具使用固定原子实例 ID，不删除、归一化或忽略 ID／顺序／引用／资源来获取等价结果。工具须在 A5 的身份与子配置修复基线上运行；编译通过不代表旧基线具备这些语义保证。

PowerShell 运行示例（仅在没有其他构建和测量竞争时运行）：

```powershell
$env:PSD2LIVE_TOOLS = '1'
$env:PSD2LIVE_BASELINE_SAMPLES = '30'
$env:PSD2LIVE_BASELINE_EDITS = '1000'
.\gradlew.bat test --tests '*SyntheticWorkerBaselineTool' --max-workers=2 '-Ppsd2live.testForks=1'
.\gradlew.bat test --tests '*SyntheticWorkspaceBaselineTool' --max-workers=2 '-Ppsd2live.testForks=1'
.\gradlew.bat test --tests '*SyntheticPartBaselineTool' --max-workers=2 '-Ppsd2live.testForks=1'
```

每个工具写 `samples.jsonl`（每次立即落盘）与 `summary.json`（样本数、中位数、P95、最大值和大于中位数三倍的样本）。报告记录 JDK、操作系统、处理器数量、最大堆、输入或插件 digest。当前冷样本是同 JVM 的新会话／worker，不是操作系统冷缓存或 JVM 冷启动；需要进程冷启动数据时单独重启测试进程。

中位数在奇数样本时取中间值，偶数时取两个中间值的算术均值；P95 使用 nearest-rank（排序后第 `ceil(n × 0.95)` 个值）。异常值阈值复用同一中位数，统计规则同时写入 metadata。

报告默认 `complete=false`，启动时立即覆盖旧的成功状态。只有所有场景及关闭释放校验通过才写 `complete=true` 和分位数汇总；失败或未完成时保留已写样本、样本数、最后场景及可捕获的错误上下文，不输出 `scenarios` 成功汇总。`acceptance_run=true` 仅说明使用正式采样配置，不能代替 `complete=true`。

`memory.jsonl` 单独记录阶段边界及每 100 次压力编辑后的宿主 JVM／已知 worker 进程快照，不放入操作计时块；每次快照批量采样所有 PID，另记采样耗时。Windows 通过系统 PowerShell 读取 `WorkingSet64` 和 `PrivateMemorySize64`（私有提交量）；Linux 读取 `/proc/PID/status` 的 `VmRSS` 和 `smaps_rollup` 私有驻留量，两平台的 private 指标不可直接等同。其他平台或读取失败显式写 `unavailable` 与空数值，存活状态仍由 `ProcessHandle` 检查。正常结束在释放后验证记录过的 worker 已退出；失败诊断写入后才抛错。采样器开销虽不进入延迟样本，仍可能影响后续执行；它不是零扰动测量。

仅检查工具流程时可设 `PSD2LIVE_BASELINE_SMOKE=1`，只跑小档、1 次交互样本和 3 次压力编辑，输出目录带 `-smoke` 且 `acceptance_run=false`。它不满足 30／1,000 次验收要求；正式测量前应删除该环境变量。

## 构建计划与两类试点

`WorkspaceBuildInputs` 捕获同一文档、配置、插件目录修订、语言及原始源坐标执行选项。`WorkspaceBuildPlanner` 仅为两类编辑授权检查已有缓存：一个部件子配置中单个 `values`／`tuning` 数值变化，以及在原栈末尾追加启用的 `mesh.split` v1。类型、版本、精确包身份和输入读取契约均须在已审查范围内；目标、依赖、资源设置或其他文档内容同时变化时不进入试点。

计划中的 `CHECK_EXISTING_KEY` 表示允许核对已有缓存的完整键和前置条件，不表示直接跳过执行。试点保留未变化的修改器前缀，失效修改项及其后继，并重新处理图集和目标产物；它不交换修改器顺序。候选使用独立 pipeline，失败不替换已发布快照。普通编辑仍走既有 pipeline 和原有缓存检查，不因未纳入试点而全量清空缓存。显式关闭缓存使用新构建状态，作为语义对照。

`WorkspacePartBuildPlanTool` 使用实际嘴部下颌子配置变化，比较完整 IR、打包 PNG 和规范 RGBA；`WorkspaceTopologyBuildPlanTool` 使用真实连通块拆分，包含遮罩、Glue、路径、顶点组及纹理来源。每次测量都核验缓存开启与关闭结果一致；正式模式交替两种执行顺序，先完成 5 对不计入统计的预热，再记录至少 30 对样本。输出分别为 `build/tools/workspace-part-build-plan/report.json` 和 `build/tools/workspace-topology-build-plan/report.json`。

```powershell
$env:PSD2LIVE_TOOLS = '1'
$env:PSD2LIVE_BUILD_PLAN_FORMAL = '1'
$env:PSD2LIVE_BUILD_PLAN_BASELINE = (git rev-parse HEAD).Trim()
.\gradlew.bat :test --tests '*WorkspacePartBuildPlanTool' --max-workers=2 '-Ppsd2live.testForks=1'
.\gradlew.bat :test --tests '*WorkspaceTopologyBuildPlanTool' --max-workers=2 '-Ppsd2live.testForks=1'
```

正式模式要求运行前后 HEAD 相同、工作区无受跟踪修改及未跟踪文件，报告必须同时满足 `complete=true` 和 `performance_acceptance=true`。默认模式仅执行 1 对正确性检查，不是性能证据。比较对象是当前实现的缓存开启／关闭，不是重构前的旧实现；结果不能用于声称相对旧版本的提速幅度，也不包含窗口呈现。

## 内存和所有权

`ModifierGeometryCache` 是 identity LRU。默认每 worker 32 MiB，协议 1 与协议 2 只启用对应一种表示。记账包含强引用 key 的 positions、UV、indices 缓冲，编码 value 和保守对象开销。它是可解释的保留量估计，不是 JVM 对象布局的精确测量。超过预算的单条数据只服务当前请求，不进入缓存；淘汰仅移除引用，不清零或修改活动请求的数组。

上一请求的模型和协议增量 blob 是单独的同步状态，`protocolBlobBytes` 单独报告；读取结果缓存保留至多 256 条。它们不包含在几何预算中，不能把几何预算描述成 worker 总内存上限。协议 1 的资源文件是磁盘数据，也不包含在内存预算中。

显式 `releaseCaches(store)` 在请求边界清除几何、读取结果和协议同步状态，下次发送完整 reset。这个维护入口不是工作区关闭路径。工作区使用 `ModifierBuildCacheOwner`：可变 mesh cache 与 worker Session 属于 owner，已发布模型和求值快照属于捕获读者。工作区换工程、关闭和 Host 退出释放所登记的 owner；仍存活的 builder 清除可重算输入引用，已捕获模型不变。未发布的失败构建释放自己的 owner；遗弃候选的 Session 另有 Cleaner 兜底，不能将 GC 清理当作正常关闭契约。

worker Session 与包引用是两种所有权。关闭一个 Session 立即拒绝新请求，随后在后台放弃它自己的租约；其他 Session 或活动请求仍保有 worker。最后一个 Session 释放且活动请求完成后，进程交由 `WorkerRetirementQueue` 回收。进程退出、后代进程等待和资源目录清理不在工作区发布锁或进程池 key 锁内执行。

`retirementSnapshots(store)` 报告 pending/running/failed、尝试数与错误；失败保留待回收对象，`retryRetirements(store)` 可重试，不将失败冒充释放成功。工具可显式调用 `Session.awaitReleased()`、`awaitRetirements(store)` 验证实际退出，产品的关闭和发布路径不等待。兼容的无 Session 调用仍按进程生命周期保留；包卸载／禁用继续使用同步 `stop(reference)`，确保包文件句柄已释放。

JVM 退出同时处理 live pool 与退休队列保留的条目。shutdown 先请求终止进程及已记录的后代，再用独立 daemon 平台线程回收资源，等待窗口为 5 秒；超过窗口或失败的 PID／状态／错误写到 stderr。它不依赖普通虚拟线程继续保活，也不将超时清理报告为成功。

## 当前测量边界

- JVM heap、committed heap、non-heap 与显式缓存计数分别记录；工具层另采 OS 进程内存，不能由几何预算推断 RSS／Working Set 有界。尚不采 worker 子进程堆或 retained-object heap dump；正式运行的 OS 采样证据见上述验收记录，不能由此声称任意工作负载的总内存有界。
- 工作区工具保留历史，预期历史必需数据增长；关闭重开时故意持有一个捕获模型验证读取，再在下一次样本释放它。一次 GC 后的堆大小不作为泄漏判据。
- 眼、眉、嘴折叠部件的进程内 base/sub 埋点计量 `changes` 调用，不包括应用变化与最终组合；展开栈阶段与折叠总阶段仍保留。真实 worker 不通过协议返回内部阶段时间，需与宿主端到端结果联合解读。GUI 真实编辑工具已通过独立票据关联到窗口回执与像素；没有这份关联的激活或离屏样本仍不能替代完整编辑延迟。
- 开发工具通过编译与常规单元回归，不等于已运行 30／1,000 次测量；是否实际运行及结果应由验收记录单独说明。
