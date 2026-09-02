# AutoLive2D

AutoLive2D 是一个自动化的 Live2D 模型生成流水线与桌面应用：输入采用 **See-Through** 规范（及中/日文别名）的分层 PSD 文件，自动完成图层语义识别、连通域拆分、自适应网格三角化、九轴面部经纬网构建、头发物理模拟与循环待机动作生成，一键导出可编辑的 `.cmo3` 工程文件与运行时 `.moc3` 文件族。

---

## 核心特性

- **现代桌面 GUI & 命令行 CLI**
  - 基于 JetBrains Compose Multiplatform 构建的桌面应用，支持 PSD 文件拖拽导入。
  - **多视图工作区**：提供“层级 (Hierarchy)”、“拓扑 (Topology)”、“预览 (Preview)”、“日志 (Log)”四大面板。
  - **无限画布视口**：支持平滑缩放（以光标为中心）、平移与一键居中适配（`F` / `Home` / `0`）。
  - **实时语义与图层管理**：表格化管理图层识别结果与可见性，直接编辑语义与侧别并实时重算预览。
  - **内置多语言**：支持简体中文、English、日本語即时切换（CLI 支持 `--lang`）。
- **智能图层语义与自动拆分**
  - 全面支持 See-Through 英文命名规范及常见中文、日文别名，自动解析 `-l/-r`、`_left/_right` 与 `左/右` 侧别。
  - 对合并绘制的双眼、眉毛、眼白、睫毛等图层执行 8 邻域连通域自动左右拆分。
  - **整体口形处理**：将 `mouth` / `mouth_open` 视为完整张口原图，`ParamMouthOpenY` 向中线平滑压缩闭口；支持可选的 `tooth-t`、`tooth-b`、`tongue` 自动生成剪切蒙版并规范绘制层序。
- **高质量自适应网格生成 (Adaptive Mesh & Delaunay)**
  - **边缘预处理**：Alpha 高斯平滑与自适应百分位阈值二值化，有效消除羽化边缘毛刺与噪点孤岛。
  - **Bézier 闭合轮廓**：拟合周期三次 Bézier 曲线并沿法线微量外扩，根据局部曲率自适应加密（最高 12 倍），精确保留发尖、手指等转角细节。
  - **受约束 Delaunay 三角化**：内部错列 Steiner 点分布与自适应 Lawson 翻边收敛算法，彻底解决长窄发束的扇形退化面。
- **九轴面部经纬网与解耦变形器层级**
  - **8×8 面部经纬网**：生成 `AngleX {-45, 0, +45} × AngleY {-30, 0, +30}` 九个标准关键姿态。横向采用 C1 连续分段透视曲线（近侧平移展开、远侧透视压缩），纵向实现 V/^ 仰俯曲率，斜角包含独立 XY 交叉修正。
  - **五官二次修形**：眼、眉、瞳孔、鼻、嘴、耳按感知深度与二维保持率独立修形，瞳孔继承眼部透视面，虹膜自动按眼白裁切。
  - **独立头发层级与物理系统**：前发/后发脱离面部 Warp，先做头壳跟随，再由发根固定、`v³` 递增的发梢 Warp 接收多摆物理模拟。
- **双格式导出与完整性自检**
  - 同时导出官方编辑器工程 `.cmo3` 与运行时 `.moc3` 文件族（以 Cubism 5.0 / MOC5 为标准基线）。
  - 自动生成 `physics3.json`（前后发多摆物理）与 6 秒平滑循环 `idle.motion3.json` 待机动作。
  - **自动化几何与格式审计**：导出前/后求值默认姿态与九轴极限姿态，逐画元检查边界、透明度与对称性，杜绝塌缩或异常位移。

---

## 技术架构

- **语言与平台**：Kotlin / JVM 21
- **UI 框架**：JetBrains Compose Multiplatform Desktop
- **底层格式引擎**：基于 Umamo 的 `format`、`runtime`、`interop`、`render` 模块
- **核心算法**：纯 Kotlin 实现的图像分析、连通域提取、自适应 Delaunay 剖分、九轴面部经纬网与物理动力学生成

详细的逐流程对比与设计说明请参阅 [`docs/IMPLEMENTATION_COMPARISON.md`](docs/IMPLEMENTATION_COMPARISON.md)。

---

## 快速上手

### 环境要求
- **Java 21+**（JDK 21 或更高版本）

### 启动桌面界面 (GUI)

- **Windows 快捷启动**：
  双击运行根目录下的 `run-gui.bat`。

- **命令行启动**：
  ```powershell
  # Windows
  .\umamo\gradlew.bat -p .\autolive2d run

  # Linux / macOS
  ../umamo/gradlew -p ./autolive2d run
  ```

