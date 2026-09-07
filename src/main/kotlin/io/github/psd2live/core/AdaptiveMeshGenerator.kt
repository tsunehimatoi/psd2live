package io.github.psd2live.core

import org.umamo.format.art.analyzeAlpha
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.sin

/** Silhouette-constrained mesh: two edge bands around a Bezier guide, sparse triangular interior.
 * Outer rings and holes share the same material-side winding and constrained topology.
 */
internal object AdaptiveMeshGenerator {
	internal data class Result(
		/** Raster-local x/y pairs, in source pixels. */
		val positions: FloatArray,
		val indices: IntArray,
		/** Global vertex indices for tests/integrity checks; closure is implicit. */
		val boundaryLoops: List<IntArray>,
		/** Always empty: the Bezier guide is a placement guide, never a mesh seam. */
		val guideLoops: List<IntArray> = emptyList(),
		/** The second real envelope row, constrained to the interior triangulation. */
		val innerLoops: List<IntArray> = emptyList(),
		/** Open, longitudinal paths for narrow ribbon meshes (not boundary loops). */
		val spinePaths: List<IntArray> = emptyList(),
	)

	private data class Point(val x: Double, val y: Double)
	private data class Triangle(val a: Int, val b: Int, val c: Int)
	private data class Edge(val low: Int, val high: Int)
	private data class Cubic(val p0: Point, val c1: Point, val c2: Point, val p1: Point)
	private data class LocalMesh(val points: List<Point>, val triangles: List<Triangle>)

	private const val FILTER_PASSES = 2
	private const val FILTER_MAX_OFFSET = 0.55
	private const val CURVE_CHORD_ERROR = 0.85
	private const val MAX_CURVE_DENSITY = 12.0
	private const val MAX_BOUNDARY_POINTS_PER_LOOP = 480
	private const val MAX_QUALITY_REFINEMENT_POINTS = 512
	private const val MAX_INTERNAL_EDGE_FACTOR = 1.72
	private const val GEOMETRY_EPSILON = 1e-8

	/** Occupancy is read from the hardened alpha; enclosed transparency stays empty. */
	private class SolidAlphaMask(val width: Int, val height: Int, val rgba: ByteArray) {
		fun isSolid(x: Int, y: Int): Boolean = x in 0 until width && y in 0 until height &&
			(rgba[(y * width + x) * 4 + 3].toInt() and 0xff) != 0
	}

	/** [spacing] controls the edge guides; [interiorSpacing] controls the staggered triangular fill.
	 * The default interior step is 35% larger (about 45% fewer bulk samples for a large region).
	 */
	fun generate(
		width: Int,
		height: Int,
		rgba: ByteArray,
		alphaThreshold: Int,
		settings: MeshSettings,
	): Result? = generate(
		width = width,
		height = height,
		rgba = rgba,
		alphaThreshold = alphaThreshold,
		spacing = settings.maxEdgeDistance,
		interiorSpacing = settings.interiorDensity,
		outerMargin = settings.outerMargin,
		innerMargin = settings.innerMargin,
		innerMarginEnabled = settings.innerMarginEnabled,
	)

