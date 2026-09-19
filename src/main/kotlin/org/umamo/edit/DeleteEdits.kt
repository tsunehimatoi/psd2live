package org.umamo.edit

import org.umamo.render.eval.RotationXform
import org.umamo.render.eval.rotationXform
import org.umamo.render.eval.warpApply
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshDeltaForm
import org.umamo.runtime.model.MeshForm
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.RotationForm
import org.umamo.runtime.model.RotationPivotForm
import org.umamo.runtime.model.WarpForm
import org.umamo.runtime.model.WarpLatticeForm
import org.umamo.runtime.model.withDerivedRenderRoot

/*
 * Entity deletion over the org tree. Removing a drawable, a deformer, or a part scrubs every dangling
 * reference so no later code dereferences a deleted id. Deleting an art mesh is the only inherently
 * destructive case; deleting a deformer "unwraps" it (its sub-deformers and bound meshes re-home to its
 * parent, with rest-pose coordinates baked through the removed transform); deleting a part is either a
 * cascade (the part and its whole subtree) or an ungroup (the folder only, contents spliced up into its
 * parent). Org-tree edits re-derive the render order.
 */

/** A copy of this deformer re-nested under [newParent] in the transform hierarchy (used to re-home orphans). */
private fun Deformer.reparentedTo(newParent: DeformerId?): Deformer =
	when (this) {
		is Deformer.Warp -> copy(parent = newParent)
		is Deformer.Rotation -> copy(parent = newParent)
	}

/** A copy of this deformer re-bound to the organizational part [newPart] (or null to clear a dangling ref). */
private fun Deformer.reboundToPart(newPart: PartId?): Deformer =
	when (this) {
		is Deformer.Warp -> copy(partId = newPart)
		is Deformer.Rotation -> copy(partId = newPart)
	}

/**
 * Returns a copy of [this] with every drawable in [ids] removed and every reference to them scrubbed: the
 * drawables list, the org tree (their [OrgChild.Drawable] entries), any other drawable's clip mask, and any
 * glue whose either partner was deleted. The render order is NOT re-derived here - the caller does that once
 * it has finished its structural changes. An empty set returns the same instance.
 *
 * @param Set<DrawableId> ids The drawables to delete.
 * @return PuppetModel The model with those drawables and their references gone.
 */
private fun PuppetModel.removingDrawables(ids: Set<DrawableId>): PuppetModel {
	if (ids.isEmpty()) {
		return this
	}
	val remaining =
		drawables.filterNot { drawable -> drawable.id in ids }
			.map { drawable ->
				val cleanedMasks = drawable.maskedBy.filterNot { maskId -> maskId in ids }
				if (cleanedMasks.size != drawable.maskedBy.size) drawable.copy(maskedBy = cleanedMasks) else drawable
			}
	val cleanedGlues = glues.filterNot { glue -> glue.meshA in ids || glue.meshB in ids }
	val cleanedRoot = rootChildren.filterNot { child -> child is OrgChild.Drawable && child.id in ids }
	val cleanedParts =
		parts.map { part ->
			val kids = part.children.filterNot { child -> child is OrgChild.Drawable && child.id in ids }
			if (kids.size != part.children.size) part.copy(children = kids) else part
		}
	return copy(drawables = remaining, glues = cleanedGlues, rootChildren = cleanedRoot, parts = cleanedParts)
}

/**
 * Returns a copy of [this] with every drawable in [ids] deleted (mask / glue / tree references scrubbed,
 * render order re-derived once). A no-op (none of them present) returns the same instance.
 *
 * @param Set<DrawableId> ids The drawables to delete.
 * @return PuppetModel The model without those drawables.
 */
fun PuppetModel.withDrawablesDeleted(ids: Set<DrawableId>): PuppetModel {
	if (drawables.none { it.id in ids }) {
		return this
	}
	return removingDrawables(ids).withDerivedRenderRoot()
}

