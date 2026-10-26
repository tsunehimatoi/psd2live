# `.p2lrt` 2.0 格式

[运行时](RUNTIME.md) · [工程、运行时与导出边界](RUNTIME_EXPORT_ARCHITECTURE_AND_GAPS.md)

状态：容器、核心块、`PGUI`、`META`、扩展块 `BONE`、`SKIN`、`COLL`、`SIMS` 和附带块 `EXPR`、`HITA`、`UDAT`、`POSE` 已实现，导出默认写版本 2。读取器支持全部压缩方式、数组编码与贴图页类型；写出器可写 deflate 与 zstd 压缩，数组为 f32，贴图页为内嵌 PNG。

## 目标

- **一次性定下演进规则**：2.0 之后新增功能只加块，不改已有记录布局，不再提升主版本。
- **核心层与 Cubism 等价**：只读核心块即可完整播放，结果与 v1 逐位一致，语义与 moc3 一一对应。
- **扩展层可选**：运行时蒙皮、顶点模拟、碰撞等高级数据放在可选块中，不认识它们的运行时退回 Cubism 效果（同文件双模式）。
- **便于加载**：随机访问、按需加载、数组零拷贝；单文件可携带现有边车内容。
- **可复现**：同一 IR 写出字节完全相同，不含时间戳等易变数据。

非目标：通用场景格式、可编辑工程格式（工程仍是 `.psd2live`）、加密或混淆。

## 容器

小端。文件由文件头、块目录（TOC）和块数据组成：

```
Header  (32 字节)
  u8[8]  magic        "P2LRT\0\0\0"
  u16    major        = 2
  u16    minor        写出器所知的最高次版本，仅作信息
  u32    flags        bit0 名称已剥离；其余保留，写 0
  u32    chunk_count
  u32    toc_offset   = 32
  u32    reserved[2]  写 0
TOC     chunk_count × 40 字节
  u8[4]  tag          四字符 ASCII，大写为规范块，小写为厂商/私有块
  u16    version      块版本（布局与求值语义）
  u16    flags        bit0 必需；bit1 有 CRC；bit2–3 压缩方式（见下）；其余写 0
  u64    offset       块数据相对文件头的偏移，16 字节对齐
  u64    length       块在文件中存储的字节数（不含填充；压缩时为压缩后长度）
  u64    raw_length   解压后的字节数；未压缩时等于 length
  u32    crc32        对存储字节（压缩后）计算的 IEEE CRC32（bit1 为 0 时写 0）
  u32    reserved     写 0
Chunks  各块数据，块间用 0 填充到 16 字节对齐
```

- 文件头的 `magic` 前 5 字节与 v1 相同；v1 在 `magic` 之后是 `u32 version = 1`，v2 在同一位置是 `u16 major = 2, u16 minor`。读取器读出 u32 后，值为 1 按 v1 解析，低 16 位为 2 按 v2 解析，其余拒绝。
- 同一个规范标签在一个文件中最多出现一次；块在文件中的顺序不限，写出器按本文“核心块”表的顺序写出。
- 块之间不允许重叠，所有块必须位于文件内；块之后、文件末尾之外不允许有多余数据（填充除外）。
- 偏移和长度为 u64，读取器在超出自身地址宽度（如 WASM32）时拒绝该文件，而不是截断。

### 块压缩

| flags bit2–3 | 方式 | 存储内容 |
| --- | --- | --- |
| 0 | 无 | 原始块数据 |
| 1 | deflate | zlib 流（RFC 1950） |
| 2 | zstd | 单个 zstd 帧（RFC 8878），不使用外部字典 |
| 3 | 保留 | 读取器拒绝 |

- 读取器必须支持 deflate 和 zstd。解压后长度必须等于 `raw_length`，否则拒绝；解压结果放入 16 字节对齐的缓冲区，之后按未压缩块解析。
- 压缩只改变存储，不改变块内容；块内的偏移与数组对齐都相对解压后的数据。
- 压缩块无法零拷贝；需要内存映射或流式加载的宿主应选择不压缩的文件。
- 写出器默认不压缩。导出选项开启压缩时，只压缩能变小的块；已压缩的贴图负载（PNG、KTX2）所在的 `TEXR` 不压缩。若压缩后不小于原数据，该块按未压缩写出。
- 字节稳定只要求同一写出器版本、同一压缩设置下成立；一致性比较以解压后的块内容为准。

### 兼容规则

