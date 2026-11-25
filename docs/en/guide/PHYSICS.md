<!-- lang: zh en ko ja -->
> [中文](../../zh/guide/PHYSICS.md) · **English** · [한국어](../../ko/guide/PHYSICS.md) · [日本語](../../ja/guide/PHYSICS.md)

# Physics

[Documentation](../README.md) · [Swing generation](SWING.md) · [Simulation and baking](SIMULATION.md) · [MCP usage and API](../agent/MCP_AUTHORING.md)

Physics makes parameters follow the head, the body, and other inputs with an inertial swing. PSD2Live uses Cubism’s pendulum model: a chain of pendulums hanging from the root (the rows numbered 1, 2, 3… in Cubism Editor’s pendulum settings). Inputs push the root or tilt gravity, the pendulums swing by inertia, and the angle of a chosen pendulum is written to an output parameter. On export it is written to `physics3.json` and `.cmo3`.

## Where physics groups come from

Every physics group of the model is listed in the Physics panel. There are five sources:

| Source | Description | ID |
|------|------|----|
| Preset | Front hair, back hair, and eye jelly generated automatically from the layers (present only when the layers contain the matching part) | `PhysicsHairFront`, `PhysicsHairBack`, `PhysicsEyeJelly` |
| Skeleton | Follow-through of a tail or wings from the second bone onward | `PhysicsSkel_<骨骼 id>` |
| Swing | One pendulum for each direction of each swing. See [Swing generation](SWING.md) | `PhysicsSwing_<id>[_X\|_Y]` |
| Simulation | One fitted pendulum for each baked simulation. Each segment drives one mode parameter. Up/down motion has a second pendulum fed by translation. See [Simulation and baking](SIMULATION.md) | `PhysicsSim_<id>`, `PhysicsSim_<id>_y` |
| Custom | Created in the panel or through MCP | `PhysicsCustom<N>` by default |

A generated group can be edited directly. After an edit it becomes Edited. What is saved is your version, and later changes to the source (swing, skeleton) do not overwrite it. A simulation pendulum is three-way merged after a rebake or an output rename: only the fields you changed are kept, and the rest follows the new fit (see [Simulation and baking](SIMULATION.md)). Reset to generated returns to the generated version. Each group can be turned off on its own. A group that is off is not exported. If a custom group drives an output parameter of a generated group, that generated group shows Replaced and is not exported either.

Physics settings in an imported CMO3 keep the IDs from the file. Cubism Editor can save several settings with the same ID. From the second one on they are renamed `<id>_2`, `<id>_3`, and so on (skipping IDs already in the file). Every setting is kept. Each import produces the same IDs, and export uses those IDs.

One parameter can be driven by only one group that is in effect. When a group is invalid (a parameter does not exist, there is no input or no output, or some parameter is both an input and an output), the list marks it Invalid. It is not exported, and the editor shows the reason.

## Physics panel

The physics master switch and the physics frame rate are on the left toolbar of the preview canvas, together with the idle motion and mouse tracking. The physics frame rate can be 30 / 60 / 90 / 120 / unlimited (60 by default). It is a project setting: physics is computed at that rate, and the exported `physics3.json` (`Fps`) and the CMO3 write it too. With Unlimited, physics is computed on every rendered frame, export declares no `Fps`, and each runtime computes physics on its own frames (a CMO3 writes no physics FPS). Preview frames are not limited by it. The preview’s frame-rate cap is in the menu of the status capsule at the bottom left of the canvas, Follow display by default, and it is independent of the physics frame rate. Physics interpolates between the two steps.

Above the list, the panel shows whether physics is running, the current frame rate, and an “n / m active” count, plus the preview’s idle-motion and mouse-tracking switches.

The idle motion and physics are independent. When the idle motion is stopped and physics is on, dragging a parameter slider or turning the head with mouse tracking still swings the physics in the preview, and the preview stops producing frames once everything is still. Turning physics off shows only the edited pose. While paused, physics is computed by the same engine as export (see “Preview and export agreement” below). It does not keep a paused motion playing.

The parameter panel and the preview always agree. While the idle motion plays, the sliders change with the preview frame by frame. When it is stopped, the angles and eye parameters driven by mouse tracking are shown live in the parameter panel as well. The pendulums in the Physics panel read that same pose.