	fun generate(
		width: Int,
		height: Int,
		rgba: ByteArray,
		alphaThreshold: Int,
		spacing: Float,
		interiorSpacing: Float = spacing * 1.35f,
		outerMargin: Float = min(2.75f, max(0.8f, spacing * 0.10f)),
		innerMargin: Float = min(2.75f, max(0.8f, spacing * 0.10f)),
		innerMarginEnabled: Boolean = true,
	): Result? {
		if (width <= 0 || height <= 0 || width.toLong() * height * 4 > rgba.size ||
			!spacing.isFinite() || !interiorSpacing.isFinite() ||
			!outerMargin.isFinite() || !innerMargin.isFinite()) return null
		val threshold = alphaThreshold.coerceIn(1, 255)
		val hardened = AlphaEdgePreprocessor.process(width, height, rgba, threshold)
		val geometryRgba = hardened?.rgba ?: ByteArray(rgba.size)
		// Smoothing must neither bridge a transparent gap nor erase a bright one-pixel stroke
		// beside a much thicker island. Texture alpha itself remains untouched.
		for (pixel in 0 until width * height) {
			val offset = pixel * 4 + 3
			val sourceAlpha = rgba[offset].toInt() and 0xff
			if (sourceAlpha < threshold) geometryRgba[offset] = 0
			else if (sourceAlpha >= (hardened?.hardThreshold ?: threshold)) geometryRgba[offset] = -1
		}
		// One-pixel tolerance can simplify a one-pixel rectangle into a triangle, clipping half
		// of a hairline before meshing even starts. Keep subpixel contour accuracy here.
		val alpha = analyzeAlpha(width, height, geometryRgba, 1, contourEpsilon = 0.35f) ?: return null
		// Both analyses trace the same mask in the same order. Coarser fit controls suppress
		// raster staircase curvature while the precise rings retain coverage and ownership.
		val fitContours = analyzeAlpha(width, height, geometryRgba, 1, contourEpsilon = 1.0f)?.contours ?: return null
		val fitSources = alpha.contours.zip(fitContours).associate { (exact, fit) -> exact.points to fit.points }
		val outerCandidates = alpha.contours.filter { !it.isHole && it.points.size >= 6 }
		if (outerCandidates.isEmpty()) return null
		val areas = outerCandidates.map { contourArea(it.points) }
		val largestIndex = areas.indices.maxByOrNull { areas[it] } ?: return null
		val minimumSecondaryArea = max(6.0, min(24.0, areas[largestIndex] * 0.0002))
		val outerContours = outerCandidates.filterIndexed { index, _ ->
			index == largestIndex || areas[index] >= minimumSecondaryArea
		}
		if (outerContours.isEmpty()) return null

		val solidMask = SolidAlphaMask(width, height, geometryRgba)
		val budgetSpacing = sqrt(width.toDouble() * height / (1_200.0 * 0.8660254037844386))
		val edgeSpacing = max(6.0, spacing.toDouble())
		val gridSpacing = max(max(6.0, interiorSpacing.toDouble()), budgetSpacing)
		val sources = outerContours.map { it.points }
		val holesByOuter = Array(sources.size) { mutableListOf<IntArray>() }
		for (hole in alpha.contours.filter { it.isHole && it.points.size >= 6 }) {
			val probe = Point(hole.points[0].toDouble(), hole.points[1].toDouble())
			val owner = sources.indices.filter { pointInPolygon(probe, sourcePoints(sources[it])) }
				.minByOrNull { contourArea(sources[it]) } ?: continue
			holesByOuter[owner] += hole.points
		}
		val gridCandidates = sampleInterior(solidMask, gridSpacing)
		val globalPoints = mutableListOf<Point>()
		val globalTriangles = mutableListOf<Triangle>()
		val globalBoundaryLoops = mutableListOf<IntArray>()
		val globalGuides = mutableListOf<IntArray>()
		val globalInnerLoops = mutableListOf<IntArray>()
		val globalSpines = mutableListOf<IntArray>()
		val completed = mutableListOf<BandedMesh>()
		val domains = sources.indices.map { island ->
			val raw = (listOf(sources[island]) + holesByOuter[island]).mapIndexed { index, contour ->
				orient(sourcePoints(contour), index == 0)
			}
			var guides = (listOf(sources[island]) + holesByOuter[island]).mapIndexed { index, contour ->
				orient(buildBezierBoundary(fitSources[contour] ?: contour, edgeSpacing), index == 0)
			}
			// A fitted loop must preserve the whole domain, not just be individually simple.
			if (!validDomain(guides)) guides = raw
			raw to guides
		}
		// Independent Bezier fits can overshoot across a narrow gap between separate islands.
		// Compare against both fitted and raw neighbors before adding any offset bands.
		val safeDomains = domains.mapIndexed { index, domain ->
			val collision = domains.withIndex().any { (otherIndex, other) ->
				otherIndex != index && domain.second.any { loop ->
					(other.first + other.second).any { loopsTouch(loop, it) }
				}
			}
			if (collision) domain.first to domain.first else domain
		}
		for ((island, domain) in safeDomains.withIndex()) {
			val (raw, guides) = domain
			val neighborDomains = safeDomains.filterIndexed { index, _ -> index != island }
			val neighbors = neighborDomains.flatMap { it.first + it.second }
			fun isolated(mesh: BandedMesh, allowBoundaryContact: Boolean = false): Boolean {
				val borders = mesh.boundaries.map { loop -> loop.map { mesh.mesh.points[it] } }
				return neighborDomains.none { domainsOverlap(borders, it.first, allowBoundaryContact) ||
					domainsOverlap(borders, it.second, allowBoundaryContact) } &&
					completed.none { previous -> domainsOverlap(borders,
						previous.boundaries.map { loop -> loop.map { previous.mesh.points[it] } }, allowBoundaryContact) }
			}
			var built: BandedMesh? = null
			if (raw.size == 1) {
				for (scale in doubleArrayOf(1.0, 0.5, 0.25, 0.0)) {
					val ribbon = buildRibbon(raw.single(), neighbors, width, height, edgeSpacing, scale,
						samplingScale = if (scale < 0.5) 0.25 else 1.0)
					if (ribbon != null && isolated(ribbon)) { built = ribbon; break }
				}
			}
			for (scale in doubleArrayOf(1.0, 0.5, 0.25, 0.125, 0.0625)) {
				if (built != null) break
				val band = buildBands(guides, gridCandidates, width, height, edgeSpacing, gridSpacing, scale, neighbors,
					outerMargin.toDouble(), innerMargin.toDouble(), innerMarginEnabled)
				if (band != null && isolated(band)) built = band
			}
			if (built == null) {
				// Contour-preserving recovery, never replace a valid silhouette with its bounds.
				for (recoveryLoops in listOf(guides, raw)) {
					val local = triangulateDomain(recoveryLoops, gridCandidates, gridSpacing) ?: continue
					var cursor = 0
					val loops = recoveryLoops.map { loop -> IntArray(loop.size) { cursor + it }.also { cursor += loop.size } }
					val recovery = BandedMesh(local, loops, emptyList(), emptyList())
					// Raster islands can meet at a single corner. Keep their vertex indices separate;
					// rejecting that harmless contact would trigger the caller's rectangular fallback.
					if (isolated(recovery, allowBoundaryContact = true)) { built = recovery; break }
				}
			}
			if (built == null) return null
			completed += built
			val offset = globalPoints.size
			globalPoints += built.mesh.points
			globalTriangles += built.mesh.triangles.map { Triangle(it.a + offset, it.b + offset, it.c + offset) }
			fun shifted(loops: List<IntArray>) = loops.map { loop -> IntArray(loop.size) { loop[it] + offset } }
			globalBoundaryLoops += shifted(built.boundaries)
			globalGuides += shifted(built.guides)
			globalInnerLoops += shifted(built.inner)
			globalSpines += shifted(built.spines)
		}

		if (globalTriangles.isEmpty() || globalPoints.size >= 65_535) return null

		val positions = FloatArray(globalPoints.size * 2)
		for (index in globalPoints.indices) {
			positions[index * 2] = globalPoints[index].x.toFloat()
			positions[index * 2 + 1] = globalPoints[index].y.toFloat()
		}
		val indices = IntArray(globalTriangles.size * 3)
		for (index in globalTriangles.indices) {
			val triangle = globalTriangles[index]
			indices[index * 3] = triangle.a
			if (cross(globalPoints[triangle.a], globalPoints[triangle.b], globalPoints[triangle.c]) < 0.0) {
				indices[index * 3 + 1] = triangle.b
				indices[index * 3 + 2] = triangle.c
			} else {
				indices[index * 3 + 1] = triangle.c
				indices[index * 3 + 2] = triangle.b
			}
		}
		return Result(positions, indices, globalBoundaryLoops, globalGuides, globalInnerLoops, globalSpines)
	}

	private data class BandedMesh(
		val mesh: LocalMesh,
		val boundaries: List<IntArray>,
		val guides: List<IntArray>,
		val inner: List<IntArray>,
		val spines: List<IntArray> = emptyList(),
	)

	private fun domainsOverlap(
		a: List<List<Point>>, b: List<List<Point>>, allowBoundaryContact: Boolean = false,
	): Boolean {
		fun inside(p: Point, domain: List<List<Point>>): Boolean =
			inDomain(p, domain) && domain.all { distanceSquaredToLoop(p, it) > 1e-12 }
		if (a.any { first -> b.any { second ->
			if (!allowBoundaryContact) loopsTouch(first, second)
			else first.indices.any { i -> second.indices.any { j -> segmentsProperlyIntersect(
				first[i], first[(i + 1) % first.size], second[j], second[(j + 1) % second.size]) } }
		} }) return true
		return a.first().any { inside(it, b) } || b.first().any { inside(it, a) }
	}

