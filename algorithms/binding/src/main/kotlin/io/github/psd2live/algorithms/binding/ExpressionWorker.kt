package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker
import io.github.psd2live.core.modifier.ModifierAlgorithmPlugin
import java.util.HexFormat

object ExpressionWorker {
    private val hex = HexFormat.of()

    private fun id(type: String, version: Int = 1) =
        "builtin." + hex.formatHex(type.encodeToByteArray()) + ".v$version"

    @JvmStatic fun main(args: Array<String>) {
        val algorithms = linkedMapOf<Pair<String, Int>, ModifierAlgorithmPlugin>()
        fun register(type: String, entrypoint: String, plugin: ModifierAlgorithmPlugin, version: Int = 1) {
            algorithms["${id(type, version)}/$entrypoint" to version] = plugin
        }

        val scaffold = ScaffoldAlgorithm()
        register("rig.scaffold", "structure", scaffold, version = 1)
        register("rig.scaffold", "structure", scaffold, version = 2)

        register("body", "part", BodyPartAlgorithm())
        register("body/legacy", "motion", BindingTransitionAlgorithm(LegacyBodyAlgorithm(), 1))
        register("face", "part", FacePartAlgorithm())
        register("face/legacy", "binding", LegacyFaceAlgorithm())
        register("face/fitted", "binding", BindingTransitionAlgorithm(FittedFaceBindingAlgorithm))
        register("face.depth-turn", "turn", BindingTransitionAlgorithm(DepthTurnAlgorithm(), 1))
        register("face.neck", "tilt", BindingTransitionAlgorithm(NeckAlgorithm(), NeckAlgorithm.VERSION))
        register("face.skin-clip", "clip", BindingTransitionAlgorithm(SkinClipAlgorithm(), 1))

        register("eye", "part", EyePartAlgorithm())
        register("eye/legacy", "legacy", BindingTransitionAlgorithm(EyeLegacySourceAlgorithm(), EyeLegacySourceAlgorithm.VERSION))
        register("eye/fitted", "fitted", BindingTransitionAlgorithm(EyeFittedSourceAlgorithm(), EyeFittedAlgorithm.VERSION))
        register("eye.wide", "wide", BindingTransitionAlgorithm(EyeWideAlgorithm(), EyeWideAlgorithm.VERSION))
        register("eye.smile", "smile", BindingTransitionAlgorithm(EyeSmileAlgorithm(), EyeSmileAlgorithm.VERSION))
        register("eye.squash", "squash", BindingTransitionAlgorithm(EyeSquashAlgorithm(), EyeSquashAlgorithm.VERSION))
        register("eye.emotion", "emotion", BindingTransitionAlgorithm(EyeEmotionAlgorithm(), EyeEmotionAlgorithm.VERSION))
        register("eye.gaze-lid", "gaze", BindingTransitionAlgorithm(EyeGazeLidAlgorithm(), EyeGazeLidAlgorithm.VERSION))
        register("eye.highlight", "highlight", BindingTransitionAlgorithm(EyeHighlightAlgorithm(), EyeHighlightAlgorithm.VERSION))
        register("eye.lid-fade", "fade", BindingTransitionAlgorithm(LidFadeAlgorithm(), LidFadeAlgorithm.VERSION))
        register("eye.aftershock", "aftershock", BindingTransitionAlgorithm(EyeAftershockAlgorithm(), EyeAftershockAlgorithm.VERSION))
        register("eye.see-through", "visibility", SeeThroughAlgorithm())

        register("brow", "part", BrowPartAlgorithm())
        register("brow/legacy", "binding", BindingTransitionAlgorithm(BrowLegacyAlgorithm(), 1))
        register("brow/fitted", "binding", BindingTransitionAlgorithm(BrowFittedAlgorithm(), 1))
        register("brow.lid-coupling", "coupling", BindingTransitionAlgorithm(BrowLidAlgorithm(), BrowLidAlgorithm.VERSION))
        register("brow.shape", "shape", BindingTransitionAlgorithm(BrowShapeAlgorithm(), BrowShapeAlgorithm.VERSION))
        register("brow.blink", "blink", BindingTransitionAlgorithm(BrowBlinkAlgorithm(), BrowBlinkAlgorithm.VERSION))
        register("brow.spring", "spring", BindingTransitionAlgorithm(BrowSpringAlgorithm(), BrowSpringAlgorithm.VERSION))
        register("brow.see-through", "visibility", SeeThroughAlgorithm())

        register("mouth/legacy", "binding", BindingTransitionAlgorithm(MouthLegacyAlgorithm(), MouthLegacyAlgorithm.VERSION))
        val mouthFitted = BindingTransitionAlgorithm(MouthFittedSourceAlgorithm(), 1)
        register("mouth/fitted", "binding", mouthFitted)
        register("mouth/fitted", "source", mouthFitted)
        register("mouth.outline", "outline", BindingTransitionAlgorithm(MouthOutlineAlgorithm(), 1))
        register("mouth.capture", "capture", BindingTransitionAlgorithm(MouthCaptureAlgorithm(), MouthCaptureAlgorithm.VERSION))
        register("mouth.vowel", "vowel", BindingTransitionAlgorithm(MouthVowelAlgorithm(), MouthVowelAlgorithm.VERSION))
        register("mouth.tongue", "tongue", BindingTransitionAlgorithm(MouthTongueAlgorithm(), MouthTongueAlgorithm.VERSION))
        register("mouth.jaw", "jaw", BindingTransitionAlgorithm(MouthJawAlgorithm(), MouthJawAlgorithm.VERSION))
        register("mouth.cheek-lid", "coupling", BindingTransitionAlgorithm(MouthCheekLidAlgorithm(), MouthCheekLidAlgorithm.VERSION))
        register("mouth.interior-depth", "depth", BindingTransitionAlgorithm(MouthDepthAlgorithm(), MouthDepthAlgorithm.VERSION))
        register("mouth.spring", "spring", BindingTransitionAlgorithm(MouthSpringAlgorithm(), MouthSpringAlgorithm.VERSION))

        register("hair", "part", HairPartAlgorithm())
        register("hair/legacy", "legacy", BindingTransitionAlgorithm(HairLegacyAlgorithm(), 1))
        register("hair/chains", "chains", BindingTransitionAlgorithm(HairChainsAlgorithm(), 1))
        register("body.bust", "bust", BindingTransitionAlgorithm(BustBindingAlgorithm(), BustBindingAlgorithm.VERSION))
        register("body.arms", "swing", BindingTransitionAlgorithm(ArmSwingAlgorithm(), ArmSwingAlgorithm.VERSION))
        register("outfit.skirt", "skirt", BindingTransitionAlgorithm(SkirtAlgorithm(), SkirtAlgorithm.VERSION))
        register("outfit.ribbons", "ribbons", BindingTransitionAlgorithm(RibbonsAlgorithm(), RibbonsAlgorithm.VERSION))
        register("outfit.creature", "creature", BindingTransitionAlgorithm(CreatureAlgorithm(), CreatureAlgorithm.VERSION))

        register("expression.toggles", "toggles", BindingTransitionAlgorithm(ExpressionAlgorithm(), 1))
        register("expression.cheek", "color", BindingTransitionAlgorithm(CheekAlgorithm(), 1))
        register("expression.gloom", "gloom", BindingTransitionAlgorithm(GloomAlgorithm(), 1))

        AlgorithmWorker.run(algorithms)
    }
}
