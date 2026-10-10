package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.format.compile.document.ContentHash
import io.github.psd2live.project.*
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import org.umamo.format.art.*
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.DrawableId
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.test.*

/**
 * An edit that changes what the generators make under the journal - here back hair simulation, which drops the
 * legacy sway warp split parts hang on - merges onto the new generation and checkpoints the result; builds start
 * from the checkpoint and never replay the entries before it.
 */
class RegenerationCheckpointTest {
	@TempDir lateinit var temporary: Path
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

	private fun hash(model: RigPreviewModel) = ContentHash.of(PuppetIr.toIr(model.rig.puppet))

	private fun runtime(): WorkspaceRuntime<RigPreviewModel> =
		WorkspaceRuntime({ builder.build(it) }, rebuildFrom = { document, previous -> builder.build(document, previous) })

	/** The split back hair, then back hair simulation switched on: the runtime and its two commits. */
	private class Edited(val runtime: WorkspaceRuntime<RigPreviewModel>, val split: WorkspaceCapture<RigPreviewModel>,
	                     val simulated: WorkspaceCapture<RigPreviewModel>, val parts: List<DrawableId>)

	/** The back hair split in two on a fresh [runtime], and the ids of the two parts. */
	private suspend fun split(runtime: WorkspaceRuntime<RigPreviewModel>): Pair<WorkspaceCapture<RigPreviewModel>, List<DrawableId>> {
		val document = document()
		runtime.install(runtime.state.value.state, "project", document, builder.build(document))
		val start = runtime.capture()
		val split = WorkspacePartitionCommands(runtime).execute(start.projectId, start.state, listOf(
			WorkspaceDocumentOperation("source_split_polygon", buildJsonObject {
				put("layer_id", "hair"); putJsonArray("names") { add("Left"); add("Right") }; putJsonArray("piece_ids") { add("left"); add("right") }
				putJsonArray("polygon") { listOf(0 to 0, 32 to 0, 32 to 64, 0 to 64).forEach { (x, y) -> add(buildJsonArray { add(x); add(y) }) } }
			})), "Split", MutationAuthor.USER).commit.capture
		return split to ArtPrimitiveJournal.commands(split.document.rigEdits).single().let(ArtPrimitiveJournal::primitives)
			.map { DrawableId(it.getValue("id").jsonPrimitive.content) }
	}

	private fun edited(): Edited = runBlocking {
		val runtime = runtime()
		val (split, parts) = split(runtime)
		val simulated = WorkspaceSimulationCommands(runtime).execute(split.projectId, split.state,
			WorkspaceDocumentOperation("model_apply_preset", buildJsonObject { put("preset", "back_hair") }), "Hair simulation",
			MutationAuthor.USER, autoBake = false).commit.capture
		Edited(runtime, split, simulated, parts)
	}

	@Test fun theHairSwitchCheckpointsTheMergedRigWhereTheJournalEnded() {
		val edited = edited()
		val journal = edited.simulated.document.rigEdits.authoringJournal
		val before = edited.split.document.rigEdits.authoringJournal
		assertEquals(before, journal.subList(0, before.size))
		assertTrue(RigCheckpoint.isRecord(journal[before.size]), "the checkpoint follows the journal it replaces")
		// The preset's own entries (its weights) come after the checkpoint.
		assertEquals(listOf(before.size), (before.size until journal.size).filter { RigCheckpoint.isRecord(journal[it]) })
		assertTrue(journal.subList(before.size + 1, journal.size).all { it["op"]?.jsonPrimitive?.contentOrNull == VertexGroupJournal.PUT })
		val puppet = edited.simulated.model.rig.puppet
		assertTrue(puppet.deformers.none { it.id.raw == "DeformHairBackPhysics" })
		val evaluator = CpuDeformationEvaluator()
		val a = evaluator.evaluate(edited.split.model.rig.puppet, emptyMap()).worldPositions
		val b = evaluator.evaluate(puppet, emptyMap()).worldPositions
		for (id in edited.parts) {
			assertEquals("DeformHairBackFollow", puppet.drawables.single { it.id == id }.parentDeformerId?.raw)
			val want = a.getValue(id); val got = b.getValue(id)
			want.indices.forEach { assertEquals(want[it], got[it], 1e-3f, "${id.raw}[$it]") }
		}
	}

