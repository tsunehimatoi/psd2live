package io.github.psd2live.application

import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.project.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import io.github.psd2live.core.legacy.RigGenerationJournal

internal data class WorkspaceGenerationCommit(val commit: WorkspaceCommit<RigPreviewModel>, val mutation: WorkspaceMutationResult)

/** Generation edits prepare against the captured document, including omitted classification fields. */
internal class WorkspaceGenerationCommands(private val runtime: WorkspaceRuntime<RigPreviewModel>) {
    private val commands = WorkspaceDocumentCommands(runtime)
    private val previews = WorkspacePreviewBuilder()

    /** Field gestures freeze one draft; generation-only changes use the public pure candidates. */
    suspend fun prepareDraft(before: WorkspaceDocument, current: RigPreviewModel, draft: WorkspaceDocument): WorkspaceDocument {
        // A public command may have already materialized the complete draft. Re-normalizing
        // that journal against the gesture's original model would append the transition twice.
        if (draft.generationSource != null && draft.rigEdits.authoringJournal != before.rigEdits.authoringJournal &&
            draft.rigEdits.authoringJournal.lastOrNull()?.get("op")?.jsonPrimitive?.contentOrNull == RigGenerationJournal.OP)
            return draft
        val changes = JsonObject(draft.settings.filter { (key, value) -> before.settings[key] != value })
        if (before.copy(settings = draft.settings, layerOverrides = draft.layerOverrides, meshOverrides = draft.meshOverrides) != draft ||
            (before.settings.keys - draft.settings.keys).isNotEmpty() || changes.keys.any { it !in workspaceProjectSettingKeys } ||
            (before.layerOverrides.keys - draft.layerOverrides.keys).isNotEmpty()) return previews.normalizeMeshEdits(draft, current)
        val edits = buildList {
            if (changes.isNotEmpty()) add(WorkspaceDocumentOperation("settings_update", buildJsonObject { put("changes", changes) }))
            draft.layerOverrides.filter { (id, value) -> before.layerOverrides[id] != value }.forEach { (id, value) ->
                add(WorkspaceDocumentOperation("layer_classify", buildJsonObject {
                    put("layer_id", id); put("type", value.type.name); put("role", value.tag.name); put("side", value.side.name)
                    put("parameter", value.parameter); put("switch_id", value.switchId)
                }))
            }
            (before.meshOverrides.keys + draft.meshOverrides.keys).filter { before.meshOverrides[it] != draft.meshOverrides[it] }.forEach { id ->
                add(WorkspaceDocumentOperation("layer_mesh_update", buildJsonObject {
                    put("layer_id", id)
                    val settings = draft.meshOverrides[id]
                    if (settings == null) put("reset", true) else put("changes", buildJsonObject {
                        put("outerMargin", settings.outerMargin); put("edgeMode", settings.edgeMode.name); put("edgeWidth", settings.edgeWidth)
                        put("maxEdgeDistance", settings.maxEdgeDistance); put("interiorDensity", settings.interiorDensity)
                        put("fillAlgorithm", settings.fillAlgorithm.name); put("suppressBoundaryDiagonals", settings.suppressBoundaryDiagonals)
                        put("fillParameters", WorkspaceSettingsCodec.encodeFillParameters(settings.fillParameters))
                        put("wrap", settings.wrap)
                    })
                }))
            }
        }
        var document = before; var model = current
        for ((index, edit) in edits.withIndex()) {
            document = previews.normalizeMeshEdits(WorkspaceDocumentEdits.apply(edit, document, model), model)
            if (index < edits.lastIndex) model = previews.build(document)
        }
        return document
    }

