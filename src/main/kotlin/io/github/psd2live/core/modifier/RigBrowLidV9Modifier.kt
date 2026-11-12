package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.BrowLidV9Algorithm
import io.github.psd2live.algorithms.binding.BrowSourceProvidersV1
import io.github.psd2live.core.LayerType
import org.umamo.format.art.LayerBlend
import io.github.psd2live.algorithms.binding.ScaffoldBinding
import io.github.psd2live.core.RigBuilder
import io.github.psd2live.core.RigTuning
import io.github.psd2live.core.SemanticTag
import io.github.psd2live.core.recipe.BaseChoice
import io.github.psd2live.core.recipe.ModuleChoice
import io.github.psd2live.core.recipe.RigModules
import io.github.psd2live.core.recipe.RigRecipe
import io.github.psd2live.i18n.tr
import io.github.psd2live.project.WorkspaceSettingsCodec
import kotlinx.serialization.json.*

internal object RigBrowLidV9Modifier {
    private val algorithm = object : ReadingModifierAlgorithmPlugin by BrowLidV9Algorithm(),
        ModifierOptionalUpstream, ModifierVersionSelection {
        override val optionalUpstream = BrowLidV9Algorithm.optionalUpstream
        override val canonicalOptInType = BrowLidV9Algorithm.TYPE
    }

    fun definition(source: RigModifierSource?): RigModifierDefinition = RigBrowLidModifier.definition(source).copy(
        version = BrowLidV9Algorithm.VERSION, plugin = algorithm, changeWrites = BrowLidV9Algorithm.WRITES,
        requires = BrowSourceProvidersV1.required(BrowSourceProvidersV1.Stage.LID),
        providers = { instance -> source?.sourceProviders(instance) ?: RigLegacyBindings.providers(instance) },
        authorContext = { requireNotNull(source).captureSingleEye(it) },
    )

    private fun RigModifierSource.sourcePrefix(instance: RigModifierInstance): List<RigModifierInstance> {
        require(appliedSourceCapabilities.none { it.id == null && it.targets.any(instance.targets::contains) }) {
            "Brow and lid v9 requires identified source providers"
        }
        val owners = activeBefore(instance).filter { it.targets.any(instance.targets::contains) }
        val scaffold = instance.config.getValue("scaffold")
        require(owners.map { it.id }.distinct().size == owners.size && owners.all { owner ->
            BrowSourceProvidersV1.acceptsVersion(BrowSourceProvidersV1.Stage.LID, owner.type, owner.version) &&
                owner.targets.toSet() == instance.targets.toSet() &&
                (if (owner.type == "rig.scaffold") JsonPrimitive(owner.id) == scaffold else owner.config["scaffold"] == scaffold) &&
                BundledAlgorithmArtifacts.find(owner.type, owner.version)?.reference?.let { it == owner.plugin } == true
        }) { "Brow and lid v9 rejects unknown, foreign or incompatible source providers" }
        return owners
    }

    private fun RigModifierSource.sourceProviders(instance: RigModifierInstance): Map<String, String> {
        val owners = sourcePrefix(instance)
        val selected = RigLegacyBindings.providers(instance)
        return BrowSourceProvidersV1.required(BrowSourceProvidersV1.Stage.LID).associateWith { type ->
            owners.singleOrNull { it.type == type && (selected[type] == null || selected[type] == it.id) }?.id
                ?: generateSequence("${instance.id}/missing:$type") { "$it?" }.first { missing -> owners.none { it.id == missing } }
        }
    }