	/**
	 * Past a checkpoint that nothing needing the base follows, a setting that changes the generation without an entry
	 * of its own still regenerates: the checkpoint's rig was merged from the previous generation, so the switch merges
	 * and checkpoints again instead of building from it.
	 */
	@Test fun aSwitchPastATrailingCheckpointRegeneratesInsteadOfBuildingFromIt() = runBlocking<Unit> {
		val edited = edited()
		val journal = edited.simulated.document.rigEdits.authoringJournal
		assertTrue(journal.subList(RigCheckpoint.latest(journal), journal.size).none(ArtPrimitiveJournal::isRecord))
		assertTrue(edited.simulated.model.rig.puppet.deformers.none { it.id.raw == "DeformHairBackPhysics" })
		val start = edited.runtime.capture()
		val off = WorkspaceDocumentCommands(edited.runtime).executeCandidate(start.projectId, start.state, "Hair off", MutationAuthor.USER, mutation = { document, _ ->
			document.copy(settings = JsonObject(document.settings + ("hairSimulationBack" to JsonPrimitive(false))))
		}).capture
		assertTrue(off.model.rig.puppet.deformers.any { it.id.raw == "DeformHairBackPhysics" }, "the legacy sway warp is generated again")
		assertEquals(journal, off.document.rigEdits.authoringJournal.subList(0, journal.size))
		assertTrue(RigCheckpoint.isRecord(off.document.rigEdits.authoringJournal.last()))
	}

	/**
	 * A journal without a checkpoint whose setting alone switched the generation, as a document saved before the switch
	 * checkpointed: the journal replays on the new generation, and the split parts keep their place once the legacy
	 * sway warp they hang on is gone. (A runtime that rebuilds from the previous model checkpoints the split itself.)
	 */
	@Test fun aSettingSwitchedAfterASplitKeepsThePartsInPlace() = runBlocking<Unit> {
		val (split, parts) = split(WorkspaceRuntime({ builder.build(it) }))
		assertTrue(RigCheckpoint.isRecord(split.document.rigEdits.authoringJournal.last()), "the split checkpoints its merge")
		assertEquals(listOf("DeformHairBackPhysics", "DeformHairBackPhysics"),
			split.model.rig.puppet.drawables.filter { it.id in parts }.map { it.parentDeformerId?.raw })
		val simulated = builder.build(split.document.copy(settings = JsonObject(split.document.settings + ("hairSimulationBack" to JsonPrimitive(true)))), split.model)
		val puppet = simulated.rig.puppet
		assertTrue(puppet.deformers.none { it.id.raw == "DeformHairBackPhysics" })
		val evaluator = CpuDeformationEvaluator()
		val before = evaluator.evaluate(split.model.rig.puppet, emptyMap()).worldPositions
		val after = evaluator.evaluate(puppet, emptyMap()).worldPositions
		for (id in parts) {
			assertTrue(puppet.deformers.any { it.id == puppet.drawables.single { d -> d.id == id }.parentDeformerId }, id.raw)
			val want = before.getValue(id); val got = after.getValue(id)
			want.indices.forEach { assertEquals(want[it], got[it], 1e-3f, "${id.raw}[$it]") }
		}
	}

	/** A skeleton committed after a checkpoint bakes: its bones' parameters reach the rig, merged onto the checkpoint. */
	@Test fun aSkeletonAfterACheckpointBakes() = runBlocking<Unit> {
		val edited = edited()
		val face = edited.simulated.model.rig.layerIdByDrawableId.entries.single { it.value == "face" }.key
		val body = SkeletonBone("body", "Body", null, BoneRole.UPPER_BODY, headX = 32f, headY = 60f, tailX = 32f, tailY = 40f)
		val arm = SkeletonBone("arm", "Arm", "body", BoneRole.UPPER_ARM, Side.LEFT, headX = 36f, headY = 14f, tailX = 36f, tailY = 34f,
			drawableIds = listOf(face))
		val spec = SkeletonSpec(bones = listOf(body, arm))
		val before = edited.simulated
		val committed = WorkspaceDocumentCommands(edited.runtime).execute(before.projectId, before.state, "Edit skeleton",
			listOf(WorkspaceDocumentOperation("skeleton_put", buildJsonObject { put("spec", spec.toJson()) })), MutationAuthor.USER).capture
		val journal = committed.document.rigEdits.authoringJournal
		assertTrue(RigCheckpoint.latest(journal) > RigCheckpoint.latest(before.document.rigEdits.authoringJournal), "the skeleton checkpoints")
		val puppet = committed.model.rig.puppet
		assertTrue(puppet.parameters.any { it.id.raw == arm.parameterId }, "the arm bone drives a parameter")
		assertTrue(puppet.drawables.none { it.id in edited.parts && it.parentDeformerId == null })
		// A cold build of the committed document gives the same rig.
		MaterializedRigStore.clear(); ReplayCheckpoints.clear()
		assertEquals(hash(committed.model), hash(builder.build(committed.document)))
	}

