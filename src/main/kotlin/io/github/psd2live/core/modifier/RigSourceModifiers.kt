package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.ModelIdRemap
import io.github.psd2live.algorithms.binding.ExpressionChannels
import io.github.psd2live.algorithms.binding.ScaffoldAlgorithm
import io.github.psd2live.algorithms.binding.ScaffoldParts
import io.github.psd2live.algorithms.binding.ScaffoldPartsContext
import io.github.psd2live.core.*
import io.github.psd2live.core.recipe.*
import io.github.psd2live.format.compile.RigIrBinary
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.bridge.PuppetIr
import kotlinx.serialization.json.*
import org.umamo.runtime.model.*

/** Immutable source inputs captured with the candidate, never the editor's current selection or a previous output. */
internal class RigModifierSource(
    internal val analysis: PipelineAnalysis,
    internal val atlas: PackedAtlas,
    private val config: PipelineConfig,
    internal val layersByMesh: Map<String, String>,
    copiedMeshes: Set<String> = emptySet(),
    private val materialBase: PuppetModel,
    internal val meshCache: PreviewMeshCache = PreviewMeshCache(),
) {
    fun resolveRasters(input: RigIR, outputs: List<ModifierRasterOutput>, checkpoint: () -> Unit): RigIR =
        RigModifierRasterAssets.attach(input, outputs, config, checkpoint)
    val physicsOrder: List<String> get() = config.rigEdits.physicsOrder
    private val stack by lazy { RigModifierStack.fromJson(requireNotNull(config.rigEdits.modifierStack)) }
    /** Raw persisted providers, including anonymous legacy records; source candidates decide their own eligibility. */
    internal val appliedSourceCapabilities: List<ModifierCapability> get() = stack.appliedCapabilities.toList()
    private val appliedCapabilityIds: Set<String> by lazy { stack.appliedCapabilities.mapNotNullTo(HashSet()) { it.id } }
    /**
     * Legacy Cheek and Gloom still reconstruct before/after templates. Applied outline assets are render inputs,
     * not authored mouth landmarks, even though restoration no longer retains MouthLipLayer's type.
     * Scope this compatibility correction to those v1 consumers; independent source workers keep
     * their own capture contracts, and neither authored geometry nor stored raster assets is changed.
     */
    private val legacyMoodSource: RigModifierSource by lazy {
        val authoredIds = analysis.source.layers.mapTo(hashSetOf()) { it.id.raw }
        val authoredLayers = analysis.layers.filter { it.source.id.raw in authoredIds }.associateBy { it.source.id.raw }
        val currentOutline = BundledAlgorithmArtifacts.find("mouth.outline", 1)?.reference
        // An unpinned legacy alias resolves to the current built-in. A pinned provider must match
        // its complete package identity; the namespace alone does not establish an output contract.
        val outlines = stack.appliedCapabilities.filter { it.type == "mouth.outline" && it.version >= 1 &&
            (it.plugin == null || it.plugin == currentOutline) && it.id != null }
        val generated = outlines.flatMapTo(hashSetOf()) { owner ->
            val instance = RigModifierInstance(requireNotNull(owner.id), owner.type, owner.version, owner.targets,
                config = owner.config, outputAliases = owner.outputAliases, plugin = owner.plugin)
            owner.targets.filter { target -> authoredLayers[layersByMesh[target.id]]?.let { layer ->
                layer.semantic.tag in setOf(SemanticTag.MOUTH, SemanticTag.MOUTH_OPEN) && layer.semantic.role == null
            } == true }.flatMap { target -> (0..1).map { side -> instance.outputId("mouth-art:${target.id}:$side") } }
        }
        fun sourceOnly(input: PipelineAnalysis): PipelineAnalysis {
            val original = input.source.layers.mapTo(hashSetOf()) { it.id.raw }
            val layers = input.layers.filter { it.source.id.raw in original || it.source.id.raw !in generated }
            val calibration = input.calibration?.let(::sourceOnly)
            return if (layers.size == input.layers.size && calibration === input.calibration) input
                else input.copy(layers = layers, calibration = calibration)
        }
        val source = sourceOnly(analysis)
        if (source === analysis) this else RigModifierSource(source, atlas, config, layersByMesh, copiedMeshes,
            materialBase, meshCache)
    }

    internal fun legacyBindingSource(instance: RigModifierInstance): RigModifierSource =
        if (instance.version >= 1 && instance.type in setOf("expression.cheek", "expression.gloom")) legacyMoodSource else this

    private val parameterDefinitions = java.util.IdentityHashMap<PuppetModel, List<io.github.psd2live.format.model.Parameter>>()
    internal val primaryByLayer = layersByMesh.entries.sortedBy { it.key in copiedMeshes }.groupBy { it.value }.mapValues { it.value.first().key }
    // Captured source inputs are immutable; keep these indexes local to this capture.
    private val layersById by lazy { analysis.layers.associateBy { it.source.id.raw } }
    private val sourceClips by lazy {
        RigOrdinaryMaterials.clipping(analysis.source, primaryByLayer.entries.associate { it.value to it.key })
    }
    private val meshesByLayer by lazy { layersByMesh.entries.groupBy({ it.value }, { it.key }) }
    private val projections = java.util.IdentityHashMap<PuppetModel, MutableMap<String, PuppetModel>>()
    private fun generated(native: PipelineConfig): BuiltRig {
        RigBuildProfile.count("modifier legacy rig builds")
        if (!native.mouthOutlineEnabled) return RigBuilder.build(analysis, atlas, native, meshCache)
        val prepared = MouthLipLayers.prepare(analysis, native, meshCache)
        val lips = prepared.layers.filter { it.source is MouthLipLayer }
        val pack = { RigBuildProfile.stage("source artwork: outline atlas") { RigModifierRasterAssets.append(atlas, lips, native) } }
        val packed = if (RigStageCache.enabled) meshCache.stages.get("mouth outline atlas", 16,
            listOf(atlas, lips, native.effectiveAtlasBudget()), pack) else pack()
        return RigBuilder.build(prepared, packed, native, meshCache)
    }
    val skeletonEnabled: Boolean get() = config.rigEdits.skeleton?.enabled == true
    private val hasCopiedSourceMeshes = copiedMeshes.isNotEmpty()
    /** Source-only opt-ins must check the author configuration before sourceConfig removes edited structure. */
    internal val hasCustomSourceStructure: Boolean get() = config.parentOverrides.isNotEmpty() ||
        config.rigEdits.warpEdits.isNotEmpty() || config.rigEdits.importedCmo3 != null ||
        config.rigEdits.splitBaselineLayerIds.isNotEmpty() || config.rigEdits.calibrationLayerIds.isNotEmpty() ||
        hasCopiedSourceMeshes || PrimitiveResolution.of(config.rigEdits).active
    private fun generationConfig(strength: Float, tuning: RigTuning, headStrength: Float, recipe: RigRecipe,
                                 outline: RigModifierInstance?, featureDisplacement: Boolean,
                                 frontHairSway: Boolean, backHairSway: Boolean) = sourceConfig().copy(
        bodyStrength = strength, headTurnStrength = headStrength, rigTuning = tuning, recipe = recipe,
        featureDisplacementEnabled = featureDisplacement, mouthOutlineEnabled = outline != null,
        hairSimulationFront = !frontHairSway, hairSimulationBack = !backHairSway,
        mouthThickness = outline?.let { RigLegacyBindings.values(it)["thickness"] } ?: 1.5f,
        mouthColor = outline?.let(RigLegacyBindings::outlineColor))

    internal fun sourceConfig() = RigModifierPipeline.ordinaryConfig(config).copy(meshOnly = false, generateDeformers = true,
        deferRig = false, rigPendingLayerIds = emptySet(), parentOverrides = emptyMap(),
        rigEdits = RigEditOverlay(splitDrawableIds = primaryByLayer))

    private data class Template(val model: PuppetModel, val chains: List<RigModifierChains.Binding>)
    private val templates = HashMap<Map<String, JsonObject>, Template>()
    private val transitions = HashMap<Pair<String, ModifierTarget>, Pair<RigModifierChains.Projection, RigModifierChains.Projection>>()
    private val chainProjections = java.util.IdentityHashMap<PuppetModel, MutableMap<List<RigModifierChains.Owned>, RigModifierChains.Projection>>()

    fun skinClipContext(instance: RigModifierInstance): ModifierAlgorithmContext {
        val clips = sourceClips
        fun bounds(layer: ClassifiedLayer) = JsonArray(listOf(layer.bounds.left, layer.bounds.top, layer.bounds.right, layer.bounds.bottom).map(::JsonPrimitive))
        val layers = layersById
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
            put("already_active", JsonArray(instance.targets.filter { target ->
                relevantBefore(instance, target).any { it.type == "face.skin-clip" }
            }.map { JsonPrimitive(it.id) }))
        })
    }

    private val seeThroughCandidates by lazy {
        val layers = layersById
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
            activeBefore(instance).filter {
                RigLegacyBindings.find(it.type)?.seeThrough == true ||
                    isPackagedAuthorContext(it, setOf("eye.see-through", "brow.see-through"))
            }))
    })

    private fun isPackagedAuthorContext(instance: RigModifierInstance, contracts: Set<String>): Boolean {
        val plugin = instance.plugin ?: return false
        if (ModifierPluginStore.shared.packages().any { pkg ->
                pkg.reference == plugin && pkg.manifest.algorithms.any {
                    it.type == instance.type && it.version == instance.version && it.authorContext in contracts
                }
            }) return true
        return contracts.any { alias ->
            val artifact = BundledAlgorithmArtifacts.find(alias, instance.version) ?: return@any false
            plugin.id == artifact.manifest.id && artifact.manifest.algorithms.any {
                it.type == instance.type && it.version == instance.version && alias in it.aliases
            }
        }
    }

    private val expressionLayers by lazy {
        analysis.layers.mapNotNull { layer ->
            val override = config.layerOverrides[layer.source.id.raw]
            val type = override?.type ?: layer.semantic.type
            val parameter = (override?.parameter ?: layer.semantic.parameter).trim()
            if (type == LayerType.PRESET || parameter.isBlank()) null else layer.source.id.raw to
                io.github.psd2live.algorithms.binding.ExpressionChannels.Layer(parameter,
                    if (type == LayerType.SWITCH) override?.switchId ?: layer.semantic.switchId else null)
        }.toMap()
    }

    private val expressionCatalog by lazy {
        io.github.psd2live.algorithms.binding.ExpressionChannels.catalog(expressionLayers.values.toList(), staggered = true)
    }
    private val standardExpressionParameters by lazy {
        // The v1 source recipe used default bases with no widening or extra mouth reach.
        // Hair chains replaced a legacy sway axis only when that side had source artwork.
        val layout = analysis.calibration ?: analysis
        val hair = layout.layers.filter { it.opaquePixels > 0 }.mapTo(hashSetOf()) { it.semantic.tag }
        StandardParameters.all.filterNot {
            (it.id == StandardParameters.HAIR_FRONT && SemanticTag.FRONT_HAIR in hair) ||
                (it.id == StandardParameters.HAIR_BACK && SemanticTag.BACK_HAIR in hair)
        }
    }
    private val expressionHairParameters by lazy {
        val recipe = RigRecipe(true, modules = RigModules.modules.associate { it.id to ModuleChoice(it == RigModules.EXPRESSION_TOGGLES) })
        val native = generationConfig(1f, RigTuning(), 1f, recipe, null,
            featureDisplacement = false, frontHairSway = true, backHairSway = true)
        RigBuilder.expressionHairParameters(analysis, native, meshCache)
    }

    internal fun expressionIds(instance: RigModifierInstance) = instance.targets.mapNotNullTo(linkedSetOf()) {
        expressionLayers[layersByMesh[it.id]]?.let { layer -> ParameterId(layer.parameter) }
    }

    fun expressionParameters(instance: RigModifierInstance): List<io.github.psd2live.format.model.Parameter> {
        val ids = expressionIds(instance)
        if (ids.isEmpty()) return emptyList()
        val standardIds = StandardParameters.all.mapTo(HashSet()) { it.id }
        val collisions = ids.intersect(standardIds)
        val standard = if (collisions.isEmpty()) emptyList() else standardExpressionParameters.filter { it.id in collisions }
        val fittedAxes = setOf(StandardParameters.EYE_L_OPEN, StandardParameters.EYE_R_OPEN, StandardParameters.MOUTH_OPEN)
        // The previous generator-backed path preferred an existing chain declaration to a same-name expression.
        val hair = if (collisions.any { it !in fittedAxes } && ids.any { it !in standardIds && it.raw.startsWith("ParamHair") })
            expressionHairParameters.filter { it.id in ids } else emptyList()
        val reserved = standardIds + hair.map { it.id }
        val parameters = standard + hair + expressionCatalog.parameters.filter { it.id in ids && it.id !in reserved }
        return PuppetIr.parametersToIr(materialBase.copy(parameters = parameters))
    }

    private fun expressionStandardIds(instance: RigModifierInstance): Set<ParameterId> =
        expressionIds(instance).intersect(StandardParameters.all.mapTo(HashSet()) { it.id })

    fun expressionSourceParameters(instance: RigModifierInstance): List<io.github.psd2live.format.model.Parameter> {
        val ids = expressionIds(instance) - expressionStandardIds(instance)
        return PuppetIr.parametersToIr(materialBase.copy(parameters = expressionCatalog.parameters.filter { it.id in ids }))
    }

    fun expressionContext(instance: RigModifierInstance, requireUpstreamStandards: Boolean = false): ModifierAlgorithmContext =
        ModifierAlgorithmContext(buildJsonObject {
            put("schema", "expression/1")
            if (requireUpstreamStandards) put("requiredParameters", JsonArray(expressionStandardIds(instance).map { JsonPrimitive(it.raw) }))
            putJsonObject("layers") {
                expressionLayers.forEach { (id, layer) ->
                    putJsonObject(id) {
                        put("parameter", layer.parameter)
                        layer.switchId?.let { put("switch", it) }
                    }
                }
            }
            put("meshes", JsonObject(layersByMesh.mapValues { JsonPrimitive(it.value) }))
            putJsonObject("opacity") { analysis.layers.forEach { put(it.source.id.raw, it.source.opacity.coerceIn(0f, 1f)) } }
            put("chain_owners", Json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(RigModifierInstance.serializer()),
                activeBefore(instance).filter { RigModifierChains.kinds(it.type).isNotEmpty() }))
        }, resources = mapOf("parameters" to ModifierAlgorithmResource(io.github.psd2live.format.compile.RigIrBinary.encode(
            RigIR(io.github.psd2live.format.model.Canvas(1f, 1f),
                if (requireUpstreamStandards) expressionSourceParameters(instance) else expressionParameters(instance))))))

    fun addUpstreamExpressionInputs(rig: RigIR, instance: RigModifierInstance): RigIR {
        val preceding = activeBefore(instance).filter {
            RigLegacyBindings.find(it.type)?.expressionToggles == true ||
                isPackagedAuthorContext(it, setOf("expression.toggles"))
        }
        return RigModifierPhysics.addExpressionInputs(rig, listOf(instance), preceding.flatMap { expressionIds(it).map(ParameterId::raw) })
    }

    private fun priorMoodStrength(instance: RigModifierInstance, contract: String): Float? =
        activeBefore(instance).lastOrNull {
            it.type == contract || isPackagedAuthorContext(it, setOf(contract))
        }?.let { RigLegacyBindings.values(it.copy(type = contract))["strength"] ?: 100f }

    fun cheekContext(instance: RigModifierInstance): ModifierAlgorithmContext {
        val priorCheek = priorMoodStrength(instance, "expression.cheek")
        val priorGloom = priorMoodStrength(instance, "expression.gloom")
        val materialById = materialBase.drawables.associateBy { it.id.raw }
        val targets = instance.targets.mapNotNull { target ->
            val layerId = layersByMesh[target.id] ?: return@mapNotNull null
            val layer = layersById[layerId] ?: return@mapNotNull null
            val tag = layer.semantic.tag
            val role = layer.semantic.role
            val kind = when {
                tag == SemanticTag.FACE_DETAIL && role == PartRole.BLUSH -> io.github.psd2live.algorithms.binding.CheekSource.LayerKind.BLUSH
                tag == SemanticTag.FACE -> io.github.psd2live.algorithms.binding.CheekSource.LayerKind.FACE
                tag == SemanticTag.NOSE -> io.github.psd2live.algorithms.binding.CheekSource.LayerKind.NOSE
                tag == SemanticTag.EARS -> io.github.psd2live.algorithms.binding.CheekSource.LayerKind.EARS
                tag == SemanticTag.FACE_DETAIL && role != PartRole.HIGHLIGHT -> io.github.psd2live.algorithms.binding.CheekSource.LayerKind.FACE_DETAIL
                else -> io.github.psd2live.algorithms.binding.CheekSource.LayerKind.OTHER
            }
            val baseDrawable = primaryByLayer[layerId]?.let(materialById::get)
            val generatedMultiply = baseDrawable?.channelGrids?.gridsByChannel?.containsKey(org.umamo.runtime.model.FormChannel.MULTIPLY_COLOR) == true
            target.id to io.github.psd2live.algorithms.binding.CheekSource.Layer(kind, priorCheek, priorGloom, generatedMultiply)
        }.toMap()
        return io.github.psd2live.algorithms.binding.CheekSource(
            hasFace = analysis.layers.any { it.semantic.tag == SemanticTag.FACE },
            parameterName = io.github.psd2live.i18n.tr("model.parameter.cheek"),
            folderName = io.github.psd2live.i18n.tr("model.group.face"),
            targets = targets,
        ).context()
    }

    fun lidFadeContext(instance: RigModifierInstance): ModifierAlgorithmContext {
        val targetLayers = instance.targets.mapNotNull { t -> layersByMesh[t.id]?.let { t.id to it } }.toMap()
        val upstream = activeBefore(instance).map {
            ModifierUpstreamCapability(it.type, it.type, it.version, it.targets, it.id, it.config)
        }
        return RigBuilder.lidFadeSourceInputs(analysis, sourceConfig(), meshCache, targetLayers).withUpstream(upstream)
    }

    fun eyeSquashContext(instance: RigModifierInstance): ModifierAlgorithmContext {
        val targetLayers = instance.targets.mapNotNull { t -> layersByMesh[t.id]?.let { t.id to it } }.toMap()
        val base = instance.config["base"]?.jsonPrimitive?.content
        val fitted = activeBefore(instance).singleOrNull { it.id == base }
        val scaffold = provider(instance)
        return RigBuilder.eyeSquashSourceInputs(analysis, atlas, sourceConfig(), meshCache, targetLayers) { target, deformer ->
            val owner = if (io.github.psd2live.algorithms.binding.ScaffoldFramesContext.owns(DeformerId(deformer))) scaffold
            else (fitted ?: instance)
            RigSourceModifiers.deformerId(owner, target, deformer)
        }
    }

    private fun eyeSubParentId(instance: RigModifierInstance): (String, String) -> String {
        val scaffold = provider(instance)
        return { target, deformer -> RigSourceModifiers.deformerId(scaffold, target, deformer) }
    }

    private fun eyeModuleParameterNames(vararg modules: io.github.psd2live.core.recipe.RigModule): Map<String, String> =
        modules.flatMap { it.defines }.associate { it.id to io.github.psd2live.i18n.tr(it.nameKey) }

    fun eyeFittedContext(instance: RigModifierInstance): ModifierAlgorithmContext =
        RigFittedEyeSource.captureTransition(this, instance)

    fun eyeLegacyContext(instance: RigModifierInstance): ModifierAlgorithmContext {
        val scaffold = provider(instance)
        val names = StandardParameters.all
            .filter { it.id in RigLegacyBindings.spec("eye/legacy").parameters }
            .associate { it.id.raw to it.name }
        return io.github.psd2live.core.modifier.source.RigStaticSourceCollector.eyeLegacySourceInputs(
            analysis, atlas, sourceConfig(), meshCache, scaffoldContext(scaffold),
            primaryByLayer, layersByMesh, instance.targets.map { it.id }, names,
        )
    }

    private fun eyeConfig(instance: RigModifierInstance): PipelineConfig {
        if (instance.type == RigSourceModifiers.EYE) {
            return if ((instance.config["mode"]?.jsonPrimitive?.content ?: "fitted") == "fitted") fittedEyeConfig(instance) else sourceConfig()
        }
        val baseId = instance.config["dependencies"]?.jsonObject?.get("eye/fitted")?.jsonPrimitive?.content
            ?: instance.config["base"]?.jsonPrimitive?.content
        val base = if (baseId != null) {
            stack.instances.firstOrNull { it.id == baseId }
        } else {
            activeBefore(instance).lastOrNull {
                isFittedEyeInstance(it) && it.config["scaffold"] == instance.config["scaffold"]
            }
        } ?: return sourceConfig()
        val fitted = base.type == "eye/fitted" || (base.type == "eye" && (base.config["mode"]?.jsonPrimitive?.content ?: "fitted") == "fitted")
        if (!fitted) return sourceConfig()
        return fittedEyeConfig(base)
    }

    private fun fittedEyeConfig(base: RigModifierInstance): PipelineConfig {
        val scaffold = provider(base)
        val corrections = io.github.psd2live.project.WorkspaceSettingsCodec.decodeRecipe(scaffold.config["corrections"])
        val eyeVersion = if (base.type == "eye/fitted") base.version else 3
        return sourceConfig().copy(
            recipe = io.github.psd2live.core.recipe.RigRecipe(
                modern = true,
                bases = mapOf("eye" to io.github.psd2live.core.recipe.BaseChoice("eye/fitted", eyeVersion)),
                modules = io.github.psd2live.core.recipe.RigModules.modules.associate {
                    it.id to io.github.psd2live.core.recipe.ModuleChoice(false)
                },
                moves = corrections.moves,
                eyeParts = corrections.eyeParts,
            ),
        )
    }

    fun eyeWideContext(instance: RigModifierInstance): ModifierAlgorithmContext {
        val targetLayers = instance.targets.mapNotNull { t -> layersByMesh[t.id]?.let { t.id to it } }.toMap()
        return io.github.psd2live.core.modifier.source.RigStaticSourceCollector.eyeWideSourceInputs(
            analysis, atlas, eyeConfig(instance), meshCache, targetLayers, eyeSubParentId(instance),
        )
    }

    fun eyeSmileContext(instance: RigModifierInstance): ModifierAlgorithmContext {
        val targetLayers = instance.targets.mapNotNull { t -> layersByMesh[t.id]?.let { t.id to it } }.toMap()
        val names = eyeModuleParameterNames(io.github.psd2live.core.recipe.RigModules.EYE_SMILE)
        return io.github.psd2live.core.modifier.source.RigStaticSourceCollector.eyeSmileSourceInputs(
            analysis, atlas, eyeConfig(instance), meshCache, targetLayers, names, eyeSubParentId(instance),
        )
    }

    fun eyeEmotionContext(instance: RigModifierInstance): ModifierAlgorithmContext {
        val targetLayers = instance.targets.mapNotNull { t -> layersByMesh[t.id]?.let { t.id to it } }.toMap()
        val names = eyeModuleParameterNames(io.github.psd2live.core.recipe.RigModules.EYE_EMOTION)
        return io.github.psd2live.core.modifier.source.RigStaticSourceCollector.eyeSmileSourceInputs(
            analysis, atlas, eyeConfig(instance), meshCache, targetLayers, names, eyeSubParentId(instance),
        )
    }

    fun eyeGazeLidContext(instance: RigModifierInstance): ModifierAlgorithmContext {
        val targetLayers = instance.targets.mapNotNull { t -> layersByMesh[t.id]?.let { t.id to it } }.toMap()
        val names = eyeModuleParameterNames(io.github.psd2live.core.recipe.RigModules.EYE_GAZE_LID)
        val source = io.github.psd2live.core.modifier.source.RigStaticSourceCollector.eyeSmileSourceInputs(
            analysis, atlas, eyeConfig(instance), meshCache, targetLayers, names, eyeSubParentId(instance),
        )
        val scaffold = provider(instance)
        val representative = instance.targets.firstOrNull()?.id.orEmpty()
        val carriers = mapOf(Side.LEFT to "L", Side.RIGHT to "R").entries.associate { (side, code) ->
            RigSourceModifiers.deformerId(
                scaffold,
                representative,
                io.github.psd2live.algorithms.binding.ScaffoldHead.gazeId(side).raw,
            ) to code
        }
        return io.github.psd2live.algorithms.binding.EyeGazeLidAlgorithm.context(
            source, carriers, io.github.psd2live.i18n.tr("model.deformer.meshShape", "{meshName}"),
        )
    }

    fun eyeHighlightContext(instance: RigModifierInstance): ModifierAlgorithmContext {
        val targetLayers = instance.targets.mapNotNull { t -> layersByMesh[t.id]?.let { t.id to it } }.toMap()
        return io.github.psd2live.core.modifier.source.RigStaticSourceCollector.eyeHighlightSourceInputs(
            analysis, sourceConfig(), meshCache, targetLayers, eyeSubParentId(instance),
        )
    }

    fun eyeAftershockContext(instance: RigModifierInstance): ModifierAlgorithmContext {
        val targetLayers = instance.targets.mapNotNull { t -> layersByMesh[t.id]?.let { t.id to it } }.toMap()
        val names = eyeModuleParameterNames(io.github.psd2live.core.recipe.RigModules.EYE_AFTERSHOCK)
        val source = io.github.psd2live.core.modifier.source.RigStaticSourceCollector.eyeSmileSourceInputs(
            analysis, atlas, eyeConfig(instance), meshCache, targetLayers, names, eyeSubParentId(instance),
        )
        return io.github.psd2live.algorithms.binding.EyeAftershockAlgorithm.context(
            source,
            io.github.psd2live.i18n.tr("model.group.physics"),
            listOf(
                io.github.psd2live.i18n.tr("model.physics.eyeLBounce"),
                io.github.psd2live.i18n.tr("model.physics.eyeRBounce"),
            ),
        )
    }

    private fun browFrameConfig(instance: RigModifierInstance): PipelineConfig = sourceConfig()

    fun browFittedContext(instance: RigModifierInstance): ModifierAlgorithmContext {
        val targetLayers = instance.targets.mapNotNull { t -> layersByMesh[t.id]?.let { t.id to it } }.toMap()
        val scaffold = provider(instance)
        val eye = activeBefore(instance).lastOrNull {
            (it.type == "eye/fitted" || (it.type == "eye" && (it.config["mode"]?.jsonPrimitive?.content ?: "fitted") == "fitted")) &&
                it.config["scaffold"]?.jsonPrimitive?.content == scaffold.id
        }
        val upstream = buildList {
            add(ModifierUpstreamCapability("rig.scaffold", scaffold.type, scaffold.version, scaffold.targets,
                scaffold.id, scaffold.config, scaffold.outputAliases, scaffold.plugin))
            eye?.let {
                add(ModifierUpstreamCapability("eye/fitted", it.type, it.version, it.targets,
                    it.id, it.config, it.outputAliases, it.plugin))
            }
        }
        return io.github.psd2live.core.modifier.source.RigStaticSourceCollector.browFittedSourceInputs(
            analysis, atlas, eye?.let(::fittedEyeConfig) ?: sourceConfig(), meshCache, scaffold.id, targetLayers, primaryByLayer, eye?.id, upstream,
            browFrameConfig(instance),
        )
    }

    fun browLegacyContext(instance: RigModifierInstance): ModifierAlgorithmContext {
        val targetLayers = instance.targets.mapNotNull { t -> layersByMesh[t.id]?.let { t.id to it } }.toMap()
        return io.github.psd2live.core.modifier.source.RigStaticSourceCollector.browLegacySourceInputs(
            analysis, sourceConfig(), meshCache, targetLayers, eyeSubParentId(instance),
        )
    }

    fun browLidContext(instance: RigModifierInstance): ModifierAlgorithmContext {
        val targetLayers = instance.targets.mapNotNull { t -> layersByMesh[t.id]?.let { t.id to it } }.toMap()
        val scaffold = instance.config["scaffold"]
        val base = instance.config["base"]?.jsonPrimitive?.content
        val brow = activeBefore(instance).singleOrNull { it.id == base }
            ?: activeBefore(instance).lastOrNull { it.type == "brow/fitted" && it.config["scaffold"] == scaffold }
            ?: instance.takeIf { it.type == RigSourceModifiers.BROW }
        val browMeshes = brow?.targets?.filter { it.kind == "mesh" }?.map { it.id }.orEmpty()
        return io.github.psd2live.core.modifier.source.RigStaticSourceCollector.browLidSourceInputs(
            analysis, atlas, eyeConfig(instance), meshCache, targetLayers, primaryByLayer, browMeshes, eyeSubParentId(instance),
        )
    }

    private fun isFittedEyeInstance(instance: RigModifierInstance): Boolean =
        instance.type == "eye/fitted" ||
            (instance.type == RigSourceModifiers.EYE && (instance.config["mode"]?.jsonPrimitive?.content ?: "fitted") == "fitted")

    fun browBlinkContext(instance: RigModifierInstance): ModifierAlgorithmContext {
        val targetLayers = instance.targets.mapNotNull { t -> layersByMesh[t.id]?.let { t.id to it } }.toMap()
        val scaffold = instance.config["scaffold"]
        val eyeProvider = activeBefore(instance).lastOrNull {
            isFittedEyeInstance(it) && it.config["scaffold"] == scaffold
        }?.id
        return io.github.psd2live.core.modifier.source.RigStaticSourceCollector.browBlinkSourceInputs(
            analysis, atlas, activeRecipeConfig(instance), meshCache, targetLayers, eyeProvider, eyeSubParentId(instance),
            browFrameConfig(instance),
        )
    }

    fun browShapeContext(instance: RigModifierInstance): ModifierAlgorithmContext {
        val targetLayers = instance.targets.mapNotNull { t -> layersByMesh[t.id]?.let { t.id to it } }.toMap()
        val scaffold = instance.config["scaffold"]
        val eyeProvider = activeBefore(instance).lastOrNull {
            isFittedEyeInstance(it) && it.config["scaffold"] == scaffold
        }?.id
        return RigBuilder.browShapeSourceInputs(
            analysis, atlas, activeRecipeConfig(instance), meshCache, targetLayers, eyeProvider, eyeSubParentId(instance),
            browFrameConfig(instance),
        ).encode()
    }

    fun browSpringContext(instance: RigModifierInstance): ModifierAlgorithmContext {
        val targetLayers = instance.targets.mapNotNull { t -> layersByMesh[t.id]?.let { t.id to it } }.toMap()
        val scaffold = instance.config["scaffold"]
        val eyeProvider = activeBefore(instance).lastOrNull {
            isFittedEyeInstance(it) && it.config["scaffold"] == scaffold
        }?.id
        val shapeProvider = activeBefore(instance).lastOrNull {
            (it.type == "brow.shape" || isPackagedAuthorContext(it, setOf("brow.shape"))) &&
                it.config["scaffold"] == scaffold && it.config["base"] == instance.config["base"]
        }?.id
        val kept = activeBefore(instance).plus(instance).flatMapTo(HashSet()) {
            val mode = it.config["mode"]?.jsonPrimitive?.content ?: "fitted"
            val contracts = when (it.type) {
                "eye" -> listOf(if (mode == "fitted") "eye/fitted" else "eye/legacy")
                "brow" -> buildList {
                    add(if (mode == "fitted") "brow/fitted" else "brow/legacy")
                    if (it.config["shape"]?.jsonPrimitive?.booleanOrNull == true) add("brow.shape")
                    if (it.config["spring"]?.jsonPrimitive?.booleanOrNull == true) add("brow.spring")
                }
                "face" -> listOf(if (mode == "fitted") RigLegacyBindings.FITTED_FACE else "face/legacy")
                "body" -> listOf("body/legacy")
                "hair" -> listOf("hair/legacy")
                else -> listOf(
                    RigLegacyBindings.find(it.type)?.type
                        ?: if (isPackagedAuthorContext(it, setOf("brow.shape"))) "brow.shape"
                        else if (isPackagedAuthorContext(it, setOf("brow.spring"))) "brow.spring"
                        else it.type,
                )
            }
            contracts.flatMap { contract -> RigLegacyBindings.find(contract)?.parameters.orEmpty() }
        }
        val cfg = activeRecipeConfig(instance, browBeforeFaceOnly = true)
        return RigBuilder.browSpringSourceInputs(
            analysis, atlas, cfg, meshCache, targetLayers, kept, eyeProvider, shapeProvider, eyeSubParentId(instance),
            { part -> provider(instance).outputId("shared/part:$part") },
            browFrameConfig(instance),
        ).encode()
    }

    fun gloomContext(instance: RigModifierInstance): ModifierAlgorithmContext {
        val priorCheek = priorMoodStrength(instance, "expression.cheek")
        val priorGloom = priorMoodStrength(instance, "expression.gloom")
        val materialById = materialBase.drawables.associateBy { it.id.raw }
        val layers = LinkedHashMap<String, io.github.psd2live.algorithms.binding.GloomInputs.Layer>()
        val neutral = LinkedHashMap<String, io.github.psd2live.format.model.Mesh>()
        for (target in instance.targets) {
            val layerId = layersByMesh[target.id] ?: continue
            val layer = layersById[layerId] ?: continue
            val tag = layer.semantic.tag
            val role = layer.semantic.role
            val skin = tag == SemanticTag.FACE || tag == SemanticTag.NOSE || tag == SemanticTag.EARS ||
                (tag == SemanticTag.FACE_DETAIL && role != PartRole.HIGHLIGHT)
            val cheekWarmth = when {
                tag == SemanticTag.FACE_DETAIL && role == PartRole.BLUSH -> 0.3f
                tag == SemanticTag.FACE || tag == SemanticTag.NOSE -> 0.06f
                tag == SemanticTag.EARS -> 0.05f
                else -> 0f
            }
            layers[target.id] = io.github.psd2live.algorithms.binding.GloomInputs.Layer(
                skin = skin,
                highlight = role == PartRole.HIGHLIGHT,
                cheekWarmth = cheekWarmth,
                precedingCheekStrength = priorCheek,
                precedingGloomStrength = priorGloom,
            )
            val baseDrawable = primaryByLayer[layerId]?.let(materialById::get)
            neutral[target.id] = if (baseDrawable != null) {
                io.github.psd2live.bridge.PuppetIr.meshToIr(baseDrawable).copy(
                    id = target.id, name = target.id, parent = null, geometry = null, offsets = null, shapes = emptyList(),
                )
            } else {
                io.github.psd2live.format.model.Mesh(target.id, target.id, null, geometry = null, offsets = null)
            }
        }
        return io.github.psd2live.algorithms.binding.GloomInputs(
            applies = analysis.layers.any { it.semantic.tag == SemanticTag.FACE },
            parameterName = io.github.psd2live.i18n.tr("model.parameter.gloom"),
            groupName = io.github.psd2live.i18n.tr("model.group.face"),
            layers = layers,
            neutral = neutral,
        ).context()
    }

    fun bodyContext(instance: RigModifierInstance): ModifierAlgorithmContext {
        val scaffold = provider(instance)
        val specInstance = if (RigLegacyBindings.find(instance.type) != null) instance else instance.copy(type = "body/legacy")
        val tuning = RigLegacyBindings.tuning(specInstance, RigTuning()).bodyMotion()
        val context = scaffoldContext(scaffold)
        val torso = requireNotNull(scaffoldTorsos[scaffold]) { "Body requires captured scaffold measurements" }
        val tr = { key: String -> io.github.psd2live.i18n.tr(key) }
        val native = RigBuilder.rigContext(analysis, sourceConfig().copy(meshOnly = false, generateDeformers = true), meshCache)
        val byId = native.deformers.associateBy { it.id }
        val poses = native.deformers.filterIsInstance<Deformer.Warp>().filter { warp ->
            warp.geometryGrid?.axes?.any { it.parameterId == StandardParameters.ANGLE_Y } == true &&
                generateSequence(warp.parent) { byId[it]?.parent }.any { it == io.github.psd2live.algorithms.binding.ScaffoldHead.rotationId }
        }.map(io.github.psd2live.bridge.PuppetIr::deformerToIr)
        val pitch = if (poses.any { it.id == io.github.psd2live.algorithms.binding.ScaffoldHead.headId.raw }) {
            ModifierAlgorithmResource(RigIrBinary.encode(
                io.github.psd2live.format.model.RigIR(io.github.psd2live.format.model.Canvas(0f, 0f), emptyList(), deformers = poses),
            ))
        } else null
        return io.github.psd2live.algorithms.binding.LegacyBodyInputs.capture(context, torso, tuning,
            StandardParameters.all.filter { it.id in RigBodyModifier.parameters }.associate { it.id.raw to it.name },
            linkedMapOf("ParamGroupFace" to tr("model.group.face"), "ParamGroupEyes" to tr("model.group.eyes"),
                "ParamGroupBrows" to tr("model.group.brows"), "ParamGroupMouth" to tr("model.group.mouth"),
                "ParamGroupBody" to tr("model.group.body"), "ParamGroupPhysics" to tr("model.group.physics")),
            pitch)
    }

    fun faceLegacyContext(instance: RigModifierInstance): ModifierAlgorithmContext {
        val scaffold = provider(instance)
        val context = scaffoldContext(scaffold)
        val specInstance = if (RigLegacyBindings.find(instance.type) != null) instance else instance.copy(type = "face/legacy")
        val tuning = RigLegacyBindings.tuning(specInstance, RigTuning())
        val strength = RigLegacyBindings.strength(specInstance)
        val ids = RigLegacyBindings.spec("face/legacy").parameters
        val names = StandardParameters.all.filter { it.id in ids }.map { it.name }
        return io.github.psd2live.core.modifier.source.RigStaticSourceCollector.faceLegacySourceInputs(
            analysis, sourceConfig(), meshCache, context, strength, tuning, names,
        )
    }

    fun faceFittedContext(instance: RigModifierInstance): ModifierAlgorithmContext {
        val scaffold = provider(instance)
        val context = scaffoldContext(scaffold)
        val specInstance = if (RigLegacyBindings.find(instance.type) != null) instance else instance.copy(type = RigLegacyBindings.FITTED_FACE)
        val tuning = RigLegacyBindings.tuning(specInstance, RigTuning())
        val strength = RigLegacyBindings.strength(specInstance)
        val ids = RigLegacyBindings.spec(RigLegacyBindings.FITTED_FACE).parameters
        val names = StandardParameters.all.filter { it.id in ids }.map { it.name }
        val targetLayers = instance.targets.mapNotNull { t -> layersByMesh[t.id]?.let { t.id to it } }.toMap()
        val upstream = listOf(
            ModifierUpstreamCapability("rig.scaffold", scaffold.type, scaffold.version, scaffold.targets,
                scaffold.id, scaffold.config, scaffold.outputAliases, scaffold.plugin),
        )
        return io.github.psd2live.core.modifier.source.RigStaticSourceCollector.faceFittedSourceInputs(
            analysis, depthTurnConfig(instance, specInstance, tuning, strength), meshCache, context, targetLayers, primaryByLayer,
            strength, tuning, names, upstream,
        )
    }

    fun depthTurnContext(instance: RigModifierInstance): ModifierAlgorithmContext {
        val scaffold = provider(instance)
        val context = scaffoldContext(scaffold)
        val targetLayers = instance.targets.mapNotNull { t -> layersByMesh[t.id]?.let { t.id to it } }.toMap()
        return io.github.psd2live.core.modifier.source.RigStaticSourceCollector.depthTurnSourceInputs(
            analysis, depthTurnConfig(instance), meshCache, context, targetLayers, primaryByLayer,
        )
    }

    private fun depthTurnConfig(
        instance: RigModifierInstance,
        specInstance: RigModifierInstance = instance,
        tuning: RigTuning = RigLegacyBindings.tuning(specInstance, RigTuning()),
        strength: Float = RigLegacyBindings.strength(specInstance),
    ): PipelineConfig = activeRecipeConfig(
        instance,
        extraFace = specInstance,
        tuningOverride = tuning,
        strengthOverride = strength,
    )

    private fun activeRecipeConfig(
        instance: RigModifierInstance,
        extraFace: RigModifierInstance? = null,
        tuningOverride: RigTuning? = null,
        strengthOverride: Float? = null,
        browBeforeFaceOnly: Boolean = false,
    ): PipelineConfig {
        val scaffold = provider(instance)
        val preceding = activeBefore(instance).filter { it.config["scaffold"]?.jsonPrimitive?.content == scaffold.id }
        val corrections = io.github.psd2live.project.WorkspaceSettingsCodec.decodeRecipe(scaffold.config["corrections"])
        val depthId = io.github.psd2live.core.recipe.RigModules.FACE_DEPTH_TURN.id
        val faceChoice = preceding.lastOrNull { it.type == RigLegacyBindings.FITTED_FACE || it.type == "face" || it.type == depthId }
        val areas = io.github.psd2live.core.recipe.RigModules.areas
        val bases = areas.associate { area ->
            val candidateChosen = preceding.lastOrNull { prev ->
                RigLegacyBindings.find(prev.type)?.let { it.isBase && it.area == area.id } == true || prev.type == area.id
            } ?: instance.takeIf {
                (it.type == area.id || it.type.startsWith("${area.id}.")) &&
                    it.config["scaffold"]?.jsonPrimitive?.content == scaffold.id
            }
            val chosen = if (browBeforeFaceOnly && area.id == "brow" && candidateChosen != null && faceChoice != null &&
                preceding.indexOf(candidateChosen) > preceding.indexOf(faceChoice)) null else candidateChosen
            val base = when {
                chosen == null -> area.legacy.id
                chosen.type == area.id || chosen.type.startsWith("${area.id}.") -> {
                    val mode = chosen.config["mode"]?.jsonPrimitive?.content ?: "fitted"
                    val candidate = "${area.id}/$mode"
                    if (candidate == RigLegacyBindings.FITTED_FACE) area.legacy.id
                    else if (RigLegacyBindings.find(candidate) != null) candidate else area.legacy.id
                }
                chosen.type == RigLegacyBindings.FITTED_FACE -> area.legacy.id
                else -> chosen.type
            }
            val specType = when {
                chosen == null -> base
                chosen.type == "face" && base == area.legacy.id &&
                    (chosen.config["mode"]?.jsonPrimitive?.content ?: "fitted") == "fitted" -> RigLegacyBindings.FITTED_FACE
                chosen.type == area.id -> base
                else -> chosen.type
            }
            area.id to io.github.psd2live.core.recipe.BaseChoice(base, chosen?.version ?: area.legacy.version,
                chosen?.let { RigLegacyBindings.values(it.copy(type = specType)) }.orEmpty())
        }
        val modules = io.github.psd2live.core.recipe.RigModules.modules.associate { module ->
            val chosen = when {
                module.id == depthId && extraFace != null -> extraFace
                module.id == depthId -> preceding.lastOrNull { it.type == depthId }
                    ?: faceChoice?.takeIf { it.type == RigLegacyBindings.FITTED_FACE || (it.config["mode"]?.jsonPrimitive?.content ?: "fitted") == "fitted" }
                else -> preceding.lastOrNull { it.type == module.id }
            }
            val moduleSpecType = if (chosen?.type == "face") depthId else chosen?.type
            module.id to io.github.psd2live.core.recipe.ModuleChoice(chosen != null,
                chosen?.version ?: module.version,
                chosen?.let { RigLegacyBindings.values(it.copy(type = requireNotNull(moduleSpecType))) }.orEmpty())
        }
        val partTypes = setOf("eye", "brow", "mouth", "face", "body", "hair")
        val tuning = tuningOverride ?: preceding.fold(RigTuning()) { acc, prev ->
            val tuningType = when {
                RigLegacyBindings.find(prev.type) != null -> prev.type
                prev.type in partTypes -> {
                    val mode = prev.config["mode"]?.jsonPrimitive?.content ?: "fitted"
                    val candidate = "${prev.type}/$mode"
                    if (RigLegacyBindings.find(candidate) != null) candidate else "${prev.type}/legacy"
                }
                else -> null
            }
            if (tuningType != null) RigLegacyBindings.tuning(prev.copy(type = tuningType), acc) else acc
        }
        val headStrength = strengthOverride ?: faceChoice?.let(RigLegacyBindings::strength) ?: 1f
        return sourceConfig().copy(
            headTurnStrength = headStrength,
            rigTuning = tuning,
            recipe = io.github.psd2live.core.recipe.RigRecipe(
                modern = true,
                bases = bases,
                modules = modules,
                moves = corrections.moves,
                eyeParts = corrections.eyeParts,
            ),
        )
    }

    fun neckContext(instance: RigModifierInstance): ModifierAlgorithmContext {
        val targetLayers = instance.targets.mapNotNull { t -> layersByMesh[t.id]?.let { t.id to it } }.toMap()
        return io.github.psd2live.core.modifier.source.RigStaticSourceCollector.neckSourceInputs(
            analysis, atlas, sourceConfig(), meshCache, targetLayers, primaryByLayer, eyeSubParentId(instance),
        )
    }

    fun bustContext(instance: RigModifierInstance): ModifierAlgorithmContext {
        val scaffold = provider(instance)
        val context = scaffoldContext(scaffold)
        val torso = requireNotNull(scaffoldTorsos[scaffold]) { "Bust requires captured scaffold measurements" }
        val targetLayers = instance.targets.mapNotNull { t -> layersByMesh[t.id]?.let { t.id to it } }.toMap()
        return io.github.psd2live.core.modifier.source.RigStaticSourceCollector.bustSourceInputs(
            analysis, sourceConfig(), meshCache, context, torso, materialBase, targetLayers, primaryByLayer,
        )
    }

    fun armSwingContext(instance: RigModifierInstance): ModifierAlgorithmContext {
        val scaffold = provider(instance)
        val context = scaffoldContext(scaffold)
        val torso = requireNotNull(scaffoldTorsos[scaffold]) { "Arm swing requires captured scaffold measurements" }
        val targetLayers = instance.targets.mapNotNull { t -> layersByMesh[t.id]?.let { t.id to it } }.toMap()
        return io.github.psd2live.core.modifier.source.RigStaticSourceCollector.armSwingSourceInputs(
            context, torso, targetLayers, primaryByLayer,
        )
    }

    private val phaseContexts = java.util.concurrent.ConcurrentHashMap<Pair<String, List<RigModifierInstance>>, Pair<PipelineConfig, RigBuilder.RigContext>>()

    internal fun phaseContext(scaffold: RigModifierInstance, preceding: List<RigModifierInstance>): Pair<PipelineConfig, RigBuilder.RigContext> =
        phaseContexts.computeIfAbsent(scaffold.id to preceding) {
            val native = RigFittedMouthModuleSource.nativeConfig(this, scaffold, preceding, null)
            native to RigBuilder.rigContext(analysis, native, meshCache)
        }

    private fun precedingExpressionIds(instance: RigModifierInstance): List<String> =
        activeBefore(instance).filter {
            RigLegacyBindings.find(it.type)?.expressionToggles == true || isPackagedAuthorContext(it, setOf("expression.toggles"))
        }.flatMap { expressionIds(it).map(ParameterId::raw) }

    private fun sharedOutfitTargets(instance: RigModifierInstance, otherType: String): Set<String> {
        val targetIds = instance.targets.mapTo(HashSet()) { it.id }
        val scaffold = instance.config["scaffold"]
        return stack.instances.asSequence()
            .filter { it.enabled && it.type == otherType && it.config["scaffold"] == scaffold }
            .flatMap { it.targets.asSequence() }
            .map { it.id }
            .filterTo(HashSet()) { it in targetIds }
    }

    private fun outfitMeshContext(instance: RigModifierInstance): ModifierAlgorithmContext {
        val targetLayers = instance.targets.mapNotNull { layersByMesh[it.id] }.distinct().associateWith { it }
        val meshes = RigBuilder.scaffoldMeshInputs(analysis, atlas, sourceConfig(), meshCache, targetLayers)
            .resources.getValue("source_meshes")
        return ModifierAlgorithmContext(resources = mapOf("source_meshes" to meshes))
    }

    fun skirtContext(instance: RigModifierInstance): ModifierAlgorithmContext =
        io.github.psd2live.core.modifier.source.RigStaticSourceCollector.skirtSourceInputs(
            analysis, sourceConfig(), meshCache, meshesByLayer, instance.targets.mapTo(HashSet()) { it.id },
            sharedOutfitTargets(instance, "outfit.ribbons"), precedingExpressionIds(instance), outfitMeshContext(instance))

    fun ribbonsContext(instance: RigModifierInstance): ModifierAlgorithmContext =
        io.github.psd2live.core.modifier.source.RigStaticSourceCollector.ribbonsSourceInputs(
            analysis, sourceConfig(), meshCache, meshesByLayer, instance.targets.mapTo(HashSet()) { it.id },
            sharedOutfitTargets(instance, "outfit.skirt"), precedingExpressionIds(instance), outfitMeshContext(instance))

    fun creatureContext(instance: RigModifierInstance): ModifierAlgorithmContext =
        io.github.psd2live.core.modifier.source.RigStaticSourceCollector.creatureSourceInputs(
            analysis, sourceConfig(), meshCache, meshesByLayer, instance.targets.mapTo(HashSet()) { it.id },
            precedingExpressionIds(instance), outfitMeshContext(instance))

    fun workerBindingContext(instance: RigModifierInstance): ModifierAlgorithmContext {
        val spec = RigLegacyBindings.spec(instance.type)
        when {
            spec.expressionToggles -> return expressionContext(instance)
            spec.expressionCheek -> return cheekContext(instance)
            spec.expressionGloom -> return gloomContext(instance)
            spec.type == "eye/fitted" -> return eyeFittedContext(instance)
            spec.type == "eye/legacy" -> return eyeLegacyContext(instance)
            spec.type == "eye.wide" -> return eyeWideContext(instance)
            spec.type == "eye.smile" -> return eyeSmileContext(instance)
            spec.type == "eye.emotion" -> return eyeEmotionContext(instance)
            spec.type == "eye.gaze-lid" -> return eyeGazeLidContext(instance)
            spec.type == "eye.highlight" -> return eyeHighlightContext(instance)
            spec.type == "eye.aftershock" -> return eyeAftershockContext(instance)
            spec.eyeLidFade -> return lidFadeContext(instance)
            spec.eyeSquash -> return eyeSquashContext(instance)
            spec.type == "body/legacy" -> return bodyContext(instance)
            spec.type == "body.bust" -> return bustContext(instance)
            spec.type == "body.arms" -> return armSwingContext(instance)
            spec.type == "face/legacy" -> return faceLegacyContext(instance)
            spec.type == RigLegacyBindings.FITTED_FACE -> return faceFittedContext(instance)
            spec.type == "face.depth-turn" -> return depthTurnContext(instance)
            spec.type == "face.neck" -> return neckContext(instance)
            spec.type == "brow/fitted" -> return browFittedContext(instance)
            spec.type == "brow/legacy" -> return browLegacyContext(instance)
            spec.type == "brow.lid-coupling" -> return browLidContext(instance)
            spec.type == "brow.blink" -> return browBlinkContext(instance)
            spec.browShape -> return browShapeContext(instance)
            spec.browSpring -> return browSpringContext(instance)
            spec.seeThrough -> return seeThroughContext(instance)
            spec.skinClip -> return skinClipContext(instance)
            spec.type == "outfit.skirt" -> return skirtContext(instance)
            spec.type == "outfit.ribbons" -> return ribbonsContext(instance)
            spec.type == "outfit.creature" -> return creatureContext(instance)
            spec.type == "hair/legacy" -> return hairLegacyContext(instance)
            spec.type == "hair/chains" -> return hairChainsContext(instance)
            spec.type == "mouth/fitted" -> return mouthFittedContext(instance)
            spec.type == "mouth/legacy" -> return mouthLegacyContext(instance)
            spec.type == "mouth.outline" -> return mouthOutlineContext(instance)
            spec.type == "mouth.vowel" -> return mouthModuleContext(instance, RigModules.MOUTH_VOWEL)
            spec.type == "mouth.capture" -> return mouthModuleContext(instance, RigModules.MOUTH_CAPTURE)
            spec.type == "mouth.tongue" -> return mouthModuleContext(instance, RigModules.MOUTH_TONGUE)
            spec.type == "mouth.jaw" -> return mouthJawContext(instance)
            spec.type == "mouth.cheek-lid" -> return mouthCheekLidContext(instance)
            spec.type == "mouth.interior-depth" -> return mouthModuleContext(instance, RigModules.MOUTH_DEPTH)
            spec.type == "mouth.spring" -> return mouthModuleContext(instance, RigModules.MOUTH_SPRING)
        }
        error("Unmigrated binding type without worker context: ${instance.type}")
    }

    fun outlineShellSource(context: RigBuilder.RigContext, config: PipelineConfig, layers: Map<String, ClassifiedLayer>,
                           parameters: Set<ParameterId>, includeFaceLean: Boolean = false,
                           projectedMeshes: Set<String> = layers.keys,
                           chains: List<io.github.psd2live.core.fit.HairChain> = emptyList(),
                           parameterAliases: Map<ParameterId, ParameterId> = emptyMap(),
                           includeMouthShading: Boolean = false,
                           includeLayeredTongueKeys: Boolean = false, lowerShapes: Boolean = true): ModifierAlgorithmResource =
        ModifierAlgorithmResource(io.github.psd2live.format.compile.RigIrBinary.encode(PuppetIr.toIr(
            RigBuilder.closedMouthShellProjection(context, config, atlas, meshCache, materialBase, layers, parameters,
                includeFaceLean, chains, includeMouthShading, includeLayeredTongueKeys, lowerShapes)
                .let { projection -> if (parameterAliases.isEmpty()) projection else ModelIdRemap(parameters = parameterAliases).model(projection) }
                .let { projection -> projection.copy(drawables = projection.drawables.filter { it.id.raw in projectedMeshes }) })))
    fun tongueTipSource(context: RigBuilder.RigContext, config: PipelineConfig, layers: Map<String, ClassifiedLayer>): ModifierAlgorithmContext {
        val fitted = requireNotNull(context.fittedMouth(config))
        val measurements = requireNotNull(fitted.source).encode()
        val materials = materialBase.drawables.associateBy { it.id.raw }
        val lower = layers.filterValues { it.semantic.role in setOf(PartRole.LOWER_LIP, PartRole.LOWER_LINE) }
        val lowerOrder = requireNotNull(lower.keys.maxOfOrNull { materials.getValue(it).drawOrder })
        val frames = linkedMapOf<String, JsonArray>()
        val meshes = layers.filterValues { it.semantic.tag == SemanticTag.TONGUE && fitted.covers(it) }.map { (id, layer) ->
            val (drawable, frame) = RigBuilder.tongueTipTopology(layer, context, config, atlas, meshCache, materials.getValue(id))
            frames[id] = JsonArray(listOf(frame.left, frame.top, frame.right, frame.bottom, layer.source.opacity).map(::JsonPrimitive))
            PuppetIr.meshToIr(drawable)
        }
        require(meshes.isNotEmpty()) { "Layered tongue requires source artwork for an unmasked tip" }
        val topology = io.github.psd2live.format.model.RigIR(
            io.github.psd2live.format.model.Canvas(materialBase.canvasWidth, materialBase.canvasHeight), emptyList(), meshes = meshes)
        return ModifierAlgorithmContext(buildJsonObject {
            put("tip_measurements", measurements.values)
            put("tip_frames", JsonObject(frames))
            put("tip_lower_order", lowerOrder)
            put("tip_chin", fitted.chin)
        }, measurements.resources + ("tongue_tips" to ModifierAlgorithmResource(
            io.github.psd2live.format.compile.RigIrBinary.encode(topology))))
    }

    private val staticModels = java.util.IdentityHashMap<PuppetModel, PuppetModel>()
    fun static(model: PuppetModel) = staticModels.getOrPut(model) { restricted(model, emptySet()) }

    private val scaffoldTorsos = HashMap<RigModifierInstance, io.github.psd2live.algorithms.binding.TorsoFrame>()
    private val scaffoldContexts = HashMap<RigModifierInstance, ModifierAlgorithmContext>()
    private val sourceScaffoldContexts = HashMap<RigModifierInstance, ModifierAlgorithmContext>()
    fun eyePartContext(instance: RigModifierInstance): ModifierAlgorithmContext {
        val mode = instance.config["mode"]?.jsonPrimitive?.content ?: "fitted"
        val baseContext = if (mode == "fitted") {
            eyeFittedContext(instance.copy(type = "eye/fitted", version = 3))
        } else {
            eyeLegacyContext(instance.copy(type = "eye/legacy", version = 2))
        }
        val extraResources = LinkedHashMap(baseContext.resources)
        val subContexts = buildJsonObject {
            if (mode == "fitted") {
                val subBuilders = listOf(
                    "wide" to ("eye.wide" to ::eyeWideContext),
                    "smile" to ("eye.smile" to ::eyeSmileContext),
                    "emotion" to ("eye.emotion" to ::eyeEmotionContext),
                    "gazeLid" to ("eye.gaze-lid" to ::eyeGazeLidContext),
                    "highlight" to ("eye.highlight" to ::eyeHighlightContext),
                    "lidFade" to ("eye.lid-fade" to ::lidFadeContext),
                    "squash" to ("eye.squash" to ::eyeSquashContext),
                    "aftershock" to ("eye.aftershock" to ::eyeAftershockContext),
                )
                for ((flag, entry) in subBuilders) {
                    if (instance.config[flag]?.jsonPrimitive?.booleanOrNull == true) {
                        val (subType, builder) = entry
                        val subCtx = builder(instance)
                        put(subType, subCtx.values)
                        for ((resKey, resVal) in subCtx.resources) {
                            extraResources["sub:$subType:$resKey"] = resVal
                        }
                    }
                }
            }
        }
        val values = buildJsonObject {
            put("mode", mode)
            put("base_context", baseContext.values)
            if (subContexts.isNotEmpty()) put("sub_contexts", subContexts)
            put(ModifierParameterI18n.CONTEXT_KEY, io.github.psd2live.core.modifier.source.ModifierParameterContext.i18n(
                mapOf(
                    "ParamMouthForm" to io.github.psd2live.i18n.tr("model.parameter.mouthForm"),
                    "ParamMouthOpenY" to io.github.psd2live.i18n.tr("model.parameter.mouthOpenY"),
                )
            ).toJsonElement())
        }
        return ModifierAlgorithmContext(values, extraResources, baseContext.upstream)
    }

    fun browPartContext(instance: RigModifierInstance): ModifierAlgorithmContext {
        val mode = instance.config["mode"]?.jsonPrimitive?.content ?: "fitted"
        val baseContext = if (mode == "fitted") browFittedContext(instance) else browLegacyContext(instance)
        val extraResources = LinkedHashMap<String, ModifierAlgorithmResource>(baseContext.resources)
        val subContexts = buildJsonObject {
            if (mode == "fitted") {
                val subs = listOf(
                    "shape" to ("brow.shape" to ::browShapeContext),
                    "spring" to ("brow.spring" to ::browSpringContext),
                    "blink" to ("brow.blink" to ::browBlinkContext),
                    "lidCoupling" to ("brow.lid-coupling" to ::browLidContext),
                )
                for ((flag, pair) in subs) {
                    if (instance.config[flag]?.jsonPrimitive?.booleanOrNull == true) {
                        val (subType, provider) = pair
                        val subCtx = provider(instance)
                        put(subType, subCtx.values)
                        for ((resKey, resVal) in subCtx.resources) {
                            extraResources["sub:$subType:$resKey"] = resVal
                        }
                    }
                }
            }
        }
        val values = buildJsonObject {
            put("mode", mode)
            put("base_context", baseContext.values)
            if (subContexts.isNotEmpty()) put("sub_contexts", subContexts)
            put(ModifierParameterI18n.CONTEXT_KEY, io.github.psd2live.core.modifier.source.ModifierParameterContext.i18n(
                mapOf(
                    "ParamMouthForm" to io.github.psd2live.i18n.tr("model.parameter.mouthForm"),
                    "ParamMouthOpenY" to io.github.psd2live.i18n.tr("model.parameter.mouthOpenY"),
                )
            ).toJsonElement())
        }
        return ModifierAlgorithmContext(values, extraResources, baseContext.upstream)
    }

    fun facePartContext(instance: RigModifierInstance): ModifierAlgorithmContext {
        val mode = instance.config["mode"]?.jsonPrimitive?.content ?: "fitted"
        val baseContext = if (mode == "fitted") faceFittedContext(instance) else faceLegacyContext(instance)
        val extraResources = LinkedHashMap<String, ModifierAlgorithmResource>(baseContext.resources)
        val subContexts = buildJsonObject {
            if (mode == "legacy" && instance.config["depthTurn"]?.jsonPrimitive?.booleanOrNull == true) {
                val subCtx = depthTurnContext(instance)
                put("face.depth-turn", subCtx.values)
                for ((resKey, resVal) in subCtx.resources) {
                    extraResources["sub:face.depth-turn:$resKey"] = resVal
                }
            }
            if (instance.config["neck"]?.jsonPrimitive?.booleanOrNull == true) {
                val subCtx = neckContext(instance)
                put("face.neck", subCtx.values)
                for ((resKey, resVal) in subCtx.resources) {
                    extraResources["sub:face.neck:$resKey"] = resVal
                }
            }
        }
        val values = buildJsonObject {
            put("mode", mode)
            put("base_context", baseContext.values)
            if (subContexts.isNotEmpty()) put("sub_contexts", subContexts)
            put(ModifierParameterI18n.CONTEXT_KEY, io.github.psd2live.core.modifier.source.ModifierParameterContext.i18n(
                mapOf(
                    "ParamMouthForm" to io.github.psd2live.i18n.tr("model.parameter.mouthForm"),
                    "ParamMouthOpenY" to io.github.psd2live.i18n.tr("model.parameter.mouthOpenY"),
                )
            ).toJsonElement())
        }
        return ModifierAlgorithmContext(values, extraResources, baseContext.upstream)
    }

    fun bodyPartContext(instance: RigModifierInstance): ModifierAlgorithmContext {
        val baseContext = bodyContext(instance.copy(type = "body/legacy"))
        val extraResources = LinkedHashMap<String, ModifierAlgorithmResource>(baseContext.resources)
        val subContexts = buildJsonObject {
            val subs = listOf(
                "bust" to ("body.bust" to ::bustContext),
                "arms" to ("body.arms" to ::armSwingContext),
            )
            for ((flag, pair) in subs) {
                if (instance.config[flag]?.jsonPrimitive?.booleanOrNull == true) {
                    val (subType, provider) = pair
                    val subCtx = provider(instance)
                    put(subType, subCtx.values)
                    for ((resKey, resVal) in subCtx.resources) {
                        extraResources["sub:$subType:$resKey"] = resVal
                    }
                }
            }
        }
        val values = buildJsonObject {
            put("mode", "legacy")
            put("base_context", baseContext.values)
            if (subContexts.isNotEmpty()) put("sub_contexts", subContexts)
            put(ModifierParameterI18n.CONTEXT_KEY, io.github.psd2live.core.modifier.source.ModifierParameterContext.i18n(
                mapOf(
                    "ParamMouthForm" to io.github.psd2live.i18n.tr("model.parameter.mouthForm"),
                    "ParamMouthOpenY" to io.github.psd2live.i18n.tr("model.parameter.mouthOpenY"),
                )
            ).toJsonElement())
        }
        return ModifierAlgorithmContext(values, extraResources, baseContext.upstream)
    }

    fun hairLegacyParameters(instance: RigModifierInstance): List<io.github.psd2live.format.model.Parameter> {
        val scaffold = provider(instance)
        val frontHairSway = scaffold.config["frontHairSway"]?.jsonPrimitive?.boolean ?: true
        val backHairSway = scaffold.config["backHairSway"]?.jsonPrimitive?.boolean ?: true
        val hasFront = frontHairSway
        val hasBack = backHairSway
        return buildList {
            if (hasFront) add(io.github.psd2live.format.model.Parameter(StandardParameters.HAIR_FRONT.raw, io.github.psd2live.i18n.tr("model.parameter.hairFront"), -1f, 1f, 0f))
            if (hasBack) add(io.github.psd2live.format.model.Parameter(StandardParameters.HAIR_BACK.raw, io.github.psd2live.i18n.tr("model.parameter.hairBack"), -1f, 1f, 0f))
        }
    }

    fun hairLegacyContext(instance: RigModifierInstance): ModifierAlgorithmContext {
        val scaffold = provider(instance)
        val tuning = RigLegacyBindings.tuning(instance.copy(type = "hair/legacy"), RigTuning())
        val frontHairSway = scaffold.config["frontHairSway"]?.jsonPrimitive?.boolean ?: true
        val backHairSway = scaffold.config["backHairSway"]?.jsonPrimitive?.boolean ?: true
        val earlierBinding = activeBefore(instance).any { it.type == "hair/legacy" || it.type == "hair" }
        val targetLayers = instance.targets.mapNotNull { t -> layersByMesh[t.id]?.let { t.id to it } }.toMap()
        return io.github.psd2live.core.modifier.source.RigStaticSourceCollector.hairLegacySourceInputs(
            analysis, sourceConfig().copy(rigTuning = tuning), meshCache, materialBase, targetLayers, primaryByLayer,
            { targetId, canonical -> RigSourceModifiers.deformerId(scaffold, targetId, canonical) },
            earlierBinding, frontHairSway, backHairSway,
        )
    }

    fun hairChainsContext(instance: RigModifierInstance): ModifierAlgorithmContext {
        val scaffold = provider(instance)
        val frontHairSway = scaffold.config["frontHairSway"]?.jsonPrimitive?.boolean ?: true
        val backHairSway = scaffold.config["backHairSway"]?.jsonPrimitive?.boolean ?: true
        return io.github.psd2live.core.modifier.source.RigStaticSourceCollector.hairChainsSourceInputs(
            analysis, sourceConfig(), meshCache, meshesByLayer, instance.targets.mapTo(HashSet()) { it.id },
            frontHairSway, backHairSway, precedingExpressionIds(instance),
        )
    }

    fun hairPartContext(instance: RigModifierInstance): ModifierAlgorithmContext {
        val mode = instance.config["mode"]?.jsonPrimitive?.content ?: "chains"
        val baseContext = if (mode == "chains") hairChainsContext(instance) else hairLegacyContext(instance)
        val values = buildJsonObject {
            put("mode", mode)
            put("base_context", baseContext.values)
            put(ModifierParameterI18n.CONTEXT_KEY, io.github.psd2live.core.modifier.source.ModifierParameterContext.i18n(
                mapOf(
                    "ParamMouthForm" to io.github.psd2live.i18n.tr("model.parameter.mouthForm"),
                    "ParamMouthOpenY" to io.github.psd2live.i18n.tr("model.parameter.mouthOpenY"),
                )
            ).toJsonElement())
        }
        return ModifierAlgorithmContext(values, baseContext.resources, baseContext.upstream)
    }

    fun mouthFittedContext(instance: RigModifierInstance): ModifierAlgorithmContext {
        val scaffold = provider(instance)
        val preceding = activeBefore(instance)
        val precedingWithFitted = if (preceding.any { it.type == "mouth/fitted" || it.type == "mouth" }) preceding
            else preceding + instance.copy(type = "mouth/fitted")
        val (native, context) = RigFittedMouthModuleSource.context(this, scaffold, precedingWithFitted, activeModule = null, neutralScaffold = true)
        val targetLayers = instance.targets.mapNotNull { t -> layersByMesh[t.id]?.let { layersById[it] }?.let { t.id to it } }.toMap()
        return RigFittedMouthModuleSource.measure(this, native, context, precedingWithFitted, targetLayers)
    }

    fun mouthLegacyContext(instance: RigModifierInstance): ModifierAlgorithmContext {
        val scaffold = provider(instance)
        val targetLayers = instance.targets.mapNotNull { t -> layersByMesh[t.id]?.let { layersById[it] }?.let { t.id to it } }.toMap()
        return io.github.psd2live.core.modifier.source.RigStaticSourceCollector.mouthLegacySourceInputs(
            analysis, sourceConfig(), meshCache, targetLayers, primaryByLayer, sourceScaffoldContext(scaffold),
        )
    }

    fun mouthOutlineContext(instance: RigModifierInstance): ModifierAlgorithmContext {
        val scaffold = provider(instance)
        val preceding = activeBefore(instance)
        val fittedProvider = preceding.lastOrNull {
            (it.type == "mouth/fitted" || it.type == "mouth") && it.config["scaffold"] == instance.config["scaffold"]
        }?.id
        val headProvider = preceding.lastOrNull {
            (it.type in setOf("face/fitted", "face.depth-turn") ||
                (it.type == "face" && ((it.config["mode"]?.jsonPrimitive?.content ?: "fitted") == "fitted" ||
                    it.config["depthTurn"]?.jsonPrimitive?.booleanOrNull == true))) &&
                it.config["scaffold"] == instance.config["scaffold"]
        }?.id
        val targetLayers = instance.targets.mapNotNull { t -> layersByMesh[t.id]?.let { layersById[it] }?.let { t.id to it } }.toMap()
        val owners = targetLayers.mapNotNull { (id, layer) ->
            if (layer.semantic.tag !in setOf(SemanticTag.MOUTH, SemanticTag.MOUTH_OPEN) || layer.semantic.role != null) null
            else id to RigMouthOutlineSource.Owner(layer.source.id.raw, id, fittedProvider = fittedProvider, headOnMesh = headProvider != null)
        }.toMap()
        val thickness = instance.config["values"]?.jsonObject?.get("thickness")?.jsonPrimitive?.floatOrNull ?: 1.5f
        val color = instance.config["color"]?.jsonPrimitive?.intOrNull
        val outlineConfig = activeRecipeConfig(instance).copy(mouthOutlineEnabled = true, mouthThickness = thickness, mouthColor = color)
        val head = headProvider?.let { providerId ->
            val outlineRigContext = RigBuilder.rigContext(analysis, outlineConfig, meshCache)
            io.github.psd2live.core.RigFittedFaceSource.headTurn(outlineRigContext, outlineConfig)?.let { turn ->
                io.github.psd2live.algorithms.binding.MouthFittedInputs.Head(
                    providerId, turn.centerX, turn.centerY, turn.radiusX, turn.radiusY, turn.turn, turn.depth, turn.chinY,
                )
            }
        }
        return RigMouthOutlineSource.capture(analysis, outlineConfig, owners, head = head, cache = meshCache).encode()
    }

    fun mouthModuleContext(instance: RigModifierInstance, module: RigModule): ModifierAlgorithmContext {
        val scaffold = provider(instance)
        val preceding = activeBefore(instance)
        val extraModules = buildList {
            if (preceding.none { it.type == "mouth/fitted" || it.type == "mouth" }) {
                add(instance.copy(type = "mouth/fitted", config = JsonObject(instance.config - "values" - "tuning")))
            }
            if (module == RigModules.MOUTH_TONGUE &&
                instance.config["capture"]?.jsonPrimitive?.booleanOrNull == true &&
                preceding.none { it.type == RigModules.MOUTH_CAPTURE.id }) {
                add(instance.copy(type = RigModules.MOUTH_CAPTURE.id, config = JsonObject(instance.config - "values" - "tuning")))
            }
            if (module == RigModules.MOUTH_SPRING &&
                instance.config["jaw"]?.jsonPrimitive?.booleanOrNull == true &&
                preceding.none { it.type == RigModules.MOUTH_JAW.id }) {
                add(instance.copy(type = RigModules.MOUTH_JAW.id, config = JsonObject(instance.config - "values" - "tuning")))
            }
        }
        val precedingWithFitted = preceding + extraModules
        val (native, context) = RigFittedMouthModuleSource.context(this, scaffold, precedingWithFitted, activeModule = module, neutralScaffold = true)
        val allTargetLayers = instance.targets.mapNotNull { t -> layersByMesh[t.id]?.let { layersById[it] }?.let { t.id to it } }.toMap()
        val fitted = context.fittedMouth(native)
        val rig = fitted?.rig
        val hasSeparateMouth = fitted?.result?.layers?.any { it.semantic.tag == SemanticTag.MOUTH } == true
        val lippedSkin = allTargetLayers.entries.mapNotNullTo(linkedSetOf()) { (id, layer) ->
            if (fitted != null && rig != null && layer.semantic.tag == SemanticTag.FACE &&
                layer.semantic.role == null && fitted.lipped && !rig.fit.hasLines) id else null
        }
        val targetLayers = when (module) {
            RigModules.MOUTH_TONGUE -> allTargetLayers.filter { (_, layer) -> fitted != null && fitted.covers(layer) }
            RigModules.MOUTH_VOWEL, RigModules.MOUTH_CAPTURE -> allTargetLayers.filter { (id, layer) ->
                (fitted != null && fitted.covers(layer)) || id in lippedSkin
            }
            RigModules.MOUTH_SPRING -> allTargetLayers.filter { (id, _) -> id in lippedSkin }
            RigModules.MOUTH_DEPTH -> allTargetLayers.filter { (_, layer) ->
                (fitted != null && fitted.covers(layer)) ||
                    (layer.semantic.tag == SemanticTag.MOUTH_OPEN && layer.semantic.role == null && hasSeparateMouth)
            }
            else -> allTargetLayers
        }
        val inner = RigFittedMouthModuleSource.measure(this, native, context, precedingWithFitted, targetLayers)
        val folder = io.github.psd2live.i18n.tr("model.group.mouth")
        val followsOrNull = targetLayers.mapValues { (_, layer) ->
            if (fitted != null && fitted.covers(layer)) fitted.follow(layer) else null
        }
        return when (module) {
            RigModules.MOUTH_VOWEL -> io.github.psd2live.algorithms.binding.MouthVowelAlgorithm.context(
                inner, followsOrNull, module.defines.associate { it.id to io.github.psd2live.i18n.tr(it.nameKey) }, folder)
            RigModules.MOUTH_CAPTURE -> io.github.psd2live.algorithms.binding.MouthCaptureAlgorithm.context(
                inner, followsOrNull, lippedSkin, module.defines.associate { it.id to io.github.psd2live.i18n.tr(it.nameKey) }, folder)
            RigModules.MOUTH_TONGUE -> io.github.psd2live.algorithms.binding.MouthTongueAlgorithm.context(
                inner, targetLayers.mapValues { (_, layer) -> requireNotNull(fitted).follow(layer) },
                io.github.psd2live.i18n.tr(module.defines.single().nameKey), folder)
            RigModules.MOUTH_SPRING -> io.github.psd2live.algorithms.binding.MouthSpringAlgorithm.context(
                inner, lippedSkin, io.github.psd2live.i18n.tr(module.defines.single().nameKey), folder, io.github.psd2live.i18n.tr("model.physics.mouthSpring"))
            RigModules.MOUTH_DEPTH -> ModifierAlgorithmContext(buildJsonObject {
                put("schema", "mouth-depth/1")
                put("source", inner.values)
                put("interior", JsonObject(targetLayers.mapValues { (_, layer) ->
                    val follow = fitted?.follow(layer)
                    val depth = when (follow) {
                        io.github.psd2live.algorithms.binding.MouthRig.Follow.UPPER_TEETH -> 0.06f
                        io.github.psd2live.algorithms.binding.MouthRig.Follow.LOWER_TEETH -> 0.1f
                        io.github.psd2live.algorithms.binding.MouthRig.Follow.TONGUE -> 0.16f
                        io.github.psd2live.algorithms.binding.MouthRig.Follow.DEEP -> 0.2f
                        io.github.psd2live.algorithms.binding.MouthRig.Follow.INSIDE,
                        io.github.psd2live.algorithms.binding.MouthRig.Follow.OPENING -> 0f
                        else -> if (layer.semantic.tag == SemanticTag.MOUTH_OPEN && hasSeparateMouth) 0.12f else 0f
                    }
                    val shade = follow == io.github.psd2live.algorithms.binding.MouthRig.Follow.TONGUE ||
                        follow == io.github.psd2live.algorithms.binding.MouthRig.Follow.DEEP ||
                        follow == io.github.psd2live.algorithms.binding.MouthRig.Follow.INSIDE ||
                        follow == io.github.psd2live.algorithms.binding.MouthRig.Follow.OPENING ||
                        (layer.semantic.tag == SemanticTag.MOUTH_OPEN && layer.semantic.role == null && hasSeparateMouth)
                    buildJsonObject { put("depth", depth); put("shade", shade) }
                }))
            }, inner.resources)
            else -> ModifierAlgorithmContext(buildJsonObject { put("source", inner.values) }, inner.resources)
        }
    }

    fun mouthCheekLidContext(instance: RigModifierInstance): ModifierAlgorithmContext {
        val targetLayers = instance.targets.mapNotNull { t -> layersByMesh[t.id]?.let { t.id to it } }.toMap()
        val names = eyeModuleParameterNames(io.github.psd2live.core.recipe.RigModules.MOUTH_CHEEK_LID)
        return io.github.psd2live.core.modifier.source.RigStaticSourceCollector.mouthCheekLidSourceInputs(
            analysis, atlas, eyeConfig(instance), meshCache, targetLayers, names, eyeSubParentId(instance),
        )
    }

    fun mouthJawContext(instance: RigModifierInstance): ModifierAlgorithmContext {
        val cfg = activeRecipeConfig(instance)
        val rig = RigBuilder.rigContext(analysis, cfg, meshCache, neutralScaffold = true).fittedMouth(cfg)?.rig
        val targetLayers = instance.targets.mapNotNull { t -> layersByMesh[t.id]?.let { layersById[it] }?.let { t.id to it } }
            .filter { (_, layer) ->
                val tag = layer.semantic.tag
                tag == SemanticTag.FACE || tag == SemanticTag.NOSE ||
                    (tag == SemanticTag.FACE_DETAIL && rig != null && rig.fit.toMouth(layer.centroidX, layer.centroidY).second > 0f)
            }.toMap()
        val scaffold = instance.config["scaffold"]
        val preceding = activeBefore(instance).filter { it.config["scaffold"] == scaffold }
        val providers = linkedMapOf<String, String>()
        for (p in preceding) {
            if (p.type in io.github.psd2live.algorithms.binding.MouthJawAlgorithm.optionalUpstream) {
                providers[p.type] = p.id
            }
        }
        return io.github.psd2live.core.modifier.source.RigStaticSourceCollector.mouthJawSourceInputs(
            analysis, atlas, cfg, meshCache, targetLayers, primaryByLayer, providers,
        )
    }

    fun mouthPartContext(instance: RigModifierInstance): ModifierAlgorithmContext {
        val mode = instance.config["mode"]?.jsonPrimitive?.content ?: "fitted"
        val baseContext = if (mode == "fitted") mouthFittedContext(instance) else mouthLegacyContext(instance)
        val subFlagToModule = listOf(
            "outline" to (RigModules.MOUTH_OUTLINE.id to ::mouthOutlineContext),
            "vowel" to (RigModules.MOUTH_VOWEL.id to { i: RigModifierInstance -> mouthModuleContext(i, RigModules.MOUTH_VOWEL) }),
            "capture" to (RigModules.MOUTH_CAPTURE.id to { i: RigModifierInstance -> mouthModuleContext(i, RigModules.MOUTH_CAPTURE) }),
            "tongue" to (RigModules.MOUTH_TONGUE.id to { i: RigModifierInstance -> mouthModuleContext(i, RigModules.MOUTH_TONGUE) }),
            "jaw" to (RigModules.MOUTH_JAW.id to ::mouthJawContext),
            "cheekLid" to (RigModules.MOUTH_CHEEK_LID.id to ::mouthCheekLidContext),
            "depth" to (RigModules.MOUTH_DEPTH.id to { i: RigModifierInstance -> mouthModuleContext(i, RigModules.MOUTH_DEPTH) }),
            "spring" to (RigModules.MOUTH_SPRING.id to { i: RigModifierInstance -> mouthModuleContext(i, RigModules.MOUTH_SPRING) }),
        )
        val subConfigs = instance.config["subConfigs"]?.jsonObject.orEmpty()
        val resources = LinkedHashMap<String, ModifierAlgorithmResource>(baseContext.resources)
        val subContexts = buildJsonObject {
            for ((flag, entry) in subFlagToModule) {
                if (instance.config[flag]?.jsonPrimitive?.booleanOrNull == true) {
                    val (subType, builder) = entry
                    val subCfgObj = subConfigs[subType]?.jsonObject
                    val subTargets = subCfgObj?.get("targets")?.jsonArray?.map {
                        kotlinx.serialization.json.Json.decodeFromJsonElement(ModifierTarget.serializer(), it)
                    } ?: instance.targets
                    val subInstanceConfig = buildJsonObject {
                        instance.config.forEach { (k, v) -> if (k != "values" && k != "tuning") put(k, v) }
                        subCfgObj?.get("values")?.let { put("values", it) }
                        subCfgObj?.get("tuning")?.let { put("tuning", it) }
                        subCfgObj?.get("color")?.let { put("color", it) }
                    }
                    val subCtx = builder(instance.copy(type = subType, targets = subTargets, config = subInstanceConfig))
                    put(subType, subCtx.values)
                    for ((resKey, resVal) in subCtx.resources) {
                        resources["sub:$subType:$resKey"] = resVal
                    }
                }
            }
        }
        val values = buildJsonObject {
            put("mode", mode)
            put("base_context", baseContext.values)
            if (subContexts.isNotEmpty()) put("sub_contexts", subContexts)
            if (subConfigs.isNotEmpty()) put("sub_configs", JsonObject(subConfigs))
            put(ModifierParameterI18n.CONTEXT_KEY, io.github.psd2live.core.modifier.source.ModifierParameterContext.i18n(
                mapOf(
                    "ParamMouthForm" to io.github.psd2live.i18n.tr("model.parameter.mouthForm"),
                    "ParamMouthOpenY" to io.github.psd2live.i18n.tr("model.parameter.mouthOpenY"),
                )
            ).toJsonElement())
        }
        return ModifierAlgorithmContext(values, resources, baseContext.upstream)
    }

    fun partParameterContext(instance: RigModifierInstance, defaultMode: String): ModifierAlgorithmContext =
        ModifierAlgorithmContext(buildJsonObject {
            put("mode", instance.config["mode"]?.jsonPrimitive?.content ?: defaultMode)
            put(ModifierParameterI18n.CONTEXT_KEY, io.github.psd2live.core.modifier.source.ModifierParameterContext.i18n(
                mapOf(
                    "ParamMouthForm" to io.github.psd2live.i18n.tr("model.parameter.mouthForm"),
                    "ParamMouthOpenY" to io.github.psd2live.i18n.tr("model.parameter.mouthOpenY"),
                )
            ).toJsonElement())
        })

    fun scaffoldContext(instance: RigModifierInstance): ModifierAlgorithmContext = scaffoldContexts.getOrPut(instance) {
        val source = sourceScaffoldContext(instance)
        val pinned = instance.plugin?.takeIf { it.id.startsWith("builtin.") }
            ?.let { ModifierPluginVersion.parse(it.version, legacy = true) }
        if (pinned == null || pinned >= ModifierPluginVersion.parse("1.0.41")) source
        else {
            val canonical = source.values.getValue("canonical_targets").jsonObject.mapValues { it.value.jsonPrimitive.content }
            val template = scaffold(instance) {}
            // Old packages received the material drawables in their native order.
            val order = materialBase.drawables.mapIndexed { index, drawable -> drawable.id to index }.toMap()
            val nativeOrder = template.drawables.sortedBy { order[it.id] ?: Int.MAX_VALUE }
            ScaffoldAlgorithm.capture(template.copy(drawables = nativeOrder), materialBase, canonical,
                source.values.getValue("organization").jsonObject.takeIf { pinned >= ModifierPluginVersion.parse("1.0.37") },
                source.values.getValue("frames").jsonObject.takeIf { pinned >= ModifierPluginVersion.parse("1.0.38") },
                source.values.getValue("branches").jsonObject.takeIf { pinned >= ModifierPluginVersion.parse("1.0.40") })
        }
    }

    private fun sourceScaffoldContext(instance: RigModifierInstance): ModifierAlgorithmContext =
        sourceScaffoldContexts.getOrPut(instance) {
            val organization = ScaffoldPartsContext.capture(analysis.layers.mapNotNull { layer ->
                primaryByLayer[layer.source.id.raw]?.let { id ->
                    ScaffoldParts.Material(DrawableId(id), RigBuilder.scaffoldSlot(layer, analysis.anchors), layer.source.order)
                }
            }, label = { io.github.psd2live.i18n.tr(it) })
            val options = instance.config
            val corrections = io.github.psd2live.project.WorkspaceSettingsCodec.decodeRecipe(options["corrections"])
            val native = sourceConfig().copy(
                bodyStrength = 1f, headTurnStrength = 1f, rigTuning = RigTuning(),
                recipe = RigRecipe.Legacy.copy(moves = corrections.moves, eyeParts = corrections.eyeParts),
                featureDisplacementEnabled = options["featureDisplacement"]?.jsonPrimitive?.boolean ?: false,
                hairSimulationFront = !(options["frontHairSway"]?.jsonPrimitive?.boolean ?: true),
                hairSimulationBack = !(options["backHairSway"]?.jsonPrimitive?.boolean ?: true),
            )
            val structure = RigBuilder.scaffoldSourceInputs(analysis, native, meshCache)
            scaffoldTorsos[instance] = structure.torso
            val sources = instance.targets.associate { target ->
                val layer = layersByMesh.getValue(target.id)
                layer to primaryByLayer.getValue(layer)
            }
            val canonical = instance.targets.associate { it.id to sources.getValue(layersByMesh.getValue(it.id)) }
            ScaffoldAlgorithm.captureSource(materialBase, canonical, organization, structure.frames, structure.branches,
                RigBuilder.scaffoldMeshInputs(analysis, atlas, native, meshCache, sources))
        }

    private fun templateKey(state: Map<String, RigModifierInstance>, scaffold: JsonObject): Map<String, JsonObject> {
        val key = state.mapValues { (_, instance) -> buildJsonObject {
            // This is an intermediate geometry template, not a modifier-result cache. Pendulums are evaluated separately.
            put("version", instance.version); put("config", JsonObject(instance.config - "scaffold" - "base" - "physics"))
            instance.plugin?.let { put("plugin", Json.encodeToJsonElement(ModifierPluginReference.serializer(), it)) }
        } }
        return key + ("rig.scaffold" to scaffold)
    }

    private fun template(instances: List<RigModifierInstance>, scaffold: JsonObject,
                         requester: String = instances.last().type): Template {
        RigBuildProfile.count("modifier legacy template requests")
        val state = RigSourceBindingState(instances)
        val key = templateKey(state.byType, scaffold)
        return templates.getOrPut(key) {
            // Preview bitmaps and encoded atlas pages are presentation objects recreated on rebuild.
            // Geometry reads classified source pixels and atlas placement, not those objects' identity.
            val sharedKey = listOf(analysis.source, analysis.layers, analysis.anchors, analysis.warnings, analysis.calibration,
                atlas.placementByLayerId, atlas.pages.map { it.image.width to it.image.height }, atlas.footprints,
                RigModifierPipeline.ordinaryConfig(config), primaryByLayer, key, io.github.psd2live.i18n.I18n.currentLanguage)
            val build = { buildTemplate(instances, scaffold, state, requester) }
            if (RigStageCache.enabled) meshCache.stages.get("modifier geometry template", 64, sharedKey, build) else build()
        }
    }

    private fun buildTemplate(instances: List<RigModifierInstance>, scaffold: JsonObject, state: RigSourceBindingState,
                              requester: String = instances.last().type): Template {
        val kept = state.byType.keys.flatMapTo(HashSet()) { RigLegacyBindings.spec(it).parameters }
        val native = RigBuildProfile.stage("modifier legacy template: $requester") {
            generated(nativeBindingConfig(instances, scaffold, state))
        }
        val chains = native.generatedChains.filter { chain -> instances.any { chain.kind in RigModifierChains.kinds(it.type) } }
            .map { RigModifierChains.Binding(it) }
        return finishTemplate(native.puppet, chains, kept, state.byArea.keys)
    }

    private fun nativeBindingConfig(instances: List<RigModifierInstance>, scaffold: JsonObject,
                                    state: RigSourceBindingState = RigSourceBindingState(instances)): PipelineConfig {
        val byArea = state.byArea
        val tuning = state.tuning
        val bodyStrength = state.bodyStrength
        val headStrength = state.headStrength
        val recipe = if (instances.none { !it.type.endsWith("/legacy") }) RigRecipe.Legacy else RigRecipe(true,
            bases = RigModules.areas.associate { area ->
                val chosen = byArea[area.id]
                val base = chosen?.type?.takeUnless { it == RigLegacyBindings.FITTED_FACE } ?: area.legacy.id
                area.id to BaseChoice(base, chosen?.version ?: area.legacy.version, chosen?.let(RigLegacyBindings::values).orEmpty())
            }, modules = RigModules.modules.associate { module ->
                val chosen = state.byType[module.id] ?: state.byType[RigLegacyBindings.FITTED_FACE]?.takeIf { module.id == RigModules.FACE_DEPTH_TURN.id }
                module.id to ModuleChoice(chosen != null, chosen?.version ?: module.version, chosen?.let(RigLegacyBindings::values).orEmpty())
            })
        val outline = state.byType[RigModules.MOUTH_OUTLINE.id]
        val corrections = io.github.psd2live.project.WorkspaceSettingsCodec.decodeRecipe(scaffold["corrections"])
        val correctedRecipe = recipe.copy(moves = corrections.moves, eyeParts = corrections.eyeParts)
        return generationConfig(bodyStrength, tuning, headStrength, correctedRecipe, outline,
            scaffold["featureDisplacement"]?.jsonPrimitive?.boolean ?: false,
            scaffold["frontHairSway"]?.jsonPrimitive?.boolean ?: true, scaffold["backHairSway"]?.jsonPrimitive?.boolean ?: true)
    }

    fun captureAttachmentResidual(instance: RigModifierInstance, affected: Set<String>, namespace: String,
                                  base: ModifierAlgorithmContext): ModifierAlgorithmContext {
        val targets = instance.targets.filter { it.id in affected }
        val grouped = targets.groupBy { relevantBefore(instance, it) }
        val resources = base.resources.toMutableMap()
        for ((suffix, active) in listOf("before" to false, "after" to true)) {
            for ((native, static) in attachmentProjection(instance, grouped, active)) {
                val id = native.drawables.single().id.raw
                resources["$namespace/$id/$suffix"] = ModifierAlgorithmResource(RigIrBinary.encode(PuppetIr.toIr(native)))
                resources["$namespace/$id/$suffix-static"] = ModifierAlgorithmResource(RigIrBinary.encode(PuppetIr.toIr(static)))
            }
        }
        return ModifierAlgorithmContext(base.values, resources)
    }

    private fun attachmentProjection(instance: RigModifierInstance,
                                     grouped: Map<List<RigModifierInstance>, List<ModifierTarget>>,
                                     active: Boolean): List<Pair<PuppetModel, PuppetModel>> {
        val scaffold = provider(instance)
        return grouped.flatMap { (preceding, selected) ->
            val items = if (active) preceding + instance else preceding
            val config = nativeBindingConfig(items, scaffold.config)
            val sources = selected.associate { target -> layersByMesh.getValue(target.id) to DrawableId(target.id) }
            require(sources.size == selected.size) { "${instance.type} source requires one mesh per drawing" }
            val (native, generatedChains) = RigBuilder.attachmentBindingSource(analysis, atlas, config, meshCache, sources)
            val chains = generatedChains.filter { chain -> items.any { chain.kind in RigModifierChains.kinds(it.type) } }
                .map(RigModifierChains::Binding)
            val kept = items.flatMapTo(hashSetOf()) { RigLegacyBindings.spec(it.type).parameters } + chains.flatMap { it.native.parameters }
            val normalized = RigModifierChains.normalize(restricted(native, kept), chains)
            val cache = HashMap<List<RigModifierChains.Owned>, RigModifierChains.Projection>()
            selected.map { target ->
                val projected = RigModifierChains.projection(normalized, target, items, chains, cache, ::restricted).model
                val mesh = projected.drawables.single { it.id.raw == target.id }
                fun scoped(model: PuppetModel): PuppetModel {
                    val frames = model.deformers.associateBy { it.id }
                    val branch = generateSequence(model.drawables.single { it.id == mesh.id }.parentDeformerId) { frames.getValue(it).parent }.toSet()
                    return model.copy(drawables = model.drawables.filter { it.id == mesh.id }, deformers = model.deformers.filter { it.id in branch })
                }
                scoped(projected) to scoped(static(projected))
            }
        }
    }

    private val neutralModels = HashMap<JsonObject, PuppetModel>()

    /** The predecessor of a first legacy binding is the independent scaffold at its material rest pose. */
    private fun neutralInput(provider: RigModifierInstance): PuppetModel = neutralModels.getOrPut(provider.config) {
        val targets = primaryByLayer.values.map { ModifierTarget("mesh", it) }
        val structure = scaffold(provider.copy(targets = targets, plugin = null)) {}
        val geometry = structure.drawables.associateBy { it.id }
        val expression = ExpressionChannels.catalog(expressionLayers.values.toList(), staggered = false)
        val standard = StandardParameters.all
        val standardIds = standard.mapTo(HashSet()) { it.id }
        val model = materialBase.copy(
            parameters = standard + expression.parameters.filterNot { it.id in standardIds },
            deformers = structure.deformers, parts = structure.parts, rootChildren = structure.rootChildren,
            rootPartId = structure.rootPartId,
            drawables = materialBase.drawables.mapNotNull { material ->
                val bound = geometry[material.id] ?: return@mapNotNull null
                val opacity = expressionLayers[layersByMesh[material.id.raw]]?.let { ExpressionChannels.opacity(it, expression.switchKeys) }
                material.copy(mesh = bound.mesh, parentDeformerId = bound.parentDeformerId, geometryGrid = bound.geometryGrid,
                    channelGrids = opacity?.let { ChannelGrids(mapOf(FormChannel.OPACITY to it)) } ?: ChannelGrids.Empty)
            },
        ).withDerivedRenderRoot()
        finishTemplate(model, emptyList(), emptySet(), emptySet()).model
    }

    private fun finishTemplate(input: PuppetModel, chains: List<RigModifierChains.Binding>, kept: Set<ParameterId>,
                               areas: Set<String>): Template {
        val dynamic = chains.flatMap { it.native.parameters }
        val model = RigModifierChains.normalize(restricted(input, kept + dynamic), chains)
        val clips = sourceClips
        return Template(model.copy(drawables = model.drawables.map { mesh ->
            val layerId = layersByMesh[mesh.id.raw]
            val layer = layersById[layerId]
            val eye = layer?.semantic?.tag in setOf(SemanticTag.IRIDES, SemanticTag.EYEWHITE, SemanticTag.EYELASH, SemanticTag.EYE_CLOSE)
            val mouth = layer?.semantic?.tag in CharacterAnalyzer.MOUTH_COMPONENT_TAGS + setOf(SemanticTag.MOUTH, SemanticTag.MOUTH_OPEN)
            val active = eye && "eye" in areas || mouth && "mouth" in areas
            mesh.copy(maskedBy = if ((eye || mouth) && !active) listOfNotNull(clips[layerId]) else mesh.maskedBy,
                channelGrids = if ((eye || mouth) && !active) ChannelGrids.Empty else mesh.channelGrids,
                opacity = if ((eye || mouth) && !active) layer?.source?.opacity ?: mesh.opacity else mesh.opacity)
        }), chains)
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
        val appliedIds = appliedCapabilityIds
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
        val scaffold = provider(instance)
        fun projected(items: List<RigModifierInstance>): RigModifierChains.Projection {
            if (items.isEmpty()) return RigModifierChains.Projection(forTarget(neutralInput(scaffold), target), emptyList())
            val template = template(items, scaffold.config, instance.type)
            val model = forTarget(template.model, target)
            return RigModifierChains.projection(model, target, items, template.chains,
                chainProjections.getOrPut(model) { HashMap() }, ::restricted)
        }
        return projected(relevant) to projected(relevant + instance)
    }

    private val activePrefixes = HashMap<String, List<RigModifierInstance>>()

    /** Mirror ordered dependency eligibility, so a paused module cannot leak into a later source transition. */
    internal fun activeBefore(instance: RigModifierInstance): List<RigModifierInstance> = activePrefixes.getOrPut(instance.id) {
        val available = stack.appliedCapabilities.toMutableList()
        val active = stack.appliedCapabilities.filter { it.id != null }.mapTo(ArrayList()) {
            RigModifierInstance(requireNotNull(it.id), it.type, it.version, it.targets, config = it.config,
                outputAliases = it.outputAliases, plugin = it.plugin)
        }
        for (previous in stack.instances.take(stack.index(instance.id))) {
            if (!previous.enabled) continue
            val spec = RigLegacyBindings.find(previous.type)
            val providers = if (spec == null) emptyMap() else RigLegacyBindings.providers(previous)
            val missing = spec?.requires.orEmpty().any { type -> previous.targets.any { target ->
                available.none { RigModifierAlgorithms.registry.provides(it, type) && target in it.targets && (providers[type] == null || it.id == providers[type]) }
            } }
            if (missing) continue
            active += previous
            available += previous.capability()
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

    private data class PartHierarchyIndex(val parents: Map<PartId, PartId>, val deformers: Map<DeformerId, Deformer>)
    // Source-owned scaffold and template topology is replaced by model copies, never edited in place.
    private val partHierarchyIndexes = java.util.IdentityHashMap<PuppetModel, PartHierarchyIndex>()

    fun requiredParts(model: PuppetModel, target: ModifierTarget): Set<PartId> {
        val hierarchy = partHierarchyIndexes.getOrPut(model) {
            PartHierarchyIndex(
                model.parts.flatMap { part -> part.children.filterIsInstance<OrgChild.Part>().map { it.id to part.id } }.toMap(),
                model.deformers.associateBy { it.id })
        }
        val parents = hierarchy.parents
        val deformers = hierarchy.deformers
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
        val widening = instance.type == "eye.wide" || instance.type == "psd2live.native.core/eye-wide" ||
            (instance.type == RigSourceModifiers.EYE && instance.config["wide"]?.jsonPrimitive?.booleanOrNull == true)
        val inherited = activeBefore(instance).any {
            it.type == "eye.wide" || it.type == "psd2live.native.core/eye-wide" ||
                (it.type == RigSourceModifiers.EYE && it.config["wide"]?.jsonPrimitive?.booleanOrNull == true)
        }
        if (!widening && (!inherited || before.max < declared.max)) return null
        if (before.max < 1f || declared.max < 1f) return null
        return before.copy(max = maxOf(before.max, declared.max))
    }

    private fun restricted(input: PuppetModel, kept: Set<ParameterId>): PuppetModel =
        // The source mouth is open; removing its binding retains the artwork pose at 1.
        io.github.psd2live.algorithms.binding.BindingProjection.retain(input, kept, mapOf(StandardParameters.MOUTH_OPEN to 1f))

    private val sourceScaffolds = HashMap<RigModifierInstance, PuppetModel>()
    private fun scaffold(instance: RigModifierInstance, checkpoint: () -> Unit): PuppetModel {
        checkpoint()
        val result = sourceScaffolds.getOrPut(instance) {
            ScaffoldAlgorithm().buildSource(sourceScaffoldContext(instance), checkpoint)
        }
        checkpoint()
        return result
    }

    fun scaffoldFor(instance: RigModifierInstance, target: ModifierTarget, checkpoint: () -> Unit) = forTarget(scaffold(instance, checkpoint), target)

    private fun forTarget(model: PuppetModel, target: ModifierTarget): PuppetModel {
        val canonical = primaryByLayer.getValue(layersByMesh.getValue(target.id))
        if (canonical == target.id) return model
        return projections.getOrPut(model) { HashMap() }.getOrPut(target.id) {
            ModelIdRemap(drawables = mapOf(DrawableId(canonical) to DrawableId(target.id))).model(model)
        }
    }

    fun hasMesh(id: String) = id in layersByMesh
    fun hasLayer(id: String) = id in layersById

    fun provider(instance: RigModifierInstance): RigModifierInstance {
        val id = instance.config.getValue("scaffold").jsonPrimitive.content
        val position = stack.instances.indexOfFirst { it.id == instance.id }
        require(position >= 0 || stack.appliedCapabilities.any { it.id == instance.id && it.type == instance.type && it.version == instance.version }) {
            "Unknown modifier: ${instance.id}"
        }
        val preceding = stack.instances.take(position.coerceAtLeast(0)).singleOrNull { it.id == id && it.enabled && it.type == RigSourceModifiers.SCAFFOLD }
        val applied = stack.appliedCapabilities.singleOrNull { it.id == id && it.type == RigSourceModifiers.SCAFFOLD }
        val targets = preceding?.targets ?: requireNotNull(applied) { "${instance.id}: binding scaffold $id must precede this modifier" }.targets
        require(instance.targets.all { it in targets }) { "${instance.id}: scaffold $id does not cover all captured targets" }
        val aliases = preceding?.outputAliases ?: requireNotNull(applied).outputAliases
        require((preceding?.version ?: applied?.version ?: 1) < 2 && aliases.keys.none { it.startsWith("shared/deformer:") } || instance.targets.toSet() == targets.toSet()) {
            "${instance.id}: shared scaffold $id requires its complete target scope"
        }
        return preceding ?: RigModifierInstance(id, RigSourceModifiers.SCAFFOLD, requireNotNull(applied).version, targets,
            config = applied.config, outputAliases = applied.outputAliases, plugin = applied.plugin)
    }

    companion object {
        fun of(model: RigPreviewModel) = RigModifierSource(model.modifierAuthorAnalysis ?: model.analysis, model.modifierAuthorAtlas ?: model.atlas,
            model.config, model.authored.rig.layerIdByDrawableId, model.authored.rig.copiedDrawableIds, model.authored.rig.puppet)
    }
}

/** Source-aware parts of the binding algorithms. The scaffold is explicit and contains no motion. */
internal object RigSourceModifiers {
    const val SCAFFOLD = "rig.scaffold"
    const val EYE = "eye"
    const val BROW = "brow"
    const val FACE = "face"
    const val BODY = "body"
    const val HAIR = "hair"
    const val MOUTH = "mouth"

    private fun partDefinitions(source: RigModifierSource?): List<RigModifierDefinition> = listOf(
        RigModifierDefinition(
            type = EYE,
            version = 1,
            requires = setOf(SCAFFOLD),
            provides = setOf("eye/fitted", "eye/legacy"),
            exclusiveFamily = "base:eye",
            providers = { mapOf(SCAFFOLD to it.config.getValue("scaffold").jsonPrimitive.content) },
            upstreamInputs = true,
            appliedConfig = { it.config },
            validate = { _, instance ->
                require("scaffold" in instance.config) { "Specify eye's scaffold" }
                val mode = instance.config["mode"]?.jsonPrimitive?.content ?: "fitted"
                require(mode == "fitted" || mode == "legacy") { "Invalid eye mode: $mode" }
                require(instance.targets.all { it.kind == "mesh" && source?.hasMesh(it.id) == true }) {
                    "Eye targets must have captured source layers"
                }
                requireNotNull(source).provider(instance)
            },
            composableKinds = setOf("mesh", "part", "deformer", "hierarchy", "relations", "parameter", "physics"),
            plugin = io.github.psd2live.algorithms.binding.EyePartAlgorithm(),
            changeWrites = setOf("mesh", "part", "deformer", "hierarchy", "relations", "parameter", "physics"),
            authorContext = { requireNotNull(source).eyePartContext(it) },
        ),
        RigModifierDefinition(
            type = BROW,
            version = 1,
            requires = setOf(SCAFFOLD),
            provides = setOf("brow/fitted", "brow/legacy"),
            exclusiveFamily = "base:brow",
            providers = { mapOf(SCAFFOLD to it.config.getValue("scaffold").jsonPrimitive.content) },
            upstreamInputs = true,
            appliedConfig = { it.config },
            validate = { _, instance ->
                require("scaffold" in instance.config) { "Specify brow's scaffold" }
                val mode = instance.config["mode"]?.jsonPrimitive?.content ?: "fitted"
                require(mode == "fitted" || mode == "legacy") { "Invalid brow mode: $mode" }
                require(instance.targets.all { it.kind == "mesh" && source?.hasMesh(it.id) == true }) {
                    "Brow targets must have captured source layers"
                }
                requireNotNull(source).provider(instance)
            },
            composableKinds = setOf("mesh", "part", "deformer", "hierarchy", "relations", "parameter", "physics"),
            plugin = object : ChangingModifierAlgorithmPlugin by io.github.psd2live.algorithms.binding.BrowPartAlgorithm(), ModifierOptionalUpstream {
                override val optionalUpstream = setOf("eye/fitted")
            },
            changeWrites = setOf("mesh", "part", "deformer", "hierarchy", "relations", "parameter", "physics", "restPose"),
            authorContext = { requireNotNull(source).browPartContext(it) },
        ),
        RigModifierDefinition(
            type = FACE,
            version = 1,
            requires = setOf(SCAFFOLD),
            provides = setOf("face/fitted", "face/legacy"),
            exclusiveFamily = "base:face",
            providers = { mapOf(SCAFFOLD to it.config.getValue("scaffold").jsonPrimitive.content) },
            upstreamInputs = true,
            appliedConfig = { it.config },
            validate = { _, instance ->
                require("scaffold" in instance.config) { "Specify face's scaffold" }
                val mode = instance.config["mode"]?.jsonPrimitive?.content ?: "fitted"
                require(mode == "fitted" || mode == "legacy") { "Invalid face mode: $mode" }
                require(instance.targets.all { it.kind == "mesh" && source?.hasMesh(it.id) == true }) {
                    "Face targets must have captured source layers"
                }
                requireNotNull(source).provider(instance)
            },
            composableKinds = setOf("mesh", "part", "deformer", "hierarchy", "relations", "parameter"),
            plugin = io.github.psd2live.algorithms.binding.FacePartAlgorithm(),
            changeWrites = setOf("mesh", "part", "deformer", "hierarchy", "relations", "parameter"),
            authorContext = { requireNotNull(source).facePartContext(it) },
        ),
        RigModifierDefinition(
            type = BODY,
            version = 1,
            requires = setOf(SCAFFOLD),
            provides = setOf("body/legacy"),
            exclusiveFamily = "base:body",
            providers = { mapOf(SCAFFOLD to it.config.getValue("scaffold").jsonPrimitive.content) },
            upstreamInputs = true,
            appliedConfig = { it.config },
            validate = { _, instance ->
                require("scaffold" in instance.config) { "Specify body's scaffold" }
                require(instance.targets.all { it.kind == "mesh" && source?.hasMesh(it.id) == true }) {
                    "Body targets must have captured source layers"
                }
                requireNotNull(source).provider(instance)
            },
            composableKinds = setOf("mesh", "part", "deformer", "hierarchy", "relations", "parameter", "physics"),
            plugin = object : ChangingModifierAlgorithmPlugin by io.github.psd2live.algorithms.binding.BodyPartAlgorithm(), ModifierOptionalUpstream {
                override val optionalUpstream = setOf("face/legacy", "face/fitted")
            },
            changeWrites = setOf("mesh", "part", "deformer", "hierarchy", "relations", "parameter", "physics"),
            authorContext = { requireNotNull(source).bodyPartContext(it) },
        ),
        RigModifierDefinition(
            type = HAIR,
            version = 1,
            requires = setOf(SCAFFOLD, "body/legacy", "face/legacy"),
            provides = setOf("hair/chains", "hair/legacy"),
            exclusiveFamily = "base:hair",
            providers = { mapOf(SCAFFOLD to it.config.getValue("scaffold").jsonPrimitive.content) },
            upstreamInputs = true,
            appliedConfig = { it.config },
            validate = { _, instance ->
                require("scaffold" in instance.config) { "Specify hair's scaffold" }
                val mode = instance.config["mode"]?.jsonPrimitive?.content ?: "chains"
                require(mode == "chains" || mode == "legacy") { "Invalid hair mode: $mode" }
                require(instance.targets.all { it.kind == "mesh" && source?.hasMesh(it.id) == true }) {
                    "Hair targets must have captured source layers"
                }
                requireNotNull(source).provider(instance)
            },
            composableKinds = setOf("mesh", "part", "deformer", "hierarchy", "relations", "parameter", "physics"),
            plugin = io.github.psd2live.algorithms.binding.HairPartAlgorithm(),
            changeWrites = setOf("mesh", "part", "deformer", "hierarchy", "relations", "parameter", "physics"),
            authorContext = { requireNotNull(source).hairPartContext(it) },
        ),
        RigModifierDefinition(
            type = MOUTH,
            version = 1,
            requires = setOf(SCAFFOLD),
            provides = setOf("mouth/fitted", "mouth/legacy"),
            exclusiveFamily = "base:mouth",
            providers = { mapOf(SCAFFOLD to it.config.getValue("scaffold").jsonPrimitive.content) },
            upstreamInputs = true,
            appliedConfig = { it.config },
            validate = { _, instance ->
                require("scaffold" in instance.config) { "Specify mouth's scaffold" }
                val mode = instance.config["mode"]?.jsonPrimitive?.content ?: "fitted"
                require(mode == "fitted" || mode == "legacy") { "Invalid mouth mode: $mode" }
                require(instance.targets.all { it.kind == "mesh" && source?.hasMesh(it.id) == true }) {
                    "Mouth targets must have captured source layers"
                }
                requireNotNull(source).provider(instance)
            },
            composableKinds = setOf("mesh", "part", "deformer", "hierarchy", "relations", "parameter", "physics", "restPose", "textures", "authoring"),
            plugin = object : ChangingModifierAlgorithmPlugin by io.github.psd2live.algorithms.binding.MouthPartAlgorithm(), ModifierOptionalUpstream {
                override val optionalUpstream = setOf("face/fitted", "face.depth-turn", "body/legacy", "face/legacy",
                    "eye/fitted", "eye.smile", "eye.wide")
            },
            changeWrites = setOf("mesh", "part", "deformer", "hierarchy", "relations", "parameter", "physics", "restPose", "textures", "authoring"),
            authorContext = { requireNotNull(source).mouthPartContext(it) },
        ),
    )

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
            plugin = io.github.psd2live.algorithms.binding.ScaffoldAlgorithm(),
            changeWrites = setOf("mesh", "part", "deformer", "hierarchy"),
            authorContext = { requireNotNull(source).scaffoldContext(it) })
    } + partDefinitions(source) + RigLegacyBindings.specs.map { RigLegacyBindings.definition(it, source) }

    /** Version 2 retains the source hierarchy; version 1 keeps persisted per-mesh identities replayable. */
    fun deformerId(instance: RigModifierInstance, mesh: String, canonical: String, shared: Boolean = instance.type == SCAFFOLD && instance.version >= 2) =
        io.github.psd2live.algorithms.binding.ScaffoldBinding.deformerId(instance, mesh, canonical, shared)
    fun partId(instance: RigModifierInstance, mesh: String, canonical: String, shared: Boolean = instance.type == SCAFFOLD && instance.version >= 2) =
        io.github.psd2live.algorithms.binding.ScaffoldBinding.partId(instance, mesh, canonical, shared)

    internal fun pointsInParent(model: PuppetModel, sample: Drawable, points: FloatArray, parent: DeformerId, scratch: String): FloatArray =
        io.github.psd2live.algorithms.binding.BindingFrames.pointsInParent(model, sample, points, parent, scratch)

}
