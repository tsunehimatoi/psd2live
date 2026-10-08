# Development and CLI

[Documentation](../../README.md) · [中文](../../zh/guide/DEVELOPMENT.md) · [日本語](../../ja/guide/DEVELOPMENT.md)

This page is for running from source, using the command line or contributing code. Release packages bundle their own runtime and do not need anything here.

## Requirements

- JDK 21. Gradle runs through the bundled wrapper; no separate install is needed.
- Use `.\gradlew.bat` on Windows and `./gradlew` on Linux / macOS. Examples below use `./gradlew`.
- The official Cubism SDK is not required. Source builds use the built-in renderer; see [Cubism native preview](CUBISM_SDK_SETUP.md) for the native bridge.

## Running

```bash
./gradlew run                     # no arguments: start the GUI
./gradlew run --args="--help"     # CLI help
./gradlew run --args="--input examples/tml/psd-input/tml.psd --output build/example-output"
```

On Windows, `run-gui.bat` in the repository root also starts the GUI. Without arguments the GUI starts; with arguments the CLI runs and requires `--input` (except for `--help`). The CLI writes export files directly from a PSD and does not create an editable `.psd2live` project.

## CLI options

| Option | Default | Meaning |
| --- | --- | --- |
| `--input <path>` | required | Layered input PSD |
| `--output <path>` | `psd2live-output` next to the PSD | Output directory |
| `--lang <zh\|en\|ja>` | system language | Log language |
| `--atlas <size>` | 4096 | Texture atlas size |
| `--mesh-spacing <px>` | 64 | Mesh spacing |
| `--mesh-pixels` | off | Measure mesh lengths in source pixels instead of pixels of the document scaled to a 2048 px long side |
| `--head-strength <value>` | 1.0 | Head deformation strength |
| `--body-strength <value>` | 1.0 | Body deformation strength |
| `--mesh-only` | off | Generate meshes only |
| `--no-deformers` | off | Skip deformers |
| `--no-motions` | off | Skip motions |
| `--no-physics` | off | Skip physics |
| `--no-cmo3` | off | Skip CMO3 |
| `--no-moc3` | off | Skip MOC3 |
| `--no-json` | off | Skip the diagnostics JSON |
| `--upscale <1\|2\|4>` | 1 | Texture upscale factor; 1 disables it |
| `--upscale-python <path>` | `python` | Python with nunif dependencies |
| `--nunif-dir <path>` | empty | nunif source directory |
| `--upscale-model <path>` | empty | Weights directory |
| `--upscale-tile <64..512>` | 256 | Inference tile size |
| `--upscale-noise <-1..3>` | 1 | Denoise level; -1 disables denoising |
| `--no-upscale-neural-alpha` | off | Upscale alpha bilinearly instead |

Notes:

- Defaults come from [`Main.kt`](../../../src/main/kotlin/io/github/psd2live/Main.kt). The GUI's initial mesh spacing comes from `PipelineConfig` (40), not the CLI's 64.
- Keep at least one of CMO3, MOC3 or the diagnostics JSON.
- Neural alpha is on by default; `--upscale-neural-alpha` remains only for older commands.
- Upscaling setup is described in the [texture upscale guide](../../zh/guide/TEXTURE_UPSCALE.md) (Chinese).

## Exporting to other formats

The `export` command exports a `.psd2live` project (its current history state) or a PSD (generated with default settings) to any target through the neutral IR, and writes a loss report:

```bash
./gradlew run --args="targets"                                            # list the export targets
./gradlew run --args="export model.psd2live --target gif --set clip=Nod --set size=512"
./gradlew run --args="export model.psd2live --target psd-pose --set pose=ParamAngleX=20 --output out/pose"
```

- Files go to `--output` (by default `<name>-<target>` beside the input) with the loss report `<name>.<target>.report.json`; `--name` sets the base name.
- `--set key=value` repeats; each target's settings are listed in [Neutral rig IR and export targets (中文)](../../zh/spec/EXPORT_TARGETS.md).
- Exit status: 0 on success, 1 when the export fails, 2 on a usage error.

