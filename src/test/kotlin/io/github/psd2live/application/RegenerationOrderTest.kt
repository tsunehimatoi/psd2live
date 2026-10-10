package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.core.SkeletonCharacterFixture.layer
import io.github.psd2live.core.quality.SkeletonBindingQuality
import io.github.psd2live.project.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import kotlin.math.hypot
import kotlin.test.*

/**
 * An edit of the user's and a regeneration commute: whichever comes first, the skeleton reaches what it binds. Here the
 * user rebuilds a sleeve's mesh (their own topology) and the skeleton is put on, in both orders, through the runtime's
 * commits - so the regeneration merges and checkpoints as in the editor - and then a bone moves, merging again.
 */
class RegenerationOrderTest {
	private val builder = WorkspacePreviewBuilder()

	@AfterTest fun forget() { MaterializedRigStore.clear() }

	private fun document(): WorkspaceDocument {
		val source = SkeletonCharacterFixture.source(
			layer("face", 5, intArrayOf(170, 30, 250, 110)),
			layer("top", 3, intArrayOf(160, 115, 260, 240)),
			layer("skirt", 1, intArrayOf(165, 235, 255, 290)),
			layer("sleeve", 4, intArrayOf(258, 120, 340, 148)),
		)
		val overrides = mapOf(
			"face" to LayerClassificationOverride(tag = SemanticTag.FACE),
			"top" to LayerClassificationOverride(tag = SemanticTag.TOPWEAR),
			"skirt" to LayerClassificationOverride(tag = SemanticTag.BOTTOMWEAR),
			"sleeve" to LayerClassificationOverride(SemanticTag.HANDWEAR, Side.LEFT),
		)
		return WorkspaceDocument(source, emptyMap(), emptySet(), overrides, emptyMap(), RigEditOverlay.Empty,
			WorkspaceSettingsCodec.encode(PipelineConfig(atlasSize = 1024, generatePhysics = false, exportMoc3 = false)))
	}

	private fun runtime(): WorkspaceRuntime<RigPreviewModel> =
		WorkspaceRuntime({ builder.build(it) }, rebuildFrom = { document, previous -> builder.build(document, previous) })

	private fun WorkspaceCapture<RigPreviewModel>.layerMesh(layer: String) = model.rig.layerIdByDrawableId.entries.single { it.value == layer }.key

	private fun spec(capture: WorkspaceCapture<RigPreviewModel>, elbow: Float = 300f) = SkeletonSpec(bones = listOf(
		SkeletonBone("upper", "Upper body", null, BoneRole.UPPER_BODY, Side.NONE, 210f, 240f, 210f, 115f, listOf(capture.layerMesh("top"))),
		SkeletonBone("lower", "Lower body", null, BoneRole.LOWER_BODY, Side.NONE, 210f, 240f, 210f, 290f, listOf(capture.layerMesh("skirt"))),
		SkeletonBone("armL", "Upper arm", "upper", BoneRole.UPPER_ARM, Side.LEFT, 262f, 134f, elbow, 134f, listOf(capture.layerMesh("sleeve"))),
		SkeletonBone("foreL", "Forearm", "armL", BoneRole.FOREARM, Side.LEFT, elbow, 134f, 340f, 134f),
	))

	private suspend fun WorkspaceRuntime<RigPreviewModel>.skeleton(elbow: Float = 300f): WorkspaceCapture<RigPreviewModel> {
		val before = capture()
		return WorkspaceDocumentCommands(this).execute(before.projectId, before.state, "Skeleton", listOf(WorkspaceDocumentOperation("skeleton_put",
			buildJsonObject { put("spec", spec(before, elbow).toJson()) })), MutationAuthor.USER).capture
	}

