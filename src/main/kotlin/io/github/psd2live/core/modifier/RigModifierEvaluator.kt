package io.github.psd2live.core.modifier

import io.github.psd2live.format.model.Parameter
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.format.model.Deformer
import io.github.psd2live.core.GeneratedOverrideIssue
import io.github.psd2live.core.RigBuildProfile
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/** An adapter for a version of an existing algorithm; it receives the actual preceding result. */
internal data class RigModifierDefinition(
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
    val plugin: ModifierAlgorithmPlugin,
    val pluginReference: ModifierPluginReference? = null,
    /** Null keeps source-aware algorithms on the conservative ordered-prefix cache. */
    val cacheReads: Set<String>? = null,
    /** Required for direct-output plugins; checked before any change is applied. */
    val changeWrites: Set<String>? = null,
    val upstreamInputs: Boolean = false,
    val authorContext: (RigModifierInstance) -> ModifierAlgorithmContext = { ModifierAlgorithmContext.Empty },
) {
    val optionalUpstream: Set<String> get() = (plugin as? ModifierOptionalUpstream)?.optionalUpstream.orEmpty()
    val explicitVersion: Boolean get() = type == (plugin as? ModifierVersionSelection)?.canonicalOptInType
}

/** Optional capabilities are passed to a contextual plugin when active, without blocking evaluation. */
internal interface ModifierOptionalUpstream {
    val optionalUpstream: Set<String>
}

/** A published version can require explicit selection under its canonical type. */
internal interface ModifierVersionSelection {
    val canonicalOptInType: String?
}

internal class RigModifierRegistry(definitions: List<RigModifierDefinition>) {
    private val entries = definitions.associateBy { Triple(it.type, it.version, it.pluginReference) }
    private val defaults by lazy { definitions.filterNot { it.explicitVersion }.groupBy { it.type }
        .mapValues { (_, candidates) -> candidates.maxBy { it.version } } }

    init {
        require(entries.size == definitions.size) { "Duplicate modifier algorithm version" }
        require(definitions.all { it.type.isNotBlank() && it.version > 0 }) { "Invalid modifier definition" }
        val graph = HashMap<String, MutableSet<String>>()
        for (definition in definitions) {
            for (provided in definition.provides + definition.type)
                graph.getOrPut(provided) { LinkedHashSet() }.addAll(definition.requires)
        }
        val known = graph.keys
        require(definitions.filter { it.pluginReference == null }.all { it.requires.all(known::contains) }) { "Unknown modifier dependency" }
        val visiting = HashSet<String>()
        val visited = HashSet<String>()
        fun visit(type: String) {
            if (type in visited) return
            require(visiting.add(type)) { "Cyclic modifier dependency: $type" }
            graph.getValue(type).forEach { if (it in known) visit(it) }
            visiting.remove(type)
            visited.add(type)
        }
        known.forEach(::visit)
    }

    fun definition(instance: RigModifierInstance): RigModifierDefinition = requireNotNull(
        definition(instance.type, instance.version, instance.plugin)) {
        "Unsupported modifier algorithm: ${instance.type}@${instance.version}"
    }

    private fun definition(type: String, version: Int, plugin: ModifierPluginReference?): RigModifierDefinition? =
        (if (plugin != null && plugin == BuiltinModifierPlugins.reference(type, version)) entries[Triple(type, version, null)] else null)
            ?: entries[Triple(type, version, plugin)]

    fun provides(instance: RigModifierInstance, required: String): Boolean = instance.type == required ||
        required in definition(instance.type, instance.version, instance.plugin)?.provides.orEmpty()

    fun provides(capability: ModifierCapability, required: String): Boolean = capability.type == required ||
        required in (capability.provides ?: definition(capability.type, capability.version, capability.plugin)?.provides.orEmpty())

    val definitions: List<RigModifierDefinition> get() = entries.values.toList()

    fun defaultDefinition(type: String): RigModifierDefinition? = defaults[type]

