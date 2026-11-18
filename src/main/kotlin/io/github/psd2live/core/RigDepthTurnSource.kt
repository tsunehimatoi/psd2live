package io.github.psd2live.core

import io.github.psd2live.algorithms.binding.DepthTurnInputs
import io.github.psd2live.algorithms.binding.DepthTurnMotion
import io.github.psd2live.algorithms.binding.DepthTurnMouthWarp
import io.github.psd2live.core.modifier.ModifierAlgorithmResource
import io.github.psd2live.core.recipe.RigModules
import io.github.psd2live.i18n.tr
import org.umamo.runtime.model.DeformerId
import org.umamo.format.art.SourceLayer
import java.text.Normalizer
import java.util.Locale

/** Adapts source analysis and a preceding binding's canonical frame identities to depth_turn/1. */
internal object RigDepthTurnSource {
    fun capture(
        context: RigBuilder.RigContext,
        config: PipelineConfig,
        layers: Map<String, ClassifiedLayer>,
        parents: Map<String, DeformerId?>,
        parentFrames: Map<DeformerId, Bounds>,
        scaffoldMeshes: ModifierAlgorithmResource? = null,
        scaffoldMeshIds: Map<String, String> = emptyMap(),
        scaffoldHead: io.github.psd2live.algorithms.binding.ScaffoldHead.Input? = null,
    ): DepthTurnInputs {
        val fitted = context.fittedMouth(config)
        val face = context.faceRig
        val tuning = config.rigTuning
        val centerCanvas = face.coordinateSpace.toCanvas(face.centerX, face.centerY).first
        val module = RigModules.FACE_DEPTH_TURN.id
        val mouthParent = parents.values.firstOrNull { it?.raw == "DeformMouthShapeBoth" }
        val mouthFrame = mouthParent?.let(parentFrames::get)
        val mouthWarp = face.regions.firstOrNull { it.feature == FaceFeature.MOUTH && it.bounds == mouthFrame }?.let { region ->
            parentFrames[DeformerId("DeformFaceNinePose")]?.let { parent ->
                DepthTurnMouthWarp(region.bounds, parent, tuning.yawEase, tuning.pitchEase, tuning.featurePlane,
                    tuning.mouthNearLag, tuning.mouthCornerLag, tuning.mouthShift, tuning.mouthTurnArch, tuning.mouthTilt)
            }
        }
        val hairCarriers = shadowCarriers(layers, context.analysis.source.groups
            .filter { !it.visible || it.opacity <= 0f }.mapTo(hashSetOf()) { it.path })
        val measurements = layers.mapValues { (id, original) ->
            val layer = context.rigLayer(original)
            val parent = parents.getValue(id)
            val frame = if (parent == null) context.character else parentFrames.getValue(parent)
            DepthTurnInputs.Layer(layer.semantic.tag.name, layer.semantic.role?.name, frame,
                original.centroidX >= centerCanvas, scaffoldMeshIds[id],
                fitted?.takeIf { layer.semantic.tag != SemanticTag.MOUTH_CLOSE && it.covers(layer) }?.follow(layer),
                layer.semantic.tag == SemanticTag.FACE && layer.semantic.role == null &&
                    fitted?.let { it.lipped && it.rig?.fit?.hasLines == false } == true,
                hairCarriers[id])
        }
        fun sides(values: Set<Side>) = values.mapNotNullTo(hashSetOf()) {
            when (it) {
                Side.LEFT -> "L"
                Side.RIGHT -> "R"
                Side.NONE -> null
            }
        }
        return DepthTurnInputs(scaffoldHead?.headFrame ?: context.head, scaffoldHead?.faceFrame ?: context.face,
            DepthTurnMotion.Container(scaffoldHead?.alignedPivot?.x ?: face.centerX,
                scaffoldHead?.alignedPivot?.y ?: face.mouthLineY, config.headTurnStrength,
                tuning.headShellTurn, tuning.headShellCrown, tuning.headShellTilt),
            face.centerY, face.radiusX, face.radiusY, face.chinY,
            config.recipe.value(module, "turn") / 100f * config.headTurnStrength,
            config.recipe.value(module, "depth") / 100f, RigModules.turnStyle(config),
            context.faceLandmarks(config), sides(context.fittedEyes(config)?.fitting?.fits.orEmpty().keys),
            sides(context.fittedBrows(config).keys), parentFrames, measurements, tuning.faceLeanDown, tuning.faceLeanUp,
            mouthWarp, fitted?.source, fitted?.chin ?: .6f, scaffoldMeshes,
            tr("model.deformer.meshShape", "{meshName}"), tr("model.deformer.meshTurn", "{meshName}"))
    }

    /** The layer called "front hair shadow X" follows the visible, unique "front hair X" it depicts. */
    fun shadowCarriers(layers: Map<String, ClassifiedLayer>, hiddenGroups: Set<String>): Map<String, String> {
        val front = layers.filterValues { layer ->
            layer.semantic.tag == SemanticTag.FRONT_HAIR && layer.opaquePixels > 0 &&
                layer.source.visible && layer.source.opacity > 0f &&
                generateSequence(layer.source.groupPath) { it.substringBeforeLast('/', "").takeIf(String::isNotEmpty) }
                    .none { it in hiddenGroups }
        }
        val sources = front.entries.filter { it.value.semantic.role != PartRole.SHADOW }
            .groupBy { hairName(it.value.source.name) }
        return buildMap {
            for ((id, shadow) in front) {
                if (shadow.semantic.role != PartRole.SHADOW) continue
                val name = hairName(shadow.source.name)
                if (!name.startsWith("front hair shadow ")) continue
                val carrierName = "front hair " + name.removePrefix("front hair shadow ")
                val carrier = sources[carrierName]?.singleOrNull() ?: continue
                if (comparableCoverage(shadow, carrier.value)) put(id, carrier.key)
            }
        }
    }

    private val nameWhitespace = Regex("\\s+")
    private fun hairName(name: String) =
        Normalizer.normalize(name, Normalizer.Form.NFKC).trim().lowercase(Locale.ROOT).replace(nameWhitespace, " ")

    private fun comparableCoverage(shadow: ClassifiedLayer, carrier: ClassifiedLayer): Boolean {
        val area = shadow.bounds.union(carrier.bounds)
        val steps = 64
        var projected = 0
        var original = 0
        var common = 0
        for (row in 0 until steps) for (column in 0 until steps) {
            val x = area.left + (column + .5f) * area.width / steps
            val y = area.top + (row + .5f) * area.height / steps
            val inProjection = opaqueAt(shadow.source, x, y)
            val inCarrier = opaqueAt(carrier.source, x, y)
            if (inProjection) projected++
            if (inCarrier) original++
            if (inProjection && inCarrier) common++
        }
        return common > 0 && common >= maxOf(projected, original) * .25f
    }

    private fun opaqueAt(layer: SourceLayer, x: Float, y: Float): Boolean {
        val raster = layer.raster
        val bounds = layer.bounds
        if (bounds.width <= 0 || bounds.height <= 0 || raster.width <= 0 || raster.height <= 0) return false
        val u = (x - bounds.left) * raster.width / bounds.width
        val v = (y - bounds.top) * raster.height / bounds.height
        if (u < 0f || u >= raster.width || v < 0f || v >= raster.height) return false
        return (raster.rgba[4 * (v.toInt() * raster.width + u.toInt()) + 3].toInt() and 255) >= 128
    }
}
