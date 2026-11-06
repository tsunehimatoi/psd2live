package io.github.psd2live.core.modifier

import io.github.psd2live.core.*
import io.github.psd2live.core.recipe.*
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.*
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
    fun resolveRasters(input: RigIR, outputs: List<ModifierRasterOutput>, checkpoint: () -> Unit): RigIR =
        RigModifierRasterAssets.attach(input, outputs, config, checkpoint)

    val physicsOrder: List<String> get() = config.rigEdits.physicsOrder
    private val stack by lazy { RigModifierStack.fromJson(requireNotNull(config.rigEdits.modifierStack)) }
    private val parameterDefinitions = java.util.IdentityHashMap<PuppetModel, List<io.github.psd2live.format.model.Parameter>>()
    private val primaryByLayer = layersByMesh.entries.sortedBy { it.key in copiedMeshes }.groupBy { it.value }.mapValues { it.value.first().key }
    private val projections = java.util.IdentityHashMap<PuppetModel, MutableMap<String, PuppetModel>>()
    private fun generated(strength: Float = 1f, tuning: RigTuning = RigTuning(), headStrength: Float = 1f,
                          recipe: RigRecipe = RigRecipe.Legacy, outline: RigModifierInstance? = null,
                          featureDisplacement: Boolean = false, frontHairSway: Boolean = true, backHairSway: Boolean = true, neutralScaffold: Boolean = false, capture: ((() -> PuppetModel) -> Unit)? = null): BuiltRig {
        val clean = sourceConfig()
        val native = clean.copy(bodyStrength = strength, headTurnStrength = headStrength, rigTuning = tuning, recipe = recipe,
            featureDisplacementEnabled = featureDisplacement, mouthOutlineEnabled = outline != null,
            hairSimulationFront = !frontHairSway, hairSimulationBack = !backHairSway,
            mouthThickness = outline?.let { RigLegacyBindings.values(it)["thickness"] } ?: 1.5f,
            mouthColor = outline?.let(RigLegacyBindings::outlineColor))
        if (outline == null) return if (neutralScaffold) RigBuilder.buildNeutralScaffold(analysis, atlas, native, meshCache, capture)
            else RigBuilder.build(analysis, atlas, native, meshCache, capture)
        val prepared = MouthLipLayers.prepare(analysis, native, meshCache)
        val lips = prepared.layers.filter { it.source is MouthLipLayer }
        val pack = { RigBuildProfile.stage("source artwork: outline atlas") { RigModifierRasterAssets.append(atlas, lips, native) } }
        val packed = if (RigStageCache.enabled) meshCache.stages.get("mouth outline atlas", 16,
            listOf(atlas, lips, native.effectiveAtlasBudget()), pack) else pack()
        return RigBuilder.build(prepared, packed, native, meshCache, capture)
    }

    private fun sourceConfig() = RigModifierPipeline.ordinaryConfig(config).copy(meshOnly = false, generateDeformers = true,
        deferRig = false, rigPendingLayerIds = emptySet(), parentOverrides = emptyMap(),
        rigEdits = RigEditOverlay(splitDrawableIds = primaryByLayer))

    private data class Template(val model: PuppetModel, val chains: List<RigModifierChains.Binding>, val bindingInput: (() -> PuppetModel)? = null)
    private val templates = HashMap<Map<String, JsonObject>, Template>()
    private val transitions = HashMap<Pair<String, ModifierTarget>, Pair<RigModifierChains.Projection, RigModifierChains.Projection>>()
    private val chainProjections = java.util.IdentityHashMap<PuppetModel, MutableMap<List<RigModifierChains.Owned>, RigModifierChains.Projection>>()

    fun skinClipContext(instance: RigModifierInstance, includeEarlier: Boolean = true): ModifierAlgorithmContext {
        val clips = RigOrdinaryMaterials.clipping(analysis.source, primaryByLayer.entries.associate { it.value to it.key })
        fun bounds(layer: ClassifiedLayer) = JsonArray(listOf(layer.bounds.left, layer.bounds.top, layer.bounds.right, layer.bounds.bottom).map(::JsonPrimitive))
        val layers = analysis.layers.associateBy { it.source.id.raw }
        return ModifierAlgorithmContext(buildJsonObject {
            put("schema", "skin-clip/1")
            putJsonArray("faces") { analysis.layers.sortedBy { it.source.order }.filter { it.semantic.tag == SemanticTag.FACE }.forEach { layer ->
                primaryByLayer[layer.source.id.raw]?.let { id -> add(buildJsonObject { put("id", id); put("bounds", bounds(layer)) }) }
            } }
            putJsonObject("overlays") { for ((mesh, layerId) in layersByMesh) {
                val layer = layers[layerId] ?: continue
                if (layerId !in clips && (layer.semantic.tag == SemanticTag.FACE_DETAIL ||
                    layer.semantic.tag == SemanticTag.FRONT_HAIR && layer.semantic.role == PartRole.SHADOW)) put(mesh, bounds(layer))
            } }
            put("already_active", JsonArray(if (!includeEarlier) emptyList() else instance.targets.filter { target ->
                relevantBefore(instance, target).any { it.type == "face.skin-clip" }
            }.map { JsonPrimitive(it.id) }))
        })
    }

    fun skinClip(rig: RigIR, instance: RigModifierInstance, checkpoint: () -> Unit): RigIR =
        io.github.psd2live.algorithms.binding.SkinClipAlgorithm().evaluate(rig, instance, skinClipContext(instance), checkpoint)

    private val seeThroughCandidates by lazy {
        val layers = analysis.layers.associateBy { it.source.id.raw }
        // Coverage depends only on source pixels and semantics. Expand against the live model in the plugin.
        val sourceMeshes = layersByMesh.keys.map { Drawable(DrawableId(it), it, null, BlendMode.Normal, emptyList(), null, null) }
        SeeThroughLayers.candidates(materialBase.copy(drawables = sourceMeshes), config.alphaThreshold) { layers[layersByMesh[it.raw]] }
    }

    fun seeThroughContext(instance: RigModifierInstance): ModifierAlgorithmContext = ModifierAlgorithmContext(buildJsonObject {
        put("schema", "see-through/1")
        put("eyes", instance.type == "eye.see-through")
        put("name_pattern", io.github.psd2live.i18n.tr("model.drawable.seeThrough", "{meshName}"))
        put("draw_order_step", config.drawOrderStep)
        put("inverted_masks", config.runtimeTarget.supports(RuntimeFeature.ReversedMask))
        putJsonArray("candidates") { seeThroughCandidates.forEach { candidate -> add(buildJsonObject {
            put("original", candidate.original); put("eyes", candidate.eyes)
            put("bangs", JsonArray(candidate.bangs.map(::JsonPrimitive)))
        }) } }
        put("earlier_owners", Json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(RigModifierInstance.serializer()),
            activeBefore(instance).filter { RigLegacyBindings.find(it.type)?.seeThrough == true }))
    })

    fun seeThrough(rig: RigIR, instance: RigModifierInstance, checkpoint: () -> Unit): RigIR =
        io.github.psd2live.algorithms.binding.SeeThroughAlgorithm().evaluate(rig, instance, seeThroughContext(instance), checkpoint)

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
        // Standard-name collisions need fitted parameter ranges, not meshes or an assembled model.
        val parameters = if (catalog.parameters.any { custom -> StandardParameters.all.any { it.id == custom.id } }) {
            val recipe = RigRecipe(true, modules = RigModules.modules.associate {
                it.id to ModuleChoice(it == RigModules.EXPRESSION_TOGGLES)
            })
            val native = sourceConfig().copy(bodyStrength = 1f, headTurnStrength = 1f, rigTuning = RigTuning(), recipe = recipe,
                featureDisplacementEnabled = false, mouthOutlineEnabled = false, hairSimulationFront = false,
                hairSimulationBack = false, mouthThickness = 1.5f, mouthColor = null)
            // These axes remain ordinary keyform parameters after Cubism lowering. Other standard names
            // may be retyped from generated geometry, so keep their complete compatibility path for now.
            val fittedAxes = setOf(StandardParameters.EYE_L_OPEN, StandardParameters.EYE_R_OPEN, StandardParameters.MOUTH_OPEN)
            if (catalog.parameters.all { it.id in fittedAxes }) RigBuilder.sourceStandardParameters(analysis, native, meshCache)
            else generated(recipe = recipe).puppet.parameters
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
        val lipLayers = MouthLipLayers.prepare(analysis, outlineConfig, meshCache).layers.mapNotNull {
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
        return RigModifierRasterAssets.attach(rig, model, ribbons, config)
    }

    fun body(strength: Float, tuning: RigTuning = RigTuning(), checkpoint: () -> Unit): PuppetModel {
        checkpoint()
        return restricted(generated(strength, tuning).puppet, RigBodyModifier.parameters).also { checkpoint() }
    }

    private val staticModels = java.util.IdentityHashMap<PuppetModel, PuppetModel>()
    fun static(model: PuppetModel) = staticModels.getOrPut(model) { restricted(model, emptySet()) }

    private val scaffoldContexts = HashMap<RigModifierInstance, ModifierAlgorithmContext>()
    fun scaffoldContext(instance: RigModifierInstance): ModifierAlgorithmContext = scaffoldContexts.getOrPut(instance) {
        io.github.psd2live.algorithms.binding.ScaffoldAlgorithm.capture(scaffold(instance) {}, materialBase,
            instance.targets.associate { it.id to primaryByLayer.getValue(layersByMesh.getValue(it.id)) })
    }

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
        if (RigStageCache.enabled && instances.lastOrNull()?.type == "face.skin-clip") {
            return skinTemplate(instances, scaffold)
        }
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
                neutralScaffold = instances.isEmpty(), capture = { bindingInput = it })
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

    private fun skinTemplate(instances: List<RigModifierInstance>, scaffold: JsonObject): Template {
        val previous = template(instances.dropLast(1), scaffold)
        val instance = instances.last()
        // Clipping reads only identities and mask state; never serialize geometry for this local pass.
        val input = RigIR(io.github.psd2live.format.model.Canvas(1f, 1f), emptyList(), meshes = previous.model.drawables.map {
            io.github.psd2live.format.model.Mesh(it.id.raw, it.name, null, geometry = null, offsets = null,
                maskedBy = it.maskedBy.map { id -> id.raw }, invertMask = it.invertMask)
        })
        val context = skinClipContext(instance, includeEarlier = false)
        val complete = instance.copy(targets = input.meshes.map { ModifierTarget("mesh", it.id) })
        val result = io.github.psd2live.algorithms.binding.SkinClipAlgorithm().evaluate(input, complete,
            context) {}
        val masks = result.meshes.filterIndexed { index, mesh -> mesh != input.meshes[index] }
            .associate { it.id to (it.maskedBy.map(::DrawableId) to it.invertMask) }
        fun apply(model: PuppetModel) = model.copy(drawables = model.drawables.map { mesh ->
            val next = masks[mesh.id.raw] ?: return@map mesh
            if (mesh.maskedBy == next.first && mesh.invertMask == next.second) mesh
            else mesh.copy(maskedBy = next.first, invertMask = next.second)
        })
        RigBuildProfile.count("modifier local clipping templates")
        return Template(apply(previous.model), previous.chains, previous.bindingInput?.let { capture -> { apply(capture()) } })
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

    private fun restricted(input: PuppetModel, kept: Set<ParameterId>): PuppetModel =
        // The source mouth is open; removing its binding retains the artwork pose at 1.
        io.github.psd2live.algorithms.binding.BindingProjection.retain(input, kept, mapOf(StandardParameters.MOUTH_OPEN to 1f))

    private fun scaffold(instance: RigModifierInstance, checkpoint: () -> Unit): PuppetModel {
        checkpoint()
        val result = template(emptyList(), instance.config).model
        checkpoint()
        return result
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
            evaluate = { rig, instance, checkpoint -> scaffold(rig, instance, requireNotNull(source), checkpoint) },
            plugin = io.github.psd2live.algorithms.binding.ScaffoldAlgorithm(),
            authorContext = { requireNotNull(source).scaffoldContext(it) })
    } + RigLegacyBindings.specs.map { RigLegacyBindings.definition(it, source) }

    /** Version 2 retains the source hierarchy; version 1 keeps persisted per-mesh identities replayable. */
    fun deformerId(instance: RigModifierInstance, mesh: String, canonical: String, shared: Boolean = instance.type == SCAFFOLD && instance.version >= 2) =
        io.github.psd2live.algorithms.binding.ScaffoldBinding.deformerId(instance, mesh, canonical, shared)
    fun partId(instance: RigModifierInstance, mesh: String, canonical: String, shared: Boolean = instance.type == SCAFFOLD && instance.version >= 2) =
        io.github.psd2live.algorithms.binding.ScaffoldBinding.partId(instance, mesh, canonical, shared)

    private fun scaffold(rig: RigIR, instance: RigModifierInstance, source: RigModifierSource, checkpoint: () -> Unit): RigIR =
        io.github.psd2live.algorithms.binding.ScaffoldAlgorithm().evaluate(rig, instance, source.scaffoldContext(instance), checkpoint)

    internal fun pointsInParent(model: PuppetModel, sample: Drawable, points: FloatArray, parent: DeformerId, scratch: String): FloatArray =
        io.github.psd2live.algorithms.binding.BindingFrames.pointsInParent(model, sample, points, parent, scratch)

}
