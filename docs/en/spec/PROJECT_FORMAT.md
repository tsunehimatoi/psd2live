# Project format v2

[Documentation](../../README.md) · [中文](../../zh/spec/PROJECT_FORMAT.md) · [User guide](../guide/USER_GUIDE.md)

`.psd2live` is an unencrypted ZIP containing UTF-8 JSON and PNG raster resources. A saved project carries its source artwork and history without relying on the original PSD path. Export reports named `.psd2live.json` are not projects.

ZIP entry methods: PNGs (`assets/`, `images/`, view images) and a CMO3 source are compressed already and are written `STORED`. Other non-JSON files of 1 MiB or more (such as the source PSD) are sampled first: they are DEFLATEd (at the fastest level) only when the sample shrinks to under half at that level, and `STORED` otherwise. JSON and every other entry are DEFLATEd as before. `manifest.json` is the last entry. Readers accept either method and any entry order; archives from earlier builds, with every entry DEFLATEd and the manifest among the others, open as before. Methods and entry order are not part of the format's identity; manifest hashes cover the uncompressed bytes.

## Archive layout (v2)

Saves write v2. Each history revision is split into content-addressed document nodes, which revisions share where they did not change; each node carries its own schema version.

| Path | Content |
| --- | --- |
| `manifest.json` | Format, version `2`, project UUID and SHA-256 inventory |
| `source/original.psd` or `source/original.cmo3` | Original imported source; artwork-created projects generate a PSD source |
| `history/HEAD.json` | Current node and node order |
| `history/nodes/` | Immutable parent-linked nodes and metadata (as in v1) |
| `history/revisions/<key>.json` | Per revision: `{schema, nodes:{kind:hash}, overrides?, clips?, payloads?}` |
| `document/nodes/<kind>/<sha256>.json` | Document nodes `{schema, kind, value}`; kinds are `source` (layers and groups), `generation-source` / `mesh-source` (a `placement-source` written by older builds is ignored on read), `layers` (visibility, soft deletion, classification, parent, mesh and texture overrides), `settings`, `rig` (parameter, skeleton, swing, simulation, physics and other rig definitions), `journal` (authoring journal without overrides) and `document` (remaining fields) |
| `document/nodes/payload/<sha256>.json` | Content-addressed payload nodes for large journal entries, listed under `payloads` by a schema-2 revision |
| `document/overrides/<sha256>.json` | Generated overrides (`generated_override`) with their places in the journal |
| `document/clips/<sha256>.json` | Motion clips and generated-motion settings |
| `assets/` | Deduplicated RGBA rasters encoded as PNG |
| `auxiliary/assets/` | Staged artwork metadata |
| `auxiliary/views/`, `auxiliary/view-images/` | Spatial references of rendered views; older projects also hold every rendered image, which saving no longer keeps |
| `auxiliary/workflow/` | Reference packages, registrations and placement records |
| `auxiliary/tasks.json` | Agent task records and events |
| `workspace.json` | Durable UI layout, camera, selection, parameter preview, annotations and logs |
| `images/<hash>.png` | Log images |
| `rig/objects/` | Rig objects: those checkpoint records name (required by revisions with checkpoints) and those of each revision's authored rig |
| `rig/revisions/` | Optional, each revision's authored rig index (see "Authored rig" below) |
| `cache/head/` | Optional, disposable cache for rebuilding the head revision (see "Head cache" below) |

Document node, override and clip files are named by the SHA-256 of their bytes, checked on open. Splitting is lossless: opening joins every revision's complete document from its index, so revision IDs, node IDs and branches are unchanged. The complete rig model is never stored; a revision with an authored rig builds from it on open, any other from source, settings and edits. Auxiliary entries depend on features used; `cache/` holds only disposable rebuild caches and takes part in no identity.

### Checkpoints

