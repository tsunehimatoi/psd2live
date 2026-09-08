package org.umamo.edit

import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PuppetModel

/**
 * The drawables an object-mode Grab / Scale / Rotate may transform, or null when the gesture must be
 * blocked.  Object G / S / R writes only DrawableMesh.positions, so it can move a drawable but not a
 * deformer (whose shape lives in absolute per-keyform forms with no writer) nor a part (a container with
 * no geometry of its own).  Those ineligible targets are silently IGNORED rather than blocking: a Select
 * All / Invert sweeps parts and deformers into the selection, and the user expectation is that G still
 * moves the meshes it can.  Only a selection with nothing transformable at all (empty, or holding only
 * parts / deformers / mesh-less drawables) returns null, and the caller blocks with a note.
 *
 * The future Deformer to Part to Mesh cascade (transforming a part transforms its meshes; transforming a
 * deformer cascades through its parts into their meshes) is a separate project; until it and a
 * deformer-shape writer exist, parts and deformers are skipped.
 *
 * オブジェクトモードの G / S / R が変形できる描画メッシュ。パーツ・デフォーマ・メッシュ無しは黙って
 * 除外し、変形可能な描画オブジェクトの ID 一覧を返す。1つも無ければ null（ジェスチャをブロック）。
 *
 * @param Selection selection The object-mode selection to evaluate.
 * @param PuppetModel model The rig the targets index into.
 * @return List<DrawableId>? The transformable drawable ids, or null when nothing is transformable.
 */
fun eligibleTransformDrawables(selection: Selection, model: PuppetModel): List<DrawableId>? {
	if (selection.isEmpty) {
		return null
	}
	val drawableIds = ArrayList<DrawableId>(selection.size)
	for (target in selection.targets) {
		// Parts and deformers cannot be transformed yet (see the docblock); skip them, not the gesture.
		val drawableTarget = target as? SelectionTarget.Drawable ?: continue
		// A drawable that carries no source geometry has no positions to move; skip it too.
		val drawable = model.drawables.firstOrNull { it.id == drawableTarget.id }
		if (drawable?.mesh == null) {
			continue
		}
		drawableIds.add(drawableTarget.id)
	}
	return drawableIds.ifEmpty { null }
}

/**
 * Reports whether the live pose sits at every parameter's default value.  Object-mode transforms capture
 * the displayed (posed) geometry and write it back through the deformer-chain inverse, which is only
 * exact at the neutral pose - so [EditorSession.beginObjectOperator] refuses to start while any
 * parameter is scrubbed away from its default.  A parameter missing from the pose map counts as neutral
 * (the renderer substitutes the default for it).
 *
 * @param PuppetModel model The rig whose parameter defaults define neutral.
 * @param Pose pose The live parameter values to test.
 * @return Boolean True when every posed value equals its parameter's default.
 */
fun isPoseNeutral(model: PuppetModel, pose: Pose): Boolean {
	for (parameter in model.parameters) {
		val value = pose[parameter.id] ?: continue
		if (value != parameter.default) {
			return false
		}
	}
	return true
}

/**
 * The axis-aligned bounds of an interleaved position array: its center and its extents.  A drawable has no
 * scalar transform - its placement IS its geometry - so a "transform" for one is derived from these bounds,
 * and an edit to the transform is an edit to the geometry that produced them.
 *
 * Space-agnostic: this measures whatever array it is handed.  Which array that should be is the caller's
 * decision and a consequential one - see the Properties panel's DrawableWorldTransform, which measures
 * WORLD positions because a keyformed drawable's base array is nothing like its on-screen shape.
 *
 * @property Float centerX The bounds' center x.
 * @property Float centerY The bounds' center y.
 * @property Float width The x extent (0 for a degenerate or empty mesh).
 * @property Float height The y extent (0 for a degenerate or empty mesh).
 */
class MeshBounds(val centerX: Float, val centerY: Float, val width: Float, val height: Float)

/**
 * Computes the axis-aligned bounds of an interleaved (x, y) position array.  Shared by the Properties
 * readout and the transform edits below, so what is displayed and what is written are the same measurement
 * - a second definition would let the two drift and make a typed value land somewhere else.  Both must be
 * handed positions in the SAME space for that to hold.
 *
 * @param FloatArray positions Interleaved x, y vertex positions.
 * @return MeshBounds The center and extents; a zero box when there are no vertices.
 */
