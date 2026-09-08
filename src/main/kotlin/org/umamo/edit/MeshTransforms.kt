package org.umamo.edit

import kotlin.math.cos
import kotlin.math.sin

/**
 * The active modal mesh operator: Blender-style Grab (translate), Scale, or Rotate. The session latches
 * one of these while a gesture is in flight (the desktop overlay drives the pointer tracking and the
 * corresponding [MeshTransforms] function); null means no operator is running.
 *
 * モーダルなメッシュ操作の種類（移動・拡縮・回転）。
 */
enum class MeshOperatorKind {
	Grab,
	Scale,
	Rotate,

	/**
	 * Slides the active vertex along one of its incident edges (Blender's Shift+V), clamped between the
	 * endpoints.  Edit mode only, driven by the Edit overlay's own slide branch (the pointer projects
	 * onto the edge rather than through the shared operator math).
	 */
	VertexSlide,
}

/**
 * Pure transforms over an interleaved mesh-position array for the modal G / S / R operators. Positions
 * are interleaved (x0, y0, x1, y1, ...), so vertex [index] occupies slots `2*index` and `2*index+1`
 * (matching [org.umamo.runtime.model.DrawableMesh]). Each operator returns a NEW array, mutating only the
 * coordinates of the selected vertices and copying the rest verbatim - it never touches its input, which
 * keeps them trivially testable and upholds the copy-on-write discipline (the caller commits the returned
 * array into a fresh DrawableMesh).
 *
 * The functions are coordinate-space agnostic: they operate on whatever values the array holds. The
 * caller (the gizmo overlay) maps the pointer gesture into the same space the positions live in (model
 * space) before computing the delta / factor / angle and the pivot.
 *
 * モーダルな移動・拡縮・回転の純粋変換。選択頂点のみを変更した新しい配列を返し、入力は変更しない。
 */
object MeshTransforms {
	/**
	 * The median pivot for scale / rotate: the mean (centroid) of the selected vertices' positions, in the
	 * array's coordinate space. Returns (0, 0) when nothing is selected (no operator runs in that case).
	 *
	 * @param FloatArray positions The interleaved mesh positions.
	 * @param Set<Int> indices The selected vertex indices.
	 * @return Pair<Float, Float> The (x, y) centroid of the selected vertices.
	 */
	fun medianPivot(positions: FloatArray, indices: Set<Int>): Pair<Float, Float> {
		if (indices.isEmpty()) {
			return 0f to 0f
		}
		var sumX = 0f
		var sumY = 0f
		for (vertexIndex in indices) {
			sumX += positions[vertexIndex * 2]
			sumY += positions[vertexIndex * 2 + 1]
		}
		val count = indices.size.toFloat()
		return (sumX / count) to (sumY / count)
	}

	/**
	 * The combined median pivot across several interleaved position arrays: the mean (centroid) of every
	 * vertex of every array, weighted by vertex count (so a denser mesh pulls the pivot toward itself, the
	 * same way [medianPivot] weights a single mesh by its vertices). This is the shared anchor an object-mode
	 * Scale / Rotate turns all selected drawables about. Returns (0, 0) when the collection is empty or holds
	 * only empty arrays (no gesture runs in that case).
	 *
	 * @param Collection<FloatArray> positionsList The interleaved position arrays, one per selected drawable.
	 * @return Pair<Float, Float> The (x, y) centroid across all arrays' vertices.
	 */
	fun combinedCentroid(positionsList: Collection<FloatArray>): Pair<Float, Float> {
		var sumX = 0f
		var sumY = 0f
		var vertexCount = 0
		for (positions in positionsList) {
			var slotIndex = 0
			while (slotIndex + 1 < positions.size) {
				sumX += positions[slotIndex]
				sumY += positions[slotIndex + 1]
				vertexCount++
				slotIndex += 2
			}
		}
		if (vertexCount == 0) {
			return 0f to 0f
		}
		val count = vertexCount.toFloat()
		return (sumX / count) to (sumY / count)
	}

