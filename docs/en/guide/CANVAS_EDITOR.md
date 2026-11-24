<!-- lang: zh en ko ja -->
> [中文](../../zh/guide/CANVAS_EDITOR.md) · **English** · [한국어](../../ko/guide/CANVAS_EDITOR.md) · [日本語](../../ja/guide/CANVAS_EDITOR.md)

# Canvas editing quick reference

[Documentation](../README.md) · [Quick reference](USER_GUIDE.md) · [Depth split illustrated tutorial](DEPTH_SPLIT.md) · [Deform paths](DEFORM_PATHS.md)

This page matches lessons 3 and 6–12 of the in-app beginner path. It collects the editing rules on the canvas by topic. Button placement and the current shortcuts follow the **Tutorial** on the Help page.

## Choose a mode, then a tool

The mode menu at the top left of the canvas (a mode dropdown like Blender’s) lists every mode. While it is open, press 1–7 to choose directly, move with the arrow keys, and confirm with Enter. Preview is in this menu too, and it does the same job as the old Preview item in the title bar. Choosing any other mode on the preview canvas returns to editing.

| Mode | What it changes | Common tools |
| --- | --- | --- |
| Object | Object selection | Click, box select, lasso; stays in sync with the hierarchy; interior shape handles; Create on the toolbar |
| Deform | The base shape, or the keyform of the current parameter | Vertex / edge / face selection, transform, push and pull, smooth, inflate, path control points |
| Edit | Mesh topology and path bindings | Vertex / edge / face selection, mesh points (points / line), connect, merge, subdivide, delete edges, re-triangulate, delete, Glue (available when two meshes are selected) |
| Simulate | Vertex weight groups the simulation reads (this application only) | Weight brush and weight gradient; switch the group to paint by kind underneath (pin, stiffness, and so on). The mode bar can create cloth / hair and switch the reference simulation (not exported) |
| Skeleton | The skeleton | Pose and edit bones. Switching to pose, or leaving the mode, saves the bone edit |
| Paint | Layer pixels | Brush, pencil, eraser, blur, fill, gradient, eyedropper, shape, selection |
| Preview | Nothing | Playback, mouse follow, physics, frame rate |

Simulate mode, like Edit mode, acts on every selected mesh at once, and always shows the current weight group in Blender’s weight colors (blue 0 → green → yellow → red 1). The weight-brush ring is colored by mode (add white, subtract red, set in the accent color, smooth cyan). While hovering, the ring marks the vertices a press would affect, and the number beside the pointer is the weight of the nearest vertex. Alt + right-drag adjusts radius / hardness and previews the falloff. The weight gradient drags from full value to the fade-out position. The simulation itself runs only inside the editor. Exporting it requires a bake in the Simulation panel; see [Simulation and baking](SIMULATION.md).

Settings for the current tool are split across several places, each with its own job:

| Place | Contents |
| --- | --- |
| Left toolbar | Tools for the current mode, one tool per row, grouped by category with separators. In Object mode the last group creates Warp, Rotation, and deform paths. Variants of the selected tool (brush shape, paint shape, selection method, Glue and bone sub-tools, weight-group kind) expand under the tool. `1`–`5` selects them too |
| Mode bar (top of the canvas) | After the mode menu, the items that decide how the canvas operates: vertex / edge / face for selection tools in Deform / Edit mode (`1`–`3`), and confirm and cancel for the current step (placement, cuts, paths, Glue, and so on) |
| Tool options bar (bottom left of the canvas) | Numbers for the current tool: radius, strength, hardness, opacity, mode, and so on. Drag a number left or right to adjust it; click to pop a slider upward. In Paint mode the foreground / background colors come first |
| Canvas context menu | The same set of settings as the mode bar and the options bar (one item per row), plus that tool’s actions, each with an icon. Open and hover animation matches the mode menu. In Object mode, creating a Warp, a Rotation, a deform path, bones, and a swing are gathered in the Create submenu. In Object, Edit, and Paint mode, actions on the layer as a whole also have submenus: Mesh holds rebuild the mesh from the current pixels, check this layer’s separate meshes, and depth split when it is available. A layer linked to a material also has Material, which can open the material in the material window or unlink it. When the rest of the menu is 10 rows or fewer, everything is shown directly. Past that, the largest group (such as topology or falloff) collapses to one row and expands on the right while hovered. Context menus of the panels (hierarchy, materials, atlas, motions, physics, and so on) use the same look and open animation as the canvas menu: they expand from the press, and flip to the other side near a window edge |

