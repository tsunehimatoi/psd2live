package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.format.compile.document.ContentHash
import io.github.psd2live.project.*
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.umamo.format.art.*
import kotlin.test.*

/**
 * Direct edits act on the authored rig: the first edit of a generated document checkpoints its rig where the journal
 * ends, and so does an edit once [RigEditOverlay.CHECKPOINT_INTERVAL] entries follow the last checkpoint, so every build
 * replays a bounded number of entries and none needs the generated base.
 */
class AuthoredEditCheckpointTest {
	private val builder = WorkspacePreviewBuilder()
	private val pipeline = PSD2LivePipeline()

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

	private suspend fun runtime(): WorkspaceRuntime<RigPreviewModel> {
		val runtime = WorkspaceRuntime<RigPreviewModel>({ builder.build(it) }, rebuildFrom = { document, previous -> builder.build(document, previous) })
		val document = document()
		runtime.install(runtime.state.value.state, "project", document, builder.build(document))
		return runtime
	}

	private suspend fun create(runtime: WorkspaceRuntime<RigPreviewModel>, id: String): WorkspaceCapture<RigPreviewModel> {
		val before = runtime.capture()
		return WorkspaceDocumentCommands(runtime).execute(before.projectId, before.state, "Create $id", listOf(
			WorkspaceDocumentOperation("parameter_create", buildJsonObject { put("parameter_id", id); put("name", id) })), MutationAuthor.USER).capture
	}

	/** [document] built by generating the base and replaying its journal with every checkpoint left out. */
	private fun replayed(document: WorkspaceDocument): RigPreviewModel {
		val config = document.config()
		val journal = config.rigEdits.authoringJournal.filterNot(RigCheckpoint::isRecord)
		return pipeline.buildPreview(document.source, config.copy(rigEdits = config.rigEdits.copy(authoringJournal = journal)))
	}

	@Test fun theFirstEditCheckpointsTheGeneratedRigBeforeItsEntry() = runBlocking {
		val runtime = runtime()
		val edited = create(runtime, "First")
		val journal = edited.document.rigEdits.authoringJournal
		assertEquals(2, journal.size)
		assertTrue(RigCheckpoint.isRecord(journal[0]), "the checkpoint holds the rig the edit started from")
		assertNotNull(RigCheckpoint.generated(journal[0]), "it stores the generation, for a later update")
		assertEquals("structure", journal[1]["op"]?.jsonPrimitive?.content)
		assertEquals(hash(replayed(edited.document)), hash(edited.model))
		assertTrue(edited.model.rig.puppet.parameters.any { it.id.raw == "First" })
	}

	@Test fun editsPastTheIntervalCheckpointAgainAndBuildWithoutTheBase() = runBlocking {
		val runtime = runtime()
		var last = runtime.capture()
		repeat(RigEditOverlay.CHECKPOINT_INTERVAL + 2) { last = create(runtime, "P$it") }
		val overlay = last.document.rigEdits
		val checkpoints = overlay.authoringJournal.indices.filter { RigCheckpoint.isRecord(overlay.authoringJournal[it]) }
		assertEquals(listOf(0, RigEditOverlay.CHECKPOINT_INTERVAL + 1), checkpoints)
		assertTrue(overlay.afterCheckpoint.size <= RigEditOverlay.CHECKPOINT_INTERVAL)
		assertEquals(RigCheckpoint.generated(overlay.authoringJournal[0])?.authored?.rig?.puppet?.let { PuppetIr.toIr(it) },
			RigCheckpoint.generated(overlay.authoringJournal.last(RigCheckpoint::isRecord))?.authored?.rig?.puppet?.let { PuppetIr.toIr(it) },
			"the generation carries over to the later checkpoint")
		assertEquals(hash(replayed(last.document)), hash(last.model))
		// A cold build starts at the last checkpoint and never generates the base.
		MaterializedRigStore.clear()
		val cold = builder.build(last.document)
		assertFalse(cold.sources.baseKnown)
		assertEquals(hash(last.model), hash(cold))
	}

	@Test fun anEditRewritingAnEntryAfterTheCheckpointBuildsWithoutTheBase() = runBlocking {
		val runtime = runtime()
		create(runtime, "A")
		val edited = create(runtime, "B")
		MaterializedRigStore.clear()
		val cold = builder.build(edited.document)
		assertFalse(cold.sources.baseKnown)
		// The last entry rewritten in place, as a drag coalescing into it does.
		val journal = edited.document.rigEdits.authoringJournal
		val rewritten = Json.parseToJsonElement(journal.last().toString().replace("\"B\"", "\"C\"")).jsonObject
		val document = edited.document.copy(rigEdits = edited.document.rigEdits.copy(authoringJournal = journal.dropLast(1) + rewritten))
		val model = builder.build(document, cold)
		assertFalse(model.sources.baseKnown, "the rewrite replays from the checkpoint")
		assertTrue(model.rig.puppet.parameters.any { it.id.raw == "C" })
		assertTrue(model.rig.puppet.parameters.none { it.id.raw == "B" })
		assertEquals(hash(replayed(document)), hash(model))
	}

