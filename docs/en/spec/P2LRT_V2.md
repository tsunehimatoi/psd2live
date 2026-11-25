<!-- lang: zh en ko ja -->
> [中文](../../zh/spec/P2LRT_V2.md) · **English** · [한국어](../../ko/spec/P2LRT_V2.md) · [日本語](../../ja/spec/P2LRT_V2.md)

# `.p2lrt` 2.0 format

[Runtime](RUNTIME.md)

Status: the container, the core chunks, `PGUI`, `META`, the extension chunks `BONE`, `SKIN`, `COLL`, and `SIMS`, and the attached chunks `EXPR`, `HITA`, `UDAT`, and `POSE` are implemented. Export writes version 2 by default. The reader supports every compression method, every array encoding, and every texture-page kind. The writer can write deflate and zstd compression. Arrays are f32, and texture pages are embedded PNG.

## Goals

- **Fix the evolution rules once**: after 2.0, a new feature only adds a chunk. It does not change the layout of an existing record, and the major version is not raised again.
- **The core layer is equivalent to Cubism**: reading only the core chunks plays the model in full. The result matches v1 bit for bit, and the semantics correspond one to one with moc3.
- **The extension layer is optional**: advanced data such as runtime skinning, vertex simulation, and collision live in optional chunks. A runtime that does not know them falls back to the Cubism result (two modes in the same file).
- **Easy to load**: random access, load on demand, and zero-copy arrays. A single file can carry the existing sidecar content.
- **Reproducible**: the same IR writes identical bytes. It contains no volatile data such as a timestamp.

Out of scope: a general scene format, an editable project format (a project is still `.psd2live`), and encryption or obfuscation.

## Container

Little-endian. A file is a header, a chunk directory (TOC), and the chunk data:

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

- The first 5 bytes of the header `magic` are the same as v1. In v1, what follows `magic` is `u32 version = 1`. In v2, the same position is `u16 major = 2, u16 minor`. The reader reads a u32. A value of 1 is parsed as v1, a low 16 bits of 2 is parsed as v2, and anything else is rejected.
- The same normative tag appears at most once in a file. The order of chunks in the file is not fixed. The writer writes them in the order of the "Core chunks" table in this document.
- Chunks must not overlap, and every chunk must lie inside the file. After the chunks, nothing extra is allowed past the end of the file (padding excepted).
- Offsets and lengths are u64. When they exceed the reader's own address width (for example WASM32), the reader rejects the file instead of truncating it.

### Chunk compression

| flags bit2–3 | Method | Stored content |
| --- | --- | --- |
| 0 | none | the raw chunk data |
| 1 | deflate | a zlib stream (RFC 1950) |
| 2 | zstd | a single zstd frame (RFC 8878), with no external dictionary |
| 3 | reserved | the reader rejects it |

- The reader must support deflate and zstd. The length after decompression must equal `raw_length`, or the file is rejected. The decompressed result is placed in a 16-byte-aligned buffer, and it is then parsed as an uncompressed chunk.
- Compression changes only storage. It does not change the chunk content. Offsets and array alignment inside a chunk are relative to the decompressed data.
- A compressed chunk cannot be zero-copy. A host that needs memory mapping or streaming should choose an uncompressed file.
- The writer does not compress by default. When the export option turns compression on, only a chunk that gets smaller is compressed. The `TEXR` that holds an already compressed texture payload (PNG, KTX2) is not compressed. If the compressed form is not smaller than the original data, that chunk is written uncompressed.
- Byte stability is required only for the same writer version and the same compression settings. A consistency comparison uses the chunk content after decompression.

### Compatibility rules

