package io.github.psd2live.algorithm.worker

import io.github.psd2live.format.compile.RigIrObjects
import io.github.psd2live.format.model.RigIR
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

/** Content-addressed objects shared by the host and JVM workers; no application types. */
object AlgorithmObjects {
    private const val LIMIT = 64L * 1024 * 1024
    fun hash(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    fun encode(input: RigIR, write: (ByteArray) -> JsonObject): Map<String, JsonElement> {
        val objects = RigIrObjects.split(input)
        return linkedMapOf<String, JsonElement>("frame" to write(objects.frame)).apply {
            input.deformers.forEachIndexed { i, item -> put("deformer:${item.id}", write(objects.deformers[i])) }
            input.meshes.forEachIndexed { i, item -> put("mesh:${item.id}", write(objects.meshes[i])) }
        }
    }

    fun write(directory: Path, bytes: ByteArray): JsonObject {
        require(bytes.size <= LIMIT) { "Algorithm object exceeds size limit" }
        val digest = hash(bytes)
        val path = directory.resolve(digest)
        require(!Files.isSymbolicLink(path)) { "Algorithm resource cannot be a link" }
        if (!Files.exists(path)) Files.write(path, bytes)
        return buildJsonObject { put("sha256", digest); put("bytes", bytes.size) }
    }

    fun read(directory: Path, reference: JsonElement): ByteArray {
        val objectRef = reference.jsonObject
        require(objectRef.keys == setOf("sha256", "bytes")) { "Invalid algorithm object reference" }
        val digest = objectRef.getValue("sha256").jsonPrimitive.content
        require(digest.matches(Regex("[a-f0-9]{64}"))) { "Invalid algorithm object hash" }
        val expected = objectRef.getValue("bytes").jsonPrimitive.long
        val path = directory.resolve(digest)
        require(expected in 0..LIMIT && !Files.isSymbolicLink(path) && Files.size(path) == expected) { "Invalid algorithm object size" }
        val bytes = Files.readAllBytes(path)
        require(bytes.size.toLong() == expected && hash(bytes) == digest) { "Algorithm object checksum mismatch" }
        return bytes
    }

    fun readInline(blobs: Map<String, ByteArray>, reference: JsonElement): ByteArray {
        val objectRef = reference.jsonObject
        require(objectRef.keys == setOf("inline", "bytes")) { "Invalid inline algorithm object reference" }
        val key = objectRef.getValue("inline").jsonPrimitive.content
        val expected = objectRef.getValue("bytes").jsonPrimitive.long
        val bytes = blobs[key] ?: throw IllegalArgumentException("Missing inline algorithm object: $key")
        require(expected in 0..LIMIT && bytes.size.toLong() == expected) { "Invalid inline algorithm object size" }
        return bytes
    }

    fun apply(previous: Map<String, JsonElement>, delta: JsonObject): Map<String, JsonElement> {
        val changed = delta.getValue("changed").jsonObject
        val removed = delta.getValue("removed").jsonArray.map { it.jsonPrimitive.content }
        require(changed.size <= 10000 && removed.size <= 10000 && removed.distinct().size == removed.size) { "Too many or duplicate algorithm objects" }
        require(removed.none { it in changed } && removed.all { it in previous }) { "Invalid algorithm object removal" }
        return (previous - removed.toSet() + changed).also { require(it.size <= 10000 && "frame" in it) { "Invalid algorithm object set" } }
    }

    fun decode(objects: Map<String, JsonElement>, meshOrder: JsonArray, deformerOrder: JsonArray, directory: Path): RigIR =
        decode(objects, meshOrder, deformerOrder) { read(directory, it) }

    fun decode(objects: Map<String, JsonElement>, meshOrder: JsonArray, deformerOrder: JsonArray, read: (JsonElement) -> ByteArray): RigIR {
        val meshes = meshOrder.map { it.jsonPrimitive.content }
        val deformers = deformerOrder.map { it.jsonPrimitive.content }
        require(meshes.distinct().size == meshes.size && deformers.distinct().size == deformers.size) { "Duplicate algorithm object identity" }
        require(objects.keys == (listOf("frame") + meshes.map { "mesh:$it" } + deformers.map { "deformer:$it" }).toSet()) { "Algorithm object order differs from contents" }
        require(objects.values.sumOf { it.jsonObject.getValue("bytes").jsonPrimitive.long.also { size -> require(size in 0..LIMIT) } } <= 512L * 1024 * 1024) { "Algorithm objects exceed size limit" }
        val result = RigIrObjects.join(RigIrObjects.Objects(read(objects.getValue("frame")),
            deformers.map { read(objects.getValue("deformer:$it")) },
            meshes.map { read(objects.getValue("mesh:$it")) }))
        require(result.meshes.map { it.id } == meshes && result.deformers.map { it.id } == deformers) { "Algorithm object identity mismatch" }
        return result
    }

    fun delta(before: Map<String, JsonElement>, after: Map<String, JsonElement>, output: RigIR): JsonObject = buildJsonObject {
        put("changed", JsonObject(after.filter { (key, value) -> before[key] != value }))
        put("removed", JsonArray((before.keys - after.keys).map(::JsonPrimitive)))
        put("mesh_order", JsonArray(output.meshes.map { JsonPrimitive(it.id) }))
        put("deformer_order", JsonArray(output.deformers.map { JsonPrimitive(it.id) }))
    }
}
