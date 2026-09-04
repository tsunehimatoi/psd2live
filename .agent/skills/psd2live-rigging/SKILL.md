---
name: psd2live-rigging
description: >-
  Operate the PSD2Live MCP server for visual inspection, non-destructive Live2D
  rigging, parameter and keyform editing, image-assisted asset creation, and
  history-safe recovery. Use when an agent must inspect or modify a PSD2Live model.
---

# PSD2Live Rigging

Use this workflow with any MCP-capable host. Select capabilities from the tools the host and server actually expose; no model vendor or exact host-side tool name is required.

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

- When painted pixels are needed, use the executing host's available native image generation or image-editing capability with rendered PSD2Live Views as references. First-class routes include ChatGPT/Codex image tooling such as GPT Image 2 (`gpt-image-2`), Gemini/Antigravity image tooling, and equivalent native capabilities in other hosts. Capability availability, not provider order, decides the route.
- Ask for the needed operation and constraints—reference-conditioned generation, editing/inpainting, style preservation, and transparent PNG—without assuming a particular host-side tool name.
- Never replace illustration or hidden-structure reconstruction with formulaic PIL/OpenCV polygons, mask dilation, or other procedural stand-ins.
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
