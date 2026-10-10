package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.umamo.format.art.*
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.ParameterKind
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Actual rig command preparation and replay without constructing a desktop view model. */
class WorkspaceDocumentCommandsTest {
    @Test fun materializedCandidatesKeepTheUiExecutorResponsiveAndCancellationPublishesNothing() = runBlocking<Unit> {
        Executors.newSingleThreadExecutor { task -> Thread(task, "Synthetic UI") }.asCoroutineDispatcher().use { ui ->
            val uiThread = withContext(ui) { Thread.currentThread() }
            for (cancel in listOf(true, false)) {
                val runtime = runtime(); val before = runtime.capture(); val entered = CompletableDeferred<Unit>()
                val release = CountDownLatch(1)
                val command = async(ui) {
                    WorkspaceDocumentCommands(runtime).executeCandidate(before.projectId, before.state, "Materialized draft", MutationAuthor.USER,
                        mutation = { document, model ->
                            entered.complete(Unit); check(release.await(10, TimeUnit.SECONDS))
                            WorkspaceDocumentEdits.apply(setting(2), document, model)
                        }, beforeCommit = { _, _, _ -> assertSame(uiThread, Thread.currentThread()) })
                }
                try {
                    withTimeout(5000) { entered.await() }
                    withTimeout(5000) { withContext(ui) { assertEquals(before.state, runtime.capture().state) } }
                    if (cancel) {
                        command.cancelAndJoin()
                        assertEquals(before, runtime.capture()); assertEquals(1, runtime.history().selections.size)
                    } else {
                        release.countDown()
                        assertTrue(command.await().applied); assertEquals(2, runtime.history().selections.size)
                    }
                } finally { release.countDown(); command.cancelAndJoin() }
            }
        }
    }
    private fun document(): WorkspaceDocument {
        val layers = (0..1).map { n -> WorkspaceSourceLayer(LayerId("art$n"), "Artwork $n", "", SourceLayerKind.Raster,
            true, n, LayerBounds(0, 0, 8, 8), 1f, false, LayerBlend.Normal, ChannelMask.ALL,
            LayerRaster(8, 8, ByteArray(8 * 8 * 4) { if (it % 4 == 3) 255.toByte() else 80 }), null, null, false) }
        return WorkspaceDocument(WorkspaceSourceArt(16, 16, layers, emptyList()), emptyMap(), emptySet(),
            layers.associate { it.id.raw to LayerClassificationOverride(type = LayerType.PRESET, tag = SemanticTag.OBJECTS) },
            emptyMap(), RigEditOverlay.Empty, WorkspaceSettingsCodec.encode(PipelineConfig(atlasSize = 256, meshOnly = true, exportMoc3 = false)))
    }
    private fun operation(id: String, request: JsonObject) = WorkspaceDocumentOperation(id, request)
    private fun parameter(id: String) = operation("parameter_create", buildJsonObject { put("parameter_id", id); put("name", id) })
    private fun setting(value: Int) = operation("settings_update", buildJsonObject { putJsonObject("changes") { put("headStrength", value) } })
    private suspend fun runtime(rebuild: (suspend (WorkspaceDocument) -> RigPreviewModel)? = null): WorkspaceRuntime<RigPreviewModel> {
        val builder = WorkspacePreviewBuilder()
        val runtime = WorkspaceRuntime(rebuild ?: { doc -> builder.build(doc) })
        val document = document()
        runtime.install(runtime.state.value.state, "project", document, builder.build(document))
        return runtime
    }