    private fun RigModifierSource.validateSource(instance: RigModifierInstance) {
        val original = sourceConfig()
        require(!skeletonEnabled && !hasCustomSourceStructure && analysis.source.groups.isEmpty() &&
            analysis.source.layers.all { it.groupPath.isEmpty() }) {
            "Brow and lid v9 requires flat original source artwork without custom structure"
        }
        val eyeTags = setOf(SemanticTag.IRIDES, SemanticTag.EYEWHITE, SemanticTag.EYELASH, SemanticTag.EYE_CLOSE, SemanticTag.EYEBROW)
        val ordinaryTags = setOf(SemanticTag.FRONT_HAIR, SemanticTag.BACK_HAIR, SemanticTag.TOPWEAR,
            SemanticTag.FACE, SemanticTag.MOUTH, SemanticTag.EYEBROW, SemanticTag.NOSE)
        require(analysis.layers.all { layer ->
            layer.opaquePixels > 0 && layer.source.id.raw in atlas.placementByLayerId &&
                layer.semantic.type == LayerType.PRESET && layer.semantic.parameter.isBlank() &&
                layer.source.blend == LayerBlend.Normal && !layer.source.clipped &&
                (original.layerOverrides[layer.source.id.raw]?.type ?: LayerType.PRESET) == LayerType.PRESET &&
                original.layerOverrides[layer.source.id.raw]?.parameter.orEmpty().isBlank() &&
                layer.semantic.tag in eyeTags + ordinaryTags &&
                (layer.semantic.tag !in eyeTags || layer.semantic.side == io.github.psd2live.core.Side.LEFT) &&
                (layer.semantic.tag !in ordinaryTags + setOf(SemanticTag.EYEWHITE, SemanticTag.EYELASH) || layer.semantic.role == null)
        }) { "Brow and lid v9 requires ordinary left-sided source materials without mouth interiors" }
        for (tag in listOf(SemanticTag.FACE, SemanticTag.EYEBROW, SemanticTag.EYEWHITE, SemanticTag.EYELASH, SemanticTag.MOUTH))
            require(analysis.layers.count { it.semantic.tag == tag } == 1)
        require(analysis.layers.single { it.semantic.tag == SemanticTag.MOUTH }.semantic.side == io.github.psd2live.core.Side.NONE)
        val targets = instance.targets.associate { it.id to layersByMesh.getValue(it.id) }
        require(targets.values.toSet() == analysis.source.layers.mapTo(hashSetOf()) { it.id.raw } &&
            targets.size == analysis.layers.size && targets.values.toSet().size == targets.size &&
            targets.all { (id, layer) -> primaryByLayer[layer] == id } && layersByMesh.size == targets.size) {
            "Brow and lid v9 requires the complete original unsplit and uncopied target inventory"
        }
        val measured = RigBuilder.rigContext(analysis, original.copy(generateDeformers = false), meshCache)
        require(measured.faceRig.initialAngleZ == 0f) { "Brow and lid v9 requires upright source artwork" }
    }

    private fun RigModifierSource.captureSingleEye(instance: RigModifierInstance): ModifierAlgorithmContext {
        val owners = sourcePrefix(instance)
        val scaffold = provider(instance)
        val prior = owners.filterNot { it.id == scaffold.id }
        val providers = owners.map { owner -> ModifierUpstreamCapability(owner.type, owner.type, owner.version,
            owner.targets, owner.id, owner.config, owner.outputAliases, owner.plugin) }
        BrowSourceProvidersV1.validate(providers, instance, BrowSourceProvidersV1.Stage.LID)
        validateSource(instance)
        require(scaffold.version == 2)
        require(prior.none { it.type in setOf(BrowLidV9Algorithm.TYPE, "face.depth-turn", "mouth.cheek-lid") })
        val eye = prior.single { it.id == instance.config.getValue("dependencies").jsonObject.getValue("eye/fitted").jsonPrimitive.content }
        val brow = prior.single { it.id == instance.config.getValue("base").jsonPrimitive.content }
        val face = prior.single { it.type == "face/fitted" }
        val shape = prior.single { it.type == "brow.shape" }
        require(eye.type == "eye/fitted" && eye.version == 5 && brow.type == "brow/fitted" && brow.version == 5 &&
            face.version == 3 && shape.version == 7 && shape.config["base"] == JsonPrimitive(brow.id) &&
            shape.targets.toSet() == instance.targets.toSet() && brow.targets.toSet() == instance.targets.toSet() &&
            listOf(eye, brow, face, shape).all { it.targets.containsAll(instance.targets) }) {
            "Brow and lid v9 requires shared scaffold2/face3/eye5/brow5 with shape7 on the same targets"
        }
        val corrections = WorkspaceSettingsCodec.decodeRecipe(scaffold.config["corrections"])
        val recipe = RigRecipe(true, bases = mapOf("eye" to BaseChoice(eye.type, eye.version)),
            modules = RigModules.modules.associate { it.id to ModuleChoice(false) },
            moves = corrections.moves, eyeParts = corrections.eyeParts)
        val native = sourceConfig().copy(recipe = recipe, rigTuning = RigLegacyBindings.tuning(eye, RigTuning()),
            headTurnStrength = 1f, bodyStrength = 1f,
            featureDisplacementEnabled = scaffold.config["featureDisplacement"]?.jsonPrimitive?.boolean ?: false,
            hairSimulationFront = !(scaffold.config["frontHairSway"]?.jsonPrimitive?.boolean ?: true),
            hairSimulationBack = !(scaffold.config["backHairSway"]?.jsonPrimitive?.boolean ?: true))
        val captured = capture(instance)
        val brows = analysis.layers.filter { it.semantic.tag == SemanticTag.EYEBROW && it.semantic.role == null }
        val sides = instance.targets.mapNotNull { target ->
            val layer = brows.singleOrNull { it.source.id.raw == layersByMesh[target.id] } ?: return@mapNotNull null
            target.id to JsonPrimitive(layer.semantic.side.name)
        }.toMap()
        val source = ModifierAlgorithmContext(JsonObject(captured.values + mapOf(
            "shape" to JsonPrimitive(shape.id), "brow_sides" to JsonObject(sides))), captured.resources)
        // Keep the eye-only source recipe and head-shell measurement timing identical to v8.
        val measured = captureLidHeadSource(instance, scaffold, eye, native, source)
        return ModifierAlgorithmContext(JsonObject(measured.values + mapOf(
            "providers" to JsonArray(providers.map { Json.encodeToJsonElement(ModifierUpstreamCapability.serializer(), it) }),
            "provider_packages" to JsonObject(owners.associate { owner -> owner.id to
                Json.encodeToJsonElement(ModifierPluginReference.serializer(), requireNotNull(owner.plugin)) }),
            "source_inventory" to JsonArray(instance.targets.map { target ->
                val layer = analysis.layers.single { it.source.id.raw == layersByMesh.getValue(target.id) }
                buildJsonObject {
                    put("id", target.id); put("source_id", layer.source.id.raw)
                    put("tag", layer.semantic.tag.name); put("side", layer.semantic.side.name)
                    put("role", layer.semantic.role?.name?.let(::JsonPrimitive) ?: JsonNull)
                }
            }),
        )), measured.resources)
    }

