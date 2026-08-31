package io.github.autolive2d.core

import org.umamo.format.art.analyzeAlpha
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Deterministic silhouette-aware ArtMesh generation.
 *
 * The pipeline follows Stretchy Studio's useful parts: alpha contours, perimeter-proportional
 * edge samples, staggered interior samples, and Delaunay triangulation.  Unlike Stretchy's raw
 * Delaunay output, triangles are checked against a two-pixel alpha dilation so concave gaps and
 * transparent holes do not get bridged by visible geometry.
 */
internal object AdaptiveMeshGenerator {
	internal data class Result(
		/** Raster-local x/y pairs, in source pixels. */
		val positions: FloatArray,
		val indices: IntArray,
	)

	private data class Point(val x: Double, val y: Double)
	private data class Triangle(val a: Int, val b: Int, val c: Int)
	private data class Edge(val low: Int, val high: Int)

	fun generate(
		width: Int,
		height: Int,
		rgba: ByteArray,
		alphaThreshold: Int,
		spacing: Float,
	): Result? {
		if (width <= 0 || height <= 0) return null
		val threshold = alphaThreshold.coerceIn(1, 255)
		val alpha = analyzeAlpha(width, height, rgba, threshold, contourEpsilon = 1.25f) ?: return null
		val usableContours = alpha.contours.filter { it.points.size >= 6 }
		if (usableContours.isEmpty()) return null

		val perimeters = usableContours.map { contourPerimeter(it.points) }
		val totalPerimeter = perimeters.sum().coerceAtLeast(1.0)
		val edgeBudget = (totalPerimeter / max(4.0, spacing * 0.52)).roundToInt().coerceIn(12, 360)
		val edgePoints = mutableListOf<Point>()
		for (index in usableContours.indices) {
			val rawCount = (edgeBudget * perimeters[index] / totalPerimeter).roundToInt()
			val available = usableContours[index].points.size / 2
			val count = rawCount.coerceIn(min(3, available), min(180, available))
			edgePoints += resampleClosedContour(usableContours[index].points, count)
		}

		// Bowyer-Watson is intentionally dependency-free and quadratic.  Raise the sampling interval
		// for huge layer rectangles so one full-canvas PSD layer cannot create tens of thousands of
		// points and stall the export.  Contour points remain separately budgeted and are never dropped.
		val budgetSpacing = sqrt(width.toDouble() * height / (1_200.0 * 0.8660254037844386))
		val interiorSpacing = max(max(6.0, spacing.toDouble()), budgetSpacing)
		val interiorPoints = sampleInterior(width, height, rgba, threshold, interiorSpacing)
		val edgePadding = min(8.0, max(2.0, spacing * 0.24))
		val paddedInterior = interiorPoints.filter { point ->
			edgePoints.none { edge -> squaredDistance(point, edge) < edgePadding * edgePadding }
		}
		val points = deduplicate(edgePoints + paddedInterior, minimumDistance = 1.5)
		if (points.size < 3 || points.size >= 65_535) return null

		val triangles = delaunay(points).filter { triangle ->
			triangleInsideDilatedAlpha(triangle, points, width, height, rgba, threshold)
		}
		if (triangles.isEmpty()) return null

		val positions = FloatArray(points.size * 2)
		for (index in points.indices) {
			positions[index * 2] = points[index].x.toFloat()
			positions[index * 2 + 1] = points[index].y.toFloat()
		}
		val indices = IntArray(triangles.size * 3)
		for (index in triangles.indices) {
			val triangle = triangles[index]
			// The previous regular grid used negative signed area in the raster's Y-down space.
			// Preserve that winding so the renderer's front-face convention does not change.
			val cross = cross(points[triangle.a], points[triangle.b], points[triangle.c])
			indices[index * 3] = triangle.a
			if (cross < 0.0) {
				indices[index * 3 + 1] = triangle.b
				indices[index * 3 + 2] = triangle.c
			} else {
				indices[index * 3 + 1] = triangle.c
				indices[index * 3 + 2] = triangle.b
			}
		}
		return Result(positions, indices)
	}

