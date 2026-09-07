package io.github.psd2live.core

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
	fun `enclosed transparency has a constrained hole boundary`() {
		val width = 128
		val height = 112
		val rgba = ByteArray(width * height * 4)
		for (y in 8 until 104) for (x in 8 until 120) {
			val inTransparentCenter = x in 38 until 90 && y in 30 until 82
			if (!inTransparentCenter) rgba[(y * width + x) * 4 + 3] = 0xff.toByte()
		}

		val mesh = assertNotNull(AdaptiveMeshGenerator.generate(width, height, rgba, 8, 14f))
		assertTrue(!pointCovered(mesh, 64f, 56f), "hole centre must remain empty")
		assertTrue(mesh.boundaryLoops.size == 2)
		assertTopology(mesh, 0)

	}

	@Test
	fun `one pixel ring preserves its centre`() {
		val width = 96
		val height = 96
		val rgba = ByteArray(width * height * 4)
		for (y in 5 until 91) for (x in 5 until 91) {
			val inTransparentCenter = x in 6 until 90 && y in 6 until 90
			if (!inTransparentCenter) rgba[(y * width + x) * 4 + 3] = 0xff.toByte()
		}

		val mesh = assertNotNull(AdaptiveMeshGenerator.generate(width, height, rgba, 8, 12f))
		assertTrue(!pointCovered(mesh, 48f, 48f), "thin ring must preserve the hole")
		assertTopology(mesh, 0)
	}

	@Test
	fun `deep open concavity does not leave long internal fan edges`() {
		val width = 360
		val height = 420
		val polygon = arrayOf(
			30f to 20f,
			142f to 20f,
			157f to 316f,
			202f to 76f,
			218f to 20f,
			330f to 20f,
			330f to 390f,
			30f to 390f,
		)
		val rgba = ByteArray(width * height * 4)
		for (y in 0 until height) for (x in 0 until width) {
			if (insidePolygon(x + 0.5f, y + 0.5f, polygon)) rgba[(y * width + x) * 4 + 3] = 0xff.toByte()
		}

		val spacing = 64f
		val mesh = assertNotNull(AdaptiveMeshGenerator.generate(width, height, rgba, 8, spacing, interiorSpacing = spacing))
		val protected = mesh.boundaryLoops.flatMapTo(hashSetOf()) { loop ->
			loop.indices.map { index ->
				val a = loop[index]
				val b = loop[(index + 1) % loop.size]
				minOf(a, b) to maxOf(a, b)
			}
		}
		var longestInternal = 0f
		var worstLongSliver = 0f
		for (offset in mesh.indices.indices step 3) {
			val vertices = intArrayOf(mesh.indices[offset], mesh.indices[offset + 1], mesh.indices[offset + 2])
			val edgeLengths = FloatArray(3)
			for (edgeIndex in 0..2) {
				val a = vertices[edgeIndex]
				val b = vertices[(edgeIndex + 1) % 3]
				val ax = mesh.positions[a * 2]
				val ay = mesh.positions[a * 2 + 1]
				val bx = mesh.positions[b * 2]
				val by = mesh.positions[b * 2 + 1]
				edgeLengths[edgeIndex] = kotlin.math.hypot(bx - ax, by - ay)
				if ((minOf(a, b) to maxOf(a, b)) !in protected) longestInternal = maxOf(longestInternal, edgeLengths[edgeIndex])
			}
			val longest = edgeLengths.max()
			val a = vertices[0] * 2
			val b = vertices[1] * 2
			val c = vertices[2] * 2
			val areaTwice = abs(
				(mesh.positions[b] - mesh.positions[a]) * (mesh.positions[c + 1] - mesh.positions[a + 1]) -
					(mesh.positions[b + 1] - mesh.positions[a + 1]) * (mesh.positions[c] - mesh.positions[a]),
			)
			val altitude = areaTwice / longest.coerceAtLeast(1e-4f)
			if (longest > spacing * 1.15f && altitude < spacing * 0.28f) {
				worstLongSliver = maxOf(worstLongSliver, longest)
			}
		}
		assertTrue(longestInternal <= spacing * 1.85f, "deep concavity left an internal edge of $longestInternal px")
		assertTrue(worstLongSliver == 0f, "deep concavity left a long sliver triangle of $worstLongSliver px")
	}

	@Test
	fun `long narrow diagonal strand converges instead of retaining an ear clipping fan`() {
		val width = 600
		val height = 1200
		val strand = arrayOf(
			140f to 23f,
			440f to 1183f,
			460f to 1177f,
			160f to 17f,
		)
		val rgba = ByteArray(width * height * 4)
		for (y in 0 until height) for (x in 0 until width) {
			if (insidePolygon(x + 0.5f, y + 0.5f, strand)) rgba[(y * width + x) * 4 + 3] = 0xff.toByte()
		}

		val spacing = 32f
		val mesh = assertNotNull(AdaptiveMeshGenerator.generate(width, height, rgba, 8, spacing, interiorSpacing = spacing))
		val protected = mesh.boundaryLoops.flatMapTo(hashSetOf()) { loop ->
			loop.indices.map { index ->
				val a = loop[index]
				val b = loop[(index + 1) % loop.size]
				minOf(a, b) to maxOf(a, b)
			}
		}
		var longestInternal = 0f
		for (offset in mesh.indices.indices step 3) {
			val vertices = intArrayOf(mesh.indices[offset], mesh.indices[offset + 1], mesh.indices[offset + 2])
			for (edgeIndex in 0..2) {
				val a = vertices[edgeIndex]
				val b = vertices[(edgeIndex + 1) % 3]
				if ((minOf(a, b) to maxOf(a, b)) in protected) continue
				val dx = mesh.positions[a * 2] - mesh.positions[b * 2]
				val dy = mesh.positions[a * 2 + 1] - mesh.positions[b * 2 + 1]
				longestInternal = maxOf(longestInternal, kotlin.math.hypot(dx, dy))
			}
		}
		assertTrue(
			longestInternal <= spacing * 1.9f,
			"ear-clipping fan did not converge; longest internal strand edge=$longestInternal",
		)
	}

	@Test
	fun `donut bands are constrained and interior spacing is independent`() {
		val width = 240
		val rgba = ByteArray(width * width * 4)
		for (y in 0 until width) for (x in 0 until width) {
			val radius = kotlin.math.hypot(x + 0.5 - 120, y + 0.5 - 120)
			if (radius in 40.0..105.0) rgba[(y * width + x) * 4 + 3] = -1
		}
		val dense = assertNotNull(AdaptiveMeshGenerator.generate(width, width, rgba, 8, 18f, 18f))
		val sparse = assertNotNull(AdaptiveMeshGenerator.generate(width, width, rgba, 8, 18f, 42f))
		assertTrue(sparse.positions.size < dense.positions.size)
		assertTrue(sparse.guideLoops.isEmpty() && sparse.innerLoops.size == 2, "Bezier must not be a mesh seam; the inner envelope remains constrained")
		assertTrue(sparse.boundaryLoops.map { it.size } == dense.boundaryLoops.map { it.size })
		for (y in 90..150 step 5) for (x in 90..150 step 5) {
			if (kotlin.math.hypot(x - 120.0, y - 120.0) < 36) assertTrue(!pointCovered(sparse, x.toFloat(), y.toFloat()))
		}
		assertTopology(dense, 0)
		assertTopology(sparse, 0)
	}

	@Test
	fun `multiple holes and nested island preserve Euler topology`() {
		val width = 180
		val rgba = ByteArray(width * width * 4)
		for (y in 8 until 172) for (x in 8 until 172) {
			val hole = (x in 25..75 && y in 30..140) || (x in 100..150 && y in 30..140)
			val island = x in 40..60 && y in 60..90
			if (!hole || island) rgba[(y * width + x) * 4 + 3] = -1
		}
		val mesh = assertNotNull(AdaptiveMeshGenerator.generate(width, width, rgba, 8, 16f))
		assertTrue(mesh.boundaryLoops.size == 4)
		assertTrue(!pointCovered(mesh, 120f, 80f))
		assertTrue(pointCovered(mesh, 50f, 75f))
		assertTopology(mesh, 0) // two components minus two holes
	}

	@Test
	fun `canvas contact and coarse sampling do not collapse to a rectangle`() {
		for (spacing in listOf(12f, 64f, 500f)) {
			val width = 100
			val rgba = ByteArray(width * width * 4)
			for (y in 0 until width) for (x in 0 until width) {
				if (kotlin.math.hypot(x + 0.5 - 50, y + 0.5 - 50) <= 50) rgba[(y * width + x) * 4 + 3] = -1
			}
			val mesh = assertNotNull(AdaptiveMeshGenerator.generate(width, width, rgba, 8, spacing))
			assertTrue(!pointCovered(mesh, 3f, 3f))
			assertTopology(mesh, 1)
		}
	}

	@Test
	fun `concave curved silhouettes retain manifold topology across densities`() {
		val width = 160
		for (lobes in listOf(3, 5, 9)) for (depth in listOf(0.2, 0.65)) {
			val rgba = ByteArray(width * width * 4)
			for (y in 0 until width) for (x in 0 until width) {
				val dx = x + 0.5 - 80; val dy = y + 0.5 - 80
				val angle = kotlin.math.atan2(dy, dx)
				val radius = 65 * (1 - depth * (0.5 + 0.5 * kotlin.math.cos(lobes * angle)))
				if (kotlin.math.hypot(dx, dy) <= radius) rgba[(y * width + x) * 4 + 3] = -1
			}
			for (spacing in listOf(12f, 40f, 96f)) {
				val mesh = assertNotNull(AdaptiveMeshGenerator.generate(width, width, rgba, 8, spacing),
					"lobes=$lobes depth=$depth spacing=$spacing")
				assertTrue(!pointCovered(mesh, 4f, 4f))
				assertTopology(mesh, 1)
			}
		}
	}

	@Test
	fun `nearby islands do not overlap their expanded edge bands`() {
		val width = 110
		val rgba = ByteArray(width * width * 4)
		for (y in 10 until 100) for (x in 10 until 100) {
			if (x !in 50..51) rgba[(y * width + x) * 4 + 3] = -1
		}
		val mesh = assertNotNull(AdaptiveMeshGenerator.generate(width, width, rgba, 8, 16f))
		assertTrue(mesh.boundaryLoops.size == 2)
		assertTrue(!pointCovered(mesh, 51f, 55f), "edge expansion overlapped adjacent islands")
		assertTopology(mesh, 2)
	}

	@Test
	fun `thin curved eyebrows receive an expanded longitudinal ribbon`() {
		val width = 280
		val height = 120
		for (thickness in listOf(1.0, 3.0, 7.0)) {
			val rgba = ByteArray(width * height * 4)
			for (x in 20 until 260) {
				val center = 35 + 25 * kotlin.math.sin((x - 20.0) / 240 * Math.PI)
				// A connected one-pixel stroke includes the staircase corner, not separated diagonal pixels.
				val previous = 35 + 25 * kotlin.math.sin((maxOf(x - 1, 20) - 20.0) / 240 * Math.PI)
				for (y in minOf(previous.toInt(), center.toInt())..maxOf(previous.toInt(), center.toInt())) {
					rgba[(y * width + x) * 4 + 3] = -1
				}
				for (y in 0 until height) if (abs(y + 0.5 - center) <= thickness / 2) {
					rgba[(y * width + x) * 4 + 3] = -1
				}
			}
			val mesh = assertNotNull(AdaptiveMeshGenerator.generate(width, height, rgba, 8, 48f))
			assertTrue(mesh.spinePaths.isEmpty(), "Bezier spine must not be emitted as a mesh line; thickness=$thickness")
			assertTrue(mesh.positions.size / 2 < 180, "thin eyebrow was flooded with perimeter rows")
			assertTopology(mesh, 1)
			for (x in 25 until 255 step 5) {
				val center = (35 + 25 * kotlin.math.sin((x - 20.0) / 240 * Math.PI)).toFloat()
				assertTrue(pointCovered(mesh, x + 0.5f, center), "painted eyebrow was clipped")
			}
			assertTrue(pointCovered(mesh, 140f, 64f), "free outer space should improve ribbon width")
			assertTrue(worstAspect(mesh) < 14f, "ribbon retained needle triangles: ${worstAspect(mesh)}")
		}
	}

	@Test
	fun `single pixel gap survives smoothing and mesh expansion`() {
		val width = 220
		val height = 90
		val rgba = ByteArray(width * height * 4)
		for (x in 10 until 210) for (y in 40..44) {
			if (y != 42) rgba[(y * width + x) * 4 + 3] = -1
		}
		val mesh = assertNotNull(AdaptiveMeshGenerator.generate(width, height, rgba, 8, 48f))
		assertTrue(mesh.boundaryLoops.size == 2, "blur joined independent strokes")
		assertTrue(mesh.spinePaths.isEmpty())
		assertTopology(mesh, 2)
		assertComponents(mesh, 2)
		for (x in 15..205 step 5) assertTrue(!pointCovered(mesh, x.toFloat(), 42.5f), "gap was bridged")
	}

	@Test
	fun `bright hairline survives beside a thick opaque component`() {
		val width = 220
		val height = 130
		val rgba = ByteArray(width * height * 4)
		for (x in 10 until 210) rgba[(20 * width + x) * 4 + 3] = -1
		for (x in 10 until 210) for (y in 35 until 120) rgba[(y * width + x) * 4 + 3] = -1
		val mesh = assertNotNull(AdaptiveMeshGenerator.generate(width, height, rgba, 8, 48f))
		assertComponents(mesh, 2)
		assertTopology(mesh, 2)
		assertTrue(pointCovered(mesh, 100f, 20.5f))
	}

	@Test
	fun `staggered nearly touching diagonal ribbons stay independent`() {
		val width = 260
		val height = 200
		val rgba = ByteArray(width * height * 4)
		for (x in 15 until 235) for (y in 0 until height) {
			val center = 25 + x * 0.5
			if (abs(y + 0.5 - center) < 1.6 || (x >= 35 && abs(y + 0.5 - center - 6) < 1.6)) {
				rgba[(y * width + x) * 4 + 3] = -1
			}
		}
		val mesh = assertNotNull(AdaptiveMeshGenerator.generate(width, height, rgba, 8, 48f))
		assertComponents(mesh, 2)
		assertTrue(mesh.spinePaths.isEmpty(), "diagonal thin strokes should retain two envelope sides without a center seam")
		assertTopology(mesh, 2)
		for (x in 40..220 step 5) assertTrue(!pointCovered(mesh, x.toFloat(), 28f + x * 0.5f))
	}

	@Test
	fun `corner touching islands keep distinct vertex indices`() {
		val width = 200
		val height = 60
		val rgba = ByteArray(width * height * 4)
		for (x in 10 until 100) for (y in 10 until 20) rgba[(y * width + x) * 4 + 3] = -1
		for (x in 100 until 190) for (y in 20 until 30) rgba[(y * width + x) * 4 + 3] = -1
		val mesh = assertNotNull(AdaptiveMeshGenerator.generate(width, height, rgba, 8, 32f))
		assertComponents(mesh, 2)
		assertTopology(mesh, 2)
		assertTrue(!pointCovered(mesh, 50f, 25f) && !pointCovered(mesh, 150f, 15f))
	}

	@Test
	fun `tightly cropped hairline refines along its length`() {
		for ((width, height) in listOf(200 to 1, 1 to 200, 200 to 3)) {
			val rgba = ByteArray(width * height * 4)
			for (i in 0 until width * height) rgba[i * 4 + 3] = -1
			val mesh = assertNotNull(AdaptiveMeshGenerator.generate(width, height, rgba, 8, 64f))
			assertTrue(mesh.spinePaths.isEmpty())
			assertTrue(worstAspect(mesh) < 10f, "cropped hairline retained needle triangles")
			assertTopology(mesh, 1)
		}
	}

	@Test
	fun `high alpha threshold does not erase a bright hairline`() {
		val width = 120
		val rgba = ByteArray(width * 30 * 4)
		for (x in 10 until 110) rgba[(15 * width + x) * 4 + 3] = -1
		val mesh = assertNotNull(AdaptiveMeshGenerator.generate(width, 30, rgba, 200, 48f))
		assertTrue(pointCovered(mesh, 60f, 15.5f))
		assertTopology(mesh, 1)
	}

	@Test
	fun `adjacent one pixel strokes keep their entire painted width`() {
		val width = 220
		val rgba = ByteArray(width * 70 * 4)
		for (x in 10 until 210) for (y in listOf(40, 42)) rgba[(y * width + x) * 4 + 3] = -1
		val mesh = assertNotNull(AdaptiveMeshGenerator.generate(width, 70, rgba, 8, 48f))
		assertComponents(mesh, 2)
		assertTopology(mesh, 2)
		for (x in 11 until 209) {
			for (y in listOf(40f, 42f)) {
				assertTrue(pointCovered(mesh, x + 0.5f, y + 0.1f) && pointCovered(mesh, x + 0.5f, y + 0.9f),
					"one-pixel contour simplification clipped painted pixels")
			}
			assertTrue(!pointCovered(mesh, x + 0.5f, 41.5f))
		}
	}

	@Test
	fun `branched thin parts keep the open gap instead of becoming one ribbon`() {
		val width = 220
		val height = 80
		val rgba = ByteArray(width * height * 4)
		for (x in 15 until 205) for (y in 20 until 44) {
			if (y < 23 || y >= 41 || x >= 202) rgba[(y * width + x) * 4 + 3] = -1
		}
		val mesh = assertNotNull(AdaptiveMeshGenerator.generate(width, height, rgba, 8, 48f))
		assertTrue(mesh.spinePaths.isEmpty(), "a fork must use the general constrained mesh")
		assertTrue(!pointCovered(mesh, 100f, 33f))
		assertTopology(mesh, 1)
		assertComponents(mesh, 1)
	}

	@Test
	fun `single layer line mesh has empty innerLoops and valid topology`() {
		val width = 180
		val height = 180
		val rgba = ByteArray(width * height * 4)
		for (x in 30 until 150) for (y in 30 until 150) {
			rgba[(y * width + x) * 4 + 3] = -1
		}
		val mesh = assertNotNull(AdaptiveMeshGenerator.generate(
			width = width,
			height = height,
			rgba = rgba,
			alphaThreshold = 8,
			spacing = 32f,
			interiorSpacing = 32f,
			outerMargin = 2f,
			innerMargin = 2f,
			innerMarginEnabled = false,
		))
		assertTrue(mesh.innerLoops.isEmpty(), "single-line mesh must not produce innerLoops")
		assertTrue(mesh.boundaryLoops.isNotEmpty(), "boundaryLoops must exist")
		assertTopology(mesh, 1)
	}

	@Test
	fun `MeshSettings overload generates single and dual layer line correctly`() {
		val width = 180
		val height = 180
		val rgba = ByteArray(width * height * 4)
		for (x in 30 until 150) for (y in 30 until 150) {
			rgba[(y * width + x) * 4 + 3] = -1
		}
		val single = assertNotNull(AdaptiveMeshGenerator.generate(
			width, height, rgba, 8,
			MeshSettings(outerMargin = 2f, innerMarginEnabled = false, innerMargin = 2f, maxEdgeDistance = 32f, interiorDensity = 32f)
		))
		assertTrue(single.innerLoops.isEmpty())
		assertTopology(single, 1)

		val dual = assertNotNull(AdaptiveMeshGenerator.generate(
			width, height, rgba, 8,
			MeshSettings(outerMargin = 2f, innerMarginEnabled = true, innerMargin = 2f, maxEdgeDistance = 32f, interiorDensity = 32f)
		))
		assertTrue(dual.innerLoops.isNotEmpty())
		assertTopology(dual, 1)
	}

	private fun worstAspect(mesh: AdaptiveMeshGenerator.Result): Float = mesh.indices.toList().chunked(3).maxOf { ids ->
		val a = ids[0] * 2; val b = ids[1] * 2; val c = ids[2] * 2
		val areaTwice = abs((mesh.positions[b] - mesh.positions[a]) * (mesh.positions[c + 1] - mesh.positions[a + 1]) -
			(mesh.positions[b + 1] - mesh.positions[a + 1]) * (mesh.positions[c] - mesh.positions[a]))
		val longestSquared = (0..2).maxOf { i ->
			val p = ids[i] * 2; val q = ids[(i + 1) % 3] * 2
			val dx = mesh.positions[p] - mesh.positions[q]; val dy = mesh.positions[p + 1] - mesh.positions[q + 1]
			dx * dx + dy * dy
		}
		longestSquared / areaTwice
	}

	private fun assertComponents(mesh: AdaptiveMeshGenerator.Result, expected: Int) {
		val parent = IntArray(mesh.positions.size / 2) { it }
		fun root(index: Int): Int {
			var i = index
			while (parent[i] != i) { parent[i] = parent[parent[i]]; i = parent[i] }
			return i
		}
		for (ids in mesh.indices.toList().chunked(3)) {
			parent[root(ids[0])] = root(ids[1]); parent[root(ids[1])] = root(ids[2])
		}
		assertTrue(parent.indices.map { root(it) }.toSet().size == expected, "independent islands were connected")
	}

	private fun assertTopology(mesh: AdaptiveMeshGenerator.Result, euler: Int) {
		val uses = mutableMapOf<Pair<Int, Int>, Int>()
		for (i in mesh.indices.indices step 3) {
			val ids = mesh.indices.slice(i..i + 2)
			val a = ids[0] * 2; val b = ids[1] * 2; val c = ids[2] * 2
			val cross = (mesh.positions[b] - mesh.positions[a]) * (mesh.positions[c + 1] - mesh.positions[a + 1]) -
				(mesh.positions[b + 1] - mesh.positions[a + 1]) * (mesh.positions[c] - mesh.positions[a])
			assertTrue(cross < 0, "degenerate or reversed triangle: ${ids.map { mesh.positions[it * 2] to mesh.positions[it * 2 + 1] }}")
			for (j in 0..2) {
				val key = minOf(ids[j], ids[(j + 1) % 3]) to maxOf(ids[j], ids[(j + 1) % 3])
				uses[key] = (uses[key] ?: 0) + 1
			}
		}
		fun edges(loops: List<IntArray>) = loops.flatMap { loop -> loop.indices.map {
			minOf(loop[it], loop[(it + 1) % loop.size]) to maxOf(loop[it], loop[(it + 1) % loop.size])
		} }.toSet()
		val boundary = edges(mesh.boundaryLoops)
		assertTrue(uses.filterValues { it == 1 }.keys == boundary, "unexpected cracks or missing boundary")
		assertTrue(uses.values.all { it in 1..2 }, "non-manifold edge")
		assertTrue(edges(mesh.guideLoops).all { it in uses } && edges(mesh.innerLoops).all { it in uses })
		assertTrue(mesh.indices.toSet().size == mesh.positions.size / 2, "unused vertices")
		assertTrue(mesh.positions.size / 2 - uses.size + mesh.indices.size / 3 == euler, "incorrect Euler characteristic")
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
