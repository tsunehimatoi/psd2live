package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.MouthJawAlgorithm
import io.github.psd2live.algorithms.binding.ScaffoldMesh
import io.github.psd2live.algorithms.binding.ScaffoldMeshContext
import io.github.psd2live.core.*
import io.github.psd2live.core.recipe.BaseChoice
import io.github.psd2live.core.recipe.ModuleChoice
import io.github.psd2live.core.recipe.RigModules
import io.github.psd2live.core.recipe.RigRecipe
import io.github.psd2live.project.WorkspaceSettingsCodec
import kotlinx.serialization.json.*

internal object RigMouthJawModifier {
    private val algorithm = object : ReadingModifierAlgorithmPlugin by MouthJawAlgorithm(),
        ModifierOptionalUpstream, ModifierVersionSelection {
        override val optionalUpstream = MouthJawAlgorithm.optionalUpstream
        override val canonicalOptInType = MouthJawAlgorithm.TYPE
    }

    fun definition(source: RigModifierSource?): RigModifierDefinition =
        RigLegacyBindings.definition(RigLegacyBindings.spec(MouthJawAlgorithm.TYPE), source).copy(
            version = MouthJawAlgorithm.VERSION, parameters = { emptyList() }, resolveParameter = { _, _, _ -> null },
            plugin = algorithm, upstreamInputs = true, changeWrites = setOf("mesh"),
            evaluate = { _, _, _ -> error("Mouth jaw requires captured source measurements") },
            authorContext = { requireNotNull(source).capture(it) },
        )

    private fun RigModifierSource.capture(instance: RigModifierInstance): ModifierAlgorithmContext {
        val scaffold = provider(instance)
        require(scaffold.version == 2) { "Mouth jaw v2 requires a shared v2 scaffold" }
        val preceding = activeBefore(instance).filter { item ->
            item.config["scaffold"] == JsonPrimitive(scaffold.id) && item.targets.any(instance.targets::contains) &&
                RigLegacyBindings.find(item.type)?.let { !it.expressionToggles && !it.seeThrough } == true
        }
        val baseId = instance.config.getValue("base").jsonPrimitive.content
        require(preceding.any { it.id == baseId && it.type == "mouth/fitted" && it.version in 1..2 }) {
            "Mouth jaw v2 requires a compatible fitted mouth"
        }
        require(preceding.none { it.type.startsWith("mouth.") && it.type !in MouthJawAlgorithm.mouthUpstream }) {
            "Use mouth jaw v1 after other mouth modules"
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
                chosen != null -> ModuleChoice(true, chosen.version, RigLegacyBindings.values(chosen))
                else -> ModuleChoice(false)
            }
        }, moves = corrections.moves, eyeParts = corrections.eyeParts)
        val tuning = RigLegacyBindings.specs.fold(RigTuning()) { current, spec ->
            state[spec.type]?.let { RigLegacyBindings.tuning(it, current) } ?: current
        }
        val head = state[RigLegacyBindings.FITTED_FACE] ?: state["face/legacy"]
        val outline = state["mouth.outline"]
        val native = sourceConfig().copy(recipe = recipe, rigTuning = tuning,
            mouthOutlineEnabled = outline != null,
            headTurnStrength = head?.let(RigLegacyBindings::strength) ?: 1f,
            bodyStrength = state["body/legacy"]?.let(RigLegacyBindings::strength) ?: 1f,
            featureDisplacementEnabled = scaffold.config["featureDisplacement"]?.jsonPrimitive?.boolean ?: false,
            hairSimulationFront = !(scaffold.config["frontHairSway"]?.jsonPrimitive?.boolean ?: true),
            hairSimulationBack = !(scaffold.config["backHairSway"]?.jsonPrimitive?.boolean ?: true))
        val context = RigBuilder.rigContext(analysis, native, meshCache)
        val fitted = requireNotNull(context.fittedMouth(native))
        val measured = requireNotNull(fitted.source) { "Mouth jaw v2 requires readable fitted mouth artwork" }
        require(!fitted.layered) { "Use mouth jaw v1 for artwork with separate lip or line layers" }
        val sourceLayers = context.analysis.layers.associateBy { it.source.id.raw }
        val materials = mutableListOf<ScaffoldMeshContext.Material>()
        val targets = buildJsonObject {
            for (target in instance.targets) {
                val layer = sourceLayers[layersByMesh[target.id]] ?: continue
                val aligned = context.rigLayer(layer)
                val tag = aligned.semantic.tag
                if (fitted.covers(aligned) || aligned.semantic.role?.let { it != PartRole.SHADOW } == true) continue
                if (tag != SemanticTag.FACE && tag != SemanticTag.NOSE &&
                    !(tag == SemanticTag.FACE_DETAIL && measured.fit.toMouth(aligned.centroidX, aligned.centroidY).second > 0f)) continue
                require(preceding.all { target in it.targets }) { "Mouth jaw v2 requires a shared source frame for its targets" }
                val (parent, frame) = context.parentAndFrame(layer, native)
                val data = RigBuilder.fittedMouthSourceMesh(layer, context, native, atlas, meshCache)
                materials += ScaffoldMeshContext.Material(target.id, layer.source.id.raw,
                    ScaffoldMesh.RawMesh(data.rigPositions, data.mesh.uvs, data.mesh.indices), ScaffoldMesh.Geometry.Zero)
                putJsonObject(target.id) {
                    put("nose", tag == SemanticTag.NOSE)
                    put("parent", parent?.raw?.let(::JsonPrimitive) ?: JsonNull)
                    put("frame", JsonArray(listOf(frame.left, frame.top, frame.right, frame.bottom).map(::JsonPrimitive)))
                }
            }
        }
        val fit = measured.fit
        return ModifierAlgorithmContext(buildJsonObject {
            put("schema", "mouth-jaw/1")
            put("fit", JsonArray(listOf(fit.originX, fit.originY, fit.axisX, fit.axisY, fit.width, fit.opening).map(::JsonPrimitive)))
            put("reach", measured.options.reach)
            put("chin", fitted.chin)
            put("targets", targets)
            putJsonObject("providers") {
                for (provider in preceding.filter { it.type in MouthJawAlgorithm.optionalUpstream }) {
                    put(provider.type, provider.id)
                }
            }
        }, ScaffoldMeshContext.capture(materials).resources)
    }
}
