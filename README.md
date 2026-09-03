# PSD2Live

[English](README_en.md) | [日本語](README_ja.md)

PSD2Live 是一个自动化的 Live2D 模型生成流水线与桌面应用。输入分层 PSD 文件，系统自动完成图层语义识别、连通域双侧拆分、自适应三角网格剖分、九轴面部经纬网与解耦变形器层级构建、头发多摆物理与果冻眼动力学模拟及循环待机动作生成，一键导出可编辑的 `.cmo3` 编辑器工程与运行时 `.moc3` 文件族。

---

## 文档索引

| 文档 | 描述 |
| :--- | :--- |
| [用户操作指南 (docs/zh/USER_GUIDE.md)](docs/zh/USER_GUIDE.md) | 桌面 GUI 布局、四大工作区面板、视口操作、快捷键及 CLI 参数说明 |
| [PSD 图层规范与命名指南 (docs/zh/PSD_LAYER_SPEC.md)](docs/zh/PSD_LAYER_SPEC.md) | 31 种语义标签中日英对照、侧别规则、连通域拆分与五官/头发分层规范 |
| [变形器与算法数学规范 (docs/zh/DEFORMER_AND_PARAMETER_SPEC.md)](docs/zh/DEFORMER_AND_PARAMETER_SPEC.md) | 变形器拓扑树、九轴经纬网数学模型、C1 连续曲线、五官修形与动力学公式 |
| [实现对比与设计决策 (docs/zh/IMPLEMENTATION_COMPARISON.md)](docs/zh/IMPLEMENTATION_COMPARISON.md) | 逐流程技术选型、格式不变性与自动化几何自检说明 |

---

## 核心特性

- **自适应网格剖分**：基于可分离高斯平滑滤波与 95th 百分位自适应二值化消除边缘噪点；采用带物理窗口角点识别的周期三次 Bézier 曲线拟合与曲率加权加密采样（最高 12 倍）；执行受约束 Delaunay 剖分结合拓扑动态收敛 Lawson 翻边与超长内部边中点二分细分。
- **变形器 (Warp) 生成**：
  - **眼/口 变形**：眼睛与眉毛共享透视平面约束，瞳孔自动反向补偿防止挤压，睫毛沿 Alpha 权重中线弯曲生成平滑闭眼 U 形曲线；嘴部以最大张口为基准向中线平滑向心压缩闭合，牙齿与舌头自动以嘴部为剪切蒙版。
  - **九轴构建**：建立 `AngleX (±45°) × AngleY (±30°)` 8×8 面部经纬网，结合 C1 连续水平展开/压缩曲线（近侧展开、近眼宽平台保持、远侧透视压缩）、垂直 V/^ 仰俯曲率及四角 $C_{xy} = \text{yaw} \times \text{pitch}$ 交叉修正项。
- **动画**：自动生成 6 秒无缝循环 `idle.motion3.json` 平滑待机动作，涵盖胸腔呼吸起伏、轻微头部/身体倾斜摇摆及自然眨眼；桌面 GUI 内置官方 Cubism 5-r.5 SDK 原生着色器离屏渲染引擎，支持实时鼠标视线追踪（Mouse Look）与动作回放。
- **物理**：前后发完全解耦独立跟随头壳，基于发根固定与 $v^3$ 立方发梢摆幅梯度的多摆物理系统；左右眼开合速度物理驱动二阶阻尼弹簧振子输出果冻眼挤压/回弹动力学（`ParamEyeBallForm`）。
- **工程文件/运行时文件导出**：一键同步导出可在 Live2D Cubism Modeler 5 中二次编辑的 `.cmo3` 完整工程与运行时 `.moc3` 文件族（包含 `.model3.json`、`.cdi3.json`、`physics3.json`、`idle.motion3.json` 及纹理贴图集）；内置中立姿态保真、极限姿态完整性与变形器镜像对称性三道几何自检闸门。

---

## 快速上手

### 环境要求
- **Java Runtime**：JDK 21 或更高版本
- **操作系统**：Windows 10/11 x64（原生预览引擎最佳体验），亦支持 Linux / macOS（软件光栅化渲染）

### 启动桌面应用 (GUI)

- **Windows 一键启动**：运行根目录下的 `run-gui.bat`。
- **Gradle 启动**：
  ```powershell
  # Windows
  .\umamo\gradlew.bat -p .\psd2live run

  # Linux / macOS
  ../umamo/gradlew -p ./psd2live run
  ```

#### 常用快捷键

