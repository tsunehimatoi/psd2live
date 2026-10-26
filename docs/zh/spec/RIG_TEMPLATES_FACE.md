# 算法模板目录：面部

> 本目录写于部位与模块架构之前，沿用“插槽 / 模板”的说法：同一插槽下互斥的做法将登记为该部位的**基础算法**，可叠加的能力登记为**模块**，`*/current` 对应各部位的 `legacy` 基础算法。实现状态以注册表（MCP `module_catalog`）为准，本页的 P0/P1/P2 只是规划优先级，“已实现”指引入注册表之前已有的算法。见 [Rig 预设架构](RIG_PRESETS.md)。

[文档目录](../../README.md) · [预设架构](RIG_PRESETS.md) · 模板目录：[面部](RIG_TEMPLATES_FACE.md) · [头发与身体](RIG_TEMPLATES_HAIR_BODY.md) · [物理与动作](RIG_TEMPLATES_PHYSICS_MOTION.md) · [结构与表现](RIG_TEMPLATES_STRUCTURE.md)

本页是 [Rig 预设架构](RIG_PRESETS.md) 的模板目录之一：每个区域插槽可注册多个算法模板，模型预设为每个插槽选择一个模板并给出参数值。同一问题的不同做法作为同一插槽下的不同模板并存。设计依据为通用 Live2D 制作惯例；数值均为建议默认值与可调范围，实现后按出图调校。每个插槽的 `*/current` 是现有算法，作为默认模板登记，选它时输出不变。

相关文档：现有变形器与参数见 [DEFORMER_AND_PARAMETER_SPEC.md](DEFORMER_AND_PARAMETER_SPEC.md)；导出目标与损失报告见 [EXPORT_TARGETS.md](EXPORT_TARGETS.md)。本文状态为"设计中"，未实现的模板不代表现有能力。

## 0. 约定

### 0.1 记号（全部用相对量）
- H_e：眼高（上睑闭合行程）；W_e：眼宽；R_i：虹膜半径；G_x / G_y：虹膜视线行程。
- W_f / H_f：脸宽 / 脸高；W_m：嘴宽；H_m：嘴全开高度；E：兽耳长度（耳根 → 耳尖）。
- 参数满值指 ±1（或 0..1）端点；"实际物理输出"通常只到满值的 20–40%。
- 物理摆链按 Cubism 物理：质点长度 r、移动性 m（mobility）、延迟 d（delay）、加速度 a（acceleration）；输入类型 X（位移，只对变化响应，静止回中）或 Angle（角度，持续映射）；输出取第 k 个质点 v_k，可 reflect（取反）、乘 scale。
- 模板 ID：`<slot>/<name>`；每个插槽的 `*/current` 是现有算法，作为默认模板登记，选它必须逐字节复现现有输出。
- 新增参数沿用 Cubism 标准名（`ParamEyeLSmile`、`ParamBrowLAngle`、`ParamBrowLForm`、`ParamBrowLX`、`ParamCheek`）或 VBridger/ARKit 通行名（`ParamJawOpen`、`ParamMouthX`、`ParamCheekPuff`、`ParamMouthShrug`、`ParamMouthFunnel`、`ParamMouthPuckerWiden`、`ParamMouthPressLipOpen`、`ParamTongueOut`）；派生参数（物理输出、影子参数）用确定的 `Param<部位><作用>{L|R}`，不得用随机 ID。

### 0.2 引擎能力词汇
| 能力 | 含义 | PSD2Live 现状 |
| --- | --- | --- |
| 非均匀多键 | 一个轴上 > 3 个、位置任意的键 | 数据模型支持；生成器键位写死 |
| 投影求值 | 每个键的形状按投影/规则精确计算，而非端点插值 | 无 |
| 混合形状 | Cubism 5 blend shape，加法叠加不增维 | 数据模型支持；生成器不产生；导出到 < 5.0 需烘焙或报告损失 |
| 物理多输出 | 一个物理组输出多个参数，取不同质点 v_k | 支持；生成预设只有三组 |
| 物理级联 | 物理输出参数再作为另一物理组的输入 | 需核对 |
| reflect / 不对称归一化 | 输入/输出取反；归一化正负范围不同 | 支持 |
| 乘积门控 | 多个开关参数的不透明度格，只有全 0 角点为 1 | 多维不透明度通道可表达；无生成规则 |
| 反向蒙版 | inverse mask（Cubism ≥ 4.0） | 支持（透过刘海开窗已用） |
| 绘制顺序关键形 | 绘制顺序随参数变化 | 需核对 |
| 乘算/滤色色关键形 | multiply / screen color 随参数变化 | 支持乘算色关键形；无生成规则 |
| 胶水 | glue 顶点配对 | 支持 |
| 循环参数 | 0 → max 对应 N 圈旋转等循环动画 | 无 |
| 辅助纹理钩子 | 模板生成新像素（渐变阴影、线稿） | 只有嘴唇丝带的分析期注入 |
| 宿主映射导出 | 追踪通道 → 参数的增益、上限、平滑 | 1:1 默认 |

### 0.3 插槽一览
本目录覆盖：`face.turn`、`face.tilt`、`face.contour`、`cheek`、`nose`、`ear`、`animal-ear`、`eye.lid`、`eye.smile`、`eye.emotion`、`eye.iris`、`eye.highlight`（补充插槽：高光从虹膜中独立）、`brow`、`mouth.shape`、`mouth.vowel`、`mouth.interior`。邻接插槽（`mask`、`toggle`、`lighting`、`effect`、`motion.*`、`param.system`、`hair.accessory`、`body.lean`、`mesh.density`）只在引用处说明。

### 0.4 模板与优先级总表
P0 = 本轮应实现（`*/current` 的 P0 指"登记为默认模板"）；P1 = 下一轮；P2 = 研究或依赖其他插槽。

| 插槽 | 模板 | 难度 | 优先级 |
| --- | --- | --- | --- |
| face.turn | `current` · `depth-layered` · `arc-keys` | — · medium · medium | P0 · P1 · P1 |
| face.tilt | `current` · `neck-split` · `turn-coupled-roll` | — · easy · easy | P0 · P1 · P1 |
| face.contour | `current`（补镜像为 v2）· `jaw-follow` · `mouth-spring` · `radial-mesh` | — · easy · medium · medium | P0 · **P0** · P2 · P2 |
| cheek | `current` · `aux-spread` · `global-field` · `squint-lid-push` · `blush-overlay` | — · medium · hard · easy · easy | P0 · P1 · P2 · P1 · P1 |
| nose | `current` · `mouth-follow` | — · easy | P0 · P2 |
| ear | `current` · `face-stencil-occlusion` | — · medium | P0 · P2 |
| animal-ear | `current` · `local-frame-stack` · `blink-twitch` · `emotion-mood` · `combo-correction` · `flick-motion` | — · medium · medium · medium · hard · easy | P0 · P1 · P1 · P2 · P2 · P2 |
| eye.lid | `current` · `arc-blink` · `wide-overrange` · `gaze-follow` · `blink-aftershock` · `overlay-early-fade` | — · easy · easy/medium · easy · medium · easy | P0 · **P0** · P1 · **P0** · P1 · **P0** |
| eye.smile | `current` · `closed-row` · `mouthform-coupled` | — · easy · medium | P0 · **P0** · P1 |
| eye.emotion | `current` · `additive-shapes` · `tension-axis` · `expression-blink-matrix` · `replacement-pupil` · `symbol-mode` · `tears` · `gloom` · `shock-tremor` | — · medium · medium · medium · easy/hard · medium/hard · easy · easy/medium · easy | P0 · P1 · P2 · P2 · P1 · P2 · P2 · P2 · P2 |
| eye.iris | `current` · `perspective-split` · `depth-parallax` · `jelly-layered` · `pupil-scale` · `close-squash` · `inertia` · `arc-path` · `color-palette` | — · medium · medium · medium · easy · easy · medium · easy · easy | P0 · P1 · P1 · **P0** · **P0** · P1 · P2 · P2 · P2 |
| eye.highlight | `current` · `parallax-layer` · `lid-follow` · `blink-wobble` · `gate` · `sweep-loop` | — · easy · easy · easy · easy · easy/medium | P0 · **P0** · P1 · P1 · P1 · P2 |
| brow | `current` · `lid-coupling` · `blink-dip` · `shape-axes` · `spring` · `emotion-fanout` | — · medium · easy · medium · easy · medium | P0 · **P0** · **P0** · P1 · P1 · P2 |
| mouth.shape | `current` · `staged-open` · `closed-line-swap` · `form-midkeys` · `overrange-open` · `placement` · `turn-perspective` · `lip-glue` · `press-axis` · `banded-lineart` · `expression-families` | — · medium · easy · easy · medium · easy · medium · easy · medium · hard · medium | P0 · **P0** · P1 · P1 · P2 · P1 · P1 · P1 · P2 · P2 · P2 |
| mouth.vowel | `current` · `aiueo-blend` · `dual-system` · `arkit-factorized` | — · medium · medium · hard | P0 · P1 · P2 · P1 |
| mouth.interior | `current` · `depth-parallax` · `open-shading` · `tongue-handoff` · `tongue-mask-expand` · `insert-mask` · `teeth-arc` | — · easy · easy · easy · medium · medium · medium | P0 · P1 · P1 · P1 · P2 · P2 · P2 |

本轮 P0 新模板 11 个：`face.contour/jaw-follow`、`eye.lid/arc-blink`、`eye.lid/gaze-follow`、`eye.lid/overlay-early-fade`、`eye.smile/closed-row`、`eye.iris/jelly-layered`、`eye.iris/pupil-scale`、`eye.highlight/parallax-layer`、`brow/lid-coupling`、`brow/blink-dip`、`mouth.shape/staged-open`；外加 `face.contour/current` 补镜像（作为 v2）。

### 0.5 对注册表的要求（由本组模板推出）
1. **派生参数 + 物理组是模板产出的一等公民**：近半数模板产出"影子参数/物理输出参数 → 关键形或混合形状"。模板须能声明物理组（输入、链、输出 v_k）和派生参数，并由它们的声明生成 ID、缓存键与 `DocumentGenerators` 拥有关系。
2. **模板声明插入位置而不是改树**：耦合几乎都通过"在某父级之下、某子级之上插入一层 warp（或在某 warp 上写混合形状）"实现。需要按插槽定义的锚点（如 `eye.lidGroup.outer`、`iris.betweenGaze`、`mouth.stack[jaw|offset|width|shape]`）与层序规则，模板只声明锚点与轴。
3. **模板声明自己的键位**，键形在每个键按规则/投影求值；现有 `ninePoseAxes`、`mouthAxes`、眨眼 0/1 两键都应成为 `current` 模板的声明值。
4. **参数范围变化走模板版本**：EyeOpen `[0,1] → [0,1,2]`、MouthOpenY `[0,1] → [0,2]`、AngleX ±45 → ±60 会改变旧工程外观，必须是新版本模板，旧工程保持旧版本直到"更新生成结果"。
5. **混合形状优先、关键形烘焙为降级**：叠加型修形（辅助口型、眉→眼、情绪形、元音）在 Cubism 5 目标用混合形状；导出到更低版本时烘焙"单形状 × 主轴"，并写损失报告。
6. **语义需要细分**：高光须从 IRIDES 拆出（或作为 IRIDES 的子角色）、新增 `ANIMAL_EAR`、"笑眼"不再并入 `EYE_CLOSE`、口内件区分口腔/牙/舌/口内暗层、眼部覆盖层（眼影、睑缘阴影、反光）与泪/符号层有独立角色；否则大部分眼部模板无从挂载。
7. **同一问题的相反做法并存为选项**（见 0.6），不要合并成"平均值"。

### 0.6 同一问题的不同做法（保留为选项）
| 问题 | 做法 A | 做法 B | 本目录处理 |
| --- | --- | --- | --- |
| 头部转向时高光怎么动 | 随光源固定，相对眼睛反向（或比虹膜少移） | 角膜在虹膜前，比虹膜多移 | `eye.highlight/parallax-layer` 的 `turnMode` |
| 眨眼时眉 | 眉随闭眼下沉（眼轮匝肌带动） | 刻意不压眉，只在睁大时抬眉（避免"委屈"） | `brow/blink-dip` 的 `dipMode` |
| 转头带出的滚转 | 稳态反向滚转 3–5° | 瞬态向转向侧内倾、静止回正 | `face.tilt/turn-coupled-roll` 的 `rollMode` |
| 笑眼的驱动 | 独立 EyeSmile，只改闭眼行 | MouthForm 作为眼部第二轴（带死区） | `eye.smile/closed-row` 与 `mouthform-coupled`，二选一或取 max |
| 惊讶睁大 | 开合参数超量程（追踪可达） | 加法情绪形 Wide（表情驱动） | `eye.lid/wide-overrange` 与 `eye.emotion/additive-shapes` |
| 睫毛余震形状 | 下压 / 倾斜 / 尖端甩三层 | 沿睫毛线内中外三段、中段取反 | `eye.lid/blink-aftershock` 的 `lashMode` |
| 兽耳耷拉形式 | 向外下旋 | 外翻压平（飞机耳）/ 向后下压 | `animal-ear/emotion-mood` 的 `droopStyle` |
| 吐舌越过唇线 | 正/反蒙版两份舌错开交接 | 蒙版局部扩张 + 绘制顺序提升 | `mouth.interior/tongue-handoff` 与 `tongue-mask-expand` |
| 嘴横移 | 全脸肌肉联动（追踪向） | 纯嘴位置层（表情/动作向） | `cheek/aux-spread` 与 `mouth.shape/placement` |
| 辅助口型实现 | 混合形状挂到各区域 | 高分辨率脸修形 warp + 五官同场采样 | `cheek/aux-spread` 与 `cheek/global-field` |


---

## 1. face.turn — 头部转向（AngleX × AngleY）

### `face.turn/current` 九姿态经纬网（现有）
- **目的**：头部 X/Y 转向时，脸面以"低频表面 + 五官高频修形"的经纬网变形，作为所有面部模板的父级。
- **适用**：有 FACE 图层或头部组；五官区域由眼/虹膜/眉/鼻/嘴/耳 tag 推导。
- **产出**：参数 `ParamAngleX{-45,0,45}`、`ParamAngleY{-30,0,30}`；变形器 `DeformHeadRotation`（旋转）→ `DeformHeadContainer`（4×5）→ `DeformFaceNinePose`（8×8）→ `DeformFaceContour` / 可选 `DeformFeatureDisplacement` / 各五官 `Deform*Shape{L,R}`；所有带 AngleY 轴的 warp 追加 `ParamBodyLean` 轴。
- **算法**：`NinePoseFaceRig.surfacePoint`（近侧平台平移、远侧压缩、抬头 ^ 低头 V、角交互项 Cxy）；`featureOffset` 按 EYE/IRIS/BROW/NOSE/MOUTH/EAR 分支修形；键只在端点与中点，键间线性插值。
- **可调参数**：`RigTuning` 头部约 20 项（`faceTurn*`、`faceLift`、`faceTilt`、`faceArch`、`faceCorner`、`eyeFarShrink` 等）与 `headTurnStrength`。
- **失败模式与检查**：AngleX 中间姿态是两端线性平均（顶点走弦不走弧），大角度时脸"缩"；键位 `{-45,0,45}` 硬编码。
- **引擎能力**：warp、旋转、多维关键形。
- **难度 / 优先级**：已实现 / 作为默认模板登记（P0：注册即可，不改输出）。

### `face.turn/depth-layered` 按深度分组的嵌套角度 warp
- **目的**：让头部各深度层（后发 < 耳 < 脸 < 五官 < 刘海 < 头顶饰物）在转头时有不同位移，形成体积视差；组内再做五官透视。
- **适用**：已识别的头部子组（后发、耳、脸、五官、刘海、头饰）；每组需能估计相对脸面的深度 z（以头宽为单位）。
- **产出**：在 `DeformHeadContainer` 下为每个深度组建一个 AngleX×AngleY warp（脸、刘海 ≥ 20×20 格；耳、饰物 5×5–9×9）；五官组内每个五官一个细 warp（3×3 键）。不新增参数。
- **算法**：
  1. 深度默认：后发 −0.4、耳 −0.3、脸 0、眉 +0.02、眼 0、鼻 +0.12、刘海 +0.12、头顶呆毛/光环按其三维位置。
  2. 组 warp 横移 = `sinψ · R_head · (1 + k·z)`，远侧按 `cosψ` 压缩（球模型）；后侧组（z<0）方向取反。
  3. 五官细 warp：宽度 × `cos(ψ ± φ_feature)`（φ 为该五官相对脸中线的方位角），远侧宽度比下限 0.5，防翻面。
  4. 对角姿态由两轴组合，必要时单独修正。
- **可调参数**：`depth.<group>`（深度，头宽）默认见上，范围 −0.6–0.3；`parallaxK`（视差系数）默认 0.2，范围 0.1–0.3；`minFarWidth` 默认 0.5，范围 0.4–0.7。
- **失败模式与检查**：深度组之间的接缝（刘海根与额头、耳根与鬓角）在 ±端点错开 → 渲染九姿态检查重叠区；层数多导致关键形开销上升。
- **引擎能力**：嵌套 warp、多维关键形。
- **难度 / 优先级**：medium / P1。
- **差距**：现有经纬网已含五官位移与前后发头壳跟随，缺按组深度系数与每组刘海的独立角度 warp。

