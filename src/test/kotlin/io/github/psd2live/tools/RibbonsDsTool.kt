package io.github.psd2live.tools

import io.github.psd2live.application.*
import io.github.psd2live.core.*
import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.Deformer
import io.github.psd2live.project.*
import org.umamo.format.art.*
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.*

class RibbonsDsTool {
    @Test fun compareWorkspaceRibbons(): Unit = runBlocking { compareRibbons(includeSkirt = true) }

    @Test fun compareWorkspaceRibbonsWithoutSkirt(): Unit = runBlocking { compareRibbons(includeSkirt = false) }

    @Test fun compareLayeredMouthRibbons(): Unit = runBlocking { compareRibbons(includeSkirt = true, layered = true) }

    @Test fun compareLayeredMouthRibbonsWithoutSkirt(): Unit = runBlocking {
        compareRibbons(includeSkirt = false, layered = true)
    }

    private suspend fun compareRibbons(includeSkirt: Boolean, layered: Boolean = false) {
        requireTools()
        val config = PipelineConfig(meshSpacing = 40)
        val artwork = PSD2LivePipeline().readPsd(Sample.fromEnvironment().path)
        val overlay = RigEditOverlay(modifierStack = RigModifierStack().toJson())
        val settings = WorkspaceSettingsCodec.encode(config.copy(rigEdits = overlay))
        val original = WorkspaceDocument(artwork, emptyMap(), emptySet(), emptyMap(), emptyMap(), overlay, settings)
        val preset = WorkspaceInitialPreset()
        val (originalPlanned, originalModel) = WorkspacePresetInitialization.prepare(original, WorkspaceRasterWork.Direct, preset)
        val originalStack = RigModifierStack.fromJson(assertNotNull(originalPlanned.rigEdits.modifierStack))
        assertTrue(originalStack.instances.none { it.type == "outfit.ribbons" })
        val skirtLayers = originalModel.analysis.layers.filter { it.opaquePixels > 0 && it.semantic.role == PartRole.SKIRT }
        assertTrue(skirtLayers.isNotEmpty())
        val skirtOverrides = if (includeSkirt) emptyMap() else plainSkirtOverrides(skirtLayers)
        val document = ribbonDsDocument(artwork, config.copy(rigEdits = overlay), layered, skirtOverrides)
        val layeredPrefix = if (layered) layeredRibbonMouthVersions else emptyMap()
        val versions = mapOf("face/fitted" to 3, "eye/fitted" to 5, "brow/fitted" to 4,
            "mouth/fitted" to 6, "mouth.outline" to 8, "mouth.vowel" to 7, "mouth.capture" to 6,
            "mouth.jaw" to 8, "mouth.cheek-lid" to 8, "mouth.spring" to 9,
            "face.neck" to 3, "body.arms" to 3, "body.bust" to 3,
            "outfit.ribbons" to 1, "outfit.creature" to 2,
            "eye.smile" to 4, "eye.wide" to 4, "eye.gaze-lid" to 4, "eye.emotion" to 4, "eye.aftershock" to 5, "eye.squash" to 5,
            "brow.shape" to 5, "brow.lid-coupling" to 7, "brow.blink" to 5, "brow.spring" to 6) + layeredPrefix +
            if (includeSkirt) mapOf("outfit.skirt" to 2) else emptyMap()
        val (planned, historical) = WorkspacePresetInitialization.prepare(document, WorkspaceRasterWork.Direct,
            preset, requestedVersions = versions)
        val stack = RigModifierStack.fromJson(assertNotNull(planned.rigEdits.modifierStack))

        assertEquals(includeSkirt, stack.instances.any { it.type == "outfit.skirt" })
        assertEquals(includeSkirt, historical.analysis.layers.any { it.opaquePixels > 0 && it.semantic.role == PartRole.SKIRT })
        if (!includeSkirt) {
            val analyzed = historical.analysis.layers.associateBy { it.source.id }
            for (originalLayer in skirtLayers) {
                val retained = assertNotNull(analyzed[originalLayer.source.id])
                assertNull(retained.semantic.role)
                assertEquals(originalLayer.opaquePixels, retained.opaquePixels)
            }
        }
        val historicalEvaluation = assertNotNull(historical.modifierEvaluation)
        for ((type, version) in versions) {
            val provider = historicalEvaluation.entries.single { it.instance.type == type }
            assertEquals(version, provider.instance.version, type)
            assertEquals(ModifierStatus.ACTIVE, provider.status, type)
        }

        val entry = stack.instances.single { it.type == "outfit.ribbons" }
        val index = stack.index(entry.id)
        if (layered) {
            val fit = assertNotNull(RigBuilder.rigContext(historical.analysis, config).fittedMouth(config))
            assertTrue(fit.layered && fit.rig?.fit?.layered != null)
            for ((type, version) in layeredPrefix) {
                val provider = stack.instances.single { it.type == type }
                assertTrue(stack.index(provider.id) < index, type)
                assertEquals(assertNotNull(BundledAlgorithmArtifacts.find(type, version)).reference, provider.plugin)
            }
        }
        val before = historicalEvaluation.outputs[index - 1]
        val after = historicalEvaluation.outputs[index]
        val prior = before.meshes.associateBy { it.id }
        val changed = after.meshes.filter { it != prior[it.id] }
        assertEquals(setOf("ArtMeshTopwear", "ArtMeshHeadwear"), changed.mapTo(hashSetOf()) { it.id })
        assertTrue(changed.all { it.visible && it.opacity > 0f && it.parent != prior.getValue(it.id).parent })
        val carriers = changed.map { mesh -> after.deformers.single { it.id == mesh.parent } as Deformer.Warp }
        assertEquals(2, carriers.mapTo(hashSetOf()) { it.parent }.size, "Fixture must use separate head and body frames")
        val physics = after.physics.groups.filter { it !in before.physics.groups }
        for (carrier in carriers) {
            assertTrue(before.deformers.none { it.id == carrier.id })
            assertTrue(carrier.shapes.any { binding -> binding.shapes.filterNotNull().any { it.points.shared().any { point -> point != 0f } } })
            assertTrue(physics.any { group -> group.inputs.any { it.weight > 0f } &&
                group.outputs.any { output -> output.weight > 0f && carrier.shapes.any { it.parameter == output.parameter } } })
        }

        fun select(version: Int): Pair<WorkspaceDocument, RigModifierInstance> {
            val artifact = assertNotNull(BundledAlgorithmArtifacts.find(entry.type, version))
            val selected = entry.copy(version = version, plugin = artifact.reference)
            val next = stack.copy(instances = stack.instances.map { if (it.id == entry.id) selected else it })
            return planned.copy(rigEdits = planned.rigEdits.copy(modifierStack = next.toJson())) to selected
        }
        if (!layered || includeSkirt) {
            val v2 = WorkspacePreviewBuilder().build(select(2).first)
            val plain = assertNotNull(v2.modifierEvaluation).outputs[index]
            val head = changed.single { it.id == "ArtMeshHeadwear" }
            val actualHead = plain.meshes.single { it.id == head.id }
            assertNotEquals(head.offsets, actualHead.offsets)
            val corrected = plain.copy(meshes = plain.meshes.map { if (it.id == head.id) it.copy(offsets = head.offsets) else it })
            assertTrue(after == corrected, "v2 differs in more than the pendant offsets")
            assertContains(assertFailsWith<IllegalArgumentException> { WorkspacePreviewBuilder().build(select(3).first) }.message.orEmpty(),
                "Ribbons v3 requires mouth/fitted@4")
            assertContains(assertFailsWith<IllegalArgumentException> { WorkspacePreviewBuilder().build(select(4).first) }.message.orEmpty(),
                "Ribbons v4 requires a fitted open or closed mouth source")

            if (layered) {
                assertContains(assertFailsWith<IllegalArgumentException> { WorkspacePreviewBuilder().build(select(5).first) }.message.orEmpty(),
                    "Ribbons v5 requires mouth/fitted@6")
            }
        }
        val version = if (layered) 6 else 5
        val (candidate, selected) = select(version)
        val model = WorkspacePreviewBuilder().build(candidate)
        val actual = assertNotNull(model.modifierEvaluation)
        assertEquals(ModifierStatus.ACTIVE, actual.entries[index].status)
        for (stage in stack.instances.indices) {
            assertEquals(historicalEvaluation.outputs[stage], actual.outputs[stage], stack.instances[stage].type)
        }
        assertEquals(RigIrCompiler.compile(historical, tileArt = true), RigIrCompiler.compile(model, tileArt = true))
        if (!layered) {
            val (_, defaultModel) = WorkspacePresetInitialization.prepare(document, WorkspaceRasterWork.Direct, preset)
            val defaultStage = assertNotNull(defaultModel.modifierEvaluation).entries.single { it.instance.type == entry.type }
            assertEquals(5, defaultStage.instance.version)
            assertEquals(selected.plugin, defaultStage.instance.plugin)
            assertEquals(ModifierStatus.ACTIVE, defaultStage.status)
            assertEquals(RigIrCompiler.compile(model, tileArt = true), RigIrCompiler.compile(defaultModel, tileArt = true))
        }

        val artifact = assertNotNull(BundledAlgorithmArtifacts.find(entry.type, version))
        val directory = Files.createTempDirectory("ribbons-ds-")
        val preferences = Preferences.userRoot().node("ribbons-ds/" + UUID.randomUUID())
        val store = ModifierPluginStore(directory.resolve("installed"), preferences)
        try {
            BundledAlgorithmArtifacts.retain(store, listOf(artifact))
            val worker = store.snapshot().single()
            val context = assertNotNull(actual.readAlgorithms[entry.id]).context
            assertEquals(includeSkirt, context.upstream.any { it.capability == "outfit.skirt" })
            for ((type, required) in layeredPrefix) {
                assertEquals(required, context.upstream.single { it.capability == type }.version)
            }
            assertEquals(after, ModifierPluginWorkers.evaluate(worker, worker.manifest.algorithms.single(), before,
                selected, {}, store, context = context))
        } finally {
            ModifierPluginWorkers.stop(artifact.reference)
            preferences.removeNode()
            ModifierPluginStore.deleteTree(directory)
        }
    }
}

