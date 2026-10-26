# 开发与命令行

[文档目录](../../README.md) · [English](../../en/guide/DEVELOPMENT.md) · [日本語](../../ja/guide/DEVELOPMENT.md)

本页面向从源码运行、使用命令行或参与开发的用户。发布包自带运行时，普通使用不需要本页内容。

## 环境

- JDK 21，推荐 JetBrains Runtime（JBR）21：发布包用它，主窗口在它上面保留系统窗口框架（最大化、贴靠、拖边调整大小由系统处理），标题栏由程序绘制；其他 JDK 也能构建、测试和运行，主窗口改为无边框、整条标题栏由程序绘制。打包（`createDistributable`、`packageExe` 等）要求 JBR 21 SDK（带 jpackage），用其他 JDK 打包需加 `-Ppsd2live.allowNonJbr=true`。Gradle 使用仓库自带的 Wrapper，无需单独安装。
- Windows 用 `.\gradlew.bat`，Linux / macOS 用 `./gradlew`。下文示例统一写作 `./gradlew`。
- 官方 Cubism SDK 不是构建前提。源码构建默认使用内置渲染；原生预览见 [Cubism Native 预览](CUBISM_SDK_SETUP.md)。

## 运行

```bash
./gradlew run                     # 不带参数：启动 GUI
./gradlew run --args="--help"     # 查看 CLI 帮助
python tools/examples/fetch.py --case tml
./gradlew run --args="--input examples/tml/psd-input/tml.psd --output build/example-output"
```

Windows 也可以直接运行根目录的 `run-gui.bat`。不带参数启动 GUI；带参数进入 CLI，此时必须提供 `--input`（`--help` 除外）。CLI 从 PSD 直接生成导出文件，不产生可继续编辑的 `.psd2live` 工程。

## CLI 参数

| 参数 | 默认值 | 作用 |
| --- | --- | --- |
| `--input <path>` | 必填 | 输入的分层 PSD |
| `--output <path>` | PSD 同目录下的 `psd2live-output` | 导出目录 |
| `--lang <zh\|en\|ja\|ko>` | 系统语言 | 日志语言 |
| `--clear-user-data` | — | 单独使用：删除当前用户的设置、工作区存储与 `~/.psd2live` 下的运行时和缓存后退出；编辑器运行时不删除并返回 1。Windows 卸载时选择同时删除数据时调用 |
| `--atlas <size>` | 4096 | 纹理图集尺寸 |
| `--mesh-spacing <px>` | 64 | 网格间距 |
| `--mesh-pixels` | 关闭 | 按源像素而非长边缩放到 2048 px 的文档像素计算网格长度 |
| `--mesh-wrap <单位>` | 0 | 包裹拓扑：闭合窄于此值（网格单位）的轮廓缝隙，细小凸起共用一个包络 |
| `--head-strength <value>` | 1.0 | 头部形变幅度 |
| `--body-strength <value>` | 1.0 | 身体形变幅度 |
| `--mesh-only` | 关闭 | 仅生成网格 |
| `--no-deformers` | 关闭 | 不生成变形器 |
| `--no-see-through` | 关闭 | 眉毛与眼睛都不透过刘海（默认都透过，输出比此前多出眉毛复制件、刘海的眼部开窗及其复制件） |
| `--no-see-through-eyes` | 关闭 | 眼睛不透过刘海（现已默认开启的 `--see-through-eyes` 仍可接受） |
| `--see-through-opacity <0..1>` | 0.5 | 眉毛与眼睛透过刘海的程度 |
| `--no-motions` | 关闭 | 不输出动作 |
| `--no-physics` | 关闭 | 不生成物理 |
| `--no-cmo3` | 关闭 | 不输出 CMO3 |
| `--no-moc3` | 关闭 | 不输出 MOC3 |
| `--no-json` | 关闭 | 不输出诊断 JSON |
| `--upscale <1\|2\|4>` | 1 | 纹理高清化倍率，1 为关闭 |
| `--upscale-python <path>` | Windows 为 `python`，其他为 `python3` | 装有 nunif 依赖的 Python |
| `--nunif-dir <path>` | 空 | nunif 源码目录 |
| `--upscale-model <path>` | 空 | 权重目录 |
| `--upscale-tile <64..512>` | 256 | 推理分块大小 |
| `--upscale-noise <-1..3>` | 1 | 降噪等级，-1 为不降噪 |
| `--no-upscale-neural-alpha` | 关闭 | 改用双线性放大 Alpha |

说明：

- 默认值以 [`Main.kt`](../../../src/main/kotlin/io/github/psd2live/Main.kt) 为准。GUI 的初始网格间距来自 `PipelineConfig`（40），与 CLI 的 64 不同。
- CMO3、MOC3 和诊断 JSON 至少保留一种输出。
- 神经 Alpha 默认开启；`--upscale-neural-alpha` 仅为兼容旧命令保留。
- 高清化的准备与示例见[纹理高清化](TEXTURE_UPSCALE.md)。

### 无界面 MCP 服务

`./gradlew run --args="mcp [--port N] [--token T] [--store DIR] [--profile core|full]"` 不开窗口，只启动 MCP 端点，供智能体单独工作：工作区由应用层宿主承载，不创建 ViewModel 与 Compose 状态（见[无窗口宿主](../spec/HEADLESS_HOST.md)）；启动后打印 `endpoint`、`token`、`profile`、`store` 与 `capabilities`（`view_sample_motion=yes|no`，构建是否带 Cubism Core），直到进程结束。脚本用 `python mcp_proxy.py call <操作> [JSON] --edit` 或 Python 的 `mcp_proxy.Client` 调用，设 `PSD2LIVE_MCP_ENDPOINT` 与 `PSD2LIVE_MCP_TOKEN` 指向它。工作区存储默认在用户目录的 `psd2live-mcp` 下，与编辑器分开（不共用历史与实例锁）。`--token` 须为 32–256 个 URL 安全 Base64 字符，省略时随机生成。

