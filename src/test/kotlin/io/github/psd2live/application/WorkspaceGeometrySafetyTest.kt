package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.coroutines.*
import io.github.psd2live.core.quality.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.Files
import org.umamo.render.eval.CpuDeformationEvaluator
import kotlin.test.*

class WorkspaceGeometrySafetyTest {
    @TempDir lateinit var temporary: Path
    private val builder = WorkspacePreviewBuilder()
    private class Host(val runtime: WorkspaceRuntime<RigPreviewModel>) : WorkspaceBackendStub() {
        val commands = WorkspaceDocumentCommands(runtime)
        override fun snapshot(): WorkspaceProjectSnapshot {
            val c = runtime.capture()
            return WorkspaceProjectSnapshot(c.projectId, c.revision, c.historyHead, true, "Artwork",
                c.document.source.widthPx, c.document.source.heightPx, false, "Ready", null, emptyList(), emptyList(), state = c.state)
        }
        override suspend fun previewDocumentEdits(state: String, edits: List<WorkspaceDocumentOperation>): JsonObject =
            commands.preview(runtime.capture().projectId, state, edits)
        override suspend fun applyDocumentEdits(state: String, summary: String, edits: List<WorkspaceDocumentOperation>, author: MutationAuthor): WorkspaceMutationResult {
            val before = runtime.capture()
            return WorkspaceDocumentCommands.mutationResult(before, commands.execute(before.projectId, state, summary, edits, author), summary, edits)
        }
    }
    private suspend fun runtime(rebuild: suspend (WorkspaceDocument) -> RigPreviewModel = { builder.build(it) }): WorkspaceRuntime<RigPreviewModel> {
        val (png, _) = writeSourceImportFixture(temporary)
        val runtime = WorkspaceRuntime(rebuild)
        WorkspaceSourceImporter(runtime).createArtwork(sourceImportArguments(png), null, runtime.state.value.state,
            initialConfig = PipelineConfig(atlasSize = 256, meshOnly = true, exportMoc3 = false))
        val before = runtime.capture()
        WorkspaceDocumentCommands(runtime).execute(before.projectId, before.state, "Axis", listOf(
            WorkspaceDocumentOperation("parameter_create", buildJsonObject { put("parameter_id", "P"); put("name", "P") })), MutationAuthor.USER)
        runtime.saved(runtime.capture().state)
        return runtime
    }
    private fun deform(runtime: WorkspaceRuntime<RigPreviewModel>, x: Float, y: Float) = WorkspaceDocumentOperation("rig_deform", buildJsonObject {
        putJsonArray("changes") { add(buildJsonObject {
            put("target", "mesh:${runtime.capture().model.rig.puppet.drawables.first().id.raw}")
            putJsonObject("key") { put("P", 0f) }
            putJsonArray("operations") { add(buildJsonObject {
                if (y == 0f) {
                    val mesh = runtime.capture().model.rig.puppet.drawables.first().mesh!!
                    val points = mesh.positions; val bounds = RigGeometryTools.bounds(points)
                    val a = mesh.indices[0] * 2; val b = mesh.indices[1] * 2
                    put("type", "translate")
                    putJsonArray("delta") { add((points[b]-points[a])/bounds[2]); add((points[b+1]-points[a+1])/bounds[3]) }
                    putJsonObject("selection") { putJsonArray("rect") {
                        add((points[a]-bounds[0])/bounds[2]-0.0001f); add((points[a+1]-bounds[1])/bounds[3]-0.0001f)
                        add((points[a]-bounds[0])/bounds[2]+0.0001f); add((points[a+1]-bounds[1])/bounds[3]+0.0001f)
                    } }
                }
                else { put("type", "scale"); putJsonArray("factors") { add(x); add(y) } }
            }) }
        }) }
    })
    private fun pixels(model: RigPreviewModel) = WorkspaceViewRenderer.modelComposite(model, "geometry", mapOf("P" to 0f),
        model.rig.layerIdByDrawableId.values.toSet(), emptySet(),
        WorkspaceViewFrame.CanvasRect(Bounds(0f, 0f, model.analysis.source.widthPx.toFloat(), model.analysis.source.heightPx.toFloat())),
        WorkspaceViewBackground.TRANSPARENT, WorkspaceViewOutputSpec(256)).png

    private fun input(runtime: WorkspaceRuntime<RigPreviewModel>, id: String, edits: List<WorkspaceDocumentOperation>) = buildJsonObject {
        val c = runtime.capture()
        put("request_id", id); put("state", c.state); put("project_id", c.projectId)
        put("edits", JsonArray(edits.map { buildJsonObject { put("operation", it.operation); put("request", it.request) } }))
    }
    private suspend fun WorkspaceOperations.call(id: String, request: JsonObject) =
        registry.invoke(id, request, WorkspaceOperationContext(MutationAuthor.AGENT)).data
    private suspend fun WorkspaceOperations.wait(job: JsonObject) = call("job_wait", buildJsonObject { put("id", job.getValue("id")) })

