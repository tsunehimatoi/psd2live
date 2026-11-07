package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.LegacyBodyV2Algorithm
import io.github.psd2live.algorithms.binding.LegacyFaceInputs
import io.github.psd2live.core.RigBuilder
import io.github.psd2live.core.RigTuning
import io.github.psd2live.core.StandardParameters
import io.github.psd2live.core.recipe.RigRecipe
import io.github.psd2live.i18n.tr
import io.github.psd2live.project.WorkspaceSettingsCodec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

internal object RigLegacyBodyDefinition {
    private val algorithm = object : ChangingModifierAlgorithmPlugin by LegacyBodyV2Algorithm, ModifierVersionSelection {
        override val canonicalOptInType = LegacyBodyV2Algorithm.TYPE
    }

    fun definition(source: RigModifierSource?): RigModifierDefinition = RigBodyModifier.sourceDefinition(source) { null }.copy(
        version = LegacyBodyV2Algorithm.VERSION, plugin = algorithm, evaluate = algorithm::evaluate,
        authorContext = { requireNotNull(source).capture(it) },
    )

    private fun RigModifierSource.capture(body: RigModifierInstance): ModifierAlgorithmContext {
        val scaffold = provider(body)
        val context = bodyContext(body, null)
        val corrections = WorkspaceSettingsCodec.decodeRecipe(scaffold.config["corrections"])
        val config = sourceConfig().copy(generateDeformers = false,
            recipe = RigRecipe.Legacy.copy(moves = corrections.moves, eyeParts = corrections.eyeParts))
        val face = RigBuilder.rigContext(analysis, config, meshCache).faceRig
        val angles = StandardParameters.all.filter { it.id in RigLegacyBindings.spec("face/legacy").parameters }
        val pitch = LegacyFaceInputs.capture(ModifierAlgorithmContext(context.values.getValue("scaffold").jsonObject),
            face.centerY, face.radiusX, face.radiusY, face.eyeLineY, RigTuning().legacyFaceTuning(), 1f,
            angles.map { it.name }, tr("model.group.face"))
        return ModifierAlgorithmContext(JsonObject(context.values + ("pitch" to JsonObject(pitch.values - "scaffold"))),
            context.resources)
    }
}
