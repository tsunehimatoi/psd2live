package io.github.psd2live.core

import io.github.psd2live.algorithms.binding.MouthFittedInputs
import io.github.psd2live.i18n.tr
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.PuppetModel

/** Captures source semantics and provider configuration, never evaluated mouth poses. */
internal object RigFittedMouthSource {
    fun capture(
        context: RigBuilder.RigContext,
        config: PipelineConfig,
        source: PuppetModel,
        layers: Map<String, ClassifiedLayer>,
        frames: Map<String, Bounds>,
        maskSources: List<String>,
        headProvider: String?,
    ): MouthFittedInputs = capture(context, config, source.drawables.associate { it.id.raw to it.parentDeformerId?.raw },
        layers, frames, maskSources, headProvider)

    fun capture(
        context: RigBuilder.RigContext,
        config: PipelineConfig,
        parents: Map<String, String?>,
        layers: Map<String, ClassifiedLayer>,
        frames: Map<String, Bounds>,
        maskSources: List<String>,
        headProvider: String?,
    ): MouthFittedInputs {
        val fitted = context.fittedMouth(config)
        val head = RigFittedFaceSource.headTurn(context, config)?.let { turn ->
            require(!headProvider.isNullOrBlank()) { "Fitted mouth depth turn requires an explicit provider" }
            MouthFittedInputs.Head(headProvider, turn.centerX, turn.centerY, turn.radiusX, turn.radiusY,
                turn.turn, turn.depth, turn.chinY)
        }
        val styled = context.faceTurn(config) != null
        val captured = layers.mapValues { (id, original) ->
            val frame = requireNotNull(frames[id]) { "Missing captured mouth parent frame: $id" }
            val layer = context.rigLayer(original)
            val parent = parents.getValue(id)?.let(::DeformerId)
            val ownTurn = parent?.raw?.startsWith("DeformMouthShape") != true &&
                !(styled && parent != null && (parent.raw == "DeformFaceContour" || parent in context.underFace))
            MouthFittedInputs.Layer(layer.semantic.tag.name, layer.semantic.role?.name, layer.semantic.side.name,
                layer.semantic.variant, frame, original.bounds, layer.centroidX, layer.centroidY, layer.opaquePixels > 0,
                layer.source.clipped,
                (config.layerOverrides[original.source.id.raw]?.type ?: original.semantic.type) == LayerType.PRESET,
                turnsWithHeadMesh = ownTurn,
                overridesDrawOrder = config.drawOrderOverrides[original.source.id.raw] != null || config.drawOrderOverrides[id] != null,
                aperture = layer.bounds)
        }
        val tuning = config.rigTuning
        return MouthFittedInputs(fitted?.source, fitted?.chin ?: .6f, fitted?.result?.layers?.firstOrNull()?.semantic?.variant,
            context.hasClosedMouth, fitted?.result?.layers?.any { it.semantic.tag == SemanticTag.MOUTH } == true,
            tuning.teethFade, captured, maskSources.filter { id ->
                layers[id]?.let { it.semantic.tag in setOf(SemanticTag.MOUTH, SemanticTag.MOUTH_OPEN) &&
                    it.semantic.role == null && !it.source.clipped } == true
            }, head,
            tr("model.deformer.meshShape", "{meshName}"), tr("model.deformer.meshTurn", "{meshName}"),
            MouthFittedInputs.Fallback(if (config.mouthShape == "custom") config.mouthCurve else MouthCurve.preset(config.mouthShape),
                config.mouthOutlineEnabled, tuning.mouthClosedNarrow, tuning.mouthFormWiden, tuning.mouthSeam,
                tuning.mouthSmileBase, tuning.mouthSmile))
    }
}
