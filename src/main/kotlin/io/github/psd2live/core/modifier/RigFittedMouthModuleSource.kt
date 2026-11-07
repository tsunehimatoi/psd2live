package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.MouthFittedInputs
import io.github.psd2live.algorithms.binding.ScaffoldMesh
import io.github.psd2live.algorithms.binding.ScaffoldMeshContext
import io.github.psd2live.core.*
import io.github.psd2live.core.recipe.*
import io.github.psd2live.project.WorkspaceSettingsCodec
import kotlinx.serialization.json.*

/** Source fitting and raw vertex measurements shared by fitted-mouth add-ons. */
internal object RigFittedMouthModuleSource {
    fun context(source: RigModifierSource, scaffold: RigModifierInstance, preceding: List<RigModifierInstance>,
                activeModule: RigModule?, neutralScaffold: Boolean = false): Pair<PipelineConfig, RigBuilder.RigContext> {
        val native = nativeConfig(source, scaffold, preceding, activeModule)
        return native to RigBuilder.rigContext(source.analysis, native, source.meshCache, neutralScaffold = neutralScaffold)
    }

    private fun nativeConfig(source: RigModifierSource, scaffold: RigModifierInstance, preceding: List<RigModifierInstance>,
                             activeModule: RigModule?): PipelineConfig {
        val state = preceding.associateBy { it.type }
        val bases = preceding.filter { RigLegacyBindings.spec(it.type).isBase }.associateBy { RigLegacyBindings.spec(it.type).area }
        val corrections = WorkspaceSettingsCodec.decodeRecipe(scaffold.config["corrections"])
        val recipe = RigRecipe(true, bases = RigModules.areas.associate { area ->
            val chosen = bases[area.id]?.takeUnless { it.type == RigLegacyBindings.FITTED_FACE }
            area.id to if (chosen == null) BaseChoice(area.legacy.id, area.legacy.version)
            else BaseChoice(chosen.type, chosen.version, RigLegacyBindings.values(chosen))
        }, modules = RigModules.modules.associate { module ->
            val chosen = state[module.id] ?: state[RigLegacyBindings.FITTED_FACE]?.takeIf { module == RigModules.FACE_DEPTH_TURN }
            module.id to when {
                module == activeModule -> ModuleChoice(true)
                chosen != null -> ModuleChoice(true, chosen.version, RigLegacyBindings.values(chosen))
                else -> ModuleChoice(false)
            }
        }, moves = corrections.moves, eyeParts = corrections.eyeParts)
        val tuning = RigLegacyBindings.specs.fold(RigTuning()) { current, spec ->
            state[spec.type]?.let { RigLegacyBindings.tuning(it, current) } ?: current
        }
        val head = state[RigLegacyBindings.FITTED_FACE] ?: state["face/legacy"]
        return source.sourceConfig().copy(recipe = recipe, rigTuning = tuning,
            mouthOutlineEnabled = state.containsKey(RigModules.MOUTH_OUTLINE.id),
            headTurnStrength = head?.let(RigLegacyBindings::strength) ?: 1f,
            bodyStrength = state["body/legacy"]?.let(RigLegacyBindings::strength) ?: 1f,
            featureDisplacementEnabled = scaffold.config["featureDisplacement"]?.jsonPrimitive?.boolean ?: false,
            hairSimulationFront = !(scaffold.config["frontHairSway"]?.jsonPrimitive?.boolean ?: true),
            hairSimulationBack = !(scaffold.config["backHairSway"]?.jsonPrimitive?.boolean ?: true))
    }

    fun measure(source: RigModifierSource, native: PipelineConfig, context: RigBuilder.RigContext,
                preceding: List<RigModifierInstance>, layers: Map<String, ClassifiedLayer>): ModifierAlgorithmContext {
        val frames = layers.mapValues { (_, layer) -> context.parentAndFrame(layer, native) }
        val parents = frames.mapValues { it.value.first?.raw }
        val headProvider = preceding.lastOrNull { it.type in setOf("face.depth-turn", RigLegacyBindings.FITTED_FACE) }
        val inputs = RigFittedMouthSource.capture(context, native, parents, layers,
            frames.mapValues { it.value.second }, emptyList(), headProvider?.id)
        val vertices = ScaffoldMeshContext.capture(layers.values.distinctBy { it.source.id }.map { layer ->
            val data = RigBuilder.fittedMouthSourceMesh(layer, context, native, source.atlas, source.meshCache)
            ScaffoldMeshContext.Material(source.primaryByLayer.getValue(layer.source.id.raw), layer.source.id.raw,
                ScaffoldMesh.RawMesh(data.rigPositions, data.mesh.uvs, data.mesh.indices), ScaffoldMesh.Geometry.Zero)
        }).resources.getValue("source_meshes")
        val targets = layers.mapValues { (id, layer) ->
            MouthFittedInputs.SourceVertexTarget(source.primaryByLayer.getValue(layer.source.id.raw), parents.getValue(id))
        }
        return inputs.copy(sourceVertices = MouthFittedInputs.SourceVertices(vertices, targets)).encode()
    }
}