    @Test fun orderedCanvasPathsAndPhysicsUseDraftModelThenReplayOneNode() = runBlocking {
        val runtime = runtime()
        val before = runtime.capture()
        val meshes = before.model.rig.puppet.drawables
        val first = meshes[0].id.raw; val second = meshes[1].id.raw
        val points = meshes[0].mesh!!.positions
        val input = listOf(parameter("BatchInput"), parameter("BatchOutput"),
            operation("canvas_warp", buildJsonObject {
                put("id", "batchWarp"); put("name", "Batch warp"); putJsonArray("meshes") { add(first) }; put("rows", 2); put("columns", 2)
            }),
            operation("canvas_rotation", buildJsonObject {
                put("id", "batchRotation"); put("name", "Batch rotation"); put("add_to", "parent_of_deformer"); put("deformer_id", "batchWarp")
            }),
            operation("canvas_glue", buildJsonObject { put("id", "batchGlue"); put("mesh_a", first); put("mesh_b", second); put("distance", 128) }),
            operation("path_put", buildJsonObject {
                put("id", "batchPath"); put("target", "mesh:$first"); putJsonArray("points") {
                    add(buildJsonArray { add(points[0]); add(points[1]) })
                    add(buildJsonArray { add(points[points.size - 2]); add(points.last()) })
                }
            }),
            operation("physics_put", buildJsonObject {
                put("id", "BatchPhysics"); put("name", "Batch physics")
                putJsonArray("inputs") { add(buildJsonObject { put("parameter", "BatchInput"); put("type", "angle"); put("weight", 100) }) }
                putJsonArray("outputs") { add(buildJsonObject { put("parameter", "BatchOutput"); put("vertex", 1); put("scale", 1) }) }
            }),
            operation("physics_config", buildJsonObject { put("fps", 120); putJsonArray("order") { add("BatchPhysics") } }))
        val result = WorkspaceDocumentCommands(runtime).execute(before.projectId, before.state, "Rig draft", input, MutationAuthor.AGENT)
        assertTrue(result.applied)
        assertEquals(2, runtime.history().selections.size)
        val puppet = result.capture.model.rig.puppet
        val warp = puppet.deformers.single { it.id.raw == "batchWarp" }
        assertIs<Deformer.Warp>(warp)
        assertEquals("batchRotation", warp.parent!!.raw)
        assertTrue(puppet.glues.single { it.id == "batchGlue" }.pairs.isNotEmpty())
        assertEquals("batchPath", puppet.deformPaths.single().id)
        assertEquals(120, result.capture.document.rigEdits.physicsFps)
        assertEquals("BatchOutput", result.capture.document.rigEdits.physicsEdits.single().outputs.single().parameter)
        assertTrue(WorkspaceDocumentCommands.createdObjectIds(before.model.rig.puppet, puppet)
            .containsAll(listOf("warp:batchWarp", "rotation:batchRotation", "glue:batchGlue", "path:batchPath", "parameter:BatchInput")))
        val replayed = WorkspacePreviewBuilder().build(result.capture.document)
        assertEquals(puppet.deformers.map { it.id }, replayed.rig.puppet.deformers.map { it.id })
        assertEquals(puppet.deformPaths, replayed.rig.puppet.deformPaths)
        runtime.checkout(before.projectId, result.capture.state, before.historyHead)
        assertFalse(runtime.capture().model.rig.puppet.parameters.any { it.id.raw == "BatchInput" })
        runtime.checkout(before.projectId, runtime.capture().state, result.capture.historyHead)
        assertEquals("batchPath", runtime.capture().model.rig.puppet.deformPaths.single().id)
    }

    @Test fun parameterDeletionReplaysAfterFormsAndUsesTheLastAuthoredDefault() = runBlocking {
        val runtime = runtime()
        val before = runtime.capture()
        val commands = WorkspaceDocumentCommands(runtime)
        val target = "mesh:" + before.model.rig.puppet.drawables.first().id.raw
        val forms = operation("keyform_apply", buildJsonObject { putJsonArray("changes") {
            listOf(-1f to 0.2f, 0f to 0.6f, 1f to 0.9f).forEach { (key, opacity) -> add(buildJsonObject {
                put("op", "set"); put("target", target); putJsonObject("key") { put("Axis", key) }
                putJsonObject("channels") { put("opacity", opacity) }
            }) }
        } })
        val authored = commands.execute(before.projectId, before.state, "Axis and forms", listOf(parameter("Axis"), forms,
            operation("parameter_update", buildJsonObject { put("parameter_id", "Axis"); put("default", 1) })), MutationAuthor.AGENT).capture
        val deleted = commands.execute(authored.projectId, authored.state, "Delete axis",
            listOf(operation("parameter_delete", buildJsonObject { put("parameter_id", "Axis") })), MutationAuthor.USER).capture
        assertFalse(deleted.model.rig.puppet.parameters.any { it.id.raw == "Axis" })
        assertEquals(0.9f, deleted.model.rig.puppet.drawables.first().opacity, 1e-5f)
        val replayed = WorkspacePreviewBuilder().build(deleted.document)
        assertFalse(replayed.rig.puppet.parameters.any { it.id.raw == "Axis" })
        assertEquals(0.9f, replayed.rig.puppet.drawables.first().opacity, 1e-5f)
        val restored = runtime.checkout(authored.projectId, deleted.state, authored.historyHead)
        assertEquals(1f, restored.model.rig.puppet.parameters.single { it.id.raw == "Axis" }.default)
        commands.execute(restored.projectId, restored.state, "Definition metadata", listOf(operation("parameter_update", buildJsonObject {
            put("parameter_id", "Axis"); put("kind", "blend_shape"); put("repeat", true)
        })), MutationAuthor.AGENT).capture.let {
            val parameter = it.model.rig.puppet.parameters.single { it.id.raw == "Axis" }
            assertEquals(ParameterKind.BLEND_SHAPE, parameter.kind)
            assertTrue(parameter.repeat)
        }
    }

