package org.umamo.edit

import org.umamo.runtime.model.DrawableId

/**
 * How Individual Origins ([TransformPivotMode.IndividualOrigins]) partitions one mesh into pivot groups.
 *
 * The two editing contexts read the same pivot mode differently, which the mode's own docblock already
 * describes: in Edit and the UV editor a mesh's covered vertices split into connectivity islands, each
 * turning about its own centroid; in Object mode a whole drawable turns about its single centroid.  Making
 * that an explicit parameter of [buildModalTransformCapture] keeps the one difference visible instead of
 * hiding it in two copies of the group-derivation code.
 */
enum class IndividualOriginScope {
	/** Split the covered set into connectivity islands (Edit mode, the UV editor). */
	ConnectivityIsland,

	/** One group for the whole covered set, about its centroid (Object mode). */
	WholeMesh,
}

/**
 * One mesh offered to [buildModalTransformCapture], before its pivots are resolved.
 *
 * The positions are frozen in whatever space the gesture runs in - world-posed for the viewport overlays,
 * texel display for the UV editor - and the covered indices are the union of vertices the mesh's selected
 * elements span (every vertex, for an Object-mode whole-drawable transform).  A source whose covered set is
 * empty contributes nothing and is dropped by the builder.
 *
 * @property DrawableId drawableId The drawable this mesh belongs to (the capture's per-mesh key).
 * @property FloatArray positions The frozen interleaved positions the operator transforms and the pivots
 *   are measured in.  The builder does not copy it - the caller freezes (copies) before handing it over.
 * @property IntArray triangleIndices The mesh's triangle vertex indices (connectivity for islands and the
 *   geodesic proportional falloff).
 * @property Set<Int> coveredIndices The vertices the selection covers on this mesh.
 */
class ModalCaptureSource(
	val drawableId: DrawableId,
	val positions: FloatArray,
	val triangleIndices: IntArray,
	val coveredIndices: Set<Int>,
)

/**
 * One mesh inside an in-flight modal transform: its frozen source geometry plus the pivot groups its
 * covered vertices turn about, and the proportional-editing halo re-derived from the same frozen positions.
 *
 * [influence] and [movedIndices] are the only mutable state, because a mid-gesture radius or falloff change
 * re-derives them - always from [positions], never from a live preview, so the influence ring can never
 * feed back on its own output.
 *
 * @property DrawableId drawableId The drawable this entry belongs to.
 * @property FloatArray positions The frozen positions the operator transforms (shared by reference from the
 *   source - never mutated).
 * @property Set<Int> coveredIndices The vertices the selection covers on this mesh.
 * @property IntArray triangleIndices The mesh's triangle vertex indices.
 * @property List<TransformPivotGroup> groups The pivot groups the covered vertices turn about.
 */
class ModalCaptureEntry internal constructor(
	val drawableId: DrawableId,
	val positions: FloatArray,
	val coveredIndices: Set<Int>,
	val triangleIndices: IntArray,
	val groups: List<TransformPivotGroup>,
) {
	/** The proportional-editing halo: influenced unselected vertices to weight, empty when proportional is off. */
	var influence: Map<Int, ProportionalInfluence> = emptyMap()
		private set

	/** Every vertex the gesture moves: the covered set, plus the influenced halo when proportional is on. */
	var movedIndices: Set<Int> = coveredIndices
		private set

	/**
	 * Re-derives this mesh's influence halo and moved set from the FROZEN positions.
	 *
	 * @param ProportionalEditState? state The proportional configuration, or null to clear the halo.
	 * @param Float radius The influence radius in the positions' units (unused when [state] is null).
	 */
	internal fun deriveInfluence(state: ProportionalEditState?, radius: Float) {
		influence =
			when {
				state == null -> emptyMap()
				state.connectedOnly ->
					proportionalInfluencesConnected(positions, coveredIndices, radius, state.falloff, triangleIndices)
				else -> proportionalInfluences(positions, coveredIndices, radius, state.falloff)
			}
		movedIndices =
			if (influence.isEmpty()) {
				coveredIndices
			} else {
				coveredIndices + influence.keys
			}
	}
}

/**
 * The captured state of an in-flight modal transform, frozen when the operator latches so the whole drag is
 * atomic across the meshes it moves.  Shared by the Edit, Object, and UV gizmo overlays: each freezes its
 * own-space geometry into [ModalCaptureSource]s, resolves its own active-element and cursor anchors, and
 * hands both to [buildModalTransformCapture]; what comes back is identical in shape across all three.
 *
 * Overlays that need extra per-mesh data the shared capture does not carry (the Edit and Object overlays'
 * deformer-chain mapping, the UV editor's page dimensions) hold it in a map keyed on
 * [ModalCaptureEntry.drawableId] rather than a second index-aligned list.
 *
 * @property List<ModalCaptureEntry> entries The moving meshes, one per source that had covered vertices.
 * @property Pair<Float, Float> anchor The point in the gesture's space that factors and angles measure
 *   against (and the shared-pivot modes turn about); the HUD's pivot marker draws here.
 * @property MeshOperatorKind operatorKind The operator that latched, frozen so the commit names the
 *   operation that actually ran rather than re-reading a latch that may already have cleared.
 */
