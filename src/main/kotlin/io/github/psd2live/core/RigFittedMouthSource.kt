package io.github.psd2live.core

import io.github.psd2live.algorithms.binding.MouthFittedInputs
import io.github.psd2live.core.recipe.RecipeSubject
import io.github.psd2live.core.recipe.RigModules
import io.github.psd2live.i18n.tr
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
    ): MouthFittedInputs {
        val fitted = context.fittedMouth(config)
        val depthTurn = RigModules.builds(RigModules.FACE_DEPTH_TURN, config, RecipeSubject.of(context.analysis))
        val head = if (!depthTurn) null else {
            require(!headProvider.isNullOrBlank()) { "Fitted mouth depth turn requires an explicit provider" }
            val face = context.faceRig
            MouthFittedInputs.Head(headProvider, face.centerX, face.centerY, face.radiusX, face.radiusY,
                config.recipe.value(RigModules.FACE_DEPTH_TURN.id, "turn") / 100f * config.headTurnStrength,
                config.recipe.value(RigModules.FACE_DEPTH_TURN.id, "depth") / 100f, face.chinY)
        }
        val styled = context.faceTurn(config) != null
        val byId = source.drawables.associateBy { it.id.raw }
        val captured = layers.mapValues { (id, original) ->
            val drawable = requireNotNull(byId[id]) { "Missing captured mouth source mesh: $id" }
            val frame = requireNotNull(frames[id]) { "Missing captured mouth parent frame: $id" }
            val layer = context.rigLayer(original)
            val parent = drawable.parentDeformerId
            val ownTurn = parent?.raw?.startsWith("DeformMouthShape") != true &&
                !(styled && parent != null && (parent.raw == "DeformFaceContour" || parent in context.underFace))
            MouthFittedInputs.Layer(layer.semantic.tag.name, layer.semantic.role?.name, layer.semantic.side.name,
                layer.semantic.variant, frame, layer.bounds, layer.centroidX, layer.centroidY, layer.opaquePixels > 0,
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
