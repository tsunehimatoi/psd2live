package io.github.psd2live.application

import io.github.psd2live.core.modifier.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.*
import java.nio.file.Path

internal object WorkspacePluginDevSchemas {
    private val s = WorkspaceResultSchema
    val jobs = setOf("plugin_dev_scaffold", "plugin_dev_build", "modifier_code_apply")

    private val reference = s.obj(
        mapOf(
            "id" to s.handle(),
            "version" to s.handle(),
            "digest" to s.handle(),
            "dependencyDigests" to s.dictionary(s.handle()),
        ),
        setOf("id", "version", "digest"),
    )

    private val diagnostic = s.obj(
        mapOf(
            "code" to s.handle(),
            "severity" to s.handle(),
            "message" to s.string(),
            "instance_ids" to s.array(s.handle()),
            "package_ids" to s.array(s.handle()),
            "details" to s.dictionary(JsonObject(emptyMap())),
        ),
        setOf("code", "severity", "message"),
    )

    private val source = s.obj(
        mapOf(
            "source_id" to s.handle(),
            "source" to s.handle(),
            "mode" to s.choices("managed_build", "built_package"),
            "profile_id" to s.nullable(s.handle()),
            "status" to s.handle(),
            "package_ids" to s.array(s.handle()),
        ),
    )

    private val artifact = s.obj(
        mapOf(
            "artifact_id" to s.handle(),
            "source_id" to s.handle(),
            "build_digest" to s.handle(),
            "retention" to s.choices("candidate", "adopted"),
            "expires_at" to s.nullable(s.integer(0)),
            "directory" to s.handle(),
            "packages" to s.array(reference),
        ),
    )

    private val contractResult = s.obj(
        mapOf(
            "host_api_version" to s.handle(),
            "protocol" to s.obj(mapOf("min" to s.integer(1), "max" to s.integer(1), "default" to s.integer(1))),
            "context_version" to s.obj(mapOf("min" to s.integer(1), "max" to s.integer(1))),
            "targets" to s.array(s.handle()),
            "components" to s.array(s.handle()),
            "derived_output_kinds" to s.array(s.handle()),
            "config_schema_keywords" to s.array(s.handle()),
            "ui_field_types" to s.array(s.handle()),
            "creates_modifies_rules" to s.obj(mapOf("wildcard_forbidden" to s.boolean(), "max_length" to s.integer(1), "no_iso_control" to s.boolean())),
            "output_id_rules" to s.obj(mapOf("format" to s.handle(), "supports_output_aliases" to s.boolean(), "deterministic_across_replays" to s.boolean())),
            "build_profiles" to s.array(s.obj(mapOf("id" to s.handle(), "task" to s.handle(), "sdk_property" to s.handle(), "version_property" to s.handle()))),
            "environment" to s.dictionary(JsonObject(emptyMap())),
            "minimal_example" to s.dictionary(JsonObject(emptyMap())),
        ),
        setOf(
            "host_api_version", "protocol", "context_version", "targets", "components",
            "derived_output_kinds", "config_schema_keywords", "ui_field_types",
            "creates_modifies_rules", "output_id_rules", "build_profiles", "environment",
        ),
    )
    private val scaffoldResult = s.obj(
        mapOf(
            "directory" to s.handle(),
            "plugin_id" to s.handle(),
            "algorithm_type" to s.handle(),
            "base_version" to s.handle(),
            "files" to s.array(s.handle()),
        ),
    )
    private val listResult = s.obj(mapOf("sources" to s.array(source), "artifacts" to s.array(artifact)))
    private val buildResult = s.obj(
        mapOf(
            "artifact" to artifact,
            "unchanged" to s.boolean(),
            "diagnostics" to s.array(diagnostic),
            "log_ref" to s.nullable(s.handle()),
        ),
    )
    private val detachResult = s.obj(mapOf("source_id" to s.handle(), "detached" to s.boolean()))
    private val codePlanResult = s.obj(
        mapOf(
            "plan_token" to s.handle(),
            "expires_at" to s.integer(0),
            "state" to s.handle(),
            "changes" to s.array(s.dictionary(JsonObject(emptyMap()))),
            "dependency_changes" to s.array(s.dictionary(JsonObject(emptyMap()))),
            "replayed_instance_ids" to s.array(s.handle()),
            "blockers" to s.array(diagnostic),
            "warnings" to s.array(diagnostic),
            "can_apply" to s.boolean(),
        ),
    )
    private val codeApplyResult = s.obj(
        mapOf(
            "applied" to s.boolean(),
            "project_id" to s.handle(),
            "state_before" to s.handle(),
            "state" to s.handle(),
            "revision" to s.handle(),
            "history_node_id" to s.handle(),
            "changes" to s.array(s.dictionary(JsonObject(emptyMap()))),
            "observation" to s.dictionary(JsonObject(emptyMap())),
            "presentation" to s.dictionary(JsonObject(emptyMap())),
        ),
    )

