package io.github.psd2live.ui.state

import io.github.psd2live.application.CanvasAddress
import io.github.psd2live.application.CanvasVisibility
import io.github.psd2live.application.CanvasVisibilityCodec
import io.github.psd2live.application.PendingPoseCommit
import io.github.psd2live.application.PreviewSessions
import io.github.psd2live.application.SessionRecovery
import io.github.psd2live.application.WorkspaceBackend
import io.github.psd2live.application.WorkspaceCapture
import io.github.psd2live.application.WorkspaceCloseReason
import io.github.psd2live.application.WorkspaceHost
import io.github.psd2live.application.WorkspaceHostFacts
import io.github.psd2live.application.WorkspaceHostPresentation
import io.github.psd2live.application.WorkspaceImportedSource
import io.github.psd2live.application.WorkspaceProjectStep
import io.github.psd2live.application.WorkspaceSimulationPreview
import io.github.psd2live.application.WorkspaceSwingPreview
import io.github.psd2live.core.CubismRuntimeBundle
import io.github.psd2live.core.PipelineConfig
import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.history.WorkspaceHistoryTree
import io.github.psd2live.i18n.tr
import io.github.psd2live.project.WorkspaceAuxiliaryCodec
import io.github.psd2live.project.WorkspaceAuxiliaryData
import io.github.psd2live.project.WorkspaceDocument
import io.github.psd2live.project.WorkspaceHistorySnapshot
import io.github.psd2live.project.WorkspaceStore
import io.github.psd2live.project.WorkspaceTaskSnapshot
import io.github.psd2live.project.rawConfig
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.umamo.runtime.model.ParameterId
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * The desktop editor's workspace: the application [WorkspaceHost], shown through the view model's state. The runtime
 * owns the document; the editor's document fields are its projection plus field drafts the editor submits through
 * the draft queue, carried over each commit it is shown ([DesktopPresentation.show]).
 */
class DesktopWorkspace private constructor(
    private val viewModel: PSD2LiveViewModel,
    internal val host: WorkspaceHost<PSD2LiveState>,
) : WorkspaceBackend by host, AutoCloseable {
    constructor(viewModel: PSD2LiveViewModel, storeRoot: Path = WorkspaceStore.defaultRoot()) :
        this(viewModel, WorkspaceHost(DesktopPresentation(viewModel), storeRoot, viewModel.lifecycle))

    /** Names the project whose unsaved edits only the store holds, so a killed app can offer them back. */
    internal val sessionRecovery: SessionRecovery get() = host.sessionRecovery

    /** The committed HEAD, read without the runtime lock. */
    internal fun currentHistoryHead(): String? = host.currentHistoryHead()
    internal fun attachCurrentWorkspace() = host.attachCurrentWorkspace()
    internal suspend fun importedPsd(recoverLegacy: Boolean = true) = host.importedPsd(recoverLegacy)
    internal suspend fun flushProjectPersistence() = host.flushProjectPersistence()
    internal fun rememberProjectDirectory(path: Path) = host.rememberProjectDirectory(path)
    internal fun prepareClose(reason: WorkspaceCloseReason) = host.prepareClose(reason)
    internal fun projectPresentationChanged(state: PSD2LiveState) = host.projectPresentationChanged(state)
    internal suspend fun restoreSession(session: SessionRecovery.Session) = host.restoreSession(session)
    internal suspend fun captureProjectOpen(discardUnsaved: Boolean) = host.captureProjectOpen(discardUnsaved)

    internal suspend fun installProject(
        id: String, file: Path?, source: Path, state: PSD2LiveState,
        tree: WorkspaceHistoryTree<WorkspaceDocument>, store: WorkspaceStore,
        expectation: WorkspaceHost.ProjectOpenExpectation<PSD2LiveState>, discardUnsaved: Boolean,
        auxiliary: JsonObject = JsonObject(emptyMap()), dirty: Boolean = false, restoredTasks: List<WorkspaceTaskSnapshot>? = null,
    ) = host.installProject(id, file, source, state, tree, store, expectation, discardUnsaved, auxiliary, dirty, restoredTasks)

    /** Opens the archive [path] as the workspace's project. */
    internal suspend fun open(path: Path, discardUnsaved: Boolean = true,
                              repository: io.github.psd2live.project.ProjectRepository = io.github.psd2live.project.ProjectRepository()) =
        host.openProject(path, discardUnsaved, repository)

    /** Saves the workspace to the archive [path]; returns the saved history head. */
    internal suspend fun save(path: Path, actor: String = "user",
                              repository: io.github.psd2live.project.ProjectRepository = io.github.psd2live.project.ProjectRepository()): String =
        host.saveCapture(path, actor, repository).history.headNodeId

    override fun close() = host.close()
}

