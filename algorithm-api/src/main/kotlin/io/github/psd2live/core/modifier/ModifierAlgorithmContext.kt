package io.github.psd2live.core.modifier

import io.github.psd2live.format.model.RigIR
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.util.HexFormat

/** A resolved preceding provider, preserved when its output is materialized into author content. */
@Serializable
data class ModifierUpstreamCapability(
    val capability: String, val type: String, val version: Int, val targets: List<ModifierTarget>,
    val instanceId: String? = null, val config: JsonObject = JsonObject(emptyMap()),
    val outputAliases: Map<String, String> = emptyMap(), val plugin: ModifierPluginReference? = null,
) {
    init {
        require(capability.isNotBlank() && type.isNotBlank() && version > 0)
        require(targets.isNotEmpty() && targets.distinct().size == targets.size)
    }
}

/** Captured author inputs, separate from saved editable configuration and generated model state. */
class ModifierAlgorithmContext(values: JsonObject = JsonObject(emptyMap()), resources: Map<String, ModifierAlgorithmResource> = emptyMap(),
                               upstream: List<ModifierUpstreamCapability> = emptyList()) {
    val values = JsonObject(values.toMap())
    val resources = resources.toMap()
    val upstream: List<ModifierUpstreamCapability> = java.util.Collections.unmodifiableList(upstream.toList())
    fun withUpstream(upstream: List<ModifierUpstreamCapability>) = ModifierAlgorithmContext(values, resources, upstream)
    init {
        require(this.upstream.size <= 10000 && this.upstream.distinct().size == this.upstream.size)
        require(this.resources.size <= 10000 && this.resources.keys.all { it.isNotBlank() && it.length <= 256 && it.none(Char::isISOControl) })
        require(this.resources.values.sumOf { it.size.toLong() } <= 512L * 1024 * 1024)
    }
    override fun equals(other: Any?) = other is ModifierAlgorithmContext && values == other.values && resources == other.resources && upstream == other.upstream
    override fun hashCode() = 31 * (31 * values.hashCode() + resources.hashCode()) + upstream.hashCode()
    companion object { val Empty = ModifierAlgorithmContext() }
}

/** Immutable bytes addressed by their complete contents, never by a host filesystem path. */
class ModifierAlgorithmResource(bytes: ByteArray) {
    private val data = bytes.copyOf()
    val size get() = data.size
    init { require(size <= 64 * 1024 * 1024) }
    val digest: String by lazy { HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)) }
    fun read(): ByteArray = data.copyOf()
    /** Read one byte without exposing or copying the backing resource. */
    fun unsignedByte(index: Int): Int = data[index].toInt() and 0xff
    override fun equals(other: Any?) = other is ModifierAlgorithmResource && size == other.size && digest == other.digest
    override fun hashCode() = digest.hashCode()
}

/** Optional source-aware extension; existing model-only plugins retain their original entrypoint. */
interface ContextualModifierAlgorithmPlugin : ModifierAlgorithmPlugin {
    fun evaluate(input: RigIR, instance: RigModifierInstance, context: ModifierAlgorithmContext, checkpoint: () -> Unit): RigIR
    override fun evaluate(input: RigIR, instance: RigModifierInstance, checkpoint: () -> Unit): RigIR =
        evaluate(input, instance, ModifierAlgorithmContext.Empty, checkpoint)
}
