# PSD2Live 运行时

`runtime/` 是一个独立的 Rust 库（MIT），播放 PSD2Live 编译出的 `.p2lrt` 模型：参数驱动的变形、物理和动作片段，提供 C ABI 给其他语言和引擎调用。编辑器通过 `:format-eval`（JNA）调用同一个库。

## 组成

| 部分 | 位置 | 许可证 |
| --- | --- | --- |
| `.p2lrt` 写出与导出目标 `p2lrt` | `:targets:runtime`（`P2lrt`、`P2lrtTarget`） | MIT |
| 读取、求值、物理、动作片段、C ABI | `runtime/`（crate `p2l-runtime`，cdylib + staticlib） | MIT |
| C 头文件 | `runtime/include/p2l_runtime.h` | MIT |
| JVM 绑定 | `:format-eval`（`P2lRuntime`、`NativeGeometryEvaluator`） | MIT |
| 网页播放器 | `:targets:web`（导出目标 `web`：页面、`p2l.js`、运行时的 WebAssembly 构建） | MIT |
| Godot 4 节点 `P2LCharacter` | `runtime/godot/`（GDExtension，godot-rust，Godot 4.3+），演示工程 `runtime/godot/demo/` | MIT |

构建：在 `runtime/` 运行 `cargo build --release`，库位于 `runtime/target/release/`（Windows 为 `p2l_runtime.dll`）。Gradle 的 `buildRuntime` 在有 cargo 时自动构建：`./gradlew run` 使用它，打包时随应用资源分发；没有 cargo 时跳过，应用回退到编辑器求值器。`P2lRuntime.locate()` 依次查找系统属性 `psd2live.runtime.library`、环境变量 `PSD2LIVE_RUNTIME`（文件或目录）、系统属性 `psd2live.runtime.dir`、打包应用的资源目录和 `java.library.path`。

编辑器的导出烘焙（Spine、DragonBones、glTF）在运行时库可用时经 `NativeGeometryEvaluator` 求值，否则使用引擎求值器；两者逐姿势一致。编辑器软件预览（`RigCanvasSupport.evaluate`）同样经 `NativePreview` 使用运行时：首次遇到某个预览模型时由引擎作答并在后台编译，之后的帧走运行时；拖动中的临时模型不等待编译，失败或 `-Dpsd2live.preview.runtime=false` 时保持引擎。在带骨架的 tml 上单次求值约 0.5 毫秒，与引擎相当（JNA 调用与结果转换抵消了原生速度）。

