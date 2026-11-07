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
import io.github.psd2live.project.WorkspaceSourceLayer
import org.umamo.format.art.*
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.*

/** Compares the source blink aftershock under refitted shared head frames. */
class EyeAftershockSourceTool {
    @Test fun compareSharedFrameAftershock(): Unit = runBlocking {
        requireTools()
        val artwork = PSD2LivePipeline().readPsd(Sample.fromEnvironment().path)
        val config = PipelineConfig(meshSpacing = 64, rigEdits = RigEditOverlay(modifierStack = RigModifierStack().toJson()))
        val highlightVersion = setting("PSD2LIVE_AFTERSHOCK_HIGHLIGHT_VERSION", "").toIntOrNull()
        val original = RigGenerationSource.analyze(artwork, config)
        // DS has no separately drawn glints. Add one small clipped glint per fitted iris.
        val existing = original.layers.filter { it.semantic.tag == SemanticTag.IRIDES && it.semantic.role == PartRole.HIGHLIGHT }
        val additions = if (highlightVersion == null || existing.isNotEmpty()) emptyMap() else original.layers
            .filter { it.semantic.tag == SemanticTag.IRIDES && it.semantic.role == null && it.opaquePixels > 0 }
            .distinctBy { it.semantic.side }.associate { iris ->
                val layer = iris.source
                val size = (minOf(layer.bounds.width, layer.bounds.height) / 4).coerceAtLeast(4)
                val id = LayerId("ds-glint-" + iris.semantic.side.name)
                val raster = LayerRaster(size, size, ByteArray(size * size * 4) { -1 })
                val bounds = LayerBounds(layer.bounds.left + layer.bounds.width / 3, layer.bounds.top + layer.bounds.height / 3, size, size)
                layer.id to (WorkspaceSourceLayer(id, id.raw, layer.groupPath, SourceLayerKind.Raster, true, layer.order, bounds,
                    1f, true, LayerBlend.Normal, ChannelMask.ALL, raster, null, null, false) to
                    LayerClassificationOverride(LayerType.PRESET, SemanticTag.IRIDES, iris.semantic.side, role = PartRole.HIGHLIGHT))
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
        val emotionVersion = setting("PSD2LIVE_AFTERSHOCK_EMOTION_VERSION", "1").toInt()
        val versions = mapOf("face/fitted" to 3, "eye/fitted" to 5, "brow/fitted" to 4,
            "eye.smile" to 4, "eye.wide" to 4, "eye.gaze-lid" to 4, "eye.emotion" to emotionVersion, "eye.aftershock" to 1) +
            if (highlightVersion == null) emptyMap() else mapOf("eye.highlight" to highlightVersion)
        val (planned, reference) = WorkspacePresetInitialization.prepare(document, WorkspaceRasterWork.Direct,
            WorkspaceInitialPreset(clothing = false), requestedVersions = versions, cliBindings = CliPresetBindings(analysis, config))
        val stack = RigModifierStack.fromJson(assertNotNull(planned.rigEdits.modifierStack))
        assertEquals(versions, stack.instances.filter { it.type in versions }.associate { it.type to it.version })
        val aftershock = stack.instances.single { it.type == "eye.aftershock" }
        val index = stack.index(aftershock.id)
        val expected = assertNotNull(reference.modifierEvaluation).outputs
        if (highlightVersion != null) {
            val highlightIndex = stack.instances.indexOfFirst { it.type == "eye.highlight" }
            val glints = analyzed.layers.filter { it.semantic.tag == SemanticTag.IRIDES && it.semantic.role == PartRole.HIGHLIGHT }
                .mapTo(hashSetOf()) { it.source.id.raw }
            val before = expected[highlightIndex - 1].meshes.associateBy { it.id }
            val changed = expected[highlightIndex].meshes.filter { it != before[it.id] }
            assertEquals(2, changed.count { reference.rig.layerIdByDrawableId[it.id] in glints })
        }
        val oldMeshes = expected[index - 1].meshes.associateBy { it.id }
        for (id in listOf("ArtMeshEyelashL", "ArtMeshEyelashR")) {
            assertNotEquals(oldMeshes.getValue(id), expected[index].meshes.single { it.id == id })
        }
        assertTrue(expected[index].physics.groups.size > expected[index - 1].physics.groups.size)
        fun candidate(version: Int): WorkspaceDocument {
            val artifact = assertNotNull(BundledAlgorithmArtifacts.find(aftershock.type, version))
            val modified = stack.copy(instances = stack.instances.map {
                if (it.id == aftershock.id) it.copy(version = version, plugin = artifact.reference) else it
            })
            return planned.copy(rigEdits = planned.rigEdits.copy(modifierStack = modified.toJson()))
        }
        val rejected = assertFailsWith<IllegalArgumentException> { WorkspacePreviewBuilder().build(candidate(4)) }
        assertContains(rejected.message.orEmpty(), "Blink aftershock v4 cannot migrate an edited neutral lid frame: ArtMeshEye")
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
        val artifact = assertNotNull(BundledAlgorithmArtifacts.find(aftershock.type, 5))
        val root = Files.createTempDirectory("eye-aftershock-source-")
        val preferences = Preferences.userRoot().node("eye-aftershock-source/" + UUID.randomUUID())
        val store = ModifierPluginStore(root.resolve("installed"), preferences)
        try {
            BundledAlgorithmArtifacts.retain(store, listOf(artifact))
            val worker = store.snapshot().single()
            val instance = result.entries[index].instance
            val definition = RigEyeAftershockModifier.sharedFrameDefinition(RigModifierSource.of(preview))
            val context = definition.authorContext(instance).withUpstream(result.entries.take(index)
                .filter { it.status == ModifierStatus.ACTIVE && it.instance.type in definition.requires + definition.optionalUpstream }
                .map { entry -> entry.instance.let {
                    ModifierUpstreamCapability(it.type, it.type, it.version, it.targets, it.id, it.config, it.outputAliases, it.plugin)
                } })
            val preceding = listOf("brow/fitted", "eye.smile", "eye.wide", "eye.gaze-lid", "eye.emotion") +
                listOfNotNull("eye.highlight".takeIf { highlightVersion != null })
            for (type in preceding) {
                val provider = context.upstream.single { it.type == type }
                assertEquals(versions.getValue(type), provider.version)
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
