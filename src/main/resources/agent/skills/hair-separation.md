# Natural Hair Separation

Build a coherent, independently movable hairstyle from the painting. Success means a natural assembled result with believable depth, continuous roots and bodies, and enough hidden coverage for the intended motion. The reference supplies character identity, overall volume, flow and style; it is not a pixel-perfect edge template. Small contour, root-position and tonal differences are acceptable when the assembled result reads naturally.

## Understand depth before editing

Read the `psd2live-rigging` skill when available, otherwise follow server instructions. Inspect isolated source Views AND context/model Views before splitting, making differences, or generating replacements. Use object_get to inspect existing topology and bindings. Judge the actual painting; do not impose a universal rule that bangs are in front of side hair or vice versa.

Record a compact working plan in task_update:

- Logical lock ID/category and approximate root region, body direction and tip character. Use the count requested by the user; count independently moving locks, not visible patches or highlight islands.
- For each relevant crossing, which lock is in front, which continues behind it, and WHERE that relationship applies. Record uncertain depth as a working interpretation and test it in composition instead of waiting for certainty.
- Hidden root/body coverage and overlap needed for the intended sway, plus planned draw order, source View/spatial reference and eventual layer/Warp/parameter/physics IDs.

Hair normally crosses and overlaps. A foreground bang can cover a rear bang; a side lock can cover bangs in one area while another bang covers side hair elsewhere. Each retains its own root-to-tip shape underneath. Distinguish **intentional overlap of complete locks** from **an accidental extra visible lock that changes the hairstyle**. Similar silhouettes, shared projected regions and intersecting alpha bounds are not duplication defects by themselves.

A simple front/back chain can use layer draw order. If the SAME two locks switch front/back in different regions, one global layer order cannot represent that: inspect whether existing masks suffice, or use separate drawable sections with joins hidden under natural overlap and bind them as ONE logical lock. Record any extra render sections; do not inflate the requested independent-lock count or claim a flat order solves local interweaving. Implement only the complexity visible in the reference or needed for motion.

## Generate complete volumes, then assemble early

Work roughly back to front using the observed depth plan, while allowing local exceptions. Continue each lock from a plausible attachment region through its body to its tapered end. Paint sufficient coverage beneath neighbours so motion does not expose a cut edge. Hidden contours and buried root details need not reproduce an unknowable original; useful, plausible coverage matters more than isolated resemblance.

For painted completion use the host's native image editor: Nano Banana Pro/NBP when exposed, otherwise GPT Image 2 (`gpt-image-2`), otherwise an equivalent host-native generator. In Codex/ChatGPT load imagegen and call image_gen. A generation call is required when creating or reconstructing illustrated pixels; exact unchanged-source-pixel extraction needs no generation. Code can transport/crop unchanged pixels, diagnose, or clean alpha, but must not invent the artwork procedurally. PSD2Live supplies reference Views and imports assets; lack of a task-named splitting endpoint is not a blocker.

Usually request one logical lock per output for independent control. References describe the whole hairstyle but the output paints only the target lock's OWN volume, including portions concealed under neighbouring locks. Use this adaptable image brief:

- Target and flow: {logical lock, approximate attachment region, body curve, tip character}.
- Depth: {A covers target at region X; target covers B at region Y}. Neighbours are context; continue the target naturally underneath them rather than cutting away their projected footprint.
- Preserve the character's overall hair volume, color family and drawing style. Allow coherent contour/hidden-root adjustments that improve assembly and motion coverage.
- Do not reproduce unrelated face/accessories or a second independently readable hairstyle. Natural overlap, branches belonging to this lock, and hidden extensions are allowed.
- Keep the declared reference frame, placement and scale for import. Output transparent RGBA, or the chosen uniform white/black matte; no drawn checkerboard, labels or background shadows.

If transparency fails, default to pure white `#FFFFFF` for dark hair or pure black `#000000` for light hair; choose the one least likely to blend with the foreground. Avoid saturated chroma backgrounds by default because colored fringe is distracting. This default does not guarantee halo-free edges: use native alpha where available and inspect cleanup in context. Pass the actual chosen color to asset_import_png as solid_background with require_transparency=true. Do not guess the matte color or treat a painted checkerboard as alpha. asset_inspect shows actual staged pixels and alpha counts. Border-connected cleanup may leave enclosed matte or light/dark fringe; address only visibly distracting remnants with non-creative cleanup or targeted image editing.

