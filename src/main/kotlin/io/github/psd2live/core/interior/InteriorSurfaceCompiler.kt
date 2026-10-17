package io.github.psd2live.core.interior

import io.github.psd2live.core.mesh.*
import java.util.PriorityQueue
import kotlin.math.*

internal data class InteriorFoldBinding(
	val vertex: Int,
	val u: Double,
	val nx: Double,
	val ny: Double,
	val weight: Double,
)

internal data class InteriorFold(
	val diameter: Double,
	val amount: Double,
	val pitchOnly: Boolean,
	val bindings: List<InteriorFoldBinding>,
)

internal class InteriorMaterialBinding(
	val acrossX: Double,
	val acrossY: Double,
	val alongX: Double,
	val alongY: Double,
	val span: Double,
	val length: Double,
	val u: DoubleArray,
	val rowU: DoubleArray,
	val rowSpan: DoubleArray,
	val alongU: DoubleArray,
	val columnU: DoubleArray,
	val columnSpan: DoubleArray,
	val edge: DoubleArray,
	val interior: DoubleArray,
	val folds: List<InteriorFold>,
)

/** Raster-space registration, separate from topology authoring and shape input. */
internal object InteriorSurfaceCompiler {
	private fun smooth(t: Double): Double {
		val x = t.coerceIn(0.0, 1.0)
		return x * x * x * (10 + x * (-15 + 6 * x))
	}

	private data class PathPoint(
		val x: Double,
		val y: Double,
		var s: Double = 0.0,
		var tx: Double = 0.0,
		var ty: Double = 0.0,
	)

	private fun path(guide: InteriorGuide): List<PathPoint> {
		val p = guide.points
		val out = ArrayList<PathPoint>()
		for (i in 0 until p.size - 1) {
			val a = p[max(0, i - 1)]
			val b = p[i]
			val c = p[i + 1]
			val d = p[min(p.lastIndex, i + 2)]
			for (j in 0 until 12) {
				val t = j / 12.0
				val t2 = t * t
				val t3 = t2 * t
				fun value(a: Double, b: Double, c: Double, d: Double) =
					.5 *
						(2 * b +
							(-a + c) * t +
							(2 * a - 5 * b + 4 * c - d) * t2 +
							(-a + 3 * b - 3 * c + d) * t3)
				out +=
					PathPoint(
						value(a.x.toDouble(), b.x.toDouble(), c.x.toDouble(), d.x.toDouble()),
						value(a.y.toDouble(), b.y.toDouble(), c.y.toDouble(), d.y.toDouble()),
					)
			}
		}
		out += PathPoint(p.last().x.toDouble(), p.last().y.toDouble())
		for (i in 1 until out.size) out[i].s =
			out[i - 1].s + hypot(out[i].x - out[i - 1].x, out[i].y - out[i - 1].y)
		for (i in out.indices) {
			val a = out[max(0, i - 1)]
			val b = out[min(out.lastIndex, i + 1)]
			val length = hypot(b.x - a.x, b.y - a.y).let { if (it == 0.0) 1.0 else it }
			out[i].tx = (b.x - a.x) / length
			out[i].ty = (b.y - a.y) / length
		}
		return out
	}

	/** Authoring topology follows the same material curve, without changing its shape controls. */
	fun guideSamples(guide: InteriorGuide): List<InteriorPoint> =
		path(guide)
			.map {
				InteriorPoint(it.x.toFloat(), it.y.toFloat())
			}
			.fold(ArrayList<InteriorPoint>()) { points, p ->
				points.apply { if (lastOrNull() != p) add(p) }
			}