	/** A monotone slender island is a ribbon, not a tiny two-dimensional fill region.
	 * Cross sections follow its principal axis. Their envelope covers every source vertex;
	 * available transparent space buys better triangle proportions without joining islands.
	 * Branched shapes and holes continue through the general constrained mesher.
	 */
	private fun buildRibbon(
		boundary: List<Point>, neighbors: List<List<Point>>, width: Int, height: Int,
		spacing: Double, expansionScale: Double, samplingScale: Double,
	): BandedMesh? {
		val meanX = boundary.map { it.x }.average()
		val meanY = boundary.map { it.y }.average()
		val xx = boundary.sumOf { (it.x - meanX) * (it.x - meanX) }
		val yy = boundary.sumOf { (it.y - meanY) * (it.y - meanY) }
		val xy = boundary.sumOf { (it.x - meanX) * (it.y - meanY) }
		val principalAngle = atan2(2 * xy, xx - yy) * 0.5
		// Near an axis, use exact raster-aligned sections. An insignificant PCA tilt can cut
		// the same one-pixel staircase several times and falsely classify it as a branch.
		val angle = when {
			abs(sin(principalAngle)) < 0.08 -> 0.0
			abs(cos(principalAngle)) < 0.08 -> Math.PI * 0.5
			else -> principalAngle
		}
		val axisX = cos(angle)
		val axisY = sin(angle)
		val projected = boundary.map { Point(it.x * axisX + it.y * axisY, -it.x * axisY + it.y * axisX) }
		val start = projected.minOf { it.x }
		val end = projected.maxOf { it.x }
		val length = end - start
		val thickness = abs(signedAreaTwice(boundary)) / (2 * length.coerceAtLeast(1e-8))
		if (thickness <= 0 || length < thickness * 7 || thickness > spacing * 0.45) return null
		fun section(x: Double): List<Double> {
			val hits = mutableListOf<Double>()
			for (i in projected.indices) {
				val a = projected[i]
				val b = projected[(i + 1) % projected.size]
				if (x < min(a.x, b.x) - 1e-7 || x > max(a.x, b.x) + 1e-7) continue
				if (abs(a.x - b.x) < 1e-8) { hits += a.y; hits += b.y }
				else hits += a.y + ((x - a.x) / (b.x - a.x)).coerceIn(0.0, 1.0) * (b.y - a.y)
			}
			val unique = mutableListOf<Double>()
			for (y in hits.sorted()) if (unique.isEmpty() || y - unique.last() > 1e-6) unique += y
			return unique
		}
		// Test every interval where the number of intersections could change, not only the
		// emitted stations: a narrow branch must never be silently bridged by a ribbon.
		val events = projected.map { it.x }.distinct().sorted()
		if (events.zipWithNext().any { (a, b) ->
			if (b - a <= 1e-6) false
			else {
				val hits = section((a + b) * 0.5)
				// Oblique sections can graze a raster stair and produce two tiny intervals.
				// Permit only subpixel gaps within this island; real forks still use the general mesh.
				hits.size < 2 || hits.size % 2 != 0 || (1 until hits.lastIndex step 2).any { hits[it + 1] - hits[it] > 0.75 }
			}
		}) return null
		val clearance = boundary.minOf { p -> neighbors.minOfOrNull { sqrt(distanceSquaredToLoop(p, it)) }
			?: Double.POSITIVE_INFINITY }
		val margin = min(min(6.0, spacing * 0.16), clearance * 0.24) * expansionScale
		// Tightly cropped textures may have no transparent room for widening. Spend vertices
		// along their length instead; clamping a wide ribbon must not restore needle triangles.
		val rasterThickness = width * abs(axisY) + height * abs(axisX)
		val availableThickness = min(thickness + margin * 2, rasterThickness)
		val step = min(spacing * 0.5, max(3.0, availableThickness * 1.25)) * samplingScale
		val intervals = ceil(length / step).toInt().coerceIn(2, MAX_BOUNDARY_POINTS_PER_LOOP)
		val stations = List(intervals + 1) { start + length * it / intervals }
		val lows = DoubleArray(stations.size)
		val highs = DoubleArray(stations.size)
		for (i in stations.indices) {
			val hits = section(stations[i])
			if (hits.isEmpty()) return null
			lows[i] = hits.first()
			highs[i] = hits.last()
		}
		// Expand adjacent sections enough to contain bends between stations. Since the source
		// edges and output edges are linear, checking all source vertices bounds the full contour.
		for (i in 0 until intervals) {
			var lowerError = 0.0
			var upperError = 0.0
			for (p in projected) {
				if (p.x < stations[i] || p.x > stations[i + 1]) continue
				val t = (p.x - stations[i]) / (stations[i + 1] - stations[i])
				lowerError = max(lowerError, lows[i] * (1 - t) + lows[i + 1] * t - p.y)
				upperError = max(upperError, p.y - highs[i] * (1 - t) - highs[i + 1] * t)
			}
			lows[i] -= lowerError; lows[i + 1] -= lowerError
			highs[i] += upperError; highs[i + 1] += upperError
		}
		val points = mutableListOf<Point>()
		for (i in stations.indices) {
			val center = (lows[i] + highs[i]) * 0.5
			val halfWidth = max((highs[i] - lows[i]) * 0.5 + margin, 0.25)
			// The center is only the conceptual Bezier/spine location. It is deliberately not
			// emitted as a vertex row: the two envelope sides are the complete mesh boundary.
			for (side in listOf(-1, 1)) {
				val y = center + halfWidth * side
				points += snap(Point((stations[i] * axisX - y * axisY).coerceIn(0.0, width.toDouble()),
					(stations[i] * axisY + y * axisX).coerceIn(0.0, height.toDouble())))
			}
		}
		val triangles = mutableListOf<Triangle>()
		for (i in 0 until intervals) {
			val a = i * 2; val b = a + 2; val c = b + 1; val d = a + 1
			if (i % 2 == 0) { triangles += Triangle(a, b, c); triangles += Triangle(a, c, d) }
			else { triangles += Triangle(a, b, d); triangles += Triangle(b, c, d) }
		}
		if (triangles.any { cross(points[it.a], points[it.b], points[it.c]) <= 1e-5 }) return null
		val border = (stations.indices.map { it * 2 } +
			stations.indices.reversed().map { it * 2 + 1 }).toIntArray()
		if (!validDomain(listOf(border.map { points[it] }))) return null
		return BandedMesh(LocalMesh(points, triangles), listOf(border), emptyList(), emptyList())
	}

	private fun sourcePoints(source: IntArray): List<Point> =
		List(source.size / 2) { Point(source[it * 2].toDouble(), source[it * 2 + 1].toDouble()) }

	private fun orient(loop: List<Point>, positive: Boolean): List<Point> =
		(if ((signedAreaTwice(loop) > 0) == positive) loop else loop.reversed()).map(::snap)

	// Construct topology at exported precision to avoid faces collapsing on Float conversion.
	private fun snap(point: Point): Point = Point(point.x.toFloat().toDouble(), point.y.toFloat().toDouble())

	private fun inDomain(point: Point, loops: List<List<Point>>): Boolean =
		pointInPolygon(point, loops.first()) && loops.drop(1).none { pointInPolygon(point, it) }

	private fun loopsTouch(a: List<Point>, b: List<Point>): Boolean = a.indices.any { i ->
		b.indices.any { j ->
			val p = a[i]; val q = a[(i + 1) % a.size]
			val r = b[j]; val s = b[(j + 1) % b.size]
			segmentsProperlyIntersect(p, q, r, s) || pointOnSegment(p, r, s) ||
				pointOnSegment(q, r, s) || pointOnSegment(r, p, q) || pointOnSegment(s, p, q)
		}
	}

