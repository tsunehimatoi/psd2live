# PSD2Live User Guide

[中文](../zh/USER_GUIDE.md) | [日本語](../ja/USER_GUIDE.md)

PSD2Live provides a desktop GUI, an automated Command Line Interface (CLI), and a local MCP workspace for external AI hosts. This guide covers the four main workspaces, independent log dock, Agent connection and recovery, viewport interaction, parameter tuning, and export.

---

## Table of Contents

- [Prerequisites](#prerequisites)
- [Launch Methods](#launch-methods)
- [Desktop GUI Overview](#desktop-gui-overview)
- [Workspace Views](#workspace-views)
  - [1. Hierarchy View](#1-hierarchy-view)
  - [2. Topology View](#2-topology-view)
  - [3. Preview View](#3-preview-view)
  - [4. History View](#4-history-view)
- [Independent Log Dock](#independent-log-dock)
- [Connecting an AI Agent / MCP Host](#connecting-an-ai-agent--mcp-host)
- [Canvas Viewport & Shortcuts](#canvas-viewport--shortcuts)
- [Inspector Panels](#inspector-panels)
  - [Export Control Section](#export-control-section)
  - [Model Settings Section](#model-settings-section)
  - [Layers Table](#layers-table)
  - [Parameters Panel](#parameters-panel)
- [End-to-End Workflow](#end-to-end-workflow)
- [CLI Reference](#cli-reference)
- [Troubleshooting & FAQ](#troubleshooting--faq)

---

## Prerequisites

- **OS**: Windows 10/11 x64 (recommended for official Cubism 5-r.5 native rendering; Linux / macOS automatically fall back to CPU software rasterization).
- **Java Runtime**: JDK 21 or higher (JetBrains Runtime / OpenJDK 21+).
- **Input File**: Layered `.psd` file (8-bit RGB color mode, alpha channel transparency).

---

## Launch Methods

### 1. Windows Quick Launch
Double-click `run-gui.bat` in the repository root.

### 2. Gradle Command Line
```powershell
# Windows (PowerShell)
.\umamo\gradlew.bat -p .\psd2live run

# Linux / macOS (Bash)
../umamo/gradlew -p ./psd2live run
```

---

## Desktop GUI Overview

The main window is structured as **Left Workspace + Right Inspector + Independent Bottom Log Dock and Status Bar**.

- **Menu Bar**:
  - **File**: `Open PSD...` (`Ctrl + O`), `Reanalyze` (`Ctrl + R`), `Open Output Directory`, `Generate & Export` (`Ctrl + G`), `Export To...` (`Ctrl + Shift + G`), `Exit`.
  - **Language**: Instant switching between Simplified Chinese (`zh`), English (`en`), and Japanese (`ja`).
  - **Agent / MCP**: Open connection and skill setup, or jump directly to the version-history tree. The top-right status pill reopens the connection dialog.
  - **Help**: Version and license notices.
- **Split Pane Divider**: Drag to adjust the Workspace/Inspector ratio within `25% ~ 85%`.

---

## Workspace Views

### 1. Hierarchy View
- **Deformer & Drawable Tree**: Displays the full deformer hierarchy (`BodyXY` -> `BodyZ_Breath` -> `HeadRotation` -> `HeadContainer` -> `FaceNinePose` -> Feature Warps -> ArtMeshes).
- **VS Code Style Tree Guides**: Clean indentation guide lines with expand/collapse arrows.
- **Ear Tab Collapse**: Click the collapse button in the header to minimize the hierarchy tree into a compact side tab, maximizing canvas area.

### 2. Topology View
- **Mesh Wireframe Overlay**: Renders the adaptive Delaunay triangulation over the semi-transparent canvas.
- **Selection Highlight**: Selected ArtMeshes are highlighted in bold wireframe (2.2px).
- **Statistics Badge**: Displays active drawable count, total vertices, total triangles, and current zoom level.

### 3. Preview View
- **Native Cubism 5-r.5 Engine (Optional)**: On Windows x86-64, supports offscreen OpenGL rendering via JNA using the official Live2D Cubism Core & Framework library (`live2d_renderer.dll`) to achieve **100% faithful rendering and physical dynamics parity with the official runtime (Ground Truth)**.
  - *Note: To comply with Live2D's Proprietary License, this repository does NOT include or redistribute official SDK binaries. See [Live2D Cubism SDK Configuration Guide](CUBISM_SDK_SETUP.md) for setup instructions.*
- **Built-in Pure CPU Software Rasterizer**: Automatically active when the official SDK is absent or on non-Windows platforms (macOS / Linux); provides 100% out-of-the-box preview and slider inspection without any manual configuration.
- **Interactive Features**: Real-time mouse gaze tracking, 6-second breathing/blink idle loop, and blink-driven eye jelly dynamics.
- **Status Badge**: Bottom-left pill displays current zoom percentage, underlying engine type (`Native Cubism` or `Software Fallback`), and physics state.

### 4. History View
- **Append-only Branch Tree**: Shows immutable nodes created by the system, user, and Agent. A green `HEAD` badge marks the active workspace version; editing after restoring an older node creates a new branch without deleting the old future.
- **Navigation**: Drag the canvas to pan, use `-` / `+` to zoom or reset the view, and search summaries, node IDs, or actors.
- **Inspect and Restore**: Select a node to view its ID, parent, revision hash, actor, and timestamp. “Restore to this node” rebuilds its editable assets and rig without deleting any history node.

## Independent Log Dock

Logs remain visible below Hierarchy, Topology, Preview, and History instead of occupying a workspace tab:

- Click the header chevron to collapse or expand it; drag its top edge to resize it between `80` and `450 px`.
- Filter by All, System, Agent/MCP, or Images Only; search messages, tags, and details; and toggle auto-scroll. The header reports both log and image counts.
- Rendered Views and imported assets appear as inline thumbnails. Click one to open a larger checkerboard preview with dimensions, file size, and a Copy Image action.
- Clear affects only the current UI log. Copy Log copies the filtered text and does not modify history or task records.

## Connecting an AI Agent / MCP Host

<p align="center">
  <img src="../imgs/agent.png" alt="PSD2Live AI Agent asset generation, integration, and multi-parameter rendering workflow" />
  <br>
  <em>Inspect model Views, generate through the host-native image tool, import a layer, and verify multiple parameter poses</em>
</p>

At startup, PSD2Live exposes a bearer-authenticated Streamable HTTP MCP at `127.0.0.1:23871/mcp`. Keep the app running. Treat the token as local workspace write access; do not publish or commit it.

1. Open **Agent / MCP → Agent / MCP Connection & Prompts…**.
2. On Connection Config, copy the matching configuration and prefer native Streamable HTTP:
   - ChatGPT desktop / Codex: merge the HTTP TOML into `~/.codex/config.toml`, or `.codex/config.toml` in a trusted project, restart the client, and check `/mcp`.
   - Gemini / Antigravity: merge the `psd2live` HTTP JSON entry into `~/.gemini/config/mcp_config.json`, then refresh MCP Servers.
   - Other HTTP hosts: use the displayed endpoint with `Authorization: Bearer <token>`. Do not switch to the legacy `/sse` endpoint.
   - Stdio-only hosts: copy the Stdio JSON and run the repository-root `mcp_proxy.py` with Python 3. The bridge reads `PSD2LIVE_MCP_ENDPOINT` (defaulting to the address above), `PSD2LIVE_MCP_TOKEN`, and optional `PSD2LIVE_MCP_TIMEOUT`. On Windows, it can fall back to the credential saved by PSD2Live.
3. The Installation Prompt tab contains the complete setup prompt. For domain workflows, copy `.agent/skills/psd2live-rigging` and `.agent/skills/hair-separation` into the host's documented skill directory. List tools and call `project_get_state` first.

Current tools include:

| Capability | Tools |
| :--- | :--- |
| Project inspection | `project_get_state`, `project_list_layers`, `project_list_parameters`, `object_get` |
| Parameters and keyforms | `parameter_create`, `parameter_update`, `parameter_delete`, `keyform_set`, `keyform_copy`, `keyform_delete`, `rig_k_pose` |
| Model Views | `view_render_layer`, `view_render_context`, `view_render_model` |
| Transparent assets and layers | `asset_import_png`, `layer_add_from_asset`, `layer_soft_delete` |
| Recovery and long tasks | `history_list`, `history_checkout`, `task_start`, `task_update`, `task_get`, `task_list` |

Every project edit that advances `HEAD` must carry the current `expected_history_head_node_id`; use the returned node as the base for the next edit. Staging a PNG and appending task events do not move `HEAD`. After a timeout, disconnect, or expired session, the commit state may be unknown. Reconnect and inspect `project_get_state`, `history_list`, the task record, and affected objects before deciding whether to retry. The Stdio bridge automatically retries only safe read calls, never project edits.

PNG Views come from model data rather than UI screenshots and include reversible pixel-to-canvas mapping. `asset_import_png` preserves placement through `spatial_reference_id`; declare `source_pixel_rect` when returning only a crop. Aspect-ratio mismatches are rejected instead of stretched. Painted differences, part separation, occlusion completion, and pixel reconstruction require an actual host-native Nano Banana Pro/NBP, GPT Image 2 (`gpt-image-2`), or equivalent image-tool call. PSD2Live supplies Views and import only; do not draw substitutes with Python, PIL/OpenCV, SVG, or Canvas.

History, tasks, spatial references, and SHA-256-deduplicated RGBA assets are persistent. The default Windows store is `%LOCALAPPDATA%/PSD2Live/agent-workspaces`; override it with the JVM property `psd2live.agent.store`. Reloading a PSD with the same normalized path and file signature restores its last `HEAD`.

---

## Canvas Viewport & Shortcuts

| Action | Shortcut / Mouse Gesture | Description |
| :--- | :--- | :--- |
| **Zoom** | Mouse Wheel (`Scroll`) | Cursor-centered zoom (`0.05x` ~ `64.0x`) |
| **Pan** | Middle Click Drag / Left Click Blank Drag | Canvas translation |
| **Center & Fit** | `F` / `Home` / `0` | Resets camera to center and fit the entire model |
| **Hit Select** | Left Click on Mesh | Selects clicked layer with tree and table synchronization |
| **Open PSD** | `Ctrl + O` | File open dialog |
| **Reanalyze** | `Ctrl + R` | Reloads and re-evaluates current PSD |
| **Generate & Export** | `Ctrl + G` | Executes full pipeline export |
| **Export To...** | `Ctrl + Shift + G` | Selects target folder and exports |

---

## Inspector Panels

### Export Control Section
- Output directory path input and folder browse button.
- Toggle checkboxes: `Physics`, `CMO3`, `MOC3`.
- Primary action: `Generate & Export`.

### Model Settings Section
- **Atlas Size**: Presets (`1024`, `2048`, `4096`, `8192`, `16384`) + numeric spinner (`256 ~ 16384`).
- **Mesh Spacing**: Spacing slider (`16 ~ 128 px`) with `32`, `64`, `96` quick chips.
- **Head Strength**: Multiplier for 9-pose facial lattice and feature deformation (`0.0x ~ 4.0x`).
- **Body Strength**: Multiplier for torso kinematics and chest breathing (`0.0x ~ 4.0x`).
- **Advanced Settings**: Texture padding (`0 ~ 32 px`, default `2px`) and Alpha threshold (`0 ~ 255`, default `8`).

### Layers Table
- Summary statistics (Visible / Total, Recognized, Unknown).
- Batch actions: `Show All`, `Hide All`, `Invert`.
- Table columns: Eye icon toggle, Layer name & index, Semantic dropdown (31 tags), Side dropdown (`NONE`/`LEFT`/`RIGHT`).

### Parameters Panel
- Real-time search filter by parameter name or ID.
- Global controls: Animation Play/Pause, Mouse tracking toggle, Unlock All, Reset All.
- Parameter rows: Pin lock button, display name + ID, value slider, precision spinner, single reset button.

---

## End-to-End Workflow

1. **Import**: Drag and drop a layered `.psd` file into the application window.
2. **Review & Tune**: Confirm semantic assignments in the Layers Table; check mesh triangulation in Topology View; test mouse tracking in Preview View.
3. **Optional Agent Refinement**: Connect an MCP host and use model Views for parameter, keyform, or asset edits. Confirm the active `HEAD` in History; restoring an older node and editing from it creates a branch.
4. **Export**: Set target directory and format toggles; click `Generate & Export` (`Ctrl+G`) to produce model files.

---

## CLI Reference

```powershell
# Basic export
.\umamo\gradlew.bat -p .\psd2live run --args="--input D:/models/character.psd --output D:/dist/character"

# Advanced configuration
.\umamo\gradlew.bat -p .\psd2live run --args="--input D:/models/character.psd --output D:/dist/character --atlas 8192 --mesh-spacing 48 --head-strength 1.2 --lang en"
```

| Option | Type | Default | Description |
| :--- | :---: | :---: | :--- |
| `--input <path>` | Path | *(Required)* | Input PSD file path |
| `--output <path>` | Path | `PSD_DIR/psd2live-output` | Destination output directory |
| `--lang <zh\|en\|ja>` | String | System Locale | UI and log language (`zh` / `en` / `ja`) |
| `--atlas <size>` | Int | `4096` | Texture atlas square dimension (`256 ~ 16384`) |
| `--mesh-spacing <px>` | Int | `64` | Base mesh sampling spacing in pixels |
| `--head-strength <val>` | Float | `1.0` | 9-pose facial deformer strength multiplier |
| `--body-strength <val>` | Float | `1.0` | Body kinematics and breath strength multiplier |
| `--no-physics` | Flag | `false` | Disables `physics3.json` and CMO3 physics injection |
| `--no-cmo3` | Flag | `false` | Skips `.cmo3` project export |
| `--no-moc3` | Flag | `false` | Skips `.moc3` runtime export |
| `--help` / `-h` | Flag | - | Prints CLI help text |

---

## Troubleshooting & FAQ

- **Q: The Agent cannot connect, or reconnecting reports an expired session.**
  A: Keep PSD2Live running and recopy the current endpoint and token from the connection dialog. Native HTTP hosts must use `/mcp`, not `/sse`; Stdio hosts should run `mcp_proxy.py`. Reinitialize after session expiry and read `project_get_state` plus `history_list` before resuming mutations.
- **Q: Does restoring an old history node delete later edits?**
  A: No. Nodes are append-only and immutable. Restore moves only the workspace `HEAD`; editing from that node creates a new branch while the original future remains available.

- **Q: Java runtime version error on launch?**
  A: Ensure JDK 21+ is installed and `JAVA_HOME` is configured.
- **Q: Why are certain layers labeled as unknown?**
  A: Layers not matching known naming patterns are classified as unknown and assigned to head or body containers by spatial position. You can manually assign semantics in the Layers Table.
- **Q: Why does the eyelash distort or tear on blink closure?**
  A: Check if lower eyelashes or complete lower lid contours are included in the `eyelash` layer. The blink algorithm pulls the alpha-weighted centerline downward into a U-shape; merged lower lashes drag the centroid to the eyeball center and cause collision tearing. **Eyelashes must strictly be authored on the upper half of the eye**. Move lower lashes to `facedetail` or an independent static layer.
- **Q: Why does the mouth look blurred or muddy when closed?**
  A: The `mouth` layer must be drawn in a **fully open state**, and **crisp outline strokes on the lips are strongly recommended**. Upon closure, the mesh is centripetally compressed; clean outline strokes merge neatly into a sharp seam, whereas unbordered soft paint easily blurs or blends into adjacent skin.
- **Q: If the source artwork has a tilted head, is it snapped upright? How are rotation ranges computed?**
  A: The head is not forcibly straightened. The pipeline estimates the authored tilt (`initialAngleZ`) from facial baselines, aligning `DeformHeadRotation` to this tilt. The 9-pose facial lattice and `ParamAngleZ` ($\pm 30^\circ$) **calibrate their rotation limits around this initial angle as the neutral origin**. Keep initial head roll within natural limits ($\pm 25^\circ$).
- **Q: Are heavily reclining, sideways, or horizontal character poses supported?**
  A: **Excessive body tilt is not supported**. Torso kinematics (`ParamBodyAngleX/Y`) and chest breathing (`ParamBreath`) are formulated on a vertical canvas reference frame. Severe body tilts cause breathing to deform sideways and induce shear tearing under torso rotation. Keep character bodies predominantly upright.
- **Q: Why does the pupil stay visible when the eye closes?**
  A: Iris layers are clipped by eye-white (`eyewhite`). Verify that an `eyewhite` layer is present so eye closure shrinks the clipping mask.
- **Q: Exported `.cmo3` prompts about missing source art in Cubism Editor?**
  A: The base meshes are constructed from the generated atlas slices (`MissingSourceArt` is expected and normal). Keyforms, deformers, and parameters remain fully editable.
- **Q: Why does the Preview viewport show "Software Rasterizer"? How do I enable official runtime consistency verification?**
  A: PSD2Live strictly complies with open source licensing and Live2D's Proprietary License terms; **proprietary Live2D SDK binaries are NOT distributed with the source or releases**. Without the SDK, the application smoothly uses the built-in CPU software rasterizer (full model analysis, rigging, and `.cmo3`/`.moc3` exports are completely unaffected). If you want to enable official shader rendering to achieve **100% pixel-perfect parity with official game clients / Cubism Viewer (Ground Truth)**, please follow the [Live2D Cubism SDK Configuration Guide](CUBISM_SDK_SETUP.md).
