# AutoLive2D

AutoLive2D 是一个不使用 Web 技术的桌面流水线：导入采用 **See-Through** 命名风格的分层 PSD，自动完成语义识别、左右拆分、网格建模、变形器层级、标准参数关键形态、头发物理和待机动作，最后同时导出可编辑的 `.cmo3` 与运行时 `.moc3` 文件族。

> 用户需求中的 `com3` 按 Live2D 的实际扩展名解释为 `cmo3`。

## 当前能力

- 原生 Swing GUI，无 WebView、浏览器或本地 HTTP 服务；支持 PSD 拖放、预览、语义表、参数设置、后台进度与日志。
- 读取 PSD 的 RGBA 图层、顺序、可见性、透明度、裁切范围、组路径和混合模式。
- See-Through 英文命名，并兼容常见中文、日文别名与 `-l/-r`、`左/右` 后缀。
- 对合并的双眼、眉毛、眼白、睫毛执行 8 邻域连通域左右拆分。
- 参考 Stretchy Studio 生成 alpha 轮廓自适应 ArtMesh：外轮廓与每个内孔独立等弧长采样，细弯画元按平均笔画宽度自动加密，保留深凹口拐点，内部错列采样并执行确定性 Delaunay；每条三角边和三角形覆盖像素都通过 alpha 拓扑检查。外部抗锯齿边缘只允许 1px 贴图余量，封闭孔洞不膨胀，避免中空件、内凹件被跨区缝合。仅在退化细线等无法三角化时回退到矩形网格。
- 生成 17 个 Cubism 常用参数：头部 XYZ、身体 XYZ、双眼开合、视线 XY、双眉 Y、嘴型/嘴巴开合、呼吸、前后发摆动。
- 生成 `AngleX {-45,0,+45} × AngleY {-30,0,+30}` 九个手工建模式关键姿态：8×8 面部经纬网负责轮廓和体积，眼/瞳孔/眉/鼻/嘴/耳的子 Warp 再按各自深度和二维保持率重画；四个斜角包含独立 XY 交叉修正项。
- 眼白/睫毛/虹膜闭眼形变，闭眼图层和开闭嘴图层交叉淡化，虹膜按眼白裁切。
- 自动生成 `physics3.json` 和循环 `idle.motion3.json`。
- MOC3 固定以 Cubism 5.0/MOC5 为兼容基线，不依赖 5.3 的 MOC6 扩展。
- 导出后立即回读 MOC3、CMO3、model3、cdi3、physics3；除 ID/清单/JSON 外，还会求值默认姿态及 AngleX/AngleY 四个极限姿态，逐画元检查是否缺失、透明、塌缩或异常放大。

## 技术栈

- Kotlin/JVM 21
- Java Swing/AWT 原生桌面 UI
- Gradle composite build
- Umamo 的 `format`、`runtime`、`interop`、`render` 模块负责 PSD/CMO3/MOC3 格式边界与统一运行时模型
- 新项目内的纯 Kotlin 算法负责识别、图像分析、贴图、建模和自动 Rig

选择 Kotlin/JVM 是因为当前工作区中可验证的 PSD、CMO3、MOC3 读写链已经在 Umamo 中形成同一套类型安全模型；继续使用它能避免在另一个语言里重新实现高风险二进制格式。Swing 是 JDK 原生桌面 UI，不引入浏览器运行时，适合本工具以可靠和实用为先的界面目标。

详细的逐流程对比见 [`docs/IMPLEMENTATION_COMPARISON.md`](docs/IMPLEMENTATION_COMPARISON.md)。

## 使用

要求：Windows 上安装 JDK 21；当前源码布局中 `autolive2d` 与 `umamo` 是相邻目录。

最简单的 GUI 启动方式：

```bat
run-gui.bat
```

也可在工作区根目录执行：

```powershell
.\umamo\gradlew.bat -p .\autolive2d run
```

GUI 工作流：

1. 选择或拖入 PSD。
2. 点击“分析 PSD”，检查预览中的 Face/Body 范围和右侧语义表。
3. 按需要调整贴图页、网格间距和动作幅度。
4. 点击“生成并导出”。
5. 日志显示“导出并回读校验完成”后使用输出目录中的文件。Viewer/运行时应加载 `sample.model3.json`，这样贴图、物理和动作会随 MOC3 一起解析。

命令行模式：

```powershell
.\umamo\gradlew.bat -p .\autolive2d run --args="--input ..\Anime2.5DRig\sample.psd --output build\sample-output"
```

参数：

| 参数 | 默认值 | 说明 |
| --- | ---: | --- |
| `--input` | 必需 | 输入 PSD |
| `--output` | PSD 旁的 `autolive2d-output` | 输出目录 |
| `--atlas` | `4096` | 贴图页边长，256..16384 |
| `--mesh-spacing` | `64` | 轮廓与内部三角网格的基础采样间距，越小越密；大图层会自动限制顶点预算 |
| `--head-strength` | `1.0` | 九轴面部经纬网及五官二次修形幅度 |
| `--body-strength` | `1.0` | 身体/呼吸动作幅度 |
| `--no-physics` | 否 | 不生成 physics3，也不向 CMO3 写入物理设置 |
| `--no-cmo3` | 否 | 不生成 CMO3 |
| `--no-moc3` | 否 | 不生成 MOC3 文件族 |

