package io.github.psd2live.core.mesh

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

internal data class Point(val x: Double, val y: Double)
internal data class Triangle(val a: Int, val b: Int, val c: Int)
internal data class Edge(val low: Int, val high: Int)
internal data class LocalMesh(val points: List<Point>, val triangles: List<Triangle>)

internal const val GEOMETRY_EPSILON = 1e-8

// Construct topology at exported precision to avoid faces collapsing on Float conversion.
internal fun snap(point: Point): Point = Point(point.x.toFloat().toDouble(), point.y.toFloat().toDouble())

internal fun orient(loop: List<Point>, positive: Boolean): List<Point> =
	(if ((signedAreaTwice(loop) > 0) == positive) loop else loop.reversed()).map(::snap)

internal fun inDomain(point: Point, loops: List<List<Point>>): Boolean =
	pointInPolygon(point, loops.first()) && loops.drop(1).none { pointInPolygon(point, it) }

internal fun loopsTouch(a: List<Point>, b: List<Point>): Boolean = a.indices.any { i ->
	b.indices.any { j ->
		val p = a[i]; val q = a[(i + 1) % a.size]
		val r = b[j]; val s = b[(j + 1) % b.size]
		segmentsProperlyIntersect(p, q, r, s) || pointOnSegment(p, r, s) ||
			pointOnSegment(q, r, s) || pointOnSegment(r, p, q) || pointOnSegment(s, p, q)
	}
}

internal fun validDomain(loops: List<List<Point>>): Boolean {
	if (loops.isEmpty() || loops.any { it.size < 3 || abs(signedAreaTwice(it)) < 1e-6 || hasSelfIntersection(it) }) return false
	for (i in loops.indices) for (j in 0 until i) {
		if (loopsTouch(loops[i], loops[j])) return false
		if (j == 0 && !pointInPolygon(loops[i][0], loops[0])) return false
		if (j > 0 && (pointInPolygon(loops[i][0], loops[j]) || pointInPolygon(loops[j][0], loops[i]))) return false
	}
	return true
}

internal fun pointInPolygon(point: Point, polygon: List<Point>): Boolean {
	var inside = false
	var previous = polygon.last()
	for (current in polygon) {
		if ((current.y > point.y) != (previous.y > point.y)) {
			val crossingX = (previous.x - current.x) * (point.y - current.y) /
				(previous.y - current.y) + current.x
			if (point.x < crossingX) inside = !inside
		}
		previous = current
	}
	return inside
}

internal fun pointInTriangleInclusive(point: Point, a: Point, b: Point, c: Point): Boolean {
	val ab = cross(a, b, point)
	val bc = cross(b, c, point)
	val ca = cross(c, a, point)
	val hasNegative = ab < -GEOMETRY_EPSILON || bc < -GEOMETRY_EPSILON || ca < -GEOMETRY_EPSILON
	val hasPositive = ab > GEOMETRY_EPSILON || bc > GEOMETRY_EPSILON || ca > GEOMETRY_EPSILON
	return !(hasNegative && hasPositive)
}

internal fun pointOnSegment(point: Point, a: Point, b: Point): Boolean {
	val length = distance(a, b).coerceAtLeast(GEOMETRY_EPSILON)
	if (abs(cross(a, b, point)) / length > 1e-7) return false
	return point.x >= min(a.x, b.x) - 1e-7 && point.x <= max(a.x, b.x) + 1e-7 &&
		point.y >= min(a.y, b.y) - 1e-7 && point.y <= max(a.y, b.y) + 1e-7
}

internal fun distanceSquaredToSegment(point: Point, a: Point, b: Point): Double {
	val dx = b.x - a.x
	val dy = b.y - a.y
	val lengthSquared = dx * dx + dy * dy
	val t = if (lengthSquared <= GEOMETRY_EPSILON) 0.0 else {
		((point.x - a.x) * dx + (point.y - a.y) * dy) / lengthSquared
	}.coerceIn(0.0, 1.0)
	val px = a.x + dx * t - point.x
	val py = a.y + dy * t - point.y
	return px * px + py * py
}