### `face.turn/arc-keys` 投影求值的中间键
- **目的**：消除线性插值导致的"中间姿态变平/变缩"，让顶点沿头部弧线运动，并支持更大的转向范围。
- **适用**：任何以 AngleX/AngleY 为轴的面部 warp；大角度（> ±30°）或强 3/4 侧脸风格。
- **产出**：AngleX 键改为每 15–20° 一键（如 ±30 范围用 `{-30,-15,0,15,30}`，±60 范围用 20° 步长）；脸轮廓 warp 与头部主 warp 采用同一键集；单侧件（侧发髻、耳饰）只在"转向远侧"一边额外加 ±R/3 键。
- **算法**：
  1. 每个键的形状用与端点相同的投影公式在该角度精确求值（不是端点插值）。
  2. 选键：先以 3 键求值，在中点比较"精确求值"与"线性插值"的最大顶点偏差，若 > 1.5% 脸宽则在该区间中点加键，递归至达标或到最小步长。
  3. 单侧件：只在偏差超标的一侧加键。
- **可调参数**：`keyStepDeg` 默认 15，范围 10–20；`maxChordError`（脸宽比例）默认 0.015，范围 0.005–0.03；`angleXRange` 默认 45，范围 30–60。
- **失败模式与检查**：键数随维度相乘膨胀（AngleX 5 键 × AngleY 3 键 × BodyLean 3 键 = 45）→ 只对脸轮廓、头部主 warp 加密，五官细 warp 保持 3 键；±60 时远侧脸后会露空，需要远侧轮廓/头发遮挡检查。
- **引擎能力**：非均匀多键。
- **难度 / 优先级**：medium / P1。
- **差距**：键位 `{-45,0,45}` 硬编码在 `ninePoseAxes`；无按误差自动插键。

### 邻接说明
- 硬质配件（眼镜、帽子、耳机）用独立粗 warp 按刚体投影，不受表情修形——归 `hair.accessory`。
- "靠近镜头"整体前探参数——归 `body.lean`。
- 转头带出的滚转见 `face.tilt/turn-coupled-roll`。

---

## 2. face.tilt — 头部倾斜（AngleZ）

### `face.tilt/current` 嘴线支点整体旋转（现有）
- **目的**：AngleZ 让头部整体绕嘴线中点旋转。
- **适用**：总是生成。
- **产出**：`DeformHeadRotation`，轴 `ParamAngleZ{-30,0,30}` × BodyLean × 头身比；支点 `(centerX, mouthLineY)`；初始角 = 原画头部倾斜估计。
- **算法**：参数度数直接作为旋转角；颈部随身体 warp。
- **可调参数**：无专门项。
- **失败模式与检查**：头转满 30° 而颈不弯，像"脖子折断"；身体不随头倾。
- **难度 / 优先级**：已实现 / 默认模板（P0 登记）。

### `face.tilt/neck-split` 头部只转一部分，余量交给颈部弯曲与身体
- **目的**：把倾斜沿脊柱分配，形成颈部 S 形，而不是头在颈根处折断。
- **适用**：有颈部图层（或脸下沿到领口的皮肤区域）。
- **产出**：`DeformHeadRotation` 的角度 = `headShare × AngleZ`；新增颈部 warp `DeformNeckTilt`（以 AngleZ 为轴，6×6–8×8），挂在身体 warp 下、包住颈与贴颈饰物；可选物理组 `PhysicsBodyFollowZ`：AngleZ（Angle 型）→ `ParamBodyAngleZ`。
- **算法**：
  1. 头部旋转角 = 0.65 × 参数度数。
  2. 颈 warp：上沿随头部旋转后的颈根位置（取头部旋转在颈上沿处的位移），下沿 0，中间按 smoothstep 过渡，形成弯曲。
  3. 贴颈饰物（choker、领结）随颈 warp；下沿位移必须归零以接胸部。
  4. 身体跟随：物理输出约为头角的 0.3，或由宿主映射完成（二选一，避免重复）。
- **可调参数**：`headShare` 默认 0.65，范围 0.5–1.0（1.0 即现状）；`bodyFollow` 默认 0.3，范围 0–0.5；`neckBendFalloff` 默认 smoothstep。
- **失败模式与检查**：颈上沿与头部下巴阴影脱节 → AngleZ 端点检查颈-下巴接缝；与宿主"身体跟随头"映射叠加时过量。
- **引擎能力**：旋转 + warp、可选物理。
- **难度 / 优先级**：easy / P1。
- **差距**：现状头部全额旋转，身体不随头。

### `face.tilt/turn-coupled-roll` 转头耦合滚转与呼吸点头
- **目的**：转头时自动带一点滚转（颈椎耦合），呼吸时头轻点，消除"平移的面具"。
- **适用**：总是可用；对真人追踪时需降低权重。
- **产出**：派生参数 `ParamAngleZ2`（±30），由物理组 `PhysicsHeadRollCouple` 输出；头部旋转改以 `AngleZ2` 为轴（或 AngleZ 与 AngleZ2 两轴叠加）。可选 `ParamAngleY2` = AngleY + 呼吸。
- **算法**：
  1. 物理输入：AngleZ（Angle 型，主输入，权重 60–80%）+ AngleX（X 型，权重 20–30%，按方向约定决定 reflect）+ AngleY（X 型，5%）。
  2. 两种模式：
     - `steady`：X 型输入经摆链形成稳态偏移，转头满值时滚转稳态 ≈ Z 范围的 10–20%（约 3–5°），方向与转向相反（头顶向转向反侧倾）。
     - `transient`：AngleX 以 X 型单独驱动一个重而慢的单摆，只对转动"变化"产生瞬时滚转，静止后回正；幅度为 AngleZ 幅度的 0.3–0.5，方向向转向侧内倾。
  3. 呼吸：AngleY2 物理加 Breath（Angle 型，5–10%）。
  4. 校验：AngleX 满值时 Z2 稳态 ≤ Z 范围 25%。
- **可调参数**：`rollMode` steady|transient，默认 steady；`rollWeight` 默认 0.25，范围 0.15–0.35；`rollSign` ±1（提供开关，渲染确认）；`breathToPitch` 默认 0.07，范围 0.03–0.1。
- **失败模式与检查**：追踪端已包含真实 Z 时叠加过量 → 真人追踪预设把权重降到 0.15；低头 + 转头组合显得歪 → 在 AngleY 端点减小耦合。
- **引擎能力**：物理（多输入、X/Angle 型、reflect）、派生参数。
- **难度 / 优先级**：easy / P1。
- **差距**：头 X 与头 Z 互相独立，无耦合层。

### 邻接说明
- 头 Z 驱动上半身弧形侧倾、身体 Z 经物理反向回正头部——归 `body.lean`。
- 发片自带 AngleZ warp 让发尾回摆——归 `hair.*`。

---

## 3. face.contour — 脸轮廓与下颌

### `face.contour/current` 左轮廓内凹（现有）
- **目的**：转头时近侧颧骨轮廓内收。
- **适用**：FACE 图层。
- **产出**：`DeformFaceContour`（8×16，仅 AngleX）。
- **算法**：`faceContourPoint`：`turn = clamp(−AX/45·s, 0..1)`，在眼线 ±0.18 与 `u<0.35` 范围内用两条三次 Bezier 衰减把左轮廓向内推 `faceContour%`。
- **失败模式与检查**：只处理左轮廓、只在 AngleX<0 生效，没有镜像方向——重构时应补成对称（注册新版本，保持旧版本可选以兼容旧工程）。
- **难度 / 优先级**：已实现 / P0（登记 + 补镜像为 v2）。

### `face.contour/jaw-follow` 张嘴带下颌
- **目的**：张嘴时下巴与下半脸轮廓随下颌下移，避免"嘴是挖在脸上的洞"。
- **适用**：FACE 图层；嘴区域存在。可选：下巴阴影、颈部阴影图层；可选独立 `ParamJawOpen`。
- **产出**：在 `DeformFaceNinePose` 之下、`DeformFaceContour` 之上插入 `DeformJaw`（≥ 8×10，覆盖鼻下到下巴并外扩 10%），轴 `ParamMouthOpenY`（键与 `mouth.shape` 相同）；若启用 `ParamJawOpen`（0..1），作为第二轴。下巴阴影、颈部阴影挂在其下。
- **算法**：
  1. 下颌位移场：权重 `w(y)` 从嘴线处 0 到下巴尖 1（smoothstep），嘴线以上为 0；水平方向在脸轮廓处保留 100%、向中线不变。
  2. `MouthOpenY = 1`：下巴尖下移 `jawRatio × H_m`（H_m 为嘴全开高度）；下半脸两颊按 `cheekNarrow` 向内收（下移量的 0–0.3 倍）。
  3. `JawOpen = 1`（"闭唇张颌"，mmm 口型）：同样下移，但嘴唇层用 `mouth.shape` 中的 MouthClose 补偿使唇闭合。
  4. 鼻子可选随动（见 `nose/mouth-follow`）。
  5. 颈部不随动：脸下沿与颈接缝处检查。
- **可调参数**：`jawRatio` 默认 0.15，范围 0.05–0.25（相对 H_m）；`cheekNarrow` 默认 0.15，范围 0–0.3；`jawFalloff` smoothstep|linear，默认 smoothstep；`useJawOpenParam` 默认 false。
- **失败模式与检查**：下巴下移露出颈部空白或与颈部阴影错位 → 张嘴满值渲染检查脸-颈接缝；与 `DeformFaceContour` 的转头修形叠加时轮廓不平滑 → 九姿态 × 张嘴端点检查。
- **引擎能力**：warp 多维关键形。
- **难度 / 优先级**：easy / **P0**。
- **差距**：生成口型时脸轮廓不随张嘴移动。

### `face.contour/mouth-spring` 嘴动的软组织回弹
- **目的**：口型突变（口型同步爆发）后，下颌与脸颊有轻微余震，显得是软组织。
- **适用**：启用 `face.contour/jaw-follow`。
- **产出**：派生参数 `ParamJawSpring`（±1）；物理组 `PhysicsJawSpring`：MouthOpenY（X 型 80%）+ MouthForm（X 型 20%）→ v1（唇）、v2（下颌/牙舌）；作为 `DeformJaw` 与嘴 warp 上的混合形状（或附加轴）。
- **算法**：单摆或 3 质点链，回弹满值 = 0.1–0.2 × 张嘴行程，作用方向竖直；唇用 v1、下颌用 v2（滞后一拍）。
- **可调参数**：`springAmp` 默认 0.15，范围 0.05–0.25；`springDamping`（delay）默认 0.9，范围 0.7–1.2。
- **失败模式与检查**：幅度过大像抽搐；闭嘴后嘴唇仍在抖 → 末质点阻尼下限；受开口速度影响。
- **引擎能力**：物理多输出、混合形状。
- **难度 / 优先级**：medium / P2。

### `face.contour/radial-mesh` 放射状脸网格（网格拓扑选项）
- **目的**：脸皮网格以"辐条 + 同心环"排布，使转头形变时边与等位移线对齐，最外环落在轮廓上，轮廓保持平滑；叠加层同拓扑可共用关键形。
- **适用**：FACE 图层轮廓近似星形（被头发遮挡造成凹口时先取闭包轮廓）。
- **产出**：替换 FACE 图层的自适应网格。
- **算法**：中心 c 取两眼连线中点略下；沿角度 θ 求 c 到轮廓的距离 R(θ)，环 i 半径 `R(θ)·i/N`，辐条数 M；外加一圈轮廓外余量环。腮红、阴影等与脸同轮廓的叠加层复制拓扑并重映射 UV。
- **可调参数**：`rings` 默认 10，范围 8–12；`spokes` 默认 40，范围 32–48。
- **失败模式与检查**：凹轮廓导致 R(θ) 多值；顶点数比自适应网格多。
- **引擎能力**：网格生成。
- **难度 / 优先级**：medium / P2（归 `mesh.density`，此处登记为脸轮廓相关选项）。

### 邻接说明
- 脸皮作为脸上所有叠层的统一蒙版、脸剪影副本做鬓发反向蒙版——归 `mask`（`ear/face-stencil-occlusion` 引用其机制）。

---

## 4. cheek — 脸颊、鼓腮、嘴横移的全脸联动

### `cheek/current` 无（现有）
- **目的 / 现状**：没有脸颊参数；腮红按 FACE_DETAIL 挂在 `DeformFaceNinePose` 下，只随转头。
- **难度 / 优先级**：— / P0 登记为"空模板"。

### `cheek/aux-spread` 口型辅助参数向周围区域扩散（混合形状）
- **目的**：鼓腮、嘴横移、下颌、撇嘴、嘴宽等辅助口型参数不只作用于嘴，也按比例牵动脸皮、鼻、腮红、下眼睑、眉，形成"肌肉联动"；用混合形状叠加，避免与主口型关键形相乘。
- **适用**：启用任一辅助口型参数（来自 `mouth.vowel/arkit-factorized` 或单独启用）；需要脸、嘴区域，可选鼻、腮红、眼、眉区域。
- **产出**：参数（按需启用）`ParamMouthX`（±1）、`ParamCheekPuff`（0..1）、`ParamJawOpen`（0..1）、`ParamMouthShrug`（0..1）、`ParamMouthPuckerWiden`（±1）；在各区域最外层形变 warp（脸皮 `DeformFaceNinePose` 下的修形 warp、`DeformNoseShape*`、腮红 warp、`DeformEyeShape*`、`DeformBrowShape*`）上为每个参数各加一个混合形状。
- **算法**：
  1. 位移场模板（以脸宽 W_f、脸高 H_f、嘴宽 W_m、眼高 H_e 为单位），以嘴中心为源，按距离衰减（距离 > 0.6 H_f 处为 0）：
     - CheekPuff：颊最宽处轮廓沿法向外扩 0.03 W_f（高斯，σ = 0.15 H_f）；嘴宽 −20%、中段微噘；腮红上移 0.1 W_m；下眼睑上推 0.06 H_e；双眉上抬 0.1 H_e（可选）。
     - MouthX：嘴横移 0.06 W_f；同侧脸颊同向 0.6 × 嘴位移并上提 0.03 W_f；腮红 0.5、脸皮 0.25 跟随；同侧下眼睑上推 0.05 H_e；眼、眉同向 0.1–0.15、鼻 0.3 × 嘴位移。
     - JawOpen：下巴下移 0.06 H_f，嘴以下脸皮拉伸，两颊收窄 0.02 W_f，眼区整体下移 0.02 H_e。
     - Shrug：下唇与下巴上推 0.02 H_f。
     - PuckerWiden：嘴宽 ±15%。
  2. 区域增益：嘴 1、鼻 0.6、脸皮 0.4–1、眼 0.3、眉 0.2。
  3. 写入：每个区域在其**最外层**修形 warp 上写混合形状，使五官与脸皮从同一位移场采样，五官不会漂在脸上。
  4. 组合裁剪测试：所有辅助参数同时 ±1 时，下眼睑不得越过瞳孔中心、上下唇不交叉；超出则按比例缩小该区域增益。
- **可调参数**：`<param>.amp`（各参数主幅度）默认见上，范围为默认的 0.5–2 倍；`<region>.gain` 默认见上；`spreadRadius` 默认 0.6 H_f，范围 0.4–0.8。
- **失败模式与检查**：多个参数同时满值时眼睑被推过瞳孔；目标运行时不支持混合形状（Cubism < 5）时须烘焙为关键形或丢弃并写损失报告；未接面捕时参数恒为 0，不影响基本口型。
- **引擎能力**：混合形状（必需）、导出烘焙降级。
- **难度 / 优先级**：medium / P1。
- **差距**：只有 MouthForm、MouthOpenY；数据模型支持混合形状但生成器不产生；无跨区域耦合。

### `cheek/global-field` 高分辨率全脸平滑场 + 五官同场采样（`aux-spread` 的关键形实现）
- **目的**：同 `aux-spread`，但在不支持混合形状的目标上，用一张细密的脸修形 warp 承载连续位移场，五官各自在自己的层级中按同一场采样做同步修正。
- **适用**：目标运行时无混合形状，或需要眼部修正与开合交互时。
- **产出**：脸皮组在脸 AngleXY warp 之下插入 ≥ 15×15 的 `DeformFaceMuscle`（轴 MouthX × CheekPuff 或分两层）；每个五官在自己的 AngleXY warp 下插入同参数修形 warp；眼的修形做成 `EyeOpen × MouthX` 二维键。
- **算法**：
  1. 场 Δ(p) 同 `aux-spread` 的模板，在脸轮廓处衰减到 0.3 保持外形。
  2. 五官锚点（眉中、眼中、嘴中、腮红中心）在场中采样 Δ，作为该五官修形 warp 的平移，再叠加特征修形：同侧眼下睑上推 0.15–0.2 H_e，`EyeOpen = 0` 行只平移不变形（避免闭眼时再眯一次造成眼线穿插）。
  3. 嘴横移时远端嘴角拉长、近端收紧上翘。
- **可调参数**：同 `aux-spread`，另 `fieldGrid` 默认 16，范围 12–20。
- **失败模式与检查**：五官层级与脸皮层级平移不一致 → 必须同一场采样；嘴横移与转头叠加总位移 ≤ 0.15 W_f。
- **引擎能力**：多维关键形（无需混合形状）。
- **难度 / 优先级**：hard / P2。