| 情况 | 读取器行为 |
| --- | --- |
| `major` 不是 1 或 2 | 拒绝 |
| 认识的标签，`version` ≤ 支持的版本 | 按对应版本解析，必须正好读完 `length` 字节 |
| 认识的标签，`version` 更高 | 视为不认识的块 |
| 不认识的块，非必需 | 跳过 |
| 不认识的块，必需 | 拒绝，错误信息列出该标签（“需要特性 XXXX”） |
| 缺少必需的核心块 | 拒绝 |
| CRC 不符（仅在宿主要求校验时检查） | 拒绝 |
| 压缩方式为保留值，或解压失败、解压长度不等于 `raw_length` | 拒绝 |

`minor` 不参与判断，只用于诊断。

### 演进规则

1. 同一主版本内，已发布的块版本布局和求值语义永不改变。
2. 给已有对象增加属性：新增一个“平行块”，按对象下标存这一列，而不是改原记录。例如给网格加蒙皮写 `SKIN` 块（网格下标 → 蒙皮数据），`MESH` 不变。
3. 块内不允许“某个标志为 1 时多读若干字节”这类条件字段；可选内容一律用独立块或显式长度包裹。
4. 改变某块的求值语义必须提升该块版本，且旧版本仍按旧语义求值。
5. 核心块永远不依赖扩展块；扩展块只能引用核心块和更早定义的扩展块。
6. 厂商或实验块使用小写标签，必须为非必需块，规范块使用大写标签。

## 基础编码

| 名称 | 编码 |
| --- | --- |
| `u8/u16/u32/i32/f32` | 小端；f32 必须为有限值 |
| `ref` | u32 下标；可空引用用 i32，-1 为空 |
| `sref` | u32 字符串表下标（`STRS`），`0xFFFFFFFF` 为空 |
| `rgb` | 3 × f32 |
| `str` | 只在 `STRS` 内部使用；其他位置的字符串一律是 `sref` |
| `array<T>` | 先用 0 填充到块内 4 字节对齐，再写 `u8 codec, u8 reserved[3], u32 count`，随后 `count` 个元素 |
| `grid<F>` | 同 v1：`u16 axes`（`0xFFFF` 表示无网格）× `{ ref parameter, array<f32> keys }`，`u32 cells` × `{ u16 coordinate[axes], F }` |
| `channels` | 同 v1：`u8 n` × `{ u8 channel, u8 kind, grid<value> }`；channel 依次为 DrawOrder、Opacity、MultiplyColor、ScreenColor、FlipX、FlipY、GlueIntensity；kind 0 标量、1 颜色、2 标志 |
| `bindings<S>` | 同 v1：`u16 n` × `{ ref parameter, array<f32> keys, u32 neutral, u8 present + S 每个关键点, u16 n × { ref limit_parameter, u16 n × { f32 value, f32 weight } } }` |

数组编码（`codec`）：

| 值 | 元素 | 用途 |
| --- | --- | --- |
| 0 | f32 | 默认；所有一致性测试使用 |
| 1 | f16 | 可选的体积优化（位置、偏移） |
| 2 | unorm16 | 可选的体积优化（UV，范围 [0,1]） |
| 16 | u16 | 索引，顶点数 ≤ 65535 时 |
| 17 | u32 | 索引 |
| 32 | u8 | 字节串（贴图负载） |

浮点数组的 codec 1、2 只在宿主显式选择“发布体积优化”时写出；读取器必须支持全部编码，解码后与 f32 同等对待。数组自行对齐：头部从块内 4 字节对齐处开始、长 8 字节，元素因此也 4 字节对齐；块本身 16 字节对齐，所以 codec 0/16/17 的数组可直接按切片读取。除数组前的填充外，记录内不再有其他填充，标量可以不对齐。

## 核心块

全部为必需块（标记 bit0），块版本除 `PHYS` 外均为 1，求值语义即 [运行时](RUNTIME.md) “求值规则”“物理”“动作片段”各节。字段顺序和计数宽度与 v1 相同，差别只有：字符串改为 `sref`，浮点数组、索引和字节串改为 `array<T>`，贴图页增加类型。