| Case | Reader behavior |
| --- | --- |
| `major` is not 1 or 2 | Reject |
| A known tag, and `version` ≤ the supported version | Parse it as that version. It must read exactly `length` bytes |
| A known tag, and a higher `version` | Treat it as an unknown chunk |
| An unknown chunk that is not required | Skip it |
| An unknown chunk that is required | Reject it. The error lists the tag ("feature XXXX is required") |
| A required core chunk is missing | Reject |
| The CRC does not match (checked only when the host asks for a check) | Reject |
| The compression method is a reserved value, or decompression fails, or the decompressed length does not equal `raw_length` | Reject |

`minor` takes no part in the decision. It is only for diagnosis.

### Evolution rules

1. Within one major version, the layout and the evaluation semantics of a published chunk version never change.
2. Adding an attribute to an existing object adds a "parallel chunk" that stores that column by object index, instead of changing the original record. For example, adding skinning to a mesh writes a `SKIN` chunk (mesh index → skinning data), and `MESH` stays unchanged.
3. A chunk must not contain a conditional field of the kind "when some flag is 1, read several more bytes". Optional content always uses a separate chunk or an explicit length wrapper.
4. Changing the evaluation semantics of a chunk must raise that chunk's version, and the old version is still evaluated with the old semantics.
5. A core chunk never depends on an extension chunk. An extension chunk may refer only to core chunks and to extension chunks defined earlier.
6. A vendor or experimental chunk uses a lowercase tag, and it must be a non-required chunk. A normative chunk uses an uppercase tag.

## Basic encoding

| Name | Encoding |
| --- | --- |
| `u8/u16/u32/i32/f32` | Little-endian. An f32 must be a finite value |
| `ref` | A u32 index. A nullable reference is an i32, and -1 is empty |
| `sref` | A u32 index into the string table (`STRS`). `0xFFFFFFFF` is empty |
| `rgb` | 3 × f32 |
| `str` | Used only inside `STRS`. A string anywhere else is an `sref` |
| `array<T>` | Pad with 0 to a 4-byte alignment inside the chunk, then write `u8 codec, u8 reserved[3], u32 count`, followed by `count` elements |
| `grid<F>` | The same as v1: `u16 axes` (`0xFFFF` means no grid) × `{ ref parameter, array<f32> keys }`, `u32 cells` × `{ u16 coordinate[axes], F }` |
| `channels` | The same as v1: `u8 n` × `{ u8 channel, u8 kind, grid<value> }`. channel is, in order, DrawOrder, Opacity, MultiplyColor, ScreenColor, FlipX, FlipY, and GlueIntensity. kind 0 is a scalar, 1 is a color, and 2 is a flag |
| `bindings<S>` | The same as v1: `u16 n` × `{ ref parameter, array<f32> keys, u32 neutral, u8 present + S per key, u16 n × { ref limit_parameter, u16 n × { f32 value, f32 weight } } }` |

Array encoding (`codec`):

| Value | Element | Use |
| --- | --- | --- |
| 0 | f32 | The default. Every consistency test uses it |
| 1 | f16 | An optional size optimization (positions, offsets) |
| 2 | unorm16 | An optional size optimization (UVs, range [0, 1]) |
| 16 | u16 | Indices, when the vertex count is ≤ 65535 |
| 17 | u32 | Indices |
| 32 | u8 | A byte string (a texture payload) |

Codecs 1 and 2 of a float array are written only when the host explicitly chooses "publish size optimization". The reader must support every encoding, and after decoding it treats them the same as f32. An array aligns itself: the header starts at a 4-byte alignment inside the chunk and is 8 bytes long, so the elements are 4-byte aligned as well. The chunk itself is 16-byte aligned, so an array of codec 0, 16, or 17 can be read directly as a slice. Apart from the padding before an array, a record has no other padding. A scalar does not have to be aligned.

## Core chunks

All of them are required chunks (flag bit0). The chunk version is 1 except for `PHYS`. The evaluation semantics are the sections "Evaluation rules", "Physics", and "Motion clips" of [Runtime](RUNTIME.md). Field order and count widths are the same as v1. The only differences are that strings become `sref`, float arrays, indices, and byte strings become `array<T>`, and a texture page gains a kind.

