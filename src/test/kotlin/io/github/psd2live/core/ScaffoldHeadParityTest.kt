package io.github.psd2live.core

import io.github.psd2live.algorithms.binding.BindingProjection
import io.github.psd2live.algorithms.binding.ScaffoldAlgorithm
import io.github.psd2live.algorithms.binding.ScaffoldHead
import io.github.psd2live.algorithms.binding.ScaffoldFramesContext
import io.github.psd2live.algorithms.binding.ScaffoldBranchesContext
import io.github.psd2live.algorithms.binding.ScaffoldMeshContext
import io.github.psd2live.core.modifier.BuiltinModifierPlugins
import io.github.psd2live.core.modifier.ModifierTarget
import io.github.psd2live.core.modifier.RigModifierInstance
import io.github.psd2live.core.modifier.RigModifierPipeline
import io.github.psd2live.core.modifier.RigModifierSource
import io.github.psd2live.core.modifier.RigModifierStack
import io.github.psd2live.i18n.tr
import io.github.psd2live.project.rawConfig
import io.github.psd2live.scenario.Characters
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
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

    @Test fun historicalScaffoldInputsMatchWithoutBuildingALegacyRig() {
        for (document in listOf(Characters.figure(), Characters.browed())) {
            val config = document.rawConfig().let { it.copy(rigEdits = it.rigEdits.copy(modifierStack = RigModifierStack().toJson())) }
            val model = PSD2LivePipeline().buildPreview(document.source, config)
            val source = RigModifierSource.of(model)
            val native = source.sourceConfig().copy(featureDisplacementEnabled = true, hairSimulationFront = true)
            val atlas = model.modifierAuthorAtlas ?: model.atlas
            val cache = PreviewMeshCache()
            val legacy = RigBuilder.build(source.analysis, atlas, native, cache).puppet
            val neutral = BindingProjection.retain(legacy, emptySet(), mapOf(StandardParameters.MOUTH_OPEN to 1f))
            val all = model.rig.puppet.drawables.filter { it.mesh != null }.map { ModifierTarget("mesh", it.id.raw) }
            val reference = requireNotNull(BuiltinModifierPlugins.reference("rig.scaffold", 1))
            for (targets in listOf(all, all.take(1))) {
                val canonical = targets.associate { it.id to source.primaryByLayer.getValue(source.layersByMesh.getValue(it.id)) }
                val instance = RigModifierInstance("structure", "rig.scaffold", 1, targets, config = buildJsonObject {
                    put("featureDisplacement", true)
                    put("frontHairSway", false)
                })
                val current = source.scaffoldContext(instance)
                for ((version, schema) in listOf("1.0.36" to 1, "1.0.37" to 2, "1.0.38" to 3, "1.0.40" to 4)) {
                    val pinned = instance.copy(plugin = reference.copy(version = version))
                    val expected = ScaffoldAlgorithm.capture(neutral, model.baseRig.puppet, canonical,
                        current.values.getValue("organization").jsonObject.takeIf { schema >= 2 },
                        current.values.getValue("frames").jsonObject.takeIf { schema >= 3 },
                        current.values.getValue("branches").jsonObject.takeIf { schema >= 4 })
                    val actual = source.scaffoldContext(pinned)
                    assertEquals(JsonPrimitive("scaffold/$schema"), actual.values["schema"])
                    assertEquals(expected.values, actual.values)
                    assertEquals(expected.resources.mapValues { it.value.digest }, actual.resources.mapValues { it.value.digest },
                        "Historical package $version, target count " + targets.size)
                }
            }
        }
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
        val built = RigBuilder.build(analysis, atlas, config, cache)
        val generated = built.puppet
        val legacy = BindingProjection.retain(generated, emptySet(), mapOf(StandardParameters.MOUTH_OPEN to 1f))
        val inputs = RigBuilder.scaffoldSourceInputs(analysis, config, cache)
        val pluginFrames = ScaffoldFramesContext.build(inputs.frames)
        assertEquals(legacy.deformers.filter { ScaffoldFramesContext.owns(it.id) }.map(PuppetIr::deformerToIr),
            pluginFrames.map(PuppetIr::deformerToIr), "Frames computed from captured source measurements")
        val pluginBranches = ScaffoldBranchesContext.build(inputs.branches, pluginFrames)
        assertEquals(legacy.deformers.map(PuppetIr::deformerToIr), (pluginFrames + pluginBranches.deformers).map(PuppetIr::deformerToIr),
            "Complete structure computed from source measurements")
        val meshInputs = RigBuilder.scaffoldMeshInputs(analysis, atlas, config, cache,
            built.layerIdByDrawableId.entries.associate { it.value to it.key })
        val pluginMeshes = ScaffoldMeshContext.build(meshInputs, pluginBranches.parents)
        val expectedMeshes = legacy.drawables.map { drawable ->
            org.umamo.runtime.model.Drawable(drawable.id, drawable.id.raw, drawable.parentDeformerId,
                org.umamo.runtime.model.BlendMode.Normal, emptyList(), drawable.mesh, drawable.geometryGrid)
        }
        assertEquals(PuppetIr.toIr(legacy.copy(drawables = expectedMeshes)).meshes.associateBy { it.id },
            PuppetIr.toIr(legacy.copy(drawables = pluginMeshes)).meshes.associateBy { it.id },
            "Static meshes computed from unbound source topology")
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