	@Test fun paintingNeverReachesTheGenerationAndAnUpdateFindsNothingToDo() = runBlocking {
		val runtime = runtime()
		create(runtime, "First")
		val before = runtime.capture()
		// Erasing the lower half of the hair would change its generated mesh, were the generation to read the art now.
		val painted = WorkspaceDocumentCommands(runtime).execute(before.projectId, before.state, "Erase", listOf(WorkspaceDocumentOperation(
			"source_paint_eraser", buildJsonObject {
				put("layer_id", "hair"); put("radius", 12)
				putJsonArray("points") { for (x in listOf(20, 32, 44)) add(buildJsonArray { add(x); add(48) }) }
			})), MutationAuthor.USER).capture
		assertNotNull(painted.document.generationSource, "painting freezes the generation input")
		val update = WorkspaceGenerationUpdateCommands(runtime).execute(painted.projectId, painted.state, "Update", MutationAuthor.USER)
		assertFalse(update.result.getValue("updated").jsonPrimitive.boolean, "the generators make what they made: the frozen art")
		assertEquals(hash(painted.model), hash(runtime.capture().model))
	}

	@Test fun parentAndDrawOrderOverridesRegenerate() = runBlocking {
		val runtime = runtime()
		val current = create(runtime, "A")
		val hair = current.model.rig.puppet.drawables.single { current.model.rig.layerIdByDrawableId[it.id.raw] == "hair" }
		val parent = current.model.rig.puppet.deformers.first { it.id != hair.parentDeformerId }.id.raw
		// A generation input: merged and checkpointed, so a build from the checkpoint has the mesh where it was moved.
		val reparented = builder.normalizeMeshEdits(current.document.copy(parentOverrides = mapOf("hair" to parent)), current.model)
		assertTrue(RigCheckpoint.isRecord(reparented.rigEdits.authoringJournal.last()))
		MaterializedRigStore.clear()
		val model = builder.build(reparented)
		assertEquals(parent, model.rig.puppet.drawables.single { it.id == hair.id }.parentDeformerId?.raw)
		// The base reads draw-order overrides too: a regeneration like the parent, and a cold build from its checkpoint shows it.
		val ordered = WorkspaceDocumentCommands(runtime).execute(current.projectId, current.state, "Order", listOf(
			WorkspaceDocumentOperation(WorkspaceDrawOrderEdits.OP, buildJsonObject { put("target", "layer:hair"); put("order", 900) })),
			MutationAuthor.USER).capture
		assertTrue(RigCheckpoint.isRecord(ordered.document.rigEdits.authoringJournal.last()))
		assertEquals(900f, ordered.model.rig.puppet.drawables.single { it.id == hair.id }.drawOrder)
		MaterializedRigStore.clear()
		assertEquals(hash(ordered.model), hash(builder.build(ordered.document)))
	}

	@Test fun aGenerationChangeMergesUnlessItRewritesEarlierEdits() = runBlocking {
		val runtime = runtime()
		create(runtime, "A")
		val current = create(runtime, "B")
		val settings = JsonObject(current.document.settings + ("headStrength" to JsonPrimitive(2)))
		// The static fields only a journal without a checkpoint replays: past one, they do not break the continuation.
		val legacy = current.document.copy(settings = settings, rigEdits = current.document.rigEdits.copy(
			parameterEdits = listOf(RigParameterEdit("Stale", "Stale", -1f, 1f, 0f, created = true))))
		val merged = builder.normalizeMeshEdits(legacy, current.model).rigEdits.authoringJournal
		assertEquals(current.document.rigEdits.authoringJournal.size + 1, merged.size)
		assertTrue(RigCheckpoint.isRecord(merged.last()), "the regeneration checkpoints its merge")
		// Rewriting an earlier entry in the same edit would replay it on the new generation: refused.
		val journal = current.document.rigEdits.authoringJournal
		val rewritten = Json.parseToJsonElement(journal.last().toString().replace("\"B\"", "\"C\"")).jsonObject
		val failure = assertFailsWith<IllegalArgumentException> { builder.normalizeMeshEdits(current.document.copy(settings = settings,
			rigEdits = current.document.rigEdits.copy(authoringJournal = journal.dropLast(1) + rewritten)), current.model) }
		assertTrue("commit them separately" in failure.message.orEmpty())
	}