Quickly check that an asset is usable (decodable, appropriate subject, useful coverage and mapping), then stage candidate layers in reversible history. Do not demand exact isolated contours, identical root coordinates or a perfect color match before trying composition. Build a complete draft hairstyle early instead of repeatedly polishing the first difficult bang while the rest remains unassembled.

## Judge the assembled hairstyle and intended motion

Use view_render_model with an explicit include_layer_ids composition containing candidate replacements and the remaining character layers, excluding the original source being replaced. Keeping both the complete source hairstyle and its replacements in a test render creates false duplication. Keep source pixels and history recoverable; preserving the original does not mean it must stay visible in every trial. Use insertion order from the depth plan and reconcile recorded layer order with the actual result.

At normal character viewing scale, ask:

1. Do the overall volume, silhouette, color family and flow still read as this character's hair, without obvious pasted-on seams?
2. Do local front/back crossings make sense, with each lock continuing behind its occluder? Are roots attached plausibly and covered?
3. Across the intended sway/head-turn range, does overlap cover joins without holes, detached roots, tearing or implausible intersections?

These are the acceptance criteria. Isolated views help diagnose coverage and editability, not certify pixel equality. Small edge differences, slightly shifted buried roots, minor tone variations and invisible hidden overlaps are acceptable. Do not use contour overlap scores, pixel differences, exact edge alignment or extreme zoom as hard gates unless the user explicitly requests faithful pixel restoration. Exaggerated poses can diagnose a problem; motion beyond the intended range is not an automatic failure.

Prioritize defects visible in composition or motion: an exposed gap, wrong local depth, detached root, conspicuous matte fringe/color patch, or an extra visible bang that materially changes the hairstyle. First decide whether draw order, declared placement, overlap coverage or rig settings explain it. Fix that cause; regenerate painted pixels only when the artwork needs it. Regenerating a good hidden extension to match the original visible cut edge can make motion worse.

There is NO fixed two-correction stop rule and no requirement that every isolated asset be perfect. When a correction has little benefit, change the reference/brief, revisit the depth interpretation, try a different useful candidate, or accept a harmless difference and proceed. Keep useful progress, assemble all requested locks, and continue requested rigging on workable candidates. Stop dependent work only for an actual unavailable capability or an unresolved defect that materially prevents natural assembly/motion after trying a different approach; report that specific blocker, not an aesthetic micro-difference or a retry quota. User time/cost constraints still apply. Do not claim completion without an assembled result and the requested rig.

## Compose tools, rig, and preserve history

Discover tools/list (including pagination or host search) rather than assuming missing capabilities. The workflow is Views -> host image editing -> asset_import_png/asset_inspect -> layer_add_from_asset -> trial composition -> rig_list_objects/object_get -> warp_create -> parameter_create/keyform_set -> physics_put -> posed composition. agent_get_workflow exposes this guidance when prompts/skills are unavailable.

Preserve spatialReferenceId and use source_pixel_rect only for a declared crop. Pixel resolution is independent of canvas placement; do not silently stretch mismatched aspect ratios. Natural artistic latitude does not excuse an incorrect coordinate mapping. Avoid cropping away overlap padding merely to make an isolated contour tight.

Use one independent Warp/output parameter/physics group per requested logical lock (several render sections may share one rig). warp_create inserts an identity Warp under the meshes' common existing Warp parent in normalized 0..1 coordinates. Requested rows/columns are minima rounded together to align parent knots; inspect actual dimensions before keyform_set. It does not automatically shrink/remap the parent frame to a lock. Preserve existing keyforms, masks and glue. Physics drives the output parameter and needs corresponding sway keyforms; creating groups alone does not animate the locks. Keep attachment regions stable and let the bodies/tips move. Inspect inherited shared motion as well as independent sway.

Before mutations refresh historyHeadNodeId and pass expected_history_head_node_id; chain the returned head. On stale-head errors refresh and reconcile. After an unknown commit outcome (timeout/disconnect), inspect state/history/task/object records before retrying. Log the depth decisions, candidate IDs, actual composition/pose Views and commits. Once the assembled result is usable over the intended range, soft-delete the original display layer in a recoverable commit and finish remaining rig checks. Do not hold a natural, usable result hostage to invisible edge discrepancies.