The mode bar, the options bar, and the context menu show the same definition, so their scope matches. Brush shortcuts such as `[` `]` adjust the same value the current tool shows in the options bar. The Tool details panel on the right keeps only the advanced settings that need more room: precise numeric transform, Warp / Rotation creation defaults, the path list, bone binding and weights, and the paint palette. Skin-weight display is toggled in the display bar at the bottom right.

By default the wheel zooms, the middle button or Space + left-drag pans, Shift adds to the selection, and Alt removes from it.

Choosing Desktop for the canvas background makes the main window transparent. That choice is kept only after a normal quit. If a transparent-window session crashes or is force-quit, the next launch restores the previous solid or checkerboard background (checkerboard when no setting can be restored). Color settings stay. The recovery note is written to the session log. This avoids a transparent-window failure that would make the app unable to start again and again. It does not mean an underlying graphics-driver or desktop-compositor problem is fixed.

Screenshots of the pin weight brush, and the bake flow, are in the [simulation tutorial](SIMULATION_TUTORIAL.md).

## Points and lines on the canvas

Editable points and lines look the same in every mode. Each color has a fixed meaning:

| Look | Meaning |
| --- | --- |
| Light thin lines and small points | The mesh or Warp mesh being edited. When several meshes are edited at once, each uses its own color |
| Accent color, enlarged, with a white rim | Selected vertices, edges, and faces |
| White outer ring | The hovered point, the one a click would select |
| Red circles | Points a brush press would push. A larger, more solid circle means a stronger effect. They show while hovering and stay while dragging |
| Accent-colored circles | Points a selection brush would select |
| Weight colors (blue → red) | Simulation and bone weights |

The point shape says what it is: mesh vertices are circles, Warp control points are squares, and Glue points are diamonds. Only points that can be clicked directly are drawn. The mesh toggle in the display bar at the bottom right draws only each part’s mesh lines (in that part’s own color), and no longer draws points. The mesh or Warp being edited is drawn by the canvas itself, so the display bar does not draw a second copy.

## Deformation and keyforms

An object with no bound parameter edits its base shape. A bound object edits the current parameter combination. When the target is a mesh deformer (Warp), the mode bar can switch Vertices (edit mesh points) and Bezier. Under Bezier only the cyan anchors and amber handles can be dragged; the detailed mesh is dimmed and cannot be edited. Other objects have no difference between the two, so the switch is hidden. Set the target values in the parameter panel first, then move control points. Check the object’s own axes and the motion it inherits from its parent, so a parent pose is not treated as a direct binding of the current object.

Mirror keyform in the Deform mode canvas context menu mirrors the current key’s keyform left to right across a vertical line. The result is written as an ordinary keyform and can be undone:

| Action | Effect |
| --- | --- |
| Mirror to the opposite key | Mirrors the current key onto the opposite key (for example AngleX 30 → −30), then switches to that key to show the result. Shortcut `Shift+M` |
| Mirror from the opposite key | The reverse: the opposite key’s shape overwrites the current key |
| Mirror to the opposite object | Writes to the matching object on the other side (left eye → right eye). The key is mirrored with it: a parameter with L/R is swapped to the other side (EyeLOpen → EyeROpen), and left/right parameters are negated |
| Flip in place | Flips the current key around the object’s own center |
| Mirror every key to the other side / to the opposite object | Handles every key at once. On the same object, only the keys on the side of the current pose are copied |

