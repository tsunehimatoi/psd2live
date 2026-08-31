package io.github.autolive2d.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RigGeometryTest {
	@Test
	fun `static drawable geometry is not keyed only at AngleX zero`() {
		val grid = RigBuilder.zeroMeshGrid(12)
		assertTrue(grid.axes.isEmpty())
		assertEquals(1, grid.cells.size)
		assertEquals(0, grid.cells.single().coordinate.size)
	}

	@Test
	fun `positive AngleY looks upward with restrained cylindrical curvature`() {
		val face = Bounds(0f, 0f, 200f, 240f)
		val center = RigBuilder.cylindricalFaceProject(100f, 120f, face, 0f, 30f, 1f)
		val leftEdge = RigBuilder.cylindricalFaceProject(0f, 120f, face, 0f, 30f, 1f)
		val down = RigBuilder.cylindricalFaceProject(100f, 120f, face, 0f, -30f, 1f)

		assertTrue(center.second < 120f, "positive AngleY must move the face upward in a Y-down canvas")
		assertTrue(down.second > 120f)
		assertTrue(abs(center.second - 120f) < 24f, "AngleY displacement should remain below 10% of face height")
		assertTrue(center.second < leftEdge.second - 5f, "cylindrical depth must bend a horizontal feature into a curve")
	}

	@Test
	fun `alpha silhouette produces triangles instead of a rectangular grid`() {
		val width = 96
		val height = 64
		val rgba = ByteArray(width * height * 4)
		for (y in 6 until 58) for (x in 7 until 89) {
			// U silhouette with a deep transparent notch from the top.
			val inNotch = x in 34..61 && y <= 39
			if (!inNotch) rgba[(y * width + x) * 4 + 3] = 0xff.toByte()
		}

		val mesh = assertNotNull(AdaptiveMeshGenerator.generate(width, height, rgba, 8, 14f))
		assertTrue(mesh.positions.size >= 12)
		assertTrue(mesh.indices.size >= 3 && mesh.indices.size % 3 == 0)
		assertTrue(mesh.indices.all { it in 0 until mesh.positions.size / 2 })

		val uniqueX = mesh.positions.filterIndexed { index, _ -> index % 2 == 0 }.toSet().size
		val uniqueY = mesh.positions.filterIndexed { index, _ -> index % 2 == 1 }.toSet().size
		assertTrue(uniqueX * uniqueY > mesh.positions.size / 2 * 2, "vertices should follow the contour, not a Cartesian rectangle")

		for (index in mesh.indices.indices step 3) {
			val a = mesh.indices[index] * 2
			val b = mesh.indices[index + 1] * 2
			val c = mesh.indices[index + 2] * 2
			val centerX = (mesh.positions[a] + mesh.positions[b] + mesh.positions[c]) / 3f
			val centerY = (mesh.positions[a + 1] + mesh.positions[b + 1] + mesh.positions[c + 1]) / 3f
			val inDeepNotch = centerX in 37f..58f && centerY < 36f
			assertTrue(!inDeepNotch, "Delaunay triangle crossed the transparent concavity")
			val cross = (mesh.positions[b] - mesh.positions[a]) * (mesh.positions[c + 1] - mesh.positions[a + 1]) -
				(mesh.positions[b + 1] - mesh.positions[a + 1]) * (mesh.positions[c] - mesh.positions[a])
			assertTrue(cross < 0f, "triangle winding changed")
		}
	}
}
