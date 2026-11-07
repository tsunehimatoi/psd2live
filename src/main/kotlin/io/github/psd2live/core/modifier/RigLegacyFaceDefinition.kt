package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.LegacyFaceInputs
import io.github.psd2live.algorithms.binding.LegacyFaceV2Algorithm
import io.github.psd2live.core.RigBuilder
import io.github.psd2live.core.RigTuning
import io.github.psd2live.core.StandardParameters
import io.github.psd2live.core.recipe.RigRecipe
import io.github.psd2live.format.model.Parameter
import io.github.psd2live.i18n.tr
import io.github.psd2live.project.WorkspaceSettingsCodec
import kotlinx.serialization.json.*

internal object RigLegacyFaceDefinition {
    private val spec get() = RigLegacyBindings.spec(LegacyFaceV2Algorithm.TYPE)
    private val algorithm = object : ChangingModifierAlgorithmPlugin by LegacyFaceV2Algorithm,
        ModifierOptionalUpstream, ModifierVersionSelection {
        override val canonicalOptInType = LegacyFaceV2Algorithm.TYPE
        override val optionalUpstream = setOf(RigBodyModifier.TYPE)
    }

    fun definition(source: RigModifierSource?): RigModifierDefinition = RigLegacyBindings.definition(spec, source).copy(
        version = LegacyFaceV2Algorithm.VERSION, plugin = algorithm, upstreamInputs = true,
        evaluate = { _, _, _ -> error("Legacy face v2 requires captured scaffold inputs") },
        parameters = { angles().map { Parameter(it.id.raw, it.name, it.min, it.max, it.default) } },
        changeWrites = LegacyFaceV2Algorithm.WRITES, resolveParameter = { _, _, _ -> null },
        authorContext = { requireNotNull(source).capture(it) },
    )

    private fun angles() = StandardParameters.all.filter { it.id in spec.parameters }

    private fun RigModifierSource.capture(instance: RigModifierInstance): ModifierAlgorithmContext {
        val scaffold = provider(instance)
        val prior = activeBefore(instance).filter { it.id != scaffold.id && it.targets.any(instance.targets::contains) }
        val body = prior.singleOrNull { it.type == RigBodyModifier.TYPE }
        require(scaffold.version == 2 && prior.all { it == body } &&
            (body == null || body.version in 1..2 && body.targets.toSet() == instance.targets.toSet() &&
                body.config["scaffold"]?.jsonPrimitive?.content == scaffold.id)) {
            "Legacy face v2 requires a shared scaffold and supports an optional preceding legacy body"
        }
        val pinned = scaffold.plugin?.takeIf { it.id.startsWith("builtin.") }
            ?.let { ModifierPluginVersion.parse(it.version, legacy = true) }
        require(pinned == null || pinned >= ModifierPluginVersion.parse("1.0.41")) {
            "Legacy face v2 requires a source-aware shared scaffold package"
        }
        val context = scaffoldContext(scaffold)
        val corrections = WorkspaceSettingsCodec.decodeRecipe(scaffold.config["corrections"])
        val config = sourceConfig().copy(generateDeformers = false,
            recipe = RigRecipe.Legacy.copy(moves = corrections.moves, eyeParts = corrections.eyeParts))
        val face = RigBuilder.rigContext(analysis, config, meshCache).faceRig
        val bodyTuning = body?.let { RigLegacyBindings.tuning(it, RigTuning()) } ?: RigTuning()
        val tuning = RigLegacyBindings.tuning(instance, bodyTuning).legacyFaceTuning()
        val captured = LegacyFaceInputs.capture(context, face.centerY, face.radiusX, face.radiusY, face.eyeLineY,
            tuning, RigLegacyBindings.strength(instance), angles().map { it.name }, tr("model.group.face"))
        return ModifierAlgorithmContext(buildJsonObject {
            put("face", captured.values)
            body?.let {
                put("body", JsonObject(bodyContext(it).values - "scaffold" - "schema" - "group_names" + ("id" to JsonPrimitive(it.id))))
            }
        }, captured.resources)
    }
}
