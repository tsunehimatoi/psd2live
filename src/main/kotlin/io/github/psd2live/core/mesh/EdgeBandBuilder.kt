package io.github.psd2live.core.mesh

import io.github.psd2live.core.MeshEdgeMode
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Parallel contour rows around an interleaved station loop.
 *
 * An interleaved loop has 2N samples on the guide curve: even samples are aligned stations and odd
 * samples are the half stations between them. Aligned rows use even samples and staggered rows use
 * odd samples, so neighboring rows always form the fixed `/\/\` or `\/\/` strip patterns.
 * Every loop is wound so that its left normal points into the material.
 */
internal object EdgeBandBuilder {
	private const val THICKNESS_SHARE = 0.45
	private const val WIDTH_SLOPE = 0.4
	private const val FOLD_ITERATIONS = 16
	private const val FOLD_REACH = 3
	private const val FOLD_SHRINK = 0.75
	/** Strip triangles need a height of at least this fraction of their longest edge. */
	private const val SLIVER_RATIO = 0.2
	private const val FOLD_RATIO = 1e-4
	/** Smallest outer-row offset where the raster crop leaves no room: pixels, and share of the station spacing. */
	private const val MIN_OUTWARD = 2.0
	private const val MIN_OUTWARD_SHARE = 0.3
	private const val NARROW_SCALE = 0.35
	private const val NARROW_FRACTION = 0.3

	/** Interleaves midpoints between the vertices of a polygon (fallback for unfitted contours). */
	fun interleave(stations: List<Point>): List<Point> = stations.indices.flatMap { i ->
		listOf(stations[i], snap(lerp(stations[i], stations[(i + 1) % stations.size], 0.5)))
	}

	/** Orients an interleaved loop while keeping aligned stations on even indices. */
	fun orientInterleaved(loop: List<Point>, positive: Boolean): List<Point> {
		if ((signedAreaTwice(loop) > 0) == positive) return loop.map(::snap)
		val reversed = loop.reversed()
		return (reversed.drop(1) + reversed.first()).map(::snap)
	}

	fun stations(loop: List<Point>): List<Point> = loop.filterIndexed { index, _ -> index % 2 == 0 }

	/** Nominal row offsets from the guide, outermost first; positive values point into the material. */
	fun rowOffsets(mode: MeshEdgeMode, width: Double): DoubleArray = when (mode) {
		MeshEdgeMode.SINGLE -> doubleArrayOf(0.0)
		MeshEdgeMode.DOUBLE -> doubleArrayOf(-width * 0.5, width * 0.5)
		MeshEdgeMode.TRIPLE -> doubleArrayOf(-width, 0.0, width)
	}

	/** Row parity: aligned rows sit on stations, staggered rows on half stations. */
	fun rowStaggered(mode: MeshEdgeMode): BooleanArray = when (mode) {
		MeshEdgeMode.SINGLE -> booleanArrayOf(false)
		MeshEdgeMode.DOUBLE -> booleanArrayOf(false, true)
		MeshEdgeMode.TRIPLE -> booleanArrayOf(false, true, false)
	}

	/**
	 * Builds all rows for every loop of one island, outermost row first.
	 * Returns null when the band cannot be embedded or is too narrow to be worth keeping.
	 *
	 * Rows are not bounded by the layer raster: like manually placed vertices, outer rows may overhang
	 * it and their UVs extrapolate onto transparent texels.
	 */
	fun build(
		loops: List<List<Point>>, mode: MeshEdgeMode, width: Double, neighbors: List<List<Point>>,
	): List<List<List<Point>>>? {
		if (mode == MeshEdgeMode.SINGLE || width <= 1e-3 || loops.any { it.size < 6 || it.size % 2 != 0 }) return null
		val offsets = rowOffsets(mode, width)
		val staggered = rowStaggered(mode)
		val outward = -offsets.first()
		val inward = offsets.last()
		val ownIndex = SegmentIndex(loops, width)
		val neighborIndex = if (neighbors.isEmpty()) null else SegmentIndex(neighbors, width)
		val result = mutableListOf<List<List<Point>>>()
		for (loop in loops) {
			val count = loop.size
			val (nx, ny) = smoothedNormals(loop)
			val arcs = DoubleArray(count) { distance(loop[it], loop[(it + 1) % count]) }
			val roomIn = DoubleArray(count)
			val roomOut = DoubleArray(count)
			// Near another contour or island the guide row stays on the curve and only the outer row is
			// compressed, down to a floor tied to the station spacing rather than the band width, so wide
			// bands do not drag the rows off the curve.
			val stationSpacing = arcs.sorted()[count / 2] * 2
			val minOutward = min(outward, max(MIN_OUTWARD, MIN_OUTWARD_SHARE * stationSpacing))
			val shrink = DoubleArray(count) { 1.0 }
			var scale = DoubleArray(count)
			var rows: List<List<Point>>? = null
			var unfolded: List<List<Point>>? = null
			for (attempt in 0..FOLD_ITERATIONS) {
				for (k in 0 until count) {
					val p = loop[k]
					roomIn[k] = THICKNESS_SHARE * ownIndex.rayDistance(p, nx[k], ny[k])
					var out = THICKNESS_SHARE * ownIndex.rayDistance(p, -nx[k], -ny[k])
					if (neighborIndex != null) out = min(out, THICKNESS_SHARE * neighborIndex.rayDistance(p, -nx[k], -ny[k]))
					roomOut[k] = out
				}
				val out = DoubleArray(count) { max(min(outward, roomOut[it]), minOutward) * shrink[it] }
				minSmooth(out, arcs, WIDTH_SLOPE)
				val shift = DoubleArray(count) { max(0.0, out[it] - roomOut[it]) }
				val required = shift.copyOf()
				maxSmooth(shift, arcs, WIDTH_SLOPE)
				for (k in 0 until count) shift[k] = max(required[k], min(shift[k], max(0.0, roomIn[k] - inward)))
				scale = DoubleArray(count) { (min(1.0, (roomIn[it] - shift[it]) / inward)).coerceAtLeast(0.0) * shrink[it] }
				minSmooth(scale, arcs, WIDTH_SLOPE / inward)
				val candidate = offsets.indices.map { r ->
					val parity = if (staggered[r]) 1 else 0
					List(count / 2) { i ->
						val k = i * 2 + parity
						val offset = if (offsets[r] < 0) offsets[r] / outward * out[k] else offsets[r] * scale[k]
						val amount = shift[k] + offset
						snap(Point(loop[k].x + nx[k] * amount, loop[k].y + ny[k] * amount))
					}
				}
				val folded = badSamples(candidate, staggered, SLIVER_RATIO)
				if (folded.isEmpty()) { rows = candidate; break }
				// Densely sampled tips cannot lose every sliver without collapsing the band; keep the
				// last result that is at least unfolded.
				val inverted = badSamples(candidate, staggered, FOLD_RATIO)
				if (inverted.isEmpty()) unfolded = candidate
				// Rows cross where the normal turns faster than 1/offset. Spread the turn over a longer
				// arc first (tips and corners get rounded rows); narrow only where rows still invert,
				// since narrowing flattens slivers further.
				fun around(samples: Set<Int>) = BooleanArray(count).also { region ->
					for (k in samples) for (d in -FOLD_REACH..FOLD_REACH) region[((k + d) % count + count) % count] = true
				}
				val region = around(folded)
				repeat(2) { smoothNormalsIn(nx, ny, region) }
				if (attempt % 2 == 1 && inverted.isNotEmpty()) {
					val narrow = around(inverted)
					for (k in 0 until count) if (narrow[k]) shrink[k] *= FOLD_SHRINK
				}
			}
			rows = rows ?: unfolded
			if (rows == null) return null
			if (scale.count { it < NARROW_SCALE } > count * NARROW_FRACTION) return null
			result += rows
		}
		for (r in offsets.indices) if (!validDomain(result.map { it[r] })) return null
		return result
	}

	/** Strip triangles between consecutive rows of one loop (row ids outermost first). */
	fun stripTriangles(rows: List<IntArray>, staggered: BooleanArray): List<Triangle> {
		val triangles = mutableListOf<Triangle>()
		for (r in 0 until rows.lastIndex) {
			val n = rows[r + 1].size
			// Aligned row j lies between staggered j-1 and j; staggered j lies between aligned j and j+1.
			val anchors = IntArray(n) { j -> if (staggered[r]) (j - 1 + n) % n else j }
			triangles += zipRows(rows[r], rows[r + 1], anchors)
		}
		return triangles
	}

	/**
	 * Triangulates the strip between closed rows [outer] and [inner], inner on the material side.
	 * Inner vertex j sits over the outer run starting at `anchors[j]`; a run of k outer segments
	 * gives k triangles on the outer row and one pointing back toward it.
	 */
	fun zipRows(outer: IntArray, inner: IntArray, anchors: IntArray): List<Triangle> {
		val n = outer.size
		val m = inner.size
		val triangles = mutableListOf<Triangle>()
		for (j in 0 until m) {
			val start = anchors[j]
			var span = ((anchors[(j + 1) % m] - start) % n + n) % n
			if (span == 0) span = n
			val split = start + (span + 1) / 2
			for (t in start until start + span) {
				val apex = if (t < split) inner[j] else inner[(j + 1) % m]
				triangles += Triangle(outer[t % n], outer[(t + 1) % n], apex)
			}
			triangles += Triangle(outer[split % n], inner[(j + 1) % m], inner[j])
		}
		return triangles
	}

	/** Unit left normals from central differences, smoothed [passes] times with a [1,2,1] kernel. */
	fun smoothedNormals(loop: List<Point>, passes: Int = 2): Pair<DoubleArray, DoubleArray> {
		val count = loop.size
		var tx = DoubleArray(count)
		var ty = DoubleArray(count)
		for (k in 0 until count) {
			val previous = loop[(k - 1 + count) % count]
			val next = loop[(k + 1) % count]
			val length = hypot(next.x - previous.x, next.y - previous.y).coerceAtLeast(GEOMETRY_EPSILON)
			tx[k] = (next.x - previous.x) / length
			ty[k] = (next.y - previous.y) / length
		}
		repeat(passes) {
			val sx = DoubleArray(count)
			val sy = DoubleArray(count)
			for (k in 0 until count) {
				val a = (k - 1 + count) % count
				val b = (k + 1) % count
				val x = tx[a] + tx[k] * 2 + tx[b]
				val y = ty[a] + ty[k] * 2 + ty[b]
				val length = hypot(x, y)
				if (length < 1e-9) { sx[k] = tx[k]; sy[k] = ty[k] } else { sx[k] = x / length; sy[k] = y / length }
			}
			tx = sx; ty = sy
		}
		return DoubleArray(count) { -ty[it] } to DoubleArray(count) { tx[it] }
	}

	/** One [1,2,1] pass over the normals inside [region], leaving the rest of the loop untouched. */
	private fun smoothNormalsIn(nx: DoubleArray, ny: DoubleArray, region: BooleanArray) {
		val count = nx.size
		val sx = nx.copyOf()
		val sy = ny.copyOf()
		for (k in 0 until count) {
			if (!region[k]) continue
			val a = (k - 1 + count) % count
			val b = (k + 1) % count
			val x = nx[a] + nx[k] * 2 + nx[b]
			val y = ny[a] + ny[k] * 2 + ny[b]
			val length = hypot(x, y)
			if (length > 1e-9) { sx[k] = x / length; sy[k] = y / length }
		}
		sx.copyInto(nx)
		sy.copyInto(ny)
	}

	/** Lowers values so they change by at most [slope] per unit of arc length around the cycle. */
	fun minSmooth(values: DoubleArray, arcs: DoubleArray, slope: Double) {
		val count = values.size
		repeat(2) {
			for (k in 1..count) {
				val i = k % count
				val previous = k - 1
				values[i] = min(values[i], values[previous] + slope * arcs[previous])
			}
			for (k in count - 1 downTo 0) {
				val next = (k + 1) % count
				values[k] = min(values[k], values[next] + slope * arcs[k])
			}
		}
	}

	/** Raises values so they change by at most [slope] per unit of arc length around the cycle. */
	fun maxSmooth(values: DoubleArray, arcs: DoubleArray, slope: Double) {
		val count = values.size
		repeat(2) {
			for (k in 1..count) {
				val i = k % count
				val previous = k - 1
				values[i] = max(values[i], values[previous] - slope * arcs[previous])
			}
			for (k in count - 1 downTo 0) {
				val next = (k + 1) % count
				values[k] = max(values[k], values[next] - slope * arcs[k])
			}
		}
	}

	/** Interleaved sample indices of strip triangles whose height is below [ratio] times their longest edge. */
	private fun badSamples(rows: List<List<Point>>, staggered: BooleanArray, ratio: Double): Set<Int> {
		val points = rows.flatten()
		var cursor = 0
		val ids = rows.map { row -> IntArray(row.size) { cursor + it }.also { cursor += row.size } }
		val sampleOf = IntArray(points.size)
		for (r in rows.indices) for (i in ids[r].indices) sampleOf[ids[r][i]] = i * 2 + if (staggered[r]) 1 else 0
		val folded = mutableSetOf<Int>()
		for (t in stripTriangles(ids, staggered)) {
			val a = points[t.a]; val b = points[t.b]; val c = points[t.c]
			val longest = max(distanceSquared(a, b), max(distanceSquared(b, c), distanceSquared(c, a)))
			if (cross(a, b, c) <= max(GEOMETRY_EPSILON, longest * ratio)) {
				folded += sampleOf[t.a]; folded += sampleOf[t.b]; folded += sampleOf[t.c]
			}
		}
		return folded
	}
}
