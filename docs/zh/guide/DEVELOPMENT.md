# 开发与命令行

[文档目录](../../README.md) · [English](../../en/guide/DEVELOPMENT.md) · [日本語](../../ja/guide/DEVELOPMENT.md)

本页面向从源码运行、使用命令行或参与开发的用户。发布包自带运行时，普通使用不需要本页内容。

## 环境

- JDK 21。Gradle 使用仓库自带的 Wrapper，无需单独安装。
- Windows 用 `.\gradlew.bat`，Linux / macOS 用 `./gradlew`。下文示例统一写作 `./gradlew`。
- 官方 Cubism SDK 不是构建前提。源码构建默认使用内置渲染；原生预览见 [Cubism Native 预览](CUBISM_SDK_SETUP.md)。

## 运行

```bash
./gradlew run                     # 不带参数：启动 GUI
./gradlew run --args="--help"     # 查看 CLI 帮助
./gradlew run --args="--input examples/tml/psd-input/tml.psd --output build/example-output"
```

Windows 也可以直接运行根目录的 `run-gui.bat`。不带参数启动 GUI；带参数进入 CLI，此时必须提供 `--input`（`--help` 除外）。CLI 从 PSD 直接生成导出文件，不产生可继续编辑的 `.psd2live` 工程。

## CLI 参数

