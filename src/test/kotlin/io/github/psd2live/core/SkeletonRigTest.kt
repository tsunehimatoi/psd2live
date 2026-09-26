package io.github.psd2live.core

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
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
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SkeletonRigTest {
	private val bodyId = DeformerId("DeformBodyXY")
	private val size = 500f
	private val frame = Bounds(0f, 0f, size, size)

	private fun body() = Deformer.Warp(bodyId, "Body", null, null, 1, 1, true,
		KeyformGrid(emptyList(), listOf(KeyformCell(intArrayOf(), WarpLatticeForm(floatArrayOf(0f, 0f, size, 0f, 0f, size, size, size))))))

	/** A vertical strip of quads from [top] to [bottom] around [x], [half] wide each side, rows every [step] px. */
	private fun strip(id: String, x: Float, top: Float, bottom: Float, half: Float, step: Float, columns: Int = 4): Drawable {
		val rows = ((bottom - top) / step).toInt().coerceAtLeast(1)
		val positions = ArrayList<Float>()
		for (r in 0..rows) for (c in 0..columns) {
			positions += (x - half + 2 * half * c / columns) / size
			positions += (top + (bottom - top) * r / rows) / size
		}
		val indices = ArrayList<Int>()
		for (r in 0 until rows) for (c in 0 until columns) {
			val a = r * (columns + 1) + c
			val b = a + 1
			val d = a + columns + 1
			val e = d + 1
			indices += listOf(a, b, e, a, e, d)
		}
		val p = positions.toFloatArray()
		return Drawable(DrawableId(id), id, bodyId, BlendMode.Normal, emptyList(), DrawableMesh(p, p.copyOf(), indices.toIntArray()), null)
	}

	private fun model(vararg drawables: Drawable) = PuppetModel(emptyList(), emptyList(), listOf(body()),
		drawables.toList(), drawables.map { OrgChild.Drawable(it.id) }, null)

	private fun bone(id: String, parent: String?, role: BoneRole, hx: Float, hy: Float, tx: Float, ty: Float,
		meshes: List<String> = emptyList(), side: Side = Side.LEFT) =
		SkeletonBone(id, id, parent, role, side, hx, hy, tx, ty, meshes)

	private val chest = bone("chest", null, BoneRole.UPPER_BODY, 100f, 200f, 100f, 60f, side = Side.NONE)
	private fun arm(vararg meshes: String) = SkeletonSpec(bones = listOf(
		chest,
		bone("upper", "chest", BoneRole.UPPER_ARM, 100f, 100f, 100f, 250f, meshes.toList()),
		bone("fore", "upper", BoneRole.FOREARM, 100f, 250f, 100f, 370f),
		bone("hand", "fore", BoneRole.HAND, 100f, 370f, 100f, 430f),
	))

	private fun canvas(model: PuppetModel, params: Map<String, Float> = emptyMap()): Map<DrawableId, FloatArray> =
		CpuDeformationEvaluator().evaluate(model, params.mapKeys { ParameterId(it.key) }).worldPositions
			.mapValues { (_, w) -> FloatArray(w.size) { if (it % 2 == 1) -w[it] else w[it] } }

	private fun rotate(x: Float, y: Float, cx: Float, cy: Float, deg: Float): Pair<Float, Float> {
		val p = SkeletonIk.rotate(x.toDouble(), y.toDouble(), cx.toDouble(), cy.toDouble(), deg.toDouble())
		return p[0].toFloat() to p[1].toFloat()
	}

	private fun rest(source: Drawable): FloatArray = FloatArray(source.mesh!!.positions.size) { source.mesh!!.positions[it] * size }

	@Test fun restPoseIsUnchanged() {
		val arm = strip("arm", 100f, 95f, 435f, 18f, 10f)
		val baked = SkeletonRig.apply(model(arm), arm("arm"), frame)
		val expected = rest(arm)
		val actual = canvas(baked).getValue(arm.id)
		// Refinement may append vertices; the original ones keep their indices and must not move.
		for (i in expected.indices) assertEquals(expected[i], actual[i], 0.05f, "vertex coordinate $i")
		// One art mesh across the whole arm: one rotation deformer at the shoulder, pointing down the arm,
		// hung from the upper body's deformer at the waist.
		val skel = baked.deformers.filter { it.id.raw.startsWith("DeformSkel_") }
		assertEquals(listOf("DeformSkel_chest", "DeformSkel_upper"), skel.map { it.id.raw })
		val upper = skel.last() as Deformer.Rotation
		assertEquals(DeformerId("DeformSkel_chest"), upper.parent)
		// Cubism's handle points up at 0 and turns clockwise; an arm hanging straight down rests at 180.
		assertEquals(180f, abs(upper.baseAngle), 0.01f)
		assertEquals(DeformerId("DeformSkel_upper"), baked.drawables.single().parentDeformerId)
	}

	@Test fun splitPartsGetTheirOwnJointsAndAreGlued() {
		// Upper arm, forearm and hand drawn as three meshes that share their seam rows.
		val upper = strip("upper", 100f, 95f, 250f, 16f, 10f)
		val fore = strip("fore", 100f, 250f, 370f, 16f, 10f)
		val hand = strip("hand", 100f, 370f, 435f, 16f, 10f)
		val spec = SkeletonSpec(bones = listOf(
			chest,
			bone("upper", "chest", BoneRole.UPPER_ARM, 100f, 100f, 100f, 250f, listOf("upper")),
			bone("fore", "upper", BoneRole.FOREARM, 100f, 250f, 100f, 370f, listOf("fore")),
			bone("hand", "fore", BoneRole.HAND, 100f, 370f, 100f, 430f, listOf("hand")),
		))
		val source = model(upper, fore, hand)
		val baked = SkeletonRig.apply(source, spec, frame, lockedTopology = setOf("upper", "fore", "hand"))
		val parentOf = baked.drawables.associate { it.id.raw to it.parentDeformerId?.raw }
		assertEquals("DeformSkel_upper", parentOf["upper"])
		assertEquals("DeformSkel_fore", parentOf["fore"])
		assertEquals("DeformSkel_hand", parentOf["hand"])
		val foreDeformer = baked.deformers.single { it.id.raw == "DeformSkel_fore" } as Deformer.Rotation
		assertEquals("DeformSkel_upper", foreDeformer.parent?.raw)
		// Nested deformers turn relative to their parent: a straight arm needs no extra turn at the elbow.
		assertEquals(0f, foreDeformer.baseAngle, 0.01f)
		// Pivot on the elbow, the end of the upper arm.
		val elbow = FloatArray(2).also { SkeletonRig.worlds(baked, emptyMap()).getValue(foreDeformer.id).apply(0f, 0f, it, 0) }
		assertEquals(100f, elbow[0], 0.01f)
		assertEquals(250f, elbow[1], 0.01f)
		// The seams are glued, and stay closed under a bend.
		assertTrue(baked.glues.any { setOf(it.meshA.raw, it.meshB.raw) == setOf("upper", "fore") && it.pairs.size >= 5 })
		val posed = canvas(baked, mapOf("ParamArmLB" to 50f, "ParamHandL" to -40f))
		val a = posed.getValue(DrawableId("upper"))
		val b = posed.getValue(DrawableId("fore"))
		val seamA = rest(upper).let { r -> (0 until r.size / 2).filter { abs(r[it * 2 + 1] - 250f) < 0.01f } }
		val seamB = rest(fore).let { r -> (0 until r.size / 2).filter { abs(r[it * 2 + 1] - 250f) < 0.01f } }
		assertTrue(seamA.isNotEmpty() && seamB.isNotEmpty())
		for (i in seamB) {
			val nearest = seamA.minOf { j -> hypot(a[j * 2] - b[i * 2], a[j * 2 + 1] - b[i * 2 + 1]) }
			assertTrue(nearest < 0.5f, "seam opened by $nearest")
		}
	}

	@Test fun overlappingPartsAreGluedAcrossTheOverlapWithTheChildLeading() {
		// The forearm is drawn over the end of the upper arm, and none of their vertices coincide.
		val upper = strip("upper", 100f, 95f, 265f, 16f, 10f)
		val fore = strip("fore", 100f, 242f, 370f, 15f, 9f, columns = 3)
		val spec = SkeletonSpec(bones = listOf(
			chest,
			bone("upper", "chest", BoneRole.UPPER_ARM, 100f, 100f, 100f, 250f, listOf("upper")),
			bone("fore", "upper", BoneRole.FOREARM, 100f, 250f, 100f, 370f, listOf("fore")),
		))
		val baked = SkeletonRig.apply(model(upper, fore), spec, frame)
		val glue = baked.glues.single { setOf(it.meshA.raw, it.meshB.raw) == setOf("upper", "fore") }
		val rest = canvas(baked)
		val upperRest = rest.getValue(DrawableId("upper"))
		val foreRest = rest.getValue(DrawableId("fore"))
		// Every forearm vertex over the upper arm is welded, each pair on one point at rest.
		val foreIndex = if (glue.meshA.raw == "fore") 0 else 1
		val glued = glue.pairs.mapTo(HashSet()) { if (foreIndex == 0) it.indexA else it.indexB }
		val original = fore.mesh!!.vertexCount
		val over = (0 until original).filter { foreRest[it * 2 + 1] <= 265f }
		assertTrue(over.size >= 8)
		for (v in over) assertTrue(v in glued, "overlap vertex $v not glued")
		for (pair in glue.pairs) {
			val (u, f) = if (foreIndex == 0) pair.indexB to pair.indexA else pair.indexA to pair.indexB
			val d = hypot(upperRest[u * 2] - foreRest[f * 2], upperRest[u * 2 + 1] - foreRest[f * 2 + 1])
			assertTrue(d < 0.05f, "pair apart by $d at rest")
			// The forearm is the child: it keeps its shape and the upper arm's end follows it.
			val (wUpper, wFore) = if (foreIndex == 0) pair.weightB to pair.weightA else pair.weightA to pair.weightB
			assertTrue(wFore < wUpper)
			assertEquals(1f, wFore + wUpper, 1e-6f)
		}
	}

	@Test fun rigidPartsFollowExactForwardKinematicsBetweenKeys() {
		val arm = strip("arm", 100f, 95f, 435f, 18f, 10f)
		val baked = SkeletonRig.apply(model(arm), arm("arm"), frame)
		val a = 17f
		val b = -23f
		val c = 11f
		val posed = canvas(baked, mapOf("ParamArmLA" to a, "ParamArmLB" to b, "ParamHandL" to c)).getValue(arm.id)
		val restPoints = rest(arm)
		var checked = 0
		for (v in 0 until restPoints.size / 2) {
			val x = restPoints[v * 2]
			val y = restPoints[v * 2 + 1]
			if (y < 400f) continue // only the hand, clear of the wrist band
			var p = rotate(x, y, 100f, 370f, c)
			p = rotate(p.first, p.second, 100f, 250f, b)
			p = rotate(p.first, p.second, 100f, 100f, a)
			// An unsplit arm hangs under the shoulder alone, so its hand rides the mesh keyforms: linear between
			// keys about 12 degrees apart, which is within a pixel this far from the elbow.
			assertEquals(p.first, posed[v * 2], 1.5f)
			assertEquals(p.second, posed[v * 2 + 1], 1.5f)
			checked++
		}
		assertTrue(checked > 0)
		// The bone tip itself, read from the deformer the runtime builds.
		val tip = io.github.psd2live.ui.SkeletonPoseTool.posed(baked, arm("arm"),
			mapOf(ParameterId("ParamArmLA") to a, ParameterId("ParamArmLB") to b, ParameterId("ParamHandL") to c))
			.single { it.bone.id == "hand" }.let { floatArrayOf(it.tailX, it.tailY) }
		var expected = rotate(100f, 430f, 100f, 370f, c)
		expected = rotate(expected.first, expected.second, 100f, 250f, b)
		expected = rotate(expected.first, expected.second, 100f, 100f, a)
		assertEquals(expected.first, tip[0], 0.5f)
		assertEquals(expected.second, tip[1], 0.5f)
	}

	@Test fun elbowBendKeepsTheLimbWidthAndNeverFolds() {
		val arm = strip("arm", 100f, 95f, 435f, 18f, 8f)
		val baked = SkeletonRig.apply(model(arm), arm("arm"), frame)
		val mesh = baked.drawables.single { it.id == arm.id }.mesh!!
		val restPoints = canvas(baked).getValue(arm.id)
		for (angle in listOf(-60f, -35f, 40f, 27f)) {
			val posed = canvas(baked, mapOf("ParamArmLB" to angle)).getValue(arm.id)
			// Every vertex keeps its distance to the elbow, the joint turning about it.
			for (v in 0 until restPoints.size / 2) {
				val before = hypot(restPoints[v * 2] - 100f, restPoints[v * 2 + 1] - 250f)
				val after = hypot(posed[v * 2] - 100f, posed[v * 2 + 1] - 250f)
				assertTrue(abs(after - before) <= before * 0.01f + 0.3f, "vertex $v at $angle°: $before -> $after")
			}
			assertNoFlips(mesh.indices, restPoints, posed, "elbow $angle°")
		}
		for ((id, angle) in listOf("ParamArmLA" to 60f, "ParamArmLA" to -20f, "ParamHandL" to 35f, "ParamHandL" to -35f)) {
			assertNoFlips(mesh.indices, restPoints, canvas(baked, mapOf(id to angle)).getValue(arm.id), "$id $angle°")
		}
	}

	private fun assertNoFlips(indices: IntArray, rest: FloatArray, posed: FloatArray, label: String) {
		fun area(p: FloatArray, a: Int, b: Int, c: Int) =
			(p[b * 2] - p[a * 2]) * (p[c * 2 + 1] - p[a * 2 + 1]) - (p[b * 2 + 1] - p[a * 2 + 1]) * (p[c * 2] - p[a * 2])
		for (t in indices.indices step 3) {
			val before = area(rest, indices[t], indices[t + 1], indices[t + 2])
			val after = area(posed, indices[t], indices[t + 1], indices[t + 2])
			if (abs(before) < 1e-3f) continue
			assertTrue(before * after > 0f, "triangle ${t / 3} flipped at $label")
		}
	}

	@Test fun coarseMeshesGainRowsAcrossJointsOnce() {
		val arm = strip("arm", 100f, 95f, 435f, 18f, 170f, columns = 1)
		val spec = arm("arm")
		val before = arm.mesh!!.vertexCount
		val baked = SkeletonRig.apply(model(arm), spec, frame)
		val after = baked.drawables.single { it.id == arm.id }.mesh!!.vertexCount
		assertTrue(after > before + 6, "expected joint rows, got $before -> $after")

		val bones = SkeletonRig.limbBones(spec)
		val ids = bones.mapTo(HashSet()) { it.id }
		val skin = SkeletonRig.skinBones(bones, bones.associate { it.id to SkeletonRig.limbParent(spec, it, ids) })
		val bands = skin.indices.filter { skin[it].parent >= 0 }.map {
			val n = SkeletonWeights.bandNormal(skin, it)
			JointBand(skin[it].headX, skin[it].headY, n[0], n[1], skin[it].blend)
		}
		val once = SkeletonMeshRefine.refine(model(arm), arm.id, rest(arm), bands)
		val twice = SkeletonMeshRefine.refine(once.first, arm.id, once.second, bands)
		assertEquals(once.first.drawables.single().mesh!!.vertexCount, twice.first.drawables.single().mesh!!.vertexCount)
	}

	@Test fun lockedTopologyIsNotRefined() {
		val arm = strip("arm", 100f, 95f, 435f, 18f, 170f, columns = 1)
		val baked = SkeletonRig.apply(model(arm), arm("arm"), frame, lockedTopology = setOf("arm"))
		assertEquals(arm.mesh!!.vertexCount, baked.drawables.single().mesh!!.vertexCount)
	}

	private fun legs(): SkeletonSpec {
		fun leg(s: String, side: Side, x: Float, toe: Float) = listOf(
			SkeletonBone("thigh_$s", "thigh", "hip", BoneRole.THIGH, side, x, 250f, x, 350f, listOf("leg_$s")),
			SkeletonBone("shin_$s", "shin", "thigh_$s", BoneRole.SHIN, side, x, 350f, x, 450f),
			SkeletonBone("foot_$s", "foot", "shin_$s", BoneRole.FOOT, side, x, 450f, x, 490f),
		)
		return SkeletonSpec(bones = listOf(bone("hip", null, BoneRole.LOWER_BODY, 250f, 240f, 250f, 280f, side = Side.NONE)) +
			leg("l", Side.RIGHT, 210f, -25f) + leg("r", Side.LEFT, 290f, 25f))
	}

	@Test fun crouchAndWeightShiftKeepTheFeetPlanted() {
		val left = strip("leg_l", 210f, 245f, 490f, 15f, 5f)
		val right = strip("leg_r", 290f, 245f, 490f, 15f, 5f)
		val baked = SkeletonRig.apply(model(left, right), legs(), frame)
		assertTrue(baked.parameters.any { it.id == SkeletonRig.crouchId })
		val restL = canvas(baked).getValue(left.id)
		for ((crouch, weight) in listOf(1f to 0f, 0.6f to 0.3f, 0.37f to -0.81f, 0f to 1f, 0.9f to -0.45f)) {
			val values = mapOf(SkeletonRig.crouchId.raw to crouch, SkeletonRig.weightId.raw to weight)
			val posed = canvas(baked, values).getValue(left.id)
			// The foot, clear of the ankle band, stays exactly where it was drawn.
			for (v in 0 until restL.size / 2) {
				if (restL[v * 2 + 1] < 470f) continue
				assertEquals(restL[v * 2], posed[v * 2], 1f, "foot x at $crouch/$weight")
				assertEquals(restL[v * 2 + 1], posed[v * 2 + 1], 1f, "foot y at $crouch/$weight")
			}
			// The pose tool draws the ankle where the mesh put it.
			val bones = io.github.psd2live.ui.SkeletonPoseTool.posed(baked, legs(), values.mapKeys { ParameterId(it.key) })
			for (s in listOf("l", "r")) {
				val shin = bones.single { it.bone.id == "shin_$s" }
				assertEquals(if (s == "l") 210f else 290f, shin.tailX, 1f, "ankle x $s at $crouch/$weight")
				assertEquals(450f, shin.tailY, 1f, "ankle y $s at $crouch/$weight")
			}
		}
		// The hips did move: the top of the legs dropped when crouching.
		val crouched = canvas(baked, mapOf(SkeletonRig.crouchId.raw to 1f)).getValue(left.id)
		assertTrue(crouched[1] > restL[1] + 5f)
	}

	@Test fun ikSolversReachTheirTargets() {
		val knee = SkeletonIk.twoBone(0.0, 0.0, 100.0, 100.0, 0.0, 150.0, 1.0)
		assertEquals(100.0, hypot(knee[0], knee[1]), 1e-6)
		assertEquals(100.0, hypot(knee[0] - 0.0, knee[1] - 150.0), 1e-6)
		val joints = doubleArrayOf(0.0, 0.0, 0.0, 100.0, 0.0, 200.0)
		val turns = SkeletonIk.ccd(joints, 120.0, 120.0, doubleArrayOf(-180.0, -180.0), doubleArrayOf(180.0, 180.0))
		val elbow = SkeletonIk.rotate(0.0, 100.0, 0.0, 0.0, turns[0])
		val tip = SkeletonIk.rotate(0.0, 200.0, 0.0, 100.0, turns[1]).let { SkeletonIk.rotate(it[0], it[1], 0.0, 0.0, turns[0]) }
		assertTrue(hypot(tip[0] - 120.0, tip[1] - 120.0) < 0.5, "tip ${tip.toList()} elbow ${elbow.toList()}")
	}

	@Test fun jsonRoundTripsAndReadsVersionOne() {
		val spec = arm("arm")
		val tuned = spec.withBone(spec.bone("fore")!!.copy(minAngle = -80f, maxAngle = 10f, blendWidth = 14f))
		assertEquals(tuned, SkeletonSpec.fromJson(tuned.toJson()))
		val v1 = buildJsonObject {
			put("version", JsonPrimitive(1))
			put("bones", kotlinx.serialization.json.buildJsonArray {
				add(buildJsonObject {
					put("id", JsonPrimitive("fore")); put("role", JsonPrimitive("FOREARM"))
					put("head", kotlinx.serialization.json.buildJsonArray { add(JsonPrimitive(0f)); add(JsonPrimitive(0f)) })
					put("tail", kotlinx.serialization.json.buildJsonArray { add(JsonPrimitive(0f)); add(JsonPrimitive(50f)) })
					put("joint", JsonPrimitive("PATH"))
				})
			})
		}
		val read = SkeletonSpec.fromJson(v1.jsonObject).bone("fore")!!
		assertEquals(BoneRole.FOREARM.minAngle, read.minAngle)
		assertEquals(null, read.blendWidth)
	}

	@Test fun versionTwoAnchorsFoldIntoUpperAndLowerBody() {
		fun anchor(id: String, parent: String?, role: String, drawables: List<String> = emptyList()) = buildJsonObject {
			put("id", JsonPrimitive(id)); put("role", JsonPrimitive(role))
			parent?.let { put("parent", JsonPrimitive(it)) }
			put("head", kotlinx.serialization.json.buildJsonArray { add(JsonPrimitive(0f)); add(JsonPrimitive(0f)) })
			put("tail", kotlinx.serialization.json.buildJsonArray { add(JsonPrimitive(0f)); add(JsonPrimitive(50f)) })
			put("drawables", kotlinx.serialization.json.buildJsonArray { drawables.forEach { add(JsonPrimitive(it)) } })
		}
		val v2 = buildJsonObject {
			put("version", JsonPrimitive(2))
			put("bones", kotlinx.serialization.json.buildJsonArray {
				add(anchor("root", null, "ROOT"))
				add(anchor("hip", "root", "HIP", listOf("skirt")))
				add(anchor("chest", "root", "CHEST", listOf("shirt")))
				add(anchor("neck", "chest", "NECK", listOf("neck")))
				add(anchor("head", "neck", "HEAD"))
				add(anchor("arm", "chest", "UPPER_ARM"))
				add(anchor("thigh", "hip", "THIGH"))
			})
		}
		val spec = SkeletonSpec.fromJson(v2)
		assertEquals(setOf(SkeletonSpec.UPPER_BODY_ID, SkeletonSpec.LOWER_BODY_ID, "head", "arm", "thigh"), spec.bones.map { it.id }.toSet())
		val upper = spec.bone(SkeletonSpec.UPPER_BODY_ID)!!
		assertEquals(BoneRole.UPPER_BODY, upper.role)
		assertEquals(null, upper.parentId)
		assertEquals(listOf("shirt", "neck"), upper.drawableIds)
		assertEquals(BoneRole.LOWER_BODY, spec.bone(SkeletonSpec.LOWER_BODY_ID)!!.role)
		assertEquals(SkeletonSpec.UPPER_BODY_ID, spec.bone("head")!!.parentId)
		assertEquals(SkeletonSpec.UPPER_BODY_ID, spec.bone("arm")!!.parentId)
		assertEquals(SkeletonSpec.LOWER_BODY_ID, spec.bone("thigh")!!.parentId)
	}

	@Test fun motionsLoopAndStayInsideTheLimits() {
		val spec = legs().withBone(bone("tail_1", "hip", BoneRole.TAIL, 250f, 280f, 330f, 300f, side = Side.NONE).copy(chainIndex = 1))
		val idle = SkeletonMotions.idle(spec)
		assertTrue(idle.any { it.first == SkeletonRig.weightId.raw } && idle.any { it.first == "ParamTail1" })
		for ((id, points) in idle) {
			assertEquals(points.first().second, points.last().second, 1e-3f, "$id loop seam")
			spec.bones.firstOrNull { it.parameterId == id }?.let { b ->
				assertTrue(points.all { it.second in b.minAngle..b.maxAngle }, "$id out of range")
			}
		}
		assertTrue(SkeletonMotions.crouch(spec).isNotEmpty())
		assertTrue(SkeletonMotions.weightShift(spec).isNotEmpty())
		assertTrue(SkeletonMotions.tailSwing(spec).isNotEmpty())
		assertTrue(SkeletonMotions.oneShot(SkeletonMotions.crouch(spec), 99.0) == null)
		assertEquals(SkeletonMotions.liveIdle(spec, 0.0).getValue(SkeletonRig.weightId),
			SkeletonMotions.liveIdle(spec, SkeletonMotions.IDLE_DURATION.toDouble()).getValue(SkeletonRig.weightId), 1e-3f)
		assertTrue(SkeletonMotions.idle(spec, exclude = setOf("ParamTail1")).none { it.first == "ParamTail1" })
	}
}
