package io.github.psd2live.core.modifier

import io.github.psd2live.core.*
import io.github.psd2live.core.recipe.*
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.*
import org.umamo.edit.withParameterDeleted
import org.umamo.runtime.keyform.*
import org.umamo.runtime.model.*

/** Immutable source inputs captured with the candidate, never the editor's current selection or a previous output. */
internal class RigModifierSource(
    private val analysis: PipelineAnalysis,
    private val atlas: PackedAtlas,
    private val config: PipelineConfig,
    private val layersByMesh: Map<String, String>,
    copiedMeshes: Set<String> = emptySet(),
    private val materialBase: PuppetModel,
    private val meshCache: PreviewMeshCache = PreviewMeshCache(),
) {
    val physicsOrder: List<String> get() = config.rigEdits.physicsOrder
    private val stack by lazy { RigModifierStack.fromJson(requireNotNull(config.rigEdits.modifierStack)) }
    private val parameterDefinitions = java.util.IdentityHashMap<PuppetModel, List<io.github.psd2live.format.model.Parameter>>()
    private val primaryByLayer = layersByMesh.entries.sortedBy { it.key in copiedMeshes }.groupBy { it.value }.mapValues { it.value.first().key }
    private val projections = java.util.IdentityHashMap<PuppetModel, MutableMap<String, PuppetModel>>()
    private fun generated(strength: Float = 1f, tuning: RigTuning = RigTuning(), headStrength: Float = 1f,
                          recipe: RigRecipe = RigRecipe.Legacy, outline: RigModifierInstance? = null,
                          featureDisplacement: Boolean = false, frontHairSway: Boolean = true, backHairSway: Boolean = true, capture: ((() -> PuppetModel) -> Unit)? = null): BuiltRig {
        val clean = RigModifierPipeline.ordinaryConfig(config).copy(meshOnly = false, generateDeformers = true,
            deferRig = false, rigPendingLayerIds = emptySet(), parentOverrides = emptyMap(),
            rigEdits = RigEditOverlay(splitDrawableIds = primaryByLayer))
        val native = clean.copy(bodyStrength = strength, headTurnStrength = headStrength, rigTuning = tuning, recipe = recipe,
            featureDisplacementEnabled = featureDisplacement, mouthOutlineEnabled = outline != null,
            hairSimulationFront = !frontHairSway, hairSimulationBack = !backHairSway,
            mouthThickness = outline?.let { RigLegacyBindings.values(it)["thickness"] } ?: 1.5f,
            mouthColor = outline?.let(RigLegacyBindings::outlineColor))
        if (outline == null) return RigBuilder.build(analysis, atlas, native, meshCache, capture)
        val prepared = MouthLipLayers.prepare(analysis, native)
        val lips = prepared.layers.filter { it.source is MouthLipLayer }
        return RigBuilder.build(prepared, RigModifierRasterAssets.append(atlas, lips, native), native, meshCache, capture)
    }

    private data class Template(val model: PuppetModel, val chains: List<RigModifierChains.Binding>, val bindingInput: (() -> PuppetModel)? = null)
    private val templates = HashMap<Map<String, JsonObject>, Template>()
    private val transitions = HashMap<Pair<String, ModifierTarget>, Pair<RigModifierChains.Projection, RigModifierChains.Projection>>()
    private val chainProjections = java.util.IdentityHashMap<PuppetModel, MutableMap<List<RigModifierChains.Owned>, RigModifierChains.Projection>>()

    fun seeThrough(rig: RigIR, instance: RigModifierInstance, checkpoint: () -> Unit): RigIR {
        checkpoint()
        val model = PuppetIr.toPuppet(rig)
        val layers = analysis.layers.associateBy { it.source.id.raw }
        val targets = instance.targets.mapTo(HashSet()) { it.id }
        val eyes = instance.type == "eye.see-through"
        val owners = activeBefore(instance).filter { RigLegacyBindings.find(it.type)?.seeThrough == true }
        val earlierCopies = model.drawables.mapNotNull { mesh ->
            mesh.id.raw.takeIf { id -> owners.any { it.localOutputName(id)?.startsWith("see-through:") == true } }
        }.toSet()
        val candidates = SeeThroughLayers.candidates(model, config.alphaThreshold) { mesh ->
            if (mesh.raw in earlierCopies) null else layers[layersByMesh[mesh.raw]]
        }.filter { it.eyes == eyes && it.original in targets }
        val opacity = (RigLegacyBindings.values(instance)["opacity"] ?: 50f) / 100f
        val result = SeeThroughLayers.apply(model, candidates,
            SeeThroughLayers.Settings(!eyes, eyes, opacity, config.drawOrderStep,
                config.runtimeTarget.supports(org.umamo.runtime.model.RuntimeFeature.ReversedMask)),
            idFor = { DrawableId(instance.outputId("see-through:${it.raw}")) }, earlierCopies = earlierCopies)
        require(result.notes.none { it.reason == SeeThroughLayers.DropReason.ID_TAKEN ||
            it.reason == SeeThroughLayers.DropReason.BANGS_CLIPPED }) {
            "${instance.id}: see-through output conflicts with existing meshes or masks"
        }
        checkpoint()
        if (result.copies.isEmpty()) return rig
        return rig.withPuppet(result.model.copy(drawables = result.model.drawables.map {
            if (it.id.raw in result.copies) it.copy(isSelectable = true) else it
        }))
    }

    private val expressionParameterDefinitions by lazy {
        val layers = analysis.layers.mapNotNull { layer ->
            val override = config.layerOverrides[layer.source.id.raw]
            val type = override?.type ?: layer.semantic.type
            val parameter = (override?.parameter ?: layer.semantic.parameter).trim()
            if (type == LayerType.PRESET || parameter.isBlank()) null else layer.source.id.raw to
                io.github.psd2live.algorithms.binding.ExpressionChannels.Layer(parameter,
                    if (type == LayerType.SWITCH) override?.switchId ?: layer.semantic.switchId else null)
        }.toMap()
        val catalog = io.github.psd2live.algorithms.binding.ExpressionChannels.catalog(layers.values.toList(), staggered = true)
        // Preserve existing standard-name resolution until the standard catalog is independently packaged.
        val parameters = if (catalog.parameters.any { custom -> StandardParameters.all.any { it.id == custom.id } }) {
            generated(recipe = RigRecipe(true, modules = RigModules.modules.associate {
                it.id to ModuleChoice(it == RigModules.EXPRESSION_TOGGLES)
            })).puppet.parameters
        } else catalog.parameters
        PuppetIr.parametersToIr(materialBase.copy(parameters = parameters))
    }

    private val expressionLayers by lazy {
        analysis.layers.mapNotNull { layer ->
            val override = config.layerOverrides[layer.source.id.raw]
            val type = override?.type ?: layer.semantic.type
            val parameter = (override?.parameter ?: layer.semantic.parameter).trim()
            (layer.source.id.raw to ParameterId(parameter)).takeIf { type != LayerType.PRESET && parameter.isNotEmpty() }
        }.toMap()
    }

    private fun expressionIds(instance: RigModifierInstance) = instance.targets.mapNotNullTo(linkedSetOf()) {
        expressionLayers[layersByMesh[it.id]]
    }

    fun expressionParameters(instance: RigModifierInstance): List<io.github.psd2live.format.model.Parameter> {
        val ids = expressionIds(instance).mapTo(HashSet()) { it.raw }
        if (ids.isEmpty()) return emptyList()
        return expressionParameterDefinitions
            .filter { it.id in ids }
    }

    fun expressionContext(instance: RigModifierInstance): ModifierAlgorithmContext = ModifierAlgorithmContext(buildJsonObject {
        put("schema", "expression/1")
        putJsonObject("layers") {
            analysis.layers.forEach { layer ->
                val override = config.layerOverrides[layer.source.id.raw]
                val type = override?.type ?: layer.semantic.type
                val parameter = (override?.parameter ?: layer.semantic.parameter).trim()
                if (type != LayerType.PRESET && parameter.isNotBlank()) putJsonObject(layer.source.id.raw) {
                    put("parameter", parameter)
                    if (type == LayerType.SWITCH) put("switch", override?.switchId ?: layer.semantic.switchId)
                }
            }
        }
        put("meshes", JsonObject(layersByMesh.mapValues { JsonPrimitive(it.value) }))
        putJsonObject("opacity") { analysis.layers.forEach { put(it.source.id.raw, it.source.opacity.coerceIn(0f, 1f)) } }
        put("chain_owners", Json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(RigModifierInstance.serializer()),
            activeBefore(instance).filter { RigModifierChains.kinds(it.type).isNotEmpty() }))
    }, resources = mapOf("parameters" to ModifierAlgorithmResource(io.github.psd2live.format.compile.RigIrBinary.encode(
        RigIR(io.github.psd2live.format.model.Canvas(1f, 1f), expressionParameters(instance))))))

    fun expression(rig: RigIR, instance: RigModifierInstance, checkpoint: () -> Unit): RigIR =
        io.github.psd2live.algorithms.binding.ExpressionAlgorithm().evaluate(rig, instance, expressionContext(instance), checkpoint)

    fun addUpstreamExpressionInputs(rig: RigIR, instance: RigModifierInstance): RigIR {
        val preceding = activeBefore(instance).filter { RigLegacyBindings.find(it.type)?.expressionToggles == true }
        return RigModifierPhysics.addExpressionInputs(rig, listOf(instance), preceding.flatMap { expressionIds(it).map(ParameterId::raw) })
    }

    fun outline(rig: RigIR, instance: RigModifierInstance, checkpoint: () -> Unit): RigIR {
        val thickness = RigLegacyBindings.values(instance)["thickness"] ?: 1.5f
        val outlineConfig = RigModifierPipeline.ordinaryConfig(config).copy(
            meshOnly = false, deferRig = false, mouthOutlineEnabled = true, mouthThickness = thickness,
            mouthColor = RigLegacyBindings.outlineColor(instance))
        val lipLayers = MouthLipLayers.prepare(analysis, outlineConfig).layers.mapNotNull {
            (it.source as? MouthLipLayer)?.let { lip -> (lip.ownerId to lip.side) to it }
        }.toMap()
        val model = PuppetIr.toPuppet(rig)
        val ribbons = instance.targets.flatMap { target ->
            checkpoint()
            val layer = layersByMesh.getValue(target.id)
            if (lipLayers.keys.none { it.first == layer }) return@flatMap emptyList()
            val native = template(relevantBefore(instance, target) + instance, provider(instance).config).model
            val canonical = primaryByLayer.getValue(layer)
            val owner = model.drawables.single { it.id.raw == target.id }
            (0..1).mapNotNull { side ->
                val lip = native.drawables.singleOrNull { it.id.raw == "${canonical}_lip_$side" } ?: return@mapNotNull null
                val source = lipLayers.getValue(layer to side)
                val id = DrawableId(instance.outputId("mouth-lip:${target.id}:$side"))
                val assetId = instance.outputId("mouth-art:${target.id}:$side")
                val asset = source.copy(source = object : org.umamo.format.art.SourceLayer by source.source {
                    override val id = org.umamo.format.art.LayerId(assetId)
                })
                val drawable = lip.copy(id = id, parentDeformerId = owner.parentDeformerId, maskedBy = owner.maskedBy,
                    invertMask = owner.invertMask, isVisible = owner.isVisible, textureSourceId = null,
                    drawOrder = (owner.drawOrder + 1f).coerceAtMost(1000f), atlasTileId = PuppetSourceAtlas.tileIdFor(assetId))
                val paths = native.deformPaths.filter { it.drawableId == lip.id }.map { path ->
                    path.copy(id = instance.outputId("mouth-path:${target.id}:$side:${path.id}"), drawableId = id)
                }
                RigModifierRasterAssets.Ribbon(owner.id, drawable, asset, paths)
            }
        }
        return RigModifierRasterAssets.attach(rig, model, ribbons, analysis, config)
    }

    fun body(strength: Float, tuning: RigTuning = RigTuning(), checkpoint: () -> Unit): PuppetModel {
        checkpoint()
        return restricted(generated(strength, tuning).puppet, RigBodyModifier.parameters).also { checkpoint() }
    }

    private val staticModels = java.util.IdentityHashMap<PuppetModel, PuppetModel>()
    fun static(model: PuppetModel) = staticModels.getOrPut(model) { restricted(model, emptySet()) }

    private fun templateKey(instances: List<RigModifierInstance>, scaffold: JsonObject): Map<String, JsonObject> {
        val state = instances.associateBy { it.type }
        val key = state.mapValues { (_, instance) -> buildJsonObject {
            // This is an intermediate geometry template, not a modifier-result cache. Pendulums are evaluated separately.
            put("version", instance.version); put("config", JsonObject(instance.config - "scaffold" - "base" - "physics"))
        } }
        return key + ("rig.scaffold" to scaffold)
    }

    private fun template(instances: List<RigModifierInstance>, scaffold: JsonObject): Template {
        val state = instances.associateBy { it.type }
        val key = templateKey(instances, scaffold)
        return templates.getOrPut(key) {
            // Preview bitmaps and encoded atlas pages are presentation objects recreated on rebuild.
            // Geometry reads classified source pixels and atlas placement, not those objects' identity.
            val sharedKey = listOf(analysis.source, analysis.layers, analysis.anchors, analysis.warnings, analysis.calibration,
                atlas.placementByLayerId, atlas.pages.map { it.image.width to it.image.height }, atlas.footprints,
                RigModifierPipeline.ordinaryConfig(config), primaryByLayer, key, io.github.psd2live.i18n.I18n.currentLanguage)
            val build = { buildTemplate(instances, scaffold, state) }
            if (RigStageCache.enabled) meshCache.stages.get("modifier geometry template", 64, sharedKey, build) else build()
        }
    }

    private fun buildTemplate(instances: List<RigModifierInstance>, scaffold: JsonObject, state: Map<String, RigModifierInstance>): Template {
        // Keep the full generator as the uncached equivalence path. Color-only additions do not rebuild geometry.
        if (RigStageCache.enabled && instances.lastOrNull()?.type in setOf("expression.cheek", "expression.gloom") &&
            instances.dropLast(1).any { !it.type.endsWith("/legacy") }) {
            moodTemplate(instances, scaffold, state)?.let { return it }
        }


            var tuning = RigTuning()
            for (spec in RigLegacyBindings.specs) state[spec.type]?.let { tuning = RigLegacyBindings.tuning(it, tuning) }
            val bodyStrength = state["body/legacy"]?.let(RigLegacyBindings::strength) ?: 1f
            val headStrength = (state[RigLegacyBindings.FITTED_FACE] ?: state["face/legacy"])?.let(RigLegacyBindings::strength) ?: 1f
            val kept = state.keys.flatMapTo(HashSet()) { RigLegacyBindings.spec(it).parameters }
            val byArea = instances.filter { RigLegacyBindings.spec(it.type).isBase }.associateBy { RigLegacyBindings.spec(it.type).area }
            val recipe = if (instances.none { !it.type.endsWith("/legacy") }) RigRecipe.Legacy else RigRecipe(true,
                bases = RigModules.areas.associate { area ->
                    val chosen = byArea[area.id]
                    val base = chosen?.type?.takeUnless { it == RigLegacyBindings.FITTED_FACE } ?: area.legacy.id
                    area.id to BaseChoice(base, chosen?.version ?: area.legacy.version, chosen?.let(RigLegacyBindings::values).orEmpty())
                }, modules = RigModules.modules.associate { module ->
                    val chosen = state[module.id] ?: state[RigLegacyBindings.FITTED_FACE]?.takeIf { module.id == RigModules.FACE_DEPTH_TURN.id }
                    module.id to ModuleChoice(chosen != null, chosen?.version ?: module.version, chosen?.let(RigLegacyBindings::values).orEmpty())
                })
            val outline = state[RigModules.MOUTH_OUTLINE.id]
            val corrections = io.github.psd2live.project.WorkspaceSettingsCodec.decodeRecipe(scaffold["corrections"])
            var bindingInput: (() -> PuppetModel)? = null
            val native = generated(bodyStrength, tuning, headStrength,
                recipe.copy(moves = corrections.moves, eyeParts = corrections.eyeParts), outline,
                scaffold["featureDisplacement"]?.jsonPrimitive?.boolean ?: false,
                scaffold["frontHairSway"]?.jsonPrimitive?.boolean ?: true, scaffold["backHairSway"]?.jsonPrimitive?.boolean ?: true,
                capture = { bindingInput = it })
            val chains = native.generatedChains.filter { chain -> instances.any { chain.kind in RigModifierChains.kinds(it.type) } }
                .map { RigModifierChains.Binding(it) }
            return finishTemplate(native.puppet, chains, kept, byArea.keys, bindingInput)
    }

    private fun finishTemplate(input: PuppetModel, chains: List<RigModifierChains.Binding>, kept: Set<ParameterId>,
                               areas: Set<String>, bindingInput: (() -> PuppetModel)?): Template {
            val dynamic = chains.flatMap { it.native.parameters }
            val model = RigModifierChains.normalize(restricted(input, kept + dynamic), chains)
            val clips = RigOrdinaryMaterials.clipping(analysis.source, primaryByLayer.entries.associate { it.value to it.key })
            val tags = analysis.layers.associate { it.source.id.raw to it.semantic.tag }
            val opacity = analysis.layers.associate { it.source.id.raw to it.source.opacity }
            return Template(model.copy(drawables = model.drawables.map { mesh ->
                val layer = layersByMesh[mesh.id.raw]
                val eye = tags[layer] in setOf(SemanticTag.IRIDES, SemanticTag.EYEWHITE, SemanticTag.EYELASH, SemanticTag.EYE_CLOSE)
                val mouth = tags[layer] in CharacterAnalyzer.MOUTH_COMPONENT_TAGS + setOf(SemanticTag.MOUTH, SemanticTag.MOUTH_OPEN)
                val active = eye && "eye" in areas || mouth && "mouth" in areas
                mesh.copy(maskedBy = if ((eye || mouth) && !active) listOfNotNull(clips[layer]) else mesh.maskedBy,
                    channelGrids = if ((eye || mouth) && !active) ChannelGrids.Empty else mesh.channelGrids,
                    opacity = if ((eye || mouth) && !active) opacity[layer] ?: mesh.opacity else mesh.opacity)
            }), chains, bindingInput)
    }

    private fun moodTemplate(instances: List<RigModifierInstance>, scaffold: JsonObject, state: Map<String, RigModifierInstance>): Template? {
        // Source tracks using these names participate in standard parameter resolution; retain that compatibility path.
        if (expressionLayers.values.any { it in io.github.psd2live.algorithms.binding.MoodChannels.parameters }) return null
        val previous = template(instances.dropLast(1), scaffold)
        val input = previous.bindingInput?.invoke() ?: return null
        val modules = listOf(RigModules.EXPRESSION_CHEEK, RigModules.EXPRESSION_GLOOM)
        val subject = RecipeSubject.of(analysis)
        val active = modules.filter { it.id in state && it.applies(subject) }
        val additions = active.flatMap { it.defines }.filter { definition -> input.parameters.none { it.id.raw == definition.id } }
            .map { Parameter(ParameterId(it.id), io.github.psd2live.i18n.tr(it.nameKey), it.min, it.max, it.default) }
        val order = RigModules.modules.flatMap { it.defines }.map { ParameterId(it.id) }.withIndex().associate { it.value to it.index }
        val dynamic = previous.chains.flatMapTo(HashSet()) { it.native.parameters }
        val layers = analysis.layers.associateBy { it.source.id.raw }
        val cheek = active.any { it == RigModules.EXPRESSION_CHEEK }
        val gloom = active.any { it == RigModules.EXPRESSION_GLOOM }
        val influences = input.drawables.mapNotNull { mesh ->
            val layer = layers[layersByMesh[mesh.id.raw]] ?: return@mapNotNull null
            val tag = layer.semantic.tag; val role = layer.semantic.role
            val skin = tag == SemanticTag.FACE || tag == SemanticTag.NOSE || tag == SemanticTag.EARS || tag == SemanticTag.FACE_DETAIL && role != PartRole.HIGHLIGHT
            val warmth = when {
                tag == SemanticTag.FACE_DETAIL && role == PartRole.BLUSH -> .3f
                tag == SemanticTag.FACE || tag == SemanticTag.NOSE -> .06f
                tag == SemanticTag.EARS -> .05f
                else -> 0f
            } * (state["expression.cheek"]?.let { RigLegacyBindings.values(it)["strength"] } ?: 100f) / 100f
            val darkness = (if (skin) 1f else 0f) * (state["expression.gloom"]?.let { RigLegacyBindings.values(it)["strength"] } ?: 100f) / 100f
            mesh.id to io.github.psd2live.algorithms.binding.MoodBinding.Influence(warmth, darkness, role == PartRole.HIGHLIGHT)
        }.toMap()
        val evaluated = io.github.psd2live.algorithms.binding.MoodBinding(carrierName = { head, meshName ->
            io.github.psd2live.i18n.tr(if (head) "model.deformer.meshTurn" else "model.deformer.meshShape", meshName)
        }).evaluate(input, additions, order, dynamic, influences, cheek, gloom)
        RigBuildProfile.count("modifier local color templates")
        val kept = state.keys.flatMapTo(HashSet()) { RigLegacyBindings.spec(it).parameters }
        val areas = instances.filter { RigLegacyBindings.spec(it.type).isBase }.mapTo(HashSet()) { RigLegacyBindings.spec(it.type).area }
        return finishTemplate(evaluated.model, previous.chains, kept, areas) { evaluated.source }
    }

    fun transition(instance: RigModifierInstance, target: ModifierTarget, checkpoint: () -> Unit): Pair<PuppetModel, PuppetModel> {
        checkpoint()
        val (before, after) = transitions.getOrPut(instance.id to target) { transitionProjections(instance, target) }
        checkpoint()
        return before.model to after.model
    }

    fun chains(instance: RigModifierInstance, checkpoint: () -> Unit = {}): List<RigModifierChains.Owned> =
        instance.targets.flatMap { target ->
            transition(instance, target, checkpoint)
            transitions.getValue(instance.id to target).second.chains.filter { it.owner == instance.id }
        }.distinctBy { it.binding.key }

    private fun relevantBefore(instance: RigModifierInstance, target: ModifierTarget): List<RigModifierInstance> {
        val provider = provider(instance)
        val spec = RigLegacyBindings.spec(instance.type)
        val appliedIds = stack.appliedCapabilities.mapNotNullTo(HashSet()) { it.id }
        val preceding = activeBefore(instance).filter { previous ->
            // Reapplying a base to materialized content treats the earlier base as authored residuals.
            !(spec.isBase && previous.id in appliedIds &&
                RigLegacyBindings.find(previous.type)?.let { it.isBase && it.area == spec.area } == true)
        }
        return preceding.filter { previous -> RigLegacyBindings.find(previous.type)?.let { !it.expressionToggles && !it.seeThrough } == true && target in previous.targets &&
            previous.config["scaffold"]?.jsonPrimitive?.content == provider.id }
    }

    private fun transitionProjections(instance: RigModifierInstance, target: ModifierTarget): Pair<RigModifierChains.Projection, RigModifierChains.Projection> {
        val relevant = relevantBefore(instance, target)
        fun projected(items: List<RigModifierInstance>): RigModifierChains.Projection {
            val template = template(items, provider(instance).config)
            val model = forTarget(template.model, target)
            return RigModifierChains.projection(model, target, items, template.chains,
                chainProjections.getOrPut(model) { HashMap() }, ::restricted)
        }
        return projected(relevant) to projected(relevant + instance)
    }

    private val activePrefixes = HashMap<String, List<RigModifierInstance>>()

    /** Mirror ordered dependency eligibility, so a paused module cannot leak into a later source transition. */
    private fun activeBefore(instance: RigModifierInstance): List<RigModifierInstance> = activePrefixes.getOrPut(instance.id) {
        val available = stack.appliedCapabilities.toMutableList()
        val active = stack.appliedCapabilities.filter { it.id != null }.mapTo(ArrayList()) {
            RigModifierInstance(requireNotNull(it.id), it.type, it.version, it.targets, config = it.config, outputAliases = it.outputAliases)
        }
        for (previous in stack.instances.take(stack.index(instance.id))) {
            if (!previous.enabled) continue
            val spec = RigLegacyBindings.find(previous.type)
            val providers = if (spec == null) emptyMap() else RigLegacyBindings.providers(previous)
            val missing = spec?.requires.orEmpty().any { type -> previous.targets.any { target ->
                available.none { RigModifierAlgorithms.registry.provides(it.type, type) && target in it.targets && (providers[type] == null || it.id == providers[type]) }
            } }
            if (missing) continue
            active += previous
            available += ModifierCapability(previous.type, previous.version, previous.targets, previous.id, previous.config)
        }
        active
    }

    private val bindingComparison = io.github.psd2live.algorithms.binding.BindingComparison()

    /** Preserve authored geometry byte-for-byte when a native transition only changes clipping (or nothing). */
    fun onlyMasksChange(before: PuppetModel, after: PuppetModel, target: ModifierTarget, branch: Set<DeformerId>): Boolean =
        bindingComparison.onlyMasksChange(before, after, DrawableId(target.id), branch)

    private val deformerOrigins = HashMap<Pair<String, String>, Map<DeformerId, DeformerId>>()

    /** A native carrier is named by the first persisted instance that creates it, including applied providers. */
    fun deformerIds(instance: RigModifierInstance, target: ModifierTarget, checkpoint: () -> Unit): Map<DeformerId, DeformerId> =
        deformerOrigins.getOrPut(instance.id to target.id) {
            val provider = provider(instance)
            val relevant = (activeBefore(instance) + instance).filter { previous ->
                RigLegacyBindings.find(previous.type)?.let { !it.expressionToggles && !it.seeThrough } == true &&
                target in previous.targets && previous.config["scaffold"]?.jsonPrimitive?.content == provider.id }
            val preceding = relevant.dropLast(1).lastOrNull()?.takeIf { entry -> stack.instances.any { it.id == entry.id } }
            val ids = preceding?.let { LinkedHashMap(deformerIds(it, target, checkpoint)) }
                ?: scaffoldFor(provider, target, checkpoint).deformers.associateTo(LinkedHashMap()) {
                    it.id to DeformerId(RigSourceModifiers.deformerId(provider, target.id, it.id.raw))
                }
            checkpoint()
            for (end in (if (preceding == null) 1 else relevant.size)..relevant.size) {
                val item = relevant[end - 1]
                for (deformer in template(relevant.take(end), provider.config).model.deformers) ids.putIfAbsent(deformer.id,
                    DeformerId(RigSourceModifiers.deformerId(item, target.id, deformer.id.raw, shared = provider.version >= 2)))
            }
            ids
        }

    private val partOrigins = HashMap<Pair<String, String>, Map<PartId, PartId>>()

    fun requiredParts(model: PuppetModel, target: ModifierTarget): Set<PartId> {
        val parents = model.parts.flatMap { part -> part.children.filterIsInstance<OrgChild.Part>().map { it.id to part.id } }.toMap()
        val deformers = model.deformers.associateBy { it.id }
        val seeds = ArrayList<PartId>()
        model.parts.singleOrNull { OrgChild.Drawable(DrawableId(target.id)) in it.children }?.id?.let(seeds::add)
        var parent = model.drawables.single { it.id.raw == target.id }.parentDeformerId
        val visited = HashSet<DeformerId>()
        while (parent != null) {
            require(visited.add(parent)) { "Cyclic binding branch" }
            val deformer = deformers.getValue(parent)
            deformer.partId?.let(seeds::add); parent = deformer.parent
        }
        return linkedSetOf<PartId>().apply {
            for (seed in seeds) {
                var part: PartId? = seed
                val seen = HashSet<PartId>()
                while (part != null) { require(seen.add(part)) { "Cyclic binding parts" }; add(part); part = parents[part] }
            }
        }
    }

    fun partIds(instance: RigModifierInstance, target: ModifierTarget, checkpoint: () -> Unit): Map<PartId, PartId> =
        partOrigins.getOrPut(instance.id to target.id) {
            val provider = provider(instance)
            val relevant = (activeBefore(instance) + instance).filter { previous ->
                RigLegacyBindings.find(previous.type)?.let { !it.expressionToggles && !it.seeThrough } == true &&
                target in previous.targets && previous.config["scaffold"]?.jsonPrimitive?.content == provider.id }
            val preceding = relevant.dropLast(1).lastOrNull()?.takeIf { entry -> stack.instances.any { it.id == entry.id } }
            val ids = preceding?.let { LinkedHashMap(partIds(it, target, checkpoint)) }
                ?: requiredParts(scaffoldFor(provider, target, checkpoint), target).associateTo(LinkedHashMap()) {
                    it to PartId(RigSourceModifiers.partId(provider, target.id, it.raw))
                }
            checkpoint()
            for (end in (if (preceding == null) 1 else relevant.size)..relevant.size) {
                val item = relevant[end - 1]
                for (part in requiredParts(forTarget(template(relevant.take(end), provider.config).model, target), target)) ids.putIfAbsent(part,
                    PartId(RigSourceModifiers.partId(item, target.id, part.raw, shared = provider.version >= 2)))
            }
            ids
        }

    fun resolveParameter(instance: RigModifierInstance, before: io.github.psd2live.format.model.Parameter,
                         declared: io.github.psd2live.format.model.Parameter): io.github.psd2live.format.model.Parameter? {
        if (before.id !in setOf(StandardParameters.EYE_L_OPEN.raw, StandardParameters.EYE_R_OPEN.raw) ||
            before.min != declared.min || before.default != declared.default || before.blend != declared.blend ||
            before.repeat != declared.repeat || before.keys != declared.keys) return null
        val widening = instance.type == "eye.wide"
        val inherited = activeBefore(instance).any { it.type == "eye.wide" }
        if (!widening && (!inherited || before.max < declared.max)) return null
        if (before.max < 1f || declared.max < 1f) return null
        return before.copy(max = maxOf(before.max, declared.max))
    }

    fun parameters(instance: RigModifierInstance): List<io.github.psd2live.format.model.Parameter> {
        val found = LinkedHashMap<String, io.github.psd2live.format.model.Parameter>()
        val definitions = instance.targets.flatMap { target ->
            val desired = transition(instance, target) {}.second
            parameterDefinitions.getOrPut(desired) { PuppetIr.parametersToIr(desired) }
        }
        for (parameter in definitions) {
            val previous = found[parameter.id]
            require(previous == null || previous.min == parameter.min && previous.max == parameter.max && previous.default == parameter.default &&
                previous.blend == parameter.blend && previous.repeat == parameter.repeat && previous.keys == parameter.keys) {
                "${instance.id}: incompatible shared parameter ${parameter.id} across targets"
            }
            found.putIfAbsent(parameter.id, parameter)
        }
        return found.values.toList()
    }

    private fun restricted(input: PuppetModel, kept: Set<ParameterId>): PuppetModel {
        // The source mouth is drawn open. Parameter default 0 is the algorithm's closed pose, not the material
        // state before that algorithm exists. Removing this axis must retain its source pose at 1.
        var model = input.copy(parameters = input.parameters.map { parameter ->
            if (parameter.id == StandardParameters.MOUTH_OPEN && parameter.id !in kept) parameter.copy(default = 1f.coerceIn(parameter.min, parameter.max))
            else parameter
        })
        val defaults = model.parameters.associate { it.id to it.default }
        val forms = model.deformers.associate { deformer -> deformer.id to when (deformer) {
            is Deformer.Warp -> deformer.copy(geometryGrid = restrict(deformer.geometryGrid, kept, defaults, WarpLatticeInterpolator))
            is Deformer.Rotation -> deformer.copy(geometryGrid = restrict(deformer.geometryGrid, kept, defaults, RotationPivotInterpolator))
        } }
        // Parameter deletion lifts channel constants but intentionally unkeys the last geometry axis. A Warp has
        // no separate rest lattice, so keep its sampled neutral form explicitly after removing binding parameters.
        val meshes = model.drawables.associate { it.id to restrict(it.geometryGrid, kept, defaults, MeshDeltaInterpolator) }
        for (parameter in model.parameters.toList().filter { it.id !in kept }) model = model.withParameterDeleted(parameter.id)
        return model.copy(drawables = model.drawables.map { it.copy(geometryGrid = meshes[it.id]) },
            deformers = model.deformers.map { deformer -> when (deformer) {
            is Deformer.Warp -> deformer.copy(geometryGrid = (forms.getValue(deformer.id) as Deformer.Warp).geometryGrid)
            is Deformer.Rotation -> deformer.copy(geometryGrid = (forms.getValue(deformer.id) as Deformer.Rotation).geometryGrid)
        } })
    }

    private fun scaffold(instance: RigModifierInstance, checkpoint: () -> Unit): PuppetModel {
        checkpoint()
        val result = template(emptyList(), instance.config).model
        checkpoint()
        return result
    }

    /** Only exact, unedited source geometry can bypass numerical inverse mapping. */
    fun originalMaterial(actual: Drawable, desired: Drawable): Boolean {
        val original = materialBase.drawables.singleOrNull { it.id == actual.id } ?: return false
        val mesh = actual.mesh ?: return false
        val base = original.mesh ?: return false
        val native = desired.mesh ?: return false
        return actual.parentDeformerId == null && actual.geometryGrid == null && actual.blendShapes.isEmpty() &&
            mesh.positions.contentEquals(base.positions) && mesh.uvs.contentEquals(base.uvs) && mesh.indices.contentEquals(base.indices) &&
            mesh.uvs.contentEquals(native.uvs) && mesh.indices.contentEquals(native.indices)
    }

    fun scaffoldFor(instance: RigModifierInstance, target: ModifierTarget, checkpoint: () -> Unit) = forTarget(scaffold(instance, checkpoint), target)

    private fun forTarget(model: PuppetModel, target: ModifierTarget): PuppetModel {
        val canonical = primaryByLayer.getValue(layersByMesh.getValue(target.id))
        if (canonical == target.id) return model
        return projections.getOrPut(model) { HashMap() }.getOrPut(target.id) {
            RigIdRemap(drawables = mapOf(DrawableId(canonical) to DrawableId(target.id))).model(model)
        }
    }

    fun hasMesh(id: String) = id in layersByMesh
    fun hasLayer(id: String) = analysis.layers.any { it.source.id.raw == id }

    fun provider(instance: RigModifierInstance): RigModifierInstance {
        val id = instance.config.getValue("scaffold").jsonPrimitive.content
        val preceding = stack.instances.take(stack.index(instance.id)).singleOrNull { it.id == id && it.enabled && it.type == RigSourceModifiers.SCAFFOLD }
        val applied = stack.appliedCapabilities.singleOrNull { it.id == id && it.type == RigSourceModifiers.SCAFFOLD }
        val targets = preceding?.targets ?: requireNotNull(applied) { "${instance.id}: binding scaffold $id must precede this modifier" }.targets
        require(instance.targets.all { it in targets }) { "${instance.id}: scaffold $id does not cover all captured targets" }
        val aliases = preceding?.outputAliases ?: requireNotNull(applied).outputAliases
        require((preceding?.version ?: applied?.version ?: 1) < 2 && aliases.keys.none { it.startsWith("shared/deformer:") } || instance.targets.toSet() == targets.toSet()) {
            "${instance.id}: shared scaffold $id requires its complete target scope"
        }
        return preceding ?: RigModifierInstance(id, RigSourceModifiers.SCAFFOLD, requireNotNull(applied).version, targets,
            config = applied.config, outputAliases = applied.outputAliases)
    }

    private fun <F> restrict(grid: KeyformGrid<F>?, kept: Set<ParameterId>, defaults: Map<ParameterId, Float>, interpolator: FormInterpolator<F>): KeyformGrid<F>? {
        var current = grid ?: return null
        while (current.axes.any { it.parameterId !in kept }) {
            val axis = current.axes.first { it.parameterId !in kept }
            val value = (defaults[axis.parameterId] ?: 0f).coerceIn(axis.keys.first(), axis.keys.last())
            current = current.withKeyInserted(axis.parameterId, value, interpolator)
            if (current.axes.size == 1) {
                val index = current.axes.single().keys.indices.minBy { kotlin.math.abs(current.axes.single().keys[it] - value) }
                val form = current.cells.single { it.coordinate.single() == index }.form
                return KeyformGrid(emptyList(), listOf(KeyformCell(IntArray(0), form)))
            }
            current = requireNotNull(current.withAxisCollapsed(axis.parameterId, value))
        }
        return current
    }

    companion object {
        fun of(model: RigPreviewModel) = RigModifierSource(model.modifierAuthorAnalysis ?: model.analysis, model.modifierAuthorAtlas ?: model.atlas,
            model.config, model.authored.rig.layerIdByDrawableId, model.authored.rig.copiedDrawableIds, model.baseRig.puppet)
    }
}

