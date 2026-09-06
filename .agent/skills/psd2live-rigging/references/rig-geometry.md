# Rig tool menu

Complete the user's task: understand intent and ownership → inspect chosen evidence → author → compare neutral and intended poses → record results/save. Tools are one stage, not the objective. Use stable IDs, current history HEAD, and task records for multi-step recovery. Inspect after an uncertain mutation response; never blindly replay it.

| Need | Tool / minimal input |
|---|---|
| State, IDs, parameters | project_get_state, rig_list_objects, project_list_parameters |
| Clean posed image | view_render_model(parameters, viewport) |
| Parts / deformation image | same View with annotate_layer_ids / annotate_deformer_ids; point_indices only for point edits |
| Small geometry summary | rig_inspect(target, coordinate); includes actual representation, native control availability, axes, parent, counts and data cost |
| Exact points | rig_inspect(detail="points", space="local" or "canvas", offset, limit≤256); request only needed pages |
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
- `bend`: `axis:"x"|"y"`, `amount`. Cubic bow with zero endpoint displacement and peak amount at midpoint. X bows along Y, Y bows along X.
- `curve`: `axis`, `controls:[p0,p1,p2,p3]`. Signed cubic displacement along the other axis, fractions of current dimension.
- `smooth`: `strength:0..1`, Warp only, one neighbor pass; pins boundary. Use only to correct demonstrated unevenness.

Top-level `selection` is shared by every operation; top-level `range` supplies `radius` and/or `feather`. An operation may supply a complete `selection` override (replaces shared selection and range). Optional `selection`: `indices:[...]` (≤512), `rect:[left,top,right,bottom]`, `center:[u,v]` + `radius`, or `line:[x0,y0,x1,y1]` + `radius`. Point/line radius uses smooth cubic falloff; rectangles optionally use `feather:0..0.5`. Combine selectors by intersection. A zero-weight point is unchanged. Selection coordinates are normalized rest coordinates, **not screenshot pixels**. Point indices match the View and point pages.

Example local eye-socket edit without downloading points:
`selection:{"center":[0,0.4]}, range:{"radius":0.2}, operations:[{"type":"translate","delta":[0.02,0]}]`

Human preview: enable the deformer information layer in the Preview tab, then choose names, point indices, or selected-only. Overlay and artwork share the displayed pose and camera. MCP annotation requests independently choose IDs and are saved to Agent Log with the View.

## Recipes from face authoring

- Ownership: face surface parent → facial-feature displacement child → eyes/brows/mouth. A separate skin-only child edits silhouette without distorting eyes. Create a child only when that ownership is appropriate.
- Side look: scale X≈0.85 → X bow → small translation. Negative X looks left. Reverse signs for a requested mirror, retaining scale.
- Diagonal: add Y bow to X bow; rotate slightly (about 3°), upper-left/lower-right clockwise, other diagonals counterclockwise. Operation order matters.
- Up look: compress Y toward bottom, extra upper-half selection. Down look: lower-half selection compressed toward middle. Keep a smooth transition, inspect middle rows.
- Eye socket: skin child, small positive X translation weighted around left eye-height edge. Tune radius/depth from actual silhouette rather than a universal number.

Example ordered edit at a bound two-axis pose:
`operations:[{"type":"scale","factors":[0.85,1]},{"type":"bend","axis":"x","amount":-0.04},{"type":"translate","delta":[-0.02,0]}]`

Render the result and adjacent/neutral poses. Check silhouette, attachment, masking, foldovers, and intended range; revise based on evidence. Keep successful history node IDs and before/after View IDs in task_update. Finish with the task outcome and any actual validation limitations. Pixel synthesis still uses the host native image tool; geometry tools do not paint or reconstruct assets. See the hair-separation prompt when that workflow is needed.
