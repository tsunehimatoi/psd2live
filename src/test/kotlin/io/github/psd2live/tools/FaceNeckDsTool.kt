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

class FaceNeckDsTool {
    @Test fun compareWorkspaceNeck() = runBlocking {
        requireTools()
        val config = WorkspacePresetBaseline.configure(PipelineConfig(meshSpacing = 40), "modern")
        val source = PSD2LivePipeline().readPsd(Sample.fromEnvironment().path)
        val overlay = RigEditOverlay(modifierStack = RigModifierStack().toJson())
        val document = WorkspaceDocument(source, emptyMap(), emptySet(), emptyMap(), emptyMap(), overlay,
            WorkspaceSettingsCodec.encode(config.copy(rigEdits = overlay)))
        val (planned, defaultModel) = WorkspacePresetInitialization.prepare(document, WorkspaceRasterWork.Direct,
            WorkspaceInitialPreset.forMode(WorkspaceInitialModifiers.DEFAULT).copy(clothing = false))
        val stack = RigModifierStack.fromJson(assertNotNull(planned.rigEdits.modifierStack))
        val entry = stack.instances.single { it.type == "face.neck" }
        val versions = stack.instances.associate { it.type to it.version }
        assertEquals(4, entry.version, "The verified fresh DS cohort must select source neck v4")
        assertEquals(6, versions["mouth/fitted"])
        assertEquals(8, versions["mouth.outline"])
        assertEquals(BuiltinModifierPlugins.reference(entry.type, 4), entry.plugin)
        val cache = RigStageCache.enabled
        val cold = try {
            RigStageCache.enabled = false
            WorkspacePreviewBuilder().build(planned)
        } finally { RigStageCache.enabled = cache }
        assertEquals(stack, RigModifierStack.fromJson(assertNotNull(cold.config.rigEdits.modifierStack)))
        assertEquals(assertNotNull(defaultModel.modifierEvaluation).outputs, assertNotNull(cold.modifierEvaluation).outputs)
        assertEquals(RigIrCompiler.compile(defaultModel, tileArt = true), RigIrCompiler.compile(cold, tileArt = true))
        val index = stack.index(entry.id)
        suspend fun select(version: Int): RigPreviewModel {
            val reference = assertNotNull(BuiltinModifierPlugins.reference(entry.type, version))
            val selected = entry.copy(version = version, plugin = reference)
            val modified = stack.copy(instances = stack.instances.map { if (it.id == entry.id) selected else it })
            return WorkspacePreviewBuilder().build(planned.copy(rigEdits = planned.rigEdits.copy(modifierStack = modified.toJson())))
        }
        val v2Model = if (entry.version == 2) defaultModel else select(2)
        val expected = assertNotNull(v2Model.modifierEvaluation)
        val local = assertNotNull(expected.readAlgorithms[entry.id]).context
        val before = expected.outputs[index - 1]
        val after = expected.outputs[index]
        val touched = after.meshes.filter { mesh ->
            before.meshes.singleOrNull { it.id == mesh.id } != mesh
        }.map { it.id }
        val report = output("neck-ds").resolve("baseline.txt")
        report.writeText("Versions: $versions\nTouched meshes: $touched\nContext: ${local.resources.keys}\nFinal neck tracks: " +
            expected.outputs.last().meshes.filter { it.id in touched }.associate { it.id to it.shapes.map { shape -> shape.parameter } })
        assertEquals(listOf("ArtMeshNeck"), touched)
        assertTrue("source_meshes" in local.resources)
        val finalNeck = expected.outputs.last().meshes.single { it.id == touched.single() }
        assertTrue(finalNeck.shapes.any { it.parameter == "ParamAngleZ" }, "The final neck must retain the tilt")
        val historical = select(1)
        val legacy = assertNotNull(historical.modifierEvaluation)
        val compiled = RigIrCompiler.compile(historical, tileArt = true)
        for (version in listOf(2, 3, 4)) {
            val model = if (version == 2) v2Model else if (entry.version == version) defaultModel else select(version)
            val evaluated = assertNotNull(model.modifierEvaluation)
            val selected = evaluated.entries[index].instance
            assertEquals(version, selected.version)
            assertEquals(ModifierStatus.ACTIVE, evaluated.entries[index].status)
            val actual = evaluated.outputs[index]
            val stage = legacy.outputs.indices.firstOrNull { legacy.outputs[it] != evaluated.outputs[it] }
            val changes = actual.meshes.zip(legacy.outputs[index].meshes).filter { (a, b) -> a != b }.map { (a, b) ->
                val fields = a.javaClass.declaredFields.filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
                    .filter { it.isAccessible = true; it.get(a) != it.get(b) }.map { it.name }
                "${a.id}:$fields"
            }
            val complete = compiled == RigIrCompiler.compile(model, tileArt = true)
            report.appendText("\nV$version vs v1: first difference=$stage meshes=$changes complete=$complete")
            if (version >= 3) {
                assertNull(stage, "v$version differs at stage $stage: $changes")
                assertTrue(complete, "v$version final IR differs")
            }
            val artifact = assertNotNull(BundledAlgorithmArtifacts.find(entry.type, version))
            val capture = assertNotNull(evaluated.readAlgorithms[entry.id]).context
            report.appendText(" resources=" + capture.resources.keys)
            val root = Files.createTempDirectory("neck-ds-")
            val preferences = Preferences.userRoot().node("neck-ds/" + UUID.randomUUID())
            val store = ModifierPluginStore(root.resolve("installed"), preferences)
            try {
                BundledAlgorithmArtifacts.retain(store, listOf(artifact))
                val worker = store.snapshot().single()
                val source = RigModifierSource.of(model)
                val definition = when (version) {
                    2 -> RigNeckModifier.definition(source)
                    3 -> RigNeckModifier.foldedDefinition(source)
                    else -> RigNeckSourceModifier.definition(source)
                }
                if (version == 4) assertEquals(setOf("source_meshes"), capture.resources.keys)
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
}