The left/right parameters that are negated are those whose range is symmetric about the default value and that are the horizontal axis of a 2D link, or whose ID ends in X or Z (AngleX, AngleZ, BodyAngleX, EyeBallX, and so on). The opposite object is paired first by layer semantics, then by L/R, Left/Right, or 左/右 in the ID or name. Mirroring handles only the object’s own deformation, in the coordinate system of the parent at the default pose. A parent’s keyforms have to be mirrored on their own. With several layers selected, each mesh is mirrored separately, and the edits are committed as one history step.

Keyform in the same context menu handles the shape of the current key. The result is likewise written as an ordinary keyform and can be undone. The parameters must sit exactly on one of the object’s keys:

| Action | Effect |
| --- | --- |
| Copy keyform | Stores the shape the current object shows under the current parameters. Shortcut `Ctrl+Alt+C` |
| Paste keyform | Writes the shape frozen at copy time onto the current key as a displacement (a mesh relative to its rest shape; a Warp and a rotation deformer relative to the default-key shape). It can be written back to the original object, or to an object of the same kind, with the same triangle count (rows and columns for a Warp) and the same parent. `Ctrl+Alt+V` |
| Reset to the default-key shape | Sets the current key back to the shape at each parameter’s default value. `Ctrl+Alt+X` |
| Reset to the mesh rest shape | Meshes only: returns to the undeformed mesh |
| Interpolate from the neighbouring keys of (parameter) | Rebuilds the current key by interpolating the shapes of the neighbouring keys on either side of that parameter, by position. When several parameters can be interpolated, each is listed. The shortcut `Ctrl+Alt+I` takes the first |
| Paste with options… | A dialog chooses what to paste (shape, opacity, draw order, multiply color, screen color), the mix (100% is a paste; a smaller value mixes in proportion), a left/right or up/down flip (a Warp reverses columns / rows; a mesh needs a symmetric rest shape, and vertices are matched by mirrored position), and confirms row by row which object receives each copied shape |
| Restore every key to the default-key shape | Every key of the object returns to the default-key shape, to start over |
| Multi-key editing… | Selects a range by parameter key values and changes opacity, draw order, and color (overwrite, add, multiply, or screen) of several objects and several keyforms at once |

Each selected mesh stores its own shape at copy time. Paste writes them back onto themselves, or assigns them in order to the next selected object that can receive one. Every shape is written as it was at copy time. When points are selected, paste, reset, and interpolate change only the selected points, and the menu row shows the count. A Warp at the Bezier level, and a rotation deformer, are always handled as a whole. Changing the shape of a keyform a generator owns is recorded as a generated-result override. When the parameters are off the object’s keys, or the object binds no parameter, the menu states why.

**Soft selection**: with the select or transform tool, turn on Soft selection in the tool options bar (`O`). Dragging or transforming selected points makes surrounding points follow, falling off with distance to the nearest selected point. Falloff radius is in canvas units and can be adjusted with `[` / `]`. The falloff curve is shared with the deform brushes. Affected points are marked with red circles; a larger circle follows more. What is committed is still one ordinary geometry edit.

**Temporary deform**: `Shift+T`, or Temporary deform (lattice) in the context menu, lays a one-shot lattice over the selected points (or over the whole mesh or Warp when nothing is selected). Points inside follow the lattice points by bilinear interpolation while they are dragged. Each release is one history step. If the parameters are off a key, the press snaps onto a key first. If the shape has already been changed by another operation, the press relaids the lattice from the current shape. Neither of those presses starts a drag; press again to drag. Lattice on the toolbar switches 1×1–4×4. Enter, Esc, or Done ends it. The lattice itself is not saved.

## Multiple canvases, parameters, and animation

Every canvas in the same workspace shares the parameter pose, the parameter locks, and playback. Switching between edit and preview, changing focus, or hiding a canvas does not swap in another pose. Selection, tool, camera, and display settings are still saved per canvas. Different workspaces keep their own poses.