### `cheek/squint-lid-push` 颊上提推起下眼睑
- **目的**：笑、眯眼、颊上提时，下眼睑被颧部推起，眼睛变弯——"杜兴式微笑"。
- **适用**：眼区域与脸区域；与 `eye.smile/*`、`eye.emotion/additive-shapes` 配合。
- **产出**：参数 `ParamCheekSquintL/R`（0..1，可由 `eye.smile` 或 `mouth.shape` 的 MouthForm 派生）；同时写入脸修形 warp（颊上移）和眼部 warp（下睑上移）的混合形状。
- **算法**：颊上移 0.7 × 嘴角高度单位（约 0.03 H_f）；下睑上移 0.15 H_e 并拉平；可选由 CheekPuff 经 2 质点、高 delay 的摆（低通）派生 `lowerLidPush`。
- **可调参数**：`lidPush` 默认 0.15 H_e，范围 0.08–0.25；`cheekLift` 默认 0.03 H_f，范围 0.01–0.05；`deriveFrom` none|MouthForm|CheekPuff，默认 none。
- **失败模式与检查**：与 `eye.smile` 下睑上推重复计算 → 二者取 max；下睑越过瞳孔中心。
- **引擎能力**：混合形状或二维关键形；可选物理低通。
- **难度 / 优先级**：easy / P1。

### `cheek/blush-overlay` 以脸皮为蒙版的腮红/羞红叠层
- **目的**：腮红、羞红斜线等叠层随脸转向，并永远限制在脸轮廓内，可画得比脸大。
- **适用**：腮红/羞红图层（或生成的椭圆/斜线素材）；FACE 图层。
- **产出**：叠层挂在脸修形 warp 链内（与五官同级）；蒙版 = 脸皮网格；可选开关参数 `ParamCheek`（0..1，Cubism 标准"照れ"）控制不透明度。
- **算法**：腮红中心在颊最宽处偏下、尺寸约 0.25 W_f；不透明度 0→1 线性；正片叠底或普通混合按原图层属性；若参与 `aux-spread`，腮红按其区域增益跟随。
- **可调参数**：`blushOpacityMax` 默认 1，范围 0.4–1；`blushBlend` 原图层|multiply。
- **失败模式与检查**：九姿态下叠层超出脸皮面积应为 0。
- **引擎能力**：蒙版、不透明度通道。
- **难度 / 优先级**：easy / P1。

---

## 5. nose — 鼻

### `nose/current` 深度尺修形（现有）
- **目的**：转头时鼻按"深度尺"比脸面多移，表现鼻梁突出。
- **适用**：NOSE 图层。
- **产出**：`DeformNoseShape{side}`（3×4，九姿态），父级总是脸（不挂五官位移）。
- **算法**：`featureOffset` NOSE 分支：`depthByLatitude = 0.58 + 0.52v`、`noseShift%` 及若干硬编码项。
- **失败模式与检查**：开启五官位移时鼻不挂在 `DeformFeatureDisplacement` 下，与文档描述不一致（重构时核实）。
- **难度 / 优先级**：已实现 / P0 登记。

### `nose/mouth-follow` 鼻随下颌与口型微动
- **目的**：张嘴、鼓腮、撇嘴时鼻子跟随轻微下移/上推，避免鼻子钉死。
- **适用**：NOSE 图层；启用 `face.contour/jaw-follow` 或 `cheek/aux-spread`。
- **产出**：`DeformNoseShape*` 上加 MouthOpenY 轴（或混合形状），以及辅助口型参数的混合形状。
- **算法**：张嘴满值鼻下移 0.1 × 下巴下移量；鼓腮、撇嘴各 0.6 区域增益（见 `cheek/aux-spread`）。
- **可调参数**：`noseFollow` 默认 0.1，范围 0–0.2。
- **失败模式与检查**：鼻与脸皮修形不一致时"漂移"。
- **引擎能力**：关键形或混合形状。
- **难度 / 优先级**：easy / P2。

---

## 6. ear — 人耳

### `ear/current` 远侧淡出的遮挡补偿（现有）
- **目的**：转头时耳反向平移、远耳收窄并淡出。
- **适用**：EARS / EARWEAR 图层。
- **产出**：`DeformEarOcclusion{L,R}`（3×3，九姿态）+ AngleX 上的不透明度通道；父级总是脸。
- **算法**：`featureOffset` EAR 分支（反向平移 `earShift%`、远耳收窄 `earFarShrink%`）；`earOpacity` 远耳 `1 − |yaw|·earFade%`。
- **难度 / 优先级**：已实现 / P0 登记。

### `ear/face-stencil-occlusion` 脸剪影反向蒙版做连续遮挡
- **目的**：鬓发、耳、耳饰"藏到脸颊后面"的遮挡随转头连续变化，替代全有全无的淡出或绘制顺序切换。
- **适用**：目标运行时支持反向蒙版（Cubism ≥ 4.0）；候选对象满足"静止时与脸重叠 < 5%、任一 AngleX 端点重叠 > 10%"。
- **产出**：不可见脸剪影副本 `<FaceId>_Stencil`（复制脸网格与关键形，可降维只留 AngleX），共享父 warp；候选对象加 inverse mask = 该副本。
- **算法**：
  1. 复制脸皮网格（ID 确定），不透明度 0，只做蒙版源。
  2. 筛选候选（按重叠率阈值）。
  3. 某一端点该对象应完全在脸前时，该侧改用绘制顺序或做半脸副本。
  4. 所有以脸剪影为蒙版的对象共享同一蒙版源。
- **可调参数**：`overlapRestMax` 默认 0.05；`overlapPoseMin` 默认 0.1；`stencilAxes` X|XY，默认 X。
- **失败模式与检查**：运行时 < 4.0 退化为绘制顺序并写损失报告；全局蒙版缓冲上限。
- **引擎能力**：反向蒙版、复制件（复用 `SeeThroughLayers` 的复制机制）。
- **难度 / 优先级**：medium / P2（机制归 `mask`）。

---

## 7. animal-ear — 兽耳

兽耳需要新的语义（建议 `ANIMAL_EAR`，左右分侧；识别：头顶轮廓之上、三角形、在发际线以上，或用户标注）。以下模板共用标注点：耳根 = 耳与头发/头顶轮廓相交线段的中点；耳轴 = 根 → 耳尖；耳长 E；耳外倾角 β。

### `animal-ear/current` 无（现有）
- **现状**：没有兽耳语义；兽耳若被标为 EARS 只做人耳遮挡补偿，否则作为一般头部件挂 `DeformHeadContainer`。
- **难度 / 优先级**：— / P0 登记为空模板。

### `animal-ear/local-frame-stack` 兽耳变形栈：定位 → 局部坐标旋转 → 物理 → 局部视角
- **目的**：给兽耳固定顺序的变形栈，使物理摆动在耳自身坐标中发生、转头时的外观变化（露出耳内或耳背）在最内层完成，再被物理弯曲、被头部带到位。
- **适用**：`ANIMAL_EAR` 图层；可选子图层：外廓、内耳（粉）、绒毛、耳背。
- **产出**（外 → 内）：
  1. `DeformAnimalEarCarry{L,R}`：2×2 warp，轴 AngleX×AngleY，关键形 = 在头部转向场中采样耳根点的平移与缩放。
  2. `DeformAnimalEarRoot{L,R}`：旋转变形器，支点 = 耳根中点，基准角 = β（把子级 warp 的网格对齐到耳轴），以头部偏航×俯仰为轴 ±3–5°；以 `ParamAnimalEarSwing` 为轴绕根转动。
  3. 物理 warp 层（1–3 层，9×9，13 键）：`ParamAnimalEarFold`（折平/竖起）、`ParamAnimalEarTip`（耳尖弯）；可加 AngleY 轴使低头/抬头时折向不同。
  4. 每个耳网格一个局部视角 warp（轴 AngleX[×AngleY]）：偏航时宽度 × `cos(ψ ± β)`，远侧耳内层压缩到 0.3–0.5、露出外廓/耳背，近侧相反。
  5. 网格级 `ParamAnimalEarTurn` 关键形（朝向）：内耳宽度向外缘压到 0.4（−1）/ 1.15（+1），外耳反向小幅。
- **算法**：
  - 折平 −1：耳上 70% 绕耳根外转 35–45°，高度 −20%；+1：竖直拉长 8%。
  - 耳尖 ±1：上 30% 绕 0.7 高度处弯 ±25°，弯折权重沿耳轴从根 0 到尖 1 二次增长。
  - 子部件（内毛、耳背）在栈内加一个以前两级物理参数为轴的二维修正键，滞后外廓 10–20%。
  - 内毛以外耳为蒙版；内外耳沿共享边加胶水（顶点距离 < 2 px 配对）。
  - 耳根附近的头发画在耳之上（或以发际网格反向剪切耳根），防止转动时耳根从头发里穿出。
- **可调参数**：`foldAngle` 默认 40°，范围 30–45；`tipBend` 默认 25°，范围 15–30；`turnCompress` 默认 0.45，范围 0.3–0.6；`innerLag` 默认 0.15，范围 0.1–0.2；`rootLocalFrame` 默认 true。
- **失败模式与检查**：耳被头发部分遮挡时朝向关键形露出未绘制部分；多张耳网格 ID 顺序不一致导致胶水配对错位（按确定 ID 配对）；耳很短（< 头高 15%）时物理层降为 1 层。
- **引擎能力**：旋转变形器、嵌套 warp、13 键、胶水、蒙版、反向蒙版。
- **难度 / 优先级**：medium / P1。
- **差距**：无兽耳语义与结构；耳只有单层转头补偿。

### `animal-ear/blink-twitch` 眨眼触发抖耳（+ 头部惯性甩动）
- **目的**：每次眨眼耳朵下意识地抖一下（动物的反射动作），左右耳各接同侧眼，单眼眨时只抖一只；头部横移带来惯性甩动。
- **适用**：`animal-ear/local-frame-stack` 已生成；也可用于头戴软三角片（布偶耳、蝴蝶结翼）。
- **产出**：每只耳一个物理组 `PhysicsAnimalEarTwitch{L,R}`，输出到 `ParamAnimalEarSwing`（根摆）、`ParamAnimalEarFold`、`ParamAnimalEarTip`、可选 `ParamAnimalEarTurn`；可选共享组 `PhysicsAnimalEarSway`：头 X/Y（X 型）+ 头 Z（Angle 型）→ `ParamAnimalEarSway`（双耳并列 warp 的同向平移 + 旋转）。
- **算法**：
  1. 抖动组输入：同侧 EyeOpen（权重 50–100），头 AngleX/AngleY（X 型，各 15–25，可为 0）。
  2. 链：4–5 质点，首段更重更阻尼（使抖动短促不持续），质点长度按 E × {0.4, 0.3, 0.2, 0.1} 递减，m/d 向末端递减 0.05–0.1。
  3. 链序映射到越来越远端的形变：v1 → 根摆，v2 → 中段/朝向，v3 → 折平，v4 → 耳尖，形成"先转 → 再侧转 → 再折 → 耳尖最后甩"的跟随序列。
  4. reflect 让眨眼时耳朵向后抖；归一化角度负侧（后翻）取正侧的 1.5 倍，避免翻过头。
  5. 双耳不同步：左右各接同侧眼；可再给一侧 v1 乘 0.9。
  6. 标定：一次标准眨眼（0.15 s 闭、0.1 s 开）后，峰值 ≤ 耳高 0.25，0.3–0.6 s 衰减。
- **可调参数**：`blinkWeight` 默认 70，范围 30–100；`headWeight` 默认 20，范围 0–30；`swingDeg` 默认 ±12°，范围 8–20；`asymNegRatio` 默认 1.5，范围 1.3–2；`chainLength` 4|5，默认 4。
- **失败模式与检查**：长时间闭眼耳停在一侧（输出 scale 适中使闭眼稳态接近中性）；追踪眨眼抖动让耳朵颤（EyeOpen 输入加 0.05 死区）；兔耳等长耳需更多质点、更小加速度；硬质突起不要做。
- **引擎能力**：物理（多输出 v_k、reflect、不对称归一化）。
- **难度 / 优先级**：medium / P1（本组新意最高的手法之一，依赖兽耳语义先落地）。
- **差距**：眨眼只驱动眼部；没有"眨眼 → 非眼部件"的次级物理。

### `animal-ear/emotion-mood` 情绪驱动耳朵耷拉/竖起
- **目的**：兽耳作为"第二套眉毛"：皱眉、嘴角下撇时耷拉，开心时竖起；嘴向一侧撇时同侧耳下压。
- **适用**：兽耳栈已生成；有眉参数（BrowY 或 BrowForm）与 MouthForm，可选 MouthX。
- **产出**：参数 `ParamAnimalEarMood{L,R}`（±1）挂在耳根定位层之下的姿态 warp；物理组 `PhysicsAnimalEarMood`：BrowForm/BrowY（Angle 型，40–45）+ MouthForm（15）[+ MouthX 一侧取反]→ Mood（欠阻尼单摆，持续值而非瞬态）。可选：表情开关 E 在姿态 warp 上加一层以 E[0, 0.5] 为轴的压耳形，E 后半程切换替代耳造型。
- **算法**：
  - Mood −1（耷拉）：绕根向外下旋 25–35°，耳廓压扁 15%；或"飞机耳"（外翻 70–90°，同时绕耳轴转 30° 使内耳朝前）；或"向后下压"（绕根水平轴后倒 60–80°，投影长度 0.4–0.6）。
  - Mood +1（竖起）：向内上旋 8–12°，拉长 3–5%。
  - 关键形按"绕耳根的三维刚体旋转 + 正交投影"计算，把耳当作略弯的薄片。
  - 眉未被追踪时 Mood 恒 0 → MouthForm 权重提高到 30% 作后备。
- **可调参数**：`droopDeg` 默认 30，范围 20–40；`raiseDeg` 默认 10，范围 5–15；`browMouthRatio` 默认 3:1；`droopStyle` outward|flatten|backward，默认 outward。
- **失败模式与检查**：耷拉时耳尖穿进头发（限制耳尖不低于头顶轮廓下 0.15 头高）；外翻后与帽子、头发的绘制顺序需随参数切换；表情动作若直接写 Mood 会与物理冲突（动作应写输入参数）。
- **引擎能力**：物理（Angle 型持续输入、欠阻尼）、多维关键形。
- **难度 / 优先级**：medium / P2。

### `animal-ear/combo-correction` 大角度组合修正键
- **目的**：抖、外翻、下压与转头都是大角度运动，嵌套 warp 线性叠加会穿帮；对最显眼的一张耳面按三维刚体组合精确求值。
- **适用**：启用 `emotion-mood` 的大角度样式；仅主耳面网格。
- **产出**：主耳面网格（或其最内层 warp）的 4 维关键形 `Twitch × Flatten × Droop × AngleX`（每轴 3 键，共 81 键）；其余耳图层保持嵌套 warp 近似。
- **算法**：对每个格点重新做一次"绕耳根刚体旋转组合 + 投影"，而不是叠加各轴形变——程序化生成天然能算出精确键。
- **可调参数**：`comboAxes` 选哪几轴参与；`keysPerAxis` 默认 3。
- **失败模式与检查**：键数爆炸（只给 1 张网格）；与其余图层的嵌套近似在极值处错开。
- **引擎能力**：高维关键形。
- **难度 / 优先级**：hard / P2（research）。

### `animal-ear/flick-motion` 动作中的抖耳脉冲
- **目的**：打招呼、感动、唱歌等动作中耳朵快速抖 2–3 下，余振交给物理。
- **适用**：兽耳栈已生成。
- **产出**：动作轨道写 `ParamAnimalEarFold`（或冲量输入参数）：2–3 次交替脉冲，每次 0.1–0.2 s，幅度为范围的 30–80%；或 0.1 s 压平 30% 再弹回。
- **可调参数**：`pulses` 默认 3；`pulseSec` 默认 0.17；`amp` 默认 0.5。
- **失败模式与检查**：动作直接写被物理驱动的参数会被覆盖 → 写冲量输入参数。
- **引擎能力**：动作轨道、物理冲量输入。
- **难度 / 优先级**：easy / P2（归 `motion.idle` 协作）。


---

## 8. eye.lid — 眼睑开合、视线带眼睑、眨眼余震

眼部模板共用的标注：眼白 / 上睑线（睫毛、眼线）/ 下睑线 / 双眼皮线 / 眼影 / 闭眼线 / 虹膜 / 瞳孔 / 高光（可多块）。H_e = 上睑闭合行程（眼高），W_e = 眼宽，G_x/G_y = 虹膜视线行程，R_i = 虹膜半径。

### `eye.lid/current` U 形闭眼（现有）
- **目的**：EyeOpen 0 时上睑落到深 U 形闭眼线，睫毛保留粗细。
- **适用**：EYEWHITE、EYELASH、EYE_CLOSE。
- **产出**：参数 `ParamEyeL/ROpen{0,1}`（默认 1）；网格关键形 `eyeClosureGrid`；闭眼线只有不透明度 `1 − open`；Side.NONE 时按顶点 x 拆到两个参数。
- **算法**：`edgeY = top + h·blinkCorner%`、`curveY = edgeY + max(1.5, h·blinkDepth%)·(1 − nx²)`；睫毛竖直保留 `eyelashSquash%`，眼白压成约 1.2 px 带；睫毛逐列沿 alpha 加权中心线锚定（原画有倾斜时禁用）。
- **失败模式与检查**：只有 0/1 两键，半闭时上睑弧被线性插值压平；闭眼线与睁眼组同时半透明时出现"跳线"。
- **难度 / 优先级**：已实现 / P0 登记。

