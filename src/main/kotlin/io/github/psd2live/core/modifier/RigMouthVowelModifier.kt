package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.MouthFittedInputs
import io.github.psd2live.algorithms.binding.MouthRig
import io.github.psd2live.algorithms.binding.MouthVowelAlgorithm
import io.github.psd2live.algorithms.binding.ScaffoldMesh
import io.github.psd2live.algorithms.binding.ScaffoldMeshContext
import io.github.psd2live.core.*
import io.github.psd2live.core.recipe.BaseChoice
import io.github.psd2live.core.recipe.ModuleChoice
import io.github.psd2live.core.recipe.RigModules
import io.github.psd2live.core.recipe.RigRecipe
import io.github.psd2live.i18n.tr
import io.github.psd2live.project.WorkspaceSettingsCodec
import kotlinx.serialization.json.*

internal object RigMouthVowelModifier {
    private val algorithm = object : ReadingModifierAlgorithmPlugin by MouthVowelAlgorithm(),
        ModifierOptionalUpstream, ModifierVersionSelection {
        override val optionalUpstream = MouthVowelAlgorithm.optionalUpstream
        override val canonicalOptInType = MouthVowelAlgorithm.TYPE
    }

    fun definition(source: RigModifierSource?): RigModifierDefinition {
        val legacy = RigLegacyBindings.definition(RigLegacyBindings.spec(MouthVowelAlgorithm.TYPE), source)
        val contexts = HashMap<RigModifierInstance, ModifierAlgorithmContext>()
        fun capture(instance: RigModifierInstance) = contexts.getOrPut(instance) { requireNotNull(source).capture(instance) }
        return legacy.copy(version = MouthVowelAlgorithm.VERSION,
            parameters = { MouthVowelAlgorithm.parameters(capture(it)) }, resolveParameter = { _, _, _ -> null },
            plugin = algorithm, upstreamInputs = true, changeWrites = MouthVowelAlgorithm.WRITES,
            evaluate = { _, _, _ -> error("Mouth vowel requires captured fitted-mouth inputs") }, authorContext = ::capture)
    }

    private fun RigModifierSource.capture(instance: RigModifierInstance): ModifierAlgorithmContext {
        val scaffold = provider(instance)
        require(scaffold.version == 2) { "Mouth vowel v2 requires a shared v2 scaffold" }
        val preceding = activeBefore(instance).filter { item ->
            item.config["scaffold"] == JsonPrimitive(scaffold.id) && item.targets.any(instance.targets::contains) &&
                RigLegacyBindings.find(item.type)?.let { !it.expressionToggles && !it.seeThrough } == true
        }
        val baseId = instance.config.getValue("base").jsonPrimitive.content
        val base = requireNotNull(preceding.singleOrNull { it.id == baseId && it.type == "mouth/fitted" && it.version in 1..2 })
        require(preceding.none { it.type.startsWith("mouth.") }) {
            "Mouth vowel v2 requires a fitted mouth without preceding mouth modules. Use v1 with earlier mouth modules."
        }
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
                module == RigModules.MOUTH_VOWEL -> ModuleChoice(true)
                chosen != null -> ModuleChoice(true, chosen.version, RigLegacyBindings.values(chosen))
                else -> ModuleChoice(false)
            }
        }, moves = corrections.moves, eyeParts = corrections.eyeParts)
        val tuning = RigLegacyBindings.specs.fold(RigTuning()) { current, spec ->
            state[spec.type]?.let { RigLegacyBindings.tuning(it, current) } ?: current
        }
        val head = state[RigLegacyBindings.FITTED_FACE] ?: state["face/legacy"]
        val native = sourceConfig().copy(recipe = recipe, rigTuning = tuning,
            headTurnStrength = head?.let(RigLegacyBindings::strength) ?: 1f,
            bodyStrength = state["body/legacy"]?.let(RigLegacyBindings::strength) ?: 1f,
            featureDisplacementEnabled = scaffold.config["featureDisplacement"]?.jsonPrimitive?.boolean ?: false,
            hairSimulationFront = !(scaffold.config["frontHairSway"]?.jsonPrimitive?.boolean ?: true),
            hairSimulationBack = !(scaffold.config["backHairSway"]?.jsonPrimitive?.boolean ?: true))
        val context = RigBuilder.rigContext(analysis, native, meshCache)
        val fitted = requireNotNull(context.fittedMouth(native)?.takeIf { it.source != null }) {
            "Mouth vowel v2 requires readable fitted mouth source artwork"
        }
        require(!fitted.closedLine) {
            "Mouth vowel v2 requires a mouth without a separate closed drawing. Use v1 for closed-drawing swaps."
        }
        val sourceLayers = context.analysis.layers.associateBy { it.source.id.raw }
        val follows = linkedMapOf<String, MouthRig.Follow?>()
        val layers = instance.targets.mapNotNull { target ->
            val layer = sourceLayers[layersByMesh[target.id]] ?: return@mapNotNull null
            val measured = context.rigLayer(layer)
            val covered = fitted.covers(measured)
            val lipSkin = measured.semantic.tag == SemanticTag.FACE && measured.semantic.role == null &&
                fitted.lipped && fitted.rig?.fit?.hasLines == false
            if (!covered && !lipSkin) return@mapNotNull null
            require(target in base.targets && preceding.all { target in it.targets }) {
                "Mouth vowel v2 requires a shared source frame for its mouth targets"
            }
            follows[target.id] = if (covered) fitted.follow(measured) else null
            target.id to layer
        }.toMap()
        require(layers.isNotEmpty()) { "Mouth vowel v2 requires fitted mouth targets" }
        val frames = layers.mapValues { (_, layer) -> context.parentAndFrame(layer, native) }
        val parents = frames.mapValues { it.value.first?.raw }
        val headProvider = state["face.depth-turn"] ?: state[RigLegacyBindings.FITTED_FACE]
        val inputs = RigFittedMouthSource.capture(context, native, parents, layers,
            frames.mapValues { it.value.second }, emptyList(), headProvider?.id)
        val vertices = ScaffoldMeshContext.capture(layers.values.distinctBy { it.source.id }.map { layer ->
            val data = RigBuilder.fittedMouthSourceMesh(layer, context, native, atlas, meshCache)
            ScaffoldMeshContext.Material(primaryByLayer.getValue(layer.source.id.raw), layer.source.id.raw,
                ScaffoldMesh.RawMesh(data.rigPositions, data.mesh.uvs, data.mesh.indices), ScaffoldMesh.Geometry.Zero)
        }).resources.getValue("source_meshes")
        val targets = layers.mapValues { (id, layer) ->
            MouthFittedInputs.SourceVertexTarget(primaryByLayer.getValue(layer.source.id.raw), parents.getValue(id))
        }
        return MouthVowelAlgorithm.context(inputs.copy(sourceVertices = MouthFittedInputs.SourceVertices(vertices, targets)).encode(),
            follows, RigModules.MOUTH_VOWEL.defines.associate { it.id to tr(it.nameKey) }, tr("model.group.mouth"))
    }
}
