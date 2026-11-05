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
) {
    private val stack by lazy { RigModifierStack.fromJson(requireNotNull(config.rigEdits.modifierStack)) }
    private val parameterDefinitions = java.util.IdentityHashMap<PuppetModel, List<io.github.psd2live.format.model.Parameter>>()
    private val primaryByLayer = layersByMesh.entries.sortedBy { it.key in copiedMeshes }.groupBy { it.value }.mapValues { it.value.first().key }
    private val projections = java.util.IdentityHashMap<PuppetModel, MutableMap<String, PuppetModel>>()
    private fun generated(strength: Float = 1f, tuning: RigTuning = RigTuning(), headStrength: Float = 1f,
                          recipe: RigRecipe = RigRecipe.Legacy): BuiltRig {
        val clean = RigModifierPipeline.ordinaryConfig(config).copy(meshOnly = false, generateDeformers = true,
            deferRig = false, rigPendingLayerIds = emptySet(), parentOverrides = emptyMap(),
            rigEdits = RigEditOverlay(splitDrawableIds = primaryByLayer))
        return RigBuilder.build(analysis, atlas, clean.copy(bodyStrength = strength, headTurnStrength = headStrength, rigTuning = tuning, recipe = recipe))
    }

    private data class Template(val model: PuppetModel, val chains: List<RigModifierChains.Binding>)
    private val templates = HashMap<Map<String, JsonObject>, Template>()
    private val neutral by lazy { template(emptyList()).model }
    private val transitions = HashMap<Pair<String, ModifierTarget>, Pair<RigModifierChains.Projection, RigModifierChains.Projection>>()

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

    private val expressionTemplate by lazy {
        generated(recipe = RigRecipe(true, modules = RigModules.modules.associate {
            it.id to ModuleChoice(it == RigModules.EXPRESSION_TOGGLES)
        })).puppet
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
        return parameterDefinitions.getOrPut(expressionTemplate) { PuppetIr.toIr(expressionTemplate).parameters }
            .filter { it.id in ids }
    }

    fun expression(rig: RigIR, instance: RigModifierInstance, checkpoint: () -> Unit): RigIR {
        checkpoint()
        val ids = expressionIds(instance)
        if (ids.isEmpty()) return rig
        val current = PuppetIr.toPuppet(rig)
        val existing = current.parameters.mapTo(HashSet()) { it.id }
        val additions = expressionTemplate.parameters.filter { it.id in ids && it.id !in existing }
        val templates = expressionTemplate.drawables.associateBy { it.id.raw }
        val targets = instance.targets.mapTo(HashSet()) { it.id }
        val model = current.copy(parameters = current.parameters + additions,
            parameterTree = current.parameterTree + additions.map { ParameterNode.Param(it.id) })
        val drawables = model.drawables.map { actual ->
            val layer = layersByMesh[actual.id.raw]
            if (actual.id.raw !in targets || layer == null || layer !in expressionLayers) return@map actual
            val template = templates.getValue(primaryByLayer.getValue(layer))
            val opacity = requireNotNull(template.channelGrids[FormChannel.OPACITY]) { "${instance.id}: missing expression track on ${actual.id.raw}" }
            require(opacity.axes.singleOrNull()?.parameterId == expressionLayers[layer]) { "${instance.id}: incompatible expression track on ${actual.id.raw}" }
            require(actual.channelGrids[FormChannel.OPACITY]?.axes.orEmpty().none { it.parameterId in ids }) {
                "${instance.id}: expression track already exists on ${actual.id.raw}"
            }
            checkpoint()
            val neutral = actual.copy(channelGrids = ChannelGrids.Empty, opacity = template.opacity)
            val desired = neutral.copy(channelGrids = ChannelGrids(mapOf(FormChannel.OPACITY to opacity)))
            actual.copy(channelGrids = io.github.psd2live.core.legacy.RigGenerationResidual.channels(model, actual, neutral, desired, checkpoint))
        }
        val output = rig.withPuppet(model.copy(drawables = drawables))
        return RigModifierPhysics.addExpressionInputs(output, activeBefore(instance), ids.map { it.raw })
    }

    fun addUpstreamExpressionInputs(rig: RigIR, instance: RigModifierInstance): RigIR {
        val preceding = activeBefore(instance).filter { RigLegacyBindings.find(it.type)?.expressionToggles == true }
        return RigModifierPhysics.addExpressionInputs(rig, listOf(instance), preceding.flatMap { expressionIds(it).map(ParameterId::raw) })
    }

    fun body(strength: Float, tuning: RigTuning = RigTuning(), checkpoint: () -> Unit): PuppetModel {
        checkpoint()
        return restricted(generated(strength, tuning).puppet, RigBodyModifier.parameters).also { checkpoint() }
    }

    fun static(model: PuppetModel) = restricted(model, emptySet())

    private fun template(instances: List<RigModifierInstance>): Template {
        val state = instances.associateBy { it.type }
        val key = state.mapValues { (_, instance) -> buildJsonObject {
            // This is an intermediate geometry template, not a modifier-result cache. Pendulums are evaluated separately.
            put("version", instance.version); put("config", JsonObject(instance.config - "scaffold" - "base" - "physics"))
        } }
        return templates.getOrPut(key) {
            var tuning = RigTuning()
            for (spec in RigLegacyBindings.specs) state[spec.type]?.let { tuning = RigLegacyBindings.tuning(it, tuning) }
            val bodyStrength = state["body/legacy"]?.let(RigLegacyBindings::strength) ?: 1f
            val headStrength = state["face/legacy"]?.let(RigLegacyBindings::strength) ?: 1f
            val kept = state.keys.flatMapTo(HashSet()) { RigLegacyBindings.spec(it).parameters }
            val byArea = instances.filter { RigLegacyBindings.spec(it.type).isBase }.associateBy { RigLegacyBindings.spec(it.type).area }
            val recipe = if (instances.none { !it.type.endsWith("/legacy") }) RigRecipe.Legacy else RigRecipe(true,
                bases = RigModules.areas.associate { area ->
                    val chosen = byArea[area.id]
                    val base = chosen?.type ?: area.legacy.id
                    area.id to BaseChoice(base, chosen?.version ?: area.legacy.version, chosen?.let(RigLegacyBindings::values).orEmpty())
                }, modules = RigModules.modules.associate { module ->
                    val chosen = state[module.id]
                    module.id to ModuleChoice(chosen != null, chosen?.version ?: module.version, chosen?.let(RigLegacyBindings::values).orEmpty())
                })
            val native = generated(bodyStrength, tuning, headStrength, recipe)
            val chains = native.generatedChains.filter { chain -> instances.any { chain.kind in RigModifierChains.kinds(it.type) } }
                .map { RigModifierChains.Binding(it) }
            val dynamic = chains.flatMap { it.native.parameters }
            val model = RigModifierChains.normalize(restricted(native.puppet, kept + dynamic), chains)
            val clips = RigOrdinaryMaterials.clipping(analysis.source, primaryByLayer.entries.associate { it.value to it.key })
            val tags = analysis.layers.associate { it.source.id.raw to it.semantic.tag }
            val opacity = analysis.layers.associate { it.source.id.raw to it.source.opacity }
            Template(model.copy(drawables = model.drawables.map { mesh ->
                val layer = layersByMesh[mesh.id.raw]
                val eye = tags[layer] in setOf(SemanticTag.IRIDES, SemanticTag.EYEWHITE, SemanticTag.EYELASH, SemanticTag.EYE_CLOSE)
                val mouth = tags[layer] in CharacterAnalyzer.MOUTH_COMPONENT_TAGS + setOf(SemanticTag.MOUTH, SemanticTag.MOUTH_OPEN)
                val active = eye && "eye" in byArea || mouth && "mouth" in byArea
                mesh.copy(maskedBy = if ((eye || mouth) && !active) listOfNotNull(clips[layer]) else mesh.maskedBy,
                    channelGrids = if ((eye || mouth) && !active) ChannelGrids.Empty else mesh.channelGrids,
                    opacity = if ((eye || mouth) && !active) opacity[layer] ?: mesh.opacity else mesh.opacity)
            }), chains)
        }
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

    private fun transitionProjections(instance: RigModifierInstance, target: ModifierTarget): Pair<RigModifierChains.Projection, RigModifierChains.Projection> {
        val provider = provider(instance)
        val spec = RigLegacyBindings.spec(instance.type)
        val appliedIds = stack.appliedCapabilities.mapNotNullTo(HashSet()) { it.id }
        val preceding = activeBefore(instance).filter { previous ->
            // Reapplying a base to materialized content treats the earlier base as authored residuals.
            !(spec.isBase && previous.id in appliedIds &&
                RigLegacyBindings.find(previous.type)?.let { it.isBase && it.area == spec.area } == true)
        }
        val relevant = preceding.filter { previous -> RigLegacyBindings.find(previous.type)?.let { !it.expressionToggles && !it.seeThrough } == true && target in previous.targets &&
            previous.config["scaffold"]?.jsonPrimitive?.content == provider.id }
        fun projected(items: List<RigModifierInstance>): RigModifierChains.Projection {
            val template = template(items)
            return RigModifierChains.projection(forTarget(template.model, target), target, items, template.chains, ::restricted)
        }
        return projected(relevant) to projected(relevant + instance)
    }

    private val activePrefixes = HashMap<String, List<RigModifierInstance>>()

    /** Mirror ordered dependency eligibility, so a paused module cannot leak into a later source transition. */
    private fun activeBefore(instance: RigModifierInstance): List<RigModifierInstance> = activePrefixes.getOrPut(instance.id) {
        val available = stack.appliedCapabilities.toMutableList()
        val active = stack.appliedCapabilities.filter { it.id != null }.mapTo(ArrayList()) {
            RigModifierInstance(requireNotNull(it.id), it.type, it.version, it.targets, config = it.config)
        }
        for (previous in stack.instances.take(stack.index(instance.id))) {
            if (!previous.enabled) continue
            val spec = RigLegacyBindings.find(previous.type)
            val providers = if (spec == null) emptyMap() else RigLegacyBindings.providers(previous)
            val missing = spec?.requires.orEmpty().any { type -> previous.targets.any { target ->
                available.none { it.type == type && target in it.targets && (providers[type] == null || it.id == providers[type]) }
            } }
            if (missing) continue
            active += previous
            available += ModifierCapability(previous.type, previous.version, previous.targets, previous.id, previous.config)
        }
        active
    }

    private val canonicalRecords = java.util.IdentityHashMap<PuppetModel, RigIR>()

    /** Preserve authored geometry byte-for-byte when a native transition only changes clipping (or nothing). */
    fun onlyMasksChange(before: PuppetModel, after: PuppetModel, target: ModifierTarget, branch: Set<DeformerId>): Boolean {
        fun record(model: PuppetModel) = canonicalRecords.getOrPut(model) { PuppetIr.toIr(model) }
        val a = record(before); val b = record(after)
        val old = a.meshes.single { it.id == target.id }; val next = b.meshes.single { it.id == target.id }
        if (old.copy(maskedBy = next.maskedBy, invertMask = next.invertMask) != next) return false
        val oldDeformers = a.deformers.associateBy { it.id }; val nextDeformers = b.deformers.associateBy { it.id }
        return branch.all { oldDeformers[it.raw] == nextDeformers[it.raw] }
    }

    private val deformerOrigins = HashMap<Pair<String, String>, Map<DeformerId, DeformerId>>()

    /** A native carrier is named by the first persisted instance that creates it, including applied providers. */
    fun deformerIds(instance: RigModifierInstance, target: ModifierTarget, checkpoint: () -> Unit): Map<DeformerId, DeformerId> =
        deformerOrigins.getOrPut(instance.id to target.id) {
            val provider = provider(instance)
            val ids = scaffoldFor(target, checkpoint).deformers.associateTo(LinkedHashMap()) {
                it.id to DeformerId(RigSourceModifiers.deformerId(provider, target.id, it.id.raw))
            }
            val relevant = (activeBefore(instance) + instance).filter { previous -> RigLegacyBindings.find(previous.type) != null &&
                target in previous.targets && previous.config["scaffold"]?.jsonPrimitive?.content == provider.id }
            val prefix = ArrayList<RigModifierInstance>()
            for (item in relevant) {
                checkpoint(); prefix += item
                for (deformer in template(prefix).model.deformers) ids.putIfAbsent(deformer.id,
                    DeformerId(RigSourceModifiers.deformerId(item, target.id, deformer.id.raw)))
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
            val ids = requiredParts(scaffoldFor(target, checkpoint), target).associateTo(LinkedHashMap()) {
                it to PartId(RigSourceModifiers.partId(provider, target.id, it.raw))
            }
            val relevant = (activeBefore(instance) + instance).filter { previous -> RigLegacyBindings.find(previous.type) != null &&
                target in previous.targets && previous.config["scaffold"]?.jsonPrimitive?.content == provider.id }
            val prefix = ArrayList<RigModifierInstance>()
            for (item in relevant) {
                checkpoint(); prefix += item
                for (part in requiredParts(forTarget(template(prefix).model, target), target)) ids.putIfAbsent(part,
                    PartId(RigSourceModifiers.partId(item, target.id, part.raw)))
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
            parameterDefinitions.getOrPut(desired) { PuppetIr.toIr(desired).parameters }
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

    fun scaffold(checkpoint: () -> Unit): PuppetModel {
        checkpoint()
        val result = neutral
        checkpoint()
        return result
    }

    fun scaffoldFor(target: ModifierTarget, checkpoint: () -> Unit) = forTarget(scaffold(checkpoint), target)

    private fun forTarget(model: PuppetModel, target: ModifierTarget): PuppetModel {
        val canonical = primaryByLayer.getValue(layersByMesh.getValue(target.id))
        if (canonical == target.id) return model
        return projections.getOrPut(model) { HashMap() }.getOrPut(target.id) {
            RigIdRemap(drawables = mapOf(DrawableId(canonical) to DrawableId(target.id))).model(model)
        }
    }

    fun hasMesh(id: String) = id in layersByMesh

    fun provider(instance: RigModifierInstance): RigModifierInstance {
        val id = instance.config.getValue("scaffold").jsonPrimitive.content
        val preceding = stack.instances.take(stack.index(instance.id)).singleOrNull { it.id == id && it.enabled && it.type == RigSourceModifiers.SCAFFOLD }
        val applied = stack.appliedCapabilities.singleOrNull { it.id == id && it.type == RigSourceModifiers.SCAFFOLD }
        val targets = preceding?.targets ?: requireNotNull(applied) { "${instance.id}: binding scaffold $id must precede this modifier" }.targets
        require(instance.targets.all { it in targets }) { "${instance.id}: scaffold $id does not cover all captured targets" }
        return preceding ?: RigModifierInstance(id, RigSourceModifiers.SCAFFOLD, requireNotNull(applied).version, targets)
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
        fun of(model: RigPreviewModel) = RigModifierSource(model.analysis, model.atlas, model.config, model.rig.layerIdByDrawableId, model.rig.copiedDrawableIds)
    }
}

/** Source-aware parts of the binding algorithms. The scaffold is explicit and contains no motion. */
internal object RigSourceModifiers {
    const val SCAFFOLD = "rig.scaffold"

    fun definitions(source: RigModifierSource?): List<RigModifierDefinition> = listOf(
        RigModifierDefinition(SCAFFOLD, 1, exclusiveFamily = SCAFFOLD,
            validate = { _, instance ->
                require(instance.config.isEmpty()) { "A binding scaffold has no motion settings" }
                require(instance.targets.all { it.kind == "mesh" }) { "A binding scaffold targets source meshes" }
                val input = requireNotNull(source) { "Binding modifiers require the captured source artwork" }
                require(instance.targets.all { input.hasMesh(it.id) }) { "A binding scaffold target has no source layer" }
            }, composableKinds = setOf("mesh", "part", "deformer", "hierarchy"),
            evaluate = { rig, instance, checkpoint -> scaffold(rig, instance, requireNotNull(source), checkpoint) }),
    ) + RigLegacyBindings.specs.map { RigLegacyBindings.definition(it, source) }

    /** Each captured mesh gets a stable branch, so regrouping by the current parent never renames outputs. */
    fun deformerId(instance: RigModifierInstance, mesh: String, canonical: String) = instance.outputId("$mesh/deformer:$canonical")
    fun partId(instance: RigModifierInstance, mesh: String, canonical: String) = instance.outputId("$mesh/part:$canonical")

    private fun scaffold(rig: RigIR, instance: RigModifierInstance, source: RigModifierSource, checkpoint: () -> Unit): RigIR {
        var current = PuppetIr.toPuppet(rig)
        for (target in instance.targets) {
            checkpoint()
            val template = source.scaffoldFor(target, checkpoint)
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
            require(clonedDeformers.none { added -> current.deformers.any { it.id == added.id } }) { "Binding scaffold output ID collision: ${instance.id}" }
            require(clonedParts.none { added -> current.parts.any { it.id == added.id } }) { "Binding scaffold part ID collision: ${instance.id}" }
            current = current.copy(deformers = current.deformers + clonedDeformers, parts = current.parts + clonedParts,
                rootChildren = current.rootChildren + partBranch.filter { partParents[it] !in partBranch }.map { OrgChild.Part(remap.part(it)) })
                .withDerivedRenderRoot()
            val destination = remap.deformer(requireNotNull(desired.parentDeformerId))
            current = RigStructureEdits.apply(current, listOf(buildJsonObject {
                put("action", "bind"); put("kind", "mesh"); put("id", target.id); put("parent_id", destination.raw); put("space", "canvas")
            }))
            if (orgParent != null) current = RigStructureEdits.apply(current, listOf(buildJsonObject {
                put("action", "move"); put("kind", "mesh"); put("id", target.id); put("parent_id", remap.part(orgParent).raw)
            }))
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
