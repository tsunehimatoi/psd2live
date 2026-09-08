package org.umamo.render.puppet

import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PuppetModel

/**
 * What a backend must do to one drawable to reconcile its residency with an edited model.
 *
 * The decision, not the doing: which tier a drawable falls into is backend-neutral, while freeing GPU
 * objects and re-uploading buffers is the backend's.
 */
internal sealed interface DrawableAction {
	/** The drawable this action concerns. */
	val drawableId: DrawableId

	/**
	 * The backend has never seen this drawable - an Object-mode duplicate, or one skipped at load for
	 * having no geometry. Upload it whole. Never part of the load-time glue layout, so it welds nothing.
	 */
	class Upload(val drawable: Drawable) : DrawableAction {
		override val drawableId: DrawableId get() = drawable.id
	}

	/**
	 * The topology changed (vertex count, indices, the keyform grid, or the blend-shape bindings), or
	 * the drawable was reparented across the warp boundary (gaining or losing a control-point texture),
	 * so every buffer and the delta texture are stale. Free the resident and upload it whole against
	 * the new mesh.
	 */
	class Reupload(val drawable: Drawable) : DrawableAction {
		override val drawableId: DrawableId get() = drawable.id
	}

	/**
	 * The residency is reusable. Non-null [positions] / [uvs] are in-place re-uploads for the tiers that
	 * changed; both null means no BUFFER work - but the resident's static composite state (blend/alpha
	 * mode, culling, mask ids, invert) is still re-stamped from [drawable], since a composite-only edit
	 * changes no mesh array and so lands here, not in Reupload.
	 */
	class Keep(
		val drawable: Drawable,
		val positions: FloatArray?,
		val uvs: FloatArray?,
	) : DrawableAction {
		override val drawableId: DrawableId get() = drawable.id
	}
}

/**
 * A reconcile plan: what to do with each drawable, and which residents to free.
 *
 * @property List actions Per-drawable actions in [PuppetModel.drawables] order, so applying them in
 *   sequence rebuilds residency in that same order.
 * @property List removed Resident ids the edit dropped entirely; free them after applying [actions].
 */
internal class ModelDiff(
	val actions: List<DrawableAction>,
	val removed: List<DrawableId>,
)

/**
 * Decides how to reconcile a backend's resident drawables with an edited model - four tiers, cheapest
 * first.
 *
 * A layer reorder or a reparent that does not change control-point ownership needs no buffer work at all
 * (every drawable is a no-op [Keep]); a base-mesh move re-uploads positions; a UV edit re-uploads UVs; and
 * a structural change - a drawable added, removed, remeshed, its blend-shape bindings changed, or
 * reparented across the warp boundary (which gains or loses its control-point texture) - frees and
 * re-uploads whole.
 *
 * The diff is keyed by drawable id (so it is robust to a simultaneous reorder) and by ARRAY REFERENCE
 * identity: the edit path is copy-on-write, so an untouched drawable keeps its exact array instances and
 * is skipped by an `!==` test without comparing any element.
 *
 * @param PuppetModel model                   The model the residency currently reflects.
 * @param PuppetModel newModel                The edited model.
 * @param Map         residentVertexCountById Each resident drawable's uploaded vertex count.  An INPUT
 *   rather than something derived from [model], and deliberately so: a drawable whose mesh went null is
 *   kept resident, after which [model] no longer carries the count its buffers were built at - only the
 *   backend still knows it.  Its keys are also the authoritative "what is resident" set, which is not the
 *   same as [model]'s drawables (one with no geometry is skipped at upload).
 * @return ModelDiff The reconcile plan.
 * @pre Call BEFORE reassigning the backend's current model - the diff reads [model] as the state the
 *   GPU buffers still match.
 */
internal fun diffModel(
	model: PuppetModel,
	newModel: PuppetModel,
	residentVertexCountById: Map<DrawableId, Int>,
): ModelDiff {
	val oldDrawableById = model.drawables.associateBy { it.id }
	// Warp parenting decides whether a drawable owns a control-point texture, and that is fixed at upload:
	// a reparent that flips it (deleting a rotation deformer between a drawable and a warp, say) leaves a
	// resident whose cp texture is stale or absent, so it must re-upload, not Keep. Derived purely from the
	// two models - residency === `model`, so the old warp set matches what the backend actually uploaded.
	val oldWarpIds = model.deformers.filterIsInstance<Deformer.Warp>().mapTo(HashSet()) { it.id }
	val newWarpIds = newModel.deformers.filterIsInstance<Deformer.Warp>().mapTo(HashSet()) { it.id }
	val actions = ArrayList<DrawableAction>(newModel.drawables.size)
	for (drawable in newModel.drawables) {
		val residentVertexCount = residentVertexCountById[drawable.id]
		if (residentVertexCount == null) {
			actions.add(DrawableAction.Upload(drawable))
			continue
		}
		val newMesh = drawable.mesh
		val oldDrawable = oldDrawableById[drawable.id]
		val oldMesh = oldDrawable?.mesh
		val warpParentingFlipped =
			(oldDrawable != null && oldDrawable.parentDeformerId in oldWarpIds) != (drawable.parentDeformerId in newWarpIds)
		val remeshed =
			warpParentingFlipped ||
				(
					newMesh != null &&
						(
							newMesh.positions.size != residentVertexCount * 2 ||
								(oldMesh != null && newMesh.indices !== oldMesh.indices) ||
								drawable.geometryGrid !== oldDrawable?.geometryGrid ||
								// Blend-shape deltas are baked into the delta texture's appended
								// columns, so a binding change invalidates the texture layout.
								drawable.blendShapes !== oldDrawable?.blendShapes
						)
				)
		if (remeshed) {
			actions.add(DrawableAction.Reupload(drawable))
			continue
		}
		if (newMesh == null || oldMesh == null) {
			actions.add(DrawableAction.Keep(drawable, positions = null, uvs = null))
			continue
		}
		val positions = newMesh.positions.takeIf { it !== oldMesh.positions }
		// The length guard is defensive: the copy-on-write UV edit never changes the array length, so a
		// mismatch can only mean a mesh the backend padded at upload - leave that resident's UVs alone.
		val uvs = newMesh.uvs.takeIf { it !== oldMesh.uvs && it.size == residentVertexCount * 2 }
		actions.add(DrawableAction.Keep(drawable, positions, uvs))
	}
	// Anything resident with no action is gone from the model. A Reupload is an ACTION, so a drawable that
	// is freed and then fails to re-upload cannot also appear here - which is what stops the free happening
	// twice, and with it the chance of the second free hitting a GL name the driver has since recycled.
	val actioned = actions.mapTo(HashSet(actions.size)) { it.drawableId }
	val removed = residentVertexCountById.keys.filter { it !in actioned }
	return ModelDiff(actions, removed)
}