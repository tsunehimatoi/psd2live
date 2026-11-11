package io.github.psd2live.application

import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.core.modifier.*
import io.github.psd2live.core.sim.RigSimEdit
import kotlinx.serialization.json.*

internal fun registerModifierOperations(registry: WorkspaceOperationRegistry, workspace: WorkspaceBackend, jobs: WorkspaceJobs) {
    for (id in WorkspaceModifierEdits.supported) registry.register(WorkspaceOperationDefinition(id,
        "Edit explicit modifier content in one atomic history transaction. Add captures stable targets; optional IDs may be fixed before a batch. " +
            "Groups add independent instances with a common origin. Optional index inserts an item explicitly; omission appends. Omitted version selects the highest registered version. Moving changes evaluation order and invalid dependencies fail. " +
            "modifier_organize changes presentation folders and sibling order only; evaluation order is unchanged. " +
            "Apply materializes the complete prefix, preserves the suffix and verifies the final result before committing. " +
            "Convert explicitly migrates a legacy project and commits only if the complete result matches. Other operations require an explicit stack. " +
            "modifier_initialize finishes source import after splitting/naming artwork. Requires a pristine empty modifier stack; modifiers selects the original import mode and preset supplies custom choices only. Existing/applied stacks are rejected. " +
            "modifier_switch replaces the entire preset using an ID from the preference preset library while preserving ordinary authored content; incompatible authored overrides block replacement. " +
            "Returns a process job; use job_wait/job_get for its committed state.",
        WorkspaceModifierSchemas.request(id), WorkspaceOperationKind.DOCUMENT, jobBacked = true, batchable = true,
        resultSchema = requireNotNull(WorkspaceJobResultSchemas.operationOutput(id)), jobResultSchema = WorkspaceJobResultSchemas.result(id))) { request, context ->
        startDocumentBatch(workspace, workspace, jobs, id, request.getValue("state").jsonPrimitive.content,
            if (id == "modifier_convert") "Converted model presets" else "Edited modifier stack", listOf(WorkspaceDocumentOperation(id, JsonObject(request - "state"))), context.author)
    }
    registry.register(WorkspaceOperationDefinition("modifier_catalog", "List registered modifier algorithms, versions and dependencies.",
        WorkspaceResultSchema.obj(emptyMap()), WorkspaceOperationKind.QUERY, workspaceBound = false,
        resultSchema = WorkspaceModifierSchemas.catalog)) { _, _ -> WorkspaceOperationOutput(buildJsonObject {
            putJsonArray("types") { RigModifierAlgorithms.registry.definitions.forEach { definition -> addJsonObject {
                put("type", definition.type); put("version", definition.version)
                put("requires", JsonArray(definition.requires.sorted().map(::JsonPrimitive)))
                put("provides", JsonArray(definition.provides.sorted().map(::JsonPrimitive)))
                put("exclusive_family", definition.exclusiveFamily?.let(::JsonPrimitive) ?: JsonNull)
            } } }
            putJsonArray("groups") { WorkspaceModifierPlan.groupCatalog.forEach { group -> addJsonObject {
                put("id", group.id); put("algorithm", group.algorithm); put("richness", group.richness)
                put("types", JsonArray(group.types.map(::JsonPrimitive)))
            } } }
            put("baselines", JsonArray(WorkspacePresetBaseline.ids.map(::JsonPrimitive)))
            putJsonArray("presets") { WorkspaceModelPresetLibrary.shared.snapshot().forEach { preset -> addJsonObject {
                put("id", preset.id); put("name", preset.name); put("name_key", preset.nameKey?.let(::JsonPrimitive) ?: JsonNull)
            } } }
        }) }
    registry.register(WorkspaceOperationDefinition("modifier_plan", "Plan source bindings, a legacy or modern preset group at minimal/standard/full richness, or native swing/physics/simulation/motion at captured targets. " +
        "Optional physics presets capture the original front hair, back hair or eye jelly rules. Returns the complete additions with fixed IDs and reused or applied providers; publishes nothing. Dry-run its entries before adding the group.",
        WorkspaceModifierSchemas.planRequest, WorkspaceOperationKind.QUERY, workspaceBound = true, resultSchema = WorkspaceModifierSchemas.plan)) { request, _ ->
        WorkspaceOperationOutput(workspace.captureQueries().modifierPlan(request))
    }
    registry.register(WorkspaceOperationDefinition("modifier_stack", "Read the captured stack, editable configurations and actual contributions. " +
        "Simulation bakes are summarized; geometry arrays are not returned. The state token identifies the snapshot to edit.",
        WorkspaceResultSchema.obj(emptyMap()), WorkspaceOperationKind.QUERY, workspaceBound = true, resultSchema = WorkspaceModifierSchemas.stack)) { _, _ ->
        WorkspaceOperationOutput(workspace.captureQueries().modifierStack())
    }
}

internal object WorkspaceModifierQueries {
    fun describe(capture: WorkspaceCapture<RigPreviewModel>): JsonObject = buildJsonObject {
        val raw = capture.document.rigEdits.modifierStack
        val stack = raw?.let(RigModifierStack::fromJson)
        val reports = capture.model.rig.modifierReport?.get("entries")?.jsonArray.orEmpty().associateBy {
            it.jsonObject.getValue("id").jsonPrimitive.content
        }
        put("state", capture.state); put("project_id", capture.projectId); put("history_node_id", capture.historyHead); put("revision", capture.revision)
        put("legacy", stack == null)
        put("organization", RigModifierOrganization.encode(stack?.let(RigModifierOrganization::nodes).orEmpty()))
        put("next_id", stack?.nextId ?: 1L)
        put("retired_ids", JsonArray(stack?.retiredIds.orEmpty().sorted().map(::JsonPrimitive)))
        putJsonArray("entries") {
            stack?.instances.orEmpty().forEach { instance ->
                val report = requireNotNull(reports[instance.id]) { "Captured modifier report is missing ${instance.id}" }.jsonObject
                add(buildJsonObject {
                    report.forEach { (key, value) -> put(key, value) }
                    instance.presetGroup?.let { put("group", it) }
                    instance.plugin?.let { put("plugin", Json.encodeToJsonElement(ModifierPluginReference.serializer(), it)) }
                    put("config", if (instance.type == "simulation") JsonObject(instance.config - "bake") else instance.config)
                    if (instance.type == "simulation") RigSimEdit.fromJson(instance.config).bake?.let { put("bake", it.summary()) }
                })
            }
        }
    }
}
