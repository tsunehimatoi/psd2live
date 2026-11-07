package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.ExpressionChannels
import io.github.psd2live.algorithms.binding.MouthFittedInputs
import io.github.psd2live.algorithms.binding.MouthLegacyInputs
import io.github.psd2live.algorithms.binding.ScaffoldAlgorithm
import io.github.psd2live.algorithms.binding.ScaffoldParts
import io.github.psd2live.algorithms.binding.ScaffoldPartsContext
import io.github.psd2live.core.*
import io.github.psd2live.core.recipe.*
import io.github.psd2live.format.compile.RigIrBinary
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.targets.cubism.PuppetIr
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
    private val parameterDefinitions = java.util.IdentityHashMap<PuppetModel, List<io.github.psd2live.format.model.Parameter>>()
    internal val primaryByLayer = layersByMesh.entries.sortedBy { it.key in copiedMeshes }.groupBy { it.value }.mapValues { it.value.first().key }
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
            put("already_active", JsonArray(instance.targets.filter { target ->
                relevantBefore(instance, target).any { it.type == "face.skin-clip" }
            }.map { JsonPrimitive(it.id) }))
        })
    }

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
            activeBefore(instance).filter { RigLegacyBindings.find(it.type)?.seeThrough == true || isPackagedSeeThrough(it) }))
    })

    private fun isPackagedSeeThrough(instance: RigModifierInstance): Boolean {
        val plugin = instance.plugin ?: return false
        return listOf("eye.see-through", "brow.see-through").any { alias ->
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
        val owners = rig.meshes.associateBy { it.id }
        val ribbons = instance.targets.flatMap { target ->
            checkpoint()
            val layer = layersByMesh.getValue(target.id)
            if (lipLayers.keys.none { it.first == layer }) return@flatMap emptyList()
            val native = template(relevantBefore(instance, target) + instance, provider(instance).config).model
            val canonical = primaryByLayer.getValue(layer)
            val owner = owners.getValue(target.id)
            (0..1).mapNotNull { side ->
                val lip = native.drawables.singleOrNull { it.id.raw == "${canonical}_lip_$side" } ?: return@mapNotNull null
                val source = lipLayers.getValue(layer to side)
                val id = DrawableId(instance.outputId("mouth-lip:${target.id}:$side"))
                val assetId = instance.outputId("mouth-art:${target.id}:$side")
                val asset = source.copy(source = object : org.umamo.format.art.SourceLayer by source.source {
                    override val id = org.umamo.format.art.LayerId(assetId)
                })
                val drawable = lip.copy(id = id, parentDeformerId = owner.parent?.let(::DeformerId), maskedBy = owner.maskedBy.map(::DrawableId),
                    invertMask = owner.invertMask, isVisible = owner.visible, textureSourceId = null,
                    drawOrder = (owner.drawOrder + 1f).coerceAtMost(1000f), atlasTileId = PuppetSourceAtlas.tileIdFor(assetId))
                val paths = native.deformPaths.filter { it.drawableId == lip.id }.map { path ->
                    path.copy(id = instance.outputId("mouth-path:${target.id}:$side:${path.id}"), drawableId = id)
                }
                RigModifierRasterAssets.Ribbon(DrawableId(owner.id), drawable, asset, paths)
            }
        }
        return RigModifierRasterAssets.attachRibbons(rig, ribbons, config)
    }

    fun bodyContext(instance: RigModifierInstance): ModifierAlgorithmContext {
        val scaffold = provider(instance)
        val tuning = RigLegacyBindings.tuning(instance, RigTuning()).bodyMotion()
        val context = scaffoldContext(scaffold)
        val torso = requireNotNull(scaffoldTorsos[scaffold]) { "Body requires captured scaffold measurements" }
        val tr = { key: String -> io.github.psd2live.i18n.tr(key) }
        return io.github.psd2live.algorithms.binding.LegacyBodyInputs.capture(context, torso, tuning,
            StandardParameters.all.filter { it.id in RigBodyModifier.parameters }.associate { it.id.raw to it.name },
            linkedMapOf("ParamGroupFace" to tr("model.group.face"), "ParamGroupEyes" to tr("model.group.eyes"),
                "ParamGroupBrows" to tr("model.group.brows"), "ParamGroupMouth" to tr("model.group.mouth"),
                "ParamGroupBody" to tr("model.group.body"), "ParamGroupPhysics" to tr("model.group.physics")))
    }

    fun closedMouthShellContext(instance: RigModifierInstance) =
        RigClosedMouthShellProjection.capture(this, materialBase, instance)

    fun outlineShellSource(context: RigBuilder.RigContext, config: PipelineConfig, layers: Map<String, ClassifiedLayer>,
                           parameters: Set<ParameterId>, includeFaceLean: Boolean = false,
                           projectedMeshes: Set<String> = layers.keys): ModifierAlgorithmResource =
        ModifierAlgorithmResource(io.github.psd2live.format.compile.RigIrBinary.encode(PuppetIr.toIr(
            RigBuilder.closedMouthShellProjection(context, config, atlas, meshCache, materialBase, layers, parameters, includeFaceLean)
                .let { projection -> projection.copy(drawables = projection.drawables.filter { it.id.raw in projectedMeshes }) })))
    private val staticModels = java.util.IdentityHashMap<PuppetModel, PuppetModel>()
    fun static(model: PuppetModel) = staticModels.getOrPut(model) { restricted(model, emptySet()) }

    private val scaffoldTorsos = HashMap<RigModifierInstance, io.github.psd2live.algorithms.binding.TorsoFrame>()
    private val scaffoldContexts = HashMap<RigModifierInstance, ModifierAlgorithmContext>()
    private val sourceScaffoldContexts = HashMap<RigModifierInstance, ModifierAlgorithmContext>()
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

    fun legacyMouthContext(instance: RigModifierInstance): ModifierAlgorithmContext {
        val structure = provider(instance)
        val corrections = io.github.psd2live.project.WorkspaceSettingsCodec.decodeRecipe(structure.config["corrections"])
        val native = sourceConfig().copy(recipe = RigRecipe.Legacy.copy(moves = corrections.moves, eyeParts = corrections.eyeParts),
            featureDisplacementEnabled = structure.config["featureDisplacement"]?.jsonPrimitive?.boolean ?: false,
            hairSimulationFront = !(structure.config["frontHairSway"]?.jsonPrimitive?.boolean ?: true),
            hairSimulationBack = !(structure.config["backHairSway"]?.jsonPrimitive?.boolean ?: true))
        val measured = RigBuilder.rigContext(analysis, native.copy(generateDeformers = false), meshCache)
        val tuning = RigLegacyBindings.tuning(instance, RigTuning())
        val targets = instance.targets.mapTo(hashSetOf()) { it.id }
        val clips = RigOrdinaryMaterials.clipping(analysis.source, primaryByLayer.entries.associate { it.value to it.key })
        val meshesByLayer = layersByMesh.entries.groupBy({ it.value }, { it.key })
        val layers = buildMap {
            for (layer in measured.analysis.layers.sortedBy { it.source.order }) {
                val id = layer.source.id.raw
                val semantic = layer.semantic
                if (semantic.tag !in CharacterAnalyzer.MOUTH_TAGS) continue
                val primary = primaryByLayer[id] ?: continue
                val aligned = measured.rigLayer(layer)
                val frame = measured.parentAndFrame(layer, native).second
                val isMask = semantic.tag in setOf(SemanticTag.MOUTH, SemanticTag.MOUTH_OPEN) &&
                    semantic.role == null && !layer.source.clipped && layer.opaquePixels > 0
                for (mesh in meshesByLayer[id].orEmpty()) {
                    if (mesh !in targets && !(mesh == primary && isMask)) continue
                    put(mesh, MouthLegacyInputs.Layer(primary, semantic.tag.name, semantic.side, semantic.variant, frame,
                        aligned.bounds, layer.bounds, (native.layerOverrides[id]?.type ?: semantic.type) == LayerType.PRESET,
                        layer.source.opacity, clips[id]?.raw, mesh == primary && isMask))
                }
            }
        }
        return MouthLegacyInputs(layers,
            MouthFittedInputs.Fallback(if (native.mouthShape == "custom") native.mouthCurve else MouthCurve.preset(native.mouthShape),
                false, tuning.mouthClosedNarrow, tuning.mouthFormWiden, tuning.mouthSeam, tuning.mouthSmileBase, tuning.mouthSmile),
            tuning.teethFade, measured.hasClosedMouth, io.github.psd2live.i18n.tr("model.parameter.mouthForm"), io.github.psd2live.i18n.tr("model.parameter.mouthOpen"),
            io.github.psd2live.i18n.tr("model.group.mouth"), scaffoldContext(structure.copy(plugin = null))).encode()
    }

    private fun templateKey(instances: List<RigModifierInstance>, scaffold: JsonObject): Map<String, JsonObject> {
        val state = instances.associateBy { it.type }
        val key = state.mapValues { (_, instance) -> buildJsonObject {
            // This is an intermediate geometry template, not a modifier-result cache. Pendulums are evaluated separately.
            put("version", instance.version); put("config", JsonObject(instance.config - "scaffold" - "base" - "physics"))
            instance.plugin?.let { put("plugin", Json.encodeToJsonElement(ModifierPluginReference.serializer(), it)) }
        } }
        return key + ("rig.scaffold" to scaffold)
    }

    private fun template(instances: List<RigModifierInstance>, scaffold: JsonObject): Template {
        RigBuildProfile.count("modifier legacy template requests")
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
        val kept = state.keys.flatMapTo(HashSet()) { RigLegacyBindings.spec(it).parameters }
        val byArea = instances.filter { RigLegacyBindings.spec(it.type).isBase }.associateBy { RigLegacyBindings.spec(it.type).area }
        val native = RigBuildProfile.stage("modifier legacy template: ${instances.last().type}") {
            generated(nativeBindingConfig(instances, scaffold))
        }
        val chains = native.generatedChains.filter { chain -> instances.any { chain.kind in RigModifierChains.kinds(it.type) } }
            .map { RigModifierChains.Binding(it) }
        return finishTemplate(native.puppet, chains, kept, byArea.keys)
    }

    private fun nativeBindingConfig(instances: List<RigModifierInstance>, scaffold: JsonObject): PipelineConfig {
        val state = instances.associateBy { it.type }
        var tuning = RigTuning()
        for (spec in RigLegacyBindings.specs) state[spec.type]?.let { tuning = RigLegacyBindings.tuning(it, tuning) }
        val bodyStrength = state["body/legacy"]?.let(RigLegacyBindings::strength) ?: 1f
        val headStrength = (state[RigLegacyBindings.FITTED_FACE] ?: state["face/legacy"])?.let(RigLegacyBindings::strength) ?: 1f
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
        val correctedRecipe = recipe.copy(moves = corrections.moves, eyeParts = corrections.eyeParts)
        return generationConfig(bodyStrength, tuning, headStrength, correctedRecipe, outline,
            scaffold["featureDisplacement"]?.jsonPrimitive?.boolean ?: false,
            scaffold["frontHairSway"]?.jsonPrimitive?.boolean ?: true, scaffold["backHairSway"]?.jsonPrimitive?.boolean ?: true)
    }

    fun captureAttachmentResidual(instance: RigModifierInstance, affected: Set<String>, namespace: String,
                                  base: ModifierAlgorithmContext): ModifierAlgorithmContext {
        val targets = instance.targets.filter { it.id in affected }
        val resources = base.resources.toMutableMap()
        for ((suffix, active) in listOf("before" to false, "after" to true)) {
            for ((native, static) in attachmentProjection(instance, targets, active)) {
                val id = native.drawables.single().id.raw
                resources["$namespace/$id/$suffix"] = ModifierAlgorithmResource(RigIrBinary.encode(PuppetIr.toIr(native)))
                resources["$namespace/$id/$suffix-static"] = ModifierAlgorithmResource(RigIrBinary.encode(PuppetIr.toIr(static)))
            }
        }
        return ModifierAlgorithmContext(base.values, resources)
    }

    private fun attachmentProjection(instance: RigModifierInstance, targets: List<ModifierTarget>, active: Boolean): List<Pair<PuppetModel, PuppetModel>> {
        val scaffold = provider(instance)
        return targets.groupBy { relevantBefore(instance, it) }.flatMap { (preceding, selected) ->
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
        val scaffold = provider(instance)
        fun projected(items: List<RigModifierInstance>): RigModifierChains.Projection {
            if (items.isEmpty()) return RigModifierChains.Projection(forTarget(neutralInput(scaffold), target), emptyList())
            val template = template(items, scaffold.config)
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
            RigIdRemap(drawables = mapOf(DrawableId(canonical) to DrawableId(target.id))).model(model)
        }
    }

    fun hasMesh(id: String) = id in layersByMesh
    fun hasLayer(id: String) = analysis.layers.any { it.source.id.raw == id }

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
            plugin = io.github.psd2live.algorithms.binding.ScaffoldAlgorithm(),
            changeWrites = setOf("mesh", "part", "deformer", "hierarchy"),
            authorContext = { requireNotNull(source).scaffoldContext(it) })
    } + RigLegacyBindings.specs.map { RigLegacyBindings.definition(it, source) } + RigLegacyBindings.legacyMouthDefinition(source)

    /** Version 2 retains the source hierarchy; version 1 keeps persisted per-mesh identities replayable. */
    fun deformerId(instance: RigModifierInstance, mesh: String, canonical: String, shared: Boolean = instance.type == SCAFFOLD && instance.version >= 2) =
        io.github.psd2live.algorithms.binding.ScaffoldBinding.deformerId(instance, mesh, canonical, shared)
    fun partId(instance: RigModifierInstance, mesh: String, canonical: String, shared: Boolean = instance.type == SCAFFOLD && instance.version >= 2) =
        io.github.psd2live.algorithms.binding.ScaffoldBinding.partId(instance, mesh, canonical, shared)

    internal fun pointsInParent(model: PuppetModel, sample: Drawable, points: FloatArray, parent: DeformerId, scratch: String): FloatArray =
        io.github.psd2live.algorithms.binding.BindingFrames.pointsInParent(model, sample, points, parent, scratch)

}
