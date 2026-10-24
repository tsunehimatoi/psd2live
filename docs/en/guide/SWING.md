# Swing generation

[Documentation](../../README.md) · [中文](../../zh/guide/SWING.md) · [Physics](PHYSICS.md) · [MCP usage and interface](../agent/MCP_AUTHORING.md)

Swing generation corresponds to Cubism Editor 5.1's automatic sway generation: select a warp deformer and it generates swinging keyforms at -1 / 0 / 1. On top of that it also:

- accepts a mesh too, wrapping it automatically in a fitted warp deformer;
- offers **left/right** and **up/down** swings, and both can be on at once;
- can keep the tip level and the bottom swinging in parallel, so one deformer imitates several strands of hair swinging together;
- generates the matching **physics pendulum** at the same time;
- saves its settings with the project, so it can be reopened and adjusted at any time.

## Opening it

- On the canvas (Object mode), right-click the selected mesh or warp deformer → **Generate Swing...**. With a multi-selection, all are registered together.
- In the Hierarchy, right-click a mesh or warp deformer → **Generate Swing...**. Objects that already have a swing show **Edit Swing...** instead.
- Physics panel → **+ New** → **Swing for the selection…**; in the physics group list, groups that come from a swing open from **Edit swing** in the "⋯" menu.

Once open, the current canvas switches to the edit canvas (no longer the preview) and swing editing starts. Editing opens no window over the canvas: shapes are dragged out directly on the canvas, and the settings are in the swing panel floating at the bottom left of the workspace; clicking the title collapses it, and the "↗" in the title bar moves it to a separate window ("↙" docks it back into the workspace).

- Multiple targets share one set of parameters and pendulums.
- The canvas is locked while editing: clicking anywhere other than a handle does not select or drag other objects, and the context menu does not open.
- To add targets, select other meshes in the Hierarchy and click **Register Selection** on the panel.
- Rotation deformers cannot be targets.

## Canvas controls

The canvas draws three outlines of the swing rectangle (its width is the pivot edge, its length runs from the pivot edge's midpoint to the opposite edge's midpoint): the rest shape (dashed), the +1 pose (solid) and the -1 pose (faint), plus the centerlines of both poses. The fixed edge is drawn thicker. All handles sit on the +1 pose of the direction being edited; wherever you drag one, that part of the +1 pose goes:

| Handle | Position | Drag effect |
| --- | --- | --- |
| Dot | Tip | Dragging sideways sets the amount, past the other side flips it; dragging along the axis sets rise / droop. For stretch bounce, only the along-axis direction acts |
| Diamond | Centerline midpoint | Sets softness: the less the middle follows the tip, the stiffer the root |
| Small squares | Tip's two corners | Set the tip's widening / narrowing; when bending, also set how parallel the bottom is (how level the tip edge stays) |
| Crossed dot | Pivot edge midpoint | Moves the whole swing rectangle, the pivot edge's midpoint following the pointer |
| Hollow circle | Past the tip on the rest centerline's extension | Turns the whole swing rectangle about the pivot midpoint (tilt), using only the pointer's direction; snaps back to 0° near 0° |
| Hollow squares | Midpoints of the other three edges | Click to make that edge the pivot |

All changes show live on the model. **Preview Sway** on the panel swings the parameters back and forth between -1 and 1 (with several segments they are staggered, and up/down is staggered from left/right). **Generate** / **Apply** or Enter writes one history entry; **Cancel** or Esc leaves no change.

## The two swings and the pivot

**Motion** is the direction the tip moves on screen; **Pivot** is the edge that stays fixed (by grid rows and columns, row 0 being the top edge). Their combination decides the deformation:

| Direction | Pivot | Deformation | Typical use |
| --- | --- | --- | --- |
| Left / Right | Top / Bottom | Arc bend | Hair strands, ribbons, tails |
| Up / Down | Left / Right | Arc bend | Ornaments sticking out sideways, side ribbons |
| Up / Down | Top / Bottom | Stretch bounce | Earrings, fluffy hair tips, chest |
| Left / Right | Left / Right | Stretch bounce | Rarely used |

