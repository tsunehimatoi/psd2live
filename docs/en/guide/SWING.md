<!-- lang: zh en ko ja -->
> [中文](../../zh/guide/SWING.md) · **English** · [한국어](../../ko/guide/SWING.md) · [日本語](../../ja/guide/SWING.md)

# Swing generation

[Documentation](../README.md) · [Physics](PHYSICS.md) · [MCP usage and API](../agent/MCP_AUTHORING.md)

Swing generation matches Automatic sway generation (「揺れの自動生成」) in Cubism Editor 5.1: select a warp deformer, and it generates swing keyforms at -1 / 0 / 1. On top of that it also does the following:

- A mesh can be selected too. It is wrapped automatically in a fitted warp deformer.
- There are two swings, **left/right** and **up/down**, and both can be on at once.
- The tip can stay level, and the bottom can swing in parallel, so one deformer stands in for several hair strands swinging together.
- It also generates the matching **physics pendulum**.
- The settings are saved with the project and can be opened again and adjusted at any time.

## How to open it

- On the canvas (Object mode), right-click a selected mesh or warp deformer → Generate Swing.... A multi-selection is registered together.
- In the hierarchy, right-click a mesh or warp deformer → Generate Swing.... An object that already has a swing shows Edit Swing....
- Physics panel → + New → Swing for the selection…. A group whose source is swing can be opened from Edit swing in its ⋯ menu.

Once it is open, the current canvas switches to the edit canvas (no longer the preview) and enters swing editing. Editing does not pop up a window over the canvas: the shape is dragged out directly on the canvas, and the settings sit in the swing panel floating at the bottom left of the workspace. Click the title to collapse it. Click ↗ on the title bar to move it to its own window (↙ docks it back into the workspace).

- Several targets share one set of parameters and pendulums.
- The canvas is locked during editing: a click anywhere but a handle does not select or drag another object, and the context menu does not open.
- To add a target, select another mesh in the hierarchy, then click Register Selection on the panel.
- A rotation deformer cannot be a target.

## Controls on the canvas

The canvas draws three outlines of the swing rectangle (the width is the pivot edge, the length runs from the midpoint of the pivot edge to the midpoint of the opposite edge): the rest shape (dashed), the +1 pose (solid), and the -1 pose (faint), plus the center lines of both poses. The fixed edge is drawn thicker. Every handle sits on the +1 pose of the direction currently being edited. Wherever a handle is dragged, that part of the +1 pose goes there:

| Handle | Position | Drag effect |
| --- | --- | --- |
| Circle | Tip | A sideways drag sets the sway. Dragging to the other side flips it. A drag along the axis sets the rise / droop. During a stretch bounce it acts only along the axis |
| Diamond | Midpoint of the center line | Sets softness: the less the middle follows the tip, the stiffer the root |
| Small squares | The two corners of the tip | Set how the tip grows / shrinks. While bending, they also set how parallel the bottom stays (how level the tip edge stays) |
| Crossed circle | Midpoint of the pivot edge | Translates the whole swing rectangle. The midpoint of the pivot edge follows the pointer |
| Hollow circle | The rest center line extended past the tip | Rotates the whole swing rectangle about the pivot midpoint (tilt). Only the pointer direction matters. Near 0° it snaps back to 0° |
| Hollow squares | Midpoints of the other three edges | A click makes that edge the pivot |

Every change shows on the model at once. Preview Sway on the panel swings the parameters back and forth between -1 and 1 (segments are staggered, and up/down is staggered from left/right). Generate / Apply, or Enter, writes one history entry. Cancel, or Esc, leaves no change.

## The two swings and the pivot

Motion is the direction the tip moves on screen. Pivot is the edge that stays fixed (counted on the grid rows and columns; row 0 is the top edge). The combination decides the deformation:

| Direction | Pivot | Deformation | Typical use |
| --- | --- | --- | --- |
| Left / right | Top / bottom | Arc bend | Strands, ribbons, tails |
| Up / down | Left / right | Arc bend | Ornaments that stick out sideways, side ribbons |
| Up / down | Top / bottom | Stretch bounce | Earrings, fluffy tips, the chest |
| Left / right | Left / right | Stretch bounce | Rarely used |

