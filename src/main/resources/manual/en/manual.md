@@ new-features | Introducing new features
@@ new-feature-introduction-title | Release History
To see what an upgrade added, open **What's new** on the Start page: it lists new features, improvements and fixes by version, newest first. It ships with the program and can be read offline.
## Viewing the release notes
1. Open the Start page: **File → Start Page**, or click **Go to Start** on the canvas when no project is open.
2. In the "Learn" group of the list on the left, select **What's new**.
3. In the details panel on the right, click a version and the content jumps to it.
4. When an entry carries **Show me in a tutorial**, click it to open the interactive tutorial for that feature.
For the complete notes entry by entry, click **Full changelog** to open the releases page. For the rest of the Start page, see [[home]].

@@ before | Before you begin
@@ workflow | Production Flow
PSD2Live turns a layered PSD directly into a model that moves: after import, the meshes, deformers, parameters, idle and blink motions and physics are all generated in one go, and you refine shapes and add motions on top of that, then export for Cubism or another runtime. You can finish a model with or without opening Cubism.
## Choose a learning path
The interactive tutorials in the Help page highlight the relevant controls in your current workspace and use your own shortcuts in the prompts. If Live2D is new to you, start with this path, which goes from layered PSDs, parts and parameters all the way to export:
{path=BEGINNER}
If you have used Cubism, this path first maps ArtMesh, deformers and parameters onto PSD2Live, then focuses on automatic generation, canvas modes, skeletons, animation and physics:
{path=EXPERIENCED}
## From PSD to export
1. **Prepare the PSD**: put each moving part on its own layer and name layers after the part where you can (see [[precautions-for-psd-data]]).
2. **Generate**: on the Start page, pick a preset under **Generate from PSD** (Minimal / Default / Complete), then drop the PSD into the window or press {key:OPEN_PSD}. When generation finishes you land in the editing workspace (see [[psd-import]], [[model-presets]]).
3. **Check the recognition**: in the **Layers** panel look at each layer's tags (part, side) and variants, and fix anything misrecognized right there (see [[layers-palette]]).
4. **Preview**: choose **Preview** in the canvas mode menu and check that idle, blinking, mouse tracking and physics look natural (see [[preview-canvas]]).
5. **Edit as needed**: on the editing canvas use the Deform, Edit, Skeleton, Paint and other modes to fix keyforms, meshes and pixels; adjust sway and simulation in the Physics and Modifiers panels (see [[canvas-modes]], [[modifiers]]).
6. **Save**: {key:SAVE_PROJECT} saves a `.psd2live` file, which holds the source images, all edits and the branching history.
7. **Export**: {key:GENERATE} opens **Export Live2D model…**, which exports `.moc3` runtime files or a `.cmo3` project; other formats are under **File → Export as**.
You do not have to start from a PSD: a **Layout project** lets you place images, PSDs or SVGs on an empty canvas first and then generate (see [[layout-project]]), and **Import CMO3** lets you carry on editing an existing Cubism model (see [[cmo3-import]]).
## Edits survive regeneration
Every change on the canvas is recorded as a replayable edit. After you change layer tags or the model preset, the model is regenerated from the source images and your edits are applied again in their original order. Upgrading PSD2Live never quietly changes an existing project; to use a newer generator, run **Model → Update Generated Rig** (see [[update-generated-rig]]).
*> To keep refining in Cubism Editor, export a `.cmo3`. A model you changed in Cubism can be brought back into the project by ID with **Model → Replace the model with a CMO3…** (see [[modeling-menu]]).

@@ file-type-and-extension | File Types and Extensions
In daily use you only need to remember one file type: `.psd2live` is the project itself; the rest are either imported materials or exported deliverables.
## Project
| Extension | Description |
|---|---|
| `.psd2live` | Project. An unencrypted ZIP holding the source PSD or CMO3, materials, settings, all edits and the branching history; it does not depend on the original PSD's path |
| `.psd2live.json` | Report written alongside an export (can be turned off in the export window) that records losses and warnings; it is not a project file |
## Import
| Extension | Description |
|---|---|
| `.psd` | Layered PSD, the source for generating a model |
| `.cmo3` | Cubism Editor model project; can create a new project or replace the current model |
| `.png` `.webp` `.tiff` `.bmp` | Drag onto the hierarchy tree (into or beside the node the insertion mark shows), onto an edit canvas (placed where you let go), or use **Import Layer…** to add as a single layer; it is also added to the material library |
| `.svg` and other images | Import into a Layout project's material library |
## Export
| Extension | Description |
|---|---|
| `.cmo3` / `.can3` | Cubism Editor model project; when there are motions, a `.can3` (Animator project) with the same name is written too, in the same folder |
| `.moc3` `.model3.json` etc. | The Cubism runtime file family: model, settings, physics, motions, display info and textures, loaded from `.model3.json` |
| `.vtube.json` | Exported together with the `.moc3` file family, for VTube Studio to read |
| `.p2lrt` | PSD2Live runtime model, also used by the web player |
| `.p2lunity` | Model data exported for a Unity prefab |
| `.psd` | Layered PSD in a given pose, or the source PSD with generated layers |
| `.png` `.gif` `.apng` `.webp` `.mp4` `.webm` `.mov` | Image sequences, sprite sheets, animated images and video |
!> A `.cmo3` does not contain generator settings or edit history. To go back to PSD2Live and keep editing, keep the `.psd2live`.
For what each format contains and its limits, see [[export-moc3-motion3-files]] and [[exporting-images]].

@@ fault-map | What to Check When the Editor/Viewer Does Not Start Properly
When the display is wrong, the preview will not open or the program exits unexpectedly, check this table first; most cases can be fixed without reinstalling.
## Common cases
| Symptom | What to do |
|---|---|
| The editing canvas is black, flickers or is garbled | Turn on "Draw the editing canvas and the atlas page in software" under **Settings → Canvas Interaction**; the same place shows whether GPU or software rendering is in use. If the graphics card lacks multisampling or GPU drawing fails, the program switches to a compatible mode on its own |
| The preview canvas cannot use the Cubism SDK | If no usable native Cubism SDK library is present, switch **Preview runtime** on the preview canvas to the PSD2Live runtime; on Linux, pure Wayland without XWayland and musl systems fall back automatically |
| A panel has disappeared | Tick it again in the **Windows** menu at the right of the workspace tab bar, or choose **Reset layout** |
| The model is not visible on the canvas | Press {key:FRAME_VIEW} to frame the selection, or {key:RESET_CAMERA} to reset the camera, then check layer visibility |
| The program exited abnormally | At the next launch it asks whether to restore unsaved edits (see [[autosave]]) |
| Settings or UI state are corrupted | Click **Reset Defaults** at the bottom of the settings page's list; if that does not help, close the program and run it once with the `--clear-user-data` argument to clear settings and local data |
## Before reporting a problem
1. Open the **Log** panel, filter for errors by level and source, and click **Copy Log**.
2. **Settings → Files & data** shows the location of the **Error logs**; it can also clean up leftovers that an abnormal exit left in the system temp folder.
3. **About** in the Help page shows the version and runtime environment.
4. Submit it from **Help → On the web → Report an issue**, with the version, operating system, steps to reproduce and any related materials.

@@ performance | Performance When Working With Models in the SDK
How fast an exported model runs mainly depends on mesh vertex count, keyform count, texture size and masks. The generator produces most of these from its settings, so to control them you change the settings rather than deleting points object by object.
## Where to check and adjust
| Factor | Where |
|---|---|
| Mesh vertex count | The **Mesh** panel shows the vertex count and edge length of the selected layer, and you can change the global defaults or override per layer; the vertex count is also in the **Layers** info |
| Texture size and video memory | The Texture workspace: with no layer selected, the Texture panel lists each page's size, tile count, usage and video memory (RGBA); the atlas budget at the top right sets page size, maximum page count and spacing, and you can also tune the texture density per layer (see [[texture-atlas-edit]]) |
| Keyform count | When a simulation is baked into keyform axes, a mesh's keyform count is multiplied by the number of key points of the modes; choosing blend shapes (Cubism 4.2+) avoids this, and "Auto" switches to blend shapes when keyforms would exceed 64 (see [[simulation]]) |
| Target version | On export, features the chosen SDK target does not support are removed and the downgrades are listed (see [[target-version-selection]]) |
The export report `.psd2live.json` records downgrades and losses and is worth a look before delivery.
## Preview load in the editor
The preview canvas's **Frame rate cap** can limit the frame rate or follow the monitor. **Advanced mode** in the preview menu works only with the PSD2Live runtime and runs cloth and hair simulation live; turn it off first if the machine struggles.

@@ editor | About the Editor
@@ glossary | Live2D Glossary
PSD2Live mostly uses Live2D's common terms; a few are named differently in the interface, and a few are new concepts that come with automatic generation. When you meet an unfamiliar word, look it up here first.
## Name comparison
| Cubism | PSD2Live | Description |
|---|---|---|
| ArtMesh | Art mesh / layer | A PSD layer becomes an art mesh with a mesh on import |
| Warp Deformer | Mesh deformer / Warp | Deforms children with a grid of control points |
| Rotation Deformer | Rotation deformer / Rotation | Rotates children around an origin |
| Parameter | Parameter | A value that drives deformation and variants |
| Key | Parameter key | A position on a parameter where a shape is recorded; shown as a round dot in the Parameter panel |
| Form | Keyform | The shape recorded at a key; interpolated between keys |
| Blend shape | Blend shape key | Shown as a square dot in the Parameter panel; adds a shape difference on top of the base shape |
| Glue | Glue | Points shared by two meshes |
| Parts / Deformer palette | Hierarchy | The parent-child structure of deformers and art meshes |
| Model project (.cmo3) | Project (.psd2live) | `.cmo3` is only an import/export format |
{tutorial=LIVE2D_BRIDGE}
## Concepts that come with automatic generation
| Term | Meaning |
|---|---|
| Model presets | Control the motions, physics and simulation that are generated; deformation amounts and mouth shapes are on the role component cards (see [[model-presets]], [[modifiers]]) |
| Tags | A layer's part, side and so on; the generator uses them to recognize parts |
| Variants | None, Toggle Variant (one parameter shows or hides a layer), Switch Variant (one parameter picks one of several) (see [[variants]]) |
| Generated rig | The model generated automatically from the source images and presets; your edits are replayed on top of it |
| Canvas modes | Object, Deform, Edit, Simulate, Skeleton, Paint, Preview (see [[canvas-modes]]) |
| Workspace | The tabs at the top, each with its own canvas and panel layout (see [[workspace]]) |
| Deform path | A tool that deforms a mesh along a curve; baked into mesh keyforms on export |
| Skeleton / Simulation | A bone skeleton and cloth/hair simulation, baked into parameters, keyforms and pendulums on export |
| Baking | Converting features Cubism does not have into native deformers, keyforms or pendulums |
| Texture atlas | The texture pages that pack each layer's pixels; texture density can be set per layer |
| Reference layer | A layer shown on the canvas that takes no part in generation or export |

@@ shortcut | List of shortcut keys
The table below lists every shortcut and your current binding. The default is Photoshop style; under **Settings → Shortcuts** the "Preset" can be switched to Blender style or Live2D Cubism style, and you can also rebind individual keys. The Cubism style adds the commonly used Cubism Editor keys to the Photoshop layout, so it feels closer when you come from Cubism.
## Rebinding keys
1. Press {key:OPEN_SETTINGS} to open Settings and choose **Shortcuts**.
2. Click a key cell and press the new combination; scrolling the wheel over the cell or pressing a mouse button also records. {kbd:Esc} cancels recording.
3. A conflict with another action is reported; one action can have extra alternate keys, and "Reset this shortcut" restores a single entry.
!> Switching presets overwrites all custom keys.
The keys shown in this manual and in the interactive tutorials follow your current bindings.

@@ home | Home
The Start page is the first page you see after launching the program: creating and opening projects and reading updates all start here. Opening or creating a project switches to the matching workspace automatically; to come back, use **File → Start Page**, or click **Go to Start** on an empty canvas.
## What you can do
| Group | Item | Purpose |
|---|---|---|
| Open | Open project… | Opens a `.psd2live` ({key:OPEN_PROJECT}) |
| Open | Recent | Lists recent projects and PSDs as a grid or list, with filtering and search; double-click to open |
| New | Generate from PSD | Choose a preset (Minimal / Default / Complete) in the details panel and adjust as needed, then drop a PSD into the window or click **Generate…**; below it, **Recent PSDs**: single-click to preview, double-click to generate |
| New | Layout project | Choose a common size or enter a width and height, then click **Create Layout Project** |
| New | Import CMO3 | Creates a new project from a `.cmo3`; when a model is already open you can also **Replace with a CMO3…** |
| Learn | What's new | Release notes for each version (see [[new-feature-introduction-title]]) |
| Learn | Quick start, Help, Settings | Opens the interactive tutorial list, the Help page ({key:OPEN_HELP}) or the Settings page ({key:OPEN_SETTINGS}) |
*> The preset chosen here is remembered: PSDs imported later from the menu, recent files or by drag and drop are all generated with it.
The list is on the left, the content in the middle, and the details panel on the right explains what the current action will do; the action bar at the bottom holds the main button, which {kbd:Enter} also triggers. These panels can be dragged, merged or closed like the editor's panels, and **Windows → Reset layout** restores them.

@@ target-version-selection | Select Target Version
The target version decides which Cubism features the exported `.moc3` / `.cmo3` can use. In PSD2Live it is an export option: while editing you can use every feature freely, and when exporting to an older version the program downgrades for you, so one project can be exported for different versions.
## Choose a target
1. Press {key:GENERATE} to open **Export Live2D model…**.
2. In the **SDK target** drop-down choose Cubism 3.0, 3.3, 4.0, 4.2, 5.0 or 5.3 (default 5.0).
3. If the model uses features the target does not support, the window shows "Used features downgraded on export:" and lists each one by format, feature and object.
## Common downgrades
| Feature | Description |
|---|---|
| Quad warp method | Earlier targets do not support the new warp method |
| Inverted mask | Needs a newer target |
| Blend shapes | Blend-shape features are graded by version |
| Multiply color / screen color | Not supported by earlier targets |
| Extended blend modes, part offscreen compositing | Need a newer target |
The target version also affects generation: for example, when the target does not support blend shapes, simulation modes are written as keyform axes.

@@ environment-settings | Environment settings
The Settings page stores machine-level preferences unrelated to any project: interface size, theme, language, shortcuts and so on. Open it with {key:OPEN_SETTINGS} or the gear at the right of the title bar; changes take effect immediately.
## Settings
| Item | Contents |
|---|---|
| Scale and display | UI scale and font size; one click adopts the scale recommended for the monitor, and the current monitor's resolution and system scale are shown |
| Theme | Choose a color theme, or duplicate one and adjust the colors yourself |
| Language | 中文, English, 日本語, 한국어 |
| Canvas Interaction | Whether the editing canvas and atlas page render in software, and the rendering method currently in use |
| Prompts | The list of windows for which "Don't show again" was ticked; re-enable them one by one or **Show all prompts again** |
| Shortcuts | Presets and per-key rebinding (see [[shortcut]]) |
| Files & data | Recent files, the last export folder, and the locations and sizes of the install, workspace storage, downloaded runtimes, upscale cache, error logs and so on |
| MCP | Status and settings of the local MCP service; after changing the port, token and so on, click **Apply and restart** |
**Reset Defaults** at the bottom of the list resets all settings. Settings, layouts and shortcuts are stored on this machine and do not travel with the project.

@@ menus | Menu
@@ file-menu | File menu
The **File** menu manages projects coming and going: new, open, save, and all export formats.
## Menu items
| Item | Purpose |
|---|---|
| Start Page | Opens the Start page (see [[home]]) |
| Open project | Opens a `.psd2live` ({key:OPEN_PROJECT}) |
| Open recent | Recent projects and PSDs; **Clear Recent Files** at the bottom |
| Save project / Save project as | {key:SAVE_PROJECT} / {key:SAVE_PROJECT_AS} |
| New → New project from PSD… | Generates with the preset chosen on the Start page ({key:OPEN_PSD}) |
| New → New project from CMO3… | Imports a Cubism model as a new project (see [[cmo3-import]]) |
| New → New Layout project… | Creates a Layout project (see [[layout-project]]) |
| Export Live2D model… | Exports `.moc3` / `.cmo3` ({key:GENERATE}) |
| Export as | Runtimes and players (VTube Studio, PSD2Live runtime model, web player, Unity prefab), images and video, layered PSD, and **Source PSD…** (with generated layers) ({key:REEXPORT_PSD}) |
| Open folder | The folders of the project, source files and output, plus workspace storage and app data |
| Exit | Closes the program |
Importing is generating: a new project from a PSD gives you a model that moves right away, and motions are exported along with the model, so there is no separate animation project to open.
{tutorial=BASIC}

@@ display-menu | Show menu
The **View** menu in the title bar manages workspace tabs and the scale of the whole interface. The display toggles on the canvas, such as mesh wireframe and deformers, are not here but on each canvas itself.
## Menu items
| Group | Item | Shortcut |
|---|---|---|
| Workspace | New workspace | {key:NEW_EDIT_TAB} |
| Workspace | Add Preview Canvas | {key:NEW_PREVIEW_TAB} |
| Workspace | Show History | {key:OPEN_HISTORY_TAB} |
| Workspace | Duplicate workspace / Close workspace | {key:DUPLICATE_TAB} / {key:CLOSE_TAB} |
| Workspace | Next workspace / Previous workspace | {key:NEXT_TAB} / {key:PREV_TAB} |
| Interface and scale | Zoom in UI / Zoom out UI / Reset zoom | {key:ZOOM_IN} / {key:ZOOM_OUT} / {key:ZOOM_RESET} |
## Display toggles on the canvas
Every display toggle - texture, mesh wireframe, deformers, rotation deformers, deform paths, skeleton, texture density, source pixels, reference images, grid, guides and so on - is under "View ▾" in the canvas title bar; the common ones are also in the display rail at the lower right of the canvas, and the pin at the right of each item in "View ▾" adds it to the rail or takes it off. The rail lists only the toggles the model can use: with no deform paths in the model there is no "Deform paths" row, for instance. The background (checkerboard / solid / transparent) is under "Background ▾" in the canvas title bar. The toggles are stored per canvas, so two canvases in the same workspace can show wireframe on one and the finished result on the other (see [[about-viewarea]]).

@@ modeling-menu | Modeling Menu
The **Model** menu in the title bar holds commands that act on the whole model. Modeling for a single object (creating deformers, editing meshes, making keyforms) is done on the canvas; see [[canvas-modes]].
## Menu items
| Item | Purpose |
|---|---|
| Reanalyze PSD | Re-reads the source PSD with the current settings and generates ({key:REANALYZE}) |
| Replace the model with a CMO3… | Replaces objects in the current model with those in the CMO3 by ID, adds new objects, and keeps what the file does not have |
| Split Layers by Mesh… | Splits parts drawn on one layer but separate from each other (such as the left and right legs) into independent layers |
| Texture Upscale… | Upscales layer pixels 2× / 4× ({key:TEXTURE_UPSCALE}, see [[texture-upscale]]) |
| Update Generated Rig | Switches to the result of the current generator version and merges your edits (see [[update-generated-rig]]) |
| Preview Generated Rig Update | Shows only what an update would change; the project stays as it is |
*> After upgrading the program, first use **Preview Generated Rig Update** to see the difference, and run **Update Generated Rig** when you are satisfied.

@@ window-menu | Window Menu
Panels that were closed, or an extra canvas you want, are handled in the **Windows** menu at the right of the workspace tab bar. When the Start, Help or Settings page is open, the menu lists only that page's panels.
## Menu items
| Item | Purpose |
|---|---|
| Panel list | Hierarchy, Skeleton, History, Log, Animation Editor, Layers, Parameters, Tool Details, Mesh, Inspector, Animation, Physics, Modifiers, Atlas, Texture, Materials; a tick means shown in the current workspace |
| Canvas | This workspace's canvases, which can be shown or hidden one by one |
| Add Edit Canvas / Add Preview Canvas | Adds a canvas to this workspace (see [[multiview]]) |
| Reset layout | Restores the current workspace's preset layout without affecting the model |
Each workspace has its own set of panels, and **Reset layout** acts only on the current workspace too (see [[workspace]]). If you only want more room, use the sidebar buttons at the upper right of the title bar to collapse all the panels on one side, and click again to restore them.

@@ help-menu | Help Menu
The **Help** menu in the title bar opens the built-in Help page and the online resources.
## Menu items
| Item | Purpose |
|---|---|
| Help | Opens the Help page ({key:OPEN_HELP}; press again to return to the workspace) |
| On the web → GitHub repository | The source repository |
| On the web → Online documentation | Online documentation |
| On the web → Sample repository | Sample PSDs and projects |
| On the web → Releases | Download each version |
| On the web → Report an issue | Submit a problem (see [[fault-map]]) |
## The Help page
The Help page contains the **Manual**, **Tutorials**, **Examples**, **Community & Links**, **Third-party licenses** and **About**; everything is inside the program and readable offline. The manual follows the contents of the Cubism Editor manual, and each page carries a tag saying whether it is the same as Cubism, works differently, is unique to this program or is not implemented yet, so coming from Cubism you can look things up in the chapters you know. On a tutorial card in a page, click **Start** to be walked through it step by step in your current workspace.

@@ mesh-edit-menu | Mesh Edit mode menu
Every layer already gets a mesh automatically when generated, so most of the time you will not touch meshes; when you need to add density at joints or cut apart parts that are joined, switch the canvas to **Edit** mode, where all mesh operations are available, in the canvas right-click "Mesh" submenu and in the **Mesh** panel.
## Where the operations are
| Operation | Where |
|---|---|
| Subdivide, mesh points, delete vertices | Edit mode toolbar and right-click menu: subdivide {key:TOOL_SUBDIVIDE}, mesh points {key:TOOL_MESH_POINT} (Line {key:TOOL_KNIFE}), delete {key:DELETE_SELECTION} |
| Point / edge / face selection | The Edit mode bar ({key:PICK_OPTION_1}–{key:PICK_OPTION_3}) |
| Rebuild from current pixels | The right-click "Mesh" submenu, or the Mesh panel |
| Split disconnected meshes… | The right-click "Mesh" submenu, or right-click in the hierarchy tree |
| Front/back layers | The right-click "Mesh" submenu (when available) |
| Auto mesh outline, point density, topology | **Mesh** panel: change the global defaults or override per layer |
{tutorial=EDIT_MODE}
When you change the topology, existing keyforms, blend shapes and glue references migrate to the new mesh together, so nothing has to be redone (see [[mesh-edit-manual]]).

@@ workspace | Workspace
A workspace is a tab at the top of the interface. Each tab has its own canvas and panel layout, but they all edit the same project. Rigging, tuning physics and arranging animation each need different panels, so instead of rearranging panels again and again, switch workspaces by task.
## Create a workspace
Click the **+** at the right of the tab bar and choose a preset; the layout and its purpose are previewed on the right.
| Preset | Good for |
|---|---|
| Edit | One editing canvas for deformers, keyforms and skeleton, with the full set of property panels |
| Mesh | Hierarchy, editing canvas and Mesh panel, for changing mesh topology |
| Rig | Editing canvas beside a live preview, with the Parameter panel always shown |
| Animation | Motion list, preview canvas and Animation Editor |
| Preview | A large preview with only the motion list |
| Physics | Preview canvas plus the Parameter and Physics panels, for tuning while you pull the model |
| Texture | Atlas page, editing canvas and Texture panel |
| Layout | Material browser, an Object-mode editing canvas, the hierarchy tree and the layer table, for placing first and then generating |
| History | Only the history tree and the operations list |
| Blank | An empty dock area; add panels as needed from the **Windows** menu |
## Switching and arranging
- Click a tab to switch, drag to reorder, middle-click to close; {key:JUMP_TAB_1} and so on jump straight to the workspace with that number, and {key:NEXT_TAB} / {key:PREV_TAB} step through them.
- Right-click a tab to **Rename**, **Duplicate**, **Close**, **Close others** or **Close to the right**.
- Panel grouping, split ratios, floating state, canvas mode and view options are all stored in their own workspace; **Reset layout** restores only the current one.
{tutorial=WORKSPACE}
For dragging, splitting and floating panels, see [[palatte-basic-operation]].

