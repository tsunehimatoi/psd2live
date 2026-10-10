package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.umamo.format.art.*
import kotlin.test.*

/** Results that used to read as success, or failures that said nothing of their cause, now say what happened. */
class AuthoringFailureReasonsTest {
	private val builder = WorkspacePreviewBuilder()

	@AfterTest fun forget() { MaterializedRigStore.clear() }

	private fun layer(id: String, name: String, order: Int, bounds: LayerBounds) = WorkspaceSourceLayer(LayerId(id), name, "",
		SourceLayerKind.Raster, true, order, bounds, 1f, false, LayerBlend.Normal, ChannelMask.ALL,
		LayerRaster(bounds.width, bounds.height, ByteArray(bounds.width * bounds.height * 4) { if (it % 4 == 3) 255.toByte() else 120 }),
		null, null, false)

	private suspend fun runtime(): WorkspaceRuntime<RigPreviewModel> {
		val source = WorkspaceSourceArt(64, 64, listOf(layer("hair", "Back hair", 0, LayerBounds(16, 8, 32, 48)),
			layer("face", "Face", 1, LayerBounds(20, 12, 24, 24))), emptyList())
		val document = WorkspaceDocument(source, emptyMap(), emptySet(), mapOf(
			"hair" to LayerClassificationOverride(type = LayerType.PRESET, tag = SemanticTag.BACK_HAIR),
			"face" to LayerClassificationOverride(type = LayerType.PRESET, tag = SemanticTag.FACE),
		), emptyMap(), RigEditOverlay(skeleton = SkeletonSpec.Disabled), WorkspaceSettingsCodec.encode(PipelineConfig(atlasSize = 256, exportMoc3 = false)))
		val runtime = WorkspaceRuntime<RigPreviewModel>({ builder.build(it) }, rebuildFrom = { next, previous -> builder.build(next, previous) })
		runtime.install(runtime.state.value.state, "project", document, builder.build(document))
		return runtime
	}

	@Test fun aHairPresetSaysItReplacedTheGeneratedSway() = runBlocking<Unit> {
		val runtime = runtime(); val start = runtime.capture()
		val report = WorkspaceSimulationCommands(runtime).execute(start.projectId, start.state,
			WorkspaceDocumentOperation("model_apply_preset", buildJsonObject { put("preset", "back_hair") }), "Hair simulation",
			MutationAuthor.USER, autoBake = false).report
		val warnings = report["warnings"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }
		assertTrue(warnings.any { "replaced the generated hair sway" in it }, report.toString())
	}

	@Test fun aSkeletonMotionWithoutASkeletonSaysToCreateOne() = runBlocking<Unit> {
		val runtime = runtime(); val start = runtime.capture()
		val failure = assertFails {
			WorkspaceDocumentCommands(runtime).execute(start.projectId, start.state, "Seed", listOf(WorkspaceDocumentOperation("motion_seed_builtin",
				buildJsonObject { put("builtin", "Wave") })), MutationAuthor.USER)
		}
		assertTrue("skeleton_auto" in generateSequence(failure) { it.cause }.joinToString { it.message.orEmpty() }, failure.toString())
	}
}