## Tests and packaging

```bash
./gradlew test                                   # all tests (CI runs them on Ubuntu and Windows)
./gradlew test --tests "io.github.psd2live.core.SwingDeformerTest"   # one test class
./gradlew createDistributable                    # application directory with runtime
./gradlew packageDistributionForCurrentOS        # installer for this platform
```

- Both packaging tasks include only the build host's native libraries and ship `LICENSE`, `THIRD_PARTY_NOTICES.md` and `licenses/` in the application resources.
- Official SDK resources (`src/main/resources/cubism/`) are excluded unless you pass `-Ppsd2live.includeCubism=true` or set `PSD2LIVE_INCLUDE_CUBISM=true`. Packages with the SDK must not be distributed publicly; see [CI and releases](CUBISM_CI_RELEASE.md).
- On Linux, `./native/package_linux.sh` builds a local launcher package that needs a system JDK 21 (written to `dist/linux-<timestamp>/`); see [native/README.md](../../../native/README.md).
- There is no separate lint task; code style is `kotlin.code.style=official`.
- The Rust runtime builds in `runtime/` with `cargo test` and `cargo build --release`. See [Runtime (中文)](../../zh/spec/RUNTIME.md).
- The test suite keeps only fast unit and contract tests; a full `./gradlew test` should finish within one minute. Do not add tests that run the whole pipeline on the example PSDs (tml, ds), compare every step against a cold replay or render whole frames; write such checks as the development tools below and run them by hand.

## Development tools

`src/test/kotlin/io/github/psd2live/tools/` holds development tools for checking results by eye and measuring them. They are written as tests so they can reach the pipeline's internals, and run only with `PSD2LIVE_TOOLS=1`; a plain `./gradlew test` skips them. Outputs go to `build/tools/`.

```bash
PSD2LIVE_TOOLS=1 PSD2LIVE_SAMPLE=ds ./gradlew test --tests "io.github.psd2live.tools.MotionSheetTool.body"
```