### `eye.lid/arc-blink` 保弧中间键与闭眼线瞬切
- **目的**：让上睑以弧线下落（中段先垂、像包住眼球），并在接近闭合时干净地换成手绘闭眼线。
- **适用**：眼白 + 上睑线；闭眼线可选（无闭眼线时只加中间键）。
- **产出**：EyeOpen 键 `{0, ε, t_mid, 1}`（有闭眼线）或 `{0, t_mid, 1}`；眼白（同时也是虹膜蒙版）必须使用同一组键；不透明度通道：睁眼组 `{0:0, ε:1}`，闭眼线 `{0:1, ε:0}`。
- **算法**：
  1. `1`：原画。
  2. `ε`（0.05）：上睑轮廓沿每列移到下睑线（只移睑线、保持睫毛厚度），眼白高度 ≈ 0；闭眼线位置取 ε 形的睑线位置，两者重合使切换不可见。
  3. `t_mid`（0.3–0.5）：每列下移量 = `h(x)·(1 − f(t_mid))`，f 使睑中段比两端提前 10–25% 下垂；上睑弧顶仍高出两端（保留约 50% 原弧高），按弧长参数在开眼弧与闭眼线之间插值。
  4. 检查 `t_mid` 处睫毛与眼白边缘不交叉、虹膜不从眼白蒙版漏出。
- **可调参数**：`epsSwap` 默认 0.05，范围 0.03–0.08；`midKey` 默认 0.35，范围 0.25–0.5；`midSagLead` 默认 0.15，范围 0.1–0.25；`midArcKeep` 默认 0.5，范围 0.3–0.7。
- **失败模式与检查**：眼白蒙版键与眼睑键不一致 → 虹膜在中间键漏出；闭眼线与 ε 形不重合 → 跳线。
- **引擎能力**：非均匀多键、不透明度通道。
- **难度 / 优先级**：easy / **P0**。
- **差距**：现状只有 0/1 两键、闭眼线与开合线性交叉。

### `eye.lid/wide-overrange` 睁眼超量程（惊讶睁大）
- **目的**：一个开合参数覆盖"闭 → 中性 → 睁大"三态；睁大时上睑上提、下睑下移、虹膜与瞳孔缩小（漫画式惊吓），中性仍是原画零变形。
- **适用**：眼部完整分层；对开合追踪做宿主映射的目标（VTube Studio 等）。
- **产出**：`ParamEyeL/ROpen` 范围扩为 `[0, 1, Omax]`，默认 1；新增键 `Omax`（可选中间键 `(1+Omax)/2`）；虹膜组外包一层以 EyeOpen 为轴的 warp `DeformIrisWide{L,R}`；眉可选以 EyeOpen 为轴上移（见 `brow/blink-dip` 的 wide-raise 选项）；导出宿主映射：追踪开合 0..1 → 参数 0..`hostMax`，使正常睁眼落在 1。
- **算法**：
  1. `Omax` 关键形：上睑行 +0.2 H_e（中心最大，按 cos 衰减到眼角 0.3 倍），下睑 −0.1 H_e，眼白随之扩张。
  2. 虹膜组以中心缩放 0.85（瞳孔 0.75），高光不缩（放在缩放层之外）或同比缩。
  3. 眼影/睑褶阴影不随睁大变化。
  4. 宿主映射：`hostMax = Omax × 中性追踪校准值`（建议 1.8–2.0）；为防中性附近抖动进出睁大段，虹膜缩放从 `1 + 0.2·(Omax−1)` 才开始（死区）。
  5. 兼容：旧工程保持 `[0,1]`；新模板版本才扩范围（参数范围变化须走模板版本而非静默修改）。
- **可调参数**：`openMax` 默认 2.0，范围 1.3–2.0；`wideLift` 默认 0.2 H_e，范围 0.12–0.3；`irisShrink` 默认 0.85，范围 0.75–0.92；`shrinkDeadzone` 默认 0.2；`hostMax` 默认 1.9，范围 1.6–2.0。
- **失败模式与检查**：追踪噪声让瞳孔一闪一闪（死区）；`Omax` 与笑眼、表情组合须逐一检查（见 `eye.smile/closed-row` 的 wide-row 规则）；标准动作文件只给 0..1 时睁大段不可达（由表情或增益触发）。
- **引擎能力**：参数范围扩展、宿主映射导出。
- **难度 / 优先级**：easy（几何）/ medium（兼容）/ P1。
- **差距**：EyeOpen 只有 0..1，无睁大态；宿主映射为 1:1。

### `eye.lid/gaze-follow` 视线带动眼睑
- **目的**：往上看上睑抬起，往下看上睑下垂、多盖住虹膜，横看时睑弧最高点偏向视线方向——眼珠不再像贴纸在眼眶里滑。
- **适用**：眼睑组（上睑线、睫毛、下睑、眼白、双眼皮线、眼周阴影）与虹膜组可分开挂载。
- **产出**：在 `DeformEyeShape{L,R}` 内、眼睑组外插入 `DeformEyeLidGaze{L,R}`（3×3 键，EyeBallX × EyeBallY；lattice ≥ 5×5）；虹膜组仍在 `DeformEyeGaze` 支路。可选（`lagged`）：派生参数 `ParamEyeBallX2/Y2{L,R}` 由物理（摆长中等、阻尼 0.9）从 EyeBallX/Y 生成，眼睑 warp 改以派生参数为轴，得到"眼珠先到、眼皮后到"。
- **算法**：
  1. 竖直：Y+（上看）上睑控制行上移 `kUp·G_y`、下睑上移 0.2·G_y；Y−（下看）上睑下移 `kDown·G_y`、下睑下移 0.25·G_y。
  2. 横向：上睑弧顶向视线方向偏移 `kX·G_x`；整眼框平移 `kShift·G_x`，近视线侧眼角略收窄。
  3. 内外眼角权重为 0（钉住）。
  4. 闭眼时耦合趋零：若眼睑栈在闭眼 warp 之外，改用 `EyeBall × EyeOpen` 组合键，闭眼端只保留 30%；或把本 warp 放在开合 warp 之内（层级相乘自然满足）。
  5. 检查 25/50/75% 闭合与视线极值的组合。
- **可调参数**：`kUp` 默认 0.6，范围 0.4–0.7；`kDown` 默认 0.7，范围 0.5–0.8；`kX` 默认 0.3，范围 0.2–0.45；`kShift` 默认 0.15，范围 0–0.3；`lagged` 默认 false；`closedKeep` 默认 0.3。
- **失败模式与检查**：耦合过大像整只眼在脸上滑；眼区 warp 已有多轴时再乘视线 3×3 会使关键形 ×9 → 改用混合形状或独立 warp 层。
- **引擎能力**：嵌套 warp、可选物理派生参数。
- **难度 / 优先级**：easy / **P0**。
- **差距**：现有视线只平移虹膜（`gazePoint`），眼睑不随视线。

### `eye.lid/blink-aftershock` 眨眼余震：睑过冲、眼睑回弹、睫毛甩动
- **目的**：眨眼后上睑略过冲再回落、睫毛像细毛一样弹一下，让每次眨眼"有弹性"，不再像快门。
- **适用**：上睑线/睫毛分层；睫毛尖可单独分区（或按到睑线距离加权）。
- **产出**（每只眼，均为派生参数 + 物理组）：
  - `ParamEyeLidOvershoot{L,R}`（−1..1 或 −1..2）：与 EyeOpen 组成二维键，或作为眼框 warp 的混合形状；
  - `ParamEyeLidBounce{L,R}`（±1）：上睑弧竖向回弹；
  - `ParamEyeLash1..3{L,R}`（±1，或单向 0..1 下压）：睫毛分段甩动的混合形状；
  - 物理组 `PhysicsEyeLid{L,R}`：输入同侧 EyeOpen（X 型 100）[+ 眉 Y X 型 30，可选 + MouthForm 5]；睫毛组 `PhysicsEyeLash{L,R}`：EyeOpen（X 型 100）[+ EyeSmile 10]。
- **算法**：
  1. 睑过冲：单节点、长摆（重而慢），输出 scale 使一次完整眨眼峰值 ≈ 满值 0.3；关键形 (open=1, overshoot=+1) 上睑再抬 0.08–0.1 H_e、下睑略降，(open=0, overshoot=−1) 眼线上挤。
  2. 眼睑回弹：2 质点；上睑弧竖向 ±0.25 H_e，沿睑缘余弦分布（中 1、眼角 0），眼白跟随 50%，下睑不动。
  3. 睫毛：4 质点、加速度大（"甩一下"）；三种形状二选：
     - 分层（`layers`）：B1 整体下压（单向，0.05 H_e）、B2 以内眼角为支点倾斜 ±3°、B3 只作用睫毛尖、按到睑线距离加权旋转 ±5°；B1←v1，B2/B3←v2。
     - 分段（`segments`）：沿睫毛线分内/中/外三段，各一个"上翘/下压"形状（0.08–0.1 H_e），段 k 读 v_k，中段取反，使三段不同相。
  4. 闭眼时余震不可见：几何层放在闭眼 warp 之内，或形变幅度按 Open 衰减。
  5. 标定：一次标准眨眼后可见振荡 ≤ 2 次，0.4–0.8 s 内稳定；峰值为各元件尺寸的 2–4%。
  6. 物理归一化中心放在"睁眼静息态"，否则静息时持续偏移。
- **可调参数**：`overshootAmp` 默认 0.08 H_e，范围 0.06–0.12；`lidBounceAmp` 默认 0.2 H_e，范围 0.15–0.3；`lashMode` layers|segments，默认 segments；`lashAmp` 默认 0.08 H_e（或 6°），范围 0.04–0.1 H_e / 4–10°；`browInput` 默认 0，范围 0–30；末质点阻尼 ≥ 0.7。
- **失败模式与检查**：连续快速眨眼余震叠加不衰减（末质点阻尼下限、限制输出幅度）；追踪眨眼信号抖动导致持续微颤（EyeOpen 输入 0.05 死区，或过冲链 d ≥ 0.85）；极快眨眼（< 80 ms）过冲偏大；与笑眼组合时过冲方向要跟着变（二维键覆盖 Smile=1）；睫毛形状超出眼睑蒙版出现毛刺；透过刘海的复制件须继承同样的混合形状（现有复制件已共用）。
- **引擎能力**：物理多输出（v_k、reflect）、混合形状、二维关键形。
- **难度 / 优先级**：medium / P1（与 `eye.iris/jelly-layered` 同批实现，共用"眨眼激励"物理工具）。
- **差距**：现有 `PhysicsEyeJelly` 只输出 `ParamEyeBallForm`，没有睑过冲、眼睑回弹、睫毛惯性。

### `eye.lid/overlay-early-fade` 睑上覆盖层提前淡出
- **目的**：眼影、睑缘阴影、加算/滤色反光层在闭眼前就沉底淡出，闭合线上不留色块或亮斑。
- **适用**：附着在上睑、被眼白剪切的薄层；眼内所有 Additive/Screen 层；眼影层。
- **产出**：这些层的不透明度通道（与可选绘制顺序键）以 EyeOpen 为轴。
- **算法**：
  - 睑缘阴影/反光：EyeOpen 加中间键 t₀；[0, t₀] 不透明度 0、绘制顺序降到眼白之下；[t₀, 1] 线性回到原值。
  - Additive/Screen 反光：不透明度 = 原值 × EyeOpen（线性，0 时为 0）。
  - 眼影：闭眼时保留约 55%（只淡一半）。
- **可调参数**：`fadeStart` 默认 0.25，范围 0.2–0.3；`eyeshadowClosed` 默认 0.55，范围 0.4–1。
- **失败模式与检查**：识别"附着在上睑"的层依赖标注或位置（中心在眼白上 1/3 且被眼白剪切）。
- **引擎能力**：不透明度通道、绘制顺序关键形（若不支持则只做不透明度）。
- **难度 / 优先级**：easy / **P0**。
- **差距**：未统一处理眼内覆盖层随开合淡出。

---

## 9. eye.smile — 笑眼

### `eye.smile/current` 无独立笑眼（现有）
- **现状**：别名"笑眼/眯眼"并入 `EYE_CLOSE`，只作为闭眼线以不透明度出现；没有 `ParamEyeL/RSmile` 与笑眼形变。
- **难度 / 优先级**：— / P0 登记。

### `eye.smile/closed-row` 笑眼只改变闭合路径
- **目的**：笑意只在半闭和闭眼时把下睑抬起、闭眼线弯成 ^；睁眼时完全不动——追踪的笑意持续波动也不会扭曲睁着的眼，"笑着眨眼呈 ^^"。
- **适用**：眼部标准分层；新参数 `ParamEyeL/RSmile`（0..1，默认 0，Cubism 标准名）。
- **产出**：眼部网格/眼睑 warp 的键网格扩为 `EyeOpen{0, t_mid, 1[, Omax]} × EyeSmile{0, 1}`；眼周装饰（泪痣、妆点、眼下装饰）绑定同一网格；表情与追踪映射：MouthSmile 0.3–0.5 起映射到 EyeSmile。
- **算法**：
  1. 强制 (Open=1, Smile=1) ≡ (Open=1, Smile=0)。
  2. (0, 1) 笑眼闭合线：`y(x) = y_c(x) − k·(1 − (2x'/W_e)²)`，k = 0.3 H_e（中心上拱），眼角下垂 0.05 H_e；下睑上推 0.1 H_e（颧肌）。普通闭眼 (0, 0) 保持现有下垂弧。
  3. (t_mid, 1)：下睑上抬 0.3 H_e，上睑弧略压。
  4. 睁大行（若启用 `wide-overrange`）：(Omax, 1) = (1,0) + 0.3 × [(Omax,0) − (1,0)]，避免"笑着惊恐"。
  5. 眼周装饰按与下睑距离衰减跟随。
  6. 检查 (0.5, 0.5)、(0.25, 1) 等组合上下睑线不交叉。
- **可调参数**：`smileArc` 默认 0.3 H_e，范围 0.2–0.4；`smileLowerPush` 默认 0.1 H_e，范围 0.05–0.3；`wideKeep` 默认 0.3，范围 0.2–0.5；`hostSmileStart` 默认 0.4，范围 0.3–0.5。
- **失败模式与检查**：与 `eye.smile/mouthform-coupled` 同时启用会重复计算（二选一或取 max）；笑眼闭合线与普通闭眼线差异太小时读不出。
- **引擎能力**：二维关键形。
- **难度 / 优先级**：easy / **P0**（新增参数，旧工程不受影响）。
- **差距**：标准参数无 EyeSmile。

### `eye.smile/mouthform-coupled` 嘴形作为眼部第二轴
- **目的**：不依赖 EyeSmile 输入（很多追踪方案不输出），嘴角一笑眼睛就笑；MouthForm 负侧表现不满/委屈。
- **适用**：没有 EyeSmile 追踪的目标；MouthForm 主要由追踪而非口型同步驱动。
- **产出**：眼部各变形器（眼框、睫毛、眼白、虹膜、泪、腮红）的 EyeOpen 轴扩为 `EyeOpen × MouthForm{-1, 0, d, 1}`（d = 死区键）；眉的闭眼联动层同样加 MouthForm 轴。
- **算法**：
  1. MouthForm = +1：下睑上推 0.4 × 嘴角行程（约 0.15–0.25 H_e），眼裂外角微扬；EyeOpen = 0 时闭眼线成上弯笑眼弧；虹膜随下睑被稍遮。
  2. MouthForm = −1：下睑略降，上睑略压。
  3. 眉：+1 时整体上移 0.2 × 嘴角行程并舒展。
  4. 死区：在 MouthForm 轴加中间键 d = 0.3，0–0.3 区间眼部变化很小（说话时 MouthForm 会抖）。
- **可调参数**：`couple` 默认 0.4，范围 0.2–0.5；`positiveOnly` 默认 true；`deadzone` 默认 0.3，范围 0–0.4。
- **失败模式与检查**：口型同步驱动 MouthForm 的应用里眼睛会跟着说话乱动（死区或改用物理平滑，见 `brow/emotion-fanout`）。
- **引擎能力**：二维关键形。
- **难度 / 优先级**：medium / P1。

---

## 10. eye.emotion — 眼部情绪形与表情件

### `eye.emotion/current` 无（现有）
- **现状**：表情只是参数偏移集（`ModelSidecars`），不生成眼部情绪形、替换瞳孔、泪或阴影层。
- **难度 / 优先级**：— / P0 登记。

### `eye.emotion/additive-shapes` 可叠加的眼部情绪形
- **目的**：惊讶（睁大 + 缩瞳）、上睑下垂（困/不屑）、下睑上推（眯）作为加法层，任意组合都不需要再增加关键形维度。
- **适用**：眼部标准分层；目标支持混合形状（否则烘焙为额外轴，只烘焙单形状 × 开合）。
- **产出**：每只眼的混合形状参数（0..1）`ParamEyeWide{L,R}`、`ParamEyeLidDroop{L,R}`、`ParamEyeSquint{L,R}`，作用在眼睑组网格/warp 与虹膜组 warp 上。
- **算法**：
  - Wide：上睑 +0.15 H_e、下睑 −0.05 H_e，虹膜组以中心缩放 0.85。
  - LidDroop：上睑 −0.23 H_e（弧形保持）。
  - Squint：下睑 +0.15 H_e 并拉平。
  - 组合检查：在加法层空间各角点求值，上睑线必须始终在下睑线之上；交叉则按比例缩小，或加一个修正组合键。
  - 与开合相乘的收敛：每个形状在 EyeOpen 的各键分别计算，使闭合时收敛到同一条线（`eye.emotion/expression-blink-matrix` 的同一规则）。
