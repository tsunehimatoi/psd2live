# 中立绑定 IR 与导出目标

所有导出都从同一份中立绑定 IR 出发：编辑器把当前已提交的 Rig 编译成 IR，每个导出目标再把 IR 降级成自己的格式，并写出一份损失报告，列出该格式无法保留的内容。

## 模块

| 模块 | 内容 | 许可证 |
| --- | --- | --- |
| `:format-model` | 中立绑定 IR（`RigIR`）：参数、部件、Warp / 旋转变形器、网格、关键形网格、通道、混合形、Glue、绘制树、贴图页与图块、物理组、动作片段、编辑器附带数据 | MIT |
| `:format-compile` | 导出框架：`ExportTarget`、`CapabilityProfile`、`LossEntry`、`ExportReport`、`Compiler`；降级步骤：能力扫描、按参数烘焙与交叉项误差测量（`ParameterBake`）、关键帧精简（`KeyReduction`）、动作片段采样；宿主接口 `FrameRenderer`、`GeometryEvaluator` | MIT |
| `:targets:raster` | `png-sequence`、`sprite-sheet`、`gif`，以及经 ffmpeg 编码的 `mp4`、`webm`、`mov`、`apng`、`webp` | MIT |
| `:targets:spine` | `spine`：Spine 4.2 骨骼（JSON 或二进制 `.skel`）+ 图集，旋转变形器写成骨骼，其余变形由宿主提供的几何求值器烘焙 | MIT |
| `:targets:dragonbones` | `dragonbones`：DragonBones 5.5 骨骼 JSON + 图集，由宿主提供的几何求值器烘焙变形 | MIT |
| `:targets:runtime` | `p2lrt`：PSD2Live 运行时模型，见[运行时](RUNTIME.md) | MIT |
| `:format-eval` | Rust 运行时的 JVM 绑定（JNA），可作为几何求值器 | MIT |
| `:targets:gltf` | `gltf`：glTF 2.0 二进制（`.glb`），变形按参数关键点烘焙为变形目标，由宿主提供的几何求值器采样 | MIT |
| `:targets:web` | `web`：网页播放器（运行时的 WebAssembly 构建、WebGL 播放脚本、页面与模型） | MIT |
| `:targets:cubism` | `PuppetModel` 与 IR 的双向转换器（`PuppetIr`）、`moc3`、`cmo3`、`vtube-studio`、motion3 / physics3 写出 | GPL-3 |
| `:targets:psd` | `psd-pose`：指定姿势的分层 PSD | GPL-3 |
| 根项目 | `RigIrCompiler`（预览模型 → IR）、`IrFrameRenderer`（宿主提供的渲染器）、`ExportService`、CLI 与界面 | GPL-3 |

MIT 模块不依赖任何 GPL 模块，由 Gradle 依赖关系在编译期保证。

## IR 约定

- 网格的静止位置与各变形器的关键形都在父变形器的空间中：根部为画布像素（y 向下），Warp 子对象为其 0–1 格子空间，旋转子对象为其局部像素坐标。
- 所有 ID 稳定，重新生成后不变。
- 动作片段已经从预设编译成参数曲线；Bezier 段的控制点时间限制在段内，按时间线性推进曲线参数求值（与 Cubism 的 `AreBeziersRestricted` 一致）。
- `restPose` 给出需要画布空间基础网格的格式烘焙基础网格时使用的参数值（例如嘴保持张开，使纹理坐标覆盖整幅图）。
- `PuppetIr` 双向无损：`toPuppet(toIr(model))` 与原模型逐字段一致。两边新增字段时须在同一改动中更新转换器。

## 导出目标

`spine`、`dragonbones`、`gltf` 为实验性目标：按参数逐个烘焙后叠加，组合姿势与编辑器差异明显，已从“文件 → 导出为”菜单隐藏，只能经命令行导出（`psd2live targets` 标注为 experimental），暂停开发、是否继续待定。下文对它们的描述保留现状。