/** Source-aware parts of the binding algorithms. The scaffold is explicit and contains no motion. */
internal object RigSourceModifiers {
    const val SCAFFOLD = "rig.scaffold"

    fun definitions(source: RigModifierSource?): List<RigModifierDefinition> = listOf(1, 2).map { version ->
        RigModifierDefinition(SCAFFOLD, version, exclusiveFamily = SCAFFOLD, appliedConfig = { it.config },
            validate = { _, instance ->
                require(instance.config.keys.all { it in setOf("featureDisplacement", "corrections", "frontHairSway", "backHairSway") }) { "Unknown binding scaffold configuration" }
                instance.config["corrections"]?.jsonObject?.let { corrections ->
                    require(corrections.keys.all { it in setOf("moves", "eyeParts") }) { "Invalid binding scaffold corrections" }
                    io.github.psd2live.project.WorkspaceSettingsCodec.mergeRecipe(RigRecipe.Modern, corrections)
                }
                for (key in listOf("featureDisplacement", "frontHairSway", "backHairSway")) instance.config[key]?.jsonPrimitive?.boolean
                require(instance.targets.all { it.kind == "mesh" }) { "A binding scaffold targets source meshes" }
                val input = requireNotNull(source) { "Binding modifiers require the captured source artwork" }
                require(instance.targets.all { input.hasMesh(it.id) }) { "A binding scaffold target has no source layer" }
            }, composableKinds = setOf("mesh", "part", "deformer", "hierarchy"),
            evaluate = { rig, instance, checkpoint -> scaffold(rig, instance, requireNotNull(source), checkpoint) })
    } + RigLegacyBindings.specs.map { RigLegacyBindings.definition(it, source) }

