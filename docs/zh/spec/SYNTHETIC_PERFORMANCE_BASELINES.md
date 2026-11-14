# 合成性能观测与缓存边界

本页说明开发工具和内部缓存契约，不代表性能验收已通过。工具只用程序生成的输入，输出位于 `build/tools/`。

## 测量入口

继续使用 `RigBuildProfile`。原有阶段汇总和 legacy 模板计数保留；`measure` / `measureSuspending` 返回单次操作样本，关联任务、起始修订、候选、场景和模型规模。挂起构建传播同一协程上下文，worker executor 显式传播样本。profiler 不持有历史样本或模型结果。

worker 记录排队、输入序列化、便携模型转换、请求编码、写入、读取与 worker 等待、响应解码与应用，以及几何命中、失效、淘汰和传输载荷字节。`read and worker` 是合并阶段，不应称为纯算法时间；载荷字节不包括协议 framing。嵌套阶段不能直接相加当作总耗时。

`SyntheticWorkerBaselineTool` 是协议 2 的真实子进程微基准。三档规模为 4×64、16×256、64×1024（网格数×每网格顶点数）。每档分别记录新 worker 首次请求、5 次预热、至少 30 次热请求与单字段变更、至少 1,000 次几何变更，以及 5 次 worker 关闭重开。使用实际 worker 几何缓存并断言字节预算，最后一个租约释放后断言进程池中不再保留该 worker。

`SyntheticWorkspaceBaselineTool` 是无窗口工作区工具，经真实 PSD 导入、文档命令、候选重建、CAS、历史、归档和导出。三档为 4×64²、16×128²、64×256² 的合成图层；实际输出网格／顶点数写入样本。每档记录导入、预热、至少 30 次字段修改、拓扑编辑与撤销重做、至少 1,000 次正式编辑、保存、5 次关闭重开及 CMO3 导出。保留所有正式编辑及拓扑分支，不丢弃历史来降低内存。

PowerShell 运行示例（仅在没有其他构建和测量竞争时运行）：

```powershell
$env:PSD2LIVE_TOOLS = '1'
$env:PSD2LIVE_BASELINE_SAMPLES = '30'
$env:PSD2LIVE_BASELINE_EDITS = '1000'
.\gradlew.bat test --tests '*SyntheticWorkerBaselineTool' --max-workers=2 '-Ppsd2live.testForks=1'
.\gradlew.bat test --tests '*SyntheticWorkspaceBaselineTool' --max-workers=2 '-Ppsd2live.testForks=1'
```

每个工具写 `samples.jsonl`（每次立即落盘）与 `summary.json`（样本数、中位数、P95、最大值和大于中位数三倍的样本）。报告记录 JDK、操作系统、处理器数量、最大堆、输入或插件 digest。当前冷样本是同 JVM 的新会话／worker，不是操作系统冷缓存或 JVM 冷启动；需要进程冷启动数据时单独重启测试进程。

仅检查工具流程时可设 `PSD2LIVE_BASELINE_SMOKE=1`，只跑小档、1 次交互样本和 3 次压力编辑，输出目录带 `-smoke` 且 `acceptance_run=false`。它不满足 30／1,000 次验收要求；正式测量前应删除该环境变量。

## 内存和所有权

`ModifierGeometryCache` 是 identity LRU。默认每 worker 32 MiB，协议 1 与协议 2 只启用对应一种表示。记账包含强引用 key 的 positions、UV、indices 缓冲，编码 value 和保守对象开销。它是可解释的保留量估计，不是 JVM 对象布局的精确测量。超过预算的单条数据只服务当前请求，不进入缓存；淘汰仅移除引用，不清零或修改活动请求的数组。

上一请求的模型和协议增量 blob 是单独的同步状态，`protocolBlobBytes` 单独报告；读取结果缓存保留至多 256 条。它们不包含在几何预算中，不能把几何预算描述成 worker 总内存上限。协议 1 的资源文件是磁盘数据，也不包含在内存预算中。

显式 `releaseCaches(store)` 在请求边界清除几何、读取结果和协议同步状态，下次发送完整 reset。这个维护入口不是工作区关闭路径。工作区使用 `ModifierBuildCacheOwner`：可变 mesh cache 与 worker Session 属于 owner，已发布模型和求值快照属于捕获读者。工作区换工程、关闭和 Host 退出释放所登记的 owner；仍存活的 builder 清除可重算输入引用，已捕获模型不变。未发布的失败构建释放自己的 owner；遗弃候选的 Session 另有 Cleaner 兜底，不能将 GC 清理当作正常关闭契约。

worker Session 与包引用是两种所有权。关闭一个 Session 立即拒绝新请求，随后在后台放弃它自己的租约；其他 Session 或活动请求仍保有 worker。最后一个 Session 释放且活动请求完成后，进程交由 `WorkerRetirementQueue` 回收。进程退出、后代进程等待和资源目录清理不在工作区发布锁或进程池 key 锁内执行。

`retirementSnapshots(store)` 报告 pending/running/failed、尝试数与错误；失败保留待回收对象，`retryRetirements(store)` 可重试，不将失败冒充释放成功。工具可显式调用 `Session.awaitReleased()`、`awaitRetirements(store)` 验证实际退出，产品的关闭和发布路径不等待。兼容的无 Session 调用仍按进程生命周期保留；包卸载／禁用继续使用同步 `stop(reference)`，确保包文件句柄已释放。

## 当前测量边界

- JVM heap、committed heap、non-heap 与显式缓存计数分别记录；目前不采集操作系统 RSS、worker 子进程堆或 retained-object heap dump。
- 工作区工具保留历史，预期历史必需数据增长；关闭重开时故意持有一个捕获模型验证读取，再在下一次样本释放它。一次 GC 后的堆大小不作为泄漏判据。
- 当前工作区工具使用空修改器栈。部件折叠／展开对照、栈重排、纯子算法耗时与 GUI 首次正确呈现尚未覆盖，不能据本工具宣布整个 B1 验收完成。
- 开发工具通过编译与常规单元回归，不等于已运行 30／1,000 次测量；是否实际运行及结果应由验收记录单独说明。