构建可携带的发行包：

```powershell
.\umamo\gradlew.bat -p .\autolive2d clean test distZip
```

产物位于 `autolive2d/build/distributions/autolive2d-0.1.0.zip`。解压后运行 `bin/autolive2d.bat`；依赖 JDK 21，但不再依赖相邻源码目录。

## See-Through 命名

推荐的基础图层名：

```text
back hair, front hair, headwear, face, facedetail,
irides, eyebrow, eyewhite, eyelash, eye_close,
eyewear, ears, earwear, nose,
mouth, mouth_open, mouth_close,
neck, neckwear, topwear, handwear, bottomwear,
legwear, footwear, tail, wings, objects
```

双侧图层用 `-l/-r`、`_left/_right` 或 `左/右`。后缀中的 L/R 指角色自身左右：角色左眼通常显示在画面的右边。未带侧别且包含两个分离区域的眼部图层会自动拆分。图层可以附加编号，如 `front hair 2`。

未知图层不会丢弃：程序按它与面部范围的位置关系归入 Head 或 Body，并在报告中提示。

## 九轴面部算法

九轴按 `K(x,y)=K00+X(x)+Y(y)+C(x,y)` 生成。`C` 是四个斜角独有的交叉修正，不把斜角简单视为横向与纵向位移相加。

1. 由左右眼窝中点建立横向中心，再用鼻位轻微修正；纵向中心放在眼线到嘴线之间，而不是使用 face 图层的 alpha 质心。实际中心和半径写入 `*.autolive2d.json` 的 `faceRig` 字段。
2. 8×8 面部经纬网的 X 位移采用 C1 连续的分段曲线：近侧轮廓短暂展开后进入宽平台，让近眼以平移为主、宽度基本不变；远侧曲线连续下降，形成主要透视压缩。鼻尖向右滚动时，画面左侧是近侧、右侧是远侧，反向转头时整条曲线镜像。
3. Y 使用独立的密度和横向曲率曲线：低头形成 V 并缩短眼—鼻—嘴—下巴间距，抬头形成 ^ 并扩大间距；斜角的交叉项会把 V/^ 向远近方向偏斜。
4. 五官再经过各自不同的子 Warp：鼻尖位移大于嘴，嘴大于眼；近眼只允许极小的宽度变化，远眼采用受限缩小，眉毛使用独立且稍强的宽度曲线。双眼和双眉分别约束在透视平行四边形中：`AngleY=0` 时左右边线严格无斜率，只有四个斜角才由带符号的 `yaw×pitch` 产生斜率，并保证两侧局部边线与两中心连线平行。瞳孔继承眼部透视面，只做二维保持而不重复剪切；嘴的中心移动快于两端，远侧嘴角滞后更多；远耳缩窄并渐隐。
5. 面部九轴只覆盖脸皮、五官、耳朵和面部附件，不再使用包含头发的整头包围盒。前发、后发与面部是头部容器下的三个独立子树，头饰和未识别的头部内容直接跟随头部容器，不继承脸部 Roll。

输出仍使用标准 `ParamAngleX/ParamAngleY`，因此面捕或其他追踪器无需理解内部算法，只需驱动参数即可。

## 变形器层级与头发物理

```text
BodyXY → BodyZ/Breath → HeadRotation → HeadContainer
                                      ├─ FaceNinePose → Eye/Brow/Nose/Mouth/Ear
                                      ├─ HairFrontFollow → HairFrontPhysics
                                      ├─ HairBackFollow  → HairBackPhysics
                                      └─ HeadAccessories
```

`HeadContainer` 以小幅 AngleX/AngleY 位移控制头壳和头饰，面部在其上叠加更强九轴，实现“头壳慢、脸面快”。前后发不经过 `FaceNinePose`：每组头发先用独立的 AngleX/AngleY Warp 表达头壳跟随与深度视差，再用只绑定 `ParamHairFront` 或 `ParamHairBack` 的发梢 Warp 接收物理输出。发根行完全固定，位移按 `v³` 集中到发梢；摆幅以 `min(width,height)` 计算，避免短而宽的刘海整块漂浮。

物理规则参考 StretchyStudio/Hiyori：前发使用长度 3、Delay 0.9、输出 Scale 1.522 的短摆；后发使用长度 15、Delay 0.8、输出 Scale 2.061 的长摆。输入为 `AngleX + AngleZ + BodyAngleX + BodyAngleZ`。MOC3 文件族生成标准 `physics3.json` 并由 `model3.json` 引用；CMO3 同时写入可编辑的 `CPhysicsSettingsSourceSet`。

## 输出结构

以模型名 `sample` 为例：