	private fun validDomain(loops: List<List<Point>>): Boolean {
		if (loops.isEmpty() || loops.any { it.size < 3 || abs(signedAreaTwice(it)) < 1e-6 || hasSelfIntersection(it) }) return false
		for (i in loops.indices) for (j in 0 until i) {
			if (loopsTouch(loops[i], loops[j])) return false
			if (j == 0 && !pointInPolygon(loops[i][0], loops[0])) return false
			if (j > 0 && (pointInPolygon(loops[i][0], loops[j]) || pointInPolygon(loops[j][0], loops[i]))) return false
		}
		return true
	}

	/** Signed material-side offset. Winding makes the same code work for holes and islands. */
	private fun offsetLoop(
		loop: List<Point>, amount: Double, width: Int, height: Int, barriers: List<List<Point>>,
	): List<Point> =
		loop.indices.map { i ->
			val p = loop[(i + loop.size - 1) % loop.size]; val q = loop[i]; val r = loop[(i + 1) % loop.size]
			val incoming = distance(p, q).coerceAtLeast(1e-8)
			val outgoing = distance(q, r).coerceAtLeast(1e-8)
			val nx = -(q.y - p.y) / incoming - (r.y - q.y) / outgoing
			val ny = (q.x - p.x) / incoming + (r.x - q.x) / outgoing
			val length = hypot(nx, ny).coerceAtLeast(1e-8)
			// Bound the miter near acute tips; domain validation shrinks crowded bands further.
			val clearance = barriers.filter { it !== loop }.minOfOrNull { sqrt(distanceSquaredToLoop(q, it)) }
				?: Double.POSITIVE_INFINITY
			val limit = min(min(incoming, outgoing), clearance) * 0.3
			val localAmount = amount.coerceIn(-limit, limit)
			snap(Point((q.x + nx / length * localAmount).coerceIn(0.0, width.toDouble()),
				(q.y + ny / length * localAmount).coerceIn(0.0, height.toDouble())))
		}

	private fun buildBands(
		guides: List<List<Point>>, candidates: List<Point>, width: Int, height: Int,
		edgeSpacing: Double, interiorSpacing: Double, scale: Double, neighbors: List<List<Point>>,
		outerMargin: Double = min(2.75, max(0.8, edgeSpacing * 0.10)),
		innerMargin: Double = min(2.75, max(0.8, edgeSpacing * 0.10)),
		innerMarginEnabled: Boolean = true,
	): BandedMesh? {
		if (innerMarginEnabled) {
			val outerDist = outerMargin * scale
			val innerDist = innerMargin * scale
			val outer = guides.map { offsetLoop(it, -outerDist, width, height, guides + neighbors) }
			val inner = guides.map { offsetLoop(it, innerDist, width, height, guides + neighbors) }
			if (!validDomain(outer) || !validDomain(inner)) return null
			val core = triangulateDomain(inner, candidates, interiorSpacing) ?: return null
			val points = core.points.toMutableList()
			val triangles = core.triangles.toMutableList()
			val lookup = points.withIndex().associate { it.value to it.index }.toMutableMap()
			fun vertices(loop: List<Point>) = loop.map { p -> lookup.getOrPut(p) { points.add(p); points.lastIndex } }.toIntArray()
			val innerIds = inner.map { vertices(it) }
			val outerIds = outer.map { vertices(it) }
			fun strip(a: IntArray, b: IntArray): Boolean {
				for (i in a.indices) {
					val j = (i + 1) % a.size
					// Alternating diagonals avoid a directional bias along the two edge bands.
					val faces = if (i % 2 == 0) listOf(Triangle(a[i], a[j], b[i]), Triangle(a[j], b[j], b[i]))
						else listOf(Triangle(a[i], a[j], b[j]), Triangle(a[i], b[j], b[i]))
					for (t in faces) {
						if (t.a == t.b || t.b == t.c || t.c == t.a) continue // clipped at the canvas boundary
						if (cross(points[t.a], points[t.b], points[t.c]) <= GEOMETRY_EPSILON) return false
						triangles += t
					}
				}
				return true
			}
			for (i in guides.indices) if (!strip(outerIds[i], innerIds[i])) return null
			val uses = triangles.flatMap { triangleEdges(it) }.groupingBy { it }.eachCount()
			val boundaryEdges = outerIds.flatMap { ids -> ids.indices.map { edgeOf(ids[it], ids[(it + 1) % ids.size]) } }.toSet()
			if (uses.any { (edge, count) -> count != if (edge in boundaryEdges) 1 else 2 }) return null
			return BandedMesh(LocalMesh(points, triangles), outerIds, emptyList(), innerIds)
		} else {
			val outerDist = outerMargin * scale
			val outer = if (outerDist > 1e-4) guides.map { offsetLoop(it, -outerDist, width, height, guides + neighbors) } else guides
			if (!validDomain(outer)) return null
			val core = triangulateDomain(outer, candidates, interiorSpacing) ?: return null
			var cursor = 0
			val outerIds = outer.map { loop -> IntArray(loop.size) { cursor + it }.also { cursor += loop.size } }
			return BandedMesh(core, outerIds, emptyList(), emptyList())
		}
	}

	/** Join holes by visible, non-crossing bridges, reusing endpoint indices on both sides.
	 * Ear clipping operates on this weakly simple walk; the final mesh has no duplicate seam.
	 */
	private fun bridgeHoles(loops: List<List<Point>>): List<Int>? {
		val points = loops.flatten()
		var offset = 0
		val ids = loops.map { loop -> List(loop.size) { offset + it }.also { offset += loop.size } }
		val path = ids.first().toMutableList()
		for (hole in ids.drop(1).sortedByDescending { ring -> ring.maxOf { points[it].x } }) {
			val h = hole.maxBy { points[it].x }
			val a = points[h]
			val visible = path.distinct().sortedBy { distanceSquared(a, points[it]) }.firstOrNull { v ->
				val b = points[v]
				inDomain(lerp(a, b, 0.5), loops) && (ids + listOf(path)).all { ring ->
					ring.indices.all { i ->
						val p = ring[i]; val q = ring[(i + 1) % ring.size]
						!segmentsProperlyIntersect(a, b, points[p], points[q]) &&
							(p == h || p == v || !pointOnSegment(points[p], a, b)) &&
							(q == h || q == v || !pointOnSegment(points[q], a, b))
					}
				}
			} ?: return null
			val at = path.indexOf(visible)
			val start = hole.indexOf(h)
			val walk = List(hole.size) { hole[(start + it) % hole.size] } + listOf(h, visible)
			path.addAll(at + 1, walk)
		}
		return path
	}

