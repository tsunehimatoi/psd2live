## v3.7.0 · 2026-10-27

### New

- **Rig modules by area**: the generated rig is organised into areas (eyes, brows, mouth, head, hair, body, outfit, expressions, motions), each with a choice of base and modules that add up; new projects use the modern recipe with every applicable module on, with presets and a cost budget.
- **Eyes and brows fitted to the art**: lids come from the eye white's outline and the closed-eye line and close down the face when the head tilts or turns; brows turn along an arc and move the upper lids, which carry the mood when bangs hide the brows.
- **A mouth that opens in stages**: the mouth opens by its lip edges in stages, with vowels, face capture, the jaw and pressed / parted lips; mouths drawn in layers (lips, lip lines, teeth, inside) are supported, teeth and tongue move whole with their lips, and the mouth can open past its drawing into a shout.
- **Head turns in depth**: the head turns as a cylinder with features, front hair, back hair and ears at their own depths; far side locks go behind the face at the end of a turn.
- **Hair, outfit and body in chains**: hair bends in segments like a pendulum chain; skirts, ribbons, pendants, animal ears, tails and wings swing in chains; the chest bounces, arms swing and the neck shares the head's tilt.
- **Expressions and natural motion**: Cheek and Gloom moods, richer standard expressions (shy, sleepy and gloomy added), natural blinks and glances, and a VTube Studio mapping tuned for module rigs.
- **Feature points**: `Shift+Y` shows the eyes', brows' and mouth's handles on the canvas; drag to refit, every pose following, Alt-click to return to the art.

### Improved

- Blush, shading and the bangs' shadow are clipped to the face, so a turn never carries them off it.
- Workspace tabs drag smoothly, all the way to either end.

### Compatibility

- Older projects generate with the legacy recipe, byte for byte as before, and are offered an upgrade to the module rig.

## v3.6.7 · 2026-10-24

### New

- **Graphics settings**: a new Settings > Graphics page: render path (GPU / software canvas / software), graphics card, a quality preset, window and canvas antialiasing (MSAA, SSAA), texture filtering, vsync, preview and background frame rates.
- **One call per editor gesture in MCP**: read and set ruler guides, import as reference, preview expressions, soft-selection deforms, symmetric strokes and more; refusals tell their causes apart and writes read back.

### Improved

- **Settings layout**: flat headings and two-column rows instead of cards.
- **Lighter GPU canvas**: less work per frame, and a drag no longer recomposes the toolbars and status bar.
- **Faster saves**: small projects save about a third faster, with identical archives.

### Fixes