internal fun distanceSquaredToLoop(point: Point, loop: List<Point>): Double {
	var best = Double.POSITIVE_INFINITY
	for (index in loop.indices) best = min(best, distanceSquaredToSegment(point, loop[index], loop[(index + 1) % loop.size]))
	return best
}

internal fun hasSelfIntersection(loop: List<Point>): Boolean {
	if (loop.size < 4) return false
	for (first in loop.indices) {
		val firstNext = (first + 1) % loop.size
		for (second in first + 1 until loop.size) {
			val secondNext = (second + 1) % loop.size
			if (first == second || firstNext == second || secondNext == first) continue
			if (first == 0 && secondNext == 0) continue
			if (segmentsProperlyIntersect(loop[first], loop[firstNext], loop[second], loop[secondNext])) return true
		}
	}
	return false
}

internal fun segmentsProperlyIntersect(a: Point, b: Point, c: Point, d: Point): Boolean {
	val abC = cross(a, b, c)
	val abD = cross(a, b, d)
	val cdA = cross(c, d, a)
	val cdB = cross(c, d, b)
	return abC * abD < -GEOMETRY_EPSILON && cdA * cdB < -GEOMETRY_EPSILON
}

/** Distance along [direction] (unit) from [origin] to segment ab, or +inf when the ray misses. */
internal fun raySegmentDistance(origin: Point, dx: Double, dy: Double, a: Point, b: Point): Double {
	val ex = b.x - a.x
	val ey = b.y - a.y
	val denominator = dx * ey - dy * ex
	if (abs(denominator) < 1e-12) return Double.POSITIVE_INFINITY
	val wx = a.x - origin.x
	val wy = a.y - origin.y
	val t = (wx * ey - wy * ex) / denominator
	val u = (wx * dy - wy * dx) / denominator
	return if (t > 1e-6 && u >= -1e-9 && u <= 1 + 1e-9) t else Double.POSITIVE_INFINITY
}

internal fun lerp(a: Point, b: Point, t: Double): Point =
	Point(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)

internal fun signedAreaTwice(points: List<Point>): Double {
	var area = 0.0
	for (index in points.indices) {
		val next = (index + 1) % points.size
		area += points[index].x * points[next].y - points[next].x * points[index].y
	}
	return area
}

internal fun triangleEdges(triangle: Triangle): List<Edge> = listOf(
	edgeOf(triangle.a, triangle.b),
	edgeOf(triangle.b, triangle.c),
	edgeOf(triangle.c, triangle.a),
)

internal fun oppositeVertex(triangle: Triangle, edge: Edge): Int =
	when {
		triangle.a != edge.low && triangle.a != edge.high -> triangle.a
		triangle.b != edge.low && triangle.b != edge.high -> triangle.b
		else -> triangle.c
	}

internal fun edgeOf(a: Int, b: Int): Edge = Edge(min(a, b), max(a, b))

internal fun distance(a: Point, b: Point): Double = hypot(a.x - b.x, a.y - b.y)

internal fun distanceSquared(a: Point, b: Point): Double {
	val dx = a.x - b.x
	val dy = a.y - b.y
	return dx * dx + dy * dy
}

internal fun cross(a: Point, b: Point, c: Point): Double =
	(b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)

/** Smallest interior angle of a triangle, in radians. */
internal fun minimumAngle(a: Point, b: Point, c: Point): Double {
	val ab = distance(a, b); val bc = distance(b, c); val ca = distance(c, a)
	fun angle(opposite: Double, s1: Double, s2: Double): Double {
		if (s1 <= GEOMETRY_EPSILON || s2 <= GEOMETRY_EPSILON) return 0.0
		return kotlin.math.acos(((s1 * s1 + s2 * s2 - opposite * opposite) / (2 * s1 * s2)).coerceIn(-1.0, 1.0))
	}
	return min(angle(bc, ab, ca), min(angle(ca, ab, bc), angle(ab, bc, ca)))
}

