package io.github.autolive2d.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AdaptiveMeshGeneratorTest {
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
}