```text
sample.cmo3                    可编辑 Cubism 工程
sample.moc3                    运行时模型
sample.model3.json             文件族清单与 EyeBlink/LipSync/Idle 接线
sample.cdi3.json               参数、组、部件、画元显示名
sample.physics3.json           前后发物理（存在相应图层时）
sample.idle.motion3.json       6 秒循环待机动作
sample.4096/texture_00.png     一页或多页贴图
sample.autolive2d.json         识别映射、统计和警告报告
```

`.moc3` 必须和 `model3.json`、贴图及被引用 sidecar 一起部署；单独复制 `.moc3` 不是完整的运行时模型。导出的二进制目标是 MOC5，Cubism Viewer 5.4 可加载该代模型。

## 校验边界与故障判断

- “角色头部不显示”和“二进制文件头损坏”是两件事。`.moc3` 的魔数是 `MOC3`，`.cmo3` 容器的魔数是 `CAFF`；此前 MOC3 与 Umamo 中同时缺少角色头部，根因是 `HeadRotation` 子级错误地把像素偏移写成了 0..1 归一化绝对坐标，导致整棵面部 Warp 缩到亚像素大小，并非容器魔数丢失。
- 现在 Rotation 的枢轴仍按父 Warp 归一化坐标保存，而其直接子 Warp 使用相对枢轴的像素偏移；默认姿态在生成后、MOC3 回读后和 CMO3 回读后各检查一次画元边界，头部再次塌缩会直接终止导出。
- 静态画元使用真正的“无参数、单关键形态”网格，不再伪装成只有 `ParamAngleX=0` 的单点绑定。生成模型、MOC3 回读模型和 CMO3 回读模型都会额外求值 `AngleX=±45`、`AngleY=±30`；任一原本可见画元在非零角度下缺失、变透明或塌缩都会终止导出。
- AngleY 遵循画布 Y 向下的约定：正值向上看。新版不再把 Anime2.5DRig 的椭球/圆柱投影作为最终脸型，而是将少量隐式体积与大量二维感知修形组合，避免所有部件同速移动。
- MOC3 的“能被 Umamo/live2dConverter 回读”只说明结构可解析，不等于官方 Core 接受。开发验收另外使用 Cubism Core 5-r.5 的 `csmHasMocConsistency`；当前由同一 PSD 生成的 MOC5 返回 `1`。应用本身不捆绑或分发官方 SDK，因此 GUI 内置校验仍是格式回读与几何校验。
- Cubism 4.2+ 即使没有调色，也要求每个 Warp、Rotation、ArtMesh 关键形态对应默认的白色 Multiply/黑色 Screen 行。导出器现在始终写入这些基础颜色行及正确的 CountInfo 总数；此前缺失这些行会被官方 Core 报为 `Data section is invalid`。

## 已知边界

- 当前目标是单个、正面、分层完整的二次元角色；多人、极端透视和大面积遮挡不在自动推断保证范围内。
- CMO3 会写入可编辑的 Part、Deformer、ArtMesh、参数与关键形态；其“源图层视图”从打包贴图切片回构，不保留 PSD 的文本、智能对象、调整层及原始 Photoshop 编辑结构。日志中的中文 `未保留原始 PSD 源图编辑链`（底层报告名 `MissingSourceArt`）只描述这一编辑链缺失，不表示角色头部或 Rig 数据缺失。
- Cubism 只提供 Normal/Add/Multiply 三类运行时混合，其他 PSD 混合模式会降级映射。
- 自动网格直接使用阈值 alpha 的外环/孔洞拓扑。长直孔边会继续细分，外环的显著内凹角和孔洞转角会作为固定锚点；过滤后没有参与有效面的孤立顶点会被删除。极细、近共线或不足三个有效采样点的真正退化图层仍会单独回退到矩形网格，并继续接受导出后的几何校验。
- 自动结果能从现有像素生成 Warp、压缩和渐隐，但不能凭空画出 PSD 中不存在的“三分之二鼻翼、侧脸线稿”等替换素材；有这些图层时仍需后续增加专用显隐规则。
- 项目与所参考格式实现仍属早期工程，处理重要原稿前请保留 PSD 和输出备份。

## 测试

```powershell
.\umamo\gradlew.bat -p .\autolive2d test
```

测试覆盖多语言命名、左右连通域拆分、普通轮廓/封闭孔洞/深窄凹口/单像素中空环网格、九轴中心/深度差/远眼保持/XY 交叉项、前后发独立层级/发根固定/发梢响应、physics3 参数，以及 `Anime2.5DRig/sample.psd` 的 MOC3/CMO3 完整导出、CMO3 内嵌物理和九轴极限姿态回读。官方 Core 检查属于开发验收，因为许可证要求本项目不能随测试分发 Core DLL。

## 许可

项目使用 GPL-3.0。原因是运行时链接了 GPL-3.0 的 Umamo 模块。参考项目和使用边界详见 [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)。Live2D、Cubism 及相关文件扩展名属于各自权利人；本项目不包含 Live2D 官方 SDK。