    @Test fun channelCaptureWithIdenticalGeometryPersistsAndItsRetryDoesNotCreateHistory() = runBlocking {
        val runtime = runtime()
        val commands = WorkspaceDocumentCommands(runtime)
        val root = runtime.capture()
        val axis = commands.execute(root.projectId, root.state, "Axis", listOf(parameter("Axis")), MutationAuthor.USER).capture
        val target = "mesh:" + axis.model.rig.puppet.drawables.first().id.raw
        val seeded = commands.executeJournal(axis.projectId, axis.state, "Seed", buildJsonArray { add(buildJsonObject {
            put("op", "seed"); put("target", target); putJsonObject("key") { put("Axis", 0) }
        }) }, MutationAuthor.USER).capture
        val points = seeded.model.rig.puppet.drawables.first().mesh!!.positions.size
        val edit = buildJsonArray { add(buildJsonObject {
            put("op", "set"); put("target", target); putJsonObject("key") { put("Axis", 0) }
            putJsonObject("geometry") { put("positionDeltas", JsonArray(List(points) { JsonPrimitive(0) })) }
            putJsonObject("channels") { put("opacity", 0.4); putJsonArray("multiplyColor") { add(0.5); add(0.6); add(0.7) } }
        }) }
        val captured = commands.executeJournal(seeded.projectId, seeded.state, "Channel capture", edit, MutationAuthor.AGENT)
        assertTrue(captured.applied)
        assertEquals(4, runtime.history().selections.size)
        val noOp = commands.executeJournal(captured.capture.projectId, captured.capture.state, "Identical capture", edit, MutationAuthor.USER)
        assertFalse(noOp.applied)
        assertSame(captured.capture, noOp.capture)
        assertEquals(4, runtime.history().selections.size)
        val deleted = commands.execute(noOp.capture.projectId, noOp.capture.state, "Remove axis",
            listOf(operation("parameter_delete", buildJsonObject { put("parameter_id", "Axis") })), MutationAuthor.USER).capture
        assertEquals(0.4f, deleted.model.rig.puppet.drawables.first().opacity, 1e-6f)
        assertEquals(org.umamo.runtime.model.ColorRgb(0.5f, 0.6f, 0.7f), deleted.model.rig.puppet.drawables.first().multiplyColor)
        val replayed = WorkspacePreviewBuilder().build(deleted.document)
        assertEquals(deleted.model.rig.puppet.drawables.first().multiplyColor, replayed.rig.puppet.drawables.first().multiplyColor)
        val blend = commands.execute(deleted.projectId, deleted.state, "Blend shape", listOf(operation("parameter_create",
            buildJsonObject { put("parameter_id", "Blend"); put("name", "Blend"); put("kind", "blend_shape") })), MutationAuthor.AGENT).capture
        assertEquals(0f, blend.model.rig.puppet.parameters.single { it.id.raw == "Blend" }.min)
    }

    @Test fun legacyParameterOverridesKeepTheirRevisionAndReplayWithOrderedDeletion() = runBlocking {
        val original = document().copy(rigEdits = RigEditOverlay.Empty.copy(parameterEdits = listOf(RigParameterEdit("LegacyAxis", "Legacy axis", -1f, 1f, 0f, created = true))))
        val builder = WorkspacePreviewBuilder()
        val model = builder.build(original)
        val target = RigTargetRef(RigTargetKind.ART_MESH, model.rig.puppet.drawables.first().id.raw)
        val legacy = original.copy(rigEdits = original.rigEdits.copy(keyformSetEdits = listOf(RigKeyformSetEdit(target,
            mapOf("LegacyAxis" to 0f), channels = RigKeyformChannelsEdit(opacity = 0.4f)))))
        val revision = WorkspaceRevisions.of(legacy)
        val runtime = WorkspaceRuntime<RigPreviewModel>({ doc -> builder.build(doc) })
        val before = runtime.install(runtime.state.value.state, "legacy", legacy, builder.build(legacy))
        val deleted = WorkspaceDocumentCommands(runtime).execute(before.projectId, before.state, "Delete legacy axis",
            listOf(operation("parameter_delete", buildJsonObject { put("parameter_id", "LegacyAxis") })), MutationAuthor.AGENT).capture
        assertTrue(deleted.model.rig.puppet.parameters.none { it.id.raw == "LegacyAxis" })
        assertEquals(0.4f, deleted.model.rig.puppet.drawables.first().opacity, 1e-6f)
        assertEquals(legacy.rigEdits.parameterEdits, deleted.document.rigEdits.parameterEdits)
        assertEquals(revision, WorkspaceRevisions.of(legacy))
        assertEquals(before.historyHead, runtime.history().selections.first().node.id)
        assertEquals(revision, runtime.history().selections.first().node.revisionId)
    }

