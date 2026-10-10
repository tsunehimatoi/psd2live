<div align="center">

<img src="../branding/psd2live-icon-mesh.png" width="112" alt="PSD2Live icon">

# PSD2Live

**An open-source 2D character rigging and animation editor**

Build a moving first draft from a layered PSD, then shape, rig, simulate and animate it in one workspace,<br>
and export to Cubism, VTube Studio, its own runtime, the web, game engines or video.

[![Release](https://img.shields.io/github/v/release/tsunehimatoi/psd2live?label=download)](https://github.com/tsunehimatoi/psd2live/releases/latest)
[![License](https://img.shields.io/badge/license-GPL--3.0%20%2F%20MIT-blue)](#license)
[![Platform](https://img.shields.io/badge/platform-Windows%20%7C%20Linux-lightgrey)](#download)
[![Cubism](https://img.shields.io/badge/Cubism-3.0%20%E2%80%93%205.3-ff6b9d)](#export-and-playback)
[![MCP](https://img.shields.io/badge/MCP-180%2B%20ops-8a63d2)](#connecting-an-agent-mcp)

[中文](../../README.md) · [日本語](../ja/README.md) · [한국어](../ko/README.md)

[Download](https://github.com/tsunehimatoi/psd2live/releases/latest) · [Quick start](#quick-start) · [Documentation](../README.md) · [Changelog](../zh/CHANGELOG.md) · [Roadmap](../zh/ROADMAP.md)

</div>

PSD2Live is a complete 2D character rigging toolchain: it generates meshes, deformers, parameters, motions and physics from a layered PSD, lets you keep editing in a desktop editor, and compiles to Cubism, its own p2lrt runtime and several general formats. It has its own evaluation engine, project format and branching history. One-click generation is only the starting point: every edit is stored as a replayable record and still applies after regeneration.

> [!NOTE]
> Cubism is the primary export target, not the limit of what PSD2Live can do. Features Cubism lacks, such as skeletons and 2D simulation, are baked on export into native deformers, parameters, keyforms and pendulums; the delivered model does not depend on PSD2Live.

## Who it is for

| You are | What PSD2Live does for you |
| --- | --- |
| **New to rigging and just want a moving model** | Layer the PSD by the naming guide, import it and pick a preset: within minutes you have a model with idle, blink, nod and hair physics, ready for VTube Studio or the web |
| **Not a modeler and want AI to do the work** | A local MCP server lets agents such as Claude Code and Codex read and edit the project; every step lands in the same history, to check and undo in the editor |
| **Experienced and tired of repetitive work** | Meshes, deformer chains, facial parameters and physics in one click, then refine on the canvas; hand edits are stored as replayable records and survive regeneration |
| **Looking for an open-source alternative** | Editor, engine and project format are all open source; edit, preview and export `.moc3` / `.cmo3` without the official SDK |
| **Held back by what Cubism offers** | Skeletons and IK, 2D cloth and hair simulation and automatic armatures, tools Cubism does not have, baked into native structures on export |
| **Putting characters into your own program** | The open-source Rust runtime p2lrt plugs into C / C++, .NET, Android, the web and Godot, with its own software renderer; also video, frame sequences, and Spine and glTF (experimental) |

## Core capabilities

| Area | Capabilities |
| --- | --- |
| **Generation** | Chinese / English / Japanese / Korean layer names; adaptive meshes, head and body deformer chains, facial parameters, idle / blink / nod / shake motions, hair physics and cloth simulation |
| **Canvas editing** | Seven modes: Select, Deform, Edit, Simulate, Skeleton, Paint, Preview; deformation brushes, mesh topology editing, layer splits and depth splits, Warp / Rotation, Glue |
| **Skeleton and simulation** | Automatic armatures, FK / IK, preset motions; XPBD cloth and hair simulation; all baked into native Cubism structures |
| **Animation and physics** | Timeline, keyframes and curves; visual pendulum editing evaluated to match the Cubism Native Framework |
| **Textures** | Atlas layout, per-layer texture density, optional 2× / 4× upscaling |
| **Compile and export** | Every target compiles from one neutral rig IR, each export with a loss report |
| **p2lrt runtime** | Open-source Rust runtime: C ABI, software renderer, WebAssembly web player, Godot 4 node, .NET and Android builds; matches the editor's evaluation pose for pose |
| **Agents** | Local MCP server with more than 180 public operations, sharing commands and history with the editor; atomic batches and dry runs |

## Results

The images below come from the two example PSDs in the repository (the knee test and the body-motion animation use a separate long-legged character project). Both examples were imported with the Full preset and not edited by hand; hair and skirts move by baked simulation. Test plates are produced by the development tools and command-line exports; editor screenshots are frames rendered by the real editor window.

### Editor

<table>
<tr>
<td width="50%" valign="top"><img src="../imgs/readme/workspace-animation.webp" alt="Animation workspace: motion list, preview canvas, parameters and parameter tracks in the animation editor"><br><sub><b>Animation</b>: motion list, timeline and curves; preview and parameters follow the playhead</sub></td>
<td width="50%" valign="top"><img src="../imgs/readme/skeleton-ds.webp" alt="Skeleton mode: automatically inferred torso, limb and tail bones over the character"><br><sub><b>Skeleton</b>: adjust joints and pose with FK / IK on the canvas</sub></td>
</tr>
<tr>
<td width="50%" valign="top"><img src="../imgs/readme/workspace-texture.webp" alt="Texture workspace: tiles on an atlas page, model preview and texture panel"><br><sub><b>Textures</b>: arrange tiles on the atlas and set texture density per layer</sub></td>
<td width="50%" valign="top"><img src="../imgs/readme/start-screen.webp" alt="Start screen: model presets and the layers that can be split by mesh"><br><sub><b>Import</b>: pick model presets and split left and right parts drawn on one layer</sub></td>
</tr>
</table>

### Physics and simulation

The body angle moves up and down, side to side, then round an ellipse. Hair, skirt, sleeves and bow follow by baked simulation: they lag behind each rise and turn and swing back once the body stops. On the left, each part's pin weights: 1 follows the body, 0 is free, values between pull in proportion.

<p align="center"><img src="../imgs/readme/star-orbit.webp" alt="Left: the pin weights of every simulated part (back hair, front hair, skirt, sleeves, bow); right: the body angle drives the character up and down, side to side and round an ellipse, and the hair and cloth lag and swing" width="785"></p>

<p align="center"><img src="../imgs/readme/sim-skirt.webp" alt="Skirt close-up under the same body motion: simulation off on the left, the skirt follows the body rigidly; baked cloth simulation on the right, the skirt lags and swings back" width="660"></p>

### Automatic rigging

A complete head and face rig on import: face warp, feature planes, hair and body follow-through are driven by one generated deformer chain.

![Head parameter space: both examples at nine combinations of AngleX ±45 and AngleY ±30, generated without hand edits](../imgs/readme/rig-head.webp)

![Facial parameters: neutral, blink, half-closed with gaze, mouth open, smile, frown](../imgs/readme/rig-face.webp)

### Automatic mesh topology

Meshes follow the texture outline; holes, thin bridges and tips each get their own outline loop. Settings apply globally or per layer.

![Topology stress test: one synthetic layer with 20 round holes, 28 radial slots, a six-hole hub, 12 thin spokes and 4 tapering tendrils, each fitted by the automatic mesh; close-ups on the right](../imgs/readme/mesh-stress.webp)

![Edge rings against interior fill: rows of 1 / 2 / 3 outline rings, columns of graded Poisson, adaptive quadtree, contour paving and triangle fractal fills, each with vertex and triangle counts and time](../imgs/readme/mesh-params.webp)

![Wrap: 26 strands 2–10 px apart meshed at wrap 0, 3, 8 and 20; a wider wrap merges narrow gaps into one outline with fewer vertices](../imgs/readme/mesh-wrap.webp)

<table>
<tr>
<td width="50%" valign="top"><img src="../imgs/readme/mesh-real.webp" alt="Automatic mesh of an example character's long curly hair in the editor"><br><sub><b>Real artwork</b>: the example character's long curly hair, the outline following every curl</sub></td>
<td width="50%" valign="top"><img src="../imgs/readme/sim-weights-hair.webp" alt="Pin weights of the back hair in Simulate mode: red at the top for pinned, fading to blue"><br><sub><b>Vertex weights</b>: Simulate mode shows and paints pin weights on the same mesh (red pinned, blue free)</sub></td>
</tr>
</table>

### Automatic skeleton

Torso, limbs and tail are inferred from layer tags and the bones bake into native deformers and keyforms. Knees and elbows are shaped like human joints: the kneecap rounds out on the outside, the inside folds, and the rigid part of the thigh does not drift with the shin. The same preset motions fit different body shapes.

![Knee test: one shin of a long-legged character bent to 0°, 60° and 120°, and knee close-ups at 0°, 30° and 120° showing the outer kneecap and inner fold](../imgs/readme/rig-knee.webp)

![Preset poses: both examples at rest and at the widest pose of six presets: wave, cheer, crouch, weight shift, shy, head tilt](../imgs/readme/rig-body.webp)

### p2lrt runtime conformance

The Rust runtime replays the editor's reference poses and trajectories; geometry, physics and simulation all pass, and four file encodings read back identical results.

![Runtime against the editor evaluator: maximum vertex error of 84 rigs on a log scale, all under the 0.02 px limit or within their own sensitivity; physics 16/16 and simulation 12/12 pass](../imgs/readme/runtime-conformance.webp)

![Blend modes across hosts: 18 color blend modes, multiply / screen colors, masks and inverted masks, an isolated group and 5 alpha blend modes, rendered by the reference rasterizer, the software renderer, the web player and the Godot node, with each difference from the reference](../imgs/readme/runtime-blend.webp)

## Relationship to Cubism

PSD2Live runs without Cubism Editor or the SDK but stays compatible with Cubism formats. Each feature falls into one of three tiers by whether it can enter Cubism:

| Tier | Meaning | Examples |
| :---: | --- | --- |
| **Lossless** | Native in Cubism, or affects only the editing process | Meshes, deformers, parameters, physics, motions; brushes, splits, generation |
| **Baked** | Not in Cubism, but bakes into keyforms, parameters or pendulums | Skeletons and IK, cloth / hair simulation, swing generation |
| **Runtime only** | Cannot be baked; works only in the own runtime, the web, Godot and raster exports | Planned dynamic lighting and stylized rendering, see the [roadmap](../zh/ROADMAP.md) (Chinese) |

When exporting `.moc3` / `.cmo3`, the last two tiers are lowered to the target version (Cubism 3.0 – 5.3) and recorded in the loss report.

## Download

Get the latest version from [Releases](https://github.com/tsunehimatoi/psd2live/releases/latest); see the [changelog](../zh/CHANGELOG.md) (Chinese) for what changed.

| Platform | Package | Notes |
| --- | --- | --- |
| Windows 10 / 11 x64 | Portable ZIP, EXE | Bundles a Java runtime; unzip or install and run. Each also comes as a `-ffmpeg` build with ffmpeg included |
| Windows 11 ARM64 | Portable ZIP, EXE | Bundles a Java runtime; no Cubism native preview (Live2D ships no Cubism Core for it), the preview uses the PSD2Live runtime |
| Linux x86_64 / arm64 | Deb | Bundles the runtime and Cubism native preview (experimental from Live2D on arm64); needs X11 / GLX (XWayland works) |
| macOS and others | No package yet | Install JDK 21 and [run from source](#building-from-source) |

<details>
<summary>Upgrading, uninstalling and Linux notes</summary>

- **Upgrading**: installs into the existing folder without asking for a path; EXE / MSI installs of 3.1.x and earlier are removed automatically and their folder is reused.
- **Uninstalling**: settings and workspace data are kept by default; the uninstaller asks whether to delete PSD2Live's data as well, and choosing Yes removes it. Saved projects and files you put in the install folder are always kept.
- **Linux**: native preview does not support pure Wayland without XWayland or musl (such as Alpine); those fall back to the built-in renderer. See [Cubism native preview](guide/CUBISM_SDK_SETUP.md).

</details>

## Quick start

1. **Import a PSD** with **File → Import → New project from PSD…** (`Ctrl+Shift+O`) or drop it on the window. On the Start screen, pick the model presets (Minimal / Default / Full) and tick the layers to split by mesh (such as both legs drawn on one layer).
2. **Check the classification** in the Layers table: part type, side and variant settings. Correct anything that was misread.
3. **Preview and refine** in the Preview workspace, then adjust in the Edit, Rigging, Animation, Physics and Texture workspaces as needed.
4. **Save and export**: `Ctrl+S` saves a `.psd2live` project; `Ctrl+G` exports `.moc3` / `.cmo3`, and other formats are under **File → Export as**.

> [!TIP]
> **New to PSD2Live? Open Help → Tutorials… (`F1`).** The interactive tutorials highlight each control and use your current shortcuts, with a beginner path (18 lessons) and a path for Cubism users (13 lessons). The [user guide](guide/USER_GUIDE.md) is the text companion.

### Preparing artwork

Generation quality depends mostly on how the PSD is layered:

- separate eye whites, irises and upper lashes, and keep the parts of the iris hidden by the eyelids;
- provide an open mouth (or separate upper teeth, lower teeth and tongue);
- separate front and back hair and leave room behind what they cover;
- keep the body roughly upright, and rasterize layer effects and text beforehand.

The full naming table is in [PSD preparation](spec/PSD_LAYER_SPEC.md). Unrecognized layers are kept and can be classified by hand.

## Export and playback

| Destination | Output |
| --- | --- |
| **Cubism Editor** | A `.cmo3` model project to check and refine in the official editor |
| **Cubism runtime** | `.model3.json` + `.moc3` with textures, physics, motions and more; load from `.model3.json` |
| **VTube Studio** | The `.moc3` bundle plus `.vtube.json`, face tracking mapped to standard parameters and a hotkey per motion |
| **Your program / engine / the web** | A `.p2lrt` rig for C / C++, .NET, Unity, Godot and Android, or a WebGL player page that opens in a browser |
| **Images and video** | PNG sequences, sprite sheets, GIF; APNG, animated WebP, MP4, WebM, ProRes 4444 (through ffmpeg) |
| **PSD** | A layered PSD at the current pose, or the source PSD with generated layers |
| **Experimental** | Spine 4.2, DragonBones 5.5, glTF 2.0, from the command line only |

`Ctrl+G` exports Cubism formats for target versions 3.0 – 5.3 (default 5.0); the rest are under **File → Export as**. See [export targets](../zh/spec/EXPORT_TARGETS.md) (Chinese).

> [!IMPORTANT]
> To keep working, save the `.psd2live` project: it holds the source PSD, artwork, settings, every edit and the history branches. The `.psd2live.json` written with an export is only a diagnostic report. A successful export does not guarantee identical results in every runtime, so check the model in the target editor and runtime before delivery; see the [runtime and export reference](../zh/spec/RUNTIME_EXPORT_ARCHITECTURE_AND_GAPS.md) (Chinese).

The built-in renderer needs no official SDK. [Cubism native preview](guide/CUBISM_SDK_SETUP.md) is optional and lets you compare against the official runtime's rendering and physics.

## p2lrt runtime

[`runtime/`](../../runtime/) is an open-source (MIT) Rust runtime that plays exported `.p2lrt` rigs. The editor's software preview evaluates through the same library.

| Item | Description |
| --- | --- |
| **Core evaluation** | Cubism-equivalent Warp / Rotation deformation, blend shapes, Glue, parts and draw order, opacity and color channels; matches the editor's evaluation pose for pose |
| **Physics and motions** | Pendulum physics with physics3.json semantics, wind and stabilization; up to 16 motion layers by priority, with events and part / rig opacity curves, fades as in Cubism; several expressions at once |
| **Procedural behavior** | Blinking, breathing, gaze and lip sync (audio-driven if wanted); a host layer adds outside parameters (such as face tracking) over motions |
| **Advanced extensions** | Optional: runtime joint skinning, exact links, live XPBD cloth and hair with collision; when off, results are bit-identical to core evaluation |
| **Rendering** | Draw by the header's drawing rules, or let the built-in software renderer `p2l_render` draw into an RGBA image: every color and alpha blend mode, masks, isolated groups and culling, as the editor's reference rasterizer; per-mesh change flags let the host upload only what changed |
| **Host integration** | One read-only model shared by many instances; staged updates, part opacity and color overrides, hit areas, a log and a host allocator; an internal error fails that handle without aborting the host |
| **File format** | `.p2lrt` 2.0: a chunked container whose core chunks map one to one to moc3, with optional extension chunks; deflate / zstd compression, byte-identical output from the same IR |
| **Integration** | C / C++ header [`p2l_runtime.h`](../../runtime/include/p2l_runtime.h) with a dynamic or static library (usable from Unreal), .NET P/Invoke bindings and a Unity component example, Android (arm64-v8a, armeabi-v7a, x86_64), WebAssembly web player, Godot 4 node `P2LCharacter` ([`runtime/godot/`](../../runtime/godot/)); see [other languages and platforms](../../runtime/bindings/README.md) |

<table>
<tr>
<td width="50%" align="center"><img src="../imgs/readme/runtime-web.webp" alt="The web player showing an example character in a browser, with clip, expression and mouth controls below"><br><sub>Web player (WebAssembly + WebGL)</sub></td>
<td width="50%" align="center"><img src="../imgs/readme/runtime-godot.webp" alt="A P2LCharacter node showing an example character in Godot 4"><br><sub>Godot 4 node</sub></td>
</tr>
</table>

Format and evaluation rules: [runtime](../zh/spec/RUNTIME.md) and [`.p2lrt` 2.0 format](../zh/spec/P2LRT_V2.md) (Chinese).

## Connecting an agent (MCP)

1. Keep PSD2Live running and open **Tools → MCP…** to set the port, access token and published tool set (compact by default).
2. Copy the command or configuration for your host (Claude Code, Codex, generic JSON). Stdio-only hosts use [`mcp_proxy.py`](../../mcp_proxy.py) at the repository root.
3. Have the agent read the project with `workspace_overview` and single objects with `workspace_inspect`; operations not listed as tools are found with `workspace_list_operations` and called through `workspace_call`.

`workspace_apply_edits` commits several edits atomically (all or nothing), and `workspace_preview_edits` dry-runs them first. Requests and examples are in the [MCP reference](../zh/agent/MCP_AUTHORING.md) (Chinese).

> [!NOTE]
> The MCP server does not generate images; new artwork requires image generation in the host. A callable tool does not make a complex modeling task reliable; [recorded evaluations](../zh/STATUS.md) keep both successes and failures.

## Building from source

Requires JDK 21; Gradle runs through the bundled wrapper. With Rust (cargo) installed, the Rust runtime is built as well; without it the build skips it and the editor uses its built-in evaluator.

```bash
# Start the GUI (Windows: .\gradlew.bat run or run-gui.bat)
./gradlew run

# Generate a model from a PSD on the command line
./gradlew run --args="--input examples/tml/psd-input/tml.psd --output build/example-output"

# Export a project to another format, for example GIF
./gradlew run --args="export model.psd2live --target gif --set clip=Nod"

# Run the tests / package for the current platform
./gradlew test
./gradlew packageDistributionForCurrentOS
```

Source builds use the built-in renderer; the official Cubism SDK must be obtained separately to build the bridge library. All CLI options, packaging and the native preview build are in [development and CLI](guide/DEVELOPMENT.md).

## Documentation

| Using PSD2Live | Reference |
| --- | --- |
| [User guide](guide/USER_GUIDE.md) · [Development and CLI](guide/DEVELOPMENT.md) | [PSD preparation](spec/PSD_LAYER_SPEC.md) · [Deformers and parameters](spec/DEFORMER_AND_PARAMETER_SPEC.md) |
| [Cubism native preview](guide/CUBISM_SDK_SETUP.md) · [CI and releases](guide/CUBISM_CI_RELEASE.md) | [Project format](spec/PROJECT_FORMAT.md) · [Implementation overview](spec/IMPLEMENTATION_COMPARISON.md) |

Guides for canvas editing, skeletons, swing, physics, simulation and texture upscaling, and the export and runtime references, are currently in Chinese; see the [documentation index](../README.md). Example PSDs and outputs are in [examples](../../examples/readme.md); planned work is on the [roadmap](../zh/ROADMAP.md) (Chinese).

## Contributing

Issues, reproducible PSDs, documentation fixes and code are welcome.

- **Bug reports**: include the version, operating system, steps to reproduce, expected and actual results, and relevant entries from the Log panel.
- **Code**: run the tests related to your change (`./gradlew test`). New editing features must persist in the project and replay after reopening; see [development](guide/DEVELOPMENT.md).
- **Agent evaluations**: also record the host, model, retries and cost, following the [evaluation format](../zh/STATUS.md).

## License

| Part | License |
| --- | --- |
| The application, the `umamo` engine, the Cubism and PSD export targets | [GPL-3.0](../../LICENSE) |
| The neutral rig IR `format-model`, the export framework `format-compile`, the other export targets, the Rust runtime `runtime/` | MIT, reusable on their own |

The application as a whole is released under GPL-3.0; see each module's `LICENSE` and the [module table](../zh/spec/EXPORT_TARGETS.md#模块) (Chinese). Third-party components are listed in [THIRD_PARTY_NOTICES.md](../../THIRD_PARTY_NOTICES.md); example artwork has its own usage terms.

<sub>PSD2Live is an independent project, not affiliated with or endorsed by Live2D Inc. This repository does not contain or distribute proprietary Live2D Cubism SDK components. Live2D and Cubism are trademarks of Live2D Inc.</sub>
