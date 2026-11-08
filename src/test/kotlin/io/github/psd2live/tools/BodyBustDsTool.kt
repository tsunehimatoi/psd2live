package io.github.psd2live.tools

import io.github.psd2live.application.*
import io.github.psd2live.core.*
import io.github.psd2live.core.modifier.*
import io.github.psd2live.project.*
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.*

class BodyBustDsTool {
    @Test fun compareWorkspaceBust() = runBlocking {
        requireTools()
        val config = WorkspacePresetBaseline.configure(PipelineConfig(meshSpacing = 40), "modern")
        val source = PSD2LivePipeline().readPsd(Sample.fromEnvironment().path)
        val overlay = RigEditOverlay(modifierStack = RigModifierStack().toJson())
        val document = WorkspaceDocument(source, emptyMap(), emptySet(), emptyMap(), emptyMap(), overlay,
            WorkspaceSettingsCodec.encode(config.copy(rigEdits = overlay)))
        val (defaultDocument, _) = WorkspacePresetInitialization.prepare(document, WorkspaceRasterWork.Direct,
            WorkspaceInitialPreset.forMode(WorkspaceInitialModifiers.DEFAULT).copy(clothing = false))
        val current = RigModifierStack.fromJson(assertNotNull(defaultDocument.rigEdits.modifierStack))
        val explicit = mapOf("mouth.jaw" to 8, "mouth.cheek-lid" to 8)
        val stack = current.copy(instances = current.instances.map { instance ->
            val version = explicit[instance.type] ?: return@map instance
            val artifact = assertNotNull(BundledAlgorithmArtifacts.find(instance.type, version))
            instance.copy(version = version, plugin = artifact.reference)
        })
        val planned = defaultDocument.copy(rigEdits = defaultDocument.rigEdits.copy(modifierStack = stack.toJson()))
        val entry = stack.instances.single { it.type == "body.bust" }
        val versions = stack.instances.associate { it.type to it.version }
        val mouth = mapOf("mouth/fitted" to 6, "mouth.outline" to 8, "mouth.vowel" to 7, "mouth.capture" to 6) + explicit
        for ((type, version) in mouth) {
            val instance = stack.instances.single { it.type == type }
            assertEquals(version, instance.version, type)
            assertEquals(assertNotNull(BundledAlgorithmArtifacts.find(type, version)).reference, instance.plugin, type)
        }
        assertTrue(entry.version in setOf(2, 3))
        val index = stack.index(entry.id)
        suspend fun select(version: Int): RigPreviewModel {
            val reference = assertNotNull(BuiltinModifierPlugins.reference(entry.type, version))
            val selected = entry.copy(version = version, plugin = reference)
            val modified = stack.copy(instances = stack.instances.map { if (it.id == entry.id) selected else it })
            return WorkspacePreviewBuilder().build(planned.copy(rigEdits = planned.rigEdits.copy(modifierStack = modified.toJson())))
        }
        val v2Model = select(2)
        val expected = assertNotNull(v2Model.modifierEvaluation)
        val local = assertNotNull(expected.readAlgorithms[entry.id]).context
        assertTrue("source_meshes" in local.resources)
        for (type in mouth.keys) assertEquals(ModifierStatus.ACTIVE,
            expected.entries.single { it.instance.type == type }.status, type)
        val before = expected.outputs[index - 1]
        val after = expected.outputs[index]
        val touched = after.meshes.filter { mesh ->
            before.meshes.singleOrNull { it.id == mesh.id } != mesh
        }.map { it.id }
        assertTrue(touched.isNotEmpty() || before.physics != after.physics)
        val report = output("bust-ds").resolve("baseline.txt")
        report.writeText("Versions: $versions\nTouched meshes: $touched\nPhysics added: " +
            (after.physics.groups.map { it.id } - before.physics.groups.map { it.id }.toSet()))
        val historical = select(1)
        val legacy = assertNotNull(historical.modifierEvaluation)
        val legacyMesh = legacy.outputs[index].meshes.single { it.id == "ArtMeshTopwear" }
        val compiled = RigIrCompiler.compile(historical, tileArt = true)
        for (version in listOf(2, 3)) {
            val model = if (version == 2) v2Model else select(3)
            val evaluated = assertNotNull(model.modifierEvaluation)
            val selected = evaluated.entries[index].instance
            assertEquals(ModifierStatus.ACTIVE, evaluated.entries[index].status)
            val actual = evaluated.outputs[index]
            val stage = legacy.outputs.indices.firstOrNull { legacy.outputs[it] != evaluated.outputs[it] }
            val mesh = actual.meshes.single { it.id == legacyMesh.id }
            val differentFields = mesh.javaClass.declaredFields.filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
                .filter { it.isAccessible = true; it.get(mesh) != it.get(legacyMesh) }.map { it.name }
            val complete = compiled == RigIrCompiler.compile(model, tileArt = true)
            report.appendText("\nV$version vs v1: first difference=$stage fields=$differentFields complete=$complete")
            if (version == 3) {
                assertNull(stage, "v3 stage mismatch at $stage, topwear fields $differentFields")
                assertTrue(complete, "v3 final IR mismatch")
            }
            val artifact = BundledAlgorithmArtifacts.find(entry.type, version) ?: continue
            val capture = assertNotNull(evaluated.readAlgorithms[entry.id]).context
            report.appendText(" resources=" + capture.resources.keys)
            val root = Files.createTempDirectory("bust-ds-")
            val preferences = Preferences.userRoot().node("bust-ds/" + UUID.randomUUID())
            val store = ModifierPluginStore(root.resolve("installed"), preferences)
            try {
                BundledAlgorithmArtifacts.retain(store, listOf(artifact))
                val worker = store.snapshot().single()
                val source = RigModifierSource.of(model)
                val definition = if (version == 2) RigBustModifier.definition(source) else RigBustModifier.fittedDefinition(source)
                val replay = ModifierPluginWorkers.evaluate(worker, worker.manifest.algorithms.single(), evaluated.outputs[index - 1],
                    selected, {}, store, context = capture, declaredParameters = definition.parameters(selected))
                assertTrue(actual == replay, "installed JVM v$version: IR differs")
            } finally {
                ModifierPluginWorkers.stop(artifact.reference)
                preferences.removeNode()
                ModifierPluginStore.deleteTree(root)
            }
        }
    }