- **Arc bend**: length stays constant while bending along the center line, so the tip lifts naturally as it swings out, instead of stretching the way a plain translation would.
- **Stretch bounce**: the tip lengthens or shortens along the pivot direction. It narrows as it lengthens and widens as it compresses, which keeps a sense of volume.
- **Auto pivot**:
  - A long, thin object takes the top edge.
  - A sideways object takes the side closer to the body’s midline.

### Rotating and moving the swing rectangle

The default swing rectangle lines up with the deformer. When the art hangs on a slant (side hair, a slanted ornament) and the deformer is still an upright rectangle, drag the hollow circle past the tip to rotate the whole swing rectangle about the midpoint of the pivot edge, within ±75°:

- A positive value turns the tip toward the end of the pivot edge (the right end for a top / bottom pivot, the bottom end for a left / right pivot). A mirrored parent space mirrors with it.
- The rectangle keeps its size and only changes direction. Bending is perpendicular to the axis after the turn. Arc length, rise, stretch, and parallel are all computed inside the rotated rectangle. The outlines, the fixed edge, and the pivot and tip handles turn with it.
- What stays fixed is the rectangle’s pivot edge: the part behind it does not move. If one end of a grid point’s original edge reaches in front of the pivot edge, it shifts slightly with the bend near the root.
- Dragging the crossed circle at the midpoint of the pivot edge translates the whole rectangle. For example, move the pivot down to where the hair root actually is, and the part above stays still. Rotation is about the midpoint after that translation.
- The translation is stored as a fraction of the rectangle itself: along the axis in lengths, along the pivot edge in widths (each ±1). So after the target deformer’s keyforms scale or move, it still lands on the same place in the art.
- Tilt and translation belong to the whole swing and are shared by both directions. The panel shows the current values. Each Reset returns that one to the default. Clicking another edge to change the pivot zeros both. A swing saved before this opens at 0.

### Combining left/right and up/down

Both options under Motion on the panel can be checked at once. Both act on the same deformer.

- Each direction has its own parameters, shape settings, and pendulum.
- Under the same pivot, one of them is always a bend and the other a stretch. Generation stretches first and then bends, so the combined pose is exact, rather than a plain sum of the two.
- When both directions are on, the panel adds an Editing row, which chooses which direction the canvas handles follow.

## Level tip (several hair strands)

When the hair is not split, the left hair, the bangs, and the right hair all sit in one deformer. An ordinary bend turns every cross-section with the center line, so the whole piece turns like a board: the tip edge tilts, one side lifts, and the other sinks, which looks wrong.

Level controls how far the cross-section turns:

- **0**: the cross-section turns with the bend, and the whole piece swings like a board. This suits a single strand or an ornament.
- **1**: the cross-section stays level, and the tip edge does not tilt. Each column hangs from its own place on the fixed edge and translates beside its neighbours, which looks like several strands swinging together.
- The arc of the center line is unaffected, so sway, rise, and softness keep their meaning.
- A short bunch close to the fixed edge, such as bangs, already sits where the bend is small, so it swings little on its own. It does not need a split, and it does not need an extra deformer.

Hair and cloth presets lean toward level by default (hair left/right 0.7). The accessory preset is 0. Drag the small squares at the two corners of the tip on the canvas to adjust it.

## Settings

The panel holds only the options that have no place on the canvas (motion, editing direction, estimate, segments, auto pivot, physics). The rest are read-only and show the current direction’s values.

| Setting | Effect |
| --- | --- |
| Sway | How far the tip moves at ±1, as a fraction of the length from pivot to tip (0.25 = a quarter of the length) |
| Rise / droop | Extra lift of the tip while bending (positive) or droop (negative) |
| Softness | 0: the whole length bends evenly. 1: the root stays straight, and mostly the tip moves |
| Grow / shrink | At ±1, the part near the tip grows wider or narrower |
| Level | 0: the whole piece turns like a board. 1: the tip edge stays level, and the columns swing side by side |
| Tilt | The angle the swing rectangle rotates about the pivot midpoint (±75°), for art that hangs on a slant |
| Offset | How far the midpoint of the pivot edge is shifted along the axis / along the pivot edge, in lengths / widths |
| Flip horizontally | Swaps the +1 and -1 directions |
| Segments | 1–3 segments per direction, one parameter each. Segment k bends only the part below it. In physics they share one multi-segment pendulum. The segment below lags the one above by a beat, which makes a whip-like follow |
| Estimate | Hair / accessory / cloth. Gives a starting shape and pendulum. Pendulum length is estimated from the target’s length in pixels |