For physics work, create a Physics workspace (+ menu on the tab bar): from left to right, the preview canvas, the parameter panel, and the Physics panel, about 2 : 1 : 1 wide. The other panels are hidden, and can be opened from the Window menu when needed.

Below that is the physics group list, in computation order, with a sequence number. Each row can be checked to enable it, and shows its source and status (Edited / Invalid / Replaced). ↑ / ↓ on the title bar changes the computation order of the selected group. Cubism computes in order. A later group can read an earlier group’s output in the same step, so when one group takes another group’s output as an input (chained physics), the group that is read has to come first.

+ New can:

- Create a blank pendulum: the default inputs are the head and body sway, one pendulum of length 10, and no output yet.
- Duplicate the selected group as a new group (the output parameters are cleared, so it does not conflict with the original).
- Import physics3.json: each group in the file becomes a custom group, placed after the existing groups in file order. A group with the same ID is replaced (a generated group stays replaced until Reset to generated). Other custom groups that drive the same outputs are turned off. The `Fps` declared by the file becomes the project’s computation FPS. Parameters the model does not have are noted in the log, and the matching group is shown as invalid.
- Create a swing for a mesh or warp deformer selected on the canvas (enters [swing editing](SWING.md)).

### Pendulum canvas

With a group selected, the top of the editor is its pendulums. The solid line is the pendulum swinging live. The dashed line is its rest pose, hanging from the live root, with a ring at the end of each pendulum. The view is mirrored left to right, so pulling to the right moves the root to the right.

