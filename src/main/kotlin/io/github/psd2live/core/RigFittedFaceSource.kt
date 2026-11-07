package io.github.psd2live.core

import io.github.psd2live.algorithms.binding.DepthTurnMotion
import io.github.psd2live.algorithms.binding.FittedFaceLattices
import io.github.psd2live.algorithms.binding.FittedHeadTurnSource
import io.github.psd2live.core.recipe.RecipeSubject
import io.github.psd2live.core.recipe.RigModules
import org.umamo.runtime.model.DeformerId

/** Captures the source side of the styled face transition, without sampling any generated pose. */
internal object RigFittedFaceSource {
    fun headTurn(context: RigBuilder.RigContext, config: PipelineConfig): FittedHeadTurnSource? {
        val module = RigModules.FACE_DEPTH_TURN
        if (!RigModules.builds(module, config, RecipeSubject.of(context.analysis))) return null
        val face = context.faceRig
        return FittedHeadTurnSource(face.centerX, face.centerY, face.radiusX, face.radiusY, face.chinY,
            config.recipe.value(module.id, "turn") / 100f * config.headTurnStrength,
            config.recipe.value(module.id, "depth") / 100f, context.faceLandmarks(config), RigModules.turnStyle(config))
    }

    fun capture(context: RigBuilder.RigContext, config: PipelineConfig, parents: Set<DeformerId>): FittedFaceLattices.Inputs? {
        val source = headTurn(context, config) ?: return null
        val face = context.faceRig
        val tuning = config.rigTuning
        val frames = parents.mapNotNull { parent -> context.resolveFrame(parent, config)?.let { parent to it } }.toMap()
        return FittedFaceLattices.Inputs(source, context.head, DepthTurnMotion.Container(
            face.centerX, face.mouthLineY, config.headTurnStrength,
            tuning.headShellTurn, tuning.headShellCrown, tuning.headShellTilt,
        ), context.face, frames, context.fittedEyes(config)?.fitting?.fits.orEmpty().keys, context.fittedBrows(config).keys)
    }
}
