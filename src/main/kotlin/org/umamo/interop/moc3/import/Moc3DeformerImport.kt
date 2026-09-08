package org.umamo.interop.moc3.import

import org.umamo.format.moc3.MocDocument
import org.umamo.format.moc3.model.BlendShapeTarget
import org.umamo.format.moc3.model.RotationDeformer
import org.umamo.format.moc3.model.WarpDeformer
import org.umamo.interop.moc3.rotationScaleFactor
import org.umamo.runtime.keyform.fanOutRotation
import org.umamo.runtime.keyform.fanOutWarp
import org.umamo.runtime.model.ChannelGrids
import org.umamo.runtime.model.ColorRgb
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.RotationForm
import org.umamo.runtime.model.WarpForm

/**
 * Imports every deformer, warps and rotations alike, in file order.
 *
 * File order is preserved because every cross-reference in a MOC3 - a parent link, an art mesh's owner,
 * a blend record's target - is a position in this list, so reordering here would silently repoint all of
 * them.
 *
 * Each deformer's keyform grid is built ONCE as a bundled form and then fanned into its geometry and its
 * render tracks, rather than walked twice: the opacity and color tracks CASCADE onto every drawable
 * underneath, so a second walk that disagreed with the first about a cell would shift a whole subtree.
 *
 * @param Moc3ImportContext context The import's derived state.
 * @return List<Deformer> The runtime deformers, in file order.
 */
internal fun importDeformers(context: Moc3ImportContext): List<Deformer> {
	val mocDocument = context.mocDocument
	// A bake stores no deformer display name, so the label is the id - readable, but it says nothing
	// about what the deformer moves.  Where the answer is unambiguous, the drawable it deforms is
	// appended as an anchor: "Warp40 (ArtMesh5)".  See [soleDrawableByDeformer] for what counts.
	val soleDrawable = soleDrawableByDeformer(mocDocument)

	/**
	 * The display name of the deformer at [deformerIndex]: its id, plus the drawable it deforms
	 * when exactly one is in reach.
	 *
	 * @param Int        deformerIndex The deformer's file index.
	 * @param DeformerId id            The deformer's resolved runtime id.
	 * @return String The display name.
	 */
	fun deformerNameOf(
		deformerIndex: Int,
		id: DeformerId,
	): String {
		val anchorIndex = soleDrawable[deformerIndex] ?: return id.raw
		val anchorName = mocDocument.artMeshes.getOrNull(anchorIndex)?.id ?: return id.raw
		return "${id.raw} ($anchorName)"
	}

	return mocDocument.deformers.mapIndexed { deformerIndex, source ->
		val id = context.deformerIds[deformerIndex]
		val parent = context.deformerIds.getOrNull(source.parentDeformerIndex)
		val keyformSpace = context.pointSpaceOf(source.parentDeformerIndex)
		val binding = context.bindingOf(source.keyformBindingIndex)
		when (source) {
			is WarpDeformer -> {
				// One bundled grid, then split into lattice geometry and the render tracks that cascade
				// down onto every drawable under this deformer.
				val fannedWarp =
					gridOf(context, binding) { gridIndex ->
						source.keyforms.getOrNull(gridIndex)?.let { keyform ->
							WarpForm(
								context.convertPoints(keyformSpace, keyform.controlPoints),
								opacity = keyform.opacity,
								multiplyColor = colorRgbOf(keyform.multiplyColor) ?: ColorRgb.MultiplyIdentity,
								screenColor = colorRgbOf(keyform.screenColor) ?: ColorRgb.ScreenIdentity,
							)
						}
					}?.fanOutWarp()
				val warp =
					Deformer.Warp(
						id = id,
						name = deformerNameOf(deformerIndex, id),
						parent = parent,
						// MOC3 §5.6 s15: the deformer's own org-tree part; -1 (→ null) at the root.
						partId = context.partIds.getOrNull(source.parentPartIndex),
						// MOC3 §5.6 s13/s14: the editor's eye toggle and its unpinned partner.
						isVisible = source.isVisible,
						isEnabled = source.isEnabled,
						rows = source.rows,
						columns = source.columns,
						// MOC3 §5.6 warp mode: 0 = triangle split, non-zero = bilinear (quad).
						isQuadTransform = source.mode != 0,
						geometryGrid = fannedWarp?.geometry,
						channelGrids = fannedWarp?.channels ?: ChannelGrids.Empty,
					)
				val warpRecords = context.blendRecordsByTarget[BlendShapeTarget.WARP to deformerIndex].orEmpty()
				if (warpRecords.isEmpty()) {
					warp
				} else {
					warp.copy(blendShapes = warpBlendShapesOf(context, warp, keyformSpace, warpRecords))
				}
			}

			is RotationDeformer -> {
				val scaleFactor = rotationScaleFactor(context.hasRotationAncestor[deformerIndex], context.canvasMapping)
				// One bundled grid, then split into the pivot geometry, the render tracks that cascade
				// down onto every drawable under this deformer, and the two reflection flags.
				val fannedRotation =
					gridOf(context, binding) { gridIndex ->
						source.keyforms.getOrNull(gridIndex)?.let { keyform ->
							val origin =
								context.convertPoints(keyformSpace, floatArrayOf(keyform.originX, keyform.originY))
							RotationForm(
								originX = origin[0],
								originY = origin[1],
								angle = keyform.angle,
								scale = keyform.scale * scaleFactor,
								flipX = keyform.reflectX,
								flipY = keyform.reflectY,
								opacity = keyform.opacity,
								multiplyColor = colorRgbOf(keyform.multiplyColor) ?: ColorRgb.MultiplyIdentity,
								screenColor = colorRgbOf(keyform.screenColor) ?: ColorRgb.ScreenIdentity,
							)
						}
					}?.fanOutRotation()
				val rotation =
					Deformer.Rotation(
						id = id,
						name = deformerNameOf(deformerIndex, id),
						parent = parent,
						partId = context.partIds.getOrNull(source.parentPartIndex),
						isVisible = source.isVisible,
						isEnabled = source.isEnabled,
						baseAngle = source.baseAngle,
						geometryGrid = fannedRotation?.geometry,
						channelGrids = fannedRotation?.channels ?: ChannelGrids.Empty,
					)
				val rotationRecords = context.blendRecordsByTarget[BlendShapeTarget.ROTATION to deformerIndex].orEmpty()
				if (rotationRecords.isEmpty()) {
					rotation
				} else {
					rotation.copy(
						blendShapes =
							rotationBlendShapesOf(
								context,
								rotation,
								keyformSpace,
								scaleFactor,
								rotationRecords,
							),
					)
				}
			}
		}
	}
}