| Tag | Content | Must be present |
| --- | --- | --- |
| `STRS` | The string table | Yes |
| `CANV` | The canvas: f32 width, height, originX, originY, pixelsPerUnit (-1 means none) | Yes |
| `PARM` | Parameters | Yes (may be 0) |
| `DEFM` | Deformers, parents before children | Yes (may be 0) |
| `PART` | Parts | Yes |
| `MESH` | Meshes | Yes |
| `GLUE` | Glue | No (defaults to 0) |
| `DRAW` | The draw tree | Yes |
| `TEXR` | Texture pages | No (defaults to no textures) |
| `PHYS` | Physics groups | No |
| `CLIP` | Motion clips | No |
| `ROLE` | Parameter roles | No |

Events of a motion clip, non-parameter curves, and curve fades live in the optional attached chunk `CEXT` (see "Attached chunks" below).

When a chunk whose "Must be present" is no is missing, it is treated as an empty set. When it is present, it is still marked required, because it takes part in core evaluation.

### `STRS`

```
u32 count
u32 offsets[count + 1]   每个字符串在 blob 中的起止偏移
u8  blob[...]            UTF-8，不以 0 结尾
```

The writer deduplicates. When bit0 of the header flags (names stripped) is 1, every `name` field is an empty `sref`. `id` is still kept, and the host looks up parameters and meshes by id.

### `PARM`

`u32 n` × `{ sref id, sref name, f32 min, max, default, u8 flags (1 blend, 2 repeat) }`

### `DEFM`

`u32 n` × a deformer, and a parent must come before its children:

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

`[geometry]` appears only when flags bit3 is 1. The control points of a Bezier segment in `CLIP` likewise appear only when the segment type is 1. These two are conditional fields left over from v1. They are kept so the layout corresponds one to one with v1, and they are the only exception to rule 3.

### `GLUE`

`u32 n × { sref id, ref meshA, ref meshB, f32 intensity, u32 n × { u32 a, u32 b, f32 weightA, f32 weightB }, channels }`

### `DRAW`

The root group of the draw tree, recursive: `{ i32 part, i32 drawOrder, channels, u8 hasComposite, [composite], u32 n × { u8 kind (0 group, 1 mesh), group | ref mesh } }`

### `TEXR`

```
u32 n × { u8 kind, u32 width, height, payload }
kind 0  PNG       payload = array<u8> png      所有运行时必须支持
kind 1  KTX2      payload = array<u8> ktx2     可选；不支持的运行时报告该页不可用
kind 2  外部文件  payload = sref uri           相对模型文件的路径，由宿主加载
```

`p2l_texture_png` returns empty for a page that is not PNG. The added `p2l_texture_info(i)` returns the kind, the width and height, and the uri.

### `PHYS`

`f32 fps (0 is the runtime default), u32 n × { sref id, sref name, inputs, outputs, segments, normalization }`, and each field is the same as v1.

