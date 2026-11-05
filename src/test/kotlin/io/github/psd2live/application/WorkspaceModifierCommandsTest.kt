package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.core.modifier.*
import io.github.psd2live.core.sim.*
import io.github.psd2live.project.*
import io.github.psd2live.targets.cubism.PuppetIr
import io.github.psd2live.format.model.Deformer as IrDeformer
import io.github.psd2live.format.model.Floats
import io.github.psd2live.format.model.KeyGrid
import io.github.psd2live.format.model.LatticePoints
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
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

    private suspend fun fixture(withWarp: Boolean = false, layerCount: Int = 1,
                                additionalParameters: List<RigParameterEdit> = emptyList(), physicsOrder: List<String> = emptyList()): Fixture {
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
            val warp = IrDeformer.Warp("warp", "Warp", null, null, 1, 1, true, KeyGrid.single(
                LatticePoints(Floats.of(floatArrayOf(left, top, left + width, top, left, top + height, left + width, top + height)))))
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
        assertEquals(listOf("rig.scaffold", "body/legacy", "face/legacy", "hair/legacy", "physics"),
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

    @Test fun invalidNativePlansAndConflictingOutputsLeaveNoPartialCommit(): Unit = runBlocking<Unit> {
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
        assertFails { f.commands.previewRegeneration(before.projectId, before.state, listOf(operation)) }
        assertEquals(before, f.now)
        assertFails { f.commands.execute(before.projectId, before.state, "Conflict", listOf(operation), MutationAuthor.USER) }
        assertEquals(before, f.now)
        val malformed = JsonObject(request + ("config" to buildJsonObject { put("id", "invalid") }))
        assertFails { validateOperationSchema(malformed, WorkspaceModifierSchemas.planRequest) }
        assertEquals(before, f.now)
    }

    @Test fun sourcePlansContainTheCompleteNativePrerequisites(): Unit = runBlocking<Unit> {
        val f = fixture()
        val before = f.now
        for ((type, expected) in listOf(
            "mouth.tongue" to listOf("rig.scaffold", "mouth/fitted", "mouth.capture", "mouth.tongue"),
            "mouth.spring" to listOf("rig.scaffold", "mouth/fitted", "mouth.jaw", "mouth.spring"),
            "mouth.interior-depth" to listOf("rig.scaffold", "mouth/fitted", "face/legacy", "mouth.interior-depth"),
            "expression.cheek" to listOf("rig.scaffold", "expression.cheek"),
            "face.skin-clip" to listOf("rig.scaffold", "face.skin-clip"),
            "face.neck" to listOf("rig.scaffold", "face/legacy", "face.neck"),
            "face.depth-turn" to listOf("rig.scaffold", "face/legacy", "face.depth-turn"),
            "hair/chains" to listOf("rig.scaffold", "body/legacy", "face/legacy", "hair/chains"),
            "body.bust" to listOf("rig.scaffold", "body/legacy", "face/legacy", "body.bust"),
            "outfit.ribbons" to listOf("rig.scaffold", "body/legacy", "face/legacy", "outfit.ribbons"))) {
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

    private suspend fun overrideFarPoint(f: Fixture) {
        val parameter = stack(f).instances.single().outputId("swing:lateral:0")
        val before = farPoints(f)
        f.edit("keyform_apply", buildJsonObject {
            putJsonArray("changes") { addJsonObject {
                put("op", "set"); put("target", "warp:warp")
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
        assertFails { f.edit("modifier_add", upstream) }
        assertEquals(locked, f.now, "an outdated disabled-auto-bake must reject the entire candidate")
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
        assertFailsWith<WorkspaceBatchEditException> {
            f.edit("modifier_add", add(f, RigSourceModifiers.SCAFFOLD, JsonObject(emptyMap()), "conflicting_structure"))
        }
        assertEquals(first.state, f.now.state)
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
        val native = RigModifierSource.of(f.now.model).body(1f) {}
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
        assertFailsWith<WorkspaceBatchEditException> { f.edit("modifier_add", add(f, RigBodyModifier.TYPE, body("first_structure"), "conflict")) }
        assertEquals(before.state, f.now.state)
        assertFailsWith<WorkspaceBatchEditException> { f.edit("modifier_move", buildJsonObject { put("id", "first_body"); put("index", 0) }) }
        assertEquals(before.state, f.now.state)
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
