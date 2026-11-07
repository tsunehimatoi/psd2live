package io.github.psd2live.tools

import io.github.psd2live.application.*
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

class MouthOutlineSourceTool {
    @Test fun compareDsOutline(): Unit = runBlocking {
        requireTools()
        val source = PSD2LivePipeline().readPsd(Sample.fromEnvironment().path)
        val config = PipelineConfig(meshSpacing = 64, rigEdits = RigEditOverlay(modifierStack = RigModifierStack().toJson()))
        val document = WorkspaceDocument(source, emptyMap(), emptySet(), emptyMap(), emptyMap(), config.rigEdits,
            WorkspaceSettingsCodec.encode(config))
        val analysis = RigGenerationSource.prepare(RigGenerationSource.analyze(source, config), config)
        assertTrue(analysis.geometry.layers.none { it.semantic.tag == SemanticTag.MOUTH_CLOSE })
        val versions = mapOf("face/fitted" to 3, "eye/fitted" to 5, "brow/fitted" to 4, "mouth/fitted" to 6,
            "eye.smile" to 4, "eye.wide" to 4, "eye.gaze-lid" to 4, "eye.emotion" to 4, "eye.aftershock" to 5,
            "eye.squash" to 5, "brow.shape" to 5, "brow.lid-coupling" to 7, "brow.blink" to 5, "brow.spring" to 6,
            "mouth.outline" to 1)
        val (planned, reference) = WorkspacePresetInitialization.prepare(document, WorkspaceRasterWork.Direct,
            WorkspaceInitialPreset(clothing = false), requestedVersions = versions, cliBindings = CliPresetBindings(analysis, config))
        val stack = RigModifierStack.fromJson(assertNotNull(planned.rigEdits.modifierStack))
        assertEquals(versions, stack.instances.filter { it.type in versions }.associate { it.type to it.version })
        val outline = stack.instances.single { it.type == "mouth.outline" }
        val index = stack.index(outline.id)
        val expected = assertNotNull(reference.modifierEvaluation).outputs
        val lips = expected[index].meshes.filter { mesh -> expected[index - 1].meshes.none { it.id == mesh.id } }
        assertEquals(2, lips.size)
        assertTrue(expected[index].authoring.paths.any { path -> lips.any { it.id == path.mesh } })
        fun candidate(version: Int): WorkspaceDocument {
            val artifact = assertNotNull(BundledAlgorithmArtifacts.find(outline.type, version))
            val next = stack.copy(instances = stack.instances.map {
                if (it.id == outline.id) it.copy(version = version, plugin = artifact.reference) else it
            })
            return planned.copy(rigEdits = planned.rigEdits.copy(modifierStack = next.toJson()))
        }
        val rejected = assertFailsWith<IllegalArgumentException> { WorkspacePreviewBuilder().build(candidate(7)) }
        assertContains(rejected.message.orEmpty(), "Fitted mouth outline cannot replace authored owner topology: ArtMeshMouth")
        RigBuildProfile.reset()
        val recording = RigBuildProfile.recording
        val preview = try {
            RigBuildProfile.recording = true
            WorkspacePreviewBuilder().build(candidate(8))
        } finally {
            RigBuildProfile.recording = recording
        }
        assertTrue(RigBuildProfile.snapshot().keys.none { it.startsWith("modifier template consumer: mouth.outline ->") })
        val actual = assertNotNull(preview.modifierEvaluation)
        for (entry in actual.entries.filter { it.instance.type in versions }) {
            assertEquals(ModifierStatus.ACTIVE, entry.status, entry.instance.type)
        }
        fun equal(a: RigIR, b: RigIR, stage: String) {
            if (a == b) return
            val fields = RigIR::class.java.declaredFields.filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
                .filter { it.isAccessible = true; it.get(a) != it.get(b) }.map { it.name }
            val known = a.meshes.associateBy { it.id }
            val meshes = b.meshes.filter { it != known[it.id] }.map { it.id }
            fail("$stage differs in $fields, meshes=$meshes")
        }
        for (stage in stack.instances.indices) equal(expected[stage], actual.outputs[stage], stack.instances[stage].type)
        equal(RigIrCompiler.compile(reference, tileArt = true), RigIrCompiler.compile(preview, tileArt = true), "compiled IR")

        val artifact = assertNotNull(BundledAlgorithmArtifacts.find(outline.type, 8))
        val root = Files.createTempDirectory("mouth-outline-source-")
        val preferences = Preferences.userRoot().node("mouth-outline-source/" + UUID.randomUUID())
        val store = ModifierPluginStore(root.resolve("installed"), preferences)
        try {
            BundledAlgorithmArtifacts.retain(store, listOf(artifact))
            val worker = store.snapshot().single()
            val instance = actual.entries[index].instance
            val sourceInputs = RigModifierSource.of(preview)
            val definition = RigFittedMouthOutlineDefinition.sharedFrameDefinition(sourceInputs)
            val context = definition.authorContext(instance).withUpstream(actual.entries.take(index)
                .filter { it.status == ModifierStatus.ACTIVE && it.instance.type in definition.requires + definition.optionalUpstream }
                .map { entry -> entry.instance.let {
                    ModifierUpstreamCapability(it.type, it.type, it.version, it.targets, it.id, it.config, it.outputAliases, it.plugin)
                } })
            val provider = context.upstream.single { it.type == "mouth/fitted" }
            assertEquals(6, provider.version)
            assertEquals(stack.instances.single { it.type == provider.type }.plugin, provider.plugin)
            equal(expected[index], ModifierPluginWorkers.evaluate(worker, worker.manifest.algorithms.single(),
                actual.outputs[index - 1], instance, {}, store, context = context,
                resolveRasters = sourceInputs::resolveRasters), "installed JVM")
        } finally {
            store.snapshot().forEach { ModifierPluginWorkers.stop(it.reference) }
            preferences.removeNode()
            ModifierPluginStore.deleteTree(root)
        }
    }
}
