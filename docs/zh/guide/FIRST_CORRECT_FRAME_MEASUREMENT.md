# 首个正确画面的测量边界

`FirstCorrectFrameTool` 测量的是**捕获修订到离屏编辑画布 GPU 读回完成**，不是桌面窗口呈现延迟。工具已提供，性能采样须等待独占窗口；没有采样数据时不能据此声称性能达标。

## 现有路径与可用测量点

- `PreviewRenderer.composite` 是源图合成及其缓存，不代表绑定后模型的显示。
- 编辑视口通过 `CanvasScene`、`ArtworkDrawList` 和 `GlCanvasRenderer` 绘制。工具复用这些实现，并调用已有的 `GlCanvasRenderer.render` 离屏入口；该入口以 `glReadPixels` 同步 GPU 后返回位图。
- 工具从 `WorkspaceRuntime.capture()` 前开始计时。捕获快照后做求值、场景装配、真实 GL 绘制和读回。结束时间取自读回返回后，逐格检查像素成功才接受样本；校验和报告写出不计入结束时间。
- `WorkspaceRenderCoordinator.publish(frame)` 只发布帧，不证明该帧已被窗口消费。生产预览现通过同一 `PresentationToken` 及逐次纹理绘制的 `PresentationFrame` 连接模型加载、发布、视口消费和窗口 GPU 完成回执，详见下文。

## 合成场景与结果

三个场景只使用代码生成的色块，无文件输入：small 为 16 个图层，medium 为 144 个，large 为 576 个。实际网格数、顶点数随每条样本写入报告，不以图层数代替实测模型规模。每次改变前 16 个图层的可见性位图，得到新的文档修订。所有格子中心像素都必须符合该捕获的可见性与已知颜色，不能只验证非空画面。

这是静态网格及可见性变化的绘制基线，不包含遮罩、变形器、物理或播放。报告另记录当帧实际绘制网格数；结果不能外推为完整角色或编辑命令的端到端延迟。

每个规模分别采集至少 30 个 renderer_cold 和 30 个 renderer_warm 样本，保留全部有效原始样本及慢样本，输出中位数、最近秩 P95 和最大值。

- renderer_cold：捕获之后新建渲染器，包含 shader、目标和纹理缓存的初始化。JVM、驱动和 GL context 已存在，**不是进程冷启动**。
- renderer_warm：同一渲染器和视图预热 5 帧后，连续显示不同捕获修订。
- 固定 512×512 输出、无 MSAA、无 mipmap。报告记录 GPU、驱动 GL 版本、JDK、系统、处理器数量、任务/起始修订/候选身份与模式。
- 模型生成与安装、GL context 创建、Compose 调度与合成、窗口交换及显示器扫描均不在本指标内。读回带来的同步和传输开销在本指标内，不能把结果当作无读回的 UI 延迟。
- 错误帧、GL 错误或初始化失败会使工具失败。失败报告标记 `complete=false`，不生成成功分位数，也不把失败样本记为 0。

入口（独占窗口下运行，不与其他 GPU/性能任务并行）：

```powershell
$env:PSD2LIVE_TOOLS = '1'
# 仅验证合成场景的真实 GPU 读回：每档、每模式 2 帧，不输出性能数字。
.\gradlew.bat :test --tests '*FirstCorrectFrameTool.verifyReadback' --max-workers=2 '-Ppsd2live.testForks=1'

# 性能采样，默认每档、每模式 30 帧；如需增加可设 PSD2LIVE_FIRST_FRAME_SAMPLES。
.\gradlew.bat :test --tests '*FirstCorrectFrameTool.measure' --max-workers=2 '-Ppsd2live.testForks=1'
```

结果分别位于 `build/tools/first-correct-frame-check/report.json` 和 `build/tools/first-correct-frame/report.json`。普通 `FirstCorrectFrameProbeTest` 不初始化 GLFW，检查过期修订、候选和模型身份、错误像素、重复完成及统计规则。

离屏工具通过 `RigBuildProfile.stage/add` 记录阶段，单样本报告携带 `task/startRevision/candidate/scenario/meshes/vertices`。阶段计时和工具的完整区间起点不同：捕获耗时由工具起始时间覆盖，不能把捕获之后开始的 profiler 区间当作完整捕获延迟。

## 生产预览的窗口 GPU 完成回执