    @Test fun compareLayeredMouthBust(): Unit = runBlocking {
        requireTools()
        val source = PSD2LivePipeline().readPsd(Sample.fromEnvironment().path)
        val config = PipelineConfig(meshSpacing = 40, rigEdits = RigEditOverlay(modifierStack = RigModifierStack().toJson()))
        val document = layeredMouthDocument(source, config)
        val versions = mapOf("face/fitted" to 3, "eye/fitted" to 5, "brow/fitted" to 4, "mouth/fitted" to 8,
            "eye.smile" to 4, "eye.wide" to 4, "eye.gaze-lid" to 4, "eye.emotion" to 4, "eye.aftershock" to 5,
            "eye.squash" to 5, "brow.shape" to 5, "brow.lid-coupling" to 7, "brow.blink" to 5, "brow.spring" to 6,
            "mouth.outline" to 11, "mouth.vowel" to 8, "mouth.capture" to 7, "mouth.jaw" to 1,
            "mouth.cheek-lid" to 1, "mouth.spring" to 1, "body.bust" to 1)
        val (planned, historical) = WorkspacePresetInitialization.prepare(document, WorkspaceRasterWork.Direct,
            WorkspaceInitialPreset(clothing = false), requestedVersions = versions)
        val stack = RigModifierStack.fromJson(assertNotNull(planned.rigEdits.modifierStack))
        val evaluation = assertNotNull(historical.modifierEvaluation)
        for ((type, version) in versions) {
            val provider = evaluation.entries.single { it.instance.type == type }
            assertEquals(version, provider.instance.version, type)
            assertEquals(ModifierStatus.ACTIVE, provider.status, type)
        }
        val fit = assertNotNull(RigBuilder.rigContext(historical.analysis, config).fittedMouth(config))
        assertTrue(fit.layered && fit.rig?.fit?.layered != null)
        val entry = stack.instances.single { it.type == "body.bust" }
        val index = stack.index(entry.id)
        for (type in listOf("mouth/fitted", "mouth.outline", "mouth.vowel", "mouth.capture")) {
            val upstream = stack.instances.single { it.type == type }
            assertTrue(stack.index(upstream.id) < index)
            assertEquals(assertNotNull(BundledAlgorithmArtifacts.find(type, upstream.version)).reference, upstream.plugin)
        }
        assertTrue(entry.outputAliases.isEmpty())
        val before = evaluation.outputs[index - 1]
        val after = evaluation.outputs[index]
        val prior = before.meshes.associateBy { it.id }
        val changed = after.meshes.filter { it != prior[it.id] }
        val axes = setOf("ParamBustY", "ParamBustX")
        assertTrue(changed.any { mesh ->
            mesh.visible && mesh.opacity > 0f && mesh.shapes.any { binding ->
                binding.parameter in axes && binding.shapes.filterNotNull().any { shape ->
                    shape.deltas.shared().any { it != 0f }
                }
            }
        })
        val physics = after.physics.groups.filterNot { it in before.physics.groups }
        assertEquals(axes, physics.flatMap { group -> group.outputs.map { it.parameter } }.toSet())
        assertEquals(listOf("ArtMeshTopwear"), changed.map { it.id })

        val artifact = assertNotNull(BundledAlgorithmArtifacts.find(entry.type, 3))
        val selected = entry.copy(version = 3, plugin = artifact.reference)
        val next = stack.copy(instances = stack.instances.map { if (it.id == entry.id) selected else it })
        val candidate = planned.copy(rigEdits = planned.rigEdits.copy(modifierStack = next.toJson()))
        val model = WorkspacePreviewBuilder().build(candidate)
        val actual = assertNotNull(model.modifierEvaluation)
        assertEquals(ModifierStatus.ACTIVE, actual.entries[index].status)
        for (stage in stack.instances.indices) {
            assertTrue(evaluation.outputs[stage] == actual.outputs[stage], stack.instances[stage].type)
        }
        assertTrue(RigIrCompiler.compile(historical, tileArt = true) == RigIrCompiler.compile(model, tileArt = true),
            "v3 final IR differs")

        val capture = assertNotNull(actual.readAlgorithms[entry.id]).context
        assertTrue(capture.resources.keys.containsAll(setOf("bustBefore", "bustAfter")))
        val root = Files.createTempDirectory("bust-layered-ds-")
        val preferences = Preferences.userRoot().node("bust-layered-ds/" + UUID.randomUUID())
        val store = ModifierPluginStore(root.resolve("installed"), preferences)
        try {
            BundledAlgorithmArtifacts.retain(store, listOf(artifact))
            val worker = store.snapshot().single()
            val definition = RigBustModifier.fittedDefinition(RigModifierSource.of(model))
            assertTrue(after == ModifierPluginWorkers.evaluate(worker, worker.manifest.algorithms.single(), before,
                selected, {}, store, context = capture, declaredParameters = definition.parameters(selected)),
                "installed JVM IR differs")
        } finally {
            ModifierPluginWorkers.stop(artifact.reference)
            preferences.removeNode()
            ModifierPluginStore.deleteTree(root)
        }
    }
}
