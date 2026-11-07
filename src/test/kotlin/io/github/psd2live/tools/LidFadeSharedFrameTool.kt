package io.github.psd2live.tools

import io.github.psd2live.application.*
import io.github.psd2live.core.*
import io.github.psd2live.core.modifier.*
import io.github.psd2live.project.*
import org.umamo.format.art.*
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LidFadeSharedFrameTool {
    @Test fun compareLidFade() = runBlocking {
        requireTools()
        val artwork = PSD2LivePipeline().readPsd(Sample.fromEnvironment().path)
        val config = PipelineConfig(meshSpacing = 64, rigEdits = RigEditOverlay(modifierStack = RigModifierStack().toJson()))
        val original = RigGenerationSource.analyze(artwork, config)
        // Add a separate lid line and lid shadow to the DS artwork when they are missing.
        val existing = original.layers.filter { it.semantic.tag == SemanticTag.EYELASH && it.semantic.role in setOf(PartRole.LID, PartRole.SHADOW) }
        val additions = if (existing.isNotEmpty()) emptyMap() else original.layers
            .filter { it.semantic.tag == SemanticTag.EYELASH && it.semantic.role == null && it.opaquePixels > 0 }
            .distinctBy { it.semantic.side }.associate { iris ->
                val layer = iris.source
                val width = (layer.bounds.width / 2).coerceAtLeast(8)
                val height = (layer.bounds.height / 5).coerceAtLeast(4)
                val role = if (iris.semantic.side == Side.LEFT) PartRole.LID else PartRole.SHADOW
                val id = LayerId("ds-lid-" + iris.semantic.side.name)
                val raster = LayerRaster(width, height, ByteArray(width * height * 4) { if (it % 4 == 3) -1 else 70 })
                val bounds = LayerBounds(layer.bounds.left + layer.bounds.width / 4, layer.bounds.top - height, width, height)
                layer.id to (WorkspaceSourceLayer(id, id.raw, layer.groupPath, SourceLayerKind.Raster, true, layer.order, bounds,
                    0.7f, false, LayerBlend.Normal, ChannelMask.ALL, raster, null, null, false) to
                    LayerClassificationOverride(LayerType.PRESET, SemanticTag.EYELASH, iris.semantic.side, role = role))
            }
        val source = if (additions.isEmpty()) artwork else object : SourceArt by artwork {
            override val layers = artwork.layers.flatMap { layer -> listOfNotNull(additions[layer.id]?.first, layer) }
                .mapIndexed { index, layer -> WorkspaceSourceLayer.copyOf(layer, index) }
        }
        val overrides = additions.values.associate { (layer, classification) -> layer.id.raw to classification }
        val document = WorkspaceDocument(source, emptyMap(), emptySet(), overrides, emptyMap(), config.rigEdits,
            WorkspaceSettingsCodec.encode(config))
        val analyzed = RigGenerationSource.analyze(source, config.copy(layerOverrides = overrides))
        val analysis = RigGenerationSource.prepare(analyzed, config.copy(layerOverrides = overrides))
        val versions = mapOf("face/fitted" to 3, "eye/fitted" to 5, "brow/fitted" to 4,
            "eye.smile" to 4, "eye.wide" to 4, "eye.gaze-lid" to setting("PSD2LIVE_EYE_GAZE", "4").toInt(),
            "eye.emotion" to setting("PSD2LIVE_EYE_EMOTION", "4").toInt(), "eye.lid-fade" to 1)
        val (planned, historical) = WorkspacePresetInitialization.prepare(document, WorkspaceRasterWork.Direct,
            WorkspaceInitialPreset(clothing = false), requestedVersions = versions, cliBindings = CliPresetBindings(analysis, config))
        val stack = RigModifierStack.fromJson(planned.rigEdits.modifierStack!!)
        for ((type, version) in versions) assertEquals(version, stack.instances.single { it.type == type }.version, type)
        val fade = stack.instances.single { it.type == "eye.lid-fade" }
        val index = stack.index(fade.id)
        val artifact = assertNotNull(BundledAlgorithmArtifacts.find(fade.type, setting("PSD2LIVE_LID_FADE", "3").toInt()))
        val selected = fade.copy(version = artifact.manifest.algorithms.single().version, plugin = artifact.reference)
        val modified = stack.copy(instances = stack.instances.map { if (it.id == fade.id) selected else it })
        val candidate = WorkspacePreviewBuilder().build(planned.copy(rigEdits = planned.rigEdits.copy(modifierStack = modified.toJson())))
        val expected = assertNotNull(historical.modifierEvaluation).outputs
        val evaluation = assertNotNull(candidate.modifierEvaluation)
        assertEquals(ModifierStatus.ACTIVE, evaluation.entries[index].status)
        val actual = evaluation.outputs
        val previous = expected[index - 1].meshes.associateBy { it.id }
        val changed = expected[index].meshes.filter { previous[it.id] != it }
        val lids = analyzed.layers.filter { it.semantic.tag == SemanticTag.EYELASH && it.semantic.role in setOf(PartRole.LID, PartRole.SHADOW) }
            .mapTo(hashSetOf()) { it.source.id.raw }
        assertEquals(2, changed.count { historical.rig.layerIdByDrawableId[it.id] in lids }, "Both lids must change")
        println("Added lids: ${additions.size}; source fade changes: ${changed.map { it.id }}")
        for (stage in stack.instances.indices) {
            val oldMeshes = expected[stage].meshes.associateBy { it.id }
            val changed = actual[stage].meshes.filter { it != oldMeshes[it.id] }
            val oldWarps = expected[stage].deformers.associateBy { it.id }
            val changedWarps = actual[stage].deformers.filter { it != oldWarps[it.id] }
            assertTrue(expected[stage] == actual[stage], "${stack.instances[stage].type}: meshes=${changed.map { it.id }}, warps=${changedWarps.map { it.id }}")
        }
        assertTrue(RigIrCompiler.compile(historical, tileArt = true) == RigIrCompiler.compile(candidate, tileArt = true))
        val root = Files.createTempDirectory("lid-fade-shared-")
        val preferences = Preferences.userRoot().node("lid-fade-shared/" + UUID.randomUUID())
        val store = ModifierPluginStore(root.resolve("installed"), preferences)
        try {
            BundledAlgorithmArtifacts.retain(store, listOf(artifact))
            val worker = store.snapshot().single()
            val definition = RigLidFadeModifier.definition(RigModifierSource.of(candidate), modern = true)
            val upstream = modified.instances.take(index).filter { it.type in definition.requires + definition.optionalUpstream }
                .map { ModifierUpstreamCapability(it.type, it.type, it.version, it.targets, it.id, it.config, it.outputAliases, it.plugin) }
            val context = definition.authorContext(selected).withUpstream(upstream)
            for ((type, version) in versions.filterKeys { it in setOf("eye/fitted", "eye.wide") }) {
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
