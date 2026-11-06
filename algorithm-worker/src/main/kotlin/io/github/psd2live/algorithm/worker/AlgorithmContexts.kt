package io.github.psd2live.algorithm.worker

import io.github.psd2live.core.modifier.*
import kotlinx.serialization.json.*
import java.nio.file.Path

/** Versioned author-context transport; payload resources share the model object's content store. */
object AlgorithmContexts {
    fun encode(context: ModifierAlgorithmContext, write: (ByteArray) -> JsonObject): JsonObject = buildJsonObject {
        put("version", if (context.upstream.isEmpty()) 1 else 2)
        val metadata = context.values.toString().encodeToByteArray()
        require(metadata.size <= 1024 * 1024) { "Algorithm context metadata is too large" }
        put("values", write(metadata))
        if (context.upstream.isNotEmpty()) {
            val upstream = Json.encodeToJsonElement(context.upstream).toString().encodeToByteArray()
            require(upstream.size <= 1024 * 1024) { "Upstream metadata is too large" }
            put("upstream", write(upstream))
        }
        putJsonObject("resources") { context.resources.forEach { (name, resource) -> put(name, write(resource.read())) } }
    }

    fun decode(value: JsonElement?, directory: Path): ModifierAlgorithmContext {
        if (value == null) return ModifierAlgorithmContext.Empty
        val encoded = value.jsonObject
        val version = encoded.getValue("version").jsonPrimitive.int
        require(version in 1..2 && encoded.keys == (setOf("version", "values", "resources") +
            if (version == 2) setOf("upstream") else emptySet())) { "Unsupported algorithm context" }
        val upstream = if (version == 1) emptyList<ModifierUpstreamCapability>() else {
            val reference = encoded.getValue("upstream")
            require(reference.jsonObject.getValue("bytes").jsonPrimitive.long in 0..1024 * 1024) { "Upstream metadata is too large" }
            Json.decodeFromString<List<ModifierUpstreamCapability>>(AlgorithmObjects.read(directory, reference).decodeToString(throwOnInvalidSequence = true))
        }
        val metadata = encoded.getValue("values")
        require(metadata.jsonObject.getValue("bytes").jsonPrimitive.long in 0..1024 * 1024) { "Algorithm context metadata is too large" }
        val references = encoded.getValue("resources").jsonObject
        require(references.size <= 10000 && references.values.sumOf {
            it.jsonObject.getValue("bytes").jsonPrimitive.long.also { size -> require(size in 0..64L * 1024 * 1024) }
        } <= 512L * 1024 * 1024) { "Algorithm context resources are too large" }
        val values = Json.parseToJsonElement(AlgorithmObjects.read(directory, metadata).decodeToString(throwOnInvalidSequence = true)).jsonObject
        return ModifierAlgorithmContext(values, references.mapValues { ModifierAlgorithmResource(AlgorithmObjects.read(directory, it.value)) }, upstream)
    }
}