    private fun RigModifierSource.capture(instance: RigModifierInstance): ModifierAlgorithmContext {
        val scaffold = provider(instance)
        val eyeId = instance.config.getValue("dependencies").jsonObject.getValue("eye/fitted").jsonPrimitive.content
        val eye = activeBefore(instance).single { it.id == eyeId && it.type == "eye/fitted" }
        val corrections = WorkspaceSettingsCodec.decodeRecipe(scaffold.config["corrections"])
        val recipe = RigRecipe(true, bases = mapOf("eye" to BaseChoice(eye.type, eye.version)),
            modules = RigModules.modules.associate { it.id to ModuleChoice(false) },
            moves = corrections.moves, eyeParts = corrections.eyeParts)
        val native = sourceConfig().copy(recipe = recipe, rigTuning = RigLegacyBindings.tuning(eye, RigTuning()),
            featureDisplacementEnabled = scaffold.config["featureDisplacement"]?.jsonPrimitive?.boolean ?: false)
        val targets = instance.targets.associate { it.id to layersByMesh.getValue(it.id) }
        val layers = analysis.layers.associateBy { it.source.id.raw }
        val measurements = RigBuilder.eyeWideSourceInputs(analysis, atlas, native, meshCache, targets) { mesh, parent ->
            ScaffoldBinding.deformerId(scaffold, mesh, parent)
        }
        val context = RigBuilder.rigContext(analysis, native, meshCache, neutralScaffold = true)
        return ModifierAlgorithmContext(JsonObject(measurements.values + mapOf(
            "brow_cover" to JsonPrimitive(context.browCover),
            "brow_meshes" to JsonArray(targets.filterValues { layer ->
                layers.getValue(layer).semantic.let { it.tag == SemanticTag.EYEBROW && it.role == null }
            }.keys.map(::JsonPrimitive)),
            "carrier_names" to JsonObject(targets.mapValues { (_, layer) ->
                JsonPrimitive(tr("model.deformer.meshShape", layers.getValue(layer).source.name))
            }),
            "native_ids" to JsonObject(targets.mapValues { (_, layer) -> JsonPrimitive(primaryByLayer.getValue(layer)) }),
        )), measurements.resources)
    }
}