When a parameter does not exist it is created (range -1..1), named `ParamSwing<id>`. When both directions are on, `_X` (left/right) / `_Y` (up/down) is added, and with several segments `_1`, `_2`, and so on are added after that. Changing the direction or the segment count renames the parameters by this rule. If an existing parameter is chosen, the keys are placed at its minimum, default, and maximum.

A swing saved before Level existed is treated as 0 when opened, so it keeps its old look.

## Physics

With Generate a pendulum for this swing checked, the exported physics3.json and the cmo3 gain pendulum physics groups:

- One pendulum per direction. With only one direction the group name is `PhysicsSwing_<id>`. With both directions they are `PhysicsSwing_<id>_X` and `PhysicsSwing_<id>_Y`.
- **Left / right**: the inputs match front and back hair, head and body angle X (X type) and angle Z (angle type).
- **Up / down**: inputs are head and body angle Y, plus angle Z at a small weight.
  - A Cubism pendulum rod is rigid. A pendulum hanging straight down does not respond to an up/down translation.
  - So angle Y is fed in here as a sideways (X type) excitation, and the pendulum’s angle then drives the up/down form.
- **Segments**: each segment is one pendulum in the physics group. The parameter of segment k is driven by the output whose pendulum number is k.

The pendulums are also listed in the [Physics panel](PHYSICS.md), and can be edited there directly (inputs, outputs, the dynamics of each pendulum, and so on). After an edit in the Physics panel, that panel’s version wins. The pendulum values in the swing panel no longer take effect, and the panel says so. Reset to generated in the Physics panel returns to the pendulum generated from the swing. A custom group created on the same output parameter replaces the matching pendulum. Deleting the swing, or changing its direction, clears the edits of the old pendulum as well.

## Generator semantics and baking

A swing is a generator: every time the model is rebuilt, it is recomputed from the target deformer’s current form, and the swing axes are added after that. So:

- The target’s existing key axes (for example angle X) are all kept. Each existing keyform is expanded into each swing form.
- These swing axes belong to the swing. Keyforms edited by hand on these axes are overwritten on the next rebuild.
- To tweak by hand, click Bake to Keys on the panel:
  - The current forms are written as ordinary keyforms, and can be edited freely afterward.
  - The pendulum is kept. The swing keeps only its physics.
- Delete removes the generated axes and the automatically created parameters as well.

## Agent

MCP uses `swing_put` to create or replace a swing and `swing_delete` to delete one. Parameters go in `{"request": {...}}`, with `project_id`, `state`, and `request_id`:

- `swing_put` writes by `id`. `targets` are meshes or warp deformers. With only one direction, write `kind` (`lateral` / `vertical`), `segments`, `parameters`, and the shape fields (`magnitude`, `lift`, `softness`, `zoom`, `parallel`, `flip`) at the top level.
- To combine both directions, use `motions: [{kind: "lateral", ...}, {kind: "vertical", ...}]`. Each item carries its own fields, parameters, and `physics`.
- Top-level `fulcrum` chooses the pivot edge. `tilt` is the swing rectangle’s rotation about the pivot midpoint (degrees, ±75). `offset_along` / `offset_across` are the rectangle’s translation (in lengths / widths, ±1), shared by both directions. `preset` (`hair` / `accessory` / `cloth`) matches Estimate on the panel.
- When `physics` is omitted, the pendulum is estimated from the target size. `physics_enabled=false` generates no pendulum.
- `bake=true` on `swing_delete` is the same as Bake to Keys.

Swing editing on the canvas is an audition session. It does not enter the document or history until it is committed. `swing_preview` starts with `mode=begin` and `targets`, then changes it with `update`, `kinds`, `segments`, `preset`, `physics`, `select`, `handle` (the same handles as on the canvas), `play`, and `cancel`. `swing_preview_get` reads the draft, the handle positions, and the playback pose. `swing_preview_render` renders the current pose. `swing_preview_commit` commits once. `swing_preview` and `swing_preview_commit` return a background job handle. Take the result with `job_wait`.

`workspace_inspect` with `scope=swings` lists every current swing.
