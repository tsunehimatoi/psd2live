package io.github.psd2live.tools

import io.github.psd2live.application.CliPresetBindings
import io.github.psd2live.application.WorkspaceInitialPreset
import io.github.psd2live.application.WorkspacePresetInitialization
import io.github.psd2live.application.WorkspacePreviewBuilder
import io.github.psd2live.application.WorkspaceRasterWork
import io.github.psd2live.core.*
import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.project.WorkspaceDocument
import io.github.psd2live.project.WorkspaceSettingsCodec
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.*

/** Compares the source iris squash under refitted shared head frames. */
class EyeSquashSourceTool {
    @Test fun compareSharedFrameSquash(): Unit = runBlocking {
        requireTools()
        val config = PipelineConfig(meshSpacing = 64)
        val source = PSD2LivePipeline().readPsd(Sample.fromEnvironment().path)
        val overlay = config.rigEdits.copy(modifierStack = RigModifierStack().toJson())
        val document = WorkspaceDocument(source, emptyMap(), emptySet(), emptyMap(), emptyMap(), overlay,
            WorkspaceSettingsCodec.encode(config.copy(rigEdits = overlay)))
        val analysis = RigGenerationSource.prepare(RigGenerationSource.analyze(source, config), config)
        val versions = mapOf("face/fitted" to 3, "eye/fitted" to 5, "brow/fitted" to 4,
            "eye.smile" to 4, "eye.wide" to 4, "eye.gaze-lid" to 4, "eye.emotion" to 4, "eye.aftershock" to 1, "eye.squash" to 1)
        val (planned, reference) = WorkspacePresetInitialization.prepare(document, WorkspaceRasterWork.Direct,
            WorkspaceInitialPreset(clothing = false), requestedVersions = versions, cliBindings = CliPresetBindings(analysis, config))
        val stack = RigModifierStack.fromJson(assertNotNull(planned.rigEdits.modifierStack))
        assertEquals(versions, stack.instances.filter { it.type in versions }.associate { it.type to it.version })
        val squash = stack.instances.single { it.type == "eye.squash" }
        val index = stack.index(squash.id)
        val expected = assertNotNull(reference.modifierEvaluation).outputs
        val oldMeshes = expected[index - 1].meshes.associateBy { it.id }
        for (id in listOf("ArtMeshIridesL", "ArtMeshIridesR")) {
            assertNotEquals(oldMeshes.getValue(id), expected[index].meshes.single { it.id == id })
        }
        fun candidate(version: Int): WorkspaceDocument {
            val artifact = assertNotNull(BundledAlgorithmArtifacts.find(squash.type, version))
            val modified = stack.copy(instances = stack.instances.map {
                if (it.id == squash.id) it.copy(version = version, plugin = artifact.reference) else it
            })
            return planned.copy(rigEdits = planned.rigEdits.copy(modifierStack = modified.toJson()))
        }
        val previous = assertNotNull(WorkspacePreviewBuilder().build(candidate(4)).modifierEvaluation).outputs[index]
        val oldStage = expected[index]
        assertNotEquals(oldStage, previous)
        println("SQUASH v4 differing meshes: " + oldStage.meshes.zip(previous.meshes).filter { (a, b) -> a != b }.map { (a, b) ->
            "${a.id}: geometry=${a.geometry != b.geometry}, offsets=${a.offsets != b.offsets}, shapes=${a.shapes != b.shapes}"
        })
        val preview = WorkspacePreviewBuilder().build(candidate(5))
        val result = assertNotNull(preview.modifierEvaluation)
        fun equal(reference: RigIR, observed: RigIR, stage: String) {
            if (reference == observed) return
            val fields = RigIR::class.java.declaredFields.filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
                .filter { it.isAccessible = true; it.get(reference) != it.get(observed) }.map { it.name }
            val meshes = reference.meshes.zip(observed.meshes).filter { (a, b) -> a != b }.map { (a, b) ->
                "${a.id}: geometry=${a.geometry != b.geometry}, offsets=${a.offsets != b.offsets}, shapes=${a.shapes != b.shapes}"
            }
            fail("$stage differs in $fields, meshes: $meshes")
        }
        for (stage in stack.instances.indices) equal(expected[stage], result.outputs[stage], stack.instances[stage].type)
        equal(RigIrCompiler.compile(reference, tileArt = true), RigIrCompiler.compile(preview, tileArt = true), "compiled IR")
        val artifact = assertNotNull(BundledAlgorithmArtifacts.find(squash.type, 5))
        val root = Files.createTempDirectory("eye-squash-source-")
        val preferences = Preferences.userRoot().node("eye-squash-source/" + UUID.randomUUID())
        val store = ModifierPluginStore(root.resolve("installed"), preferences)
        try {
            BundledAlgorithmArtifacts.retain(store, listOf(artifact))
            val worker = store.snapshot().single()
            val instance = result.entries[index].instance
            val definition = RigEyeSquashModifier.sharedFrameDefinition(RigModifierSource.of(preview))
            val context = definition.authorContext(instance).withUpstream(result.entries.take(index)
                .filter { it.status == ModifierStatus.ACTIVE && it.instance.type in definition.requires + definition.optionalUpstream }
                .map { entry -> entry.instance.let {
                    ModifierUpstreamCapability(it.type, it.type, it.version, it.targets, it.id, it.config, it.outputAliases, it.plugin)
                } })
            for ((type, version) in versions.filterKeys { it != squash.type }) {
                val provider = context.upstream.single { it.type == type }
                assertEquals(version, provider.version)
                assertEquals(stack.instances.single { it.type == type }.plugin, provider.plugin)
            }
            equal(expected[index], ModifierPluginWorkers.evaluate(worker, worker.manifest.algorithms.single(),
                result.outputs[index - 1], instance, {}, store, context = context, declaredParameters = definition.parameters(instance)), "installed JVM")
        } finally {
            store.snapshot().forEach { ModifierPluginWorkers.stop(it.reference) }
            preferences.removeNode()
            ModifierPluginStore.deleteTree(root)
        }
    }
}