## 导出到其他格式

`export` 命令从 `.psd2live` 工程（当前历史状态）或 PSD（默认设置生成）经中立 IR 导出到任意目标，并写出损失报告：

```bash
./gradlew run --args="targets"                                            # 列出导出目标
./gradlew run --args="export model.psd2live --target gif --set clip=Nod --set size=512"
./gradlew run --args="export model.psd2live --target psd-pose --set pose=ParamAngleX=20 --output out/pose"
```

- 文件写入 `--output`（默认输入旁的 `<名称>-<目标>`），损失报告为 `<名称>.<目标>.report.json`；`--name` 指定基础名称。
- `--set key=value` 可重复，各目标的设置键见[中立绑定 IR 与导出目标](../spec/EXPORT_TARGETS.md)。
- 退出码：0 成功，1 导出失败，2 参数错误。

## 测试与打包

```bash
./gradlew test                                   # 全部测试（CI 在 Ubuntu 与 Windows 上运行）
./gradlew test --tests "io.github.psd2live.scenario.CatalogContractsTest"   # 单个测试类
./gradlew createDistributable                    # 带运行时的应用目录
./gradlew packageDistributionForCurrentOS        # 当前平台的安装包
```

- 两种打包都只包含当前构建平台的原生库，并在应用资源目录附带 `LICENSE`、`THIRD_PARTY_NOTICES.md` 与 `licenses/`。
- 官方 SDK 资源（`src/main/resources/cubism/`）默认不打包，只有传入 `-Ppsd2live.includeCubism=true` 或设置 `PSD2LIVE_INCLUDE_CUBISM=true` 时才包含。含 SDK 的包不得公开分发，发行流程见 [CI 与发行](CUBISM_CI_RELEASE.md)。
- Linux 也可用 `./native/package_linux.sh` 生成需要系统 JDK 21 的本地启动包（输出到 `dist/linux-<时间戳>/`），详见 [native/README.md](../../../native/README.md)。
- 项目没有独立的 lint 任务，代码风格为 `kotlin.code.style=official`。
- Rust 运行时在 `runtime/` 中用 `cargo test`、`cargo build --release` 构建，见[运行时](../spec/RUNTIME.md)。`./gradlew run` 和打包在没有可用 cargo 时，Linux 上会下载 CI（`runtime-prebuilt.yml`）按同一份源码构建的库，按 `runtime/src`、`Cargo.toml`、`Cargo.lock` 的哈希匹配（`./gradlew printRuntimeHash`）并校验 sha256；找不到或离线则回退到内置求值器。`-Ppsd2live.prebuiltRuntime=false` 关闭下载。改 `runtime/` 反复运行时可加 `-Ppsd2live.fastRuntime=true`，关闭 LTO 以大幅缩短编译，生成的库更慢，不要用于打包。
- 测试套件只保留快速的单元与契约测试，全量 `./gradlew test` 应在 1 分钟内完成。不要加入在示例 PSD（tml、ds）上跑完整流水线、逐步对比冷重放或渲染整幅画面的测试；这类检查写成下面的开发工具，需要时手动运行。

## 外部示例与验收

