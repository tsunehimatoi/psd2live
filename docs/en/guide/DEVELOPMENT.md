<!-- lang: zh en ko ja -->
> [中文](../../zh/guide/DEVELOPMENT.md) · **English** · [한국어](../../ko/guide/DEVELOPMENT.md) · [日本語](../../ja/guide/DEVELOPMENT.md)

# Development and the command line

[Documentation](../README.md)

This page is for people who run from source, use the command line, or work on the project. A release package ships its runtime. Ordinary use does not need this page.

## Environment

- JDK 21. On Windows, JetBrains Runtime (JBR) 21 is recommended: the Windows release package uses it, and on it the main window keeps the system window frame (maximize, snap, and edge resizing are handled by the system) while the application draws the title bar. Other JDKs can still build, test, and run. On Linux, an opaque window keeps the system title bar on every JDK, and a transparent window still uses the application title bar. On Windows, other JDKs use a borderless window. Windows packaging (`createDistributable`, `packageExe`) requires the JBR 21 SDK (with jpackage). Packaging with another JDK needs `-Ppsd2live.allowNonJbr=true`. The macOS DMG (`packageDmg`) is built with the JDK that runs Gradle and does not need JBR, because the Swift shell keeps the system window frame. Gradle uses the Wrapper shipped with the repository. It does not need a separate install.
- Windows uses `.\gradlew.bat`. Linux / macOS uses `./gradlew`. The examples below are written as `./gradlew`.
- The official Cubism SDK is not a build prerequisite. A source build uses the built-in renderer by default. The native preview is in [Cubism Native preview](CUBISM_SDK_SETUP.md).

## Running

```bash
./gradlew run                     # 不带参数：启动 GUI
./gradlew run --args="--help"     # 查看 CLI 帮助
python tools/examples/fetch.py --case tml
./gradlew run --args="--input examples/tml/psd-input/tml.psd --output build/example-output"
```

On Windows, `run-gui.bat` in the repository root can be run directly. With no arguments it starts the GUI. With arguments it enters the CLI, which then requires `--input` (except for `--help`). The CLI generates export files straight from a PSD. It does not produce a `.psd2live` project that can be edited further.

## CLI arguments

| Argument | Default | Effect |
| --- | --- | --- |
| `--input <path>` | Required | The layered PSD to read |
| `--output <path>` | `psd2live-output` beside the PSD | Export directory |
| `--lang <zh\|en\|ja\|ko>` | The system language | Log language |
| `--clear-user-data` | — | Used alone: deletes the current user’s settings, the workspace store, and the runtimes and caches under `~/.psd2live`, then exits. It does not delete them while the editor is running, and returns 1. Called when a Windows uninstall chooses to delete data as well |
| `--atlas <size>` | 4096 | Texture atlas size |
| `--mesh-spacing <px>` | 64 | Mesh spacing |
| `--mesh-pixels` | Off | Measure mesh length in source pixels instead of document pixels scaled to a 2048 px long side |
| `--mesh-wrap <units>` | 0 | Wrap topology: close outline gaps narrower than this value (mesh units), and let small protrusions share one envelope |
| `--head-strength <value>` | 1.0 | Head deformation amount |
| `--body-strength <value>` | 1.0 | Body deformation amount |
| `--mesh-only` | Off | Generate meshes only |
| `--no-deformers` | Off | Do not generate deformers |
| `--no-see-through` | Off | Neither brows nor eyes show through the bangs (by default both do, and the output includes extra brow copies, eye windows in the bangs, and copies of those windows) |
| `--no-see-through-eyes` | Off | Eyes do not show through the bangs (`--see-through-eyes`, which is on by default now, is still accepted) |
| `--see-through-opacity <0..1>` | 0.5 | How far the brows and eyes show through the bangs |
| `--no-motions` | Off | Do not write motions |
| `--no-physics` | Off | Do not generate physics |
| `--no-cmo3` | Off | Do not write CMO3 |
| `--no-moc3` | Off | Do not write MOC3 |
| `--no-json` | Off | Do not write the diagnostic JSON |
| `--upscale <1\|2\|4>` | 1 | Texture upscale factor. 1 is off |
| `--upscale-python <path>` | `python` on Windows, `python3` elsewhere | A Python that has the nunif dependencies |
| `--nunif-dir <path>` | Empty | nunif source directory |
| `--upscale-model <path>` | Empty | Weight directory |
| `--upscale-tile <64..512>` | 256 | Inference tile size |
| `--upscale-noise <-1..3>` | 1 | Denoise level. -1 is no denoise |
| `--no-upscale-neural-alpha` | Off | Use bilinear upscale for alpha instead |

Notes:

- Defaults follow [`Main.kt`](../../../src/main/kotlin/io/github/psd2live/Main.kt). The GUI’s initial mesh spacing comes from `PipelineConfig` (40), which differs from the CLI’s 64.
- At least one of CMO3, MOC3, and the diagnostic JSON is kept.
- Neural alpha is on by default. `--upscale-neural-alpha` is kept only so old commands still work.
- Preparing an upscale, and examples, are in [Texture upscaling](TEXTURE_UPSCALE.md).

### Headless MCP server

`./gradlew run --args="mcp [--port N] [--token T] [--store DIR] [--profile core|full]"` opens no window. It only starts an MCP endpoint, so an agent can work on its own: the workspace is hosted by the application layer, and no ViewModel or Compose state is created. After startup it prints `endpoint`, `token`, `profile`, `store`, and `capabilities` (`view_sample_motion=yes|no`, whether the build includes Cubism Core) until the process ends. Scripts call it with `python tools/mcp_proxy.py call <operation> [JSON] --edit` or Python’s `mcp_proxy.Client`, and set `PSD2LIVE_MCP_ENDPOINT` and `PSD2LIVE_MCP_TOKEN` to point at it. The workspace store defaults to `psd2live-mcp` under the user directory, separate from the editor (history and the instance lock are not shared). `--token` must be 32–256 URL-safe Base64 characters. When it is omitted, one is generated at random.

## Exporting to other formats

The `export` command exports a `.psd2live` project (the current history state) or a PSD (generated with the default settings) to any target through the neutral IR, and writes a loss report:

```bash
./gradlew run --args="targets"                                            # 列出导出目标
./gradlew run --args="export model.psd2live --target gif --set clip=Nod --set size=512"
./gradlew run --args="export model.psd2live --target psd-pose --set pose=ParamAngleX=20 --output out/pose"
```

- Files are written to `--output` (by default `<name>-<target>` beside the input). The loss report is `<name>.<target>.report.json`. `--name` sets the base name.
- `--set key=value` can be repeated. Each target’s setting keys are in [Neutral rig IR and export targets](../spec/EXPORT_TARGETS.md).
- Exit codes: 0 success, 1 export failed, 2 bad arguments.

## Tests and packaging

```bash
./gradlew unitTest                               # 本地快速单元与契约测试（不含 scenario 与外部 worker/plugin 测试）
./gradlew integrationTest                        # 场景与外部 worker/plugin 集成测试
./gradlew test                                   # 各模块日常 JVM 回归
./gradlew test pluginBuildTest                   # 完整 JVM 回归，含插件实际编译、热替换、撤销和重开
./gradlew test --tests "io.github.psd2live.scenario.CatalogContractsTest"   # 单个测试类
./gradlew createDistributable                    # 带运行时的应用目录
./gradlew packageDistributionForCurrentOS        # 当前平台的安装包
./gradlew packageDmg                             # 仅 macOS：带 Swift 外壳的 DMG
```