/**
 * Returns a copy of [this] with the single drawable [id] deleted (mask / glue / tree references scrubbed,
 * render order re-derived). A no-op (no such drawable) returns the same instance.
 *
 * @param DrawableId id The drawable to delete.
 * @return PuppetModel The model without that drawable, or [this] if it was absent.
 */
fun PuppetModel.withDrawableDeleted(id: DrawableId): PuppetModel = withDrawablesDeleted(setOf(id))

/** Maps one (x, y) from the deleted deformer's local space into its parent's local space. */
private fun interface LocalPointMap {
	fun map(x: Float, y: Float, out: FloatArray)
}

private fun restWarpMapper(warp: Deformer.Warp): LocalPointMap? {
	val grid = warp.geometryGrid ?: return null
	val points = grid.cells.firstOrNull { it.coordinate.isEmpty() }?.form?.controlPoints
		?: grid.cells.firstOrNull()?.form?.controlPoints
		?: return null
	val cols = warp.columns
	val rows = warp.rows
	val bilinear = warp.isQuadTransform
	val scratch = FloatArray(2)
	return LocalPointMap { x, y, out ->
		warpApply(points, cols, rows, bilinear, x, y, scratch, 0)
		out[0] = scratch[0]
		out[1] = scratch[1]
	}
}

private fun restRotationMapper(rotation: Deformer.Rotation): LocalPointMap? {
	val grid = rotation.geometryGrid ?: return null
	val form = grid.cells.firstOrNull { it.coordinate.isEmpty() }?.form
		?: grid.cells.firstOrNull()?.form
		?: return null
	val xform: RotationXform = rotationXform(form.angle, form.scale, flipX = false, flipY = false, form.originX, form.originY)
	return LocalPointMap { x, y, out -> xform.apply(x, y, out, 0) }
}

private fun restLocalMapper(deformer: Deformer): LocalPointMap? =
	when (deformer) {
		is Deformer.Warp -> restWarpMapper(deformer)
		is Deformer.Rotation -> restRotationMapper(deformer)
	}

private fun mapInterleaved(points: FloatArray, mapper: LocalPointMap): FloatArray {
	val out = FloatArray(points.size)
	val scratch = FloatArray(2)
	for (i in 0 until points.size / 2) {
		mapper.map(points[i * 2], points[i * 2 + 1], scratch)
		out[i * 2] = scratch[0]
		out[i * 2 + 1] = scratch[1]
	}
	return out
}

/** Maps absolute base + delta through [mapper], returning the new delta in the parent space. */
private fun mapDeltas(base: FloatArray, deltas: FloatArray, mapper: LocalPointMap): FloatArray {
	val out = FloatArray(deltas.size)
	val scratchBase = FloatArray(2)
	val scratchAbs = FloatArray(2)
	val count = minOf(base.size, deltas.size) / 2
	for (i in 0 until count) {
		val bx = base[i * 2]
		val by = base[i * 2 + 1]
		mapper.map(bx, by, scratchBase)
		mapper.map(bx + deltas[i * 2], by + deltas[i * 2 + 1], scratchAbs)
		out[i * 2] = scratchAbs[0] - scratchBase[0]
		out[i * 2 + 1] = scratchAbs[1] - scratchBase[1]
	}
	return out
}

private fun mapDrawableThrough(drawable: Drawable, grandParent: DeformerId?, mapper: LocalPointMap?): Drawable {
	val mesh = drawable.mesh
	if (mapper == null || mesh == null) {
		return drawable.copy(parentDeformerId = grandParent)
	}
	val newBase = mapInterleaved(mesh.positions, mapper)
	val newGrid = drawable.geometryGrid?.let { grid ->
		KeyformGrid(
			grid.axes,
			grid.cells.map { cell ->
				KeyformCell(cell.coordinate, MeshDeltaForm(mapDeltas(mesh.positions, cell.form.positionDeltas, mapper)))
			},
		)
	}
	val newBlends = drawable.blendShapes.map { binding ->
		binding.copy(
			forms = binding.forms.map { form ->
				form?.let {
					MeshForm(
						mapDeltas(mesh.positions, it.positionDeltas, mapper),
						it.drawOrder,
						it.opacity,
						it.multiplyColor,
						it.screenColor,
					)
				}
			},
		)
	}
	return drawable.copy(
		parentDeformerId = grandParent,
		mesh = DrawableMesh(newBase, mesh.uvs, mesh.indices),
		geometryGrid = newGrid,
		blendShapes = newBlends,
	)
}

