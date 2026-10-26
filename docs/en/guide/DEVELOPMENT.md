# Development and CLI

[Documentation](../../README.md) · [中文](../../zh/guide/DEVELOPMENT.md) · [日本語](../../ja/guide/DEVELOPMENT.md)

This page is for running from source, using the command line or contributing code. Release packages bundle their own runtime and do not need anything here.

## Requirements

- JDK 21, preferably the JetBrains Runtime (JBR) 21: release packages ship it, and on it the main window keeps its native frame (the system maximizes, snaps and resizes it) under the title bar the app draws. Other JDKs build, test and run the app too, with an undecorated main window and the whole title bar drawn by the app. Packaging (`createDistributable`, `packageExe` and the like) requires a JBR 21 SDK (with jpackage); `-Ppsd2live.allowNonJbr=true` packages with another JDK. Gradle runs through the bundled wrapper; no separate install is needed.
- Use `.\gradlew.bat` on Windows and `./gradlew` on Linux / macOS. Examples below use `./gradlew`.
- The official Cubism SDK is not required. Source builds use the built-in renderer; see [Cubism native preview](CUBISM_SDK_SETUP.md) for the native bridge.

## Running

```bash
./gradlew run                     # no arguments: start the GUI
./gradlew run --args="--help"     # CLI help
python tools/examples/fetch.py --case tml
./gradlew run --args="--input examples/tml/psd-input/tml.psd --output build/example-output"
```

On Windows, `run-gui.bat` in the repository root also starts the GUI. Without arguments the GUI starts; with arguments the CLI runs and requires `--input` (except for `--help`). The CLI writes export files directly from a PSD and does not create an editable `.psd2live` project.

## CLI options

