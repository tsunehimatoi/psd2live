package io.github.psd2live.core

import java.nio.file.Path
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkeletonAutoBuilderTest {
	/** A filled strip of points from ([x0], [y0]) to ([x1], [y1]), [half] wide each side. */
	private fun strip(x0: Float, y0: Float, x1: Float, y1: Float, half: Float): List<Float> {
		val out = ArrayList<Float>()
		val length = hypot(x1 - x0, y1 - y0)
		val nx = -(y1 - y0) / length
		val ny = (x1 - x0) / length
		for (i in 0..40) for (j in -3..3) {
			val t = i / 40f
			out += x0 + (x1 - x0) * t + nx * half * j / 3f
			out += y0 + (y1 - y0) * t + ny * half * j / 3f
		}
		return out
	}

	@Test fun aFoldedArmPutsItsElbowAtTheFold() {
		// Shoulder at (0, 0), elbow at (40, 150), forearm back up to the hand at (120, 20).
		val points = (strip(0f, 0f, 40f, 150f, 12f) + strip(40f, 150f, 120f, 20f, 10f)).toFloatArray()
		val elbow = assertNotNull(SkeletonAutoBuilder.foldCorner(points, 0f, 0f, 120f, 20f))
		assertTrue(hypot(elbow.first - 40f, elbow.second - 150f) < 20f, "elbow at $elbow")
	}

	@Test fun aStraightArmFallsBackToProportions() {
		val points = strip(0f, 0f, 0f, 300f, 15f).toFloatArray()
		assertNull(SkeletonAutoBuilder.foldCorner(points, 0f, 0f, 0f, 300f))
		val line = assertNotNull(SkeletonAutoBuilder.medial(listOf(points to IntArray(0)), 0f, 0f))
		val elbow = line.at(line.jointNear(0.28, 0.62, 0.42))
		assertEquals(0.42f * line.length, elbow.second, 12f)
	}

	@Test fun legsGetBonesUnderTheHips() {
		for (sample in listOf("ds", "tml")) {
			val preview = PSD2LivePipeline().buildPreview(Path.of("examples/$sample/psd-input/$sample.psd"))
			val spec = SkeletonAutoBuilder.build(preview.analysis, preview.rig)
			assertEquals(2, spec.bones.count { it.role == BoneRole.THIGH }, sample)
			assertEquals(listOf(BoneRole.HEAD), spec.bones.filter { it.role.anchor }.map { it.role }, sample)
			assertEquals(listOf(BoneRole.UPPER_BODY, BoneRole.LOWER_BODY), spec.bones.filter { it.role.body }.map { it.role }, sample)
			for (bone in spec.bones.filter { it.role == BoneRole.UPPER_ARM || it.role == BoneRole.HEAD }) {
				assertEquals(SkeletonSpec.UPPER_BODY_ID, bone.parentId, "$sample ${bone.id}")
			}
			for (thigh in spec.bones.filter { it.role == BoneRole.THIGH }) assertEquals(SkeletonSpec.LOWER_BODY_ID, thigh.parentId, sample)
			// The head bone leans with the head Z rotation.
			assertEquals(preview.rig.initialHeadAngleZ, spec.bone(SkeletonSpec.HEAD_ID)!!.angleDeg, 0.5f, sample)
			// Hip joints sit under the hip line, not at the hem of a skirt.
			val anchors = preview.analysis.anchors
			for (thigh in spec.bones.filter { it.role == BoneRole.THIGH }) {
				assertTrue(thigh.headY < anchors.hipY + (anchors.hipY - anchors.shoulderY) * 0.2f, "$sample ${thigh.id}")
			}
		}
	}
}
