<!-- lang: zh en ko ja -->
> [中文](../zh/ROADMAP.md) · **English** · [한국어](../ko/ROADMAP.md) · [日本語](../ja/ROADMAP.md)

# Roadmap

[Project home](../../README.en.md) · [Capability record](STATUS.md) · [Changelog](CHANGELOG.md)

## Ownership and priority

This page records later directions only. It does not promise a schedule. Whether the code already has an entry point, whether a complete workflow is usable, and whether the result passes acceptance are judged separately. Evaluations are recorded only in [STATUS.md](STATUS.md). Finished items leave this page. See the [changelog](CHANGELOG.md).

This round's history-package retention, split references, hot and cold results, part configuration and folding, cache ownership, the GPL conversion bridge, and the controller pilot are integrated. Acceptance is in [STATUS](STATUS.md#2026-11-14-architecture-optimization-acceptance). Next, continue along the cache contract and the first-frame measurement boundary: prioritize breaking apart the atlas-page wait inside batched texture replacement, and split out the close-and-reopen cost of retaining the full history. Existing package-dependency exceptions are removed one by one. A new cache pilot must keep doing a full semantic diff and a benefit measurement. The one-minute goal for the full regression also still needs to be checked.

**Current product order: usability and real-machine acceptance of the user-defined plugin loop → optimization of the built-in "style" (fitting) effects → new effects such as automatic shadows.** The first closed loop for a user creating a capability extension through an agent (a standalone scaffold, contract discovery, declarative parameters, a first-time add, a no-restart undoable package swap, worker-process generation isolation, and save and reopen) has finished its code and its unit / end-to-end tests. Usability closes together with real agent tasks and visual acceptance in the desktop window. Cross-machine packaging and sharing is delivered later (the existing prebuilt-package install rules stay). A global UX rebuild is evaluated separately. Built-in effects both serve out-of-the-box use and provide examples for writing plugins. Cross-artwork stability comes first, then more kinds.

[Next] **Real-machine acceptance and cross-machine sharing of the user-defined plugin loop**: on the desktop window and a real agent host, finish a manual test and usability polish of "a new behavior that is not in the built-in list" (record it in [STATUS](STATUS.md)), and fill in cross-machine packaging and sharing.

Each new capability is placed in one of three tiers by its relationship to Cubism:

| Ownership | Meaning |
| --- | --- |
| p2lrt | Cubism does not support it at all, and it cannot be baked. It takes effect only in the p2l runtime, the web, Godot, and raster export |
| Compatible | Cubism does not support it, but it can be baked into keyforms, parameters, pendulums, or textures. Between key points there may be an approximation |
| p2ledior | Cubism supports it fully and losslessly, or it is a tool that only affects the editing process |

Priority is marked at the start of each item:

- [Next] Implement first in the current stage, and keep it to about five items at a time.
- [Later] After the current stage, or it depends on other items.
- [Long-term] Difficult, and hoped for in the future.
- [Ongoing] Filled in gradually as features move forward, with no single completion point.
- [Undecided] Whether to do it is not decided yet.

The end of the page also has "Not planned for now". The parentheses after each item are the ownership and the kind of work: **Deepening** (add capability to an existing system), **Optimization** (the effect and performance of the same algorithm or preset), **New** (a new system), **Refactor** (change the structure of an existing system).

## Stages

The current work first finishes the user-plugin extension loop described above, reusing the existing part-plugin stack. Finishing every general deform modifier is not a prerequisite. The table below keeps the dependency grouping of editor capabilities. It is not a schedule that competes with the current product priority. New effects that depend on general modifiers advance by the capability they need, so they are not implemented on the old deformers and then migrated.