The journal entry `rig_checkpoint` (`{op, v:1, authored:{header, frame, deformers, meshes}, issues}`) stores the authored rig as data at its place in the journal: `authored` is an authored-rig index as below (header and object hashes), `issues` what the regeneration merge that wrote it could not carry over cleanly (`{kind, target, detail}`; design in Chinese: [materialized rig](../../zh/spec/MATERIALIZED_RIG.md)). Replay starts from the last checkpoint: its authored rig (re-bound through canvas texture coordinates when the atlas changed) and the entries after it; the entries before it are history and no longer replay. The application writes a checkpoint when an edit changes what generation produces, at the first edit of a generated model, and once 32 entries follow the last checkpoint; users do not edit it directly. The latter two store the authored rig from before the edit and carry the previous checkpoint's `generated` snapshot over, so every generated project this version edits is revision 3.

Checkpoint objects belong to the document: the working store writes them to `<project>/rig-objects/` before the snapshots that name them, and a save archives them in `rig/objects/`.

### Authored rig

`rig/revisions/` holds each revision's authored rig: the generated base (with the skeleton bake) after the legacy static edits and the whole journal, before swings, simulation write-back and override merging (design in Chinese: [materialized rig](../../zh/spec/MATERIALIZED_RIG.md)).

| Path | Content |
| --- | --- |
| `rig/objects/<sha256>.bin` | One rig object (`RigIrObjects`): the frame (everything but deformers and meshes), one deformer or one mesh, named by the SHA-256 of its bytes and shared between revisions and checkpoints |
| `rig/revisions/<revision>.json` | `{header, frame, deformers:[...], meshes:[...]}`: the header and the object hashes in rig order |

- Objects use the binary form of the neutral IR (`RigIrBinary`, floats bit for bit) with a magic number, encoding version and object kind; reading accepts every version from `RigIrObjects.MIN_VERSION` on, a new IR field being read only from the version that added it.
- The header `{format:"psd2live-authored-rig", version:1, build, binding_key, pages, layers, bounds, face, warnings, skipped, visibility}`: meshes to texture pages, source layers and neutral bounds, the face centre, radii and initial tilt, generation warnings, entries the replay skipped, meshes whose layer visibility applies, and the atlas binding key. Floats are written as their raw bits.
- The revision key in a file name is the document's revision ID (`WorkspaceRevisions.of`), the key builds look it up by.
- The binding key digests the atlas placements, page sizes and texture layer rectangles the generated base was bound to. On open the current build packs the revision's atlas; under an equal key the stored texture coordinates are used as they are, otherwise (this build packs differently) they move onto the new tiles through canvas texture coordinates. An entry that cannot be read or fails its checksum is ignored and the revision builds as before.
- A save writes the authored rigs this process built for its revisions and those read from the opened archive; other revisions get none. `-Dpsd2live.materializedRigs=false` turns reading and writing off.
- Revision indexes enter no revision ID or document node, and a revision without one builds as before: from its journal's last checkpoint when it has one, otherwise by generation and replay. Imported CMO3 models write no authored rig.

### Head cache

A save may write `cache/head/`: expensive intermediate results of rebuilding the head revision that are safe to reuse, so opening can skip recomputing them. It is not authoritative: opening still rebuilds fully from source, settings and the journal; the cache only seeds generator caches in memory, whose own content keys decide whether an entry is used, so a rebuild with and without the cache gives a bit-identical model.

| Path | Content |
| --- | --- |
| `cache/head/manifest.json` | `{format:"psd2live-head-cache", version:1, build, generator, revision, language, entries:[{kind, file, sha256, bytes}]}` |
| `cache/head/skeleton-bake.bin` | The head's skeleton bake: a JSON header (bake version, cache key, skeleton definition, read set, re-parenting of unread objects, optional full hash) followed by the output model as binary IR (`RigIrBinary`, floats kept bit for bit; without atlas, source inventory and deform paths, which a hit takes from the new base rig) |