	fun compile(
		mesh: InteriorMesh,
		raster: InteriorRaster,
		guides: List<InteriorGuide>,
	): InteriorMaterialBinding {
		require(guides.size <= 64 && guides.map { it.id }.distinct().size == guides.size) {
			"Too many or duplicate interior guides"
		}
		val w = raster.width
		val h = raster.height
		val count = mesh.vertexCount
		var x0 = w
		var x1 = -1
		var y0 = h
		var y1 = -1
		for (y in 0 until h) for (x in 0 until w) if (raster.visible(x, y)) {
			x0 = min(x0, x)
			x1 = max(x1, x + 1)
			y0 = min(y0, y)
			y1 = max(y1, y + 1)
		}
		require(x1 > x0 && y1 > y0) { "The material is fully transparent" }
		// Source image axes: right/down. Interior shaping has no hanging attachment.
		val n = 0.0 to 1.0
		val acrossX = n.second
		val acrossY = -n.first
		val corners =
			listOf(
				Point(x0.toDouble(), y0.toDouble()),
				Point(x1.toDouble(), y0.toDouble()),
				Point(x0.toDouble(), y1.toDouble()),
				Point(x1.toDouble(), y1.toDouble()),
			)
		val across = corners.map { it.x * acrossX + it.y * acrossY }
		val along = corners.map { it.x * n.first + it.y * n.second }
		val lo = across.min()
		val span = across.max() - lo
		val top = along.min()
		val length = along.max() - top
		val unit = min(span, length)
		val horizontal = acrossX != 0.0
		val rows = if (horizontal) h else w
		val columns = if (horizontal) w else h
		fun supports(row: Int, column: Int) =
			if (horizontal) raster.visible(column, row) else raster.visible(row, column)
		val lower = DoubleArray(rows) { Double.POSITIVE_INFINITY }
		val upper = DoubleArray(rows) { Double.NEGATIVE_INFINITY }
		val alongLower = DoubleArray(columns) { Double.POSITIVE_INFINITY }
		val alongUpper = DoubleArray(columns) { Double.NEGATIVE_INFINITY }
		for (row in 0 until rows) for (column in 0 until columns) if (supports(row, column)) {
			lower[row] = min(lower[row], column.toDouble())
			upper[row] = max(upper[row], column + 1.0)
			alongLower[column] = min(alongLower[column], row.toDouble())
			alongUpper[column] = max(alongUpper[column], row + 1.0)
		}
		fun extend(low: DoubleArray, high: DoubleArray) {
			var previous = -1
			for (i in low.indices) if (low[i].isFinite()) previous = i
			else if (previous >= 0) {
				low[i] = low[previous]
				high[i] = high[previous]
			}
			previous = -1
			for (i in low.indices.reversed()) if (low[i].isFinite()) previous = i
			else if (previous >= 0) {
				low[i] = low[previous]
				high[i] = high[previous]
			}
		}
		extend(lower, upper)
		extend(alongLower, alongUpper)
		fun interpolate(a: DoubleArray, value: Double): Double {
			val t = value.coerceIn(0.0, a.lastIndex.toDouble())
			val i = floor(t).toInt()
			val j = min(a.lastIndex, i + 1)
			return a[i] + (a[j] - a[i]) * (t - i)
		}
		val distance = distanceField(raster)
		fun pixel(x: Double, y: Double): Double {
			val px = x.coerceIn(0.0, w - 1.0)
			val py = y.coerceIn(0.0, h - 1.0)
			val ix = floor(px).toInt()
			val iy = floor(py).toInt()
			val jx = min(w - 1, ix + 1)
			val jy = min(h - 1, iy + 1)
			val u = px - ix
			val v = py - iy
			return (distance[iy * w + ix] * (1 - u) + distance[iy * w + jx] * u) * (1 - v) +
				(distance[jy * w + ix] * (1 - u) + distance[jy * w + jx] * u) * v
		}
		val u = DoubleArray(count)
		val rowU = DoubleArray(count)
		val rowSpan = DoubleArray(count)
		val alongU = DoubleArray(count)
		val columnU = DoubleArray(count)
		val columnSpan = DoubleArray(count)
		val edge = DoubleArray(count)
		for ((i, p) in mesh.points.withIndex()) {
			val v = (p.x * n.first + p.y * n.second - top) / length
			u[i] = ((p.x * acrossX + p.y * acrossY - lo) / span).coerceIn(0.0, 1.0)
			edge[i] = smooth(pixel(p.x, p.y) / (unit * .11))
			val r = if (horizontal) p.y else p.x
			val q = if (horizontal) p.x else p.y
			val a = interpolate(lower, r)
			val d = max(1e-12, interpolate(upper, r) - a)
			val t = ((q - a) / d).coerceIn(0.0, 1.0)
			rowU[i] = if ((if (horizontal) acrossX else acrossY) > 0) t else 1 - t
			rowSpan[i] = d
			val ar = if (horizontal) p.x else p.y
			val aq = if (horizontal) p.y else p.x
			val aa = interpolate(alongLower, ar)
			val ad = max(1e-12, interpolate(alongUpper, ar) - aa)
			val at = ((aq - aa) / ad).coerceIn(0.0, 1.0)
			alongU[i] = v.coerceIn(0.0, 1.0)
			columnU[i] = if ((if (horizontal) n.second else n.first) > 0) at else 1 - at
			columnSpan[i] = ad
		}
		val folds =
			guides
				.filter { it.purpose == InteriorGuidePurpose.FOLD && it.amount > 0 }
				.map { guide ->
					val path = path(guide)
					val radius = guide.radius.toDouble()
					val bindings = ArrayList<InteriorFoldBinding>()
					for ((i, p) in mesh.points.withIndex()) {
						var closest = Double.POSITIVE_INFINITY
						var mass = 0.0
						var cx = 0.0
						var cy = 0.0
						var tx = 0.0
						var ty = 0.0
						var alongSum = 0.0
						for (j in 0 until path.size - 1) {
							val a = path[j]
							val b = path[j + 1]
							val dx = b.x - a.x
							val dy = b.y - a.y
							val d = dx * dx + dy * dy
							if (d < 1e-12) continue
							val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / d).coerceIn(0.0, 1.0)
							val px = a.x + dx * t
							val py = a.y + dy * t
							val ds = (p.x - px).pow(2) + (p.y - py).pow(2)
							closest = min(closest, ds)
							val influence = exp(-ds / (radius * radius * .16)) * sqrt(d)
							mass += influence
							cx += px * influence
							cy += py * influence
							tx += (a.tx + (b.tx - a.tx) * t) * influence
							ty += (a.ty + (b.ty - a.ty) * t) * influence
							alongSum += (a.s + (b.s - a.s) * t) * influence
						}
						val tangent = hypot(tx, ty)
						if (mass > 1e-14 && closest < radius * radius && tangent / mass >= .05) {
							val nx = -ty / tangent
							val ny = tx / tangent
							val d = (p.x - cx / mass) * nx + (p.y - cy / mass) * ny
							val v = alongSum / mass / path.last().s
							val weight =
								smooth(v / .16) *
									smooth((1 - v) / .16) *
									smooth((1 - sqrt(closest) / radius) / .25) *
									tangent / mass
							if (weight > 0)
								bindings +=
									InteriorFoldBinding(
										i,
										((d / radius + 1) / 2).coerceIn(0.0, 1.0),
										nx,
										ny,
										weight,
									)
						}
					}
					InteriorFold(radius * 2, guide.amount.toDouble(), guide.pitchOnly, bindings)
				}
		return InteriorMaterialBinding(
			acrossX,
			acrossY,
			n.first,
			n.second,
			span,
			length,
			u,
			rowU,
			rowSpan,
			alongU,
			columnU,
			columnSpan,
			edge,
			boundaryWeights(mesh, raster, unit),
			folds,
		)
	}

	private fun distanceField(raster: InteriorRaster): FloatArray {
		val w = raster.width
		val h = raster.height
		val diagonal = sqrt(2.0)
		val dist =
			FloatArray(w * h) { i ->
				if (
					!raster.visible(i % w, i / w) ||
						i % w == 0 ||
						i / w == 0 ||
						i % w == w - 1 ||
						i / w == h - 1
				)
					0f
				else Float.POSITIVE_INFINITY
			}
		for (y in 0 until h) for (x in 0 until w) {
			val i = y * w + x
			if (x > 0) dist[i] = min(dist[i].toDouble(), dist[i - 1] + 1.0).toFloat()
			if (y > 0) {
				dist[i] = min(dist[i].toDouble(), dist[i - w] + 1.0).toFloat()
				if (x > 0) dist[i] = min(dist[i].toDouble(), dist[i - w - 1] + diagonal).toFloat()
				if (x < w - 1)
					dist[i] = min(dist[i].toDouble(), dist[i - w + 1] + diagonal).toFloat()
			}
		}
		for (y in h - 1 downTo 0) for (x in w - 1 downTo 0) {
			val i = y * w + x
			if (x < w - 1) dist[i] = min(dist[i].toDouble(), dist[i + 1] + 1.0).toFloat()
			if (y < h - 1) {
				dist[i] = min(dist[i].toDouble(), dist[i + w] + 1.0).toFloat()
				if (x > 0) dist[i] = min(dist[i].toDouble(), dist[i + w - 1] + diagonal).toFloat()
				if (x < w - 1)
					dist[i] = min(dist[i].toDouble(), dist[i + w + 1] + diagonal).toFloat()
			}
		}
		return dist
	}

	private fun boundaryWeights(
		mesh: InteriorMesh,
		raster: InteriorRaster,
		unit: Double,
	): DoubleArray {
		val w = raster.width
		val h = raster.height
		val stride = w + 1
		val integral = IntArray(stride * (h + 1))
		for (y in 0 until h) {
			var count = 0
			for (x in 0 until w) {
				if (
					raster.visible(x, y) &&
						(!raster.visible(x - 1, y) ||
							!raster.visible(x + 1, y) ||
							!raster.visible(x, y - 1) ||
							!raster.visible(x, y + 1))
				)
					count++
				integral[(y + 1) * stride + x + 1] = integral[y * stride + x + 1] + count
			}
		}
		val pinned = BooleanArray(mesh.vertexCount)
		val adjacent = Array(mesh.vertexCount) { ArrayList<Pair<Int, Double>>() }
		var edgeSum = 0.0
		var edgeCount = 0
		for (t in mesh.triangles) {
			val ids = listOf(t.a, t.b, t.c)
			val p = ids.map { mesh.points[it] }
			val x0 = floor(p.minOf { it.x } - 1).toInt().coerceIn(0, w)
			val x1 = ceil(p.maxOf { it.x } + 1).toInt().coerceIn(0, w)
			val y0 = floor(p.minOf { it.y } - 1).toInt().coerceIn(0, h)
			val y1 = ceil(p.maxOf { it.y } + 1).toInt().coerceIn(0, h)
			if (
				integral[y1 * stride + x1] -
					integral[y0 * stride + x1] -
					integral[y1 * stride + x0] + integral[y0 * stride + x0] > 0
			)
				for (id in ids) pinned[id] = true
			for (j in 0..2) {
				val a = ids[j]
				val b = ids[(j + 1) % 3]
				val length = distance(mesh.points[a], mesh.points[b])
				adjacent[a] += b to length
				adjacent[b] += a to length
				edgeSum += length
				edgeCount++
			}
		}
		val dist = DoubleArray(mesh.vertexCount) { Double.POSITIVE_INFINITY }
		val heap =
			PriorityQueue<Pair<Int, Double>>(
				compareBy<Pair<Int, Double>> { it.second }.thenBy { it.first }
			)
		for (i in pinned.indices) if (pinned[i]) {
			dist[i] = 0.0
			heap += i to 0.0
		}
		while (heap.isNotEmpty()) {
			val (i, d) = heap.remove()
			if (d != dist[i]) continue
			for ((j, length) in adjacent[i]) if (d + length < dist[j]) {
				dist[j] = d + length
				heap += j to dist[j]
			}
		}
		val transition = max(unit * .32, edgeSum / max(1, edgeCount) * 2)
		return DoubleArray(mesh.vertexCount) { smooth(dist[it] / transition) }
	}
}
