package io.github.psd2live.tools

import io.github.psd2live.application.*
import io.github.psd2live.core.*
import io.github.psd2live.core.modifier.*
import io.github.psd2live.project.WorkspaceDocument
import io.github.psd2live.project.WorkspaceSettingsCodec
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BrowBlinkSharedFrameTool {
    @Test fun compareBrowBlink() = runBlocking {
        requireTools()
        val source = PSD2LivePipeline().readPsd(Sample.fromEnvironment().path)
        val config = PipelineConfig(meshSpacing = 64, rigEdits = RigEditOverlay(modifierStack = RigModifierStack().toJson()))
        val document = WorkspaceDocument(source, emptyMap(), emptySet(), emptyMap(), emptyMap(), config.rigEdits,
            WorkspaceSettingsCodec.encode(config))
        val analysis = RigGenerationSource.prepare(RigGenerationSource.analyze(source, config), config)
        val versions = mapOf("face/fitted" to 3, "eye/fitted" to 5, "brow/fitted" to 4,
            "brow.shape" to setting("PSD2LIVE_BROW_SHAPE", "1").toInt(),
            "brow.lid-coupling" to setting("PSD2LIVE_BROW_COUPLING", "1").toInt(), "brow.blink" to 1, "brow.spring" to 1)
        val (planned, historical) = WorkspacePresetInitialization.prepare(document, WorkspaceRasterWork.Direct,
            WorkspaceInitialPreset(clothing = false), requestedVersions = versions, cliBindings = CliPresetBindings(analysis, config))
        val stack = RigModifierStack.fromJson(planned.rigEdits.modifierStack!!)
        for ((type, version) in versions) assertEquals(version, stack.instances.single { it.type == type }.version, type)
        val blink = stack.instances.single { it.type == "brow.blink" }
        val index = stack.index(blink.id)
        val artifact = assertNotNull(BundledAlgorithmArtifacts.find(blink.type, 5))
        val selected = blink.copy(version = 5, plugin = artifact.reference)
        val modified = stack.copy(instances = stack.instances.map { if (it.id == blink.id) selected else it })
        val candidate = WorkspacePreviewBuilder().build(planned.copy(rigEdits = planned.rigEdits.copy(modifierStack = modified.toJson())))
        val expected = assertNotNull(historical.modifierEvaluation).outputs
        val evaluation = assertNotNull(candidate.modifierEvaluation)
        val actual = evaluation.outputs
        for (stage in stack.instances.indices) {
            val oldMeshes = expected[stage].meshes.associateBy { it.id }
            val changed = actual[stage].meshes.filter { it != oldMeshes[it.id] }
            val oldWarps = expected[stage].deformers.associateBy { it.id }
            val changedWarps = actual[stage].deformers.filter { it != oldWarps[it.id] }
            assertTrue(expected[stage] == actual[stage], "${stack.instances[stage].type}: meshes=${changed.map { it.id }}, warps=${changedWarps.map { it.id }}")
        }
        assertTrue(RigIrCompiler.compile(historical, tileArt = true) == RigIrCompiler.compile(candidate, tileArt = true))
        val root = Files.createTempDirectory("brow-blink-shared-")
        val preferences = Preferences.userRoot().node("brow-blink-shared/" + UUID.randomUUID())
        val store = ModifierPluginStore(root.resolve("installed"), preferences)
        try {
            BundledAlgorithmArtifacts.retain(store, listOf(artifact))
            val worker = store.snapshot().single()
            val context = assertNotNull(evaluation.readAlgorithms[selected.id]).context
            for ((type, version) in versions.filterKeys { it in setOf("brow/fitted", "brow.shape", "brow.lid-coupling", "eye/fitted") }) {
                val provider = context.upstream.single { it.type == type }
                assertEquals(version, provider.version)
                assertEquals(stack.instances.single { it.type == type }.plugin, provider.plugin)
            }
            assertTrue(expected[index] == ModifierPluginWorkers.evaluate(worker, worker.manifest.algorithms.single(), expected[index - 1],
                selected, {}, store, context = context))
        } finally {
            ModifierPluginWorkers.stop(artifact.reference)
            preferences.removeNode()
            ModifierPluginStore.deleteTree(root)
        }
    }
}
