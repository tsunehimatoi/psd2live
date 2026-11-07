package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.HairChainsAlgorithm
import io.github.psd2live.algorithms.binding.HairChainsInputs
import io.github.psd2live.algorithms.binding.HairFollowMotion
import io.github.psd2live.algorithms.binding.ScaffoldPairs
import io.github.psd2live.core.*
import io.github.psd2live.core.fit.HairKind
import io.github.psd2live.core.recipe.RigRecipe
import io.github.psd2live.i18n.tr
import io.github.psd2live.project.WorkspaceSettingsCodec
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.ParameterId

internal fun RigModifierSource.hairChainsContext(instance: RigModifierInstance): ModifierAlgorithmContext {
    val scaffold = provider(instance)
    val corrections = WorkspaceSettingsCodec.decodeRecipe(scaffold.config["corrections"])
    val config = sourceConfig().copy(recipe = RigRecipe.Legacy.copy(moves = corrections.moves, eyeParts = corrections.eyeParts),
        rigTuning = RigTuning(), mouthOutlineEnabled = false)
    val impulses = activeBefore(instance).filter { RigLegacyBindings.find(it.type)?.expressionToggles == true }
        .flatMap { expressionIds(it).map(ParameterId::raw) }
    return RigHairChainsSource.capture(analysis, config, meshCache, scaffold,
        layersByMesh.entries.groupBy({ it.value }, { it.key }), impulses).context()
}

/** Captures classified artwork and aligned measurements without generating a binding candidate. */
internal object RigHairChainsSource {
    fun capture(
        analysis: PipelineAnalysis, config: PipelineConfig, meshCache: PreviewMeshCache,
        provider: RigModifierInstance, meshesByLayer: Map<String, List<String>>, impulses: List<String>,
    ): HairChainsInputs {
        val measured = RigSourceMeasurements.meshFramedAnalysis(analysis, config, meshCache)
        val layout = measured.calibration ?: measured
        val face = NinePoseFaceRig.from(layout, config.rigTuning)
        val space = face.coordinateSpace
        val planes = listOf(SemanticTag.FRONT_HAIR to "Front", SemanticTag.BACK_HAIR to "Back").mapNotNull { (tag, side) ->
            val frame = layout.layers.filter { it.semantic.tag == tag && it.opaquePixels > 0 }
                .map { space.boundsToAligned(it.bounds) }.reduceOrNull(Bounds::union)?.expanded(0.04f) ?: return@mapNotNull null
            val sway = provider.config["${side.lowercase()}HairSway"]?.jsonPrimitive?.booleanOrNull != false
            side to HairChainsInputs.Plane(frame, "DeformHair${side}Follow",
                "DeformHair${side}Physics".takeIf { sway }, "PartHair$side")
        }.toMap()
        val layers = measured.layers.mapNotNull { layer ->
            val side = when (layer.semantic.tag) {
                SemanticTag.FRONT_HAIR -> "Front"
                SemanticTag.BACK_HAIR -> "Back"
                else -> return@mapNotNull null
            }
            val plane = planes[side] ?: return@mapNotNull null
            Triple(layer, side, plane)
        }
        val paired = ScaffoldPairs.build(layers.filter { it.first.opaquePixels > 0 }.map { (layer, _, plane) ->
            ScaffoldPairs.Layer(layer.source.id.raw, layer.source.name, layer.semantic.tag.name, layer.semantic.side,
                space.boundsToAligned(layer.bounds), DeformerId(plane.sway ?: plane.follow), plane.frame, PartId(plane.part))
        }, emptySet(), { it }).flatMapTo(HashSet()) { it.layerIds }
        val token = HairChainsAlgorithm.NAME_TOKEN
        val headCandidates = layout.layers.filter { layer -> layer.opaquePixels > 0 &&
            (layer.semantic.tag.group == LayerGroup.HEAD ||
                layer.semantic.tag.group == LayerGroup.UNKNOWN && layer.bounds.centerY <= measured.anchors.face.bottom) }
        val head = headCandidates.map { space.boundsToAligned(it.bounds) }.reduceOrNull(Bounds::union)?.expanded(0.025f) ?: face.face
        val faceBounds = layout.layers.filter { it.opaquePixels > 0 && it.semantic.tag in RigSourceMeasurements.faceTags }
            .map { space.boundsToAligned(it.bounds) }.fold(face.face, Bounds::union).expanded(0.025f)
        val pivot = space.toCanvas(face.centerX, face.mouthLineY)
        val headInput = HairFollowMotion.Head(head, pivot.first, pivot.second, faceBounds.bottom)
        return HairChainsInputs(headInput, face.eyeLineY, face.radiusX, face.radiusY, planes, layers.map { (layer, side, plane) ->
            val id = layer.source.id.raw
            HairChainsInputs.Layer(id, side, space.boundsToAligned(layer.bounds), layer.opaquePixels,
                HairKind.of(layer)?.motion?.takeIf { plane.sway != null },
                layer.semantic.role in setOf(PartRole.SHADOW, PartRole.HIGHLIGHT), id in paired,
                meshesByLayer[id].orEmpty(), tr("model.deformer.hairChain", layer.source.name),
                tr("model.parameter.hairChain", layer.source.name, token))
        }, tr("model.group.physics"), listOf("model.physics.hairChain", "model.physics.hairBounce").associateWith { tr(it, token) },
            impulses)
    }
}