- The editing canvas draws on the GPU on Linux again instead of skipping every frame, and the restore prompt no longer opens blank and frozen (#62).
- The editing canvas draws masks, multiply / screen colours and every blend mode as the preview does.

## v3.6.6 · 2026-10-24

### New

- **Free transform of selections**: the Paint free transform (`Alt+T`, or `T` in Paint mode) puts the selection's pixels (or the whole layer) in a box to move, scale and rotate; Enter applies it as one stroke, Esc puts it back.
- **Quick selection and resizing selections**: a quick selection brush joins the selection tool; the paint menu expands or contracts the selection and selects opaque pixels.
- **Whole eyes through the bangs**: eye whites, irides with highlights, lashes and closed-eye lines can show through the bangs, on by default for new projects; rigs made under the 3.6.5 rules update when regenerated.

### Improved

- **Knife folded into Mesh points**: the tool has a Points face (`Alt+M`) and a Line face (`K`) whose polyline lets the mesh fold along it; recorded projects replay as before.
- **Interior handles in Object mode**: `Shift+I` switches to Object mode with no mesh points under the handles; clicking another object picks it.
- **Tab switches modes**: Tab toggles between Object mode and the mode last used.
- **A shorter canvas display rail**: only pinned toggles show, and only when the canvas has something for them; pin or unpin from "View ▾". Onion skin moves to the animation editor's toolbar and random pose to the parameters panel.

### Fixes

- The editing canvas shows inverted masks and add / multiply blends correctly.

## v3.6.5 · 2026-10-23

### New

- **Paint selections and tools**: a selection tool (`Alt+L`) with marquee, ellipse, lasso, polygon lasso and magic wand, adding, subtracting, intersecting, feathering and inverting; brushes, fills and gradients stay inside it. New gradient, blur brush and global fill, with a stabilizer, mirror symmetry and lock transparency.
- **Mesh points**: the Edit-mode mesh point tool (`Alt+M`) adds points by click or stroke, drags them, connects with Shift-click and removes points or edges with Alt-click, optionally mirrored; the topology menu adds delete edges and re-triangulate.
- **Interior handles on the canvas**: `Shift+I` drags bulge, pressure and the propagation arrow and draws and edits fold guides on the canvas, in step with the panel.
- **Keyform tools**: the Deform keyform menu pastes a copied shape onto other keys or objects (blended or flipped), restores the default shape, interpolates between neighbouring keys and edits several keys at once; `Shift+T` lays a temporary lattice over the selected points.
- **Canvas aids**: grid, ruler guides (with snapping, saved with the project), onion skin and random pose, toggled from the canvas's bottom-right corner.
- **Copy, paste and rename**: `Ctrl+C` / `Ctrl+V` in Object mode copy meshes, deformers, parts and parameters; a mesh's topology can be duplicated onto other layers; find and replace by name or ID; convert IDs in bulk with every reference following.
- **Expressions, pose and events**: the animation panel makes expressions from the current parameters (add / multiply / overwrite, fades), sets pose groups of which one shows at a time and puts events on motions; exports write exp3, pose3 and motion UserData, and generated models come with smile, sad, angry and surprised.
- **Hit areas**: set hit areas from the inspector or the hierarchy and click in the preview to test them; exports write them to model3 and the VTube Studio package.
- **Timeline aids and loops**: parameter bookmarks, shy, freeze, markers and motion trails; close a loop in one step, and let a parameter repeat for continuous spins.
- **Brows through the bangs**: generation shows brows covered by bangs semi-transparently above them, with adjustable opacity, and can be turned off.

### Improved

- **Editing alongside the agent**: the runtime is the workspace's only authority; when an agent commit lands, the editor's pending changes yield only the conflicting fields and the rest still commit, and saving records what is really there.
- **Screen blend**: a PSD's Screen layers export as Cubism 5.3 Screen and as an approximated Add for earlier versions, noted in the loss report; generated-result overrides now keep color, opacity and draw order edits too.
- **Window modes**: pick windowed, maximized or full screen under Settings > Display, remembered; `F11` or View > Full Screen toggles full screen, and maximizing no longer covers the taskbar.
- **New look**: panels are rounded cards on the window background with tighter spacing; title bar menus, dropdowns and context menus share one frosted menu style.

### Fixes

- Painting a generated lip outline explains that it comes from the mouth layer and mouth settings instead of reporting a missing source layer; the parameter name's right-click menu unbinds it from the selected mesh or deformer (#55).
- The saved window size stays inside the screen's usable area, so the title bar can't open off screen.
- On large canvases, adding a deformer to a mesh, moving a bone or squeezing a whole root mesh no longer reports false collapses or regeneration conflicts (#56, #59).

### Compatibility

- Projects holding expressions, a pose or hit areas are refused by earlier builds instead of losing that data.
- Screen layers in older projects export as Screen (or Add) after reopening instead of Normal.

## v3.6.0 · 2026-10-20

### New

- **Manual and tutorials**: the help page is laid out like the Cubism manual and tutorial site, each page tagged its relation to Cubism, linked to its Cubism page and ending with its MCP operations.
- **References are hierarchy members**: background, in-stack and foreground references coexist in any number, lock, pin to keyforms, with a continuous opacity slider and Alt+R to show or hide; a reference image and an ArtMesh convert into each other.
- **Mesh along texture structure**: rebuild a mesh's inside along the creases, shading boundaries and pattern lines drawn into its texture, migrating keyforms, paths, weights and glue.
- **Mirror keyforms**: the deform menu mirrors a key onto its opposite key, the opposite-side object or in place; Shift+M mirrors in one step.
- **Drop where you mean it**: the drop position decides the action (material, hierarchy insert, placed layer, reference, image replacement or library-only import); a dropped CMO3 opens directly.
- **Materials and CMO3**: every layer has its material and links no longer break silently; an imported CMO3 gets its atlas packed from the current layers.
- **Compressed history tree**: consecutive linear versions share one card, a toolbar toggle restores the per-node view, and double-clicking a card checks out the last step of its run.

### Improved

- **Edit before generating**: a Layout project not generated yet edits meshes, deformers, parameters and the hierarchy like any project, and exports directly.
- **Smoother**: the edit canvas paints on its own, parameter drags and pose-less panels no longer recompose every frame, and PSD import prepares in the background (#50).
- **Materials panel**: shows usage counts, lists the layers using a material on hover, and its menu edits tags, sets the default reference and selects the using layers.
- **Interior panel**: uses the same toolbar and folding sections as the other inspectors, with the audition as sliders and a 2D pad.
- **Freeze diagnostics**: the console goes to a log file and a UI freeze over 5 seconds writes a thread dump (#52).

### Fixes

- **MOC3 user data**: exporting user data writes `userdata3.json` and references it from `model3.json`.
- **Texture binding**: imported images and reference layers no longer bind their textures to a placeholder tile.
- **Reference layer placement**: a Layout reference layer draws at its own canvas rectangle.

## v3.5.2 · 2026-10-18

### Fixes

- The window no longer freezes or goes black when the GPU cannot multisample or a canvas GPU draw fails; it falls back to a compatible way of drawing (#48).

## v3.5.1 · 2026-10-17

### New

- **Interior shaping**: give a mesh outline-preserving surface changes inside it (bulge, press, folds), try them with the pointer; saved with the project and exported.
- **Unity prefab export**: Export → Unity Prefab… bakes parameters and motions into blend shapes and an Animator, so the character folder drops into a Unity project.
- **Reference layers**: keep a layer as reference (Ctrl+Alt+R): it stays on the canvas but out of the rig and exports.
- **Layout manifest**: open a Layout project from a psd2live-layout manifest; layers not generated yet move to the front or back, or a step forward or backward.

### Fixed

- Fractional draw orders in CMO3 export, CMO3 imports that hung or drifted in draw order, 3.5.0 material and generation regressions, and a crash in the Animation workspace.

## v3.5.0 · 2026-10-15

### New

- **Start, Help and Settings are pages**: all three are pages at the head of the tab strip instead of dialogs. Their panels drag, tab together, float and close like the editor's, the arrangement is remembered, and "Reset layout" restores it. F1 opens Help, Ctrl+, opens Settings.
- **Material library**: import images, PSDs, the layered images inside CMO3 files and SVGs; sort them into folders, tag and preview them, and export a folder as a PSD. Materials are saved with the project. {tutorial=LAYOUT}
- **Place first, then generate**: a Layout project starts from an empty canvas; drag materials onto it and arrange them before generating the rig. Afterwards keep adding images and changing tags, and "Regenerate" merges them in while your edits stay. {tutorial=LAYOUT}
- **Material window**: double-click a material to view and paint it in its own window, with the same tools as the canvas's paint mode; every layer using it updates together. {tutorial=PAINT_MODE}
- **Semantic tags**: the layer table's type, part and side become tags that combine, and you can add your own.

### Improved

- **Long hair bends with a head tilt**: hair below the chin turns back as the head tilts (new tuning "Long hair hold on tilt", 80% by default) instead of swinging out whole.
- **Reference image and close-ups**: a material can be laid over the edit canvas as a reference, kept out of generation and export; close-up materials can be cropped from it.
- **Painting is no longer bounded by the mesh**: paint anywhere on the canvas; what falls outside the mesh becomes new layers when applied.
- **Context menus and dropdowns match**: every panel's menus share one look and animation, opening from where you clicked.
- The editor's "Select" mode is now called "Object" mode.

## v3.4.5 · 2026-10-13

### Fixed

- **Saving and recovery**: saving, closing and crash recovery agree on the project's state, and consecutive pose edits commit in order.
- **Import sources kept**: projects keep the imported PSD / CMO3 content and no longer depend on the outside file.
- **Empty layers**: PSDs with empty layers save and reopen normally.
- **Separate-mesh check**: a failed check shows its error, and the check stops after 60 seconds.

### Improved

- **Examples ship separately**: example materials, projects and outputs moved to their own repository, downloaded when needed.

## v3.4.4 · 2026-10-13

### Fixed

- **Saving and closing**: projects save and close again after long editing sessions.
- **Undo and redo**: a field being typed in is committed before the history moves. {tutorial=PROJECT_HISTORY}
- **Import and deformers**: 16-bit PSDs, relative layer masks and TIFF orientation read correctly.
- **Export and motions**: a failed export restores the previous files; GIF frame timing and Bézier motion trimming are fixed. {tutorial=LIVE2D_BRIDGE}