/** The view model's state as a [WorkspaceHost]'s presentation. */
internal class DesktopPresentation(private val viewModel: PSD2LiveViewModel) : WorkspaceHostPresentation<PSD2LiveState> {
    override fun current(): PSD2LiveState = viewModel.state.value
    override fun <T> withCurrent(read: (PSD2LiveState) -> T): T = viewModel.withProjectState(read)

    override fun facts(view: PSD2LiveState) = WorkspaceHostFacts(
        projectId = view.projectId, openGeneration = view.projectOpenGeneration, analyzing = view.isAnalyzing,
        generating = view.isGenerating, saving = view.projectSaving, dirty = view.projectDirty,
        canvasEditBusy = view.canvasEditBusy, workspaceEditBusy = view.workspaceEditBusy,
        activeWorkspaceId = view.activeWorkspace.id, workspaceIds = view.workspaces.map { it.id },
        canvasAddresses = {
            view.workspaces.flatMap { workspace ->
                workspace.canvases.flatMap { canvas -> CanvasMode.entries.map { CanvasAddress(workspace.id, canvas.id, it.canvasViewMode()) } }
            }
        },
        mouseTracking = view.mouseTrackingEnabled, smoothTracking = view.smoothMouseTracking,
        sourceName = view.projectSourceName, inputPath = view.loadedInputPath ?: view.inputPath, projectFile = view.projectFile,
        status = view.statusText, selectedLayerId = view.selectedLayerId, saveError = view.projectSaveError,
        saveIdentity = { view.saveIdentity })

    override fun visibleLayers(capture: WorkspaceCapture<RigPreviewModel>, view: PSD2LiveState): Set<String> =
        projection(capture, view).effectiveVisibleLayerIds.toSet()

    /** Canvas records are read from the same v1 presentation the project archive stores. */
    override fun auxiliary(view: PSD2LiveState): JsonObject = CanvasVisibilityCodec.withRecords(JsonObject(
        WorkspaceAuxiliaryCodec.encode(view.savedProjectData()) +
            ("posesByWorkspace" to buildJsonObject {
                view.workspaces.forEach { workspace ->
                    val pose = workspace.pose
                    val values = if (workspace.id == view.activeWorkspace.id) view.parameterValues else pose?.parameterValues.orEmpty()
                    val locks = if (workspace.id == view.activeWorkspace.id) view.lockedParameters else pose?.lockedParameters.orEmpty()
                    put(workspace.id, PreviewSessions.encode(io.github.psd2live.application.WorkspacePose(values, locks)))
                }
            })), CanvasVisibilityCodec.fromPresentation(WorkspaceStateCodec.encode(view)))

    override fun encode(capture: WorkspaceCapture<RigPreviewModel>, view: PSD2LiveState, checkpoint: Boolean): JsonObject =
        if (checkpoint) WorkspaceStateCodec.encode(projection(capture, view), includeLogs = false, includePreviewControls = false)
        else WorkspaceStateCodec.encode(projection(capture, view))

    override fun decode(presentation: JsonObject?): PSD2LiveState =
        presentation?.let(WorkspaceStateCodec::decode) ?: viewModel.state.value

    override fun config(view: PSD2LiveState): PipelineConfig = view.rawConfig()

    override fun basis(view: PSD2LiveState): Pair<RigPreviewModel?, Map<String, Float>> = view.previewModel to view.drawOrderOverrides

