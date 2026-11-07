package io.github.psd2live.tools

import io.github.psd2live.application.CliPresetBindings
import io.github.psd2live.application.WorkspaceInitialPreset
import io.github.psd2live.application.WorkspacePresetInitialization
import io.github.psd2live.application.WorkspacePreviewBuilder
import io.github.psd2live.application.WorkspaceRasterWork
import io.github.psd2live.core.*
import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.project.*
import org.umamo.format.art.*
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.*

/** Compares the source iris squash under refitted shared head frames. */
class EyeSquashSourceTool {
    @Test fun compareSharedFrameSquash(): Unit = verify(withSourceNeighbors = false)

    @Test fun compareSharedFrameSquashAfterSourceHighlightAndAftershock(): Unit = verify(withSourceNeighbors = true)

    private fun verify(withSourceNeighbors: Boolean): Unit = runBlocking {
        requireTools()
        val config = PipelineConfig(meshSpacing = 64)
        val artwork = PSD2LivePipeline().readPsd(Sample.fromEnvironment().path)
        val (source, overrides) = if (withSourceNeighbors) withGlints(artwork, config) else artwork to emptyMap()
        val overlay = config.rigEdits.copy(modifierStack = RigModifierStack().toJson())
        val document = WorkspaceDocument(source, emptyMap(), emptySet(), overrides, emptyMap(), overlay,
            WorkspaceSettingsCodec.encode(config.copy(rigEdits = overlay)))
        val configured = config.copy(layerOverrides = overrides)
        val analysis = RigGenerationSource.prepare(RigGenerationSource.analyze(source, configured), configured)
        val versions = mapOf("face/fitted" to 3, "eye/fitted" to 5, "brow/fitted" to 4,
            "eye.smile" to 4, "eye.wide" to 4, "eye.gaze-lid" to 4, "eye.emotion" to 4, "eye.squash" to 1) +
            if (withSourceNeighbors) mapOf("eye.highlight" to 4, "eye.aftershock" to 5) else mapOf("eye.aftershock" to 1)
        val (planned, reference) = WorkspacePresetInitialization.prepare(document, WorkspaceRasterWork.Direct,
            WorkspaceInitialPreset(clothing = false), requestedVersions = versions, cliBindings = CliPresetBindings(analysis, config))
        val stack = RigModifierStack.fromJson(assertNotNull(planned.rigEdits.modifierStack))
        assertEquals(versions, stack.instances.filter { it.type in versions }.associate { it.type to it.version })
        val squash = stack.instances.single { it.type == "eye.squash" }
        val index = stack.index(squash.id)
        val expected = assertNotNull(reference.modifierEvaluation).outputs
        val oldMeshes = expected[index - 1].meshes.associateBy { it.id }
        val originalIrisLayers = RigGenerationSource.analyze(artwork, config).layers.filter {
            it.semantic.tag == SemanticTag.IRIDES && it.semantic.role == null
        }.mapTo(hashSetOf()) { it.source.id.raw }
        val irises = expected[index].meshes.filter { reference.rig.ownLayerIdByDrawableId[it.id] in originalIrisLayers }
        assertEquals(2, irises.size)
        for (iris in irises) assertNotEquals(oldMeshes.getValue(iris.id), iris)
        fun candidate(version: Int): WorkspaceDocument {
            val artifact = assertNotNull(BundledAlgorithmArtifacts.find(squash.type, version))
            val modified = stack.copy(instances = stack.instances.map {
                if (it.id == squash.id) it.copy(version = version, plugin = artifact.reference) else it
            })
            return planned.copy(rigEdits = planned.rigEdits.copy(modifierStack = modified.toJson()))
        }
        if (!withSourceNeighbors) {
            val previous = assertNotNull(WorkspacePreviewBuilder().build(candidate(4)).modifierEvaluation).outputs[index]
            val differences = expected[index].meshes.zip(previous.meshes).filter { (legacy, observed) -> legacy != observed }
            assertEquals(irises.map { it.id }.toSet(), differences.map { it.first.id }.toSet())
            for ((legacy, observed) in differences) {
                assertNotEquals(legacy.offsets, observed.offsets)
                assertEquals(legacy, observed.copy(offsets = legacy.offsets))
            }
        }
        val preview = WorkspacePreviewBuilder().build(candidate(5))
        val result = assertNotNull(preview.modifierEvaluation)
        assertEquals(ModifierStatus.ACTIVE, result.entries[index].status)
        if (withSourceNeighbors) {
            for (type in listOf("eye.highlight", "eye.aftershock")) {
                val stage = stack.instances.indexOfFirst { it.type == type }
                assertTrue(stage in 1 until index, "$type must precede squash")
                assertEquals(ModifierStatus.ACTIVE, result.entries[stage].status)
                assertNotEquals(expected[stage - 1], expected[stage], "$type must change its stage")
            }
        }
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

    private fun withGlints(source: SourceArt, config: PipelineConfig): Pair<SourceArt, Map<String, LayerClassificationOverride>> {
        // DS has no separately drawn glints. Use small clipped source layers so the highlight modifier is active.
        val irises = RigGenerationSource.analyze(source, config).layers.filter {
            it.semantic.tag == SemanticTag.IRIDES && it.semantic.role == null && it.opaquePixels > 0
        }.distinctBy { it.semantic.side }
        assertEquals(2, irises.size)
        val additions = irises.associate { iris ->
            val layer = iris.source
            val size = (minOf(layer.bounds.width, layer.bounds.height) / 4).coerceAtLeast(4)
            val id = LayerId("squash-ds-glint-" + iris.semantic.side.name)
            val raster = LayerRaster(size, size, ByteArray(size * size * 4) { -1 })
            val bounds = LayerBounds(layer.bounds.left + layer.bounds.width / 3, layer.bounds.top + layer.bounds.height / 3, size, size)
            layer.id to (WorkspaceSourceLayer(id, id.raw, layer.groupPath, SourceLayerKind.Raster, true, layer.order, bounds,
                1f, true, LayerBlend.Normal, ChannelMask.ALL, raster, null, null, false) to
                LayerClassificationOverride(LayerType.PRESET, SemanticTag.IRIDES, iris.semantic.side, role = PartRole.HIGHLIGHT))
        }
        val art = object : SourceArt by source {
            override val layers = source.layers.flatMap { layer -> listOfNotNull(additions[layer.id]?.first, layer) }
                .mapIndexed { index, layer -> WorkspaceSourceLayer.copyOf(layer, index) }
        }
        return art to additions.values.associate { (layer, classification) -> layer.id.raw to classification }
    }

}
