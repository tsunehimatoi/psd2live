package io.github.autolive2d.core

import org.umamo.format.art.analyzeAlpha
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Deterministic silhouette-aware ArtMesh generation.
 *
 * The mesh follows only opaque islands' outer contours. Enclosed transparent regions are
 * deliberately solidified before interior sampling and triangle validation: they do not create
 * inner boundary vertices, radial connections, or holes in the resulting ArtMesh.
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

	/** Bit-packed alpha mask with every outer contour filled as one solid island. */
	private class SolidAlphaMask(
		val width: Int,
		val height: Int,
		rgba: ByteArray,
		threshold: Int,
		outerContours: List<IntArray>,
	) {
		private val words = LongArray((width * height + 63) ushr 6).also { destination ->
			// Keep every thresholded source pixel, including antialiased extremities that may sit
			// just outside a simplified contour.
			for (index in 0 until width * height) {
				if ((rgba[index * 4 + 3].toInt() and 0xff) >= threshold) set(destination, index)
			}
			// Filling only outer polygons intentionally covers all enclosed transparency. Inner
			// contours are never passed here and therefore never become mesh topology.
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
		val alpha = analyzeAlpha(width, height, rgba, threshold, contourEpsilon = 1.25f) ?: return null
		val outerContours = alpha.contours.filter { !it.isHole && it.points.size >= 6 }
		if (outerContours.isEmpty()) return null
		val solidMask = SolidAlphaMask(width, height, rgba, threshold, outerContours.map { it.points })

		val perimeters = outerContours.map { contourPerimeter(it.points) }
		val totalPerimeter = perimeters.sum().coerceAtLeast(1.0)
		val edgeBudget = (totalPerimeter / max(4.0, spacing * 0.52)).roundToInt().coerceIn(12, 360)
		val edgePoints = mutableListOf<Point>()
		for (index in outerContours.indices) {
			val rawCount = (edgeBudget * perimeters[index] / totalPerimeter).roundToInt()
			val available = outerContours[index].points.size / 2
			val count = rawCount.coerceIn(min(3, available), min(180, available))
			edgePoints += resampleClosedContour(outerContours[index].points, count)
		}

		// Bowyer-Watson is intentionally dependency-free and quadratic. Raise the sampling interval
		// for huge layers so one full-canvas PSD layer cannot create tens of thousands of points.
		val budgetSpacing = sqrt(width.toDouble() * height / (1_200.0 * 0.8660254037844386))
		val interiorSpacing = max(max(6.0, spacing.toDouble()), budgetSpacing)
		val interiorPoints = sampleInterior(solidMask, interiorSpacing)
		val edgePadding = min(8.0, max(2.0, spacing * 0.24))
		val paddedInterior = interiorPoints.filter { point ->
			edgePoints.none { edge -> squaredDistance(point, edge) < edgePadding * edgePadding }
		}
		val points = deduplicate(edgePoints + paddedInterior, minimumDistance = 1.5)
		if (points.size < 3 || points.size >= 65_535) return null

		val triangles = delaunay(points).filter { triangle ->
			triangleInsideDilatedSolid(triangle, points, solidMask)
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
			// Preserve the renderer's negative signed-area winding in raster Y-down space.
			val signedArea = cross(points[triangle.a], points[triangle.b], points[triangle.c])
			indices[index * 3] = triangle.a
			if (signedArea < 0.0) {
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

	private fun triangleInsideDilatedSolid(
		triangle: Triangle,
		points: List<Point>,
		mask: SolidAlphaMask,
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
		return samples.all { sample -> insideDilatedSolid(mask, sample.x, sample.y, 2) }
	}

	private fun insideDilatedSolid(mask: SolidAlphaMask, x: Double, y: Double, radius: Int): Boolean {
		val centerX = floor(x).toInt().coerceIn(0, mask.width - 1)
		val centerY = floor(y).toInt().coerceIn(0, mask.height - 1)
		for (sampleY in max(0, centerY - radius)..min(mask.height - 1, centerY + radius)) {
			for (sampleX in max(0, centerX - radius)..min(mask.width - 1, centerX + radius)) {
				if (mask.isSolid(sampleX, sampleY)) return true
			}
		}
		return false
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
