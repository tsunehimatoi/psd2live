package io.github.psd2live.application

import io.github.psd2live.core.PipelineConfig
import io.github.psd2live.project.HistoryAnnotation
import io.github.psd2live.project.ParameterSnapshot
import io.github.psd2live.project.WorkspaceTaskSnapshot
import org.umamo.format.art.SourceArt
import org.umamo.runtime.model.ParameterId

/** Authored content as an application value; UI presentation and evaluated frames have no fields here. */
internal data class WorkspaceDurableIdentity(
    val source: SourceArt?,
    val config: PipelineConfig,
    val outputPath: String,
    val snapshots: List<ParameterSnapshot>,
    val annotations: Map<String, HistoryAnnotation>,
    val auxiliaryVersion: Long,
    val workspaces: List<Workspace>,
    val externalPsdPath: String? = null,
) {
    data class Workspace(val id: String, val name: String, val preset: String,
                         val values: Map<ParameterId, Float>, val locks: Set<ParameterId>,
                         val canvases: Map<String, List<CanvasVisibility>>)
}

/** The runtime capture owns the document/auxiliary data; this part owns host-authored durable content. */
internal data class WorkspaceDurableState(
    val projectId: String?, val generation: Long, val identity: WorkspaceDurableIdentity,
    val contentVersion: Long, val tasks: List<WorkspaceTaskSnapshot>, val projectFile: String? = null,
)

internal data class WorkspaceSavedVersion(val projectId: String?, val generation: Long,
                                          val identity: WorkspaceDurableIdentity)

/** Save/install boundaries and legacy task preservation belong to the application, not a UI widget. */
internal class WorkspaceLifecycleCoordinator {
    private val lock = Any()
    private var current: WorkspaceDurableState? = null
    private var saved: WorkspaceSavedVersion? = null
    private var records = WorkspaceTaskRecords()

    fun install(projectId: String?, generation: Long, identity: WorkspaceDurableIdentity, clean: Boolean,
                projectFile: String? = null) = synchronized(lock) {
        current = WorkspaceDurableState(projectId, generation, identity, 0, records.list(), projectFile)
        saved = if (clean) WorkspaceSavedVersion(projectId, generation, identity) else null
    }

    fun saved(projectId: String?, generation: Long, identity: WorkspaceDurableIdentity, projectFile: String) = synchronized(lock) {
        if (current?.projectId == projectId && current?.generation == generation) {
            saved = WorkspaceSavedVersion(projectId, generation, identity)
            current = current?.copy(projectFile = projectFile)
        }
    }

    fun observe(projectId: String?, generation: Long, identity: WorkspaceDurableIdentity,
                runtimeDirty: Boolean, requestedDirty: Boolean, busy: Boolean): Boolean = synchronized(lock) {
        val before = current?.takeIf { it.projectId == projectId && it.generation == generation }
        val changed = before?.identity != identity
        if (changed) current = WorkspaceDurableState(projectId, generation, identity,
            (before?.contentVersion ?: 0) + 1, records.list(), before?.projectFile)
        val baseline = saved?.takeIf { it.projectId == projectId && it.generation == generation }
        if (baseline == null) requestedDirty || changed
        else runtimeDirty || identity != baseline.identity || (requestedDirty && busy)
    }

    fun restoreTasks(tasks: List<WorkspaceTaskSnapshot>) = synchronized(lock) {
        records = WorkspaceTaskRecords().also { it.restore(tasks) }
        current = current?.copy(tasks = records.list())
    }

    fun tasks(): List<WorkspaceTaskSnapshot> = synchronized(lock) { records.list() }
    fun capture(): WorkspaceDurableState? = synchronized(lock) { current }
}