| 操作 | 快捷键 / 鼠标指令 |
| :--- | :--- |
| **画布缩放** | 鼠标滚轮（以光标为中心，`0.05x ~ 64.0x`） |
| **画布平移** | 鼠标中键拖拽 或 左键拖拽空白 |
| **居中适配** | `F` / `Home` / `0` |
| **选择画元** | 鼠标左键单击画元 |
| **打开 PSD** | `Ctrl + O` |
| **重新分析** | `Ctrl + R` |
| **生成并导出** | `Ctrl + G` |
| **导出到...** | `Ctrl + Shift + G` |

---

### 命令行批处理 (CLI)

```powershell
# 基础运行
.\umamo\gradlew.bat -p .\psd2live run --args="--input ./sample.psd --output ./output"

# 进阶参数配置
.\umamo\gradlew.bat -p .\psd2live run --args="--input ./sample.psd --output ./output --atlas 8192 --mesh-spacing 48 --head-strength 1.2 --lang zh"
```

| 参数 | 类型 | 默认值 | 说明 |
| :--- | :---: | :---: | :--- |
| `--input <path>` | 路径 | *(必需)* | 输入分层 PSD 文件路径 |
| `--output <path>` | 路径 | `PSD同级/psd2live-output` | 导出模型文件族的输出目录路径 |
| `--lang <zh\|en\|ja>` | 字符串 | 系统语言 | 界面与日志语言（支持 `zh` / `en` / `ja`） |
| `--atlas <size>` | 整数 | `4096` | 贴图集尺寸（`256 ~ 16384`） |
| `--mesh-spacing <px>` | 整数 | `64` | 网格基础间距（像素） |
| `--head-strength <val>`| 浮点数 | `1.0` | 头部转动九轴形变幅度（`0.0 ~ 4.0`） |
| `--body-strength <val>`| 浮点数 | `1.0` | 身体与呼吸动作幅度（`0.0 ~ 4.0`） |
| `--no-physics` | 开关 | `false` | 不生成物理配置 |
| `--no-cmo3` | 开关 | `false` | 跳过 `.cmo3` 工程导出 |
| `--no-moc3` | 开关 | `false` | 跳过 `.moc3` 运行时导出 |

---

## PSD 命名速查表

> [!TIP]
> **PSD 原画制作与构图核心建议**：
> - **嘴巴张开且带描边更佳**：原画口部需绘制为最大张口状态（张嘴）；嘴唇外缘带有清晰描边/线稿效果更佳，向心压缩闭合时能自然贴合成清晰唇线。
> - **睫毛仅限眼部上半部分**：`eyelash` 图层必须仅绘制上睫毛，严禁混入下睫毛或下眼眶线，以保证平滑闭眼 U 形曲线算法精准运行。
> - **初始头部允许自然倾斜**：原画立绘中头部允许带有初始倾斜（歪头），系统会自动识别初始角度并以此作为中立原点展开确认转动范围。
> - **身体须保持正立（过于倾斜不受支持）**：躯干动作与胸腔呼吸起伏严格基于垂直坐标系构建，过于倾斜、横卧的身体不受支持。
> 
> 更多分层规则与图层规范请参阅 [PSD 图层规范与命名指南 (docs/zh/PSD_LAYER_SPEC.md)](docs/zh/PSD_LAYER_SPEC.md)。

| 部件 | 推荐英文名 | 常用中文/日文别名 | 行为说明 |
| :--- | :--- | :--- | :--- |
| **头发** | `front hair`, `back hair` | 前发, 后发, 前髪, 後ろ髪 | 独立头壳跟随 + $v^3$ 发梢物理摆动 |
| **脸部** | `face`, `facedetail` | 脸, 脸部, 脸颊, 顔, 肌, 腮红 | 面部轮廓与细节 |
| **眼睛** | `eyewhite`, `eyelash`, `irides`, `eye_close` | 眼白, 睫毛, 瞳孔, 闭眼, 目, 瞳 | 支持自动左右拆分，瞳孔自动剪切，睫毛仅限上睫毛平滑闭眼 |
| **眉毛** | `eyebrow` | 眉, 眉毛, まゆ | 支持自动左右拆分与透视联动 |
| **鼻子** | `nose` | 鼻, 鼻子 | 最大立体空间深度位移 |
| **嘴巴** | `mouth`, `mouth_open` | 嘴, 口, 嘴巴, 张嘴 | 最大张口原图（建议带清晰描边），自动向中线向心压缩闭口 |
| **口腔内部件** | `tooth-t`, `tooth-b`, `tongue` | 上牙, 下牙, 舌头, 歯, 舌 | 可选部件，自动以 mouth 为剪切蒙版 |
| **耳朵** | `ears` | 耳, 耳朵 | 随头部转动负深度位移与透视遮挡淡出 |
| **身体** | `neck`, `topwear`, `bottomwear`, `legwear` | 脖子, 上衣, 裤子, 裙子, 身体 | 身体偏航、俯仰、倾斜与呼吸膨胀（身体须保持正立） |
| **饰品** | `headwear`, `earwear`, `neckwear`, `tail`, `wings` | 头饰, 耳饰, 项链, 尾巴, 翅膀 | 挂载于对应父级变形器 |