@@ view-area | View area
@@ about-viewarea | About the View Area
The canvas is where you view and edit the model. PSD2Live has editing canvases and preview canvases; one workspace can hold several, and each remembers its own camera and display settings.
## What is on the canvas
| Position | Contents |
|---|---|
| Upper left | The canvas mode menu and the mode bar (confirm, cancel and so on), with the toolbar of the current mode below (see [[canvas-modes]], [[toolbar]]) |
| Lower left | The tool options bar: the current tool's radius, strength and other values |
| Lower right | The display bar: toggles for texture, mesh wireframe, deformers, rotation deformers, deform paths, skeleton, texture density, source pixels, reference images and so on; hover to expand |
| Title bar | "View ▾": the canvas background (checkerboard / solid / transparent) and more, plus resetting this canvas's view options |
## Moving the view
| Operation | How |
|---|---|
| Zoom | Mouse wheel; or hold {key:ZOOM_DRAG} and drag up and down |
| Pan | Hold {key:PAN_VIEW} and drag, or {kbd:Space} + left-drag |
| Frame the selected objects | {key:FRAME_VIEW} |
| Reset the camera | {key:RESET_CAMERA} |
| Temporary preview | {key:QUICK_PREVIEW} switches quickly between editing and preview |
{tutorial=WORKSPACE}

@@ canvas-modes | Canvas modes
The canvas mode decides what you change on the canvas: shapes, meshes, skeleton or pixels. The mode menu at the upper left of each canvas lists all the modes; with the menu open, press 1–7 to pick directly; after switching, the toolbar on the left changes to that mode's tools.
## Modes at a glance
| Mode | What it changes | Shortcut |
|---|---|---|
| Object | Select objects; create Warp, rotation deformers and deform paths | {key:MODE_SELECT} |
| Deform | The base shape, or the keyform at the current parameter key | {key:MODE_DEFORM} |
| Edit | Mesh topology, path binding and Glue | {key:MODE_EDIT} |
| Simulate | The vertex weight groups read by cloth and hair simulation | {key:MODE_SIMULATE} |
| Skeleton | The pose and structure of the bone skeleton | {key:MODE_SKELETON} |
| Paint | Layer pixels | {key:MODE_PAINT} |
| Preview | Changes nothing; plays and checks | {key:MODE_PREVIEW} |
Deform changes shape and Edit changes mesh structure; this is the pair most easily confused. Painting changes pixels, runs in its own session that can be undone independently, and is applied or discarded on the mode bar at the end.
{tutorial=HIERARCHY}
## Selecting in Object mode
1. Choose **Object** in the mode menu, or press {key:TOOL_SELECT} to activate the click-select tool; switch to lasso {key:TOOL_LASSO_SELECT} or brush select {key:TOOL_BRUSH_SELECT} when needed.
2. Click to select; {kbd:Shift} adds, {kbd:Alt} subtracts, {kbd:Ctrl} + click cycles the hierarchy level among overlapping hit objects, drag for a box selection, {key:SELECT_ALL} selects all.
3. The selection stays in sync with the hierarchy tree; press {key:FRAME_VIEW} to bring the selected objects to the center of the view.
4. After choosing a target, switch to the mode you need. With no target selected, the mode tells you to select one first.
In other modes, hold {key:TEMPORARY_SELECT} to switch to selection temporarily and release to return. {key:TOGGLE_OBJECT_MODE} switches between Object mode and the mode last used (Deform by default). Multi-step operations such as placing or drawing a mesh-points line are finished with "Confirm" on the mode bar or {kbd:Enter}, and cancelled with {key:CANCEL}.
{tutorial=SELECT_MODE}
*> Simulate, Skeleton and Paint are modes Cubism does not have; their results are baked into parameters, keyforms and pendulums on export, or written straight into the textures.

@@ preview-canvas | Preview canvas
The preview canvas plays the exported model so you can check motions, physics and simulation; it never modifies the model. It uses the same compile as the `.moc3` export, so what you see here is what you deliver.
## Opening a preview
- Choose **Preview** in the canvas mode menu ({key:MODE_PREVIEW}); choose another mode to return to editing.
- **View → Add Preview Canvas** ({key:NEW_PREVIEW_TAB}), or **Windows → Add Preview Canvas**.
- Switch to a workspace that has its own preview canvas, such as Preview, Animation or Physics.
## Preview options
| Item | Purpose |
|---|---|
| Idle animation | Plays or stops the idle motion |
| Mouse tracking | Head and gaze follow the mouse; "Smooth + body Y" can be turned on |
| Physics | Turns physics on or off |
| Preview runtime | Cubism SDK (the exported moc3, played in the official Cubism 5 runtime) or the PSD2Live runtime (the exported .p2lrt) |
| Advanced mode | PSD2Live runtime only: arc skinning, exact links, live cloth and hair simulation |
| Frame rate cap | Follow the monitor or a fixed FPS |
The bottom of the canvas shows the current runtime, physics state and zoom ratio; a hint appears when a simulation has not been baked yet.
!> The reference simulation runs only in the editor and is not exported; to carry it into the exported files, bake it in the simulation's card or on the **World** page of the Modifiers panel.

@@ guide-function | Guide line function
Guides are horizontal or vertical reference lines dragged out of the rulers, for lining parts up, checking left-right symmetry or marking where a keyform should reach. Guides are saved with the project, stay out of the history and are never exported.
## Showing the rulers and guides
- Click **Guides** in the display rail at the lower right of the canvas ({key:TOGGLE_GUIDES}); rulers appear along the top and left edges, in canvas pixels.
- Each edit canvas decides whether it shows them; the guides themselves belong to the project, and every edit canvas shares one set.
## Operations
| Operation | How |
|---|---|
| Add | Drag down from the top ruler for a horizontal guide, right from the left ruler for a vertical one |
| Move | With a selection tool or **Transform**, drag a guide when the pointer over it turns into a double arrow. Brushes, creation tools, Paint mode and Shift or Alt presses keep working near a guide, and a handle under the pointer wins over it |
| Remove | Drag the guide back onto its ruler, or choose **Clear all guides** in **Canvas aids…** of the display rail; **Restore cleared guides** in the same menu brings the last cleared set back |
| Lock | Turn on **Lock guides** in **Canvas aids…** ({key:LOCK_GUIDES}); locked guides are dashed and can be neither added nor dragged |
| Snap | With **Snap to grid and guides** on ({key:TOGGLE_SNAP}), a point you drag lines up with a guide it comes near |
The guides' colour is picked among the theme colours in **Canvas aids…**, so they stay visible in any theme.
*> Unlike Cubism, locking and colour are switched in the display rail's settings menu rather than a guide dialog; with snapping and the grid on, a dragged guide also snaps to the grid lines.

@@ solo | Solo function
Solo shows only the selected layer and temporarily hides the others, which makes it easy to check parts covered by hair or clothing.
## How to use
1. Right-click a layer in the hierarchy tree.
2. In the "Show" group choose **Solo this layer**; a `SOLO` mark appears beside the menu item.
3. When done, right-click again and choose **Exit solo**; the other layers return to the visibility they had before solo.
Solo affects only the current canvas; other canvases in the same workspace display as usual.
*> Solo is only a temporary show/hide while editing. To show or hide a part with a parameter, set up a variant or an opacity keyform (see [[variants]]).

@@ grid | Grid Function
The grid draws evenly spaced reference lines over the document, for lining up mesh points, deformers and parts. It shows on edit canvases only and changes neither the model nor the export.
## Showing the grid
Click **Grid** in the display rail at the lower right of the canvas ({key:TOGGLE_GRID}).
## Settings
In **Canvas aids…** of the display rail:
| Item | Effect |
|---|---|
| Spacing | Canvas pixels between major grid lines (2–1024) |
| Divisions | Smaller cells within each cell; 1 draws major lines only |
| Opacity, colour | How strong the lines are and their colour, picked among the theme colours |
Zoomed out, divisions that would crowd together are hidden and the major lines spread out as needed, so the grid never turns into a wash.
## Snapping
With **Snap to grid and guides** on ({key:TOGGLE_SNAP}), a mesh or deformer point you drag lands on a grid line once it comes within about 6 pixels of one. The point you grabbed is what snaps, not the pointer, so the point itself sits on the line, and the rest of the selection moves by the same amount. Clicks and picking use the pointer as it is. It snaps to the grid lines visible at the current zoom. Paint and the deform brushes do not snap, so strokes are not pulled onto the lines.

@@ snapshot | Snapshot Function
@@ onion-skin | Onion Skin
The onion skin lays the shape at neighbouring keys or frames translucently over the current view: while shaping you see the keys either side, while animating the path through the neighbouring frames. It shows on edit canvases only, is not written to the project and is never exported.
## Showing the onion skin
Click **Onion skin** at the right end of the animation panel's toolbar ({key:TOGGLE_ONION_SKIN}). It acts on the current edit canvas; while the current canvas is a preview canvas, on the first edit canvas. What it shows depends on the moment:
| State | Shapes laid over |
|---|---|
| A motion is open in the animation panel | Frames around the playhead, every "Frame step" frames |
| No motion is open | The keys around the current value of the parameter last set in the parameters panel: the selected object's keys on it when it has any, else the whole model's |
The onion skin button's tooltip says which of the two it is showing.
## Settings
Click the arrow beside the onion skin to set how many before and after (0–5), the frame step, the opacity, and a colour each for before and after. Layers farther from the current pose are fainter.
*> The onion skin follows the canvas's display filters (selected only, layer visibility); it is not shown while a parameter snapshot is previewed on hover or in Paint mode.

@@ multiview | Multiview
One workspace can hold several canvases: for example an editing canvas and a preview canvas side by side, so you see the finished result while editing keyforms; or two editing canvases, one zoomed in on detail and one showing the full body.
## Adding a canvas
1. Open the **Windows** menu at the right of the workspace tab bar.
2. Choose **Add Edit Canvas** or **Add Preview Canvas**; the new canvas docks into the workspace, and dragging its title moves it.
3. When no longer needed, untick it in the "Canvas" group of the **Windows** menu, or simply close it. A workspace keeps at least one canvas.
## What is independent and what is shared
| Independent per canvas | Shared |
|---|---|
| Selection, tool, camera, display settings, canvas mode | Parameter pose, parameter locks and playback controls |
So when you drag a parameter on the editing canvas, the preview canvas shows the same pose.
*> To compare two different parameter poses side by side, put them in two workspaces: each workspace keeps its own pose.

@@ random-pose | Random Pose
The random pose sets parameters to random values in their ranges, to check many parameter combinations at once and find clipping, tearing or runaway deformation.
## How to use
1. Click the die, **Random pose**, in the parameters panel's toolbar ({key:RANDOM_POSE}); each click gives a new pose.
2. When done, click **Restore pose** in the arrow menu beside the die ({key:RESTORE_RANDOM_POSE}); the parameters return to the values they had before the first random pose.
## Choosing the parameters
In the arrow menu beside the die:
| Item | Effect |
|---|---|
| All unlocked parameters | Every parameter takes part; parameters locked in the parameters panel stay put |
| Parameters being edited | Only the parameters last set in the parameters panel; when none is set on this canvas, as on a preview canvas, all unlocked parameters move |
| Range | How far each parameter may go from its default; 100% reaches both ends of its range |
The random pose sets the current pose the way dragging sliders does, except that it never keys an open motion.

@@ toolbar-folder | Toolbar
@@ toolbar | About the Toolbar
The toolbar sits at the upper left of each canvas, below the mode menu, and lists only the tools available in the current mode. Tools of the same kind share one slot that shows the one used last; click the slot again or right-click it to expand the rest, and hovering shows the name and shortcut.
## Tools in each mode
| Mode | Tools |
|---|---|
| Object | Select, Transform, lasso select; Create Warp, Create Rotation, deform path |
| Deform | Select, Transform, lasso select, brush select; push/pull brush, smooth brush, inflate/deflate |
| Edit | The Deform mode tools, plus mesh points, subdivide and Glue (available when two meshes are selected) |
| Simulate | Weight brush, weight gradient, with the weight groups to paint listed below |
| Skeleton | Bone pose, edit bones |
| Paint | Brush, pencil, eraser; paint bucket, eyedropper; shapes |
When a tool has variants (brush shapes, the sub-tools of Glue and Skeleton, weight group kinds, paint shapes), the variants are listed as icons once it is selected, and {key:PICK_OPTION_1}–{key:PICK_OPTION_5} switch between them directly.
## Where to adjust values
| Where | Contents |
|---|---|
| Mode bar (top of the canvas) | Point / edge / face selection, confirm and cancel for the current step |
| Tool options bar (lower left of the canvas) | Radius, strength, hardness, opacity and so on; drag left and right on a value to adjust, click to pop up a slider |
| Canvas right-click menu | The same values as the options bar, plus the tool's operations |
| Tool Details panel | Advanced settings that need more room (see [[detailed-toolpalatte]]) |
Brush size and hardness can also be adjusted directly with shortcuts, such as {key:BRUSH_RADIUS_DOWN} / {key:BRUSH_RADIUS_UP}.
{tutorial=TOOL_DETAILS}

@@ edit-level | Level edit
When editing a mesh deformer (Warp), sometimes you need to refine point by point and sometimes you just want to bend the whole grid into an arc. The two edit levels in Deform mode serve these two needs: **Vertices** and **Bezier**.
## Switching levels
1. Select a mesh deformer and enter Deform mode.
2. On the mode bar choose **Vertices** ({key:PICK_OPTION_1}) or **Bezier** ({key:PICK_OPTION_2}).
3. At the vertex level you drag each control point directly; at the Bezier level you drag only the cyan anchors and amber handles, the whole grid bends smoothly along the curve, and the fine grid fades and cannot be edited.
When the target is not a mesh deformer, the mode bar shows no levels, and an art mesh's vertices are edited by point, edge and face as usual.
## Related settings
**Bezier Division** (1–16) in the Inspector and Tool Details panels sets the segments of the Bezier level, and **Reset Bezier Control Points** returns the handles to their default positions.
{tutorial=DEFORM_MODE}
*> Cubism's edit levels 1–3 control how dense the displayed points are; here there are only the two levels Vertices and Bezier, and they apply only to mesh deformers.

@@ palettes | Palette
@@ palatte-basic-operation | Basic Palette Operations
The canvas and the panels are all dockable modules; drag them around to build a layout that suits the current task. The layout is stored in its own workspace, so a mess affects only that one workspace.
## Basic operations
| What you want | How |
|---|---|
| Reorder, or merge into a tab group | Drag a module's tab to another position in the same column, or between the tabs of another column |
| Split | Drag a module to the top, bottom, left or right edge of the target area and release to create a new column; drag the dividers to change ratios |
| Move a whole group | Drag an empty part of the tab bar or the faint handle at the right end |
| Float | Drag it out of the main window to become a separate window; **Dock in workspace** on the floating window's title bar takes it back |
| Close | Middle-click the tab; right-click a tab for **Close Other Tabs** or **Close All Tabs** in the group |
| Show again | Tick it in the **Windows** menu, or **Reset layout** |
The sidebar buttons at the upper right of the title bar collapse or expand all the panels on one side, and hovering shows the modules in them. The panels of the Start, Help and Settings pages can be arranged in the same way.
{tutorial=WORKSPACE}

@@ detailed-toolpalatte | Tool Details palette
The **Tool Details** panel holds settings of the current tool that need more room. Frequently used values such as radius, strength and hardness are not here but in the tool options bar at the lower left of the canvas and the canvas right-click menu, so you do not have to look away from the canvas while adjusting.
## Contents for each tool
| Tool | Settings |
|---|---|
| Transform | Exact numeric transforms |
| Create Warp / Create Rotation | Defaults used at creation, such as Bezier division |
| Deform path | The path list |
| Skeleton | Bone binding and weights |
| Paint | Palette: the foreground color's gamut and hue bar, swatches and recently used colors; "Discard" and "Apply" for the paint session |
{tutorial=TOOL_DETAILS}

