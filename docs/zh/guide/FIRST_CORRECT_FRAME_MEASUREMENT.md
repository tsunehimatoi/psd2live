# 首个正确画面的测量边界

`FirstCorrectFrameTool` 测量的是**捕获修订到离屏编辑画布 GPU 读回完成**，不是桌面窗口呈现延迟。工具已提供，性能采样须等待独占窗口；没有采样数据时不能据此声称性能达标。

## 现有路径与可用测量点

- `PreviewRenderer.composite` 是源图合成及其缓存，不代表绑定后模型的显示。
- 编辑视口通过 `CanvasScene`、`ArtworkDrawList` 和 `GlCanvasRenderer` 绘制。工具复用这些实现，并调用已有的 `GlCanvasRenderer.render` 离屏入口；该入口以 `glReadPixels` 同步 GPU 后返回位图。
- 工具从 `WorkspaceRuntime.capture()` 前开始计时。捕获快照后做求值、场景装配、真实 GL 绘制和读回。结束时间取自读回返回后，逐格检查像素成功才接受样本；校验和报告写出不计入结束时间。
- `WorkspaceRenderCoordinator.publish(frame)` 只发布帧，不证明该帧已被窗口消费。`PreviewFrame` 目前没有修订或编译产物身份，所以不能用捕获后的任意第一帧作为正确画面。

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

## 真实窗口还需要的最小接线

这部分是待集成设计，工具尚未声称测得真实窗口呈现：

1. 应用捕获时生成不可复用的呈现令牌，携带 `revision/candidate/modelLoadGeneration` 和模型身份。令牌绑定实际加载的编译产物，不能在回调时从“当前修订”补写。
2. `PreviewSessions.load` 将令牌交给后端。P2LRT 在 `PendingModel → Loaded` 接受成功时保存；Cubism 在成功加载或成功更新纹理后保存。失败后继续显示旧模型时仍返回旧令牌。`PreviewFrame` 携带该已加载令牌及绘制序号，避免新请求给旧模型贴上新身份。
3. coordinator 的 `publish` 保留该令牌但不结束计时。视口实际消费对应 GPU texture 时记录令牌、视图、纹理代次及绘制序号，避免同一纹理随后被另一帧覆盖时发生错配。
4. 窗口合成完成并通过对应 GPU fence 后报告“窗口合成完成”，确认视图仍挂载、项目打开代次及令牌匹配。若没有 swap/presentation 回执，仍不能命名为“已显示到屏幕”。仅在 Compose `drawImage` 返回处计时也不够。

VM 的令牌捕获及视口/窗口回执接线由主会话与控制器迁移一起集成。需要覆盖：旧编译完成晚到、加载失败保留旧画面、视图关闭/重开、后端回退，以及同纹理连续绘制。不能用放宽身份匹配或把 publish 时间提前当作完成来通过测量。

已核对控制器迁移后的 VM：`refreshSdkSession`、`ensureSdkSessionLoaded` 中的 `sdkSession.load(preview)` 与 `acceptSdkFrame` 中的 `renderCoordinator.publish(frame)` 仍分离。现有项目代次、工作区和 viewId 检查需要保留，但不提供候选模型身份。`PreviewFpsTool` 可观测真实窗口帧周期，不能代替上述首个正确修订的验收。
