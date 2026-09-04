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

1. Prefer native transparent output. When the image service returns a solid background, remove it, defringe translucent edges, and alpha-bleed edge colors before import.
2. Avoid baked soft gray/black halos on primary drawables; use clean cel-shaded contact lines or a separate multiply layer.
3. Keep crops tight enough for contour-following mesh density. Declare `source_pixel_rect` only when the output is a crop of the referenced View; never silently stretch an aspect-ratio mismatch.
4. Call `asset_import_png` with the PNG, `spatial_reference_id`, and optional declared crop. Add it with `layer_add_from_asset` using the current expected history head.
5. Verify placement visually and inspect generated topology with `object_get`. Remove redundant source layers only with recoverable `layer_soft_delete` after the replacement is validated.

## Author and verify the rig

- Use `project_list_parameters`, `parameter_create`, `parameter_update`, and `parameter_delete` for real Cubism parameters.
- Use `object_get`, `keyform_set`, `keyform_copy`, `keyform_delete`, and `rig_k_pose` for geometry and visual channels at exact N-dimensional coordinates.
- Render neutral and extreme poses after edits. Check silhouette, overlap, mesh density, parent containment, tearing, halos, and parameter limits.
- Finish only when final Views and validation results are recorded and every successful mutation has a known history node.
