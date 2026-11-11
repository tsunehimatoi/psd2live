# Presets

Projects with an explicit stack display the Presets panel; legacy projects retain their preset panel when opened. Use **Convert to Editable Presets** to request conversion. Conversion has been verified for ordinary mesh projects, basic legacy bindings, and a modern fixture with multiple body areas and add-on modules. Other projects can still fail the result comparison. Disabled legacy simulations stay disabled after conversion. An enabled simulation needs an existing bake that matches the candidate inputs. Source bindings can be added, adjusted, reordered, and applied. Swing, physics groups, simulation, and generated motion share this addition flow and have in-stack controls. Start-page shortcuts also add explicit modifiers through isolated conversion. Earlier panel revisions were captured across languages and themes; those captures do not verify the redesigned list and inspector.

## Create a PSD project

The start page initially selects **Recommended modifiers**. The first revision includes the shared structure and body, face, eye, brow, mouth, and hair bindings. Choose **Empty modifier stack** to start with editable source meshes. Minimal, Default, Full, and custom choices generate their bindings, physics, simulations, and motions in an isolated import candidate. Explicit instances are evaluated before installing one initial revision. Entries remain independently editable; the group only records their source. Failure or cancellation installs no partial project.

## Add and switch

Legacy and Modern are two modifier group presets in the same project model, not project format versions. New projects directly instantiate the selected bindings, modules, dynamics, motions and clothing simulations. The stack evaluates them; no intermediate legacy model is generated or migrated.

Choose meshes, then **Add Preset**. Algorithms are grouped by body area, with legacy and fitted choices together. Clothing and hair simulations appear in their respective areas and use the original automatic material and weight setup. Generic generators are under Advanced. Selection previews a dependency list; expensive evaluation runs on confirmation. Non-stackable items already present on all selected targets are hidden, including disabled instances. Partially covered selections show the existing count and add only to uncovered targets. Stackable items such as swings and motions remain available.

**Switch Preset** replaces the whole preset with **Legacy**, **Modern**, or **Empty**, preserving ordinary authored content. It commits once and can be undone. Tight clothing is skipped when it needs no simulation. Automatically generated weights belong to individually added simulation instances; existing authored groups with the same name take precedence.

Switching currently rejects generated-content overrides or applied preset capabilities instead of discarding edits. Handle those contents before switching. Tiered MCP groups remain available for compatibility but are no longer the primary UI choice.

**Settings** organizes entries into folders, grouped by purpose by default. Create, rename, delete and drag folders or entries without changing execution order or rebuilding the model. **Order** displays a flat list and uses the parameter list drag interaction to change execution order; invalid dependency orders are rejected. Folder layout is saved with the project and supports undo and redo. Dependencies are added before new consumers; existing entries are not silently reordered.

The library also includes Modern Light, Modern Dynamic and Legacy Light presets. **Manage presets** saves the current configuration, imports or exports JSON, and deletes the selected library preset. Saved presets include modifier configuration and folders, excluding generated-content overrides and bake caches; simulations bake again on application. Both bundled and user presets are JSON stored in application preferences. Missing bundled entries are restored from the shipped JSON and then read through the same path. Targets resolve by stable reference or a unique name and type; an ambiguous or missing target fails without widening the scope.

The shared structure modifier can enable the original feature displacement hierarchy. Later face bindings continue to use it after the structure is applied.

New structures use version 2 and share parents within the captured target group, instead of duplicating an entire parent chain per mesh. Downstream bindings must cover the shared structure’s complete target scope. Targets under different authored parents cannot share one structure. Saved version 1 stacks retain their original object IDs and replay behavior; existing branches are not automatically merged or upgraded.

## Adjust and locate

Select a compact list row to edit all its values to the right of the list. Drag the center divider to resize the independently scrolling list and inspector. Adjustment forms use the shared inspector layout. Physics edits the selected input, output, or segment; motion edits the selected track and key.

Targets and Generated Content provide object location and actual contributions. Apply Through Here stays available for the selected item.

`face/legacy` and `face/fitted` are mutually exclusive bases on the same targets. The fitted base reuses the existing fitting algorithm and provides the head parameters required downstream without inserting a separate legacy face entry. Stored `face.depth-turn` entries still replay; new additions use `face/fitted`. A single-item plan reuses an existing legacy face provider on its targets, or defaults to fitted face when no provider exists. Explicit legacy face and legacy group choices retain the legacy algorithm. Existing stacks are not rewritten.

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

Swing exposes pivots, offsets, axes, segments, shape, and optional physics. Physics groups expose parameter inputs/outputs, segments, and normalization. Generated motion uses existing algorithms and only includes tracks supported by current parameters; choose a preset before insertion. In-stack controls edit duration, looping, fades, tracks, and keys. Edit values in the preset inspector after insertion.