- **可调参数**：`wideLift` 默认 0.15 H_e，范围 0.1–0.2；`wideIris` 默认 0.85，范围 0.8–0.9；`droop` 默认 0.23 H_e，范围 0.2–0.3；`squint` 默认 0.15 H_e，范围 0.1–0.2。
- **失败模式与检查**：Wide 与 `eye.lid/wide-overrange` 功能重叠——选其一作为"惊讶睁大"的主实现（追踪向选 overrange，表情向选 additive）。
- **引擎能力**：混合形状、烘焙降级。
- **难度 / 优先级**：medium / P1。

### `eye.emotion/tension-axis` 双极张力轴
- **目的**：一个中性在中间的参数同时给出"眯"（怀疑、得意、困）与"瞪"（惊讶），与开合组成二维眼睑空间，表情中也能眨眼。
- **适用**：动作/表情驱动为主的模型（游戏立绘、短剧）。
- **产出**：参数 `ParamEyeTension{L,R}`（0..1，默认 0.5）；眼部网格键 `Tension{0, 0.5, 1} × EyeOpen{0, 1}`（6 个角点全部建键）。
- **算法**：Tension = 1：上睑中部 +0.15 H_e、曲率 +20%，下睑 −0.05 H_e；Tension = 0：上睑 −0.2 H_e、曲率变平、内眼角略下，下睑 +0.05 H_e；闭眼行三种张力对应不同弧度的闭眼线；虹膜、睫毛网格跟随。
- **可调参数**：`wide` 默认 +0.15 H_e，范围 0.1–0.2；`narrow` 默认 −0.2 H_e，范围 0.15–0.25；`curvatureDelta` 默认 0.2。
- **失败模式与检查**：与 `additive-shapes` 的 Wide/Squint 重复，二选一。
- **引擎能力**：二维关键形。
- **难度 / 优先级**：medium / P2。

### `eye.emotion/expression-blink-matrix` 表情眼形 × 眨眼
- **目的**：表情改变眼形（怒眼、泪眼、半睁）后仍能眨眼，且闭合形与该表情眼形匹配。
- **适用**：有表情眼形（来自差分图层或 `additive-shapes`）。
- **产出**：内部以加性形状 Δ_E 表达每个表情，在 EyeOpen 的每个键上分别计算；导出到不支持混合形状的目标时只烘焙"眨眼 × 单表情"，不烘焙表情 × 表情；闭眼线组不透明度 = `(EyeOpen < ε) × (没有符号眼/笑眼表情)`。
- **算法**：以表情眼形 B_E 为基础，眨眼 = 把 B_E 的上睑沿自身弧线压到下睑线；保证完全闭合时所有 Δ_E 收敛到同一条线。
- **可调参数**：`bakeLimit`（每网格关键形上限）默认 64，范围 32–128。
- **失败模式与检查**：笛卡尔烘焙爆炸（表情 × 表情 × 眨眼）；加性组合在完全闭合处不收敛 → 闭眼线双线。
- **引擎能力**：混合形状、导出烘焙策略。
- **难度 / 优先级**：medium / P2。

### `eye.emotion/replacement-pupil` 替换瞳孔与眼内符号
- **目的**：星星眼、爱心眼、漩涡眼、花纹瞳、空白眼、暗瞳等挂在虹膜链最内层，自动继承视线、转头视差、闭眼压扁、眼部果冻和惊吓缩瞳；原有高光在任一变体开启时淡出。
- **适用**：替换瞳孔素材（原画差分或素材库，按虹膜尺寸放置）；眼白蒙版。
- **产出**：每个变体一个开关参数（0..1，不透明度线性 0→1）；变体图层挂在 `eye.iris/jelly-layered` 的最内层 warp 之下（或 `DeformEyeGaze` 之下），以眼白为蒙版，常用加算混合；冲突的原图层（高光、瞳孔纹理）由 `eye.highlight/gate` 门控；可选小物理：输入开关本身（X 型 40–50）、同侧 EyeOpen（40–100）、呼吸（40–50）→ 变体 warp 的缩放 ±10–15%、旋转 ±15°、下垂混合形状；旋转类符号（漩涡）用旋转变形器 + 循环参数（0 → 0°，max → N·360°）。
- **算法**：
  1. 素材相对尺寸：心形 0.6 虹膜直径、星光 0.5、漩涡填满 0.9 R_i；
  2. 插入到高光同层，与高光共用二维果冻键；
  3. 空白眼 = 整组虹膜淡出；暗瞳 = 虹膜正片叠底变暗或替换。
- **可调参数**：`variantScale` 与虹膜一致；`suppressHighlight` 默认 true；`variantPhysics` 默认 false。
- **失败模式与检查**：与常规高光重叠过曝（门控）；变体若不在虹膜链内，视线/眨眼时会脱位。
- **引擎能力**：蒙版、加算混合、不透明度通道、乘积门控、可选物理与旋转循环参数。
- **难度 / 优先级**：easy（绑定）/ hard（素材）/ P1。

### `eye.emotion/symbol-mode` 漫画符号眼模式
- **目的**：○ 眼、>< 眼、^^ 等符号表情是画风切换：写实眼整组淡出、符号眼淡入，同时换眉、换腮红、隐藏写实小特征。
- **适用**：符号层素材；写实层/符号层标注（眼白、睫毛、虹膜、高光、鼻、眼下阴影 vs ○、><、汗、黑线）。
- **产出**：符号表情参数 E（0..1）；写实层组不透明度 1−E（组级 warp 不透明度），符号层 E；符号层细节挂在符号主体组下得到 E² 淡入；符号组挂在各眼的 AngleX warp 下但不挂视线 warp；符号层仍以 EyeOpen 为键（能眨眼）。
- **算法**：
  1. 过渡类型按特征分类（`toggle` 槽提供实现）：硬线条可配准的（^^、闭眼线、怒眉）先形变到重合、最后 1% 切换；口型先切后形变；柔边叠层线性交叉；不可配准符号线性交叉并压缩到 [0.3, 0.7]。
  2. 符号表情下禁用写实联动（闭眼线不可见、视线不作用于符号）。
  3. 进入时替换件从缩小 0.6–0.85 或偏移处展开到位（几何键与不透明度同步）。
- **可调参数**：`crossBand` 默认 [0.3, 0.7]；`detailExp` 默认 2；`enterScale` 默认 0.75，范围 0.6–0.85；`hiddenRealFeatures` 列表。
- **失败模式与检查**：线性交叉在 0.5 时两套半透明显脏；符号层不应被眼白剪切；替换件不继承开合键会在眨眼时露馅。
- **引擎能力**：组级不透明度、嵌套淡入。
- **难度 / 优先级**：medium（编排）/ hard（素材）/ P2。

### `eye.emotion/tears` 泪膜与泪滴
- **目的**：泪膜是眼球上的水光（被眼形剪切、随开合变形），泪滴骑在下睑缘（可溢出眼外、随下睑微动、有自己的视差）。
- **适用**：泪素材（或生成）；眼白、下睑线。
- **产出**：开关参数 `ParamTear{L,R}`（0..1）；泪膜 = 复制眼白形的网格，以眼白为蒙版，EyeOpen 键与眼白相同，不透明度随开关；泪滴锚在下睑线外侧 0.7 处，挂在 `EyeOpen{0, t_mid, 1}` warp → 独立 AngleX/AngleY warp 下，不剪切；可选物理（EyeOpen + 开关 + 呼吸）让泪光晃动。
- **算法**：泪滴位移 = 下睑线在锚点的位移 × 0.9；泪光淡入时额外下移 0.2–0.3 H_e；泪滴视差系数同高光。
- **可调参数**：`anchor` 默认 0.7，范围 0.6–0.8；`lidFollow` 默认 0.9，范围 0.8–1.0；`tearSlide` 默认 0.25 H_e。
- **失败模式与检查**：眨眼输入权重太大时泪光在眼睑外露出（依赖蒙版）。
- **引擎能力**：蒙版、不透明度通道、可选物理。
- **难度 / 优先级**：easy（绑定）/ P2。

### `eye.emotion/gloom` 阴沉脸："幕布"阴影 + 去高光 + 压眼
- **目的**："黑脸/阴沉"由多个线索组成：上半脸阴影像幕布从额头落下、眼睛失去高光、上睑下压、眉变化，一个参数统一调度。
- **适用**：FACE 图层；高光分层；需要生成辅助纹理（纵向渐变、可选竖线）或原画已有阴影层。
- **产出**：参数 `ParamGloom`（0..1）；层 A：纵向渐变正片叠底（上深下透明，颜色按肤色取暗紫/蓝紫色相，不用纯灰），以脸皮（和刘海）为蒙版，挂在脸修形 warp 链内、五官之上、刘海之下；层 B（可选）：眼区正片叠底，以眼白/眼睑为蒙版，P > 0 时绘制顺序升到眼部之上；可选竖线层；高光门控（`eye.highlight/gate`）；眼框 warp 上睑下压。
- **算法**：
  1. 0 键：A 位于脸上方（偏移 1–1.2 H_f，完全在蒙版外）、不透明度 0；1 键：A 盖到眼下缘，不透明度 0.6–0.7。
  2. 高光不透明度在 P 的 0–0.6 区间降到 0。
  3. 上睑下压 0.05–0.08 H_e；眉按 `brow/shape-axes` 的怒形小幅。
- **可调参数**：`slide` 默认 1.0 H_f，范围 0.8–1.2；`maxOpacity` 默认 0.65，范围 0.5–0.8；`lidPress` 默认 0.06 H_e；`tint` 按肤色。
- **失败模式与检查**：蒙版不是整张脸时滑动途中露出边缘；阴影透过半透明刘海变脏（放在刘海之下）。
- **引擎能力**：辅助纹理生成钩子、蒙版、正片叠底、不透明度与位移关键形、乘积门控。
- **难度 / 优先级**：easy（绑定）/ medium（纹理钩子）/ P2（与 `lighting`、`effect` 协作）。

### `eye.emotion/shock-tremor` 常驻振荡器 × 表情门控
- **目的**：惊吓时瞳孔高频颤动；表情只能给静态值，用"永远在跑的振荡器 × 门参数"的二维键把静态开关变成动画。
- **适用**：有循环待机动作的导出目标。
- **产出**：振荡参数 `ParamTremor`（±1）与门参数 `ParamShockGate`（0..1）；虹膜组 warp 建 `Gate{0,1} × Tremor{-1,0,1}` 键：Gate = 0 时三个 Tremor 键相同；所有循环待机动作加 Tremor 轨（随机电报信号，每 60–120 ms 在 ±A 间切换，随机插 0，循环 ≥ 15 s）；表情文件只写 Gate。
- **可调参数**：`tremorAmp` 默认 0.1 H_e，范围 0.05–0.12；`switchMs` 默认 90，范围 60–120。
- **失败模式与检查**：待机动作被替换后振荡消失；同一振荡器可复用到汗滴抖、怒符跳、身体发抖。
- **引擎能力**：乘积门控（二维关键形）、动作轨道。
- **难度 / 优先级**：easy / P2（与 `motion.idle` 协作）。

---

## 11. eye.iris — 虹膜、瞳孔与视线

### `eye.iris/current` 瞳孔保持 + 平移视线 + 虹膜果冻（现有）
- **目的**：转头时虹膜按 `featureOffset IRIS` 反向平移与补宽；视线纯平移；眨眼物理驱动虹膜绕质心缩放。
- **适用**：IRIDES（高光与虹膜同 tag）。
- **产出**：`DeformIrisPreserve{L,R}`（3×3，九姿态）→ `DeformEyeGaze{L,R}`（2×2，EyeBallX/Y，`gazePoint` 纯平移）；虹膜网格关键形 `irisJellyGrid`（`ParamEyeBallForm{-1,0,1}`）；物理 `PhysicsEyeJelly`：EyeL/ROpen（X 型 50）→ `ParamEyeBallForm`（两眼共用一个输出）；蒙版 = 同侧眼白。
- **失败模式与检查**：高光与虹膜同动；两眼共用一个果冻输出；无形状/位置透视分层。
- **难度 / 优先级**：已实现 / P0 登记。

### `eye.iris/perspective-split` 形状透视与位置透视分层，视线夹在中间
- **目的**：转头后再看向侧面时，虹膜仍沿转过去的眼眶移动；虹膜形状的透视压缩（变窄成椭圆）与视线位置无关。
- **适用**：虹膜组可独立挂载。
- **产出**（外 → 内）：位置透视层（AngleX×AngleY，与眼白所在 warp 的透视一致，即现 `DeformIrisPreserve`）→ 视线平移层（EyeBallX×Y）→ 开合层（可选，`eye.iris/close-squash`）→ 形状透视层 `DeformIrisShape{L,R}`（AngleX×AngleY，只做横向压缩 `cos(yaw)` 并向远侧偏，不做位移）→ 果冻层（`jelly-layered`）→ 网格。
- **算法**：视线行程按眼白宽度 15–20%，Y 可略大于 X（竖长动漫眼 1.2–2 倍）；形状层压缩系数 k：`cos(θ·k)`，k 0.7–0.8；所有层在眼白蒙版下。
- **可调参数**：`gazeRangeX` 默认 0.18 W_e，范围 0.13–0.25；`gazeYRatio` 默认 1.2，范围 1–2；`shapeCompressK` 默认 0.75，范围 0.6–0.85。
- **失败模式与检查**：内外两层重复施加压缩（内层只做形状、不做位移）。
- **引擎能力**：嵌套 warp。
- **难度 / 优先级**：medium / P1。

### `eye.iris/depth-parallax` 眼内分层深度视差
- **目的**：虹膜贴在球面上、瞳孔与高光在不同深度；转头时三者有不同位移与压缩，形成"镜片/玻璃体"般的深度。
- **适用**：眼内至少分 {虹膜底、瞳孔、高光}；更细可分 {瞳线、瞳涂、虹彩、瞳孔、瞳孔饰、高光、反射}。
- **产出**：在视线层之下为每个深度组建一个 AngleX[×AngleY] warp `DeformIrisDepth<k>{L,R}`（3 键/轴，5×5 lattice）。
- **算法**：
  1. 深度 d_r（0..1）：瞳线 0、涂色 0.2、虹彩 0.4、瞳孔 0.6、高光 0.9、反射 1.0。
  2. 端点关键形相对父级额外平移 `δ_r = s·(d_r − 0.5)·W_iris·sign(AngleX)`，并按转向压缩 `cos(θ·k)`。
  3. 瞳孔：在虹膜位移基础上再多移 10–20%（更深）。
  4. 方向符号可配置：`deeperLags`（深层相对表面落后，舌/瞳孔模型）或 `frontLeads`（角膜在前，高光多移）；高光的方向由 `eye.highlight/parallax-layer` 单独决定。
  5. 所有层在眼白蒙版内，平移后检查不出界。
- **可调参数**：`parallaxMax` 默认 0.08 W_iris，范围 0.05–0.12；`pupilExtra` 默认 0.15，范围 0.1–0.2；`depthSign` 默认 deeperLags；Q 版（眼很小）可关闭。
- **失败模式与检查**：视差过大在眼角被眼白截掉。
- **引擎能力**：嵌套 warp。
- **难度 / 优先级**：medium / P1。
- **差距**：现有虹膜与眼睑共享同一套面部形变，无按深度的独立视差。

### `eye.iris/jelly-layered` 眨眼激发的分层果冻物理（扩展 `PhysicsEyeJelly`）
- **目的**：眨眼（以及笑眼、眉动）作为冲击输入，让眼眶、虹膜、瞳孔、每颗高光以不同频率、不同相位回弹，读作含水、有弹性的眼球，而不是整只眼一起抖；不占追踪通道。
- **适用**：眼内分层（至少虹膜组与高光分开）；左右眼各一套。
- **产出**（每只眼）：
  - 派生参数：`ParamEyeJellyX/Y{L,R}`（虹膜组二维）、`ParamEyePupilPulse{L,R}`（瞳孔缩放）、`ParamEyeGlint1..k{L,R}`（每颗高光，或 X/Y 二维）、可选 `ParamEyeFrameJelly{L,R}`（整眼眶刚体小位移）。
  - 变形器：在视线 warp 之下、虹膜组之上插入 `DeformEyeJelly{L,R}`（5×5，JellyX × JellyY）；高光各自一层 warp；眼眶果冻 warp 包整只眼（只做刚体平移 + 2–3° 旋转，闭眼时不拉开上下眼线）。
  - 物理按"轻重分层"：慢链（虹膜，3–4 质点，输出取相邻质点 v1/v2 且一个取反 → 二维 warp 的两条轴，得到椭圆/李萨如轨迹）、中链（瞳孔，3 质点，加速度略大）、快链（高光，2 质点，最短最轻，阻尼低）；X 与 Y 两路链长比 1:1.5–1:2（互不整除，防同相）。
  - 输入：同侧 EyeOpen（X 型 50–100）[+ EyeSmile 15–25] [+ 眉 Y 20–35，瞳孔链可改为眉 Angle 型 100 得到持续情绪映射] [+ 呼吸 30–50 只入高光链，静止时也闪]。