    /**
     * A field session's settings switches replay through [WorkspaceSettingsIntent] in the order they were made, so an
     * off then on inside one session still releases what the off drove, and the fields they link follow the public
     * command rather than the GUI's local copy of the link. The rest of the draft then applies as [prepareDraft].
     */
    suspend fun prepareEditorDraft(before: WorkspaceDraft, current: RigPreviewModel, draft: WorkspaceDocument,
                                   settingsIntents: List<JsonObject>): WorkspaceDraft {
        if (settingsIntents.isEmpty()) return before.copy(document = prepareDraft(before.document, current, draft))
        var next = before; var model = current
        for (changes in settingsIntents) {
            val applied = WorkspaceSettingsIntent.parse(next.document, model, changes).apply(next, model)
            val document = previews.normalizeMeshEdits(applied.document, model)
            // A later switch reads the rig its predecessors produced, as members of one public batch do.
            if (WorkspaceRevisions.of(document) != WorkspaceRevisions.of(next.document)) model = previews.build(document)
            next = applied.copy(document = document)
        }
        // atlasSize is only raised to a minimum, which the remaining settings re-check anyway.
        val named = settingsIntents.flatMapTo(HashSet()) { it.keys }
        val linked = next.document.settings.filter { (key, value) -> key !in named && key != "atlasSize" && before.document.settings[key] != value }
        val rest = draft.copy(settings = JsonObject(draft.settings + linked))
        return next.copy(document = prepareDraft(next.document, model, rest))
    }

    suspend fun execute(projectId: String, state: String, operation: WorkspaceDocumentOperation, summary: String,
        author: MutationAuthor,
        poses: (Map<String, WorkspacePose>) -> Unit = {},
        beforeCommit: (WorkspaceCapture<RigPreviewModel>, WorkspaceDocument, RigPreviewModel) -> Unit = { _, _, _ -> }): WorkspaceGenerationCommit {
        require(operation.operation in supported)
        val context = currentCoroutineContext()
        val job = context[WorkspaceGenerationJobExecution]
        job?.check(operation.operation)
        val before = runtime.capture()
        if (state != before.state) throw WorkspaceConflict(state, before.state)
        require(projectId == before.projectId) { "Operation targets another project" }
        context.ensureActive()
        context[WorkspaceJobContext]?.progress(0.1f, "Preparing generation settings")
        val commit = { capture: WorkspaceCapture<RigPreviewModel>, document: WorkspaceDocument, model: RigPreviewModel ->
            context.ensureActive(); context[WorkspaceJobContext]?.progress(0.95f, "Committing generation candidate")
            beforeCommit(capture, document, model)
        }
        val result = if (operation.operation == "settings_update") {
            context[WorkspaceJobContext]?.progress(0.3f, "Rebuilding generation candidate")
            commands.executeSettings(projectId, state, summary, author, operation.request.getValue("changes").jsonObject, poses, commit)
        } else commands.executeCandidate(projectId, state, summary, author, mutation = { document, model ->
            WorkspaceDocumentEdits.apply(operation, document, model).also {
                context.ensureActive(); context[WorkspaceJobContext]?.progress(0.3f, "Rebuilding generation candidate")
            }
        }, beforeCommit = commit)
        val layers = if (result.applied) listOfNotNull(operation.request["layer_id"]?.jsonPrimitive?.content) else emptyList()
        val mutation = WorkspaceMutationResult(result.capture.historyHead, result.capture.revision, layers, summary,
            affectedObjectIds = if (result.applied) WorkspaceDocumentCommands.createdObjectIds(before.model.rig.puppet,
                result.capture.model.rig.puppet) else emptyList(), applied = result.applied,
            state = result.capture.state, projectId = result.capture.projectId)
        // Retain the CAS result before host projection refresh or late cancellation can intervene.
        job?.committed(operation.operation, mutation.generationResult(operation))
        return WorkspaceGenerationCommit(result, mutation)
    }

    companion object { val supported = setOf("settings_update", "layer_mesh_update", "layer_classify") }
}

internal fun WorkspaceMutationResult.generationResult(operation: WorkspaceDocumentOperation): JsonObject =
    if (operation.operation != "layer_classify") compact() else buildJsonObject {
        put("state", requireNotNull(state)); put("project_id", requireNotNull(projectId)); put("history_node_id", historyNodeId)
        put("layer_id", operation.request.getValue("layer_id"))
        if (!applied) put("applied", false)
    }

internal class WorkspaceGenerationJobExecution(private val operation: String, private val completion: WorkspaceJobCompletion) :
    AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<WorkspaceGenerationJobExecution>
    fun check(actual: String) { require(actual == operation) { "A nested command cannot replace the active generation operation" } }
    fun committed(actual: String, result: JsonObject) { check(actual); completion.committed(WorkspaceOperationOutput(result)) }
}
