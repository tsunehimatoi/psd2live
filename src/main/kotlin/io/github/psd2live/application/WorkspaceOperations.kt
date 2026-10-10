package io.github.psd2live.application

import io.github.psd2live.application.WorkspaceBackend
import kotlinx.serialization.json.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

/** Process-owned operations and jobs are shared by all desktop MCP sessions. */
internal class WorkspaceOperations(private val workspace: WorkspaceBackend,
                                   presetLibrary: WorkspacePhysicsPresetLibrary = WorkspacePhysicsPresetLibrary.shared) : AutoCloseable {
    private val requests = WorkspaceRequestExecutor(workspace)
    val registry = WorkspaceOperationRegistry(requests)
    private val jobs = WorkspaceJobs()

    init {
        val commands = WorkspaceCommands(workspace)
        registerAuthoringOperations(registry, workspace, commands, jobs)
        registerCanvasWeightOperations(registry, workspace)
        registerCanvasDeformOperations(registry, workspace)
        registerDrawOrderOperations(registry, workspace)
        registerWarpControlOperations(registry, workspace, jobs)
        registerTextureOperations(registry, workspace, workspace, jobs)
        registerPhysicsPresetApplyOperation(registry, workspace)
        registerPaintSessionOperations(registry, workspace, workspace, jobs)
        registerSkeletonDraftOperations(registry, workspace)
        registerSimulationPreviewOperations(registry, workspace, workspace, jobs)
        registerPhysicsAuditionOperations(registry, workspace)
        registerDocumentBatch(registry, workspace, workspace, jobs)
        registerIntentOperations(registry, workspace, workspace, jobs)
        registerAuxiliaryOperations(registry, workspace, workspace)
        registerCanvasVisibilityOperations(registry, workspace)
        registerPhysicsPresetLibraryOperations(registry, presetLibrary)
        for ((id, commandId) in mapOf("project_import_psd" to "asset_import_psd", "project_create_artwork" to "asset_create_artwork")) {
            val command = commands.get(commandId)
            register(id, "Create a new source project from ${if (id == "project_import_psd") "an absolute PSD path" else "placed local raster layers"}. Unsaved changes are rejected unless discard_unsaved=true. Source reading, isolated rebuilding and installation run in a process-owned job; use job_wait/job_get for the committed project and state.",
                schema(command.schema.properties.mapValues { it.value.jsonObject }, command.schema.required),
                WorkspaceOperationKind.PROJECT, jobBacked = true) { request ->
                start(id, allowUnloaded = true) { commands.invoke(commandId, request).data }
            }
        }
        register("project_import_cmo3", "Import an authored CMO3 file from an absolute local path. mode=new creates a new project and rejects unsaved changes unless discard_unsaved=true; mode=replace updates matching object IDs and retains absent objects in the current project as one history step. Imported content does not run PSD rig or motion presets. Returns a process-owned job handle.",
            schema(mapOf("path" to string(), "mode" to buildJsonObject {
                put("type", "string"); put("enum", JsonArray(listOf("new", "replace").map(::JsonPrimitive)))
            }, "discard_unsaved" to buildJsonObject { put("type", "boolean") }), listOf("path", "mode")),
            WorkspaceOperationKind.PROJECT, jobBacked = true) { request ->
            val path = absolutePath(request.text("path"))
            val mode = io.github.psd2live.core.Cmo3ImportMode.valueOf(request.text("mode").uppercase())
            start("project_import_cmo3", allowUnloaded = true) {
                workspace.importCmo3(path, mode, request["discard_unsaved"]?.jsonPrimitive?.boolean ?: false).lifecycleResult()
            }
        }
        register("project_save", "Save the complete portable project to its current archive. For the first save use project_save_as. Returns a job handle; the result contains the captured state and history node.",
            schema(emptyMap()), WorkspaceOperationKind.OUTPUT, jobBacked = true) {
            start("project_save") { workspace.saveProject().lifecycleResult() }
        }
        workspace.let { lifecycle ->
            register("project_save_as", "Save the complete portable project to an absolute archive path and select it as the current save location. Returns a job handle; unchanged documents add no history node.",
                schema(mapOf("path" to string()), listOf("path")), WorkspaceOperationKind.OUTPUT, jobBacked = true) { request ->
                val path = absolutePath(request.text("path"))
                start("project_save_as") { lifecycle.saveProjectAt(path).lifecycleResult() }
            }
            register("project_open", "Open a portable project archive from an absolute path. Unsaved changes are rejected by default: save first, or explicitly set discard_unsaved=true. No GUI dialog is opened. Returns a job handle and, on success, the newly loaded project and opaque state.",
                schema(mapOf("path" to string(), "discard_unsaved" to buildJsonObject { put("type", "boolean") }), listOf("path")),
                WorkspaceOperationKind.PROJECT, jobBacked = true) { request ->
                val path = absolutePath(request.text("path"))
                start("project_open", allowUnloaded = true) {
                    lifecycle.openProjectAt(path, request["discard_unsaved"]?.jsonPrimitive?.boolean ?: false).lifecycleResult()
                }
            }
        }
        register("workspace_list_operations", "Discover supported operations and their business kind. Filter by domain, kind or query words and page; items carry the first sentence of each description, and workspace_get_operation returns the whole description with the exact schema.",
            schema(mapOf("domain" to string(), "kind" to buildJsonObject {
                put("type", "string"); put("enum", JsonArray(WorkspaceOperationKind.entries.map { JsonPrimitive(it.name.lowercase()) }))
            }, "query" to buildJsonObject {
                put("type", "string"); put("minLength", 1)
                put("description", "Words that must all occur in the operation ID or description, ignoring case.")
            }, "offset" to integer(0, Int.MAX_VALUE), "limit" to integer(1, 64))), WorkspaceOperationKind.QUERY) { request ->
            val domain = request["domain"]?.jsonPrimitive?.content
            val kind = request["kind"]?.jsonPrimitive?.content
            val words = request["query"]?.jsonPrimitive?.content?.lowercase()?.split(Regex("[\\s_]+"))?.filter(String::isNotEmpty).orEmpty()
            val matching = registry.definitions().filter { (domain == null || it.id.startsWith("${domain}_")) &&
                (kind == null || it.kind.name.lowercase() == kind) &&
                words.all { word -> word in it.id.replace('_', ' ') || word in it.description.lowercase() } }
            val offset = request["offset"]?.jsonPrimitive?.int ?: 0
            val limit = request["limit"]?.jsonPrimitive?.int ?: 24
            WorkspaceOperationOutput(buildJsonObject {
                put("items", JsonArray(matching.drop(offset).take(limit).map { definition ->
                    // Operations of one family share a long description; a page repeats only its opening.
                    JsonObject(definition.toJson() + ("description" to JsonPrimitive(firstSentence(definition.description))))
                }))
                put("total", matching.size)
                if (offset.toLong() + limit < matching.size) put("next", offset + limit)
            })
        }
        register("workspace_get_operation", "Read one operation's exact request schema, field help, business kind and execution metadata.",
            schema(mapOf("id" to string()), listOf("id")), WorkspaceOperationKind.QUERY) { request ->
            WorkspaceOperationOutput(registry.definition(request.text("id")).toJson(includeSchema = true))
        }
        register("source_sample_color", "Sample one source layer in canvas pixels, returning RGBA bytes. Pixels outside the layer are transparent. This query does not change the pose, source, or history.",
            schema(mapOf("layer_id" to string(), "x" to integer(0, Int.MAX_VALUE), "y" to integer(0, Int.MAX_VALUE)), listOf("layer_id", "x", "y")),
            WorkspaceOperationKind.QUERY) { request ->
            WorkspaceOperationOutput(buildJsonObject {
                put("layer_id", request.getValue("layer_id"))
                put("rgba", JsonArray(workspace.sampleSourceColor(request.text("layer_id"),
                    request.getValue("x").jsonPrimitive.int, request.getValue("y").jsonPrimitive.int).map(::JsonPrimitive)))
            })
        }
        register("project_export_model", "Export the model file family to an absolute output directory. Returns a job handle; use job_wait or job_get for the written files and warnings.",
            schema(mapOf("request_id" to string(), "state" to string(), "output_directory" to string()), listOf("request_id", "state", "output_directory")),
            WorkspaceOperationKind.OUTPUT, jobBacked = true) { request ->
            start("project_export_model") {
                workspace.exportModel(request.text("state"), request.text("output_directory"))
            }
        }
        val exportTargets = io.github.psd2live.core.ExportService.registry(io.github.psd2live.core.PipelineConfig()).targets
            .filterNot { it.id in io.github.psd2live.core.ExportService.experimental }
        val targets = exportTargets.map { it.id }
        // Each target's keys as `key=<values> (default)`, so an agent need not find the CLI to learn them.
        val targetSettings = exportTargets.filter { it.settings.isNotEmpty() }.joinToString("; ") { target ->
            "${target.id}: " + target.settings.joinToString(", ") { io.github.psd2live.ExportCli.usage(it) }
        }
        register("project_export_target", "Export the committed model through one export target into an absolute output directory, as File > Export as does: " +
            "${targets.joinToString(", ")}. settings are the target's own keys (listed on the settings field); unset keys take the target's default " +
            "or the project's export settings. Writes the files and <name>.<target>.report.json; returns a job handle whose result lists the files and what the target could not carry (losses).",
            schema(mapOf("request_id" to string(), "state" to string(), "target" to buildJsonObject {
                put("type", "string"); put("enum", JsonArray(targets.map(::JsonPrimitive)))
            }, "output_directory" to string(), "settings" to buildJsonObject {
                put("type", "object"); put("additionalProperties", string())
                put("description", "Values are strings. Keys per target: $targetSettings")
            }), listOf("request_id", "state", "target", "output_directory")),
            WorkspaceOperationKind.OUTPUT, jobBacked = true) { request ->
            val directory = absolutePath(request.text("output_directory"))
            start("project_export_target") {
                workspace.exportTarget(request.text("state"), request.text("target"), directory.toString(),
                    request["settings"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }.orEmpty())
            }
        }
        register("project_export_psd", "Export editable source layers to an absolute PSD path. Returns a job handle. Scale 2 or 4 uses the project's configured texture upscale models.",
            schema(mapOf("request_id" to string(), "state" to string(), "path" to string(), "scale" to buildJsonObject {
                put("type", "integer"); put("enum", JsonArray(listOf(1, 2, 4).map(::JsonPrimitive)))
            }, "include_generated_layers" to buildJsonObject { put("type", "boolean") }), listOf("request_id", "state", "path")),
            WorkspaceOperationKind.OUTPUT, jobBacked = true) { request ->
            start("project_export_psd") {
                workspace.exportPsd(request.text("state"), request.text("path"),
                    request["scale"]?.jsonPrimitive?.int ?: 1,
                    request["include_generated_layers"]?.jsonPrimitive?.boolean ?: true)
            }
        }
        register("job_get", "Read a process-local job and its terminal result. Failed jobs retain error.code/message and applicable conflict or edit details, using the same contract as immediate errors. Jobs survive client disconnection and are lost when the application exits.",
            schema(mapOf("id" to string()), listOf("id")), WorkspaceOperationKind.QUERY) { request ->
            output(jobs.get(request.text("id")))
        }
        register("job_wait", "Wait up to 30000 ms for a job to finish, then return its current status and terminal result. Disconnecting this wait does not cancel the job.",
            schema(mapOf("id" to string(), "timeout_ms" to buildJsonObject {
                put("type", "integer"); put("minimum", 0); put("maximum", 30000)
            }), listOf("id")), WorkspaceOperationKind.QUERY) { request ->
            output(jobs.wait(request.text("id"), request["timeout_ms"]?.jsonPrimitive?.long ?: 30000))
        }
        register("job_cancel", "Request cancellation of a running job. A completed commit stays completed; inspect the returned terminal status before retrying.",
            schema(mapOf("id" to string()), listOf("id")), WorkspaceOperationKind.SESSION, workspaceBound = false) { request ->
            output(jobs.cancel(request.text("id")))
        }
        register("job_list", "List process-local jobs, optionally filtered by project ID. Results are paged in creation order.",
            schema(mapOf("project_id" to string(), "offset" to integer(0, Int.MAX_VALUE), "limit" to integer(1, 64))),
            WorkspaceOperationKind.QUERY) { request ->
            val matching = jobs.list(request["project_id"]?.jsonPrimitive?.content)
            val offset = request["offset"]?.jsonPrimitive?.int ?: 0
            val limit = request["limit"]?.jsonPrimitive?.int ?: 24
            WorkspaceOperationOutput(buildJsonObject {
                put("items", JsonArray(matching.drop(offset).take(limit).map { it.toJson(includeResult = false) }))
                put("total", matching.size)
                if (offset.toLong() + limit < matching.size) put("next", offset + limit)
            })
        }
    }

    private fun register(id: String, description: String, schema: JsonObject, kind: WorkspaceOperationKind,
                         jobBacked: Boolean = false, workspaceBound: Boolean = kind != WorkspaceOperationKind.QUERY, execute: suspend (JsonObject) -> WorkspaceOperationOutput) {
        registry.register(WorkspaceOperationDefinition(id, description, schema, kind, jobBacked = jobBacked, workspaceBound = workspaceBound,
            resultSchema = requireNotNull(WorkspaceJobResultSchemas.operationOutput(id) ?: WorkspaceCapabilityResultSchemas.forOperation(id)) { "No result contract: $id" },
            jobResultSchema = if (jobBacked) WorkspaceJobResultSchemas.result(id) else null)) { request, _ -> execute(request) }
    }

    private suspend fun start(operation: String, allowUnloaded: Boolean = false, action: suspend () -> JsonObject): WorkspaceOperationOutput =
        startWorkspaceOperationJob(workspace, jobs, operation, allowUnloaded) { WorkspaceOperationOutput(action()) }

    override fun close() { requests.close(); jobs.close() }

    private fun absolutePath(value: String): java.nio.file.Path = java.nio.file.Path.of(value).also {
        require(it.isAbsolute) { "Provide an absolute local path" }
    }
    private fun output(job: WorkspaceJobSnapshot) = WorkspaceOperationOutput(job.toJson(), job.result?.images.orEmpty())
    private fun firstSentence(text: String): String {
        val end = Regex("\\. ").find(text)?.range?.first?.plus(1) ?: text.length
        return text.take(minOf(end, 240)).trim()
    }
    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
    private fun string() = buildJsonObject { put("type", "string"); put("minLength", 1) }
    private fun integer(min: Int, max: Int) = buildJsonObject { put("type", "integer"); put("minimum", min); put("maximum", max) }
    private fun schema(fields: Map<String, JsonObject>, required: List<String> = emptyList()) = buildJsonObject {
        put("type", "object"); put("properties", JsonObject(fields)); put("additionalProperties", false)
        put("required", JsonArray(required.map(::JsonPrimitive)))
    }
}

internal fun WorkspaceJobSnapshot.toJson(includeResult: Boolean = true): JsonObject = buildJsonObject {
    put("id", id); put("operation", operation); projectId?.let { put("project_id", it) }
    put("input_state", inputState); put("status", status.name.lowercase()); put("terminal", status.terminal)
    put("progress", progress); put("message", message)
    put("created_at", createdAt.toString()); put("updated_at", updatedAt.toString())
    if (includeResult) result?.let { put("result", it.data) }
    error?.let { put("error", it.toJson()) }
}
