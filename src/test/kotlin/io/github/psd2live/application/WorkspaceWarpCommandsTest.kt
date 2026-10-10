package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import org.umamo.render.eval.CpuDeformationEvaluator
import java.nio.file.Path
import kotlin.test.*

class WorkspaceWarpCommandsTest {
    @TempDir lateinit var temporary: Path
    private val builder = WorkspacePreviewBuilder()
    private val agent = WorkspaceOperationContext(MutationAuthor.AGENT)
    private suspend fun fixture(rebuild: suspend (WorkspaceDocument) -> RigPreviewModel = { builder.build(it) }): WorkspaceRuntime<RigPreviewModel> =
        WorkspaceRuntime(rebuild).also { simulationFixture(it) }
    private fun parent(mesh: String) = WorkspaceDocumentOperation("canvas_warp", buildJsonObject {
        put("id", "parent"); put("name", "Parent"); put("rows", 2); put("columns", 2); put("meshes", buildJsonArray { add(mesh) })
    })
    private fun warp(mesh: String, id: String = "child") = WorkspaceDocumentOperation("rig_create_warp", buildJsonObject {
        put("id", id); put("name", id); put("targets", buildJsonArray { add("mesh:$mesh") }); put("fit_local", true)
    })
    private suspend fun prepare(runtime: WorkspaceRuntime<RigPreviewModel>): WorkspaceCapture<RigPreviewModel> {
        val before = runtime.capture()
        return WorkspaceDocumentCommands(runtime).execute(before.projectId, before.state, "Parent", listOf(parent(before.model.rig.puppet.drawables.single().id.raw)), MutationAuthor.USER).capture
    }
    private class Host(val runtime: WorkspaceRuntime<RigPreviewModel>, val after: suspend () -> Unit = {}) : WorkspaceBackendStub() {
        override fun snapshot() = WorkspaceReadSession(runtime.read()).snapshot()
        override suspend fun createIndependentWarp(request: JsonObject, expectedState: String): WorkspaceMutationResult {
            val result = WorkspaceWarpCommands(runtime).execute(runtime.capture().projectId, expectedState, request, MutationAuthor.AGENT)
            after(); return result.mutation
        }
        override suspend fun applyDocumentEdits(state: String, summary: String, edits: List<WorkspaceDocumentOperation>, author: MutationAuthor): WorkspaceMutationResult {
            val before = runtime.capture(); val result = WorkspaceDocumentCommands(runtime).execute(before.projectId, state, summary, edits, author)
            after(); return WorkspaceDocumentCommands.mutationResult(before, result, summary, edits)
        }
    }
    private fun input(runtime: WorkspaceRuntime<RigPreviewModel>, fields: JsonObject, request: String = "warp") = JsonObject(fields + buildJsonObject {
        val capture = runtime.capture(); put("state", capture.state); put("project_id", capture.projectId); put("request_id", request)
    })
    private suspend fun WorkspaceOperations.call(id: String, request: JsonObject) = registry.invoke(id, request, agent).data
    private suspend fun WorkspaceOperations.wait(job: JsonObject) = call("job_wait", buildJsonObject { put("id", job.getValue("id")) })

