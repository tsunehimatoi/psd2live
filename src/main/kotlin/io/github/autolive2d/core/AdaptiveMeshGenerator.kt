package io.github.autolive2d.core

import org.umamo.format.art.analyzeAlpha
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Solid, silhouette-aware ArtMesh generation with explicit boundary constraints.
 *
 * Each opaque island produces one ordered periodic cubic Bezier loop. Boundary samples are never
 * merged with one another, and every consecutive pair is protected as a triangle edge. Enclosed
 * transparency is intentionally filled and never becomes a second boundary loop.
 */
internal object AdaptiveMeshGenerator {
	internal data class Result(
		/** Raster-local x/y pairs, in source pixels. */
		val positions: FloatArray,
		val indices: IntArray,
		/** Global vertex indices for tests/integrity checks; closure is implicit. */
		val boundaryLoops: List<IntArray>,
	)

	private data class Point(val x: Double, val y: Double)
	private data class Triangle(val a: Int, val b: Int, val c: Int)
	private data class Edge(val low: Int, val high: Int)
	private data class Cubic(val p0: Point, val c1: Point, val c2: Point, val p1: Point)
	private data class LocalMesh(val points: List<Point>, val triangles: List<Triangle>)

	private const val EDGE_EXPANSION = 1.15
	private const val FILTER_PASSES = 2
	private const val FILTER_MAX_OFFSET = 0.55
	private const val CURVE_CHORD_ERROR = 0.85
	private const val MAX_CURVE_DENSITY = 12.0
	private const val MAX_BOUNDARY_POINTS_PER_LOOP = 480
	private const val GEOMETRY_EPSILON = 1e-8

	/** Bit-packed alpha mask with every outer contour filled as a solid island. */
	private class SolidAlphaMask(
		val width: Int,
		val height: Int,
		rgba: ByteArray,
		threshold: Int,
		outerContours: List<IntArray>,
	) {
		private val words = LongArray((width * height + 63) ushr 6).also { destination ->
			for (index in 0 until width * height) {
				if ((rgba[index * 4 + 3].toInt() and 0xff) >= threshold) set(destination, index)
			}
			// Only outer rings are filled. Hole contours never enter this class.
			for (contour in outerContours) rasterizeSolidContour(contour, destination)
		}

		fun isSolid(x: Int, y: Int): Boolean {
			if (x !in 0 until width || y !in 0 until height) return false
			val index = y * width + x
			return (words[index ushr 6] and (1L shl (index and 63))) != 0L
		}

		private fun rasterizeSolidContour(contour: IntArray, destination: LongArray) {
			val pointCount = contour.size / 2
			if (pointCount < 3) return
			val minimumY = (0 until pointCount).minOf { contour[it * 2 + 1] }.coerceAtLeast(0)
			val maximumY = (0 until pointCount).maxOf { contour[it * 2 + 1] }.coerceAtMost(height)
			for (pixelY in minimumY until maximumY) {
				val scanY = pixelY + 0.5
				val intersections = mutableListOf<Double>()
				for (index in 0 until pointCount) {
					val next = (index + 1) % pointCount
					val x1 = contour[index * 2].toDouble()
					val y1 = contour[index * 2 + 1].toDouble()
					val x2 = contour[next * 2].toDouble()
					val y2 = contour[next * 2 + 1].toDouble()
					if ((y1 <= scanY && y2 > scanY) || (y2 <= scanY && y1 > scanY)) {
						intersections += x1 + (scanY - y1) * (x2 - x1) / (y2 - y1)
					}
				}
				intersections.sort()
				for (pair in 0 until intersections.size / 2) {
					val firstX = ceil(intersections[pair * 2] - 0.5).toInt().coerceAtLeast(0)
					val lastX = (ceil(intersections[pair * 2 + 1] - 0.5).toInt() - 1).coerceAtMost(width - 1)
					for (pixelX in firstX..lastX) set(destination, pixelY * width + pixelX)
				}
			}
		}

		private fun set(destination: LongArray, index: Int) {
			destination[index ushr 6] = destination[index ushr 6] or (1L shl (index and 63))
		}
	}

