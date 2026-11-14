package io.github.psd2live.tools

import io.github.psd2live.application.*
import io.github.psd2live.core.*
import io.github.psd2live.format.compile.document.ContentHash
import io.github.psd2live.project.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.umamo.format.art.*
import org.umamo.format.psd.PsdWriter
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Headless source import -> document commands -> CAS/history -> reopen/export. No private assets. */
class SyntheticWorkspaceBaselineTool {
    @Test fun profile() = runBlocking {
        requireTools()
        val smoke = setting("PSD2LIVE_BASELINE_SMOKE", "0") == "1"
        val samples = if (smoke) 1 else setting("PSD2LIVE_BASELINE_SAMPLES", "30").toInt().also { require(it >= 30) }
        val edits = if (smoke) 3 else setting("PSD2LIVE_BASELINE_EDITS", "1000").toInt().also { require(it >= 1000) }
        val report = SyntheticBaselineReport(if (smoke) "synthetic-workspace-smoke" else "synthetic-workspace",
            "headless workspace commands and history; starts with empty stack then reorders two motion modifiers; no GUI presentation")
        report.metadata("acceptance_run", (!smoke).toString())
        report.metadata("history_policy", "Retain every committed edit and topology undo branch until close; persistent history growth is expected")
        report.metadata("retained_readers", "The close-reopen sample deliberately holds one immutable reader to verify validity; later samples release it. No forced GC leak claim")
        val directory = output("synthetic-workspace").toPath().toAbsolutePath()
        val wasRecording = RigBuildProfile.recording
        RigBuildProfile.recording = true
        try {
            for ((size, layers, edge) in listOf(Triple("small", 4, 64), Triple("medium", 16, 128), Triple("large", 64, 256))) {
                if (smoke && size != "small") continue
                report.context("$size/setup")
                val source = source(layers, edge)
                val psd = directory.resolve("$size.psd")
                Files.write(psd, PsdWriter.write(source))
                report.metadata("$size input", "$layers opaque raster layers, ${source.widthPx}x${source.heightPx}; actual meshes/vertices in each sample")
                report.metadata("$size source_sha256", io.github.psd2live.core.modifier.ModifierPluginStore.hash(psd))
                val builder = WorkspacePreviewBuilder()
                val runtime = WorkspaceRuntime<RigPreviewModel>({ builder.build(it) }, rebuildFrom = { doc, previous -> builder.build(doc, previous) })
                val commands = WorkspaceDocumentCommands(runtime)
                var activeArchive: OpenedProject? = null
                var ordinal = 0
                suspend fun measured(scenario: String, action: suspend () -> Unit) {
                    report.context("$size/$scenario/$ordinal")
                    val before = runtime.state.value.capture
                    val mesh = before?.model?.rig?.puppet?.drawables?.mapNotNull { it.mesh }.orEmpty()
                    val sample = RigBuildProfile.measureSuspending(RigBuildProfile.Operation("$size-${ordinal++}",
                        before?.revision ?: "unloaded", "candidate-$ordinal", "$size/$scenario", mesh.size,
                        mesh.sumOf { it.positions.size.toLong() / 2 })) {
                        action()
                        runtime.state.value.capture?.let { capture ->
                            val outputMeshes = capture.model.rig.puppet.drawables.mapNotNull { it.mesh }
                            RigBuildProfile.gauge("output meshes", outputMeshes.size.toLong())
                            RigBuildProfile.gauge("output vertices", outputMeshes.sumOf { it.positions.size.toLong() / 2 })
                            RigBuildProfile.gauge("history nodes", runtime.history().selections.size.toLong())
                            RigBuildProfile.gauge("build cache owner open", if (capture.model.modifierBuildOwner?.isClosed == false) 1 else 0)
                        }
                    }
                    report.append(sample)
                }
                suspend fun command(name: String, request: JsonObject) {
                    val before = runtime.capture()
                    commands.execute(before.projectId, before.state, name,
                        listOf(WorkspaceDocumentOperation(name, request)), MutationAuthor.USER)
                    check(runtime.capture().historyHead != before.historyHead) { "Benchmark command did not create an edit" }
                }
                try {
                    measured("cold-import") {
                        WorkspaceSourceImporter(runtime, build = { builder.build(it) }).importPsd(psd, null, runtime.state.value.state,
                            initialConfig = PipelineConfig(atlasSize = 2048, generatePhysics = false, exportMoc3 = false),
                            modifiers = WorkspaceInitialModifiers.EMPTY)
                    }
                    val target = runtime.capture().model.rig.puppet.drawables.first { it.mesh != null }.id.raw
                    repeat(5) { index -> measured("warmup") { command("rig_edit_structure", rename(target, "Warmup $index")) } }
                    repeat(samples) { index -> measured("single-field") { command("rig_edit_structure", rename(target, "Sample $index")) } }
                    // A real topology change, followed by history checkout to keep every sample's input comparable.
                    repeat(samples) {
                        val before = runtime.capture()
                        val mesh = before.model.rig.puppet.drawables.single { it.id.raw == target }.mesh!!
                        val triangle = mesh.indices.take(3)
                        val x = triangle.sumOf { mesh.positions[it * 2].toDouble() }.toFloat() / 3
                        val y = triangle.sumOf { mesh.positions[it * 2 + 1].toDouble() }.toFloat() / 3
                        measured("topology") { command("canvas_topology", buildJsonObject {
                            put("id", target); put("action", "add_points"); putJsonArray("vertices") {}
                            putJsonArray("anchors") { addJsonObject { put("x", x); put("y", y) } }
                        }) }
                        val edited = runtime.capture().historyHead
                        measured("undo") { runtime.capture().let { runtime.checkout(it.projectId, it.state, before.historyHead) } }
                        measured("redo") { runtime.capture().let { runtime.checkout(it.projectId, it.state, edited) } }
                        runtime.capture().let { runtime.checkout(it.projectId, it.state, before.historyHead) }
                    }
                    measured("stack-setup") {
                        command("parameter_create", buildJsonObject {
                            put("parameter_id", "ParamBenchmark"); put("name", "Benchmark")
                            put("min", -1); put("max", 1); put("default", 0)
                        })
                        for (id in listOf("motion-a", "motion-b")) command("modifier_add", buildJsonObject {
                            put("id", id); put("type", "motion"); put("version", 1)
                            put("config", MotionClips.toJson(MotionClip("template", id, curves = listOf(
                                MotionCurve("ParamBenchmark", listOf(MotionKey(0f, 0f), MotionKey(1f, .5f)))))))
                            putJsonArray("targets") { addJsonObject { put("kind", "mesh"); put("id", target) } }
                        })
                    }
                    repeat(samples) { index ->
                        measured("stack-reorder") {
                            command("modifier_move", buildJsonObject { put("id", "motion-b"); put("index", if (index % 2 == 0) 0 else 1) })
                        }
                        val captured = runtime.capture()
                        val cold = WorkspacePreviewBuilder().build(captured.document)
                        try { assertEquals(RigIrCompiler.compile(cold), RigIrCompiler.compile(captured.model), "Reordered stack must equal cold rebuild") }
                        finally { cold.modifierBuildOwner?.close() }
                    }
                    repeat(edits) { index -> measured("committed-edit") {
                        command("rig_edit_structure", rename(target, "Stress $index"))
                    } }
                    val expected = ContentHash.of(RigIrCompiler.compile(runtime.capture().model))
                    // Keep metadata, not the old selections/snapshots: close must be allowed to release their objects.
                    val expectedHead = runtime.history().headNodeId
                    val expectedNodes = runtime.history().selections.associate { it.node.id to it.node }
                    val expectedLeaves = expectedNodes.keys - expectedNodes.values.mapNotNull { it.parentId }.toSet()
                    val expectedAuxiliary = runtime.capture().auxiliary
                    assertTrue(expectedNodes.size >= edits + samples * 2 + 6)
                    val archive = directory.resolve("$size.psd2live")
                    val storage = WorkspaceStore(Files.createTempDirectory("synthetic-baseline-store-"))
                    try {
                        measured("save") {
                            val capture = runtime.capture()
                            ProjectRepository().save(ProjectSaveCapture(capture.projectId, runtime.history(),
                                capture.auxiliary, psd, storage, auxiliary = capture.auxiliary), archive)
                        }
                        repeat(5) {
                            measured("close-reopen") {
                                val captured = runtime.capture()
                                runtime.close(captured.state, discardUnsaved = true)
                                assertEquals(expected, ContentHash.of(RigIrCompiler.compile(captured.model)),
                                    "Releasing caches changed an active immutable reader")
                                captured.model.modifierBuildOwner?.let { owner ->
                                    assertTrue(owner.isClosed && !owner.retainsMeshCache)
                                }
                                activeArchive?.close()
                                activeArchive = null
                                val opened = ProjectRepository().open(archive)
                                try {
                                    val document = opened.history.head().snapshot
                                    val model = builder.build(document)
                                    assertEquals(expected, ContentHash.of(RigIrCompiler.compile(model)))
                                    assertEquals(captured.projectId, opened.projectId)
                                    runtime.install(runtime.state.value.state, opened.projectId, document, model,
                                        history = opened.history.state(), auxiliary = opened.presentation)
                                    val restored = runtime.history()
                                    assertEquals(expectedNodes.size, restored.selections.size)
                                    assertEquals(expectedHead, restored.headNodeId)
                                    val restoredNodes = restored.selections.associate { it.node.id to it.node }
                                    assertEquals(expectedNodes, restoredNodes, "History metadata and branch identities must survive reopen")
                                    assertEquals(expectedLeaves, restoredNodes.keys - restoredNodes.values.mapNotNull { it.parentId }.toSet())
                                    expectedAuxiliary.forEach { (key, value) -> assertEquals(value, runtime.capture().auxiliary[key]) }
                                    // Keep the extraction alive while its lazy historical selections are installed.
                                    activeArchive = opened
                                } catch (failure: Throwable) {
                                    opened.close()
                                    throw failure
                                }
                            }
                        }
                        measured("cmo3-export") {
                            PSD2LivePipeline().export(runtime.capture().model, size, directory.resolve("$size-export"))
                        }
                    } finally { io.github.psd2live.core.modifier.ModifierPluginStore.deleteTree(storage.root) }
                } finally {
                    runtime.state.value.capture?.let { runtime.close(it.state, discardUnsaved = true) }
                    activeArchive?.close()
                }
            }
            report.complete()
        } catch (failure: Throwable) {
            report.failed(failure)
            throw failure
        } finally { RigBuildProfile.recording = wasRecording; report.close() }
    }

    private fun rename(target: String, name: String) = buildJsonObject {
        putJsonArray("edits") { addJsonObject { put("kind", "mesh"); put("action", "rename"); put("id", target); put("name", name) } }
    }

    private fun source(count: Int, edge: Int): WorkspaceSourceArt {
        val columns = kotlin.math.ceil(kotlin.math.sqrt(count.toDouble())).toInt()
        return WorkspaceSourceArt(columns * edge, columns * edge, List(count) { index ->
            val pixels = ByteArray(edge * edge * 4) { channel -> when (channel % 4) {
                0 -> (70 + index % 100).toByte(); 1 -> 110; 2 -> 140.toByte(); else -> -1
            } }
            WorkspaceSourceLayer(LayerId("object_$index"), "object_$index", "", SourceLayerKind.Raster, true, index,
                LayerBounds(index % columns * edge, index / columns * edge, edge, edge), 1f,
                false, LayerBlend.Normal, ChannelMask.ALL, LayerRaster(edge, edge, pixels), null, null, false)
        }, emptyList())
    }
}