The Linux application directory uses a manifest `Class-Path` JAR to keep dependency order and shorten the native launcher arguments, which avoids
[JDK-8380085](https://github.com/openjdk/jdk/commit/54c5e41cbaabcd21d7bbf8f5667b2e35cfe12dd7)
crashing before the JVM starts on a long classpath. After changing the JDK or the packaging configuration, run the check below. It uses temporary user settings, and it checks a migration path that contains spaces, quotes, and non-English characters:

```bash
python3 packaging/linux/smoke-test.py build/compose/binaries/main/app/PSD2Live
```

- `packageDmg` is available only on macOS. It uses the runtime that `createDistributable` packed in, and it replaces the launcher with the Swift shell in `packaging/macos`, so the Dock name, the document types, and the window buttons are the system’s. Menus appear in the menu bar at the top of the screen. The menu inside the window stays, because that is where every command is listed. The DMG’s architecture matches the Mac that built it (Apple silicon or Intel), and it needs macOS 13 or newer. The package is not notarized: after a download, open it in Finder with a secondary click, then drag `PSD2Live` into Applications.
- `CI` builds the native runtime and the algorithm plugins on Linux, Windows, and macOS, x64 and ARM64, runs the JVM regression, and checks the workers inside the package. Linux starts the CLI from the unpacked Deb, Windows uses the application image, and macOS also verifies the mounted DMG. `pluginBuildTest`, which starts a nested Gradle, runs once each on Linux x64, Windows x64, and macOS ARM64, and it is included in the local `check`. It verifies the native launcher’s error handling, and it starts the mounted application with the runtime inside the package. The shared `.github/actions/package-macos` also provides both architectures of DMG for the release workflow, and it does not include the Cubism SDK.
- Both packages include native libraries only for the platform that built them, and they ship `LICENSE`, `THIRD_PARTY_NOTICES.md`, and `licenses/` in the application resource directory.
- Official SDK resources (`src/main/resources/cubism/`) are not packaged by default. They are included only when `-Ppsd2live.includeCubism=true` is passed or `PSD2LIVE_INCLUDE_CUBISM=true` is set. A package that contains the SDK must not be distributed publicly. The release process is in [CI and releases](CUBISM_CI_RELEASE.md).
- On Linux, `./native/package_linux.sh` can also produce a local launcher package that needs a system JDK 21 (written to `dist/linux-<timestamp>/`). See [native/README.md](../../../native/README.md).
- The project has no separate lint task. The code style is `kotlin.code.style=official`.
- The Rust runtime in `runtime/` is built with `cargo test` and `cargo build --release`. See [Runtime](../spec/RUNTIME.md). When cargo is not available, `./gradlew run` and packaging download, on Linux, the library CI (`runtime-prebuilt.yml`) built from the same sources, matched by the hash of `runtime/src`, `Cargo.toml`, and `Cargo.lock` (`./gradlew printRuntimeHash`) and checked with sha256. If it cannot be found, or the machine is offline, it falls back to the built-in evaluator. `-Ppsd2live.prebuiltRuntime=false` turns the download off. While iterating on `runtime/`, `-Ppsd2live.fastRuntime=true` turns LTO off and shortens compilation a great deal. The library it produces is slower. Do not use it for packaging.
- The test suite keeps only the fast unit and contract tests. A full `./gradlew test` should finish within 1 minute. Do not add a test that runs the full pipeline on an example PSD (tml, ds), compares a cold replay step by step, or renders a whole frame. Write that kind of check as a development tool below, and run it by hand when it is needed.

### Algorithm plugin runtime check

Building the application image on Windows needs `mt.exe` from the Windows SDK. The packaging task merges a UTF-8 manifest into the application and the Java launcher inside the package, so the runtime DLL can be loaded from an install path whose code page differs from the system’s.

Algorithm plugins inside the application start a separate JVM through `java.home/bin/java` (`java.exe` on Windows), so a packaged runtime must keep the Java launcher.
The check below uses the real algorithm worker from the package, and verifies startup with no system JDK, temporary user settings, and a migration path that contains spaces and non-English characters:

```bash
python3 packaging/smoke-test-workers.py build/compose/binaries/main/app/PSD2Live
# macOS 使用 build/PSD2Live.app；Windows 使用对应的 PSD2Live 应用目录。
```

### Linux window regression check

A real graphics session is required. JDK 21’s AWT runs through XWayland in a Wayland session.
Moving, resizing, and maximizing an opaque window are left to the window manager, so the whole desktop’s work area is not mistaken for a single monitor’s work area.

```bash
./gradlew :test --tests '*NativeTitleBarTest' --tests '*BugListRegressionTest'
PSD2LIVE_TOOLS=1 ./gradlew :test --tests '*LinuxWindowModesTool'
```

The graphics tool checks the maximized work area, the restored size, external window state, and single-axis maximize. An ordinary test run skips it.
Check by hand: dragging the system title bar, resizing from corners and edges, the maximize button and desktop shortcuts, moving between monitors of different sizes and scales, reopening after a monitor is unplugged, toggling fullscreen with F11, and dragging a transparent window back. Windows and macOS should get a smoke test of window operations too.

Run a source build with its own settings, so it does not share a workspace with an installed copy (point `JAVA_HOME` at JDK 21 first):

```bash
./gradlew writeRunArgs
mkdir -p build/gui-check/home
"$JAVA_HOME/bin/java" \
  "-Duser.home=$PWD/build/gui-check/home" \
  "-Djava.util.prefs.userRoot=$PWD/build/gui-check/home/prefs" \
  @build/run/jvm.args
```

## External examples and acceptance

Example art, projects, and showcase output are managed in [psd2live-examples](https://github.com/tsunehimatoi/psd2live-examples). Unless a license is noted separately, the default is CC0 1.0. The Python 3.11+ download command is `python tools/examples/fetch.py --case tml`. `--all` downloads every pinned case. Files are still written to `examples/<id>/`. The download cache is `build/example-assets/`.

The main project’s `tools/examples/fixtures.lock.json` pins the art version, the download URL, the archive, and each file’s SHA-256 and size. An art upgrade is a separate edit of the lock file. It does not follow the example repository’s latest branch. A failed download, or a size or hash that does not match, is an error.

Ordinary CI uses small synthetic art and internal contract tests. It does not download the large examples. The GitHub Actions `Example acceptance` workflow is enabled by hand. It downloads the pinned `tml` and `ds`, runs `ExportGoldenTool` to check full generation, export, and the CMO3 import path, and requires export and preview reports for all four variants. The reports check the generation path. They do not replace a visual acceptance or a comparison against a historical baseline. Before a release they can be run, and the uploaded reports checked. MCP’s internal regression tests stay in the main repository. External use cases and discovery checks are in the example repository.

## Development tools

`src/test/kotlin/io/github/psd2live/tools/` holds development tools for looking and for measuring. They are written as tests so they can call the pipeline’s internal interfaces. They run only when `PSD2LIVE_TOOLS=1` is set. An ordinary `./gradlew test` skips them. Output is written to `build/tools/`. While development tools are on, the test task disables Gradle’s result cache and up-to-date checks, so every measurement actually runs.

```bash
python tools/examples/fetch.py --case ds
PSD2LIVE_TOOLS=1 PSD2LIVE_SAMPLE=ds ./gradlew test --tests "io.github.psd2live.tools.MotionSheetTool.body"
```

The first-frame tools measure different ranges. The offscreen tool includes GPU sync and readback. It does not include model generation, GL context creation, Compose composition, or window presentation. `renderer_cold` means only a newly created renderer, not a cold process start. The window tool gets the GPU-completion receipt through the real application, the viewport, and Skiko playback, and it is currently limited to P2LRT. Model generation is already finished, and the JVM and the window are warm. It does not cover the window swap, system desktop composition, or the display scan. Neither figure is the full edit latency, and neither can be concatenated directly with algorithm-generation time.

`verifyReadback`, `smoke`, and `verifyWindowPixels` are functional checks. They do not print performance percentiles. `measure` collects at least 30 valid samples for each scenario / mode, and warmup samples are left out of the statistics. A timeout or a failed check is not recorded as zero latency, and no success statistics are printed. A window tool must set `PSD2LIVE_COMPOSITOR=1` or `0` before graphics initialization, run each, and check the actual mode. Save the report before switching. Window-pixel readback and PNG encoding disturb the timing. That entry is not a performance sample, and it does not measure the window swap or the display scan. GPU / performance sampling should run alone, and not in parallel with a build or another performance task. Entry arguments and the full boundary are in the measurement boundary of the first correct frame.

| Tool | Contents | Output |
| --- | --- | --- |
| `FirstCorrectFrameTool.verifyReadback` / `.measure` | A known-pixel check of three synthetic meshes, and a measurement from the captured revision until GPU readback of the offscreen edit canvas completes, covering a newly created renderer and a warmed renderer | `first-correct-frame-check/report.json`, `first-correct-frame/report.json` |
| `WindowActivationTool.smoke` / `.measure` | In a real application window, a receipt check and measurement from P2LRT model-activation capture until the window GPU completes. Run both compositor modes | `window-activation-smoke/report.json`, `window-activation/report.json` |
| `WindowActivationTool.verifyWindowPixels` | Reads back the real Skiko window surface and checks the revision colors of four patterns, the viewport position, and the identity of the first GPU receipt. Each compositor mode runs separately. Functional acceptance only | `window-activation-pixels/report.json`, `window-activation-pixels/window-*.png` |
| `WindowActivationTool.verifyDocumentEdits` / `.measureDocumentEdits` | A real public edit request, through candidate build and CAS, to the correct frame in the window. Matches the edit, the build, the revision, and the actual pixels. Each compositor mode runs separately | `window-document-edits/report.json`, `window-document-edits-measure/report.json` |
| `DeformerDeletionVisualTool` | Silhouette comparison of a synthetic asymmetric mesh before and after deleting the parent deformer, and after a MOC3 export readback | `deformer-deletion/comparison.png` |
| `MotionSheetTool.motions` | A contact sheet of each preset motion over time, the idle loop, before and after a breath and the difference image, and body Z | `motion-sheet/<example>-*.png` |
| `MotionSheetTool.body` | Body X × body Y, close-ups of the legs and the upper body, lean, size change, and leg poses, each with no skeleton and with the automatic skeleton | `motion-sheet/<example>-{stance,lean,size,legposes}*.png` |
| `MotionSheetTool.tracking` | The pointer orbits the frame slowly for 12 seconds. The head and body follow with the preview’s gain and speed | `motion-frames/<example>-track/` |
| `MotionSheetTool.idle` | Frame-by-frame images of 12 seconds of idle | `motion-frames/<example>-idle/` |
| `ModelProfileTool.cmo3` | A `.cmo3`’s parameters, deformer tree (mesh axes and ranges), graphic meshes, a segmented motion profile, each mesh’s displacement under the body parameters, a body X × body Y silhouette, and the physics groups | `model-profile/<name>.txt`, `.png`, `-physics.txt` |
| `RigStudyTool` | Studies finished Cubism models (`PSD2LIVE_MOC3`: a `.model3.json` or its directory, which may contain several models): parameters and groups and what they are for, the part tree, the deformer tree and each object’s keyform axes, mesh density, masks, Glue, physics, expressions, motions, and the VTube Studio mapping; deformation amount per parameter, opacity / draw order / color changes, and half-range nonlinearity; the default pose, a five-frame scan of each parameter (cropped to its influence), head angles, eyes, mouth, body mesh images, and mesh wireframes. `PSD2LIVE_STUDY_ONLY` filters by path. `PSD2LIVE_STUDY_REVIEW=1` outputs only the eye open/close, widen, and gaze sequence at the review tool’s poses (`review-eye.png`). A large model needs `-Ppsd2live.testHeap=16g` | `rig-study/<directory>/profile.txt`, `effects.txt`, `*.png` |
| `FitDetectionTool` | Overlays the example’s (`PSD2LIVE_SAMPLE`) fitted feature points on the original image: each eye’s inner and outer corners, upper and lower lid edges, the drawn closed-eye line, and the fitted eyelid’s upper lid edge at each openness key (yellow to blue); the upper lip’s top edge (white) and the lower lip’s bottom edge (cyan) in an open-mouth pose; lists the mouth layers, and writes why a fit failed and fell back to the previous algorithm | `fit-detection/<example>.png` |
| `RigReviewTool` | Generates the example (`PSD2LIVE_SAMPLE`) once with the new project’s recipe and once with the previous generation from before modules were introduced, and compares them under review poses and motion clips: one pose board per part (two rows), and one side-by-side GIF with physics per clip (blink, slow blink, smile-eye blink, gaze, speech, vowels, brows, head turn, nod and tilt, head whip, body, idle). `PSD2LIVE_REVIEW` chooses the parts (eye, brow, mouth, head, hair, body, motion; all by default). `PSD2LIVE_REVIEW_SIZE` is the side length of one frame | `rig-review/<example>/<part>-poses.png`, `<clip>.gif` |
| `ModelProfileTool.sample` | A segmented motion profile, the body layers, and the automatic bones of a generated model (no skeleton and automatic skeleton) | `model-profile/<example>.txt` |
| `SkeletonAutoViewTool.view` | Overlays the automatic skeleton on the example and prints every bone, to check joint positions and branches. `PSD2LIVE_SAMPLES` is a comma-separated list of examples or PSD paths. `PSD2LIVE_ZOOM=left,top,right,bottom` (canvas pixels) crops a region | `skeleton-auto/<example>.png` |
| `SimBakeSuite` | After generation from the model presets, builds physics bodies such as back hair, front hair, and clothing (plus back hair with the top 10% pinned), bakes with the old method and the per-input method, and compares the reference simulation with the exported model on motions that did not take part in the fit (face-tracking-like follow, head tilt, built-in motions, per-input steps, head turns, drags, shakes). See [Simulation and baking](SIMULATION.md) | `build/tools/sim-bake/<sample>/`: `summary.md`, a JSON per setup, tip-trajectory plots |
| `DirectSimulationReviewTool.exportedAnimations` | Plays the same input as a full XPBD, or as a MOC3 plus the physics file read back from export. The default uses the real preset and 5 keyforms. `PSD2LIVE_DIRECT_REVIEW_TRACK=xpbd` records the full simulation at 60 Hz. On readback, point `PSD2LIVE_DIRECT_REVIEW_REFERENCE_OUT` at the reference output for a per-vertex comparison. `PSD2LIVE_DIRECT_REVIEW_FIXTURE=controlled` tests a strong waist rotation on its own, or `PSD2LIVE_DIRECT_REVIEW_KEYS=9` checks keyform density. `PSD2LIVE_DIRECT_REVIEW_MIX_ANALYSIS=1` records per-axis displacement stacking, weight limits, and third-order frame differences on the blend segment. `PSD2LIVE_DIRECT_REVIEW_MIX_ONLY=1` runs only the blend segment | 25 fps image sequences, per-frame CSV, the exported model, and the reference trajectory, under `build/tools/direct-simulation/` |
| `SimRootDofExperiment` | Checks whether a body responds only to the root’s translation and rotation: how much each input moves the root, how much extra shape it changes, and the difference between a real driver and a rigid driver, between amplitudes, and between different inputs that share the same root motion | `build/tools/sim-root-dof/<sample>.md` |
| `SimBakeProfileTool` | Bakes `tml` back hair once single-threaded and once multi-threaded, times the spans between progress marks, and samples every thread | Standard output |
| `PendulumKernelTool` | Per-frame time and deviation of the pendulum-array kernel (exact and fast modes) against `PhysicsEngine` | Standard output |
| `CanvasPerfTool` | Opens a real window under Xvfb and, on the edit canvas, runs still, hover, wheel zoom, middle-button pan, and dragging every point of the face in Deform mode, once with GPU rendering and once with software rendering. Reports frame interval, UI-thread latency, UI-thread hot spots, and the edit canvas’s GPU frame count at each stage (a GPU pass of 0 means the canvas did not go through the GPU). `PSD2LIVE_CANVAS_AA` chooses canvas antialiasing. `PSD2LIVE_OVERLAY=1` turns on the performance overlay. It moves a real pointer with `Robot`, so it must run on a dedicated X server (`xvfb-run -a -s "-screen 0 1920x1080x24"`, or a separate Xorg on a machine with a GPU). Do not run it on the desktop in daily use | `canvas-perf/report.txt`, a screenshot and a `.jfr` per stage |
| `DragonBonesFidelityTool` | Exports `tml` and `ds` (no skeleton and automatic skeleton) to DragonBones, and plays them with `tools/dragonbones-check` (the official DragonBones 5.7 runtime core, which needs node): parameter animation is compared with the editor evaluator at every keyframe (error within the tolerance in the export report), and clip error is measured | `dragonbones-fidelity/report.txt` |
| `RuntimeConformanceTool.generate` / `.physics` | Reference data for the Rust runtime. `generate` is the editor’s evaluation of random models (Warp, rotation, nesting, sparse meshes, blend shapes, Glue, channels, parts), samples, and the project named by `PSD2LIVE_RUNTIME_PROJECT`, at random poses (skeleton samples also have a finest-sampled `rig.fine.p2lrt`). `physics` is the frame-by-frame trajectory of random pendulum groups and sample physics. Compare with `p2lrt-conformance` in `runtime/` | `runtime-conformance/<case>/`, `runtime-physics/<case>/` |
| `RuntimeConformanceTool.simulation` / `.simulationRig` | Reference trajectories for simulation. `simulation` is the frame-by-frame result of random cloth patches (top edge pinned, random stiffness) solved by the editor’s XPBD under swaying, turning, and bouncing targets and pin drivers. Half of them have a moving capsule collider. `simulationRig` is the model and live scene exported after baking `tml` back hair (the top tenth pinned), and the editor scene on the unbaked rig as the head and body move. They are read by `p2lrt-conformance --sim` and `--sim-rig` | `runtime-sim/<case>/trace.bin`, `runtime-sim-rig/back-hair/` |
| `RuntimeSamplesTool` | Synthetic models for looking at the player: every color blend mode on a gradient, multiply and screen color, masks and inverted masks, plus a reference image rendered by the software rasterizer | `runtime-samples/features.p2lrt`, `features.png`, `layout.txt` |
| `WarpProbeTool` | A black-box probe of the editor evaluator: Warp mapping (inside and outside the grid), a rotation frame under a Warp, flips, blend shapes, and sparse meshes, for an independent runtime implementation to compare against | `warp-probe/*.tsv` |
| `GeneratorCostTool` | Time for each generator (rig generation with and without a skeleton, the skeleton bake cache before and after, the physics-group catalog, generated-motion cache before and after, rig IR compile, generation and replay of two swings, simulation bake and write-back), and the time to content-hash their inputs, used to judge whether a generator is worth putting on the generation cache. `PSD2LIVE_SAMPLE` names the sample (it needs front hair and back hair) | `generator-cost/report.txt` |
| `ExportGoldenTool` | All export files and the preview-package summary for the no-skeleton, automatic-skeleton, and custom-motion variants of `tml` and `ds`, and for the no-skeleton cmo3 imported as a new project (the cmo3 uses the summary after readback degraded to moc3, and the physics-group count), for a byte-for-byte comparison of exports before and after a refactor. `PSD2LIVE_GOLDEN_LABEL` names the output. `PSD2LIVE_GOLDEN_RECIPE=legacy` generates from the project as it was before modules (the old recipe), to confirm an old project’s export is unchanged | `export-golden/<name>.txt`, `<name>/<example>-<variant>/` |
| `LayoutGoldenTool` | The same summary as `ExportGoldenTool`, but it generates the PSD from the synthetic characters used in tests (a full-body character and a mouth), so no example download is needed: the pipeline exports three variants, and a moc3 / cmo3 export goes through the export session after the PSD is imported. Used for a byte-for-byte comparison of exports before and after a change (a CAN3 contains a random GUID, so it differs every time). `PSD2LIVE_GOLDEN_LABEL` names the output | `layout-golden/<name>.txt` |
| `LayoutPanelsTool` | Renders the material panel, the layer-table tag cells, and the generate bar with no window: import a synthetic PSD, place one material so it is waiting to generate, double-click to open the folder, select an entry, drag an entry onto a canvas drop point (the drop point is registered by the test, and the check is centered on it), hover the generate button, and drag and drop again after opening and closing the tag menu | `layout-panels/*.png` |
| `LayoutWindowTool` | Opens a real editor window (OpenGL), imports a synthetic PSD, switches to the Layout workspace, uses AWT mouse events to drag a material folder from the material panel onto the canvas and checks the drop point, clicks the generate-bar button and checks that generation finished. Screenshots come from the window’s own Skia frame | `layout-window/report.txt`, `{layout,dragging,placed,generated}.png` |
| `ExportGoldenComparisonTool` | Compares two golden reports that have already been generated. A CAN3 is compared as full XML after random GUID references are resolved through the matching CMO3 part IDs. Everything else compares summaries. Keep both reports and the matching CAN3 / CMO3 files. `PSD2LIVE_GOLDEN_BEFORE` / `PSD2LIVE_GOLDEN_AFTER` default to `baseline` / `optimized` | `export-golden/comparison.txt` |
| `WorkspaceLifecyclePerfTool` | A synthetic history of 20,000 nodes, 20 workspaces of 4 canvases each. Compares the time and thread allocations of a shared history snapshot, identity reuse of a display-only save, and an immediate rebuild. Enabled only as a development tool. It does not represent a real render frame rate | `workspace-lifecycle/performance.txt` |
| `ExportPerfTool` | Scales an example PSD (`PSD2LIVE_SAMPLE`) by integer nearest-neighbour factors (`PSD2LIVE_EXPORT_SCALES`, default `1,2`), then times each export target (`PSD2LIVE_EXPORT_TARGETS`, default every target except video), and records a summary of each export file (a cmo3 uses the image entries inside it and the summary after readback degraded to moc3), to compare time and bytes before and after an export speedup. Each raster scale writes 1024 pixels at 5 fps. `PSD2LIVE_GOLDEN_LABEL` names the output. A large scale needs a bigger test heap, such as `-Ppsd2live.testHeap=8g` | `export-perf/<name>.txt`, `<name>.digest.txt` |
| `ArtPrimitiveV2VisualTool` | On `tml`, writes the legs split by side-connected components, the left eyelashes split as a polygon, and the mouth split as a polygon as version 1 and version 2 records, and renders the unsplit, v1, v2, and v1/v2 difference (×4) under poses such as eyes open / half closed / closed and mouth closed / open / open smile, to check seams, misalignment, and missing pixels | `art-primitive-v2/{legs,eye,mouth}.png` |
| `SafetyGoldenTool` | The full geometry safety report (without `coverage`) of 24 seeded random geometry edits on an automatic-skeleton rig, for a byte-for-byte comparison of the checker’s classification before and after a change. `PSD2LIVE_GOLDEN_LABEL` names the output | `safety-golden/<name>.txt` |
| `BundleProfileTool` | Itemized time for a moc3 preview package (IR compile, IR round-trip, rest mesh moved into canvas space, physics3/motion3, moc downgrade and write, cdi3) and the time for the geometry safety check. `PSD2LIVE_SAMPLER=1` also prints stack-sample hot spots | Standard output only |
| `CubismParityTool` | Renders the example’s (`PSD2LIVE_SAMPLE`) preview package with the Cubism runtime shipped in the application (`live2d_renderer`, offscreen), side by side with the editor’s own render: full body, face, and mouth close-ups, covering rest, head turn, mouth open, mouth shapes, eyes closed, and similar poses. `PSD2LIVE_PARITY_RECIPE=legacy` uses the generation from before modules were introduced. Windows only | `cubism-parity/<example>[-legacy]-<view>.png` |
| `CubismCoreCheckTool` | Hands the moc3 inside the `tml` and `ds` preview packages to the official Cubism Core consistency check (`csmHasMocConsistency`). Set `PSD2LIVE_TEST_CUBISM_CORE` to the Core dynamic library, or the tool is skipped. The same check for a small newly built model is in `MocDefaultColorsTest` of `:umamo` | Standard output only |
| `TextureWorkspaceTool` | After setting several densities and locks on `tml` and arranging once by mesh shape, renders the atlas page and the texture panel (Chinese and English, single selection / multi selection / none, heatmap on and off, density-slider preview), and compares the edit canvas’s atlas pixels with the original pixels | `texture-workspace/*.png` |
| `AtlasFramePerfTool` | With no window and software rendering, measures frame time of the `tml` atlas page and the texture panel: sends idle, hover, corner drag (density), wheel zoom, tile drag, and hover after the wireframe is turned off into `ImageComposeScene`, records each render’s time (median, p90, max), and saves four frames: mid corner-drag, the edit session, the still page, and a zoomed close-up. GPU rendering needs the window’s Skia context, and is measured by `AtlasWindowPerfTool` and `PreviewWindowTool` | `atlas-frame-perf/report.txt`, `{corner-drag,session,frame,zoomed}.png` |
| `AtlasWindowPerfTool` | Opens a real editor window into the texture workspace (`PSD2LIVE_SAMPLE` can name a `.psd` or a `.psd2live`; use a copy), and dispatches pointer input to the window with AWT events (it does not move the real cursor): submits a move and a density through the view model, drags a tile, drags a corner, and six continuous drags that do not wait. Records frame interval, UI-thread latency, when each busy flag drops, UI-thread hot spots, how many times the drag preview draws, and each view’s GPU frame count. Screenshots come from the window’s own Skia frame, not a screen capture | `atlas-window-perf/report.txt`, `*.jfr`, `*-after.png` |
| `PreviewFpsTool` | Opens a real editor window and plays the preview (`PSD2LIVE_WORKSPACE` chooses the workspace preset, `PSD2LIVE_BACKENDS` chooses the backend). Reports frame rate and frame-interval percentiles, Compose time inside the window frame period, the time of each kind of UI-thread event, and how often document state changes. `PSD2LIVE_VSYNC=0` does not wait for the display refresh, so the real cost is measured. `PSD2LIVE_PANELS=0` hides the side bars. `PSD2LIVE_TRACE=1` counts how many times each composable recomposes per second. `PSD2LIVE_JFR=1` records and lists UI-thread hot spots. `PSD2LIVE_SVG=1` exports every draw of the last frame as SVG and counts them. `PSD2LIVE_INVALIDATIONS=1` lists the sources of requests that make the window compositor redraw the whole frame. `PSD2LIVE_CAPTURE=1` reads the window image back from the GPU after a pause (`PSD2LIVE_COMPOSITOR=0` turns the compositor off, for comparison). `PSD2LIVE_SCRUB=EDIT` (or `PREVIEW`) instead drags a parameter slider the panel’s way on that mode’s canvas (`PSD2LIVE_SCRUB_PARAMETER` chooses the parameter, `PSD2LIVE_SCRUB_HZ` sets samples per second, 240 by default), and reports each sample’s time on the UI thread. `PSD2LIVE_GESTURE=hover` (or `deform`) hovers the face mesh on the edit canvas through the editor’s own entry (or drags every point in Deform mode), and does not move the system pointer. It also reports the compositor’s frame count and full-redraw count | `preview-fps/report.txt`, `{cubism,p2lrt}.{jfr,svg}`, `*-paused.png` |
| `PreviewWindowTool` | Opens a real editor window (Skia uses OpenGL), checks that the edit canvas and both preview backends produce frames in the window’s Skia context, the frame count received over two seconds of playback, and reads each view’s texture back from the GPU (no screen capture). If it stalls for two minutes it dumps threads and exits | `preview-window/report.txt`, `{edit,cubism,p2lrt}.png` |
| `TexturePerfTool` | Commit time of texture commands (pin a tile, change density, auto-arrange, change the budget). `runtime` goes through application commands and times the rebuild on its own. `TEXTURE_PERF_SCENARIO` is `plain`, `skeleton` (automatic skeleton), or `deleted` (automatic skeleton with one layer soft-deleted). `TEXTURE_PERF_JFR=1` also records those rounds as `texture-perf/runtime.jfr`. `desktop` goes through the view model and the desktop adapter, and records Swing-thread stalls, UI captures, and page PNGs. Environment variables are not Gradle task inputs. Add `--rerun` when the scenario changes | `texture-perf/runtime-<scenario>.txt`, `desktop.txt` |
| `ExportDialogTool` | The File menu with the Import and Export as submenus expanded, the Export as dialog of each target, the Live2D and PSD export dialogs, and the Export succeeded window (Chinese and English; the success window also has a light theme), to check menu grouping, labels, and control layout | `export-dialog/<language>-<target or menu>.png` |
| `ModalDialogTool.renderDialogs` / `.depthSplit` | Dialogs on the shared dialog frame (Chinese), to check that the title bar, the body, and the bottom action bar agree. `renderDialogs` is the settings, help, texture upscaling, and draw-order dialogs (dark theme). `depthSplit` is the depth-split dialog (dark, light, and large type) | `modal-dialog/<dialog>.png`, `depth-split-{dark,light,large-text}.png` |
| `CanvasLookSheetTool.meshLook` / `.toolIcons` | Contact sheets of canvas drawing styles. `meshLook` is editable geometry (`MeshLook`) in each state on dark, mid, light, and saturated base images (structure, selected, hover, brush influence). `toolIcons` is every canvas tool icon and the preview sidebar toggles, in dark and light themes | `canvas-look/mesh-look.png`, `tool-icons-{dark,light}.png` |
| `CliPresetMigrationTool.compareRawPreset` | Compares the historical binding and the explicit preset stack of the same PSD. `PSD2LIVE_SAMPLE` chooses the sample. `PSD2LIVE_CLI_PHYSICS=0` or `PSD2LIVE_CLI_MOTIONS=0` turns the matching output off. `PSD2LIVE_CLI_VERIFY=1` exports through the real CLI entry and checks the full IR and the readback. Setting `PSD2LIVE_CLI_TARGET_LOADER=1` as well checks the default PSD load path of the `export` subcommand | `cli-preset-migration/report.txt`, `report-target-loader.txt` |
| `CliPresetMigrationTool.compareMeshOnly`, `compareWithoutDeformers` | Checks mesh-only and no-deformer mode separately. Records the independent difference of those options with the same sample and the strict verify switch | `cli-preset-migration/report-mesh-only.txt`, `report-no-deformers.txt` |
| `ModifierPerfTool.profile` | A full application-layer PSD import, including the recommended preset, the simulation bake, the final preview package, and the initial history. Records the first and the second import in the same JVM separately (a new project each time). Records the full time, the synchronous bake time, the time with the bake subtracted, and the eye-adjustment and rebake counts. `PSD2LIVE_SAMPLE` chooses the input. `PSD2LIVE_IMPORT_MODIFIER_VERSIONS=type@version` locks the bundled binding version before preset evaluation, baking, and history initialization. `PSD2LIVE_VERIFY=1` checks the full IR rebuild result cached at the close stage after adjustment. `PSD2LIVE_VERIFY_INDEPENDENT=1` checks that import, adjustment, and cold replay did not request an old template or build an old binding. `PSD2LIVE_BASELINE_MODIFIER=type@version` compares the full IR of the adjusted stack with the result of locking a named built-in version. Several versions are comma-separated, to compare a combination. `PSD2LIVE_JFR=1` records samples. Window drawing and native preview loading are not included | `modifier-perf/report.txt`, `profile.jfr` |
| `CommitPerfTool.profile` / `.desktop` | Time for one authored commit. `profile` goes through the application-layer command boundary and splits it by stage (revision number, config decode, rebuild, geometry check). `desktop` goes through the desktop view model and adapter, commits a mesh-vertex edit and a brush stroke in a row, and reports commit time and the longest UI-thread stall. Sample with `JAVA_TOOL_OPTIONS=-XX:StartFlightRecording=...` if wanted | `commit-perf/report.txt`, `desktop.txt` |
| `CommitPerfTool.baseline` | A stage-by-stage baseline of the commit path. On a project with an automatic skeleton, two swings, and one baked simulation, measures each stage of a full rebuild (it does not read the authored rig already stored in the process; it generates the base rig and replays the log): analysis, atlas packing and PNG encoding, the base rig, skeleton cache hit / miss, log replay, swing / simulation write-back, overrides, IR, moc3 packing, `validateBundle`, revision-number hash, and runtime file write. It also measures geometry commits, painting a small layer, replacing an image (same shape / rebuild the mesh), a skeleton cache hit after an unrelated topology edit, and commit time and growth when the log is appended to 50 / 200 entries. Each commit is written to history storage afterward. Native preview reload is not measured (it needs a GL context) | `commit-perf/baseline.json`, `baseline.md` |
| `CommitPerfTool.rigStages` | Does only the preparation and generation-class commits of `baseline` (layer classification, layer meshes), records each stage of the rig build inside every commit, then rebuilds the full result. When `PSD2LIVE_RIG_MODES` is `staged` or `unstaged`, only one mode of the stage cache is measured. Both are measured by default | `commit-perf/rig-stages.json`, `rig-stages.md` |
| `SkeletonCommitTool.profile` / `.equivalence` | Skeleton commits. `profile` first does `PSD2LIVE_GEOMETRY_EDITS` geometry commits (24 by default) on a sample scaled by `PSD2LIVE_SCALE` (2 by default), then creates bones, binds them, and moves joints through the command boundary (including a limb joint of the largest skinned mesh), and reports commit time and each stage of the rig build. `equivalence` compares, step by step, the runtime model with a cold rebuild and replay that clears the caches and does not use the stored authored rig, after skeleton, geometry, and vertex-group commits and after undo / redo / switching branches | `skeleton-commit/report.txt`, `equivalence.txt` |
| `SkeletonBakeDiffTool` | A before-and-after of the skeleton bake: records canvas coordinates of each skinned mesh at every keyform grid point and blend-shape key on a scaled sample (`PSD2LIVE_SCALE`, 2 by default), and renders each limb’s extreme poses. `PSD2LIVE_LABEL` names this result. When `PSD2LIVE_REFERENCE` is set, it is compared with that result, and each mesh’s maximum vertex difference (pixels) and render difference are reported | `skeleton-bake/<label>.bin`, `<label>-limbs.png`, `<label>-vs-<reference>.png` |
| `OpenPerfTool.profile` | Time to open a project the application’s way (after `ProjectRepository.open`, a new `WorkspacePreviewBuilder` builds the head): on a project with an automatic skeleton, two swings, and one baked simulation, saves three archives — the application’s current writing (authored rig and head cache per revision), head cache only (generate and replay when no rig is stored), and neither — opens each 3 times with an empty authored rig and skeleton cache, measures unpack / seed and head build, and checks that the three build the same model | `open-perf/report.json`, `report.md` |
| `SavePerfTool.profile` | Time to save a generated project at a realistic scale (`PSD2LIVE_SAVE_LAYERS`, `PSD2LIVE_SAVE_SIZE`, `PSD2LIVE_SAVE_REVISIONS`): the same capture is saved 3 times, saved 2 times after a reopen, then opened once | `save-perf/report.txt` |
| `ExportTargetsScenario`, `GenerateAndRigScenario`, `PaintAndReplaceScenario`, `SplitThenRegenerateScenario`, `Cmo3ImportScenario`, `MotionScenario` | Long end-to-end scenarios on a synthetic character, checking cold replay, undo, and quality step by step the way the scenarios in the test suite (`scenario/`) do: export through every export target, the first session from import to export, painting and image replacement under a skeleton, split and then generate again, import a cmo3, edit, and export back to cmo3, and edit and export built-in motions. The whole pipeline takes too long to be in the test suite. Run them after a change to generation, export, or save | Test results only |
| `RigSourcePerfTool.profile` | Stages counted in pixels during a rebuild of a large image (an example scaled by `PSD2LIVE_SCALE`, 3 by default) that carries generated inputs: every 3rd layer is cropped a few pixels smaller (it has to be padded back to a fixed rectangle), and every 5th layer is 2× density. Stage times for a cold build, a hot rebuild, and a rebuild after drawing, plus cold composite atlas pages and preview PNG encoding | `rig-source-perf/report.txt` |
| `MeshTraceTool.compare` / `.wrap` | `compare` generates meshes for synthetic layers (an eye with lashes on a 1024² texture, a thin hair strand, a canvas-resolution layer, and layers in 6000 and 2048 documents) by tracing the outline from the canvas and from the texture, and renders them side by side (grey is texture alpha, blue is the mesh, red is opaque texture pixels outside the mesh). It reports vertex count, time, `detail`, pixels outside the mesh, and mesh area. `wrap` traces the outline from the texture and generates meshes for lashes, a thin hair strand, and a hand at wrap 0–32, and reports vertex count, outline loop count, time, and pixels outside the mesh | `mesh-trace/<case>.png`, `report.txt`; `mesh-wrap/<case>.png`, `report.txt` |
| `StructureMeshTool` | Redistributes points by texture structure: runs the plan of `layer_mesh_structure` on the clothing and hair layers of an example PSD (`PSD2LIVE_STRUCTURE_PSD`, tml by default), or on the layers listed by `PSD2LIVE_STRUCTURE_LAYERS`, draws the old mesh and the new mesh side by side with the recognized lines (creases orange, shadows blue, patterns purple, unused lines faded), and reports vertex count, lines adopted, crease-band count, and time. `PSD2LIVE_STRUCTURE_SETTINGS` passes a settings JSON | `structure-mesh/<mesh>.png`, `report.txt` |
| `ReadmeShotsTool` | Opens a real editor window, imports `PSD2LIVE_SAMPLE` after choosing the Full preset on the start page, checks and confirms the split with Check separate meshes of the whole model…, waits for the simulation bake to finish, commits the automatic skeleton, then captures a window frame of each workspace and each canvas mode (no screen capture), and saves the project as `project.psd2live`. `PSD2LIVE_SHOT_REUSE=1` reopens the saved project. `PSD2LIVE_SHOT_W` / `_H` set the window size. Used for the home-page screenshots | `readme-shots/<example>/*.png`, `project.psd2live` |
| `ReadmeShowcaseTool` | Home-page test images. `meshStress`, `meshParams`, and `meshWrap` are meshes of synthetic layers (holes and thin bridges, edge-layer count × fill algorithm, envelope width). `rigHead`, `rigFace`, and `rigBody` are preset poses of the head, the features, and the skeleton of two examples. `rigKnee` is the knee bend of `PSD2LIVE_KNEE_SAMPLE` (a project or a PSD). `bodyOrbit` temporarily adds a motion of body X / Y up and down, left and right, and in an ellipse to each project in `PSD2LIVE_ORBIT_PROJECTS`, and exports PNG frames with physics on and off (`PSD2LIVE_ORBIT` sets the amplitude, `PSD2LIVE_ORBIT_PERIOD` sets the seconds per cycle) | `readme-plates/*.png`, `readme-orbit/<project>-{on,off}/` |

The `modifier legacy template` row counts how many times a compatibility template is built. The `modifier template consumer` row records the actual caller of that same batch of builds, including requests for a previous modifier’s template. When totaling time, count only one of the two.

| Environment variable | Effect |
| --- | --- |
| `PSD2LIVE_SAMPLE` | An example name (`tml`, `ds`) or a PSD path. Default `tml`. `CommitPerfTool.desktop` also accepts a `.psd2live` project |
| `PSD2LIVE_CMO3` | Input of `ModelProfileTool.cmo3`: a `.cmo3` file or the directory it is in |
| `PSD2LIVE_PROBES` | Parameters for the motion-profile probe, `id=value,...`. Default is the ends of body X, Y, and Z |
| `PSD2LIVE_SHEET_PARAM` | The silhouette is spread along this parameter instead of body X × body Y |
| `PSD2LIVE_BONES` | Bone positions that correct the automatic skeleton in `MotionSheetTool.body`, `id=headX,headY,tailX,tailY;...` (canvas pixels) |
| `PSD2LIVE_BIND_LEGS` | When set to `1`, binds the leg and shoe meshes to the first thigh bone |
| `PSD2LIVE_ZOOM` | The range of the leg close-up, `left,top,right,bottom`, as fractions of the canvas |
| `PSD2LIVE_VERBOSE` | When set to `1`, `motions` also prints every curve |
| `PSD2LIVE_BAKE_CONFIGS` | Settings for `SimBakeSuite`, `modes:keys,...`. Default `preset` (each part’s own settings) |
| `PSD2LIVE_SIM_PARTS` | Parts for `SimBakeSuite` / `SimRootDofExperiment`, comma-separated. Default is all of them |
| `PSD2LIVE_BAKE_METHODS` | Bake methods for `SimBakeSuite`, `legacy,dof` |
| `PSD2LIVE_SIM_INPUTS` | `SimBakeSuite` keeps only these inputs |
| `PSD2LIVE_SIM_CSV` | `SimBakeSuite` writes the tip trajectories, mode values, and inputs of these motions as CSV |
| `PSD2LIVE_RUNTIME_PROJECT` | An extra project `RuntimeConformanceTool.generate` writes (`.psd2live` or PSD). The case name is `project` |
| `PSD2LIVE_GEOMETRY_EDITS` | How many geometry commits `SkeletonCommitTool.profile` does before the skeleton commit. Default 24 |
| `PSD2LIVE_RIG_MODES` | Generation-class commits of `CommitPerfTool.baseline` / `.rigStages` measure only `staged` or `unstaged`. Default `both` |
| `PSD2LIVE_TEST_CUBISM_CORE` | Path of the official Cubism Core dynamic library, for `CubismCoreCheckTool` and `MocDefaultColorsTest` of `:umamo`. When it is unset, both skip |

### From PSD import to a fully generated modifier stack

The desktop start page captures the chosen file through `WorkspaceHost.importPsd`. `WorkspaceSourceImporter` decodes the PSD, links layers and materials, generates an editable mesh preview of an empty modifier stack, pins the initial atlas, and installs the project. After splitting and naming finish, `ImportPresetContinuation` submits `modifier_initialize` once, on the same project. Choosing an empty stack stops at the import. MCP’s `asset_import_psd` can also name a preset in one import. That path finishes the stages in the table below before the project is installed.

| Stage | Where it runs | What downstream uses |
| --- | --- | --- |
| Prepare the base inputs | `WorkspacePresetInitialization` → `RigModifierPipeline` → `buildOrdinaryPreview` | Analyzes the source image, builds the atlas and ordinary meshes, replays existing edits, and compiles the modifier input IR. |
| Plan and bind | `WorkspaceModifierPlan` → `RigModifierEvaluator` | Picks targets from the actual meshes and builds instances by dependency. Each instance reads the previous instance’s output. The binding result decides the available parameters and the physics outputs that are already driven. |
| Physics, motions, and simulation | `WorkspacePresetInitialization` → `WorkspaceModifierSimulation.prepare` | Adds physics and motions from the parameters that have been generated. Each simulation reads the stack before itself, tracks targets, collision meshes, Glue-connected meshes, and ancestor deformers, and writes the bake into the document. |
| Final evaluation and commit | A direct import is installed by `WorkspaceSourceImporter`. A deferred initialization is rebuilt as a candidate by `WorkspacePreviewBuilder`, then committed through `WorkspaceRuntime` | The candidate reuses an evaluation prefix whose inputs match, and generates the final preview package. The whole modifier stack of a deferred initialization appends only one history node. |

When the CLI exports straight from a raw PSD, `buildSourceExport` chooses the generation branch. Full generation with deformers enabled goes through `CliPresetBindings.build`, shares the preset planning and ordered evaluation above, then arranges the export model by the CLI configuration. Mesh-only and deformers off each have their own branch.

Measure a direct import of the recommended preset with `ModifierPerfTool.profile`. Measure the command path that runs the full preset after an empty-stack import with `ModifierPerfTool.deferredImport`. `PSD2LIVE_IMPORT_ROUNDS` controls how many new projects are created in the same JVM. The default is 2. The report lists time and GC timing per round, and the time of each stage such as simulation calibration, training, and dynamic fitting. A sub-stage’s time is already included in the stage above it. The deferred-initialization baseline also writes the SHA-256 of the preview-package files and the simulation bake data to `build/tools/modifier-perf/deferred.txt`. With `PSD2LIVE_VERIFY=1`, it saves and reopens the last generated project, checks the full IR, the modifier report, and the pictures of the rest pose and a head-turn with the mouth open, and reads the MOC3 back. Timing covers only application-layer commands. Verification, window drawing, and the time spent splitting layers by hand are all outside the timing.

## Code layout

```text
psd2live/
├─ src/                               # 产品层（根 Gradle 项目）
│  ├─ main/kotlin/io/github/psd2live/
│  │  ├─ core/                        # 生成流水线、质量检查与可重放编辑
│  │  ├─ document/                    # 源图、图层变换与纹理值类型
│  │  ├─ project/                     # WorkspaceDocument、WorkspaceStore、.psd2live 归档
│  │  ├─ application/                 # WorkspaceRuntime、宿主、命令注册与 schema
│  │  ├─ history/                     # 分支式撤销 / 重做
│  │  ├─ agent/                       # 本地 MCP 服务与工具定义
│  │  ├─ render/                      # 编辑画布与 p2lrt GPU 渲染
│  │  ├─ ui/                          # Compose Desktop 界面、ViewModel、主题与交互教程
│  │  ├─ i18n/                        # 多语言加载
│  │  └─ util/                        # 通用工具
│  ├─ main/resources/                 # i18n 文案、whatsnew、manual、皮肤预设等资源
│  └─ test/kotlin/io/github/psd2live/ # 回归测试与开发工具（tools/）
├─ umamo/                             # 通用引擎（GPL）：runtime、format、interop、render、edit
├─ umamo-ir-bridge/                   # GPL 转换桥（PuppetIr）
├─ format-model/                      # 中立绑定 IR（MIT）
├─ format-compile/                    # 导出编译框架与损失报告（MIT）
├─ format-eval/                       # 运行时求值绑定（MIT）
├─ algorithm-api/                     # 独立 JVM 算法契约与清单
├─ algorithm-worker/                  # 外部算法进程宿主机
├─ algorithms/                        # 内置算法模块（binding / motion / physics / simulation / swing）
├─ targets/                           # 导出目标（cubism / raster / psd / spine / runtime / web / gltf / unity / dragonbones）
├─ plugin-sdk/                        # 插件 SDK（Python / Rust）与打包脚本
├─ plugins/                           # 内置 / 示例原生插件（native-core）
├─ runtime/                           # Rust 运行时（p2lrt）、Web / Godot 绑定与一致性测试
├─ native/                            # 可选 Cubism Native 预览桥接（C++ / CMake）
├─ packaging/                         # Windows / macOS 安装包与启动器脚本
├─ tools/                             # mcp_proxy.py、示例拉取/校验与目标检查脚本
├─ examples/                          # 示例说明与多语言插件示例
├─ docs/                              # 中文主文档与图片资源
├─ licenses/                          # 第三方许可证文本
├─ build.gradle.kts                   # Gradle 构建与打包配置
├─ settings.gradle.kts                # 22 个 Gradle 子模块定义
└─ run-gui.bat                        # Windows 快捷启动脚本
```

The source is split into several Gradle modules, and dependencies between modules are constrained by Gradle at compile time. The engine ported from Umamo, `org.umamo.*`, lives in `:umamo` (`umamo/src/main/kotlin/`) and does not depend on product code. The neutral rig IR `:format-model`, the export framework `:format-compile`, raster export `:targets:raster`, the runtime model `:targets:runtime`, and the runtime binding `:format-eval` are MIT, and they do not depend on any GPL module. The GPL module `:umamo-ir-bridge` depends only on `:umamo` and `:format-model`, and it owns the shared converter `io.github.psd2live.bridge.PuppetIr`. Algorithms and export targets share it, and they do not go through a target’s downgrade, compile, or write implementation. `:targets:cubism` keeps the Cubism target’s downgrade, compile, and write. `:targets:psd` uses the engine. See [Neutral rig IR and export targets](../spec/EXPORT_TARGETS.md).

The product layer `io.github.psd2live.*` lives in the root project (`src/main/kotlin/`). The application layer `application/SourceExport.kt` chooses the source-file export use case and the CLI preset, and provides the `PSD2LivePipeline.run` extension. `core/PSD2LivePipeline.kt` keeps the generation and export computation. Source images, layer transforms, and texture value types belong to the root project’s `document` package. It is not a separate Gradle module. Compatibility aliases in `project` keep the old package names.

Product-package dependencies are checked by `LayerDependencyTest`: `core/project/history/document/util` does not call back into `application/agent/ui`, and `document/util/history` does not depend on `core/project`. The existing `core/project/render` strongly connected group is still there. Its cross-package symbol exceptions are listed in `src/test/resources/architecture/product-layer-exceptions.txt`. Do not add a cycle, widen an exception, or bypass the check with a cross-package wildcard import.

The engine packages are:

| Package | Responsibility |
| --- | --- |
| `org.umamo.runtime` | `PuppetModel`, keyform interpolation, and evaluation |
| `org.umamo.format` | Reading and writing PSD, CMO3, MOC3, and image formats |
| `org.umamo.interop` | Conversion between `PuppetModel` and CMO3 / MOC3 |
| `org.umamo.render` | LWJGL / OpenGL preview |
| `org.umamo.edit` | Immutable edit primitives on a model |
| `io.github.psd2live.core` | The generation pipeline (`PSD2LivePipeline`, `LayerClassifier`, `AdaptiveMeshGenerator`, `RigBuilder`, `MotionGenerator`, `PhysicsGenerator`) and the replayable edits |
| `io.github.psd2live.project` | The neutral workspace document (`WorkspaceDocument`), history storage (`WorkspaceStore`), and the `.psd2live` archive. It does not depend on `application`, Compose, or MCP |
| `io.github.psd2live.application` | The neutral runtime (`WorkspaceRuntime`), command registration and schema, preview sessions, and in-process background jobs. The interface and MCP share it |
| `io.github.psd2live.history` | Branched undo / redo |
| `io.github.psd2live.agent` | The local MCP server and the public tool definitions |
| `io.github.psd2live.render` | GPU rendering of the edit canvas and the p2lrt preview (`CanvasGpu`, `GlCanvasRenderer`, `P2lrtGlRenderer`, `SkiaGpu`) |
| `io.github.psd2live.ui` | The Compose interface: `state` (ViewModel, shortcut registry), `views` (workspaces and panels), `components` (dialogs and controls), `tutorial` (the interactive tutorial). Canvas editing and paint live in the `ui` root package |
| `io.github.psd2live.i18n` | Interface copy. The resources are in `src/main/resources/i18n/` |

Put generation and export logic in `core` / `project` / `application`. Do not write it into Compose interface code.

Start, Help, and Settings are application pages (`AppPage` in `ui/state/AppPages.kt`). They do not enter the project. Each page reuses the editor’s dock frame (`DockSession` and `DockTree` in `DockWorkspaceView`). Each page is a catalog panel plus a content panel (the start page also has a details panel). Panel IDs carry a page prefix (`start.*`, `help.*`, `settings.*`). They share the catalog row and the body container in `PageChrome.kt`. The default arrangement is `pageDockLayout`. Arrangement and closed panels are stored in local preferences (`AppSettings.pageArrangement`). Panels are dragged, floated, and docked again. State they share belongs in the ViewModel or in `StartPageModel`, not in a single panel’s composition. The text of feature updates is in `src/main/resources/whatsnew/<language>.md`. The four languages’ versions and entries must correspond one to one (`WhatsNewTest`). The version number is written by the build into `psd2live/version.properties`, and the code reads `AppVersion`.

## Core constraint: rebuild and replay

`PuppetModel` is not persistent state. A project saves the source image, the layer classification and settings, and a serializable edit record (`RigEditOverlay`). Every time the project is opened or a change happens:

1. `RigBuilder` generates the base rig from the source image again.
2. The authored stage (`RigEditOverlay.replayAuthored`): starts from the rig saved by the last `rig_checkpoint` record in the log, or, when there is none, from the base rig and first replays the static fields of the old format (parameter delete / create, Warp, structure, keyforms), then replays the `authoringJournal` after that point in real order.
3. Finish (`RigEditOverlay.finish`): runs generators such as swing and simulation by the generator dependency graph (`core/DocumentGenerators.kt`), three-way merges generated-result overrides (`generated_override`), then replays panel edits that reference generated parameters.

A revision that already stores an authored rig (one this process has built, or one saved in the archive’s `rig/`) is built directly from it. It does not generate the base rig, and it does not replay the log. See [Document layer](../spec/DOCUMENT_LAYER.md).

So when adding an editing feature:

- The change must be written into a serializable field of `RigEditOverlay` (a new command is preferably a JSON command in the edit log), and saved and restored in the workspace state codec. A change that only edits `PuppetModel` is lost after a rebuild.
- The interface and MCP should call the same edit commands. A low-level method in `org.umamo.edit` is not automatically a public interface.
- The acceptance path is: domain data → history replay → project save and restore → handling for the target Cubism version → export readback → a visual check.

When interface copy is added, `Messages.properties`, `Messages_zh_CN.properties`, `Messages_ja.properties`, and `Messages_ko.properties` all need the new entry at the same time, with the key counts kept equal (`CatalogContractsTest` checks this).

## Checking export results

Acceptance of plugin Git distribution needs Git and `git daemon` installed locally. Set `PSD2LIVE_PLUGIN_GIT_TEST=1` and run `test --tests '*ModifierPluginTest.gitTagsAndCommitsInstallPrebuiltPinnedPackages'`. It creates an already-built test package in a temporary directory and listens only on this machine. It checks tags, commit ids, the default branch, versions living side by side, and a real evaluation. It does not reach an external repository. Ordinary regression skips this.

- Every file referenced by `.model3.json` has to be delivered with the model.
- Read the warnings in the log panel and in the diagnostic JSON.
- Save a `.psd2live` project when editing should continue. `.psd2live.json` is only a report.

Related: [Cubism Native preview](CUBISM_SDK_SETUP.md) · [CI and releases](CUBISM_CI_RELEASE.md) · [Texture upscaling](TEXTURE_UPSCALE.md) · [`build.gradle.kts`](../../../build.gradle.kts)
