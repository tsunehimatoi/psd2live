<!-- lang: zh en ko ja -->
> [中文](../../zh/spec/MODIFIER_AUTHORING_AND_APPLY.md) · **English** · [한국어](../../ko/spec/MODIFIER_AUTHORING_AND_APPLY.md) · [日本語](../../ja/spec/MODIFIER_AUTHORING_AND_APPLY.md)

# Modifiers and manual edits together

## Goals and boundaries

An ordinary reshape should live together with modifiers. The user does not have to apply a modifier first. When the result really has to leave the generation logic, there is an apply operation whose scope can be inspected and that can be undone. There is no implicit bake, and a manual edit is never dropped silently. Object parent-child relationships, modifier evaluation dependencies, and plugin-package dependencies are handled separately.

A brush is only one tool that produces a geometry edit. Vertex drags, the transform box, temporary deformers, paths and handles, numeric input, keyform operations, and Agent commands follow the same edit contract. Interaction tools sample. The model layer prepares keyforms, ownership, replay, and dependency checks. The output of the bone-pose tool is a parameter value. It is not an operation that changes geometry after the parameters are fixed.

## Edit contract

One edit fixes the document version, the target, the pose, the channel, the execution stage, and the merge mode. Display, picking, snapping, preview, and commit use the model and the coordinates of the same stage. If the document changes during the edit, the commit is cancelled or rejected. A failed commit produces no partial model and no history record.

An edit that is not on a key keeps the current parameter values by default and prepares the required keyforms from the interpolated result. The user can explicitly choose the nearest common keyform: for several objects, the key values are intersected per parameter, and one pick takes the nearest value. When the intersection is empty, snapping is blocked and the reason is stated. Parameters are not switched back and forth per object. From the start of a gesture until the asynchronous commit ends or is cancelled, the pose and the merge mode stay fixed.

`edit_stage` sets where the correction sits:

| Value | Source model and where it runs |
| --- | --- |
| `auto` | The current final model, assigned automatically to the channel's actual writer |
| `base` | The authored model before modifiers |
| `modifier:<id>` | After the specified active modifier |
| `final` | After the last active modifier at the time of this edit; the base when there is no active node |

An explicit stage currently accepts geometry and keyform values. It does not accept topology operations. One transaction must not mix stages, and it must not refer to a parameter that does not yet exist at that stage. A disabled or failed node cannot be an edit source. `final` is resolved to a concrete position when the edit starts. A modifier added later does not move an old correction.

## Where a correction belongs, and how it merges

The ownership report records the actual rewrite of objects and of existing keyforms. The same channel belongs to the last writer. Ownership cannot be decided only from the object's creator or from a newly added axis. A base edit runs before generation. A local correction is placed after the related generated result. A final-appearance correction is chosen explicitly by the author.

`correction_mode` chooses how geometry merges:

- `delta`: A mesh and a Warp store the difference of local coordinates before and after the edit. After each generation it is replayed once, then downstream runs. It does not accumulate the displacement from the previous recompute.
- `absolute`: Stores an explicit fixed override. Untouched components follow the generated result. On a three-way conflict the author value is kept and the conflict is reported.

When the explicit keyform API does not specify a mode, the fixed-override meaning is kept. The canvas commits according to the toolbar choice. An old override with no recorded mode keeps the historical three-way merge rule. It is not migrated to a delta automatically.

A rotation correction uses a local delta for the pivot and the angle, and a ratio for positive scale. An explicit assignment uses a fixed override. Channels such as color and opacity keep an explicit set meaning. They are not forced to add.

A geometry correction records a binding fingerprint: for a mesh, the vertex count, connectivity, UVs, and the parent chain; for a Warp, the grid rows and columns, the interpolation, and the parent chain. After the binding changes, the record is kept and a mismatch is reported. It is not applied further. The current check is conservative. A UV reorder can also require the correction to be handled. Topology is not migrated automatically.

Algorithm writes and author corrections are recorded separately. Algorithms keep checking that their writes can be combined. An author correction is replayed at the specified stage. A geometry correction hanging after a motion node must not be counted as geometry generated by the motion algorithm. Cache reuse and checkpoint replay use the same algorithm-write record.

## Apply mechanism

`modifier_apply_plan` is a read-only scope plan. The GUI and commands share it. Before apply, it shows what will be baked, disabled, and kept, the correction count, fixed-override conflicts, mismatch records, and cross-boundary dependencies. After apply, the full result still has to be re-evaluated and compared.