	private fun contourPerimeter(points: IntArray): Double {
		var perimeter = 0.0
		val count = points.size / 2
		for (index in 0 until count) {
			val next = (index + 1) % count
			perimeter += hypot(
				(points[next * 2] - points[index * 2]).toDouble(),
				(points[next * 2 + 1] - points[index * 2 + 1]).toDouble(),
			)
		}
		return perimeter
	}

	private fun resampleClosedContour(source: IntArray, targetCount: Int): List<Point> {
		val count = source.size / 2
		if (targetCount <= 0 || count < 3) return emptyList()
		val cumulative = DoubleArray(count + 1)
		for (index in 0 until count) {
			val next = (index + 1) % count
			cumulative[index + 1] = cumulative[index] + hypot(
				(source[next * 2] - source[index * 2]).toDouble(),
				(source[next * 2 + 1] - source[index * 2 + 1]).toDouble(),
			)
		}
		val perimeter = cumulative.last()
		if (perimeter <= 1e-6) return emptyList()
		val result = ArrayList<Point>(targetCount)
		var segment = 0
		for (sample in 0 until targetCount) {
			val distance = perimeter * sample / targetCount
			while (segment + 1 < count && cumulative[segment + 1] < distance) segment++
			val next = (segment + 1) % count
			val length = (cumulative[segment + 1] - cumulative[segment]).coerceAtLeast(1e-9)
			val t = (distance - cumulative[segment]) / length
			result += Point(
				source[segment * 2] + (source[next * 2] - source[segment * 2]) * t,
				source[segment * 2 + 1] + (source[next * 2 + 1] - source[segment * 2 + 1]) * t,
			)
		}
		return result
	}

	private fun sampleInterior(
		width: Int,
		height: Int,
		rgba: ByteArray,
		threshold: Int,
		spacing: Double,
	): List<Point> {
		val points = mutableListOf<Point>()
		val rowStep = spacing * 0.8660254037844386 // triangular/hexagonal packing
		var row = 0
		var y = min(height * 0.5, rowStep * 0.5)
		while (y < height) {
			var x = spacing * 0.5 + if ((row and 1) == 0) 0.0 else spacing * 0.5
			while (x < width) {
				if (alphaAt(rgba, width, height, x, y) >= threshold) points += Point(x, y)
				x += spacing
			}
			row++
			y += rowStep
		}
		return points
	}

	private fun deduplicate(source: List<Point>, minimumDistance: Double): List<Point> {
		val result = mutableListOf<Point>()
		val minimumSquared = minimumDistance * minimumDistance
		for (point in source) {
			if (result.none { existing -> squaredDistance(point, existing) < minimumSquared }) result += point
		}
		return result
	}

	/** Incremental Bowyer-Watson triangulation; deterministic because insertion order is stable. */
	private fun delaunay(source: List<Point>): List<Triangle> {
		if (source.size < 3) return emptyList()
		val minX = source.minOf { it.x }
		val minY = source.minOf { it.y }
		val maxX = source.maxOf { it.x }
		val maxY = source.maxOf { it.y }
		val span = max(maxX - minX, maxY - minY).coerceAtLeast(1.0)
		val centerX = (minX + maxX) * 0.5
		val centerY = (minY + maxY) * 0.5
		val points = source + listOf(
			Point(centerX - span * 24.0, centerY - span * 2.0),
			Point(centerX, centerY + span * 24.0),
			Point(centerX + span * 24.0, centerY - span * 2.0),
		)
		val superA = source.size
		val superB = source.size + 1
		val superC = source.size + 2
		val triangles = mutableListOf(Triangle(superA, superB, superC))

		for (pointIndex in source.indices) {
			val bad = triangles.filter { triangle -> inCircumcircle(points[pointIndex], triangle, points) }
			val edgeCounts = linkedMapOf<Edge, Int>()
			for (triangle in bad) {
				for (edge in listOf(edgeOf(triangle.a, triangle.b), edgeOf(triangle.b, triangle.c), edgeOf(triangle.c, triangle.a))) {
					edgeCounts[edge] = (edgeCounts[edge] ?: 0) + 1
				}
			}
			triangles.removeAll(bad.toSet())
			for ((edge, uses) in edgeCounts) {
				if (uses != 1) continue
				if (abs(cross(points[edge.low], points[edge.high], points[pointIndex])) > 1e-8) {
					triangles += Triangle(edge.low, edge.high, pointIndex)
				}
			}
		}
		return triangles.filter { it.a < source.size && it.b < source.size && it.c < source.size }
	}

