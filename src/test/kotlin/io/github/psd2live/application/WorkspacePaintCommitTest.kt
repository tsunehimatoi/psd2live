package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.core.modifier.*
import io.github.psd2live.core.sim.*
import io.github.psd2live.project.*
import io.github.psd2live.scenario.req
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import androidx.compose.ui.geometry.Offset
import io.github.psd2live.ui.WeightPaintMode
import io.github.psd2live.ui.state.DesktopWorkspace
import io.github.psd2live.ui.state.PSD2LiveViewModel
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import org.umamo.runtime.model.*
import java.nio.file.Path
import kotlin.test.*

class WorkspacePaintCommitTest {
    @TempDir lateinit var temp: Path
    private class Fixture(val builder: WorkspacePreviewBuilder, val runtime: WorkspaceRuntime<RigPreviewModel>) {
        val now get() = runtime.capture()
        suspend fun paint(request: JsonObject) = now.let { WorkspaceDocumentCommands(runtime).execute(it.projectId, it.state,
            "Paint weights", listOf(WorkspaceDocumentOperation("vertex_group_paint", request)), MutationAuthor.USER) }
    }

    private suspend fun fixture(legacy: Boolean = false, automatic: Boolean = false,
                                gate: suspend () -> Unit = {}): Fixture {
        val source = SyntheticProject(temp.resolve("source-${legacy}"), 2, 12, 1).tree.head().snapshot
        val config = PipelineConfig(atlasSize = 64, meshSpacing = 8, generateDeformers = false, generatePhysics = false,
            rigEdits = RigEditOverlay(parameterEdits = listOf(RigParameterEdit("Input", "Input", -1f, 1f, 0f, created = true)),
                modifierStack = if (legacy) null else RigModifierStack().toJson()))
        var doc = source.copy(settings = WorkspaceSettingsCodec.encode(config), rigEdits = config.rigEdits)
        val builder = WorkspacePreviewBuilder()
        var model = builder.build(doc)
        val targets = model.rig.puppet.drawables.map { ModifierTarget("mesh", it.id.raw) }
        val weights = model.rig.puppet.drawables.flatMap { mesh -> listOf(VertexGroupKind.PIN, VertexGroupKind.MASS).map { kind ->
            VertexGroupJournal.encode(VertexGroup(kind.jsonName, mesh.id, kind,
                FloatArray(mesh.mesh!!.vertexCount) { (it % 4) * .2f }))
        } }
        if (!automatic) doc = doc.copy(rigEdits = doc.rigEdits.copy(authoringJournal = weights))
        model = builder.build(doc)
        if (!legacy) {
            val sim = RigSimEdit("cloth", "Cloth", SimKind.CLOTH, targets.map { it.id }, inputs = listOf(PhysicsInput("Input")))
            val counts = model.rig.puppet.drawables.associate { it.id.raw to it.mesh!!.vertexCount }
            val weighted = weights.fold(model.rig.puppet, VertexGroupJournal::apply)
            val axis = SimBakedAxis("Mode", floatArrayOf(-1f, 0f, 1f), counts.mapValues { (_, n) ->
                listOf(FloatArray(n * 2) { -.1f }, FloatArray(n * 2), FloatArray(n * 2) { .1f }) })
            val baked = sim.copy(bake = SimBakeResult(SimBake.fingerprint(weighted, sim), counts, emptyList(), listOf(SimBakedMode(axis, 1f, 1f))))
            val config = if (automatic) JsonObject(baked.toJson() + ("automatic_weights" to JsonArray(weights))) else baked.toJson()
            val stack = RigModifierStack.fromJson(doc.rigEdits.modifierStack!!).add("simulation", 1, targets, config, id = "sim")
                .update("sim") { it.copy(inputFingerprint = RigModifierInput.simulationFingerprint(model.rig.modifierOutput!!, baked)) }
            doc = doc.copy(rigEdits = doc.rigEdits.copy(modifierStack = stack.toJson()))
            model = builder.build(doc)
            assertTrue(model.modifierEvaluation!!.entries.all { it.status == ModifierStatus.ACTIVE })
        }
        val runtime = WorkspaceRuntime<RigPreviewModel>({ builder.build(it) }, rebuildFrom = { document, previous ->
            gate(); builder.build(document, previous)
        })
        runtime.install(runtime.state.value.state, "weights", doc, model)
        return Fixture(builder, runtime)
    }

