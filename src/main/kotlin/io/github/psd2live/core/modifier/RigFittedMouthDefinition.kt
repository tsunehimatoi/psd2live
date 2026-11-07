package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.MouthFittedInputs
import io.github.psd2live.algorithms.binding.MouthFittedV2Algorithm
import io.github.psd2live.algorithms.binding.MouthFittedV3Algorithm
import io.github.psd2live.algorithms.binding.ScaffoldBinding
import io.github.psd2live.algorithms.binding.ScaffoldMesh
import io.github.psd2live.algorithms.binding.ScaffoldMeshContext
import io.github.psd2live.core.*
import io.github.psd2live.core.recipe.BaseChoice
import io.github.psd2live.core.recipe.ModuleChoice
import io.github.psd2live.core.recipe.RigModules
import io.github.psd2live.core.recipe.RigRecipe
import io.github.psd2live.i18n.tr
import io.github.psd2live.project.WorkspaceSettingsCodec
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive

/** Explicit source-only entry. Shared fitted head fields still use the composed v1 path. */
internal object RigFittedMouthDefinition {
    const val TYPE = MouthFittedV2Algorithm.TYPE

    fun definition(source: RigModifierSource?): RigModifierDefinition {
        val legacy = RigLegacyBindings.definition(RigLegacyBindings.spec(TYPE), source)
        val plugin = object : ChangingModifierAlgorithmPlugin by MouthFittedV2Algorithm(), ModifierOptionalUpstream, ModifierVersionSelection {
            override val optionalUpstream = MouthFittedV2Algorithm.HEAD_TURN
            override val canonicalOptInType = TYPE
        }
        return legacy.copy(version = MouthFittedV2Algorithm.VERSION, parameters = { parameters(it) }, plugin = plugin, upstreamInputs = true,
            changeWrites = MouthFittedV2Algorithm.WRITES, composableKinds = MouthFittedV2Algorithm.COMPOSABLE, resolveParameter = { _, _, _ -> null },
            authorContext = { requireNotNull(source).capture(it) })
    }

    private fun reach(instance: RigModifierInstance) = (RigLegacyBindings.values(instance)["reach"] ?: 100f) / 100f

    fun modernDefinition(source: RigModifierSource?): RigModifierDefinition {
        val plugin = object : ChangingModifierAlgorithmPlugin by MouthFittedV3Algorithm(), ModifierOptionalUpstream, ModifierVersionSelection {
            override val optionalUpstream = setOf("body/legacy", "eye/fitted", "brow/fitted")
            override val canonicalOptInType = TYPE
        }
        return definition(source).copy(version = MouthFittedV3Algorithm.VERSION, plugin = plugin,
            requires = setOf("rig.scaffold", "face/fitted"),
            parameters = { instance -> MouthFittedV2Algorithm.parameters(
                tr("model.parameter.mouthForm"), tr("model.parameter.mouthOpen"), reach(instance).takeIf { it > 1.001f } ?: 1f) },
            changeWrites = MouthFittedV3Algorithm.WRITES, composableKinds = MouthFittedV3Algorithm.WRITES - "restPose" + "relations",
            authorContext = { RigFittedMouthModernSource.capture(requireNotNull(source), it) })
    }

    private fun parameters(instance: RigModifierInstance) = MouthFittedV2Algorithm.parameters(
        tr("model.parameter.mouthForm"), tr("model.parameter.mouthOpen"), reach(instance))

    private fun RigModifierSource.capture(instance: RigModifierInstance): ModifierAlgorithmContext {
        val structure = provider(instance)
        require(activeBefore(instance).none { previous -> previous.id != structure.id &&
            previous.targets.any { it in instance.targets } }) {
            "Fitted mouth v2 requires neutral scaffold targets. Use v1 with preceding bindings."
        }
        val corrections = WorkspaceSettingsCodec.decodeRecipe(structure.config["corrections"])
        val recipe = RigRecipe(true, bases = RigModules.areas.associate { area ->
            area.id to if (area.id == "mouth") BaseChoice(TYPE, values = RigLegacyBindings.values(instance))
            else BaseChoice(area.legacy.id, area.legacy.version)
        }, modules = RigModules.modules.associate { it.id to ModuleChoice(false, it.version) },
            moves = corrections.moves, eyeParts = corrections.eyeParts)
        val native = sourceConfig().copy(recipe = recipe, rigTuning = RigLegacyBindings.tuning(instance, RigTuning()),
            featureDisplacementEnabled = structure.config["featureDisplacement"]?.jsonPrimitive?.boolean ?: false)
        val measured = RigBuilder.rigContext(analysis, native, meshCache)
        require(measured.fittedMouth(native)?.rig != null) { "Fitted mouth v2 requires a readable mouth opening" }
        val byLayer = measured.analysis.layers.associateBy { it.source.id.raw }
        val layers = instance.targets.mapNotNull { target ->
            val layer = byLayer[layersByMesh[target.id]] ?: return@mapNotNull null
            if (layer.semantic.tag !in CharacterAnalyzer.MOUTH_TAGS) return@mapNotNull null
            target.id to layer
        }.toMap()
        require(layers.isNotEmpty()) { "Fitted mouth v2 needs a mouth target" }
        val parentFrames = layers.mapValues { (_, layer) -> measured.parentAndFrame(layer, native) }
        val parents = parentFrames.mapValues { (id, parent) -> parent.first?.let { ScaffoldBinding.deformerId(structure, id, it.raw) } }
        val captured = RigFittedMouthSource.capture(measured, native, parents, layers, parentFrames.mapValues { it.value.second },
            layers.keys.toList(), headProvider = null)
        val scaffoldMeshes = scaffoldContext(structure).resources.getValue("source_meshes")
        val vertices = layers.mapValues { (id, layer) ->
            MouthFittedInputs.SourceVertexTarget(primaryByLayer.getValue(layer.source.id.raw), parents.getValue(id))
        }
        val materials = layers.values.distinctBy { it.source.id }.map { layer ->
            val mesh = RigBuilder.fittedMouthSourceMesh(layer, measured, native, atlas, meshCache)
            val raw = ScaffoldMesh.RawMesh(mesh.rigPositions, mesh.mesh.uvs, mesh.mesh.indices)
            ScaffoldMeshContext.Material(primaryByLayer.getValue(layer.source.id.raw), layer.source.id.raw, raw, ScaffoldMesh.Geometry.Zero)
        }
        val fitted = ScaffoldMeshContext.capture(materials).resources.getValue("source_meshes")
        return MouthFittedV2Algorithm.capture(captured.copy(sourceVertices = MouthFittedInputs.SourceVertices(fitted, vertices)),
            scaffoldMeshes, tr("model.parameter.mouthForm"), tr("model.parameter.mouthOpen"), reach(instance), tr("model.group.mouth"))
    }
}
