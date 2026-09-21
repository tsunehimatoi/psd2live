package io.github.psd2live.ui.state

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.key
import io.github.psd2live.core.MeshSettings
import io.github.psd2live.core.PackedAtlas

import io.github.psd2live.core.PSD2LivePipeline
import io.github.psd2live.core.RigStructureEdits
import io.github.psd2live.core.CubismSdkFrame
import io.github.psd2live.core.CubismSdkPreviewSession
import io.github.psd2live.core.EyeJellyDynamics
import io.github.psd2live.core.HierarchyImportTarget
import io.github.psd2live.core.LayerClassificationOverride
import io.github.psd2live.core.LayerImport
import io.github.psd2live.core.LayerType
import io.github.psd2live.core.PipelineAnalysis
import io.github.psd2live.core.PipelineConfig
import io.github.psd2live.core.ProgressListener
import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.core.RigEditOverlay
import io.github.psd2live.core.RigPhysicsEdit
import io.github.psd2live.core.SemanticTag
import io.github.psd2live.core.Side
import io.github.psd2live.core.StandardParameters
import io.github.psd2live.agent.AgentWorkspace
import io.github.psd2live.agent.AgentHistorySnapshot
import io.github.psd2live.i18n.AppLanguage
import io.github.psd2live.i18n.I18n
import io.github.psd2live.i18n.tr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import org.umamo.edit.freshParameterGroupId
import io.github.psd2live.ui.CanvasEditor
import io.github.psd2live.ui.keyformAxesFor
import io.github.psd2live.ui.EditHierarchyMode
import org.umamo.format.art.SourceArt
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.prefs.Preferences
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

class PSD2LiveViewModel : AutoCloseable {
    internal val canvasEditor: CanvasEditor by lazy { CanvasEditor(this) }

    fun updatePuppetModel(transform: (PuppetModel) -> PuppetModel) {
        val currentPreview = _state.value.previewModel ?: return
        val newPuppet = transform(currentPreview.rig.puppet)
        val updatedRig = currentPreview.rig.copy(puppet = newPuppet)
        val updatedPreview = currentPreview.copy(rig = updatedRig)
        _state.update { it.copy(previewModel = updatedPreview, previewModelDirty = true, projectDirty = true) }
        markWorkspaceChanged()
        editorChanged()
    }

    fun applyCommittedPaint(updatedPreview: RigPreviewModel, summary: String) {
        _state.update {
            it.copy(
                previewModel = updatedPreview,
                analysis = updatedPreview.analysis,
                previewModelDirty = true,
                projectDirty = true,
            ).withLog(tr("editor.paint.applied", summary), level = LogLevel.INFO, tag = "Paint")
        }
        refreshSdkSession(updatedPreview)
        markWorkspaceChanged()
        commitEditorChange(summary)
    }

