package io.github.psd2live.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.umamo.format.art.LayerBlend
import org.umamo.format.art.LayerBounds
import org.umamo.format.art.LayerId
import org.umamo.format.art.LayerRaster
import org.umamo.format.art.SourceArt
import org.umamo.format.art.SourceLayer
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.ParameterId
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The skeleton's contract against a synthetic character: which joints it infers from the layer
 * preset, what those joints lower to, and - the load-bearing one - that lowering a skeleton does not
 * move a single pixel at the neutral pose.
 */
class SkeletonRigTest {
	private class TestLayer(
		override val id: LayerId,
		override val name: String,
		override val order: Int,
		override val bounds: LayerBounds,
		override val raster: LayerRaster,
	) : SourceLayer {
		override val groupPath: String = ""
		override val opacity: Float = 1f
		override val clipped: Boolean = false
		override val blend: LayerBlend = LayerBlend.Normal
	}

	private fun solid(width: Int, height: Int): LayerRaster =
		LayerRaster(width, height, ByteArray(width * height * 4) { index -> if (index % 4 == 3) -1 else 90 })

	private fun layer(name: String, order: Int, x: Int, y: Int, width: Int, height: Int) =
		TestLayer(LayerId(name), name, order, LayerBounds(x, y, width, height), solid(width, height))

	/**
	 * A character cut the way the two branches of the inference need: the viewer-left arm is two
	 * ArtMeshes (so the elbow is a seam), the viewer-right arm is one (so the elbow is inside a mesh).
	 */
	private fun character(): SourceArt = object : SourceArt {
		override val widthPx: Int = 400
		override val heightPx: Int = 800
		override val layers: List<SourceLayer> = listOf(
			layer("face", 0, 160, 60, 80, 140),
			layer("body", 1, 150, 200, 100, 300),
			// Viewer-left is the character's right: two segments, so two rotations.
			layer("arm r 1", 2, 104, 220, 40, 120),
			layer("arm r 2", 3, 104, 344, 40, 110),
			// Viewer-right is the character's left: one unbroken mesh, so a rotation plus a path.
			layer("arm l", 4, 258, 220, 40, 234),
			layer("leg r 1", 5, 162, 500, 34, 240),
			layer("leg l 1", 6, 206, 500, 34, 240),
			layer("tail", 7, 244, 470, 130, 44),
		)
	}

	private fun analysis(): PipelineAnalysis = CharacterAnalyzer.analyze(character(), PipelineConfig())

	private fun build(skeleton: Skeleton?): BuiltRig {
		val analysis = analysis()
		val config = PipelineConfig(rigEdits = RigEditOverlay(skeleton = skeleton))
		val atlas = AtlasPacker.pack(analysis.layers, config.atlasSize, config.texturePadding, config.textureUpscale)
		return RigBuilder.build(analysis, atlas, config)
	}

	@Test
	fun inferenceReadsJointsFromTheLayerPreset() {
		val skeleton = SkeletonInference.infer(analysis())

		// The spine is always there, so a PSD naming nothing recognizable still rigs.
		assertEquals(listOf("Root", "Hip", "Waist", "Chest"), skeleton.chain(BoneChain.SPINE, Side.NONE).map { it.id })
		assertNull(skeleton.byId.getValue("Chest").drive, "the chest only anchors; the torso warp still breathes")

		// Two ArtMeshes on the character's right: a seam at the elbow, so both joints pivot.
		val right = skeleton.chain(BoneChain.ARM, Side.RIGHT)
		assertEquals(2, right.size)
		assertTrue(right.all { it.binding == BoneBinding.ROTATION })
		assertEquals("Chest", right.first().parentId)
		assertEquals("ArmRA", right.last().parentId)

		// One ArtMesh on the character's left: the shoulder is a real seam, the elbow is not.
		val left = skeleton.chain(BoneChain.ARM, Side.LEFT)
		assertEquals(listOf(BoneBinding.ROTATION, BoneBinding.PATH), left.map { it.binding })
		assertEquals(left.first().layerIds, left.last().layerIds, "a path bone bends the mesh it shares")

		// The guessed bend sits inside the arm, between the shoulder and the hand.
		val bend = left.last()
		val shoulder = left.first()
		assertTrue(
			hypot(bend.pivotX - shoulder.pivotX, bend.pivotY - shoulder.pivotY) > 1f &&
				hypot(bend.pivotX - shoulder.tipX, bend.pivotY - shoulder.tipY) > 1f,
			"the bend must not collapse onto either end of the limb",
		)

		// Each leg is one mesh here, so each side reads like the unbroken arm: a hip pivot and a knee path.
		for (side in listOf(Side.LEFT, Side.RIGHT)) {
			assertEquals(
				listOf(BoneBinding.ROTATION, BoneBinding.PATH),
				skeleton.chain(BoneChain.LEG, side).map { it.binding },
				"leg $side",
			)
		}
		assertTrue(skeleton.chain(BoneChain.TAIL, Side.NONE).isNotEmpty(), "a tail layer becomes a tail chain")
	}