#### GUI 操作流程
1. **导入 PSD**：点击菜单“文件 -> 打开 PSD”或直接将 `.psd` 文件拖入窗口。
2. **分析与调试**：
   - 在“层级”面板查看生成的变形器树与画元组织；
   - 在“拓扑”面板检查 ArtMesh 顶点与三角网格连通性；
   - 在“预览”面板实时测试鼠标视线追踪、呼吸、眨眼与物理摆动，或在右侧滑杆手动调整参数。
3. **图层微调**：在右侧图层表格中按需调整未识别图层的语义、侧别或显示开关。
4. **生成与导出**：点击“生成并导出”，日志显示完成后即可在输出目录获得完整模型文件。

---

### 命令行模式 (CLI)

```powershell
# 运行示例
.\umamo\gradlew.bat -p .\autolive2d run --args="--input ./sample.psd --output ./output"
```

#### CLI 参数列表

| 参数 | 默认值 | 说明 |
| :--- | :--- | :--- |
| `--input <path>` | *(必需)* | 输入分层 PSD 文件路径 |
| `--output <path>` | `PSD同级/autolive2d-output` | 输出目录路径 |
| `--lang <zh\|en\|ja>` | 系统语言 | 界面与日志语言（支持 `zh` / `en` / `ja`） |
| `--atlas <size>` | `4096` | 贴图集尺寸（支持 `256` ~ `16384`） |
| `--mesh-spacing <px>` | `64` | 网格基础间距（像素）；局部高曲率区域会自动细分加密 |
| `--head-strength <val>`| `1.0` | 九轴面部经纬网与五官修形幅度倍率 |
| `--body-strength <val>`| `1.0` | 身体与呼吸动作幅度倍率 |
| `--no-physics` | `false` | 不生成 `physics3.json`，且不在 CMO3 中写入物理设置 |
| `--no-cmo3` | `false` | 跳过 `.cmo3` 工程导出 |
| `--no-moc3` | `false` | 跳过 `.moc3` 运行时文件族导出 |

---

## PSD 图层命名规范

AutoLive2D 推荐采用 **See-Through** 标准命名风格，并广泛兼容中文与日文别名：

| 部件类型 | 推荐英文图层名 | 常用中文/日文别名 | 说明 |
| :--- | :--- | :--- | :--- |
| **头发** | `front hair`, `back hair` | 前发, 后发, 前髪, 後ろ髪 | 前后发自动绑定物理摆动 |
| **脸部** | `face`, `facedetail` | 脸, 脸部, 脸颊, 顔, 肌 | 面部基础轮廓与腮红 |
| **眼睛** | `eyewhite`, `eyelash`, `irides`, `eye_close` | 眼白, 睫毛, 瞳孔, 闭眼, 目, 瞳 | 支持连通域左右拆分，虹膜自动按眼白剪切 |
| **眉毛** | `eyebrow` | 眉, 眉毛, まゆ | 支持左右拆分与仰俯联动 |
| **鼻子** | `nose` | 鼻, 鼻子 | 具有更高空间感知深度位移 |
| **嘴巴** | `mouth`, `mouth_open` | 嘴, 口, 嘴巴 | 提供最大张口图，自动计算闭口形变 |
| **口腔部件** | `tooth-t`, `tooth-b`, `tongue` | 上牙, 下牙, 舌头, 歯, 舌 | 可选部件，自动以 mouth 为蒙版剪切 |
| **耳朵** | `ears` | 耳, 耳朵 | 随头部转动产生透视遮挡渐隐 |
| **身体** | `neck`, `topwear`, `bottomwear`, `legwear` | 脖子, 上衣, 裤子, 裙子, 身体 | 自动绑定身体 XYZ 与呼吸变形器 |
| **饰品/其他** | `headwear`, `earwear`, `neckwear`, `tail`, `wings` | 头饰, 耳饰, 项链, 尾巴, 翅膀 | 依附于对应父级变形器 |

> **提示**：
> - 双侧部件支持后缀：`-l`/`-r`、`_l`/`_r`、`_left`/`_right` 或 `左`/`右`（以角色自身左右为准：角色左眼通常位于画面右侧）。
> - 未拆分侧别的双眼、眉毛等图层，程序将自动通过 8 邻域连通域算法拆分为独立左右部件。
> - 未匹配到预设规则的图层不会被丢弃，将根据空间位置自动归入头部或身体容器。

---

## 变形器层级与参数体系

### 变形器树形结构
```text
Root
 └─ BodyXY (BodyAngleX / BodyAngleY)
     └─ BodyZ_Breath (BodyAngleZ / Breath)
         └─ HeadRotation (AngleZ)
             └─ HeadContainer (AngleX / AngleY 跟随)
                 ├─ FaceNinePose (AngleX / AngleY 九轴强变形)
                 │   ├─ Eye / Brow / Nose / Mouth / Ear
                 │   └─ FaceDetails
                 ├─ HairFrontFollow → HairFrontPhysics (ParamHairFront)
                 ├─ HairBackFollow  → HairBackPhysics  (ParamHairBack)
                 └─ HeadAccessories (头饰与未识别头部组件)
```