生产路径已接线，真实窗口/GPU 功能证据见末节；尚未执行整合基线的 30 次正式性能采样。本节指标名称为**模型激活捕获至窗口 GPU 完成**。起点是 VM 准备激活模型、捕获已提交工作区之前，不是编辑命令发起时刻；模型构建已完成，不能据此计算完整编辑延迟。

1. `refreshSdkSession` 记录单调时钟，使用 `captureTextures()` 的原子快照检查 `preview === capture.model`。只有匹配时才携带该快照的 `revision/state`。每次激活分配不可复用的 `candidate`；未知来源的编辑草稿仍有候选身份以拒绝过期帧，但不报告修订延迟。延迟加载保留原令牌。
2. `PreviewSessions.load` 向 Cubism 和 P2LRT 传递相同令牌，包括后端回退和多窗口懒加载。Cubism 仅在加载/更新成功后安装令牌；P2LRT 从 `PendingModel` 携带到实际成功的 `Loaded`。失败不会将新身份赋给旧模型。
3. 每次纹理绘制创建独立的 `PresentationFrame`，由 `GpuFrame` 和对应 `PreviewFrame` 共享。coordinator 验证候选与视图挂载代次；同一 viewId 关闭后重开也不能接受旧回执。发布本身不完成样本。
4. `drawGpuFrame` 实际发出绘制命令后记录所消费的纹理绘制，覆盖普通 Compose 及 `WindowCompositor` 的预览路径。旧画面可以作为缓存继续绘制，但不能完成新候选的测量。复用纹理被下一次绘制覆盖或绘制失败时，旧帧不能获得 GPU 回执。
5. Skiko 的 `onRender` 仅录制 picture。测量钩子在 `ContextHandler.drawContent` 将该 picture 回放到实际窗口 GPU surface 后，检查 picture、窗口 context、纹理绘制身份，并调用 `DirectContext.flush()` / `submit(true)` 等待 GPU 完成。GPU 完成和异步帧发布允许任意先后顺序，两者必须指向同一个 `PresentationFrame`，最后再验证候选及挂载代次。每个候选、每次视图挂载只报告一次。

测量开关为 `PresentationMeasurements.onCompleted`，在模型激活前设置，例如开发工具在应用启动前安装：

```kotlin
val receipts = java.util.concurrent.ConcurrentLinkedQueue<PresentationReceipt>()
PresentationMeasurements.onCompleted = { receipts.add(it) }
// 启动真实窗口、载入或提交模型，等待正常应用帧循环；回执由生产路径自动发送。
// 采样结束后撤销订阅。不要在回调中执行耗时工作。
PresentationMeasurements.onCompleted = null
```

默认关闭测量，不安装回放钩子、不等待 GPU。窗口像素还有独立的 `WindowPixelMeasurements.request/onReadback` 开关，默认 null，不分配窗口位图、不读回像素；仅在诊断回调内借用位图，回调结束后立即释放。完成观察者在 token/frame 锁外运行；coordinator 先更新帧流、退出自身锁，再标记发布。窗口钩子在 `pictureLock` 内完成真实 GPU 同步和 CPU 位图读回，退出该锁后才派发完成通知及像素回调，PNG 编码/校验也在此时执行。像素 `request` 是回放上下文内的轻量选择谓词，必须非阻塞且不做 I/O；不能把它描述成锁外回调。观察者异常被隔离。GPU 结束时钟在同步返回后立即捕获，像素传输、通知派发、校验和编码都不改变它；因此像素入口只用于功能验收，不能把该时钟称为 CPU 读回完成。

边界与验收限制：

- 接线仅证明同一已提交模型候选的预览纹理经过窗口消费及 GPU 完成，不自动验证像素颜色、裁剪可见区域、遮挡或业务视觉正确性；`verifyWindowPixels` 另行使用可区分修订的已知图案，检查实际窗口 Surface 的像素。编辑画布的离屏工具不因此变成窗口测量工具。
- 不覆盖窗口交换、系统桌面合成或显示器扫描，不能称为“已显示到屏幕”。同步 GPU 等待的开销包含在本指标中。
- `candidate` 是本次模型激活身份，尚未与 `RigBuildProfile.Operation` 的候选身份建立映射；不能拼接两者得到完整编辑延迟。`revision/state` 可以用于诊断，但不能替代这种关联。
- 钩子依赖仓库当前 Skiko 0.144.6 的 `drawContent/picture/pictureLock`。结构变化或上下文不可用会关闭回执并记录原因，不回退成录制时间。真实驱动、窗口回放和反射接线已有功能检查证据；正式性能验收仍须在最终整合基线和独占窗口中执行。
- 无已提交捕获、预览未挂载、软件绘制、无匹配帧或被更新修订淘汰时不产生成功样本，验收工具应将其记为未完成，不能记作零延迟。

