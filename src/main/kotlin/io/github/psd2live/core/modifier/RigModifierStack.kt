package io.github.psd2live.core.modifier

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** A reference captured at insertion, independent of the editor's current selection. */
@Serializable
internal data class ModifierTarget(val kind: String, val id: String) {
    init {
        require(kind in setOf("mesh", "deformer", "part", "layer")) { "Unknown modifier target kind: $kind" }
        require(id.isNotBlank() && id.none(Char::isISOControl)) { "Invalid modifier target ID" }
    }
}

@Serializable
internal data class RigModifierInstance(
    val id: String,
    val type: String,
    val version: Int,
    val targets: List<ModifierTarget>,
    val enabled: Boolean = true,
    val config: JsonObject = JsonObject(emptyMap()),
    val presetGroup: String? = null,
    /** Exact upstream input used by an expensive generated result; independent of editable configuration. */
    val inputFingerprint: String? = null,
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val parameterProviders: Map<String, String> = emptyMap(),
    /** Migration preserves existing object references with immutable, persisted local-output aliases. */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val outputAliases: Map<String, String> = emptyMap(),
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val plugin: ModifierPluginReference? = null,
) {
    init {
        require(id.matches(Regex("[A-Za-z0-9_.:-]+"))) { "Invalid modifier instance ID" }
        require(type.isNotBlank() && version > 0) { "A modifier needs a type and positive algorithm version" }
        require(targets.isNotEmpty() && targets.distinct().size == targets.size) { "A modifier needs distinct targets" }
        require(outputAliases.all { (local, id) -> local.isNotBlank() && id.isNotBlank() && id.none(Char::isISOControl) }) {
            "Output aliases need valid local names and object IDs"
        }
        require(outputAliases.values.distinct().size == outputAliases.size) { "Output aliases must be distinct" }
    }

    /** A generated local token is reversible for adapters that call native name-based algorithms. */
    fun localOutputName(output: String): String? {
        outputAliases.entries.firstOrNull { it.value == output }?.let { return it.key }
        val prefix = "modifier:$id:"
        if (!output.startsWith(prefix)) return null
        val hex = output.removePrefix(prefix)
        if (hex.length % 2 != 0) return null
        val bytes = hex.chunked(2).map { it.toIntOrNull(16)?.toByte() ?: return null }.toByteArray()
        return runCatching { bytes.decodeToString(throwOnInvalidSequence = true) }.getOrNull()
    }

    /** Encode every byte, including separators, so distinct local names cannot alias one another. */
    fun outputId(localName: String): String {
        require(localName.isNotBlank()) { "An output needs a local name" }
        outputAliases[localName]?.let { return it }
        val encoded = java.util.HexFormat.of().formatHex(localName.encodeToByteArray())
        return "modifier:$id:$encoded"
    }
}

/** An applied provider remains available to downstream modifiers at the same captured targets. */
@Serializable
internal data class ModifierCapability(val type: String, val version: Int, val targets: List<ModifierTarget>, val id: String? = null,
    val config: JsonObject = JsonObject(emptyMap()),
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val outputAliases: Map<String, String> = emptyMap())

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
) {
    init {
        require(nextId > 0) { "Invalid modifier ID sequence" }
        require(instances.map { it.id }.distinct().size == instances.size) { "Duplicate modifier instance ID" }
        require(instances.none { it.id in retiredIds }) { "A retired modifier ID cannot be reused" }
        require(overrides.keys.all { id -> id in retiredIds || instances.any { it.id == id } }) { "Override has no modifier owner" }
    }

    fun add(type: String, version: Int, targets: List<ModifierTarget>, config: JsonObject = JsonObject(emptyMap()),
            presetGroup: String? = null, id: String? = null, outputAliases: Map<String, String> = emptyMap(), plugin: ModifierPluginReference? = null): RigModifierStack {
        var sequence = nextId
        val reserved = instances.mapTo(HashSet()) { it.id } + retiredIds
        while ("m$sequence" in reserved) sequence = Math.addExact(sequence, 1)
        val chosen = id ?: "m$sequence"
        require(chosen !in reserved) { "Modifier ID is already used or retired: $chosen" }
        val entry = RigModifierInstance(chosen, type, version, targets.toList(), config = config, presetGroup = presetGroup,
            outputAliases = outputAliases.toMap(), plugin = plugin ?: BuiltinModifierPlugins.reference(type, version))
        return copy(instances = instances + entry, nextId = Math.addExact(sequence, 1))
    }

    fun update(id: String, change: (RigModifierInstance) -> RigModifierInstance): RigModifierStack {
        val index = index(id)
        val updated = change(instances[index])
        require(updated.id == id && updated.type == instances[index].type && updated.version == instances[index].version &&
            updated.outputAliases == instances[index].outputAliases && updated.plugin == instances[index].plugin) {
            "Updating a modifier cannot change its identity or algorithm version"
        }
        return copy(instances = instances.toMutableList().apply { set(index, updated) })
    }

    fun remove(id: String): RigModifierStack {
        index(id)
        return copy(instances = instances.filterNot { it.id == id }, retiredIds = retiredIds + id)
    }

    /** The index in the resulting list. Validation against providers happens before a candidate is published. */
    fun move(id: String, destination: Int): RigModifierStack {
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

    fun index(id: String): Int = instances.indexOfFirst { it.id == id }.also { require(it >= 0) { "Unknown modifier: $id" } }

    fun toJson(): JsonObject = codec.encodeToJsonElement(serializer(), this) as JsonObject

    companion object {
        // Reject unknown state rather than silently discard ownership or a newer algorithm's configuration.
        private val codec = Json { encodeDefaults = true }
        fun fromJson(value: JsonObject): RigModifierStack = codec.decodeFromJsonElement(serializer(), value)
    }
}
