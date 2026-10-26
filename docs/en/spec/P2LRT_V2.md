# `.p2lrt` 2.0 format

[Documentation](../../README.md) · [中文](../../zh/spec/P2LRT_V2.md) · [Runtime](RUNTIME.md) · [Project, runtime and export boundaries](../../zh/spec/RUNTIME_EXPORT_ARCHITECTURE_AND_GAPS.md) (Chinese)

Status: the container, core chunks, `PGUI`, `META`, extension chunks `BONE`, `SKIN`, `COLL`, `SIMS` and companion chunks `EXPR`, `HITA`, `UDAT`, `POSE` are implemented, and export writes version 2 by default. The reader supports every compression method, array codec and texture page kind; the writer can write deflate and zstd compression, f32 arrays and embedded PNG texture pages.

## Goals

- **Settle the evolution rules once**: after 2.0, new features only add chunks; existing record layouts do not change and the major version is not raised again.
- **Core layer equivalent to Cubism**: reading only the core chunks is enough for full playback, with results bit-identical to v1 and semantics that map one-to-one to moc3.
- **Optional extension layer**: advanced data such as runtime skinning, vertex simulation and collision lives in optional chunks; runtimes that do not know them fall back to the Cubism result (two modes in one file).
- **Easy to load**: random access, on-demand loading and zero-copy arrays; a single file can carry the content of the existing sidecars.
- **Reproducible**: the same IR always writes the same bytes, with no timestamps or other volatile data.

Non-goals: a general scene format, an editable project format (projects remain `.psd2live`), encryption or obfuscation.

## Container

Little-endian. A file consists of a header, a chunk table (TOC) and chunk data:

```
Header  (32 bytes)
  u8[8]  magic        "P2LRT\0\0\0"
  u16    major        = 2
  u16    minor        highest minor version the writer knows; informational only
  u32    flags        bit0 names stripped; the rest reserved, written as 0
  u32    chunk_count
  u32    toc_offset   = 32
  u32    reserved[2]  written as 0
TOC     chunk_count × 40 bytes
  u8[4]  tag          four ASCII characters; upper case for standard chunks, lower case for vendor/private chunks
  u16    version      chunk version (layout and evaluation semantics)
  u16    flags        bit0 required; bit1 has CRC; bit2–3 compression method (see below); the rest written as 0
  u64    offset       offset of the chunk data from the file header, 16-byte aligned
  u64    length       bytes the chunk occupies in the file (without padding; the compressed length when compressed)
  u64    raw_length   bytes after decompression; equal to length when uncompressed
  u32    crc32        IEEE CRC32 over the stored (compressed) bytes (0 when bit1 is 0)
  u32    reserved     written as 0
Chunks  chunk data, padded with 0 between chunks to 16-byte alignment
```

- The first 5 bytes of the header `magic` are the same as v1. In v1 the `magic` is followed by `u32 version = 1`; in v2 the same position holds `u16 major = 2, u16 minor`. A reader reads a u32: a value of 1 parses as v1, a low 16 bits of 2 parses as v2, anything else is rejected.
- A standard tag appears at most once per file. Chunks may appear in any order in the file; the writer writes them in the order of the "Core chunks" table on this page.
- Chunks must not overlap and must all lie inside the file; no extra data is allowed after the chunks, beyond the end of the file (except padding).
- Offsets and lengths are u64; a reader whose address width is smaller (such as WASM32) rejects a file that exceeds it rather than truncating.

### Chunk compression

| flags bit2–3 | Method | Stored content |
| --- | --- | --- |
| 0 | None | Raw chunk data |
| 1 | deflate | zlib stream (RFC 1950) |
| 2 | zstd | A single zstd frame (RFC 8878), no external dictionary |
| 3 | Reserved | Readers reject |

