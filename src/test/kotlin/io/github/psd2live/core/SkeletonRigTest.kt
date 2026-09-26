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
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.ParameterKind
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.RuntimeTarget
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

	/** A model for a runtime without blend shapes on rotation deformers, where every bone is a keyform axis. */
	private fun legacy(vararg drawables: Drawable) = model(*drawables).copy(runtimeTarget = RuntimeTarget.Cubism42)

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
		val baked = SkeletonRig.apply(legacy(arm), arm("arm"), frame)
		val expected = rest(arm)
		val actual = canvas(baked).getValue(arm.id)
		// Refinement may append vertices; the original ones keep their indices and must not move.
		for (i in expected.indices) assertEquals(expected[i], actual[i], 0.05f, "vertex coordinate $i")
		// One art mesh across the whole arm: one rotation deformer at the shoulder, pointing down the arm,
		// hung from the torso's bend, which the upper body turns.
		val skel = baked.deformers.filter { it.id.raw.startsWith("DeformSkel") }
		assertEquals(listOf("DeformSkelTorso", "DeformSkel_upper"), skel.map { it.id.raw })
		assertTrue(skel.first() is Deformer.Warp)
		val upper = skel.last() as Deformer.Rotation
		assertEquals(SkeletonRig.torsoWarpId, upper.parent)
		// Cubism's handle points up at 0 and turns clockwise; an arm hanging straight down rests at 180.
		assertEquals(180f, abs(upper.baseAngle), 0.01f)
		assertEquals(DeformerId("DeformSkel_upper"), baked.drawables.single().parentDeformerId)
	}

	private val breathId = DeformerId("DeformBodyZBreath")

	/** A breath warp over the whole body whose breath key lifts the top edge, stretching the body. */
	private fun breath() = Deformer.Warp(breathId, "Breath", bodyId, null, 1, 1, true, KeyformGrid(
		listOf(KeyformAxis(ParameterId("ParamBreath"), floatArrayOf(0f, 1f))),
		listOf(
			KeyformCell(intArrayOf(0), WarpLatticeForm(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))),
			KeyformCell(intArrayOf(1), WarpLatticeForm(floatArrayOf(0f, -0.04f, 1f, -0.04f, 0f, 1f, 1f, 1f))),
		),
	))

	@Test fun theBodyChainBendsReachTheLimbsAndTheTorso() {
		val arm = strip("arm", 100f, 95f, 435f, 18f, 10f)
		// Torso and skirt hang from the breath warp, in its normalized space, as the rig builder hangs them.
		val torso = strip("torso", 250f, 60f, 290f, 40f, 10f).copy(parentDeformerId = breathId)
		val skirt = strip("skirt", 250f, 330f, 450f, 50f, 10f).copy(parentDeformerId = breathId)
		// A shoe left on the body warp, bound to no bone, as the rig builder leaves an unsplit pair of legs.
		val shoe = strip("shoe", 250f, 460f, 490f, 40f, 10f)
		val source = PuppetModel(emptyList(), emptyList(), listOf(body(), breath()), listOf(arm, torso, skirt, shoe),
			listOf(arm, torso, skirt, shoe).map { OrgChild.Drawable(it.id) }, null)
		val waist = bone("chest", null, BoneRole.UPPER_BODY, 250f, 300f, 250f, 60f, listOf("torso"), side = Side.NONE)
		val spec = SkeletonSpec(bones = listOf(
			waist,
			bone("hip", null, BoneRole.LOWER_BODY, 250f, 300f, 250f, 400f, listOf("skirt"), side = Side.NONE),
			bone("upper", "chest", BoneRole.UPPER_ARM, 100f, 100f, 100f, 250f, listOf("arm")),
			bone("fore", "upper", BoneRole.FOREARM, 100f, 250f, 100f, 370f),
			bone("hand", "fore", BoneRole.HAND, 100f, 370f, 100f, 430f),
		))
		val baked = SkeletonRig.apply(source, spec, frame)
		val rest = canvas(baked)
		for (d in listOf(arm, torso, skirt, shoe)) {
			val expected = rest(d)
			val actual = rest.getValue(d.id)
			for (i in expected.indices) assertEquals(expected[i], actual[i], 0.05f, "rest coordinate $i of " + d.id.raw)
		}
		// The torso bend ends the body chain, so every bend above reaches every mesh and bone below it; the
		// legs bend beside the breath.
		val parent = baked.deformers.associate { it.id.raw to it.parent?.raw }
		assertEquals("DeformBodyXY", parent["DeformBodyZBreath"])
		assertEquals("DeformBodyZBreath", parent["DeformSkelTorso"])
		assertEquals("DeformBodyXY", parent["DeformSkelLegs"])
		assertEquals("DeformSkelTorso", parent["DeformSkel_upper"])
		assertEquals("DeformSkelTorso", baked.drawables.single { it.id == torso.id }.parentDeformerId?.raw)
		assertEquals("DeformSkelTorso", baked.drawables.single { it.id == skirt.id }.parentDeformerId?.raw)
		assertEquals("DeformSkelLegs", baked.drawables.single { it.id == shoe.id }.parentDeformerId?.raw)

		// The breath stretches the body, and the arm rides it: its shoulder lifts with the torso beside it.
		val breathing = canvas(baked, mapOf("ParamBreath" to 1f))
		val armRest = rest.getValue(arm.id)
		for (v in 0 until armRest.size / 2) {
			val y = armRest[v * 2 + 1]
			if (y > 105f) continue
			val lift = 0.04f * size * (1f - y / size)
			assertEquals(y - lift, breathing.getValue(arm.id)[v * 2 + 1], 0.5f, "shoulder vertex $v")
		}
		// It never lifts the feet.
		for (i in rest.getValue(shoe.id).indices) assertEquals(rest.getValue(shoe.id)[i], breathing.getValue(shoe.id)[i], 0.05f)

		// The upper body turns everything past the waist band about the waist, the arm with it; the skirt stays.
		val turned = canvas(baked, mapOf(waist.parameterId to 15f))
		val torsoRest = rest.getValue(torso.id)
		for (v in 0 until torsoRest.size / 2) {
			if (torsoRest[v * 2 + 1] > 200f) continue
			val p = rotate(torsoRest[v * 2], torsoRest[v * 2 + 1], 250f, 300f, 15f)
			assertEquals(p.first, turned.getValue(torso.id)[v * 2], 0.5f, "torso x $v")
			assertEquals(p.second, turned.getValue(torso.id)[v * 2 + 1], 0.5f, "torso y $v")
		}
		for (v in 0 until armRest.size / 2) {
			val p = rotate(armRest[v * 2], armRest[v * 2 + 1], 250f, 300f, 15f)
			assertEquals(p.first, turned.getValue(arm.id)[v * 2], 1f, "arm x $v")
			assertEquals(p.second, turned.getValue(arm.id)[v * 2 + 1], 1f, "arm y $v")
		}
		val skirtRest = rest.getValue(skirt.id)
		// Clear of the waist band, which reaches a quarter of the upper body's length below the waist, and of
		// the lattice row across its edge.
		for (v in 0 until skirtRest.size / 2) {
			if (skirtRest[v * 2 + 1] < 300f + 240f * 0.25f + size / 12f) continue
			assertEquals(skirtRest[v * 2], turned.getValue(skirt.id)[v * 2], 0.05f, "skirt x $v")
			assertEquals(skirtRest[v * 2 + 1], turned.getValue(skirt.id)[v * 2 + 1], 0.05f, "skirt y $v")
		}
		// The lower body turns the skirt about the waist and leaves the top of the torso.
		val hips = canvas(baked, mapOf("ParamSkelLowerBody" to 10f))
		val hem = (0 until skirtRest.size / 2).maxBy { skirtRest[it * 2 + 1] }
		val p = rotate(skirtRest[hem * 2], skirtRest[hem * 2 + 1], 250f, 300f, 10f)
		assertEquals(p.first, hips.getValue(skirt.id)[hem * 2], 0.5f)
		assertEquals(p.second, hips.getValue(skirt.id)[hem * 2 + 1], 0.5f)
		// The feet turn with the hips exactly as the skirt does.
		val shoeRest = rest.getValue(shoe.id)
		for (v in 0 until shoeRest.size / 2) {
			val q = rotate(shoeRest[v * 2], shoeRest[v * 2 + 1], 250f, 300f, 10f)
			assertEquals(q.first, hips.getValue(shoe.id)[v * 2], 0.5f, "shoe x $v")
			assertEquals(q.second, hips.getValue(shoe.id)[v * 2 + 1], 0.5f, "shoe y $v")
		}
		assertEquals(torsoRest[0], hips.getValue(torso.id)[0], 0.05f)
		// The pose tool draws the upper body where its warp put it.
		val chest = io.github.psd2live.ui.SkeletonPoseTool.posed(baked, spec, mapOf(ParameterId(waist.parameterId) to 15f))
			.single { it.bone.id == "chest" }
		val tip = rotate(250f, 60f, 250f, 300f, 15f)
		assertEquals(tip.first, chest.tailX, 0.5f)
		assertEquals(tip.second, chest.tailY, 0.5f)
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

	@Test fun aJointInsideOneMeshGetsNoRotationOfItsOwn() {
		// Upper arm and forearm drawn as one mesh, the hand as another.
		val arm = strip("arm", 100f, 95f, 370f, 16f, 10f)
		val hand = strip("hand", 100f, 370f, 435f, 16f, 10f)
		val spec = SkeletonSpec(bones = listOf(
			chest,
			bone("upper", "chest", BoneRole.UPPER_ARM, 100f, 100f, 100f, 250f, listOf("arm")),
			bone("fore", "upper", BoneRole.FOREARM, 100f, 250f, 100f, 370f),
			bone("hand", "fore", BoneRole.HAND, 100f, 370f, 100f, 430f, listOf("hand")),
		))
		val baked = SkeletonRig.apply(legacy(arm, hand), spec, frame)
		// The elbow bends inside the arm's keyforms; the hand hangs from the shoulder and carries the elbow.
		val rotations = baked.deformers.filterIsInstance<Deformer.Rotation>().associate { it.id.raw to it.parent?.raw }
		assertEquals(mapOf("DeformSkel_upper" to "DeformSkelTorso", "DeformSkel_hand" to "DeformSkel_upper"), rotations)
		val restPoints = canvas(baked).getValue(hand.id)
		val expectedRest = rest(hand)
		for (i in expectedRest.indices) assertEquals(expectedRest[i], restPoints[i], 0.05f, "rest coordinate $i")
		val a = 17f
		val b = -23f
		val c = 11f
		val values = mapOf("ParamArmLA" to a, "ParamArmLB" to b, "ParamHandL" to c)
		val posed = canvas(baked, values).getValue(hand.id)
		var checked = 0
		for (v in 0 until expectedRest.size / 2) {
			val x = expectedRest[v * 2]
			val y = expectedRest[v * 2 + 1]
			if (y < 400f) continue // clear of the wrist band
			var p = rotate(x, y, 100f, 370f, c)
			p = rotate(p.first, p.second, 100f, 250f, b)
			p = rotate(p.first, p.second, 100f, 100f, a)
			assertEquals(p.first, posed[v * 2], 1f)
			assertEquals(p.second, posed[v * 2 + 1], 1f)
			checked++
		}
		assertTrue(checked > 0)
		// The pose tool still finds the forearm, whose rotation is gone, and the hand on its own deformer.
		val bones = io.github.psd2live.ui.SkeletonPoseTool.posed(baked, spec, values.mapKeys { ParameterId(it.key) })
		var wrist = rotate(100f, 370f, 100f, 250f, b)
		wrist = rotate(wrist.first, wrist.second, 100f, 100f, a)
		for (id in listOf("fore", "hand")) {
			val bone = bones.single { it.bone.id == id }
			val (x, y) = if (id == "fore") bone.tailX to bone.tailY else bone.headX to bone.headY
			// The folded pivot runs on chords of the arc, within a pixel of it.
			assertEquals(wrist.first, x, 1f, "$id wrist x")
			assertEquals(wrist.second, y, 1f, "$id wrist y")
		}
	}

	@Test fun separateJointsTurnByBlendShapesThatAdd() {
		val arm = strip("arm", 100f, 95f, 435f, 18f, 10f)
		val spec = arm("arm")
		val baked = SkeletonRig.apply(model(arm), spec, frame)
		val old = SkeletonRig.apply(legacy(arm), spec, frame)
		val bones = listOf("ParamArmLA", "ParamArmLB", "ParamHandL").map(::ParameterId)
		// Every vertex follows one joint at most relative to the forearm, so each bone only adds.
		for (id in bones) {
			assertEquals(ParameterKind.BLEND_SHAPE, baked.parameters.single { it.id == id }.kind, id.raw)
			assertEquals(ParameterKind.NORMAL, old.parameters.single { it.id == id }.kind, id.raw)
		}
		assertTrue(baked.deformers.none { d -> d.axes().any { it in bones } })
		assertTrue(baked.drawables.none { d -> d.geometryGrid?.axes.orEmpty().any { it.parameterId in bones } })
		val mesh = baked.drawables.single()
		assertEquals(DeformerId("DeformSkel_fore"), mesh.parentDeformerId)
		val forms = mesh.blendShapes.filter { it.parameterId in bones }.sumOf { it.keys.size }
		val cells = old.drawables.single().geometryGrid!!.cells.size
		assertTrue(forms * 4 < cells, "$forms blend keys against $cells keyforms")
		// At rest nothing moves; turned, the arm lands where the multiplied keyforms put it.
		val expected = rest(arm)
		val actual = canvas(baked).getValue(arm.id)
		for (i in expected.indices) assertEquals(expected[i], actual[i], 0.05f, "vertex coordinate $i")
		for ((a, b, c) in listOf(Triple(17f, -23f, 11f), Triple(-60f, 95f, -70f), Triple(120f, -140f, 85f))) {
			val values = mapOf("ParamArmLA" to a, "ParamArmLB" to b, "ParamHandL" to c)
			val posed = canvas(baked, values).getValue(arm.id)
			val reference = canvas(old, values).getValue(arm.id)
			for (i in posed.indices) assertEquals(reference[i], posed[i], 2f, "vertex coordinate $i at $a/$b/$c")
			for (v in 0 until expected.size / 2) {
				val y = expected[v * 2 + 1]
				if (y < 410f) continue // clear of the wrist band
				var p = rotate(expected[v * 2], y, 100f, 370f, c)
				p = rotate(p.first, p.second, 100f, 250f, b)
				p = rotate(p.first, p.second, 100f, 100f, a)
				assertEquals(p.first, posed[v * 2], 1f)
				assertEquals(p.second, posed[v * 2 + 1], 1f)
			}
		}
	}

	@Test fun aLinkFoldsItsBlendShapeAlongTheArc() {
		// The forearm draws nothing: its turn folds into the hand's rotation as a blend shape keyed along the arc.
		val upperArm = strip("upper_arm", 100f, 95f, 250f, 16f, 10f)
		val hand = strip("hand", 100f, 372f, 435f, 16f, 10f)
		val spec = SkeletonSpec(bones = listOf(
			chest,
			bone("upper", "chest", BoneRole.UPPER_ARM, 100f, 100f, 100f, 250f, listOf("upper_arm")),
			bone("fore", "upper", BoneRole.FOREARM, 100f, 250f, 100f, 370f),
			bone("hand", "fore", BoneRole.HAND, 100f, 370f, 100f, 430f, listOf("hand")),
		))
		val baked = SkeletonRig.apply(model(upperArm, hand), spec, frame)
		assertEquals(ParameterKind.BLEND_SHAPE, baked.parameters.single { it.id.raw == "ParamArmLB" }.kind)
		val rotations = baked.deformers.filterIsInstance<Deformer.Rotation>().associateBy { it.id.raw }
		assertEquals(setOf("DeformSkel_upper", "DeformSkel_hand"), rotations.keys)
		val folded = rotations.getValue("DeformSkel_hand")
		assertEquals(DeformerId("DeformSkel_upper"), folded.parent)
		assertTrue(folded.geometryGrid!!.axes.isEmpty())
		assertTrue(folded.blendShapes.single { it.parameterId.raw == "ParamArmLB" }.keys.size > 3)
		val restPoints = rest(hand)
		for ((a, b, c) in listOf(Triple(17f, -23f, 11f), Triple(-40f, 130f, 60f))) {
			val posed = canvas(baked, mapOf("ParamArmLA" to a, "ParamArmLB" to b, "ParamHandL" to c)).getValue(hand.id)
			for (v in 0 until restPoints.size / 2) {
				if (restPoints[v * 2 + 1] < 395f) continue // clear of the wrist band
				var p = rotate(restPoints[v * 2], restPoints[v * 2 + 1], 100f, 370f, c)
				p = rotate(p.first, p.second, 100f, 250f, b)
				p = rotate(p.first, p.second, 100f, 100f, a)
				assertEquals(p.first, posed[v * 2], 1f, "x $v at $a/$b/$c")
				assertEquals(p.second, posed[v * 2 + 1], 1f, "y $v at $a/$b/$c")
			}
		}
	}

	@Test fun aMeshHangsUnderTheBoneItMostlyDraws() {
		// A sleeve from the lower upper arm to the wrist, mostly forearm, as a stocking reaches up the thigh.
		val sleeve = strip("sleeve", 100f, 200f, 370f, 16f, 10f)
		val spec = SkeletonSpec(bones = listOf(
			chest,
			bone("upper", "chest", BoneRole.UPPER_ARM, 100f, 100f, 100f, 250f, listOf("sleeve")),
			bone("fore", "upper", BoneRole.FOREARM, 100f, 250f, 100f, 370f),
		))
		val baked = SkeletonRig.apply(model(sleeve), spec, frame)
		// The shoulder stays as the pivot that carries the forearm, which carries the sleeve.
		val rotations = baked.deformers.filterIsInstance<Deformer.Rotation>().associate { it.id.raw to it.parent?.raw }
		assertEquals(mapOf("DeformSkel_upper" to "DeformSkelTorso", "DeformSkel_fore" to "DeformSkel_upper"), rotations)
		assertEquals("DeformSkel_fore", baked.drawables.single().parentDeformerId?.raw)
		val restPoints = rest(sleeve)
		val actual = canvas(baked).getValue(sleeve.id)
		for (i in restPoints.indices) assertEquals(restPoints[i], actual[i], 0.05f, "rest coordinate $i")
		// Both joints still move their own part of the drawing.
		val a = 20f
		val b = -30f
		val posed = canvas(baked, mapOf("ParamArmLA" to a, "ParamArmLB" to b)).getValue(sleeve.id)
		var checked = 0
		for (v in 0 until restPoints.size / 2) {
			val y = restPoints[v * 2 + 1]
			val p = when {
				y < 215f -> rotate(restPoints[v * 2], y, 100f, 100f, a)
				y > 300f -> rotate(restPoints[v * 2], y, 100f, 250f, b).let { rotate(it.first, it.second, 100f, 100f, a) }
				else -> continue
			}
			assertEquals(p.first, posed[v * 2], 1f, "x $v")
			assertEquals(p.second, posed[v * 2 + 1], 1f, "y $v")
			checked++
		}
		assertTrue(checked > 0)
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
		val crouch = SkeletonPoses.crouch.id
		val weight = SkeletonPoses.weight.id
		assertEquals(ParameterKind.BLEND_SHAPE, baked.parameters.single { it.id == crouch }.kind)
		// Poses are blend shapes, never keyform axes, so nothing multiplies by them.
		val poseIds = setOf(crouch, weight, SkeletonPoses.kneesIn.id, SkeletonPoses.hop.id)
		assertTrue(baked.deformers.none { d -> d.axes().any { it in poseIds } })
		assertTrue(baked.drawables.none { d -> d.geometryGrid?.axes.orEmpty().any { it.parameterId in poseIds } })
		assertTrue((baked.deformers.single { it.id == bodyId } as Deformer.Warp).blendShapes.map { it.parameterId }.toSet() == poseIds)
		// The meshes carry every pose that turns a leg joint, not the hop, which only lifts the body; the leg
		// lift is a gesture on the bones' own parameters.
		val allPoses = SkeletonPoses.all.mapTo(HashSet()) { it.id }
		for (d in baked.drawables) {
			assertEquals(poseIds - SkeletonPoses.hop.id, d.blendShapes.map { it.parameterId }.filter { it in allPoses }.toSet())
		}
		val restL = canvas(baked).getValue(left.id)
		// One pose at a time is solved exactly; both at once add their shapes, which holds only while one is slight.
		for ((values, tolerance) in listOf(1f to 0f, 0.6f to 0f, 0.37f to 0f, 0f to 1f, 0f to -1f, 0f to -0.45f).map { it to 1f } +
			listOf(0.12f to 0.3f).map { it to 1f }) {
			val (c, w) = values
			val params = mapOf(crouch.raw to c, weight.raw to w)
			val posed = canvas(baked, params).getValue(left.id)
			// The foot, clear of the ankle band, stays where it was drawn.
			for (v in 0 until restL.size / 2) {
				if (restL[v * 2 + 1] < 470f) continue
				assertEquals(restL[v * 2], posed[v * 2], tolerance, "foot x at $c/$w")
				assertEquals(restL[v * 2 + 1], posed[v * 2 + 1], tolerance, "foot y at $c/$w")
			}
			if (c != 0f && w != 0f) continue
			// The pose tool draws the ankle where the mesh put it.
			val bones = io.github.psd2live.ui.SkeletonPoseTool.posed(baked, legs(), params.mapKeys { ParameterId(it.key) })
			for (s in listOf("l", "r")) {
				val shin = bones.single { it.bone.id == "shin_$s" }
				assertEquals(if (s == "l") 210f else 290f, shin.tailX, 1f, "ankle x $s at $c/$w")
				assertEquals(450f, shin.tailY, 1f, "ankle y $s at $c/$w")
			}
		}
		// The hips did move: the top of the legs dropped when crouching.
		val crouched = canvas(baked, mapOf(crouch.raw to 1f)).getValue(left.id)
		assertTrue(crouched[1] > restL[1] + 5f)
	}

	@Test fun kneesInBendTheKneesTowardEachOtherWithTheFeetPlanted() {
		val left = strip("leg_l", 210f, 245f, 490f, 15f, 5f)
		val right = strip("leg_r", 290f, 245f, 490f, 15f, 5f)
		val baked = SkeletonRig.apply(model(left, right), legs(), frame)
		val kneesIn = SkeletonPoses.kneesIn.id
		assertEquals(ParameterKind.BLEND_SHAPE, baked.parameters.single { it.id == kneesIn }.kind)
		val restL = canvas(baked).getValue(left.id)
		for (k in listOf(0.25f, 0.6f, 1f)) {
			val posed = canvas(baked, mapOf(kneesIn.raw to k)).getValue(left.id)
			for (v in 0 until restL.size / 2) {
				if (restL[v * 2 + 1] < 470f) continue
				assertEquals(restL[v * 2], posed[v * 2], 1f, "foot x at $k")
				assertEquals(restL[v * 2 + 1], posed[v * 2 + 1], 1f, "foot y at $k")
			}
			val bones = io.github.psd2live.ui.SkeletonPoseTool.posed(baked, legs(), mapOf(kneesIn to k))
			for ((s, restX) in listOf("l" to 210f, "r" to 290f)) {
				val shin = bones.single { it.bone.id == "shin_$s" }
				assertEquals(restX, shin.tailX, 1f, "ankle x $s at $k")
				assertEquals(450f, shin.tailY, 1f, "ankle y $s at $k")
				// Both knees move toward the middle, 250.
				assertTrue(kotlin.math.abs(shin.headX - 250f) < kotlin.math.abs(restX - 250f) - 3f, "knee $s at $k: ${shin.headX}")
			}
		}
	}

	private fun Deformer.axes(): List<ParameterId> = when (this) {
		is Deformer.Warp -> geometryGrid?.axes.orEmpty().map { it.parameterId }
		is Deformer.Rotation -> geometryGrid?.axes.orEmpty().map { it.parameterId }
	}

	@Test fun aGesturePlaysOnTheBonesOwnParameters() {
		val arm = strip("arm", 100f, 95f, 435f, 18f, 10f)
		val spec = arm("arm")
		val baked = SkeletonRig.apply(model(arm), spec, frame)
		assertEquals(listOf(SkeletonPoses.armSway, SkeletonPoses.arms), SkeletonPoses.available(spec))
		// A gesture has no parameter and no shape in the rig.
		val gestures = setOf(SkeletonPoses.armSway.id, SkeletonPoses.arms.id)
		assertTrue(baked.parameters.none { it.id in gestures })
		assertTrue(baked.drawables.none { d -> d.blendShapes.any { it.parameterId in gestures } })
		assertTrue(baked.deformers.none { d -> d is Deformer.Rotation && d.blendShapes.any { it.parameterId in gestures } })
		// A motion plays it on the bones, added to what it writes there itself, point for point.
		val sway = SkeletonPoses.armSway.id.raw
		val played = SkeletonMotions.played(spec, listOf(
			sway to listOf(0f to 0f, 1f to 1f, 2f to -0.5f),
			"ParamArmLB" to listOf(0f to 10f, 2f to 30f),
		)).toMap()
		assertTrue(sway !in played)
		for (time in listOf(0.0, 0.3, 1.0, 1.7, 2.0)) {
			val value = SkeletonMotions.sample(listOf(0f to 0f, 1f to 1f, 2f to -0.5f), time, false)
			val turns = SkeletonPoses.boneTurns(spec, SkeletonPoses.armSway, value)
			for (bone in listOf("upper", "fore", "hand")) {
				val id = spec.bone(bone)!!.parameterId
				val own = if (id == "ParamArmLB") 10f + 10f * time.toFloat() else 0f
				assertEquals(own + (turns[bone] ?: 0f), SkeletonMotions.sample(played.getValue(id), time, false), 1e-3f, "$id at $time")
			}
		}
		// Played on the bones, the gesture stacks exactly with the arm turned by hand.
		val turns = SkeletonPoses.boneTurns(spec, SkeletonPoses.arms, 1f)
		val values = turns.map { (bone, turn) -> spec.bone(bone)!!.parameterId to turn + if (bone == "fore") -40f else 0f }.toMap()
		val posed = canvas(baked, values).getValue(arm.id)
		val expected = rest(arm)
		for (v in 0 until expected.size / 2) {
			if (expected[v * 2 + 1] < 410f) continue // clear of the wrist band
			var p = rotate(expected[v * 2], expected[v * 2 + 1], 100f, 370f, values.getValue("ParamHandL"))
			p = rotate(p.first, p.second, 100f, 250f, values.getValue("ParamArmLB"))
			p = rotate(p.first, p.second, 100f, 100f, values.getValue("ParamArmLA"))
			assertEquals(p.first, posed[v * 2], 1f)
			assertEquals(p.second, posed[v * 2 + 1], 1f)
		}
	}

	@Test fun aHopLiftsTheBodyWithTheLegsAndAddsOntoACrouch() {
		val left = strip("leg_l", 210f, 245f, 490f, 15f, 5f)
		val right = strip("leg_r", 290f, 245f, 490f, 15f, 5f)
		val baked = SkeletonRig.apply(model(left, right), legs(), frame)
		val hop = SkeletonPoses.hop.id.raw
		val crouch = SkeletonPoses.crouch.id.raw
		val rest = canvas(baked).getValue(left.id)
		// Nothing is planted: the whole leg rises as one piece.
		val hopped = canvas(baked, mapOf(hop to 1f)).getValue(left.id)
		val rise = hopped[1] - rest[1]
		assertTrue(rise < -10f, "rise $rise")
		for (v in 0 until rest.size / 2) {
			assertEquals(rest[v * 2], hopped[v * 2], 0.5f, "x of $v")
			assertEquals(rest[v * 2 + 1] + rise, hopped[v * 2 + 1], 0.5f, "y of $v")
		}
		// Out of a crouch, the bent legs are carried up unchanged.
		val crouched = canvas(baked, mapOf(crouch to 1f)).getValue(left.id)
		val both = canvas(baked, mapOf(crouch to 1f, hop to 1f)).getValue(left.id)
		for (i in crouched.indices) assertEquals(crouched[i] + if (i % 2 == 1) rise else 0f, both[i], 0.5f, "component $i")
		val shin = io.github.psd2live.ui.SkeletonPoseTool.posed(baked, legs(), mapOf(SkeletonPoses.hop.id to 1f)).single { it.bone.id == "shin_l" }
		assertEquals(450f + rise, shin.tailY, 1f)
	}

	@Test fun aLegLiftKicksOnlyOneLeg() {
		val spec = legs()
		val left = SkeletonPoses.boneTurns(spec, SkeletonPoses.legLift, 1f)
		assertEquals(setOf("thigh_r", "shin_r", "foot_r"), left.keys, "+1 lifts the character's left leg")
		assertTrue(left.getValue("shin_r") > 0f)
		assertEquals(setOf("thigh_l", "shin_l", "foot_l"), SkeletonPoses.boneTurns(spec, SkeletonPoses.legLift, -0.5f).keys)
	}

	@Test fun armPosesTurnBothArmsOrOnlyTheWavingOne() {
		fun side(s: String, side: Side, x: Float) = listOf(
			bone("upper_$s", "chest", BoneRole.UPPER_ARM, x, 100f, x, 250f, listOf("arm_$s"), side = side),
			bone("fore_$s", "upper_$s", BoneRole.FOREARM, x, 250f, x, 370f, side = side),
		)
		val spec = SkeletonSpec(bones = listOf(chest) + side("l", Side.LEFT, 140f) + side("r", Side.RIGHT, 60f))
		assertTrue(listOf(SkeletonPoses.arms, SkeletonPoses.wave, SkeletonPoses.waveSwing).all { it in SkeletonPoses.available(spec) })
		// Tucked, every joint turns toward the body; open, away from it.
		assertTrue(SkeletonPoses.boneTurns(spec, SkeletonPoses.arms, -1f).let { it.size == 4 && it.values.all { t -> t < 0f } })
		assertTrue(SkeletonPoses.boneTurns(spec, SkeletonPoses.arms, 1f).let { it.size == 4 && it.values.all { t -> t > 0f } })
		assertEquals(setOf("upper_r", "fore_r"), SkeletonPoses.boneTurns(spec, SkeletonPoses.wave, 1f).keys)
		assertEquals(setOf("fore_r"), SkeletonPoses.boneTurns(spec, SkeletonPoses.waveSwing, -1f).keys)
		assertTrue(SkeletonPoses.wave !in SkeletonPoses.available(SkeletonSpec(bones = listOf(chest) + side("l", Side.LEFT, 140f))))
	}

	@Test fun thePoseToolDrawsABoneOnItsDeformer() {
		val arm = strip("arm", 100f, 95f, 435f, 18f, 10f)
		val spec = arm("arm")
		val baked = SkeletonRig.apply(legacy(arm), spec, frame)
		val upper = spec.bone("upper")!!
		val values = mapOf(ParameterId(upper.parameterId) to 30f)
		val bone = io.github.psd2live.ui.SkeletonPoseTool.posed(baked, spec, values).single { it.bone.id == "upper" }
		// The arm hangs under the upper arm's deformer, whose handle runs up its local -y for the bone's length.
		val mapping = org.umamo.render.eval.drawableSpaceMapping(baked, values, arm.id)!!
		val tail = mapping.localToWorld(floatArrayOf(0f, -upper.length))
		assertEquals(bone.tailX, tail[0], 0.5f)
		assertEquals(bone.tailY, -tail[1], 0.5f)
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

	@Test fun motionsOnlyDriveThePosesAndLoop() {
		val tail = bone("tail_1", "hip", BoneRole.TAIL, 250f, 280f, 330f, 300f, side = Side.NONE).copy(chainIndex = 1)
		// A tail with no mesh has nothing to swing.
		assertTrue(SkeletonPoses.tailSwing !in SkeletonPoses.available(legs().withBone(tail)))
		val spec = legs().withBone(tail.copy(drawableIds = listOf("tail")))
		assertEquals(setOf(SkeletonPoses.crouch, SkeletonPoses.weight, SkeletonPoses.kneesIn, SkeletonPoses.hop, SkeletonPoses.legLift,
			SkeletonPoses.tailSwing), SkeletonPoses.available(spec).toSet())
		val idle = SkeletonMotions.idle(spec)
		// The idle keeps to the weight: the leg poses only add, so they never play together.
		val posed = idle.map { it.first }.filter { id -> SkeletonPoses.all.any { it.id.raw == id } }.toSet()
		assertEquals(setOf(SkeletonPoses.weight, SkeletonPoses.tailSwing).map { it.id.raw }.toSet(), posed)
		// The body follows on the same loop, without a skeleton too.
		val body = setOf("ParamBreath", "ParamBodyAngleX", "ParamBodyAngleZ", "ParamAngleZ")
		assertTrue(idle.map { it.first }.containsAll(body))
		assertTrue(SkeletonMotions.idle(null).map { it.first }.containsAll(body))
		for ((id, points) in idle) {
			assertEquals(points.first().second, points.last().second, 1e-3f, "$id loop seam")
			assertEquals(SkeletonMotions.IDLE_DURATION, points.last().first, 1e-4f, "$id duration")
			val pose = SkeletonPoses.all.singleOrNull { it.id.raw == id } ?: continue
			assertTrue(points.all { it.second in pose.min..pose.max }, "$id out of range")
		}
		assertTrue(idle.single { it.first == "ParamBreath" }.second.all { it.second in -1e-4f..1.0001f })
		for (tracks in listOf(SkeletonMotions.crouch(spec), SkeletonMotions.weightShift(spec), SkeletonMotions.tailSwing(spec))) {
			assertTrue(tracks.isNotEmpty())
			for ((id, points) in tracks) {
				assertTrue(SkeletonPoses.all.any { it.id.raw == id })
				assertEquals(0f, points.last().second, 1e-4f)
			}
		}
		// A leg one-shot holds the other leg pose at rest.
		assertTrue(SkeletonMotions.crouch(spec).single { it.first == SkeletonPoses.weight.id.raw }.second.all { it.second == 0f })
		assertTrue(SkeletonMotions.oneShot(SkeletonMotions.crouch(spec), 99.0) == null)
		assertEquals(SkeletonMotions.liveIdle(spec, 0.0).getValue(SkeletonPoses.weight.id),
			SkeletonMotions.liveIdle(spec, SkeletonMotions.IDLE_DURATION.toDouble()).getValue(SkeletonPoses.weight.id), 1e-3f)
		// A one-shot that moves the legs keeps to one planted leg pose and holds the rest at rest; every one ends at rest.
		val legIds = SkeletonPoses.legPoses.map { it.id.raw }
		val plantedIds = SkeletonPoses.legPoses.filterNot { it.airborne }.map { it.id.raw }
		for (preset in SkeletonMotions.presets.filterNot { it.loop }) {
			val name = preset.name
			val tracks = preset.tracks(spec)
			if (name == "Wave") assertTrue(tracks.isEmpty(), "no right arm to wave")
			else assertTrue(name == "TailSwing" || tracks.isNotEmpty(), "$name has legs to play")
			if (tracks.isEmpty()) continue
			val legTracks = tracks.map { it.first }.filter { it in legIds }.toSet()
			assertTrue(legTracks.isEmpty() || legTracks == legIds.toSet(), "$name leaves a leg pose free")
			assertTrue(tracks.count { (id, points) -> id in plantedIds && points.any { it.second != 0f } } <= 1, "$name plays two leg poses")
			for ((id, points) in tracks) {
				assertEquals(0f, points.last().second, 1e-4f, "$name $id")
				val pose = SkeletonPoses.all.singleOrNull { it.id.raw == id } ?: continue
				assertTrue(pose in SkeletonPoses.available(spec), "$name drives $id")
				assertTrue(points.all { it.second in pose.min..pose.max }, "$name $id out of range")
			}
			assertTrue(SkeletonMotions.oneShot(tracks, 99.0) == null)
		}
		assertTrue(SkeletonMotions.shy(spec).any { (id, points) -> id == SkeletonPoses.kneesIn.id.raw && points.any { it.second > 0f } })
		// The cute idle stands knock-kneed instead of shifting its weight, and still loops.
		val cute = SkeletonMotions.idleCute(spec).toMap()
		assertTrue(cute.getValue(SkeletonPoses.kneesIn.id.raw).all { it.second == 0.3f })
		assertTrue(cute.getValue(SkeletonPoses.weight.id.raw).all { it.second == 0f })
		for ((id, points) in cute) assertEquals(points.first().second, points.last().second, 1e-3f, "$id loop seam")
		assertTrue(SkeletonMotions.idleCute(null).isEmpty())
		// A pose whose every bone is driven by physics stays out of the idle.
		assertTrue(SkeletonMotions.idle(spec, exclude = setOf("ParamTail1")).none { it.first == SkeletonPoses.tailSwing.id.raw })
	}

}