	private fun buildBezierBoundary(
		source: IntArray,
		spacing: Double,
	): List<Point> {
		var controls = List(source.size / 2) { index ->
			Point(source[index * 2].toDouble(), source[index * 2 + 1].toDouble())
		}
		repeat(FILTER_PASSES) { controls = filterControls(controls) }
		// Offset rows are built separately; the Bezier guide remains on the silhouette.

		// Reduce handle strength if a highly concave silhouette would make the fitted curve cross
		// itself. Even the zero-handle fallback remains a cubic Bezier representation of the ring.
		for (handleStrength in doubleArrayOf(1.0, 0.55, 0.25, 0.0)) {
			val sampled = samplePeriodicBezier(controls, spacing, handleStrength)
			val hasCollapsedEdge = sampled.indices.any { index ->
				distance(sampled[index], sampled[(index + 1) % sampled.size]) < 0.15
			}
			if (sampled.size >= 3 && !hasCollapsedEdge && !hasSelfIntersection(sampled)) return sampled
		}
		return sourcePoints(source)
	}

	/** Bounded low-pass filter: removes raster stair steps without deleting or merging controls. */
	private fun filterControls(source: List<Point>): List<Point> {
		if (source.size < 3) return source
		return List(source.size) { index ->
			val previous = source[(index - 1 + source.size) % source.size]
			val current = source[index]
			val next = source[(index + 1) % source.size]
			val incomingX = current.x - previous.x
			val incomingY = current.y - previous.y
			val outgoingX = next.x - current.x
			val outgoingY = next.y - current.y
			val incomingLength = hypot(incomingX, incomingY).coerceAtLeast(GEOMETRY_EPSILON)
			val outgoingLength = hypot(outgoingX, outgoingY).coerceAtLeast(GEOMETRY_EPSILON)
			val cosine = ((incomingX * outgoingX + incomingY * outgoingY) /
				(incomingLength * outgoingLength)).coerceIn(-1.0, 1.0)
			val turnFraction = acos(cosine) / Math.PI
			val targetX = (previous.x + current.x * 2.0 + next.x) * 0.25
			val targetY = (previous.y + current.y * 2.0 + next.y) * 0.25
			val deltaX = targetX - current.x
			val deltaY = targetY - current.y
			val deltaLength = hypot(deltaX, deltaY)
			val maximumMove = FILTER_MAX_OFFSET * (1.0 - turnFraction * 0.62)
			val scale = if (deltaLength > maximumMove && deltaLength > GEOMETRY_EPSILON) {
				maximumMove / deltaLength
			} else {
				1.0
			}
			Point(current.x + deltaX * scale, current.y + deltaY * scale)
		}
	}


	private fun samplePeriodicBezier(
		controls: List<Point>,
		spacing: Double,
		handleStrength: Double,
	): List<Point> {
		if (controls.size < 3) return emptyList()
		val tangents = List(controls.size) { index ->
			val previous = controls[(index - 1 + controls.size) % controls.size]
			val next = controls[(index + 1) % controls.size]
			val length = hypot(next.x - previous.x, next.y - previous.y).coerceAtLeast(GEOMETRY_EPSILON)
			Point((next.x - previous.x) / length, (next.y - previous.y) / length)
		}
		val handles = DoubleArray(controls.size) { index ->
			val previous = controls[(index - 1 + controls.size) % controls.size]
			val current = controls[index]
			val next = controls[(index + 1) % controls.size]
			val incomingX = current.x - previous.x
			val incomingY = current.y - previous.y
			val outgoingX = next.x - current.x
			val outgoingY = next.y - current.y
			val incomingLength = hypot(incomingX, incomingY).coerceAtLeast(GEOMETRY_EPSILON)
			val outgoingLength = hypot(outgoingX, outgoingY).coerceAtLeast(GEOMETRY_EPSILON)
			val cosine = ((incomingX * outgoingX + incomingY * outgoingY) /
				(incomingLength * outgoingLength)).coerceIn(-1.0, 1.0)
			val turn = acos(cosine)
			val turnFraction = turn / Math.PI
			min(incomingLength, outgoingLength) * 0.24 * (1.0 - turnFraction * 0.80) * handleStrength
		}
		// A one-pixel raster stair can have a large immediate turn but is not a real silhouette
		// feature. Measure critical turns across a physical support window instead. This preserves
		// fingertip/valley corners while preventing smooth antialiased arcs from creating clusters.
		val structuralSupport = min(10.0, max(3.5, spacing * 0.14))
		val structuralTurns = DoubleArray(controls.size) { index ->
			supportedTurn(controls, index, structuralSupport)
		}
		val cubics = List(controls.size) { index ->
			val next = (index + 1) % controls.size
			val chord = hypot(controls[next].x - controls[index].x, controls[next].y - controls[index].y)
			val firstHandle = min(handles[index], chord / 3.0)
			val secondHandle = min(handles[next], chord / 3.0)
			Cubic(
				controls[index],
				Point(
					controls[index].x + tangents[index].x * firstHandle,
					controls[index].y + tangents[index].y * firstHandle,
				),
				Point(
					controls[next].x - tangents[next].x * secondHandle,
					controls[next].y - tangents[next].y * secondHandle,
				),
				controls[next],
			)
		}

		// Build a dense temporary evaluation of the high-order curve. It is only a measuring tape;
		// emitted vertices are resampled once below and never deduplicated or mixed with controls.
		val dense = mutableListOf<Point>()
		val denseControlIndices = IntArray(cubics.size)
		for ((cubicIndex, cubic) in cubics.withIndex()) {
			denseControlIndices[cubicIndex] = dense.size
			val controlLength = distance(cubic.p0, cubic.c1) + distance(cubic.c1, cubic.c2) + distance(cubic.c2, cubic.p1)
			val measurementStep = max(1.25, min(3.0, spacing * 0.08))
			val subdivisions = ceil(controlLength / measurementStep).toInt().coerceIn(6, 128)
			for (step in 0 until subdivisions) dense += evaluate(cubic, step.toDouble() / subdivisions)
		}
		if (dense.size < 3) return emptyList()

		val localDensity = DoubleArray(dense.size) { index ->
			val previous = dense[(index - 1 + dense.size) % dense.size]
			val current = dense[index]
			val next = dense[(index + 1) % dense.size]
			val incomingX = current.x - previous.x
			val incomingY = current.y - previous.y
			val outgoingX = next.x - current.x
			val outgoingY = next.y - current.y
			val incomingLength = hypot(incomingX, incomingY).coerceAtLeast(GEOMETRY_EPSILON)
			val outgoingLength = hypot(outgoingX, outgoingY).coerceAtLeast(GEOMETRY_EPSILON)
			val cosine = ((incomingX * outgoingX + incomingY * outgoingY) /
				(incomingLength * outgoingLength)).coerceIn(-1.0, 1.0)
			val turn = acos(cosine)
			val localLength = (incomingLength + outgoingLength) * 0.5
			val curvaturePerPixel = turn / localLength
			val curvatureSpacing = if (curvaturePerPixel <= 1e-7) {
				spacing
			} else {
				sqrt(8.0 * CURVE_CHORD_ERROR / curvaturePerPixel)
			}
			val minimumLocalSpacing = max(3.5, spacing / MAX_CURVE_DENSITY)
			val targetSpacing = curvatureSpacing.coerceIn(minimumLocalSpacing, spacing)
			(spacing / targetSpacing).coerceIn(1.0, MAX_CURVE_DENSITY)
		}
		val cumulative = DoubleArray(dense.size + 1)
		for (index in dense.indices) {
			val next = (index + 1) % dense.size
			val density = (localDensity[index] + localDensity[next]) * 0.5
			cumulative[index + 1] = cumulative[index] + distance(dense[index], dense[next]) * density
		}
		val mandatory = sortedSetOf(0)
		for (index in controls.indices) {
			if (structuralTurns[index] >= 0.70) mandatory += denseControlIndices[index]
		}
		return resampleMetricArcs(dense, cumulative, mandatory.toList(), spacing)
	}