	@Test fun buildsStartFromTheCheckpointAndIgnoreTheEntriesBeforeIt() = runBlocking<Unit> {
		val edited = edited()
		val document = edited.simulated.document
		val expected = hash(edited.simulated.model)
		// A cold build of the document alone.
		MaterializedRigStore.clear(); ReplayCheckpoints.clear()
		assertEquals(expected, hash(builder.build(document)))
		// An entry before the checkpoint that no longer replays changes nothing.
		val journal = document.rigEdits.authoringJournal
		val at = RigCheckpoint.latest(journal)
		val broken = buildJsonObject { put("op", VertexGroupJournal.PUT); put("target", "mesh:${edited.parts.first().raw}"); put("name", "pin"); put("kind", "pin")
			putJsonArray("weights") { add(1f) } }
		val drifted = document.copy(rigEdits = document.rigEdits.copy(authoringJournal = journal.subList(0, at) + broken + journal.subList(at, journal.size)))
		MaterializedRigStore.clear()
		assertEquals(expected, hash(builder.build(drifted)))
	}

	@Test fun aCheckpointBuildsWithoutTheBaseAndMovesOntoAnotherAtlas() = runBlocking<Unit> {
		val edited = edited()
		val document = edited.simulated.document
		MaterializedRigStore.clear()
		val model = builder.build(document)
		assertFalse(model.sources.baseKnown, "the checkpoint and the entries after it need no generated base")
		assertEquals(hash(edited.simulated.model), hash(model))
		// A larger atlas page: the same rig, its texture coordinates moved onto the new tiles.
		val larger = document.copy(settings = JsonObject(document.settings + ("atlasSize" to JsonPrimitive(512))))
		val moved = builder.build(larger)
		assertFalse(moved.sources.baseKnown)
		val evaluator = CpuDeformationEvaluator()
		val a = evaluator.evaluate(model.rig.puppet, emptyMap()).worldPositions; val b = evaluator.evaluate(moved.rig.puppet, emptyMap()).worldPositions
		for ((id, positions) in a) positions.indices.forEach { assertEquals(positions[it], b.getValue(id)[it], 1e-4f, "${id.raw}[$it]") }
		for (drawable in model.rig.puppet.drawables) {
			val mesh = drawable.mesh ?: continue
			val other = moved.rig.puppet.drawables.single { it.id == drawable.id }
			val canvas = RasterMeshJournal.TextureCoordinates(model.rig.puppet, drawable).toCanvas(mesh.uvs)
			val again = RasterMeshJournal.TextureCoordinates(moved.rig.puppet, other).toCanvas(other.mesh!!.uvs)
			canvas.indices.forEach { assertEquals(canvas[it], again[it], 1e-2f, "${drawable.id.raw} canvas uv $it") }
		}
	}

