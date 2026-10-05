package io.github.psd2live.application

import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.core.quality.GeometryInspectionReport
import io.github.psd2live.project.MutationAuthor
import io.github.psd2live.project.WorkspaceDocument
import io.github.psd2live.project.WorkspaceMutationResult
import io.github.psd2live.project.WorkspaceRevisions
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.*
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.PuppetModel

/** The same document command boundary is used by desktop batches and independent application hosts. */
internal class WorkspaceDocumentCommands(private val runtime: WorkspaceRuntime<RigPreviewModel>,
                                         private val simulationWork: WorkspaceSimulationWork = WorkspaceSimulationWork.Direct,
                                         private val physicsWork: WorkspacePhysicsWork = WorkspacePhysicsWork.Direct,
                                         private val rasterWork: WorkspaceRasterWork = WorkspaceRasterWork.Direct,
                                         private val assetResources: ((WorkspaceCapture<RigPreviewModel>) -> WorkspaceAssetWorkflow)? = null) {
    private val previews = WorkspacePreviewBuilder()
    suspend fun execute(projectId: String, state: String, summary: String, edits: List<WorkspaceDocumentOperation>,
                        author: MutationAuthor, taskId: String? = null,
                        poses: (Map<String, WorkspacePose>) -> Unit = {},
                        beforeCommit: (WorkspaceCapture<RigPreviewModel>, WorkspaceDocument, RigPreviewModel) -> Unit = { _, _, _ -> }): WorkspaceCommit<RigPreviewModel> {
        require(edits.size in 1..128) { "Use 1..128 edits" }
        require(edits.all { it.operation in WorkspaceDocumentEdits.supported }) { "Batch contains an unsupported document operation" }
        val (before, resources) = runtime.withCapture { capture ->
            if (capture.state != state) throw WorkspaceConflict(state, capture.state)
            require(capture.projectId == projectId) { "Operation targets another project" }
            capture to if (edits.any { it.operation in WorkspaceAssetLayerEdits.supported && it.operation != "layer_finalize_placement" })
                requireNotNull(assetResources) { "Asset resources are required" }.invoke(capture) else null
        }
        val context = currentCoroutineContext()
        val batch = context[WorkspaceBatchJobExecution]
        require(batch == null || batch.edits == edits) { "A nested document command cannot replace the active batch" }
        val prepared = prepare(projectId, state, edits, resources)
        val geometry = checkGeometry(prepared)
        WorkspaceCandidateQuality.requireAccepted(geometry)
        val diagnostics = geometry.takeIf { it.affectedTargets.isNotEmpty() }?.toJson()
        val result = runtime.commitPrepared(projectId, state, summary, author, prepared.draft.document, prepared.model,
            taskId = taskId, auxiliary = prepared.draft.auxiliary.takeIf { it != prepared.before.auxiliary },
            beforeCommit = { captured, document, model ->
                batch?.committing()
                beforeCommit(captured, document, model)
                changedPoses(captured, prepared.draft, model).takeIf { it.isNotEmpty() }?.let(poses)
            }).copy(geometryDiagnostics = diagnostics)
        // No suspension between the authoritative CAS and retaining its complete public result.
        batch?.committed(mutationResult(before, result, summary, edits))
        return result
    }

    /** Same preparation and gate as commit, without projection, CAS, history or resource publication. */
    suspend fun preview(projectId: String, state: String, edits: List<WorkspaceDocumentOperation>): JsonObject {
        require(edits.all { it.operation in WorkspaceCandidateQuality.operations }) { "Preview supports geometry authoring operations only" }
        require(edits.filter { it.operation in setOf("canvas_warp", "canvas_rotation", "canvas_glue") }
            .all { !it.request["id"]?.jsonPrimitive?.contentOrNull.isNullOrBlank() }) { "Preview creation requires an explicit id; reuse it when committing" }
        val prepared = prepare(projectId, state, edits, null)
        val report = checkGeometry(prepared)
        val before = prepared.before
        val revision = WorkspaceRevisions.of(prepared.draft.document)
        val wouldChange = revision != before.revision
        return buildJsonObject {
            put("project_id", JsonPrimitive(before.projectId))
            put("state", JsonPrimitive(before.state))
            put("history_node_id", JsonPrimitive(before.historyHead))
            put("revision", JsonPrimitive(before.revision))
            put("candidate_revision", JsonPrimitive(revision))
            put("dry_run", JsonPrimitive(true))
            put("would_change", JsonPrimitive(wouldChange))
            put("would_commit", JsonPrimitive(wouldChange && report.safe))
            put("changed", JsonArray(createdObjectIds(before.model.rig.puppet, prepared.model.rig.puppet).map(::JsonPrimitive)))
            put("diagnostics", report.toJson())
        }
    }

    private suspend fun prepare(projectId: String, state: String, edits: List<WorkspaceDocumentOperation>,
                                resources: WorkspaceAssetWorkflow?): WorkspacePreparedDraft<RigPreviewModel> {
        val context = currentCoroutineContext()
        val batch = context[WorkspaceBatchJobExecution]
        val prepared = runtime.prepareDraft(projectId, state,
            edits.mapIndexed { index, operation -> WorkspaceDraftEdit { draft, model ->
                batch?.preparing(index)
                if (operation.operation == "settings_update") {
                    // Settings also release authored poses; both stay in this member's private candidate.
                    val next = runInterruptible(Dispatchers.Default) {
                        WorkspaceSettingsIntent.parse(draft.document, model, operation.request.getValue("changes").jsonObject).apply(draft, model)
                    }
                    return@WorkspaceDraftEdit next.copy(document = previews.normalizeMeshEdits(next.document, model))
                }
                val document = draft.document
                val candidate = runInterruptible(Dispatchers.Default) { WorkspaceDocumentEdits.apply(operation, document, model, simulationWork.cancellable(context) { id, value ->
                    batch?.baking(index, id, value) ?: context[WorkspaceJobContext]?.progress(0.1f + 0.75f * value, "Baking simulation $id")
                }, physicsWork.cancellable(context) { id, fraction ->
                    batch?.fitting(index, id, fraction) ?: context[WorkspaceJobContext]?.progress(0.1f + 0.65f * fraction, "Fitting physics $id")
                }, rasterWork.cancellable(context) { fraction, message ->
                    batch?.painting(index, fraction, message) ?: context[WorkspaceJobContext]?.progress(0.05f + 0.75f * fraction, message)
                }, resources) }
                draft.copy(document = previews.normalizeMeshEdits(candidate, model))
            } },
            editFailure = { index, failure -> WorkspaceBatchEditException(index, edits[index].operation, failure) })
        runInterruptible(Dispatchers.Default) {
            WorkspaceAssetLayerEdits.validate(prepared.before.document, prepared.draft.document, prepared.model)
            validateRegisteredNeutral(prepared.model, edits.filter { it.operation == "layer_set_bounds" }.mapTo(HashSet()) { it.request.getValue("layer_id").jsonPrimitive.content })
        }
        return prepared
    }

    private suspend fun checkGeometry(prepared: WorkspacePreparedDraft<RigPreviewModel>): GeometryInspectionReport =
        runInterruptible(Dispatchers.Default) {
            WorkspaceCandidateQuality.inspect(prepared)
        }

    /** Materialized GUI gestures and public authoring operations use this same isolated journal draft. */
    suspend fun executeJournal(projectId: String, state: String, summary: String, edits: JsonArray,
                               author: MutationAuthor,
                               beforeCommit: (WorkspaceCapture<RigPreviewModel>, WorkspaceDocument, RigPreviewModel) -> Unit = { _, _, _ -> }): WorkspaceCommit<RigPreviewModel> =
        executeCandidate(projectId, state, summary, author,
            mutation = { document, model -> WorkspaceDocumentEdits.journal(document, model, edits) }, beforeCommit = beforeCommit)

    /** Internal typed commands also use the same candidate/rebuild/CAS boundary as public batches. */
    suspend fun executeCandidate(projectId: String, state: String, summary: String, author: MutationAuthor,
                                 taskId: String? = null,
                                 mutation: (WorkspaceDocument, RigPreviewModel) -> WorkspaceDocument,
                                 beforeCommit: (WorkspaceCapture<RigPreviewModel>, WorkspaceDocument, RigPreviewModel) -> Unit = { _, _, _ -> }): WorkspaceCommit<RigPreviewModel> {
        val prepared = runtime.prepareDraft(projectId, state, listOf(WorkspaceDraftEdit { draft, model ->
            val candidate = runInterruptible(Dispatchers.Default) { mutation(draft.document, model) }
            draft.copy(document = previews.normalizeMeshEdits(candidate, model))
        }))
        val geometry = checkGeometry(prepared)
        WorkspaceCandidateQuality.requireAccepted(geometry)
        val diagnostics = geometry.takeIf { it.affectedTargets.isNotEmpty() }?.toJson()
        return runtime.commitPrepared(projectId, state, summary, author, prepared.draft.document, prepared.model,
            taskId = taskId, beforeCommit = beforeCommit).copy(geometryDiagnostics = diagnostics)
    }

    /** Settings commands that may release authored poses; other generation inputs keep [executeCandidate]. */
    suspend fun executeSettings(projectId: String, state: String, summary: String, author: MutationAuthor,
                                changes: JsonObject,
                                poses: (Map<String, WorkspacePose>) -> Unit = {},
                                beforeCommit: (WorkspaceCapture<RigPreviewModel>, WorkspaceDocument, RigPreviewModel) -> Unit = { _, _, _ -> }): WorkspaceCommit<RigPreviewModel> =
        runtime.executeDraft(projectId, state, summary, author, listOf(WorkspaceDraftEdit { draft, model ->
            val next = runInterruptible(Dispatchers.Default) { WorkspaceSettingsIntent.parse(draft.document, model, changes).apply(draft, model) }
            next.copy(document = previews.normalizeMeshEdits(next.document, model))
        })) { captured, draft, model ->
            beforeCommit(captured, draft.document, model)
            changedPoses(captured, draft, model).takeIf { it.isNotEmpty() }?.let(poses)
        }

    companion object {
        /** The authored poses a commit changes, read against the committed model as every later reader will. */
        fun changedPoses(before: WorkspaceCapture<RigPreviewModel>, draft: WorkspaceDraft, model: RigPreviewModel): Map<String, WorkspacePose> {
            val records = draft.auxiliary["posesByWorkspace"]?.jsonObject.orEmpty()
            if (records == before.auxiliary["posesByWorkspace"]?.jsonObject.orEmpty()) return emptyMap()
            return records.keys.filter { records[it] != before.auxiliary["posesByWorkspace"]?.jsonObject?.get(it) }
                .associateWith { PreviewSessions.read(model.rig.puppet.parameters, draft.auxiliary, it) }
        }

        fun mutationResult(before: WorkspaceCapture<RigPreviewModel>, result: WorkspaceCommit<RigPreviewModel>,
                           summary: String, edits: List<WorkspaceDocumentOperation>): WorkspaceMutationResult {
            val changed = if (!result.applied) emptyList() else edits.flatMap { edit ->
                listOf("layer_id", "parameter_id", "target", "id", "path_id").mapNotNull { edit.request[it]?.jsonPrimitive?.contentOrNull }
            }.plus((before.document.deletedLayerIds - result.capture.document.deletedLayerIds) +
                (result.capture.document.deletedLayerIds - before.document.deletedLayerIds))
                .plus(createdObjectIds(before.model.rig.puppet, result.capture.model.rig.puppet)).distinct()
                .plus(result.capture.document.source.layers.map { it.id.raw }.filterNot { id -> before.document.source.layers.any { it.id.raw == id } }
                    .map { "layer:$it" })
                .plus(before.document.source.layers.map { it.id.raw }.filterNot { id -> result.capture.document.source.layers.any { it.id.raw == id } }
                    .map { "layer:$it" }).distinct()
            return WorkspaceMutationResult(result.capture.historyHead, result.capture.revision, emptyList(), summary,
                affectedObjectIds = changed, applied = result.applied, state = result.capture.state, projectId = result.capture.projectId,
                geometryDiagnostics = result.geometryDiagnostics)
        }

        /** Stable handles include generated IDs even when a request omitted an optional ID. */
        fun createdObjectIds(before: PuppetModel, after: PuppetModel): List<String> {
            fun ids(puppet: PuppetModel): Set<String> = buildSet {
                puppet.drawables.forEach { add("mesh:${it.id.raw}") }
                puppet.deformers.forEach { add("${if (it is Deformer.Warp) "warp" else "rotation"}:${it.id.raw}") }
                puppet.glues.forEach { it.id?.let { id -> add("glue:$id") } }
                puppet.deformPaths.forEach { add("path:${it.id}") }
                puppet.parameters.forEach { add("parameter:${it.id.raw}") }
            }
            return (ids(after) - ids(before)).toList()
        }
    }
}
