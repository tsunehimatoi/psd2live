package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.umamo.render.restMeshesToCanvasSpace
import org.umamo.runtime.model.PuppetModel
import org.umamo.format.art.*
import kotlin.test.*

/**
 * A split is a regeneration ([RigRegenerationCheckpoint.split]): a part the user had not changed is the generated part,
 * the user's change to the original carries onto its parts, and the parts follow a later change of the generation.
 */
class SplitMergeRulesTest {
	private val builder = WorkspacePreviewBuilder()

	@AfterTest fun forget() { MaterializedRigStore.clear() }

	private fun layer(id: String, name: String, order: Int, bounds: LayerBounds) = WorkspaceSourceLayer(LayerId(id), name, "",
		SourceLayerKind.Raster, true, order, bounds, 1f, false, LayerBlend.Normal, ChannelMask.ALL,
		LayerRaster(bounds.width, bounds.height, ByteArray(bounds.width * bounds.height * 4) { if (it % 4 == 3) 255.toByte() else 120 }),
		null, null, false)

	private fun document(): WorkspaceDocument {
		val layers = listOf(layer("hair", "Back hair", 0, LayerBounds(16, 8, 32, 48)), layer("face", "Face", 1, LayerBounds(20, 12, 24, 24)))
		return WorkspaceDocument(WorkspaceSourceArt(64, 64, layers, emptyList()), emptyMap(), emptySet(), mapOf(
			"hair" to LayerClassificationOverride(type = LayerType.PRESET, tag = SemanticTag.BACK_HAIR),
			"face" to LayerClassificationOverride(type = LayerType.PRESET, tag = SemanticTag.FACE),
		), emptyMap(), RigEditOverlay.Empty, WorkspaceSettingsCodec.encode(PipelineConfig(atlasSize = 256, exportMoc3 = false)))
	}

	private suspend fun runtime(): WorkspaceRuntime<RigPreviewModel> {
		val runtime = WorkspaceRuntime<RigPreviewModel>({ builder.build(it) }, rebuildFrom = { document, previous -> builder.build(document, previous) })
		val document = document()
		runtime.install(runtime.state.value.state, "project", document, builder.build(document))
		return runtime
	}

	/** The hair's mesh moved by ([dx], [dy]) in its parent's space (the hair's sway warp, where the cut at canvas x 32 is local x 0.5). */
	private suspend fun moveHair(runtime: WorkspaceRuntime<RigPreviewModel>, dx: Float, dy: Float) {
		val before = runtime.capture()
		val hair = before.model.rig.puppet.drawables.single { before.model.rig.layerIdByDrawableId[it.id.raw] == "hair" }
		val points = hair.mesh!!.positions.copyOf().also { for (i in it.indices) it[i] += if (i % 2 == 0) dx else dy }
		WorkspaceDocumentCommands(runtime).executeJournal(before.projectId, before.state, "Move", JsonArray(listOf(buildJsonObject {
			put("op", "canvas_geometry"); put("kind", "mesh"); put("id", hair.id.raw)
			put("key", JsonObject(emptyMap())); put("pose", JsonObject(emptyMap())); put("preserve_image", true)
			put("points", JsonArray(points.map(::JsonPrimitive)))
		})), MutationAuthor.USER)
	}

	private suspend fun split(runtime: WorkspaceRuntime<RigPreviewModel>): WorkspaceCapture<RigPreviewModel> {
		val start = runtime.capture()
		return WorkspacePartitionCommands(runtime).execute(start.projectId, start.state, listOf(
			WorkspaceDocumentOperation("source_split_polygon", buildJsonObject {
				put("layer_id", "hair"); putJsonArray("names") { add("Left"); add("Right") }; putJsonArray("piece_ids") { add("left"); add("right") }
				putJsonArray("polygon") { listOf(0 to 0, 32 to 0, 32 to 64, 0 to 64).forEach { (x, y) -> add(buildJsonArray { add(x); add(y) }) } }
			})), "Split", MutationAuthor.USER).commit.capture
	}