    /** Version 2 retains the source hierarchy; version 1 keeps persisted per-mesh identities replayable. */
    fun deformerId(instance: RigModifierInstance, mesh: String, canonical: String, shared: Boolean = instance.type == SCAFFOLD && instance.version >= 2) =
        instance.outputAliases["shared/deformer:$canonical"] ?: instance.outputId(if (shared) "shared/deformer:$canonical" else "$mesh/deformer:$canonical")
    fun partId(instance: RigModifierInstance, mesh: String, canonical: String, shared: Boolean = instance.type == SCAFFOLD && instance.version >= 2) =
        instance.outputAliases["shared/part:$canonical"] ?: instance.outputId(if (shared) "shared/part:$canonical" else "$mesh/part:$canonical")

    private fun scaffold(rig: RigIR, instance: RigModifierInstance, source: RigModifierSource, checkpoint: () -> Unit): RigIR {
        var current = PuppetIr.toPuppet(rig)
        if (instance.version >= 2) {
            val parents = instance.targets.map { target -> current.drawables.single { it.id.raw == target.id }.parentDeformerId }.distinct()
            require(parents.size == 1) { "A shared binding hierarchy requires targets in the same authored frame" }
        }
        val emittedDeformers = HashSet<DeformerId>()
        val emittedParts = HashSet<PartId>()
        val sharedTemplate = if (instance.outputAliases.keys.any { it.startsWith("shared/deformer:") })
            source.scaffoldFor(instance, instance.targets.first(), checkpoint) else null
        for (target in instance.targets) {
            checkpoint()
            val template = source.scaffoldFor(instance, target, checkpoint)
            val deformers = template.deformers.associateBy { it.id }
            val partParents = template.parts.flatMap { part -> part.children.filterIsInstance<OrgChild.Part>().map { it.id to part.id } }.toMap()
            val meshId = DrawableId(target.id)
            val actual = current.drawables.single { it.id == meshId }
            val upstreamParent = actual.parentDeformerId
            val desired = requireNotNull(template.drawables.singleOrNull { it.id == meshId }) { "No binding scaffold for ${target.id}" }
            val branch = LinkedHashSet<DeformerId>()
            var ancestor = desired.parentDeformerId
            while (ancestor != null) {
                require(branch.add(ancestor)) { "Cyclic source binding scaffold" }
                ancestor = deformers.getValue(ancestor).parent
            }
            require(branch.isNotEmpty()) { "The source layer has no binding hierarchy: ${target.id}" }
            val orgParent = template.parts.singleOrNull { OrgChild.Drawable(meshId) in it.children }?.id
            val partBranch = LinkedHashSet<PartId>()
            for (start in branch.mapNotNull { deformers.getValue(it).partId } + listOfNotNull(orgParent)) {
                var part: PartId? = start
                val seen = HashSet<PartId>()
                while (part != null) {
                    require(seen.add(part)) { "Cyclic source part hierarchy" }
                    partBranch += part
                    part = partParents[part]
                }
            }
            if (sharedTemplate != null) partBranch += template.parts.filter {
                "shared/part:${it.id.raw}" in instance.outputAliases
            }.map { it.id }
            val remap = RigIdRemap(
                deformers = branch.associateWith { DeformerId(deformerId(instance, target.id, it.raw)) },
                parts = partBranch.associateWith { PartId(partId(instance, target.id, it.raw)) })
            val clonedDeformers = template.deformers.filter { it.id in branch }.map { original ->
                val cloned = remap.deformer(original)
                if (original.parent != null || upstreamParent == null) cloned else {
                    require(cloned is Deformer.Warp) { "The binding scaffold root must be a Warp" }
                    val grid = requireNotNull(cloned.geometryGrid)
                    require(grid.axes.isEmpty() && grid.cells.size == 1) { "The binding scaffold must be static" }
                    val points = grid.cells.single().form.controlPoints
                    val carried = pointsInParent(current, actual, points, upstreamParent, instance.outputId("$meshId/frame"))
                    cloned.copy(parent = upstreamParent, geometryGrid = KeyformGrid(emptyList(),
                        listOf(KeyformCell(IntArray(0), WarpLatticeForm(carried)))))
                }
            }
            val clonedParts = template.parts.filter { it.id in partBranch }.map { original ->
                remap.part(original.copy(children = original.children.filter { it is OrgChild.Part && it.id in partBranch }))
            }
            require(clonedDeformers.all { added -> current.deformers.none { it.id == added.id } ||
                added.id in emittedDeformers && current.deformers.single { it.id == added.id } == added }) { "Binding scaffold output ID collision: ${instance.id}" }
            require(clonedParts.all { added -> current.parts.none { it.id == added.id } || added.id in emittedParts &&
                current.parts.single { it.id == added.id }.copy(children = emptyList()) == added.copy(children = emptyList()) }) { "Binding scaffold part ID collision: ${instance.id}" }
            val newDeformers = clonedDeformers.filter { emittedDeformers.add(it.id) }
            val newParts = clonedParts.filter { emittedParts.add(it.id) }
            val sharedParts = clonedParts.associateBy { it.id }
            val parts = current.parts.map { part -> sharedParts[part.id]?.let { next ->
                part.copy(children = (part.children + next.children).distinct())
            } ?: part }
            current = current.copy(deformers = current.deformers + newDeformers, parts = parts + newParts,
                rootChildren = (current.rootChildren + partBranch.filter { partParents[it] !in partBranch }.map { OrgChild.Part(remap.part(it)) }).distinct())
                .withDerivedRenderRoot()
            val destination = remap.deformer(requireNotNull(desired.parentDeformerId))
            current = if (source.originalMaterial(actual, desired)) current.copy(drawables = current.drawables.map {
                if (it.id == meshId) it.copy(parentDeformerId = destination, mesh = desired.mesh, geometryGrid = desired.geometryGrid) else it
            }) else RigStructureEdits.apply(current, listOf(buildJsonObject {
                put("action", "bind"); put("kind", "mesh"); put("id", target.id); put("parent_id", destination.raw); put("space", "canvas")
            }))
            if (orgParent != null) current = RigStructureEdits.apply(current, listOf(buildJsonObject {
                put("action", "move"); put("kind", "mesh"); put("id", target.id); put("parent_id", remap.part(orgParent).raw)
            }))
        }
        if (sharedTemplate != null) {
            val mesh = instance.targets.first().id
            val remap = RigIdRemap(parts = sharedTemplate.parts.associate {
                it.id to PartId(partId(instance, mesh, it.id.raw))
            })
            val desiredParts = sharedTemplate.parts.map(remap::part).associateBy { it.id }
            val deformerOrder = instance.targets.flatMap { target -> sharedTemplate.deformers.mapIndexed { index, value ->
                DeformerId(deformerId(instance, target.id, value.id.raw)) to index
            } }.toMap()
            fun ordered(children: List<OrgChild>, desired: List<OrgChild>) =
                children.filter { it !in desired } + desired.filter { it in children }
            current = current.copy(
                deformers = current.deformers.filter { it.id !in emittedDeformers } +
                    current.deformers.filter { it.id in emittedDeformers }.sortedBy { deformerOrder.getValue(it.id) },
                parts = current.parts.map { part -> desiredParts[part.id]?.let {
                    part.copy(children = ordered(part.children, it.children))
                } ?: part },
                rootChildren = ordered(current.rootChildren, sharedTemplate.rootChildren.map(remap::child)),
            )
        }
        checkpoint()
        return rig.withPuppet(current.withDerivedRenderRoot())
    }

    /** Use the same canvas-space transfer as ordinary DCC rebinding, including non-affine Warp parents. */
    internal fun pointsInParent(model: PuppetModel, sample: Drawable, points: FloatArray, parent: DeformerId, scratch: String): FloatArray {
        val id = DrawableId(scratch)
        require(model.drawables.none { it.id == id }) { "Binding frame ID collision" }
        val drawable = sample.copy(id = id, parentDeformerId = null,
            mesh = DrawableMesh(points, FloatArray(points.size), IntArray(0)), geometryGrid = null,
            channelGrids = ChannelGrids.Empty, blendShapes = emptyList())
        val before = model.copy(drawables = model.drawables + drawable)
        val after = before.copy(drawables = before.drawables.map { if (it.id == id) it.copy(parentDeformerId = parent) else it })
        return RigStructureEdits.carriedToParent(before, after, id).drawables.single { it.id == id }.mesh!!.positions
    }
}