    val canvasPathRequests = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    fun requestCanvasPathTool() { canvasPathRequests.tryEmit(Unit) }
    fun saveAuthoringEdits(expectedState: String, edits: kotlinx.serialization.json.JsonArray, onComplete: (String?) -> Unit) {
        if (_state.value.canvasEditBusy) { onComplete("An editor operation is still being applied"); return }
        _state.update { it.copy(canvasEditBusy = true) }
        scope.launch {
            try {
                val workspace = requireNotNull(agentWorkspace) { "Project workspace unavailable" }
                // Canvas authoring uses the same journal the MCP tools write, so the author has to
                // be stated here: these edits came from the person at the editor.
                withContext(Dispatchers.Default) { workspace.authorRig(expectedState, edits, io.github.psd2live.agent.MutationAuthor.USER) }
                onComplete(null)
            } catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                onComplete(failure.message ?: "Could not save deform paths")
            } finally {
                _state.update { it.copy(canvasEditBusy = false) }
                queuedCanvasSave?.let { saveAs -> queuedCanvasSave=null; requestProjectSave(saveAs) }
            }
        }
    }

    /** What each open field session will record once it ends; the last value written wins. */
    private val pendingRigEdits = mutableMapOf<String, kotlinx.serialization.json.JsonObject>()

    /**
     * Live-previews one object property on the puppet and holds the edit for the end of its field session.
     *
     * This is what the inspector's fields call on every change. The preview is patched immediately — the
     * canvas is what the user is watching, and a colour or opacity that only landed on blur would feel
     * broken — while the document edit waits in [pendingRigEdits] until [endEditorField], so a drag or a
     * typed number becomes one history node instead of one per sample or keystroke.
     *
     * The opening of the session is implicit: the first change on a token starts it, so a call site needs
     * only the pairing `onEditEnd`.
     *
     * @param token identifies the field; the matching `endEditorField` must use the same string.
     * @param fields the `static` action's own fields, e.g. `"opacity" to JsonPrimitive(0.5f)`.
     */
    fun applyRigStaticLive(
        token: String,
        kind: String,
        id: String,
        vararg fields: Pair<String, kotlinx.serialization.json.JsonElement>,
    ) {
        val edit = structureEdit("static", kind, id, kotlinx.serialization.json.JsonObject(linkedMapOf(*fields)))
        editorSessions.begin(token)
        pendingRigEdits[token] = edit
        patchPreview(edit)
    }

    /**
     * Records one object property change with no session around it.
     *
     * For the controls where a single interaction *is* the whole edit — a checkbox, a dropdown row — so
     * there is nothing to coalesce and waiting for a blur would just delay the node. Continuous controls
     * use [applyRigStaticLive] instead.
     */
    fun applyRigStaticNow(kind: String, id: String, vararg fields: Pair<String, kotlinx.serialization.json.JsonElement>) {
        recordStructure(structureEdit("static", kind, id, kotlinx.serialization.json.JsonObject(linkedMapOf(*fields))))
    }

    /**
     * The same, for the `rename`, `visibility`, `move` and `bind` actions, which carry fields of their own.
     */
    fun applyRigStructureLive(token: String, action: String, kind: String, id: String, fields: kotlinx.serialization.json.JsonObject) {
        val edit = structureEdit(action, kind, id, fields)
        editorSessions.begin(token)
        pendingRigEdits[token] = edit
        patchPreview(edit)
    }

    /** Applies the pending edit to the puppet in place, which is what makes the field feel immediate. */
    private fun patchPreview(edit: kotlinx.serialization.json.JsonObject) {
        val current = _state.value.previewModel ?: return
        val patched = runCatching { RigStructureEdits.apply(current.rig.puppet, listOf(edit)) }.getOrNull() ?: return
        _state.update {
            it.copy(
                previewModel = it.previewModel?.copy(rig = it.previewModel!!.rig.copy(puppet = patched)) ?: current,
                previewModelDirty = true,
                projectDirty = true,
            )
        }
    }

    private fun structureEdit(action: String, kind: String, id: String, fields: kotlinx.serialization.json.JsonObject) =
        kotlinx.serialization.json.JsonObject(
            linkedMapOf(
                "action" to kotlinx.serialization.json.JsonPrimitive(action),
                "kind" to kotlinx.serialization.json.JsonPrimitive(kind),
                "id" to kotlinx.serialization.json.JsonPrimitive(id),
            ) + fields,
        )

    /**
     * Records one object property change in the document, as the single history node for the field session
     * that produced it.
     *
     * Call this when the session **ends**, not while it is open: the inspector already patches the puppet
     * for immediate feedback, and the funnel would read a mid-typing commit as a no-op against that
     * patched preview — see [PSD2LiveState.previewModelDirty]. Ending the session is what makes the
     * command describe a change the document has not seen.
     *
     * @param action a `structure` action: `rename`, `visibility`, `move`, `bind`, `static`, or `delete`.
     * @param fields the action's own fields, e.g. `{"opacity": 0.5}` for `static`.
     */
    fun applyRigStructure(
        action: String,
        kind: String,
        id: String,
        fields: kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.JsonObject(emptyMap()),
    ) {
        recordStructure(structureEdit(action, kind, id, fields))
    }

    /**
     * Deletes deformers innermost-first (unwrap: children bake into the parent and re-home).
     * Clears hierarchy overrides and selection for the removed ids.
     */
    fun deleteDeformers(idsInnermostFirst: List<String>) {
        if (idsInnermostFirst.isEmpty()) return
        val puppet = _state.value.previewModel?.rig?.puppet ?: return
        val byId = puppet.deformers.associateBy { it.id.raw }
        val edits = idsInnermostFirst.mapNotNull { id ->
            val d = byId[id] ?: return@mapNotNull null
            val kind = when (d) {
                is org.umamo.runtime.model.Deformer.Warp -> "warp"
                is org.umamo.runtime.model.Deformer.Rotation -> "rotation"
            }
            structureEdit("delete", kind, id, kotlinx.serialization.json.JsonObject(emptyMap()))
        }
        if (edits.isEmpty()) return
        val removed = idsInnermostFirst.toSet()
        _state.update { current ->
            current.copy(
                parentOverrides = current.parentOverrides.filterKeys { it !in removed },
                selectedDeformerId = if (current.selectedDeformerId in removed) null else current.selectedDeformerId,
            )
        }
        val expected = _state.value.historySnapshot?.headNodeId ?: return
        val command = kotlinx.serialization.json.JsonObject(
            linkedMapOf(
                "op" to kotlinx.serialization.json.JsonPrimitive("structure"),
                "edits" to kotlinx.serialization.json.JsonArray(edits),
            ),
        )
        saveAuthoringEdits(expected, kotlinx.serialization.json.JsonArray(listOf(command))) {}
    }

    private fun recordStructure(edit: kotlinx.serialization.json.JsonObject) {
        val expected = _state.value.historySnapshot?.headNodeId ?: return
        val command = kotlinx.serialization.json.JsonObject(
            linkedMapOf(
                "op" to kotlinx.serialization.json.JsonPrimitive("structure"),
                "edits" to kotlinx.serialization.json.JsonArray(listOf(edit)),
            ),
        )
        saveAuthoringEdits(expected, kotlinx.serialization.json.JsonArray(listOf(command))) {}
    }

    /** Creates a parameter-panel folder (CMO3 CParameterGroup) at the panel root or under [parentGroupId]. */
    fun createParameterGroup(name: String, parentGroupId: String? = null) {
        val puppet = _state.value.previewModel?.rig?.puppet ?: return
        val id = puppet.freshParameterGroupId().raw
        applyRigStructure(
            "create",
            "param_group",
            id,
            kotlinx.serialization.json.buildJsonObject {
                put("name", kotlinx.serialization.json.JsonPrimitive(name))
                put("parent_id", parentGroupId?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull)
            },
        )
    }

    fun renameParameterGroup(groupId: String, name: String) {
        applyRigStructure(
            "rename",
            "param_group",
            groupId,
            kotlinx.serialization.json.buildJsonObject {
                put("name", kotlinx.serialization.json.JsonPrimitive(name))
            },
        )
    }

    fun deleteParameterGroup(groupId: String) {
        applyRigStructure("delete", "param_group", groupId)
    }

    fun setParameterGroupOpen(groupId: String, open: Boolean) {
        applyRigStructure(
            "open",
            "param_group",
            groupId,
            kotlinx.serialization.json.buildJsonObject {
                put("open", kotlinx.serialization.json.JsonPrimitive(open))
            },
        )
    }

    /**
     * Moves a parameter or folder in the panel tree. [parentGroupId] null = root; [beforeId] null = append.
     * Flat parameter order is rewritten to tree preorder so CMO3 combined adjacency matches the panel.
     */
    fun moveParameterPanelNode(
        kind: String,
        id: String,
        parentGroupId: String?,
        beforeId: String?,
        beforeKind: String?,
    ) {
        applyRigStructure(
            "move",
            kind,
            id,
            kotlinx.serialization.json.buildJsonObject {
                put("parent_id", parentGroupId?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull)
                if (beforeId != null && beforeKind != null) {
                    put("before_id", kotlinx.serialization.json.JsonPrimitive(beforeId))
                    put("before_kind", kotlinx.serialization.json.JsonPrimitive(beforeKind))
                }
            },
        )
    }

    /** Links [horizontalId] + [partnerId] as a Cubism combined pair (2D pad), or unlinks them. */
    fun setParameterLink(horizontalId: String, partnerId: String, linked: Boolean) {
        applyRigStructure(
            "link",
            "parameter",
            horizontalId,
            kotlinx.serialization.json.buildJsonObject {
                put("partner_id", kotlinx.serialization.json.JsonPrimitive(partnerId))
                put("linked", kotlinx.serialization.json.JsonPrimitive(linked))
            },
        )
    }
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
	internal val pipeline = PSD2LivePipeline()
	private val preferences by lazy { Preferences.userNodeForPackage(PSD2LiveViewModel::class.java) }
	private var agentWorkspace: AgentWorkspace? = null
    private val projectSession = io.github.psd2live.project.ProjectSession(this)
    private var pendingDestructiveAction: (() -> Unit)? = null
    var confirmUnsavedChanges: (() -> Int)? = null
    private var queuedCanvasSave: Boolean? = null

    fun withSavedChanges(action: () -> Unit) {
        if (_state.value.projectSaving) return
        // Anything half-typed is closed first, so the dirty check and the confirm dialog below see the
        // value the user actually ended on rather than the last committed one.
        flushEditorFields()
        if (!_state.value.projectDirty) { action(); return }
        when (confirmUnsavedChanges?.invoke() ?: 2) {
            0 -> { pendingDestructiveAction = action; requestProjectSave() }
            1 -> action()
        }
    }
    fun requestProjectSave(saveAs: Boolean = false) {
        // A save captures the workspace, so it has to see the value still sitting in a focused field.
        flushEditorFields()
        if (_state.value.canvasEditBusy) { queuedCanvasSave=saveAs; return }
        if (_state.value.analysis == null) return
        if (saveAs || _state.value.projectFile == null) {
            _state.update { it.copy(showProjectLocationDialog = true, projectSaveError = null) }
        } else saveProjectTo(Path.of(_state.value.projectFile!!))
    }
    fun clearProjectSaveError() { _state.update { it.copy(projectSaveError = null) } }
    fun cancelProjectLocation() {
        pendingDestructiveAction = null
        _state.update { it.copy(showProjectLocationDialog = false) }
    }
    fun saveProjectTo(path: Path) {
        scope.launch {
            try {
                saveProjectNow(path)
                _state.update { it.copy(showProjectLocationDialog = false) }
                if (!_state.value.projectDirty) pendingDestructiveAction?.also { pendingDestructiveAction = null; it() }
            } catch (_: Exception) { pendingDestructiveAction = null }
        }
    }
    internal suspend fun saveProjectNow(path: Path? = null, actor: String = "user"): String {
        val target = path ?: _state.value.projectFile?.let(Path::of) ?: error("Choose a project save location in the application first")
        val workspace = agentWorkspace as? io.github.psd2live.agent.ViewModelAgentWorkspace ?: error("Project workspace unavailable")
        return projectSession.save(workspace, target, actor)
    }
    fun openProject(path: Path) = withSavedChanges {
        scope.launch {
            try {
                val workspace = agentWorkspace as? io.github.psd2live.agent.ViewModelAgentWorkspace ?: error("Project workspace unavailable")
                projectSession.open(workspace, path)
            } catch (failure: Exception) { _state.update { it.copy(errorMessage = failure.message) } }
        }
    }
    internal fun installProjectState(state: PSD2LiveState) {
        previewRebuildJob?.cancel()
        activeWorkJob?.cancel()
        canvasEditor.resetPaintSession()
        _state.value = state.copy(projectDirty = false, projectOpenGeneration = _state.value.projectOpenGeneration + 1)
    }
    private val pendingProjectSaves = java.util.concurrent.atomic.AtomicInteger()
    internal fun projectSaveStarted() { pendingProjectSaves.incrementAndGet(); _state.update { it.copy(projectSaving = true, projectSaveError = null) } }
    internal fun projectSaveFailed(failure: Exception) { val saving = pendingProjectSaves.decrementAndGet() > 0; _state.update { it.copy(projectSaving = saving, projectDirty = true, projectSaveError = failure.message ?: "Save failed") } }
    internal fun projectSaveFinished(path: Path, headId: String, captured: PSD2LiveState) {
        val saving = pendingProjectSaves.decrementAndGet() > 0
        _state.update { current -> current.copy(projectFile = path.toAbsolutePath().normalize().toString(), projectSaving = saving,
            projectDirty = current.historySnapshot?.headNodeId != headId || current.projectAuxiliaryVersion != captured.projectAuxiliaryVersion || io.github.psd2live.project.WorkspaceStateCodec.editableIdentity(current) != io.github.psd2live.project.WorkspaceStateCodec.editableIdentity(captured), projectSaveError = null) }
    }
    internal fun markProjectAuxiliaryChanged() { _state.update { it.copy(projectDirty = true, projectEditVersion = it.projectEditVersion + 1, projectAuxiliaryVersion = it.projectAuxiliaryVersion + 1) } }
    private fun markWorkspaceChanged() { _state.update { if (it.analysis == null) it else it.copy(projectDirty = true, projectEditVersion = it.projectEditVersion + 1) } }
    /**
     * Open field sessions, so a slider drag or a half-typed value commits once rather than per sample or
     * per keystroke. See [EditorFieldSessions].
     */
    private val editorSessions = EditorFieldSessions { commitEditorChange() }

    fun beginEditorGesture() = editorSessions.begin(SLIDER_SESSION)
    fun endEditorGesture() = editorSessions.end(SLIDER_SESSION)

    /** Brackets one text/number field's editing session; [token] has to match the paired `end`. */
    fun beginEditorField(token: String) = editorSessions.begin(token)

    /**
     * Ends a field session and records whatever it was holding.
     *
     * The edit is recorded before the session closes: closing it can ask the workspace to record an
     * editor change, and the object edit is the one that has to land first.
     */
    fun endEditorField(token: String) {
        pendingRigEdits.remove(token)?.let(::recordStructure)
        editorSessions.end(token)
    }

    /** Closes every open field session so a save or a window close sees the value just typed. */
    fun flushEditorFields() = editorSessions.flush()

    private fun editorChanged() {
        if (editorSessions.anyOpen) { markWorkspaceChanged(); return }
        commitEditorChange()
    }

    private fun commitEditorChange(summary: String? = null) {
        if (_state.value.analysis == null) return
        (agentWorkspace as? io.github.psd2live.agent.ViewModelAgentWorkspace)?.editorChanged(summary)
    }
    fun editHistoryAnnotation(id: String, title: String, note: String, hidden: Boolean) {
        require(_state.value.historySnapshot?.nodes?.any { it.id == id } == true)
        _state.update { it.copy(historyAnnotations = it.historyAnnotations + (id to HistoryAnnotation(title.trim(), note, hidden)), projectDirty = true, projectEditVersion = it.projectEditVersion + 1) }
    }
    fun undoHistory() {
        if (_state.value.canvasEditBusy) return
        val history = _state.value.historySnapshot ?: return
        history.nodes.firstOrNull { it.id == history.headNodeId }?.parentId?.let(::checkoutHistoryNode)
    }
    fun redoHistory() {
        if (_state.value.canvasEditBusy) return
        val history = _state.value.historySnapshot ?: return
        val children = history.nodes.filter { it.parentId == history.headNodeId }
        if (children.size == 1) checkoutHistoryNode(children.single().id)
        else openHistoryTab()
    }
    fun setHistoryView(zoom: Float, x: Float, y: Float, search: String, showHidden: Boolean) {
        _state.update { if (it.historyZoom == zoom && it.historyPanX == x && it.historyPanY == y && it.historySearch == search && it.historyShowHidden == showHidden) it
            else it.copy(historyZoom = zoom, historyPanX = x, historyPanY = y, historySearch = search, historyShowHidden = showHidden, projectDirty = it.analysis != null, projectEditVersion = it.projectEditVersion + 1) }
    }
    fun setHierarchyView(width: Float = _state.value.hierarchyWidth, collapsed: Boolean = _state.value.hierarchyCollapsed, search: String = _state.value.hierarchySearch) {
        val clampedWidth = width.coerceIn(100f, 600f)
        _state.update {
            val layoutChanged = it.hierarchyWidth != clampedWidth || it.hierarchyCollapsed != collapsed
            val searchChanged = it.hierarchySearch != search
            if (!layoutChanged && !searchChanged) it
            else it.copy(
                hierarchyWidth = clampedWidth,
                hierarchyCollapsed = collapsed,
                hierarchySearch = search,
                // Search is a transient filter — do not dirty the project or bump edit version.
                projectDirty = if (layoutChanged && it.analysis != null) true else it.projectDirty,
                projectEditVersion = if (layoutChanged) it.projectEditVersion + 1 else it.projectEditVersion,
            )
        }
    }

    fun setHierarchySearch(search: String) {
        _state.update {
            if (it.hierarchySearch == search) it
            else it.copy(hierarchySearch = search)
        }
    }
    fun adjustHierarchyWidth(deltaDp: Float, min: Float = 100f, max: Float = 600f) {
        _state.update {
            val next = (it.hierarchyWidth + deltaDp).coerceIn(min, max)
            if (next == it.hierarchyWidth) it
            else it.copy(hierarchyWidth = next, projectDirty = it.analysis != null, projectEditVersion = it.projectEditVersion + 1)
        }
    }
    fun setDrawOrderRulerWidth(width: Float, min: Float = 14f, max: Float = 100f) {
        val clamped = width.coerceIn(min, max)
        _state.update {
            if (it.drawOrderRulerWidth == clamped) it
            else it.copy(drawOrderRulerWidth = clamped, projectDirty = it.analysis != null, projectEditVersion = it.projectEditVersion + 1)
        }
    }
    fun adjustDrawOrderRulerWidth(deltaDp: Float, min: Float = 14f, max: Float = 100f) {
        _state.update {
            val next = (it.drawOrderRulerWidth + deltaDp).coerceIn(min, max)
            if (next == it.drawOrderRulerWidth) it
            else it.copy(drawOrderRulerWidth = next, projectDirty = it.analysis != null, projectEditVersion = it.projectEditVersion + 1)
        }
    }
    fun setModelSettingsExpanded(expanded: Boolean) { _state.update { it.copy(modelSettingsExpanded = expanded, projectDirty = it.analysis != null, projectEditVersion = it.projectEditVersion + 1) } }
    fun setWorkspaceSplitRatio(value: Float) {
        val clamped = value.coerceIn(0.25f, 0.85f)
        _state.update {
            if (it.workspaceSplitRatio == clamped) it
            else it.copy(workspaceSplitRatio = clamped, projectDirty = it.analysis != null, projectEditVersion = it.projectEditVersion + 1)
        }
    }

    fun setInspectorCollapsed(collapsed: Boolean) {
        _state.update {
            if (it.inspectorCollapsed == collapsed) it
            else it.copy(inspectorCollapsed = collapsed, projectDirty = it.analysis != null, projectEditVersion = it.projectEditVersion + 1)
        }
    }

	fun requestSelectDockModule(moduleId: String) {
		_state.update { it.copy(requestedDockModule = moduleId) }
	}

	fun clearDockModuleRequest() {
		_state.update {
			if (it.requestedDockModule == null) it else it.copy(requestedDockModule = null)
		}
	}
    fun adjustWorkspaceSplitRatio(deltaRatio: Float, min: Float = 0.25f, max: Float = 0.85f) {
        _state.update {
            val next = (it.workspaceSplitRatio + deltaRatio).coerceIn(min, max)
            if (next == it.workspaceSplitRatio) it
            else it.copy(workspaceSplitRatio = next, projectDirty = it.analysis != null, projectEditVersion = it.projectEditVersion + 1)
        }
    }
    fun setCanvasView(zoom: Float, x: Float, y: Float) {
        _state.update { current ->
            current.updateActiveTab { tab -> tab.copy(camera = TabCamera(zoom, x, y)) }
                .copy(projectDirty = current.analysis != null, projectEditVersion = current.projectEditVersion + 1)
        }
    }

	fun attachAgentWorkspace(workspace: AgentWorkspace) {
		agentWorkspace = workspace
		runCatching {
			val snapshot = workspace.history()
			_state.update { it.copy(historySnapshot = snapshot, projectDirty = it.projectDirty || (it.historySnapshot != null && it.historySnapshot.headNodeId != snapshot.headNodeId), projectEditVersion = it.projectEditVersion + if (it.historySnapshot?.headNodeId != snapshot.headNodeId) 1 else 0) }
		}
	}

	var lastExportDirectory: String?
		get() = runCatching { preferences.get(PREF_LAST_EXPORT_DIR, null) }.getOrNull()?.takeIf(String::isNotBlank)
		private set(value) {
			runCatching {
				if (value.isNullOrBlank()) preferences.remove(PREF_LAST_EXPORT_DIR)
				else preferences.put(PREF_LAST_EXPORT_DIR, value.trim())
			}
		}

	private val _state = MutableStateFlow(PSD2LiveState(statusText = tr("status.ready")))
	val state: StateFlow<PSD2LiveState> = _state.asStateFlow()
	private val _sdkFrame = MutableStateFlow<CubismSdkFrame?>(null)
	val sdkFrame: StateFlow<CubismSdkFrame?> = _sdkFrame.asStateFlow()

	private var previewRebuildJob: Job? = null
	private var motionJob: Job? = null
	private var activeWorkJob: Job? = null

	private var pointerActive = false
	private var pointerX = 0f
	private var pointerY = 0f
	private var followX = 0f
	private var followY = 0f
	private var previousFollowX = 0f
	private var frontHair = 0f
	private var frontHairVelocity = 0f
	private var backHair = 0f
	private var backHairVelocity = 0f
	private val eyeJellyDynamics = EyeJellyDynamics()
	private var elapsed = 0.0
	private var lastTick = System.nanoTime()
	private var lastSdkParameterPublishNanos = 0L
	private var sdkSessionNeedsReload = false

	private fun refreshSdkSession(preview: RigPreviewModel) {
		if (_state.value.activeTabKind == WorkspaceTabKind.PREVIEW) {
			sdkSession.load(preview.runtimeBundle, preview.rig.puppet.parameters.map { it.id })
			sdkSessionNeedsReload = false
		} else {
			sdkSessionNeedsReload = true
		}
	}

	private fun ensureSdkSessionLoaded() {
		if (sdkSessionNeedsReload) {
			val preview = _state.value.previewModel
			if (preview != null) {
				sdkSessionNeedsReload = false
				sdkSession.load(preview.runtimeBundle, preview.rig.puppet.parameters.map { it.id })
			}
		}
	}

	private val sdkSession = CubismSdkPreviewSession(
		onFrame = { frame ->
			val now = System.nanoTime()
			var accepted = false
			_state.update { current ->
				if (!previewFrameMatchesState(current, frame.animationEnabled)) {
					return@update current
				}
				accepted = true
				val publishParameters = current.animationEnabled && !current.meshOnly &&
					(now - lastSdkParameterPublishNanos >= SDK_PARAMETER_PUBLISH_INTERVAL_NANOS)
				if (!publishParameters && current.sdkStatus == "ready") return@update current
				if (publishParameters) lastSdkParameterPublishNanos = now
				current.copy(
					sdkStatus = "ready",
					previewParameterValues = if (publishParameters) {
						parameterValuesAfterPreviewFrame(current, frame.parameters)
					} else {
						current.previewParameterValues
					},
				)
			}
			if (accepted) _sdkFrame.value = frame
		},
		onStatus = { status ->
			if (status != "ready") _sdkFrame.value = null
			_state.update { it.copy(sdkStatus = status) }
		},
	)

	init {
		startMotionLoop()
	}

	fun setInputPath(path: String) {
		val normalized = path.trim()
		_state.update { current ->
			val currentOutput = current.outputPath
			val nextOutput = if (currentOutput.isBlank() && normalized.isNotBlank()) {
				try {
					val p = Path.of(normalized)
					val parent = p.toAbsolutePath().parent
					val name = p.fileName.toString().substringBeforeLast('.')
					parent.resolve("$name-psd2live").toString()
				} catch (_: Exception) {
					currentOutput
				}
			} else currentOutput
			current.copy(inputPath = normalized, outputPath = nextOutput)
		}
	}

	fun setOutputPath(path: String) {
		val trimmed = path.trim()
		if (trimmed.isNotBlank()) {
			lastExportDirectory = trimmed
		}
		_state.update { it.copy(outputPath = trimmed) }
	    markWorkspaceChanged()
	}

	fun setTextureUpscale(config: io.github.psd2live.core.TextureUpscaleConfig) {
		val current = _state.value
		val prevScale = current.textureUpscale.scale
		val minRequired = current.minRequiredAtlasSize(config.scale)
		val shouldAutoExpand = config.scale > 1 && current.atlasSize < minRequired
		val newAtlasSize = if (shouldAutoExpand) minRequired else current.atlasSize
		if (config.scale > 1) {
			addLog(
				message = tr("log.upscaleConfigured", config.scale, config.noiseLevel, config.tileSize),
				level = LogLevel.INFO,
				tag = "Upscale",
			)
		} else if (prevScale > 1) {
			addLog(
				message = tr("log.upscaleDisabled"),
				level = LogLevel.INFO,
				tag = "Upscale",
			)
		}
		if (shouldAutoExpand) {
			addLog(
				message = tr("log.atlasAutoExpanded", newAtlasSize),
				level = LogLevel.INFO,
				tag = "Upscale",
			)
		}
		_state.update { it.copy(textureUpscale = config, atlasSize = newAtlasSize) }
		schedulePreviewRebuild()
		editorChanged()
	}

	fun setAtlasSize(size: Int) {
		val minRequired = _state.value.minRequiredAtlasSize()
		val validSize = maxOf(size, minRequired)
		_state.update { it.copy(atlasSize = validSize) }
		schedulePreviewRebuild()
		editorChanged()
	}

	fun setMeshSpacing(spacing: Int) {
		_state.update { it.copy(meshSpacing = spacing.coerceIn(16, 128), meshMaxEdgeDistance = spacing.toFloat(), meshInteriorDensity = spacing.toFloat()) }
		schedulePreviewRebuild()
	    editorChanged()
	}

	fun setMeshOuterMargin(margin: Float) {
		_state.update { it.copy(meshOuterMargin = margin.coerceIn(0f, 32f)) }
		schedulePreviewRebuild()
	    editorChanged()
	}

	fun setMeshInnerMargin(margin: Float) {
		_state.update { it.copy(meshInnerMargin = margin.coerceIn(0.5f, 32f)) }
		schedulePreviewRebuild()
	    editorChanged()
	}

	fun setMeshMaxEdgeDistance(distance: Float) {
		_state.update { it.copy(meshMaxEdgeDistance = distance.coerceIn(6f, 128f), meshSpacing = distance.toInt().coerceIn(16, 128)) }
		schedulePreviewRebuild()
	    editorChanged()
	}

	fun setMeshInteriorDensity(density: Float) {
		_state.update { it.copy(meshInteriorDensity = density.coerceIn(6f, 128f)) }
		schedulePreviewRebuild()
	    editorChanged()
	}

	fun setPartMeshSettings(layerId: String, settings: MeshSettings) {
		_state.update { it.copy(meshOverrides = it.meshOverrides + (layerId to settings)) }
		schedulePreviewRebuild()
	    editorChanged()
	}

	fun resetPartMeshSettings(layerId: String) {
		_state.update { it.copy(meshOverrides = it.meshOverrides - layerId) }
		schedulePreviewRebuild()
	    editorChanged()
	}

	fun setHeadStrength(strength: Float) {
		_state.update { it.copy(headStrength = strength.coerceIn(0f, 4f)) }
		schedulePreviewRebuild()
	    editorChanged()
	}

	fun setBodyStrength(strength: Float) {
		_state.update { it.copy(bodyStrength = strength.coerceIn(0f, 4f)) }
		schedulePreviewRebuild()
	    editorChanged()
	}

	fun setTexturePadding(padding: Int) {
		_state.update { it.copy(texturePadding = padding.coerceIn(0, 32)) }
		schedulePreviewRebuild()
	    editorChanged()
	}

	fun setAlphaThreshold(threshold: Int) {
		_state.update { it.copy(alphaThreshold = threshold.coerceIn(0, 255)) }
		schedulePreviewRebuild()
	    editorChanged()
	}

    fun setMouthOutlineEnabled(enabled: Boolean) {
        _state.update { it.copy(mouthOutlineEnabled = enabled) }
        schedulePreviewRebuild()
        editorChanged()
    }
    fun setMouthShape(shape: String) {
        require(shape in listOf("flat", "smile", "w"))
        _state.update { it.copy(mouthShape = shape, mouthCurve = io.github.psd2live.core.MouthCurve.preset(shape)) }
        schedulePreviewRebuild()
        editorChanged()
    }
    fun setMouthSettings(shape: String, curve: io.github.psd2live.core.MouthCurve, color: Int?, thickness: Float) {
        require(shape in io.github.psd2live.core.MouthCurve.presets + "custom")
        require(color == null || color in 0..0xFFFFFF)
        require(thickness.isFinite() && thickness in 0.5f..8f)
        _state.update { it.copy(mouthShape = shape, mouthCurve = curve, mouthColor = color, mouthThickness = thickness) }
        schedulePreviewRebuild()
        editorChanged()
    }

    fun setMouthShapeCurve(shape: String, curve: io.github.psd2live.core.MouthCurve) {
        require(shape in io.github.psd2live.core.MouthCurve.presets + "custom")
        _state.update { it.copy(mouthShape = shape, mouthCurve = curve) }
        schedulePreviewRebuild()
        editorChanged()
    }

    fun setMouthThickness(thickness: Float) {
        val clamped = thickness.coerceIn(0.5f, 8f)
        _state.update { it.copy(mouthThickness = clamped) }
        schedulePreviewRebuild()
        editorChanged()
    }

    fun setMouthColor(color: Int?) {
        require(color == null || color in 0..0xFFFFFF)
        _state.update { it.copy(mouthColor = color) }
        schedulePreviewRebuild()
        editorChanged()
    }

	fun setMeshOnly(enabled: Boolean) {
		_state.update { current ->
			val updated = current.copy(meshOnly = enabled, generateDeformers = !enabled)
			if (enabled) {
				val defaults = current.previewModel?.rig?.puppet?.parameters?.associate { it.id to it.default } ?: emptyMap()
				val resetMap = defaults.filterKeys { it !in current.lockedParameters }
				updated.copy(parameterValues = current.parameterValues + resetMap)
			} else updated
		}
		if (enabled) {
			frontHair = 0f
			frontHairVelocity = 0f
			backHair = 0f
			backHairVelocity = 0f
			eyeJellyDynamics.reset()
			followX = 0f
			followY = 0f
			previousFollowX = 0f
			activeSoftwareMotionName = null
		}
		schedulePreviewRebuild()
	    editorChanged()
	}

	fun setGenerateDeformers(enabled: Boolean) {
		_state.update { it.copy(generateDeformers = enabled) }
		schedulePreviewRebuild()
	    editorChanged()
	}

	fun setFeatureDisplacementEnabled(enabled: Boolean) {
		_state.update { it.copy(featureDisplacementEnabled = enabled) }
		schedulePreviewRebuild()
		editorChanged()
	}

	fun setExportMotions(enabled: Boolean) {
		_state.update { it.copy(exportMotions = enabled) }
		scheduleRuntimeBundleUpdate()
	    editorChanged()
	}

	fun setMotionIdle(enabled: Boolean) {
		_state.update { current ->
			val next = current.copy(motionIdle = enabled)
			val updated = next.copy(exportMotions = next.motionIdle || next.motionBlink || next.motionNod || next.motionShake)
			if (!enabled) {
				val idleReset = mapOf(
					StandardParameters.ANGLE_X to 0f,
					StandardParameters.ANGLE_Y to 0f,
					StandardParameters.ANGLE_Z to 0f,
					StandardParameters.BODY_X to 0f,
					StandardParameters.BODY_Y to 0f,
					StandardParameters.BODY_Z to 0f,
					StandardParameters.BREATH to 0f,
					StandardParameters.MOUTH_OPEN to 0f,
					StandardParameters.MOUTH_FORM to 0f,
				).filterKeys { key -> key !in updated.lockedParameters }
				updated.copy(parameterValues = updated.parameterValues + idleReset)
			} else updated
		}
		if (!enabled) {
			followX = 0f
			followY = 0f
			previousFollowX = 0f
		}
		scheduleRuntimeBundleUpdate()
	    editorChanged()
	}

	fun setMotionBlink(enabled: Boolean) {
		_state.update { current ->
			val next = current.copy(motionBlink = enabled)
			val updated = next.copy(exportMotions = next.motionIdle || next.motionBlink || next.motionNod || next.motionShake)
			if (!enabled) {
				val blinkReset = mapOf(
					StandardParameters.EYE_L_OPEN to 1.0f,
					StandardParameters.EYE_R_OPEN to 1.0f,
				).filterKeys { key -> key !in updated.lockedParameters }
				updated.copy(parameterValues = updated.parameterValues + blinkReset)
			} else updated
		}
		scheduleRuntimeBundleUpdate()
	    editorChanged()
	}

	fun setMotionNod(enabled: Boolean) {
		_state.update { current ->
			val next = current.copy(motionNod = enabled)
			val updated = next.copy(exportMotions = next.motionIdle || next.motionBlink || next.motionNod || next.motionShake)
			if (!enabled && activeSoftwareMotionName == "nod") {
				val nodReset = mapOf(
					StandardParameters.ANGLE_Y to 0f,
					StandardParameters.BODY_Y to 0f,
				).filterKeys { key -> key !in updated.lockedParameters }
				updated.copy(parameterValues = updated.parameterValues + nodReset)
			} else updated
		}
		if (!enabled && activeSoftwareMotionName == "nod") activeSoftwareMotionName = null
		scheduleRuntimeBundleUpdate()
		if (enabled) triggerMotion("Nod")
	    editorChanged()
	}

	fun setMotionShake(enabled: Boolean) {
		_state.update { current ->
			val next = current.copy(motionShake = enabled)
			val updated = next.copy(exportMotions = next.motionIdle || next.motionBlink || next.motionNod || next.motionShake)
			if (!enabled && activeSoftwareMotionName == "shake") {
				val shakeReset = mapOf(
					StandardParameters.ANGLE_X to 0f,
					StandardParameters.BODY_X to 0f,
					StandardParameters.ANGLE_Z to 0f,
				).filterKeys { key -> key !in updated.lockedParameters }
				updated.copy(parameterValues = updated.parameterValues + shakeReset)
			} else updated
		}
		if (!enabled && activeSoftwareMotionName == "shake") activeSoftwareMotionName = null
		scheduleRuntimeBundleUpdate()
		if (enabled) triggerMotion("Shake")
	    editorChanged()
	}

	fun setGeneratePhysics(enabled: Boolean) {
		_state.update { current ->
			val updated = current.copy(generatePhysics = enabled)
			if (!enabled) {
				val physReset = mapOf(
					StandardParameters.HAIR_FRONT to 0f,
					StandardParameters.HAIR_BACK to 0f,
					StandardParameters.EYE_BALL_FORM to 0f,
				).filterKeys { key -> key !in updated.lockedParameters }
				updated.copy(parameterValues = updated.parameterValues + physReset)
			} else updated
		}
		if (!enabled) {
			frontHair = 0f
			frontHairVelocity = 0f
			backHair = 0f
			backHairVelocity = 0f
			eyeJellyDynamics.reset()
		}
		scheduleRuntimeBundleUpdate()
	    editorChanged()
	}

	fun setPhysicsFrontHair(enabled: Boolean) {
		_state.update { current ->
			val next = current.copy(physicsFrontHair = enabled)
			val updated = next.copy(generatePhysics = next.physicsFrontHair || next.physicsBackHair || next.physicsEyeJelly)
			if (!enabled) {
				val hairReset = mapOf(
					StandardParameters.HAIR_FRONT to 0f,
				).filterKeys { key -> key !in updated.lockedParameters }
				updated.copy(parameterValues = updated.parameterValues + hairReset)
			} else updated
		}
		if (!enabled) {
			frontHair = 0f
			frontHairVelocity = 0f
		}
		scheduleRuntimeBundleUpdate()
	    editorChanged()
	}

	fun setPhysicsBackHair(enabled: Boolean) {
		_state.update { current ->
			val next = current.copy(physicsBackHair = enabled)
			val updated = next.copy(generatePhysics = next.physicsFrontHair || next.physicsBackHair || next.physicsEyeJelly)
			if (!enabled) {
				val hairReset = mapOf(
					StandardParameters.HAIR_BACK to 0f,
				).filterKeys { key -> key !in updated.lockedParameters }
				updated.copy(parameterValues = updated.parameterValues + hairReset)
			} else updated
		}
		if (!enabled) {
			backHair = 0f
			backHairVelocity = 0f
		}
		scheduleRuntimeBundleUpdate()
	    editorChanged()
	}

	fun setPhysicsEyeJelly(enabled: Boolean) {
		_state.update { current ->
			val next = current.copy(physicsEyeJelly = enabled)
			val updated = next.copy(generatePhysics = next.physicsFrontHair || next.physicsBackHair || next.physicsEyeJelly)
			if (!enabled) {
				val jellyReset = mapOf(
					StandardParameters.EYE_BALL_FORM to 0f,
				).filterKeys { key -> key !in updated.lockedParameters }
				updated.copy(parameterValues = updated.parameterValues + jellyReset)
			} else updated
		}
		if (!enabled) {
			eyeJellyDynamics.reset()
		}
		scheduleRuntimeBundleUpdate()
	    editorChanged()
	}

	fun upsertPhysicsEdit(edit: RigPhysicsEdit) {
		_state.update { current ->
			val existing = current.rigEdits.physicsEdits
			val index = existing.indexOfFirst { it.id == edit.id || it.outputParameter == edit.outputParameter }
			val nextList = if (index >= 0) {
				existing.toMutableList().also { it[index] = edit }
			} else {
				existing + edit
			}
			current.copy(
				rigEdits = current.rigEdits.copy(physicsEdits = nextList),
				generatePhysics = true,
			)
		}
		scheduleRuntimeBundleUpdate()
		editorChanged()
	}

	fun removePhysicsEdit(id: String) {
		_state.update { current ->
			val nextList = current.rigEdits.physicsEdits.filterNot { it.id == id || it.outputParameter == id }
			current.copy(rigEdits = current.rigEdits.copy(physicsEdits = nextList))
		}
		scheduleRuntimeBundleUpdate()
		editorChanged()
	}

	fun setExportCmo3(enabled: Boolean) {
		_state.update { it.copy(exportCmo3 = enabled) }
	    editorChanged()
	}

	fun setExportMoc3(enabled: Boolean) {
		_state.update { it.copy(exportMoc3 = enabled) }
	    editorChanged()
	}

	fun setExportJson(enabled: Boolean) {
		_state.update { it.copy(exportJson = enabled) }
	    editorChanged()
	}

	fun setRuntimeTarget(target: org.umamo.runtime.model.RuntimeTarget) {
		_state.update { it.copy(runtimeTarget = target) }
		schedulePreviewRebuild()
		editorChanged()
	}

	fun setExportHiddenParts(enabled: Boolean) {
		_state.update { it.copy(exportHiddenParts = enabled) }
		editorChanged()
	}

	fun setExportHiddenDrawables(enabled: Boolean) {
		_state.update { it.copy(exportHiddenDrawables = enabled) }
		editorChanged()
	}

	fun setExportGuideImageParts(enabled: Boolean) {
		_state.update { it.copy(exportGuideImageParts = enabled) }
		editorChanged()
	}

	fun setExportIncludePhysics(enabled: Boolean) {
		_state.update { it.copy(exportIncludePhysics = enabled) }
		editorChanged()
	}

	fun setExportIncludeUserData(enabled: Boolean) {
		_state.update { it.copy(exportIncludeUserData = enabled) }
		editorChanged()
	}

	fun setExportIncludeDisplayInfo(enabled: Boolean) {
		_state.update { it.copy(exportIncludeDisplayInfo = enabled) }
		editorChanged()
	}

	fun setExportPixelsPerUnit(value: Float?) {
		_state.update { it.copy(exportPixelsPerUnit = value?.takeIf { v -> v > 0f }) }
		editorChanged()
	}

	fun setExportOptionsExpanded(expanded: Boolean) {
		_state.update { it.copy(exportOptionsExpanded = expanded) }
	    markWorkspaceChanged()
	}

	fun setMotionSubExpanded(expanded: Boolean) {
		_state.update { it.copy(motionSubExpanded = expanded) }
	    markWorkspaceChanged()
	}

	fun setPhysicsSubExpanded(expanded: Boolean) {
		_state.update { it.copy(physicsSubExpanded = expanded) }
	    markWorkspaceChanged()
	}

	fun setDynamicsSubExpanded(expanded: Boolean) {
		_state.update { it.copy(dynamicsSubExpanded = expanded) }
	    markWorkspaceChanged()
	}

	fun setProjectOutputsExpanded(expanded: Boolean) {
		_state.update { it.copy(projectOutputsExpanded = expanded) }
	    markWorkspaceChanged()
	}

	fun setTextureSubExpanded(expanded: Boolean) {
		_state.update { it.copy(textureSubExpanded = expanded) }
	    markWorkspaceChanged()
	}

	fun setMeshSubExpanded(expanded: Boolean) {
		_state.update { it.copy(meshSubExpanded = expanded) }
	    markWorkspaceChanged()
	}

	fun setStrengthSubExpanded(expanded: Boolean) {
		_state.update { it.copy(strengthSubExpanded = expanded) }
	    markWorkspaceChanged()
	}

	fun setAdvancedExpanded(expanded: Boolean) {
		_state.update { it.copy(advancedExpanded = expanded) }
	    markWorkspaceChanged()
	}

	fun resetSettingsToDefault() {
		_state.update {
			it.copy(
				atlasSize = 4096,
                textureUpscale = io.github.psd2live.core.TextureUpscaleConfig(),
				textureSubExpanded = false,
				meshSubExpanded = false,
				strengthSubExpanded = false,
				dynamicsSubExpanded = false,
				meshSpacing = 40,
				meshOuterMargin = 1.0f,
				meshInnerMargin = 10.0f,
				meshMaxEdgeDistance = 6.0f,
				meshInteriorDensity = 40.0f,
				meshOverrides = emptyMap(),
				texturePadding = 2,
				alphaThreshold = 8,
				headStrength = 1.0f,
				bodyStrength = 1.0f,
				meshOnly = false,
				generateDeformers = true,
				featureDisplacementEnabled = true,
                mouthOutlineEnabled = true,
                mouthShape = "smile",
                mouthCurve = io.github.psd2live.core.MouthCurve.preset("smile"),
                mouthColor = null,
                mouthThickness = 1.5f,
				exportMotions = true,
				motionIdle = true,
				motionBlink = true,
				motionNod = true,
				motionShake = true,
				generatePhysics = true,
				physicsFrontHair = true,
				physicsBackHair = true,
				physicsEyeJelly = true,
				exportCmo3 = true,
				exportMoc3 = true,
				exportJson = true,
				runtimeTarget = org.umamo.runtime.model.RuntimeTarget.Cubism50,
				exportHiddenParts = false,
				exportHiddenDrawables = false,
				exportGuideImageParts = false,
				exportIncludePhysics = true,
				exportIncludeUserData = true,
				exportIncludeDisplayInfo = true,
				exportPixelsPerUnit = null,
			)
		}
		schedulePreviewRebuild()
	    editorChanged()
	}

	fun setLanguage(language: AppLanguage) {
		I18n.setLanguage(language)
		_state.update { it.copy(currentLanguage = language) }
		schedulePreviewRebuild()
	}

	private val zoomScaleSteps = listOf(1.0f, 1.15f, 1.25f, 1.35f, 1.5f, 1.75f, 2.0f, 2.25f, 2.5f)

	fun setUiScale(scale: Float) {
		val clamped = (kotlin.math.round(scale.coerceIn(0.75f, 3.0f) * 100) / 100f)
		AppSettings.uiScale = clamped
		_state.update { it.copy(uiScale = clamped) }
	}

	fun setFontScale(scale: Float) {
		val clamped = (kotlin.math.round(scale.coerceIn(0.85f, 1.5f) * 100) / 100f)
		AppSettings.fontScale = clamped
		_state.update { it.copy(fontScale = clamped) }
	}

	fun zoomIn() {
		val current = _state.value.uiScale
		val next = zoomScaleSteps.firstOrNull { it > current + 0.03f } ?: (current + 0.25f).coerceAtMost(3.0f)
		setUiScale(next)
	}

	fun zoomOut() {
		val current = _state.value.uiScale
		val next = zoomScaleSteps.asReversed().firstOrNull { it < current - 0.03f } ?: (current - 0.25f).coerceAtLeast(0.75f)
		setUiScale(next)
	}

	fun resetZoom() {
		val def = AppSettings.defaultUiScale()
		setUiScale(def)
		setFontScale(1.0f)
	}

	/**
	 * Restores the interaction preferences that [AppSettings.resetToDefaults] clears on disk.
	 *
	 * Deliberately not routed through [setClickToSelectLayer], which marks the project dirty: a
	 * global UI preference is not part of the project, and "Reset Defaults" must not leave an opened
	 * project looking unsaved.
	 */
	fun resetInteractionPrefs() {
		AppSettings.clickToSelectLayer = true
		_state.update { it.copy(clickToSelectLayer = true) }
	}

	fun openSettingsDialog() {
		_state.update { it.copy(showSettingsDialog = true) }
	}

	fun closeSettingsDialog() {
		_state.update {
			it.copy(
				showSettingsDialog = false,
				// A capture left dangling would swallow every key from here on.
				keyCapture = null,
				focusCanvasRequest = it.focusCanvasRequest + 1,
			)
		}
	}

	// -------------------------------------------------------------------------------------
	// Keyboard shortcuts
	//
	// None of these mark the project dirty — the keymap is an application preference, not project
	// content. Persistence is write-through so the state always mirrors what is on disk.
	// -------------------------------------------------------------------------------------

	/** Starts recording a replacement for the binding at [index] (use size to append). */
	fun beginKeyCapture(action: ShortcutAction, index: Int) {
		_state.update { it.copy(keyCapture = KeyCapture(action, index)) }
	}

	fun cancelKeyCapture() {
		_state.update { it.copy(keyCapture = null) }
	}

	/**
	 * Feeds one key event to the active capture. `Esc` abandons it; a refused chord is reported back
	 * through [KeyCapture.feedback] and recording continues.
	 */
	fun captureKeyEvent(event: KeyEvent) {
		val capture = _state.value.keyCapture ?: return
		if (event.key == Key.Escape) {
			cancelKeyCapture()
			return
		}
		if (isModifierKey(event.key)) return
		val binding = keyBindingOf(event)
		val check = _state.value.keymap.validateCapture(capture.action, capture.index, binding)
		if (check != CaptureCheck.Ok) {
			_state.update { it.copy(keyCapture = capture.copy(feedback = check)) }
			return
		}
		val updated = _state.value.keymap.bindingsFor(capture.action).toMutableList()
		if (capture.index < updated.size) updated[capture.index] = binding else updated.add(binding)
		applyBindings(capture.action, updated)
		cancelKeyCapture()
	}

	fun addKeyBinding(action: ShortcutAction) {
		beginKeyCapture(action, _state.value.keymap.bindingsFor(action).size)
	}

	fun removeKeyBinding(action: ShortcutAction, index: Int) {
		val updated = _state.value.keymap.bindingsFor(action).toMutableList()
		if (index !in updated.indices) return
		updated.removeAt(index)
		applyBindings(action, updated)
		if (_state.value.keyCapture?.action == action) cancelKeyCapture()
	}

	/** Drops the override so the action falls back to whatever the active preset defines. */
	fun resetKeyBinding(action: ShortcutAction) {
		AppSettings.removeKeymapOverride(action)
		_state.update { it.copy(keymap = loadPersistedKeymap()) }
	}

	fun applyKeymapPreset(preset: KeymapPreset) {
		if (_state.value.keymapPreset == preset) return
		// An override is expressed relative to a base preset, so carrying it across a switch has no
		// defined meaning. Switching is therefore a full reset of the customisations.
		AppSettings.clearKeymap()
		AppSettings.keymapPreset = preset
		_state.update {
			it.copy(keymapPreset = preset, keymap = Keymap.of(preset), keyCapture = null)
		}
	}

	/** Part of "Reset Defaults": back to the shipped Photoshop table with no customisations. */
	fun resetKeymap() {
		AppSettings.clearKeymap()
		_state.update {
			it.copy(keymapPreset = KeymapPreset.PHOTOSHOP, keymap = Keymap.DEFAULT, keyCapture = null)
		}
	}

	/**
	 * Writes [bindings] and refreshes the keymap. A set that matches the preset default drops the
	 * override entirely, so the row's reset button goes back to being disabled.
	 */
	private fun applyBindings(action: ShortcutAction, bindings: List<KeyBinding>) {
		val preset = _state.value.keymapPreset
		if (bindings == Keymap.of(preset).bindingsFor(action)) AppSettings.removeKeymapOverride(action)
		else AppSettings.putKeymapOverride(action, bindings)
		_state.update { it.copy(keymap = loadPersistedKeymap()) }
	}


	fun setActiveTab(id: String) {
        if (_state.value.canvasEditBusy) return
		var changed = false
		_state.update { current ->
			if (current.activeWorkspaceTabId == id || current.workspaceTabs.none { it.id == id }) current
			else {
				changed = true
				val target = current.workspaceTabs.firstOrNull { it.id == id }
				if (target?.kind != WorkspaceTabKind.PREVIEW) {
					pointerActive = false
					activeSoftwareMotionName = null
				}
				current.copy(activeWorkspaceTabId = id)
			}
		}
		if (changed) {
			markWorkspaceChanged()
			if (_state.value.activeTabKind == WorkspaceTabKind.PREVIEW) {
				ensureSdkSessionLoaded()
			}
		}
	}

	/** Adds a tab after the last one of the same kind; [sourceTabId] duplicates that tab's view and camera. */
	fun addTab(kind: WorkspaceTabKind, sourceTabId: String? = null): String {
		val current = _state.value
		val source = sourceTabId?.let { id -> current.workspaceTabs.firstOrNull { it.id == id } }
		val ordinal = (current.workspaceTabs.filter { it.kind == kind }.maxOfOrNull { it.ordinal } ?: 0) + 1
		val tab = WorkspaceTabState(
			id = java.util.UUID.randomUUID().toString(),
			kind = kind,
			ordinal = ordinal,
			pinned = false,
			view = source?.view ?: kind.defaultViewOptions(),
			camera = source?.camera ?: TabCamera(),
		)
		_state.update { it.copy(workspaceTabs = it.workspaceTabs + tab, activeWorkspaceTabId = tab.id) }
		markWorkspaceChanged()
		if (kind == WorkspaceTabKind.PREVIEW) {
			ensureSdkSessionLoaded()
		}
		return tab.id
	}

	fun duplicateActiveTab(): String = duplicateTab(_state.value.activeWorkspaceTab.id)

	/** Duplicates [id]'s kind, view options and camera into a new closable tab. */
	fun duplicateTab(id: String): String {
		val source = _state.value.workspaceTabs.firstOrNull { it.id == id } ?: return addTab(_state.value.activeTabKind)
		// History is a singleton: its zoom, pan and search live in shared state, so a second history
		// tab could never show anything the first one does not. Duplicating just brings it forward.
		if (source.kind == WorkspaceTabKind.HISTORY) {
			setActiveTab(source.id)
			return source.id
		}
		return addTab(source.kind, source.id)
	}

	fun closeTab(id: String) {
		val current = _state.value
		val tab = current.workspaceTabs.firstOrNull { it.id == id } ?: return
		if (tab.pinned) {
			_state.update { it.copy(statusText = tr("status.tabPinned")) }
			return
		}
		val remaining = current.workspaceTabs.filterNot { it.id == id }
		val nextActive = if (current.activeWorkspaceTabId != id) current.activeWorkspaceTabId else {
			val index = current.workspaceTabs.indexOf(tab)
			(remaining.getOrNull(index - 1) ?: remaining.getOrNull(index) ?: remaining.firstOrNull())?.id
				?: PINNED_EDIT_TAB_ID
		}
		_state.update {
			it.copy(
				workspaceTabs = remaining,
				activeWorkspaceTabId = nextActive,
				statusText = tr("status.tabClosed", tabTitle(tab)),
			)
		}
		markWorkspaceChanged()
		if (_state.value.activeTabKind == WorkspaceTabKind.PREVIEW) {
			ensureSdkSessionLoaded()
		}
	}

	/** Activates the existing history tab, or creates one when the workspace has none. */
	fun openHistoryTab() {
		val existing = _state.value.workspaceTabs.firstOrNull { it.kind == WorkspaceTabKind.HISTORY }
		if (existing != null) {
			setActiveTab(existing.id)
		} else {
			addTab(WorkspaceTabKind.HISTORY)
		}
	}

	fun cycleTab(delta: Int) {
		val tabs = _state.value.workspaceTabs
		if (tabs.size < 2) return
		val index = tabs.indexOfFirst { it.id == _state.value.activeWorkspaceTabId }.coerceAtLeast(0)
		val next = ((index + delta) % tabs.size + tabs.size) % tabs.size
		setActiveTab(tabs[next].id)
	}

	fun activateTabByIndex(index: Int) {
		_state.value.workspaceTabs.getOrNull(index)?.let { setActiveTab(it.id) }
	}

	/** Applies the View menu / tab-strip toggles to the active tab. */
	fun setTabViewOptions(options: TabViewOptions) {
		val normalized = options.normalized()
		var changed = false
		_state.update { current ->
			if (current.activeTabView == normalized) current
			else {
				changed = true
				current.updateActiveTab { tab -> tab.copy(view = normalized) }
			}
		}
		if (changed) markWorkspaceChanged()
	}

	/**
	 * Seeds display toggles when entering a hierarchy mode. Presets differ by mode; afterwards the
	 * same toggles apply uniformly — no mode forces overlays that the user turned off.
	 */
	fun applyHierarchyModeViewPreset(mode: EditHierarchyMode) {
		val current = _state.value.activeTabView
		val next = hierarchyModeViewPreset(mode, current)
		if (next != current) setTabViewOptions(next)
	}

	/** Restores the active tab's canvas options to the defaults for its kind. */
	fun resetActiveTabViewOptions() {
		val id = _state.value.activeWorkspaceTab.id
		var changed = false
		_state.update { current ->
			val target = current.workspaceTabs.firstOrNull { it.id == id } ?: return@update current
			val defaults = target.kind.defaultViewOptions()
			if (target.view == defaults) current
			else {
				changed = true
				current.updateTab(id) { tab -> tab.copy(view = defaults) }
			}
		}
		if (changed) markWorkspaceChanged()
	}

	/** Display title of a tab: localized kind name plus its creation ordinal for added tabs. */
	fun tabTitle(tab: WorkspaceTabState): String = tr(tabTitleKey(tab.kind)) + if (tab.ordinal > 1) " ${tab.ordinal}" else ""

	private fun tabTitleKey(kind: WorkspaceTabKind): String = when (kind) {
		WorkspaceTabKind.EDIT -> "tab.edit"
		WorkspaceTabKind.PREVIEW -> "tab.preview"
		WorkspaceTabKind.HISTORY -> "tab.history"
	}

	fun addLog(
		message: String,
		level: LogLevel = LogLevel.INFO,
		source: LogSource = LogSource.SYSTEM,
		tag: String = "",
		imageBytes: ByteArray? = null,
		imageLabel: String? = null,
		detail: String? = null,
	) {
		val entry = AppLogEntry(
			source = source,
			level = level,
			tag = tag,
			message = message,
			detail = detail,
			imageBytes = imageBytes,
			imageLabel = imageLabel,
		)
		_state.update { current ->
			current.copy(
				logLines = current.logLines + message,
				logEntries = current.logEntries + entry,
			)
		}
	}

	fun clearLogs() {
		_state.update { it.copy(logLines = emptyList(), logEntries = emptyList()) }
	    markWorkspaceChanged()
	}

	private fun PSD2LiveState.withLog(
		message: String,
		level: LogLevel = LogLevel.INFO,
		tag: String = "System",
	): PSD2LiveState {
		val entry = AppLogEntry(source = LogSource.SYSTEM, level = level, tag = tag, message = message)
		return copy(logLines = logLines + message, logEntries = logEntries + entry)
	}

	private fun PSD2LiveState.withLogs(
		messages: List<String>,
		level: LogLevel = LogLevel.INFO,
		tag: String = "System",
	): PSD2LiveState {
		val entries = messages.map { AppLogEntry(source = LogSource.SYSTEM, level = level, tag = tag, message = it) }
		return copy(logLines = logLines + messages, logEntries = logEntries + entries)
	}

	fun setLogPanelExpanded(expanded: Boolean) {
		_state.update {
			if (it.logPanelExpanded == expanded) it
			else it.copy(logPanelExpanded = expanded)
		}
	    markWorkspaceChanged()
	}

	fun setLogPanelHeight(height: Float) {
		val clamped = height.coerceIn(80f, 450f)
		var changed = false
		_state.update {
			if (it.logPanelHeight == clamped) it
			else {
				changed = true
				it.copy(logPanelHeight = clamped)
			}
		}
		if (changed) markWorkspaceChanged()
	}

	fun adjustLogPanelHeight(deltaDp: Float, min: Float = 80f, max: Float = 450f) {
		_state.update {
			val next = (it.logPanelHeight + deltaDp).coerceIn(min, max)
			if (next == it.logPanelHeight) it
			else it.copy(logPanelHeight = next)
		}
		markWorkspaceChanged()
	}

	fun openLightbox(imageBytes: ByteArray, title: String? = null) {
		_state.update { it.copy(lightboxImage = imageBytes, lightboxTitle = title) }
	}

	fun closeLightbox() {
		_state.update { it.copy(lightboxImage = null, lightboxTitle = null) }
	}

	fun updateHistorySnapshot(snapshot: AgentHistorySnapshot) {
		_state.update { it.copy(historySnapshot = snapshot, projectDirty = it.projectDirty || (it.historySnapshot != null && it.historySnapshot.headNodeId != snapshot.headNodeId), projectEditVersion = it.projectEditVersion + if (it.historySnapshot?.headNodeId != snapshot.headNodeId) 1 else 0) }
	}

	fun selectHistoryNode(nodeId: String?) {
		_state.update { it.copy(selectedHistoryNodeId = nodeId) }
	    markWorkspaceChanged()
	}

	fun checkoutHistoryNode(nodeId: String) {
		scope.launch {
			try {
				val ws = agentWorkspace ?: throw IllegalStateException("Agent workspace is not attached")
				val result = withContext(Dispatchers.Default) {
					ws.checkoutHistory(nodeId, io.github.psd2live.agent.MutationAuthor.USER)
				}
				// The workspace already logged the checkout; this only reflects it in the status bar.
				_state.update { current ->
					current.copy(
						statusText = result.summary,
						selectedHistoryNodeId = nodeId,
					)
				}
			} catch (failure: Throwable) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
				val err = failure.message ?: failure.javaClass.simpleName
				addLog(
					message = "History checkout failed: $err",
					level = LogLevel.ERROR,
					source = LogSource.EDITOR,
					tag = "History",
				)
				_state.update { it.copy(errorMessage = err) }
			}
		}
	}

	fun setInspectorTab(tab: InspectorTab) {
		_state.update { it.copy(activeInspectorTab = tab) }
	    markWorkspaceChanged()
	}

	fun setAnimationEnabled(enabled: Boolean) {
		_state.update { current ->
			var nextState = current.copy(animationEnabled = enabled)
			if (enabled && current.activeTabKind != WorkspaceTabKind.PREVIEW) {
				val previewTab = current.workspaceTabs.firstOrNull { it.kind == WorkspaceTabKind.PREVIEW }
				if (previewTab != null) {
					nextState = nextState.copy(activeWorkspaceTabId = previewTab.id)
				}
			}
			nextState
		}
		lastTick = System.nanoTime()
	    markWorkspaceChanged()
	}

	fun setParameterSearchQuery(query: String) {
		_state.update { it.copy(parameterSearchQuery = query) }
	    markWorkspaceChanged()
	}

	fun selectLayer(layerId: String?) {
		_state.update {
			it.copy(
				selectedLayerId = layerId,
				selectedDeformerId = if (layerId != null) null else it.selectedDeformerId,
			)
		}
	    markWorkspaceChanged()
	}

	fun selectDeformer(deformerId: String?) {
		_state.update {
			it.copy(
				selectedDeformerId = deformerId,
				selectedLayerId = if (deformerId != null) null else it.selectedLayerId,
			)
		}
	    markWorkspaceChanged()
	}

	fun setClickToSelectLayer(enabled: Boolean) {
		AppSettings.clickToSelectLayer = enabled
		_state.update { it.copy(clickToSelectLayer = enabled) }
		markWorkspaceChanged()
	}

	fun setHoveredItem(layerId: String?, deformerId: String?) {
		_state.update {
			if (it.hoveredLayerId == layerId && it.hoveredDeformerId == deformerId) it
			else it.copy(hoveredLayerId = layerId, hoveredDeformerId = deformerId)
		}
	}

	fun toggleDeformerVisibility(deformerId: String) {
		val current = _state.value.isDeformerVisible(deformerId)
		setDeformerVisibility(deformerId, !current)
	}

	fun setDeformerVisibility(deformerId: String, visible: Boolean) {
		_state.update {
			val updated = it.deformerVisibility + (deformerId to visible)
			it.copy(
				deformerVisibility = updated,
				statusText = tr("status.visibilityChanged"),
			)
		}
		schedulePreviewRebuild()
		editorChanged()
	}

	fun toggleLayerVisibility(layerId: String) {
		val current = _state.value.isLayerVisible(layerId)
		setLayerVisibility(layerId, !current)
	}

	fun setLayerVisibility(layerId: String, visible: Boolean) {
		_state.update {
			val updated = it.layerVisibility + (layerId to visible)
			it.copy(
				layerVisibility = updated,
				statusText = tr("status.visibilityChanged"),
				isolationSnapshot = null,
				isolatedLayerId = null,
			)
		}
		schedulePreviewRebuild()
	    editorChanged()
	}

	fun setAllLayersVisibility(visible: Boolean) {
		val analysis = _state.value.analysis ?: return
		val updated = analysis.layers.associate { it.source.id.raw to visible }
		_state.update {
			it.copy(
				layerVisibility = updated,
				statusText = tr("status.visibilityChanged"),
				isolationSnapshot = null,
				isolatedLayerId = null,
			)
		}
		schedulePreviewRebuild()
	    editorChanged()
	}

	fun invertLayerVisibility() {
		val analysis = _state.value.analysis ?: return
		val current = _state.value
		val updated = analysis.layers.associate { layer ->
			val id = layer.source.id.raw
			id to !current.isLayerVisible(id, layer.source.visible)
		}
		_state.update {
			it.copy(
				layerVisibility = updated,
				statusText = tr("status.visibilityChanged"),
				isolationSnapshot = null,
				isolatedLayerId = null,
			)
		}
		schedulePreviewRebuild()
	    editorChanged()
	}

	fun isolateLayer(layerId: String) {
		val analysis = _state.value.analysis ?: return
		val current = _state.value
		if (current.isolatedLayerId == layerId && current.isolationSnapshot != null) {
			_state.update {
				it.copy(
					layerVisibility = it.isolationSnapshot.orEmpty(),
					isolationSnapshot = null,
					isolatedLayerId = null,
					statusText = tr("status.visibilityChanged"),
				)
			}
		} else {
			val snapshot = current.layerVisibility
			val updated = analysis.layers.associate { it.source.id.raw to (it.source.id.raw == layerId) }
			_state.update {
				it.copy(
					layerVisibility = updated,
					isolationSnapshot = snapshot,
					isolatedLayerId = layerId,
					statusText = tr("status.visibilityChanged"),
				)
			}
		}
		schedulePreviewRebuild()
	    editorChanged()
	}

	fun showOnlyLayers(layerIds: Set<String>) {
		if (layerIds.isEmpty()) return
		val analysis = _state.value.analysis ?: return
		val updated = analysis.layers.associate { it.source.id.raw to (it.source.id.raw in layerIds) }
		_state.update {
			it.copy(
				layerVisibility = updated,
				isolationSnapshot = null,
				isolatedLayerId = null,
				statusText = tr("status.visibilityChanged"),
			)
		}
		schedulePreviewRebuild()
	    editorChanged()
	}

	fun deleteLayer(layerId: String) {
		val analysis = _state.value.analysis
		val layerName = analysis?.layers?.firstOrNull { it.source.id.raw == layerId }?.source?.name ?: layerId
		_state.update { current ->
			current.copy(
				deletedLayerIds = current.deletedLayerIds + layerId,
				selectedLayerId = if (current.selectedLayerId == layerId) null else current.selectedLayerId,
				statusText = tr("status.layerDeleted", layerName),
			)
		}
		schedulePreviewRebuild()
	    editorChanged()
	}

	fun restoreLayer(layerId: String) {
		val analysis = _state.value.analysis
		val layerName = analysis?.layers?.firstOrNull { it.source.id.raw == layerId }?.source?.name ?: layerId
		_state.update { current ->
			current.copy(
				deletedLayerIds = current.deletedLayerIds - layerId,
				statusText = tr("status.layerRestored", layerName),
			)
		}
		schedulePreviewRebuild()
	    editorChanged()
	}

	fun restoreAllDeletedLayers() {
		_state.update { current ->
			current.copy(
				deletedLayerIds = emptySet(),
				statusText = tr("status.allLayersRestored"),
			)
		}
		schedulePreviewRebuild()
	    editorChanged()
	}

	fun reparentItem(childId: String, newParentId: String?) {
		val model = _state.value.previewModel
		val isDeformer = model?.rig?.puppet?.deformers?.any { it.id.raw == childId } ?: false
		val deformerById = model?.rig?.puppet?.deformers?.associateBy { it.id.raw } ?: emptyMap()

		// If child is a deformer, check for cycle
		if (isDeformer && newParentId != null) {
			if (childId == newParentId) return
			var cur: String? = newParentId
			val visited = mutableSetOf(childId)
			while (cur != null) {
				if (!visited.add(cur)) return
				cur = _state.value.parentOverrides[cur] ?: deformerById[cur]?.parent?.raw
			}
		}

		_state.update { current ->
			val updated = current.parentOverrides + (childId to newParentId)
			current.copy(
				parentOverrides = updated,
				statusText = tr("status.hierarchyUpdated"),
			)
		}
		schedulePreviewRebuild()
	    editorChanged()
	}

	/**
	 * Hit-test for external file drops over the hierarchy tree. Registered by [HierarchyTreeList]
	 * while it is composed; coordinates are window-relative (Compose [positionInWindow] space).
	 */
	@Volatile
	var hierarchyImportHitTest: ((windowX: Int, windowY: Int) -> HierarchyImportTarget?)? = null

	/**
	 * Imports transparent rasters as layers under [parentDeformerId] (null = root), then opens the
	 * canvas placement panel for the last imported layer so the artist can fine-tune position.
	 */
	fun importLayersFromFiles(files: List<java.io.File>, parentDeformerId: String?, anchorLabel: String) {
		val rasters = LayerImport.transparentRasterFiles(files)
		if (rasters.isEmpty()) return
		if (_state.value.previewModel == null || _state.value.isBusy) {
			setErrorMessage(tr("error.importLayerBusy"))
			return
		}
		scope.launch {
			try {
				_state.update { it.copy(statusText = tr("status.importingLayers", rasters.size)) }
				val result = withContext(Dispatchers.Default) {
					buildImportedLayersPreview(rasters, parentDeformerId)
				}
				_state.update {
					it.copy(
						parentOverrides = result.parentOverrides,
						layerVisibility = result.layerVisibility,
						layerOverrides = result.layerOverrides,
					)
				}
				applyCommittedPaint(result.preview, tr("editor.importLayer.summary", result.layerIds.size))
				val placeId = result.layerIds.lastOrNull() ?: return@launch
				val placeName = result.preview.analysis.layers
					.firstOrNull { it.source.id.raw == placeId }?.source?.name
					?: placeId
				val bounds = result.preview.analysis.source.layers
					.firstOrNull { it.id.raw == placeId }?.bounds
					?: return@launch
				selectLayer(placeId)
				// Let history-driven gesture cleanup run before arming the placement panel,
				// so a cancel() from head-node churn cannot race the new LAYER session.
				yield()
				canvasEditor.beginLayerPlacement(
					layerId = placeId,
					layerName = placeName,
					anchorLabel = anchorLabel,
					parentDeformerId = parentDeformerId,
					canvasLeft = bounds.left.toFloat(),
					canvasTop = bounds.top.toFloat(),
					canvasWidth = bounds.width.toFloat(),
					canvasHeight = bounds.height.toFloat(),
					cancelLayerIds = result.layerIds,
				)
			} catch (failure: Exception) {
				if (failure is kotlinx.coroutines.CancellationException) throw failure
				setErrorMessage(failure.message ?: tr("error.importLayerFailed"))
			}
		}
	}

	private fun buildImportedLayersPreview(
		files: List<java.io.File>,
		parentDeformerId: String?,
	): ImportedLayersResult {
		val current = _state.value
		val preview = current.previewModel ?: error("No preview model")
		val analysis = preview.analysis
		val canvasW = analysis.source.widthPx
		val canvasH = analysis.source.heightPx
		val existing = analysis.source.layers.toMutableList()
		val addedIds = mutableListOf<String>()
		val overrides = current.parentOverrides.toMutableMap()
		val visibility = current.layerVisibility.toMutableMap()
		val classifications = current.layerOverrides.toMutableMap()
		var nextOrder = (existing.maxOfOrNull { it.order } ?: 0) + 1
		for (file in files) {
			val image = LayerImport.decodeRasterFile(file)
			val name = LayerImport.displayNameOf(file)
			val layer = LayerImport.placedLayer(
				image = image,
				canvasWidth = canvasW,
				canvasHeight = canvasH,
				name = name,
				order = nextOrder++,
			)
			existing += layer
			addedIds += layer.id.raw
			overrides[layer.id.raw] = parentDeformerId
			visibility[layer.id.raw] = true
			classifications[layer.id.raw] = LayerClassificationOverride(
				type = LayerType.PRESET,
				tag = SemanticTag.UNKNOWN,
				side = Side.NONE,
			)
		}
		val newSource = io.github.psd2live.agent.WorkspaceSourceArt(
			widthPx = canvasW,
			heightPx = canvasH,
			layers = existing.mapIndexed { index, layer ->
				val order = existing.size - index
				if (layer is io.github.psd2live.agent.WorkspaceSourceLayer) layer.copy(order = order)
				else io.github.psd2live.agent.WorkspaceSourceLayer.copyOf(layer, order)
			},
			groups = analysis.source.groups,
		)
		val config = current.buildConfig().copy(
			parentOverrides = overrides,
			layerVisibility = visibility,
			layerOverrides = classifications,
		)
		val built = pipeline.buildPreview(newSource, config)
		return ImportedLayersResult(built, addedIds, overrides, visibility, classifications)
	}

	private data class ImportedLayersResult(
		val preview: RigPreviewModel,
		val layerIds: List<String>,
		val parentOverrides: Map<String, String?>,
		val layerVisibility: Map<String, Boolean>,
		val layerOverrides: Map<String, LayerClassificationOverride>,
	)

	/**
	 * Moves/resizes an imported layer's canvas bounds and rebuilds its mesh.
	 * [commitHistory] false is for live field scrubbing; true records an undoable step.
	 */
	fun relocateImportedLayer(
		layerId: String,
		name: String,
		left: Float,
		top: Float,
		width: Float,
		height: Float,
		commitHistory: Boolean = true,
	) {
		val current = _state.value
		val preview = current.previewModel ?: return
		val analysis = preview.analysis
		val w = width.roundToInt().coerceAtLeast(1)
		val h = height.roundToInt().coerceAtLeast(1)
		val newBounds = org.umamo.format.art.LayerBounds(
			left.roundToInt(),
			top.roundToInt(),
			w,
			h,
		)
		val existing = analysis.source.layers.firstOrNull { it.id.raw == layerId } ?: return
		if (existing.bounds == newBounds && (name.isBlank() || name == existing.name)) return
		val updatedLayers = analysis.source.layers.map { layer ->
			if (layer.id.raw != layerId) layer
			else {
				val base = if (layer is io.github.psd2live.agent.WorkspaceSourceLayer) layer
				else io.github.psd2live.agent.WorkspaceSourceLayer.copyOf(layer, layer.order) as io.github.psd2live.agent.WorkspaceSourceLayer
				base.copy(name = name.ifBlank { base.name }, bounds = newBounds)
			}
		}
		val newSource = io.github.psd2live.agent.WorkspaceSourceArt(
			widthPx = analysis.source.widthPx,
			heightPx = analysis.source.heightPx,
			layers = updatedLayers,
			groups = analysis.source.groups,
		)
		scope.launch {
			try {
				val built = withContext(Dispatchers.Default) {
					pipeline.buildPreview(newSource, current.buildConfig())
				}
				if (commitHistory) {
					applyCommittedPaint(built, tr("editor.importLayer.placed", name.ifBlank { layerId }))
				} else {
					applyPreviewWithoutHistory(built)
				}
				selectLayer(layerId)
				// Keep paint session on the relocated layer if the artist was painting it.
				if (canvasEditor.hierarchyMode == EditHierarchyMode.PAINT) {
					canvasEditor.startPaintSession(layerId, forceReload = true)
				}
			} catch (failure: Exception) {
				if (failure is kotlinx.coroutines.CancellationException) throw failure
				setErrorMessage(failure.message ?: tr("error.importLayerFailed"))
			}
		}
	}

	/** Publish a rebuilt preview without opening a history node (live placement scrub). */
	private fun applyPreviewWithoutHistory(updatedPreview: RigPreviewModel) {
		_state.update {
			it.copy(
				previewModel = updatedPreview,
				analysis = updatedPreview.analysis,
				previewModelDirty = true,
				projectDirty = true,
			)
		}
		refreshSdkSession(updatedPreview)
		markWorkspaceChanged()
	}

	/** Removes layers created by a cancelled import placement session. */
	fun cancelImportedLayerPlacement(layerIds: List<String>) {
		if (layerIds.isEmpty()) return
		val current = _state.value
		val preview = current.previewModel ?: return
		val analysis = preview.analysis
		val remaining = analysis.source.layers.filterNot { it.id.raw in layerIds }
		if (remaining.size == analysis.source.layers.size) {
			layerIds.forEach { deleteLayer(it) }
			return
		}
		if (remaining.none { it.raster.width > 0 && it.raster.height > 0 }) {
			layerIds.forEach { deleteLayer(it) }
			return
		}
		val newSource = io.github.psd2live.agent.WorkspaceSourceArt(
			widthPx = analysis.source.widthPx,
			heightPx = analysis.source.heightPx,
			layers = remaining.mapIndexed { index, layer ->
				val order = remaining.size - index
				if (layer is io.github.psd2live.agent.WorkspaceSourceLayer) layer.copy(order = order)
				else io.github.psd2live.agent.WorkspaceSourceLayer.copyOf(layer, order)
			},
			groups = analysis.source.groups,
		)
		scope.launch {
			try {
				_state.update {
					it.copy(
						parentOverrides = it.parentOverrides - layerIds.toSet(),
						layerVisibility = it.layerVisibility - layerIds.toSet(),
						layerOverrides = it.layerOverrides - layerIds.toSet(),
						deletedLayerIds = it.deletedLayerIds - layerIds.toSet(),
						selectedLayerId = it.selectedLayerId?.takeUnless { id -> id in layerIds },
					)
				}
				val built = withContext(Dispatchers.Default) {
					pipeline.buildPreview(newSource, _state.value.buildConfig())
				}
				applyCommittedPaint(built, tr("editor.importLayer.cancelled"))
			} catch (failure: Exception) {
				if (failure is kotlinx.coroutines.CancellationException) throw failure
				layerIds.forEach { deleteLayer(it) }
			}
		}
	}

	fun resetHierarchyOverrides() {
		_state.update { current ->
			current.copy(
				parentOverrides = emptyMap(),
				statusText = tr("status.hierarchyReset"),
			)
		}
		schedulePreviewRebuild()
	    editorChanged()
	}

	fun resetItemHierarchy(itemId: String) {
		_state.update { current ->
			current.copy(
				parentOverrides = current.parentOverrides - itemId,
				statusText = tr("status.hierarchyUpdated"),
			)
		}
		schedulePreviewRebuild()
	    editorChanged()
	}

	fun setLayerDrawOrder(targetId: String, order: Float) {
		val clamped = order.coerceIn(0f, 1000f)
		val model = _state.value.previewModel
		val layerId = model?.rig?.layerIdByDrawableId?.get(targetId) ?: targetId
		_state.update { current ->
			val updated = current.drawOrderOverrides + (layerId to clamped)
			current.copy(drawOrderOverrides = updated)
		}
		editorChanged()
	}

	fun resetLayerDrawOrder(targetId: String) {
		val model = _state.value.previewModel
		val layerId = model?.rig?.layerIdByDrawableId?.get(targetId) ?: targetId
		_state.update { current ->
			current.copy(drawOrderOverrides = current.drawOrderOverrides - layerId - targetId)
		}
		editorChanged()
	}

	fun resetAllDrawOrders() {
		_state.update { current ->
			current.copy(drawOrderOverrides = emptyMap())
		}
		editorChanged()
	}

	fun setLayerClassification(layerId: String, override: LayerClassificationOverride) {
		_state.update {
			it.copy(
				layerOverrides = it.layerOverrides + (layerId to override),
				statusText = tr("status.classificationChanged"),
			)
		}
		schedulePreviewRebuild()
	    editorChanged()
	}

	fun toggleParameterLock(id: ParameterId, currentValue: Float? = null) {
		_state.update { current ->
			val wasLocked = id in current.lockedParameters
			if (wasLocked) {
				current.copy(
					lockedParameters = current.lockedParameters - id,
				)
			} else {
				val model = current.previewModel
				val param = model?.rig?.puppet?.parameters?.firstOrNull { it.id == id }
				val defaultVal = param?.default ?: 0f
				val valueToLock = (currentValue ?: current.parameterValues[id] ?: defaultVal).let { v ->
					if (param != null) v.coerceIn(param.min, param.max) else v
				}
				current.copy(
					lockedParameters = current.lockedParameters + id,
					parameterValues = current.parameterValues + (id to valueToLock),
				)
			}
		}
	    markWorkspaceChanged()
	}

	fun setParameterValue(id: ParameterId, value: Float) {
		_state.update { current ->
			val model = current.previewModel
			val param = model?.rig?.puppet?.parameters?.firstOrNull { it.id == id }
			val clamped = if (param != null) value.coerceIn(param.min, param.max) else value
			current.copy(
				parameterValues = current.parameterValues + (id to clamped),
			)
		}
	    markWorkspaceChanged()
	}

	private var parameterSnapJob: Job? = null

	/** True while a snap-to-nearest-key animation is running. */
	val isSnappingParameters: Boolean get() = parameterSnapJob?.isActive == true

	/**
	 * If the current pose sits between keys on any axis of [kind]/[id], animates those parameters to
	 * the nearest key (Cubism-style) then invokes [onReady]. Returns true when a snap animation
	 * started; false when already on-key (and [onReady] has already been called).
	 */
	fun snapToNearestKeys(kind: String, id: String, onReady: () -> Unit): Boolean {
		val puppet = _state.value.previewModel?.rig?.puppet ?: return false
		val axes = puppet.keyformAxesFor(kind, id)
		return snapAxesToNearestKeys(axes, onReady)
	}

	/** Same as [snapToNearestKeys] for the union of axes across several edit targets. */
	fun snapTargetsToNearestKeys(targets: List<Pair<String, String>>, onReady: () -> Unit): Boolean {
		val puppet = _state.value.previewModel?.rig?.puppet ?: return false
		if (targets.isEmpty()) return false
		val axes = targets.flatMap { (kind, id) -> puppet.keyformAxesFor(kind, id) }
			.distinctBy { it.parameterId to it.keys.contentHashCode() }
		return snapAxesToNearestKeys(axes, onReady)
	}

	fun snapAxesToNearestKeys(axes: List<org.umamo.runtime.model.KeyformAxis>, onReady: () -> Unit): Boolean {
		val puppet = _state.value.previewModel?.rig?.puppet ?: return false
		if (axes.isEmpty()) return false
		val pose = _state.value.parameterValues
		val defaults = puppet.parameters.associate { it.id to it.default }
		val targets = io.github.psd2live.ui.nearestKeyPose(axes, pose, defaults)
		if (targets.isEmpty()) return false
		parameterSnapJob?.cancel()
		parameterSnapJob = scope.launch {
			try {
				animateParameterValues(targets, durationMs = 220L)
			} finally {
				onReady()
			}
		}
		return true
	}

	private suspend fun animateParameterValues(targets: Map<ParameterId, Float>, durationMs: Long) {
		val startValues = _state.value.parameterValues.toMap()
		val from = targets.mapValues { (id, _) -> startValues[id] ?: targets.getValue(id) }
		val startedAt = System.nanoTime()
		while (true) {
			val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
			val t = (elapsedMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
			// Smoothstep ease-in-out.
			val eased = t * t * (3f - 2f * t)
			_state.update { current ->
				val next = current.parameterValues.toMutableMap()
				for ((id, to) in targets) {
					val a = from[id] ?: to
					next[id] = a + (to - a) * eased
				}
				current.copy(parameterValues = next, lockedParameters = current.lockedParameters + targets.keys)
			}
			if (t >= 1f) break
			delay(16L)
		}
		_state.update { current ->
			current.copy(parameterValues = current.parameterValues + targets)
		}
		markWorkspaceChanged()
	}

	fun resetParameter(id: ParameterId) {
		_state.update { current ->
			val model = current.previewModel
			val param = model?.rig?.puppet?.parameters?.firstOrNull { it.id == id }
			val defaultVal = param?.default ?: 0f
			current.copy(
				lockedParameters = current.lockedParameters + id,
				parameterValues = current.parameterValues + (id to defaultVal),
			)
		}
		if (id == StandardParameters.ANGLE_X || id == StandardParameters.EYE_BALL_X || id == StandardParameters.BODY_X) {
			followX = 0f
			pointerX = 0f
		}
		if (id == StandardParameters.ANGLE_Y || id == StandardParameters.EYE_BALL_Y || id == StandardParameters.BODY_Y) {
			followY = 0f
			pointerY = 0f
		}
	    markWorkspaceChanged()
	}

	fun resetAllParameters() {
		pointerActive = false
		pointerX = 0f
		pointerY = 0f
		followX = 0f
		followY = 0f
		previousFollowX = 0f
		frontHair = 0f
		backHair = 0f
		frontHairVelocity = 0f
		backHairVelocity = 0f
		eyeJellyDynamics.reset()
		elapsed = 0.0
		activeSoftwareMotionName = null
		lastTick = System.nanoTime()

		_state.update { current ->
			val model = current.previewModel
			val defaults = model?.rig?.puppet?.parameters?.associate { it.id to it.default } ?: emptyMap()
			current.copy(
				animationEnabled = false,
				lockedParameters = emptySet(),
				parameterValues = defaults,
				previewParameterValues = defaults,
			)
		}
	    markWorkspaceChanged()
	}

	fun unlockAllParameters() {
		_state.update { current ->
			current.copy(
				lockedParameters = emptySet(),
			)
		}
	    markWorkspaceChanged()
	}

	fun setMouseTrackingEnabled(enabled: Boolean) {
		_state.update { it.copy(mouseTrackingEnabled = enabled) }
		if (!enabled) {
			pointerActive = false
			pointerX = 0f
			pointerY = 0f
		}
	    markWorkspaceChanged()
	}

	fun updatePointer(screenNormX: Float, screenNormY: Float) {
		pointerActive = true
		pointerX = screenNormX.coerceIn(-1f, 1f)
		pointerY = screenNormY.coerceIn(-1f, 1f)
	}

	fun clearPointer() {
		pointerActive = false
	}

	fun clearErrorMessage() {
		_state.update { it.copy(errorMessage = null) }
	}

	fun setErrorMessage(message: String?) {
		_state.update { it.copy(errorMessage = message) }
	}

	fun setStatusText(text: String) {
		_state.update { it.copy(statusText = text) }
	}

	fun clearSuccessExportMessage() {
		_state.update { it.copy(successExportMessage = null) }
	}

	fun analyze() {
		val rawInput = _state.value.inputPath.trim()
		if (rawInput.isEmpty()) {
			_state.update { it.copy(errorMessage = tr("dialog.inputRequired")) }
			return
		}
		val input = Path.of(rawInput)
		if (!Files.isRegularFile(input) || !input.fileName.toString().endsWith(".psd", true)) {
			_state.update { it.copy(errorMessage = tr("dialog.inputInvalid", input)) }
			return
		}

		activeWorkJob?.cancel()
		activeWorkJob = scope.launch {
			_state.update {
				it.copy(
					isAnalyzing = true,
					isIndeterminateProgress = true,
					progress = 0f,
					statusText = tr("status.analyzing"),
					errorMessage = null,
				)
			}
			try {
				val config = _state.value.copy(layerVisibility = emptyMap(), layerOverrides = emptyMap(), deletedLayerIds = emptySet(), parentOverrides = emptyMap(), rigEdits = RigEditOverlay.Empty).buildConfig()
				val preview = runInterruptible(Dispatchers.Default) {
					pipeline.buildPreview(input, config)
				}
				val inputSignature = runCatching {
					"${Files.size(input)}:${Files.getLastModifiedTime(input).toMillis()}"
				}.getOrNull()
				_state.update { current ->
					val recognized = preview.analysis.layers.count { it.semantic.tag != SemanticTag.UNKNOWN }
					val summary = tr(
						"status.analysisSummary",
						preview.analysis.source.widthPx,
						preview.analysis.source.heightPx,
						preview.analysis.layers.size,
						recognized,
					)
					val logLinesList = listOf(
						tr(
							"log.analysis",
							preview.analysis.layers.size,
							preview.analysis.anchors.character.width.toInt(),
							preview.analysis.anchors.character.height.toInt(),
						),
					) + preview.analysis.warnings.map { tr("log.warning", it) }
					current.withLogs(logLinesList, level = LogLevel.INFO, tag = "Analysis").copy(
						isIndeterminateProgress = false,
						projectId = java.util.UUID.randomUUID().toString(),
                        projectSourceName = input.fileName.toString(),
                        projectFile = null, projectDirty = true, showProjectLocationDialog = false, isAnalyzing = true,
                        layerVisibility = emptyMap(), layerOverrides = emptyMap(), deletedLayerIds = emptySet(), parentOverrides = emptyMap(), rigEdits = RigEditOverlay.Empty,
                        selectedLayerId = null, selectedDeformerId = null, isolatedLayerId = null, isolationSnapshot = null,
                        workspaceTabs = current.workspaceTabs.map { tab ->
                            if (tab.kind.canvasMode != null) tab.copy(camera = TabCamera()) else tab
                        },
                        historySnapshot = null, historyAnnotations = emptyMap(),
                        projectOpenGeneration = current.projectOpenGeneration + 1,
                        analysis = preview.analysis,
						loadedInputPath = input.toAbsolutePath().normalize().toString(),
						loadedInputFileSignature = inputSignature,
						previewModel = preview,
						statusText = summary,
						lockedParameters = emptySet(),
						parameterValues = preview.rig.puppet.parameters.associate { it.id to it.default },
					)
				}
				refreshSdkSession(preview)
                canvasEditor.resetPaintSession()
                (agentWorkspace as? io.github.psd2live.agent.ViewModelAgentWorkspace)?.importedPsd()
                _state.update { it.copy(isAnalyzing = false) }
			} catch (failure: Throwable) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
				val detail = failure.message ?: failure.javaClass.simpleName
				_state.update {
					it.withLog(tr("log.failed", detail), level = LogLevel.ERROR, tag = "Analysis").copy(
						isAnalyzing = false,
						isIndeterminateProgress = false,
						statusText = tr("status.failed", detail),
						errorMessage = detail,
					)
				}
			}
		}
	}

	fun generateRig(targetOutputPath: String? = null) {
		if (!targetOutputPath.isNullOrBlank()) {
			setOutputPath(targetOutputPath)
		}
		val rawInput = _state.value.inputPath.trim()
		if (rawInput.isEmpty()) {
			_state.update { it.copy(errorMessage = tr("dialog.inputRequired")) }
			return
		}
		var rawOutput = _state.value.outputPath.trim()
		if (rawOutput.isEmpty()) {
			try {
				val p = Path.of(rawInput)
				val parent = p.toAbsolutePath().parent
				val name = p.fileName.toString().substringBeforeLast('.')
				rawOutput = parent.resolve("$name-psd2live").toString()
				setOutputPath(rawOutput)
			} catch (_: Exception) {
				_state.update { it.copy(errorMessage = tr("dialog.outputRequired")) }
				return
			}
		}
		lastExportDirectory = rawOutput
		val input = Path.of(rawInput)
		val output = Path.of(rawOutput)
		val config = _state.value.buildConfig()
		val workspaceSource = _state.value.analysis?.source
		if (!config.exportCmo3 && !config.exportMoc3) {
			_state.update { it.copy(errorMessage = tr("dialog.exportFormatRequired")) }
			return
		}

		activeWorkJob?.cancel()
		activeWorkJob = scope.launch {
			_state.update {
				val base = it.withLog(tr("status.generating"), level = LogLevel.INFO, tag = "Export")
				val withUpscale = if (config.textureUpscale.scale > 1) {
					base.withLog(
						tr("log.upscaleExportActive", config.textureUpscale.scale),
						level = LogLevel.INFO,
						tag = "Upscale",
					)
				} else {
					base
				}
				withUpscale.copy(
					isGenerating = true,
					isIndeterminateProgress = false,
					progress = 0f,
					statusText = tr("status.generating"),
					errorMessage = null,
					successExportMessage = null,
				)
			}
			try {
				val result = runInterruptible(Dispatchers.Default) {
					val progress = ProgressListener { stage, fraction ->
							_state.update {
								val tag = if (stage.contains("高清化") || stage.contains("upscal", true) || stage.contains("高解像度")) "Upscale" else "Export"
								it.withLog("%3d%%  %s".format((fraction * 100).toInt(), stage), level = LogLevel.INFO, tag = tag).copy(
									progress = fraction.toFloat().coerceIn(0f, 1f),
									statusText = stage,
								)
							}
						}
					if (workspaceSource != null) {
						pipeline.run(workspaceSource, _state.value.projectSourceName ?: input.fileName.toString(), output, config, progress)
					} else {
						pipeline.run(input, output, config, progress)
					}
				}
				_state.update { current ->
					val outputLogs = listOf(
						tr("log.outputFiles"),
					) + result.exportedFiles.map { "• ${it.path} (${it.bytes} bytes)" }
					val warningLogs = if (result.warnings.isNotEmpty()) {
						listOf(tr("log.warnings")) + result.warnings.map { "• $it" }
					} else emptyList()
					val summary = tr("status.completed", result.exportedFiles.size, result.warnings.size)
					current
						.withLogs(outputLogs, level = LogLevel.INFO, tag = "Export")
						.let { state ->
							if (warningLogs.isNotEmpty()) {
								state.withLogs(warningLogs, level = LogLevel.WARNING, tag = "Export")
							} else state
						}
						.copy(
							isGenerating = false,
							progress = 1f,
							analysis = result.previewModel.analysis,
							loadedInputPath = current.loadedInputPath ?: input.toAbsolutePath().normalize().toString(),
							loadedInputFileSignature = current.loadedInputFileSignature ?: runCatching {
								"${Files.size(input)}:${Files.getLastModifiedTime(input).toMillis()}"
							}.getOrNull(),
							previewModel = result.previewModel,
							statusText = summary,
							successExportMessage = tr("dialog.exportSuccess", result.exportedFiles.size, output),
						)
				}
				refreshSdkSession(result.previewModel)
			} catch (failure: Throwable) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
				val detail = failure.message ?: failure.javaClass.simpleName
				_state.update {
					it.withLog(tr("log.failed", detail), level = LogLevel.ERROR, tag = "Export").copy(
						isGenerating = false,
						statusText = tr("status.failed", detail),
						errorMessage = detail,
					)
				}
			}
		}
	}

	fun openExportDialog() {
		_state.update { it.copy(showExportDialog = true) }
	}

	fun closeExportDialog() {
		_state.update {
			it.copy(
				showExportDialog = false,
				focusCanvasRequest = it.focusCanvasRequest + 1,
			)
		}
	}

	fun openExportPsdDialog() {
		if (_state.value.analysis == null) return
		_state.update { it.copy(showExportPsdDialog = true) }
	}

	fun closeExportPsdDialog() {
		_state.update { it.copy(showExportPsdDialog = false) }
	}

	fun exportPsd(targetPath: Path, scale: Int = 1, includeGeneratedLayers: Boolean = true) {
		val currentState = _state.value
		val analysis = currentState.analysis ?: run {
			_state.update { it.copy(errorMessage = tr("error.noPsdLoaded")) }
			return
		}
		activeWorkJob?.cancel()
		activeWorkJob = scope.launch {
			_state.update {
				it.copy(
					isExportingPsd = true,
					showExportPsdDialog = false,
					progress = 0.05f,
					statusText = tr("exportPsd.starting", targetPath.fileName.toString()),
				)
			}
			addLog(
				message = tr("log.exportPsdStart", targetPath.toAbsolutePath().normalize().toString(), scale),
				level = LogLevel.INFO,
				tag = "Export",
			)
			try {
				val effectiveLayers = if (includeGeneratedLayers) {
					analysis.layers.map { it.source }
				} else {
					analysis.source.layers
				}
				val upscaledTextures = if (scale > 1) {
					_state.update { it.copy(statusText = tr("upscale.startingInference"), progress = 0.15f) }
					io.github.psd2live.core.TextureUpscale.prepare(
						layers = analysis.layers,
						config = currentState.textureUpscale.copy(scale = scale),
						progress = { stage, frac ->
							_state.update { it.copy(statusText = stage, progress = (0.15 + frac * 0.70).toFloat().coerceIn(0.15f, 0.85f)) }
						}
					)
				} else emptyMap()

				_state.update { it.copy(statusText = tr("exportPsd.writingBytes"), progress = 0.90f) }
				val bytes = withContext(Dispatchers.Default) {
					org.umamo.format.psd.PsdWriter.write(
						width = analysis.source.widthPx,
						height = analysis.source.heightPx,
						layers = effectiveLayers,
						groups = analysis.source.groups,
						scale = scale,
						upscaledTextures = upscaledTextures,
					)
				}
				withContext(Dispatchers.IO) {
					val parent = targetPath.toAbsolutePath().parent
					if (parent != null) Files.createDirectories(parent)
					Files.write(targetPath, bytes)
				}
				val fileSize = Files.size(targetPath)
				val successMsg = tr("log.exportPsdSuccess", targetPath.fileName.toString(), effectiveLayers.size, fileSize)
				addLog(
					message = successMsg,
					level = LogLevel.SUCCESS,
					tag = "Export",
				)
				_state.update {
					it.copy(
						isExportingPsd = false,
						progress = 1f,
						statusText = tr("exportPsd.completed", targetPath.fileName.toString()),
					)
				}
			} catch (failure: Throwable) {
				if (failure is kotlinx.coroutines.CancellationException) throw failure
				val detail = failure.message ?: failure.javaClass.simpleName
				addLog(
					message = tr("log.failed", detail),
					level = LogLevel.ERROR,
					tag = "Export",
				)
				_state.update {
					it.copy(
						isExportingPsd = false,
						statusText = tr("status.failed", detail),
						errorMessage = detail,
					)
				}
			}
		}
	}

	/** Fast incremental update or CPU rebuild used by the authenticated Agent transaction boundary. */
	internal suspend fun buildAgentWorkspacePreview(source: SourceArt, config: PipelineConfig): RigPreviewModel =
		runInterruptible(Dispatchers.Default) {
			val current = _state.value.previewModel
			if (current != null && pipeline.canFastUpdateRig(current, source, config)) {
				pipeline.updateRigEdits(current, config)
			} else if (current != null && (current.analysis.source === source || current.analysis.source == source) &&
				current.config.copy(parentOverrides = config.parentOverrides, rigEdits = config.rigEdits, drawOrderOverrides = config.drawOrderOverrides) == config
			) {
				pipeline.rebuildPreview(current, config)
			} else {
				pipeline.buildPreview(source, config)
			}
		}

    internal suspend fun sampleAgentMotion(bundle: io.github.psd2live.core.CubismRuntimeBundle,
                                          parameters: List<ParameterId>, frames: Int, fps: Int): List<Map<ParameterId, Float>> =
        kotlinx.coroutines.runInterruptible(Dispatchers.IO) {
            sdkSession.sampleMotion(bundle, parameters, "AgentObservation", frames, fps).get(45, java.util.concurrent.TimeUnit.SECONDS)
        }

	/** Publish one already-built authoritative workspace snapshot atomically to Compose and preview. */
	internal fun applyAgentWorkspacePreview(
		preview: RigPreviewModel,
		expectedSource: SourceArt,
		expectedLayerVisibility: Map<String, Boolean>,
		expectedDeletedLayerIds: Set<String>,
		expectedLayerOverrides: Map<String, LayerClassificationOverride>,
		expectedParentOverrides: Map<String, String?>,
		expectedRigEdits: RigEditOverlay,
        expectedSettings: kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.JsonObject(emptyMap()),
		layerVisibility: Map<String, Boolean>,
		deletedLayerIds: Set<String>,
		layerOverrides: Map<String, LayerClassificationOverride>,
		parentOverrides: Map<String, String?>,
		rigEdits: RigEditOverlay,
		status: String,
        settings: kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.JsonObject(emptyMap()),
	): Boolean {
		previewRebuildJob?.cancel()
		canvasEditor.resetPaintSession()
		var applied = false
		_state.update { current ->
			applied = false
			if (
				current.analysis?.source !== expectedSource ||
				current.layerVisibility != expectedLayerVisibility ||
				current.deletedLayerIds != expectedDeletedLayerIds ||
				current.layerOverrides != expectedLayerOverrides ||
				current.parentOverrides != expectedParentOverrides ||
				current.rigEdits != expectedRigEdits ||
                (expectedSettings.isNotEmpty() && io.github.psd2live.project.WorkspaceStateCodec.settings(current) != expectedSettings)
			) return@update current
			applied = true
			io.github.psd2live.project.WorkspaceStateCodec.decode(settings, current).copy(
				analysis = preview.analysis,
				previewModel = preview,
				previewModelDirty = false,
				layerVisibility = layerVisibility,
				deletedLayerIds = deletedLayerIds,
				layerOverrides = layerOverrides,
				parentOverrides = parentOverrides,
				rigEdits = rigEdits,
				selectedLayerId = current.selectedLayerId?.takeIf { selected ->
					preview.analysis.layers.any { it.source.id.raw == selected } && selected !in deletedLayerIds
				},
				isolationSnapshot = null,
				isolatedLayerId = null,
				lockedParameters = current.lockedParameters.intersect(preview.rig.puppet.parameters.mapTo(mutableSetOf()) { it.id }),
				parameterValues = preview.rig.puppet.parameters.associate { parameter ->
					parameter.id to (current.parameterValues[parameter.id] ?: parameter.default).coerceIn(parameter.min, parameter.max)
				},
				statusText = status,
				logLines = current.logLines + status,
				errorMessage = null,
			)
		}
		return applied
	}

	internal fun loadAgentWorkspacePreview(preview: RigPreviewModel) {
		canvasEditor.resetPaintSession()
		_state.update { it.copy(previewModel = preview, analysis = preview.analysis, previewModelDirty = false) }
		refreshSdkSession(preview)
	}

	private fun scheduleRuntimeBundleUpdate() {
		val previous = _state.value.previewModel ?: return
		if (_state.value.isAnalyzing || _state.value.isGenerating) return

		previewRebuildJob?.cancel()
		previewRebuildJob = scope.launch {
			delay(200)
			try {
				val config = _state.value.buildConfig()
				val updated = runInterruptible(Dispatchers.Default) {
					pipeline.updateRuntimeBundle(previous, config)
				}
				_state.update {
					it.copy(previewModel = updated)
				}
				refreshSdkSession(updated)
			} catch (failure: Throwable) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
				val detail = failure.message ?: failure.javaClass.simpleName
				_state.update {
					it.withLog(tr("log.previewUpdateFailed", detail), level = LogLevel.ERROR, tag = "Preview").copy(
						statusText = tr("status.previewUpdateFailed", detail),
					)
				}
			}
		}
	}

	private fun schedulePreviewRebuild() {
		val previous = _state.value.previewModel ?: return
		if (_state.value.isAnalyzing || _state.value.isGenerating) return

		previewRebuildJob?.cancel()
		previewRebuildJob = scope.launch {
			delay(60)
			val isUpscalingJob = _state.value.textureUpscale.scale > 1 && _state.value.textureUpscale != previous.config.textureUpscale
			_state.update { current ->
				val base = if (isUpscalingJob) {
					current.withLog(
						message = tr("log.upscaleStarting", current.textureUpscale.scale),
						level = LogLevel.INFO,
						tag = "Upscale",
					).copy(
						logPanelExpanded = true,
					)
				} else {
					current
				}
				base.copy(
					statusText = if (isUpscalingJob) tr("upscale.startingInference") else tr("status.applyingLayerChanges"),
					isUpscaling = isUpscalingJob,
					progress = 0f,
				)
			}
			try {
				val config = _state.value.buildConfig()
				var lastReportedStage: String? = null
				val progress = ProgressListener { stage, frac ->
					_state.update { current ->
						val shouldLog = stage.isNotBlank() && stage != lastReportedStage
						if (shouldLog) {
							lastReportedStage = stage
						}
						val base = if (shouldLog) {
							val tag = if (current.isUpscaling) "Upscale" else "Preview"
							current.withLog(
								message = "%3d%%  %s".format((frac * 100).toInt(), stage),
								level = LogLevel.INFO,
								tag = tag,
							)
						} else {
							current
						}
						base.copy(
							statusText = stage,
							progress = frac.toFloat().coerceIn(0f, 1f),
						)
					}
				}
				val rebuilt = runInterruptible(Dispatchers.Default) {
					pipeline.rebuildPreview(previous, config, progress)
				}
				val packedAtlasSize = rebuilt.atlas.pages.firstOrNull()?.image?.width ?: config.atlasSize
				_state.update { current ->
					val validParamIds = rebuilt.rig.puppet.parameters.mapTo(mutableSetOf()) { it.id }
					val completionMsg = if (isUpscalingJob) {
						tr("log.upscaleCompleted", rebuilt.analysis.layers.size, packedAtlasSize, packedAtlasSize)
					} else {
						tr("log.previewUpdated")
					}
					val base = current.withLog(
						message = completionMsg,
						level = LogLevel.SUCCESS,
						tag = if (isUpscalingJob) "Upscale" else "Preview",
					)
					base.copy(
						previewModel = rebuilt,
						analysis = rebuilt.analysis,
						atlasSize = maxOf(current.atlasSize, packedAtlasSize),
						isUpscaling = false,
						progress = 1f,
						lockedParameters = current.lockedParameters.intersect(validParamIds),
						parameterValues = rebuilt.rig.puppet.parameters.associate { param ->
							param.id to (current.parameterValues[param.id] ?: param.default).coerceIn(param.min, param.max)
						},
						statusText = tr("status.layerChangesApplied"),
					)
				}
				refreshSdkSession(rebuilt)
			} catch (failure: Throwable) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
				val detail = failure.message ?: failure.javaClass.simpleName
				_state.update {
					it.withLog(
						message = if (isUpscalingJob) tr("log.upscaleFailed", detail) else tr("log.previewUpdateFailed", detail),
						level = LogLevel.ERROR,
						tag = if (isUpscalingJob) "Upscale" else "Preview",
					).copy(
						isUpscaling = false,
						statusText = tr("status.previewUpdateFailed", detail),
						errorMessage = detail,
					)
				}
			}
		}
	}

	private var activeSoftwareMotionName: String? = null
	private var activeSoftwareMotionElapsed: Float = 0f
	private var activeSoftwareMotionDuration: Float = 2.0f
	@Volatile private var latestLiveParameters: Map<ParameterId, Float> = emptyMap()

	val currentLiveParameters: Map<ParameterId, Float> get() = latestLiveParameters
	val activeMotionName: String? get() = activeSoftwareMotionName

	fun triggerMotion(group: String) {
		_state.update { current ->
			var nextState = current.copy(animationEnabled = true)
			if (current.activeTabKind != WorkspaceTabKind.PREVIEW) {
				val previewTab = current.workspaceTabs.firstOrNull { it.kind == WorkspaceTabKind.PREVIEW }
				if (previewTab != null) {
					nextState = nextState.copy(activeWorkspaceTabId = previewTab.id)
				}
			}
			nextState
		}
		ensureSdkSessionLoaded()
		sdkSession.startMotion(group, index = 0, priority = 3)
		when (group.lowercase()) {
			"nod" -> {
				activeSoftwareMotionName = "nod"
				activeSoftwareMotionElapsed = 0f
				activeSoftwareMotionDuration = 2.0f
			}
			"shake" -> {
				activeSoftwareMotionName = "shake"
				activeSoftwareMotionElapsed = 0f
				activeSoftwareMotionDuration = 2.0f
			}
			"blink" -> {
				activeSoftwareMotionName = "blink"
				activeSoftwareMotionElapsed = 0f
				activeSoftwareMotionDuration = 1.2f
			}
			"idle" -> {
				elapsed = 0.0
			}
		}
	}

	private fun startMotionLoop() {
		motionJob = scope.launch {
			while (isActive) {
				val now = System.nanoTime()
				val dt = ((now - lastTick) / 1_000_000_000.0).coerceIn(0.001, 0.08).toFloat()
				lastTick = now

				val current = _state.value
				val inPreview = current.activeTabKind == WorkspaceTabKind.PREVIEW
				val isMeshOnly = current.meshOnly
				val anim = inPreview && current.animationEnabled && !isMeshOnly
				val tracking = inPreview && current.mouseTrackingEnabled && !isMeshOnly
				if (anim) elapsed += dt

				// 1. Advance one-shot software motion (Nod / Shake / Blink)
				var nodAngleY = 0f
				var nodBodyY = 0f
				var nodEyeBlink = 1f

				var shakeAngleX = 0f
				var shakeBodyX = 0f
				var shakeAngleZ = 0f

				val activeMotion = activeSoftwareMotionName
				if (activeMotion != null && anim) {
					activeSoftwareMotionElapsed += dt
					val t = activeSoftwareMotionElapsed
					when (activeMotion) {
						"nod" -> {
							if (t <= 2.0f) {
								nodAngleY = when {
									t < 0.55f -> -18f * (t / 0.55f)
									t < 1.25f -> -18f + 24f * ((t - 0.55f) / 0.70f)
									else -> 6f * (1f - (t - 1.25f) / 0.75f)
								}
								nodBodyY = when {
									t < 0.55f -> -4f * (t / 0.55f)
									t < 1.25f -> -4f + 5.5f * ((t - 0.55f) / 0.70f)
									else -> 1.5f * (1f - (t - 1.25f) / 0.75f)
								}
								nodEyeBlink = when {
									t < 0.55f -> 1f - 0.25f * (t / 0.55f)
									t < 1.25f -> 0.75f + 0.25f * ((t - 0.55f) / 0.70f)
									else -> 1f
								}
							} else {
								activeSoftwareMotionName = null
							}
						}
						"shake" -> {
							if (t <= 2.0f) {
								shakeAngleX = when {
									t < 0.4f -> -20f * (t / 0.4f)
									t < 0.9f -> -20f + 40f * ((t - 0.4f) / 0.5f)
									t < 1.4f -> 20f - 28f * ((t - 0.9f) / 0.5f)
									else -> -8f * (1f - (t - 1.4f) / 0.6f)
								}
								shakeBodyX = when {
									t < 0.4f -> -3f * (t / 0.4f)
									t < 0.9f -> -3f + 6f * ((t - 0.4f) / 0.5f)
									t < 1.4f -> 3f - 4.2f * ((t - 0.9f) / 0.5f)
									else -> -1.2f * (1f - (t - 1.4f) / 0.6f)
								}
								shakeAngleZ = when {
									t < 0.4f -> 2f * (t / 0.4f)
									t < 0.9f -> 2f - 4f * ((t - 0.4f) / 0.5f)
									t < 1.4f -> -2f + 3f * ((t - 0.9f) / 0.5f)
									else -> 1f * (1f - (t - 1.4f) / 0.6f)
								}
							} else {
								activeSoftwareMotionName = null
							}
						}
						"blink" -> {
							if (t > 1.2f) {
								activeSoftwareMotionName = null
							}
						}
					}
				}

				// 2. Eye Blink (Periodic + Triggered)
				val hasBlink = anim && current.motionBlink
				val periodicBlink = if (hasBlink) blinkAt(elapsed % 4.6) else 1f
				val blink = minOf(periodicBlink, nodEyeBlink)

				// 3. Eye Jelly Dynamics
				val hasEyeJelly = anim && current.generatePhysics && current.physicsEyeJelly
				eyeJellyDynamics.advance(blink, dt, hasEyeJelly)

				// 4. Idle Motion (Head & Body Sway, Mouse Tracking)
				val hasIdle = anim && current.motionIdle
				val idleX = if (hasIdle) (sin(elapsed * 0.47) * 0.12).toFloat() else 0f
				val idleY = if (hasIdle) (sin(elapsed * 0.31 + 1.1) * 0.08).toFloat() else 0f
				val targetX = if (pointerActive && tracking) pointerX else idleX
				val targetY = if (pointerActive && tracking) pointerY else idleY
				val response = (dt * 7.5f).coerceAtMost(1f)
				previousFollowX = followX
				followX += (targetX - followX) * response
				followY += (targetY - followY) * response

				if (!pointerActive && kotlin.math.abs(followX - targetX) < 0.001f) followX = targetX
				if (!pointerActive && kotlin.math.abs(followY - targetY) < 0.001f) followY = targetY

				// 5. Hair Physics Simulation
				val hasFrontHair = anim && current.generatePhysics && current.physicsFrontHair
				val hasBackHair = anim && current.generatePhysics && current.physicsBackHair
				if (anim && (hasFrontHair || hasBackHair)) {
					val headVelocity = ((followX - previousFollowX) / dt).coerceIn(-5f, 5f)
					val hairTarget = (-followX * 0.42f - headVelocity * 0.085f).coerceIn(-1f, 1f)
					if (hasFrontHair) {
						val frontEdit = current.rigEdits.physicsEdits.find { it.id == "PhysicsHairFront" || it.outputParameter == "ParamHairFront" }
						val stiffness = if (frontEdit != null) 22f * (frontEdit.mobility / 0.77f).coerceIn(0.2f, 3f) else 22f
						val damp = if (frontEdit != null) 7.2f * (frontEdit.delay / 1.45f).coerceIn(0.2f, 3f) else 7.2f
						frontHairVelocity += ((hairTarget - frontHair) * stiffness - frontHairVelocity * damp) * dt
						frontHair += frontHairVelocity * dt
					} else {
						frontHair = 0f
						frontHairVelocity = 0f
					}
					if (hasBackHair) {
						val backEdit = current.rigEdits.physicsEdits.find { it.id == "PhysicsHairBack" || it.outputParameter == "ParamHairBack" }
						val stiffness = if (backEdit != null) 10f * (backEdit.mobility / 0.95f).coerceIn(0.2f, 3f) else 10f
						val damp = if (backEdit != null) 4.2f * (backEdit.delay / 0.8f).coerceIn(0.2f, 3f) else 4.2f
						backHairVelocity += ((hairTarget - backHair) * stiffness - backHairVelocity * damp) * dt
						backHair += backHairVelocity * dt
					} else {
						backHair = 0f
						backHairVelocity = 0f
					}
				} else {
					frontHair = 0f
					frontHairVelocity = 0f
					backHair = 0f
					backHairVelocity = 0f
				}

				val model = current.previewModel
				if (model != null && inPreview && (anim || tracking)) {
					val liveParams = if (isMeshOnly) {
						model.rig.puppet.parameters.associate { it.id to it.default }
					} else computeLiveParameters(
						model = model,
						current = current,
						blink = blink,
						nodAngleY = nodAngleY,
						nodBodyY = nodBodyY,
						shakeAngleX = shakeAngleX,
						shakeBodyX = shakeBodyX,
						shakeAngleZ = shakeAngleZ,
					)
					latestLiveParameters = liveParams
					if (current.sdkStatus != "ready") {
						_state.update { latest ->
							if (latest.activeTabKind != WorkspaceTabKind.PREVIEW) latest
							else {
								val mergedValues = parameterValuesAfterSoftwareFrame(latest, liveParams, pointerActive)
								if (mergedValues === latest.previewParameterValues) latest
								else latest.copy(previewParameterValues = mergedValues)
							}
						}
					}
				}

				delay(33)
			}
		}
	}

	fun computeLiveParameters(
		model: RigPreviewModel,
		current: PSD2LiveState = _state.value,
		blink: Float = blinkAt(elapsed % 4.6),
		nodAngleY: Float = 0f,
		nodBodyY: Float = 0f,
		shakeAngleX: Float = 0f,
		shakeBodyX: Float = 0f,
		shakeAngleZ: Float = 0f,
	): Map<ParameterId, Float> {
		if (current.meshOnly) {
			return model.rig.puppet.parameters.associate { it.id to it.default }
		}

		val hasIdle = current.animationEnabled && current.motionIdle
		val hasFrontHair = current.animationEnabled && current.generatePhysics && current.physicsFrontHair
		val hasBackHair = current.animationEnabled && current.generatePhysics && current.physicsBackHair
		val hasEyeJelly = current.animationEnabled && current.generatePhysics && current.physicsEyeJelly

		val mouthPhase = elapsed % 5.8
		val mouthOpen = if (mouthPhase in 1.25..2.45 && current.animationEnabled && hasIdle) {
			sin((mouthPhase - 1.25) / 1.20 * PI).toFloat().coerceAtLeast(0f)
		} else 0f

		val idleAngleZ = if (hasIdle) (sin(elapsed * PI / 1.5) * 2.0).toFloat() else 0f
		val idleBodyX = if (hasIdle) sin(elapsed * 0.72).toFloat() * 1.2f else 0f
		val idleBodyZ = if (hasIdle) sin(elapsed * 0.92).toFloat() * 2.2f else 0f
		val breath = if (hasIdle) ((sin(elapsed * 1.45) + 1.0) * 0.5).toFloat() else 0f

		val isTracking = pointerActive && current.mouseTrackingEnabled && !current.meshOnly
		val headAngleX = if (hasIdle || isTracking) followX * 38f else 0f
		val headAngleY = if (hasIdle || isTracking) -followY * 24f else 0f
		val bodyAngleX = if (hasIdle || isTracking) followX * 4f else 0f
		val bodyAngleY = if (hasIdle || isTracking) -followY * 2f else 0f
		val eyeBallX = if (hasIdle || isTracking) followX.coerceIn(-1f, 1f) else 0f
		val eyeBallY = if (hasIdle || isTracking) (-followY).coerceIn(-1f, 1f) else 0f

		return mapOf(
			StandardParameters.ANGLE_X to (headAngleX + shakeAngleX),
			StandardParameters.ANGLE_Y to (headAngleY + nodAngleY),
			StandardParameters.ANGLE_Z to (idleAngleZ + shakeAngleZ),
			StandardParameters.BODY_X to (bodyAngleX + idleBodyX + shakeBodyX),
			StandardParameters.BODY_Y to (bodyAngleY + nodBodyY),
			StandardParameters.BODY_Z to idleBodyZ,
			StandardParameters.EYE_BALL_X to eyeBallX,
			StandardParameters.EYE_BALL_Y to eyeBallY,
			StandardParameters.EYE_BALL_FORM to if (hasEyeJelly) eyeJellyDynamics.value else 0f,
			StandardParameters.EYE_L_OPEN to blink,
			StandardParameters.EYE_R_OPEN to blink,
			StandardParameters.MOUTH_FORM to if (current.animationEnabled && hasIdle) sin(elapsed * 0.41).toFloat() * 0.18f else 0f,
			StandardParameters.MOUTH_OPEN to mouthOpen,
			StandardParameters.BREATH to breath,
			StandardParameters.HAIR_FRONT to if (hasFrontHair) frontHair.coerceIn(-1f, 1f) else 0f,
			StandardParameters.HAIR_BACK to if (hasBackHair) backHair.coerceIn(-1f, 1f) else 0f,
		)
	}

	private fun blinkAt(phase: Double): Float = if (phase in 4.18..4.46) {
		(1.0 - sin((phase - 4.18) / 0.28 * PI)).toFloat().coerceIn(0f, 1f)
	} else 1f

	fun requestSdkFrame(
		width: Int,
		height: Int,
		scale: Float,
		offsetX: Float,
		offsetY: Float,
		deltaTime: Float = 1f / 60f,
		frameTimeNanos: Long = System.nanoTime(),
	) {
		val current = _state.value
		val model = current.previewModel ?: return
		val inPreview = current.activeTabKind == WorkspaceTabKind.PREVIEW
		if (inPreview && sdkSessionNeedsReload) {
			ensureSdkSessionLoaded()
		}
		val isAnim = inPreview && current.animationEnabled && !current.meshOnly
		val tracking = inPreview && current.mouseTrackingEnabled && !current.meshOnly
		val liveParams = latestLiveParameters.ifEmpty {
			computeLiveParameters(model, current)
		}
		val previewValues = parameterValuesForPreview(current, liveParams)
		sdkSession.render(
			CubismSdkPreviewSession.RenderRequest(
				width = width,
				height = height,
				scale = scale,
				offsetX = offsetX,
				offsetY = offsetY,
				deltaTime = deltaTime,
				// Cubism receives the pointer target and performs its own critically damped tracking.
				// X stays raw for native hair inertia; Y uses UI smoothing because it is applied
				// separately to keep mouse tracking from owning ParamAngleZ.
				pointerX = if (pointerActive && tracking) pointerX else 0f,
				pointerY = if (pointerActive && tracking) -followY else 0f,
				animationEnabled = isAnim,
				parameterOverrides = previewValues,
				frameTimeNanos = frameTimeNanos,
			),
		)
	}

	private val isClosed = java.util.concurrent.atomic.AtomicBoolean(false)

	override fun close() {
		if (!isClosed.compareAndSet(false, true)) return
		motionJob?.cancel()
		previewRebuildJob?.cancel()
		activeWorkJob?.cancel()
		scope.cancel()
		sdkSession.close()
	}

	internal fun setStateForTest(state: PSD2LiveState) {
		_state.value = state
	}

	private companion object {
		const val SDK_PARAMETER_PUBLISH_INTERVAL_NANOS = 33_333_333L
		const val PREF_LAST_EXPORT_DIR = "last_export_dir"
		/** The token every slider shares; the call sites predate the per-field tokens and stay untouched. */
		const val SLIDER_SESSION = "slider"
	}
}

