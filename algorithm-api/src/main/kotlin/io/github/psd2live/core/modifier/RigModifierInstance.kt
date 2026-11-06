package io.github.psd2live.core.modifier

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** A reference captured at insertion, independent of the editor's current selection. */
@Serializable
data class ModifierTarget(val kind: String, val id: String) {
    init {
        require(kind in setOf("mesh", "deformer", "part", "layer")) { "Unknown modifier target kind: $kind" }
        require(id.isNotBlank() && id.none(Char::isISOControl)) { "Invalid modifier target ID" }
    }
}

@Serializable
data class RigModifierInstance(
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

