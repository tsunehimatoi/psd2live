package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.EyeFittedAlgorithm
import io.github.psd2live.algorithms.binding.EyeLegacyInputs
import io.github.psd2live.algorithms.binding.EyeLegacyMotion
import io.github.psd2live.algorithms.binding.EyeLegacySourceAlgorithm
import io.github.psd2live.algorithms.binding.EyeOpeningObservation
import io.github.psd2live.algorithms.binding.ScaffoldBranchesContext
import io.github.psd2live.algorithms.binding.ScaffoldFramesContext
import io.github.psd2live.algorithms.binding.ScaffoldMesh
import io.github.psd2live.core.*
import io.github.psd2live.core.recipe.RigRecipe
import io.github.psd2live.i18n.tr
import io.github.psd2live.project.WorkspaceSettingsCodec
import kotlinx.serialization.json.*

/** Captures source eye observations for the explicitly selected legacy eye v2. */
internal object RigLegacyEyeDefinition {
    private val algorithm = object : ReadingModifierAlgorithmPlugin by EyeLegacySourceAlgorithm(),
        ModifierOptionalUpstream, ModifierVersionSelection {
        override val optionalUpstream = EyeLegacySourceAlgorithm.STYLED_HEAD + "face/legacy"
        override val canonicalOptInType = EyeLegacySourceAlgorithm.TYPE
    }
    private val spec = RigLegacyBindings.spec(EyeLegacySourceAlgorithm.TYPE)
    private val tags = setOf(SemanticTag.EYEWHITE, SemanticTag.EYELASH, SemanticTag.IRIDES, SemanticTag.EYE_CLOSE)

    fun definition(source: RigModifierSource?): RigModifierDefinition = RigLegacyBindings.definition(spec, source).copy(
        version = EyeLegacySourceAlgorithm.VERSION,
        parameters = { EyeFittedAlgorithm.parameters(parameterNames()) },
        evaluate = { _, _, _ -> error("Legacy eye v2 requires captured scaffold inputs") },
        plugin = algorithm, upstreamInputs = true, changeWrites = EyeLegacySourceAlgorithm.WRITES,
        authorContext = { capture(requireNotNull(source), it) },
    )

    private fun parameterNames() = StandardParameters.all.filter { it.id in spec.parameters }.associate { it.id.raw to it.name }

    private fun capture(owner: RigModifierSource, instance: RigModifierInstance): ModifierAlgorithmContext {
        val provider = owner.provider(instance)
        require(provider.version == 2) { "Legacy eyes v2 require the complete version 2 scaffold scope" }
        val scaffold = owner.scaffoldContext(provider)
        require(scaffold.values["schema"] == JsonPrimitive("scaffold/5")) { "Legacy eyes v2 require a source scaffold" }
        val options = provider.config
        val corrections = WorkspaceSettingsCodec.decodeRecipe(options["corrections"])
        val config = owner.sourceConfig().copy(
            generateDeformers = false,
            recipe = RigRecipe.Legacy.copy(moves = corrections.moves, eyeParts = corrections.eyeParts),
            featureDisplacementEnabled = options["featureDisplacement"]?.jsonPrimitive?.boolean ?: false,
            hairSimulationFront = !(options["frontHairSway"]?.jsonPrimitive?.boolean ?: true),
            hairSimulationBack = !(options["backHairSway"]?.jsonPrimitive?.boolean ?: true),
        )
        val context = RigBuilder.rigContext(owner.analysis, config, owner.meshCache)
        val frames = ScaffoldFramesContext.build(scaffold.values.getValue("frames").jsonObject)
        val parents = ScaffoldBranchesContext.build(scaffold.values.getValue("branches").jsonObject, frames).parents
        val materialIds = scaffold.values.getValue("mesh_inputs").jsonObject.getValue("materials").jsonArray
            .mapTo(hashSetOf()) { it.jsonObject.getValue("id").jsonPrimitive.content }
        val clips = RigOrdinaryMaterials.clipping(owner.analysis.source, owner.primaryByLayer.entries.associate { it.value to it.key })
        val tuning = RigLegacyBindings.tuning(instance, RigTuning())
        val eyeTuning = EyeLegacyMotion.Tuning(tuning.blinkCorner, tuning.blinkDepth, tuning.eyelashSquash,
            tuning.irisJellySquash, tuning.irisJellyStretch, tuning.gazeX, tuning.gazeY)
        val layers = context.analysis.layers.filter {
            it.semantic.tag in tags && it.opaquePixels > 0 && owner.primaryByLayer[it.source.id.raw] in materialIds
        }
        val records = layers.associate { layer ->
            val sourceId = layer.source.id.raw
            val id = owner.primaryByLayer.getValue(sourceId)
            val aligned = context.rigLayer(layer)
            val tag = layer.semantic.tag
            val blink = if (tag == SemanticTag.EYEWHITE || tag == SemanticTag.EYELASH) {
                ScaffoldMesh.Geometry.Eye(tag == SemanticTag.EYELASH, aligned.bounds,
                    RigBuilder.matchingEyeWhiteBounds(aligned, context.eyeWhiteLayers), aligned.centroidY, layer.source.bounds.left,
                    if (tag == SemanticTag.EYELASH && context.faceRig.initialAngleZ == 0f) RigBuilder.eyelashCenterline(aligned) else null,
                    eyeTuning.blinkCorner, eyeTuning.blinkDepth, eyeTuning.lashSquash)
            } else null
            val iris = if (tag == SemanticTag.IRIDES) ScaffoldMesh.Geometry.Iris(aligned.centroidX, aligned.centroidY,
                eyeTuning.irisSquash, eyeTuning.irisStretch) else null
            val drawing = tag == SemanticTag.EYE_CLOSE && (config.layerOverrides[sourceId]?.type ?: layer.semantic.type) == LayerType.PRESET
            id to EyeLegacyInputs.Layer(layer.semantic.side, parents.getValue(sourceId).frame, blink, iris, drawing,
                listOfNotNull(clips[sourceId]?.raw), iris?.let { observation(layer, id) })
        }
        val openings = layers.filter { it.semantic.tag == SemanticTag.EYEWHITE }.sortedBy { it.source.order }
            .map { observation(it, owner.primaryByLayer.getValue(it.source.id.raw)) }
        val eye = EyeLegacyInputs(context.faceRig.centerX, eyeTuning, records, scaffold.resources.getValue("source_meshes"), openings).encode()
        return ModifierAlgorithmContext(buildJsonObject {
            put("scaffold", scaffold.values)
            put("eye", eye.values)
            put("parameter_names", JsonObject(parameterNames().mapValues { JsonPrimitive(it.value) }))
            put("group_name", tr("model.group.eyes"))
            putJsonObject("opacity") {
                for (layer in layers) put(owner.primaryByLayer.getValue(layer.source.id.raw), layer.source.opacity.coerceIn(0f, 1f))
            }
        }, scaffold.resources - "materials")
    }

    private fun observation(layer: ClassifiedLayer, id: String): EyeOpeningObservation = EyeOpeningObservation(
        id, layer.source.id.raw, layer.semantic.side, layer.bounds, layer.centroidX, layer.centroidY,
        layer.source.groupPath, layer.semantic.parameter.takeIf { layer.semantic.type == LayerType.SWITCH },
        layer.semantic.switchId.takeIf { layer.semantic.type == LayerType.SWITCH },
    )
}
