---
name: hair-separation
description: >-
  Expert workflow constraints and procedure for non-destructive hair separation,
  occlusion-order completion, and independent physics rigging in PSD2Live. Use this
  skill whenever the user asks to separate, split, or rig hair layers (bangs, side hair,
  back hair, ahoge, twintails, etc.).
---

# Hair Separation Skill

Use this skill when the user asks to split bangs, side hair, back hair, ponytails, or another compound hair layer into independently deformable pieces.

## Goal

Produce editable hair pieces that follow natural strand boundaries, remain visually complete behind their neighbours, have stable roots, and can receive independent mesh, Warp Deformer, parameter, and Physics bindings.
The entire workflow is non-destructive: every derived asset must retain its source and be removable as one undoable transaction.
Occlusion order and hidden-area completion are part of the deliverable, not optional visual polish.

## Required evidence before editing

- Start a long task with your own plan using `task_start` and update it as visual evidence changes via `task_update`; task checkpoints are coordination records, not approval gates.
- Read the current project and layer tree using `project_get_state` and `project_list_layers`; identify targets by stable IDs rather than names alone.
- Inspect each target as an isolated transparent PNG using `view_render_layer` and in one or more focused context Views using `view_render_context`. Choose an object-relative scale below 1 when surrounding hair, face, and roots are needed; request the full character only when it answers a specific question.
- Check whether the source is already split, keyed, masked, clipped, meshed, deformed, or bound to physics using `object_get`.
- Determine the natural strand direction, root region, depth order, visible overlap, and missing occluded pixels.

## Planning constraints

- **Mandatory AI Image Inpainting (Multi-Agent Standard)**:
  - Whichever AI agent platform is executing this skill (Google Gemini / Antigravity with `generate_image`, OpenAI ChatGPT with DALL-E / GPT-4o Image, Claude with sidecars, or custom diffusion APIs):
  - The Agent MUST use its available multimodal AI image generation or inpainting tools conditioned on reference views (`view_render_layer` / `view_render_context` exports) to synthesize and inpaint occluded hair structures.
  - Never substitute true strand completion with naive mask dilation or superficial reuse of neighboring surface pixels ("pseudo-overlap").
- **True Crossing & Occlusion Completion (交叉穿插结构重绘)**:
  - When hair strands cross (e.g. side hair emerging from beneath center bangs), the occluded strand MUST be fully reconstructed:
    1. Continue the strand's natural 3D curvature, volume, and tapering back to its anatomical root under the overlapping piece.
    2. Inpaint the complete hair texture, highlights, and contact ambient occlusion (AO) shadow beneath the crossing point.
    3. Ensure the overlapping piece has clean, self-contained contour outlines and wrap-around back curvature.
- Preserve the source layer and create derived layers. Removing the source from the working composition must be a reversible soft delete using `layer_soft_delete`.
- Preserve every source View's spatial reference. Generated PNG resolution may differ, but adding a derived piece must map it through pixel-to-canvas coordinates so its canvas position and size do not change. Never silently stretch an aspect-ratio mismatch.
- Stage every generated piece with `asset_import_png`, using the originating `spatial_reference_id` and a `source_pixel_rect` only when the output represents a declared crop of that View. Add it with `layer_add_from_asset` and the current `expected_history_head_node_id`.
- Prefer natural painted boundaries over equal-width slices.
- Do not invent a preset for an unsupported shape. Keep unsupported pieces under the nearest reliable parent and mark them unknown.
- Never remesh an object with keyforms unless all keyforms, glue, masks, and bindings can be transferred and validated.
- Use tools to create pixels, masks, mesh, deformers, and physics. Do not simulate pixel or geometry edits in prose.
- In the current build, adding a PNG runs the normal mesh/rig rebuild and soft deletion is reversible. Keyform editing and deformers can be authored via `keyform_set`, `keyform_copy`, `keyform_delete`, and `rig_k_pose`.

## Verification

- Compare the neutral composite before and after separation.
- **Isolated & Crossing Structure Inspection**:
  - Individually inspect each transparent piece with adjacent foreground layers hidden (`include_layer_ids` or solo view). Verify that the hidden roots and crossing regions are fully painted, organic, and free of cut edges or repeated textures.
- Inspect mesh flow and density before creating deformers. Verify that accessories and hair strands have dense, contour-hugging ArtMeshes.
- Confirm every child mesh stays within its parent Warp at the full parameter range using `view_render_model`.
- **Exaggerated Motion & Collision Check**:
  - Test separated pieces under exaggerated sway poses (e.g. `ParamHairFront: ±1.0` or wide head angles) to ensure no holes, gaps, or tearing appear at the crossing points.
- Complete only after every operation is recorded in history and the final views and validation report are available.
- After adding all derived pieces, soft-delete the original in a separate recoverable commit only after a context View shows that placement and overlap are correct. Report the resulting history node IDs.
- Attach every important View ID, staged asset ID, derived layer ID, and committed history node ID to the persisted task event log so work can continue across MCP reconnects and application restarts.

If the available tools cannot produce or validate a required artifact, report the missing capability and stop at the last valid reversible state.