	@Test
	fun loweringNestsBonesAndLeavesTheTorsoOnItsBreathWarp() {
		val skeleton = SkeletonInference.infer(analysis())
		val rig = build(skeleton)
		val puppet = rig.puppet
		val byId = puppet.deformers.associateBy { it.id.raw }

		// The spine is the new top of the rig, and the body warp hangs off its deepest joint.
		assertNull(byId.getValue("BoneRoot").parent)
		assertEquals(DeformerId("BoneRoot"), byId.getValue("BoneHip").parent)
		assertEquals(DeformerId("BoneChest"), byId.getValue("DeformBodyXY").parent)
		assertEquals(DeformerId("DeformBodyXY"), byId.getValue("DeformBodyZBreath").parent)

		// A path bone bends a mesh instead of pivoting it, so it contributes no deformer.
		assertNotNull(byId["BoneArmLA"])
		assertNull(byId["BoneArmLB"], "a path joint is not a rotation deformer")
		assertEquals(DeformerId("BoneArmRA"), byId.getValue("BoneArmRB").parent)

		val layerIdByDrawable = rig.layerIdByDrawableId
		fun parentOf(layerName: String): DeformerId? = puppet.drawables
			.first { layerIdByDrawable[it.id.raw] == layerName }
			.parentDeformerId

		assertEquals(DeformerId("BoneArmRA"), parentOf("arm r 1"))
		assertEquals(DeformerId("BoneArmRB"), parentOf("arm r 2"))
		assertEquals(DeformerId("BoneArmLA"), parentOf("arm l"))
		assertEquals(DeformerId("BoneTailA"), parentOf("tail"))
		assertEquals(DeformerId("DeformBodyZBreath"), parentOf("body"), "the torso keeps breathing")

		// Every bone that swings owns a parameter, filed in its own folder.
		val parameterIds = puppet.parameters.map { it.id.raw }
		assertTrue(SkeletonParameters.CROUCH in parameterIds)
		assertTrue(SkeletonParameters.WEIGHT in parameterIds)
		assertTrue(SkeletonParameters.arm(Side.RIGHT, 0) in parameterIds)
		assertTrue(SkeletonParameters.arm(Side.LEFT, 1) in parameterIds)
		assertTrue(SkeletonParameters.tail(0) in parameterIds)
	}

	/**
	 * The whole coordinate story in one assertion: a bone lowers with a zero base angle and a unit
	 * frame at its pivot, so the rig it produces renders exactly the rig it replaced. Any drift here
	 * means a mesh was normalized against a frame its deformer was not laid out on.
	 */
	@Test
	fun theNeutralPoseIsUnchangedByAddingASkeleton() {
		val plain = build(null)
		val rigged = build(SkeletonInference.infer(analysis()))
		val evaluator = CpuDeformationEvaluator()

		val before = evaluator.evaluate(plain.puppet, emptyMap()).worldPositions
		val after = evaluator.evaluate(rigged.puppet, emptyMap()).worldPositions
		assertEquals(before.keys, after.keys)
		for ((id, expected) in before) {
			val actual = after.getValue(id)
			assertEquals(expected.size, actual.size, "vertex count of ${id.raw}")
			for (index in expected.indices) {
				assertEquals(expected[index], actual[index], 0.05f, "${id.raw} component $index")
			}
		}
	}

	@Test
	fun swingingOneArmLeavesTheRestOfTheBodyStill() {
		val rig = build(SkeletonInference.infer(analysis()))
		val puppet = rig.puppet
		val layerIdByDrawable = rig.layerIdByDrawableId
		fun drawableFor(layerName: String) = puppet.drawables.first { layerIdByDrawable[it.id.raw] == layerName }.id

		val evaluator = CpuDeformationEvaluator()
		val rest = evaluator.evaluate(puppet, emptyMap()).worldPositions
		val swung = evaluator.evaluate(
			puppet,
			mapOf(ParameterId(SkeletonParameters.arm(Side.RIGHT, 0)) to 1f),
		).worldPositions

		fun travel(layerName: String): Float {
			val id = drawableFor(layerName)
			val a = rest.getValue(id)
			val b = swung.getValue(id)
			return (a.indices step 2).maxOf { hypot(a[it] - b[it], a[it + 1] - b[it + 1]) }
		}

		assertTrue(travel("arm r 1") > 1f, "the swung arm has to move")
		assertTrue(travel("arm r 2") > 1f, "the forearm inherits its parent's turn")
		assertTrue(travel("body") < 0.05f, "the torso must not follow one arm")
		assertTrue(travel("arm l") < 0.05f, "the other arm must not follow either")
		assertTrue(travel("face") < 0.05f, "the head must not follow either")
	}