- **算法**（幅度以 H_e 为单位，参数 ±1 满值；实际物理输出只到满值 20–40%）：
  - 虹膜 JellyX：水平平移 0.08 W_e 并 3% 横向挤压/剪切；JellyY：竖直平移 0.1 H_e 并以下缘为锚竖向缩放 ±8%。
  - 瞳孔：缩放 ±15%（以瞳孔中心）。
  - 高光 i：平移 0.05–0.25 H_e，按高光面积排序线性分配（越小越亮越靠内的层振幅越大），相邻高光方向错开 ≥ 45°；用虹膜椭圆裁剪位移（见 `eye.highlight/blink-wobble` 的软限位）。
  - 振幅随深度递增：`A_r = A_0·(1 + 3.5·d_r)`。
  - 闭眼时果冻形变按 Open 衰减（或依赖层级相乘）。
  - 标定：一次 0.15 s 眨眼后，虹膜峰值 ≤ 0.1 H_e、高光 ≤ 0.3 R_i，1 s 内衰减到 10% 以下；"眼睛完全静止"时的帧应取物理收敛态。
- **可调参数**：`irisAmp` 默认 0.1 H_e，范围 0.05–0.2；`pupilPulse` 默认 0.15，范围 0.1–0.2；`glintAmp` 默认 0.2 H_e，范围 0.1–0.35；`frameJelly` 默认 0（关），范围 0–0.13 H_e；`chainRatioXY` 默认 1.5；`inputs.smile` 默认 0；`inputs.brow` 默认 0；`inputs.breathGlint` 默认 0；`stereoJitter`（左右确定性微调）默认 0.05。
- **失败模式与检查**：输出过大"抽搐"；追踪在半睁附近抖动时眼睛一直晃（EyeOpen 0.05 死区或由眨眼速度驱动）；虹膜链 delay 高时拖尾；眼小（Q 版）必须按 H_e 计；左右参数必须镜像一致；高光必须被眼白剪切；眉输入噪声大时追踪端平滑。
- **引擎能力**：物理多链、多输出 v_k、reflect、二维 warp、混合形状（可选）。
- **难度 / 优先级**：medium / **P0**（动漫向高精度模型最常见的"灵气"手法；可从现有 `PhysicsEyeJelly` 逐步扩展，先做"虹膜二维 + 高光独立"，再加瞳孔与眼眶）。
- **差距**：`PhysicsEyeJelly` 两眼共用单输出 `ParamEyeBallForm`（只缩放虹膜），没有分元件、分频、高光、瞳孔、眉/笑输入。

### `eye.iris/pupil-scale` 独立的瞳孔/虹膜缩放层
- **目的**：瞳孔大小是强情绪信号（惊吓缩瞳、喜爱放大）；做成独立一层，对所有瞳孔贴图（含替换瞳孔）统一生效，可被多个表情按不同强度复用，不乘进开合或视线的关键形。
- **适用**：虹膜组。
- **产出**：参数 `ParamPupilScale{L,R}`（−1..1，默认 0；可左右共用 `ParamPupilScale`）；在视线层之内、形状透视层之外插入 `DeformPupilScale{L,R}`（以虹膜中心缩放）；替换瞳孔挂在其下；高光默认放在其外（不缩放）。可选"阈值驱动"：由挑眉驱动缩瞳——在虹膜组外包一层 BrowY warp，键 `{-1, 0, t, 1}`，0→t 几乎不变、t→1 虹膜缩 10–20%。
- **算法**：键 −1/0/1 → 缩放 0.75 / 1 / 1.15；表情预设：惊讶 −1、恐惧 −0.7、黑脸 −0.5、喜爱 +0.6。
- **可调参数**：`shrinkMin` 默认 0.75，范围 0.45–0.85（漫画式缩瞳可到 0.5）；`growMax` 默认 1.15，范围 1.1–1.2；`browThreshold` 默认关，开启时 t = 0.75。
- **失败模式与检查**：缩放过大时虹膜相对眼白比例失真；高光随之缩小显得假（放外层）。
- **引擎能力**：warp、表情预设。
- **难度 / 优先级**：easy / **P0**。
- **差距**：`ParamEyeBallForm` 只给物理用，不在表情预设中使用，也不包含替换瞳孔。

### `eye.iris/close-squash` 闭眼时虹膜压扁
- **目的**：闭眼时瞳孔随上睑纵向压扁、锚在下睑，避免虹膜像贴纸一样滑到眼睑后面。
- **适用**：虹膜组。
- **产出**：虹膜组内以 EyeOpen 为轴的 warp `DeformIrisClose{L,R}`（键与 `eye.lid` 相同）。
- **算法**：闭眼端纵向缩到 0.8，锚点在下睑；中间键按眼睑弧同步。
- **可调参数**：`squash` 默认 0.8，范围 0.75–0.9。
- **难度 / 优先级**：easy / P1。

### `eye.iris/inertia` 扫视惯性与瞳孔视差修正
- **目的**：扫视或转头后虹膜轻微过冲；瞳孔相对虹膜在视线偏向一侧时更贴近眼角、头转时朝镜头方向回正（球面感）。
- **适用**：视线输入来自追踪或待机动作。
- **产出**：派生参数 `ParamEyeBallInertiaX/Y`（物理：EyeBallX/Y（X 型）+ 头 AngleX/Y（Angle 型，60），短而活的摆，scale 小）→ 虹膜组平移 5–10% H_e；`ParamPupilCorrX/Y`（物理：−0.5·HeadX − 0.5·EyeBallX，过阻尼）→ 瞳孔层平移 0.1–0.15 虹膜径。
- **可调参数**：`inertiaAmp` 默认 0.07 H_e；`pupilCorr` 默认 0.12 虹膜径，范围 0.1–0.15。
- **失败模式与检查**：与 `eye.lid/gaze-follow` 的 lagged 选项叠加时眼区漂移；视线位移须 clamp 在虹膜内。
- **引擎能力**：物理（X/Angle 混合输入、过阻尼）。
- **难度 / 优先级**：medium / P2。

### `eye.iris/arc-path` 沿曲线运动的密键（豆豆眼/活动眼）
- **目的**：Live2D 键间只有线性插值，要让瞳孔沿眼眶走圆弧只能多放键；用网格键（只平移不自转）做"豆豆眼/活动眼"，由物理驱动后头一晃瞳孔沿眼眶滚动再落回底部。
- **适用**：Q 版豆豆眼、活动眼（googly eye）；也推广到指针、钟摆坠等沿路径运动的对象。
- **产出**：参数 `ParamEyeRoll`（−1..1）；键数 `K = ⌈2Φ/Δφ⌉ + 1`；物理：头 X/Y（X 型 50）+ 头 Z（Angle 型 100），轻小链。
- **算法**：键 k 处对象中心 = `c + R·(sinφ_k, cosφ_k)`，`φ_k = −Φ + kΔφ`，R = 眼眶半径 − 瞳孔半径；Δφ ≤ 18°（弦误差 ≤ 1.2% R）；可推广为沿路径弧长等分放键（复用变形路径烘焙）。
- **可调参数**：`arcSpan` Φ 默认 90°，范围 90–180；`keyStepDeg` 默认 15，范围 10–20。
- **失败模式与检查**：K 太大关键形膨胀；与其他轴相乘时键数成倍。
- **引擎能力**：多键、物理。
- **难度 / 优先级**：easy / P2。

### `eye.iris/color-palette` 乘算色瞳色调色板
- **目的**：观众/主播用三个滑块自由调瞳色，不为每种颜色准备贴图，保留原画明暗。
- **适用**：虹膜层（或其浅色复制件）；目标支持乘算色关键形。
- **产出**：开关 `ParamIrisTintOn` 与 `ParamIrisHue`（5–9 键）、`ParamIrisSat`（5 键）、`ParamIrisVal`（3 键）；叠层关键形只改乘算色，不改几何；叠层在开关为 0 时不透明度 0。
- **算法**：每键乘算色 = HSV→RGB(H_i, S_j, V_k)；RGB 线性插值使相邻色相间略灰 → 色环取更密的 H 键。
- **可调参数**：`hueKeys` 默认 7，范围 5–9；`valMin` 默认 0.5，范围 0.4–0.6。
- **引擎能力**：乘算色关键形。
- **难度 / 优先级**：easy / P2（与 `lighting` 协作）。

---

## 12. eye.highlight — 眼部高光（补充插槽）

按通用制作惯例，高光是独立的变形单元，与虹膜分开处理；PSD2Live 现在把高光与虹膜同 tag、同变形。本插槽要求先能区分高光（亮度高、面积小于虹膜 10% 的近白块，或用户标注），可进一步区分"镜面高光"（小、亮、白）与"漫反射/纹理高光"。

### `eye.highlight/current` 与虹膜同动（现有）
- **现状**：高光图层归 IRIDES，与虹膜共用 `DeformIrisPreserve → DeformEyeGaze` 与 `irisJellyGrid`。
- **难度 / 优先级**：— / P0 登记。

### `eye.highlight/parallax-layer` 高光独立视差层
- **目的**：高光是光源的反射，眼球转动/头部转向时它的位移与虹膜不同，读作有曲面的湿润眼球。
- **适用**：高光已从虹膜中区分出来。
- **产出**：每只眼的高光组挂在独立 warp `DeformEyeGlint{L,R}`（视线轴 3×3 + 头部 AngleX×AngleY 3×3，或两层），以眼白为蒙版；镜面高光可选择挂在虹膜形状透视层之外（只吃视线、闭眼和果冻）。
- **算法**：
  1. 视线：高光位移 = 虹膜视线位移 × (f_x, f_y)，大高光 (0.9, 0.8)、小高光 (0.9, 0.6)——比虹膜少移，像固定光源反射。
  2. 头部转向，两种互斥模式（`turnMode`）：
     - `light-fixed`：高光相对眼睛反向补偿虹膜位移的 50–70%（近似固定于光源方向）；可由物理平滑：AngleX/Y（Angle 型 reflect 60）+ EyeBallX/Y（50）+ EyeOpen（40）→ `ParamGlintParallaxX/Y`，2 节点轻延迟，±1 时平移 ±0.25 R_i；多颗高光按离虹膜中心越远移动越少（中心 1.0、边缘 0.6）。
     - `cornea-front`：高光在角膜表面、比虹膜靠前，头转时额外多移 β = 0.1–0.5 × 虹膜位移，方向与头转一致。
  3. 全部 clamp 在虹膜内（位置限制）+ 眼白蒙版（可见性）。
- **可调参数**：`gazeFactorX` 默认 0.9，范围 0.85–0.95；`gazeFactorY` 默认 0.7，范围 0.55–0.85；`turnMode` light-fixed|cornea-front，默认 light-fixed；`turnAmount` 默认 0.6（light-fixed 补偿比）/ 0.2（cornea-front 的 β），范围 0.3–0.7 / 0.1–0.5；`specularBypassShape` 默认 true。
- **失败模式与检查**：与视线跟随叠加后高光离开虹膜 → clamp；头部 X 的 reflect 方向必须渲染统一测试（light-fixed：头向右转，高光相对眼睛向左）；贴在虹膜边缘的高光被推出眼白。
- **引擎能力**：嵌套 warp、可选物理、蒙版。
- **难度 / 优先级**：easy / **P0**（`turnMode` 两种做法并存，默认 light-fixed，作为风格选项）。
- **差距**：瞳孔保持/视线有，但高光与虹膜同动；无头部转向的高光视差。

### `eye.highlight/lid-follow` 高光随眼睑下滑
- **目的**：眼半闭时高光留在原位会被眼白蒙版裁掉，眼睛立刻"没神"；让高光随睑下滑，细缝里依然有反光。
- **适用**：高光独立；与 `eye.lid/arc-blink` 配合。
- **产出**：高光组外套一个 EyeOpen warp（只承载高光，虹膜不受影响）。
- **算法**：EyeOpen = t_mid 时高光组下移 0.15–0.3 H_e 并纵向压到 70%；→ ε 继续压缩，随后整组隐藏（不透明度跟随睁眼组）。
- **可调参数**：`slide` 默认 0.2 H_e，范围 0.15–0.3；`squashAtMid` 默认 0.7。
- **难度 / 优先级**：easy / P1。

### `eye.highlight/blink-wobble` 高光绕虹膜中心摆动与软限位
- **目的**：眨眼后高光沿眼球表面弧线滑动（而非直线），并在大幅时贴住虹膜边不出界。
- **适用**：高光独立；作为 `eye.iris/jelly-layered` 高光链的几何实现选项。
- **产出**：每块高光一个旋转变形器 `RotEyeGlint<k>{L,R}`，轴心放在虹膜中心（不是高光中心），参数来自高光物理链；或二维平移 warp + 软限位中间键。
- **算法**：
  1. 旋转角 θ 按"沿虹膜边缘最多滑过的弧长 = 0.3 R_i"换算；向高光所在侧不对称（远离一侧取 0.75θ）。
  2. 软限位（平移实现）：在 +0.4 范围处加中间键，0.4 → 1 段只再前进 15%，以虹膜半径为上限。
- **可调参数**：`arcLen` 默认 0.3 R_i，范围 0.2–0.4；`asym` 默认 0.75；`softLimitKnee` 默认 0.4，范围 0.3–0.5。
- **失败模式与检查**：输出 scale 过大像抽搐；蒙版必须是眼白。
- **引擎能力**：旋转变形器、非均匀多键。
- **难度 / 优先级**：easy / P1。

### `eye.highlight/gate` 高光乘积门控
- **目的**：替换瞳孔、黑脸、空白眼等任一开启时原高光淡出（逻辑 NOR），不需要额外参数也不会互相打架。
- **适用**：存在任何会与高光冲突的开关。
- **产出**：高光组外包门控 warp `DeformEyeGlintGate{L,R}`，不透明度关键形为所有冲突开关的乘积格（只有全 0 角点为 1）；开关多于 3 个时级联门控 warp（每层 2 个开关）。
- **算法**：门控 warp 几何恒等，只有不透明度键；冲突开关列表由开启的模板自动登记（`replacement-pupil`、`gloom`、`symbol-mode`）。
- **可调参数**：`gateFadeBand` 默认 [0, 0.6]（开关到 0.6 时高光已为 0）。
- **失败模式与检查**：门控过多使高光在多种状态下消失 → 质量检查列出组合。
- **引擎能力**：多维不透明度关键形（乘积门控）。
- **难度 / 优先级**：easy / P1。

### `eye.highlight/sweep-loop` 高光扫光循环与闪烁
- **目的**："闪亮"情绪与视线无关：高光沿光照方向扫过虹膜；或以滤色通道闪烁，零额外纹理。
- **适用**：高光独立；动作中情绪节拍。
- **产出**：参数 `ParamEyeSparkle`（0..1，循环）；高光网格沿"虹膜左上 → 右下"路径 8–9 键，缩放与不透明度包络，加算混合；或 `ParamEyeGlintFlicker`（由 `jelly-layered` 快链驱动）控制高光与虹膜反光层的滤色（screen color 加 0.15–0.25 白）。
- **可调参数**：`sweepLen` 默认 0.8 虹膜径；`flickerStrength` 默认 0.2，范围 0.1–0.3。
- **失败模式与检查**：滤色在暗虹膜上发灰（按虹膜主色调整）。
- **引擎能力**：多键路径、加算混合、滤色关键形、循环参数。
- **难度 / 优先级**：easy–medium / P2。


---

## 13. brow — 眉

### `brow/current` 整体竖直平移（现有）
- **目的**：眉 Y 参数让眉整体上下平移。
- **适用**：EYEBROW 图层。
- **产出**：`ParamBrowL/RY{-1,0,1}`；`DeformBrowShape{L,R}`（3×3，九姿态）；网格关键形 `eyebrowGrid`：`dy = −value·h·browLift%`；Side.NONE 落到 `BrowRY`。透过刘海的复制件共用关键形与混合形状。
- **失败模式与检查**：无旋转、变形、X；眉与眼完全独立。
- **难度 / 优先级**：已实现 / P0 登记。

### `brow/lid-coupling` 眉驱动上睑（同一片皮肤）
- **目的**：挑眉时上睑被拉起、眼睛睁大；皱眉/倒八字时上睑外侧或内侧被压低、眼形变窄变凶。被刘海遮住的眉，其情绪转嫁到看得见的眼睛上。
- **适用**：眉与同侧眼都存在。
- **产出**：在眼组（上睑、睫毛、眼线、眼白、可选虹膜组）之外、`DeformEyeShape{L,R}` 之内插入 `DeformEyeBrowPull{L,R}`（lattice 5×(≥10)，覆盖眼包围盒并向上延伸到眉），轴为现有眉参数（`BrowY`，启用 `brow/shape-axes` 时再加 `BrowAngle`；`BrowForm` 另起一层）。目标支持混合形状时可改为在眼部 warp 上写同名混合形状（不与眼的开合键相乘）。
- **算法**：
  1. 关键形 = 眉在该键的位移 Δbrow(x)（同 x 处眉的竖向位移）× 衰减场 c(y)：眉线处 c = 1；上睑线处 `c_lid`；下睑处 ≈ 0；其间按竖直距离 smoothstep 插值。
  2. `c_lid`：Y 轴 0.4–0.8，Angle 轴 0.3–0.6（Angle 的转动支点用眉自己的支点，不用眼中心）。
  3. 被遮挡自适应：算眉被前发覆盖的像素比例 cov（静止姿态），`c_lid(Y) = 0.4 + 0.5·cov`（夹 0.4–0.95）——眉可见时取低值，不显得过度。
  4. 眼睛形变：BrowY = +1 上睑上移、眼裂纵向张开（整眼竖向 +5%）；−1 上睑压低 0.12–0.15 H_e、外眼角下压 0.05 H_e；Angle 让上睑内外侧不对称移动（怒/哀）。