@@ inspector-palette | Inspector palette
The **Inspector** shows and edits the properties of the selected object; its contents change with the object type, and with nothing selected it shows a hint.
## Main properties
| Object | Properties |
|---|---|
| Art mesh | Name, clipping mask, draw order, opacity, multiply color, screen color, blend mode, culling, user data, vertex info |
| Mesh deformer | Bezier division, edit level settings, deformer conversion method |
| Rotation deformer, deform path | Their own dedicated fields (such as the path's curve, width, hardness and closed state) |
Draw order has three entry points: the value in the Inspector, **Draw order…** in the hierarchy tree's right-click menu, and the draw-order ruler beside the hierarchy tree. They change the same property, so use whichever is handy.
{tutorial=INSPECTOR}
*> When you modify a keyform produced by the generator, "Generated overrides" in the Inspector lists the items that cannot be merged cleanly with regeneration.

@@ partspalatte | Parts palette
Looking for the Parts palette: the hierarchy of objects is in the **Hierarchy** tree, and the recognition and variants of PSD layers are in the **Layers** panel (see [[layers-palette]]). The hierarchy tree arranges art meshes directly by the deformers' parent-child relationships, and parts are written out when you export a `.cmo3`.
## Hierarchy tree
- The root node "Model Hierarchy" shows the number of deformers and layers.
- The top bar can search deformers and layers, expand or collapse everything, and sort by model order, height, horizontal position, draw order or name.
- The eye icon toggles visibility; selecting in the tree stays in sync with the canvas highlight.
- Dragging a node under another deformer changes its parent, and dragging it to "Drop here to move it to the root" moves it to the root level.
- Right-click a layer for: solo, draw order, mesh, add parent deformer or deform path, import layer, delete layer.
- Drag a PNG / WebP / TIFF / BMP onto the hierarchy tree: on a deformer it goes under it, on the upper or lower half of a mesh it is inserted in front of or behind it (an insertion line shows where), on empty space it goes to the root. The label by the pointer says what letting go does, and a place that takes nothing does not drop.
- The draw-order ruler beside the tree zooms with the wheel, reorders by dragging, and takes a value on double-click.
{tutorial=HIERARCHY}

@@ deformerpalatte | Deformer palette
The parent-child structure of deformers is also edited in the **Hierarchy** tree; deformers and art meshes are in the same tree, so you can see at a glance who follows whom.
## Creating and adjusting deformers
1. In the hierarchy tree select the layer or deformer to wrap in a deformer.
2. Right-click and choose add parent mesh, child mesh or parent rotation deformer.
3. A placement panel appears at the lower left of the canvas; adjust the extent on the canvas and confirm, or cancel with {key:CANCEL} (see [[making-and-placement-of-warp-deformer]]).
4. After that, dragging a node changes its parent; right-click also offers **Move to Root**, expanding or collapsing a branch, and **Delete (children up)**.
{tutorial=CREATE_DEFORMER}
If you have messed up the parent-child relationships, **Reset Hierarchy** restores everything, and **Reset Hierarchy for This Item** on an item restores only that one.

@@ palametorpalatte | Parameter palette
The **Parameters** panel lists all of the model's parameters, for posing and for adding or removing parameter keys. A generated model already comes with angle, blink, mouth and other parameters, so dragging them shows how the model can move.
## Basic operations
- Left-drag a parameter bar to change the current value; right-click while hovering over a key mark to snap to that key.
- Parameters can be searched, locked and reset, and organized with folders.
- Two-dimensionally linked parameters are operated with an XY pad; right-click to snap to the key intersection of the two parameters.
- Among key marks, a round dot is an ordinary key and a square dot is a blend shape key.
{tutorial=PARAMETERS}
*> The keyform on a parameter key is modeling data and is not the same thing as a keyframe on the animation timeline; dragging a parameter on the editing canvas pauses animation playback.

@@ layers-palette | Layers panel
The **Layers** panel is the main entry for controlling automatic generation: it lists the PSD layers as a table, together with the tags and variants the generator recognized for them. The generator decides how to rig a part from its tags, so when something moves wrongly in the model, check here first for a wrongly recognized tag.
## Table columns
| Column | Contents |
|---|---|
| # | Index |
| PSD layer / component | The layer name; the summary at the top shows the number of visible, recognized and unrecognized layers |
| Tags | Part, side, and custom tags the generator does not read; click to choose, or type words such as "hair" or "front" |
| Variant | None, Toggle Variant, Switch Variant |
| Parameter | The parameter ID the variant uses |
## Correcting the recognition
1. From the layer name, confirm what part it depicts.
2. Open the tags and choose the right part and side.
3. For layers that should appear or switch with a parameter, set the "Variant" column (see [[variants]]).
Generated layers take on their new tags after regeneration.
{tutorial=BASIC}
## Other operations
- Right-click to show all, hide all or invert visibility.
- Deleted layers can be brought back with **Restore Deleted Layers**.
- A layer set as a reference layer ({key:LAYER_TOGGLE_REFERENCE}) stays on the canvas for reference but takes no part in generation or export.
- Layers not yet generated can be reordered with {key:LAYER_TO_FRONT}, {key:LAYER_FORWARD}, {key:LAYER_BACKWARD} and {key:LAYER_TO_BACK}.
{tutorial=VARIANTS}

@@ timelinepalatte | Timeline palette
The **Animation Editor** edits a motion's parameter tracks and keyframes, and the motion list is in the **Animation** panel. Generation already brings idle and other preset motions, which you can edit directly or add to with new ones.
## Making a motion
1. In the Animation panel choose or create a motion.
2. In the Animation Editor add parameter tracks and double-click a track to add keyframes.
3. The dope sheet suits arranging timing and the curve view suits adjusting easing; keyframes use Bezier transitions by default.
4. With "Auto Key" on in the toolbar, dragging a parameter slider, a 2D point or the canvas pose at the playhead keys automatically; with it off, set the pose first and write it to the playhead with "Key Pose".
{tutorial=ANIMATION}
## Shortcuts (when the timeline has focus)
| Operation | Shortcut |
|---|---|
| Play / pause | {key:MOTION_PLAY_PAUSE} |
| Key the pose | {key:MOTION_KEY_POSE} |
| Auto Key | {key:MOTION_TOGGLE_AUTO_KEY} |
| Previous frame / next frame | {key:MOTION_PREV_FRAME} / {key:MOTION_NEXT_FRAME} |
| Fit view | {key:MOTION_FIT_VIEW} |
!> Preset motions have their own adjustment controls. After you edit its keyframes by hand, you must restore the default before those controls can be used again.
*> A motion belongs to the current model; there are no scenes as in Cubism Animator. Motions are written out together with the model on export.

@@ scene-palette | Scene palette
@@ project-palette | Project palette
@@ history-palette | History panel
The **History** panel draws every version of the project as a tree. Going back to an old version and editing does not discard the later steps but grows a new branch; the original branch is always kept and can be checked out at any time. History is stored in the `.psd2live`, so it is still there when you close and reopen the program.
## Opening
- {key:OPEN_HISTORY_TAB}, or **View → Show History**.
- You can also create a workspace with the "History" preset, which shows only the history tree and the **Operations** list.
## Common operations
| Operation | How |
|---|---|
| Go back to a version | Double-click the node card, or select it and click **Checkout**; **Latest** checks out the newest version |
| Search | **Search history…** at the top; the branch leading to HEAD is highlighted |
| Annotate | Fill in **Node title** and **Notes**, then click **Apply annotation** |
| Hide a branch | **Hide branch** (the data is not deleted); **Show hidden branches** restores them |
| Undo / redo | {key:UNDO} / {key:REDO}; with several branches, redo lets you choose the branch |
| Zoom the view | Zoom in, zoom out, reset to 100%, fit view |
The node details show the node ID, parent node ID, revision and creation time, and you can **Copy ID**.
{tutorial=PROJECT_HISTORY}

@@ logpalatte | Log Palette
The **Log** panel records program activity, edit operations and messages from AI clients, including errors, warnings and export reports. When something goes wrong, look here first to see what happened.
## Operations
- Filter by source (all, system, user, AI, image) and by level (debug, info, warning, error).
- **Filter…** filters by text; there are also **Auto Scroll**, **Clear** and **Copy Log**.
- The columns are time, source, tag and message, and can be restored with **Reset Columns**; repeated messages are merged and show a count.
- Click a record with an image to see it larger.
*> When reporting a problem, attach the copied log (see [[fault-map]]).

@@ convert-id | ID Conversion
Gives parameters, parameter groups, parts, deformers and ArtMeshes new IDs, such as Cubism 2's `PARAM_ANGLE_X` to Cubism 3's standard `ParamAngleX`. Every reference follows: the parameters keyforms and blend shapes use, parents and parts, clipping masks, glue, paths, vertex groups, combined parameters and parameter groups, motion curves, your own physics groups and swing targets; the physics, motions and expressions generated on standard IDs find the converted parameters too.
## Convert IDs
1. **Model → Convert IDs…**.
2. Under **Objects** pick parameters, parameter groups, ArtMeshes, warp deformers, rotation deformers or parts, and narrow the list with **Filter**.
3. Type the new ID beside each object to change. **Cubism 3 standard IDs** fills in the standard ID for every Cubism 2 style parameter (`PARAM_…`) and part (`PARTS_01_…`).
4. Click **Convert**. The whole batch is one history step you can undo.
A new ID uses letters, digits and underscores, starts with a letter, and is not taken by another object of its kind (warp and rotation deformers share one set of IDs); the dialog says why when one is not. To change many IDs by a rule, see [[search-and-replace]].
## Checked before export
An export checks the IDs: objects of one kind sharing an ID stop the export, which names the conflicting IDs; an ID with characters other than letters, digits and underscores only warns.
!> Parameters and meshes a swing, simulation or interior effect makes or reads cannot be converted on their own; remove them from that panel first. Meshes bound to the skeleton, bone parameters and bone deformers cannot be converted either. After a conversion, the current pose, parameter snapshots, timeline bookmarks and deformer visibility follow the new IDs, and an undo takes them back.
*> Unlike Cubism, no conversion table file is read or written. The conversion is recorded in the project history: later edits use the new IDs, and a regenerated model replays it in order.

@@ autosave | Automatic Backup Function
While you edit, PSD2Live keeps writing unsaved changes into the local workspace storage. If the program crashes or the power fails, the next launch can recover those changes, so you do not have to dig through backup files by hand.
## Recovery
1. After the program closed abnormally before you saved or discarded changes, launch PSD2Live again.
2. When the "Restore unsaved edits" window appears, choose **Restore**; choosing **Discard** does not restore.
3. The project opens in an unsaved state with its history, parameter poses, snapshots and workspaces intact; press {key:SAVE_PROJECT} to save it.
After a normal save, or after discarding and quitting, the recovery data is cleared. Background tasks that were running at the crash are shown as records but are not run again automatically.
!> Recovered content is still unsaved; save it as a `.psd2live` soon.
## Storage location
**Settings → Files & data** shows the location and size of the workspace storage, where the history of opened projects is kept; it can also clean up leftovers that an abnormal exit left in the system temp folder.

@@ material | Material division / PSD creation
@@ divide-the-material | About Material Separation
PSD2Live can generate a moving model straight from a layered PSD, provided that parts which move separately, or which cover each other, are drawn on different layers. The more sensibly it is split, the less the automatically generated meshes, deformers and parameters need reworking.
## How to split
- One layer per part: eye white, pupil, upper eyelashes, eyebrows, nose, mouth, front hair, back hair, face, neck, top and so on.
- Draw the pupil as a complete circle, including the part hidden by the eyelid; the eye white limits where the pupil shows.
- Draw the mouth at its widest open state; the closed mouth is generated by the program. The mouth interior can be one layer, or split further into upper teeth, lower teeth and tongue.
- Keep front hair and back hair separate and draw extra in the covered areas so that no base shows when the hair sways.
- Parts that come in left and right pairs (both eyes, both ears, both arms) go on separate left and right layers, with the side written in the name.
The layer name tells the generator what part each layer is; for the naming conventions and recognized names, see [[precautions-for-psd-data]].
## Missed splits can be fixed in the editor
Left and right parts drawn on the same layer are not separated automatically on import. After importing, [[split-by-mesh]] separates them into independent layers by connected region; when one layer must be both in front of and behind another part (for example a collar that wraps around the neck), use [[depth-split]]. When the materials come from several files and are hard to merge into one PSD, you can place the images directly in a [[layout-project]] and then generate.

@@ reimport-psd | How to create PSDs to import
If you keep to a few rules when preparing the PSD, you get a moving model straight after import and skip binding layer by layer by hand.
## File
- Use a layered PSD in RGB, 8-bit, with a transparent background.
- Rasterize text, vectors, layer styles and adjustment effects first, or merge them into the corresponding pixel layer.
- Keep the body basically upright; the head may tilt slightly. After import, check the automatically estimated reference in the preview.
## Layer names decide the part
The generator recognizes parts from layer names, in English, Chinese, Japanese or Korean, for example `front hair`, `前发`, `eyelash-l`, `左睫毛`. The recognition is only an initial value and can be changed at any time in the **Tags** column of the "Layers" panel after import.
*> A note for users of Cubism: a PSD's folder structure does not become parts; parts are built by the generator from the recognized parts, so layer names matter more than folders.
- Left and right refer to the character itself: the character's left side is usually on the right of the picture.
- A number in a name (such as `front hair 1`) only tells similar materials apart; the copy suffix Photoshop adds automatically is cleaned up.
- A number does not create expression switching. For layers that should show or switch with a parameter, set [[variants]] after import.
## Canvas size
The mesh settings default to **Scale with document size**: mesh lengths are computed as if the document's long side were scaled to a fixed size, so a large PSD gets a mesh of the same density; sharpness comes from the layers' own pixels.

@@ precautions-for-psd-data | Notes on PSD creation
Automatic generation depends on layer names and how the layers are split. Below are the recognized layer names and the places where drawing most easily goes wrong; a layer whose name is not in the table is not lost either — after import, add its part in the **Tags** column of the "Layers" panel.
!> A successful name recognition does not mean the shape is good enough. After import, check the neutral pose, eye and mouth opening, head turns and hair movement, and verify layer order, left/right, masks and exposed base.

@@ split-by-mesh | Split layers by mesh
When one layer holds several parts that are not connected to each other (for example two eyes on one layer), you can split it right in the editor by the mesh's connected regions; each piece gets its own mesh, deformation and tags, with no need to go back to the drawing software and edit the PSD.
## Splitting layers
1. Open **Model → Split Layers by Mesh…**; the dialog lists all layers that contain several separate meshes, and you can **Select all** / **Deselect all** and then click **Split selected**. To handle just one layer, right-click it in the hierarchy tree and choose **Split disconnected meshes…**, or choose it under **Mesh** in the canvas right-click menu.
2. Check the preview of each region and choose the naming: for two pieces you can pick `L / R`, `T / B`, `1 / 2` or **Custom names**; for more pieces, numbering or custom names.
3. Click **Split layers**; if you do not want to split, click **Keep as one layer**, and for batches you can click **Keep all as single layers**.
`L / R` names by the character's own left and right: the piece on the left of the picture gets the `-r` suffix and is tagged as the right side, and the other is `-l`.
After importing a layer the program may also ask on its own whether to split; to stop seeing the prompt, tick the box in the dialog, or turn off "Offer mesh splits when importing layers" under **Settings › Prompts**.
## After splitting
The parts replace the original layer, with meshes taken from the matching portions of the original mesh, and the keyforms, blend shapes, paths, weights and glue on the original layer carry over to the parts. If you later change a part's mesh settings, the mesh is regenerated from that part's own pixels and these bindings are migrated.
To split along a custom shape, or to make one layer interleave front and back, use [[depth-split]].
!> A model imported from a CMO3 cannot be split by mesh.

@@ depth-split | Front/back layers
A back collar hidden behind the neck and a front collar piece covering the neck in front — when one layer has to appear both in front of and behind another part, **Front/back layers** solves it in the editor in one step: the program duplicates the layer into a front layer and a back layer that sandwich the middle object, arranges the draw order automatically and ties the two layers together with glue so that they deform together; all you do is erase on the front layer the parts that should be hidden behind.
## Steps
1. On the editing canvas switch to **Object** mode, select the layer to split (such as the top), and hold {kbd:Shift} to add the object that goes in the middle (such as the neck).
2. Right-click the layer to split and choose **Split "layer name" front/back**. The menu shows the object that will be duplicated; check it before you click.
3. The program creates two layers, "`layer name · back`" and "`layer name · front`", in place of the original, selects the front layer and switches to the **Eraser** in **Paint** mode.
4. Along the edge of the middle object, erase the parts of the front layer that should be covered; if you erase too much, undo it in the paint session.
5. Click **Apply**. The front layer keeps the original mesh and deformation, so the mesh does not have to be regenerated.
6. Drag the head-turn, body-turn and other parameters in the Parameter panel to check, and go back to the front layer to touch up edges if needed.
{tutorial=PAINT_MODE}
With only one layer selected, right-click it in the hierarchy tree and choose **Front/back layers…**, pick the middle object under **Intervening object** in the dialog, check the relationship of front layer, middle and back layer, and click **Create and erase**.
## Notes
- Only one layer is split at a time; you can add several middle objects, which are placed together between the front and back layers and keep their order relative to each other.
- The appearance does not change right after creation: the front layer starts as a full copy, and the occlusion appears only after you erase.
- With an uncommitted paint session, apply or discard it before creating.
!> Front/back layers are not supported yet for models imported from a CMO3.

@@ layout-project | Layout projects and the material library
A Layout project lets you place parts on the canvas and tag them first, then generate the rig; after generation you can still add images, change tags and regenerate, and your existing edits are kept. It suits materials scattered over several files or split by other tools. All images a project uses live in the **Materials** panel, and a layer's pixels on the canvas come from the material it is linked to.
## Starting a Layout project
- **Empty canvas**: on the Start page choose **Layout project** (or **File → New → New Layout project…**), choose a common size or enter a width and height, and click **Create Layout Project**.
- **From a PSD**: import the PSD as usual and the project generates the rig directly; each PSD layer is also registered as a material and linked to its layer on the canvas.
- **From a Layout manifest**: a manifest written by an external tool (JSON, or a zip with it in the root) is opened the way a PSD is; the layers are placed per the manifest and not yet generated.
## Placing and generating
1. In the Materials panel click **Import** or **Import folder**, or drag files straight onto the panel. PNG, BMP, TIFF, WebP, JPEG and SVG are supported, plus a PSD's layers and a CMO3's ArtMesh images; a PSD becomes a folder, and layer groups become subfolders.
2. Drag a material or a whole folder onto the canvas, or right-click **Place on canvas**.
3. In **Object** mode use the transform box to move, scale and mirror layers; none of these resample pixels.
4. In the "Layers" panel's **Tags** column write the part and side, and in the **Variant** column set [[variants]].
5. Click **Generate** in the generate bar at the upper right of the editing canvas. After that, newly placed layers or layers whose tags changed show as "waiting"; click **Regenerate** to merge them into the rig.
{tutorial=LAYOUT}
*> The canvas unit decides the model's size and proportions, while sharpness comes from the material's own pixels: the smaller a layer is placed, the denser its texture.
Layers not yet generated move up or down one step with {key:LAYER_FORWARD} / {key:LAYER_BACKWARD}, and to the front or back with {key:LAYER_TO_FRONT} / {key:LAYER_TO_BACK}; after generation use [[draworder]] instead.
## Organizing materials
- Drag a material to another folder to move it; right-click to rename, create a folder or delete. A material in use by a layer cannot be deleted. Tags on a folder carry to every layer placed from it.
- Double-click a material to open a separate material window, where you can zoom, paint, flip and recolor; every layer using that material updates together.
- Right-click a material and choose **Replace selected layer's image**: the layer's position and size on the canvas stay the same and only the pixels change; see [[psd-re-import]].
- Every layer has a material as the one source of its pixels: importing a PSD, a CMO3 or images and splitting a layer register them. When layers share a material, painting any of them paints the material; to change only one, right-click **Make material its own**. In older projects, layers without a material are offered **Add to library** at the top of the Materials panel.
- Reference images such as the original art or drafts can be placed as reference images or reference layers; see [[sketch]].

@@ modeling | Modeling
@@ psd-import-folder | PSD Import
@@ psd-import | Import PSDs
After a layered PSD is imported, PSD2Live recognizes parts from the layer names and generates meshes, deformers, parameters, motions and physics in one go, giving you a model that moves right away; every later change is recorded as an edit and replayed automatically on regeneration. What gets generated is decided by [[model-presets]].
## Import
1. Choose **File → New → New project from PSD…** ({key:OPEN_PSD}), or drag a PSD anywhere onto the window. Or on the Start page click **Generate from PSD**, pick the **Minimal** / **Default** / **Complete** preset in the details panel and adjust as needed, then choose the file; double-clicking in **Recent PSDs** generates right away.
2. Wait for generation to finish. Hair and clothing simulations are then baked one after another, with progress shown in the status bar.
3. Open the **Layers** panel and check the **Tags** (part and side) recognized for each layer; open and change any that are wrong. Generated layers take on new tags after regeneration.
4. For layers that should show or switch with a parameter, set [[variants]] in the **Variant** column.
5. Switch to the preview and check blinking, mouth shapes, head turns and hair movement, then save as a `.psd2live` project with {key:SAVE_PROJECT}.
{tutorial=BASIC}
The chosen preset is remembered, and PSDs imported later from the menu, recent files or by drag and drop are generated with it. Each PSD layer is also registered as a material, and you can keep adding images in [[layout-project]].
*> For users coming from Cubism: parts are built automatically from the recognized parts, not from the PSD folders. To open an existing Cubism model project, use [[cmo3-import]].

@@ psd-re-import | Re-import PSDs
After the artwork changes, you can put only the new pixels into the existing project, and the edits you made to the model are all kept. PSD2Live does not match up the layers of a whole PSD; it replaces layer by layer:
## Replacing a layer's pixels
1. In the Materials panel **Import** the modified PSD; it becomes a new material folder.
2. Select the layer to update on the canvas or in the layer list.
3. Right-click the new material and choose **Replace selected layer's image**. The layer's position and size on the canvas stay the same; only the pixels and the atlas tile change.
4. Click **Regenerate** in the generate bar. A layer whose image was replaced is rebuilt by default with a mesh from the new pixels; to keep the original mesh, choose **Generate, keeping replaced images' meshes** in the generate button's drop-down.
A single layer can also be replaced in the **Texture** panel with **Replace image…**; see [[png-file-export-replacement]]. When the artwork gains a new part, treat it as a new material, drag it onto the canvas, tag it, and **Regenerate**; see [[layout-project]].
!> For a layer that already has authored edits, bindings or materialized meshes, replacing the image and rebuilding the mesh may be refused, and the reason is shown above the atlas page.
## Regenerate from scratch
When you want to start over completely from a modified PSD, use **Model → Reanalyze PSD** ({key:REANALYZE}): it re-reads the current PSD and generates a new project with the current preset. Edits in the original project are not carried over; if there are unsaved changes it asks first whether to save.

@@ model-presets | Model presets
**Model presets** is the section on the **World** page of the Modifiers panel. It decides in one place which motions, physics and simulation are generated automatically. Deformation amounts, mouth shapes and the Rig values of each part are adjusted on the role component cards (see [[modifiers]]). After a change the model is regenerated with the new settings and your existing edits are replayed as usual, so feel free to try repeatedly.
## Where to set them
- Before import: the details panel of **Generate from PSD** on the Start page; first choose **Minimal** / **Default** / **Complete**, then adjust item by item; the generate button's menu can switch presets or **Apply to Current Model**.
- After import: the **Model presets** section on the **World** page of the Modifiers panel (reopen the panel from the **Windows** menu if it was closed). The section's toolbar can expand or collapse everything, and **Reset Defaults** restores all presets.
## Contents of each group
| Group | Contents |
|---|---|
| Motions | Two groups, **Basic motions** and **Skeleton motions**; when turned off, that group's motions are removed from the Animation panel, preview and export |
| Physics and simulation | Front hair and back hair each choose **Simulation** / **Classic sway** / **Off**; **Clothing** simulation (acts only on loose parts); jelly eyes; auto Pin physics weights |
With **Selected only** ticked, the physics and simulation presets act only on the selected layers.
*> It is best to generate with the defaults first and preview; when you need to adjust, change one item at a time so you can see what each does. Texture atlas settings are not here but in the export window.

@@ update-generated-rig | Update Generated Rig
Actions such as turning on hair simulation, enabling the skeleton or splitting layers merge your edits onto the generated result of that moment and pin it, so that upgrading the program never quietly changes your model. When you want the improvements of a newer generator, run **Update Generated Rig** yourself.
## Operations
1. First choose **Model → Preview Generated Rig Update**: the status bar shows whether the update would change the model and how many items need a look; the project itself is unchanged.
2. Then choose **Model → Update Generated Rig**. The parts you did not change switch to the new version's result, and your changes are kept.
3. Where something cannot be migrated cleanly, the status bar reports how many, and the items are listed one by one in **Generated overrides** in the Inspector.
An update is one history step and can be undone; when the generated result does not change, no history step is made.
After a project is opened or the generated result changes, the program checks in the background whether an earlier version merged something wrongly; if so, a prompt appears at the top of the editing canvas, where you can click "Update Generated Rig" or "Preview changes" directly. After you dismiss the prompt, it does not appear again until the generated result changes once more.
*> Canvas edits on keyforms owned by the generator are saved as overrides of the generated result and three-way merged with the new result on update, so shapes you tuned by hand are not washed away.

@@ cmo3-import | Import from CMO3
An existing Cubism model project (`.cmo3`) can be imported into PSD2Live to keep editing, previewing and exporting; parameters and keyforms, deformers and hierarchy, textures and meshes, and physics all come along and are saved with the `.psd2live` project. After exporting a CMO3 you can go back to Cubism Editor and carry on there.
## Two ways to import
| Way | Entry | Result |
|---|---|---|
| New project from CMO3 | **File → New → New project from CMO3…**, or **Import CMO3** on the Start page | Clears the current model; the content comes only from that file |
| Replace the current model with a CMO3 | **Model → Replace the model with a CMO3…**, or **Replace with a CMO3…** on the Start page | Keeps the current project, replaces objects of the same ID in the model with those from the CMO3 and adds new ones; objects the CMO3 does not have stay as they are |
Neither way applies a model preset automatically.
{path=EXPERIENCED}
## Notes
- Parts marked as reference images in Cubism are not written by default when exporting moc3; when needed, tick **Export guide image parts** in the export window.
- The Materials panel can also import a CMO3, and the layered images in it are used as materials; see [[layout-project]].
!> A model imported from a CMO3 cannot use [[split-by-mesh]] or [[depth-split]].

@@ texture | Texture
@@ texture-atlas-edit | Edit Texture Atlas
The texture atlas is packed automatically with the model and usually needs no attention; when you want to control the sharpness of a particular part, or squeeze the textures into smaller pages, adjust tile positions, density and rotation directly in the **Texture** workspace and set the page budget.
## Three sizes
Each layer has three sizes: the rectangle it covers on the canvas (canvas units), the pixels of its own raster, and the tile pixels in the atlas. The **Texture** panel shows these three for the selected layer side by side as "Canvas → Source → Atlas".
## Adjusting tiles
1. On the atlas page click a tile to select its layer, or drag on empty space to box-select; the selection stays in sync with the canvas and the layer list.
2. Drag a tile to move it; when its mesh overlaps another tile's or goes beyond the page, the tile turns red and does not move on release.
3. Drag a corner of the selected tile to change density proportionally, and drag the round handle outside the top edge to rotate (hold {kbd:Shift} to step by 15°).
4. A session bar appears at the top of the page where you can undo, redo or **Discard**; when satisfied click **Apply**, which is committed as one history step.
Right-clicking a tile also offers doubling / halving the density, rotating 90°, moving to another page, locking its size, arranging the selected tiles, or replacing the image. Overlap is judged by the area the mesh actually covers, so transparent corners of tiles can nest into each other.
## Layout and budget
- **Arrange** at the upper left rearranges once and saves: **By mesh shape (nest tiles)** or **By rectangles**, with a scope of **All tiles** or **Selected tiles only**.
- With **Auto arrange** on, every change rearranges by rectangles; for new projects it is off by default and the current layout is kept.
- **Atlas budget** at the upper right sets page size, maximum page count and spacing. A "Fit" below 100% means it does not fit, and all unlocked tiles are shrunk together; layers with **Lock size** stay unchanged.
With no layer selected, the **Texture** panel shows each page's size, tile count, usage and a video memory estimate; with a layer selected, the **Texture density** slider and the ½× / 2× / reset buttons adjust how many atlas pixels correspond to each source pixel (1/64–16).
*> **Source pixels** in the display bar at the lower right of the editing canvas makes the canvas sample the layer's own raster directly, which is handy for comparing against the packed sharpness.

@@ png-file-export-replacement | Exporting and Replacing PNG Files
To modify a texture in a drawing program, export it first, and after editing, replace it back per layer; the atlas is repacked automatically, and tile positions do not have to stay the same.
## Export
- Right-click empty space on the atlas page in [[texture-atlas-edit]] and choose **Save page as PNG…**; the content is byte-for-byte identical to the texture in the exported model.
- **File → Export as → Source PSD…** ({key:REEXPORT_PSD}) exports a layered PSD at 1× or an upscale factor, and can **Include generated auxiliary layers**; it suits touching up layer by layer in a drawing program.
- In the Materials panel, right-click a folder and choose **Export folder as PSD…**.
## Replace
1. Select the layer to replace.
2. In the **Texture** panel click **Replace image…** (or right-click a tile on the atlas page).
3. Choose the fit method **Stretch** or **Contain**, and whether to **Rebuild the mesh from the new pixels**.
The new image can be any resolution; the canvas rectangle stays the same, and a larger image raises the layer's density. You can also right-click in the Materials panel and choose **Replace selected layer's image**.
!> For a layer that already has authored edits, bindings or materialized meshes, replacing the image and rebuilding the mesh is refused, and the reason is shown above the atlas page.
*> To fix only a few pixels, [[paint-mode]] is easier.

@@ texture-upscale | Texture upscale
When the artwork resolution is low, texture upscale enlarges textures layer by layer with AI before the atlas is packed, so lines and details stay clear at large display sizes. It does not change the original PSD, the canvas size or the bindings; the preview and export use the processed textures, and you can change the factor or turn it off at any time.
## Usage
1. Open **Model → Texture Upscale…** ({key:TEXTURE_UPSCALE}), or click **Texture Upscale…** in the **Texture** panel.
2. Fill in the local runtime environment. If no upscale model is detected, click **One-Click Download Model (110MB)** to download it automatically and fill in the model folder and nunif folder; if the machine already has an environment, click **Use detected local runtime**.
3. Choose the **Scale** and the denoise level, then click **Apply**. The program processes layer by layer and rebuilds the preview, and the configuration is saved with the project.
4. Check the meshes and deformation on the editing page, confirm in the preview, and export again.
{tutorial=TEXTURE_UPSCALE}
| Item | Contents |
|---|---|
| Python | A Python executable with the nunif dependencies (including PyTorch) installed |
| nunif source folder | A local source folder containing the `nunif` and `waifu2x` subfolders |
| Art weights folder | The folder that holds the Art `.pth` weights |
| Scale | 1 is off; 2× or 4× enlargement |
| Denoise and line sharpening | No denoise (enlarge only), or Level 0–3, default Level 1 |
| Neural alpha channel | On by default; when off, alpha is enlarged bilinearly |
| Input tile size | 64–512, default 256; reduce it when video memory runs short |
## Notes
- The release package does not include Python or PyTorch; you must install them yourself, and the local paths must be checked again after changing computers.
- A missing matching model causes an error; ordinary interpolation is not passed off as upscaling.
- The neural alpha channel may change semi-transparent outlines; check hair strands, eyelashes, lip lines and moving mask edges against both light and dark backgrounds.

@@ paint-mode | Paint mode
Touch up edges, fill in color, erase a stray line, all without going back to a drawing program: Paint mode edits layer pixels right in the editor. Strokes are first placed in a separate paint session and only written into the project as one history step once you confirm, so you can discard it whenever it goes wrong.
## Start painting
1. Select a layer on the canvas or in the layer list.
2. In the mode menu at the upper left of the canvas choose **Paint** ({key:MODE_PAINT}).
3. Pick a tool in the left toolbar, and adjust size, hardness and opacity in the tool options bar at the lower left.
4. When done, click **Apply** on the session bar at the top of the canvas, or **Discard** to throw away all strokes of this session.
{tutorial=PAINT_MODE}
The session bar shows the layer being painted and the stroke count, and also offers undo and redo; the "Tool Details" panel also has **Discard changes** and **Apply**. During a session {key:UNDO} undoes only strokes and does not affect the project history.
## Tools and shortcuts
| Tool | Shortcut |
|---|---|
| Brush | {key:TOOL_PAINT_BRUSH} |
| Pencil | {key:TOOL_PAINT_PENCIL} |
| Eraser | {key:TOOL_PAINT_ERASER} |
| Paint bucket | {key:TOOL_PAINT_BUCKET} |
| Eyedropper | {key:TOOL_PAINT_EYEDROPPER} |
| Shapes (line / rectangle / ellipse) | {key:TOOL_PAINT_LINE}; {key:PAINT_SHAPE_CYCLE} switches the shape |
Brush size {key:BRUSH_RADIUS_DOWN} / {key:BRUSH_RADIUS_UP}, hardness {key:BRUSH_HARDNESS_DOWN} / {key:BRUSH_HARDNESS_UP}, opacity {key:PAINT_OPACITY_DOWN} / {key:PAINT_OPACITY_UP}; hold {key:BRUSH_ADJUST_DRAG} and drag to adjust on the spot. The foreground / background colors come first in the tool options bar, and {key:AXIS_CONSTRAIN_X} swaps the two.
## Painting beyond the mesh
You can paint anywhere on the canvas. On apply, it is clipped to the layer's mesh: what is inside the mesh stays in the layer, and the mesh, keyforms and bindings do not change; what is outside the mesh is split into a new layer named after the original layer plus "+", placed directly above the original layer and moving with the same parent. To make the original layer's own mesh cover the newly painted area, click **Rebuild from pixels** in the Mesh panel.
*> For a layer linked to a material, strokes are written into the material itself, so every layer using that material changes together; see [[layout-project]].

@@ parts-folder | Part
@@ parts | About Parts
Parts group art meshes and deformers, for organizing objects, showing or hiding them as a whole and grouping on export. In PSD2Live you normally do not create parts yourself: the generator builds "Head", "Back Hair", "Face", "Front Hair", "Head Accessories", "Extra", "Body" and other parts automatically from the recognized parts, layers are put into them by tag, and unrecognized layers go into "Body".
## Where to see parts
The **Hierarchy** tree shows the parent-child relationships of deformers and art meshes, which decide how deformation is inherited; it is not a parts tree. To see which part an art mesh belongs to, select it and look at the **Part** drop-down in the **Inspector**; for changing the assignment see [[edit-parts]].
{tutorial=HIERARCHY}
*> A model imported from a CMO3 keeps its original part structure. When exporting moc3, **Export hidden parts** in the export window decides whether hidden parts are written.

@@ edit-parts | Editing Parts
Parts are built by the generator; when adjustment is needed, you can change which part an art mesh belongs to, or specify which part a deformer goes into when you create it.
## Change the part an art mesh belongs to
1. Select the art mesh on the canvas or in the hierarchy tree.
2. In the **Part** drop-down of the **Inspector**, choose the target part, or **(None)** to move it to the top level.
The change is recorded as one history step and can be undone.
## Specify the part when creating a deformer
When creating a Warp in Object mode, **Part to insert** in the "Tool Details" panel decides which part the new deformer belongs to; the default is **Inherit from selection**.
*> For users used to dragging objects in Cubism's Parts palette: in PSD2Live, dragging adjusts the parent-child relationships in the hierarchy tree, and part membership is changed only in the Inspector; see [[partspalatte]].

@@ edit-parts-sketch | Guide image parts
Images used only for comparison, such as the original art and drafts, can be set as reference layers: they show on the canvas but take no part in generation and are not exported.
## Setting as reference
1. Select the layer.
2. Click **Use as Reference** on the "Layers" panel toolbar, or press {key:LAYER_TOGGLE_REFERENCE}; the canvas right-click menu also has a **Use as Reference** switch.
A reference layer has a reference icon after its name; click it to turn it back into a model layer, and the restored layer joins the model at the next generation. A layer that has already been generated cannot be set as a reference.
## Display
Reference layers sit underneath the model by default, and **Reference layers on top** in the display bar at the lower right of the canvas stacks them above the model. References of different angles can be right-clicked and **Pin to Keyform**, so they show only at the matching parameter values; see [[sketch]].
*> References are set per layer, not per part. A reference-image part imported from a CMO3 is not written by default when exporting moc3; when needed, tick **Export guide image parts** in the export window.

@@ variants | Variants
Parts that are simply "present / absent", such as blush, tears and glasses, and looks that are "pick one of several", such as expressions, gestures and outfits, can all be made variants: declare it on the layer, and generation automatically builds the matching parameter and visibility control, so dragging the parameter switches it, with no need to keyframe opacity by hand. Variants are set in the **Variant** column of the "Layers" panel.
| Kind | Effect | Suited to |
|---|---|---|
| None | Generates the matching part from the tag | Ordinary layers |
| Toggle Variant | One parameter shows or hides the layer | Blush, tears, glasses, light and shadow |
| Switch Variant | With one parameter, only the layer of one ID is shown at a time | Expressions, gestures, outfits |
{tutorial=VARIANTS}
## Setting a Toggle Variant
1. In the "Layers" panel set the row's **Variant** to **Toggle Variant**.
2. In **Parameter** enter the parameter name (such as `blush`). The same parameter name can drive several toggle layers.
## Setting a Switch Variant
1. Set all the related layers to **Switch Variant**.
2. In **Parameter** enter the same parameter name, or pick an existing switch parameter from the drop-down.
3. Set the IDs beside them to 0, 1, 2… in turn, for example `expression`: 0 normal, 1 happy.
Once set, dragging the parameter shows the effect in both editing and preview.
!> A number in a layer name does not create a variant automatically: a Toggle Variant needs a parameter, and a Switch Variant needs a shared parameter with different IDs.

@@ copy-and-paste | Copy and Paste Objects
Copy ArtMeshes and deformers and paste copies of them. A copy keeps the original's keyforms, blend shapes, clipping masks, paths and vertex groups and shares its texture and layer; after pasting, each can be changed on its own.
## Copy and paste
1. In **Object** mode, select ArtMeshes (one or more) in the hierarchy or on the canvas, or one deformer in the hierarchy.
2. Press {key:COPY}, or right-click in the hierarchy and choose **Copy**.
3. Press {key:PASTE}, or right-click and choose **Paste**: each copy appears beside its original, under the same parent. Right-click a deformer and choose **Paste into this deformer** to hang the copies under it; ArtMeshes keep where they show on the canvas.
A paste is one history step you can undo. A copy's ID is the original's with `_copy` (`_copy_2`, `_copy_3`… when taken); change it later with [[convert-id]].
## What is copied
| Copied object | Copied with it |
|---|---|
| ArtMesh | Keyforms, blend shapes, paths, vertex groups; glue between two meshes copied together |
| Deformer | Every child deformer and ArtMesh under it, their parents pointing at the copies |
| Part | Its child parts, ArtMeshes and the deformers organized in it |
| Parameter | A new parameter with the same range and default beside the original; keyforms stay on the original |
A clipping mask that names a mesh copied along points at its copy; otherwise it keeps the original mask. Parts are copied through MCP; the parameter panel also copies a parameter's definition.
*> Swings, simulations and interior effects belong to the whole document and are not copied with an object. The paste copies objects as they are when you paste: changes made to an original after copying are in the copy too.

@@ search-and-replace | Search and Replace Objects
Find objects by name or ID and replace in bulk: tidy up naming, rename parts finished on one side for the other side, or prefix a group of parameters.
## Use
1. **Model → Find and Replace…**.
2. Under **Search in** choose **Names** or **IDs**, and toggle the object kinds below; check **Selection only** to touch only the objects selected in the hierarchy.
3. Fill **Find** and **Replace with**, or **Add prefix** and **Add suffix**; check **Regular expression** when needed (the replacement may use `$1` for groups) or uncheck **Match case**.
4. The list shows, as you type, what each match becomes. Click **Replace All** to apply the whole batch as one history step.
## Swap left and right
**Swap left and right** exchanges the side marks that stand as words of their own in a name or ID: `L`/`R` (as in `ArmL`, `Hair_L_01`, `ParamEyeLOpen`), `Left`/`Right` and `左`/`右`; letters inside words such as `Lip` stay.
Replacing IDs works as [[convert-id]] does: every reference follows.
*> With nothing to replace, the list only shows what matches, so the dialog also serves as a search.

@@ offscreen-drawing | Offscreen drawing
@@ draw-order | Draw order
@@ draworder | About Draw Order
Draw order decides which art mesh covers which: the larger the value, the further to the front, in the range 0–1000 with a default of 500. It is independent of the deformers' parent-child relationships, so adjusting occlusion does not affect deformation inheritance.
## Changing the draw order
- Type it in the **Draw Order** field of the **Inspector**.
- In the hierarchy tree, right-click an art mesh → **Draw order…** and enter a value, or **Reset to Default**.
- On the draw-order ruler beside the hierarchy tree, zoom with the wheel, drag to reorder, and double-click to enter a value.
To see the front-to-back relationships at a glance, set the hierarchy tree's **Sort** to **Draw order (front to back)**.
{tutorial=INSPECTOR}
*> Layout layers not yet generated are adjusted by order up and down ({key:LAYER_FORWARD} / {key:LAYER_BACKWARD}); after generation use the draw order instead; see [[layout-project]].

@@ drawing-order-group | Draw order group
@@ artmesh | ArtMesh
@@ concept-of-artmesh | About ArtMeshes
An ArtMesh maps a texture onto a mesh of vertices and triangles, and moving the vertices deforms the image. Each PSD layer becomes an ArtMesh on import, also called an art mesh in PSD2Live; the mesh is generated automatically at import from the layer's transparency.
## What a mesh consists of
- Vertices: the points that move when deforming.
- Edges and triangles: connect the vertices and decide how the image stretches with them.
- Outline: follows the edge of the layer's opaque pixels; pixels outside the mesh are not shown.
The denser the vertices, the finer the deformation, but the heavier the load on editing and at runtime. For adjusting density see [[mesh-edit]], for changing topology locally see [[mesh-edit-manual]]; what moves several meshes together is a [[deformer]].
{tutorial=LIVE2D_BRIDGE}
## Properties
With an ArtMesh selected, the **Inspector** shows its name, ID, part, deformer, Clip ID, inverted mask, draw order, opacity, multiply color, screen color, blend mode, culling and user data.

@@ mesh-edit | Automatic Mesh generator
Every layer already has a mesh on import: the program traces the outline from the layer's transparency, places edge points and interior points according to the settings, and triangulates. When the mesh is too sparse, too dense or its outline does not fit, change the settings in the **Mesh** panel to rebuild it; existing keyforms, paths, weights and glue migrate to the new mesh, so nothing has to be redone.
## Adjusting the mesh
1. Open the **Mesh** panel in the **Mesh** workspace or from the **Windows** menu.
2. **Global defaults** apply to all parts without their own setting; after selecting an ArtMesh, the panel shows its own settings, and after you change them it shows "Custom Settings".
3. The mesh is rebuilt right after a change; check the effect in Deform mode and in the preview.
When the mesh does not cover some visible pixels, the panel shows the proportion; click **Rebuild from pixels** to include them. **Show descriptions** in the panel expands an explanation of each option.
## Main settings
| Group | Setting | Purpose |
|---|---|---|
| Outline position | Single / double / triple edge layers, outer edge distance, edge mesh width | How many rings of vertices go near the outline, and where |
| Point density | Edge point distance, interior density | Spacing of outline vertices and of interior points |
| Interior fill algorithm | Graded Poisson fill (default), balanced quadtree fill, regular-triangle fractal subdivision, contour layering, simple triangle fill | How the interior points are arranged |
| Connection topology | Suppress edges that cross between edge points | Make non-adjacent edge points connect through interior points |
| Units | Scale with document size, trace outline by texture pixels, wrap | The length unit, the precision of the outline trace, and gaps between small protrusions such as closed eyelashes |

@@ mesh-edit-manual | Edit Mesh manually
Where the automatic mesh is not right in a few places, change the topology directly in **Edit** mode: subdivide, connect, merge, duplicate, delete and mesh points (adding points, drawing lines). Even when the mesh already has keyforms you can modify it — the vertex references of the UVs, keyforms, blend shapes and glue are updated along with it, and every operation goes straight into the history.
## Entering Edit mode
1. Select one or more art meshes.
2. In the mode menu choose **Edit** ({key:MODE_EDIT}); the mesh is forced to display.
3. On the mode bar choose point / edge / face ({key:PICK_OPTION_1}–{key:PICK_OPTION_3}), which decides the elements the select tool picks.
{tutorial=EDIT_MODE}
## Operations
| Operation | Result |
|---|---|
| Subdivide ({key:TOOL_SUBDIVIDE}) | Adds a point on the hit edge and reorganizes the triangles, handling the seams of neighboring faces too |
| Connect / merge | Creates connections between the selected points, or merges the selected vertices into one point |
| Duplicate | Duplicates the selected vertices and the faces they fully cover; the new copy becomes the selection |
| Delete ({key:DELETE_SELECTION}) | Deletes the selection and tries to fill the gap; refused when no valid topology can result |
| Mesh points · Points ({key:TOOL_MESH_POINT}) | Click on open canvas to add a vertex (inside a triangle, on an edge or outside the mesh), drag on open canvas to add points along the stroke at **Point spacing**, drag a vertex to move it; {kbd:Shift}+click connects from the selected vertex, {kbd:Alt}+click deletes a vertex or edge; **Mirror across centre** repeats it on the other side. The mesh tidies its own connections around new points, so they are not promised an edge between them |
| Mesh points · Line ({key:TOOL_KNIFE}) | Click a polyline point by point; each point is joined to the last by an edge, so the mesh can fold along it (an eyelid, a mouth line, a crease). Snaps near points or edges (hold {kbd:Shift} to turn this off temporarily); {key:FINISH_PATH} finishes and {key:CANCEL} cancels |
| Delete edges | {key:DELETE_SELECTION} in edge mode or the topology menu: an inner edge becomes the other diagonal of its quad, an outline edge removes its triangle; refused when it would break the mesh |
| Re-triangulate | Topology menu: around the selected vertices (the whole mesh with none selected) turns slivers into more even triangles, leaving vertices and texture in place |
When several art meshes are selected, clicking a vertex of one mesh makes it the primary mesh; a mesh-points line and edge and face selection act only on the primary mesh.
!> Deleting outline points changes the boundary, so check under several parameter poses after changing it. Do everyday shape pulling in Deform mode, and use Edit mode only to change topology.

@@ mesh-duplication | Mesh duplication
Copy one ArtMesh's vertices and triangles onto other ArtMeshes, for variants of one shape such as an open and a closed eye or several expressions. Each target takes the same topology where the source sits on the canvas and samples its own texture; the target's keyforms, blend shapes, paths, vertex groups and glue migrate onto the new mesh.
## Use
1. In the hierarchy, select the source mesh together with one or more target meshes.
2. Right-click the source mesh and choose **Duplicate this mesh onto N selected meshes**.
The duplication is one history step you can undo.
!> Target artwork outside the source mesh is not drawn, and source vertices outside the target artwork sample beyond it. Check the result in Edit mode and add points or cut with [[mesh-edit-manual]] where needed.
*> What is copied is the mesh structure, not its deformation: the target's keyforms migrate by vertex position; the source's keyforms are not copied.

@@ clipping-mask | Clipping Mask
A clipping mask makes an ArtMesh show only within the area of another ArtMesh, for example a pupil showing only inside the eye white. The commonly used masks are already set automatically when the model is generated: a pupil is masked by the eye white on the same side, and inner-mouth parts such as teeth and tongue are masked by the mouth.
## Setting manually
1. Select the ArtMesh to be masked.
2. In **Clip ID** in the **Inspector** specify the mask: type the ID directly (separate several with commas), click the list button **Select clipping mask from list**, or click the crosshair button **Quick link mask from canvas** and then click the mask art mesh on the canvas.
One ArtMesh can be limited by several masks at once. For the opposite effect, see [[reversed-mask]].
{tutorial=INSPECTOR}

@@ reversed-mask | Reversed mask
A reversed mask makes an ArtMesh show only outside the mask area, the opposite of a [[clipping-mask]].
## Setting
1. Select an ArtMesh that already has a **Clip ID**.
2. Tick **Inverted mask** in the **Inspector**.
The change is recorded as one history step and can be undone.

@@ sketch | Placement of Guide Image
Putting the original art, a draft or character sheets of other angles on the canvas for comparison while you work lets you adjust shapes more accurately. A reference is a kind of member of the hierarchy, with the reference icon, and shows in one of three places; the place decides whether it is in the model:
| Shows as | In the model | Description |
|---|---|---|
| Background | No | Under the whole model, in the editor only; once selected, drag it on the canvas to move, scale and turn it, and lock it |
| In the layer stack | Yes | Once generated, an ArtMesh of the model among the layers, stacked by drawing order, which can hang under a deformer and deform with it; exports treat it as a guide-image part, left out unless **Export guide image parts** is checked |
| Foreground | No | Over the whole model; otherwise like the background |
Before a layout project's first generation, a reference in the layer stack waits with the other layers and enters the model when the project is generated.
## Adding a reference
1. Drag a picture or a material onto an edit canvas: big options appear at the bottom - **Background · Reference**, **In the layer stack · Reference**, **Foreground · Reference**, replacing the selected layer's image, and only adding to the library - each saying whether it is in the model; letting go elsewhere on the canvas places an ordinary layer.
2. Or click the reference button on the hierarchy toolbar or in the canvas display bar (it goes to the foreground), or right-click a material and choose one of the three places under **Add as reference**.
A layer already on the canvas that has not been generated yet, or an imported image, becomes a reference in the layer stack with **Use as Reference** on the "Layers" panel toolbar or {key:LAYER_TOGGLE_REFERENCE}; see [[edit-parts-sketch]].
## Adjusting a reference
- Click a reference in the hierarchy: a background or foreground one shows handles on the edit canvas - drag inside to move it, a corner to scale it, the knob above to turn it; one in the layer stack takes the transform box like any ArtMesh.
- Right-click a reference: **Shows as** moves it between background, layer stack and foreground, each marked with whether it is in the model; its material, place, visibility and pin carry over. The menu also shows or hides it, sets opacity and lock (background and foreground), gives it back to the model (layer stack) and removes it.
- {key:REFERENCE_TOGGLE_SHOWN} shows or hides the background and foreground references on the current canvas.
## Showing by keyform
References of different angles, such as front, side and looking up, can be stacked in one place and each shown only at its keyforms; one reference can show at several:
1. Right-click the reference; **Pin to Keyform** at the bottom of the menu lists the parameters the model has keyforms on, in the parameter panel's shapes - a track with keyform dots for a single parameter, a 2D pad for a pair.
2. Click a keyform dot to put a point there (filled), click it again to take it away; with several points the reference shows at any of them and hides at other poses. The grey marker is the current pose.
3. **Unpin** clears every point, showing the reference at any pose.
*> The **Pin to Keyform** submenu of the canvas context menu is the same panel. Pins only decide when the editor shows a reference; they are not exported.

@@ hittest | Preparation for Setting Up Collision Detection
Hit areas tell an app which part of the model was clicked: change the expression when the head is tapped, play a motion when the body is. PSD2Live keeps the hit areas in the project and writes them on export to model3.json's `HitAreas` (the VTube Studio package carries them too) and to the `.p2lrt` runtime model.
## Setting up hit areas
- **Inspector**: select an ArtMesh and choose the area it belongs to in **Hit area**, or **New hit area** to start one with it. While there is none, **Set up Head and Body** creates Head and Body from the recognized face and top (the neck or the bottom when there is no top). Once the mesh is in an area, edit its **Hit area name** (what an app asks for; Head and Body are usual) and **Hit area ID**.
- **Hierarchy**: right-click an ArtMesh and, under **Hit areas**, check or uncheck the areas it belongs to, or choose **New hit area from this mesh**.
An area can cover several ArtMeshes; areas are tested in list order and the first hit wins. Each change is one history step and can be undone. Without any area, the `.p2lrt` runtime uses automatic Head and Body areas and model3.json gets no hit areas.
## Checking in the preview
Click the model on a preview canvas without dragging: the area hit is highlighted in the accent color and the log reads "Hit area: name (ID)"; a miss logs that there is no hit area there. The test is the runtime's: a mesh that is visible, has an opacity above 0 and has the point inside one of its triangles is hit.
*> A model3.json hit area names a single ArtMesh, so an area of several meshes is written as several entries of the same name, which the export report notes. The Cubism SDK samples test a mesh's bounding rectangle, which can differ slightly from the preview. Cubism models often use a dedicated hit mesh at opacity 0: such a mesh is never hit in the PSD2Live preview or the `.p2lrt` runtime, and once exported to model3.json the host decides. When a mesh is split its pieces take its place in its areas, and a deleted layer's meshes leave them; an area that still names meshes the rig no longer has lists them as **Missing meshes** in the inspector, and export leaves them out and notes them in the export report.

@@ blend-mode | Blend mode
The blend mode decides how an ArtMesh's color combines with what has already been drawn beneath it, and is often used for shadows and highlights. Select an ArtMesh and choose in **Blend Mode** in the **Inspector**:
| Mode | Effect |
|---|---|
| Normal | Ordinary covering |
| Multiply | Multiplies with the color beneath; for shadows |
| Add | Adds to the color beneath; for glow and highlights |
A PSD layer's blend mode is converted automatically on import: additive modes such as Linear Dodge (Add), and Linear Light, become Add; Multiply, Linear Burn and Color Burn become Multiply; other modes are treated as Normal.
*> To only tint an ArtMesh without changing how it is composited, use **Multiply Color** and **Screen Color** in the Inspector; see [[multiply-color-screen-color]].

@@ interior-shape | Interior shape
On a large garment or head of hair, only the outline moves when turning, so it looks like a sheet of paper. Interior shape makes the texture inside a layer contract, expand and lean along a virtual curved surface, giving a sense of volume as it turns; it generates the keyforms it needs automatically and exports as standard blend shapes and physics settings. It does not generate new textures or shadows, and it is not cloth simulation.
## Usage
1. Select a generated layer and choose **Interior shape** in the **Add** menu of the Modifiers panel. It becomes a card in that mesh's stack.
2. The switch on the card's title enables or disables it; once enabled, the program generates the interior shape.
3. Under **Shape** adjust the horizontal / vertical amounts, surface bulge, bias, fold strength and outline preservation; for a local fold, click + add under **Local folds**.
4. When the motion needs easing, expand **Easing / propagation** and set the easing time, propagation time, propagation strength and **Direction**.
5. In the card's **Audition** drag the 2D parameter pad or turn on **Mouse follow** to check the effect; the card menu's **Solo / frame** shows only this layer and centers it on the canvas, and **Edit on canvas** enters the interior shape handles.
Each drag commits one history entry when it ends; if generation fails, the draft and the previous valid result are kept.
## Settings
| Setting | Range | Meaning |
|---|---|---|
| Horizontal / vertical amount | 0–2 | The total shape amount in each of the two directions |
| Surface bulge / bias | 0–2 | The virtual arch and how much it is biased; with either at 0 there is no surface displacement |
| Fold strength | 0–2 | The contribution of local folds; has no effect without local folds |
| Outline preservation | 0–1 | 0 frees the visible outline, 1 protects the original outline |
| Easing time | 0–0.6 s | Response time of the first stage |
| Propagation time | 0–0.5 s | How long the rear area takes to follow the front area |
| Propagation strength | 0–1 | The weight of the rear area; 0 means no zoning |
The horizontal / vertical mouse multipliers under **More settings** affect only the mouse audition and do not change keyform amounts or the exported parameter range; **Source parameters** can pick an existing parameter or **New parameter**.
## Notes
- Turning the card's switch off keeps the settings but does not apply the deformation; **Delete** in the card menu removes the recipe and its physics, and can be undone.
- After the mesh, material or driving parameter changes, the card prompts you to regenerate or rebind; effects that have become invalid are skipped on export and listed in the warnings.
- Easing uses the project's physics group and exports as standard physics settings.

@@ modifiers | Modifiers
The **Modifiers** panel shows the selected object in the order Live2D actually computes it: first its own keyforms, then each parent deformer in turn (innermost first). Each level is a **stage**, and the order of the stages is the nesting order in the hierarchy tree; to change it, change the parent-child relations in the hierarchy tree.
## Object page
- One row per stage: its name, what generated it (head turn, eyes, swing..., or **By hand**) and the number of cards acting on it. The selected object's own stage is expanded by default and the others are collapsed; **Contributed only** on the toolbar hides the stages nothing else acts on.
- Expand a stage to see first which generators and hand edits its keyforms come from (the parameter list), then the cards acting on it: modifiers, simulations, interior effects and swings. The cards are in execution order, and each reads the result of the ones before it; a card can be expanded for its settings, switched, moved up or down, applied as keyforms or deleted.
- The arrow button on a stage row selects that deformer, and **+** adds to that stage; **Add** on the toolbar adds to the selected object's own stage. A mesh's own stage can also add **Simulation · Cloth / Hair** and **Interior shape**.
- A simulation card holds the bake, material, inputs, outputs, glue, vertex weight groups and the comparison (the reference sim switch); an interior shape card holds the audition and all the shape settings; a swing card holds the directions, preset, fulcrum, and each direction's shape and pendulum.
## Mirror
- Key one side of a parameter and get the other as its mirror image: for example key only the positive side of Angle X, and the negative side follows; change the positive side and the negative side changes with it.
- Options: **Parameter** picks the parameter to mirror (by default a two-sided parameter the target is keyed on, Angle X first), **Kept side** positive or negative, **From** another mesh or warp (a left eye for the right), whose displacement is then taken over the whole range.
- **Mirror line x** is where the vertical mirror line stands (canvas x); a new mirror takes its target's centre.
- The displacement field is mirrored, not the vertices: asymmetric meshes, and eyes with different vertices, work too.
- **Output**: **Into the mesh's keyforms**, or **A warp of their own** (a warp between the mesh and its parent keyed on the modifiers' parameters only, so the mesh's keyforms are not multiplied); **Automatic** uses the warp when the keyforms would multiply.

## Follow surface, corner correction, turn in depth
- **Follow surface**: line art, shading, highlights and accessories move with the mesh they are drawn over (the **Host**, by default the closest layer under them): whatever keyforms the host has, they follow without being keyed again. **Amount** 1 follows fully.
- **Corner correction**: when shapes were made on X and Y one at a time, the corner poses (both at once) often collapse. It makes a corner the sum of what each axis does alone; an **Amount** above 1 pushes further.
- **Turn in depth**: treats the targets as a solid at a **Depth** in front of the centre, turned by the parameters (Angle X / Y by default) and seen in perspective: what is in front moves further and the far side narrows. **Bulge** rounds the middle forward, **Angle** is the turn at the parameters' ends, and a nearer **View distance** gives more perspective.

## Role components
The generated head turn, eyes, brows, mouth, body pose, breath and hair are each a role component. With a mesh selected, the components that generated something in its stages are listed as cards before the stages; the **Role components** section of the **World** page lists all of the model's components.
- The switch on a card's title turns the component off: its parameters no longer drive anything it generated, while the deformers and existing edits stay. With Head turn off, the eyes and others also stop reacting to Angle X.
- **Values**: the items of the former "Rig Values" that belong to the component, common ones first, the rest under **Advanced**. The eyes and brows can tick **Left and right apart**, which gives **Blink sag**, **Blink corners**, **Closed lash thickness** and the brow lift a value per side.
- The component's own settings: for the head, **Head range** and the **Facial Feature Displacement Deformer**; for the body, **Body range**; for the mouth, the lip outlines, mouth shape, line width and color; for the eyes, the idle blink motion, see-through over the bangs and jelly eyes; for the brows, see-through over the bangs; for the hair, the pendulums.
- **Members**: the layers the component recognised, with their role and side; changing a role changes that layer's semantic tag. Click a name to select the layer.
- **Parameters**: the parameters the component generated.

## World page
- **Role components**: the cards of all the model's components.
- **Simulations**: all physics bodies and their bake state, with **Bake all** and **Bake automatically**; click a row to select its mesh and switch to the Object page.
- **Problems**: the problems of all cards (missing parameters, too many keyforms, out of tolerance, an interior shape that needs regenerating); click a row to jump to the object.
- **Model presets**: see [[model-presets]].
!> A simulation reads only what comes before it in the stack; after the cards before it change, its bake shows **Stale**.

@@ artpath-folder | ArtPath
@@ artpath | About ArtPaths
@@ artpath-auto-edit | ArtPath Auto Generation
@@ artpath-edit-manual | ArtPath Manual Creation
@@ artpath-brush-custom | ArtPath Brush Editing
@@ artpath-adjustment | ArtPath adjustment
@@ stroke-artpath | Stroke ArtPath / Re-stroke ArtPath

@@ deformer-folder | Deformer
@@ deformer | About Deformers
A deformer wraps ArtMeshes and other deformers and changes its own shape with parameters, so everything inside moves with it. After a PSD is imported, PSD2Live has already built a full set of deformers from the layer tags (body, head, face, hair and so on); most of the time you fine-tune that structure instead of building from scratch.
## Two kinds of deformer
| Kind | How it deforms | Typically used for |
|---|---|---|
| Warp deformer | A grid of control points wraps its children; moving the points bends the contents smoothly | Face turns, body bends, hair sway |
| Rotation deformer | Rotates everything around an origin (optionally with scale); children stay rigid | Arms, neck, ornaments and other parts that turn around a joint |
*> The warp deformer is Cubism's "Warp Deformer"; in the interface it is called warp deformer or Warp.
## Where to see them
The Hierarchy lists deformers and ArtMeshes by parent-child relationship, and selection stays in sync with the canvas. The **Deformer Warp** and **Deformer Rotation** toggles in the display bar at the bottom right of the canvas control whether they are drawn. To find out which deformers affect an object, see [[convenient-function-deformer]].
## Next
- Add a deformer yourself: [[making-and-placement-of-warp-deformer]], [[making-and-rotation-of-rotationdeformer]].
- Change a parent and understand layered motion: [[system-of-parent-child-relation]], [[setting-of-parent-child-relation]].
- What the generated structure contains: [[auto-generation-of-deformer]].
{tutorial=CREATE_DEFORMER}

@@ making-and-placement-of-warp-deformer | Warp Deformer
When the generated structure has no suitable deformer (for example, you want an accessory or a lock of hair to bend on its own), add a warp deformer yourself. PSD2Live creates it by dragging a box on the canvas; the preview box you drag out is the final grid.
## Create
1. Select the ArtMeshes to put into the deformer in the Hierarchy or on the canvas. With several selected, they must all hang under the same parent deformer.
2. In Object mode, choose **Create Warp** ({key:TOOL_CREATE_WARP}) in the last group of the left toolbar. You can also enter from the canvas right-click menu **Create**, or from the Hierarchy right-click **Parent · Warp** / **Child · Warp**.
3. Drag a rectangle on the canvas and scroll the wheel while dragging to adjust the divisions. To fit the selected objects only, just click **Create from selection**.
4. In the placement panel at the bottom left, check the attachment, the part to insert into and the size strategy; drag inside the box to move it, and drag a corner or edge midpoint to scale it.
5. Click **Create** on the mode bar or press {key:FINISH_PATH} to write it to the project; **Cancel** or {key:CANCEL} abandons it.
{tutorial=CREATE_DEFORMER}
| Placement option | Contents |
|---|---|
| Add to | Set as parent of selected / Set as child of selected deformer (empty) / Specify parent deformer |
| Part to insert | Which part the new deformer goes into; inherits the selection's part by default |
| Size | Fit selection (+5% padding) / Include keyform envelope / Align center with selection |
| Continuous creation | Keep placing the next one after creating |
*> Unlike Cubism's creation dialog, the name and divisions are not entered before creating: the divisions are set with the wheel while dragging, and defaults are set in the **Tool Details** panel and the model presets. The placement box can only be moved and scaled, not rotated.
## Edit levels: Vertices and Bezier
After selecting a warp deformer and entering Deform mode, the mode bar offers two levels, **Vertices** and **Bezier** ({key:PICK_OPTION_1} / {key:PICK_OPTION_2}). The Vertices level edits the grid point by point; the Bezier level only drags the cyan anchors and amber handles to bend the whole grid with a few control points, while the detailed grid is dimmed and not editable. In the Inspector you can set **Bezier Division**, **Reset Bezier Control Points** and **Deformer Conversion (3.2 method)**.
{tutorial=DEFORM_MODE}

@@ making-and-rotation-of-rotationdeformer | Rotation Deformer
For parts that turn around a point, such as arms, necks and hanging ornaments, a rotation deformer is the simplest choice: it has only an origin, an angle and a scale, so any parameter value gives an exact rigid rotation instead of the in-between deformation a grid would show.
## Create
1. Select the ArtMeshes or deformers to rotate.
2. Choose **Create Rotation** ({key:TOOL_CREATE_ROTATION}) on the Object mode toolbar, or enter from the canvas right-click menu **Create** or the Hierarchy right-click **Parent · Rotation**.
3. On the canvas, drag from the rotation center to the direction endpoint; a single drag sets both the origin and the heading. Hold Shift to snap to 15°.
4. The new deformer is inserted as the direct parent of the selection, and the former parent is kept. The canvas highlights what will be remounted, and the placement panel shows how many deformers and ArtMeshes are affected.
5. Press {key:FINISH_PATH} or click **Create** to confirm, {key:CANCEL} to cancel.
{tutorial=CREATE_DEFORMER}
## Adjust
In Deform mode, drag the origin to move it and drag the endpoint to rotate; Shift snaps the angle, and Alt + dragging the endpoint adjusts the scale. A rotation deformer does not accept the deform brush; use the move, rotate or scale tools.
*> The handle direction follows Cubism's convention: 0° points up and clockwise is positive, so the angles you see in Cubism Editor after export are the same.
Rotation deformers for limbs, tails and wings usually need no manual creation: the skeleton generates them automatically, see [[skeleton]].

@@ system-of-parent-child-relation | Parent-Child Hierarchy Structure
An object follows the deformer it hangs under: the parent deforms first, the child is carried along as a whole, and the child's own keyforms are then layered on top. Understanding this lets you tell whether a shape is moving by itself or being carried.
## Inheritance rules
- Objects under a warp deformer: the whole shape bends with the parent grid.
- A warp deformer inside a warp deformer: the child deformer's control points are transformed by the parent, and the inside is then interpolated by its own grid.
- A rotation deformer under a warp deformer: it inherits only the origin position and angle; the parent's bending does not pass down.
- Objects under a rotation deformer: they rotate rigidly with the origin.
## Telling the two kinds of motion apart while editing
The parameters bound directly to an object determine its own shape, while parent parameters come down through the hierarchy. Before changing a shape, make clear whether you want to edit the object's own axis or should actually edit the parent; otherwise it is easy to mistake a pose brought by the parent for the object's own binding. The **Parent** badge in the Hierarchy marks the ancestors of the selected object.
Related: [[setting-of-parent-child-relation]] · [[combintion-of-parent-child-relation]] · [[keyform-parent-chilid-relation]]

@@ setting-of-parent-child-relation | Setting Up Parent-Child Hierarchies
To change a parent, just drag in the Hierarchy; when creating a deformer you can also decide on the spot which object it goes above or below.
## Change the parent in the Hierarchy
1. In the Hierarchy, press and hold the row of an ArtMesh or deformer.
2. Drag it onto the target deformer and release to hang it underneath; drag it to the **Drop here to move it to the root** area to move it to the root level.
3. Changing a parent is one history step and can be undone.
{tutorial=HIERARCHY}
!> Dragging a transparent image onto the Hierarchy imports a layer; it does not change a parent.
## Set while inserting a deformer
- The Hierarchy right-click **Parent · Warp**, **Child · Warp** and **Parent · Rotation** insert a new deformer above or below the selected object; when a deformer is deleted, its children move up one level.
- When creating a Warp, **Add to** in the placement panel can be set as parent of selected, set as child of selected deformer (empty), or a specified parent deformer; see [[making-and-placement-of-warp-deformer]].
*> If you change a parent on a generated model and later regenerate by editing the model presets, the skeleton and so on, your changes are kept through a three-way merge; see [[update-generated-rig]].

@@ combintion-of-parent-child-relation | Combination of Parent-Child Hierarchy
The core idea of a deformer structure is to split complex motion into several simple layers: outer layers handle the large turns, inner layers handle local bending or rotation, each layer binds only the parameters it needs, and lower layers automatically inherit the motion of the upper ones.
## Layering in a generated model
The body PSD2Live generates is stacked exactly like this (the actual structure in the Hierarchy is authoritative):
1. `DeformBodyXY`: Body X / Y; the whole upper body turns and follows the hips.
2. `DeformBodyLean`: body lean forward/back and proportion (head-to-body ratio).
3. `DeformBodyZBreath`: Body Z and breath.
4. Below that come the head rotation and the face lat-long grid, then the facial features and hair.
So when the head turns, it is also carried by the body's turn, and you do not need to repeat the body's motion on the head. With the skeleton enabled, the torso bend and the limb rotation deformers are attached at the end of this chain; see [[skeleton]].
## Tips when building
- An upper deformer with a coarse grid resamples the shapes below it, so put fine bending in the layer closest to the mesh.
- Parts that must bend along go under a warp deformer; under a rotation deformer they inherit only the origin and angle.

@@ auto-generation-of-deformer | Auto Generation of Deformer
When a PSD is imported, PSD2Live generates the whole set of deformers, parameters and keyforms from the layer tags in one go, and the model can move right away. Your work changes from building the structure to tuning it.
## What is generated
- Body chain: Body X / Y, lean forward/back, Body Z and breath; full-body standing characters also get leg deformers.
- Head: head rotation with head-shell follow, the face lat-long grid, and face contour and facial-feature displacement; see [[face-auto-edit]].
- Hair: follow and sway branches for front and back hair; you can add [[auto-generation-of-sway-motion]] or simulation on top.
- Skeleton (when enabled): torso bend and limb rotation deformers; see [[skeleton]].
The exact objects vary with the model presets; the Hierarchy is authoritative.
## Adjust the result
1. In the **Role components** section on the **World** page of the Modifiers panel, adjust each component's range and values, or in the **Model presets** section adjust motions and physics; the model regenerates accordingly.
2. The edits you made on the generated result are merged onto the new result and kept.
3. To benefit from improvements in a newer generator, use **Model → Update Generated Rig**; see [[update-generated-rig]].
*> When you create a warp deformer by hand, **Create from selection** and the size strategy in the placement panel also size it automatically from the selected objects.
Related: [[model-presets]] · [[psd-import]]

@@ convenient-function-deformer | Validate Deformer Function
Once a structure gets complicated, you need a quick answer to "where does this object hang, and which deformers and parameters drive it". PSD2Live puts these checks where you are already working: the canvas display bar, the Hierarchy and the Parameters panel.
| To find out | How |
|---|---|
| Which deformers affect the selected object | In the display bar at the bottom right of the canvas, turn on **Contextual warp**, which shows only deformers related to the selection; combine it with **Dim unselected** and **Selected only** |
| A deformer's name | The **Names** toggle in the display bar |
| Where an object hangs | The **Parent** badge in the Hierarchy marks the ancestors; you can search deformers and layers and expand or collapse all |
| Which parameters an object is bound to | **Related only** in the Parameters panel lists only the parameters bound to the selected components |
| Which objects have shapes on a key | Hover the key marker in the Parameters panel |
| Bring an object to the center of the view | {key:FRAME_VIEW} |
{tutorial=HIERARCHY}

@@ parameter-folder | Parameter
@@ parameter | About Parameters
Parameters are numeric sliders that drive the model, such as Angle X or eye open. An object records a shape (a keyform) on several keys of a parameter and is interpolated automatically at in-between values; animation, physics and face tracking all end up just changing parameter values. A generated model already carries a full set of parameters, so dragging them in the Parameters panel is enough to check the result.
## The Parameters panel
| Action | How |
|---|---|
| Change a value | Drag the slider with the left button |
| Snap to a key | Hover a key marker and right-click |
| Two-dimensional control | Two linked parameters appear as a 2D pad: drag with the left button, right-click to snap to the intersection of both parameters' keys |
| Reset / lock | The reset and lock buttons on the row; **Reset All** and **Unlock All** on the toolbar |
| Find | Search by name or ID; **Related only** lists just the parameters bound to the selected components |
| Change the definition | Click the parameter name to open **Parameter Properties**; see [[parameter-modifyication]] |
{tutorial=PARAMETERS}
## Reading key markers
- A round dot is a normal key, interpolated between neighboring keys; a square dot is a blend shape key, which adds the shape difference on top of the base shape; see [[blend-shape]].
- Hovering a key marker lists the components that have a keyform on that key.
- A parameter with a physics icon is a physics output, and physics takes over while previewing.
!> Keys on a parameter are modeling data; they are not the same thing as keyframes on an animation timeline.
*> Several canvas tabs share one parameter pose, so a value changed in one canvas is visible in the others too.

@@ edit-parameters | Add/Delete Keys to/from parameters
In PSD2Live the positions of keys belong to the parameter itself, and all objects bound to that parameter share the same set of keys; when an object is modified on a key, the shape is recorded on that key.
*> In Cubism you select an object and press "Add 2 points / 3 points" to add keys to that object; here the parameter's key points are edited together in **Parameter Properties**, and objects bind by being deformed on a key.
## Add, delete and move keys
1. Click the parameter name in the Parameters panel to open **Parameter Properties**.
2. On the **Key points** axis, click empty space to add a point and drag to change its value; click a number to type a value and press Enter to confirm; right-click to delete.
3. **Step** sets the snapping interval while dragging, and **Even** spreads the key points evenly; the minimum, default and maximum can be typed directly at the ends of the axis.
4. Use **Undo point edit** and **Redo point edit** to step back, and click **Save** to commit once.
!> When you move a key, the existing keyforms move with it; deleting a key also deletes every object's shape on that key (undoable).
## Give an object a shape on a key
1. Select the object.
2. In the Parameters panel, drag the parameter to the target key (or right-click to snap to the key).
3. Switch to Deform mode ({key:MODE_DEFORM}) and change the shape; the result is written into this key's keyform.
A newly created parameter produces no motion by itself; it moves only after you give objects shapes on its keys.
{tutorial=PARAMETERS}
{tutorial=DEFORM_MODE}

@@ parameter-modifyication | Parameter Adjustment
A parameter's name, range and keys are changed in **Parameter Properties**; once there are many parameters, use folders and 2D links to keep the Parameters panel tidy.
## Change the definition
1. Click the parameter name in the Parameters panel to open **Parameter Properties**.
2. Change the **Name**; the ID is editable only when the parameter is created.
3. At the two ends of the **Key points** axis, change the minimum, default and maximum; the default must lie within the range.
4. Click **Save**; this is one undoable history step.
The dialog also has **Lock parameter value** (only keeps the preview at the current value; it is not part of the definition), **Duplicate definition** (copies the name and range, not component bindings or deformation data) and **Delete Parameter** (also removes the associated keyframes and links).
An ordinary parameter can also **Repeat**, its value running on from the minimum past the maximum; see [[repeat]].
!> Shrinking the range also limits the key values and Bezier control points in animations; deleting a parameter removes the corresponding animation tracks.
## Tidy the Parameters panel
- Create parameters or folders from the toolbar; right-click a folder to create a parameter / folder inside it, set a label color, rename it or dissolve it.
- The link button on a row joins the parameter with the next one into a 2D controller, and **Unlink** restores two sliders; the height of the 2D pad can be adjusted by dragging.

@@ standard-parameter-list | Standard Parameter List
Generated models use the Cubism standard parameter IDs, so after export they can be connected directly to all kinds of runtimes and face-tracking software. The table below shows the parameters generated by default; in mesh mode, with some deformers turned off, or when the matching parts are missing, the actual set differs, and the Parameters panel is authoritative.
| ID | Name | Range | Default |
|---|---|---|---|
| `ParamAngleX` | Angle X | -45…45 | 0 |
| `ParamAngleY` / `ParamAngleZ` | Angle Y / Angle Z | -30…30 | 0 |
| `ParamBodyAngleX` / `Y` / `Z` | Body Angle X / Y / Z | -10…10 | 0 |
| `ParamBodyLean` | Body lean forward/back | -10…10 | 0 |
| `ParamProportion` | Proportion (head-to-body ratio) | -10…10 | 0 |
| `ParamEyeLOpen` / `ParamEyeROpen` | Left Eye Open / Right Eye Open | 0…1 | 1 |
| `ParamEyeBallX` / `ParamEyeBallY` | EyeBall X / EyeBall Y | -1…1 | 0 |
| `ParamEyeBallForm` | Jelly eye | -1…1 | 0 |
| `ParamBrowLY` / `ParamBrowRY` | Left Brow Y / Right Brow Y | -1…1 | 0 |
| `ParamMouthForm` | Mouth Form | -1…1 | 0 |
| `ParamMouthOpenY` | Mouth Open | 0…1 | 0 |
| `ParamBreath` | Breath | 0…1 | 0 |
| `ParamHairFront` / `ParamHairBack` | Front hair sway / Back hair sway | -1…1 | 0 |
*> `ParamBodyLean` and `ParamProportion` are PSD2Live's own parameters and not in the Cubism standard parameters; tracking software does not drive them.
## Other automatic parameters
- Skeleton parameters: `ParamArmLA`, `ParamLegLA`, `ParamTail1..n` and so on, in degrees; see [[skeleton]].
- Swing parameters: `ParamSwing<id>`, range -1…1; see [[auto-generation-of-sway-motion]].

@@ extended-interpolation-folder | Extended Interpolation
@@ extended-interpolation | About Extended Interpolation
@@ extended-type | Interpolation Type
@@ repeat | Repeat
With **Repeat** on, a parameter's value runs on past its maximum from its minimum, and past its minimum from its maximum: for a wheel that keeps turning, a windmill or a tail that swings round and round.
## Set it
1. Click the parameter's name in the Parameters panel to open **Parameter properties**.
2. Tick **Repeat (wrap from the maximum back to the minimum)** and click **Save**. This is one undoable history step; only ordinary parameters can repeat, blend-shape parameters cannot.
## What it does
- The preview and the runtimes wrap the value into the range. The maximum and the minimum are the same pose, so the shapes at both ends should match.
- A motion may key the parameter past its range, up to 64 turns, for a continuous spin; see [[timeline-basic-operation-repeat]].
- The `moc3` gets the parameter repeat flag (the format from Cubism 5.3), the `cmo3` its `isRepeat`.
- The value wraps as in Cubism: exactly whole turns past the maximum land on the minimum, and below the minimum on the maximum.
- Physics inputs and motion fades take the wrapped value, so across the ends they work from the other end, which may differ from a continuous spin.
- Exporting to a target without repeat, the downgrade report lists each motion curve keyed past the range.
!> Exporting for an SDK target before Cubism 5.3 drops the setting and lists it in the downgrade report; the runtime then clamps motion values past the range to its ends.
@@ recording-parameters | Record Parameter Operations and Generate Animations
@@ copy-and-paste-parameter-values | Copy/Paste Parameter Values
After you have dialed in a pose or expression you like, save it as a parameter snapshot and return to that state with one click later; this is easier than copying parameter values one by one, and suits comparing back and forth between several poses while correcting shapes. A snapshot records only parameter values and does not change the model.
## Use parameter snapshots
1. In the Parameters panel, set the parameters to the pose you want.
2. Click **Save current parameters as a snapshot** in the snapshot bar; the new snapshot is named **Snapshot n**.
3. Hover a snapshot to preview that pose on the canvas, click to load it, and middle-click to delete it.
4. Right-click a snapshot for **Overwrite with current values**, rename or delete.
*> A snapshot loads the whole set of parameters, like pasting all parameter values at once, rather than copying the selected parameters and pasting them as in Cubism.
## Skeleton poses
A pose set up in Skeleton mode can also be saved by name in **Tool Details** and applied again; **Insert saved pose** in the animation editor inserts it at the playhead; see [[skeleton]].

@@ motion-sync | Motion-sync
@@ keyform | Key form editing
@@ how-to-choose | How to make selections
Choose the right thing before editing a shape: select the object to edit in Object mode, then select the points to move in Deform or Edit mode. The selection always stays in sync with the Hierarchy.
*> PSD2Live uses the canvas mode to tell the two kinds of selection apart: Object mode selects only objects, while Deform / Edit mode selects points. Edit mode can edit several selected meshes at once.
## Select objects (Object mode)
- Click to select, Shift to add, Alt to subtract; Ctrl + click cycles among overlapping objects.
- Drag to box-select; {key:SELECT_ALL} selects all, {key:CANCEL} deselects.
- Selection in the Hierarchy stays in sync with the canvas; in the tree, Shift selects a range and Ctrl toggles. {key:FRAME_VIEW} brings the selection into view.
{tutorial=SELECT_MODE}
## Select points (Deform / Edit mode)
| Tool | Shortcut | Use |
|---|---|---|
| Select | {key:TOOL_SELECT} | Click or box-select |
| Lasso Select | {key:TOOL_LASSO_SELECT} | Free-form lasso |
| Brush Select | {key:TOOL_BRUSH_SELECT} | Select by painting like a brush |
| Temporary Select | Hold {key:TEMPORARY_SELECT} | Release to return to the previous tool |
A mesh can be selected by point / edge / face; switch on the mode bar or press {key:PICK_OPTION_1}–{key:PICK_OPTION_3}. {key:INVERT_SELECTION} inverts the selection, {key:SELECT_LINKED} selects the connected part, and {key:SELECTION_STYLE_BOX} / {key:SELECTION_STYLE_LASSO} switch the selection style.

@@ how-to-edit | How to edit
All keyforms are made in Deform mode: set a parameter to a key, then change the object's shape. If the object is not bound to a parameter yet, you change its base shape; if it is bound, you change the keyform at the current parameter combination.
## Basic flow
1. Select the object and switch to Deform mode ({key:MODE_DEFORM}).
2. In the Parameters panel, set the parameter to the target key.
3. Change the shape with the tools in the table below; releasing the mouse enters it into the history, {key:UNDO} undoes and {key:REDO} redoes.
{tutorial=DEFORM_MODE}
| Tool | Shortcut | Effect |
|---|---|---|
| Transform | {key:TOOL_TRANSFORM} | Move, scale and rotate the selected points with the transform box |
| Deform Brush | {key:TOOL_BRUSH} | Push the points within the brush range; see [[deform-brush]] |
| Smooth Brush | {key:TOOL_SMOOTH} | Smooth the shape out |
| Inflate Brush | {key:TOOL_INFLATE} | Inflate outward or shrink inward |
- {key:AXIS_CONSTRAIN_X} / {key:AXIS_CONSTRAIN_Y} restrict movement to one axis.
- A warp deformer can switch between the **Vertices** and **Bezier** edit levels; for a rotation deformer, drag the origin and the endpoint.
- **Precise Transform** in **Tool Details** moves, scales and rotates the selected points by numeric values; see [[edit-vertices]].
- The canvas right-click menu **Mirror keyform** mirrors a finished side to the other; see [[motion-mirroring]].
- The canvas right-click menu **Keyform** copies, pastes, resets or interpolates the shape at the current key; see [[copy-and-blend-shape]].
- With **Soft selection** on ({key:SOFT_SELECTION_TOGGLE}), dragging or transforming selected points also moves the points around them, less the further away they are; set the reach in the tool options bar or with {key:BRUSH_RADIUS_DOWN} / {key:BRUSH_RADIUS_UP}. The falloff curve is shared with the deform brush, and the affected points are ringed in red.
*> Changing the mesh structure (adding points, drawing lines) happens in Edit mode, separate from changing shapes; for everyday shape correction stay in Deform mode. When you edit a keyform owned by a generator, the change is recorded as a generated override and survives regeneration; see [[generated-overrides]].

@@ keyform-parent-chilid-relation | Keyforms (Parent-Child Hierarchy Movement)
A parent deformer and a child object can be bound to the same parameter, each recording shapes on the keys; when the parameter changes, the overall motion brought by the parent and the child's own shape are combined into the final result. This way the parent handles the large motion and the child only needs small corrections.
## Example in a generated model
When Angle X turns the head, the head deformer handles the turn of the whole head, and then the face lat-long grid and facial features each correct their shapes by Angle X; the generated model is bound in layers like this.
## Things to note while editing
- First see which parameters the object is directly bound to (**Related only** in the Parameters panel) and tell that apart from the motion it gets through the hierarchy from its parent.
- A shape edited on a child is based on the parent's state in the current pose.
- Mirror keyform handles only the object's own deformation, using the parent in the default pose as the coordinate system; the parent's keyforms must be mirrored separately.

@@ keyform-xydirection | Keyforms (Make X, Y Movements)
When an object is bound to two parameters at once (such as Angle X and Angle Y), the keyforms are arranged in a grid: each combination of an X key and a Y key has its own shape, and diagonal poses are interpolated from the surrounding keys. The generated head already has nine poses made from combinations of the end and middle points of Angle X / Y, with extra correction for the diagonals; the strength is tuned in the **Values** of the **Head turn** component card.
## Make a set of XY shapes yourself
1. In the Parameters panel, link the two parameters into a 2D controller with the link button on the row, and right-click on the pad to snap to the intersection of both parameters' keys.
2. In Deform mode, make shapes at each intersection in turn: first the end and middle points, then the four diagonals.
3. Drag the pad back and forth between keys to check whether the interpolation looks natural.
{tutorial=PARAMETERS}
*> The shapes of the four diagonals have to be made one by one; PSD2Live does not synthesize them automatically as Cubism does.

@@ copy-and-blend-shape | Edit Form
The **Keyform** submenu of the Deform mode canvas right-click menu copies the shape at one key to another key or to a like object, resets a key to its default shape, or interpolates a middle key from the keys on either side. The result is written as ordinary keyforms and can be undone.
## How to use
1. Select the object in Deform mode and set the parameters onto one of its keys.
2. Choose an operation in the canvas right-click menu **Keyform**, or use the shortcuts below.
| Action | Shortcut | What it does |
|---|---|---|
| Copy keyform | {key:KEYFORM_COPY} | Records each selected object's shape, opacity and colors at the current parameters; later changes to the source do not reach the paste |
| Paste keyform | {key:KEYFORM_PASTE} | Writes the copied shape onto the current key as moves (a mesh's from its rest, a Warp's or rotation deformer's from its default-key shape); onto the same object, or one of its kind with the same triangles (a Warp: rows and columns) under the same parent |
| Reset to the default-key shape | {key:KEYFORM_RESET} | Puts the current key back to the shape at the parameters' defaults |
| Reset to the mesh rest shape | — | Meshes only: puts the current key back to the undeformed mesh |
| Interpolate from neighbouring keys of (parameter) | {key:KEYFORM_INTERPOLATE} | Remakes the current key from the keys on either side along that parameter; with several such parameters the menu lists each (blend shape parameters excepted) |
- With points selected, paste, reset and interpolate change only those points, and the menu rows show how many; a Warp at the Bezier level and a rotation deformer are always handled whole.
- When the object binds no parameter or the parameters are between its keys, the menu says so and the shape rows are off.
- A shape edit on keyforms a generator owns is recorded as a generated override; see [[generated-overrides]].
- **Paste with options…** pastes only some contents, blends by a ratio or flips, and sets where several shapes go; see [[paste-form-special]] and [[confirm-target-for-pasting-multiple-forms]]. **Restore every key to the default-key shape**: [[restore-deformer-shape]]; **Multi-key editing…**: [[multi-key]].

@@ paste-form-special | Paste Form Special
**Paste with options** pastes only the checked contents, and can blend by a ratio or flip before pasting.
## How to use
1. Select the object in Deform mode and copy with {key:KEYFORM_COPY} or the canvas right-click menu **Keyform → Copy keyform**.
2. Set the parameters onto the key to write, and choose **Paste with options…** under **Keyform**.
3. Set the options in the dialog and click **Paste** (or press Enter).
| Option | What it does |
|---|---|
| What to paste | Any of shape (points), opacity, draw order, multiply color and screen color; without the shape the channels go onto any selected object (draw order onto meshes only) |
| Blend ratio | 100% pastes; less moves the current key that share of the way toward the copied values, blending the forms |
| Flip | Left to right reverses a Warp's columns, top to bottom its rows; a mesh pairs vertices by their mirrored rest places (its rest must be symmetric that way, or the paste is refused); each point takes its partner's move turned around; a rotation deformer's angle change is reversed |
*> Unlike Cubism the flip works on the moves, so it needs no flip line, and there is no Warp "flip over" option; mirror an asymmetric mesh with [[motion-mirroring]]. A plain paste after the dialog closes still pastes the shape only.

@@ confirm-target-for-pasting-multiple-forms | Where to apply form pasting
After copying several objects' shapes together, the **Paste with options** dialog shows and lets you change which object each shape is pasted onto.
## How pairs are made
- Copying records one shape per selected mesh (the first is the current target).
- When pasting, a source that is itself selected and sits on one of its keys gets its own shape back; otherwise each shape goes to the next selected object not yet taken that can take it: the same kind, the same triangles (a Warp: rows and columns), the same parent.
- The dialog's **Paste onto** rows list source → target; pick another fitting object, or **Do not paste** to leave a shape out. Without the shape any selected object can take the channels.
- Every shape is written as it was copied, so pasting A onto B and B onto A swaps them.
*> A plain paste ({key:KEYFORM_PASTE}) uses the automatic pairing without a dialog.

@@ synthesize-corners | Automatic Generation of Four Corner Forms
@@ face-auto-edit | Auto generation of facial motion
Head turns, blinks, eyeballs, eyebrows, mouth shapes and lip sync are all generated when the PSD is imported; you do not have to make the up, down, left and right shapes by hand first. What you do is adjust the range with numbers and then correct a few places by hand.
## What is generated
- Head X / Y: the end and middle points combine into nine poses, based on the head tilt in the original art, with extra correction for the diagonals.
- The face is corrected with a lat-long grid and by region; eyes and brows, pupils, mouth and ears each have their own compensation or masking.
- Mouth: opening and mouth form are generated from the mouth shape settings, and upper and lower lip outlines can optionally be generated.
- Brows through the bangs: each brow the front hair covers gets a copy drawn just above the bangs at partial opacity and clipped to them, so it shows through the hair as in anime. The copy follows its brow through every deformer, form and edit; edit the brow, not the copy. Eyes through the bangs: the bangs fade over every part of the eyes - whites, irides and highlights, lashes - so the whole eye shows through the hair; closed-eye lines are copied over the bangs like the brows. New models have both on; models made before keep what they had (eyes off) until you turn them on.
- Front and back hair are separated from strong face deformation and each outputs its own sway parameter.
## Adjust the range
1. In the **Role components** section on the **World** page of the Modifiers panel, open the **Head turn** card and adjust the **Facial Feature Displacement Deformer** switch and **Head range**; the **Body pose** card holds **Body range**.
2. In the **Values** of each component card, fine-tune head turn, eyes, brows, mouth and hair (nose and ears are in Head turn); less common items are under **Advanced**.
3. After a change the model regenerates, and the manual edits you already made are merged and kept.
## Correct by hand
The generated facial keyforms can be edited further directly in Deform mode; for the method see [[how-to-edit]].
!> This is the result of a preset algorithm, not a 3D reconstruction. Unusual layering, wide hair strands, extreme angles and overlapping regions may need manual correction.
Related: [[model-presets]] · [[standard-parameter-list]]

@@ auto-generation-of-sway-motion | Auto Generation of Sway Motion
For parts like hair, accessories and skirt hems, select them and generate a sway in one step: PSD2Live automatically makes swaying keyforms at -1 / 0 / 1 of a new parameter and also generates a physics pendulum, so after export they swing with the head. The settings are saved with the project and can be reopened and adjusted at any time.
## Open
- In Object mode, right-click the selected mesh or warp deformer on the canvas and choose **Generate Swing...**; one that has already been generated shows **Edit Swing...**. The Hierarchy right-click has the same menu.
- In the Physics panel, **+ New** → **Swing for the selection…**.
When a mesh is selected, a fitting warp deformer is wrapped around it automatically; a rotation deformer cannot be the target.
## Edit
1. After opening, the canvas enters swing editing and a swing panel appears at the bottom left.
2. Drag the handles on the canvas: the dot at the tip sets the amplitude and upward curl, the diamond on the center line sets the softness, the two corners at the tip set enlarge / shrink and parallel, the hollow circle rotates the whole swing rectangle, and the hollow square changes the pivot edge.
3. In the panel choose the swing direction (left-right / up-down, both can be ticked), the segments (1–3), the auto estimate (strands / accessory / cloth), and whether to generate a physics pendulum.
4. **Preview Sway** to see the effect; **Generate** / **Apply** or Enter writes it, **Cancel** or Esc abandons it.
*> Compared with Cubism's feature of the same name, this one can also target a mesh, make up-down swings and segments, and use bottom parallel to make several bundles of hair sway together. If the parameter does not exist it is created automatically as `ParamSwing<id>`, and the pendulum is exported to physics3.json and cmo3.
## Regeneration and baking
The keyforms on the swing axis belong to the swing and are recomputed from the target's current shape on every rebuild; your manual changes on these keyforms are recorded as generated overrides (see [[generated-overrides]]). To take over completely by hand, click **Apply as Keyforms** to write the current form as ordinary keyforms and keep only the physics pendulum; **Delete** removes it together with the generated axis and the automatically created parameter.

@@ edit-vertices | Edit Vertices in Canvas Coordinates
In PSD2Live, dragging, the transform box and brushes always act on the position where a point is shown on the canvas; whichever deformer the object hangs under, the program converts it to the parent's local coordinates automatically. This is default behavior and needs no separate switch. When you need exact values, use **Precise Transform**.
## Precise Transform
1. In Deform mode, select the points to move.
2. Open **Precise Transform** in the **Tool Details** panel.
3. Enter the movement (canvas pixels), the scale ratio or the rotation angle, and click **Apply**.
Rotation and scaling are centered on the anchor of the transform box; when the target is a single rotation deformer, they are centered on its origin. The values act on the selected points as a whole; there is no per-vertex coordinate input.
{tutorial=TOOL_DETAILS}
## Reading values from the transform box
While dragging the transform box, the rotation angle, scale ratio or moved canvas pixels are shown beside the pointer; dragging the anchor changes the center, and it snaps when near the center, a corner or an edge midpoint.

@@ restore-deformer-shape | Restore deformer shape
When a deformer's (or mesh's) keyforms have gone wrong and you want to start over, **Restore every key to the default-key shape** puts each key of the object back to its shape at the parameters' defaults, as one history step that can be undone.
## How to use
1. Select the deformer or mesh in Deform mode.
2. Canvas right-click menu **Keyform → Restore every key to the default-key shape**.
*> Unlike Cubism no separate original shape is recorded: the object's current default-key shape (usually its shape when it was first keyed) is what it restores to. MCP: `keyform_apply op=reset all_keys=true`.

@@ glue | Glue
When two parts split apart at their seam as soon as they move, use glue to stick the vertices of the two meshes together. A glue point is one point shared by both meshes, drawn as a diamond on the canvas, and when it is selected or dragged the vertices on both sides always move together.
## Create
1. In Object mode, select two meshes and switch to Edit mode ({key:MODE_EDIT}).
2. Choose the **Glue** tool ({key:TOOL_GLUE}); it has three sub-tools below: Glue brush, Weight brush and Remerge ({key:PICK_OPTION_1}–{key:PICK_OPTION_3}).
3. Brush with the glue brush where they overlap; or press {key:FINISH_PATH} / click **Create glue** on the mode bar to glue the whole seam along the two outlines.
{tutorial=EDIT_MODE}
## Operations
| Operation | Effect |
|---|---|
| Glue brush | Where they overlap, makes the other mesh gain or slide a vertex (UV follows); only the mesh changes, not the picture |
| Alt + drag | Unglue |
| Weight brush | Shows and paints the pull weights of both sides in blue (A) / orange (B) |
| Remerge | Removes the existing glue on the brushed vertices and glues again by the weld tolerance |
| {key:GENERATE} | Glue the selected vertices on either mesh |
| Right-click **Remerge the whole seam** | Remove all glue between the two meshes and glue again along the outline |
*> The two meshes do not need aligned vertices beforehand: the glue brush adds or slides vertices on the other side so that both sides coincide.
!> Gluing is done on the rest shape (all parameters at their defaults). When a parameter is not at its default, the glue brush, Remerge and Create glue first move the parameter smoothly back to the default and then glue.
Limbs split by the skeleton and front/back layering get glue automatically, so you rarely need to handle it by hand.

@@ deformpath | Deform Paths
A deform path (experimental) lets you draw a curve along a part and drag the curve's control points, and the mesh deforms along the curve; it suits long hair, ribbons and tails. The path is only an editing aid attached to one mesh, the result is written as ordinary keyforms of that mesh, and it is not a deformer itself.
## Create and edit
1. Select an ArtMesh and choose **Deform Path** in the Hierarchy right-click menu, or press {key:TOOL_CREATE_DEFORM_PATH}.
2. Click on the ArtMesh to place at least two control points, press {key:FINISH_PATH} to confirm, {key:CANCEL} to cancel.
3. In Edit mode, adjust the path's binding position, influence width, hardness, closure and corners; the mesh does not move at this point.
4. Set the target pose in the Parameters panel, switch to Deform mode and drag the control points; the shape is written into the ArtMesh's keyforms.
Paths are divided into two groups, L2 / L3, each storing its own set; choose one in **Path edit level** in the placement panel or **Tool Details**, and change an existing path with **Edit Level** in the Inspector. The canvas shows only the paths of the current group. These two groups are unrelated to the Vertices / Bezier levels of a warp deformer.
## Export
`.cmo3` stores the path controller structure and the mesh keyforms; `.moc3` stores only the baked mesh keyforms, and the runtime does not run the path algorithm.
!> A mesh can have several paths, but one path cannot control several meshes. The dragging result has not been fully verified to match Cubism Editor, so check width, hardness and large-angle deformation yourself.

@@ skinning | Skinning
When a whole mesh that spans a joint (such as an arm that has not been split, or stockings) must bend smoothly with several bones, PSD2Live hands it to the skeleton: bind the mesh to bones, the weights are computed automatically, and on export they are baked into rotation deformers and mesh correction keyforms.
## Binding and weights
1. In Skeleton mode, choose **Edit Bones** ({key:TOOL_SKELETON_EDIT}) and bind the mesh to bones with the **Bind** sub-tool; meshes of one limb only need to be bound to one of its bones.
2. Weights are computed automatically over the whole limb: each vertex follows at most the two bones on either side of the joint, with a smooth transition within the **Joint band**.
3. To correct them, use the **Weights** sub-tool: select a bound mesh and a bone in the same limb, and paint with add, subtract, replace or smooth.
4. In **Tool Details** you can clean up and restore the automatic weights, or copy / mirror the weights to another mesh.
5. While posing, turn on **Show skin weights** under "View ▾" (or pin it to the display rail) to check the transitions at the joints by bone color.
{tutorial=SKELETON}
Vertices inside the joint band blend by angle and rotate around the joint, so the limb keeps its width when it bends.
*> Cubism's skinning converts the mesh directly into a chain of rotation deformers and you then edit on that chain; here the weights belong to the skeleton, and changing the bones bakes again. For the full description see [[skeleton]].

@@ skeleton | Skeleton
For a body, limbs, tail or wings that need to move, the skeleton is the fastest way to rig: the program infers the skeleton from the layers and binds the meshes, you adjust the joints and pose it, and on export it is baked into structures Cubism can run directly: a torso bend deformer, rotation deformers for the limbs, and correction keyforms for meshes that span joints. The exported model does not depend on this program.
*> Cubism Editor has no skeleton object; there, limbs are usually built by hand with chains of rotation deformers and skinning. The skeleton here is exactly the tool that generates those structures automatically.
## Create a skeleton
1. Enter Skeleton mode from the mode menu ({key:MODE_SKELETON}). When the project has no skeleton yet, click the center of the canvas or **Create skeleton** on the **Skeleton** tab.
2. The program infers the bones from layer tags and sides and binds them: arms to the same-side handwear, legs to legwear, feet to footwear, and tails and wings to their own layers.
3. Adjust with **Edit Bones** ({key:TOOL_SKELETON_EDIT}): **Edit** drags joints, **New Bone** and **Extrude** add bones, **Bind** binds meshes in batches, and **Weights** corrects skinning (see [[skinning]]).
4. In **Tool Details** you can copy, mirror, subdivide and merge bones; after selecting a bone on the **Skeleton** tab you can adjust the **Joint band** and the minimum / maximum angle.
5. Click **Done** to save as one history step. Switching to Pose Bones, changing mode or selecting another layer also saves; only **Cancel** discards this round of changes.
{tutorial=SKELETON}
!> Limbs drawn on the same layer for left and right are not bound; first split them with **Model → Split Layers by Mesh…** and then bind.
## Pose
In **Pose Bones** ({key:TOOL_SKELETON_POSE}), dragging a bone's body is FK and turns only that bone; dragging the tip or dragging while holding Shift is IK. **Tool Details** lets you set the IK chain length, iterations and bend direction, and **Pin selected tip**. Posing changes only parameter values and produces no history; **Reset Pose** zeroes everything. A good pose can be saved by name and applied again later or inserted into an animation.
## Generated structures and parameters
| Item | Description |
|---|---|
| Torso bend | `DeformSkelTorso`, the upper and lower body bend around the waist |
| Limbs, tail, wings | Rotation deformers `DeformSkel_<bone ID>` |
| Skeleton parameters | `ParamArmLA`, `ParamLegLA`, `ParamTail1..n` and so on, in degrees |
| Pose parameters | Squat, weight shift, tail flick, wing flap, knock-knees, bounce |
The motions a skeleton can play appear in the Animation panel; see [[builtin-motions]]. **Parameter sampling** at the bottom of the **Skeleton** tab sets the tolerance, the minimum angle interval and the per-mesh keyform limit, and is used to control the export size.
## Export notes
- Limb parameters that can be added are written as blend shape parameters when the export target is Cubism 5.0 or later; with a target below 5.0, everything is baked into ordinary keyforms and the poses on rotation deformers are dropped.
- The skeleton itself is not kept in Cubism Editor; if you will need to change the skeleton later, keep the `.psd2live` project.
Related: [[making-and-rotation-of-rotationdeformer]] · [[blend-shape]]

@@ generated-overrides | Generated Overrides
The keyforms of generators such as swing, simulation and skeleton are recomputed on every rebuild. Your hand-made changes on those keyforms are not washed away by a rebuild: they are recorded as "generated overrides" and merged with the new result after all generators have run. So you can safely correct shapes on a generated result and still adjust the swing, skeleton or model presets afterward.
## Which edits become overrides
- Keyforms that swing generates on the target warp deformer (when the swing parameter is not at its default).
- Baked simulation written back to the mesh keyforms as keyform axes.
- Keyforms written by skeleton skinning on a mesh whose parent you changed yourself.
Editing at the default value of a generated parameter is still an ordinary edit and changes the rest shape.
## Merge rules
| Case | Result |
|---|---|
| Points you did not change | Follow the new generated result |
| Points the generator did not change this time | Your values are kept |
| Both sides changed | Your values are kept and a conflict is reported |
## Viewing problems
When there are problems, the Inspector toolbar shows an **n override issue(s)** badge; the problems of the selected object are listed in **Generated overrides** at the top of the Inspector, and clicking the badge lists all of them.
- Conflict: some points were changed by both the generator and your edit, and your edit was kept.
- Not applied: the generated keyform no longer exists, the number of points of the generated shape changed, or the mesh was split and replaced.
!> Copying to or from a generated keyform, and adding or deleting keys on a generator's axis, cannot be recorded as overrides and are rejected. To edit a swing completely by hand, first click **Apply as Keyforms** in the swing panel.
Related: [[auto-generation-of-sway-motion]] · [[simulation]] · [[update-generated-rig]]

@@ multi-key | Multiple Keys Editing Function
Changes the opacity, draw order, multiply color and screen color of many keyforms of several objects at once, for example bringing the eyelashes in front of the bangs on every blink key together.
## How to use
1. Select the objects to change in Deform mode (several meshes can be selected).
2. Canvas right-click menu **Keyform → Multi-key editing…**.
3. **Keys to edit** lists each parameter's key values; click values off to narrow the range. The number of keyforms that will change is shown below.
4. Check what to change and set the values, pick a method and click **Apply**.
| Method | What it does |
|---|---|
| Overwrite | Each keyform takes the value |
| Add | The value is added to each keyform's own |
| Multiply | Multiplied with each keyform's own (draw order takes only overwrite and add) |
| Screen | 1 − (1 − own) × (1 − value), mostly for colors |
- The result is one history step. It writes channel keyforms as ordinary keyforms, not as generated overrides; each channel is written on the axes it already has, and on the object's shape keys only while it has no track.
*> Deformers have no draw order; with deformers in the dialog the draw order cannot be changed. MCP: `keyform_apply op=multi_key`.

@@ motion-mirroring | Motion Mirroring
For left-right symmetric motion, make one side only: make the shape for Angle X 30 and mirror it to -30, or mirror the left eye's shape to the right eye. The symmetric key and the opposite object are decided automatically from the parameter and layer semantics, the result is written as ordinary keyforms, and it can be undone.
## How to use
1. In Deform mode, select the object and set the parameter to the key to mirror.
2. Choose an operation in the canvas right-click menu **Mirror keyform**, or press {key:MIRROR_KEYFORM} to mirror directly to the symmetric key.
| Operation | Effect |
|---|---|
| Mirror to opposite key | Mirrors the current key to the symmetric key and switches to that key to show the result |
| Mirror from opposite key | Overwrites the current key with the symmetric key's shape |
| Mirror to (opposite object) | Writes to the same kind of object on the other side; parameters with L/R are swapped to the other side, and left-right parameters are negated |
| Flip in place | Flips the current key about the object's own center |
| Mirror all keys to the other side / Mirror all keys to (opposite object) | Handles all keys at once |
## How the program decides on symmetry
- Negated left-right parameters: the range is symmetric about the default and the parameter is the horizontal axis of a 2D link, or its ID ends in X or Z (AngleX, BodyAngleX, EyeBallX and so on).
- Opposite objects: first paired by layer semantics, then by L/R, Left/Right, 左/右 in the name.
- Only the object's own deformation is handled, and the parent's keyforms must be mirrored separately; when several layers are selected, each is mirrored in turn and committed in one history step.

@@ temporary-deform-tool | Temporary deform tool
The temporary deform tool lays a disposable lattice over the selected points (or the whole object); dragging its points bends those points together, for broad changes of shape. The lattice itself is not kept.
## How to use
1. Select a mesh or Warp in Deform mode (a Warp at the **Vertices** level), and the points to deform if only some.
2. Press {key:TEMPORARY_DEFORM} or choose **Temporary deform (lattice)** in the canvas right-click menu.
3. Drag lattice points: the points inside follow by bilinear interpolation, each release one history step. When the parameters are between keys a press first snaps them onto the keys, and when the shape changed (another key, an undo, another edit) a press lays the lattice again over it; such a press does not start a drag, so press again.
4. **Lattice** in the toolbar switches between 1×1 and 4×4 (laying the lattice again over the current shape); Enter, {key:CANCEL} or **Done** ends it.
*> Unlike Cubism there is only the temporary lattice (warp) mode; scale and rotate with the Transform tool {key:TOOL_TRANSFORM}, and bend along a path with deform paths.

@@ deform-brush | Deform brushes
For places that need broad, soft adjustment, such as hair and face contours, the deform brush pushes the shape around like a brush, much faster than dragging point by point. It changes only geometry, not the texture.
## How to use
1. Select the object, switch to Deform mode and choose the **Deform Brush** ({key:TOOL_BRUSH}); the same group also has the **Smooth Brush** ({key:TOOL_SMOOTH}) and the **Inflate Brush** ({key:TOOL_INFLATE}).
2. Drag on the canvas to push the shape. With no selection it acts on all points of the object; with a selection, only on the selected points. When hovering, a red circle marks the points that would be pushed on press; the bigger the circle, the stronger the effect.
3. {key:BRUSH_ADJUST_DRAG} drags to adjust the radius or hardness on the spot; you can also use {key:BRUSH_RADIUS_DOWN} / {key:BRUSH_RADIUS_UP} for the radius and {key:BRUSH_HARDNESS_DOWN} / {key:BRUSH_HARDNESS_UP} for the hardness. {key:CANCEL} cancels the current stroke.
{tutorial=DEFORM_MODE}
## Settings
| Setting | Effect |
|---|---|
| Brush tip | Circle, line or rectangle; {key:BRUSH_SHAPE_CYCLE} cycles through them |
| Radius / Hardness / Strength | The range of effect, the softness of the edge and the force of each push |
| Falloff | Smooth, sphere, root, inverse square, sharp, linear, constant, random |
| Connected Only | Affects only points connected to the mesh under the pointer |
The brush tip is chosen below the brush on the left toolbar; the other settings are in the tool options bar at the bottom left and the canvas right-click menu.
*> **Connected Only** suits brushing parts that sit close together separately, such as adjacent strands of hair or the upper and lower lips.
{tutorial=TOOL_DETAILS}

@@ blend-shape | Blend Shape
A blend shape parameter adds a shape difference on top of the object's existing keyforms, instead of multiplying with the keys of other parameters. When several parameters act independently, it greatly reduces the number of keyforms; the skeleton and simulation rely on exactly this to keep the export size down.
## Create a blend shape parameter
1. Create a parameter in the Parameters panel and choose **Blend shape** in **Parameter Properties** (the type can be chosen only on creation; the default range is 0…1).
2. In the Parameters panel, the keys of a blend shape are shown as square dots; 0 is the neutral base shape and cannot be moved or deleted.
3. Set the parameter to another key and change the mesh or deformer in Deform mode; the additive shape is then recorded.
The skeleton and simulation generate blend shape parameters automatically (skeleton parameters, the simulation's mode parameters); see [[skeleton]] and [[simulation]].
!> The editing workflow for blend shapes is still being completed: Cubism's weight limit settings and limit curves are not provided yet, and an existing parameter cannot be changed into a blend shape.
## Export
Blend shapes on meshes and warp deformers need an export target of Cubism 4.2 or later; those on rotation deformers and parts need 5.0 or later. When the target version does not support them, the export is downgraded and the report says so.

@@ limit-settings-for-blend-shape-weights | Limit settings for blend shape weights
@@ multiply-color-screen-color | Multiply Color/Screen Color
Without drawing another layer, you can tint, darken or brighten a part: Multiply Color darkens or tints an object, and Screen Color (Cubism's screen color) brightens it, which suits blushes, shadows and highlights.
## How to set
1. Select an ArtMesh or deformer.
2. In the Inspector, click the color swatch of **Multiply Color** or **Screen Color** to pick a color, or type a hex code.
3. The reset button at the end of the row restores the default (white for Multiply Color, black for Screen Color).
!> What the Inspector sets is a fixed color of the object, not varying with parameters; the Cubism way of setting different colors on each key is not provided in the interface for now.
*> When the export target version does not support Multiply / Screen colors, the export is downgraded and the report says so.

@@ apply-3d-rotation-expression | Application of 3D Rotation Expression
@@ apply-3d-rotation-expression-settings | Setting items for applying 3D rotation expressions
@@ convenience | Convenient editing function
@@ csv-export-import | CSV export/import
@@ template | About model templates
@@ applying-the-model-template | How to Apply Model Templates
@@ creating-exporting-the-model-template | Creating/Exporting Model Templates
@@ animation | Animation
@@ animation-preparation | Preparation for Animation Creation
Once the model is generated, it can already idle, blink, nod and shake its head; to make it do something else, make the motion right in the same project. Motions are saved in the project, go along with undo/redo and saving, and are written to `motion3.json` automatically on export.
## Where motions come from
- **Built-in motions**: made automatically when the model is generated, according to the model presets; you only adjust a few sliders. See [[builtin-motions]].
- **Custom motions**: in the **Animation** panel click **New Motion** and choose **Blank Motion** to start from scratch, or **Copy From Preset** to start from a built-in motion.
## Get started
1. Click the **+** at the right of the workspace tab bar and choose the **Animation** preset: the motion list is at the left of the preview canvas, the animation editor is below, and the Parameters panel is on the right.
2. In the **Animation** panel, click the edit button on a motion row (or right-click and choose **Edit**) to open the motion in the animation editor.
3. Pose the model in the Parameters panel and record it as keyframes in the animation editor; for how, see [[timeline-basic-operation-timelinepalette]].
{tutorial=ANIMATION}
*> If you are used to Cubism Animator: there are no scenes or model tracks here, and the open motion drives the current model's parameters directly. When CMO3 is exported, the motions are saved as `.can3` files of the same name, which you can keep editing in Animator.
@@ builtin-motions | Built-in Motions
Built-in motions let a freshly generated model move right away: the program makes common motions such as idle and blink according to the model, without a single keyframe from you, and on export they are written as `motion3.json` just like hand-made motions.
## Which motions there are
| Group | Motions | Description |
|---|---|---|
| Basic motions | Idle Loop, Blink, Nod, Shake | Any model can play them; the Idle Loop loops |
| Skeleton motions | Tail Swing, Crouch, Weight Shift, Shy, Wave, Head Tilt, Cheer, Leg Kick, Happy Sway, Cute Idle | A skeleton is required; motions the current skeleton cannot do are not listed; Cute Idle loops |
Whether the two groups are generated is decided by the **Basic motions** and **Skeleton motions** checkboxes in the **Motions** group of the model presets; see [[model-presets]]. For making the skeleton see [[skeleton]].
## Adjust
1. In the **Built-in Motions** folder of the **Animation** panel, use the checkbox at the left of each row to decide whether the motion is exported.
2. Click a motion row to expand its knobs. The idle has **Sway**, **Breath**, **Head**, **Speed** and a **Blink** switch; the blink has **Closure**, **Speed** and **Double blink**; nod and shake have **Amplitude**, **Speed** and **Count**.
3. Click the play button at the right of the row to see the effect in the preview canvas.
{tutorial=ANIMATION}
## After editing keyframes by hand
After you change the keyframes of a built-in motion in the animation editor, the row is marked **Edited** and the knobs are disabled until you restore the default. The right-click menu offers:
- **Duplicate as Custom Motion**: saves the current content as a custom motion and leaves the original built-in motion unchanged.
- **Reset to default**: discards the manual changes and returns to what the knobs generate.
- **Delete**: no longer exported; you can bring it back later from **Restore deleted presets** in the **New Motion** menu.
!> Parameters driven by physics are not written into the idle animation, so that the motion and physics do not fight over the same parameter.
@@ loading-model-and-placement | Model loading and placement
@@ eye-blink-settings | Eye Blinking Setting
Automatic blinking needs no separate setup: the built-in blink motion handles the blink animation in the editor and in the export, and on export `model3.json` also gets an `EyeBlink` group, which the SDK's automatic blinking relies on.
## How to set
1. Make sure **Basic motions** is ticked in the **Motions** group of the model presets.
2. In the **Animation** panel tick **Blink**; click the row to expand it, adjust **Closure** and **Speed**, and tick **Double blink** to make it blink twice in a row.
3. The idle animation includes blinking; when you do not want it, turn off **Blink** in the idle animation's knobs.
When both the basic motions and the blink motion are enabled and the export is not mesh-only, the `EyeBlink` group contains `ParamEyeLOpen` and `ParamEyeROpen`.
*> Cubism users set the interval in the blink effect of Viewer or Animator; there is no such setting here, and the rhythm of the blinking is decided by the blink motion itself.
@@ generating-scene-from-audio-file | Creating Scenes with Background Music and Audio
@@ placement-background-and-grouptrack | Background Placement and Group Tracks
@@ management-of-scenes | Scene management and settings
@@ change-pose | Create Motion with Pose Switching
@@ timeline | Manipulating the timeline
@@ timeline-basic-operation-timelinepalette | Displaying and operating the timeline palette
The animation editor is where motions are made: one track per parameter; move the playhead, set the pose and key it, then fine-tune in the dopesheet or the curves.
## Open
Open the **Animation Editor** from the **Windows** menu, or use the **Animation** workspace preset directly. Edit a motion from the **Animation** panel, or pick one with **Choose a motion…** on the editor toolbar.
## Make a motion
1. Click **Track**, then search for and add the parameters to move. A track row shows the value at the playhead and has buttons for **Key at playhead** and for deleting the track.
2. Click or drag on the ruler to move the playhead to the target time, and set the pose in the Parameters panel.
3. Press {key:MOTION_KEY_POSE} (**Key Pose**) to record the current values of all tracks at the playhead; or turn on **Auto Key** ({key:MOTION_TOGGLE_AUTO_KEY}) so that changing a parameter keys it automatically, and parameters without a track are added automatically too.
4. Press {key:MOTION_PLAY_PAUSE} to play and check; the canvas shows the result live.
{tutorial=ANIMATION}
## Toolbar
| Item | Description |
|---|---|
| New Motion | Creates a blank motion and opens it |
| Back to start / Play | The playhead returns to 0; play or pause |
| Time / Duration | The playhead time and the motion length (0.1–600 seconds) |
| FPS | The motion frame rate (1–120), which decides the step for frame-by-frame movement and snapping |
| Loop | Export as a looping motion |
| Snap to Frames | Align to whole frames when dragging keyframes and the playhead |
| Dopesheet / Curves | Switch between [[timeline-basic-operation-dopesheet]] and [[grapheditor]] |
| Hide Shy / Bookmarks | See [[timeline-basic-operation-shy]] and [[parameter-bookmark]] |
| Trajectory | See [[locus-of-tracks]] |
| Marker / Close loop | See [[marker]] and [[loop-editing-support]] |
Each track row also has bookmark, shy and freeze buttons; see [[parameter-bookmark]], [[timeline-basic-operation-shy]] and [[freeze]].
## Shortcuts (when the timeline has focus)
| Action | Key |
|---|---|
| Previous / next frame | {key:MOTION_PREV_FRAME} / {key:MOTION_NEXT_FRAME} |
| Jump to start / end | {key:MOTION_GO_START} / {key:MOTION_GO_END} |
| Fit the timeline | {key:MOTION_FIT_VIEW} |
| Zoom / pan the timeline | {kbd:Ctrl+Wheel} / {kbd:Shift+Wheel} |
| Add / previous / next marker | {key:MOTION_ADD_MARKER} / {key:MOTION_PREV_MARKER} / {key:MOTION_NEXT_MARKER} |
| Hide shy tracks | {key:MOTION_HIDE_SHY} |
| Close the loop | {key:MOTION_CLOSE_LOOP} |
@@ timeline-basic-operation-dopesheet | Basic Dope Sheet Operation
The dopesheet lays out each track's keyframes as a row of dots and is best for arranging timing: shifting the rhythm, copying a stretch of motion, changing the interpolation in one go. Click **Dopesheet** on the animation editor toolbar to switch here.
| Operation | How |
|---|---|
| Add a keyframe | Double-click empty space on the track |
| Select | Click; {kbd:Shift} adds, {kbd:Ctrl} toggles; drag in empty space to box-select; {key:MOTION_SELECT_ALL} selects all |
| Move | Drag the selected keyframes (they align to frames when **Snap to Frames** is on) |
| Copy / paste | {key:MOTION_COPY_KEYS} / {key:MOTION_PASTE_KEYS} |
| Delete | {key:MOTION_DELETE_KEYS}, or **Delete Keys** at the bottom |
With keyframes selected, the bottom shows **n keys selected**, and you can type the **Time** and **Value** directly and set the interpolation for all of them. One drag or one input is recorded as one history step.
@@ parameter-bookmark | Parameter Bookmarks
Once a model has many parameters, bookmark the ones you use: the animation editor can show only their tracks, and they lead the list when you add a track.
## Use
| Action | How |
|---|---|
| Bookmark / remove | The bookmark button on a track row, or click the row and press {key:MOTION_TOGGLE_BOOKMARK} |
| Bookmarks only | **Bookmarks** on the toolbar shows only the tracks of bookmarked parameters |
| Add tracks | In the **Track** menu bookmarked parameters come first, marked ★; **Add bookmarked tracks** adds every bookmarked parameter that has no track yet |
Bookmarks belong to the whole project and every motion shares them; they are saved with the project but are not model data, so they take no history step and never export.
*> Unlike Cubism there is one set of bookmarks: a parameter is bookmarked or not.
@@ timeline-basic-operation-shy | Shy function
Mark the tracks you don't need to see as **shy**, turn on **Hide Shy**, and the timeline keeps only the tracks you are editing. Shy tracks still play and export.
## Use
1. Click the shy button (a lowered eye) on a track row, or click the row and press {key:MOTION_TOGGLE_SHY}.
2. **Hide Shy** on the toolbar ({key:MOTION_HIDE_SHY}) hides them or shows them again; the track list's header says how many are hidden.
Shy tracks are kept per motion and saved with the project; they take no history step.
@@ freeze | Freeze function
Freezing holds one track at a fixed value while the motion plays or the playhead moves, so you can check the rest on its own: stop the blink, say, and watch the head turn.
## Use
1. Move the playhead to a time where the track has the value you want.
2. Click the freeze button (a snowflake) on the track row, or click the row and press {key:MOTION_TOGGLE_FREEZE}. The track freezes at its value at the playhead: the row shows the value, the dopesheet hatches the track and the curves view draws the value as a dashed line.
3. Click again to unfreeze.
While frozen the track's keys cannot be selected, dragged or keyed, and Auto Key passes it by; the keys themselves stay as they are and so does the exported motion. Frozen tracks are kept per motion and saved with the project; they take no history step.
*> Freezing applies to the motion open in the animation editor; keys edited through MCP are not held back by it.
@@ timeline-basic-operation-repeat | Animation of parameter repeat
For a parameter set to [[repeat]], a motion's keys may go past the parameter's range, for motions that keep turning: a wheel with a range of 0–360 keyed 0 at 0 s and 720 at 1 s turns twice a second.
- Keys may go up to 64 turns past the range; the curves view draws a dashed line at each whole turn.
- The preview shows the value wrapped into the range; the exported `motion3.json` keeps the keyed values and the runtime wraps them.
- Keying on the timeline (auto-key too) writes the turn nearest the curve's own value, never the 180 the preview shows for 540.
- When [[loop-editing-support]] closes a looping motion, such a parameter ends on the next whole turn in the way it is going (0→170 closes at 360), so the spin never winds back; past 64 turns it fails and changes nothing.
!> Turning the parameter's repeat off or narrowing its range clamps keys past the range to its ends.
@@ grapheditor | Graph Editor
The curve view draws a track as a curve of the value over time, used to tune easing and overshoot. It shares the same area, selection and shortcuts as the dopesheet; click **Curves** on the animation editor toolbar to switch.
## Operations
1. Click a track row on the left to show only that curve; click **Show all** to bring back all curves.
2. Dragging a keyframe changes both its time and its value; when only one curve is shown, double-clicking in empty space adds a keyframe there.
3. A keyframe on a Bezier segment has handles on both sides; drag them to adjust the acceleration and deceleration, and **Reset Handles** at the bottom restores the default shape.
4. {kbd:Ctrl+Wheel} zooms time, {kbd:Ctrl+Shift+Wheel} zooms the value, and {kbd:Wheel} pans up and down.
## Interpolation
| Interpolation | Effect |
|---|---|
| Linear | Changes at a constant speed between two keys |
| Ease (Bezier) | The default; the handles control acceleration and deceleration |
| Stepped | Holds the previous key's value and jumps at the next key |
| Inverse stepped | Jumps to the next key's value immediately and holds it |
*> The handle time is limited to within its segment (consistent with `AreBeziersRestricted` in `motion3.json`), so the curve shape does not change after export.
@@ marker | Marker Function
Markers are named, coloured times on the timeline, to line keys up with a beat, a line of dialogue or another motion.
## Use
| Action | How |
|---|---|
| Add | The marker button on the toolbar or {key:MOTION_ADD_MARKER} adds one at the playhead (on a whole frame while **Snap to Frames** is on) |
| Move | Drag its flag on the ruler |
| Edit | Click its flag: the playhead jumps there and its label, time, colour and **Delete Marker** open |
| Jump | {key:MOTION_PREV_MARKER} / {key:MOTION_NEXT_MARKER} go to the previous / next marker |
Markers are kept per motion and saved with the project; they take no history step and never export: they are not `motion3.json` events (for user data see [[userdata]]).
@@ locus-of-tracks | Track Trajectory
The trajectory draws, on the preview canvas, the path the selected object takes through the motion: a dot for every frame and a ring at the playhead's frame, to check that a movement's arc is smooth.
## Use
1. Open the motion in the animation editor and turn on **Trajectory** on its toolbar.
2. Select one or more layers (the centre of their meshes is followed) or a deformer (a rotation deformer's pivot, a warp deformer's middle).
3. Look at the preview canvas; the path follows as you change keys or move the playhead.
The path follows the motion's keys, frozen tracks held at their value, without physics or mouse tracking.
@@ template-making-and-management | Animation Templates
@@ about-fade | About Fading
Fading decides how smoothly the runtime switches between motions: the new motion takes effect gradually during the fade-in time and the old motion fades out during the fade-out time, so the pose does not jump suddenly.
- Custom motions have **Fade In (s)** and **Fade Out (s)**, both 1 second by default, exported to `FadeInTime` / `FadeOutTime` in `motion3.json`.
- Built-in motions that have not been edited by hand do not write fade times; the runtime uses its defaults.
Fading only matters when switching motions in an application; playing a single motion on its own in the editor shows no difference. For how to set it see [[fade-setting]].
@@ fade-setting | Fade value setting
Fading is set per motion in the **Animation** panel.
1. In the **Custom Motions** folder of the **Animation** panel, click the motion row to expand its settings.
2. Enter 0–5 seconds in **Fade In (s)** and **Fade Out (s)**.
3. After export they are written into that motion's `motion3.json`.
*> To set fading on a built-in motion, first make a copy with **Duplicate as Custom Motion** in the right-click menu.
!> Fading applies to the whole motion; you cannot set a separate fade for a single parameter curve as in Cubism.
@@ notes-for-sdk | Precautions When Embedding the SDK
When a Live2D model is exported, all motions are written at once, and the groups and file names are decided automatically according to the table below. Check here before calling motions in your application.
## Files and groups
| Motion | Motion group | File name |
|---|---|---|
| Idle Loop and other looping motions (including looping custom motions) | `Idle` | `<model name>.<motion>.motion3.json` |
| Other built-in motions | Same as the motion name (such as `Blink`, `Nod`) | For example `<model name>.blink.motion3.json` |
| Custom motions that do not loop | The motion name | Same as above |
All motions are written into `Motions` in `model3.json`; `Groups` gets `EyeBlink` (when blinking is enabled) and `LipSync` (`ParamMouthOpenY`).
## Notes
- Only parameters that exist in the model are written; tracks pointing to a deleted parameter are not exported.
- Bezier handles are limited within the segment (`AreBeziersRestricted: true`), so every SDK version plays them correctly.
- Parameters driven by physics are not written into the idle animation.
- Motions unticked in the **Animation** panel are not exported; mesh-only export writes no motions.
- A motion's events are written into the `UserData` of that motion's `motion3.json`; see [[userdata]].
- Expressions are written as `<model name>.<expression ID>.exp3.json` and listed in the `Expressions` of `model3.json` in the order of the expression list; when there are pose groups, `<model name>.pose3.json` is written too. See [[setting-and-exporting-facial-expressions]] and [[pose-setting]].
@@ parameter-controller | Parameter Controller
@@ about-the-parameter-controller | About the parameter controller
@@ parameter-controller-target-setting | Controller target settings
@@ form-animation | Form animation (FA)
@@ about-form-animation | About Form Animation (FA)
@@ create-form-animation | Create a form animation (FA)
@@ form-animation-time-remapping | Adjusting the Display Timing of Form Animations (FA)
@@ adjustment-mode | Adjustment Mode
@@ form-animation-animation-preview | Animation preview
@@ serial-number-image-track | About Image Sequence Track
@@ limited-animation | Frame Step
@@ physics-operation-bake | Bake Animation from Physics
@@ motion-sync-bake | Motion-sync Animation Bake
@@ loop-editing-support | Loop editing support
A looping motion (an idle, a walk, a spin) has to end where it starts, or every cycle jumps. With **Loop** on, the timeline shows the seam and mends it.
## See the seam
- A band along the foot of the ruler marks the loop, from 0 to the end; outside it the cycles before and after are drawn faintly, curves and keys, so you see at once whether the seam is smooth.
- A track whose end differs from its start shows its value in the warning colour, and the dopesheet marks the end of its row.
## Change the loop
Drag the grip at the end on the ruler to change the loop's length, the same as the toolbar's duration; shortening it removes the keys past the end.
## Close the loop
The close-loop button on the toolbar ({key:MOTION_CLOSE_LOOP}) mends every track that is not frozen at once: it keys 0 and the end, the end taking the start's value ([[repeat]] parameters whole turns on in the way they go), and where the segment into the end eases, the end arrives at the slope the start leaves with, so the curve meets itself smoothly. This is one undoable history step.
*> The loop is the whole motion: `motion3.json` cannot loop part of one.
@@ export | Export
@@ exporting-images | Image and video export
To post a model's motion to social platforms, turn it into stickers or put it in a video, you can export an image sequence, an animation or a video directly. The picture is drawn by the editor's own renderer, with physics advanced between frames, and no Cubism SDK is needed.
## Steps
1. Choose **File → Export as**, and pick a format in the **Images and video** group.
2. Set the **Motion** (the first motion by default, or the rest pose when there are no motions), **FPS**, **Size** (long edge in pixels, default 1024), **Background** and **Physics**; GIF also has **Dither**.
3. Choose an **Output folder** and click **Export**. The files are written into a "<project name>-<format>" folder under the output folder, and when finished the **Export complete** window lists what that format cannot keep.
## Formats
| Menu item | Output | Description |
|---|---|---|
| PNG sequence… | Numbered PNG frames | With transparency |
| Sprite sheet… | PNG laid out in a grid + JSON | Rejected above 16384 px |
| GIF… | Animated GIF | 256 colors, 1-bit transparency |
| APNG… / Animated WebP… | Animated PNG / WebP | WebP uses lossy compression |
| MP4 video… | H.264 | No transparency, composited onto the background color (white by default) |
| WebM video (with alpha)… | VP9 | Transparency needs libvpx decoding |
| QuickTime ProRes 4444… | MOV | With transparency |
!> Video, APNG and WebP need ffmpeg: the environment variable `PSD2LIVE_FFMPEG`, the ffmpeg bundled with the installer and the ffmpeg on PATH are used in that order, or you can enter a path in the **ffmpeg** field of the window.
*> For a still layered image, use **PSD at a pose…**; see [[other-formats]].
@@ export-moc3-motion3-files | Data for Embedded Use
To put a model into an application, streaming software or a game, you export embedded data: `.moc3`, `model3.json`, physics, motions, display names and textures. PSD2Live writes everything at once in one window and, when needed, also writes a `.cmo3` that you can keep editing in Cubism Editor.
## Steps
1. Choose **File → Export Live2D model…** ({key:GENERATE}).
2. Under **Formats:** tick **moc3**, **cmo3**, **can3** (writes the motions along with cmo3) and **json** (project metadata report). With **Mesh Only** ticked, only the mesh is exported, without motions or physics.
3. In **SDK target** choose the runtime version (from Cubism 3.0 to the latest). When the chosen version does not support features already used in the model, the window lists what will be downgraded.
4. Adjust the **MOC3 bake options** as needed: Export hidden parts, Export hidden art meshes, Export guide image parts, Include physics3.json, Include cdi3.json, Include userdata3.json, and **Pixels / unit** (automatic by canvas width by default).
5. Choose an **Output folder** and click **Generate & Export**.
{tutorial=BASIC}
## Output files
| File | Contents |
|---|---|
| `<name>.moc3` | Model data |
| `<name>.model3.json` | Model settings, referencing the files below |
| `<name>.physics3.json` | Physics; `Fps` is the project's physics frame rate |
| `<name>.<motion>.motion3.json` | One per motion; see [[notes-for-sdk]] |
| `<name>.<expression ID>.exp3.json` | One per expression; see [[setting-and-exporting-facial-expressions]] |
| `<name>.pose3.json` | Pose groups, written only when there are groups; see [[pose-setting]] |
| `<name>.cdi3.json` | Display names of parameters, parts and meshes |
| `<name>.userdata3.json` | Mesh user data, written only when there is some; see [[userdata]] |
| `<name>.<width>/texture_00.png` etc. | Texture atlas pages; see [[texture-atlas-edit]] |
| `<name>.cmo3` / `<name>.can3` | Cubism Editor project and Animator project |
What is exported is the currently committed model; it is not regenerated from the PSD. The Cubism SDK preview in the editor and the moc3 export are the same compilation, so what you see in the preview is the export result.
!> The GUID of a cmo3 differs on every export, and a `.can3` pairs only with the `.cmo3` from the same export; keep the two in the same folder.
@@ other-formats | Exporting Other Formats
The same model can be exported directly for VTube Studio, the web, Unity and so on, without first exporting embedded data and then going through each platform's SDK. Every format starts from the same model data, and after export the window lists what it cannot keep.
## Steps
1. Choose **File → Export as** and pick a format in the three groups **Runtimes and players**, **Images and video** and **Layered PSD**.
2. In the window that opens, adjust the settings for that format, choose an **Output folder** and click **Export**.
3. The files are written into a "<project name>-<format>" folder under the output folder, with a `<name>.<format>.report.json` loss report beside it.
## Runtimes and players
| Menu item | Output | Main limits |
|---|---|---|
| VTube Studio… | The full set of moc3 files + `.vtube.json` | Face tracking maps only standard parameters; one hotkey per motion and one toggle hotkey per expression |
| PSD2Live runtime rig… | `.p2lrt` | Without the data the editor attaches; optional compression, stripping names and so on |
| Web player… | `index.html`, the player script, the WebAssembly runtime and `.p2lrt` | Must be accessed over http |
| Unity prefab… | `.p2lunity`, textures and an install script | Needs Unity 6.0 or later; parameters are baked into Blend Shapes and combinations are approximated additively; masks and physics are not written |
## Layered PSD
- **PSD at a pose…**: poses the model by a motion's moment or by given parameter values, one layer per visible mesh; deformers and parameters are not kept.
- **Source PSD…** (with generated layers): exports the project's source layers, optionally at 1× or an upscaled ratio, and can include the automatically split layers, so you can go back to your drawing software for fine touch-ups (also available with {key:REEXPORT_PSD}).
For images and video see [[exporting-images]].
@@ userdata | User Data and Events
User data is a piece of text attached to an ArtMesh that an application can read, for example to mark clickable regions.
## How to set
1. Select an ArtMesh.
2. At the bottom of the **Inspector**, expand **User Data** and type the text. When you finish typing it is recorded as one history step and can be undone.
## Export
- User data is saved with the project and written into the exported `.cmo3`.
- When exporting MOC3, if **Include userdata3.json** is ticked (the default) and some mesh has user data filled in, `<name>.userdata3.json` is written and referenced in `model3.json`; without user data this file is not generated.
## Motion events
An event is a piece of text at a moment in a motion: when the SDK plays to that moment it hands the text to the application, for example to play a sound at the top of a wave.
1. In the Animation panel, expand the settings of a custom motion.
2. Under **Events**, click **Add event at playhead**; drag the playhead in the Animation Editor first to pick the moment.
3. Change the time or text; click x to delete. Each change is recorded as one history entry.
On export the events are written into the `UserData` of that motion's `motion3.json` (`UserDataCount` and `TotalUserDataSize` are updated too), and into the matching frames in the `.can3`. All events and mesh user data can be reviewed in one place in [[userdata-event-information]].
@@ physics-playlist | Physics Settings and Playlists
@@ switch-viewer-setting | Physics Settings
Physics is part of the project: in the **Physics** panel you tune while pulling on the model, changes go into the undo history, and on export they are written to `physics3.json` and `.cmo3` automatically, with no separate Viewer and no separate physics file to save.
## Open the physics settings
1. Click the **+** at the right of the workspace tab bar and choose the **Physics** preset: the preview canvas takes half on the left, and the Parameters panel and the Physics panel follow on the right.
2. You can also add the **Physics** panel to any workspace from the **Windows** menu.
3. The toolbar at the left of the preview canvas has the physics switch and **Physics FPS** (30 / 60 / 90 / 120 / Unlimited, default 60). The physics frame rate is a project setting and is also written into the exported files.
{tutorial=PHYSICS}
@@ physics-operation | About Physics
Physics lets hair, accessories and clothing swing with inertia as the head and body move. PSD2Live uses the same pendulum model as Cubism, and the computation corresponds item by item to the Cubism Native Framework, so the sway you see in the editor is the sway in the SDK.
## Mechanism
- **Input**: parameters such as head and body angles push the root of the pendulum (Position X), or tilt the direction of gravity (Angle).
- **Pendulum**: a chain of pendulums hanging from the root, moving according to length, sway, reaction speed and convergence speed.
- **Output**: a pendulum's swing angle multiplied by a scale is written into an output parameter.
Physics changes only parameter values; the shape of the swing comes from the keyforms on the output parameter. If the output parameter has no end-point shapes made, the parameter moves but the model does not.
## Where physics groups come from
Most physics groups need no manual building: front hair, back hair and jelly eyes come from the model presets, skeleton, swing and simulation baking also generate their own groups, and you can create them by hand as well. For managing them see [[physics-group-information]].
@@ physical-operation-setting | How to Set Up Physics
Select a physics group in the **Physics** panel; below it come, in order, the pendulum canvas, the response curve, the pendulum settings, the inputs, the outputs and the advanced settings. The quickest way to tune is to drag right on the pendulum canvas and judge the effect from the response curve and the max output.
## Pendulum canvas
| Operation | Effect |
|---|---|
| Drag left and right in empty space | Pulls the model; it returns to rest after you release |
| Drag the ring at the end of a pendulum up and down | Adjusts that pendulum's length; click to select it |
| Drag an output label on the right next to a pendulum | Changes it to read that pendulum's angle (Pendulum #) |
| Click an output label | Shows the range sector; drag the sector's edge to adjust the scale |
The response curve under the pendulums shows the reaction to a 1-second pull to the right and then release, so the overshoot and convergence of different settings can be compared directly.
{tutorial=PHYSICS}
## Settings
1. **Preset**: pick a preset and click **Import** to replace all pendulums; **Add…** saves the current pendulums as your own preset.
2. **Edit pendulum**: choose **All** or a particular number to decide which pendulum the sliders below act on; you can add, delete and move pendulums up and down.
3. Sliders: **Total length / Length**, **Sway**, **Reaction**, **Convergence**, with the same names and meanings as in Cubism Editor.
4. **Inputs**: use **Add** to add a parameter, choose **Position X** or **Angle**, and set the **Weight** and reversal.
5. **Outputs**: set the **Pendulum #**, **Scale** and reversal. **Max output** shows the percentage at which the output reaches the parameter's end points, and **Fit scale to max** brings it to 100%.
6. **Advanced**: the pendulum settings table, output details and **Input normalization**.
After you let go, each drag or input is recorded as one history step.
*> Outputs have only the angle type: an output of the Position X type is always 0 in the Cubism runtime.
@@ physics-group-information | Edit Physics Group
The list at the top of the **Physics** panel shows all the model's physics groups. Groups built by generators update automatically with their source, and the places you changed are kept.
## The physics group list
- Sorted by calculation order with numbers; the checkbox enables or disables a group, and a disabled group is not exported.
- Each row shows its source (preset, skeleton, swing, simulation, custom and so on) and its state: **Modified**, **Invalid** (parameter missing, no inputs or outputs and so on), **Replaced** (the same output parameter is already driven by another group, so this group is not exported).
- Move up / move down in the title bar adjusts the calculation order: when one group reads another group's output, the group being read must come first.
## New
Click **New**:
- **Blank pendulum**: by default the input is the head and body sway, and there is no output yet.
- **Duplicate as new group**: duplicates the selected group with the output parameters cleared.
- **Import physics3.json…**: each group in the file becomes a custom group, and groups with the same ID are replaced.
- **Swing for the selection…**: creates a swing for the mesh or warp deformer selected on the canvas; see [[auto-generation-of-sway-motion]].
## Change a generated group
A generated group can be edited directly; after editing it is marked **Modified**, and later changes at the source will not overwrite your changes; **Reset to generated** in the **More** menu returns to the generated version. A custom group can be **renamed** or **deleted**.
{tutorial=PHYSICS}
@@ simulation | Cloth and Hair Simulation
Skirt hems, coat hems and long hair are hard to make feel like cloth with just a few pendulums. Simulation runs a real 2D cloth and hair simulation on the mesh in the editor and then **bakes** the result into parameters, keyforms and pendulums that Cubism can play, so the exported model moves in any Cubism runtime.
## Start with the model presets
The **Physics & Simulation** group of the model presets can generate simulation for common parts in one step: change the dropdowns for front and back hair to **Simulate**, or tick **Clothing**, and the program builds the physics bodies, writes the weights and bakes in the background. Afterwards you can keep adjusting everything in the simulation card of the target mesh in the Modifiers panel; see [[model-presets]].
{tutorial=SIMULATION}
## Build a physics body by hand
1. Select the mesh of the skirt or hair on the canvas, choose **Simulation · Cloth** or **Simulation · Hair** in the **Add** menu of the Modifiers panel. Several meshes in one physics body are simulated together, and it becomes a simulation card in the stacks of those meshes.
2. Specify the pins: switch to the canvas's Simulate mode ({key:MODE_SIMULATE}) and paint pins with the **Weight brush** or **Weight gradient**; glue connected to the waist can also be set to **Pin** in the **Glue** section, see [[glue]].
3. Turn on **Reference sim** in the card's **Compare** section, turn the head and shake the body, and change the preset or fine-tune the values in the **Material** section until the sway looks natural.
4. Click **Bake** in the card's **Bake** section, or **Bake all** in the **Simulations** section on the **World** page. Baking runs in the background, usually takes a few seconds, and is committed as one undo step when done.
5. Turn off **Reference sim** and go back to **Export**: what the canvas plays is the exported model's motion, which you can compare with the reference sim.
## Vertex weight groups
A weight group tells the simulation where to fix, and where to be stiffer or heavier. It exists only in this program and is not written into the exported files.
| Kind | Effect |
|---|---|
| Pin | Vertices held by the rig; 1 follows the binding completely, 0 leaves it entirely to the simulation |
| Stiffness | Scales stretch and bend stiffness per vertex, making a stiff root and a soft hem |
| Shape retention | Scales the spring-back toward the original shape per vertex, holding up a flared skirt or an upturned hair tip |
| Mass | Scales mass per vertex; the heavier, the clearer the lag and overshoot |
| Damping | Scales motion decay per vertex, suppressing jitter at edges and corners |
## Material, inputs and baking
- **Material**: cloth has cotton, silk, chiffon, wool, denim, leather and stretch fabric; hair has hair, fine soft hair and coarse stiff hair; after choosing, each value can still be fine-tuned.
- **Inputs**: the parameters that make the object sway, filled in automatically with the head and body angles and so on when created; baking is impossible while the list is empty.
- **Bake**: **Modes** (1–3, default 2) decides how many parameters are generated, **Key points** decides the number of keyforms per parameter, and **Exaggeration** enlarges the sway without needing to bake again. After baking, R² and the error are shown.
- The baked pendulums appear in the **Physics** panel with the source "Simulation"; the parameters appear in the Parameters panel, named `ParamSim…`.
!> After changing settings, the mesh, the weights or what comes before it in the stack, the bake shows **Stale**, and the export keeps using the old result until you bake again. The reference sim itself is never exported.
@@ about-playlists | About Playlist
@@ playlist-settings | How to set up a playlist
@@ external | External Application Integration feature
@@ external-application-integration | External Application Integration
PSD2Live has a built-in local MCP service. Once it is on, AI hosts such as Claude Code and Codex, or your own scripts, can read and edit the current project: import, change the rig, tune physics, make motions, save and export, all through the same commands as the interface, so they also enter the history and can be undone.
## Enable and connect
1. Open the **MCP** page of **Settings** ({key:OPEN_SETTINGS}).
2. Under **Service**, tick **Enable the MCP service**, set the **Port** (default 23871), the **Access token** (it can be **Regenerate**d) and the **Tools**, and click **Apply and restart**.
3. Under **Connect a host**, copy the command or configuration for your host; you can also click **Copy setup prompt** and give the prompt to the host so it configures itself.
4. After the host connects, first call `workspace_inspect` to read the current project's object IDs and state.
## Tool sets
| Tool set | Suited for |
|---|---|
| Compact (recommended) | Most hosts. Operations of one family are merged into one tool and the rest are called through `workspace_call`; the schema is compact, and background tasks can return their result directly |
| Full | Hosts that load tools on demand. One tool per operation, with the full schema |
All operations can be called under either tool set, with exactly the same execution and validation.
## Notes
- The service runs only on this machine, using Streamable HTTP and Bearer token authentication. The token can edit your workspace and is stored only in the configuration of the host on this machine; after changing the port or token, update the configuration of the connected hosts to match.
- For hosts that support only Stdio, use Python 3 to run `mcp_proxy.py` in the repository root to bridge.
- When no window is open, the command-line `mcp` subcommand can start the MCP endpoint on its own.
@@ external-application-integration-api | Live2D Cubism Editor External API Integration
When writing scripts or troubleshooting host calls, you need to know the calling conventions of MCP operations. Every operation has strict request and result schemas, which you can list with `workspace_list_operations` and inspect field by field with `workspace_get_operation`.
## Calling conventions
- Put the arguments in `{"request": {...}}`.
- Modifying operations need `state` (from `workspace_inspect` or the previous result); a conflict is reported when `state` does not match the current project, so read again and retry.
- The full tool set also needs `project_id` and a unique `request_id`; the compact tool set can omit them, `request_id` is derived from the arguments, and retrying the same call fetches the original result.
- Long-running operations return a background task; get the result with `job_wait` / `job_get`; the compact tool set's `wait_ms` can return the result directly within a time limit.
- `workspace_apply_edits` commits 1–128 edits as one atomic batch and appends one history node only if all succeed; `workspace_preview_edits` only does a trial run and does not change the project.
For the main tools grouped by purpose see [[external-application-integration-api-list]].
@@ external-application-integration-api-list | List of External API Integration Functions
The table below lists the main MCP tools by purpose (names in the compact tool set). Use `workspace_get_operation` for field details; the end of every manual page also lists the operations related to that page.
| Group | Tools | Purpose |
|---|---|---|
| Query | `workspace_overview`, `workspace_inspect` | Read the canvas, parameters, layers, deformers, physics groups, motions and so on |
| Edit | `workspace_apply_edits`, `workspace_preview_edits`, `rig_deform`, `keyform_apply` | Atomic batch edits, trial runs, editing keyforms |
| Intent tools | `author_axis`, `author_physics`, `author_compile` | Create parameter-axis shapes and physics groups and fit them in one step |
| Parameters and preview | `parameter`, `preview_set`, `preview_reset`, `snapshot_*` | Parameters, preview pose and parameter snapshots |
| Motion | `motion` | Motion clips, keyframes and events |
| Expression / pose | `expression`, `pose` | Expressions (exp3) and pose groups (pose3) |
| Skeleton / path | `skeleton`, `path` | Skeleton and deform paths |
| Physics / swing / simulation | `physics`, `swing`, `simulation` | Physics groups, swing, cloth and hair simulation and baking |
| Painting | `source_paint` | Source layer pixels |
| Observation | `view` | Rendering and spatial mapping |
| History | `history` | History nodes and annotations |
| Project and export | `project` | Open, import, save and export |
| Tasks | `job` | Background tasks |
| Other | `workspace_call` | Call operations not published directly as tools |
@@ viewer | Expressions and Poses
@@ pose-setting | Pose Settings
A pose makes a group of parts show only one at a time, such as arm A and arm B, or two outfits. The SDK reads the exported `pose3.json`: on load each group shows its first part, and on switching the new part fades in and the old one fades out within the set time.
## Setting up pose groups
1. Open the Animation panel and expand the **Pose** section.
2. Click **New group** and choose the first part of the group; the first part in a group is marked **Shown first**.
3. Expand the group and click **Add part** to add the other parts of the group. A part can appear only once across all groups.
4. For a part that should show and hide together with another (for example the hand of arm A), click **Link** on that row and pick it; click again to unlink.
5. At the bottom of the section set **Switch fade (s)** (seconds, default 0.5).
Each change is recorded as one history entry, can be undone, and is saved with the project. When a part is deleted, the group marks it **(missing)** and export skips it.
## Differences from Cubism
- It is set in the editor, with no separate Viewer and no separately saved `pose3.json`.
- The editing canvas and preview do not apply poses: all parts show at their own opacity as usual, and the pose only takes effect when the SDK loads the model.
- Exporting moc3 writes `<name>.pose3.json` and references it in the `Pose` of `model3.json`; with no groups it is not written.
@@ facial-expression-system | Facial Expression Mechanism
An expression is a set of parameter values that the host layers on top of a motion, for example switching to "smile" while the idle motion keeps playing. The SDK reads the exported `exp3.json`, and each parameter is combined with the value the motion gives according to its blend:
| Blend | Result |
|---|---|
| Add | motion value + expression value |
| Multiply | motion value x expression value |
| Overwrite | expression value, motion ignored |
When switching expressions, the new one fades in during its fade-in time and the old one fades out during its fade-out time, so you do not need a separate motion for each mood.
## Standard expressions
A generated model comes with four standard expressions (Smile, Sad, Angry, Surprised, named in the interface language), made from the mouth shape, eye opening and eyebrow parameters the model already has; missing parameters are left out automatically. An imported CMO3 model has no standard expressions. The first time you change the expression list (create, delete or edit any), the standard expressions are taken into the project as ordinary expressions and from then on only the project's expressions are exported; choose **Back to standard expressions** in the expression's right-click menu to return to the automatically generated set.
## When a parameter is deleted or its range changes
Deleting a parameter also deletes its entry in the expressions; narrowing a parameter's range pulls Overwrite values into the new range.
@@ create-facial-expressions | Create Facial Expressions in Animation View
Expressions are made straight from the parameter state: pose the face the way you want on the Parameters panel or the canvas, then save it as an expression.
## New from the current parameters
1. Adjust the expression on the Parameters panel (eyes, eyebrows, mouth and so on). While a motion plays in the preview, the values currently shown in the panel are taken.
2. In the Animation panel toolbar click **+** and choose **New expression from current parameters**.
3. Every parameter that differs from its default becomes an Add entry whose value is the difference from the default, matching the starting point of making expressions in Cubism's animation view.
## Other ways to create
- **New expression from snapshot**: uses the values of a parameter snapshot saved in the snapshot bar of the Parameters panel; the expression takes the snapshot's name.
- **New empty expression**: an expression with no parameters, which the host can reset to as "no expression".
- Right-click an existing expression and choose **Set from current parameters** to regenerate all its entries from the current parameters.
## Previewing
Click the eye icon at the right of an expression row (or right-click and choose **Preview on the parameters**); the expression is applied to the current parameters by its blends, so you see the effect directly on the canvas and in the preview. This changes only the preview parameters and is not recorded in history.
@@ setting-and-exporting-facial-expressions | Expression Settings and Export
Each expression's entries, blends and fades are set in the **Expressions** section of the Animation panel and written automatically on export.
## Settings
1. Click an expression row to expand its settings.
2. **Fade In Time** and **Fade Out Time**: the seconds taken to switch to and away from this expression (default 0.5).
3. Each entry is one row: parameter name, blend buttons (Add / Multiply / Overwrite), value, remove. When you change the blend without changing the value, the value is converted so that the parameter default gives the same result.
4. **Add parameter** adds a parameter that is not set yet.
5. The right-click menu can rename, duplicate, **Move up** / **Move down** and delete. The order of expressions is the order in `model3.json`.
Each change is recorded as one history entry, can be undone, and is saved with the project.
## Export
- Exporting moc3 writes each expression as `<name>.<expression ID>.exp3.json` (`Type`, `FadeInTime`, `FadeOutTime`, `Parameters`) and references it by name in the `Expressions` of `model3.json`. Parameters that no longer exist in the model are skipped; an expression with no entries is still written.
- The VTube Studio package adds a hotkey per expression that toggles it.
- The PSD2Live runtime model (`.p2lrt`) and the web player carry the expressions as well.
- `.cmo3` / `.can3` do not store expressions, and the export report says so; to hand them to the SDK, export moc3.
*> Mesh-only export writes no expressions or poses.
@@ userdata-event-information | Check User Data and Event Information
The **User data and events** section at the bottom of the Animation panel lists in one place all the text the host can receive from the model: each motion's events (motion name, time, value) and each ArtMesh's user data (mesh name, value).
- Click an event to open that motion and move the playhead to that moment.
- Events are edited in a custom motion's settings and mesh user data in the Inspector; see [[userdata]].
- After export, events are in each motion's `motion3.json` `UserData` and mesh user data is in `userdata3.json`.
