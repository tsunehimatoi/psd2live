package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.umamo.format.art.*
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import kotlin.test.*
import io.github.psd2live.core.legacy.RigGenerationJournal
import io.github.psd2live.core.legacy.RigGenerationFrames

/**
 * A skeleton committed onto a journal the generators' new output merged under (see [RigRegenerationCheckpoint]) skins
 * the meshes the user left.
 */
class GenerationTransitionSkeletonTest {
	private val builder = WorkspacePreviewBuilder()

	@AfterTest fun forget() { MaterializedRigStore.clear() }

	private fun layer(id: String, name: String, order: Int, bounds: LayerBounds) = WorkspaceSourceLayer(LayerId(id), name, "",
		SourceLayerKind.Raster, true, order, bounds, 1f, false, LayerBlend.Normal, ChannelMask.ALL,
		LayerRaster(bounds.width, bounds.height, ByteArray(bounds.width * bounds.height * 4) { if (it % 4 == 3) 255.toByte() else 120 }),
		null, null, false)

	private suspend fun runtime(): WorkspaceRuntime<RigPreviewModel> {
		val layers = listOf(layer("tail", "Tail", 0, LayerBounds(36, 36, 24, 12)), layer("body", "Body", 1, LayerBounds(20, 24, 24, 32)),
			layer("face", "Face", 2, LayerBounds(22, 6, 20, 18)))
		val document = WorkspaceDocument(WorkspaceSourceArt(64, 64, layers, emptyList()), emptyMap(), emptySet(), mapOf(
			"tail" to LayerClassificationOverride(type = LayerType.PRESET, tag = SemanticTag.TAIL),
			"body" to LayerClassificationOverride(type = LayerType.PRESET, tag = SemanticTag.TOPWEAR),
			"face" to LayerClassificationOverride(type = LayerType.PRESET, tag = SemanticTag.FACE),
		), emptyMap(), RigEditOverlay.Empty, WorkspaceSettingsCodec.encode(PipelineConfig(atlasSize = 256, exportMoc3 = false)))
		val runtime = WorkspaceRuntime<RigPreviewModel>({ builder.build(it) }, rebuildFrom = { next, previous -> builder.build(next, previous) })
		runtime.install(runtime.state.value.state, "project", document, builder.build(document))
		return runtime
	}

	private fun tailOf(capture: WorkspaceCapture<RigPreviewModel>) = capture.model.rig.layerIdByDrawableId.entries.single { it.value == "tail" }.key

	/** A body and a two-bone tail, the tail's mesh on its first bone, committed as the skeleton. */
	private suspend fun skeleton(runtime: WorkspaceRuntime<RigPreviewModel>): WorkspaceCapture<RigPreviewModel> {
		val before = runtime.capture()
		val body = SkeletonBone("body", "Body", null, BoneRole.UPPER_BODY, headX = 32f, headY = 56f, tailX = 32f, tailY = 26f)
		val tail1 = SkeletonBone("tail_1", "Tail 1", "body", BoneRole.TAIL, headX = 38f, headY = 42f, tailX = 48f, tailY = 42f,
			drawableIds = listOf(tailOf(before)), chainIndex = 1)
		val tail2 = SkeletonBone("tail_2", "Tail 2", "tail_1", BoneRole.TAIL, headX = 48f, headY = 42f, tailX = 58f, tailY = 42f, chainIndex = 2)
		val spec = SkeletonSpec(bones = listOf(body, tail1, tail2))
		return WorkspaceDocumentCommands(runtime).execute(before.projectId, before.state, "Edit skeleton",
			listOf(WorkspaceDocumentOperation("skeleton_put", buildJsonObject { put("spec", spec.toJson()) })), MutationAuthor.USER).capture
	}

	/** Swung to either end of the tail swing, the tail keeps about its size; a shape from another space throws it across the canvas. */
	private fun assertSwingsInPlace(puppet: PuppetModel, tail: String) {
		val evaluator = CpuDeformationEvaluator()
		fun extent(values: Map<ParameterId, Float>): Float {
			val p = evaluator.evaluate(puppet, values).worldPositions.getValue(puppet.drawables.single { it.id.raw == tail }.id)
			val xs = p.filterIndexed { i, _ -> i % 2 == 0 }; val ys = p.filterIndexed { i, _ -> i % 2 == 1 }
			return maxOf(xs.max() - xs.min(), ys.max() - ys.min())
		}
		val rest = extent(emptyMap())
		for (value in listOf(-1f, 1f)) {
			val swung = extent(mapOf(SkeletonPoses.tailSwing.id to value))
			assertTrue(swung < rest * 2f, "tail swing $value: $rest px at rest, $swung px swung")
		}
	}

	/**
	 * A setting that changes the generation merges the new output onto the user's rig and checkpoints it; a skeleton
	 * committed after it skins the tail the user left.
	 */
	@Test fun aSkeletonAfterAGenerationChangeSkinsTheTail() = runBlocking<Unit> {
		val runtime = runtime()
		val start = runtime.capture()
		val changed = WorkspaceDocumentCommands(runtime).executeCandidate(start.projectId, start.state, "Features", MutationAuthor.USER, mutation = { document, _ ->
			document.copy(settings = JsonObject(document.settings + ("featureDisplacementEnabled" to JsonPrimitive(true))))
		}).capture
		val journal = changed.document.rigEdits.authoringJournal
		assertTrue(journal.isNotEmpty() && RigCheckpoint.isRecord(journal.last()), "the setting checkpoints the generation it changes")
		assertTrue(journal.none { it["op"]?.jsonPrimitive?.contentOrNull in setOf(RigGenerationFrames.OP, RigGenerationJournal.OP) },
			"no generation transition is recorded")
		val tail = tailOf(changed)
		val puppet = skeleton(runtime).model.rig.puppet
		val skinned = puppet.drawables.single { it.id.raw == tail }
		assertTrue(skinned.parentDeformerId?.raw.orEmpty().startsWith("DeformSkel"), "the tail hangs from its bones: ${skinned.parentDeformerId?.raw}")
		assertTrue(skinned.geometryGrid?.axes.orEmpty().any { it.parameterId.raw == "ParamTail2" }, "the tail keys on its bones")
		assertSwingsInPlace(puppet, tail)
	}
}
