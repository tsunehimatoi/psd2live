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

class ExpressionGloomDsTool {
    @Test fun compareWorkspaceGloom() = runBlocking {
        requireTools()
        val config = WorkspacePresetBaseline.configure(PipelineConfig(meshSpacing = 40), "modern")
        val source = PSD2LivePipeline().readPsd(Sample.fromEnvironment().path)
        val overlay = RigEditOverlay(modifierStack = RigModifierStack().toJson())
        val document = WorkspaceDocument(source, emptyMap(), emptySet(), emptyMap(), emptyMap(), overlay,
            WorkspaceSettingsCodec.encode(config.copy(rigEdits = overlay)))
        val (planned, _) = WorkspacePresetInitialization.prepare(document, WorkspaceRasterWork.Direct,
            WorkspaceInitialPreset.forMode(WorkspaceInitialModifiers.DEFAULT))
        val stack = RigModifierStack.fromJson(assertNotNull(planned.rigEdits.modifierStack))
        val entry = stack.instances.single { it.type == "expression.gloom" }
        val cheek = stack.instances.single { it.type == "expression.cheek" }
        val versions = stack.instances.associate { it.type to it.version }
        val shared = mapOf("face/fitted" to 3, "eye/fitted" to 5, "brow/fitted" to 4, "mouth/fitted" to 6,
            "eye.smile" to 4, "eye.wide" to 4, "eye.gaze-lid" to 4, "eye.emotion" to 4,
            "eye.aftershock" to 5, "eye.squash" to 5, "brow.shape" to 5,
            "brow.lid-coupling" to 7, "brow.blink" to 5, "brow.spring" to 6)
        for ((type, version) in shared) assertEquals(version, versions[type], type)
        val index = stack.index(entry.id)
        fun select(version: Int): Pair<WorkspaceDocument, RigModifierInstance> {
            val reference = assertNotNull(BuiltinModifierPlugins.reference(entry.type, version))
            val chosen = entry.copy(version = version, plugin = reference)
            val cheekArtifact = assertNotNull(BundledAlgorithmArtifacts.find(cheek.type, 3))
            val modified = stack.copy(instances = stack.instances.map {
                when (it.id) {
                    entry.id -> chosen
                    cheek.id -> cheek.copy(version = 3, plugin = cheekArtifact.reference)
                    else -> it
                }
            })
            return planned.copy(rigEdits = planned.rigEdits.copy(modifierStack = modified.toJson())) to chosen
        }
        val historical = WorkspacePreviewBuilder().build(select(1).first)
        val legacyEvaluation = assertNotNull(historical.modifierEvaluation)
        val activeCheek = legacyEvaluation.entries[stack.index(cheek.id)]
        assertEquals(ModifierStatus.ACTIVE, activeCheek.status)
        assertEquals(3, activeCheek.instance.version)
        val expected = legacyEvaluation.outputs
        val oldMeshes = expected[index - 1].meshes.associateBy { it.id }
        val touched = expected[index].meshes.filter { it != oldMeshes[it.id] }.map { it.id }
        assertTrue(touched.isNotEmpty(), "The historic gloom stage must change the source face")
        output("gloom-ds").resolve("baseline.txt").writeText("Versions: ${versions + (cheek.type to 3)}\nTouched meshes: $touched")
        val (documentWithWorker, selected) = select(3)
        val model = WorkspacePreviewBuilder().build(documentWithWorker)
        val evaluated = assertNotNull(model.modifierEvaluation)
        assertEquals(ModifierStatus.ACTIVE, evaluated.entries[index].status)
        for (stage in stack.instances.indices) {
            val actual = evaluated.outputs[stage]
            assertTrue(expected[stage] == actual, "${stack.instances[stage].type}: IR differs")
        }
        val compiled = RigIrCompiler.compile(historical, tileArt = true)
        val candidate = RigIrCompiler.compile(model, tileArt = true)
        assertTrue(compiled == candidate, "compiled: IR differs")

        val artifact = assertNotNull(BundledAlgorithmArtifacts.find(entry.type, 3))
        val root = Files.createTempDirectory("gloom-ds-")
        val preferences = Preferences.userRoot().node("gloom-ds/" + UUID.randomUUID())
        val store = ModifierPluginStore(root.resolve("installed"), preferences)
        try {
            BundledAlgorithmArtifacts.retain(store, listOf(artifact))
            val worker = store.snapshot().single()
            val definition = RigGloomModifier.carrierDefinition(RigModifierSource.of(model))
            val providers = evaluated.entries.take(index).filter { it.status == ModifierStatus.ACTIVE &&
                it.instance.type in definition.requires + definition.optionalUpstream }.map { it.instance }
            val context = definition.authorContext(selected).withUpstream(providers.map {
                ModifierUpstreamCapability(it.type, it.type, it.version, it.targets, it.id, it.config, it.outputAliases, it.plugin)
            })
            for ((type, version) in shared + mapOf("mouth.outline" to versions.getValue("mouth.outline"), cheek.type to 3)) {
                val provider = context.upstream.single { it.type == type }
                assertEquals(version, provider.version)
                assertEquals(evaluated.entries.single { it.instance.type == type }.instance.plugin, provider.plugin)
            }
            val replay = ModifierPluginWorkers.evaluate(worker, worker.manifest.algorithms.single(), expected[index - 1],
                selected, {}, store, context = context, declaredParameters = definition.parameters(selected))
            assertTrue(expected[index] == replay, "installed JVM: IR differs")
        } finally {
            ModifierPluginWorkers.stop(artifact.reference)
            preferences.removeNode()
            ModifierPluginStore.deleteTree(root)
        }
    }
}