Physics requires an available output parameter and a distinct input. Add binding modifiers or ordinary parameters first if necessary. Conflicting output writes block insertion without replacing existing groups. Simulation bakes its actual upstream stack in the background, independently of the playback frame. Controls include material, inputs, modes, keys, exaggeration, blend-shape output mode, and adding or removing static input axes. Pin, stiffness, shape, mass, damping, and wind can select a named vertex group on the targets. The default reads the first group of that kind on each target. Add a collider on a mesh outside the simulation targets, adjust vertices A/B, both radii, and friction, or remove it. Equal endpoints form a circle and different endpoints form a capsule. Vertex indices start at zero. Select ordinary clips or preceding motion modifiers for training, adjust their weights, or remove them. A missing clip suspends simulation evaluation while retaining its entry. Moving the simulation above its motion provider is rejected. Automatic baking refreshes outdated results within the same edit transaction; otherwise stale results block submission. Inputs without enough movement are rejected; explicitly choose Keep to retain a small input.

Blink aftershock and brow spring add pendulums together with their bindings. Expand Physics inside the modifier to edit inputs/outputs, segments, and normalization; values stay in that instance. Separate targets can reuse compatible standard-output groups, retaining the first owner’s identity and name. Incompatible shared values reject the edit; disable other users first and align their settings before re-enabling. Applying retains the edited values as ordinary physics.

Mouth modifiers include capture axes, tongue, jaw, interior depth, and spring. Addition plans list their prerequisites. Cheek and gloom add color channels that combine with mouth movement. Color-key edits belong to an instance, sleep while disabled, and resume with that original instance.

Cheek v4 (`expression.cheek@4`) is an explicit-only source candidate for single-eye, whole open-mouth artwork with a matching brow and eyelash and unedited, unsplit preset skin. It uses the specific source-provider versions listed in [Algorithm plugins](ALGORITHM_PLUGINS.md), with both mood parameters unused. Its frozen, separately compiled `skinReplayV1` helper reconstructs source motion and checks the exact preceding skin and carrier state before adding warmth. Dense detail meshes are rejected if that state cannot be reproduced exactly; provider versions are not downgraded to obtain a match. Existing defaults and saved locks are unchanged, Gloom v4 still requires Cheek v1, and this candidate does not establish independence of the entire preset chain.

The Physics panel shows evaluated stack groups with a Modifier badge. Edit generated groups in their owning modifiers; Open Presets returns to that panel. Their order follows the stack. After application, they are ordinary custom configurations that can be edited, disabled, and reordered normally.

Skin clipping adds face masks for blush, skin overlays and bangs shadows. Neck follow binds the neck to head tilt and clips its shadow; its addition plan includes a pinned face-binding prerequisite. Both use existing artwork. Contributions show the mask sources and inversion before and after the modifier; click the target or a source to locate it.

Neck follow v4 is an explicit-only source candidate. It reconstructs ordinary neck tilt and neck-shadow turn motion from the current preceding configuration inside its worker. It currently requires the current bundled providers and supports unsided neck meshes directly below the shared body/breath branch, with unchanged source UVs and topology. Older provider package digests, paired necks, parent wrappers and transitions needing a private turn carrier are rejected. Existing neck versions, preset choices and saved v1–v3 package locks are unchanged.

Segmented hair chains are an alternative to the legacy hair base on the same target. Skirts, ribbons and pendants, ears, tails and wings, chest follow and arm lag can also be added separately. The addition plan lists the required body and face bindings. Adjust swing, hair softness and the actual physics groups inside their modifier; moving other entries keeps the chain references intact.

Fitted face turning is a face base, mutually exclusive with the legacy face base. Adjust turn, depth, and style inside the modifier. Choosing a new style resets the other values in that modifier to their defaults. Eligible side locks move behind the face at extreme turns.

When adding physics, choose front hair sway, back hair sway, or eye jelly. If required parameters are missing, the plan lists the base bindings to add with the preset. When adding a motion, choose the original or natural idle, blink, nod, or shake, or choose natural speech. Choosing a natural motion plans any missing base bindings. Editing its values before confirmation keeps the planned prerequisites. Edit the resulting rule or tracks inside the instance.

## Shared with MCP

`modifier_catalog` lists algorithms; `modifier_stack` reports captured state and actual contributions. `modifier_plan` plans source bindings and prerequisites, and accepts native swing, physics, simulation, and motion configurations. Dry-run its `group` and `entries` with `workspace_preview_regeneration`, then submit `modifier_add_group` against the same `state` to reproduce the GUI addition flow. See the [MCP reference](../agent/MCP_AUTHORING.md).

Blink motion modifiers also contribute the EyeBlink runtime parameter role. The contribution appears under relations and survives applying the modifier as ordinary model metadata.

Applying a motion modifier preserves its file name and exact curve controls. Later parameter-range edits constrain the ordinary clip to the new range; saving, reopening or rebuilding alone does not alter the curve.

Expand a motion track to edit key times, values, interpolation, and incoming/outgoing Bézier handles. The Events section supports adding, retiming, editing, and deleting events. Applying the modifier retains an ordinary editable motion clip.

## Non-PSD creation

Blank Layout projects and imported Layout manifests start with an explicit empty stack and editable meshes. The first generation from an empty stack uses the complete Modern baseline. Later generation adds the selected compatibility group to new targets without replacing existing bindings. MCP `rig_generate.preset_group` selects any of the six groups or `none` for plain meshes. Source-art creation uses the recommended group. Legacy documents retain their existing generation path.

New CMO3 imports use an empty preset stack, retaining the imported model and physics as ordinary authored content.

See [Algorithm plugins](ALGORITHM_PLUGINS.md) for installation, removal, and language integration.