---

## 变形器层级与参数体系

```text
Root (Canvas Space)
 └─ DeformBodyXY (ParamBodyAngleX, ParamBodyAngleY)
     └─ DeformBodyZBreath (ParamBodyAngleZ, ParamBreath)
         └─ DeformHeadRotation (ParamAngleZ)
             └─ DeformHeadContainer (ParamAngleX, ParamAngleY 头壳跟随)
                 ├─ DeformFaceNinePose (ParamAngleX, ParamAngleY 九轴经纬网)
                 │   ├─ Eye / Iris / Brow / Nose / Mouth / Ear
                 │   └─ FaceDetails
                 ├─ HairFrontFollow → HairFrontPhysics (ParamHairFront)
                 ├─ HairBackFollow  → HairBackPhysics  (ParamHairBack)
                 └─ HeadAccessories
```

| 参数 ID | 名称 | 范围 | 默认值 | 作用说明 |
| :--- | :--- | :---: | :---: | :--- |
| `ParamAngleX` / `Y` / `Z` | 头部角度 X / Y / Z | `[-45..45]` / `[-30..30]` / `[-30..30]` | `0` | 头部偏航、仰俯与平面旋转 |
| `ParamEyeLOpen` / `ROpen` | 左/右眼 开闭 | `[0, 1]` | `1` | 睫毛平滑闭眼 U 形线，瞳孔由眼白遮罩隐藏 |
| `ParamEyeBallX` / `Y` | 视线 X / Y | `[-1, +1]` | `0` | 瞳孔注视追踪 |
| `ParamEyeBallForm` | 果冻眼 | `[-1, +1]` | `0` | 眨眼驱动瞳孔挤压回弹动力学 |
| `ParamBrowLY` / `RY` | 左/右眉 上下 | `[-1, +1]` | `0` | 眉毛上下移动 |
| `ParamMouthForm` | 嘴 变形 | `[-1, +1]` | `0` | 嘴角抬升/下压与宽度 |
| `ParamMouthOpenY` | 嘴 开闭 | `[0, 1]` | `0` | 完整张口 $\to$ 中线闭口缝平滑插值 |
| `ParamBodyAngleX` / `Y` / `Z`| 身体 X / Y / Z | `[-10, +10]` | `0` | 躯干偏航 Roll、S 形俯仰与倾斜 |
| `ParamBreath` | 呼吸 | `[0, 1]` | `0` | 胸腔高斯呼吸起伏 |
| `ParamHairFront` / `Back` | 前/后发 摇摆 | `[-1, +1]` | `0` | 前后发多摆物理模拟 |

---

## 导出产物

```text
output_dir/
├── sample.cmo3                    # 可在 Live2D Modeler 5 中二次编辑的完整工程
├── sample.moc3                    # 运行时模型文件 (MOC5 基线)
├── sample.model3.json             # 运行时配置文件 (贴图、物理、动作接线)
├── sample.cdi3.json               # 显示名称元数据
├── sample.physics3.json           # 物理模拟配置 (头发多摆 + 果冻眼)
├── sample.idle.motion3.json       # 6 秒无缝循环平滑待机动作
├── sample.4096/texture_00.png     # 纹理贴图集
└── sample.psd2live.json         # 诊断报告与映射元数据
```

---

## 构建与测试

```powershell
# 编译并打包独立运行 ZIP
.\umamo\gradlew.bat -p .\psd2live clean test distZip

# 运行全套单元测试
.\umamo\gradlew.bat -p .\psd2live test
```

---

## 许可证与致谢

- **开源许可证**：本项目采用 [GNU General Public License v3.0 (GPL-3.0)](LICENSE)。
- **第三方参考与致谢**：本项目运行时通过 Gradle Composite Build 链接了 [Umamo](THIRD_PARTY_NOTICES.md) 模块，并在语义规范、网格算法与物理设计上参考了 [Stretchy Studio](THIRD_PARTY_NOTICES.md)。详细说明请参阅 [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)。

---

## 免责声明

- PSD2Live 是独立开发的开源项目，与 Live2D Inc. 及其关联方不存在任何隶属、授权或赞助关系。
- `Live2D`、`Cubism`、`.cmo3`、`.moc3` 等名称与文件扩展名仅用于格式兼容性说明，其商标与知识产权归各自权利人所有。本项目不包含且不分发 Live2D 官方 SDK。
- 本项目按“现状”提供，请在正式生产前备份原始 PSD 文件，并在目标软件中检查生成效果。