| 标签 | 内容 | 必须存在 |
| --- | --- | --- |
| `STRS` | 字符串表 | 是 |
| `CANV` | 画布：f32 width, height, originX, originY, pixelsPerUnit（-1 无） | 是 |
| `PARM` | 参数 | 是（可为 0 个） |
| `DEFM` | 变形器，父级在前 | 是（可为 0 个） |
| `PART` | 部件 | 是 |
| `MESH` | 网格 | 是 |
| `GLUE` | Glue | 否（缺省为 0 个） |
| `DRAW` | 绘制树 | 是 |
| `TEXR` | 贴图页 | 否（缺省为无贴图） |
| `PHYS` | 物理组 | 否 |
| `CLIP` | 动作片段 | 否 |
| `ROLE` | 参数角色 | 否 |

动作片段的事件、非参数曲线与曲线淡入淡出在非必需的附带块 `CEXT` 中（见下文“附带块”）。

“必须存在”为否的块缺失时按空集处理；存在时仍标记为必需，因为它们参与核心求值。

### `STRS`

```
u32 count
u32 offsets[count + 1]   每个字符串在 blob 中的起止偏移
u8  blob[...]            UTF-8，不以 0 结尾
```

写出器去重。文件头 flags bit0（名称已剥离）为 1 时，所有 `name` 字段为空 `sref`，`id` 仍保留，宿主按 id 查找参数和网格。

### `PARM`

`u32 n` × `{ sref id, sref name, f32 min, max, default, u8 flags (1 blend, 2 repeat) }`

### `DEFM`

`u32 n` × 变形器，父级必须在子级之前：

```
u8 kind (0 Warp, 1 Rotation), sref id, i32 parent, i32 part,
u8 flags (1 visible, 2 enabled, 4 flipX, 8 flipY, 16 bilinear),
f32 opacity, rgb multiply, rgb screen
Warp:     u32 columns, rows, grid<array<f32> points>, channels,
          bindings<{ array<f32> points, f32 opacity, rgb multiply, rgb screen }>
Rotation: f32 baseAngle, grid<{ f32 x, y, angle, scale }>, channels,
          bindings<{ f32 x, y, angle, scale, u8 flipX, u8 flipY, f32 opacity, rgb multiply, rgb screen }>
```

### `PART`

```
u32 n × { sref id, sref name, u8 flags (1 visible, 2 sketch), u8 groupMode, i32 drawOrder,
          u32 n × { u8 kind (0 part, 1 mesh), ref target },
          channels, composite,
          bindings<{ f32 drawOrder, f32 opacity, rgb multiply, rgb screen }> }
composite = { u8 blend, u8 alphaBlend, u32 n × ref maskMesh, u32 n × ref maskPart,
              u8 invertMask, f32 opacity, rgb multiply, rgb screen }
```

### `MESH`

```
u32 n × { sref id, sref name, i32 parent, u8 blend, u8 alphaBlend, u32 n × ref maskMesh,
          u8 flags (1 invertMask, 2 culling, 4 visible, 8 geometry), i32 texturePage,
          [geometry] array<f32> positions, array<f32> uvs, array<u16|u32> indices,
          grid<array<f32> deltas>, channels,
          f32 drawOrder, f32 opacity, rgb multiply, rgb screen,
          bindings<{ array<f32> deltas, f32 drawOrder, f32 opacity, rgb multiply, rgb screen }> }
```

`[geometry]` 仅在 flags bit3 为 1 时出现；`CLIP` 中 Bezier 段的控制点同样只在段类型为 1 时出现。这两处是 v1 遗留的条件字段，为与 v1 一一对应而保留，是规则 3 仅有的例外。

### `GLUE`

`u32 n × { sref id, ref meshA, ref meshB, f32 intensity, u32 n × { u32 a, u32 b, f32 weightA, f32 weightB }, channels }`

### `DRAW`

绘制树根组，递归：`{ i32 part, i32 drawOrder, channels, u8 hasComposite, [composite], u32 n × { u8 kind (0 group, 1 mesh), group | ref mesh } }`

### `TEXR`

```
u32 n × { u8 kind, u32 width, height, payload }
kind 0  PNG       payload = array<u8> png      所有运行时必须支持
kind 1  KTX2      payload = array<u8> ktx2     可选；不支持的运行时报告该页不可用
kind 2  外部文件  payload = sref uri           相对模型文件的路径，由宿主加载
```

`p2l_texture_png` 对非 PNG 页返回空；新增 `p2l_texture_info(i)` 返回 kind、宽高与 uri。

### `PHYS`

`f32 fps (0 运行时默认), u32 n × { sref id, sref name, inputs, outputs, segments, normalization }`，各字段同 v1。

