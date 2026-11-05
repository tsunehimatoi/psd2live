package io.github.psd2live.core.modifier

import io.github.psd2live.format.model.Parameter
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.format.model.Deformer
import io.github.psd2live.core.GeneratedOverrideIssue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/** An adapter for a version of an existing algorithm; it receives the actual preceding result. */
internal class RigModifierDefinition(
    val type: String,
    val version: Int,
    val requires: Set<String> = emptySet(),
    val exclusiveFamily: String? = null,
    val parameters: (RigModifierInstance) -> List<Parameter> = { emptyList() },
    val validate: (RigIR, RigModifierInstance) -> Unit = { _, _ -> },
    val composableKinds: Set<String> = emptySet(),
    val providers: (RigModifierInstance) -> Map<String, String> = { emptyMap() },
    val appliedConfig: (RigModifierInstance) -> JsonObject = { JsonObject(emptyMap()) },
    /** Explicit composable transformations, distinct from reusing an unchanged standard definition. */
    val resolveParameter: (RigModifierInstance, Parameter, Parameter) -> Parameter? = { _, _, _ -> null },
    val references: (RigIR, RigIR, RigModifierInstance) -> List<ModifierContribution> = { _, _, _ -> emptyList() },
    val motionInputs: (RigModifierInstance) -> Set<String> = { emptySet() },
    val motionOutputs: (RigModifierInstance) -> Set<String> = { emptySet() },
    val provides: Set<String> = emptySet(),
    val evaluate: (RigIR, RigModifierInstance, () -> Unit) -> RigIR,
    val pluginReference: ModifierPluginReference? = null,
    val plugin: ModifierAlgorithmPlugin = ModifierAlgorithmPlugin(evaluate),
    /** Null keeps source-aware algorithms on the conservative ordered-prefix cache. */
    val cacheReads: Set<String>? = null,
)

internal class RigModifierRegistry(definitions: List<RigModifierDefinition>) {
    private val entries = definitions.associateBy { Triple(it.type, it.version, it.pluginReference) }

    init {
        require(entries.size == definitions.size) { "Duplicate modifier algorithm version" }
        require(definitions.all { it.type.isNotBlank() && it.version > 0 }) { "Invalid modifier definition" }
        val known = definitions.mapTo(HashSet()) { it.type }
        require(definitions.filter { it.pluginReference == null }.all { it.requires.all(known::contains) }) { "Unknown modifier dependency" }
        val visiting = HashSet<String>()
        val visited = HashSet<String>()
        fun visit(type: String) {
            if (type in visited) return
            require(visiting.add(type)) { "Cyclic modifier dependency: $type" }
            definitions.filter { it.type == type }.flatMap { it.requires }.distinct().filter { it in known }.forEach(::visit)
            visiting.remove(type)
            visited.add(type)
        }
        known.forEach(::visit)
    }

    fun definition(instance: RigModifierInstance): RigModifierDefinition = requireNotNull(entries[Triple(instance.type, instance.version, instance.plugin)]) {
        "Unsupported modifier algorithm: ${instance.type}@${instance.version}"
    }.also { require(it.pluginReference == instance.plugin) { "Missing pinned plugin for ${instance.type}: ${instance.plugin}" } }

    fun provides(actual: String, required: String): Boolean = actual == required || entries.values.any { it.type == actual && required in it.provides }

    val definitions: List<RigModifierDefinition> get() = entries.values.toList()
}

internal enum class ModifierStatus { ACTIVE, DISABLED, MISSING_DEPENDENCY, MISSING_PLUGIN }
internal enum class ModifierChange { ADDED, MODIFIED, REMOVED, REUSED }
internal data class ModifierMaskRelation(val target: String, val before: List<String>, val after: List<String>,
    val beforeInverted: Boolean, val afterInverted: Boolean)