/**
 * Infers each deformer's organizational part from the drawables it deforms.  A FALLBACK only: MOC3
 * §5.6 s15 carries real deformer→part membership, and this runs solely when that section placed
 * nothing at all.
 *
 * A MOC3 whose s15 is absent or entirely -1 - a stripped or synthesized file, or one that
 * genuinely puts every deformer at the root - converts to a flat org tree with an unusable
 * parts panel.  The two cases are indistinguishable in a bake, and inference is the better
 * outcome for both, so this reconstructs the grouping from the one signal that is still
 * present: a deformer's drawables almost always live in the deformer's own part.  The rule is
 * the plurality part of the DIRECTLY deformed drawables, falling back to the whole descendant
 * subtree for a deformer that only deforms other deformers.
 *
 * This is INFERENCE, not recovery.  Measured against the corpus twins (each model's
 * editor-written CMO3 against its own bake) it reproduced 81-93% of the original placements,
 * and a deformer whose drawables span several parts can land in any of them - which is why it
 * never overrides s15.  A CMO3-origin document never reaches here: it carries the real
 * membership on ACParameterControllableSource.parentGuid.
 *
 * @param List deformers      The imported deformers, none of which resolved a part.
 * @param List drawables      The imported drawables.
 * @param Map  partByDrawable Each drawable's org-tree part (null at the root).
 * @return List The deformers with inferred [Deformer.partId] values.
 * @see <a href="https://docs.umamo.org/format/MOC3.md">MOC3.md §5.6 s15</a>
 */