internal fun mergeUnlockedParameterValues(
	current: Map<ParameterId, Float>,
	incoming: Map<ParameterId, Float>,
	locked: Set<ParameterId>,
): Map<ParameterId, Float> {
	if (incoming.isEmpty()) return current
	val merged = current.toMutableMap()
	var changed = false
	for ((id, value) in incoming) {
		if (id !in locked && merged[id] != value) {
			merged[id] = value
			changed = true
		}
	}
	return if (changed) merged else current
}

internal fun parameterValuesForPreview(
	state: PSD2LiveState,
	liveParams: Map<ParameterId, Float> = emptyMap(),
): Map<ParameterId, Float> {
	if (!state.animationEnabled || state.activeTabKind != WorkspaceTabKind.PREVIEW) {
		return state.parameterValues
	}
	if (state.meshOnly) {
		val defaults = state.previewModel?.rig?.puppet?.parameters?.associate { it.id to it.default } ?: emptyMap()
		return defaults + state.parameterValues.filterKeys { it in state.lockedParameters }
	}

	val standardIds = StandardParameters.all.map { it.id }.toSet()
	val overrides = state.parameterValues.filterKeys { it in state.lockedParameters || it !in standardIds }.toMutableMap()

	// 1. Idle animation disabled:
	// Silences Native SDK's hardcoded CubismBreath and Idle motion.
	// Overrides AngleX/Y/Z, BodyAngleX/Y/Z, Breath, and Mouth to controlled values (neutral 0 unless moving mouse/motion).
	if (!state.motionIdle) {
		val idleSuppressedIds = listOf(
			StandardParameters.ANGLE_X,
			StandardParameters.ANGLE_Y,
			StandardParameters.ANGLE_Z,
			StandardParameters.BODY_X,
			StandardParameters.BODY_Y,
			StandardParameters.BODY_Z,
			StandardParameters.BREATH,
			StandardParameters.MOUTH_OPEN,
			StandardParameters.MOUTH_FORM,
		)
		for (id in idleSuppressedIds) {
			if (id !in state.lockedParameters) {
				overrides[id] = liveParams[id] ?: 0f
			}
		}
	}

	// 2. Blink motion disabled:
	// Silences Native SDK eye blinking; keeps eyes fully open (1.0f).
	if (!state.motionBlink) {
		if (StandardParameters.EYE_L_OPEN !in state.lockedParameters) {
			overrides[StandardParameters.EYE_L_OPEN] = liveParams[StandardParameters.EYE_L_OPEN] ?: 1.0f
		}
		if (StandardParameters.EYE_R_OPEN !in state.lockedParameters) {
			overrides[StandardParameters.EYE_R_OPEN] = liveParams[StandardParameters.EYE_R_OPEN] ?: 1.0f
		}
	}

	// 3. Physics disabled or specific chains disabled:
	val physicsActive = state.generatePhysics && !state.meshOnly
	if (!physicsActive || !state.physicsFrontHair) {
		if (StandardParameters.HAIR_FRONT !in state.lockedParameters) {
			overrides[StandardParameters.HAIR_FRONT] = 0f
		}
	}
	if (!physicsActive || !state.physicsBackHair) {
		if (StandardParameters.HAIR_BACK !in state.lockedParameters) {
			overrides[StandardParameters.HAIR_BACK] = 0f
		}
	}
	if (!physicsActive || !state.physicsEyeJelly) {
		if (StandardParameters.EYE_BALL_FORM !in state.lockedParameters) {
			overrides[StandardParameters.EYE_BALL_FORM] = 0f
		}
	}

	return overrides
}

