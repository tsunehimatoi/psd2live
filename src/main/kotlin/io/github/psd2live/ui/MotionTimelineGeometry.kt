package io.github.psd2live.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** The timeline's horizontal mapping: [startTime] sits at x = 0, [pxPerSecond] pixels per second. */
internal data class TimelineViewport(val startTime: Float = 0f, val pxPerSecond: Float = 120f) {
	fun x(time: Float): Float = (time - startTime) * pxPerSecond
	fun time(x: Float): Float = startTime + x / pxPerSecond

	/** Zoomed by [factor] around the pixel [anchorX], which keeps its time. */
	fun zoomed(factor: Float, anchorX: Float): TimelineViewport {
		val anchor = time(anchorX)
		val scale = (pxPerSecond * factor).coerceIn(MIN_PX_PER_SECOND, MAX_PX_PER_SECOND)
		return TimelineViewport(startTime = (anchor - anchorX / scale).coerceAtLeast(-0.5f), pxPerSecond = scale)
	}

	fun panned(dx: Float): TimelineViewport = copy(startTime = (startTime + dx / pxPerSecond).coerceAtLeast(-0.5f))

	companion object {
		const val MIN_PX_PER_SECOND = 8f
		const val MAX_PX_PER_SECOND = 4000f

		/** [duration] across [width] pixels, with a small margin either side. */
		fun fit(duration: Float, width: Float, margin: Float = 16f): TimelineViewport {
			val usable = (width - margin * 2f).coerceAtLeast(40f)
			val scale = (usable / duration.coerceAtLeast(0.1f)).coerceIn(MIN_PX_PER_SECOND, MAX_PX_PER_SECOND)
			return TimelineViewport(startTime = -margin / scale, pxPerSecond = scale)
		}
	}
}

/** The curve view's vertical mapping of normalized values (0 = parameter minimum, 1 = maximum). */
internal data class ValueViewport(val low: Float = -0.08f, val high: Float = 1.08f) {
	fun y(normalized: Float, top: Float, height: Float): Float =
		top + (1f - (normalized - low) / (high - low).coerceAtLeast(1e-4f)) * height

	fun normalized(y: Float, top: Float, height: Float): Float =
		low + (1f - (y - top) / height.coerceAtLeast(1f)) * (high - low)

	/** Units of normalized value per pixel. */
	fun perPixel(height: Float): Float = (high - low) / height.coerceAtLeast(1f)

	fun zoomed(factor: Float, anchor: Float): ValueViewport {
		val span = ((high - low) / factor).coerceIn(0.02f, 20f)
		val t = (anchor - low) / (high - low)
		return ValueViewport(anchor - t * span, anchor - t * span + span)
	}

	companion object {
		/** Frames [values] with a margin; a flat set gets a band around it. */
		fun fit(values: List<Float>): ValueViewport {
			if (values.isEmpty()) return ValueViewport()
			var lo = values.min()
			var hi = values.max()
			if (hi - lo < 0.05f) {
				val mid = (lo + hi) / 2f
				lo = mid - 0.1f
				hi = mid + 0.1f
			}
			val pad = (hi - lo) * 0.1f
			return ValueViewport(lo - pad, hi + pad)
		}
	}
}

internal object MotionTimelineGeometry {
	private val STEPS = floatArrayOf(0.01f, 0.02f, 0.05f, 0.1f, 0.2f, 0.25f, 0.5f, 1f, 2f, 5f, 10f, 30f, 60f)

	/** Seconds between labelled ruler ticks, so labels sit at least [minSpacing] pixels apart. */
	fun majorStep(pxPerSecond: Float, minSpacing: Float = 64f): Float =
		STEPS.firstOrNull { it * pxPerSecond >= minSpacing } ?: STEPS.last()

	/** Seconds between the unlabelled ticks inside a [major] step. */
	fun minorStep(major: Float): Float = when {
		major >= 1f -> major / 4f
		major == 0.25f -> 0.05f
		else -> major / 5f
	}

	/** Tick times from [from] to [to], multiples of [step]. */
	fun ticks(from: Float, to: Float, step: Float): List<Float> {
		if (step <= 0f || to < from) return emptyList()
		val first = kotlin.math.ceil(from / step).toLong()
		val last = kotlin.math.floor(to / step).toLong()
		if (last - first > 2000) return emptyList()
		return (first..last).map { it * step }
	}

	fun formatTime(seconds: Float, step: Float): String = when {
		step >= 1f -> "%.0fs".format(seconds)
		step >= 0.1f -> "%.1f".format(seconds)
		else -> "%.2f".format(seconds)
	}

	/** The nearest of [points] within [radius] of [position]. */
	fun <T> hit(points: List<Pair<T, Offset>>, position: Offset, radius: Float): T? =
		points.minByOrNull { (it.second - position).getDistanceSquared() }
			?.takeIf { (it.second - position).getDistanceSquared() <= radius * radius }?.first

	/** Every point inside the rectangle spanned by [a] and [b]. */
	fun <T> boxSelect(points: List<Pair<T, Offset>>, a: Offset, b: Offset): Set<T> {
		val rect = Rect(min(a.x, b.x), min(a.y, b.y), max(a.x, b.x), max(a.y, b.y))
		return points.filter { rect.contains(it.second) }.mapTo(LinkedHashSet()) { it.first }
	}

	/** [time] on the frame grid of [fps] when [snap], else unchanged. */
	fun snap(time: Float, fps: Float, snap: Boolean): Float = if (!snap || fps <= 0f) time else Math.round(time * fps) / fps

	/** True when a press moved far enough to be a drag rather than a click. */
	fun isDrag(start: Offset, current: Offset, slop: Float): Boolean =
		abs(current.x - start.x) > slop || abs(current.y - start.y) > slop

	/** A parameter value as a fraction of its range. */
	fun normalize(value: Float, range: ClosedFloatingPointRange<Float>?): Float {
		if (range == null) return value
		val span = range.endInclusive - range.start
		return if (span <= 1e-6f) 0.5f else (value - range.start) / span
	}

	fun denormalize(normalized: Float, range: ClosedFloatingPointRange<Float>?): Float {
		if (range == null) return normalized
		return range.start + normalized * (range.endInclusive - range.start)
	}
}
