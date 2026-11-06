package io.github.psd2live.core.modifier

import io.github.psd2live.core.RigBuildProfile
import io.github.psd2live.algorithm.worker.AlgorithmObjects
import io.github.psd2live.algorithm.worker.AlgorithmResultValidation
import io.github.psd2live.format.compile.RigIrObjects
import io.github.psd2live.format.model.RigIR
import kotlinx.serialization.json.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeoutException

/** One serialized request stream per package. Worker failures discard the candidate, never the source document. */
internal object ModifierPluginWorkers {
    private val workers = ConcurrentHashMap<ModifierPluginReference, Worker>()
    private val executor = Executors.newVirtualThreadPerTaskExecutor()
    private class QueuedFailure(val original: Throwable) : RuntimeException(original)
    init { Runtime.getRuntime().addShutdownHook(Thread { workers.values.forEach { it.close() } }) }
    fun stop(reference: ModifierPluginReference) { workers.remove(reference)?.close() }

    fun evaluate(entry: InstalledModifierPlugin, algorithm: ModifierPluginAlgorithm, rig: RigIR,
                 instance: RigModifierInstance, checkpoint: () -> Unit, store: ModifierPluginStore = ModifierPluginStore.shared,
                 timeoutMillis: Long = 120_000, context: ModifierAlgorithmContext = ModifierAlgorithmContext.Empty,
                 declaredParameters: List<io.github.psd2live.format.model.Parameter> = emptyList()): RigIR {
        require(timeoutMillis > 0)
        store.requireAvailable(entry.reference)
        if (!workers.containsKey(entry.reference)) store.verify(entry.reference)
        val worker = workers.computeIfAbsent(entry.reference) { Worker(entry, store) }
        try {
            val result = worker.evaluate(algorithm, rig, instance, checkpoint, timeoutMillis, context, declaredParameters)
            store.requireAvailable(entry.reference)
            return result
        } catch (failure: QueuedFailure) {
            throw failure.original
        } catch (failure: Throwable) {
            if (workers.remove(entry.reference, worker)) runCatching { worker.close() }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    private class Worker(val entry: InstalledModifierPlugin, val store: ModifierPluginStore) : AutoCloseable {
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
        private val requests = java.util.concurrent.locks.ReentrantLock(true)

        fun evaluate(algorithm: ModifierPluginAlgorithm, rig: RigIR, instance: RigModifierInstance, checkpoint: () -> Unit, timeoutMillis: Long, context: ModifierAlgorithmContext, declaredParameters: List<io.github.psd2live.format.model.Parameter>): RigIR {
            // A cancelled queued request must neither wait on a JVM monitor nor kill the active request.
            try {
                val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
                while (true) {
                    checkpoint()
                    val remaining = deadline - System.nanoTime()
                    if (remaining <= 0) throw TimeoutException("Algorithm plugin queue timed out: ${entry.manifest.name}")
                    if (requests.tryLock(minOf(remaining, TimeUnit.MILLISECONDS.toNanos(50)), TimeUnit.NANOSECONDS)) break
                }
            } catch (failure: Throwable) { throw QueuedFailure(failure) }
            try { return evaluateLocked(algorithm, rig, instance, checkpoint, timeoutMillis, context, declaredParameters) }
            finally { requests.unlock() }
        }

        private fun evaluateLocked(algorithm: ModifierPluginAlgorithm, rig: RigIR, instance: RigModifierInstance, checkpoint: () -> Unit, timeoutMillis: Long, context: ModifierAlgorithmContext, declaredParameters: List<io.github.psd2live.format.model.Parameter>): RigIR {
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
                put("type", algorithm.type); put("version", instance.version); put("instance", instance.id)
                put("capabilities", JsonArray((ModifierPluginOutput.operations + setOf("rig_objects", "author_context_v1")).sorted().map(::JsonPrimitive)))
                if (context != ModifierAlgorithmContext.Empty) put("context", io.github.psd2live.algorithm.worker.AlgorithmContexts.encode(context, ::blob))
                put("invocation", Json.encodeToJsonElement(RigModifierInstance.serializer(), instance.copy(type = algorithm.type)))
                put("dependencies", JsonArray(store.resolution(entry.reference).dependencies.map { reference ->
                    Json.encodeToJsonElement(ModifierPluginReference.serializer(), reference)
                }))
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
            val response = try { awaitResponse(task, checkpoint, timeoutMillis) }
            catch (failure: ExecutionException) { throw failure.cause ?: failure }
            catch (failure: Exception) { task.cancel(true); throw failure }
            checkpoint()
            require(response.keys.all { it in setOf("protocol", "id", "commands", "objects", "error") } && response["protocol"]?.jsonPrimitive?.int == 1 && response["id"]?.jsonPrimitive?.long == requestId) { "Invalid plugin response envelope" }
            response["error"]?.let { error("${entry.manifest.name}: ${it.jsonPrimitive.content}") }
            require(("commands" in response) != ("objects" in response)) { "Plugin must return commands or model objects" }
            val output = response["objects"]?.let { value ->
                val delta = value.jsonObject
                require(delta.keys == setOf("changed", "removed", "mesh_order", "deformer_order")) { "Invalid model object result" }
                val objects = AlgorithmObjects.apply(current, delta)
                val projected = AlgorithmObjects.decode(objects, delta.getValue("mesh_order").jsonArray,
                    delta.getValue("deformer_order").jsonArray, resources)
                checkpoint()
                val merged = ModifierInputCache.Patch(input, projected).apply(rig)
                AlgorithmResultValidation.validate(merged)
                ModifierPluginOutput.validate(rig, merged, instance, algorithm, declaredParameters)
            } ?: run {
                val commands = response.getValue("commands").jsonArray
                require(commands.size <= 10000) { "Too many plugin commands" }
                ModifierPluginOutput.apply(rig, instance, algorithm, JsonArray(commands.map(::expand)), checkpoint, declaredParameters)
            }
            previous = current
            return output
        }

        private fun awaitResponse(task: java.util.concurrent.Future<JsonObject>, checkpoint: () -> Unit, timeoutMillis: Long): JsonObject {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
            while (true) {
                checkpoint()
                check(!closed) { "Algorithm plugin was stopped" }
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) throw TimeoutException("Algorithm plugin timed out: ${entry.manifest.name}")
                try { return task.get(minOf(remaining, TimeUnit.MILLISECONDS.toNanos(50)), TimeUnit.NANOSECONDS) }
                catch (_: TimeoutException) { continue }
            }
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
                check(process.waitFor(5, TimeUnit.SECONDS)) { "Plugin worker did not exit; package remains installed" }
                descendants.forEach { runCatching { it.onExit().get(5, TimeUnit.SECONDS) } }
                stderr.cancel(true)
                runCatching { process.inputStream.close() }
                runCatching { process.outputStream.close() }
                runCatching { process.errorStream.close() }
                runCatching { ModifierPluginStore.deleteTree(resources) }
            } finally { if (interrupted) Thread.currentThread().interrupt() }
        }
    }
}
