# 参考实现与 AutoLive2D 的逐流程对比

本文记录选择依据和实际落点，避免把多个参考项目的重复功能机械叠加。

## 参考项目定位

| 项目 | 擅长部分 | 本项目如何使用 |
| --- | --- | --- |
| `umamo` | PSD 中立源图模型、PuppetModel、CMO3/MOC3 读写、空间变换和 sidecar 文件族 | 直接作为 Gradle composite 模块依赖；作为格式与运行时模型的唯一事实源 |
| `stretchystudio` | See-Through 命名整理、连通域左右拆分、图像分析、轮廓采样/Delaunay、贴图、简单自动 Rig | 重写语义流程、alpha 分析与左右拆分；保留“连续面部 Warp”的设计经验；没有复制 Web UI |
| `Anime2.5DRig` | 多语言部件别名、骨架锚点、椭球壳 2.5D 投影、头发摆动和运行时动作 | 重写别名表、椭球投影与头发控制思路，并把实时 WebGL 算法烘焙成 Cubism 关键形态 |
| `live2dConverter`（Quadrism） | MOC3/CMO3 转换时的参数命名、分组、层级追踪、动作姿态读取和容错报告 | 参考其“ID/显示名分离、组和组合参数进入 cdi3、转换必须报告降级”的原则；未链接 Rust 代码 |
| `live2d_docs` | PSD、MOC3、CMO3 容器、记录布局、坐标与文件族说明 | 约束输入/输出、大小端、坐标空间、清单闭包和导出后回读校验 |

## 分阶段比较

| 阶段 | 参考实现 | AutoLive2D 的实现与选择 |
| --- | --- | --- |
| 1. PSD 解码 | Umamo 完整解析 PSD 头、图层记录、Raw/RLE/ZIP 通道并生成 `SourceArt`；Stretchy/Anime 侧重浏览器 PSD 库 | 使用 Umamo `PsdReader`，一次获得裁切 RGBA、层序、透明度、可见性、组路径、裁切标记和混合模式，避免维护第二个 PSD 解码器 |
| 2. See-Through 分类 | Stretchy `psdOrganizer` 使用固定英文标签；Anime 的 rigger 有英/中/日别名并做 NFKC 归一化 | 合并二者：NFKC、小写、copy/编号清理、最长前缀匹配、L/R/左/右解析；保留未知层而不是拒绝导入 |
| 3. 双侧拆分 | Stretchy `splitLR` 使用 8 邻域 union-find，取最大两块；Anime 同样用连通域和画面中心判断侧别 | 使用 8 邻域 BFS，过滤小于 12 像素噪点，取两大且必须分居面部中心两侧的区域；输出独立裁切 raster 与稳定虚拟 ID |
| 4. 人体/面部锚点 | Stretchy 通过 alpha 并集、脊柱、肩/髋估算；Anime 使用语义包围盒和启发式锚点 | alpha 非透明边界联合；face 优先显式图层，缺失时由头部层估计；topwear/bottomwear 修正肩与髋；所有降级写入报告 |
| 5. 网格 | Stretchy 轮廓膨胀、内部抖动采样、Delaunay；Anime 为实时效果构建规则网格；Umamo 需要明确 positions/UV/indices | 采用 Stretchy 的轮廓/内部/Delaunay 流程并重写为确定性 Kotlin：Umamo alpha 轮廓按周长分配等弧长边界点，内部使用三角形错列采样，自实现 Bowyer-Watson；额外检测三角形中心、边中点和内部采样点，删除跨透明凹槽/孔洞的连接。大图层自动提高间距控制预算，只有退化图层回退矩形网格 |
| 6. 贴图 | Stretchy 使用 MaxRects 并 remap UV；Umamo 接收贴图页和每画元页号 | 使用确定性多页 shelf packer，保持 1:1 像素、不翻转 V、2px padding、页边长自动提升到能容纳最大层；输出 PNG 和画元页映射 |
| 7. 层级 | Stretchy/Anime 根据身体部位构建简单骨架；Umamo 运行时区分 Part、Rotation、Warp 和 Drawable | 固定链：BodyXY → BodyZ/Breath → HeadRotation → Face3D；视线和前/后发作为 Face3D 子 Warp；画元依语义挂接，Part 分 Head/Body/Extra。Rotation 枢轴使用父 Warp 的归一化坐标，而直接子 Warp 使用相对枢轴的像素偏移，不能混用 |
| 8. 面部 3D | Anime 将二维点提升到 3D 壳后做 Yaw/Pitch；Stretchy 的迭代结论是单一连续 Warp，且完整椭球+透视会让 AngleY 过度压缩 | 8×8 连续 Warp；采用 Stretchy 验证后的横向圆柱穹顶，AngleX=±30 对应 ±15° Yaw，AngleY=±30 对应 ±8° Pitch，去除透视缩放。正 AngleY 在 Y 向下画布中向上；穹顶 Z 只随横向位置变化，使眼眉嘴沿脸部曲率连续弯曲，同时避免额头/下巴被夸张拉伸 |
| 9. 身体动作 | Stretchy 有躯干链和 body analyzer；Anime 有视差、呼吸与胸部效果 | BodyAngleX/Y 4×6 全身 Warp，BodyAngleZ 与 Breath 4×6 次级 Warp；上身钟形权重控制呼吸膨胀，头部链自然继承身体动作 |
| 10. 眼睛/眉/嘴 | Stretchy 简单 rig 为眼闭和嘴开建立形变；Anime 有表情控制 | 左右 EyeOpen 压合网格，闭眼图层反向透明度；EyeBallXY 控制视线 Warp；眉毛独立 Y；MouthForm×MouthOpenY 二维形变并对开闭嘴图层交叉淡化 |
| 11. 遮罩 | Cubism 使用 Drawable mask；Stretchy WebGL 有自己的剪裁 | 同侧 iris 以 eyewhite 画元作为 `maskedBy`，合并眼白则回退到无侧别遮罩 |
| 12. 头发物理 | Anime 的弹性链实时积分；Stretchy 也有简化动态控制 | Rig 内生成 Front/Back Hair 参数 Warp；MOC3 文件族另生成 physics3 三粒子链，以 AngleX 和 BodyAngleX 为输入。物理交给 Cubism runtime 积分 |
| 13. 动作 | Anime 在运行时驱动动作；live2dConverter 能按 motion3 时间恢复姿态 | 自动生成 6 秒循环 idle.motion3：呼吸、轻微头/身体摆动和一次眨眼，并在 model3 的 `Idle` 组中接线 |
| 14. 参数组织 | live2dConverter 可 basic/smart/flat 分组并从 cdi3/链追踪名字；Umamo 能写 parameter tree 和 links | 新模型没有旧链可追踪，因此直接采用 Cubism 标准 ID；建立 Face/Eyes/Brows/Mouth/Body/Physics 显示组，XY 成对参数写入 combined links |
| 15. MOC3 导出 | Umamo `Moc3Export` 降低 PuppetModel；`Moc3Sidecars` 生成文件族；live2dConverter 用于交叉检查已有文件的参数/层级解释 | 以 MOC5 输出 moc、model3、cdi3、全部贴图、physics、motion；EyeBlink/LipSync 接线；为 Cubism 4.2+ 无条件生成默认颜色行；验证清单引用、格式回读和默认姿态几何，开发验收再过官方 Core consistency gate |
| 16. CMO3 导出 | Umamo 构造 CAFF/主 XML/PNG 图像链，再将 PuppetModel reconcile 到新图 | 使用 fresh graph 转换并回读；绘制顺序强制整数以避免 CMO3 降级；回读后再次求值默认姿态，防止 ID 尚在但面部子树已塌缩。PSD 原始编辑对象不能映射，源层由 atlas 切片回构并明确提示 |