    @Test fun safeDryRunMatchesCommitAndRejectsDoNotPublishHistoryOrDirtyState() = runBlocking {
        var invalidBuild = false
        val runtime = runtime { document ->
            val model = builder.build(document)
            if (!invalidBuild) model else model.copy(rig = model.rig.copy(puppet = model.rig.puppet.copy(
                drawables = model.rig.puppet.drawables.map { drawable -> drawable.copy(mesh = drawable.mesh?.let { mesh -> org.umamo.runtime.model.DrawableMesh(mesh.positions, mesh.uvs, intArrayOf(0, 1, Int.MAX_VALUE)) }) })))
        }
        val host = Host(runtime); val before = runtime.capture(); val history = runtime.history()
        invalidBuild = true
        WorkspaceOperations(host).use { operations ->
            val unsafe = listOf(deform(runtime, 1f, 0f))
            val preview = operations.wait(operations.call("workspace_preview_edits", input(runtime, "unsafe-preview", unsafe)))
            assertEquals("completed", preview.getValue("status").jsonPrimitive.content)
            val report = preview.getValue("result").jsonObject
            assertFalse(report.getValue("would_commit").jsonPrimitive.boolean)
            assertFalse(report.getValue("diagnostics").jsonObject.getValue("safe").jsonPrimitive.boolean)
            assertEquals(before, runtime.capture()); assertEquals(history, runtime.history())
            val commit = operations.wait(operations.call("workspace_apply_edits", input(runtime, "unsafe-commit", unsafe)))
            assertEquals("failed", commit.getValue("status").jsonPrimitive.content)
            assertEquals("geometry_unsafe", commit.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
            assertEquals(report.getValue("diagnostics"), commit.getValue("error").jsonObject.getValue("diagnostics"))
            assertEquals(before, runtime.capture()); assertEquals(history, runtime.history())
            invalidBuild = false
            val safe = listOf(deform(runtime, 0.9f, 0.9f), deform(runtime, 1.1f, 1.1f))
            val request = input(runtime, "safe-preview", safe)
            val job = operations.call("workspace_preview_edits", request)
            val result = operations.wait(job).getValue("result").jsonObject
            assertTrue(result.getValue("would_commit").jsonPrimitive.boolean)
            assertEquals(before, runtime.capture())
            assertEquals(job.getValue("id"), operations.call("workspace_preview_edits", request).getValue("id"))
            val applied = operations.wait(operations.call("workspace_apply_edits", input(runtime, "safe-commit", safe)))
            assertEquals("completed", applied.getValue("status").jsonPrimitive.content)
            assertEquals(result.getValue("candidate_revision").jsonPrimitive.content, runtime.capture().revision)
            assertEquals(history.selections.size + 1, runtime.history().selections.size)
            val final = runtime.capture()
            val archive = temporary.resolve("geometry.psd2live")
            ProjectRepository().save(ProjectSaveCapture(final.projectId, runtime.history(), JsonObject(emptyMap()), null,
                WorkspaceStore(temporary.resolve("store"))), archive)
            val reopened = ProjectRepository().open(archive).use { builder.build(it.history.head().snapshot) }
            assertContentEquals(final.model.rig.puppet.drawables.first().geometryGrid!!.cells.first().form.positionDeltas,
                reopened.rig.puppet.drawables.first().geometryGrid!!.cells.first().form.positionDeltas)
            val exported = PSD2LivePipeline().run(final.document.source, "geometry", temporary.resolve("export"),
                final.document.config()).exportedFiles
            val cmo = Cmo3ModelImport.read(Files.readAllBytes(exported.single { it.path.toString().endsWith(".cmo3") }.path)).puppet
            val evaluator = CpuDeformationEvaluator()
            val expected = evaluator.evaluate(final.model.rig.puppet, emptyMap()).worldPositions
            val actual = evaluator.evaluate(cmo, emptyMap()).worldPositions
            expected.forEach { (id, points) -> points.indices.forEach { assertEquals(points[it], actual.getValue(id)[it], 0.001f) } }
            val pixels = pixels(final.model)
            assertContentEquals(pixels, pixels(reopened))
            val visual = Path.of("build/geometry-safety-visual"); Files.createDirectories(visual)
            Files.write(visual.resolve("accepted.png"), pixels)
            runtime.checkout(final.projectId, final.state, before.historyHead)
            runtime.checkout(final.projectId, runtime.capture().state, final.historyHead)
            assertEquals(final.revision, runtime.capture().revision)
        }
    }

    @Test fun internalJournalAcceptsDegeneracyWarningsAndNoopPreviewPreservesCapture() = runBlocking {
        val runtime = runtime(); val commands = WorkspaceDocumentCommands(runtime); val before = runtime.capture()
        val change = deform(runtime, 1f, 0f).request.getValue("changes").jsonArray.single().jsonObject
        // A channel no-op does not create a journal, state or history node.
        val noop = WorkspaceDocumentOperation("object_edit_appearance", buildJsonObject { putJsonArray("edits") {
            add(buildJsonObject { put("action", "static"); put("kind", "mesh"); put("id", before.model.rig.puppet.drawables.first().id.raw); put("opacity", 1f) })
        } })
        val preview = commands.preview(before.projectId, before.state, listOf(noop))
        assertFalse(preview.getValue("would_change").jsonPrimitive.boolean)
        assertEquals(before, runtime.capture())
        val warningPreview = commands.preview(before.projectId, before.state, listOf(deform(runtime, 1f, 0f)))
        assertTrue(warningPreview.getValue("would_commit").jsonPrimitive.boolean)
        val diagnostics = warningPreview.getValue("diagnostics").jsonObject
        assertTrue(diagnostics.getValue("warnings").jsonArray.isNotEmpty())
        assertEquals("accept_with_diagnostics", diagnostics.getValue("quality").jsonObject.getValue("decision").jsonPrimitive.content)
        assertEquals(before, runtime.capture())
        val result = commands.executeJournal(before.projectId, before.state, "GUI geometry",
            JsonArray(listOf(JsonObject(change + ("op" to JsonPrimitive("deform"))))), MutationAuthor.USER)
        assertTrue(result.applied)
        assertEquals(diagnostics, result.geometryDiagnostics)
        val reopened = builder.build(result.capture.document)
        assertContentEquals(result.capture.model.rig.puppet.drawables.first().geometryGrid!!.cells.first().form.positionDeltas,
            reopened.rig.puppet.drawables.first().geometryGrid!!.cells.first().form.positionDeltas)

    }

    @Test fun previewCancellationAndConcurrentCommitDoNotPublishPrivateCandidates() = runBlocking {
        var block = false
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val runtime = runtime { doc -> if (block) { entered.complete(Unit); release.await() }; builder.build(doc) }
        val before = runtime.capture(); val commands = WorkspaceDocumentCommands(runtime)
        block = true
        val preview = async { commands.preview(before.projectId, before.state, listOf(deform(runtime, 0.9f, 0.9f))) }
        entered.await()
        preview.cancelAndJoin()
        assertEquals(before, runtime.capture())
        block = false
        val foreign = runtime.updateAuxiliary(before.projectId, before.state, buildJsonObject { put("foreign", true) })
        assertFailsWith<WorkspaceConflict> { commands.preview(before.projectId, before.state, listOf(deform(runtime, 0.9f, 0.9f))) }
        assertEquals(foreign, runtime.capture())
    }

    @Test fun previewCreationRequiresStableIdsAndSharesFinalBatchCandidate() = runBlocking {
        val runtime = runtime(); val commands = WorkspaceDocumentCommands(runtime); val before = runtime.capture()
        val create = WorkspaceDocumentOperation("canvas_rotation", buildJsonObject { put("name", "Rotation"); putJsonArray("meshes") { add(before.model.rig.puppet.drawables.first().id.raw) } })
        assertFailsWith<IllegalArgumentException> { commands.preview(before.projectId, before.state, listOf(create)) }
        val stable = create.copy(request = JsonObject(create.request + ("id" to JsonPrimitive("previewRotation"))))
        val preview = commands.preview(before.projectId, before.state, listOf(stable))
        assertTrue(preview.getValue("changed").jsonArray.contains(JsonPrimitive("rotation:previewRotation")))
        val result = commands.execute(before.projectId, before.state, "Created", listOf(stable), MutationAuthor.USER)
        assertEquals(preview.getValue("candidate_revision").jsonPrimitive.content, result.capture.revision)
    }

    @Test fun gateChecksTheFinalRebuiltCandidateRatherThanRejectingAnIntermediateShape() = runBlocking {
        val runtime = runtime(); val before = runtime.capture(); val commands = WorkspaceDocumentCommands(runtime)
        val unsafe = deform(runtime, 1f, 0f)
        val change = unsafe.request.getValue("changes").jsonArray.single().jsonObject
        val transform = change.getValue("operations").jsonArray.single().jsonObject
        val inverse = JsonObject(transform + ("delta" to JsonArray(transform.getValue("delta").jsonArray.map { JsonPrimitive(-it.jsonPrimitive.float) })))
        val repaired = WorkspaceDocumentOperation("rig_deform", buildJsonObject { put("changes", JsonArray(listOf(
            JsonObject(change + ("operations" to JsonArray(listOf(inverse))))))) })
        val edits = listOf(unsafe, repaired)
        val preview = commands.preview(before.projectId, before.state, edits)
        assertTrue(preview.getValue("diagnostics").jsonObject.getValue("safe").jsonPrimitive.boolean)
        val committed = commands.execute(before.projectId, before.state, "Repaired batch", edits, MutationAuthor.USER)
        assertEquals(preview.getValue("candidate_revision").jsonPrimitive.content, committed.capture.revision)
        assertEquals(3, runtime.history().selections.size)
    }

    @Test fun intentionalFoldoverInformationSurvivesPublicPreviewAndCommitContracts() = runBlocking {
        val runtime = runtime(); val before = runtime.capture()
        val source = deform(runtime, 1f, 0f)
        val change = source.request.getValue("changes").jsonArray.single().jsonObject
        val transform = change.getValue("operations").jsonArray.single().jsonObject
        val folded = JsonObject(transform + ("delta" to JsonArray(transform.getValue("delta").jsonArray.map { JsonPrimitive(it.jsonPrimitive.float * 1.5f) })))
        val edit = source.copy(request = buildJsonObject { put("changes", JsonArray(listOf(
            JsonObject(change + ("operations" to JsonArray(listOf(folded))))))) })
        WorkspaceOperations(Host(runtime)).use { operations ->
            val preview = operations.wait(operations.call("workspace_preview_edits", input(runtime, "fold-preview", listOf(edit)))).getValue("result").jsonObject
            assertTrue(preview.getValue("would_commit").jsonPrimitive.boolean)
            assertTrue(preview.getValue("diagnostics").jsonObject.getValue("information").jsonArray.isNotEmpty())
            assertTrue(preview.getValue("diagnostics").jsonObject.getValue("warnings").jsonArray.isEmpty())
            val job = operations.wait(operations.call("workspace_apply_edits", input(runtime, "fold-commit", listOf(edit))))
            assertEquals("completed", job.getValue("status").jsonPrimitive.content)
            assertEquals(preview.getValue("diagnostics"), job.getValue("result").jsonObject.getValue("geometry_diagnostics"))
            assertNotEquals(before.state, runtime.capture().state)
        }
    }
    @Test fun degeneracyWarningSurvivesPublicCommitArchiveExportAndHistoryReplay() = runBlocking {
        val runtime = runtime(); val before = runtime.capture()
        val edits = listOf(deform(runtime, 1f, 0f))
        WorkspaceOperations(Host(runtime)).use { operations ->
            val preview = operations.wait(operations.call("workspace_preview_edits", input(runtime, "flat-preview", edits))).getValue("result").jsonObject
            assertTrue(preview.getValue("would_commit").jsonPrimitive.boolean)
            assertEquals(before, runtime.capture())
            val job = operations.wait(operations.call("workspace_apply_edits", input(runtime, "flat-commit", edits)))
            assertEquals("completed", job.getValue("status").jsonPrimitive.content)
            val report = job.getValue("result").jsonObject.getValue("geometry_diagnostics").jsonObject
            assertEquals(preview.getValue("diagnostics"), report)
            assertTrue(report.getValue("violations").jsonArray.isEmpty())
            assertTrue(report.getValue("warnings").jsonArray.isNotEmpty())
            assertEquals("warning", report.getValue("quality").jsonObject.getValue("findings").jsonArray.first().jsonObject.getValue("severity").jsonPrimitive.content)
            val final = runtime.capture()
            val archive = temporary.resolve("flat.psd2live")
            ProjectRepository().save(ProjectSaveCapture(final.projectId, runtime.history(), JsonObject(emptyMap()), null,
                WorkspaceStore(temporary.resolve("flat-store"))), archive)
            val reopened = ProjectRepository().open(archive).use { builder.build(it.history.head().snapshot) }
            assertContentEquals(pixels(final.model), pixels(reopened))
            val exported = PSD2LivePipeline().run(final.document.source, "flat", temporary.resolve("flat-export"), final.document.config()).exportedFiles
            val cmo = Cmo3ModelImport.read(Files.readAllBytes(exported.single { it.path.toString().endsWith(".cmo3") }.path)).puppet
            val evaluator = CpuDeformationEvaluator()
            val expected = evaluator.evaluate(final.model.rig.puppet, emptyMap()).worldPositions
            val actual = evaluator.evaluate(cmo, emptyMap()).worldPositions
            expected.forEach { (id, points) -> points.indices.forEach { assertEquals(points[it], actual.getValue(id)[it], 0.001f) } }
            runtime.checkout(final.projectId, final.state, before.historyHead)
            runtime.checkout(final.projectId, runtime.capture().state, final.historyHead)
            assertEquals(final.revision, runtime.capture().revision)
        }
    }

}