示例素材、工程和展示输出在 [psd2live-examples](https://github.com/tsunehimatoi/psd2live-examples) 中管理，未单独注明许可证时默认 CC0 1.0。Python 3.11+ 下载命令：`python tools/examples/fetch.py --case tml`；`--all` 下载全部固定案例。文件仍写入 `examples/<id>/`，下载缓存位于 `build/example-assets/`。

主项目的 `tools/examples/fixtures.lock.json` 固定素材版本、下载地址、压缩包及逐文件 SHA-256 与大小。素材升级通过独立修改锁文件进行，不跟随示例仓库最新分支。下载失败、大小或哈希不符会报错。

普通 CI 使用小型合成素材与内部契约测试，不下载大示例。GitHub Actions 的 `Example acceptance` 工作流手动启用，下载固定的 `tml`、`ds`，运行 `ExportGoldenTool` 检查完整生成、导出及 CMO3 导入路径，并要求四个变体的导出与预览报告齐全。报告用于生成链路检查，不替代视觉验收或历史基准比较；发行前可运行并检查上传的报告。MCP 内部回归测试留在主仓库，外部使用案例与发现检查见示例仓库。

## 开发工具

`src/test/kotlin/io/github/psd2live/tools/` 中是用于目视检查和测量的开发工具。它们写成测试，以便调用流水线内部接口；只有设置 `PSD2LIVE_TOOLS=1` 时才运行，平时的 `./gradlew test` 会跳过。输出写到 `build/tools/`。

```bash
python tools/examples/fetch.py --case ds
PSD2LIVE_TOOLS=1 PSD2LIVE_SAMPLE=ds ./gradlew test --tests "io.github.psd2live.tools.MotionSheetTool.body"
```

| 工具 | 内容 | 输出 |
| --- | --- | --- |
| `DeformerDeletionVisualTool` | 合成非对称网格在删除父变形器前后及 MOC3 导出读回后的剪影对照 | `deformer-deletion/comparison.png` |
| `MotionSheetTool.motions` | 每个预设动作按时间展开的拼图、待机循环、呼吸前后与差分图、身体 Z | `motion-sheet/<示例>-*.png` |
| `MotionSheetTool.body` | 身体 X × 身体 Y、腿部和上半身特写、前后倾、大小变、腿部姿势，分别为无骨架和自动骨架 | `motion-sheet/<示例>-{stance,lean,size,legposes}*.png` |
| `MotionSheetTool.tracking` | 指针在画面上慢速绕行 12 秒，头和身体按预览的增益与速度跟随 | `motion-frames/<示例>-track/` |
| `MotionSheetTool.idle` | 待机 12 秒的逐帧图 | `motion-frames/<示例>-idle/` |
| `ModelProfileTool.cmo3` | `.cmo3` 的参数、变形器树（网格轴与范围）、图形网格、分段运动剖面、身体参数下各网格的位移，身体 X × 身体 Y 的剪影，以及物理组 | `model-profile/<名称>.txt`、`.png`、`-physics.txt` |
| `RigStudyTool` | 研究成品 Cubism 模型（`PSD2LIVE_MOC3`：`.model3.json` 或其所在目录，可含多个模型）：参数与分组及用途、部件树、变形器树与各对象的关键形轴、网格密度、蒙版、Glue、物理、表情、动作与 VTube Studio 映射；逐参数的形变量、不透明度 / 绘制顺序 / 颜色变化与半段非线性度；默认姿态、每个参数五帧扫描（裁剪到其影响范围）、头部角度、眼、嘴、身体网格图与网格线框；`PSD2LIVE_STUDY_ONLY` 按路径筛选，大模型需 `-Ppsd2live.testHeap=16g` | `rig-study/<目录>/profile.txt`、`effects.txt`、`*.png` |
| `RigReviewTool` | 示例（`PSD2LIVE_SAMPLE`）以新工程的配方和引入模块之前的原有生成方式各生成一次，在审阅姿态与动作片段下对照：每个部位一张姿态板（上下两行），每个片段一张并排播放、带物理的 GIF（眨眼、慢眨、笑眼眨眼、视线、说话、元音、眉、转头、点头歪头、甩头、身体、待机）；`PSD2LIVE_REVIEW` 选部位（eye、brow、mouth、head、hair、body、motion，默认全部），`PSD2LIVE_REVIEW_SIZE` 为单帧边长 | `rig-review/<示例>/<部位>-poses.png`、`<片段>.gif` |
| `ModelProfileTool.sample` | 生成模型（无骨架和自动骨架）的分段运动剖面、身体图层与自动骨骼 | `model-profile/<示例>.txt` |
| `SkeletonAutoViewTool.view` | 把自动骨架叠画在示例上并打印每根骨骼，检查关节位置与分叉；`PSD2LIVE_SAMPLES` 逗号分隔多个示例或 PSD 路径，`PSD2LIVE_ZOOM=左,上,右,下`（画布像素）裁出局部 | `skeleton-auto/<示例>.png` |
| `SimBakeSuite` | 按模型预设生成后发、前发、服装等物理体（另加顶部 10% 固定的后发），用旧方法和逐输入方法烘焙，在没参与拟合的动作（类面捕跟随、歪头、内置动作、逐输入阶跃、转头、拖动、摇晃）上对比参考模拟与导出模型的视觉指标，见[模拟与烘焙](SIMULATION.md) | `build/tools/sim-bake/<样例>/`：`summary.md`、每个设置的 JSON、尖端轨迹图 |
| `SimRootDofExperiment` | 检验物体是否只响应根部的平移与旋转：每个输入带动根部多少、额外改变多少形状，真实驱动与刚体驱动、不同幅度、同样根部运动的不同输入之间的差别 | `build/tools/sim-root-dof/<样例>.md` |
| `SimBakeProfileTool` | `tml` 后发单线程与多线程烘焙各一次，按进度标记分段计时并对所有线程采样 | 标准输出 |
| `PendulumKernelTool` | 摆锤数组内核（精确与快速模式）与 `PhysicsEngine` 每帧耗时及偏差 | 标准输出 |
| `CanvasPerfTool` | 在 Xvfb 下打开真实窗口，对编辑画布依次做静止、悬停、滚轮缩放、中键平移、变形模式拖动脸部全部点，GPU 渲染与软件渲染各一轮，报告帧间隔、界面线程延迟、界面线程热点与每阶段编辑画布的 GPU 帧数（GPU 一轮为 0 即画布没走 GPU）；`PSD2LIVE_CANVAS_AA` 选画布抗锯齿，`PSD2LIVE_OVERLAY=1` 打开性能叠加层。它用 `Robot` 移动真实指针，须在专用的 X 服务器上运行（`xvfb-run -a -s "-screen 0 1920x1080x24"`，或有 GPU 的机器上单独的 Xorg），不要在日常使用的桌面上运行 | `canvas-perf/report.txt`、各阶段截图与 `.jfr` |
| `DragonBonesFidelityTool` | 把 `tml`、`ds`（无骨架和自动骨架）导出为 DragonBones，用 `tools/dragonbones-check`（官方 DragonBones 5.7 运行时核心，需要 node）播放：参数动画在每个关键帧上与编辑器求值器比较（误差不超过导出报告的容差），并测量片段误差 | `dragonbones-fidelity/report.txt` |
| `RuntimeConformanceTool.generate` / `.physics` | Rust 运行时的参考数据：`generate` 为随机模型（Warp、旋转、嵌套、稀疏网格、混合形、Glue、通道、部件）、样例与 `PSD2LIVE_RUNTIME_PROJECT` 指定的工程在随机姿势下的编辑器求值结果（骨架样例另有最细采样的 `rig.fine.p2lrt`）；`physics` 为随机摆锤组和样例物理的逐帧轨迹。用 `runtime/` 中的 `p2lrt-conformance` 比较 | `runtime-conformance/<用例>/`、`runtime-physics/<用例>/` |
| `RuntimeConformanceTool.simulation` / `.simulationRig` | 模拟的参考轨迹：`simulation` 为随机布片（顶边固定、刚度随机）在摇摆、转动、跳动的目标与固定点驱动下经编辑器 XPBD 求解的逐帧结果，一半带移动的胶囊碰撞体；`simulationRig` 为 `tml` 后发（顶部十分之一固定）烘焙后导出的模型与实时场景，以及未烘焙 Rig 上编辑器场景随头部和身体动作的结果。分别由 `p2lrt-conformance --sim`、`--sim-rig` 读取 | `runtime-sim/<用例>/trace.bin`、`runtime-sim-rig/back-hair/` |
| `RuntimeSamplesTool` | 供目视检查播放器的合成模型：渐变上的每种颜色混合模式、乘色与屏幕色、遮罩与反向遮罩，并用软件光栅器渲染参考图 | `runtime-samples/features.p2lrt`、`features.png`、`layout.txt` |
| `WarpProbeTool` | 编辑器求值器的黑盒探测：Warp 映射（格子内外）、Warp 下的旋转框架、翻转、混合形、稀疏网格，供运行时独立实现对照 | `warp-probe/*.tsv` |
| `GeneratorCostTool` | 各生成器（Rig 生成有无骨架、骨架烘焙缓存前后、物理组目录、生成动作缓存前后、Rig IR 编译、两个摆动的生成与重放、模拟烘焙与写回）的耗时，及对其输入做内容哈希的耗时，用于判断生成器是否值得接入生成缓存；`PSD2LIVE_SAMPLE` 指定样例（需有前发与后发） | `generator-cost/report.txt` |
| `ExportGoldenTool` | `tml`、`ds` 的无骨架、自动骨架、自定义动作三种变体，以及无骨架 cmo3 作为新工程导入后的全部导出文件与预览包摘要（cmo3 取读回后降级为 moc3 的摘要与物理组数），用于逐字节对比重构前后的导出；`PSD2LIVE_GOLDEN_LABEL` 指定输出名 | `export-golden/<名称>.txt`、`<名称>/<示例>-<变体>/` |
| `LayoutGoldenTool` | 与 `ExportGoldenTool` 相同的摘要，但用测试用的合成角色（全身角色、嘴部）生成 PSD，不需下载示例：流水线导出三种变体，以及导入 PSD 后经导出会话的 moc3 / cmo3 导出；用于逐字节对比改动前后的导出（CAN3 含随机 GUID，每次都不同）；`PSD2LIVE_GOLDEN_LABEL` 指定输出名 | `layout-golden/<名称>.txt` |
| `LayoutPanelsTool` | 无窗口渲染素材面板、图层表标签格与生成栏：导入合成 PSD、放置一个素材使其待生成、双击打开文件夹、选中条目、把条目拖到画布落点放置（落点由测试注册，检查以落点为中心）、悬停生成按钮、打开与关闭标签菜单后再次拖放 | `layout-panels/*.png` |
| `LayoutWindowTool` | 打开真实编辑器窗口（OpenGL），导入合成 PSD、切到 Layout 工作区，用 AWT 鼠标事件把素材文件夹从素材面板拖到画布并检查落点、点击生成栏按钮并检查生成完成；截图取自窗口自身的 Skia 帧 | `layout-window/report.txt`、`{layout,dragging,placed,generated}.png` |
| `ExportGoldenComparisonTool` | 对比两个已生成的金样本报告；CAN3 按配套 CMO3 部件 ID 解析随机 GUID 引用后比较完整 XML，其余比较摘要。保留两份报告和对应 CAN3 / CMO3 文件；`PSD2LIVE_GOLDEN_BEFORE` / `PSD2LIVE_GOLDEN_AFTER` 默认 `baseline` / `optimized` | `export-golden/comparison.txt` |
| `WorkspaceLifecyclePerfTool` | 合成 20,000 节点历史、20 工作区各 4 画布；比较共享历史快照、纯展示保存身份复用与立即重建方式的耗时和线程分配。仅开发工具启用，不表示真实渲染帧率 | `workspace-lifecycle/performance.txt` |
| `ExportPerfTool` | 把示例 PSD（`PSD2LIVE_SAMPLE`）按整数倍最近邻放大（`PSD2LIVE_EXPORT_SCALES`，默认 `1,2`）后，逐个导出目标（`PSD2LIVE_EXPORT_TARGETS`，默认除视频外全部）计时，并记录每个导出文件的摘要（cmo3 取其中图像条目与读回后降级为 moc3 的摘要），用于对比导出提速前后的耗时与字节；栅格目标每档放大输出 1024 像素、5 fps；`PSD2LIVE_GOLDEN_LABEL` 指定输出名，大倍数需 `-Ppsd2live.testHeap=8g` 等加大测试堆 | `export-perf/<名称>.txt`、`<名称>.digest.txt` |
| `ArtPrimitiveV2VisualTool` | 在 `tml` 上把腿按侧连通块拆分、左眼睫多边形拆分、嘴部多边形拆分分别写成版本 1 与版本 2 记录，渲染未拆分、v1、v2 与 v1/v2 差异（×4）在睁眼/半闭/闭眼、闭嘴/张嘴/张嘴笑等姿态下的对照，检查接缝、错位与缺失像素 | `art-primitive-v2/{legs,eye,mouth}.png` |
| `SafetyGoldenTool` | 自动骨架 Rig 上 24 组带种子的随机几何编辑的完整几何安全报告（不含 `coverage`），用于逐字节对比检查器改动前后的分类；`PSD2LIVE_GOLDEN_LABEL` 指定输出名 | `safety-golden/<名称>.txt` |
| `BundleProfileTool` | moc3 预览包的分项耗时（IR 编译、IR 转回、静止网格换到画布空间、physics3/motion3、moc 降级与写出、cdi3）及几何安全检查耗时；`PSD2LIVE_SAMPLER=1` 另打印栈采样的热点 | 仅标准输出 |
| `CubismCoreCheckTool` | 把 `tml`、`ds` 预览包中的 moc3 交给官方 Cubism Core 的一致性检查（`csmHasMocConsistency`）；需用 `PSD2LIVE_TEST_CUBISM_CORE` 指定 Core 动态库，否则跳过。小型新建模型的同样检查在 `:umamo` 的 `MocDefaultColorsTest` 中 | 仅标准输出 |
| `TextureWorkspaceTool` | 用 `tml` 设置几种密度与锁定并按网格形状排布一次后，渲染纹理集页面与纹理面板（中英文、单选/多选/未选、热力图开关、密度滑块预览），以及编辑画布的纹理集像素与原始像素对比 | `texture-workspace/*.png` |
| `AtlasFramePerfTool` | 无窗口、软件渲染下测量 `tml` 纹理集页面与纹理面板的帧耗时：向 `ImageComposeScene` 发送空闲、悬停、拖动角点（密度）、滚轮缩放、拖动图块与关闭线框后悬停的指针输入，记录每次渲染耗时（中位数、p90、最大），并保存角点拖动中、编辑会话、静止页面与放大近景四帧。GPU 渲染需要窗口的 Skia 上下文，由 `AtlasWindowPerfTool` 与 `PreviewWindowTool` 测量 | `atlas-frame-perf/report.txt`、`{corner-drag,session,frame,zoomed}.png` |
| `AtlasWindowPerfTool` | 打开真实编辑器窗口进入纹理工作区（`PSD2LIVE_SAMPLE` 可指定 `.psd` 或 `.psd2live`，请用副本），以 AWT 事件向窗口派发指针输入（不移动真实光标）：经视图模型提交移动与密度、拖动图块、拖动角点、六次不等待的连续拖动；记录帧间隔、界面线程延迟、各忙碌标志何时落下、界面线程热点、拖动预览绘制次数与各视图的 GPU 帧数；截图取自窗口自身的 Skia 帧，不截屏幕 | `atlas-window-perf/report.txt`、`*.jfr`、`*-after.png` |
| `PreviewFpsTool` | 打开真实编辑器窗口播放预览（`PSD2LIVE_WORKSPACE` 选工作区预设，`PSD2LIVE_BACKENDS` 选后端），报告帧率与帧间隔分位、窗口帧周期中 Compose 的耗时、界面线程各类事件的耗时与文档状态的变化频率；`PSD2LIVE_VSYNC=0` 不等显示刷新以测出实际成本，`PSD2LIVE_PANELS=0` 隐藏侧栏，`PSD2LIVE_TRACE=1` 统计各可组合函数每秒的组合次数，`PSD2LIVE_JFR=1` 录制并列出界面线程热点，`PSD2LIVE_SVG=1` 把最后一帧的全部绘制导出为 SVG 并计数，`PSD2LIVE_INVALIDATIONS=1` 列出让窗口合成器整幅重画的请求来源，`PSD2LIVE_CAPTURE=1` 暂停后从 GPU 读出窗口画面（`PSD2LIVE_COMPOSITOR=0` 关闭合成器，用于对比），`PSD2LIVE_SCRUB=EDIT`（或 `PREVIEW`）改为在该模式的画布上按面板方式拖动参数滑块（`PSD2LIVE_SCRUB_PARAMETER` 选参数，`PSD2LIVE_SCRUB_HZ` 设每秒采样数，默认 240），并报告每个采样在界面线程上的耗时；`PSD2LIVE_GESTURE=hover`（或 `deform`）经编辑器自身的入口在编辑画布的脸部网格上悬停（或在 Deform 模式拖动全部点），不移动系统指针；另报告合成器的帧数与整幅重画次数 | `preview-fps/report.txt`、`{cubism,p2lrt}.{jfr,svg}`、`*-paused.png` |
| `PreviewWindowTool` | 打开真实编辑器窗口（Skia 使用 OpenGL），检查编辑画布与两种预览后端在窗口的 Skia 上下文中出帧、播放两秒收到的帧数，并从 GPU 读出各视图纹理（不截屏）；卡住两分钟时转储线程并退出 | `preview-window/report.txt`、`{edit,cubism,p2lrt}.png` |
| `TexturePerfTool` | 纹理命令（固定图块、改密度、自动排布、改预算）的提交耗时：`runtime` 经应用命令并单独计时重建，`TEXTURE_PERF_SCENARIO` 取 `plain`、`skeleton`（自动骨架）或 `deleted`（自动骨架并软删除一层），`TEXTURE_PERF_JFR=1` 另把这些轮次录成 `texture-perf/runtime.jfr`；`desktop` 走视图模型与桌面适配器，记录 Swing 线程停顿、界面抓取与页面 PNG。环境变量不是 Gradle 任务输入，换场景时加 `--rerun` | `texture-perf/runtime-<场景>.txt`、`desktop.txt` |
| `ExportDialogTool` | “文件”菜单展开“导入”与“导出为”子菜单、每个目标的“导出为”对话框、Live2D 与 PSD 导出对话框，以及“导出成功”窗口（中英文；成功窗口另有浅色主题），用于检查菜单分组、标签与控件布局 | `export-dialog/<语言>-<目标或菜单>.png` |
| `ModalDialogTool.renderDialogs` / `.depthSplit` | 共用弹窗外框上的弹窗（中文），用于检查标题栏、正文与底部操作栏是否一致：`renderDialogs` 为设置、帮助、纹理高清化与绘制顺序弹窗（深色主题），`depthSplit` 为前后分层弹窗（深色、浅色与大字号） | `modal-dialog/<弹窗>.png`、`depth-split-{dark,light,large-text}.png` |
| `CanvasLookSheetTool.meshLook` / `.toolIcons` | 画布绘制样式的拼图：`meshLook` 为可编辑几何（`MeshLook`）在暗、中、亮与饱和底图上的各种状态（结构、选中、悬停、笔刷影响范围）；`toolIcons` 为全部画布工具图标与预览侧栏开关，深浅两种主题 | `canvas-look/mesh-look.png`、`tool-icons-{dark,light}.png` |
| `CommitPerfTool.profile` / `.desktop` | 单次作者提交的耗时：`profile` 走应用层命令边界并按阶段拆分（修订号、配置解码、重建、几何检查）；`desktop` 走桌面视图模型与适配器，连续提交网格顶点编辑和画笔笔触，报告提交耗时与界面线程最长停顿。可配合 `JAVA_TOOL_OPTIONS=-XX:StartFlightRecording=...` 采样 | `commit-perf/report.txt`、`desktop.txt` |
| `CommitPerfTool.baseline` | 提交路径的逐阶段基线：在带自动骨架、两个摆动和一个已烘焙模拟的工程上，分别测量完整重建（不读取进程内已存的作者 Rig，生成基础 Rig 并重放日志）的各阶段（分析、纹理集打包与 PNG 编码、基础 Rig、骨架缓存命中/未命中、日志重放、摆动/模拟写回、覆盖、IR、moc3 打包、`validateBundle`、修订号哈希、运行时文件写出），以及几何提交、小图层绘画、换图（同形/重建网格）、无关拓扑编辑后的骨架缓存命中和日志追加到 50/200 条时的提交耗时与增长；每次提交后写入历史存储。不测原生预览重载（需要 GL 上下文） | `commit-perf/baseline.json`、`baseline.md` |
| `CommitPerfTool.rigStages` | 只做 `baseline` 的准备与生成类提交（图层分类、图层网格），记录每次提交中 Rig 构建的各阶段，再完整重建结果；`PSD2LIVE_RIG_MODES` 取 `staged` 或 `unstaged` 时只测阶段缓存的一种模式，默认两种都测 | `commit-perf/rig-stages.json`、`rig-stages.md` |
| `SkeletonCommitTool.profile` / `.equivalence` | 骨架提交：`profile` 在放大 `PSD2LIVE_SCALE` 倍（默认 2）的样例上先做 `PSD2LIVE_GEOMETRY_EDITS` 次（默认 24）几何提交，再经命令边界新建、绑定骨骼并移动关节（含蒙皮最大网格的肢体关节），报告提交耗时与 Rig 构建各阶段；`equivalence` 在骨架、几何、顶点组提交及撤销/重做/切换分支后，逐步比较运行时模型与清空缓存、不用已存作者 Rig 的冷重建与重放 | `skeleton-commit/report.txt`、`equivalence.txt` |
| `SkeletonBakeDiffTool` | 骨架烘焙的前后对比：记录放大样例（`PSD2LIVE_SCALE`，默认 2）上每个蒙皮网格在各关键形格点与混合形键处的画布坐标，并渲染各肢体的极限姿势；`PSD2LIVE_LABEL` 命名本次结果，设 `PSD2LIVE_REFERENCE` 时与该结果比较，报告各网格最大顶点差（像素）与渲染差 | `skeleton-bake/<label>.bin`、`<label>-limbs.png`、`<label>-vs-<reference>.png` |
| `OpenPerfTool.profile` | 按应用的路径打开工程（`ProjectRepository.open` 后由新的 `WorkspacePreviewBuilder` 构建头部）的耗时：在带自动骨架、两个摆动和一个已烘焙模拟的工程上，保存三种归档——应用现在的写法（各修订的作者 Rig 与头部缓存）、仅头部缓存（无已存 Rig 时的生成与重放）、两者皆无——各在空的作者 Rig 与骨架缓存下打开 3 次，测量解包/种入与头部构建，并核对三者构建的模型相同 | `open-perf/report.json`、`report.md` |
| `SavePerfTool.profile` | 保存现实规模的生成工程的耗时（`PSD2LIVE_SAVE_LAYERS`、`PSD2LIVE_SAVE_SIZE`、`PSD2LIVE_SAVE_REVISIONS`）：同一捕获保存 3 次，重开后保存 2 次，再打开 1 次 | `save-perf/report.txt` |
| `ExportTargetsScenario`、`GenerateAndRigScenario`、`PaintAndReplaceScenario`、`SplitThenRegenerateScenario`、`Cmo3ImportScenario`、`MotionScenario` | 合成角色上的长端到端场景，与测试套件中的场景（`scenario/`）一样逐步检查冷重放、撤销与质量：经每个导出目标导出、从导入到导出的第一次会话、骨架下的绘画与换图、拆分后再生成、cmo3 导入编辑后导出回 cmo3、内置动作编辑与导出。整条流水线跑得太久，不进测试套件；改动生成、导出或保存后运行 | 仅测试结果 |
| `RigSourcePerfTool.profile` | 大图（示例放大 `PSD2LIVE_SCALE` 倍，默认 3）带生成输入时重建中按像素计的阶段：每第 3 层裁小几像素（需补齐到固定矩形）、每第 5 层为 2 倍密度；冷构建、热重建、绘制后重建的分阶段耗时，以及冷合成图集页与预览 PNG 编码 | `rig-source-perf/report.txt` |
| `MeshTraceTool.compare` / `.wrap` | `compare` 把合成图层（1024² 贴图上带睫毛的眼睛、头发细束、画布分辨率图层、6000 与 2048 文档中的图层）分别以画布描轮廓与贴图描轮廓生成网格，并排渲染（灰为贴图 alpha、蓝为网格、红为网格外的不透明贴图像素），报告顶点数、耗时、`detail`、网格外像素与网格面积；`wrap` 以贴图描轮廓在包裹 0–32 下为睫毛、头发细束与手生成网格，报告顶点数、轮廓环数、耗时与网格外像素 | `mesh-trace/<用例>.png`、`report.txt`；`mesh-wrap/<用例>.png`、`report.txt` |
| `StructureMeshTool` | 按纹理结构重布点：在示例 PSD（`PSD2LIVE_STRUCTURE_PSD`，默认 tml）的衣物与头发图层（或 `PSD2LIVE_STRUCTURE_LAYERS` 列出的图层）上运行 `layer_mesh_structure` 的计划，并排绘出旧网格与新网格及识别线（褶皱橙、阴影蓝、图案紫，未采用的线变淡），报告顶点数、采用的线、褶皱带数与耗时；`PSD2LIVE_STRUCTURE_SETTINGS` 传入设置 JSON | `structure-mesh/<网格>.png`、`report.txt` |
| `ReadmeShotsTool` | 打开真实编辑器窗口，在开始页选「完整」预设后导入 `PSD2LIVE_SAMPLE`，再用「按网格拆分图层」接受全部拆分，等模拟烘焙完，提交自动骨架，再截取各工作区与画布各模式的窗口帧（不截屏），并把工程存为 `project.psd2live`；`PSD2LIVE_SHOT_REUSE=1` 重开已存的工程，`PSD2LIVE_SHOT_W` / `_H` 设窗口大小。用于首页截图 | `readme-shots/<示例>/*.png`、`project.psd2live` |
| `ReadmeShowcaseTool` | 首页测试图：`meshStress`、`meshParams`、`meshWrap` 为合成图层的网格（孔洞与细桥、边缘层数 × 填充算法、包络宽度）；`rigHead`、`rigFace`、`rigBody` 为两个示例的头部、五官与骨架预设姿态；`rigKnee` 为 `PSD2LIVE_KNEE_SAMPLE`（工程或 PSD）的膝盖弯折；`bodyOrbit` 给 `PSD2LIVE_ORBIT_PROJECTS` 中各工程临时加一段身体 X / Y 上下、左右、椭圆运动的动作，按物理开、关各导出 PNG 帧（`PSD2LIVE_ORBIT` 设幅度，`PSD2LIVE_ORBIT_PERIOD` 设每轮秒数） | `readme-plates/*.png`、`readme-orbit/<工程>-{on,off}/` |

| 环境变量 | 作用 |
| --- | --- |
| `PSD2LIVE_SAMPLE` | 示例名（`tml`、`ds`）或 PSD 路径，默认 `tml`；`CommitPerfTool.desktop` 也接受 `.psd2live` 工程 |
| `PSD2LIVE_CMO3` | `ModelProfileTool.cmo3` 的输入：`.cmo3` 文件或其所在目录 |
| `PSD2LIVE_PROBES` | 运动剖面探测的参数，`id=值,...`；默认身体 X、Y、Z 的端点 |
| `PSD2LIVE_SHEET_PARAM` | 剪影改为沿此参数展开，代替身体 X × 身体 Y |
| `PSD2LIVE_BONES` | `MotionSheetTool.body` 中修正自动骨架的骨骼位置，`id=头x,头y,尾x,尾y;...`（画布像素） |
| `PSD2LIVE_BIND_LEGS` | 设为 `1` 时把腿和鞋的网格绑定到第一根大腿骨 |
| `PSD2LIVE_ZOOM` | 腿部特写的范围，`左,上,右,下`，按画布比例 |
| `PSD2LIVE_VERBOSE` | 设为 `1` 时 `motions` 另外打印每条曲线 |
| `PSD2LIVE_BAKE_CONFIGS` | `SimBakeSuite` 的设置，`模态数:键数,...`；默认 `preset`（各部件自己的设置） |
| `PSD2LIVE_SIM_PARTS` | `SimBakeSuite` / `SimRootDofExperiment` 的部件，逗号分隔，默认全部 |
| `PSD2LIVE_BAKE_METHODS` | `SimBakeSuite` 的烘焙方法，`legacy,dof` |
| `PSD2LIVE_SIM_INPUTS` | `SimBakeSuite` 只保留这些输入 |
| `PSD2LIVE_SIM_CSV` | `SimBakeSuite` 把这些动作的尖端轨迹、模态值和输入写成 CSV |
| `PSD2LIVE_RUNTIME_PROJECT` | `RuntimeConformanceTool.generate` 另外写出的工程（`.psd2live` 或 PSD），用例名为 `project` |
| `PSD2LIVE_GEOMETRY_EDITS` | `SkeletonCommitTool.profile` 在骨架提交前做的几何提交次数，默认 24 |
| `PSD2LIVE_RIG_MODES` | `CommitPerfTool.baseline` / `.rigStages` 的生成类提交只测 `staged` 或 `unstaged`，默认 `both` |
| `PSD2LIVE_TEST_CUBISM_CORE` | 官方 Cubism Core 动态库的路径，供 `CubismCoreCheckTool` 与 `:umamo` 的 `MocDefaultColorsTest`；未设置时二者跳过 |

## 代码结构

源码分为多个 Gradle 模块，依赖只能向下，由编译期保证：从 Umamo 移植的引擎 `org.umamo.*` 在 `:umamo`（`umamo/src/main/kotlin/`，不依赖产品代码）；中立绑定 IR `:format-model`、导出框架 `:format-compile` 与光栅导出 `:targets:raster`、运行时模型 `:targets:runtime` 与运行时绑定 `:format-eval` 为 MIT，不依赖任何 GPL 模块；`:targets:cubism`（IR 转换器、moc3、cmo3）与 `:targets:psd` 使用引擎；产品层 `io.github.psd2live.*` 在根项目（`src/main/kotlin/`）。导出模块见[中立绑定 IR 与导出目标](../spec/EXPORT_TARGETS.md)。引擎包如下：

| 包 | 职责 |
| --- | --- |
| `org.umamo.runtime` | `PuppetModel`、关键形插值与求值 |
| `org.umamo.format` | PSD、CMO3、MOC3 及图像格式的读写 |
| `org.umamo.interop` | `PuppetModel` 与 CMO3 / MOC3 的相互转换 |
| `org.umamo.render` | LWJGL / OpenGL 预览 |
| `org.umamo.edit` | 对模型的不可变编辑原语 |
| `io.github.psd2live.core` | 生成流水线（`PSD2LivePipeline`、`LayerClassifier`、`AdaptiveMeshGenerator`、`RigBuilder`、`MotionGenerator`、`PhysicsGenerator`）与各类可重放编辑 |
| `io.github.psd2live.project` | 中立工作区文档（`WorkspaceDocument`）、历史存储（`WorkspaceStore`）与 `.psd2live` 归档；不依赖 `application`、Compose 或 MCP |
| `io.github.psd2live.application` | 中立运行时（`WorkspaceRuntime`）、命令注册与 schema、预览会话、进程内后台任务；界面与 MCP 共用 |
| `io.github.psd2live.history` | 分支式撤销 / 重做 |
| `io.github.psd2live.agent` | 本地 MCP 服务与公开工具定义 |
| `io.github.psd2live.render` | 编辑画布与 p2lrt 预览的 GPU 渲染（`CanvasGpu`、`GlCanvasRenderer`、`P2lrtGlRenderer`、`SkiaGpu`） |
| `io.github.psd2live.ui` | Compose 界面：`state`（ViewModel、快捷键注册表）、`views`（工作区与面板）、`components`（对话框与控件）、`tutorial`（交互教程）；画布编辑与绘画位于 `ui` 根包 |
| `io.github.psd2live.i18n` | 界面文案，资源在 `src/main/resources/i18n/` |

生成与导出逻辑放在 `core` / `project` / `application`，不要写进 Compose 界面代码。

开始、帮助与设置是应用页面（`ui/state/AppPages.kt` 的 `AppPage`），不进工程：每个页面复用编辑器的停靠框架（`DockWorkspaceView` 中的 `DockSession` 与 `DockTree`），每页都是目录面板加内容面板（开始页另有详情面板），面板 ID 带页面前缀（`start.*`、`help.*`、`settings.*`），共用 `PageChrome.kt` 的目录行与正文容器，默认排列见 `pageDockLayout`，排列与关闭的面板存在本机偏好（`AppSettings.pageArrangement`）。面板会被拖动、浮动和重新停靠，彼此共享的状态放在 ViewModel 或 `StartPageModel` 中，不放在单个面板的组合里。功能更新的文字在 `src/main/resources/whatsnew/<语言>.md`，四种语言的版本与条目须一一对应（`WhatsNewTest`）；版本号由构建写入 `psd2live/version.properties`，代码读 `AppVersion`。

## 核心约束：重建与重放

`PuppetModel` 不是持久状态。工程保存的是源图、图层分类与设置，以及一份可序列化的编辑记录（`RigEditOverlay`）。每次打开工程或发生修改时：

1. `RigBuilder` 从源图重新生成基础 Rig；
2. 作者阶段（`RigEditOverlay.replayAuthored`）：从日志中最后一条 `rig_checkpoint` 记录保存的 Rig 开始，没有时从基础 Rig 开始并先重放旧格式的静态字段（参数删除 / 创建、Warp、结构、关键形），然后按实际顺序重放其后的 `authoringJournal`；
3. 收尾（`RigEditOverlay.finish`）：按生成器依赖图（`core/DocumentGenerators.kt`）运行摆动、模拟等生成器，三方合并生成结果覆盖（`generated_override`），再重放引用生成参数的面板编辑。

已存有作者 Rig 的修订（本进程构建过的，或归档 `rig/` 中保存的）直接从它构建，不生成基础 Rig、也不重放日志。详见[固化 Rig](../spec/MATERIALIZED_RIG.md)与[文档层](../spec/DOCUMENT_LAYER.md)。

因此新增编辑功能时：

- 修改必须写入 `RigEditOverlay` 的可序列化字段（新命令优先作为编辑日志中的 JSON 命令），并在工作区状态编解码中保存与恢复。只改 `PuppetModel` 的修改会在重建后丢失。
- 界面与 MCP 应调用同一套编辑命令。`org.umamo.edit` 中的底层方法不自动等同于公开接口。
- 验收链路：领域数据 → 历史重放 → 工程保存与恢复 → 目标 Cubism 版本处理 → 导出读回 → 视觉检查。详见[运行时与导出边界](../spec/RUNTIME_EXPORT_ARCHITECTURE_AND_GAPS.md)。

界面文案新增时，`Messages.properties`、`Messages_zh_CN.properties`、`Messages_ja.properties`、`Messages_ko.properties` 四个文件需要同时添加，保持键数一致（`CatalogContractsTest` 检查）。

## 检查导出结果

- `.model3.json` 引用的所有文件都要随模型交付。
- 查看日志面板与诊断 JSON 中的警告。
- 需要继续编辑时保存 `.psd2live` 工程；`.psd2live.json` 只是报告。

相关：[Cubism Native 预览](CUBISM_SDK_SETUP.md) · [CI 与发行](CUBISM_CI_RELEASE.md) · [纹理高清化](TEXTURE_UPSCALE.md) · [`build.gradle.kts`](../../../build.gradle.kts)