| Stage | Contents |
| --- | --- |
| Independent capabilities | Editing, animation, and export features that do not depend on modifiers, scheduled by actual need |
| Stage goal | [Modifier stack refactor](#modifier-stack-refactor): write the design and the staged plan before work starts |
| Dependent capabilities | Features that depend on modifiers: lighting and shadows, a 3D-projection preset for interior shaping, a hair volume shell, deformer-like tools, 2D geometry nodes, and others |

## What is already in place

The following already have an implementation and are no longer listed as to-be-implemented. Specific gaps are under each topic below.

| System | Already present |
| --- | --- |
| Automatic generation | Layer-name recognition, adaptive meshes, a head-and-body deformer chain, facial parameters, and preset motions. Brows and eyes (eye white, iris and highlights, eyelashes, closed-eye line) show through the bangs |
| Skeleton and skinning | Automatic skeleton (torso, limbs, tail), create / extrude / bind, weight editing, FK / IK, saving a pose, and joint shaping. Canvas skinning, baked into native rotation deformers and keyforms. p2lrt advanced mode skins along an arc |
| Physics and simulation | Pendulum physics, XPBD cloth and hair simulation, vertex-group weights, and baking |
| Mesh | Automatic topology, subdivision, point placement (point / line, delete edges, retriangulate), replotting points from texture structure, Glue, and copying mesh topology |
| Deformation and keyforms | Deform brushes, soft selection, Warp / Rotation / deform paths. Keyform copy and paste (mix ratio, flip, several shapes), restore, interpolate, mirror, multi-key editing, and temporary deform |
| Interior shaping | A virtual surface that keeps the outer contour lets the original texture gather, spread, bias, and crease inside the mesh. It bakes into native mesh keyforms, with optional easing and drive. The panel, the canvas handles, and MCP (`interior_put` / `interior_delete`) share one recipe (the specification) |
| Paint | Paint sessions. Brush, pencil, eraser, fill (including non-contiguous fill), eyedropper, shapes, gradient, and blur. Selections (rectangle, ellipse, lasso, polygon, magic wand; add, subtract, and intersect; feather; invert), stabilization, symmetry, and protect transparency (the tool comparison) |
| Editor | Seven canvas modes, multiple canvases, solo, reference images, materials and layout, and branch history. Grid, guides, onion skin, and random pose. Object copy and paste, find and replace, and ID conversion. An interactive tutorial, and help arranged like the Cubism manual |
| Animation | Timeline, keyframes and curves, bookmarks / mask / freeze / markers / motion tracks, a seamless loop, and parameter looping. Procedural blink, breath, gaze, and lip sync. Expressions, pose switching, and motion events |
| Textures | Atlas packing, per-layer density, and upscaling |
| Engine, formats, and export | `:umamo`, the neutral rig IR, the document layer, and project format v2. The workspace treats the runtime as the only authority, and the editor draft rebases field by field. moc3 / cmo3 / VTube Studio / Unity / raster and other targets, and the loss report. Cubism 5.3 blend modes such as Screen, and downgrades for older versions. exp3, pose3, userdata3, and HitArea |
| Runtime | `.p2lrt` and the Rust runtime (pose-by-pose agreement with the editor evaluator), the Godot 4 node, the WebAssembly web player, and every blend mode |
| Agent | Local MCP, 180+ public operations, shared edit commands and history with the interface, and atomic batches and dry runs |

## Modifier stack refactor

- [ ] [Next] **General deform modifiers** (compatible · refactor): on the existing preset / part plugin stack, express Warp, Rotation, and others further as non-destructive modifiers (Lattice, Bend, Twist, Wave, and others), baked into keyforms. Workspace runtime authority and part plugins are already in place. The general deformation layer still needs a staged design, which at least answers:
  - Where a modifier sits in the document layer and in the generator dependency graph, and how that relates to `RigEditOverlay` replay order and generated-result overrides.
  - How existing Warp / Rotation / deform paths / skeleton skinning / interior shaping are expressed as modifiers, and how old projects stay compatible on replay.
  - Bake precision and the loss report when export is downgraded to Cubism, and whether p2lrt keeps the modifier as-is.
  - Staging: which modifiers come first, and which features that depend on them unlock in which stage.
  - Automatically generated parts also join the modifier stack, and can be "applied" into ordinary layers. For example an upper and lower lip outline, after apply, disconnects from the mouth-shape settings and can be painted directly ([#54](https://github.com/tsunehimatoi/psd2live/issues/54); painting on the lip line currently only shows a notice).
- [ ] [Later] **2D geometry nodes** (p2ledior / compatible · refactor, difficult): a generator refactor. A node graph generates parts procedurally. Static generation is lossless. Procedural animation needs to be baked. This follows the modifier stack immediately.
- [ ] [Long-term] **Deformer-like tools** (compatible · refactor): array / instancing, frayed edges, generating parts from strokes, and transformation. Together with the modifier stack, this is one deformer refactor.
- [ ] [Ongoing] Split the internal stages of rig generation into independent graph nodes. Simulation keyform overrides written back as blend shapes.
- [ ] [Ongoing] Registration, selection, and extension for preset implementations: a registry of parts, base algorithms, modules, and recipes. Presets and budgets that switch modules by parameter count, and inference and correction of part roles, are already in place ([Rig preset architecture](spec/RIG_PRESETS.md)). The recipe libraries for physics, swing, simulation, and motions are not registered as modules yet. Blend-shape output and the downgrader, module versions, and the connection to "Update generated results" are still to be done.
- [x] **Automatic generation reaches hand-tuned quality** (compatible · deepening): eyes (fitted eyelids, smiling eyes, wide open, emotion, gaze with the lid, aftershock, highlights, iris squash), brows (fitted brows, brow shape, brow-and-eye coupling, follow the blink), mouth (staged opening, in-between mouth-shape keys, swap the line when closed, vowels, face-capture mouth shapes, the jaw, the cheek pushing the lower lid, depth inside the mouth, spring-back), a solid head turn and clipped face overlays, hair-strand chains, skirt / ribbon / pendant chains and chest bounce, blush and gloom and richer standard expressions, natural blink and gaze sweeps, a VTube Studio mapping tuned for the modular rig, and canvas feature-point correction are implemented. Four pieces of artwork passed review. The old and new recipes coexist ([Rig preset architecture](spec/RIG_PRESETS.md)).
- [ ] [Later] Remaining automatic-generation items: brow spring and emotion-fan derived amounts; dental-arch segments, tongue out, and light and dark inside the mouth; the neck and the body sharing a head tilt, and the neck shadow clipped to the neck while it hangs from the head; switching front-and-back hair draw order on a head turn, bangs trembling with the blink, and a hair-quality knob entering the pendulum parameters; a passive arm swing chain, hard ornaments pinned to cloth, automatic Glue, and conditional modules for animal ears / tail / wings; switch choreography (product gating, transitions, impulses) and manga symbols and costume changes; speech curves, driving layered art, and VBridger vowel mapping; dragging an offset of the driving mesh on the canvas, and floating macro knobs; after the user edits a base keyform, re-basing absolute-form blend shapes to match.

## Lighting and shadows

These are later capabilities that depend on modifiers. They advance on their own after the modifier capabilities they need are ready. Product priority comes after the user-extension loop, the related usability work, and built-in style optimization. The first scope picks one acceptance target from "an existing shadow layer moves with the angle" or "bangs cast onto the face". Only a static key light is in scope, and it serves shadow layers the artist has already painted. Dynamic light is left to the runtime only. A volume proxy does not aim to widen the head-turn angle. Large angles can only come from multi-view artwork.

- [ ] [Later] **Artist shadow / highlight layers move with the angle** (compatible · new): recognize a clipping mask plus a Multiply / Screen layer, or add SHADOW / HIGHLIGHT / RIM tags, and let it move with the angle and fade in and out. Light and dark gathering inside the contour can reuse interior shaping.
- [ ] [Later] **Static key light** (compatible · new): the light-dark boundary, bangs cast shadow, angel-ring highlights, darkening the far side, and contact shadows between layers, baked into shadow-layer keyforms and deformer color keyforms.
- [ ] [Later] **Runtime dynamic light** (p2lrt · new): per-pixel lighting from a normal map. Export to Cubism downgrades it to static light and writes that into the loss report.
- [ ] [Later] **Hair volume shell** (compatible · new, difficult): on looking down or up, the bangs leave or meet the forehead, baked into the angle axis.
- [ ] [Long-term] **Inverse light baking** (compatible · new): infer normals back from the light and dark the artist already painted, for the static key light to use.

## Default rig quality

- [ ] [Later] **3D-projection presets based on interior shaping** (compatible · deepening): an eyeball sphere (the iris and highlights slide along the sphere and compress near the edge), torso twist (on body X, clothing folds and patterns shift sideways inside an unchanged outer contour), and surface-space textures (a pattern moves stuck to the surface, rather than translating with the layer). Consider this together with Cubism's "3D rotation expression". Do it after interior shaping has moved to a modifier.
- [ ] [Undecided] **Rigid-body hold** (p2ledior · optimization): the default rig automatically inserts rotation deformers for buttons, glasses, and similar. Whether it depends on modifiers is confirmed at the planning stage.
- [ ] [Long-term] **Deformation-aware texture density** (p2ledior · optimization): when the default atlas is allocated, automatically raise the resolution of regions that will be enlarged.
- [ ] [Long-term] **Missing-region hint image** (p2ledior · new): compute the unpainted regions each angle would reveal, and prompt to paint them in or leave margin.
- [ ] [Long-term] **Multi-view turn** (compatible · new): front, 3/4, and side art sets correspond to deformation plus a crossfade.
- [ ] [Ongoing] Improve dedicated presets for the face contour / shadows, local body motion, clothing physics, animal ears and tails, and similar.
- [ ] [Ongoing] Simulation quality and performance: shorten bake time (back hair is about 6–8 seconds), per-mode fitting of thin hair strands, head-turn roughness after mass is allocated by area, retest model presets under the new material model, and exported swing amplitude that is too small while dragging.

## Editing tools

Item-by-item comparison of each mode with PS / SAI and Cubism is in the gap table: paint, mesh, and [deform](spec/DEFORMER_AND_PARAMETER_SPEC.md#tool-comparison-and-gaps).

- [ ] [Ongoing] **Remaining paint-tool gaps** (p2ledior · deepening): in the order artists need them, pressure (needs native tablet input), rotating / flipping the view, smudge and watercolor mixing, selection transform and liquify, an eyedropper that samples the merged image, and brush presets and flow.
- [ ] [Ongoing] **Remaining mesh-tool gaps** (p2ledior · deepening): mesh presets, placing points along a strip stroke, a custom symmetry axis, and others.
- [ ] [Ongoing] **Remaining deform-tool gaps** (p2ledior · deepening): multi-key editing of geometry at once, live symmetry while editing, automatic synthesis of a four-corner shape, "turn over" paste for Warp, flip-paste of an asymmetric mesh (mirror is used today), paste across parents (converted by canvas coordinates), path and perspective modes for temporary deform, and locking and hiding a single point.
- [ ] [Next] **Canvas editing of simulation obstacles** (p2ledior · deepening): capsule colliders (`obstacles`) can currently be authored only through MCP `simulation_put`. Also add a paintable texture direction.
- [ ] [Later] **Texture-mapping (UV transform) editing** (p2ledior · deepening): texture-mapping edits at per-layer pixel size.
- [ ] [Ongoing] Fill in blend-shape creation, limit curves (including Cubism's blend-deform weight limit), and the product editing flow.
- [ ] [Ongoing] Extend Glue pairing, weight editing, and repair diagnostics.
- [ ] [Ongoing] Organize the interface entry points for canvas visibility and export visibility.
- [ ] [Undecided] An option for hierarchy drag-and-drop to refit by appearance.

## Skeleton and physics

- [ ] [Undecided] **Inventory of skeleton and skinning gaps**: inventory them against common 2D skinning tools, then decide whether they go in stage one or follow the modifier refactor.
- [ ] [Long-term] **Soft bodies, rigid-body chains, and secondary motion** (compatible · deepening): extend physics and simulation, and bake them into pendulums and keyforms.
- [ ] [Long-term] **Bone constraints** (compatible · deepening): surface attach, contact constraints, ground lock, center-of-mass balance, and gaze locked on the viewer, baked into combined keyforms after the solve.
- [ ] [Ongoing] Global physics settings such as wind, more input / output types, and export of force fields such as wind.

## Animation

- [ ] [Next] **Parameter controllers and animation templates** (p2ledior · new): Cubism's parameter controllers and target settings, animation templates, and recording parameter operations to generate animation.
- [ ] [Next] **Physics and lip sync generating animation** (compatible · new): physics simulation generates animation (the simulation bake can be reused). Dynamic sync: audio-driven lip sync and its bake (procedural lip sync already exists).
- [ ] [Long-term] **Shape animation, image-sequence tracks, and frame skipping** (compatible · new): related to a flipbook atlas.
- [ ] [Long-term] **3D motion retargeting** (p2ledior · new): BVH and VMD converted into ordinary motion3 curves.
- [ ] [Ongoing] Version the default animations: an undoable "upgrade animations" entry that does not overwrite motions edited by hand. Confirm that the native bridge's `CubismLook` / `CubismBreath` are not stacked onto the workspace pose.
- [ ] [Ongoing] An editing entry for a motion's part-opacity curves (they drive pose switching).

## Layout and materials

- [ ] [Next] **Layout follow-ups** (p2ledior · deepening): drag a material in the material panel to a chosen place in the hierarchy tree (system files can already do this), merge the new material library with `asset_*` materials (`asset_*` is no longer used for new flows), and let layers waiting to generate take part in preview animation (batch-editing tags on a multi-selection of layers is already implemented).
- [ ] [Next] **Remaining material and reference items** (p2ledior · deepening): "Add reference image" in the main menu. A reference image carries its rotation when it enters the layer stack.
- [ ] [Later] **AI assistance for layout** (p2ledior · new): on top of the materials and layout that are already implemented, do alignment, cutout, and completion. See the design draft.
- [ ] [Ongoing] Improve reimport mapping, and keeping edits after the source artwork changes.

## Export and formats

- [ ] [Later] **Offscreen drawing and draw-order groups** (p2ledior · deepening): correspond to offscreen drawing and draw-order groups in the new Cubism.
- [ ] [Later] **Extended interpolation** (p2ledior · deepening): Cubism's extended interpolation and interpolation types. First confirm which moc3 version supports them.
- [ ] [Later] **Model templates and CSV** (p2ledior · deepening): merge this with "parameter templates that can be saved on their own, and a stable semantic interface".
- [ ] [Long-term] **Graphic paths (ArtPath)** (p2ledior · new): vector line objects. The volume is large.
- [ ] [Ongoing] Improve the export-loss display and target-version checks.
- [ ] [Ongoing] A tool that compares the fidelity of raster export and Cubism native rendering.
- [ ] [Ongoing] Extend the CLI's project workflow and multi-file batch processing.
- [ ] [Ongoing] Unify the project model and reference maintenance for sidecars such as Motion / Expression / Pose / UserData. Expressions, poses, and motion events are already in the project (`ModelSidecars`). Deleting a parameter and converting an ID update the references. Other dangling references are skipped on export.

## Runtime and stylization

- [ ] [Later] **Flipbook animation atlases** (compatible / p2lrt · new): inside Cubism this downgrades to switching among several meshes. Real frame animation is in the runtime.
- [ ] [Undecided] **Pixel rendering, halftone / screentone, and ASCII character art** (p2lrt · new): screen-space post-processing, usable in raster export. It is the same kind as the screen-space effects under "Not planned for now". Whether to keep it is undecided.
- [ ] [Long-term] **Gradient-map recolor** (p2lrt / p2ledior · new): live recolor is in the runtime. Pre-baking a palette before export is lossless.
- [ ] [Long-term] **Pre-baked atlases linked to stylization** (compatible · new): screen-space effects are lost.
- [ ] [Long-term] **The runtime writes textures** (p2lrt · new): doodles, dirt, stickers, and signatures. Cubism textures are read-only at runtime.
- [ ] [Long-term] **Particles** (p2lrt · new): a fixed trajectory can be baked in part.
- [ ] [Long-term] **AOV render-pass export** (p2lrt · new): depth, normals, UV, part ID, and motion vectors, for compositing or AI repainting.
- [ ] [Long-term] **3D props and attach points** (compatible · new): an attach point is a parent deformer. A prop is pre-rendered at several angles and then switched.

## Performance and the project

- [ ] [Ongoing] Incremental rebuild of edit commits: after painting or replacing an image, the regeneration check does a dirty check first for edits that do not change the outline, reuses the input set of `BoundBaseKey`, and no longer fully generates G′ and then throws it away. Atlas pages synthesized by the generation check do not pollute the preview's PNG segment reuse. Command objects share a runtime-level pipeline cache (meshes, `RigStageCache`, generated geometry), so the first commit does not cold-start.
- [ ] [Ongoing] Project history size: optional history compression (fold old branches, or keep only checkpoints that have a comment). The tradeoff against the "keep every branch" semantics has to be decided first. Rig objects in existing projects keep the old encoding. Only new checkpoints benefit from encoding by byte planes.

## Lite workspace and mobile

- [ ] [Later] **A lite workspace and mobile touch operation** (p2ledior · new): a simplified interface for beginners and for phone/tablet touch, hiding complex parameters and panels and keeping only the necessary tools a person cannot replace: automatic generation and gesture try-out, depth split (`source_split_depth` + the front-layer eraser), facial feature-point nudges, and part-error correction. Advanced edits from an external agent come in through MCP. It shares the `.psd2live` project and the `WorkspaceHost` command channel with the full desktop workspace. See [Lite workspace and mobile design](spec/LITE_WORKSPACE.md) (in design).

## Agent

- [ ] [Ongoing] Improve tool discovery, locating, occlusion understanding, and workflow hints. Reduce useless queries, image generation, and rework, and record the budget and the reason for stopping.
- [ ] [Ongoing] Cross-tool transactions, structured history diffs, and recoverable task execution.

## Cubism comparison gaps

In [`manual/index.txt`](../../src/main/resources/manual/index.txt), the help manual marks each Cubism manual page as the same, different, unique, or not implemented (`missing`). Pages that are still not implemented are already filed under the topics above. The following groups are left out for now:

- **Snapshots**: parameter snapshots and onion skin already stand in for them.
- **Scene panel and project panel**: multi-file management. It may overlap workspace tabs. Undecided.
- **Scenes and playlists** (importing a placed model, audio scenes, background and group tracks, scene management, pose-switch motions, playlists): closer to film production, and the same kind as backgrounds and scenes under "Not planned for now".

## Verification and documentation

An interface existing does not mean a task has passed. A document update does not mean real compatibility has been verified.

- [ ] [Ongoing] Visual acceptance in the desktop window for this round's new features: paint selections and gradients, point placement, interior-shaping handles, keyform tools and temporary deform, grid / guides / onion skin, object copy and paste and ID conversion, expressions / poses / HitArea, and timeline aids. Save and close while the editor and MCP edit in an interleaved way.
- [ ] [Ongoing] Official Editor round trip: real Cubism samples, opening / dragging / re-saving deform paths, `layer_art=native`, and reading back blend-deform modal parameters. Loading exp3 / pose3 / HitArea in Cubism Viewer and VTube Studio.
- [ ] [Ongoing] Simulation: visual acceptance of the example model's skirt and hair bake results in the official runtime.
- [ ] [Ongoing] A real load test of a VTube Studio model package.
- [ ] [Ongoing] Desktop-window acceptance and runs that include the Cubism SDK. CI (Ubuntu, Windows) does not include the SDK, native GL, or the desktop window. Those paths are verified only by hand on a local machine. Add Linux / macOS publishing and real-machine verification.
- [ ] [Ongoing] Agent: evaluate splits, hidden-region completion, variants, and nine-axis adjustment with samples. Compare the compact and full MCP tool sets with a real host, and decide from the call log whether to add intent tools.
- [ ] [Ongoing] Keep checking the in-app interactive tutorial against actual operation. As features update, keep the three-language home pages, the text quick reference, and the development guide in sync. Extend the topic references that currently exist only in Chinese, and add reproducible cases.

## Not planned for now

| Ownership | Capability |
| --- | --- |
| p2lrt | Screen-space textures, Matcap, UV flow, automatic ink lines, sticker-style paper texture and registration-color offset, color-script LUTs, LED / neon / shadow-puppet / frosted-glass / glitch / ukiyo-e / blueprint styles, AI style repaint, and time echo |
| Compatible | Back-face UV, mouth volume, neck connection, contour-line reconstruction, sticker white edges, paper-cut style, Grease Pencil live lines, keeping line width, squash and stretch, constraints / drivers, RBF pose interpolation, response curves, a procedural finger skeleton, and growth animation |
| p2ledior | Parallax eyes, automatic occlusion order, a volume-proxy side-view panel, mixed frame rates, transparent-edge bleed repair, small-size output optimization, multi-angle consistent painting, component rigs / rig recipes, rig migration, mirroring a character, solving a pose back from a reference image, transferring motions across models, comparing and merging rigs between versions, and an export optimizer |

Outside the product's scope (they belong to the player or the application layer, and they do not belong to a modeling editor): live-stream events and audience interaction, emotion-driven and LLM-driven behavior, a runtime state machine, touch and multi-character interaction, AR, camera and performance scripts, backgrounds and scenes, face capture, rhythm driving, sticker-pack export, and reverse export to VRM.

## Modifier algorithm plugins and performance

The execution order, the framework bar, and the per-item ledger are in the modifier-plugin migration execution specification. Validate the framework first, then migrate items in parallel. Extracting a kernel does not count as a complete migration.

- [Next] Built-in items already have per-item management, an entry-point summary, and a lock on external package dependencies. Continue to split out built-in packages that can be published on their own, and keep the historical binaries. The current entry points are still compiled with the application, and binding calculation still depends on the shared generation service, so this is not treated as a finished independent-publish migration. (compatible, refactor)
- [Next] Change built-in binding from extracting a whole template to computing the owned result directly, and keep the generated effect and the output IDs. The default preset, adding a single item, and switching presets go through independent plugins, and the host's old template-generation path is removed. Historical projects replay through the locked version. Generation and baking keep their current effect. Performance optimization is scheduled after the refactor is delivered. (compatible)
- [Later] Compute in parallel by the declared read and write dependencies, merge consecutive inputs, and cancel stale candidates. After timing the full boundary, decide whether to migrate local hot spots. (compatible, optimization)
- [Next] Fill in HTTPS / SSH credential environments, and acceptance of cross-language complex geometry and interface operations. Local Git distribution and worker-process failures have passed targeted acceptance, and this round's full regression passed. Basic install and evaluation passing in six languages does not mean the full plugin ecosystem or the performance goal has been accepted. (compatible, deepening)