Dragging a parameter on the edit canvas, or posing with bones, pauses animation and shows the edit values directly. Resetting parameters and snapping to keys does not lock automatically. Dragging the timeline in the animation editor sends the sampled pose to the workspace, so the edit canvas can view it and keep adjusting. Edit values do not use a preview frame left over from earlier playback. While posing, mouse follow and paused physics do not overwrite it. Turning playback back on, or toggling mouse follow, returns to the live preview. Adjusting parameters on the preview canvas still shows how paused physics responds.

The small ruler beside an animation track name shows the parameter keys. The curve view shows the same keys as guides with values. Deleting a parameter removes the matching animation track. Shrinking a parameter’s range limits the animation key values and Bezier control points to match.

Playback, the timeline, and baking do not create, show, or switch a canvas on their own. When a canvas is needed, open it from the Window menu or from a panel’s show-canvas button.

## Deform brushes

Deform brushes change geometry. They do not change the texture. With no control-point selection they act on every point of the current object. With a selection they stay inside the selected points. Radius is in canvas units. By default Alt + right-drag adjusts radius or hardness, and Esc cancels the current gesture.

The tip shape is chosen under the brush on the left toolbar (the weight brush is the same). The other settings are in the tool options bar and the canvas context menu:

| Setting | Effect |
| --- | --- |
| Shape | Circle, line, or rectangle |
| Radius / hardness / strength | Reach, edge softness, and how hard each push is |
| Falloff | The weight curve from center to edge: smooth, sphere, root, inverse square, sharp, linear, constant, random |
| Connected only | Affects only points connected, through the mesh, to the mesh under the pointer. A circle brush then measures distance along mesh edges |

![Deform brush settings, and the reach with Connected only on](../../imgs/mesh-brush.webp)

*Left: brush settings. Right: with Connected only on, the circle covers the neighbouring lock, but the affected vertices (red) stay on the strand under the pointer. Those vertices are marked with red circles while hovering; a larger circle means a stronger effect. Use this to brush neighbouring strands, or the upper and lower lips, which sit close together.*

## Mesh topology

| Action | Result |
| --- | --- |
| Subdivide edge / subdivide region | Adds a point on the hit edge and rebuilds the triangles. Neighbouring faces update the seam together |
| Connect / merge | Connects the selected points, or merges the selected vertices into one |
| Duplicate | Duplicates the selected vertices and the faces they fully cover. The new copy becomes the selection |
| Delete | Deletes the selection and tries to rebuild the gap. The action is refused when the result would not be legal topology |
| Delete edges | Deletes the selected edges (Del in edge mode, or Delete edges in the topology menu). An interior edge flips to the other diagonal of the quad formed by the two triangles. The action is refused when that quad is non-convex in the rest shape or in any keyform or blend shape, because posing would then flip a face. An outline edge deletes the triangle it belongs to, and vertices that are no longer used are deleted with it. The action is refused when a vertex would be joined at only one point, or when the last triangle would be deleted |
| Re-triangulate | Topology menu: around the selected vertices (or the whole mesh when nothing is selected), flips skinny triangles into a more even connection. Vertices, the outline, and the texture stay put. When the triangulation is already as even as it can be, a notice says nothing needs doing |
| Mesh points | When automatic point placement needs a correction, adjust point by point. Points adds, moves, and deletes points. Line adds connected edges along a line. See below |

### Mesh points

The first tool in the topology group of the Edit mode toolbar. It works on ordinary meshes and on meshes with an interior shape. It has two modes, switched on the options bar or under the toolbar, or by each mode’s own shortcut:

| Mode | Shortcut | Use | Log command |
| --- | --- | --- | --- |
| Points | `Alt+M` | Add, move, and delete points to adjust mesh density. Each click or stroke commits at once. Connections around a new point are arranged by the mesh. An edge between points is **not guaranteed** | `add_points` (and `connect` / `delete` / `delete_edges`) |
| Line | `K` | Click point by point to draw a polyline. Enter commits once. Every point is joined to the previous one by an edge, so the mesh can **fold** along that line (eyelids, a mouth line, creases) | `knife` |

