package io.github.psd2live.core

import io.github.psd2live.algorithms.binding.MouthOutlineAlgorithm
import io.github.psd2live.core.modifier.*
import io.github.psd2live.project.rawConfig
import io.github.psd2live.scenario.Characters
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.JsonObject
import io.github.psd2live.core.recipe.BaseChoice
import io.github.psd2live.core.recipe.ModuleChoice
import io.github.psd2live.core.recipe.RigModules
import io.github.psd2live.core.recipe.RigRecipe
import kotlin.test.*

class MouthOutlinePluginParityTest {
    @Test fun candidateRecreatesLegacyRibbonPixelsTopologyKeysAndPath() = compare(fitted = false)

    @Test fun candidateRecreatesFittedRibbonFromDeclaredMouthMeasurements() = compare(fitted = true)

    private fun compare(fitted: Boolean) {
        val document = Characters.mouthed()
        val recipe = if (!fitted) RigRecipe.Legacy else RigRecipe(true,
            bases = mapOf("mouth" to BaseChoice("mouth/fitted")),
            modules = RigModules.modules.associate { it.id to ModuleChoice(false) })
        val config = document.rawConfig().copy(generateDeformers = true, meshOnly = false, deferRig = false,
            mouthOutlineEnabled = false, mouthThickness = 1.5f, mouthColor = null, recipe = recipe)
        val analysis = CharacterAnalyzer.analyze(document.source, config)
        val cache = PreviewMeshCache()
        val atlas = AtlasLayout.pack(analysis.layers, config)
        val source = RigBuilder.build(analysis, atlas, config, cache)
        val layer = analysis.layers.single { it.semantic.tag in setOf(SemanticTag.MOUTH, SemanticTag.MOUTH_OPEN) && it.semantic.role == null }
        val layerId = layer.source.id.raw
        val id = source.layerIdByDrawableId.entries.single { it.value == layerId }.key
        val geometry = RigBuilder.rigContext(analysis, config, cache)
        val target = ModifierTarget("mesh", id)
        val provider = "fitted".takeIf { fitted }
        val capture = RigMouthOutlineSource.capture(analysis, config,
            mapOf(id to RigMouthOutlineSource.Owner(layerId, id, fittedProvider = provider)),
            geometry.faceRig.coordinateSpace, cache = cache)
        val instance = RigModifierInstance("outline", "mouth.outline", 1, listOf(target),
            config = JsonObject(emptyMap()))
        val input = PuppetIr.toIr(source.puppet)
        val candidate = MouthOutlineAlgorithm()
        val context = capture.encode()
        if (fitted) assertFailsWith<IllegalArgumentException> { candidate.changes(input, instance, context) {} }
        val upstream = provider?.let { listOf(ModifierUpstreamCapability("mouth/fitted", "mouth/fitted", 1, listOf(target), it)) }.orEmpty()
        val changes = candidate.changes(input, instance, context.withUpstream(upstream)) {}
        assertEquals(2, changes.rasters.size)

        val legacyConfig = config.copy(mouthOutlineEnabled = true)
        val prepared = MouthLipLayers.prepare(analysis, legacyConfig, cache)
        val lipLayers = prepared.layers.filter { it.source is MouthLipLayer }
        val packed = RigModifierRasterAssets.append(atlas, lipLayers, legacyConfig)
        val expected = PuppetIr.toIr(RigBuilder.build(prepared, packed, legacyConfig, cache).puppet)
        val attached = changes.applyWithResources(input, {}, { rig, outputs, checkpoint ->
            RigModifierRasterAssets.attach(rig, outputs, legacyConfig, checkpoint)
        })
        for ((side, output) in changes.rasters.withIndex()) {
            val legacyId = "${id}_lip_$side"
            val old = expected.meshes.single { it.id == legacyId }
            val new = attached.meshes.single { it.id == output.mesh.id }
            val art = lipLayers.single { (it.source as MouthLipLayer).side == side }.source
            assertContentEquals(art.raster.rgba, output.pixels.read())
            assertEquals(listOf(art.bounds.left, art.bounds.top, art.bounds.width, art.bounds.height),
                listOf(output.layer.left, output.layer.top, output.layer.width, output.layer.height))
            assertEquals(old.geometry, new.geometry)
            assertEquals(old.offsets, new.offsets)
            val oldPath = expected.authoring.paths.single { it.mesh == legacyId }
            val newPath = attached.authoring.paths.single { it.mesh == new.id }
            assertEquals(oldPath.copy(id = instance.outputId("mouth-path:$id:$side:${oldPath.id}"), mesh = new.id), newPath)
            assertEquals(instance.outputId("mouth-lip:$id:$side"), new.id)
            assertEquals(instance.outputId("mouth-art:$id:$side"), output.layer.key)
        }
    }
}