- Readers must support deflate and zstd. The decompressed length must equal `raw_length`, otherwise the file is rejected; the decompressed result goes into a 16-byte-aligned buffer and is then parsed as an uncompressed chunk.
- Compression changes only the storage, not the chunk content; offsets and array alignment inside a chunk are relative to the decompressed data.
- Compressed chunks cannot be zero-copy; hosts that need memory mapping or streaming loads should choose uncompressed files.
- The writer does not compress by default. When the export option enables compression, only chunks that get smaller are compressed; `TEXR`, which holds already-compressed texture payloads (PNG, KTX2), is not compressed. If the compressed data is not smaller than the original, the chunk is written uncompressed.
- Byte stability is required only for the same writer version and the same compression setting; conformance comparisons use the decompressed chunk content.

### Compatibility rules

| Case | Reader behavior |
| --- | --- |
| `major` is not 1 or 2 | Reject |
| Known tag, `version` ≤ supported version | Parse as that version; must read exactly `length` bytes |
| Known tag, higher `version` | Treat as an unknown chunk |
| Unknown chunk, not required | Skip |
| Unknown chunk, required | Reject; the error names the tag ("requires feature XXXX") |
| A required core chunk is missing | Reject |
| CRC mismatch (checked only when the host requests verification) | Reject |
| Compression method is the reserved value, decompression fails, or the decompressed length differs from `raw_length` | Reject |

`minor` takes no part in these decisions; it is used only for diagnostics.

### Evolution rules

1. Within a major version, the layout and evaluation semantics of a published chunk version never change.
2. To add a property to existing objects, add a "parallel chunk" that stores the column by object index, instead of changing the original record. For example, skinning for meshes is written as a `SKIN` chunk (mesh index → skinning data) and `MESH` stays unchanged.
3. Chunks must not contain conditional fields of the form "read a few more bytes when some flag is 1"; optional content always goes in a separate chunk or is wrapped with an explicit length.
4. Changing a chunk's evaluation semantics requires raising that chunk's version, and older versions still evaluate with the old semantics.
5. Core chunks never depend on extension chunks; extension chunks may reference only core chunks and extension chunks defined earlier.
6. Vendor or experimental chunks use lower-case tags and must be non-required; standard chunks use upper-case tags.

## Basic encoding

| Name | Encoding |
| --- | --- |
| `u8/u16/u32/i32/f32` | Little-endian; f32 must be finite |
| `ref` | u32 index; nullable references use i32, -1 for null |
| `sref` | u32 string-table index (`STRS`), `0xFFFFFFFF` for null |
| `rgb` | 3 × f32 |
| `str` | Used only inside `STRS`; strings anywhere else are always `sref` |
| `array<T>` | First pad with 0 to 4-byte alignment within the chunk, then write `u8 codec, u8 reserved[3], u32 count`, followed by `count` elements |
| `grid<F>` | As v1: `u16 axes` (`0xFFFF` means no grid) × `{ ref parameter, array<f32> keys }`, `u32 cells` × `{ u16 coordinate[axes], F }` |
| `channels` | As v1: `u8 n` × `{ u8 channel, u8 kind, grid<value> }`; channels in order are DrawOrder, Opacity, MultiplyColor, ScreenColor, FlipX, FlipY, GlueIntensity; kind 0 scalar, 1 color, 2 flag |
| `bindings<S>` | As v1: `u16 n` × `{ ref parameter, array<f32> keys, u32 neutral, u8 present + S per key, u16 n × { ref limit_parameter, u16 n × { f32 value, f32 weight } } }` |

Array codecs (`codec`):

| Value | Element | Use |
| --- | --- | --- |
| 0 | f32 | Default; used by all conformance tests |
| 1 | f16 | Optional size optimization (positions, offsets) |
| 2 | unorm16 | Optional size optimization (UVs, range [0,1]) |
| 16 | u16 | Indices, when the vertex count is ≤ 65535 |
| 17 | u32 | Indices |
| 32 | u8 | Byte strings (texture payloads) |

Codecs 1 and 2 for float arrays are written only when the host explicitly chooses "release size optimization"; readers must support every codec and treat the decoded values the same as f32. Arrays align themselves: the header starts at 4-byte alignment within the chunk and is 8 bytes long, so elements are also 4-byte aligned; chunks themselves are 16-byte aligned, so arrays with codec 0/16/17 can be read directly as slices. Apart from the padding before arrays, records contain no other padding, and scalars may be unaligned.