- **可调参数**：`cLidY` 默认 0.6，范围 0.4–0.95；`cLidAngle` 默认 0.45，范围 0.3–0.6；`coverageBoost` 默认 0.5，范围 0–0.5；`useBlendShapes` 默认按目标能力自动。
- **失败模式与检查**：挑眉 + 闭眼组合时上睑不得穿过眉；耦合高时追踪噪声让整只眼跟着眉抖（追踪端加平滑，或配合 `brow/spring`）；透过刘海的眉/眼复制件必须共享同一变形（复制件共用父变形器即可满足）。
- **引擎能力**：嵌套 warp 或混合形状。
- **难度 / 优先级**：medium / **P0**。
- **差距**：现状眉与眼各自独立形变。

### `brow/blink-dip` 眉随眨眼（与睁大）联动
- **目的**：闭眼时眼轮匝肌把眉带下少许；睁大眼时眉抬起（惊讶）；但刻意避免"眨眼显得委屈"。
- **适用**：眉与同侧眼都存在。
- **产出**：在眉形变 warp 之外插入 `DeformBrowEyeFollow{L,R}`，轴 = 同侧 `EyeOpen`（键与 `eye.lid` 相同，含 Omax）[× `EyeSmile`]。模式：
  - `static`：直接关键形；
  - `physics`：派生参数 `ParamBrowBlinkDip{L,R}`，物理 EyeOpen（X 型 80–100）[+ EyeBallY 20] → 单节点长摆（慢而软，过阻尼），只在负侧打键（正侧为 0）；
  - `wide-raise-only`：派生参数 C = 弹簧(clamp((EyeOpen − 1)/(Omax − 1), 0, 1))，只在睁大时抬眉，眨眼不压眉。
- **算法**：
  1. EyeOpen = 0：眉整体下移 `dip·H_e`，内端 1.2×、外端 0.8×（或眼尾一端更多，按风格）。
  2. EyeOpen = Omax：眉上移 0.2 H_e，眉形略向外弧。
  3. EyeSmile = 1（闭眼行）：眉放松上移 0.05–0.1 H_e 并略弯（"眉开眼笑"）。
- **可调参数**：`dipMode` none|static|physics|wide-raise-only，默认 static；`dip` 默认 0.05 H_e，范围 0.02–0.2（physics 模式可到 0.15–0.25）；`innerOuterRatio` 默认 1.2，范围 1.0–1.3；`wideRaise` 默认 0.2 H_e，范围 0.1–0.25；`smileRelax` 默认 0.08 H_e。
- **失败模式与检查**：dip 过大显得委屈（static 模式 ≤ 0.06 H_e）；追踪 EyeOpen 映射偏大时 wide-raise 常态上扬（要求中性 ≈ 1）；眉被刘海遮挡时配合透过刘海显示。
- **引擎能力**：二维关键形；物理（可选）。
- **难度 / 优先级**：easy / **P0**（static 模式）；physics 与 wide-raise 选项 P1。
- **差距**：眉只受 BrowY 控制。

### `brow/shape-axes` 眉角度、眉形与 X
- **目的**：眉的情绪主要靠角度与形状而不是位置：位置参数幅度小，角度用密键逼近圆弧，眉形（笑/疑惑/怒）在沿笔画的窄长 warp 上组合。
- **适用**：EYEBROW 图层；可估计眉头、眉峰、眉尾三点与笔画中线。
- **产出**：参数 `ParamBrowL/RAngle`（±1）、`ParamBrowL/RForm`（±1，Cubism 标准名；或拆为单边形状参数 Smile/Puzzled/Angry 0..1）、`ParamBrowL/RX`（±1）；变形器（外 → 内）：眨眼联动 → 视线（可选）→ `BrowY × BrowX`（3×3 键，5×5 lattice）→ `BrowAngle`（密键，7×10 lattice）→ 形状 warp（3 行 × ≥ 15 列沿笔画）。
- **算法**：
  1. 位置：Y ±0.15 H_e、X ±0.06 W_e（小幅）。
  2. 角度：绕眉中点（或眉头）旋转 ±15°；凡角度变化 > 10° 的类旋转运动，插键使每段 ≤ 5°，每个键按绕支点的精确旋转计算（不是端点插值）。
  3. 形状：笑 = 中段上拱、两端下压；疑惑 = 一端上挑；怒 = 眉头下压、变陡；三形状用 2×2×2 键或混合形状组合；主表情量放在形状上（约 0.5 H_e），位置量小。
- **可调参数**：`posY` 默认 0.15 H_e；`posX` 默认 0.06 W_e；`angleDeg` 默认 15，范围 10–25；`angleStepDeg` 默认 5，范围 3–6；`formAmp` 默认 0.5 H_e，范围 0.3–0.6；`formStyle` bipolar|tri-shape，默认 bipolar。
- **失败模式与检查**：密键 × 其他轴相乘膨胀（角度层单独一层 warp，不与形状相乘）；透过刘海的眉复制件须继承形状与混合形状。
- **引擎能力**：多键、窄长 warp、混合形状（可选）。
- **难度 / 优先级**：medium / P1。
- **差距**：标准参数只有 BrowY。

### `brow/spring` 眉弹簧（欠阻尼回弹）
- **目的**：眉是表情最快的部位，"挑眉"有到位冲击；单摆欠阻尼把冲击做出来，眉动时眼睛也有"肉感"的延迟回弹。
- **适用**：启用 `brow/shape-axes` 或至少 BrowY。
- **产出**：每个眉输入参数 B 一个影子参数 `B2`（如 `ParamBrowLY2`）+ 单质点物理组（输入 B，Angle 型）；眉网格与（可选）眼区 warp 的形变改由 B2 驱动（B 本身零几何，或 B 直接驱动、B2 叠加过冲混合形状）。
- **算法**：物理 m 0.6–0.7、d 0.5–0.8、a 1.4–1.6，输出 scale = 范围 × 0.6；B2 的 ± 形 = 原眉关键形；眼区：B2 让整只眼（瞳孔栈 + 眼睑栈）沿眉运动方向小平移 ±0.2 H_e（混合形状）。校验：眉阶跃时 B2 过冲 10–20%，0.3 s 内稳定。
- **可调参数**：`overshoot` 默认 0.15，范围 0.05–0.25；`eyeCarry` 默认 0.1 H_e，范围 0.05–0.25。
- **失败模式与检查**：追踪高频抖动让眼睛也抖（降 scale 或追踪平滑）；表情动作若直接写 B2 会与物理叠加冲突（动作写 B）。
- **引擎能力**：物理、混合形状。
- **难度 / 优先级**：easy / P1。

### `brow/emotion-fanout` 物理当"混音台"：一个情绪输入扇出到眉、眼、瞳孔
- **目的**：追踪给的眉信号是一维的、弱且噪；真实眉动是复合的。用物理把 BrowY、MouthForm、MouthX 混合扇出到多个形状参数，并用第二节点输出做"先抬后弯"的延迟层次：笑带出笑眉，嘴角侧移带出疑惑眉，皱嘴带出怒眉和缩瞳。
- **适用**：启用 `brow/shape-axes`（形状参数）、`brow/lid-coupling` 的眼部怒形、`eye.iris/pupil-scale`。
- **产出**：原始输入参数（BrowY、MouthForm、MouthX）不直接绑定几何；派生参数 `BrowY2`、`BrowAngle2`、`BrowSmile`、`BrowPuzzled`、`BrowAngry`、`EyeAngry`、`PupilScale2`（每侧）；每侧一个物理组（3 质点，中间节点 m 0.6、尾节点 m 0.45，输入 Angle 型）。另可选"嘴 → 眉"延迟链：MouthForm → 摆 → 摆 → 摆 → `BrowSmileSad`（"哀笑眉"：眉内端上提、外端下垂，眼区略眯）。
- **算法**：
  1. 混合矩阵 M（行 = 输出，列 = 输入，权重为设计默认）：Smile ← BrowY 0.4 + MouthForm⁺ 0.3；Angry ← BrowY⁻ 0.4 + MouthForm⁻ 0.3；EyeAngry ← 同 Angry；Angle ← 0.3 BrowY + 0.3 MouthForm + 0.2 MouthX；Puzzled ← |MouthX|（取 v2，延迟一拍）；PupilScale ← −Angry（生气时缩瞳）。
  2. 形状参数单边（Smile、Puzzled 用 [0,1]，Angry 用 [−1,0]），物理正负方向自然分流。
  3. 每个动作一层 warp，顺序固定：（视线）→ BrowY2 → Angle（密键）→ Angry × 表情 → 形状。
  4. 左右各一组，MouthX 一侧 reflect 产生不对称。
  5. 验收：笑 → 眉成笑弧，中性回零；无抖动；延迟 < 150 ms。
- **可调参数**：`mouthMix` 默认 0.3，范围 0–0.4；`mouthXMix` 默认 0.2，范围 0–0.3；各输出权重；`delayedOutputs`（取 v2 的输出列表）；`sadSmileChain` 默认 false，级数 2–3。
- **失败模式与检查**：回落时过冲到对侧形状（Smile → 短暂 Angry）→ 尾节点 m 小、d ≈ 1；MouthForm 权重过大时说话让眉乱跳（≤ 0.3，物理本身也起平滑作用）；表情动作应写输入参数而非形状参数；原始追踪已提供眉形时降低嘴 → 眉权重。
- **引擎能力**：物理多输出（v1/v2）、派生参数链（物理级联）。
- **难度 / 优先级**：medium / P2（research：混合矩阵的默认值需出图调校）。
- **差距**：眉由 BrowY 直接驱动；五官间无耦合。

---

## 14. mouth.shape — 口型（开合、嘴形与放置）

M = 嘴宽 W_m；H_m = 全开口高度。现有约定"原画为完全张开"（`open=1, form=0` 为恒等）贯穿导出与中性校验，新模板若改用"原画闭合"须声明并由导出读取，而不是改常量。

### `mouth.shape/current` 3×3 嘴形 × 开合（现有）
- **目的**：嘴从张开原画压成闭合线，MouthForm 控制嘴角上下。
- **适用**：MOUTH / MOUTH_OPEN / MOUTH_CLOSE、牙、舌。
- **产出**：`MouthForm{-1,0,1} × MouthOpenY{0,0.5,1}`；`DeformMouthShape{side}`（4×3，九姿态）；`mouthWholePoint`（smoothstep 开度、闭合线 seam、`|nx|^1.55` 嘴角、闭合时 `MouthCurve` 预设 flat/smile/w/custom）；可选嘴描边（`MouthLipLayers` 唇线纹理 + `mouthContourMesh` 25×3 结构网格 + 唇丝带）；通道：闭嘴线 1−open、牙/舌在 `teethFade%` 内淡入。
- **失败模式与检查**：0.5 中间键由同一公式求值，中间态是"缩小的全张嘴"；只有一套闭合曲线。
- **难度 / 优先级**：已实现 / P0 登记（嘴型预设字符串在 6 处重复，注册表应收拢）。

### `mouth.shape/staged-open` 分阶段非线性开口
- **目的**：说话的音量大多落在小开口区间。嘴不是均匀张开：先唇缝出现（嘴角不动、中央先开的新月形）→ 嘴角拉开、口腔与牙出现 → 下唇与下颌下落成竖长大口。前段给足开口量，小音量也有清楚口型。
- **适用**：嘴图层（张开原画）；最好有口线、口腔、牙、舌分层。
- **产出**：MouthOpenY 键 `{0, 0.25, 0.5, 1}`（可选再加 0.75），网格与嘴 warp、口腔蒙版同一组键。
- **算法**（各键由分段规则直接求值，不由线性插值得到；相对 H_m、W_m）：
  - 0：闭合（现有）。
  - 0.25：嘴角不动，上唇不动，中央下唇下移 0.25 H_m（唇缝 ≈ 0.1 W_m），开口宽 ≈ 0.85–1.15 W_m（新月形）。
  - 0.5：开口 0.5–0.6 H_m 的椭圆/宽月牙，嘴角内收 5%，口腔层出现、上牙可见；可选嘴角上提 0.05 H_m。
  - 0.75（可选）：露出上牙与舌面。
  - 1：原画（开口 H_m，嘴宽 −10%，下巴配合 `face.contour/jaw-follow`）。
  - 开口高度曲线 h(t) 默认 {0, 0.3, 0.55, 1}·H_m（前段加量）；宽度 w(t) 中段最宽 {1, 1.15, 1.2, 1}·W_m（先横后纵）。
  - 自动调键（可选）：在均匀采样姿态上计算唇线曲率与开合面积变化率，在变化最急处插键。
- **可调参数**：`keys` 默认 [0, 0.25, 0.5, 1]；`hCurve` 默认 [0, 0.3, 0.55, 1]，h(0.25) 范围 0.25–0.4；`wPeak` 默认 1.2，范围 1.1–1.3；`crescentMid` 默认 0.65（中央开口比，0.25 键时中央开 60–70%、嘴角开 30%）。
- **失败模式与检查**：需要口线、口腔、牙、舌分层，否则中间键只能做整体形变；Form × Open 四个角逐一检查唇线不交叉；与描边唇丝带共用路径时丝带也要在新键上重建。
- **引擎能力**：非均匀多键。
- **难度 / 优先级**：medium / **P0**。
- **差距**：张嘴为 0/0.5/1 同一公式（smoothstep），中间态无阶段形状。

### `mouth.shape/closed-line-swap` 小开度瞬切闭口线
- **目的**：闭口时用干净线条件，稍一张口立刻换成"开口结构"（上下唇 + 口内），避免闭口线与开口唇在小开度时重叠发糊。
- **适用**：有闭口线图层（MOUTH_CLOSE）与开口嘴；或描边生成的唇线。
- **产出**：MouthOpenY 加键 ε；通道：闭口线 `{0:1, ε:0}` 并降到口内之下，开口件 `{0:0, ε:1}`。
- **算法**：ε 处开口件形状与 0 键几乎相同（口腔面积 ≈ 0、唇线与闭口线重合）；0.3 键 = 1 键开度的 55–65%，使 0 → 0.3 更陡。
- **可调参数**：`eps` 默认 0.05，范围 0.02–0.06；`stepAt03` 默认 0.6，范围 0.5–0.7。
- **失败模式与检查**：口型同步噪声在 ε 附近来回跳（宿主平滑或 ε 取 0.05）。
- **引擎能力**：非均匀多键、不透明度通道、绘制顺序键（可选）。
- **难度 / 优先级**：easy / P1（现有闭嘴线 1−open 线性交叉的替代版本）。

### `mouth.shape/form-midkeys` 嘴形中间键与超范围键
- **目的**：嘴形负侧从平线收成小圆"o"是非线性收缩（嘴角向中间聚、线条变弯），需要中间键；中性附近的小笑意与追踪抖动分开处理；可加夸张撇嘴键。
- **适用**：嘴图层。
- **产出**：MouthForm 键 `{-1[, -1.2], -0.8, -0.5, 0[, f₀], 1}`（负侧 2–3 个中间键、正侧只用端点）。
- **算法**：负侧嘴宽 {0.45, 0.55, 0.75, 1}·W_m（对应 −1, −0.8, −0.5, 0）；中线在 −0.5 前保持原曲率，−0.8 后变圆；f₀ = 0.2 处中性略带笑意，与追踪的小笑意分开；超范围 −1.2 嘴角再下压 30%。
- **可调参数**：`negKeys` 默认 [-0.8, -0.5]；`f0` 默认 0（关），范围 0.15–0.3；`overrangeNeg` 默认关。
- **失败模式与检查**：Form × Open 组合的关键形数增加（只在嘴网格与嘴 warp 上加，不扩到脸）。
- **引擎能力**：非均匀多键。
- **难度 / 优先级**：easy / P1。

### `mouth.shape/overrange-open` 张嘴超量程（喊叫）
- **目的**：0–1 是说话常用区间，1–Omax 是喊叫、大笑的夸张口型，露出更多舌与牙、嘴角外拉。
- **适用**：嘴图层张开原画足够大，或可外推。
- **产出**：MouthOpenY 范围 `[0, Omax]`；口型同步增益让大音量进入 1–Omax；导出宿主映射上限。
- **算法**：1 → Omax：开口继续增大 30–50%、嘴角外拉；下颌在 Omax 时为唇开行程 12–15%。
- **可调参数**：`openMax` 默认 1.5，范围 1.5–2；`hostMax` 默认 2.0。
- **失败模式与检查**：标准动作与追踪只给 0–1，需要增益或表情才能触发；与 Form 二维组合键数多；参数范围变化须走模板版本。
- **难度 / 优先级**：medium / P2。