    /** [ui] with [document]'s fields in place of the ones it shows. */
    private fun withDocument(ui: PSD2LiveState, document: WorkspaceDocument): PSD2LiveState =
        WorkspaceStateCodec.decode(document.settings, ui).copy(
            documentLayerVisibility = document.layerVisibility, deletedLayerIds = document.deletedLayerIds,
            layerOverrides = document.layerOverrides, parentOverrides = document.parentOverrides,
            rigEdits = document.rigEdits, meshOverrides = document.meshOverrides,
            generationSource = document.generationSource, meshSource = document.meshSource,
            textureOverrides = document.textureOverrides, layout = document.layout)

    /** The editor's state over [capture]: the archive's presentation of the runtime's document. */
    private fun projection(capture: WorkspaceCapture<RigPreviewModel>, ui: PSD2LiveState): PSD2LiveState {
        val auxiliary = WorkspaceAuxiliaryCodec.decode(capture.auxiliary)
        val persisted = WorkspaceStateCodec.decode(capture.document.settings, ui).copy(
            parameterSnapshots = auxiliary.parameterSnapshots, historyAnnotations = auxiliary.historyAnnotations,
            canvasGuides = auxiliary.canvasGuides,
            timelineAids = auxiliary.timelineAids,
            projectId = capture.projectId, analysis = capture.model.analysis, previewModel = capture.model,
            documentLayerVisibility = capture.document.layerVisibility, deletedLayerIds = capture.document.deletedLayerIds,
            layerOverrides = capture.document.layerOverrides, parentOverrides = capture.document.parentOverrides,
            rigEdits = capture.document.rigEdits, meshOverrides = capture.document.meshOverrides,
            generationSource = capture.document.generationSource,
            meshSource = capture.document.meshSource,
            textureOverrides = capture.document.textureOverrides,
            layout = capture.document.layout,
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

    override fun show(view: PSD2LiveState, preview: RigPreviewModel, base: WorkspaceDocument?, next: WorkspaceDocument,
                      status: String) =
        viewModel.showWorkspaceCommit(preview, base, next, status, view.projectId, view.projectOpenGeneration)

    override fun draftYielded(view: PSD2LiveState, fields: List<String>) = viewModel.reportDraftYielded(view, fields)

    override fun savedView(capture: WorkspaceCapture<RigPreviewModel>, view: PSD2LiveState): PSD2LiveState =
        if (view.projectId != capture.projectId || view.analysis == null) view else withDocument(view, capture.document)

    override fun historyChanged(history: WorkspaceHistorySnapshot) = viewModel.updateHistorySnapshot(history)
    override fun shownHistoryHead(): String? = viewModel.state.value.historySnapshot?.headNodeId
    override fun modelPublished(model: RigPreviewModel) { viewModel.refreshWorkspaceRenderer(model) }
    override fun posesChanged(view: PSD2LiveState, poses: Map<String, io.github.psd2live.application.WorkspacePose>) {
        viewModel.projectWorkspacePoses(view, poses)
    }
    override fun playbackFrame(frame: JsonObject) { viewModel.applyPlaybackFrame(frame) }
    override fun authoredPose(view: PSD2LiveState, pose: io.github.psd2live.application.WorkspacePose, changed: Boolean,
                              commit: PendingPoseCommit?, projectDocument: () -> Unit) {
        viewModel.projectAuthoredPose(view, pose, changed, commit, projectDocument)
    }
    override fun previewSession(view: PSD2LiveState, pose: io.github.psd2live.application.WorkspacePose, changed: Boolean,
                                commit: PendingPoseCommit?) { viewModel.applyPreviewSession(view, pose, changed, commit) }
    override fun swingPreview(preview: WorkspaceSwingPreview) { viewModel.applySwingPreview(preview) }
    override fun simulationPreview(preview: WorkspaceSimulationPreview) { viewModel.applySimulationPreview(preview) }
    override fun canvasVisibility(view: PSD2LiveState, address: CanvasAddress, value: CanvasVisibility, changed: Boolean) {
        viewModel.applyCanvasVisibility(view, address, value, changed)
    }
    override fun savedDataInstalled(view: PSD2LiveState, before: WorkspaceAuxiliaryData, next: WorkspaceAuxiliaryData) {
        viewModel.installSavedProjectData(view, before, next)
    }
    override fun auxiliaryChanged() = viewModel.markProjectAuxiliaryChanged()
    override fun discardDrafts() { viewModel.discardUncommittedTextureEdits(); viewModel.discardEditorSessionDrafts() }

    override fun sourceInstalled(view: PSD2LiveState, document: WorkspaceDocument, preview: RigPreviewModel, path: Path?, projectId: String) =
        viewModel.applySourceImport(view, document, preview, path, projectId)

    override fun cmo3Installed(view: PSD2LiveState, document: WorkspaceDocument, preview: RigPreviewModel, path: Path,
                               projectId: String, replacing: Boolean) {
        viewModel.applyCmo3Import(view, document, preview, path, projectId, replacing)
    }

    override fun projectInstalled(view: PSD2LiveState, expected: PSD2LiveState, projectId: String, file: Path?, source: Path,
                                  document: WorkspaceDocument, preview: RigPreviewModel, dirty: Boolean) {
        viewModel.installProjectState(view.copy(
            projectId = projectId, projectFile = file?.toString(), inputPath = source.toString(), loadedInputPath = source.toString(),
            analysis = preview.analysis, previewModel = preview,
            documentLayerVisibility = document.layerVisibility, layerOverrides = document.layerOverrides,
            deletedLayerIds = document.deletedLayerIds, parentOverrides = document.parentOverrides, rigEdits = document.rigEdits,
            generationSource = document.generationSource,
            meshSource = document.meshSource,
            textureOverrides = document.textureOverrides,
            layout = document.layout,
        ), expected, dirty = dirty, document = document)
    }

    override fun importedSource(view: PSD2LiveState): WorkspaceImportedSource? {
        if (view.analysis == null) return null
        val id = view.projectId ?: error("Imported project has no identity")
        return WorkspaceImportedSource(id, legacyProjectId(view), WorkspaceStateCodec.document(view), requireNotNull(view.previewModel),
            WorkspaceStateCodec.settings(view), view.projectDirty)
    }

    /** The identity a project's history was stored under before projects had their own: its source file. */
    private fun legacyProjectId(state: PSD2LiveState): String {
        val input = state.loadedInputPath ?: state.inputPath
        val identity = buildString {
            append(runCatching { Path.of(input).toAbsolutePath().normalize().toString() }.getOrDefault(input))
            (state.loadedInputFileSignature ?: runCatching {
                val path = Path.of(input)
                "${Files.size(path)}:${Files.getLastModifiedTime(path).toMillis()}"
            }.getOrNull())?.let { append("|file:").append(it) }
        }
        return "project-" + MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }.take(16)
    }

    override fun projectProgress(step: WorkspaceProjectStep, path: Path, fraction: Float) =
        viewModel.reportProjectProgress(tr("project.progress." + step.name.lowercase(), path.fileName.toString()), fraction)
    override fun projectProgressCleared() = viewModel.clearProjectProgress()
    override fun saveStarted() = viewModel.projectSaveStarted()
    override fun saveFinished(path: Path, head: String, baseline: PSD2LiveState, runtimeState: String, runtimeSaved: Boolean) =
        viewModel.projectSaveFinished(path, head, baseline, runtimeState, runtimeSaved)
    override fun saveFailed(failure: Throwable, view: PSD2LiveState) = viewModel.projectSaveFailed(failure, view)

    override fun <T> simulationBake(id: String, action: ((Float) -> Unit, () -> Boolean) -> T): T = viewModel.trackSimulationBake(id, action)
    override suspend fun sampleMotion(bundle: CubismRuntimeBundle, parameters: List<ParameterId>, frames: Int, fps: Int,
                                      progress: (Float) -> Unit, cancelled: () -> Boolean): List<Map<ParameterId, Float>> =
        viewModel.sampleAgentMotion(bundle, parameters, frames, fps, progress, cancelled)
}
