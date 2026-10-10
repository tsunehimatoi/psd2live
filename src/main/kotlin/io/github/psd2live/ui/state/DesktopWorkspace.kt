package io.github.psd2live.ui.state

import io.github.psd2live.project.ParameterSnapshot

import io.github.psd2live.application.WorkspaceRigGeometry


import io.github.psd2live.application.*


import io.github.psd2live.application.WorkspaceSkeletonMotionEdits
import io.github.psd2live.application.sourceArtwork
import io.github.psd2live.application.addLayer
import io.github.psd2live.application.replacePlacedLayer
import io.github.psd2live.application.paintSource
import io.github.psd2live.application.sampleSourceColor

import io.github.psd2live.application.mergeProjectSettings

import io.github.psd2live.project.rawConfig

import io.github.psd2live.project.WorkspaceSettingsCodec

import io.github.psd2live.project.preview

import io.github.psd2live.project.MutationAuthor
import io.github.psd2live.project.WorkspaceAddLayerRequest
import io.github.psd2live.project.WorkspaceAssetPreview
import io.github.psd2live.application.WorkspaceBackend
import io.github.psd2live.project.WorkspaceCreateParameterRequest
import io.github.psd2live.project.WorkspaceHistoryNodeSnapshot
import io.github.psd2live.project.WorkspaceHistorySnapshot
import io.github.psd2live.project.WorkspaceImportedPngAsset
import io.github.psd2live.project.WorkspaceKeyformTargetRef
import io.github.psd2live.project.WorkspaceLayerSnapshot
import io.github.psd2live.project.WorkspaceModelViewRequest
import io.github.psd2live.project.WorkspaceObjectAxisSnapshot
import io.github.psd2live.project.WorkspaceObjectCellSnapshot
import io.github.psd2live.project.WorkspaceObjectChannelTrackSnapshot
import io.github.psd2live.project.WorkspaceObjectGeometrySnapshot
import io.github.psd2live.project.WorkspaceObjectSnapshot
import io.github.psd2live.project.WorkspaceParameterSnapshot
import io.github.psd2live.project.WorkspacePngImportRequest
import io.github.psd2live.project.WorkspaceProjectSnapshot
import io.github.psd2live.project.WorkspaceRenderedView
import io.github.psd2live.project.WorkspaceTaskSnapshot
import io.github.psd2live.project.WorkspaceTaskStatus
import io.github.psd2live.project.WorkspaceUpdateParameterRequest
import io.github.psd2live.project.WorkspaceViewBackground
import io.github.psd2live.project.WorkspaceViewFrame
import io.github.psd2live.project.WorkspaceViewOutputSpec
import io.github.psd2live.project.WorkspaceViewSpatialMetadata
import io.github.psd2live.project.WorkspaceWorkflowResult
import io.github.psd2live.project.WorkspaceDocument
import io.github.psd2live.project.WorkspaceMutationResult
import io.github.psd2live.project.WorkspaceSourceMetadata
import io.github.psd2live.project.WorkspaceStore

import io.github.psd2live.core.withRigEdits
import io.github.psd2live.core.RigAuthoringJournal
import io.github.psd2live.core.Bounds
import io.github.psd2live.core.LayerClassificationOverride
import io.github.psd2live.core.ProgressListener
import io.github.psd2live.ui.state.WorkspaceStateCodec
import io.github.psd2live.core.PipelineConfig
import io.github.psd2live.core.MeshSettings
import io.github.psd2live.core.RigKeyformChannelsEdit
import io.github.psd2live.core.RigKeyformCopyEdit
import io.github.psd2live.core.RigKeyformDeleteEdit
import io.github.psd2live.core.RigKeyformGeometryEdit
import io.github.psd2live.core.RigKeyformSetEdit
import io.github.psd2live.core.RigParameterEdit
import io.github.psd2live.core.RigTargetKind
import io.github.psd2live.core.RigTargetRef
import io.github.psd2live.core.findDrawable
import io.github.psd2live.core.SkeletonAutoBuilder
import io.github.psd2live.ui.BoneHit
import io.github.psd2live.ui.SkeletonPoseTool
import io.github.psd2live.history.StaleWorkspaceHeadException
import io.github.psd2live.history.WorkspaceHistoryTree
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.state.PSD2LiveState
import org.umamo.format.art.SourceArt
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterKind
import org.umamo.runtime.model.PuppetModel
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.float
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.add
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Files
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger



