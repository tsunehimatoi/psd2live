# PSD2Live User Guide

[中文](../zh/USER_GUIDE.md) | [日本語](../ja/USER_GUIDE.md)

PSD2Live provides both a modern desktop Graphical User Interface (GUI) and an automated Command Line Interface (CLI). This guide covers interface architecture, workflow, shortcuts, parameter tuning, and batch command usage.

---

## Table of Contents

- [Prerequisites](#prerequisites)
- [Launch Methods](#launch-methods)
- [Desktop GUI Overview](#desktop-gui-overview)
- [Workspace Views](#workspace-views)
  - [1. Hierarchy View](#1-hierarchy-view)
  - [2. Topology View](#2-topology-view)
  - [3. Preview View](#3-preview-view)
  - [4. Log View](#4-log-view)
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

The main window is structured into a three-pane layout: **Left Workspace + Right Inspector + Bottom Status Bar**.

- **Menu Bar**:
  - **File**: `Open PSD...` (`Ctrl + O`), `Reanalyze` (`Ctrl + R`), `Open Output Directory`, `Generate & Export` (`Ctrl + G`), `Export To...` (`Ctrl + Shift + G`), `Exit`.
  - **Language**: Instant switching between Simplified Chinese (`zh`), English (`en`), and Japanese (`ja`).
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

### 4. Log View
- **Diagnostic Stream**: Full chronological execution log with color-coded warning (yellow) and error (red) highlighting.
- **Copy Log**: Single click to copy all logs to the system clipboard.

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
3. **Export**: Set target directory and format toggles; click `Generate & Export` (`Ctrl+G`) to produce model files.

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