	private suspend fun settings(runtime: WorkspaceRuntime<RigPreviewModel>): WorkspaceCapture<RigPreviewModel> {
		val start = runtime.capture()
		return WorkspaceDocumentCommands(runtime).execute(start.projectId, start.state, "Head", listOf(WorkspaceDocumentOperation("settings_update",
			buildJsonObject { putJsonObject("changes") { put("headStrength", 2) } })), MutationAuthor.USER).capture
	}

	private fun parts(capture: WorkspaceCapture<RigPreviewModel>): List<String> =
		ArtPrimitiveJournal.commands(capture.document.rigEdits).single().let(ArtPrimitiveJournal::primitives).map { it.getValue("id").jsonPrimitive.content }

	/** Each of [ids]' rest-pose centroid on the canvas. */
	private fun centroids(puppet: PuppetModel, ids: List<String>): List<Pair<Float, Float>> {
		val canvas = restMeshesToCanvasSpace(puppet)
		return ids.map { id ->
			val points = canvas.drawables.single { it.id.raw == id }.mesh!!.positions
			val n = points.size / 2
			(0 until n).sumOf { points[it * 2].toDouble() }.toFloat() / n to (0 until n).sumOf { points[it * 2 + 1].toDouble() }.toFloat() / n
		}
	}

	private fun hairCentroid(model: RigPreviewModel): Pair<Float, Float> =
		centroids(model.rig.puppet, listOf(model.rig.puppet.drawables.single { model.rig.layerIdByDrawableId[it.id.raw] == "hair" }.id.raw)).single()

	@Test fun anUntouchedOriginalSplitsIntoTheGeneratedParts() = runBlocking {
		val runtime = runtime()
		val split = split(runtime)
		val ids = parts(split)
		val generated = split.model.baseRig.resolvedPuppet()
		for (id in ids) assertContentEquals(generated.drawables.single { it.id.raw == id }.mesh!!.positions,
			split.model.rig.puppet.drawables.single { it.id.raw == id }.mesh!!.positions, id)
	}

	@Test fun theUsersChangeToTheOriginalCarriesOntoThePartsAndThroughALaterRegeneration() = runBlocking {
		val plain = runtime()
		val edited = runtime()
		val before = hairCentroid(edited.capture().model)
		moveHair(edited, 0.02f, 0.01f)
		val after = hairCentroid(edited.capture().model)
		val moved = (after.first - before.first) to (after.second - before.second)
		assertTrue(kotlin.math.abs(moved.first) > 0.1f, "the edit moves the hair: $moved")

		val plainSplit = split(plain)
		val editedSplit = split(edited)
		val ids = parts(plainSplit)
		assertEquals(ids, parts(editedSplit))
		// Each part hangs where the original did, so the user's move is the same move of every vertex in that space.
		fun assertMoved(a: WorkspaceCapture<RigPreviewModel>, b: WorkspaceCapture<RigPreviewModel>) {
			for (id in ids) {
				val p = a.model.rig.puppet.drawables.single { it.id.raw == id }
				val q = b.model.rig.puppet.drawables.single { it.id.raw == id }
				assertEquals(p.parentDeformerId, q.parentDeformerId, id)
				val pp = p.mesh!!.positions; val qp = q.mesh!!.positions
				assertEquals(pp.size, qp.size, id)
				// The cut is where the user drew it on the canvas: vertices on it stay there; every other vertex carries the move.
				for (v in 0 until pp.size / 2) {
					val onCut = kotlin.math.abs(pp[v * 2] - 0.5f) < 1e-4f
					assertEquals(if (onCut) 0f else 0.02f, qp[v * 2] - pp[v * 2], 1e-4f, "$id[$v].x")
					if (!onCut) assertEquals(0.01f, qp[v * 2 + 1] - pp[v * 2 + 1], 1e-4f, "$id[$v].y")
				}
			}
		}
		assertMoved(plainSplit, editedSplit)
		// A later change of the generation regenerates the parts under both; the user's move stays.
		val plainHead = settings(plain)
		val editedHead = settings(edited)
		assertTrue(editedHead.document.rigEdits.authoringJournal.count(RigCheckpoint::isRecord) >
			editedSplit.document.rigEdits.authoringJournal.count(RigCheckpoint::isRecord), "the change regenerates")
		assertMoved(plainHead, editedHead)
	}
}