	private fun supportedTurn(points: List<Point>, index: Int, support: Double): Double {
		var before = index
		var beforeDistance = 0.0
		var steps = 0
		while (beforeDistance < support && steps < points.size - 1) {
			val previous = (before - 1 + points.size) % points.size
			beforeDistance += distance(points[before], points[previous])
			before = previous
			steps++
		}
		var after = index
		var afterDistance = 0.0
		steps = 0
		while (afterDistance < support && steps < points.size - 1) {
			val next = (after + 1) % points.size
			afterDistance += distance(points[after], points[next])
			after = next
			steps++
		}
		val current = points[index]
		val incomingX = current.x - points[before].x
		val incomingY = current.y - points[before].y
		val outgoingX = points[after].x - current.x
		val outgoingY = points[after].y - current.y
		val incomingLength = hypot(incomingX, incomingY).coerceAtLeast(GEOMETRY_EPSILON)
		val outgoingLength = hypot(outgoingX, outgoingY).coerceAtLeast(GEOMETRY_EPSILON)
		val cosine = ((incomingX * outgoingX + incomingY * outgoingY) /
			(incomingLength * outgoingLength)).coerceIn(-1.0, 1.0)
		return acos(cosine)
	}

	/**
	 * Resample each ordered arc between critical curve locations. Critical locations are emitted
	 * exactly once as arc starts; they are not appended afterward and are never deduplicated.
	 */
	private fun resampleMetricArcs(
		dense: List<Point>,
		cumulative: DoubleArray,
		mandatoryIndices: List<Int>,
		spacing: Double,
	): List<Point> {
		val totalMetric = cumulative.last()
		if (totalMetric <= GEOMETRY_EPSILON) return emptyList()
		val result = mutableListOf<Point>()
		for (anchorPosition in mandatoryIndices.indices) {
			val startIndex = mandatoryIndices[anchorPosition]
			val endIndex = mandatoryIndices[(anchorPosition + 1) % mandatoryIndices.size]
			val startMetric = cumulative[startIndex]
			val endMetric = if (endIndex > startIndex) cumulative[endIndex] else totalMetric + cumulative[endIndex]
			val arcMetric = (endMetric - startMetric).coerceAtLeast(GEOMETRY_EPSILON)
			val intervals = ceil(arcMetric / spacing).toInt().coerceAtLeast(if (mandatoryIndices.size == 1) 3 else 1)
			for (interval in 0 until intervals) {
				val target = startMetric + arcMetric * interval / intervals
				result += pointAtMetric(dense, cumulative, target)
			}
		}
		if (result.size > MAX_BOUNDARY_POINTS_PER_LOOP && mandatoryIndices.size < MAX_BOUNDARY_POINTS_PER_LOOP) {
			// Re-run smooth arcs with a larger shared spacing; critical locations remain mandatory.
			return resampleMetricArcs(
				dense,
				cumulative,
				mandatoryIndices,
				spacing * max(1.1, result.size.toDouble() / MAX_BOUNDARY_POINTS_PER_LOOP),
			)
		}
		return result
	}

	private fun pointAtMetric(dense: List<Point>, cumulative: DoubleArray, rawTarget: Double): Point {
		val total = cumulative.last()
		val target = ((rawTarget % total) + total) % total
		var low = 0
		var high = dense.size - 1
		while (low < high) {
			val middle = (low + high + 1) ushr 1
			if (cumulative[middle] <= target) low = middle else high = middle - 1
		}
		val next = (low + 1) % dense.size
		val metric = (cumulative[low + 1] - cumulative[low]).coerceAtLeast(GEOMETRY_EPSILON)
		return lerp(dense[low], dense[next], (target - cumulative[low]) / metric)
	}

	private fun triangulateDomain(
		loops: List<List<Point>>,
		interiorCandidates: List<Point>,
		spacing: Double,
	): LocalMesh? {
		if (!validDomain(loops)) return null
		val points = loops.flatten().toMutableList()
		val path = bridgeHoles(loops) ?: return null
		val triangles = earClip(path.map { points[it] })?.map {
			Triangle(path[it.a], path[it.b], path[it.c])
		}?.toMutableList() ?: return null
		var offset = 0
		val protectedEdges = linkedSetOf<Edge>()
		for (loop in loops) {
			for (i in loop.indices) protectedEdges += edgeOf(offset + i, offset + (i + 1) % loop.size)
			offset += loop.size
		}
		for (candidate in interiorCandidates) {
			if (inDomain(candidate, loops) && loops.all { distanceSquaredToLoop(candidate, it) > 1.0 })
				insertInteriorPoint(candidate, points, triangles)
		}
		relaxToConstrainedDelaunay(points, triangles, protectedEdges)
		var refinementBudget = MAX_QUALITY_REFINEMENT_POINTS
		for (pass in 0 until 4) {
			if (refinementBudget <= 0) break
			val inserted = splitOverlongInternalEdges(
				points,
				triangles,
				protectedEdges,
				spacing,
				refinementBudget,
			)
			if (inserted == 0) break
			refinementBudget -= inserted
			relaxToConstrainedDelaunay(points, triangles, protectedEdges)
		}
		// Reject a failed mesh as a whole: dropping degenerate faces here would create cracks.
		if (triangles.any { abs(cross(points[it.a], points[it.b], points[it.c])) <= GEOMETRY_EPSILON }) return null
		val uses = triangles.flatMap { triangleEdges(it) }.groupingBy { it }.eachCount()
		if (uses.filterValues { it == 1 }.keys != protectedEdges || uses.values.any { it !in 1..2 }) return null
		if (points.size - uses.size + triangles.size != 2 - loops.size) return null
		return LocalMesh(points, triangles)
	}

