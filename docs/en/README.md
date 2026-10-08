# PSD2Live

[中文](../../README.md) · [日本語](../ja/README.md) · [한국어](../ko/README.md) · [Download](https://github.com/tsunehimatoi/psd2live/releases/latest) · [Changelog](../zh/CHANGELOG.md) · [Documentation](../README.md)

**Generate a Live2D model from a layered PSD, refine, rig, animate, simulate and texture it in one desktop workspace, then export it to Cubism, VTube Studio, the web or video.**

![PSD2Live editing workspace: hierarchy on the left, the canvas in Deform mode showing the front-hair mesh, model presets and the layer classification table on the right](../imgs/overview.webp)

PSD2Live recognizes parts from layer names and generates meshes, a deformer hierarchy, head, body and facial parameters, basic motions, physics and cloth simulation. The generated model is a starting point: keep shaping it on the canvas, split meshes, paint textures, build a skeleton, edit motion curves and tune the texture atlas, then export a `.cmo3` for further work in Cubism Editor, a `.moc3` runtime bundle, or another format.

![The same model at different head and body angles: left, lower right, neutral, upper right, lower left](../imgs/poses.webp)

<sub>Automatic result for the bundled example PSD, without manual edits, driven by the head and body angle parameters.</sub>

## Features

| Area | Capabilities |
| --- | --- |
| Automatic rigging | Chinese / English / Japanese / Korean layer names, automatic splitting of paired parts, adaptive meshes, head and body deformer chains, eye, mouth, gaze and brow parameters, idle / blink / nod / shake motions, hair and eye-jelly physics |
| Canvas editing | Seven modes: Select, Deform, Edit, Simulate, Skeleton, Paint and Preview; deformation brushes, mesh subdivision and cuts, splitting by mesh or polygon, front / back depth split, Warp / Rotation creation, Glue, deform paths (experimental) |
| Skeleton | Inferred skeletons for limbs, tails and wings with FK / IK posing; joints shaped after human joint poses; baked on export into native Cubism deformers, parameters and corrective keyforms that run without PSD2Live |
| Swing, physics and simulation | Lateral / vertical sway with matching pendulums; visual pendulum editing, response curves and chained groups, evaluated to match the Cubism Native Framework; 2D cloth and hair simulation baked into parameters, keyforms and pendulums |
| Artwork and textures | Transparent image placement, toggle and exclusive variants, layer painting and edge cleanup; a Texture workspace for per-layer texture density and dragging or scaling tiles on the atlas; optional 2× / 4× texture upscaling |
| Animation | Timeline, keyframe and curve editing with live preview; preset crouch, wave, cheer and other motions when a skeleton is available |
| Export | `.moc3` / `.cmo3` for Cubism 3.0 – 5.3, VTube Studio, the PSD2Live runtime rig and web player, PNG sequences, sprite sheets, GIF, APNG, WebP, video and layered PSDs at a pose; every export comes with a loss report |
| Projects | Single-file `.psd2live` projects, branching history, tabs, eight workspace presets plus a blank layout, light and dark themes, Photoshop / Blender / Cubism keymaps |
| Agents | Authenticated local MCP server with more than 180 public operations for observation, shapes, artwork, textures, parameters, skeletons, motions, physics, simulation, export and history; atomic batches and dry runs |

<table>
<tr>
<td width="50%"><img src="../imgs/skeleton.webp" alt="Rigging workspace: the skeleton tab lists bones and bound meshes; both arms are raised with IK on the canvas"></td>
<td width="50%"><img src="../imgs/physics.webp" alt="Physics workspace: preview canvas, parameters and the physics panel with its pendulum canvas and response curve"></td>
</tr>
<tr>
<td align="center"><sub>Skeleton: inferred automatically, posed by dragging bone tips with IK</sub></td>
<td align="center"><sub>Physics: pull the pendulum directly and watch the response curve update</sub></td>
</tr>
</table>

![Animation workspace: motion list, preview canvas and parameters, with the animation editor showing nine parameter tracks of the idle motion](../imgs/animation.webp)

<sub>Screenshots show the Chinese interface; switch languages with the globe button at the top right.</sub>

## Download

Get the latest version from [Releases](https://github.com/tsunehimatoi/psd2live/releases/latest); see the [changelog](../zh/CHANGELOG.md) (Chinese) for what changed.

| Platform | Package | Notes |
| --- | --- | --- |
| Windows 10 / 11 x64 | Portable ZIP, EXE, MSI | Bundles a Java runtime; extract or install and run. Each also comes as a `-ffmpeg` build with ffmpeg included. An upgrade installs into the installed folder without asking again |
| Linux x86_64 | Deb | Bundles the runtime and Cubism native preview; requires X11 / GLX (XWayland works) |
| macOS and others | No package yet | Install JDK 21 and [run from source](#build-from-source) |

Uninstalling on Windows keeps your settings and workspace data; to delete them too, choose Remove in the uninstall wizard and tick "Also delete my PSD2Live data". Saved project files are always kept.

The Linux native preview does not support pure Wayland without XWayland, aarch64 or musl (e.g. Alpine); those environments fall back to the built-in renderer. See [Cubism native preview](guide/CUBISM_SDK_SETUP.md).

## Quick start

1. **Import a PSD** with **File → Import → New project from PSD…** (`Ctrl+Shift+O`) or drop it on the window. On the Start screen that opens next, pick the model presets (Minimal / Default / Full) and tick the layers to split by mesh (such as both legs drawn on one layer).
2. **Check the classification** in the Layers table: part type, side and variant settings. Correct anything that was misread.
3. **Preview and refine** in the Preview workspace, then adjust in the Edit, Rigging, Animation, Physics and Texture workspaces as needed.
4. **Save and export**: `Ctrl+S` saves a `.psd2live` project; `Ctrl+G` exports `.moc3` / `.cmo3`, and other formats are under **File → Export as**.

<img src="../imgs/import-split.webp" width="560" alt="Splitting layers by mesh: legwear, footwear, eyelash and front hair are each detected as two parts">

**New to PSD2Live? Open Help → Tutorials… (`F1`).** The interactive tutorials highlight each control and use your current shortcuts. There is a beginner path (18 lessons) and a path for Cubism users (13 lessons); the [user guide](guide/USER_GUIDE.md) is the text companion.

## Preparing artwork

Layer structure matters most for the automatic result:

- Separate eye whites, irises and upper lashes, and keep the part of the iris hidden under the eyelid.
- Provide an open-mouth drawing, or separate upper teeth, lower teeth and tongue.
- Separate front and back hair and leave overlap under covered areas.
- Keep the body roughly upright; rasterize layer effects and text.

See [PSD preparation and naming](spec/PSD_LAYER_SPEC.md) for the full name table. Unrecognized layers are kept and can be classified manually.

## Export

**File → Export Live2D model…** (`Ctrl+G`) writes the Cubism formats:

| File | Purpose |
| --- | --- |
| `.psd2live` | PSD2Live project: source PSD, artwork, settings, every edit and all history branches. Save this to keep working |
| `.cmo3` | Cubism Editor model project for inspection and refinement in the official editor |
| `.model3.json` + `.moc3` + textures, physics, motions | Runtime bundle; deliver together and load from `.model3.json` |
| `.psd2live.json` | Export diagnostics, not a project |

The target version ranges from Cubism 3.0 to 5.3 (default 5.0); features the target cannot express are downgraded and reported.

**File → Export as** offers other formats:

| Format | Notes |
| --- | --- |
| VTube Studio | The `.moc3` bundle plus `.vtube.json`, mapping face tracking onto the standard parameters, one hotkey per motion |
| PSD2Live runtime rig / web player | A `.p2lrt` rig, or a WebGL player page that opens in a browser; see [runtime](../zh/spec/RUNTIME.md) (Chinese) |
| PNG sequence, sprite sheet, GIF | Rendered from sampled motions |
| APNG, animated WebP, MP4, WebM, ProRes 4444 | Encoded by ffmpeg: included in the Windows `-ffmpeg` packages, otherwise install it yourself (set it in the settings or put it on `PATH`) |
| PSD at a pose, source PSD | A layered PSD of the current pose, or the source PSD including generated layers |

Every export compiles through the same neutral rig IR and comes with a loss report listing what the target format cannot keep. Spine 4.2, DragonBones 5.5 and glTF 2.0 are experimental targets available only from the command line. See [export targets](../zh/spec/EXPORT_TARGETS.md) (Chinese).

A successful export does not guarantee identical results in every runtime, so check the model in the target editor and runtime before delivery; support boundaries are described in the [runtime and export reference](../zh/spec/RUNTIME_EXPORT_ARCHITECTURE_AND_GAPS.md) (Chinese). The built-in renderer needs no official SDK. [Cubism native preview](guide/CUBISM_SDK_SETUP.md) is optional and lets you compare against the official runtime's rendering and physics.

## Playing models in your own project

[`runtime/`](../../runtime/) is an open-source (MIT) Rust runtime that plays exported `.p2lrt` rigs: parameter-driven deformation, physics, motion clips, and procedural blinking, breathing, gaze and lip sync. It exposes a C interface; the web player uses its WebAssembly build, and a Godot 4 node, `P2LCharacter`, lives in [`runtime/godot/`](../../runtime/godot/). The editor's software preview evaluates through the same library. See [runtime](../zh/spec/RUNTIME.md) (Chinese).

## Connecting an agent (MCP)

1. Keep PSD2Live running and open **Tools → MCP…**. It also sets the port, the access token and which tools are published (compact by default).
2. Copy the command or configuration for your host (Claude Code, Codex, generic JSON). Stdio-only hosts use [`mcp_proxy.py`](../../mcp_proxy.py) in the repository root.
3. Have the agent read the project with `workspace_overview` and single objects with `workspace_inspect`; operations that are not listed as tools are found with `workspace_list_operations` and called through `workspace_call`. Every write goes into the same history as UI edits and can be undone in the app.

`workspace_apply_edits` commits several edits atomically (all or nothing), and `workspace_preview_edits` dry-runs them to check geometry without changing the project. The [MCP reference](../zh/agent/MCP_AUTHORING.md) (Chinese) lists requests and examples. The MCP server does not generate images; new artwork requires image generation in the host. A callable tool does not make a complex modeling task reliable; [recorded evaluations](../zh/STATUS.md) keep both successes and failures.

## Build from source

Requires JDK 21; Gradle runs through the bundled wrapper. When Rust (cargo) is installed the Rust runtime is built too; without it the step is skipped and the editor uses its built-in evaluator.

```bash
# start the GUI (Windows: .\gradlew.bat run or run-gui.bat)
./gradlew run

# generate a model from a PSD on the command line
./gradlew run --args="--input examples/tml/psd-input/tml.psd --output build/example-output"

# export a project to another format, e.g. GIF
./gradlew run --args="export model.psd2live --target gif --set clip=Nod"

# run the tests / build an installer for this platform
./gradlew test
./gradlew packageDistributionForCurrentOS
```

Source builds use the built-in renderer. The official Cubism SDK must be obtained separately to build the native bridge. See [development and CLI](guide/DEVELOPMENT.md) for all CLI options, packaging and native builds.

## Documentation

| Using PSD2Live | Reference |
| --- | --- |
| [User guide](guide/USER_GUIDE.md) · [Development and CLI](guide/DEVELOPMENT.md) | [PSD preparation](spec/PSD_LAYER_SPEC.md) · [Deformers and parameters](spec/DEFORMER_AND_PARAMETER_SPEC.md) |
| [Cubism native preview](guide/CUBISM_SDK_SETUP.md) · [CI and releases](guide/CUBISM_CI_RELEASE.md) | [Project format](spec/PROJECT_FORMAT.md) · [Implementation overview](spec/IMPLEMENTATION_COMPARISON.md) |

Guides for canvas editing, skeletons, swing, physics, simulation and texture upscaling, and the export and runtime references, are currently in Chinese; see the [documentation index](../README.md). Example PSDs and outputs are in [examples](../../examples/readme.md).

## Contributing

Issues, reproducible PSDs, documentation fixes and code are welcome.

- **Bug reports**: include the version, operating system, steps to reproduce, expected and actual results, and relevant entries from the Log panel.
- **Code**: run the tests related to your change (`./gradlew test`). New editing features must persist in the project and replay after reopening; see [development](guide/DEVELOPMENT.md).
- **Agent evaluations**: also record the host, model, retries and cost, following the [evaluation format](../zh/STATUS.md).

## License

The application as a whole is released under [GPL-3.0](../../LICENSE): the `umamo` Live2D engine, the Cubism and PSD export targets and the application itself are GPL-3.0. The neutral rig IR (`format-model`), the export framework (`format-compile`), the other export targets and the Rust runtime (`runtime/`) are MIT and can be reused on their own; see each module's `LICENSE` and the [module table](../zh/spec/EXPORT_TARGETS.md#模块) (Chinese). Third-party components are listed in [THIRD_PARTY_NOTICES.md](../../THIRD_PARTY_NOTICES.md); example artwork has its own usage terms.

PSD2Live is an independent project, not affiliated with or endorsed by Live2D Inc. This repository does not contain or distribute proprietary Live2D Cubism SDK components. Live2D and Cubism are trademarks of Live2D Inc.