| 参数 | 默认值 | 作用 |
| --- | --- | --- |
| `--input <path>` | 必填 | 输入的分层 PSD |
| `--output <path>` | PSD 同目录下的 `psd2live-output` | 导出目录 |
| `--lang <zh\|en\|ja>` | 系统语言 | 日志语言 |
| `--atlas <size>` | 4096 | 纹理图集尺寸 |
| `--mesh-spacing <px>` | 64 | 网格间距 |
| `--mesh-pixels` | 关闭 | 按源像素而非长边缩放到 2048 px 的文档像素计算网格长度 |
| `--head-strength <value>` | 1.0 | 头部形变幅度 |
| `--body-strength <value>` | 1.0 | 身体形变幅度 |
| `--mesh-only` | 关闭 | 仅生成网格 |
| `--no-deformers` | 关闭 | 不生成变形器 |
| `--no-motions` | 关闭 | 不输出动作 |
| `--no-physics` | 关闭 | 不生成物理 |
| `--no-cmo3` | 关闭 | 不输出 CMO3 |
| `--no-moc3` | 关闭 | 不输出 MOC3 |
| `--no-json` | 关闭 | 不输出诊断 JSON |
| `--upscale <1\|2\|4>` | 1 | 纹理高清化倍率，1 为关闭 |
| `--upscale-python <path>` | `python` | 装有 nunif 依赖的 Python |
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
./gradlew test --tests "io.github.psd2live.core.SwingDeformerTest"   # 单个测试类
./gradlew createDistributable                    # 带运行时的应用目录
./gradlew packageDistributionForCurrentOS        # 当前平台的安装包
```

- 两种打包都只包含当前构建平台的原生库，并在应用资源目录附带 `LICENSE`、`THIRD_PARTY_NOTICES.md` 与 `licenses/`。
- 官方 SDK 资源（`src/main/resources/cubism/`）默认不打包，只有传入 `-Ppsd2live.includeCubism=true` 或设置 `PSD2LIVE_INCLUDE_CUBISM=true` 时才包含。含 SDK 的包不得公开分发，发行流程见 [CI 与发行](CUBISM_CI_RELEASE.md)。
- Linux 也可用 `./native/package_linux.sh` 生成需要系统 JDK 21 的本地启动包（输出到 `dist/linux-<时间戳>/`），详见 [native/README.md](../../../native/README.md)。
- 项目没有独立的 lint 任务，代码风格为 `kotlin.code.style=official`。
- Rust 运行时在 `runtime/` 中用 `cargo test`、`cargo build --release` 构建，见[运行时](../spec/RUNTIME.md)。
- 测试套件只保留快速的单元与契约测试，全量 `./gradlew test` 应在 1 分钟内完成。不要加入在示例 PSD（tml、ds）上跑完整流水线、逐步对比冷重放或渲染整幅画面的测试；这类检查写成下面的开发工具，需要时手动运行。

## 开发工具

`src/test/kotlin/io/github/psd2live/tools/` 中是用于目视检查和测量的开发工具。它们写成测试，以便调用流水线内部接口；只有设置 `PSD2LIVE_TOOLS=1` 时才运行，平时的 `./gradlew test` 会跳过。输出写到 `build/tools/`。

```bash
PSD2LIVE_TOOLS=1 PSD2LIVE_SAMPLE=ds ./gradlew test --tests "io.github.psd2live.tools.MotionSheetTool.body"
```

| 工具 | 内容 | 输出 |
| --- | --- | --- |
| `MotionSheetTool.motions` | 每个预设动作按时间展开的拼图、待机循环、呼吸前后与差分图、身体 Z | `motion-sheet/<示例>-*.png` |
| `MotionSheetTool.body` | 身体 X × 身体 Y、腿部和上半身特写、前后倾、大小变、腿部姿势，分别为无骨架和自动骨架 | `motion-sheet/<示例>-{stance,lean,size,legposes}*.png` |
| `MotionSheetTool.tracking` | 指针在画面上慢速绕行 12 秒，头和身体按预览的增益与速度跟随 | `motion-frames/<示例>-track/` |
| `MotionSheetTool.idle` | 待机 12 秒的逐帧图 | `motion-frames/<示例>-idle/` |
| `ModelProfileTool.cmo3` | `.cmo3` 的参数、变形器树（网格轴与范围）、图形网格、分段运动剖面、身体参数下各网格的位移，身体 X × 身体 Y 的剪影，以及物理组 | `model-profile/<名称>.txt`、`.png`、`-physics.txt` |
| `ModelProfileTool.sample` | 生成模型（无骨架和自动骨架）的分段运动剖面、身体图层与自动骨骼 | `model-profile/<示例>.txt` |
| `SimBakeBenchmark` | 在 `tml` 后发上按几组设置烘焙模拟，在未参与拟合的动作上对比模拟与导出结果，见[模拟与烘焙](SIMULATION.md) | 标准输出 |
| `CanvasPerfTool` | 在 Xvfb 下打开真实窗口，对编辑画布依次做静止、悬停、滚轮缩放、中键平移、变形模式拖动脸部全部点，GPU 渲染与软件渲染各一轮，报告帧间隔、界面线程延迟与界面线程热点；需 `xvfb-run -a -s "-screen 0 1920x1080x24"` | `canvas-perf/report.txt`、各阶段截图与 `.jfr` |
| `DragonBonesFidelityTool` | 把 `tml`、`ds`（无骨架和自动骨架）导出为 DragonBones，用 `tools/dragonbones-check`（官方 DragonBones 5.7 运行时核心，需要 node）播放：参数动画在每个关键帧上与编辑器求值器比较（误差不超过导出报告的容差），并测量片段误差 | `dragonbones-fidelity/report.txt` |
| `RuntimeConformanceTool` | Rust 运行时的参考数据：随机模型（Warp、旋转、嵌套、稀疏网格、混合形、Glue、通道、部件）、样例与本地工程在随机姿势下的编辑器求值结果，以及随机摆锤组和样例物理的逐帧轨迹；用 `runtime/` 中的 `p2lrt-conformance` 比较 | `runtime-conformance/<用例>/`、`runtime-physics/<用例>/` |
| `WarpProbeTool` | 编辑器求值器的黑盒探测：Warp 映射（格子内外）、Warp 下的旋转框架、翻转、混合形、稀疏网格，供运行时独立实现对照 | `warp-probe/*.tsv` |
| `SwingCostTool` | tml 上两个摆动的生成耗时、朴素哈希其输入的耗时与全部编辑重放耗时，用于判断生成器是否值得接入生成缓存 | `swing-cost/report.txt` |
| `GeneratorCostTool` | 各生成器（Rig 生成有无骨架、骨架烘焙缓存前后、物理组目录、生成动作缓存前后、Rig IR 编译、模拟烘焙与写回）的耗时，及对其输入做内容哈希的耗时；`PSD2LIVE_SAMPLE` 指定样例 | `generator-cost/report.txt` |
| `ExportGoldenTool` | `tml`、`ds` 的无骨架、自动骨架和自定义动作三种变体的全部导出文件摘要（cmo3 取读回后降级为 moc3 的摘要），用于逐字节对比重构前后的导出；`PSD2LIVE_GOLDEN_LABEL` 指定输出名 | `export-golden/<名称>.txt` |
| `ArtPrimitiveV2VisualTool` | 在 `tml` 上把腿按侧连通块拆分、左眼睫多边形拆分、嘴部多边形拆分分别写成版本 1 与版本 2 记录，渲染未拆分、v1、v2 与 v1/v2 差异（×4）在睁眼/半闭/闭眼、闭嘴/张嘴/张嘴笑等姿态下的对照，检查接缝、错位与缺失像素 | `art-primitive-v2/{legs,eye,mouth}.png` |
| `SafetyGoldenTool` | 自动骨架 Rig 上 24 组带种子的随机几何编辑的完整几何安全报告（不含 `coverage`），用于逐字节对比检查器改动前后的分类；`PSD2LIVE_GOLDEN_LABEL` 指定输出名 | `safety-golden/<名称>.txt` |
| `BundleProfileTool` | moc3 预览包的分项耗时（IR 编译、IR 转回、静止网格换到画布空间、physics3/motion3、moc 降级与写出、cdi3）及几何安全检查耗时；`PSD2LIVE_SAMPLER=1` 另打印栈采样的热点 | 仅标准输出 |
| `TextureWorkspaceTool` | 用 `tml` 设置几种密度与锁定并按网格形状排布一次后，渲染纹理集页面与纹理面板（中英文、单选/多选/未选、热力图开关、密度滑块预览），以及编辑画布的纹理集像素与原始像素对比 | `texture-workspace/*.png` |
| `AtlasFramePerfTool` | 无窗口测量 `tml` 纹理集页面与纹理面板的帧耗时：向 `ImageComposeScene` 发送空闲、悬停、拖动角点（密度）、滚轮缩放、拖动图块与关闭线框后悬停的指针输入，记录每次渲染耗时（中位数、p90、最大）；先软件渲染，能取得 OpenGL 时再走 GPU，各保存角点拖动中、静止页面与放大近景三帧 | `atlas-frame-perf/report.txt`、`software-*.png`、`gpu-*.png` |
| `AtlasWindowPerfTool` | 打开真实编辑器窗口进入纹理工作区（`PSD2LIVE_SAMPLE` 可指定 `.psd` 或 `.psd2live`，请用副本），以 AWT 事件向窗口派发指针输入（不移动真实光标）：经视图模型提交移动与密度、拖动图块、拖动角点、六次不等待的连续拖动；记录帧间隔、界面线程延迟、各忙碌标志何时落下、界面线程热点、拖动预览绘制次数与各视图的 GPU 帧数；截图取自窗口自身的 Skia 帧，不截屏幕 | `atlas-window-perf/report.txt`、`*.jfr`、`*-after.png` |
| `TexturePerfTool` | 纹理命令（固定图块、改密度、自动排布、改预算）的提交耗时：`runtime` 经应用命令并单独计时重建，`TEXTURE_PERF_SCENARIO` 取 `plain`、`skeleton`（自动骨架）或 `deleted`（自动骨架并软删除一层），`TEXTURE_PERF_JFR=1` 另把这些轮次录成 `texture-perf/runtime.jfr`；`desktop` 走视图模型与桌面适配器，记录 Swing 线程停顿、界面抓取与页面 PNG。环境变量不是 Gradle 任务输入，换场景时加 `--rerun` | `texture-perf/runtime-<场景>.txt`、`desktop.txt` |
| `ExportDialogTool` | “文件”菜单展开“导入”与“导出为”子菜单、每个目标的“导出为”对话框、Live2D 与 PSD 导出对话框，以及“导出成功”窗口（中英文；成功窗口另有浅色主题），用于检查菜单分组、标签与控件布局 | `export-dialog/<语言>-<目标或菜单>.png` |
| `ModalDialogTool` | 共用弹窗外框上的设置、帮助、纹理高清化、绘制顺序与重建网格弹窗（中文、深色主题），用于检查标题栏、正文与底部操作栏是否一致 | `modal-dialog/<弹窗>.png` |
| `CommitPerfTool.profile` / `.desktop` | 单次作者提交的耗时：`profile` 走应用层命令边界并按阶段拆分（修订号、配置解码、重建、几何检查）；`desktop` 走桌面视图模型与适配器，连续提交网格顶点编辑和画笔笔触，报告提交耗时与界面线程最长停顿。可配合 `JAVA_TOOL_OPTIONS=-XX:StartFlightRecording=...` 采样 | `commit-perf/report.txt`、`desktop.txt` |
| `CommitPerfTool.baseline` | 提交路径的逐阶段基线：在带自动骨架、两个摆动和一个已烘焙模拟的工程上，分别测量完整重建的各阶段（分析、纹理集打包与 PNG 编码、基础 Rig、骨架缓存命中/未命中、日志重放、摆动/模拟写回、覆盖、IR、moc3 打包、`validateBundle`、修订号哈希、运行时文件写出），以及几何提交、小图层绘画、换图（同形/重建网格）、无关拓扑编辑后的骨架缓存命中和日志追加到 50/200 条时的提交耗时与增长；每次提交后写入历史存储。不测原生预览重载（需要 GL 上下文） | `commit-perf/baseline.json`、`baseline.md` |
| `OpenPerfTool.profile` | 打开工程的耗时：在带自动骨架、两个摆动和一个已烘焙模拟的工程上，分别保存带与不带头部缓存的归档，各在空骨架缓存下打开 3 次，测量解包/种入与头部重建，并核对两者重建的模型相同 | `open-perf/report.json`、`report.md` |
| `SavePerfTool.profile` | 保存现实规模的生成工程的耗时（`PSD2LIVE_SAVE_LAYERS`、`PSD2LIVE_SAVE_SIZE`、`PSD2LIVE_SAVE_REVISIONS`）：同一捕获保存 3 次，重开后保存 2 次，再打开 1 次 | `save-perf/report.txt` |
| `Cmo3HiresTool` | 高密度图层写入 `.cmo3` 的调研：把 tml 的一个眼部图层栅格放大 4 倍（中间三分之一带一像素棋盘格），按基线、图层保持画布分辨率仅纹理集高清、图层高清加模型图像缩放仿射（图层矩形取栅格尺寸或画布尺寸）、整个分层图像放大 4 倍各写一份，并用读取器回读放置链；文件供在 Cubism Editor 中人工检查 | `cmo3-hires/*.cmo3`、`report.txt`、`README.txt` |

| 环境变量 | 作用 |
| --- | --- |
| `PSD2LIVE_SAMPLE` | 示例名（`tml`、`ds`）或 PSD 路径，默认 `tml`；`CommitPerfTool.desktop` 也接受 `.psd2live` 工程 |
| `PSD2LIVE_CMO3` | `ModelProfileTool.cmo3` 的输入：`.cmo3` 文件或其所在目录 |
| `PSD2LIVE_HIRES_TILE` | `Cmo3HiresTool` 放大的图层名，默认最小的眼部图层 |
| `PSD2LIVE_PROBES` | 运动剖面探测的参数，`id=值,...`；默认身体 X、Y、Z 的端点 |
| `PSD2LIVE_SHEET_PARAM` | 剪影改为沿此参数展开，代替身体 X × 身体 Y |
| `PSD2LIVE_BONES` | `MotionSheetTool.body` 中修正自动骨架的骨骼位置，`id=头x,头y,尾x,尾y;...`（画布像素） |
| `PSD2LIVE_BIND_LEGS` | 设为 `1` 时把腿和鞋的网格绑定到第一根大腿骨 |
| `PSD2LIVE_ZOOM` | 腿部特写的范围，`左,上,右,下`，按画布比例 |
| `PSD2LIVE_VERBOSE` | 设为 `1` 时 `motions` 另外打印每条曲线 |
| `PSD2LIVE_BAKE_CONFIGS` | `SimBakeBenchmark` 的设置，`模态数:键数,...`，默认 `2:5,2:7,1:5` |

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
| `io.github.psd2live.project` | `.psd2live` 归档、会话与工作区状态序列化 |
| `io.github.psd2live.history` | 分支式撤销 / 重做 |
| `io.github.psd2live.agent` | 本地 MCP 服务与公开工具定义 |
| `io.github.psd2live.ui` | Compose 界面：`state`（ViewModel、快捷键注册表）、`views`（工作区与面板）、`components`（对话框与控件）、`tutorial`（交互教程）；画布编辑与绘画位于 `ui` 根包 |
| `io.github.psd2live.i18n` | 界面文案，资源在 `src/main/resources/i18n/` |

生成与导出逻辑放在 `core` / `project`，不要写进 Compose 界面代码。

## 核心约束：重建与重放

`PuppetModel` 不是持久状态。工程保存的是源图、图层分类与设置，以及一份可序列化的编辑记录（`RigEditOverlay`）。每次打开工程或发生修改时：

1. `RigBuilder` 从源图重新生成基础 Rig；
2. `RigEditOverlay.applyTo` 按固定顺序重放编辑：参数删除 / 创建 → Warp 与结构 → 关键形 → 按实际顺序记录的编辑日志 → 最后生成摇摆。

因此新增编辑功能时：

- 修改必须写入 `RigEditOverlay` 的可序列化字段（新命令优先作为编辑日志中的 JSON 命令），并在工作区状态编解码中保存与恢复。只改 `PuppetModel` 的修改会在重建后丢失。
- 界面与 MCP 应调用同一套编辑命令。`org.umamo.edit` 中的底层方法不自动等同于公开接口。
- 验收链路：领域数据 → 历史重放 → 工程保存与恢复 → 目标 Cubism 版本处理 → 导出读回 → 视觉检查。详见[运行时与导出边界](../spec/RUNTIME_EXPORT_ARCHITECTURE_AND_GAPS.md)。

界面文案新增时，`Messages.properties`、`Messages_zh_CN.properties`、`Messages_ja.properties` 三个文件需要同时添加，保持键数一致。

## 检查导出结果

- `.model3.json` 引用的所有文件都要随模型交付。
- 查看日志面板与诊断 JSON 中的警告。
- 需要继续编辑时保存 `.psd2live` 工程；`.psd2live.json` 只是报告。

相关：[Cubism Native 预览](CUBISM_SDK_SETUP.md) · [CI 与发行](CUBISM_CI_RELEASE.md) · [纹理高清化](TEXTURE_UPSCALE.md) · [`build.gradle.kts`](../../../build.gradle.kts)