/** Uniform bucket grid over closed loops for nearest-segment distance queries. */
internal class SegmentIndex(loops: List<List<Point>>, cellHint: Double) {
	private val starts = mutableListOf<Point>()
	private val ends = mutableListOf<Point>()
	private val minX: Double
	private val minY: Double
	private val cell: Double
	private val columns: Int
	private val rows: Int
	private val buckets: Array<IntArray>

	init {
		for (loop in loops) for (i in loop.indices) { starts += loop[i]; ends += loop[(i + 1) % loop.size] }
		val all = loops.flatten()
		minX = all.minOfOrNull { it.x } ?: 0.0
		minY = all.minOfOrNull { it.y } ?: 0.0
		val maxX = all.maxOfOrNull { it.x } ?: 1.0
		val maxY = all.maxOfOrNull { it.y } ?: 1.0
		val extent = max(maxX - minX, maxY - minY).coerceAtLeast(1.0)
		cell = max(cellHint, extent / 256.0).coerceAtLeast(1.0)
		columns = (ceil((maxX - minX) / cell).toInt() + 1).coerceAtLeast(1)
		rows = (ceil((maxY - minY) / cell).toInt() + 1).coerceAtLeast(1)
		val lists = Array(columns * rows) { mutableListOf<Int>() }
		for (s in starts.indices) {
			val a = starts[s]; val b = ends[s]
			val x0 = column(min(a.x, b.x)); val x1 = column(max(a.x, b.x))
			val y0 = row(min(a.y, b.y)); val y1 = row(max(a.y, b.y))
			for (y in y0..y1) for (x in x0..x1) lists[y * columns + x] += s
		}
		buckets = Array(lists.size) { lists[it].toIntArray() }
	}

	private fun column(x: Double) = floor((x - minX) / cell).toInt().coerceIn(0, columns - 1)
	private fun row(y: Double) = floor((y - minY) / cell).toInt().coerceIn(0, rows - 1)

	val segmentCount: Int get() = starts.size

	fun distance(point: Point): Double {
		if (starts.isEmpty()) return Double.POSITIVE_INFINITY
		val cx = column(point.x)
		val cy = row(point.y)
		// Clamped queries outside the grid must still see the nearest cells first.
		val outside = max(max(minX - point.x, point.x - (minX + columns * cell)),
			max(minY - point.y, point.y - (minY + rows * cell))).coerceAtLeast(0.0)
		var best = Double.POSITIVE_INFINITY
		val maxRing = max(columns, rows)
		for (ring in 0..maxRing) {
			if (best < Double.POSITIVE_INFINITY && sqrt(best) <= outside + (ring - 1).coerceAtLeast(0) * cell) break
			for (y in cy - ring..cy + ring) {
				if (y < 0 || y >= rows) continue
				val edgeRow = y == cy - ring || y == cy + ring
				var x = cx - ring
				while (x <= cx + ring) {
					if (x in 0 until columns) {
						for (s in buckets[y * columns + x]) {
							best = min(best, distanceSquaredToSegment(point, starts[s], ends[s]))
						}
					}
					x += if (edgeRow || ring == 0) 1 else 2 * ring
				}
			}
		}
		return sqrt(best)
	}

	/** Nearest hit of a ray against every indexed segment, skipping segments touching [origin]. */
	fun rayDistance(origin: Point, dx: Double, dy: Double): Double {
		var best = Double.POSITIVE_INFINITY
		for (s in starts.indices) {
			val a = starts[s]; val b = ends[s]
			if (distanceSquaredToSegment(origin, a, b) < 1e-10) continue
			best = min(best, raySegmentDistance(origin, dx, dy, a, b))
		}
		return best
	}
}
