package io.github.psd2live.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RigGeometryTest {
	@Test
	fun `body AngleX keeps equal width and mirrors its internal roll`() {
		val frame = Bounds(120f, 80f, 920f, 1280f)
		for (strength in floatArrayOf(0.5f, 1f, 2f)) {
			for (bodyY in floatArrayOf(-10f, 0f, 10f)) {
				for (v in floatArrayOf(0f, 1f / 6f, 0.5f, 5f / 6f, 1f)) {
					fun point(u: Float, angleX: Float) =
						RigBuilder.bodyWarpPoint(frame, u, v, angleX, bodyY, strength)
					val negativeLeft = point(0f, -10f)
					val negativeRight = point(1f, -10f)
					val positiveLeft = point(0f, 10f)
					val positiveRight = point(1f, 10f)
					val negativeWidth = negativeRight.first - negativeLeft.first
					val positiveWidth = positiveRight.first - positiveLeft.first
					assertEquals(frame.width, negativeWidth, 1e-3f, "-X changed body width at v=$v")
					assertEquals(frame.width, positiveWidth, 1e-3f, "+X changed body width at v=$v")
					assertEquals(negativeWidth, positiveWidth, 1e-4f, "X signs produced unequal widths at v=$v")

					for (u in floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
						val neutral = point(u, 0f).first
						val negativeOffset = point(u, -10f).first - neutral
						val positiveOffset = point(u, 10f).first - neutral
						assertEquals(-negativeOffset, positiveOffset, 1e-3f, "body roll is not mirrored at u=$u v=$v")
					}
				}
			}
		}
	}

	@Test
	fun `body AngleY keeps equal height and mirrors its internal roll`() {
		val frame = Bounds(120f, 80f, 920f, 1280f)
		for (strength in floatArrayOf(0.5f, 1f, 2f)) {
			for (bodyX in floatArrayOf(-10f, 0f, 10f)) {
				for (u in floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
					fun point(v: Float, angleY: Float) =
						RigBuilder.bodyWarpPoint(frame, u, v, bodyX, angleY, strength)
					val negativeHeight = point(1f, -10f).second - point(0f, -10f).second
					val positiveHeight = point(1f, 10f).second - point(0f, 10f).second
					assertEquals(frame.height, negativeHeight, 1e-3f, "-Y changed body height at u=$u")
					assertEquals(frame.height, positiveHeight, 1e-3f, "+Y changed body height at u=$u")
					for (v in floatArrayOf(0f, 1f / 6f, 0.5f, 5f / 6f, 1f)) {
						val neutral = point(v, 0f).second
						val negativeOffset = point(v, -10f).second - neutral
						val positiveOffset = point(v, 10f).second - neutral
						assertEquals(-negativeOffset, positiveOffset, 1e-3f, "body pitch is not mirrored at u=$u v=$v")
					}
				}
			}
		}
	}

	@Test
	fun `directional container gaze and hair warps do not acquire signed scale`() {
		val head = Bounds(40f, 30f, 440f, 550f)
		for (v in floatArrayOf(0f, 0.2f, 0.5f, 0.8f, 1f)) {
			fun headPoint(u: Float, x: Float, y: Float) =
				RigBuilder.headContainerPoint(head, 240f, 550f, u, v, x, y, 1f)
			for (angleY in floatArrayOf(-30f, 0f, 30f)) {
				val negativeWidth = headPoint(1f, -45f, angleY).first - headPoint(0f, -45f, angleY).first
				val positiveWidth = headPoint(1f, 45f, angleY).first - headPoint(0f, 45f, angleY).first
				assertEquals(head.width, negativeWidth, 1e-3f)
				assertEquals(negativeWidth, positiveWidth, 1e-3f)
			}
		}
		for (u in floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
			fun headPoint(v: Float, y: Float) =
				RigBuilder.headContainerPoint(head, 240f, 550f, u, v, 0f, y, 1f)
			val negativeHeight = headPoint(1f, -30f).second - headPoint(0f, -30f).second
			val positiveHeight = headPoint(1f, 30f).second - headPoint(0f, 30f).second
			assertEquals(head.height, negativeHeight, 1e-3f)
			assertEquals(negativeHeight, positiveHeight, 1e-3f)

			for (eyeY in floatArrayOf(-1f, 0f, 1f)) {
				val gazeNegativeWidth = RigBuilder.gazePoint(1f, 0.5f, -1f, eyeY).first -
					RigBuilder.gazePoint(0f, 0.5f, -1f, eyeY).first
				val gazePositiveWidth = RigBuilder.gazePoint(1f, 0.5f, 1f, eyeY).first -
					RigBuilder.gazePoint(0f, 0.5f, 1f, eyeY).first
				assertEquals(1f, gazeNegativeWidth, 1e-6f)
				assertEquals(gazeNegativeWidth, gazePositiveWidth, 1e-6f)
			}
		}

		val hairFrame = Bounds(0.12f, 0.08f, 0.88f, 0.91f)
		for (pitchParallax in floatArrayOf(-0.006f, 0.004f)) {
			for (angleX in floatArrayOf(-45f, 0f, 45f)) {
				fun hairPoint(v: Float, angleY: Float) =
					RigBuilder.hairFollowPoint(hairFrame, 0.5f, v, angleX, angleY, 0.014f, pitchParallax)
				val negativeHeight = hairPoint(1f, -30f).second - hairPoint(0f, -30f).second
				val positiveHeight = hairPoint(1f, 30f).second - hairPoint(0f, 30f).second
				assertEquals(hairFrame.height, negativeHeight, 1e-6f)
				assertEquals(negativeHeight, positiveHeight, 1e-6f)
			}
		}
	}

	@Test
	fun `Front hair follow warp widens on near turn side and mirrors`() {
		val hairFrame = Bounds(0.12f, 0.08f, 0.88f, 0.91f)
		for (v in floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
			fun point(u: Float, angleX: Float) =
				RigBuilder.hairFollowPoint(hairFrame, u, v, angleX, 0f, -0.020f, -0.006f, yawPerspective = 0.10f)

			val leftNeutral = point(0.5f, 0f).first - point(0f, 0f).first
			val rightNeutral = point(1f, 0f).first - point(0.5f, 0f).first
			assertEquals(leftNeutral, rightNeutral, 1e-5f)

			// When turning to look (AngleX > 0): front hair left side gets wider, right side gets narrower
			val leftTurnRight = point(0.5f, 45f).first - point(0f, 45f).first
			val rightTurnRight = point(1f, 45f).first - point(0.5f, 45f).first
			assertTrue(leftTurnRight > leftNeutral, "Left side must widen when AngleX > 0")
			assertTrue(rightTurnRight < rightNeutral, "Right side must narrow when AngleX > 0")

			// When turning right (AngleX > 0), hair shifts to the left; when turning left, hair shifts to the right
			assertTrue(point(0f, 45f).first < point(0f, 0f).first, "Front hair left edge must shift to the left when AngleX > 0")
			assertTrue(point(1f, 45f).first < point(1f, 0f).first, "Front hair right edge must shift to the left when AngleX > 0")
			assertTrue(point(0f, -45f).first > point(0f, 0f).first, "Front hair left edge must shift to the right when AngleX < 0")
			assertTrue(point(1f, -45f).first > point(1f, 0f).first, "Front hair right edge must shift to the right when AngleX < 0")

			// When turning to look (AngleX < 0): front hair left side gets narrower, right side gets wider (mirrored)
			val leftTurnLeft = point(0.5f, -45f).first - point(0f, -45f).first
			val rightTurnLeft = point(1f, -45f).first - point(0.5f, -45f).first
			assertTrue(leftTurnLeft < leftNeutral, "Left side must narrow when AngleX < 0")
			assertTrue(rightTurnLeft > rightNeutral, "Right side must widen when AngleX < 0")

			// Exact mirror symmetry
			assertEquals(leftTurnRight, rightTurnLeft, 1e-5f, "Left width at X>0 must equal right width at X<0")
			assertEquals(rightTurnRight, leftTurnLeft, 1e-5f, "Right width at X>0 must equal left width at X<0")

			// 3-column warp verification (columns at 0, 1/3, 2/3, 1)
			val col0WidthNeutral = point(1f / 3f, 0f).first - point(0f, 0f).first
			val col2WidthNeutral = point(1f, 0f).first - point(2f / 3f, 0f).first
			assertEquals(col0WidthNeutral, col2WidthNeutral, 1e-5f)

			val col0WidthTurnRight = point(1f / 3f, 45f).first - point(0f, 45f).first
			val col2WidthTurnRight = point(1f, 45f).first - point(2f / 3f, 45f).first
			assertTrue(col0WidthTurnRight > col0WidthNeutral, "Left column must widen when AngleX > 0")
			assertTrue(col2WidthTurnRight < col2WidthNeutral, "Right column must narrow when AngleX > 0")

			val col0WidthTurnLeft = point(1f / 3f, -45f).first - point(0f, -45f).first
			val col2WidthTurnLeft = point(1f, -45f).first - point(2f / 3f, -45f).first
			assertTrue(col0WidthTurnLeft < col0WidthNeutral, "Left column must narrow when AngleX < 0")
			assertTrue(col2WidthTurnLeft > col2WidthNeutral, "Right column must widen when AngleX < 0")
			assertEquals(col0WidthTurnRight, col2WidthTurnLeft, 1e-5f)
			assertEquals(col2WidthTurnRight, col0WidthTurnLeft, 1e-5f)
		}
	}

	@Test
	fun `BodyZ and hair swing retain direction symmetric dimensions`() {
		for (breath in floatArrayOf(0f, 0.5f, 1f)) {
			for (v in floatArrayOf(0f, 1f / 6f, 0.5f, 5f / 6f, 1f)) {
				fun bodyPoint(u: Float, z: Float) = RigBuilder.bodySecondaryWarpPoint(u, v, z, breath, 1f)
				val negativeWidth = bodyPoint(1f, -10f).first - bodyPoint(0f, -10f).first
				val positiveWidth = bodyPoint(1f, 10f).first - bodyPoint(0f, 10f).first
				assertEquals(negativeWidth, positiveWidth, 1e-6f)
				for (u in floatArrayOf(0f, 0.5f, 1f)) {
					val neutral = bodyPoint(u, 0f).first
					assertEquals(
						-(bodyPoint(u, -10f).first - neutral),
						bodyPoint(u, 10f).first - neutral,
						1e-6f,
					)
				}
			}
		}

		for (swing in floatArrayOf(0.25f, 0.5f, 1f)) {
			val negativeTip = RigBuilder.hairPhysicsPoint(0.5f, 1f, -swing, 0.12f, 0.03f)
			val neutralTip = RigBuilder.hairPhysicsPoint(0.5f, 1f, 0f, 0.12f, 0.03f)
			val positiveTip = RigBuilder.hairPhysicsPoint(0.5f, 1f, swing, 0.12f, 0.03f)
			assertEquals(-(negativeTip.first - neutralTip.first), positiveTip.first - neutralTip.first, 1e-6f)
			assertEquals(negativeTip.second, positiveTip.second, 1e-6f)
			assertTrue(positiveTip.second < neutralTip.second, "both swing directions should shorten/lift the hair equally")
			assertEquals(0f to 0f, RigBuilder.hairPhysicsPoint(0f, 0f, swing, 0.12f, 0.03f))
		}
	}

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
			assertEquals(positiveNear, negativeNear, 1e-3f, "near-eye width differs between yaw signs at Y=$angleY")
			assertEquals(positiveFar, negativeFar, 1e-3f, "far-eye width differs between yaw signs at Y=$angleY")
		}
	}

	@Test
	fun `face surface yaw is a true mirror at every pitch key`() {
		val rig = testFaceRig()
		for (angleY in floatArrayOf(-30f, 0f, 30f)) {
			for (distanceX in floatArrayOf(0f, 20f, 55f, 100f)) {
				for (sourceY in floatArrayOf(20f, 88f, 150f, 230f)) {
					val positive = rig.surfacePoint(rig.centerX + distanceX, sourceY, 45f, angleY, 1f)
					val negative = rig.surfacePoint(rig.centerX - distanceX, sourceY, -45f, angleY, 1f)
					assertEquals(rig.centerX * 2f, positive.first + negative.first, 1e-3f)
					assertEquals(positive.second, negative.second, 1e-3f)
				}
			}
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
