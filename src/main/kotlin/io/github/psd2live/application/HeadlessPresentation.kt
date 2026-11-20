package io.github.psd2live.application

import io.github.psd2live.core.CubismRuntimeBundle
import io.github.psd2live.core.CubismSdkPreviewSession
import io.github.psd2live.core.PipelineConfig
import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.project.WorkspaceAuxiliaryData
import io.github.psd2live.project.WorkspaceDocument
import io.github.psd2live.project.WorkspaceHistorySnapshot
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.umamo.runtime.model.ParameterId
import java.nio.file.Path

/**
 * The presentation of a host without a window: it shows nothing and holds no drafts, and it keeps only what
 * the workspace's queries report - which project is open, from what source, saved where. An archive's own
 * presentation (an editor's layout and canvases) passes through untouched, so saving it back keeps it.
 */
internal class HeadlessPresentation(
    /** Settings a new source starts from. */
    private val config: PipelineConfig = PipelineConfig(),
) : WorkspaceHostPresentation<HeadlessPresentation.View>, AutoCloseable {
    data class View(
        val projectId: String? = null,
        val projectFile: String? = null,
        val inputPath: String? = null,
        val sourceName: String? = null,
        /** The archive's presentation, kept as it was read. */
        val presentation: JsonObject = JsonObject(emptyMap()),
    )

    @Volatile private var view = View()
    @Volatile private var saving = 0
    private var dirty: () -> Boolean = { false }
    private val sampler = lazy { CubismSdkPreviewSession.windowless() }

    /** Reports the [host]'s unsaved changes as this presentation's, as an editor does. */
    fun attach(host: WorkspaceHost<View>) { dirty = host::hasUnsavedChanges }

    override fun current(): View = view
    override fun <T> withCurrent(read: (View) -> T): T = synchronized(this) { read(view) }

    override fun facts(view: View) = WorkspaceHostFacts(
        projectId = view.projectId, saving = saving > 0, dirty = view.projectId != null && dirty(),
        activeWorkspaceId = WORKSPACE, workspaceIds = listOf(WORKSPACE), canvasAddresses = { emptyList() },
        sourceName = view.sourceName, inputPath = view.inputPath, projectFile = view.projectFile)

    override fun visibleLayers(capture: WorkspaceCapture<RigPreviewModel>, view: View): Set<String>? = null
    override fun auxiliary(view: View): JsonObject = JsonObject(emptyMap())

    override fun encode(capture: WorkspaceCapture<RigPreviewModel>, view: View, checkpoint: Boolean): JsonObject =
        JsonObject(view.presentation + listOfNotNull(view.sourceName?.let { "projectSourceName" to JsonPrimitive(it) }))

    override fun decode(presentation: JsonObject?): View = presentation?.let { view.copy(presentation = it) } ?: view
    override fun config(view: View): PipelineConfig = config
    override fun basis(view: View): Pair<RigPreviewModel?, Map<String, Float>> = null to emptyMap()

    override fun show(view: View, preview: RigPreviewModel, base: WorkspaceDocument?, next: WorkspaceDocument, status: String) = Unit

    override fun draftYielded(view: View, fields: List<String>) = Unit
    override fun savedView(capture: WorkspaceCapture<RigPreviewModel>, view: View): View = view
    override fun historyChanged(history: WorkspaceHistorySnapshot) = Unit
    override fun shownHistoryHead(): String? = null
    override fun modelPublished(model: RigPreviewModel) = Unit
    override fun posesChanged(view: View, poses: Map<String, WorkspacePose>) = Unit
    override fun playbackFrame(frame: JsonObject) = Unit
    override fun authoredPose(view: View, pose: WorkspacePose, changed: Boolean, commit: PendingPoseCommit?, projectDocument: () -> Unit) =
        projectDocument()
    override fun previewSession(view: View, pose: WorkspacePose, changed: Boolean, commit: PendingPoseCommit?) = Unit
    override fun swingPreview(preview: WorkspaceSwingPreview) = Unit
    override fun simulationPreview(preview: WorkspaceSimulationPreview) = Unit
    override fun canvasVisibility(view: View, address: CanvasAddress, value: CanvasVisibility, changed: Boolean) = Unit
    override fun savedDataInstalled(view: View, before: WorkspaceAuxiliaryData, next: WorkspaceAuxiliaryData) = Unit
    override fun auxiliaryChanged() = Unit
    override fun discardDrafts() = Unit

    override fun sourceInstalled(view: View, document: WorkspaceDocument, preview: RigPreviewModel, path: Path?, projectId: String) {
        this.view = View(projectId, null, path?.toString(), path?.fileName?.toString())
    }

    override fun cmo3Installed(view: View, document: WorkspaceDocument, preview: RigPreviewModel, path: Path, projectId: String,
                               replacing: Boolean) {
        this.view = if (replacing) this.view.copy(projectId = projectId) else View(projectId, null, path.toString(), path.fileName.toString())
    }

    override fun projectInstalled(view: View, expected: View, projectId: String, file: Path?, source: Path, document: WorkspaceDocument,
                                  preview: RigPreviewModel, dirty: Boolean) {
        val name = (view.presentation["projectSourceName"] as? JsonPrimitive)?.content ?: source.fileName?.toString()
        this.view = view.copy(projectId = projectId, projectFile = file?.toString(), inputPath = source.toString(), sourceName = name)
    }

    override fun importedSource(view: View): WorkspaceImportedSource? = null

    override fun projectProgress(step: WorkspaceProjectStep, path: Path, fraction: Float) = Unit
    override fun projectProgressCleared() = Unit
    override fun saveStarted() { synchronized(this) { saving++ } }
    override fun saveFinished(path: Path, head: String, baseline: View, runtimeState: String, runtimeSaved: Boolean) {
        synchronized(this) { saving--; if (view.projectId == baseline.projectId) view = view.copy(projectFile = path.toString()) }
    }
    override fun saveFailed(failure: Throwable, view: View) { synchronized(this) { saving-- } }

    override fun <T> simulationBake(id: String, action: ((Float) -> Unit, () -> Boolean) -> T): T = action({}, { false })

    override suspend fun sampleMotion(bundle: CubismRuntimeBundle, parameters: List<ParameterId>, frames: Int, fps: Int,
                                      progress: (Float) -> Unit, cancelled: () -> Boolean): List<Map<ParameterId, Float>> =
        sampler.value.sampleMotionAwait(bundle, parameters, "AgentObservation", frames, fps, progress, cancelled)

    override fun close() { if (sampler.isInitialized()) sampler.value.close() }

    companion object {
        const val WORKSPACE = "main"
    }
}