输入与输出的 `u8 source` 为 0 横向（X）、1 角度、2 纵向（Y，Cubism physics3 的 `Y`）。块版本 2 允许纵向：写出器只在用到纵向时写版本 2，早于它的运行时据此报出“需要 PHYS 版本 2”，而不是读错；版本 1 的文件布局完全相同。纵向输入把归一化值加到根的 y 位移上，纵向输出读该顶点相对上一顶点的 y 偏移；与横向一样，平移输出的缩放为零（Cubism 的行为）。版本 1 文件（导出选项 `v1`）不写纵向输入输出，并在损失报告中注明。

### `CLIP`

同 v1，字符串改为 `sref`。

### `ROLE`

`u32 n × { sref role, u32 n × ref parameter }`

## 参数界面块 `PGUI`（非必需）

供宿主展示参数面板的元数据，不参与求值；不认识或不需要它的运行时直接跳过。块版本 1：

```
u32 n × { ref parameter, array<f32> keys }        吸附值：只列有吸附值的参数，keys 升序
u32 n × { ref horizontal, ref vertical }          二维摇杆：两个参数组成一个平面控件
u32 n × node                                      参数分组树，先序展开
node = { u8 kind (0 参数, 1 分组), u8 open, u8 label (0 无, 1 预设, 2 自定义), u8 reserved,
         i32 parameter (分组为 -1), sref id, sref name, sref preset, u32 argb, u32 children }
```

- 节点为定长记录，所有字段总是写出：参数节点的 `id`、`name`、`preset` 为空，分组节点的 `parameter` 为 -1；`label` 不是 1 时 `preset` 为空，不是 2 时 `argb` 为 0。`children` 是紧随其后的直接子节点数。
- 文件头 flags bit0（名称已剥离）为 1 时，分组 `name` 为空。
- 未列入分组树的参数由宿主按 `PARM` 顺序放在末尾。
- `RigIR.restPose` 只用于以画布空间基础网格存储的格式（如 Spine），p2lrt 由参数直接求值，不写出。

## 信息块 `META`（非必需）

`u32 n × { sref key, sref value }`。规范键：`generator`、`generator_version`、`source_hash`（工程源数据哈希，用于缓存和诊断）、`capabilities`（以空格分隔的文件内扩展标签，供宿主在加载前展示）。不写时间戳，保证可复现。

## 求值阶段与扩展挂点

核心求值固定为以下阶段；扩展块只能在标明的挂点生效，块的规范必须写明它挂在哪个点、替换哪项贡献。

| 序号 | 阶段 | 核心内容 | 扩展挂点 |
| --- | --- | --- | --- |
| 1 | 参数 | 动作片段 → 程序化行为 → Cubism 摆锤 → 钳制 / 回绕 | **H1 参数覆盖**：被替换的参数在本次求值中取默认值，被替换的摆锤组跳过 |
| 2 | 变形器 | 父级在前，Warp / 旋转框架 | **H2 框架替换**：用精确合成的框架替换指定变形器（如折叠链接） |
| 3 | 骨骼帧 | 无（核心层没有骨骼） | **H3 骨骼帧**：从变形器框架或虚拟帧得到每根骨骼的画布变换，供 H4、碰撞体和宿主挂件使用 |
| 4 | 网格 | 静止位置 + 关键形偏移 + 混合形 → 父变换 | **H4 网格贡献替换**：指定网格的指定贡献（蒙皮轴关键形、指定混合形）被替换为扩展计算结果，其余照常 |
| 5 | Glue | 顶点对互拉 | 无 |
| 6 | 时间步后处理 | 无 | **H6 顶点模拟**：需要 dt，以本次求值的顶点为目标做定步长模拟，覆盖目标网格顶点；碰撞在此阶段内求解 |
| 7 | 绘制顺序 | 绘制树排序 | 无 |

挂点的共同规则：

- 未开启扩展时，所有挂点为空操作，代码路径与核心求值相同。
- H1 的覆盖作用于整次求值。写出器只能列出只驱动被替换几何的参数（如 `ParamSim*`），因此通道不受影响。
- `p2l_evaluate`（无 dt）不推进 H6，模拟网格显示上一次模拟状态，从未推进时显示目标。

### 扩展块的公共头

每个扩展块的负载以统一的覆盖声明开头，读取器可以在不理解块内容的情况下知道它会替换什么（用于诊断与 `META` 展示）：