	@Test fun updatingTheGenerationTakesWhatTheGeneratorsMakeNowWhereTheUserLeftIt() = runBlocking<Unit> {
		val edited = edited()
		val document = edited.simulated.document
		val journal = document.rigEdits.authoringJournal
		val at = RigCheckpoint.latest(journal)
		val record = journal[at]
		val face = edited.simulated.model.rig.layerIdByDrawableId.entries.single { it.value == "face" }.key
		// As an older generator left it: the face 2 px to the right, in the stored generation and, untouched, in the rig.
		fun older(rig: AuthoredRig) = rig.copy(rig = rig.rig.copy(puppet = rig.rig.puppet.copy(drawables = rig.rig.puppet.drawables.map { drawable ->
			if (drawable.id.raw != face) drawable else drawable.mesh!!.let { mesh ->
				val unit = 2f / 64f
				drawable.copy(mesh = org.umamo.runtime.model.DrawableMesh(FloatArray(mesh.positions.size) { mesh.positions[it] + if (it % 2 == 0) unit else 0f }, mesh.uvs, mesh.indices))
			}
		})))
		val stored = RigCheckpoint.decode(record)
		val aged = RigCheckpoint.encode(older(stored.authored), stored.bindingKey, generated = older(assertNotNull(RigCheckpoint.generated(record)).authored))
		val old = document.copy(rigEdits = document.rigEdits.copy(authoringJournal = journal.subList(0, at) + aged + journal.subList(at + 1, journal.size)))
		val runtime = runtime()
		runtime.install(runtime.state.value.state, "project", old, builder.build(old))
		assertTrue(WorkspaceGenerationUpdate.updatable(old.rigEdits))
		val before = runtime.capture()
		val shifted = before.model.rig.puppet.drawables.single { it.id.raw == face }.mesh!!.positions
		val result = WorkspaceGenerationUpdateCommands(runtime).execute(before.projectId, before.state, "Update", MutationAuthor.USER)
		assertTrue(result.result.getValue("updated").jsonPrimitive.boolean)
		val after = runtime.capture()
		assertTrue(RigCheckpoint.isRecord(after.document.rigEdits.authoringJournal.last()))
		val now = after.model.rig.puppet.drawables.single { it.id.raw == face }.mesh!!.positions
		val generated = edited.simulated.model.rig.puppet.drawables.single { it.id.raw == face }.mesh!!.positions
		generated.indices.forEach { assertEquals(generated[it], now[it], 1e-5f, "face[$it] follows the generators") }
		assertFalse(shifted.contentEquals(now))
		// The user's parts did not move, and a second update finds nothing to do.
		for (id in edited.parts) assertContentEquals(before.model.rig.puppet.drawables.single { it.id == id }.mesh!!.positions,
			after.model.rig.puppet.drawables.single { it.id == id }.mesh!!.positions)
		val again = WorkspaceGenerationUpdateCommands(runtime).execute(after.projectId, after.state, "Update", MutationAuthor.USER)
		assertFalse(again.result.getValue("updated").jsonPrimitive.boolean)
		assertFalse(again.commit.applied)
	}

	@Test fun undoRedoAndAReopenedArchiveKeepTheCheckpoint() = runBlocking<Unit> {
		val edited = edited()
		val runtime = edited.runtime
		val head = runtime.capture()
		val expected = hash(edited.simulated.model)
		// Undo to the split, redo to the checkpoint.
		val undone = runtime.checkout(head.projectId, head.state, runtime.history().selections.single { it.node.id == head.historyHead }.node.parentId!!)
		assertEquals(hash(edited.split.model), hash(undone.model))
		val redone = runtime.checkout(undone.projectId, undone.state, head.historyHead)
		assertEquals(expected, hash(redone.model))
		// Saved and opened by a cold process: the archive carries the checkpoint's objects under a schema 3 revision.
		val store = WorkspaceStore(temporary.resolve("store"))
		store.persistHistory("project", runtime.history())
		val file = temporary.resolve("saved.psd2live")
		ProjectRepository(writeHeadCache = false).save(ProjectSaveCapture("project", runtime.history(), JsonObject(emptyMap()), null, store), file)
		val names = ZipFile(file.toFile()).use { zip -> zip.entries().asSequence().map { it.name }.toList() }
		val required = RigCheckpoint.hashes(head.document.rigEdits.authoringJournal).distinct()
		assertTrue(required.isNotEmpty())
		assertTrue(required.all { "rig/objects/$it.bin" in names })
		val schemas = ZipFile(file.toFile()).use { zip -> zip.entries().asSequence().filter { it.name.startsWith("history/revisions/") }
			.map { Json.parseToJsonElement(zip.getInputStream(it).readBytes().decodeToString()).jsonObject.getValue("schema").jsonPrimitive.int }.toList() }
		assertTrue(ProjectFormatV2.REVISION_SCHEMA_RIG in schemas)
		MaterializedRigStore.clear(); RigObjects.clear(); ReplayCheckpoints.clear()
		ProjectRepository().open(file).use { opened ->
			assertEquals(expected, hash(builder.build(opened.history.head().snapshot)))
		}
	}
}