    fun storedType(type: String): String = (defaults[type]?.plugin as? ModifierVersionSelection)?.canonicalOptInType ?: type
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
                        val algorithms: Map<String, ModifierInputCache.Entry> = emptyMap(),
                        val authorContexts: Map<String, ModifierAlgorithmContext> = emptyMap(),
                        val readAlgorithms: Map<String, ModifierInputCache.ReadEntry> = emptyMap())
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
    private val resolveRasters: ModifierRasterResolver = RigModifierRasterAssets::resolve,
    private val rawSourceRotations: Boolean = false,
    private val applyOverrides: (RigIR, RigModifierInstance, List<JsonObject>) -> ModifierOverrideResult = { rig, _, edits ->
        require(edits.isEmpty()) { "This evaluator has no override replayer" }; ModifierOverrideResult(rig)
    },
) {
    fun evaluate(authored: RigIR, stack: RigModifierStack,
                 cache: RigModifierEvaluationCache? = null, context: Any = Unit,
                 reuseCapturedAuthorInputs: Boolean = false,
                 checkpoint: () -> Unit = {}): RigModifierResult = ModifierReadAhead().use {
        evaluate(authored, stack, cache, context, reuseCapturedAuthorInputs, it, checkpoint)
    }

    // Reuse captures only when the caller's context keys all author inputs outside the evaluated prefix.
    private fun evaluate(authored: RigIR, stack: RigModifierStack, cache: RigModifierEvaluationCache?, context: Any,
                         reuseCapturedAuthorInputs: Boolean,
                         ahead: ModifierReadAhead, checkpoint: () -> Unit): RigModifierResult {
        val pluginRevision = ModifierPluginStore.shared.revision.value
        val definitions = resolveDefinitions(stack)
        validateOrder(stack, definitions)
        var current = authored
        val available = stack.appliedCapabilities.toMutableList()
        val entries = ArrayList<ModifierEvaluation>()
        val exclusive = HashMap<Pair<String, ModifierTarget>, String>()
        val writers = HashMap<Pair<String, String>, Pair<String, RigModifierDefinition>>()
        val previous = cache?.snapshot?.takeIf { it.plugins == pluginRevision }
        val saved = previous?.takeIf { it.context == context && it.authored == authored &&
            it.stack.appliedCapabilities == stack.appliedCapabilities }
        val authorContexts = HashMap<String, ModifierAlgorithmContext>()
        fun capture(instance: RigModifierInstance): ModifierAlgorithmContext = authorContexts.getOrPut(instance.id) {
            checkpoint()
            registry.definition(instance).authorContext(instance)
        }
        val reuse = saved?.stack?.instances?.zip(stack.instances)?.takeWhile { (a, b) ->
            a == b && saved.stack.overrides[a.id].orEmpty() == stack.overrides[b.id].orEmpty() &&
                (reuseCapturedAuthorInputs || (saved.authorContexts[a.id]?.let { it == capture(b) } ?: true))
        }?.size ?: 0
        val outputs = ArrayList<RigIR>()
        val instanceIds = stack.instances.mapTo(HashSet()) { it.id }
        val algorithmCache = saved?.algorithms.orEmpty().filterKeys { it in instanceIds }.toMutableMap()
        // Tracked changes are checked against their actual reads and write preconditions.
        val readCache = previous?.readAlgorithms.orEmpty().filterKeys { it in instanceIds }.toMutableMap()
        var unavailableUpstream: String? = null
        for ((index, instance) in stack.instances.withIndex()) {
            checkpoint()
            val definition = definitions[index]
            if (definition == null) {
                entries += ModifierEvaluation(instance, if (instance.enabled) ModifierStatus.MISSING_PLUGIN else ModifierStatus.DISABLED)
                if (instance.enabled) unavailableUpstream = instance.id
                outputs += current
                continue
            }
            if (unavailableUpstream != null) {
                entries += ModifierEvaluation(instance, if (instance.enabled) ModifierStatus.MISSING_DEPENDENCY else ModifierStatus.DISABLED,
                    missingDependencies = if (instance.enabled) setOf("plugin:$unavailableUpstream") else emptySet())
                outputs += current
                continue
            }
            if (index < reuse) {
                RigBuildProfile.count("modifier cache hits")
                val entry = saved!!.entries[index]
                if (reuseCapturedAuthorInputs) saved.authorContexts[instance.id]?.let {
                    authorContexts[instance.id] = it
                    if (it != ModifierAlgorithmContext.Empty) RigBuildProfile.count("modifier author context cache hits")
                }
                current = saved.outputs[index]
                entries += entry
                outputs += current
                if (entry.status == ModifierStatus.ACTIVE) {
                    definition.exclusiveFamily?.let { family -> instance.targets.forEach { exclusive[family to it] = instance.id } }
                    entry.contributions.filter { it.change != ModifierChange.REUSED }.forEach {
                        writers[it.kind to it.id] = instance.id to definition
                    }
                    available += instance.capability(definition.appliedConfig(instance), definition.provides)
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
                instance.targets.any { target -> available.none { registry.provides(it, type) && target in it.targets && (provider == null || it.id == provider) } }
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
            RigBuildProfile.modifierStage("validation", instance.type) { definition.validate(current, instance) }
            val declared = RigBuildProfile.modifierStage("parameters", instance.type) { definition.parameters(instance) }
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
            val capturedContext = RigBuildProfile.modifierStage("input", instance.type) { capture(instance) }
            val authorContext = if (definition.upstreamInputs || definition.plugin is ReadingModifierAlgorithmPlugin) {
                val providers = definition.providers(instance)
                val capabilities = (definition.requires + definition.optionalUpstream).sorted().flatMap { required ->
                    available.filter { registry.provides(it, required) &&
                        it.targets.any { target -> target in instance.targets } &&
                        (providers[required] == null || it.id == providers[required]) }.map {
                        ModifierUpstreamCapability(required, it.type, it.version, it.targets, it.id, it.config, it.outputAliases, it.plugin)
                    }
                }
                capturedContext.withUpstream(capabilities)
            } else capturedContext
            val inputKey = if (definition.plugin is ReadingModifierAlgorithmPlugin) null else
                definition.cacheReads?.let { ModifierInputCache.input(before, it) to authorContext }
            val cached = algorithmCache[instance.id]?.takeIf { it.instance == instance && it.input == inputKey }
            val evaluated = if (cached != null) {
                io.github.psd2live.core.RigBuildProfile.count("modifier dependency cache hits")
                cached.patch.apply(before)
            } else RigBuildProfile.modifierStage("evaluate", instance.type) {
                val plugin = definition.plugin
                when (plugin) {
                    is ReadingModifierAlgorithmPlugin -> {
                        val speculative = ahead.take(index)
                        val cachedRead = (speculative ?: readCache[instance.id])?.takeIf {
                            it.instance == instance && it.plugin === plugin && it.context == authorContext && it.reads.matches(before, authorContext)
                        }
                        val reused = cachedRead?.let {
                            it.changes.requireWrites(requireNotNull(definition.changeWrites) { "${instance.type}: missing change write contract" })
                            // Writes have their own preconditions even when a plugin did not read that object.
                            try { it.changes.applyWithResources(before, checkpoint, resolveRasters) } catch (_: IllegalArgumentException) { null }
                        }
                        if (reused != null) {
                            if (speculative != null && cachedRead === speculative) {
                                RigBuildProfile.count("modifier parallel evaluations")
                                readCache[instance.id] = speculative
                            } else RigBuildProfile.count("modifier read cache hits")
                            reused
                        } else {
                            val next = stack.instances.getOrNull(index + 1)
                            val nextDefinition = definitions.getOrNull(index + 1)
                            val nextPlugin = nextDefinition?.plugin as? ReadingModifierAlgorithmPlugin
                            if (next != null && next.enabled && nextDefinition != null && nextPlugin != null &&
                                nextDefinition.requires.isEmpty() && nextDefinition.optionalUpstream.isEmpty() &&
                                nextDefinition.exclusiveFamily == null && next.id !in readCache &&
                                physicsOrder.isEmpty() && stack.overrides[instance.id].isNullOrEmpty() &&
                                ahead.canOverlap(definition, nextDefinition) &&
                                next.parameterProviders.all { (parameter, provider) ->
                                    available.any { it.id == provider } && parameter in existing
                                }) {
                                // Source capture stays on the caller. A rejected probe is validated in stack order.
                                val nextContext = try {
                                    if (nextDefinition.motionInputs(next).all { id -> before.clips.any { it.id == id } })
                                        capture(next).withUpstream(emptyList())
                                    else null
                                } catch (_: IllegalArgumentException) { null }
                                if (nextContext != null) ahead.start(index + 1, next, nextPlugin, before, nextContext)
                            }
                            val view = ModifierEvaluationInput(before, authorContext)
                            val changes = plugin.changes(view, instance, checkpoint)
                            changes.requireWrites(requireNotNull(definition.changeWrites) { "${instance.type}: missing change write contract" })
                            changes.applyWithResources(before, checkpoint, resolveRasters).also {
                                readCache[instance.id] = ModifierInputCache.ReadEntry(instance, plugin, authorContext, view.reads(), changes)
                            }
                        }
                    }
                    is ChangingModifierAlgorithmPlugin -> plugin.changes(before, instance, authorContext, checkpoint).also {
                        it.requireWrites(requireNotNull(definition.changeWrites) { "${instance.type}: missing change write contract" })
                    }.applyWithResources(before, checkpoint, resolveRasters)
                    is ContextualModifierAlgorithmPlugin -> plugin.evaluate(before, instance, authorContext, checkpoint)
                    else -> plugin.evaluate(before, instance, checkpoint)
                }
            }
            val generated = if (rawSourceRotations && cached == null)
                RawSourceRotationTransfer.apply(before, evaluated, instance, authorContext) else evaluated
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
            val keyforms = io.github.psd2live.core.RigBuildProfile.stage("modifier keyform ownership") { ownedKeyforms(before, generated) }
            entries += ModifierEvaluation(instance, ModifierStatus.ACTIVE, contributions = contributions,
                ownedKeyforms = keyforms, overrideIssues = overridden.issues)
            available += instance.capability(definition.appliedConfig(instance), definition.provides)
            outputs += current
        }
        checkpoint()
        require(pluginRevision == ModifierPluginStore.shared.revision.value) { "Algorithm plugins changed during evaluation; retry the edit" }
        cache?.snapshot = RigModifierEvaluationCache.Snapshot(context, authored, stack, entries, outputs, pluginRevision, algorithmCache, authorContexts, readCache)
        return RigModifierResult(current, entries)
    }

    /** A dependency supplied only below its consumer is an invalid order, never an invitation to sort silently. */
    fun validateOrder(stack: RigModifierStack) = validateOrder(stack, resolveDefinitions(stack))

    private fun resolveDefinitions(stack: RigModifierStack) = stack.instances.map { instance ->
        if (ModifierPluginCatalog.available(instance)) registry.definition(instance) else null
    }

    private fun validateOrder(stack: RigModifierStack, definitions: List<RigModifierDefinition?>) {
        val instances = stack.instances
        // Missing plugins still occupy an enabled position, so they count for explicit provider order.
        val lastEnabled = HashMap<String, Int>(instances.size)
        for ((index, instance) in instances.withIndex()) if (instance.enabled) lastEnabled[instance.id] = index
        val motionOutputs = HashMap<Int, Set<String>>()
        for ((index, instance) in instances.withIndex()) {
            val definition = definitions[index] ?: continue
            if (!instance.enabled) continue
            val motionInputs = definition.motionInputs(instance)
            if (motionInputs.isNotEmpty()) for (laterIndex in index + 1..instances.lastIndex) {
                val producer = definitions[laterIndex] ?: continue
                val later = instances[laterIndex]
                if (!later.enabled) continue
                val outputs = motionOutputs.getOrPut(laterIndex) { producer.motionOutputs(later) }
                val conflicts = motionInputs.intersect(outputs)
                require(conflicts.isEmpty()) { "${instance.id} must follow ${later.id} for motions ${conflicts.joinToString()}" }
            }
            for ((parameter, provider) in instance.parameterProviders) {
                require(provider != instance.id && (lastEnabled[provider] ?: -1) <= index) {
                    "${instance.id} must follow $provider for parameter $parameter"
                }
            }
            for (command in stack.overrides[instance.id].orEmpty()) {
                for (provider in command["providers"]?.jsonArray.orEmpty()) {
                    val id = provider.jsonPrimitive.content
                    require((lastEnabled[id] ?: -1) <= index) {
                        "${instance.id} must follow $id for its generated-content override"
                    }
                }
            }
            if (definition.requires.isNotEmpty()) {
                val prefix = instances.subList(0, index)
                val suffix = instances.subList(index + 1, instances.size)
                for (type in definition.requires) for (target in instance.targets) {
                    val provider = definition.providers(instance)[type]
                    val earlier = stack.appliedCapabilities.any { registry.provides(it, type) && target in it.targets && (provider == null || it.id == provider) } ||
                        prefix.any { it.enabled && registry.provides(it, type) && target in it.targets && (provider == null || it.id == provider) }
                    val later = suffix.any { it.enabled && registry.provides(it, type) && target in it.targets && (provider == null || it.id == provider) }
                    require(earlier || !later) { "${instance.id} must follow ${provider ?: type} ($type) on ${target.id}" }
                }
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
            val definition = registry.definition(it.instance)
            it.instance.capability(definition.appliedConfig(it.instance), definition.provides)
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
        fun <F> forms(kind: String, id: String, previous: io.github.psd2live.format.model.KeyGrid<F>?,
                      next: io.github.psd2live.format.model.KeyGrid<F>?, channel: String? = null) {
            if (next == null || previous?.axes == next.axes) return
            val old = previous?.axes.orEmpty().associateBy { it.parameter }
            if (old.keys != next.axes.mapTo(HashSet()) { it.parameter }) {
                next.axes.filter { it.parameter !in old }.forEach { add(ModifierKeyform(kind, id, it.parameter, channel)) }
                return
            }
            val addedIndices = next.axes.map { axis ->
                val previousKeys = old.getValue(axis.parameter).keys
                BooleanArray(axis.keys.size) { key ->
                    (0 until previousKeys.size).none { previousKeys[it] == axis.keys[key] }
                }
            }
            if (addedIndices.none { keys -> keys.any { it } }) return
            for (cell in next.cells) {
                val changed = addedIndices.indices.firstOrNull { addedIndices[it][cell.coordinate[it]] } ?: continue
                val key = next.axes.mapIndexed { index, axis -> axis.parameter to axis.keys[cell.coordinate[index]] }.toMap()
                add(ModifierKeyform(kind, id, next.axes[changed].parameter, channel, key))
            }
        }
        fun channels(kind: String, id: String, previous: io.github.psd2live.format.model.Channels, next: io.github.psd2live.format.model.Channels) {
            if (previous === next) return
            for ((channel, grid) in next) forms(kind, id, previous[channel], grid, channel.name.lowercase())
        }
        if (before.deformers !== after.deformers) {
            val warps = before.deformers.filterIsInstance<Deformer.Warp>().associateBy { it.id }
            for (warp in after.deformers.filterIsInstance<Deformer.Warp>()) {
                val previous = warps[warp.id]
                if (previous === warp) continue
                forms("warp", warp.id, previous?.lattice, warp.lattice)
                channels("warp", warp.id, previous?.channels.orEmpty(), warp.channels)
            }
        }
        if (before.meshes !== after.meshes) {
            val meshes = before.meshes.associateBy { it.id }
            for (mesh in after.meshes) {
                val previous = meshes[mesh.id]
                if (previous === mesh) continue
                forms("mesh", mesh.id, previous?.offsets, mesh.offsets)
                channels("mesh", mesh.id, previous?.channels.orEmpty(), mesh.channels)
            }
        }
    }

    companion object {
    internal fun contributions(before: RigIR, after: RigIR): List<ModifierContribution> = buildList {
        fun <T> compareIndexed(kind: String, a: Map<String, T>, b: Map<String, T>) {
            for ((id, value) in b) when {
                id !in a -> add(ModifierContribution(kind, id, ModifierChange.ADDED))
                a[id] != value -> add(ModifierContribution(kind, id, ModifierChange.MODIFIED))
            }
            for (id in a.keys) if (id !in b) add(ModifierContribution(kind, id, ModifierChange.REMOVED))
        }
        fun <T> compare(kind: String, left: List<T>, right: List<T>, key: (T) -> String) {
            if (left === right) return
            val a = left.associateBy(key); val b = right.associateBy(key)
            require(a.size == left.size && b.size == right.size) { "Duplicate $kind IDs in modifier output" }
            compareIndexed(kind, a, b)
        }
        compare("parameter", before.parameters, after.parameters) { it.id }
        compare("part", before.parts, after.parts) { it.id }
        compare("deformer", before.deformers, after.deformers) { it.id }
        if (before.meshes !== after.meshes) {
            val previousMeshes = before.meshes.associateBy { it.id }
            val nextMeshes = after.meshes.associateBy { it.id }
            require(previousMeshes.size == before.meshes.size && nextMeshes.size == after.meshes.size) {
                "Duplicate mesh IDs in modifier output"
            }
            compareIndexed("mesh", previousMeshes, nextMeshes)
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
