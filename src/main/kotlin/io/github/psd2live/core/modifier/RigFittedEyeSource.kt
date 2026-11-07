package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.EyeFittedInputs
import io.github.psd2live.algorithms.binding.ScaffoldBinding
import io.github.psd2live.algorithms.binding.ScaffoldBranchesContext
import io.github.psd2live.algorithms.binding.ScaffoldFramesContext
import io.github.psd2live.algorithms.binding.ScaffoldHead
import io.github.psd2live.core.*
import io.github.psd2live.core.recipe.BaseChoice
import io.github.psd2live.core.recipe.ModuleChoice
import io.github.psd2live.core.recipe.RigModules
import io.github.psd2live.core.recipe.RigRecipe
import io.github.psd2live.core.fit.EyeArtworkFitting
import io.github.psd2live.core.fit.EyeOpeningAssociation
import io.github.psd2live.project.WorkspaceSettingsCodec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import io.github.psd2live.i18n.tr
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Captures source observations, never an eye fit or generated motion. */
internal object RigFittedEyeSource {
    data class Measurements(
        val layers: List<ClassifiedLayer>,
        val faceCenterX: Float,
        val headSpace: HeadCoordinateSpace,
        val headLayers: Set<String>,
        val aligned: Map<String, Pair<ClassifiedLayer, List<Bounds>>>,
    )

    fun parameterNames(): Map<String, String> {
        val ids = RigLegacyBindings.spec("eye/fitted").parameters
        return StandardParameters.all.filter { it.id in ids }.associate { it.id.raw to it.name }
    }

    fun capture(
        instance: RigModifierInstance, provider: RigModifierInstance, analysis: PipelineAnalysis, ordinary: PipelineConfig,
        scaffold: ModifierAlgorithmContext, measurements: Measurements, layersByMesh: Map<String, String>, primaryByLayer: Map<String, String>,
    ): ModifierAlgorithmContext {
        val corrections = WorkspaceSettingsCodec.decodeRecipe(provider.config["corrections"])
        val ordinaryMasks = RigOrdinaryMaterials.clipping(analysis.source, primaryByLayer.entries.associate { it.value to it.key })
        val layers = measurements.layers
        val frames = ScaffoldFramesContext.build(scaffold.values.getValue("frames").jsonObject)
        val branches = ScaffoldBranchesContext.build(scaffold.values.getValue("branches").jsonObject, frames)
        val gazeIds = Side.entries.mapTo(hashSetOf(), ScaffoldHead::gazeId)
        val canonicalMeshes = scaffold.values.getValue("mesh_inputs").jsonObject.getValue("materials").jsonArray
            .mapTo(hashSetOf()) { it.jsonObject.getValue("id").jsonPrimitive.content }
        val byLayer = layers.associateBy { it.source.id.raw }
        val targets = instance.targets.mapNotNull { target ->
            val layer = byLayer[layersByMesh[target.id]] ?: return@mapNotNull null
            val canonical = primaryByLayer.getValue(layer.source.id.raw)
            if (layer.opaquePixels == 0 || canonical !in canonicalMeshes) return@mapNotNull null
            target.id to layer.source.id.raw
        }.toMap()
        val source = RigSourceCapture.capture(layers, targets)
        val space = measurements.headSpace
        val radians = space.angleDegrees * PI.toFloat() / 180f
        val meshAlignment = EyeFittedInputs.Alignment(space.pivotX, space.pivotY, cos(radians), sin(radians))
        val firstWhite = layers.firstOrNull { it.semantic.tag == SemanticTag.EYEWHITE && it.opaquePixels > 0 }
        val alignment = meshAlignment.takeIf { firstWhite != null && firstWhite.source.id.raw in measurements.headLayers }
        val origin = alignment?.point(0f, 0f)
        val moves = mapOf(Side.LEFT to "eye.L", Side.RIGHT to "eye.R").mapKeys { it.key.name }.mapValues { (_, feature) ->
            corrections.moves(feature).mapValues { (_, move) ->
                val point = alignment?.point(move.dx, move.dy)
                if (point == null || origin == null) move.dx to move.dy
                else (point.first - origin.first) to (point.second - origin.second)
            }
        }
        val gaze = linkedSetOf<String>()
        val materials = targets.mapValues { (id, layerId) ->
            val layer = byLayer.getValue(layerId)
            val (aligned, whites) = measurements.aligned.getValue(layerId)
            val (parent, frame) = branches.parents.getValue(layerId)
            if (parent in gazeIds) gaze += ScaffoldBinding.deformerId(provider, id, parent.raw)
            EyeFittedInputs.Material(
                primaryByLayer.getValue(layerId), ScaffoldBinding.deformerId(provider, id, parent.raw),
                frame.sourceBounds(), aligned.bounds.sourceBounds(), aligned.centroidX, aligned.centroidY,
                whites.map { it.sourceBounds() },
                opening = EyeOpeningAssociation.observation(layer),
                aligned = layerId in measurements.headLayers,
                ordinaryMask = ordinaryMasks[layerId]?.raw,
                preset = (ordinary.layerOverrides[layerId]?.type ?: layer.semantic.type) == LayerType.PRESET,
                useSourceColumns = measurements.headSpace.angleDegrees == 0f,
            )
        }
        val openings = provider.targets.mapNotNull { target ->
            val layer = byLayer[layersByMesh[target.id]] ?: return@mapNotNull null
            if (layer.semantic.tag != SemanticTag.EYEWHITE || layer.opaquePixels == 0) return@mapNotNull null
            target.id to EyeOpeningAssociation.observation(layer)
        }.toMap()
        val sourceMeshes = scaffold.resources.getValue("source_meshes")
        val tuning = RigLegacyBindings.tuning(instance, RigTuning())
        return EyeFittedInputs(
            measurements.faceCenterX, alignment, meshAlignment, materials,
            layers.filter { it.semantic.tag == SemanticTag.EYELASH && it.opaquePixels > 0 }
                .associate { it.source.id.raw to EyeArtworkFitting.stamp(it) },
            corrections.eyeParts, moves, parameterNames(), tr("model.group.eyes"), gaze, openings,
            EyeFittedInputs.Tuning(tuning.gazeX, tuning.gazeY, tuning.blinkDepth, tuning.blinkCorner,
                tuning.eyelashSquash, tuning.irisJellySquash, tuning.irisJellyStretch),
        ).capture(source, sourceMeshes).withUpstream(listOf(
            ModifierUpstreamCapability("rig.scaffold", provider.type, provider.version, provider.targets,
                provider.id, provider.config, provider.outputAliases, provider.plugin),
        ))
    }