| Action | Effect |
|------|------|
| Drag left and right on empty space | Pulls the model the way a sideways drag pulls a model in Cubism Viewer (horizontal only). After release it eases back. The root always moves along the top horizontal line |
| Drag a ring up or down | That pendulum’s length. A click selects that pendulum (click again to clear it), and the sliders below adjust only that one |
| Output label on the right | The label sits at the height of the pendulum it reads. A line points at that pendulum’s live position, and a bar shows the current parameter value. While dragging up or down, the nearest pendulum highlights. Release to read that pendulum instead (the output row’s Pendulum #) |
| Click an output label (or the color dot in front of an output row) | Shows that output’s range fan on the pendulum: when this pendulum swings through the fan’s angle relative to the pendulum above, the parameter travels from one end to the other. Drag the dot on the fan’s edge to adjust the range (the scale). A narrower fan makes the swing more visible |

A click only selects. A drag has to travel a short distance before it edits, so a generated group is not changed by accident. While hovering or dragging, the top left shows what that item means and its value.

While pulling, the pointer position is the target. The model follows it at Cubism’s CubismTargetPoint limited speed and acceleration, then converts that, by CubismLook’s coefficients, into angle X, body X, and eye X (the group’s other input parameters take the drag as a fraction of half their range). The live pendulum is added on top of the preview parameters while the preview plays (including motions and gaze follow), and on top of the parameter panel’s values while paused, so dragging a parameter slider directly shows a response too. The button at the bottom right sets the pendulums still at once.

The root’s travel range and the gravity tilt range (input normalization) are set under Advanced.

### Response curve

The curve under the pendulums shows how this group responds to one standard pull: pulled fully to the right for 1 second, then released. Each output is one curve (normalized to the parameter range; dashes are the parameter ends), and the faint curve is the pull itself. Under the curve, each output lists its peak and how long after release it becomes still. The curve updates as length and the dynamics sliders change, so different setups can be compared for overshoot and settling.

### Pendulum settings

The Preset row matches Cubism Editor’s pendulum presets. Choose a preset and click Load to replace every pendulum of this group (when the pendulum count drops, an output that read a deleted pendulum reads the tip instead). The built-in presets are taken from the generated front hair, back hair, eye jelly, and a three-pendulum chain. Under ⋯, the current pendulums can be Add…’d as your own preset, or one of your presets can be overwritten, renamed, or deleted. Presets are stored on this machine and shared by every project.

The Edit pendulum row chooses what the sliders below act on. All adjusts every pendulum at once. A number adjusts only that one (a ring on the canvas can be clicked to select it too). It only decides which pendulum the sliders change. It is unrelated to an output’s Pendulum #. The buttons on the right:

- ↑ / ↓: move the selected pendulum. Outputs that read it move with it.
- +: append one below the selected pendulum (copying the selected pendulum’s values). With nothing selected, it is added at the tip. Deeper outputs shift down and still read the pendulum they read before.
- −: delete the selected pendulum (or the last one when nothing is selected). An output that read it reads the previous one, and deeper outputs shift up.

| Slider | Cubism Editor name | Effect |
|------|-------------|------|
| Total length / Length | Length | Longer swings more slowly and farther. All scales every pendulum in proportion |
| Sway | Mobility | Higher is harder to stop |
| Reaction | Delay | Higher reacts to input faster |
| Settling | Acceleration | Higher gravity is stronger, and it returns to hanging down faster |

### Inputs

Each input is a parameter and how it influences the pendulum:

- **Type**: **Position X** pushes the pendulum root left and right. **Angle** tilts the direction of gravity.
- **Weight**: 0–100%. The fraction of the input-normalization range that the parameter’s whole range maps onto.
- **⇄**: reflect.

+ Add lists the available parameters. The common head and body angles are listed first.

The Preset row matches Cubism Editor’s input presets. One preset includes every input and the input normalization. The built-in presets are head and body sway (±10°, the same as front hair; ±30°, the same as back hair), head and body pitch (the same as vertical swing), and eye open/close (the same as eye jelly). Parameters the model does not have, and this group’s own output parameters, are skipped. Presets are managed the same way as pendulum presets.

### Outputs

Each output writes some pendulum’s angle into a parameter:

- **Pendulum #**: which pendulum’s angle the output reads (relative to the pendulum above; pendulum 1 relative to the direction of gravity). This is Pendulum # in Cubism Editor’s output settings, and `VertexIndex` in `physics3.json`. It is unrelated to Edit pendulum above. Dragging the label on the right beside a pendulum and releasing also changes it. Connecting several pendulums to different parameters makes a whip-like follow, segment by segment.
- **Scale**: the parameter value per radian of swing. The result is clamped to the parameter range. The end of the fan arc can also be dragged on the canvas.
- **⇄**: reflect.

Outputs are angle only. The native Cubism runtime does not read the scale of a Position X output, and that kind of output is always 0. Older data that has a Position X output is called out and can be switched to angle in one step.

In + Add, a parameter already driven by another group that is in effect is shown as unavailable, with a note of which group drives it.

Max output on the right of each output row matches the column of the same name in Cubism Editor’s output settings: from when this group was opened (or last reset), the largest value this output has reached at the current scale, as a percentage of the parameter end. Pulling the canvas, playing the preview, and gaze follow all record it. Over 100% is shown in the warning color, which means the parameter has been clamped at the end. After the scale changes, the percentage is converted to the new scale at once, with no need to pull again.

- Fit scale to max: adjusts each output’s scale so its max output is exactly 100%. When nothing has been recorded yet, it measures with the response curve’s standard pull.
- Reset output max: clears the record and starts measuring again.

### Advanced

Folded under Advanced is the data the canvas and the sliders already cover, used only when an exact number is needed:

- The pendulum settings table: each pendulum’s length, sway, reaction, and settling.
- Output details: scale and weight.
- Input normalization: minimum / center / maximum for Position X and angle inputs.

Click the group name to rename it. The ⋯ menu has rename, duplicate, edit swing (swing sources), reset to generated, and delete (custom groups).

Each drag, slider drag, or number entry records one history step on release, and can be undone. Edits are stored in the project. After the model is rebuilt, the same groups are generated again.

## Preview and export agreement

The pendulums in the panel, the response curve, the software preview of the built-in renderer, and MCP’s simulation use the same physics engine. Each piece matches `CubismPhysics::Evaluate` in Cubism Native Framework 5-r.5: input normalization, a fixed step at `Fps` with input interpolated between frames, groups passing values through the parameter cache (a later group can read an earlier group’s output from the same step), segment updates and air resistance, and output interpolation and weight blending, including some special behavior of the SDK itself. `PhysicsReferenceTest` in the repository checks against frame-by-frame data the official Framework produced on exported models (`native/physics_reference`): 900 frames at irregular frame intervals, chained groups, reflection, partial weights, and translation outputs, with and without `Fps`. The largest deviation is about 1e-5.

The exported `physics3.json` declares the project’s frame rate (`Fps`, 60 by default; omitted when Unlimited is selected). The CMO3 physics FPS matches it. The swing does not change with the render frame rate, and exporting again from a CMO3 through Cubism Editor stays consistent. Groups are exported in list order, which is the runtime’s computation order. When the official SDK preview is available, the preview is read by the SDK directly from the exported `physics3.json`.

Physics writes parameters only. The swing depends on the output parameter’s keyforms. A custom group needs the end forms of its output parameters prepared first. Otherwise the parameters move and the model does not.

## MCP

Physics-group operations are split into several tools by job. Parameters all go in `{"request": {...}}` (the full fields are in [MCP usage and API](../agent/MCP_AUTHORING.md), or call `workspace_get_operation`). Edits and samples all carry `project_id`, `state` (from `workspace_inspect` or the previous result), and a unique `request_id`. A tool marked as a background job returns a job handle. Take the result with `job_wait` / `job_get`.

- `physics_put` (background job): creates or edits by `id`, and writes only the fields that are given. An existing ID (including a generated group) starts from the current group. `inputs` / `outputs` / `segments` replace the list as a whole. `length` / `mobility` / `delay` / `acceleration` apply to every segment. `output_scale` applies to every output. `segment_count` changes the segment count. `normalization` sets input normalization. `enabled=false` turns any group off.
- `physics_delete` (background job): deletes a custom group, or restores an edited generated group to the generated values. A generated group itself is turned off with `enabled=false` on `physics_put`.
- `physics_simulate` (read-only background job): from rest, steps the parameters in `inputs` to the given values, holds them for `hold` seconds, then releases them. Returns each output’s start, peak, final value, and settling time during the hold and after release, plus a curve sampled by `samples`. `ids` looks at only the given groups. `duration` sets the total time.
- `physics_fit` (background job): sets the scale of each output of group `id` so the peak reaches exactly `target`% of the parameter end (100 by default). When `observed_peaks` is omitted, it measures with the response curve’s standard pull, the same as Fit scale to max on the panel when there is no live record. When it is given, the scales are adjusted from the measured values. Keys are output indices (`"0"`, `"1"`, …) and values are the `peaks` reported by `physics_audition` (1 is the parameter end).
- `physics_config` (background job): `order` sets the computation order (the listed groups come first). `fps` sets the project frame rate (0 is unlimited).
- `physics_import` (background job): imports physics3.json (`path` is an absolute path). The rules match the panel. It returns the imported groups, the groups that were turned off, and the missing parameters.

Pulling on the pendulum canvas is an audition session. It does not change the document, history, or the stored pose:

- `physics_audition`: `mode` of `start` (give `group_id`, and optionally `values` to add parameter values) starts an audition and stops the previous session in this workspace. `target` pulls the root toward `x`, `y` (−1–1, the panel pointer). `release` lets go. `reset` sets the pendulums still. `reset_peaks` starts recording the maximum again. `stop` ends it. The later modes all carry `session_id`.
- `physics_audition_step`: advances `steps` steps (1–240) by `dt` seconds (at most 0.1), and returns pendulum vertices, outputs, and `peaks`.
- `physics_audition_get`: reads only the latest frame, and does not advance time.

Editing the document during an audition makes the session keep swinging with the new groups. Reopening the project, or deleting that group, makes the session expired. After pulling, releasing, and advancing several steps, pass `peaks` unchanged as `observed_peaks` of `physics_fit`. That is clicking Fit scale to max on the panel after a pull.

`workspace_inspect` with `scope=physics` returns the computation FPS and every group in computation order. Each group includes its source, whether it is in effect, what replaced it, and why it is invalid.

Input and pendulum presets can be used through MCP as well. `physics_preset_list` lists the built-in presets and your own. `physics_apply_preset` applies one of them to a group. `physics_preset_put` / `physics_preset_rename` / `physics_preset_delete` manage your own presets, and need the `library_state` returned by the list.
