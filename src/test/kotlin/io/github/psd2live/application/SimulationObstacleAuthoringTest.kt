package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.umamo.format.art.*
import kotlin.test.*

/** Obstacles are authored through simulation put: checked against the rig, kept with the body, and felt by the simulation. */
class SimulationObstacleAuthoringTest {
	private val builder = WorkspacePreviewBuilder()

	private fun layer(id: String, order: Int, bounds: LayerBounds) = WorkspaceSourceLayer(LayerId(id), id, "", SourceLayerKind.Raster, true, order,
		bounds, 1f, false, LayerBlend.Normal, ChannelMask.ALL,
		LayerRaster(bounds.width, bounds.height, ByteArray(bounds.width * bounds.height * 4) { if (it % 4 == 3) -1 else 100 }), null, null, false)

	@Test fun anObstacleIsCheckedKeptAndFelt() = runBlocking<Unit> {
		val runtime = WorkspaceRuntime<RigPreviewModel>({ builder.build(it) }, rebuildFrom = { document, previous -> builder.build(document, previous) })
		val config = PipelineConfig(atlasSize = 256, meshOnly = true, meshSpacing = 8, generatePhysics = false, exportMoc3 = false,
			layerOverrides = listOf("strip", "body").associateWith { LayerClassificationOverride(type = LayerType.PRESET, tag = SemanticTag.OBJECTS) })
		val document = WorkspaceDocument(WorkspaceSourceArt(64, 96, listOf(layer("strip", 1, LayerBounds(26, 0, 12, 72)),
			layer("body", 0, LayerBounds(30, 40, 20, 40))), emptyList()), emptyMap(), emptySet(), config.layerOverrides,
			emptyMap(), RigEditOverlay.Empty, WorkspaceSettingsCodec.encode(config))
		val root = runtime.install(runtime.state.value.state, "obstacles", document, builder.build(document))
		val strip = root.model.rig.puppet.drawables.single { root.model.rig.layerIdByDrawableId[it.id.raw] == "strip" }
		val body = root.model.rig.puppet.drawables.single { root.model.rig.layerIdByDrawableId[it.id.raw] == "body" }
		val commands = WorkspaceDocumentCommands(runtime)
		// The fixture's driving edits and body address the first mesh: the strip.
		val stripFirst = root.model.rig.puppet.let { it.copy(drawables = listOf(strip) + (it.drawables - strip)) }
		val prepared = commands.execute(root.projectId, root.state, "Prepare", simulationDrivingEdits(stripFirst), MutationAuthor.USER).capture
		fun put(obstacles: JsonArray) = WorkspaceDocumentOperation("simulation_put",
			JsonObject(simulationPut(stripFirst).request + ("obstacles" to obstacles)))
		val bodyVertices = body.mesh!!.positions.size / 2
		for ((obstacle, message) in listOf(
			buildJsonObject { put("mesh", strip.id.raw); put("a", 0); put("radius", 4) } to "does not move",
			buildJsonObject { put("mesh", "missing"); put("a", 0); put("radius", 4) } to "not found",
			buildJsonObject { put("mesh", body.id.raw); put("a", bodyVertices); put("radius", 4) } to "within")) {
			val failure = assertFailsWith<WorkspaceBatchEditException> {
				commands.execute(prepared.projectId, prepared.state, "Bad obstacle", listOf(put(buildJsonArray { add(obstacle) })), MutationAuthor.USER)
			}
			assertTrue(message in failure.message.orEmpty(), failure.message)
		}
		val capsule = buildJsonObject { put("mesh", body.id.raw); put("a", 0); put("b", bodyVertices - 1); put("radius", 6); put("friction", 0.3) }
		val kept = commands.execute(prepared.projectId, prepared.state, "Obstacle", listOf(put(buildJsonArray { add(capsule) })), MutationAuthor.USER).capture
		val edit = kept.document.rigEdits.simEdits.single { it.id == "sway" }
		assertEquals(listOf(io.github.psd2live.core.sim.SimCollider(body.id.raw, 0, bodyVertices - 1, 6f, friction = 0.3f)), edit.colliders)
		// The simulation feels it: the same body reports differently with and without the obstacle.
		val model = kept.model.rig.puppet
		val without = io.github.psd2live.core.sim.SimAuthoring.report(model, edit.copy(colliders = emptyList()))
		val with = io.github.psd2live.core.sim.SimAuthoring.report(model, edit)
		assertNotEquals(without.toString(), with.toString())
	}
}