## 重复功能的取舍

1. PSD 只保留 Umamo 一套解析器。Stretchy 与 Anime 的浏览器解析链不再重复。
2. 图层分类以 Stretchy 的 See-Through 规范为主，Anime 只补充别名和空间启发式。
3. 2.5D 保留 Anime 的 3D 旋转核心，并采用 Stretchy 多轮调参后的圆柱穹顶、15°/8° 限幅与“单个连续 Face Warp”组织，最终转换为 Cubism keyforms，不保留实时 WebGL 渲染器。
4. 参数、层级、空间和格式只通过 PuppetModel 表达；MOC3 与 CMO3 不各自建立一套 Rig 数据。
5. live2dConverter 的强项是已有模型的逆向转换，本项目面对的是新 PSD，因此采用其可审计的分组/命名/降级报告思想，而不调用其转换引擎。

## 坐标与格式不变量

- PSD/贴图：左上原点、Y 向下，UV 的 V 不翻转。
- PuppetModel：根 Warp 的控制点在画布空间；Warp 的子 Warp/画元 rest mesh 在父 Warp 归一化局部空间；Rotation 枢轴在其父 Warp 归一化空间，而 Rotation 的直接子节点使用以枢轴为原点、受 scale 影响的像素偏移。
- 写 CMO3 前使用 `restMeshesToCanvasSpace`，因为可编辑基准网格使用画布空间，而关键形态绝对量仍保持父空间。
- MOC3 是文件族；`model3.json` 的每个相对引用必须在同一导出 bundle 中闭合。
- MOC3 内部回读不是官方兼容性的充分条件；MOC5 开发验收还需通过官方 Core 的一致性检查。v4+ 基础颜色表即使全为默认值也不能省略。
- 生成模型及两种格式回读模型都必须在默认参数姿态下逐画元求值；相对 PSD 边界发生大幅位移、缩放、塌缩或出现 NaN 时导出失败。
- 静态 ArtMesh 的 geometry grid 必须为零轴单单元，不能用 `AngleX=0` 单点轴模拟常量；生成后及两种格式回读后必须求值 AngleX/AngleY 四个 ±30 极限姿态，画元缺失、透明度意外归零、塌缩或异常放大均视为导出失败。
- 所有输出路径先规范化并检查仍位于用户选择的输出根目录，避免清单名越界。

## 后续质量提升方向

- 使用 alpha 距离场定位眼裂、嘴角和发根，减少仅靠包围盒带来的偏差。
- 给 GUI 增加语义手动覆盖和锚点拖拽；自动结果不可靠时无需改 PSD 名称。
- 从原 PSD 构建 CMO3 原生 source image web，以消除 atlas 回构层的 `MissingSourceArt` 限制。
- 加入参数滑杆驱动的本地 Rig 预览和碰撞/穿帮检查。
