package io.github.psd2live.core

import io.github.psd2live.algorithms.binding.MouthFittedAlgorithm
import io.github.psd2live.algorithms.binding.MouthFittedInputs
import io.github.psd2live.core.recipe.BaseChoice
import io.github.psd2live.core.recipe.ModuleChoice
import io.github.psd2live.core.recipe.RigModules
import io.github.psd2live.core.recipe.RigRecipe
import io.github.psd2live.project.rawConfig
import io.github.psd2live.scenario.Characters
import io.github.psd2live.bridge.PuppetIr
import org.umamo.runtime.model.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class FittedMouthPluginParityTest {
    @Test fun sourceCaptureReproducesTheExistingFittedMouthBinding() {
        compare(false)
    }

    @Test fun depthTurnKeepsTheExistingMouthAndFaceBinding() {
        compare(true)
    }

    private fun compare(depthTurn: Boolean) {
        val document = Characters.mouthed()
        val config = document.rawConfig().copy(generateDeformers = true, mouthOutlineEnabled = false,
            recipe = RigRecipe(true, bases = mapOf("mouth" to BaseChoice("mouth/fitted")),
                modules = RigModules.modules.associate { it.id to ModuleChoice(depthTurn && it == RigModules.FACE_DEPTH_TURN) }))
        val analysis = CharacterAnalyzer.analyze(document.source, config)
        val cache = PreviewMeshCache()
        val context = RigBuilder.rigContext(analysis, config, cache)
        val atlas = AtlasLayout.pack(analysis.layers, config)
        var captured: (() -> PuppetModel)? = null
        val built = RigBuilder.build(analysis, atlas, config, cache) { captured = it }
        val original = requireNotNull(captured).invoke()
        val layersById = analysis.layers.associateBy { it.source.id.raw }
        val layers = built.layerIdByDrawableId.mapValues { (_, id) -> layersById.getValue(id) }
        val frames = layers.mapValues { (_, layer) -> context.parentAndFrame(layer, config).second }
        val inputs = RigFittedMouthSource.capture(context, config, original, layers, frames,
            original.drawables.map { it.id.raw }, if (depthTurn) "head-provider" else null)
        assertNotNull(inputs.source)
        val transported = MouthFittedInputs.decode(inputs.encode())
        val mouthIds = layers.filterValues { it.semantic.tag in setOf(SemanticTag.MOUTH, SemanticTag.MOUTH_OPEN) }
            .keys
        // Test-only removal of the old mouth output provides the same untouched source mesh and other bindings.
        val neutral = original.copy(drawables = original.drawables.map {
            if (it.id.raw in mouthIds) it.copy(geometryGrid = null, blendShapes = emptyList()) else it
        })
        val result = MouthFittedAlgorithm().project(neutral, layers.keys, transported)
        val expected = PuppetIr.toIr(built.puppet)
        val actual = PuppetIr.toIr(result)
        assertEquals(expected.meshes, actual.meshes)
        assertEquals(expected.deformers, actual.deformers)
        assertEquals(expected.parameters, actual.parameters)
    }
}