```
u16 feature_bit           对应 p2l_set_advanced 的特性位
u16 hooks                 位集：H1=1, H2=2, H3=4, H4=8, H6=32
u32 n × ref parameter     H1 被覆盖为默认值的参数
u32 n × ref physicsGroup  H1 被跳过的摆锤组
u32 n × ref deformer      H2 被替换框架的变形器
u32 n × { ref mesh, u32 n × ref axisParameter, u32 n × u32 binding }
                          H4 被替换的贡献：这些关键形轴取默认值、这些混合形不计入
u32 n × u8[4] depends_on  依赖的其他扩展块标签
```

之后是块自己的内容。依赖块缺失或未开启时，该扩展自动停用。

### 特性位与模式开关

| 位 | 块 | 说明 |
| --- | --- | --- |
| 1 | `SKIN`（引用虚拟骨骼时需 `BONE`） | 运行时蒙皮 |
| 2 | `BONE` 的圆弧 | 精确链接（沿圆弧插值的枢轴） |
| 4 | `SIMS` | 顶点级实时模拟 |
| 8 | `COLL`（需 `SIMS`；随模拟开启） | 碰撞 |

- `p2l_advanced_available()` 返回文件携带且运行时支持的位；`p2l_set_advanced(bits)` 返回实际开启的位，0 为 Cubism 模式（默认）。切换时重置模拟状态。
- `p2l_bone_count / p2l_bone_id / p2l_bone_transform` 输出 H3 的骨骼帧。
- 时间步扩展必须定步长（步长写在块内）、单线程、固定遍历顺序，每次更新步数有上限并丢弃超出的时间，输出在最后两步之间插值，与 `PHYS` 的做法一致。

## 扩展块 `BONE`（版本 1）

```
公共头                      feature 2，hooks H2 | H3，deformers = 有圆弧的变形器
u32 n × deformer            虚拟骨骼：DEFM 格式的旋转变形器记录，第 i 个的下标为 n_DEFM + i；
                            父级是 DEFM 中的变形器或更早的虚拟骨骼
u32 n × { ref deformer, sref id }            宿主可挂接的骨骼（DEFM 或虚拟骨骼）
u32 n × { ref deformer, ref parameter, f32 cx, cy }   圆弧
```

- **虚拟骨骼**：骨架烘焙时没有网格直接挂接、被裁剪或折叠的骨骼。它们只在运行时蒙皮中求值，不参与核心求值。骨骼参数与角度的关系与烘焙前的旋转变形器一致：角度 = 参数值 × 方向，枢轴为骨头位置。
- **圆弧**：该旋转变形器的枢轴只在 `parameter` 一条轴上建键，且各键的位置落在以 (cx, cy) 为圆心（父空间）的同一圆上。精确链接开启时，相邻两键之间的枢轴位置按角度和半径插值，不再取弦。写出器只对四个及以上键、全部落在同一圆上、转向一致且每步小于半圈的枢轴写出圆弧。
- `p2l_bone_count / p2l_bone_id / p2l_bone_transform` 列出可挂接的骨骼；虚拟骨骼的变换按当前参数即时求出。

## 扩展块 `SKIN`（版本 1）

```
公共头                      feature 1，hooks H4，不替换任何贡献
u32 n × {
  ref mesh                  其父级是旋转变形器，且在 bones 中
  u32 n × { ref parameter, array<f32> keys }   蒙皮轴：烘焙时取样的参数及其键
  u32 n × ref deformer      骨骼：DEFM 或虚拟骨骼中的旋转变形器
  array<u16|u32> from, array<u16|u32> to, array<f32> weight   每顶点的两骨权重；长度为 0 时由运行时拟合
}
```

求值（H4，在网格的父空间、父变换之前）：

1. 每根骨骼 b 相对静止姿势搬运顶点的变换：A_b = H⁻¹ · B · B_rest⁻¹ · H_rest。其中 H 为网格父级（home）的框架，B 为骨骼框架，均为当前参数下的画布变换。
2. 两骨线性混合：LBS(v) = (1 − w) · A_from(v) · p + w · A_to(v) · p，p 为静止位置。
3. 在网格上加 LBS(当前值) − Σ_c w_c · LBS(c)。c 遍历当前值两侧的蒙皮轴键组合，w_c 是与烘焙相同的多线性权重；其余参数保持当前值。