class ModalTransformCapture internal constructor(
	val entries: List<ModalCaptureEntry>,
	val anchor: Pair<Float, Float>,
	val operatorKind: MeshOperatorKind,
) {
	/** The Rotate gesture's angle accumulator (unwrapped per-move increments; see RotationAngleTracker). */
	val rotationTracker = RotationAngleTracker()

	/** The captured drawables, in capture order. */
	val drawableIds: List<DrawableId> get() = entries.map { entry -> entry.drawableId }

	/**
	 * Re-derives every mesh's proportional halo from the frozen originals.  Called on latch and again on a
	 * mid-gesture radius or falloff change; feeding the same inputs twice is idempotent.
	 *
	 * @param ProportionalEditState? state The proportional configuration, or null to clear the halos.
	 * @param Float radius The influence radius in the gesture space's units (unused when [state] is null).
	 */
	fun applyProportional(state: ProportionalEditState?, radius: Float) {
		for (entry in entries) {
			entry.deriveInfluence(state, radius)
		}
	}
}

/**
 * Builds a [ModalTransformCapture] from the meshes a gesture will move, resolving the shared anchor and each
 * mesh's pivot groups per the active [pivotMode].
 *
 * This owns exactly the part every overlay duplicated: the covered-vertex mean across the meshes, the
 * fallback policy (Active Element and Cursor both fall back to the median when their anchor is absent), and
 * the per-mesh group derivation.  What differs between overlays stays with the caller and arrives
 * pre-resolved: [activeAnchor] (each overlay finds its active element / drawable differently) and
 * [cursorAnchor] (each reads a different cursor, in a different space).
 *
 * @param List<ModalCaptureSource> sources The meshes offered; those with no covered vertices are dropped.
 * @param TransformPivotMode pivotMode The active pivot mode (which anchor, and single vs per-island groups).
 * @param IndividualOriginScope individualOriginScope How Individual Origins partitions a mesh.
 * @param MeshOperatorKind operatorKind The operator that latched (frozen onto the capture).
 * @param Pair<Float, Float>? activeAnchor The Active-Element anchor the caller resolved, or null to fall
 *   back to the median.
 * @param Pair<Float, Float>? cursorAnchor The Cursor anchor the caller resolved, or null to fall back to
 *   the median.
 * @return ModalTransformCapture? The capture, or null when no source had covered vertices (no gesture runs).
 */
fun buildModalTransformCapture(
	sources: List<ModalCaptureSource>,
	pivotMode: TransformPivotMode,
	individualOriginScope: IndividualOriginScope,
	operatorKind: MeshOperatorKind,
	activeAnchor: Pair<Float, Float>?,
	cursorAnchor: Pair<Float, Float>?,
): ModalTransformCapture? {
	val moving = sources.filter { source -> source.coveredIndices.isNotEmpty() }
	if (moving.isEmpty()) {
		return null
	}
	// The covered-vertex mean across every moving mesh - the shared median anchor, and the fallback for the
	// Active-Element and Cursor modes.  Object mode covers every vertex, so this equals its combined
	// centroid (MeshTransforms.combinedCentroid) up to summation order.
	var coveredSumX = 0f
	var coveredSumY = 0f
	var coveredCount = 0
	for (source in moving) {
		for (vertexIndex in source.coveredIndices) {
			coveredSumX += source.positions[vertexIndex * 2]
			coveredSumY += source.positions[vertexIndex * 2 + 1]
			coveredCount++
		}
	}
	val median = (coveredSumX / coveredCount) to (coveredSumY / coveredCount)
	val anchor =
		when (pivotMode) {
			TransformPivotMode.MedianPoint, TransformPivotMode.IndividualOrigins -> median
			TransformPivotMode.ActiveElement -> activeAnchor ?: median
			TransformPivotMode.Cursor -> cursorAnchor ?: median
		}
	val entries =
		moving.map { source ->
			val groups =
				when {
					pivotMode == TransformPivotMode.IndividualOrigins && individualOriginScope == IndividualOriginScope.ConnectivityIsland ->
						TransformPivots.islandGroups(source.positions, source.coveredIndices, source.triangleIndices)
					pivotMode == TransformPivotMode.IndividualOrigins -> {
						// WholeMesh: the drawable turns about its own centroid (the object-mode island).
						val ownCentroid = MeshTransforms.medianPivot(source.positions, source.coveredIndices)
						TransformPivots.sharedGroup(source.coveredIndices, ownCentroid.first, ownCentroid.second)
					}
					else -> TransformPivots.sharedGroup(source.coveredIndices, anchor.first, anchor.second)
				}
			ModalCaptureEntry(source.drawableId, source.positions, source.coveredIndices, source.triangleIndices, groups)
		}
	return ModalTransformCapture(entries, anchor, operatorKind)
}