- **Arc bend**: bending along the centerline keeps the length, so the tip rises naturally as it swings out instead of stretching as a plain shift would.
- **Stretch bounce**: the tip stretches or shortens along the pivot direction. It narrows when stretched and widens when compressed, keeping a sense of volume.
- **Automatic pivot**:
  - long thin objects take the top edge;
  - horizontal objects take the side closer to the body's centerline.

### Turning and moving the swing rectangle

By default the swing rectangle is aligned with the deformer. When the art hangs at a slant (side hair, a slanted ornament) while the deformer is still an upright rectangle, drag the hollow circle beyond the tip to turn the whole swing rectangle about the pivot edge's midpoint, within ±75°:

- Positive values turn the tip toward the end of the pivot edge (the right end for a top / bottom pivot, the bottom end for a left / right pivot); a mirrored parent space mirrors it too.
- The rectangle keeps its size and only changes direction; the bend is perpendicular to the turned axis, and arc length keeping, rise, stretch and parallel are all computed in the turned rectangle; the canvas outlines, fixed edge, pivot and tip handles turn with it.
- What stays fixed is the rectangle's pivot edge: everything behind it stays put, and where an end of an original grid edge reaches in front of the pivot edge, it moves slightly with the bend near the root.
- Dragging the crossed dot at the pivot edge's midpoint moves the whole rectangle, for example moving the pivot down to where the hair root actually is so that the part above stays put; turning happens about the moved midpoint.
- The offset is saved relative to the rectangle itself: along the axis by its length, along the pivot edge by its width (±1 each), so it lands on the same spot of the art in every keyform of the target deformer, however those are scaled or moved.
- Tilt and offset belong to the whole swing and are shared by both directions; the panel shows the current values, and each **Reset** restores its default. Clicking another edge to change the pivot resets both. Swings saved earlier open at 0.

### Combining left/right and up/down

Both options of **Motion** on the panel can be checked at once, and both act on the same deformer.

- Each direction has its own parameters, shape settings and pendulum.
- Under one pivot, one is necessarily a bend and the other a stretch: generation applies the stretch first and then the bend, so the combined pose is exact, not a simple sum of the two.
- With both directions on, the panel gains an **Editing** row that chooses which direction the canvas handles control.

## Parallel bottom (several strands of hair)

When hair is not split, the left side hair, bangs and right side hair are all in one deformer. A normal bend turns each cross-section with the centerline, and the whole piece turns like a board: the tip edge tilts, one side rising and the other sinking, which looks odd.

The parallel setting controls how much the cross-sections turn:

- **0**: cross-sections turn with the bend, and the whole piece swings like a board; suited to single strands and ornaments.
- **1**: cross-sections stay level and the tip edge does not tilt. Each column hangs from its own spot on the fixed edge and moves side by side with its neighbors, looking like several strands of hair swinging together.
- The centerline's arc is unaffected, so amount, rise and softness mean the same.
- Short strands close to the fixed edge, such as bangs, already sit where the bend is small and naturally swing less; no splitting and no extra deformer are needed.

The hair and cloth presets lean toward parallel by default (hair left/right 0.7); the accessory preset uses 0. Drag the small squares at the tip's two corners on the canvas to adjust it.

## Settings

The panel holds only the options with no place on the canvas (Motion, Editing, Estimate, segments, automatic pivot, physics); the remaining items are shown read-only for the current direction.

| Setting | Effect |
| --- | --- |
| Swing amount | How far the tip moves at ±1, relative to the pivot-to-tip length (0.25 = a quarter of the length) |
| Rise / droop | Extra lift (positive) or droop (negative) of the tip when bending |
| Softness | 0: the whole length bends evenly; 1: the root stays straight and mainly the tip moves |
| Widen / narrow | At ±1 the part near the tip widens or narrows |
| Parallel | 0: the whole piece turns like a board; 1: the tip edge stays level and the columns swing side by side |
| Tilt | The angle the swing rectangle is turned about the pivot midpoint (±75°), for slanted art |
| Offset | The shift of the swing rectangle's pivot edge midpoint along the axis / along the pivot edge, by length / width |
| Flip horizontally | Swaps the +1 and -1 directions |
| Segments | 1–3 segments per direction, one parameter per segment. Segment k bends only the part below it; in physics they share one multi-segment pendulum, each lower segment a beat behind the one above, giving a whip-like follow-through |
| Estimate | Hair / Accessory / Cloth, giving a set of initial shapes and pendulum values; the pendulum length is estimated from the target's pixel length |