这个修正在每个键组合处恰为零，因此高级模式在烘焙的每个键上与 Cubism 模式一致；键之间把烘焙的弦换成骨骼的弧。权重未给出时，运行时在开启时拟合：在全部键组合（最多 1024 个，否则逐轴）处，为每个顶点选择最能复现烘焙位置的骨骼对与权重，平局时取刚性跟随单根骨骼。

写出器为父级是旋转变形器、并在其下方（经旋转变形器相连，可含虚拟骨骼）有按网格轴建键的旋转变形器的网格写出 `SKIN`，最多 16 根骨骼、8 条轴。腿部网格挂在站姿 Warp 上，保持烘焙。

## 扩展块 `COLL`（版本 1）

```
公共头                      feature 8，hooks H6
u32 n × { sref id, u8 capsule, i32 deformer, i32 mesh, u32 vertexA, u32 vertexB,
          f32 ax, ay, bx, by, radiusA, radiusB, friction }
```

碰撞体是圆（capsule 为 0，只用 a 端）或从 a 到 b、半径从 radiusA 渐变到 radiusB 的胶囊，半径为画布像素。它的位置按以下方式之一确定：

- **跟随网格顶点**：mesh 不为 -1 时，两端取该网格的 vertexA / vertexB 在本次求值中的位置。编辑器导出的碰撞体都是这种，所以编辑器与运行时看到的位置相同。
- **跟随变形器**：deformer 不为 -1（可为虚拟骨骼）时，(ax, ay)、(bx, by) 是该变形器局部空间中的点。
- **固定**：两者都为 -1 时，两端是画布中的固定点。

friction 取 0–1，表示接触时抵消的滑动比例。碰撞只在模拟中生效：`SIMS` 的每个模拟列出它要避开的碰撞体。

## 扩展块 `SIMS`（版本 1）

```
公共头                      feature 4，hooks H1 | H4 | H6；parameters、physics groups 为各模拟替换项的并集
u32 n × {
  sref id, f32 fps, u32 substeps, f32 gravityX, gravityY, windX, windY, pinCompliance
  u32 n × { ref mesh, u32 vertexCount }                 目标网格，粒子按此顺序排列
  array<f32> invMass, damping, windFactor, pinWeight, goalCompliance, goalOffsetX, goalOffsetY
  array<u32> anchorMesh (0xFFFFFFFF 为跟随自身), array<u32> anchorVertex
  array<u32> stretchA, stretchB; array<f32> rest, compliance, compressionCompliance
  array<u32> triA, triB, triC; array<f32> areaCompliance
  array<u32> bendT1, bendT2; array<f32> bendCompliance
  array<u32> weldA, weldB; array<f32> weightA, weightB, weldCompliance
  array<u32> longParticle, longRoot; array<f32> maxDistance
  u32 n × ref parameter                                 本模拟替换的烘焙模式参数
  u32 n × u32 physicsGroup                              本模拟替换的摆锤组（PHYS 下标）
  u32 n × { ref parameter, array<f32> keys, u32 n × { ref mesh, keys × array<f32> offsets } }   烘焙加入的静态修正
  u32 n × u32 collider                                  COLL 下标
}
```

场景与编辑器的 XPBD 求解器（`XpbdSolver`）逐项对应。世界空间为画布 x、取负的画布 y（y 向上）。compliance 为负表示没有该约束（编辑器中的无穷大）。

开启模拟后：

- **H1**：替换的模式参数保持默认值，对应的摆锤组被跳过。
- **H4**：目标网格减去静态修正（各键之间线性插值，与烘焙写入关键形的方式一致）。此时求值出的目标网格就是未烘焙的绑定，作为每个粒子的目标；钉住的粒子跟随自身或所跟随网格顶点的位置。
- **H6**：`p2l_update` 按场景自身的 fps 定步长推进。每次更新最多 4 步，超出的时间丢弃，绘制在最后两步之间插值；`p2l_evaluate` 只绘制上一步。首次更新时粒子静止于当时的姿势。`p2l_sim_reset` 让下次更新重新开始，`p2l_sim_wind` 叠加宿主的风。

导出时（只对运行时与网页目标），编辑器在最终绑定上重建并校准每个已烘焙的模拟。在默认姿势下，模式参数为零、静态修正处于静止键，所以场景与烘焙时相同。

## 附带块（非必需，版本 1，无公共头）

### `EXPR`

```
u32 n × { sref id, sref name, f32 fadeIn, f32 fadeOut, u32 n × { ref parameter, u8 blend (0 Add, 1 Multiply, 2 Overwrite), f32 value } }
```