## Core chunks

All are required chunks (flag bit0). Every chunk version is 1 except `PHYS`, and the evaluation semantics are the "Evaluation rules", "Physics" and "Motion clips" sections of [Runtime](RUNTIME.md). Field order and count widths are the same as v1; the only differences are that strings become `sref`, float arrays, indices and byte strings become `array<T>`, and texture pages gain a kind.

| Tag | Content | Must be present |
| --- | --- | --- |
| `STRS` | String table | Yes |
| `CANV` | Canvas: f32 width, height, originX, originY, pixelsPerUnit (-1 none) | Yes |
| `PARM` | Parameters | Yes (may be 0) |
| `DEFM` | Deformers, parents first | Yes (may be 0) |
| `PART` | Parts | Yes |
| `MESH` | Meshes | Yes |
| `GLUE` | Glue | No (0 when absent) |
| `DRAW` | Draw tree | Yes |
| `TEXR` | Texture pages | No (no textures when absent) |
| `PHYS` | Physics groups | No |
| `CLIP` | Motion clips | No |
| `ROLE` | Parameter roles | No |

Motion clip events, non-parameter curves and curve fades are in the non-required companion chunk `CEXT` (see "Companion chunks" below).

A chunk whose "Must be present" is No is treated as an empty set when absent; when present it is still marked required, because it takes part in core evaluation.

### `STRS`

```
u32 count
u32 offsets[count + 1]   start and end offset of each string in the blob
u8  blob[...]            UTF-8, not 0-terminated
```

The writer deduplicates. When header flags bit0 (names stripped) is 1, every `name` field is a null `sref` while `id` is kept, and hosts look up parameters and meshes by id.

### `PARM`

`u32 n` × `{ sref id, sref name, f32 min, max, default, u8 flags (1 blend, 2 repeat) }`

### `DEFM`

`u32 n` × deformer; parents must come before children:

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

`[geometry]` is present only when flags bit3 is 1; likewise, the control points of Bezier segments in `CLIP` are present only when the segment type is 1. These two are conditional fields inherited from v1, kept for a one-to-one mapping with v1, and are the only exceptions to rule 3.

### `GLUE`

`u32 n × { sref id, ref meshA, ref meshB, f32 intensity, u32 n × { u32 a, u32 b, f32 weightA, f32 weightB }, channels }`

### `DRAW`

The draw tree's root group, recursive: `{ i32 part, i32 drawOrder, channels, u8 hasComposite, [composite], u32 n × { u8 kind (0 group, 1 mesh), group | ref mesh } }`

### `TEXR`

```
u32 n × { u8 kind, u32 width, height, payload }
kind 0  PNG            payload = array<u8> png      all runtimes must support it
kind 1  KTX2           payload = array<u8> ktx2     optional; runtimes without support report the page as unavailable
kind 2  external file  payload = sref uri           path relative to the model file, loaded by the host
```

`p2l_texture_png` returns empty for non-PNG pages; the new `p2l_texture_info(i)` returns the kind, width and height, and uri.

### `PHYS`

`f32 fps (0 runtime default), u32 n × { sref id, sref name, inputs, outputs, segments, normalization }`, each field as in v1.

The `u8 source` of inputs and outputs is 0 horizontal (X), 1 angle, 2 vertical (Y, the `Y` of Cubism physics3). Chunk version 2 allows vertical: the writer writes version 2 only when vertical is used, so earlier runtimes report "requires PHYS version 2" instead of misreading; the file layout of version 1 is exactly the same. A vertical input adds the normalized value to the root's y translation, and a vertical output reads the y offset of that vertex relative to the previous vertex; as with horizontal, the scale of translation outputs is zero (Cubism's behavior). Version 1 files (export option `v1`) do not write vertical inputs or outputs and note this in the loss report.

### `CLIP`

As v1, with strings changed to `sref`.

### `ROLE`

`u32 n × { sref role, u32 n × ref parameter }`

## Parameter UI chunk `PGUI` (non-required)

Metadata for hosts that show a parameter panel; it takes no part in evaluation, and runtimes that do not know or need it skip it. Chunk version 1:

```
u32 n × { ref parameter, array<f32> keys }        snap values: only parameters with snap values, keys ascending
u32 n × { ref horizontal, ref vertical }          2D sticks: two parameters forming one planar control
u32 n × node                                      parameter group tree, expanded in pre-order
node = { u8 kind (0 parameter, 1 group), u8 open, u8 label (0 none, 1 preset, 2 custom), u8 reserved,
         i32 parameter (-1 for groups), sref id, sref name, sref preset, u32 argb, u32 children }
```

- Nodes are fixed-length records and every field is always written: parameter nodes have null `id`, `name` and `preset`, and group nodes have `parameter` -1; `preset` is null when `label` is not 1, and `argb` is 0 when it is not 2. `children` is the number of direct child nodes that immediately follow.
- When header flags bit0 (names stripped) is 1, group `name` is null.
- Parameters not listed in the group tree are placed at the end by the host, in `PARM` order.
- `RigIR.restPose` is used only by formats that store base meshes in canvas space (such as Spine); p2lrt evaluates directly from parameters and does not write it.

## Information chunk `META` (non-required)

`u32 n × { sref key, sref value }`. Standard keys: `generator`, `generator_version`, `source_hash` (hash of the project source data, for caching and diagnostics), `capabilities` (space-separated extension tags present in the file, for hosts to show before loading). No timestamps are written, keeping the file reproducible.

## Evaluation stages and extension hooks

Core evaluation consists of the fixed stages below; extension chunks may act only at the marked hooks, and a chunk's specification must state which hook it attaches to and which contribution it replaces.

| No. | Stage | Core content | Extension hook |
| --- | --- | --- | --- |
| 1 | Parameters | Motion clips → procedural behaviors → Cubism pendulums → clamp / wrap | **H1 parameter override**: replaced parameters take their default value for this evaluation, and replaced pendulum groups are skipped |
| 2 | Deformers | Parents first, Warp / rotation frames | **H2 frame replacement**: replace the frame of given deformers with an exactly composed frame (such as folded links) |
| 3 | Bone frames | None (the core layer has no bones) | **H3 bone frames**: derive each bone's canvas transform from deformer frames or virtual frames, for H4, colliders and host attachments |
| 4 | Meshes | Rest positions + keyform offsets + blend shapes → parent transform | **H4 mesh contribution replacement**: given contributions of given meshes (keyforms on skinning axes, given blend shapes) are replaced by the extension's result; the rest proceeds as usual |
| 5 | Glue | Vertex pairs pull each other | None |
| 6 | Time-step post-processing | None | **H6 vertex simulation**: needs dt; runs a fixed-step simulation targeting the vertices of this evaluation and overwrites the target meshes' vertices; collisions are solved within this stage |
| 7 | Draw order | Draw tree sorting | None |

Rules common to all hooks:

- With no extension enabled, every hook is a no-op and the code path is the same as core evaluation.
- An H1 override applies to the whole evaluation. Writers may list only parameters that drive nothing but the replaced geometry (such as `ParamSim*`), so channels are unaffected.
- `p2l_evaluate` (no dt) does not advance H6; simulated meshes show the previous simulation state, or the target if never advanced.

### Common header of extension chunks

Each extension chunk's payload starts with a uniform override declaration, so a reader knows what it replaces without understanding the chunk content (for diagnostics and `META` display):

```
u16 feature_bit           feature bit for p2l_set_advanced
u16 hooks                 bit set: H1=1, H2=2, H3=4, H4=8, H6=32
u32 n × ref parameter     parameters H1 overrides to their default
u32 n × ref physicsGroup  pendulum groups H1 skips
u32 n × ref deformer      deformers whose frame H2 replaces
u32 n × { ref mesh, u32 n × ref axisParameter, u32 n × u32 binding }
                          contributions H4 replaces: these keyform axes take their default, these blend shapes are not counted
u32 n × u8[4] depends_on  tags of other extension chunks depended on
```

The chunk's own content follows. When a dependency chunk is missing or not enabled, the extension is disabled automatically.

### Feature bits and mode switch