internal fun inferDeformerParts(
	deformers: List<Deformer>,
	drawables: List<Drawable>,
	partByDrawable: Map<DrawableId, PartId?>,
): List<Deformer> {
	val parentById = deformers.associate { deformer -> deformer.id to deformer.parent }
	val directParts = HashMap<DeformerId, MutableList<PartId>>()
	val subtreeParts = HashMap<DeformerId, MutableList<PartId>>()
	for (drawable in drawables) {
		val partId = partByDrawable[drawable.id] ?: continue
		val ownerId = drawable.parentDeformerId ?: continue
		directParts.getOrPut(ownerId) { ArrayList() }.add(partId)
		// Walk the deformer chain so an ancestor that deforms no drawable directly still sees the
		// parts below it; the visited set guards a malformed cyclic chain.
		var ancestorId: DeformerId? = ownerId
		val visited = HashSet<DeformerId>()
		while (ancestorId != null && visited.add(ancestorId)) {
			subtreeParts.getOrPut(ancestorId) { ArrayList() }.add(partId)
			ancestorId = parentById[ancestorId]
		}
	}

	/**
	 * The plurality part id of a candidate list, or null when empty.
	 *
	 * @param List? candidates The observed part ids.
	 * @return PartId? The most frequent id.
	 */
	fun plurality(candidates: List<PartId>?): PartId? =
		candidates?.groupingBy { partId -> partId }?.eachCount()?.maxByOrNull { entry -> entry.value }?.key

	return deformers.map { deformer ->
		val inferred = plurality(directParts[deformer.id]) ?: plurality(subtreeParts[deformer.id])
		if (inferred == null) {
			deformer
		} else {
			when (deformer) {
				is Deformer.Warp -> deformer.copy(partId = inferred)
				is Deformer.Rotation -> deformer.copy(partId = inferred)
			}
		}
	}
}

/**
 * The one drawable each deformer deforms, for the deformers where "the one" is unambiguous.
 *
 * Used only to label a deformer, whose authored name a bake drops.  A deformer's own drawables
 * decide it when it has any; otherwise its whole descendant subtree does, so a rotation that
 * only drives a warp still names the mesh at the bottom of the chain.  Either way the answer is
 * a CARDINALITY, not a vote: a deformer over several drawables has no single answer and is left
 * out, because drawables - unlike parts - are distinct entities rather than a category several
 * of them can agree on, so picking a winner would just be naming the deformer after an arbitrary
 * one of its meshes.  Being an anchor rather than data, an absent entry costs a label, nothing more.
 *
 * @param MocDocument mocDocument The decoded document (file indices throughout).
 * @return Map Deformer file index → its sole drawable's file index, for the unambiguous ones.
 */
private fun soleDrawableByDeformer(mocDocument: MocDocument): Map<Int, Int> {
	val deformerCount = mocDocument.deformers.size
	if (deformerCount == 0) {
		return emptyMap()
	}
	val ambiguous = -1
	val direct = HashMap<Int, Int>()
	val subtree = HashMap<Int, Int>()

	/**
	 * Records [drawableIndex] against [deformerIndex], marking the slot ambiguous on a second hit.
	 *
	 * @param HashMap into          The tally to record into.
	 * @param Int     deformerIndex The owning deformer's file index.
	 * @param Int     drawableIndex The drawable's file index.
	 */
	fun record(
		into: HashMap<Int, Int>,
		deformerIndex: Int,
		drawableIndex: Int,
	) {
		into[deformerIndex] = if (deformerIndex in into) ambiguous else drawableIndex
	}
	for ((drawableIndex, artMesh) in mocDocument.artMeshes.withIndex()) {
		var ancestorIndex = artMesh.parentDeformerIndex
		if (ancestorIndex !in 0 until deformerCount) {
			continue
		}
		record(direct, ancestorIndex, drawableIndex)
		// Walk to the root so an ancestor that deforms no drawable directly still sees the meshes
		// below it; the visited set guards a malformed cyclic chain.
		val visited = HashSet<Int>()
		while (ancestorIndex in 0 until deformerCount && visited.add(ancestorIndex)) {
			record(subtree, ancestorIndex, drawableIndex)
			ancestorIndex = mocDocument.deformers[ancestorIndex].parentDeformerIndex
		}
	}
	val resolved = HashMap<Int, Int>(deformerCount)
	for (deformerIndex in 0 until deformerCount) {
		val drawableIndex = direct[deformerIndex] ?: subtree[deformerIndex] ?: continue
		if (drawableIndex != ambiguous) {
			resolved[deformerIndex] = drawableIndex
		}
	}
	return resolved
}