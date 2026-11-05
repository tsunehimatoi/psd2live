package io.github.psd2live.core.modifier

import io.github.psd2live.core.RigAuthoringJournal
import io.github.psd2live.core.RigBuildProfile
import io.github.psd2live.format.compile.RigIrObjects
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.ExecutionException

/** One serialized request stream per package. Worker failures discard the candidate, never the source document. */
internal object ModifierPluginWorkers {
    private val workers = ConcurrentHashMap<ModifierPluginReference, Worker>()
    private val executor = Executors.newVirtualThreadPerTaskExecutor()
    init { Runtime.getRuntime().addShutdownHook(Thread { workers.values.forEach { it.close() } }) }
    fun stop(reference: ModifierPluginReference) { workers.remove(reference)?.close() }

    fun evaluate(entry: InstalledModifierPlugin, algorithm: ModifierPluginAlgorithm, rig: RigIR,
                 instance: RigModifierInstance, checkpoint: () -> Unit, store: ModifierPluginStore = ModifierPluginStore.shared): RigIR {
        store.requireAvailable(entry.reference)
        if (!workers.containsKey(entry.reference)) store.verify(entry.reference)
        val worker = workers.computeIfAbsent(entry.reference) { Worker(entry) }
        try {
            val result = worker.evaluate(algorithm, rig, instance, checkpoint)
            store.requireAvailable(entry.reference)
            return result
        } catch (failure: Throwable) {
            if (workers.remove(entry.reference, worker)) worker.close()
            throw failure
        }
    }

    private class Worker(val entry: InstalledModifierPlugin) : AutoCloseable {
        private val resources = Files.createTempDirectory("psd2live-plugin-")
        private val command = (entry.manifest.commands[ModifierPluginStore.platform] ?: entry.manifest.commands.getValue("jvm")).mapIndexed { index, value ->
            when {
                value == "\$JAVA" -> Path.of(System.getProperty("java.home"), "bin", if (ModifierPluginStore.platform.startsWith("windows")) "java.exe" else "java").toString()
                index == 0 || value in entry.manifest.files -> ModifierPluginStore.inside(entry.directory, value).toString()
                else -> value
            }
        }
        private val process = try { ProcessBuilder(command).directory(entry.directory.toFile()).apply {
            environment()["PSD2LIVE_PLUGIN_RESOURCES"] = resources.toString()
        }.start() } catch (failure: Exception) { ModifierPluginStore.deleteTree(resources); throw failure }
        private val errors = StringBuilder()
        private val stderr = executor.submit {
            process.errorStream.bufferedReader().use { input ->
                val buffer = CharArray(1024)
                while (true) {
                    val length = input.read(buffer); if (length < 0) break
                    synchronized(errors) { if (errors.length < 8192) errors.append(buffer, 0, minOf(length, 8192 - errors.length)) }
                }
            }
        }
        private val writer = process.outputStream.bufferedWriter(Charsets.UTF_8)
        private val reader = process.inputStream.bufferedReader(Charsets.UTF_8)
        private var sequence = 0L
        private var previous = emptyMap<String, JsonElement>()
        @Volatile private var closed = false