	/**
	 * Translates the selected vertices by ([deltaX], [deltaY]).
	 *
	 * @param FloatArray positions The interleaved mesh positions.
	 * @param Set<Int> indices The selected vertex indices.
	 * @param Float deltaX The x offset.
	 * @param Float deltaY The y offset.
	 * @return FloatArray A new positions array with the selected vertices moved.
	 */
	fun translateVertices(positions: FloatArray, indices: Set<Int>, deltaX: Float, deltaY: Float): FloatArray {
		val result = positions.copyOf()
		for (vertexIndex in indices) {
			result[vertexIndex * 2] += deltaX
			result[vertexIndex * 2 + 1] += deltaY
		}
		return result
	}

	/**
	 * Collapses the selected vertices onto one point ([targetX], [targetY]) - Blender's pile-up snap
	 * semantics (Selection to Cursor / Selection to Active).  Unlike [translateVertices], the vertices
	 * do not keep their offsets from each other; every one lands exactly on the target.
	 *
	 * @param FloatArray positions The interleaved mesh positions.
	 * @param Set<Int> indices The selected vertex indices.
	 * @param Float targetX The x every selected vertex lands on.
	 * @param Float targetY The y every selected vertex lands on.
	 * @return FloatArray A new positions array with the selected vertices piled onto the target.
	 */
	fun collapseVertices(positions: FloatArray, indices: Set<Int>, targetX: Float, targetY: Float): FloatArray {
		val result = positions.copyOf()
		for (vertexIndex in indices) {
			result[vertexIndex * 2] = targetX
			result[vertexIndex * 2 + 1] = targetY
		}
		return result
	}

	/**
	 * Scales the selected vertices about the pivot ([pivotX], [pivotY]) by [factor] (uniform on both axes).
	 *
	 * @param FloatArray positions The interleaved mesh positions.
	 * @param Set<Int> indices The selected vertex indices.
	 * @param Float factor The uniform scale factor (1 leaves them unchanged).
	 * @param Float pivotX The pivot x (the scale anchor).
	 * @param Float pivotY The pivot y.
	 * @return FloatArray A new positions array with the selected vertices scaled.
	 */
	fun scaleVertices(positions: FloatArray, indices: Set<Int>, factor: Float, pivotX: Float, pivotY: Float): FloatArray {
		val result = positions.copyOf()
		for (vertexIndex in indices) {
			val x = result[vertexIndex * 2]
			val y = result[vertexIndex * 2 + 1]
			result[vertexIndex * 2] = pivotX + (x - pivotX) * factor
			result[vertexIndex * 2 + 1] = pivotY + (y - pivotY) * factor
		}
		return result
	}

	/**
	 * Scales the selected vertices about the pivot ([pivotX], [pivotY]) with independent per-axis
	 * factors - the axis-locked variant of [scaleVertices] (an X-locked scale passes factorY = 1 and
	 * vice versa).
	 *
	 * @param FloatArray positions The interleaved mesh positions.
	 * @param Set<Int> indices The selected vertex indices.
	 * @param Float factorX The scale factor along x (1 leaves x unchanged).
	 * @param Float factorY The scale factor along y (1 leaves y unchanged).
	 * @param Float pivotX The pivot x (the scale anchor).
	 * @param Float pivotY The pivot y.
	 * @return FloatArray A new positions array with the selected vertices scaled.
	 */
	fun scaleVerticesAxis(positions: FloatArray, indices: Set<Int>, factorX: Float, factorY: Float, pivotX: Float, pivotY: Float): FloatArray {
		val result = positions.copyOf()
		for (vertexIndex in indices) {
			val x = result[vertexIndex * 2]
			val y = result[vertexIndex * 2 + 1]
			result[vertexIndex * 2] = pivotX + (x - pivotX) * factorX
			result[vertexIndex * 2 + 1] = pivotY + (y - pivotY) * factorY
		}
		return result
	}

	/**
	 * Rotates the selected vertices about the pivot ([pivotX], [pivotY]) by [radians].
	 *
	 * @param FloatArray positions The interleaved mesh positions.
	 * @param Set<Int> indices The selected vertex indices.
	 * @param Float radians The rotation angle in radians.
	 * @param Float pivotX The pivot x (the rotation center).
	 * @param Float pivotY The pivot y.
	 * @return FloatArray A new positions array with the selected vertices rotated.
	 */
	fun rotateVertices(positions: FloatArray, indices: Set<Int>, radians: Float, pivotX: Float, pivotY: Float): FloatArray {
		val result = positions.copyOf()
		val cosAngle = cos(radians)
		val sinAngle = sin(radians)
		for (vertexIndex in indices) {
			val offsetX = result[vertexIndex * 2] - pivotX
			val offsetY = result[vertexIndex * 2 + 1] - pivotY
			result[vertexIndex * 2] = pivotX + offsetX * cosAngle - offsetY * sinAngle
			result[vertexIndex * 2 + 1] = pivotY + offsetX * sinAngle + offsetY * cosAngle
		}
		return result
	}