    @Test fun identicalParameterAndImplicitMeshDefaultsDoNotCreateOverridesOrHistory() = runBlocking {
        val runtime = runtime()
        val commands = WorkspaceDocumentCommands(runtime)
        val root = runtime.capture()
        val before = commands.execute(root.projectId, root.state, "Axis", listOf(parameter("Axis")), MutationAuthor.USER).capture
        val layer = before.model.analysis.layers.first()
        val default = before.document.config().defaultMeshSettings(layer.semantic.tag)
        val result = commands.execute(before.projectId, before.state, "Unchanged", listOf(
            operation("parameter_update", buildJsonObject { put("parameter_id", "Axis"); put("name", "Axis"); put("min", -1); put("max", 1); put("default", 0) }),
            operation("layer_mesh_update", buildJsonObject { put("layer_id", layer.source.id.raw); putJsonObject("changes") { put("outerMargin", default.outerMargin) } })), MutationAuthor.AGENT)
        assertFalse(result.applied)
        assertSame(before, result.capture)
        assertTrue(result.capture.document.meshOverrides.isEmpty())
        assertEquals(2, runtime.history().selections.size)
    }

    @Test fun indexedRebuildFailureAndCancellationDiscardEarlierPreparedRigChanges() = runBlocking {
        val builder = WorkspacePreviewBuilder()
        val entered = CompletableDeferred<Unit>()
        var cancelBuild = false
        val runtime = runtime { doc ->
            if (doc.settings["headStrength"]?.jsonPrimitive?.float == 3f) {
                if (cancelBuild) { entered.complete(Unit); awaitCancellation() }
                else error("Candidate rebuild rejected")
            }
            builder.build(doc)
        }
        val before = runtime.capture()
        val commands = WorkspaceDocumentCommands(runtime)
        val edits = listOf(parameter("PreparedAxis"), setting(3))
        val failure = assertFailsWith<WorkspaceBatchEditException> { commands.execute(before.projectId, before.state, "Rejected", edits, MutationAuthor.AGENT) }
        assertEquals(1, failure.index)
        assertEquals("settings_update", failure.editOperation)
        assertSame(before, runtime.capture())
        cancelBuild = true
        val pending = launch { commands.execute(before.projectId, before.state, "Cancel", edits, MutationAuthor.AGENT) }
        entered.await()
        pending.cancelAndJoin()
        assertSame(before, runtime.capture())
        assertEquals(1, runtime.history().selections.size)
    }

    @Test fun completedPreparationCannotOverwriteAConcurrentDurableEdit() = runBlocking {
        val builder = WorkspacePreviewBuilder()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val runtime = runtime { doc ->
            if (doc.settings["headStrength"]?.jsonPrimitive?.float == 2f) { entered.complete(Unit); release.await() }
            builder.build(doc)
        }
        val before = runtime.capture()
        val commands = WorkspaceDocumentCommands(runtime)
        val slow = async { runCatching { commands.execute(before.projectId, before.state, "Slow", listOf(setting(2)), MutationAuthor.AGENT) } }
        entered.await()
        val winner = commands.execute(before.projectId, before.state, "User edit", listOf(parameter("UserAxis")), MutationAuthor.USER)
        release.complete(Unit)
        assertIs<WorkspaceConflict>(slow.await().exceptionOrNull())
        assertSame(winner.capture, runtime.capture())
        assertEquals(2, runtime.history().selections.size)
        assertEquals("user", runtime.history().selections.last().node.actor)
        assertFalse(runtime.capture().model.rig.puppet.parameters.any { it.id.raw == "PreparedAxis" })
    }
}