无 GPU 回归覆盖令牌淘汰、视图关闭/重开、逐次纹理身份、发布与 GPU 完成乱序、重复完成、关闭测量、未知修订及观察者异常隔离，并以模拟 Cubism API 检查实际 session 的相同 bundle 重载、旧交付淘汰和加载失败。P2LRT 和真实窗口接线已有下述功能检查证据；实际窗口像素通过下述独立 opt-in 入口验收，正式性能验收仍待执行。

## 独占窗口验收驱动

`WindowActivationTool` 提供真实窗口功能检查与独立性能采样入口，已有功能证据见末节；正式性能采样仍需单独安排。它启动完整的 `PSD2LiveApp`，使用合成的四块彩色图案，经 `DesktopWorkspace` 安装原子工作区快照，再通过实际 VM、`WorkspaceRenderCoordinator`、P2LRT、`CanvasViewportComposable` 和 Skiko 的 `drawContent` 回放回执验收。它不调用离屏探针、不直接制造 GPU 完成回执。

每个样本先生成模型，再安装不同颜色的源图；模型生成不计入 activation 指标。激活在同一 EDT turn 内连续执行两次，前一候选被取代；验收要求后一候选的 `revision/state/candidate/viewId/capturedAt` 完全匹配。上一轮真实帧还会重新送给 production coordinator 和 VM，确认不能覆盖新帧流。普通测试中的假回执仅用于身份校验和队列边界的无 GPU 单元测试，不进入窗口采样。

导入本身在 `recoverLegacy=false` 时不直接发布 renderer 模型，但 UI 状态变化会重挂载视口、重启预览请求，静态检查不能保证显式激活前没有已到达回执；这尚未在真实窗口中复现。工具在两次显式 refresh 前建立原子队列边界，将此前已到达的回执单独列入 `preActivationReceipts`，所有回执仍完整保留在 `rawReceipts`，包含到达时间和序号。边界后到达的回执不按较早的 GPU 时间过滤，仍必须严格匹配当前候选；任何已标记 superseded 的回执及重复回执，即使在下一轮边界前到达，也会失败，不能通过归档掩盖。

- `smoke`：1 次预热、2 次正式激活，仅作实际窗口、native 及反射接线的初验，无性能分位数。
- `verifyWindowPixels`：1 次预热、2 次检查激活；回放及 GPU 完成后读取实际 Skiko 窗口 `ContextHandler.surface`，保留完整窗口 PNG。根据该次 native draw 固定的相机、Compose 视口原点和窗口 scene offset，定位四块图案，各检查 3×3 像素，RGBA 每通道容差 2；所有采样点必须在实际窗口及预览视口内。颜色随源图修订改变，每块还必须拒绝上一修订的颜色。读回与身份回执必须具有相同 `revision/state/candidate/viewId/gpuCompletedAtNanos`，不会用后续正确帧补替首帧。它不启动离屏渲染器，不输出性能分位数。
- `measure`：5 次预热、至少 30 次正式激活；预热也必须通过身份与回执校验，预热耗时不纳入统计。报告记录要求和完成的预热次数；所有样本身份正确才输出中位数、最近秩 P95、最大值。
- 使用 coroutine flow/channel 和有界超时等待事件，不使用 sleep 轮询。无窗口回执会记录 `timeout_no_window_receipt` 和空 GPU 结束时间，失败退出、保留已完成样本，不输出成功统计或用 CPU/离屏时间补齐。
- 报告分别写到 `build/tools/window-activation-smoke/report.json`、`build/tools/window-activation/report.json`，保留所有原始回执、期望身份、被取代候选、旧帧拒绝结果、GPU/native 环境与 DLL SHA-256。上述两个入口仍只验证身份和 GPU 完成。像素入口另写 `build/tools/window-activation-pixels/report.json`，包含每次激活（含预热）的窗口尺寸、视口、相机、采样位置和颜色。所有入口均不测量屏幕扫描。
- 起点分别记录 `syntheticInstallStartedAtNanos`、`activationCapturedAtNanos`；`editStartedAtNanos=null`，因为这里没有测量真实编辑命令，不能把合成安装起点称为编辑起点。JVM/窗口 context 已热，每次安装会重新挂载预览视图，结果也不能冒充进程冷启动。
- 当前窗口驱动限定 P2LRT。native 缺失、ABI 不兼容、OpenGL 不可用、后端回退都会失败，不会作为跳过的成功验收。Cubism 实际窗口还需另行覆盖。

