# User quick reference

[Documentation](../../README.md) · [中文](../../zh/guide/USER_GUIDE.md) · [日本語](../../ja/guide/USER_GUIDE.md)

Open **Help → Tutorials** first. Interactive lessons highlight the controls and show your configured shortcuts. This page is a short companion, in the same lesson order.

## Basic workflow

Import PSD (`Ctrl+Shift+O`) → inspect classifications and preview → edit → save (`Ctrl+S`) → export (`Ctrl+G`). Open an existing `.psd2live` project with `Ctrl+O`.

## The 14 lessons

| Lesson | Topic | Remember |
| --- | --- | --- |
| 1 | Basic workflow | Import layered artwork, inspect parts and settings, then choose export formats. |
| 2 | Workspace | Edit changes the model; Preview shows it; History restores versions. Each canvas tab has its own camera and overlays. |
| 3 | Hierarchy and mode bar | Select, search and reparent objects. Drop transparent artwork on the tree and confirm placement. Drawing order and parent deformation are different. |
| 4 | Layer types and variants | Presets apply part algorithms; toggle variants show/hide; exclusive variants share a parameter with different association IDs. |
| 5 | Parameters and keyforms | Drag sliders or XY controls; right-click a key marker to snap. Set the desired value before changing a shape. |
| 6 | Select mode | Select objects with the canvas tools or hierarchy; selection alone changes no geometry. |
| 7 | Create deformers | Select a target, use the tree context menu, adjust the placement preview and confirm. |
| 8 | Deform mode | Edit points or use brushes at the current parameter pose; check the L1 / L2 editing level. |
| 9 | Edit mode | Subdivide, connect, cut or remove mesh elements; inspect existing poses afterward. |
| 10 | Paint mode | Select a layer, paint pixels and use session-local undo. Apply or discard the session. |
| 11 | Inspector | Edit properties for the selected object: name, ownership, masks, drawing order, opacity and colors. |
| 12 | Tool details | Configure the current tool; canvas context menus also change with mode and tool. |
| 13 | Project and history | Save the project, restore a history node or branch from it. Hiding a branch does not delete it. |
| 14 | Texture upscaling | Configure the local backend, choose 2× / 4× and check edges, transparency and exports. |

## Important distinctions

- Saving preserves artwork, edits and history. Exporting delivers model files. `.psd2live.json` is only a report.
- Deform changes shapes; Edit changes mesh structure; Paint changes pixels in an isolated apply/discard session.
- Temporary solo visibility and static visibility are not parameter-driven variants. Use variants or opacity keyforms for animated switches.

## Default shortcuts

| Action | Keys |
| --- | --- |
| Open project / import PSD | `Ctrl+O` / `Ctrl+Shift+O` |
| Save / save as | `Ctrl+S` / `Ctrl+Shift+S` |
| Undo / redo | `Ctrl+Z` / `Ctrl+Y` or `Ctrl+Shift+Z` |
| Export model / re-export PSD | `Ctrl+G` / `Ctrl+Shift+E` |
| Zoom / pan | Wheel / middle drag or Space + left drag |
| Fit canvas | `F` / `Home` / `0` |

Settings can change these bindings. `F1` lists current shortcuts; the current tool explains confirmation and cancellation gestures.

## Troubleshooting and references

Check [layer preparation](../spec/PSD_LAYER_SPEC.md), side assignments, masks and parents before changing rig settings. Observe motion in the animation and physics panels; static poses do not establish dynamic behavior. For missing panels, check layout visibility or reset the layout. For missing artwork, fit the canvas and check visibility. Inspect logs and export reports for errors or conversion losses.

See [SDK setup](CUBISM_SDK_SETUP.md), [development and CLI](DEVELOPMENT.md), [project format](../spec/PROJECT_FORMAT.md), and the Chinese references for [canvas editing](../../zh/guide/CANVAS_EDITOR.md), [paths](../../zh/guide/DEFORM_PATHS.md), [upscaling](../../zh/guide/TEXTURE_UPSCALE.md) and [MCP](../../zh/agent/MCP_AUTHORING.md).

Maintained against the [tutorial catalog](../../../src/main/kotlin/io/github/psd2live/ui/tutorial/InteractiveTutorial.kt) and [shortcut registry](../../../src/main/kotlin/io/github/psd2live/ui/state/ShortcutRegistry.kt).
