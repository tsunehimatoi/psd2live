package io.github.psd2live.application

import io.github.psd2live.algorithms.binding.BindingProjection
import io.github.psd2live.algorithms.binding.EyeFittedSourceAlgorithm
import io.github.psd2live.core.*
import io.github.psd2live.core.modifier.*
import io.github.psd2live.core.sim.*
import io.github.psd2live.format.compile.ExportOptions
import io.github.psd2live.project.*
import io.github.psd2live.history.WorkspaceHistoryTree
import io.github.psd2live.targets.cubism.Cmo3Target
import io.github.psd2live.bridge.PuppetIr
import io.github.psd2live.format.model.Deformer as IrDeformer
import io.github.psd2live.format.model.Floats
import io.github.psd2live.format.model.KeyGrid
import io.github.psd2live.format.model.LatticePoints
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import org.umamo.format.cmo3.Cmo3
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class WorkspaceModifierCommandsTest {
    @TempDir lateinit var temp: Path

    private class Fixture(val runtime: WorkspaceRuntime<RigPreviewModel>, val builder: WorkspacePreviewBuilder, val target: ModifierTarget) {
        val commands = WorkspaceDocumentCommands(runtime)
        val now get() = runtime.capture()
        suspend fun edit(operation: String, request: JsonObject): WorkspaceCommit<RigPreviewModel> = now.let {
            commands.execute(it.projectId, it.state, operation, listOf(WorkspaceDocumentOperation(operation, request)), MutationAuthor.USER)
        }
    }

    @Test fun initializePresetsOnEmptyArtworkIsAtomicAndCannotBeRepeated(): Unit = runBlocking {
        val f = fixture()
        val before = f.now.document
        val recording = RigBuildProfile.recording
        val generation = try {
            RigBuildProfile.recording = true
            RigBuildProfile.measureSuspending(RigBuildProfile.Operation("initialize", "empty", "preset", "minimal", 1, 0)) {
                f.edit("modifier_initialize", buildJsonObject { put("modifiers", "minimal") })
            }
        } finally { RigBuildProfile.recording = recording }
        assertTrue("source preset: binding preview" in generation.stages)
        assertFalse("source preset: final preview" in generation.stages, "The document commit owns the final preview")
        assertTrue((generation.stages["modifier cache hits"]?.second ?: 0) > 0, "The commit must reuse the prepared prefix")
        val initialized = f.now.document
        val stack = RigModifierStack.fromJson(requireNotNull(initialized.rigEdits.modifierStack))
        assertTrue(stack.instances.isNotEmpty())
        assertEquals(before.source, initialized.source)
        assertEquals(before.layerOverrides, initialized.layerOverrides)
        assertEquals(before.rigEdits.parameterEdits, initialized.rigEdits.parameterEdits)
        val state = f.now.state
        assertFailsWith<IllegalArgumentException> {
            f.edit("modifier_initialize", buildJsonObject { put("modifiers", "recommended") })
        }
        assertEquals(state, f.now.state)
        assertEquals(initialized, f.now.document)
    }

    @Test fun presetMotionEditsAndPlaybackUseTheOwningInstance(): Unit = runBlocking {
        val f = fixture()
        val clip = MotionClip("source", "Preset motion", duration = 1f, curves = listOf(MotionCurve("ParamInput", listOf(MotionKey(0f, 0f), MotionKey(1f, 1f)))))
        val stack = RigModifierStack().add("motion", 1, listOf(f.target), MotionClips.toJson(clip), id = "motion-owner")
        val doc = f.now.document.copy(rigEdits = f.now.document.rigEdits.copy(modifierStack = stack.toJson()))
        f.runtime.install(f.now.state, "motion", doc, f.builder.build(doc))
        val id = MotionCatalog.entries(f.now.document.rigEdits).single().id
        f.edit("motion_rename", buildJsonObject { put("id", id); put("name", "Renamed") })
        assertTrue(f.now.document.rigEdits.motionClips.isEmpty())
        assertEquals("Renamed", MotionCatalog.entries(f.now.document.rigEdits).single().name)
        val playback = WorkspacePlaybackSessions(f.runtime)
        val frame = playback.configure(f.now.projectId, f.now.state, "main", buildJsonObject {
            put("mode", "seek"); put("clip_id", id); put("time", 0.5f)
        })
        assertEquals(0.5f, frame.getValue("values").jsonObject.getValue("ParamInput").jsonPrimitive.float, 0.001f)
        f.edit("motion_duplicate", buildJsonObject { put("id", id); put("new_id", "motion-copy") })
        assertTrue(f.now.document.rigEdits.motionClips.isEmpty())
        val copied = MotionCatalog.entries(f.now.document.rigEdits).last()
        assertEquals("motion-copy", copied.id)
        assertEquals(MotionCatalog.entries(f.now.document.rigEdits).first().curves, copied.curves)
        f.edit("motion_delete", buildJsonObject { put("id", copied.id) })
        f.edit("motion_delete", buildJsonObject { put("id", id) })
        assertTrue(MotionCatalog.entries(f.now.document.rigEdits).isEmpty())
    }

    @Test fun newSimulationFromThePanelCreatesAModifier(): Unit = runBlocking {
        val f = fixture()
        val mesh = f.now.model.rig.puppet.drawables.single()
        val count = mesh.mesh!!.vertexCount
        val source = RigSimEdit("new-body", "Body", SimKind.CLOTH, listOf(mesh.id.raw))
        val axis = SimBakedAxis("Mode", floatArrayOf(-1f, 0f, 1f), mapOf(mesh.id.raw to listOf(FloatArray(count * 2) { -1f }, FloatArray(count * 2), FloatArray(count * 2) { 1f })))
        val edit = source.copy(bake = SimBakeResult(SimBake.fingerprint(f.now.model.rig.puppet, source), mapOf(mesh.id.raw to count), emptyList(), listOf(SimBakedMode(axis, 1f, 1f))))
        f.edit("simulation_put", edit.toJson())
        assertTrue(f.now.document.rigEdits.simEdits.isEmpty())
        assertNotNull(SimulationCatalog.entries(f.now.document.rigEdits).single().bake)
        assertEquals("new-body", RigModifierStack.fromJson(f.now.document.rigEdits.modifierStack!!).instances.single().id)
    }

    @Test fun appliedMotionRemainsEditableWithoutChangingItsFrozenConfiguration(): Unit = runBlocking {
        val f = fixture()
        f.edit("modifier_add", add(f, "motion", MotionClips.toJson(motion()), "motion-owner"))
        f.edit("modifier_apply", buildJsonObject { put("id", "motion-owner"); put("scope", "selected") })
        val configuration = stack(f).instances.single().config
        val clip = MotionCatalog.entries(f.now.document.rigEdits).single()
        f.edit("motion_rename", buildJsonObject { put("id", clip.id); put("name", "Edited applied motion") })
        assertEquals(configuration, stack(f).instances.single().config)
        assertEquals("Edited applied motion", RigIrCompiler.compile(f.now.model).clips.single().name)
        assertEquals(RigIrCompiler.compile(f.now.model), RigIrCompiler.compile(f.builder.build(f.now.document)))
        f.edit("modifier_apply", id("motion-owner"))
        assertTrue(stack(f).appliedNodes.isEmpty())
        assertEquals("Edited applied motion", f.now.document.rigEdits.motionClips.single().name)
        assertEquals(RigIrCompiler.compile(f.now.model), RigIrCompiler.compile(f.builder.build(f.now.document)))
    }

    @Test fun arbitraryAppliedMotionSurvivesPrefixApplyDiskAndOrdinaryEditing(): Unit = runBlocking {
        val f = fixture()
        val input = RigModifierPipeline.input(f.now.model)
        val clip = io.github.psd2live.format.model.Clip("custom", "Custom", "Group", "custom", 2f, 30f, false,
            fadeOut = .7f, curves = listOf(io.github.psd2live.format.model.Curve("ParamInput", .2f, 0f,
                listOf(io.github.psd2live.format.model.CurveSegment.Linear(2f, 1f)), .3f, .4f)),
            events = listOf(io.github.psd2live.format.model.ClipEvent(.5f, "event")),
            targetCurves = listOf(io.github.psd2live.format.model.TargetCurve(
                io.github.psd2live.format.model.CurveTarget.ModelOpacity, 0f, 1f,
                listOf(io.github.psd2live.format.model.CurveSegment.Linear(2f, .5f)))))
        val definition = RigModifierDefinition("custom.motion", 1, plugin = ModifierAlgorithmPlugin { rig, _, _ ->
            rig.copy(clips = rig.clips + clip)
        })
        val live = RigModifierStack().add("custom.motion", 1, listOf(f.target), id = "custom-owner")
        val applied = RigModifierEvaluator(RigModifierRegistry(listOf(definition))).applySelected(input, live, "custom-owner")
        val document = f.now.document.copy(rigEdits = f.now.document.rigEdits.copy(modifierStack = applied.toJson()))
        f.runtime.install(f.now.state, "custom motion", document, f.builder.build(document))
        assertEquals(listOf(clip), RigIrCompiler.compile(f.now.model).clips)
        suspend fun verifyDuplicate() {
            f.edit("motion_duplicate", buildJsonObject { put("id", clip.id); put("new_id", "copy") })
            val copied = RigIrCompiler.compile(f.now.model).clips.single { it.id == "copy" }
            assertEquals(clip.curves, copied.curves)
            assertEquals(clip.targetCurves, copied.targetCurves)
            assertEquals(clip.events, copied.events)
            assertEquals(clip.fadeIn, copied.fadeIn)
            assertEquals(clip.fadeOut, copied.fadeOut)
            f.edit("motion_delete", id("copy"))
        }
        verifyDuplicate()
        f.edit("modifier_apply", id("custom-owner"))
        verifyDuplicate()
        assertEquals(listOf(clip), RigIrCompiler.compile(f.now.model).clips)
        assertTrue(stack(f).instances.isEmpty())
        assertEquals(setOf(clip.id), f.now.document.rigEdits.motionSources.keys)
        val revision = WorkspaceRevisions.of(f.now.document)
        val store = WorkspaceStore(temp.resolve("custom-motion-project"))
        store.persistHistory("project", WorkspaceHistoryTree(f.now.document, revision, revision).state())
        val reopened = WorkspaceStore(store.root).loadHistory("project")!!.head().snapshot
        assertEquals(listOf(clip), RigIrCompiler.compile(f.builder.build(reopened)).clips)
        f.runtime.install(f.now.state, "reopened", reopened, f.builder.build(reopened), discardUnsaved = true)
        f.edit("motion_rename", buildJsonObject { put("id", clip.id); put("name", "Renamed") })
        assertEquals(listOf(clip.copy(name = "Renamed")), RigIrCompiler.compile(f.now.model).clips)
        f.edit("motion_delete", id(clip.id))
        assertTrue(RigIrCompiler.compile(f.now.model).clips.isEmpty())
        assertTrue(f.now.document.rigEdits.motionSources.isEmpty())
    }

    @Test fun appliedMotionRejectsParametersOnlyAvailableDownstream(): Unit = runBlocking {
        val f = fixture(withWarp = true)
        f.edit("modifier_add", add(f, "motion", MotionClips.toJson(motion()), "motion-owner"))
        f.edit("modifier_apply", buildJsonObject { put("id", "motion-owner"); put("scope", "selected") })
        val swing = RigSwingEdit.single("template", "Swing", SwingKind.LATERAL, listOf("warp"), listOf("P"))
        f.edit("modifier_add", add(f, "swing", swing.toJson(), "later", ModifierTarget("deformer", "warp")))
        val parameter = stack(f).instances.last().outputId("swing:lateral:0")
        val clip = MotionCatalog.entries(f.now.document.rigEdits).single()
        val before = f.now
        for (enabled in listOf(true, false)) {
            val changed = clip.copy(enabled = enabled,
                curves = listOf(MotionCurve(parameter, listOf(MotionKey(0f, .25f)))))
            val failure = assertFailsWith<IllegalArgumentException> {
                MotionCatalog.replacing(before.document.rigEdits, listOf(changed))
            }
            assertTrue(failure.message.orEmpty().contains("stack position"))
        }
        assertEquals(before, f.now)
        val valid = clip.copy(curves = listOf(MotionCurve("ParamInput", listOf(MotionKey(0f, .25f)))))
        f.edit("motion_put", buildJsonObject { put("clip", JsonObject(MotionClips.toJson(valid) - "file")) })
        assertEquals(valid.curves, MotionCatalog.entries(f.now.document.rigEdits).single().curves)
        assertEquals(RigIrCompiler.compile(f.now.model), RigIrCompiler.compile(f.builder.build(f.now.document)))
    }

    @Test fun ordinaryCandidateKeepsIndependentOutputsWhenOneModifierFails(): Unit = runBlocking {
        val f = fixture()
        f.edit("modifier_add", add(f, "motion", MotionClips.toJson(motion()), "motion-owner"))
        f.edit("modifier_add", add(f, "physics", physics().toJson(), "physics-owner"))
        val before = f.now
        val broken = motion().copy(curves = listOf(MotionCurve("MissingParameter", listOf(MotionKey(0f, 0f)))))
        val candidate = before.document.copy(rigEdits = before.document.rigEdits.copy(
            modifierStack = stack(f).update("motion-owner") { it.copy(config = MotionClips.toJson(broken)) }.toJson()))
        f.commands.executeCandidate(before.projectId, before.state, "Ordinary edit", MutationAuthor.USER,
            mutation = { _, _ -> candidate })
        val after = f.now
        assertEquals(listOf(ModifierStatus.FAILED, ModifierStatus.ACTIVE), after.model.modifierEvaluation!!.entries.map { it.status })
        assertTrue(after.model.modifierEvaluation!!.entries.first().error.orEmpty().contains("Motion parameters are missing"))
        assertEquals(RigIrCompiler.compile(before.model).physics, RigIrCompiler.compile(after.model).physics)
        assertNotEquals(before.historyHead, after.historyHead)
        assertEquals(RigIrCompiler.compile(after.model), RigIrCompiler.compile(f.builder.build(after.document)))
        f.runtime.checkout(after.projectId, after.state, before.historyHead)
        assertEquals(RigIrCompiler.compile(before.model), RigIrCompiler.compile(f.now.model))
        f.runtime.checkout(after.projectId, f.now.state, after.historyHead)
        val store = WorkspaceStore(temp.resolve("isolated-modifier"))
        store.persistHistory("isolated", f.runtime.history())
        val reopened = WorkspaceStore(store.root).loadHistory("isolated")!!.head().snapshot
        assertEquals(RigIrCompiler.compile(after.model), RigIrCompiler.compile(f.builder.build(reopened)))
        f.edit("modifier_update", buildJsonObject {
            put("id", "motion-owner"); put("type", "motion"); put("config", MotionClips.toJson(motion()))
        })
        assertTrue(f.now.model.modifierEvaluation!!.entries.all { it.status == ModifierStatus.ACTIVE })
    }

    @Test fun batchKeepsFailedPluginAndIndependentPluginInOneHistoryStep(): Unit = runBlocking {
        val f = fixture()
        val before = f.now
        val broken = motion().copy(curves = listOf(MotionCurve("MissingParameter", listOf(MotionKey(0f, 0f)))))
        f.commands.execute(before.projectId, before.state, "Add independent plugins", listOf(
            WorkspaceDocumentOperation("modifier_add", add(f, "motion", MotionClips.toJson(broken), "broken")),
            WorkspaceDocumentOperation("modifier_add", add(f, "physics", physics().toJson(), "good")),
        ), MutationAuthor.USER)
        assertEquals(listOf(ModifierStatus.FAILED, ModifierStatus.ACTIVE), f.now.model.modifierEvaluation!!.entries.map { it.status })
        assertEquals(1, RigIrCompiler.compile(f.now.model).physics.groups.size)
        assertEquals(before.historyHead, f.runtime.history().selections.single { it.node.id == f.now.historyHead }.node.parentId)
        f.edit("modifier_enable", buildJsonObject { put("id", "broken"); put("enabled", true) })
        assertEquals(1L, stack(f).retryRevisions["broken"])
        assertEquals(ModifierStatus.ACTIVE, f.now.model.modifierEvaluation!!.entries.last().status)
    }

    @Test fun physicsImportEditsAppliedOutputAndDisablesConflictingAppliedGroups(): Unit = runBlocking {
        val f = fixture()
        f.edit("modifier_add", add(f, "physics", physics().toJson(), "physics-owner"))
        f.edit("modifier_apply", buildJsonObject { put("id", "physics-owner"); put("scope", "selected") })
        val group = WorkspaceDocumentEdits.physicsCatalog(f.now.document, f.now.model).single()
        suspend fun import(setting: RigPhysicsEdit) {
            val text = assertNotNull(Physics3Json.write(listOf(setting), 30))
            val current = f.now
            f.commands.executeCandidate(current.projectId, current.state, "Import physics", MutationAuthor.USER,
                mutation = { doc, model -> WorkspacePhysicsEdits.import(doc, model, text).document })
        }
        import(group.setting.copy(name = "Imported applied group"))
        assertTrue(f.now.document.rigEdits.physicsEdits.isEmpty())
        assertEquals("Imported applied group", RigIrCompiler.compile(f.now.model).physics.groups.single().name)
        import(group.setting.copy(id = "replacement", name = "Replacement"))
        assertEquals(listOf("replacement"), RigIrCompiler.compile(f.now.model).physics.groups.map { it.id })
        val catalog = WorkspaceDocumentEdits.physicsCatalog(f.now.document, f.now.model)
        assertFalse(catalog.single { it.id == group.id }.active)
        assertEquals(RigIrCompiler.compile(f.now.model), RigIrCompiler.compile(f.builder.build(f.now.document)))
    }

    @Test fun mergedMeshKeepsPaintedWeightsAndRebakesWithTheNewVertexCount(): Unit = runBlocking {
        assertBakingPaintOnMergedMesh(autoBake = false)
    }

    @Test fun paintingMergedMeshWithAutoBakeRebakesWithTheNewVertexCount(): Unit = runBlocking {
        assertBakingPaintOnMergedMesh(autoBake = true)
    }

    private suspend fun assertBakingPaintOnMergedMesh(autoBake: Boolean) {
        val f = fixture()
        val initial = f.now.model.rig.puppet
        val target = initial.drawables.single()
        val mesh = requireNotNull(target.mesh)
        val group = org.umamo.runtime.model.VertexGroup("Pin", target.id, org.umamo.runtime.model.VertexGroupKind.PIN,
            FloatArray(mesh.vertexCount) { (it % 4) * 0.2f })
        val source = RigSimEdit("template", "Cloth", SimKind.CLOTH, listOf(target.id.raw), inputs = listOf(PhysicsInput("ParamInput")))
        val config = JsonObject(source.toJson() + ("automatic_weights" to JsonArray(listOf(VertexGroupJournal.encode(group)))))
        f.edit("modifier_add", add(f, "simulation", config, "cloth"))
        val before = f.now
        val oldBake = assertNotNull(RigSimEdit.fromJson(stack(f).instances.single().config).bake)
        val edge = org.umamo.edit.MeshTopology.uniqueEdges(mesh.indices).first()
        val merge = buildJsonObject {
            put("op", "canvas_topology"); put("action", "merge"); put("id", target.id.raw)
            putJsonArray("vertices") { add(edge.endpointLow); add(edge.endpointHigh) }
        }
        val expected = RigAuthoringJournal.apply(before.model.rig.puppet, merge)
        f.commands.executeJournal(before.projectId, before.state, "Merge cloth vertices", JsonArray(listOf(merge)), MutationAuthor.USER)
        assertEquals(expected.vertexGroups, f.now.model.rig.puppet.vertexGroups)
        assertEquals(oldBake, RigSimEdit.fromJson(stack(f).instances.single { it.id == "cloth" }.config).bake)
        val count = mesh.vertexCount - 1
        val surface = CanvasWeightAuthoring.surfaces(f.now.model.rig.puppet, listOf(target.id), emptyMap(), false).single()
        val point = surface.points.first()
        f.edit("vertex_group_paint", buildJsonObject {
            put("auto_bake", autoBake)
            putJsonObject("edit") {
                put("action", "brush"); put("mode", "set"); put("kind", "pin"); put("name", group.name)
                putJsonArray("targets") { add(target.id.raw) }; put("strength", 1f); put("radius", 5)
                putJsonArray("points") { addJsonArray { add(point.x); add(point.y) } }
            }
        })
        val weights = f.now.model.rig.puppet.vertexGroups.single { it.name == group.name }.weights
        assertEquals(count, weights.size)
        assertEquals(1f, weights.first())
        if (!autoBake) {
            assertEquals(oldBake, RigSimEdit.fromJson(stack(f).instances.single { it.id == "cloth" }.config).bake)
            assertContentEquals(weights, f.builder.build(f.now.document).rig.puppet.vertexGroups.single { it.name == group.name }.weights)
            f.edit("simulation_bake", id("cloth"))
        }
        val simulation = stack(f).instances.single { it.id == "cloth" }
        val baked = assertNotNull(RigSimEdit.fromJson(simulation.config).bake)
        assertEquals(count, baked.vertexCounts.getValue(target.id.raw))
        val fallback = simulation.config.getValue("automatic_weights").jsonArray.single().jsonObject.getValue("weights").jsonArray
        assertContentEquals(expected.vertexGroups.single { it.name == group.name }.weights, fallback.map { it.jsonPrimitive.float }.toFloatArray())
        assertEquals(ModifierStatus.ACTIVE, f.now.model.modifierEvaluation!!.entries.single { it.instance.id == "cloth" }.status)
        assertContentEquals(weights, f.builder.build(f.now.document).rig.puppet.vertexGroups.single { it.name == group.name }.weights)
    }

    @Test fun explicitSimulationRebakeRunsTheBakerAndClearingDiscardsTheStoredBake(): Unit = runBlocking {
        val f = fixture()
        val source = RigSimEdit("template", "Cloth", SimKind.CLOTH, listOf(f.target.id),
            inputs = listOf(PhysicsInput("ParamInput")), autoBake = false)
        val count = f.now.model.rig.puppet.drawables.single().mesh!!.vertexCount
        val axis = SimBakedAxis("Mode", floatArrayOf(-1f, 0f, 1f), mapOf(f.target.id to listOf(
            FloatArray(count * 2) { -1f }, FloatArray(count * 2), FloatArray(count * 2) { 1f })))
        val bake = SimBakeResult(SimBake.fingerprint(f.now.model.rig.puppet, source), mapOf(f.target.id to count),
            emptyList(), listOf(SimBakedMode(axis, 1f, 1f)))
        val weights = JsonArray(emptyList())
        f.edit("modifier_add", add(f, "simulation", JsonObject(source.copy(bake = bake).toJson() + ("automatic_weights" to weights)), "cloth"))
        val before = f.now
        var enteredBaker = false
        val work = object : WorkspaceSimulationWork {
            override fun <T> run(id: String, action: ((Float) -> Unit, () -> Boolean) -> T): T = action({ progress ->
                if (progress > 0f) {
                    enteredBaker = true
                    throw java.util.concurrent.CancellationException("Stop explicit bake")
                }
            }, { false })
        }
        assertFailsWith<java.util.concurrent.CancellationException> {
            WorkspaceDocumentCommands(f.runtime, simulationWork = work).execute(before.projectId, before.state, "Rebake cloth",
                listOf(WorkspaceDocumentOperation("simulation_bake", id("cloth"))), MutationAuthor.USER)
        }
        assertTrue(enteredBaker)
        assertEquals(before, f.now)

        f.edit("simulation_clear_bake", id("cloth"))
        val cleared = stack(f).instances.single()
        assertFalse(cleared.enabled)
        assertNull(cleared.inputFingerprint)
        assertFalse("bake" in cleared.config)
        assertEquals(weights, cleared.config["automatic_weights"])
        assertNull(SimulationCatalog.entries(f.now.document.rigEdits).single().bake)
        val rebuilt = f.builder.build(f.now.document)
        assertEquals(RigIrCompiler.compile(f.now.model), RigIrCompiler.compile(rebuilt))
        assertFalse(rebuilt.rig.puppet.parameters.any { it.id.raw == cleared.outputId("simulation:Mode") })
    }

    @Test fun reopeningSwingAuditionEditsTheExistingModifier(): Unit = runBlocking {
        val f = fixture(withWarp = true)
        val source = RigSwingEdit.single("swing-owner", "Swing", SwingKind.LATERAL, listOf("warp"), listOf("SwingMode"))
        f.edit("swing_put", source.toJson())
        assertTrue(f.now.document.rigEdits.swingEdits.isEmpty())
        val sessions = WorkspaceSwingSessions(f.runtime)
        val draft = sessions.execute(f.now.projectId, f.now.state, "main", buildJsonObject {
            put("mode", "begin"); putJsonArray("targets") { add("warp") }
        })
        assertEquals("swing-owner", draft.existingId)
        assertTrue(draft.prepared.parameterIds.all { id -> draft.model.rig.puppet.parameters.any { it.id.raw == id } })
        sessions.execute(f.now.projectId, f.now.state, "main", buildJsonObject {
            put("mode", "commit"); put("session_id", draft.report.getValue("session_id"))
        })
        assertEquals(1, RigModifierStack.fromJson(f.now.document.rigEdits.modifierStack!!).instances.size)
        f.edit("swing_delete", buildJsonObject { put("id", "swing-owner") })
        assertTrue(SwingCatalog.entries(f.now.document.rigEdits).isEmpty())
    }

    @Test fun registeringAnotherSwingMeshUpdatesTheExistingInstanceAndReplays(): Unit = runBlocking {
        val f = fixture(layerCount = 2)
        val targets = f.now.model.rig.puppet.drawables.map { it.id.raw }
        val source = RigSwingEdit.single("accessory", "Accessory swing", SwingKind.LATERAL,
            targets.take(1), listOf("SwingMode"), preset = SwingPreset.ACCESSORY)
        f.edit("swing_put", source.toJson())
        val before = stack(f).instances.single()
        val wrapper = "Warp_Swing_${before.outputId("swing")}"
        overrideFarPoint(f, wrapper)
        val overridden = farPoints(f).copyOf()
        val overrides = stack(f).overrides.getValue(before.id)
        val sessions = WorkspaceSwingSessions(f.runtime)
        val draft = sessions.execute(f.now.projectId, f.now.state, "main", buildJsonObject {
            put("mode", "begin"); putJsonArray("targets") { add(targets.first()) }
        })
        val updated = sessions.execute(f.now.projectId, f.now.state, "main", buildJsonObject {
            put("mode", "update"); put("session_id", draft.report.getValue("session_id"))
            put("draft", draft.draft.copy(targets = targets).toJson())
        })
        assertEquals(before, stack(f).instances.single())
        sessions.execute(f.now.projectId, f.now.state, "main", buildJsonObject {
            put("mode", "commit"); put("session_id", updated.report.getValue("session_id"))
        })
        val after = stack(f).instances.single()
        assertEquals(before.copy(targets = targets.map { ModifierTarget("mesh", it) }, config = after.config), after)
        assertEquals(targets, RigSwingEdit.fromJson(after.config).targets)
        assertEquals(overrides, stack(f).overrides.getValue(after.id))
        assertContentEquals(overridden, farPoints(f))
        val expected = RigIrCompiler.compile(f.now.model)
        assertTrue(expected.meshes.all { it.parent == wrapper })
        assertEquals(expected, RigIrCompiler.compile(f.builder.build(f.now.document)))
        f.edit("modifier_apply", id(after.id))
        assertTrue(stack(f).instances.isEmpty())
        assertEquals(expected, RigIrCompiler.compile(f.builder.build(f.now.document)))
    }

    @Test fun nativeAdditionPlanCreatesAnotherInstanceInsteadOfReusingTheFirst(): Unit = runBlocking<Unit> {
        val f = fixture()
        val swing = RigSwingEdit.single("template", "Swing", SwingKind.LATERAL, listOf(f.target.id), listOf("P"))
        f.edit("modifier_add", add(f, "swing", swing.toJson(), "existing_swing"))
        val plan = WorkspaceModifierPlan.prepare(f.now, buildJsonObject {
            put("type", "swing")
            putJsonArray("targets") { addJsonObject { put("kind", f.target.kind); put("id", f.target.id) } }
        })
        val entries = plan.getValue("entries").jsonArray
        assertEquals(1, entries.size)
        assertNotEquals("existing_swing", entries.single().jsonObject.getValue("id").jsonPrimitive.content)
        f.edit("modifier_add_group", buildJsonObject { put("group", plan.getValue("group")); put("entries", entries) })
        assertEquals(2, stack(f).instances.count { it.type == "swing" })
    }

    @Test fun partiallyCoveredSourceAdditionOnlyPlansTheUncoveredTargets(): Unit = runBlocking<Unit> {
        val f = fixture(layerCount = 2)
        val targets = f.now.model.rig.puppet.drawables.map { ModifierTarget("mesh", it.id.raw) }
        fun request(selection: List<ModifierTarget>) = buildJsonObject {
            put("type", "body/legacy")
            putJsonArray("targets") { selection.forEach { target -> addJsonObject { put("kind", target.kind); put("id", target.id) } } }
        }
        val (first, _) = WorkspaceModifierPlan.plan(f.now.model, stack(f), request(targets.take(1)))
        val (_, plan) = WorkspaceModifierPlan.plan(f.now.model, first, request(targets))
        val added = plan.getValue("entries").jsonArray.map { it.jsonObject }
        assertTrue(added.isNotEmpty())
        assertTrue(added.all { entry -> entry.getValue("targets").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content } == targets.drop(1).map { it.id } })
    }

    @Test fun organizingPresetsPreservesEvaluationAndReplaysAsDocumentContent(): Unit = runBlocking {
        val f = fixture()
        val clip = MotionClip("source", "Motion", duration = 1f, curves = listOf(MotionCurve("ParamInput", listOf(MotionKey(0f, 0f)))))
        val stack = RigModifierStack().add("motion", 1, listOf(f.target), MotionClips.toJson(clip), id = "first")
            .add("motion", 1, listOf(f.target), MotionClips.toJson(clip.copy(id = "second")), id = "second")
        val doc = f.now.document.copy(rigEdits = f.now.document.rigEdits.copy(modifierStack = stack.toJson()))
        f.runtime.install(f.now.state, "motion", doc, f.builder.build(doc))
        val before = f.now
        val nodes = listOf(ModifierPanelNode("folder/custom", name = "Custom"),
            ModifierPanelNode("second", "folder/custom"), ModifierPanelNode("first", "folder/custom"))
        f.edit("modifier_organize", buildJsonObject { put("nodes", RigModifierOrganization.encode(nodes)) })
        val organized = f.now
        assertSame(before.model.rig, organized.model.rig, "Organization must not rebuild generated content")
        assertEquals(stack.instances, RigModifierStack.fromJson(organized.document.rigEdits.modifierStack!!).instances)
        assertEquals(nodes, RigModifierStack.fromJson(organized.document.rigEdits.modifierStack!!).organization)
        val cold = f.builder.build(organized.document)
        assertEquals(RigIrCompiler.compile(before.model), RigIrCompiler.compile(cold))
        f.runtime.checkout(f.now.projectId, f.now.state, before.historyHead)
        assertNull(RigModifierStack.fromJson(f.now.document.rigEdits.modifierStack!!).organization)
        f.runtime.checkout(f.now.projectId, f.now.state, organized.historyHead)
        assertEquals(nodes, RigModifierStack.fromJson(f.now.document.rigEdits.modifierStack!!).organization)
    }

    private suspend fun fixture(withWarp: Boolean = false, layerCount: Int = 1,
                                additionalParameters: List<RigParameterEdit> = emptyList(), physicsOrder: List<String> = emptyList(), warpDivisions: Int = 1): Fixture {
        val source = SyntheticProject(temp.resolve("source"), layerCount, 12, 1).tree.head().snapshot
        val config = PipelineConfig(atlasSize = 64, meshSpacing = 8, rigEdits = RigEditOverlay(
            physicsOrder = physicsOrder, parameterEdits = listOf(RigParameterEdit("ParamInput", "Input", -1f, 1f, 0f, created = true),
                RigParameterEdit("ParamOutput", "Output", -1f, 1f, 0f, created = true)) + additionalParameters, modifierStack = RigModifierStack().toJson()))
        var document = source.copy(settings = WorkspaceSettingsCodec.encode(config), rigEdits = config.rigEdits)
        val builder = WorkspacePreviewBuilder()
        val runtime = WorkspaceRuntime<RigPreviewModel>({ builder.build(it) }, rebuildFrom = { doc, previous -> builder.build(doc, previous) })
        var model = builder.build(document)
        if (withWarp) {
            val ir = RigIrCompiler.compile(model)
            val original = model.rig.puppet.drawables.single().mesh!!.positions
            val xs = original.indices.filter { it % 2 == 0 }.map { original[it] }
            val ys = original.indices.filter { it % 2 == 1 }.map { original[it] }
            val left = xs.min(); val top = ys.min(); val width = xs.max() - left; val height = ys.max() - top
            val lattice = FloatArray((warpDivisions + 1) * (warpDivisions + 1) * 2) { i ->
                if (i % 2 == 0) left + width * ((i / 2) % (warpDivisions + 1)) / warpDivisions
                else top + height * ((i / 2) / (warpDivisions + 1)) / warpDivisions
            }
            val warp = IrDeformer.Warp("warp", "Warp", null, null, warpDivisions, warpDivisions, true,
                KeyGrid.single(LatticePoints(Floats.of(lattice))))
            val mesh = ir.meshes.single()
            val geometry = mesh.geometry!!.copy(positions = Floats.of(FloatArray(original.size) { i ->
                if (i % 2 == 0) (original[i] - left) / width else (original[i] - top) / height
            }))
            val puppet = PuppetIr.toPuppet(ir.copy(deformers = listOf(warp), meshes = listOf(mesh.copy(parent = "warp", geometry = geometry))))
            val authored = model.authored.copy(rig = model.authored.rig.copy(puppet = puppet))
            val checkpoint = RigCheckpoint.encode(authored, model.sources.bindingKey!!)
            document = document.copy(rigEdits = document.rigEdits.copy(authoringJournal = listOf(checkpoint)))
            model = builder.build(document)
        }
        runtime.install(runtime.state.value.state, "project", document, model)
        return Fixture(runtime, builder, ModifierTarget("mesh", model.rig.puppet.drawables.first().id.raw))
    }

    private fun motion(name: String = "Sway") = MotionClip("template", name,
        curves = listOf(MotionCurve("ParamInput", listOf(MotionKey(0f, 0f, MotionInterpolation.LINEAR), MotionKey(1f, .5f)))))
    private fun physics() = RigPhysicsEdit("template", "Physics", listOf(PhysicsInput("ParamInput")), listOf(PhysicsOutput("ParamOutput")))

    @Test fun replacingCmo3KeepsEvaluatedPhysicsAsOrdinaryContent(): Unit = runBlocking {
        val f = fixture()
        val incoming = Cmo3Target().convert(RigIrCompiler.compile(f.now.model, tileArt = true), ExportOptions("replacement"))
        val path = Files.write(temp.resolve("replacement.cmo3"), Cmo3.write(incoming.model))
        f.edit("modifier_add", add(f, "physics", physics().toJson(), "pendulum"))
        val previous = f.now
        val groups = PhysicsCatalog.active(previous.model)
        assertEquals(1, groups.size)

        val replaced = WorkspaceCmo3Importer(f.runtime).import(path, Cmo3ImportMode.REPLACE, previous.projectId,
            previous.state, MutationAuthor.USER).capture

        assertTrue(RigModifierStack.fromJson(requireNotNull(replaced.document.rigEdits.modifierStack)).instances.isEmpty())
        assertEquals(groups, replaced.document.rigEdits.physicsEdits)
        assertEquals(groups, PhysicsCatalog.active(replaced.model))
    }

    private fun add(f: Fixture, type: String, config: JsonObject, id: String? = null, target: ModifierTarget = f.target) = buildJsonObject {
        put("type", type); put("version", 1); put("config", config)
        id?.let { put("id", it) }
        putJsonArray("targets") { addJsonObject { put("kind", target.kind); put("id", target.id) } }
    }
    private fun id(id: String) = buildJsonObject { put("id", id) }
    private fun stack(f: Fixture) = RigModifierStack.fromJson(f.now.document.rigEdits.modifierStack!!)

    @Test fun simulationPrefixReuseStopsAtChangedInputsAndOverrides() {
        val target = ModifierTarget("mesh", "mesh")
        val before = RigModifierStack().add("body/legacy", 1, listOf(target))
            .add("simulation", 1, listOf(target)).add("motion", 1, listOf(target))
        val tail = before.update(before.instances.last().id) { it.copy(enabled = false) }
        assertEquals(2, WorkspaceModifierSimulation.unchangedPrefix(before, tail))
        val first = before.update(before.instances.first().id) { it.copy(enabled = false) }
        assertEquals(0, WorkspaceModifierSimulation.unchangedPrefix(before, first))
        val overridden = before.copy(overrides = mapOf(before.instances.first().id to listOf(buildJsonObject { put("op", "edit") })))
        assertEquals(0, WorkspaceModifierSimulation.unchangedPrefix(before, overridden))
        assertEquals(0, WorkspaceModifierSimulation.unchangedPrefix(before, before.move(before.instances.last().id, 0)))
    }

    @Test fun wholePresetSwitchReplacesInstancesAndKeepsOrdinaryParameters(): Unit = runBlocking<Unit> {
        val f = fixture()
        f.edit("modifier_add", add(f, "motion", MotionClips.toJson(motion())))
        val original = stack(f).instances.map { it.id }.toSet()
        f.edit("modifier_switch", buildJsonObject { put("group", "legacy") })
        assertTrue(stack(f).instances.isNotEmpty())
        assertTrue(stack(f).instances.all { it.presetGroup == "legacy" && it.id !in original })
        assertTrue(f.now.model.rig.puppet.parameters.any { it.id.raw == "ParamInput" })
        val legacy = stack(f).instances.map { it.id }.toSet()
        f.edit("modifier_switch", buildJsonObject { put("group", "modern") })
        assertTrue(stack(f).instances.all { it.presetGroup == "modern" && it.id !in legacy })
        f.edit("modifier_switch", buildJsonObject { put("group", "empty") })
        assertTrue(stack(f).instances.isEmpty())
        assertTrue(f.now.model.rig.puppet.parameters.any { it.id.raw == "ParamInput" })
    }

    @Test fun addUpdateEnableMoveAndRemoveAreOrdinaryUndoableDocumentTransactions(): Unit = runBlocking<Unit> {
        val f = fixture()
        val initial = f.now
        f.edit("modifier_add", add(f, "motion", MotionClips.toJson(motion()), "first"))
        val first = f.now
        f.edit("modifier_add", add(f, "motion", MotionClips.toJson(motion("Other")), "second"))
        f.edit("modifier_move", buildJsonObject { put("id", "second"); put("index", 0) })
        assertEquals(listOf("second", "first"), stack(f).instances.map { it.id })
        f.edit("modifier_update", buildJsonObject { put("id", "first"); put("type", "motion"); put("config", MotionClips.toJson(motion("Changed"))) })
        assertEquals("Changed", RigIrCompiler.compile(f.now.model).clips.last().name)
        f.edit("modifier_enable", buildJsonObject { put("id", "first"); put("enabled", false) })
        assertEquals(1, RigIrCompiler.compile(f.now.model).clips.size)
        f.edit("modifier_remove", id("first"))
        assertTrue("first" in stack(f).retiredIds)
        assertEquals(listOf("second"), stack(f).instances.map { it.id })
        f.runtime.checkout(f.now.projectId, f.now.state, first.historyHead)
        assertEquals(first.revision, f.now.revision)
        assertEquals(listOf("first"), stack(f).instances.map { it.id })
        assertEquals(RigIrCompiler.compile(first.model), RigIrCompiler.compile(f.now.model))
        f.runtime.checkout(f.now.projectId, f.now.state, initial.historyHead)
        assertEquals(initial.revision, f.now.revision)
        assertTrue(stack(f).instances.isEmpty())
    }

    @Test fun sourceAdditionPlansCaptureDependenciesAndReuseAppliedProvidersWithoutPublishing(): Unit = runBlocking<Unit> {
        val f = fixture()
        val original = f.now
        fun request(type: String) = buildJsonObject { put("type", type)
            putJsonArray("targets") { addJsonObject { put("kind", f.target.kind); put("id", f.target.id) } }
        }
        val request = request("body/legacy")
        validateOperationSchema(request, WorkspaceModifierSchemas.planRequest)
        val plan = WorkspaceModifierPlan.prepare(original, request)
        validateOperationSchema(plan, WorkspaceModifierSchemas.plan, "result")
        assertEquals(original, f.now)
        val entries = plan.getValue("entries").jsonArray
        assertEquals(listOf("rig.scaffold", "body/legacy"), entries.map { it.jsonObject.getValue("type").jsonPrimitive.content })
        val operation = WorkspaceDocumentOperation("modifier_add_group", buildJsonObject { put("group", plan.getValue("group")); put("entries", entries) })
        val preview = f.commands.previewRegeneration(original.projectId, original.state, listOf(operation))
        val committed = f.commands.execute(original.projectId, original.state, "Add planned bindings", listOf(operation), MutationAuthor.USER)
        assertEquals(preview.getValue("candidate_revision").jsonPrimitive.content, committed.capture.revision)
        assertEquals(preview.getValue("modifiers"), f.now.model.rig.modifierReport!!.getValue("entries"))
        val structure = entries.first().jsonObject.getValue("id").jsonPrimitive.content
        f.edit("modifier_apply", buildJsonObject { put("id", structure) })
        val next = WorkspaceModifierPlan.prepare(f.now, request("face/legacy"))
        assertEquals(listOf("face/legacy"), next.getValue("entries").jsonArray.map { it.jsonObject.getValue("type").jsonPrimitive.content })
        assertEquals(structure, next.getValue("entries").jsonArray.single().jsonObject.getValue("config").jsonObject.getValue("scaffold").jsonPrimitive.content)
        assertEquals("applied", next.getValue("plan").jsonArray.first().jsonObject.getValue("disposition").jsonPrimitive.content)
        validateOperationSchema(next, WorkspaceModifierSchemas.plan, "result")
    }

    @Test fun nativePlansShareCapturedIdentityAndPreviewWithTheCommittedStack(): Unit = runBlocking<Unit> {
        val f = fixture()
        for ((type, supplied) in listOf("swing" to null, "physics" to null, "motion" to MotionClips.toJson(motion()))) {
            val before = f.now
            val request = buildJsonObject {
                put("type", type); supplied?.let { put("config", it) }
                putJsonArray("targets") { addJsonObject { put("kind", f.target.kind); put("id", f.target.id) } }
            }
            validateOperationSchema(request, WorkspaceModifierSchemas.planRequest)
            val plan = WorkspaceModifierPlan.prepare(before, request)
            validateOperationSchema(plan, WorkspaceModifierSchemas.plan, "result")
            assertEquals(before, f.now)
            val entry = plan.getValue("entries").jsonArray.single().jsonObject
            assertEquals(type, entry.getValue("type").jsonPrimitive.content)
            supplied?.let { assertEquals(it, entry.getValue("config")) }
            val operation = WorkspaceDocumentOperation("modifier_add_group", buildJsonObject {
                put("group", plan.getValue("group")); put("entries", plan.getValue("entries"))
            })
            val preview = f.commands.previewRegeneration(before.projectId, before.state, listOf(operation))
            assertEquals(before, f.now)
            val commit = f.commands.execute(before.projectId, before.state, "Add native modifier", listOf(operation), MutationAuthor.USER)
            assertEquals(preview.getValue("candidate_revision").jsonPrimitive.content, commit.capture.revision)
            assertEquals(preview.getValue("modifiers"), commit.capture.model.rig.modifierReport!!.getValue("entries"))
            assertEquals(1, stack(f).instances.size)
            f.edit("modifier_remove", id(entry.getValue("id").jsonPrimitive.content))
        }
    }

    @Test fun plannedGeneratedMotionUsesExistingAlgorithmsAndOnlyExistingParameters(): Unit = runBlocking<Unit> {
        val f = fixture()
        val sourcePlan = WorkspaceModifierPlan.prepare(f.now, buildJsonObject {
            put("type", "body/legacy"); putJsonArray("targets") { addJsonObject { put("kind", f.target.kind); put("id", f.target.id) } }
        })
        f.edit("modifier_add_group", buildJsonObject { put("group", sourcePlan.getValue("group")); put("entries", sourcePlan.getValue("entries")) })
        val before = f.now
        val plan = WorkspaceModifierPlan.prepare(before, buildJsonObject {
            put("type", "motion"); putJsonArray("targets") { addJsonObject { put("kind", f.target.kind); put("id", f.target.id) } }
        })
        val clip = MotionClips.fromJson(plan.getValue("entries").jsonArray.single().jsonObject.getValue("config").jsonObject)
        val available = before.model.rig.puppet.parameters.mapTo(HashSet()) { it.id.raw }
        val tracks = MotionClips.builtinTracks(clip.name, before.document.rigEdits.skeleton).filter { it.parameterId in available }
        val generated = MotionClips.fromTracks("template", clip.name, null, MotionClips.isLoopBuiltin(clip.name), tracks, MotionClips.builtinDuration(clip.name, tracks))
        assertEquals(MotionClips.reconcileParameters(listOf(generated), before.model.rig.puppet.parameters).single(), clip)
        val namedRequest = buildJsonObject {
            put("type", "motion"); put("preset", clip.name)
            putJsonArray("targets") { addJsonObject { put("kind", f.target.kind); put("id", f.target.id) } }
        }
        validateOperationSchema(namedRequest, WorkspaceModifierSchemas.planRequest)
        assertEquals(plan, WorkspaceModifierPlan.prepare(before, namedRequest))
        assertFails { validateOperationSchema(JsonObject(namedRequest + ("config" to MotionClips.toJson(clip))), WorkspaceModifierSchemas.planRequest) }
        assertTrue(clip.curves.isNotEmpty())
        assertEquals(before, f.now)
    }

    @Test fun naturalMotionPresetsCaptureTheOriginalTracksAndRemainEditableAfterApplication(): Unit = runBlocking<Unit> {
        val parameters = StandardParameters.all.map { RigParameterEdit(it.id.raw, it.name, it.min, it.max, it.default, created = true) }
        val f = fixture(additionalParameters = parameters)
        val available = parameters.mapTo(HashSet()) { it.id }
        val settings = MotionPresetSettings(natural = true)
        for (name in MotionClips.BASIC_NAMES) {
            val before = f.now
            val preset = "natural:$name"
            val request = buildJsonObject {
                put("type", "motion"); put("preset", preset)
                putJsonArray("targets") { addJsonObject { put("kind", f.target.kind); put("id", f.target.id) } }
            }
            validateOperationSchema(request, WorkspaceModifierSchemas.planRequest)
            val plan = WorkspaceModifierPlan.prepare(before, request)
            validateOperationSchema(plan, WorkspaceModifierSchemas.plan, "result")
            val tracks = MotionClips.builtinTracks(name, null, settings = settings).filter { it.parameterId in available }
            val generated = MotionClips.fromTracks(preset, name, null, MotionClips.isLoopBuiltin(name), tracks,
                MotionClips.builtinDuration(name, tracks, settings))
            val expected = MotionClips.reconcileParameters(listOf(generated), before.model.rig.puppet.parameters).single().copy(
                parameterRoles = if (name == "Blink") mapOf("EyeBlink" to listOf("ParamEyeLOpen", "ParamEyeROpen")) else emptyMap())
            val entry = plan.getValue("entries").jsonArray.single().jsonObject
            assertEquals(expected, MotionClips.fromJson(entry.getValue("config").jsonObject))
            assertEquals(before, f.now)
            f.edit("modifier_add_group", buildJsonObject { put("group", plan.getValue("group")); put("entries", plan.getValue("entries")) })
            val ir = RigIrCompiler.compile(f.now.model)
            val instance = stack(f).instances.single()
            assertEquals(expected.curves.map(MotionGenerator::curve), ir.clips.single { it.id == instance.outputId("motion") }.curves)
            f.edit("modifier_apply", id(instance.id))
            assertEquals(ir, RigIrCompiler.compile(f.builder.build(f.now.document)))
        }
    }

    @Test fun legacyPhysicsPresetsProduceEditableExplicitGroups(): Unit = runBlocking<Unit> {
        val parameters = StandardParameters.all.map { RigParameterEdit(it.id.raw, it.name, it.min, it.max, it.default, created = true) }
        val f = fixture(additionalParameters = parameters)
        val originalRules = PhysicsGenerator.presetRules(PhysicsGenerator.Presets.All, parameters.mapTo(HashSet()) { it.id })
        for (rule in originalRules) {
            val before = f.now
            val request = buildJsonObject {
                put("type", "physics"); put("preset", rule.id)
                putJsonArray("targets") { addJsonObject { put("kind", f.target.kind); put("id", f.target.id) } }
            }
            validateOperationSchema(request, WorkspaceModifierSchemas.planRequest)
            val plan = WorkspaceModifierPlan.prepare(before, request)
            validateOperationSchema(plan, WorkspaceModifierSchemas.plan, "result")
            val entry = plan.getValue("entries").jsonArray.single().jsonObject
            assertEquals(rule.toJson(), entry.getValue("config"))
            assertEquals(before, f.now)
            val operation = WorkspaceDocumentOperation("modifier_add_group", buildJsonObject {
                put("group", plan.getValue("group")); put("entries", plan.getValue("entries"))
            })
            val preview = f.commands.previewRegeneration(before.projectId, before.state, listOf(operation))
            val result = f.commands.execute(before.projectId, before.state, "Add physics preset", listOf(operation), MutationAuthor.USER)
            assertEquals(preview.getValue("candidate_revision").jsonPrimitive.content, result.capture.revision)
            val instance = stack(f).instances.single()
            val rig = RigIrCompiler.compile(f.now.model)
            assertEquals(Physics3Json.group(rule.copy(id = instance.outputId("physics"))),
                rig.physics.groups.single { it.id == instance.outputId("physics") })
            assertFailsWith<IllegalArgumentException> { WorkspaceModifierPlan.prepare(f.now, request) }
            f.edit("modifier_apply", id(instance.id))
            assertEquals(rig, RigIrCompiler.compile(f.builder.build(f.now.document)))
        }
    }

    @Test fun explicitFittedEyePackagePlansItsVersionTwoScaffold(): Unit = runBlocking {
        val f = fixture()
        val plan = WorkspaceModifierPlan.prepare(f.now, buildJsonObject {
            put("type", "eye/fitted")
            putJsonArray("targets") { addJsonObject { put("kind", f.target.kind); put("id", f.target.id) } }
        })
        val entries = plan.getValue("entries")
        f.edit("modifier_add_group", buildJsonObject { put("group", plan.getValue("group")); put("entries", entries) })
        val (scaffold, eye) = stack(f).instances
        assertEquals("rig.scaffold" to 2, scaffold.type to scaffold.version)
        assertEquals("eye/fitted" to 1, eye.type to eye.version)
        assertEquals(scaffold.id, eye.config.getValue("scaffold").jsonPrimitive.content)
        assertEquals(5, f.now.model.rig.puppet.parameters.count { it.id.raw.startsWith("ParamEye") })
        val smile = WorkspaceModifierPlan.prepare(f.now, buildJsonObject {
            put("type", "eye.smile")
            putJsonArray("targets") { addJsonObject { put("kind", f.target.kind); put("id", f.target.id) } }
        }).getValue("entries").jsonArray.single().jsonObject
        assertEquals("eye.smile", smile.getValue("type").jsonPrimitive.content)
        assertEquals(eye.id, smile.getValue("config").jsonObject.getValue("base").jsonPrimitive.content)
    }

    @Test fun naturalBlinkPlansItsEyeBindingAndAppliesWithTheSameCurves(): Unit = runBlocking<Unit> {
        val f = fixture()
        val before = f.now
        val plan = WorkspaceModifierPlan.prepare(before, buildJsonObject {
            put("type", "motion"); put("preset", "natural:Blink")
            putJsonArray("targets") { addJsonObject { put("kind", f.target.kind); put("id", f.target.id) } }
        })
        validateOperationSchema(plan, WorkspaceModifierSchemas.plan, "result")
        val entries = plan.getValue("entries").jsonArray
        assertEquals(listOf("rig.scaffold", "eye/fitted", "motion"), entries.map { it.jsonObject.getValue("type").jsonPrimitive.content })
        assertEquals(before, f.now)
        f.edit("modifier_add_group", buildJsonObject { put("group", plan.getValue("group")); put("entries", entries) })
        val instance = stack(f).instances.last()
        val eye = stack(f).instances.single { it.type == "eye/fitted" }
        assertEquals(1, eye.version)
        assertEquals(mapOf("ParamEyeLOpen" to eye.id, "ParamEyeROpen" to eye.id), instance.parameterProviders)
        val ir = RigIrCompiler.compile(f.now.model)
        assertEquals(setOf("ParamEyeLOpen", "ParamEyeROpen"), ir.clips.single().curves.map { it.parameter }.toSet())
        assertEquals(listOf("ParamEyeLOpen", "ParamEyeROpen"), ir.parameterRoles.single { it.role == "EyeBlink" }.parameters)
        f.edit("modifier_apply", id(instance.id))
        assertEquals(ir, RigIrCompiler.compile(f.builder.build(f.now.document)))
    }

    @Test fun namedPhysicsPlanIncludesAndPinsMissingBindings(): Unit = runBlocking<Unit> {
        val f = fixture()
        val start = f.now
        val plan = WorkspaceModifierPlan.prepare(start, buildJsonObject {
            put("type", "physics"); put("preset", PhysicsGenerator.FRONT_HAIR_ID)
            putJsonArray("targets") { addJsonObject { put("kind", f.target.kind); put("id", f.target.id) } }
        })
        validateOperationSchema(plan, WorkspaceModifierSchemas.plan, "result")
        val entries = plan.getValue("entries").jsonArray
        assertEquals(listOf("rig.scaffold", "body/legacy", "face/fitted", "hair/legacy", "physics"),
            entries.map { it.jsonObject.getValue("type").jsonPrimitive.content })
        assertEquals(start, f.now)
        val result = f.edit("modifier_add_group", buildJsonObject {
            put("group", plan.getValue("group")); put("entries", entries)
        })
        val consumer = stack(f).instances.last()
        val providers = stack(f).instances.drop(1).dropLast(1).mapTo(HashSet()) { it.id }
        assertEquals(providers, consumer.parameterProviders.values.toSet())
        assertEquals(1, RigIrCompiler.compile(result.capture.model).physics.groups.size)
        val hair = stack(f).instances.single { it.type == "hair/legacy" }.id
        f.edit("modifier_remove", id(hair))
        assertTrue(RigIrCompiler.compile(f.now.model).physics.groups.isEmpty())
        assertEquals(consumer, stack(f).instances.last())
    }

    @Test fun physicsAndMotionKeepTheirCapturedParameterProviderAcrossDisableAndApply(): Unit = runBlocking<Unit> {
        val f = fixture()
        val plan = WorkspaceModifierPlan.prepare(f.now, buildJsonObject {
            put("type", "body/legacy")
            putJsonArray("targets") { addJsonObject { put("kind", f.target.kind); put("id", f.target.id) } }
        })
        val bindings = plan.getValue("entries").jsonArray
        val provider = bindings.last().jsonObject.getValue("id").jsonPrimitive.content
        val parameter = StandardParameters.BODY_X.raw
        val rule = physics().copy(inputs = listOf(PhysicsInput(parameter)))
        val clip = motion().copy(curves = listOf(MotionCurve(parameter, listOf(MotionKey(0f, 0f), MotionKey(1f, .5f)))))
        val group = WorkspaceDocumentOperation("modifier_add_group", buildJsonObject {
            put("group", plan.getValue("group"))
            put("entries", JsonArray(bindings + add(f, "motion", MotionClips.toJson(clip), "action")))
        })
        val start = f.now
        val preview = f.commands.previewRegeneration(start.projectId, start.state, listOf(group))
        assertEquals(start, f.now)
        val commit = f.commands.execute(start.projectId, start.state, "Add binding and action", listOf(group), MutationAuthor.USER)
        assertEquals(preview.getValue("candidate_revision").jsonPrimitive.content, commit.capture.revision)
        f.edit("modifier_add", add(f, "physics", rule.toJson(), "pendulum"))
        val expected = RigIrCompiler.compile(f.now.model)
        assertTrue(stack(f).instances.takeLast(2).all { it.parameterProviders == mapOf(parameter to provider) })
        val before = f.now
        assertFails { f.edit("modifier_move", buildJsonObject { put("id", "action"); put("index", 1) }) }
        assertEquals(before, f.now)
        f.edit("modifier_enable", buildJsonObject { put("id", provider); put("enabled", false) })
        val paused = WorkspaceModifierQueries.describe(f.now)
        validateOperationSchema(paused, WorkspaceModifierSchemas.stack, "result")
        assertTrue(paused.getValue("entries").jsonArray.takeLast(2).all {
            it.jsonObject.getValue("status").jsonPrimitive.content == "missing_dependency"
        })
        assertTrue(RigIrCompiler.compile(f.now.model).physics.groups.isEmpty())
        assertTrue(RigIrCompiler.compile(f.now.model).clips.isEmpty())
        f.edit("modifier_enable", buildJsonObject { put("id", provider); put("enabled", true) })
        assertEquals(expected, RigIrCompiler.compile(f.now.model))
        f.edit("modifier_apply", id(provider))
        assertEquals(expected, RigIrCompiler.compile(f.builder.build(f.now.document)))
    }

    @Test fun conflictingPluginOutputsStayIsolatedInPreviewAndCommit(): Unit = runBlocking<Unit> {
        val f = fixture()
        f.edit("modifier_add", add(f, "physics", physics().toJson(), "claimed"))
        val before = f.now
        val request = buildJsonObject { put("type", "physics"); put("config", physics().toJson())
            putJsonArray("targets") { addJsonObject { put("kind", f.target.kind); put("id", f.target.id) } }
        }
        val plan = WorkspaceModifierPlan.prepare(before, request)
        val operation = WorkspaceDocumentOperation("modifier_add_group", buildJsonObject {
            put("group", plan.getValue("group")); put("entries", plan.getValue("entries"))
        })
        f.commands.previewRegeneration(before.projectId, before.state, listOf(operation))
        assertEquals(before, f.now)
        f.commands.execute(before.projectId, before.state, "Conflict", listOf(operation), MutationAuthor.USER)
        assertEquals(listOf(ModifierStatus.ACTIVE, ModifierStatus.FAILED), f.now.model.modifierEvaluation!!.entries.map { it.status })
        assertTrue(f.now.model.modifierEvaluation!!.entries.last().error.orEmpty().contains("Multiple physics groups"))
        assertEquals(RigIrCompiler.compile(before.model), RigIrCompiler.compile(f.now.model))
        val isolated = f.now
        val malformed = JsonObject(request + ("config" to buildJsonObject { put("id", "invalid") }))
        assertFails { validateOperationSchema(malformed, WorkspaceModifierSchemas.planRequest) }
        assertEquals(isolated, f.now)
    }

    @Test fun sourcePlansContainTheCompleteNativePrerequisites(): Unit = runBlocking<Unit> {
        val f = fixture()
        val before = f.now
        for ((type, expected) in listOf(
            "mouth.tongue" to listOf("rig.scaffold", "mouth/fitted", "mouth.capture", "mouth.tongue"),
            "mouth.spring" to listOf("rig.scaffold", "mouth/fitted", "mouth.jaw", "mouth.spring"),
            "mouth.interior-depth" to listOf("rig.scaffold", "mouth/fitted", "face/fitted", "mouth.interior-depth"),
            "expression.cheek" to listOf("rig.scaffold", "expression.cheek"),
            "face.skin-clip" to listOf("rig.scaffold", "face.skin-clip"),
            "face.neck" to listOf("rig.scaffold", "face/fitted", "face.neck"),
            "face/fitted" to listOf("rig.scaffold", "face/fitted"),
            "hair/chains" to listOf("rig.scaffold", "body/legacy", "face/fitted", "hair/chains"),
            "body.bust" to listOf("rig.scaffold", "body/legacy", "face/fitted", "body.bust"),
            "outfit.ribbons" to listOf("rig.scaffold", "body/legacy", "face/fitted", "outfit.ribbons"))) {
            val request = buildJsonObject { put("type", type)
                putJsonArray("targets") { addJsonObject { put("kind", f.target.kind); put("id", f.target.id) } }
            }
            validateOperationSchema(request, WorkspaceModifierSchemas.planRequest)
            val plan = WorkspaceModifierPlan.prepare(before, request)
            validateOperationSchema(plan, WorkspaceModifierSchemas.plan, "result")
            assertEquals(expected, plan.getValue("entries").jsonArray.map { it.jsonObject.getValue("type").jsonPrimitive.content })
            assertEquals(before, f.now)
        }
    }

    @Test fun applyingPrefixPreservesDisabledMaterializedMotionAndPhysicsForReenable(): Unit = runBlocking {
        val f = fixture()
        f.edit("modifier_add", add(f, "physics", physics().toJson(), "physics-owner"))
        f.edit("modifier_add", add(f, "motion", MotionClips.toJson(motion()), "motion-owner"))
        for (owner in listOf("physics-owner", "motion-owner")) {
            f.edit("modifier_apply", buildJsonObject { put("id", owner); put("scope", "selected") })
        }
        val expected = RigIrCompiler.compile(f.now.model)
        val physicsId = PhysicsCatalog.active(f.now.model).single().id
        val motionId = MotionCatalog.entries(f.now.document.rigEdits).single().id
        f.edit("physics_put", buildJsonObject { put("id", physicsId); put("enabled", false) })
        f.edit("motion_properties", buildJsonObject { put("id", motionId); put("enabled", false) })
        val inactive = RigIrCompiler.compile(f.now.model)
        f.edit("modifier_apply", id("motion-owner"))
        assertTrue(stack(f).instances.isEmpty())
        assertEquals(inactive, RigIrCompiler.compile(f.builder.build(f.now.document)))
        assertEquals(physicsId, f.now.document.rigEdits.physicsEdits.single().id)
        assertFalse(f.now.document.rigEdits.motionClips.single().enabled)
        f.edit("physics_put", buildJsonObject { put("id", physicsId); put("enabled", true) })
        f.edit("motion_properties", buildJsonObject { put("id", motionId); put("enabled", true) })
        assertEquals(expected, RigIrCompiler.compile(f.now.model))
        assertEquals(expected, RigIrCompiler.compile(f.builder.build(f.now.document)))
    }

    @Test fun selectedAppliedPhysicsSupportsOrdinaryEditsAndRejectsLaterParameterInputs(): Unit = runBlocking {
        val f = fixture(withWarp = true)
        f.edit("modifier_add", add(f, "physics", physics().toJson(), "pendulum"))
        f.edit("modifier_apply", buildJsonObject { put("id", "pendulum"); put("scope", "selected") })
        val row = WorkspaceDocumentEdits.physicsCatalog(f.now.document, f.now.model).single()
        assertEquals(PhysicsOrigin.CUSTOM, row.origin)
        val edited = row.setting.copy(segments = listOf(PhysicsSegment(mobility = .7f)))
        f.edit("physics_put", edited.toJson())
        assertEquals(edited.segments, PhysicsCatalog.active(f.now.model).single().segments)
        assertTrue(f.now.document.rigEdits.physicsEdits.isEmpty())
        f.edit("physics_put", buildJsonObject { put("id", edited.id); put("enabled", false) })
        assertTrue(PhysicsCatalog.active(f.now.model).isEmpty())
        assertFalse(WorkspaceDocumentEdits.physicsCatalog(f.now.document, f.now.model).single().enabled)
        f.edit("physics_put", buildJsonObject { put("id", edited.id); put("enabled", true) })
        assertEquals(edited.segments, PhysicsCatalog.active(f.now.model).single().segments)
        val swing = RigSwingEdit.single("template", "Swing", SwingKind.LATERAL, listOf("warp"), listOf("P"))
        f.edit("modifier_add", add(f, "swing", swing.toJson(), "later", ModifierTarget("deformer", "warp")))
        val laterParameter = stack(f).instances.last().outputId("swing:lateral:0")
        val before = f.now
        assertFails { f.edit("physics_put", edited.copy(inputs = listOf(PhysicsInput(laterParameter))).toJson()) }
        assertEquals(before.state, f.now.state)
        assertEquals(RigIrCompiler.compile(f.now.model), RigIrCompiler.compile(f.builder.build(f.now.document)))
        f.edit("physics_delete", id(edited.id))
        assertTrue(PhysicsCatalog.active(f.now.model).isEmpty())
        assertTrue(WorkspaceDocumentEdits.physicsCatalog(f.now.document, f.now.model).isEmpty())
    }

    @Test fun workspacePhysicsReadsTheEvaluatedStackAndAppliedGroupsStayOrdinarilyEditable(): Unit = runBlocking<Unit> {
        val f = fixture()
        val initial = f.now
        val document = initial.document.copy(settings = JsonObject(initial.document.settings + ("generatePhysics" to JsonPrimitive(false))))
        f.runtime.install(initial.state, initial.projectId, document, f.builder.build(document))
        f.edit("modifier_add", add(f, "physics", physics().toJson(), "pendulum"))
        val generated = f.now
        assertFalse(generated.model.config.generatePhysics)
        val rows = WorkspaceDocumentEdits.physicsCatalog(generated.document, generated.model)
        assertEquals(1, rows.size)
        assertEquals(PhysicsOrigin.MODIFIER, rows.single().origin)
        assertTrue(rows.single().active)
        assertEquals(rows.map { it.setting }, PhysicsCatalog.active(generated.model))
        val read = WorkspaceReadSession(f.runtime.read())
        assertEquals(rows, read.listPhysics())
        val trace = read.simulatePhysics(buildJsonObject { putJsonObject("inputs") { put("ParamInput", .8f) }; put("duration", .5f); put("hold", .2f) }, {}, { false })
        assertFalse("note" in trace, "MCP must not report evaluated stack physics as switched off")
        val clock = WorkspacePreviewPhysics()
        val expected = PhysicsEngine(rows.map { it.setting }, PhysicsEngine.ranges(generated.model.rig.puppet.parameters))
        var moved = false
        repeat(90) { frame ->
            val input = mapOf("ParamInput" to if (frame < 30) .8f else -.6f)
            val actual = clock.step(generated.model, input.mapKeys { org.umamo.runtime.model.ParameterId(it.key) }, emptySet(), 1f / 60, false)
            assertEquals(expected.step(input, 1f / 60), actual.mapKeys { it.key.raw })
            if (actual.values.any { kotlin.math.abs(it) > .01f }) moved = true
        }
        assertTrue(moved)
        assertFails { f.edit("physics_put", buildJsonObject { put("id", rows.single().id); put("enabled", false) }) }
        assertEquals(generated, f.now)
        assertFails { f.edit("physics_delete", id(rows.single().id)) }
        assertEquals(generated, f.now)
        assertFails { f.edit("physics_config", buildJsonObject { putJsonArray("order") { add(rows.single().id) } }) }
        assertEquals(generated, f.now)
        f.edit("modifier_apply", id("pendulum"))
        assertEquals(PhysicsOrigin.CUSTOM, WorkspaceDocumentEdits.physicsCatalog(f.now.document, f.now.model).single().origin)
        val edited = rows.single().setting.copy(segments = listOf(PhysicsSegment(mobility = .7f)))
        f.edit("physics_put", edited.toJson())
        assertEquals(edited.segments, PhysicsCatalog.active(f.now.model).single().segments)
        assertFalse(f.now.document.config().generatePhysics, "ordinary edits must not reactivate a legacy preset switch")
        f.edit("physics_put", buildJsonObject { put("id", edited.id); put("enabled", false) })
        assertTrue(PhysicsCatalog.active(f.now.model).isEmpty())
        val inactive = WorkspaceDocumentEdits.physicsCatalog(f.now.document, f.now.model).single()
        assertEquals(PhysicsOrigin.CUSTOM, inactive.origin)
        assertFalse(inactive.active)
        val release = clock.step(f.now.model, emptyMap(), emptySet(), 1f / 60, false)
        assertEquals(0f, release.getValue(org.umamo.runtime.model.ParameterId("ParamOutput")))
    }

    @Test fun invalidSourcePlanDoesNotAllocateDocumentIdentityOrPublishPartialProviders(): Unit = runBlocking<Unit> {
        val f = fixture()
        val before = f.now
        assertFailsWith<IllegalArgumentException> {
            WorkspaceModifierPlan.prepare(before, buildJsonObject { put("type", "body/legacy")
                putJsonArray("targets") { addJsonObject { put("kind", "mesh"); put("id", "missing") } }
            })
        }
        assertEquals(before, f.now)
        val plan = WorkspaceModifierPlan.prepare(before, buildJsonObject { put("type", "brow.lid-coupling")
            putJsonArray("targets") { addJsonObject { put("kind", "mesh"); put("id", f.target.id) } }
        })
        assertEquals(listOf("rig.scaffold", "brow/fitted", "eye/fitted", "brow.lid-coupling"),
            plan.getValue("entries").jsonArray.map { it.jsonObject.getValue("type").jsonPrimitive.content })
        assertEquals(before, f.now)
    }

    @Test fun additionPreviewReportsTheSameContributionsWithoutPublishingTheCandidate(): Unit = runBlocking<Unit> {
        val f = fixture()
        val before = f.now
        val edits = listOf(WorkspaceDocumentOperation("modifier_add_group", buildJsonObject {
            put("group", "previewed")
            put("entries", JsonArray(listOf(add(f, "rig.scaffold", JsonObject(emptyMap()), "structure"),
                add(f, "body/legacy", buildJsonObject { put("scaffold", "structure") }))))
        }))
        val preview = f.commands.previewRegeneration(before.projectId, before.state, edits)
        validateOperationSchema(preview, WorkspaceJobResultSchemas.regenerationPreview, "result")
        assertEquals(before, f.now, "preview must not publish a model, state, history, or document")
        val entries = preview.getValue("modifiers").jsonArray
        assertEquals(listOf("structure", "m2"), entries.map { it.jsonObject.getValue("id").jsonPrimitive.content })
        assertTrue(entries.all { it.jsonObject.getValue("status").jsonPrimitive.content == "active" })
        assertTrue(entries.last().jsonObject.getValue("contributions").jsonArray.isNotEmpty())
        val result = f.commands.execute(before.projectId, before.state, "Add previewed group", edits, MutationAuthor.USER)
        assertEquals(preview.getValue("candidate_revision").jsonPrimitive.content, result.capture.revision)
        assertEquals(preview.getValue("modifiers"), f.now.model.rig.modifierReport!!.getValue("entries"))
        val changed = f.now
        assertFailsWith<WorkspaceConflict> { f.commands.previewRegeneration(before.projectId, before.state, edits) }
        assertEquals(changed, f.now)
    }

    @Test fun aMissingProviderCanBeInsertedBeforeItsConsumerWithoutReorderingExistingItems(): Unit = runBlocking<Unit> {
        val f = fixture()
        f.edit("modifier_add", add(f, "body/legacy", buildJsonObject { put("scaffold", "structure") }, "body"))
        val paused = f.now
        val structure = add(f, "rig.scaffold", JsonObject(emptyMap()), "structure")
        assertFailsWith<WorkspaceBatchEditException> { f.edit("modifier_add", structure) }
        assertEquals(paused.state, f.now.state)
        val inserted = JsonObject(structure + ("index" to JsonPrimitive(0)))
        validateOperationSchema(JsonObject(inserted + ("state" to JsonPrimitive(paused.state))), WorkspaceModifierSchemas.request("modifier_add"))
        f.edit("modifier_add", inserted)
        assertEquals(listOf("structure", "body"), stack(f).instances.map { it.id })
        assertTrue(f.now.model.rig.modifierReport!!.getValue("entries").jsonArray.all {
            it.jsonObject.getValue("status").jsonPrimitive.content == "active"
        })
        assertEquals(RigIrCompiler.compile(f.now.model), RigIrCompiler.compile(f.builder.build(f.now.document)))
        val current = f.now
        assertFailsWith<WorkspaceBatchEditException> {
            f.edit("modifier_add", JsonObject(add(f, "motion", MotionClips.toJson(motion()), "bad") + ("index" to JsonPrimitive(99))))
        }
        assertEquals(current.state, f.now.state)
        f.runtime.checkout(f.now.projectId, f.now.state, paused.historyHead)
        assertEquals(listOf("body"), stack(f).instances.map { it.id })
        assertEquals(paused.revision, f.now.revision)
    }

    @Test fun movingPhysicsModifiersChangesCapturedOrderAndSurvivesReplayAndUndo(): Unit = runBlocking<Unit> {
        fun output(id: String) = RigModifierInstance(id, "physics", 1, listOf(ModifierTarget("mesh", "placeholder"))).outputId("physics")
        val first = output("first")
        val second = output("second")
        val f = fixture(additionalParameters = listOf(RigParameterEdit("ParamFinal", "Final", -1f, 1f, 0f, created = true)),
            physicsOrder = listOf(first, second))
        f.edit("modifier_add", add(f, "physics", physics().toJson(), "first"))
        f.edit("modifier_add", add(f, "physics", physics().copy(inputs = listOf(PhysicsInput("ParamOutput")),
            outputs = listOf(PhysicsOutput("ParamFinal"))).toJson(), "second"))
        val before = f.now
        assertEquals(listOf(first, second), RigIrCompiler.compile(before.model).physics.groups.map { it.id })
        f.edit("modifier_move", buildJsonObject { put("id", "second"); put("index", 0) })
        assertEquals(listOf(second, first), RigIrCompiler.compile(f.now.model).physics.groups.map { it.id })
        assertEquals(listOf(second, first), RigIrCompiler.compile(f.builder.build(f.now.document)).physics.groups.map { it.id })
        f.runtime.checkout(f.now.projectId, f.now.state, before.historyHead)
        assertEquals(listOf(first, second), RigIrCompiler.compile(f.now.model).physics.groups.map { it.id })
    }

    @Test fun groupsCommitOnceAndLaterBatchMembersCanReferenceChosenInstanceIds(): Unit = runBlocking<Unit> {
        val f = fixture()
        val before = f.now
        val group = buildJsonObject {
            put("group", "recommended")
            put("entries", JsonArray(listOf(add(f, "physics", physics().toJson(), "pendulum"), add(f, "motion", MotionClips.toJson(motion()), "animation"))))
        }
        val result = f.commands.execute(before.projectId, before.state, "Group", listOf(
            WorkspaceDocumentOperation("modifier_add_group", group),
            WorkspaceDocumentOperation("modifier_enable", buildJsonObject { put("id", "animation"); put("enabled", false) })), MutationAuthor.USER)
        assertTrue(result.applied)
        assertNotEquals(before.historyHead, result.capture.historyHead)
        assertEquals(listOf("recommended", "recommended"), stack(f).instances.map { it.presetGroup })
        assertFalse(stack(f).instances.last().enabled)
        assertTrue(RigIrCompiler.compile(f.now.model).clips.isEmpty())
        f.runtime.checkout(f.now.projectId, f.now.state, before.historyHead)
        assertTrue(stack(f).instances.isEmpty(), "one checkout must undo the whole batch")
    }

    @Test fun invalidBatchesCancellationAndStaleStatesPublishNoPrefix(): Unit = runBlocking<Unit> {
        val f = fixture()
        val before = f.now
        assertFailsWith<WorkspaceBatchEditException> {
            f.commands.execute(before.projectId, before.state, "Invalid", listOf(
                WorkspaceDocumentOperation("modifier_add", add(f, "motion", MotionClips.toJson(motion()), "valid")),
                WorkspaceDocumentOperation("modifier_move", buildJsonObject { put("id", "valid"); put("index", 99) })), MutationAuthor.USER)
        }
        assertEquals(before.state, f.now.state)
        assertEquals(before.historyHead, f.now.historyHead)
        f.edit("modifier_add", add(f, "motion", MotionClips.toJson(motion())))
        val current = f.now
        assertFailsWith<WorkspaceConflict> {
            f.commands.execute(before.projectId, before.state, "Stale", listOf(WorkspaceDocumentOperation("modifier_remove", id("m1"))), MutationAuthor.USER)
        }
        assertEquals(current.state, f.now.state)
        assertFailsWith<java.util.concurrent.CancellationException> {
            f.commands.execute(current.projectId, current.state, "Cancelled", listOf(WorkspaceDocumentOperation("modifier_remove", id("m1"))), MutationAuthor.USER,
                beforeCommit = { _, _, _ -> throw java.util.concurrent.CancellationException("cancel") })
        }
        assertEquals(current.historyHead, f.now.historyHead)
        assertEquals(current.revision, f.now.revision)
    }

    @Test fun canvasStagePreviewAndTargetAxesUseTheSameSnapshot(): Unit = runBlocking {
        val f = fixture(withWarp = true)
        val swing = RigSwingEdit.single("template", "Swing", SwingKind.LATERAL, listOf("warp"), listOf("P"))
        f.edit("modifier_add", add(f, "swing", swing.toJson(), "sway", ModifierTarget("deformer", "warp")))
        io.github.psd2live.ui.state.PSD2LiveViewModel().use { vm ->
            vm.setStateForTest(vm.state.value.copy(previewModel = f.now.model, rigEdits = f.now.document.rigEdits))
            kotlinx.coroutines.withTimeout(10_000) {
                androidx.compose.runtime.snapshotFlow { vm.uiState.value }.first { it.previewModel === f.now.model }
            }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                val editor = vm.canvasEditor
                editor.hierarchyMode = io.github.psd2live.ui.EditHierarchyMode.DEFORM
                editor.changeGeometryEditStage("base")
                assertNotNull(editor.displayPuppet)
                assertSame(editor.editSource, editor.displayPuppet)
                assertTrue(editor.target(deformerId = "warp")!!.geometry.axes.isEmpty())
                editor.changeGeometryEditStage("modifier:sway")
                assertSame(editor.editSource, editor.displayPuppet)
                assertEquals(1, editor.target(deformerId = "warp")!!.geometry.axes.size)
                assertTrue("modifier:sway" in editor.geometryEditStages())
                editor.changeGeometryEditStage("auto")
                assertNull(editor.displayPuppet)
                assertSame(f.now.model.rig.puppet, editor.editSource)
            }
        }
    }

    @Test fun vertexGestureFreezesIntermediatePoseAndReleasesItOnCancelAndMouseUp(): Unit = runBlocking {
        val f = fixture(withWarp = true)
        val swing = RigSwingEdit.single("template", "Swing", SwingKind.LATERAL, listOf("warp"), listOf("P"))
        f.edit("modifier_add", add(f, "swing", swing.toJson(), "sway", ModifierTarget("deformer", "warp")))
        val parameter = org.umamo.runtime.model.ParameterId(stack(f).instances.single().outputId("swing:lateral:0"))
        io.github.psd2live.ui.state.PSD2LiveViewModel().use { vm ->
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                vm.setStateForTest(vm.state.value.copy(previewModel = f.now.model,
                    rigEdits = f.now.document.rigEdits, selectedDeformerId = "warp",
                    parameterValues = mapOf(parameter to .35f),
                    historySnapshot = io.github.psd2live.project.WorkspaceHistorySnapshot("test", emptyList())))
                val editor = vm.canvasEditor
                editor.hierarchyMode = io.github.psd2live.ui.EditHierarchyMode.DEFORM
                editor.tool = io.github.psd2live.ui.CanvasTool.SELECT
                editor.setEditLevel(1)
                editor.snapDeformationToKeys = false
                val viewport = CanvasViewport(100.0, 200.0, 200.0, 500f, 500f)
                for (cancel in listOf(true, false)) {
                    val target = assertNotNull(editor.target())
                    val badges = editor.deformerCorners(viewport).values
                    val position = editor.screen(target.geometry.points, target, viewport).first { point ->
                        badges.none { it.contains(point.x.toDouble(), point.y.toDouble()) }
                    }
                    assertTrue(editor.press(position, viewport, false, false))
                    assertTrue(editor.inGesture)
                    assertEquals(.35f, assertNotNull(editor.capturedEditPose)[parameter])
                    vm.setParameterValues(mapOf(parameter to 0f))
                    assertEquals(.35f, editor.pose[parameter.raw])
                    assertEquals(.35f, vm.state.value.parameterValues[parameter])
                    val originalPoints = target.geometry.points.copyOf()
                    editor.move(position + androidx.compose.ui.geometry.Offset(10f, 0f), viewport, false)
                    assertNull(editor.error)
                    assertFalse(originalPoints.contentEquals(assertNotNull(editor.target()).geometry.points))
                    assertEquals(.35f, editor.pose[parameter.raw])
                    if (cancel) editor.cancel() else {
                        editor.move(position, viewport, false)
                        editor.release()
                    }
                    assertNull(editor.capturedEditPose)
                    assertFalse(editor.inGesture)
                    assertNull(editor.displayPuppet)
                    assertContentEquals(originalPoints, assertNotNull(editor.target()).geometry.points)
                }
            }
        }
    }

    @Test fun transformAndTemporaryWarpUseTheSharedPoseLockAndCancellation(): Unit = runBlocking {
        val f = fixture(withWarp = true)
        val swing = RigSwingEdit.single("template", "Swing", SwingKind.LATERAL, listOf("warp"), listOf("P"))
        f.edit("modifier_add", add(f, "swing", swing.toJson(), "sway", ModifierTarget("deformer", "warp")))
        val parameter = org.umamo.runtime.model.ParameterId(stack(f).instances.single().outputId("swing:lateral:0"))
        for (temporary in listOf(false, true)) io.github.psd2live.ui.state.PSD2LiveViewModel().use { vm ->
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                vm.setStateForTest(vm.state.value.copy(previewModel = f.now.model,
                    rigEdits = f.now.document.rigEdits, selectedDeformerId = "warp",
                    parameterValues = mapOf(parameter to .35f),
                    historySnapshot = io.github.psd2live.project.WorkspaceHistorySnapshot("test", emptyList())))
                val editor = vm.canvasEditor
                editor.hierarchyMode = io.github.psd2live.ui.EditHierarchyMode.DEFORM
                editor.setEditLevel(1)
                editor.snapDeformationToKeys = false
                val viewport = CanvasViewport(100.0, 200.0, 200.0, 500f, 500f)
                val target = assertNotNull(editor.target())
                val original = target.geometry.points.copyOf()
                editor.vertices = (0 until target.count).toSet()
                val position = if (temporary) {
                    editor.tool = io.github.psd2live.ui.CanvasTool.SELECT
                    editor.beginTemporaryWarp()
                    editor.temporaryWarpScreen(viewport)[4]
                } else {
                    editor.tool = io.github.psd2live.ui.CanvasTool.TRANSFORM
                    assertNotNull(editor.transformFrame(viewport)).bounds.at(androidx.compose.ui.geometry.Offset(1f, 1f))
                }
                assertTrue(editor.press(position, viewport, false, false))
                assertTrue(editor.inGesture)
                assertEquals(.35f, assertNotNull(editor.capturedEditPose)[parameter])
                vm.setParameterValues(mapOf(parameter to 0f))
                editor.move(position + androidx.compose.ui.geometry.Offset(10f, 0f), viewport, false)
                assertNull(editor.error)
                assertEquals(.35f, editor.pose[parameter.raw])
                assertFalse(original.contentEquals(assertNotNull(editor.target()).geometry.points))
                editor.cancel()
                assertNull(editor.capturedEditPose)
                assertFalse(editor.inGesture)
                assertContentEquals(original, assertNotNull(editor.target()).geometry.points)
                // This isolated editor has no workspace backend. Change the observed state directly to
                // verify that cancellation releases the pose used by display, picking and geometry.
                vm.setStateForTest(vm.state.value.copy(parameterValues = mapOf(parameter to .2f)))
                assertEquals(.2f, editor.pose[parameter.raw])
            }
        }
    }

    @Test fun pathAndBezierHandlesKeepTheSharedPoseDuringDeformation(): Unit = runBlocking {
        val f = fixture(withWarp = true, warpDivisions = 4)
        val drawable = f.now.model.rig.puppet.drawables.single()
        val mesh = requireNotNull(drawable.mesh)
        val path = org.umamo.runtime.model.DeformPath("test-path", drawable.id,
            mesh.indices.take(2).map { i -> DeformPathTools.bind(mesh.positions, mesh.indices, mesh.positions[i * 2], mesh.positions[i * 2 + 1]) },
            .2f, hardness = 50f, editLevel = 2)
        f.commands.executeJournal(f.now.projectId, f.now.state, "Path", JsonArray(listOf(DeformPathJournal.encode(path))), MutationAuthor.USER)
        val swing = RigSwingEdit.single("template", "Swing", SwingKind.LATERAL, listOf("warp"), listOf("P"))
        f.edit("modifier_add", add(f, "swing", swing.toJson(), "sway", ModifierTarget("deformer", "warp")))
        val parameter = org.umamo.runtime.model.ParameterId(stack(f).instances.single().outputId("swing:lateral:0"))
        val layer = f.now.model.rig.ownLayerIdByDrawableId.getValue(drawable.id.raw)
        for (bezier in listOf(false, true)) io.github.psd2live.ui.state.PSD2LiveViewModel().use { vm ->
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                vm.setStateForTest(vm.state.value.copy(previewModel = f.now.model, rigEdits = f.now.document.rigEdits,
                    selectedDeformerId = if (bezier) "warp" else null, selectedLayerId = layer,
                    parameterValues = mapOf(parameter to .35f),
                    historySnapshot = io.github.psd2live.project.WorkspaceHistorySnapshot("test", emptyList())))
                val editor = vm.canvasEditor
                editor.hierarchyMode = io.github.psd2live.ui.EditHierarchyMode.DEFORM
                editor.tool = io.github.psd2live.ui.CanvasTool.SELECT
                editor.setEditLevel(2)
                editor.pathLevel = 2
                editor.snapDeformationToKeys = false
                editor.parameter = parameter.raw
                val viewport = CanvasViewport(100.0, 200.0, 200.0, 500f, 500f)
                val target = assertNotNull(editor.target())
                val original = target.geometry.points.copyOf()
                val point = if (bezier) {
                    editor.ensureBezierState()
                    val handle = assertNotNull(editor.bezierState).handles.values.first()
                    floatArrayOf(handle.x, handle.y)
                } else {
                    val position = DeformPathTools.positions(editor.paths().single(), original).first()
                    floatArrayOf(position.first, position.second)
                }
                val position = editor.screen(point, target, viewport).first()
                editor.updateHover(position, viewport)
                if (bezier) assertNotNull(editor.hoveredBezierHandle)
                assertTrue(editor.press(position, viewport, false, false))
                if (!bezier) assertEquals(0, editor.pathPoint)
                assertEquals(.35f, assertNotNull(editor.capturedEditPose)[parameter])
                editor.move(position + androidx.compose.ui.geometry.Offset(10f, 0f), viewport, false)
                assertNull(editor.error)
                assertFalse(original.contentEquals(assertNotNull(editor.target()).geometry.points), "bezier=$bezier")
                assertEquals(.35f, editor.pose[parameter.raw])
                editor.cancel()
                assertNull(editor.capturedEditPose)
                assertContentEquals(original, assertNotNull(editor.target()).geometry.points)
            }
        }
    }

    @Test fun explicitGeometryStagesSeparateBaseIntermediateAndEndOfStackEdits(): Unit = runBlocking {
        val f = fixture(withWarp = true)
        val swing = RigSwingEdit.single("template", "Swing", SwingKind.LATERAL, listOf("warp"), listOf("P"))
        f.edit("modifier_add", add(f, "swing", swing.toJson(), "sway", ModifierTarget("deformer", "warp")))
        f.edit("modifier_add", add(f, "motion", MotionClips.toJson(motion()), "motion-owner"))
        val parameter = stack(f).instances.first().outputId("swing:lateral:0")
        suspend fun edit(stage: String, key: Map<String, Float>) {
            val source = GeometryEditStages.resolve(f.now.model, stage).puppet
            val points = RigGeometryTools.geometry(source, "warp", "warp", key).points
            val command = buildJsonObject {
                put("op", "canvas_geometry"); put("kind", "warp"); put("id", "warp"); put("edit_stage", stage)
                put("key", JsonObject(key.mapValues { JsonPrimitive(it.value) }))
                put("points", JsonArray(points.mapIndexed { i, value -> JsonPrimitive(if (i == 0) value + .1f else value) }))
            }
            val current = f.now
            f.commands.executeJournal(current.projectId, current.state, "Edit stage", JsonArray(listOf(command)), MutationAuthor.USER)
        }
        val baseJournal = f.now.document.rigEdits.authoringJournal.size
        edit("base", emptyMap())
        assertTrue(f.now.document.rigEdits.authoringJournal.size > baseJournal)
        assertTrue(stack(f).overrides.isEmpty())
        edit("modifier:sway", mapOf(parameter to .35f))
        assertEquals(setOf("sway"), stack(f).overrides.keys)
        edit("final", mapOf(parameter to .35f))
        assertEquals(setOf("sway", "motion-owner"), stack(f).overrides.keys)
        assertTrue(f.now.model.modifierEvaluation!!.entries.all { it.status == ModifierStatus.ACTIVE })
        assertEquals(RigIrCompiler.compile(f.now.model), RigIrCompiler.compile(f.builder.build(f.now.document)))
        val before = f.now
        assertFails { edit("base", mapOf(parameter to .35f)) }
        assertEquals(before.state, f.now.state)
        f.edit("modifier_apply", buildJsonObject { put("id", "motion-owner"); put("scope", "selected") })
        assertEquals(RigIrCompiler.compile(before.model), RigIrCompiler.compile(f.now.model))
        assertEquals(RigIrCompiler.compile(before.model), RigIrCompiler.compile(f.builder.build(f.now.document)))
        f.runtime.checkout(f.now.projectId, f.now.state, before.historyHead)
        f.edit("modifier_enable", buildJsonObject { put("id", "motion-owner"); put("enabled", false) })
        assertFails { edit("modifier:motion-owner", mapOf(parameter to .35f)) }
    }

    @Test fun generatedWarpCanBeEditedWhileKeepingUnkeyedChildrenInPlace(): Unit = runBlocking {
        val f = fixture(withWarp = true)
        val swing = RigSwingEdit.single("template", "Swing", SwingKind.LATERAL, listOf("warp"), listOf("P"))
        f.edit("modifier_add", add(f, "swing", swing.toJson(), "sway", ModifierTarget("deformer", "warp")))
        val parameter = stack(f).instances.single().outputId("swing:lateral:0")
        val key = mapOf(parameter to .35f)
        val pose = key.mapKeys { org.umamo.runtime.model.ParameterId(it.key) }
        val evaluator = org.umamo.render.eval.CpuDeformationEvaluator()
        fun assertSameAppearance(a: RigPreviewModel, b: RigPreviewModel) {
            val expected = evaluator.evaluate(a.rig.puppet, pose).worldPositions
            val actual = evaluator.evaluate(b.rig.puppet, pose).worldPositions
            // Child compensation uses the runtime warp inverse, whose world tolerance scales with lattice extent.
            val lattice = RigGeometryTools.geometry(b.rig.puppet, "warp", "warp", key).points
            val tolerance = kotlin.math.sqrt(org.umamo.render.eval.warpInverseToleranceSquared(lattice)) * 2f
            expected.forEach { (id, points) -> points.indices.forEach { i ->
                assertEquals(points[i], actual.getValue(id)[i], tolerance)
            } }
        }
        val before = f.now
        val points = RigGeometryTools.geometry(before.model.rig.puppet, "warp", "warp", key).points
        val command = buildJsonObject {
            put("op", "canvas_geometry"); put("kind", "warp"); put("id", "warp"); put("preserve_children", true)
            put("key", JsonObject(key.mapValues { JsonPrimitive(it.value) }))
            put("points", JsonArray(points.mapIndexed { i, v -> JsonPrimitive(v + if (i % 2 == 0) .1f else 0f) }))
        }
        f.commands.executeJournal(before.projectId, before.state, "Move parent", JsonArray(listOf(command)), MutationAuthor.USER)
        assertSameAppearance(before.model, f.now.model)
        assertFalse(points.contentEquals(RigGeometryTools.geometry(f.now.model.rig.puppet, "warp", "warp", key).points))
        val record = stack(f).overrides.getValue("sway").single()
        assertTrue(record.getValue("preserve_children").jsonPrimitive.boolean)
        val changed = swing.copy(motions = swing.motions.map { it.copy(shape = it.shape.copy(magnitude = .5f)) })
        f.edit("modifier_update", buildJsonObject { put("id", "sway"); put("config", changed.toJson()) })
        val uncorrected = f.builder.build(f.now.document.copy(rigEdits = f.now.document.rigEdits.copy(
            modifierStack = stack(f).copy(overrides = emptyMap()).toJson())))
        assertSameAppearance(uncorrected, f.now.model)
        assertEquals(RigIrCompiler.compile(f.now.model), RigIrCompiler.compile(f.builder.build(f.now.document)))
        val detached = uncorrected.rig.puppet.copy(drawables = uncorrected.rig.puppet.drawables.map { it.copy(parentDeformerId = null) })
        assertEquals(GeneratedOverrideOrphanReason.SHAPE_MISMATCH, GeneratedOverrides.apply(detached, record).issues.single().reason)
        val expected = RigIrCompiler.compile(f.now.model)
        f.edit("modifier_apply", buildJsonObject { put("id", "sway"); put("scope", "selected") })
        assertEquals(expected, RigIrCompiler.compile(f.now.model))
    }

    @Test fun impossibleChildCompensationRejectsTheWholeEdit(): Unit = runBlocking {
        val f = fixture(withWarp = true)
        val swing = RigSwingEdit.single("template", "Swing", SwingKind.LATERAL, listOf("warp"), listOf("P"))
        f.edit("modifier_add", add(f, "swing", swing.toJson(), "sway", ModifierTarget("deformer", "warp")))
        val parameter = stack(f).instances.single().outputId("swing:lateral:0")
        val before = f.now
        val geometry = RigGeometryTools.geometry(before.model.rig.puppet, "warp", "warp", mapOf(parameter to .35f))
        val collapsed = buildJsonObject {
            put("op", "canvas_geometry"); put("kind", "warp"); put("id", "warp"); put("preserve_children", true)
            put("key", buildJsonObject { put(parameter, .35f) })
            put("points", JsonArray(geometry.points.map { JsonPrimitive(0f) }))
        }
        assertFails {
            f.commands.executeJournal(before.projectId, before.state, "Collapsed parent", JsonArray(listOf(collapsed)), MutationAuthor.USER)
        }
        assertEquals(before.state, f.now.state)
        assertEquals(before.historyHead, f.now.historyHead)
        assertEquals(RigIrCompiler.compile(before.model), RigIrCompiler.compile(f.now.model))
    }

    @Test fun generatedWarpKeepsChildIndependentKeyformsInPlace(): Unit = runBlocking {
        val f = fixture(withWarp = true)
        val child = f.now.model.rig.puppet.drawables.single().id.raw
        val childPoints = RigGeometryTools.geometry(f.now.model.rig.puppet, "mesh", child, emptyMap()).points
        val childCommand = buildJsonObject {
            put("op", "canvas_geometry"); put("kind", "mesh"); put("id", child)
            put("key", buildJsonObject { put("ParamInput", 1f) })
            put("points", JsonArray(childPoints.mapIndexed { i, value -> JsonPrimitive(value + if (i % 2 == 0) .05f else 0f) }))
        }
        f.commands.executeJournal(f.now.projectId, f.now.state, "Child key", JsonArray(listOf(childCommand)), MutationAuthor.USER)
        val swing = RigSwingEdit.single("template", "Swing", SwingKind.LATERAL, listOf("warp"), listOf("P"))
        f.edit("modifier_add", add(f, "swing", swing.toJson(), "sway", ModifierTarget("deformer", "warp")))
        val parameter = stack(f).instances.single().outputId("swing:lateral:0")
        val key = mapOf(parameter to .35f)
        val pose = key.mapKeys { org.umamo.runtime.model.ParameterId(it.key) }
        val evaluator = org.umamo.render.eval.CpuDeformationEvaluator()
        fun assertSameAppearance(a: RigPreviewModel, b: RigPreviewModel) {
            for (value in (0..30).map { it / 30f }) {
            val childPose = pose + (org.umamo.runtime.model.ParameterId("ParamInput") to value)
            val expected = evaluator.evaluate(a.rig.puppet, childPose).worldPositions
            val actual = evaluator.evaluate(b.rig.puppet, childPose).worldPositions
            // Child compensation uses the runtime warp inverse, whose world tolerance scales with lattice extent.
            val lattice = RigGeometryTools.geometry(b.rig.puppet, "warp", "warp", key).points
            val tolerance = kotlin.math.sqrt(org.umamo.render.eval.warpInverseToleranceSquared(lattice)) * 2f
            expected.forEach { (id, points) -> points.indices.forEach { i ->
                assertEquals(points[i], actual.getValue(id)[i], tolerance, "child parameter=$value target=$id component=$i")
            } }
            }
        }
        val before = f.now
        val points = RigGeometryTools.geometry(before.model.rig.puppet, "warp", "warp", key).points
        val command = buildJsonObject {
            put("op", "canvas_geometry"); put("kind", "warp"); put("id", "warp"); put("preserve_children", true)
            put("key", JsonObject(key.mapValues { JsonPrimitive(it.value) }))
            put("points", JsonArray(points.mapIndexed { i, v -> JsonPrimitive(v + if (i % 2 == 0) .1f else 0f) }))
        }
        f.commands.executeJournal(before.projectId, before.state, "Move parent", JsonArray(listOf(command)), MutationAuthor.USER)
        assertSameAppearance(before.model, f.now.model)
        assertFalse(points.contentEquals(RigGeometryTools.geometry(f.now.model.rig.puppet, "warp", "warp", key).points))
        val record = stack(f).overrides.getValue("sway").single()
        assertTrue(record.getValue("preserve_children").jsonPrimitive.boolean)
        val changed = swing.copy(motions = swing.motions.map { it.copy(shape = it.shape.copy(magnitude = .5f)) })
        f.edit("modifier_update", buildJsonObject { put("id", "sway"); put("config", changed.toJson()) })
        val uncorrected = f.builder.build(f.now.document.copy(rigEdits = f.now.document.rigEdits.copy(
            modifierStack = stack(f).copy(overrides = emptyMap()).toJson())))
        assertSameAppearance(uncorrected, f.now.model)
        assertEquals(RigIrCompiler.compile(f.now.model), RigIrCompiler.compile(f.builder.build(f.now.document)))
        val detached = uncorrected.rig.puppet.copy(drawables = uncorrected.rig.puppet.drawables.map { it.copy(parentDeformerId = null) })
        assertEquals(GeneratedOverrideOrphanReason.SHAPE_MISMATCH, GeneratedOverrides.apply(detached, record).issues.single().reason)
        val expected = RigIrCompiler.compile(f.now.model)
        f.edit("modifier_apply", buildJsonObject { put("id", "sway"); put("scope", "selected") })
        assertEquals(expected, RigIrCompiler.compile(f.now.model))
    }

    @Test fun selectedApplicationSurvivesHistoryDiskReloadAndCmo3AtIntermediatePoses(): Unit = runBlocking {
        val f = fixture(withWarp = true)
        f.edit("modifier_add", add(f, "physics", physics().toJson(), "upstream"))
        val swing = RigSwingEdit.single("template", "Swing", SwingKind.LATERAL, listOf("warp"), listOf("P"))
        f.edit("modifier_add", add(f, "swing", swing.toJson(), "middle", ModifierTarget("deformer", "warp")))
        f.edit("modifier_add", add(f, "motion", MotionClips.toJson(motion()), "downstream"))
        val before = f.now
        val parameter = stack(f).instances[1].outputId("swing:lateral:0")
        val expected = RigIrCompiler.compile(before.model)
        f.edit("modifier_apply", buildJsonObject { put("id", "middle"); put("scope", "selected") })
        val applied = f.now
        assertEquals(listOf("upstream", "middle", "downstream"), stack(f).instances.map { it.id })
        assertEquals(expected, RigIrCompiler.compile(applied.model))
        f.runtime.checkout(f.now.projectId, f.now.state, before.historyHead)
        assertTrue(stack(f).appliedNodes.isEmpty())
        f.runtime.checkout(f.now.projectId, f.now.state, applied.historyHead)
        assertEquals(setOf("middle"), stack(f).appliedNodes.keys)
        val revision = WorkspaceRevisions.of(applied.document)
        val store = WorkspaceStore(temp.resolve("applied-project"))
        store.persistHistory("project", WorkspaceHistoryTree(applied.document, revision, revision).state())
        val reopened = WorkspaceStore(store.root).loadHistory("project")!!.head().snapshot
        val rebuilt = f.builder.build(reopened)
        assertEquals(expected, RigIrCompiler.compile(rebuilt))
        fun export(model: RigPreviewModel): org.umamo.runtime.model.PuppetModel {
            val converted = Cmo3Target().convert(RigIrCompiler.compile(model, tileArt = true), ExportOptions("applied"))
            val bytes = Cmo3.write(converted.model)
            assertTrue(bytes.isNotEmpty())
            return org.umamo.interop.cmo3.Cmo3Import.fromModelSource(Cmo3.read(bytes).root as org.umamo.format.cmo3.model.custom.CModelSource)
        }
        val exportedBefore = export(before.model)
        val exportedAfter = export(rebuilt)
        val evaluator = org.umamo.render.eval.CpuDeformationEvaluator()
        for (value in listOf(-1f, -.3f, 0f, .35f, 1f)) {
            val pose = mapOf(org.umamo.runtime.model.ParameterId(parameter) to value)
            for ((a, b) in listOf(before.model.rig.puppet to rebuilt.rig.puppet, exportedBefore to exportedAfter)) {
                val first = evaluator.evaluate(a, pose).worldPositions
                val second = evaluator.evaluate(b, pose).worldPositions
                assertEquals(first.keys, second.keys)
                first.forEach { (id, points) -> points.indices.forEach { i -> assertEquals(points[i], second.getValue(id)[i], .0001f) } }
            }
        }
    }

    @Test fun applyingAPrefixKeepsTheSuffixAndMakesPhysicsAndMotionOrdinaryEditableContent(): Unit = runBlocking<Unit> {
        val f = fixture()
        f.edit("modifier_add", add(f, "physics", physics().toJson(), "pendulum"))
        val original = motion().copy(fileStem = "legacy_sway", exportGroup = "Legacy", runtimeDefaultFades = true)
        f.edit("modifier_add", add(f, "motion", MotionClips.toJson(original), "first"))
        f.edit("modifier_add", add(f, "motion", MotionClips.toJson(motion("Later")), "later"))
        val before = f.now
        val expected = RigIrCompiler.compile(before.model)
        f.edit("modifier_apply", id("first"))
        assertEquals(listOf("later"), stack(f).instances.map { it.id })
        assertEquals(expected, RigIrCompiler.compile(f.now.model))
        assertEquals(1, f.now.document.rigEdits.physicsEdits.size)
        val clip = f.now.document.rigEdits.motionClips.single()
        assertEquals(expected.clips.first().file, clip.fileStem)
        assertEquals("legacy_sway", expected.clips.first().file)
        assertEquals("Legacy", expected.clips.first().group)
        assertNull(expected.clips.first().fadeIn)
        assertNull(expected.clips.first().fadeOut)
        assertNull(clip.builtin)
        assertEquals(expected, RigIrCompiler.compile(f.builder.build(f.now.document)))
        val applied = f.now
        f.runtime.checkout(f.now.projectId, f.now.state, before.historyHead)
        assertEquals(listOf("pendulum", "first", "later"), stack(f).instances.map { it.id })
        f.runtime.checkout(f.now.projectId, f.now.state, applied.historyHead)
        assertEquals(expected, RigIrCompiler.compile(f.now.model))
        f.edit("modifier_remove", id("later"))
        assertEquals(1, RigIrCompiler.compile(f.now.model).clips.size, "applied motion must remain ordinary content")
        val editedClip = clip.copy(name = "Authored", curves = listOf(MotionCurve("ParamInput", listOf(MotionKey(0f, .25f), MotionKey(1f, .25f)))))
        f.edit("motion_put", buildJsonObject { put("clip", JsonObject(MotionClips.toJson(editedClip) - "file")) })
        assertEquals("Authored", RigIrCompiler.compile(f.now.model).clips.single().name)
        assertEquals(clip.fileStem, f.now.document.rigEdits.motionClips.single().fileStem)
        f.edit("motion_duplicate", buildJsonObject { put("id", clip.id); put("new_id", "copied") })
        assertNull(f.now.document.rigEdits.motionClips.single { it.id == "copied" }.fileStem)
        assertEquals(2, RigIrCompiler.compile(f.now.model).clips.map { it.file }.distinct().size)
    }

    @Test fun failedApplyLeavesTheStackAndItsOverridesUntouched(): Unit = runBlocking<Unit> {
        val f = fixture()
        f.edit("modifier_add", add(f, "motion", MotionClips.toJson(motion()), "first"))
        val before = f.now
        val cancel = object : WorkspaceRasterWork {
            override fun checkpoint() { throw java.util.concurrent.CancellationException("cancel") }
            override fun progress(fraction: Float, message: String) {}
        }
        assertFailsWith<java.util.concurrent.CancellationException> {
            WorkspaceDocumentCommands(f.runtime, rasterWork = cancel).execute(before.projectId, before.state, "Apply", listOf(
                WorkspaceDocumentOperation("modifier_apply", id("first"))), MutationAuthor.USER)
        }
        assertEquals(before.state, f.now.state)
        assertEquals(before.document.rigEdits.modifierStack, f.now.document.rigEdits.modifierStack)
    }

    @Test fun capturedQueriesMatchTheirStrictSchemaAndDoNotFollowLaterChanges(): Unit = runBlocking<Unit> {
        val f = fixture()
        f.edit("modifier_add", add(f, "motion", MotionClips.toJson(motion()), "first"))
        val capture = f.now
        val observed = WorkspaceModifierQueries.describe(capture)
        validateOperationSchema(observed, WorkspaceModifierSchemas.stack, "result")
        assertEquals("first", observed.getValue("entries").jsonArray.single().jsonObject.getValue("id").jsonPrimitive.content)
        f.edit("modifier_remove", id("first"))
        assertEquals(observed, WorkspaceModifierQueries.describe(capture))
        assertTrue(WorkspaceModifierQueries.describe(f.now).getValue("entries").jsonArray.isEmpty())
    }

    @Test fun appliedSwingKeyformsRemainOrdinaryThroughColdReplay(): Unit = runBlocking<Unit> {
        val f = fixture(withWarp = true)
        val swing = RigSwingEdit.single("template", "Swing", SwingKind.LATERAL, listOf("warp"), listOf("P"))
        f.edit("modifier_add", add(f, "swing", swing.toJson(), "sway", ModifierTarget("deformer", "warp")))
        val expected = RigIrCompiler.compile(f.now.model)
        assertTrue((expected.deformers.single() as IrDeformer.Warp).lattice!!.axes.isNotEmpty())
        f.edit("modifier_apply", id("sway"))
        assertTrue(stack(f).instances.isEmpty())
        assertEquals(expected, RigIrCompiler.compile(f.now.model))
        assertEquals(expected, RigIrCompiler.compile(f.builder.build(f.now.document)))
    }

    private fun farPoints(f: Fixture): FloatArray {
        val grid = (f.now.model.rig.puppet.deformers.single() as org.umamo.runtime.model.Deformer.Warp).geometryGrid!!
        return grid.cells.single { it.coordinate.single() == grid.axes.single().keys.lastIndex }.form.controlPoints
    }

    @Test fun brushCorrectionFollowsModifierChangesAndSurvivesApplication(): Unit = runBlocking {
        correctionFollowsModifierChangesAndSurvivesApplication("brush")
    }

    @Test fun directGeometryEditFollowsModifierChangesAndSurvivesApplication(): Unit = runBlocking {
        correctionFollowsModifierChangesAndSurvivesApplication("direct")
    }

    @Test fun bezierCorrectionFollowsModifierChangesAndSurvivesApplication(): Unit = runBlocking {
        correctionFollowsModifierChangesAndSurvivesApplication("bezier")
    }

    @Test fun fixedDirectGeometryCorrectionKeepsEditedValuesAfterRegeneration(): Unit = runBlocking {
        correctionFollowsModifierChangesAndSurvivesApplication("direct", "absolute")
    }

    @Test fun fixedBrushCorrectionUsesTheSameMergePolicy(): Unit = runBlocking {
        correctionFollowsModifierChangesAndSurvivesApplication("brush", "absolute")
    }

    private suspend fun correctionFollowsModifierChangesAndSurvivesApplication(tool: String, mode: String = "delta") {
        val f = fixture(withWarp = true)
        val swing = RigSwingEdit.single("template", "Swing", SwingKind.LATERAL, listOf("warp"), listOf("P"))
        f.edit("modifier_add", add(f, "swing", swing.toJson(), "sway", ModifierTarget("deformer", "warp")))
        val parameter = stack(f).instances.single().outputId("swing:lateral:0")
        val pose = mapOf(parameter to .35f)
        fun points(model: RigPreviewModel = f.now.model) = RigGeometryTools.geometry(model.rig.puppet, "warp", "warp", pose).points
        val before = points().copyOf()
        if (tool != "brush") {
            val command = if (tool == "bezier") RigBezierJournal.prepare(f.now.model.rig.puppet,
                f.now.document.rigEdits, "anchor", buildJsonObject {
                    put("target", "warp:warp"); putJsonObject("coordinate") { put(parameter, .35f) }
                    put("row", 0); put("column", 0); put("x", before[0] + .1f); put("y", before[1])
                }) else buildJsonObject {
                put("op", "canvas_geometry"); put("kind", "warp"); put("id", "warp")
                putJsonObject("key") { put(parameter, .35f) }
                put("points", JsonArray(before.mapIndexed { i, value -> JsonPrimitive(if (i == 0) value + .1f else value) }))
            }
            val current = f.now
            f.commands.executeJournal(current.projectId, current.state, "Edit geometry", JsonArray(listOf(GeometryEditIntent.withMode(command, mode))), MutationAuthor.USER)
        } else f.edit("canvas_deform_stroke", buildJsonObject {
            put("correction_mode", mode)
            putJsonObject("stroke") {
                put("action", "brush"); put("mode", "deform"); put("radius", 100f); put("strength", 1f)
                putJsonObject("pose") { put(parameter, .35f) }
                putJsonArray("targets") { addJsonObject {
                    put("target", "warp:warp"); putJsonObject("key") { put(parameter, .35f) }
                } }
                putJsonArray("samples") {
                    addJsonObject { put("point", JsonArray(listOf(JsonPrimitive(before[0]), JsonPrimitive(before[1])))) }
                    addJsonObject { put("point", JsonArray(listOf(JsonPrimitive(before[0] + 2f), JsonPrimitive(before[1])))) }
                }
            }
        })
        val correction = stack(f).overrides.getValue("sway").single()
        assertEquals(mode, correction.getValue("merge_mode").jsonPrimitive.content)
        assertTrue(correction.getValue("insert_keys").jsonPrimitive.boolean)
        val delta = points().mapIndexed { i, value -> value - before[i] }
        assertTrue(delta.any { kotlin.math.abs(it) > .001f })
        val changed = swing.copy(motions = swing.motions.map { it.copy(shape = it.shape.copy(magnitude = .5f)) })
        f.edit("modifier_update", buildJsonObject { put("id", "sway"); put("type", "swing"); put("config", changed.toJson()) })
        val withoutCorrection = f.builder.build(f.now.document.copy(rigEdits = f.now.document.rigEdits.copy(
            modifierStack = stack(f).copy(overrides = emptyMap()).toJson())))
        val generated = points(withoutCorrection)
        points().forEachIndexed { i, value ->
            val editedPair = kotlin.math.abs(delta[i / 2 * 2]) > .0001f || kotlin.math.abs(delta[i / 2 * 2 + 1]) > .0001f
            val expectedValue = if (mode == "absolute" && editedPair) before[i] + delta[i] else generated[i] + if (mode == "delta") delta[i] else 0f
            assertEquals(expectedValue, value, .00001f)
        }
        val expected = RigIrCompiler.compile(f.now.model)
        f.edit("modifier_apply", id("sway"))
        assertEquals(expected, RigIrCompiler.compile(f.builder.build(f.now.document)))
    }

    private suspend fun overrideFarPoint(f: Fixture, target: String = "warp") {
        val parameter = stack(f).instances.single().outputId("swing:lateral:0")
        val before = farPoints(f)
        f.edit("keyform_apply", buildJsonObject {
            putJsonArray("changes") { addJsonObject {
                put("op", "set"); put("target", "warp:$target")
                putJsonObject("key") { put(parameter, 1f) }
                putJsonObject("geometry") {
                    put("controlPoints", JsonArray(before.mapIndexed { index, value -> JsonPrimitive(if (index == before.lastIndex - 1) value + .1f else value) }))
                }
            } }
        })
    }

    @Test fun generatedGeometryEditsBelongToTheInstanceSleepAndRestoreAndApplyWithIt(): Unit = runBlocking<Unit> {
        val f = fixture(withWarp = true)
        val swing = RigSwingEdit.single("template", "Swing", SwingKind.LATERAL, listOf("warp"), listOf("P"))
        f.edit("modifier_add", add(f, "swing", swing.toJson(), "sway", ModifierTarget("deformer", "warp")))
        val original = farPoints(f).copyOf()
        val journal = f.now.document.rigEdits.authoringJournal
        overrideFarPoint(f)
        val edited = f.now
        assertEquals(journal, edited.document.rigEdits.authoringJournal, "generated edits must not replay before the modifier")
        val overrides = stack(f).overrides.getValue("sway")
        assertEquals("modifier:sway", overrides.single().getValue("generator").jsonPrimitive.content)
        assertEquals(original[original.lastIndex - 1] + .1f, farPoints(f)[original.lastIndex - 1])
        assertEquals(RigIrCompiler.compile(edited.model), RigIrCompiler.compile(f.builder.build(edited.document)))
        f.edit("modifier_enable", buildJsonObject { put("id", "sway"); put("enabled", false) })
        assertEquals(overrides, stack(f).overrides.getValue("sway"))
        assertTrue((f.now.model.rig.puppet.deformers.single() as org.umamo.runtime.model.Deformer.Warp).geometryGrid!!.axes.isEmpty())
        f.edit("modifier_enable", buildJsonObject { put("id", "sway"); put("enabled", true) })
        assertEquals(RigIrCompiler.compile(edited.model), RigIrCompiler.compile(f.now.model))
        val active = f.now
        f.edit("modifier_remove", id("sway"))
        f.edit("modifier_add", add(f, "swing", swing.toJson(), "fresh", ModifierTarget("deformer", "warp")))
        assertEquals(original[original.lastIndex - 1], farPoints(f)[original.lastIndex - 1], "a new identity cannot inherit retired edits")
        assertEquals(overrides, stack(f).overrides.getValue("sway"))
        f.runtime.checkout(f.now.projectId, f.now.state, active.historyHead)
        f.edit("modifier_apply", id("sway"))
        assertTrue(stack(f).instances.isEmpty())
        assertTrue(stack(f).overrides.isEmpty())
        assertEquals(RigIrCompiler.compile(edited.model), RigIrCompiler.compile(f.now.model))
        assertEquals(RigIrCompiler.compile(edited.model), RigIrCompiler.compile(f.builder.build(f.now.document)))
        f.runtime.checkout(f.now.projectId, f.now.state, edited.historyHead)
        assertEquals(overrides, stack(f).overrides.getValue("sway"))
    }

    @Test fun anOverrideThatCannotMigrateIsKeptReportedAndPreventsApplyingItsPrefix(): Unit = runBlocking<Unit> {
        val f = fixture(withWarp = true)
        val swing = RigSwingEdit.single("template", "Swing", SwingKind.LATERAL, listOf("warp"), listOf("P"))
        f.edit("modifier_add", add(f, "swing", swing.toJson(), "sway", ModifierTarget("deformer", "warp")))
        overrideFarPoint(f)
        val overrides = stack(f).overrides.getValue("sway")
        val changed = swing.copy(motions = swing.motions.map { it.copy(parameterIds = listOf("P", "Q")) })
        f.edit("modifier_update", buildJsonObject { put("id", "sway"); put("type", "swing"); put("config", changed.toJson()) })
        val before = f.now
        val report = WorkspaceModifierQueries.describe(before)
        validateOperationSchema(report, WorkspaceModifierSchemas.stack, "result")
        val issue = report.getValue("entries").jsonArray.single().jsonObject.getValue("override_issues").jsonArray.single().jsonObject
        assertEquals("orphaned", issue.getValue("kind").jsonPrimitive.content)
        assertEquals("missing_keyform", issue.getValue("reason").jsonPrimitive.content)
        assertEquals(overrides, stack(f).overrides.getValue("sway"))
        assertFailsWith<WorkspaceBatchEditException> { f.edit("modifier_apply", id("sway")) }
        assertEquals(before.state, f.now.state)
        assertEquals(before.revision, f.now.revision)
        f.edit("modifier_update", buildJsonObject { put("id", "sway"); put("type", "swing"); put("config", swing.toJson()) })
        val recovered = WorkspaceModifierQueries.describe(f.now).getValue("entries").jsonArray.single().jsonObject
        assertTrue(recovered.getValue("override_issues").jsonArray.isEmpty())
    }

    @Test fun changingTheGeneratedFormMergesUneditedPointsAndReportsConflicts(): Unit = runBlocking<Unit> {
        val f = fixture(withWarp = true)
        val swing = RigSwingEdit.single("template", "Swing", SwingKind.LATERAL, listOf("warp"), listOf("P"))
        f.edit("modifier_add", add(f, "swing", swing.toJson(), "sway", ModifierTarget("deformer", "warp")))
        overrideFarPoint(f)
        val edited = farPoints(f).copyOf()
        val changed = swing.copy(motions = swing.motions.map { it.copy(shape = it.shape.copy(magnitude = .5f)) })
        f.edit("modifier_update", buildJsonObject { put("id", "sway"); put("type", "swing"); put("config", changed.toJson()) })
        val row = WorkspaceModifierQueries.describe(f.now).getValue("entries").jsonArray.single().jsonObject
        val issue = row.getValue("override_issues").jsonArray.single().jsonObject
        assertEquals("conflict", issue.getValue("kind").jsonPrimitive.content)
        assertEquals(edited[edited.lastIndex - 1], farPoints(f)[edited.lastIndex - 1])
        assertTrue(edited.indices.any { it < edited.lastIndex - 1 && edited[it] != farPoints(f)[it] }, "untouched points follow the new generation")
        val expected = RigIrCompiler.compile(f.now.model)
        f.edit("modifier_apply", id("sway"))
        assertEquals(expected, RigIrCompiler.compile(f.now.model))
    }

    @Test fun editingTheRestCellOfAnEntirelyGeneratedWrapperStaysWithItsInstance(): Unit = runBlocking<Unit> {
        val f = fixture()
        val swing = RigSwingEdit.single("template", "Swing", SwingKind.LATERAL, listOf(f.target.id), listOf("P"))
        f.edit("modifier_add", add(f, "swing", swing.toJson(), "sway"))
        val instance = stack(f).instances.single()
        val warp = f.now.model.rig.puppet.deformers.single() as org.umamo.runtime.model.Deformer.Warp
        val grid = warp.geometryGrid!!
        val neutral = (0..grid.axes.single().keys.lastIndex).single { grid.axes.single().keys[it] == 0f }
        val points = grid.cells.single { it.coordinate.single() == neutral }.form.controlPoints
        f.edit("keyform_apply", buildJsonObject {
            putJsonArray("changes") { addJsonObject {
                put("op", "set"); put("target", "warp:${warp.id.raw}")
                putJsonObject("key") { put(instance.outputId("swing:lateral:0"), 0f) }
                putJsonObject("geometry") { put("controlPoints", JsonArray(points.map { JsonPrimitive(it + .01f) })) }
            } }
        })
        assertEquals(1, stack(f).overrides.getValue("sway").size)
        val expected = RigIrCompiler.compile(f.now.model)
        f.edit("modifier_enable", buildJsonObject { put("id", "sway"); put("enabled", false) })
        assertTrue(f.now.model.rig.puppet.deformers.isEmpty())
        f.edit("modifier_enable", buildJsonObject { put("id", "sway"); put("enabled", true) })
        assertEquals(expected, RigIrCompiler.compile(f.now.model))
        f.edit("modifier_apply", id("sway"))
        assertEquals(expected, RigIrCompiler.compile(f.now.model))
    }

    @Test fun cancellationDuringModifierBakingPublishesNeitherTheSimulationNorEarlierGroupEntries(): Unit = runBlocking<Unit> {
        val f = fixture()
        val before = f.now
        var baking = false
        val work = object : WorkspaceRasterWork {
            override fun checkpoint() {}
            override fun progress(fraction: Float, message: String) {
                if (message.startsWith("Baking simulation:")) {
                    baking = true
                    throw java.util.concurrent.CancellationException("Stop bake")
                }
            }
        }
        val simulation = RigSimEdit("template", "Simulation", SimKind.CLOTH, listOf(f.target.id), inputs = listOf(PhysicsInput("ParamInput")))
        val operation = WorkspaceDocumentOperation("modifier_add_group", buildJsonObject {
            put("group", "cancelled"); put("entries", JsonArray(listOf(add(f, "motion", MotionClips.toJson(motion()), "first"),
                add(f, "simulation", simulation.toJson(), "simulation"))))
        })
        assertFailsWith<java.util.concurrent.CancellationException> {
            WorkspaceDocumentCommands(f.runtime, rasterWork = work).execute(before.projectId, before.state, "Cancelled bake", listOf(operation), MutationAuthor.USER)
        }
        assertTrue(baking, "cancellation must happen inside the expensive bake, after upstream preparation")
        assertEquals(before, f.now)
    }

    @Test fun missingTrainingClipsKeepSimulationDormantWithoutStartingABake(): Unit = runBlocking<Unit> {
        val f = fixture()
        val before = RigIrCompiler.compile(f.now.model)
        val source = RigSimEdit("template", "Simulation", SimKind.CLOTH, listOf(f.target.id),
            trainingClips = mapOf("missing-clip" to .5f))
        f.edit("modifier_add", add(f, "simulation", source.toJson(), "cloth"))
        val row = WorkspaceModifierQueries.describe(f.now).getValue("entries").jsonArray.single().jsonObject
        assertEquals("missing_dependency", row.getValue("status").jsonPrimitive.content)
        assertEquals(listOf("motion:missing-clip"), row.getValue("missing_dependencies").jsonArray.map { it.jsonPrimitive.content })
        assertNull(RigSimEdit.fromJson(stack(f).instances.single().config).bake)
        assertEquals(before, RigIrCompiler.compile(f.now.model))
        assertEquals(before, RigIrCompiler.compile(f.builder.build(f.now.document)))
    }

    @Test fun simulationBakesAreSummarizedAndApplyingKeepsTheirBindingsRatherThanASinglePose(): Unit = runBlocking<Unit> {
        val f = fixture()
        val source = RigSimEdit("template", "Simulation", SimKind.CLOTH, listOf(f.target.id), blendShapes = false)
        val input = RigModifierPipeline.input(f.now.model)
        val count = f.now.model.rig.puppet.drawables.single().mesh!!.vertexCount
        val axis = SimBakedAxis("Mode", floatArrayOf(-1f, 0f, 1f), mapOf(f.target.id to listOf(
            FloatArray(count * 2) { if (it % 2 == 0) -.25f else 0f }, FloatArray(count * 2),
            FloatArray(count * 2) { if (it % 2 == 0) .25f else 0f })))
        val baked = source.copy(bake = SimBakeResult(SimBake.fingerprint(PuppetIr.toPuppet(input), source), mapOf(f.target.id to count),
            emptyList(), listOf(SimBakedMode(axis, .25f, 1f)), physics = physics().copy(outputs = listOf(PhysicsOutput("Mode")))))
        f.edit("modifier_add", add(f, "simulation", baked.toJson(), "cloth"))
        val observation = WorkspaceModifierQueries.describe(f.now)
        validateOperationSchema(observation, WorkspaceModifierSchemas.stack, "result")
        val row = observation.getValue("entries").jsonArray.single().jsonObject
        assertTrue("bake" in row && "bake" !in row.getValue("config").jsonObject)
        assertNotNull(stack(f).instances.single().inputFingerprint)
        val unchanged = f.now
        f.edit("modifier_update", buildJsonObject { put("id", "cloth"); put("type", "simulation"); put("config", baked.copy(autoBake = false).toJson()) })
        val locked = f.now
        val upstream = JsonObject(add(f, "motion", MotionClips.toJson(motion()), "before") + ("index" to JsonPrimitive(0)))
        f.edit("modifier_add", upstream)
        assertEquals(listOf(ModifierStatus.ACTIVE, ModifierStatus.FAILED), f.now.model.modifierEvaluation!!.entries.map { it.status })
        assertFalse(stack(f).instances.single { it.id == "cloth" }.enabled)
        assertTrue(stack(f).initializationFailures.getValue("cloth").contains("valid bake"))
        f.runtime.checkout(f.now.projectId, f.now.state, locked.historyHead)
        f.edit("modifier_update", buildJsonObject { put("id", "cloth"); put("type", "simulation"); put("config", baked.toJson()) })
        assertEquals(unchanged.document.rigEdits.modifierStack, f.now.document.rigEdits.modifierStack)
        val expected = RigIrCompiler.compile(f.now.model)
        val live = RigIrCompiler.compile(f.now.model, simulations = true).advanced.simulations.single()
        val instance = stack(f).instances.single()
        assertEquals(instance.outputId("simulation"), live.id)
        assertEquals(listOf(instance.outputId("simulation:Mode")), live.parameters)
        assertEquals(listOf(instance.outputId("physics:0")), live.physicsGroups)
        assertEquals(listOf(f.target.id), live.targets.map { it.mesh })
        assertEquals(3, expected.meshes.single().offsets!!.axes.single().keys.size)
        f.edit("modifier_apply", id("cloth"))
        assertTrue(stack(f).instances.isEmpty())
        assertEquals(expected, RigIrCompiler.compile(f.now.model))
        assertEquals(expected, RigIrCompiler.compile(f.builder.build(f.now.document)))
    }

    @Test fun aMeshSwingOwnsItsWrapperPreservesTheRestPoseAndAppliesAsOrdinaryGeometry(): Unit = runBlocking<Unit> {
        val f = fixture()
        val drawable = org.umamo.runtime.model.DrawableId(f.target.id)
        val evaluator = org.umamo.render.eval.CpuDeformationEvaluator()
        val rest = evaluator.evaluate(f.now.model.rig.puppet, emptyMap()).worldPositions.getValue(drawable)
        val swing = RigSwingEdit.single("template", "Mesh swing", SwingKind.LATERAL, listOf(f.target.id), listOf("P"))
        f.edit("modifier_add", add(f, "swing", swing.toJson(), "mesh_sway"))
        val instance = stack(f).instances.single()
        val expected = RigIrCompiler.compile(f.now.model)
        assertEquals(1, expected.deformers.size)
        assertEquals(expected.deformers.single().id, expected.meshes.single().parent)
        val atRest = evaluator.evaluate(f.now.model.rig.puppet, emptyMap()).worldPositions.getValue(drawable)
        assertTrue(rest.indices.all { kotlin.math.abs(rest[it] - atRest[it]) < .001f })
        val parameter = org.umamo.runtime.model.ParameterId(instance.outputId("swing:lateral:0"))
        val moving = evaluator.evaluate(f.now.model.rig.puppet, mapOf(parameter to 1f)).worldPositions.getValue(drawable)
        assertTrue(rest.indices.any { kotlin.math.abs(rest[it] - moving[it]) > .001f })
        f.edit("modifier_apply", id("mesh_sway"))
        assertTrue(stack(f).instances.isEmpty())
        assertEquals(expected, RigIrCompiler.compile(f.now.model))
        assertEquals(expected, RigIrCompiler.compile(f.builder.build(f.now.document)))
    }

    @Test fun anExplicitBindingScaffoldKeepsAuthoredGeometryAndUpstreamParentsWithoutAddingMotion(): Unit = runBlocking<Unit> {
        val f = fixture(withWarp = true)
        val drawable = f.now.model.rig.puppet.drawables.single()
        val count = drawable.mesh!!.vertexCount
        f.edit("keyform_apply", buildJsonObject {
            putJsonArray("changes") { addJsonObject {
                put("op", "set"); put("target", "mesh:${drawable.id.raw}")
                putJsonObject("key") { put("ParamInput", 1f) }
                putJsonObject("geometry") { put("positionDeltas", JsonArray(List(count * 2) { JsonPrimitive(if (it % 2 == 0) .05f else 0f) })) }
            } }
        })
        val before = f.now.model.rig.puppet
        val evaluator = org.umamo.render.eval.CpuDeformationEvaluator()
        val poses = listOf(emptyMap(), mapOf(org.umamo.runtime.model.ParameterId("ParamInput") to 1f))
        val positions = poses.map { evaluator.evaluate(before, it).worldPositions.getValue(drawable.id) }
        f.edit("modifier_add", add(f, RigSourceModifiers.SCAFFOLD, JsonObject(emptyMap()), "structure"))
        val after = f.now.model.rig.puppet
        assertEquals(before.parameters, after.parameters, "a static scaffold cannot implicitly turn on motion parameters")
        assertTrue(after.deformers.size > before.deformers.size)
        assertTrue(after.deformers.any { it.id.raw == "warp" }, "the authored parent remains part of the evaluated model")
        val ids = after.deformers.filter { it.id.raw != "warp" }.map { it.id.raw }.toSet()
        assertTrue(ids.all { it.startsWith("modifier:structure:") })
        poses.forEachIndexed { index, pose ->
            val actual = evaluator.evaluate(after, pose).worldPositions.getValue(drawable.id)
            assertTrue(actual.indices.all { kotlin.math.abs(actual[it] - positions[index][it]) < .01f })
        }
        val expected = RigIrCompiler.compile(f.now.model)
        assertEquals(expected, RigIrCompiler.compile(f.builder.build(f.now.document)))
        f.edit("modifier_enable", buildJsonObject { put("id", "structure"); put("enabled", false) })
        assertEquals(setOf("warp"), f.now.model.rig.puppet.deformers.map { it.id.raw }.toSet())
        f.edit("modifier_enable", buildJsonObject { put("id", "structure"); put("enabled", true) })
        assertEquals(expected, RigIrCompiler.compile(f.now.model))
        f.edit("modifier_apply", id("structure"))
        assertEquals(expected, RigIrCompiler.compile(f.now.model))
        assertEquals(expected, RigIrCompiler.compile(f.builder.build(f.now.document)))
    }

    @Test fun scaffoldInstancesHaveIndependentTargetsAndApplyingThemPreservesASubsequentSwing(): Unit = runBlocking<Unit> {
        val f = fixture(layerCount = 2)
        val second = ModifierTarget("mesh", f.now.model.rig.puppet.drawables.last().id.raw)
        f.edit("modifier_add", add(f, RigSourceModifiers.SCAFFOLD, JsonObject(emptyMap()), "first_structure"))
        val first = f.now
        f.edit("modifier_add", add(f, RigSourceModifiers.SCAFFOLD, JsonObject(emptyMap()), "conflicting_structure"))
        assertEquals(ModifierStatus.FAILED, f.now.model.modifierEvaluation!!.entries.last().status)
        assertEquals(RigIrCompiler.compile(first.model), RigIrCompiler.compile(f.now.model))
        f.edit("modifier_remove", id("conflicting_structure"))
        f.edit("modifier_add", add(f, RigSourceModifiers.SCAFFOLD, JsonObject(emptyMap()), "second_structure", second))
        val swing = RigSwingEdit.single("template", "Swing", SwingKind.LATERAL, listOf(f.target.id), listOf("P"))
        f.edit("modifier_add", add(f, "swing", swing.toJson(), "sway"))
        val active = f.now
        val expected = RigIrCompiler.compile(active.model)
        f.edit("modifier_apply", id("second_structure"))
        assertEquals(listOf("sway"), stack(f).instances.map { it.id })
        assertEquals(expected, RigIrCompiler.compile(f.now.model))
        assertEquals(expected, RigIrCompiler.compile(f.builder.build(f.now.document)))
        f.runtime.checkout(f.now.projectId, f.now.state, active.historyHead)
        assertEquals(listOf("first_structure", "second_structure", "sway"), stack(f).instances.map { it.id })
        assertEquals(expected, RigIrCompiler.compile(f.now.model))
    }

    private fun body(scaffold: String, strength: Float = 1f) = buildJsonObject { put("scaffold", scaffold); put("strength", strength) }

    @Test fun bodyBindingMatchesTheExistingAlgorithmAndSurvivesApplyingItsScaffold(): Unit = runBlocking<Unit> {
        val f = fixture(layerCount = 2)
        val evaluator = org.umamo.render.eval.CpuDeformationEvaluator()
        val target = org.umamo.runtime.model.DrawableId(f.target.id)
        val untouched = f.now.model.rig.puppet.drawables.last().id
        val rest = evaluator.evaluate(f.now.model.rig.puppet, emptyMap()).worldPositions.getValue(untouched)
        f.edit("modifier_add", add(f, RigSourceModifiers.SCAFFOLD, JsonObject(emptyMap()), "structure"))
        f.edit("modifier_add", add(f, RigBodyModifier.TYPE, body("structure"), "body"))
        val source = RigModifierSource.of(f.now.model)
        val generated = RigBuilder.build(source.analysis, source.atlas, source.sourceConfig()).puppet
        val native = BindingProjection.retain(generated, RigBodyModifier.parameters, mapOf(StandardParameters.MOUTH_OPEN to 1f))
        val poses = listOf(emptyMap(), mapOf(StandardParameters.BODY_X to 8f), mapOf(StandardParameters.BODY_LEAN to -5f),
            mapOf(StandardParameters.BODY_X to 7f, StandardParameters.BODY_Z to -6f, StandardParameters.BODY_LEAN to 4f,
                StandardParameters.PROPORTION to -3f, StandardParameters.BREATH to .7f))
        val actualPuppet = f.now.model.rig.puppet
        val nativeMesh = native.drawables.single { it.id == target }
        val actualMesh = actualPuppet.drawables.single { it.id == target }
        fun canvas(model: org.umamo.runtime.model.PuppetModel, mesh: org.umamo.runtime.model.Drawable): org.umamo.runtime.model.DrawableMesh {
            val geometry = mesh.mesh!!
            val uv = RasterMeshJournal.TextureCoordinates(model, mesh).toCanvas(geometry.uvs)
            return org.umamo.runtime.model.DrawableMesh(uv, uv, geometry.indices)
        }
        val sources = RasterMeshJournal.prepare(canvas(native, nativeMesh), canvas(actualPuppet, actualMesh)).sources
        for (pose in poses) {
            val actual = evaluator.evaluate(actualPuppet, pose).worldPositions
            val expected = PrimitiveResidual.transfer(evaluator.evaluate(native, pose).worldPositions.getValue(target), sources)
            val points = actual.getValue(target)
            val error = points.indices.maxOf { kotlin.math.abs(points[it] - expected[it]) }
            assertTrue(error < .02f, "body binding differs from the native algorithm at $pose by $error")
            assertContentEquals(rest, actual.getValue(untouched), "target scope must not follow later selection or affect other meshes")
        }
        assertFalse(actualPuppet.parameters.any { it.id == StandardParameters.ANGLE_X || it.id == StandardParameters.MOUTH_OPEN })
        val expected = RigIrCompiler.compile(f.now.model)
        val active = f.now
        f.edit("modifier_apply", id("structure"))
        assertEquals(listOf("body"), stack(f).instances.map { it.id })
        assertEquals("structure", stack(f).appliedCapabilities.single().id)
        assertEquals(expected, RigIrCompiler.compile(f.now.model))
        assertEquals(expected, RigIrCompiler.compile(f.builder.build(f.now.document)))
        f.edit("modifier_apply", id("body"))
        assertTrue(stack(f).instances.isEmpty())
        assertEquals(expected, RigIrCompiler.compile(f.now.model))
        assertEquals(expected, RigIrCompiler.compile(f.builder.build(f.now.document)))
        f.runtime.checkout(f.now.projectId, f.now.state, active.historyHead)
        assertEquals(expected, RigIrCompiler.compile(f.now.model))
    }

    @Test fun bodyInstancesShareCompatibleStandardParametersAndKeepIndependentStrengths(): Unit = runBlocking<Unit> {
        val f = fixture(layerCount = 2)
        val second = ModifierTarget("mesh", f.now.model.rig.puppet.drawables.last().id.raw)
        f.edit("modifier_add", add(f, RigSourceModifiers.SCAFFOLD, JsonObject(emptyMap()), "first_structure"))
        f.edit("modifier_add", add(f, RigSourceModifiers.SCAFFOLD, JsonObject(emptyMap()), "second_structure", second))
        f.edit("modifier_add", add(f, RigBodyModifier.TYPE, body("first_structure", .5f), "first_body"))
        f.edit("modifier_add", add(f, RigBodyModifier.TYPE, body("second_structure", 1.5f), "second_body", second))
        val definitions = f.now.model.rig.puppet.parameters.filter { it.id in RigBodyModifier.parameters }
        assertEquals(RigBodyModifier.parameters, definitions.map { it.id }.toSet())
        assertEquals(definitions.size, definitions.map { it.id }.distinct().size)
        val row = WorkspaceModifierQueries.describe(f.now).getValue("entries").jsonArray.last().jsonObject
        validateOperationSchema(WorkspaceModifierQueries.describe(f.now), WorkspaceModifierSchemas.stack, "result")
        assertEquals(definitions.size, row.getValue("contributions").jsonArray.count { it.jsonObject["change"]?.jsonPrimitive?.content == "reused" })
        val before = f.now
        f.edit("modifier_add", add(f, RigBodyModifier.TYPE, body("first_structure"), "conflict"))
        assertEquals(ModifierStatus.FAILED, f.now.model.modifierEvaluation!!.entries.last().status)
        assertEquals(RigIrCompiler.compile(before.model), RigIrCompiler.compile(f.now.model))
        f.edit("modifier_remove", id("conflict"))
        val repaired = f.now
        assertFailsWith<WorkspaceBatchEditException> { f.edit("modifier_move", buildJsonObject { put("id", "first_body"); put("index", 0) }) }
        assertEquals(repaired.state, f.now.state)
        f.edit("modifier_update", buildJsonObject { put("id", "first_body"); put("type", RigBodyModifier.TYPE); put("config", body("first_structure", 1f)) })
        assertNotEquals(RigIrCompiler.compile(before.model), RigIrCompiler.compile(f.now.model))
    }

    @Test fun aBodyDependencyIsPinnedToItsProviderAndCanBeRepairedWithoutReplacingTheConsumer(): Unit = runBlocking<Unit> {
        val f = fixture()
        f.edit("modifier_add", add(f, RigSourceModifiers.SCAFFOLD, JsonObject(emptyMap()), "structure"))
        f.edit("modifier_add", add(f, RigBodyModifier.TYPE, body("structure"), "body"))
        val expected = RigIrCompiler.compile(f.now.model)
        f.edit("modifier_enable", buildJsonObject { put("id", "structure"); put("enabled", false) })
        var row = WorkspaceModifierQueries.describe(f.now).getValue("entries").jsonArray.last().jsonObject
        assertEquals("missing_dependency", row.getValue("status").jsonPrimitive.content)
        f.edit("modifier_enable", buildJsonObject { put("id", "structure"); put("enabled", true) })
        assertEquals(expected, RigIrCompiler.compile(f.now.model))
        f.edit("modifier_remove", id("structure"))
        f.edit("modifier_add", add(f, RigSourceModifiers.SCAFFOLD, JsonObject(emptyMap()), "replacement"))
        row = WorkspaceModifierQueries.describe(f.now).getValue("entries").jsonArray.first().jsonObject
        assertEquals("body", row.getValue("id").jsonPrimitive.content)
        assertEquals("missing_dependency", row.getValue("status").jsonPrimitive.content, "a new identity cannot silently replace a pinned provider")
        f.edit("modifier_move", buildJsonObject { put("id", "replacement"); put("index", 0) })
        f.edit("modifier_update", buildJsonObject { put("id", "body"); put("type", RigBodyModifier.TYPE); put("config", body("replacement")) })
        row = WorkspaceModifierQueries.describe(f.now).getValue("entries").jsonArray.last().jsonObject
        assertEquals("active", row.getValue("status").jsonPrimitive.content)
        assertEquals("body", stack(f).instances.last().id)
    }

    @Test fun deletingTheProviderOfAppliedContentIsRejectedWithoutChangingHistory(): Unit = runBlocking {
        val f = fixture()
        f.edit("modifier_add", add(f, RigSourceModifiers.SCAFFOLD, JsonObject(emptyMap()), "structure"))
        f.edit("modifier_add", add(f, RigBodyModifier.TYPE, body("structure"), "body"))
        f.edit("modifier_apply", buildJsonObject { put("id", "body"); put("scope", "selected") })
        val before = f.now
        for (operation in listOf("modifier_remove", "modifier_enable")) {
            assertFails {
                f.edit(operation, buildJsonObject { put("id", "structure"); if (operation == "modifier_enable") put("enabled", false) })
            }
            assertEquals(before.state, f.now.state)
            assertEquals(before.historyHead, f.now.historyHead)
            assertEquals(RigIrCompiler.compile(before.model), RigIrCompiler.compile(f.now.model))
        }
        // Applying the prerequisite prefix is the supported way to release the live provider.
        f.edit("modifier_apply", buildJsonObject { put("id", "body"); put("scope", "prefix") })
        assertTrue(stack(f).instances.isEmpty())
        assertEquals(RigIrCompiler.compile(before.model), RigIrCompiler.compile(f.now.model))
    }

    @Test fun deletingAnAppliedMotionInputParameterRejectsTheEntireDocumentEdit(): Unit = runBlocking {
        val f = fixture()
        f.edit("modifier_add", add(f, "motion", MotionClips.toJson(motion()), "motion-owner"))
        f.edit("modifier_apply", buildJsonObject { put("id", "motion-owner"); put("scope", "selected") })
        val before = f.now
        assertFails {
            f.edit("parameter_delete", buildJsonObject { put("parameter_id", "ParamInput") })
        }
        assertEquals(before.state, f.now.state)
        assertEquals(before.historyHead, f.now.historyHead)
        assertEquals(RigIrCompiler.compile(before.model), RigIrCompiler.compile(f.now.model))
    }

    @Test fun movingASwingAcrossBodyBindingChangesTheActualCombinedGeometry(): Unit = runBlocking<Unit> {
        val f = fixture()
        f.edit("modifier_add", add(f, RigSourceModifiers.SCAFFOLD, JsonObject(emptyMap()), "structure"))
        val root = f.now.model.rig.puppet.deformers.single { it.parent == null }.id.raw
        f.edit("modifier_add", add(f, RigBodyModifier.TYPE, body("structure"), "body"))
        val swing = RigSwingEdit.single("template", "Swing", SwingKind.LATERAL, listOf(root), listOf("P"))
            .let { it.copy(motions = it.motions.map { motion -> motion.copy(physics = null) }) }
        f.edit("modifier_add", add(f, "swing", swing.toJson(), "sway", ModifierTarget("deformer", root)))
        val evaluator = org.umamo.render.eval.CpuDeformationEvaluator()
        val pose = mapOf(StandardParameters.BODY_X to 8f, StandardParameters.BODY_LEAN to 4f, StandardParameters.PROPORTION to 5f,
            org.umamo.runtime.model.ParameterId(stack(f).instances.last().outputId("swing:lateral:0")) to 1f)
        val id = org.umamo.runtime.model.DrawableId(f.target.id)
        val before = evaluator.evaluate(f.now.model.rig.puppet, pose).worldPositions.getValue(id)
        f.edit("modifier_move", buildJsonObject { put("id", "sway"); put("index", 1) })
        val after = evaluator.evaluate(f.now.model.rig.puppet, pose).worldPositions.getValue(id)
        assertTrue(before.indices.any { kotlin.math.abs(before[it] - after[it]) > .001f }, "moving must change evaluation, not only presentation order")
        val expected = RigIrCompiler.compile(f.now.model)
        assertEquals(expected, RigIrCompiler.compile(f.builder.build(f.now.document)))
        f.edit("modifier_apply", id("sway"))
        assertEquals(listOf("body"), stack(f).instances.map { it.id })
        assertEquals(expected, RigIrCompiler.compile(f.now.model))
        assertEquals(expected, RigIrCompiler.compile(f.builder.build(f.now.document)))
    }

    @Test fun bodyValuesLiveInTheInstanceAndDoNotReadLegacyGlobalControls(): Unit = runBlocking<Unit> {
        val f = fixture()
        f.edit("modifier_add", add(f, RigSourceModifiers.SCAFFOLD, JsonObject(emptyMap()), "structure"))
        f.edit("modifier_add", add(f, RigBodyModifier.TYPE, body("structure"), "body"))
        val expected = RigIrCompiler.compile(f.now.model)
        val legacySettings = WorkspaceSettingsCodec.encode(f.now.model.config.copy(bodyStrength = 2f, headTurnStrength = 2f,
            rigTuning = RigTuning(breathLift = 9f, bodyZDegrees = 8f)))
        assertEquals(expected, RigIrCompiler.compile(f.builder.build(f.now.document.copy(settings = legacySettings))))
        val evaluator = org.umamo.render.eval.CpuDeformationEvaluator()
        val pose = mapOf(StandardParameters.BREATH to 1f)
        val target = org.umamo.runtime.model.DrawableId(f.target.id)
        val original = evaluator.evaluate(f.now.model.rig.puppet, pose).worldPositions.getValue(target)
        val config = JsonObject(body("structure") + ("tuning" to buildJsonObject { put("breathLift", 0f); put("breathWiden", 0f) }))
        f.edit("modifier_update", buildJsonObject { put("id", "body"); put("type", RigBodyModifier.TYPE); put("config", config) })
        val changed = evaluator.evaluate(f.now.model.rig.puppet, pose).worldPositions.getValue(target)
        assertTrue(original.indices.any { kotlin.math.abs(original[it] - changed[it]) > .001f })
        val before = f.now
        val invalid = JsonObject(config + ("tuning" to buildJsonObject { put("eyeFarShrink", 10f) }))
        assertFailsWith<WorkspaceBatchEditException> {
            f.edit("modifier_update", buildJsonObject { put("id", "body"); put("type", RigBodyModifier.TYPE); put("config", invalid) })
        }
        assertEquals(before.state, f.now.state)
        assertEquals(RigIrCompiler.compile(before.model), RigIrCompiler.compile(f.builder.build(before.document)))
        f.edit("modifier_apply", id("body"))
        assertEquals(RigIrCompiler.compile(before.model), RigIrCompiler.compile(f.now.model))
    }
}
