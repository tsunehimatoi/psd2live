package io.github.psd2live.core

import io.github.psd2live.algorithms.binding.BindingProjection
import io.github.psd2live.algorithms.binding.ScaffoldHead
import io.github.psd2live.core.modifier.RigModifierPipeline
import io.github.psd2live.i18n.tr
import io.github.psd2live.project.rawConfig
import io.github.psd2live.scenario.Characters
import io.github.psd2live.targets.cubism.PuppetIr
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScaffoldHeadParityTest {
    @Test fun mouthArtworkKeepsTheOpenDefaultProjection() {
        val document = Characters.mouthed()
        val config = RigModifierPipeline.ordinaryConfig(document.rawConfig()).copy(generateDeformers = true)
        compare(CharacterAnalyzer.analyze(document.source, config), config)
    }

    @Test fun browsAndHairMatchTheLegacyNeutralProjection() {
        val document = Characters.browed()
        val config = RigModifierPipeline.ordinaryConfig(document.rawConfig()).copy(
            generateDeformers = true,
            featureDisplacementEnabled = true,
            hairSimulationFront = true,
        )
        val analysis = CharacterAnalyzer.analyze(document.source, config)
        val ids = compare(analysis, config)
        assertTrue(ids.containsAll(listOf("DeformBrows", "DeformBrowShapeL", "DeformBrowShapeR",
            "DeformFeatureDisplacement", "DeformHairFrontFollow", "DeformHairBackPhysics")))
        assertTrue("DeformHairFrontPhysics" !in ids)
    }

    @Test fun gazeAndEarChannelsMatchTheLegacyNeutralProjection() {
        val document = Characters.eyed(legacyEyes = true)
        val config = RigModifierPipeline.ordinaryConfig(document.rawConfig()).copy(generateDeformers = true)
        val source = CharacterAnalyzer.analyze(document.source, config)
        // Give both sides visible ear artwork while retaining eye whites and irises in the same fixture.
        val analysis = source.copy(layers = source.layers.map { layer ->
            if (layer.semantic.tag == SemanticTag.EYELASH)
                layer.copy(semantic = layer.semantic.copy(tag = SemanticTag.EARS))
            else layer
        })
        val ids = compare(analysis, config)
        assertTrue(ids.containsAll(listOf("DeformEyes", "DeformEyeGazeL", "DeformEyeGazeR",
            "DeformEars", "DeformEarOcclusionL", "DeformEarOcclusionR")))
        assertTrue("DeformFeatureDisplacement" !in ids)
    }

    private fun compare(analysis: PipelineAnalysis, config: PipelineConfig): List<String> {
        val cache = PreviewMeshCache()
        val context = RigBuilder.rigContext(analysis, config, cache)
        val rig = context.faceRig
        val canvasPivot = context.headSpace.toCanvas(rig.centerX, rig.mouthLineY)
        val input = ScaffoldHead.Input(
            bodyFrame = context.bodyFrame,
            headFrame = context.head,
            faceFrame = context.face,
            alignedPivot = ScaffoldHead.Point(rig.centerX, rig.mouthLineY),
            canvasPivot = ScaffoldHead.Point(canvasPivot.first, canvasPivot.second),
            initialAngle = rig.initialAngleZ,
            featureDisplacement = config.featureDisplacementEnabled,
            regions = rig.regions.map { region ->
                val side = tr("side.${region.side.name.lowercase()}")
                val feature = tr("model.feature.${region.feature.name.lowercase()}")
                ScaffoldHead.Region(
                    ScaffoldHead.Feature.valueOf(region.feature.name), region.side, region.bounds,
                    if (region.side == Side.NONE) feature else tr("model.feature.name", side, feature),
                    if (region.feature == FaceFeature.IRIS) tr("model.deformer.gaze", side) else null,
                )
            },
            frontHair = context.frontHair?.let { ScaffoldHead.Hair(it, !config.hairSimulationFront) },
            backHair = context.backHair?.let { ScaffoldHead.Hair(it, !config.hairSimulationBack) },
        )
        val actual = ScaffoldHead.build(input, { tr(it) })
        val atlas = AtlasLayout.pack(analysis.layers, config)
        val generated = RigBuilder.build(analysis, atlas, config, cache).puppet
        val legacy = BindingProjection.retain(generated, emptySet(), mapOf(StandardParameters.MOUTH_OPEN to 1f))
        val optimized = RigBuilder.buildNeutralScaffold(analysis, atlas, config, cache).puppet
        val optimizedNeutral = BindingProjection.retain(optimized, emptySet(), mapOf(StandardParameters.MOUTH_OPEN to 1f))
        val before = PuppetIr.toIr(legacy)
        val after = PuppetIr.toIr(optimizedNeutral)
        assertEquals(before.deformers, after.deformers, "Complete static deformer scaffold")
        assertEquals(before.meshes.map { it.id }, after.meshes.map { it.id })
        before.meshes.zip(after.meshes).forEach { (old, fresh) -> assertEquals(old, fresh, "Static mesh ${old.id}") }
        assertEquals(before, after, "Complete static scaffold after direct head generation")
        val coreIds = setOf("DeformBodyZBreath", "DeformHeadRotation", "DeformHeadContainer", "DeformFaceNinePose",
            "DeformFaceContour", "DeformFeatureDisplacement", "DeformEyes", "DeformBrows", "DeformEars",
            "DeformHairFrontFollow", "DeformHairFrontPhysics", "DeformHairBackFollow", "DeformHairBackPhysics")
        val featureIds = listOf("EyeShape", "IrisPreserve", "BrowShape", "NoseShape", "MouthShape", "EarOcclusion", "EyeGaze")
            .flatMap { feature -> listOf("L", "R", "Both").map { side -> "Deform$feature$side" } }
        val expected = legacy.deformers.filter { it.id.raw in coreIds || it.id.raw in featureIds }
        assertEquals(expected.map { it.id }, actual.deformers.map { it.id })
        for ((old, fresh) in expected.zip(actual.deformers)) {
            assertEquals(PuppetIr.deformerToIr(old), PuppetIr.deformerToIr(fresh), old.id.raw)
            assertEquals(context.resolveFrame(old.id, config), actual.frames[fresh.id], "${old.id.raw} child frame")
        }
        return actual.deformers.map { it.id.raw }
    }
}
