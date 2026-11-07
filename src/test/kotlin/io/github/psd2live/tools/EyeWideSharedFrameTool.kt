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

class EyeWideSharedFrameTool {
    @Test fun compareEyeWide() = runBlocking {
        requireTools()
        val source = PSD2LivePipeline().readPsd(Sample.fromEnvironment().path)
        val config = PipelineConfig(meshSpacing = 64, rigEdits = RigEditOverlay(modifierStack = RigModifierStack().toJson()))
        val document = WorkspaceDocument(source, emptyMap(), emptySet(), emptyMap(), emptyMap(), config.rigEdits,
            WorkspaceSettingsCodec.encode(config))
        val analysis = RigGenerationSource.prepare(RigGenerationSource.analyze(source, config), config)
        val versions = mapOf("face/fitted" to 3, "eye/fitted" to 5, "brow/fitted" to 4,
            "eye.smile" to setting("PSD2LIVE_EYE_SMILE", "1").toInt(), "eye.wide" to 1)
        val (planned, historical) = WorkspacePresetInitialization.prepare(document, WorkspaceRasterWork.Direct,
            WorkspaceInitialPreset(clothing = false), requestedVersions = versions, cliBindings = CliPresetBindings(analysis, config))
        val stack = RigModifierStack.fromJson(planned.rigEdits.modifierStack!!)
        for ((type, version) in versions) assertEquals(version, stack.instances.single { it.type == type }.version, type)
        val wide = stack.instances.single { it.type == "eye.wide" }
        val index = stack.index(wide.id)
        val artifact = assertNotNull(BundledAlgorithmArtifacts.find(wide.type, setting("PSD2LIVE_EYE_WIDE", "4").toInt()))
        val selected = wide.copy(version = artifact.manifest.algorithms.single().version, plugin = artifact.reference)
        val modified = stack.copy(instances = stack.instances.map { if (it.id == wide.id) selected else it })
        val candidate = WorkspacePreviewBuilder().build(planned.copy(rigEdits = planned.rigEdits.copy(modifierStack = modified.toJson())))
        val expected = assertNotNull(historical.modifierEvaluation).outputs
        val evaluation = assertNotNull(candidate.modifierEvaluation)
        assertEquals(ModifierStatus.ACTIVE, evaluation.entries[index].status)
        val actual = evaluation.outputs
        val openings = setOf("ParamEyeLOpen", "ParamEyeROpen")
        assertEquals(openings, expected[index - 1].parameters.filter { it.id in openings && it.max == 1f }.mapTo(hashSetOf()) { it.id })
        assertEquals(openings, actual[index].parameters.filter { it.id in openings && it.max == 2f }.mapTo(hashSetOf()) { it.id })
        for (stage in stack.instances.indices) {
            val oldMeshes = expected[stage].meshes.associateBy { it.id }
            val changed = actual[stage].meshes.filter { it != oldMeshes[it.id] }
            val oldWarps = expected[stage].deformers.associateBy { it.id }
            val changedWarps = actual[stage].deformers.filter { it != oldWarps[it.id] }
            assertTrue(expected[stage] == actual[stage], "${stack.instances[stage].type}: meshes=${changed.map { it.id }}, warps=${changedWarps.map { it.id }}")
        }
        assertTrue(RigIrCompiler.compile(historical, tileArt = true) == RigIrCompiler.compile(candidate, tileArt = true))
        val root = Files.createTempDirectory("eye-wide-shared-")
        val preferences = Preferences.userRoot().node("eye-wide-shared/" + UUID.randomUUID())
        val store = ModifierPluginStore(root.resolve("installed"), preferences)
        try {
            BundledAlgorithmArtifacts.retain(store, listOf(artifact))
            val worker = store.snapshot().single()
            val definition = RigEyeWideModifier.sharedFrameDefinition(RigModifierSource.of(candidate))
            val upstream = modified.instances.take(index).filter { it.type in definition.requires + definition.optionalUpstream }
                .map { ModifierUpstreamCapability(it.type, it.type, it.version, it.targets, it.id, it.config, it.outputAliases, it.plugin) }
            val context = definition.authorContext(selected).withUpstream(upstream)
            for ((type, version) in versions.filterKeys { it in setOf("eye.smile", "eye/fitted", "face/fitted", "brow/fitted") }) {
                val provider = context.upstream.single { it.type == type }
                assertEquals(version, provider.version)
                assertEquals(stack.instances.single { it.type == type }.plugin, provider.plugin)
            }
            assertTrue(expected[index] == ModifierPluginWorkers.evaluate(worker, worker.manifest.algorithms.single(), expected[index - 1],
                selected, {}, store, context = context,
                declaredParameters = definition.parameters(selected)))
        } finally {
            ModifierPluginWorkers.stop(artifact.reference)
            preferences.removeNode()
            ModifierPluginStore.deleteTree(root)
        }
    }
}