internal fun plainSkirtOverrides(skirts: List<ClassifiedLayer>): Map<String, LayerClassificationOverride> = skirts.associate { layer ->
    val semantic = layer.semantic
    layer.source.id.raw to LayerClassificationOverride(semantic.type, semantic.tag, semantic.side,
        semantic.parameter, semantic.switchId, PartRole.PLAIN)
}

internal val layeredRibbonMouthVersions = mapOf("mouth/fitted" to 8, "mouth.outline" to 11, "mouth.vowel" to 8,
    "mouth.capture" to 7, "mouth.jaw" to 10, "mouth.cheek-lid" to 10, "mouth.spring" to 11)

internal fun ribbonDsDocument(
    artwork: SourceArt, config: PipelineConfig, layered: Boolean,
    extraOverrides: Map<String, LayerClassificationOverride> = emptyMap(),
): WorkspaceDocument {
    fun layer(id: String, x: Int, y: Int, width: Int, height: Int) =
        WorkspaceSourceLayer(LayerId(id), id, "", SourceLayerKind.Raster, true, 0,
            LayerBounds(x, y, width, height), 1f, false, LayerBlend.Normal, ChannelMask.ALL,
            LayerRaster(width, height, ByteArray(width * height * 4) { -1 }), null, null, false)
    val ribbon = layer("ds-cloth-ribbon", 565, 455, 16, 63)
    val pendant = layer("ds-head-pendant", 674, 212, 13, 31)
    val source = object : SourceArt by artwork {
        override val layers = (listOf(ribbon, pendant) + artwork.layers)
            .mapIndexed { index, layer -> WorkspaceSourceLayer.copyOf(layer, index) }
    }
    val overrides = mapOf(ribbon.id.raw to LayerClassificationOverride(LayerType.PRESET, SemanticTag.TOPWEAR, Side.NONE, role = PartRole.RIBBON),
        pendant.id.raw to LayerClassificationOverride(LayerType.PRESET, SemanticTag.HEADWEAR, Side.NONE, role = PartRole.PENDANT)) + extraOverrides
    return if (layered) {
        val lips = layeredMouthDocument(source, config)
        lips.copy(layerOverrides = lips.layerOverrides + overrides)
    } else WorkspaceDocument(source, emptyMap(), emptySet(), overrides, emptyMap(), config.rigEdits, WorkspaceSettingsCodec.encode(config))
}
