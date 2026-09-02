# 参考实现与 AutoLive2D 的逐流程对比

本文记录选择依据和实际落点，避免把多个参考项目的重复功能机械叠加。

## 参考项目定位

| 项目 | 擅长部分 | 本项目如何使用 |
| --- | --- | --- |
| `umamo` | PSD 中立源图模型、PuppetModel、CMO3/MOC3 读写、空间变换和 sidecar 文件族 | 直接作为 Gradle composite 模块依赖；作为格式与运行时模型的唯一事实源 |
| `stretchystudio` | See-Through 命名整理、连通域左右拆分、图像分析、轮廓采样/Delaunay、贴图、简单自动 Rig | 重写语义流程、alpha 分析与左右拆分；保留“连续面部 Warp”的设计经验；没有复制 Web UI |
| `Anime2.5DRig` | 多语言部件别名、骨架锚点、椭球壳 2.5D 投影、头发摆动和运行时动作 | 保留别名、初始锚点和头发控制经验；椭球投影只作为早期基线，现有九轴脸型已由语义化二维修形替代 |
| `live2dConverter`（Quadrism） | MOC3/CMO3 转换时的参数命名、分组、层级追踪、动作姿态读取和容错报告 | 参考其“ID/显示名分离、组和组合参数进入 cdi3、转换必须报告降级”的原则；未链接 Rust 代码 |
| `live2d_docs` | PSD、MOC3、CMO3 容器、记录布局、坐标与文件族说明 | 约束输入/输出、大小端、坐标空间、清单闭包和导出后回读校验 |

## 分阶段比较