    @Test fun orderedBatchResolvesCreatedParentsAndPersistsBothWarpsAsOneHistoryNode() = runBlocking<Unit> {
        val runtime = fixture(); val before = runtime.capture(); val mesh = before.model.rig.puppet.drawables.single()
        val edits = listOf(parent(mesh.id.raw), warp(mesh.id.raw), warp(mesh.id.raw, "grandchild"))
        val commit = WorkspaceDocumentCommands(runtime).execute(before.projectId, before.state, "Nested warps", edits, MutationAuthor.AGENT)
        assertEquals(2, runtime.history().selections.size)
        val after = commit.capture
        assertEquals("child", after.model.rig.puppet.deformers.single { it.id.raw == "grandchild" }.parent!!.raw)
        assertEquals("grandchild", after.model.rig.puppet.drawables.single().parentDeformerId!!.raw)
        assertTrue(after.document.rigEdits.warpEdits.isEmpty()); assertTrue(after.document.rigEdits.structureEdits.isEmpty())
        assertEquals(2, after.document.rigEdits.authoringJournal.count { it["op"]?.jsonPrimitive?.content == "warp" })
        val result = WorkspaceDocumentCommands.mutationResult(before, commit, "Nested warps", edits)
        assertTrue(result.affectedObjectIds.containsAll(listOf("warp:parent", "warp:child", "warp:grandchild")))
        val store = WorkspaceStore(temporary); store.persistHistory(after.projectId, runtime.history())
        val history = assertNotNull(WorkspaceStore(temporary).loadHistory(after.projectId))
        val document = history.selections().single { it.node.id == after.historyHead }.snapshot
        assertEquals(after.revision, WorkspaceRevisions.of(document))
        assertEquals(after.document.rigEdits, document.rigEdits)
        val replay = builder.build(document)
        val evaluator = CpuDeformationEvaluator()
        val expected = evaluator.evaluate(before.model.rig.puppet, emptyMap()).worldPositions.getValue(mesh.id)
        val actual = evaluator.evaluate(replay.rig.puppet, emptyMap()).worldPositions.getValue(mesh.id)
        expected.indices.forEach { assertEquals(expected[it], actual[it], 0.0001f) }
        assertEquals(before.document, runtime.history().selections.first().snapshot)
    }

    @Test fun aWarpUsesTheOrderedJournalAfterAParentCreatedByAuthoring() = runBlocking<Unit> {
        val runtime = fixture(); val before = prepare(runtime); val mesh = before.model.rig.puppet.drawables.single()
        val request = buildJsonObject { put("id", "typed"); put("name", "Typed"); putJsonArray("targets") { add("mesh:${mesh.id.raw}") }
            put("rows", 4); put("columns", 4) }
        val result = WorkspaceWarpCommands(runtime).execute(before.projectId, before.state, request, MutationAuthor.USER)
        assertEquals("warp:typed", result.mutation.warpResult().getValue("target").jsonPrimitive.content)
        assertTrue(result.commit.capture.document.rigEdits.warpEdits.isEmpty())
        assertEquals("user", runtime.history().selections.last().node.actor)
        assertEquals("typed", builder.build(result.commit.capture.document).rig.puppet.drawables.single().parentDeformerId!!.raw)
    }

    @Test fun invalidCandidateAndLaterBatchFailureNeverPublishAnyWarpOrPrefix() = runBlocking<Unit> {
        val runtime = fixture(); val before = prepare(runtime); val mesh = before.model.rig.puppet.drawables.single()
        val commands = WorkspaceWarpCommands(runtime); val history = runtime.history()
        val request = warp(mesh.id.raw).request
        assertFailsWith<WorkspaceConflict> { commands.execute(before.projectId, "stale", JsonObject(emptyMap()), MutationAuthor.AGENT) }
        for (bad in listOf(JsonObject(request + ("targets" to buildJsonArray { add("warp:parent") })),
            JsonObject(request + ("id" to JsonPrimitive("parent"))), JsonObject(request + ("targets" to buildJsonArray { add("mesh:missing") })))) {
            assertFails { commands.execute(before.projectId, before.state, bad, MutationAuthor.AGENT) }
        }
        assertFailsWith<IllegalStateException> { commands.execute(before.projectId, before.state, request, MutationAuthor.USER) { _, _, _ -> error("Reject projection") } }
        val failed = assertFailsWith<WorkspaceBatchEditException> { WorkspaceDocumentCommands(runtime).execute(before.projectId, before.state, "Rollback",
            listOf(warp(mesh.id.raw), warp("missing", "later")), MutationAuthor.AGENT) }
        assertEquals(1, failed.index); assertEquals("rig_create_warp", failed.editOperation)
        assertEquals(before, runtime.capture()); assertEquals(history, runtime.history())
    }