class DesktopWorkspace(
	private val viewModel: PSD2LiveViewModel,
    private val storeRoot: Path = WorkspaceStore.defaultRoot(),
) : WorkspaceBackend, AutoCloseable {
    override suspend fun previewDocumentEdits(state: String, edits: List<WorkspaceDocumentOperation>): JsonObject {
        val before = captureForMutation()
        if (state != before.state) throw WorkspaceConflict(state, before.state)
        return documentCommands.preview(before.projectId, before.state, edits)
    }
    override suspend fun previewRegeneration(state: String, edits: List<WorkspaceDocumentOperation>): JsonObject {
        val before = captureForMutation()
        if (state != before.state) throw WorkspaceConflict(state, before.state)
        return documentCommands.previewRegeneration(before.projectId, before.state, edits)
    }
    override suspend fun applyDocumentEdits(state: String, summary: String, edits: List<WorkspaceDocumentOperation>,
                                            author: MutationAuthor): WorkspaceMutationResult = editMutex.withLock {
        require(edits.size in 1..128) { "Use 1..128 edits" }
        require(edits.all { it.operation in WorkspaceDocumentEdits.supported }) { "Batch contains an unsupported document operation" }
        val before = captureForMutation()
        if (state != before.state) throw WorkspaceConflict(state, before.state)
        val current = viewModel.state.value
        if (current.isAnalyzing || current.isGenerating) throw WorkspaceBusy()
        val result = documentCommands.execute(before.projectId, before.state, summary, edits, mutationAuthor(author),
            poses = { viewModel.projectWorkspacePoses(current, it) },
            beforeCommit = { _, document, model ->
                applyPreviewOrThrow(model, documentFrom(current), document, summary, current)
            })
        if (result.applied) {
            scheduleHistoryPersistence(before.projectId)
            viewModel.updateHistorySnapshot(history())
            viewModel.refreshWorkspaceRenderer(result.capture.model)
        }
        WorkspaceDocumentCommands.mutationResult(before, result, summary, edits)
    }

    private fun auxiliaryFrom(state: PSD2LiveState) = io.github.psd2live.project.WorkspaceAuxiliaryData(
        state.parameterSnapshots, state.historyAnnotations)

    override fun savedProjectData(): WorkspaceAuxiliarySnapshot {
        val read = runtime.read()
        val captured = requireNotNull(read.runtime.capture) { "No workspace is loaded" }
        return WorkspaceAuxiliarySnapshot(captured.projectId, captured.state, captured.historyHead,
            io.github.psd2live.project.WorkspaceAuxiliaryCodec.decode(captured.auxiliary),
            requireNotNull(read.history).selections.mapTo(HashSet()) { it.node.id })
    }

    override fun editSavedProjectData(state: String, edit: WorkspaceAuxiliaryEdit): WorkspaceAuxiliarySnapshot = synchronized(historyLock) {
        val before = runtime.capture()
        if (state != before.state) throw WorkspaceConflict(state, before.state)
        val current = viewModel.state.value
        val data = io.github.psd2live.project.WorkspaceAuxiliaryCodec.decode(before.auxiliary)
        val nodeIds = runtime.history(before.state).selections.mapTo(HashSet()) { it.node.id }
        val next = WorkspaceAuxiliaryEdits.apply(data, edit, before.model.rig.puppet.parameters, nodeIds)
        val encoded = kotlinx.serialization.json.JsonObject(before.auxiliary + io.github.psd2live.project.WorkspaceAuxiliaryCodec.encode(next))
        val committed = runtime.updateAuxiliary(before.projectId, before.state, encoded) { _, _ ->
            viewModel.installSavedProjectData(current, data, next)
        }
        WorkspaceAuxiliarySnapshot(committed.projectId, committed.state, committed.historyHead, next, nodeIds)
    }

    override fun sampleSourceColor(layerId: String, x: Int, y: Int): List<Int> =
        captureQueries().sampleSourceColor(layerId, x, y)

    /** Canvas records are read from the same v1 presentation the project archive stores. */
    private fun auxiliaryJson(state: PSD2LiveState) = CanvasVisibilityCodec.withRecords(kotlinx.serialization.json.JsonObject(
        io.github.psd2live.project.WorkspaceAuxiliaryCodec.encode(auxiliaryFrom(state)) +
            ("posesByWorkspace" to kotlinx.serialization.json.buildJsonObject {
                state.workspaces.forEach { workspace ->
                    val pose = workspace.pose
                    val values = if (workspace.id == state.activeWorkspace.id) state.parameterValues else pose?.parameterValues.orEmpty()
                    val locks = if (workspace.id == state.activeWorkspace.id) state.lockedParameters else pose?.lockedParameters.orEmpty()
                    put(workspace.id, PreviewSessions.encode(io.github.psd2live.application.WorkspacePose(values, locks)))
                }
            })), CanvasVisibilityCodec.fromPresentation(WorkspaceStateCodec.encode(state)))

    private fun canvasAddresses(state: PSD2LiveState): List<CanvasAddress> = state.workspaces.flatMap { workspace ->
        workspace.canvases.flatMap { canvas -> CanvasMode.entries.map { CanvasAddress(workspace.id, canvas.id, it.canvasViewMode()) } }
    }

    override fun canvasVisibility(): WorkspaceCanvasVisibilitySnapshot = synchronized(historyLock) {
        canvasVisibilityCommands.snapshot(runtime.capture(), canvasAddresses(viewModel.state.value))
    }

    override fun editCanvasVisibility(state: String, address: CanvasAddress, intent: CanvasVisibilityIntent): WorkspaceCanvasVisibilitySnapshot =
        synchronized(historyLock) {
            val current = viewModel.state.value
            canvasVisibilityCommands.edit(runtime.capture().projectId, state, address, canvasAddresses(current), intent) { _, value, changed ->
                viewModel.applyCanvasVisibility(current, address, value, changed)
            }
        }

    /** Called only for an authored pose boundary, never for evaluated animation/physics frames. */
    override fun commitAuthoredPoses(state: String, poses: Map<String, io.github.psd2live.application.WorkspacePose>) = synchronized(historyLock) {
        val before = runtime.capture()
        previewCommands.authored(before.projectId, state, poses)
        Unit
    }

    override fun previewSession(): kotlinx.serialization.json.JsonObject = captureQueries().previewSession()

    override fun authoredPose(workspaceId: String): io.github.psd2live.application.WorkspacePose {
        val captured = runtime.capture()
        return PreviewSessions.read(captured.model.rig.puppet.parameters, captured.auxiliary, workspaceId)
    }

    override fun controlPlayback(arguments: kotlinx.serialization.json.JsonObject): kotlinx.serialization.json.JsonObject = synchronized(historyLock) {
        val captured = runtime.capture()
        requireExpected(arguments.getValue("state").jsonPrimitive.content, captured)
        val current = viewModel.state.value
        val result = playbackSessions.configure(captured.projectId, captured.state, current.activeWorkspace.id, arguments,
            initialTracking = current.mouseTrackingEnabled, initialSmoothTracking = current.smoothMouseTracking)
        viewModel.applyPlaybackFrame(result)
        result
    }

    override fun playbackFrame(dt: Float?): kotlinx.serialization.json.JsonObject = synchronized(historyLock) {
        runtime.capture()
        playbackSessions.frame(viewModel.state.value.activeWorkspace.id, dt)
    }

    override fun playbackPointer(pointer: Pair<Float, Float>?) =
        playbackSessions.pointer(viewModel.state.value.activeWorkspace.id, pointer)

    override fun previewPhysics(arguments: kotlinx.serialization.json.JsonObject): kotlinx.serialization.json.JsonObject = synchronized(historyLock) {
        val capture = runtime.capture()
        requireExpected(arguments.getValue("state").jsonPrimitive.content, capture)
        playbackSessions.physics(capture.projectId, capture.state, viewModel.state.value.activeWorkspace.id, arguments)
    }

    override suspend fun authorPose(arguments: kotlinx.serialization.json.JsonObject, author: MutationAuthor): kotlinx.serialization.json.JsonObject = editMutex.withLock {
        val captured = captureForMutation()
        requireExpected(arguments.getValue("state").jsonPrimitive.content, captured)
        val current = viewModel.state.value
        // A queued GUI change lands in the workspace it was made in, even if focus has moved on since.
        val commit = kotlinx.coroutines.currentCoroutineContext()[PendingPoseCommit]
        val workspaceId = commit?.workspaceId ?: current.activeWorkspace.id
        val result = poseCommands.execute(captured.projectId, captured.state, workspaceId, arguments, mutationAuthor(author)) { _, document, model, pose ->
            viewModel.projectAuthoredPose(current, pose, pose != PreviewSessions.read(captured.model.rig.puppet.parameters, captured.auxiliary, workspaceId), commit) {
                if (document != captured.document) applyPreviewOrThrow(model, documentFrom(current), document, "Author pose", current)
            }
        }
        val frame = resetAuthoredPlayback(captured.projectId, result.getValue("state").jsonPrimitive.content, current)
        viewModel.applyPlaybackFrame(frame)
        scheduleHistoryPersistence(captured.projectId)
        viewModel.updateHistorySnapshot(history())
        viewModel.refreshWorkspaceRenderer(runtime.capture().model)
        result
    }

    override suspend fun setPreviewSession(arguments: kotlinx.serialization.json.JsonObject): kotlinx.serialization.json.JsonObject = editMutex.withLock {
        val captured = captureForMutation()
        requireExpected(arguments.getValue("state").jsonPrimitive.content, captured)
        val current = viewModel.state.value
        val commit = kotlinx.coroutines.currentCoroutineContext()[PendingPoseCommit]
        val result = previewCommands.edit(captured.projectId, captured.state, commit?.workspaceId ?: current.activeWorkspace.id, arguments) { _, pose, changed ->
            viewModel.applyPreviewSession(current, pose, changed, commit)
        }
        val frame = resetAuthoredPlayback(captured.projectId, result.getValue("state").jsonPrimitive.content, current)
        viewModel.applyPlaybackFrame(frame)
        result
    }

    override fun applySavedSnapshotPose(state: String, id: String): kotlinx.serialization.json.JsonObject = synchronized(historyLock) {
        val captured = runtime.capture()
        if (state != captured.state) throw WorkspaceConflict(state, captured.state)
        val current = viewModel.state.value
        val result = previewCommands.snapshot(captured.projectId, captured.state, current.activeWorkspace.id, id) { _, pose, changed ->
            viewModel.applyPreviewSession(current, pose, changed)
        }
        viewModel.applyPlaybackFrame(resetAuthoredPlayback(captured.projectId, result.getValue("state").jsonPrimitive.content, current))
        result
    }

    /** An authored change stops playback and restarts the clocks from the new pose; tracking and the open motion stay. */
    private fun resetAuthoredPlayback(projectId: String, state: String, current: PSD2LiveState): kotlinx.serialization.json.JsonObject =
        playbackSessions.restart(projectId, state, current.activeWorkspace.id, initialTracking = current.mouseTrackingEnabled, initialSmoothTracking = current.smoothMouseTracking)

    override fun layerMeshSettings(layerId: String): kotlinx.serialization.json.JsonObject = captureQueries().layerMeshSettings(layerId)
    override suspend fun importPsd(path: String, discardUnsaved: Boolean): WorkspaceMutationResult = editMutex.withLock {
        val (state, current) = captureSourceImport(discardUnsaved)
        val input = Path.of(path)
        val result = sourceImporter.importPsd(input, state.capture?.projectId, state.state, discardUnsaved,
            state.capture?.document?.rawConfig() ?: current.rawConfig()) { document, preview, id ->
            viewModel.applySourceImport(current, document, preview, input, id)
        }
        finishSourceImport(result)
        result.mutation("Imported PSD ${input.fileName}")
    }

    private suspend fun captureSourceImport(discardUnsaved: Boolean): Pair<WorkspaceRuntimeState<io.github.psd2live.core.RigPreviewModel>, PSD2LiveState> {
        draftQueue.awaitSettled()
        val state = runtime.state.value
        currentCoroutineContext()[WorkspaceExecution]?.check(state.capture?.projectId, state.state)
        val current = viewModel.state.value
        val author = mutationAuthor(MutationAuthor.AGENT)
        if (current.isGenerating || current.projectSaving || (author != MutationAuthor.USER && current.isAnalyzing)) throw WorkspaceBusy()
        if (!discardUnsaved && current.projectDirty) throw WorkspaceUnsavedChanges()
        return state to current
    }

    private suspend fun finishSourceImport(result: WorkspaceCommit<io.github.psd2live.core.RigPreviewModel>) {
        synchronized(historyLock) {
            discardEditorQueues(); recoveringProjectId = null; taskProjectId = null
            spatialByViewId.clear()
            scheduleHistoryPersistence(result.capture.projectId)
        }
        viewModel.refreshWorkspaceRenderer(result.capture.model)
    }

    override suspend fun importCmo3(path: Path, mode: io.github.psd2live.core.Cmo3ImportMode,
                                    discardUnsaved: Boolean): WorkspaceMutationResult = editMutex.withLock {
        draftQueue.awaitSettled()
        val state = runtime.state.value
        currentCoroutineContext()[WorkspaceExecution]?.check(state.capture?.projectId, state.state)
        val current = viewModel.state.value
        val author = mutationAuthor(MutationAuthor.AGENT)
        if (current.isGenerating || current.projectSaving || (author != MutationAuthor.USER && current.isAnalyzing)) throw WorkspaceBusy()
        if (mode != io.github.psd2live.core.Cmo3ImportMode.REPLACE && !discardUnsaved && current.projectDirty) throw WorkspaceUnsavedChanges()
        val result = cmo3Importer.import(path, mode, state.capture?.projectId, state.state, author,
            discardUnsaved, initialConfig = current.rawConfig()) { _, document, preview, id ->
            viewModel.applyCmo3Import(current, document, preview, path, id, mode == io.github.psd2live.core.Cmo3ImportMode.REPLACE)
        }
        synchronized(historyLock) {
            if (mode == io.github.psd2live.core.Cmo3ImportMode.NEW) {
                discardEditorQueues()
                recoveringProjectId = null; taskProjectId = null
                spatialByViewId.clear()
            }
            if (result.applied) scheduleHistoryPersistence(result.capture.projectId)
        }
        viewModel.refreshWorkspaceRenderer(result.capture.model)
        result.mutation("Imported CMO3 ${path.fileName}")
    }

    override suspend fun setLayerMeshSettings(
        state: String, layerId: String, changes: kotlinx.serialization.json.JsonObject?, reset: Boolean,
    ): WorkspaceMutationResult = generationCommand(WorkspaceDocumentOperation("layer_mesh_update", buildJsonObject {
        put("layer_id", layerId); changes?.let { put("changes", it) }; put("reset", reset)
    }), state, "Updated mesh settings for $layerId")

    override suspend fun exportPsd(
        state: String, path: String, scale: Int, includeGeneratedLayers: Boolean,
    ): kotlinx.serialization.json.JsonObject {
        val session = editMutex.withLock {
            val captured = captureForMutation()
            requireExpected(state, captured)
            val current = viewModel.state.value
            if (current.isAnalyzing || current.isGenerating && mutationAuthor(MutationAuthor.AGENT) != MutationAuthor.USER) throw WorkspaceBusy()
            WorkspaceExportSession(captured, current.projectSourceName ?: "model.psd")
        }
        return session.psd(Path.of(path), scale, includeGeneratedLayers)
    }
    override suspend fun exportModel(state: String, outputDirectory: String): kotlinx.serialization.json.JsonObject {
        val session = editMutex.withLock {
            val captured = captureForMutation()
            requireExpected(state, captured)
            val current = viewModel.state.value
            if (current.isAnalyzing || current.isGenerating && mutationAuthor(MutationAuthor.AGENT) != MutationAuthor.USER) throw WorkspaceBusy()
            WorkspaceExportSession(captured, current.projectSourceName ?: "model.psd")
        }
        return session.model(Path.of(outputDirectory))
    }
    override suspend fun exportTarget(state: String, targetId: String, outputDirectory: String,
                                      settings: Map<String, String>): kotlinx.serialization.json.JsonObject {
        val session = editMutex.withLock {
            val captured = captureForMutation()
            requireExpected(state, captured)
            val current = viewModel.state.value
            if (current.isAnalyzing || current.isGenerating && mutationAuthor(MutationAuthor.AGENT) != MutationAuthor.USER) throw WorkspaceBusy()
            WorkspaceExportSession(captured, current.projectSourceName ?: "model.psd")
        }
        return session.target(targetId, Path.of(outputDirectory), settings)
    }
    override fun projectSettings(): kotlinx.serialization.json.JsonObject = captureQueries().projectSettings()

    override fun skeletonSpec(): io.github.psd2live.core.SkeletonSpec? = captureQueries().skeletonSpec()

    override fun proposeSkeleton(): io.github.psd2live.core.SkeletonSpec = captureQueries().proposeSkeleton()

    override suspend fun editSkeleton(state: String, request: kotlinx.serialization.json.JsonObject): WorkspaceMutationResult =
        mutateModel(state, null, "Edited skeleton", "skeleton") { document, model -> WorkspaceDocumentEdits.skeleton(document, model, request) }

    override suspend fun openSkeletonDraft(state: String): WorkspaceSkeletonDraft = editMutex.withLock {
        val captured = captureForMutation()
        requireExpected(state, captured)
        val current = viewModel.state.value
        if (current.isAnalyzing || current.isGenerating) throw WorkspaceBusy()
        val opened = skeletonDraftSessions.open(captured.projectId, captured.state, current.activeWorkspace.id) { _, pose, changed ->
            viewModel.applyPreviewSession(current, pose, changed)
        }
        viewModel.applyPlaybackFrame(resetAuthoredPlayback(captured.projectId, opened.state, current))
        opened
    }

    override fun skeletonDrafts(): List<WorkspaceSkeletonDraft> = skeletonDraftSessions.list()

    override fun skeletonDraft(sessionId: String): WorkspaceSkeletonDraft = skeletonDraftSessions.get(sessionId)

    override fun skeletonDraftRevision(sessionId: String, revision: Long): io.github.psd2live.core.SkeletonSpec? =
        skeletonDraftSessions.revision(sessionId, revision)

    override fun editSkeletonDraft(sessionId: String, state: String, sessionState: String,
                                   intents: List<io.github.psd2live.core.SkeletonDraftIntent>): WorkspaceSkeletonDraft =
        skeletonDraftSessions.edit(sessionId, state, sessionState, intents)

    override fun previewSkeletonWeightTransfer(sessionId: String, transfer: io.github.psd2live.core.SkeletonDraftIntent.TransferWeights) =
        skeletonDraftSessions.weightTransfer(sessionId, transfer)

    override suspend fun commitSkeletonDraft(sessionId: String, state: String, sessionState: String,
                                             author: MutationAuthor): WorkspaceSkeletonDraftCommit = editMutex.withLock {
        val captured = captureForMutation()
        val current = viewModel.state.value
        if (current.isAnalyzing || current.isGenerating) throw WorkspaceBusy()
        // The session checks its own lineage; the live state is never substituted for it.
        val result = skeletonDraftSessions.commit(sessionId, state, sessionState, mutationAuthor(author)) { _, document, model ->
            applyPreviewOrThrow(model, documentFrom(current), document, "Edit skeleton", current)
        }
        if (result.mutation.applied) {
            scheduleHistoryPersistence(captured.projectId)
            viewModel.updateHistorySnapshot(history())
            viewModel.refreshWorkspaceRenderer(runtime.capture().model)
        }
        result
    }

    override fun cancelSkeletonDraft(sessionId: String): WorkspaceSkeletonDraft = skeletonDraftSessions.cancel(sessionId)

    override fun solveSkeletonPose(request: kotlinx.serialization.json.JsonObject): kotlinx.serialization.json.JsonObject = captureQueries().solveSkeletonPose(request)

    override fun motionClips(): List<io.github.psd2live.core.MotionClip> = captureQueries().motionClips()

    override suspend fun editMotion(state: String, request: kotlinx.serialization.json.JsonObject): WorkspaceMutationResult {
        var changed = emptyList<String>()
        val result = mutateModel(state, null, "Edited motion", request["id"]?.jsonPrimitive?.contentOrNull ?: "motion") { document, model ->
            val next = WorkspaceDocumentEdits.apply(WorkspaceDocumentOperation("motion_" + request.getValue("mode").jsonPrimitive.content, request), document, model)
            changed = (document.rigEdits.motionClips.filter { old -> next.rigEdits.motionClips.none { it == old } }.map { it.id } +
                next.rigEdits.motionClips.filter { clip -> document.rigEdits.motionClips.none { it == clip } }.map { it.id }).distinct()
            next
        }
        return result.copy(affectedObjectIds = changed.takeIf { result.applied }.orEmpty())
    }

    override suspend fun updateProjectSettings(
        state: String,
        changes: kotlinx.serialization.json.JsonObject,
    ): WorkspaceMutationResult = generationCommand(WorkspaceDocumentOperation("settings_update", buildJsonObject {
        put("changes", changes)
    }), state, "Updated project settings")

    override suspend fun deletePhysics(id: String, expectedState: String): WorkspaceMutationResult {
        return physicsCommand(WorkspaceDocumentOperation("physics_delete", kotlinx.serialization.json.buildJsonObject { put("id", id) }),
            expectedState, "Deleted physics group $id").first
    }

	private val editMutex = Mutex()
	private val historyLock = Any()
	private var workspaceStore = WorkspaceStore(storeRoot)
	private val persistenceJob = SupervisorJob()
	private val persistenceScope = CoroutineScope(persistenceJob + Dispatchers.IO.limitedParallelism(1))
	private val pendingPersistenceWrites = AtomicInteger()
	@Volatile private var persistenceError: String? = null
	@Volatile private var recoveringProjectId: String? = null
	private val spatialByViewId = ConcurrentHashMap<String, WorkspaceViewSpatialMetadata>()
	private var taskProjectId: String? = null
	private var taskManager = WorkspaceTaskRecords()
	private val previewBuilder = WorkspacePreviewBuilder()
    private val runtime = WorkspaceRuntime<io.github.psd2live.core.RigPreviewModel>({ document ->
        previewBuilder.build(document, runtimeModel())
    }, rebuildFrom = { document, previous -> previewBuilder.build(document, previous) })
    private val simulationWork = object : WorkspaceSimulationWork {
        override fun <T> run(id: String, action: ((Float) -> Unit, () -> Boolean) -> T): T = viewModel.trackSimulationBake(id, action)
    }
    private val assetSessions = WorkspaceAssetSessions(runtime)
    private val documentCommands = WorkspaceDocumentCommands(runtime, simulationWork, assetResources = { assetSessions.workflow(it, workspaceStore) })
    private val assetLayerCommands = WorkspaceAssetLayerCommands(runtime) { assetSessions.workflow(it, workspaceStore) }
    private val imageLayerCommands = WorkspaceImageLayerCommands(runtime)
    private val simulationCommands = WorkspaceSimulationCommands(runtime, simulationWork)
    private val physicsCommands = WorkspacePhysicsCommands(runtime)
    private val rasterCommands = WorkspaceRasterCommands(runtime)
    private val paintSessions = WorkspacePaintSessions(runtime)
    private val layerCommands = WorkspaceLayerCommands(runtime)
    private val generationCommands = WorkspaceGenerationCommands(runtime)
    private val partitionCommands = WorkspacePartitionCommands(runtime)
    private val generationUpdateCommands = WorkspaceGenerationUpdateCommands(runtime)
    private val warpCommands = WorkspaceWarpCommands(runtime)
    private val warpControlCommands = WorkspaceWarpControlCommands(runtime)
    private val previewCommands = WorkspacePreviewCommands(runtime)
    private val canvasVisibilityCommands = WorkspaceCanvasVisibilityCommands(runtime)
    private val poseCommands = WorkspacePoseCommands(runtime)
    private val playbackSessions = WorkspacePlaybackSessions(runtime)
    private val swingSessions = WorkspaceSwingSessions(runtime)
    private val skeletonDraftSessions = WorkspaceSkeletonDraftSessions(runtime)
    private val simulationPreviewSessions = WorkspaceSimulationPreviewSessions(runtime)
    private val physicsAuditionSessions = io.github.psd2live.application.WorkspacePhysicsAuditionSessions(runtime)
    private val projectController = ProjectController(viewModel)
    private val draftScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val draftQueue = WorkspaceDraftQueue(runtime, draftScope)
    private val textureCommands = WorkspaceTextureCommands(runtime)
    private val sourceImporter = WorkspaceSourceImporter(runtime, { document -> previewBuilder.build(document) })
    private val cmo3Importer = WorkspaceCmo3Importer(runtime, { document, current -> previewBuilder.build(document, current) })
    private fun runtimeModel(): io.github.psd2live.core.RigPreviewModel? = runtime.state.value.capture?.model
    internal fun attachCurrentWorkspace() {
        if (viewModel.state.value.analysis != null) runBlocking { importedPsd() }
    }
    private fun discardEditorQueues() {
        paintSessions.clear()
        draftQueue.discard()
    }

    private suspend fun captureForMutation(): WorkspaceCapture<io.github.psd2live.core.RigPreviewModel> {
        draftQueue.awaitSettled()
        return runtime.capture().also { currentCoroutineContext()[WorkspaceExecution]?.check(it.projectId, it.state) }
    }

    private suspend fun expectedRuntimeState(): String = runtime.state.value.let { value ->
        currentCoroutineContext()[WorkspaceExecution]?.check(value.capture?.projectId, value.state)
        value.state
    }

    private suspend fun mutationAuthor(fallback: MutationAuthor): MutationAuthor =
        currentCoroutineContext()[WorkspaceExecution]?.author ?: fallback

    private fun requireExpected(expected: String, capture: WorkspaceCapture<io.github.psd2live.core.RigPreviewModel>) {
        if (expected != capture.state)
            throw WorkspaceConflict(expected, capture.state)
    }
    private fun commitPrepared(capture: WorkspaceCapture<io.github.psd2live.core.RigPreviewModel>,
        current: PSD2LiveState, document: WorkspaceDocument, preview: io.github.psd2live.core.RigPreviewModel,
        summary: String, author: MutationAuthor, taskId: String? = null, checkpoint: Boolean = false) =
        runtime.commitPrepared(capture.projectId, capture.state, summary, author, document, preview, taskId, checkpoint) { _, next, model ->
            applyPreviewOrThrow(model, documentFrom(current), next, summary, current)
        }
    private fun projection(capture: WorkspaceCapture<io.github.psd2live.core.RigPreviewModel>, ui: PSD2LiveState = viewModel.state.value): PSD2LiveState {
        val auxiliary = io.github.psd2live.project.WorkspaceAuxiliaryCodec.decode(capture.auxiliary)
        val persisted = WorkspaceStateCodec.decode(capture.document.settings, ui).copy(
            parameterSnapshots = auxiliary.parameterSnapshots, historyAnnotations = auxiliary.historyAnnotations,
            projectId = capture.projectId, analysis = capture.model.analysis, previewModel = capture.model,
            documentLayerVisibility = capture.document.layerVisibility, deletedLayerIds = capture.document.deletedLayerIds,
            layerOverrides = capture.document.layerOverrides, parentOverrides = capture.document.parentOverrides,
            rigEdits = capture.document.rigEdits, meshOverrides = capture.document.meshOverrides,
            generationSource = capture.document.generationSource,
            meshSource = capture.document.meshSource,
            textureOverrides = capture.document.textureOverrides,
            projectDirty = capture.dirty || ui.projectDirty)
        val workspaces = persisted.workspaces.map { workspace ->
            val authored = PreviewSessions.read(capture.model.rig.puppet.parameters, capture.auxiliary, workspace.id)
            val presentation = if (workspace.id == persisted.activeWorkspace.id) WorkspacePose.capture(persisted)
                else workspace.pose ?: WorkspacePose.capture(workspace.activeCanvas.presentation)
            workspace.withPose(presentation.copy(parameterValues = authored.values, lockedParameters = authored.locked,
                previewParameterValues = emptyMap()))
        }
        val projected = persisted.copy(workspaces = workspaces)
        return projected.activeWorkspace.pose!!.applyTo(projected)
    }

    internal fun projectSaved(capture: ProjectCapture) = runtime.saved(capture.runtimeState)

    internal fun savedResult(capture: ProjectCapture): WorkspaceMutationResult {
        val head = capture.history.selections.single { it.node.id == capture.history.headNodeId }.node
        return WorkspaceMutationResult(head.id, head.revisionId, emptyList(), "Project saved", applied = capture.createdNode,
            state = capture.runtimeState, projectId = capture.projectId)
    }


    internal data class ProjectCapture(
        val projectId: String,
        val history: io.github.psd2live.history.WorkspaceHistoryState<WorkspaceDocument>,
        val uiState: PSD2LiveState,
        val tasks: List<WorkspaceTaskSnapshot>,
        val store: WorkspaceStore,
        val spatial: Map<String, WorkspaceViewSpatialMetadata>,
        /** False when the workspace already matched HEAD, so the capture appended no node. */
        val createdNode: Boolean = true,
        val runtimeState: String,
        val auxiliary: JsonObject = JsonObject(emptyMap()),
    )
    private val projectDirectories = mutableListOf<Path>()
    internal fun rememberProjectDirectory(path: Path) { projectDirectories.add(path) }
    internal suspend fun flushProjectPersistence() { persistenceScope.launch { }.join() }

    internal suspend fun importedPsd(recoverLegacy: Boolean = true) = editMutex.withLock {
        val expectedRuntime = expectedRuntimeState()
        val state = viewModel.state.value
        val id = state.projectId ?: error("Imported project has no identity")
        val store = WorkspaceStore(storeRoot)
        val legacyId = projectId(state.copy(projectId = null))
        val legacy = if (recoverLegacy) withContext(Dispatchers.IO) { store.loadHistory(legacyId) } else null
        val migrated = legacy?.let { old ->
            WorkspaceHistoryTree.restore(old.selections().map { selection ->
                val document = selection.snapshot.copy(settings = selection.snapshot.settings.ifEmpty { io.github.psd2live.ui.state.WorkspaceStateCodec.settings(state) })
                val revision = revisionId(state, document)
                selection.copy(node = selection.node.copy(revisionId = revision, snapshotHash = revision), snapshot = document)
            }, old.head().node.id)
        }
        val document = migrated?.head()?.snapshot ?: documentFrom(state)
        val preview = if (migrated != null) previewBuilder.build(document, state.previewModel) else state.previewModel!!
        if (migrated != null) withContext(Dispatchers.IO) {
            store.copyAuxiliary(legacyId, store.projectRoot(id), targetProjectId = id)
            store.persistTasks(id, store.loadTasks(legacyId))
        }
        currentCoroutineContext().ensureActive()
        synchronized(historyLock) {
            runtime.install(expectedRuntime, id, document, preview, migrated?.state(),
                auxiliary = JsonObject(auxiliaryJson(state) + ("assetCatalog" to store.existingAssetCatalog(id).encode())),
                discardUnsaved = true, dirty = state.projectDirty) {
                if (migrated != null) applyPreviewOrThrow(preview, documentFrom(state), document, "Recovered legacy workspace", state)
            }
            discardEditorQueues()
            workspaceStore = store
            recoveringProjectId = null
            taskProjectId = null
            spatialByViewId.clear()
            scheduleHistoryPersistence(id)
        }
        if (migrated != null) viewModel.refreshWorkspaceRenderer(preview)
    }

    /** Completed GUI drafts use the same candidate/rebuild/CAS boundary as application commands. */
    override fun submitEditorDraft(projectId: String, state: String, document: WorkspaceDocument,
                                   settingsIntents: List<kotlinx.serialization.json.JsonObject>,
                                   summary: String, author: MutationAuthor): kotlinx.coroutines.Deferred<WorkspaceMutationResult> {
        val expectedUi = viewModel.state.value
        val submitted = draftQueue.submit(projectId, state, document, summary, author,
            beforeCommit = { _, next, model, following ->
                val current = viewModel.state.value
                check(current.projectId == expectedUi.projectId &&
                    current.projectOpenGeneration == expectedUi.projectOpenGeneration) { "Editor draft belongs to an old project load" }
                val currentDocument = documentFrom(current)
                if (io.github.psd2live.project.WorkspaceRevisions.of(currentDocument) == io.github.psd2live.project.WorkspaceRevisions.of(document)) {
                    applyPreviewOrThrow(model, currentDocument, next, summary, current)
                } else {
                    check(following != null && io.github.psd2live.project.WorkspaceRevisions.of(currentDocument) ==
                        io.github.psd2live.project.WorkspaceRevisions.of(following)) { "Editor draft changed before its commit" }
                    // A later queued completion owns the visible draft. Preserve it while publishing this history step.
                }
            }, committed = { result ->
                synchronized(historyLock) {
                    val current = viewModel.state.value
                    if (current.projectId == expectedUi.projectId && current.projectOpenGeneration == expectedUi.projectOpenGeneration) {
                        if (result.applied) scheduleHistoryPersistence(projectId)
                        viewModel.refreshWorkspaceRenderer(result.capture.model)
                    }
                }
            }, prepare = { before, model, draft -> generationCommands.prepareEditorDraft(before, model, draft, settingsIntents) },
            auxiliary = { captured, next, model ->
                WorkspaceDocumentCommands.changedPoses(captured, next, model).takeIf { it.isNotEmpty() }
                    ?.let { viewModel.projectWorkspacePoses(expectedUi, it) }
            })
        return draftScope.async {
            val result = submitted.await()
            WorkspaceMutationResult(result.capture.historyHead, result.capture.revision, emptyList(), summary,
                applied = result.applied, state = result.capture.state, projectId = result.capture.projectId)
        }
    }

    override suspend fun awaitEditorDrafts() {
        draftQueue.awaitIdle()
    }

    override suspend fun settleEditorDrafts(projectId: String, state: String): String {
        val settled = draftQueue.settleCommand(projectId, state)
        val captured = runtime.capture()
        if (captured.projectId != projectId || captured.state != settled) throw WorkspaceConflict(settled, captured.state)
        return settled
    }

    internal suspend fun captureProject(summary: String, actor: String, alwaysCommit: Boolean = false): ProjectCapture = editMutex.withLock {
        awaitEditorDrafts()
        val before = captureForMutation()
        val captureAuthor = mutationAuthor(if (actor == "agent") MutationAuthor.AGENT else MutationAuthor.USER)
        val state = viewModel.state.value
        require(!state.isAnalyzing && recoveringProjectId == null && !state.canvasEditBusy) { "Workspace still has an operation in progress" }
        val document = documentFrom(state)
        val changed = io.github.psd2live.project.WorkspaceRevisions.of(document) != before.revision
        // Preparation can suspend without holding the history lock or blocking the GUI thread.
        val model = if (changed) previewBuilder.build(document, before.model) else before.model
        currentCoroutineContext().ensureActive()
        synchronized(historyLock) {
            val result = commitPrepared(before, state, document, model, summary,
                captureAuthor, checkpoint = alwaysCommit)
            if (result.applied) scheduleHistoryPersistence(before.projectId)
            ProjectCapture(before.projectId, runtime.history(result.capture.state), projection(result.capture),
                taskManagerFor(before.projectId).list(), workspaceStore, spatialByViewId.toMap(), result.applied, result.capture.state,
                JsonObject(result.capture.auxiliary + ("assetCatalog" to
                    (io.github.psd2live.project.WorkspaceAssetCatalog.read(result.capture.auxiliary)
                        ?: workspaceStore.existingAssetCatalog(before.projectId)).encode())))
        }
    }

    internal data class ProjectOpenExpectation(val uiState: PSD2LiveState, val runtimeState: String)

    internal suspend fun captureProjectOpen(discardUnsaved: Boolean): ProjectOpenExpectation {
        val expected = expectedRuntimeState()
        val current = viewModel.state.value
        check(!current.isAnalyzing && !current.isGenerating) { "Workspace is still loading" }
        if (!discardUnsaved && current.projectDirty) throw WorkspaceUnsavedChanges()
        return ProjectOpenExpectation(current, expected)
    }

    internal suspend fun installProject(
        id: String, file: Path, source: Path, state: PSD2LiveState,
        tree: WorkspaceHistoryTree<WorkspaceDocument>, store: WorkspaceStore, expectation: ProjectOpenExpectation,
        discardUnsaved: Boolean,
        auxiliary: JsonObject = JsonObject(emptyMap()),
    ) = editMutex.withLock {
        val expected = expectation.uiState
        val expectedRuntime = expectation.runtimeState
        val document = tree.head().snapshot
        val preview = previewBuilder.build(document, state.previewModel, state.drawOrderOverrides)
        val tasks = store.loadTasks(id) // Validate before replacing the live session.
        val restoredTasks = WorkspaceTaskRecords().also { it.restore(tasks) }
        // ProjectRepository.open validated this catalog against the store it opened.
        val catalog = io.github.psd2live.project.WorkspaceAssetCatalog.read(auxiliary) ?: store.existingAssetCatalog(id)
        currentCoroutineContext().ensureActive()
        lateinit var installed: WorkspaceCapture<io.github.psd2live.core.RigPreviewModel>
        synchronized(historyLock) {
            val current = viewModel.state.value
            if (!discardUnsaved && current.projectDirty) throw WorkspaceUnsavedChanges()
            require(current.projectId == expected.projectId && current.projectOpenGeneration == expected.projectOpenGeneration &&
                current.projectEditVersion == expected.projectEditVersion) { "Workspace changed while opening project; open again after saving your edits" }
            installed = runtime.install(expectedRuntime, id, document, preview, tree.state(),
                auxiliary = JsonObject(auxiliaryJson(state) + ("assetCatalog" to catalog.encode())), discardUnsaved = discardUnsaved) {
                viewModel.installProjectState(state.copy(
                projectId = id, projectFile = file.toString(), inputPath = source.toString(), loadedInputPath = source.toString(),
                analysis = preview.analysis, previewModel = preview,
                documentLayerVisibility = document.layerVisibility, layerOverrides = document.layerOverrides,
                deletedLayerIds = document.deletedLayerIds, parentOverrides = document.parentOverrides, rigEdits = document.rigEdits,
                generationSource = document.generationSource,
                meshSource = document.meshSource,
                textureOverrides = document.textureOverrides,
                ), expected)
            }
            discardEditorQueues()
            workspaceStore = store
            recoveringProjectId = null
            persistenceError = null
            taskProjectId = id
            taskManager = restoredTasks
            spatialByViewId.clear()
            viewModel.updateHistorySnapshot(history())
        }
        installed
    }

    internal fun openedResult(installed: WorkspaceCapture<io.github.psd2live.core.RigPreviewModel>) =
        WorkspaceMutationResult(installed.historyHead, installed.revision, emptyList(), "Project opened",
            state = installed.state, projectId = installed.projectId)

    override suspend fun openProjectAt(path: Path, discardUnsaved: Boolean): WorkspaceMutationResult {
        require(path.isAbsolute && Files.isRegularFile(path)) { "Provide an absolute project archive path" }
        val installed = projectController.open(this, path, discardUnsaved)
        return openedResult(installed)
    }

    override suspend fun saveProject(): WorkspaceMutationResult = saveProjectAt()

    override suspend fun saveProjectAt(path: Path?): WorkspaceMutationResult {
        require(path == null || path.isAbsolute) { "Provide an absolute project archive path" }
        captureForMutation()
        val author = mutationAuthor(MutationAuthor.AGENT)
        val target = path ?: viewModel.state.value.projectFile?.let(Path::of)
            ?: error("Choose a project archive path with save-as first")
        val saved = projectController.saveCapture(this, target, actor = author.historyActor)
        return savedResult(saved)
    }
    override suspend fun checkpoint(summary: String): WorkspaceMutationResult {
        require(summary.isNotBlank()) { "Checkpoint summary is required" }
        // The one deliberate exception: a checkpoint exists to pin a moment, so it appends even when the
        // workspace already matches HEAD. Every other commit path records a change or records nothing.
        val capture = captureProject(summary, "agent", alwaysCommit = true)
        val node = capture.history.selections.last().node
        return WorkspaceMutationResult(node.id, node.revisionId, emptyList(), summary, state = capture.runtimeState, projectId = capture.projectId)
    }

    override fun captureQueries(): WorkspaceQueries {
        val read = runtime.read()
        val ui = viewModel.state.value
        val sameProject = ui.projectId == read.runtime.capture?.projectId
        return WorkspaceReadSession(read, WorkspaceQueryPresentation(
            workspaceId = ui.activeWorkspace.id,
            inputName = if (sameProject) ui.projectSourceName ?: workspaceInputPath(ui).takeIf(String::isNotBlank)
                ?.let { runCatching { Path.of(it).fileName.toString() }.getOrNull() } else null,
            busy = ui.isAnalyzing || ui.isGenerating || recoveringProjectId != null,
            status = ui.statusText,
            selectedLayerId = ui.selectedLayerId.takeIf { sameProject },
            projectFile = ui.projectFile.takeIf { sameProject },
            projectDirty = sameProject && ui.projectDirty,
            projectSaving = sameProject && ui.projectSaving,
            projectSaveError = ui.projectSaveError.takeIf { sameProject },
            persistenceStatus = when {
                persistenceError != null -> "error"
                recoveringProjectId != null -> "restoring"
                pendingPersistenceWrites.get() > 0 -> "saving"
                else -> "ready"
            },
            persistenceError = persistenceError,
        ))
    }

	override fun snapshot(): WorkspaceProjectSnapshot = captureQueries().snapshot()

    override fun history(): WorkspaceHistorySnapshot = captureQueries().history()

    override suspend fun renderLayer(layerId: String, background: WorkspaceViewBackground, output: WorkspaceViewOutputSpec): WorkspaceRenderedView =
        captureObservation().renderLayer(layerId, background, output)

    override suspend fun renderContext(layerId: String, objectScale: Float, aspectRatio: Float, background: WorkspaceViewBackground,
                                       output: WorkspaceViewOutputSpec): WorkspaceRenderedView =
        captureObservation().renderContext(layerId, objectScale, aspectRatio, background, output)

    override suspend fun renderModel(request: WorkspaceModelViewRequest): WorkspaceRenderedView =
        captureObservation().renderModel(request)
    override suspend fun assetWorkflow(operation: String, arguments: JsonObject): WorkspaceWorkflowResult = editMutex.withLock {
        if (operation == "asset_preview_composite") {
            val api = assetSessions.captureWorkflow(workspaceStore)
            withContext(Dispatchers.Default) { api.preview(arguments) }
        } else commitAsset(operation, arguments)
    }

    private suspend fun commitAsset(operation: String, arguments: JsonObject): WorkspaceWorkflowResult {
        val captured = captureForMutation()
        requireExpected(arguments.getValue("state").jsonPrimitive.content, captured)
        val current = viewModel.state.value
        if (current.isAnalyzing || current.isGenerating || recoveringProjectId != null) throw WorkspaceBusy()
        return assetSessions.execute(captured.projectId, captured.state, workspaceStore, operation, arguments) {
            val latest = viewModel.state.value
            check(latest.projectId == current.projectId && latest.projectOpenGeneration == current.projectOpenGeneration) {
                "Asset candidate belongs to an old project load"
            }
            viewModel.markProjectAuxiliaryChanged()
        }
    }

    override suspend fun setLayerPlacement(layerId: String, registrationId: String, expectedState: String, taskId: String?) =
        assetLayer(WorkspaceDocumentOperation("layer_set_placement", buildJsonObject {
            put("layer_id", layerId); put("registration_id", registrationId)
        }), expectedState, "Repositioned layer $layerId under existing parent", taskId)

    override suspend fun finalizeLayerPlacement(layerId: String, expectedState: String, taskId: String?) =
        assetLayer(WorkspaceDocumentOperation("layer_finalize_placement", buildJsonObject { put("layer_id", layerId) }),
            expectedState, "Finalized placement $layerId (existing parent retained)", taskId)

    override suspend fun inspectAsset(assetId: String): WorkspaceAssetPreview = editMutex.withLock {
        val api = assetSessions.captureWorkflow(workspaceStore)
        withContext(Dispatchers.Default) { api.inspect(assetId) }
    }

    override suspend fun importPng(arguments: JsonObject): WorkspaceWorkflowResult = editMutex.withLock {
        commitAsset("asset_import_png", arguments)
    }

    override suspend fun addLayer(request: WorkspaceAddLayerRequest): WorkspaceMutationResult =
        assetLayer(request.documentOperation(), request.expectedState, "Added generated layer '${request.name.trim()}'", request.taskId)

    private suspend fun assetLayer(operation: WorkspaceDocumentOperation, expectedState: String, summary: String,
                                   taskId: String?): WorkspaceMutationResult = editMutex.withLock {
        val before = captureForMutation()
        requireExpected(expectedState, before)
        val current = viewModel.state.value
        if (current.isAnalyzing || current.isGenerating || recoveringProjectId != null) throw WorkspaceBusy()
        val result = assetLayerCommands.execute(before.projectId, before.state, operation, summary, mutationAuthor(MutationAuthor.AGENT), taskId) {
                _, document, model -> applyPreviewOrThrow(model, documentFrom(current), document, summary, current)
        }
        if (result.commit.applied) {
            scheduleHistoryPersistence(before.projectId)
            viewModel.updateHistorySnapshot(history())
            viewModel.refreshWorkspaceRenderer(result.commit.capture.model)
        }
        result.mutation
    }

    override fun captureTextures(): WorkspaceTextureView = WorkspaceTextureView(runtime.capture())

    override suspend fun editTexture(state: String, edit: WorkspaceTextureEdit, author: MutationAuthor): WorkspaceTextureResult = editMutex.withLock {
        val before = captureForMutation(); requireExpected(state, before)
        require(recoveringProjectId != before.projectId) { "Workspace is still being restored; retry shortly" }
        val ui = viewModel.state.value
        if (ui.isAnalyzing || ui.isGenerating) throw WorkspaceBusy()
        val result = textureCommands.execute(before.projectId, before.state, edit, mutationAuthor(author)) { _, document, model ->
            applyPreviewOrThrow(model, documentFrom(ui), document, "Texture edit: ${edit.operation}", ui)
        }
        if (result.commit.applied) {
            scheduleHistoryPersistence(before.projectId); viewModel.updateHistorySnapshot(history()); viewModel.refreshWorkspaceRenderer(result.commit.capture.model)
        }
        result.result
    }

	override suspend fun deleteLayer(
		layerId: String,
		expectedState: String,
		taskId: String?,
	): WorkspaceMutationResult = layerMembership(WorkspaceDocumentOperation("layer_delete", buildJsonObject {
        put("layer_id", layerId)
    }), expectedState, "Deleted layer $layerId", taskId)

    override suspend fun restoreDeletedLayers(layerIds: List<String>?, expectedState: String, taskId: String?): WorkspaceMutationResult =
        layerMembership(WorkspaceDocumentOperation("layer_restore", buildJsonObject {
            layerIds?.let { put("layer_ids", JsonArray(it.map(::JsonPrimitive))) }
        }), expectedState, "Restored deleted layers", taskId)

    private suspend fun layerMembership(operation: WorkspaceDocumentOperation, expectedState: String, summary: String,
                                        taskId: String?): WorkspaceMutationResult = editMutex.withLock {
        val before = captureForMutation()
        requireExpected(expectedState, before)
        require(recoveringProjectId != before.projectId) { "Workspace is still being restored; retry shortly" }
        val current = viewModel.state.value
        if (current.isAnalyzing || current.isGenerating) throw WorkspaceBusy()
        val result = layerCommands.execute(before.projectId, before.state, operation, summary, mutationAuthor(MutationAuthor.AGENT), taskId) { _, document, model ->
            applyPreviewOrThrow(model, documentFrom(current), document, summary, current)
        }
        if (result.commit.applied) {
            scheduleHistoryPersistence(before.projectId)
            viewModel.updateHistorySnapshot(history())
            viewModel.refreshWorkspaceRenderer(result.commit.capture.model)
        }
        result.mutation
    }

    override suspend fun classifyLayer(
        layerId: String,
        fields: JsonObject,
        expectedState: String,
    ): WorkspaceMutationResult = generationCommand(WorkspaceDocumentOperation("layer_classify",
        JsonObject(fields + ("layer_id" to JsonPrimitive(layerId)))), expectedState, "Classified layer $layerId")

    private suspend fun generationCommand(operation: WorkspaceDocumentOperation, expectedState: String, summary: String): WorkspaceMutationResult = editMutex.withLock {
        val before = captureForMutation()
        requireExpected(expectedState, before)
        require(recoveringProjectId != before.projectId) { "Workspace is still being restored; retry shortly" }
        val current = viewModel.state.value
        if (current.isAnalyzing || current.isGenerating) throw WorkspaceBusy()
        val result = generationCommands.execute(before.projectId, before.state, operation, summary, mutationAuthor(MutationAuthor.AGENT),
            poses = { viewModel.projectWorkspacePoses(current, it) }) { _, document, model ->
            applyPreviewOrThrow(model, documentFrom(current), document, summary, current)
        }
        if (result.commit.applied) {
            scheduleHistoryPersistence(before.projectId)
            viewModel.updateHistorySnapshot(history())
            viewModel.refreshWorkspaceRenderer(result.commit.capture.model)
        }
        result.mutation
    }

	override suspend fun createParameter(request: WorkspaceCreateParameterRequest): WorkspaceMutationResult =
        mutateParameter(request.expectedState, request.id.trim(), request.taskId, "Created parameter ${request.id.trim()}") { document, model ->
            WorkspaceDocumentEdits.parameter(document, model, "create", kotlinx.serialization.json.buildJsonObject {
                put("parameter_id", request.id); put("name", request.name); put("min", request.min); put("max", request.max)
                put("default", request.default); put("kind", request.kind); put("repeat", request.repeat)
            })
        }

    override suspend fun updateParameter(request: WorkspaceUpdateParameterRequest): WorkspaceMutationResult =
        mutateParameter(request.expectedState, request.id.trim(), request.taskId, "Updated parameter ${request.id.trim()}") { document, model ->
            WorkspaceDocumentEdits.parameter(document, model, "update", kotlinx.serialization.json.buildJsonObject {
                put("parameter_id", request.id)
                request.name?.let { put("name", it) }; request.min?.let { put("min", it) }; request.max?.let { put("max", it) }
                request.default?.let { put("default", it) }; request.kind?.let { put("kind", it) }; request.repeat?.let { put("repeat", it) }
            })
        }

    override suspend fun deleteParameter(parameterId: String, expectedState: String, taskId: String?): WorkspaceMutationResult =
        mutateParameter(expectedState, parameterId.trim(), taskId, "Deleted parameter ${parameterId.trim()} and collapsed its keyform axes at the previous default") { document, model ->
            WorkspaceDocumentEdits.parameter(document, model, "delete", kotlinx.serialization.json.buildJsonObject { put("parameter_id", parameterId) })
        }

    private suspend fun mutateParameter(
        expectedState: String, parameterId: String, taskId: String?, summary: String,
        mutation: (WorkspaceDocument, io.github.psd2live.core.RigPreviewModel) -> WorkspaceDocument,
    ): WorkspaceMutationResult {
        val result = mutateModel(expectedState, taskId, summary, parameterId) { document, model ->
            mutation(document, model)
        }
        return result.copy(affectedParameterIds = if (result.applied) listOf(parameterId) else emptyList(),
            affectedObjectIds = emptyList())
    }

    override fun listRigObjectSummaries(): List<kotlinx.serialization.json.JsonObject> = captureQueries().listRigObjectSummaries()

    override suspend fun authorRig(state: String, edits: kotlinx.serialization.json.JsonArray, author: MutationAuthor): WorkspaceMutationResult = editMutex.withLock {
        val before = captureForMutation()
        if (state != before.state) throw WorkspaceConflict(state, before.state)
        val current = viewModel.state.value
        if (current.isAnalyzing || current.isGenerating) throw WorkspaceBusy()
        val summary = "Authored ${edits.size} ordered edits"
        val result = documentCommands.executeJournal(before.projectId, before.state, summary, edits, mutationAuthor(author)) { _, document, model ->
            if (document.rigEdits.assetLayers != before.document.rigEdits.assetLayers ||
                document.rigEdits.calibrationLayerIds != before.document.rigEdits.calibrationLayerIds)
                validateRegisteredNeutral(model, document.rigEdits.assetLayers.filter { (id, record) -> before.document.rigEdits.assetLayers[id] != record }.keys)
            applyPreviewOrThrow(model, documentFrom(current), document, summary, current)
        }
        if (result.applied) {
            draftQueue.forgetRejected()
            scheduleHistoryPersistence(before.projectId)
            viewModel.refreshWorkspaceRenderer(result.capture.model)
        }
        val ids = if (!result.applied) emptyList() else edits.mapNotNull { it.jsonObject["target"]?.jsonPrimitive?.content }.distinct()
        WorkspaceMutationResult(result.capture.historyHead, result.capture.revision, emptyList(), summary,
            affectedObjectIds = ids, applied = result.applied, state = result.capture.state, projectId = result.capture.projectId,
            geometryDiagnostics = result.geometryDiagnostics)
    }

    override suspend fun createArtwork(arguments: kotlinx.serialization.json.JsonObject): WorkspaceMutationResult = editMutex.withLock {
        val discard = arguments["discard_unsaved"]?.jsonPrimitive?.boolean ?: false
        val (state, current) = captureSourceImport(discard)
        val result = sourceImporter.createArtwork(arguments, state.capture?.projectId, state.state, discard,
            state.capture?.document?.rawConfig() ?: current.rawConfig()) { document, preview, id ->
            viewModel.applySourceImport(current, document, preview, null, id)
        }
        finishSourceImport(result)
        result.mutation("Created source artwork")
    }

    override fun captureObservation(): WorkspaceObservation {
        val (read, store, visible) = synchronized(historyLock) {
            val read = runtime.read()
            Triple(read, workspaceStore, read.runtime.capture?.let { projection(it).effectiveVisibleLayerIds.toSet() })
        }
        val projectId = read.runtime.capture?.projectId
        return WorkspaceObservationSession(read, previewBuilder, WorkspaceMotionSampler { bundle, parameters, frames, fps, progress, cancelled ->
            viewModel.sampleAgentMotion(bundle, parameters, frames, fps, progress, cancelled)
        }, { view -> remember(requireNotNull(projectId), store, view) }, visible)
    }
    override suspend fun splitArtwork(arguments: JsonObject, author: MutationAuthor): WorkspaceMutationResult =
        partitionSource(arguments.getValue("state").jsonPrimitive.content,
            listOf(WorkspaceDocumentOperation("source_split_polygon", JsonObject(arguments - "state"))), author)

    override suspend fun splitMeshComponents(state: String, splits: List<JsonObject>): WorkspaceMutationResult =
        partitionSource(state, splits.map { WorkspaceDocumentOperation("source_split_components", it) }, MutationAuthor.AGENT)

    override suspend fun splitDepth(state: String, request: JsonObject): WorkspaceMutationResult =
        partitionSource(state, listOf(WorkspaceDocumentOperation("source_split_depth", request)), MutationAuthor.AGENT)

    override fun sourceMeshComponents(layerId: String): JsonObject = captureQueries().sourceMeshComponents(layerId)

    private suspend fun partitionSource(expectedState: String, operations: List<WorkspaceDocumentOperation>, author: MutationAuthor): WorkspaceMutationResult = editMutex.withLock {
        val before = captureForMutation()
        requireExpected(expectedState, before)
        require(recoveringProjectId != before.projectId) { "Workspace is still being restored; retry shortly" }
        val current = viewModel.state.value
        if (current.isAnalyzing || current.isGenerating) throw WorkspaceBusy()
        val summary = "Partitioned ${operations.size} source layers"
        val result = partitionCommands.execute(before.projectId, before.state, operations, summary, mutationAuthor(author)) { _, document, model ->
            applyPreviewOrThrow(model, documentFrom(current), document, summary, current)
        }
        if (result.commit.applied) {
            scheduleHistoryPersistence(before.projectId)
            viewModel.updateHistorySnapshot(history())
            viewModel.refreshWorkspaceRenderer(result.commit.capture.model)
        }
        result.mutation
    }

    override suspend fun updateGeneration(state: String, author: MutationAuthor): JsonObject = editMutex.withLock {
        val before = captureForMutation()
        requireExpected(state, before)
        require(recoveringProjectId != before.projectId) { "Workspace is still being restored; retry shortly" }
        val current = viewModel.state.value
        if (current.isAnalyzing || current.isGenerating) throw WorkspaceBusy()
        val summary = "Updated the generated rig"
        val result = generationUpdateCommands.execute(before.projectId, before.state, summary, mutationAuthor(author)) { _, document, model ->
            applyPreviewOrThrow(model, documentFrom(current), document, summary, current)
        }
        if (result.commit.applied) {
            scheduleHistoryPersistence(before.projectId)
            viewModel.updateHistorySnapshot(history())
            viewModel.refreshWorkspaceRenderer(result.commit.capture.model)
        }
        result.result
    }

    override suspend fun importImages(state: String, paths: List<Path>, parentDeformerId: String?): WorkspaceMutationResult = editMutex.withLock {
        val before = captureForMutation()
        requireExpected(state, before)
        val current = viewModel.state.value
        if (current.isAnalyzing || current.isGenerating || recoveringProjectId != null) throw WorkspaceBusy()
        val summary = "Imported ${paths.size} image layers"
        val result = imageLayerCommands.importImages(before.projectId, before.state, paths, parentDeformerId, summary,
            mutationAuthor(MutationAuthor.USER)) { _, document, model ->
            applyPreviewOrThrow(model, documentFrom(current), document, summary, current)
        }
        if (result.commit.applied) {
            scheduleHistoryPersistence(before.projectId)
            viewModel.updateHistorySnapshot(history())
            viewModel.refreshWorkspaceRenderer(result.commit.capture.model)
        }
        result.mutation
    }

    override suspend fun paintSource(arguments: kotlinx.serialization.json.JsonObject): WorkspaceMutationResult {
        val id = arguments.getValue("layer_id").jsonPrimitive.content
        return rasterCommand(arguments.getValue("state").jsonPrimitive.content, id, arguments = arguments)
    }

    override suspend fun commitPaintRaster(state: String, request: WorkspacePaintRaster): WorkspaceMutationResult =
        rasterCommand(state, request.layerId, raster = request)

    override fun beginPaintSession(state: String, layerId: String): WorkspacePaintSession =
        paintSessions.begin(state, layerId)

    override fun listPaintSessions(): List<kotlinx.serialization.json.JsonObject> = paintSessions.list()

    override suspend fun controlPaintSession(request: kotlinx.serialization.json.JsonObject): WorkspaceWorkflowResult =
        kotlinx.coroutines.runInterruptible(kotlinx.coroutines.Dispatchers.Default) {
            paintSessions.get(request.getValue("session_id").jsonPrimitive.content).control(request)
        }

    override suspend fun commitPaintSession(state: String, sessionId: String, sessionState: String,
        rebuildMesh: Boolean, preserveSourceRaster: Boolean, author: MutationAuthor): WorkspaceMutationResult = editMutex.withLock {
        val before = captureForMutation()
        requireExpected(state, before)
        require(recoveringProjectId != before.projectId) { "Workspace is still being restored; retry shortly" }
        val current = viewModel.state.value
        if (current.isAnalyzing || current.isGenerating) throw WorkspaceBusy()
        val session = paintSessions.get(sessionId)
        require(session.projectId == before.projectId && session.workspaceState == state) { "Paint session belongs to another workspace state" }
        val summary = "Painted source layer "+session.layerId
        val result = paintSessions.commit(sessionId, sessionState, rebuildMesh, preserveSourceRaster, mutationAuthor(author)) { _, document, model ->
            applyPreviewOrThrow(model, documentFrom(current), document, summary, current)
        }
        if (result.commit.applied) {
            scheduleHistoryPersistence(before.projectId)
            viewModel.updateHistorySnapshot(history())
            viewModel.refreshWorkspaceRenderer(result.commit.capture.model)
        }
        result.mutation
    }

    private suspend fun rasterCommand(expectedState: String, layerId: String, arguments: kotlinx.serialization.json.JsonObject? = null,
                                      raster: WorkspacePaintRaster? = null): WorkspaceMutationResult = editMutex.withLock {
        require((arguments == null) != (raster == null))
        val before = captureForMutation()
        requireExpected(expectedState, before)
        require(recoveringProjectId != before.projectId) { "Workspace is still being restored; retry shortly" }
        val current = viewModel.state.value
        if (current.isAnalyzing || current.isGenerating) throw WorkspaceBusy()
        val operation = arguments?.let {
            val mode = it.getValue("mode").jsonPrimitive.content
            WorkspaceDocumentOperation("source_paint_$mode", kotlinx.serialization.json.JsonObject(it - "mode"))
        }
        val summary = "Painted source layer $layerId"
        val project: (WorkspaceCapture<io.github.psd2live.core.RigPreviewModel>, WorkspaceDocument, io.github.psd2live.core.RigPreviewModel) -> Unit = { _, document, model ->
            applyPreviewOrThrow(model, documentFrom(current), document, summary, current)
        }
        val result = if (operation != null) rasterCommands.execute(before.projectId, before.state, operation, summary,
            mutationAuthor(MutationAuthor.AGENT), beforeCommit = project)
        else rasterCommands.commitRaster(before.projectId, before.state, requireNotNull(raster), summary,
            mutationAuthor(MutationAuthor.USER), beforeCommit = project)
        if (result.commit.applied) {
            scheduleHistoryPersistence(before.projectId)
            viewModel.updateHistorySnapshot(history())
            viewModel.refreshWorkspaceRenderer(result.commit.capture.model)
        }
        result.mutation
    }


    override fun currentPuppet(): org.umamo.runtime.model.PuppetModel? =
        captureQueries().currentPuppet()

    override fun inspectRigGeometry(arguments: kotlinx.serialization.json.JsonObject): kotlinx.serialization.json.JsonObject = captureQueries().inspectRigGeometry(arguments)

    override fun listPhysics(): List<io.github.psd2live.core.PhysicsGroup> = captureQueries().listPhysics()

    override fun listSwings() = captureQueries().listSwings()

    override fun generatedOverrideIssues() = captureQueries().generatedOverrideIssues()
    override fun supersededEntryNotes() = captureQueries().supersededEntryNotes()
    override fun regenerationIssues() = captureQueries().regenerationIssues()

    override fun listSimulations() = captureQueries().listSimulations()

    private suspend fun simulationCommand(operation: WorkspaceDocumentOperation, expectedState: String, summary: String,
                                          author: MutationAuthor = MutationAuthor.AGENT, taskId: String? = null,
                                          autoBake: Boolean? = null): Pair<WorkspaceMutationResult, kotlinx.serialization.json.JsonObject> = editMutex.withLock {
        val before = captureForMutation()
        requireExpected(expectedState, before)
        require(recoveringProjectId != before.projectId) { "Workspace is still being restored; retry shortly" }
        val current = viewModel.state.value
        if (current.isAnalyzing || current.isGenerating) throw WorkspaceBusy()
        val result = simulationCommands.execute(before.projectId, before.state, operation, summary, mutationAuthor(author), taskId, autoBake) { _, document, model ->
            applyPreviewOrThrow(model, documentFrom(current), document, summary, current)
        }
        if (result.commit.applied) {
            scheduleHistoryPersistence(before.projectId)
            viewModel.updateHistorySnapshot(history())
            viewModel.refreshWorkspaceRenderer(result.commit.capture.model)
        }
        result.mutation to result.report
    }

    override suspend fun putSimulation(arguments: kotlinx.serialization.json.JsonObject, expectedState: String, taskId: String?,
        autoBake: Boolean?): Pair<WorkspaceMutationResult, kotlinx.serialization.json.JsonObject> =
        simulationCommand(WorkspaceDocumentOperation("simulation_put", arguments), expectedState,
            "Set simulation ${arguments.getValue("id").jsonPrimitive.content}", taskId = taskId, autoBake = autoBake)

    override suspend fun applyModelPreset(preset: io.github.psd2live.core.sim.ModelPresets.Preset, layers: Set<String>, expectedState: String,
        author: MutationAuthor, autoBake: Boolean?): Pair<WorkspaceMutationResult, kotlinx.serialization.json.JsonObject> =
        simulationCommand(WorkspaceDocumentOperation("model_apply_preset", kotlinx.serialization.json.buildJsonObject {
            put("preset", preset.jsonName); put("layers", kotlinx.serialization.json.JsonArray(layers.map { kotlinx.serialization.json.JsonPrimitive(it) }))
        }), expectedState, "Applied model preset ${preset.jsonName}", author, autoBake = autoBake)

    override suspend fun restoreClassicHair(front: Boolean, expectedState: String, author: MutationAuthor, sway: Boolean) =
        simulationCommand(WorkspaceDocumentOperation("model_apply_preset", kotlinx.serialization.json.buildJsonObject {
            put("preset", if (front) "classic_front_hair" else "classic_back_hair"); put("sway", sway)
        }), expectedState, if (front) "Restored classic front hair sway" else "Restored classic back hair sway", author).first

    override suspend fun removeClothingPresets(expectedState: String, author: MutationAuthor) =
        simulationCommand(WorkspaceDocumentOperation("model_apply_preset", kotlinx.serialization.json.buildJsonObject { put("preset", "remove_clothing") }),
            expectedState, "Removed clothing simulation presets", author).first

    override suspend fun deleteSimulation(id: String, expectedState: String) =
        simulationCommand(WorkspaceDocumentOperation("simulation_delete", kotlinx.serialization.json.buildJsonObject { put("id", id) }),
            expectedState, "Deleted simulation $id").first

    override suspend fun bakeSimulation(id: String, expectedState: String): Pair<WorkspaceMutationResult, kotlinx.serialization.json.JsonObject> =
        simulationCommand(WorkspaceDocumentOperation("simulation_bake", kotlinx.serialization.json.buildJsonObject { put("id", id) }),
            expectedState, "Baked simulation $id")

    override suspend fun putSimulationBake(id: String, bake: io.github.psd2live.core.sim.SimBakeResult?, expectedState: String) =
        if (bake == null) simulationCommand(WorkspaceDocumentOperation("simulation_clear_bake", kotlinx.serialization.json.buildJsonObject { put("id", id) }),
            expectedState, "Cleared simulation bake $id").first
        else putSimulationBakes(mapOf(id to bake), expectedState, "Baked simulation $id", id)

    override suspend fun putSimulationBakes(bakes: Map<String, io.github.psd2live.core.sim.SimBakeResult?>, expectedState: String) =
        putSimulationBakes(bakes, expectedState,
            if (bakes.values.any { it != null }) "Baked simulations ${bakes.keys.joinToString()}" else "Cleared simulation bakes ${bakes.keys.joinToString()}",
            bakes.keys.firstOrNull() ?: "simulation")

    private suspend fun putSimulationBakes(bakes: Map<String, io.github.psd2live.core.sim.SimBakeResult?>, expectedState: String, summary: String, affected: String) =
        mutateRigKeyform(expectedState, null, summary, affected) { document, _ ->
            WorkspaceSimulationEdits.withBakes(document, bakes)
        }

    override fun compareSimulation(id: String, motions: List<String>, progress: (Float) -> Unit, cancelled: () -> Boolean): kotlinx.serialization.json.JsonObject =
        captureQueries().compareSimulation(id, motions, progress, cancelled)

    override fun reportSimulation(id: String, hold: Float, release: Float, wind: Pair<Float, Float>?, progress: (Float) -> Unit,
                                  cancelled: () -> Boolean): kotlinx.serialization.json.JsonObject = captureQueries().reportSimulation(id, hold, release, wind, progress, cancelled)

    override suspend fun controlSwingPreview(arguments: JsonObject, author: MutationAuthor): WorkspaceSwingPreview = editMutex.withLock {
        val capture = captureForMutation()
        requireExpected(arguments.getValue("state").jsonPrimitive.content, capture)
        val current = viewModel.state.value
        val result = swingSessions.execute(capture.projectId, capture.state, current.activeWorkspace.id, arguments, mutationAuthor(author)) { _, document, model ->
            applyPreviewOrThrow(model, documentFrom(current), document, "Set swing preview", current)
        }
        viewModel.applySwingPreview(result)
        if (arguments.getValue("mode").jsonPrimitive.content == "commit") {
            scheduleHistoryPersistence(capture.projectId)
            viewModel.updateHistorySnapshot(history())
            viewModel.refreshWorkspaceRenderer(runtime.capture().model)
        }
        result
    }

    override suspend fun controlSimulationPreview(arguments: JsonObject): WorkspaceSimulationPreview {
        val (capture, workspace) = synchronized(historyLock) { runtime.capture() to viewModel.state.value.activeWorkspace.id }
        requireExpected(arguments.getValue("state").jsonPrimitive.content, capture)
        val result = simulationPreviewSessions.control(capture.projectId, capture.state, workspace, arguments)
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        viewModel.applySimulationPreview(result)
        return result
    }

    override suspend fun stepSimulationPreview(arguments: JsonObject): WorkspaceSimulationPreview {
        val (capture, workspace) = synchronized(historyLock) { runtime.capture() to viewModel.state.value.activeWorkspace.id }
        requireExpected(arguments.getValue("state").jsonPrimitive.content, capture)
        val result = simulationPreviewSessions.step(capture.projectId, capture.state, workspace, arguments)
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        viewModel.applySimulationPreview(result)
        return result
    }

    override fun controlPhysicsAudition(arguments: JsonObject): JsonObject {
        val (capture, workspace) = synchronized(historyLock) { runtime.capture() to viewModel.state.value.activeWorkspace.id }
        requireExpected(arguments.getValue("state").jsonPrimitive.content, capture)
        return physicsAuditionSessions.control(capture.projectId, capture.state, workspace, arguments)
    }

    override fun stepPhysicsAudition(arguments: JsonObject): JsonObject {
        val (capture, workspace) = synchronized(historyLock) { runtime.capture() to viewModel.state.value.activeWorkspace.id }
        requireExpected(arguments.getValue("state").jsonPrimitive.content, capture)
        return physicsAuditionSessions.step(capture.projectId, capture.state, workspace, arguments)
    }

    override fun physicsAudition(sessionId: String): JsonObject = physicsAuditionSessions.get(
        runtime.capture().projectId, viewModel.state.value.activeWorkspace.id, sessionId)

    override fun simulationPreviewFrame(sessionId: String): WorkspaceSimulationPreview = simulationPreviewSessions.get(
        runtime.capture().projectId, viewModel.state.value.activeWorkspace.id, sessionId)

    override suspend fun renderSimulationPreview(sessionId: String, request: WorkspaceModelViewRequest): WorkspaceRenderedView {
        val (read, preview, store) = synchronized(historyLock) {
            val read = runtime.read(); val capture = requireNotNull(read.runtime.capture)
            val preview = simulationPreviewSessions.get(capture.projectId, viewModel.state.value.activeWorkspace.id, sessionId)
            if (preview.report.getValue("stale").jsonPrimitive.boolean) throw WorkspaceConflict(preview.report.getValue("state").jsonPrimitive.content, capture.state)
            Triple(read, preview, workspaceStore)
        }
        require(request.parameters.isEmpty()) { "A simulation observation uses its evaluated frame pose" }
        val capture = requireNotNull(read.runtime.capture)
        return WorkspaceRenderSession(read, { view -> remember(capture.projectId, store, view) }).renderModel(preview.model, capture.revision,
            request.copy(parameters = preview.values.mapKeys { it.key.raw }, includeLayerIds = request.includeLayerIds ?: capture.document.renderVisibleLayers(preview.model)),
            preview.frame.positions)
    }

    override fun swingPreviewFrame(sessionId: String, time: Float?): WorkspaceSwingPreview = swingSessions.get(viewModel.state.value.activeWorkspace.id, sessionId, time)

    override suspend fun renderSwingPreview(sessionId: String, request: WorkspaceModelViewRequest): WorkspaceRenderedView {
        val (read, preview, store) = synchronized(historyLock) {
            val read = runtime.read(); val capture = requireNotNull(read.runtime.capture)
            val preview = swingSessions.get(viewModel.state.value.activeWorkspace.id, sessionId)
            if (preview.report.getValue("state").jsonPrimitive.content != capture.state) throw WorkspaceConflict(preview.report.getValue("state").jsonPrimitive.content, capture.state)
            Triple(read, preview, workspaceStore)
        }
        val capture = requireNotNull(read.runtime.capture)
        return WorkspaceRenderSession(read, { view -> remember(capture.projectId, store, view) }).renderModel(preview.model, capture.revision,
            request.copy(parameters = preview.values.mapKeys { it.key.raw } + request.parameters,
                includeLayerIds = request.includeLayerIds ?: capture.document.renderVisibleLayers(preview.model)))
    }

    override suspend fun putSwing(edit: io.github.psd2live.core.RigSwingEdit, estimatePhysics: Boolean, expectedState: String,
        taskId: String?, author: MutationAuthor) =
        mutateRigKeyform(expectedState, taskId, "Set swing ${edit.id}", edit.id, author) { document, puppet ->
            WorkspaceDocumentEdits.swing(document, puppet, edit, estimatePhysics)
        }

    override suspend fun deleteSwing(id: String, bake: Boolean, expectedState: String, author: MutationAuthor) =
        mutateModel(expectedState, null, if (bake) "Baked swing $id" else "Deleted swing $id", id, author) { document, model ->
            WorkspaceDocumentEdits.removeSwing(document, model.rig.puppet, id, bake, model.authored.rig.puppet)
        }

    override suspend fun createIndependentWarp(request: JsonObject, expectedState: String) = independentWarp(request, expectedState)

    override suspend fun editWarpControls(operation: String, expectedState: String, request: JsonObject, author: MutationAuthor): JsonObject = editMutex.withLock {
        val before = captureForMutation()
        requireExpected(expectedState, before)
        require(recoveringProjectId != before.projectId) { "Workspace is still being restored; retry shortly" }
        val current = viewModel.state.value
        if (current.isAnalyzing || current.isGenerating) throw WorkspaceBusy()
        val result = warpControlCommands.execute(before.projectId, before.state, WorkspaceDocumentOperation(operation, request), mutationAuthor(author)) { _, document, model ->
            applyPreviewOrThrow(model, documentFrom(current), document, "Edit Warp controls", current)
        }
        if (result.commit.applied) {
            scheduleHistoryPersistence(before.projectId)
            viewModel.updateHistorySnapshot(history())
            viewModel.refreshWorkspaceRenderer(result.commit.capture.model)
        }
        result.output
    }

    private suspend fun independentWarp(request: JsonObject, expectedState: String): WorkspaceMutationResult = editMutex.withLock {
        val before = captureForMutation()
        requireExpected(expectedState, before)
        require(recoveringProjectId != before.projectId) { "Workspace is still being restored; retry shortly" }
        val current = viewModel.state.value
        if (current.isAnalyzing || current.isGenerating) throw WorkspaceBusy()
        val result = warpCommands.execute(before.projectId, before.state, request, mutationAuthor(MutationAuthor.AGENT)) { _, document, model ->
            applyPreviewOrThrow(model, documentFrom(current), document, "Created independent Warp", current)
        }
        if (result.commit.applied) {
            scheduleHistoryPersistence(before.projectId)
            viewModel.updateHistorySnapshot(history())
            viewModel.refreshWorkspaceRenderer(result.commit.capture.model)
        }
        result.mutation
    }

    override suspend fun putPhysics(arguments: kotlinx.serialization.json.JsonObject, expectedState: String, taskId: String?): WorkspaceMutationResult {
        val id = arguments["id"]?.jsonPrimitive?.contentOrNull ?: throw IllegalArgumentException("id is required")
        return physicsCommand(WorkspaceDocumentOperation("physics_put", arguments), expectedState, "Set physics $id", taskId).first
    }

    override fun simulatePhysics(arguments: kotlinx.serialization.json.JsonObject, progress: (Float) -> Unit,
                                 cancelled: () -> Boolean): kotlinx.serialization.json.JsonObject = captureQueries().simulatePhysics(arguments, progress, cancelled)

    override fun physicsFps() = captureQueries().physicsFps()

    override suspend fun configurePhysics(order: List<String>?, fps: Int?, expectedState: String): WorkspaceMutationResult {
        return physicsCommand(WorkspaceDocumentOperation("physics_config", kotlinx.serialization.json.buildJsonObject {
            order?.let { put("order", kotlinx.serialization.json.JsonArray(it.map { id -> kotlinx.serialization.json.JsonPrimitive(id) })) }
            fps?.let { put("fps", it) }
        }), expectedState, "Configured physics").first
    }

    override suspend fun importPhysics(path: String, expectedState: String): Pair<WorkspaceMutationResult, kotlinx.serialization.json.JsonObject> {
        return physicsCommand(WorkspaceDocumentOperation("physics_import", kotlinx.serialization.json.buildJsonObject { put("path", path) }),
            expectedState, "Imported physics ${Path.of(path).fileName}")
    }

    override suspend fun fitPhysics(id: String, target: Float, expectedState: String,
                                    observedPeaks: Map<Int, Float>?): WorkspaceMutationResult =
        physicsCommand(WorkspaceDocumentOperation("physics_fit", kotlinx.serialization.json.buildJsonObject {
            put("id", id); put("target", target * 100f)
            observedPeaks?.let { peaks -> put("observed_peaks", kotlinx.serialization.json.buildJsonObject {
                peaks.toSortedMap().forEach { (index, reach) -> put(index.toString(), reach) }
            }) }
        }), expectedState, "Fitted physics scales $id").first

    private suspend fun physicsCommand(operation: WorkspaceDocumentOperation, expectedState: String, summary: String,
                                       taskId: String? = null): Pair<WorkspaceMutationResult, kotlinx.serialization.json.JsonObject> = editMutex.withLock {
        val before = captureForMutation()
        requireExpected(expectedState, before)
        require(recoveringProjectId != before.projectId) { "Workspace is still being restored; retry shortly" }
        val current = viewModel.state.value
        if (current.isAnalyzing || current.isGenerating) throw WorkspaceBusy()
        val result = physicsCommands.execute(before.projectId, before.state, operation, summary, mutationAuthor(MutationAuthor.AGENT), taskId) { _, document, model ->
            applyPreviewOrThrow(model, documentFrom(current), document, summary, current)
        }
        if (result.commit.applied) {
            scheduleHistoryPersistence(before.projectId)
            viewModel.updateHistorySnapshot(history())
            viewModel.refreshWorkspaceRenderer(result.commit.capture.model)
        }
        result.mutation to result.report
    }

    override suspend fun editPhysics(intent: WorkspacePhysicsIntent, expectedState: String): Pair<WorkspaceMutationResult, kotlinx.serialization.json.JsonObject> = editMutex.withLock {
        val before = captureForMutation()
        requireExpected(expectedState, before)
        require(recoveringProjectId != before.projectId) { "Workspace is still being restored; retry shortly" }
        val current = viewModel.state.value
        if (current.isAnalyzing || current.isGenerating) throw WorkspaceBusy()
        val summary = "Edit physics group"
        val result = physicsCommands.executeIntent(before.projectId, before.state, intent, summary, mutationAuthor(MutationAuthor.USER)) { _, document, model ->
            applyPreviewOrThrow(model, documentFrom(current), document, summary, current)
        }
        if (result.commit.applied) {
            scheduleHistoryPersistence(before.projectId)
            viewModel.updateHistorySnapshot(history())
            viewModel.refreshWorkspaceRenderer(result.commit.capture.model)
        }
        result.mutation to result.report
    }

	override fun getObject(target: WorkspaceKeyformTargetRef): WorkspaceObjectSnapshot = captureQueries().getObject(target)


	private suspend fun mutateRigKeyform(
		expectedState: String,
		taskId: String?,
		summary: String,
		affectedObjectId: String,
		// Defaults to the Agent because only [authorRig] is shared with the editor; every other
		// caller here is an MCP tool.
		author: MutationAuthor = MutationAuthor.AGENT,
		mutation: (WorkspaceDocument, PuppetModel) -> WorkspaceDocument,
	): WorkspaceMutationResult = mutateModel(expectedState, taskId, summary, affectedObjectId, author) { document, model ->
        mutation(document, model.rig.puppet)
    }

    private suspend fun mutateModel(
        expectedState: String, taskId: String?, summary: String, affectedObjectId: String,
        author: MutationAuthor = MutationAuthor.AGENT,
        mutation: (WorkspaceDocument, io.github.psd2live.core.RigPreviewModel) -> WorkspaceDocument,
    ): WorkspaceMutationResult = editMutex.withLock {
        val before = captureForMutation()
        requireExpected(expectedState, before)
        require(recoveringProjectId != before.projectId) { "Workspace is still being restored; retry shortly" }
        val current = viewModel.state.value
        if (current.isAnalyzing || current.isGenerating) throw WorkspaceBusy()
        val result = documentCommands.executeCandidate(before.projectId, before.state, summary, mutationAuthor(author),
            taskId, mutation, beforeCommit = { _, document, model ->
            if (document.rigEdits.assetLayers != before.document.rigEdits.assetLayers ||
                document.rigEdits.calibrationLayerIds != before.document.rigEdits.calibrationLayerIds)
                validateRegisteredNeutral(model, document.rigEdits.assetLayers.filter { (id, record) -> before.document.rigEdits.assetLayers[id] != record }.keys)
            applyPreviewOrThrow(model, documentFrom(current), document, summary, current)
        })
        if (result.applied) {
            scheduleHistoryPersistence(before.projectId)
            viewModel.updateHistorySnapshot(history())
            viewModel.refreshWorkspaceRenderer(result.capture.model)
        }
        WorkspaceMutationResult(result.capture.historyHead, result.capture.revision, emptyList(), summary,
            affectedObjectIds = if (result.applied) (listOf(affectedObjectId) +
                WorkspaceDocumentCommands.createdObjectIds(before.model.rig.puppet, result.capture.model.rig.puppet)).distinct() else emptyList(),
            applied = result.applied, state = result.capture.state, projectId = result.capture.projectId,
            geometryDiagnostics = result.geometryDiagnostics)
    }

    override suspend fun checkoutHistory(nodeId: String, author: MutationAuthor): WorkspaceMutationResult = editMutex.withLock {
        val before = captureForMutation()
        val current = viewModel.state.value
        val selected = runtime.checkout(before.projectId, before.state, nodeId) { _, document, model ->
            applyPreviewOrThrow(model, documentFrom(current), document, "Checked out history node $nodeId", current)
        }
        discardEditorQueues()
        scheduleHistoryPersistence(before.projectId)
        viewModel.refreshWorkspaceRenderer(selected.model)
        WorkspaceMutationResult(selected.historyHead, selected.revision, emptyList(), "Checked out history node $nodeId",
            applied = before.historyHead != selected.historyHead, state = selected.state, projectId = selected.projectId)
    }


	private fun projectId(state: PSD2LiveState): String {
        state.projectId?.let { return it }
		val identity = buildString {
			append(normalizedPath(workspaceInputPath(state)))
			workspaceFileSignature(state)?.let { append("|file:").append(it) }
		}
		return "project-${sha256(identity).take(16)}"
	}

	private fun revisionId(state: PSD2LiveState): String {
		val analysis = state.analysis ?: return "revision-${sha256("unloaded|${normalizedPath(state.inputPath)}").take(16)}"
		return revisionId(state, documentFrom(state).copy(source = analysis.source))
	}

	private fun revisionId(state: PSD2LiveState, document: WorkspaceDocument): String =
        io.github.psd2live.project.WorkspaceRevisions.of(document)

	private fun documentFrom(state: PSD2LiveState): WorkspaceDocument = WorkspaceStateCodec.document(state)


	private fun taskManagerFor(projectId: String): WorkspaceTaskRecords = synchronized(historyLock) {
		if (taskProjectId != projectId) {
			val restored = try {
				workspaceStore.loadTasks(projectId)
			} catch (failure: Exception) {
				persistenceError = failure.message ?: failure.javaClass.simpleName
				throw IllegalStateException("Unable to load persisted Agent tasks: ${persistenceError}", failure)
			}
			taskProjectId = projectId
			taskManager = WorkspaceTaskRecords().also { it.restore(restored) }
		}
		taskManager
	}

    private fun scheduleHistoryPersistence(projectId: String) {
        draftQueue.forgetRejected()
        val captured = runtime.history()
        val store = workspaceStore
        schedulePersistence { store.persistHistory(projectId, captured) }
        viewModel.updateHistorySnapshot(history())
    }

	private fun scheduleTaskPersistence(projectId: String, manager: WorkspaceTaskRecords) {
		val tasks = manager.list()
        val store = workspaceStore
        viewModel.markProjectAuxiliaryChanged()
		schedulePersistence { store.persistTasks(projectId, tasks) }
	}

	private fun schedulePersistence(block: () -> Unit) {
		pendingPersistenceWrites.incrementAndGet()
		persistenceScope.launch {
			try {
				block()
				persistenceError = null
			} catch (failure: Exception) {
				persistenceError = failure.message ?: failure.javaClass.simpleName
			} finally {
				pendingPersistenceWrites.decrementAndGet()
			}
		}
	}


    private fun remember(projectId: String, capturedStore: WorkspaceStore, view: WorkspaceRenderedView): WorkspaceRenderedView = view.also {
        synchronized(historyLock) {
            if (runtime.state.value.capture?.projectId == projectId) spatialByViewId[it.viewId] = it.spatial
        }
        // Rendering may create an observation resource, but never edits the project or its UI log.
        schedulePersistence { capturedStore.persistView(projectId, it) }
    }

	private fun applyPreviewOrThrow(
		preview: io.github.psd2live.core.RigPreviewModel,
		expected: WorkspaceDocument,
		next: WorkspaceDocument,
		status: String,
        expectedUi: PSD2LiveState = viewModel.state.value,
	) {
		if (!applyPreview(preview, expected, next, status, expectedUi)) {
			throw IllegalStateException("Workspace changed while the operation was being built; retry from current state")
		}
	}

	private fun applyPreview(
		preview: io.github.psd2live.core.RigPreviewModel,
		expected: WorkspaceDocument,
		next: WorkspaceDocument,
		status: String,
        expectedUi: PSD2LiveState = viewModel.state.value,
	): Boolean = viewModel.applyAgentWorkspacePreview(
            expectedProjectId = expectedUi.projectId,
            expectedProjectOpenGeneration = expectedUi.projectOpenGeneration,
			preview = preview,
			expectedSource = expected.source,
			expectedLayerVisibility = expected.layerVisibility,
			expectedDeletedLayerIds = expected.deletedLayerIds,
			expectedLayerOverrides = expected.layerOverrides,
			expectedParentOverrides = expected.parentOverrides,
			expectedRigEdits = expected.rigEdits,
            expectedGenerationSource = expected.generationSource,
            expectedMeshSource = expected.meshSource,
            expectedTextureOverrides = expected.textureOverrides,
            expectedSettings = expected.settings,
			expectedMeshOverrides = expected.meshOverrides,
			layerVisibility = next.layerVisibility,
			deletedLayerIds = next.deletedLayerIds,
			layerOverrides = next.layerOverrides,
			parentOverrides = next.parentOverrides,
			rigEdits = next.rigEdits,
            generationSource = next.generationSource,
            meshSource = next.meshSource,
            textureOverrides = next.textureOverrides,
			status = status,
            settings = next.settings,
			meshOverrides = next.meshOverrides,
		)



	private fun normalizedPath(inputPath: String): String = runCatching {
		Path.of(inputPath).toAbsolutePath().normalize().toString()
	}.getOrDefault(inputPath)

	private fun workspaceInputPath(state: PSD2LiveState): String = state.loadedInputPath ?: state.inputPath

	private fun workspaceFileSignature(state: PSD2LiveState): String? =
		state.loadedInputFileSignature ?: fileSignature(workspaceInputPath(state))

	private fun fileSignature(inputPath: String): String? = runCatching {
		val path = Path.of(inputPath)
		"${Files.size(path)}:${Files.getLastModifiedTime(path).toMillis()}"
	}.getOrNull()

	private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
		.digest(value.toByteArray(StandardCharsets.UTF_8))
		.joinToString("") { "%02x".format(it.toInt() and 0xff) }

	private fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
		.digest(value)
		.joinToString("") { "%02x".format(it.toInt() and 0xff) }

	private val isClosed = AtomicBoolean(false)

	override fun close() {
		if (!isClosed.compareAndSet(false, true)) return
        discardEditorQueues()
        draftScope.cancel()
		runCatching {
			runBlocking {
				// Queued history and task writes are the recovery data; let them land rather than drop them.
				persistenceJob.complete()
				withTimeoutOrNull(5_000L) { persistenceJob.join() }
			}
		}
		persistenceScope.cancel()
	}
}