	/**
	 * Translates the weighted vertices by their share of ([deltaX], [deltaY]) - the proportional-editing
	 * companion of [translateVertices]: a weight of 1 moves fully, 0.5 half way.
	 *
	 * @param FloatArray positions The interleaved mesh positions.
	 * @param Map<Int, Float> weights Vertex index to influence weight in [0, 1].
	 * @param Float deltaX The full x offset (each vertex takes weight x this).
	 * @param Float deltaY The full y offset.
	 * @return FloatArray A new positions array with the weighted vertices moved.
	 */
	fun translateVerticesWeighted(positions: FloatArray, weights: Map<Int, Float>, deltaX: Float, deltaY: Float): FloatArray {
		val result = positions.copyOf()
		for ((vertexIndex, weight) in weights) {
			result[vertexIndex * 2] += deltaX * weight
			result[vertexIndex * 2 + 1] += deltaY * weight
		}
		return result
	}

	/**
	 * Scales the weighted vertices about the pivot by their share of the per-axis factors - the
	 * proportional-editing companion of [scaleVerticesAxis]: each vertex lerps from its current position
	 * toward its fully-scaled position by its weight (equivalently, its effective factor per axis is
	 * 1 + (factor - 1) x weight).
	 *
	 * @param FloatArray positions The interleaved mesh positions.
	 * @param Map<Int, Float> weights Vertex index to influence weight in [0, 1].
	 * @param Float factorX The full scale factor along x.
	 * @param Float factorY The full scale factor along y.
	 * @param Float pivotX The pivot x (the scale anchor).
	 * @param Float pivotY The pivot y.
	 * @return FloatArray A new positions array with the weighted vertices scaled.
	 */
	fun scaleVerticesWeighted(positions: FloatArray, weights: Map<Int, Float>, factorX: Float, factorY: Float, pivotX: Float, pivotY: Float): FloatArray {
		val result = positions.copyOf()
		for ((vertexIndex, weight) in weights) {
			val x = result[vertexIndex * 2]
			val y = result[vertexIndex * 2 + 1]
			result[vertexIndex * 2] = pivotX + (x - pivotX) * (1f + (factorX - 1f) * weight)
			result[vertexIndex * 2 + 1] = pivotY + (y - pivotY) * (1f + (factorY - 1f) * weight)
		}
		return result
	}

	/**
	 * Rotates the weighted vertices about the pivot by their share of [radians] - the
	 * proportional-editing companion of [rotateVertices].  The ANGLE is weighted (a half-weight vertex
	 * turns half the angle along the arc), not the endpoint position: lerping positions would cut the
	 * chord and collapse the falloff ring toward the pivot on large rotations.
	 *
	 * @param FloatArray positions The interleaved mesh positions.
	 * @param Map<Int, Float> weights Vertex index to influence weight in [0, 1].
	 * @param Float radians The full rotation angle in radians (each vertex turns weight x this).
	 * @param Float pivotX The pivot x (the rotation center).
	 * @param Float pivotY The pivot y.
	 * @return FloatArray A new positions array with the weighted vertices rotated.
	 */
	fun rotateVerticesWeighted(positions: FloatArray, weights: Map<Int, Float>, radians: Float, pivotX: Float, pivotY: Float): FloatArray {
		val result = positions.copyOf()
		for ((vertexIndex, weight) in weights) {
			val cosAngle = cos(radians * weight)
			val sinAngle = sin(radians * weight)
			val offsetX = result[vertexIndex * 2] - pivotX
			val offsetY = result[vertexIndex * 2 + 1] - pivotY
			result[vertexIndex * 2] = pivotX + offsetX * cosAngle - offsetY * sinAngle
			result[vertexIndex * 2 + 1] = pivotY + offsetX * sinAngle + offsetY * cosAngle
		}
		return result
	}
}