### `mouth.shape/placement` 嘴的漫画式放置层
- **目的**：不加新画稿就能风格化：嘴偏向一侧（坏笑、咀嚼）、嘴大小夸张（喊叫变大、嘟嘴变小）、整嘴淡出；与嘴型正交，所有嘴型变体都能用。
- **适用**：嘴组。
- **产出**：嘴部链 = 头部透视 → `ParamMouthPosX/PosY` 平移 → `ParamMouthSize` 缩放 → `ParamMouthFade` 淡出 → 宽度 `ParamMouthWidth`（只动嘴角）→ 变体槽 → 开合 × 嘴形；平移/缩放可用旋转变形器（支点 = 嘴中心）实现。
- **算法**：平移 ±1 → 水平 ±0.2–0.5 W_m、竖直 ±0.1–0.3 W_m；缩放 0.65–1.15；宽度 ±15%。放置层在透视 warp 之上，偏移始终贴着脸面。
- **可调参数**：`posX` 默认 0.3 W_m，范围 0.2–0.5；`posY` 默认 0.15 W_m，范围 0.1–0.3；`sizeMin/Max` 默认 0.7/1.2；`widthAmp` 默认 0.15。
- **失败模式与检查**：与 `cheek/aux-spread` 的 MouthX 功能重叠：MouthX 带全脸联动（追踪向），placement 是纯嘴位移（表情/动作向），二者可并存但命名区分。
- **引擎能力**：旋转变形器、warp、组不透明度。
- **难度 / 优先级**：easy / P1。

### `mouth.shape/turn-perspective` 张开的嘴随头部角度的透视
- **目的**：张嘴侧头时口腔可见区域、唇角透视与牙弓都会变，只按头角变形闭口嘴做不到；侧脸张嘴不像贴片。
- **适用**：嘴组；口腔/牙分层更佳。
- **产出**：嘴组 warp 以 `AngleX{-30,0,30} × AngleY{-30,0,30} × MouthOpenY{0,1}`（或 × MouthForm 3 键）为键；唇角/唇线网格另加 AngleX × AngleY 3×3 键；嘴单独一层 AngleY warp（低头压扁下移、抬头拉高）。
- **算法**：张开 + 偏航时，口腔内容向远侧嘴角偏移 0.1–0.2 W_m 并压缩远侧；唇角按远侧压缩；只在 AngleX/Y 取 3 键以控制组合数。
- **可调参数**：`interiorShift` 默认 0.15 W_m，范围 0.1–0.2；`farCornerCompress` 默认 0.8。
- **失败模式与检查**：组合键数（3×3×2×3 = 54）——只给嘴组一个 warp。
- **引擎能力**：高维关键形。
- **难度 / 优先级**：medium / P1。

### `mouth.shape/lip-glue` 上下唇同拓扑与唇线-唇面胶合
- **目的**：闭口时上下唇线完全重合不出双线；多个形状叠加时嘴角不裂开、线与面对齐。
- **适用**：描边唇线或分层唇面。
- **产出**：上下唇线由同一闭口线复制（同顶点数、同拓扑）；相邻唇网格间自动胶水。
- **算法**：顶点距离 < 2 px 成对，强度 1，优先嘴角处 3–5 对。
- **可调参数**：`glueDistPx` 默认 2；`cornerPairs` 默认 4。
- **失败模式与检查**：胶水与描边丝带重建冲突（丝带每个关键形重建时保留配对 ID）。
- **引擎能力**：胶水。
- **难度 / 优先级**：easy / P1。

### `mouth.shape/press-axis` 抿嘴/嘟嘴第三轴
- **目的**：覆盖 Form × Open 表达不了的抿嘴、嘟嘴口型。
- **适用**：嘴分层。
- **产出**：`ParamMouthPressLipOpen{-1, 0, 1}` 作为嘴网格第三轴（或混合形状）。
- **算法**：Press −1 双唇压合、宽 −10%；+1 唇向外翻开。
- **可调参数**：`pressNarrow` 默认 0.1。
- **难度 / 优先级**：medium / P2（与 `mouth.vowel/arkit-factorized` 一起实现）。

### `mouth.shape/banded-lineart` 分带唇线（多套画稿）
- **目的**：闭口笑线、微张、大张的线稿（线粗、嘴角、下唇阴影）本质不同，从一张画变形像橡皮；分带切换 + 带内变形，既有手绘质感又保持连续。
- **适用**：在 Open × Form 网格节点（如 3×3）上有或可生成 M 张线稿。
- **产出**：每张线稿一个网格，不透明度为以节点为中心、宽度 1 格的帐篷函数（任一点最多 2–4 张重叠）；每张在其邻域建变形关键形，使带边界处与相邻线稿轮廓对齐。
- **可调参数**：`grid` 默认 3×3；`crossFade` 默认 0.25。
- **失败模式与检查**：半透明交叉区双线（带边界前先形变对齐）。
- **引擎能力**：不透明度二维关键形。
- **难度 / 优先级**：hard（素材）/ P2（research）。

### `mouth.shape/expression-families` 表情家族张口组
- **目的**：任何表情下都要能说话；按"家族"共享张口组，既统一口型风格，又避免每个表情一套画。
- **适用**：多个表情各有闭口线（差分图层）。
- **产出**：每个家族一个组变形器：不透明度 = 家族成员表情激活 × (MouthOpenY > ε)；组内唇线、口腔、牙、舌，键 `MouthOpenY{0, ε, 0.3, 1} × MouthForm{-1,0,1}`；闭口线组不透明度 = 家族激活 × (MouthOpenY < ε)。
- **算法**：没有张口画的表情按闭口线特征（口角上扬/下垂、曲率）聚类到最近家族，口角方向作为硬约束；共用闭口线的不同表情写相对中性口的形变键；口部网格以脸皮为蒙版。
- **可调参数**：`families` 默认 3，范围 3–5；`eps` 默认 0.01。
- **引擎能力**：乘积门控、多键。
- **难度 / 优先级**：medium / P2。

---

## 15. mouth.vowel — 元音口型与面捕扩展

### `mouth.vowel/current` 无（现有）
- **现状**：只有 MouthForm 与 MouthOpenY；口型同步只驱动 OpenY。
- **难度 / 优先级**：— / P0 登记。

### `mouth.vowel/aiueo-blend` 五元音混合形状
- **目的**：音频口型同步给 A/I/U/E/O；元音做成加法层叠在 Open × Form 基础键上，仍可与笑/撇嘴叠加。
- **适用**：嘴分层；特征点：嘴角、上下唇中点、唇厚。
- **产出**：混合形状参数 `ParamMouthA/I/U/E/O`（0..1）挂在嘴组各网格；口型同步（导出 LipSync 组）可选择驱动这五个参数。
- **算法**：形状表（以 W_m、开口 M = H_m 为单位），用特征点做 TPS 拟合转移：A 开口 1.0M、宽 +0；I 开口 0.25M、宽 +0.25 W_m、嘴角略上；U 开口 0.3M、宽 −0.35 W_m、唇外凸变圆；E 开口 0.5M、宽 +0.1 W_m；O 开口 0.8M、宽 −0.2 W_m、变圆。
- **可调参数**：`vowelTable`（每元音的开口、宽度、圆度）。
- **失败模式与检查**：五个同时非零时唇交叉（口型同步通常一次只给一个主元音）；目标 < Cubism 5 时烘焙为 5 个独立键（与 Open 不相乘）。
- **引擎能力**：混合形状。
- **难度 / 优先级**：medium / P1。

### `mouth.vowel/dual-system` 完美同步嘴与元音嘴两套系统
- **目的**：摄像头完美同步需要逐块肌肉的形状，音频口型只给元音；单套嘴难以兼顾时，复制两套嘴放在同一父 warp 下，用一个参数切换。
- **适用**：同时面向面捕与音频口型。
- **产出**：嘴部图层集复制两份（共用纹理、同一父 warp），参数 `ParamMouthSystem`（0 = 面捕，1 = 元音）作用在两组的组级不透明度上；Form 默认值取原画表情（通常浅笑 0.3–0.5）。
- **可调参数**：`defaultForm` 默认 0，范围 0–0.5。
- **失败模式与检查**：切换中间值双嘴（用错开交叉淡化，见 `toggle`）。
- **难度 / 优先级**：medium / P2。

### `mouth.vowel/arkit-factorized` 因子化嘴部 warp 栈（面捕扩展）
- **目的**：ARKit/VBridger 类面捕的嘴部动作彼此组合频繁，一张 N 维关键形网格不可做；把每个动作单元放在独立 warp 层层嵌套，变形自动复合；每层只多带一个"张嘴量"轴处理交互；键数 = Σ 而不是 Π。
- **适用**：面捕目标；嘴分层完整。
- **产出**：参数集（按需开启）：`ParamJawOpen`、`ParamMouthX`、`ParamJawX`、`ParamCheekPuff`、`ParamMouthShrug`、`ParamMouthPuckerWiden`、`ParamMouthFunnel`、`ParamMouthPressLipOpen`、`ParamTongueOut`；层级（外 → 内）：
  1. 面部级 `JawX × MouthX`、`OpenY × CheekPuff`（覆盖下半脸，lattice ≥ 30×20，使轮廓随动；由 `cheek/aux-spread` 或 `cheek/global-field` 提供）；
  2. 嘴局部 `JawOpen`；
  3. `MouthX × OpenY`（MouthX 沿脸面弧线平移，多键）；
  4. `PuckerWiden × OpenY`；
  5. `OpenY × Shrug`；
  6. 嘴角与闭合层：Smile/Frown L/R、MouthClose（下唇上移，行程等于 OpenY 行程，与 JawOpen 组合出"闭唇张颌" mmm）；
  7. 混合形状：PressLipOpen、Funnel、Roll；
  8. 唇网格 `OpenY × Form`（`mouth.shape` 的基础键）。
  TongueOut 可作为各层的附加轴，保证舌伸出时嘴型张开。
- **算法**：各动作形状由口型几何模板生成：JawOpen 下唇与下颌整体下移、嘴角内收；Pucker 唇向中心收拢 20–30% W_m 并前凸；Widen 嘴角外拉；Shrug 下唇上推；Funnel 唇圈外翻呈 O 形；交互轴统一用 OpenY 三键。层序规则：侧移/下颌放外层，局部形状放内层（侧移放内层会被外层张嘴拉歪）。导出宿主映射表（ARKit 名 → 参数）。
- **可调参数**：`enabledUnits`（动作单元子集）；各动作幅度（相对 W_m 0.1–0.35）；`faceLatticeDensity` 默认 30×20。
- **失败模式与检查**：层序决定复合方式；键不要超出参数范围；未接面捕时参数全为 0 不影响基本口型；目标 < Cubism 5 时混合形状须烘焙或丢弃并报告。
- **引擎能力**：嵌套 warp、混合形状、宿主映射导出。
- **难度 / 优先级**：hard / P1（分期：先 JawOpen + MouthX + CheekPuff + PuckerWiden 四个，再加其余）。
- **差距**：只有 MouthForm 和 MouthOpenY 两个嘴参数。

---

## 16. mouth.interior — 口内（牙、舌、口腔）

### `mouth.interior/current` 嘴剪切 + 小开口淡入（现有）
- **目的**：牙/舌被最近的同侧嘴剪切，在 `teethFade%`（15%）内从 0 升到 1；牙始终用矩形网格；排序在嘴之前。
- **难度 / 优先级**：已实现 / P0 登记。

### `mouth.interior/depth-parallax` 口内深度视差
- **目的**：口腔凹进脸里，转头时牙和舌相对唇线应"落后"一点，张开的嘴有立体感。
- **适用**：牙、舌、口腔阴影分层，被口腔蒙版剪切。
- **产出**：在嘴型 warp 之内为口内件加一层 AngleX × AngleY warp `DeformMouthInteriorDepth`（5×5，3×3 键）。
- **算法**：平移 `Δ = −d·(sinθx, −sinθy)`，深度 d：舌 0.25–0.35 W_m、下牙 0.1 W_m、口腔阴影 0.3 W_m；可同时沿转向压缩 cosθ；方向与唇线相反（越深越反向）。
- **可调参数**：`depth.tongue` 默认 0.3 W_m；`depth.teeth` 默认 0.1 W_m；`depth.shade` 默认 0.3 W_m；整体系数默认 1，范围 0.5–1.5。
- **失败模式与检查**：内容必须在口腔蒙版之内，平移后检查不出界。
- **引擎能力**：嵌套 warp。
- **难度 / 优先级**：easy / P1。

### `mouth.interior/open-shading` 口内明暗与层次随开口
- **目的**：口内暗层随开口变化模拟口腔深处明暗；口内件在中段临时上提绘制顺序，避免被唇线压住。
- **适用**：口内暗层/口腔层；牙、舌根。
- **产出**：口内暗层不透明度以 MouthOpenY（与 MouthForm）为轴；口内件绘制顺序键在 0.25–0.5 区间上提、张满后回原层。
- **算法**：开口小时较浅，开口大时 +10%；撇嘴超范围时略加深。
- **可调参数**：`shadeGain` 默认 0.1，范围 0.05–0.2；`orderBumpBand` 默认 [0.25, 0.5]。
- **引擎能力**：不透明度通道、绘制顺序关键形。
- **难度 / 优先级**：easy / P1。

### `mouth.interior/tongue-handoff` 吐舌：口内舌与口外舌错开交接
- **目的**：舌头伸出口外时需要从"被口腔裁切"切换到"画在嘴外"；用正、反两种蒙版加错开区间，不切换绘制顺序，也不会出现两根舌头。
- **适用**：舌图层；口腔蒙版。
- **产出**：参数 `ParamTongueOut`（0..1）；舌复制成两份：A（口腔蒙版）、B（口腔反向蒙版，可加长）；不透明度：A 在 [0, 0.6] 从 1 降到 0，B 在 [0.4, 1] 从 0 升到 1；B 的几何在 [0.4, 1] 内从唇缘向外伸出 0.3–0.5 H_m；B 的不透明度再乘以 MouthOpenY 门控（闭嘴时口腔蒙版为空，B 会完全露出）。
- **可调参数**：`handoffBand` 默认 [0.4, 0.6]；`outLength` 默认 0.4 H_m，范围 0.3–0.5。
- **失败模式与检查**：门控缺失时闭嘴露舌；运行时无反向蒙版时退化为 `tongue-mask-expand`。
- **引擎能力**：蒙版、反向蒙版、乘积门控。
- **难度 / 优先级**：easy / P1。

### `mouth.interior/tongue-mask-expand` 吐舌：分阶段 + 蒙版局部扩张 + 绘制顺序
- **目的**：舌头始终有干净边界：口内时被口腔裁剪，伸出时蒙版随同一参数在靠近舌的局部边外推，舌越过唇线时提升绘制顺序。
- **适用**：舌图层；口腔蒙版为独立网格（不显示或与口内底色同形）；目标不支持反向蒙版时也可用。
- **产出**：`ParamTongueOut` 键 `{0, t₁, t₂, 1}`；舌的绘制顺序在 t₂ 后提到下唇之上；口腔蒙版网格以 `TongueOut × MouthOpenY × MouthForm` 为键（局部边外推）；可选舌父 warp 四向混合形状（±0.2 W_m）。
- **算法**：[0, t₁] 不动；[t₁, t₂] 在口内移动到唇边；[t₂, 1] 伸出 0.5 W_m 并提层；蒙版只扩张靠近舌的局部边（舌在该键的凸包再膨胀 2 px），其他边不变（整体扩张会露出本应被唇遮住的口内件）。
- **可调参数**：`t1` 默认 0.5；`t2` 默认 0.75；`maskPadPx` 默认 2。
- **引擎能力**：蒙版网格关键形、绘制顺序关键形、混合形状（可选）。
- **难度 / 优先级**：medium / P2。

### `mouth.interior/insert-mask` 进入嘴里的物体（食物、吸管、叼着的道具）
- **目的**：进入开口的物体必须在唇线处消失、夹在唇间，并跟着下颌、嘴唇与头部角度动。
- **适用**：标注为"嘴部道具"或"可插入物"的图层（食物、吸管、棒棒糖、手指）。
- **产出**：可插入物挂在嘴部开合/嘴形 warp 之后（`DeformMouthShape` 子级）；蒙版 = 口腔 ∪ 嘴外区域（或拆为嘴外段与嘴内段，嘴内段只用口腔蒙版；或以口腔与下唇反向蒙版）；开口多边形可由内唇轮廓合成并键控 Open × Form；一个开关参数控不透明度，表情文件设置它；进食参数驱动物体向嘴中心平移并缩放 0.9。
- **可调参数**：`maskPadPx` 默认 1，范围 1–2；`attachMode` split|inverse-mask|draw-order。
- **失败模式与检查**：蒙版数量上限；外露段与嘴内段在唇线处接缝。
- **引擎能力**：蒙版、反向蒙版、组不透明度。
- **难度 / 优先级**：medium / P2（与 `prop` 协作）。

### `mouth.interior/teeth-arc` 牙弓分段跟随
- **目的**：张嘴侧头时牙弓透视变化；牙齿按弧拆段，每段一个 warp 跟随弧形，而不是一整块矩形。
- **适用**：上/下牙图层宽度足够。
- **产出**：牙按弧等分 3–6 段，每段一个小 warp，键随 `turn-perspective` 与 `depth-parallax`。
- **可调参数**：`segments` 默认 4，范围 3–6。
- **难度 / 优先级**：medium / P2。

