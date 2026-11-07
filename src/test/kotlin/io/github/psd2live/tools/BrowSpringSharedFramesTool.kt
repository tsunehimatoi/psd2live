package io.github.psd2live.tools

import io.github.psd2live.application.CliPresetBindings
import io.github.psd2live.application.WorkspaceInitialPreset
import io.github.psd2live.application.WorkspacePresetInitialization
import io.github.psd2live.application.WorkspacePreviewBuilder
import io.github.psd2live.application.WorkspaceRasterWork
import io.github.psd2live.core.*
import io.github.psd2live.core.modifier.*
import io.github.psd2live.project.WorkspaceDocument
import io.github.psd2live.project.WorkspaceSettingsCodec
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BrowSpringSharedFramesTool {
    @Test fun compareDsSpring() = runBlocking {
        requireTools()
        val sample = Sample.fromEnvironment()
        val config = PipelineConfig(meshSpacing = 64)
        val source = PSD2LivePipeline().readPsd(sample.path)
        val overlay = config.rigEdits.copy(modifierStack = RigModifierStack().toJson())
        val document = WorkspaceDocument(source, emptyMap(), emptySet(), emptyMap(), emptyMap(), overlay,
            WorkspaceSettingsCodec.encode(config.copy(rigEdits = overlay)))
        val analysis = RigGenerationSource.prepare(RigGenerationSource.analyze(source, config), config)
        val bindings = CliPresetBindings(analysis, config)
        val (planned, _) = WorkspacePresetInitialization.prepare(document, WorkspaceRasterWork.Direct,
            WorkspaceInitialPreset(clothing = false), requestedVersions = mapOf("face/fitted" to 3, "eye/fitted" to 5, "brow/fitted" to 4,
                "brow.shape" to 5, "brow.lid-coupling" to 7, "brow.blink" to 5, "brow.spring" to 1), cliBindings = bindings)
        val stack = RigModifierStack.fromJson(planned.rigEdits.modifierStack!!)
        fun select(instance: RigModifierInstance, version: Int) = instance.copy(version = version,
            plugin = assertNotNull(BundledAlgorithmArtifacts.find(instance.type, version)).reference)
        val spring = stack.instances.single { it.type == "brow.spring" }
        val index = stack.index(spring.id)
        suspend fun build(version: Int): RigPreviewModel {
            val chosen = if (spring.version == version) spring else select(spring, version)
            val changed = stack.copy(instances = stack.instances.map { if (it.id == spring.id) chosen else it })
            return WorkspacePreviewBuilder().build(planned.copy(rigEdits = planned.rigEdits.copy(modifierStack = changed.toJson())))
        }
        val reference = build(1)
        val old = assertNotNull(reference.modifierEvaluation).outputs
        val base = old[index - 1]
        val expected = old[index]
        val oldMeshes = base.meshes.associateBy { it.id }
        val affected = expected.meshes.filter { it != oldMeshes[it.id] }.map { it.id }
        assertEquals(setOf("ArtMeshEyebrowL", "ArtMeshEyebrowR"), affected.toSet())
        val addedPhysics = expected.physics.groups.filter { it !in base.physics.groups }
        assertEquals(listOf("ParamBrowLSpring", "ParamBrowRSpring"), addedPhysics.flatMap { group ->
            group.outputs.map { it.parameter }
        })
        assertContains(assertFailsWith<IllegalArgumentException> { build(5) }.message.orEmpty(), "requires a compatible fitted brow provider")
        val candidate = build(6)
        val actual = assertNotNull(candidate.modifierEvaluation).outputs
        assertTrue(old[index - 1] == actual[index - 1], "The preceding fitted stage must match")
        assertTrue(expected == actual[index], "The spring stage must match exactly")
        assertTrue(RigIrCompiler.compile(reference, tileArt = true) == RigIrCompiler.compile(candidate, tileArt = true),
            "The final DS IR must match exactly")

        val selected = select(spring, 6)
        val artifact = assertNotNull(BundledAlgorithmArtifacts.find(selected.type, selected.version))
        val root = Files.createTempDirectory("fitted-brow-spring-v6-")
        val preferences = Preferences.userRoot().node("fitted-brow-spring-v6/" + UUID.randomUUID())
        val store = ModifierPluginStore(root.resolve("installed"), preferences)
        try {
            BundledAlgorithmArtifacts.retain(store, listOf(artifact))
            val worker = store.snapshot().single()
            val definition = RigBrowSpringModifier.sharedFrameDefinition(RigModifierSource.of(candidate))
            val providers = stack.instances.take(index).filter { it.type in definition.requires + definition.optionalUpstream }
            for ((type, version) in mapOf("face/fitted" to 3, "eye/fitted" to 5, "brow/fitted" to 4, "brow.shape" to 5, "brow.lid-coupling" to 7, "brow.blink" to 5))
                assertEquals(version, providers.single { it.type == type }.version)
            val context = definition.authorContext(selected).withUpstream(providers.map {
                ModifierUpstreamCapability(it.type, it.type, it.version, it.targets, it.id, it.config, it.outputAliases, it.plugin)
            })
            val replay = ModifierPluginWorkers.evaluate(worker, worker.manifest.algorithms.single(), base,
                selected, {}, store, context = context, declaredParameters = definition.parameters(selected))
            assertTrue(expected == replay, "The installed worker must reproduce the exact spring stage")
        } finally {
            ModifierPluginWorkers.stop(artifact.reference)
            preferences.removeNode()
            ModifierPluginStore.deleteTree(root)
        }
    }
}
