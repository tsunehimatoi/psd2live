<!-- lang: zh en ko ja -->
> [中文](../../zh/guide/USER_GUIDE.md) · **English** · [한국어](../../ko/guide/USER_GUIDE.md) · [日本語](../../ja/guide/USER_GUIDE.md)

# Quick reference

[Documentation](../README.md) · [PSD layer specification](../spec/PSD_LAYER_SPEC.md) · [Canvas editing](CANVAS_EDITOR.md) · [Development and CLI](DEVELOPMENT.md)

> [!TIP]
> The first time you use it, press `F1` in the application and open the Help page, then start the interactive tutorial from **Production workflow** (19 lessons for beginners / 14 lessons for people with Cubism experience). This page is for looking up operations again and for shortcut lookup.

---

## 1. Shortest workflow

1. **Import**: `Ctrl+Shift+O` imports a layered PSD (an existing `.psd2live` project opens with `Ctrl+O`).
2. **Check**: look at the automatic-generation preview and the layer classification, and fine-tune on the canvas as needed.
3. **Save**: `Ctrl+S` saves a `.psd2live` project (`Ctrl+Shift+S` saves as).
4. **Export**: `Ctrl+G` exports the target model (`.moc3` / `.cmo3` and others).

> **Import an existing Cubism model**:
> - **File → New → New project from CMO3…**: clears the current model, then imports. Model content comes only from that CMO3.
> - **Model → Replace the current model with a CMO3…**: replaces, by ID, the objects that appear in the file, and adds new objects. Existing content that does not appear is kept.
> - Neither way generates presets automatically. Imported parameters, keyforms, deformers, textures, and physics can be saved with the project.

---

## 2. Save state and recovery

After you finish an edit that is being typed or dragged, press `Ctrl+S` to save. The status bar at the bottom shows the current save state:

| Status-bar text | Meaning and what to do |
| --- | --- |
| Editing a draft | Typing or dragging is still in progress, or a draft is being committed. Finish the current edit first |
| Changes committed to memory | The changes are in the current project, but the recovery copy has not been confirmed written yet. The project still needs to be saved |
| Saving the recovery copy… | Writing the local copy used to recover from an abnormal exit |
| Recovery copy saved · project not saved | The recovery copy is written. Press `Ctrl+S` to save the changes to the `.psd2live` file |
| Recovery save failed · retry | Click retry to write the recovery copy (hover to see the error). This does not add history |
| Saving the project… | The project file is being written. Wait for it to finish |
| Project save failed | Hover to see the error, then save again or save as after you handle it |
| Project saved | The current changes are saved to the `.psd2live` file |