| Scope | Behavior |
| --- | --- |
| `selected` | Bake the selected nodes at their original evaluation position, and keep the nodes before and after |
| `prefix` | Write the prefix through the selected node into the base as an equivalent, and keep the suffix |

For `A → B → C`, `B(A(基础))` cannot become the new base and then continue to run A. Applying a single item saves the original node's outputs before and after, its capability contract, the inputs it actually read, and its algorithm writes. The original algorithm no longer runs. The configuration, position, and enabled state of a baked node are no longer generation controls. The author edits its persistent output.

Apply includes a full-result check. Comparing only the current picture is not enough. A mismatched correction or a failed dependency on an enabled item blocks apply. A fixed-override conflict keeps the current author value and says so. An author record on a disabled item stays dormant. When a prefix continues to be applied, a disabled motion and a disabled physics group can be enabled again.

Plugin identity stays in the history archive. A baked node does not need to look up the original plugin package at runtime. If an old prefix capability record lacks a `provides` contract, the compatibility query is kept. When an input contract is incompatible, an upstream change is rejected and the user is told to handle the prerequisite dependency. A stale output cannot stand in for baked content.

## Upstream and downstream dependencies, and content edits

The plan checks cross-boundary dependencies of configured capabilities, explicit parameter providers, manual structure references, and motion inputs. An author correction also looks up the actual earlier contributors from the key parameters and the target objects. A baked node stores the input identity of objects that were replaced or removed, and continues to show the corresponding dependency. A shared parent, parameter, track, or simulation input must be included in the scope decision. When a simulation input's identity changes, it has to be baked again.

When a known dependency can be satisfied by the baked contract, the downstream is kept. When the full contract cannot be kept, the apply scope should be widened, or the operation should be rejected with a reason. The current scopes are a single item and a prefix. There is no automatic selection of the full minimum dependency closure yet. An implicit read that a plugin did not declare cannot be treated as safe from the plan alone. The final rebuild check always remains.

An author's record of a motion is independent of the frozen configuration. A built-in motion keeps its original record. Other plugins build an author record from the Clips they actually added or changed. One node can hold several motions. The original Clip is stored on its own. Disabling it does not drop its curves, fades, events, or non-parameter tracks. An edit replaces only the fields the author changed. Directed regression of the general record, prefix apply, and the save-to-disk path has passed. Rename, curve edit, enable and disable, and delete update the persistent Clip. Copying a baked motion keeps the copy at the original stage. After a full prefix apply it becomes ordinary content. Copy keeps the original channels. An unmodified curve keeps its original representation. A curve can refer only to parameters that already exist at the original node's position. A disabled record checks this constraint too. Continuing to apply a prefix uses the edited record and the original tracks. Renaming a parameter or a part of an ordinary motion updates the original references in sync. Deleting a record removes the original data with it. The workspace clips node uses the new schema. The model-object binary uses version 7 to store events, non-parameter tracks, and per-track fades, and it still reads versions 4–6. Objects and whole-package auxiliary resources that do not contain these new fields continue to be written as version 6, so an existing worker can still read them. The host reads whole-package resources of versions 6 and 7. It cannot drop the new fields by downgrading. The host-managed SDK refreshes the actual serialization module before the build. A specified external SDK is not overwritten automatically.

A baked physics group joins ordinary parameter tuning, enable and disable, fitting, delete, and import. An import of the same ID modifies the baked group. When a new group occupies the same output, the conflicting group is disabled by the import rule. The built-in swing physics algorithm reads other groups only for the conflict check. After baking, it re-checks ID and output conflicts. It does not lock the whole physics list or the FPS. Other plugins keep their original read contract.

When an ordinary candidate, a settings change, or a batch command produces a new modifier execution failure, the partial result is rejected. An existing failure that did not change does not block an unrelated edit. Preset initialization can still record its explicit isolated failure. A downstream dependency that is disabled because its provider was disabled on purpose stays recoverable. It is not treated as an algorithm execution error. Deleting or disabling the structure provider of baked content, or deleting an input parameter of a baked motion, validates the downstream output contract. If it is incompatible, the whole transaction is rejected. The model and the history stay as they were.

## Implementation and acceptance status

The main entry points are `GeometryEditStages`, `GeneratedOverrides`, `RotationCorrections`, `JournalRecording`, `RigModifierEvaluator`, `AppliedModifierNode`, `ModifierApplyPlan`, and `WorkspaceDocumentCommands`. The canvas passes the stage and the merge mode through the shared commit path.

Directed regression evidence that has already passed:

- Incremental replay does not accumulate; fixed overrides; topology mismatch; insertion of an in-between key value; common key values; and actual-write ownership of existing keyforms.
- Direct geometry, strokes, Bezier, and rotation corrections. The stage source and the picked target share the same key axes.
- A vertex drag that starts at an in-between parameter value actually moves, and the parameters stay unchanged. Cancel, or moving back to the start and releasing, restores the original geometry and releases the lock.
- Applying a single item keeps the order of non-commutative operations. A final-stage correction can be baked in place. Replay still works when the original plugin is missing.
- Editing of baked motions and physics; recovery after disabled content continues to be applied; physics import replacement and conflict disable.
- When an ordinary edit meets a new modifier execution failure, no candidate model and no history are published.
- History round trip, workspace save and reread, CMO3 serialization read-back, and comparison of endpoint and in-between poses.

The final full regression of the main module and the format module has passed (command `./gradlew.bat :format-compile:test :test --console=plain`; log `build/modifier-authoring-final-tests-3.log`, 2 minutes 18 seconds). The main module reports 1228 items: 1087 passed, 141 skipped, 0 failed. The format module has 5 passed. Coverage includes old project revision identity, old-format reads, the plugin development loop, saving of original motion data, atomic rejection of stale compensation, and interaction of paths, Bezier handles, and bone pose. Skipped items are not counted as passed. These checks use synthetic data. They do not mean the original operation recording of issue #81 has been reproduced.

Acceptance of the unified edit entry covers pose hold and cancel for vertices, the transform box, a temporary Warp, paths, and Bezier handles. A real workspace vertex drag covers asynchronous commit, undo and redo, and unlock after commit. The bone-pose check confirms that parameters change as the drag moves, and that it does not take a geometry pose lock.

Keeping a child's appearance recomputes the direct child's compensation under the parent's correction. When the child is not yet bound to the current parameter, the required keyforms are prepared. An in-between value of an independent parameter is subdivided adaptively by the actual deformation error, at most 8 rounds and at most 16384 sample poses per round. If it cannot converge, the whole transaction is rejected. 31 independent parameter sample values, regeneration, mismatch, and baking have been verified. That sampling check does not prove a continuous error bound on an arbitrarily complex hierarchy.

Supplementary acceptance of dependencies and complex hierarchies has passed (`modifier-authoring-tests-35.log`): deleting or disabling the provider of baked content, or deleting an input parameter of a baked motion, rejects the whole edit and does not change history. Applying a full prefix can keep the result and release the generation dependency. Translation keeps appearance for a shared parent, nested rotations, and blend deformations. A nonlinear combination that cannot represent the same deep mesh position is rejected explicitly. The check covers child key values, blend-deformation key values, and limit points and their midpoints. Beyond the limit there is no silent approximation.

Delivery-scope audit:

| Original requirement | Implementation and verification |
| --- | --- |
| Manual edits and presets exist together, with fewer conflicts | A unified edit pose and stage, common key values, delta / fixed overrides, write ownership, and fingerprint mismatch. Covered by `ParameterKeyPoseTest`, `ModifierGeometryCorrectionTest`, `RotationCorrectionsTest`, and workspace recompute regression |
| Aimed at deformation / editing itself, not only at fixing the brush | Vertices, the transform box, a temporary Warp, paths, and Bezier handles share the pose and the commit path. Covered by `WorkspaceModifierCommandsTest` and `ModifierGeometryGestureTest` connected to a real backend. Bone pose keeps the behavior of changing parameters |
| Apply when needed, and take parent and child dependencies into account | In-place bake of a single item, equivalent materialization of a prefix, dependency-scope planning, input contracts, and a full-result check. Covered by `AppliedModifierNodeTest`, `ModifierApplyPlanTest`, and workspace regression of deleting / disabling providers, parameters, and history |
| Still editable and reusable after save | Author records of motions / physics, original channels, old project identity, replay when a plugin is missing, undo and redo, save and reopen, and export regression |

After merge to the main branch the full regression passed again (`build/modifier-merge-tests-3.log`, 2 minutes 17 seconds): the main module has 1093 passed and 147 skipped, the format module has 5 passed, and there are zero failures. This includes a consistency check with a real Rust native plugin. Whole-package auxiliary resources and object frames share the version choice. Data that an old version can represent stays on version 6. The new motion fields use version 7.

The last motion-copy compatibility fix has passed full regression: a baked copy stays at the original stage, and an ordinary copy keeps the existing behavior of generating an export file name automatically. Both copies keep the original curves, events, and non-parameter tracks. The delivery-scope acceptance above is complete.

Automatic topology migration is not part of the current implementation. Mismatch records are kept and require explicit handling.
