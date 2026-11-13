package io.github.psd2live.algorithm.worker

import io.github.psd2live.core.modifier.*
import kotlinx.serialization.json.*
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.nio.file.Path

/** A persistent worker for ordinary JVM algorithm implementations. Stdout is reserved for protocol responses. */
object AlgorithmWorker {
    fun run(algorithms: Map<Pair<String, Int>, ModifierAlgorithmPlugin>) {
        val protocol = if (System.getenv("PSD2LIVE_PLUGIN_PROTOCOL") == "2") 2 else 1
        var previous = emptyMap<String, JsonElement>()
        if (protocol == 2) {
            val input = BufferedInputStream(System.`in`)
            val output = BufferedOutputStream(System.out)
            val sessionBlobs = HashMap<String, ByteArray>()
            var nextBlobId = 0L
            while (true) {
                val frame = AlgorithmFrames.read(input) ?: return
                val request = frame.header
                val responseBlobs = LinkedHashMap<String, ByteArray>()
                val response = try {
                    val isReset = request.getValue("reset").jsonPrimitive.boolean
                    if (isReset) sessionBlobs.clear()
                    val (objects, result) = evaluateRequest(
                        algorithms = algorithms,
                        request = request,
                        expectedProtocol = 2,
                        previous = if (isReset) emptyMap() else previous,
                        read = { ref ->
                            val key = ref.jsonObject.getValue("inline").jsonPrimitive.content
                            val combined = frame.blobs[key] ?: sessionBlobs[key]
                            if (combined != null) {
                                val expected = ref.jsonObject.getValue("bytes").jsonPrimitive.long
                                require(ref.jsonObject.keys == setOf("inline", "bytes") && combined.size.toLong() == expected) { "Invalid inline algorithm object" }
                                combined
                            } else AlgorithmObjects.readInline(frame.blobs, ref)
                        },
                        write = { bytes ->
                            val key = "r${nextBlobId++}"
                            responseBlobs[key] = bytes
                            buildJsonObject { put("inline", key); put("bytes", bytes.size) }
                        },
                    )
                    previous = objects
                    val retained = objects.values.mapNotNullTo(HashSet()) { it.jsonObject["inline"]?.jsonPrimitive?.content }
                    sessionBlobs.keys.retainAll(retained)
                    for (key in retained) {
                        frame.blobs[key]?.let { sessionBlobs[key] = it }
                    }
                    result
                } catch (failure: Exception) {
                    responseBlobs.clear()
                    buildJsonObject { put("error", failure.message ?: failure.javaClass.simpleName) }
                }
                val envelope = JsonObject(response + mapOf("protocol" to JsonPrimitive(2), "id" to request.getValue("id")))
                AlgorithmFrames.write(output, envelope, responseBlobs)
            }
        } else {
            val resources = Path.of(requireNotNull(System.getenv("PSD2LIVE_PLUGIN_RESOURCES")))
            val reader = System.`in`.bufferedReader(Charsets.UTF_8)
            val writer = System.out.bufferedWriter(Charsets.UTF_8)
            while (true) {
                val line = reader.readLine() ?: return
                val request = Json.parseToJsonElement(line).jsonObject
                val response = try {
                    val isReset = request.getValue("reset").jsonPrimitive.boolean
                    val (objects, result) = evaluateRequest(
                        algorithms = algorithms,
                        request = request,
                        expectedProtocol = 1,
                        previous = if (isReset) emptyMap() else previous,
                        read = { AlgorithmObjects.read(resources, it) },
                        write = { AlgorithmObjects.write(resources, it) },
                    )
                    previous = objects
                    result
                } catch (failure: Exception) {
                    buildJsonObject { put("error", failure.message ?: failure.javaClass.simpleName) }
                }
                writer.write(JsonObject(response + mapOf("protocol" to JsonPrimitive(1), "id" to request.getValue("id"))).toString())
                writer.newLine(); writer.flush()
            }
        }
    }

    private fun evaluateRequest(
        algorithms: Map<Pair<String, Int>, ModifierAlgorithmPlugin>,
        request: JsonObject,
        expectedProtocol: Int,
        previous: Map<String, JsonElement>,
        read: (JsonElement) -> ByteArray,
        write: (ByteArray) -> JsonObject,
    ): Pair<Map<String, JsonElement>, JsonObject> {
        require(request.getValue("protocol").jsonPrimitive.int == expectedProtocol && request.getValue("method").jsonPrimitive.content == "evaluate")
        val capabilities = request.getValue("capabilities").jsonArray.map { it.jsonPrimitive.content }.toSet()
        require("rig_objects" in capabilities) { "Host does not support model object results" }
        val instance = Json.decodeFromJsonElement(RigModifierInstance.serializer(), request.getValue("invocation"))
        val algorithm = requireNotNull(algorithms[instance.type to instance.version]) { "Missing algorithm version" }
        val objects = AlgorithmObjects.apply(previous, request)
        val input = AlgorithmObjects.decode(objects, request.getValue("mesh_order").jsonArray,
            request.getValue("deformer_order").jsonArray, read)
        val checkpoint = {
            if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException()
        }
        val context = AlgorithmContexts.decode(request["context"], read)
        val response = if (algorithm is ChangingModifierAlgorithmPlugin && AlgorithmChanges.CAPABILITY in capabilities) {
            val view = if (algorithm is ReadingModifierAlgorithmPlugin) ModifierEvaluationInput(input, context) else null
            val changes = if (view != null) (algorithm as ReadingModifierAlgorithmPlugin).changes(view, instance, checkpoint)
            else algorithm.changes(input, instance, context, checkpoint)
            buildJsonObject {
                put("changes", AlgorithmChanges.encode(changes, allowRasters = AlgorithmChanges.RASTER_CAPABILITY in capabilities, write = write))
                if (view != null && "input_reads_v1" in capabilities &&
                    (view.reads().keys.none { it.section == "meshIds" } || "input_mesh_ids_v1" in capabilities)) {
                    put("reads", Json.encodeToJsonElement(view.reads().keys.sortedWith(compareBy({ it.section }, { it.id ?: "" }))))
                }
            }
        } else {
            val output = if (algorithm is ContextualModifierAlgorithmPlugin)
                algorithm.evaluate(input, instance, context, checkpoint)
            else algorithm.evaluate(input, instance, checkpoint)
            val encoded = AlgorithmObjects.encode(output, write)
            buildJsonObject { put("objects", AlgorithmObjects.delta(objects, encoded, output)) }
        }
        return objects to response
    }
}