internal fun parameterValuesAfterPreviewFrame(
	state: PSD2LiveState,
	incoming: Map<ParameterId, Float>,
): Map<ParameterId, Float> {
	val base = state.previewParameterValues.ifEmpty { state.parameterValues }
	return if (state.animationEnabled && !state.meshOnly) {
		mergeUnlockedParameterValues(base, incoming, state.lockedParameters)
	} else if (state.meshOnly) {
		val defaults = state.previewModel?.rig?.puppet?.parameters?.associate { it.id to it.default } ?: emptyMap()
		mergeUnlockedParameterValues(base, defaults, state.lockedParameters)
	} else {
		base
	}
}

/**
 * Parameters the pointer drives. A paused preview still follows the mouse, and the live map holds
 * nothing but those angles then -- merging the whole map would overwrite the pose the user is
 * inspecting (breath, mouth, eyes) with the neutral values a paused motion reports.
 */
private val POINTER_POSE_PARAMETERS = setOf(
	StandardParameters.ANGLE_X,
	StandardParameters.ANGLE_Y,
	StandardParameters.BODY_X,
	StandardParameters.BODY_Y,
	StandardParameters.EYE_BALL_X,
	StandardParameters.EYE_BALL_Y,
)

internal fun parameterValuesAfterSoftwareFrame(
	state: PSD2LiveState,
	incoming: Map<ParameterId, Float>,
	pointerActive: Boolean,
): Map<ParameterId, Float> {
	val base = state.previewParameterValues.ifEmpty { state.parameterValues }
	return when {
		state.meshOnly -> {
			val defaults = state.previewModel?.rig?.puppet?.parameters?.associate { it.id to it.default } ?: emptyMap()
			mergeUnlockedParameterValues(base, defaults, state.lockedParameters)
		}
		state.sdkStatus != "ready" && state.animationEnabled ->
			mergeUnlockedParameterValues(base, incoming, state.lockedParameters)
		// A paused preview still follows the pointer, but with the pointer gone the live map reads
		// neutral: publishing the edit pose itself is what keeps a parameter the user is inspecting
		// from being flattened to zero, and keeps the canvas off a stale animated map.
		state.sdkStatus != "ready" && !state.animationEnabled && !pointerActive -> state.parameterValues
		state.sdkStatus != "ready" && !state.animationEnabled -> {
			// Merge onto the current edit values, never onto the previous preview pose, or a
			// parameter edited while paused would be masked by the map published a tick earlier.
			val tracked = mergeUnlockedParameterValues(
				state.parameterValues,
				incoming.filterKeys { it in POINTER_POSE_PARAMETERS },
				state.lockedParameters,
			)
			// A parked pointer merges to the pose already published; returning the old map keeps a
			// paused preview from copying an identical one on every tick.
			if (tracked == base) base else tracked
		}
		else -> base
	}
}

internal fun previewFrameMatchesState(
	state: PSD2LiveState,
	frameAnimationEnabled: Boolean,
): Boolean = (state.activeTabKind == WorkspaceTabKind.PREVIEW) &&
	(frameAnimationEnabled == (state.animationEnabled && !state.meshOnly))