Both modes place points by the same rule. The pointer snaps when it is near a vertex or an edge (one shared snap radius; Shift temporarily turns snap off in Line mode). The hover mark shows where a press would land. Points can land outside the mesh.

#### Points

| Action | Result |
| --- | --- |
| Click empty space | Adds a vertex there. Inside a triangle, the triangle becomes three. On an edge, that edge is split. Outside the mesh, a triangle grows from the nearest visible outline edge |
| Drag on empty space | Adds a vertex along the stroke every Point spacing (canvas pixels). The whole stroke commits once |
| Drag a vertex | Moves the vertex, the same way as the select tool. The texture stays put. Glued points move together |
| Shift + click a vertex | Connects a line from the one currently selected vertex to it, then moves the selection onto it. Repeated Shift + clicks build a polyline |
| Alt + click | Deletes a vertex (and fills the gap) or deletes an edge |

#### Lines

Click point by point. Each click drops an anchor: on a vertex it uses that vertex, on an edge it splits the edge, and inside a triangle or outside the mesh it adds a new vertex. Adjacent anchors are joined by an edge, and edges the line crosses are split. Enter (or Finish line on the mode bar) commits the whole line once. Delete steps back one point. Esc cancels. When the line cannot connect (it would overlap the mesh), the whole line is refused and the draft is kept, so the last point can be stepped back and placed again.

#### Shared notes

A new vertex’s position, UV, keyforms, blend shapes, and vertex weights all come from the surrounding original vertices with the same weights, so adding a point does not move the picture. After a point is added, surrounding edges are flipped without changing the texture, which reduces skinny triangles. With Mirror across centre on, adding points, deleting, and deleting edges also happen at the symmetric position across the vertical midline of the mesh’s current bounding box (when that position has no point or edge, only this side is handled). The selection group of the context menu also has Select outline vertices and Select interior vertices, for adjusting only the boundary or only the interior.

On a mesh with an interior shape, adding or deleting points, deleting edges, re-triangulating, or moving rest-shape vertices rebakes the shape from the new mesh in the same step. When the new mesh fails the shape’s area check, the edit still commits, and the interior shape panel says the mesh has changed and needs to be generated again.

A topology update also handles UVs, keyforms, blend shapes, and Glue vertex references, and rebinds paths. Existing poses should still be checked, especially the boundary after outline points are deleted.

## Interior shape handles

Interior handles in Object mode (Shift+I, or Edit on canvas on the interior shape panel toolbar; pressing it in another mode switches to Object mode) adjusts the interior shape directly on the selected mesh. Object mode does not draw mesh vertices, so the handles are not crowded against vertices. When the mesh has no effect yet, or the effect is off, the options bar says so and can turn it on in one step. Clicking another object selects it instead, and the handles move with the selection.

| Handle | Effect |
| --- | --- |
| Center cross | Upper arm bulge, left arm pressure, right arm horizontal amount, lower arm vertical amount. A longer arm is a larger value. The dashed box edge is 2 |
| Arch | The solid line is the virtual arch at rest. The dashed line is the arch tilted by pressure at full input (a guide) |
| Arrow | Shown when easing and propagation are on. Drag either end to change the start / end. Drag the midpoint to move the whole arrow |
| Guide | Click on the mesh or on empty canvas to start a fold line. Click the last point, or press Enter, to finish. Drag a point to move it. Drag a segment to move the whole line. Ctrl+click adds a point. Alt+click deletes a point. Delete deletes the selected points or the whole line. A selected fold line can drag its radius handle |

Handles and lines highlight while hovered, and the status bar shows the name and the value. Each drag records one history step on release. Esc cancels the drag. Panel values and handles stay in sync. With audition on, the shape change is visible while dragging.

## Feature points

