package org.umamo.render.puppet

import org.umamo.render.ContentBounds
import org.umamo.render.eval.DeformedGeometry
import org.umamo.runtime.model.DrawableId

/**
 * Computes the world-space extent of a deformed pose's shown drawables - the framing `view.fit` frames to.
 *
 * Backend-neutral: a bounding box over already-deformed positions, so every renderer backend shares it
 * rather than re-deriving it beside its own GPU calls.
 *
 * Hidden drawables are excluded deliberately: a hidden full-canvas guide image or background must not
 * stretch the framing of a view it is not drawn in.
 *
 * @param DeformedGeometry geometry The CPU-evaluated pose to measure.
 * @param Set<DrawableId>  shownIds The drawables actually drawn (the resolved visibility cascade).
 * @return ContentBounds The extent, with each span floored at 1 so a degenerate model never divides by
 *   zero downstream.
 * @note With no shown drawable the min/max sweep never runs, so the result is the sentinel
 *   `ContentBounds(Float.MAX_VALUE, Float.MAX_VALUE, 1f, 1f)`.  That is the long-standing behavior and is
 *   preserved verbatim here rather than "fixed" during a pure extraction; [contentBoundsOfIsSentinelWhenNothingShown]
 *   pins it.  A caller that frames on it gets a nonsense camera, so change it deliberately, on its own, if ever.
 */
internal fun contentBoundsOf(geometry: DeformedGeometry, shownIds: Set<DrawableId>): ContentBounds {
	var loX = Float.MAX_VALUE
	var loY = Float.MAX_VALUE
	var hiX = -Float.MAX_VALUE
	var hiY = -Float.MAX_VALUE
	for ((drawableId, world) in geometry.worldPositions) {
		// A hidden full-canvas guide image / background must not stretch the framing it isn't drawn in.
		if (drawableId !in shownIds) {
			continue
		}
		var coordIndex = 0
		while (coordIndex < world.size) {
			loX = minOf(loX, world[coordIndex])
			hiX = maxOf(hiX, world[coordIndex])
			loY = minOf(loY, world[coordIndex + 1])
			hiY = maxOf(hiY, world[coordIndex + 1])
			coordIndex += 2
		}
	}
	return ContentBounds(loX, loY, maxOf(hiX - loX, 1f), maxOf(hiY - loY, 1f))
}