Missing parameters are created automatically (range -1..1), named `ParamSwing<id>`, with `_X` (left/right) / `_Y` (up/down) added when both directions are on, and `_1`, `_2`… with several segments. Changing the direction or segment count renames the parameters by this rule. When an existing parameter is chosen, the keys go on its minimum, default and maximum.

Swings saved earlier have no parallel setting; they open at 0 and keep their former look.

## Physics

With **Generate a pendulum for this swing** checked, the exported physics3.json and cmo3 gain pendulum physics groups:

- One pendulum per direction. With one direction the group is named `PhysicsSwing_<id>`; with two, `PhysicsSwing_<id>_X` and `PhysicsSwing_<id>_Y`.
- **Left/right**: the same inputs as front and back hair, head and body Angle X (X type) and Angle Z (angle type).
- **Up/down**: head and body Angle Y as input, plus Angle Z with a small weight.
  - Cubism's pendulum rods are rigid, and a pendulum hanging vertically does not respond to vertical translation.
  - So here Angle Y is used as a sideways (X type) excitation input, and the pendulum's angle drives the vertical shape.
- **Segments**: each segment is one pendulum in the physics group, and segment k's parameter is driven by the output of pendulum k.

The pendulums are also listed in the [Physics panel](PHYSICS.md), where they can be edited directly (inputs, outputs, each pendulum's dynamics and so on). After a change in the Physics panel, the Physics panel's version wins and the pendulum values in the swing panel stop applying, which the panel points out; **Reset to generated** in the Physics panel returns to the pendulum generated from the swing. A custom group created for the same output parameter replaces the matching pendulum. Deleting the swing or changing its direction also clears the edits to the old pendulums.

## Generator semantics and baking

A swing is a "generator": every time the model is rebuilt, it is recomputed from the target deformer's current shape and the swing axes are appended after it. So:

- The target's existing key axes (for example Angle X) are all kept. Each existing keyform expands into each of the swing's forms.
- The swing axes belong to the swing; keyforms edited by hand on them are overwritten on rebuild.
- To fine-tune by hand, click **Apply as Keyforms** on the panel:
  - the current forms are written as ordinary keyforms, freely editable afterward;
  - the pendulum is kept, and only the physics part of the swing remains.
- **Delete** removes the swing together with its generated axes and automatically created parameters.

## Agent

Over MCP, `swing_put` creates or replaces a swing and `swing_delete` deletes one; the arguments go in `{"request": {...}}`, with `project_id`, `state` and `request_id`:

- `swing_put` writes by `id`, and `targets` are meshes or warp deformers. For a single direction, write `kind` (`lateral` / `vertical`), `segments`, `parameters` and the shape fields (`magnitude`, `lift`, `softness`, `zoom`, `parallel`, `flip`) directly at the top level.
- To combine both directions use `motions: [{kind: "lateral", ...}, {kind: "vertical", ...}]`, each item with its own fields, parameters and `physics`.
- The top-level `fulcrum` picks the pivot edge, `tilt` is the swing rectangle's turn about the pivot midpoint (degrees, ±75), and `offset_along` / `offset_across` are the rectangle's offset (by length / width, ±1), shared by both directions. `preset` (`hair` / `accessory` / `cloth`) corresponds to the panel's Estimate.
- With `physics` omitted, the pendulum is estimated from the target's size; `physics_enabled=false` generates no pendulum.
- `swing_delete` with `bake=true` is the same as **Apply as Keyforms**.

Swing editing on the canvas corresponds to a preview session, which stays out of the document and history until committed: `swing_preview` starts with `mode=begin` and `targets`, then `update`, `kinds`, `segments`, `preset`, `physics`, `select`, `handle` (the same as the canvas handles), `play` and `cancel` modify it; `swing_preview_get` reads the draft, handle positions and playback pose, `swing_preview_render` renders the current pose, and `swing_preview_commit` commits once. `swing_preview` and `swing_preview_commit` return background job handles; use `job_wait` for the result.

`workspace_inspect` with `scope=swings` lists all current swings.