	@Test fun bakingASwingAfterTheCheckpointCreatesItsParametersInTheJournal() = runBlocking {
		val runtime = runtime()
		create(runtime, "First")
		fun run(label: String, operation: String, arguments: JsonObject) = runBlocking {
			val before = runtime.capture()
			WorkspaceDocumentCommands(runtime).execute(before.projectId, before.state, label,
				listOf(WorkspaceDocumentOperation(operation, arguments)), MutationAuthor.USER).capture
		}
		val put = run("Swing", "swing_put", buildJsonObject {
			put("id", "s"); put("kind", "lateral"); putJsonArray("targets") { add("DeformHairBackPhysics") } })
		val grid = { model: RigPreviewModel -> (model.rig.puppet.deformers.single { it.id.raw == "DeformHairBackPhysics" }
			as org.umamo.runtime.model.Deformer.Warp).geometryGrid!! }
		val parameter = put.model.rig.puppet.parameters.single { it.id.raw.startsWith("ParamSwing") }.id
		val baked = run("Bake", "swing_delete", buildJsonObject { put("id", "s"); put("bake", true) })
		assertTrue(baked.document.rigEdits.parameterEdits.none { it.id == parameter.raw }, "a checkpointed journal never reads them")
		val (swung, kept) = grid(put.model) to grid(baked.model)
		assertEquals(swung.axes.single { it.parameterId == parameter }.keys.toList(), kept.axes.single { it.parameterId == parameter }.keys.toList())
		for (cell in swung.cells) {
			val other = kept.cells.single { it.coordinate.contentEquals(cell.coordinate) }
			for (i in cell.form.controlPoints.indices) assertEquals(cell.form.controlPoints[i], other.form.controlPoints[i], 1e-4f)
		}
		MaterializedRigStore.clear()
		assertEquals(hash(baked.model), hash(builder.build(baked.document)))
	}
}

/** A regeneration's dry run reports the checkpoint its merge would write and publishes nothing. */
class RegenerationPreviewTest {
	private val builder = WorkspacePreviewBuilder()

	@AfterTest fun forget() { MaterializedRigStore.clear() }

	@Test fun aSettingsChangePreviewsItsMergeWithoutCommitting() = runBlocking {
		val runtime = WorkspaceRuntime<RigPreviewModel>({ builder.build(it) }, rebuildFrom = { document, previous -> builder.build(document, previous) })
		val layers = listOf(LayerBounds(16, 8, 32, 48), LayerBounds(20, 12, 24, 24)).mapIndexed { index, bounds ->
			WorkspaceSourceLayer(LayerId(listOf("hair", "face")[index]), listOf("Back hair", "Face")[index], "", SourceLayerKind.Raster, true, index,
				bounds, 1f, false, LayerBlend.Normal, ChannelMask.ALL,
				LayerRaster(bounds.width, bounds.height, ByteArray(bounds.width * bounds.height * 4) { if (it % 4 == 3) 255.toByte() else 120 }), null, null, false)
		}
		val document = WorkspaceDocument(WorkspaceSourceArt(64, 64, layers, emptyList()), emptyMap(), emptySet(), mapOf(
			"hair" to LayerClassificationOverride(type = LayerType.PRESET, tag = SemanticTag.BACK_HAIR),
			"face" to LayerClassificationOverride(type = LayerType.PRESET, tag = SemanticTag.FACE),
		), emptyMap(), RigEditOverlay.Empty, WorkspaceSettingsCodec.encode(PipelineConfig(atlasSize = 256, exportMoc3 = false)))
		runtime.install(runtime.state.value.state, "project", document, builder.build(document))
		val before = runtime.capture()
		val commands = WorkspaceDocumentCommands(runtime)
		val edit = WorkspaceDocumentOperation("settings_update", buildJsonObject { putJsonObject("changes") { put("headStrength", 2) } })
		val preview = commands.previewRegeneration(before.projectId, before.state, listOf(edit))
		assertEquals(before, runtime.capture(), "a dry run publishes nothing")
		assertEquals(true, preview.getValue("dry_run").jsonPrimitive.boolean)
		assertEquals(true, preview.getValue("would_change").jsonPrimitive.boolean)
		val checkpoints = preview.getValue("checkpoints").jsonArray.map { it.jsonObject }
		assertEquals(listOf("regeneration"), checkpoints.map { it.getValue("kind").jsonPrimitive.content })
		val committed = commands.execute(before.projectId, before.state, "Head strength", listOf(edit), MutationAuthor.USER).capture
		assertEquals(preview.getValue("candidate_revision").jsonPrimitive.content, committed.revision, "the commit is the previewed candidate")
		assertTrue(RigCheckpoint.isRecord(committed.document.rigEdits.authoringJournal[checkpoints.single().getValue("index").jsonPrimitive.int]))
	}
}

