package io.github.psd2live.core

import io.github.psd2live.algorithms.binding.BindingProjection
import io.github.psd2live.algorithms.binding.LegacyFaceBinding
import io.github.psd2live.algorithms.binding.ScaffoldFramesContext
import io.github.psd2live.core.modifier.RigModifierPipeline
import io.github.psd2live.core.modifier.legacyFaceTuning
import io.github.psd2live.i18n.tr
import io.github.psd2live.project.rawConfig
import io.github.psd2live.scenario.Characters
import io.github.psd2live.bridge.PuppetIr
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LegacyFaceBindingParityTest {
    @Test fun faceAxesMatchLegacyDeformersWithDisplacementAndEarChannels() {
        val document = Characters.eyed(legacyEyes = true)
        val tuning = RigTuning(faceTurn = 43f, displacementTwist = 12f)
        val config = RigModifierPipeline.ordinaryConfig(document.rawConfig()).copy(
            generateDeformers = true, featureDisplacementEnabled = true, headTurnStrength = 1.7f, rigTuning = tuning,
        )
        val source = CharacterAnalyzer.analyze(document.source, config)
        val analysis = source.copy(layers = source.layers.map { layer ->
            if (layer.semantic.tag == SemanticTag.EYELASH)
                layer.copy(semantic = layer.semantic.copy(tag = SemanticTag.EARS))
            else layer
        })
        compare(analysis, config, emptySet())
    }

    @Test fun longHairAndBodyLeanKeepTheirLegacyAngleAxes() {
        val document = Characters.browed()
        val config = RigModifierPipeline.ordinaryConfig(document.rawConfig()).copy(
            generateDeformers = true, headTurnStrength = 0f, rigTuning = RigTuning(hairRollHold = 100f),
        )
        compare(CharacterAnalyzer.analyze(document.source, config), config,
            setOf(StandardParameters.BODY_LEAN, StandardParameters.PROPORTION))
    }

    private fun compare(analysis: PipelineAnalysis, config: PipelineConfig, upstream: Set<org.umamo.runtime.model.ParameterId>) {
        val cache = PreviewMeshCache()
        val atlas = AtlasLayout.pack(analysis.layers, config)
        val model = RigBuilder.build(analysis, atlas, config, cache).puppet
        val axes = setOf(StandardParameters.ANGLE_X, StandardParameters.ANGLE_Y, StandardParameters.ANGLE_Z)
        val angles = StandardParameters.all.filter { it.id in axes }
        val sourceDefaults = mapOf(StandardParameters.MOUTH_OPEN to 1f)
        val expected = BindingProjection.retain(model, upstream + axes, sourceDefaults)
        val before = BindingProjection.retain(model, upstream, sourceDefaults)
        val face = RigBuilder.rigContext(analysis, config, cache).faceRig
        val head = ScaffoldFramesContext.decode(RigBuilder.scaffoldSourceInputs(analysis, config, cache).frames).head
        val result = LegacyFaceBinding.bind(before,
            LegacyFaceBinding.Measurements(head, face.centerY, face.radiusX, face.radiusY, face.eyeLineY),
            config.rigTuning.legacyFaceTuning(), config.headTurnStrength, angles, tr("model.group.face"))
        assertEquals(expected.parameters, result.parameters)
        assertEquals(expected.parameterTree, result.parameterTree)
        assertEquals(expected.parameterLinks, result.parameterLinks)
        assertTrue(result.deformers.any { it.id.raw == "DeformHeadRotation" })
        assertTrue(result.deformers.any { it.id.raw == "DeformFaceNinePose" })
        assertEquals(expected.deformers.map { it.id }, result.deformers.map { it.id })
        for ((old, direct) in expected.deformers.zip(result.deformers)) {
            assertEquals(PuppetIr.deformerToIr(old), PuppetIr.deformerToIr(direct), old.id.raw)
        }
        assertTrue(result.drawables === before.drawables)
    }
}