| 阶段 | 参考实现 | AutoLive2D 的实现与选择 |
| --- | --- | --- |
| 1. PSD 解码 | Umamo 完整解析 PSD 头、图层记录、Raw/RLE/ZIP 通道并生成 `SourceArt`；Stretchy/Anime 侧重浏览器 PSD 库 | 使用 Umamo `PsdReader`，一次获得裁切 RGBA、层序、透明度、可见性、组路径、裁切标记和混合模式，避免维护第二个 PSD 解码器 |
| 2. See-Through 分类 | Stretchy `psdOrganizer` 使用固定英文标签；Anime 的 rigger 有英/中/日别名并做 NFKC 归一化 | 合并二者：NFKC、小写、copy/编号清理、最长前缀匹配、L/R/左/右解析；保留未知层而不是拒绝导入 |
| 3. 双侧拆分 | Stretchy `splitLR` 使用 8 邻域 union-find，取最大两块；Anime 同样用连通域和画面中心判断侧别 | 使用 8 邻域 BFS，过滤小于 12 像素噪点，取两大且必须分居面部中心两侧的区域；输出独立裁切 raster 与稳定虚拟 ID |
| 3b. 口形输入 | 常规手工建模既可把口腔、舌和牙齿预先拆层，也可提供一张完整口形 | 不再从颜色自动拆分。`mouth` / `mouth_open` 始终作为最大张口的完整画元；只有明确命名为 `tooth-t`、`tooth-b`、`tongue` 的图层才作为可选内部件，避免对画风、阴影和高光作不可靠推断 |
| 4. 人体/面部锚点 | Stretchy 通过 alpha 并集、脊柱、肩/髋估算；Anime 使用语义包围盒和启发式锚点 | alpha 非透明边界联合；face 优先显式图层，缺失时由头部层估计；topwear/bottomwear 修正肩与髋；所有降级写入报告 |
| 5. 网格 | Stretchy 轮廓膨胀、内部抖动采样、Delaunay，并以不透明岛外缘为主要边界；Anime 为实时效果构建规则网格；Umamo 同时提供外环和孔洞环 | 先对 alpha 做可分离 `[1,2,1]` 高斯预滤波，以非零 alpha 第 95 百分位的 45% 和导入阈值二者较高值建立硬二值蒙版；原贴图不变。仅保留主要岛及达到面积下限的次级岛，从源头消除羽化尾/透明尘点产生的四点闭环。再只使用 Umamo 的非孔洞外环作为控制数据，并按外环扫描线实心填充内部透明区。控制环以 1px 容差化简，经两次限幅低通和 1.15px 外法线偏移后，构造带转角限幅手柄的周期三次 Bézier 链；若曲线自交则逐级缩短手柄。曲线先高分辨率求值，再按 0.85px 弦高误差做曲率加权弧长采样：平滑区与内部错列网格间距相同，曲率区最高加密 12 倍。转角必须跨越随网格间距变化的物理窗口仍然显著，才被认定为指尖/指缝等结构点并成为同一有序曲线的强制分段点；单像素阶梯不会触发。输出环不后插控制点、不执行去重。每个外环独立耳切并插入同间距错列 Steiner 点；受约束 Lawson 翻边不再固定为 14 轮，而以三角形数量决定上限并提前收敛，解决长窄发束只能每轮推进一条对角线而残留长扇边的问题。收敛后遍历非保护内部边，超过实际网格间距 1.72 倍时从中点拆分并再次 Delaunay 化，最多补 512 个质量点。所有 `(i,i+1)` 闭环边进入保护集合、禁止拆分，且在输出前逐条验证存在，因此优化不能跨序连接或吞掉边界。不同不透明岛独立三角化，孔洞不形成拓扑。退化、自交或约束丢失时才回退矩形网格 |
| 6. 贴图 | Stretchy 使用 MaxRects 并 remap UV；Umamo 接收贴图页和每画元页号 | 使用确定性多页 shelf packer，保持 1:1 像素、不翻转 V、2px padding、页边长自动提升到能容纳最大层；输出 PNG 和画元页映射 |
| 7. 层级 | 手工九轴区分“头部容器”和“面部表面”：脸是头部子集，头发/头饰不能进入脸部 Roll；Umamo 区分 Part、Rotation、Warp 和 Drawable | 固定主链：BodyXY → BodyZ/Breath → HeadRotation → HeadContainer。HeadContainer 先做小幅头壳 X/Y 跟随；其下并列 FaceNinePose、HairFrontFollow、HairBackFollow 和头部附件，面部再叠加强九轴。只有 FaceNinePose 包含 EyeShape、IrisPreserve、BrowShape、NoseShape、MouthShape、EarOcclusion。组织 Part 同步拆为 Head → Face/FrontHair/BackHair/Accessories。Rotation 只有 HeadContainer 这个像素空间子节点，后续 Warp 全部使用规范化父空间 |
| 8. 九轴面部 | 建模师将九轴理解为 `K00+X+Y+Cxy`：X 的局部体积来自连续位移曲线梯度，Y 是纬线密度与 V/^ 曲率，四角专门修 XY 联动问题 | AngleX 扩为 ±45，AngleY 为 ±30。双眼中点+鼻位确定 X 中心，眼线与嘴线确定 Y 中心。X 使用 C1 分段位移场：近侧轮廓短展开、近眼区域为宽平台、远侧连续下降，避免大侧角把近眼拉宽，同时保留远侧透视压缩；Y 在低头时集中并形成 V、抬头时分散并形成 ^。四角以带符号的 `yaw*pitch` 修正偏斜轮廓、中心线和五官冲突 |
| 9. 身体动作 | Stretchy 有躯干链和 body analyzer；Anime 有视差、呼吸与胸部效果 | BodyAngleX/Y 4×6 全身 Warp，BodyAngleZ 与 Breath 4×6 次级 Warp；BodyAngleX 以钟形纵向包络移动躯干，并用左右端点为零的内部曲线表达方向性空间 Roll，每行轮廓宽度保持不变；BodyAngleY 使用上下端点与中线均为零的纵向 S 曲线重排内部纬线，总高度保持不变。两轴的正负关键形态严格镜像，不再把有符号角度误作全局缩放；上身钟形权重控制呼吸膨胀，头部链自然继承身体动作 |
| 10. 五官二次修形 | 手工流程要求五官先随脸面走，再脱离脸面修形；不同部件具有不同感知深度和二维保持率 | 鼻根到鼻尖使用递增深度，鼻位移 > 嘴 > 眼；眼睛采用“近侧约保持、远侧受限缩小”的专用宽度曲线，眉毛使用另一组较强系数。眼/眉共享透视平面约束：Y=0 的二次修正线斜率严格为零，四角斜率仅来自 `yaw×pitch`，局部左右边与双中心连线保持平行，X 或 Y 反号时斜率随之反号。瞳孔继承眼 Warp，避免重复透视剪切；嘴使用中心快、近角慢、远角更慢的曲线；完整 mouth 以最大张口为 OpenY=1，OpenY=0 围绕中线压到约 1.25px，MouthForm 改变宽度与嘴角曲率；耳朵为负深度且远侧透明度最低约 0.52。眨眼、眉 Y、嘴型/开合继续在画元层与这些父 Warp 组合 |
| 11. 遮罩 | Cubism 使用 Drawable mask；Stretchy WebGL 有自己的剪裁 | 同侧 iris 以 eyewhite 画元作为 `maskedBy`，闭眼时保持瞳孔原始网格并由收缩的眼白遮罩裁掉；睫毛按逐列透明度中心轨迹弯成更深的 U 形、两端减少位移并仅轻微变细，与眼白中心线重合后覆盖眼白；显式 `tooth-t`、`tooth-b`、`tongue` 以匹配的 mouth 画元作为 `maskedBy`，只做闭口渐隐而不建立复杂内部形变 |
| 12. 头发物理 | Stretchy/Hiyori 使用发根固定、`v³` 发梢梯度；短前发摆长 3/Scale 1.522，长后发摆长 15/Scale 2.061，并以头/身体 X、Z 为输入 | 前后发各拆成 Head-follow Warp 与 Physics-tip Warp，完全绕过 FaceNinePose。发梢摆幅按 `min(width,height)` 缩放，防止短宽刘海整块漂浮。左右 Sway 对参数保持奇对称，纵向 Lift 使用连续的 `swing²` 偶对称曲线，两侧摆动等量收短；Head-follow 的 AngleY 权重改为上下端点等值的正弦拱形，保留内部深度而不改变整组高度。physics3 使用 AngleX/AngleZ/BodyAngleX/BodyAngleZ 四输入和独立短/长摆；MOC3 由 runtime 积分，CMO3 写入相同规则的可编辑物理设置 |
| 13. 动作 | Anime 在运行时驱动动作；live2dConverter 能按 motion3 时间恢复姿态 | 自动生成 6 秒循环 idle.motion3：呼吸、轻微头/身体摆动和一次眨眼，并在 model3 的 `Idle` 组中接线 |
| 14. 参数组织 | live2dConverter 可 basic/smart/flat 分组并从 cdi3/链追踪名字；Umamo 能写 parameter tree 和 links | 新模型没有旧链可追踪，因此直接采用 Cubism 标准 ID；建立 Face/Eyes/Brows/Mouth/Body/Physics 显示组，XY 成对参数写入 combined links |
| 15. MOC3 导出 | Umamo `Moc3Export` 降低 PuppetModel；`Moc3Sidecars` 生成文件族；live2dConverter 用于交叉检查已有文件的参数/层级解释 | 以 MOC5 输出 moc、model3、cdi3、全部贴图、physics、motion；EyeBlink/LipSync 接线；为 Cubism 4.2+ 无条件生成默认颜色行；验证清单引用、格式回读和默认姿态几何，开发验收再过官方 Core consistency gate |
| 16. CMO3 导出 | Umamo 构造 CAFF/主 XML/PNG 图像链，再将 PuppetModel reconcile 到新图；Stretchy 直接写 `CPhysicsSettingsSourceSet` | 使用 fresh graph 转换后写入参数 GUID 关联的前/后发 `CPhysicsSettingsSource`，再序列化并回读确认四输入、输出比例和摆锤顶点均保留；绘制顺序强制整数，默认姿态再次求值。PSD 原始编辑对象不能映射，源层由 atlas 切片回构并明确提示 |

