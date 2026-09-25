package io.github.psd2live.core

import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.WarpLatticeForm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SkeletonConverterTest {
	private val bodyId = DeformerId("DeformBodyXY")
	private val frame = Bounds(0f, 0f, 100f, 100f)
	private fun body() = Deformer.Warp(bodyId, "Body", null, null, 1, 1, true,
		KeyformGrid(emptyList(), listOf(KeyformCell(intArrayOf(),
			WarpLatticeForm(floatArrayOf(0f, 0f, 100f, 0f, 0f, 100f, 100f, 100f))))))
	private fun mesh(id: String, vertices: FloatArray) = Drawable(DrawableId(id), id, bodyId, BlendMode.Normal, emptyList(),
		DrawableMesh(vertices, FloatArray(vertices.size), intArrayOf(0, 1, 2, 1, 3, 2)), null)
	private fun model(vararg drawables: Drawable) = PuppetModel(emptyList(), emptyList(), listOf(body()),
		drawables.toList(), drawables.map { OrgChild.Drawable(it.id) }, null)
	private fun upper(ids: List<String>) = SkeletonBone("upper", "Upper", null, BoneRole.UPPER_ARM, Side.LEFT,
		20f, 20f, 50f, 50f, ids)
	private fun fore(ids: List<String>) = SkeletonBone("fore", "Fore", "upper", BoneRole.FOREARM, Side.LEFT,
		50f, 50f, 80f, 80f, ids)

	@Test fun boneChainSharesWarpDeformerAndPreservesNeutralPose() {
		val upperMesh = mesh("upperMesh", floatArrayOf(.2f, .2f, .55f, .2f, .2f, .55f, .55f, .55f))
		val foreMesh = mesh("foreMesh", floatArrayOf(.48f, .48f, .8f, .48f, .48f, .8f, .8f, .8f))
		val source = model(upperMesh, foreMesh)
		val result = SkeletonConverter.apply(source, SkeletonSpec(bones = listOf(upper(listOf("upperMesh")), fore(listOf("foreMesh")))), frame)

		// Chain is in the same Warp Deformer
		val warps = result.deformers.filterIsInstance<Deformer.Warp>().filter { it.id.raw.startsWith("DeformWarpSkel_") }
		assertEquals(1, warps.size)
		val warp = warps.single()
		assertEquals(bodyId, warp.parent)
		assertEquals(warp.id, result.drawables.single { it.id.raw == "upperMesh" }.parentDeformerId)
		assertEquals(warp.id, result.drawables.single { it.id.raw == "foreMesh" }.parentDeformerId)

		val evaluator = CpuDeformationEvaluator()
		val before = evaluator.evaluate(source, emptyMap()).worldPositions
		val after = evaluator.evaluate(result, emptyMap()).worldPositions

		// Neutral pose accuracy
		for (id in listOf(upperMesh.id, foreMesh.id)) {
			val expected = before.getValue(id)
			val actual = after.getValue(id)
			expected.indices.forEach { assertEquals(expected[it], actual[it], .02f) }
		}

		// Pose rotation bends the joint
		val rotated = evaluator.evaluate(result, mapOf(ParameterId("ParamArmLB") to 40f)).worldPositions
		val afterFore = after.getValue(foreMesh.id)
		val rotatedFore = rotated.getValue(foreMesh.id)
		assertTrue(afterFore.indices.any { kotlin.math.abs(afterFore[it] - rotatedFore[it]) > 0.5f })
	}

	@Test fun singleMeshGetsWarpDeformerWithoutDeformPath() {
		val arm = mesh("arm", floatArrayOf(.2f, .2f, .8f, .2f, .2f, .8f, .8f, .8f))
		val result = SkeletonConverter.apply(model(arm), SkeletonSpec(bones = listOf(upper(listOf("arm")), fore(emptyList()))), frame)
		assertEquals(0, result.deformPaths.size)

		val warp = result.deformers.filterIsInstance<Deformer.Warp>().single { it.id.raw.startsWith("DeformWarpSkel_") }
		val grid = warp.geometryGrid
		assertTrue(grid != null)
		assertTrue(grid.axes.any { it.parameterId.raw == "ParamArmLA" })
		assertTrue(grid.axes.any { it.parameterId.raw == "ParamArmLB" })
		assertTrue(result.parameters.any { it.id.raw == "ParamArmLB" })
	}

	@Test fun serializedSkeletonRetainsConnectionsAndBinding() {
		val spec = SkeletonSpec(bones = listOf(upper(listOf("arm")), fore(emptyList())))
		val moved = spec.withJointMoved("upper", BoneEnd.TAIL, 54f, 58f)
		assertEquals(54f, moved.bone("fore")!!.headX)
		assertEquals(58f, moved.bone("fore")!!.headY)
		val rebound = moved.withDrawableBound("arm", "fore")
		assertEquals("fore", rebound.ownerOf("arm")?.id)
		assertEquals(rebound, SkeletonSpec.fromJson(rebound.toJson()))
	}
}