private fun mapDeformerThrough(deformer: Deformer, grandParent: DeformerId?, mapper: LocalPointMap?, deletedRotation: Deformer.Rotation?): Deformer {
	val reparented = deformer.reparentedTo(grandParent)
	if (mapper == null) return reparented
	return when (reparented) {
		is Deformer.Warp -> {
			val grid = reparented.geometryGrid ?: return reparented
			reparented.copy(
				geometryGrid = KeyformGrid(
					grid.axes,
					grid.cells.map { cell ->
						KeyformCell(cell.coordinate, WarpLatticeForm(mapInterleaved(cell.form.controlPoints, mapper)))
					},
				),
				blendShapes = reparented.blendShapes.map { binding ->
					binding.copy(
						forms = binding.forms.map { form ->
							form?.let {
								WarpForm(
									mapInterleaved(it.controlPoints, mapper),
									it.opacity,
									it.multiplyColor,
									it.screenColor,
								)
							}
						},
					)
				},
			)
		}
		is Deformer.Rotation -> {
			val parentAngle = deletedRotation?.geometryGrid?.cells
				?.firstOrNull { it.coordinate.isEmpty() }?.form?.angle
				?: deletedRotation?.geometryGrid?.cells?.firstOrNull()?.form?.angle
				?: 0f
			val parentScale = deletedRotation?.geometryGrid?.cells
				?.firstOrNull { it.coordinate.isEmpty() }?.form?.scale
				?: deletedRotation?.geometryGrid?.cells?.firstOrNull()?.form?.scale
				?: 1f
			val grid = reparented.geometryGrid ?: return reparented
			reparented.copy(
				geometryGrid = KeyformGrid(
					grid.axes,
					grid.cells.map { cell ->
						val scratch = FloatArray(2)
						mapper.map(cell.form.originX, cell.form.originY, scratch)
						KeyformCell(
							cell.coordinate,
							RotationPivotForm(
								scratch[0],
								scratch[1],
								cell.form.angle + parentAngle,
								cell.form.scale * parentScale,
							),
						)
					},
				),
				blendShapes = reparented.blendShapes.map { binding ->
					binding.copy(
						forms = binding.forms.map { form ->
							form?.let {
								val scratch = FloatArray(2)
								mapper.map(it.originX, it.originY, scratch)
								RotationForm(
									scratch[0],
									scratch[1],
									it.angle + parentAngle,
									it.scale * parentScale,
									it.flipX,
									it.flipY,
									it.opacity,
									it.multiplyColor,
									it.screenColor,
								)
							}
						},
					)
				},
			)
		}
	}
}

/**
 * Returns a copy of [this] with the deformer [id] deleted by unwrapping it: its child deformers and the
 * drawables it deformed re-home to its own parent (null = an armature root). Child rest geometry is baked
 * through the removed deformer's rest pose so appearance is preserved. Does not touch the org tree. A
 * no-op (no such deformer) returns the same instance.
 *
 * @param DeformerId id The deformer to delete.
 * @return PuppetModel The model without that deformer, or [this] if it was absent.
 */