在独占窗口获准后，先 smoke，再运行 30 次采样；两种 compositor 模式应分开执行：

```powershell
$env:PSD2LIVE_TOOLS = '1'
# 可明确指定已验证的 native DLL；未指定时会查找工具目录内准备好的本地副本。
$env:PSD2LIVE_RUNTIME = (Resolve-Path 'build/tools/window-activation/native/p2l_runtime.dll').Path
$env:PSD2LIVE_COMPOSITOR = '1'
.\gradlew.bat :test --tests '*WindowActivationTool.smoke' --max-workers=2 '-Ppsd2live.testForks=1'
# 独立的窗口像素功能验收；读回/PNG 编码会干扰耗时，不作为性能样本。
.\gradlew.bat :test --tests '*WindowActivationTool.verifyWindowPixels' --max-workers=2 '-Ppsd2live.testForks=1'
$env:PSD2LIVE_WINDOW_SAMPLES = '30'
.\gradlew.bat :test --tests '*WindowActivationTool.measure' --max-workers=2 '-Ppsd2live.testForks=1'
# 普通 Compose 回放路径另跑一轮，先保存前一轮报告，再设 PSD2LIVE_COMPOSITOR=0。
```

`PSD2LIVE_WINDOW_TIMEOUT_SECONDS` 默认 30，允许 5–120 秒。正式采样前核对 `PSD2LIVE_RUNTIME` 指向的 native DLL、ABI 与源码来源，并保留构建及哈希记录；native 来源校验不代替实际窗口、GPU 或像素验收。下述功能检查使用的构建来源记录位于主检出的 `build/architecture-acceptance/runtime-build.log` 和 `runtime-provenance.json`。

## 2026-11-14 窗口功能验收证据

功能证据集中归档在主检出的 `build/architecture-acceptance/window-callback-locks/`：`summary.json` 汇总两种模式，`compositor-1/` 与 `compositor-0/` 各保存 `report.json`、三张窗口 PNG、运行日志和 JUnit XML；根目录另保存相关无 GPU 检查的日志与 XML。报告中的原始工作树路径是采样时的来源记录，查阅归档文件使用上述相对目录。

本轮复核在 coordinator 和窗口回放锁的回调边界修正后执行。coordinator 先更新帧流并解锁，再标记发布；窗口钩子在 `pictureLock` 内固定 GPU 完成时间与 CPU 像素，解锁后派发完成通知和像素观察者。迟到通知仍须通过候选有效性检查。轻量 `request` 谓词及必需的 GPU 同步／读回仍位于回放上下文。

- 14 项相关无 GPU 检查通过。
- `WindowActivationTool.verifyWindowPixels` 在 compositor=1、0 下分别完成 1 次预热和 2 次检查激活；两份报告均为 `complete=true`、`benchmark=false`，实际后端为 P2LRT。
- 共保留 6 张实际窗口 Surface 读回图，每种模式检查 108 个像素，共 216 个。每块图案的颜色、视口位置及首帧回执身份通过检查，并拒绝上一修订颜色；没有用后续正确帧替代首帧。
- 每种模式各进行 6 次 `Thread.holdsLock` 观察者检查，receipt 和 pixel 回调均未持有 coordinator 锁或 `pictureLock`。结论仅覆盖这两个明确检查的锁，不泛化为所有回调均在所有锁外。
- 两轮 `preActivationReceipts` 均为空，未在这些样本中复现导入前回执竞态。请求与实际 compositor 模式一致；启动配置须在 `SkiaGpu.requestOpenGl()` 之前设置。

这些是静态四块已知图案的功能证据，不覆盖整个模型的遮罩、变形、物理、所有遮挡情形，也不证明窗口交换或显示器扫描完成。未执行至少 30 次的正式性能采样，不提供性能分位数；像素读回和 PNG 编码会扰动时间。

公开编辑请求已有 `WorkspaceEditCorrelation`、`WorkspaceBuildCorrelation` 和发布观测身份，但本工具仍在模型构建完成后从激活捕获开始，报告为 `fullEditLatencyMeasured=false`、`editStartedAtNanos=null`。不能将编辑、构建或发布观测与本次 activation 样本直接拼接成完整 edit→window 延迟；真实编辑链路的性能结果须由独立验收提供。
