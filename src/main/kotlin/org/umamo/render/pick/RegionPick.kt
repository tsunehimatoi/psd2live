package org.umamo.render.pick

import org.umamo.runtime.model.DrawableId

/*
 * Region enclosure for object-mode box / circle select.  Object selection is whole-drawable, so a region
 * selects a drawable by its geometry CENTROID (the mean of its posed world vertices), Blender's object-mode
 * rule: a drawable is selected when its centroid falls inside the box or circle.  Centroid-inside is stable
 * and predictable - a full-canvas layer whose centroid sits at the canvas center is not grabbed by a region
 * that only grazes its edge, unlike an any-vertex-touching rule.
 *
 * These functions are pure and work in the same interleaved (x, y) world space as
 * org.umamo.render.eval.DeformedGeometry.worldPositions, which is exactly what an overlay caches at
 * gesture start and tests against each frame - so the selection math is unit-testable with no render or
 * Compose context.  Only drawables appear in worldPositions (deformers and parts have no viewport geometry),
 * and hidden / mesh-less drawables are already absent, so the enclosure naturally covers only real drawables.
 *
 * オブジェクトモードのボックス / サークル選択の領域判定。描画メッシュの重心が領域内にあるとき選択する。
 */

/**
 * The world-space centroid of each drawable: the mean of its interleaved (x, y) world vertices.  A drawable
 * with an empty array is skipped (it has no centroid to test).
 *
 * @param Map<DrawableId, FloatArray> worldPositions Interleaved world vertices per drawable.
 * @return Map<DrawableId, FloatArray> A two-element [x, y] centroid per drawable.
 */
fun drawableCentroids(worldPositions: Map<DrawableId, FloatArray>): Map<DrawableId, FloatArray> {
	val centroids = LinkedHashMap<DrawableId, FloatArray>(worldPositions.size)
	for ((drawableId, positions) in worldPositions) {
		var sumX = 0f
		var sumY = 0f
		var vertexCount = 0
		var slotIndex = 0
		while (slotIndex + 1 < positions.size) {
			sumX += positions[slotIndex]
			sumY += positions[slotIndex + 1]
			vertexCount++
			slotIndex += 2
		}
		if (vertexCount > 0) {
			centroids[drawableId] = floatArrayOf(sumX / vertexCount, sumY / vertexCount)
		}
	}
	return centroids
}

/**
 * The drawables whose centroid lies inside the axis-aligned world rectangle (a box select).  Boundaries are
 * inclusive.  The caller normalises the corners, so [minX] <= [maxX] and [minY] <= [maxY] is assumed.
 *
 * @param Map<DrawableId, FloatArray> centroids The per-drawable [x, y] centroids (see [drawableCentroids]).
 * @param Float minX The rectangle's low x edge.
 * @param Float minY The rectangle's low y edge.
 * @param Float maxX The rectangle's high x edge.
 * @param Float maxY The rectangle's high y edge.
 * @return Set<DrawableId> The enclosed drawable ids.
 */
fun drawablesInBox(
	centroids: Map<DrawableId, FloatArray>,
	minX: Float,
	minY: Float,
	maxX: Float,
	maxY: Float,
): Set<DrawableId> {
	val enclosed = LinkedHashSet<DrawableId>()
	for ((drawableId, centroid) in centroids) {
		if (centroid[0] in minX..maxX && centroid[1] in minY..maxY) {
			enclosed.add(drawableId)
		}
	}
	return enclosed
}

/**
 * The drawables whose centroid lies within [radius] of ([centerX], [centerY]) in world space (a circle
 * select brush stamp).  A point exactly on the circle counts as inside.
 *
 * @param Map<DrawableId, FloatArray> centroids The per-drawable [x, y] centroids (see [drawableCentroids]).
 * @param Float centerX The brush center x in world space.
 * @param Float centerY The brush center y in world space.
 * @param Float radius The brush radius in world units.
 * @return Set<DrawableId> The enclosed drawable ids.
 */
fun drawablesInCircle(
	centroids: Map<DrawableId, FloatArray>,
	centerX: Float,
	centerY: Float,
	radius: Float,
): Set<DrawableId> {
	val radiusSquared = radius * radius
	val enclosed = LinkedHashSet<DrawableId>()
	for ((drawableId, centroid) in centroids) {
		val offsetX = centroid[0] - centerX
		val offsetY = centroid[1] - centerY
		if (offsetX * offsetX + offsetY * offsetY <= radiusSquared) {
			enclosed.add(drawableId)
		}
	}
	return enclosed
}