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
	fun `positive AngleY looks upward with restrained latitude redistribution`() {
		val rig = testFaceRig()
		val center = rig.surfacePoint(100f, 120f, 0f, 30f, 1f)
		val leftEdge = rig.surfacePoint(0f, 120f, 0f, 30f, 1f)
		val down = rig.surfacePoint(100f, 120f, 0f, -30f, 1f)

		assertTrue(center.second < 120f, "positive AngleY must move the face upward in a Y-down canvas")
		assertTrue(down.second > 120f)
		assertTrue(abs(center.second - 120f) < 24f, "AngleY displacement should remain below 10% of face height")
		assertTrue(center.second < leftEdge.second - 2f, "latitude surface must bend a horizontal feature into a curve")
	}

	@Test
	fun `nine pose feature corrections preserve identity while separating perceived depth`() {
		val rig = testFaceRig()
		val eye = Bounds(120f, 72f, 176f, 104f)
		val nose = Bounds(91f, 82f, 111f, 148f)
		val mouth = Bounds(66f, 150f, 136f, 180f)
		val eyeOffset = rig.featureOffset(FaceFeature.EYE, eye, 0.5f, 0.7f, 45f, 0f, 1f)
		val noseOffset = rig.featureOffset(FaceFeature.NOSE, nose, 0.5f, 0.7f, 45f, 0f, 1f)
		val mouthOffset = rig.featureOffset(FaceFeature.MOUTH, mouth, 0.5f, 0.7f, 45f, 0f, 1f)
		assertTrue(noseOffset.first > mouthOffset.first)
		assertTrue(mouthOffset.first > eyeOffset.first)

		val nearEye = Bounds(24f, 72f, 80f, 104f)
		val farEye = Bounds(120f, 72f, 176f, 104f)
		val nearEyeLeft = rig.featureOffset(FaceFeature.EYE, nearEye, 0f, 0.5f, 45f, 0f, 1f)
		val nearEyeRight = rig.featureOffset(FaceFeature.EYE, nearEye, 1f, 0.5f, 45f, 0f, 1f)
		val farEyeLeft = rig.featureOffset(FaceFeature.EYE, farEye, 0f, 0.5f, 45f, 0f, 1f)
		val farEyeRight = rig.featureOffset(FaceFeature.EYE, farEye, 1f, 0.5f, 45f, 0f, 1f)
		val nearWidth = 56f + nearEyeRight.first - nearEyeLeft.first
		val farWidth = 56f + farEyeRight.first - farEyeLeft.first
		assertTrue(farWidth in 48f..54f, "far eye should compress but retain its drawn identity")
		assertTrue(nearWidth in 56f..58f, "near eye width must remain almost unchanged")
		assertTrue(nearWidth > farWidth)
	}

	@Test
	fun `eye perspective keeps near width stable and swaps correctly for both yaw signs`() {
		val rig = testFaceRig()
		val leftEye = Bounds(24f, 72f, 80f, 104f)
		val rightEye = Bounds(120f, 72f, 176f, 104f)

		fun finalWidth(region: Bounds, angleX: Float, angleY: Float): Float {
			fun xAt(u: Float): Float {
				val sourceX = region.left + u * region.width
				val surface = rig.surfacePoint(sourceX, region.centerY, angleX, angleY, 1f)
				val local = rig.featureOffset(FaceFeature.EYE, region, u, 0.5f, angleX, angleY, 1f)
				return surface.first + local.first
			}
			return xAt(1f) - xAt(0f)
		}

		for (angleY in floatArrayOf(-30f, 0f, 30f)) {
			val positiveNear = finalWidth(leftEye, 45f, angleY)
			val positiveFar = finalWidth(rightEye, 45f, angleY)
			assertTrue(positiveNear in 54f..60f, "+X near eye changed too much at Y=$angleY: $positiveNear")
			assertTrue(positiveFar < positiveNear, "+X must keep left/near eye larger at Y=$angleY")

			val negativeNear = finalWidth(rightEye, -45f, angleY)
			val negativeFar = finalWidth(leftEye, -45f, angleY)
			assertTrue(negativeNear in 54f..60f, "-X near eye changed too much at Y=$angleY: $negativeNear")
			assertTrue(negativeFar < negativeNear, "-X must keep right/near eye larger at Y=$angleY")
		}
	}

	@Test
	fun `eyes and brows use a signed perspective parallelogram only in diagonal poses`() {
		val rig = testFaceRig()
		val pairs = listOf(
			FaceFeature.EYE to (Bounds(24f, 72f, 80f, 104f) to Bounds(120f, 72f, 176f, 104f)),
			FaceFeature.BROW to (Bounds(20f, 50f, 82f, 66f) to Bounds(118f, 50f, 180f, 66f)),
		)

		fun correctedPoint(feature: FaceFeature, region: Bounds, u: Float, angleX: Float, angleY: Float): Pair<Float, Float> {
			val offset = rig.featureOffset(feature, region, u, 0.5f, angleX, angleY, 1f)
			return (region.left + u * region.width + offset.first) to (region.centerY + offset.second)
		}

		fun slope(a: Pair<Float, Float>, b: Pair<Float, Float>): Float =
			(b.second - a.second) / (b.first - a.first)

		for ((feature, regions) in pairs) {
			val (left, right) = regions
			for (angleX in floatArrayOf(-45f, 45f)) {
				val yZeroLeftSlope = slope(
					correctedPoint(feature, left, 0f, angleX, 0f),
					correctedPoint(feature, left, 1f, angleX, 0f),
				)
				val yZeroRightSlope = slope(
					correctedPoint(feature, right, 0f, angleX, 0f),
					correctedPoint(feature, right, 1f, angleX, 0f),
				)
				assertEquals(0f, yZeroLeftSlope, 1e-6f, "$feature must not tilt on the AngleY=0 row")
				assertEquals(0f, yZeroRightSlope, 1e-6f, "$feature must not tilt on the AngleY=0 row")

				for (angleY in floatArrayOf(-30f, 30f)) {
					val leftEdgeSlope = slope(
						correctedPoint(feature, left, 0f, angleX, angleY),
						correctedPoint(feature, left, 1f, angleX, angleY),
					)
					val rightEdgeSlope = slope(
						correctedPoint(feature, right, 0f, angleX, angleY),
						correctedPoint(feature, right, 1f, angleX, angleY),
					)
					val centreLineSlope = slope(
						correctedPoint(feature, left, 0.5f, angleX, angleY),
						correctedPoint(feature, right, 0.5f, angleX, angleY),
					)
					assertTrue(abs(leftEdgeSlope) > 0.01f, "$feature diagonal correction disappeared at X=$angleX Y=$angleY")
					assertEquals(leftEdgeSlope, rightEdgeSlope, 1e-5f, "$feature envelopes must be parallel")
					assertEquals(leftEdgeSlope, centreLineSlope, 1e-5f, "$feature centre line must be parallel to its envelopes")
					assertEquals(kotlin.math.sign(angleX * angleY), kotlin.math.sign(leftEdgeSlope))
				}
			}
		}
	}

	@Test
	fun `continuous X curve expands near face and compresses far face`() {
		val rig = testFaceRig()
		fun projectedX(x: Float) = rig.surfacePoint(x, 120f, 45f, 0f, 1f).first
		val nearWidth = projectedX(50f) - projectedX(0f)
		val farWidth = projectedX(150f) - projectedX(100f)
		assertTrue(nearWidth > 50f, "near-side curve gradient must expand the local space")
		assertTrue(farWidth < 50f, "far-side curve gradient must compress the local space")
		assertTrue(projectedX(100f) - 100f > projectedX(0f) - 0f, "face center must roll faster than the skull contour")
	}

	@Test
	fun `pitch curve concentrates down pose and disperses up pose`() {
		val rig = testFaceRig()
		fun verticalSpan(angleY: Float): Float {
			val upper = rig.surfacePoint(100f, 80f, 0f, angleY, 1f).second
			val lower = rig.surfacePoint(100f, 160f, 0f, angleY, 1f).second
			return lower - upper
		}
		assertTrue(verticalSpan(-30f) < 80f, "down pose must concentrate facial latitudes")
		assertTrue(verticalSpan(30f) > 80f, "up pose must disperse facial latitudes")
	}

	@Test
	fun `corner pose contains a non additive XY correction`() {
		val rig = testFaceRig()
		val mouth = Bounds(66f, 150f, 136f, 180f)
		val corner = rig.featureOffset(FaceFeature.MOUTH, mouth, 0.35f, 0.6f, 45f, 30f, 1f)
		val yawOnly = rig.featureOffset(FaceFeature.MOUTH, mouth, 0.35f, 0.6f, 45f, 0f, 1f)
		val pitchOnly = rig.featureOffset(FaceFeature.MOUTH, mouth, 0.35f, 0.6f, 0f, 30f, 1f)
		assertTrue(abs(corner.first - yawOnly.first - pitchOnly.first) > 0.1f)
		assertTrue(abs(corner.second - yawOnly.second - pitchOnly.second) > 0.1f)
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

	private fun testFaceRig() = NinePoseFaceRig(
		face = Bounds(0f, 0f, 200f, 240f),
		centerX = 100f,
		centerY = 120f,
		radiusX = 100f,
		radiusY = 120f,
		eyeLineY = 88f,
		noseLineY = 126f,
		mouthLineY = 165f,
		chinX = 100f,
		chinY = 240f,
		regions = emptyList(),
	)
}