    private fun request(model: PuppetModel, points: List<CanvasBrushPoint>, kind: String = "pin", mode: String = "set",
                        radius: Float = 5f, shape: String = "circle", connected: Boolean = false): JsonObject {
        return req("edit" to mapOf("action" to "brush", "targets" to model.drawables.map { it.id.raw }, "kind" to kind,
            "mode" to mode, "strength" to .85f, "radius" to radius, "hardness" to 0f, "shape" to shape,
            "connected_only" to connected, "points" to points.map { listOf(it.x, it.y) }))
    }

    @Test fun paintingWithAutoBakeOffCommitsWeightsWithoutRunningTheSolver(): Unit = runBlocking {
        val f = fixture()
        val before = f.now
        val mesh = before.model.rig.puppet.drawables.first()
        val point = CanvasWeightAuthoring.surfaces(before.model.rig.puppet, listOf(mesh.id), emptyMap(), false).single().points.first()
        val stroke = request(before.model.rig.puppet, listOf(point))
        val observed = mutableListOf<String>()
        RigBuildProfile.observeOperations().use {
            RigBuildProfile.observeSuspending(RigBuildProfile.Operation("paint", before.revision, "paint", "ordinary stack", 2, 0),
                { stats, _ -> observed += stats.stages.keys
                    println("Paint commit: ${stats.elapsedMillis} ms, bake ${stats.stages["modifier simulation: bake"]}") }) { f.paint(stroke) }
        }
        val group = f.now.model.rig.puppet.vertexGroups.single { it.drawableId == mesh.id && it.kind == VertexGroupKind.PIN }
        assertEquals(.85f, group.weights.first(), .0001f)
        assertFalse("modifier simulation: bake" in observed, "A paint-only gesture invoked SimBaker")
    }

    @Test fun newPsdKeepsBakedMotionUntilTheArtistBakesPaintedWeights(): Unit = runBlocking {
        val builder = WorkspacePreviewBuilder()
        val runtime = WorkspaceRuntime<RigPreviewModel>({ builder.build(it) }, rebuildFrom = { doc, previous -> builder.build(doc, previous) })
        val path = io.github.psd2live.scenario.Characters.figurePsd(temp)
        val src = io.github.psd2live.scenario.Characters.figure(physics = true)
        val initial = WorkspaceSourceImporter(runtime).importPsd(path, null, runtime.state.value.state,
            initialConfig = src.config(), modifiers = WorkspaceInitialModifiers.MINIMAL).capture
        val target = initial.model.rig.puppet.drawables.maxBy { it.mesh?.vertexCount ?: 0 }
        val input = initial.model.rig.puppet.parameters.first { it.id.raw == "ParamAngleX" }
        val setup = RigSimEdit("paint-cloth", "Cloth", SimKind.CLOTH, listOf(target.id.raw),
            inputs = listOf(PhysicsInput(input.id.raw)))
        val imported = WorkspaceDocumentCommands(runtime).execute(initial.projectId, initial.state, "Create cloth simulation",
            listOf(WorkspaceDocumentOperation("simulation_put", setup.toJson())), MutationAuthor.USER).capture
        fun stack(doc: WorkspaceDocument) = RigModifierStack.fromJson(requireNotNull(doc.rigEdits.modifierStack))
        val sims = stack(imported.document).instances.filter { it.type == "simulation" && it.enabled && "bake" in it.config }
        assertTrue(sims.isNotEmpty(), "Import must produce a baked simulation")
        val sim = sims.first()
        val model = imported.model.rig.puppet
        val mesh = sim.targets.first().id
        val vertex = 0
        val pt = CanvasWeightAuthoring.surfaces(model, listOf(target.id), emptyMap(), false).single().points[vertex]
        val brush = req("edit" to mapOf("action" to "brush", "targets" to listOf(mesh), "kind" to "pin", "name" to "pin",
            "mode" to "set", "strength" to 1f, "radius" to 4f, "points" to listOf(listOf(pt.x, pt.y))))
        val f = Fixture(builder, runtime)
        val observed = mutableListOf<String>()
        RigBuildProfile.observeOperations().use {
            RigBuildProfile.observeSuspending(RigBuildProfile.Operation("paint", imported.revision, "paint", "fresh PSD", 1, 0),
                { stats, _ -> observed += stats.stages.keys
                    println("Fresh PSD commit: ${stats.elapsedMillis} ms, bake ${stats.stages["modifier simulation: bake"]}") }) { f.paint(brush) }
        }
        assertFalse("modifier simulation: bake" in observed)
        val painted = f.now
        fun verifyPaint(preview: RigPreviewModel) {
            assertEquals(1f, preview.rig.puppet.vertexGroups.single { it.name == "pin" && it.drawableId == target.id }.weights[vertex])
            assertEquals(imported.model.modifierEvaluation!!.entries.map { it.status },
                preview.modifierEvaluation!!.entries.map { it.status }, "Painting should preserve the last baked motion")
            assertEquals(RigIrCompiler.compile(imported.model).physics, RigIrCompiler.compile(preview).physics)
        }
        verifyPaint(painted.model)
        verifyPaint(builder.build(painted.document))
        assertEquals(sim.config, stack(painted.document).instances.single { it.id == sim.id }.config)
        assertTrue(SimulationCatalog.stale(painted.document.rigEdits, painted.model.rig.puppet,
            SimulationCatalog.entries(painted.document.rigEdits).single { it.id == sim.id }))
        f.runtime.checkout(painted.projectId, painted.state, imported.historyHead)
        assertEquals(imported.document, f.now.document)
        f.runtime.checkout(painted.projectId, f.now.state, painted.historyHead)
        verifyPaint(f.now.model)
        val ids = sims.map { it.id }
        WorkspaceDocumentCommands(runtime).execute(f.now.projectId, f.now.state, "Bake all", ids.map {
            WorkspaceDocumentOperation("simulation_bake", req("id" to it))
        }, MutationAuthor.USER)
        val baked = stack(f.now.document)
        assertNotEquals(sim.config["bake"], baked.instances.single { it.id == sim.id }.config["bake"])
        assertTrue(f.now.model.modifierEvaluation!!.entries.filter { it.instance.id in ids }.all { it.status == ModifierStatus.ACTIVE })
    }

