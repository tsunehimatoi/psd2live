# PSD2Live

[中文](README.md) · [日本語](README_ja.md) · [Download releases](https://github.com/tsunehimatoi/psd2live/releases/latest) · [Documentation](docs/README.md)

**Generate a Live2D model from a layered PSD, then edit, preview and export it in one workspace.**

PSD2Live recognizes parts from layer names and builds meshes, deformers, facial parameters, basic motions and physics. Refine the result with canvas tools, paint textures, add variants and artwork, or connect an MCP agent.

![Editing workspace with hierarchy, mesh tools and model settings](docs/imgs/view.png)

## Get started

Windows 10/11 x64 packages include a Java runtime. Extract the portable ZIP or use an EXE / MSI installer.

The same release page offers a Linux amd64 Deb with Cubism Native preview. It requires X11/GLX; the native preview supports XWayland but not pure Wayland without XWayland, aarch64, or musl/Alpine. See [SDK setup](docs/en/guide/CUBISM_SDK_SETUP.md). Linux source builds require JDK 21.

1. Import a layered PSD from **File → Import PSD** (`Ctrl+Shift+O` by default).
2. Check the preview and correct part classifications and sides in the Layers panel.
3. Edit as needed and save a `.psd2live` project with `Ctrl+S`.
4. Open export settings with `Ctrl+G` to generate `.cmo3` or the `.moc3` runtime bundle.

**Start with Help → Tutorials.** Interactive lessons highlight the actual controls. The [short user guide](docs/en/guide/USER_GUIDE.md) follows the same 14 lessons.

## Features

| Area | Available tools |
| --- | --- |
| Automatic rigging | Multilingual layer classification, paired-part splitting, adaptive meshes, head/body motion, eyes, mouth and gaze |
| Canvas | Select / Deform / Edit / Paint modes; deformation brushes, mesh cuts and subdivision, Warp / Rotation, Glue and experimental deform paths |
| Artwork | Transparent image placement, toggle and exclusive variants, texture painting, optional 2× / 4× upscaling |
| Preview | Parameters and XY controls, idle / blink / nod / shake motions, gaze tracking and physics |
| Projects | Portable project archive, branching history, undo/redo, tabs, configurable panels, themes and keymaps |
| Agents | Authenticated local MCP for observation, artwork, forms, parameters, paths, physics and history |

![Layer, tool, inspector, parameter, animation and physics panels](docs/imgs/tools.png)

Results depend on the source artwork. Separate eye whites, irises and upper lashes; supply an open-mouth image and separate front/back hair. See [PSD preparation](docs/en/spec/PSD_LAYER_SPEC.md).

## Files and compatibility

- `.psd2live` saves source artwork, settings, edits and history for continued work.
- `.cmo3` is an editor project for further inspection and refinement in Cubism.
- `.moc3`, `.model3.json`, textures and optional sidecars form the runtime delivery.
- `.psd2live.json` is an export report, not a saved project.

The built-in renderer works without the official SDK. [Native SDK preview](docs/en/guide/CUBISM_SDK_SETUP.md) is optional. Export support does not imply complete Cubism feature coverage or identical results for every operation; see the [architecture reference](docs/zh/spec/RUNTIME_EXPORT_ARCHITECTURE_AND_GAPS.md) (Chinese).

## Agents

Open **Tools → MCP → MCP connection and installation**, copy the configuration for your host and keep the app running. Use Streamable HTTP where supported; `mcp_proxy.py` provides a Stdio bridge.

The public API has 11 tools. See the [MCP contract](docs/zh/agent/MCP_AUTHORING.md) (Chinese) for requests and limits. New generated artwork requires image generation in the host. [Recorded evaluations](STATUS.md) and the [roadmap](ROADMAP.md) distinguish observed results from future work.

## Build and contribute

Source builds require JDK 21. On Windows:

```powershell
.\gradlew.bat run
.\gradlew.bat run --args="--input examples/tml/psd-input/tml.psd --output build/example-output"
.\gradlew.bat test
```

Use `./gradlew` on Linux / macOS with the built-in renderer. See [development and CLI](docs/en/guide/DEVELOPMENT.md), [all documentation](docs/README.md) and [examples](examples/readme.md).

Issues and patches are welcome. Include your version, operating system, reproduction steps and logs; for agent evaluations, also record the host, model, retries and cost.

## License

Code is [GPL-3.0](LICENSE); see [third-party notices](THIRD_PARTY_NOTICES.md). Example artwork has separate usage notes. PSD2Live is independent of Live2D Inc. and does not distribute the official proprietary SDK. Check generated files in the target editor and runtime before delivery.
