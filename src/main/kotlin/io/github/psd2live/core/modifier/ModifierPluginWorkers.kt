package io.github.psd2live.core.modifier

import io.github.psd2live.core.RigBuildProfile
import io.github.psd2live.algorithm.worker.AlgorithmFrames
import io.github.psd2live.algorithm.worker.AlgorithmObjects
import io.github.psd2live.algorithm.worker.AlgorithmResultValidation
import io.github.psd2live.format.compile.RigIrObjects
import io.github.psd2live.format.model.Deformer
import io.github.psd2live.format.model.Mesh
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.targets.cubism.PuppetIr
import org.umamo.runtime.model.ChannelValue
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.visibleDrawableIds
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
    // The manifest names the component separately from the structured IR field.
    private fun typedWrites(writes: Set<String>): Set<String> =
        writes + listOfNotNull("restPose".takeIf { "rest_pose" in writes }, "hitArea".takeIf { "hit_areas" in writes })

    private val CAPABILITIES_JSON = JsonArray((ModifierPluginOutput.operations + setOf(
        "rig_objects", "author_context_v1", "author_context_v2", "input_reads_v1", "input_mesh_ids_v1",
        io.github.psd2live.algorithm.worker.AlgorithmChanges.CAPABILITY,
        io.github.psd2live.algorithm.worker.AlgorithmChanges.RASTER_CAPABILITY,
    )).sorted().map(::JsonPrimitive))

    private data class WorkerKey(val storeRoot: Path, val protocol: Int, val command: List<String>, val files: Map<String, String>, val dependencies: List<ModifierPluginReference>)

    private fun workerKey(store: ModifierPluginStore, entry: InstalledModifierPlugin): WorkerKey {
        val raw = entry.manifest.commands[ModifierPluginStore.platform] ?: entry.manifest.commands.getValue("jvm")
        val normalized = raw.mapIndexed { index, value ->
            when {
                value == "\$JAVA" -> "\$JAVA"
                value in entry.manifest.files -> "file:$value:${entry.manifest.files.getValue(value)}"
                index == 0 -> ModifierPluginStore.inside(entry.directory, value).toString()
                else -> value
            }
        }
        val dependencies = store.resolution(entry.reference).dependencies
        return WorkerKey(store.root.toAbsolutePath().normalize(), entry.manifest.protocol, normalized, entry.manifest.files, dependencies)
    }

    private val workers = ConcurrentHashMap<WorkerKey, Worker>()
    private val workerKeys = ConcurrentHashMap<ModifierPluginReference, WorkerKey>()
    private val executor = Executors.newVirtualThreadPerTaskExecutor()
    private class QueuedFailure(val original: Throwable) : RuntimeException(original)
    init { Runtime.getRuntime().addShutdownHook(Thread { workers.values.forEach { it.close() } }) }
    fun stop(reference: ModifierPluginReference) {
        val key = workerKeys.remove(reference) ?: return
        workers.computeIfPresent(key) { _, worker ->
            worker.refs.remove(reference)
            if (worker.refs.isEmpty()) {
                worker.close()
                null
            } else worker
        }
    }

    fun evaluate(entry: InstalledModifierPlugin, algorithm: ModifierPluginAlgorithm, rig: RigIR,
                 instance: RigModifierInstance, checkpoint: () -> Unit, store: ModifierPluginStore = ModifierPluginStore.shared,
                 timeoutMillis: Long = 120_000, context: ModifierAlgorithmContext = ModifierAlgorithmContext.Empty,
                 declaredParameters: List<io.github.psd2live.format.model.Parameter> = emptyList(),
                 resolveRasters: ModifierRasterResolver = RigModifierRasterAssets::resolve): RigIR {
        require(timeoutMillis > 0)
        store.requireAvailable(entry.reference)
        val key = workerKey(store, entry)
        val worker = workers.compute(key) { _, existing ->
            val current = if (existing != null && !existing.isClosed) existing else Worker(store.workerPackage(entry.reference), store)
            current.also { it.refs.add(entry.reference) }
        }!!
        workerKeys[entry.reference] = key
        try {
            val result = worker.evaluate(entry, algorithm, rig, instance, checkpoint, timeoutMillis, context, declaredParameters, resolveRasters)
            store.requireAvailable(entry.reference)
            return result
        } catch (failure: QueuedFailure) {
            throw failure.original
        } catch (failure: Throwable) {
            if (workers.remove(key, worker)) {
                worker.refs.forEach { ref -> workerKeys.remove(ref, key) }
                runCatching { worker.close() }.exceptionOrNull()?.let(failure::addSuppressed)
            }
            throw failure
        }
    }

    private data class ReadResult(val algorithm: ModifierPluginAlgorithm, val instance: RigModifierInstance,
                                  val context: ModifierAlgorithmContext, val reads: ModifierReadSet,
                                  val changes: ModifierModelChanges)

    private data class SentInput(val meshes: Map<String, Mesh>, val deformers: Map<String, Deformer>,
                                 val objects: Map<String, JsonElement>)

    private class Worker(launchEntry: InstalledModifierPlugin, private val store: ModifierPluginStore) : AutoCloseable {
        val refs: MutableSet<ModifierPluginReference> = ConcurrentHashMap.newKeySet()
        private val readResults = object : LinkedHashMap<Pair<ModifierPluginReference, String>, ReadResult>(16, .75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<ModifierPluginReference, String>, ReadResult>?): Boolean = size > 256
        }
        private val protocol = launchEntry.manifest.protocol
        private val writtenHashes = HashSet<String>()
        private val sentObjectBlobs = HashMap<String, ByteArray>()
        private var nextBlobId = 0L
        private val geometryBlobs = java.util.IdentityHashMap<io.github.psd2live.format.model.MeshGeometry, Pair<JsonObject, JsonObject>>()
        private val geometryRawBlobs = java.util.IdentityHashMap<io.github.psd2live.format.model.MeshGeometry, Pair<ByteArray, ByteArray>>()
        private val resources = Files.createTempDirectory("psd2live-plugin-")
        private val resolvedDeps = store.resolution(launchEntry.reference).dependencies
        private val runtimeDir = try {
            Files.createDirectories(resources.resolve(".runtime")).also { dir ->
                fun copyFiles(sourceRoot: Path, targetRoot: Path, files: Map<String, String>) {
                    files.keys.forEach { relative ->
                        val source = ModifierPluginStore.inside(sourceRoot, relative)
                        val target = targetRoot.resolve(relative).normalize()
                        require(target.startsWith(targetRoot))
                        Files.createDirectories(target.parent)
                        Files.copy(source, target)
                    }
                }
                copyFiles(launchEntry.directory, dir, launchEntry.manifest.files)
                if (resolvedDeps.isNotEmpty()) {
                    val depsRoot = Files.createDirectories(dir.resolve(".deps"))
                    val byRef = store.snapshot().associateBy { it.reference }
                    val manifestEntries = resolvedDeps.mapNotNull { depRef ->
                        byRef[depRef]?.let { depPkg ->
                            val depDir = Files.createDirectories(depsRoot.resolve(depRef.id))
                            copyFiles(depPkg.directory, depDir, depPkg.manifest.files)
                            buildJsonObject {
                                put("id", depRef.id)
                                put("version", depRef.version)
                                put("digest", depRef.digest)
                                put("dir", depDir.toString())
                            }
                        }
                    }
                    Files.writeString(dir.resolve("deps.json"), buildJsonObject {
                        put("dependencies", JsonArray(manifestEntries))
                    }.toString())
                }
            }
        } catch (failure: Exception) { ModifierPluginStore.deleteTree(resources); throw failure }
        private val command = (launchEntry.manifest.commands[ModifierPluginStore.platform] ?: launchEntry.manifest.commands.getValue("jvm")).mapIndexed { index, value ->
            when {
                value == "\$JAVA" -> Path.of(System.getProperty("java.home"), "bin", if (ModifierPluginStore.platform.startsWith("windows")) "java.exe" else "java").toString()
                value in launchEntry.manifest.files -> runtimeDir.resolve(value).toString()
                index == 0 -> ModifierPluginStore.inside(launchEntry.directory, value).toString()
                else -> value
            }
        }
        private val process = try { ProcessBuilder(command).directory(runtimeDir.toFile()).apply {
            environment()["PSD2LIVE_PLUGIN_RESOURCES"] = resources.toString()
            environment()["PSD2LIVE_PLUGIN_PROTOCOL"] = protocol.toString()
            if (resolvedDeps.isNotEmpty()) {
                environment()["PSD2LIVE_PLUGIN_DEPS"] = runtimeDir.resolve("deps.json").toString()
            }
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
        private val writer = if (protocol == 1) process.outputStream.bufferedWriter(Charsets.UTF_8) else null
        private val reader = if (protocol == 1) process.inputStream.bufferedReader(Charsets.UTF_8) else null
        private val frameOut = if (protocol == 2) java.io.BufferedOutputStream(process.outputStream) else null
        private val frameIn = if (protocol == 2) java.io.BufferedInputStream(process.inputStream) else null
        private var sequence = 0L
        private var previous: SentInput? = null
        @Volatile private var closed = false
        val isClosed: Boolean get() = closed || !process.isAlive
        private val requests = java.util.concurrent.locks.ReentrantLock(true)

        fun evaluate(entry: InstalledModifierPlugin, algorithm: ModifierPluginAlgorithm, rig: RigIR, instance: RigModifierInstance, checkpoint: () -> Unit, timeoutMillis: Long, context: ModifierAlgorithmContext, declaredParameters: List<io.github.psd2live.format.model.Parameter>, resolveRasters: ModifierRasterResolver): RigIR {
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
            try { return evaluateLocked(entry, algorithm, rig, instance, checkpoint, timeoutMillis, context, declaredParameters, resolveRasters) }
            finally { requests.unlock() }
        }

        private fun evaluateLocked(entry: InstalledModifierPlugin, algorithm: ModifierPluginAlgorithm, rig: RigIR, instance: RigModifierInstance, checkpoint: () -> Unit, timeoutMillis: Long, context: ModifierAlgorithmContext, declaredParameters: List<io.github.psd2live.format.model.Parameter>, resolveRasters: ModifierRasterResolver): RigIR {
            check(!closed) { "Algorithm plugin was stopped" }
            check(process.isAlive) { "Algorithm plugin process exited" }
            checkpoint()
            val input = ModifierInputCache.project(rig, algorithm.reads + algorithm.writes)
            val cacheKey = entry.reference to instance.id
            val cached = readResults[cacheKey]?.takeIf {
                it.algorithm == algorithm && it.instance == instance && it.context == context && it.reads.matches(input, context)
            }
            if (cached != null) {
                cached.changes.requireWrites(typedWrites(algorithm.writes))
                val output = try { cached.changes.applyWithResources(rig, checkpoint, resolveRasters) } catch (_: IllegalArgumentException) { null }
                if (output != null) {
                    AlgorithmResultValidation.validate(output)
                    val validated = ModifierPluginOutput.validate(rig, output, instance, algorithm, declaredParameters)
                    checkpoint()
                    RigBuildProfile.count("plugin read cache hits")
                    return validated
                }
            }
            readResults.remove(cacheKey)
            val requestBlobs = if (protocol == 2) LinkedHashMap<String, ByteArray>() else null
            fun writeBlob(bytes: ByteArray): JsonObject = if (requestBlobs != null) {
                val key = "b${nextBlobId++}"
                requestBlobs[key] = bytes
                buildJsonObject { put("inline", key); put("bytes", bytes.size) }
            } else blob(bytes)
            val sent = RigBuildProfile.stage("plugin input serialization") { serializeInput(input, ::writeBlob) }
            val current = sent.objects
            val earlier = previous?.objects.orEmpty()
            require(context.upstream.isEmpty() || algorithm.contextVersion >= 2) { "Plugin does not support upstream capability inputs" }
            val requestId = ++sequence
            val request = buildJsonObject {
                put("protocol", protocol); put("id", requestId); put("method", "evaluate")
                put("type", algorithm.type); put("version", instance.version); put("instance", instance.id)
                put("capabilities", CAPABILITIES_JSON)
                if (context != ModifierAlgorithmContext.Empty) put("context", io.github.psd2live.algorithm.worker.AlgorithmContexts.encode(context, ::writeBlob))
                put("invocation", Json.encodeToJsonElement(RigModifierInstance.serializer(), instance.copy(type = algorithm.type)))
                put("dependencies", JsonArray(store.resolution(entry.reference).dependencies.map { reference ->
                    Json.encodeToJsonElement(ModifierPluginReference.serializer(), reference)
                }))
                put("config", instance.config)
                if (protocol == 1) put("resource_directory", resources.toString())
                put("reset", requestId == 1L)
                put("changed", JsonObject(current.filter { (key, value) -> earlier[key] != value }))
                put("removed", JsonArray((earlier.keys - current.keys).map(::JsonPrimitive)))
                put("mesh_order", JsonArray(input.meshes.map { JsonPrimitive(it.id) }))
                put("deformer_order", JsonArray(input.deformers.map { JsonPrimitive(it.id) }))
                putJsonArray("targets") { instance.targets.forEach { addJsonObject { put("kind", it.kind); put("id", it.id) } } }
                putJsonArray("parameters") { input.parameters.forEach { addJsonObject {
                    put("id", it.id); put("min", it.min); put("max", it.max); put("default", it.default)
                } } }
                if (declaredParameters.isNotEmpty()) {
                    putJsonArray("declared_parameters") {
                        declaredParameters.forEach { p ->
                            addJsonObject {
                                put("id", p.id); put("name", p.name)
                                put("min", p.min); put("max", p.max); put("default", p.default)
                                put("blend", p.blend); put("repeat", p.repeat)
                                p.keys?.let { keys -> putJsonArray("keys") { keys.toArray().forEach { add(it) } } }
                            }
                        }
                    }
                }
                if ("physics" in algorithm.reads || "*" in algorithm.reads) {
                    putJsonArray("physics_groups") {
                        input.physics.groups.forEach { group ->
                            addJsonObject {
                                put("id", group.id); put("name", group.name)
                                putJsonArray("inputs") {
                                    group.inputs.forEach { pin ->
                                        addJsonObject {
                                            put("parameter", pin.parameter)
                                            put("weight", pin.weight)
                                            put("type", pin.source.name)
                                            put("reflect", pin.reflect)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                putJsonArray("clips") { input.clips.forEach { addJsonObject { put("id", it.id); put("file", it.file) } } }
                putJsonArray("parameter_roles") { input.parameterRoles.forEach { addJsonObject {
                    put("role", it.role); putJsonArray("parameters") { it.parameters.forEach { p -> add(p) } }
                } } }
                // Portable geometry views avoid requiring a language-specific full model decoder.
                val targetMeshIds = instance.targets.filter { it.kind == "mesh" }.mapTo(HashSet()) { it.id }
                val readsMeshes = "mesh" in algorithm.reads || "*" in algorithm.reads
                val puppet by lazy { PuppetIr.toPuppet(input) }
                val visibleIds by lazy { puppet.visibleDrawableIds().mapTo(HashSet()) { it.raw } }
                val drawablesById by lazy { puppet.drawables.associateBy { it.id.raw } }
                putJsonArray("meshes") {
                    input.meshes.filter { readsMeshes || it.id in targetMeshIds }.forEach { mesh -> addJsonObject {
                        put("id", mesh.id); put("name", mesh.name); put("drawOrder", mesh.drawOrder)
                        put("opacity", mesh.opacity); put("invertMask", mesh.invertMask)
                        val drawable = drawablesById[mesh.id]
                        if (readsMeshes) {
                            val orders = drawable?.channelGrids?.get(FormChannel.DRAW_ORDER)?.cells
                                ?.mapNotNull { (it.form as? ChannelValue.Scalar)?.value }?.takeIf { it.isNotEmpty() }
                            put("lowestOrder", orders?.min() ?: mesh.drawOrder)
                            put("highestOrder", orders?.max() ?: mesh.drawOrder)
                            put("visible", mesh.id in visibleIds)
                            put("hasOpacityTrack", drawable?.channelGrids?.get(FormChannel.OPACITY) != null)
                        }
                        drawable?.channelGrids?.get(FormChannel.OPACITY)?.let { grid ->
                            putJsonObject("opacityGrid") {
                                putJsonArray("axes") {
                                    grid.axes.forEach { axis ->
                                        addJsonObject {
                                            put("parameter", axis.parameterId.raw)
                                            putJsonArray("keys") { axis.keys.forEach { add(it) } }
                                        }
                                    }
                                }
                                putJsonArray("cells") {
                                    grid.cells.forEach { cell ->
                                        val scalar = (cell.form as? ChannelValue.Scalar)?.value ?: return@forEach
                                        addJsonObject {
                                            putJsonArray("coord") { cell.coordinate.forEach { add(it) } }
                                            put("value", scalar)
                                        }
                                    }
                                }
                            }
                        }
                        putJsonArray("maskedBy") { mesh.maskedBy.forEach { add(it) } }
                        if (mesh.id in targetMeshIds) mesh.geometry?.let { geometry ->
                        if (requestBlobs != null) {
                            val (posBytes, uvBytes) = geometryRawBlobs.getOrPut(geometry) {
                                floatBytes(geometry.positions.toArray()) to floatBytes(geometry.uvs.toArray())
                            }
                            put("positions", floatRef(writeBlob(posBytes), geometry.positions.size))
                            put("uvs", floatRef(writeBlob(uvBytes), geometry.uvs.size))
                        } else {
                            val (positions, uvs) = geometryBlobs.getOrPut(geometry) {
                                floatBlob(geometry.positions.toArray()) to floatBlob(geometry.uvs.toArray())
                            }
                            put("positions", positions)
                            put("uvs", uvs)
                        }
                    }
                } } }
            }
            val task = executor.submit<AlgorithmFrames.Frame> {
                RigBuildProfile.count("plugin worker evaluations")
                if (protocol == 2) {
                    AlgorithmFrames.write(frameOut!!, request, requestBlobs!!)
                    AlgorithmFrames.read(frameIn!!)
                        ?: error("Algorithm plugin exited (${entry.manifest.name}): ${synchronized(errors) { errors.toString() }}")
                } else {
                    val text = request.toString()
                    RigBuildProfile.count("plugin sent bytes", text.toByteArray().size.toLong())
                    writer!!.write(text); writer.newLine(); writer.flush()
                    val line = reader!!.readLine()
                        ?: error("Algorithm plugin exited (${entry.manifest.name}): ${synchronized(errors) { errors.toString() }}")
                    require(line.length < 16 * 1024 * 1024) { "Plugin response exceeds size limit" }
                    AlgorithmFrames.Frame(Json.parseToJsonElement(line).jsonObject, emptyMap())
                }
            }
            val frame = try { awaitResponse(entry, task, checkpoint, timeoutMillis) }
            catch (failure: ExecutionException) { throw failure.cause ?: failure }
            catch (failure: Exception) { task.cancel(true); throw failure }
            val response = frame.header
            val responseBlobs = frame.blobs
            checkpoint()
            require(response.keys.all { it in setOf("protocol", "id", "commands", "objects", "changes", "reads", "error") } && response["protocol"]?.jsonPrimitive?.int == protocol && response["id"]?.jsonPrimitive?.long == requestId) { "Invalid plugin response envelope" }
            response["error"]?.let { error("${entry.manifest.name}: ${it.jsonPrimitive.content}") }
            require(listOf("commands", "objects", "changes").count { it in response } == 1) { "Plugin must return exactly one result format" }
            require("reads" !in response || "changes" in response) { "Read observations require structured changes" }
            fun readResponseBlob(ref: JsonElement): ByteArray = if (protocol == 2) {
                val obj = ref.jsonObject
                val key = obj.getValue("inline").jsonPrimitive.content
                val combined = responseBlobs[key] ?: requestBlobs?.get(key) ?: sentObjectBlobs[key]
                if (combined != null) {
                    val expected = obj.getValue("bytes").jsonPrimitive.long
                    require(obj.keys == setOf("inline", "bytes") && combined.size.toLong() == expected) { "Invalid inline algorithm object" }
                    combined
                } else AlgorithmObjects.readInline(responseBlobs, ref)
            } else AlgorithmObjects.read(resources, ref)
            var readResult: ReadResult? = null
            val output = response["changes"]?.let { value ->
                val changes = io.github.psd2live.algorithm.worker.AlgorithmChanges.decode(value.jsonObject, ::readResponseBlob)
                changes.requireWrites(typedWrites(algorithm.writes))
                val reads = response["reads"]?.let { encoded ->
                    ModifierReadSet.capture(input, context, Json.decodeFromJsonElement<List<ModifierReadKey>>(encoded))
                }
                val merged = changes.applyWithResources(rig, checkpoint, resolveRasters)
                AlgorithmResultValidation.validate(merged)
                ModifierPluginOutput.validate(rig, merged, instance, algorithm, declaredParameters).also {
                    if (reads != null) readResult = ReadResult(algorithm, instance, context, reads, changes)
                }
            } ?: response["objects"]?.let { value ->
                val delta = value.jsonObject
                require(delta.keys == setOf("changed", "removed", "mesh_order", "deformer_order")) { "Invalid model object result" }
                val objects = AlgorithmObjects.apply(current, delta)
                val projected = AlgorithmObjects.decode(objects, delta.getValue("mesh_order").jsonArray,
                    delta.getValue("deformer_order").jsonArray, ::readResponseBlob)
                checkpoint()
                val merged = ModifierInputCache.Patch(input, projected).apply(rig)
                AlgorithmResultValidation.validate(merged)
                ModifierPluginOutput.validate(rig, merged, instance, algorithm, declaredParameters)
            } ?: run {
                val commands = response.getValue("commands").jsonArray
                require(commands.size <= 10000) { "Too many plugin commands" }
                ModifierPluginOutput.apply(rig, instance, algorithm, JsonArray(commands.map { expand(it, responseBlobs) }), checkpoint, declaredParameters)
            }
            checkpoint()
            previous = sent
            if (requestBlobs != null) {
                val retainedKeys = current.values.mapNotNullTo(HashSet()) { it.jsonObject["inline"]?.jsonPrimitive?.content }
                sentObjectBlobs.keys.retainAll(retainedKeys)
                for (key in retainedKeys) {
                    requestBlobs[key]?.let { sentObjectBlobs[key] = it }
                }
            }
            readResult?.let { readResults[cacheKey] = it }
            return output
        }

        private fun serializeInput(input: RigIR, writeBlob: (ByteArray) -> JsonObject): SentInput {
            val previous = previous
            val retained = HashMap<String, JsonElement>()
            fun <T> changes(kind: String, values: List<T>, prior: Map<String, T>, id: (T) -> String) = values.filter { value ->
                val key = "$kind:${id(value)}"
                val reference = previous?.objects?.get(key)
                val available = reference != null && (protocol == 2 || reference.jsonObject.getValue("sha256").jsonPrimitive.content in writtenHashes)
                if (prior[id(value)] !== value || !available) true
                else { retained[key] = reference!!; false }
            }
            // Identity preserves the exact serialization, including signed zero and ordered maps.
            val changedMeshes = changes("mesh", input.meshes, previous?.meshes.orEmpty(), Mesh::id)
            val changedDeformers = changes("deformer", input.deformers, previous?.deformers.orEmpty(), Deformer::id)
            val encoded = RigIrObjects.split(input.copy(meshes = changedMeshes, deformers = changedDeformers))
            val objects = linkedMapOf<String, JsonElement>("frame" to writeBlob(encoded.frame))
            val deformers = encoded.deformers.iterator()
            val meshes = encoded.meshes.iterator()
            input.deformers.forEach { val key = "deformer:${it.id}"; objects[key] = retained[key] ?: writeBlob(deformers.next()) }
            input.meshes.forEach { val key = "mesh:${it.id}"; objects[key] = retained[key] ?: writeBlob(meshes.next()) }
            RigBuildProfile.count("plugin input objects encoded", 1L + changedMeshes.size + changedDeformers.size)
            RigBuildProfile.count("plugin input objects reused", retained.size.toLong())
            return SentInput(input.meshes.associateBy(Mesh::id), input.deformers.associateBy(Deformer::id), objects)
        }

        private fun <T> awaitResponse(entry: InstalledModifierPlugin, task: java.util.concurrent.Future<T>, checkpoint: () -> Unit, timeoutMillis: Long): T {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
            while (true) {
                checkpoint()
                check(!closed) { "Algorithm plugin was stopped" }
            check(process.isAlive) { "Algorithm plugin process exited" }
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) throw TimeoutException("Algorithm plugin timed out: ${entry.manifest.name}")
                try { return task.get(minOf(remaining, TimeUnit.MILLISECONDS.toNanos(50)), TimeUnit.NANOSECONDS) }
                catch (_: TimeoutException) { continue }
            }
        }

        private fun blob(bytes: ByteArray): JsonObject {
            val hash = ModifierPluginStore.hash(bytes)
            if (writtenHashes.add(hash)) {
                val path = resources.resolve(hash)
                if (!Files.exists(path)) {
                    Files.write(path, bytes)
                    RigBuildProfile.count("plugin resource bytes", bytes.size.toLong())
                }
            }
            return buildJsonObject { put("sha256", hash); put("bytes", bytes.size) }
        }
        private fun floatBytes(values: FloatArray): ByteArray {
            val buffer = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            values.forEach(buffer::putFloat)
            return buffer.array()
        }
        private fun floatRef(ref: JsonObject, count: Int): JsonObject =
            JsonObject(ref + mapOf("format" to JsonPrimitive("f32le"), "count" to JsonPrimitive(count)))
        private fun floatBlob(values: FloatArray): JsonObject =
            floatRef(blob(floatBytes(values)), values.size)
        private fun expand(value: JsonElement, responseBlobs: Map<String, ByteArray>): JsonElement = when (value) {
            is JsonObject -> if (value["format"]?.jsonPrimitive?.content == "f32le" && ("sha256" in value || "inline" in value)) {
                val bytes = if ("inline" in value) {
                    val key = value.getValue("inline").jsonPrimitive.content
                    val raw = responseBlobs[key] ?: throw IllegalArgumentException("Missing inline plugin buffer: $key")
                    require(raw.size <= 64 * 1024 * 1024 && raw.size % 4 == 0) { "Invalid plugin buffer" }
                    raw
                } else {
                    val hash = value.getValue("sha256").jsonPrimitive.content
                    require(hash.matches(Regex("[a-f0-9]{64}"))) { "Invalid plugin resource reference" }
                    val path = resources.resolve(hash)
                    require(!Files.isSymbolicLink(path) && Files.size(path) <= 64 * 1024 * 1024) { "Invalid plugin buffer" }
                    val raw = Files.readAllBytes(path)
                    require(ModifierPluginStore.hash(raw) == hash && raw.size % 4 == 0) { "Plugin buffer checksum mismatch" }
                    raw
                }
                require(value["bytes"]?.jsonPrimitive?.int == bytes.size && value["count"]?.jsonPrimitive?.int == bytes.size / 4) { "Plugin buffer size mismatch" }
                val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                JsonArray(List(bytes.size / 4) { JsonPrimitive(buffer.float.also { require(it.isFinite()) }) })
            } else JsonObject(value.mapValues { expand(it.value, responseBlobs) })
            is JsonArray -> JsonArray(value.map { expand(it, responseBlobs) })
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