fun meshBounds(positions: FloatArray): MeshBounds {
	if (positions.size < 2) {
		return MeshBounds(0f, 0f, 0f, 0f)
	}
	var minX = Float.POSITIVE_INFINITY
	var minY = Float.POSITIVE_INFINITY
	var maxX = Float.NEGATIVE_INFINITY
	var maxY = Float.NEGATIVE_INFINITY
	var slotIndex = 0
	while (slotIndex + 1 < positions.size) {
		val x = positions[slotIndex]
		val y = positions[slotIndex + 1]
		if (x < minX) {
			minX = x
		}
		if (x > maxX) {
			maxX = x
		}
		if (y < minY) {
			minY = y
		}
		if (y > maxY) {
			maxY = y
		}
		slotIndex += 2
	}
	return MeshBounds((minX + maxX) / 2f, (minY + maxY) / 2f, maxX - minX, maxY - minY)
}

/** Every vertex index of an interleaved position array - the whole-mesh index set the object edits move. */
private fun allVertexIndices(positions: FloatArray): Set<Int> = (0 until positions.size / 2).toSet()

/**
 * Translates a whole mesh so its bounds center lands on ([newCenterX], [newCenterY]).  This is the write
 * side of the Properties Position row: the row reads [meshBounds]'s center, and typing a value moves the
 * mesh rigidly by the difference, so the displayed number becomes exactly the value entered.
 *
 * Pure and space-agnostic - it moves the array it is given.  The Properties panel applies it to WORLD
 * positions and inverts the result back onto the base mesh; see DrawableWorldTransform.
 *
 * @param FloatArray positions The interleaved positions.
 * @param Float newCenterX The x the bounds center should land on.
 * @param Float newCenterY The y the bounds center should land on.
 * @return FloatArray A new positions array, or the same instance when nothing moves.
 */
fun movedToBoundsCenter(positions: FloatArray, newCenterX: Float, newCenterY: Float): FloatArray {
	if (positions.size < 2) {
		return positions
	}
	val bounds = meshBounds(positions)
	val deltaX = newCenterX - bounds.centerX
	val deltaY = newCenterY - bounds.centerY
	if (deltaX == 0f && deltaY == 0f) {
		return positions
	}
	return MeshTransforms.translateVertices(positions, allVertexIndices(positions), deltaX, deltaY)
}

/**
 * Scales a whole mesh about its bounds center so its extents become ([newWidth], [newHeight]).  The write
 * side of the Properties Size row.  Pure and space-agnostic like [movedToBoundsCenter].
 *
 * The bounds center is the pivot precisely because it is a fixed point of the scale: the Position row reads
 * that same center, so resizing never makes the position readout jump.  (The vertex centroid -
 * MeshTransforms.combinedCentroid, which the viewport gizmo pivots on - sits off the bounds center on an
 * asymmetric mesh and would move it.)
 *
 * An axis whose current extent is zero has no defined factor - a degenerate mesh with every vertex on one
 * line cannot be given a width - so that axis is left alone rather than multiplied by infinity.
 *
 * @param FloatArray positions The interleaved positions.
 * @param Float newWidth The target x extent (ignored when the current x extent is 0).
 * @param Float newHeight The target y extent (ignored when the current y extent is 0).
 * @return FloatArray A new positions array, or the same instance when nothing changes.
 */
fun resizedAboutBoundsCenter(positions: FloatArray, newWidth: Float, newHeight: Float): FloatArray {
	if (positions.size < 2) {
		return positions
	}
	val bounds = meshBounds(positions)
	val factorX = if (bounds.width > 0f) newWidth / bounds.width else 1f
	val factorY = if (bounds.height > 0f) newHeight / bounds.height else 1f
	if (factorX == 1f && factorY == 1f) {
		return positions
	}
	return MeshTransforms.scaleVerticesAxis(
		positions,
		allVertexIndices(positions),
		factorX,
		factorY,
		bounds.centerX,
		bounds.centerY,
	)
}