| Bit | Chunk | Description |
| --- | --- | --- |
| 1 | `SKIN` (needs `BONE` when it references virtual bones) | Runtime skinning |
| 2 | Arcs in `BONE` | Exact links (pivots interpolated along arcs) |
| 4 | `SIMS` | Real-time vertex-level simulation |
| 8 | `COLL` (needs `SIMS`; enabled with simulation) | Collision |

- `p2l_advanced_available()` returns the bits the file carries and the runtime supports; `p2l_set_advanced(bits)` returns the bits actually enabled, with 0 being Cubism mode (the default). Switching resets the simulation state.
- `p2l_bone_count / p2l_bone_id / p2l_bone_transform` output the H3 bone frames.
- Time-step extensions must use a fixed step (the step size is stored in the chunk), run single-threaded in a fixed traversal order, cap the number of steps per update and drop the excess time, and interpolate the output between the last two steps, as `PHYS` does.

## Extension chunk `BONE` (version 1)

```
common header               feature 2, hooks H2 | H3, deformers = deformers with arcs
u32 n × deformer            virtual bones: rotation deformer records in DEFM format, the i-th has index n_DEFM + i;
                            the parent is a deformer in DEFM or an earlier virtual bone
u32 n × { ref deformer, sref id }            bones hosts can attach to (DEFM or virtual bones)
u32 n × { ref deformer, ref parameter, f32 cx, cy }   arcs
```

- **Virtual bones**: bones that had no directly attached mesh when the skeleton was baked, or were pruned or folded. They are evaluated only in runtime skinning and take no part in core evaluation. The relation between a bone parameter and its angle is the same as the rotation deformer before baking: angle = parameter value × direction, with the pivot at the bone's position.
- **Arcs**: the pivot of this rotation deformer is keyed only on the `parameter` axis, and the positions of the keys lie on the same circle centered at (cx, cy) (in parent space). With exact links enabled, the pivot position between two adjacent keys is interpolated by angle and radius instead of along the chord. The writer writes arcs only for pivots with four or more keys that all lie on one circle, turn in a consistent direction and move less than half a turn per step.
- `p2l_bone_count / p2l_bone_id / p2l_bone_transform` list the bones that can be attached to; virtual bone transforms are computed on the fly from the current parameters.

## Extension chunk `SKIN` (version 1)

```
common header               feature 1, hooks H4, replaces no contribution
u32 n × {
  ref mesh                  its parent is a rotation deformer that is among the bones
  u32 n × { ref parameter, array<f32> keys }   skinning axes: parameters sampled when baking, and their keys
  u32 n × ref deformer      bones: rotation deformers in DEFM or the virtual bones
  array<u16|u32> from, array<u16|u32> to, array<f32> weight   two-bone weights per vertex; fitted by the runtime when length is 0
}
```