	/** Every triangle of [mesh] split in four at its edges' midpoints. */
	private fun subdivided(mesh: DrawableMesh): DrawableMesh {
		val positions = mesh.positions.toMutableList(); val uvs = mesh.uvs.toMutableList()
		val midpoints = HashMap<Pair<Int, Int>, Int>()
		fun mid(a: Int, b: Int): Int = midpoints.getOrPut(minOf(a, b) to maxOf(a, b)) {
			positions += (mesh.positions[a * 2] + mesh.positions[b * 2]) / 2; positions += (mesh.positions[a * 2 + 1] + mesh.positions[b * 2 + 1]) / 2
			uvs += (mesh.uvs[a * 2] + mesh.uvs[b * 2]) / 2; uvs += (mesh.uvs[a * 2 + 1] + mesh.uvs[b * 2 + 1]) / 2
			positions.size / 2 - 1
		}
		val triangles = ArrayList<Int>()
		for (t in mesh.indices.indices step 3) {
			val a = mesh.indices[t]; val b = mesh.indices[t + 1]; val c = mesh.indices[t + 2]
			val ab = mid(a, b); val bc = mid(b, c); val ca = mid(c, a)
			triangles += listOf(a, ab, ca, ab, b, bc, ca, bc, c, ab, bc, ca)
		}
		return DrawableMesh(positions.toFloatArray(), uvs.toFloatArray(), triangles.toIntArray())
	}

	/** The user's own topology for the sleeve, as a mesh rebuild records it. */
	private suspend fun WorkspaceRuntime<RigPreviewModel>.rebuildSleeve(): WorkspaceCapture<RigPreviewModel> {
		val before = capture()
		val id = DrawableId(before.layerMesh("sleeve"))
		val record = RasterMeshJournal.encode(before.model.rig.puppet, id, subdivided(before.model.rig.puppet.drawables.single { it.id == id }.mesh!!))
		return WorkspaceDocumentCommands(this).executeJournal(before.projectId, before.state, "Rebuild", JsonArray(listOf(record)), MutationAuthor.USER).capture
	}

	private fun motion(model: PuppetModel, mesh: String, parameter: String, value: Float): Double {
		val evaluator = CpuDeformationEvaluator()
		val rest = evaluator.evaluate(model, emptyMap()).worldPositions.getValue(DrawableId(mesh))
		val moved = evaluator.evaluate(model, mapOf(ParameterId(parameter) to value)).worldPositions.getValue(DrawableId(mesh))
		return (rest.indices step 2).sumOf { hypot((moved[it] - rest[it]).toDouble(), (moved[it + 1] - rest[it + 1]).toDouble()) } / (rest.size / 2)
	}

	private fun assertReached(capture: WorkspaceCapture<RigPreviewModel>, label: String) {
		val puppet = capture.model.rig.puppet
		assertEquals(emptyList(), SkeletonBindingQuality.issues(puppet, capture.document.rigEdits.skeleton), label)
		val sleeve = capture.layerMesh("sleeve")
		assertTrue(motion(puppet, sleeve, "ParamArmLA", 60f) > 20.0, "$label: the upper arm turns the sleeve")
		assertTrue(motion(puppet, sleeve, "ParamArmLB", 60f) > 2.0, "$label: the forearm bends the sleeve")
		assertTrue(motion(puppet, sleeve, "ParamSkelUpperBody", 20f) > 10.0, "$label: the upper body carries the sleeve")
		assertTrue(motion(puppet, capture.layerMesh("top"), "ParamSkelUpperBody", 20f) > 10.0, "$label: the upper body bends the top")
	}

	private fun scenario(rebuildFirst: Boolean) = runBlocking<Unit> {
		val runtime = runtime()
		val document = document()
		runtime.install(runtime.state.value.state, "project", document, builder.build(document))
		val generated = runtime.capture().model.rig.puppet.drawables.single { it.id.raw == runtime.capture().layerMesh("sleeve") }.mesh!!.vertexCount
		val label = if (rebuildFirst) "rebuild, then skeleton" else "skeleton, then rebuild"
		val done = if (rebuildFirst) { runtime.rebuildSleeve(); runtime.skeleton() } else { runtime.skeleton(); runtime.rebuildSleeve() }
		val sleeve = done.model.rig.puppet.drawables.single { it.id.raw == done.layerMesh("sleeve") }
		assertTrue(sleeve.mesh!!.vertexCount > generated, "$label: the user's topology stays")
		assertReached(done, label)
		// A bone moved: the skeleton regenerates and merges again; what the merge decided before must not stick.
		assertReached(runtime.skeleton(elbow = 310f), "$label, then a bone moved")
	}

	@Test fun aRebuiltSleeveFollowsASkeletonPutOnAfterIt() = scenario(rebuildFirst = true)

	@Test fun aSleeveRebuiltOnTheSkeletonStillFollowsIt() = scenario(rebuildFirst = false)
}
