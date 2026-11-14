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

工具仅调用已有 `RigBuildProfile.stage/add`，不修改 B 会话正在扩展的 profiler。单样本报告已携带 `task/startRevision/candidate/scenario/meshes/vertices`；集成 B 的 `measure(Operation(...))` 时，可在 `sample` 中捕获快照后包围后续渲染。捕获耗时仍由工具的起始时间覆盖，不应把 `measure` 的较晚起点冒充完整捕获延迟。

## 生产预览的窗口 GPU 完成回执

生产路径已接线，尚未运行真实窗口/GPU 验收或 30 次正式采样。本节指标名称为**模型激活捕获至窗口 GPU 完成**。起点是 VM 准备激活模型、捕获已提交工作区之前，不是编辑命令发起时刻；模型构建已完成，不能据此计算完整编辑延迟。

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

默认关闭测量，不安装回放钩子、不等待 GPU。观察者在 token/frame 锁外运行，异常被隔离，不能使正常帧发布或窗口绘制失败。

边界与验收限制：

- 接线仅证明同一已提交模型候选的预览纹理经过窗口消费及 GPU 完成，不自动验证像素颜色、裁剪可见区域、遮挡或业务视觉正确性；正式首个正确画面验收仍应使用可区分修订的已知图案与像素检查。编辑画布的离屏工具不因此变成窗口测量工具。
- 不覆盖窗口交换、系统桌面合成或显示器扫描，不能称为“已显示到屏幕”。同步 GPU 等待的开销包含在本指标中。
- `candidate` 是本次模型激活身份，尚未与 `RigBuildProfile.Operation` 的候选身份建立映射；不能拼接两者得到完整编辑延迟。`revision/state` 可以用于诊断，但不能替代这种关联。
- 钩子依赖仓库当前 Skiko 0.144.6 的 `drawContent/picture/pictureLock`。结构变化或上下文不可用会关闭回执并记录原因，不回退成录制时间。真实驱动、窗口回放和反射接线仍须在主会话安排的独占窗口中验证。
- 无已提交捕获、预览未挂载、软件绘制、无匹配帧或被更新修订淘汰时不产生成功样本，验收工具应将其记为未完成，不能记作零延迟。

无 GPU 回归覆盖令牌淘汰、视图关闭/重开、逐次纹理身份、发布与 GPU 完成乱序、重复完成、关闭测量、未知修订及观察者异常隔离，并以模拟 Cubism API 检查实际 session 的相同 bundle 重载、旧交付淘汰和加载失败。P2LRT 原生编译、真实窗口与像素正确性留待独占验收。
