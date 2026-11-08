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

class MouthJawSourceTool {
    @Test fun compareDsJaw(): Unit = runBlocking { compareJaw(cli = true) }

    @Test fun compareDsWorkspaceJaw(): Unit = runBlocking { compareJaw(cli = false) }

    private suspend fun compareJaw(cli: Boolean) {
        requireTools()
        val source = PSD2LivePipeline().readPsd(Sample.fromEnvironment().path)
        val config = PipelineConfig(meshSpacing = if (cli) 64 else 40, rigEdits = RigEditOverlay(modifierStack = RigModifierStack().toJson()))
        val document = WorkspaceDocument(source, emptyMap(), emptySet(), emptyMap(), emptyMap(), config.rigEdits,
            WorkspaceSettingsCodec.encode(config))
        val analysis = RigGenerationSource.prepare(RigGenerationSource.analyze(source, config), config)
        assertTrue(analysis.geometry.layers.none { it.semantic.tag == SemanticTag.MOUTH_CLOSE })
        val sourceChain = setting("PSD2LIVE_JAW_SOURCE_CHAIN", "1") == "1"
        val versions = mapOf("face/fitted" to 3, "eye/fitted" to 5, "brow/fitted" to 4, "mouth/fitted" to 6,
            "eye.smile" to 4, "eye.wide" to 4, "eye.gaze-lid" to 4, "eye.emotion" to 4, "eye.aftershock" to 5,
            "eye.squash" to 5, "brow.shape" to 5, "brow.lid-coupling" to 7, "brow.blink" to 5, "brow.spring" to 6,
            "mouth.outline" to 8,
            "mouth.vowel" to (if (sourceChain) 7 else 1), "mouth.capture" to (if (sourceChain) 6 else 1), "mouth.jaw" to 1)
        val (planned, reference) = WorkspacePresetInitialization.prepare(document, WorkspaceRasterWork.Direct,
            WorkspaceInitialPreset(clothing = false), requestedVersions = versions, cliBindings = if (cli) CliPresetBindings(analysis, config) else null)
        val stack = RigModifierStack.fromJson(assertNotNull(planned.rigEdits.modifierStack))
        assertEquals(versions, stack.instances.filter { it.type in versions }.associate { it.type to it.version })
        val jaw = stack.instances.single { it.type == "mouth.jaw" }
        val index = stack.index(jaw.id)
        val expected = assertNotNull(reference.modifierEvaluation).outputs
        val old = expected[index - 1].meshes.associateBy { it.id }
        assertEquals(setOf("ArtMeshNose", "ArtMeshFace"),
            expected[index].meshes.filter { it != old[it.id] }.mapTo(hashSetOf()) { it.id })
        fun candidate(version: Int): WorkspaceDocument {
            val artifact = assertNotNull(BundledAlgorithmArtifacts.find(jaw.type, version))
            val next = stack.copy(instances = stack.instances.map {
                if (it.id == jaw.id) it.copy(version = version, plugin = artifact.reference) else it
            })
            return planned.copy(rigEdits = planned.rigEdits.copy(modifierStack = next.toJson()))
        }
        val rejected = assertFailsWith<IllegalArgumentException> { WorkspacePreviewBuilder().build(candidate(7)) }
        assertContains(rejected.message.orEmpty(), "Mouth jaw v7 requires a compatible fitted mouth")
        RigBuildProfile.reset()
        val recording = RigBuildProfile.recording
        val preview = try {
            RigBuildProfile.recording = true
            WorkspacePreviewBuilder().build(candidate(8))
        } finally {
            RigBuildProfile.recording = recording
        }
        assertTrue("mouth.jaw" !in legacyTemplateConsumers())
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

        val artifact = assertNotNull(BundledAlgorithmArtifacts.find(jaw.type, 8))
        val root = Files.createTempDirectory("mouth-jaw-source-")
        val preferences = Preferences.userRoot().node("mouth-jaw-source/" + UUID.randomUUID())
        val store = ModifierPluginStore(root.resolve("installed"), preferences)
        try {
            BundledAlgorithmArtifacts.retain(store, listOf(artifact))
            val worker = store.snapshot().single()
            val instance = actual.entries[index].instance
            val sourceInputs = RigModifierSource.of(preview)
            val definition = RigMouthJawModifier.sharedFrameDefinition(sourceInputs)
            val context = definition.authorContext(instance).withUpstream(actual.entries.take(index)
                .filter { it.status == ModifierStatus.ACTIVE && it.instance.type in definition.requires + definition.optionalUpstream }
                .map { entry -> entry.instance.let {
                    ModifierUpstreamCapability(it.type, it.type, it.version, it.targets, it.id, it.config, it.outputAliases, it.plugin)
                } })
            for (type in listOf("mouth/fitted", "mouth.outline", "mouth.vowel", "mouth.capture")) {
                val provider = context.upstream.single { it.type == type }
                assertEquals(versions.getValue(type), provider.version)
                assertEquals(stack.instances.single { it.type == type }.plugin, provider.plugin)
            }
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
