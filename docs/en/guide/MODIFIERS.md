# Presets

Projects with an explicit stack display the Modifier Stack panel; legacy projects retain their preset panel when opened. Use **Convert to Modifier Stack** to request conversion. Conversion has been verified for ordinary mesh projects, basic legacy bindings, and a modern fixture with multiple body areas and add-on modules. Other projects can still fail the result comparison. Disabled legacy simulations stay disabled after conversion. An enabled simulation needs an existing bake that matches the candidate inputs. Source bindings can be added, adjusted, reordered, and applied. Swing, physics groups, simulation, and generated motion share this addition flow and have in-stack controls. Start-page shortcuts also add explicit modifiers through isolated conversion. Panel captures cover four languages, eight themes, and two scales; motion handles, events, and simulation controls have been visually checked.

## Create a PSD project

The start page initially selects **Recommended modifiers**. The first revision includes the shared structure and body, face, eye, brow, mouth, and hair bindings. Choose **Empty modifier stack** to start with editable source meshes. Minimal, Default, Full, and custom choices generate their bindings, physics, simulations, and motions in an isolated import candidate. The complete output is compared before installing one initial revision. Entries remain independently editable; the group only records their source. Failure or cancellation installs no partial project.

## Add content

1. Select the meshes to affect. Selecting only a Warp allows swing insertion on that Warp. With no meshes or Warp selected, the flow captures all current meshes.
2. Choose **Add Preset** and a binding, swing, physics group, simulation, or generated motion, or **Add Preset Group** for the fitted binding group. This group contains body, face, eye, brow, mouth, and hair bases; it does not replace every existing shortcut preset.
3. Review captured targets, the full prerequisites, and expected contributions. The plan distinguishes additions, reused providers, and applied content. Expand parameters, meshes, deformers, relations, and hierarchy separately.
4. Confirm. New entries are added in one history transaction and can then be adjusted independently. Later selection changes do not alter their scope.

A group records provenance without locking entries together. Adding never silently disables an exclusive base or reorders existing items; incompatible candidates are rejected.

The shared structure modifier can enable the original feature displacement hierarchy. Later face bindings continue to use it after the structure is applied.

New structures use version 2 and share parents within the captured target group, instead of duplicating an entire parent chain per mesh. Downstream bindings must cover the shared structure’s complete target scope. Targets under different authored parents cannot share one structure. Saved version 1 stacks retain their original object IDs and replay behavior; existing branches are not automatically merged or upgraded.

## Adjust and locate

Expand a preset to adjust its complete controls directly, including turn style and detailed values. There is no three-control limit or separate adjustment page. Binding controls are grouped by area; physics edits the selected input, output, or segment, and motion edits the selected track and key.

**Inspect** only contains affected objects and generated content, including object location, provider connections, and **Apply Through Here**. The addition dialog still offers optional detailed configuration.

The face base retains the `face/legacy` identity. Landmark-fitted turning is supplied by `face.depth-turn`, with the existing algorithm fallback when the artwork does not support fitting. The fitted group now includes this module for artwork with a face. Saved stacks are not changed automatically; add Depth Turn or the fitted group again to supply it. This group is still not every optional preset: model parameters are generated only by the active presets actually present.

Drag the modifier name to change actual evaluation order. The drag image and displaced sibling rows share the parameter panel implementation. Invalid dependency order is rejected, leaving the original stack intact. A continuous slider or number edit commits one history node when it ends.

Disabling stops evaluation; dependent entries remain visible with **Missing dependency** status. Re-enabling the original instance restores its overrides. Removing retains dormant overrides, and undo restores the entry. A newly added instance does not inherit a removed instance’s edits.

Rename generated meshes, parts, and deformers, adjust their supported static appearance, or change their hierarchy in the regular editor. Referenced objects must exist by that modifier’s evaluation stage. Edits to a generated parameter’s name, range, and default are stored with its provider. Changes that conflict with downstream definitions are rejected. The edits belong to the creating modifier and resume when it is re-enabled.

Add, move, or delete keys on an object track generated by a modifier. Select a specific track when the command would otherwise edit tracks owned by several instances. Parameter panel stops are stored with the instance that generated the parameter.

Exporting to a format with live simulation includes active, baked simulation entries. The scenes reference the parameters and physics groups generated by the same evaluation.

## Apply Through Here

**Apply Through Here** materializes the effective results through that entry as ordinary editable content and removes the entire prefix. Disabled entries are removed without generating content. Later instances and object references remain; the complete result must match before and after application.

Physics becomes ordinary configuration, generated motion becomes ordinary clips with their file names, export groups, and runtime fade defaults, and simulation materializes its generated bindings. The current playback frame is not the result. Missing dependencies, unmatched overrides, or invalid bakes block application; failure publishes no partial result. Undo restores the original stack.

Eye modifiers include widening, lid-line fading, and highlight parallax. Widening explicitly extends eye openness to 2 and reports the parameter change. Highlight insertion plans include a face-binding prerequisite. Newly added stops show their key coordinates in the contribution list.

