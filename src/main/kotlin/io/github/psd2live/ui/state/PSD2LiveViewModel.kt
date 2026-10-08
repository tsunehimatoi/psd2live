package io.github.psd2live.ui.state

import io.github.psd2live.project.HistoryAnnotation

import io.github.psd2live.project.ParameterSnapshot

import io.github.psd2live.project.MutationAuthor
import io.github.psd2live.project.WorkspaceSourceArt
import io.github.psd2live.project.WorkspaceSourceLayer
import io.github.psd2live.project.WorkspaceMutationResult
import io.github.psd2live.project.WorkspaceProjectSnapshot

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.key
import io.github.psd2live.core.MeshSettings
import io.github.psd2live.core.MotionClip
import io.github.psd2live.core.MotionClips
import io.github.psd2live.core.MotionHandle
import io.github.psd2live.core.MotionKey
import io.github.psd2live.core.MotionPresetSettings
import io.github.psd2live.core.MotionPresets
import io.github.psd2live.core.MeshComponentSplit
import io.github.psd2live.core.PackedAtlas

import io.github.psd2live.core.PSD2LivePipeline
import io.github.psd2live.core.RigStructureEdits
import io.github.psd2live.core.CubismSdkFrame
import io.github.psd2live.core.CubismSdkPreviewSession
import io.github.psd2live.core.HierarchyImportTarget
import io.github.psd2live.core.LayerClassificationOverride
import io.github.psd2live.core.layerSelectionRange
import io.github.psd2live.core.LayerImport
import io.github.psd2live.core.LayerType
import io.github.psd2live.core.PipelineAnalysis
import io.github.psd2live.core.PipelineConfig
import io.github.psd2live.core.ProgressListener
import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.core.RigEditOverlay
import io.github.psd2live.core.RigPhysicsEdit
import io.github.psd2live.core.PhysicsAuthoring
import io.github.psd2live.core.PhysicsCatalog
import io.github.psd2live.core.PhysicsGenerator
import io.github.psd2live.core.PhysicsGroup
import io.github.psd2live.core.RigAuthoringJournal
import io.github.psd2live.core.RigGeometryTools
import io.github.psd2live.core.RigSwingEdit
import io.github.psd2live.core.SwingAuthoring
import io.github.psd2live.core.SwingGenerator
import io.github.psd2live.core.SwingKind
import io.github.psd2live.core.SwingPreset
import io.github.psd2live.core.SwingPresets
import org.umamo.runtime.model.Deformer
import io.github.psd2live.core.SemanticTag
import io.github.psd2live.core.Side
import io.github.psd2live.core.StandardParameters
import io.github.psd2live.application.WorkspaceBackend
import io.github.psd2live.application.WorkspaceSwingPort
import io.github.psd2live.application.WorkspaceSimulationPort
import io.github.psd2live.project.WorkspaceHistorySnapshot
import io.github.psd2live.i18n.AppLanguage
import io.github.psd2live.i18n.I18n
import io.github.psd2live.i18n.tr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.long
import kotlinx.serialization.json.float
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.putJsonArray
import kotlin.math.abs
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import org.umamo.edit.freshParameterGroupId
import io.github.psd2live.ui.CanvasEditor
import io.github.psd2live.ui.views.sidebars
import io.github.psd2live.ui.keyformAxesFor
import io.github.psd2live.ui.EditHierarchyMode
import org.umamo.format.art.SourceArt
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.prefs.Preferences
import kotlin.math.PI
import kotlin.math.roundToInt
import io.github.psd2live.ui.theme.CustomTheme
import io.github.psd2live.ui.theme.ThemeCatalog
import io.github.psd2live.ui.theme.ThemeCodec
import kotlin.math.sin

class PSD2LiveViewModel : AutoCloseable {
    /** Transient hover input; changing it invalidates the canvas without writing document state. */
    private var parameterSnapshotHover by mutableStateOf<ParameterSnapshotPreview?>(null)

    internal data class MeshSplitOffer(
        val layerId: String,
        val layerName: String,
        val plan: MeshComponentSplit.Plan,
        val preview: RigPreviewModel,
        val expected: io.github.psd2live.project.WorkspaceProjectSnapshot? = null,
    )

    internal data class LayerSplitDecision(
        val offer: MeshSplitOffer,
        val names: List<String>,
        val sides: List<Side>,
    )

    /**
     * The start screen: the model presets with quick choices, and the layers that can be split by mesh.
     * [splits] is null while the scan runs. Without [presets] it only offers the splits, as after a layer
     * import. [initial] is what the preset controls open on.
     */
    internal data class StartScreenOffer(
        val preview: RigPreviewModel,
        val presets: Boolean,
        val splits: List<MeshSplitOffer>?,
        val initial: StartPresetChoices,
    )

    internal var pendingMeshSplit by mutableStateOf<MeshSplitOffer?>(null)
        private set
    internal var pendingStartScreen by mutableStateOf<StartScreenOffer?>(null)
        private set

    /**
     * A swing being authored on the canvas. [draft] keeps the targets as picked (meshes stay meshes until the
     * commit wraps them); [gizmo] is the handle geometry on the live preview, where those meshes are wrapped.
     * [motion] is the direction the handles edit.
     */
    internal class SwingSession(val existingId: String?, draft: RigSwingEdit,
                                internal val expectation: io.github.psd2live.project.WorkspaceProjectSnapshot?, internal val sessionId: String) {
        var draft by mutableStateOf(draft)
        var gizmo by mutableStateOf<io.github.psd2live.core.SwingGizmo?>(null)
        var motion by mutableStateOf(0)
        var playing by mutableStateOf(false)
        var busy by mutableStateOf(false)
        var error by mutableStateOf<String?>(null)
        /** The patched rig and the edit as replayed on it; the handles are rebuilt from these when the pose changes. */
        internal var preview: Pair<PuppetModel, RigSwingEdit>? = null
    }

    internal var swingSession by mutableStateOf<SwingSession?>(null)
        private set
    private var swingPlayer: Job? = null
    private var swingDraftJob: Job? = null
    internal var swingPreviewValues by mutableStateOf(emptyMap<ParameterId, Float>())
        private set

    /**
     * Starts a swing session on Warps or meshes; a target already swung (or wrapped for a swing) edits that
     * swing. The canvas switches to edit mode, where the handles live.
     */
    internal fun beginSwing(targets: List<String>) {
        val state = _state.value
        if (state.previewModel == null) return
        if (targets.isEmpty() || state.workspaceEditBusy) return
        endSwing()
        setCanvasMode(state.activeCanvas.id, CanvasMode.EDIT)
        // One canvas session at a time: a pending placement would fight over the corner and the pointer.
        if (canvasEditor.placement != null) canvasEditor.cancelPlacement()
        runSwingControl("begin", kotlinx.serialization.json.buildJsonObject {
            put("targets", kotlinx.serialization.json.JsonArray(targets.map { kotlinx.serialization.json.JsonPrimitive(it) }))
            put("preset", SwingPreset.HAIR.name)
        })
    }

    /** Leaves the session without recording anything. */
    internal fun endSwing() {
        val session = swingSession
        swingDraftJob?.cancel(); swingPlayer?.cancel(); swingPlayer = null
        if (session != null) {
            scope.launch {
                val state = currentWorkspaceState() ?: return@launch
                runCatching { workspaceBackend?.controlSwingPreview(kotlinx.serialization.json.buildJsonObject {
                    put("state", state); put("mode", "cancel"); put("session_id", session.sessionId)
                }, MutationAuthor.USER) }
            }
            clearSwingProjection(session)
        }
        swingSession = null
    }

    /** Replaces the draft and refreshes the canvas preview and handles. */
    internal fun updateSwing(draft: RigSwingEdit) {
        val session = swingSession ?: return
        session.draft = draft
        session.error = null
        session.motion = session.motion.coerceIn(0, draft.motions.size - 1)
        runSwingControl("update", kotlinx.serialization.json.buildJsonObject { put("draft", draft.toJson()) })
    }

    /** Rebuilds the handles for the pose on screen, so they stay on the art when other parameters move. */
    internal fun refreshSwingGizmo() {
        val session = swingSession ?: return
        val (puppet, prepared) = session.preview ?: return
        session.gizmo = io.github.psd2live.core.SwingGizmo.of(puppet, prepared, values = canvasPose(_state.value), motion = session.motion)
    }

    /** Takes the settings a handle produced; the handles work on the wrap, the draft keeps the picked targets. */
    internal fun updateSwingSettings(settings: RigSwingEdit) {
        val draft = swingSession?.draft ?: return
        updateSwing(settings.copy(id = draft.id, name = draft.name, targets = draft.targets))
    }

    /** Sets which directions the draft moves in. */
    internal fun setSwingKinds(kinds: List<SwingKind>) {
        if (swingSession == null) return
        if (kinds.isEmpty()) return
        runSwingControl("kinds", kotlinx.serialization.json.buildJsonObject { put("kinds", kotlinx.serialization.json.JsonArray(kinds.map { kotlinx.serialization.json.JsonPrimitive(it.name) })) })
    }

    /** Sets the number of segment parameters in [motion]. */
    internal fun setSwingSegments(motion: Int, segments: Int) {
        if (swingSession == null) return
        runSwingControl("segments", kotlinx.serialization.json.buildJsonObject { put("motion", motion); put("segments", segments) })
    }

    /** Selects the direction the canvas handles edit. */
    internal fun selectSwingMotion(motion: Int) {
        if (swingSession == null) return
        runSwingControl("select", kotlinx.serialization.json.buildJsonObject { put("motion", motion) })
    }

    /** Starts every direction of the draft from [preset]'s shape, with its pendulums sized again. */
    internal fun setSwingPreset(preset: SwingPreset) {
        if (swingSession == null) return
        runSwingControl("preset", kotlinx.serialization.json.buildJsonObject { put("preset", preset.name) })
    }

    /** Gives every direction a pendulum sized from the target, or takes them all away. */
    internal fun setSwingPhysicsEnabled(enabled: Boolean) {
        if (swingSession == null) return
        runSwingControl("physics", kotlinx.serialization.json.buildJsonObject { put("enabled", enabled) })
    }

    private fun runSwingControl(mode: String, fields: kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.JsonObject(emptyMap())) {
        val port = workspaceBackend as? WorkspaceSwingPort ?: return
        val session = swingSession
        val expected = if (mode == "begin") workspaceBackend?.snapshot() else session?.expectation
        if (expected == null) return
        swingDraftJob?.cancel()
        val committing = mode == "commit"
        if (committing) { session?.busy = true; updateState { it.copy(canvasEditBusy = true) } }
        swingDraftJob = scope.launch {
            try {
                withContext(Dispatchers.Default + io.github.psd2live.application.WorkspaceExecution(expected.projectId, expected.state, MutationAuthor.USER)) {
                    if (committing && session != null) port.controlSwingPreview(kotlinx.serialization.json.buildJsonObject {
                        put("state", expected.state); put("mode", "update"); put("session_id", session.sessionId); put("draft", session.draft.toJson())
                    }, MutationAuthor.USER)
                    port.controlSwingPreview(kotlinx.serialization.json.JsonObject(fields + kotlinx.serialization.json.buildJsonObject {
                        put("state", expected.state); put("mode", mode); session?.let { put("session_id", it.sessionId) }
                    }), MutationAuthor.USER)
                }
            } catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                swingSession?.error = failure.message ?: tr("swing.failed")
                if (swingSession == null) updateState { it.copy(statusText = failure.message ?: tr("swing.failed")) }
            } finally {
                if (committing) { session?.busy = false; updateState { it.copy(canvasEditBusy = false) } }
            }
        }
    }

    private fun clearSwingProjection(session: SwingSession) {
        if (canvasEditor.preview === session.preview?.first) canvasEditor.preview = null
        swingPreviewValues = emptyMap()
    }

    /**
     * The pose the edit canvases show and resolve edits at: the one the sliders show ([shownPose]) plus a swing draft.
     * Playback, swing and stale panel values can name parameters the model no longer has or sit past a range, and the
     * shared geometry commands reject both, so the pose is kept to the model.
     */
    internal fun canvasPose(current: PSD2LiveState, live: Map<ParameterId, Float> = livePose.value): Map<ParameterId, Float> {
        val pose = shownPose(current, live) + swingPreviewValues
        val parameters = current.previewModel?.rig?.puppet?.parameters ?: return pose
        val known = parameters.mapTo(HashSet()) { it.id }
        return io.github.psd2live.core.boundedPreviewPose(pose.filterKeys { it in known }, parameters)
    }

    internal fun applySwingPreview(preview: io.github.psd2live.application.WorkspaceSwingPreview) {
        val current = _state.value
        if (preview.report.getValue("workspace_id").jsonPrimitive.content != current.activeWorkspace.id) return
        val id = preview.report.getValue("session_id").jsonPrimitive.content
        if (preview.report.getValue("status").jsonPrimitive.content != "active" || preview.report.getValue("stale").jsonPrimitive.boolean) {
            val session = swingSession?.takeIf { it.sessionId == id } ?: return
            clearSwingProjection(session); swingSession = null
            swingPlayer?.cancel(); swingPlayer = null
            return
        }
        val session = swingSession?.takeIf { it.sessionId == id }
            ?: SwingSession(preview.existingId, preview.draft, workspaceBackend?.snapshot(), id).also { swingSession = it }
        session.draft = preview.draft; session.motion = preview.motion; session.playing = preview.playing
        session.error = null; session.preview = preview.model.rig.puppet to preview.prepared
        canvasEditor.preview = preview.model.rig.puppet; swingPreviewValues = preview.values
        refreshSwingGizmo()
        if (preview.playing && swingPlayer?.isActive != true) swingPlayer = scope.launch {
            while (isActive && swingSession?.sessionId == id) {
                delay(16)
                val frame = workspaceBackend?.swingPreviewFrame(id) ?: break
                applySwingPreview(frame)
            }
        }
        else if (!preview.playing) { swingPlayer?.cancel(); swingPlayer = null }
    }

    /** The application session supplies playback frames on its own clock. */
    internal fun playSwing(play: Boolean) {
        if (swingSession == null) return
        runSwingControl("play", kotlinx.serialization.json.buildJsonObject { put("enabled", play) })
    }

    /** Records the session's swing as one history node and ends the session. */
    internal fun commitSwing() {
        if (swingSession == null) return
        runSwingControl("commit")
    }

    /** Deletes the edited swing; [bake] first keeps its current forms as ordinary keys. */
    internal fun deleteSwing(bake: Boolean) {
        val session = swingSession ?: return
        val id = session.existingId ?: return
        runSwingMutation(session) { workspace, state -> workspace.deleteSwing(id, bake, state, io.github.psd2live.project.MutationAuthor.USER) }
    }

    private fun runSwingMutation(
        session: SwingSession,
        mutation: suspend (WorkspaceSwingPort, String) -> io.github.psd2live.project.WorkspaceMutationResult,
    ) {
        if (_state.value.workspaceEditBusy || session.busy) return
        swingDraftJob?.cancel(); swingPlayer?.cancel(); swingPlayer = null
        clearSwingProjection(session)
        session.busy = true
        updateState { it.copy(canvasEditBusy = true) }
        scope.launch {
            try {
                val workspace = requireNotNull(workspaceBackend) { "Project workspace unavailable" }
                val expected = requireNotNull(session.expectation) { "Project workspace unavailable" }
                withContext(Dispatchers.Default + io.github.psd2live.application.WorkspaceExecution(
                    expected.projectId, expected.state, io.github.psd2live.project.MutationAuthor.USER)) {
                    mutation(workspace, expected.state)
                }
                if (swingSession === session) swingSession = null
            } catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                if (swingSession === session) workspaceBackend?.swingPreviewFrame(session.sessionId)?.let(::applySwingPreview)
                session.error = failure.message ?: tr("swing.failed")
            } finally {
                session.busy = false
                updateState { it.copy(canvasEditBusy = false) }
            }
        }
    }
    // The application owns live scenes; these fields project only the current workspace's returned frames.
    @Volatile internal var simulationSessionId: String? = null
        private set
    private var simStepping: kotlinx.coroutines.Job? = null
    @Volatile private var simulationEpoch = 0L
    private var pendingSimulationDelta = 0f
    private val _simulationFrames = MutableStateFlow<io.github.psd2live.core.sim.SimulatedFrame?>(null)
    /** The live simulation's latest frame, for the canvas; null while none runs or it is still preparing. */
    val simulationFrames: StateFlow<io.github.psd2live.core.sim.SimulatedFrame?> = _simulationFrames.asStateFlow()
    private val _simulationStatus = MutableStateFlow<SimulationStatus>(SimulationStatus.Idle)
    val simulationStatus: StateFlow<SimulationStatus> = _simulationStatus.asStateFlow()
    private var simPreparing: kotlinx.coroutines.Job? = null

    /** What the simulation panel shows under the list. */
    sealed interface SimulationStatus {
        data object Idle : SimulationStatus
        data object Preparing : SimulationStatus
        data class Running(val notes: List<String>) : SimulationStatus
        data class Failed(val message: String) : SimulationStatus
    }

    /**
     * The simulation being baked and how far along, 0..1; null while none is. A batch bakes [count]
     * simulations one after another, [index] of them done.
     */
    data class SimulationBaking(val id: String, val progress: Float, val index: Int = 0, val count: Int = 1) {
        val overall: Float get() = (index + progress) / count
    }

    private val _simulationBaking = MutableStateFlow<SimulationBaking?>(null)
    val simulationBaking: StateFlow<SimulationBaking?> = _simulationBaking.asStateFlow()
    private val _modelDownloadState = MutableStateFlow<io.github.psd2live.core.DownloadState>(io.github.psd2live.core.DownloadState.Idle)
    internal val modelDownloadState = _modelDownloadState.asStateFlow()
    internal fun reportModelDownload(state: io.github.psd2live.core.DownloadState) {
        _modelDownloadState.value = state
    }
    private var simBaking: kotlinx.coroutines.Job? = null

    /**
     * Simulations waiting for the background bake, each with whether it bakes afresh (the Bake buttons) or,
     * after an edit, only when missing or stale and from its old pendulum. Touched on the main thread only.
     */
    private val simBakeQueue = LinkedHashMap<String, Boolean>()
    /** The simulations the running bake round is computing; a new request for one of them restarts the round. */
    private var simBakeRound: Set<String> = emptySet()
    @Volatile private var simBakeRestart = false

    /**
     * Bakes simulation [id] in the background into parameters, keyforms and pendulums; editing goes on
     * meanwhile and the bake lands as its own history node once done. Progress arrives in [simulationBaking],
     * a failure in [simulationStatus].
     */
    internal fun bakeSimulation(id: String) {
        if (_state.value.rigEdits.simEdits.none { it.id == id }) return
        queueSimulationBakes(listOf(id), fresh = true)
    }

    /**
     * Queues [ids] for the background bake. A round bakes everything queued one after another, each on the
     * rig with the bakes before it, and commits the round as one history node on whatever version is current
     * then; a simulation whose setup changed while it baked is baked again instead of committed stale.
     */
    private fun queueSimulationBakes(ids: Collection<String>, fresh: Boolean) {
        if (ids.isEmpty() || _state.value.previewModel == null) return
        ids.forEach { simBakeQueue[it] = fresh || simBakeQueue[it] == true }
        if (ids.any { it in simBakeRound }) simBakeRestart = true
        if (simBaking?.isActive == true) return
        simBaking = scope.launch {
            val self = coroutineContext[kotlinx.coroutines.Job]
            try {
                while (simBakeQueue.isNotEmpty()) {
                    val round = LinkedHashMap(simBakeQueue)
                    simBakeQueue.clear()
                    simBakeRound = round.keys
                    simBakeRestart = false
                    try {
                        bakeRound(round)
                    } catch (restart: kotlinx.coroutines.CancellationException) {
                        if (!simBakeRestart || self?.isActive != true) throw restart
                        round.forEach { (id, fresh) -> simBakeQueue[id] = fresh || simBakeQueue[id] == true }
                    } catch (failure: Exception) {
                        _simulationStatus.value = SimulationStatus.Failed(failure.message ?: failure.toString())
                    }
                }
            } finally {
                // A cancelled runner may finish after a new one started; the queue is the new one's then.
                if (simBaking === self) {
                    simBakeRound = emptySet()
                    simBakeQueue.clear()
                    _simulationBaking.value = null
                }
            }
        }
    }

    /** Waits until the background bake has committed or dropped everything queued. */
    internal suspend fun awaitSimulationBakes() {
        while (true) withContext(Dispatchers.Main) { simBaking?.takeIf { it.isActive } }?.join() ?: return
    }

    /** Bakes [round] off the main thread, then commits what is still current; see [queueSimulationBakes]. */
    private suspend fun bakeRound(round: Map<String, Boolean>) {
        val start = _state.value
        val model = start.previewModel ?: return
        val snapshot = workspaceBackend?.snapshot() ?: return
        // An edit's bake waits only when it is missing or stale; the Bake buttons always bake.
        val ids = withContext(Dispatchers.Default) {
            round.keys.filter { id ->
                val edit = start.rigEdits.simEdits.firstOrNull { it.id == id && it.enabled } ?: return@filter false
                round.getValue(id) || edit.bake == null || io.github.psd2live.core.sim.SimBake.stale(model.rig.puppet, edit)
            }
        }
        if (ids.isEmpty()) return
        _simulationBaking.value = SimulationBaking(ids.first(), 0f, 0, ids.size)
        val (bakes, failures) = withContext(Dispatchers.Default) {
            val job = coroutineContext[kotlinx.coroutines.Job]
            val cancelled = { job?.isCancelled == true || simBakeRestart }
            var overlay = start.rigEdits
            val bakes = LinkedHashMap<String, io.github.psd2live.core.sim.SimBakeResult>()
            val failures = ArrayList<String>()
            ids.forEachIndexed { index, id ->
                val progress = { value: Float -> _simulationBaking.value = SimulationBaking(id, value, index, ids.size) }
                try {
                    if (round.getValue(id)) {
                        val bake = io.github.psd2live.core.sim.SimAuthoring.bake(overlay, model.baseRig.puppet, id, progress, cancelled,
                            model.baseRig.primitiveSkins)
                        overlay = io.github.psd2live.core.sim.SimAuthoring.withBake(overlay, id, bake)
                        bakes[id] = bake
                    } else {
                        val (next, failure) = io.github.psd2live.core.sim.SimAuthoring.rebaked(overlay, model.baseRig.puppet, id, progress, cancelled,
                            autoBake = true, skins = model.baseRig.primitiveSkins)
                        if (cancelled()) throw kotlinx.coroutines.CancellationException("Bake cancelled")
                        if (failure != null) failures += "$id: $failure"
                        else next.simEdits.single { it.id == id }.bake?.takeIf { next !== overlay }?.let { bakes[id] = it }
                        overlay = next
                    }
                } catch (failure: IllegalArgumentException) {
                    failures += "$id: ${failure.message ?: failure}"
                }
                if (cancelled()) throw kotlinx.coroutines.CancellationException("Bake cancelled")
            }
            bakes to failures
        }
        if (failures.isNotEmpty()) _simulationStatus.value = SimulationStatus.Failed(failures.joinToString("\n"))
        if (bakes.isNotEmpty()) commitBakes(bakes, round, snapshot.projectId, model.rig.puppet)
    }

    /**
     * Commits [bakes] as one history node once no other edit is applying, on the version current then. A
     * simulation deleted meanwhile is dropped; one whose setup changed is queued again instead.
     */
    private suspend fun commitBakes(bakes: Map<String, io.github.psd2live.core.sim.SimBakeResult>, round: Map<String, Boolean>,
                                    projectId: String?, baked: org.umamo.runtime.model.PuppetModel) {
        while (true) {
            _state.first { !it.workspaceEditBusy }
            val workspace = workspaceBackend ?: return
            val expected = workspace.snapshot()
            val head = _state.value
            val puppet = head.previewModel?.rig?.puppet
            if (expected.projectId != projectId || puppet == null) return
            val present = bakes.filterKeys { id -> head.rigEdits.simEdits.any { it.id == id } }
            // The rig the round started on needs no check; on a newer one each bake must still match its setup.
            val changed = if (puppet === baked) emptySet() else withContext(Dispatchers.Default) {
                present.filter { (id, bake) ->
                    bake.fingerprint != io.github.psd2live.core.sim.SimBake.fingerprint(puppet, head.rigEdits.simEdits.first { it.id == id })
                }.keys
            }
            changed.forEach { id -> simBakeQueue[id] = round.getValue(id) || simBakeQueue[id] == true }
            val current = present - changed
            if (current.isEmpty()) return
            if (_state.value.workspaceEditBusy) continue
            updateState { it.copy(canvasEditBusy = true) }
            try {
                withContext(Dispatchers.Default + io.github.psd2live.application.WorkspaceExecution(
                    expected.projectId, expected.state, io.github.psd2live.project.MutationAuthor.USER)) {
                    workspace.putSimulationBakes(current, expected.state)
                }
                return
            } catch (conflict: io.github.psd2live.application.WorkspaceConflict) {
                continue
            } catch (switched: io.github.psd2live.application.WorkspaceProjectConflict) {
                return
            } catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                _simulationStatus.value = SimulationStatus.Failed(failure.message ?: failure.toString())
                return
            } finally {
                updateState { it.copy(canvasEditBusy = false) }
            }
        }
    }

    /** The enabled simulations a bake would change: unbaked or stale ones, or every one when all are up to date. */
    internal fun simulationsToBake(current: PSD2LiveState = _state.value): List<String> {
        val puppet = current.previewModel?.rig?.puppet ?: return emptyList()
        val enabled = current.rigEdits.simEdits.filter { it.enabled }
        val outdated = enabled.filter { it.bake == null || io.github.psd2live.core.sim.SimBake.stale(puppet, it) }
        return (outdated.ifEmpty { enabled }).map { it.id }
    }

    /**
     * Bakes [simulationsToBake] in the background, one after another, each on the rig with the bakes before
     * it, and commits them together as one history node. A simulation that fails keeps its old bake and is reported.
     */
    internal fun bakeAllSimulations() = queueSimulationBakes(simulationsToBake(), fresh = true)

    /** Removes every simulation's bake as one history node. */
    internal fun clearAllSimulationBakes() {
        val ids = _state.value.rigEdits.simEdits.filter { it.bake != null }.map { it.id }
        if (ids.isEmpty()) return
        runSimulationMutation("Cleared simulation bakes") { workspace, state -> workspace.putSimulationBakes(ids.associateWith { null }, state) }
    }

    /** Stops the background bake and drops what was queued, and stops a bake running inside a workspace edit. */
    internal fun cancelSimulationBake() {
        simBakeCancelled = true
        simBakeQueue.clear()
        simBakeRound = emptySet()
        simBaking?.cancel()
        simBaking = null
        _simulationBaking.value = null
    }

    @Volatile private var simBakeCancelled = false

    /**
     * Runs [bake] for simulation [id] with its progress shown in [simulationBaking] and the status bar's Cancel
     * wired to its second argument; for bakes that run inside a workspace edit.
     */
    internal fun <T> trackSimulationBake(id: String, bake: (progress: (Float) -> Unit, cancelled: () -> Boolean) -> T): T {
        simBakeCancelled = false
        _simulationBaking.value = SimulationBaking(id, 0f)
        try {
            return bake({ _simulationBaking.value = SimulationBaking(id, it) }, { simBakeCancelled })
        } finally {
            _simulationBaking.value = null
        }
    }

    /** Removes simulation [id]'s bake: its parameters, keys and pendulums go with it. */
    internal fun clearSimulationBake(id: String) =
        runSimulationMutation("Cleared simulation bake $id") { workspace, state -> workspace.putSimulationBake(id, null, state) }

    /** Runs simulation [id] live in the preview, or stops it with null. */
    internal fun setSimulationPreview(id: String?) {
        if (_state.value.simulationPreviewId == id) return
        simPreparing?.cancel()
        simStepping?.cancel(); simStepping = null
        val previous = simulationSessionId
        simulationSessionId = null; simulationEpoch++; pendingSimulationDelta = 0f
        _simulationFrames.value = null
        _simulationStatus.value = SimulationStatus.Idle
        updateState { it.copy(simulationPreviewId = id) }
        if (previous != null) scope.launch(Dispatchers.Default) {
            val state = currentWorkspaceState() ?: return@launch
            try { workspaceBackend?.controlSimulationPreview(kotlinx.serialization.json.buildJsonObject {
                put("state", state); put("mode", "stop"); put("session_id", previous)
            }) } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { /* A switched project/workspace owns its own preview. */ }
        }
        if (id != null) startSimulationPreview(id)
    }

    /** Puts the live simulation back at rest. */
    internal fun restartSimulationPreview() {
        val id = simulationSessionId ?: run {
            _state.value.simulationPreviewId?.let(::startSimulationPreview)
            return
        }
        val current = _state.value; val model = current.previewModel ?: return
        val state = currentWorkspaceState() ?: return
        simStepping?.cancel(); pendingSimulationDelta = 0f
        simPreparing?.cancel()
        val epoch = simulationEpoch
        simPreparing = scope.launch(Dispatchers.Default, start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try { workspaceBackend?.controlSimulationPreview(kotlinx.serialization.json.buildJsonObject {
                put("state", state); put("mode", "restart"); put("session_id", id)
                putJsonObject("values") { simulationPose(current, model).forEach { (parameter, value) -> put(parameter.raw, value) } }
            }) } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) { if (simulationEpoch == epoch) _simulationStatus.value = SimulationStatus.Failed(failure.message ?: "Could not restart simulation") }
        }.also { it.start() }
    }

    private fun startSimulationPreview(id: String) {
        if (simPreparing?.isActive == true) return
        val current = _state.value; val model = current.previewModel ?: return
        val state = currentWorkspaceState() ?: return
        val epoch = simulationEpoch
        _simulationStatus.value = SimulationStatus.Preparing
        simPreparing = scope.launch(Dispatchers.Default, start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                workspaceBackend?.controlSimulationPreview(kotlinx.serialization.json.buildJsonObject {
                    put("state", state); put("mode", "start"); put("simulation_id", id)
                    putJsonObject("values") { simulationPose(current, model).forEach { (parameter, value) -> put(parameter.raw, value) } }
                })
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (simulationEpoch == epoch) _simulationStatus.value = SimulationStatus.Failed(failure.message ?: "Could not prepare simulation")
            }
        }.also { it.start() }
    }

    internal fun applySimulationPreview(preview: io.github.psd2live.application.WorkspaceSimulationPreview) {
        val current = _state.value
        if (preview.report.getValue("project_id").jsonPrimitive.content != current.projectId ||
            preview.report.getValue("workspace_id").jsonPrimitive.content != current.activeWorkspace.id ||
            preview.model.rig.puppet !== current.previewModel?.rig?.puppet ||
            preview.report.getValue("state").jsonPrimitive.content != currentWorkspaceState()) return
        val id = preview.report.getValue("session_id").jsonPrimitive.content
        if (preview.report.getValue("stale").jsonPrimitive.boolean || preview.report.getValue("status").jsonPrimitive.content == "stopped") {
            if (simulationSessionId != id) return
            simulationSessionId = null; _simulationFrames.value = null; _simulationStatus.value = SimulationStatus.Idle
            updateState { it.copy(simulationPreviewId = null) }
            return
        }
        simulationSessionId = id
        _simulationFrames.value = preview.frame
        _simulationStatus.value = SimulationStatus.Running(preview.notes)
        if (current.simulationPreviewId != preview.frame.simulationId) updateState { it.copy(simulationPreviewId = preview.frame.simulationId) }
    }

    private val _simulationAutoBake = MutableStateFlow(AppSettings.simulationAutoBake)
    /** Whether panel edits bake again in their history node; MCP puts keep each simulation's own setting. */
    val simulationAutoBake: StateFlow<Boolean> = _simulationAutoBake.asStateFlow()

    internal fun setSimulationAutoBake(on: Boolean) {
        AppSettings.simulationAutoBake = on
        _simulationAutoBake.value = on
    }

    /**
     * Creates or replaces [edit] as one history node at once; with [simulationAutoBake] on, a missing or stale
     * bake then follows in the background as its own node.
     */
    internal fun putSimulation(edit: io.github.psd2live.core.sim.RigSimEdit) {
        val autoBake = _simulationAutoBake.value
        runSimulationMutation("Set simulation ${edit.id}", then = { if (autoBake) queueSimulationBakes(listOf(edit.id), fresh = false) }) { workspace, state ->
            workspace.putSimulation(edit.toJson(), state, null, autoBake = false).first
        }
    }

    /**
     * Names output [outputId] (a parameter or pendulum of simulation [simId]'s bake) [name]; blank or
     * [defaultName] goes back to the name after the body. One history node, nothing baked again.
     */
    internal fun renameSimulationOutput(simId: String, outputId: String, name: String, defaultName: String) {
        val sim = _state.value.rigEdits.simEdits.firstOrNull { it.id == simId } ?: return
        val trimmed = name.trim()
        val names = if (trimmed.isEmpty() || trimmed == defaultName) sim.outputNames - outputId else sim.outputNames + (outputId to trimmed)
        if (names != sim.outputNames) putSimulation(sim.copy(outputNames = names))
    }

    /**
     * Writes mode [baked] (as simulation [simId]'s bake names it) as [output] says: its ID, range and gain.
     * One history node, nothing baked again; the default setting clears it.
     */
    internal fun setSimulationOutput(simId: String, baked: String, output: io.github.psd2live.core.sim.SimOutput) {
        val sim = _state.value.rigEdits.simEdits.firstOrNull { it.id == simId } ?: return
        val outputs = if (output.isDefault) sim.outputs - baked else sim.outputs + (baked to output)
        if (outputs != sim.outputs) putSimulation(sim.copy(outputs = outputs))
    }

    internal fun deleteSimulation(id: String) {
        if (_state.value.simulationPreviewId == id) setSimulationPreview(null)
        runSimulationMutation("Deleted simulation $id") { workspace, state -> workspace.deleteSimulation(id, state) }
    }

    private val _modelPresetReport = MutableStateFlow<kotlinx.serialization.json.JsonObject?>(null)
    /** What the last model preset made: its simulations, the garments it read and each bake. */
    val modelPresetReport: StateFlow<kotlinx.serialization.json.JsonObject?> = _modelPresetReport.asStateFlow()

    /** Applies [preset] to every recognized part, or with [selectedOnly] to the selected layers, as one history node. */
    internal fun applyModelPreset(preset: io.github.psd2live.core.sim.ModelPresets.Preset, selectedOnly: Boolean) {
        val current = _state.value
        val layers = if (selectedOnly) current.selectedLayerIds.ifEmpty { setOfNotNull(current.selectedLayerId) } else emptySet()
        if (selectedOnly && layers.isEmpty()) return
        _simulationStatus.value = SimulationStatus.Idle
        runSimulationMutation("Applied model preset ${preset.jsonName}", mutation = modelPresetMutation(preset, layers))
    }

    /**
     * Applies [preset] to [layers] (empty: every recognized part), keeping its report; the simulations it made
     * that bake on their own are then baked in the background, as their own history node.
     */
    private fun modelPresetMutation(
        preset: io.github.psd2live.core.sim.ModelPresets.Preset,
        layers: Set<String>,
    ): suspend (WorkspaceSimulationPort, String) -> io.github.psd2live.project.WorkspaceMutationResult = { workspace, state ->
        val (result, report) = workspace.applyModelPreset(preset, layers, state, io.github.psd2live.project.MutationAuthor.USER, autoBake = false)
        _modelPresetReport.value = report
        val made = (report["simulations"] as? kotlinx.serialization.json.JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()
        withContext(Dispatchers.Main) {
            queueSimulationBakes(_state.value.rigEdits.simEdits.filter { it.id in made && it.autoBake }.map { it.id }, fresh = false)
        }
        result
    }

    /** Drops the [front] or back hair simulation preset and brings back the legacy sway, running when [sway]. */
    internal fun restoreClassicHair(front: Boolean, sway: Boolean = true) {
        if (_state.value.simulationPreviewId in io.github.psd2live.core.sim.ModelPresets.PRESET_SIMS) setSimulationPreview(null)
        runSimulationMutation("Restored classic hair sway") { workspace, state ->
            workspace.restoreClassicHair(front, state, io.github.psd2live.project.MutationAuthor.USER, sway)
        }
    }

    /** Removes every clothing simulation preset. */
    internal fun removeClothingPresets() {
        if (_state.value.simulationPreviewId in io.github.psd2live.core.sim.ModelPresets.CLOTHING_SIMS.values) setSimulationPreview(null)
        _simulationStatus.value = SimulationStatus.Idle
        _modelPresetReport.value = null
        runSimulationMutation("Removed clothing simulation presets") { workspace, state ->
            workspace.removeClothingPresets(state, io.github.psd2live.project.MutationAuthor.USER)
        }
    }

    /** A new simulation of the meshes of the selected layers; returns its ID, or null with nothing selected. */
    internal fun createSimulationFromSelection(kind: io.github.psd2live.core.sim.SimKind): String? {
        val current = _state.value
        val model = current.previewModel ?: return null
        val layers = current.selectedLayerIds.ifEmpty { setOfNotNull(current.selectedLayerId) }
        val meshes = model.rig.layerIdByDrawableId.filter { (drawable, layer) ->
            layer in layers && model.rig.puppet.drawables.any { it.id.raw == drawable && it.mesh != null }
        }.keys.sorted()
        if (meshes.isEmpty()) return null
        val id = io.github.psd2live.core.sim.SimAuthoring.nextId(current.rigEdits, meshes)
        val available = model.rig.puppet.parameters.mapTo(HashSet()) { it.id.raw }
        putSimulation(io.github.psd2live.core.sim.RigSimEdit(id, id, kind, meshes, inputs = io.github.psd2live.core.sim.RigSimEdit.defaultInputs(available, kind)))
        return id
    }

    /** Opens the weight brush on [drawableId]'s group of [kind]: selects the mesh's layer and puts the canvas in Edit. */
    internal fun beginVertexGroupPaint(drawableId: String, kind: org.umamo.runtime.model.VertexGroupKind) {
        val current = _state.value
        val model = current.previewModel ?: return
        val layer = model.rig.layerIdByDrawableId[drawableId] ?: return
        selectLayer(layer)
        setCanvasMode(current.activeCanvas.id, CanvasMode.EDIT)
        canvasEditor.weightGroupKind = kind
        canvasEditor.activateTool(io.github.psd2live.ui.CanvasTool.WEIGHT_PAINT)
    }

    /** Runs [mutation] in the background, then [then] on the main thread when it succeeded. */
    private fun runSimulationMutation(
        summary: String,
        expected: io.github.psd2live.project.WorkspaceProjectSnapshot? = workspaceBackend?.snapshot(),
        then: () -> Unit = {},
        mutation: suspend (WorkspaceSimulationPort, String) -> io.github.psd2live.project.WorkspaceMutationResult,
    ) {
        if (_state.value.workspaceEditBusy) return
        updateState { it.copy(canvasEditBusy = true) }
        scope.launch { if (performSimulationMutation(summary, mutation, expected)) then() }
    }

    /**
     * Runs [mutation] on the project workspace; the caller has set canvasEditBusy, which this clears. Returns
     * whether it succeeded.
     */
    private suspend fun performSimulationMutation(
        summary: String,
        mutation: suspend (WorkspaceSimulationPort, String) -> io.github.psd2live.project.WorkspaceMutationResult,
        expected: io.github.psd2live.project.WorkspaceProjectSnapshot? = workspaceBackend?.snapshot(),
    ): Boolean {
        try {
            val workspace = requireNotNull(workspaceBackend) { "Project workspace unavailable" }
            val captured = requireNotNull(expected) { "Project workspace unavailable" }
            withContext(Dispatchers.Default + io.github.psd2live.application.WorkspaceExecution(
                captured.projectId, captured.state, io.github.psd2live.project.MutationAuthor.USER)) {
                mutation(workspace, captured.state)
            }
            return true
        } catch (failure: Exception) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            _simulationStatus.value = SimulationStatus.Failed(failure.message ?: summary)
            return false
        } finally {
            updateState { it.copy(canvasEditBusy = false) }
        }
    }

    /** The pose the live simulation follows: the playing preview's, or the paused edit pose with its physics. */
    private fun simulationPose(current: PSD2LiveState, model: RigPreviewModel): Map<ParameterId, Float> =
        if (current.previewLive && current.animationEnabled && !current.meshOnly && latestLiveParameters.isNotEmpty()) latestLiveParameters
        else parameterScrubPose(current, current.previewPanelState().parameterValues) + pausedPhysics

    private fun stepSimulationPreview(current: PSD2LiveState, model: RigPreviewModel?, dt: Float) {
        if (_simulationStatus.value is SimulationStatus.Failed) return
        val id = current.simulationPreviewId ?: return
        if (model == null || current.rigEdits.simEdits.none { it.id == id }) {
            _simulationFrames.value = null
            return
        }
        val session = simulationSessionId
        if (session == null) {
            if (_simulationStatus.value !is SimulationStatus.Failed) startSimulationPreview(id)
            return
        }
        pendingSimulationDelta = (pendingSimulationDelta + dt).coerceAtMost(0.2f)
        if (simStepping?.isActive == true || simPreparing?.isActive == true) return
        val state = currentWorkspaceState() ?: return
        val elapsed = pendingSimulationDelta; pendingSimulationDelta = 0f
        val steps = kotlin.math.ceil(elapsed / 0.05f).toInt().coerceAtLeast(1)
        simStepping = scope.launch(Dispatchers.Default, start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try { workspaceBackend?.stepSimulationPreview(kotlinx.serialization.json.buildJsonObject {
                put("state", state); put("session_id", session); put("dt", elapsed / steps); put("steps", steps)
                putJsonObject("values") { simulationPose(current, model).forEach { (parameter, value) -> put(parameter.raw, value) } }
            }) } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (conflict: io.github.psd2live.application.WorkspaceConflict) {
                if (simulationSessionId == session) {
                    simulationSessionId = null; _simulationFrames.value = null; _simulationStatus.value = SimulationStatus.Idle
                }
            } catch (failure: Exception) {
                if (simulationSessionId == session) _simulationStatus.value = SimulationStatus.Failed(failure.message ?: "Could not step simulation")
            }
        }.also { it.start() }
    }

    /** False while a live simulation needs frames, so the paused preview keeps pumping. */
    val previewSettled: Boolean get() = pausedPhysicsSettled && (_state.value.simulationPreviewId == null || _simulationStatus.value is SimulationStatus.Failed)

    private val meshSplitQueue = ArrayDeque<String>()
    private val manualMeshSplitRequests = mutableSetOf<String>()
    private var meshSplitChecking = false
    private var meshSplitApplying = false

    private val stateLock = Any()

    private inline fun updateState(transform: (PSD2LiveState) -> PSD2LiveState) {
        synchronized(stateLock) {
            val before = _state.value
            _state.update { current ->
                var next = reconcileCanvasPresentation(current, transform(current))
                val parameters = next.previewModel?.rig?.puppet?.parameters
                if (parameters != null && (parameters != current.previewModel?.rig?.puppet?.parameters ||
                    next.rigEdits.motionClips != current.rigEdits.motionClips)) {
                    val clips = MotionClips.reconcileParameters(next.rigEdits.motionClips, parameters)
                    if (clips != next.rigEdits.motionClips) next = next.copy(rigEdits = next.rigEdits.copy(motionClips = clips))
                }
                if (next.projectOpenGeneration != current.projectOpenGeneration) {
                    pendingMeshSplit = null
                    pendingStartScreen = null
                    meshSplitQueue.clear()
                    manualMeshSplitRequests.clear()
                }
                if (next.activeWorkspace.id != current.activeWorkspace.id || next.projectOpenGeneration != current.projectOpenGeneration)
                    next = next.copy(simulationPreviewId = null)
                // Pruning walks the entire rig and every canvas session. Pointer hover, camera
                // and dock updates must never pay that cost; only a new model can invalidate ids.
                if (next.previewModel !== current.previewModel ||
                    next.projectOpenGeneration != current.projectOpenGeneration
                ) pruneCanvasSessions(next) else next
            }
            val after = _state.value
            pruneParameterSnapshotPreview(after)
            if (before.activeWorkspace.id != after.activeWorkspace.id ||
                before.projectOpenGeneration != after.projectOpenGeneration) {
                simulationEpoch++; simPreparing?.cancel(); simStepping?.cancel()
                simulationSessionId = null; pendingSimulationDelta = 0f
                _simulationFrames.value = null; _simulationStatus.value = SimulationStatus.Idle
                motionEditor.playing = false
                stopProcessMotion()
                latestLiveParameters = emptyMap()
                pausedPhysics = emptyMap()
                setLivePose(emptyMap())
                setMotionFramePose(emptyMap())
                resetPreviewPhysics()
                pointerActive = false
            } else if (before.previewModel !== after.previewModel ||
                (after.activeWorkspace.pose?.authoringPose == true &&
                    (before.parameterValues != after.parameterValues || before.activeWorkspace.pose?.authoringPose != true))) {
                setLivePose(emptyMap())
                pausedPhysics = emptyMap()
                latestLiveParameters = emptyMap()
                if (before.previewModel !== after.previewModel) {
                    simulationEpoch++; simPreparing?.cancel(); simStepping?.cancel()
                    simulationSessionId = null; pendingSimulationDelta = 0f
                    _simulationFrames.value = null; _simulationStatus.value = SimulationStatus.Idle
                }
            }
            // With no preview on screen no frame will replace the last one the sliders show.
            if (before.previewLive && !after.previewLive) setLivePose(emptyMap())
            _uiState.value = _state.value
        }
    }

    private fun replaceState(next: PSD2LiveState) {
        synchronized(stateLock) {
            if (next.projectOpenGeneration != _state.value.projectOpenGeneration) {
                simulationEpoch++; simPreparing?.cancel(); simStepping?.cancel()
                simulationSessionId = null; pendingSimulationDelta = 0f
                _simulationFrames.value = null; _simulationStatus.value = SimulationStatus.Idle
                pendingMeshSplit = null
                pendingStartScreen = null
                meshSplitQueue.clear()
                manualMeshSplitRequests.clear()
            }
            _state.value = next
            pruneParameterSnapshotPreview(next)
            _uiState.value = next
        }
    }

    internal fun updateCanvasPresentation(
        workspaceId: String,
        canvasId: String,
        mode: CanvasMode? = null,
        transform: (PSD2LiveState) -> PSD2LiveState,
    ) {
        updateState { current ->
            val canvas = current.workspaces.firstOrNull { it.id == workspaceId }
                ?.canvases?.firstOrNull { it.id == canvasId } ?: return@updateState current
            val targetMode = mode ?: canvas.mode
            val projected = current.forCanvas(canvasId, workspaceId, targetMode)
            val transformed = transform(projected)
            val shared = WorkspacePose.capture(transformed)
            val presentation = CanvasPresentation.capture(transformed)
            if (presentation == canvas.session(targetMode).presentation && shared == current.activeWorkspace.pose) return@updateState current
            current.updateWorkspace(workspaceId) { workspace ->
                workspace.copy(canvases = workspace.canvases.map {
                    if (it.id == canvasId) it.updateSession(targetMode) { session ->
                        session.copy(presentation = presentation)
                    } else it
                }).withPose(shared)
            }
        }
    }

    // Canvas IDs may repeat across workspaces. Transient editing state belongs to both.
    // Guarded by itself: the UI thread and workspace commands both reach it. Two unsynchronized lookups
    // could each create an editor for one canvas and orphan one, whose placement is then never dismissed.
    private val canvasEditors = mutableMapOf<Pair<String, String>, CanvasEditor>()
    private var editorGeneration = -1L
    private fun editorsSnapshot(): List<CanvasEditor> = synchronized(canvasEditors) { canvasEditors.values.toList() }
    internal fun canvasEditorFor(canvasId: String): CanvasEditor {
        val current = uiState.value
        var retired = emptyList<CanvasEditor>()
        val editor = synchronized(canvasEditors) {
            if (editorGeneration != current.projectOpenGeneration) {
                retired = canvasEditors.values.toList()
                canvasEditors.clear()
                editorGeneration = current.projectOpenGeneration
            }
            canvasEditors.getOrPut(current.activeWorkspace.id to canvasId) {
                CanvasEditor(this, current.activeWorkspace.id, canvasId)
            }
        }
        // Callbacks run outside the map lock; they may take the workspace runtime's lock.
        retired.forEach { it.dismissImagePlacement() }
        return editor
    }
    internal val canvasEditor: CanvasEditor get() = canvasEditorFor(uiState.value.activeCanvas.id)

    /** Paint confirm for whichever canvas still has the dialog open, not only the focused one. */
    internal fun canvasAwaitingMeshRebuild(): CanvasEditor? =
        uiState.value.activeWorkspace.canvases.firstNotNullOfOrNull { canvas ->
            canvasEditorFor(canvas.id).takeIf { it.showRebuildMeshDialog }
        }
    private fun resetCanvasPaintSessions() {
        pendingDepthSplit = null
        editorsSnapshot().forEach { it.resetPaintSession() }
    }


    fun updatePuppetModel(transform: (PuppetModel) -> PuppetModel) {
        val currentPreview = _state.value.previewModel ?: return
        val newPuppet = transform(currentPreview.rig.puppet)
        val updatedRig = currentPreview.rig.copy(puppet = newPuppet)
        val updatedPreview = currentPreview.copy(rig = updatedRig)
        updateState { it.copy(previewModel = updatedPreview, previewModelDirty = true, projectDirty = true) }
        markWorkspaceChanged()
        editorChanged()
    }

    suspend fun applyCommittedPaint(updatedPreview: RigPreviewModel, summary: String) {
        updateState {
            it.copy(
                previewModel = updatedPreview,
                analysis = updatedPreview.analysis,
                rigEdits = updatedPreview.config.rigEdits,
                previewModelDirty = true,
                projectDirty = true,
            ).withLog(tr("editor.paint.applied", summary), level = LogLevel.INFO, tag = "Paint")
        }
        refreshSdkSession(updatedPreview)
        markWorkspaceChanged()
        commitEditorChange(summary)?.await()
    }

    /** Paint tools resume from the authoritative model only after their queued commit has completed. */
    internal fun capturePaintExpectation(): io.github.psd2live.project.WorkspaceProjectSnapshot? =
        workspaceBackend?.snapshot()?.takeIf { it.loaded }

    internal fun beginPaintSession(layerId: String): io.github.psd2live.application.WorkspacePaintSession? {
        val backend = workspaceBackend ?: return null
        val expected = backend.snapshot().takeIf { it.loaded } ?: return null
        return try { backend.beginPaintSession(expected.state, layerId) }
        catch (failure: Exception) { setErrorMessage(failure.message ?: "Could not begin paint session"); null }
    }

    internal fun savePaintSession(session: io.github.psd2live.application.WorkspacePaintSession,
        rebuildMesh: Boolean, preserveSourceRaster: Boolean, summary: String, onCommitted: () -> Unit) {
        if (_state.value.workspaceEditBusy || _state.value.editorDraftBusy) {
            setErrorMessage("An editor operation is still being applied"); return
        }
        val backend = workspaceBackend ?: run { setErrorMessage("Project workspace unavailable"); return }
        val sessionState = session.sessionState
        updateState { it.copy(canvasEditBusy = true) }
        scope.launch {
            try {
                withContext(Dispatchers.Default + io.github.psd2live.application.WorkspaceExecution(
                    session.projectId, session.workspaceState, MutationAuthor.USER)) {
                    backend.commitPaintSession(session.workspaceState, session.id, sessionState, rebuildMesh, preserveSourceRaster, MutationAuthor.USER)
                }
                updateState { it.withLog(tr("editor.paint.applied", summary), level = LogLevel.INFO, tag = "Paint") }
                onCommitted()
            } catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                setErrorMessage(failure.message ?: "Could not save paint changes")
            } finally { updateState { it.copy(canvasEditBusy = false) } }
        }
    }

    internal fun savePaintRaster(request: io.github.psd2live.application.WorkspacePaintRaster,
                                 expected: io.github.psd2live.project.WorkspaceProjectSnapshot?,
                                 summary: String, onCommitted: () -> Unit) {
        if (_state.value.workspaceEditBusy || _state.value.editorDraftBusy) {
            setErrorMessage("An editor operation is still being applied"); return
        }
        val workspace = workspaceBackend ?: run { setErrorMessage("Project workspace unavailable"); return }
        val captured = expected ?: run { setErrorMessage("Paint session has no captured workspace state"); return }
        updateState { it.copy(canvasEditBusy = true) }
        scope.launch {
            try {
                withContext(Dispatchers.Default + io.github.psd2live.application.WorkspaceExecution(
                    captured.projectId, captured.state, MutationAuthor.USER)) {
                    workspace.commitPaintRaster(captured.state, request)
                }
                updateState { it.withLog(tr("editor.paint.applied", summary), level = LogLevel.INFO, tag = "Paint") }
                onCommitted()
            } catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                setErrorMessage(failure.message ?: "Could not save paint changes")
            } finally { updateState { it.copy(canvasEditBusy = false) } }
        }
    }

    /** Offer one source split at a time after the new mesh is actually in the preview. */
    internal fun offerMeshSplit(layerIds: List<String>) {
        if (layerIds.size > 1) {
            offerImportMeshSplit(layerIds)
            return
        }
        layerIds.forEach { id ->
            if (id !in meshSplitQueue && pendingMeshSplit?.layerId != id) meshSplitQueue.addLast(id)
        }
        checkNextMeshSplit()
    }

    /** A layer import with several new layers: offers their splits, alone or on a start screen without presets. */
    internal fun offerImportMeshSplit(layerIds: List<String>) {
        if (!AppSettings.autoDetectMeshSplitsOnImport) return
        if (meshSplitChecking || meshSplitApplying || pendingMeshSplit != null || pendingStartScreen != null) return
        val preview = _state.value.previewModel ?: return
        meshSplitChecking = true
        scope.launch {
            try {
                val offers = detectMeshSplits(preview, layerIds)
                if (_state.value.previewModel === preview) when {
                    offers.size == 1 -> pendingMeshSplit = offers.single()
                    offers.size > 1 -> pendingStartScreen = StartScreenOffer(preview, presets = false, splits = offers,
                        initial = StartPresetChoices.of(_state.value))
                }
            } catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                setErrorMessage(failure.message ?: failure.javaClass.simpleName)
            } finally {
                meshSplitChecking = false
            }
        }
    }

    /**
     * Opens the start screen on the current model and scans every source layer for meshes to split. After a
     * PSD import ([fresh]) the presets open on the defaults; from the Tools menu they open on the model as it is.
     */
    internal fun requestStartScreen(fresh: Boolean = false) {
        if (meshSplitChecking || meshSplitApplying || pendingMeshSplit != null || pendingStartScreen != null) return
        val current = _state.value
        val preview = current.previewModel ?: return
        val initial = if (fresh) StartQuickPreset.DEFAULT.choices else StartPresetChoices.of(current)
        val offer = StartScreenOffer(preview, presets = !current.meshOnly && current.rigEdits.importedCmo3 == null,
            splits = null, initial = initial)
        pendingStartScreen = offer
        meshSplitChecking = true
        scope.launch {
            try {
                val offers = detectMeshSplits(preview, preview.analysis.source.layers.map { it.id.raw })
                if (pendingStartScreen === offer) pendingStartScreen = offer.copy(splits = offers)
            } catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                if (pendingStartScreen === offer) pendingStartScreen = offer.copy(splits = emptyList())
                setErrorMessage(failure.message ?: failure.javaClass.simpleName)
            } finally {
                meshSplitChecking = false
            }
        }
    }

    /** The source layers among [layerIds] whose mesh falls apart into several pieces and can still be split. */
    private suspend fun detectMeshSplits(preview: RigPreviewModel, layerIds: List<String>): List<MeshSplitOffer> {
        val expected = workspaceBackend?.snapshot()
        return withContext(Dispatchers.Default) {
            layerIds.mapNotNull { id ->
                val plan = io.github.psd2live.application.WorkspacePartitionEdits.componentPlan(preview, id)
                    ?: return@mapNotNull null
                val source = preview.analysis.source.layers.firstOrNull { it.id.raw == id }
                    ?: preview.analysis.layers.first { it.source.id.raw == id }.source
                MeshSplitOffer(id, source.name, plan, preview, expected)
            }
        }
    }

    /**
     * Answers the start screen: splits the [decisions] first, since a preset simulation holds on to the meshes
     * it reads, then brings the model to [choices] (null keeps the presets as they are).
     */
    internal fun applyStartScreen(choices: StartPresetChoices?, decisions: List<LayerSplitDecision>) {
        val offer = pendingStartScreen ?: return
        pendingStartScreen = null
        val valid = validSplitDecisions(decisions)
        if (valid.isEmpty() && choices == null) return
        meshSplitApplying = true
        scope.launch {
            try {
                if (valid.isNotEmpty()) applyMeshSplits(offer.preview, valid)
                if (choices != null) applyPresetChoices(choices)
            } catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                setErrorMessage(failure.message ?: failure.javaClass.simpleName)
            } finally {
                meshSplitApplying = false
                if (pendingMeshSplit == null && pendingStartScreen == null) checkNextMeshSplit()
            }
        }
    }

    internal fun dismissStartScreen() {
        pendingStartScreen = null
    }

    /** A PSD import with the start screen turned off still gets the default presets, clothing included. */
    private fun applyStartScreenDefaults() {
        if (meshSplitApplying) return
        meshSplitApplying = true
        scope.launch {
            try {
                applyPresetChoices(StartQuickPreset.DEFAULT.choices)
            } catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                setErrorMessage(failure.message ?: failure.javaClass.simpleName)
            } finally {
                meshSplitApplying = false
            }
        }
    }

    /**
     * Brings the model presets to [choices], changing only what differs: the rig, motion and sway switches as
     * one history step, then each hair or clothing simulation as its own, once the preview has been rebuilt.
     */
    private suspend fun applyPresetChoices(choices: StartPresetChoices) {
        val current = _state.value
        if (current.previewModel == null || current.meshOnly || current.rigEdits.importedCmo3 != null) return
        val parts = PresetParts.of(current.analysis)
        // Both motion switches are one settings intent ahead of the field, so their pose releases are not left
        // to a later diff of the field's draft.
        val motions = buildJsonObject {
            if (choices.motionBasic != current.motionBasic) put("motionBasic", choices.motionBasic)
            if (choices.motionSkeleton != current.motionSkeleton) put("motionSkeleton", choices.motionSkeleton)
        }
        val motionsCommitted = motions.isNotEmpty() && applySettingsIntentNow(motions)
        if (motionsCommitted) {
            if (!choices.motionBasic) closePresetGroupMotion(skeleton = false)
            if (!choices.motionSkeleton) closePresetGroupMotion(skeleton = true)
        }
        val token = "startScreen"
        beginEditorField(token)
        try {
            if (!motionsCommitted && choices.motionBasic != current.motionBasic) setMotionBasic(choices.motionBasic)
            if (!motionsCommitted && choices.motionSkeleton != current.motionSkeleton) setMotionSkeleton(choices.motionSkeleton)
            if (choices.eyeJelly != current.physicsEyeJelly) {
                if (parts.eyeJelly) setPhysicsEyeJelly(choices.eyeJelly)
                else updateState { it.copy(physicsEyeJelly = choices.eyeJelly) }
            }
            // A simulated hair turned back to sway or still goes through its own step below.
            if (!current.hairSimulationFront && choices.frontHair != HairMode.SIMULATION &&
                (choices.frontHair == HairMode.CLASSIC) != current.physicsFrontHair) {
                if (parts.frontHair) setPhysicsFrontHair(choices.frontHair == HairMode.CLASSIC)
                else updateState { it.copy(physicsFrontHair = choices.frontHair == HairMode.CLASSIC) }
            }
            if (!current.hairSimulationBack && choices.backHair != HairMode.SIMULATION &&
                (choices.backHair == HairMode.CLASSIC) != current.physicsBackHair) {
                if (parts.backHair) setPhysicsBackHair(choices.backHair == HairMode.CLASSIC)
                else updateState { it.copy(physicsBackHair = choices.backHair == HairMode.CLASSIC) }
            }
            val rigChanged = choices.headStrength != current.headStrength || choices.bodyStrength != current.bodyStrength ||
                choices.featureDisplacement != current.featureDisplacementEnabled || choices.mouthOutline != current.mouthOutlineEnabled
            if (rigChanged) updateState {
                it.copy(
                    headStrength = choices.headStrength.coerceIn(0f, 4f),
                    bodyStrength = choices.bodyStrength.coerceIn(0f, 4f),
                    featureDisplacementEnabled = choices.featureDisplacement,
                    mouthOutlineEnabled = choices.mouthOutline,
                )
            }
            // One full rebuild covers what the switches above scheduled as runtime updates.
            if (StartPresetChoices.of(_state.value) != StartPresetChoices.of(current)) {
                schedulePreviewRebuild()
                markWorkspaceChanged()
            }
        } finally {
            endEditorField(token)
        }

        val clothingNow = current.rigEdits.simEdits.any { it.id in io.github.psd2live.core.sim.ModelPresets.CLOTHING_SIMS.values }
        val steps = ArrayList<Pair<String, suspend (WorkspaceSimulationPort, String) -> io.github.psd2live.project.WorkspaceMutationResult>>()
        for (front in listOf(true, false)) {
            val exists = if (front) parts.frontHair else parts.backHair
            val simulated = if (front) current.hairSimulationFront else current.hairSimulationBack
            val wanted = if (front) choices.frontHair else choices.backHair
            val preset = if (front) io.github.psd2live.core.sim.ModelPresets.Preset.FRONT_HAIR else io.github.psd2live.core.sim.ModelPresets.Preset.BACK_HAIR
            if (exists && wanted == HairMode.SIMULATION && !simulated) steps += "Applied model preset ${preset.jsonName}" to modelPresetMutation(preset, emptySet())
            if (simulated && wanted != HairMode.SIMULATION) steps += "Restored classic hair sway" to { workspace, head ->
                workspace.restoreClassicHair(front, head, io.github.psd2live.project.MutationAuthor.USER, wanted == HairMode.CLASSIC)
            }
        }
        if (parts.clothing && choices.clothing && !clothingNow) {
            val preset = io.github.psd2live.core.sim.ModelPresets.Preset.CLOTHING
            steps += "Applied model preset ${preset.jsonName}" to modelPresetMutation(preset, emptySet())
        }
        if (clothingNow && !choices.clothing) steps += "Removed clothing simulation presets" to { workspace, head ->
            _modelPresetReport.value = null
            workspace.removeClothingPresets(head, io.github.psd2live.project.MutationAuthor.USER)
        }
        if (steps.isEmpty()) return
        workspaceBackend?.awaitEditorDrafts()
        awaitPreviewRebuild()
        _simulationStatus.value = SimulationStatus.Idle
        for ((summary, mutation) in steps) {
            if (_state.value.workspaceEditBusy) return
            updateState { it.copy(canvasEditBusy = true) }
            performSimulationMutation(summary, mutation)
        }
    }

    /** Waits until no preview rebuild or runtime update is pending, including one a finished job queued again. */
    private suspend fun awaitPreviewRebuild() {
        while (true) {
            val job = previewRebuildJob ?: return
            job.join()
            if (previewRebuildJob === job) return
        }
    }

    internal fun requestMeshSplit(layerId: String) {
        if (pendingMeshSplit?.layerId == layerId) return
        manualMeshSplitRequests += layerId
        offerMeshSplit(listOf(layerId))
    }

    internal data class DepthSplitOffer(
        val preview: RigPreviewModel,
        val sourceId: String,
        val workspaceId: String,
        val canvasId: String,
        val initialMiddleId: String?,
        val expected: io.github.psd2live.project.WorkspaceProjectSnapshot,
    )

    internal var pendingDepthSplit by mutableStateOf<DepthSplitOffer?>(null)
        private set

    /** The context target is the only copied mesh; all other selected meshes stay between its slices. */
    internal fun depthSplitMiddleIds(drawableId: String): List<String> {
        val current = _state.value
        val preview = current.previewModel ?: return emptyList()
        val selected = current.selectedLayerIds.ifEmpty { setOfNotNull(current.selectedLayerId) }
        fun isSelected(id: String) = id in selected || preview.rig.layerIdByDrawableId[id] in selected
        if (!isSelected(drawableId)) return emptyList()
        return preview.rig.puppet.drawables.filter {
            it.id.raw != drawableId && it.mesh != null && isSelected(it.id.raw)
        }.map { it.id.raw }
    }

    internal fun requestDepthSplit(drawableId: String) {
        val current = _state.value
        if (current.isBusy || current.workspaceEditBusy) return
        val preview = current.previewModel ?: return
        if (preview.rig.puppet.drawables.none { it.id.raw == drawableId && it.mesh != null }) return
        if (canvasEditor.paintSession?.isDirty == true) {
            setErrorMessage(tr("editor.depthSplit.pendingPaint")); return
        }
        val expected = workspaceBackend?.snapshot() ?: return
        val middleIds = depthSplitMiddleIds(drawableId)
        if (middleIds.isNotEmpty()) {
            pendingDepthSplit = null
            createDepthSplit(DepthSplitOffer(preview, drawableId, current.activeWorkspace.id,
                current.activeCanvas.id, middleIds.first(), expected), middleIds)
            return
        }
        val others = preview.rig.puppet.drawables.filter { it.id.raw != drawableId && it.mesh != null }
        val selected = others.firstOrNull { it.id.raw in current.selectedLayerIds ||
            preview.rig.layerIdByDrawableId[it.id.raw] in current.selectedLayerIds }
        val neck = others.firstOrNull { d -> preview.analysis.layers.any {
            it.source.id.raw == preview.rig.layerIdByDrawableId[d.id.raw] && it.semantic.tag == SemanticTag.NECK
        } }
        pendingDepthSplit = DepthSplitOffer(preview, drawableId, current.activeWorkspace.id,
            current.activeCanvas.id, selected?.id?.raw ?: neck?.id?.raw, expected)
    }

    internal fun dismissDepthSplit() { pendingDepthSplit = null }

    /** Tools > Upgrade split records is offered while version 2 records are enabled and a version 1 split record remains. */
    internal fun canUpgradeSplitRecords(state: PSD2LiveState): Boolean =
        io.github.psd2live.core.ArtPrimitiveV2.enabled && io.github.psd2live.application.WorkspaceSplitUpgradeEdits.upgradable(state.rigEdits)

    /** Rewrites every version 1 split record as version 2 through the shared command, as one undoable user edit. */
    internal fun upgradeSplitRecords() {
        val current = _state.value
        if (current.isBusy || current.workspaceEditBusy || !canUpgradeSplitRecords(current)) return
        val port: io.github.psd2live.application.WorkspaceSplitUpgradePort = workspaceBackend ?: return
        val expected = workspaceBackend?.snapshot() ?: return
        updateState { it.copy(canvasEditBusy = true, statusText = tr("status.splitUpgrade.working")) }
        scope.launch {
            try {
                val result = withContext(Dispatchers.Default + io.github.psd2live.application.WorkspaceExecution(
                    expected.projectId, expected.state, MutationAuthor.USER)) {
                    port.upgradeSplitRecords(requireNotNull(expected.state), null, MutationAuthor.USER)
                }
                val records = result.getValue("records").jsonArray.map { it.jsonObject }
                val upgraded = records.count { it.getValue("upgraded").jsonPrimitive.boolean }
                val kept = records.filterNot { it.getValue("upgraded").jsonPrimitive.boolean }.joinToString("; ") { record ->
                    val layers = record.getValue("layers").jsonArray.joinToString { it.jsonPrimitive.content }
                    "$layers: ${record["reason"]?.jsonPrimitive?.content}"
                }
                updateState { it.copy(statusText = if (kept.isEmpty()) tr("status.splitUpgrade.done", upgraded)
                    else tr("status.splitUpgrade.partial", upgraded, records.size, kept)) }
            } catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                setErrorMessage(failure.message ?: tr("status.splitUpgrade.failed"))
            } finally {
                updateState { it.copy(canvasEditBusy = false) }
            }
        }
    }

    internal fun confirmDepthSplit(middleId: String) {
        val offer = pendingDepthSplit ?: return
        if (_state.value.workspaceEditBusy) return
        pendingDepthSplit = null
        createDepthSplit(offer, listOf(middleId))
    }

    private fun createDepthSplit(offer: DepthSplitOffer, middleIds: List<String>) {
        if (_state.value.previewModel !== offer.preview) {
            setErrorMessage(tr("editor.depthSplit.changed")); return
        }
        val port: io.github.psd2live.application.WorkspaceSourcePort = workspaceBackend ?: return
        val frontLayerId = "depth:${java.util.UUID.randomUUID()}"
        val request = kotlinx.serialization.json.buildJsonObject {
            put("source_id", offer.sourceId)
            put("front_layer_id", frontLayerId)
            put("middle_ids", kotlinx.serialization.json.JsonArray(middleIds.map { kotlinx.serialization.json.JsonPrimitive(it) }))
            val name = offer.preview.rig.puppet.drawables.single { it.id.raw == offer.sourceId }.name
            put("names", kotlinx.serialization.json.JsonArray(listOf(tr("editor.depthSplit.backName", name),
                tr("editor.depthSplit.frontName", name)).map { kotlinx.serialization.json.JsonPrimitive(it) }))
        }
        updateState { it.copy(canvasEditBusy = true, statusText = tr("editor.depthSplit.working")) }
        scope.launch {
            try {
                val result = withContext(Dispatchers.Default + io.github.psd2live.application.WorkspaceExecution(
                    offer.expected.projectId, offer.expected.state, MutationAuthor.USER)) {
                    port.splitDepth(requireNotNull(offer.expected.state), request)
                }
                // Both slices replace the source layer; the front is the one to paint.
                revealCanvasLayers(result.state, offer.workspaceId, offer.canvasId, result.affectedLayerIds)
                updateCanvasPresentation(offer.workspaceId, offer.canvasId, CanvasMode.EDIT) {
                    it.copy(selectedLayerId = frontLayerId, selectedLayerIds = setOf(frontLayerId), selectedDeformerId = null)
                }
                if (_state.value.activeWorkspace.id == offer.workspaceId && _state.value.activeCanvas.id == offer.canvasId) {
                    setCanvasMode(offer.canvasId, CanvasMode.EDIT)
                    val editor = canvasEditorFor(offer.canvasId)
                    editor.activateTool(io.github.psd2live.ui.CanvasTool.PAINT_ERASER)
                    editor.startPaintSession(frontLayerId, forceReload = true)
                    requestCanvasFocus(offer.canvasId)
                }
            } catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                setErrorMessage(failure.message ?: tr("editor.depthSplit.failed"))
            } finally {
                updateState { it.copy(canvasEditBusy = false) }
            }
        }
    }

    internal fun dismissMeshSplit() {
        pendingMeshSplit = null
        checkNextMeshSplit()
    }

    internal fun dismissAllMeshSplits() {
        pendingMeshSplit = null
        pendingStartScreen = null
        meshSplitQueue.clear()
        manualMeshSplitRequests.clear()
    }

    private fun checkNextMeshSplit() {
        if (meshSplitChecking || meshSplitApplying || pendingMeshSplit != null || pendingStartScreen != null || meshSplitQueue.isEmpty()) return
        val id = meshSplitQueue.removeFirst()
        val preview = _state.value.previewModel ?: return
        if (preview.config.rigEdits.importedCmo3 != null) {
            if (id in manualMeshSplitRequests) setErrorMessage(tr("editor.meshSplit.authored"))
            manualMeshSplitRequests -= id
            checkNextMeshSplit()
            return
        }
        meshSplitChecking = true
        scope.launch {
            try {
                val offer = detectMeshSplits(preview, listOf(id)).singleOrNull()
                if (_state.value.previewModel === preview && offer != null) pendingMeshSplit = offer
                else if (id in manualMeshSplitRequests && _state.value.previewModel === preview)
                    setErrorMessage(tr("editor.meshSplit.none"))
                manualMeshSplitRequests -= id
            } catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                setErrorMessage(failure.message ?: failure.javaClass.simpleName)
            } finally {
                meshSplitChecking = false
                if (pendingMeshSplit == null && pendingStartScreen == null) checkNextMeshSplit()
            }
        }
    }

    internal fun confirmMeshSplit(names: List<String>, sides: List<Side>) {
        val offer = pendingMeshSplit ?: return
        val valid = validSplitDecisions(listOf(LayerSplitDecision(offer, names, sides)))
        if (valid.isEmpty()) return
        pendingMeshSplit = null
        meshSplitApplying = true
        scope.launch {
            try {
                applyMeshSplits(offer.preview, valid)
            } catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                setErrorMessage(failure.message ?: failure.javaClass.simpleName)
            } finally {
                meshSplitApplying = false
                if (pendingMeshSplit == null && pendingStartScreen == null) checkNextMeshSplit()
            }
        }
    }

    /** The decisions that name every piece, each name once. */
    private fun validSplitDecisions(decisions: List<LayerSplitDecision>) = decisions.filter { d ->
        d.names.size == d.offer.plan.components.size &&
        d.sides.size == d.names.size &&
        d.names.all { it.isNotBlank() } &&
        d.names.map(String::trim).distinct().size == d.names.size
    }

    /** Splits each decided layer of [basePreview] into its pieces as one history step; nothing if the model moved on. */
    private suspend fun applyMeshSplits(basePreview: RigPreviewModel, validDecisions: List<LayerSplitDecision>) {
        if (_state.value.previewModel !== basePreview) return
        val workspace = requireNotNull(workspaceBackend) { "Workspace is not ready" }
        val expected = requireNotNull(validDecisions.first().offer.expected) { "Partition offer has no captured workspace state" }
        require(validDecisions.all { it.offer.expected?.state == expected.state }) { "Partition offers belong to different states" }
        val port: io.github.psd2live.application.WorkspaceSourcePort = workspace
        val requests = validDecisions.map { decision -> kotlinx.serialization.json.buildJsonObject {
            put("layer_id", decision.offer.layerId)
            put("names", kotlinx.serialization.json.JsonArray(decision.names.map { kotlinx.serialization.json.JsonPrimitive(it) }))
            put("sides", kotlinx.serialization.json.JsonArray(decision.sides.map { kotlinx.serialization.json.JsonPrimitive(it.name.lowercase()) }))
        } }
        withContext(Dispatchers.Default + io.github.psd2live.application.WorkspaceExecution(expected.projectId, expected.state, MutationAuthor.USER)) {
            port.splitMeshComponents(requireNotNull(expected.state), requests)
        }
    }

    fun requestCanvasPathTool() {
        editorForFocusedCanvas().activateTool(io.github.psd2live.ui.CanvasTool.CREATE_DEFORM_PATH)
    }

    /**
     * The editor of the canvas the panels are following. Structural tools stay on that canvas:
     * a preview canvas is switched to its own edit session instead of focusing some other edit canvas
     * and dropping the pick the hierarchy just made.
     */
    internal fun editorForFocusedCanvas(): CanvasEditor {
        val current = uiState.value
        val canvas = current.activeCanvas
        if (canvas.mode != CanvasMode.EDIT) {
            val layer = current.selectedLayerId
            val deformer = current.selectedDeformerId
            if (layer != null || deformer != null) {
                updateCanvasPresentation(current.activeWorkspace.id, canvas.id, CanvasMode.EDIT) {
                    it.copy(
                        selectedLayerId = layer,
                        selectedDeformerId = if (layer != null) null else deformer,
                    )
                }
            }
            setCanvasMode(canvas.id, CanvasMode.EDIT)
        }
        return canvasEditorFor(canvas.id)
    }
    /** Opaque document generation/version, captured when a gesture or dialog begins. */
    internal fun currentWorkspaceState(): String? = workspaceBackend?.snapshot()?.takeIf { it.loaded }?.state

    /** A completed authored gesture hands over frozen poses through the neutral preview port. */
    private fun persistAuthoredPose() {
        val port = workspaceBackend as? io.github.psd2live.application.WorkspacePreviewPort ?: return
        val state = currentWorkspaceState() ?: return
        val current = _state.value
        val poses = mapOf(current.activeWorkspace.id to io.github.psd2live.application.WorkspacePose(
            current.parameterValues.toMap(), current.lockedParameters.toSet()))
        port.commitAuthoredPoses(state, poses)
    }

    fun saveAuthoringEdits(expectedState: String, edits: kotlinx.serialization.json.JsonArray, onComplete: (String?) -> Unit) =
        saveWorkspaceEdit(onComplete) {
            val workspace: io.github.psd2live.application.WorkspaceRigPort = requireNotNull(workspaceBackend) { "Project workspace unavailable" }
            workspace.authorRig(expectedState, edits, io.github.psd2live.project.MutationAuthor.USER)
        }

    internal fun saveDocumentEdits(expectedState: String, summary: String,
                                  edits: List<io.github.psd2live.application.WorkspaceDocumentOperation>, onComplete: (String?) -> Unit) =
        saveWorkspaceEdit(onComplete) {
            val workspace: io.github.psd2live.application.WorkspaceDocumentPort = requireNotNull(workspaceBackend) { "Project workspace unavailable" }
            workspace.applyDocumentEdits(expectedState, summary, edits, io.github.psd2live.project.MutationAuthor.USER)
        }

    private val warpControlFields = mutableMapOf<String, WarpControlField>()

    internal fun applyWarpControlField(token: String, operation: String, request: kotlinx.serialization.json.JsonObject) {
        val current = _state.value
        if (current.workspaceEditBusy || current.editorDraftBusy) return
        val source = current.previewModel ?: return
        val expected = currentWorkspaceState() ?: return
        val edit = io.github.psd2live.application.WorkspaceDocumentOperation(operation, request)
        val field = warpControlFields.getOrPut(token) { WarpControlField(expected, current, WorkspaceStateCodec.document(current), source, edit) }
        try {
            val document = io.github.psd2live.application.WorkspaceWarpControlEdits.apply(edit, field.document, field.model)
            val records = document.rigEdits.authoringJournal.drop(field.document.rigEdits.authoringJournal.size)
            val puppet = records.fold(field.model.rig.puppet) { model, record -> io.github.psd2live.core.RigAuthoringJournal.apply(model, record) }
            val preview = field.model.copy(rig = field.model.rig.copy(puppet = puppet), config = field.model.config.copy(rigEdits = document.rigEdits))
            field.operation = edit; field.preview = preview
            updateState { it.copy(previewModel = preview, previewModelDirty = true, projectDirty = true) }
        } catch (failure: Exception) { updateState { it.copy(errorMessage = failure.message) } }
    }

    internal fun endWarpControlField(token: String) {
        val field = warpControlFields.remove(token) ?: return
        saveDocumentEdits(field.expectedState, "Edit Warp controls", listOf(field.operation)) { failure ->
            if (failure != null || currentWorkspaceState() == field.expectedState) updateState { state ->
                if (state.previewModel === field.preview) state.copy(previewModel = field.state.previewModel,
                    previewModelDirty = field.state.previewModelDirty, projectDirty = field.state.projectDirty,
                    errorMessage = failure) else state.copy(errorMessage = failure)
            }
        }
    }

    private fun saveWorkspaceEdit(onComplete: (String?) -> Unit, mutation: suspend () -> Unit) {
        if (_state.value.workspaceEditBusy || _state.value.editorDraftBusy) { onComplete("An editor operation is still being applied"); return }
        updateState { it.copy(canvasEditBusy = true) }
        scope.launch {
            try {
                withContext(Dispatchers.Default) { mutation() }
                onComplete(null)
            } catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                onComplete(failure.message ?: "Could not save editor changes")
            } finally {
                updateState { it.copy(canvasEditBusy = false) }
                runQueuedProjectSave()
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
        beginEditorSession(token)
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
        beginEditorSession(token)
        pendingRigEdits[token] = edit
        patchPreview(edit)
    }

    /** Applies the pending edit to the puppet in place, which is what makes the field feel immediate. */
    private fun patchPreview(edit: kotlinx.serialization.json.JsonObject) {
        val current = _state.value.previewModel ?: return
        val patched = runCatching { RigStructureEdits.apply(current.rig.puppet, listOf(edit)) }.getOrNull() ?: return
        updateState {
            it.copy(
                previewModel = it.previewModel?.copy(rig = it.previewModel.rig.copy(puppet = patched)) ?: current,
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
        updateState { current ->
            current.copy(
                parentOverrides = current.parentOverrides.filterKeys { it !in removed },
                selectedDeformerId = if (current.selectedDeformerId in removed) null else current.selectedDeformerId,
            )
        }
        val expected = currentWorkspaceState() ?: return
        val command = kotlinx.serialization.json.JsonObject(
            linkedMapOf(
                "op" to kotlinx.serialization.json.JsonPrimitive("structure"),
                "edits" to kotlinx.serialization.json.JsonArray(edits),
            ),
        )
        saveAuthoringEdits(expected, kotlinx.serialization.json.JsonArray(listOf(command))) {}
    }

    private fun recordStructure(edit: kotlinx.serialization.json.JsonObject) {
        val expected = currentWorkspaceState() ?: return
        val command = kotlinx.serialization.json.JsonObject(
            linkedMapOf(
                "op" to kotlinx.serialization.json.JsonPrimitive("structure"),
                "edits" to kotlinx.serialization.json.JsonArray(listOf(edit)),
            ),
        )
        saveAuthoringEdits(expected, kotlinx.serialization.json.JsonArray(listOf(command))) {}
    }

    fun saveParameterDefinition(
		action: String,
		id: String,
		name: String,
		min: Float,
		default: Float,
		max: Float,
		parameterKind: org.umamo.runtime.model.ParameterKind = org.umamo.runtime.model.ParameterKind.NORMAL,
		keyEdits: List<kotlinx.serialization.json.JsonObject> = emptyList(),
		expectedState: String? = null,
		parentGroupId: String? = null,
		onComplete: (String?) -> Unit,
	) {
        flushEditorFields()
        val expected = expectedState ?: currentWorkspaceState()
        if (expected == null) { onComplete("Project workspace unavailable"); return }
        require(action in setOf("create", "update", "delete")) { "Unknown parameter action: $action" }
        val definition = io.github.psd2live.application.WorkspaceDocumentOperation("parameter_$action",
            kotlinx.serialization.json.buildJsonObject {
                put("parameter_id", id)
                if (action != "delete") {
                    put("name", name); put("min", min); put("default", default); put("max", max)
                    put("kind", parameterKind.name)
                }
            })
        val trailing = if (action == "delete") emptyList() else buildList {
            if (action == "create" && parentGroupId != null) add(kotlinx.serialization.json.buildJsonObject {
                put("op", "structure")
                put("edits", kotlinx.serialization.json.JsonArray(listOf(structureEdit("move", "parameter", id,
                    kotlinx.serialization.json.buildJsonObject { put("parent_id", parentGroupId) }))))
            })
            addAll(keyEdits)
        }
        val edits = listOf(definition) + if (trailing.isEmpty()) emptyList() else listOf(
            io.github.psd2live.application.WorkspaceDocumentOperation("keyform_apply",
                kotlinx.serialization.json.buildJsonObject { put("changes", kotlinx.serialization.json.JsonArray(trailing)) }))
        saveDocumentEdits(expected, "${action.replaceFirstChar(Char::uppercase)} parameter $id", edits, onComplete)
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

    fun setParameterGroupLabelColor(groupId: String, color: org.umamo.runtime.model.ParameterLabelColor) {
        applyRigStructure(
            "color",
            "param_group",
            groupId,
            kotlinx.serialization.json.buildJsonObject {
                when (color) {
                    org.umamo.runtime.model.ParameterLabelColor.None ->
                        put("label_type", kotlinx.serialization.json.JsonPrimitive("UNDEFINED"))
                    is org.umamo.runtime.model.ParameterLabelColor.Preset ->
                        put("label_type", kotlinx.serialization.json.JsonPrimitive(color.kind.cmo3Name))
                    is org.umamo.runtime.model.ParameterLabelColor.Custom -> {
                        put("label_type", kotlinx.serialization.json.JsonPrimitive("CUSTOM"))
                        put("color", kotlinx.serialization.json.JsonPrimitive(color.argb))
                    }
                }
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
	private var workspaceBackend: WorkspaceBackend? = null
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
    /**
     * The save asked for while an edit was being applied: true for Save As. One slot, and a Save As in it is never
     * turned into a plain save by a later Ctrl+S (the Save As dialog saves the same content), nor dropped.
     */
    internal val queuedProjectSave: Boolean? get() = queuedCanvasSave

    fun requestProjectSave(saveAs: Boolean = false) {
        // A save captures the workspace, so it has to see the value still sitting in a focused field.
        flushEditorFields()
        if (_state.value.workspaceEditBusy) {
            val queued = queuedCanvasSave == true || saveAs
            queuedCanvasSave = queued
            updateState { it.copy(statusText = tr(if (queued) "project.saveAsQueued" else "project.saveQueued")) }
            return
        }
        if (_state.value.analysis == null) return
        if (saveAs || _state.value.projectFile == null) {
            updateState { it.copy(showProjectLocationDialog = true, projectSaveError = null) }
        } else saveProjectTo(Path.of(_state.value.projectFile!!))
    }
    /** Runs the save [requestProjectSave] queued, once no edit is being applied; a Save As opens its dialog. */
    internal fun runQueuedProjectSave() {
        if (_state.value.workspaceEditBusy) return
        val saveAs = queuedCanvasSave ?: return
        queuedCanvasSave = null
        if (_state.value.statusText == tr(if (saveAs) "project.saveAsQueued" else "project.saveQueued")) updateState { it.copy(statusText = tr("status.ready")) }
        requestProjectSave(saveAs)
    }
    fun clearProjectSaveError() { updateState { it.copy(projectSaveError = null) } }
    fun cancelProjectLocation() {
        pendingDestructiveAction = null
        updateState { it.copy(showProjectLocationDialog = false) }
    }
    fun saveProjectTo(path: Path) {
        scope.launch {
            try {
                saveProjectNow(path)
                updateState { it.copy(showProjectLocationDialog = false) }
                if (!_state.value.projectDirty) pendingDestructiveAction?.also { pendingDestructiveAction = null; it() }
            } catch (_: Exception) { pendingDestructiveAction = null }
        }
    }
    internal suspend fun saveProjectNow(path: Path? = null): String =
        executeUserProject({ it.saveProjectAt(path?.toAbsolutePath()?.normalize()) }).historyNodeId

    private suspend fun executeUserProject(
        action: suspend (io.github.psd2live.application.WorkspaceProjectLifecycle) -> WorkspaceMutationResult,
        discardRejectedDraft: Boolean = false,
    ): WorkspaceMutationResult {
        val workspace = requireNotNull(workspaceBackend) { "Project workspace unavailable" }
        try { workspace.awaitEditorDrafts() }
        catch (failure: Exception) {
            if (failure is kotlinx.coroutines.CancellationException || !discardRejectedDraft) throw failure
        }
        val expected = workspace.snapshot()
        return withContext(io.github.psd2live.application.WorkspaceExecution(
            expected.projectId, expected.state, MutationAuthor.USER)) { action(workspace) }
    }
    internal suspend fun openProjectNow(path: Path, discardUnsaved: Boolean = false) =
        executeUserProject({ it.openProjectAt(path.toAbsolutePath().normalize(), discardUnsaved) },
            discardRejectedDraft = discardUnsaved)

    fun openProject(path: Path) = withSavedChanges {
        scope.launch {
            try {
                openProjectNow(path, discardUnsaved = true)
            } catch (failure: Exception) { updateState { it.copy(errorMessage = failure.message) } }
        }
    }
    internal fun installProjectState(state: PSD2LiveState, expected: PSD2LiveState? = null, dirty: Boolean = false,
                                     cancelActiveWork: Boolean = true) = synchronized(stateLock) {
        expected?.let { prior ->
            val current = _state.value
            require(current.projectId == prior.projectId && current.projectOpenGeneration == prior.projectOpenGeneration &&
                current.projectEditVersion == prior.projectEditVersion) { "Workspace changed while loading project" }
        }
        previewRebuildJob?.cancel()
        if (cancelActiveWork) activeWorkJob?.cancel()
        resetCanvasPaintSessions()
        state.projectFile?.let(AppSettings::rememberRecentFile)
        replaceState(state.copy(
            projectDirty = dirty,
            projectOpenGeneration = _state.value.projectOpenGeneration + 1,
            recentFiles = AppSettings.recentFiles(),
        ))
    }
    internal fun applySourceImport(expected: PSD2LiveState, document: io.github.psd2live.project.WorkspaceDocument,
                                   preview: RigPreviewModel, path: Path?, projectId: String) {
        val recognized = preview.analysis.layers.count { it.semantic.tag != SemanticTag.UNKNOWN }
        val status = tr("status.analysisSummary", preview.analysis.source.widthPx, preview.analysis.source.heightPx,
            preview.analysis.layers.size, recognized)
        applyImportedSource(expected, document, preview, path, path?.fileName?.toString() ?: "Generated artwork", projectId, false, status)
        if (path != null) updateState { it.withLogs(listOf(tr("log.analysis", preview.analysis.layers.size,
            preview.analysis.anchors.character.width.toInt(), preview.analysis.anchors.character.height.toInt())) +
            preview.analysis.warnings.map { warning -> tr("log.warning", warning) }, level = LogLevel.INFO, tag = "Analysis") }
    }

    internal fun applyCmo3Import(expected: PSD2LiveState, document: io.github.psd2live.project.WorkspaceDocument,
                               preview: RigPreviewModel, path: Path, projectId: String, replacing: Boolean) =
        applyImportedSource(expected, document, preview, path, path.fileName.toString(), projectId, replacing,
            tr("cmo3.imported", path.fileName.toString()))

    /** Domain reading, generation and history belong to the application; the GUI only projects a prepared import. */
    private fun applyImportedSource(expected: PSD2LiveState, document: io.github.psd2live.project.WorkspaceDocument,
                                    preview: RigPreviewModel, path: Path?, sourceName: String, projectId: String,
                                    replacing: Boolean, status: String) = synchronized(stateLock) {
        val current = _state.value
        check(current.projectId == expected.projectId && current.projectOpenGeneration == expected.projectOpenGeneration &&
            current.projectEditVersion == expected.projectEditVersion) { "Workspace changed while importing model" }
        val defaults = preview.rig.puppet.parameters.associate { it.id to it.default }
        val workspaces = current.workspaces.map { workspace ->
            val pose = WorkspacePose(parameterValues = defaults,
                authoringPose = replacing && workspace.pose?.authoringPose == true,
                mouseTrackingEnabled = if (replacing) workspace.pose?.mouseTrackingEnabled ?: current.mouseTrackingEnabled else true)
            val canvases = workspace.canvases.map { canvas ->
                fun reset(session: CanvasModeSession) = session.copy(
                    camera = if (replacing) session.camera else TabCamera(),
                    presentation = if (replacing) session.presentation.copy(selectedLayerId = null, selectedLayerIds = emptySet(),
                        selectedDeformerId = null, hoveredLayerId = null, hoveredDeformerId = null,
                        isolatedLayerId = null, isolationSnapshot = null) else CanvasPresentation())
                canvas.copy(editSession = reset(canvas.editSession), previewSession = reset(canvas.previewSession))
            }
            workspace.copy(canvases = canvases).withPose(pose)
        }
        val next = WorkspaceStateCodec.decode(document.settings, current).copy(
            workspaces = workspaces, mouseTrackingEnabled = if (replacing) current.mouseTrackingEnabled else true,
            projectId = projectId, projectSourceName = if (replacing) current.projectSourceName else sourceName,
            projectFile = if (replacing) current.projectFile else null,
            inputPath = if (replacing) current.inputPath else path?.toString().orEmpty(),
            loadedInputPath = if (replacing) current.loadedInputPath else path?.toString(),
            loadedInputFileSignature = if (replacing) current.loadedInputFileSignature else null,
            analysis = preview.analysis, previewModel = preview, previewModelDirty = false,
            rigEdits = document.rigEdits, generationSource = document.generationSource,
            meshSource = document.meshSource,
            placementSource = document.placementSource,
            textureOverrides = document.textureOverrides,
            layerOverrides = document.layerOverrides, documentLayerVisibility = document.layerVisibility,
            deletedLayerIds = document.deletedLayerIds, parentOverrides = document.parentOverrides, meshOverrides = document.meshOverrides,
            layerVisibility = if (replacing) current.layerVisibility else emptyMap(),
            deformerVisibility = if (replacing) current.deformerVisibility else emptyMap(),
            selectedLayerId = null, selectedLayerIds = emptySet(), selectedDeformerId = null,
            hoveredLayerId = null, hoveredDeformerId = null, isolatedLayerId = null, isolationSnapshot = null, clipMaskPickSourceId = null,
            parameterValues = preview.rig.puppet.parameters.associate { it.id to it.default },
            previewParameterValues = emptyMap(), lockedParameters = emptySet(),
            parameterSnapshots = if (replacing) current.parameterSnapshots else emptyList(),
            animationEnabled = false, simulationPreviewId = null,
            historySnapshot = if (replacing) current.historySnapshot else null,
            historyAnnotations = if (replacing) current.historyAnnotations else emptyMap(),
            projectDirty = true, projectEditVersion = current.projectEditVersion + 1,
            showProjectLocationDialog = false, statusText = status, errorMessage = null,
        )
        if (replacing) {
            previewRebuildJob?.cancel()
            resetCanvasPaintSessions()
            replaceState(next)
        } else installProjectState(next, current, dirty = true, cancelActiveWork = false)
    }
    private val pendingProjectSaves = java.util.concurrent.atomic.AtomicInteger()
    internal fun projectSaveStarted() { pendingProjectSaves.incrementAndGet(); updateState { it.copy(projectSaving = true, projectSaveError = null) } }
    internal fun projectSaveFailed(failure: Exception, captured: PSD2LiveState) {
        val saving = pendingProjectSaves.decrementAndGet() > 0
        updateState { current ->
            if (current.projectId != captured.projectId || current.projectOpenGeneration != captured.projectOpenGeneration) {
                current.copy(projectSaving = saving)
            } else current.copy(projectSaving = saving, projectDirty = true, projectSaveError = failure.message ?: "Save failed")
        }
    }
    internal fun projectSaveFinished(path: Path, headId: String, captured: PSD2LiveState) {
        val saving = pendingProjectSaves.decrementAndGet() > 0
        val saved = path.toAbsolutePath().normalize().toString()
        AppSettings.rememberRecentFile(saved)
        updateState { current ->
            if (current.projectId != captured.projectId || current.projectOpenGeneration != captured.projectOpenGeneration) {
                return@updateState current.copy(projectSaving = saving, recentFiles = AppSettings.recentFiles())
            }
            current.copy(projectFile = saved, projectSaving = saving,
            projectDirty = current.historySnapshot?.headNodeId != headId || current.projectAuxiliaryVersion != captured.projectAuxiliaryVersion || io.github.psd2live.ui.state.WorkspaceStateCodec.editableIdentity(current) != io.github.psd2live.ui.state.WorkspaceStateCodec.editableIdentity(captured),
            projectSaveError = null, recentFiles = AppSettings.recentFiles())
        }
    }
    internal fun markProjectAuxiliaryChanged() { updateState { it.copy(projectDirty = true, projectEditVersion = it.projectEditVersion + 1, projectAuxiliaryVersion = it.projectAuxiliaryVersion + 1) } }
    private fun markWorkspaceChanged() { updateState { if (it.analysis == null) it else it.copy(projectDirty = true, projectEditVersion = it.projectEditVersion + 1) } }
    /**
     * Open field sessions, so a slider drag or a half-typed value commits once rather than per sample or
     * per keystroke. See [EditorFieldSessions].
     */
    private val editorSessions = EditorFieldSessions { commitEditorChange() }
    private var editorDraftExpected: io.github.psd2live.project.WorkspaceProjectSnapshot? = null
    private var editorDraftCount = 0
    /** Settings switches made while a field session is open; the draft replays them through the settings intent. */
    private val editorSettingsIntents = mutableListOf<kotlinx.serialization.json.JsonObject>()

    private fun beginEditorSession(token: String) {
        if (!editorSessions.anyOpen) {
            editorDraftExpected = workspaceBackend?.snapshot()
            synchronized(stateLock) { editorSettingsIntents.clear() }
        }
        editorSessions.begin(token)
    }

    fun beginEditorGesture() = beginEditorSession(SLIDER_SESSION)
    fun endEditorGesture() = editorSessions.end(SLIDER_SESSION)

	/**
	 * A slider or pose drag. Its samples are the authored pose every view shows at once, held as one pending
	 * change; release commits that change once, cancel withdraws it.
	 */
	private data class ParameterScrub(
		val generation: Long,
		val workspaceId: String,
		val expectedState: String,
		val pending: PendingPose,
		val overrides: Map<ParameterId, Float> = emptyMap(),
		val autoKey: kotlinx.serialization.json.JsonObject? = null,
		val poseTarget: kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.JsonObject(emptyMap()),
	)

	/**
	 * One authored pose change the panels, canvases and physics already show while its commit waits in
	 * [poseCommits]. A projected commit keeps every later pending change on top, so a slow commit never pulls a
	 * slider back past a newer value.
	 */
	private class PendingPose(val id: Long, val generation: Long, val workspaceId: String,
		@Volatile var values: Map<ParameterId, Float>)

	/** Guarded by [stateLock]; in submission order. */
	private val pendingPoses = ArrayList<PendingPose>()
	private var nextPendingPoseId = 0L
	private var poseCommitsQueued = 0
	/** FIFO: each authored pose commit starts from the state the previous one published. */
	private val poseCommits = kotlinx.coroutines.sync.Mutex()
	/**
	 * States this queue replaced with its own commits. A change captured before an earlier queued commit landed
	 * continues from that commit; any other change since the capture still conflicts. Guarded by [stateLock].
	 */
	private val ownPoseSuccessors = object : LinkedHashMap<String, String>() {
		override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>) = size > 64
	}

	/** [workspaceId]'s pending changes in order; [after] skips those up to the commit being projected. */
	private fun pendingPoseValues(generation: Long, workspaceId: String, after: Long? = null): Map<ParameterId, Float> = synchronized(stateLock) {
		var values = emptyMap<ParameterId, Float>()
		for (pending in pendingPoses) {
			if (pending.generation != generation || pending.workspaceId != workspaceId) continue
			if (after != null && pending.id <= after) continue
			values = values + pending.values
		}
		values
	}

	private fun pendingPoseValues(current: PSD2LiveState) = pendingPoseValues(current.projectOpenGeneration, current.activeWorkspace.id)

	/** Removes [commit]'s change once it is projected and returns what stays shown over the committed pose. */
	private fun consumePendingPose(generation: Long, workspaceId: String, commit: Long?): Map<ParameterId, Float> = synchronized(stateLock) {
		if (commit != null) pendingPoses.removeAll { it.id == commit }
		pendingPoseValues(generation, workspaceId, commit)
	}

	private fun ownPoseLineage(expected: String): String = synchronized(stateLock) {
		var state = expected
		val seen = HashSet<String>()
		while (seen.add(state)) state = ownPoseSuccessors[state] ?: break
		state
	}

	private fun publishPoseCommitBusy() {
		val busy = synchronized(stateLock) { poseCommitsQueued > 0 }
		updateState { if (it.poseCommitBusy == busy) it else it.copy(poseCommitBusy = busy) }
		if (!busy && !_state.value.canvasEditBusy) runQueuedProjectSave()
	}

	/** Registers a change that a gesture fills in sample by sample before it is submitted. */
	private fun openPendingPose(): PendingPose = synchronized(stateLock) {
		val current = _state.value
		PendingPose(++nextPendingPoseId, current.projectOpenGeneration, current.activeWorkspace.id, emptyMap())
			.also { pendingPoses += it }
	}

	/** Shows [values] at once as the authored pose of the active workspace and holds them until their commit lands. */
	private fun showPendingPose(values: Map<ParameterId, Float>): PendingPose = synchronized(stateLock) {
		val current = _state.value
		val pending = PendingPose(++nextPendingPoseId, current.projectOpenGeneration, current.activeWorkspace.id, values)
		if (values.isNotEmpty()) {
			pendingPoses += pending
			updateState { it.copy(animationEnabled = false, parameterValues = it.parameterValues + values)
				.authoringPose(it.activeCanvas.mode == CanvasMode.EDIT) }
		}
		pending
	}

	/**
	 * Commits one authored pose change after every change queued before it. [expected] is the state the gesture
	 * started from; the queue's own earlier commits are followed, anything else since then still conflicts. A
	 * failure puts the panels back on the committed pose instead of leaving a value the project never got.
	 */
	private fun commitPose(pending: PendingPose, expected: String,
		commit: suspend (String) -> kotlinx.serialization.json.JsonObject,
		onCommitted: (kotlinx.serialization.json.JsonObject) -> Unit = {}): kotlinx.coroutines.Deferred<Boolean> {
		synchronized(stateLock) { poseCommitsQueued++ }
		publishPoseCommitBusy()
		return scope.async {
			try {
				poseCommits.withLock {
					check(_state.value.projectOpenGeneration == pending.generation) { "The project changed before its pose was saved" }
					val state = ownPoseLineage(expected)
					val result = withContext(Dispatchers.Default + PendingPoseCommit(pending.id, pending.workspaceId)) { commit(state) }
					val committed = result["state"]?.jsonPrimitive?.content
					synchronized(stateLock) {
						if (committed != null && committed != state) ownPoseSuccessors[state] = committed
						// A commit that changed nothing is never projected; settle its pending values here.
						if (pendingPoses.removeAll { it.id == pending.id }) {
							val values = result["values"]?.jsonObject?.mapNotNull { (id, value) ->
								value.jsonPrimitive.floatOrNull?.let { ParameterId(id) to it } }?.toMap()
							if (values != null) projectWorkspacePose(pending.generation, pending.workspaceId, values, null)
						}
					}
					onCommitted(result)
				}
				true
			} catch (failure: Exception) {
				if (failure is kotlinx.coroutines.CancellationException) throw failure
				restoreCommittedPose(pending, failure.message ?: "Could not save the pose")
				false
			} finally {
				synchronized(stateLock) { poseCommitsQueued-- }
				publishPoseCommitBusy()
			}
		}
	}

	/**
	 * Shows [values] (and [locked], when given) as [workspaceId]'s authored pose with its still-pending changes on
	 * top. Another workspace keeps it as its stored pose until it is focused again.
	 */
	private fun projectWorkspacePose(generation: Long, workspaceId: String, values: Map<ParameterId, Float>,
		locked: Set<ParameterId>?) = synchronized(stateLock) {
		updateState { latest ->
			val shown = values + pendingPoseValues(generation, workspaceId)
			if (latest.projectOpenGeneration != generation) latest
			else if (latest.activeWorkspace.id == workspaceId)
				latest.copy(parameterValues = shown, lockedParameters = locked ?: latest.lockedParameters)
			else latest.updateWorkspace(workspaceId) { workspace ->
				val stored = workspace.pose ?: WorkspacePose.capture(workspace.activeCanvas.presentation)
				workspace.withPose(stored.copy(parameterValues = shown, lockedParameters = locked ?: stored.lockedParameters,
					previewParameterValues = emptyMap()))
			}
		}
	}

	/** Withdraws one change that will never be committed, such as a cancelled snap. */
	private fun discardPendingPose(pending: PendingPose) {
		if (synchronized(stateLock) { pendingPoses.none { it.id == pending.id } }) return
		val committed = runCatching { workspaceBackend?.authoredPose(pending.workspaceId) }.getOrNull()
		synchronized(stateLock) {
			pendingPoses.removeAll { it.id == pending.id }
			if (committed != null) projectWorkspacePose(pending.generation, pending.workspaceId, committed.values, null)
		}
	}

	/**
	 * Drops every pending change of [pending]'s workspace and shows the pose that workspace actually committed. A
	 * project opened since then owns its own pose, so the stale change is dropped without a message.
	 */
	private fun restoreCommittedPose(pending: PendingPose, message: String) {
		val reopened = _state.value.projectOpenGeneration != pending.generation
		val committed = if (reopened) null else runCatching { workspaceBackend?.authoredPose(pending.workspaceId) }.getOrNull()
		synchronized(stateLock) {
			pendingPoses.removeAll { it.generation == pending.generation && it.workspaceId == pending.workspaceId }
			if (reopened) return
			if (committed != null) projectWorkspacePose(pending.generation, pending.workspaceId, committed.values, committed.locked)
			updateState { it.copy(statusText = message) }
		}
	}

	@Volatile private var parameterScrub: ParameterScrub? = null
	private val parameterScrubValues = mutableStateMapOf<ParameterId, Float>()
	var parameterScrubActive by mutableStateOf(false)
		private set

	fun parameterScrubValueOf(id: ParameterId): Float? = parameterScrubValues[id]

	fun beginParameterScrub() {
		beginEditorSession(SLIDER_SESSION)
		val current = _state.value
		if (current.previewModel == null) return
		if (parameterScrub != null) return
		if (processActiveMotion != null) {
			processActiveMotion = null
			configurePlayback("stop_motion")
		}
		stopPlaybackForAuthoring()
		motionEditor.playing = false
		// One state change pauses motion; each sample after it only moves the authored pose.
		val suppressPreviewEffects = current.activeCanvas.mode == CanvasMode.EDIT
		if (current.animationEnabled || current.previewParameterValues.isNotEmpty() ||
			current.activeWorkspace.pose?.authoringPose != suppressPreviewEffects) {
			updateState { it.copy(animationEnabled = false, previewParameterValues = emptyMap())
				.authoringPose(suppressPreviewEffects) }
		}
		val started = _state.value
		val expected = currentWorkspaceState() ?: return
		parameterScrub = ParameterScrub(started.projectOpenGeneration, started.activeWorkspace.id, expected,
			openPendingPose(), autoKey = currentAutoKey())
		parameterScrubActive = true
	}

	fun endParameterScrub() {
		commitParameterScrub()
		editorSessions.end(SLIDER_SESSION)
	}

	fun endIkTargetScrub(id: String, target: io.github.psd2live.core.SkeletonIkTarget?) {
		commitParameterScrub(kotlinx.serialization.json.buildJsonObject { put("ik_target", ikTargetRequest(id, target)) })
		editorSessions.end(SLIDER_SESSION)
	}
	fun cancelParameterScrub() {
		parameterScrub?.let { discardPendingPose(it.pending) }
		parameterScrub = null; parameterScrubValues.clear(); parameterScrubActive = false
		editorSessions.end(SLIDER_SESSION)
	}
	private fun ikTargetRequest(id: String, target: io.github.psd2live.core.SkeletonIkTarget?) = kotlinx.serialization.json.buildJsonObject {
		put("bone_id", id)
		put("point", target?.let { kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive(it.x), kotlinx.serialization.json.JsonPrimitive(it.y))) } ?: kotlinx.serialization.json.JsonNull)
		target?.let { put("enabled", it.enabled) }
	}
	internal fun editIkTarget(id: String, target: io.github.psd2live.core.SkeletonIkTarget?) {
		submitParameterValues(emptyMap(), currentWorkspaceState() ?: return,
			kotlinx.serialization.json.buildJsonObject { put("ik_target", ikTargetRequest(id, target)) })
	}
	internal fun editBoneIk(id: String, settings: io.github.psd2live.core.SkeletonIkSettings) {
		submitParameterValues(emptyMap(), currentWorkspaceState() ?: return,
			kotlinx.serialization.json.buildJsonObject { putJsonObject("bone_ik") { put("bone_id", id); put("settings", settings.toJson()) } })
	}
	internal fun poseGestureTarget(id: String, x: Float, y: Float, ik: Boolean) {
		parameterScrub = parameterScrub?.copy(poseTarget = kotlinx.serialization.json.buildJsonObject {
			put("bone_id", id); put("target", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive(x), kotlinx.serialization.json.JsonPrimitive(y)))); put("ik", ik)
		})
	}

	private fun commitParameterScrub(extras: kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.JsonObject(emptyMap())) {
		val scrub = parameterScrub ?: return
		parameterScrub = null
		val current = _state.value
		if (current.projectOpenGeneration == scrub.generation && current.activeWorkspace.id == scrub.workspaceId && (scrub.overrides.isNotEmpty() || scrub.poseTarget.isNotEmpty() || extras.isNotEmpty())) {
			scrub.pending.values = scrub.overrides
			submitParameterValues(scrub.pending, scrub.expectedState, kotlinx.serialization.json.JsonObject(scrub.poseTarget + extras), scrub.autoKey)
		} else {
			discardPendingPose(scrub.pending)
		}
		parameterScrubValues.clear()
		parameterScrubActive = false
	}

    /** Brackets one text/number field's editing session; [token] has to match the paired `end`. */
    fun beginEditorField(token: String) = beginEditorSession(token)

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
    fun flushEditorFields() {
		warpControlFields.keys.toList().forEach(::endWarpControlField)
		commitParameterScrub()
		editorSessions.flush()
	}

    private fun editorChanged(summary: String? = null) {
        if (editorSessions.anyOpen) { markWorkspaceChanged(); return }
        commitEditorChange(summary)
    }

    private fun commitEditorChange(summary: String? = null): kotlinx.coroutines.Deferred<WorkspaceMutationResult>? {
        val expected = editorDraftExpected ?: workspaceBackend?.snapshot()
        editorDraftExpected = null
        val intents = synchronized(stateLock) { editorSettingsIntents.toList().also { editorSettingsIntents.clear() } }
        val workspace: io.github.psd2live.application.WorkspaceEditorDraftPort = workspaceBackend ?: return null
        if (expected?.loaded != true || expected.projectId == null) return null
        val (current, document) = synchronized(stateLock) {
            val captured = _state.value
            if (captured.analysis == null) return null
            val draft = WorkspaceStateCodec.document(captured)
            // A switch turned off and back on leaves the document as it was but still released poses.
            if (intents.isEmpty() && !captured.editorDraftBusy &&
                io.github.psd2live.project.WorkspaceRevisions.of(draft) == expected.revisionId) return null
            // The draft's commit checks the state still holds this document and installs its own model. A local
            // preview rebuild in flight would write rigEdits/atlasSize in between, so it is superseded here.
            previewRebuildToken++
            previewRebuildJob?.cancel()
            captured to draft
        }
        markWorkspaceChanged()
        val result = workspace.submitEditorDraft(expected.projectId, expected.state, document, intents,
            summary ?: "Workspace changed in the editor", MutationAuthor.USER)
        synchronized(stateLock) {
            editorDraftCount++
            updateState { it.copy(editorDraftBusy = true) }
        }
        scope.launch {
            try { result.await() }
            catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                updateState { state ->
                    if (state.projectId != current.projectId || state.projectOpenGeneration != current.projectOpenGeneration) state
                    else state.copy(errorMessage = failure.message ?: "Could not save editor changes")
                }
            } finally {
                synchronized(stateLock) {
                    editorDraftCount--
                    updateState { it.copy(editorDraftBusy = editorDraftCount > 0) }
                }
            }
        }
        return result
    }
    internal fun installSavedProjectData(expected: PSD2LiveState, before: io.github.psd2live.project.WorkspaceAuxiliaryData,
                                          next: io.github.psd2live.project.WorkspaceAuxiliaryData) = synchronized(stateLock) {
        val current = _state.value
        check(current.projectId == expected.projectId && current.projectOpenGeneration == expected.projectOpenGeneration &&
            current.parameterSnapshots == before.parameterSnapshots && current.historyAnnotations == before.historyAnnotations) {
            "Saved project data changed while the operation was being prepared"
        }
        updateState { it.copy(parameterSnapshots = next.parameterSnapshots, historyAnnotations = next.historyAnnotations,
            projectDirty = it.projectDirty || it.analysis != null, projectEditVersion = it.projectEditVersion + 1,
            projectAuxiliaryVersion = it.projectAuxiliaryVersion + 1) }
    }

    private fun editSavedProjectData(edit: io.github.psd2live.application.WorkspaceAuxiliaryEdit) {
        val workspace = workspaceBackend as? io.github.psd2live.application.WorkspaceAuxiliaryPort
        if (workspace != null) {
            workspace.editSavedProjectData(workspace.savedProjectData().state, edit)
            return
        }
        updateState { current ->
            val before = io.github.psd2live.project.WorkspaceAuxiliaryData(current.parameterSnapshots, current.historyAnnotations)
            val next = io.github.psd2live.application.WorkspaceAuxiliaryEdits.apply(before, edit,
                current.previewModel?.rig?.puppet?.parameters.orEmpty(), current.historySnapshot?.nodes.orEmpty().mapTo(HashSet()) { it.id })
            if (next == before) current else current.copy(parameterSnapshots = next.parameterSnapshots,
                historyAnnotations = next.historyAnnotations, projectDirty = current.projectDirty || current.analysis != null,
                projectEditVersion = current.projectEditVersion + 1, projectAuxiliaryVersion = current.projectAuxiliaryVersion + 1)
        }
    }

    fun editHistoryAnnotation(id: String, title: String, note: String, hidden: Boolean) {
        editSavedProjectData(io.github.psd2live.application.WorkspaceAuxiliaryEdit.PutAnnotation(id, HistoryAnnotation(title, note, hidden)))
    }
    fun undoHistory() {
        if (_state.value.workspaceEditBusy) return
        val history = _state.value.historySnapshot ?: return
        history.nodes.firstOrNull { it.id == history.headNodeId }?.parentId?.let(::checkoutHistoryNode)
    }
    fun redoHistory() {
        if (_state.value.workspaceEditBusy) return
        val history = _state.value.historySnapshot ?: return
        val children = history.nodes.filter { it.parentId == history.headNodeId }
        if (children.size == 1) checkoutHistoryNode(children.single().id)
        else showHistoryModule()
    }
    fun setHistoryView(zoom: Float, x: Float, y: Float, search: String, showHidden: Boolean) {
        updateState { if (it.historyZoom == zoom && it.historyPanX == x && it.historyPanY == y && it.historySearch == search && it.historyShowHidden == showHidden) it
            else it.copy(historyZoom = zoom, historyPanX = x, historyPanY = y, historySearch = search, historyShowHidden = showHidden, projectDirty = it.analysis != null, projectEditVersion = it.projectEditVersion + 1) }
    }
    fun setHierarchyView(width: Float = _state.value.hierarchyWidth, collapsed: Boolean = _state.value.hierarchyCollapsed, search: String = _state.value.hierarchySearch) {
        val clampedWidth = width.coerceIn(100f, 600f)
        updateState { current ->
            val hidden = current.activeWorkspace.hiddenModules.toMutableSet().apply {
                // The skeleton tab shares the hierarchy's dock, so the two collapse together.
                if (collapsed) { add("hierarchy"); add("skeleton") } else { remove("hierarchy"); remove("skeleton") }
            }
            val layoutChanged = current.hierarchyWidth != clampedWidth || current.activeWorkspace.hiddenModules != hidden
            val searchChanged = current.hierarchySearch != search
            if (!layoutChanged && !searchChanged) current
            else current.copy(
                hierarchyWidth = clampedWidth,
                hierarchySearch = search,
                // Search is a transient filter — do not dirty the project or bump edit version.
                projectDirty = if (layoutChanged && current.analysis != null) true else current.projectDirty,
                projectEditVersion = if (layoutChanged) current.projectEditVersion + 1 else current.projectEditVersion,
            ).updateActiveWorkspace { it.copy(hiddenModules = hidden) }
        }
    }

    fun setHierarchySearch(search: String) {
        updateState {
            if (it.hierarchySearch == search) it
            else it.copy(hierarchySearch = search)
        }
    }
    fun setDrawOrderRulerWidth(width: Float, min: Float = 14f, max: Float = 100f) {
        val clamped = width.coerceIn(min, max)
        updateState {
            if (it.drawOrderRulerWidth == clamped) it
            else it.copy(drawOrderRulerWidth = clamped, projectDirty = it.analysis != null, projectEditVersion = it.projectEditVersion + 1)
        }
    }

    fun setInspectorCollapsed(collapsed: Boolean) {
        updateState { current ->
            val hidden = current.activeWorkspace.hiddenModules.toMutableSet().apply {
                if (collapsed) addAll(INSPECTOR_DOCK_MODULES) else removeAll(INSPECTOR_DOCK_MODULES)
            }
            if (current.activeWorkspace.hiddenModules == hidden) current
            else current.updateActiveWorkspace { it.copy(hiddenModules = hidden) }
                .copy(projectDirty = current.analysis != null, projectEditVersion = current.projectEditVersion + 1)
        }
    }

	fun requestSelectDockModule(moduleId: String) {
		updateState { it.copy(requestedDockModule = moduleId) }
	}

	fun clearDockModuleRequest() {
		updateState {
			if (it.requestedDockModule == null) it else it.copy(requestedDockModule = null)
		}
	}
    /**
     * Cameras a canvas holds locally and has not written yet (a wheel zoom waiting for the wheel to settle,
     * a pan still in flight), by [pendingCameraKey]. A mode or workspace switch folds them into the same
     * update, so the view neither jumps back nor loses the zoom when the viewport goes away first.
     */
    private val pendingCanvasCameras = java.util.concurrent.ConcurrentHashMap<String, TabCamera>()

    private fun pendingCameraKey(generation: Long, workspaceId: String, canvasId: String) = "$generation/$workspaceId/$canvasId"

    /** The camera [canvasId] shows but has not yet written with [setCanvasView]. */
    internal fun notePendingCanvasCamera(generation: Long, workspaceId: String, canvasId: String, camera: TabCamera) {
        pendingCanvasCameras[pendingCameraKey(generation, workspaceId, canvasId)] = camera
    }

    /** Takes [workspaceId]'s unwritten cameras out of the pending map; [withPendingCameras] applies them. */
    private fun takePendingCameras(state: PSD2LiveState, workspaceId: String): Map<String, TabCamera> {
        val prefix = "${state.projectOpenGeneration}/$workspaceId/"
        val taken = mutableMapOf<String, TabCamera>()
        val iterator = pendingCanvasCameras.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key.startsWith(prefix)) {
                taken[entry.key.removePrefix(prefix)] = entry.value
                iterator.remove()
            } else if (!entry.key.startsWith("${state.projectOpenGeneration}/")) iterator.remove()
        }
        return taken
    }

    private fun PSD2LiveState.withPendingCameras(workspaceId: String, cameras: Map<String, TabCamera>): PSD2LiveState =
        if (cameras.isEmpty()) this else updateWorkspace(workspaceId) { workspace ->
            workspace.copy(canvases = workspace.canvases.map { canvas ->
                val camera = cameras[canvas.id]
                if (camera == null) canvas else canvas.updateSession { it.copy(camera = camera) }
            })
        }

    fun setCanvasView(
        zoom: Float, x: Float, y: Float,
        canvasId: String = _state.value.activeCanvas.id,
        mode: CanvasMode? = null,
    ) {
        val snapshot = _state.value
        pendingCanvasCameras.remove(pendingCameraKey(snapshot.projectOpenGeneration, snapshot.activeWorkspace.id, canvasId))
        val camera = TabCamera(zoom, x, y)
        // Writing back the camera the canvas already has must not dirty the project or wake every panel.
        val unchanged = snapshot.activeWorkspace.canvases.firstOrNull { it.id == canvasId }
            ?.let { it.session(mode ?: it.mode).camera == camera } == true
        if (unchanged) return
        updateState { current ->
            if (current.activeWorkspace.canvases.none { it.id == canvasId }) current
            else current.updateActiveWorkspace { workspace ->
                workspace.copy(
                    canvases = workspace.canvases.map { canvas ->
                        if (canvas.id == canvasId) canvas.updateSession(mode ?: canvas.mode) {
                            it.copy(camera = camera)
                        } else canvas
                    },
                )
            }.copy(projectDirty = current.analysis != null, projectEditVersion = current.projectEditVersion + 1)
        }
    }

	fun attachWorkspace(workspace: WorkspaceBackend) {
		workspaceBackend = workspace
        (workspace as? DesktopWorkspace)?.attachCurrentWorkspace()
		runCatching {
			val snapshot = workspace.history()
			updateState { it.copy(historySnapshot = snapshot, projectDirty = it.projectDirty || (it.historySnapshot != null && it.historySnapshot.headNodeId != snapshot.headNodeId), projectEditVersion = it.projectEditVersion + if (it.historySnapshot?.headNodeId != snapshot.headNodeId) 1 else 0) }
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
	private val _uiState = mutableStateOf(_state.value)
	/** Same document [state] publishes, readable as Compose snapshot state so one frame cannot mix two copies. */
	val uiState: State<PSD2LiveState> get() = _uiState
	private val _sdkFrame = MutableStateFlow<CubismSdkFrame?>(null)
	val sdkFrame: StateFlow<CubismSdkFrame?> = _sdkFrame.asStateFlow()
    private val canvasFrames = mutableMapOf<String, MutableStateFlow<CubismSdkFrame?>>()
    private val canvasFrameUsers = mutableMapOf<String, Int>()
    fun canvasRenderKey(canvasId: String, mode: CanvasMode = state.value.activeWorkspace.canvases
        .firstOrNull { it.id == canvasId }?.mode ?: CanvasMode.EDIT): String =
        "${state.value.projectOpenGeneration}/${state.value.activeWorkspace.id}/$canvasId/${mode.name}"
    fun sdkFrameFor(renderKey: String): StateFlow<CubismSdkFrame?> =
        canvasFrames.getOrPut(renderKey) { MutableStateFlow(null) }
    internal fun retainCanvasFrame(renderKey: String): StateFlow<CubismSdkFrame?> {
        val flow = sdkFrameFor(renderKey)
        canvasFrameUsers[renderKey] = (canvasFrameUsers[renderKey] ?: 0) + 1
        return flow
    }
    internal fun releaseRetainedCanvasFrame(renderKey: String) {
        val users = canvasFrameUsers[renderKey] ?: return
        if (users > 1) canvasFrameUsers[renderKey] = users - 1
        else {
            canvasFrameUsers.remove(renderKey)
            releaseCanvasFrame(renderKey)
        }
    }
    fun releaseCanvasFrame(renderKey: String) {
        clearPointer(renderKey)
        canvasFrames.remove(renderKey)
        sdkSession.removeView(renderKey)
    }

	private var previewRebuildJob: Job? = null
	/** Changed under [stateLock] when a rebuild is scheduled or an editor draft takes over the preview. */
	private var previewRebuildToken = 0L
	private val previewMeshSettingsOverrides = mutableMapOf<String, MeshSettings>()
	private var previewMeshSettingsBaseline: RigPreviewModel? = null
	private var motionJob: Job? = null
	private var processActiveMotion: String? by mutableStateOf(null)
	private var processFrameValues: Map<ParameterId, Float> = emptyMap()
	private var processPlaybackActive = false
	/** Shared by the animation panel and the animation editor. Initialized before [startMotionLoop]. */
	internal val motionEditor = MotionEditorState()
	private var activeWorkJob: Job? = null

	private val canvasPointers = mutableMapOf<String, Pair<Float, Float>>()
	private var pointerOwner: String? = null
	private var pointerActive = false
	private var pointerX = 0f
	private var pointerY = 0f
	private var lastTick = System.nanoTime()
	private var lastSdkParameterPublishNanos = 0L
	private var lastSdkParameterCanvasId: String? = null
	private var sdkSessionNeedsReload = false

	// Only a mounted canvas renders through Cubism, and its first frame request loads the session. Without one
	// (an agent or test driving the model) a load would only post its status to the UI thread later, where it
	// would demote the frames accepted meanwhile and hand the live pose back to the software tick.
	private val sdkSessionWanted: Boolean get() = canvasFrameUsers.isNotEmpty()

	private fun refreshSdkSession(preview: RigPreviewModel) {
		if (_state.value.previewLive && sdkSessionWanted) {
			sdkSession.load(preview.runtimeBundle, preview.rig.puppet.parameters.map { it.id })
			sdkSessionNeedsReload = false
		} else {
			sdkSessionNeedsReload = true
		}
	}

	private fun ensureSdkSessionLoaded() {
		if (sdkSessionNeedsReload && sdkSessionWanted) {
			val preview = _state.value.previewModel
			if (preview != null) {
				sdkSessionNeedsReload = false
				sdkSession.load(preview.runtimeBundle, preview.rig.puppet.parameters.map { it.id })
			}
		}
	}

	internal fun acceptSdkFrame(frame: CubismSdkFrame, nowNanos: Long = System.nanoTime()) {
		val current = state.value
		val canvas = current.activeWorkspace.canvases.firstOrNull {
			it.mode == CanvasMode.PREVIEW &&
			"${current.projectOpenGeneration}/${current.activeWorkspace.id}/${it.id}/PREVIEW" == frame.viewId
		}
		val frameFlow = canvasFrames[frame.viewId]
		if (frame.viewId.isNotEmpty() && (canvas == null || frameFlow == null)) return
		val animationEnabled = canvas?.presentation?.animationEnabled ?: current.animationEnabled
		if (!current.previewLive || frame.animationEnabled != (animationEnabled && !current.meshOnly)) return

		// The image and information overlays follow their own view's frame stream. Publishing every
		// frame through the document state makes every dock and every other canvas recompose.
		frameFlow?.value = frame
		if (canvas == null || canvas.id == current.activeCanvas.id) {
			_sdkFrame.value = frame
		}
		// The pose and the ready status publish together, so a software tick that checks the status under the
		// same lock either clears the pose before this frame sets it or sees the frame and leaves it.
		synchronized(stateLock) {
			if (canvas == null || canvas.id == current.previewControlCanvas().id) publishLivePose(current, frame)
			val activeAnimatedCanvas = canvas != null && canvas.id == current.previewControlCanvas().id &&
				animationEnabled && !current.meshOnly
			val publishParameters = activeAnimatedCanvas &&
				(lastSdkParameterCanvasId != frame.viewId || nowNanos - lastSdkParameterPublishNanos >= SDK_PARAMETER_PUBLISH_INTERVAL_NANOS)
			if (publishParameters) {
				lastSdkParameterCanvasId = frame.viewId
				lastSdkParameterPublishNanos = nowNanos
			}
			if (publishParameters || current.sdkStatus != "ready") {
				updateState { latest ->
					if (canvas == null || latest.activeWorkspace.id != current.activeWorkspace.id ||
						latest.previewControlCanvas().id != canvas.id || !previewFrameMatchesState(latest, frame.animationEnabled)) {
						if (latest.sdkStatus == "ready") latest else latest.copy(sdkStatus = "ready")
					} else {
						val values = if (publishParameters) parameterValuesAfterPreviewFrame(latest, frame.parameters)
							else latest.previewParameterValues
						if (latest.sdkStatus == "ready" && values == latest.previewParameterValues) latest
						else latest.copy(sdkStatus = "ready", previewParameterValues = values)
					}
				}
			}
		}
	}

	/** The frame's pose for the panels: all of it while animating, the pointer's look while paused. */
	private fun publishLivePose(current: PSD2LiveState, frame: CubismSdkFrame) {
		val panel = current.previewPanelState()
		val tracked = canvasPointers[frame.viewId] != null && panel.mouseTrackingEnabled && !current.meshOnly && current.activeWorkspace.pose?.authoringPose != true
		val swinging = pausedPhysics
		setLivePose(when {
			frame.animationEnabled -> panel.parameterValues + frame.parameters
			tracked || swinging.isNotEmpty() -> frame.parameters.filterKeys {
				it !in panel.lockedParameters && ((tracked && it in POINTER_POSE_PARAMETERS) || it in swinging)
			}
			else -> emptyMap()
		})
	}

	private val sdkSession = CubismSdkPreviewSession(
        onFrame = { frame -> acceptSdkFrame(frame) },
		onStatus = { status ->
			if (status != "ready") { _sdkFrame.value = null; canvasFrames.values.forEach { it.value = null } }
			updateState { it.copy(sdkStatus = status) }
		},
	)

	fun setInputPath(path: String) {
		val normalized = path.trim()
		if (classifyRecentPath(normalized) == RecentFileKind.PSD) {
			AppSettings.rememberRecentFile(normalized)
		}
		updateState { current ->
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
			current.copy(inputPath = normalized, outputPath = nextOutput, recentFiles = AppSettings.recentFiles())
		}
	}

	fun openRecentFile(path: String) {
		if (_state.value.isBusy) return
		val target = runCatching { Path.of(path).toAbsolutePath().normalize() }.getOrNull()
		if (target == null || !Files.isRegularFile(target)) {
			AppSettings.forgetRecentFile(path)
			updateState {
				it.copy(recentFiles = AppSettings.recentFiles(), errorMessage = tr("canvas.start.missing", path))
			}
			return
		}
		when (classifyRecentPath(target.toString())) {
			RecentFileKind.PROJECT -> openProject(target)
			RecentFileKind.PSD -> withSavedChanges {
				setInputPath(target.toString())
				analyze()
			}
			null -> {
				AppSettings.forgetRecentFile(path)
				updateState { it.copy(recentFiles = AppSettings.recentFiles()) }
			}
		}
	}

	fun setOutputPath(path: String) {
		val trimmed = path.trim()
		if (trimmed.isNotBlank()) {
			lastExportDirectory = trimmed
		}
		updateState { it.copy(outputPath = trimmed) }
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
		updateState { it.copy(textureUpscale = config, atlasSize = newAtlasSize) }
		schedulePreviewRebuild()
		editorChanged()
	}

	fun setAtlasSize(size: Int) {
		val minRequired = _state.value.minRequiredAtlasSize()
		val validSize = maxOf(size, minRequired)
		updateState { it.copy(atlasSize = validSize) }
		schedulePreviewRebuild()
		editorChanged()
	}

	/** Applies the global mesh defaults in one rebuild (panel “no selection” mode). */
	fun setGlobalMeshSettings(settings: MeshSettings) {
        workspaceBackend?.takeUnless { editorSessions.anyOpen }?.let { workspace ->
            val port: io.github.psd2live.application.WorkspaceSettingsPort = workspace
            runWorkspaceCommand { state -> port.updateProjectSettings(state, kotlinx.serialization.json.buildJsonObject {
                put("meshOuterMargin", settings.outerMargin.coerceIn(0f, 32f)); put("meshEdgeMode", settings.edgeMode.name)
                put("meshEdgeWidth", settings.edgeWidth.coerceIn(0.5f, 32f)); put("meshMaxEdgeDistance", settings.maxEdgeDistance.coerceIn(6f, 128f))
                put("meshSpacing", settings.maxEdgeDistance.toInt().coerceIn(16, 128)); put("meshInteriorDensity", settings.interiorDensity.coerceIn(6f, 128f))
                put("meshFillAlgorithm", settings.fillAlgorithm.name); put("meshSuppressBoundaryDiagonals", settings.suppressBoundaryDiagonals)
                put("meshFillParameters", io.github.psd2live.project.WorkspaceSettingsCodec.encodeFillParameters(settings.fillParameters))
            }) }
            return
        }
		updateState {
			it.copy(
				meshOuterMargin = settings.outerMargin.coerceIn(0f, 32f),
				meshEdgeMode = settings.edgeMode,
				meshEdgeWidth = settings.edgeWidth.coerceIn(0.5f, 32f),
				meshMaxEdgeDistance = settings.maxEdgeDistance.coerceIn(6f, 128f),
				meshSpacing = settings.maxEdgeDistance.toInt().coerceIn(16, 128),
				meshInteriorDensity = settings.interiorDensity.coerceIn(6f, 128f),
				meshFillAlgorithm = settings.fillAlgorithm,
				meshSuppressBoundaryDiagonals = settings.suppressBoundaryDiagonals,
				meshFillParameters = settings.fillParameters,
			)
		}
		schedulePreviewRebuild()
		editorChanged()
	}

	/** Switches what every mesh length is measured in; the meshes are rebuilt like any global mesh change. */
	fun setMeshUnits(units: io.github.psd2live.core.MeshUnits) {
		if (state.value.meshUnits == units) return
        workspaceBackend?.takeUnless { editorSessions.anyOpen }?.let { workspace ->
            val port: io.github.psd2live.application.WorkspaceSettingsPort = workspace
            runWorkspaceCommand { state -> port.updateProjectSettings(state,
                kotlinx.serialization.json.buildJsonObject { put("meshUnits", units.name) }) }
            return
        }
		updateState { it.copy(meshUnits = units) }
		schedulePreviewRebuild()
		editorChanged()
	}

	fun setPartMeshSettings(layerId: String, settings: MeshSettings) {
		clearMeshSettingsPreviewState(layerId)
        workspaceBackend?.let { workspace ->
            val port: io.github.psd2live.application.WorkspaceSettingsPort = workspace
            runWorkspaceCommand { state -> port.setLayerMeshSettings(state, layerId, meshSettingFields(settings), false) }
            return
        }
		updateState { it.copy(meshOverrides = it.meshOverrides + (layerId to settings)) }
		schedulePreviewRebuild()
	    editorChanged()
	}

	fun resetPartMeshSettings(layerId: String) {
		clearMeshSettingsPreviewState(layerId)
        workspaceBackend?.let { workspace ->
            val port: io.github.psd2live.application.WorkspaceSettingsPort = workspace
            runWorkspaceCommand(after = { offerMeshSplit(listOf(layerId)) }) { state -> port.setLayerMeshSettings(state, layerId, null, true) }
            return
        }
		updateState { it.copy(meshOverrides = it.meshOverrides - layerId) }
		schedulePreviewRebuild()
	    editorChanged()
		scope.launch { previewRebuildJob?.join(); offerMeshSplit(listOf(layerId)) }
	}

	fun previewPartMeshSettings(layerId: String, settings: MeshSettings) {
		val changed = synchronized(stateLock) {
			if (previewMeshSettingsOverrides[layerId] == settings) false else {
				if (previewMeshSettingsOverrides.isEmpty()) previewMeshSettingsBaseline = _state.value.previewModel
				previewMeshSettingsOverrides[layerId] = settings
				true
			}
		}
		if (changed) schedulePreviewRebuild()
	}

	fun cancelPartMeshSettingsPreview(layerId: String) {
		var removed = false
		val baseline = synchronized(stateLock) {
			if (previewMeshSettingsOverrides.remove(layerId) != null) removed = true
			if (previewMeshSettingsOverrides.isEmpty()) {
				previewMeshSettingsBaseline.also { previewMeshSettingsBaseline = null }
			} else null
		}
		if (!removed) return
		if (baseline != null) {
			previewRebuildJob?.cancel()
			updateState {
				it.copy(
					previewModel = baseline,
					analysis = baseline.analysis,
					rigEdits = baseline.config.rigEdits,
					isUpscaling = false,
					progress = 1f,
					statusText = tr("status.layerChangesApplied"),
					errorMessage = null,
				)
			}
			refreshSdkSession(baseline)
		} else {
			schedulePreviewRebuild()
		}
	}

	fun confirmPartMeshSettingsPreview(layerId: String, settings: MeshSettings) {
        workspaceBackend?.let { workspace ->
            cancelPartMeshSettingsPreview(layerId)
            val port: io.github.psd2live.application.WorkspaceSettingsPort = workspace
            runWorkspaceCommand(after = { offerMeshSplit(listOf(layerId)) }) { state ->
                port.setLayerMeshSettings(state, layerId, meshSettingFields(settings), false)
            }
            return
        }
		val currentPreview = _state.value.previewModel
		val previewIsReady = synchronized(stateLock) {
			val ready = previewMeshSettingsOverrides[layerId] == settings &&
				currentPreview?.config?.meshOverrides?.get(layerId) == settings
			previewMeshSettingsOverrides.remove(layerId)
			if (previewMeshSettingsOverrides.isEmpty()) previewMeshSettingsBaseline = null
			ready
		}
		if (previewIsReady && currentPreview != null) {
			updateState {
				it.copy(
					meshOverrides = it.meshOverrides + (layerId to settings),
					rigEdits = currentPreview.config.rigEdits,
				)
			}
			editorChanged()
			offerMeshSplit(listOf(layerId))
		} else {
			setPartMeshSettings(layerId, settings)
			scope.launch { previewRebuildJob?.join(); offerMeshSplit(listOf(layerId)) }
		}
	}

	private fun clearMeshSettingsPreviewState(layerId: String) {
		synchronized(stateLock) {
			previewMeshSettingsOverrides.remove(layerId)
			if (previewMeshSettingsOverrides.isEmpty()) previewMeshSettingsBaseline = null
		}
	}

	fun setHeadStrength(strength: Float) {
		updateState { it.copy(headStrength = strength.coerceIn(0f, 4f)) }
		schedulePreviewRebuild()
	    editorChanged()
	}

	fun setBodyStrength(strength: Float) {
		updateState { it.copy(bodyStrength = strength.coerceIn(0f, 4f)) }
		schedulePreviewRebuild()
	    editorChanged()
	}

	/** One of the rig values ([io.github.psd2live.core.RigTuning.fields]), clamped to its range. */
	fun setRigTuning(id: String, value: Float) {
		updateState { it.copy(rigTuning = it.rigTuning.with(id, value)) }
		schedulePreviewRebuild()
	    editorChanged()
	}

	/** Every rig value back to its default. */
	fun resetRigTuning() {
		updateState { it.copy(rigTuning = io.github.psd2live.core.RigTuning()) }
		schedulePreviewRebuild()
	    editorChanged()
	}

	fun setTexturePadding(padding: Int) {
		updateState { it.copy(texturePadding = padding.coerceIn(0, 32)) }
		schedulePreviewRebuild()
	    editorChanged()
	}

	fun setAlphaThreshold(threshold: Int) {
		updateState { it.copy(alphaThreshold = threshold.coerceIn(0, 255)) }
		schedulePreviewRebuild()
	    editorChanged()
	}

    fun setMouthOutlineEnabled(enabled: Boolean) {
        updateState { it.copy(mouthOutlineEnabled = enabled) }
        schedulePreviewRebuild()
        editorChanged()
    }

    fun setMouthShapeCurve(shape: String, curve: io.github.psd2live.core.MouthCurve) {
        require(shape in io.github.psd2live.core.MouthCurve.presets + "custom")
        updateState { it.copy(mouthShape = shape, mouthCurve = curve) }
        schedulePreviewRebuild()
        editorChanged()
    }

    fun setMouthThickness(thickness: Float) {
        val clamped = thickness.coerceIn(0.5f, 8f)
        updateState { it.copy(mouthThickness = clamped) }
        schedulePreviewRebuild()
        editorChanged()
    }

    fun setMouthColor(color: Int?) {
        require(color == null || color in 0..0xFFFFFF)
        updateState { it.copy(mouthColor = color) }
        schedulePreviewRebuild()
        editorChanged()
    }

	/**
	 * Settings switches that link other settings or release authored poses go through the application's settings
	 * intent, so the settings, the released poses of every workspace and the rebuilt model publish in one commit.
	 * An open field session keeps the local draft for display and records the switch, which the draft replays
	 * through the same intent when the session closes.
	 */
	private fun submitSettingsIntent(changes: kotlinx.serialization.json.JsonObject, after: suspend () -> Unit = {}): Boolean {
		if (workspaceBackend != null && editorSessions.anyOpen) {
			synchronized(stateLock) { editorSettingsIntents.add(changes) }
			return false
		}
		val workspace = workspaceBackend ?: return false
		val port: io.github.psd2live.application.WorkspaceSettingsPort = workspace
		runWorkspaceCommand(after) { state -> port.updateProjectSettings(state, changes) }
		return true
	}

	/** Awaited form of [submitSettingsIntent] for a sequence that must see the commit; false when not applicable. */
	private suspend fun applySettingsIntentNow(changes: kotlinx.serialization.json.JsonObject): Boolean {
		val workspace = workspaceBackend?.takeUnless { editorSessions.anyOpen || _state.value.workspaceEditBusy } ?: return false
		val port: io.github.psd2live.application.WorkspaceSettingsPort = workspace
		val expected = workspace.snapshot()
		val projectId = expected.projectId ?: return false
		val settled = workspace.settleEditorDrafts(projectId, expected.state)
		withContext(Dispatchers.Default + io.github.psd2live.application.WorkspaceExecution(projectId, settled, MutationAuthor.USER)) {
			port.updateProjectSettings(settled, changes)
		}
		return true
	}

	fun setMeshOnly(enabled: Boolean) {
		if (submitSettingsIntent(buildJsonObject { put("meshOnly", enabled) }) {
				if (enabled) { resetPreviewPhysics(); stopProcessMotion() }
			}) return
		updateState { current ->
			val updated = current.copy(meshOnly = enabled, generateDeformers = !enabled)
			if (enabled) {
				val defaults = current.previewModel?.rig?.puppet?.parameters?.associate { it.id to it.default } ?: emptyMap()
				val resetMap = defaults.filterKeys { it !in current.lockedParameters }
				updated.copy(parameterValues = current.parameterValues + resetMap)
			} else updated
		}
		if (enabled) {
			resetPreviewPhysics()
			stopProcessMotion()
		}
		schedulePreviewRebuild()
	    editorChanged()
	}

	fun setFeatureDisplacementEnabled(enabled: Boolean) {
		updateState { it.copy(featureDisplacementEnabled = enabled) }
		schedulePreviewRebuild()
		editorChanged()
	}

	/**
	 * The model presets' basic motions switch: off, idle, blink, nod and shake leave the animation panel, the
	 * editor, the preview and the export together, each keeping its own switch and settings for when it is back.
	 */
	fun setMotionBasic(enabled: Boolean) {
		if (submitSettingsIntent(buildJsonObject { put("motionBasic", enabled) }) {
				if (!enabled) closePresetGroupMotion(skeleton = false)
				scheduleRuntimeBundleUpdate()
			}) return
		updateState { current ->
			if (enabled) return@updateState current.copy(motionBasic = true)
			val rest = mapOf(
				StandardParameters.ANGLE_X to 0f,
				StandardParameters.ANGLE_Y to 0f,
				StandardParameters.ANGLE_Z to 0f,
				StandardParameters.BODY_X to 0f,
				StandardParameters.BODY_Y to 0f,
				StandardParameters.BODY_Z to 0f,
				StandardParameters.BREATH to 0f,
				StandardParameters.MOUTH_OPEN to 0f,
				StandardParameters.MOUTH_FORM to 0f,
				StandardParameters.EYE_L_OPEN to 1f,
				StandardParameters.EYE_R_OPEN to 1f,
			).filterKeys { key -> key !in current.lockedParameters }
			current.copy(motionBasic = false, parameterValues = current.parameterValues + rest)
		}
		if (!enabled) {
			closePresetGroupMotion(skeleton = false)
		}
		scheduleRuntimeBundleUpdate()
		editorChanged()
	}

	/** Closes the editor on, and stops, a generated motion of the group just switched off. */
	private fun closePresetGroupMotion(skeleton: Boolean) {
		val names = MotionClips.BUILTIN_NAMES.filter { MotionClips.isSkeletonPreset(it) == skeleton }
		if (names.any { motionEditor.clipId == MotionEditorState.presetClipId(it) }) closeMotionEditorClip()
		if (names.any { it.equals(processActiveMotion, ignoreCase = true) }) stopProcessMotion()
	}

	fun setMotionIdle(enabled: Boolean) {
		if (submitSettingsIntent(buildJsonObject { put("motionIdle", enabled) }) { scheduleRuntimeBundleUpdate() }) return
		updateState { current ->
			val next = current.copy(motionIdle = enabled)
			val updated = next.copy(exportMotions = next.motionIdle || next.motionBlink || next.motionNod || next.motionShake || next.motionSkeleton)
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
		}
		scheduleRuntimeBundleUpdate()
	    editorChanged()
	}

	fun setMotionBlink(enabled: Boolean) {
		if (submitSettingsIntent(buildJsonObject { put("motionBlink", enabled) }) { scheduleRuntimeBundleUpdate() }) return
		updateState { current ->
			val next = current.copy(motionBlink = enabled)
			val updated = next.copy(exportMotions = next.motionIdle || next.motionBlink || next.motionNod || next.motionShake || next.motionSkeleton)
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
		// Nod and Shake only play transient frames over the authored pose; stopping them leaves that pose alone.
		if (!enabled) stopProcessMotion("nod")
		if (submitSettingsIntent(buildJsonObject { put("motionNod", enabled) }) {
				scheduleRuntimeBundleUpdate()
				if (enabled) triggerMotion("Nod")
			}) return
		updateState { current ->
			val next = current.copy(motionNod = enabled)
			next.copy(exportMotions = next.motionIdle || next.motionBlink || next.motionNod || next.motionShake || next.motionSkeleton)
		}
		scheduleRuntimeBundleUpdate()
		if (enabled) triggerMotion("Nod")
	    editorChanged()
	}

	fun setMotionShake(enabled: Boolean) {
		if (!enabled) stopProcessMotion("shake")
		if (submitSettingsIntent(buildJsonObject { put("motionShake", enabled) }) {
				scheduleRuntimeBundleUpdate()
				if (enabled) triggerMotion("Shake")
			}) return
		updateState { current ->
			val next = current.copy(motionShake = enabled)
			next.copy(exportMotions = next.motionIdle || next.motionBlink || next.motionNod || next.motionShake || next.motionSkeleton)
		}
		scheduleRuntimeBundleUpdate()
		if (enabled) triggerMotion("Shake")
	    editorChanged()
	}

	fun setMotionSkeleton(enabled: Boolean) {
		if (submitSettingsIntent(buildJsonObject { put("motionSkeleton", enabled) }) {
				if (!enabled) closePresetGroupMotion(skeleton = true)
				scheduleRuntimeBundleUpdate()
			}) return
		updateState { current ->
			val next = current.copy(motionSkeleton = enabled)
			next.copy(exportMotions = next.motionIdle || next.motionBlink || next.motionNod || next.motionShake || next.motionSkeleton)
		}
		if (!enabled) closePresetGroupMotion(skeleton = true)
		scheduleRuntimeBundleUpdate()
		editorChanged()
	}

	fun setGeneratePhysics(enabled: Boolean) {
		if (submitSettingsIntent(buildJsonObject { put("generatePhysics", enabled) }) {
				if (!enabled) resetPreviewPhysics()
				scheduleRuntimeBundleUpdate()
			}) return
		updateState { current ->
			val updated = current.copy(generatePhysics = enabled)
			if (!enabled) updated.copy(parameterValues = updated.parameterValues + physicsRestValues(current, current.rigEdits)) else updated
		}
		if (!enabled) resetPreviewPhysics()
		scheduleRuntimeBundleUpdate()
	    editorChanged()
	}

	fun setPhysicsFrontHair(enabled: Boolean) = setPresetPhysics(PhysicsGenerator.FRONT_HAIR_ID, enabled)
	fun setPhysicsBackHair(enabled: Boolean) = setPresetPhysics(PhysicsGenerator.BACK_HAIR_ID, enabled)
	fun setPhysicsEyeJelly(enabled: Boolean) = setPresetPhysics(PhysicsGenerator.EYE_JELLY_ID, enabled)

	private fun setPresetPhysics(id: String, enabled: Boolean) {
		submitPhysicsIntent(io.github.psd2live.application.WorkspacePhysicsIntent.Enabled(id, enabled))
	}

	// region Physics groups

	private var physicsCatalogCache: Pair<List<Any?>, List<PhysicsGroup>>? = null

	/** Every physics group of the current model, as export sees it; see [PhysicsCatalog.groups]. */
	fun physicsGroups(state: PSD2LiveState = _state.value): List<PhysicsGroup> {
		val model = state.previewModel ?: return emptyList()
		val key = listOf(model.analysis, model.rig.puppet.parameters, state.rigEdits.physicsEdits, state.rigEdits.disabledPhysicsIds, state.rigEdits.physicsOrder,
			state.rigEdits.skeleton, state.rigEdits.swingEdits, state.physicsFrontHair, state.physicsBackHair, state.physicsEyeJelly,
			state.hairSimulationFront, state.hairSimulationBack, state.rigEdits.importedCmo3)
		physicsCatalogCache?.let { (k, groups) -> if (k == key) return groups }
		val groups = PhysicsCatalog.groups(if (state.rigEdits.importedCmo3 != null) PhysicsGenerator.Presets(false, false, false)
			else PhysicsGenerator.Presets.present(model.analysis, state.hairSimulationFront, state.hairSimulationBack),
			PhysicsGenerator.Presets(state.physicsFrontHair, state.physicsBackHair, state.physicsEyeJelly),
			state.rigEdits, model.rig.puppet.parameters.mapTo(HashSet()) { it.id.raw })
		physicsCatalogCache = key to groups
		return groups
	}

	/** Makes [edit] the version of its group; a slider drag inside a gesture commits once. */
	fun putPhysicsGroup(edit: RigPhysicsEdit) {
		submitPhysicsIntent(io.github.psd2live.application.WorkspacePhysicsIntent.Put(edit))
	}

	fun setPhysicsGroupEnabled(id: String, enabled: Boolean) {
		submitPhysicsIntent(io.github.psd2live.application.WorkspacePhysicsIntent.Enabled(id, enabled))
	}

	/** Deletes a user group, or returns a replaced generated one to its generated values. */
	fun removePhysicsGroup(id: String) {
		if (_state.value.rigEdits.physicsEdits.none { it.id == id }) return
		submitPhysicsIntent(io.github.psd2live.application.WorkspacePhysicsIntent.Delete(id))
	}

	/** Adds a new pendulum, a copy of [from] without its outputs when given, and returns its ID. */
	fun createPhysicsGroup(from: RigPhysicsEdit? = null, onCreated: (String) -> Unit = {}) {
		submitPhysicsIntent(io.github.psd2live.application.WorkspacePhysicsIntent.Create(
			from?.let { tr("physics.copyName", it.name) } ?: tr("physics.newName"), from?.id), onCreated)
	}

	/** Moves [id] [by] places in the evaluation order. */
	fun movePhysicsGroup(id: String, by: Int) = submitPhysicsIntent(io.github.psd2live.application.WorkspacePhysicsIntent.Move(id, by))

	/** The project's one frame rate, [RigEditOverlay.UNLIMITED_FPS] for the display's: preview, parameters and physics. */
	fun setProjectFps(fps: Int) = submitPhysicsIntent(io.github.psd2live.application.WorkspacePhysicsIntent.Fps(fps))

	/** Sets each output's scale so the swing measured in [peaks] just reaches its parameter's end. */
	fun fitPhysicsScales(id: String, peaks: Map<Int, Float>) {
		submitPhysicsIntent(io.github.psd2live.application.WorkspacePhysicsIntent.FitObserved(id, peaks.toMap()))
	}

	fun applyPhysicsPreset(id: String, preset: io.github.psd2live.core.PhysicsPresets.Preset) {
		submitPhysicsIntent(io.github.psd2live.application.WorkspacePhysicsIntent.Preset(id, preset))
	}

	/** Imports a physics3.json's groups as user groups and returns the first one's ID. */
	fun importPhysics(path: String, onCreated: (String) -> Unit = {}) {
		val port: io.github.psd2live.application.WorkspacePhysicsPort = workspaceBackend ?: return
		var report: kotlinx.serialization.json.JsonObject? = null
		runWorkspaceCommand(after = {
			val imported = report?.get("imported")?.jsonArray.orEmpty().map { it.jsonPrimitive.content }
			addLog(tr("physics.import.done", imported.size, java.nio.file.Path.of(path).fileName), tag = "Physics")
			report?.get("disabled")?.jsonArray?.takeIf { it.isNotEmpty() }?.let { disabled ->
				addLog(tr("physics.import.disabled", disabled.joinToString { it.jsonPrimitive.content }), level = LogLevel.WARNING, tag = "Physics")
			}
			report?.get("missing_parameters")?.jsonObject?.forEach { (group, missing) ->
				addLog(tr("physics.import.missing", group, missing.jsonArray.joinToString { it.jsonPrimitive.content }), level = LogLevel.WARNING, tag = "Physics")
			}
			imported.firstOrNull()?.let(onCreated)
		}) { state ->
			val result = port.importPhysics(path, state); report = result.second; result.first
		}
	}

	private fun submitPhysicsIntent(intent: io.github.psd2live.application.WorkspacePhysicsIntent, onCreated: (String) -> Unit = {}) {
		val workspace = workspaceBackend ?: return
		if (editorSessions.anyOpen && intent !is io.github.psd2live.application.WorkspacePhysicsIntent.Create) {
			val current = _state.value
			val model = current.previewModel ?: return
			val prepared = runCatching {
				val document = WorkspaceStateCodec.document(current)
				val operation = io.github.psd2live.application.WorkspacePhysicsIntents.operation(document, model, intent)
				io.github.psd2live.application.WorkspacePhysicsEdits.apply(operation, document, model)
			}.getOrElse { failure -> setErrorMessage(failure.message); return }
			updateState { it.copy(rigEdits = prepared.rigEdits,
				generatePhysics = prepared.settings["generatePhysics"]?.jsonPrimitive?.boolean ?: it.generatePhysics,
				physicsFrontHair = prepared.settings["physicsFrontHair"]?.jsonPrimitive?.boolean ?: it.physicsFrontHair,
				physicsBackHair = prepared.settings["physicsBackHair"]?.jsonPrimitive?.boolean ?: it.physicsBackHair,
				physicsEyeJelly = prepared.settings["physicsEyeJelly"]?.jsonPrimitive?.boolean ?: it.physicsEyeJelly) }
			scheduleRuntimeBundleUpdate()
			editorChanged()
			return
		}
		val port: io.github.psd2live.application.WorkspacePhysicsPort = workspace
		var created: String? = null
		runWorkspaceCommand(after = { created?.let(onCreated) }) { state ->
			val result = port.editPhysics(intent, state)
			created = result.second["created"]?.jsonPrimitive?.content
			result.first
		}
	}

	/** Rest values for the outputs of [ids] (every group when null) so a switched-off group lets go. */
	private fun physicsRestValues(state: PSD2LiveState, overlay: RigEditOverlay, ids: Set<String>? = null): Map<ParameterId, Float> {
		val parameters = state.previewModel?.rig?.puppet?.parameters?.associateBy { it.id.raw } ?: return emptyMap()
		return physicsGroups(state.copy(rigEdits = overlay)).filter { ids == null || it.id in ids }
			.flatMap { it.setting.outputParameters }.mapNotNull { parameters[it] }
			.filter { it.id !in state.lockedParameters }.associate { it.id to it.default }
	}

	// endregion

	// region Authored motions

	val motionClips: List<MotionClip> get() = _state.value.rigEdits.motionClips

	/**
	 * The clip the editor has open, if it still exists (an undo can remove it). A generated motion opens as
	 * its override once edited, else as its tracks as its settings make them (see [MotionEditorState.presetClipId]).
	 */
	fun editingMotionClip(state: PSD2LiveState = _state.value): MotionClip? {
		val id = motionEditor.clipId ?: return null
		MotionEditorState.presetOf(id)?.let { return presetMotionClip(state, it) }
		return state.rigEdits.motionClips.firstOrNull { it.id == id }
	}

	private class PresetClip(val name: String, val skeleton: io.github.psd2live.core.SkeletonSpec?, val settings: MotionPresetSettings, val clip: MotionClip)
	@Volatile private var presetClipCache: PresetClip? = null

	/** [name] as the editor and the playback see it; null once deleted or when the rig cannot play it. */
	internal fun presetMotionClip(state: PSD2LiveState, name: String): MotionClip? {
		val settings = state.rigEdits.motionPresets[name] ?: MotionPresetSettings()
		if (settings.deleted || !state.motionPresetGroupOn(name)) return null
		MotionClips.overrideOf(state.rigEdits.motionClips, name)?.let { return it }
		val skeleton = state.rigEdits.skeleton
		// The idle expands its poses onto the bones; the editor reads it every frame while it plays.
		presetClipCache?.takeIf { it.name == name && it.skeleton === skeleton && it.settings == settings }?.let { return it.clip }
		val clip = MotionPresets.clip(MotionEditorState.presetClipId(name), name, skeleton, settings).takeIf { it.curves.isNotEmpty() }
		if (clip != null) presetClipCache = PresetClip(name, skeleton, settings, clip)
		return clip
	}

	fun motionPresetSettings(name: String): MotionPresetSettings = _state.value.rigEdits.motionPresets[name] ?: MotionPresetSettings()

	private fun editMotionPreset(name: String, action: String, values: Map<String, Float> = emptyMap(), disabled: Boolean? = null) {
		val state = currentWorkspaceState() ?: return
		val request = kotlinx.serialization.json.buildJsonObject {
			put("builtin", name); put("action", action)
			if (values.isNotEmpty()) putJsonObject("values") { values.forEach { (id, value) -> put(id, value) } }
			disabled?.let { put("disabled", it) }
		}
		saveDocumentEdits(state, "Edit generated motion", listOf(io.github.psd2live.application.WorkspaceDocumentOperation("motion_preset", request))) {
			failure -> if (failure != null) updateState { it.copy(statusText = failure) }
		}
	}

	fun setMotionPresetValue(name: String, knobId: String, value: Float) {
		val knob = MotionPresets.knobs(name).firstOrNull { it.id == knobId } ?: return
		if (value.isFinite()) editMotionPreset(name, "update", mapOf(knobId to value.coerceIn(knob.min, knob.max)))
	}
	fun resetMotionPreset(name: String) = editMotionPreset(name, "reset")
	fun deleteMotionPreset(name: String) {
		if (motionEditor.clipId == MotionEditorState.presetClipId(name)) closeMotionEditorClip()
		stopProcessMotion(name)
		editMotionPreset(name, "delete")
	}
	fun restoreMotionPreset(name: String) = editMotionPreset(name, "restore")
	fun setMotionPresetEnabled(name: String, enabled: Boolean) {
		if (!enabled) stopProcessMotion(name)
		editMotionPreset(name, "update", disabled = !enabled)
	}

	/**
	 * The panel's play button: plays or pauses [clipId] the way the editor's own button does, opening it in
	 * the editor first, so both show the same motion, playhead and state.
	 */
	fun toggleMotionPlayback(clipId: String) {
		if (motionEditor.clipId == clipId) {
			setMotionEditorPlaying(!motionEditor.playing)
			return
		}
		openMotionInEditor(clipId, focus = false)
		setMotionEditorPlaying(true)
	}

	internal fun motionParameterRanges(): Map<String, ClosedFloatingPointRange<Float>> =
		_state.value.previewModel?.rig?.puppet?.parameters?.associate { it.id.raw to it.min..it.max }.orEmpty()

	/** A transient drag projection; completed edits use the application timeline commands. */
	private fun previewMotionClips(transform: (List<MotionClip>) -> List<MotionClip>) {
		val current = _state.value
		val before = current.rigEdits.motionClips
		val next = transform(before)
		if (next == before) return
		updateState { it.copy(rigEdits = it.rigEdits.copy(motionClips = next)) }
	}

	/**
	 * One change to the clip [id]. A generated motion that is not yet edited gets its override here, in the
	 * same step. The clip keeps its id and what it overrides whatever [transform] returns: a drag transforms
	 * the clip it started from, which may be the generated one.
	 */
	private fun previewMotionClip(id: String, transform: (MotionClip) -> MotionClip) {
		val preset = MotionEditorState.presetOf(id)
		if (preset == null) {
			previewMotionClips { clips ->
				clips.map { if (it.id == id) transform(it).copy(id = it.id, builtin = it.builtin) else it }
			}
			return
		}
		val generated = presetMotionClip(_state.value, preset) ?: return
		previewMotionClips { clips ->
			val existing = MotionClips.overrideOf(clips, preset)
			if (existing != null) clips.map { if (it.id == existing.id) transform(it).copy(id = it.id, builtin = it.builtin) else it }
			else {
				val next = transform(generated).copy(id = MotionClips.newId(clips), name = preset, builtin = preset)
				if (next.copy(id = generated.id) == generated) clips else clips + next
			}
		}
	}

	/** A new clip, blank or a copy of a generated motion as it plays now, opened in the editor. */
	fun createMotionClip(fromBuiltin: String? = null): String {
		val id = MotionClips.newId(motionClips)
		saveMotionOperation("create", kotlinx.serialization.json.buildJsonObject {
			put("id", id); put("name", fromBuiltin ?: tr("animation.newMotionName")); fromBuiltin?.let { put("from_builtin", it) }
		}) { openMotionInEditor(id) }
		return id
	}

	fun duplicateMotionClip(id: String) {
		if (motionClips.none { it.id == id }) return
		val nextId = MotionClips.newId(motionClips)
		saveMotionOperation("duplicate", kotlinx.serialization.json.buildJsonObject { put("id", id); put("new_id", nextId) }) { openMotionInEditor(nextId) }
	}

	fun renameMotionClip(id: String, name: String) {
		val trimmed = name.trim()
		if (trimmed.isEmpty() || trimmed.any(Char::isISOControl)) return
		val clip = motionClips.firstOrNull { it.id == id } ?: return
		if (clip.builtin != null || clip.name == trimmed) return
		saveMotionOperation("rename", kotlinx.serialization.json.buildJsonObject { put("id", id); put("name", trimmed) })
	}

	fun deleteMotionClip(id: String) {
		if (motionClips.none { it.id == id }) return
		stopProcessMotion()
		saveMotionOperation("delete", kotlinx.serialization.json.buildJsonObject { put("id", id) })
		if (motionEditor.clipId == id) closeMotionEditorClip()
	}

	/** Loop, duration, FPS, fades or the export switch; a shorter duration drops the keys past it. */
	fun updateMotionClipProperties(id: String, transform: (MotionClip) -> MotionClip) {
		val clip = motionClips.firstOrNull { it.id == id } ?: editingMotionClip()?.takeIf { it.id == id } ?: return
		val next = transform(clip)
		editTimeline("properties", kotlinx.serialization.json.buildJsonObject {
			put("loop", next.loop); put("duration", next.duration); put("fps", next.fps)
			put("fade_in", next.fadeIn); put("fade_out", next.fadeOut); put("enabled", next.enabled)
		}, clip)
	}

	private fun saveMotionOperation(mode: String, fields: kotlinx.serialization.json.JsonObject, completed: () -> Unit = {}) {
		val state = currentWorkspaceState() ?: return
		saveDocumentEdits(state, "Edit motion", listOf(io.github.psd2live.application.WorkspaceDocumentOperation("motion_$mode", fields))) { failure ->
			if (failure == null) completed() else updateState { it.copy(statusText = failure) }
		}
	}

	/** Opens a generated motion in the editor; it becomes an override only once a key changes. */
	fun editBuiltinMotion(name: String) = openMotionInEditor(MotionEditorState.presetClipId(name))

	/** Drops the override so the generated motion plays and exports again. */
	fun resetBuiltinMotion(name: String) {
		val clip = MotionClips.overrideOf(motionClips, name) ?: return
		deleteMotionClip(clip.id)
	}

	/** Opens [id] in the editor; [focus] brings the editor's dock forward. */
	fun openMotionInEditor(id: String, focus: Boolean = true) {
		if (motionEditor.clipId != id) {
			motionEditor.autoKey = false
			motionEditor.playing = false
			motionEditor.selection = emptySet()
			motionEditor.focusedCurve = null
			motionEditor.playhead = 0f
		}
		val opened = motionEditor.clipId != id
		motionEditor.clipId = id
		// The process clock poses the canvases and sliders at the playhead of the motion now open.
		if (opened && editingMotionClip() != null)
			configurePlayback("seek", kotlinx.serialization.json.buildJsonObject { put("clip_id", id); put("time", 0f) })
		if (focus) requestSelectDockModule("animationEditor")
	}

	/** Selects the editor's curve for [parameterId] of the open clip. */
	fun focusMotionCurve(parameterId: String?) {
		motionEditor.focusedCurve = parameterId
	}

	fun closeMotionEditorClip() {
		val open = motionEditor.clipId != null
		motionEditor.autoKey = false
		motionEditor.playing = false
		motionEditor.clipId = null
		motionEditor.selection = emptySet()
		motionEditor.focusedCurve = null
		// Release the timeline's pose so the next clock frame does not reopen the motion it carries.
		if (open) configurePlayback("animation", kotlinx.serialization.json.buildJsonObject { put("enabled", _state.value.animationEnabled) })
		setMotionFramePose(emptyMap())
	}

	private fun motionKeyJson(key: MotionKey) = kotlinx.serialization.json.buildJsonObject {
		put("time", key.time); put("value", key.value); put("interpolation", key.interpolation.name)
		put("out", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive(key.outHandle.x), kotlinx.serialization.json.JsonPrimitive(key.outHandle.y))))
		put("in", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive(key.inHandle.x), kotlinx.serialization.json.JsonPrimitive(key.inHandle.y))))
	}
	private fun motionSelectionJson(selection: Set<MotionKeyRef>) = kotlinx.serialization.json.buildJsonArray {
		selection.forEach { ref -> add(kotlinx.serialization.json.buildJsonObject { put("parameter", ref.parameterId); put("time", ref.time) }) }
	}
	private fun editTimeline(mode: String, fields: kotlinx.serialization.json.JsonObject, source: MotionClip? = editingMotionClip(), expectedState: String? = currentWorkspaceState()) {
		val clip = source ?: return
		val state = expectedState ?: return
		val untouched = clip.id.startsWith("preset:")
		val materialized = if (untouched) clip.copy(id = MotionClips.newId(motionClips)) else clip
		val edits = buildList {
			if (untouched) add(io.github.psd2live.application.WorkspaceDocumentOperation("motion_put", kotlinx.serialization.json.buildJsonObject { put("clip", MotionClips.toJson(materialized)) }))
			add(io.github.psd2live.application.WorkspaceDocumentOperation("motion_" + mode,
				kotlinx.serialization.json.JsonObject(fields + ("id" to kotlinx.serialization.json.JsonPrimitive(materialized.id)))))
		}
		saveDocumentEdits(state, "Edit timeline", edits) { failure -> if (failure != null) updateState { it.copy(statusText = failure) } }
	}

	fun toggleMotionAutoKey() {
		if (editingMotionClip() != null) motionEditor.autoKey = !motionEditor.autoKey
	}

	fun setMotionAutoKey(enabled: Boolean) {
		motionEditor.autoKey = enabled && editingMotionClip() != null
	}


	/** A curve for [parameterId], keyed at the playhead with the pose the preview shows. */
	fun addMotionCurve(parameterId: String) {
		val clip = editingMotionClip() ?: return
		motionEditor.focusedCurve = parameterId
		if (clip.curve(parameterId) != null) return
		val time = motionEditor.playhead.coerceIn(0f, clip.duration)
		editTimeline("set_key", kotlinx.serialization.json.buildJsonObject { put("parameter", parameterId); put("key", motionKeyJson(MotionKey(time, currentMotionParameterValue(parameterId)))) })
		motionEditor.selection = setOf(MotionKeyRef(parameterId, time))
	}

	fun removeMotionCurve(parameterId: String) {
		editTimeline("remove_curve", kotlinx.serialization.json.buildJsonObject { put("parameter", parameterId) })
		motionEditor.selection = motionEditor.selection.filterTo(HashSet()) { it.parameterId != parameterId }
		if (motionEditor.focusedCurve == parameterId) motionEditor.focusedCurve = null
	}

	/** The current authored workspace value, never a cached preview frame. */
	private fun currentMotionParameterValue(parameterId: String): Float {
		val state = _state.value.previewPanelState()
		val id = ParameterId(parameterId)
		return state.parameterValues[id]
			?: state.previewModel?.rig?.puppet?.parameters?.firstOrNull { it.id == id }?.default ?: 0f
	}

	/** A key at [time] on [parameterId], at [value] or the curve's value there. */
	fun setMotionKey(parameterId: String, time: Float, value: Float? = null) {
		val clip = editingMotionClip() ?: return
		val at = time.coerceIn(0f, clip.duration)
		val ref = MotionKeyRef(parameterId, at)
		val curve = clip.curve(parameterId)
		val existing = curve?.keys?.firstOrNull(ref::matches)
		val v = value ?: curve?.let { MotionClips.sample(it, at) } ?: currentMotionParameterValue(parameterId)
		editTimeline("set_key", kotlinx.serialization.json.buildJsonObject { put("parameter", parameterId); put("key", motionKeyJson(existing?.copy(value = v) ?: MotionKey(at, v))) })
		motionEditor.selection = setOf(ref)
	}

	/** Keys every curve of the open clip at the playhead with the pose the preview shows now. */
	fun keyCurrentPose() {
		val clip = editingMotionClip() ?: return
		val time = motionEditor.playhead.coerceIn(0f, clip.duration)
		editTimeline("pose", kotlinx.serialization.json.buildJsonObject {
			put("time", time); putJsonObject("values") { clip.curves.forEach { curve -> put(curve.parameterId, currentMotionParameterValue(curve.parameterId)) } }
		})
		motionEditor.selection = clip.curves.mapTo(HashSet()) { MotionKeyRef(it.parameterId, time) }
	}

	fun insertSavedSkeletonPose(name: String) {
		val current = _state.value
		val saved = current.rigEdits.skeleton?.savedPoses?.get(name) ?: return
		val clip = editingMotionClip() ?: return
		val parameters = current.previewModel?.rig?.puppet?.parameters.orEmpty().associateBy { it.id.raw }
		val values = saved.mapNotNull { (id, value) -> parameters[id]?.let { id to value.coerceIn(it.min, it.max) } }.toMap()
		val time = motionEditor.playhead.coerceIn(0f, clip.duration)
		editTimeline("pose", kotlinx.serialization.json.buildJsonObject { put("time", time); putJsonObject("values") { values.forEach { (id, value) -> put(id, value) } } })
		motionEditor.selection = values.keys.mapTo(linkedSetOf()) { MotionKeyRef(it, time) }
	}

	fun deleteSelectedMotionKeys() {
		val selection = motionEditor.selection.takeIf { it.isNotEmpty() } ?: return
		editTimeline("delete_keys", kotlinx.serialization.json.buildJsonObject { put("selection", motionSelectionJson(selection)) })
		motionEditor.selection = emptySet()
	}

	/** Edits the selected keys in place: interpolation, handles, or a typed time or value. */
	fun updateSelectedMotionKeys(transform: (String, MotionKey) -> MotionKey) {
		val clip = editingMotionClip() ?: return
		val selection = motionEditor.selection.takeIf { it.isNotEmpty() } ?: return
		val ranges = motionParameterRanges()
		val moved = mutableSetOf<MotionKeyRef>()
		val replacements = mutableListOf<kotlinx.serialization.json.JsonObject>()
		MotionKeyEdits.mapKeys(clip, selection) { id, key ->
			val edited = transform(id, key)
			val range = ranges[id]
			edited.copy(
				time = edited.time.coerceIn(0f, clip.duration),
				value = if (range != null) edited.value.coerceIn(range) else edited.value,
			).also { next ->
				moved += MotionKeyRef(id, next.time)
				replacements += kotlinx.serialization.json.buildJsonObject { put("parameter", id); put("from_time", key.time); put("key", motionKeyJson(next)) }
			}
		}
		editTimeline("replace_keys", kotlinx.serialization.json.buildJsonObject { put("keys", kotlinx.serialization.json.JsonArray(replacements)) })
		motionEditor.selection = moved
	}

	fun copySelectedMotionKeys() {
		val clip = editingMotionClip() ?: return
		motionEditor.clipboard = MotionKeyEdits.copy(clip, motionEditor.selection)
	}

	fun pasteMotionKeys() {
		val keys = motionEditor.clipboard.takeIf { it.isNotEmpty() } ?: return
		val clip = editingMotionClip() ?: return
		val (next, pasted) = MotionKeyEdits.paste(clip, keys, motionEditor.playhead)
		editTimeline("paste_keys", kotlinx.serialization.json.buildJsonObject {
			put("time", motionEditor.playhead); putJsonArray("keys") { keys.forEach { (id, key) -> add(kotlinx.serialization.json.buildJsonObject { put("parameter", id); put("key", motionKeyJson(key)) }) } }
		})
		motionEditor.selection = pasted
	}

	/** Starts a key or handle drag: samples apply to this clip, and the drag records one history node. */
	fun beginMotionKeyDrag() {
		motionEditor.dragOrigin = editingMotionClip() ?: return
		motionEditor.dragSelection = motionEditor.selection
		motionEditor.dragState = currentWorkspaceState()
		motionEditor.dragClips = _state.value.rigEdits.motionClips
		motionEditor.dragRequest = null
	}

	fun dragMotionKeys(dt: Float, dv: Float = 0f, normalized: Boolean = false) {
		val origin = motionEditor.dragOrigin ?: return
		val (next, moved) = MotionKeyEdits.move(origin, motionEditor.dragSelection, dt, dv, motionParameterRanges(), normalized)
		previewMotionClip(origin.id) { next }
		motionEditor.dragMode = "move_keys"
		motionEditor.dragRequest = kotlinx.serialization.json.buildJsonObject { put("selection", motionSelectionJson(motionEditor.dragSelection)); put("dt", dt); put("dv", dv); put("normalized", normalized) }
		motionEditor.selection = moved
	}

	/** Sets one Bezier handle of [ref], relative to the drag's origin clip. */
	internal fun dragMotionHandle(ref: MotionKeyRef, outgoing: Boolean, handle: MotionHandle) {
		val origin = motionEditor.dragOrigin ?: return
		val next = MotionKeyEdits.mapKeys(origin, setOf(ref)) { _, key ->
			if (outgoing) key.copy(outHandle = handle.clamped()) else key.copy(inHandle = handle.clamped())
		}
		previewMotionClip(origin.id) { next }
		val key = next.curve(ref.parameterId)?.keys?.firstOrNull(ref::matches) ?: return
		motionEditor.dragMode = "replace_keys"
		motionEditor.dragRequest = kotlinx.serialization.json.buildJsonObject { putJsonArray("keys") { add(kotlinx.serialization.json.buildJsonObject { put("parameter", ref.parameterId); put("from_time", ref.time); put("key", motionKeyJson(key)) }) } }
	}

	fun endMotionKeyDrag() {
		val origin = motionEditor.dragOrigin ?: return
		val request = motionEditor.dragRequest
		val mode = motionEditor.dragMode
		val next = _state.value.rigEdits.motionClips
		val before = motionEditor.dragClips
		val expected = motionEditor.dragState
		motionEditor.dragOrigin = null
		motionEditor.dragSelection = emptySet()
		motionEditor.dragState = null
		motionEditor.dragClips = emptyList()
		if (expected == currentWorkspaceState()) updateState { it.copy(rigEdits = it.rigEdits.copy(motionClips = before)) }
		if (expected != null && request != null && next != before) editTimeline(mode, request, origin, expected)
	}

	/** Moves the playhead and poses the preview there; the preview pauses so the editor owns the pose. */
	fun setMotionPlayhead(time: Float) {
		val clip = editingMotionClip()
		val t = if (clip != null) time.coerceIn(0f, clip.duration) else time.coerceAtLeast(0f)
		motionEditor.playhead = t
		if (clip != null) configurePlayback("seek", kotlinx.serialization.json.buildJsonObject { put("clip_id", clip.id); put("time", t) })
	}

	/** False when no workspace session took the command; the caller then only has the GUI projection. */
	private fun configurePlayback(mode: String, fields: kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.JsonObject(emptyMap())): Boolean {
		val port = workspaceBackend as? io.github.psd2live.application.WorkspacePreviewPort ?: return false
		val state = currentWorkspaceState() ?: return false
		return try {
			port.controlPlayback(kotlinx.serialization.json.JsonObject(fields + mapOf("state" to kotlinx.serialization.json.JsonPrimitive(state), "mode" to kotlinx.serialization.json.JsonPrimitive(mode))))
			true
		} catch (failure: Exception) {
			updateState { it.copy(statusText = failure.message ?: "Could not change playback") }
			false
		}
	}

	private val playbackFrameLock = Any()
	/** Counts applied playback commands, so a clock frame read before one cannot switch its result back. */
	@Volatile private var playbackCommands = 0L
	/** The session the GUI's play and tracking switches were last handed to: load generation and workspace. */
	private var playbackSyncKey: String? = null

	/**
	 * The process session owns the play and tracking switches; the canvases and panels only project them.
	 * A clock frame is discarded when a command landed since it was read, including its pose and playhead.
	 * Natural motion completion still reaches the buttons when the frame belongs to the current session.
	 */
	internal fun applyPlaybackFrame(frame: kotlinx.serialization.json.JsonObject, commandsSeen: Long? = null) = synchronized(playbackFrameLock) {
		val current = _state.value
		if (frame.getValue("workspace_id").jsonPrimitive.content != current.activeWorkspace.id) return@synchronized
		if (frame.getValue("project_id").jsonPrimitive.content != current.projectId ||
			frame.getValue("state").jsonPrimitive.content != currentWorkspaceState()) return@synchronized
		if (commandsSeen != null && commandsSeen != playbackCommands) return@synchronized
		if (commandsSeen == null) playbackCommands++
		playbackSyncKey = "${current.projectOpenGeneration}/${current.activeWorkspace.id}/${frame.getValue("state").jsonPrimitive.content.substringBeforeLast(':')}"
		frame["clip_id"]?.jsonPrimitive?.content?.let {
			motionEditor.clipId = current.rigEdits.motionClips.firstOrNull { clip -> clip.id == it }?.builtin
				?.let(MotionEditorState::presetClipId) ?: it
			motionEditor.playhead = frame.getValue("time").jsonPrimitive.float
		}
		val playing = frame.getValue("playing").jsonPrimitive.boolean
		val animation = frame.getValue("animation").jsonPrimitive.boolean
		val tracking = frame.getValue("tracking").jsonPrimitive.boolean
		motionEditor.playing = playing
		processActiveMotion = frame["active_motion"]?.jsonPrimitive?.content
		processPlaybackActive = animation || playing || tracking || frame["clip_id"] != null
		val values = frame.getValue("values").jsonObject.map { (id, value) -> ParameterId(id) to value.jsonPrimitive.float }.toMap()
		processFrameValues = values
		// A control command invalidates the previously rendered/physical frame.
		if (commandsSeen == null) liveFramePose = emptyMap()
		val curves = frame["clip_id"]?.let { editingMotionClip(current)?.curves?.mapTo(HashSet()) { ParameterId(it.parameterId) } }
		setMotionFramePose(if (curves.isNullOrEmpty()) emptyMap() else values.filterKeys { it in curves })
		if (commandsSeen == null) emitLivePose()
		val composePhysics = current.previewLive && current.generatePhysics && !current.meshOnly &&
			current.activeWorkspace.pose?.authoringPose != true
		updateCanvasPresentation(current.activeWorkspace.id, current.previewControlCanvas().id, CanvasMode.PREVIEW) {
			// One play switch: the canvas shows playing whether the idle clock or the open motion runs.
			// Clock poses publish after physics composition, avoiding an intermediate frame at rest.
			it.copy(animationEnabled = animation || playing,
				previewParameterValues = if (commandsSeen != null && composePhysics) it.previewParameterValues else parameterScrubPose(current, values),
				mouseTrackingEnabled = tracking, smoothMouseTracking = frame["smooth_tracking"]?.jsonPrimitive?.boolean ?: false)
		}
	}

	/**
	 * Posing by hand stops the one play switch on the session before the pose shows; a local switch alone would
	 * be turned back on by the next clock frame. A playing motion pauses at its playhead, the idle stops.
	 */
	private fun stopPlaybackForAuthoring() {
		if (!_state.value.previewPanelState().animationEnabled && !motionEditor.playing) return
		if (editingMotionClip() != null && motionEditor.playing) configurePlayback("pause")
		else configurePlayback("animation", kotlinx.serialization.json.buildJsonObject { put("enabled", false) })
	}

	/** The canvas and animation panel play the open motion, or the generated idle when no motion is open. */
	fun togglePreviewPlayback() {
		val playing = _state.value.previewPanelState().animationEnabled
		if (editingMotionClip() != null) setMotionEditorPlaying(!playing)
		else setAnimationEnabled(!playing)
	}

	/**
	 * Hands the GUI's play and tracking switches to a session that has not seen them: a newly loaded project or
	 * a switched workspace starts a fresh session, while the switches saved with the workspace still show.
	 */
	private fun syncPlaybackSession(current: PSD2LiveState) {
		val state = currentWorkspaceState() ?: return
		val key = "${current.projectOpenGeneration}/${current.activeWorkspace.id}/${state.substringBeforeLast(':')}"
		if (key == playbackSyncKey) return
		val panel = current.previewPanelState()
		if (!configurePlayback("tracking", kotlinx.serialization.json.buildJsonObject { put("enabled", panel.mouseTrackingEnabled); put("smooth", panel.smoothMouseTracking) })) return
		if (panel.animationEnabled && editingMotionClip(current) == null)
			configurePlayback("animation", kotlinx.serialization.json.buildJsonObject { put("enabled", true) })
		playbackSyncKey = key
	}

	fun setMotionEditorPlaying(playing: Boolean) {
		val clip = editingMotionClip()
		if (playing && clip == null) return
		if (playing && clip != null) {
			val time = motionEditor.playhead.takeIf { it < clip.duration - 1e-4f } ?: 0f
			configurePlayback("start", kotlinx.serialization.json.buildJsonObject { put("clip_id", motionEditor.clipId ?: clip.id); put("time", time) })
		} else configurePlayback("pause")
	}
	fun stopMotionEditorPlayback() = configurePlayback("stop")
	// endregion

	/** Commit an edited armature as one undoable project change and rebuild its derived rig. */
	fun setSkeleton(spec: io.github.psd2live.core.SkeletonSpec, expectedState: String? = currentWorkspaceState(),
		onComplete: (String?) -> Unit = {}) = editSkeleton("Edit skeleton", io.github.psd2live.application.WorkspaceDocumentOperation("skeleton_put",
			kotlinx.serialization.json.buildJsonObject { put("spec", spec.toJson()) }), expectedState, onComplete)

	/** Removes the armature as one undoable project change; a new one can be created afterwards. */
	fun deleteSkeleton(expectedState: String? = currentWorkspaceState(), onComplete: (String?) -> Unit = {}) =
		editSkeleton("Delete skeleton", io.github.psd2live.application.WorkspaceDocumentOperation("skeleton_delete",
			kotlinx.serialization.json.JsonObject(emptyMap())), expectedState, onComplete)

	private fun editSkeleton(summary: String, operation: io.github.psd2live.application.WorkspaceDocumentOperation,
		expectedState: String?, onComplete: (String?) -> Unit) {
		if (expectedState == null) { onComplete("Project workspace unavailable"); return }
		val started = _state.value
		var committedState: String? = null
		saveWorkspaceEdit({ failure ->
			if (failure != null) updateState {
				if (it.projectId == started.projectId && it.projectOpenGeneration == started.projectOpenGeneration &&
					it.activeWorkspace.id == started.activeWorkspace.id) it.copy(statusText = failure) else it
			}
			// Entering Edit may reset the authored pose; its command must wait for this edit to finish.
			scope.launch {
				_state.first { !it.workspaceEditBusy }
				val confirmed = committedState
				val actual = currentWorkspaceState()
				val outcome = failure ?: if (confirmed != null && confirmed != actual)
					io.github.psd2live.application.WorkspaceConflict(confirmed, actual ?: "unloaded").message else null
				onComplete(outcome)
			}
		}) {
			val workspace: io.github.psd2live.application.WorkspaceDocumentPort = requireNotNull(workspaceBackend) { "Project workspace unavailable" }
			committedState = workspace.applyDocumentEdits(expectedState, summary, listOf(operation), MutationAuthor.USER).state
		}
	}

	fun setSkeletonPoseMetadata(spec: io.github.psd2live.core.SkeletonSpec) = setSkeleton(spec)

	/** The application session the Skeleton Edit tool drafts in; public operations reach the same sessions. */
	internal val skeletonDraftPort: io.github.psd2live.application.WorkspaceSkeletonDraftPort? get() = workspaceBackend

	/** Opens the draft on the current state; the rest-pose reset is the session's own commit, not a separate edit. */
	internal fun openSkeletonDraft(onComplete: (io.github.psd2live.application.WorkspaceSkeletonDraft?, String?) -> Unit) {
		val port = workspaceBackend
		val expected = currentWorkspaceState()
		if (port == null || expected == null) { onComplete(null, "Project workspace unavailable"); return }
		var opened: io.github.psd2live.application.WorkspaceSkeletonDraft? = null
		saveWorkspaceEdit({ failure -> onComplete(opened.takeIf { failure == null }, failure) }) {
			opened = port.openSkeletonDraft(expected)
		}
	}

	/** Commits on the draft's own lineage; anything that moved the workspace since it opened is reported as a conflict. */
	internal fun commitSkeletonDraft(draft: io.github.psd2live.application.WorkspaceSkeletonDraft) {
		val port = workspaceBackend ?: return
		val started = _state.value
		saveWorkspaceEdit({ failure ->
			if (failure != null) updateState {
				if (it.projectId == started.projectId && it.projectOpenGeneration == started.projectOpenGeneration) it.copy(statusText = failure) else it
			}
		}) {
			port.commitSkeletonDraft(draft.id, draft.state, draft.sessionState, MutationAuthor.USER)
		}
	}

	fun setExportCmo3(enabled: Boolean) {
		updateState { it.copy(exportCmo3 = enabled) }
	    editorChanged()
	}

	fun setExportMoc3(enabled: Boolean) {
		updateState { it.copy(exportMoc3 = enabled) }
	    editorChanged()
	}

	fun setExportJson(enabled: Boolean) {
		updateState { it.copy(exportJson = enabled) }
	    editorChanged()
	}

	fun setRuntimeTarget(target: org.umamo.runtime.model.RuntimeTarget) {
		updateState { it.copy(runtimeTarget = target) }
		schedulePreviewRebuild()
		editorChanged()
	}

	fun setExportHiddenParts(enabled: Boolean) {
		updateState { it.copy(exportHiddenParts = enabled) }
		editorChanged()
	}

	fun setExportHiddenDrawables(enabled: Boolean) {
		updateState { it.copy(exportHiddenDrawables = enabled) }
		editorChanged()
	}

	fun setExportGuideImageParts(enabled: Boolean) {
		updateState { it.copy(exportGuideImageParts = enabled) }
		editorChanged()
	}

	fun setExportIncludePhysics(enabled: Boolean) {
		updateState { it.copy(exportIncludePhysics = enabled) }
		editorChanged()
	}

	fun setExportIncludeUserData(enabled: Boolean) {
		updateState { it.copy(exportIncludeUserData = enabled) }
		editorChanged()
	}

	fun setExportIncludeDisplayInfo(enabled: Boolean) {
		updateState { it.copy(exportIncludeDisplayInfo = enabled) }
		editorChanged()
	}

	fun setExportPixelsPerUnit(value: Float?) {
		updateState { it.copy(exportPixelsPerUnit = value?.takeIf { v -> v > 0f }) }
		editorChanged()
	}

	fun setDynamicsSubExpanded(expanded: Boolean) {
		updateState { it.copy(dynamicsSubExpanded = expanded) }
	    markWorkspaceChanged()
	}

	fun setSimulationPresetsExpanded(expanded: Boolean) {
		updateState { it.copy(simulationPresetsExpanded = expanded) }
	    markWorkspaceChanged()
	}

	fun setStrengthSubExpanded(expanded: Boolean) {
		updateState { it.copy(strengthSubExpanded = expanded) }
	    markWorkspaceChanged()
	}

	fun setRigTuningExpanded(expanded: Boolean) {
		updateState { it.copy(rigTuningExpanded = expanded) }
	    markWorkspaceChanged()
	}

	fun setRigTuningAdvancedExpanded(expanded: Boolean) {
		updateState { it.copy(rigTuningAdvancedExpanded = expanded) }
	    markWorkspaceChanged()
	}

	/** Back to the default presets; hair simulations stay until their classic sway is restored. */
	fun resetModelPresetsToDefault() {
		updateState {
			it.copy(
				strengthSubExpanded = false,
				rigTuningExpanded = false,
				rigTuningAdvancedExpanded = false,
				dynamicsSubExpanded = false,
				headStrength = 1.0f,
				bodyStrength = 1.0f,
				rigTuning = io.github.psd2live.core.RigTuning(),
				featureDisplacementEnabled = false,
                mouthOutlineEnabled = true,
                mouthShape = "smile",
                mouthCurve = io.github.psd2live.core.MouthCurve.preset("smile"),
                mouthColor = null,
                mouthThickness = 1.5f,
				exportMotions = true,
				motionBasic = true,
				motionIdle = true,
				motionBlink = true,
				motionNod = true,
				motionShake = true,
				motionSkeleton = true,
				generatePhysics = true,
				physicsFrontHair = true,
				physicsBackHair = true,
				physicsEyeJelly = true,
			)
		}
		schedulePreviewRebuild()
	    editorChanged()
	}

	fun setLanguage(language: AppLanguage) {
		I18n.setLanguage(language)
		updateState { it.copy(currentLanguage = language) }
		schedulePreviewRebuild()
	}

	private val zoomScaleSteps = listOf(1.0f, 1.15f, 1.25f, 1.35f, 1.5f, 1.75f, 2.0f, 2.25f, 2.5f)

	fun setUiScale(scale: Float) {
		val clamped = (kotlin.math.round(scale.coerceIn(0.75f, 3.0f) * 100) / 100f)
		AppSettings.uiScale = clamped
		updateState { it.copy(uiScale = clamped) }
	}

	fun setFontScale(scale: Float) {
		val clamped = (kotlin.math.round(scale.coerceIn(0.85f, 1.5f) * 100) / 100f)
		AppSettings.fontScale = clamped
		updateState { it.copy(fontScale = clamped) }
	}

	fun setTheme(id: String) {
		val colors = ThemeCatalog.resolve(id, _state.value.customThemes)
		AppSettings.themeId = id
		AppSettings.rememberLastThemeId(colors.isDark, id)
		updateState { it.copy(themeId = id, toolColors = colors) }
	}

	fun setCanvasBackground(background: CanvasBackground) {
		AppSettings.canvasBackground = background
		updateState { it.copy(canvasBackground = background) }
	}

	/** Flips between the dark and the light theme used last, so a custom theme survives the round trip. */
	fun toggleDarkTheme() {
		val state = _state.value
		val target = AppSettings.lastThemeId(!state.darkTheme)
		val exists = ThemeCatalog.isBuiltIn(target) || state.customThemes.any { it.id == target }
		val resolved = if (exists && ThemeCatalog.resolve(target, state.customThemes).isDark != state.darkTheme) target
			else if (state.darkTheme) ThemeCatalog.LIGHT_ID else ThemeCatalog.DARK_ID
		setTheme(resolved)
	}

	/** Copies theme [fromId] into a new custom theme and selects it. */
	fun duplicateTheme(fromId: String) {
		val state = _state.value
		val source = state.customThemes.firstOrNull { it.id == fromId }
		val baseName = source?.name ?: tr(ThemeCatalog.builtIn(fromId).nameKey)
		val theme = CustomTheme(
			id = ThemeCatalog.newCustomId(),
			name = tr("theme.custom.copyName", baseName),
			baseId = source?.baseId ?: ThemeCatalog.builtIn(fromId).id,
			overrides = source?.overrides ?: emptyMap(),
		)
		saveCustomThemes(state.customThemes + theme)
		setTheme(theme.id)
	}

	/** Applied at once but written out after a pause, since a colour drag sends an edit per frame. */
	fun updateCustomTheme(theme: CustomTheme) {
		val themes = _state.value.customThemes.map { if (it.id == theme.id) theme else it }
		saveCustomThemes(themes, debounce = true)
		if (_state.value.themeId == theme.id) {
			val colors = theme.resolve()
			updateState { it.copy(toolColors = colors) }
		}
	}

	fun deleteCustomTheme(id: String) {
		val removed = _state.value.customThemes.firstOrNull { it.id == id } ?: return
		saveCustomThemes(_state.value.customThemes - removed)
		if (_state.value.themeId == id) setTheme(removed.baseId)
	}

	/** Adds a theme pasted as [ThemeCodec] text and selects it; false when [text] is not a theme. */
	fun importTheme(text: String): Boolean {
		val decoded = ThemeCodec.decode(text, ThemeCatalog.newCustomId()) ?: return false
		val theme = if (decoded.name.isBlank()) decoded.copy(name = tr("theme.custom.imported")) else decoded
		saveCustomThemes(_state.value.customThemes + theme)
		setTheme(theme.id)
		return true
	}

	private var customThemeSaveJob: Job? = null

	private fun saveCustomThemes(themes: List<CustomTheme>, debounce: Boolean = false) {
		updateState { it.copy(customThemes = themes) }
		customThemeSaveJob?.cancel()
		if (debounce) {
			customThemeSaveJob = scope.launch {
				delay(400)
				AppSettings.saveCustomThemes(themes)
			}
		} else {
			AppSettings.saveCustomThemes(themes)
		}
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
	 * Deliberately not routed through setters that mark the project dirty: a global UI preference
	 * is not part of the project, and "Reset Defaults" must not leave an opened project looking unsaved.
	 */
	fun resetInteractionPrefs() {
		restorePrompts()
	}

	/** Turns [prompt] on or off ("Don't show again"); a global preference, so the project stays clean. */
	fun setPromptEnabled(prompt: AppPrompt, enabled: Boolean) {
		AppSettings.setPromptEnabled(prompt, enabled)
		updateState { it.copy(mutedPrompts = if (enabled) it.mutedPrompts - prompt else it.mutedPrompts + prompt) }
	}

	/** Turns every prompt muted with "Don't show again" back on. */
	fun restorePrompts() {
		AppPrompt.entries.forEach { AppSettings.setPromptEnabled(it, true) }
		updateState { it.copy(mutedPrompts = emptySet()) }
	}

	fun openSettingsDialog() {
		updateState { it.copy(showSettingsDialog = true) }
	}

	fun closeSettingsDialog() {
		updateState {
			it.copy(
				showSettingsDialog = false,
				// A capture left dangling would swallow every key from here on.
				keyCapture = null,
				focusCanvasRequest = it.focusCanvasRequest + 1,
			)
		}
	}

	// ---- Texture workspace ----

	private var textureSnapshotCache: TextureSnapshot? = null

	/**
	 * The committed textures and atlas, captured from the workspace runtime. Views key it on the preview model,
	 * the history head and [TextureWorkspaceState.revision], so commits and undo show at once. Null without a
	 * loaded project.
	 */
	fun textureSnapshot(): TextureSnapshot? {
		val backend = workspaceBackend ?: return null
		val view = try { backend.captureTextures() } catch (_: Exception) { return null }
		textureSnapshotCache?.takeIf { it.projectId == view.projectId && it.state == view.state }?.let { return it }
		return try { TextureSnapshot(view).also { textureSnapshotCache = it } } catch (_: Exception) { null }
	}

	private inline fun updateTextureWorkspace(crossinline transform: (TextureWorkspaceState) -> TextureWorkspaceState) {
		updateState { it.copy(textureWorkspace = transform(it.textureWorkspace)) }
	}

	fun setTexturePage(page: Int) = updateTextureWorkspace { it.copy(selectedPage = page.coerceAtLeast(0)) }
	fun setTextureHeatmap(on: Boolean) = updateTextureWorkspace { it.copy(heatmap = on) }
	fun setTextureOutlines(on: Boolean) = updateTextureWorkspace { it.copy(showOutlines = on) }
	fun setTextureShowPage(on: Boolean) = updateTextureWorkspace { it.copy(showPage = on) }
	fun setTextureShowMeshes(on: Boolean) = updateTextureWorkspace { it.copy(showMeshes = on) }
	fun setTextureArrangeOptions(byMesh: Boolean, selectionOnly: Boolean) =
		updateTextureWorkspace { it.copy(arrangeByMesh = byMesh, arrangeSelectionOnly = selectionOnly) }
	fun setTextureReplaceOptions(fit: io.github.psd2live.application.WorkspaceImageFit, rebuildMesh: Boolean) =
		updateTextureWorkspace { it.copy(replaceFit = fit, replaceRebuildMesh = rebuildMesh) }
	fun clearTextureError() = updateTextureWorkspace { it.copy(error = null) }

	// ---- Atlas edit session ----
	//
	// Moving, scaling and turning tiles edits a session, as painting edits a paint session: every gesture changes
	// only the tiles the atlas shows (TextureWorkspaceState.session), nothing rebuilds, and Apply commits all of it
	// as one batch - one rebuild, one history node. Discard drops it.

	/**
	 * Where [layerId]'s tile lands when dragged to ([x], [y]) on its page, among the tiles as the atlas shows them;
	 * the atlas view keeps it while the pointer moves, so a drag touches no application state until [moveTextureTile].
	 */
	fun draggedTextureTile(snapshot: TextureSnapshot, layerId: String, x: Float, y: Float): TileDragDraft? {
		val shown = _state.value.textureWorkspace.shown
		val tile = snapshot.tilesByLayer[layerId]?.shownAs(shown) ?: return null
		val page = snapshot.atlas.pages.getOrNull(tile.page) ?: return null
		val others = snapshot.shownTiles(tile.page, shown)
		return placeDraggedTile(tile, x, y, page.width, page.height) { ix, iy -> snapshot.collides(tile.copy(x = ix, y = iy), ix, iy, others) }
	}

	/**
	 * Whether [placed] - tiles by layer at the spots, sizes and turns a gesture would give them - would meet any other
	 * tile by their meshes' cells, or leave the page; the others as the atlas shows them. Such a placement is refused:
	 * applied, it would push a tile into free space, somewhere the session never showed it.
	 */
	fun texturePlacementCollides(snapshot: TextureSnapshot, placed: Map<String, io.github.psd2live.application.WorkspaceAtlasTile>): Boolean {
		val overlay = _state.value.textureWorkspace.shown
		val pages = placed.values.mapTo(HashSet()) { it.page }
		val shown = pages.flatMap { page -> snapshot.shownTiles(page, overlay) }.map { placed[it.layerId] ?: it }
		return placed.values.any { tile ->
			val page = snapshot.atlas.pages.getOrNull(tile.page)
			val box = io.github.psd2live.core.TileTurn.bounds(tile.x.toFloat(), tile.y.toFloat(), tile.width.toFloat(), tile.height.toFloat(), tile.rotation)
			page == null || box[0] < -1e-3f || box[1] < -1e-3f || box[2] > page.width + 1e-3f || box[3] > page.height + 1e-3f ||
				snapshot.collides(tile, tile.x, tile.y, shown)
		}
	}

	/**
	 * Puts [placed] into the session - tiles by layer as a gesture leaves them, their density included - unless one
	 * would then meet another tile's meshes ([texturePlacementCollides]); [refusal] says why then. One undo step.
	 */
	fun placeTextureTiles(snapshot: TextureSnapshot, placed: Map<String, io.github.psd2live.application.WorkspaceAtlasTile>,
	                      refusal: String = "texture.scale.collides"): Boolean {
		if (placed.isEmpty()) return false
		val overlay = _state.value.textureWorkspace.shown
		// A tile only newly meets another where it grows, moves or turns; a shrink in place never does.
		val changed = placed.filter { (id, tile) ->
			val shown = snapshot.tilesByLayer[id]?.shownAs(overlay)
			shown == null || tile.x != shown.x || tile.y != shown.y || tile.page != shown.page || tile.rotation != shown.rotation ||
				tile.width > shown.width || tile.height > shown.height
		}
		if (changed.isNotEmpty() && texturePlacementCollides(snapshot, changed)) {
			updateTextureWorkspace { it.copy(error = tr(refusal)) }
			return false
		}
		updateTextureWorkspace { texture ->
			texture.copy(error = null, sessionUndo = (texture.sessionUndo + listOf(texture.session)).takeLast(200), sessionRedo = emptyList(),
				session = texture.session + placed.mapValues { (_, tile) ->
					PendingTile(tile.page, tile.x, tile.y, tile.width, tile.height, tile.density, 0L, io.github.psd2live.project.normalizedRotation(tile.rotation))
				})
		}
		return true
	}

	/** Ends a tile drag: the tile stands where it was dropped, unless its meshes would meet another's there. */
	fun moveTextureTile(snapshot: TextureSnapshot, draft: TileDragDraft): Boolean {
		val tile = snapshot.tilesByLayer[draft.layerId]?.shownAs(_state.value.textureWorkspace.shown)
		if (tile == null || (tile.x == draft.x && tile.y == draft.y && tile.page == draft.page)) return false
		if (draft.collides) {
			updateTextureWorkspace { it.copy(error = tr("texture.drag.collides")) }
			return false
		}
		return placeTextureTiles(snapshot, mapOf(draft.layerId to tile.copy(page = draft.page, x = draft.x, y = draft.y)), "texture.drag.collides")
	}

	/** Turns [layerId]'s tile to [degrees] about its centre, unless its meshes would then meet another's. */
	fun rotateTextureTile(snapshot: TextureSnapshot, layerId: String, degrees: Float): Boolean =
		turnTextureTiles(snapshot, listOf(layerId)) { degrees }

	/**
	 * Turns each of [layerIds]' tiles to [turn] of the turn the atlas shows for it, about its centre, as one session
	 * step; refused when one would then meet another's meshes or leave the page.
	 */
	fun turnTextureTiles(snapshot: TextureSnapshot, layerIds: List<String>, turn: (Float) -> Float): Boolean {
		val overlay = _state.value.textureWorkspace.shown
		val placed = layerIds.mapNotNull { id ->
			val tile = snapshot.tilesByLayer[id]?.shownAs(overlay) ?: return@mapNotNull null
			val turned = io.github.psd2live.project.normalizedRotation(turn(tile.rotation))
			if (turned == tile.rotation) null else id to tile.copy(rotation = turned)
		}.toMap()
		return placeTextureTiles(snapshot, placed, "texture.rotate.collides")
	}

	/**
	 * Moves [layerIds]' tiles to [page], each at the first free spot from the top left that keeps it off the other
	 * tiles' meshes and inside the page, at its size and turn, as one session step. Tiles already on [page] stay.
	 * Refused, with nothing moved, when one finds no spot.
	 */
	fun moveTextureTilesToPage(snapshot: TextureSnapshot, layerIds: List<String>, page: Int): Boolean {
		if (page !in snapshot.atlas.pages.indices) return false
		val overlay = _state.value.textureWorkspace.shown
		val moving = layerIds.mapNotNull { snapshot.tilesByLayer[it]?.shownAs(overlay) }.filter { it.page != page }
		if (moving.isEmpty()) return false
		val size = snapshot.atlas.pages[page]
		val padding = snapshot.atlas.budget.padding
		val standing = snapshot.shownTiles(page, overlay).filter { tile -> moving.none { it.layerId == tile.layerId } }.toMutableList()
		fun box(tile: io.github.psd2live.application.WorkspaceAtlasTile) =
			io.github.psd2live.core.TileTurn.bounds(tile.x.toFloat(), tile.y.toFloat(), tile.width.toFloat(), tile.height.toFloat(), tile.rotation)
		val placed = LinkedHashMap<String, io.github.psd2live.application.WorkspaceAtlasTile>()
		// The largest first, as a packer would; the others fill around them.
		for (tile in moving.sortedByDescending { it.width.toLong() * it.height }) {
			// Where the tile's turned box starts relative to its upright corner.
			val own = box(tile.copy(x = 0, y = 0))
			// Spots against the page's top left edges and right of or below each standing tile's turned box.
			val edges = standing.map(::box)
			val xs = (listOf(0f) + edges.map { it[2] + padding }).map { kotlin.math.ceil(it - own[0]).toInt() }.distinct().sorted()
			val ys = (listOf(0f) + edges.map { it[3] + padding }).map { kotlin.math.ceil(it - own[1]).toInt() }.distinct().sorted()
			val spot = ys.asSequence().flatMap { y -> xs.asSequence().map { x -> x to y } }
				.filter { (x, y) -> x + own[2] <= size.width && y + own[3] <= size.height }
				.firstOrNull { (x, y) -> !snapshot.collides(tile.copy(page = page, x = x, y = y), x, y, standing) }
			if (spot == null) {
				updateTextureWorkspace { it.copy(error = tr("texture.page.full", page + 1)) }
				return false
			}
			val moved = tile.copy(page = page, x = spot.first, y = spot.second)
			placed[tile.layerId] = moved
			standing += moved
		}
		return placeTextureTiles(snapshot, placed, "texture.drag.collides")
	}

	/**
	 * Shows the atlas as if [factors] (layer id to density ratio) were applied, while the density slider is dragged;
	 * empty clears it.
	 */
	fun previewTextureDensity(factors: Map<String, Float>) {
		if (_state.value.textureWorkspace.densityPreview != factors) updateTextureWorkspace { it.copy(densityPreview = factors) }
	}

	/** [layerId]'s density as the atlas shows it: the session's, a queued one's, else the committed one; 1 by default. */
	fun shownTextureDensity(snapshot: TextureSnapshot, layerId: String): Float =
		_state.value.textureWorkspace.shown[layerId]?.density ?: snapshot.layer(layerId)?.override?.density ?: 1f

	/**
	 * [densities] by layer (null resets to 1) into the session, each tile growing or shrinking about its top left, or
	 * from [origins] where given (a corner drag keeps its opposite corner, so its tile moves too).
	 */
	private fun placeDensities(snapshot: TextureSnapshot, densities: Map<String, Float?>, origins: Map<String, Pair<Int, Int>> = emptyMap()): Boolean {
		val overlay = _state.value.textureWorkspace.shown
		val placed = densities.mapNotNull { (id, density) ->
			val committed = snapshot.tilesByLayer[id] ?: return@mapNotNull null
			val shown = committed.shownAs(overlay)
			val ratio = (density ?: 1f) / (snapshot.layer(id)?.override?.density ?: 1f)
			val (x, y) = origins[id] ?: (shown.x to shown.y)
			id to shown.copy(x = x, y = y, width = Math.round(committed.width * ratio).coerceAtLeast(1),
				height = Math.round(committed.height * ratio).coerceAtLeast(1), density = density ?: 1f)
		}.toMap()
		return placeTextureTiles(snapshot, placed)
	}

	/** Sets the texture density of [layerIds] in the session (null resets to 1). */
	fun setTextureDensity(snapshot: TextureSnapshot, layerIds: List<String>, density: Float?) {
		if (layerIds.isEmpty()) return
		placeDensities(snapshot, layerIds.associateWith { density })
		previewTextureDensity(emptyMap())
	}

	/**
	 * Scales the density of [layerIds] by [factor] in the session, as a corner handle drag or a "×2" button does: each
	 * keeps its own ratio to the others, from the density the atlas shows for it, with no steps. [origins] moves a
	 * tile to keep a corner drag's opposite corner where it was.
	 */
	fun scaleTextureDensity(snapshot: TextureSnapshot, layerIds: List<String>, factor: Float, origins: Map<String, Pair<Int, Int>> = emptyMap()) {
		if (layerIds.isEmpty() || !(factor > 0f) || factor == 1f) return
		val densities = layerIds.associateWith { id ->
			val shown = shownTextureDensity(snapshot, id)
			TextureDensity.clamp(shown * factor).takeIf { it != shown }
		}.filterValues { it != null }.mapValues { (_, density) -> density!!.takeUnless { it == 1f } }
		placeDensities(snapshot, densities, origins)
	}

	fun undoTextureSession() = updateTextureWorkspace { t ->
		if (t.sessionUndo.isEmpty()) t else t.copy(session = t.sessionUndo.last(), sessionUndo = t.sessionUndo.dropLast(1), sessionRedo = t.sessionRedo + listOf(t.session))
	}

	fun redoTextureSession() = updateTextureWorkspace { t ->
		if (t.sessionRedo.isEmpty()) t else t.copy(session = t.sessionRedo.last(), sessionRedo = t.sessionRedo.dropLast(1), sessionUndo = t.sessionUndo + listOf(t.session))
	}

	/** Drops the session: every tile shows as committed again. */
	fun discardTextureSession() = updateTextureWorkspace { it.copy(session = emptyMap(), sessionUndo = emptyList(), sessionRedo = emptyList(), error = null) }

	/**
	 * Applies the session: every density, spot and turn it changed, as one batch - one rebuild and one history node.
	 * The tiles keep showing where the session put them until that version lands.
	 */
	fun applyTextureSession() {
		val texture = _state.value.textureWorkspace
		val session = texture.session
		if (session.isEmpty()) return
		val snapshot = textureSnapshot() ?: return
		val densities = session.filter { (id, tile) -> (tile.density ?: 1f) != (snapshot.layer(id)?.override?.density ?: 1f) }
			.entries.groupBy({ it.value.density?.takeUnless { d -> d == 1f } }, { it.key })
		val pins = session.filter { (id, tile) ->
			val committed = snapshot.tilesByLayer[id] ?: return@filter false
			tile.page != committed.page || tile.x != committed.x || tile.y != committed.y || tile.rotation != committed.rotation
		}
		val operations = densities.map { (density, ids) ->
			io.github.psd2live.application.WorkspaceDocumentOperation("layer_set_pixel_density", kotlinx.serialization.json.buildJsonObject {
				put("layer_ids", kotlinx.serialization.json.JsonArray(ids.sorted().map { kotlinx.serialization.json.JsonPrimitive(it) }))
				put("density", density?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull)
			})
		} + pins.map { (id, tile) ->
			io.github.psd2live.application.WorkspaceDocumentOperation("atlas_set_tile", kotlinx.serialization.json.buildJsonObject {
				put("layer_id", kotlinx.serialization.json.JsonPrimitive(id))
				put("pin", kotlinx.serialization.json.buildJsonObject {
					put("page", kotlinx.serialization.json.JsonPrimitive(tile.page)); put("x", kotlinx.serialization.json.JsonPrimitive(tile.x)); put("y", kotlinx.serialization.json.JsonPrimitive(tile.y))
					if (tile.rotation != 0f) put("rotation", kotlinx.serialization.json.JsonPrimitive(tile.rotation))
				})
			})
		}
		updateTextureWorkspace { it.copy(session = emptyMap(), sessionUndo = emptyList(), sessionRedo = emptyList()) }
		if (operations.isEmpty()) return
		queueTextureJob(TextureJob.Batch(tr("texture.session.summary", session.size), operations)) { token -> session.mapValues { it.value.copy(token = token) } }
	}

	fun setTextureLock(snapshot: TextureSnapshot, layerIds: List<String>, lock: Boolean) {
		if (layerIds.isEmpty()) return
		val groups = layerIds.groupBy { snapshot.layer(it)?.override?.density }
		queueTextureEdits(groups.map { (density, ids) -> io.github.psd2live.application.WorkspaceTextureEdit.SetPixelDensity(ids, density, lock) })
	}

	fun setLayerCanvasRect(snapshot: TextureSnapshot, layerId: String, rect: io.github.psd2live.project.LayerCanvasRect) =
		queueTextureEdits(listOf(io.github.psd2live.application.WorkspaceTextureEdit.SetCanvasRect(layerId, rect)))

	/** Replaces [layerId]'s pixels from [file] with the workspace's fit and rebuild choices; the file is read by the command. */
	fun replaceLayerImage(snapshot: TextureSnapshot, layerId: String, file: java.io.File) {
		val options = _state.value.textureWorkspace
		queueTextureEdits(listOf(io.github.psd2live.application.WorkspaceTextureEdit.ReplaceImage(layerId,
			io.github.psd2live.application.WorkspaceTextureImage.File(file.toPath().toAbsolutePath()), options.replaceFit, options.replaceRebuildMesh)))
	}

	fun setAtlasBudget(snapshot: TextureSnapshot, pageSize: Int? = null, maxPages: Int? = null, padding: Int? = null) {
		val current = snapshot.atlas.budget
		if ((pageSize ?: current.pageSize) == current.pageSize && (maxPages ?: current.maxPages) == current.maxPages &&
			(padding ?: current.padding) == current.padding) return
		queueTextureEdits(listOf(io.github.psd2live.application.WorkspaceTextureEdit.SetBudget(pageSize, maxPages, padding)))
	}

	/**
	 * Arranges the atlas once with the workspace's options - by the meshes' footprints or the tile rectangles, all
	 * tiles or only [selection] - and keeps the result.
	 */
	fun arrangeAtlas(snapshot: TextureSnapshot, selection: List<String> = emptyList(),
	                 onlySelection: Boolean = _state.value.textureWorkspace.arrangeSelectionOnly) {
		val options = _state.value.textureWorkspace
		val only = selection.filter { it in snapshot.tilesByLayer }.takeIf { onlySelection && it.isNotEmpty() }
		queueTextureEdits(listOf(io.github.psd2live.application.WorkspaceTextureEdit.Pack(options.arrangeByMesh, only)))
	}

	/** Turns the automatic arrangement on (the atlas packs itself on every build) or off (it keeps its layout). */
	fun setAtlasAuto(snapshot: TextureSnapshot, auto: Boolean) {
		if (snapshot.atlas.auto == auto) return
		queueTextureEdits(listOf(io.github.psd2live.application.WorkspaceTextureEdit.SetBudget(auto = auto)))
	}

	/** The atlas budget back to its defaults; nothing is sent when it already is. */
	fun resetAtlasBudget(snapshot: TextureSnapshot) {
		val defaults = io.github.psd2live.core.AtlasBudget()
		setAtlasBudget(snapshot, defaults.pageSize, defaults.maxPages, defaults.padding)
	}

	/** Texture work waiting to commit: single texture commands, or an applied session as one document batch. */
	private sealed interface TextureJob {
		class Edits(val edits: List<io.github.psd2live.application.WorkspaceTextureEdit>) : TextureJob
		class Batch(val summary: String, val operations: List<io.github.psd2live.application.WorkspaceDocumentOperation>) : TextureJob
	}

	private class QueuedTextureJob(val token: Long, val job: TextureJob, val tiles: Set<String>)

	private val textureJobs = kotlinx.coroutines.channels.Channel<QueuedTextureJob>(kotlinx.coroutines.channels.Channel.UNLIMITED)
	private val textureTokens = java.util.concurrent.atomic.AtomicLong()
	private val texturesQueued = java.util.concurrent.atomic.AtomicInteger()
	private var textureWorker: kotlinx.coroutines.Job? = null

	private fun queueTextureEdits(edits: List<io.github.psd2live.application.WorkspaceTextureEdit>) {
		if (edits.isNotEmpty()) queueTextureJob(TextureJob.Edits(edits)) { emptyMap() }
	}

	/**
	 * Queues [job] to commit after every job queued before it; [shows] gives the tiles to show, from the job's token,
	 * until its version arrives. Nothing is refused for being busy: each runs on the version the previous one left,
	 * once no other edit holds the workspace.
	 */
	private fun queueTextureJob(job: TextureJob, shows: (Long) -> Map<String, PendingTile>) {
		val token = textureTokens.incrementAndGet()
		val tiles = shows(token)
		texturesQueued.incrementAndGet()
		updateTextureWorkspace { it.copy(busy = true, error = null, pending = if (tiles.isEmpty()) it.pending else it.pending + tiles) }
		textureJobs.trySend(QueuedTextureJob(token, job, tiles.keys))
		synchronized(textureJobs) {
			if (textureWorker?.isActive != true) textureWorker = scope.launch { for (queued in textureJobs) runTextureJob(queued) }
		}
	}

	private suspend fun runTextureJob(queued: QueuedTextureJob) {
		try {
			// Another kind of edit (a canvas gesture, a document command) finishes first; texture edits never interleave with it.
			_state.first { !it.workspaceEditBusy && !it.editorDraftBusy && !it.canvasEditBusy }
			updateState { it.copy(canvasEditBusy = true) }
			val backend = requireNotNull(workspaceBackend) { "Project workspace unavailable" }
			when (val job = queued.job) {
				is TextureJob.Edits -> for (edit in job.edits) commitTextureEditNow(backend.captureTextures().state, edit)
				is TextureJob.Batch -> {
					val state = backend.captureTextures().state
					val port: io.github.psd2live.application.WorkspaceDocumentPort = backend
					val projectId = backend.captureTextures().projectId
					withContext(Dispatchers.Default + io.github.psd2live.application.WorkspaceExecution(projectId, state, MutationAuthor.USER)) {
						port.applyDocumentEdits(state, job.summary, job.operations, MutationAuthor.USER)
					}
					updateTextureWorkspace { it.copy(revision = it.revision + 1) }
				}
			}
		} catch (failure: Exception) {
			if (failure is kotlinx.coroutines.CancellationException) throw failure
			val message = if (failure is io.github.psd2live.application.WorkspaceTileCollision) tr("texture.apply.collides", failure.layerIds.size)
				else failure.message ?: tr("texture.failed")
			updateTextureWorkspace { it.copy(error = message) }
		} finally {
			val left = texturesQueued.decrementAndGet()
			// A later job's tiles keep showing their own result; this job's show what landed (or fall back).
			updateState { s ->
				s.copy(canvasEditBusy = false, textureWorkspace = s.textureWorkspace.copy(busy = left > 0,
					pending = s.textureWorkspace.pending.filterNot { (id, tile) -> id in queued.tiles && tile.token == queued.token }))
			}
		}
	}

	/** Waits for every queued texture edit to commit; for tools and tests. */
	internal suspend fun awaitTextureEdits() {
		_state.first { !it.textureWorkspace.busy }
	}

	/** One texture command on [state] as the user, through the trusted execution context. */
	internal suspend fun commitTextureEditNow(state: String, edit: io.github.psd2live.application.WorkspaceTextureEdit):
		io.github.psd2live.application.WorkspaceTextureResult {
		val backend = requireNotNull(workspaceBackend) { "Project workspace unavailable" }
		val port: io.github.psd2live.application.WorkspaceTexturePort = backend
		val projectId = backend.captureTextures().projectId
		val result = withContext(Dispatchers.Default + io.github.psd2live.application.WorkspaceExecution(projectId, state, MutationAuthor.USER)) {
			port.editTexture(state, edit, MutationAuthor.USER)
		}
		updateTextureWorkspace { it.copy(revision = it.revision + 1) }
		return result
	}

	/** Writes [page] of the committed atlas to [file] as the export writes it; the status bar tells how it went. */
	fun saveAtlasPage(snapshot: TextureSnapshot, page: Int, file: java.io.File) {
		scope.launch {
			try {
				withContext(Dispatchers.IO) { file.writeBytes(snapshot.pagePng(page)) }
				updateState { it.copy(statusText = tr("texture.menu.savePage.done", file.name)) }
			} catch (failure: Exception) {
				if (failure is kotlinx.coroutines.CancellationException) throw failure
				updateTextureWorkspace { it.copy(error = tr("texture.menu.savePage.failed", failure.message ?: file.name)) }
			}
		}
	}

	fun openTextureUpscaleDialog() {
		updateState { it.copy(showTextureUpscaleDialog = true) }
	}

	fun closeTextureUpscaleDialog() {
		updateState {
			it.copy(
				showTextureUpscaleDialog = false,
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
		updateState { it.copy(keyCapture = KeyCapture(action, index)) }
	}

	fun cancelKeyCapture() {
		updateState { it.copy(keyCapture = null) }
	}

	/**
	 * Feeds one key event to the active capture. `Esc` abandons it; a refused chord is reported back
	 * through [KeyCapture.feedback] and recording continues.
	 */
	fun captureKeyEvent(event: KeyEvent) {
		if (_state.value.keyCapture == null) return
		if (event.key == Key.Escape) {
			cancelKeyCapture()
			return
		}
		if (isModifierKey(event.key)) return
		captureBinding(keyBindingOf(event))
	}

	/**
	 * Feeds one wheel notch or mouse button press to the active capture, as [captureKeyEvent] does a
	 * key. The settings panel forwards them from the cell being recorded.
	 */
	fun captureBinding(binding: KeyBinding) {
		val capture = _state.value.keyCapture ?: return
		val check = _state.value.keymap.validateCapture(capture.action, capture.index, binding)
		if (check != CaptureCheck.Ok) {
			updateState { it.copy(keyCapture = capture.copy(feedback = check)) }
			return
		}
		val updated = _state.value.keymap.bindingsFor(capture.action).toMutableList()
		if (capture.index < updated.size) updated[capture.index] = binding else updated.add(binding)
		applyBindings(capture.action, updated)
		cancelKeyCapture()
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
		updateState { it.copy(keymap = loadPersistedKeymap()) }
	}

	fun applyKeymapPreset(preset: KeymapPreset) {
		if (_state.value.keymapPreset == preset) return
		// An override is expressed relative to a base preset, so carrying it across a switch has no
		// defined meaning. Switching is therefore a full reset of the customisations.
		AppSettings.clearKeymap()
		AppSettings.keymapPreset = preset
		updateState {
			it.copy(keymapPreset = preset, keymap = Keymap.of(preset), keyCapture = null)
		}
	}

	/** Part of "Reset Defaults": back to the shipped Photoshop table with no customisations. */
	fun resetKeymap() {
		AppSettings.clearKeymap()
		updateState {
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
		updateState { it.copy(keymap = loadPersistedKeymap()) }
	}


	fun setActiveWorkspace(id: String) {
        if (_state.value.workspaceEditBusy) return
		val before = _state.value
		if (before.activeWorkspaceId == id || before.workspaces.none { it.id == id }) return
		// The workspace being left keeps the camera its canvases show, even one not written yet.
		val cameras = takePendingCameras(before, before.activeWorkspace.id)
		var changed = false
		updateState { current ->
			val target = current.workspaces.firstOrNull { it.id == id } ?: return@updateState current
			if (current.activeWorkspaceId == id) current
			else {
				changed = true
				if (target.canvases.none { it.mode == CanvasMode.PREVIEW && it.id !in target.hiddenModules }) {
					pointerActive = false
					stopProcessMotion()
				}
				// One update: the switch and the change mark land together.
				val switched = current.withPendingCameras(current.activeWorkspace.id, cameras).copy(activeWorkspaceId = id)
				if (switched.analysis == null) switched
				else switched.copy(projectDirty = true, projectEditVersion = switched.projectEditVersion + 1)
			}
		}
		if (changed && _state.value.previewLive) ensureSdkSessionLoaded()
	}

	/**
	 * A new workspace starts from [preset]'s canvases and arrangement. The name stays blank (it
	 * follows the preset's localized title) unless another workspace already shows that title.
	 */
	fun addWorkspace(preset: WorkspacePreset = WorkspacePreset.EDIT): String {
		val current = _state.value
		val title = preset.title()
		val taken = current.workspaces.map { it.displayName() }.toSet()
		val name = if (title !in taken) "" else
			generateSequence(2) { it + 1 }.map { "$title $it" }.first { it !in taken }
		val workspace = presetEditorWorkspace(java.util.UUID.randomUUID().toString(), preset, name)
		updateState { it.copy(workspaces = it.workspaces + workspace) }
		// Switching syncs the panel toggles and the preview session, and marks the change itself.
		setActiveWorkspace(workspace.id)
		if (_state.value.activeWorkspaceId != workspace.id) markWorkspaceChanged()
		return workspace.id
	}

	/** Shows a workspace of [preset]: the first one the user already has, else a new one. */
	fun openWorkspacePreset(preset: WorkspacePreset) {
		val existing = _state.value.workspaces.firstOrNull { it.preset == preset }
		if (existing != null) setActiveWorkspace(existing.id) else addWorkspace(preset)
	}

	/** Copies layout, panel visibility and canvases into a new workspace. */
	fun duplicateWorkspace(id: String = _state.value.activeWorkspace.id): String {
		val source = _state.value.workspaces.firstOrNull { it.id == id } ?: return addWorkspace()
		val workspace = source.copy(
			id = java.util.UUID.randomUUID().toString(),
			name = tr("workspace.copy", source.displayName()),
			placeModules = emptyList(),
		)
		updateState { it.copy(workspaces = it.workspaces + workspace, activeWorkspaceId = workspace.id) }
		markWorkspaceChanged()
        persistAuthoredPose()
		return workspace.id
	}

	fun closeWorkspace(id: String) = closeWorkspaces(listOf(id))

	/** Closes every workspace but [id]. */
	fun closeOtherWorkspaces(id: String) =
		closeWorkspaces(_state.value.workspaces.map { it.id }.filter { it != id })

	/** Closes the workspaces after [id] in strip order. */
	fun closeWorkspacesToRight(id: String) {
		val ids = _state.value.workspaces.map { it.id }
		val index = ids.indexOf(id)
		if (index >= 0) closeWorkspaces(ids.drop(index + 1))
	}

	/** Closes [ids], always keeping at least one workspace open. */
	fun closeWorkspaces(ids: Collection<String>) {
		val current = _state.value
		val closing = current.workspaces.filter { it.id in ids }
		if (closing.isEmpty()) return
		if (closing.size >= current.workspaces.size) {
			updateState { it.copy(statusText = tr("status.workspaceLast")) }
			return
		}
		// The skeleton belongs to the project, not the canvas: an edit open on a closing canvas is kept.
		val closingIds = closing.map { it.id }.toSet()
		synchronized(canvasEditors) { canvasEditors.filterKeys { it.first in closingIds } }.forEach { (key, editor) ->
			editor.commitSkeletonDraft()
			synchronized(canvasEditors) { canvasEditors.remove(key) }?.resetPaintSession()
		}
		val remaining = current.workspaces.filterNot { it.id in closingIds }
		val next = if (current.activeWorkspaceId !in closingIds) remaining.first { it.id == current.activeWorkspaceId } else {
			// The nearest open workspace before the active one, else the first after it.
			val index = current.workspaces.indexOfFirst { it.id == current.activeWorkspaceId }
			current.workspaces.take(index).lastOrNull { it.id !in closingIds }
				?: current.workspaces.drop(index + 1).firstOrNull { it.id !in closingIds }
				?: remaining.first()
		}
		updateState {
			it.copy(
				workspaces = remaining,
				activeWorkspaceId = next.id,
				statusText = if (closing.size == 1) tr("status.workspaceClosed", closing.single().displayName())
				else tr("status.workspacesClosed", closing.size),
			)
		}
		markWorkspaceChanged()
		if (_state.value.previewLive) ensureSdkSessionLoaded()
		else {
			pointerActive = false
			stopProcessMotion()
		}
	}

	/** Moves workspace [id] to [toIndex] in the strip; the active workspace stays active. */
	fun moveWorkspace(id: String, toIndex: Int) {
		var changed = false
		updateState { current ->
			val from = current.workspaces.indexOfFirst { it.id == id }
			val to = toIndex.coerceIn(0, current.workspaces.lastIndex)
			if (from < 0 || from == to) current
			else {
				changed = true
				val reordered = current.workspaces.toMutableList()
				reordered.add(to, reordered.removeAt(from))
				current.copy(workspaces = reordered)
			}
		}
		if (changed) markWorkspaceChanged()
	}

	fun renameWorkspace(id: String, name: String) {
		val trimmed = name.trim()
		var changed = false
		updateState { current ->
			val target = current.workspaces.firstOrNull { it.id == id } ?: return@updateState current
			if (target.name == trimmed) current
			else {
				changed = true
				current.updateWorkspace(id) { it.copy(name = trimmed) }
			}
		}
		if (changed) markWorkspaceChanged()
	}

	fun cycleWorkspace(delta: Int) {
		val workspaces = _state.value.workspaces
		if (workspaces.size < 2) return
		val index = workspaces.indexOfFirst { it.id == _state.value.activeWorkspaceId }.coerceAtLeast(0)
		val next = ((index + delta) % workspaces.size + workspaces.size) % workspaces.size
		setActiveWorkspace(workspaces[next].id)
	}

	fun activateWorkspaceByIndex(index: Int) {
		_state.value.workspaces.getOrNull(index)?.let { setActiveWorkspace(it.id) }
	}

	fun focusCanvas(canvasId: String) {
		updateState { current ->
			val workspace = current.activeWorkspace
			if (workspace.activeCanvasId == canvasId || workspace.canvases.none { it.id == canvasId }) current
			else current.updateActiveWorkspace { it.copy(activeCanvasId = canvasId) }
		}
	}

	internal fun requestCanvasFocus(canvasId: String) {
		focusCanvas(canvasId)
		updateState { it.copy(focusCanvasRequest = it.focusCanvasRequest + 1) }
	}

	/**
	 * Switches [canvasId] between editing and preview. The camera goes along: both modes look at the
	 * model through it, so a switch never moves the view. A camera the canvas holds but has not written
	 * yet is taken first, in the same update.
	 */
	fun setCanvasMode(canvasId: String, mode: CanvasMode) {
		val before = _state.value
		val switching = before.activeWorkspace.canvases.firstOrNull { it.id == canvasId }?.let { it.mode != mode } == true
		val pending = if (!switching) null else pendingCanvasCameras.remove(
			pendingCameraKey(before.projectOpenGeneration, before.activeWorkspace.id, canvasId))
		var changed = false
		updateState { current ->
			val canvas = current.activeWorkspace.canvases.firstOrNull { it.id == canvasId } ?: return@updateState current
			if (canvas.mode == mode) current.updateActiveWorkspace { it.copy(activeCanvasId = canvasId) }
			else {
				changed = true
				val camera = pending ?: canvas.camera
				val next = current.updateActiveWorkspace { workspace ->
					workspace.copy(
						activeCanvasId = canvasId,
						canvases = workspace.canvases.map { pane ->
							if (pane.id == canvasId) pane
								.updateSession(pane.mode) { it.copy(camera = camera) }
								.updateSession(mode) { it.copy(camera = camera) }
								.copy(mode = mode)
							else pane
						},
					)
				}
				if (next.analysis == null) next else next.copy(projectDirty = true, projectEditVersion = next.projectEditVersion + 1)
			}
		}
		if (mode == CanvasMode.PREVIEW) ensureSdkSessionLoaded()
		if (!_state.value.previewLive) {
			pointerActive = false
			stopProcessMotion()
		}
	}

	/** Docks another canvas. [focus] makes it the one zoom shortcuts and the editor follow. */
	fun addCanvas(mode: CanvasMode, focus: Boolean = true): String {
		val id = "canvas:${java.util.UUID.randomUUID()}"
		val pane = CanvasWindowState(id = id, mode = mode)
		updateState { current ->
			current.updateActiveWorkspace { workspace ->
				workspace.copy(
					canvases = workspace.canvases + pane,
					activeCanvasId = if (focus) id else workspace.activeCanvasId,
				)
			}
		}
		markWorkspaceChanged()
		if (mode == CanvasMode.PREVIEW) ensureSdkSessionLoaded()
		return id
	}

	fun closeCanvas(canvasId: String) {
		val current = _state.value
		val workspace = current.activeWorkspace
		if (workspace.canvases.none { it.id == canvasId }) return
		if (workspace.canvases.size <= 1) {
			updateState { it.copy(statusText = tr("status.canvasLast")) }
			return
		}
		synchronized(canvasEditors) { canvasEditors[workspace.id to canvasId] }?.commitSkeletonDraft()
		updateState { state ->
			state.updateActiveWorkspace { ws ->
				val remaining = ws.canvases.filterNot { it.id == canvasId }
				ws.copy(
					canvases = remaining,
					activeCanvasId = if (ws.activeCanvasId == canvasId) remaining.first().id else ws.activeCanvasId,
					hiddenModules = ws.hiddenModules - canvasId,
				)
			}
		}
		synchronized(canvasEditors) { canvasEditors.remove(workspace.id to canvasId) }?.resetPaintSession()
		markWorkspaceChanged()
		if (!_state.value.previewLive) {
			pointerActive = false
			stopProcessMotion()
		}
	}

	/**
	 * Shows or hides one dock module in the active workspace.
	 * Showing a module that is not in the layout asks the dock to place it.
	 */
	fun setModuleVisible(module: String, visible: Boolean) {
		updateState { current ->
			current.updateActiveWorkspace { workspace ->
				val hidden = if (visible) workspace.hiddenModules - module else workspace.hiddenModules + module
				val place = if (visible) (workspace.placeModules + module).distinct() else workspace.placeModules
				workspace.copy(hiddenModules = hidden, placeModules = place)
			}
		}
		markWorkspaceChanged()
		if (isCanvasModule(module) && !_state.value.previewLive) {
			pointerActive = false
			stopProcessMotion()
		}
	}

	fun showHistoryModule() = setModuleVisible("history", true)

	/**
	 * Hides the panels docked on [side] of the canvases, or shows them again. Hiding remembers which
	 * were shown; showing brings those back, or else what the preset shows there, or else all of them.
	 */
	fun toggleSidebar(side: SidebarSide) {
		val workspace = _state.value.activeWorkspace
		val modules = workspace.sidebars()[side] ?: return
		val shown = modules.filter { it !in workspace.hiddenModules }
		val next = if (shown.isNotEmpty()) {
			workspace.copy(
				hiddenModules = workspace.hiddenModules + modules,
				sidebarRestore = workspace.sidebarRestore + (side.name to shown.toSet()),
			)
		} else {
			val restore = workspace.sidebarRestore[side.name].orEmpty().intersect(modules.toSet())
				.ifEmpty { (modules - workspace.preset.hiddenModules).toSet() }
				.ifEmpty { modules.toSet() }
			workspace.copy(
				hiddenModules = workspace.hiddenModules - restore,
				sidebarRestore = workspace.sidebarRestore - side.name,
			)
		}
		updateState { current -> current.updateWorkspace(workspace.id) { next } }
		markWorkspaceChanged()
	}

	fun setPlaceModules(modules: List<String>) {
		updateState { current ->
			if (current.activeWorkspace.placeModules == modules) current
			else current.updateActiveWorkspace { it.copy(placeModules = modules) }
		}
	}

	/** Remembers the dock tree. Called on a debounce from the dock, so it does not bump the edit version twice per pixel. */
	fun setWorkspaceLayout(workspaceId: String, layoutJson: String) {
		var changed = false
		updateState { current ->
			val workspace = current.workspaces.firstOrNull { it.id == workspaceId } ?: return@updateState current
			if (workspace.layoutJson == layoutJson) current
			else {
				changed = true
				current.updateWorkspace(workspaceId) { it.copy(layoutJson = layoutJson) }
			}
		}
		if (changed) markWorkspaceChanged()
	}

	/** Returns the active workspace to its preset's arrangement; its canvases are kept. */
	fun resetWorkspaceArrangement() {
		updateState { current ->
			current.updateActiveWorkspace {
				it.copy(layoutJson = null, placeModules = emptyList(), hiddenModules = it.preset.hiddenModules, sidebarRestore = emptyMap())
			}
		}
		markWorkspaceChanged()
	}

	/** Focus an existing visible edit canvas without changing the workspace layout. */
	fun ensureEditCanvas() {
		val workspace = _state.value.activeWorkspace
		val existing = workspace.activeCanvas.takeIf { it.mode == CanvasMode.EDIT && it.id !in workspace.hiddenModules }
			?: workspace.canvases.firstOrNull { it.mode == CanvasMode.EDIT && it.id !in workspace.hiddenModules }
		if (existing != null) focusCanvas(existing.id)
	}

	/** Makes sure a preview canvas is on screen. [focus] selects it. */
	fun ensurePreviewCanvas(focus: Boolean = false) {
		val workspace = _state.value.activeWorkspace
		val existing = workspace.canvases.firstOrNull { it.mode == CanvasMode.PREVIEW }
		if (existing != null) {
			if (existing.id in workspace.hiddenModules) setModuleVisible(existing.id, true)
			if (focus) focusCanvas(existing.id)
			ensureSdkSessionLoaded()
		} else {
			addCanvas(CanvasMode.PREVIEW, focus = focus)
		}
	}

	/**
	 * Shows a canvas of [mode] and focuses it. A hidden canvas is reused before a new one is added,
	 * switching its mode when none has [mode], so a blank workspace does not keep an unused canvas.
	 */
	fun showCanvas(mode: CanvasMode) {
		val workspace = _state.value.activeWorkspace
		val hidden = workspace.canvases.filter { it.id in workspace.hiddenModules }
		val canvas = workspace.canvases.firstOrNull { it.mode == mode && it.id !in workspace.hiddenModules }
			?: hidden.firstOrNull { it.mode == mode }
			?: hidden.firstOrNull()
			?: run {
				addCanvas(mode)
				return
			}
		if (canvas.id in workspace.hiddenModules) setModuleVisible(canvas.id, true)
		if (canvas.mode != mode) setCanvasMode(canvas.id, mode) else focusCanvas(canvas.id)
		if (mode == CanvasMode.PREVIEW) ensureSdkSessionLoaded()
	}

	fun canvasTitle(canvas: CanvasWindowState, workspace: EditorWorkspace = _state.value.activeWorkspace): String {
		val index = workspace.canvases.indexOfFirst { it.id == canvas.id }
		val mode = tr(if (canvas.mode == CanvasMode.EDIT) "tab.edit" else "tab.preview")
		val base = tr("dock.canvas")
		return if (workspace.canvases.size > 1 && index >= 0) "$base ${index + 1} · $mode" else "$base · $mode"
	}

	/** Applies view toggles to one canvas. Defaults to the canvas the editor is following. */
	fun setCanvasViewOptions(
		canvasId: String = _state.value.activeCanvas.id,
		options: TabViewOptions,
		mode: CanvasMode? = null,
	) {
		val normalized = options.normalized()
		var changed = false
		updateState { current ->
			val canvas = current.activeWorkspace.canvases.firstOrNull { it.id == canvasId } ?: return@updateState current
			val targetMode = mode ?: canvas.mode
			if (canvas.session(targetMode).view == normalized) current
			else {
				changed = true
				current.updateCanvas(canvasId) { it.updateSession(targetMode) { session -> session.copy(view = normalized) } }
			}
		}
		if (changed) markWorkspaceChanged()
	}

	/**
	 * Swaps the edit session's display toggles to [mode]'s own set. Each hierarchy mode remembers
	 * what the user left it with; a mode's preset only seeds its first visit.
	 */
    fun switchHierarchyModeView(mode: EditHierarchyMode, canvasId: String = state.value.activeCanvas.id,
        workspaceId: String = state.value.activeWorkspace.id) {
        // Re-entering the mode the view already belongs to is common (a mode menu pick, a tutorial step); skip the update.
        val session = _state.value.workspaces.firstOrNull { it.id == workspaceId }
            ?.canvases?.firstOrNull { it.id == canvasId }?.editSession ?: return
        if (session.withHierarchyView(mode) == session) return
        updateState { current ->
            current.updateWorkspace(workspaceId) { workspace ->
                workspace.copy(canvases = workspace.canvases.map {
                    if (it.id == canvasId) it.updateSession(CanvasMode.EDIT) { session ->
                        session.withHierarchyView(mode)
                    } else it
                })
            }
        }
    }

	/** Rewrites one canvas's edit-session display toggles and returns what they were before. */
	fun updateEditViewOptions(canvasId: String, workspaceId: String, transform: (TabViewOptions) -> TabViewOptions): TabViewOptions? {
		var before: TabViewOptions? = null
		updateState { current ->
			current.updateWorkspace(workspaceId) { workspace ->
				workspace.copy(canvases = workspace.canvases.map {
					if (it.id == canvasId) it.updateSession(CanvasMode.EDIT) { session ->
						before = session.view
						session.copy(view = transform(session.view).normalized())
					} else it
				})
			}
		}
		return before
	}

	/** Restores one canvas's display options to the defaults for its mode. */
	fun resetCanvasViewOptions(canvasId: String = _state.value.activeCanvas.id, mode: CanvasMode? = null) {
		var changed = false
		updateState { current ->
			val canvas = current.activeWorkspace.canvases.firstOrNull { it.id == canvasId } ?: return@updateState current
			val targetMode = mode ?: canvas.mode
			val defaults = targetMode.defaultViewOptions()
			if (canvas.session(targetMode).view == defaults) current
			else {
				changed = true
				current.updateCanvas(canvasId) { it.updateSession(targetMode) { session -> session.copy(view = defaults) } }
			}
		}
		if (changed) markWorkspaceChanged()
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
		updateState { current ->
			current.copy(logEntries = current.logEntries.appendingLog(listOf(entry)))
		}
	}

	fun clearLogs() {
		updateState { it.copy(logEntries = emptyList()) }
	    markWorkspaceChanged()
	}

	private fun PSD2LiveState.withLog(
		message: String,
		level: LogLevel = LogLevel.INFO,
		tag: String = "System",
	): PSD2LiveState {
		val entry = AppLogEntry(source = LogSource.SYSTEM, level = level, tag = tag, message = message)
		return copy(logEntries = logEntries.appendingLog(listOf(entry)))
	}

	private fun PSD2LiveState.withLogs(
		messages: List<String>,
		level: LogLevel = LogLevel.INFO,
		tag: String = "System",
	): PSD2LiveState {
		val entries = messages.map { AppLogEntry(source = LogSource.SYSTEM, level = level, tag = tag, message = it) }
		return copy(logEntries = logEntries.appendingLog(entries))
	}

	fun setLogPanelExpanded(expanded: Boolean) {
		updateState { current ->
			val hidden = current.activeWorkspace.hiddenModules.toMutableSet().apply {
				if (expanded) remove("log") else add("log")
			}
			if (current.activeWorkspace.hiddenModules == hidden) current
			else current.updateActiveWorkspace { it.copy(hiddenModules = hidden) }
		}
	    markWorkspaceChanged()
	}

	fun setLogPanelHeight(height: Float) {
		val clamped = height.coerceIn(80f, 450f)
		var changed = false
		updateState {
			if (it.logPanelHeight == clamped) it
			else {
				changed = true
				it.copy(logPanelHeight = clamped)
			}
		}
		if (changed) markWorkspaceChanged()
	}

	fun openLightbox(imageBytes: ByteArray, title: String? = null) {
		updateState { it.copy(lightboxImage = imageBytes, lightboxTitle = title) }
	}

	fun closeLightbox() {
		updateState { it.copy(lightboxImage = null, lightboxTitle = null) }
	}

	fun updateHistorySnapshot(snapshot: WorkspaceHistorySnapshot) {
		updateState { it.copy(historySnapshot = snapshot, projectDirty = it.projectDirty || (it.historySnapshot != null && it.historySnapshot.headNodeId != snapshot.headNodeId), projectEditVersion = it.projectEditVersion + if (it.historySnapshot?.headNodeId != snapshot.headNodeId) 1 else 0) }
	}

	fun selectHistoryNode(nodeId: String?) {
		updateState { it.copy(selectedHistoryNodeId = nodeId) }
	    markWorkspaceChanged()
	}

	fun checkoutHistoryNode(nodeId: String) {
		scope.launch {
			try {
				val ws = workspaceBackend ?: throw IllegalStateException("Agent workspace is not attached")
				val result = withContext(Dispatchers.Default) {
					ws.checkoutHistory(nodeId, io.github.psd2live.project.MutationAuthor.USER)
				}
				// The workspace already logged the checkout; this only reflects it in the status bar.
				updateState { current ->
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
				updateState { it.copy(errorMessage = err) }
			}
		}
	}

	/** Runs or stops the generated idle on the workspace session; its frame switches every view. */
	fun setAnimationEnabled(enabled: Boolean) {
		if (!configurePlayback("animation", kotlinx.serialization.json.buildJsonObject { put("enabled", enabled) })) {
			if (workspaceBackend != null && currentWorkspaceState() != null) return
			// No session yet (no project): only the projection can show the switch.
			if (enabled) motionEditor.playing = false
			val current = _state.value
			updateCanvasPresentation(current.activeWorkspace.id, current.previewControlCanvas().id, CanvasMode.PREVIEW) {
				it.copy(animationEnabled = enabled)
			}
		}
		lastTick = System.nanoTime()
	    markWorkspaceChanged()
	}


	fun setParameterSearchQuery(query: String) {
		updateState { it.copy(parameterSearchQuery = query) }
	    markWorkspaceChanged()
	}

	/** Last plain click. Shift-range stays anchored here until the next replace or toggle. */
	private var selectionAnchorId: String? = null

	internal fun noteSelectionAnchor(layerId: String?) {
		selectionAnchorId = layerId
	}

	/** Plain click replaces, Shift extends, Alt removes the focused canvas's layer set. */
	fun selectLayer(layerId: String?, additive: Boolean = false, subtractive: Boolean = false) {
		if (layerId != null && tryApplyClipMaskPick(layerId)) return
		updateState { current ->
			val previous = LinkedHashSet(current.selectedLayerIds.ifEmpty { setOfNotNull(current.selectedLayerId) })
			val selected = when {
				layerId == null -> LinkedHashSet()
				subtractive -> LinkedHashSet(previous.filter { it != layerId })
				additive -> LinkedHashSet(previous).apply { add(layerId) }
				else -> LinkedHashSet<String>().apply { add(layerId) }
			}
			current.copy(
				selectedLayerId = if (subtractive) current.selectedLayerId?.takeIf { it in selected }
					?: selected.lastOrNull() else layerId,
				selectedLayerIds = selected,
				selectedDeformerId = if (selected.isNotEmpty()) null else current.selectedDeformerId,
			)
		}
		when {
			layerId == null && !additive && !subtractive -> selectionAnchorId = null
			!additive && !subtractive -> selectionAnchorId = layerId
			additive && layerId != null -> selectionAnchorId = layerId
			subtractive && selectionAnchorId == layerId -> selectionAnchorId = _state.value.selectedLayerId
		}
	    markWorkspaceChanged()
	}

	/** Selects [layerIds] at once, as a marquee does: they replace the selection unless [additive]. */
	fun selectLayers(layerIds: Collection<String>, additive: Boolean = false) {
		if (layerIds.isEmpty()) {
			if (!additive) selectLayer(null)
			return
		}
		updateState { current ->
			val selected = LinkedHashSet<String>()
			if (additive) selected += current.selectedLayerIds.ifEmpty { setOfNotNull(current.selectedLayerId) }
			selected += layerIds
			current.copy(selectedLayerId = layerIds.last(), selectedLayerIds = selected, selectedDeformerId = null)
		}
		selectionAnchorId = layerIds.last()
		markWorkspaceChanged()
	}

	/** Takes [layerIds] out of the selection, as an Alt box does. */
	fun deselectLayers(layerIds: Collection<String>) {
		val removed = layerIds.toSet()
		if (removed.isEmpty()) return
		updateState { current ->
			val selected = LinkedHashSet(current.selectedLayerIds.ifEmpty { setOfNotNull(current.selectedLayerId) }.filter { it !in removed })
			current.copy(selectedLayerId = current.selectedLayerId?.takeIf { it in selected } ?: selected.lastOrNull(), selectedLayerIds = selected)
		}
		if (selectionAnchorId in removed) selectionAnchorId = _state.value.selectedLayerId
		markWorkspaceChanged()
	}

	fun beginClipMaskPick(sourceDrawableId: String) {
		val current = _state.value
		val valid = current.previewModel?.rig?.puppet?.drawables?.any { it.id.raw == sourceDrawableId } == true
		if (!valid) return
		updateState {
			it.copy(
				clipMaskPickSourceId = if (it.clipMaskPickSourceId == sourceDrawableId) null else sourceDrawableId,
				statusText = if (it.clipMaskPickSourceId == sourceDrawableId) tr("inspector.clipPickCancelled") else tr("inspector.clipPickPrompt"),
			)
		}
	}

	/** Consumes a layer selection while the Inspector is waiting for a clipping-mask target. */
	internal fun tryApplyClipMaskPick(layerId: String): Boolean {
		val current = _state.value
		val sourceId = current.clipMaskPickSourceId ?: return false
		val preview = current.previewModel ?: return false
		val pickedId = preview.rig.layerIdByDrawableId.entries.firstOrNull { it.value == layerId }?.key ?: layerId
		val source = preview.rig.puppet.drawables.firstOrNull { it.id.raw == sourceId }
		val picked = preview.rig.puppet.drawables.firstOrNull { it.id.raw == pickedId }
		if (source == null) {
			updateState { it.copy(clipMaskPickSourceId = null) }
			return false
		}
		if (picked == null || picked.id == source.id) {
			updateState { it.copy(statusText = tr("inspector.clipPickInvalid")) }
			return true
		}
		val masks = (source.maskedBy + picked.id).distinct()
		updateState { it.copy(clipMaskPickSourceId = null, statusText = tr("inspector.clipPickApplied", picked.name)) }
		applyRigStaticNow("mesh", sourceId, "masked_by" to kotlinx.serialization.json.JsonArray(masks.map { kotlinx.serialization.json.JsonPrimitive(it.raw) }))
		return true
	}

	/**
	 * Selects every layer from the anchor through [clickedId] in [orderedLayerIds].
	 * The anchor stays put, and [clickedId] becomes the primary item.
	 */
	fun selectLayerRange(orderedLayerIds: List<String>, clickedId: String) {
		val anchor = selectionAnchorId ?: _state.value.selectedLayerId
		val range = layerSelectionRange(orderedLayerIds, anchor, clickedId)
		updateState { current ->
			current.copy(
				selectedLayerId = clickedId,
				selectedLayerIds = range.toCollection(LinkedHashSet()),
				selectedDeformerId = null,
			)
		}
		markWorkspaceChanged()
	}

	/** Ctrl-click: add the layer, or drop it when it is already selected. */
	fun toggleLayerSelection(layerId: String) {
		val selected = _state.value.selectedLayerIds.ifEmpty { setOfNotNull(_state.value.selectedLayerId) }
		if (layerId in selected) selectLayer(layerId, subtractive = true)
		else selectLayer(layerId, additive = true)
	}

	private fun selectOnCanvas(workspaceId: String, canvasId: String, mode: CanvasMode, layerId: String?) {
		updateCanvasPresentation(workspaceId, canvasId, mode) {
			it.copy(
				selectedLayerId = layerId,
				selectedLayerIds = setOfNotNull(layerId),
				selectedDeformerId = if (layerId != null) null else it.selectedDeformerId,
			)
		}
		markWorkspaceChanged()
	}

	fun selectDeformer(deformerId: String?) {
		updateState {
			it.copy(
				selectedDeformerId = deformerId,
				selectedLayerId = if (deformerId != null) null else it.selectedLayerId,
				selectedLayerIds = if (deformerId != null) emptySet() else it.selectedLayerIds,
			)
		}
	    markWorkspaceChanged()
	}

	fun setAutoDetectMeshSplitsOnImport(enabled: Boolean) = setPromptEnabled(AppPrompt.START_SCREEN_ON_IMPORT, enabled)

	fun setHoveredItem(layerId: String?, deformerId: String?) {
		updateState {
			if (it.hoveredLayerId == layerId && it.hoveredDeformerId == deformerId) it
			else it.copy(hoveredLayerId = layerId, hoveredDeformerId = deformerId)
		}
	}

	fun setDeformerVisibility(deformerId: String, visible: Boolean) =
		editCanvasVisibility(io.github.psd2live.application.CanvasVisibilityIntent.Deformers(mapOf(deformerId to visible)))

	fun toggleLayerVisibility(layerId: String) {
		val current = _state.value.isLayerVisible(layerId)
		setLayerVisibility(layerId, !current)
	}

	fun setLayerVisibility(layerId: String, visible: Boolean) =
		editCanvasVisibility(io.github.psd2live.application.CanvasVisibilityIntent.Layers(mapOf(layerId to visible)))

	fun setAllLayersVisibility(visible: Boolean) {
		if (_state.value.analysis == null) return
		editCanvasVisibility(io.github.psd2live.application.CanvasVisibilityIntent.AllLayers(visible))
	}

	fun invertLayerVisibility() {
		if (_state.value.analysis == null) return
		editCanvasVisibility(io.github.psd2live.application.CanvasVisibilityIntent.InvertLayers)
	}

	fun isolateLayer(layerId: String) {
		if (_state.value.analysis == null) return
		editCanvasVisibility(io.github.psd2live.application.CanvasVisibilityIntent.ToggleSolo(layerId))
	}

	/** The focused canvas session's visibility goes through the same processor and CAS as canvas_visibility. */
	private fun editCanvasVisibility(intent: io.github.psd2live.application.CanvasVisibilityIntent) {
		val current = _state.value
		val address = io.github.psd2live.application.CanvasAddress(current.activeWorkspace.id, current.activeCanvas.id,
			current.activeCanvas.mode.canvasViewMode())
		val port: io.github.psd2live.application.WorkspaceCanvasVisibilityPort? = workspaceBackend
		val state = currentWorkspaceState()
		if (port != null && state != null) {
			try { port.editCanvasVisibility(state, address, intent) }
			catch (failure: Exception) { setErrorMessage(failure.message) }
			return
		}
		// Without a loaded workspace there is no CAS; the same processor edits this session directly.
		val scope = current.previewModel?.let { io.github.psd2live.application.CanvasVisibilityScope.of(it) }
		val next = try {
			io.github.psd2live.application.CanvasVisibilityProcessor.apply(CanvasPresentation.capture(current).canvasVisibility(), intent, scope)
		} catch (failure: IllegalArgumentException) { setErrorMessage(failure.message); return }
		updateState {
			it.copy(layerVisibility = next.layers, deformerVisibility = next.deformers, isolatedLayerId = next.isolatedLayerId,
				isolationSnapshot = next.isolationSnapshot, statusText = tr("status.visibilityChanged"))
		}
		markWorkspaceChanged()
	}

	/**
	 * Layers a command just created become visible on the edit canvas that asked for them, through the same
	 * auxiliary CAS as the eye, on the state that command committed. A newer edit or a closed canvas skips it.
	 * Returns the state to continue from, since a reveal publishes a new one.
	 */
	private fun revealCanvasLayers(state: String?, workspaceId: String, canvasId: String, layerIds: Collection<String>): String? {
		val port: io.github.psd2live.application.WorkspaceCanvasVisibilityPort = workspaceBackend ?: return state
		if (state == null || layerIds.isEmpty()) return state
		val address = io.github.psd2live.application.CanvasAddress(workspaceId, canvasId, CanvasMode.EDIT.canvasViewMode())
		return try { port.editCanvasVisibility(state, address, io.github.psd2live.application.CanvasVisibilityIntent.Layers(layerIds.associateWith { true })).state }
		catch (_: IllegalStateException) { state } catch (_: IllegalArgumentException) { state }
	}

	/** Projects a committed canvas record into its own session; other canvases and the document stay as they are. */
	internal fun applyCanvasVisibility(expected: PSD2LiveState, address: io.github.psd2live.application.CanvasAddress,
	                                   value: io.github.psd2live.application.CanvasVisibility, changed: Boolean) = synchronized(stateLock) {
		val current = _state.value
		check(current.projectId == expected.projectId && current.projectOpenGeneration == expected.projectOpenGeneration) {
			"Workspace changed while canvas visibility was being prepared"
		}
		val mode = if (address.mode == io.github.psd2live.application.CanvasViewMode.EDIT) CanvasMode.EDIT else CanvasMode.PREVIEW
		updateCanvasPresentation(address.workspaceId, address.canvasId, mode) {
			it.copy(layerVisibility = value.layers, deformerVisibility = value.deformers,
				isolatedLayerId = value.isolatedLayerId, isolationSnapshot = value.isolationSnapshot)
		}
		if (changed) updateState {
			it.copy(statusText = tr("status.visibilityChanged"), projectDirty = it.projectDirty || it.analysis != null,
				projectEditVersion = it.projectEditVersion + 1, projectAuxiliaryVersion = it.projectAuxiliaryVersion + 1)
		}
	}

	fun deleteLayer(layerId: String) {
		if (workspaceBackend != null) {
            val port: io.github.psd2live.application.WorkspaceSourcePort = requireNotNull(workspaceBackend)
            runWorkspaceCommand { state -> port.softDeleteLayer(layerId, state) }
            return
        }
		val analysis = _state.value.analysis
		val layerName = analysis?.layers?.firstOrNull { it.source.id.raw == layerId }?.source?.name ?: layerId
		updateState { current ->
			current.copy(
				deletedLayerIds = current.deletedLayerIds + layerId,
				selectedLayerId = if (current.selectedLayerId == layerId) null else current.selectedLayerId,
				statusText = tr("status.layerDeleted", layerName),
			)
		}
		schedulePreviewRebuild()
	    editorChanged()
	}

	fun restoreAllDeletedLayers() {
		if (workspaceBackend != null) {
            val port: io.github.psd2live.application.WorkspaceSourcePort = requireNotNull(workspaceBackend)
            runWorkspaceCommand { state -> port.restoreDeletedLayers(null, state) }
            return
        }
		updateState { current ->
			current.copy(
				deletedLayerIds = emptySet(),
				statusText = tr("status.allLayersRestored"),
			)
		}
		schedulePreviewRebuild()
	    editorChanged()
	}

    private fun meshSettingFields(settings: MeshSettings) = kotlinx.serialization.json.buildJsonObject {
        put("outerMargin", settings.outerMargin); put("edgeMode", settings.edgeMode.name); put("edgeWidth", settings.edgeWidth)
        put("maxEdgeDistance", settings.maxEdgeDistance); put("interiorDensity", settings.interiorDensity)
        put("fillAlgorithm", settings.fillAlgorithm.name); put("suppressBoundaryDiagonals", settings.suppressBoundaryDiagonals)
        put("fillParameters", io.github.psd2live.project.WorkspaceSettingsCodec.encodeFillParameters(settings.fillParameters))
    }

    private fun runWorkspaceCommand(after: suspend () -> Unit = {}, action: suspend (String) -> WorkspaceMutationResult) {
        // The command would act on a state the running edit is about to replace; say so rather than drop it.
        if (_state.value.workspaceEditBusy) { setErrorMessage(tr("error.workspaceCommandBusy")); return }
        flushEditorFields()
        val workspace = requireNotNull(workspaceBackend)
        val expected = workspace.snapshot()
        updateState { it.copy(canvasEditBusy = true, errorMessage = null) }
        scope.launch {
            try {
                val settled = workspace.settleEditorDrafts(requireNotNull(expected.projectId), expected.state)
                withContext(Dispatchers.Default + io.github.psd2live.application.WorkspaceExecution(expected.projectId, settled, MutationAuthor.USER)) {
                    action(settled)
                }
                after()
            } catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                updateState { it.copy(errorMessage = failure.message) }
            } finally { updateState { it.copy(canvasEditBusy = false) } }
        }
    }
	/** A hierarchy drag commits one structure journal edit; old v1 parentOverrides are never rewritten. */
	fun reparentItem(childId: String, newParentId: String?) {
		val puppet = _state.value.previewModel?.rig?.puppet ?: return
		val edit = try { io.github.psd2live.application.WorkspaceHierarchyEdits.reparent(puppet, childId, newParentId) }
			catch (failure: IllegalArgumentException) { setErrorMessage(failure.message); return }
		if (edit == null) return
		val port: io.github.psd2live.application.WorkspaceRigPort = workspaceBackend ?: return
		runWorkspaceCommand(after = { updateState { it.copy(statusText = tr("status.hierarchyUpdated")) } }) { state ->
			port.authorRig(state, io.github.psd2live.application.WorkspaceHierarchyEdits.journal(edit), MutationAuthor.USER)
		}
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
    internal suspend fun importImagesNow(files: List<java.io.File>, parentDeformerId: String?,
                                         expected: WorkspaceProjectSnapshot): WorkspaceMutationResult {
        val workspace = requireNotNull(workspaceBackend) { "Project workspace unavailable" }
        val port: io.github.psd2live.application.WorkspaceSourcePort = workspace
        return withContext(Dispatchers.Default + io.github.psd2live.application.WorkspaceExecution(
            expected.projectId, expected.state, MutationAuthor.USER)) {
            port.importImages(requireNotNull(expected.state), files.map { it.toPath().toAbsolutePath().normalize() }, parentDeformerId)
        }
    }

    fun importLayersFromFiles(files: List<java.io.File>, parentDeformerId: String?, anchorLabel: String) {
        if (files.isEmpty()) return
        val current = _state.value
        val expected = workspaceBackend?.snapshot()
        if (current.previewModel == null || current.isBusy || expected?.loaded != true) {
            setErrorMessage(tr("error.importLayerBusy"))
            return
        }
        val workspaceId = current.activeWorkspace.id
        val canvasId = current.activeCanvas.id
        scope.launch {
            try {
                updateState { it.copy(statusText = tr("status.importingLayers", files.size)) }
                val result = importImagesNow(files, parentDeformerId, expected)
                // Opening or another command after this commit cannot arm an old placement panel.
                if (workspaceBackend?.snapshot()?.state != result.state || _state.value.activeWorkspace.id != workspaceId) return@launch
                val preview = _state.value.previewModel ?: return@launch
                val ids = result.affectedLayerIds
                val placeId = ids.lastOrNull() ?: return@launch
                val source = preview.analysis.source.layers.singleOrNull { it.id.raw == placeId } ?: return@launch
                val revealed = revealCanvasLayers(result.state, workspaceId, canvasId, ids)
                updateCanvasPresentation(workspaceId, canvasId, CanvasMode.EDIT) {
                    it.copy(selectedLayerId = placeId, selectedDeformerId = null)
                }
                yield()
                if (workspaceBackend?.snapshot()?.state != revealed || _state.value.activeWorkspace.id != workspaceId) return@launch
                if (_state.value.activeCanvas.id == canvasId && _state.value.activeCanvas.mode != CanvasMode.EDIT) {
                    setCanvasMode(canvasId, CanvasMode.EDIT)
                }
                val bounds = source.bounds
                canvasEditorFor(canvasId).beginLayerPlacement(placeId, source.name, anchorLabel, parentDeformerId,
                    bounds.left.toFloat(), bounds.top.toFloat(), bounds.width.toFloat(), bounds.height.toFloat(), ids,
                    requireNotNull(workspaceBackend).beginImagePlacement(requireNotNull(revealed), ids))
            } catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                setErrorMessage(failure.message ?: tr("error.importLayerFailed"))
            }
        }
    }

    /** Only the display model changes; the document projection remains the committed baseline. */
    internal fun projectImagePlacementPreview(preview: RigPreviewModel) {
        updateState { it.copy(previewModel = preview) }
        refreshSdkSession(preview)
    }

    internal fun dismissImagePlacements() {
        editorsSnapshot().forEach { it.dismissImagePlacement() }
    }

    fun relocateImportedLayer(
        placement: io.github.psd2live.application.WorkspaceImagePlacement,
        layerId: String, name: String, left: Float, top: Float, width: Float, height: Float,
        commitHistory: Boolean = true, splitCandidates: List<String> = emptyList(), onCommitted: () -> Unit = {},
    ) {
        val ui = _state.value
        val workspaceId = ui.activeWorkspace.id; val canvasId = ui.activeCanvas.id; val mode = ui.activeCanvas.mode
        val request = io.github.psd2live.project.WorkspaceImageBounds(layerId, left, top, width, height, name.takeUnless { it.isBlank() })
        val pending = try {
            if (commitHistory) placement.commit(request, tr("editor.importLayer.placed", name.ifBlank { layerId })) else placement.preview(request)
        } catch (failure: Exception) { setErrorMessage(failure.message ?: tr("error.importLayerFailed")); return }
        scope.launch {
            try {
                val result = pending.await()
                if (commitHistory) {
                    onCommitted()
                    if (result is WorkspaceMutationResult && workspaceBackend?.snapshot()?.state == result.state && _state.value.activeWorkspace.id == workspaceId) {
                        selectOnCanvas(workspaceId, canvasId, mode, layerId)
                        offerMeshSplit(splitCandidates)
                        val editor = canvasEditorFor(canvasId)
                        if (editor.hierarchyMode == EditHierarchyMode.PAINT) editor.startPaintSession(layerId, forceReload = true)
                    }
                }
            } catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) return@launch
                setErrorMessage(failure.message ?: tr("error.importLayerFailed"))
            }
        }
    }

    fun cancelImportedLayerPlacement(placement: io.github.psd2live.application.WorkspaceImagePlacement, onCancelled: () -> Unit) {
        val pending = try { placement.cancel(tr("editor.importLayer.cancelled")) }
            catch (failure: Exception) { setErrorMessage(failure.message ?: tr("error.importLayerFailed")); return }
        scope.launch {
            try { pending.await(); onCancelled() }
            catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) return@launch
                setErrorMessage(failure.message ?: tr("error.importLayerFailed"))
            }
        }
    }

	fun setLayerDrawOrder(targetId: String, order: Float) {
		changeLayerDrawOrder(targetId, order)
	}

	fun resetLayerDrawOrder(targetId: String) {
		changeLayerDrawOrder(targetId, null)
	}

    private fun changeLayerDrawOrder(targetId: String, order: Float?) {
        val request = io.github.psd2live.application.WorkspaceDrawOrderEdits.request(targetId, order)
        workspaceBackend?.takeUnless { editorSessions.anyOpen }?.let { workspace ->
            val port: io.github.psd2live.application.WorkspaceDocumentPort = workspace
            runWorkspaceCommand { state -> port.applyDocumentEdits(state, "Updated drawing order", listOf(
                io.github.psd2live.application.WorkspaceDocumentOperation(io.github.psd2live.application.WorkspaceDrawOrderEdits.OP, request)), MutationAuthor.USER) }
            return
        }
        val current = _state.value
        val model = current.previewModel ?: return
        val candidate = try { io.github.psd2live.application.WorkspaceDrawOrderEdits.apply(WorkspaceStateCodec.document(current), model, request) }
            catch (failure: Exception) { setErrorMessage(failure.message ?: "Invalid drawing order"); return }
        val orders = candidate.settings.getValue("drawOrderOverrides").jsonObject.mapValues { it.value.jsonPrimitive.float }
        if (orders == current.drawOrderOverrides) return
        updateState { it.copy(drawOrderOverrides = orders, projectDirty = true) }
        editorChanged("Updated drawing order")
    }

	fun setLayerClassification(layerId: String, override: LayerClassificationOverride) {
        workspaceBackend?.takeUnless { editorSessions.anyOpen }?.let { workspace ->
            val port: io.github.psd2live.application.WorkspaceSourcePort = workspace
            runWorkspaceCommand { state -> port.classifyLayer(layerId, kotlinx.serialization.json.buildJsonObject {
                put("type", override.type.name); put("role", override.tag.name); put("side", override.side.name)
                put("parameter", override.parameter); put("switch_id", override.switchId)
            }, state) }
            return
        }
		updateState {
			it.copy(
				layerOverrides = it.layerOverrides + (layerId to override),
				statusText = tr("status.classificationChanged"),
			)
		}
		schedulePreviewRebuild()
	    editorChanged()
	}

	fun toggleParameterLock(id: ParameterId, currentValue: Float? = null) {
		val current = _state.value
		val parameter = current.previewModel?.rig?.puppet?.parameters?.firstOrNull { it.id == id } ?: return
		val locked = id !in current.lockedParameters
		editPreviewSession(kotlinx.serialization.json.buildJsonObject {
			put("mode", "set"); putJsonObject("locks") { put(id.raw, locked) }
			if (locked) putJsonObject("values") { put(id.raw, (currentValue ?: current.parameterValues[id] ?: parameter.default).coerceIn(parameter.min, parameter.max)) }
		})
	}
	/** Locks and resets queue behind the authored values already shown, so neither can overtake the other. */
	private fun editPreviewSession(request: kotlinx.serialization.json.JsonObject) {
		val state = currentWorkspaceState() ?: return
		val port = workspaceBackend as? io.github.psd2live.application.WorkspacePreviewPort ?: return
		commitPose(showPendingPose(emptyMap()), state, { expected ->
			port.setPreviewSession(kotlinx.serialization.json.JsonObject(request + ("state" to kotlinx.serialization.json.JsonPrimitive(expected))))
		})
	}

	fun setParameterValue(id: ParameterId, value: Float) = setParameterValues(mapOf(id to value))

    /** Panel input selects the destination axis even when its value already equals the shown key. */
    fun setParameterValuesFromPanel(values: Map<ParameterId, Float>) {
        val selected = values.filterValues(Float::isFinite).keys
        if (selected.isEmpty()) return
        uiState.value.activeWorkspace.canvases.filter { it.mode == CanvasMode.EDIT }.forEach {
            canvasEditorFor(it.id).selectDeformationParameters(selected)
        }
        setParameterValues(values)
    }

    fun setParameterValueFromPanel(id: ParameterId, value: Float) = setParameterValuesFromPanel(mapOf(id to value))

	/**
	 * Every panel, canvas and the physics preview show the change at once, with the skeleton's constrained
	 * parameters following it as they will after the commit. Scrub samples stay transient until release; a
	 * finished change commits through the pose queue from the state captured now.
	 */
	fun setParameterValues(values: Map<ParameterId, Float>) {
		if (values.isEmpty()) return
		val current = _state.value
		val model = current.previewModel
		val parameters = model?.rig?.puppet?.parameters?.associateBy { it.id }.orEmpty()
		val clamped = values.filterValues(Float::isFinite).mapValues { (id, value) -> parameters[id]?.let { value.coerceIn(it.min, it.max) } ?: value }
		if (clamped.isEmpty()) return
		val constrained = model?.let { preview -> io.github.psd2live.core.SkeletonPoseSolver.solveTargets(preview.rig.puppet,
			preview.config.rigEdits.skeleton, parameterScrubPose(current, current.parameterValues) + clamped) }.orEmpty()
		val changed = clamped + constrained.filterKeys { it !in clamped }
		if (updateParameterScrub(changed)) return
		submitParameterValues(changed, currentWorkspaceState() ?: return)
	}

	private fun currentAutoKey(): kotlinx.serialization.json.JsonObject? = editingMotionClip().takeIf { motionEditor.autoKey }?.let { clip ->
		kotlinx.serialization.json.buildJsonObject { put("clip_id", clip.id); put("time", motionEditor.playhead.coerceIn(0f, clip.duration)); put("snap", motionEditor.snapToFrames) }
	}
	private fun submitParameterValues(values: Map<ParameterId, Float>, expectedState: String,
		extras: kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.JsonObject(emptyMap()), autoKey: kotlinx.serialization.json.JsonObject? = currentAutoKey()) {
		if (values.isNotEmpty()) stopPlaybackForAuthoring()
		submitParameterValues(showPendingPose(values), expectedState, extras, autoKey)
	}

	private fun submitParameterValues(pending: PendingPose, expectedState: String,
		extras: kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.JsonObject(emptyMap()),
		autoKey: kotlinx.serialization.json.JsonObject? = currentAutoKey()): kotlinx.coroutines.Deferred<Boolean>? {
		val port = workspaceBackend as? io.github.psd2live.application.WorkspacePreviewPort ?: run { discardPendingPose(pending); return null }
		return commitPose(pending, expectedState, { state ->
			port.authorPose(kotlinx.serialization.json.buildJsonObject {
				extras.forEach { (key, value) -> put(key, value) }
				put("state", state)
				putJsonObject("values") { pending.values.forEach { (id, value) -> put(id.raw, value) } }
				autoKey?.let { put("auto_key", it) }
			}, io.github.psd2live.project.MutationAuthor.USER)
		}) { result ->
			motionEditor.selection = result.getValue("keyed").jsonArray.mapTo(linkedSetOf()) { item ->
				val key = item.jsonObject; MotionKeyRef(key.getValue("parameter").jsonPrimitive.content, key.getValue("time").jsonPrimitive.float)
			}
		}
	}

	private fun updateParameterScrub(values: Map<ParameterId, Float>): Boolean {
		val scrub = parameterScrub ?: return false
		val current = _state.value
		if (current.projectOpenGeneration != scrub.generation || current.activeWorkspace.id != scrub.workspaceId) return false
		var overrides = scrub.overrides
		val changed = HashMap<ParameterId, Float>()
		for ((id, value) in values) {
			if (overrides[id] == value) continue
			overrides = overrides + (id to value)
			parameterScrubValues[id] = value
			changed[id] = value
		}
		if (changed.isEmpty()) return true
		parameterScrub = scrub.copy(overrides = overrides)
		// The edit canvas, the software preview, guides and the physics panel all read the authored pose.
		synchronized(stateLock) {
			scrub.pending.values = overrides
			updateState { latest ->
				if (latest.projectOpenGeneration != scrub.generation || latest.activeWorkspace.id != scrub.workspaceId) latest
				else latest.copy(parameterValues = latest.parameterValues + changed)
			}
		}
		return true
	}

	/**
	 * The authored pose every view resolves at: [values] (the authored pose, or an evaluated frame built from the
	 * committed one), then the changes still waiting for their commits, then the slider being dragged.
	 */
	internal fun parameterScrubPose(current: PSD2LiveState, values: Map<ParameterId, Float>): Map<ParameterId, Float> {
		val pending = pendingPoseValues(current)
		val shown = if (pending.isEmpty()) values else values + pending
		val scrub = parameterScrub ?: return shown
		return if (scrub.generation == current.projectOpenGeneration && scrub.workspaceId == current.activeWorkspace.id && scrub.overrides.isNotEmpty())
			shown + scrub.overrides else shown
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
		val targets = io.github.psd2live.core.nearestKeyPose(axes, pose, defaults)
		if (targets.isEmpty()) return false
		val expectedState = currentWorkspaceState() ?: return false
		parameterSnapJob?.cancel()
		parameterSnapJob = scope.launch {
			try {
				animateParameterValues(targets, durationMs = 220L, expectedState)
				onReady()
			} catch (failure: Exception) {
				if (failure is kotlinx.coroutines.CancellationException) throw failure
				updateState { it.copy(statusText = failure.message ?: "Could not snap pose") }
			}
		}
		return true
	}

	/**
	 * Eases [targets] in as the authored pose every view shows, then commits them once. The frames are a pending
	 * change, so a commit landing meanwhile keeps them; a cancelled snap returns to the committed pose.
	 */
	private suspend fun animateParameterValues(targets: Map<ParameterId, Float>, durationMs: Long, state: String) {
		val initial = _state.value
		val from = targets.mapValues { (id, _) -> initial.parameterValues[id] ?: targets.getValue(id) }
		stopPlaybackForAuthoring()
		val pending = showPendingPose(from)
		var submitted = false
		try {
			val startedAt = System.nanoTime()
			while (true) {
				val t = ((System.nanoTime() - startedAt) / 1_000_000.0 / durationMs).toFloat().coerceIn(0f, 1f)
				val eased = t * t * (3f - 2f * t)
				val current = currentWorkspaceState()
				if (current != ownPoseLineage(state)) throw io.github.psd2live.application.WorkspaceConflict(state, current ?: "unloaded")
				val values = targets.mapValues { (id, to) -> from.getValue(id) + (to - from.getValue(id)) * eased }
				synchronized(stateLock) {
					pending.values = values
					updateState { latest ->
						if (latest.projectOpenGeneration != pending.generation || latest.activeWorkspace.id != pending.workspaceId) latest
						else latest.copy(parameterValues = latest.parameterValues + values).authoringPose(latest.activeCanvas.mode == CanvasMode.EDIT)
					}
				}
				if (t >= 1f) break
				delay(16L)
			}
			pending.values = targets
			submitted = true
			val committed = submitParameterValues(pending, state, autoKey = null) ?: return
			check(committed.await()) { "Could not snap pose" }
		} finally {
			if (!submitted) discardPendingPose(pending)
		}
	}

	fun resetParameter(id: ParameterId) {
		val model = _state.value.previewModel
		val param = model?.rig?.puppet?.parameters?.firstOrNull { it.id == id }
		val defaultVal = param?.default ?: 0f
		setParameterValue(id, defaultVal)
		if (id == StandardParameters.ANGLE_X || id == StandardParameters.EYE_BALL_X || id == StandardParameters.BODY_X) {
			pointerX = 0f
		}
		if (id == StandardParameters.ANGLE_Y || id == StandardParameters.EYE_BALL_Y || id == StandardParameters.BODY_Y) {
			pointerY = 0f
		}
	}

	private fun resetMotionDynamics() {
		pointerActive = false
		pointerX = 0f
		pointerY = 0f
		resetPreviewPhysics()
		stopProcessMotion()
		lastTick = System.nanoTime()
	}

    /**
     * Both projections validate before publishing any authored pose or document candidate. The authored values
     * themselves are not compared: a change the panels already show while its own commit waits is kept over the
     * committed pose instead of failing an earlier commit.
     */
    internal fun projectAuthoredPose(expected: PSD2LiveState, pose: io.github.psd2live.application.WorkspacePose,
                                    changed: Boolean, commit: PendingPoseCommit? = null,
                                    documentProjection: () -> Unit) = synchronized(stateLock) {
        checkPoseProjection(expected)
        documentProjection()
        applyPreviewSession(_state.value, pose, changed, commit)
    }

    private fun checkPoseProjection(expected: PSD2LiveState) {
        val current = _state.value
        check(current.projectId == expected.projectId && current.activeWorkspace.id == expected.activeWorkspace.id &&
            current.projectOpenGeneration == expected.projectOpenGeneration && current.previewModel === expected.previewModel &&
            current.lockedParameters == expected.lockedParameters) {
            "Preview session changed while the operation was being prepared"
        }
    }

    /**
     * Explicit session application: unlike a slider gesture, this cannot invoke automatic keying. [commit] names the
     * queued GUI change this projects; the changes queued after it stay shown on top of [pose].
     */
    internal fun applyPreviewSession(expected: PSD2LiveState, pose: io.github.psd2live.application.WorkspacePose,
                                     persistedChange: Boolean, commit: PendingPoseCommit? = null) = synchronized(stateLock) {
        checkPoseProjection(expected)
        val current = _state.value
        val workspaceId = commit?.workspaceId ?: current.activeWorkspace.id
        val shown = pose.values + consumePendingPose(current.projectOpenGeneration, workspaceId, commit?.id)
        if (workspaceId != current.activeWorkspace.id) {
            // Focus moved on while the change waited: it lands in the workspace it was made in.
            projectWorkspacePose(current.projectOpenGeneration, workspaceId, pose.values, pose.locked)
            if (persistedChange && current.analysis != null)
                updateState { it.copy(projectDirty = true, projectEditVersion = it.projectEditVersion + 1) }
            return@synchronized
        }
        val alreadyShown = current.parameterValues == shown && current.lockedParameters == pose.locked
        if (alreadyShown && !current.animationEnabled && !motionEditor.playing && !persistedChange) return@synchronized
        if (alreadyShown && commit != null) {
            // The GUI's own change landed exactly as the views already show it: a running swing keeps going
            // instead of restarting from rest when the commit arrives.
            if (persistedChange && current.analysis != null)
                updateState { it.copy(projectDirty = true, projectEditVersion = it.projectEditVersion + 1) }
            return@synchronized
        }
        resetMotionDynamics()
        motionEditor.playing = false
        updateState {
            it.copy(animationEnabled = false, parameterValues = shown, previewParameterValues = shown,
                lockedParameters = pose.locked, projectDirty = it.projectDirty || (persistedChange && it.analysis != null),
                projectEditVersion = it.projectEditVersion + if (persistedChange && it.analysis != null) 1 else 0)
                .authoringPose(it.activeCanvas.mode == CanvasMode.EDIT)
        }
    }
    /** Projects authored poses a settings commit released, in the same CAS that published them. */
    internal fun projectWorkspacePoses(expected: PSD2LiveState, poses: Map<String, io.github.psd2live.application.WorkspacePose>) = synchronized(stateLock) {
        val current = _state.value
        check(current.projectId == expected.projectId && current.projectOpenGeneration == expected.projectOpenGeneration) {
            "Workspace changed while the operation was being prepared"
        }
        updateState { state ->
            val workspaces = state.workspaces.map { workspace ->
                val pose = poses[workspace.id]
                if (pose == null || workspace.id == state.activeWorkspace.id) workspace
                else workspace.withPose((workspace.pose ?: WorkspacePose.capture(workspace.activeCanvas.presentation))
                    .copy(parameterValues = pose.values + pendingPoseValues(state.projectOpenGeneration, workspace.id),
                        lockedParameters = pose.locked, previewParameterValues = emptyMap()))
            }
            val active = poses[state.activeWorkspace.id]
            val next = state.copy(workspaces = workspaces)
            if (active == null) next else {
                val shown = active.values + pendingPoseValues(state)
                next.copy(parameterValues = shown, previewParameterValues = shown, lockedParameters = active.locked)
            }
        }
    }

	fun resetAllParameters() {
		editPreviewSession(kotlinx.serialization.json.buildJsonObject { put("mode", "reset") })
	}

	fun resetPreviewParameters() {
		resetAllParameters()
	}

	private fun pruneParameterSnapshotPreview(current: PSD2LiveState) {
		val hover = parameterSnapshotHover ?: return
		if (hover.generation != current.projectOpenGeneration || hover.workspaceId != current.activeWorkspace.id ||
			hover.canvasId != current.activeCanvas.id || current.previewModel == null ||
			current.parameterSnapshots.none { it.id == hover.snapshotId }) parameterSnapshotHover = null
	}

	internal fun previewParameterSnapshot(id: String): ParameterSnapshotPreview? {
		val current = _state.value
		if (current.previewModel == null || current.parameterSnapshots.none { it.id == id }) return null
		return ParameterSnapshotPreview(id, current.projectOpenGeneration, current.activeWorkspace.id,
			current.activeCanvas.id).also { parameterSnapshotHover = it }
	}

	internal fun clearParameterSnapshotPreview(preview: ParameterSnapshotPreview) {
		if (parameterSnapshotHover === preview) parameterSnapshotHover = null
	}

	internal fun parameterSnapshotPreviewFor(canvasId: String): ParameterSnapshot? {
		val hover = parameterSnapshotHover ?: return null
		val current = _state.value
		if (hover.generation != current.projectOpenGeneration || hover.workspaceId != current.activeWorkspace.id ||
			hover.canvasId != canvasId || hover.canvasId != current.activeCanvas.id) return null
		return current.parameterSnapshots.firstOrNull { it.id == hover.snapshotId }
	}

	/** Saves every parameter as the parameters panel shows it, the live pose included while previewing. */
	fun saveParameterSnapshot(name: String = "") {
        val values = shownParameterValues() ?: return
        editSavedProjectData(io.github.psd2live.application.WorkspaceAuxiliaryEdit.CreateSnapshot(java.util.UUID.randomUUID().toString(), name, values))
    }

    fun overwriteParameterSnapshot(id: String) {
        val values = shownParameterValues() ?: return
        editSavedProjectData(io.github.psd2live.application.WorkspaceAuxiliaryEdit.UpdateSnapshot(id, values = values))
    }

	/** Loads a saved pose onto the parameters it still names; locked parameters keep their value. */
	fun applyParameterSnapshot(id: String) {
		val current = _state.value
		val snapshot = current.parameterSnapshots.firstOrNull { it.id == id } ?: return
        val workspace = workspaceBackend as? io.github.psd2live.application.WorkspaceAuxiliaryPort
        if (workspace != null) {
            workspace.applySavedSnapshotPose(workspace.savedProjectData().state, id)
            return
        }
        val parameters = current.previewModel?.rig?.puppet?.parameters ?: return
        val values = parameters.mapNotNull { parameter -> snapshot.values[parameter.id]
            ?.takeIf { parameter.id !in current.lockedParameters }?.let { parameter.id to it.coerceIn(parameter.min, parameter.max) } }.toMap()
        val pose = io.github.psd2live.application.WorkspacePose(current.parameterValues + values, current.lockedParameters)
        applyPreviewSession(current, pose, pose.values != current.parameterValues)
	}

	fun renameParameterSnapshot(id: String, name: String) {
        editSavedProjectData(io.github.psd2live.application.WorkspaceAuxiliaryEdit.UpdateSnapshot(id, name = name))
    }

    fun deleteParameterSnapshot(id: String) {
        editSavedProjectData(io.github.psd2live.application.WorkspaceAuxiliaryEdit.DeleteSnapshot(id))
    }

	private fun shownParameterValues(): Map<ParameterId, Float>? {
		val current = _state.value
		val parameters = current.previewModel?.rig?.puppet?.parameters ?: return null
		val live = current.activeCanvas.mode == CanvasMode.PREVIEW && current.activeWorkspace.pose?.authoringPose != true && current.previewLive && (current.animationEnabled || current.mouseTrackingEnabled ||
			(current.generatePhysics && !current.meshOnly))
		val pose = if (live) livePose.value else emptyMap()
		return parameters.associate { it.id to (pose[it.id] ?: current.parameterValues[it.id] ?: it.default) }
	}

	fun unlockAllParameters() {
		val locked = _state.value.lockedParameters
		if (locked.isNotEmpty()) editPreviewSession(kotlinx.serialization.json.buildJsonObject { put("mode", "set"); putJsonObject("locks") { locked.forEach { put(it.raw, false) } } })
	}

	fun setMouseTrackingEnabled(enabled: Boolean) {
		val pointer = if (enabled && pointerActive) kotlinx.serialization.json.JsonArray(listOf(
			kotlinx.serialization.json.JsonPrimitive(pointerX), kotlinx.serialization.json.JsonPrimitive(-pointerY))) else null
		if (!configurePlayback("tracking", kotlinx.serialization.json.buildJsonObject { put("enabled", enabled); pointer?.let { put("pointer", it) } })) {
			if (workspaceBackend != null && currentWorkspaceState() != null) return
			val current = _state.value
			updateCanvasPresentation(current.activeWorkspace.id, current.previewControlCanvas().id, CanvasMode.PREVIEW) {
				it.copy(mouseTrackingEnabled = enabled)
			}
		}
		if (!enabled) {
			pointerActive = false
			pointerX = 0f
			pointerY = 0f
		}
	    markWorkspaceChanged()
	}

	fun setSmoothMouseTracking(enabled: Boolean) {
		val current = _state.value
		if (!configurePlayback("tracking", buildJsonObject {
			put("enabled", current.mouseTrackingEnabled); put("smooth", enabled)
			if (pointerActive) put("pointer", kotlinx.serialization.json.JsonArray(listOf(
				kotlinx.serialization.json.JsonPrimitive(pointerX), kotlinx.serialization.json.JsonPrimitive(-pointerY))))
		})) {
			if (workspaceBackend != null && currentWorkspaceState() != null) return
			updateState { it.copy(smoothMouseTracking = enabled) }
		}
		markWorkspaceChanged()
	}

	fun updatePointer(screenNormX: Float, screenNormY: Float, owner: String? = null) {
        if (owner != null) canvasPointers[owner] = screenNormX.coerceIn(-1f, 1f) to screenNormY.coerceIn(-1f, 1f)
        pointerOwner = owner
		pointerActive = true
		pointerX = screenNormX.coerceIn(-1f, 1f)
		pointerY = screenNormY.coerceIn(-1f, 1f)
		sendPlaybackPointer(pointerX to -pointerY)
	}

	/** Only the coordinates: the next clock frame evaluates them, so a mouse move costs no frame or state update. */
	private fun sendPlaybackPointer(pointer: Pair<Float, Float>?) {
		val port = workspaceBackend as? io.github.psd2live.application.WorkspacePreviewPort ?: return
		try { port.playbackPointer(pointer) } catch (_: IllegalArgumentException) { /* A non-finite pointer is not tracked. */ }
	}

	fun clearPointer(owner: String? = null) {
        if (owner != null) canvasPointers.remove(owner) else canvasPointers.clear()
        if (owner != null && pointerOwner != owner) return
        pointerOwner = null
		pointerActive = false
		sendPlaybackPointer(null)
	}

	fun clearErrorMessage() {
		updateState { it.copy(errorMessage = null) }
	}

	fun setErrorMessage(message: String?) {
		updateState { it.copy(errorMessage = message) }
	}

	fun setStatusText(text: String) {
		updateState { it.copy(statusText = text) }
	}

	fun clearExportSuccess() {
		updateState { it.copy(exportSuccess = null, focusCanvasRequest = it.focusCanvasRequest + 1) }
	}

    fun analyze(discardUnsaved: Boolean = false) {
        if (_state.value.isBusy || _state.value.projectSaving) return
        val rawInput = _state.value.inputPath.trim()
        if (rawInput.isEmpty()) { updateState { it.copy(errorMessage = tr("dialog.inputRequired")) }; return }
        val input = Path.of(rawInput)
        if (!Files.isRegularFile(input) || !input.fileName.toString().endsWith(".psd", true)) {
            updateState { it.copy(errorMessage = tr("dialog.inputInvalid", input)) }; return
        }
        startWorkspaceImport(discardUnsaved, tr("status.analyzing"), afterImport = {
            if (AppSettings.autoDetectMeshSplitsOnImport) requestStartScreen(fresh = true)
            else applyStartScreenDefaults()
        }) { it.importPsd(input.toAbsolutePath().normalize().toString(), discardUnsaved) }
    }

    /** Both adapters invoke the same application source capability; only this entry owns GUI confirmation. */
    fun importCmo3(path: Path, mode: io.github.psd2live.core.Cmo3ImportMode) {
        if (_state.value.isBusy || _state.value.projectSaving) return
        if (mode == io.github.psd2live.core.Cmo3ImportMode.NEW && _state.value.analysis != null) {
            withSavedChanges { startCmo3Import(path, mode, discardUnsaved = true) }
        } else startCmo3Import(path, mode, discardUnsaved = false)
    }

    private fun startCmo3Import(path: Path, mode: io.github.psd2live.core.Cmo3ImportMode, discardUnsaved: Boolean) =
        startWorkspaceImport(discardUnsaved, tr("cmo3.importing")) {
            it.importCmo3(path.toAbsolutePath().normalize(), mode, discardUnsaved)
        }

    private fun startWorkspaceImport(discardUnsaved: Boolean, status: String, afterImport: () -> Unit = {},
                                     action: suspend (io.github.psd2live.application.WorkspaceSourcePort) -> Unit) {
        flushEditorFields()
        endSwing()
        previewRebuildJob?.cancel()
        activeWorkJob = scope.launch {
            try {
                val workspace = requireNotNull(workspaceBackend) { "Project workspace unavailable" }
                try { workspace.awaitEditorDrafts() }
                catch (failure: Exception) {
                    if (failure is kotlinx.coroutines.CancellationException || !discardUnsaved) throw failure
                }
                _state.first { !it.workspaceEditBusy }
                val expected = workspace.snapshot()
                updateState { it.copy(isAnalyzing = true, isIndeterminateProgress = true, errorMessage = null, statusText = status) }
                withContext(io.github.psd2live.application.WorkspaceExecution(expected.projectId, expected.state, MutationAuthor.USER)) { action(workspace) }
                updateState { it.copy(isAnalyzing = false, isIndeterminateProgress = false) }
                afterImport()
            } catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                updateState { it.copy(errorMessage = failure.message ?: failure.toString(), statusText = tr("status.failed", failure.message)) }
            } finally { updateState { it.copy(isAnalyzing = false, isIndeterminateProgress = false) } }
        }
    }

	fun generateRig(targetOutputPath: String? = null) {
		if (!targetOutputPath.isNullOrBlank()) setOutputPath(targetOutputPath)
		val current = _state.value
		if (current.analysis == null) {
			updateState { it.copy(errorMessage = tr("error.noPsdLoaded")) }; return
		}
		var output = current.outputPath.trim()
		if (output.isEmpty()) {
			val input = runCatching { Path.of(current.inputPath).toAbsolutePath().normalize() }.getOrNull()
			if (input?.fileName == null) {
				updateState { it.copy(errorMessage = tr("dialog.outputRequired")) }; return
			}
			output = input.parent.resolve(input.fileName.toString().substringBeforeLast('.') + "-psd2live").toString()
			setOutputPath(output)
		}
		if (!current.exportCmo3 && !current.exportMoc3) {
			updateState { it.copy(errorMessage = tr("dialog.exportFormatRequired")) }; return
		}
		val directory = Path.of(output).toAbsolutePath().normalize()
		lastExportDirectory = directory.toString()
		launchWorkspaceExport(false, { port, state -> port.exportModel(state, directory.toString()) }) { result ->
			val files = result.getValue("files").jsonArray
			val warnings = result.getValue("warnings").jsonArray.map { it.jsonPrimitive.content }
			updateState { state ->
				state.withLogs(listOf(tr("log.outputFiles")) + files.map {
					"• " + it.jsonObject.getValue("path").jsonPrimitive.content + " (" +
						it.jsonObject.getValue("bytes").jsonPrimitive.content + " bytes)"
				}, level = LogLevel.INFO, tag = "Export").let {
					if (warnings.isEmpty()) it else it.withLogs(listOf(tr("log.warnings")) + warnings.map { text -> "• $text" },
						level = LogLevel.WARNING, tag = "Export")
				}.copy(progress = 1f, statusText = tr("status.completed", files.size, warnings.size))
			}
			reportExportSuccess(ExportSuccess(tr("dialog.exportSuccess", files.size), directory.toString(),
				tr("log.warnings").takeIf { warnings.isNotEmpty() }, warnings))
		}
	}

	fun openExportDialog() {
		updateState { it.copy(showExportDialog = true) }
	}

	fun closeExportDialog() {
		updateState {
			it.copy(
				showExportDialog = false,
				focusCanvasRequest = it.focusCanvasRequest + 1,
			)
		}
	}

	fun openExportPsdDialog() {
		if (_state.value.analysis == null) return
		updateState { it.copy(showExportPsdDialog = true) }
	}

	fun closeExportPsdDialog() {
		updateState { it.copy(showExportPsdDialog = false) }
	}

	/** Opens the export dialog of one neutral target (File > Export as). */
	fun openOtherExport(targetId: String) {
		if (_state.value.previewModel == null) return
		updateState { it.copy(otherExportTarget = targetId) }
	}

	fun closeOtherExport() {
		updateState { it.copy(otherExportTarget = null, focusCanvasRequest = it.focusCanvasRequest + 1) }
	}

	/** Motion clips an export can render, by id and name, compiled from the current rig. */
	internal fun exportClipChoices(): List<Pair<String, String>> {
		val preview = _state.value.previewModel ?: return emptyList()
		return runCatching { io.github.psd2live.core.RigIrCompiler.compile(preview).clips.map { it.id to it.name } }.getOrDefault(emptyList())
	}

	/** Settings changed in an export-as dialog, by target and key, kept while the app runs; the rest stay at their defaults. */
	private val otherExportEdits = androidx.compose.runtime.mutableStateMapOf<Pair<String, String>, String>()

	internal fun otherExportSetting(target: io.github.psd2live.format.compile.ExportTarget, key: String): String? =
		otherExportEdits[target.id to key]

	/** Sets one of [target]'s settings; null restores its default. */
	internal fun setOtherExportSetting(target: io.github.psd2live.format.compile.ExportTarget, key: String, value: String?) {
		if (value == null) otherExportEdits.remove(target.id to key) else otherExportEdits[target.id to key] = value
	}

	/** The settings to send for [target]: only the ones the user changed. */
	internal fun otherExportSettings(target: io.github.psd2live.format.compile.ExportTarget): Map<String, String> =
		target.settings.mapNotNull { setting -> otherExportEdits[target.id to setting.key]?.let { setting.key to it } }.toMap()

	/** The neutral targets offered beside the Cubism export. */
	internal fun otherExportTargets(): List<io.github.psd2live.format.compile.ExportTarget> =
		io.github.psd2live.core.ExportService.registry(exportConfig()).targets.filter { it.id != "moc3" && it.id != "cmo3" }

	/** What [target]'s settings start from before the user changes them: the project's export settings where it reads them. */
	internal fun exportTargetDefaults(target: io.github.psd2live.format.compile.ExportTarget): Map<String, String> =
		io.github.psd2live.core.ExportService.options(target, "model", exportConfig()).settings

	private fun exportConfig(): PipelineConfig = _state.value.previewModel?.config ?: PipelineConfig()

	/** Exports the committed rig through [targetId] into a folder named after the target under the output path. */
	internal fun exportOtherFormat(targetId: String, settings: Map<String, String>) {
		val root = _state.value.outputPath.takeIf { it.isNotBlank() }
			?: run { updateState { it.copy(errorMessage = tr("export.other.noOutput")) }; return }
		val name = (_state.value.projectSourceName ?: "model").substringBeforeLast('.')
		val target = Path.of(root).toAbsolutePath().normalize().resolve("$name-$targetId")
		launchWorkspaceExport(false, { port, state -> port.exportTarget(state, targetId, target.toString(), settings) }) { result ->
			val count = result.getValue("files").jsonArray.size
			val losses = result["losses"]?.jsonArray.orEmpty().map { it.jsonObject }
				.distinctBy { it["note"]?.jsonPrimitive?.content }
				.map { "${tr("export.loss.${it["handling"]?.jsonPrimitive?.content}")}: ${it["note"]?.jsonPrimitive?.content}" }
			addLog(tr("export.other.done", count, target.toString()), level = LogLevel.SUCCESS, tag = "Export")
			if (losses.isNotEmpty()) updateState { it.withLogs(listOf(tr("export.other.losses", losses.size)) + losses.map { line -> "• $line" },
				level = LogLevel.WARNING, tag = "Export") }
			updateState { it.copy(progress = 1f, statusText = tr("export.other.done", count, target.fileName.toString())) }
			reportExportSuccess(ExportSuccess(tr("export.other.success", tr("export.target.$targetId"), count), target.toString(),
				tr("export.other.losses", losses.size).takeIf { losses.isNotEmpty() }, losses))
		}
	}

	fun exportPsd(targetPath: Path, scale: Int = 1, includeGeneratedLayers: Boolean = true) {
		if (_state.value.analysis == null) {
			updateState { it.copy(errorMessage = tr("error.noPsdLoaded")) }; return
		}
		val target = targetPath.toAbsolutePath().normalize()
		launchWorkspaceExport(true, { port, state -> port.exportPsd(state, target.toString(), scale, includeGeneratedLayers) }) { result ->
			addLog(tr("log.exportPsdSuccess", target.fileName.toString(), result.getValue("layers").jsonPrimitive.int,
				result.getValue("bytes").jsonPrimitive.long), level = LogLevel.SUCCESS, tag = "Export")
			updateState { it.copy(progress = 1f, statusText = tr("exportPsd.completed", target.fileName.toString())) }
			reportExportSuccess(ExportSuccess(tr("exportPsd.success", target.fileName.toString()), target.parent.toString()))
		}
	}

	/**
	 * Every GUI export ends here: the export's dialog closes and, unless the user turned it off, the export-success
	 * dialog reports what was written and offers its folder. With it off, the log and status bar still say so.
	 */
	private fun reportExportSuccess(success: ExportSuccess) {
		updateState {
			it.copy(showExportDialog = false, showExportPsdDialog = false, otherExportTarget = null,
				exportSuccess = success.takeIf { _ -> AppPrompt.EXPORT_SUCCESS !in it.mutedPrompts },
				focusCanvasRequest = it.focusCanvasRequest + 1)
		}
	}

	private fun launchWorkspaceExport(psd: Boolean,
		action: suspend (io.github.psd2live.application.WorkspaceOutputPort, String) -> kotlinx.serialization.json.JsonObject,
		succeeded: (kotlinx.serialization.json.JsonObject) -> Unit) {
		val workspace = workspaceBackend ?: return
		flushEditorFields()
		val expected = workspace.snapshot()
		if (!expected.loaded || expected.projectId == null) return
		val generation = _state.value.projectOpenGeneration
		activeWorkJob?.cancel()
		val exportJob = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
			val owner = kotlinx.coroutines.currentCoroutineContext()[Job]
			fun currentExport() = activeWorkJob === owner && _state.value.projectId == expected.projectId &&
				_state.value.projectOpenGeneration == generation
			if (!currentExport()) return@launch
			val completion = io.github.psd2live.application.WorkspaceJobCompletion()
			updateState { it.copy(isGenerating = !psd, isExportingPsd = psd, showExportPsdDialog = false,
				progress = 0f, isIndeterminateProgress = false, errorMessage = null, exportSuccess = null,
				statusText = tr("status.generating")) }
			try {
				val settled = workspace.settleEditorDrafts(expected.projectId, expected.state)
				val progress = io.github.psd2live.application.WorkspaceJobContext { fraction, message ->
					if (currentExport()) updateState { it.copy(progress = fraction, statusText = message) }
				}
				val result = withContext(Dispatchers.Default + completion + progress +
					io.github.psd2live.application.WorkspaceExecution(expected.projectId, settled, MutationAuthor.USER)) {
					action(workspace, settled)
				}
				if (currentExport()) succeeded(result)
			} catch (failure: Exception) {
				val committed = completion.result
				if (committed != null) { if (currentExport()) succeeded(committed.data) }
				else {
					if (failure is kotlinx.coroutines.CancellationException) throw failure
					val detail = failure.message ?: failure.javaClass.simpleName
					if (currentExport()) updateState { it.withLog(tr("log.failed", detail), level = LogLevel.ERROR, tag = "Export")
						.copy(statusText = tr("status.failed", detail), errorMessage = detail) }
				}
			} finally { if (currentExport()) updateState { it.copy(isGenerating = false, isExportingPsd = false) } }
		}
		activeWorkJob = exportJob
		exportJob.start()
	}

	/** Fast incremental update or CPU rebuild used by the authenticated Agent transaction boundary. */
	internal suspend fun buildAgentWorkspacePreview(source: SourceArt, config: PipelineConfig): RigPreviewModel =
		runInterruptible(Dispatchers.Default) {
			val current = _state.value.previewModel
			if (current != null && pipeline.canFastUpdateRig(current, source, config)) {
				pipeline.updateRigEdits(current, config)
			} else if (current != null && (current.analysis.source === source || current.analysis.source == source) &&
				current.config.copy(parentOverrides = config.parentOverrides, rigEdits = config.rigEdits, drawOrderOverrides = config.drawOrderOverrides,
					hairSimulationFront = config.hairSimulationFront, hairSimulationBack = config.hairSimulationBack) == config
			) {
				pipeline.rebuildPreview(current, config)
			} else {
				pipeline.buildPreview(source, config)
			}
		}

    internal suspend fun sampleAgentMotion(bundle: io.github.psd2live.core.CubismRuntimeBundle,
                                          parameters: List<ParameterId>, frames: Int, fps: Int,
                                          progress: (Float) -> Unit, cancelled: () -> Boolean): List<Map<ParameterId, Float>> =
        sdkSession.sampleMotionAwait(bundle, parameters, "AgentObservation", frames, fps, progress, cancelled)

	/** Publish one already-built authoritative workspace snapshot atomically to Compose and preview. */
	internal fun applyAgentWorkspacePreview(
		preview: RigPreviewModel,
        expectedProjectId: String? = _state.value.projectId,
        expectedProjectOpenGeneration: Long = _state.value.projectOpenGeneration,
		expectedSource: SourceArt,
		expectedLayerVisibility: Map<String, Boolean>,
		expectedDeletedLayerIds: Set<String>,
		expectedLayerOverrides: Map<String, LayerClassificationOverride>,
		expectedParentOverrides: Map<String, String?>,
		expectedRigEdits: RigEditOverlay,
        expectedSettings: kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.JsonObject(emptyMap()),
		expectedMeshOverrides: Map<String, MeshSettings> = emptyMap(),
        expectedGenerationSource: SourceArt? = null,
        expectedMeshSource: SourceArt? = null,
        expectedPlacementSource: SourceArt? = null,
        expectedTextureOverrides: Map<String, io.github.psd2live.project.TextureOverride> = emptyMap(),
		layerVisibility: Map<String, Boolean>,
		deletedLayerIds: Set<String>,
		layerOverrides: Map<String, LayerClassificationOverride>,
		parentOverrides: Map<String, String?>,
		rigEdits: RigEditOverlay,
		status: String,
        settings: kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.JsonObject(emptyMap()),
		meshOverrides: Map<String, MeshSettings> = emptyMap(),
        generationSource: SourceArt? = null,
        meshSource: SourceArt? = null,
        placementSource: SourceArt? = null,
        textureOverrides: Map<String, io.github.psd2live.project.TextureOverride> = emptyMap(),
	): Boolean {
		var applied = false
		updateState { current ->
			applied = false
			if (
                current.projectId != expectedProjectId || current.projectOpenGeneration != expectedProjectOpenGeneration ||
				current.analysis?.source !== expectedSource ||
				current.documentLayerVisibility != expectedLayerVisibility ||
				current.deletedLayerIds != expectedDeletedLayerIds ||
				current.layerOverrides != expectedLayerOverrides ||
				current.parentOverrides != expectedParentOverrides ||
				current.rigEdits != expectedRigEdits ||
                current.generationSource != expectedGenerationSource ||
                current.meshSource != expectedMeshSource ||
                current.placementSource != expectedPlacementSource ||
                current.textureOverrides != expectedTextureOverrides ||
                current.meshOverrides != expectedMeshOverrides ||
                (expectedSettings.isNotEmpty() && io.github.psd2live.ui.state.WorkspaceStateCodec.settings(current) != expectedSettings)
			) return@updateState current
			applied = true
			io.github.psd2live.ui.state.WorkspaceStateCodec.decode(settings, current).copy(
				analysis = preview.analysis,
				previewModel = preview,
				previewModelDirty = false,
				documentLayerVisibility = layerVisibility,
				deletedLayerIds = deletedLayerIds,
				layerOverrides = layerOverrides,
				parentOverrides = parentOverrides,
				rigEdits = rigEdits,
				generationSource = generationSource,
				meshSource = meshSource,
                placementSource = placementSource,
                textureOverrides = textureOverrides,
				meshOverrides = meshOverrides,
				selectedLayerId = current.selectedLayerId?.takeIf { selected ->
					preview.analysis.layers.any { it.source.id.raw == selected } && selected !in deletedLayerIds
				},
				selectedLayerIds = current.selectedLayerIds.filterTo(LinkedHashSet()) { selected ->
					preview.analysis.layers.any { it.source.id.raw == selected } && selected !in deletedLayerIds
				},
				// Canvas solo is local presentation; a document commit neither ends nor forgets it.
				isolationSnapshot = current.isolationSnapshot,
				lockedParameters = current.lockedParameters.intersect(preview.rig.puppet.parameters.mapTo(mutableSetOf()) { it.id }),
				parameterValues = preview.rig.puppet.parameters.associate { parameter ->
					parameter.id to (current.parameterValues[parameter.id] ?: parameter.default).coerceIn(parameter.min, parameter.max)
				},
				statusText = status,
				errorMessage = null,
			)
		}
		if (applied) {
			previewRebuildJob?.cancel()
			resetCanvasPaintSessions()
		}
		return applied
	}

	internal fun refreshWorkspaceRenderer(preview: RigPreviewModel) = synchronized(stateLock) {
		// Publication belongs to the runtime commit. Renderer activation must not restore an old model.
		if (_state.value.previewModel === preview) refreshSdkSession(preview)
	}

	private fun scheduleRuntimeBundleUpdate() {
		if (_state.value.previewModel == null) return
		if (_state.value.isAnalyzing || _state.value.isGenerating) return

		previewRebuildJob?.cancel()
		previewRebuildJob = scope.launch {
			delay(200)
			try {
				val previous = _state.value.previewModel ?: return@launch
				val config = _state.value.buildConfig()
				val updated = runInterruptible(Dispatchers.Default) {
					pipeline.updateRuntimeBundle(previous, config)
				}
				var accepted = false
				updateState {
					if (it.previewModel !== previous || it.buildConfig() != config) it
					else { accepted = true; it.copy(previewModel = updated) }
				}
				if (accepted) refreshSdkSession(updated) else scheduleRuntimeBundleUpdate()
			} catch (failure: Throwable) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
				val detail = failure.message ?: failure.javaClass.simpleName
				updateState {
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
		val token = synchronized(stateLock) { ++previewRebuildToken }
		previewRebuildJob = scope.launch {
			delay(60)
			val isUpscalingJob = _state.value.textureUpscale.scale > 1 && _state.value.textureUpscale != previous.config.textureUpscale
			updateState { current ->
				val base = if (isUpscalingJob) {
					current.withLog(
						message = tr("log.upscaleStarting", current.textureUpscale.scale),
						level = LogLevel.INFO,
						tag = "Upscale",
					).updateActiveWorkspace { it.copy(hiddenModules = it.hiddenModules - "log") }
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
				val config = buildPreviewConfig(_state.value)
				var lastReportedStage: String? = null
				val progress = ProgressListener { stage, frac ->
					updateState { current ->
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
				var published = false
				updateState { current ->
					if (previewRebuildToken != token) return@updateState current
					published = true
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
						rigEdits = if (previewMeshSettingsOverrides.isEmpty()) rebuilt.config.rigEdits else current.rigEdits,
						atlasSize = maxOf(current.atlasSize, packedAtlasSize),
						isUpscaling = false,
						progress = 1f,
						lockedParameters = current.lockedParameters.intersect(validParamIds),
						parameterValues = rebuilt.rig.puppet.parameters.associate { param ->
							param.id to (current.parameterValues[param.id] ?: param.default).coerceIn(param.min, param.max)
						},
						statusText = tr("status.layerChangesApplied"),
						errorMessage = null,
					)
				}
				if (published) refreshSdkSession(rebuilt)
			} catch (failure: Throwable) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
				val detail = failure.message ?: failure.javaClass.simpleName
				updateState {
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

	private fun buildPreviewConfig(state: PSD2LiveState): PipelineConfig {
		val config = state.buildConfig()
		val previews = synchronized(stateLock) { previewMeshSettingsOverrides.toMap() }
		if (previews.isEmpty()) return config
		return config.copy(meshOverrides = config.meshOverrides + previews)
	}

	@Volatile private var latestLiveParameters: Map<ParameterId, Float> = emptyMap()

	private val _livePose = MutableStateFlow<Map<ParameterId, Float>>(emptyMap())
	/**
	 * Per-parameter mirror of [livePose] for Compose. Reading [livePoseOf] only invalidates the caller when
	 * that parameter's live value changes, so the parameters list does not recompose every row on every frame.
	 */
	private val _livePoseSnapshot: SnapshotStateMap<ParameterId, Float> = mutableStateMapOf()
	/**
	 * The pose the preview shows now, one update per rendered frame at the project rate: what the parameters
	 * list and the physics panel read, so neither runs a clock of its own. Playing, it is the whole pose; paused,
	 * only what the pointer's look and physics move, so a slider being dragged reads the document at once
	 * instead of the frame before. Readers lay it over the document's values.
	 */
	val livePose: StateFlow<Map<ParameterId, Float>> = _livePose.asStateFlow()

	/**
	 * What every view shows for the active workspace: the authored pose (with changes still committing), the
	 * evaluated frame and open motion over it ([livePose]), and the slider being dragged on top — the same order the
	 * parameter sliders read it in.
	 */
	internal fun shownPose(current: PSD2LiveState, live: Map<ParameterId, Float> = livePose.value): Map<ParameterId, Float> {
		val scrub = parameterScrub?.takeIf { it.generation == current.projectOpenGeneration && it.workspaceId == current.activeWorkspace.id }
		val base = if (live.isEmpty()) current.parameterValues else current.parameterValues + live
		return if (scrub == null || scrub.overrides.isEmpty()) base else base + scrub.overrides
	}

	/** Compose-readable live value for [id]; reading it only invalidates when that entry changes. */
	fun livePoseOf(id: ParameterId): Float? = _livePoseSnapshot[id]

	/** The evaluated frame's part of [livePose]: animation, the pointer's look or paused physics. */
	@Volatile private var liveFramePose: Map<ParameterId, Float> = emptyMap()
	/**
	 * The timeline's part of [livePose]: while a motion is selected the preview poses its curves at the playhead,
	 * playing or not, so the sliders of those parameters follow the playhead like the canvases do.
	 */
	@Volatile private var motionFramePose: Map<ParameterId, Float> = emptyMap()

	private fun setLivePose(next: Map<ParameterId, Float>) {
		liveFramePose = next
		emitLivePose()
	}

	private fun setMotionFramePose(next: Map<ParameterId, Float>) {
		if (next == motionFramePose) return
		motionFramePose = next
		emitLivePose()
	}

	/** Publish the frame and timeline poses to both the StateFlow readers and the per-key Compose snapshot. */
	private fun emitLivePose() {
		val frame = liveFramePose; val motion = motionFramePose
		val next = if (motion.isEmpty()) frame else if (frame.isEmpty()) motion else motion + frame
		if (next == _livePose.value) return
		_livePose.value = next
		if (next.isEmpty()) {
			if (_livePoseSnapshot.isNotEmpty()) _livePoseSnapshot.clear()
			return
		}
		if (_livePoseSnapshot.isNotEmpty()) {
			val stale = _livePoseSnapshot.keys.filter { it !in next }
			for (id in stale) _livePoseSnapshot.remove(id)
		}
		for ((id, value) in next) {
			if (_livePoseSnapshot[id] != value) _livePoseSnapshot[id] = value
		}
	}
	/** When the preview's frame pump last advanced the motion clock; the fallback loop stays out while it runs. */
	private var lastPumpTickNanos = 0L
	val activeMotionName: String? get() = processActiveMotion

	/** Plays a one-shot or starts idle on the shared process clock. */
	fun triggerMotion(name: String) {
		configurePlayback("trigger", kotlinx.serialization.json.buildJsonObject { put("name", name) })
	}

	private fun stopProcessMotion(name: String? = null) {
		if (name != null && !name.equals(processActiveMotion, true)) return
		if (processActiveMotion == null) return
		processActiveMotion = null
		scope.launch { configurePlayback("stop_motion", kotlinx.serialization.json.buildJsonObject { name?.let { put("name", it) } }) }
	}

	private fun startMotionLoop() {
		// The preview's frame pump drives the clock (see [requestSdkFrame]), so motion, follow and physics
		// step with the frames the preview shows. This loop keeps them going when no pump runs.
		motionJob = scope.launch {
			while (isActive) {
				if (System.nanoTime() - lastPumpTickNanos > PUMP_IDLE_NANOS) tickMotion()
				delay((frameIntervalNanos(_state.value.rigEdits.physicsFps).takeIf { it > 0 } ?: UNLIMITED_TICK_NANOS) / 1_000_000L)
			}
		}
	}

	private fun tickMotion() {
		try {
			advanceMotionFrame()
		} catch (cancelled: kotlinx.coroutines.CancellationException) {
			throw cancelled
		} catch (failure: Throwable) {
			// One bad frame must not end the loop: nothing would play again until a restart.
			stopProcessMotion()
			addLog(
				message = failure.message ?: failure.javaClass.simpleName,
				level = LogLevel.WARNING,
				tag = "Motion",
				detail = failure.stackTraceToString(),
			)
		}
	}

	private fun advanceMotionFrame() {
		val now = System.nanoTime()
		val dt = ((now - lastTick) / 1_000_000_000.0).coerceIn(0.001, 0.08).toFloat()
		lastTick = now

		var current = _state.value
		if (current.previewModel != null) syncPlaybackSession(current)
		current = _state.value
		// The timeline plays and poses the edit canvases too, with no preview on screen.
		val commandsSeen = playbackCommands
		val processFrame = if ((current.previewLive || motionEditor.clipId != null) && current.previewModel != null && processPlaybackActive)
			(workspaceBackend as? io.github.psd2live.application.WorkspacePreviewPort)?.playbackFrame(dt) else null
		if (processFrame != null) applyPlaybackFrame(processFrame, commandsSeen)
		if (commandsSeen != playbackCommands) return
		current = _state.value
		val inPreview = current.previewLive
		val isMeshOnly = current.meshOnly
		val anim = inPreview && current.animationEnabled && !isMeshOnly
		val tracking = inPreview && current.mouseTrackingEnabled && !isMeshOnly && current.activeWorkspace.pose?.authoringPose != true

		val model = current.previewModel
		val pausedPhysicsOn = inPreview && !anim && current.generatePhysics && !isMeshOnly &&
			current.activeWorkspace.pose?.authoringPose != true
		if (model != null && inPreview && (anim || tracking)) {
			val liveParams = if (isMeshOnly) {
				model.rig.puppet.parameters.associate { it.id to it.default }
			} else processFrameValues.let { inputs ->
				// 4. Physics reads the posed inputs and writes its outputs over them, as Cubism evaluates it.
				val boundedInputs = io.github.psd2live.core.boundedPreviewPose(inputs, model.rig.puppet.parameters)
				if (anim) boundedInputs + stepSoftwarePhysics(true, current, model, boundedInputs, dt) else boundedInputs
			}
			val boundedLiveParams = io.github.psd2live.core.boundedPreviewPose(liveParams, model.rig.puppet.parameters)
			latestLiveParameters = boundedLiveParams
			// Paused physics publishes the complete pose below. Publishing the bare pose here
			// first lets the software canvas alternate between resting and swinging parts.
			if (current.sdkStatus != "ready" && !pausedPhysicsOn) {
				updateState { latest ->
					// An SDK frame may have arrived since this tick read the state; it owns the live pose then.
					if (!latest.previewLive || latest.sdkStatus == "ready") latest
					else {
						val mergedValues = io.github.psd2live.core.boundedPreviewPose(
							parameterValuesAfterSoftwareFrame(latest, boundedLiveParams, pointerActive), model.rig.puppet.parameters)
						setLivePose(when {
							anim -> mergedValues
							pointerActive -> mergedValues.filterKeys { it in POINTER_POSE_PARAMETERS }
							else -> emptyMap()
						})
						if (mergedValues === latest.previewParameterValues) latest
						else latest.copy(previewParameterValues = mergedValues)
					}
				}
			}
		} else if (current.sdkStatus != "ready" && !pausedPhysicsOn && pausedPhysics.isEmpty()) {
			// Stopped and not following the pointer: the software preview shows the authored pose again, so the
			// sliders must not keep the last animated frame.
			setLivePose(emptyMap())
		}
		// 5. Paused, physics still runs, on the pose the user sets: a slider or the pointer's look swings it.
		stepPausedPhysics(current, model, pausedPhysicsOn, tracking, dt)
		// 6. The live simulation follows whichever pose the preview now shows.
		if (inPreview) stepSimulationPreview(current, model, dt)
	}

	private fun stepSoftwarePhysics(playing: Boolean, state: PSD2LiveState, model: RigPreviewModel,
		inputs: Map<ParameterId, Float>, dt: Float): Map<ParameterId, Float> {
		val port = workspaceBackend as? io.github.psd2live.application.WorkspacePreviewPort ?: return emptyMap()
		val expected = currentWorkspaceState() ?: return emptyMap()
		val result = port.previewPhysics(kotlinx.serialization.json.buildJsonObject {
			put("state", expected); put("dt", dt); put("playing", playing)
			putJsonObject("values") { inputs.forEach { (id, value) -> put(id.raw, value) } }
		})
		processPhysicsActive = true
		if (!playing) pausedPhysicsSettled = result.getValue("settled").jsonPrimitive.boolean
		return result.getValue("outputs").jsonObject.map { (id, value) -> ParameterId(id) to value.jsonPrimitive.float }.toMap()
	}

	@Volatile private var processPhysicsActive = false
	private fun resetPreviewPhysics() {
		if (!processPhysicsActive) return
		processPhysicsActive = false
		val port = workspaceBackend as? io.github.psd2live.application.WorkspacePreviewPort ?: return
		scope.launch {
			val expected = currentWorkspaceState() ?: return@launch
			try { port.previewPhysics(kotlinx.serialization.json.buildJsonObject { put("state", expected); put("dt", 0); put("reset", true) }) }
			catch (_: io.github.psd2live.application.WorkspaceConflict) { /* A replacement owns its own fresh clock. */ }
		}
	}

	/**
	 * The physics outputs a paused preview shows over the edit pose. Cubism's update would also advance the
	 * paused motion, so the preview runs physics here, on the engine checked against the SDK frame by frame,
	 * and hands the outputs to the renderer with the pose.
	 */
	private var pausedPhysics: Map<ParameterId, Float> = emptyMap()
	/** True once the paused physics has come to rest; the preview then stops asking for frames. */
	@Volatile var pausedPhysicsSettled = true
		private set

	private fun stepPausedPhysics(current: PSD2LiveState, model: RigPreviewModel?, on: Boolean, tracking: Boolean, dt: Float) {
		if (!on || model == null || current.activeWorkspace.pose?.authoringPose == true) {
			// The software preview let go of the swing: back to the edit pose, unless the pointer holds a look.
			if (pausedPhysics.isNotEmpty() && current.sdkStatus != "ready" && !pointerActive) {
				updateState { latest ->
					// An SDK frame may have arrived since this tick read the state; it owns the live pose then.
					if (latest.sdkStatus == "ready") latest
					else {
						setLivePose(emptyMap())
						if (latest.previewParameterValues == latest.parameterValues) latest else latest.copy(previewParameterValues = latest.parameterValues)
					}
				}
			}
			pausedPhysics = emptyMap()
			pausedPhysicsSettled = true
			return
		}
		val panel = current.previewPanelState()
		val posed = if ((tracking || motionEditor.clipId != null) && processFrameValues.isNotEmpty()) processFrameValues else panel.parameterValues
		val pose = parameterScrubPose(current, posed)
		val out = stepSoftwarePhysics(false, current, model, pose, dt)
		pausedPhysics = out
		if (current.sdkStatus != "ready") {
			val shown = io.github.psd2live.core.boundedPreviewPose(pose + out, model.rig.puppet.parameters)
			setLivePose((if (tracking && pointerActive) pose.filterKeys { it in POINTER_POSE_PARAMETERS } else emptyMap()) + out)
			updateState { latest -> if (!latest.previewLive || latest.previewParameterValues == shown) latest else latest.copy(previewParameterValues = shown) }
		}
	}

	fun computeLiveParameters(model: RigPreviewModel, current: PSD2LiveState = _state.value,
		blink: Float = 1f, motion: Map<ParameterId, Float> = emptyMap()): Map<ParameterId, Float> =
		io.github.psd2live.core.boundedPreviewPose(processFrameValues.ifEmpty { current.parameterValues } + motion, model.rig.puppet.parameters)

	fun requestSdkFrame(
		width: Int,
		height: Int,
		scale: Float,
		offsetX: Float,
		offsetY: Float,
		deltaTime: Float = 1f / 60f,
		frameTimeNanos: Long = System.nanoTime(),
        viewId: String = "",
	) {
		var snapshot = _state.value
		val keyPrefix = "${snapshot.projectOpenGeneration}/${snapshot.activeWorkspace.id}/"
		var canvas = if (viewId.startsWith(keyPrefix)) {
			snapshot.activeWorkspace.canvases.firstOrNull {
				it.mode == CanvasMode.PREVIEW && "${it.id}/PREVIEW" == viewId.removePrefix(keyPrefix)
			}
		} else null
		if (viewId.isNotEmpty() && canvas == null) return
		if (snapshot.previewModel == null) return
		// The canvas the panels follow drives the clock, once per frame it asks for.
		val drivesClock = canvas == null || canvas.id == snapshot.previewControlCanvas().id
		if (drivesClock) {
			lastPumpTickNanos = System.nanoTime()
			tickMotion()
		}
		val latest = _state.value
		if (latest.projectOpenGeneration != snapshot.projectOpenGeneration || latest.activeWorkspace.id != snapshot.activeWorkspace.id) return
		snapshot = latest
		canvas = canvas?.let { previous -> snapshot.activeWorkspace.canvases.firstOrNull { it.id == previous.id && it.mode == CanvasMode.PREVIEW } ?: return }
		val presentation = if (canvas == null || canvas.id == snapshot.activeCanvas.id)
			CanvasPresentation.capture(snapshot) else canvas.presentation
		val inPreview = snapshot.previewLive
		if (inPreview && sdkSessionNeedsReload) {
			ensureSdkSessionLoaded()
		}
		val isAnim = inPreview && presentation.animationEnabled && !snapshot.meshOnly
		val tracking = inPreview && presentation.mouseTrackingEnabled && !snapshot.meshOnly && snapshot.activeWorkspace.pose?.authoringPose != true
		val liveParams = latestLiveParameters
		val previewValues = parameterValuesForPreview(
			snapshot, presentation.animationEnabled, parameterScrubPose(snapshot, presentation.parameterValues),
			presentation.lockedParameters, liveParams,
		).let { pose ->
			// Playing, the pose already carries the session frame and its physics; paused, the frame adds tracking.
			val framed = if (!isAnim && (tracking || motionEditor.clipId != null)) pose + processFrameValues.filterKeys { it !in presentation.lockedParameters } else pose
			parameterScrubPose(snapshot, if (!isAnim && snapshot.activeWorkspace.pose?.authoringPose != true && pausedPhysics.isNotEmpty()) framed + pausedPhysics else framed)
		}
		sdkSession.render(
			CubismSdkPreviewSession.RenderRequest(
				width = width,
				height = height,
				scale = scale,
				offsetX = offsetX,
				offsetY = offsetY,
				deltaTime = deltaTime,
				// The workspace session is the only clock: idle, motions, tracking and physics arrive as the
				// pose, so Cubism renders it without running its own motion, drag or physics update.
				pointerX = 0f,
				pointerY = 0f,
				animationEnabled = isAnim,
				nativeClock = false,
				parameterOverrides = previewValues,
				parameterDefinitions = snapshot.previewModel?.rig?.puppet?.parameters.orEmpty(),
				pointerTrackingEnabled = false,
				lockedParameters = presentation.lockedParameters,
				frameTimeNanos = frameTimeNanos,
                viewId = viewId,
			),
		)
	}

	private val isClosed = java.util.concurrent.atomic.AtomicBoolean(false)

	// After every field the motion loop reads: an earlier init would race property initializers
	// that sit lower in this class (pausedPhysics, live pose, etc.).
	init {
		startMotionLoop()
		// What the atlas layout reports (a fit below 1, pages or locks beyond the budget) goes to the log only, once per change.
		scope.launch {
			_state.map { it.previewModel?.atlas?.notices.orEmpty() }.distinctUntilChanged().collect { notices ->
				notices.forEach { addLog(it, level = LogLevel.WARNING, tag = "Texture") }
			}
		}
	}

	override fun close() {
		if (!isClosed.compareAndSet(false, true)) return
		motionJob?.cancel()
		previewRebuildJob?.cancel()
		activeWorkJob?.cancel()
		scope.cancel()
		sdkSession.close()
        canvasFrames.clear()
        canvasFrameUsers.clear()
        synchronized(canvasEditors) { canvasEditors.clear() }
	}

	internal fun setStateForTest(state: PSD2LiveState) {
		replaceState(state)
	}

	private companion object {
		const val SDK_PARAMETER_PUBLISH_INTERVAL_NANOS = 100_000_000L
		/** Without a pump frame for this long, the fallback loop runs the clock. */
		const val PUMP_IDLE_NANOS = 100_000_000L
		/** The fallback loop's step when the rate is unlimited. */
		const val UNLIMITED_TICK_NANOS = 16_000_000L
		/** Cubism's force priority: a triggered motion always replaces the one playing. */
		const val MOTION_PRIORITY_FORCE = 3
		const val PREF_LAST_EXPORT_DIR = "last_export_dir"
		/** The token every slider shares; the call sites predate the per-field tokens and stay untouched. */
		const val SLIDER_SESSION = "slider"
		const val MOTION_DRAG_SESSION = "motion-drag"
	}
}

/**
 * The paused preview's pointer pose, with tracking owning the look axes rather than adding to an old edit.
 */
internal fun pausedPointerPose(values: Map<ParameterId, Float>, x: Float, y: Float): Map<ParameterId, Float> {
	return io.github.psd2live.core.pointerPreviewPose(values, x, y, StandardParameters.all, x != 0f || y != 0f)
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
): Map<ParameterId, Float> = parameterValuesForPreview(
	state, state.animationEnabled, state.parameterValues, state.lockedParameters, liveParams,
)

internal fun parameterValuesForPreview(
	state: PSD2LiveState,
	animationEnabled: Boolean,
	parameterValues: Map<ParameterId, Float>,
	lockedParameters: Set<ParameterId>,
	liveParams: Map<ParameterId, Float>,
): Map<ParameterId, Float> {
	if (!animationEnabled || !state.previewLive) {
		return parameterValues
	}
	if (state.meshOnly) {
		val defaults = state.previewModel?.rig?.puppet?.parameters?.associate { it.id to it.default } ?: emptyMap()
		return defaults + parameterValues.filterKeys { it in lockedParameters }
	}

	// The session frame carries the idle, motion, tracking and physics; locked inspector values stay authoritative.
	return parameterValues + liveParams.filterKeys { it !in lockedParameters }
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
): Boolean = state.previewLive &&
	(frameAnimationEnabled == (state.animationEnabled && !state.meshOnly))

/** Marks the coroutine that commits one queued GUI pose change, so its projection knows which change landed. */
internal class PendingPoseCommit(val id: Long, val workspaceId: String) :
    kotlin.coroutines.AbstractCoroutineContextElement(Key) {
    companion object Key : kotlin.coroutines.CoroutineContext.Key<PendingPoseCommit>
}
