# Hair Separation Skill

Use this skill when the user asks to split bangs, side hair, back hair, ponytails, or another compound hair layer into independently deformable pieces.

## Goal

Produce editable hair pieces that follow natural strand boundaries, remain visually complete behind their neighbours, have stable roots, and can receive independent mesh, Warp Deformer, parameter, and Physics bindings.
The entire workflow is non-destructive: every derived asset must retain its source and be removable as one undoable transaction.
Occlusion order and hidden-area completion are part of the deliverable, not optional visual polish.

## Required evidence before editing

- Start a long task with your own plan and update it as visual evidence changes; task checkpoints are coordination records, not approval gates.
- Read the current project and layer tree; identify targets by stable IDs rather than names alone.
- Inspect each target as an isolated transparent PNG and in one or more focused context Views. Choose an object-relative scale below 1 when surrounding hair, face, and roots are needed; request the full character only when it answers a specific question.
- Check whether the source is already split, keyed, masked, clipped, meshed, deformed, or bound to physics.
- Determine the natural strand direction, root region, depth order, visible overlap, and missing occluded pixels.

## Planning constraints

- Preserve the source layer and create derived layers. Removing the source from the working composition must be a reversible soft delete.
- Preserve every source View's spatial reference. Generated PNG resolution may differ, but adding a derived piece must map it through pixel-to-canvas coordinates so its canvas position and size do not change. Never silently stretch an aspect-ratio mismatch.
- Stage every generated piece with `asset_import_png`, using the originating `spatial_reference_id` and a `source_pixel_rect` only when the output represents a declared crop of that View. Add it with `layer_add_from_asset` and the current `expected_history_head_node_id`.
- Prefer natural painted boundaries over equal-width slices.
- Each derived piece needs overlap behind adjacent pieces and enough hidden completion for its intended motion range.
- Do not invent a preset for an unsupported shape. Keep unsupported pieces under the nearest reliable parent and mark them unknown.
- Never remesh an object with keyforms unless all keyforms, glue, masks, and bindings can be transferred and validated.
- Use tools to create pixels, masks, mesh, deformers, and physics. Do not simulate pixel or geometry edits in prose.
- In the current build, adding a PNG runs the normal mesh/rig rebuild and soft deletion is reversible. Warp editing and per-piece physics tools are not available yet; stop after the last committed valid layer state instead of claiming those steps completed.

## Verification

- Compare the neutral composite before and after separation.
- Inspect each transparent piece for cut edges, alpha fringes, holes, detached roots, and implausible hidden completion.
- Inspect mesh flow and density before creating deformers.
- Confirm every child mesh stays within its parent Warp at the full parameter range.
- Simulate combined head XY and hair physics, checking gaps, collisions, excessive stretching, jitter, and recovery.
- Complete only after every operation is recorded in history and the final views and validation report are available.
- After adding all derived pieces, soft-delete the original in a separate recoverable commit only after a context View shows that placement and overlap are correct. Report the resulting history node IDs.
- Attach every important View ID, staged asset ID, derived layer ID, and committed history node ID to the persisted task event log so work can continue across MCP reconnects and application restarts.

If the available tools cannot produce or validate a required artifact, report the missing capability and stop at the last valid reversible state.
