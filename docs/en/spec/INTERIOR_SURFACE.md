# Interior surface authoring

[中文](../../zh/spec/INTERIOR_SURFACE.md) · [Discussion #44](https://github.com/tsunehimatoi/psd2live/issues/44)

## Scope and workflow

An optional Mesh keyform generator reshapes existing interior texture through a virtual curved surface. It does not synthesize textures/shadows, simulate physical cloth or require a depth model. Projects without this feature keep the existing path.

Select a generated layer and enable **Interior surface** in the right dock. Releasing a slider commits one history edit. Failed generation retains the draft and previous valid result. Disabling retains settings without rebaking. Delete removes the recipe and owned physics and is undoable; private inputs referenced by other authored content are adopted and retained. **Solo and center** temporarily fits the selected layer; a second click restores that canvas's visibility and camera without moving model objects.

The native 2D parameter pad uses actual source ranges and defaults; right/down are positive. Releasing holds the target while physics continues. Optional mouse following is off by default and mutually exclusive with the pad. Its neutral is the isolated layer's rest center, or the main canvas center in the full view. Mouse gains affect audition only. Explicit parameter poses and exported ranges are unchanged.

Local audition uses temporary aliases in the edit canvas, leaving body/head inputs and document history unchanged. Switching project, workspace, canvas or target clears the temporary projection. Whole-character playback uses the existing Preview workspace.

## Settings

| Setting | Range | Meaning |
| --- | --- | --- |
| Horizontal / vertical amount | Each 0–2 | Independent material-axis gains |
| Bulge / pressure | Each 0–2 | Virtual arch and bias; either zero removes surface displacement |
| Fold amount | 0–2 | Contribution of FOLD guides; no effect without fold guides |
| Contour retention | 0–1 | 0 releases the visible outline, 1 protects the source alpha contour; no top attachment |
| Easing and propagation | Toggle | Native project physics drives the shape regions |
| Response time | 0–0.6 seconds | First-stage response, without changing the global clock |
| Propagation time | 0–0.5 seconds | Following-region delay |
| Propagation strength | 0–1 | Following-region weight; zero removes the split |
| Direction endpoints | Each coordinate 0–1 | Layer-relative propagation direction; distance at least 0.05 |
| X / Y mouse gain | Each 0.1–1 | Uniform gain, for mouse audition only |

Guides store source-pixel points, positive radius, amount and an optional pitch-only flag. TOPOLOGY guides do not produce folds; FOLD guides do. The present UI exposes numeric point controls; on-canvas guide handles are not implemented.

## Surface and generated representation

`InteriorSurface.compile/evaluate` owns the static formula: original mesh, RGBA, settings and guides produce displacements on unchanged topology. Arc-length registration of an arch redistributes texture; contour retention blends global and row/column registration and edge weights. Neutral returns the original coordinates. The alpha contour is distinct from the rectangular mesh boundary; there is no hanging root constraint. Separate material axes and the longitudinal aspect calibration are retained. Area guards limit folding; sampled checks are not a continuous-domain proof.

`InteriorBasisKeyforms` samples X/Y curves and locally gated mixed correction rows at authoring time, then emits native blend fields. The new entry uses a 4-source-pixel sampled target; conservative scaling error is included in diagnostics. Budget/safety failures reject generation. Visual acceptance of one material does not establish invisible error for all materials.

`NativeInteriorAuthoring` resolves source raster positions through `LayerTexture.rasterOfUv`, then maps displacements to Mesh-local coordinates using the differential of the original UV triangles. Existing mesh, UVs, skin weights, parent deformers and authored keyforms remain intact. The editor does not automatically remesh.

Existing NORMAL/BLEND inputs may be read, provided they do not repeat and their defaults lie inside their ranges. Omitted bindings prefer standard body X/Y, falling back to private sources; an explicit empty ID creates a source. Nonzero defaults are represented with F(0) constants and gated residuals while retaining Cubism blend neutral VALUE 0. Applied fields include current grid default offsets and scalar references to match native subtraction semantics and avoid neutral drift.

Image-guided remeshing, manual mesh tools and on-canvas surface/guide handles are future-PR work. This entry retains original meshes; unintegrated structure-analysis/refinement code is excluded.

## Optional timing

`InteriorMotionGenerator` splits static fields into two smooth regions. The following weight is directional smoothstep times strength; the first region uses its complement. Each axis uses cascaded native ANGLE groups (at most four total), with a single owner per output. At rest both regions reconstruct the same static shape.

The existing PhysicsEngine, PhysicsCatalog and project physics FPS are reused; export uses standard physics3. Playback runs no image analysis, surface evaluator or sampler. Mouse mapping is `clamp(input,-1,1)*gain`; explicit poses bypass it. A further 600-frame rapid-reversal area check rejects unsafe configurations without silently reducing amplitude.

The maintainer approved retaining optional easing and propagation in PR #45 review.

## Persistence, replay and ownership

`rigEdits.interiorEffects` stores schema-1 settings, guides, source/created parameters, owned outputs, native blend fields, physics groups, mesh stamp and source hash. Float32 fields use gzip/base64; recipe identity uses a stable content digest. New empty optional fields preserve old document revisions.

Each `interiorEffect:<id>` graph node owns its generated parameters and target Mesh axes, while reading existing sources. Addition/removal, toggles and recipe changes invalidate downstream overrides, physics and motions. Generation follows the authoring journal; manual edits retain the existing GeneratedOverrides path.

Mesh, source-content or input-range mismatch retains the recipe but skips stale vertex data and asks for regeneration/rebinding. Disabling retains definitions in the editor but removes shape application and active groups. Every export shares the IR cleanup: owned inactive parameters are omitted only when geometry, other physics, motions and links do not reference them. Invalid effects explicitly skip their physics and report the omission in model warnings or target loss reports. Generator cache reads include sources and atlas; new recipes record initially missing hashes too. The unpublished RigInteriorEdit/interiorSurfaces experiment is removed, with no migration route.

UI and MCP share `interior_put` and `interior_delete`. `workspace_inspect scope=interiors` returns settings, guides, parameter IDs and issue status without shape buffers. Nested schemas describe units and ranges; settings/motion accept partial updates. `InteriorController` owns drafts, trial physics and view isolation; the main ViewModel delegates to it. The target is a mesh ID or `mesh:<ID>`; optional fields are enabled/settings/motion/parameters/guides, with native transaction-state checks. A target/enabled-only request with context fields is a lightweight toggle. Draft version and project-address checks reject late asynchronous results. Temporary audition is neither saved nor exported.

## Validation and limits

```bash
./gradlew test --tests '*InteriorSurfaceTest' --tests '*InteriorReferenceParityTest' --tests '*InteriorBasisKeyformTest' --tests '*InteriorMotionTest' --tests '*NativeInteriorEffectTest'
./gradlew test
```

Coverage includes synthetic alpha/holes/edges, independent numeric surface fixtures, native Mesh/MOC3 trajectories, nonzero asymmetric sources, toggles, real archive/history roundtrips and invalidation. Public fixtures are synthetic; reference videos, artwork and private models are excluded.

Local checks include native GUI/MCP, save/reopen, export generation and CPU/MOC3 trajectory comparison. Full official-SDK animated visual review, broader material coverage and all editing combinations remain open. Human feedback and finite numerical checks are separate evidence. Extreme settings can be rejected by area checks; mesh or texture quality is not reduced to pass.

### Retained-parameter panel replay

Deletion checks references on the completed model with the removed effect disabled, including deferred panel edits, rather than only running generators. Retained private definitions become authored parameters. The internal `panel_after_generation` ID list in their structure record preserves the original phase/order of pairing and moves. Later unlinking, checkpoints and archive reopen follow that same order. Explicit parameter deletion ends the phase hint, so recreating an ID cannot resurrect an old pairing. Documents without this metadata retain their existing replay behavior.

Async draft cleanup uses the originating project/workspace/target key and checks both draft identity and version. Late replies cannot erase new-scope or newer drafts. Mouse audition and its crosshair share project-generation/workspace/active-canvas/edit-mode routing; hovering an inactive pane cannot drive the active one.
