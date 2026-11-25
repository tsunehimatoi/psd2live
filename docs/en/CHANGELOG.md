<!-- lang: zh en ko ja -->
> [中文](../zh/CHANGELOG.md) · **English** · [한국어](../ko/CHANGELOG.md) · [日本語](../ja/CHANGELOG.md)

# Changelog

[Documentation](README.md) · [Download](https://github.com/tsunehimatoi/psd2live/releases)

Only changes a user can notice are recorded. Versions v1.4.2 and earlier are on [Releases](https://github.com/tsunehimatoi/psd2live/releases).

## v3.8.4 · 2026-11-21

### Improvements and fixes

- **Reload PSD**: adds "Model > Reload PSD". After you edit the source image, it updates the layer textures inside the project directly, and it keeps the existing rig, the project file, and the undo history. When needed it rebuilds the mesh automatically and migrates deformation, weights, and path bindings. ([#97](https://github.com/tsunehimatoi/psd2live/pull/97))
- **Full pixel mesh coverage**: adds an optional mesh setting that covers every non-transparent pixel (alpha > 0), so feathered edges, a faint glow, and thin hair strands are not clipped at the mesh boundary. ([#96](https://github.com/tsunehimatoi/psd2live/pull/96))
- **Deform and parameter editing**: a Warp deformer can turn on the vertex and Bezier edit levels at the same time, so you can fine-tune them together without switching. Hold `Shift` while dragging a control point, a handle, or the transform box to lock movement to the main axis. Editing an in-between pose snaps to the nearest keyform automatically and shows a notice. Modifier blend-shape override editing and component keyform highlighting are supported as well. ([#81](https://github.com/tsunehimatoi/psd2live/pull/81), [#94](https://github.com/tsunehimatoi/psd2live/pull/94), [#95](https://github.com/tsunehimatoi/psd2live/pull/95))
- **History and layer fixes**: undo, redo, or switching a history entry keeps the current parameter pose, and no longer resets it to the default. Fixes a split layer that stayed in the visible-layer analysis after it was deleted. ([#92](https://github.com/tsunehimatoi/psd2live/pull/92), [#93](https://github.com/tsunehimatoi/psd2live/pull/93))
- **Interface cleanup**: unifies the Start page and the collapsible Help/Settings sidebars, improves vector-icon drawing and the adaptive width of dropdown menus, and gathers deform-tool options into a more compact dropdown.

## v3.8.3 · 2026-11-19

### Improvements and fixes

- **PSD import wizard**: before the project is created you can preview and split disconnected meshes, check layer bindings, choose a modifier preset, and compare head-turn styles. On create you can choose the workspace to enter.
- **Head and mouth rigging**: the head turn fits the face and hair in the picture more closely. You can edit the automatically generated open-mouth keyform on the canvas, and adjust the closed-mouth artwork's opacity curve on its own.
- **Simulation and weights**: supports painting vertex weights, and baking a physics simulation straight into keyforms.
- **VTube Studio export**: choose an icon when exporting the model, and use the motion and expression names from the project. A hotkey with no screen button assigned is written as `ScreenButton: -1`, which fixes old exports that could leave the expression editor unable to open. ([#90](https://github.com/tsunehimatoi/psd2live/issues/90))
- **Desktop compatibility**: fixes startup and worker-process problems of the packaged Linux application, Chinese path encoding on Windows, and the working directory of command-line export in the packaged macOS build.

## v3.8.2 · 2026-11-18

### Improvements and fixes

- **Automatic mouth rigging**: improves the choice of hidden mouth shapes and the mouth-cavity mask, refines independent deformation of the upper and lower lips, and reduces lip-line gaps and mouth-corner jitter when the mouth is closed.
- **Modifiers and presets**: applying a modifier keeps content that has already been edited, and updates in dependency order. Fixes saving of preset selection and editing, and keeps the other usable results when a single modifier fails.
- **Start page and import**: reorganizes the Start page's create, open, and recent-project entries, keeps the import preset, and fixes transparent display of PSD thumbnails.

## v3.8.1 · 2026-11-17

### Improvements and fixes

- **PSD import and plugin isolation**: irregular PSD input is more tolerant of errors. When a single modifier fails, its error is kept and its dependents are isolated, and the other usable features keep running.
- **Brows and deformation precision**: fixes compatibility when brow shape, blink, and elastic binding are combined, and raises inverse-deformation precision on a large canvas.
- **Eye perspective mask**: when the bangs already have a clip, the original mask is kept and the eye perspective is stacked correctly. An export target that does not support combined masks reports the capability loss.
- **Custom plugin development**: supports a plugin template, the build, parameter configuration, and an undoable code update, and fixes dependency locking and reload compatibility of old build outputs.
- **Desktop compatibility**: improves Linux window moving and file-chooser compatibility, and fixes recovery after a transparent-background session fails.

## v3.8.0 · 2026-11-15

### New features

- **Modifier menu and conflict notices**: rebuilds the modifier stack and the add-preset menu, and unfolds them gradually in three columns: category, base item, and details. Hard conflicts and soft conflicts are marked in the modifier stack and in the add menu, so mutually exclusive effects are not stacked by mistake.
- **Nested part-plugin settings and behavior preview**: a folded part plugin can expand its child-feature settings in the inspector and preview the behavior live. Resetting a part also clears inherited properties.

### Improvements

- **Presets and terminology**: unifies algorithm terms such as "stylized" and the preset names, and adjusts the default preset configuration (displacement is off by default, and optional modules such as skirt and squash are distinguished).
- **Source-PSD binding and export consistency**: mesh splits are brought into the `mesh.split` modifier plugin, and split-mesh references are kept on `.cmo3` export and read-back. Improves source-PSD binding and export for a single eye, a layered mouth (tongue out, dental arch, and lip line), and animal ears / tail / wings.
- **Preview response and cache performance**: introduces locally scoped build plans and a bounded cache, which reduces repeated rebuild cost while a modifier is edited, and keeps the preview's first frame and the saved state in sync.

## v3.7.1 · 2026-11-11

### New features

- **macOS application**: macOS 13 and later can be packaged as a DMG with `./gradlew packageDmg`. The application is a Swift shell around the existing editor, and it includes a Java runtime. Menus are at the top of the screen, the window buttons are the system buttons, and the application bar is drawn in the system title bar (traffic lights, menu, and file name on one row). An unsaved project is marked on the close button. The package architecture matches the Mac that built it (Apple silicon or Intel). It is not notarized. After downloading it from the network, right-click it in Finder, choose Open, and drag it into Applications.
- **Left and right marks in layer names**: ">" means the character's left side, and "<" means the character's right side. They can go anywhere another left/right word can go (fullwidth ＞ / ＜ work too).

### Improvements

- **Generated idle, nod, and head shake**: idle breathing is a quick inhale and a slow exhale, with a rest in between, and the head no longer takes the same path on every breath. In the natural motion the gaze drifts slightly while it holds, overshoots a little on the way back, and the brows rise and fall with the breath. A nod lifts the head slightly first, then drops quickly, rises slowly, and overshoots a little. The body arrives late, and the eyes narrow on the way down. A head shake slows at both ends, the later amplitude is smaller, and the left and right intervals are not equal. A motion whose keyframes have already been edited by hand stays as it is.

## v3.7.0 · 2026-10-27

### New features

- **Rig modules organized by area**: the automatically generated rig is divided into areas such as eyes, brows, mouth, head, hair, body, clothing, expressions, and motions. Each area can pick a base scheme and stack modules. A new project uses the modern scheme by default and turns on every applicable module, with presets and a cost budget.
- **Eyes and brows fitted to the drawing**: eyelids are generated from the eye-white contour and the closed-eye line, and they close along the face when the head tilts or turns. Brows rotate along an arc and carry the upper eyelid. When the bangs cover them, the eyelids carry the emotion.
- **A mouth that opens in stages**: the mouth opens in stages along the upper and lower lip edges, including vowels, face capture, the jaw, and pursing / spreading the lips. A mouth drawn in layers (upper and lower lips, lip line, teeth, mouth cavity) is supported. The teeth and tongue move with the lips as a whole, and a shout can open past the picture.
- **A head turn in depth**: the head turns as a cylinder. The face, front hair, back hair, and ears each sit at a different depth. At the end of the turn, the far-side hair moves behind the face.
- **Chained swing of hair, clothing, and the body**: hair segments swing like a pendulum chain. Skirts, ribbons, hanging ornaments, animal ears, tails, and wings swing as chains. The chest bounces, the arms swing, and the neck shares the head tilt.
- **Expressions and natural motion**: new emotion parameters such as blush and gloom. Standard expressions are richer (shy, sleepy, and gloom are added). Natural blink and gaze movement. A VTube Studio mapping tuned for the modular rig.
- **Feature-point tool**: `Shift+Y` shows the eye, brow, and mouth control points on the canvas. Drag them to fit again, and every pose changes with them. Alt-click restores.

### Improvements

- Blush, shadows, and bangs cast on the face are clipped to the face, and they do not leave the face on a head turn.
- Workspace-tab dragging is smoother, and a tab can be dragged all the way to either end.

### Compatibility

- An old project generates with the old scheme, and the result is byte-for-byte the same as before. It can be upgraded to the modular rig from the prompt.

## v3.6.7 · 2026-10-24

### New features

- **Graphics settings page**: Settings adds "Graphics": the render path (GPU / software canvas / software), GPU selection, quality presets, window and canvas anti-aliasing (MSAA, SSAA), texture filtering, vertical sync, preview and background frame rates, and more.
- **MCP completes one editor gesture in one step**: read and write ruler guides, import as a reference, preview an expression, soft-selection deform, symmetric strokes, and more. A refusal reason can be told apart, and what was written can be read back.

### Improvements

- **Settings page layout**: flat headings and two-column rows, and cards are no longer used.
- **The GPU canvas costs less**: less work per frame, and dragging no longer rebuilds the toolbar and the status bar over and over.
- **Saving is faster**: a small project saves about a third faster. The archive contents are unchanged.

### Fixes

- On Linux the edit canvas draws with the GPU again, and no longer skips every frame. The recovery notice no longer sticks on a blank screen (#62).
- Masks, Multiply / Screen colors, and the various blend modes on the edit canvas match the preview.

## v3.6.6 · 2026-10-24

### New features

- **Free-transform a selection**: free transform in paint mode (`Alt+T`, or `T` in paint mode) puts the selection's pixels (or the whole layer's content when there is no selection) into a transform box. You can move, scale, and rotate. Enter applies it as one stroke. Esc puts it back.
- **Quick select and selection expand**: the selection tools add a quick-select brush. The paint menu can expand / contract a selection, or select opaque pixels by opacity.
- **The whole eye shows through the bangs**: the eye white, the iris and highlights, the eyelashes, and the closed-eye line can all show through the bangs. New projects turn this on by default. A model generated by the 3.6.5 rules updates when you generate again.

### Improvements

- **The point-placement tool includes the knife**: the point-placement tool is split into "Point" (`Alt+M`) and "Line" (`K`). Line mode draws a polyline so the mesh folds along the line. An existing project still replays as usual.
- **Interior-shaping handles move to object mode**: `Shift+I` enters object mode. Mesh points are no longer drawn under the handles. Click another object to switch.
- **Tab switches modes**: Tab switches between object mode and the mode used last.
- **The canvas display bar is simpler**: the bottom right shows only fixed toggles, and only when the canvas has something it can show. "View ▾" can pin or unpin. Onion skin moves to the animation-editor toolbar. Random pose moves to the parameter panel.

### Fixes

- The edit canvas correctly shows inverted masks, and Add and Multiply blending.

## v3.6.5 · 2026-10-23

### New features

- **Paint selections and new tools**: the selection tool (`Alt+L`) includes rectangle, ellipse, lasso, polygon lasso, and magic wand. You can add, subtract, and intersect, feather, and invert. Brush, fill, and gradient affect only the selection. Adds gradient, a blur brush, non-contiguous fill, and stabilization, left-right symmetry, and protect transparency.
- **Manual point placement**: manual point placement in edit mode (`Alt+M`) clicks or follows a stroke to add points, drags to move a point, Shift-clicks to connect, and Alt-clicks to delete a point or an edge. Left-right symmetry is available. The topology menu adds delete edge and retriangulate.
- **Interior-shaping canvas handles**: `Shift+I` drags bulge, bias, and drive direction on the canvas, and draws and edits crease guide lines directly. They stay in sync with the panel values.
- **Keyform tools**: the keyform submenu in deform mode can copy a shape and paste it onto another key or object (mix ratio, flip), restore the default shape, interpolate between neighboring keys, and edit several keys at once. `Shift+T` temporarily deforms the lattice and pushes the selected points as a whole.
- **Canvas aids**: grid, ruler guides (they snap, and they are saved with the project), onion skin, and random pose. The toggles are at the bottom right of the canvas.
- **Copy and paste, and batch rename**: in object mode, `Ctrl+C` / `Ctrl+V` copies meshes, deformers, parts, and parameters. Copy mesh topology onto another layer. Find and replace by name or ID. Convert IDs in a batch, and every reference updates with them.
- **Expressions, poses, and events**: the animation panel can create an expression from the current parameters (Add / Multiply / Override, fade in and fade out), set pose groups that show exclusively, and place an event on a motion. Export writes exp3, pose3, and motion UserData. A generated model includes smile, sad, angry, and surprised by default.
- **Hit areas**: set a HitArea in the inspector or the hierarchy tree. Click in the preview to verify the hit. Export writes it into model3 and the VTube Studio package.
- **Timeline aids and looping**: parameter bookmarks, hide, freeze, markers, and motion paths. Join the start and the end in one step. A parameter can be set to loop (for example a continuous rotation).
- **Brows show through the bangs**: during automatic generation, brows covered by the bangs are shown half-transparent above the bangs. The opacity is adjustable, and it can be turned off.

### Improvements

- **The editor and an agent edit at the same time**: the workspace treats the runtime as the only authority. An uncommitted editor change yields field by field when it meets an agent's commit, and the rest commits as usual. What is saved is the actual state.
- **Screen blend**: a Screen layer from the PSD exports as Screen in Cubism 5.3. An earlier version approximates it as Add and writes that into the loss report. Generated-result overrides of color, opacity, and draw order are kept as well.
- **Window mode**: Settings → Display can choose window, maximized, and full screen, and it remembers the choice. `F11` or "View → Full screen" toggles full screen. Maximized no longer covers the taskbar.
- **A new interface style**: panels become rounded cards on the window background, with tighter spacing. The title-bar menu, dropdowns, and right-click menus share one frosted-glass menu style.

### Fixes

- When the automatically generated lip line is drawn, it explains that it comes from the mouth layer and the mouth settings, and it no longer reports "source layer not found". Right-clicking a parameter name can unbind it from the selected mesh or deformer (#55).
- A saved window size is limited to the screen's usable area, and the title bar no longer runs off the screen.
- On a large canvas, creating a new deformer for a mesh, moving a bone, or compressing the whole root mesh no longer falsely reports a degenerate result or a regeneration conflict (#56, #59).

### Compatibility

- A project that contains expressions, poses, or hit areas is refused by an earlier version. That data is not dropped quietly.
- A Screen layer in an old project exports as Screen (or Add) after it is reopened, and it is no longer treated as a normal blend.

## v3.6.0 · 2026-10-20

### New features

- **Help becomes a manual and tutorials**: the Help page is rearranged, following the Cubism editor manual and the tutorial site, into two trees, "Manual" and "Tutorials". Each page marks whether it is the same as Cubism, a different operation, unique to this program, or not implemented yet (not implemented is grayed out and cannot be opened), and it links to the matching Cubism page. Inside a page there are breadcrumbs, steps, tables, and tips. The end of the page lists the MCP operations related to that page. The catalog can be filtered, and status is shown by a color bar at the side.
- **History-tree collapse**: the history tree collapses a continuous linear run of versions into one card by default. A fork stays on its own. The card shows how many steps that span has. Hover shows the time range and the first 8 steps. The "Collapse" toggle on the toolbar switches back to the full node-by-node view. The operation list on the left always lists every step. Double-click a card to check out the last step of that span. After you check out one step inside it and keep editing, you fork from that step.
- **Replot points from texture structure**: the mesh panel and the interior-shaping panel can rebuild the inside of the mesh from folds, light-dark boundaries, and pattern lines drawn in the texture. The outer contour and the edge band stay. Vertices are placed only on the recognized lines and on the edge (optional fill between lines, and a crease band that adds rows on both sides of a crease line). You can show the recognized lines and the new mesh on the canvas first, then apply. Keyforms, paths, weights, and Glue migrate as usual. Interior shaping rebakes in the same step. A recognized line can be marked as a crease one by one. MCP adds `layer_mesh_structure` / `layer_mesh_structure_preview`.
- **Keyform mirror**: the point/deform menu adds "Mirror keyform". It can mirror the current key onto the symmetric key, mirror back from the symmetric key, mirror onto the object on the other side, or flip left and right in place, and it can mirror every key at once. `Shift+M` mirrors onto the symmetric key directly. MCP `keyform_apply` supports `mirror`.
- **Drop a file by where it lands**: when you drag a file in from the system, where you release decides what happens. An explanation appears beside the pointer first, and a full-window mask is no longer laid over the window. Drop onto the material panel to import as a material. Drop onto the hierarchy tree to put it into a deformer, or to insert it before or after a mesh, following the insertion mark. Drop onto the edit canvas to place it where you release. Above the canvas there are three more slots: "Set as reference image", "Replace image", and "Import into the material library only". A CMO3 can also be dropped to open it. A position that does not accept the drop does not take it, and it no longer reports an error. A drop still lands in the right place when the interface scale is not 100%. Dragging a material from the material panel and dropping a file from the computer use the same landing spots and hints. Dropping a folder opens the project or PSD inside it, and when neither is there it is set as the output directory. An unsupported format pops up a notice that lists the supported formats.
- **Materials are the only source of layers**: every layer has a matching material. Importing a PSD, a CMO3, an image, and splitting a layer all register one. Every ArtMesh image in a CMO3 can be found in the material library. Editing a layer no longer quietly breaks the link. Layers that share a material can "Make a separate material". An old project can register in one step. MCP adds `material_fork` (`material_unlink` is kept as the old name) and `material_adopt_layers`.
- **A CMO3 uses a new atlas**: an imported CMO3 no longer keeps its original texture pages. The atlas is repacked from the current layers, and export writes that atlas. An imported layer can change its image. Changing a material's pixels shows up in the texture and in the export.
- **References**: a reference becomes a kind of member in the hierarchy tree (the reference icon). It is shown in the background, among the layers, or in the foreground, and it is marked clearly whether it enters the model. Background and foreground are shown only in the editor. After you select one, drag it on the canvas to move, scale, and rotate. Dragging is smooth, and releasing records one history entry. It can be locked. Alt+R toggles display. A reference among the layers enters the model. It is an ArtMesh in the reference-image part. It can be parented under a deformer, follow the deformation, and be edited directly. On export it is not written by default (it is written when "Export guide layers" is checked). Right-click "Display mode" to switch at any time, and the material, position, and pin are kept. Pinning to a keyform is now a dot on the keyform point: one reference can appear on several keyforms. The panel shows sliders and a 2D pad in the shape of the parameter panel. Opacity becomes a continuous slider. A reference image and an ArtMesh can be converted into each other from the right-click menu or the toolbar "Toggle reference image / ArtMesh". The reference items on a material's right-click menu are merged into "Add as reference". MCP adds `reference_set_mode`, `layout_add_reference`, `layout_order_reference`, `layer_pin_reference`, `reference_place_grid`, and `view_render_pad`.

### Improvements

- **Layout: edit first, then generate**: in a layout project that has not been generated yet, or that still has layers waiting to generate, a layer has a real mesh as soon as it enters the canvas. You can edit meshes, deformers, parameters, and the hierarchy, and you can export directly. An empty canvas, and a project that has only reference layers, can generate too. Generation pins the existing mesh topology and three-way-merges manual edits. A layer whose image was replaced is still rebuilt from the new pixels.
- **The edit canvas and parameter dragging are smoother**: the edit canvas is drawn on its own by the window compositor. Posing, hovering, or dragging a vertex redraws only that layer. Dragging a parameter slider, and a panel that does not show the pose, no longer rebuild with every pose frame. A restore point is recorded only on release.
- **Material panel**: a material thumbnail shows how many times it is used. Hover lists the layers and ArtMeshes that use it. Right-click can edit tags, set "Place as a reference layer by default", and select the layers that use it (when there are several, they are listed one by one). Right-click empty space to create a folder, import, or switch grid / list / tree view.
- **Interior-shaping panel**: it uses the same toolbar and collapsible sections as the other inspectors. Try-out becomes the same sliders and 2D pad as the parameter panel. The edit result and failures are shown in the task indicator at the bottom. Drive direction becomes an arrow chip that is drawn. A new local crease is numbered `fold<n>` automatically.
- **Logs and a stuck-UI diagnosis**: the interface build writes console output to a log file (`psd2live.log`, and the previous run is kept; the start records the version, Java, and the system). When the interface is stuck for more than 5 seconds, every thread stack is written into the log once, and another line is recorded when it recovers (#52).
- **PSD import preparation leaves the interface thread**: while a PSD is imported, material registration and preparation of the initial history finish in the background. The interface thread only does the final publish (#50).
- **Right-click menus are clearer**: deleting a folded chain in the hierarchy tree distinguishes "Delete the current deformer" from "Delete the whole chain". Right-clicking one item of a multi-selection notes that the operation acts only on this item. A material's "Select the layers that use it" lists each layer to choose from when several layers refer to it. A command bound to right-click on the main canvas runs first and is no longer opened ahead of time by the menu, matching the atlas.
- **Parameter-name right-click menu**: right-clicking a parameter name can open properties, reset the current value, lock or unlock, copy the parameter, copy the parameter ID, or delete the parameter. Left-click still opens properties.

### Fixes

- **MOC3 user data**: when export user data is checked, `userdata3.json` is written (an entry is built from the written ArtMesh ID), and `model3.json` references it. When there is no user data, that file is not written.
- **Textures of imported images and reference layers**: the creation record of a new layer and of a reference layer builds the texture manifest from the current pixels, and it is no longer bound to a 1×1 placeholder tile. A reference among the layers enters the canvas as a four-point rectangular mesh.
- **Where a reference layer is drawn**: a layout reference layer is drawn in its own canvas rectangle, and it is no longer mirrored to the top of the canvas.
- **Extended simulation in the preview runtime**: p2lrt advanced mode advances the simulation in stages. A finished compile and a paused view update as well, and continuous redraw no longer flashes back. When there is no applicable extension, or when extended simulation needs physics turned on, the status capsule explains why.
- **GPU fallback**: when Skiko replaces or loses a renderer, the GPU is resolved again and it falls back. After switching to software drawing, the layer cache is still kept.

## v3.5.2 · 2026-10-18

### Fixes

- **GPU compatibility**: when the GPU driver does not support multisampling (for example some Intel integrated graphics), the window no longer freezes or goes black. It draws with single sampling instead. When canvas GPU drawing fails (it cannot create a framebuffer, compile a shader, and so on), it switches to software drawing (#48, #49).

## v3.5.1 · 2026-10-17

### New features

- **Interior shaping**: generates an interior surface change that keeps the contour of a single mesh (bulge, bias, and creases, and a local crease can be added). You can try it by following the mouse, and you can add easing and drive. It is written into the model as blend shapes, saved with the project, and included in export. MCP adds `interior_put` / `interior_delete`. `workspace_inspect` can view `interiors`.
- **Unity prefab export**: "Export → Unity prefab…" bakes parameter keyforms into blend shapes and motions into an Animator. The character folder can be dropped straight into a Unity project. Run the install script once the first time. Later characters are copied in the same way.
- **Reference layers**: a layer can be set as a reference layer (the layer table's "Use as reference", Ctrl+Alt+R, the canvas right-click menu, or the material menu's "Place as a reference layer"). It stays on the canvas and does not enter the rig or the export. Reference layers are drawn on their own, they no longer enlarge the atlas, and editing is smoother. MCP adds `layer_mark_reference` and `material_mark_reference`.
- **Layout manifest**: a layout project can be opened from a psd2live-layout manifest (JSON, or a zip whose root contains the manifest), or imported as a material folder. The manifest lists each layer's image, canvas position, group, tags, variant, visibility, and opacity. A layer that has not been generated yet can be moved to the front, to the back, up, or down in the layer table (MCP `layer_reorder`).

### Improvements

- **Draw order leaves gaps**: a new project's layer draw order is numbered with a gap of 10 (5, 2, or 1 when there are many layers). The lip line is placed in the gap above the mouth. Draw order accepts only integers. An old project keeps its original numbers.
- **English documentation**: the user guide, the format reference, and the MCP reference add an English version.

### Fixes

- **Fractional draw order in a CMO3 export**: a fractional draw order is converted to an integer by the original stacking order on export. Keyforms are no longer lost, and it no longer reports "Deform path ArtMesh has no default keyform" (#39).
- **CMO3 import**: a freeze while importing some models is fixed, and the imported compression is faster. Multi-axis blend of keyforms matches Cubism Core. Draw order and opacity after import no longer drift. When physics setting IDs are duplicated, all of them are kept.
- **Material and generation problems in 3.5.0**: material paint sessions, material links, layout and the generation queue after a layer is deleted, changing classification several times in a row, and translucent edges of a peeled part are fixed (#42).
- Moving the mouse in the animation workspace when no motion is open no longer crashes. During preset apply on the Start page, "Split layers by mesh" no longer ignores clicks (#47).

## v3.5.0 · 2026-10-15

### New features

- **Start, Help, and Settings become pages**: the help center and settings are no longer dialogs. Like the Start page, they are pages at the front of the tab bar (`F1` / `Ctrl+,` opens them, and pressing again returns to the workspace). The panels of the three pages use the editor's dock framework. They can be dragged to redock, merged into tabs, floated, or closed. The layout is stored on this machine, and "Reset layout" restores it. All three pages are a catalog plus content. The Start page also has a details panel. "Reset layout" moves into the "Window" menu. Settings' "Scale and display" is rearranged by the inspector's groups, and the former "Environment" is merged in. Recent PSDs on the Start page sit under the generation viewport. A layout project's common sizes, width and height, and preview are combined into one panel. The details of an import from CMO3 list what is kept, and you can "Replace with a CMO3" the current model directly. What's new becomes text notes listed by version. The details panel can jump by version, and an entry that has a tutorial can open the tutorial directly. The version number on the About page is written by the build.
- **Settings adds "Files and data" and "MCP"**: "Files and data" can clear recent files, open the folder of the last export, and view the location and size of the install location, workspace storage, the downloaded runtime, the upscale cache, error logs, and command-line MCP storage. It can clear the upscale cache and the logs, clean up temporary files left by an abnormal exit, and show where preferences are saved. The former "Tools → MCP…" dialog moves into Settings' "MCP". "File → Open folder" can open the folder that contains the project, the source file, or the output, plus the workspace storage and the application data folder.
- **Title-bar menu cleanup**: the menus are File, View, Model, and Help. "Tools" becomes "Model", and it gathers operations that act on the whole model (reanalyze the PSD, replace with a CMO3, split layers by mesh, upscale textures, update generated results). "File → Import" becomes "File → New". The Start page stays only in the "File" menu. Interface scale and font size in "View" move to the Settings page. Settings becomes the gear on the right of the title bar, next to language and the light/dark theme. The light/dark theme button uses the sidebar toggle's colors. The "Help" menu has only two items: "Help" opens the Help page, and "Online resources" opens the GitHub repository, the documentation, the example repository, releases, and issue feedback. The Help page adds "Examples" (what the example repository contains, how to use it, and how to contribute) and "Third-party licenses" (the components used, their licenses, and what they are for). The layer specification adds notes on preparing artwork, front and back hair, and left and right. Links and the About page text are updated to match.
- **Start workspace**: Start is no longer a dialog after import. It is a "Start" page fixed at the far left of the workspace tab bar. It is shown at launch, it switches away automatically after you open or create a project, and you can click back to it at any time. The layout matches the editor's panels, and the splitters can be dragged to resize: navigation on the left (open a project / recent / generate from a PSD / layout project / import from a CMO3 / quick start / what's new). On open it sits on "Recent" (on "Generate from PSD" when there are no recent files yet), and it lists recent files the way the material panel does. A layout project carries a NEW mark. The center is the content. The property panel on the right sets what is about to happen. The action bar at the bottom holds a prominent main button (Enter triggers it too). One-click generation is still one step: before you import a PSD, pick the preset in the property panel, then drop it anywhere in the window, or double-click it in the recent-PSD filmstrip, and it generates from that and enters editing. A single click previews it in the viewport. After a project opens, the "Start" tab collapses with an animation, and it can be opened again from "File / Tools → Start page". A layout project previews the canvas size in proportion. Double-click a common size to create it directly. Recent files can be viewed as a grid or a list, and filtered, and they show a thumbnail, the modified time, and the size. When a project is saved, a thumbnail is stored in the file. A PSD reads its embedded thumbnail or composite. What used to be drawn on the empty canvas moves here. The empty canvas keeps only one line of hint and "Go to Start". Split layers by mesh becomes "Tools → Split layers by mesh…".
- **Windowless MCP**: `psd2live mcp` no longer builds the whole editor interface in the background. The workspace is hosted directly by the application-layer host. When there is no window, `view_sample_motion` can still sample a motion (previously it waited until it timed out). At startup it prints `capabilities` to say whether that is available.
- **Peel a part (MCP)**: `material_peel` uses a generated image that has had a part removed to peel that part off the picture, and it produces the part and the picture under it. Pixels outside the part are kept, and stacking it back is the original image. `material_set_placement` lets an externally generated high-resolution image take the close-up's position. `keep` uses points on the part to remove stray specks the generated model changed along the way. `compare_scale` makes an image that was generated at a smaller size and then enlarged compare at the generation size. `mcp_proxy.py` can call operations directly from the command line or a Python script. The matching ComfyUI tools are on the separate branch `comfy-tools`.
- **Material library**: adds a material panel. You can import images, a PSD, layered images from a CMO3, and SVG, organize them in folders, add tags, preview, and export a folder as a PSD. Materials are saved with the project, and edits can be undone. Layers of an imported PSD also appear in the material library.
- **Place first, then generate**: the "Layout" mode on the Start page (or "File > Import > New layout project") starts from an empty canvas. Drag materials onto the canvas, place them, and then generate the rig. After generation, keep adding images and changing tags, click "Regenerate" to merge them in, and existing keyforms, deformers, physics, skeletons, and other edits are kept. Adds a Layout workspace and a "Materials and placement" tutorial.
- **Semantic tags**: "Type / part / side" in the layer table becomes tags. You can combine a part and left/right, and you can add your own tags. Variants are a column of their own.
- **Replace an image with a material**: after the replacement, the layer's position and size on the canvas stay the same.
- **Material window**: double-click a material to view it in a separate window. You can zoom and pan, and edit it directly with the brush, pencil, eraser, paint bucket, and eyedropper, or flip it and adjust hue, saturation, value, and contrast. A layer that is already placed updates with it. The material panel keeps a compact folder tree, and the column width can be dragged. A folder imported from a PSD / CMO3 is shown with the matching file icon and the item count. A material can be dragged onto a folder, the tree, or the path bar to move it.
- **The material window uses paint mode directly**: the toolbar, the options bar, the paint-session bar, the right-click menu, the brush-tip display, and the shortcuts all match the canvas paint mode, and a stroke is shown immediately. After apply, it is written into the material as one history step. During painting you can keep editing other content as usual.
- **A material is the only source of a linked layer**: painting, in paint mode, a layer that was placed from a material paints the material, and every layer that uses it updates together. The layer table marks linked layers with a chain link, and you can unlink so the layer stands on its own.
- **Material tree**: the tree shows the images inside a folder. They can be dragged onto the canvas or double-clicked to open. The tree's right-click menu can create a folder and import. A single click selects a material immediately. The right-click menu no longer shows explanatory text.
- **Dropdown motion is unified**: a dropdown on a floating bar (for example the "Generate" options) appears row by row, like the mode menu, and the highlight slides with the pointer.
- **Right-click menus are unified**: the right-click menu of each panel matches the canvas right-click menu in look and animation, and it expands from the click. Create operations on the object-mode canvas menu are gathered into a "Create" submenu. The canvas menu adds a "Mesh" submenu (rebuild the mesh, split disconnected meshes, depth split) and a "Material" submenu (open in the material window, unlink).

### Improvements

- **Long hair bends on a head tilt**: hair below the chin gradually rotates back the other way on an Angle Z tilt (a new parameter, "Long hair keeps its tilt", 80% by default). Long side hair and back hair no longer swing out as a whole bunch and expose what is under the body.
- **Reference images and close-ups**: a material can be set as a reference image stacked on the edit canvas (the right-click menu sets it, adjusts opacity, and clears it; the display toggle at the bottom right of the canvas can hide it). It is not a layer, and it does not take part in generation or export. A close-up material can be cropped losslessly from a reference image by a canvas region and exported as PNG, and it lands back in its original place when it is placed. The edit canvas adds a "texture density" heatmap, colored by each layer's texture pixels per canvas unit. MCP adds `layout_set_reference`, `material_crop`, and `material_export_png`. `view_render_model` can overlay a reference image and show the difference and the texture density. `./gradlew run --args=mcp` can start the MCP server on its own without opening a window.
- **Vector-drawn parts**: MCP adds `material_draw`, which draws a part as SVG in canvas coordinates, renders it at the given texture density, and, when it is redrawn, updates and moves layers that are already placed. `layout_set_drawing_kit` sets the colors, line styles, and gradients shared by every drawing, and after a change every part updates together. SVG supports class style rules, kit color variables, and lines that taper at both ends (`data-taper`). Adds `reference_sample` (sample a color and the main colors from a reference image), `reference_trace` (magic-wand the contour, and return a path that can be used directly), and `reference_snap` (snap a point onto a line). `view_render_model` can draw a grid with coordinates, and it can trace a drawn contour onto the reference image for comparison.
- The material panel adds "Import folder". Nested subfolders become material folders with their original structure. Files are ordered by the numbers in their names. Hidden files and folders are skipped.
- When a PSD material is placed, the PSD canvas is mapped as a whole, centered, onto the project canvas (scaled down uniformly when it is larger than the project canvas), and it is no longer placed at the top left in raw PSD coordinates. The new layout project page explains what a canvas unit means: it decides the model's size and proportions, and sharpness is decided by the material's pixels and the atlas budget.
- Painting is no longer limited to the mesh extent: the whole canvas can be painted. On apply, the part outside the mesh is split automatically into a new layer directly above the original layer (it also appears in the material library). The original layer's mesh and bindings stay the same. Applying no longer pops up a "Rebuild mesh" dialog.
- The editor's "Select" mode is renamed "Object" mode.
- Changing the classification of a layer that has already been generated no longer regenerates immediately. It takes effect together on the next "Regenerate".

## v3.4.5 · 2026-10-13

### Fixes

- **Save and restore**: improves coordination of save, close, and crash recovery. A recovery checkpoint keeps the full project state. Continuous pose edits are committed in order.
- **Keep the imported source file**: the project keeps the original PSD / CMO3 content from import. Later reads no longer depend on an external source file.
- **Empty layers**: a PSD that contains empty layers can be saved and reopened normally after import.
- **Disconnected-mesh check**: a failed check shows the error. It stops after more than 60 seconds. After the check is closed, a late result no longer pops the dialog up again. Out of memory and a call-stack overflow also have a clear notice.

### Improvements

- **Example artwork is distributed separately**: example artwork, projects, and output move to the [independent example repository](https://github.com/tsunehimatoi/psd2live-examples), and can be downloaded as needed. The main repository keeps the notes and a pinned version manifest, and contributions are welcome.

## v3.4.4 · 2026-10-13

### Fixes

- **Save and close are more reliable**: fixes being unable to save or close after a long editing session. Editing that continues during a save keeps the unsaved state. Discarding changes and crash recovery stay consistent.
- **Undo and redo**: the field that is being typed is committed before history switches, which fixes inconsistent history state during continuous editing.
- **Import and deformers**: fixes reading of 16-bit PSDs, relative layer masks, and TIFF orientation. Deleting a deformer keeps the child object's default pose and rotation inheritance.
- **Export and motions**: when export fails, the previous set of files is restored. Re-exporting a shorter frame sequence cleans up the old tail frames. Fixes GIF frame duration, Bezier motion clipping, and some runtime and web export problems.
- **Stability**: strengthens checks of damaged files, fixes clearing user data, preview recovery from an exception, and cancellation of background tasks, and reduces MCP connection log noise.

## v3.4.3 · 2026-10-12

### Fixes

- Fixes a bug where an adjustment stops taking effect.

## v3.4.2 · 2026-10-11

> For a project saved with an older version, after you open it run "Tools → Update generated results" once before you keep editing.

### Fixes

- **Flexible-bone bending and straightening no longer show ripples**: before straightening, the bone-chain joints are fitted to the graphic's center line, and the straightened graphic no longer keeps a leftover offset. The joint bends as a smooth round corner, and the graphic in front of the joint no longer flings out opposite the bend.
- **Undo, redo, and preview rebuild conflict**: when you switch history versions before a local preview rebuild has finished, the switch wins, and a late rebuild result no longer overwrites it.

## v3.4.1 · 2026-10-11

### Improvements

- **The automatic skeleton is more accurate**: the automatic skeleton recognizes limbs along the mesh shape. A tail and wings branch where the shape keeps forking (for example a fish tail or a double tail). Each branch has its own bones, and the trunk follows the thicker branch. The body frame is taken from the actual mesh, and empty template layers are ignored. The shoulder lands at the end of the arm. A hidden hip is filled in symmetrically from the spine. The position and proportion of the shoulder, hip, elbow, and knee are corrected to human structure.
- **Download files use one naming scheme**: release attachments are unified as `PSD2Live-<version>-<os>-<arch>[-<kind>][-ffmpeg]`. An installer carries `setup`. Architectures are unified as `x64` / `arm64` (for example `PSD2Live-3.4.1-windows-x64-setup.exe` and `PSD2Live-3.4.1-linux-x64.deb`).

## v3.4.0 · 2026-10-11

### Added

- **Flexible bones**: a flexible bone chain skins along a curve that passes through each joint and bends smoothly. It suits tails, tentacles, fins, ribbons, and similar. Parts that touch each other when they curl do not stick together. A bone chain can fork, and each branch bends on its own. "Straighten" unfolds a mesh that was drawn already bent, so it is straight when the parameter is 0, and the shape that was drawn is saved as a pose. A mesh bent by three or more joints bakes into a Warp layered by joint, and it keeps its length while it bends. A tail bone chain in an old project converts to flexible bones automatically when it is opened.
- **A Linux tar.gz package**: releases add a tar.gz that runs after you unpack it (amd64 and arm64, with a Java runtime bundled, and the same contents as the Deb), for distributions that do not have Deb.
- **A can3 option in the export dialog**: when you export a CMO3, you can check whether to also export a Cubism Animator project (.can3).

### Improvements

- **Skeleton editing is reorganized**: the bone tree lists only bones. The selected bone's name, joints, IK, transform, and symmetry axis are gathered in the inspector, and the modes agree. Angle limits are edited as a fan, blend bands as brackets, and the symmetry axis as a draggable line, directly on the canvas. Exact numbers are gathered under "Advanced", and they can also be typed beside a canvas handle. Skeleton editing, the pose tool, and object mode use the same bone selection and hit testing.
- **Bone shapes distinguish kinds**: a flexible bone is drawn as a rectangle of even width, the torso is thicker, an anchor is a diamond with no tail joint, and a fixed IK end carries a dot. Colors are fixed by the bone's role. Adding a bone no longer recolors the other bones.
- **IK display is clearer**: the canvas shows the bone chain IK can rotate, the starting pivot, and an arrow for the fixed bend direction, with a glow, a direction arrow, joint rings, and an end reticle. The inspector has a legend.
- **Running from source does not require Rust**: when no usable cargo is available, the runtime library that CI built from the same sources is downloaded automatically (sha256 is checked; on failure the editor evaluator is used instead).

### Fixes

- **Blend shapes in a moc3 export**: when a mesh under a Warp has no keyform (for example a skirt after a cloth bake) and it carries blend shapes, the exported model's mesh no longer flies off the canvas after a parameter leaves the neutral position (#29).
- **A fast undo no longer drops a step**: the editor stays busy until undo and redo finish, so pressing undo repeatedly no longer drops one of them.
- **Deleting a part that was split out** no longer reports "Generation layer has no current artwork".
- **A project that was split keeps its original revision after it is reopened**.
- **Rotation deformers**: a keyform that sets only some of the origin, angle, or scale is no longer discarded. An MCP-created rotation deformer keeps the pose by default, and the mesh no longer jumps off the canvas. The origin of a rotation deformer under a Warp is converted in canvas pixels, and the exported moc3 no longer differs from the editor by several pixels.
- **Shrinking a parameter range** pulls motion keyframes into the range with it. Deleting a parameter also deletes its motion curves. The motion stays editable.
- **Committing a pose while adjusting a settings slider** no longer conflicts, and the slider no longer snaps back.

## v3.3.1 · 2026-10-10

### Added

- **Recover unsaved edits after a crash**: after the program crashes, or is closed because it ran out of memory, the next launch asks whether to recover the last unsaved edits. After recovery it opens as an unsaved project. Save it to keep it.
- **A repair notice for an old version's merge result**: when the project has a generated result that an old version merged, and that a new version would merge into a different structure (it can show up as the arm leaving the shoulder when the forearm bends, or a mesh that does not rotate with the upper body), the top of the edit canvas shows a notice. You can "Update generated results" or "Preview the change" directly. After the repair, later edits are kept. The regeneration check of MCP `workspace_inspect` lists these places as well (`REGENERATION_REPAIR_AVAILABLE`).
- **"Back to latest" in the history panel**: checks out the latest version in one step and scrolls to it.

### Improvements

- **Parameter names are no longer cut off**: the name column of the parameter panel is wider by default, and the divider stays faintly visible in the normal state.
- **Physics presets are remembered per group**: preset selection starts from the preset that matches the current group's settings. Each group remembers its own selected preset.
- **The window remembers its size**: the window opens centered, at the size and maximized state it had when it was last closed.
- **Export progress**: before export has reported progress, the progress bar shows an indeterminate state, instead of sitting at 0.
- **Memory use**: the program sets its limit to half of this machine's memory, and it no longer reserves a fixed 8 GB. During `gradlew run`, Gradle itself also uses only 4 GB.
- **Projects are smaller**: saving no longer includes the view images rendered by MCP tools (there could previously be hundreds of them, tens of MB). An old project still opens as usual.
- **GIF colors are more accurate**: each motion builds a palette from its own frames, and dithering is on by default (the export setting `dither` can turn it off). A color cast and color blocks no longer appear.
- **A texture-budget notice (MCP)**: when a higher-density image goes past the texture-page budget, or its density is reduced, `atlas_get`, the texture-edit result, and a batch edit all say so, and they give the handling of `atlas_pack` and `atlas_set_budget`.
- **MCP failure reasons are clearer**: `view_sample_motion` gives the reason and an alternative when the runtime does not respond, and it is no longer shown as "cancelled". `model_apply_preset` warns when the bake and the simulation differ a lot, or when generated hair swing was replaced. `motion_seed_builtin` says to create one with `skeleton_auto` first when the skeleton is missing.

### Fixes

- **Skeleton and regeneration merge**: rebuild a mesh first (for example a sleeve) and then create the skeleton, or do it in the opposite order, and bending the forearm no longer makes the arm leave the shoulder, and the bones no longer drive the sleeve twice. A user mesh under a deformer the skeleton has taken over moves to the new parent and keeps its canvas position. A mesh bound outside the bone's range is skinned as well. "Tools → Update generated results" redoes an old version's merge by the new rules. Running it again does not produce a new commit.
- **A layered PSD exports a high-resolution layer**: a layer replaced at 2× resolution is scaled by the canvas size on export, and it no longer shows only the top-left quarter.
- **Mouth shapes**: the open-mouth layer beside the closed mouth, and the lip line, no longer grow an extra line before the mouth opens.
- **Fast typing in a text field no longer drops characters**: typing quickly in a field such as the export folder no longer drops characters, and backspace no longer needs to be pressed twice. The export folder must be an absolute path.
- **Sprite sheets**: when the frame count and the grid size do not match, it reports the error before rendering, and it no longer waits until every frame has rendered.
- **Running from source**: when rustc is older than 1.87, the Rust runtime is skipped and a notice is given, and `gradlew run` no longer fails because of it.
- **MCP**: a long run no longer keeps every request result (including images). Only the most recent 512 are kept. The command examples in the MCP dialog hide the token.

## v3.3.0 · 2026-10-10

### Added

- **Importing an image is redone** (measured on the `tml` example: importing a 1024×512 image takes about 0.5–1 second, and each later move, scale, and rotate commit takes about 40–60 milliseconds; previously every frame of a drag rebuilt the whole model): an imported image arrives with its own mesh in one commit. Move, scale, and rotate it directly with the transform tool. Dragging is as smooth as for other objects. There is no longer a "placement panel" that rebuilds the whole model every frame. Undo cancels the import. After the image is parented under any deformer, it can still be moved again at any time. The pixels are never resampled.
- **The layer panel shows where the pixels go**: hovering a layer name shows the original image size, the texture-tile size (as a percentage of the original), the size shown on the canvas, the vertex count, whether it is an imported image, and whether it has been transformed as a whole.
- **A mesh ID no longer changes with classification or sibling layers**: before you change a layer's classification, or add or delete a layer, the IDs of every existing mesh are pinned. Previously, changing classification renamed the meshes and renumbered layers of the same kind, and after another generation a new mesh appeared beside the old one (a "ghost" mesh). Deleting one layer also renumbered the meshes of layers of the same kind.
- **Deleting a layer is a real delete**: when a layer is deleted, its pixels and its mesh leave the project (together with the masks, Glue, paths, and weights that refer to them). The PSD layer also leaves the generation input, and it no longer keeps affecting the deformer frame behind the scenes. Undo restores it. There is no longer "restore all". A layer that was soft-deleted in an old project can still be restored. MCP `layer_soft_delete` becomes `layer_delete`.
- **A mesh-rebuild button and a coverage notice**: the mesh panel shows the layer's vertex count and edge length (canvas units), and it provides "Rebuild from the current pixels" (MCP `layer_mesh_rebuild`). Keyforms and bindings migrate to the new mesh. After painting or replacing the image, when pixels fall outside the mesh, the panel says what fraction will not be shown. A layer that was moved stays in place after the mesh is rebuilt.
- **Transform a layer as a whole (`layer_transform`)**: any layer (imported or from a PSD, with or without a parent and bindings) can be moved, scaled, and rotated as a whole. Pixels, the texture, and texture coordinates stay the same. It does not trigger regeneration, and the position is kept after regeneration. Dragging a whole layer in select mode commits this operation. Painting lines up with the layer's new position, and a rotated layer can be painted directly on the canvas: a stroke lands on the layer's own pixels, and rectangles and ellipses rotate with the layer. When you paint on a layer that has been scaled, or whose pixel density differs from the canvas, the size of the brush, pencil, eraser, and shapes is converted automatically into that layer's pixel count, so on screen it looks the same size as before (for example a high-resolution imported image no longer shrinks to a small dot the moment you enter), and the cursor ring matches the actual stroke size. The position and size fields on the texture panel become the same operation, and they are available for any layer.
- **Export by export target (MCP)**: `project_export_target` matches "File → Export as". It exports the committed model by one target (runtime, web, images and video, layered PSD, and others) and returns the files and the losses.
- **Simulation obstacles (MCP)**: `obstacles` of `simulation_put` adds circular or capsule obstacles that follow other meshes (arms, legs, torso) to a simulated body. Hair and cloth are kept outside them. They take part in the bake, and they export to p2lrt and the web.
- **Preview a generated-result update**: "Tools → Preview generated-result update" first tells you whether the update will change the model, and which items need to be looked at. It does not modify the project.
- **A regeneration dry run (MCP)**: `workspace_preview_regeneration` dry-runs, before commit, edits that regenerate the rig, such as changing settings, changing classification, a split, and updating generation. It reports what cannot be kept cleanly in the merge, and the objects that will be added and removed. It does not modify the project.
- **Export a Cubism Animator project (.can3)**: when you export a CMO3, the model's motions (preset motions and clips you made) are also saved as a `.can3` of the same name, one scene per motion. Open it together with the cmo3 in Cubism Editor's Animator to keep editing, then export motion3 again. Parameter curves, part opacity, model opacity, fade in and fade out, looping, and events are all written. Blink and lip-sync effect curves have no matching track in Animator, and they are listed in the export report. The can3 refers to the cmo3 by file name. The two need to be in the same folder and come from the same export.

### Improvements

- **Changing generation settings no longer mixes in a rewrite**: one edit that both changes generation settings (or classification) and rewrites an earlier rig edit is refused, and you are asked to commit them separately, instead of quietly replaying the old edit onto the new generated result.
- **A part keeps following generation after a split**: when a layer is split (connected components, a polygon, or a depth split), the part takes part in automatic generation at its position on the original layer. Keyforms, paths, weights, and Glue you made on the original layer are carried onto the part. Later, changing face settings, the standing pose, the skeleton, or the classification updates the part as well. A split no longer falls back to the old format because of an individual condition.
- **Changing generation settings and classification is more stable**: when you change generation settings such as facial features, mouth shape, head-and-body strength, or layer classification, the new generated result is merged directly with the edits you already have and saved as a checkpoint. It is no longer re-derived every time the project opens. An old edit does not become invalid because the generation rules changed.
- **A long edit history no longer gets slow**: while you edit a generated model, the model is checkpointed automatically at intervals (the first edit, then every 32 steps). Later commits, undo, redo, and drags recompute only after the most recent checkpoint. That does not depend on how long the history is, and the whole base model is no longer regenerated for it. A simulation bake likewise no longer waits for the base model to generate. A project edited this way is saved in a new revision format, and an older version of the program cannot open it.
- **An old project recomputes only once**: a project saved by an earlier version, with an old split record or a generation-migration record, still opens. On the first edit the current model is checkpointed, and after that it is no longer replayed from the old record. Opening and editing are faster and more stable. Parts from an old split are kept as meshes of your own.
- **Select mode moves and scales an imported image**: in select mode, dragging or scaling an imported image (when it is not rotated, not bound to a motion, and in the default pose) changes the layer's position and size on the canvas. The image's own pixels stay the same. Entering paint mode no longer jumps the layer back to the size it had on import. A layer that is bound to a motion, that has been rotated, or whose mesh has been edited on its own still deforms the mesh the original way.
- **A paint brush is counted in the part's own pixels**: the px size of the brush, pencil, eraser, and shapes is counted in the pixels of the layer being painted. A 16 px brush on a high-resolution layer that is displayed smaller is 16 pixels on the layer. The smallest stroke can be a single pixel.
- **Opening and saving a project shows progress**: while a `.psd2live` is opened or saved, the status bar at the bottom shows the current step (unpack, read history, build the model; record edits, write history and materials, pack and verify) and a percentage. A large project no longer looks stuck with no feedback.
- **The simulation solve is faster**: the bend constraint of hair and cloth simulation computes the angle only once each time. The reference simulation, the live simulation, and the bake are about a third faster. The motion result differs slightly from before. The editor and the PSD2Live runtime (including the web player) still agree frame by frame.
- **The simulation starts more steadily**: the shape of an object at rest is calibrated more accurately (the same time cost, and the difference from the drawn shape is about a third to a half of what it was). The slight wobble after the reference simulation and the live preview reset drops from about 3–6 px to 1–1.5 px (skirt, top, long hair). The wobble that was mixed into the bake's training data shrinks with it.

The two items above change the simulation result. Existing bakes all show as stale. Bake again to update them.

### Removed

- **`spatial_reference_id` of `asset_import_png`**: import accepts only the `reference_id` returned by `asset_prepare_reference`.
- **Tools → Upgrade split records** (and MCP `source_upgrade_split_records`): a new split takes part in generation directly. An old project is checkpointed on the first edit, and a separate upgrade is no longer needed. A split-task result no longer carries `record_version` / `record_version_reason`.

### Fixes

- **Export matches the preview**: "Export Live2D model" (and MCP `project_export_model`) writes the model already committed in the editor directly, and it no longer generates it again from the source image. The exported moc3 is byte-for-byte the same as the preview. Exporting a reopened project no longer generates the base rig first.
- **Baking swing**: on a model that has been edited, deleting a swing and choosing "Keep as keyforms" (`swing_delete bake=true`) no longer reports an error.
- **Importing an image**: after an atlas layout has been saved, placing or scaling a high-resolution imported image no longer reports "does not fit an atlas page". A tile that does not fit on one page is scaled down on its own. An imported image can be dragged under another parent in the hierarchy panel (previously it reported Object not found). On a character whose head is tilted, importing an image onto the head rotation and the deformers under it no longer reports a position offset.
- **Changing a layer's classification after a split**: in a project that has split a layer and also rebuilt a mesh while painting, changing the classification of any layer (including a preset of an imported image) no longer reports "Art primitive part was not generated". An image imported after a preset such as hair simulation can also change classification (previously it reported Mesh creation artwork is missing).
- **An atlas packed by mesh shape**: after you paint on a layer past the original mesh, neighboring tiles nested with it no longer cover each other, the texture no longer displays incorrectly, and you do not have to pack again. After painting, the tile occupies the cells that were newly painted, and a neighbor that conflicts with it moves to an empty spot. A tile that was pushed into an empty spot also writes only its own mesh cells, and it no longer covers a neighbor with a whole rectangle.
- **A hierarchy drag changes the parent**: when you drag a mesh under another deformer in the hierarchy panel (or change its deformer in the inspector), the mesh stays where it was on the canvas, and the keyforms and blend shapes are converted with it. Previously the mesh kept its original local coordinates. Dragging it from the root level or from a rotation deformer into a Warp flew it off the canvas, and dragging it from a Warp to the root level shrank it to a point. An image that was moved or scaled in select mode after import disappeared as soon as it was parented under a deformer.

## v3.2.0 · 2026-10-09

### Added

- **Runtime C API 1.1**: when you play an exported model in your own program or engine, several loads of the same character can share one model. You can advance motions, expressions, behavior, and physics by layer, and stack your own parameters on top of the motions (for example face capture). You can read parts, set part opacity, and read an isolated part's draw group and composite mode and the opacity composite mode. You can read parameter names and types, parameter-panel groups, a motion clip's duration and group, and jump the motion progress. The header states the full draw rules (premultiply, Multiply/Screen, masks, cull direction, and the formula of each blend mode) and the threading convention. A program that uses only the old API is unaffected.
- **Runtime C API 1.2**: motions can play on several layers at once, with priority (the same as Cubism's motion manager). A motion can carry events (user data of a Cubism motion), and it can also drive part opacity, model opacity, and blink and lip-sync effects. A single curve can have its own fade in and fade out. Several expressions can be active at once. You can set the model opacity, and replace a mesh or a part's Multiply/Screen color with your own color. Physics supports wind, stabilization (start from rest, instead of a fling first), and vertical (Y) input and output. Blink interval, breath, and gaze amplitude are adjustable. Lip sync can be driven directly by audio samples. When a moc3 is exported, a motion's events, part and model curves, and curve fade in and fade out are written into motion3 as well.
- **A built-in software renderer in the runtime**: C API 1.3 adds `p2l_render`. You can draw the model into an image without writing render code (masks, isolated part groups, every blend mode, and culling, pixel-for-pixel with the editor's image export). It suits C/C++ programs, engine plugins, and generating thumbnails.
- **A C# (Unity) binding and an Android build of the runtime**: adds C# declarations generated from the header, a Unity sample component, a C/C++ sample program, and a script that builds the runtime library for Android (arm64-v8a, armeabi-v7a, x86_64).
- **Windows ARM64 and Linux arm64 installers**: adds a portable ZIP and an EXE for Windows ARM64 (Cubism native preview is not included; Live2D does not provide Cubism Core for that platform, and preview uses the PSD2Live runtime) and a Deb for Linux arm64 (Cubism native preview uses Live2D's experimental arm64 Core). Neither has been tested on real hardware yet. On a GPU that only supports OpenGL 3.1 (for example a Raspberry Pi), the canvas may be unable to draw.
- **Runtime C API 1.4**: you can update only the meshes that actually change each frame (the same as Cubism's dynamic flags), receive the runtime's error log, and have the runtime use the host's own memory allocator.

### Improvements

- **Simulation baking is driven input by input, closer to the simulation, and faster**: the bake first reads from the rig how far each input moves the object. An input that barely moves it (for example a head turn on a skirt) no longer takes part, and the object no longer wobbles for no reason when the user moves that input. An input that moves the object up and down is filed under the up-down parameter automatically. Each input then does a step and a sweep on its own. Inside the pendulum, every input gets an influence that moves the object the way that input does. Previously every input moved at random inside the same motion clip. On `tml` back hair, the body's left-right turn was barely read by the pendulum, and the exported amplitude while following the mouse was only two tenths of the simulation. It now matches the simulation. The model preset's back hair and front hair bake is generally more accurate on motions it has not seen, and the time drops from about 4–13 seconds to 2–7 seconds. Cloth and the up-down parameter are still on the weak side. An existing bake does not go stale. Bake again to use the new method.
- **The bake shows a visual check**: the bake section first shows, on a motion it has not seen, the ratio of exported amplitude to simulated amplitude, the lag, the settle, and the jitter. When a difference can be seen, it is shown in a warning color. R² becomes secondary. The input section shows how far each input moves the object. An input that was excluded can be checked "Keep".
- **Compare the bake with the reference simulation**: the physics-body editing area adds a "Compare" section. The reference simulation can be shown instead of the export, as the export only, or as a ghost (translucent, stacked on the export). "Side by side" opens two preview canvases and shows the export and the reference separately. When you pick a motion and "Play", the reference simulation and the exported pendulum start from rest together. "Check" runs a visual check with that motion in the background.
- **Motions that take part in training**: under "Advanced" you can let the model's own motions (user motions or built-in motions) take part in bake training by weight. This suits a model that is driven mainly by a few fixed motions.
- **MCP**: `simulation_put` adds `force_inputs` and `training_clips`. The bake summary returns each input's read result (`inputs`) and the visual check (`visual`). Adds the read-only task `simulation_compare`, which compares the bake result with the reference simulation by motion.
- **Exported images and video composite the way the editor does**: exported PNG, GIF, video frames, and per-pose PSD now composite an isolated part as one layer (including the group's opacity, Multiply/Screen color, mask, and blend mode). Every opacity composite mode (atop, out, conjoint, disjoint) is also drawn the way the editor draws it. Draw-order groups are sorted by the part's draw order. A mesh with culling on draws only the front.
- **The web player fully supports blend modes**: the exported web player draws every color and opacity blend mode and isolated part groups (including the group's opacity, Multiply/Screen color, and mask). Modes other than Overlay and Multiply are no longer drawn as a normal draw. A mesh with culling on draws only the front.
- **The Godot node fully supports blend modes**: `P2LCharacter` draws isolated part groups (including the group's blend mode, opacity, Multiply/Screen color, mask, and nesting), every opacity composite mode, and inverted masks (previously an inverted mask was drawn as no mask). A mesh with culling on draws only the front. "Add (glow)" is drawn the way the editor draws it.
- **Details on platforms other than Windows**: on macOS, Cmd+Q and Quit on the application menu ask to save first. The menu bar and the Dock show PSD2Live's name and icon. Canvas Ctrl+click, and multi-select in layers and the tree, also accept Cmd. When opened from Finder, it can find ffmpeg installed by Homebrew / MacPorts. On macOS and Linux, the texture-upscale command line default and the MCP stdio configuration use `python3`. The installer ships `mcp_proxy.py`, and the agent dialog gives its path.
- **Runtime preview fully supports blend modes**: when preview uses "PSD2Live runtime (p2lrt)", every color and opacity blend combination and isolated part group is drawn by the editor's rules, and it no longer falls back to the nearest basic mode.

### Fixes

- **A skeleton created after a split or after hair simulation is turned on can move the hands and feet**: in a project that split layers on the Start screen, or that turned hair simulation on, creating or changing the skeleton afterward showed the bones, but it did not generate arm and leg parameters, and the hands and feet did not move in the exported model or in motions such as wave and cheer. The skeleton now bakes into the model normally. Turning hair simulation and similar settings on or off directly in Settings is the same. For this kind of change that was made and saved in an affected version, open the project and run "Tools → Update generated results" to fill it in.
- **Automatic bone Glue is restored** (it had stopped working since v3.1.0): when a limb is split into several segments (for example the leg and the shoe split left and right, or a sleeve cut at the elbow) and the skeleton is then enabled, the automatic Glue on the seam (`GlueSkel__…`) disappeared, and the part that was split later was no longer parented under the bone and did not rotate with the bone. The cause was that, after the first split, later split validation did not consider the paired Warps the split had newly generated, and it fell back to an old split record, while the merge that runs when the skeleton is enabled did not carry the skeleton's skinning and Glue over to an old-format split part. Both places are fixed. A new split writes a new-format record as usual. When a project with an old-format split record enables the skeleton, the parts are skinned and glued automatically. For a project that enabled the skeleton in v3.1.0–v3.1.2, undo to before the skeleton was enabled and enable it again to restore it.
- **A skeleton created after generation settings such as facial displacement are turned on no longer blows the mesh apart**: in a project that chose the "Full" preset on the Start screen (or that later changed generation settings such as facial displacement or head/body strength) and then enabled the automatic skeleton, the skeleton did not skin any mesh (the arms did not lift in a motion such as cheer), and the tail became large triangles covering the whole picture when the tail swung. The cause was that, after the generation settings changed, the meshes were parented under a renamed copy of the generated deformer. The merge that runs when the skeleton is enabled treated every mesh as if the user had changed its parent, kept it in place, and also brought in the pose blend shapes the skeleton had made in bone space. The merge now lines up by the renamed deformer, and the meshes skin normally. A mesh that keeps the user's parent no longer brings in blend shapes that were generated in another parent's space. For a project that was already affected, undo to before the skeleton was enabled and enable it again.
- **"Add (glow)" in an exported frame**: it is drawn as the editor's W3C Add, and no longer as Cubism's old Add.
- **Switching motions quickly no longer jumps**: in the web player and the runtime, switching motions again while the previous motion was still fading out dropped the earliest motion outright, and the picture jumped. Every replaced motion now fades out completely.
- **Several characters no longer blink at the same time**: when one program loads several characters, their blink timing used to be identical. They are now offset from each other.
- **A runtime error no longer crashes the editor**: when the PSD2Live runtime (p2lrt) hit an internal error while evaluating a model, the whole program used to exit. Now only that model stops responding. Software preview automatically switches to the editor's own evaluator. The runtime preview backend and the export report the error. The program keeps running. A runtime library whose interface does not match (for example an old version given by `PSD2LIVE_RUNTIME`) is no longer loaded, and the editor evaluator is used instead.

## v3.1.2 · 2026-10-09

### Improvements

- **Log columns are adjustable**: a column-header row is added above the log. Drag a header to change the order of time, source, tag, and message. Drag the right edge of a header to change the width. Right-click to show or hide the time, source, and tag columns, or to reset the columns. The layout is remembered.
- **Auto-scroll**: the icon becomes a down arrow beside the log rows. While it is following, the arrow is at the bottom and the latest row is bold. Scrolling up stops following automatically. Scrolling back to the bottom resumes it automatically.
- **Log levels become icons**: the level menu becomes an icon button on the toolbar. The icon shows the symbol of the selected level (a bug for debug, a circled i for info, a triangle exclamation for warning, a circled cross for error). Each menu item also carries the matching symbol. There is no longer a separator between auto-scroll and clear.
- **The log search box width follows the content**: when it is empty it is shown at the width of the hint text. As you type it grows with the content, up to half the toolbar. The filter tags beside it are no longer squeezed into "⋯".
- **Hierarchy-tree sort**: the hierarchy-tree toolbar adds a sort button. The default is by height (top to bottom on the canvas, hair in front and feet behind). It can also sort siblings by model order, horizontal position (left to right), draw order (front to back), or name (numbers compared by magnitude), and it can switch to reverse order. A deformer takes part in the sort by the position of every mesh under it, or by the front-most draw order. A deformer with no mesh sorts last. Deformers still sort before meshes. The selected sort is remembered. The button highlights when the sort is not the default.
- **The draw-order ruler prefers the selection**: the selected item's tick spans the whole ruler. When it overlaps another tick, dragging, double-clicking, or right-clicking on it changes the selected item, and it no longer changes an overlapping unselected item by mistake. That other item is selected only in the short tick area on the right of the ruler, and only when it is clearly closer to another tick.
- **The draw-order ruler previews the mesh on hover**: hovering a tick shows a mesh texture preview, the way the hierarchy tree does. When several ticks overlap, it lists every mesh there at once, with the name, the color mark, and each draw order. The selected item is highlighted.

### Fixes

- **Front-hair simulation no longer flings wildly**: front hair lies on the forehead and sweeps across it. It does not hang naturally the way back hair and clothes do, but it used the same hair material. The drawn shape was held up almost entirely by a very weak return force. On a head shake the amplitude was about twice what the head drove, and a nod bounced up and down as well. Front hair generated by a model preset now uses the new "bangs" material (stronger return, more damping). The amplitude on a head shake and a nod is clearly smaller, and the baked pendulum is closer to the simulation. When front hair in an existing project is still the default hair material, apply the front-hair preset again to switch it to the bangs material. A material you have changed stays as it is.
- **Alt + right-drag to adjust the brush no longer sticks to an axis**: after a left-right drag adjusts the radius, the next up-down drag no longer keeps using the radius adjustment. When the key is released and was not captured, the gesture also ends on the next move or press. The direction locks only after you drag about 12 pixels and it is clearly more horizontal or more vertical. Starting on a diagonal no longer locks the wrong way easily, and movement before the lock no longer makes the value jump.
- **The Windows installer switches to Inno Setup**: the EXE installer copies files directly. An overwrite install no longer goes through the MSI step that first moves the old files into `Config.Msi`, which avoids some computers reporting "Error writing to file … .rbf" on an overwrite install and being able to retry only after the install directory is deleted (#14). An upgrade installs back into the original directory. EXE / MSI installs from 3.1.x and earlier are removed automatically and the original directory is reused. Files of your own in the install directory are kept. If PSD2Live is running before install, you are asked to save, close it, and retry. The default is an install for all users. At startup you can also choose to install for the current user only (no administrator permission). The installer UI follows the system language (Chinese, English, Japanese, Korean), and creating a desktop shortcut is optional. Uninstall asks whether to delete PSD2Live's data as well (kept by default). An MSI installer is no longer provided.
- **The Windows package with ffmpeg bundles ffmpeg again**: since 3.0.1, the `-ffmpeg` portable build and installer were actually the same as the ordinary package and did not contain ffmpeg, so exporting video and animation still required you to install it yourself. Those packages now bundle ffmpeg.

## v3.1.1 · 2026-10-09

### Added

- **Log-level filter**: the log panel adds a "Level" menu. You can show debug and above, info and above, warning and above, or errors only. Each item shows a count beside it. Debug is hidden by default. Copying the log includes the level.
- **Log coverage is wider**: every change the user makes in the editor (named the same as the history tree), undo / redo / switching history, opening and saving a project, and an error a window pops up are written to the log. A status-bar message is recorded at debug level. Starting, stopping, and failing to start the AI interface, and an AI client connecting and disconnecting, are recorded as well.
- **Log style**: the source is renamed "System / User / AI". A color bar on the left of each row marks the level. Error and warning rows have a faint background. Time, source, and tag each take a fixed column, and the message lines up. Details such as request parameters are folded by default. Click the arrow at the start of the row to expand them. Consecutive repeated rows are merged into one row and shown as "×N". A rendered-image card highlights on hover. Click it to view the large image.

### Improvements

- **Preview playback is smoother**: on a 240 Hz display, the preview workspace plays at about 230 FPS. The edit workspace rises from 70–110 FPS to about 215 FPS. The animation and physics workspaces also improve clearly. The window caches the interface picture on the GPU. During playback only the preview itself is redrawn, and the whole window is redrawn only when the interface changes. The parameter panel redraws only the sliders and the numbers as playback proceeds. A number field is shown as text until it is clicked or focused. A refresh that follows playback does not exceed 60 Hz. Panels that do not show the pose, such as the hierarchy tree, the skeleton, the log, and the layer table, are no longer recomposed as playback proceeds. Every icon is drawn once and then shown as a bitmap. If the interface displays incorrectly, turn the picture cache off with `-Dpsd2live.compositor=false`.
- **Interface drawing uses less CPU**: the window is drawn through 4× multisampling. Anti-aliasing of rounded corners, icons, and curves is left to the GPU. At a high refresh rate, the edit workspace's frame rate rises by about a fifth.

### Fixes

- **MCP operations and renders show up in the log again**: every call an AI makes through MCP is recorded as one line. A write (success), a rendered view (info level, with the rendered image attached, and several images each take a row), a query (debug level), and a failure (error; a state conflict or a busy workspace is a warning) are each marked, and the time taken and the request parameters are shown.

## v3.1.0 · 2026-10-08

### Added

- **Recent**: **File → Recent** lists recently opened projects and PSDs, and it can be cleared in one step. "Recent" on the Start screen is the same. Whether a file exists is checked in the background. A file that is not there is not shown, and a disconnected network drive does not freeze the interface. "What's new" on the Start screen and the interactive tutorial are updated to the current version.
- **Update generated results**: **Tools → Update generated results** (MCP `rig_update_generation`) regenerates with the current version's generator and merges your edits onto it. A generated result that is already merged and saved in the project does not change automatically because the program was upgraded.
- **Tool options bar**: the bottom left of the canvas shows the current tool's numbers (radius, strength, hardness, opacity, mode, and others). Point / edge / face, and confirm / cancel for the current step, are on the mode bar at the top. Drag a number left or right to adjust it. Click it to pop up a slider. The right-click menu shows the same set of numbers and that tool's operations. Each operation has an icon. Each number is one row. On open they slide in row by row, and on hover the highlight slides between rows. Option buttons on the same row also have a sliding selected block and hover block, matching the mode menu. Only when the content is too long is the largest group folded into a submenu. Scrolling is no longer needed. The two places agree on range. Brush shortcuts such as `[` `]` adjust the same value. The tool panel keeps only advanced settings such as an exact transform, creation defaults, the path list, skeleton binding and weights, and the palette.
- **Number-key options**: `1`–`5` selects the nth item of the current mode or tool: a mesh deformer's level, point / edge / face in edit mode, and brush shape, Glue and skeleton sub-tools, weight-group kind, and paint shape. The toolbar shows the matching number beside each item.
- **Create tools**: the left toolbar in select mode adds create Warp, Rotation, and deform path, matching the hierarchy tree's add menu.
- **Weight-brush shapes**: the weight brush in simulation mode can use a line and a rectangle tip, and it can be rotated.
- **Point / edge / face in deform mode**: deform mode can select the mesh by point, edge, and face, the same as edit mode.
- **The edit canvas no longer shows mesh statistics**: the ArtMesh, vertex, triangle, and zoom hints at the bottom left are shown only on the preview canvas.
- **Deform level**: the mode bar shows it only when the target is a mesh deformer. It is renamed "Vertex" and "Bezier", and level 3, which made no difference, is removed. Under Bezier the detailed mesh fades and cannot be edited. Curves, anchors, and handles are recolored.
- **The transform tool can box-select**: when nothing is selected, the transform tool can click-select or box-select, like the select tool.
- **Skin-weight display**: "Show skin weights" moves to the display bar at the bottom right.
- **Runtime advanced mode**: it can be turned on while an exported runtime model plays. It is off by default. When it is off, the result matches Cubism completely.
  - **Bone skinning**: a bone bend such as an arm or a tail moves along a real arc between keyframes, and it no longer travels in a straight line. The host can read bone positions to attach objects.
  - **Live simulation**: baked cloth and hair run live with the editor's simulation, and wind can be added.
  - **Collision**: circles or capsules along the body mesh can be added in the simulation (`obstacles`). The bake also learns the motion after collision.
- **Part exclusion and zstd compression**: export settings can name a mutually exclusive part group (for example two kinds of arms, and only one is shown). The runtime fades in and out automatically when it switches. Chunk compression can be zstd.
- **The preview backend is selectable**: the bottom three rows of the preview's left toolbar switch among Cubism SDK, p2lrt · standard, and p2lrt · extended (extended is advanced mode: skinning along an arc, and live cloth and hair). The menu of the status capsule at the bottom left can switch too. When there is no Cubism SDK, preview uses the PSD2Live runtime automatically, replacing the former software-fallback preview.
- **The preview frame rate follows the display**: the preview's frame-rate cap is independent of the physics frame rate. It follows the display refresh by default, and it can also be limited to 30 / 60 / 120. When several previews are visible at once, they are no longer limited to 30 FPS each. The frame rate on the left toolbar is renamed "Physics frame rate", and it decides only physics and export.
- **Expressions and hit areas**: a runtime model carries four expressions, smile, sad, angry, and surprised, and hit areas for the head and the body. The web player and the Godot node can switch expressions, do hit testing, and toggle advanced mode.
- **Korean layer names are recognized**: Korean layer names such as 앞머리, 눈동자, and 상의 are classified automatically. Left and right are written 왼쪽 / 오른쪽 (or 왼 / 오른 stuck to the name). The Korean Photoshop copy suffix "복사" is ignored.

### Improvements

- **A unified mesh display**: editable points and lines use one look in every mode. Color meanings are fixed: a light color is structure, the emphasis color is selected, a white ring is hover, and a red ring is a point the brush will push. Every deform brush marks the vertices it will affect as soon as you hover, matching the weight brush. The select brush marks the points it will select. Warp control points become square points, distinct from the round mesh vertices. The mesh toggle on the display bar draws only mesh lines. The mesh and Warp being edited are no longer drawn a second time.
- **Unified GPU rendering**: the edit canvas, the atlas page, and both previews are drawn in the window's own OpenGL context and shown that frame. The whole picture is no longer read back into memory and uploaded again every frame. Pan, zoom, and playback no longer lag by a frame or two. Because of this, the interface uses OpenGL rendering on Windows. Cubism preview should rebuild `native/live2d_renderer`, otherwise every change reloads the model from a temporary file.
- **Runtime model format 2.0**: an exported `.p2lrt` becomes a chunked format. It can be compressed and checked, and it carries parameter groups and snap values, so new features can be added later without breaking old files. The runtime, the web player, and the Godot node read both the old and the new format. When you need to give it to an old player, check "Version 1" in the export settings.

### Fixes

- **Setting keys on simulation and swing parameters**: the way Cubism uses a physics output parameter, other meshes or Warps can set keys on simulation and swing parameters, and those parameters' names and ranges can be changed. Previously it reported "parameter is not in the model". Some operations (a channel keyform, creating a new axis at the default, deleting a generated key, renaming in the hierarchy) did not report an error, but the result was lost after commit. A parameter that is used now belongs to the project, and it is kept after the matching simulation or swing is deleted.
- **Deleting the Warp a swing acts on**: the swing drops that target. When there is no other target, its parameters and physics group are removed with it, and a swing that drives nothing is no longer left behind. When a swing becomes invalid for another reason (for example the target does not exist after regeneration), the reason is shown under that swing's physics group in the physics panel.
- **Bezier and MCP deform on a generated keyform**: Bezier editing or MCP `rig_deform` on a keyform generated by swing or simulation no longer reports an error. Like brush deform, it is saved as a generated-result override. Keeping children while holding Ctrl, copying a keyform, and adding or deleting a generator's key cannot be saved as an override. Editing now states the reason directly, and it is no longer lost after commit.
- **The project will not open, or it reports an error, after generation settings change**: an operation that changes the automatic generation result, such as turning hair simulation on, enabling or adjusting the skeleton, or splitting a layer, previously had to replay the whole edit history on the new generated result. An edit that referred to a deformer or a vertex that no longer existed made the whole project report an error (for example "Art primitive parent is missing" or "Vertex group has … weights for … vertices"). This kind of operation now merges the existing edits onto the new generated result and saves them as data. Those old edits are not replayed afterward. Content that cannot be migrated cleanly is listed in the quality report. A project that contains this kind of save point needs this version or a newer one to open.
- **Opening a project is faster**: saving also saves the model after each history version is edited. Opening, undo, and switching history read it directly, and they no longer regenerate and replay.
- **A deform error on a simulated mesh**: after a mesh has a simulation preset (or another parameter a generator added), editing it with a deform brush, Bezier, and similar reported "parameter is not in the model": the edit recorded the generator's parameter, and the generator had not run yet when the edit was replayed. As in Cubism, an edit now records only the parameters the target itself reads. A swing or simulation parameter that drives the parent does not affect the edit no matter what value it sits at, and a generator parameter of the target itself is not written into the record when it is at the default. Glue also glues on the rest shape the way Cubism does. When a parameter is not at the default, it moves smoothly back to the default first. A glued point no longer pulls at rest.
- **The interface sticks after Alt**: on Windows, pressing Alt alone (or releasing after Alt + right-drag adjusts the brush) puts the window into the system menu mode, and later key presses are swallowed.
- **Canvas tools and menus**: when the toolbar is expanded and long, the tools below no longer punch through to the canvas. The right-click menus of the transform tool, the weight gradient, skeleton mode, and the eyedropper are no longer empty. The Glue tool is disabled and explains why when two meshes are not selected, and it no longer disappears. The shape tool's line width is separate from the brush size. The bone-weight brush can be resized with `[` `]`. The Warp grid-size label follows the interface language.
- **Runtime parameters accumulate frame by frame**: on each frame update, the web player and the C API stacked breath, gaze, blink, and additive expressions on the previous frame's result, and after a while of playback an angle and other parameters drifted into the tens of thousands. Every frame is now recomputed from the pose the host set.
- **Save fails on Windows**: when the project file being replaced is briefly locked, save waits for the lock to clear, and it no longer fails outright.
- **The command-line `--mesh-pixels` reports an unknown option**: this option, which the help listed, could not be used before. It now computes the mesh spacing from the source pixels.

## v3.0.1 · 2026-10-08

### Added

- **A Korean interface**: it can be chosen in Settings. On the command line, use `--lang ko`.
- **Language switching**: the language menu becomes a globe icon on the right of the title bar, beside the theme toggle. After you switch, the whole window changes to the new language immediately.
- **The mesh fits the texture**: a new project defaults to "trace the contour from the texture". The mesh is generated from each layer's own pixels. A stroke finer than a canvas pixel stays inside the mesh. An old project keeps its original method and mesh.
- **Mesh wrap**: adds a mesh setting "Wrap" (it can be set globally, or overridden per layer, command line `--mesh-wrap`). It wraps small protrusions such as eyelashes, hair tips, and fingers into the same contour, and the vertex count drops clearly. It is off by default. An old project is unaffected.
- **A Windows installer that includes ffmpeg**: a zip / exe / msi with the `-ffmpeg` suffix bundles ffmpeg. Video and animation export does not need a separate install.
- **User data can be deleted on uninstall**: after you choose delete in Windows "Uninstall or change a program", you can check "Also delete my PSD2Live data" (unchecked by default).

### Improvements

- **Icons are unified**: icons outside the toolbar are redrawn on the same grid. Lines no longer get thinner on a high-resolution screen. Color is used only to carry a meaning.
- **Skeleton editing is faster**: creating, binding, or moving a bone rebakes only the limbs that are affected, and the work is computed in parallel. Moving an arm joint on a large project drops from about 2–3 seconds to under 1 second.
- **Save and open are faster**: saving no longer re-encodes every image, and it no longer unpacks the whole archive to verify it. When a large project is opened, images are decoded in parallel.
- **A large project is smoother**: filling, classifying, and compositing the atlas, and exporting a PSD of a given pose, are clearly faster.
- **Install and upgrade**: an upgrade or a reinstall installs back into the original directory by default, and it no longer asks about a directory that is not empty. A rebuilt installer of the same version replaces the installed version, instead of sitting beside it.

### Fixes

- **Uninstalling or upgrading on Windows emptied the install directory**, and a project saved under the install directory was lost. Only the installed files are deleted now. The default location of a new project becomes "Documents\PSD2Live". **Before you upgrade from 3.0.0, move projects out of the install directory.**
- Save as no longer points at the file that is currently open by default. The overwrite confirmation pops up inside the window. A save-as requested before an edit has finished is no longer turned into an ordinary save by a later Ctrl+S. The file chooser no longer slips behind the main window.
- Switching between edit and preview keeps the view and the canvas size.
- After the skeleton is enabled, a part that was originally split under the arm-hang deformer reports "Art primitive parent is missing" on replay.
- A mesh bound to a bone, and also placed under another deformer by hand, is in the wrong place or out of range after the bake.
- After the topology of a bound mesh is changed, or after undo, the skeleton result does not match what it was while editing when the project is reopened.
- A layer added after generation has its mesh quietly regenerated after a redraw, and a later rebuild reports "Mesh migration baseline changed".
- A Cubism preview frame is occasionally covered by the software preview, and the pose is lost.

## v3.0.0 · 2026-10-08

The change from v2.0.4 is large: several export formats and an independent runtime are added, textures can be adjusted per layer, and the MCP interface is redesigned. An old project still opens.

### Added

- **More export formats**: **File → Export as** adds VTube Studio, a PSD2Live runtime model, a web player, a PNG sequence, a sprite sheet, GIF, APNG, animated WebP, MP4, WebM, ProRes 4444, and a PSD of a given pose. Each export comes with a loss report that says what the target format dropped. Video and animation require you to install ffmpeg yourself.
- **The PSD2Live runtime**: an open-source (MIT) Rust runtime. It can play an exported model in a browser, in Godot 4, or in any program that can call the C API. It includes blink, breath, gaze tracking, and lip sync.
- **Texture workspace**: adjust texture density per layer. Drag and scale tiles directly on the atlas page. Pack tightly by mesh shape. The layout stays until you ask to pack again.
- **Keep the original resolution**: imported images, materials, and CMO3 textures are no longer scaled down to the canvas resolution. Painting happens at the layer's own resolution.
- **MCP is redesigned**: public operations grow from 25 to 181, and they can be discovered page by page. Up to 128 edits can be committed atomically, or dry-run first to check geometry problems. A long operation runs as a background task. The old tool names are removed. Connect against the new interface.
- Experimental: Spine 4.2, DragonBones 5.5, and glTF 2.0 export, command line only.

### Improvements

- **Splits are more reliable**: parts from a split by mesh, a polygon split, and a depth split replace the original layer directly, and they take part in automatic generation. Later, changing face settings, the standing pose, or the skeleton updates the part with them. The original layer no longer occupies the atlas.
- **Joints are more natural**: when a bone bends, a joint such as the elbow or the knee is shaped from measured human joint poses. The inside no longer shows a folded texture.
- **A manual edit is not lost**: a shape edit on a generated result such as a swing or a simulation bake is kept by a three-way merge after regeneration. A change that conflicts or that is no longer valid is pointed out in the inspector.
- **The File menu is reorganized**: it is divided into "Import", "Export Live2D model", and "Export as".
- **One transform box**: the "Transform" tool, the Warp / layer placement box, and an atlas tile use the same transform box. Drag outside a corner to rotate (on hover, an arc with an arrow lights up outside the corner). A new draggable anchor is the center of rotation and of Alt-scale (at the center by default, with an outline so it is easy to see). The scale cursor rotates with the box's angle. Handles become rounded squares and capsules. While you drag, the angle, the scale, or the translation is shown beside the pointer.
- **Canvas and parameters**: mesh selection and box transform are separate. The deform tool acts on the selected parameter key point. Adding or dragging a key point moves continuously by default, and it snaps only after you choose a step.
- **Project format v2**: content that has not changed is shared across history revisions. An old project still opens, and it is converted automatically on save.
- **Faster**: changing classification, or the mesh of a single layer, recomputes only the part that is affected. Opening a project reuses a saved skeleton result. An edit commit no longer rebuilds the whole model every time. Cubism native preview updates in place.
- **Results can be reproduced**: the skeleton and the exported files generated from the same PSD are identical every time.

### Fixes

- When the mesh is rebuilt after painting, a mesh under a bone, a standing-pose deformer, a lattice that was modified, or an imported deformer no longer shifts or shrinks.
- Animation playback, parameter sliders, and the canvas pose stay in sync. The speed of smooth tracking is no longer affected by the frame rate.
- A tile newly placed in the atlas no longer jumps into a spot another tile left empty.
- On Windows, replacing a file occasionally fails when you save quickly several times in a row.

## v2.0.4 · 2026-10-04

- Fixes the 2.0.3 installer missing the runtime, and GPU drawing not actually taking effect.

## v2.0.3 · 2026-10-03

- The canvas uses GPU rendering, and the whole editor is smoother.
- A large PSD scales mesh sampling in proportion, so there are not too many vertices.

## v2.0.2 · 2026-10-02

- Simulation baking is more adjustable: a training range can be set for each input. The up-down parameter can be automatic / on / off. Each output can change its parameter ID, range, and gain on its own, with no need to bake again. A change the physics panel makes to a simulation pendulum is kept after baking again. See [Simulation and baking](guide/SIMULATION.md).
- Fixes abnormal physics-simulation behavior in the Y direction.

## v2.0.1 · 2026-10-02

- Adds depth split: split a layer into a front layer and a back layer, and use Glue to keep the deformation in sync. It suits interleaving occlusion such as a collar or hair. See the [depth-split tutorial](guide/DEPTH_SPLIT.md).
- A parameter snapshot supports hover preview: hover the mouse to preview a saved pose translucently on the canvas.

## v2.0.0 · 2026-10-02

- **A unified Start screen**: after a PSD is imported, one screen chooses a quick scheme, a model preset, and a mesh split. It can also be opened again from "Tools → Start screen".
- **A more natural, adjustable rig and motions**: idle and the body hierarchy are rebuilt. Motions adapt to the model's skeleton and proportions. Adjustable deformations such as size change (head-to-body ratio) and lean (a solid bend at the waist) are added.
- **Motion presets**: divided into "Basic motions / skeleton motions". A skeleton motion can be enabled and adjusted item by item.
- **Cloth and hair simulation**: material presets are added. The up-down direction can be baked into a Y parameter on its own. Preview can switch "Export result / reference simulation".
- **CMO3 import**: create a new project, or replace the current model.
- The tutorial extends to simulation, model presets, and skeleton and motion adjustment.

## v1.6.0 · 2026-10-01

- Adds in-editor 2D cloth and hair simulation: vertex-weight groups, a weight brush, a reference simulation, and material adjustment. The result can be baked into Cubism parameters, keyforms, and pendulums.
- The skeleton workflow is upgraded throughout: create, extrude, mirror, subdivide, merge, and multi-select transform, FK / IK, joint constraints, pose snapshots, batch binding, and weight painting.
- A model preset can configure simulation of tops, skirts, pants, and similar automatically from how loose the clothing is.
- The canvas adds a unified mode menu, and it adds "Simulate" and "Skeleton" modes.

## v1.5.0 – v1.5.1 · 2026-09-29

- The animation editor supports automatic keyframing. A parameter change records a keyframe automatically.
- A skeleton parameter's sample tolerance, angle interval, and keyform limit can be configured. Skin weights spread along the connected mesh, and the transition is more natural.
- MCP adds skeleton and motion authoring tools.
- Dragging a preview parameter is smoother. Stray pixels outside the mesh no longer affect generation.