	/**
	 * A fixed interior grid can miss a thin diagonal corridor. Even after Delaunay convergence, such
	 * a corridor may contain an edge many grid cells long. Bisect only unprotected internal edges;
	 * the ordered Bezier boundary and every `(i,i+1)` constraint remain untouched.
	 */
	private fun splitOverlongInternalEdges(
		points: MutableList<Point>,
		triangles: MutableList<Triangle>,
		protectedEdges: Set<Edge>,
		spacing: Double,
		budget: Int,
	): Int {
		if (budget <= 0) return 0
		val maximumLengthSquared = spacing * spacing * MAX_INTERNAL_EDGE_FACTOR * MAX_INTERNAL_EDGE_FACTOR
		var inserted = 0
		while (inserted < budget) {
			val edges = triangles.asSequence()
				.flatMap { triangle -> triangleEdges(triangle).asSequence() }
				.filter { edge -> edge !in protectedEdges }
				.distinct()
			val longest = edges.maxByOrNull { edge -> distanceSquared(points[edge.low], points[edge.high]) }
				?: break
			val lengthSquared = distanceSquared(points[longest.low], points[longest.high])
			if (lengthSquared <= maximumLengthSquared) break
			val midpoint = lerp(points[longest.low], points[longest.high], 0.5)
			if (!insertInteriorPoint(midpoint, points, triangles)) break
			inserted++
		}
		return inserted
	}

	private fun earClip(boundary: List<Point>): List<Triangle>? {
		if (boundary.size < 3) return null
		val orientation = if (signedAreaTwice(boundary) >= 0.0) 1.0 else -1.0
		val remaining = boundary.indices.toMutableList()
		val triangles = mutableListOf<Triangle>()
		var guard = boundary.size * boundary.size
		while (remaining.size > 3 && guard-- > 0) {
			var clipped = false
			for (position in remaining.indices) {
				val previous = remaining[(position - 1 + remaining.size) % remaining.size]
				val current = remaining[position]
				val next = remaining[(position + 1) % remaining.size]
				if (cross(boundary[previous], boundary[current], boundary[next]) * orientation <= GEOMETRY_EPSILON) continue
				var containsVertex = false
				for (candidate in remaining) {
					if (boundary[candidate] == boundary[previous] || boundary[candidate] == boundary[current] ||
						boundary[candidate] == boundary[next]) continue
					if (pointInTriangleInclusive(boundary[candidate], boundary[previous], boundary[current], boundary[next])) {
						containsVertex = true
						break
					}
				}
				if (containsVertex) continue
				triangles += Triangle(previous, current, next)
				remaining.removeAt(position)
				clipped = true
				break
			}
			if (!clipped) return null
		}
		if (remaining.size == 3 &&
			abs(cross(boundary[remaining[0]], boundary[remaining[1]], boundary[remaining[2]])) > GEOMETRY_EPSILON
		) {
			triangles += Triangle(remaining[0], remaining[1], remaining[2])
		}
		return triangles.takeIf { it.isNotEmpty() }
	}

	private fun insertInteriorPoint(
		rawCandidate: Point,
		points: MutableList<Point>,
		triangles: MutableList<Triangle>,
	): Boolean {
		val candidate = snap(rawCandidate)
		if (points.any { distanceSquared(it, candidate) < 1e-8 }) return false
		val containingIndex = triangles.indexOfFirst { triangle ->
			pointInTriangleInclusive(candidate, points[triangle.a], points[triangle.b], points[triangle.c])
		}
		if (containingIndex < 0) return false
		val containing = triangles[containingIndex]
		val containingEdges = triangleEdges(containing)
		val onEdge = containingEdges.firstOrNull { edge ->
			pointOnSegment(candidate, points[edge.low], points[edge.high])
		}
		val pointIndex = points.size
		if (onEdge == null) {
			if (containingEdges.any { abs(cross(points[it.low], points[it.high], candidate)) < 1e-5 }) return false
			points += candidate
			triangles.removeAt(containingIndex)
			triangles += Triangle(containing.a, containing.b, pointIndex)
			triangles += Triangle(containing.b, containing.c, pointIndex)
			triangles += Triangle(containing.c, containing.a, pointIndex)
			return true
		}

		val adjacentIndices = triangles.indices.filter { index -> onEdge in triangleEdges(triangles[index]) }
		val adjacentTriangles = adjacentIndices.map { triangles[it] }
		if (adjacentTriangles.any { triangle ->
			val opposite = points[oppositeVertex(triangle, onEdge)]
			abs(cross(points[onEdge.low], candidate, opposite)) < 1e-5 ||
				abs(cross(candidate, points[onEdge.high], opposite)) < 1e-5
		}) return false
		points += candidate
		for (index in adjacentIndices.asReversed()) triangles.removeAt(index)
		for (triangle in adjacentTriangles) {
			val opposite = oppositeVertex(triangle, onEdge)
			triangles += Triangle(onEdge.low, pointIndex, opposite)
			triangles += Triangle(pointIndex, onEdge.high, opposite)
		}
		return true
	}

	private fun relaxToConstrainedDelaunay(
		points: List<Point>,
		triangles: MutableList<Triangle>,
		protectedEdges: Set<Edge>,
	) {
		// A fan created by ear clipping a long, narrow strand may advance only one diagonal per pass
		// because adjacent triangles are deliberately not flipped together. A fixed 14-pass cap left
		// the rest of the fan untouched. Scale the cap with topology and stop as soon as it converges.
		val maximumPasses = (triangles.size * 2).coerceIn(32, 512)
		repeat(maximumPasses) {
			val adjacency = linkedMapOf<Edge, MutableList<Int>>()
			for (index in triangles.indices) {
				for (edge in triangleEdges(triangles[index])) adjacency.getOrPut(edge) { mutableListOf() } += index
			}
			val touched = BooleanArray(triangles.size)
			var flips = 0
			for ((edge, uses) in adjacency) {
				if (edge in protectedEdges || uses.size != 2) continue
				val firstIndex = uses[0]
				val secondIndex = uses[1]
				if (touched[firstIndex] || touched[secondIndex]) continue
				val firstOpposite = oppositeVertex(triangles[firstIndex], edge)
				val secondOpposite = oppositeVertex(triangles[secondIndex], edge)
				if (firstOpposite == secondOpposite) continue
				val replacement = edgeOf(firstOpposite, secondOpposite)
				if (replacement in protectedEdges || adjacency.containsKey(replacement)) continue
				if (!convexForFlip(edge, firstOpposite, secondOpposite, points)) continue
				if (!strictlyInCircumcircle(points[secondOpposite], edge.low, edge.high, firstOpposite, points)) continue
				triangles[firstIndex] = Triangle(firstOpposite, secondOpposite, edge.low)
				triangles[secondIndex] = Triangle(secondOpposite, firstOpposite, edge.high)
				touched[firstIndex] = true
				touched[secondIndex] = true
				flips++
			}
			if (flips == 0) return
		}
	}