The **Feature points** tool in Object mode (default `Shift+Y`) shows handles fitted to the features: the inner and outer corners and the upper and lower lid midpoints of each eye, both ends and the arch of each brow, and the two corners and the upper and lower lip midpoints of the mouth. They appear only when those parts use the fitted base algorithm. After a handle is dragged and released, the features are fitted again at the new positions, and every pose updates with them: open and close, smiling eyes, emotion, mouth shapes, and the rest. The lid edge or lip edge follows most at the midpoint and follows the eye corner or mouth corner at the ends. The change is stored in the rig recipe. One drag is one history step, and it is kept after regeneration. A handle that has been moved is drawn as a square. `Alt` + click returns it to the drawn position. To nudge a whole feature without changing the fit, drag its driver mesh (the shape deformer of the eye, brow, or mouth): in Edit mode, or in Deform mode while holding `Ctrl`, drag the grid points. The change is added as a displacement to every keyform and blend shape of that deformer, so every pose moves together, and it is kept after regeneration. The options bar shows the base-algorithm values of the part last operated on (for an eye, lower-lid push and eyeball roundness; for the mouth, the front opening and the mouth-shape amount). One drag is one history step. **Reset this feature** and **Reset all features** withdraw the move of that whole feature, or of every feature, at once. MCP changes the same data with `changes.rig.moves` on `settings_update`.

## Creating objects and placing materials

1. Select the target, then in the last group of the Object mode toolbar choose Warp, Rotation, or Deform path, or press `C` / `R` / `P`. The same choices are on the hierarchy context menu as add a parent / child Warp or Rotation, and in Create on the Object mode canvas context menu.
2. Adjust the placement preview on the canvas. Check the affected objects, the name, the parent, and the division settings.
3. Click Confirm on the mode bar, or press Enter, to write it into the project. Click Cancel, or press Esc, to cancel.

Dropping a PNG, WebP, TIFF, or BMP onto the hierarchy, or choosing Import layer… from the tree’s context menu, also starts a placement session. Dropping onto a drawable parents the new layer under that drawable’s parent deformer. After placement, check occlusion and multi-parameter poses.

## Transform box

The Transform tool, the placement box of a Warp / layer, and a selected tile on the atlas page use the same transform box, with the same look and the same actions. With nothing selected, the transform tool behaves like the select tool: click or box-select, and the box appears once something is selected.

| Action | Effect |
| --- | --- |
| Drag inside the box | Move |
| Drag a corner / edge midpoint | Scale, with the opposite side held still. `Shift` on a corner keeps the proportions. `Alt` scales about the anchor |
| Drag the round handle outside the top edge, or just outside a corner (an arrowed arc lights up outside the corner while hovered) | Rotate about the anchor. `Shift` steps by 15° |
| Drag the anchor (a stroked circle with a cross) | Moves the anchor. It snaps near the center, a corner, or an edge midpoint. Dragging it back to where it started restores the default |

The anchor starts at the selection center (the centroid of the points). Its position inside the box, relative to the selection, stays fixed, so it keeps following the selection through move, scale, and rotate. Rotation and scale in Precise transform use it as the center too. Changing the selection, or leaving the tool with Esc, returns the anchor to the default position. While dragging, the pointer shows the rotation angle, the scale of the two sides, or the move in canvas pixels, and the other handles fade. When the box is under 40 pixels wide or tall on screen, the middle always moves and the anchor cannot be dragged (zoom in). A Warp or layer placement box is created as a rectangle: it can move and scale, and it cannot rotate. An atlas tile’s corners scale density in proportion, and it has no edge midpoints.

## Rotation deformers

Drag the origin to translate. Drag the end to rotate. By default, hold `Shift` to snap the angle. `Alt` + drag the end to adjust the scale.

## Editing several meshes

Edit mode acts on every selected drawable at once:

- Vertex click, box select, brush select, the transform box, and deform brushes all work across meshes.
- Clicking a vertex of a mesh makes that mesh the **primary mesh**. Line in mesh points, edge / face mode, and paths act only on the primary mesh.
- Vertex actions such as delete commit once per mesh that has a selection, into the same history entry.

## Glue