        @Synchronized fun evaluate(algorithm: ModifierPluginAlgorithm, rig: RigIR, instance: RigModifierInstance, checkpoint: () -> Unit): RigIR {
            check(!closed) { "Algorithm plugin was stopped" }
            checkpoint()
            val input = ModifierInputCache.project(rig, algorithm.reads + algorithm.writes)
            val objects = RigBuildProfile.stage("plugin input serialization") { RigIrObjects.split(input) }
            val current = linkedMapOf<String, JsonElement>("frame" to blob(objects.frame))
            objects.deformers.forEachIndexed { index, bytes -> current["deformer:${input.deformers[index].id}"] = blob(bytes) }
            objects.meshes.forEachIndexed { index, bytes -> current["mesh:${input.meshes[index].id}"] = blob(bytes) }
            val requestId = ++sequence
            val request = buildJsonObject {
                put("protocol", 1); put("id", requestId); put("method", "evaluate")
                put("type", instance.type); put("version", instance.version); put("instance", instance.id)
                put("config", instance.config); put("resource_directory", resources.toString())
                put("reset", requestId == 1L)
                put("changed", JsonObject(current.filter { (key, value) -> previous[key] != value }))
                put("removed", JsonArray((previous.keys - current.keys).map(::JsonPrimitive)))
                put("mesh_order", JsonArray(input.meshes.map { JsonPrimitive(it.id) }))
                put("deformer_order", JsonArray(input.deformers.map { JsonPrimitive(it.id) }))
                putJsonArray("targets") { instance.targets.forEach { addJsonObject { put("kind", it.kind); put("id", it.id) } } }
                putJsonArray("parameters") { input.parameters.forEach { addJsonObject {
                    put("id", it.id); put("min", it.min); put("max", it.max); put("default", it.default)
                } } }
                // Portable geometry views avoid requiring a language-specific full model decoder.
                putJsonArray("meshes") { input.meshes.filter { mesh -> instance.targets.any { it.kind == "mesh" && it.id == mesh.id } }.forEach { mesh -> addJsonObject {
                    put("id", mesh.id); put("opacity", mesh.opacity)
                    mesh.geometry?.let { geometry ->
                        put("positions", floatBlob(geometry.positions.toArray()))
                        put("uvs", floatBlob(geometry.uvs.toArray()))
                    }
                } } }
            }
            val task = executor.submit<JsonObject> {
                val text = request.toString()
                RigBuildProfile.count("plugin sent bytes", text.toByteArray().size.toLong())
                writer.write(text); writer.newLine(); writer.flush()
                val response = StringBuilder()
                while (true) {
                    val c = reader.read()
                    check(c >= 0) { "Algorithm plugin exited (${entry.manifest.name}): ${synchronized(errors) { errors.toString() }}" }
                    if (c == 10) break
                    require(response.length < 16 * 1024 * 1024) { "Plugin response exceeds size limit" }
                    response.append(c.toChar())
                }
                Json.parseToJsonElement(response.toString()).jsonObject
            }
            val response = try { task.get(120, TimeUnit.SECONDS) }
            catch (failure: ExecutionException) { throw failure.cause ?: failure }
            catch (failure: Exception) { task.cancel(true); close(); throw failure }
            checkpoint()
            require(response.keys.all { it in setOf("protocol", "id", "commands", "error") } && response["protocol"]?.jsonPrimitive?.int == 1 && response["id"]?.jsonPrimitive?.long == requestId) { "Invalid plugin response envelope" }
            response["error"]?.let { error("${entry.manifest.name}: ${it.jsonPrimitive.content}") }
            val commands = response.getValue("commands").jsonArray
            require(commands.size <= 10000) { "Too many plugin commands" }
            var output = rig
            for (raw in commands) {
                checkpoint()
                val command = expand(raw).jsonObject
                when (command["op"]?.jsonPrimitive?.content) {
                    "physics", "motion" -> {
                        val definition = if (command.getValue("op").jsonPrimitive.content == "physics") RigModifierAlgorithms.physics else RigModifierAlgorithms.motion
                        output = definition.plugin.evaluate(output, instance.copy(config = command.getValue("config").jsonObject), checkpoint)
                    }
                    "mesh_opacity" -> {
                        val id = command.getValue("id").jsonPrimitive.content
                        require(instance.targets.any { it.kind == "mesh" && it.id == id } && "mesh" in algorithm.writes) { "Plugin write is outside declared targets" }
                        val opacity = command.getValue("value").jsonPrimitive.float
                        require(opacity.isFinite() && opacity in 0f..1f) { "Invalid plugin opacity" }
                        output = output.copy(meshes = output.meshes.map { if (it.id == id) it.copy(opacity = opacity) else it })
                    }
                    else -> {
                        require(command["op"]?.jsonPrimitive?.content in setOf("structure", "set", "parameter_keys", "delete", "canvas_geometry", "canvas_topology", "canvas_create_warp", "canvas_create_rotation", "canvas_create_glue", "canvas_glue_edit", "path_put", "path_delete")) { "Unsupported plugin command" }
                        output = output.withPuppet(RigAuthoringJournal.apply(PuppetIr.toPuppet(output), command))
                    }
                }
            }
            val changes = RigModifierEvaluator.contributions(rig, output)
            require(changes.all { it.kind in algorithm.writes || "*" in algorithm.writes }) { "Plugin wrote undeclared object kinds" }
            require(changes.filter { it.change == ModifierChange.ADDED && it.kind !in setOf("relations", "hierarchy") }.all {
                instance.localOutputName(it.id) != null || it.kind == "parameter" && algorithm.parameters.any { p -> p.id == it.id }
            }) { "Plugin outputs must use instance-owned deterministic IDs or declared shared parameters" }
            previous = current
            return output
        }

        private fun blob(bytes: ByteArray): JsonObject {
            val hash = ModifierPluginStore.hash(bytes)
            val path = resources.resolve(hash)
            if (!Files.exists(path)) {
                Files.write(path, bytes)
                RigBuildProfile.count("plugin resource bytes", bytes.size.toLong())
            }
            return buildJsonObject { put("sha256", hash); put("bytes", bytes.size) }
        }
        private fun floatBlob(values: FloatArray): JsonObject {
            val buffer = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            values.forEach(buffer::putFloat)
            return JsonObject(blob(buffer.array()) + mapOf("format" to JsonPrimitive("f32le"), "count" to JsonPrimitive(values.size)))
        }
        private fun expand(value: JsonElement): JsonElement = when (value) {
            is JsonObject -> if (value["format"]?.jsonPrimitive?.content == "f32le" && "sha256" in value) {
                val hash = value.getValue("sha256").jsonPrimitive.content
                require(hash.matches(Regex("[a-f0-9]{64}"))) { "Invalid plugin resource reference" }
                val path = resources.resolve(hash)
                require(!Files.isSymbolicLink(path) && Files.size(path) <= 64 * 1024 * 1024) { "Invalid plugin buffer" }
                val bytes = Files.readAllBytes(path)
                require(ModifierPluginStore.hash(bytes) == hash && bytes.size % 4 == 0) { "Plugin buffer checksum mismatch" }
                require(value["bytes"]?.jsonPrimitive?.int == bytes.size && value["count"]?.jsonPrimitive?.int == bytes.size / 4) { "Plugin buffer size mismatch" }
                val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                JsonArray(List(bytes.size / 4) { JsonPrimitive(buffer.float.also { require(it.isFinite()) }) })
            } else JsonObject(value.mapValues { expand(it.value) })
            is JsonArray -> JsonArray(value.map(::expand))
            else -> value
        }
        override fun close() {
            closed = true
            val interrupted = Thread.interrupted()
            try {
                val descendants = process.descendants().toList()
                descendants.forEach { it.destroyForcibly() }
                process.destroyForcibly()
                // On Windows executable/JAR handles remain locked until the process actually exits.
                process.waitFor(5, TimeUnit.SECONDS)
                descendants.forEach { runCatching { it.onExit().get(5, TimeUnit.SECONDS) } }
                stderr.cancel(true)
                runCatching { ModifierPluginStore.deleteTree(resources) }
            } finally { if (interrupted) Thread.currentThread().interrupt() }
        }
    }
}