### 生成的 Cubism 标准参数

| 参数 ID | 参数名称 | 范围 | 绑定说明 |
| :--- | :--- | :---: | :--- |
| `ParamAngleX` | 角度 X | `[-45, +45]` | 头部水平转动（九轴经纬网） |
| `ParamAngleY` | 角度 Y | `[-30, +30]` | 头部垂直仰俯（V/^ 曲率） |
| `ParamAngleZ` | 角度 Z | `[-30, +30]` | 头部平面倾斜旋转 |
| `ParamEyeLOpen` / `ParamEyeROpen` | 左/右眼 开闭 | `[0, 1]` | 睫毛沿原画中心轨迹轻微变细并弯曲为与收缩眼白重合的 U 形闭眼线；瞳孔保持原形并由眼白遮罩隐藏 |
| `ParamEyeBallX` / `ParamEyeBallY` | 视线 X / Y | `[-1, +1]` | 瞳孔眼球追踪偏移 |
| `ParamBrowLY` / `ParamBrowRY` | 左/右眉 上下 | `[-1, +1]` | 眉毛上下移动 |
| `ParamMouthForm` | 嘴 变形 | `[-1, +1]` | 嘴角抬升/下压与横向宽度 |
| `ParamMouthOpenY` | 嘴 开闭 | `[0, 1]` | 完整张口 -> 中线闭口线平滑插值 |
| `ParamBodyAngleX` / `Y` / `Z` | 身体 X / Y / Z | `[-10, +10]` | 躯干内部空间重排与倾斜 |
| `ParamBreath` | 呼吸 | `[0, 1]` | 胸腔呼吸膨胀与微动 |
| `ParamHairFront` / `ParamHairBack` | 前/后发 摇摆 | `[-1, +1]` | 前后发物理模拟摆动 |

---

## 导出产物说明

执行导出后，目标目录将生成完整的 Live2D 模型套件（以模型名 `sample` 为例）：

```text
output_dir/
├── sample.cmo3                    # 可在 Live2D Cubism Modeler 中二次编辑的完整工程
├── sample.moc3                    # 运行时模型文件 (MOC5 兼容基线)
├── sample.model3.json             # 运行时配置文件 (定义贴图、物理、动作与参数接线)
├── sample.cdi3.json               # 显示名称元数据 (参数、部件、变形器显示名)
├── sample.physics3.json           # 物理模拟配置文件 (前后发多摆物理系统)
├── sample.idle.motion3.json       # 6 秒循环平滑待机动作
├── sample.4096/                   # 导出的贴图集目录
│   └── texture_00.png             # 打包生成的纹理贴图
└── sample.autolive2d.json         # 识别映射、统计信息与导出诊断报告
```

> **注意**：`.moc3` 需与 `model3.json`、贴图目录及引用的 sidecar 配置文件保持相对路径结构共同部署。

---

## 构建与开发

### 构建独立分发包

```powershell
# 编译、运行测试并打包分发 ZIP
.\umamo\gradlew.bat -p .\autolive2d clean test distZip
```
产物将输出在 `build/distributions/autolive2d-0.1.0.zip`。解压后运行 `bin/autolive2d.bat` 即可独立运行（需 Java 21 环境）。

### 运行测试套件

```powershell
.\umamo\gradlew.bat -p .\autolive2d test
```
测试套件覆盖：多语言图层命名解析、8 邻域连通域拆分、周期 Bézier 轮廓生成、Delaunay 三角化与 Lawson 翻边收敛、九轴面部经纬网位移、身体/头部对称性校验、多摆物理系统、CMO3/MOC3 序列化与极限姿态回读校验。

---

## 许可证与致谢

- **开源许可证**：本项目采用 [GNU General Public License v3.0 (GPL-3.0)](LICENSE)。
- **第三方参考与致谢**：
  - 本项目运行时通过 Gradle Composite Build 链接了 [Umamo](THIRD_PARTY_NOTICES.md) 模块（GPL-3.0）以实现底层格式支持。
  - 在语义规范、算法设计与物理系统上参考并重新实现了 [Stretchy Studio](THIRD_PARTY_NOTICES.md) 和 [Anime2.5DRig](THIRD_PARTY_NOTICES.md) 的相关设计理念。
  - 详细的第三方许可说明请参阅 [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)。

---

## 免责声明

- AutoLive2D 是独立开发的开源项目，与 Live2D Inc. 及其关联方不存在任何隶属、授权或赞助关系。
- `Live2D`、`Cubism`、`.cmo3`、`.moc3` 等名称与文件扩展名仅用于格式兼容性说明，其商标与知识产权归各自权利人所有。本项目不包含且不分发 Live2D 官方 SDK。
- 本项目按“现状”提供，请在正式生产前备份原始 PSD 文件，并在目标软件中检查生成效果。