With two meshes selected, choose the Glue tool in Edit mode. Three sub-tools are listed under it on the toolbar: glue brush, weight brush, and remerge (`1`–`3`). A Glue point is one point shared by the two meshes, drawn as a diamond on the canvas. While it is selected or dragged, the vertices on both sides always move together, so editing does not slip out of the glue.

As in Cubism, gluing is done on the meshes’ rest shape (every parameter at its default). Glued points coincide at rest and pull nothing. When the parameters are away from their defaults, the glue brush, remerge, Create glue, and gluing the selected vertices ease the parameters back to the defaults before gluing. Adjusting weights and ungluing leave the pose unchanged.

| Action | Effect |
| --- | --- |
| Glue brush | Changes the mesh and leaves the picture unchanged. Where the meshes overlap (or within the weld tolerance), the other mesh gains or slides a vertex, and the UV moves with it. After the insert, edges are flipped without changing the texture, which avoids skinny triangles |
| `Alt` + drag | Unglue |
| Weight brush | Shows the two sides’ weights on the texture, blue (A) / orange (B) |
| Remerge | Removes the glue already on the brushed vertices, then glues again by the weld tolerance |
| `Enter`, or Create glue on the mode bar | Glues the whole length of both outlines |
| `Ctrl+G` (the shortcut that exports the model; it does not open export here) | Glues the vertices selected on either mesh |
| Remerge the whole seam by distance in the context menu | Removes every glue between the two meshes, then glues along the outlines again |

## Applying and discarding paint

Select a layer before entering Paint. Strokes are kept in a separate paint session, which supports its own undo and redo. After the first stroke, a session bar slides out at the top center of the canvas (the same bar as an atlas adjustment session). It shows the layer being painted and the stroke count, and offers undo, redo, discard, and **Apply**. On a narrow canvas the buttons show icons only, with the name on hover. The Tool details panel has Discard and Confirm apply as well.

The palette works the Photoshop way. At the front of the tool options bar at the bottom left, the foreground / background colors overlap. Swap is at the top right (X). The bottom left restores the default black / white. Clicking a swatch opens the picker: a saturation / value field on the left, a vertical hue bar on the right, a New / Current comparison swatch (click Current to restore), H S B and R G B values and a hex code, and below that the swatches and recently used colors. The Tool details panel embeds the foreground color’s field and hue bar directly, plus the swatches and recently used colors. Brush, pencil, eraser, and shape sizes are independent of each other. A shape’s stroke width does not change the brush size.

The toolbar has three groups: brush, pencil, eraser, **blur**; paint bucket, **gradient**, eyedropper; shape, **selection**. Each tool’s options are on the options bar at the bottom left. Selection commands are in the Selection group of the canvas context menu:

| Feature | How to use it |
| --- | --- |
| Selection tool (`Alt+L`) | Six variants: rectangle, ellipse, lasso, polygonal lasso, magic wand, and quick selection, switched with `1`–`6`. Drag to box-select. The lasso draws an outline while held. The polygon clicks point by point; click back on the start, or press `Enter`, to close, `Delete` undoes the last point, and `Esc` abandons it. The magic wand selects the color at the click by tolerance; with Contiguous off it selects every pixel of that color in the image. Quick selection is a brush: the painted area, together with connected similar colors (including gradient shadows), is selected, and it stops at an edge where the color changes sharply. Every stroke adds to the selection. Painting while holding `Alt` subtracts. `[` `]` changes the brush size. Tolerance uses Tolerance on the options bar. Colors are compared premultiplied by alpha, so a transparent region counts as one color no matter what color is hidden there. Hold `Shift` to add, `Alt` to subtract, and `Shift+Alt` to intersect, or pin the combine mode on the options bar. A click without a drag clears the selection. The selection is shown as marching ants |
| Selection commands | `Ctrl+A` selects all, `Ctrl+D` deselects, `Ctrl+I` (or `Ctrl+Shift+I`) inverts, `Alt+Backspace` fills the selection with the foreground color (the whole layer when there is no selection), and `Del` clears the selection. The context menu also has Feather selection, Expand selection, and Contract selection (the distance comes from Feather and Expand/contract on the options bar; contract does not shrink inward from the canvas edge) and Select opaque pixels (the layer’s alpha becomes the selection, as in Ctrl+clicking a layer thumbnail in Photoshop) |
| What the selection does | With a selection, every tool (brush, eraser, blur, fill, gradient, shape, clear) changes only the inside, and a soft edge fades in proportion. The selection is not part of stroke history: undoing a stroke leaves the selection as it is. Apply, discard, or switching layers clears the selection |
| Free transform (`Alt+T`; in Paint mode `T` also works) | Choosing the tool puts the pixels inside the selection (or everything on the layer when there is no selection) into a transform box, and the original spot is emptied at once. Drag inside to move. Drag a corner to scale uniformly (`Shift` for non-uniform), an edge handle to scale on one axis, and `Alt` to scale from the center. Drag outside the box to rotate about the center (`Shift` steps by 15°). `Enter` or Apply transform on the options bar commits the stroke. `Esc` or `Ctrl+Z` cancels and puts the pixels back. Switching tools applies. With Smooth off on the options bar, sampling uses the nearest pixel, which suits pixel art. After apply, the whole transform is one step in stroke history, and the selection moves with the pixels |
| Gradient (`Alt+G`) | Drag to set the start and end, linear or radial, from the foreground color to the background color or to transparent, limited by the selection and by lock transparency |
| Blur (`Alt+J`) | Painted areas soften. Strength sets how much. Scrubbing back and forth in one stroke does not blur more and more |
| Stabilizer | Stabilizer on the options bar of the brush, pencil, eraser, and blur, 0–30. A higher value steadies the line and makes it follow more slowly, and the stroke catches up to the release point when the button is let go |
| Symmetry | The same four tools can mirror left/right, up/down, or four ways. The axis is the canvas center, drawn on the canvas as a dashed line |
| Lock transparency (`/`) | Colors only pixels that already exist, and does not change alpha. The eraser then paints with the background color |
| Paint bucket | With Contiguous off, fills every pixel of the same color in the image |
| Line | `Shift` + click draws a straight line from the end of the previous stroke |
| Default colors | `Alt+D` restores black / white (`X` swaps foreground / background) |

Pressure, rotating / flipping the view, smudge, and watercolor mixing are not supported yet. The full comparison with Photoshop / SAI is in the paint system.

Paint is not limited to the layer rectangle. The whole canvas can be painted. Apply crops by the layer’s mesh:

- **The part on the mesh** stays in the layer. The layer’s mesh, keyforms, and bindings stay as they are.
- **The part outside the mesh** is split into a new layer, named the original layer name plus “+”, placed directly above the original layer. The new layer has its own mesh and hangs under the original layer’s same parent and same part, so it moves with it. It also appears in the material library as a material, beside the original layer’s material (at the top level when the original layer has no material). The layer is linked to that material.
- Erasing outside the mesh does nothing: those pixels were never shown, and the layer’s existing soft edge is not split away.
- A layer that has no mesh yet (for example a transparent layer that has just received its first stroke) is not cropped. The mesh is generated from every pixel.
- On a layer linked to a material, the part on the mesh is written into the material, so every layer that uses that material changes together. The part outside the mesh is still split into a new layer.

To make the layer’s own mesh cover the newly painted area, use Rebuild mesh in the mesh panel. Keyforms, paths, weights, and glue migrate onto the new mesh.

After apply, check deformation and masks, then save the project. Implementation details are in the paint system.

## Commit and recovery

An ordinary geometry gesture previews while dragging and enters project history when it ends successfully. Create, mesh-point lines, and paint have their own confirm steps. Treat the current draft, history that has already been committed, and the project written to disk as three different states.

Mode differences and export limits for paths are in [Deform paths](DEFORM_PATHS.md). Project persistence is in [Project format](../spec/PROJECT_FORMAT.md).
