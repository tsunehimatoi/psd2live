# Physics

[Documentation](../../README.md) · [中文](../../zh/guide/PHYSICS.md) · [Swing generation](SWING.md) · [Simulation and baking](SIMULATION.md) · [MCP usage and interface](../agent/MCP_AUTHORING.md)

Physics makes parameters follow inputs such as the head and body with inertial sway. PSD2Live uses Cubism's pendulum model: a chain of pendulums hanging from a root (the rows numbered 1, 2, 3… in Cubism Editor's pendulum settings); inputs push the root or tilt gravity, the pendulums swing with inertia, and the angles of chosen pendulums are written to output parameters. Export writes it to `physics3.json` and `.cmo3`.

## Where physics groups come from

All of the model's physics groups are listed in the Physics panel, from five sources:

| Source | Description | ID |
|------|------|----|
| Preset | Front hair, back hair and jelly eyes generated from the layers (only when the layers contain those parts) | `PhysicsHairFront`, `PhysicsHairBack`, `PhysicsEyeJelly` |
| Skeleton | Follow-through for tails and wings from the skeleton's second bone onward | `PhysicsSkel_<bone id>` |
| Swing | One pendulum for each direction of each swing, see [Swing generation](SWING.md) | `PhysicsSwing_<id>[_X\|_Y]` |
| Simulation | One fitted pendulum per baked simulation, each segment driving one mode parameter; vertical motion gets another pendulum with a translation input, see [Simulation and baking](SIMULATION.md) | `PhysicsSim_<id>`, `PhysicsSim_<id>_y` |
| Custom | Created in the panel or over MCP | `PhysicsCustom<N>` by default |

Generated groups can be edited directly. Once edited a group becomes **Edited** and your version is saved; later changes to its source (swing, skeleton) do not overwrite it. A simulation's pendulum is merged three ways after a rebake or an output rename, keeping only the fields you changed while the rest follow the new fit (see [Simulation and baking](SIMULATION.md)); **Reset to generated** goes back to the generated version. Every group can be turned off on its own, and turned-off groups are not exported. When a custom group drives an output parameter of a generated group, that generated group shows **Replaced** and is not exported either.

Physics settings in an imported CMO3 keep the IDs the file gives them. Cubism Editor can save several settings under one ID; the second and later ones then become `<id>_2`, `<id>_3`… (skipping IDs the file already uses), so every setting is kept, each import gives the same IDs, and exports use them.

A parameter can be driven by only one active group. An invalid group (a missing parameter, no inputs or outputs, a parameter that is both an input and an output) is marked **Invalid** in the list and is not exported; the editing area shows why.

## Physics panel

The physics master switch and the physics frame rate are in the preview canvas's left toolbar, together with the idle animation and mouse tracking. The physics frame rate can be 30 / 60 / 90 / 120 / **Unlimited** (default 60) and is a project setting: physics is computed at it, and the exported `physics3.json` (`Fps`) and CMO3 record it. With **Unlimited**, physics is computed on every rendered frame, the export declares no `Fps`, and runtimes compute physics on their own frames (CMO3 writes no physics FPS). Preview frames are not limited by it: the preview frame rate cap is in the menu of the status pill at the bottom left of the canvas, **Follow display** by default and unrelated to the physics frame rate; physics interpolates between steps.

The top of the panel shows whether physics is running, the current frame rate and the "n / m active" count, along with the preview's idle animation and mouse tracking toggles.

The idle animation and physics are independent: with the idle animation stopped and physics on, dragging a parameter slider or turning the head with mouse tracking still swings the physics in the preview, and the preview stops rendering frames once things settle; with physics off only the edited pose is shown. While paused, physics is computed by the same engine as the export (see "Preview and export consistency" below) and does not make the paused motion keep playing.

The Parameters panel always matches the preview: while the idle animation plays, the sliders follow the preview frame by frame; when stopped, the angle and eyeball parameters driven by mouse tracking also show live in the Parameters panel, and the Physics panel's pendulums read the same pose.

