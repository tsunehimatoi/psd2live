package io.github.psd2live.algorithm.worker

import io.github.psd2live.core.modifier.*
import kotlinx.serialization.json.*
import java.nio.file.Path

/** A persistent worker for ordinary JVM algorithm implementations. Stdout is reserved for protocol responses. */
object AlgorithmWorker {
    fun run(algorithms: Map<Pair<String, Int>, ModifierAlgorithmPlugin>) {
        val resources = Path.of(requireNotNull(System.getenv("PSD2LIVE_PLUGIN_RESOURCES")))
        var previous = emptyMap<String, JsonElement>()
        val reader = System.`in`.bufferedReader(Charsets.UTF_8)
        val writer = System.out.bufferedWriter(Charsets.UTF_8)
        while (true) {
            val line = reader.readLine() ?: return
            val request = Json.parseToJsonElement(line).jsonObject
            val response = try {
                require(request.getValue("protocol").jsonPrimitive.int == 1 && request.getValue("method").jsonPrimitive.content == "evaluate")
                require(request.getValue("capabilities").jsonArray.any { it.jsonPrimitive.content == "rig_objects" }) { "Host does not support model object results" }
                val instance = Json.decodeFromJsonElement(RigModifierInstance.serializer(), request.getValue("invocation"))
                val algorithm = requireNotNull(algorithms[instance.type to instance.version]) { "Missing algorithm version" }
                val objects = AlgorithmObjects.apply(if (request.getValue("reset").jsonPrimitive.boolean) emptyMap() else previous, request)
                val input = AlgorithmObjects.decode(objects, request.getValue("mesh_order").jsonArray,
                    request.getValue("deformer_order").jsonArray, resources)
                val output = algorithm.evaluate(input, instance) {
                    if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException()
                }
                val encoded = AlgorithmObjects.encode(output) { AlgorithmObjects.write(resources, it) }
                previous = objects
                buildJsonObject { put("objects", AlgorithmObjects.delta(objects, encoded, output)) }
            } catch (failure: Exception) {
                buildJsonObject { put("error", failure.message ?: failure.javaClass.simpleName) }
            }
            writer.write(JsonObject(response + mapOf("protocol" to JsonPrimitive(1), "id" to request.getValue("id"))).toString())
            writer.newLine(); writer.flush()
        }
    }
}