internal data class ModifierContribution(val kind: String, val id: String, val change: ModifierChange, val mask: ModifierMaskRelation? = null, val physicsSlot: String? = null)
internal data class ModifierKeyform(val kind: String, val id: String, val parameter: String, val channel: String? = null, val key: Map<String, Float>? = null)
internal data class ModifierOverrideResult(
    val rig: RigIR,
    val issues: List<GeneratedOverrideIssue> = emptyList(),
    val parameterOverrides: Map<String, Parameter> = emptyMap(),
)
internal data class ModifierEvaluation(
    val instance: RigModifierInstance,
    val status: ModifierStatus,
    val missingDependencies: Set<String> = emptySet(),
    val contributions: List<ModifierContribution> = emptyList(),
    val ownedKeyforms: List<ModifierKeyform> = emptyList(),
    val overrideIssues: List<GeneratedOverrideIssue> = emptyList(),
)
internal data class RigModifierResult(val rig: RigIR, val entries: List<ModifierEvaluation>)
internal data class AppliedModifierPrefix(val authored: RigIR, val stack: RigModifierStack, val result: RigModifierResult)

/** One successful evaluation per pipeline. Failed/cancelled candidates never replace the snapshot. */
internal class RigModifierEvaluationCache {
    data class Snapshot(val context: Any, val authored: RigIR, val stack: RigModifierStack,
                        val entries: List<ModifierEvaluation>, val outputs: List<RigIR>, val plugins: Long = ModifierPluginStore.shared.revision.value,
                        val algorithms: Map<String, ModifierInputCache.Entry> = emptyMap())
    @Volatile var snapshot: Snapshot? = null
}

/**
 * Ordered, side-effect-free evaluation. The host owns cancellation, persistence, CAS and history. No adapter result
 * escapes until all adapters and overrides have succeeded. An override replayer must likewise be a pure function.
 */