    fun captureTransition(owner: RigModifierSource, instance: RigModifierInstance): ModifierAlgorithmContext {
        val structure = owner.provider(instance)
        require(structure.version == 2) { "Fitted eye v3 requires a version 2 source scaffold" }
        val ordinary = owner.scaffoldContext(structure)
        val options = structure.config
        val corrections = io.github.psd2live.project.WorkspaceSettingsCodec.decodeRecipe(options["corrections"])
        val fitted = owner.sourceConfig().copy(
            recipe = RigRecipe(true, bases = mapOf("eye" to BaseChoice("eye/fitted", instance.version)),
                modules = RigModules.modules.associate { it.id to ModuleChoice(false) },
                moves = corrections.moves, eyeParts = corrections.eyeParts),
            featureDisplacementEnabled = options["featureDisplacement"]?.jsonPrimitive?.boolean ?: false,
            hairSimulationFront = !(options["frontHairSway"]?.jsonPrimitive?.boolean ?: true),
            hairSimulationBack = !(options["backHairSway"]?.jsonPrimitive?.boolean ?: true),
        )
        val source = RigBuilder.scaffoldSourceInputs(owner.analysis, fitted, owner.meshCache)
        val canonical = ordinary.values.getValue("canonical_targets").jsonObject
        val layers = canonical.entries.associate { owner.layersByMesh.getValue(it.key) to it.value.jsonPrimitive.content }
        val meshes = RigBuilder.scaffoldMeshInputs(owner.analysis, owner.atlas, fitted, owner.meshCache, layers)
        val refined = ModifierAlgorithmContext(JsonObject(ordinary.values + mapOf(
            "frames" to source.frames, "branches" to source.branches, "mesh_inputs" to meshes.values.getValue("mesh_inputs"),
        )), ordinary.resources + meshes.resources)
        val eye = capture(instance, structure, owner.analysis, fitted, refined, requireNotNull(source.eyes),
            owner.layersByMesh, owner.primaryByLayer)
        return io.github.psd2live.algorithms.binding.EyeFittedSourceAlgorithm.capture(eye, ordinary, refined)
    }

    private fun Bounds.sourceBounds() = ModifierSourceBounds(left, top, right, bottom)
}
