---
name: psd2live-rigging
description: >-
  Operate the PSD2Live MCP server for visual inspection, non-destructive Live2D
  rigging, parameter and keyform editing, image differences, part separation,
  image-assisted asset creation, and history-safe recovery. Use whenever an agent
  invokes this skill or any PSD2Live MCP capability; do not use for ordinary repository work.
---

# PSD2Live Rigging

Use this workflow with any MCP-capable host. Discover the MCP tools the host exposes; for pixel creation, follow the explicit generator routing below.

## Invocation boundary

- Once this skill or any PSD2Live MCP capability is invoked, read and apply this entire file before proceeding, regardless of the model-editing task type.
- This requirement begins at skill/MCP invocation. It does not apply to ordinary source-code, documentation, test, or configuration work that does not invoke the skill or MCP.
- When the invoked task involves hair separation, also read `hair-separation` and apply both skills.

## Route pixel-producing work before coding

- Treat every painted-image difference or expression variant, part/layer split, overlap removal, occlusion completion, reconstruction, inpaint, outpaint, or new drawable as a native image-generation/editing task. This gate applies even if the user says only “diff”, “variant”, “split”, “separate”, “complete”, or “fill the hidden part”.
- For every output piece or variant that creates, guesses, reconstructs, or changes illustrated pixels, make an actual host image-tool call. Preferred named generators are **Nano Banana Pro** (`Nano Banana Pro` or `NBP`) and **GPT Image 2** (`gpt-image-2`). Use an equivalent host-native image generator only when neither named route is exposed.
- In Codex/ChatGPT, load the `imagegen` skill and call the available `image_gen` capability. Select Nano Banana Pro/NBP when the host exposes it; otherwise use GPT Image 2. In Gemini/Antigravity, call Nano Banana Pro directly when available.
- The PSD2Live MCP server supplies reference Views and imports results; it does not expose the host-private image generator. Absence of an image-generation tool in the PSD2Live MCP tool list is not evidence that generation is unavailable. Leave the MCP tool chain, call the host image generator, then return to `asset_import_png`.
- Do not use Python, PIL/Pillow, OpenCV, Matplotlib, SVG, Canvas, ImageMagick, shell scripts, or hand-authored polygons as the renderer for any such asset, including a draft, mask-painted substitute, or fallback. Code may only transport/decode bytes, copy or crop unchanged source pixels, calculate diagnostics, and perform non-creative alpha cleanup after the native generator returns.
- The only no-generator exception is a provably exact extraction or crop in which every output color/alpha sample comes unchanged from already visible source pixels and no boundary, hidden structure, or painted detail is inferred. If uncertain, use Nano Banana Pro or GPT Image 2.
- If neither named generator nor an equivalent native image capability is available, stop at the last reversible state and report the missing capability. Do not silently fall back to procedural drawing.

## Discover and compose capabilities

Discover tools/list (including pagination or host search). Use asset_prepare_reference -> host image editing -> asset_import_png/asset_inspect -> asset_register -> asset_preview_composite -> layer_add_from_asset -> layer_set_placement if needed -> layer_finalize_placement -> optional dedicated Warp/keyforms/physics -> posed composition. agent_get_workflow exposes this guidance when skills are unavailable. References contain a clean source image and a separate labeled context; labels must never be painted into the target. Before generation choose root/tip and preferably a noncollinear side anchor in source canvas coordinates and record local depth and hidden coverage.

Always request a declared uniform RGB matte, not transparent output. Choose and record background_color in asset_prepare_reference; white/black are useful defaults, but another suitable RGB is allowed. Pass the actual color as solid_background to asset_import_png with reference_id. MCP retains the original PNG, removes the matte and decontaminates a narrow edge band. Use asset_inspect for raw/processed pixels and diagnostics; asset_reprocess accepts foreground protection and background hole points in original PNG coordinates. A checkerboard or nonuniform matte is a diagnostic failure, not successful transparency. Change matte or hints when needed; judge small residual edges in normal-size composition.

Use reference_id and registration_id for generated assets. Frame registration is appropriate only when the generator kept the declared frame; declare generated_pixel_rect/source_canvas_rect for padding or crops. When content was recentered or resized, mark matching generated_anchors in the full original PNG and use landmarks registration. Pixel resolution and alpha bounds never determine target size. Coordinates are top-left, X right, Y down. Mirrors require explicit mirror_x/mirror_y; do not flip to compensate for unexplained rig drift. Registration instances are immutable; create another registration to adjust position, scale or rotation and apply it with layer_set_placement. This recomputes from the original processed pixels.