预览画布可以选用运行时作为预览后端（「PSD2Live 运行时（p2lrt）」，见 [CANVAS_RENDERER.md](CANVAS_RENDERER.md#103-预览后端)）：后台把预览模型编译为 `.p2lrt`（含模拟），运行时按工作区时钟给出的完整姿势求值，`P2lrtGlRenderer` 按网页播放器的规则在窗口自己的 Skia OpenGL 上下文中绘制。扩展（高级）模式开启时每个视图的模型开启蒙皮与精确链接；实时模拟与碰撞由预览的物理开关控制（开启时以 `p2l_update` 按帧时间推进，暂停也不停；关闭时显示烘焙摆动），运行时的程序化行为保持关闭。Cubism SDK 不可用时预览自动改用它。

## `.p2lrt` 格式

小端二进制，是编译后 IR 的紧凑表示：画布、参数、变形器（父级在前）、部件、网格、Glue、绘制树、贴图页、物理组、动作片段和参数角色（如 EyeBlink、LipSync）。引用一律为索引。

导出默认写版本 2：文件头、块目录和 16 字节对齐、带 CRC 的块，字符串集中在字符串表，数组带编码并 4 字节对齐，另有参数面板（`PGUI`）与生成器信息（`META`）。布局与演进规则见 [P2LRT_V2.md](P2LRT_V2.md)。导出选项 `v1` 写版本 1（单一顺序流，逐字段布局见 `P2lrt.kt` 的文档注释），供尚未更新的播放器使用；`compress` 用 deflate 压缩能变小的块（贴图页除外），`strip_names` 去掉显示名称、保留 id。

读取器（`runtime/src/rig.rs`、`container.rs`）同时读两个版本，解析为同一个 `Rig`，求值代码只有一份；校验引用、网格索引、关键形尺寸、块的位置与对齐、块内记录是否正好读完，跳过不认识的可选块，遇到不认识的必需块报出其标签。块可用 deflate 或 zstd 压缩（导出设置 `compress`，加 `zstd` 用 zstd）。

## 求值规则

求值输出每个网格在画布像素坐标（y 向下）中的顶点、透明度、乘算/屏幕色和绘制顺序。规则全部通过黑盒探测编辑器求值器得到（`WarpProbeTool`、`RuntimeConformanceTool`），未参考其实现：

- **参数**：钳制到范围；循环参数只回绕范围之外的值，两端保持原值。
- **关键形网格**：多线性插值。值与某个关键点相差小于 0.001 时取该关键点。稀疏网格中缺失的格子按零形状计入（网格偏移为零，变形器的绝对形状为原点），权重不重新分配。标志通道（翻转）取不大于当前值的关键点。
- **Warp**：单位正方形内按格子双线性或沿 (1,0)–(0,1) 对角线分成两个三角形插值；外部在 [-2, 3]² 内用一层较粗的虚拟格子延伸，额外格点取格子四角的平均仿射框架，按三角形插值；更远处只用仿射框架。
- **旋转变形器**：角度为关键形角度加基准角。父级为旋转时，原点经父级变换，角度和缩放与父级叠加；父级垂直翻转或父级缩放为负时，子框架额外旋转 180°；父级水平翻转不影响子框架方向；自身翻转只作用于子坐标。父级为 Warp 时，框架保持刚性：原点经格子映射，角度随格子在原点上方 0.1 处沿 v 方向的走向旋转，缩放继承格子上方最近旋转的缩放。
- **混合形**：每个绑定按参数在相邻关键点之间线性混合（不取整到关键点），中性关键点和没有形状的关键点不贡献；权重乘以各限制参数的分段线性系数。变形器和通道的形状是目标值，叠加的是“目标 − 默认姿势下的值”；网格形状是目标偏移，叠加的是“目标偏移 − 默认姿势下的偏移”。
- **透明度**：每一级（变形器、网格）先钳制到 [0, 1] 再与父级相乘；部件透明度不计入网格透明度。
- **Glue**：在全部变形之后，每对顶点各自按自己的权重乘强度向对方移动。
- **绘制顺序**：每个绘制组按子项的绘制顺序排序，子组以其部件按通道与混合形求出的绘制顺序为键（无部件时取静态值）；键加千分之一后取整，相同时保持树中顺序；隐藏网格不绘制。规则取自引擎渲染顺序接口的文档说明，未与原生渲染逐帧比对。

## 高级模式

文件中的扩展块提供 Cubism 等价求值之外的功能，全部默认关闭；`p2l_set_advanced` 开启，关闭时求值与核心求值逐位一致。布局与语义见 [P2LRT_V2.md](P2LRT_V2.md)。

- **运行时蒙皮（`P2L_SKIN`）**：在膝、肘等关节处弯折的网格，烘焙时按键取样，键之间沿弦插值。运行时把骨骼框架逐顶点两骨线性混合，加上“当前混合 − 混合在键之间的插值”。这在每个键上为零，键之间把弦换成弧。权重在开启时拟合到烘焙的关键形；骨架裁剪或折叠掉的骨骼作为虚拟骨骼随文件提供，宿主可通过 `p2l_bone_*` 读取骨骼变换来挂接物件。
- **精确链接（`P2L_EXACT_LINKS`）**：键控在同一圆上的枢轴沿圆弧插值。
- **实时模拟（`P2L_SIM`）与碰撞（`P2L_COLLISION`）**：已烘焙的布料与头发以编辑器的 XPBD 求解器实时运行，替换烘焙的模式参数与摆锤；碰撞体是沿网格顶点的圆或胶囊。

验证：`advanced_tests`（已知解析解的手臂、链接、虚拟骨骼和悬挂网格）；`p2lrt-conformance --advanced` 要求每个样例在蒙皮的键上与烘焙一致（全部为 0）；`--sim` 用 `RuntimeConformanceTool.simulation` 的随机布料轨迹比较求解器，与编辑器逐位一致（含碰撞）；`--sim-rig` 用 tml 后发的烘焙模拟端到端比较，前半秒误差 0.21 像素，全程平均 0.22 像素。差异来自两边求值目标时约 1e-4 像素的浮点差，布料会把它放大。

## 物理

按 Cubism 运行时读取 physics3.json 的方式实现，包括其特有行为：输入按参数范围中点归一化；固定步长（`Fps`）时在帧间插值输入、输出在最后两步之间插值；不限步长时输出滞后一帧；平移旋转时复用已旋转的 x；只有角度输出带缩放，平移输出的缩放为零；超过 5 秒的积累时间清零。与编辑器 `PhysicsEngine` 在随机摆锤组和样例模型上逐帧比较，误差小于 2e-5。

另有 Cubism 运行时的三项：纵向（Y）输入输出（`PHYS` 版本 2，见 [P2LRT_V2.md](P2LRT_V2.md)；编辑器与 Cubism Editor 都不产生纵向，physics3 导出可写出）、风力（`p2l_physics_wind`，加到每个粒子受的力上，与 physics3 的 Wind 相同）和稳定化（`p2l_physics_stabilize`，按当前姿势把每条摆锤直接放到受力方向上的静止位置并写出输出，与 Cubism 的 Stabilization 相同）。风力为零时结果与此前逐位一致。

## 动作片段

曲线段与编辑器相同（线性、Bezier（时间控制点限制在段内）、阶梯、反阶梯）；循环片段按时长回绕，单次片段停在末尾。`Player` 一次播放一个片段，切换时按片段的淡入淡出时间（缺省 1 秒，余弦缓动）交叉过渡；片段权重为淡入与淡出系数之积（与 Cubism 相同），每个被替换的片段都会完整淡出，连续快速切换时不会丢掉仍在淡出的片段。

## 程序化行为

宿主可开启：眨眼（`EyeBlink` 角色，每 2–6 秒一次，闭合 0.1 秒、保持 0.05 秒、睁开 0.15 秒，乘到当前值上；`p2l_blink_settings` 可改间隔与各阶段时长）、呼吸（`Breath` 设置为范围内的正弦，`AngleX/Y/Z`、`BodyAngleX` 叠加小幅摆动）、视线（`look_at(x, y)` 经临界阻尼跟随，驱动 `AngleX/Y`、`BodyAngleX`、`EyeBallX/Y`，`AngleZ` 随 x·y 倾斜）和口型（`lip_sync(level)`，`LipSync` 参数取较大值；`p2l_lip_sync_samples` 以一段音频样本的均方根乘增益作为张开量）。`p2l_behavior_strength` 调整呼吸摆动幅度、视线转动幅度与跟随速度。角色由编辑器编译时按存在的标准参数写入；moc3 的 model3.json 仍只写 EyeBlink 与 LipSync 两组。更新顺序：动作片段 → 行为 → 物理 → 变形。

## C ABI

一个句柄对应一个已加载模型，持有参数、动作播放器和物理状态。典型流程：`p2l_rig_load` → 设置参数（`p2l_parameter_values` / `p2l_set_parameter`）→ `p2l_update(dt)`（动作、物理、变形）或 `p2l_evaluate` → 读取 `p2l_mesh_vertices`、`p2l_mesh_opacity`、`p2l_mesh_colors`，按 `p2l_render_order` 由后往前绘制，贴图由 `p2l_texture_png` 提供（`p2l_texture_info` 给出贴图页类型：内嵌 PNG、KTX2 或模型旁的文件）。`p2l_rig_load_ex` 可要求校验块的 CRC，`p2l_format_support` 列出支持的版本与块。表情（`p2l_expression`）在动作片段之后、程序化行为之前叠加；`p2l_hit_test` 返回画布点下的点击区域；`p2l_mesh_user_data` 读取网格的用户数据；`p2l_pose_show` 切换部件互斥组（导出设置 `pose_groups`），被替换的部件随 `p2l_update` 淡出。`p2l_parameter_values` 是宿主设置的姿势，每次 `p2l_update` 都从它重新开始叠加动作、表情、行为和物理，叠加结果不写回，所以眨眼、呼吸和加法表情不会逐帧累积；本帧实际使用的值由 `p2l_parameter_current` 读取。所有函数接受空句柄；返回的指针在句柄释放（姿势数据在下次求值）前有效。

ABI 1.1 增加的部分（都是新函数，1.0 的函数行为不变，只有越界下标的返回值改为可区分的值）：

- **模型与实例**：`p2l_model_load` 读取一次文件得到只读、可跨线程共享的模型，`p2l_rig_create` 在其上创建各自持有参数、动作与物理状态的句柄，`p2l_model_free` 后已创建的句柄仍持有模型；`p2l_rig_load` 等于两者合一。一个句柄不能被两个线程同时使用（读也不行，`p2l_bone_transform` 会更新句柄）。
- **分层更新与宿主层**：`p2l_update_stages(dt, stages)` 只运行所选的层（动作、表情、行为、部件姿势、物理、实时模拟，顺序不变），未选的层既不推进也不叠加。`p2l_set_parameter_override(index, value, weight)` 是宿主层：在动作与表情之后、行为与物理之前把参数按权重拉向给定值（面捕等），物理仍随之摆动；`p2l_parameter_values` 仍是最底层。
- **部件与隔离组**：`p2l_part_*` 给出部件 id、名称、父部件、标志和分组方式（直通、整组排序、隔离）。`p2l_render_commands` 是保留隔离组边界的绘制顺序（网格下标；`-2 - 部件` 开组，`-1` 收组），组的混合模式、透明度合成模式、遮罩（遮罩部件展开为其网格）由 `p2l_part_group` / `p2l_part_masks` 给出，求值后的组透明度与乘算/屏幕色由 `p2l_part_composite` 给出：绘制树中该组的通道覆盖组合成的静态值，与编辑器一致。`p2l_set_part_opacity` 设置宿主的部件透明度，与部件姿势一样乘到其下所有网格上。`p2l_mesh_part` 给出网格所属部件，`p2l_mesh_alpha_blend` 给出网格的透明度合成模式。
- **文件信息**：参数名称与标志（循环、混合形参数）、吸附值和参数面板（`PGUI`：二维摇杆、分组树、标签），画布原点与每单位像素，动作片段的名称、分组、时长、帧率、循环与淡入淡出，正在播放的片段、当前时间与跳转（`p2l_clip_seek` 只移动片段时间，淡入照常），表情、点击区域与网格的显示名称，生成器信息（`META`）。
- **其他**：`p2l_behavior_seed` 重设眨眼的随机种子，进程中第一个之后的句柄自动取各自的种子；越界下标的取值函数返回 NaN（数值）或 -1（下标、模式），空句柄仍返回 0；`p2l_alloc` 按长度精确分配并清零；非 PNG 贴图页的 `p2l_texture_png` 把长度写为 0；`p2l_parameter_index` 按 `p2l_parameter_id` 给出的字符串查找。

ABI 1.2 增加的部分（同样只有新函数）：

- **动作层**：最多 `P2L_MAX_LAYERS`（16）层，每层一次播放一个片段，按层序叠在下层之上，`p2l_set_layer_weight` 设该层覆盖下层的权重。`p2l_play_layer(layer, clip, priority)` 只在优先级不低于该层正在播放片段的优先级时开始（片段播完或停止后为 0），对应 Cubism 动作管理器的优先级；`p2l_play` 与 `p2l_clip_*` 操作第 0 层。
- **动作事件与非参数曲线**：来自 `CEXT`（见 [P2LRT_V2.md](P2LRT_V2.md)）。每次更新经过的事件由 `p2l_event_count` / `p2l_event` 给出（文本、层、片段、时间）。片段设置的部件透明度与整体透明度乘到网格上，`p2l_evaluate` 只求宿主姿势，因此不带它们；眨眼与口型效果作用于对应角色参数。有自身淡入淡出的曲线按 Cubism 的规则取权重。
- **多个表情**：`p2l_expression_add` / `p2l_expression_remove` 让多个表情同时生效（各自淡入淡出，按开始先后叠加），`p2l_expressions_playing` 列出正在生效的；`p2l_expression` 仍是只保留一个。
- **整体透明度与颜色覆盖**：`p2l_set_opacity` 乘到全部网格上（与片段设置的整体透明度相乘，`p2l_opacity` 读取乘积）；`p2l_set_mesh_colors` / `p2l_set_part_colors` 用宿主给的乘算/屏幕色替换求值结果，网格自身的设置优先于其所在部件链上最近的设置。
- **物理与行为**：`p2l_physics_wind`、`p2l_physics_stabilize`、`p2l_blink_settings`、`p2l_behavior_strength`、`p2l_lip_sync_samples`，见上文“物理”“程序化行为”。

ABI 1.3 增加 `p2l_render` 与 `p2l_render_texture`（见下文“软件渲染”）。ABI 1.4 增加：

- **变化标志**：`p2l_mesh_changes` 给出网格是否绘制（在绘制顺序中且不透明度大于 0），以及与上一次求值相比可见性、不透明度、绘制顺序值、在绘制顺序中的位置、顶点、乘算/屏幕色是否改变，对应 Cubism 的 dynamic flags，宿主只需上传改变的部分；首次求值后全部置位。所有改变姿势的入口（`p2l_update`、`p2l_update_stages`、`p2l_evaluate`、`p2l_set_advanced`、`p2l_physics_stabilize`）结束时更新。
- **日志**：`p2l_set_log` 接收加载失败、句柄失效与渲染器无法解码的贴图页。
- **宿主分配器**：`p2l_set_allocator` 让运行时此后的全部内存都来自宿主的分配与释放函数（大小与对齐照给）；必须在使用运行时的其他任何部分之前、在单一线程中调用，运行时已向系统申请过内存时返回 false 且不改变。由默认开启的 `host-allocator` 特性提供（运行时作为 Rust 库被 Godot 节点等使用时关闭）。C 示例实测：渲染 tml 样例的 7570 次分配全部经宿主分配器。

写出端：IR 的 `Clip` 增加 `events`、`targetCurves`，`Curve` 增加 `fadeIn` / `fadeOut`，`PhysicsSource` 增加 `Y`；p2lrt 导出写 `CEXT` 与按需写 `PHYS` 版本 2，moc3 导出的 motion3 写出 `UserData`、`PartOpacity` 与 `Model`（`Opacity`、`EyeBlink`、`LipSync`）曲线和曲线淡入淡出，physics3 写出 `Y`；cmo3 没有纵向物理，略去纵向输入输出。编辑器的动作编辑尚不提供事件与非参数曲线的编辑入口，这些数据来自 IR。

绘制规则写在头文件开头，以 `SoftwareRasterizer` 为准：贴图为直通 alpha，规则在预乘颜色上计算；片段颜色先乘乘算色、再按 `c + s·a − c·s` 叠屏幕色，最后乘不透明度与遮罩覆盖（遮罩网格在该点纹理 alpha 的最大值，反相取 1 − 覆盖；实时播放器可用 alpha ≥ 0.5 的模板近似）；开启剔除时只画画布坐标（y 向下）中顺时针的面；Cubism 的普通、叠加、乘算按其预乘公式（叠加与乘算保留目标 alpha），扩展模式按 W3C 合成公式；混合模式与透明度合成模式有 `P2L_BLEND_*`、`P2L_ALPHA_*` 常量。

调用在运行时内部 panic（缺陷，或读取器放行却无法求值的模型）时不会展开到宿主：该次调用按空句柄返回，句柄失效，`p2l_rig_failure` 给出原因，此后它与空句柄相同，直到 `p2l_rig_free`；加载中（含首次求值）panic 时 `p2l_rig_load` 返回空并写出原因。WebAssembly 构建的 panic 仍会中止实例（该目标不支持栈展开）。

ABI 版本：头文件的 `P2L_ABI_VERSION_MAJOR` / `P2L_ABI_VERSION_MINOR` 与库的 `p2l_abi_version()`（`major << 16 | minor`）。增加函数时次版本加一，修改或删除函数时主版本加一；宿主用 `P2L_ABI_COMPATIBLE(p2l_abi_version())` 检查，没有 `p2l_abi_version` 的库早于 1.0。`p2l_version` 只是库的构建版本。JVM 绑定加载库时要求主版本相同、次版本不低于绑定所需，否则视为没有运行时；求值或更新后句柄失效时抛出异常（软件预览因此回退到编辑器求值器，运行时预览后端与导出报告错误）。`cargo test` 校验头文件声明的函数与库导出的函数一一对应、版本号一致。

## 验证

- `cargo test`：每条求值规则一个小模型单元测试（数值来自探测结果），以及 Warp、动作片段测试；`format_tests` 逐块拼出版本 2 文件，覆盖压缩、CRC、块目录与各种拒绝情形、全部数组编码、贴图页类型和参数面板。
- `RuntimeConformanceTool`（`PSD2LIVE_TOOLS=1`）：生成 80 个随机模型（Warp、旋转、嵌套、稀疏网格、混合形、Glue、通道、部件、混合）、样例和本地工程的参考姿势，以及物理轨迹；`cargo run --release --bin p2lrt-conformance -- ../build/tools/runtime-conformance`（物理为 `runtime-physics`）逐例比较。每例同时写出版本 2、版本 1，以及 deflate 和 zstd 压缩的版本 2，四者必须读成相同的 `Rig`，并逐位得到相同姿势。顶点误差上限为 0.02 像素；超出的姿势按自身条件数评判：把任一参数移动其范围的百万分之一时顶点移动最多的距离（灵敏度），误差不得超过它的两倍。网格内部插值用双精度。当前全部一致，唯一超出 0.02 的是 blend-6 的一个姿势（0.10 像素，灵敏度同为 0.10，该网格此时不透明度 3e-5）。

## 尚未完成

- GPU 渲染由宿主完成：运行时提供几何与属性，并以 `p2l_render`（CPU 软件渲染，见下节）画出遮罩与全部合成模式；GPU 宿主按同一规则自行绘制（Web 目标的绘制见[导出目标](EXPORT_TARGETS.md)）。

## 软件渲染

`p2l_render`（ABI 1.3）把最近一次求值画进宿主给的 RGBA8 图像，规则与参考光栅器 `SoftwareRasterizer` 相同：双线性预乘采样、像素中心与左上规则、按纹理 alpha 的遮罩（含反相）、隔离组图层、全部颜色与透明度合成模式和剔除。变换为画布到图像的仿射（空指针时按画布等比放入图像左上），输出为预乘或直通 alpha，可画在已有内容之上。内嵌 PNG 贴图页由运行时自带的解码器解出（非隔行，全部颜色类型），KTX2 与外部文件页由宿主经 `p2l_render_texture` 提供像素。供没有自带渲染器的宿主（C/C++、引擎插件、缩略图与校验）使用，逐像素运行在 CPU 上。验证：`SoftwareRenderParityTest` 在覆盖 90 种合成组合、隔离组（通道驱动的不透明度与颜色、网格与部件遮罩、反相、嵌套）、遮罩、乘算/屏幕色与剔除的模型上，于三种姿势与缩放下与参考光栅器逐像素比较，最大通道差 ≤ 1/255；把 conjoint 的重叠权重故意改错时该测试报出 77/255 的差异。

## 其他语言与平台

见 [`runtime/bindings/README.md`](../../../runtime/bindings/README.md)：

- **C/C++**：`runtime/examples/c/render_example.c` 经共享模型加载、播放一秒并用 `p2l_render` 写出 BMP；`build_example.bat` 用 MSVC 以 C 与 C++ 各编译一次（`/W4 /WX`，头文件无警告），两者输出逐字节相同。Unreal 以第三方模块方式使用同一头文件与库。
- **.NET / Unity**：`bindings/csharp/P2lNative.cs` 是由 `generate.py` 从头文件生成的全部 P/Invoke 声明（`cargo test` 校验其与导出函数一致），`P2l.cs` 提供字符串辅助与自动释放的 `P2lRig`；`Smoke.cs` 以 C# 6 编译后用 `Marshal.Prelink` 解析全部 129 个入口点，并加载、播放、渲染 tml 样例。`bindings/unity/P2LCharacter.cs` 把模型每帧用软件渲染画进 `Texture2D`，只按所用 Unity API 的桩代码做过类型检查，未在 Unity 中运行。
- **Android**：`runtime/build_android.sh` 用 NDK 的 clang 为 arm64-v8a、armeabi-v7a、x86_64 构建 `libp2l_runtime.so`，三者各导出全部 129 个函数。iOS 需在 Mac 上构建，未在此验证。

## 网页播放器

导出目标 `web` 写出可直接部署的文件夹：`index.html`、`p2l.js`（ES 模块 `P2LPlayer`）、`p2l_runtime.wasm` 与模型。播放器用 WebGL 绘制，按头文件的合成规则画出全部颜色与透明度混合组合和隔离组：普通（over）与 Cubism 的叠加、乘算用混合函数，其余组合先把网格包围盒内的下层复制到纹理、在着色器中合成；隔离组按 `p2l_render_commands` 画入图层，闭合时按组的模式、不透明度、乘算/屏幕色与遮罩合成；遮罩经模板缓冲（按纹理 alpha 0.5 裁剪，支持反相）；开启剔除的网格只画正面。`WebPlayerCheckTool`（`PSD2LIVE_TOOLS=1`）生成覆盖 90 种组合与 16 种隔离组的模型和逐像素参考图，Edge（无界面）中与参考相差至多 1/255；页面提供动作与表情选择、口型滑块，视线跟随指针，点击显示所在的点击区域；文件带有高级模式数据时，页面提供开关。WebAssembly 构建作为资源随仓库提交，修改运行时后用 `./gradlew :targets:web:updateWasm` 刷新（需要 `rustup target add wasm32-unknown-unknown`）；单元测试核对播放器调用的每个函数都由该构建导出。tml 样例在 Edge（无界面）中显示正确。

## Godot

`runtime/godot/` 用 `cargo build --release` 构建，`P2LCharacter` 的属性与方法见其 README。节点按头文件的绘制规则绘制：每个网格在自己的画布项中按 `render_commands` 排序，贴图加载时预乘，着色器施加乘算/屏幕色、不透明度与遮罩覆盖后按颜色与透明度合成模式合成——普通+over、Cubism 加算/乘算（任意透明度模式）与 out 用硬件混合，其余读取下方颜色在着色器中计算。隔离组把内容画进与目标视口同尺寸的离屏视口（层），在组的位置按组的混合模式、透明度模式、不透明度、乘算/屏幕色与遮罩合成，可嵌套；Forward+ 与 Mobile 的后缓冲复制会丢失 alpha，层内每个读取下方的项因此各用一个视口，先复制此前的层内容再读取；组外读取屏幕，下方按不透明处理（透明窗口下为近似）。遮罩画进覆盖视口（每个视口三组遮罩），网格与层在自己的像素读取覆盖，反相遮罩取 1 − 覆盖；同一组遮罩重叠处 alpha 相加（参考取最大值）。开启剔除的网格只提交正面三角形（画布坐标中 (b − a) × (c − a) < 0），遮罩不剔除。`RuntimeSamplesTool` 的特性模型在 Godot 中与软件光栅器参考图一致（差值 ≤ 2/255，含反相遮罩）；`GodotLayerSamplesTool`（`PSD2LIVE_TOOLS=1`）生成覆盖全部颜色×透明度合成模式（不透明背景上与隔离组内）及组的颜色、不透明度、混合模式、网格/部件遮罩、反相遮罩与嵌套的纯色模型和期望值，Godot 4.7 在 Forward+、Mobile 与 Compatibility 下各采样点差值 ≤ 1.2/255。验证：演示工程的 `smoke_test.gd` 在无界面模式加载模型并推进一秒；`--shot` 在窗口中渲染一帧，tml 与 ds 样例显示正确。