> The recovery copy is only for recovery after an abnormal exit. When you need a backup, to hand the project to someone else, or to continue on another device, save the `.psd2live` project file. Operations for part sub-settings are in [Presets and modifiers](MODIFIERS.md#part-subsettings-and-physics).

---

## 3. Application pages and menus

The front of the workspace tab bar holds the application's own pages: **Start**, **Help**, and **Settings**. They are not saved with the project. Panels can be dragged to dock, merge, or float, and the layout is stored in local preferences. Reopen them from the **Window** menu, or **Reset layout**.

### Start page
It is shown automatically at launch (it collapses after you open or create a project, and you can reopen it from **File → Start page**). The catalog moves with `↑` / `↓`, and the main button is triggered with `Enter`:

- **Generate from PSD**: pick a preset on the right (Minimal / Default / Full), drop a PSD into the window, or click **Generate…**. Under it, **Recent PSDs** previews on a single click and generates on a double click. On the right you can control "Check disconnected meshes on import". When you create a new PSD project or import an image layer, disconnected meshes that can be split pop up a confirmation first. You can also scan manually from **Model → Check disconnected meshes in the whole model…**, or right-click the target mesh and check this layer under "Mesh".
- **Layout project**: the left side previews the canvas in proportion. On the right, choose a common size or enter a custom width and height. After creation you enter the place-first-then-generate flow (see [Materials and layout](LAYOUT.md)).
- **Import from CMO3**: create a new model or replace the current one.
- **Recent**: search and filter recent projects and PSDs as a grid or a list. Double-click to open.
- **What's new**: read the update notes by version, and jump straight to the matching interactive tutorial.

### Title-bar menu
- **File**: new, open, save, export, and open the containing folder (project / source file / output / application data).
- **View**: canvas, panel, and workspace layout controls.
- **Model**: operations that act on the whole model (reanalyze the PSD, replace with a CMO3, check disconnected meshes in the whole model, upscale textures, update generated results).
- **Help**: open the Help page and online resources (GitHub, documentation, the example repository, releases, and issue feedback).
- **Tools on the right**: sidebar toggle, language, light/dark theme, and **Settings** (the gear).

### Help page (`F1`)
Press `F1` again to return to the workspace. The catalog contains the **Manual**, **Tutorials**, examples, community links, third-party licenses, and About:
- The manual and tutorials are organized like Cubism Editor. Color labels mark **Same** / **Different operation** / **Unique** / **Not implemented** (gray, and it cannot be opened).
- "MCP and tools" at the end of the manual lists the matching MCP operations and fields (read from the service that is running).
- The layer specification is under **Manual → Artwork division / PSD authoring → Notes on creating a PSD**. Shortcuts are under **Manual → About editing → Shortcut list**.

### Settings page (`Ctrl+,`)
It contains scale and display, theme, language, display, graphics, hints, shortcuts, files and data, and MCP. Most changes take effect immediately (MCP needs **Apply and restart**):
- **Display**: window / maximized / full screen (`F11`).
- **Graphics and performance**: quality presets (Low / Medium / High / Ultra), render path (GPU / GPU window plus software canvas / fully software), GPU selection, vertical sync, anti-aliasing, texture filtering, and the performance overlay. When the canvas displays incorrectly, try "GPU window, software canvas" first.
- **Files and data**: view and clear the upscale cache, error logs, temporary leftovers from an abnormal exit, and the recent-file list.

---

## 4. Workspaces and the texture workspace

Click **+** on the right of the top tab bar to create a workspace. There are 8 presets and a blank layout:

![New workspace menu](../../imgs/workspace-presets.webp)

| Preset | Use |
| --- | --- |
| Edit | A single edit canvas, for deformers, keyforms, and the skeleton, with a full property panel |
| Mesh | Hierarchy tree, edit canvas, and mesh panel, focused on mesh topology |
| Rig | Edit canvas and live preview side by side, with the parameter panel always present |
| Animation | Motion list, preview canvas, and the animation editor |
| Preview | A large preview, keeping only the motion list |
| Physics | Preview canvas plus the parameter and physics panels, so you pull the model and adjust at the same time |
| Texture | Atlas page, edit canvas, and texture panel, for tile layout and texture density |
| History | Only the history tree and the operation list, for browsing and switching branches |
| Blank | An empty dock area. Add panels yourself from the **Window** menu |

### Texture workspace notes
Each layer has three sizes: the **canvas rectangle** (canvas units), its **own raster pixels**, and the **atlas tile pixels**.

- **Atlas view (left)**:
  - Zoom and pan match the edit canvas (wheel to zoom, middle button or `Space` + drag to pan, `F` to focus, `Home`/`0` to fit, `Ctrl+A` to select all).
  - Dragging a tile, scaling density from a corner, or rotating with the outer handle all enter an **adjust session** (preview only, and the model is not rebuilt). The session bar at the top can undo/redo/discard. Click **Apply** (or `Enter`) to commit it as one history node.
  - A tile that overlaps another tile's grid, or that extends past the page, is shown in red and cannot be applied.
  - The floating bar at the top left provides **Arrange** (by mesh shape / rectangle, all / selected), an **Auto arrange** (MaxRects) toggle, and the page number. The top right sets the budget (page size 1024–16384, page count, and spacing). The bottom right can turn on the **density heatmap** (blue < 1, green ≈ 1, orange > 1).
- **Texture panel (right)**:
  - Shows the three sizes "canvas → original image → atlas" and the actual density.
  - You can adjust **texture density** (`1/64`–`16`), **lock size**, **rotation**, **Replace image…** (optionally rebuild the mesh), and **Upscale textures…**.
- **Original-pixel preview**: after you turn on **Original pixels** in the display bar at the bottom right of the edit canvas, that canvas samples the layer's original raster directly, so you can compare sharpness after packing (export and the preview canvas always use atlas pixels).
- The same-named operations on the agent side are in [MCP usage and API](../agent/MCP_AUTHORING.md#textures-and-the-atlas).

---

## 5. Interactive tutorial catalog (20 topics)

The beginner route contains the following 19 lessons. The route for people with Cubism experience reads the terminology comparison first, then skips some of the introductory lessons.

| Lesson | Topic | What you do |
| --- | --- | --- |
| 1 | Basic workflow | Import a layered PSD, check the preview and the preset, then export |
| 2 | Workspace tour | Edit / preview / history pages working together. Each canvas saves its own camera and toggles |
| 3 | Hierarchy tree and the mode bar | Select, search, and change the parent. Drop a transparent image in to make it a layer |
| 4 | Variants | No variant / toggle variant (one parameter controls presence) / switch variant (pick one of several) |
| 5 | Materials and placement | Import materials and place them, describe the part with tags, then generate (see [Materials and layout](LAYOUT.md)) |
| 6 | Parameters and keyforms | Drag a slider or an XY control, and right-click to mark a snap. Set the parameter value first, then edit the keyform |
| 7 | Object mode | Box select, lasso, and locate with the tree and the canvas linked. It does not modify geometry |
| 8 | Create a deformer | Select the target → add a parent/child deformer → preview the placement → confirm |
| 9 | Deform mode | Adjust control points, transform as a whole, or use a brush. A mesh deformer uses `1`/`2` to switch vertex/Bezier |
| 10 | Edit mode | Change mesh topology (subdivide, connect, place points, delete), then recheck the pose |
| 11 | Paint mode | Brush, eraser, fill, and other pixel fixes. Undo is independent inside the session. Apply or discard |
| 12 | Inspector | Change the name, ownership, mask, draw order, opacity, and color |
| 13 | Tool options | The options bar at the bottom left adjusts radius, hardness, and strength. "Tool details" holds advanced settings |
| 14 | Skeleton binding and editing | Create/mirror bones, bind ArtMeshes, pose with FK/IK, paint weights, and bake for export |
| 15 | Animation and the motion editor | Adjust generated motions. The timeline edits parameter tracks, keyframes, and Bezier easing |
| 16 | Physics canvas debugging | Set inputs, the pendulum, and outputs, and calibrate the multiplier from the measured maximum |
| 17 | Cloth and hair simulation | Generate a simulation, paint pin/stiffness and other weights, and bake to parameters and pendulums for export |
| 18 | Project and history | Save `.psd2live`, and search/check out branches in the history tree. **Update generated results** merges a new version |
| 19 | Texture upscaling | Configure a local backend, choose 2× / 4×, and check edges and the export result |

---

## 6. Concepts that are easy to mix up

- **Save and export**: save (`.psd2live`) keeps the project and the branch history. Export produces the model the target software uses. `.psd2live.json` is only a diagnostic report. When you export a CMO3, motions are also saved as a `.can3` of the same name, and they need to be opened from the same directory and the same batch.
- **Keyform and keyframe**: a keyform belongs to parameter modeling, and the deformation is interpolated between parameter keys. A keyframe sits on the animation timeline and records a parameter value at one moment.
- **Deform and edit**: deform changes the shape. Edit changes the mesh topology. Paint changes pixels and goes through its own session.
- **Show/hide and variants**: temporary solo and static show/hide in the tree are for checking while you edit. To switch with a parameter, use a variant or an opacity keyform.
- **Depth split**: for example when a collar passes through the neck, right-click the collar and choose **Depth split…** (see the [depth-split tutorial](DEPTH_SPLIT.md)). Steps for hair and clothing simulation are in the [simulation tutorial](SIMULATION_TUTORIAL.md).

---

## 7. Generated results and upgrading old projects

- **Rig modules**: a new project is generated by part (eyes, brows, mouth, head, hair, body, clothing, expressions, motions). Each part is a base algorithm plus modules that can be switched independently. Under **Model preset › Parts and modules** you can adjust everything at once to Compact / Standard / Full, or to a parameter budget. Stylized facial features can be corrected on the canvas with the **Feature points** tool (`Shift+Y`) (see [Canvas editing](CANVAS_EDITOR.md#feature-points)).
- **Split records in old projects**: an old split is checkpointed as an ordinary mesh on the first edit, and existing edits are kept. A new split takes part in generation directly at the original layer's position, and the part can update together when you change the face or the skeleton.
- **Update generated results**: **Model → Update generated results** upgrades the parts you have not modified to the new generator result, and a three-way merge keeps manual edits. **Model → Preview generated-result update** previews the number of changed items first and does not modify the project.

---

## 8. Common shortcuts (Photoshop style by default)

In **Settings** you can switch to a Blender / Cubism preset or bind your own (modifier keys + wheel, and mouse side buttons, are supported).

| Action | Default keys |
| --- | --- |
| Open a project / import a PSD | `Ctrl+O` / `Ctrl+Shift+O` |
| Save / save as | `Ctrl+S` / `Ctrl+Shift+S` |
| Undo / redo | `Ctrl+Z` / `Ctrl+Shift+Z` or `Ctrl+Y` |
| Export the model / export PSD | `Ctrl+G` / `Ctrl+Shift+E` |
| Reanalyze the PSD / upscale textures | `Ctrl+R` / `Ctrl+U` |
| Help / settings / full screen | `F1` / `Ctrl+,` / `F11` |
| Zoom / pan the canvas | Wheel / middle button (or `Space` + left button) drag |
| Drag to zoom the canvas | `Ctrl+MouseMiddle` drag up and down |
| Adjust brush size · hardness / opacity · angle | `Alt+MouseRight` / `Shift+Alt+MouseRight` drag |
| Rotate the brush | `Alt+WheelUp` / `Alt+WheelDown` (add `Shift` to step 45°) |
| Fit the selection / reset the camera | `F` / `Home` or `0` |
| Brush size / hardness / opacity | `[` `]` / `Shift+[` `Shift+]` / `Alt+Shift+[` `Alt+Shift+]` |
| Item 1–5 of the mode or tool | `1`–`5` |
| Paint: selection / gradient / blur / free transform | `Alt+L` / `Alt+G` / `Alt+J` / `Alt+T` (in paint mode, `T` switches free transform) |
| Paint: select all / deselect / invert / fill / clear | `Ctrl+A` / `Ctrl+D` / `Ctrl+I` / `Alt+Backspace` / `Del` |
| Paint: swap · default colors / protect transparency / straight line | `X` / `Alt+D` · `/` · `Shift` + click |
| Temporary select / quick preview | Hold `Z` / backtick `` ` `` |
| Toggle object mode / the previous mode | `Tab` |
| Confirm / cancel the current operation | `Enter` / `Esc` |
| Timeline: play / set a keyframe / step a frame | `Space` / `K` / `Left` `Right` |

---

## 9. Checks and troubleshooting

| What you see | Check this first |
| --- | --- |
| A part's motion is wrong, or there is no motion | [PSD layer specification](../spec/PSD_LAYER_SPEC.md), and the type, side, mask, and parent in the Layers table |
| The rest pose is fine, and motion shows a poke-through | Play the full motion path in the animation or physics panel |
| A panel is missing | The panel toggles in the **Window** menu, or **Reset layout** |
| The model is not visible on the canvas | Fit the canvas first (`F` / `Home`), then check layer visibility |
| Export fails, or a downgrade is reported | The log panel, the export report `.psd2live.json`, and the export target version |

---

Related: [Canvas editing](CANVAS_EDITOR.md) · [Materials and layout](LAYOUT.md) · [Skeleton and poses](SKELETON.md) · [Physics](PHYSICS.md) · [Simulation and baking](SIMULATION.md) · [SDK setup](CUBISM_SDK_SETUP.md) · [MCP interface](../agent/MCP_AUTHORING.md) · [Development and CLI](DEVELOPMENT.md)

Maintained against: [InteractiveTutorial.kt](../../../src/main/kotlin/io/github/psd2live/ui/tutorial/InteractiveTutorial.kt) · [ShortcutRegistry.kt](../../../src/main/kotlin/io/github/psd2live/ui/state/ShortcutRegistry.kt) · [Messages_zh_CN.properties](../../../src/main/resources/i18n/Messages_zh_CN.properties)
