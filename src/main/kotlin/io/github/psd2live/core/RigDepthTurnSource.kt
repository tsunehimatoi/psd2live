package io.github.psd2live.core

import io.github.psd2live.algorithms.binding.DepthTurnInputs
import io.github.psd2live.algorithms.binding.DepthTurnMotion
import io.github.psd2live.algorithms.binding.DepthTurnMouthWarp
import io.github.psd2live.core.modifier.ModifierAlgorithmResource
import io.github.psd2live.core.recipe.RigModules
import io.github.psd2live.i18n.tr
import org.umamo.runtime.model.DeformerId

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
        val measurements = layers.mapValues { (id, original) ->
            val layer = context.rigLayer(original)
            val parent = parents.getValue(id)
            val frame = if (parent == null) context.character else parentFrames.getValue(parent)
            DepthTurnInputs.Layer(layer.semantic.tag.name, layer.semantic.role?.name, frame,
                original.centroidX >= centerCanvas, scaffoldMeshIds[id],
                fitted?.takeIf { layer.semantic.tag != SemanticTag.MOUTH_CLOSE && it.covers(layer) }?.follow(layer),
                layer.semantic.tag == SemanticTag.FACE && layer.semantic.role == null &&
                    fitted?.let { it.lipped && it.rig?.fit?.hasLines == false } == true)
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
}