## 重复功能的取舍

1. PSD 只保留 Umamo 一套解析器。Stretchy 与 Anime 的浏览器解析链不再重复。
2. 图层分类以 Stretchy 的 See-Through 规范为主，Anime 只补充别名和空间启发式。
3. Anime/Stretchy 的投影只保留为低频体积约束；最终脸型由面部空间预算、部件深度差、二维形状保持和 XY 交叉修正决定，全部烘焙为标准 Cubism keyforms。
4. 参数、层级、空间和格式只通过 PuppetModel 表达；MOC3 与 CMO3 不各自建立一套 Rig 数据。
5. live2dConverter 的强项是已有模型的逆向转换，本项目面对的是新 PSD，因此采用其可审计的分组/命名/降级报告思想，而不调用其转换引擎。

## 坐标与格式不变量

- PSD/贴图：左上原点、Y 向下，UV 的 V 不翻转。
- PuppetModel：根 Warp 的控制点在画布空间；Warp 的子 Warp/画元 rest mesh 在父 Warp 归一化局部空间；Rotation 枢轴在其父 Warp 归一化空间，而 Rotation 的直接子节点使用以枢轴为原点、受 scale 影响的像素偏移。
- 写 CMO3 前使用 `restMeshesToCanvasSpace`，因为可编辑基准网格使用画布空间，而关键形态绝对量仍保持父空间。
- MOC3 是文件族；`model3.json` 的每个相对引用必须在同一导出 bundle 中闭合。
- MOC3 内部回读不是官方兼容性的充分条件；MOC5 开发验收还需通过官方 Core 的一致性检查。v4+ 基础颜色表即使全为默认值也不能省略。
- 生成模型及两种格式回读模型都必须在默认参数姿态下逐画元求值；相对 PSD 边界发生大幅位移、缩放、塌缩或出现 NaN 时导出失败。
- 静态 ArtMesh 的 geometry grid 必须为零轴单单元，不能用 `AngleX=0` 单点轴模拟常量；生成后及两种格式回读后必须求值 AngleX=±45、AngleY=±30 四个极限姿态，画元缺失、透明度意外归零、塌缩或异常放大均视为导出失败。
- 所有输出路径先规范化并检查仍位于用户选择的输出根目录，避免清单名越界。

## 后续质量提升方向

- 使用 alpha 距离场定位眼裂、嘴角和发根，减少仅靠包围盒带来的偏差。
- 给 GUI 增加语义手动覆盖和锚点拖拽；自动结果不可靠时无需改 PSD 名称。
- 从原 PSD 构建 CMO3 原生 source image web，以消除 atlas 回构层的 `MissingSourceArt` 限制。
- 加入参数滑杆驱动的本地 Rig 预览和碰撞/穿帮检查。