    @Test fun singleJobCancellationAndConcurrentAuxiliaryCommitDiscardTheCandidate() = runBlocking<Unit> {
        for (cancel in listOf(true, false)) {
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val runtime = fixture { document ->
                if (document.rigEdits.authoringJournal.any { it["op"]?.jsonPrimitive?.content == "warp" }) { entered.complete(Unit); release.await() }
                builder.build(document)
            }
            val before = prepare(runtime); val history = runtime.history()
            WorkspaceOperations(Host(runtime)).use { operations ->
                val request = input(runtime, warp(before.model.rig.puppet.drawables.single().id.raw).request)
                val job = operations.call("rig_create_warp", request); withTimeout(10000) { entered.await() }
                val expected = if (cancel) {
                    operations.call("job_cancel", buildJsonObject { put("id", job.getValue("id")); put("request_id", "cancel") }); before
                } else runtime.updateAuxiliary(before.projectId, before.state, buildJsonObject { put("foreign", true) })
                release.complete(Unit)
                val terminal = operations.wait(job)
                assertEquals(if (cancel) "cancelled" else "failed", terminal.getValue("status").jsonPrimitive.content)
                if (!cancel) assertEquals("state_conflict", terminal.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
                assertEquals(expected, runtime.capture()); assertEquals(history, runtime.history())
                assertEquals(job.getValue("id"), operations.call("rig_create_warp", request).getValue("id"))
            }
        }
    }

    @Test fun committedJobKeepsGeneratedHandleAfterLateCancellationOrRefreshFailure() = runBlocking<Unit> {
        for (cancel in listOf(true, false)) {
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val runtime = fixture(); val before = prepare(runtime)
            WorkspaceOperations(Host(runtime) { entered.complete(Unit); release.await(); error("Refresh failed") }).use { operations ->
                val fields = JsonObject(warp(before.model.rig.puppet.drawables.single().id.raw).request - "id")
                val request = input(runtime, fields); val job = operations.call("rig_create_warp", request)
                withTimeout(10000) { entered.await() }
                if (cancel) operations.call("job_cancel", buildJsonObject { put("id", job.getValue("id")); put("request_id", "cancel") })
                release.complete(Unit)
                val terminal = operations.wait(job); assertEquals("completed", terminal.getValue("status").jsonPrimitive.content, terminal.toString())
                val result = terminal.getValue("result").jsonObject
                validateOperationSchema(result, WorkspaceJobResultSchemas.result("rig_create_warp"))
                val id = runtime.capture().model.rig.puppet.drawables.single().parentDeformerId!!.raw
                assertEquals("warp:$id", result.getValue("target").jsonPrimitive.content)
                assertEquals(runtime.capture().state, result.getValue("state").jsonPrimitive.content)
                assertEquals(job.getValue("id"), operations.call("rig_create_warp", request).getValue("id"))
                assertEquals(terminal, operations.wait(job)); assertEquals(3, runtime.history().selections.size)
            }
        }
    }

    @Test fun publicBatchUsesWarpSchemaCandidateReferencesAndExactGeneratedHandles() = runBlocking<Unit> {
        val runtime = fixture(); val before = runtime.capture(); val mesh = before.model.rig.puppet.drawables.single()
        WorkspaceOperations(Host(runtime)).use { operations ->
            val definition = operations.registry.definition("rig_create_warp"); assertTrue(definition.jobBacked && definition.batchable)
            validateOperationSchema(input(runtime, buildJsonObject {
                put("name", "Many meshes"); put("targets", JsonArray((0..69).map { JsonPrimitive("mesh:mesh-$it") }))
            }), definition.requestSchema)
            val edits = listOf(parent(mesh.id.raw), warp(mesh.id.raw), warp(mesh.id.raw, "grandchild"))
            val request = input(runtime, buildJsonObject { put("edits", JsonArray(edits.map { buildJsonObject { put("operation", it.operation); put("request", it.request) } })) })
            val terminal = operations.wait(operations.call("workspace_apply_edits", request))
            assertEquals("completed", terminal.getValue("status").jsonPrimitive.content, terminal.toString())
            val result = terminal.getValue("result").jsonObject
            assertEquals(3, result.getValue("edit_count").jsonPrimitive.int)
            assertTrue(result.getValue("changed").jsonArray.map { it.jsonPrimitive.content }.containsAll(listOf("warp:child", "warp:grandchild")))
            assertEquals(2, runtime.history().selections.size)
            val bad = JsonObject(warp(mesh.id.raw, "duplicate").request + ("targets" to buildJsonArray { add("mesh:${mesh.id.raw}"); add("mesh:${mesh.id.raw}") }))
            assertFails { operations.call("rig_create_warp", input(runtime, bad, "duplicate")) }
        }
    }
}