	@Test
	fun anUnbrokenArmBendsThroughADeformPath() {
		val rig = build(SkeletonInference.infer(analysis()))
		val puppet = rig.puppet
		val drawableId = puppet.drawables
			.first { rig.layerIdByDrawableId[it.id.raw] == "arm l" }
			.id

		val path = puppet.deformPaths.firstOrNull { it.drawableId == drawableId }
		assertNotNull(path, "a single-mesh limb gets a path to bend along")
		assertEquals(3, path.points.size, "root, bend and tip")
		assertTrue(path.points.any { it.corner }, "the bend is a corner, so the curve folds there")

		// The bend is baked into the drawable's own keyforms, on the path bone's parameter.
		val grid = assertNotNull(puppet.drawables.first { it.id == drawableId }.geometryGrid)
		assertEquals(
			listOf(SkeletonParameters.arm(Side.LEFT, 1)),
			grid.axes.map { it.parameterId.raw },
		)
		assertTrue(
			grid.cells.any { cell -> cell.form.positionDeltas.any { kotlin.math.abs(it) > 0.5f } },
			"at least one key has to actually move the mesh",
		)
	}

	@Test
	fun draggingAJointCarriesTheBonesBelowIt() {
		val skeleton = SkeletonInference.infer(analysis())
		val shoulder = skeleton.chain(BoneChain.ARM, Side.RIGHT).first()
		val elbowBefore = skeleton.chain(BoneChain.ARM, Side.RIGHT).last()

		val moved = skeleton.withJointMoved(shoulder.id, shoulder.pivotX + 17f, shoulder.pivotY - 9f)
		val elbowAfter = moved.chain(BoneChain.ARM, Side.RIGHT).last()

		assertEquals(elbowBefore.pivotX + 17f, elbowAfter.pivotX, 1e-3f)
		assertEquals(elbowBefore.pivotY - 9f, elbowAfter.pivotY, 1e-3f)
		// The local pivot a rotation stores is unchanged: the whole limb moved, not the joint within it.
		assertEquals(skeleton.localPivotOf(elbowBefore), moved.localPivotOf(elbowAfter))
	}

	@Test
	fun theSkeletonSurvivesAJsonRoundTrip() {
		val skeleton = SkeletonInference.infer(analysis())
		assertEquals(skeleton, Skeleton.fromJson(skeleton.toJson()))
	}

	@Test
	fun theIdleMovesEachLimbOnItsOwnPhase() {
		val skeleton = SkeletonInference.infer(analysis())
		val tracks = SkeletonMotions.idle(skeleton).toMap()

		val leftShoulder = assertNotNull(tracks[SkeletonParameters.arm(Side.LEFT, 0)])
		val rightShoulder = assertNotNull(tracks[SkeletonParameters.arm(Side.RIGHT, 0)])
		assertTrue(
			leftShoulder.zip(rightShoulder).any { (a, b) -> a.second * b.second < -0.01f },
			"the two arms must be opposed, not swaying together",
		)

		// A loop has to close, or the motion snaps every time it restarts.
		for ((parameterId, points) in tracks) {
			assertEquals(points.first().second, points.last().second, 1e-3f, "loop seam of $parameterId")
			assertEquals(SkeletonMotions.IDLE_DURATION, points.last().first, 1e-3f, "duration of $parameterId")
		}

		assertTrue(SkeletonParameters.CROUCH in tracks, "the idle settles the knees")
		assertTrue(SkeletonParameters.WEIGHT in tracks, "the idle shifts the weight")
		assertTrue(SkeletonParameters.tail(0) in tracks, "the idle swings the tail")

		// The distal joint of a chain travels less than the joint it hangs from.
		val elbow = assertNotNull(tracks[SkeletonParameters.arm(Side.RIGHT, 1)])
		assertTrue(elbow.maxOf { kotlin.math.abs(it.second) } < rightShoulder.maxOf { kotlin.math.abs(it.second) })
	}

	@Test
	fun exportedMotionsOnlyNameParametersTheRigHas() {
		val rig = build(SkeletonInference.infer(analysis()))
		val skeleton = SkeletonInference.infer(analysis())
		val parameterIds = rig.puppet.parameters.map { it.id.raw }.toSet()

		for ((label, motion) in listOf(
			"idle" to MotionGenerator.idle(parameterIds, skeleton),
			"tail swing" to MotionGenerator.tailSwing(parameterIds, skeleton),
			"crouch" to MotionGenerator.crouch(parameterIds, skeleton),
			"weight shift" to MotionGenerator.weightShift(parameterIds, skeleton),
		)) {
			val json = assertNotNull(motion, "$label should be generated for a rigged skeleton")
			val curves = Json.parseToJsonElement(json).jsonObject.getValue("Curves").jsonArray
			assertTrue(curves.isNotEmpty(), "$label has no curves")
			for (curve in curves) {
				val id = curve.jsonObject.getValue("Id").jsonPrimitive.content
				assertTrue(id in parameterIds, "$label names a missing parameter $id")
			}
		}

		// Without a skeleton the bone-only motions have nothing to say and are left out entirely.
		assertNull(MotionGenerator.tailSwing(parameterIds, null))
		assertNull(MotionGenerator.crouch(parameterIds, Skeleton.Empty))
	}
}