    @Test fun autoBakeOnUpdatesTheStoredBakeInThePaintTransaction(): Unit = runBlocking {
        val f = fixture()
        val before = f.now
        val original = RigModifierStack.fromJson(before.document.rigEdits.modifierStack!!).instances.single().config.getValue("bake")
        val mesh = before.model.rig.puppet.drawables.first()
        val point = CanvasWeightAuthoring.surfaces(before.model.rig.puppet, listOf(mesh.id), emptyMap(), false).single().points.first()
        val paint = request(before.model.rig.puppet, listOf(point))
        f.paint(JsonObject(paint + ("auto_bake" to JsonPrimitive(true))))
        val updated = RigModifierStack.fromJson(f.now.document.rigEdits.modifierStack!!).instances.single()
        assertTrue(updated.enabled)
        assertNotEquals(original, updated.config.getValue("bake"))
        assertEquals(before.historyHead, f.runtime.history().selections.single { it.node.id == f.now.historyHead }.node.parentId)
    }

    @Test fun crossingBatchBoundaryKeepsTheSameAdditiveStrokeAndUnclippedEndpoints(): Unit = runBlocking {
        val f = fixture()
        val puppet = f.now.model.rig.puppet
        val mesh = puppet.drawables.first()
        val p = CanvasWeightAuthoring.surfaces(puppet, listOf(mesh.id), emptyMap(), false).single().points.minBy { it.x }
        val outside = CanvasBrushPoint(-5f, p.y)
        val across = CanvasBrushPoint(p.x + 8f, p.y)
        val whole = request(puppet, listOf(outside, across), mode = "add")
        val (field, batches) = CanvasWeightStroke.encode(List(4096) { outside } + across)
        val edit = whole.getValue("edit").jsonObject
        val chunked = buildJsonObject { put("edit", JsonObject(edit - "points" + (field to batches))) }
        fun recorded(request: JsonObject) = WorkspaceCanvasWeightEdits.commands(puppet, WorkspaceDocumentOperation("vertex_group_paint", request))
        assertEquals(recorded(whole), recorded(chunked))
        val segments = CanvasWeightStroke.segments(chunked.getValue("edit").jsonObject)
        assertEquals(outside to across, segments.last())
    }

    @Test fun paintingWeightsProducedByAnAppliedSimulationSurvivesRebuild(): Unit = runBlocking {
        val f = fixture(automatic = true)
        val start = f.now
        WorkspaceDocumentCommands(f.runtime).execute(start.projectId, start.state, "Apply simulation",
            listOf(WorkspaceDocumentOperation("modifier_apply", req("id" to "sim", "scope" to "selected"))), MutationAuthor.USER)
        val before = f.now
        val mesh = before.model.rig.puppet.drawables.first()
        val point = CanvasWeightAuthoring.surfaces(before.model.rig.puppet, listOf(mesh.id), emptyMap(), false).single().points.first()
        f.paint(request(before.model.rig.puppet, listOf(point)))
        val after = f.builder.build(f.now.document)
        assertEquals(.85f, after.rig.puppet.vertexGroups.single { it.drawableId == mesh.id && it.kind == VertexGroupKind.PIN }.weights.first(), .0001f)
    }

