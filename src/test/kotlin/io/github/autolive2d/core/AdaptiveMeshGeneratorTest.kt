package io.github.autolive2d.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AdaptiveMeshGeneratorTest {
	@Test
	fun `outer boundary is filtered and expanded slightly`() {
		val width = 120
		val height = 80
		val rgba = ByteArray(width * height * 4)
		for (y in 12 until 68) for (x in 12 until 108) rgba[(y * width + x) * 4 + 3] = 0xff.toByte()

		val mesh = assertNotNull(AdaptiveMeshGenerator.generate(width, height, rgba, 8, 40f))
		val xs = mesh.positions.filterIndexed { index, _ -> index % 2 == 0 }
		val ys = mesh.positions.filterIndexed { index, _ -> index % 2 == 1 }
		assertTrue(xs.min() < 11.95f && xs.max() > 108.05f, "left/right silhouette should gain a small margin")
		assertTrue(ys.min() < 11.95f && ys.max() > 68.05f, "top/bottom silhouette should gain a small margin")
		assertTrue(xs.all { it in 0f..width.toFloat() } && ys.all { it in 0f..height.toFloat() })
	}

	@Test
	fun `sharp outer bend receives more points than an equally long straight edge`() {
		val width = 200
		val height = 110
		val polygon = arrayOf(
			10f to 30f,
			88f to 30f,
			100f to 7f,
			112f to 30f,
			190f to 30f,
			190f to 96f,
			10f to 96f,
		)
		val rgba = ByteArray(width * height * 4)
		for (y in 0 until height) for (x in 0 until width) {
			if (insidePolygon(x + 0.5f, y + 0.5f, polygon)) rgba[(y * width + x) * 4 + 3] = 0xff.toByte()
		}

		val mesh = assertNotNull(AdaptiveMeshGenerator.generate(width, height, rgba, 8, 64f))
		val sharpRegion = (mesh.positions.indices step 2).count { offset ->
			mesh.positions[offset] in 87f..113f && mesh.positions[offset + 1] < 31f
		}
		val straightRegion = (mesh.positions.indices step 2).count { offset ->
			mesh.positions[offset] in 35f..75f && mesh.positions[offset + 1] in 27f..31f
		}
		assertTrue(sharpRegion >= straightRegion, "curvature may refine locally but must not starve a sharp bend")
	}

	@Test
	fun `curvature refinement preserves every finger tip and valley`() {
		val width = 132
		val height = 128
		val rgba = ByteArray(width * height * 4)
		fun fill(left: Int, top: Int, right: Int, bottom: Int) {
			for (y in top until bottom) for (x in left until right) rgba[(y * width + x) * 4 + 3] = 0xff.toByte()
		}
		fill(22, 58, 108, 118)
		val fingers = listOf(
			Triple(25..37, 28, 64),
			Triple(42..54, 18, 64),
			Triple(59..71, 13, 64),
			Triple(76..88, 20, 64),
			Triple(93..105, 31, 64),
		)
		for ((range, top, bottom) in fingers) fill(range.first, top, range.last + 1, bottom)
		val solidCore = BooleanArray(width * height) { index ->
			(rgba[index * 4 + 3].toInt() and 0xff) == 255
		}
		// Add two antialias/feather bands. Geometry extraction must return to the hard core instead
		// of meshing the soft tail or bridging the narrow gaps between fingers.
		for (y in 0 until height) for (x in 0 until width) {
			if (solidCore[y * width + x]) continue
			var nearest = 3
			for (dy in -2..2) for (dx in -2..2) {
				val sampleX = x + dx
				val sampleY = y + dy
				if (sampleX in 0 until width && sampleY in 0 until height && solidCore[sampleY * width + sampleX]) {
					nearest = minOf(nearest, maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)))
				}
			}
			val featherAlpha = when (nearest) {
				1 -> 80
				2 -> 24
				else -> 0
			}
			rgba[(y * width + x) * 4 + 3] = featherAlpha.toByte()
		}
		for ((x, y) in listOf(5 to 7, 119 to 11, 8 to 122)) {
			for (dy in 0..1) for (dx in 0..1) rgba[((y + dy) * width + x + dx) * 4 + 3] = 36
		}

		val mesh = assertNotNull(AdaptiveMeshGenerator.generate(width, height, rgba, 8, 48f))
		val loop = mesh.boundaryLoops.single()
		assertTrue(loop.size >= 28, "five-finger silhouette needs curvature-driven boundary detail; size=${loop.size}")
		fun loopHasPoint(predicate: (Float, Float) -> Boolean): Boolean = loop.any { vertex ->
			val offset = vertex * 2
			predicate(mesh.positions[offset], mesh.positions[offset + 1])
		}
		for ((range, top, _) in fingers) {
			assertTrue(
				loopHasPoint { x, y -> x in (range.first - 1f)..(range.last + 1f) && y <= top + 3f },
				"finger ${range.first}..${range.last} was clipped",
			)
		}
		for (gapCenter in listOf(39.5f, 56.5f, 73.5f, 90.5f)) {
			assertTrue(
				loopHasPoint { x, y -> kotlin.math.abs(x - gapCenter) <= 3.5f && y >= 53f },
				"finger valley at x=$gapCenter was bridged into a glove",
			)
		}
	}

	@Test
	fun `Bezier boundary is unique evenly spaced and explicitly constrained`() {
		val width = 176
		val height = 132
		val rgba = ByteArray(width * height * 4)
		for (y in 0 until height) for (x in 0 until width) {
			val dx = (x + 0.5f - width / 2f) / 66f
			val dy = (y + 0.5f - height / 2f) / 45f
			if (dx * dx + dy * dy <= 1f) rgba[(y * width + x) * 4 + 3] = 0xff.toByte()
		}

		val requestedSpacing = 18f
		val mesh = assertNotNull(AdaptiveMeshGenerator.generate(width, height, rgba, 8, requestedSpacing))
		val loop = mesh.boundaryLoops.single()
		assertTrue(loop.size >= 12)
		assertTrue(loop.toSet().size == loop.size, "boundary vertices must never be merged")

		val triangleEdges = hashSetOf<Pair<Int, Int>>()
		for (offset in mesh.indices.indices step 3) {
			val triangle = intArrayOf(mesh.indices[offset], mesh.indices[offset + 1], mesh.indices[offset + 2])
			for (edge in 0..2) {
				val a = triangle[edge]
				val b = triangle[(edge + 1) % 3]
				triangleEdges += minOf(a, b) to maxOf(a, b)
			}
		}
		val distances = loop.indices.map { index ->
			val a = loop[index] * 2
			val b = loop[(index + 1) % loop.size] * 2
			val dx = mesh.positions[b] - mesh.positions[a]
			val dy = mesh.positions[b + 1] - mesh.positions[a + 1]
			kotlin.math.hypot(dx, dy)
		}
		for (index in loop.indices) {
			val a = loop[index]
			val b = loop[(index + 1) % loop.size]
			assertTrue((minOf(a, b) to maxOf(a, b)) in triangleEdges, "consecutive boundary edge was not constrained")
		}
		val median = distances.sorted()[distances.size / 2]
		assertTrue(median in requestedSpacing * 0.55f..requestedSpacing * 1.10f)
		assertTrue(
			distances.min() >= median * 0.46f,
			"boundary points formed a dense cluster: min=${distances.min()}, median=$median",
		)
		assertTrue(
			distances.max() <= median * 1.55f,
			"boundary sampling has a discontinuous gap: max=${distances.max()}, median=$median",
		)
	}

	@Test
	fun `enclosed transparency is meshed as one solid island`() {
		val width = 128
		val height = 112
		val rgba = ByteArray(width * height * 4)
		for (y in 8 until 104) for (x in 8 until 120) {
			val inTransparentCenter = x in 38 until 90 && y in 30 until 82
			if (!inTransparentCenter) rgba[(y * width + x) * 4 + 3] = 0xff.toByte()
		}

		val mesh = assertNotNull(AdaptiveMeshGenerator.generate(width, height, rgba, 8, 14f))
		assertTrue(pointCovered(mesh, 64f, 56f), "the former hole centre must be covered by a solid face")

		// No inner-loop vertices should be emitted. Interior grid points may lie inside the filled
		// region, but none may trace one of the former hole's four boundary lines.
		val formerBoundaryVertices = (mesh.positions.indices step 2).count { offset ->
			val x = mesh.positions[offset]
			val y = mesh.positions[offset + 1]
			((abs(x - 38f) < 0.01f || abs(x - 90f) < 0.01f) && y in 30f..82f) ||
				((abs(y - 30f) < 0.01f || abs(y - 82f) < 0.01f) && x in 38f..90f)
		}
		assertTrue(formerBoundaryVertices == 0, "transparent centre must not create an inner vertex ring")
	}

	@Test
	fun `one pixel ring is also filled through its centre`() {
		val width = 96
		val height = 96
		val rgba = ByteArray(width * height * 4)
		for (y in 5 until 91) for (x in 5 until 91) {
			val inTransparentCenter = x in 6 until 90 && y in 6 until 90
			if (!inTransparentCenter) rgba[(y * width + x) * 4 + 3] = 0xff.toByte()
		}

		val mesh = assertNotNull(AdaptiveMeshGenerator.generate(width, height, rgba, 8, 12f))
		assertTrue(pointCovered(mesh, 48f, 48f), "thin ring must become a stable solid mesh")
	}

	private fun pointCovered(mesh: AdaptiveMeshGenerator.Result, x: Float, y: Float): Boolean {
		fun cross(ax: Float, ay: Float, bx: Float, by: Float): Float = ax * by - ay * bx
		for (triangleOffset in mesh.indices.indices step 3) {
			val a = mesh.indices[triangleOffset] * 2
			val b = mesh.indices[triangleOffset + 1] * 2
			val c = mesh.indices[triangleOffset + 2] * 2
			val ab = cross(
				mesh.positions[b] - mesh.positions[a],
				mesh.positions[b + 1] - mesh.positions[a + 1],
				x - mesh.positions[a],
				y - mesh.positions[a + 1],
			)
			val bc = cross(
				mesh.positions[c] - mesh.positions[b],
				mesh.positions[c + 1] - mesh.positions[b + 1],
				x - mesh.positions[b],
				y - mesh.positions[b + 1],
			)
			val ca = cross(
				mesh.positions[a] - mesh.positions[c],
				mesh.positions[a + 1] - mesh.positions[c + 1],
				x - mesh.positions[c],
				y - mesh.positions[c + 1],
			)
			if ((ab >= -1e-4f && bc >= -1e-4f && ca >= -1e-4f) ||
				(ab <= 1e-4f && bc <= 1e-4f && ca <= 1e-4f)
			) return true
		}
		return false
	}

	private fun insidePolygon(x: Float, y: Float, polygon: Array<Pair<Float, Float>>): Boolean {
		var inside = false
		var previous = polygon.last()
		for (current in polygon) {
			if ((current.second > y) != (previous.second > y)) {
				val crossingX = (previous.first - current.first) * (y - current.second) /
					(previous.second - current.second) + current.first
				if (x < crossingX) inside = !inside
			}
			previous = current
		}
		return inside
	}
}
