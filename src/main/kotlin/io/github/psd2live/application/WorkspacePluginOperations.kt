package io.github.psd2live.application

import io.github.psd2live.core.modifier.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.*

/** Shared GUI/MCP service. Package installation is process state, not an edit of the current document. */
internal object WorkspacePluginManagement {
    fun execute(operation: String, request: JsonObject, store: ModifierPluginStore = ModifierPluginStore.shared): JsonObject {
        if (operation == "plugin_inspect") return buildJsonObject { put("plugins", JsonArray(listOf(describe(store.inspect(request.getValue("source").jsonPrimitive.content, request["ref"]?.jsonPrimitive?.content))))); put("problems", JsonArray(store.problems().map(::JsonPrimitive))) }
        when (operation) {
            "plugin_install" -> store.install(request.getValue("source").jsonPrimitive.content, request["ref"]?.jsonPrimitive?.content)
            "plugin_enable", "plugin_disable", "plugin_uninstall" -> {
                val id = request.getValue("id").jsonPrimitive.content
                if (id == "builtin") {
                    require(operation != "plugin_uninstall") { "Bundled plugins can be disabled but cannot be deleted" }
                    store.setBuiltinsEnabled(operation == "plugin_enable")
                } else {
                    val reference = store.snapshot().singleOrNull { it.reference.id == id && it.reference.digest == request.getValue("digest").jsonPrimitive.content }?.reference
                        ?: error("Installed plugin was not found")
                    if (operation == "plugin_uninstall") store.uninstall(reference) else store.setEnabled(reference, operation == "plugin_enable")
                }
            }
            "plugin_list" -> Unit
            else -> error("Unknown plugin operation: $operation")
        }
        return buildJsonObject {
            put("problems", JsonArray(store.problems().map(::JsonPrimitive)))
            putJsonArray("plugins") {
            addJsonObject {
                put("id", "builtin"); put("version", "bundled"); put("digest", "builtin"); put("name", "PSD2Live")
                put("enabled", store.builtinsEnabled()); put("builtin", true); put("git_commit", JsonNull)
                putJsonArray("algorithms") { RigModifierAlgorithms.registry.definitions.filter { it.pluginReference == null }.forEach {
                    addJsonObject {
                        put("type", it.type); put("version", it.version); put("name", it.type)
                        put("config_schema", WorkspaceModifierSchemas.configuration(it.type)); put("defaults", JsonObject(emptyMap()))
                        put("requires", JsonArray(it.requires.sorted().map(::JsonPrimitive)))
                        put("targets", JsonArray((if (it.type == "swing") listOf("mesh", "deformer") else listOf("mesh")).map(::JsonPrimitive)))
                        put("reads", JsonArray(listOf(JsonPrimitive("*")))); put("writes", JsonArray(listOf(JsonPrimitive("*"))))
                        put("exclusive_family", it.exclusiveFamily?.let(::JsonPrimitive) ?: JsonNull)
                    }
                } }
            }
            store.snapshot().forEach { add(describe(it)) }
        } }
    }

    private fun describe(entry: InstalledModifierPlugin) = buildJsonObject {
        put("id", entry.reference.id); put("version", entry.reference.version); put("digest", entry.reference.digest)
        put("name", entry.manifest.name); put("enabled", entry.enabled); put("builtin", false)
        put("git_commit", entry.gitCommit?.let(::JsonPrimitive) ?: JsonNull)
        putJsonArray("algorithms") { entry.manifest.algorithms.forEach { addJsonObject {
            put("type", it.type); put("version", it.version); put("name", it.name)
            put("config_schema", it.configSchema); put("defaults", it.defaults)
            put("requires", JsonArray(it.requires.sorted().map(::JsonPrimitive)))
            put("targets", JsonArray(it.targets.sorted().map(::JsonPrimitive)))
            put("reads", JsonArray(it.reads.sorted().map(::JsonPrimitive))); put("writes", JsonArray(it.writes.sorted().map(::JsonPrimitive)))
            put("exclusive_family", it.exclusiveFamily?.let(::JsonPrimitive) ?: JsonNull)
        } } }
    }
}

internal object WorkspacePluginSchemas {
    private val s = WorkspaceResultSchema
    val jobs = setOf("plugin_inspect", "plugin_install", "plugin_enable", "plugin_disable", "plugin_uninstall")
    private val algorithm = s.obj(mapOf(
        "type" to s.handle(), "version" to s.integer(1), "name" to s.handle(),
        "config_schema" to s.dictionary(JsonObject(emptyMap())), "defaults" to s.dictionary(JsonObject(emptyMap())),
        "requires" to s.array(s.handle()), "targets" to s.array(s.handle()), "reads" to s.array(s.handle()), "writes" to s.array(s.handle()),
        "exclusive_family" to s.nullable(s.handle())))
    private val plugin = s.obj(mapOf(
        "id" to s.handle(), "version" to s.handle(), "digest" to s.handle(), "name" to s.handle(),
        "enabled" to s.boolean(), "builtin" to s.boolean(), "git_commit" to s.nullable(s.handle()), "algorithms" to s.array(algorithm)))
    val result = s.obj(mapOf("plugins" to s.array(plugin), "problems" to s.array(s.string())))
    fun request(id: String) = when (id) {
        "plugin_list" -> s.obj(emptyMap())
        "plugin_inspect", "plugin_install" -> s.obj(mapOf("source" to s.handle(), "ref" to s.handle()), setOf("source"))
        else -> s.obj(mapOf("id" to s.handle(), "digest" to s.handle()))
    }
}

internal fun registerPluginOperations(registry: WorkspaceOperationRegistry, jobs: WorkspaceJobs) {
    for (id in WorkspacePluginSchemas.jobs + "plugin_list") {
        val background = id != "plugin_list"
        registry.register(WorkspaceOperationDefinition(id,
            "Manage local algorithm plugins. Install a local directory/ZIP or a Git HTTPS/SSH URL containing a built package; optional ref selects a Git branch, tag or commit. " +
                "Packages are executable local code, not sandboxed. No installation script or source build is run. Uninstall preserves document instances. " +
                "Use id=builtin,digest=builtin to enable/disable bundled algorithms. Other changes require the digest returned by plugin_list.",
            WorkspacePluginSchemas.request(id), if (background) WorkspaceOperationKind.SESSION else WorkspaceOperationKind.QUERY,
            workspaceBound = false, jobBacked = background, destructive = id == "plugin_uninstall",
            resultSchema = if (background) requireNotNull(WorkspaceJobResultSchemas.operationOutput(id)) else WorkspacePluginSchemas.result,
            jobResultSchema = if (background) WorkspacePluginSchemas.result else null)) { request, _ ->
            if (!background) WorkspaceOperationOutput(WorkspacePluginManagement.execute(id, request)) else {
                val job = jobs.start(id, null, "plugins", WorkspacePluginSchemas.result) {
                    val completion = kotlin.coroutines.coroutineContext[WorkspaceJobCompletion]
                    runInterruptible(Dispatchers.IO) {
                        val result = WorkspaceOperationOutput(WorkspacePluginManagement.execute(id, request))
                        completion?.committed(result)
                        result
                    }
                }
                WorkspaceOperationOutput(job.toJson())
            }
        }
    }
}
