---
name: hair-separation
description: >-
  Separate and complete overlapping hair pieces in PSD2Live using visual evidence,
  native image editing, reversible history, and independently riggable topology.
  Use for bangs, side hair, back hair, ahoge, ponytails, twintails, hair variants,
  overlap removal, hidden-root completion, or any request to split painted hair.
---

# Hair Separation

Produce editable pieces through a non-destructive workflow. They must follow natural strand boundaries, remain complete behind neighbouring layers, keep stable roots, and accept independent meshes, deformers, parameters, and physics. Source preservation and occlusion completion are part of the result.

## Mandatory image-generator gate

- Read `psd2live-rigging` first. Hair separation, difference/variant creation, and occlusion completion are pixel-producing tasks, not geometry-only operations.
- Before writing or running image-processing code, call a real host image generator/editor for every piece that needs a new boundary, inferred pixels, or completed hidden structure. Use **Nano Banana Pro** (`Nano Banana Pro`/`NBP`) when exposed, otherwise **GPT Image 2** (`gpt-image-2`), otherwise an equivalent host-native generator.
- In Codex/ChatGPT, load `imagegen` and call `image_gen`; in Gemini/Antigravity, call Nano Banana Pro when available. PSD2Live MCP Views are reference inputs, not a substitute generator.
- Python, PIL/Pillow, OpenCV, Matplotlib, SVG, Canvas, ImageMagick, shell scripts, masks, polygons, blur, dilation, and texture cloning must not draw, invent, or reconstruct the output. They are allowed only for byte transport, diagnostics, exact unchanged-pixel extraction, and non-creative alpha cleanup after native generation.
- If a requested piece is omitted by the generator, call the generator again. Do not manufacture the missing piece procedurally. If no native generator is available, stop and report that blocker.

## Establish state and evidence

- Discover the host and MCP capabilities rather than assuming a provider or tool name. Read `project_get_state` and `project_list_layers`, wait for restoration to finish, and identify targets by stable IDs.
- For multi-step work, create a task with `task_start` and persist decisions, View IDs, asset IDs, derived layer IDs, and history nodes with `task_update`.
- Inspect each target through `view_render_layer` and one or more focused `view_render_context` calls. Use `view_render_model` only when the full composition or a pose answers a concrete question.
- Use `object_get` to determine whether the source is already split, keyed, masked, clipped, meshed, deformed, glued, or bound to physics. Identify strand direction, anatomical root, depth order, visible overlap, and missing occluded pixels.

## Reconstruct painted structure

- Use Nano Banana Pro/NBP or GPT Image 2 (`gpt-image-2`) for reference-conditioned generation, editing, or inpainting. Use another host-native generator only when neither named generator is available.
- Resolve the host's Nano Banana Pro/NBP, GPT Image 2, or `image_gen` entry. Provide isolated and context Views as references, preserve the source style, and request transparent PNG output when supported.
- Fully continue an occluded strand's curvature, volume, taper, texture, highlights, and root underneath the foreground piece. Keep the foreground contour self-contained and paint only intentional contact shading.
- Never fake hidden structure with mask dilation, neighbouring-pixel smearing, repeated texture, or procedural vector/polygon drawing. Each missing or rejected piece returns to the named generator. If suitable image editing is unavailable, report the missing capability and stop at the last reversible state.

## Import without spatial drift

- Preserve the originating View's `spatialReferenceId`. Output resolution may differ, but its pixel-to-canvas mapping, position, size, and aspect ratio must remain explicit.
- Prefer transparent output. If background removal is necessary, clean the alpha edge, defringe it, and avoid baked gray or black halos.
- Import each piece through `asset_import_png`. Use `source_pixel_rect` only for a declared crop of the View, then call `layer_add_from_asset` with the current `expected_history_head_node_id`.
- Keep each asset tightly cropped enough for dense contour-following mesh generation. Verify placement and topology with a context View and `object_get`.
- Preserve the source layer. Soft-delete it with `layer_soft_delete` only in a separate recoverable commit after all replacements are visibly correct.

## Protect history and recover calls

- Refresh `historyHeadNodeId` before each mutation and use the returned history node as the next expected head.
- On a stale-head error, refresh state/history and reconcile concurrent changes before issuing a new request.
- Never blindly retry a mutation after a timeout or disconnect. Reconnect, inspect `project_get_state`, `history_list`, the task log, and affected objects to determine whether it committed. Only retry when evidence shows the intended change is absent. Read-only inspection/render calls may use a small bounded retry.
- Never remesh a keyed object unless all keyforms, masks, glue, deformers, and bindings can be transferred and verified.

## Verify

- Compare the neutral composite before and after separation, then inspect every piece in isolation with foreground neighbours hidden.
- Confirm hidden roots and crossing regions are complete, organic, and free of cut edges, repeated texture, gaps, alpha fringe, or unintended shadows.
- Inspect mesh flow and density. Test exaggerated sway and head-angle poses with `view_render_model`; verify no holes or tearing appear and every child remains inside its parent deformer over the full range.
- Complete only when validation Views and every committed history node are recorded in the task log.