	private fun inCircumcircle(point: Point, triangle: Triangle, points: List<Point>): Boolean {
		val a = points[triangle.a]
		val b = points[triangle.b]
		val c = points[triangle.c]
		val denominator = 2.0 * (a.x * (b.y - c.y) + b.x * (c.y - a.y) + c.x * (a.y - b.y))
		if (abs(denominator) < 1e-10) return false
		val aa = a.x * a.x + a.y * a.y
		val bb = b.x * b.x + b.y * b.y
		val cc = c.x * c.x + c.y * c.y
		val centerX = (aa * (b.y - c.y) + bb * (c.y - a.y) + cc * (a.y - b.y)) / denominator
		val centerY = (aa * (c.x - b.x) + bb * (a.x - c.x) + cc * (b.x - a.x)) / denominator
		val radiusSquared = (centerX - a.x) * (centerX - a.x) + (centerY - a.y) * (centerY - a.y)
		val pointSquared = (centerX - point.x) * (centerX - point.x) + (centerY - point.y) * (centerY - point.y)
		return pointSquared <= radiusSquared + max(1e-8, radiusSquared * 1e-10)
	}

	private fun triangleInsideDilatedAlpha(
		triangle: Triangle,
		points: List<Point>,
		width: Int,
		height: Int,
		rgba: ByteArray,
		threshold: Int,
	): Boolean {
		val a = points[triangle.a]
		val b = points[triangle.b]
		val c = points[triangle.c]
		if (abs(cross(a, b, c)) < 0.02) return false
		val samples = listOf(
			Point((a.x + b.x + c.x) / 3.0, (a.y + b.y + c.y) / 3.0),
			Point((a.x + b.x) * 0.5, (a.y + b.y) * 0.5),
			Point((b.x + c.x) * 0.5, (b.y + c.y) * 0.5),
			Point((c.x + a.x) * 0.5, (c.y + a.y) * 0.5),
			Point(a.x * 0.6 + b.x * 0.2 + c.x * 0.2, a.y * 0.6 + b.y * 0.2 + c.y * 0.2),
			Point(a.x * 0.2 + b.x * 0.6 + c.x * 0.2, a.y * 0.2 + b.y * 0.6 + c.y * 0.2),
			Point(a.x * 0.2 + b.x * 0.2 + c.x * 0.6, a.y * 0.2 + b.y * 0.2 + c.y * 0.6),
		)
		return samples.all { sample -> insideDilatedAlpha(rgba, width, height, sample.x, sample.y, threshold, 2) }
	}

	private fun insideDilatedAlpha(
		rgba: ByteArray,
		width: Int,
		height: Int,
		x: Double,
		y: Double,
		threshold: Int,
		radius: Int,
	): Boolean {
		val centerX = floor(x).toInt().coerceIn(0, width - 1)
		val centerY = floor(y).toInt().coerceIn(0, height - 1)
		for (sampleY in max(0, centerY - radius)..min(height - 1, centerY + radius)) {
			for (sampleX in max(0, centerX - radius)..min(width - 1, centerX + radius)) {
				if ((rgba[(sampleY * width + sampleX) * 4 + 3].toInt() and 0xff) >= threshold) return true
			}
		}
		return false
	}

	private fun alphaAt(rgba: ByteArray, width: Int, height: Int, x: Double, y: Double): Int {
		val sampleX = floor(x).toInt().coerceIn(0, width - 1)
		val sampleY = floor(y).toInt().coerceIn(0, height - 1)
		return rgba[(sampleY * width + sampleX) * 4 + 3].toInt() and 0xff
	}

	private fun squaredDistance(a: Point, b: Point): Double {
		val dx = a.x - b.x
		val dy = a.y - b.y
		return dx * dx + dy * dy
	}

	private fun edgeOf(a: Int, b: Int): Edge = Edge(min(a, b), max(a, b))

	private fun cross(a: Point, b: Point, c: Point): Double =
		(b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)
}