internal class RigModifierEvaluator(
    private val registry: RigModifierRegistry,
    private val sourceTargetExists: (ModifierTarget) -> Boolean = { false },
    private val physicsOrder: List<String> = emptyList(),
    private val applyOverrides: (RigIR, RigModifierInstance, List<JsonObject>) -> ModifierOverrideResult = { rig, _, edits ->
        require(edits.isEmpty()) { "This evaluator has no override replayer" }; ModifierOverrideResult(rig)
    },
) {
    fun evaluate(authored: RigIR, stack: RigModifierStack,
                 cache: RigModifierEvaluationCache? = null, context: Any = Unit,
                 checkpoint: () -> Unit = {}): RigModifierResult {
        val pluginRevision = ModifierPluginStore.shared.revision.value
        validateOrder(stack)
        var current = authored
        val available = stack.appliedCapabilities.toMutableList()
        val entries = ArrayList<ModifierEvaluation>()
        val exclusive = HashMap<Pair<String, ModifierTarget>, String>()
        val writers = HashMap<Pair<String, String>, Pair<String, RigModifierDefinition>>()
        val saved = cache?.snapshot?.takeIf { it.plugins == pluginRevision && it.context == context && it.authored == authored &&
            it.stack.appliedCapabilities == stack.appliedCapabilities }
        val reuse = saved?.stack?.instances?.zip(stack.instances)?.takeWhile { (a, b) ->
            a == b && saved.stack.overrides[a.id].orEmpty() == stack.overrides[b.id].orEmpty()
        }?.size ?: 0
        val outputs = ArrayList<RigIR>()
        val algorithmCache = saved?.algorithms.orEmpty().filterKeys { id -> stack.instances.any { it.id == id } }.toMutableMap()
        for ((index, instance) in stack.instances.withIndex()) {
            checkpoint()
            if (!ModifierPluginCatalog.available(instance)) {
                entries += ModifierEvaluation(instance, if (instance.enabled) ModifierStatus.MISSING_PLUGIN else ModifierStatus.DISABLED)
                outputs += current
                continue
            }
            val definition = registry.definition(instance)
            if (index < reuse) {
                io.github.psd2live.core.RigBuildProfile.count("modifier cache hits")
                val entry = saved!!.entries[index]
                current = saved.outputs[index]
                entries += entry
                outputs += current
                if (entry.status == ModifierStatus.ACTIVE) {
                    definition.exclusiveFamily?.let { family -> instance.targets.forEach { exclusive[family to it] = instance.id } }
                    entry.contributions.filter { it.change != ModifierChange.REUSED }.forEach {
                        writers[it.kind to it.id] = instance.id to definition
                    }
                    available += ModifierCapability(instance.type, instance.version, instance.targets, instance.id,
                        definition.appliedConfig(instance), instance.outputAliases)
                }
                continue
            }
            if (!instance.enabled) {
                entries += ModifierEvaluation(instance, ModifierStatus.DISABLED)
                outputs += current
                continue
            }
            val missing = definition.requires.filterTo(linkedSetOf()) { type ->
                val provider = definition.providers(instance)[type]
                instance.targets.any { target -> available.none { registry.provides(it.type, type) && target in it.targets && (provider == null || it.id == provider) } }
            }
            for ((parameter, provider) in instance.parameterProviders) {
                if (available.none { it.id == provider } || current.parameters.none { it.id == parameter }) {
                    missing += "parameter:$parameter@$provider"
                }
            }
            definition.motionInputs(instance).filter { id -> current.clips.none { it.id == id } }
                .forEach { missing += "motion:$it" }
            if (missing.isNotEmpty()) {
                entries += ModifierEvaluation(instance, ModifierStatus.MISSING_DEPENDENCY, missing)
                outputs += current
                continue
            }
            definition.exclusiveFamily?.let { family -> instance.targets.forEach { target ->
                val previous = exclusive.put(family to target, instance.id)
                require(previous == null) { "Modifiers $previous and ${instance.id} are mutually exclusive on ${target.id}" }
            } }
            validateTargets(current, instance)
            definition.validate(current, instance)
            val declared = io.github.psd2live.core.RigBuildProfile.stage("modifier parameters: ${instance.type}") { definition.parameters(instance) }
            require(declared.map { it.id }.distinct().size == declared.size) { "${instance.id} declares duplicate parameters" }
            val existing = current.parameters.associateBy { it.id }
            val resolved = declared.associate { parameter ->
                val before = existing[parameter.id]
                val desired = if (before == null || compatible(before, parameter)) parameter
                    else definition.resolveParameter(instance, before, parameter)
                require(desired != null && desired.id == parameter.id) { "${instance.id}: incompatible parameter ${parameter.id}" }
                parameter.id to desired
            }
            io.github.psd2live.core.RigBuildProfile.count("modifier cache misses")
            val before = current
            val inputKey = definition.cacheReads?.let { ModifierInputCache.input(before, it) }
            val cached = algorithmCache[instance.id]?.takeIf { it.instance == instance && it.input == inputKey }
            val generated = if (cached != null) {
                io.github.psd2live.core.RigBuildProfile.count("modifier dependency cache hits")
                cached.patch.apply(before)
            } else io.github.psd2live.core.RigBuildProfile.stage("modifier evaluate: ${instance.type}") { definition.plugin.evaluate(before, instance, checkpoint) }
            if (inputKey != null && cached == null) algorithmCache[instance.id] = ModifierInputCache.Entry(instance, inputKey, ModifierInputCache.Patch(before, generated))
            checkpoint()
            validateParameters(before, generated, instance, resolved)
            declared.forEach { parameter ->
                val actual = generated.parameters.singleOrNull { it.id == parameter.id }
                require(actual != null && compatible(actual, resolved.getValue(parameter.id))) { "${instance.id} did not produce parameter ${parameter.id}" }
            }
            val overrides = stack.overrides[instance.id].orEmpty()
            val overridden = if (overrides.isEmpty()) ModifierOverrideResult(generated) else
                io.github.psd2live.core.RigBuildProfile.stage("modifier overrides") { applyOverrides(generated, instance, overrides) }
            current = overridden.rig.let { output ->
                if (physicsOrder.isEmpty()) output else output.copy(physics = output.physics.copy(
                    groups = io.github.psd2live.core.PhysicsCatalog.ordered(output.physics.groups, physicsOrder) { it.id }))
            }
            require(overridden.parameterOverrides.keys.none { it in existing }) {
                "${instance.id} cannot override the range of a parameter provided upstream"
            }
            validateParameters(generated, current, instance, overridden.parameterOverrides)
            val contributions = contributions(before, current) + definition.references(before, current, instance) + declared.filter { it.id in existing && compatible(existing.getValue(it.id), current.parameters.single { actual -> actual.id == it.id }) }.map {
                ModifierContribution("parameter", it.id, ModifierChange.REUSED)
            }
            for (contribution in contributions.filter { it.change != ModifierChange.REUSED }) {
                val key = contribution.kind to contribution.id
                val previous = writers[key]
                require(previous == null || contribution.kind in definition.composableKinds && contribution.kind in previous.second.composableKinds) {
                    "${previous?.first} and ${instance.id} have incompatible writes to ${contribution.kind} ${contribution.id}"
                }
                writers[key] = instance.id to definition
            }
            entries += ModifierEvaluation(instance, ModifierStatus.ACTIVE, contributions = contributions,
                ownedKeyforms = ownedKeyforms(before, generated), overrideIssues = overridden.issues)
            available += ModifierCapability(instance.type, instance.version, instance.targets, instance.id, definition.appliedConfig(instance), instance.outputAliases)
            outputs += current
        }
        checkpoint()
        require(pluginRevision == ModifierPluginStore.shared.revision.value) { "Algorithm plugins changed during evaluation; retry the edit" }
        cache?.snapshot = RigModifierEvaluationCache.Snapshot(context, authored, stack, entries, outputs, pluginRevision, algorithmCache)
        return RigModifierResult(current, entries)
    }

    /** A dependency supplied only below its consumer is an invalid order, never an invitation to sort silently. */
    fun validateOrder(stack: RigModifierStack) {
        stack.instances.forEachIndexed { index, instance ->
            if (!ModifierPluginCatalog.available(instance)) return@forEachIndexed
            val definition = registry.definition(instance)
            if (!instance.enabled) return@forEachIndexed
            val motionInputs = definition.motionInputs(instance)
            for (later in stack.instances.drop(index + 1).filter { it.enabled && ModifierPluginCatalog.available(it) }) {
                val conflicts = motionInputs.intersect(registry.definition(later).motionOutputs(later))
                require(conflicts.isEmpty()) { "${instance.id} must follow ${later.id} for motions ${conflicts.joinToString()}" }
            }
            for ((parameter, provider) in instance.parameterProviders) {
                require(provider != instance.id && stack.instances.drop(index + 1).none { it.id == provider && it.enabled }) {
                    "${instance.id} must follow $provider for parameter $parameter"
                }
            }
            for (command in stack.overrides[instance.id].orEmpty()) {
                for (provider in command["providers"]?.jsonArray.orEmpty()) {
                    val id = provider.jsonPrimitive.content
                    require(stack.instances.drop(index + 1).none { it.id == id && it.enabled }) {
                        "${instance.id} must follow $id for its generated-content override"
                    }
                }
            }
            for (type in definition.requires) for (target in instance.targets) {
                val provider = definition.providers(instance)[type]
                val earlier = stack.appliedCapabilities.any { registry.provides(it.type, type) && target in it.targets && (provider == null || it.id == provider) } ||
                    stack.instances.take(index).any { it.enabled && registry.provides(it.type, type) && target in it.targets && (provider == null || it.id == provider) }
                val later = stack.instances.drop(index + 1).any { it.enabled && registry.provides(it.type, type) && target in it.targets && (provider == null || it.id == provider) }
                require(earlier || !later) { "${instance.id} must follow ${provider ?: type} ($type) on ${target.id}" }
            }
        }
    }

    /**
     * Consume the complete prefix, including disabled entries, into ordinary content. Re-evaluation of the suffix is
     * checked for equality before returning the candidate. Providers must encode any durable dependency in their IR
     * output: an instance's identity is not required after its output has been applied.
     */
    fun applyThrough(authored: RigIR, stack: RigModifierStack, id: String, checkpoint: () -> Unit = {}): AppliedModifierPrefix {
        val end = stack.index(id) + 1
        val complete = evaluate(authored, stack, checkpoint = checkpoint)
        val prefix = evaluate(authored, stack.copy(instances = stack.instances.take(end),
            overrides = stack.overrides.filterKeys { owner -> owner in stack.retiredIds || stack.instances.take(end).any { it.id == owner } }), checkpoint = checkpoint)
        require(prefix.entries.none { it.status == ModifierStatus.MISSING_DEPENDENCY || it.status == ModifierStatus.MISSING_PLUGIN }) { "Cannot apply a prefix with missing dependencies or plugins" }
        require(prefix.entries.none { it.overrideIssues.any { issue -> issue.kind == io.github.psd2live.core.GeneratedOverrideIssueKind.ORPHANED } }) {
            "Cannot apply a prefix with overrides that no longer match their generated content"
        }
        val consumed = stack.instances.take(end).mapTo(HashSet()) { it.id }
        val capabilities = prefix.entries.filter { it.status == ModifierStatus.ACTIVE }.map {
            ModifierCapability(it.instance.type, it.instance.version, it.instance.targets, it.instance.id,
                registry.definition(it.instance).appliedConfig(it.instance), it.instance.outputAliases)
        }
        val baked = prefix.entries.filter { it.status == ModifierStatus.ACTIVE }.mapTo(HashSet()) { it.instance.id }
        val remaining = stack.copy(instances = stack.instances.drop(end), overrides = stack.overrides - baked,
            retiredIds = stack.retiredIds + consumed, appliedCapabilities = (stack.appliedCapabilities + capabilities).distinct())
        val result = evaluate(prefix.rig, remaining, checkpoint = checkpoint)
        require(complete.rig == result.rig) { "Applying this prefix would change the remaining modifiers' result" }
        return AppliedModifierPrefix(prefix.rig, remaining, result)
    }

    private fun compatible(a: Parameter, b: Parameter) =
        a.min == b.min && a.max == b.max && a.default == b.default && a.blend == b.blend && a.repeat == b.repeat && a.keys == b.keys

    private fun validateParameters(before: RigIR, after: RigIR, instance: RigModifierInstance, resolved: Map<String, Parameter> = emptyMap()) {
        require(after.parameters.map { it.id }.distinct().size == after.parameters.size) { "${instance.id} produced duplicate parameter IDs" }
        val previous = before.parameters.associateBy { it.id }
        after.parameters.forEach { parameter -> previous[parameter.id]?.let {
            require(compatible(it, parameter) || resolved[parameter.id]?.let { expected -> compatible(expected, parameter) } == true) { "${instance.id} changed shared parameter ${parameter.id}" }
        } }
    }

    private fun validateTargets(rig: RigIR, instance: RigModifierInstance) {
        for (target in instance.targets) {
            val present = when (target.kind) {
                "mesh" -> rig.meshes.any { it.id == target.id }
                "deformer" -> rig.deformers.any { it.id == target.id }
                "part" -> rig.parts.any { it.id == target.id }
                "layer" -> sourceTargetExists(target)
                else -> false
            }
            require(present) { "${instance.id}: missing ${target.kind} ${target.id}" }
        }
    }

    private fun ownedKeyforms(before: RigIR, after: RigIR): List<ModifierKeyform> = buildList {
        fun <F> addedCells(kind: String, id: String, previous: io.github.psd2live.format.model.KeyGrid<F>?,
                           next: io.github.psd2live.format.model.KeyGrid<F>?, channel: String? = null) {
            if (previous == null || next == null || previous.axes.isEmpty() ||
                previous.axes.map { it.parameter }.toSet() != next.axes.map { it.parameter }.toSet()) return
            val old = previous.axes.associateBy { it.parameter }
            for (cell in next.cells) {
                val key = next.axes.mapIndexed { index, axis -> axis.parameter to axis.keys[cell.coordinate[index]] }.toMap()
                val changed = key.entries.firstOrNull { (parameter, value) -> old.getValue(parameter).keys.toArray().none { it == value } } ?: continue
                add(ModifierKeyform(kind, id, changed.key, channel, key))
            }
        }
        fun channels(kind: String, id: String, previous: io.github.psd2live.format.model.Channels, next: io.github.psd2live.format.model.Channels) {
            for ((channel, grid) in next) {
                addedCells(kind, id, previous[channel], grid, channel.name.lowercase())
                val existing = previous[channel]?.axes.orEmpty().mapTo(HashSet()) { it.parameter }
                grid.axes.filter { it.parameter !in existing }.forEach { add(ModifierKeyform(kind, id, it.parameter, channel.name.lowercase())) }
            }
        }
        val warps = before.deformers.filterIsInstance<Deformer.Warp>().associateBy { it.id }
        for (warp in after.deformers.filterIsInstance<Deformer.Warp>()) {
            addedCells("warp", warp.id, warps[warp.id]?.lattice, warp.lattice)
            val previous = warps[warp.id]?.lattice?.axes.orEmpty().mapTo(HashSet()) { it.parameter }
            warp.lattice?.axes.orEmpty().filter { it.parameter !in previous }.forEach {
                add(ModifierKeyform("warp", warp.id, it.parameter))
            }
            channels("warp", warp.id, warps[warp.id]?.channels.orEmpty(), warp.channels)
        }
        val meshes = before.meshes.associateBy { it.id }
        for (mesh in after.meshes) {
            addedCells("mesh", mesh.id, meshes[mesh.id]?.offsets, mesh.offsets)
            val previous = meshes[mesh.id]?.offsets?.axes.orEmpty().mapTo(HashSet()) { it.parameter }
            mesh.offsets?.axes.orEmpty().filter { it.parameter !in previous }.forEach {
                add(ModifierKeyform("mesh", mesh.id, it.parameter))
            }
            channels("mesh", mesh.id, meshes[mesh.id]?.channels.orEmpty(), mesh.channels)
        }
    }

    companion object {
    internal fun contributions(before: RigIR, after: RigIR): List<ModifierContribution> = buildList {
        fun <T> compare(kind: String, left: List<T>, right: List<T>, key: (T) -> String) {
            if (left === right) return
            val a = left.associateBy(key); val b = right.associateBy(key)
            require(a.size == left.size && b.size == right.size) { "Duplicate $kind IDs in modifier output" }
            for ((id, value) in b) when {
                id !in a -> add(ModifierContribution(kind, id, ModifierChange.ADDED))
                a[id] != value -> add(ModifierContribution(kind, id, ModifierChange.MODIFIED))
            }
            for (id in a.keys - b.keys) add(ModifierContribution(kind, id, ModifierChange.REMOVED))
        }
        compare("parameter", before.parameters, after.parameters) { it.id }
        compare("part", before.parts, after.parts) { it.id }
        compare("deformer", before.deformers, after.deformers) { it.id }
        compare("mesh", before.meshes, after.meshes) { it.id }
        val previousMeshes = before.meshes.associateBy { it.id }
        val nextMeshes = after.meshes.associateBy { it.id }
        for (id in previousMeshes.keys + nextMeshes.keys) {
            val previous = previousMeshes[id]; val next = nextMeshes[id]
            val oldMasks = previous?.maskedBy.orEmpty(); val newMasks = next?.maskedBy.orEmpty()
            val oldInverted = previous?.invertMask == true; val newInverted = next?.invertMask == true
            if (oldMasks != newMasks || oldInverted != newInverted) {
                val change = when { oldMasks.isEmpty() && newMasks.isNotEmpty() -> ModifierChange.ADDED
                    newMasks.isEmpty() && oldMasks.isNotEmpty() -> ModifierChange.REMOVED
                    else -> ModifierChange.MODIFIED }
                add(ModifierContribution("relations", "mask:$id", change,
                    ModifierMaskRelation(id, oldMasks, newMasks, oldInverted, newInverted)))
            }
        }
        compare("glue", before.glues, after.glues) { it.id ?: "${it.meshA.length}:${it.meshA}${it.meshB}" }
        compare("physics", before.physics.groups, after.physics.groups) { it.id }
        compare("motion", before.clips, after.clips) { it.id }
        if (before.parameterLinks != after.parameterLinks) add(ModifierContribution("relations", "parameter-links", ModifierChange.MODIFIED))
        if (before.parameterRoles != after.parameterRoles) add(ModifierContribution("relations", "parameter-roles", ModifierChange.MODIFIED))
        if (before.rootChildren != after.rootChildren) add(ModifierContribution("hierarchy", "root", ModifierChange.MODIFIED))
        if (before.parameterTree != after.parameterTree) add(ModifierContribution("hierarchy", "parameters", ModifierChange.MODIFIED))
    }
    }
}
