package io.github.psd2live.ui.tutorial

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt

/** Subtract holes one at a time, without overlapping scrims (which would darken twice). */
internal fun spotlightMaskRects(window: Rect, holes: List<Rect>): List<Rect> =
	holes.fold(listOf(window)) { regions, hole ->
		regions.flatMap { region ->
			val cut = region.intersect(hole)
			if (cut.width <= 0f || cut.height <= 0f) listOf(region)
			else listOf(
				Rect(region.left, region.top, region.right, cut.top),
				Rect(region.left, cut.bottom, region.right, region.bottom),
				Rect(region.left, cut.top, cut.left, cut.bottom),
				Rect(cut.right, cut.top, region.right, cut.bottom),
			).filter { it.width > 0f && it.height > 0f }
		}
	}

/** Uses measured card dimensions, including wrapped translations and UI scaling. */
internal fun coachPosition(hole: Rect?, window: IntSize, card: IntSize, margin: Float, preferSide: Boolean): IntOffset {
	val maxX = (window.width - card.width - margin).coerceAtLeast(0f)
	val maxY = (window.height - card.height - margin).coerceAtLeast(0f)
	fun clamp(x: Float, y: Float) = IntOffset(
		x.coerceIn(margin.coerceAtMost(maxX), maxX).roundToInt(),
		y.coerceIn(margin.coerceAtMost(maxY), maxY).roundToInt(),
	)
	if (hole == null) return clamp((window.width - card.width) / 2f, (window.height - card.height) / 2f)
	val sides = listOf(
		Pair(hole.left - card.width - margin, hole.center.y - card.height / 2f),
		Pair(hole.right + margin, hole.center.y - card.height / 2f),
	)
	val vertical = listOf(
		Pair(hole.center.x - card.width / 2f, hole.bottom + margin),
		Pair(hole.center.x - card.width / 2f, hole.top - card.height - margin),
	)
	val candidates = if (preferSide) sides + vertical else vertical + sides
	return candidates.map { (x, y) -> clamp(x, y) }.firstOrNull { position ->
		val cardRect = Rect(position.x.toFloat(), position.y.toFloat(), position.x + card.width.toFloat(), position.y + card.height.toFloat())
		!cardRect.overlaps(hole)
	} ?: clamp((window.width - card.width) / 2f, (window.height - card.height) / 2f)
}
