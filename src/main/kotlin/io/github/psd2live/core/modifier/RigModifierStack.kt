package io.github.psd2live.core.modifier

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

/** An applied provider remains available to downstream modifiers at the same captured targets. */
@Serializable
internal data class ModifierCapability(val type: String, val version: Int, val targets: List<ModifierTarget>, val id: String? = null,
    val config: JsonObject = JsonObject(emptyMap()),
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val outputAliases: Map<String, String> = emptyMap(),
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val plugin: ModifierPluginReference? = null,
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val provides: Set<String>? = null)

internal fun RigModifierInstance.capability(config: JsonObject = this.config, provides: Set<String>? = null) =
    ModifierCapability(type, version, targets, id, config, outputAliases, plugin, provides)

/**
 * Immutable stack state. Retired IDs and overrides survive removal: adding a new instance must never adopt an old
 * instance's dormant edits. Prefix application is the only operation that consumes overrides into ordinary content.
 */
@Serializable
internal data class RigModifierStack(
    val instances: List<RigModifierInstance> = emptyList(),
    val overrides: Map<String, List<JsonObject>> = emptyMap(),
    val retiredIds: Set<String> = emptySet(),
    val appliedCapabilities: List<ModifierCapability> = emptyList(),
    val nextId: Long = 1,
    /** Presentation folders and sibling order never change evaluation order. */
    val organization: List<ModifierPanelNode>? = null,
    /** Preparation failures remain visible after saving and reopening. */
    val initializationFailures: Map<String, String> = emptyMap(),
    /** Explicit retries invalidate a suspended runtime result without changing plugin configuration. */
    val retryRevisions: Map<String, Long> = emptyMap(),
    /** Materialized ordinary stages stay at their original evaluation positions. */
    val appliedNodes: Map<String, AppliedModifierNode> = emptyMap(),
    /** Authored weights changed; retain saved bakes and enabled state until an explicit Bake. */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val pendingWeightEdits: Map<String, List<BakedWeightInput>> = emptyMap(),
) {
    init {
        require(nextId > 0) { "Invalid modifier ID sequence" }
        require(instances.map { it.id }.distinct().size == instances.size) { "Duplicate modifier instance ID" }
        require(instances.none { it.id in retiredIds }) { "A retired modifier ID cannot be reused" }
        require(overrides.keys.all { id -> id in retiredIds || instances.any { it.id == id } }) { "Override has no modifier owner" }
    }

    /** A solver building its inputs must not replay overrides owned by a later editing stage. */
    fun prefix(end: Int): RigModifierStack {
        val kept = instances.take(end)
        val owners = kept.mapTo(hashSetOf()) { it.id }
        return copy(instances = kept, overrides = overrides.filterKeys { it in owners || it in retiredIds },
            appliedNodes = appliedNodes.filterKeys { it in owners })
    }

    fun add(type: String, version: Int, targets: List<ModifierTarget>, config: JsonObject = JsonObject(emptyMap()),
            presetGroup: String? = null, id: String? = null, outputAliases: Map<String, String> = emptyMap(), plugin: ModifierPluginReference? = null): RigModifierStack {
        var sequence = nextId
        val reserved = instances.mapTo(HashSet()) { it.id } + retiredIds + appliedNodes.keys
        while ("m$sequence" in reserved) sequence = Math.addExact(sequence, 1)
        val chosen = id ?: "m$sequence"
        require(chosen !in reserved) { "Modifier ID is already used or retired: $chosen" }
        val entry = RigModifierInstance(chosen, type, version, targets.toList(), config = config, presetGroup = presetGroup,
            outputAliases = outputAliases.toMap(), plugin = plugin ?: BuiltinModifierPlugins.reference(type, version))
        return copy(instances = instances + entry, nextId = Math.addExact(sequence, 1))
    }

    fun update(id: String, change: (RigModifierInstance) -> RigModifierInstance): RigModifierStack {
        require(id !in appliedNodes) { "Applied content is edited on the canvas, not by changing its generator" }
        val index = index(id)
        val updated = change(instances[index])
        require(updated.id == id && updated.type == instances[index].type && updated.version == instances[index].version &&
            updated.outputAliases == instances[index].outputAliases && updated.plugin == instances[index].plugin) {
            "Updating a modifier cannot change its identity or algorithm version"
        }
        return copy(instances = instances.toMutableList().apply { set(index, updated) },
            pendingWeightEdits = if (updated.config != instances[index].config) pendingWeightEdits - id else pendingWeightEdits,
            initializationFailures = if (updated.enabled && updated != instances[index]) initializationFailures - id else initializationFailures)
    }

    /** Rebind an existing modifier instance to a compatible package revision while preserving identity, config, targets, order, and overrides. */
    fun rebind(id: String, plugin: ModifierPluginReference, version: Int = instances[index(id)].version): RigModifierStack {
        require(id !in appliedNodes) { "Applied content has no running algorithm to replace" }
        val idx = index(id)
        val updated = instances[idx].copy(plugin = plugin, version = version)
        return copy(instances = instances.toMutableList().apply { set(idx, updated) }, pendingWeightEdits = pendingWeightEdits - id)
    }

    fun remove(id: String): RigModifierStack {
        require(id !in appliedNodes) { "Applied content is ordinary authored content; delete its objects or undo application" }
        index(id)
        return copy(instances = instances.filterNot { it.id == id }, retiredIds = retiredIds + id, pendingWeightEdits = pendingWeightEdits - id)
    }

    /** The index in the resulting list. Validation against providers happens before a candidate is published. */
    fun move(id: String, destination: Int): RigModifierStack {
        require(id !in appliedNodes) { "Applied content retains its original evaluation position" }
        require(destination in instances.indices) { "Invalid modifier destination" }
        val entries = instances.toMutableList()
        val entry = entries.removeAt(index(id))
        entries.add(destination, entry)
        return copy(instances = entries)
    }

    fun withOverrides(id: String, commands: List<JsonObject>): RigModifierStack {
        index(id)
        return copy(overrides = if (commands.isEmpty()) overrides - id else overrides + (id to commands.toList()))
    }

    /** The rear slice inherits the source binding. The front follows its deformation through Glue. */
    fun retargetDepthSplit(sourceMesh: String, backMesh: String, sourceLayer: String, backLayer: String,
                           glueKeys: Map<String, String>): RigModifierStack {
        val replacements = mapOf(ModifierTarget("mesh", sourceMesh) to ModifierTarget("mesh", backMesh),
            ModifierTarget("layer", sourceLayer) to ModifierTarget("layer", backLayer))
        fun retarget(targets: List<ModifierTarget>) = targets.map { replacements[it] ?: it }.distinct()
        return copy(instances = instances.map { instance ->
            // Applied patches keep their captured inputs. The edit gate rejects changes they cannot replay.
            if (instance.id in appliedNodes) instance
            else {
                val children = instance.config["subConfigs"] as? JsonObject
                val config = if (instance.type == "simulation") depthSplitSimulation(instance.config, sourceMesh, backMesh, glueKeys)
                else if (instance.type !in ModifierPartConfig.features || children == null) instance.config else {
                    val updated = children.mapValues { (_, value) ->
                        val child = value as? JsonObject ?: return@mapValues value
                        val targets = child["targets"] as? JsonArray ?: return@mapValues value
                        val before = codec.decodeFromJsonElement<List<ModifierTarget>>(targets)
                        val after = retarget(before)
                        if (after == before) child else JsonObject(child + ("targets" to codec.encodeToJsonElement(after)))
                    }
                    JsonObject(instance.config + ("subConfigs" to JsonObject(updated)))
                }
                instance.copy(targets = retarget(instance.targets), config = config)
            }
        }, appliedCapabilities = appliedCapabilities.map { it.copy(targets = retarget(it.targets)) })
    }

    private fun depthSplitSimulation(config: JsonObject, source: String, back: String, glueKeys: Map<String, String>): JsonObject {
        // Keep the saved bake until the normal simulation preparation can refresh it against the new prefix.
        val updated = config.toMutableMap()
        val sourceId = JsonPrimitive(source)
        val backId = JsonPrimitive(back)
        (config["targets"] as? JsonArray)?.let { targets ->
            updated["targets"] = JsonArray(targets.map { if (it == sourceId) backId else it })
        }
        fun retargetObjects(field: String, key: String, from: JsonPrimitive, to: JsonPrimitive) {
            val objects = config[field] as? JsonArray ?: return
            updated[field] = JsonArray(objects.map { entry ->
                if (entry is JsonObject && entry[key] == from) JsonObject(entry + (key to to)) else entry
            })
        }
        retargetObjects("obstacles", "mesh", sourceId, backId)
        retargetObjects("automatic_weights", "target", JsonPrimitive("mesh:$source"), JsonPrimitive("mesh:$back"))
        (config["glue_roles"] as? JsonObject)?.let { roles ->
            updated["glue_roles"] = JsonObject(roles.mapKeys { glueKeys[it.key] ?: it.key })
        }
        return JsonObject(updated)
    }

    fun index(id: String): Int = instances.indexOfFirst { it.id == id }.also { require(it >= 0) { "Unknown modifier: $id" } }

    fun toJson(): JsonObject = codec.encodeToJsonElement(serializer(), this) as JsonObject

    fun pluginReferences(): List<ModifierPluginReference> = instances.mapNotNull { it.plugin } + appliedCapabilities.mapNotNull { it.plugin }

    /** Frozen output carries its contract; only old capabilities without that contract need a package lookup. */
    fun runtimePluginReferences(): List<ModifierPluginReference> =
        instances.filterNot { it.id in appliedNodes }.mapNotNull { it.plugin } +
            appliedCapabilities.filter { it.provides == null }.mapNotNull { it.plugin }

    companion object {
        // Reject unknown state rather than silently discard ownership or a newer algorithm's configuration.
        private val codec = Json { encodeDefaults = true }
        fun fromJson(value: JsonObject): RigModifierStack = codec.decodeFromJsonElement(serializer(), value)

        /** Read only lock metadata when archiving a history whose snapshots are still lazy. */
        fun pluginReferences(value: JsonObject): List<ModifierPluginReference> = sequenceOf("instances", "appliedCapabilities")
            .flatMap { (value[it] as? JsonArray).orEmpty() }
            .mapNotNull { (it as? JsonObject)?.get("plugin") as? JsonObject }
            .map { codec.decodeFromJsonElement<ModifierPluginReference>(it) }
            .toList()
    }
}