Add eye and brow see-through separately and set each entry’s opacity. The contribution list shows generated meshes, hierarchy, and mask relationships.

**Toggle choreography** adds parameters and opacity keys to captured toggle and switch layers. The incoming variant fades in before the outgoing one fades out. Toggles and switches also feed generated hanging chains, and the contribution list shows the affected physics groups.

**Mouth outline** creates upper and lower lip meshes with their own textures on eligible mouth artwork. Set the stroke thickness and an RGB color in the modifier, or sample the color from the source artwork. Applying it saves both meshes and textures with the project.

## Native generators

Swing exposes pivots, offsets, axes, segments, shape, and optional physics. Physics groups expose parameter inputs/outputs, segments, and normalization. Generated motion uses existing algorithms and only includes tracks supported by current parameters; choose a preset before insertion. In-stack controls edit duration, looping, fades, tracks, and keys. Pre-add field changes update only the candidate until confirmed.

Physics requires an available output parameter and a distinct input. Add binding modifiers or ordinary parameters first if necessary. Conflicting output writes block insertion without replacing existing groups. Simulation bakes its actual upstream stack in the background, independently of the playback frame. Controls include material, inputs, modes, keys, exaggeration, blend-shape output mode, and adding or removing static input axes. Pin, stiffness, shape, mass, damping, and wind can select a named vertex group on the targets. The default reads the first group of that kind on each target. Add a collider on a mesh outside the simulation targets, adjust vertices A/B, both radii, and friction, or remove it. Equal endpoints form a circle and different endpoints form a capsule. Vertex indices start at zero. Select ordinary clips or preceding motion modifiers for training, adjust their weights, or remove them. A missing clip suspends simulation evaluation while retaining its entry. Moving the simulation above its motion provider is rejected. Automatic baking refreshes outdated results within the same edit transaction; otherwise stale results block submission. Inputs without enough movement are rejected; explicitly choose Keep to retain a small input.

Blink aftershock and brow spring add pendulums together with their bindings. Expand Physics inside the modifier to edit inputs/outputs, segments, and normalization; values stay in that instance. Separate targets can reuse compatible standard-output groups, retaining the first owner’s identity and name. Incompatible shared values reject the edit; disable other users first and align their settings before re-enabling. Applying retains the edited values as ordinary physics.

Mouth modifiers include capture axes, tongue, jaw, interior depth, and spring. Addition plans list their prerequisites. Cheek and gloom add color channels that combine with mouth movement. Color-key edits belong to an instance, sleep while disabled, and resume with that original instance.

The Physics panel shows evaluated stack groups with a Modifier badge. Edit generated groups in their owning modifiers; Open Modifier Stack returns to that panel. Their order follows the stack. After application, they are ordinary custom configurations that can be edited, disabled, and reordered normally.

Skin clipping adds face masks for blush, skin overlays and bangs shadows. Neck follow binds the neck to head tilt and clips its shadow; its addition plan includes a pinned face-binding prerequisite. Both use existing artwork. Contributions show the mask sources and inversion before and after the modifier; click the target or a source to locate it.

Segmented hair chains are an alternative to the legacy hair base on the same target. Skirts, ribbons and pendants, ears, tails and wings, chest follow and arm lag can also be added separately. The addition plan lists the required body and face bindings. Adjust swing, hair softness and the actual physics groups inside their modifier; moving other entries keeps the chain references intact.

Depth turn requires a face binding. Adjust turn, depth, and style inside the modifier. Choosing a new style resets the other values in that modifier to their defaults. Eligible side locks move behind the face at extreme turns.

When adding physics, choose front hair sway, back hair sway, or eye jelly. If required parameters are missing, the plan lists the base bindings to add with the preset. When adding a motion, choose the original or natural idle, blink, nod, or shake, or choose natural speech. Choosing a natural motion plans any missing base bindings. Editing its values before confirmation keeps the planned prerequisites. Edit the resulting rule or tracks inside the instance.

## Shared with MCP

`modifier_catalog` lists algorithms; `modifier_stack` reports captured state and actual contributions. `modifier_plan` plans source bindings and prerequisites, and accepts native swing, physics, simulation, and motion configurations. Dry-run its `group` and `entries` with `workspace_preview_regeneration`, then submit `modifier_add_group` against the same `state` to reproduce the GUI addition flow. See the [MCP reference](../agent/MCP_AUTHORING.md).

Blink motion modifiers also contribute the EyeBlink runtime parameter role. The contribution appears under relations and survives applying the modifier as ordinary model metadata.

Applying a motion modifier preserves its file name and exact curve controls. Later parameter-range edits constrain the ordinary clip to the new range; saving, reopening or rebuilding alone does not alter the curve.

Expand a motion track to edit key times, values, interpolation, and incoming/outgoing Bézier handles. The Events section supports adding, retiming, editing, and deleting events. Applying the modifier retains an ordinary editable motion clip.