- Keys: head revision ID, application version (`Compiler.version`), the generator constant `ProjectHeadCache.GENERATOR` (raised when generation changes in a way the entries' own keys would miss) and interface language; any difference discards the whole cache. The skeleton cache key inside the entry (with `SkeletonRig.BAKE_VERSION`, everything the bake reads and the language) then decides a hit.
- Only the skeleton bake is cached (about 5–7 s cold, 1–7 ms on a hit); an entry above 64 MB is not written. A save takes the most recently used bake in the process whose skeleton definition equals the head's; without one no cache is written. `-Dpsd2live.headCache=false` turns writing and reading off.
- On open, a mismatched manifest format, key or entry checksum, or a missing, truncated or undecodable entry is logged and discarded, never failing the open; `cache/` is removed from the extracted directory after reading.
- The cache lives outside `history/` and `document/` and enters no revision ID, document node or history. Archives without it open as before. The archive manifest lists and verifies its files like any other. Earlier v2 builds unpack it with the rest and never read it, so they still open such archives; their next save drops the folder.

### Node schemas

A node or revision index moves to a newer schema only when it uses a newer field. Every other node is still written as schema 1, byte for byte, so an older project saves to the same node hashes and revision indexes as before:

| Schema | Applies to | New fields |
| --- | --- | --- |
| Node 1 | nodes without the fields below | — |
| Node 2 | a `source` or extra source-art node with a layer `rect`; `layers` with `textureOverrides`; `settings` with `atlas` or `atlasArrangement` | see texture fields below |
| Node 3 | a `source` or extra source-art node with a layer `transform` | the layer's whole-layer transform `[a, b, c, d, e, f]` (`x' = a·x + c·y + e`, `y' = b·x + d·y + f`, canvas units, y down), applied to the layer's frame (integer bounds and `rect`); texture coordinates are anchored to the frame and generation reads only the frame. Identity is not written |
| Revision 1 | the journal names no payload node | — |
| Revision 2 | the journal names payload nodes, listed under `payloads` | `{"$payload": "<sha256>"}` journal entries |
| Revision 3 | the journal holds `rig_checkpoint` records | the index lists the rig objects they name under `rig`, kept in the archive's `rig/objects/`; it may list `payloads` too |

Builds that read only schema 1 reject a schema-2 node or revision instead of dropping the new fields. This build reads node schemas 1–2 and revision schemas 1–3 and still rejects higher ones. A schema-3 index must list `rig`; opening checks that `rig/objects/` holds every listed object and refuses the archive when one is missing. Builds without schema 3 cannot build such a revision, so they refuse it. A schema-2 revision lists at least one payload, and its journal may name only payloads it lists. When saving, a revision that already has a schema-2 node moves authored journal entries of 512 characters or more out into payload nodes (the reference in the journal node is under 80 characters), writes each payload once, and gets a schema-2 index; builds that read only schema 1 cannot open such a revision anyway. Documents without newer fields still write only schema 1, with byte-identical nodes and indexes. Each revision of a long history no longer stores a whole journal, so archive size and save time no longer grow with the square of the revision count.

### Texture fields

All four are optional; absent means the earlier behaviour and keeps earlier revisions. Generation and atlas packing read them (rules in the Chinese [document layer](../../zh/spec/DOCUMENT_LAYER.md#逐层尺寸) reference).

- Source layer `rect: [left, top, width, height]`: the float canvas rectangle in canvas units - where the layer sits on the canvas, never changed by pixel operations. It is omitted when absent or equal to the integer bounds, which stay the enclosing box and must contain it. The layer raster may have any resolution; raster pixels over the rectangle size are the layer's native density, possibly different per axis.
- Document `textureOverrides: {layer ID: {density?, lock?, pin?: {page, x, y}}}`: per-layer texture density multiplier (default 1, the raster's own resolution), lock (keeps the density when the atlas has to shrink) and fixed atlas position (page and top-left page pixel); all-default entries are not written. Stored in the `layers` node.
- Setting `atlas: {pageSize, maxPages, padding}`: the atlas budget. Without it the budget is the legacy `atlasSize` / `texturePadding` with the default of 8 pages; missing members fall back the same way.
- Setting `atlasArrangement: {fitStep, tiles: {layer ID: {page, x, y, rotation?, footprint?: {cell, columns, rows, bits}}}}`: a stored atlas layout. `x`, `y` are the top left of the tile's upright rectangle and `rotation` (degrees, 0 when absent) turns it about its centre, the way Umamo's placements turn (clockwise with y down). Without it the atlas is arranged automatically on every build (the earlier behaviour); with it every tile keeps its spot and every unlocked tile the common fit `fitStep / 4096`. `footprint` is the cells of the layer raster its meshes cover (`cell` raster pixels each, `bits` a row-major bitmap as a Base64 `BitSet`); a tile with one writes only those cells. A newly imported project stores its first layout.

## v1 compatibility and migration

v1 archives still open. v1 stores each revision as one complete snapshot:

| Path | Content |
| --- | --- |
| `workspace/<projectId>/HEAD.json`, `history/nodes/` | Current node, node order and node metadata |
| `workspace/<projectId>/history/snapshots/` | Each revision's complete document: source layers, settings, structure and edit overlays |
| `workspace/<projectId>/blobs/` | Deduplicated RGBA rasters (PNG) |
| `workspace/<projectId>/assets/`, `views/`, `view-images/`, `workflow/`, `tasks.json` | Auxiliary data |

The other entries (`manifest.json`, `source/`, `workspace.json`, `images/`) are as in v2. Opening a v1 file reads those snapshots directly into the same in-memory documents as v2; the next save writes v2 and, before replacing the file, keeps the v1 original beside it as `<name>.v1.psd2live` (an existing backup is not overwritten). The migration changes only the storage layout: every revision, branch, annotation, asset, view and task record is kept. The working directory keeps the snapshot layout internally; packing and unpacking happen on save and open.

Working-directory snapshots differ from v1 only in shared content. Newly written snapshots store a non-empty `rigEdits.authoringJournal` as `{count, chunks:[key...]}`, with the entries in content-defined chunks at `history/journal/<key>.json` (a chunk ends after an entry its digest selects, about 4 entries on average and at most 16 entries or about 64K characters; the key is the SHA-256 of the entries' SHA-256 digests). An `rigEdits.importedCmo3` of 64K characters or more becomes `{payload: sha256}` with the text at `history/payloads/<sha256>.txt`. Appending entries adds only the trailing chunk; closed chunks are shared between revisions. Reading checks the keys and restores the original snapshot with its key order; packing to v2 restores it first, so archive nodes are unaffected. Snapshots are written as compact JSON. Builds without chunk support fail on the object form rather than dropping the journal. v2 unpacking writes snapshots in this shared form too, and reading parses each entry once and gives every revision the same object (beyond shared chunks: a journal's last chunk ends at its end, so the trailing entries of neighbouring revisions are merged by content); whole snapshots written by earlier builds still read, and both forms can coexist.

Internal filenames may hash logical IDs rather than display names. PNG resources retain RGB under transparent alpha. Revisions share rasters.

## Save and recovery

Saves capture immutable state and serialize writes. The writer builds and validates a temporary archive beside the destination, then replaces the destination atomically. Unsupported atomic replacement fails while keeping the previous project. Later edits remain unsaved after an earlier capture completes.

A save does not encode again what exists already: raster PNGs the working directory holds are hard-linked into the staging directory (or, where links are unavailable, copied keeping their modification time) instead of being encoded from pixels. Writing the archive reads each file once, taking its SHA-256 and CRC-32 as it is written and building the manifest from them; digests of `STORED` entries are cached in the process by entry name, length and modification time (opening a project records them too), so an unchanged raster saved again is copied without being hashed. After the temporary file is written and `fsync`ed it is read back as a stream: the entry set must be exactly the written entries plus the manifest, each entry's length and CRC must match what was written, and the manifest bytes must be the same; the archive is no longer extracted to disk and hashed again. A stale cache cannot produce a wrong archive: a `STORED` entry's bytes are checked against its CRC and length while written and once more when read back, and any mismatch fails the save and keeps the existing file. Opening still verifies every SHA-256.

Opening decodes each distinct raster of the history once, on several threads (bounded by the cores and the heap). 8-bit, non-interlaced RGBA/RGB PNGs, which the working directory writes, decode straight to RGBA bytes; other PNGs are read through ImageIO. Either result is checked against the raster digest, and the checked digest seeds the revision and rig-stage caches so it is not computed again. Checking the asset catalog reads asset metadata and confirms each raster file exists without decoding pixels (they are checked when the asset is loaded), once per open.

Ordinary saves do not append a history node when content already matches HEAD. Explicit checkpoints may record unchanged content. A failed save is not durable completion; inspect the error state.

All branches are retained. Undo follows the parent, redo selects a successor, and editing from an old node creates a branch. Renaming, annotating or hiding branches does not rewrite original nodes or remove assets.

New parameter creation, updates and deletion are stored in `rigEdits.authoringJournal` and replay in edit order. Deletion therefore runs after earlier keyforms and the last default-value update. Legacy static parameter overrides keep their original replay order; existing snapshots, revisions and node IDs are not rewritten. Channel keyforms persist alongside geometry edits.

The internal `canvas_mesh_rebuild` journal record stores parent-local vertices, triangles, texture coordinates in source canvas pixels, old vertex interpolation sources and glue vertex mappings. Optional `neutral_bounds` records the replacement mesh's neutral canvas bounds. A first replacement of an imported CMO3 canvas base can include `previous_parent_points`: replay rebases ordinary and blend forms onto those neutral parent coordinates before interpolation, and converts paths through their original triangles. Replay checks the previous geometry SHA-256, parent and artwork identity (when base generation no longer makes the recorded parent, such as the arm hang warps once a skeleton is enabled, it checks the vertex count only and moves the points into the current parent's space through the affine fit of the recorded points to their canvas texture coordinates; see [DOCUMENT_LAYER](../../zh/spec/DOCUMENT_LAYER.md#父级不再生成)), reconstructs UVs against the current atlas, and migrates keyforms, blend shapes, paths, vertex groups and glue. Mesh-only setting changes retain preceding journal and creation records and append replacements without rewriting history. The archive format version is unchanged.

The internal `canvas_mesh_create` record captures meshes absent from the original generation input: stable ID, source and layer identity, transparent coverage bounds, parent and part, static material, local vertices/triangles, canvas texture coordinates, generated parameters/keyforms/channels and paths. Replay resolves the current atlas and requires an unused mesh ID (a record with `replace: true` replaces the mesh with that ID, keeping its draw-list and part position, or creates it when there is none; placing an imported image writes one when the checkpointed rig already holds the mesh) and existing parent and artwork, validating geometry, keyforms and raster dimensions. First visible painting on an ordinary transparent layer or mouth creates meshes automatically; subsequent clearing retains their geometry and bindings. Mesh settings update creation geometry in the current candidate and persist rebound paths and weights while previous history snapshots remain immutable. The archive format version is unchanged.

The older internal `canvas_source_partition` record (now written only for imported CMO3 models) stores the source mesh identity, local-geometry fingerprint, parent, original-vertex ownership and each piece's mesh/layer identity, name, visibility, local geometry, canvas texture coordinates and vertex ancestry. Replay copies bindings at their original journal position and resolves the current atlas; deleted source meshes are filtered afterwards. Component Glue retains pair order. Simulation targets, materialized offsets and Glue roles migrate in the same document candidate. Transparent generation placeholders retain texture coverage without creating duplicate meshes. Historical nodes and the archive format version remain unchanged.

The internal `art_primitive` record notes a polygon, component or front/back depth split of a generated model: at its journal position it removes the meshes listed in `supersedes` and the parts take their place. The original layer leaves `source` and stays only in the frozen `generationSource`; it is not added to `deletedLayerIds`, and the parts are ordinary source layers. A split is a regeneration: when the generators made the original mesh, the record is version 2 (`v: 2`) and only declares what the generators build the parts from (the rest canvas mesh, layer and classification, with an empty `authored` object), and the user's changes to the original carry onto the parts through the three-way merge; when they did not (a mesh the journal created), the record is version 1 and the parts carry all their data as the user's own objects. Either way a `rig_checkpoint` follows the record, which is never replayed. Fields and the handling of the generation input are described in the Chinese [document layer](../../zh/spec/DOCUMENT_LAYER.md#拆分物化画元记录-art_primitive) page.

Records written by earlier versions are still read: version 1 records carry their parts whole, and version 2 records keep the material fields and user data that differ from the generated ones in `authored`, plus `geometry_residual` / `channels_residual` and `generated_override` entries right after the record; they replay as before where no checkpoint follows them. The first edit of such a project checkpoints the current authored rig where its journal ended, after which those records are no longer replayed. `canvas_source_partition` and `canvas_depth_split` in older projects are handled the same way, and the archive format version is unchanged.

The internal `mesh_generation_baseline` marker retains the preceding global mesh settings, per-layer overrides and alpha threshold for base generation. Current document settings remain authoritative for requested regeneration. Subsequent layer/global updates and resets regenerate from saved mesh input or current pixels, convert through the actual neutral parent, and append ordered `canvas_mesh_rebuild` records. Ordinary, partitioned, created and imported CMO3 meshes share this replacement path. Ordinary/blend forms, paths, vertex groups, Glue and simulation offsets migrate to the new topology. Bakes update vertex counts while retaining their original fingerprint and quality metrics so stale-input diagnostics remain honest. Hidden meshes regenerate before filtering and use the replacement topology on restoration. Older documents gain the marker only in a new candidate after an actual mesh change; earlier revisions, history nodes and archive version remain unchanged. Imported models normalize only replaced texture addresses. Fully transparent artwork retains its authored topology. Each replacement interpolates the preceding topology's offsets; resetting settings after coarsening cannot recover discarded high-frequency detail.

Soft deletion uses the document's existing `deletedLayerIds`, retaining source pixels, ordered authoring and earlier immutable history nodes. Projects containing creation records, depth splits or an internal `layer_membership` marker rebuild and replay the complete input before filtering deleted layers and derived lips from the active preview or export, removing mask references, glue, paths and vertex groups. Restoration replays saved IDs and bindings. The first actual deletion or restoration in an ordinary project fixes the current generation range and all source/Drawable identities in the new candidate, adding a `layer_membership` marker containing only `op`; restoration retains this identity baseline. Imported CMO3 models keep their original IDs and coordinate frames and only add the marker. Earlier documents without these records retain their original generation rules without automatic upgrades or rewriting revisions and nodes. Mesh settings changed during deletion also regenerate hidden meshes and persist rebound paths and weights; atlas constraints use the complete input. GUI and MCP deletion and selected/all restoration share the candidate commit, with repeated operations adding no history. Existing journal and baseline fields are used; the archive format version is unchanged.

An optional `generationSource` in a history document uses the same canvas, layer and raster-blob encoding as the current artwork. It retains the original raster shapes used to generate the base rig while `source` supplies painted pixels. Preview and export generate the same geometry, remap UVs through source canvas coordinates, and pad transparent coverage after a tighter crop so kept meshes cannot sample a neighboring tile. Optional `meshSource` uses the same encoding and updates the explicitly rebuilt or first-created layer's input, preserving generated lip contours and colors during later painting that keeps the mesh. A layer added after the generation input was frozen (such as an agent-added image) is not in it and generates from its current pixels; the first paint or image replacement of such a layer first pins its previous pixels into `generationSource` as its mesh outline is traced: at canvas resolution (one pixel per canvas unit over its integer bounds) under `meshTrace = CANVAS`, or under `TEXTURE` as its texture at its own density over its integer bounds (the float rectangle folded into the raster), so painting that keeps meshes keeps its topology and a rebuild migrates from the mesh it had. Depth-split fronts, art primitive parts and partition pieces keep their own records; placing an asset layer again drops the entry so its mesh follows the new placement. Existing revisions are not backfilled. Updating generated texture inputs is a durable change even when vertices stay the same. Optional `placementSource` uses the same source encoding to retain the cropped pixels of file-imported images and identifies such layers. Import and placement no longer resample pixels and only change the layer's canvas rectangle (`rect`); a layer that an earlier version scaled to its bounds returns to these pixels when placed again. All four sources and history nodes share deduplicated PNG blobs. Omitting the new fields preserves existing revisions and node identities.

Captured GUI pixels and MCP raster gestures prepare the same document candidate. Clearing all pixels retains the layer, base mesh and authored bindings even when mesh rebuilding is requested. CMO3 painting keeps unrelated meshes' original atlas and UVs, adding painted texture pages only for modified meshes.

Reopening builds the model from the revision's stored authored rig or the journal's last checkpoint, and rebuilds from source, settings and replayable edits when there is neither. Native handles, connections, active jobs and animation clocks are not serialized. Agent task records do not automatically restart execution.

Committed parameter poses and locks are held per workspace in runtime auxiliary state and projected to the existing workspace/canvas fields in `workspace.json`. Saving uses captured durable values and locks for every workspace, excluding transient GUI values and evaluated frame caches. Queries, preview changes and snapshot application normalize older poses against current parameter definitions without rewriting records or history on reads or logical no-ops. Duplicating a workspace registers its copied durable pose.

Optional `assetCatalog` in `workspace.json` stores `{version:1, assets:[ID...], workflow:[ID...]}`, the committed membership of assets and reference/registration records. Resources retain their existing auxiliary directories and do not enter the Rig journal. Asset writes advance durable runtime state without changing the Rig revision or history. Saving copies resources from the captured catalog, excluding later submissions. Opening older files without a catalog freezes membership from existing immutable records without rewriting history; new projects start with an empty catalog. Opening validates member existence, project identity and reference relationships. Inspection and composition use captured membership; failures and cancellation before commit publish no candidate files.

## Validation

Opening validates version (1 or 2), inventory, hashes, document node hashes and schemas, payload references, rasters, history references and HEAD. Unknown document node kinds or newer schemas are rejected rather than dropped. Duplicate entries, escaping paths and unsupported versions are rejected. Extraction limits are 1,000,000 entries and 64 GiB of actual decompressed bytes, not declared ZIP sizes.

Manual edits must preserve references and update inventory hashes. Use the UI for ordinary editing and history work. Legacy `.rgba.gz` recovery resources remain a compatibility read path, not the primary write format.

## Entry points

- Import PSD: `Ctrl+Shift+O`; open project: `Ctrl+O`.
- Save / save as: `Ctrl+S` / `Ctrl+Shift+S`. Save As starts beside the current project with a suggested name that is neither the current project file nor an existing file (`name-2`, `name-3`…); an existing target is confirmed in the window before it is replaced, with its own message when it is the current project file. A save or Save As asked for while an edit is still being applied is queued, shown in the status bar and run when the edit finishes; a later `Ctrl+S` does not turn a queued Save As into a plain save. System file dialogs are owned by the main window; asking for one while another is open brings that one to the front.
- MCP: `project_save`, `project_save_as`, `project_open`, and `history_checkpoint`, `history_list`, `history_checkout`. Save-as and open use absolute paths without requiring a UI file selection. Mutations carry the current `project_id`, opaque `state` and unique `request_id`; reopening the same node invalidates earlier state tokens. Public summaries do not expose every internal persistence field; see the [MCP contract](../../zh/agent/MCP_AUTHORING.md).

[ProjectArchive](../../../src/main/kotlin/io/github/psd2live/project/ProjectArchive.kt) · [ProjectFormatV2](../../../src/main/kotlin/io/github/psd2live/project/ProjectFormatV2.kt) · [ProjectRepository](../../../src/main/kotlin/io/github/psd2live/project/ProjectRepository.kt) · [WorkspaceStore](../../../src/main/kotlin/io/github/psd2live/project/WorkspaceStore.kt)

New independent Warp creation uses ordered `authoringJournal` entries with stable IDs, parent and mesh references, and fitting options. New commands do not append legacy static Warp/structure records. Existing legacy records retain their original replay order and immutable history nodes.