Evaluation (H4, in the mesh's parent space, before the parent transform):

1. The transform of each bone b that carries vertices relative to the rest pose: A_b = H⁻¹ · B · B_rest⁻¹ · H_rest, where H is the frame of the mesh's parent (home) and B the bone's frame, both canvas transforms under the current parameters.
2. Two-bone linear blending: LBS(v) = (1 − w) · A_from(v) · p + w · A_to(v) · p, where p is the rest position.
3. Add LBS(current) − Σ_c w_c · LBS(c) to the mesh. c ranges over the combinations of skinning-axis keys on either side of the current values, w_c are the same multilinear weights as in baking, and the other parameters keep their current values.

This correction is exactly zero at every key combination, so advanced mode matches Cubism mode at every baked key; between keys, the baked chord is replaced by the bone's arc. When weights are not given, the runtime fits them when enabled: over all key combinations (up to 1024, otherwise axis by axis), it picks for each vertex the bone pair and weight that best reproduce the baked positions, preferring rigidly following a single bone on ties.

The writer writes `SKIN` for meshes whose parent is a rotation deformer and that have, below it (connected through rotation deformers, possibly including virtual bones), rotation deformers keyed on the mesh's axes, with at most 16 bones and 8 axes. Leg meshes attached to the stance Warp stay baked.

## Extension chunk `COLL` (version 1)

```
common header               feature 8, hooks H6
u32 n × { sref id, u8 capsule, i32 deformer, i32 mesh, u32 vertexA, u32 vertexB,
          f32 ax, ay, bx, by, radiusA, radiusB, friction }
```

A collider is a circle (capsule 0, using only end a) or a capsule from a to b with radius tapering from radiusA to radiusB; radii are in canvas pixels. Its position is determined in one of these ways:

- **Following mesh vertices**: when mesh is not -1, the two ends are the positions of that mesh's vertexA / vertexB in this evaluation. Colliders exported by the editor are all of this kind, so the editor and the runtime see the same positions.
- **Following a deformer**: when deformer is not -1 (it may be a virtual bone), (ax, ay) and (bx, by) are points in that deformer's local space.
- **Fixed**: when both are -1, the two ends are fixed points on the canvas.

friction is 0–1, the fraction of sliding cancelled on contact. Collision takes effect only in simulation: each simulation in `SIMS` lists the colliders it avoids.

## Extension chunk `SIMS` (version 1)

```
common header               feature 4, hooks H1 | H4 | H6; parameters and physics groups are the union of each simulation's replacements
u32 n × {
  sref id, f32 fps, u32 substeps, f32 gravityX, gravityY, windX, windY, pinCompliance
  u32 n × { ref mesh, u32 vertexCount }                 target meshes; particles are ordered by this
  array<f32> invMass, damping, windFactor, pinWeight, goalCompliance, goalOffsetX, goalOffsetY
  array<u32> anchorMesh (0xFFFFFFFF follows itself), array<u32> anchorVertex
  array<u32> stretchA, stretchB; array<f32> rest, compliance, compressionCompliance
  array<u32> triA, triB, triC; array<f32> areaCompliance
  array<u32> bendT1, bendT2; array<f32> bendCompliance
  array<u32> weldA, weldB; array<f32> weightA, weightB, weldCompliance
  array<u32> longParticle, longRoot; array<f32> maxDistance
  u32 n × ref parameter                                 baked-mode parameters this simulation replaces
  u32 n × u32 physicsGroup                              pendulum groups this simulation replaces (PHYS indices)
  u32 n × { ref parameter, array<f32> keys, u32 n × { ref mesh, keys × array<f32> offsets } }   static corrections added by baking
  u32 n × u32 collider                                  COLL indices
}
```

The scene corresponds item by item to the editor's XPBD solver (`XpbdSolver`). World space is canvas x and negated canvas y (y up). A negative compliance means the constraint is absent (infinity in the editor).

With simulation enabled:

- **H1**: the replaced mode parameters stay at their default, and the corresponding pendulum groups are skipped.
- **H4**: the target meshes subtract the static corrections (linearly interpolated between keys, the same way baking writes them into keyforms). The evaluated target meshes are then the unbaked rig and serve as each particle's goal; pinned particles follow their own position or that of the mesh vertex they follow.
- **H6**: `p2l_update` advances in fixed steps at the scene's own fps. Each update takes at most 4 steps, excess time is dropped, and drawing interpolates between the last two steps; `p2l_evaluate` only draws the previous step. On the first update the particles rest in the pose of that moment. `p2l_sim_reset` makes the next update start over, and `p2l_sim_wind` adds the host's wind.

On export (only for the runtime and web targets), the editor rebuilds and calibrates each baked simulation on the final rig. In the default pose, the mode parameters are zero and the static corrections are at their rest key, so the scene is the same as when baked.

## Companion chunks (non-required, version 1, no common header)

### `EXPR`

```
u32 n × { sref id, sref name, f32 fadeIn, f32 fadeOut, u32 n × { ref parameter, u8 blend (0 Add, 1 Multiply, 2 Overwrite), f32 value } }
```

Expressions match Cubism exp3. The weight w is the sine-eased fade factor; each blend computes:

- Add: value + v·w
- Multiply: value · (1 + (v − 1)·w)
- Overwrite: value · (1 − w) + v·w

The runtime plays one expression at a time, and the previous expression fades out on a switch. The update order is: motion clips → expressions → procedural behaviors → physics. On export, the editor generates four expressions, smile, sad, angry and surprised, from the parameters present in the rig, plus shy, sleepy and gloomy where the rig has the modules' parameters for them, each with 0.5 s fade in and fade out.

### `HITA`

```
u32 n × { sref id, sref name, u32 n × ref mesh }
```

A hit area covers the area its meshes draw. `p2l_hit_test(x, y)` returns, in file order, the first area with a visible mesh containing that canvas point. Export writes the hit areas the document set up (see [export targets](EXPORT_TARGETS.md#hit-areas)); without any it generates `HitAreaHead` (face) and `HitAreaBody` (top, otherwise neck or bottoms).

### `UDAT`

```
u32 n × { u8 kind (0 = mesh), ref target, sref value }
```

User data of meshes (from an imported CMO3), read through `p2l_mesh_user_data`.

### `POSE`

```
f32 fadeIn, u32 n × { u32 n × { ref part, u32 n × ref linkedPart } }
```

Part exclusion groups match Cubism pose3: each group shows only one part at a time; on a switch, the selected part fades in over fadeIn seconds and the others fade out by Cubism's rules (φ = 0.5, back opacity threshold 0.15); linked parts follow the part they belong to.

It is a step after evaluation: a mesh's opacity is multiplied by the pose opacity of all its ancestor parts. Parts in no group have 1, so files without `POSE` evaluate unchanged; in core evaluation, part opacity still does not count toward mesh opacity. Initially each group shows its first part.

Hosts switch with `p2l_pose_show(group, entry)`. The export setting `pose_groups` specifies groups as `part|part; part|part`, with `part+linkedPart` denoting a link.

### `CEXT`

```
u32 n × { ref clip,
          u32 n × { f32 time, sref value },                                    events, ascending by time
          u32 n × { u8 target, i32 part, f32 fadeIn, f32 fadeOut,
                    f32 startTime, f32 startValue, u32 n × segment },          non-parameter curves
          u32 n × { u32 curve, f32 fadeIn, f32 fadeOut } }                     fades of the clip's own curves
```

An extension of motion clips, corresponding to the content of Cubism motion3 other than parameter curves; it is non-required, and runtimes that do not know it play only the parameter curves in `CLIP`. Each clip has at most one record.

- **Events** (motion3 `UserData`): when clip time passes an event's time, it is reported to the host through `p2l_event_count` / `p2l_event`; looping clips fire once per loop, and events skipped over by a seek (`p2l_clip_seek`) do not fire.
- **Non-parameter curves**: `target` 1 part opacity (`part` is the part index), 2 model opacity, 3 eye blink effect, 4 lip sync effect (the last three write -1 for `part`). Curve segments are encoded as in `CLIP`. Part opacity and model opacity are set directly to the curve value (not subject to fades, as in Cubism) and act as multipliers on the meshes under the part and on the whole model; the eye blink effect multiplies the `EyeBlink` role parameters and the lip sync effect adds to the `LipSync` role parameters: when the clip has a curve for that parameter it acts on the curve value, otherwise on the current value weighted by the clip weight.
- **Fades**: a negative value means the clip's setting is used. `curve` is the index of that curve as written in `CLIP` (counting only parameters present in the file). A curve with its own fades takes its weight by Cubism's rules: the fade-in factor uses the curve's fade-in (or the clip's), the fade-out factor uses the curve's fade-out (or the clip's), and the two are multiplied.

Constraints common to extension chunks:

- They only replace or supplement specific contributions of core evaluation (such as corrective keyforms or shapes driven by `ParamSim*`); parameters, motions, behaviors and Cubism pendulums drive as usual.
- Off by default; hosts enable them through `p2l_set_advanced(features)`, and when off, evaluation is bit-identical to a file with only the core chunks.
- The writer always keeps the corresponding Cubism baked result in the core chunks.

## Runtime and writer

- **Reading**: both v1 and v2 are supported; both parse into the same in-memory structure `Rig`, and there is only one copy of the evaluation code.
- **Writing**: `P2lrtTarget` writes v2 by default; the export option `v1` writes only the core content in the v1 layout, in which case `CapabilityScan` reports any extension data as kept in baked form. `compress` compresses chunks that get smaller with deflate, and `strip_names` strips names.
- **Capability query**: the new `p2l_format_support()` returns the supported major versions and the list of chunk tags and versions; flags bit0 of `p2l_rig_load_ex(bytes, len, flags)` requests CRC verification.
- **Compression dependencies**: the Rust reader uses pure-Rust `miniz_oxide` (deflate) and `ruzstd` (zstd decoding), which compile to WASM; the runtime only needs decoding. The WASM build grows from 208 KB to 400 KB as a result, of which version 2 reading and deflate take about 79 KB and zstd about 113 KB. The Kotlin writer uses the JDK `Deflater` for deflate and pure-Java aircompressor for zstd.
- **Byte stability**: writing the same IR twice gives the same bytes; name stripping and array codecs are decided by export options and are unaffected by the environment.

## Verification

- **v1/v2 bit-identical**: all random models, samples and physics trajectories of `RuntimeConformanceTool` are written as both v1 and v2, and runtime evaluation results are compared with `to_bits()`; vertices, opacity, colors and draw order must be exactly the same.
- **Reader**: v1 accepted; minimal v2 file accepted; unknown optional chunks skipped; unknown required chunks rejected with the tag reported; out-of-bounds chunks, overlap, over-read, under-read, duplicate standard tags, missing required core chunks, wrong alignment and CRC mismatch (with verification on) all rejected. Rust (`runtime/src/tests.rs`) and Kotlin (`P2lrtTest.kt`) each have a suite.
- **Reproducible**: the same IR with the same settings writes the same bytes twice; after `STRS` deduplication, content is the same regardless of write order.
- **Compression**: the same model written uncompressed, with deflate and with zstd has byte-identical chunk content after decompression and bit-identical evaluation; a mismatched `raw_length`, a truncated compressed stream and the reserved compression method are all rejected; Kotlin writing and Rust and WASM reading are all covered.
- **`PGUI`**: the group tree, 2D sticks and snap values read back after writing match `RigIR`; removing `PGUI` leaves evaluation unchanged.
- **Array codecs**: vertex error of f16 / unorm16 models against the f32 model is within the codec's precision, reported separately and not part of the bit-identical corpus.
- **Cross-language**: Kotlin writes, Rust reads; the WASM build passes the same reader tests.
- **Cubism mode unchanged with extensions present**: a file with extension chunks and feature bits 0 evaluates bit-identically to the same file with the extension chunks removed; also after enabling and then disabling (no leftover state).
- **Reference implementations of extensions**: every extension chunk has a Kotlin reference implementation, included in the random corpus of `RuntimeConformanceTool` and compared against the runtime.

## Relation to the advanced mode roadmap

This format is the container foundation for the runtime advanced mode (runtime skinning, exact links, vertex simulation, collision, two modes in one file), corresponding to its "stage 0 format pipeline", and replaces that roadmap's original idea of "appending chunks after a v1 body": the core content is also re-chunked, and all content follows the same evolution rules. Later stages each define the in-chunk layout of `BONE`/`SKIN`, `SIMS` and `COLL`; hooks, the common header and feature bits follow this page.

The items in the roadmap left to be decided by measurement are decided as follows:

- **Runtime ARAP**: not implemented. The skinning correction is zero at every key, so advanced mode matches exactly at every baked key (all conformance samples are 0); between keys it already follows the bone arc.
- **Meshes attached to the stance Warp (legs)**: stay baked. Legs bend inside the stance Warp, the skeleton generates no rotation frames for leg bones, and skinning has no frame to take.
- **Four-bone weights**: not needed. The editor's skinning spans at most two bones per vertex.

## Settled decisions

- Chunk compression: deflate and zstd are defined, and readers must support both; the writer does not compress by default, the export setting `compress` selects deflate, and adding `zstd` selects zstd.
- Offset and length width: u64.
- Parameter groups, 2D sticks and snap values go in the non-required `PGUI` chunk, keeping `PARM` one-to-one with v1; `restPose` is not written.