| Option | Default | Meaning |
| --- | --- | --- |
| `--input <path>` | required | Layered input PSD |
| `--output <path>` | `psd2live-output` next to the PSD | Output directory |
| `--lang <zh\|en\|ja\|ko>` | system language | Log language |
| `--clear-user-data` | — | On its own: deletes the current user's settings, workspace store, and the runtimes and caches under `~/.psd2live`, then exits; while the editor runs it deletes nothing and returns 1. The Windows uninstaller runs it when asked to delete user data |
| `--atlas <size>` | 4096 | Texture atlas size |
| `--mesh-spacing <px>` | 64 | Mesh spacing |
| `--mesh-pixels` | off | Measure mesh lengths in source pixels instead of pixels of the document scaled to a 2048 px long side |
| `--mesh-wrap <units>` | 0 | Wrap topology: close outline gaps narrower than this (mesh units) so fine protrusions share one envelope |
| `--head-strength <value>` | 1.0 | Head deformation strength |
| `--body-strength <value>` | 1.0 | Body deformation strength |
| `--mesh-only` | off | Generate meshes only |
| `--no-deformers` | off | Skip deformers |
| `--no-see-through` | off | Show neither the brows nor the eyes through the bangs (both shown by default, so outputs hold the brow copies, the bangs' eye windows and their copies compared with earlier versions) |
| `--no-see-through-eyes` | off | Do not show the eyes through the bangs (`--see-through-eyes`, the default now, is still accepted) |
| `--see-through-opacity <0..1>` | 0.5 | How much the brows and eyes show through the bangs |
| `--no-motions` | off | Skip motions |
| `--no-physics` | off | Skip physics |
| `--no-cmo3` | off | Skip CMO3 |
| `--no-moc3` | off | Skip MOC3 |
| `--no-json` | off | Skip the diagnostics JSON |
| `--upscale <1\|2\|4>` | 1 | Texture upscale factor; 1 disables it |
| `--upscale-python <path>` | `python` (Windows), `python3` elsewhere | Python with nunif dependencies |
| `--nunif-dir <path>` | empty | nunif source directory |
| `--upscale-model <path>` | empty | Weights directory |
| `--upscale-tile <64..512>` | 256 | Inference tile size |
| `--upscale-noise <-1..3>` | 1 | Denoise level; -1 disables denoising |
| `--no-upscale-neural-alpha` | off | Upscale alpha bilinearly instead |

Notes:

- Defaults come from [`Main.kt`](../../../src/main/kotlin/io/github/psd2live/Main.kt). The GUI's initial mesh spacing comes from `PipelineConfig` (40), not the CLI's 64.
- Keep at least one of CMO3, MOC3 or the diagnostics JSON.
- Neural alpha is on by default; `--upscale-neural-alpha` remains only for older commands.
- Upscaling setup is described in the [texture upscale guide](TEXTURE_UPSCALE.md).

### Headless MCP server

`./gradlew run --args="mcp [--port N] [--token T] [--store DIR] [--profile core|full]"` starts only the MCP endpoint, without a window, for an agent working alone: the workspace runs on the application-layer host, with no view model or Compose state (see [Headless host](../../zh/spec/HEADLESS_HOST.md), Chinese); it prints `endpoint`, `token`, `profile`, `store` and `capabilities` (`view_sample_motion=yes|no`, whether the build carries the Cubism Core) and serves until the process ends. Scripts call it with `python mcp_proxy.py call <operation> [JSON] --edit` or Python's `mcp_proxy.Client`, with `PSD2LIVE_MCP_ENDPOINT` and `PSD2LIVE_MCP_TOKEN` pointing at it. The workspace store defaults to `psd2live-mcp` in the user's home, apart from the editor's (no shared history or instance lock). `--token` must be 32–256 URL-safe Base64 characters; it is random when omitted.

## Exporting to other formats

The `export` command exports a `.psd2live` project (its current history state) or a PSD (generated with default settings) to any target through the neutral IR, and writes a loss report:

```bash
./gradlew run --args="targets"                                            # list the export targets
./gradlew run --args="export model.psd2live --target gif --set clip=Nod --set size=512"
./gradlew run --args="export model.psd2live --target psd-pose --set pose=ParamAngleX=20 --output out/pose"
```

- Files go to `--output` (by default `<name>-<target>` beside the input) with the loss report `<name>.<target>.report.json`; `--name` sets the base name.
- `--set key=value` repeats; each target's settings are listed in [Neutral rig IR and export targets](../spec/EXPORT_TARGETS.md).
- Exit status: 0 on success, 1 when the export fails, 2 on a usage error.

## Tests and packaging

```bash
./gradlew test                                   # all tests (CI runs them on Ubuntu and Windows)
./gradlew test --tests "io.github.psd2live.scenario.CatalogContractsTest"   # one test class
./gradlew createDistributable                    # application directory with runtime
./gradlew packageDistributionForCurrentOS        # installer for this platform
```

- Both packaging tasks include only the build host's native libraries and ship `LICENSE`, `THIRD_PARTY_NOTICES.md` and `licenses/` in the application resources.
- Official SDK resources (`src/main/resources/cubism/`) are excluded unless you pass `-Ppsd2live.includeCubism=true` or set `PSD2LIVE_INCLUDE_CUBISM=true`. Packages with the SDK must not be distributed publicly; see [CI and releases](CUBISM_CI_RELEASE.md).
- On Linux, `./native/package_linux.sh` builds a local launcher package that needs a system JDK 21 (written to `dist/linux-<timestamp>/`); see [native/README.md](../../../native/README.md).
- There is no separate lint task; code style is `kotlin.code.style=official`.
- The Rust runtime builds in `runtime/` with `cargo test` and `cargo build --release`. See [Runtime](../spec/RUNTIME.md). Without a usable cargo, `./gradlew run` and packaging on Linux download the library CI (`runtime-prebuilt.yml`) built from the same sources, matched by a hash of `runtime/src`, `Cargo.toml` and `Cargo.lock` (`./gradlew printRuntimeHash`) and checked against its sha256; if none is found or you are offline, the built-in evaluator is used. `-Ppsd2live.prebuiltRuntime=false` turns the download off. When iterating on `runtime/`, `-Ppsd2live.fastRuntime=true` drops LTO for a much quicker build; the library is slower, so do not package with it.
- The test suite keeps only fast unit and contract tests; a full `./gradlew test` should finish within one minute. Do not add tests that run the whole pipeline on the example PSDs (tml, ds), compare every step against a cold replay or render whole frames; write such checks as the development tools below and run them by hand.

## External examples and acceptance

Assets, projects and showcase exports are maintained in [psd2live-examples](https://github.com/tsunehimatoi/psd2live-examples), with CC0 1.0 as the default unless separately marked. With Python 3.11+, run `python tools/examples/fetch.py --case tml`, or use `--all` for every pinned case. Files keep their `examples/<id>/` paths; archives are cached in `build/example-assets/`.

`tools/examples/fixtures.lock.json` pins versions, URLs, sizes and archive/file SHA-256 checksums. Update this lock explicitly when adopting new assets. Download or integrity failures fail the command.

Ordinary CI uses small synthetic inputs and internal contract tests without downloading examples. Manually run the `Example acceptance` workflow to fetch pinned `tml` and `ds`, run `ExportGoldenTool` through generation, export and CMO3 import, and require export/preview reports for all four variants. Review its uploaded artifacts before a release; this does not replace visual acceptance or baseline comparisons. Internal MCP regression tests stay in this repository; external cases and discovery checks live in the examples repository.

## Development tools

`src/test/kotlin/io/github/psd2live/tools/` holds development tools for checking results by eye and measuring them. They are written as tests so they can reach the pipeline's internals, and run only with `PSD2LIVE_TOOLS=1`; a plain `./gradlew test` skips them. Outputs go to `build/tools/`.

```bash
python tools/examples/fetch.py --case ds
PSD2LIVE_TOOLS=1 PSD2LIVE_SAMPLE=ds ./gradlew test --tests "io.github.psd2live.tools.MotionSheetTool.body"
```

| Tool | What it produces | Output |
| --- | --- | --- |
| `DeformerDeletionVisualTool` | Synthetic asymmetric meshes before and after deleting their parent deformer, and after MOC3 export and readback | `deformer-deletion/comparison.png` |
| `MotionSheetTool.motions` | Every preset motion laid out over time, the idle loop, a breath with its difference image, body Z | `motion-sheet/<sample>-*.png` |
| `MotionSheetTool.body` | Body X × body Y, close-ups of the legs and the upper body, lean, proportion and leg poses, without a skeleton and on the auto skeleton | `motion-sheet/<sample>-{stance,lean,size,legposes}*.png` |
| `MotionSheetTool.tracking` | Twelve seconds of the pointer circling the canvas slowly, the head and body following with the preview's gains and rates | `motion-frames/<sample>-track/` |
| `MotionSheetTool.idle` | Twelve seconds of the idle, frame by frame | `motion-frames/<sample>-idle/` |
| `ModelProfileTool.cmo3` | A `.cmo3`'s parameters, deformer tree (grid axes and bounds), drawables, band motion profile and per-drawable motion under the body parameters, silhouettes over body X × body Y, and its physics groups | `model-profile/<name>.txt`, `.png`, `-physics.txt` |
| `RigStudyTool` | Studies finished Cubism models (`PSD2LIVE_MOC3`: a `.model3.json` or a directory of them, several models allowed): parameters with groups and use, the parts tree, the deformer tree with each object's keyform axes, mesh density, masks, glue, physics, expressions, motions and VTube Studio mappings; per parameter, how far it moves each drawable, opacity / draw order / colour changes and how non-linear its halves are; the default pose, five frames of every parameter cropped to what it moves, head angle, eye, mouth and body grids and mesh wireframes; `PSD2LIVE_STUDY_ONLY` filters by path, large models need `-Ppsd2live.testHeap=16g` | `rig-study/<folder>/profile.txt`, `effects.txt`, `*.png` |
| `FitDetectionTool` | The feature fit of the sample (`PSD2LIVE_SAMPLE`) drawn over the art: each eye's inner and outer corners, upper and lower lid edges, the drawn closed line, and the fitted lid's upper edge at each openness key (yellow to blue); prints why an eye was left to the original rig | `fit-detection/<sample>.png` |
| `RigReviewTool` | The sample (`PSD2LIVE_SAMPLE`) generated with a new project's recipe and with the original generation from before modules, compared in the review poses and clips: per area a pose sheet (one row each), per clip a GIF playing both side by side with physics (blink, slow blink, smiling blink, gaze, talk, vowels, brows, turn, nod and tilt, shake, body, idle); `PSD2LIVE_REVIEW` picks areas (eye, brow, mouth, head, hair, body, motion; all by default), `PSD2LIVE_REVIEW_SIZE` is a frame's side | `rig-review/<sample>/<area>-poses.png`, `<clip>.gif` |
| `ModelProfileTool.sample` | The band motion profile of a generated model (without a skeleton and on the auto skeleton), its body layers and auto bones | `model-profile/<sample>.txt` |
| `SkeletonAutoViewTool.view` | The auto skeleton drawn over its sample, every bone printed, for checking joints and forks; `PSD2LIVE_SAMPLES` takes several samples or PSD paths, comma separated, `PSD2LIVE_ZOOM=left,top,right,bottom` (canvas pixels) crops | `skeleton-auto/<sample>.png` |
| `SimBakeSuite` | Sets up the back hair, front hair and clothing as the model presets do (plus the back hair pinned along its top tenth), bakes each with the old and the input-by-input method, and compares the reference simulation and the export on motion the fit never saw (tracking-like pointer, head rolls, built-in motions, each input stepped, turns, drags, shaking) by visual measures | `build/tools/sim-bake/<sample>/`: `summary.md`, a JSON per setting, tip trajectory plots |
| `SimRootDofExperiment` | Whether a body answers only how its root is carried and turned: how far each input moves the root and how much it reshapes the body besides; real against rigid driving, amplitudes, and inputs moving the root alike | `build/tools/sim-root-dof/<sample>.md` |
| `SimBakeProfileTool` | Bakes the `tml` back hair on one thread and on all, times the stages between progress marks and samples every thread | standard output |
| `PendulumKernelTool` | The array pendulum kernel (exact and fast) against `PhysicsEngine`: time per frame and deviation | standard output |
| `CanvasPerfTool` | Opens a real window under Xvfb and runs idle, hover, wheel zoom, middle-button pan and a deform-mode drag of every face point on the edit canvas, once with GPU and once with software rendering; reports frame gaps, UI-thread latency, UI-thread hotspots and the edit canvas's GPU frames per phase (0 in the GPU round means the canvas did not draw on the GPU); `PSD2LIVE_CANVAS_AA` picks the canvas antialiasing, `PSD2LIVE_OVERLAY=1` turns on the performance overlay. It moves the real pointer with `Robot`, so run it on an X server of its own (`xvfb-run -a -s "-screen 0 1920x1080x24"`, or a separate Xorg on a machine with a GPU), never on a desktop in use | `canvas-perf/report.txt`, a capture per phase and `.jfr` |
| `DragonBonesFidelityTool` | Exports `tml` and `ds` (plain and on the auto skeleton) to DragonBones and plays them with `tools/dragonbones-check` (the official DragonBones 5.7 runtime core, needs node): parameter animations at every key frame are compared with the editor's evaluator (within the tolerance the export reports) and clips are measured | `dragonbones-fidelity/report.txt` |
| `RuntimeConformanceTool.generate` / `.physics` | Reference data for the Rust runtime: `generate` evaluates random rigs (warps, rotations, nesting, sparse grids, blend shapes, glue, channels, parts), the samples and the project `PSD2LIVE_RUNTIME_PROJECT` names at random poses with the editor (the skeleton samples also as the finest bake, `rig.fine.p2lrt`); `physics` writes frame-by-frame traces of random pendulum groups and the samples' physics. Compare with `p2lrt-conformance` in `runtime/` | `runtime-conformance/<case>/`, `runtime-physics/<case>/` |
| `RuntimeConformanceTool.simulation` / `.simulationRig` | Simulation references: `simulation` drives random cloth sheets (pinned along the top, random stiffness) through the editor's XPBD solver by goals and pins that sway, turn and jump, half of them with a moving capsule collider; `simulationRig` bakes the `tml` back hair (top tenth pinned) and exports it with its live scene, plus the editor's scene on the unbaked rig under head and body motion. Read by `p2lrt-conformance --sim` and `--sim-rig` | `runtime-sim/<case>/trace.bin`, `runtime-sim-rig/back-hair/` |
| `RuntimeSamplesTool` | A synthetic rig for checking players by eye: every color blend mode over a gradient, multiply and screen colors, a mask and an inverted mask, with the software rasterizer's rendering as the reference | `runtime-samples/features.p2lrt`, `features.png`, `layout.txt` |
| `WarpProbeTool` | Black-box probes of the editor's evaluator: the warp mapping inside and outside the lattice, rotation frames under warps, flips, blend shapes and sparse grids, for the runtime's independent implementation | `warp-probe/*.tsv` |
| `GeneratorCostTool` | Time of each generator (rig build with and without a skeleton, the skeleton bake uncached and cached, the physics catalog, generated motions uncached and cached, the rig IR compile, two swings generating and replaying, a simulation bake and its write-back) against content-hashing its inputs, to judge which generators a generation cache pays for; `PSD2LIVE_SAMPLE` picks the sample (it needs front and back hair) | `generator-cost/report.txt` |
| `ExportGoldenTool` | Digests of every exported file and of the preview bundle of `tml` and `ds`, plain, on the auto skeleton, with an authored motion, and with the plain cmo3 imported back as a new project (a cmo3 by its read-back lowered to moc3 and its physics group count), to compare exports byte for byte across a refactor; `PSD2LIVE_GOLDEN_LABEL` names the output, and `PSD2LIVE_GOLDEN_RECIPE=legacy` builds them as a project saved before rig modules (the legacy recipe), to check that old projects still export as they did | `export-golden/<label>.txt`, `<label>/<sample>-<variant>/` |
| `ExportGoldenComparisonTool` | Compares two existing golden reports. CAN3 XML is compared after resolving companion CMO3 random part GUID references by part ID; all other entries compare digests. Keep both reports and their CAN3 / CMO3 files. `PSD2LIVE_GOLDEN_BEFORE` / `PSD2LIVE_GOLDEN_AFTER` default to `baseline` / `optimized` | `export-golden/comparison.txt` |
| `WorkspaceLifecyclePerfTool` | Synthetic 20,000-node history and 20 workspaces with 4 canvases each; compares shared history snapshots and reused presentation save identities against eager rebuilding, measuring time and thread allocations. Opt-in tool results do not measure real rendering frame rates | `workspace-lifecycle/performance.txt` |
| `ExportPerfTool` | Times every export target on the sample PSD (`PSD2LIVE_SAMPLE`) upscaled by whole factors, nearest neighbour (`PSD2LIVE_EXPORT_SCALES`, default `1,2`; `PSD2LIVE_EXPORT_TARGETS`, default every non-video target), and records a digest of every exported file (a cmo3 by its image entries and its read-back lowered to moc3), to compare export speed and bytes across a change; raster targets render 1024 px per upscale step at 5 fps; `PSD2LIVE_GOLDEN_LABEL` names the output, and large factors need a bigger test heap such as `-Ppsd2live.testHeap=8g` | `export-perf/<label>.txt`, `<label>.digest.txt` |
| `ArtPrimitiveV2VisualTool` | On `tml`, the legs split per side into components, the left eyelash and the mouth split by polygon, each written as a version 1 and a version 2 record: the unsplit rig, v1, v2 and their difference (x4) at open/half/closed eyes and closed/open/smiling mouth poses, to check seams, misplaced parts and missing pixels | `art-primitive-v2/{legs,eye,mouth}.png` |
| `SafetyGoldenTool` | Full geometry-safety reports (without `coverage`) for 24 seeded random geometry edits on the auto-skeleton rig, to compare the checker's classification byte for byte across a change; `PSD2LIVE_GOLDEN_LABEL` names the output | `safety-golden/<label>.txt` |
| `BundleProfileTool` | Stage times of the moc3 preview bundle (IR compile, IR back to a puppet, rest meshes to canvas space, physics3/motion3, moc lowering and writing, cdi3) and of the geometry safety check; `PSD2LIVE_SAMPLER=1` also prints a stack sampler's hottest frames | standard output only |
| `CubismCoreCheckTool` | Hands the moc3 of the `tml` and `ds` preview bundles to the official Cubism Core's consistency check (`csmHasMocConsistency`); needs `PSD2LIVE_TEST_CUBISM_CORE` naming the Core library and is skipped without it. `MocDefaultColorsTest` in `:umamo` checks small fresh models the same way | standard output only |
| `TextureWorkspaceTool` | Sets a few densities and a lock on `tml` and arranges it once by the meshes' footprints, then renders the atlas page and the Texture panel (Chinese and English; single, multiple and no selection; heatmap on and off; the density slider's preview) and the edit canvas in atlas and source pixels | `texture-workspace/*.png` |
| `AtlasFramePerfTool` | Frame cost of the atlas page and Texture panel on `tml`, headless with the software painter: an `ImageComposeScene` gets pointer input for idle, hover, a corner (density) drag, wheel zoom, a tile drag and hover without wireframes, and every render is timed (median, p90, max); saves a frame mid corner drag, the open edit session, the settled page and a close zoom. The GPU renderer needs a window's Skia context; `AtlasWindowPerfTool` and `PreviewWindowTool` measure it | `atlas-frame-perf/report.txt`, `{corner-drag,session,frame,zoomed}.png` |
| `AtlasWindowPerfTool` | Opens the real editor window in the texture workspace (`PSD2LIVE_SAMPLE` may name a `.psd` or `.psd2live`; use a copy) and dispatches pointer input to it as AWT events, so the real cursor stays put: a move and a density change through the view model, a tile drag, a corner drag and six drags that do not wait for each other; reports frame gaps, UI latency, when each busy flag fell, UI thread hotspots, drag previews drawn and GPU frames per view; captures come from the window's own Skia frame, never the screen | `atlas-window-perf/report.txt`, `*.jfr`, `*-after.png` |
| `PreviewFpsTool` | Opens the real editor window and plays the preview (`PSD2LIVE_WORKSPACE` picks a workspace preset, `PSD2LIVE_BACKENDS` the runtimes); reports the frame rate and frame interval percentiles, Compose's share of the window's frame period, what keeps the UI thread busy and how often the document state changes. `PSD2LIVE_VSYNC=0` renders without waiting for the display to show the real cost, `PSD2LIVE_PANELS=0` hides the side panels, `PSD2LIVE_TRACE=1` counts compositions per second of each composable, `PSD2LIVE_JFR=1` records and lists UI thread hotspots, `PSD2LIVE_SVG=1` writes every draw of the last frame as SVG and counts them, `PSD2LIVE_INVALIDATIONS=1` lists where the requests that make the window compositor redraw the whole picture come from, `PSD2LIVE_CAPTURE=1` pauses and reads the window back from the GPU (`PSD2LIVE_COMPOSITOR=0` turns the compositor off, to compare), `PSD2LIVE_SCRUB=EDIT` (or `PREVIEW`) drags a parameter slider the way the panel does on a canvas in that mode instead of playing (`PSD2LIVE_SCRUB_PARAMETER` picks the parameter, `PSD2LIVE_SCRUB_HZ` the samples per second, default 240) and reports each sample's time on the UI thread, `PSD2LIVE_GESTURE=hover` (or `deform`) hovers over the face mesh on the edit canvas (or drags all its points in Deform mode) through the editor's own entry points, never moving the system pointer; the report also gives the compositor's frames and full redraws | `preview-fps/report.txt`, `{cubism,p2lrt}.{jfr,svg}`, `*-paused.png` |
| `PreviewWindowTool` | Opens the real editor window with Skia on OpenGL and checks that the edit canvas and both preview runtimes draw in the window's Skia context, how many frames arrive in two seconds of playback, and reads each view's texture back from the GPU (never the screen); dumps the threads and exits if it hangs for two minutes | `preview-window/report.txt`, `{edit,cubism,p2lrt}.png` |
| `TexturePerfTool` | Commit time of texture commands (pin a tile, change a density, arrange, change the budget): `runtime` goes through the application commands and times the rebuild apart, `TEXTURE_PERF_SCENARIO` picks `plain`, `skeleton` (auto skeleton) or `deleted` (auto skeleton with one layer soft-deleted), and `TEXTURE_PERF_JFR=1` also records those rounds into `texture-perf/runtime.jfr`; `desktop` goes through the view model and the desktop adapter and records Swing-thread stalls, UI captures and page PNGs. Environment variables are not Gradle task inputs: add `--rerun` when switching scenarios | `texture-perf/runtime-<scenario>.txt`, `desktop.txt` |
| `ExportDialogTool` | The File menu with its Import and Export as submenus open, every target's Export as dialog, the Live2D and PSD export dialogs and the Export complete dialog (Chinese and English, dark theme; the success dialog also light), to check menu groups, labels and layout | `export-dialog/<language>-<target or menu>.png` |
| `ModalDialogTool.renderDialogs` / `.depthSplit` | Dialogs on the shared modal frame (Chinese), to check that title rows, bodies and footers match: `renderDialogs` the settings, help, texture upscale and draw order dialogs (dark theme), `depthSplit` the depth split dialog (dark, light and large text) | `modal-dialog/<dialog>.png`, `depth-split-{dark,light,large-text}.png` |
| `CanvasLookSheetTool.meshLook` / `.toolIcons` | Sheets of what the canvas draws: `meshLook` the editable-geometry look (`MeshLook`) in each state (structure, selected, hovered, brush reach) over dark, mid, light and saturated art; `toolIcons` every canvas tool icon and the preview rail's toggles, in both themes | `canvas-look/mesh-look.png`, `tool-icons-{dark,light}.png` |
| `CommitPerfTool.profile` / `.desktop` | Wall time of one authored commit: `profile` goes through the application command boundary and splits it by phase (revision, settings decode, rebuild, geometry check); `desktop` goes through the desktop view model and adapter, committing mesh vertex edits and brush strokes in a row, and reports commit time and the longest UI-thread stall. Pair with `JAVA_TOOL_OPTIONS=-XX:StartFlightRecording=...` to sample | `commit-perf/report.txt`, `desktop.txt` |
| `CommitPerfTool.baseline` | Per-stage baseline of the commit paths on a project with an auto skeleton, two swings and a baked simulation: every stage of a full rebuild, which leaves out the authored rigs the process has stored and so generates the base rig and replays the journal (analysis, atlas packing and PNG encoding, base rig, skeleton cache hit/miss, journal replay, swing/simulation write-back, overrides, IR, moc3 bundle, `validateBundle`, revision hash, writing the runtime files), plus geometry commits, painting a small layer, replacing an image (same shape / mesh rebuild), the skeleton cache after an unrelated topology edit, and commit time and growth with 50/200 appended journal entries; history is persisted after every commit. The native preview reload (needs a GL context) is not measured | `commit-perf/baseline.json`, `baseline.md` |
| `CommitPerfTool.rigStages` | Only the setup of `baseline` and its generation commits (layer classification, layer mesh), recording the rig build's stages over each commit, then a full rebuild of the result; `PSD2LIVE_RIG_MODES` set to `staged` or `unstaged` measures one mode of the stage cache, both by default | `commit-perf/rig-stages.json`, `rig-stages.md` |
| `SkeletonCommitTool.profile` / `.equivalence` | Skeleton commits: `profile` makes `PSD2LIVE_GEOMETRY_EDITS` geometry commits (24 by default) on the sample scaled `PSD2LIVE_SCALE` times (2 by default), then creates and binds bones and moves joints (one on the limb skinning the largest mesh) through the command boundary, reporting commit time and the rig build's stages; `equivalence` compares the runtime model after skeleton, geometry and vertex group commits, undo, redo and a branch switch with a cold rebuild and replay from empty caches and without stored authored rigs | `skeleton-commit/report.txt`, `equivalence.txt` |
| `SkeletonBakeDiffTool` | Skeleton bake before/after: records where every skinned mesh lands, in canvas pixels, at each keyform grid cell and blend-shape key of the enlarged sample (`PSD2LIVE_SCALE`, 2 by default) and renders each limb at its extreme poses; `PSD2LIVE_LABEL` names the run, and with `PSD2LIVE_REFERENCE` it is compared with that run: the largest vertex difference per mesh in pixels and a render difference | `skeleton-bake/<label>.bin`, `<label>-limbs.png`, `<label>-vs-<reference>.png` |
| `OpenPerfTool.profile` | Time to open a project as the app does (`ProjectRepository.open`, then a fresh `WorkspacePreviewBuilder` builds the head): on a project with an auto skeleton, two swings and a baked simulation, saves three archives - as the app writes them now (each revision's authored rig and the head cache), with the head cache only (generation and replay without a stored rig) and with neither - and opens each three times on empty authored-rig and skeleton caches, timing unpack/seeding and the head build and checking all three build the same model | `open-perf/report.json`, `report.md` |
| `SavePerfTool.profile` | Time to save a generated project of realistic size (`PSD2LIVE_SAVE_LAYERS`, `PSD2LIVE_SAVE_SIZE`, `PSD2LIVE_SAVE_REVISIONS`): three saves of the same capture, two after reopening it, and one open | `save-perf/report.txt` |
| `ExportTargetsScenario`, `GenerateAndRigScenario`, `PaintAndReplaceScenario`, `SplitThenRegenerateScenario`, `Cmo3ImportScenario`, `MotionScenario` | Long end-to-end scenarios on the synthetic characters, checked step by step for cold replay, undo and quality like the suite's scenarios (`scenario/`): an export through every target, a first session from import to export, painting and image replacement under the skeleton, splitting then regenerating, a cmo3 imported, edited and exported back, a built-in motion edited and exported. They run the whole pipeline for too long to be in the suite; run them after changing generation, export or saving | Test results only |
| `RigSourcePerfTool.profile` | The pixel-proportional stages of a rebuild on a large document with a generation input (the sample enlarged `PSD2LIVE_SCALE` times, 3 by default): every third layer cropped by a few pixels (padded back to its pinned rectangle), every fifth at twice its density; stage times of a cold build, warm rebuilds and a rebuild after a paint, plus cold atlas page composition and preview PNG encoding | `rig-source-perf/report.txt` |
| `MeshTraceTool.compare` / `.wrap` | `compare` meshes synthetic layers (a lashed eye on a 1024² texture, thin hair strands, a canvas-resolution layer, layers of 6000 and 2048 px documents) with the canvas trace and the texture trace and renders them side by side (texture alpha grey, mesh blue, opaque texture pixels outside the mesh red), reporting vertices, time, `detail`, uncovered pixels and mesh area; `wrap` meshes lashes, hair strands and a hand with the texture trace at wrap 0-32 and reports vertices, outline loops, time and uncovered pixels | `mesh-trace/<case>.png`, `report.txt`; `mesh-wrap/<case>.png`, `report.txt` |
| `ReadmeShotsTool` | Opens the real editor window, imports `PSD2LIVE_SAMPLE` with the Full preset picked on the start page, accepts every split Split Layers by Mesh offers, waits for the simulation bakes, commits an automatic skeleton, then captures the window's own frames (not the screen) of each workspace and canvas mode, and saves the project as `project.psd2live`; `PSD2LIVE_SHOT_REUSE=1` reopens the saved project, `PSD2LIVE_SHOT_W` / `_H` set the window size. For the project page screenshots | `readme-shots/<sample>/*.png`, `project.psd2live` |
| `ReadmeShowcaseTool` | Test plates for the project page: `meshStress`, `meshParams` and `meshWrap` mesh synthetic layers (holes and thin bridges, edge rings × fill, wrap widths); `rigHead`, `rigFace` and `rigBody` show both samples' head, facial and skeleton preset poses; `rigKnee` bends the knee of `PSD2LIVE_KNEE_SAMPLE` (a project or PSD); `bodyOrbit` adds a body X / Y clip (up and down, side to side, ellipse) to each project in `PSD2LIVE_ORBIT_PROJECTS` and exports PNG frames with physics on and off (`PSD2LIVE_ORBIT` amplitudes, `PSD2LIVE_ORBIT_PERIOD` seconds a round) | `readme-plates/*.png`, `readme-orbit/<project>-{on,off}/` |

| Variable | Effect |
| --- | --- |
| `PSD2LIVE_SAMPLE` | Example name (`tml`, `ds`) or a PSD path; `tml` by default. `CommitPerfTool.desktop` also accepts a `.psd2live` project |
| `PSD2LIVE_CMO3` | Input of `ModelProfileTool.cmo3`: a `.cmo3` file or a directory of them |
| `PSD2LIVE_PROBES` | Parameters the band profile probes, `id=value,...`; the ends of body X, Y and Z by default |
| `PSD2LIVE_SHEET_PARAM` | Lay the silhouettes out along this parameter instead of body X × body Y |
| `PSD2LIVE_BONES` | Bone positions correcting the auto skeleton in `MotionSheetTool.body`, `id=headX,headY,tailX,tailY;...` in canvas pixels |
| `PSD2LIVE_BIND_LEGS` | `1` binds the leg and shoe meshes to the first thigh bone |
| `PSD2LIVE_ZOOM` | Frame of the leg close-up, `left,top,right,bottom` as shares of the canvas |
| `PSD2LIVE_VERBOSE` | `1` makes `motions` also print every curve |
| `PSD2LIVE_BAKE_CONFIGS` | Settings of `SimBakeSuite`, `modes:keys,...`; `preset` (each part's own) by default |
| `PSD2LIVE_SIM_PARTS` | Parts for `SimBakeSuite` / `SimRootDofExperiment`, comma-separated; all by default |
| `PSD2LIVE_BAKE_METHODS` | Bake methods for `SimBakeSuite`, `legacy,dof` |
| `PSD2LIVE_SIM_INPUTS` | `SimBakeSuite` keeps only these inputs |
| `PSD2LIVE_SIM_CSV` | Motions whose tip trajectory, mode values and inputs `SimBakeSuite` writes as CSV |
| `PSD2LIVE_RUNTIME_PROJECT` | A project (`.psd2live` or PSD) `RuntimeConformanceTool.generate` also writes, as the case `project` |
| `PSD2LIVE_GEOMETRY_EDITS` | Geometry commits `SkeletonCommitTool.profile` makes before the skeleton commits; 24 by default |
| `PSD2LIVE_RIG_MODES` | `staged` or `unstaged` limits the generation commits of `CommitPerfTool.baseline` / `.rigStages` to that mode; `both` by default |
| `PSD2LIVE_TEST_CUBISM_CORE` | Path of the official Cubism Core library, for `CubismCoreCheckTool` and `MocDefaultColorsTest` in `:umamo`; both skip without it |

## Code layout

Sources are split into Gradle modules whose dependencies only point downward, which the build enforces: the engine ported from Umamo, `org.umamo.*`, lives in `:umamo` (`umamo/src/main/kotlin/`; it cannot depend on product code); the neutral rig IR `:format-model`, the export framework `:format-compile` and the raster exports `:targets:raster`, the runtime rig `:targets:runtime` and the runtime bindings `:format-eval` are MIT and depend on no GPL module; `:targets:cubism` (IR converter, moc3, cmo3) and `:targets:psd` use the engine; the product layer, `io.github.psd2live.*`, lives in the root project (`src/main/kotlin/`). See [Neutral rig IR and export targets](../spec/EXPORT_TARGETS.md). The engine packages:

| Package | Responsibility |
| --- | --- |
| `org.umamo.runtime` | `PuppetModel`, keyform interpolation and evaluation |
| `org.umamo.format` | Reading and writing PSD, CMO3, MOC3 and image formats |
| `org.umamo.interop` | Conversion between `PuppetModel` and CMO3 / MOC3 |
| `org.umamo.render` | LWJGL / OpenGL preview |
| `org.umamo.edit` | Immutable editing primitives on the model |
| `io.github.psd2live.core` | Generation pipeline (`PSD2LivePipeline`, `LayerClassifier`, `AdaptiveMeshGenerator`, `RigBuilder`, `MotionGenerator`, `PhysicsGenerator`) and replayable edits |
| `io.github.psd2live.project` | The neutral workspace document (`WorkspaceDocument`), history store (`WorkspaceStore`) and `.psd2live` archives; no dependency on `application`, Compose or MCP |
| `io.github.psd2live.application` | The neutral runtime (`WorkspaceRuntime`), command registry and schemas, preview sessions and in-process background jobs, shared by the UI and MCP |
| `io.github.psd2live.history` | Branching undo / redo |
| `io.github.psd2live.agent` | Local MCP server and public tool definitions |
| `io.github.psd2live.render` | GPU rendering of the edit canvas and the p2lrt preview (`CanvasGpu`, `GlCanvasRenderer`, `P2lrtGlRenderer`, `SkiaGpu`) |
| `io.github.psd2live.ui` | Compose UI: `state` (ViewModel, shortcut registry), `views` (workspaces and panels), `components` (dialogs and controls), `tutorial`; canvas editing and painting live in the `ui` root package |
| `io.github.psd2live.i18n` | UI strings, with resources in `src/main/resources/i18n/` |

Keep generation and export logic in `core` / `project` / `application`, not in Compose code.

Start, Help and Settings are app pages (`AppPage` in `ui/state/AppPages.kt`), never part of a project: each reuses the editor's dock (`DockSession` and `DockTree` in `DockWorkspaceView`), each is a contents panel and a topic panel (the start page adds details), its panel IDs carry the page's prefix (`start.*`, `help.*`, `settings.*`), and they share the contents rows and text body in `PageChrome.kt`, its default arrangement is `pageDockLayout`, and its arrangement and closed panels are kept in the local preferences (`AppSettings.pageArrangement`). Panels get dragged, floated and redocked, so state they share lives in the ViewModel or `StartPageModel`, never in one panel's composition. The What's new text is `src/main/resources/whatsnew/<language>.md`; the four languages must list the same releases and entries (`WhatsNewTest`). The build stamps the version into `psd2live/version.properties`, which code reads through `AppVersion`.

## Core rule: rebuild and replay

`PuppetModel` is not persisted. A project stores the source artwork, layer classification and settings, plus a serializable edit record (`RigEditOverlay`). On every open or change:

1. `RigBuilder` regenerates the base rig from the source artwork;
2. the authored stage (`RigEditOverlay.replayAuthored`) starts from the rig stored by the journal's last `rig_checkpoint` record, or else from the base rig with the legacy static fields replayed first (parameter deletion / creation, warps, structure, keyforms), then replays the `authoringJournal` entries after it in recorded order;
3. the finish (`RigEditOverlay.finish`) runs the generators (swings, simulations, ...) in the order of the generator graph (`core/DocumentGenerators.kt`), merges generated overrides (`generated_override`) three ways, then replays the panel edits that name generated parameters.

A revision whose authored rig is stored (built in this process, or saved in the archive's `rig/`) builds from it, with no base rig generated and no journal replayed. See [Materialized rig (中文)](../../zh/spec/MATERIALIZED_RIG.md) and [Document layer (中文)](../../zh/spec/DOCUMENT_LAYER.md).

For new editing features this means:

- Store the change in serializable `RigEditOverlay` fields (preferably as a JSON command in the authoring journal) and save / restore it in the workspace state codec. Changes made only to `PuppetModel` are lost on rebuild.
- The UI and MCP should use the same editing commands. Low-level methods in `org.umamo.edit` are not automatically public interfaces.
- Acceptance path: domain data → history replay → project save and reopen → target Cubism version handling → export read-back → visual check. See the [runtime and export reference](../../zh/spec/RUNTIME_EXPORT_ARCHITECTURE_AND_GAPS.md) (Chinese).

New UI strings go into `Messages.properties`, `Messages_zh_CN.properties`, `Messages_ja.properties` and `Messages_ko.properties` together so the key counts stay equal (`CatalogContractsTest` checks).

## Checking exports

- Deliver every file referenced by `.model3.json`.
- Review warnings in the Log panel and the diagnostics JSON.
- Save a `.psd2live` project to keep editing; `.psd2live.json` is only a report.

Related: [Cubism native preview](CUBISM_SDK_SETUP.md) · [CI and releases](CUBISM_CI_RELEASE.md) · [`build.gradle.kts`](../../../build.gradle.kts)