For dedicated physics tuning, create a **Physics** workspace (the tab bar's + menu): from left to right a preview canvas, the Parameters panel and the Physics panel, about 2 : 1 : 1 in width, with other panels hidden; open them from the **Windows** menu when needed.

Below is the list of physics groups, in computation order with their numbers shown. Each row has a checkbox to enable it and shows its source and state (**Edited** / **Invalid** / **Replaced**). The ↑ / ↓ in the header change the selected group's computation order: Cubism computes groups in order, and later groups can read earlier groups' outputs within the same step, so when one group takes another group's output as input (chained physics), the group being read must come first.

**+ New** can:

- create a **Blank pendulum**: by default it takes the head and body sway as inputs, with one pendulum of length 10 and no outputs yet;
- **Duplicate as new group** the selected group (its output parameters are cleared so they do not conflict with the original);
- **Import physics3.json…**: each group in the file becomes a custom group, placed after the existing groups in file order. Groups with the same ID are replaced (a generated group stays replaced until it is reset to generated), other custom groups driving the same outputs are turned off, and the `Fps` declared in the file becomes the project's computation FPS. Parameters the model lacks are logged, and the affected groups show as invalid;
- create a swing for the mesh or warp deformer selected on the canvas (**Swing for the selection…**, which opens [swing editing](SWING.md)).

### Pendulum canvas

With a group selected, its pendulum sits at the top of the editing area. The solid line is the live swinging pendulum; the dashed line is its rest pose, hanging from the live root, with a ring at the end of each pendulum. The view is mirrored left to right, so pulling to the right moves the root to the right.

| Action | Effect |
|------|------|
| Drag left or right on empty space | Pulls the model the way dragging it sideways does in Cubism Viewer (horizontal only), and it eases back when released; the root always moves along the top line |
| Drag a ring up or down | That pendulum's length; clicking selects that pendulum (click again to deselect) so the sliders below adjust only it |
| Output labels on the right | Each label sits level with the pendulum it reads, with a line to that pendulum's live position and a bar showing the current parameter value. Dragging it up or down highlights the nearest pendulum, and releasing makes it read that pendulum (the output row's **Pendulum #**) |
| Click an output label (or the colour dot before the output row) | Shows that output's range fan on the pendulum: when this pendulum swings through the fan's angle relative to the previous pendulum, the parameter goes from one end to the other. Drag the dots on the fan's edges to adjust the range (that is, the scale); the narrower the fan, the stronger the sway |

A click only selects; only a drag past a short distance changes anything, so generated groups are not changed by accident. On hover or while dragging, the top left shows the item's meaning and value.

While pulling, the pointer position is the target: the model follows it with limited speed and acceleration as Cubism's CubismTargetPoint does, then converts that to angle X, body X and eyeball X with CubismLook's factors (the group's other input parameters take the drag amount as a share of half their range). The live pendulum is added on top of the preview parameters while the preview plays (including motions and gaze following), and on top of the Parameters panel values while paused, so dragging a parameter slider directly also shows a response. The button at the bottom right brings the pendulum to rest at once.

The root's movement range and the gravity tilt range (input normalization) are set under **Advanced**.

### Response curve

The curve below the pendulum shows the group's response to one standard pull: pulled fully to the right for 1 second and released, with one curve per output (normalized to the parameter range; the dashed lines are the parameter's ends) and a faint curve for the pull itself. Below the curve, each output's maximum amplitude and how long it takes to settle after release are listed. The curve updates while you adjust the length and dynamics sliders, so you can compare the overshoot and settling of different settings directly.

### Pendulum settings

The **Preset** row corresponds to Cubism Editor's pendulum presets: pick a preset and click **Load** to replace all of this group's pendulums with it (when there are fewer pendulums, outputs reading a removed pendulum read the tip instead). The built-in presets come from the generated front hair, back hair and jelly eyes, plus a three-segment pendulum; under "⋯" you can save the current pendulums with **Add…** as your own preset, or overwrite, rename or delete your own presets. Presets are stored on this machine and shared by all projects.

The **Edit pendulum** row picks what the sliders below act on: **All** adjusts every pendulum at once, a number adjusts only that one (you can also click a ring on the canvas to pick it). It only decides which pendulum the sliders change and is unrelated to an output's **Pendulum #**. The buttons on the right:

- ↑ / ↓: move the selected pendulum; the outputs reading it move with it;
- +: add a pendulum below the selected one (copying its values), or at the tip when none is selected; deeper outputs shift down with it and still read their original pendulums;
- −: delete the selected pendulum (the last one when none is selected). Outputs reading it read the previous one instead, and deeper outputs shift up.

| Slider | Cubism Editor name | Effect |
|------|-------------|------|
| **Total length** / **Length** | Length | Longer swings slower and wider; with **All** picked it scales every pendulum proportionally |
| **Sway** | Sway (Mobility) | Higher takes longer to come to rest |
| **Reaction** | Reaction speed (Delay) | Higher reacts faster to input |
| **Settling** | Convergence speed (Acceleration) | Higher means stronger gravity and a faster return to hanging |

### Inputs

Each input is a parameter and how it acts:

- **Type**: **Position X** pushes the pendulum root left and right; **Angle** tilts the direction of gravity.
- **Weight**: 0–100%, the share of the input normalization range that the parameter's whole range maps to.
- **⇄**: invert.

**+ Add** lists the available parameters, with the common head and body angles first.

The **Preset** row corresponds to Cubism Editor's input presets; a preset holds all inputs and the input normalization. The built-in presets are head and body sway (±10°, as for front hair; ±30°, as for back hair), head and body up/down (as for vertical swing) and eye open/close (as for jelly eyes); parameters the model lacks and the group's own output parameters are skipped. They are managed like pendulum presets.

### Outputs

Each output writes one pendulum's angle to a parameter:

- **Pendulum #**: which pendulum's angle the output reads (relative to the previous pendulum; pendulum 1 is relative to the direction of gravity), the "pendulum number" of Cubism Editor's output settings and `VertexIndex` in `physics3.json`. It is unrelated to **Edit pendulum** above; dragging a label on the right of the canvas next to a pendulum and releasing it changes it too. Connecting several pendulums to different parameters gives whip-like segment-by-segment follow-through.
- **Scale**: the parameter value per radian of swing angle; the result is clamped to the parameter range. You can also drag the ends of the fan's arc on the canvas.
- **⇄**: invert.

Outputs come in the angle type only: Cubism's native runtime does not read the scale of Position X outputs, so such outputs are always 0. If old data read in has Position X outputs, a notice offers to change them to angle with one click.

In **+ Add**, parameters already driven by another active group cannot be picked and name the group driving them.

The **Max output** at the right of each output row corresponds to the column of the same name in Cubism Editor's output settings: since the group was opened (or last reset), the largest value this output reached at the current scale, as a percentage of the parameter's end. It records while pulling on the canvas, playing the preview or following gaze; above 100% it shows in the warning colour, meaning the parameter was clamped at its end. After changing the scale the percentage is converted at once to the new scale, with no need to pull again.

- **Fit scale to max**: sets each output's scale so its max output is exactly 100%. With nothing recorded yet, it measures with the response curve's standard pull.
- **Reset output max**: clears the record and starts measuring again.

### Advanced

Folded under **Advanced** is data the canvas and sliders already cover, needed only for exact values:

- the pendulum settings table: each pendulum's length, sway, reaction speed and convergence speed;
- output details: scale and weight;
- input normalization: minimum / centre / maximum of the Position X and angle inputs.

Click the group name to rename it; the "⋯" menu has **Rename**, **Duplicate**, **Edit swing** (for swing sources), **Reset to generated** and **Delete** (custom groups).

Each drag, slider drag or numeric entry records one history entry on release and can be undone. Edits are saved in the project, and after the model is rebuilt the same groups are regenerated.

## Preview and export consistency

The panel's pendulums, the response curve, the built-in software preview and MCP simulation use the same physics engine, matching Cubism Native Framework 5-r.5's `CubismPhysics::Evaluate` step by step: input normalization, fixed steps at `Fps` with inputs interpolated between frames, passing values between groups through the parameter cache (later groups read earlier groups' outputs from the same step), segment updates and air resistance, output interpolation and weight blending, including some special behaviours of the SDK itself. The repository's `PhysicsReferenceTest` checks it against frame-by-frame data produced by the official Framework on an exported model (`native/physics_reference`): 900 frames with irregular frame intervals, chained groups, inversion, partial weights and translation outputs, with a maximum deviation of about 1e-5 both with and without `Fps`.

The exported `physics3.json` declares the project's frame rate (`Fps`, default 60; not declared for **Unlimited**), and the CMO3's physics FPS is the same, so sway does not change with the render frame rate and stays consistent when re-exported from CMO3 through Cubism Editor. Groups are exported in list order, which is the runtime's computation order. When the official SDK preview is available, the SDK reads the exported `physics3.json` directly for the preview.

Physics writes only parameters; the visible sway depends on the output parameters' keyforms. For a custom group, make the end shapes of its output parameters first, or the parameter moves but the model does not.

## MCP

Physics group operations are split into several tools by purpose, with their arguments in `{"request": {...}}` (for all fields see [MCP usage and interface](../agent/MCP_AUTHORING.md), or call `workspace_get_operation`). Changes and sampling all need `project_id`, `state` (from `workspace_inspect` or the previous result) and a unique `request_id`; tools marked as background jobs return a job handle, and `job_wait` / `job_get` fetch the result.

- `physics_put` (background job): creates or changes a group by `id`, writing only the given fields. An existing ID (including generated groups) starts from the current group; `inputs` / `outputs` / `segments` replace the whole list, `length` / `mobility` / `delay` / `acceleration` apply to every segment, `output_scale` applies to every output, `segment_count` changes the segment count, `normalization` sets the input normalization, and `enabled=false` turns off any group.
- `physics_delete` (background job): deletes a custom group, or resets an edited generated group to its generated values; generated groups themselves are turned off with `physics_put`'s `enabled=false`.
- `physics_simulate` (read-only background job): starting from rest, steps the parameters in `inputs` to the given values, holds them for `hold` seconds and releases them, returning each output's start value, peak, final value and settling time during the hold and after release, plus curves sampled at `samples`; `ids` limits it to the given groups, and `duration` sets the total length.
- `physics_fit` (background job): sets the scale of each output of group `id` so its peak reaches exactly `target`% (default 100) of the parameter's end. Without `observed_peaks` it measures with the response curve's standard pull, the same as the panel's **Fit scale to max** with no live record; with them it uses the measured values, keyed by output index (`"0"`, `"1"`…), with the values being the `peaks` reported by `physics_audition` (1 is the parameter's end).
- `physics_config` (background job): `order` sets the computation order (listed groups come first), and `fps` sets the project frame rate (0 for unlimited).
- `physics_import` (background job): imports physics3.json (`path` is an absolute path) by the same rules as the panel, returning the imported groups, the groups turned off and the missing parameters.

Pulling on the pendulum canvas corresponds to an audition session, which changes neither the document, the history nor saved poses:

- `physics_audition`: `mode` `start` (with `group_id`, and optional `values` added to parameter values) starts an audition and stops this workspace's previous session; `target` pulls the root towards `x`, `y` (-1–1, the panel pointer); `release` lets go; `reset` brings the pendulum to rest; `reset_peaks` starts recording maximums again; `stop` ends it. All but `start` take `session_id`.
- `physics_audition_step`: advances `steps` steps (1–240) of `dt` seconds (at most 0.1), returning the pendulum vertices, outputs and `peaks`.
- `physics_audition_get`: reads only the latest frame, without advancing time.

Changing the document during an audition makes the session keep swinging with the new groups; reopening the project or deleting the group makes the session stale. After pulling, releasing and advancing some steps, pass `peaks` unchanged to `physics_fit`'s `observed_peaks`; this is the panel's pull followed by **Fit scale to max**.

`workspace_inspect` with `scope=physics` returns the computation FPS and all groups in computation order, each with its source, whether it is active, what replaces it and why it is invalid.

Input and pendulum presets can be used over MCP too: `physics_preset_list` lists the built-in and your own presets, and `physics_apply_preset` applies one of them to a group; `physics_preset_put` / `physics_preset_rename` / `physics_preset_delete` manage your own presets and need the `library_state` returned by the list.
