package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.NeckAlgorithm
import io.github.psd2live.core.RigBuilder
import io.github.psd2live.core.RigTuning
import io.github.psd2live.core.recipe.RigRecipe
import io.github.psd2live.project.WorkspaceSettingsCodec
import kotlinx.serialization.json.jsonPrimitive

internal object RigNeckModifier {
    private val algorithm = NeckAlgorithm()

    fun definition(source: RigModifierSource?): RigModifierDefinition =
        RigLegacyBindings.definition(RigLegacyBindings.spec(NeckAlgorithm.TYPE), source).copy(
            version = NeckAlgorithm.VERSION,
            evaluate = { _, _, _ -> error("Neck tilt requires resolved modifier inputs") },
            plugin = algorithm,
            changeWrites = setOf("mesh", "relations"),
            upstreamInputs = true,
            authorContext = { requireNotNull(source).neckContext(it) },
        )

    private fun RigModifierSource.neckContext(instance: RigModifierInstance): ModifierAlgorithmContext {
        val scaffold = provider(instance)
        val corrections = WorkspaceSettingsCodec.decodeRecipe(scaffold.config["corrections"])
        val native = sourceConfig().copy(recipe = RigRecipe.Legacy.copy(moves = corrections.moves, eyeParts = corrections.eyeParts),
            rigTuning = RigTuning(), bodyStrength = 1f, headTurnStrength = 1f)
        val earlierNeckTargets = activeBefore(instance).asSequence()
            .filter { (it.type == NeckAlgorithm.TYPE || it.type == NeckAlgorithm.ENTRYPOINT) &&
                it.config["scaffold"]?.jsonPrimitive?.content == scaffold.id }
            .flatMap { it.targets.asSequence() }.toSet()
        val targets = instance.targets.filterNot(earlierNeckTargets::contains).associate { it.id to layersByMesh.getValue(it.id) }
        return RigBuilder.neckSourceInputs(analysis, atlas, native, meshCache, targets, primaryByLayer) { mesh, parent ->
            RigSourceModifiers.deformerId(scaffold, mesh, parent)
        }
    }
}