/** An imported model checkpoints its authored rig the same way; its base is the import, which it never regenerates. */
class ImportedEditCheckpointTest {
	@org.junit.jupiter.api.io.TempDir lateinit var temporary: java.nio.file.Path
	private val builder = WorkspacePreviewBuilder()

	@Test fun editsOfAnImportedModelCheckpointAndReplayTheSame() = runBlocking {
		val runtime = WorkspaceRuntime<RigPreviewModel>({ builder.build(it) }, rebuildFrom = { document, previous -> builder.build(document, previous) })
		val file = writeCmo3Fixture(temporary.resolve("model.cmo3"), "old", "shared")
		WorkspaceCmo3Importer(runtime).import(file, Cmo3ImportMode.NEW, null, runtime.state.value.state, MutationAuthor.USER,
			initialConfig = PipelineConfig(atlasSize = 256))
		var last = runtime.capture()
		repeat(RigEditOverlay.CHECKPOINT_INTERVAL + 2) {
			val before = runtime.capture()
			last = WorkspaceDocumentCommands(runtime).execute(before.projectId, before.state, "P$it", listOf(WorkspaceDocumentOperation("parameter_create",
				buildJsonObject { put("parameter_id", "P$it"); put("name", "P$it") })), MutationAuthor.USER).capture
		}
		val journal = last.document.rigEdits.authoringJournal
		assertEquals(listOf(0, RigEditOverlay.CHECKPOINT_INTERVAL + 1), journal.indices.filter { RigCheckpoint.isRecord(journal[it]) })
		assertTrue(journal.filter(RigCheckpoint::isRecord).none { "generated" in it }, "an import is not regenerated")
		val config = last.document.config()
		val replayed = PSD2LivePipeline().buildPreview(last.document.source,
			config.copy(rigEdits = config.rigEdits.copy(authoringJournal = journal.filterNot(RigCheckpoint::isRecord))))
		assertEquals(ContentHash.of(PuppetIr.toIr(replayed.rig.puppet)), ContentHash.of(PuppetIr.toIr(last.model.rig.puppet)))
		assertEquals(ContentHash.of(PuppetIr.toIr(last.model.rig.puppet)), ContentHash.of(PuppetIr.toIr(builder.build(last.document).rig.puppet)))
	}
}

/** An imported model's split replays once, in its own commit: the commit checkpoints the authored rig after its record. */
class ImportedSplitCheckpointTest {
	@org.junit.jupiter.api.io.TempDir lateinit var temporary: java.nio.file.Path
	private val builder = WorkspacePreviewBuilder()

	@Test fun anImportedSplitIsCheckpointedInItsOwnCommit() = runBlocking {
		val runtime = WorkspaceRuntime<RigPreviewModel>({ builder.build(it) }, rebuildFrom = { document, previous -> builder.build(document, previous) })
		val file = writeCmo3Fixture(temporary.resolve("model.cmo3"), "old", "shared")
		WorkspaceCmo3Importer(runtime).import(file, Cmo3ImportMode.NEW, null, runtime.state.value.state, MutationAuthor.USER,
			initialConfig = PipelineConfig(atlasSize = 256))
		val imported = runtime.capture()
		val layer = imported.document.rigEdits.importedLayerIds.getValue("old")
		val split = WorkspaceDocumentCommands(runtime).execute(imported.projectId, imported.state, "Split", listOf(WorkspaceDocumentOperation(
			"source_split_polygon", buildJsonObject {
				put("layer_id", layer); putJsonArray("names") { add("Left"); add("Right") }
				putJsonArray("polygon") { for ((x, y) in listOf(0 to 0, 7 to 0, 7 to 32, 0 to 32)) add(buildJsonArray { add(x); add(y) }) }
			})), MutationAuthor.USER).capture
		val journal = split.document.rigEdits.authoringJournal
		assertTrue(RigCheckpoint.isRecord(journal.last()), "the commit ends with the checkpoint")
		assertTrue(RigEditOverlay.isLegacyRecord(journal[journal.size - 2]), "right after the split's record")
		assertFalse(split.document.rigEdits.replaysLegacy, "nothing replays the record again")
		val hash = { model: RigPreviewModel -> ContentHash.of(PuppetIr.toIr(model.rig.puppet)) }
		assertEquals(hash(split.model), hash(builder.build(split.document)), "a cold build from the checkpoint is the committed model")
		// Undo leaves the import as it was; redo returns the checkpointed split.
		runtime.checkout(split.projectId, split.state, imported.historyHead)
		assertEquals(imported.document, runtime.capture().document)
		val undone = runtime.capture()
		runtime.checkout(undone.projectId, undone.state, split.historyHead)
		assertEquals(split.document, runtime.capture().document)
		assertEquals(hash(split.model), hash(runtime.capture().model))
	}
}

