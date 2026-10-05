package io.github.psd2live.agent

import io.github.psd2live.core.quality.*

import io.github.psd2live.application.WorkspaceBackendStub

import io.github.psd2live.application.WorkspaceOperations
import io.github.psd2live.application.requestEnvelope
import io.github.psd2live.application.responseEnvelope
import io.github.psd2live.application.validateOperationSchema
import io.github.psd2live.application.WorkspaceAuxiliarySnapshot
import io.github.psd2live.application.WorkspaceObservationStub
import io.github.psd2live.project.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class AgentHttpContractTest {
    private class Backend : WorkspaceBackendStub() {
        val release = CompletableDeferred<Unit>()
        val exports = AtomicInteger()
        val bakeRelease = CompletableDeferred<Unit>()
        val bakes = AtomicInteger()
        val sampleRelease = CountDownLatch(1)
        val samples = AtomicInteger()
        val motionRelease = CompletableDeferred<Unit>()
        val motions = AtomicInteger()
        val physicsRelease = CompletableDeferred<Unit>()
        val physicsImports = AtomicInteger()
        val paintRelease = CompletableDeferred<Unit>()
        val paints = AtomicInteger()
        val layerRelease = CompletableDeferred<Unit>()
        val restores = AtomicInteger()
        val generationRelease = CompletableDeferred<Unit>()
        val generations = AtomicInteger()
        val partitionRelease = CompletableDeferred<Unit>()
        val partitions = AtomicInteger()
        val depthRelease = CompletableDeferred<Unit>()
        val depthSplits = AtomicInteger()
        val warpRelease = CompletableDeferred<Unit>()
        val warps = AtomicInteger()
        val motionImage = java.io.ByteArrayOutputStream().also {
            javax.imageio.ImageIO.write(java.awt.image.BufferedImage(128, 152, java.awt.image.BufferedImage.TYPE_INT_ARGB), "png", it)
        }.toByteArray()
        override fun snapshot() = WorkspaceProjectSnapshot("project", "revision", "head", true,
            "artwork", 4, 4, false, "ready", null, emptyList(), emptyList(), state = "generation:0")
        override suspend fun createIndependentWarp(request: JsonObject, expectedState: String): WorkspaceMutationResult {
            warps.incrementAndGet(); warpRelease.await()
            return WorkspaceMutationResult("warp-head", "warp-revision", summary = "Created independent Warp",
                affectedObjectIds = listOf("warp:HttpWarp"), state = "generation:1", projectId = "project")
        }
        override suspend fun splitMeshComponents(state: String, splits: List<JsonObject>): WorkspaceMutationResult {
            partitions.incrementAndGet(); partitionRelease.await()
            return WorkspaceMutationResult("partition-head", "partition-revision", listOf("island-a", "island-b"), "Partitioned source",
                state = "generation:1", projectId = "project")
        }
        override suspend fun splitDepth(state: String, request: JsonObject): WorkspaceMutationResult {
            depthSplits.incrementAndGet(); depthRelease.await()
            return WorkspaceMutationResult("depth-head", "depth-revision", listOf("depth-front"), "Depth slices",
                state = "generation:1", projectId = "project")
        }
        override suspend fun updateProjectSettings(state: String, changes: JsonObject): WorkspaceMutationResult {
            generations.incrementAndGet(); generationRelease.await()
            return WorkspaceMutationResult("generation-head", "generation-revision", summary = "Updated generation",
                state = "generation:1", projectId = "project")
        }
        override suspend fun renderLayer(layerId: String, background: WorkspaceViewBackground, output: WorkspaceViewOutputSpec): WorkspaceRenderedView = error("Unused")
        override suspend fun renderContext(layerId: String, objectScale: Float, aspectRatio: Float, background: WorkspaceViewBackground, output: WorkspaceViewOutputSpec): WorkspaceRenderedView = error("Unused")
        override suspend fun renderModel(request: WorkspaceModelViewRequest): WorkspaceRenderedView = error("Unused")
        override fun captureObservation() = object : WorkspaceObservationStub() {
            override fun snapshot() = this@Backend.snapshot()
            override suspend fun observeAuthoring(arguments: JsonObject): WorkspaceWorkflowResult {
                motions.incrementAndGet(); motionRelease.await()
                return WorkspaceWorkflowResult(buildJsonObject {
                    put("revisionId", "revision"); put("physicsSimulated", true); put("comparison", "poses")
                    put("width", 128); put("height", 152); put("canvasRect", JsonArray(listOf(0, 0, 4, 4).map(::JsonPrimitive)))
                    put("commonParameters", buildJsonObject {})
                    putJsonArray("tiles") { add(buildJsonObject {
                        put("id", "A"); put("viewId", "view-motion"); put("imageRect", JsonArray(listOf(0, 24, 128, 128).map(::JsonPrimitive)))
                        putJsonObject("parameters") { put("Drive", 20) }
                    }) }
                    put("backend", "Synthetic HTTP fixture"); put("fps", 60); put("sampleTimes", JsonArray(listOf(JsonPrimitive(0.1))))
                    putJsonObject("ranges") { put("Drive", JsonArray(listOf(JsonPrimitive(-20), JsonPrimitive(20)))) }
                }, listOf(motionImage))
            }
        }
        override fun savedProjectData() = WorkspaceAuxiliarySnapshot("project", "generation:0", "head",
            WorkspaceAuxiliaryData(listOf(ParameterSnapshot("pose", 1, "Pose", emptyMap()))), setOf("head"))
        override suspend fun exportModel(state: String, outputDirectory: String): JsonObject {
            exports.incrementAndGet()
            release.await()
            return buildJsonObject {
                put("state", state); put("revision", "revision"); put("warnings", JsonArray(emptyList()))
                put("quality", QualityInspection.combine(QualityFence.EXPORT_PUBLICATION, emptyList()).toJson())
                putJsonArray("files") { add(buildJsonObject { put("path", outputDirectory + "/model.cmo3"); put("bytes", 42) }) }
            }
        }
        override suspend fun bakeSimulation(id: String, expectedState: String): Pair<WorkspaceMutationResult, JsonObject> {
            bakes.incrementAndGet(); bakeRelease.await()
            return WorkspaceMutationResult("baked-head", "baked-revision", emptyList(), "Baked simulation",
                affectedObjectIds = listOf("parameter:SimAxis"), state = "generation:1", projectId = "project") to buildJsonObject {
                put("quality", QualityInspection.combine(QualityFence.OBSERVATION, emptyList()).toJson())
                putJsonArray("modes") { add(buildJsonObject { put("parameter", "SimAxis"); put("amplitude_px", 12); put("energy", 1) }) }
                for (field in listOf("fit_r2", "error_p95_px", "parameter_peak", "clipped_frames", "jerk_ratio")) put(field, 0.5)
            }
        }
        override suspend fun importPhysics(path: String, expectedState: String): Pair<WorkspaceMutationResult, JsonObject> {
            physicsImports.incrementAndGet(); physicsRelease.await()
            return WorkspaceMutationResult("physics-head", "physics-revision", emptyList(), "Imported physics",
                state = "generation:1", projectId = "project") to buildJsonObject {
                putJsonArray("imported") { add("Pendulum") }; putJsonArray("disabled") { add("Previous") }
                putJsonObject("missing_parameters") { putJsonArray("Pendulum") { add("MissingAxis") } }; put("fps", 30)
            }
        }
        override suspend fun paintSource(arguments: JsonObject): WorkspaceMutationResult {
            paints.incrementAndGet(); paintRelease.await()
            return WorkspaceMutationResult("paint-head", "paint-revision", listOf("art"), "Painted artwork",
                affectedObjectIds = listOf("mesh:NewPaintedMesh"), state = "generation:1", projectId = "project")
        }
        override suspend fun restoreDeletedLayers(layerIds: List<String>?, expectedState: String, taskId: String?): WorkspaceMutationResult {
            restores.incrementAndGet(); layerRelease.await()
            return WorkspaceMutationResult("restored-head", "restored-revision", layerIds ?: listOf("art"), "Restored layers",
                state = "generation:1", projectId = "project")
        }
        override fun reportSimulation(id: String, hold: Float, release: Float, wind: Pair<Float, Float>?,
                                      progress: (Float) -> Unit, cancelled: () -> Boolean): JsonObject {
            samples.incrementAndGet(); progress(0.3f)
            check(sampleRelease.await(10, TimeUnit.SECONDS))
            cancelled(); progress(1f)
            return buildJsonObject {
                put("id", id); put("particles", 8); put("pinned", 2); put("phases", JsonArray(emptyList()))
                put("quality", QualityInspection.combine(QualityFence.OBSERVATION, emptyList()).toJson())
                put("calibration_residual_px", 0); put("rest_drift_px", 0); put("max_stretch_percent", 1)
            }
        }
    }

    @Test fun bearerAuthenticationExactPublicationAndJobsWorkAcrossHttpSessions() = runBlocking {
        val backend = Backend()
        val token = "synthetic-contract-token-with-at-least-32-characters"
        AgentMcpService(backend, AgentMcpConfig(port = 0, token = token)).use { service ->
            val endpoint = service.start().endpoint
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build().use { client ->
                var sequence = 0
                var publishedTools = emptyMap<String, JsonObject>()
                fun send(body: JsonObject, session: String? = null, credential: String? = token,
                         method: String = "POST"): HttpResponse<String> {
                    val request = HttpRequest.newBuilder(URI.create(endpoint)).timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json, text/event-stream")
                        .header("MCP-Protocol-Version", "2025-06-18")
                    credential?.let { request.header("Authorization", "Bearer $it") }
                    session?.let { request.header("Mcp-Session-Id", it) }
                    request.method(method, if (method == "DELETE") HttpRequest.BodyPublishers.noBody()
                        else HttpRequest.BodyPublishers.ofString(body.toString()))
                    return client.send(request.build(), HttpResponse.BodyHandlers.ofString())
                }
                fun rpc(method: String, params: JsonObject = JsonObject(emptyMap())) = buildJsonObject {
                    put("jsonrpc", "2.0"); put("id", ++sequence); put("method", method); put("params", params)
                }
                fun initialize(): String {
                    val response = send(rpc("initialize", buildJsonObject {
                        put("protocolVersion", "2025-06-18")
                        put("capabilities", JsonObject(emptyMap()))
                        putJsonObject("clientInfo") { put("name", "contract-test"); put("version", "1") }
                    }))
                    assertEquals(200, response.statusCode(), response.body())
                    val session = response.headers().firstValue("mcp-session-id").orElseThrow()
                    assertEquals(202, send(buildJsonObject {
                        put("jsonrpc", "2.0"); put("method", "notifications/initialized")
                    }, session).statusCode())
                    return session
                }
                fun call(session: String, name: String, request: JsonObject): JsonObject {
                    val response = send(rpc("tools/call", buildJsonObject {
                        put("name", name); putJsonObject("arguments") { put("request", request) }
                    }), session)
                    assertEquals(200, response.statusCode(), response.body())
                    val result = Json.parseToJsonElement(response.body()).jsonObject.getValue("result").jsonObject
                    publishedTools[name]?.get("outputSchema")?.jsonObject?.let { schema ->
                        validateOperationSchema(result.getValue("structuredContent"), schema, "response")
                    }
                    return result
                }
                assertEquals(401, send(rpc("tools/list"), credential = null).statusCode())
                assertEquals(401, send(rpc("tools/list"), credential = "wrong-token").statusCode())
                assertEquals(404, send(rpc("tools/list"), session = "missing-session").statusCode())
                val first = initialize()
                val listed = send(rpc("tools/list"), first)
                assertEquals(200, listed.statusCode(), listed.body())
                val tools = Json.parseToJsonElement(listed.body()).jsonObject.getValue("result").jsonObject
                    .getValue("tools").jsonArray.associate { it.jsonObject.getValue("name").jsonPrimitive.content to it.jsonObject }
                publishedTools = tools
                WorkspaceOperations(backend).use { operations ->
                    assertEquals(operations.registry.definitions().map { it.id }.toSet(), tools.keys)
                    operations.registry.definitions().forEach { operation ->
                        assertEquals(operation.requestEnvelope(), tools.getValue(operation.id).getValue("inputSchema"))
                        assertEquals(operation.responseEnvelope(), tools.getValue(operation.id).getValue("outputSchema"))
                        assertEquals(operation.requestSchema, tools.getValue(operation.id).getValue("inputSchema")
                            .jsonObject.getValue("properties").jsonObject.getValue("request"))
                    }
                }
                val detail = call(first, "workspace_get_operation", buildJsonObject { put("id", "workspace_get_operation") })
                    .getValue("structuredContent").jsonObject.getValue("data").jsonObject
                assertEquals(tools.getValue("workspace_get_operation").getValue("outputSchema"), detail.getValue("output_schema"))
                val jobDetail = call(first, "workspace_get_operation", buildJsonObject { put("id", "project_export_model") })
                    .getValue("structuredContent").jsonObject.getValue("data").jsonObject
                assertTrue("job_result_schema" in jobDetail)
                val invalid = call(first, "skeleton_get", buildJsonObject { put("mode", "get") })
                assertTrue(invalid.getValue("isError").jsonPrimitive.boolean)
                assertEquals("invalid_request", invalid.getValue("structuredContent").jsonObject
                    .getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
                val outputSchema = tools.getValue("snapshot_get").getValue("outputSchema").jsonObject
                val saved = call(first, "snapshot_get", buildJsonObject { put("id", "pose") }).getValue("structuredContent").jsonObject
                validateOperationSchema(saved, outputSchema, "response")
                assertEquals("pose", saved.getValue("data").jsonObject.getValue("snapshot").jsonObject.getValue("id").jsonPrimitive.content)
                val wrongSnapshot = call(first, "snapshot_get", buildJsonObject { put("id", 0) }).getValue("structuredContent").jsonObject
                validateOperationSchema(wrongSnapshot, outputSchema, "response")
                assertEquals("invalid_request", wrongSnapshot.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
                val export = buildJsonObject { put("request_id", "export-http"); put("project_id", "project"); put("state", "generation:0"); put("output_directory", "/out") }
                val started = call(first, "project_export_model", export).getValue("structuredContent").jsonObject.getValue("data").jsonObject
                val job = started.getValue("id").jsonPrimitive.content
                val simulation = buildJsonObject { put("request_id", "bake-http"); put("project_id", "project"); put("state", "generation:0"); put("id", "sway") }
                val bakeJob = call(first, "simulation_bake", simulation).getValue("structuredContent").jsonObject.getValue("data").jsonObject.getValue("id")
                val bakeDetail = call(first, "workspace_get_operation", buildJsonObject { put("id", "simulation_bake") })
                    .getValue("structuredContent").jsonObject.getValue("data").jsonObject
                assertTrue(bakeDetail.getValue("job_backed").jsonPrimitive.boolean)
                assertTrue(bakeDetail.getValue("batchable").jsonPrimitive.boolean)
                assertTrue("job_result_schema" in bakeDetail)
                val sampling = buildJsonObject { put("request_id", "sample-http"); put("project_id", "project"); put("state", "generation:0"); put("id", "sway") }
                val sampleJob = call(first, "simulation_simulate", sampling).getValue("structuredContent").jsonObject.getValue("data").jsonObject.getValue("id")
                val sampleDetail = call(first, "workspace_get_operation", buildJsonObject { put("id", "simulation_simulate") })
                    .getValue("structuredContent").jsonObject.getValue("data").jsonObject
                assertTrue(sampleDetail.getValue("read_only").jsonPrimitive.boolean)
                assertTrue(sampleDetail.getValue("job_backed").jsonPrimitive.boolean)
                assertTrue(sampleDetail.getValue("workspace_bound").jsonPrimitive.boolean)
                assertFalse(sampleDetail.getValue("batchable").jsonPrimitive.boolean)
                val motion = buildJsonObject {
                    put("request_id", "motion-http"); put("project_id", "project"); put("state", "generation:0")
                    put("rect", JsonArray(listOf(0, 0, 4, 4).map(::JsonPrimitive)))
                    putJsonArray("frames") {
                        add(buildJsonObject { put("time", 0); putJsonObject("parameters") { put("Drive", -20) } })
                        add(buildJsonObject { put("time", 0.2); putJsonObject("parameters") { put("Drive", 20) } })
                    }
                    put("samples", JsonArray(listOf(JsonPrimitive(0.1))))
                }
                val motionJob = call(first, "view_sample_motion", motion).getValue("structuredContent").jsonObject.getValue("data").jsonObject.getValue("id")
                val physics = buildJsonObject { put("request_id", "physics-http"); put("project_id", "project"); put("state", "generation:0"); put("path", "/synthetic.physics3.json") }
                val physicsJob = call(first, "physics_import", physics).getValue("structuredContent").jsonObject.getValue("data").jsonObject.getValue("id")
                val paint = buildJsonObject {
                    put("request_id", "paint-http"); put("project_id", "project"); put("state", "generation:0"); put("layer_id", "art")
                    put("radius", 1); put("rebuild_mesh", true); putJsonArray("points") { add(buildJsonArray { add(1); add(1) }) }
                    put("color", buildJsonArray { add(30); add(120); add(220); add(255) })
                }
                val paintJob = call(first, "source_paint_pencil", paint).getValue("structuredContent").jsonObject.getValue("data").jsonObject.getValue("id")
                val paintDetail = call(first, "workspace_get_operation", buildJsonObject { put("id", "source_paint_pencil") })
                    .getValue("structuredContent").jsonObject.getValue("data").jsonObject
                assertTrue(paintDetail.getValue("job_backed").jsonPrimitive.boolean)
                assertTrue(paintDetail.getValue("batchable").jsonPrimitive.boolean)
                assertTrue("job_result_schema" in paintDetail)
                val restore = buildJsonObject {
                    put("request_id", "restore-http"); put("project_id", "project"); put("state", "generation:0")
                    put("layer_ids", buildJsonArray { add("art") })
                }
                val restoreJob = call(first, "layer_restore", restore).getValue("structuredContent").jsonObject.getValue("data").jsonObject.getValue("id")
                val restoreDetail = call(first, "workspace_get_operation", buildJsonObject { put("id", "layer_restore") })
                    .getValue("structuredContent").jsonObject.getValue("data").jsonObject
                assertTrue(restoreDetail.getValue("job_backed").jsonPrimitive.boolean)
                assertTrue(restoreDetail.getValue("batchable").jsonPrimitive.boolean)
                val generation = buildJsonObject {
                    put("request_id", "generation-http"); put("project_id", "project"); put("state", "generation:0")
                    putJsonObject("changes") { put("meshEdgeMode", "TRIPLE") }
                }
                val generationJob = call(first, "settings_update", generation).getValue("structuredContent").jsonObject.getValue("data").jsonObject.getValue("id")
                val generationDetail = call(first, "workspace_get_operation", buildJsonObject { put("id", "settings_update") })
                    .getValue("structuredContent").jsonObject.getValue("data").jsonObject
                assertTrue(generationDetail.getValue("job_backed").jsonPrimitive.boolean)
                assertTrue(generationDetail.getValue("batchable").jsonPrimitive.boolean)
                val partition = buildJsonObject {
                    put("request_id", "partition-http"); put("project_id", "project"); put("state", "generation:0"); put("layer_id", "art")
                    put("names", JsonArray(listOf("First", "Second").map(::JsonPrimitive)))
                }
                val partitionJob = call(first, "source_split_components", partition).getValue("structuredContent").jsonObject.getValue("data").jsonObject.getValue("id")
                val partitionDetail = call(first, "workspace_get_operation", buildJsonObject { put("id", "source_split_components") })
                    .getValue("structuredContent").jsonObject.getValue("data").jsonObject
                assertTrue(partitionDetail.getValue("job_backed").jsonPrimitive.boolean)
                assertTrue(partitionDetail.getValue("batchable").jsonPrimitive.boolean)
                val duplicatePartition = call(first, "source_split_components", JsonObject(partition + mapOf(
                    "request_id" to JsonPrimitive("duplicate-partition"), "names" to JsonArray(listOf("Duplicate", "Duplicate").map(::JsonPrimitive)))))
                    .getValue("structuredContent").jsonObject
                assertEquals(false, duplicatePartition.getValue("ok").jsonPrimitive.boolean)
                assertEquals("invalid_request", duplicatePartition.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
                val depth = buildJsonObject {
                    put("request_id", "depth-http"); put("project_id", "project"); put("state", "generation:0"); put("source_id", "RearMesh")
                    put("middle_ids", JsonArray(listOf(JsonPrimitive("MiddleMesh")))); put("front_layer_id", "depth-front")
                }
                val depthJob = call(first, "source_split_depth", depth).getValue("structuredContent").jsonObject.getValue("data").jsonObject.getValue("id")
                val depthDetail = call(first, "workspace_get_operation", buildJsonObject { put("id", "source_split_depth") })
                    .getValue("structuredContent").jsonObject.getValue("data").jsonObject
                assertTrue(depthDetail.getValue("job_backed").jsonPrimitive.boolean); assertTrue(depthDetail.getValue("batchable").jsonPrimitive.boolean)
                val duplicateMiddle = call(first, "source_split_depth", JsonObject(depth + mapOf("request_id" to JsonPrimitive("duplicate-middle"),
                    "middle_ids" to JsonArray(listOf("MiddleMesh", "MiddleMesh").map(::JsonPrimitive))))).getValue("structuredContent").jsonObject
                assertEquals(false, duplicateMiddle.getValue("ok").jsonPrimitive.boolean)
                assertEquals("invalid_request", duplicateMiddle.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
                val physicsDetail = call(first, "workspace_get_operation", buildJsonObject { put("id", "physics_import") })
                    .getValue("structuredContent").jsonObject.getValue("data").jsonObject
                assertTrue(physicsDetail.getValue("job_backed").jsonPrimitive.boolean)
                assertFalse(physicsDetail.getValue("batchable").jsonPrimitive.boolean)
                val motionDetail = call(first, "workspace_get_operation", buildJsonObject { put("id", "view_sample_motion") })
                    .getValue("structuredContent").jsonObject.getValue("data").jsonObject
                assertTrue(motionDetail.getValue("read_only").jsonPrimitive.boolean)
                assertTrue(motionDetail.getValue("job_backed").jsonPrimitive.boolean)
                assertFalse(motionDetail.getValue("batchable").jsonPrimitive.boolean)
                val warp = buildJsonObject {
                    put("request_id", "warp-http"); put("project_id", "project"); put("state", "generation:0")
                    put("id", "HttpWarp"); put("name", "HTTP Warp"); put("targets", buildJsonArray { add("mesh:art") })
                }
                val warpJob = call(first, "rig_create_warp", warp).getValue("structuredContent").jsonObject.getValue("data").jsonObject.getValue("id")
                val warpDetail = call(first, "workspace_get_operation", buildJsonObject { put("id", "rig_create_warp") })
                    .getValue("structuredContent").jsonObject.getValue("data").jsonObject
                assertTrue(warpDetail.getValue("job_backed").jsonPrimitive.boolean); assertTrue(warpDetail.getValue("batchable").jsonPrimitive.boolean)
                val duplicateWarp = call(first, "rig_create_warp", JsonObject(warp + mapOf("request_id" to JsonPrimitive("duplicate-warp"),
                    "targets" to JsonArray(listOf("mesh:art", "mesh:art").map(::JsonPrimitive))))).getValue("structuredContent").jsonObject
                assertFalse(duplicateWarp.getValue("ok").jsonPrimitive.boolean)
                assertEquals("invalid_request", duplicateWarp.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
                assertEquals(200, send(JsonObject(emptyMap()), first, method = "DELETE").statusCode())
                val second = initialize()
                val retried = call(second, "project_export_model", export).getValue("structuredContent").jsonObject.getValue("data").jsonObject
                assertEquals(job, retried.getValue("id").jsonPrimitive.content)
                val bakeRetry = call(second, "simulation_bake", simulation).getValue("structuredContent").jsonObject.getValue("data").jsonObject
                assertEquals(bakeJob, bakeRetry.getValue("id"))
                val sampleRetry = call(second, "simulation_simulate", sampling).getValue("structuredContent").jsonObject.getValue("data").jsonObject
                assertEquals(sampleJob, sampleRetry.getValue("id"))
                val motionRetry = call(second, "view_sample_motion", motion).getValue("structuredContent").jsonObject.getValue("data").jsonObject
                assertEquals(motionJob, motionRetry.getValue("id"))
                val physicsRetry = call(second, "physics_import", physics).getValue("structuredContent").jsonObject.getValue("data").jsonObject
                assertEquals(physicsJob, physicsRetry.getValue("id"))
                val paintRetry = call(second, "source_paint_pencil", paint).getValue("structuredContent").jsonObject.getValue("data").jsonObject
                assertEquals(paintJob, paintRetry.getValue("id"))
                val restoreRetry = call(second, "layer_restore", restore).getValue("structuredContent").jsonObject.getValue("data").jsonObject
                assertEquals(restoreJob, restoreRetry.getValue("id"))
                assertEquals(generationJob, call(second, "settings_update", generation).getValue("structuredContent").jsonObject.getValue("data").jsonObject.getValue("id"))
                assertEquals(partitionJob, call(second, "source_split_components", partition).getValue("structuredContent").jsonObject.getValue("data").jsonObject.getValue("id"))
                assertEquals(depthJob, call(second, "source_split_depth", depth).getValue("structuredContent").jsonObject.getValue("data").jsonObject.getValue("id"))
                assertEquals(warpJob, call(second, "rig_create_warp", warp).getValue("structuredContent").jsonObject.getValue("data").jsonObject.getValue("id"))
                backend.release.complete(Unit)
                backend.bakeRelease.complete(Unit)
                backend.sampleRelease.countDown()
                backend.motionRelease.complete(Unit)
                backend.physicsRelease.complete(Unit)
                backend.paintRelease.complete(Unit)
                backend.layerRelease.complete(Unit)
                backend.generationRelease.complete(Unit)
                backend.partitionRelease.complete(Unit)
                backend.depthRelease.complete(Unit)
                backend.warpRelease.complete(Unit)
                val completed = call(second, "job_wait", buildJsonObject { put("id", job) })
                    .getValue("structuredContent").jsonObject.getValue("data").jsonObject
                assertEquals("completed", completed.getValue("status").jsonPrimitive.content)
                assertEquals("/out/model.cmo3", completed.getValue("result").jsonObject.getValue("files").jsonArray.single()
                    .jsonObject.getValue("path").jsonPrimitive.content)
                assertEquals(1, backend.exports.get())
                val baked = call(second, "job_wait", buildJsonObject { put("id", bakeJob) }).getValue("structuredContent").jsonObject.getValue("data").jsonObject
                assertEquals("completed", baked.getValue("status").jsonPrimitive.content)
                val bakeResult = baked.getValue("result").jsonObject
                validateOperationSchema(bakeResult, bakeDetail.getValue("job_result_schema").jsonObject)
                assertEquals("SimAxis", bakeResult.getValue("modes").jsonArray.single().jsonObject.getValue("parameter").jsonPrimitive.content)
                assertEquals("generation:1", bakeResult.getValue("state").jsonPrimitive.content)
                assertEquals(1, backend.bakes.get())
                val sampled = call(second, "job_wait", buildJsonObject { put("id", sampleJob) }).getValue("structuredContent").jsonObject.getValue("data").jsonObject
                assertEquals("completed", sampled.getValue("status").jsonPrimitive.content)
                val sampleResult = sampled.getValue("result").jsonObject
                validateOperationSchema(sampleResult, sampleDetail.getValue("job_result_schema").jsonObject)
                assertEquals("generation:0", sampleResult.getValue("state").jsonPrimitive.content)
                assertEquals("revision", sampleResult.getValue("revision").jsonPrimitive.content)
                assertEquals(8, sampleResult.getValue("particles").jsonPrimitive.int)
                assertEquals(1, backend.samples.get())
                val motionCompleted = call(second, "job_wait", buildJsonObject { put("id", motionJob) })
                val motionData = motionCompleted.getValue("structuredContent").jsonObject.getValue("data").jsonObject
                assertEquals("completed", motionData.getValue("status").jsonPrimitive.content)
                val motionResult = motionData.getValue("result").jsonObject
                validateOperationSchema(motionResult, motionDetail.getValue("job_result_schema").jsonObject)
                assertEquals("generation:0", motionResult.getValue("state").jsonPrimitive.content)
                assertEquals("revision", motionResult.getValue("revision").jsonPrimitive.content)
                assertEquals(1, backend.motions.get())
                val physicsCompleted = call(second, "job_wait", buildJsonObject { put("id", physicsJob) })
                    .getValue("structuredContent").jsonObject.getValue("data").jsonObject
                assertEquals("completed", physicsCompleted.getValue("status").jsonPrimitive.content)
                val physicsResult = physicsCompleted.getValue("result").jsonObject
                validateOperationSchema(physicsResult, physicsDetail.getValue("job_result_schema").jsonObject)
                assertEquals(JsonArray(listOf(JsonPrimitive("Pendulum"))), physicsResult.getValue("imported"))
                assertEquals(JsonArray(listOf(JsonPrimitive("Previous"))), physicsResult.getValue("disabled"))
                assertEquals(JsonArray(listOf(JsonPrimitive("MissingAxis"))), physicsResult.getValue("missing_parameters").jsonObject.getValue("Pendulum"))
                assertEquals(30, physicsResult.getValue("fps").jsonPrimitive.int)
                assertEquals(1, backend.physicsImports.get())
                val paintCompleted = call(second, "job_wait", buildJsonObject { put("id", paintJob) })
                    .getValue("structuredContent").jsonObject.getValue("data").jsonObject
                assertEquals("completed", paintCompleted.getValue("status").jsonPrimitive.content)
                val paintResult = paintCompleted.getValue("result").jsonObject
                validateOperationSchema(paintResult, paintDetail.getValue("job_result_schema").jsonObject)
                assertEquals("generation:1", paintResult.getValue("state").jsonPrimitive.content)
                assertEquals("paint-head", paintResult.getValue("history_node_id").jsonPrimitive.content)
                assertEquals(JsonArray(listOf(JsonPrimitive("mesh:NewPaintedMesh"))), paintResult.getValue("changed"))
                assertEquals(1, backend.paints.get())
                val restored = call(second, "job_wait", buildJsonObject { put("id", restoreJob) })
                    .getValue("structuredContent").jsonObject.getValue("data").jsonObject
                assertEquals("completed", restored.getValue("status").jsonPrimitive.content)
                val restoreResult = restored.getValue("result").jsonObject
                validateOperationSchema(restoreResult, restoreDetail.getValue("job_result_schema").jsonObject)
                assertEquals("generation:1", restoreResult.getValue("state").jsonPrimitive.content)
                assertEquals("restored-head", restoreResult.getValue("history_node_id").jsonPrimitive.content)
                assertEquals(JsonArray(listOf(JsonPrimitive("art"))), restoreResult.getValue("affectedLayerIds"))
                assertEquals(1, backend.restores.get())
                val generated = call(second, "job_wait", buildJsonObject { put("id", generationJob) })
                    .getValue("structuredContent").jsonObject.getValue("data").jsonObject
                assertEquals("completed", generated.getValue("status").jsonPrimitive.content)
                val generationResult = generated.getValue("result").jsonObject
                validateOperationSchema(generationResult, generationDetail.getValue("job_result_schema").jsonObject)
                assertEquals("generation:1", generationResult.getValue("state").jsonPrimitive.content)
                assertEquals("generation-head", generationResult.getValue("history_node_id").jsonPrimitive.content)
                assertEquals(1, backend.generations.get())
                val partitioned = call(second, "job_wait", buildJsonObject { put("id", partitionJob) })
                    .getValue("structuredContent").jsonObject.getValue("data").jsonObject
                assertEquals("completed", partitioned.getValue("status").jsonPrimitive.content)
                val partitionResult = partitioned.getValue("result").jsonObject
                validateOperationSchema(partitionResult, partitionDetail.getValue("job_result_schema").jsonObject)
                assertEquals("generation:1", partitionResult.getValue("state").jsonPrimitive.content)
                assertEquals("partition-head", partitionResult.getValue("history_node_id").jsonPrimitive.content)
                assertEquals(JsonArray(listOf("island-a", "island-b").map(::JsonPrimitive)), partitionResult.getValue("layers"))
                assertEquals(1, backend.partitions.get())
                val depthCompleted = call(second, "job_wait", buildJsonObject { put("id", depthJob) }).getValue("structuredContent").jsonObject.getValue("data").jsonObject
                assertEquals("completed", depthCompleted.getValue("status").jsonPrimitive.content)
                val depthResult = depthCompleted.getValue("result").jsonObject
                validateOperationSchema(depthResult, depthDetail.getValue("job_result_schema").jsonObject)
                assertEquals("generation:1", depthResult.getValue("state").jsonPrimitive.content)
                assertEquals("depth-head", depthResult.getValue("history_node_id").jsonPrimitive.content)
                assertEquals(JsonArray(listOf(JsonPrimitive("depth-front"))), depthResult.getValue("layers"))
                assertEquals(1, backend.depthSplits.get())
                val warpCompleted = call(second, "job_wait", buildJsonObject { put("id", warpJob) }).getValue("structuredContent").jsonObject.getValue("data").jsonObject
                assertEquals("completed", warpCompleted.getValue("status").jsonPrimitive.content)
                val warpResult = warpCompleted.getValue("result").jsonObject
                validateOperationSchema(warpResult, warpDetail.getValue("job_result_schema").jsonObject)
                assertEquals("warp:HttpWarp", warpResult.getValue("target").jsonPrimitive.content)
                assertEquals("generation:1", warpResult.getValue("state").jsonPrimitive.content)
                assertEquals("warp-head", warpResult.getValue("history_node_id").jsonPrimitive.content)
                assertEquals(1, backend.warps.get())
                fun image(result: JsonObject) = result.getValue("content").jsonArray.single {
                    it.jsonObject.getValue("type").jsonPrimitive.content == "image"
                }.jsonObject
                assertEquals("image/png", image(motionCompleted).getValue("mimeType").jsonPrimitive.content)
                assertContentEquals(backend.motionImage, java.util.Base64.getDecoder().decode(image(motionCompleted).getValue("data").jsonPrimitive.content))
                val recoveredImage = call(second, "job_get", buildJsonObject { put("id", motionJob) })
                assertEquals(image(motionCompleted), image(recoveredImage))
            }
        }
    }
}