| ID | 类别 | 输出 | 主要损失 |
| --- | --- | --- | --- |
| `moc3` | 结构化绑定 | `.moc3`、model3、physics3、motion3、cdi3、贴图 | 按目标运行时版本剥离不支持的功能 |
| `cmo3` | 结构化绑定 | Cubism Editor 工程；有动作片段时另写同名 `.can3`（Cubism Animator 工程） | 生成器意图不保留；动作片段中眨眼、口型效果曲线不写入（见 [Cubism Animator](#cubism-animator)） |
| `dragonbones` | 结构化绑定 | `_ske.json`、每页 `_tex_<i>.json` 与贴图 | 与 Spine 相同的参数动画与片段烘焙；关键点取整到帧；透明度以整百分比记录；运行时以 16 位偏移寻址每个动画的变形数据，超出时按更大容差精简并报告（骨架模型的整身片段误差较大）；遮罩、屏幕色、物理未写入 |
| `vtube-studio` | 结构化绑定 | `moc3` 的全部文件 + `.vtube.json` | 同 `moc3`；面部跟踪只映射标准参数（头、身体、眼、视线、眉、嘴、呼吸），每个动作片段一个热键；其余设置由 VTube Studio 取默认值 |
| `spine` | 结构化绑定 | 骨骼 JSON 或 `.skel`、`.atlas`、贴图页 | 旋转变形器成为骨骼链，网格挂在最近的旋转骨骼上；每个参数成为一段 1 秒动画（时间即参数归一化值），键入骨骼变换与骨骼空间内的变形，交叉项按 Spine 骨骼运算测量并报告；动作片段按帧采样后精简关键帧；无 Warp、混合形、Glue（已烘焙）；遮罩转为按轮廓多边形裁剪（反相遮罩丢弃）；驱动骨骼旋转的摆锤转为物理约束（近似），其余物理与屏幕色未写入 |
| `p2lrt` | 结构化绑定 | `.p2lrt`（PSD2Live 运行时） | 编辑器附带数据（来源图层、图块、编辑路径）不写入；渲染（遮罩、混合模式）由宿主完成 |
| `web` | 结构化绑定 | `index.html`、`p2l.js`、`p2l_runtime.wasm`、`.p2lrt` | 按运行时头文件的合成规则绘制全部颜色与 Alpha 混合模式（需要读取下层的模式复制网格下方像素后在着色器中合成）和隔离组（子网格先画入图层，再按组的不透明度、乘算/屏幕色、遮罩与混合模式合成）；遮罩（含组遮罩）以模板缓冲近似，纹素不透明度 ≥ 0.5 视为覆盖；无法创建图层帧缓冲时隔离组按直通绘制；需通过 http 访问 |
| `gltf` | 结构化绑定 | `.glb`（无光照材质、变形目标、权重动画） | 每个参数关键点一个变形目标，参数组合为叠加近似并报告误差；绘制顺序按静止值以深度分层；遮罩、混合模式、按参数变化的透明度与物理不写入 |
| `psd-pose` | 合成/时间轴 | 每个可见网格一层，按静止绘制顺序 | 变形器和参数不保留；按参数变化的绘制顺序取静止值 |
| `png-sequence` | 光栅 | 编号 PNG 帧 | 结构全部烘焙为像素 |
| `sprite-sheet` | 光栅 | 网格排列的精灵表 PNG + TexturePacker（hash）JSON | 同上 |
| `gif` | 光栅 | 动态 GIF | 256 色、1 位透明 |
| `mp4` | 光栅 | H.264 视频（ffmpeg） | 无透明，合成到背景色（默认白色） |
| `webm` | 光栅 | VP9 视频，带透明（ffmpeg） | 透明保存在 VP9 侧通道，需要 libvpx 解码 |
| `mov` | 光栅 | ProRes 4444，带透明（ffmpeg） | — |
| `apng` | 光栅 | 动态 PNG（ffmpeg） | — |
| `webp` | 光栅 | 动态 WebP（ffmpeg） | 有损压缩 |

光栅类和 `psd-pose` 通过宿主提供的 `FrameRenderer` 渲染。编辑器的实现（`IrFrameRenderer`）用引擎的 CPU 求值器得到几何，用 `IrColors` 按通道与混合形求出乘算/屏幕色及部件的绘制顺序与分组合成值，再由 `:format-compile` 的 `SoftwareRasterizer`（MIT）逐像素绘制：预乘浮点缓冲、双线性采样、按纹理 alpha 的遮罩（含反相），颜色混合与 alpha 合成按 `p2l_runtime.h` 的合成规则（与编辑器一致）：Cubism 的叠加/乘算不看 alpha 模式并保持目标 alpha，普通配 over 为预乘 source-over，其余组合取 W3C 混合函数、按 alpha 模式的重叠加权并用其 Porter-Duff 系数（over、atop、out、conjoint/disjoint over）。绘制顺序按渲染树：各组内按绘制顺序排序，组按其部件在该姿势下的绘制顺序参与排序；隔离部件的分组先画进清空的图层，再取分组的乘算/屏幕色、以不透明度与遮罩覆盖缩放 alpha，按分组的混合模式合成。帧之间推进摆锤物理。

### 设置

| 目标 | 设置键 |
| --- | --- |
| `moc3` | `physics`、`user_data`、`display_info`、`hidden_parts`、`hidden_meshes`、`guide_parts`（布尔）、`pixels_per_unit`（正数）；缺省取工程导出设置 |
| `cmo3` | `timestamp`（毫秒，默认 0）、`clips`（是否写出 `.can3`，默认 true）；内部未公开的 `layer_art`：`canvas`（默认，密度不为 1 的图层以画布分辨率写入，高分辨率只留在纹理集页，并给出 `texture_size` / `approximated` 损失项“Cubism Editor rebuilds the atlas from canvas-resolution layers”）或 `native`（按原分辨率写入图层，未经编辑器实测），见[文档层](DOCUMENT_LAYER.md#逐层尺寸) |
| `spine` | `binary`（写出二进制 `.skel` 而非 JSON，默认 false）、`clip_fps`（动作采样帧率，默认 15）、`clips`（是否写出动作，默认 true）、`key_tolerance`（关键帧精简容差，像素，默认 0.25）、`sample_pairs` |
| `psd-pose` | `clip` 与 `time`（秒）按动作片段摆姿势，或 `pose`（`ParamAngleX=20,ParamEyeLOpen=0`，覆盖片段）；`scale`（0.25–2，默认 1） |
| 视频类 | 同光栅类，另有 `ffmpeg`（ffmpeg 路径；缺省依次取环境变量 `PSD2LIVE_FFMPEG`、安装包自带的 `resources/ffmpeg/` 与 PATH） |
| 光栅类 | `clip`（默认第一个片段，无片段时为静止姿势）、`fps`（默认片段帧率）、`size`（长边像素，默认 1024）、`background`（ARGB 十六进制，默认透明）、`physics`（默认 true） |

## Cubism Animator

`cmo3` 目标在写出 `<base>.cmo3` 的同时把 IR 的动作片段写成 `<base>.can3`（`Can3`，`:targets:cubism`），旧版“导出 Live2D 模型”在勾选 CMO3 时同样写出；“导出 motion3/动作”关闭或模型只有网格时没有片段，也就不写 can3。

- **容器**：与 cmo3 相同的 CAFF，只有一个混淆、压缩的 `main.xml`。
- **关联模型**：can3 不含模型。唯一的资源按文件名引用同目录的 `<base>.cmo3`；参数按 ID（`live2dParam_<Id>`）关联，部件透明度轨道带同一次导出的 cmo3 中该部件的 GUID。cmo3 的 GUID 每次导出都不同，因此 can3 只与同一次导出的 cmo3 配套。
- **场景**：每个片段一个场景，名称为 `<base>.<file>`，与 moc3 导出的 motion3 文件名一致。场景沿用 Animator 固定的轨道结构：根组轨道与模型轨道，模型轨道上有视觉、参数、部件透明度、口型与眨眼五个效果。片段的时长、帧率、循环、淡入淡出与用户数据（事件）写入场景与模型轨道。
- **曲线**：参数曲线进参数效果（显示名取参数名称，范围取参数范围并扩到覆盖全部关键点），部件透明度曲线进部件效果，整体透明度替换视觉效果的不透明度；曲线自己的淡入淡出写入轨道选项。关键点取整到最近的帧，贝塞尔控制点保留原时间（小数帧）作为两端的手柄，阶梯与反阶梯段保留类型。
- **损失**：眨眼、口型效果曲线在 Animator 中没有对应轨道，与指向模型中不存在的参数或部件的曲线一同不写入，记为 `timeline` / `dropped`。

## Spine

Spine 有骨骼没有参数。导出的骨骼空间原点为画布底边中点，y 向上，并：

- **骨骼**：每个旋转变形器成为一根骨骼，父骨骼为其上方最近的旋转变形器（没有则为根骨骼），即镜像变形器树。骨骼的设置姿势与关键值由导出端按编辑器的旋转规则（见[运行时](RUNTIME.md)“求值规则”：角度为关键形角度加基准角；父级为旋转时原点经父级变换、角度与缩放叠加，父级垂直翻转或负缩放额外转半圈；自身翻转只作用于子坐标；父级为 Warp 时框架保持刚性并随格子方向旋转）求出世界框架，再换算成相对父骨骼的局部值。这些框架都是旋转加带翻转的等比缩放，相乘后仍是同类，因此只用旋转与 scaleX/scaleY（翻转为负缩放），不需要剪切。每根骨骼另带一个固定转角，使骨骼方向指向它承载的网格的中心，长度为网格沿该方向的最远距离。缩放在任一采样姿势下趋于零的旋转不成为骨骼，其子级烘焙到上方骨骼并报告。
- **网格**：每个可见网格为无权重网格附件，槽位挂在其最近的旋转骨骼上，顶点为该骨骼空间中的坐标；骨骼没有表达的部分（Warp、网格关键形、混合形、Glue）以该骨骼空间中的 deform 偏移写入。没有旋转祖先的网格挂在根骨骼上，与之前相同。
- **参数**：每个参数一段动画 `param/<参数 ID>`，长 1 秒，时间为参数归一化值（最小 0、最大 1），在参数的采样点键入骨骼的 `rotate`（相对设置角度的增量）、`translate`（增量）、`scale`（相对设置缩放的倍数）与网格 deform；运行时为每个参数开一个轨道、用叠加混合（`MixBlend.add`）并按参数值设置轨道时间。因为骨骼按父子关系相乘，多个参数同时旋转一条旋转链时会串联组合，而不是把各自的画布偏移相加。关键形透明度写成槽位 `rgba`，按参数变化的绘制顺序写成 `drawOrder`。
- **交叉项**：在 `sample_pairs` 个两参数组合姿势上，按 Spine 的叠加规则（骨骼值与 deform 偏移逐轨相加、缩放按设置缩放加倍数差、骨骼逐级相乘）重建并与求值器比较，超过 0.5 像素的网格写入损失报告。Warp 下的旋转（例如自动骨架挂在身体 Warp 下的头部与上臂旋转）在单个参数的采样点上精确，但与移动该 Warp 的参数组合时，支点不随 Warp 变形，报告为近似；由 Warp 实现的关节（前臂、手、上身弯曲）仍在骨骼空间中按参数相加。
- 每个动作片段一段动画 `clip/<名称>`，按 `clip_fps` 采样，再用 `KeyReduction` 去掉线性插值可重建的关键帧：deform 容差为 `key_tolerance` 像素，骨骼的旋转与缩放容差按画布对角线换算到同一像素量，偏移保留到千分之一像素。
- 图集为每页一个覆盖整页的区域，区域名与该页图片同名（`<名称>_<i>`，图片为 `<名称>_<i>.png`），网格附件的 `path` 指向它，UV 直接引用该页。运行时经图集找到区域；Spine Editor 导入数据时不读图集，按 `images` 目录（`./`）找同名图片，因此导入后能直接显示网格。
- 遮罩转为裁剪附件：被遮罩网格前插入裁剪槽位（`end` 指向该网格），多边形为单个遮罩网格的外轮廓，多个遮罩或多个轮廓时取凸包并报告；裁剪槽位在全部遮罩网格共用一根骨骼时挂在该骨骼上，否则挂在根骨骼上；裁剪附件随遮罩网格写变形键，绘制顺序变化时与被遮罩网格一起移动。Spine 按多边形裁剪，不按纹理 alpha；反相遮罩无法表达，丢弃并报告。
- **物理**：摆锤组中来源为角度的输出，若其参数的动画旋转了骨骼，就在这些骨骼上建立 Spine 4.2 物理约束（`rotate` 1）；Spine 由骨骼自身的运动驱动约束，不读取摆锤的输入参数，设置按经验映射（惯性 1、强度 = 100 × 加速度、阻尼 = 移动性、混合 = 输出权重 / 100、`fps` 取物理组帧率，默认 60），只能近似原有响应，报告为近似；该输出参数同时键入的变形不被模拟，在报告中注明。没有输出驱动骨骼旋转的摆锤组（如自动生成的头发摆锤，作用于 Warp）丢弃并报告。
- **二进制**：`binary=true` 时写出 Spine 4.2 二进制 `.skel`（按公开的 spine-runtimes 4.2 `SkeletonBinary` 布局：大端数值、变长正整数、字符串表、无 nonessential 数据），内容与 JSON 相同。二进制网格不存三角形数，读取端按“2 × 顶点数 − hull − 2”推出，因此 hull 长度按网格的三角形数设定；三角形多于该式允许值的网格（仅在三角形重复时出现）在末尾补上首顶点的未用副本。
- 验证（已实现且经下列测试；未使用官方 Spine 运行时，其许可证要求持有 Spine 许可）。Spine 4.3 试用版能导入 JSON 并显示全部网格（版本不一致与缺少 nonessential 数据仅为警告），4.2 二进制 `.skel` 因编辑器要求版本一致而无法导入；动画与遮罩未在编辑器中检查：
  - 单元测试按 Spine 4.2 运行时的规则（叠加轨道、关键帧线性插值、无权重 deform 为相对设置姿势的偏移、骨骼按正常继承逐级相乘、绘制顺序按偏移算法重排）重建姿势：三级旋转链（含网格关键形、翻转与负缩放）在单参数、参数之间与全部参数同时变化的随机姿势上，与按同一旋转规则独立实现的测试求值器误差小于 0.002 像素；动作片段在采样帧上一致。
  - 测试内按公开布局写的最小 `.skel` 读取器读回二进制，骨骼、槽位、物理约束、附件与各动画重建的姿势和 JSON 一致，且两次导出逐字节相同。

## DragonBones

与 Spine 相同的烘焙方式：一根根骨骼，每个可见网格为无权重网格显示（原点为画布底边中点，y 向下）；每个参数一段 1 秒的动画 `param/<参数 ID>`，变形（ffd）帧位于参数关键点取整后的帧上，宿主可按参数值定位并分层叠加；每个片段按帧采样后精简。帧间插值显式写 `tweenEasing: 0`（缺省为阶梯）。DragonBones 运行时用 16 位偏移寻址每个动画的变形浮点数据（每帧存满该网格全部顶点），超出约 3.2 万个浮点数时按倍增再二分的容差精简关键帧，并在损失报告中写出所用容差。验证：`DragonBonesFidelityTool`（`PSD2LIVE_TOOLS=1`，需要 node）用 `tools/dragonbones-check`（官方 DragonBones 5.7 运行时核心，无渲染）播放导出：无骨架样例的单参数姿势在关键帧上与编辑器误差小于 0.25 像素；带骨架时受上述上限影响的动画误差不超过报告的容差，片段误差见报告。

## glTF

每个可见网格成为一个无光照（`KHR_materials_unlit`）、双面、半透明混合的平面网格，处于静止姿势，按绘制顺序每层朝观察者前移 0.5 毫米；单位为米（`pixels_per_meter`，默认 1000），y 向上，原点在画布底边中点。每个参数的每个非默认关键点对它移动的网格成为一个变形目标（`extras.targetNames` 为 `参数=关键点`），参数值到权重的映射是关键点之间的线性帽函数，参数及其关键点列在 `extras.psd2live.parameters`。动作片段按 `clip_fps` 采样为权重动画并用 `KeyReduction` 精简。验证：单元测试由权重重建采样姿势；tml 导出经 Khronos glTF Validator 校验零错误零警告，并在 Godot 4.4 中按静止姿势和摇头动作渲染正确。

## 损失报告

每次导出在文件旁写出 `<名称>.<目标>.report.json`：

```json
{"target":"gif","compiler":"2.0.4","files":["model.gif"],
 "losses":[{"object":"*","feature":"structure","handling":"baked","note":"..."}]}
```

`handling` 为 `baked`（保留视觉、增大体积）、`approximated`（可接受的误差）或 `dropped`（丢弃）；`object` 为稳定 ID，`*` 表示整个 Rig；测得误差时附 `error`。报告记录编译器版本，用于追查两次导出的差异。

## 确定性

同一份 IR 与同一版本编译器产生逐字节相同的输出，导出不引入时间戳和随机数。例外是 `cmo3`：Cubism Editor 要求每个对象有唯一 GUID，因此文件字节每次不同，内容相同。导出路径重构用 `ExportGoldenTool`（`PSD2LIVE_TOOLS=1`）对比前后提交的文件摘要；cmo3 比较其读回后再降级为 moc3 的摘要。

## 使用

- 界面：“文件 → 导出 Live2D 模型…”导出 moc3 / cmo3；“文件 → 导出为”按运行时与播放器、图像与视频、分层 PSD 分组列出其余非实验性目标，每个目标打开自己的对话框，导出当前已提交状态并列出损失。每个目标在 `ExportTarget.settings` 中声明它读取的设置键（动作选择、开关、带范围的数值、文本），对话框按声明生成对应的行，只发送用户改动过的值，其余取目标默认值或工程导出设置；改动在本次运行中按目标保留。新目标须登记到菜单分组（`exportMenuGroups`，`ExportTargetSettingsTest` 检查）。
- 命令行：见[开发与命令行](../guide/DEVELOPMENT.md)中的 `export` 命令；`psd2live targets` 列出每个目标及其设置键、取值范围与默认值。
- 编辑器自身的 Cubism 预览包与 `moc3` 导出是同一次编译：“导出 Live2D 模型”（MCP `project_export_model`）与“导出为”都写出已提交的模型本身（`PSD2LivePipeline.export`），不从源图重建，也不生成基础 Rig；两者只是报告不同（前者读回校验并写 `.psd2live.json`，后者写损失报告）。命令行先按同一构建路径（`buildPreview`）得到模型再写出。