| Tool | What it produces | Output |
| --- | --- | --- |
| `MotionSheetTool.motions` | Every preset motion laid out over time, the idle loop, a breath with its difference image, body Z | `motion-sheet/<sample>-*.png` |
| `MotionSheetTool.body` | Body X × body Y, close-ups of the legs and the upper body, lean, proportion and leg poses, without a skeleton and on the auto skeleton | `motion-sheet/<sample>-{stance,lean,size,legposes}*.png` |
| `MotionSheetTool.tracking` | Twelve seconds of the pointer circling the canvas slowly, the head and body following with the preview's gains and rates | `motion-frames/<sample>-track/` |
| `MotionSheetTool.idle` | Twelve seconds of the idle, frame by frame | `motion-frames/<sample>-idle/` |
| `ModelProfileTool.cmo3` | A `.cmo3`'s parameters, deformer tree (grid axes and bounds), drawables, band motion profile and per-drawable motion under the body parameters, silhouettes over body X × body Y, and its physics groups | `model-profile/<name>.txt`, `.png`, `-physics.txt` |
| `ModelProfileTool.sample` | The band motion profile of a generated model (without a skeleton and on the auto skeleton), its body layers and auto bones | `model-profile/<sample>.txt` |
| `DragonBonesFidelityTool` | Exports `tml` and `ds` (plain and on the auto skeleton) to DragonBones and plays them with `tools/dragonbones-check` (the official DragonBones 5.7 runtime core, needs node): parameter animations at every key frame are compared with the editor's evaluator (within the tolerance the export reports) and clips are measured | `dragonbones-fidelity/report.txt` |
| `RuntimeConformanceTool` | Reference data for the Rust runtime: random rigs (warps, rotations, nesting, sparse grids, blend shapes, glue, channels, parts), the samples and a local project evaluated by the editor at random poses, plus frame-by-frame traces of random pendulum groups and the samples' physics; compare with `p2lrt-conformance` in `runtime/` | `runtime-conformance/<case>/`, `runtime-physics/<case>/` |
| `WarpProbeTool` | Black-box probes of the editor's evaluator: the warp mapping inside and outside the lattice, rotation frames under warps, flips, blend shapes and sparse grids, for the runtime's independent implementation | `warp-probe/*.tsv` |
| `SwingCostTool` | Time of two swings generating on tml, of naively hashing their inputs and of the whole edit replay, to judge whether a generator is worth a generation cache | `swing-cost/report.txt` |
| `GeneratorCostTool` | Time of each generator (rig build with and without a skeleton, the skeleton bake uncached and cached, the physics catalog, generated motions uncached and cached, the rig IR compile, a simulation bake and its write-back) against content-hashing its inputs; `PSD2LIVE_SAMPLE` picks the sample | `generator-cost/report.txt` |
| `ExportGoldenTool` | Digests of every exported file of `tml` and `ds`, plain, on the auto skeleton and with an authored motion (a cmo3 by its read-back lowered to moc3), to compare exports byte for byte across a refactor; `PSD2LIVE_GOLDEN_LABEL` names the output | `export-golden/<label>.txt` |
| `ArtPrimitiveV2VisualTool` | On `tml`, the legs split per side into components, the left eyelash and the mouth split by polygon, each written as a version 1 and a version 2 record: the unsplit rig, v1, v2 and their difference (x4) at open/half/closed eyes and closed/open/smiling mouth poses, to check seams, misplaced parts and missing pixels | `art-primitive-v2/{legs,eye,mouth}.png` |
| `SafetyGoldenTool` | Full geometry-safety reports (without `coverage`) for 24 seeded random geometry edits on the auto-skeleton rig, to compare the checker's classification byte for byte across a change; `PSD2LIVE_GOLDEN_LABEL` names the output | `safety-golden/<label>.txt` |
| `BundleProfileTool` | Stage times of the moc3 preview bundle (IR compile, IR back to a puppet, rest meshes to canvas space, physics3/motion3, moc lowering and writing, cdi3) and of the geometry safety check; `PSD2LIVE_SAMPLER=1` also prints a stack sampler's hottest frames | standard output only |
| `TextureWorkspaceTool` | Sets a few densities, a lock and a pin on `tml`, then renders the atlas page and the Texture panel (Chinese and English; single, multiple and no selection; heatmap on and off; the density slider's preview) and the edit canvas in atlas and source pixels | `texture-workspace/*.png` |
| `AtlasFramePerfTool` | Frame cost of the atlas page and Texture panel on `tml`, headless: an `ImageComposeScene` gets pointer input for idle, hover, a corner (density) drag, wheel zoom, a tile drag and hover without wireframes, and every render is timed (median, p90, max), first in software, then on the GPU when an OpenGL context can be had; per mode a frame mid corner drag, the settled page and a close zoom | `atlas-frame-perf/report.txt`, `software-*.png`, `gpu-*.png` |
| `AtlasWindowPerfTool` | Opens the real editor window in the texture workspace (`PSD2LIVE_SAMPLE` may name a `.psd` or `.psd2live`; use a copy) and dispatches pointer input to it as AWT events, so the real cursor stays put: a move and a density change through the view model, a tile drag, a corner drag and six drags that do not wait for each other; reports frame gaps, UI latency, when each busy flag fell, UI thread hotspots, drag previews drawn and GPU frames per view; captures come from the window's own Skia frame, never the screen | `atlas-window-perf/report.txt`, `*.jfr`, `*-after.png` |
| `ExportDialogTool` | The File menu with its Import and Export as submenus open, every target's Export as dialog, the Live2D and PSD export dialogs and the Export complete dialog (Chinese and English, dark theme; the success dialog also light), to check menu groups, labels and layout | `export-dialog/<language>-<target or menu>.png` |
| `ModalDialogTool` | The settings, help, texture upscale, draw order and rebuild mesh dialogs on the shared modal frame (Chinese, dark theme), to check that title rows, bodies and footers match | `modal-dialog/<dialog>.png` |
| `SimBakeBenchmark` | Bakes the `tml` back hair at a few settings and compares the simulation with the export on motion the fit never saw | standard output |
| `CommitPerfTool.profile` / `.desktop` | Wall time of one authored commit: `profile` goes through the application command boundary and splits it by phase (revision, settings decode, rebuild, geometry check); `desktop` goes through the desktop view model and adapter, committing mesh vertex edits and brush strokes in a row, and reports commit time and the longest UI-thread stall. Pair with `JAVA_TOOL_OPTIONS=-XX:StartFlightRecording=...` to sample | `commit-perf/report.txt`, `desktop.txt` |
| `CommitPerfTool.baseline` | Per-stage baseline of the commit paths on a project with an auto skeleton, two swings and a baked simulation: every stage of a full rebuild (analysis, atlas packing and PNG encoding, base rig, skeleton cache hit/miss, journal replay, swing/simulation write-back, overrides, IR, moc3 bundle, `validateBundle`, revision hash, writing the runtime files), plus geometry commits, painting a small layer, replacing an image (same shape / mesh rebuild), the skeleton cache after an unrelated topology edit, and commit time and growth with 50/200 appended journal entries; history is persisted after every commit. The native preview reload (needs a GL context) is not measured | `commit-perf/baseline.json`, `baseline.md` |
| `OpenPerfTool.profile` | Time to open a project: on a project with an auto skeleton, two swings and a baked simulation, saves archives with and without the head cache and opens each three times on an empty skeleton cache, timing unpack/seeding and the head rebuild and checking both rebuild the same model | `open-perf/report.json`, `report.md` |
| `SavePerfTool.profile` | Time to save a generated project of realistic size (`PSD2LIVE_SAVE_LAYERS`, `PSD2LIVE_SAVE_SIZE`, `PSD2LIVE_SAVE_REVISIONS`): three saves of the same capture, two after reopening it, and one open | `save-perf/report.txt` |
| `Cmo3HiresTool` | Spike for writing a denser-than-canvas layer to `.cmo3`: upscales one tml eye layer 4x (a one-texel checker across its middle third) and writes a baseline, a canvas-resolution layer with a hi-res atlas only, a hi-res layer with a scaled model-image affine (layer rect at raster or canvas size) and a 4x layered image, reading each back through the readers; the files are for checking by hand in Cubism Editor | `cmo3-hires/*.cmo3`, `report.txt`, `README.txt` |

| Variable | Effect |
| --- | --- |
| `PSD2LIVE_SAMPLE` | Example name (`tml`, `ds`) or a PSD path; `tml` by default. `CommitPerfTool.desktop` also accepts a `.psd2live` project |
| `PSD2LIVE_CMO3` | Input of `ModelProfileTool.cmo3`: a `.cmo3` file or a directory of them |
| `PSD2LIVE_HIRES_TILE` | The layer `Cmo3HiresTool` upscales; the smallest eye layer by default |
| `PSD2LIVE_PROBES` | Parameters the band profile probes, `id=value,...`; the ends of body X, Y and Z by default |
| `PSD2LIVE_SHEET_PARAM` | Lay the silhouettes out along this parameter instead of body X × body Y |
| `PSD2LIVE_BONES` | Bone positions correcting the auto skeleton in `MotionSheetTool.body`, `id=headX,headY,tailX,tailY;...` in canvas pixels |
| `PSD2LIVE_BIND_LEGS` | `1` binds the leg and shoe meshes to the first thigh bone |
| `PSD2LIVE_ZOOM` | Frame of the leg close-up, `left,top,right,bottom` as shares of the canvas |
| `PSD2LIVE_VERBOSE` | `1` makes `motions` also print every curve |
| `PSD2LIVE_BAKE_CONFIGS` | Settings of `SimBakeBenchmark`, `modes:keys,...`; `2:5,2:7,1:5` by default |

## Code layout

Sources are split into Gradle modules whose dependencies only point downward, which the build enforces: the engine ported from Umamo, `org.umamo.*`, lives in `:umamo` (`umamo/src/main/kotlin/`; it cannot depend on product code); the neutral rig IR `:format-model`, the export framework `:format-compile` and the raster exports `:targets:raster`, the runtime rig `:targets:runtime` and the runtime bindings `:format-eval` are MIT and depend on no GPL module; `:targets:cubism` (IR converter, moc3, cmo3) and `:targets:psd` use the engine; the product layer, `io.github.psd2live.*`, lives in the root project (`src/main/kotlin/`). See [Neutral rig IR and export targets (中文)](../../zh/spec/EXPORT_TARGETS.md). The engine packages:

| Package | Responsibility |
| --- | --- |
| `org.umamo.runtime` | `PuppetModel`, keyform interpolation and evaluation |
| `org.umamo.format` | Reading and writing PSD, CMO3, MOC3 and image formats |
| `org.umamo.interop` | Conversion between `PuppetModel` and CMO3 / MOC3 |
| `org.umamo.render` | LWJGL / OpenGL preview |
| `org.umamo.edit` | Immutable editing primitives on the model |
| `io.github.psd2live.core` | Generation pipeline (`PSD2LivePipeline`, `LayerClassifier`, `AdaptiveMeshGenerator`, `RigBuilder`, `MotionGenerator`, `PhysicsGenerator`) and replayable edits |
| `io.github.psd2live.project` | `.psd2live` archives, sessions and workspace state serialization |
| `io.github.psd2live.history` | Branching undo / redo |
| `io.github.psd2live.agent` | Local MCP server and public tool definitions |
| `io.github.psd2live.ui` | Compose UI: `state` (ViewModel, shortcut registry), `views` (workspaces and panels), `components` (dialogs and controls), `tutorial`; canvas editing and painting live in the `ui` root package |
| `io.github.psd2live.i18n` | UI strings, with resources in `src/main/resources/i18n/` |

Keep generation and export logic in `core` / `project`, not in Compose code.

## Core rule: rebuild and replay

`PuppetModel` is not persisted. A project stores the source artwork, layer classification and settings, plus a serializable edit record (`RigEditOverlay`). On every open or change:

1. `RigBuilder` regenerates the base rig from the source artwork;
2. `RigEditOverlay.applyTo` replays edits in a fixed order: parameter deletion / creation → warps and structure → keyforms → the authoring journal in recorded order → swing generation last.

For new editing features this means:

- Store the change in serializable `RigEditOverlay` fields (preferably as a JSON command in the authoring journal) and save / restore it in the workspace state codec. Changes made only to `PuppetModel` are lost on rebuild.
- The UI and MCP should use the same editing commands. Low-level methods in `org.umamo.edit` are not automatically public interfaces.
- Acceptance path: domain data → history replay → project save and reopen → target Cubism version handling → export read-back → visual check. See the [runtime and export reference](../../zh/spec/RUNTIME_EXPORT_ARCHITECTURE_AND_GAPS.md) (Chinese).

New UI strings go into `Messages.properties`, `Messages_zh_CN.properties` and `Messages_ja.properties` together so the key counts stay equal.

## Checking exports

- Deliver every file referenced by `.model3.json`.
- Review warnings in the Log panel and the diagnostics JSON.
- Save a `.psd2live` project to keep editing; `.psd2live.json` is only a report.

Related: [Cubism native preview](CUBISM_SDK_SETUP.md) · [CI and releases](CUBISM_CI_RELEASE.md) · [`build.gradle.kts`](../../../build.gradle.kts)