	private fun convexForFlip(edge: Edge, first: Int, second: Int, points: List<Point>): Boolean {
		val side1 = cross(points[edge.low], points[edge.high], points[first])
		val side2 = cross(points[edge.low], points[edge.high], points[second])
		val other1 = cross(points[first], points[second], points[edge.low])
		val other2 = cross(points[first], points[second], points[edge.high])
		return side1 * side2 < -GEOMETRY_EPSILON && other1 * other2 < -GEOMETRY_EPSILON
	}

	private fun strictlyInCircumcircle(
		point: Point,
		aIndex: Int,
		bIndex: Int,
		cIndex: Int,
		points: List<Point>,
	): Boolean {
		val a = points[aIndex]
		val b = points[bIndex]
		val c = points[cIndex]
		val denominator = 2.0 * (a.x * (b.y - c.y) + b.x * (c.y - a.y) + c.x * (a.y - b.y))
		if (abs(denominator) < GEOMETRY_EPSILON) return false
		val aa = a.x * a.x + a.y * a.y
		val bb = b.x * b.x + b.y * b.y
		val cc = c.x * c.x + c.y * c.y
		val centerX = (aa * (b.y - c.y) + bb * (c.y - a.y) + cc * (a.y - b.y)) / denominator
		val centerY = (aa * (c.x - b.x) + bb * (a.x - c.x) + cc * (b.x - a.x)) / denominator
		val radiusSquared = distanceSquared(Point(centerX, centerY), a)
		val pointSquared = distanceSquared(Point(centerX, centerY), point)
		return pointSquared < radiusSquared - max(1e-7, radiusSquared * 1e-10)
	}

	private fun sampleInterior(mask: SolidAlphaMask, spacing: Double): List<Point> {
		val points = mutableListOf<Point>()
		val rowStep = spacing * 0.8660254037844386
		var row = 0
		var y = min(mask.height * 0.5, rowStep * 0.5)
		while (y < mask.height) {
			var x = spacing * 0.5 + if ((row and 1) == 0) 0.0 else spacing * 0.5
			while (x < mask.width) {
				if (mask.isSolid(floor(x).toInt(), floor(y).toInt())) points += Point(x, y)
				x += spacing
			}
			row++
			y += rowStep
		}
		return points
	}

	private fun pointInPolygon(point: Point, polygon: List<Point>): Boolean {
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

	private fun pointInTriangleInclusive(point: Point, a: Point, b: Point, c: Point): Boolean {
		val ab = cross(a, b, point)
		val bc = cross(b, c, point)
		val ca = cross(c, a, point)
		val hasNegative = ab < -GEOMETRY_EPSILON || bc < -GEOMETRY_EPSILON || ca < -GEOMETRY_EPSILON
		val hasPositive = ab > GEOMETRY_EPSILON || bc > GEOMETRY_EPSILON || ca > GEOMETRY_EPSILON
		return !(hasNegative && hasPositive)
	}

	private fun pointOnSegment(point: Point, a: Point, b: Point): Boolean {
		val length = distance(a, b).coerceAtLeast(GEOMETRY_EPSILON)
		if (abs(cross(a, b, point)) / length > 1e-7) return false
		return point.x >= min(a.x, b.x) - 1e-7 && point.x <= max(a.x, b.x) + 1e-7 &&
			point.y >= min(a.y, b.y) - 1e-7 && point.y <= max(a.y, b.y) + 1e-7
	}

	private fun distanceSquaredToLoop(point: Point, loop: List<Point>): Double {
		var best = Double.POSITIVE_INFINITY
		for (index in loop.indices) {
			val a = loop[index]
			val b = loop[(index + 1) % loop.size]
			val dx = b.x - a.x
			val dy = b.y - a.y
			val lengthSquared = dx * dx + dy * dy
			val t = if (lengthSquared <= GEOMETRY_EPSILON) 0.0 else {
				((point.x - a.x) * dx + (point.y - a.y) * dy) / lengthSquared
			}.coerceIn(0.0, 1.0)
			best = min(best, distanceSquared(point, Point(a.x + dx * t, a.y + dy * t)))
		}
		return best
	}

	private fun hasSelfIntersection(loop: List<Point>): Boolean {
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

	private fun segmentsProperlyIntersect(a: Point, b: Point, c: Point, d: Point): Boolean {
		val abC = cross(a, b, c)
		val abD = cross(a, b, d)
		val cdA = cross(c, d, a)
		val cdB = cross(c, d, b)
		return abC * abD < -GEOMETRY_EPSILON && cdA * cdB < -GEOMETRY_EPSILON
	}

	private fun evaluate(cubic: Cubic, t: Double): Point {
		val oneMinus = 1.0 - t
		val w0 = oneMinus * oneMinus * oneMinus
		val w1 = 3.0 * oneMinus * oneMinus * t
		val w2 = 3.0 * oneMinus * t * t
		val w3 = t * t * t
		return Point(
			cubic.p0.x * w0 + cubic.c1.x * w1 + cubic.c2.x * w2 + cubic.p1.x * w3,
			cubic.p0.y * w0 + cubic.c1.y * w1 + cubic.c2.y * w2 + cubic.p1.y * w3,
		)
	}

	private fun lerp(a: Point, b: Point, t: Double): Point =
		Point(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)

	private fun signedAreaTwice(points: List<Point>): Double {
		var area = 0.0
		for (index in points.indices) {
			val next = (index + 1) % points.size
			area += points[index].x * points[next].y - points[next].x * points[index].y
		}
		return area
	}

	private fun contourArea(points: IntArray): Double {
		var areaTwice = 0.0
		val count = points.size / 2
		for (index in 0 until count) {
			val next = (index + 1) % count
			areaTwice += points[index * 2].toDouble() * points[next * 2 + 1] -
				points[next * 2].toDouble() * points[index * 2 + 1]
		}
		return abs(areaTwice) * 0.5
	}

	private fun triangleEdges(triangle: Triangle): List<Edge> = listOf(
		edgeOf(triangle.a, triangle.b),
		edgeOf(triangle.b, triangle.c),
		edgeOf(triangle.c, triangle.a),
	)

	private fun oppositeVertex(triangle: Triangle, edge: Edge): Int =
		when {
			triangle.a != edge.low && triangle.a != edge.high -> triangle.a
			triangle.b != edge.low && triangle.b != edge.high -> triangle.b
			else -> triangle.c
		}

	private fun edgeOf(a: Int, b: Int): Edge = Edge(min(a, b), max(a, b))

	private fun distance(a: Point, b: Point): Double = hypot(a.x - b.x, a.y - b.y)

	private fun distanceSquared(a: Point, b: Point): Double {
		val dx = a.x - b.x
		val dy = a.y - b.y
		return dx * dx + dy * dy
	}

	private fun cross(a: Point, b: Point, c: Point): Double =
		(b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)
}