    fun request(id: String): JsonObject = when (id) {
        "plugin_dev_contract" -> s.obj(mapOf("include_example" to s.boolean()), emptySet())
        "plugin_dev_scaffold" -> s.obj(
            mapOf(
                "target_dir" to s.handle(),
                "plugin_id" to s.handle(),
                "name" to s.handle(),
                "algorithm_type" to s.handle(),
                "algorithm_name" to s.handle(),
                "base_version" to s.handle(),
                "targets" to s.array(s.choices("mesh", "deformer", "part", "layer"), 1, 16),
                "package_name" to s.handle(),
            ),
            setOf("target_dir", "plugin_id", "name"),
        )
        "plugin_dev_list" -> s.obj(mapOf("source_id" to s.handle()), emptySet())
        "plugin_dev_register" -> s.obj(
            mapOf(
                "source" to s.handle(),
                "mode" to s.choices("managed_build", "built_package"),
                "profile_id" to s.handle(),
                "source_id" to s.handle(),
            ),
            setOf("source", "mode"),
        )
        "plugin_dev_build" -> s.obj(
            mapOf(
                "source_id" to s.handle(),
                "ttl_millis" to s.integer(1000),
                "timeout_millis" to s.integer(1000),
                "dependency_lock" to s.array(reference),
            ),
            setOf("source_id"),
        )
        "plugin_dev_detach" -> s.obj(mapOf("source_id" to s.handle()), setOf("source_id"))
        "modifier_code_plan" -> s.obj(
            mapOf("artifact_id" to s.handle(), "instance_ids" to s.array(s.handle(), 0, 128)),
            setOf("artifact_id"),
        )
        "modifier_code_apply" -> s.obj(
            mapOf("state" to s.handle(), "plan_token" to s.handle(), "request_id" to s.handle()),
            setOf("state", "plan_token", "request_id"),
        )
        else -> error("Unknown plugin dev operation: $id")
    }

    fun result(id: String): JsonObject = when (id) {
        "plugin_dev_contract" -> contractResult
        "plugin_dev_scaffold" -> scaffoldResult
        "plugin_dev_list" -> listResult
        "plugin_dev_register" -> source
        "plugin_dev_build" -> buildResult
        "plugin_dev_detach" -> detachResult
        "modifier_code_plan" -> codePlanResult
        "modifier_code_apply" -> codeApplyResult
        else -> error("Unknown plugin dev operation: $id")
    }

    internal fun encodeSource(item: PluginDevSource): JsonObject = buildJsonObject {
        put("source_id", item.sourceId)
        put("source", item.source.toString())
        put("mode", item.mode.name.lowercase())
        put("profile_id", item.profileId?.let(::JsonPrimitive) ?: JsonNull)
        put("status", item.status)
        putJsonArray("package_ids") { item.packageIds.forEach { add(it) } }
    }

