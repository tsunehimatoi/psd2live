package io.github.psd2live.tools

import io.github.psd2live.algorithms.binding.MouthTongueV9Algorithm
import io.github.psd2live.application.WorkspaceInitialPreset
import io.github.psd2live.application.WorkspacePresetInitialization
import io.github.psd2live.application.WorkspacePreviewBuilder
import io.github.psd2live.application.WorkspaceRasterWork
import io.github.psd2live.core.*
import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.ChildRef
import io.github.psd2live.history.WorkspaceHistoryTree
import io.github.psd2live.project.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.*

class LayeredTongueTipSourceTool {
    @Test fun compareLayeredTongueTip(): Unit = runBlocking {
        requireTools()
        val sample = Sample.fromEnvironment()
        val artwork = PSD2LivePipeline().readPsd(sample.path)
        val config = PipelineConfig(meshSpacing = 40, rigEdits = RigEditOverlay(modifierStack = RigModifierStack().toJson()))
        val document = layeredMouthDocument(artwork, config, withTongue = true)
        val versions = mapOf("face/fitted" to 3, "eye/fitted" to 5, "brow/fitted" to 4, "mouth/fitted" to 8,
            "mouth.outline" to 11, "mouth.vowel" to 8, "mouth.capture" to 7, "mouth.tongue" to 7)
        val (planned, baseline) = WorkspacePresetInitialization.prepare(document, WorkspaceRasterWork.Direct,
            WorkspaceInitialPreset(clothing = false), requestedVersions = versions)
        val stack = RigModifierStack.fromJson(assertNotNull(planned.rigEdits.modifierStack))
        val tongue = stack.instances.single { it.type == "mouth.tongue" }
        val stage = stack.index(tongue.id)
        val artifact = assertNotNull(BundledAlgorithmArtifacts.find(tongue.type, MouthTongueV9Algorithm.VERSION))
        val selected = tongue.copy(version = MouthTongueV9Algorithm.VERSION, plugin = artifact.reference)
        val upgraded = stack.copy(instances = stack.instances.take(stage) + selected)
        val candidateDocument = planned.copy(rigEdits = planned.rigEdits.copy(modifierStack = upgraded.toJson()))
        val preview = WorkspacePreviewBuilder().build(candidateDocument)
        val evaluated = assertNotNull(preview.modifierEvaluation)
        val previous = assertNotNull(baseline.modifierEvaluation)
        assertEquals(versions, previous.entries.filter { it.instance.type in versions }.associate { it.instance.type to it.instance.version })
        for (entry in evaluated.entries.filter { it.instance.type in versions }) assertEquals(ModifierStatus.ACTIVE, entry.status, entry.instance.type)
        assertEquals(previous.outputs.take(stage), evaluated.outputs.take(stage))
        val before = previous.outputs[stage]
        val after = evaluated.outputs[stage]
        val owner = after.meshes.single { it.id == "ArtMeshTongue" }
        val tip = after.meshes.single { it.id == selected.outputId(MouthTongueV9Algorithm.tipOutput(owner.id)) }
        assertEquals(before.meshes, after.meshes - tip)
        assertEquals(before.parameters, after.parameters)
        assertTrue(owner.maskedBy.isNotEmpty())
        assertTrue(tip.maskedBy.isEmpty())
        assertEquals(0f, tip.opacity)
        assertEquals(owner.id, tip.textureSource)
        assertEquals(owner.tile, tip.tile)
        assertEquals(owner.page, tip.page)
        assertTrue(after.deformers.any { it.id == tip.parent })
        val part = after.parts.single { ChildRef.MeshRef(owner.id) in it.children }
        assertTrue(ChildRef.MeshRef(tip.id) in part.children)
        val driven = tip.shapes.single { it.parameter == "ParamTongueOut" }
        assertTrue(driven.shapes.any { form ->
            form != null && form.opacity > 0f && form.deltas.toArray().any { it != 0f }
        })
        val legacy = PSD2LivePipeline().apply { deferRuntimeBundles = true }.buildPreview(document.source,
            config.copy(rigEdits = RigEditOverlay.Empty, layerOverrides = document.layerOverrides))
        val nativeTip = RigIrCompiler.compile(legacy, tileArt = true).meshes.single { it.id == "${owner.id}_Out" }
        assertEquals(nativeTip.geometry?.indices, tip.geometry?.indices)
        assertEquals(nativeTip.offsets, tip.offsets)
        val nativeDriver = nativeTip.shapes.single { it.parameter == driven.parameter }
        assertEquals(nativeDriver.keys, driven.keys)
        assertEquals(nativeDriver.limits, driven.limits)
        assertEquals(nativeDriver.shapes.map { it?.let { form -> form.deltas to form.opacity } },
            driven.shapes.map { it?.let { form -> form.deltas to form.opacity } })
        assertEquals(preview.rig.layerIdByDrawableId[owner.id], preview.rig.layerIdByDrawableId[tip.id])
        val full = RigIrCompiler.compile(preview, tileArt = true)
        assertEquals(owner.id, full.meshes.single { it.id == tip.id }.textureSource)

        val root = Files.createTempDirectory("psd2live-project-")
        val preferences = Preferences.userRoot().node("layered-tongue-tip/" + UUID.randomUUID())
        val pluginStore = ModifierPluginStore(root.resolve("installed"), preferences)
        try {
            BundledAlgorithmArtifacts.retain(pluginStore, listOf(artifact))
            val worker = pluginStore.snapshot().single()
            val definition = RigMouthTongueModifier.sharedFrameDefinition(RigModifierSource.of(preview), layered = true, withTip = true)
            val context = definition.authorContext(selected).withUpstream(evaluated.entries.take(stage)
                .filter { it.status == ModifierStatus.ACTIVE && it.instance.type in definition.requires + definition.optionalUpstream }
                .map { entry -> entry.instance.let {
                    ModifierUpstreamCapability(it.type, it.type, it.version, it.targets, it.id, it.config, it.outputAliases, it.plugin)
                } })
            val replay = ModifierPluginWorkers.evaluate(worker, worker.manifest.algorithms.single(), evaluated.outputs[stage - 1],
                selected, {}, pluginStore, context = context, declaredParameters = definition.parameters(selected))
            assertTrue(after == replay, "Installed worker must return the identical tip")

            val store = WorkspaceStore(root.resolve("live"))
            val revision = WorkspaceRevisions.of(candidateDocument)
            val state = WorkspaceHistoryTree(candidateDocument, revision, revision).state()
            val project = "layered-tongue-tip"
            store.persistHistory(project, state)
            val file = root.resolve("tongue-tip.psd2live")
            val repository = ProjectRepository(writeHeadCache = false)
            repository.save(ProjectSaveCapture(project, state, JsonObject(emptyMap()), sample.path, store), file)
            repository.open(file).use { reopened ->
                val snapshot = reopened.history.checkout(state.headNodeId).snapshot
                val rebuilt = RigIrCompiler.compile(WorkspacePreviewBuilder().build(snapshot), tileArt = true)
                assertEquals(tip.id, rebuilt.meshes.single { it.textureSource == owner.id }.id)
                assertTrue(full == rebuilt, "Reopening must preserve the complete tongue tip IR")
            }
        } finally {
            pluginStore.snapshot().forEach { ModifierPluginWorkers.stop(it.reference) }
            preferences.removeNode()
            ProjectArchive.deleteTemporaryDirectory(root)
        }
    }
}
