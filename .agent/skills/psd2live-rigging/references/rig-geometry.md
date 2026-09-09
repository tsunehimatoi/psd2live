# Rig tool menu

Choose geometry operations for the intended shape. Use stable IDs, parent-local units and the returned history head. Reconcile uncertain writes before retrying.

| Need | Tool / minimal input |
|---|---|
| State, IDs, parameters | project_get_state, rig_list_objects, project_list_parameters |
| Clean posed image | view_render_model(parameters, viewport) |
| Parts / deformation image | same View with annotate_layer_ids / annotate_deformer_ids; point_indices only for point edits |
| Small geometry summary | rig_inspect(target, coordinate); includes actual representation, native control availability, axes, parent, counts and data cost |
| Exact points | rig_inspect(detail="points", space="local" or "canvas", offset, limit≤256); request only needed pages |
| Proposed shape diagnostics | rig_preview with the same operations, no history head needed |
| Ordered shape edits | rig_transform(target, coordinate, operations, expected_history_head_node_id) |
| Hierarchy / channels / raw keys | object_get (can be large), warp_create, keyform_set/copy/delete, rig_k_pose |
| Pixels / parts / physics | asset_prepare_reference → host image editing → asset_import_png → asset_register → asset_preview_composite → layer_add_from_asset; physics_put + output keyforms |

Choose evidence, don't request every representation automatically. Usually one clean image + a selected annotated image and a summary suffice. Use the same pose and camera for comparisons. An image is visual evidence, not exact coordinates. View IDs retain reversible canvas/pixel mappings and logged annotated images. Geometry queries do not dump other poses. Edits require at least one parameter. Read every bound geometry axis from the summary and provide it in edit `coordinate`; edits at other poses remain intact.

Native Bézier controls may be edited directly only when the model actually stores anchors/handles and the backend exposes them. This runtime currently stores sampled Warp lattices or triangle meshes; `nativeBezier.available=false` means use the selection/range operations below. Editor Bézier subdivision metadata is not native control geometry. Never fit a cage and present it as native handles.

## Operations

One `rig_transform` commits an ordered list atomically. The baseline is interpolated at the supplied pose. Each operation uses the current input's bounds; selectors use the original normalized domain (Warp row/column 0..1, mesh rest bounds). Geometry itself is in **parent-local units**, not canvas pixels. Use `rig_inspect(space="canvas")` to locate posed points; do not feed canvas positions into local edits. Parent rotation/scaling can change visible directions. Positive local Y is down; positive rotation is clockwise in local units.

- `translate`: `delta:[dx,dy]`, fractions of current width/height.
- `scale`: `factors:[sx,sy]` (>0), `pivot:[u,v]` (default center). Compress with <1; inflate with >1. A line center is a pivot at its midpoint with one factor=1.
- `rotate`: `degrees`, `pivot`. Does not add scale in parent-local units.
- `bend`: `axis:"x"|"y"`, `amount`. Cubic bow with zero endpoint displacement and peak amount at midpoint. axis x displaces X along rows (v); axis y displaces Y along columns (u).
- `curve`: `axis`, `controls:[p0,p1,p2,p3]`. Signed cubic displacement along the other axis, fractions of current dimension.
- `smooth`: `strength:0..1`, Warp only, one neighbor pass; pins boundary. Use only to correct demonstrated unevenness.

Top-level `selection` is shared by every operation; top-level `range` supplies `radius` and/or `feather`. An operation may supply a complete `selection` override (replaces shared selection and range). Optional `selection`: `indices:[...]` (≤512), `rect:[left,top,right,bottom]`, `center:[u,v]` + `radius`, or `line:[x0,y0,x1,y1]` + `radius`. Point/line radius uses smooth cubic falloff; rectangles optionally use `feather:0..0.5`. Combine selectors by intersection. A zero-weight point is unchanged. Selection coordinates are normalized rest coordinates, **not screenshot pixels**. Point indices match the View and point pages.

Example local eye-socket edit without downloading points:
`selection:{"center":[0,0.4]}, range:{"radius":0.2}, operations:[{"type":"translate","delta":[0.02,0]}]`

Human preview: enable the deformer information layer in the Preview tab, then choose names, point indices, or selected-only. Overlay and artwork share the displayed pose and camera. MCP annotation requests independently choose IDs and are saved to Agent Log with the View.

## Attachment-based motion

`sway`: `root:[u,v]`, `tip:[u,v]`, `degrees`, optional `softness` (0..8, default 1), `root_pin` (0..0.95, default 0). Root/tip use normalized bounds of the operation input in parent-local units. Points behind the root/pinned region stay fixed; bending increases toward the tip. Positive degrees rotate clockwise. Softness 0 gives a rigid pivot outside the pinned region; larger values concentrate bending toward the tip. Use explicit anchors from the actual lock, not the whole parent frame. Selection can isolate the affected region.

Example at a sway parameter endpoint:
`operations:[{"type":"sway","root":[0.5,0.1],"tip":[0.6,0.95],"degrees":12,"softness":1.5,"root_pin":0.1}]`

Inspect neutral, target and relevant neighboring poses. Foldovers or drift are diagnosable geometry defects; visual coherence still needs a composed image. Optional face/hair topics explain ownership and coverage. Keep before/after evidence only when it helps assess the result.

## Reference landmarks

`landmarks`: matching `from:[[u,v],...]` and `to:[[u,v],...]` (1..64 pairs) in normalized current input bounds. The server interpolates displacement with inverse squared distance in parent-local units. An identical source/target pair pins that location; selection limits the edited region. Exact source-point matches use the requested displacement. Correspondences are supplied by the caller; this does not detect image features, solve occlusion or guarantee a fold-free mesh. Use preview diagnostics and posed composition for evidence.
