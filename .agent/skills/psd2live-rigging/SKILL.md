---
name: psd2live-rigging
description: >-
  Complete reference and operational runbook for Live2D character rigging, parameter
  authoring, object keyform editing, asset generation, and non-destructive history
  using the PSD2Live MCP server. Use this skill whenever inspecting model layers,
  rendering model/context/layer views, creating or updating Cubism parameters, setting
  keyform geometry/channels, or checking out history.
---

# PSD2Live Autonomous Rigging Skill

This skill guides the Agent in using the PSD2Live MCP server to autonomously inspect, pose, modify, and export Live2D models without destructive edits.

## Core Rules & Architecture

1. **Non-Destructive Workflows**:
   - The workspace history is strictly append-only.
   - Every mutation requires `expected_history_head_node_id` (obtained from `project_get_state` or `history_list`) for optimistic concurrency control.
   - If a mutation fails due to a stale head, refresh with `history_list` or `project_get_state` and retry.
   - Never attempt to overwrite prior nodes; use `history_checkout` to branch from an earlier state.

2. **Direct Visual Grounding**:
   - Never guess or extrapolate pixel positions from application screenshots.
   - Use `view_render_layer` to inspect isolated RGBA layers.
   - Use `view_render_context` to inspect local layer surroundings at customized scales.
   - Use `view_render_model` to inspect the full or focused model under arbitrary parameter poses.
   - Every rendered view returns reversible `spatial` metadata (`pixelToCanvas` / `canvasToPixel`).

3. **Asset Staging & Injection**:
   - When generating replacement or separated art pieces (e.g. hair strands, expressions, clothing):
     1. Pass the generated PNG bytes (base64) along with `spatial_reference_id` to `asset_import_png`.
     2. Add it into the authoritative source model via `layer_add_from_asset`.
     3. The pipeline automatically derives the mesh, bindings, and deformer hierarchies.
     4. Soft-delete redundant original layers using `layer_soft_delete` (fully recoverable).

4. **Cubism Parameter Authoring**:
   - `project_list_parameters`: lists all parameters with min, max, default, current values, and types.
   - `parameter_create`: creates a real Cubism parameter surviving rebuilds, history branches, and CMO3/MOC3 export.
   - `parameter_update`: updates parameter name, min, max, default, kind, or repeat flag.
   - `parameter_delete`: deletes a parameter and safely collapses every deformer, drawable, and glue keyform axis at its default value.

5. **Object-Level Keyform & K-Rig Editing**:
   - `object_get`: inspects an ArtMesh, Warp Deformer, Rotation Deformer, Part, or Glue object. Returns topology, geometry keyform grid, and channel tracks (opacity, draw order, multiply/screen color, glue intensity, flip X/Y).
   - `keyform_set`: sets or updates keyform geometry (warp control points, rotation pivot/angle/scale, mesh deltas) and/or visual channels at an exact N-D parameter coordinate.
   - `keyform_copy`: clones keyform geometry and channels from a source coordinate to a destination coordinate.
   - `keyform_delete`: removes a key value or collapses an entire parameter axis from geometry or specific channels.
   - `rig_k_pose`: captures the current or specified parameter pose deformation directly onto a target.

6. **Task Checkpoints**:
   - For multi-step procedures, initiate with `task_start` and record progress with `task_update`.
   - Record created asset IDs, view IDs, and history node IDs in task events.