The `u8 source` of an input and of an output is 0 for horizontal (X), 1 for angle, and 2 for vertical (Y, the `Y` of Cubism physics3). Chunk version 2 allows vertical. The writer writes version 2 only when vertical is used. A runtime from before that reports "PHYS version 2 is required" from this, instead of reading it wrong. The file layout of version 1 is exactly the same. A vertical input adds the normalized value to the root's y displacement, and a vertical output reads that vertex's y offset relative to the previous vertex. As with horizontal, the scale of a translation output is zero (Cubism's behavior). A version 1 file (export option `v1`) does not write vertical inputs and outputs, and it notes that in the loss report.

### `CLIP`

The same as v1, with strings changed to `sref`.

### `ROLE`

`u32 n × { sref role, u32 n × ref parameter }`

## Parameter UI chunk `PGUI` (optional)

Metadata for a host to show a parameter panel. It does not take part in evaluation. A runtime that does not know it, or does not need it, skips it. Chunk version 1:

```
u32 n × { ref parameter, array<f32> keys }        吸附值：只列有吸附值的参数，keys 升序
u32 n × { ref horizontal, ref vertical }          二维摇杆：两个参数组成一个平面控件
u32 n × node                                      参数分组树，先序展开
node = { u8 kind (0 参数, 1 分组), u8 open, u8 label (0 无, 1 预设, 2 自定义), u8 reserved,
         i32 parameter (分组为 -1), sref id, sref name, sref preset, u32 argb, u32 children }
```

- A node is a fixed-length record, and every field is always written. A parameter node's `id`, `name`, and `preset` are empty, and a group node's `parameter` is -1. When `label` is not 1, `preset` is empty. When it is not 2, `argb` is 0. `children` is the number of direct child nodes that follow immediately.
- When bit0 of the header flags (names stripped) is 1, a group's `name` is empty.
- A parameter that is not in the group tree is placed at the end by the host, in `PARM` order.
- `RigIR.restPose` is used only by a format that stores the base mesh in canvas space (such as Spine). p2lrt evaluates directly from parameters, and it is not written.

## Info chunk `META` (optional)

`u32 n × { sref key, sref value }`. Normative keys: `generator`, `generator_version`, `source_hash` (the hash of the project's source data, for cache and diagnosis), and `capabilities` (the extension tags present in the file, separated by spaces, so a host can show them before loading). No timestamp is written, so the file stays reproducible.

## Evaluation stages and extension hooks

Core evaluation is fixed as the following stages. An extension chunk can take effect only at a hook that is marked. A chunk's specification must say which hook it hangs on and which contribution it replaces.

| Index | Stage | Core content | Extension hook |
| --- | --- | --- | --- |
| 1 | Parameters | motion clip → procedural behavior → Cubism pendulum → clamp / wrap | **H1 parameter override**: a replaced parameter takes its default for this evaluation, and a replaced pendulum group is skipped |
| 2 | Deformers | Parents first, Warp / rotation frames | **H2 frame replacement**: replace the frame of a named deformer with an exactly composed frame (for example a folded link) |
| 3 | Bone frames | None (the core layer has no bones) | **H3 bone frames**: the canvas transform of each bone, from a deformer frame or a virtual frame, for H4, colliders, and host attachments |
| 4 | Meshes | rest position + keyform offset + blend shape → the parent transform | **H4 mesh-contribution replacement**: a named contribution of a named mesh (a skinning-axis keyform, a named blend shape) is replaced by the extension's computed result, and the rest proceeds as usual |
| 5 | Glue | Vertex pairs pull toward each other | None |
| 6 | Post-step after the time step | None | **H6 vertex simulation**: needs dt. A fixed-step simulation that takes this evaluation's vertices as the goal, overwriting the target mesh's vertices. Collision is solved inside this stage |
| 7 | Draw order | Sort the draw tree | None |

Rules shared by the hooks:

- When extensions are off, every hook is a no-op, and the code path is the same as core evaluation.
- An H1 override applies to the whole evaluation. The writer may list only parameters that drive the replaced geometry (such as `ParamSim*`), so channels are unaffected.
- `p2l_evaluate` (no dt) does not advance H6. A simulated mesh shows the previous simulation state, and it shows the goal when it has never been advanced.

### Common header of an extension chunk

The payload of every extension chunk starts with the same override declaration. A reader can know what it will replace without understanding the chunk's content (for diagnosis and for the `META` display):

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

The chunk's own content follows. When a chunk it depends on is missing or not enabled, this extension turns itself off.

### Feature bits and the mode switch

| Bit | Chunk | Description |
| --- | --- | --- |
| 1 | `SKIN` (needs `BONE` when it refers to a virtual bone) | Runtime skinning |
| 2 | The arcs of `BONE` | Exact links (a pivot interpolated along an arc) |
| 4 | `SIMS` | Realtime simulation at the vertex level |
| 8 | `COLL` (needs `SIMS`; it turns on with the simulation) | Collision |

- `p2l_advanced_available()` returns the bits the file carries and the runtime supports. `p2l_set_advanced(bits)` returns the bits that were actually turned on. 0 is Cubism mode (the default). Switching resets the simulation state.
- `p2l_bone_count / p2l_bone_id / p2l_bone_transform` output the bone frames of H3.
- A time-step extension must use a fixed step (the step is written in the chunk), a single thread, and a fixed traversal order. Each update has a cap on the number of steps and discards time beyond it. The output is interpolated between the last two steps, the same way `PHYS` does it.

## Extension chunk `BONE` (version 1)

```
公共头                      feature 2，hooks H2 | H3，deformers = 有圆弧的变形器
u32 n × deformer            虚拟骨骼：DEFM 格式的旋转变形器记录，第 i 个的下标为 n_DEFM + i；
                            父级是 DEFM 中的变形器或更早的虚拟骨骼
u32 n × { ref deformer, sref id }            宿主可挂接的骨骼（DEFM 或虚拟骨骼）
u32 n × { ref deformer, ref parameter, f32 cx, cy }   圆弧
```

- **Virtual bones**: bones that, at skeleton bake time, have no mesh attached directly and were pruned or collapsed. They are evaluated only in runtime skinning, and they do not take part in core evaluation. The relation between a bone parameter and the angle is the same as the rotation deformer before the bake: angle = parameter value × direction, and the pivot is the bone's position.
- **Arcs**: the pivot of that rotation deformer is keyed on only the one axis `parameter`, and the position at each key lies on the same circle centered at (cx, cy) (in parent space). When exact links are on, the pivot position between two adjacent keys is interpolated by angle and radius, and the chord is no longer used. The writer writes an arc only for a pivot that has four or more keys, that all lie on the same circle, that turn in one direction, and whose every step is less than half a turn.
- `p2l_bone_count / p2l_bone_id / p2l_bone_transform` list the bones a host can attach to. The transform of a virtual bone is computed immediately from the current parameters.

## Extension chunk `SKIN` (version 1)

```
公共头                      feature 1，hooks H4，不替换任何贡献
u32 n × {
  ref mesh                  其父级是旋转变形器，且在 bones 中
  u32 n × { ref parameter, array<f32> keys }   蒙皮轴：烘焙时取样的参数及其键
  u32 n × ref deformer      骨骼：DEFM 或虚拟骨骼中的旋转变形器
  array<u16|u32> from, array<u16|u32> to, array<f32> weight   每顶点的两骨权重；长度为 0 时由运行时拟合
}
```

Evaluation (H4, in the mesh's parent space, before the parent transform):

1. The transform by which each bone b carries a vertex relative to the rest pose: A_b = H⁻¹ · B · B_rest⁻¹ · H_rest. H is the frame of the mesh's parent (home), and B is the bone's frame. Both are canvas transforms under the current parameters.
2. A two-bone linear blend: LBS(v) = (1 − w) · A_from(v) · p + w · A_to(v) · p, where p is the rest position.
3. Add to the mesh LBS(the current value) − Σ_c w_c · LBS(c). c runs over the skinning-axis key combinations on both sides of the current value, w_c is the same multilinear weight as the bake, and the other parameters keep their current values.

This correction is exactly zero at every key combination, so advanced mode matches Cubism mode at every baked key. Between keys, the baked chord is replaced by the bone's arc. When the weights are not given, the runtime fits them when the feature is turned on: at every key combination (at most 1024, otherwise axis by axis), it chooses for each vertex the bone pair and the weights that best reproduce the baked position. On a tie it takes a rigid follow of a single bone.

The writer writes `SKIN` for a mesh whose parent is a rotation deformer and that has, below it (connected through rotation deformers, which may include virtual bones), rotation deformers keyed on the mesh's axes. At most 16 bones and 8 axes. A leg mesh hangs on the stance Warp and stays baked.

## Extension chunk `COLL` (version 1)

```
公共头                      feature 8，hooks H6
u32 n × { sref id, u8 capsule, i32 deformer, i32 mesh, u32 vertexA, u32 vertexB,
          f32 ax, ay, bx, by, radiusA, radiusB, friction }
```

A collider is a circle (capsule is 0, and only the a end is used) or a capsule from a to b whose radius goes from radiusA to radiusB. The radius is in canvas pixels. Its position is decided in one of these ways:

- **Follow mesh vertices**: when mesh is not -1, the two ends take the position of that mesh's vertexA / vertexB in this evaluation. Every collider the editor exports is of this kind, so the editor and the runtime see the same position.
- **Follow a deformer**: when deformer is not -1 (it may be a virtual bone), (ax, ay) and (bx, by) are points in that deformer's local space.
- **Fixed**: when both are -1, the two ends are fixed points on the canvas.

friction is from 0 to 1, and it is the fraction of sliding that is cancelled on contact. Collision takes effect only inside simulation. Each simulation in `SIMS` lists the colliders it should avoid.

## Extension chunk `SIMS` (version 1)

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

The scene corresponds item by item with the editor's XPBD solver (`XpbdSolver`). World space is canvas x and the negated canvas y (y upward). A negative compliance means that constraint is absent (infinity in the editor).

After simulation is turned on:

- **H1**: a replaced pattern parameter stays at its default, and the matching pendulum group is skipped.
- **H4**: the target mesh has the static correction subtracted (linearly interpolated between keys, the same way the bake writes keyforms). The target mesh evaluated at that point is the unbaked bind, and it is the goal of each particle. A pinned particle follows its own position, or the position of the mesh vertex it follows.
- **H6**: `p2l_update` advances at the scene's own fps, with a fixed step. Each update takes at most 4 steps. Time beyond that is discarded, and drawing interpolates between the last two steps. `p2l_evaluate` only draws the previous step. On the first update, particles are at rest in the pose of that moment. `p2l_sim_reset` makes the next update start over, and `p2l_sim_wind` adds the host's wind.

On export (only for the runtime target and the web target), the editor rebuilds and calibrates each baked simulation on the final bind. At the default pose the pattern parameters are zero and the static correction is at its rest key, so the scene is the same as at bake time.

## Attached chunks (optional, version 1, no common header)

### `EXPR`

```
u32 n × { sref id, sref name, f32 fadeIn, f32 fadeOut, u32 n × { ref parameter, u8 blend (0 Add, 1 Multiply, 2 Overwrite), f32 value } }
```

An expression matches Cubism's exp3. The weight w is the fade coefficient of a sine ease. Each blend is computed as follows:

- Add: value + v·w
- Multiply: value · (1 + (v − 1)·w)
- Overwrite: value · (1 − w) + v·w

The runtime plays one expression at a time. On a switch, the previous expression fades out. The update order is: motion clip → expression → procedural behavior → physics. On export, the editor generates four expressions, smile, sad, angry, and surprised, from the parameters that exist in the rig. When the model has the matching module parameters, it also adds shy, sleepy, and gloom. Fade in and fade out are 0.5 seconds each.

### `HITA`

```
u32 n × { sref id, sref name, u32 n × ref mesh }
```

A hit area covers the range its meshes draw. `p2l_hit_test(x, y)` returns, in file order, the first area that has a visible mesh containing that canvas point. On export, the hit areas from the document settings are written (see [Export targets](EXPORT_TARGETS.md#hit-areas)). When the document has no settings, it generates `HitAreaHead` (the face) and `HitAreaBody` (the top; otherwise the neck or the lower garment).

### `UDAT`

```
u32 n × { u8 kind (0 = 网格), ref target, sref value }
```

User data of a mesh (from an imported CMO3), read through `p2l_mesh_user_data`.

### `POSE`

```
f32 fadeIn, u32 n × { u32 n × { ref part, u32 n × ref linkedPart } }
```

Mutually exclusive part groups match Cubism's pose3. Each group shows only one part at a time. On a switch, the selected part fades in over fadeIn seconds, and the others fade out by Cubism's rule (φ = 0.5, and the back-side opacity threshold is 0.15). A linked part follows the part it belongs to.

This is a step after evaluation: a mesh's opacity is multiplied by the pose opacity of every ancestor part. A part that is in no group is 1, so a file with no `POSE` evaluates unchanged. Inside core evaluation, part opacity is still not counted into mesh opacity. Initially each group shows its first part.

The host switches with `p2l_pose_show(group, entry)`. The export setting `pose_groups` gives the groups as `part|part; part|part`. `part+linkedPart` means a link.

### `CEXT`

```
u32 n × { ref clip,
          u32 n × { f32 time, sref value },                                    事件，按时间升序
          u32 n × { u8 target, i32 part, f32 fadeIn, f32 fadeOut,
                    f32 startTime, f32 startValue, u32 n × segment },          非参数曲线
          u32 n × { u32 curve, f32 fadeIn, f32 fadeOut } }                     片段自身曲线的淡入淡出
```

The extension of a motion clip, corresponding to everything in a Cubism motion3 other than parameter curves. It is optional. A runtime that does not know it plays only the parameter curves in `CLIP`. Each clip has at most one record.

- **Events** (`UserData` of motion3): when the clip time passes an event time, it is reported to the host by `p2l_event_count` / `p2l_event`. A looping clip fires once per loop. An event that a seek (`p2l_clip_seek`) jumps over does not fire.
- **Non-parameter curves**: `target` 1 is part opacity (`part` is the part index), 2 is model opacity, 3 is the eye-blink effect, and 4 is the lip-sync effect (the last three write `part` as -1). Curve segments are encoded the same way as `CLIP`. Part opacity and model opacity are set directly from the curve value (they do not follow the fade, the same as Cubism), and they act as a multiplier on the meshes under the part and on the whole model. The eye-blink effect multiplies onto parameters of the `EyeBlink` role, and the lip-sync effect adds onto parameters of the `LipSync` role. When the clip has a curve for that parameter, it acts on the curve value. When it does not, it acts on the current value by the clip weight.
- **Fades**: a negative number means to use the clip's own setting. `curve` is the index of that curve as written in `CLIP` (only parameters that exist in the file are counted). A curve that has its own fade takes its weight by Cubism's rule: the fade-in factor uses the curve's fade-in (or the clip's), the fade-out factor uses the curve's fade-out (or the clip's), and the two are multiplied.

Constraints shared by the extension chunks:

- They only replace or supplement a specific contribution in core evaluation (such as a corrective keyform, or a shape driven by `ParamSim*`). Parameters, motions, behaviors, and the Cubism pendulum drive as usual.
- They are off by default. The host turns them on with `p2l_set_advanced(features)`. When they are off, the evaluation result matches, bit for bit, a file that has only the core chunks.
- The writer always keeps the matching Cubism bake result in the core chunks.

## Runtime and writer

- **Reading**: v1 and v2 are both supported. Both parse into the same in-memory structure `Rig`, and there is one copy of the evaluation code.
- **Writing**: `P2lrtTarget` writes v2 by default. The export option `v1` writes only the v1 layout of the core content. When there is extension data, `CapabilityScan` reports that it is kept in baked form. `compress` compresses, with deflate, the chunks that get smaller. `strip_names` strips names.
- **Capability query**: the added `p2l_format_support()` returns the supported major version and the list of chunk tags and versions. Bit0 of the flags of `p2l_rig_load_ex(bytes, len, flags)` asks for a CRC check.
- **Compression dependencies**: the Rust reader uses the pure-Rust `miniz_oxide` (deflate) and `ruzstd` (zstd decode), and it can be compiled to WASM. The runtime only needs to decode. The WASM build therefore grows from 208 KB to 400 KB, of which version 2 reading and deflate are about 79 KB and zstd is about 113 KB. On the Kotlin writer side, deflate uses the JDK `Deflater`, and zstd uses the pure-Java aircompressor.
- **Byte stability**: writing the same IR twice produces the same bytes. Name stripping and array encoding are decided by the export options, and they are not affected by the environment.

## Validation

- **v1 and v2 match bit for bit**: every random model, sample, and physics trace of `RuntimeConformanceTool` is written as both v1 and v2. The runtime evaluation results are compared with `to_bits()`. Vertices, opacity, color, and draw order must be exactly the same.
- **Reader**: v1 is accepted. A minimal v2 file is accepted. An unknown optional chunk is skipped. An unknown required chunk is rejected, and the tag is reported. A chunk that is out of range, that overlaps, that is over-read, that is under-read, a repeated normative tag, a missing required core chunk, a bad alignment, and a CRC that does not match (when checking is on) are all rejected. There is one suite in Rust (`runtime/src/tests.rs`) and one in Kotlin (`P2lrtTest.kt`).
- **Reproducible**: writing the same IR with the same settings twice produces the same bytes. After `STRS` deduplication, content that does not depend on write order matches.
- **Compression**: the same model is written uncompressed, as deflate, and as zstd. After decompression, each chunk's content is the same byte for byte, and evaluation matches bit for bit. A `raw_length` that does not match, a truncated compressed stream, and a reserved compression method are all rejected. Kotlin write, Rust read, and WASM read are all covered.
- **`PGUI`**: the group tree, the two-dimensional sticks, and the snap values read back the same as `RigIR` after they are written. Removing `PGUI` does not change evaluation.
- **Array encoding**: the vertex error of an f16 or unorm16 model against an f32 model stays within the encoding precision, and it is reported on its own. It does not enter the bit-for-bit corpus.
- **Across languages**: Kotlin writes and Rust reads. The WASM build passes the same reader tests.
- **Cubism mode is unchanged when extensions are present**: a file that has extension chunks, with the feature bits at 0, evaluates bit-for-bit the same as the same file with the extension chunks removed. Turning them on and then off matches as well (no leftover state).
- **Reference implementations of the extensions**: every extension chunk has a Kotlin reference implementation. It is included in the random corpus of `RuntimeConformanceTool` and checked against the runtime.

## Relation to the advanced-mode plan

This format is the container basis of the runtime's advanced mode (runtime skinning, exact links, vertex simulation, and collision, two modes in the same file). It corresponds to that plan's "stage 0 format pipeline", and it replaces the plan's original idea of "appending chunks after a v1 body": the core content is also split into chunks, and every part of the content follows the same evolution rules. Later stages each define the in-chunk layout of `BONE` / `SKIN`, `SIMS`, and `COLL`. The hooks, the common header, and the feature bits follow this document.

A few items in the plan that were to be decided from measured results are decided as follows:

- **Runtime ARAP**: not implemented. The skinning correction is zero at every key, and advanced mode matches it exactly at every baked key (every consistency sample is 0). Between keys, the motion already follows the bone's arc.
- **A mesh hung on the stance Warp (the legs)**: stays baked. The legs bend inside the stance Warp. The skeleton did not generate a rotation frame for the leg bones, so skinning has no frame to take.
- **Four-bone weights**: not needed. The editor's skinning has each vertex span at most two bones.

## Decisions already made

- Chunk compression: deflate and zstd are both defined, and the reader must support both. The writer does not compress by default. The export setting `compress` selects deflate, and adding `zstd` selects zstd.
- The width of offsets and lengths: u64.
- Parameter groups, two-dimensional sticks, and snap values go in the optional `PGUI` chunk. `PARM` corresponds one to one with v1. `restPose` is not written.