	fun generate(
		width: Int,
		height: Int,
		rgba: ByteArray,
		alphaThreshold: Int,
		spacing: Float,
	): Result? {
		if (width <= 0 || height <= 0) return null
		val threshold = alphaThreshold.coerceIn(1, 255)
		val hardened = AlphaEdgePreprocessor.process(width, height, rgba, threshold) ?: return null
		val alpha = analyzeAlpha(width, height, hardened.rgba, 1, contourEpsilon = 1.0f) ?: return null
		val outerCandidates = alpha.contours.filter { !it.isHole && it.points.size >= 6 }
		if (outerCandidates.isEmpty()) return null
		val areas = outerCandidates.map { contourArea(it.points) }
		val largestIndex = areas.indices.maxByOrNull { areas[it] } ?: return null
		val minimumSecondaryArea = max(6.0, min(24.0, areas[largestIndex] * 0.0002))
		val outerContours = outerCandidates.filterIndexed { index, _ ->
			index == largestIndex || areas[index] >= minimumSecondaryArea
		}
		if (outerContours.isEmpty()) return null

		val solidMask = SolidAlphaMask(width, height, hardened.rgba, 1, outerContours.map { it.points })
		val budgetSpacing = sqrt(width.toDouble() * height / (1_200.0 * 0.8660254037844386))
		val gridSpacing = max(max(6.0, spacing.toDouble()), budgetSpacing)
		val boundaryLoops = outerContours.map { contour ->
			buildBezierBoundary(contour.points, width, height, gridSpacing)
		}
		if (boundaryLoops.any { it.size < 3 }) return null

		val gridCandidates = sampleInterior(solidMask, gridSpacing)
		val globalPoints = mutableListOf<Point>()
		val globalTriangles = mutableListOf<Triangle>()
		val globalBoundaryLoops = mutableListOf<IntArray>()
		for (boundary in boundaryLoops) {
			val edgeClearance = min(8.0, max(2.0, gridSpacing * 0.28))
			val interior = gridCandidates.filter { candidate ->
				pointInPolygon(candidate, boundary) && distanceSquaredToLoop(candidate, boundary) >= edgeClearance * edgeClearance
			}
			val local = constrainedTriangulate(boundary, interior) ?: return null
			val offset = globalPoints.size
			globalPoints += local.points
			globalTriangles += local.triangles.map { triangle ->
				Triangle(triangle.a + offset, triangle.b + offset, triangle.c + offset)
			}
			globalBoundaryLoops += IntArray(boundary.size) { offset + it }
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
		return Result(positions, indices, globalBoundaryLoops)
	}

	private fun buildBezierBoundary(
		source: IntArray,
		width: Int,
		height: Int,
		spacing: Double,
	): List<Point> {
		var controls = List(source.size / 2) { index ->
			Point(source[index * 2].toDouble(), source[index * 2 + 1].toDouble())
		}
		repeat(FILTER_PASSES) { controls = filterControls(controls) }
		controls = expandControls(controls, width, height)

		// Reduce handle strength if a highly concave silhouette would make the fitted curve cross
		// itself. Even the zero-handle fallback remains a cubic Bezier representation of the ring.
		for (handleStrength in doubleArrayOf(1.0, 0.55, 0.25, 0.0)) {
			val sampled = samplePeriodicBezier(controls, spacing, handleStrength)
			val hasCollapsedEdge = sampled.indices.any { index ->
				distance(sampled[index], sampled[(index + 1) % sampled.size]) < 0.15
			}
			if (sampled.size >= 3 && !hasCollapsedEdge && !hasSelfIntersection(sampled)) return sampled
		}
		return emptyList()
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

	private fun expandControls(source: List<Point>, width: Int, height: Int): List<Point> {
		if (source.size < 3) return source
		val outwardSign = if (signedAreaTwice(source) >= 0.0) 1.0 else -1.0
		return List(source.size) { index ->
			val previous = source[(index - 1 + source.size) % source.size]
			val current = source[index]
			val next = source[(index + 1) % source.size]
			val incomingLength = hypot(current.x - previous.x, current.y - previous.y).coerceAtLeast(GEOMETRY_EPSILON)
			val outgoingLength = hypot(next.x - current.x, next.y - current.y).coerceAtLeast(GEOMETRY_EPSILON)
			val normal1X = (current.y - previous.y) / incomingLength * outwardSign
			val normal1Y = -(current.x - previous.x) / incomingLength * outwardSign
			val normal2X = (next.y - current.y) / outgoingLength * outwardSign
			val normal2Y = -(next.x - current.x) / outgoingLength * outwardSign
			var normalX = normal1X + normal2X
			var normalY = normal1Y + normal2Y
			val normalLength = hypot(normalX, normalY)
			if (normalLength <= GEOMETRY_EPSILON) {
				normalX = normal2X
				normalY = normal2Y
			} else {
				normalX /= normalLength
				normalY /= normalLength
			}
			Point(
				(current.x + normalX * EDGE_EXPANSION).coerceIn(0.0, width.toDouble()),
				(current.y + normalY * EDGE_EXPANSION).coerceIn(0.0, height.toDouble()),
			)
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
			val intervals = ceil(arcMetric / spacing).toInt().coerceAtLeast(1)
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
				spacing * result.size / MAX_BOUNDARY_POINTS_PER_LOOP,
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

	private fun constrainedTriangulate(boundary: List<Point>, interiorCandidates: List<Point>): LocalMesh? {
		val points = boundary.toMutableList()
		val triangles = earClip(boundary)?.toMutableList() ?: return null
		val protectedEdges = boundary.indices.mapTo(linkedSetOf()) { index ->
			edgeOf(index, (index + 1) % boundary.size)
		}
		for (candidate in interiorCandidates) insertInteriorPoint(candidate, points, triangles)
		relaxToConstrainedDelaunay(points, triangles, protectedEdges)
		val presentEdges = triangles.flatMapTo(hashSetOf()) { triangle -> triangleEdges(triangle) }
		if (!presentEdges.containsAll(protectedEdges)) return null
		return LocalMesh(points, triangles.filter { triangle ->
			abs(cross(points[triangle.a], points[triangle.b], points[triangle.c])) > GEOMETRY_EPSILON
		})
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
					if (candidate == previous || candidate == current || candidate == next) continue
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
		candidate: Point,
		points: MutableList<Point>,
		triangles: MutableList<Triangle>,
	): Boolean {
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
		points += candidate
		if (onEdge == null) {
			triangles.removeAt(containingIndex)
			triangles += Triangle(containing.a, containing.b, pointIndex)
			triangles += Triangle(containing.b, containing.c, pointIndex)
			triangles += Triangle(containing.c, containing.a, pointIndex)
			return true
		}

		val adjacentIndices = triangles.indices.filter { index -> onEdge in triangleEdges(triangles[index]) }
		val adjacentTriangles = adjacentIndices.map { triangles[it] }
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
		repeat(14) {
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
