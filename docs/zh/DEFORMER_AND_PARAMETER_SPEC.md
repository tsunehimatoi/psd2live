# PSD2Live 变形器层级、算法数学原理与参数系统规范 (Deformer & Math Specification)

[English](../en/DEFORMER_AND_PARAMETER_SPEC.md) | [日本語](../ja/DEFORMER_AND_PARAMETER_SPEC.md)

本文档面向对 Live2D 绑定架构、形变插值算法与物理动力学感兴趣的开发者和技术美术（Technical Artists），详细阐述 PSD2Live 的变形器拓扑树、九轴面部经纬网数学模型、五官二维修形算法、物理摆锤系统以及完整性自检规范。

---

## 目录

- [变形器拓扑与坐标空间体系](#变形器拓扑与坐标空间体系)
  - [1. 变形器树形拓扑结构](#1-变形器树形拓扑结构)
  - [2. 坐标空间与归一化变换](#2-坐标空间与归一化变换)
- [九轴面部经纬网数学模型 (Nine-Pose Face Rig)](#九轴面部经纬网数学模型-nine-pose-face-rig)
  - [1. 轴向定义与关键帧](#1-轴向定义与关键帧)
  - [2. 水平 C1 连续分段透视曲线 (Horizontal Roll Profile)](#2-水平-c1-连续分段透视曲线-horizontal-roll-profile)
  - [3. 垂直 V / ^ 仰俯曲率 (Vertical Pitch Curvature)](#3-垂直-v---仰俯曲率-vertical-pitch-curvature)
  - [4. 四角斜角交叉修正项 (Corner Interaction $C_{xy}$)](#4-四角斜角交叉修正项-corner-interaction-c_xy)
- [五官二次解耦修形算法 (Secondary Feature Warps)](#五官二次解耦修形算法-secondary-feature-warps)
  - [1. 空间深度标尺 (Perceived Depth Ruler)](#1-空间深度标尺-perceived-depth-ruler)
  - [2. 眼睛与透视平面约束 (Eye & Brow Plane)](#2-眼睛与透视平面约束-eye--brow-plane)
  - [3. 睫毛 Alpha 权重中线弯曲与闭眼算法](#3-睫毛-alpha-权重中线弯曲与闭眼算法)
  - [4. 嘴部柱面曲线与向心压缩闭口算法](#4-嘴部柱面曲线与向心压缩闭口算法)
  - [5. 耳朵透视渐隐与退行 (Ear Occlusion)](#5-耳朵透视渐隐与退行-ear-occlusion)
- [身体动作与呼吸变形器数学原理](#身体动作与呼吸变形器数学原理)
  - [1. 身体水平偏航 (BodyAngleX 纵向钟形包络)](#1-身体水平偏航-bodyanglex-纵向钟形包络)
  - [2. 身体垂直仰俯 (BodyAngleY S 形纬线重排)](#2-身体垂直仰俯-bodyangley-s-形纬线重排)
  - [3. 身体倾斜与胸腔呼吸膨胀 (BodyAngleZ & Breath)](#3-身体倾斜与胸腔呼吸膨胀-bodyanglez--breath)
- [物理动力学系统 (Physics & Dynamics)](#物理动力学系统-physics--dynamics)
  - [1. 头发多摆物理摆锤与发根固定 (Hair Pendulums)](#1-头发多摆物理摆锤与发根固定-hair-pendulums)
  - [2. 眨眼驱动的果冻眼动力学 (Blink Eye Jelly Dynamics)](#2-眨眼驱动的果冻眼动力学-blink-eye-jelly-dynamics)
- [Cubism 标准参数清单与映射表](#cubism-标准参数清单与映射表)
- [自动化几何与对称性审计 (Integrity Validation)](#自动化几何与对称性审计-integrity-validation)

---

## 变形器拓扑与坐标空间体系

### 1. 变形器树形拓扑结构

传统自动绑定工具往往将所有头部部件直接挂载在面部网格下，导致头发、头饰在头部转动时产生被面部拉扯挤压的异常失真。PSD2Live 将**“头壳跟随 (Skull Follow)”**与**“面部表面 (Facial Surface)”**进行彻底解耦：

```text
Root (Canvas Space)
 └─ DeformBodyXY [Warp 4×6] (ParamBodyAngleX, ParamBodyAngleY)
     └─ DeformBodyZBreath [Warp 4×6] (ParamBodyAngleZ, ParamBreath)
         └─ DeformHeadRotation [Rotation] (ParamAngleZ)
             └─ DeformHeadContainer [Warp 4×5] (ParamAngleX, ParamAngleY 头壳小幅跟随)
                 ├─ DeformFaceNinePose [Warp 8×8] (ParamAngleX, ParamAngleY 九轴强形变)
                 │   ├─ DeformEyeShapeL / R [Warp 4×3] (眼部透视修形)
                 │   │   └─ DeformIrisPreserveL / R [Warp 4×3] (瞳孔图形保持)
                 │   │       └─ DeformEyeGazeL / R [Warp 2×2] (ParamEyeBallX, ParamEyeBallY 视线追踪)
                 │   ├─ DeformBrowShapeL / R [Warp 3×3] (眉毛透视修形)
                 │   ├─ DeformNoseShape [Warp 3×4] (鼻子深度立体修形)
                 │   ├─ DeformMouthShape [Warp 4×3] (嘴巴柱面透视修形)
                 │   ├─ DeformEarOcclusionL / R [Warp 3×3] (耳朵遮挡渐隐)
                 │   └─ FaceDetails & Head Accessories (面部细节与头饰画元)
                 ├─ DeformHairFrontFollow [Warp 3×4] (前发头壳视差跟随)
                 │   └─ DeformHairFrontPhysics [Warp 3×4] (ParamHairFront 发根固定+发梢多摆)
                 ├─ DeformHairBackFollow [Warp 3×4] (后发头壳视差跟随)
                 │   └─ DeformHairBackPhysics [Warp 3×6] (ParamHairBack 发根固定+发梢多摆)
                 └─ HeadAccessories (头饰与未识别头部画元)
```

### 2. 坐标空间与归一化变换

- **画布空间 (Canvas Space)**：原点位于画布左上角，X 轴向右、Y 轴向下。根变形器 `DeformBodyXY` 的控制点直接位于画布绝对像素空间。
- **归一化局部空间 (Normalized Local Space)**：父 Warp 内的子 Warp 与画元 rest mesh 使用父节点的归一化包围盒坐标 $[0, 1] \times [0, 1]$。
- **旋转枢轴空间 (Rotation Pivot Space)**：`DeformHeadRotation` 的枢轴位于身体顶部的归一化局部空间，其唯一的直接子节点 `DeformHeadContainer` 采用以该枢轴为原点的像素偏移空间，其所有后续子节点均回归规范化局部空间。
- **CMO3 / MOC3 导出一致性**：在写入 CMO3 前通过 `restMeshesToCanvasSpace` 变换基准网格，使得可编辑基准网格处于画布空间，而关键形态差值绝对量依然维持父空间约束，确保在 Live2D Cubism Modeler 中完全无缝二次编辑。

---

## 九轴面部经纬网数学模型 (Nine-Pose Face Rig)

PSD2Live 采用基于手工绑定理念的 **$8 \times 8$ 面部经纬网模型**：

### 1. 轴向定义与关键帧
- **水平偏航轴**：`ParamAngleX` $\in [-45^\circ, 0^\circ, +45^\circ]$
- **垂直俯仰轴**：`ParamAngleY` $\in [-30^\circ, 0^\circ, +30^\circ]$
- **九个标准关键姿态**：
  $$\{ \text{AngleX}_{-45}, \text{AngleX}_0, \text{AngleX}_{+45} \} \times \{ \text{AngleY}_{-30}, \text{AngleY}_0, \text{AngleY}_{+30} \}$$

> [!NOTE]
> **初始头部倾角与基准轴向对齐**：
> 系统通过双眼水平线与面部垂直特征自动估算原画中头部的初始倾斜角 $\text{initialAngleZ}$，并将旋转变形器（`DeformHeadRotation`）的基准轴心与其对齐。九轴经纬网与平面旋转（`ParamAngleZ` $\in [-30^\circ, +30^\circ]$）均在以该初始倾角为中立基准的局部对齐坐标系（`HeadCoordinateSpace`）中计算与展开。若原画立绘头部自带自然倾斜，转动范围将以此倾斜角度为中立原点展开，不会强行归正至垂直。

### 2. 水平 C1 连续分段透视曲线 (Horizontal Roll Profile)

当头部向一侧偏航时（如右偏航 $+X$）：
- 近侧（靠近视线中心侧）轮廓做短距离平移展开；
- 近眼区域维持**宽平台（Broad Plateau）**，仅做位移而几乎不改变眼睛宽度，保护角色神态识别度；
- 远侧区域沿平滑步进曲线（$C^1$ 连续的 smoothstep）连续压缩，呈现真实自然的透视递减。

数学公式：
设面部水平归一化坐标为 $x \in [-1, 1]$，偏航强度为 $\text{yaw} = \tanh(\text{AngleX} / 32) \cdot \text{strength}$，方向为 $d = \operatorname{sgn}(\text{yaw})$，定向坐标为 $x_{\text{dir}} = x \cdot d$：

$$\operatorname{Roll}(x_{\text{dir}}) = \begin{cases} 
S\left(\frac{x_{\text{dir}} + 1}{-0.72 + 1}\right), & x_{\text{dir}} < -0.72 \\
1.0, & -0.72 \le x_{\text{dir}} \le 0.08 \\
1.0 - S\left(\frac{x_{\text{dir}} - 0.08}{1.0 - 0.08}\right), & x_{\text{dir}} > 0.08
\end{cases}$$

其中平滑步进函数为：$S(t) = t^2 (3 - 2t)$。

### 3. 垂直 V / ^ 仰俯曲率 (Vertical Pitch Curvature)

- **低头 ($\text{AngleY} < 0$)**：面部经线向中下部收拢，眼线、鼻线、嘴线间距压缩，脸颊下颌线形成明显的 **$V$ 字形包裹曲率**；
- **抬头 ($\text{AngleY} > 0$)**：面部经线向上发散，额头向后压缩，五官向外展开形成 **$\wedge$ 字形仰视曲率**。

$$\Delta y_{\text{pitch}} = -\text{pitch} \cdot R_y \cdot 0.018 + \text{pitch} \cdot y \cdot R_y \cdot 0.062 \cdot (0.78 + 0.22 \cdot \text{arch}_x) - \text{pitch} \cdot \text{arch}_x \cdot R_y \cdot 0.040$$

其中拱顶包络为 $\text{arch}_x = \max(0, 1 - x^2)^{1.30}$。

### 4. 四角斜角交叉修正项 (Corner Interaction $C_{xy}$)

在斜向极限姿态（如右上方 $\text{AngleX}=+45, \text{AngleY}=+30$）下，引入带符号的交叉项 $C_{xy} = \text{yaw} \cdot \text{pitch}$：

$$\Delta x_{\text{corner}} = C_{xy} \cdot R_x \cdot \text{arch}_x \cdot (0.012 + 0.018 \cdot y_{\text{lower}})$$
$$\Delta y_{\text{corner}} = C_{xy} \cdot R_y \cdot x \cdot \text{arch}_x \cdot 0.028 - |\text{yaw}| \cdot \text{pitch} \cdot R_y \cdot \text{arch}_x \cdot (0.007 + 0.008 \cdot y_{\text{lower}})$$

该修正项有效稳定了远侧脸颊结构，使 $V/\wedge$ 曲率与透视中心轴始终保持严格对齐。

---

## 五官二次解耦修形算法 (Secondary Feature Warps)

五官在跟随面部大 Warp 运动后，脱离面部表面做基于感知深度与二维图形保持率的二次修形：

### 1. 空间深度标尺 (Perceived Depth Ruler)
各部件拥有独立的空间感知深度系数：
$$\text{Depth}(\text{Nose Tip}) > \text{Depth}(\text{Nose Bridge}) > \text{Depth}(\text{Mouth}) > \text{Depth}(\text{Eye}) > \text{Depth}(\text{Face Surface}) > \text{Depth}(\text{Ear})$$

### 2. 眼睛与透视平面约束 (Eye & Brow Plane)
- 左右眼与眉毛共享一条透视斜率约束线：
  $$\text{Slope}_{\text{proj}} = \text{yaw} \cdot \text{pitch} \cdot 0.050$$
  在 $\text{AngleY}=0$ 时该斜率严格为 0；在四角姿态下，双眼中心连线与各眼睛自身的左右边界始终保持严格平行。
- **近/远眼宽度差异**：
  近眼宽度保持微幅展开（$1.0 + 0.012 \cdot |\text{yaw}|^{1.35}$），远眼做受控透视压缩（$1.0 - 0.085 \cdot |\text{yaw}|^{1.35}$）。
- **虹膜防挤压保护 (`IrisPreserve`)**：
  虹膜/瞳孔在远侧做反向位置补偿，防止瞳孔贴在眼眶边缘产生斜视错觉。

### 3. 睫毛 Alpha 权重中线弯曲与闭眼算法
1. 提取每列像素的重心高度 $Y_{\text{center}}(x) = \frac{\sum y \cdot \alpha(x,y)}{\sum \alpha(x,y)}$；
2. 计算与眼白包围盒下沉深度相吻合的目标闭眼 $U$ 形曲线：
   $$Y_{\text{closed}}(x) = Y_{\text{edge}} + \max(1.5, H_{\text{white}} \cdot 0.38) \cdot (1 - \hat{x}^2)$$
3. 睫毛网格顶点在闭眼时沿 $Y_{\text{closed}}$ 排布，纵向仅保持 $88\%$ 厚度微幅变细，形成弧形闭眼线。

### 4. 嘴部柱面曲线与向心压缩闭口算法
- `mouth` 图层作为最大张口原图（$\text{ParamMouthOpenY}=1$）；
- 当 $\text{ParamMouthOpenY} \to 0$ 时，采用平滑过渡向面部中线缝挤压：
  $$\text{Seam}_Y = Y_{\text{top}} + H_{\text{mouth}} \cdot 0.48$$
  $$\text{Scale}_Y = \text{Scale}_{\text{closed}} + \text{open}^2(3 - 2\text{open}) \cdot (1 - \text{Scale}_{\text{closed}})$$
  其中 $\text{Scale}_{\text{closed}} = \operatorname{clamp}(1.25 / H_{\text{mouth}}, 0.018, 0.12)$。
- `ParamMouthForm` 负责嘴角抬升（微笑）与嘴角下压（悲伤），并沿嘴角向内施加曲率加权。

### 5. 耳朵透视渐隐与退行 (Ear Occlusion)
- 耳朵具有负感知深度，在头部转动时向轮廓后方退行；
- 当偏航角度使耳朵处于远侧时，其不透明度通道通过 `ChannelGrids.OPACITY` 随 $\text{AngleX}$ 平滑淡出至最低约 $52\%$，还原耳部被头壳透视遮挡的效果。

---

## 身体动作与呼吸变形器数学原理

> [!IMPORTANT]
> **身体正立假设与姿态限制**：
> 身体偏航、俯仰与呼吸膨胀算法均建立在垂直画布坐标轴向的解耦几何模型之上（例如呼吸起伏严格沿躯干垂直归一化高度 $v \approx 0.42$ 展开）。因此立绘原画中角色躯干必须保持基本垂直正立；过于倾斜、横躺或大幅度倒伏的躯干会导致投影空间横向扭曲，引发呼吸错位与网格撕裂，不受系统支持。

### 1. 身体水平偏航 (BodyAngleX 纵向钟形包络)
- 采用纵向半正弦钟形包络 $\sin(\pi v)$ 约束躯干中间位移；
- 内部采用端点为零的拱形曲线 $4u(1-u)$ 表达空间偏转 Roll，**每行轮廓总宽度保持严格恒定**，正负偏角严格镜像对称。

### 2. 身体垂直仰俯 (BodyAngleY S 形纬线重排)
- 采用双端点与中点皆为零的 $S$ 形函数 $\sin(2\pi v)$ 重排内部网格纬线密度；
- 身体**总高度保持严格恒定**。

### 3. 身体倾斜与胸腔呼吸膨胀 (BodyAngleZ & Breath)
- `ParamBreath` 采用高斯钟形权重集中于胸腔区域（$v \approx 0.42$）：
  $$\text{Chest}(v) = \exp\left(-\frac{(v - 0.42)^2}{0.035}\right)$$
  实现胸部起伏与横向微动。

---

## 物理动力学系统 (Physics & Dynamics)

### 1. 头发多摆物理摆锤与发根固定 (Hair Pendulums)
- **多摆结构**：
  - 前发短摆（长度 3.0，延迟 0.9，输出倍率 1.522）；
  - 后发长摆（长度 15.0，延迟 0.8，输出倍率 2.061）；
- **发根与发梢形变**：
  - 发根行网格严格锁定，摆动幅度随高度深度采用 $v^3$ 立方递增：
    $$\Delta x_{\text{tip}} = \text{swing} \cdot \text{Sway} \cdot v^3$$
    $$\Delta y_{\text{lift}} = \text{swing}^2 \cdot \text{Curl} \cdot v^3$$
  - 平方项 $\text{swing}^2$ 确保头发向左或向右摆动时发梢产生平滑的自然上提（Curl Lift）。

### 2. 眨眼驱动的果冻眼动力学 (Blink Eye Jelly Dynamics)
- 由左右眼开合速度驱动二阶阻尼弹簧振子：
  $$\dot{v} = 86.0 \cdot (\text{Drive} - \text{Form}) - 10.5 \cdot v$$
  $$\dot{\text{Form}} = v$$
- 眨眼瞬间瞳孔产生轻微面积补偿式的压扁（Squash），睁眼过冲时产生垂直回弹（Stretch，`ParamEyeBallForm`）。

---

## Cubism 标准参数清单与映射表

| 参数 ID | 参数显示名 | 取值范围 | 默认值 | 驱动源 / 物理联动 |
| :--- | :--- | :---: | :---: | :--- |
| `ParamAngleX` | 角度 X | `[-45, +45]` | `0` | 鼠标 X 追踪 / 九轴经纬网 |
| `ParamAngleY` | 角度 Y | `[-30, +30]` | `0` | 鼠标 Y 追踪 / 仰俯曲率 |
| `ParamAngleZ` | 角度 Z | `[-30, +30]` | `0` | 待机动作 / 头部平面旋转 |
| `ParamEyeLOpen` | 左眼 开闭 | `[0, 1]` | `1` | 待机动作眨眼 / 睫毛与眼白闭合 |
| `ParamEyeROpen` | 右眼 开闭 | `[0, 1]` | `1` | 待机动作眨眼 / 睫毛与眼白闭合 |
| `ParamEyeBallX` | 视线 X | `[-1, +1]` | `0` | 鼠标 X 追踪 / 瞳孔横向位移 |
| `ParamEyeBallY` | 视线 Y | `[-1, +1]` | `0` | 鼠标 Y 追踪 / 瞳孔纵向位移 |
| `ParamEyeBallForm`| 果冻眼 | `[-1, +1]` | `0` | 由 `ParamEyeL/ROpen` 物理二阶阻尼驱动 |
| `ParamBrowLY` | 左眉 上下 | `[-1, +1]` | `0` | 眉毛高度控制 |
| `ParamBrowRY` | 右眉 上下 | `[-1, +1]` | `0` | 眉毛高度控制 |
| `ParamMouthForm` | 嘴 变形 | `[-1, +1]` | `0` | 嘴角弧度与横向宽度 |
| `ParamMouthOpenY`| 嘴 开闭 | `[0, 1]` | `0` | 整体张口 $\to$ 中线闭合线插值 |
| `ParamBodyAngleX`| 身体 X | `[-10, +10]` | `0` | 鼠标 X 追踪 / 躯干钟形偏航 |
| `ParamBodyAngleY`| 身体 Y | `[-10, +10]` | `0` | 躯干 S 形俯仰重排 |
| `ParamBodyAngleZ`| 身体 Z | `[-10, +10]` | `0` | 躯干侧向倾斜 |
| `ParamBreath` | 呼吸 | `[0, 1]` | `0` | 待机动作 / 胸腔高斯膨胀 |
| `ParamHairFront` | 前发 摇摆 | `[-1, +1]` | `0` | 由头部与身体 X/Z 物理驱动 |
| `ParamHairBack` | 后发 摇摆 | `[-1, +1]` | `0` | 由头部与身体 X/Z 物理驱动 |

---

## 自动化几何与对称性审计 (Integrity Validation)

1. **中立姿态画元保真审计 (`validateNeutralPose`)**：
   - 逐画元求值顶点世界坐标，确认均为有限数；
   - 验证求值包围盒与原始 PSD 图层包围盒的中心及尺寸偏差 $\le 4\%$。
2. **四角极限姿态完整性审计 (`validateHeadAnglePoses`)**：
   - 求值 $\text{AngleX}=\pm 45^\circ, \text{AngleY}=\pm 30^\circ$ 四个极限姿态；
   - 验证所有画元包围盒维持在原始尺寸的 $8\% \sim 400\%$ 范围内，可见画元不透明度始终大于 0。
3. **方向性变形器对称性审计 (`validateDirectionalWarpDimensions`)**：
   - 验证正负相反角度下，每一行控制点宽度与每一列控制点高度保持严格镜像相等。