fun PuppetModel.withDeformerDeleted(id: DeformerId): PuppetModel {
	val deformer = deformers.firstOrNull { it.id == id } ?: return this
	val grandParent = deformer.parent
	val mapper = restLocalMapper(deformer)
	val deletedRotation = deformer as? Deformer.Rotation
	val updatedDeformers =
		deformers.filter { it.id != id }
			.map { other ->
				if (other.parent == id) mapDeformerThrough(other, grandParent, mapper, deletedRotation) else other
			}
	val updatedDrawables =
		drawables.map { drawable ->
			if (drawable.parentDeformerId == id) mapDrawableThrough(drawable, grandParent, mapper) else drawable
		}
	return copy(deformers = updatedDeformers, drawables = updatedDrawables).withDerivedRenderRoot()
}

/** The set of part ids in [id]'s org-tree subtree (the part itself plus every descendant part). */
private fun PuppetModel.partSubtreeIds(id: PartId): Set<PartId> {
	val partById = parts.associateBy { it.id }
	val subtree = LinkedHashSet<PartId>()
	val stack = ArrayDeque<PartId>()
	stack.add(id)
	while (stack.isNotEmpty()) {
		val next = stack.removeLast()
		if (!subtree.add(next)) {
			continue
		}
		partById[next]?.children?.forEach { child -> if (child is OrgChild.Part) stack.add(child.id) }
	}
	return subtree
}

/**
 * Returns a copy of [this] with the part [id] deleted. A [cascade] removes the whole subtree - the part,
 * every descendant part, and every drawable under any of them (references scrubbed); an ungroup (cascade
 * false) dissolves only the folder - its children (sub-parts and drawables) splice into its parent in
 * place, so nothing is destroyed. The render order is re-derived. A no-op (no such part) returns the same
 * instance.
 *
 * @param PartId id The part to delete.
 * @param Boolean cascade True to delete the subtree, false to ungroup (keep contents, splice them up).
 * @return PuppetModel The model with the part deleted, or [this] if it was absent.
 */
fun PuppetModel.withPartDeleted(id: PartId, cascade: Boolean): PuppetModel {
	val part = parts.firstOrNull { it.id == id } ?: return this
	val partRef = OrgChild.Part(id)
	// The deleted part's parent (where ungrouped contents land / dangling deformer bindings fall back to).
	val grandParentId = parts.firstOrNull { partRef in it.children }?.id
	return if (cascade) {
		val subtree = partSubtreeIds(id)
		val doomedDrawables =
			parts.filter { it.id in subtree }
				.flatMap { it.children }
				.filterIsInstance<OrgChild.Drawable>()
				.map { it.id }
				.toSet()
		// Drop the subtree parts; detach the part ref from wherever it sits (root or its parent).
		val remainingParts =
			parts.filterNot { it.id in subtree }
				.map { candidate -> if (partRef in candidate.children) candidate.copy(children = candidate.children - partRef) else candidate }
		val cleanedRoot = rootChildren.filter { it != partRef }
		// A deformer's organizational partId may point into the deleted subtree; clear it so nothing dangles.
		val cleanedDeformers =
			deformers.map { deformer ->
				if (deformer.partId != null && deformer.partId in subtree) deformer.reboundToPart(null) else deformer
			}
		copy(parts = remainingParts, rootChildren = cleanedRoot, deformers = cleanedDeformers)
			.removingDrawables(doomedDrawables)
			.copy(rootPartId = if (rootPartId != null && rootPartId in subtree) null else rootPartId)
			.withDerivedRenderRoot()
	} else {
		// Ungroup: replace the part ref with the part's own children, in place, wherever it sits.
		fun splice(children: List<OrgChild>): List<OrgChild> = children.flatMap { if (it == partRef) part.children else listOf(it) }
		val cleanedRoot = splice(rootChildren)
		val remainingParts = parts.filterNot { it.id == id }.map { it.copy(children = splice(it.children)) }
		val cleanedDeformers = deformers.map { deformer -> if (deformer.partId == id) deformer.reboundToPart(grandParentId) else deformer }
		copy(
			parts = remainingParts,
			rootChildren = cleanedRoot,
			deformers = cleanedDeformers,
			rootPartId = if (rootPartId == id) grandParentId else rootPartId,
		).withDerivedRenderRoot()
	}
}