    internal fun encodeArtifact(item: PluginDevArtifact): JsonObject = buildJsonObject {
        put("artifact_id", item.artifactId)
        put("source_id", item.sourceId)
        put("build_digest", item.buildDigest)
        put("retention", item.retention.name.lowercase())
        put("expires_at", item.expiresAt?.let(::JsonPrimitive) ?: JsonNull)
        put("directory", item.directory.toString())
        put("packages", Json.encodeToJsonElement(item.packages))
    }

    internal fun encodeDiagnostic(item: PluginDevDiagnostic): JsonObject = buildJsonObject {
        put("code", item.code)
        put("severity", item.severity)
        put("message", item.message)
        putJsonArray("instance_ids") { item.instanceIds.forEach { add(it) } }
        putJsonArray("package_ids") { item.packageIds.forEach { add(it) } }
        put("details", item.details)
    }
}

internal fun registerPluginDevOperations(
    registry: WorkspaceOperationRegistry,
    workspace: WorkspaceBackend,
    jobs: WorkspaceJobs,
) {
    registry.register(
        WorkspaceOperationDefinition(
            id = "plugin_dev_contract",
            description = "Discover host plugin API/protocol contracts, supported schema keywords, outputId rules, environment readiness, and a minimal runnable Kotlin worker example.",
            requestSchema = WorkspacePluginDevSchemas.request("plugin_dev_contract"),
            kind = WorkspaceOperationKind.QUERY,
            workspaceBound = false,
            jobBacked = false,
            resultSchema = WorkspacePluginDevSchemas.result("plugin_dev_contract"),
        ),
    ) { request, _ ->
        val includeExample = request["include_example"]?.jsonPrimitive?.boolean ?: true
        WorkspaceOperationOutput(PluginDevContracts.describe(includeExample = includeExample))
    }

    registry.register(
        WorkspaceOperationDefinition(
            id = "plugin_dev_scaffold",
            description = "Generate a standalone Gradle/Kotlin custom modifier plugin project in an empty or new target directory. Runs in a background job.",
            requestSchema = WorkspacePluginDevSchemas.request("plugin_dev_scaffold"),
            kind = WorkspaceOperationKind.SESSION,
            workspaceBound = false,
            jobBacked = true,
            resultSchema = requireNotNull(WorkspaceJobResultSchemas.operationOutput("plugin_dev_scaffold")),
            jobResultSchema = WorkspacePluginDevSchemas.result("plugin_dev_scaffold"),
        ),
    ) { request, _ ->
        val job = jobs.start("plugin_dev_scaffold", null, "plugin_dev", WorkspacePluginDevSchemas.result("plugin_dev_scaffold")) {
            val completion = kotlin.coroutines.coroutineContext[WorkspaceJobCompletion]
            runInterruptible(Dispatchers.IO) {
                val targetDir = Path.of(request.getValue("target_dir").jsonPrimitive.content)
                val pluginId = request.getValue("plugin_id").jsonPrimitive.content
                val name = request.getValue("name").jsonPrimitive.content
                val algorithmType = request["algorithm_type"]?.jsonPrimitive?.content ?: "$pluginId/custom"
                val algorithmName = request["algorithm_name"]?.jsonPrimitive?.content ?: name
                val baseVersion = request["base_version"]?.jsonPrimitive?.content ?: "0.1.0"
                val targets = request["targets"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet() ?: setOf("mesh")
                val packageName = request["package_name"]?.jsonPrimitive?.content
                val scaffoldRequest = if (packageName != null) {
                    PluginDevScaffoldRequest(targetDir, pluginId, name, algorithmType, algorithmName, baseVersion, targets, packageName)
                } else {
                    PluginDevScaffoldRequest(targetDir, pluginId, name, algorithmType, algorithmName, baseVersion, targets)
                }
                val created = PluginDevScaffold.scaffold(scaffoldRequest)
                val result = WorkspaceOperationOutput(buildJsonObject {
                    put("directory", created.directory.toString())
                    put("plugin_id", created.pluginId)
                    put("algorithm_type", created.algorithmType)
                    put("base_version", created.baseVersion)
                    putJsonArray("files") { created.files.forEach { add(it.toString()) } }
                })
                completion?.committed(result)
                result
            }
        }
        WorkspaceOperationOutput(job.toJson())
    }

    registry.register(
        WorkspaceOperationDefinition(
            id = "plugin_dev_list",
            description = "List registered plugin dev sources and retained candidate/adopted dev artifacts.",
            requestSchema = WorkspacePluginDevSchemas.request("plugin_dev_list"),
            kind = WorkspaceOperationKind.QUERY,
            workspaceBound = false,
            jobBacked = false,
            resultSchema = WorkspacePluginDevSchemas.result("plugin_dev_list"),
        ),
    ) { request, _ ->
        val sourceId = request["source_id"]?.jsonPrimitive?.content
        val sources = PluginDevSourceStore.shared.list().let { all ->
            if (sourceId == null) all else all.filter { it.sourceId == sourceId }
        }
        val artifacts = PluginDevArtifactStore.list(sourceId)
        WorkspaceOperationOutput(buildJsonObject {
            putJsonArray("sources") { sources.forEach { add(WorkspacePluginDevSchemas.encodeSource(it)) } }
            putJsonArray("artifacts") { artifacts.forEach { add(WorkspacePluginDevSchemas.encodeArtifact(it)) } }
        })
    }

    registry.register(
        WorkspaceOperationDefinition(
            id = "plugin_dev_register",
            description = "Register a local plugin dev source directory in managed_build or built_package mode.",
            requestSchema = WorkspacePluginDevSchemas.request("plugin_dev_register"),
            kind = WorkspaceOperationKind.SESSION,
            workspaceBound = false,
            jobBacked = false,
            resultSchema = WorkspacePluginDevSchemas.result("plugin_dev_register"),
        ),
    ) { request, _ ->
        val source = Path.of(request.getValue("source").jsonPrimitive.content)
        val mode = PluginDevSourceMode.valueOf(request.getValue("mode").jsonPrimitive.content.uppercase())
        val profileId = request["profile_id"]?.jsonPrimitive?.content
        val sourceId = request["source_id"]?.jsonPrimitive?.content
        val registered = PluginDevSourceStore.shared.register(source, mode, profileId, sourceId)
        WorkspaceOperationOutput(WorkspacePluginDevSchemas.encodeSource(registered))
    }

    registry.register(
        WorkspaceOperationDefinition(
            id = "plugin_dev_build",
            description = "Build or ingest a registered plugin dev source into a sealed candidate dev artifact. Runs in a background job.",
            requestSchema = WorkspacePluginDevSchemas.request("plugin_dev_build"),
            kind = WorkspaceOperationKind.SESSION,
            workspaceBound = false,
            jobBacked = true,
            resultSchema = requireNotNull(WorkspaceJobResultSchemas.operationOutput("plugin_dev_build")),
            jobResultSchema = WorkspacePluginDevSchemas.result("plugin_dev_build"),
        ),
    ) { request, _ ->
        val sourceId = request.getValue("source_id").jsonPrimitive.content
        val ttlMillis = request["ttl_millis"]?.jsonPrimitive?.long
        val timeoutMillis = request["timeout_millis"]?.jsonPrimitive?.long
        val dependencyLock = request["dependency_lock"]?.jsonArray?.map {
            Json.decodeFromJsonElement(ModifierPluginReference.serializer(), it)
        } ?: emptyList()
        val job = jobs.start("plugin_dev_build", null, "plugin_dev", WorkspacePluginDevSchemas.result("plugin_dev_build")) {
            val completion = kotlin.coroutines.coroutineContext[WorkspaceJobCompletion]
            runInterruptible(Dispatchers.IO) {
                val built = when {
                    ttlMillis != null && timeoutMillis != null -> PluginDevBuilder.build(
                        sourceId = sourceId,
                        ttlMillis = ttlMillis,
                        timeoutMillis = timeoutMillis,
                        dependencyLock = dependencyLock,
                    )
                    ttlMillis != null -> PluginDevBuilder.build(
                        sourceId = sourceId,
                        ttlMillis = ttlMillis,
                        dependencyLock = dependencyLock,
                    )
                    timeoutMillis != null -> PluginDevBuilder.build(
                        sourceId = sourceId,
                        timeoutMillis = timeoutMillis,
                        dependencyLock = dependencyLock,
                    )
                    else -> PluginDevBuilder.build(
                        sourceId = sourceId,
                        dependencyLock = dependencyLock,
                    )
                }
                val result = WorkspaceOperationOutput(buildJsonObject {
                    put("artifact", WorkspacePluginDevSchemas.encodeArtifact(built.artifact))
                    put("unchanged", built.unchanged)
                    putJsonArray("diagnostics") { built.diagnostics.forEach { add(WorkspacePluginDevSchemas.encodeDiagnostic(it)) } }
                    put("log_ref", built.logRef?.let(::JsonPrimitive) ?: JsonNull)
                })
                completion?.committed(result)
                result
            }
        }
        WorkspaceOperationOutput(job.toJson())
    }

    registry.register(
        WorkspaceOperationDefinition(
            id = "plugin_dev_detach",
            description = "Detach a registered plugin dev source. Already-adopted dev artifacts remain intact for project history.",
            requestSchema = WorkspacePluginDevSchemas.request("plugin_dev_detach"),
            kind = WorkspaceOperationKind.SESSION,
            workspaceBound = false,
            jobBacked = false,
            destructive = true,
            resultSchema = WorkspacePluginDevSchemas.result("plugin_dev_detach"),
        ),
    ) { request, _ ->
        val sourceId = request.getValue("source_id").jsonPrimitive.content
        val detached = PluginDevSourceStore.shared.detach(sourceId)
        WorkspaceOperationOutput(buildJsonObject {
            put("source_id", sourceId)
            put("detached", detached)
        })
    }

    registry.register(
        WorkspaceOperationDefinition(
            id = "modifier_code_plan",
            description = "Plan an undoable package swap onto a candidate dev artifact for selected or matching modifier instances. Returns a signed plan_token and compatibility diagnostics.",
            requestSchema = WorkspacePluginDevSchemas.request("modifier_code_plan"),
            kind = WorkspaceOperationKind.QUERY,
            workspaceBound = true,
            jobBacked = false,
            resultSchema = WorkspacePluginDevSchemas.result("modifier_code_plan"),
        ),
    ) { request, _ ->
        val artifactId = request.getValue("artifact_id").jsonPrimitive.content
        val instanceIds = request["instance_ids"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
        WorkspaceOperationOutput(WorkspaceModifierCodeSwap.plan(workspace, artifactId, instanceIds))
    }

    registry.register(
        WorkspaceOperationDefinition(
            id = "modifier_code_apply",
            description = "Apply a planned modifier code swap atomically in one history node after isolated candidate evaluation. Runs in a background job.",
            requestSchema = WorkspacePluginDevSchemas.request("modifier_code_apply"),
            kind = WorkspaceOperationKind.DOCUMENT,
            workspaceBound = true,
            jobBacked = true,
            resultSchema = requireNotNull(WorkspaceJobResultSchemas.operationOutput("modifier_code_apply")),
            jobResultSchema = WorkspacePluginDevSchemas.result("modifier_code_apply"),
        ),
    ) { request, _ ->
        val state = request.getValue("state").jsonPrimitive.content
        val planToken = request.getValue("plan_token").jsonPrimitive.content
        val requestId = request.getValue("request_id").jsonPrimitive.content
        val job = jobs.start("modifier_code_apply", state, "modifier", WorkspacePluginDevSchemas.result("modifier_code_apply")) {
            val completion = kotlin.coroutines.coroutineContext[WorkspaceJobCompletion]
            val applied = WorkspaceModifierCodeSwap.apply(workspace, state, planToken, requestId)
            val result = WorkspaceOperationOutput(applied)
            completion?.committed(result)
            result
        }
        WorkspaceOperationOutput(job.toJson())
    }
}