Imported replacements immediately inherit the reference source layer’s existing parent Warp (or explicit parent_deformer_id). For example front hair 1/2/3 belong inside the existing front-hair Warp. There is no unbound-layer mode. Placement finalization marks readiness for dedicated edits; it does not create the first binding. Inherited parent motion is preserved during positioning. Once a piece has dedicated Warp/keyform/glue edits or finalized placement, whole-rig relocation is outside this version; do not erase animation. Add an independent child Warp/output parameter/physics group only when requested. Physics requires corresponding sway keyforms. Preserve shared parent motion and test the intended range.

## Connect and recover safely

- Prefer direct Streamable HTTP. Use the stdio bridge only when the host cannot connect to HTTP MCP servers.
- After initialization, discover the available MCP tools and read `project_get_state`. Wait if `persistenceStatus` is restoring. Use stable IDs from server results rather than names alone.
- The history is append-only. Before each mutation, obtain the current `historyHeadNodeId` and pass it as `expected_history_head_node_id`. After success, use the returned history node for the next mutation.
- A stale-head rejection means the workspace changed. Refresh state/history, reconcile the plan and inputs, then issue a new mutation. Do not mechanically replay the old request.
- A timeout, disconnect, or lost response leaves a mutation's commit state unknown. Reconnect and inspect `project_get_state`, `history_list`, the task record, and affected objects before deciding whether anything remains to run. Never blindly retry a mutation. Read-only discovery and render calls may be retried with a small bounded backoff.
- For multi-step work, call `task_start` and record decisions, view IDs, asset IDs, affected object IDs, and committed history node IDs with `task_update`. Tasks coordinate recovery; they are not approval gates.

## Ground every edit visually

- Use `view_render_layer` for isolated RGBA, `view_render_context` for local surroundings, and `view_render_model` for a full or focused posed composite.
- Do not infer source coordinates from application screenshots. Retain the View's `spatialReferenceId` and reversible pixel/canvas mapping.
- Inspect the target with `object_get` before changing topology or keyforms. Preserve masks, glue, deformers, bindings, and every existing keyform unless the requested operation explicitly replaces them.

## Create or edit pixels through native image capability

- When painted pixels are needed, follow the mandatory routing gate above with rendered PSD2Live Views as references. Call Nano Banana Pro/NBP when exposed, otherwise GPT Image 2 (`gpt-image-2`), otherwise an equivalent native generator.
- Resolve the actual Nano Banana Pro/NBP, GPT Image 2, or `image_gen` entry exposed by the host, then request reference-conditioned generation, editing/inpainting, style preservation, and transparent PNG output.
- Never replace illustration or hidden-structure reconstruction with formulaic PIL/OpenCV polygons, mask dilation, or other procedural stand-ins. Re-read the routing gate before any shell or Python image-processing step.
- If no usable image capability is available, report the missing capability and stop at the last valid reversible state instead of fabricating an asset.

## Stage generated assets correctly

1. Prefer native transparent output. For solid-matte generation default to pure white (#FFFFFF) or pure black (#000000), choosing contrast with the hair; avoid saturated colors by default. Import using the actual solid_background color and inspect asset_inspect. Clean distracting light/dark fringe if present; matte choice alone cannot ensure perfect alpha.
2. Preserve intentional contact shading and occlusion. Fix matte residue when it is distracting in the assembled character, rather than treating every isolated edge variation as failure.
3. Keep sufficient hidden overlap and root padding for intended motion while avoiding excessive empty canvas. Declare `source_pixel_rect` only when the output is a crop of the referenced View; never silently stretch an aspect-ratio mismatch.
4. Prepare a source reference package, import the declared matte PNG, register its actual placement, preview replacements, and add with registration_id and current history HEAD. Preserve the existing source Warp; adjust placement before dedicated rig edits.
5. Verify placement visually and inspect generated topology with `object_get`. Trial composition may exclude the original source while its pixels remain in history. Use recoverable `layer_soft_delete` once the assembled replacement is natural and usable; isolated pixel-perfect matching is not required.

## Author and verify the rig

- Use `project_list_parameters`, `parameter_create`, `parameter_update`, and `parameter_delete` for real Cubism parameters.
- Use `object_get`, `keyform_set`, `keyform_copy`, `keyform_delete`, and `rig_k_pose` for geometry and visual channels at exact N-dimensional coordinates.
- Render neutral and intended-range poses after edits. Judge coherent silhouette, local depth, attachment, overlap coverage and movement in composition. Use exaggerated poses and isolated views for diagnosis, not as automatic aesthetic failure gates. Natural inter-lock crossings and small invisible contour differences are acceptable.
- Finish only when final Views and validation results are recorded and every successful mutation has a known history node.