表情与 Cubism 的 exp3 一致。权重 w 为按正弦缓动的淡入淡出系数，各混合方式的计算：

- Add：值 + v·w
- Multiply：值 · (1 + (v − 1)·w)
- Overwrite：值 · (1 − w) + v·w

运行时一次播放一个表情，切换时上一个表情淡出。更新顺序为：动作片段 → 表情 → 程序化行为 → 物理。导出时，编辑器按绑定中存在的参数生成微笑、悲伤、生气、惊讶四个表情，模型有相应模块参数时再加害羞、困倦、阴沉，淡入淡出各 0.5 秒。

### `HITA`

```
u32 n × { sref id, sref name, u32 n × ref mesh }
```

点击区域覆盖其网格绘制出的范围。`p2l_hit_test(x, y)` 按文件顺序返回第一个有可见网格包含该画布点的区域。导出时写入文档设置的点击区域（见[导出目标](EXPORT_TARGETS.md#点击区域)），文档没有设置时生成 `HitAreaHead`（脸）与 `HitAreaBody`（上衣，否则颈或下装）。

### `UDAT`

```
u32 n × { u8 kind (0 = 网格), ref target, sref value }
```

网格的用户数据（来自导入的 CMO3），通过 `p2l_mesh_user_data` 读取。

### `POSE`

```
f32 fadeIn, u32 n × { u32 n × { ref part, u32 n × ref linkedPart } }
```

部件互斥组与 Cubism 的 pose3 一致：每组同时只显示一个部件，切换时被选中的部件在 fadeIn 秒内淡入，其余按 Cubism 的规则（φ = 0.5，背面透明度阈值 0.15）淡出；链接部件跟随所属部件。

它是求值之后的一步：网格透明度乘以其所有祖先部件的姿势透明度。不在任何组里的部件为 1，所以不带 `POSE` 的文件求值不变；核心求值中部件透明度仍不计入网格透明度。初始时每组显示第一个部件。

宿主通过 `p2l_pose_show(group, entry)` 切换。导出设置 `pose_groups` 以 `部件|部件; 部件|部件` 指定分组，`部件+链接部件` 表示链接。

### `CEXT`

```
u32 n × { ref clip,
          u32 n × { f32 time, sref value },                                    事件，按时间升序
          u32 n × { u8 target, i32 part, f32 fadeIn, f32 fadeOut,
                    f32 startTime, f32 startValue, u32 n × segment },          非参数曲线
          u32 n × { u32 curve, f32 fadeIn, f32 fadeOut } }                     片段自身曲线的淡入淡出
```

动作片段的扩展，对应 Cubism motion3 中参数曲线以外的内容；非必需，不认识它的运行时只播放 `CLIP` 中的参数曲线。每个片段至多一条记录。

- **事件**（motion3 的 `UserData`）：片段时间经过事件时间时由 `p2l_event_count` / `p2l_event` 报给宿主；循环片段每圈触发一次，跳转（`p2l_clip_seek`）越过的事件不触发。
- **非参数曲线**：`target` 1 部件透明度（`part` 为部件下标），2 整体透明度，3 眨眼效果，4 口型效果（后三者 `part` 写 -1）。曲线段编码同 `CLIP`。部件透明度与整体透明度按曲线值直接设置（不随淡入淡出，与 Cubism 相同），作为乘数作用在部件之下的网格与整个模型上；眨眼效果乘到 `EyeBlink` 角色参数上，口型效果加到 `LipSync` 角色参数上：片段有该参数曲线时作用于曲线值，没有时按片段权重作用于当前值。
- **淡入淡出**：负数表示沿用片段的设置。`curve` 是该曲线在 `CLIP` 中写出的序号（只计入文件中存在的参数）。有自身淡入淡出的曲线按 Cubism 的规则取权重：淡入因子用曲线的淡入（或片段的），淡出因子用曲线的淡出（或片段的），两者相乘。

扩展块的共同约束：

- 只替换或补充核心求值中的特定贡献（如修正关键形、`ParamSim*` 驱动的形状），参数、动作、行为与 Cubism 摆锤照常驱动。
- 默认关闭；宿主通过 `p2l_set_advanced(features)` 开启，关闭时求值结果与只有核心块的文件逐位一致。
- 写出器在核心块中始终保留对应的 Cubism 烘焙结果。

## 运行时与写出器

- **读取**：同时支持 v1 与 v2，两者解析为同一个内存结构 `Rig`，求值代码只有一份。
- **写出**：`P2lrtTarget` 默认写 v2；导出选项 `v1` 只写核心内容的 v1 布局，此时若有扩展数据，`CapabilityScan` 报告其以烘焙形式保留。`compress` 用 deflate 压缩能变小的块，`strip_names` 剥离名称。
- **能力查询**：新增 `p2l_format_support()`，返回支持的主版本和块标签及版本列表；`p2l_rig_load_ex(bytes, len, flags)` 的 flags bit0 要求校验 CRC。
- **压缩依赖**：Rust 读取端用纯 Rust 的 `miniz_oxide`（deflate）与 `ruzstd`（zstd 解码），可编译到 WASM，运行时只需解码。WASM 构建因此从 208 KB 增至 400 KB，其中版本 2 读取与 deflate 约 79 KB，zstd 约 113 KB。Kotlin 写出端 deflate 用 JDK `Deflater`；zstd 用纯 Java 的 aircompressor。
- **字节稳定**：同一 IR 两次写出字节相同；名称剥离和数组编码由导出选项决定，不受环境影响。

## 验证

- **v1/v2 逐位一致**：`RuntimeConformanceTool` 的全部随机模型、样例与物理轨迹，同时写出 v1 和 v2，运行时求值结果按 `to_bits()` 比较，顶点、透明度、颜色与绘制顺序必须完全相同。
- **读取器**：v1 接受；v2 最小文件接受；未知可选块跳过；未知必需块拒绝且报出标签；块越界、重叠、过读、欠读、重复规范标签、缺少必需核心块、错误对齐、CRC 不符（开启校验时）全部拒绝。Rust（`runtime/src/tests.rs`）与 Kotlin（`P2lrtTest.kt`）各一套。
- **可复现**：同一 IR、同一设置写两次字节相同；`STRS` 去重后与写出顺序无关的内容一致。
- **压缩**：同一模型以无压缩、deflate、zstd 写出，解压后各块内容逐字节相同，求值逐位一致；`raw_length` 不符、截断的压缩流、保留压缩方式均被拒绝；Kotlin 写、Rust 和 WASM 读三种方式都覆盖。
- **`PGUI`**：分组树、二维摇杆、吸附值写出后读回与 `RigIR` 一致；去掉 `PGUI` 后求值不变。
- **数组编码**：f16 / unorm16 模型与 f32 模型的顶点误差在编码精度内，并单独报告，不进入逐位一致语料。
- **跨语言**：Kotlin 写、Rust 读；WASM 构建通过同一组读取器测试。
- **扩展存在时 Cubism 模式不变**：带扩展块的文件在特性位为 0 时，与去掉扩展块的同一文件求值逐位一致；开启后再关闭也一致（无残留状态）。
- **扩展的参考实现**：每个扩展块都有 Kotlin 参考实现，纳入 `RuntimeConformanceTool` 的随机语料，与运行时对拍。

## 与高级模式路线的关系

本格式是运行时高级模式（运行时蒙皮、精确链接、顶点模拟、碰撞，同一文件双模式）的容器基础，对应其“阶段 0 格式管道”，并替代该路线最初设想的“v1 主体后追加分块”方案：核心内容也重新分块，所有内容遵守同一套演进规则。后续阶段各自定义 `BONE`/`SKIN`、`SIMS`、`COLL` 的块内布局，挂点、公共头和特性位以本文为准。

路线中视实测结果再定的几项，决定如下：

- **运行时 ARAP**：不实施。蒙皮修正在每个键上为零，高级模式在烘焙的每个键上与之完全一致（一致性样例全部为 0）；键之间已沿骨骼弧线。
- **挂在站姿 Warp 上的网格（腿）**：保持烘焙。腿在站姿 Warp 中弯折，骨架没有为腿骨生成旋转框架，蒙皮无从取帧。
- **四骨权重**：不需要。编辑器的蒙皮每个顶点最多跨两根骨骼。

## 已定事项

- 块压缩：定义 deflate 与 zstd 两种，读取器都必须支持；写出器默认不压缩，导出设置 `compress` 选 deflate，再加 `zstd` 选 zstd。
- 偏移与长度宽度：u64。
- 参数分组、二维摇杆与吸附值放入非必需的 `PGUI` 块，`PARM` 与 v1 一一对应；`restPose` 不写出。