    @Test fun staleBackgroundStrokeCannotReplaceANewerProjectOrPreview(): Unit = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var held = false
        val f = fixture(gate = { if (held) { entered.complete(Unit); release.await() } })
        val original = f.now
        held = true
        val pending = async(Dispatchers.Default) { runCatching { f.paint(request(original.model.rig.puppet, listOf(CanvasBrushPoint(0f, 0f)))) } }
        withTimeout(10_000) { entered.await() }
        f.runtime.install(f.now.state, "new-project", original.document, original.model)
        release.complete(Unit)
        assertTrue(pending.await().isFailure)
        assertEquals("new-project", f.now.projectId)
        assertEquals(original.model.rig.puppet.vertexGroups, f.now.model.rig.puppet.vertexGroups)
    }

    @Test fun desktopLongGestureKeepsItsPreviewAfterAsyncCommitAndToolSwitch(): Unit = runBlocking {
        val f = fixture()
        val doc = f.now.document
        val project = SyntheticProject(temp.resolve("desktop-source"), 1, 8, 1)
        val revision = WorkspaceRevisions.of(doc)
        project.tree.commit(project.tree.head().node.id, doc, revision, revision, "Weight fixture", "user")
        project.store.persistHistory(SyntheticProject.PROJECT, project.tree.state())
        val path = temp.resolve("desktop.psd2live")
        ProjectRepository(writeHeadCache = false).save(project.capture(), path)
        PSD2LiveViewModel().use { vm -> DesktopWorkspace(vm, temp.resolve("desktop-store")).use { workspace ->
            vm.attachWorkspace(workspace)
            workspace.open(path)
            withTimeout(20_000) { vm.state.first { it.previewModel != null && !it.isAnalyzing && !it.isGenerating } }
            val mesh = vm.state.value.previewModel!!.rig.puppet.drawables.first()
            val beforeMass = vm.state.value.previewModel!!.rig.puppet.vertexGroups.single { it.drawableId == mesh.id && it.kind == VertexGroupKind.MASS }
            val nodes = workspace.history().nodes.size
            var preview = floatArrayOf()
            withContext(Dispatchers.Main) {
                vm.setSimulationAutoBake(false)
                vm.beginVertexGroupPaint(mesh.id.raw, VertexGroupKind.PIN)
                val editor = vm.canvasEditor
                editor.weightPaintMode = WeightPaintMode.SET
                editor.strength = 1f; editor.radius = 5f
                val viewport = CanvasViewport(10.0, 100.0, 100.0, 12f, 12f)
                val point = CanvasWeightAuthoring.surfaces(editor.model, listOf(mesh.id), emptyMap(), false).single().points.first()
                val start = Offset(viewport.x(point.x).toFloat(), viewport.yFromWorld(-point.y).toFloat())
                assertTrue(editor.press(start, viewport, shift = false, alt = false))
                repeat(5000) { i -> editor.move(start + Offset(kotlin.math.sin(i / 5f) * 8f, kotlin.math.cos(i / 5f) * 8f), viewport, shift = false) }
                preview = assertNotNull(editor.paintedWeights(mesh.id.raw)).copyOf()
                editor.release()
                assertNull(editor.error)
                assertNotNull(editor.displayPuppet, "Keep the painted preview while the transaction is pending")
            }
            withTimeout(20_000) { vm.state.first { !it.canvasEditBusy && !it.workspaceEditBusy && !it.editorDraftBusy } }
            withContext(Dispatchers.Main) {
                val editor = vm.canvasEditor
                assertNull(editor.error)
                val committed = assertNotNull(editor.vertexGroupWeights(mesh.id.raw))
                preview.indices.forEach { assertEquals(preview[it], committed[it], .0001f) }
                editor.weightGroupKind = VertexGroupKind.MASS
                assertContentEquals(beforeMass.weights, editor.vertexGroupWeights(mesh.id.raw))
                editor.weightGroupKind = VertexGroupKind.PIN
                assertContentEquals(committed, editor.vertexGroupWeights(mesh.id.raw))
            }
            assertEquals(nodes + 1, workspace.history().nodes.size)
            val output = temp.resolve("desktop-painted.psd2live")
            vm.saveProjectNow(output)
            workspace.open(output)
            val after = workspace.captureQueries().currentPuppet()!!.vertexGroups.single { it.drawableId == mesh.id && it.kind == VertexGroupKind.PIN }
            preview.indices.forEach { assertEquals(preview[it], after.weights[it], .0001f) }
        } }
    }

}
