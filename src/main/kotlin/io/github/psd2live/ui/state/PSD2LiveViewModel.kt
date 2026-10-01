package io.github.psd2live.ui.state

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
import io.github.psd2live.core.PhysicsEngine
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
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
    internal data class MeshSplitOffer(
        val layerId: String,
        val layerName: String,
        val plan: MeshComponentSplit.Plan,
        val preview: RigPreviewModel,
    )

    internal data class LayerSplitDecision(
        val offer: MeshSplitOffer,
        val names: List<String>,
        val sides: List<Side>,
    )

    internal data class BatchMeshSplitOffer(
        val offers: List<MeshSplitOffer>,
        val preview: RigPreviewModel,
    )

    internal var pendingMeshSplit by mutableStateOf<MeshSplitOffer?>(null)
        private set
    internal var pendingBatchMeshSplit by mutableStateOf<BatchMeshSplitOffer?>(null)
        private set

    /**
     * A swing being authored on the canvas. [draft] keeps the targets as picked (meshes stay meshes until the
     * commit wraps them); [gizmo] is the handle geometry on the live preview, where those meshes are wrapped.
     * [motion] is the direction the handles edit.
     */
    internal class SwingSession(val existingId: String?, draft: RigSwingEdit) {
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
    /** The preview before the session's live forms were patched in, and whether it was already stale. */
    private var swingPreviewBase: Pair<RigPreviewModel, Boolean>? = null
    /** The preview this session last installed; anything else means a rebuild replaced it underneath. */
    private var swingPatched: RigPreviewModel? = null
    private var swingPlayer: Job? = null

    /**
     * Starts a swing session on Warps or meshes; a target already swung (or wrapped for a swing) edits that
     * swing. The canvas switches to edit mode, where the handles live.
     */
    internal fun beginSwing(targets: List<String>) {
        val state = _state.value
        val puppet = state.previewModel?.rig?.puppet ?: return
        if (targets.isEmpty() || state.canvasEditBusy) return
        endSwing()
        val parents = targets.mapNotNull { id -> puppet.drawables.firstOrNull { it.id.raw == id }?.parentDeformerId?.raw }
        val existing = state.rigEdits.swingEdits.firstOrNull { swing -> swing.targets.any { it in targets || it in parents } }
        val draft = existing ?: swingDefaults(targets, SwingPreset.HAIR) ?: return
        setCanvasMode(state.activeCanvas.id, CanvasMode.EDIT)
        // One canvas session at a time: a pending placement would fight over the corner and the pointer.
        if (canvasEditor.placement != null) canvasEditor.cancelPlacement()
        swingSession = SwingSession(existing?.id, draft)
        updateSwing(draft)
    }

    /** Leaves the session without recording anything. */
    internal fun endSwing() {
        playSwing(false)
        previewSwing(null)
        swingSession = null
    }

    /** Replaces the draft and refreshes the canvas preview and handles. */
    internal fun updateSwing(draft: RigSwingEdit) {
        val session = swingSession ?: return
        session.draft = draft
        session.error = null
        session.motion = session.motion.coerceIn(0, draft.motions.size - 1)
        val result = previewSwing(draft)
        if (result == null) { session.error = session.error ?: tr("swing.failed"); return }
        session.preview = result
        refreshSwingGizmo()
    }

    /** Rebuilds the handles for the pose on screen, so they stay on the art when other parameters move. */
    internal fun refreshSwingGizmo() {
        val session = swingSession ?: return
        val (puppet, prepared) = session.preview ?: return
        session.gizmo = io.github.psd2live.core.SwingGizmo.of(puppet, prepared, values = _state.value.parameterValues, motion = session.motion)
    }

    /** Takes the settings a handle produced; the handles work on the wrap, the draft keeps the picked targets. */
    internal fun updateSwingSettings(settings: RigSwingEdit) {
        val draft = swingSession?.draft ?: return
        updateSwing(settings.copy(id = draft.id, name = draft.name, targets = draft.targets))
    }

    /** The draft reshaped by [change] against the rig without the session's preview; null when that fails. */
    private fun swingStructure(change: (PuppetModel, io.github.psd2live.core.RigEditOverlay) -> RigSwingEdit): RigSwingEdit? {
        val state = _state.value
        val puppet = (swingPreviewBase?.first ?: state.previewModel)?.rig?.puppet ?: return null
        return runCatching { change(puppet, state.rigEdits) }.onFailure { swingSession?.error = it.message }.getOrNull()
    }

    /** Sets which directions the draft moves in. */
    internal fun setSwingKinds(kinds: List<SwingKind>) {
        val draft = swingSession?.draft ?: return
        if (kinds.isEmpty()) return
        swingStructure { puppet, overlay -> SwingAuthoring.withKinds(puppet, overlay, draft, kinds) }?.let(::updateSwing)
    }

    /** Sets the number of segment parameters in [motion]. */
    internal fun setSwingSegments(motion: Int, segments: Int) {
        val draft = swingSession?.draft ?: return
        swingStructure { puppet, overlay -> SwingAuthoring.withSegments(puppet, overlay, draft, motion, segments) }?.let(::updateSwing)
    }

    /** Selects the direction the canvas handles edit. */
    internal fun selectSwingMotion(motion: Int) {
        val session = swingSession ?: return
        session.motion = motion.coerceIn(0, session.draft.motions.size - 1)
        refreshSwingGizmo()
    }

    /** Starts every direction of the draft from [preset]'s shape, with its pendulums sized again. */
    internal fun setSwingPreset(preset: SwingPreset) {
        val draft = swingSession?.draft ?: return
        val next = draft.copy(preset = preset, motions = draft.motions.map { m ->
            m.copy(shape = SwingPresets.shape(preset, m.kind).copy(flip = m.shape.flip))
        })
        swingStructure { puppet, _ -> SwingAuthoring.resized(puppet, next) }?.let(::updateSwing)
    }

    /** Gives every direction a pendulum sized from the target, or takes them all away. */
    internal fun setSwingPhysicsEnabled(enabled: Boolean) {
        val draft = swingSession?.draft ?: return
        val next = draft.withPhysics { _, _ -> if (enabled) io.github.psd2live.core.SwingPhysics() else null }
        if (!enabled) updateSwing(next) else swingStructure { puppet, _ -> SwingAuthoring.resized(puppet, next) }?.let(::updateSwing)
    }

    /** A new left/right swing on [targets] with fresh IDs, preset values and a pendulum sized from the first target. */
    internal fun swingDefaults(targets: List<String>, preset: SwingPreset): RigSwingEdit? {
        val state = _state.value
        val puppet = (swingPreviewBase?.first ?: state.previewModel)?.rig?.puppet ?: return null
        val first = targets.firstOrNull() ?: return null
        val name = puppet.deformers.firstOrNull { it.id.raw == first }?.name
            ?: puppet.drawables.firstOrNull { it.id.raw == first }?.name ?: first
        val (id, parameters) = SwingAuthoring.freshIds(puppet, state.rigEdits, first, 1)
        return RigSwingEdit.single(id, tr("swing.defaultName", name), SwingKind.LATERAL, targets, parameters,
            shape = SwingPresets.shape(preset, SwingKind.LATERAL), preset = preset,
            physics = SwingPresets.physics(preset, SwingKind.LATERAL, swingLength(puppet, first)))
    }

    /** The pinned-edge-to-tip length of a Warp, or the long side of a mesh, in canvas pixels. */
    private fun swingLength(puppet: PuppetModel, target: String): Float? {
        SwingGenerator.measure(puppet, target)?.let { return it.second }
        val drawable = puppet.drawables.firstOrNull { it.id.raw == target } ?: return null
        val bounds = RigGeometryTools.bounds(drawable.mesh?.positions ?: return null)
        // Under a Warp the mesh is in its 0..1 space; scale by that Warp's own length.
        val scale = (puppet.deformers.firstOrNull { it.id == drawable.parentDeformerId } as? Deformer.Warp)
            ?.let { SwingGenerator.measure(puppet, it.id.raw)?.second } ?: 1f
        return maxOf(bounds[2], bounds[3]) * scale
    }

    /**
     * Shows [edit]'s forms on the canvas without recording anything; null restores the committed preview.
     * Returns the patched rig and the edit as replayed there, with mesh targets turned into their wraps.
     */
    private fun previewSwing(edit: RigSwingEdit?): Pair<PuppetModel, RigSwingEdit>? {
        val current = _state.value
        // A rebuild (undo, another edit) replaced the patched preview: that is the new base.
        if (swingPreviewBase != null && current.previewModel !== swingPatched) swingPreviewBase = null
        val base = swingPreviewBase
        if (edit == null) {
            if (base != null) {
                swingPreviewBase = null
                updateState { it.copy(previewModel = base.first, previewModelDirty = base.second) }
            }
            swingPatched = null
            return null
        }
        val (preview, _) = base ?: ((current.previewModel ?: return null) to current.previewModelDirty).also { swingPreviewBase = it }
        val result = runCatching {
            // The committed version of this swing may drive other parameters: take its forms out first.
            val puppet = current.rigEdits.swingEdits.firstOrNull { it.id == edit.id }
                ?.let { SwingGenerator.strip(preview.rig.puppet, it) } ?: preview.rig.puppet
            val overlay = SwingAuthoring.put(current.rigEdits, puppet, edit)
            val prepared = overlay.swingEdits.single { it.id == edit.id }
            val wrapped = overlay.authoringJournal.drop(current.rigEdits.authoringJournal.size).fold(puppet, RigAuthoringJournal::apply)
            SwingGenerator.apply(wrapped, listOf(prepared)) to prepared
        }.onFailure { swingSession?.error = it.message }.getOrNull() ?: return null
        val patched = preview.copy(rig = preview.rig.copy(puppet = result.first))
        swingPatched = patched
        updateState { it.copy(previewModel = patched, previewModelDirty = true) }
        return result
    }

    /** Sways the session's parameters between -1 and 1, each lower segment trailing and up/down out of step, until stopped. */
    internal fun playSwing(play: Boolean) {
        val session = swingSession
        swingPlayer?.cancel()
        swingPlayer = null
        if (session == null) return
        session.playing = play
        if (!play) {
            val ids = session.draft.parameterIds.map(::ParameterId)
            updateState { it.copy(parameterValues = it.parameterValues + ids.associateWith { 0f }) }
            return
        }
        swingPlayer = scope.launch {
            val start = System.nanoTime()
            while (isActive) {
                val t = (System.nanoTime() - start) / 1e9f
                val values = session.draft.motions.withIndex().flatMap { (m, motion) ->
                    motion.parameterIds.withIndex().map { (k, id) ->
                        ParameterId(id) to sin(2f * PI.toFloat() * t / (if (m == 0) 1.6f else 1.3f) - k * 0.7f - m * 1.3f)
                    }
                }.toMap()
                updateState { it.copy(parameterValues = it.parameterValues + values) }
                delay(16)
            }
        }
    }

    /** Records the session's swing as one history node and ends the session. */
    internal fun commitSwing() {
        val session = swingSession ?: return
        val draft = session.draft
        runSwingMutation(session) { workspace, head -> workspace.putSwing(draft, false, head, null, io.github.psd2live.agent.MutationAuthor.USER) }
    }

    /** Deletes the edited swing; [bake] first keeps its current forms as ordinary keys. */
    internal fun deleteSwing(bake: Boolean) {
        val session = swingSession ?: return
        val id = session.existingId ?: return
        runSwingMutation(session) { workspace, head -> workspace.deleteSwing(id, bake, head, io.github.psd2live.agent.MutationAuthor.USER) }
    }

    private fun runSwingMutation(
        session: SwingSession,
        mutation: suspend (AgentWorkspace, String) -> io.github.psd2live.agent.AgentWorkspaceMutationResult,
    ) {
        if (_state.value.canvasEditBusy || session.busy) return
        playSwing(false)
        previewSwing(null)
        session.busy = true
        updateState { it.copy(canvasEditBusy = true) }
        scope.launch {
            try {
                val workspace = requireNotNull(agentWorkspace) { "Project workspace unavailable" }
                val head = requireNotNull(workspace.snapshot().historyHeadNodeId) { "Project history unavailable" }
                withContext(Dispatchers.Default) { mutation(workspace, head) }
                if (swingSession === session) swingSession = null
            } catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                session.error = failure.message ?: tr("swing.failed")
                if (swingSession === session) updateSwing(session.draft)
            } finally {
                session.busy = false
                updateState { it.copy(canvasEditBusy = false) }
            }
        }
    }
    // Simulation: the panel's edits commit through the workspace like swings; the live preview runs here.

    private val simPreview = io.github.psd2live.core.sim.SimPreview()
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
    private var simBaking: kotlinx.coroutines.Job? = null

    /**
     * Bakes simulation [id] off the frame thread into parameters, keyforms and pendulums, then commits it
     * as one history node. Progress arrives in [simulationBaking], a failure in [simulationStatus].
     */
    internal fun bakeSimulation(id: String) {
        val current = _state.value
        val model = current.previewModel ?: return
        if (simBaking?.isActive == true || current.rigEdits.simEdits.none { it.id == id }) return
        _simulationBaking.value = SimulationBaking(id, 0f)
        simBaking = scope.launch {
            try {
                val bake = withContext(Dispatchers.Default) {
                    val job = coroutineContext[kotlinx.coroutines.Job]
                    io.github.psd2live.core.sim.SimAuthoring.bake(current.rigEdits, model.baseRig.puppet, id,
                        progress = { _simulationBaking.value = SimulationBaking(id, it) },
                        cancelled = { job?.isCancelled == true },
                    )
                }
                runSimulationMutation("Baked simulation $id") { workspace, head -> workspace.putSimulationBake(id, bake, head) }
            } catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                _simulationStatus.value = SimulationStatus.Failed(failure.message ?: failure.toString())
            } finally {
                _simulationBaking.value = null
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
     * Bakes [simulationsToBake] one after another, each on the rig with the bakes before it, and commits
     * them together as one history node. A simulation that fails keeps its old bake and is reported.
     */
    internal fun bakeAllSimulations() {
        val current = _state.value
        val model = current.previewModel ?: return
        val ids = simulationsToBake(current)
        if (simBaking?.isActive == true || ids.isEmpty()) return
        _simulationBaking.value = SimulationBaking(ids.first(), 0f, 0, ids.size)
        simBaking = scope.launch {
            try {
                val (bakes, failures) = withContext(Dispatchers.Default) {
                    val job = coroutineContext[kotlinx.coroutines.Job]
                    var overlay = current.rigEdits
                    val bakes = LinkedHashMap<String, io.github.psd2live.core.sim.SimBakeResult?>()
                    val failures = ArrayList<String>()
                    ids.forEachIndexed { index, id ->
                        try {
                            val bake = io.github.psd2live.core.sim.SimAuthoring.bake(overlay, model.baseRig.puppet, id,
                                progress = { _simulationBaking.value = SimulationBaking(id, it, index, ids.size) },
                                cancelled = { job?.isCancelled == true },
                            )
                            overlay = io.github.psd2live.core.sim.SimAuthoring.withBake(overlay, id, bake)
                            bakes[id] = bake
                        } catch (failure: IllegalArgumentException) {
                            failures += "$id: ${failure.message ?: failure}"
                        }
                        if (job?.isCancelled == true) throw kotlinx.coroutines.CancellationException("Bake cancelled")
                    }
                    bakes to failures
                }
                if (bakes.isNotEmpty()) runSimulationMutation("Baked simulations ${bakes.keys.joinToString()}") { workspace, head ->
                    workspace.putSimulationBakes(bakes, head)
                }
                if (failures.isNotEmpty()) _simulationStatus.value = SimulationStatus.Failed(failures.joinToString("\n"))
            } catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                _simulationStatus.value = SimulationStatus.Failed(failure.message ?: failure.toString())
            } finally {
                _simulationBaking.value = null
            }
        }
    }

    /** Removes every simulation's bake as one history node. */
    internal fun clearAllSimulationBakes() {
        val ids = _state.value.rigEdits.simEdits.filter { it.bake != null }.map { it.id }
        if (ids.isEmpty()) return
        runSimulationMutation("Cleared simulation bakes") { workspace, head -> workspace.putSimulationBakes(ids.associateWith { null }, head) }
    }

    internal fun cancelSimulationBake() {
        simBakeCancelled = true
        simBaking?.cancel()
        simBaking = null
        _simulationBaking.value = null
    }

    @Volatile private var simBakeCancelled = false

    /**
     * Runs [bake] for simulation [id] with its progress shown in [simulationBaking] and the panel's Cancel
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
        runSimulationMutation("Cleared simulation bake $id") { workspace, head -> workspace.putSimulationBake(id, null, head) }

    /** Runs simulation [id] live in the preview, or stops it with null. */
    internal fun setSimulationPreview(id: String?) {
        if (_state.value.simulationPreviewId == id) return
        simPreparing?.cancel()
        simPreview.clear()
        _simulationFrames.value = null
        _simulationStatus.value = SimulationStatus.Idle
        updateState { it.copy(simulationPreviewId = id) }
    }

    /** Puts the live simulation back at rest. */
    internal fun restartSimulationPreview() {
        val model = _state.value.previewModel ?: return
        simPreview.restart(model.rig.puppet, simulationPose(_state.value, model))
    }

    /** Creates or replaces [edit] as one history node. */
    internal fun putSimulation(edit: io.github.psd2live.core.sim.RigSimEdit) =
        runSimulationMutation("Set simulation ${edit.id}") { workspace, head ->
            val (result, report) = workspace.putSimulation(edit.toJson(), head, null)
            report["bake_error"]?.jsonPrimitive?.contentOrNull?.let { _simulationStatus.value = SimulationStatus.Failed(it) }
            result
        }

    internal fun deleteSimulation(id: String) {
        if (_state.value.simulationPreviewId == id) setSimulationPreview(null)
        runSimulationMutation("Deleted simulation $id") { workspace, head -> workspace.deleteSimulation(id, head) }
    }

    internal fun applyModelPreset(preset: io.github.psd2live.core.sim.ModelPresets.Preset, selectedOnly: Boolean) {
        val current = _state.value
        val layers = if (selectedOnly) current.selectedLayerIds.ifEmpty { setOfNotNull(current.selectedLayerId) } else emptySet()
        if (selectedOnly && layers.isEmpty()) return
        _simulationStatus.value = SimulationStatus.Idle
        runSimulationMutation("Applied model preset ${preset.name}") { workspace, head ->
            workspace.applyModelPreset(preset, layers, head, io.github.psd2live.agent.MutationAuthor.USER)
        }
    }

    internal fun beginCanvasCreationPreset(rotation: Boolean, divisions: Int = 5) {
        val current = _state.value
        setCanvasMode(current.activeCanvas.id, CanvasMode.EDIT)
        canvasEditor.updatePlacementGrid(divisions, divisions)
        canvasEditor.updatePlacementBezier(if (divisions > 5) 3 else 2, if (divisions > 5) 3 else 2)
        canvasEditor.activateTool(if (rotation) io.github.psd2live.ui.CanvasTool.CREATE_ROTATION else io.github.psd2live.ui.CanvasTool.CREATE_WARP)
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
        putSimulation(io.github.psd2live.core.sim.RigSimEdit(id, id, kind, meshes))
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

    private fun runSimulationMutation(
        summary: String,
        mutation: suspend (AgentWorkspace, String) -> io.github.psd2live.agent.AgentWorkspaceMutationResult,
    ) {
        if (_state.value.canvasEditBusy) return
        updateState { it.copy(canvasEditBusy = true) }
        scope.launch {
            try {
                val workspace = requireNotNull(agentWorkspace) { "Project workspace unavailable" }
                val head = requireNotNull(workspace.snapshot().historyHeadNodeId) { "Project history unavailable" }
                withContext(Dispatchers.Default) { mutation(workspace, head) }
            } catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                _simulationStatus.value = SimulationStatus.Failed(failure.message ?: summary)
            } finally {
                updateState { it.copy(canvasEditBusy = false) }
            }
        }
    }

    /** The pose the live simulation follows: the playing preview's, or the paused edit pose with its physics. */
    private fun simulationPose(current: PSD2LiveState, model: RigPreviewModel): Map<ParameterId, Float> =
        if (current.previewLive && current.animationEnabled && !current.meshOnly && latestLiveParameters.isNotEmpty()) latestLiveParameters
        else parameterScrubPose(current, current.previewPanelState().parameterValues) + pausedPhysics

    private fun stepSimulationPreview(current: PSD2LiveState, model: RigPreviewModel?, dt: Float) {
        val id = current.simulationPreviewId ?: return
        val edit = current.rigEdits.simEdits.firstOrNull { it.id == id }
        if (model == null || edit == null) {
            _simulationFrames.value = null
            return
        }
        val puppet = model.rig.puppet
        // The live body replaces what its own bake would add, so the baked modes stay at rest under it.
        val pose = io.github.psd2live.core.sim.SimGenerator.withoutModes(simulationPose(current, model), edit)
        if (simPreview.needs(puppet, edit)) {
            simPreparing?.cancel()
            simPreview.begin(puppet, edit)
            _simulationStatus.value = SimulationStatus.Preparing
            simPreparing = scope.launch {
                _simulationStatus.value = try {
                    SimulationStatus.Running(withContext(Dispatchers.Default) { simPreview.prepare(puppet, edit, pose) })
                } catch (failure: Exception) {
                    if (failure is kotlinx.coroutines.CancellationException) throw failure
                    SimulationStatus.Failed(failure.message ?: failure.toString())
                }
            }
            return
        }
        simPreview.step(puppet, edit, pose, dt)?.let { _simulationFrames.value = it }
    }

    /** False while a live simulation needs frames, so the paused preview keeps pumping. */
    val previewSettled: Boolean get() = pausedPhysicsSettled && _state.value.simulationPreviewId == null

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
                    pendingBatchMeshSplit = null
                    meshSplitQueue.clear()
                    manualMeshSplitRequests.clear()
                }
                // Pruning walks the entire rig and every canvas session. Pointer hover, camera
                // and dock updates must never pay that cost; only a new model can invalidate ids.
                if (next.previewModel !== current.previewModel ||
                    next.projectOpenGeneration != current.projectOpenGeneration
                ) pruneCanvasSessions(next) else next
            }
            val after = _state.value
            if (before.activeWorkspace.id != after.activeWorkspace.id ||
                before.projectOpenGeneration != after.projectOpenGeneration) {
                motionEditor.playing = false
                motionPlayer.stop()
                latestLiveParameters = emptyMap()
                pausedPhysics = emptyMap()
                setLivePose(emptyMap())
                physicsClock = PhysicsClock.NONE
                pointerActive = false
                followX = 0f
                followY = 0f
                elapsed = 0.0
            } else if (before.previewModel !== after.previewModel ||
                (after.activeWorkspace.pose?.authoringPose == true &&
                    (before.parameterValues != after.parameterValues || before.activeWorkspace.pose?.authoringPose != true))) {
                setLivePose(emptyMap())
                pausedPhysics = emptyMap()
                latestLiveParameters = emptyMap()
            }
            _uiState.value = _state.value
        }
    }

    private fun replaceState(next: PSD2LiveState) {
        synchronized(stateLock) {
            if (next.projectOpenGeneration != _state.value.projectOpenGeneration) {
                pendingMeshSplit = null
                pendingBatchMeshSplit = null
                meshSplitQueue.clear()
                manualMeshSplitRequests.clear()
            }
            _state.value = next
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
    private val canvasEditors = mutableMapOf<Pair<String, String>, CanvasEditor>()
    private var editorGeneration = -1L
    internal fun canvasEditorFor(canvasId: String): CanvasEditor {
        val current = uiState.value
        if (editorGeneration != current.projectOpenGeneration) {
            canvasEditors.clear()
            editorGeneration = current.projectOpenGeneration
        }
        return canvasEditors.getOrPut(current.activeWorkspace.id to canvasId) { CanvasEditor(this, current.activeWorkspace.id, canvasId) }
    }
    internal val canvasEditor: CanvasEditor get() = canvasEditorFor(uiState.value.activeCanvas.id)

    /** Paint confirm for whichever canvas still has the dialog open, not only the focused one. */
    internal fun canvasAwaitingMeshRebuild(): CanvasEditor? =
        uiState.value.activeWorkspace.canvases.firstNotNullOfOrNull { canvas ->
            canvasEditorFor(canvas.id).takeIf { it.showRebuildMeshDialog }
        }
    private fun resetCanvasPaintSessions() = canvasEditors.values.forEach { it.resetPaintSession() }


    fun updatePuppetModel(transform: (PuppetModel) -> PuppetModel) {
        val currentPreview = _state.value.previewModel ?: return
        val newPuppet = transform(currentPreview.rig.puppet)
        val updatedRig = currentPreview.rig.copy(puppet = newPuppet)
        val updatedPreview = currentPreview.copy(rig = updatedRig)
        updateState { it.copy(previewModel = updatedPreview, previewModelDirty = true, projectDirty = true) }
        markWorkspaceChanged()
        editorChanged()
    }

    fun applyCommittedPaint(updatedPreview: RigPreviewModel, summary: String) {
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
        commitEditorChange(summary)
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

    internal fun offerImportMeshSplit(layerIds: List<String>) {
        if (!AppSettings.autoDetectMeshSplitsOnImport) return
        scanMeshSplits(layerIds, manual = false)
    }

    /** Tools menu: scan every source layer and offer all splittable meshes in one dialog. */
    internal fun requestBatchMeshSplit() {
        if (meshSplitChecking || meshSplitApplying || pendingMeshSplit != null || pendingBatchMeshSplit != null) return
        val preview = _state.value.previewModel ?: return
        scanMeshSplits(preview.analysis.source.layers.map { it.id.raw }, manual = true)
    }

    private fun scanMeshSplits(layerIds: List<String>, manual: Boolean) {
        val preview = _state.value.previewModel ?: return
        meshSplitChecking = true
        scope.launch {
            try {
                val candidateOffers = withContext(Dispatchers.Default) {
                    layerIds.mapNotNull { id ->
                        if (meshSplitWouldDiscardEdits(preview, id)) return@mapNotNull null
                        val source = preview.analysis.source.layers.firstOrNull { it.id.raw == id }
                            ?: preview.analysis.layers.firstOrNull { it.source.id.raw == id }?.source
                            ?: return@mapNotNull null
                        if (source.clipped || source.blend != org.umamo.format.art.LayerBlend.Normal ||
                            source.channelMask != org.umamo.format.art.ChannelMask.ALL) return@mapNotNull null
                        val drawable = preview.rig.puppet.drawables.firstOrNull {
                            it.id.raw == id || preview.rig.layerIdByDrawableId[it.id.raw] == id
                        } ?: return@mapNotNull null
                        val placement = preview.atlas.placementByLayerId[id] ?: return@mapNotNull null
                        val page = preview.atlas.pages.getOrNull(placement.page)?.image ?: return@mapNotNull null
                        val plan = drawable.mesh?.let {
                            MeshComponentSplit.detect(it, source, placement, page.width, page.height)
                        } ?: return@mapNotNull null
                        if (plan.components.size <= 1) return@mapNotNull null
                        MeshSplitOffer(id, source.name, plan, preview)
                    }
                }
                if (_state.value.previewModel === preview) {
                    when {
                        candidateOffers.isEmpty() -> if (manual) setErrorMessage(tr("editor.meshSplit.noneInModel"))
                        candidateOffers.size == 1 -> pendingMeshSplit = candidateOffers.first()
                        else -> pendingBatchMeshSplit = BatchMeshSplitOffer(candidateOffers, preview)
                    }
                }
            } catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                setErrorMessage(failure.message ?: failure.javaClass.simpleName)
            } finally {
                meshSplitChecking = false
            }
        }
    }

    internal fun requestMeshSplit(layerId: String) {
        if (pendingMeshSplit?.layerId == layerId) return
        manualMeshSplitRequests += layerId
        offerMeshSplit(listOf(layerId))
    }

    internal fun dismissMeshSplit() {
        pendingMeshSplit = null
        checkNextMeshSplit()
    }

    internal fun dismissBatchMeshSplit() {
        pendingBatchMeshSplit = null
    }

    internal fun dismissAllMeshSplits() {
        pendingMeshSplit = null
        pendingBatchMeshSplit = null
        meshSplitQueue.clear()
        manualMeshSplitRequests.clear()
    }

    private fun checkNextMeshSplit() {
        if (meshSplitChecking || meshSplitApplying || pendingMeshSplit != null || pendingBatchMeshSplit != null || meshSplitQueue.isEmpty()) return
        val id = meshSplitQueue.removeFirst()
        val preview = _state.value.previewModel ?: return
        if (meshSplitWouldDiscardEdits(preview, id)) {
            if (id in manualMeshSplitRequests) setErrorMessage(tr("editor.meshSplit.authored"))
            manualMeshSplitRequests -= id
            checkNextMeshSplit()
            return
        }
        meshSplitChecking = true
        scope.launch {
            try {
                val offer = withContext(Dispatchers.Default) {
                    val source = preview.analysis.source.layers.firstOrNull { it.id.raw == id }
                        ?: preview.analysis.layers.firstOrNull { it.source.id.raw == id }?.source
                        ?: return@withContext null
                    if (source.clipped || source.blend != org.umamo.format.art.LayerBlend.Normal ||
                        source.channelMask != org.umamo.format.art.ChannelMask.ALL) return@withContext null
                    val drawable = preview.rig.puppet.drawables.firstOrNull {
                        it.id.raw == id || preview.rig.layerIdByDrawableId[it.id.raw] == id
                    } ?: return@withContext null
                    val placement = preview.atlas.placementByLayerId[id] ?: return@withContext null
                    val page = preview.atlas.pages.getOrNull(placement.page)?.image ?: return@withContext null
                    val plan = drawable.mesh?.let {
                        MeshComponentSplit.detect(it, source, placement, page.width, page.height)
                    } ?: return@withContext null
                    MeshSplitOffer(id, source.name, plan, preview)
                }
                if (_state.value.previewModel === preview && offer != null) pendingMeshSplit = offer
                else if (id in manualMeshSplitRequests && _state.value.previewModel === preview)
                    setErrorMessage(tr("editor.meshSplit.none"))
                manualMeshSplitRequests -= id
            } catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                setErrorMessage(failure.message ?: failure.javaClass.simpleName)
            } finally {
                meshSplitChecking = false
                if (pendingMeshSplit == null && pendingBatchMeshSplit == null) checkNextMeshSplit()
            }
        }
    }

    private fun meshSplitWouldDiscardEdits(preview: RigPreviewModel, layerId: String): Boolean {
        val ids = preview.rig.layerIdByDrawableId.filterValues { it == layerId }.keys + layerId
        val edits = preview.config.rigEdits
        fun referencesId(json: kotlinx.serialization.json.JsonObject): Boolean =
            ids.any { id -> json.toString().contains("\"$id\"") }
        return edits.keyformSetEdits.any { it.target.id in ids } ||
            edits.keyformDeleteEdits.any { it.target.id in ids } ||
            edits.keyformCopyEdits.any { it.sourceTarget.id in ids || it.destinationTarget.id in ids } ||
            edits.warpEdits.any { warp -> warp.meshIds.any { it in ids } } ||
            edits.authoringJournal.any(::referencesId) ||
            edits.structureEdits.any(::referencesId) ||
            preview.rig.puppet.glues.any { it.meshA.raw in ids || it.meshB.raw in ids }
    }

    internal fun confirmMeshSplit(names: List<String>, sides: List<Side>) {
        val offer = pendingMeshSplit ?: return
        confirmBatchMeshSplit(listOf(LayerSplitDecision(offer, names, sides)))
    }

    internal fun confirmBatchMeshSplit(decisions: List<LayerSplitDecision>) {
        val batchOffer = pendingBatchMeshSplit
        val singleOffer = pendingMeshSplit
        val basePreview = batchOffer?.preview ?: singleOffer?.preview ?: decisions.firstOrNull()?.offer?.preview ?: return
        if (decisions.isEmpty()) {
            pendingBatchMeshSplit = null
            pendingMeshSplit = null
            return
        }
        val validDecisions = decisions.filter { d ->
            d.names.size == d.offer.plan.components.size &&
            d.sides.size == d.names.size &&
            d.names.all { it.isNotBlank() } &&
            d.names.map(String::trim).distinct().size == d.names.size
        }
        if (validDecisions.isEmpty()) return
        pendingBatchMeshSplit = null
        pendingMeshSplit = null
        meshSplitApplying = true
        scope.launch {
            try {
                val current = _state.value
                if (current.previewModel !== basePreview) return@launch

                val generatedPiecesByLayer = withContext(Dispatchers.Default) {
                    validDecisions.associate { d ->
                        d.offer.layerId to d.offer.plan.pieces(d.names)
                    }
                }

                val allNewPieces = generatedPiecesByLayer.values.flatten()
                val allNewIds = allNewPieces.map { it.id.raw }
                val splitLayerIds = validDecisions.map { it.offer.layerId }.toSet()

                var updatedOverrides = current.layerOverrides
                var updatedVisibility = current.layerVisibility
                var updatedParents = current.parentOverrides
                var updatedMeshOverrides = current.meshOverrides
                var updatedDrawOrders = current.drawOrderOverrides

                for (decision in validDecisions) {
                    val offer = decision.offer
                    val pieces = generatedPiecesByLayer[offer.layerId] ?: continue
                    val ids = pieces.map { it.id.raw }
                    val original = basePreview.analysis.source.layers.firstOrNull { it.id.raw == offer.layerId }
                        ?: basePreview.analysis.layers.first { it.source.id.raw == offer.layerId }.source

                    val classified = basePreview.analysis.layers.firstOrNull { it.source.id.raw == offer.layerId }
                    val inherited = current.layerOverrides[offer.layerId] ?: classified?.semantic?.let {
                        LayerClassificationOverride(it.type, it.tag, it.side, it.parameter, it.switchId)
                    } ?: LayerClassificationOverride()

                    updatedOverrides = updatedOverrides + ids.mapIndexed { index, id ->
                        val side = decision.sides[index]
                        id to inherited.copy(side = if (side == Side.NONE) inherited.side else side)
                    }

                    updatedVisibility = updatedVisibility + ids.associateWith {
                        current.layerVisibility[offer.layerId] ?: original.visible
                    }

                    val oldDrawable = basePreview.rig.puppet.drawables.firstOrNull {
                        it.id.raw == offer.layerId || basePreview.rig.layerIdByDrawableId[it.id.raw] == offer.layerId
                    }
                    val oldParentId = if (offer.layerId in current.parentOverrides) current.parentOverrides[offer.layerId]
                        else oldDrawable?.parentDeformerId?.raw
                    updatedParents = updatedParents + ids.associateWith { oldParentId }

                    current.meshOverrides[offer.layerId]?.let { meshOv ->
                        updatedMeshOverrides = updatedMeshOverrides + ids.associateWith { meshOv }
                    }
                    current.drawOrderOverrides[offer.layerId]?.let { drawOv ->
                        updatedDrawOrders = updatedDrawOrders + ids.associateWith { drawOv }
                    }
                }

                val deleted = current.deletedLayerIds + splitLayerIds

                val virtualOwnerMap = splitLayerIds.associateWith { splitId ->
                    val originalIsInSource = basePreview.analysis.source.layers.any { it.id.raw == splitId }
                    if (originalIsInSource) null else basePreview.analysis.source.layers
                        .map { it.id.raw }.filter { splitId.startsWith("$it:") }.maxByOrNull(String::length)
                }

                val injectedLayerIds = mutableSetOf<String>()
                val expandedLayers = basePreview.analysis.source.layers.flatMap { layer ->
                    val matchingSplits = splitLayerIds.filter { splitId ->
                        layer.id.raw == splitId || layer.id.raw == virtualOwnerMap[splitId]
                    }
                    if (matchingSplits.isNotEmpty()) {
                        val piecesToInject = matchingSplits.flatMap { splitId ->
                            if (injectedLayerIds.add(splitId)) {
                                generatedPiecesByLayer[splitId].orEmpty()
                            } else emptyList()
                        }
                        listOf(layer) + piecesToInject
                    } else {
                        listOf(layer)
                    }
                }

                val remainingPieces = splitLayerIds.filter { it !in injectedLayerIds }.flatMap {
                    generatedPiecesByLayer[it].orEmpty()
                }
                val allCombinedLayers = expandedLayers + remainingPieces

                val reorderedLayers = allCombinedLayers.mapIndexed { index, layer ->
                    io.github.psd2live.agent.WorkspaceSourceLayer.copyOf(
                        layer,
                        allCombinedLayers.size - index
                    )
                }

                val newSource = io.github.psd2live.agent.WorkspaceSourceArt(
                    basePreview.analysis.source.widthPx,
                    basePreview.analysis.source.heightPx,
                    reorderedLayers,
                    basePreview.analysis.source.groups
                )

                val baselineIds = current.rigEdits.splitBaselineLayerIds.ifEmpty {
                    (basePreview.analysis.source.layers.map { it.id.raw }
                        .filterNot { it in current.deletedLayerIds } +
                        basePreview.analysis.layers.map { it.source.id.raw }).toSet()
                }

                val config = current.buildConfig().copy(
                    deletedLayerIds = deleted,
                    layerOverrides = updatedOverrides,
                    layerVisibility = updatedVisibility,
                    parentOverrides = updatedParents,
                    meshOverrides = updatedMeshOverrides,
                    drawOrderOverrides = updatedDrawOrders,
                    rigEdits = current.rigEdits.copy(splitBaselineLayerIds = baselineIds),
                )

                val built = withContext(Dispatchers.Default) {
                    pipeline.buildPreviewAfterLayerSplit(basePreview, newSource, config)
                }
                if (_state.value.previewModel !== basePreview) return@launch

                updateState { it.copy(
                    deletedLayerIds = deleted,
                    layerOverrides = updatedOverrides,
                    layerVisibility = updatedVisibility,
                    parentOverrides = updatedParents,
                    meshOverrides = updatedMeshOverrides,
                    drawOrderOverrides = updatedDrawOrders,
                    selectedLayerId = allNewIds.firstOrNull(),
                ) }

                val summary = if (validDecisions.size == 1) tr("canvas.hierarchy.meshSplitDone", allNewIds.size)
                    else tr("editor.meshSplit.batchDone", validDecisions.size, allNewIds.size)
                applyCommittedPaint(built, summary)
                selectLayer(allNewIds.firstOrNull())
            } catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                setErrorMessage(failure.message ?: failure.javaClass.simpleName)
            } finally {
                meshSplitApplying = false
                if (pendingMeshSplit == null && pendingBatchMeshSplit == null) checkNextMeshSplit()
            }
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
    fun saveAuthoringEdits(expectedState: String, edits: kotlinx.serialization.json.JsonArray, onComplete: (String?) -> Unit) {
        if (_state.value.canvasEditBusy) { onComplete("An editor operation is still being applied"); return }
        updateState { it.copy(canvasEditBusy = true) }
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
                updateState { it.copy(canvasEditBusy = false) }
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
        val expected = expectedState ?: _state.value.historySnapshot?.headNodeId
        if (expected == null) { onComplete("Project workspace unavailable"); return }
        val fields = kotlinx.serialization.json.buildJsonObject {
            if (action == "create") {
				put("parameter_kind", kotlinx.serialization.json.JsonPrimitive(parameterKind.name))
				if (parentGroupId != null) {
					put("parent_id", kotlinx.serialization.json.JsonPrimitive(parentGroupId))
				}
			}
            if (action != "delete") {
                put("name", kotlinx.serialization.json.JsonPrimitive(name))
                put("min", kotlinx.serialization.json.JsonPrimitive(min))
                put("default", kotlinx.serialization.json.JsonPrimitive(default))
                put("max", kotlinx.serialization.json.JsonPrimitive(max))
            }
        }
        val command = kotlinx.serialization.json.buildJsonObject {
            put("op", kotlinx.serialization.json.JsonPrimitive("structure"))
            put("edits", kotlinx.serialization.json.JsonArray(listOf(structureEdit(action, "parameter", id, fields))))
        }
        saveAuthoringEdits(expected, kotlinx.serialization.json.JsonArray(listOf(command) + if (action == "delete") emptyList() else keyEdits), onComplete)
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
            updateState { it.copy(showProjectLocationDialog = true, projectSaveError = null) }
        } else saveProjectTo(Path.of(_state.value.projectFile!!))
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
            } catch (failure: Exception) { updateState { it.copy(errorMessage = failure.message) } }
        }
    }
    internal fun installProjectState(state: PSD2LiveState) {
        previewRebuildJob?.cancel()
        activeWorkJob?.cancel()
        resetCanvasPaintSessions()
        state.projectFile?.let(AppSettings::rememberRecentFile)
        replaceState(state.copy(
            projectDirty = false,
            projectOpenGeneration = _state.value.projectOpenGeneration + 1,
            recentFiles = AppSettings.recentFiles(),
        ))
    }
    private val pendingProjectSaves = java.util.concurrent.atomic.AtomicInteger()
    internal fun projectSaveStarted() { pendingProjectSaves.incrementAndGet(); updateState { it.copy(projectSaving = true, projectSaveError = null) } }
    internal fun projectSaveFailed(failure: Exception) { val saving = pendingProjectSaves.decrementAndGet() > 0; updateState { it.copy(projectSaving = saving, projectDirty = true, projectSaveError = failure.message ?: "Save failed") } }
    internal fun projectSaveFinished(path: Path, headId: String, captured: PSD2LiveState) {
        val saving = pendingProjectSaves.decrementAndGet() > 0
        val saved = path.toAbsolutePath().normalize().toString()
        AppSettings.rememberRecentFile(saved)
        updateState { current -> current.copy(projectFile = saved, projectSaving = saving,
            projectDirty = current.historySnapshot?.headNodeId != headId || current.projectAuxiliaryVersion != captured.projectAuxiliaryVersion || io.github.psd2live.project.WorkspaceStateCodec.editableIdentity(current) != io.github.psd2live.project.WorkspaceStateCodec.editableIdentity(captured),
            projectSaveError = null, recentFiles = AppSettings.recentFiles()) }
    }
    internal fun markProjectAuxiliaryChanged() { updateState { it.copy(projectDirty = true, projectEditVersion = it.projectEditVersion + 1, projectAuxiliaryVersion = it.projectAuxiliaryVersion + 1) } }
    private fun markWorkspaceChanged() { updateState { if (it.analysis == null) it else it.copy(projectDirty = true, projectEditVersion = it.projectEditVersion + 1) } }
    /**
     * Open field sessions, so a slider drag or a half-typed value commits once rather than per sample or
     * per keystroke. See [EditorFieldSessions].
     */
    private val editorSessions = EditorFieldSessions { commitEditorChange() }

    fun beginEditorGesture() = editorSessions.begin(SLIDER_SESSION)
    fun endEditorGesture() = editorSessions.end(SLIDER_SESSION)

	/** Preview scrubs carry only changed values until release, like pointer tracking. */
	private data class ParameterScrub(
		val generation: Long,
		val workspaceId: String,
		val overrides: Map<ParameterId, Float> = emptyMap(),
	)

	@Volatile private var parameterScrub: ParameterScrub? = null
	private val parameterScrubValues = mutableStateMapOf<ParameterId, Float>()
	var parameterScrubActive by mutableStateOf(false)
		private set

	fun parameterScrubValueOf(id: ParameterId): Float? = parameterScrubValues[id]

	fun beginParameterScrub() {
		editorSessions.begin(SLIDER_SESSION)
		val current = _state.value
		if (!current.previewLive || current.previewModel == null || current.sdkStatus != "ready") return
		if (parameterScrub != null) return
		motionEditor.playing = false
		motionPlayer.stop()
		// One state change pauses motion. Slider samples after this never rebuild the edit canvas.
		val suppressPreviewEffects = current.activeCanvas.mode == CanvasMode.EDIT
		if (current.animationEnabled || current.previewParameterValues.isNotEmpty() ||
			current.activeWorkspace.pose?.authoringPose != suppressPreviewEffects) {
			updateState { it.copy(animationEnabled = false, previewParameterValues = emptyMap())
				.authoringPose(suppressPreviewEffects) }
		}
		val started = _state.value
		parameterScrub = ParameterScrub(started.projectOpenGeneration, started.activeWorkspace.id)
		parameterScrubActive = true
	}

	fun endParameterScrub() {
		commitParameterScrub()
		editorSessions.end(SLIDER_SESSION)
	}

	private fun commitParameterScrub() {
		val scrub = parameterScrub ?: return
		parameterScrub = null
		val current = _state.value
		if (current.projectOpenGeneration == scrub.generation && current.activeWorkspace.id == scrub.workspaceId && scrub.overrides.isNotEmpty()) {
			setParameterValues(scrub.overrides)
		}
		parameterScrubValues.clear()
		parameterScrubActive = false
	}

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
    fun flushEditorFields() {
		commitParameterScrub()
		editorSessions.flush()
	}

    private fun editorChanged(summary: String? = null) {
        if (editorSessions.anyOpen) { markWorkspaceChanged(); return }
        commitEditorChange(summary)
    }

    private fun commitEditorChange(summary: String? = null) {
        if (_state.value.analysis == null) return
        (agentWorkspace as? io.github.psd2live.agent.ViewModelAgentWorkspace)?.editorChanged(summary)
    }
    fun editHistoryAnnotation(id: String, title: String, note: String, hidden: Boolean) {
        require(_state.value.historySnapshot?.nodes?.any { it.id == id } == true)
        updateState { it.copy(historyAnnotations = it.historyAnnotations + (id to HistoryAnnotation(title.trim(), note, hidden)), projectDirty = true, projectEditVersion = it.projectEditVersion + 1) }
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
    fun setModelSettingsExpanded(expanded: Boolean) { updateState { it.copy(modelSettingsExpanded = expanded, projectDirty = it.analysis != null, projectEditVersion = it.projectEditVersion + 1) } }

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
    fun setCanvasView(
        zoom: Float, x: Float, y: Float,
        canvasId: String = _state.value.activeCanvas.id,
        mode: CanvasMode? = null,
    ) {
        updateState { current ->
            if (current.activeWorkspace.canvases.none { it.id == canvasId }) current
            else current.updateActiveWorkspace { workspace ->
                workspace.copy(
                    canvases = workspace.canvases.map { canvas ->
                        if (canvas.id == canvasId) canvas.updateSession(mode ?: canvas.mode) {
                            it.copy(camera = TabCamera(zoom, x, y))
                        } else canvas
                    },
                )
            }.copy(projectDirty = current.analysis != null, projectEditVersion = current.projectEditVersion + 1)
        }
    }

	fun attachAgentWorkspace(workspace: AgentWorkspace) {
		agentWorkspace = workspace
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
	private val previewMeshSettingsOverrides = mutableMapOf<String, MeshSettings>()
	private var previewMeshSettingsBaseline: RigPreviewModel? = null
	private var motionJob: Job? = null
	private val motionPlayer = PreviewMotionPlayer()
	/** Shared by the animation panel and the animation editor. Initialized before [startMotionLoop]. */
	internal val motionEditor = MotionEditorState()
	private var activeWorkJob: Job? = null

	private val canvasPointers = mutableMapOf<String, Pair<Float, Float>>()
	private var pointerOwner: String? = null
	private var pointerActive = false
	private var pointerX = 0f
	private var pointerY = 0f
	private var followX = 0f
	private var followY = 0f
	/** The exported physics, run on the software preview so it moves as Cubism would before the SDK is up. */
	private val softwarePhysics = SoftwarePhysics()
	private var elapsed = 0.0
	private var lastTick = System.nanoTime()
	private var lastSdkParameterPublishNanos = 0L
	private var lastSdkParameterCanvasId: String? = null
	private var sdkSessionNeedsReload = false

	private fun refreshSdkSession(preview: RigPreviewModel) {
		if (_state.value.previewLive) {
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

	fun setPartMeshSettings(layerId: String, settings: MeshSettings) {
		clearMeshSettingsPreviewState(layerId)
		updateState { it.copy(meshOverrides = it.meshOverrides + (layerId to settings)) }
		schedulePreviewRebuild()
	    editorChanged()
	}

	fun resetPartMeshSettings(layerId: String) {
		clearMeshSettingsPreviewState(layerId)
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

	fun setMeshOnly(enabled: Boolean) {
		updateState { current ->
			val updated = current.copy(meshOnly = enabled, generateDeformers = !enabled)
			if (enabled) {
				val defaults = current.previewModel?.rig?.puppet?.parameters?.associate { it.id to it.default } ?: emptyMap()
				val resetMap = defaults.filterKeys { it !in current.lockedParameters }
				updated.copy(parameterValues = current.parameterValues + resetMap)
			} else updated
		}
		if (enabled) {
			softwarePhysics.reset()
			followX = 0f
			followY = 0f
			motionPlayer.stop()
		}
		schedulePreviewRebuild()
	    editorChanged()
	}

	fun setFeatureDisplacementEnabled(enabled: Boolean) {
		updateState { it.copy(featureDisplacementEnabled = enabled) }
		schedulePreviewRebuild()
		editorChanged()
	}

	fun setMotionIdle(enabled: Boolean) {
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
			followX = 0f
			followY = 0f
		}
		scheduleRuntimeBundleUpdate()
	    editorChanged()
	}

	fun setMotionBlink(enabled: Boolean) {
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
		updateState { current ->
			val next = current.copy(motionNod = enabled)
			val updated = next.copy(exportMotions = next.motionIdle || next.motionBlink || next.motionNod || next.motionShake || next.motionSkeleton)
			if (!enabled && motionPlayer.activeName == "nod") {
				val nodReset = mapOf(
					StandardParameters.ANGLE_Y to 0f,
					StandardParameters.BODY_Y to 0f,
				).filterKeys { key -> key !in updated.lockedParameters }
				updated.copy(parameterValues = updated.parameterValues + nodReset)
			} else updated
		}
		if (!enabled) motionPlayer.stop("nod")
		scheduleRuntimeBundleUpdate()
		if (enabled) triggerMotion("Nod")
	    editorChanged()
	}

	fun setMotionShake(enabled: Boolean) {
		updateState { current ->
			val next = current.copy(motionShake = enabled)
			val updated = next.copy(exportMotions = next.motionIdle || next.motionBlink || next.motionNod || next.motionShake || next.motionSkeleton)
			if (!enabled && motionPlayer.activeName == "shake") {
				val shakeReset = mapOf(
					StandardParameters.ANGLE_X to 0f,
					StandardParameters.BODY_X to 0f,
					StandardParameters.ANGLE_Z to 0f,
				).filterKeys { key -> key !in updated.lockedParameters }
				updated.copy(parameterValues = updated.parameterValues + shakeReset)
			} else updated
		}
		if (!enabled) motionPlayer.stop("shake")
		scheduleRuntimeBundleUpdate()
		if (enabled) triggerMotion("Shake")
	    editorChanged()
	}

	fun setMotionSkeleton(enabled: Boolean) {
		updateState { current ->
			val next = current.copy(motionSkeleton = enabled)
			next.copy(exportMotions = next.motionIdle || next.motionBlink || next.motionNod || next.motionShake || next.motionSkeleton)
		}
		if (!enabled && PreviewMotionPlayer.isSkeletonMotion(motionPlayer.activeName)) motionPlayer.stop()
		scheduleRuntimeBundleUpdate()
		editorChanged()
	}

	fun setGeneratePhysics(enabled: Boolean) {
		updateState { current ->
			val updated = current.copy(generatePhysics = enabled)
			if (!enabled) updated.copy(parameterValues = updated.parameterValues + physicsRestValues(current, current.rigEdits)) else updated
		}
		if (!enabled) softwarePhysics.reset()
		scheduleRuntimeBundleUpdate()
	    editorChanged()
	}

	fun setPhysicsFrontHair(enabled: Boolean) = setPresetPhysics(PhysicsGenerator.FRONT_HAIR_ID, enabled)
	fun setPhysicsBackHair(enabled: Boolean) = setPresetPhysics(PhysicsGenerator.BACK_HAIR_ID, enabled)
	fun setPhysicsEyeJelly(enabled: Boolean) = setPresetPhysics(PhysicsGenerator.EYE_JELLY_ID, enabled)

	private fun setPresetPhysics(id: String, enabled: Boolean) {
		updateState { current ->
			val next = when (id) {
				PhysicsGenerator.FRONT_HAIR_ID -> current.copy(physicsFrontHair = enabled)
				PhysicsGenerator.BACK_HAIR_ID -> current.copy(physicsBackHair = enabled)
				else -> current.copy(physicsEyeJelly = enabled)
			}
			if (enabled) next else next.copy(parameterValues = next.parameterValues + physicsRestValues(current, current.rigEdits, setOf(id)))
		}
		scheduleRuntimeBundleUpdate()
		editorChanged()
	}

	// region Physics groups

	private var physicsCatalogCache: Pair<List<Any?>, List<PhysicsGroup>>? = null

	/** Every physics group of the current model, as export sees it; see [PhysicsCatalog.groups]. */
	fun physicsGroups(state: PSD2LiveState = _state.value): List<PhysicsGroup> {
		val model = state.previewModel ?: return emptyList()
		val key = listOf(model.analysis, model.rig.puppet.parameters, state.rigEdits.physicsEdits, state.rigEdits.disabledPhysicsIds, state.rigEdits.physicsOrder,
			state.rigEdits.skeleton, state.rigEdits.swingEdits, state.physicsFrontHair, state.physicsBackHair, state.physicsEyeJelly)
		physicsCatalogCache?.let { (k, groups) -> if (k == key) return groups }
		val groups = PhysicsCatalog.groups(PhysicsGenerator.Presets.present(model.analysis),
			PhysicsGenerator.Presets(state.physicsFrontHair, state.physicsBackHair, state.physicsEyeJelly),
			state.rigEdits, model.rig.puppet.parameters.mapTo(HashSet()) { it.id.raw })
		physicsCatalogCache = key to groups
		return groups
	}

	/** Makes [edit] the version of its group; a slider drag inside a gesture commits once. */
	fun putPhysicsGroup(edit: RigPhysicsEdit) {
		val generated = physicsGroups().firstOrNull { it.id == edit.id }?.generated
		val overlay = runCatching { PhysicsAuthoring.put(_state.value.rigEdits, edit, generated) }
			.getOrElse { failure -> addLog(failure.message ?: "Physics edit failed", level = LogLevel.WARNING, tag = "Physics"); return }
		updateState { it.copy(rigEdits = overlay) }
		scheduleRuntimeBundleUpdate()
		editorChanged()
	}

	fun setPhysicsGroupEnabled(id: String, enabled: Boolean) {
		if (id in PhysicsGenerator.presetIds) return setPresetPhysics(id, enabled)
		updateState { current ->
			val next = current.copy(rigEdits = PhysicsAuthoring.setEnabled(current.rigEdits, id, enabled))
			if (enabled) next else next.copy(parameterValues = next.parameterValues + physicsRestValues(current, current.rigEdits, setOf(id)))
		}
		scheduleRuntimeBundleUpdate()
		editorChanged()
	}

	/** Deletes a user group, or returns a replaced generated one to its generated values. */
	fun removePhysicsGroup(id: String) {
		val group = physicsGroups().firstOrNull { it.id == id } ?: return
		if (_state.value.rigEdits.physicsEdits.none { it.id == id }) return
		updateState { current ->
			val next = current.copy(rigEdits = PhysicsAuthoring.remove(current.rigEdits, id, group.generated != null))
			if (group.generated != null) next else next.copy(parameterValues = next.parameterValues + physicsRestValues(current, current.rigEdits, setOf(id)))
		}
		scheduleRuntimeBundleUpdate()
		editorChanged()
	}

	/** Adds a new pendulum, a copy of [from] without its outputs when given, and returns its ID. */
	fun createPhysicsGroup(from: RigPhysicsEdit? = null): String? {
		val state = _state.value
		val puppet = state.previewModel?.rig?.puppet ?: return null
		val groups = physicsGroups(state)
		val id = PhysicsAuthoring.freshId(groups, state.rigEdits)
		val names = groups.mapTo(HashSet()) { it.setting.name }
		fun unique(base: String) = generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base $it" }.first { it !in names }
		val edit = from?.copy(id = id, name = unique(tr("physics.copyName", from.name)), outputs = emptyList())
			?: PhysicsAuthoring.template(id, unique(tr("physics.newName")), puppet.parameters.mapTo(HashSet()) { it.id.raw })
		updateState { it.copy(rigEdits = it.rigEdits.copy(physicsEdits = it.rigEdits.physicsEdits + edit)) }
		scheduleRuntimeBundleUpdate()
		editorChanged()
		return id
	}

	/** Moves [id] [by] places in the evaluation order. */
	fun movePhysicsGroup(id: String, by: Int) = changePhysicsOverlay { PhysicsAuthoring.move(it, physicsGroups(), id, by) }

	/** The project's one frame rate, [RigEditOverlay.UNLIMITED_FPS] for the display's: preview, parameters and physics. */
	fun setProjectFps(fps: Int) = changePhysicsOverlay { PhysicsAuthoring.setFps(it, fps) }

	/** Sets each output's scale so the swing measured in [peaks] just reaches its parameter's end. */
	fun fitPhysicsScales(id: String, peaks: Map<Int, Float>) {
		val setting = physicsGroups().firstOrNull { it.id == id }?.setting ?: return
		putPhysicsGroup(PhysicsAuthoring.fitScales(setting, peaks))
	}

	fun applyPhysicsPreset(id: String, preset: io.github.psd2live.core.PhysicsPresets.Preset) {
		val state = _state.value
		val setting = physicsGroups(state).firstOrNull { it.id == id }?.setting ?: return
		val available = state.previewModel?.rig?.puppet?.parameters?.mapTo(HashSet()) { it.id.raw } ?: return
		putPhysicsGroup(io.github.psd2live.core.PhysicsPresets.apply(preset, setting, available))
	}

	/** Imports a physics3.json's groups as user groups and returns the first one's ID. */
	fun importPhysics(path: String): String? {
		val state = _state.value
		val available = state.previewModel?.rig?.puppet?.parameters?.mapTo(HashSet()) { it.id.raw } ?: return null
		val imported = runCatching {
			PhysicsAuthoring.import(state.rigEdits, physicsGroups(state), java.io.File(path).readText(), available)
		}.getOrElse { failure ->
			addLog(tr("physics.import.failed", failure.message ?: failure.javaClass.simpleName), level = LogLevel.ERROR, tag = "Physics")
			return null
		}
		updateState { it.copy(rigEdits = imported.overlay, generatePhysics = true) }
		scheduleRuntimeBundleUpdate()
		editorChanged()
		addLog(tr("physics.import.done", imported.ids.size, java.io.File(path).name), tag = "Physics")
		if (imported.disabled.isNotEmpty()) addLog(tr("physics.import.disabled", imported.disabled.joinToString()), level = LogLevel.WARNING, tag = "Physics")
		for ((group, missing) in imported.missing) {
			addLog(tr("physics.import.missing", group, missing.joinToString()), level = LogLevel.WARNING, tag = "Physics")
		}
		return imported.ids.firstOrNull()
	}

	private fun changePhysicsOverlay(change: (RigEditOverlay) -> RigEditOverlay) {
		val next = runCatching { change(_state.value.rigEdits) }
			.getOrElse { failure -> addLog(failure.message ?: "Physics edit failed", level = LogLevel.WARNING, tag = "Physics"); return }
		if (next == _state.value.rigEdits) return
		updateState { it.copy(rigEdits = next) }
		scheduleRuntimeBundleUpdate()
		editorChanged()
	}

	/** Rest values for the outputs of [ids] (every group when null) so a switched-off group lets go. */
	private fun physicsRestValues(state: PSD2LiveState, overlay: RigEditOverlay, ids: Set<String>? = null): Map<ParameterId, Float> {
		val parameters = state.previewModel?.rig?.puppet?.parameters?.associateBy { it.id.raw } ?: return emptyMap()
		return physicsGroups(state.copy(rigEdits = overlay)).filter { ids == null || it.id in ids }
			.flatMap { it.setting.outputParameters }.mapNotNull { parameters[it] }
			.filter { it.id !in state.lockedParameters }.associate { it.id to it.default }
	}

	/** The software preview's physics: rebuilt when the exported groups change, reset when motion stops. */
	private inner class SoftwarePhysics {
		private var key: List<Any?>? = null
		private var engine: PhysicsEngine? = null
		private var released: Map<String, Float> = emptyMap()

		fun reset() { engine?.reset() }

		fun step(state: PSD2LiveState, model: RigPreviewModel, inputs: Map<ParameterId, Float>, dt: Float): Map<ParameterId, Float> {
			val groups = if (!state.generatePhysics || state.meshOnly) emptyList() else physicsGroups(state).filter { it.active }.map { it.setting }
			val parameters = model.rig.puppet.parameters
			val fps = state.rigEdits.physicsFps
			val nextKey = listOf(groups, parameters, fps)
			if (nextKey != key) {
				val before = engine?.strands.orEmpty().flatMap { it.setting.outputParameters }.toSet()
				key = nextKey
				engine = PhysicsEngine(groups, PhysicsEngine.ranges(parameters), fps.toFloat()).also { it.carryOver(engine) }
				val now = groups.flatMap { it.outputParameters }.toSet()
				released = parameters.filter { it.id.raw in before - now }.associate { it.id.raw to it.default }
			}
			val values = inputs.mapKeys { it.key.raw }
			val out = released + engine!!.step(values, dt)
			released = emptyMap()
			return out.mapKeys { ParameterId(it.key) }
		}
	}

	// endregion

	// region Authored motions

	val motionClips: List<MotionClip> get() = _state.value.rigEdits.motionClips

	/** The clip the editor has open, if it still exists (an undo can remove it). */
	fun editingMotionClip(state: PSD2LiveState = _state.value): MotionClip? =
		motionEditor.clipId?.let { id -> state.rigEdits.motionClips.firstOrNull { it.id == id } }

	internal fun motionParameterRanges(): Map<String, ClosedFloatingPointRange<Float>> =
		_state.value.previewModel?.rig?.puppet?.parameters?.associate { it.id.raw to it.min..it.max }.orEmpty()

	/**
	 * One change to the authored motions. [commit] records a history node and refreshes the runtime bundle;
	 * a drag passes false for its samples and commits once on release.
	 */
	private fun updateMotionClips(commit: Boolean = true, summary: String? = null, transform: (List<MotionClip>) -> List<MotionClip>) {
		updateState { current ->
			val next = transform(current.rigEdits.motionClips)
			if (next == current.rigEdits.motionClips) current
			else current.copy(rigEdits = current.rigEdits.copy(motionClips = next))
		}
		if (commit) {
			scheduleRuntimeBundleUpdate()
			editorChanged(summary)
		} else markWorkspaceChanged()
	}

	private fun updateMotionClip(id: String, commit: Boolean = true, summary: String? = null, transform: (MotionClip) -> MotionClip) =
		updateMotionClips(commit, summary) { clips -> clips.map { if (it.id == id) transform(it) else it } }

	/** A new clip, blank or a copy of a generated motion's tracks, opened in the editor. */
	fun createMotionClip(fromBuiltin: String? = null): String {
		val clips = motionClips
		val id = MotionClips.newId(clips)
		val clip = if (fromBuiltin != null) {
			val tracks = MotionClips.builtinTracks(fromBuiltin, _state.value.rigEdits.skeleton)
			MotionClips.fromTracks(
				id = id,
				name = MotionClips.uniqueName(clips, fromBuiltin),
				builtin = null,
				loop = MotionClips.isLoopBuiltin(fromBuiltin),
				tracks = tracks,
				duration = MotionClips.builtinDuration(fromBuiltin, tracks),
			)
		} else MotionClip(id = id, name = MotionClips.uniqueName(clips, tr("animation.newMotionName")))
		updateMotionClips { it + clip }
		openMotionInEditor(id)
		return id
	}

	fun duplicateMotionClip(id: String) {
		val clips = motionClips
		val source = clips.firstOrNull { it.id == id } ?: return
		val copy = source.copy(id = MotionClips.newId(clips), name = MotionClips.uniqueName(clips, source.name), builtin = null)
		updateMotionClips { it + copy }
		openMotionInEditor(copy.id)
	}

	fun renameMotionClip(id: String, name: String) {
		val trimmed = name.trim()
		if (trimmed.isEmpty() || trimmed.any(Char::isISOControl)) return
		val clip = motionClips.firstOrNull { it.id == id } ?: return
		if (clip.builtin != null || clip.name == trimmed) return
		val others = motionClips.filter { it.id != id }
		updateMotionClip(id) { it.copy(name = MotionClips.uniqueName(others, trimmed)) }
	}

	fun deleteMotionClip(id: String) {
		if (motionClips.none { it.id == id }) return
		motionPlayer.stop()
		updateMotionClips { clips -> clips.filterNot { it.id == id } }
		if (motionEditor.clipId == id) closeMotionEditorClip()
	}

	/** Loop, duration, FPS, fades or the export switch; a shorter duration drops the keys past it. */
	fun updateMotionClipProperties(id: String, transform: (MotionClip) -> MotionClip) =
		updateMotionClip(id) { clip ->
			val next = transform(clip)
			if (next.duration != clip.duration) next.copy(curves = MotionKeyEdits.withDuration(clip, next.duration).curves) else next
		}

	/** The override of a generated motion, created from its tracks on first edit. */
	fun ensureBuiltinOverride(name: String): String {
		MotionClips.overrideOf(motionClips, name)?.let { return it.id }
		val tracks = MotionClips.builtinTracks(name, _state.value.rigEdits.skeleton)
		val id = MotionClips.newId(motionClips)
		val clip = MotionClips.fromTracks(
			id = id,
			name = name,
			builtin = name,
			loop = MotionClips.isLoopBuiltin(name),
			tracks = tracks,
			duration = MotionClips.builtinDuration(name, tracks),
		)
		updateMotionClips { it + clip }
		return id
	}

	fun editBuiltinMotion(name: String) = openMotionInEditor(ensureBuiltinOverride(name))

	/** Drops the override so the generated motion plays and exports again. */
	fun resetBuiltinMotion(name: String) {
		val clip = MotionClips.overrideOf(motionClips, name) ?: return
		deleteMotionClip(clip.id)
	}

	fun openMotionInEditor(id: String) {
		if (motionEditor.clipId != id) {
			motionEditor.autoKey = false
			motionEditor.playing = false
			motionEditor.selection = emptySet()
			motionEditor.focusedCurve = null
			motionEditor.playhead = 0f
		}
		motionEditor.clipId = id
		requestSelectDockModule("animationEditor")
	}

	/** Selects the editor's curve for [parameterId] of the open clip. */
	fun focusMotionCurve(parameterId: String?) {
		motionEditor.focusedCurve = parameterId
	}

	fun closeMotionEditorClip() {
		motionEditor.autoKey = false
		motionEditor.playing = false
		motionEditor.clipId = null
		motionEditor.selection = emptySet()
		motionEditor.focusedCurve = null
	}

	private fun updateEditingClip(commit: Boolean = true, summary: String? = null, transform: (MotionClip) -> MotionClip) {
		val id = editingMotionClip()?.id ?: return
		updateMotionClip(id, commit, summary, transform)
	}

	fun toggleMotionAutoKey() {
		if (editingMotionClip() != null) motionEditor.autoKey = !motionEditor.autoKey
	}

	fun setMotionAutoKey(enabled: Boolean) {
		motionEditor.autoKey = enabled && editingMotionClip() != null
	}

	internal fun recordAutoKey(
		changes: Map<String, Float>,
		initialValues: Map<String, Float>,
	) {
		val clip = editingMotionClip() ?: return
		val playhead = motionEditor.playhead.coerceIn(0f, clip.duration)
		val keyTime = if (motionEditor.snapToFrames) MotionKeyEdits.snap(playhead, clip.fps).coerceIn(0f, clip.duration) else playhead
		val (nextClip, keyRefs) = MotionKeyEdits.autoKeyMultiple(clip, changes, initialValues, keyTime)
		if (nextClip != clip) {
			updateEditingClip(summary = tr("history.motion.autoKey")) { nextClip }
			motionEditor.selection = keyRefs
			changes.keys.lastOrNull()?.let { motionEditor.focusedCurve = it }
		}
	}

	/** A curve for [parameterId], keyed at the playhead with the pose the preview shows. */
	fun addMotionCurve(parameterId: String) {
		val clip = editingMotionClip() ?: return
		motionEditor.focusedCurve = parameterId
		if (clip.curve(parameterId) != null) return
		val time = motionEditor.playhead.coerceIn(0f, clip.duration)
		updateEditingClip { MotionKeyEdits.setKey(it, parameterId, MotionKey(time, currentMotionParameterValue(parameterId))) }
		motionEditor.selection = setOf(MotionKeyRef(parameterId, time))
	}

	fun removeMotionCurve(parameterId: String) {
		updateEditingClip { clip -> clip.copy(curves = clip.curves.filterNot { it.parameterId == parameterId }) }
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
		updateEditingClip { MotionKeyEdits.setKey(it, parameterId, existing?.copy(value = v) ?: MotionKey(at, v)) }
		motionEditor.selection = setOf(ref)
	}

	/** Keys every curve of the open clip at the playhead with the pose the preview shows now. */
	fun keyCurrentPose() {
		val clip = editingMotionClip() ?: return
		val time = motionEditor.playhead.coerceIn(0f, clip.duration)
		updateEditingClip { current ->
			current.curves.fold(current) { next, curve ->
				val existing = curve.keys.firstOrNull(MotionKeyRef(curve.parameterId, time)::matches)
				val value = currentMotionParameterValue(curve.parameterId)
				MotionKeyEdits.setKey(next, curve.parameterId, existing?.copy(value = value) ?: MotionKey(time, value))
			}
		}
		motionEditor.selection = clip.curves.mapTo(HashSet()) { MotionKeyRef(it.parameterId, time) }
	}

	fun deleteSelectedMotionKeys() {
		val selection = motionEditor.selection.takeIf { it.isNotEmpty() } ?: return
		updateEditingClip { MotionKeyEdits.delete(it, selection) }
		motionEditor.selection = emptySet()
	}

	/** Edits the selected keys in place: interpolation, handles, or a typed time or value. */
	fun updateSelectedMotionKeys(transform: (String, MotionKey) -> MotionKey) {
		val clip = editingMotionClip() ?: return
		val selection = motionEditor.selection.takeIf { it.isNotEmpty() } ?: return
		val ranges = motionParameterRanges()
		val moved = mutableSetOf<MotionKeyRef>()
		val next = MotionKeyEdits.mapKeys(clip, selection) { id, key ->
			val edited = transform(id, key)
			val range = ranges[id]
			edited.copy(
				time = edited.time.coerceIn(0f, clip.duration),
				value = if (range != null) edited.value.coerceIn(range) else edited.value,
			).also { moved += MotionKeyRef(id, it.time) }
		}
		updateEditingClip { next }
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
		updateEditingClip { next }
		motionEditor.selection = pasted
	}

	/** Starts a key or handle drag: samples apply to this clip, and the drag records one history node. */
	fun beginMotionKeyDrag() {
		motionEditor.dragOrigin = editingMotionClip() ?: return
		motionEditor.dragSelection = motionEditor.selection
		beginEditorField(MOTION_DRAG_SESSION)
	}

	fun dragMotionKeys(dt: Float, dv: Float = 0f, normalized: Boolean = false) {
		val origin = motionEditor.dragOrigin ?: return
		val (next, moved) = MotionKeyEdits.move(origin, motionEditor.dragSelection, dt, dv, motionParameterRanges(), normalized)
		updateMotionClip(origin.id, commit = false) { next }
		motionEditor.selection = moved
	}

	/** Sets one Bezier handle of [ref], relative to the drag's origin clip. */
	internal fun dragMotionHandle(ref: MotionKeyRef, outgoing: Boolean, handle: MotionHandle) {
		val origin = motionEditor.dragOrigin ?: return
		val next = MotionKeyEdits.mapKeys(origin, setOf(ref)) { _, key ->
			if (outgoing) key.copy(outHandle = handle.clamped()) else key.copy(inHandle = handle.clamped())
		}
		updateMotionClip(origin.id, commit = false) { next }
	}

	fun endMotionKeyDrag() {
		if (motionEditor.dragOrigin == null) return
		motionEditor.dragOrigin = null
		motionEditor.dragSelection = emptySet()
		scheduleRuntimeBundleUpdate()
		endEditorField(MOTION_DRAG_SESSION)
	}

	/** Moves the playhead and poses the preview there; the preview pauses so the editor owns the pose. */
	fun setMotionPlayhead(time: Float) {
		val clip = editingMotionClip()
		val t = if (clip != null) time.coerceIn(0f, clip.duration) else time.coerceAtLeast(0f)
		motionEditor.playhead = t
		if (clip != null) poseMotionPreview(clip, t)
	}

	/** Samples the clip into the shared authoring pose without changing canvas layout. */
	private fun poseMotionPreview(clip: MotionClip, time: Float) {
		if (clip.curves.isEmpty()) return
		val current = _state.value
		val parameters = current.previewModel?.rig?.puppet?.parameters?.associateBy { it.id }.orEmpty()
		val values = MotionClips.sampleAll(clip, time.toDouble(), loop = false)
			.filterKeys { it in parameters }
			.mapValues { (id, value) -> parameters.getValue(id).let { value.coerceIn(it.min, it.max) } }
		motionPlayer.stop()
		updateCanvasPresentation(current.activeWorkspace.id, current.previewControlCanvas().id, CanvasMode.PREVIEW) {
			it.copy(
				animationEnabled = false,
				parameterValues = it.parameterValues + values,
			).authoringPose()
		}
	}

	fun setMotionEditorPlaying(playing: Boolean) {
		val clip = editingMotionClip()
		if (playing && clip == null) return
		if (playing && clip != null && motionEditor.playhead >= clip.duration - 1e-4f) motionEditor.playhead = 0f
		motionEditor.playing = playing
		if (playing && clip != null) poseMotionPreview(clip, motionEditor.playhead)
	}

	fun stopMotionEditorPlayback() {
		motionEditor.playing = false
		setMotionPlayhead(0f)
	}

	/** One tick of the editor's own playback; a loop wraps and a one-shot stops at its end. */
	private fun advanceMotionEditor(dt: Float) {
		if (!motionEditor.playing) return
		val clip = editingMotionClip()
		if (clip == null) {
			motionEditor.playing = false
			return
		}
		var t = motionEditor.playhead + dt
		if (t >= clip.duration) {
			if (clip.loop) t %= clip.duration
			else {
				t = clip.duration
				motionEditor.playing = false
			}
		}
		motionEditor.playhead = t
		poseMotionPreview(clip, t)
	}

	// endregion

	/** Commit an edited armature as one undoable project change and rebuild its derived rig. */
	fun setSkeleton(spec: io.github.psd2live.core.SkeletonSpec) {
		updateState { current -> current.copy(rigEdits = current.rigEdits.copy(skeleton = spec)) }
		schedulePreviewRebuild()
		editorChanged()
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

	fun setTextureSubExpanded(expanded: Boolean) {
		updateState { it.copy(textureSubExpanded = expanded) }
	    markWorkspaceChanged()
	}

	fun setStrengthSubExpanded(expanded: Boolean) {
		updateState { it.copy(strengthSubExpanded = expanded) }
	    markWorkspaceChanged()
	}

	fun resetModelPresetsToDefault() {
		updateState {
			it.copy(
				strengthSubExpanded = false,
				dynamicsSubExpanded = false,
				headStrength = 1.0f,
				bodyStrength = 1.0f,
				meshOnly = false,
				generateDeformers = true,
				featureDisplacementEnabled = false,
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
		AppSettings.autoDetectMeshSplitsOnImport = true
		updateState { it.copy(autoDetectMeshSplitsOnImport = true) }
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
		val capture = _state.value.keyCapture ?: return
		if (event.key == Key.Escape) {
			cancelKeyCapture()
			return
		}
		if (isModifierKey(event.key)) return
		val binding = keyBindingOf(event)
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
        if (_state.value.canvasEditBusy) return
		var changed = false
		updateState { current ->
			val target = current.workspaces.firstOrNull { it.id == id } ?: return@updateState current
			if (current.activeWorkspaceId == id) current
			else {
				changed = true
				if (target.canvases.none { it.mode == CanvasMode.PREVIEW && it.id !in target.hiddenModules }) {
					pointerActive = false
					motionPlayer.stop()
				}
				current.copy(activeWorkspaceId = id)
			}
		}
		if (changed) {
			markWorkspaceChanged()
			if (_state.value.previewLive) ensureSdkSessionLoaded()
		}
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
		canvasEditors.keys.filter { it.first in closingIds }.forEach { key ->
			canvasEditors[key]?.commitSkeletonDraft()
			canvasEditors.remove(key)?.resetPaintSession()
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
			motionPlayer.stop()
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

	fun setCanvasMode(canvasId: String, mode: CanvasMode) {
		var changed = false
		updateState { current ->
			val canvas = current.activeWorkspace.canvases.firstOrNull { it.id == canvasId } ?: return@updateState current
			if (canvas.mode == mode) current.updateActiveWorkspace { it.copy(activeCanvasId = canvasId) }
			else {
				changed = true
				current.updateActiveWorkspace { workspace ->
					workspace.copy(
						activeCanvasId = canvasId,
						canvases = workspace.canvases.map { pane ->
							if (pane.id == canvasId) pane.copy(mode = mode) else pane
						},
					)
				}
			}
		}
		if (changed) markWorkspaceChanged()
		if (mode == CanvasMode.PREVIEW) ensureSdkSessionLoaded()
		if (!_state.value.previewLive) {
			pointerActive = false
			motionPlayer.stop()
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
		canvasEditors[workspace.id to canvasId]?.commitSkeletonDraft()
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
		canvasEditors.remove(workspace.id to canvasId)?.resetPaintSession()
		markWorkspaceChanged()
		if (!_state.value.previewLive) {
			pointerActive = false
			motionPlayer.stop()
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
			motionPlayer.stop()
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
			current.copy(
				logLines = current.logLines + message,
				logEntries = current.logEntries + entry,
			)
		}
	}

	fun clearLogs() {
		updateState { it.copy(logLines = emptyList(), logEntries = emptyList()) }
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

	fun updateHistorySnapshot(snapshot: AgentHistorySnapshot) {
		updateState { it.copy(historySnapshot = snapshot, projectDirty = it.projectDirty || (it.historySnapshot != null && it.historySnapshot.headNodeId != snapshot.headNodeId), projectEditVersion = it.projectEditVersion + if (it.historySnapshot?.headNodeId != snapshot.headNodeId) 1 else 0) }
	}

	fun selectHistoryNode(nodeId: String?) {
		updateState { it.copy(selectedHistoryNodeId = nodeId) }
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

	fun setAnimationEnabled(enabled: Boolean) {
		if (enabled) {
			// The editor's playback poses a paused preview; the running animation takes over.
			motionEditor.playing = false
		}
		val current = _state.value
		updateCanvasPresentation(current.activeWorkspace.id, current.previewControlCanvas().id, CanvasMode.PREVIEW) {
			it.copy(animationEnabled = enabled)
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

	fun setAutoDetectMeshSplitsOnImport(enabled: Boolean) {
		AppSettings.autoDetectMeshSplitsOnImport = enabled
		updateState { it.copy(autoDetectMeshSplitsOnImport = enabled) }
		markWorkspaceChanged()
	}

	fun setHoveredItem(layerId: String?, deformerId: String?) {
		updateState {
			if (it.hoveredLayerId == layerId && it.hoveredDeformerId == deformerId) it
			else it.copy(hoveredLayerId = layerId, hoveredDeformerId = deformerId)
		}
	}

	fun setDeformerVisibility(deformerId: String, visible: Boolean) {
		updateState {
			val updated = it.deformerVisibility + (deformerId to visible)
			it.copy(
				deformerVisibility = updated,
				statusText = tr("status.visibilityChanged"),
			)
		}
		markWorkspaceChanged()
	}

	fun toggleLayerVisibility(layerId: String) {
		val current = _state.value.isLayerVisible(layerId)
		setLayerVisibility(layerId, !current)
	}

	fun setLayerVisibility(layerId: String, visible: Boolean) {
		updateState {
			val updated = it.layerVisibility + (layerId to visible)
			it.copy(
				layerVisibility = updated,
				statusText = tr("status.visibilityChanged"),
				isolationSnapshot = null,
				isolatedLayerId = null,
			)
		}
	    markWorkspaceChanged()
	}

	fun setAllLayersVisibility(visible: Boolean) {
		val analysis = _state.value.analysis ?: return
		val updated = analysis.layers.associate { it.source.id.raw to visible }
		updateState {
			it.copy(
				layerVisibility = updated,
				statusText = tr("status.visibilityChanged"),
				isolationSnapshot = null,
				isolatedLayerId = null,
			)
		}
	    markWorkspaceChanged()
	}

	fun invertLayerVisibility() {
		val analysis = _state.value.analysis ?: return
		val current = _state.value
		val updated = analysis.layers.associate { layer ->
			val id = layer.source.id.raw
			id to !current.isLayerVisible(id, layer.source.visible)
		}
		updateState {
			it.copy(
				layerVisibility = updated,
				statusText = tr("status.visibilityChanged"),
				isolationSnapshot = null,
				isolatedLayerId = null,
			)
		}
	    markWorkspaceChanged()
	}

	fun isolateLayer(layerId: String) {
		val analysis = _state.value.analysis ?: return
		val current = _state.value
		if (current.isolatedLayerId == layerId && current.isolationSnapshot != null) {
			updateState {
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
			updateState {
				it.copy(
					layerVisibility = updated,
					isolationSnapshot = snapshot,
					isolatedLayerId = layerId,
					statusText = tr("status.visibilityChanged"),
				)
			}
		}
	    markWorkspaceChanged()
	}

	fun deleteLayer(layerId: String) {
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
		updateState { current ->
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

		updateState { current ->
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
		val workspaceId = _state.value.activeWorkspace.id
		val canvasId = _state.value.activeCanvas.id
		scope.launch {
			try {
				updateState { it.copy(statusText = tr("status.importingLayers", rasters.size)) }
				val result = withContext(Dispatchers.Default) {
					buildImportedLayersPreview(rasters, parentDeformerId)
				}
				updateState {
					it.copy(
						parentOverrides = result.parentOverrides,
						layerOverrides = result.layerOverrides,
					)
				}
				updateCanvasPresentation(workspaceId, canvasId, CanvasMode.EDIT) {
					it.copy(layerVisibility = it.layerVisibility + result.layerIds.associateWith { true })
				}
				applyCommittedPaint(result.preview, tr("editor.importLayer.summary", result.layerIds.size))
				val placeId = result.layerIds.lastOrNull() ?: return@launch
				val placeName = result.preview.analysis.layers
					.firstOrNull { it.source.id.raw == placeId }?.source?.name
					?: placeId
				val bounds = result.preview.analysis.source.layers
					.firstOrNull { it.id.raw == placeId }?.bounds
					?: return@launch
				updateCanvasPresentation(workspaceId, canvasId, CanvasMode.EDIT) {
					it.copy(selectedLayerId = placeId, selectedDeformerId = null)
				}
				// Let history-driven gesture cleanup run before arming the placement panel,
				// so a cancel() from head-node churn cannot race the new LAYER session.
				yield()
				if (_state.value.activeCanvas.id == canvasId && _state.value.activeCanvas.mode != CanvasMode.EDIT) {
					setCanvasMode(canvasId, CanvasMode.EDIT)
				}
				canvasEditorFor(canvasId).beginLayerPlacement(
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
		splitCandidates: List<String> = emptyList(),
	) {
		val current = _state.value
		val preview = current.previewModel ?: return
		val workspaceId = current.activeWorkspace.id
		val canvasId = current.activeCanvas.id
		val canvasMode = current.activeCanvas.mode
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
		if (existing.bounds == newBounds && (name.isBlank() || name == existing.name)) {
			if (commitHistory) offerMeshSplit(splitCandidates)
			return
		}
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
					offerMeshSplit(splitCandidates)
				} else {
					applyPreviewWithoutHistory(built)
				}
				selectOnCanvas(workspaceId, canvasId, canvasMode, layerId)
				// Keep paint session on the relocated layer if the artist was painting it.
				val editor = canvasEditorFor(canvasId)
				if (editor.hierarchyMode == EditHierarchyMode.PAINT) {
					editor.startPaintSession(layerId, forceReload = true)
				}
			} catch (failure: Exception) {
				if (failure is kotlinx.coroutines.CancellationException) throw failure
				setErrorMessage(failure.message ?: tr("error.importLayerFailed"))
			}
		}
	}

	/** Publish a rebuilt preview without opening a history node (live placement scrub). */
	private fun applyPreviewWithoutHistory(updatedPreview: RigPreviewModel) {
		updateState {
			it.copy(
				previewModel = updatedPreview,
				analysis = updatedPreview.analysis,
				rigEdits = updatedPreview.config.rigEdits,
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
				updateState {
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

	fun setLayerDrawOrder(targetId: String, order: Float) {
		val clamped = order.coerceIn(0f, 1000f)
		val model = _state.value.previewModel
		val layerId = model?.rig?.layerIdByDrawableId?.get(targetId) ?: targetId
		updateState { current ->
			val updated = current.drawOrderOverrides + (layerId to clamped)
			current.copy(drawOrderOverrides = updated)
		}
		editorChanged()
	}

	fun resetLayerDrawOrder(targetId: String) {
		val model = _state.value.previewModel
		val layerId = model?.rig?.layerIdByDrawableId?.get(targetId) ?: targetId
		updateState { current ->
			current.copy(drawOrderOverrides = current.drawOrderOverrides - layerId - targetId)
		}
		editorChanged()
	}

	fun setLayerClassification(layerId: String, override: LayerClassificationOverride) {
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
		updateState { current ->
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
		val model = _state.value.previewModel
		val param = model?.rig?.puppet?.parameters?.firstOrNull { it.id == id }
		val clamped = if (param != null) value.coerceIn(param.min, param.max) else value
		if (updateParameterScrub(mapOf(id to clamped))) return
		motionEditor.playing = false
		motionPlayer.stop()
		val previous = _state.value.parameterValues[id] ?: param?.default ?: 0f
		val changed = abs(previous - clamped) >= 1e-5f

		updateState { current ->
			current.copy(
				animationEnabled = false,
				parameterValues = current.parameterValues + (id to clamped),
				projectDirty = current.projectDirty || current.analysis != null,
				projectEditVersion = current.projectEditVersion + if (current.analysis != null) 1 else 0,
			).authoringPose(current.activeCanvas.mode == CanvasMode.EDIT)
		}

		if (changed && motionEditor.autoKey && editingMotionClip() != null) {
			recordAutoKey(mapOf(id.raw to clamped), mapOf(id.raw to (param?.default ?: 0f)))
		}

	}

	/** Several parameters in one state update, so a gesture that moves a chain redraws once, not per joint. */
	fun setParameterValues(values: Map<ParameterId, Float>) {
		if (values.isEmpty()) return
		val model = _state.value.previewModel
		val parameters = model?.rig?.puppet?.parameters?.associateBy { it.id }.orEmpty()
		val clampedMap = values.mapValues { (id, value) -> parameters[id]?.let { value.coerceIn(it.min, it.max) } ?: value }
		if (updateParameterScrub(clampedMap)) return
		motionEditor.playing = false
		motionPlayer.stop()

		val changed = mutableMapOf<String, Float>()
		val initial = mutableMapOf<String, Float>()
		for ((id, clamped) in clampedMap) {
			val param = parameters[id]
			val previous = _state.value.parameterValues[id] ?: param?.default ?: 0f
			if (abs(previous - clamped) >= 1e-5f) {
				changed[id.raw] = clamped
				initial[id.raw] = param?.default ?: 0f
			}
		}

		updateState { current ->
			current.copy(
				animationEnabled = false,
				parameterValues = current.parameterValues + clampedMap,
				projectDirty = current.projectDirty || current.analysis != null,
				projectEditVersion = current.projectEditVersion + if (current.analysis != null) 1 else 0,
			).authoringPose(current.activeCanvas.mode == CanvasMode.EDIT)
		}

		if (changed.isNotEmpty() && motionEditor.autoKey && editingMotionClip() != null) {
			recordAutoKey(changed, initial)
		}

	}

	private fun updateParameterScrub(values: Map<ParameterId, Float>): Boolean {
		val scrub = parameterScrub ?: return false
		val current = _state.value
		if (current.projectOpenGeneration != scrub.generation || current.activeWorkspace.id != scrub.workspaceId) return false
		var overrides = scrub.overrides
		for ((id, value) in values) {
			if (overrides[id] == value) continue
			overrides = overrides + (id to value)
			parameterScrubValues[id] = value
		}
		if (overrides !== scrub.overrides) parameterScrub = scrub.copy(overrides = overrides)
		return true
	}

	/** The same authored pose for preview and paused physics, including uncommitted slider values. */
	internal fun parameterScrubPose(current: PSD2LiveState, values: Map<ParameterId, Float>): Map<ParameterId, Float> {
		val scrub = parameterScrub ?: return values
		return if (scrub.generation == current.projectOpenGeneration && scrub.workspaceId == current.activeWorkspace.id && scrub.overrides.isNotEmpty())
			values + scrub.overrides else values
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
			updateState { current ->
				val next = current.parameterValues.toMutableMap()
				for ((id, to) in targets) {
					val a = from[id] ?: to
					next[id] = a + (to - a) * eased
				}
				current.copy(parameterValues = next).authoringPose()
			}
			if (t >= 1f) break
			delay(16L)
		}
		updateState { current ->
			current.copy(parameterValues = current.parameterValues + targets)
		}
		markWorkspaceChanged()
	}

	fun resetParameter(id: ParameterId) {
		val model = _state.value.previewModel
		val param = model?.rig?.puppet?.parameters?.firstOrNull { it.id == id }
		val defaultVal = param?.default ?: 0f
		setParameterValue(id, defaultVal)
		if (id == StandardParameters.ANGLE_X || id == StandardParameters.EYE_BALL_X || id == StandardParameters.BODY_X) {
			followX = 0f
			pointerX = 0f
		}
		if (id == StandardParameters.ANGLE_Y || id == StandardParameters.EYE_BALL_Y || id == StandardParameters.BODY_Y) {
			followY = 0f
			pointerY = 0f
		}
	}

	private fun resetMotionDynamics() {
		pointerActive = false
		pointerX = 0f
		pointerY = 0f
		followX = 0f
		followY = 0f
		softwarePhysics.reset()
		elapsed = 0.0
		motionPlayer.stop()
		lastTick = System.nanoTime()
	}

	fun resetAllParameters() {
		resetMotionDynamics()

		updateState { current ->
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

	fun resetPreviewParameters() {
		resetMotionDynamics()
		val current = _state.value
		val defaults = current.previewModel?.rig?.puppet?.parameters?.associate { it.id to it.default } ?: emptyMap()
		updateCanvasPresentation(current.activeWorkspace.id, current.previewControlCanvas().id, CanvasMode.PREVIEW) {
			it.copy(animationEnabled = false, lockedParameters = emptySet(), parameterValues = defaults,
				previewParameterValues = defaults)
		}
		markWorkspaceChanged()
	}

	/** Saves every parameter as the parameters panel shows it, the live pose included while previewing. */
	fun saveParameterSnapshot(name: String = "") {
		val values = shownParameterValues() ?: return
		updateState { current ->
			val number = (current.parameterSnapshots.maxOfOrNull { it.number } ?: 0) + 1
			current.copy(parameterSnapshots = current.parameterSnapshots +
				ParameterSnapshot(java.util.UUID.randomUUID().toString(), number, name, values))
		}
		markWorkspaceChanged()
	}

	fun overwriteParameterSnapshot(id: String) {
		val values = shownParameterValues() ?: return
		updateState { current ->
			current.copy(parameterSnapshots = current.parameterSnapshots.map { if (it.id == id) it.copy(values = values) else it })
		}
		markWorkspaceChanged()
	}

	/** Loads a saved pose onto the parameters it still names; locked parameters keep their value. */
	fun applyParameterSnapshot(id: String) {
		val current = _state.value
		val snapshot = current.parameterSnapshots.firstOrNull { it.id == id } ?: return
		val known = current.previewModel?.rig?.puppet?.parameters?.mapTo(HashSet()) { it.id } ?: return
		setParameterValues(snapshot.values.filterKeys { it in known && it !in current.lockedParameters })
	}

	fun renameParameterSnapshot(id: String, name: String) {
		// Blank goes back to showing the snapshot's number.
		val trimmed = name.trim()
		updateState { current ->
			current.copy(parameterSnapshots = current.parameterSnapshots.map { if (it.id == id) it.copy(name = trimmed) else it })
		}
		markWorkspaceChanged()
	}

	fun deleteParameterSnapshot(id: String) {
		updateState { current -> current.copy(parameterSnapshots = current.parameterSnapshots.filterNot { it.id == id }) }
		markWorkspaceChanged()
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
		updateState { current ->
			current.copy(
				lockedParameters = emptySet(),
			)
		}
	    markWorkspaceChanged()
	}

	fun setMouseTrackingEnabled(enabled: Boolean) {
		val current = _state.value
		updateCanvasPresentation(current.activeWorkspace.id, current.previewControlCanvas().id, CanvasMode.PREVIEW) {
			it.copy(mouseTrackingEnabled = enabled)
		}
		if (!enabled) {
			pointerActive = false
			pointerX = 0f
			pointerY = 0f
		}
	    markWorkspaceChanged()
	}

	fun updatePointer(screenNormX: Float, screenNormY: Float, owner: String? = null) {
        if (owner != null) canvasPointers[owner] = screenNormX.coerceIn(-1f, 1f) to screenNormY.coerceIn(-1f, 1f)
        pointerOwner = owner
		pointerActive = true
		pointerX = screenNormX.coerceIn(-1f, 1f)
		pointerY = screenNormY.coerceIn(-1f, 1f)
	}

	fun clearPointer(owner: String? = null) {
        if (owner != null) canvasPointers.remove(owner) else canvasPointers.clear()
        if (owner != null && pointerOwner != owner) return
        pointerOwner = null
		pointerActive = false
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

	fun clearSuccessExportMessage() {
		updateState { it.copy(successExportMessage = null) }
	}

	fun analyze() {
		val rawInput = _state.value.inputPath.trim()
		if (rawInput.isEmpty()) {
			updateState { it.copy(errorMessage = tr("dialog.inputRequired")) }
			return
		}
		val input = Path.of(rawInput)
		if (!Files.isRegularFile(input) || !input.fileName.toString().endsWith(".psd", true)) {
			updateState { it.copy(errorMessage = tr("dialog.inputInvalid", input)) }
			return
		}

		activeWorkJob?.cancel()
		activeWorkJob = scope.launch {
			updateState {
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
				updateState { current ->
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
                        layerVisibility = emptyMap(), deformerVisibility = emptyMap(), layerOverrides = emptyMap(),
                        deletedLayerIds = emptySet(), parentOverrides = emptyMap(), rigEdits = preview.config.rigEdits,
                        selectedLayerId = null, selectedDeformerId = null, hoveredLayerId = null, hoveredDeformerId = null,
                        isolatedLayerId = null, isolationSnapshot = null, animationEnabled = false,
                        mouseTrackingEnabled = true, previewParameterValues = emptyMap(),
                        workspaces = current.workspaces.map { workspace ->
                            workspace.copy(canvases = workspace.canvases.map { canvas ->
                                canvas.updateSession(CanvasMode.EDIT) {
                                    it.copy(camera = TabCamera(), presentation = CanvasPresentation())
                                }.updateSession(CanvasMode.PREVIEW) {
                                    it.copy(camera = TabCamera(), presentation = CanvasPresentation())
                                }
                            })
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
						parameterSnapshots = emptyList(),
					)
				}
				refreshSdkSession(preview)
                resetCanvasPaintSessions()
                (agentWorkspace as? io.github.psd2live.agent.ViewModelAgentWorkspace)?.importedPsd()
                updateState { it.copy(isAnalyzing = false) }
				offerImportMeshSplit(_state.value.analysis?.source?.layers.orEmpty().map { it.id.raw })
			} catch (failure: Throwable) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
				val detail = failure.message ?: failure.javaClass.simpleName
				updateState {
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
			updateState { it.copy(errorMessage = tr("dialog.inputRequired")) }
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
				updateState { it.copy(errorMessage = tr("dialog.outputRequired")) }
				return
			}
		}
		lastExportDirectory = rawOutput
		val input = Path.of(rawInput)
		val output = Path.of(rawOutput)
		val config = _state.value.buildConfig()
		val workspaceSource = _state.value.analysis?.source
		if (!config.exportCmo3 && !config.exportMoc3) {
			updateState { it.copy(errorMessage = tr("dialog.exportFormatRequired")) }
			return
		}

		activeWorkJob?.cancel()
		activeWorkJob = scope.launch {
			updateState {
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
							updateState {
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
				updateState { current ->
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
				updateState {
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

	fun exportPsd(targetPath: Path, scale: Int = 1, includeGeneratedLayers: Boolean = true) {
		val currentState = _state.value
		val analysis = currentState.analysis ?: run {
			updateState { it.copy(errorMessage = tr("error.noPsdLoaded")) }
			return
		}
		activeWorkJob?.cancel()
		activeWorkJob = scope.launch {
			updateState {
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
					updateState { it.copy(statusText = tr("upscale.startingInference"), progress = 0.15f) }
					io.github.psd2live.core.TextureUpscale.prepare(
						layers = analysis.layers,
						config = currentState.textureUpscale.copy(scale = scale),
						progress = { stage, frac ->
							updateState { it.copy(statusText = stage, progress = (0.15 + frac * 0.70).toFloat().coerceIn(0.15f, 0.85f)) }
						}
					)
				} else emptyMap()

				updateState { it.copy(statusText = tr("exportPsd.writingBytes"), progress = 0.90f) }
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
				updateState {
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
				updateState {
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
		expectedMeshOverrides: Map<String, MeshSettings> = emptyMap(),
		layerVisibility: Map<String, Boolean>,
		deletedLayerIds: Set<String>,
		layerOverrides: Map<String, LayerClassificationOverride>,
		parentOverrides: Map<String, String?>,
		rigEdits: RigEditOverlay,
		status: String,
        settings: kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.JsonObject(emptyMap()),
		meshOverrides: Map<String, MeshSettings> = emptyMap(),
	): Boolean {
		previewRebuildJob?.cancel()
		resetCanvasPaintSessions()
		var applied = false
		updateState { current ->
			applied = false
			if (
				current.analysis?.source !== expectedSource ||
				current.layerVisibility != expectedLayerVisibility ||
				current.deletedLayerIds != expectedDeletedLayerIds ||
				current.layerOverrides != expectedLayerOverrides ||
				current.parentOverrides != expectedParentOverrides ||
				current.rigEdits != expectedRigEdits ||
                current.meshOverrides != expectedMeshOverrides ||
                (expectedSettings.isNotEmpty() && io.github.psd2live.project.WorkspaceStateCodec.settings(current) != expectedSettings)
			) return@updateState current
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
				meshOverrides = meshOverrides,
				selectedLayerId = current.selectedLayerId?.takeIf { selected ->
					preview.analysis.layers.any { it.source.id.raw == selected } && selected !in deletedLayerIds
				},
				selectedLayerIds = current.selectedLayerIds.filterTo(LinkedHashSet()) { selected ->
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
		resetCanvasPaintSessions()
		updateState { it.copy(previewModel = preview, analysis = preview.analysis, previewModelDirty = false) }
		refreshSdkSession(preview)
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
				updateState { current ->
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
				refreshSdkSession(rebuilt)
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

	/** Compose-readable live value for [id]; reading it only invalidates when that entry changes. */
	fun livePoseOf(id: ParameterId): Float? = _livePoseSnapshot[id]

	/** Publish [next] to both the StateFlow readers and the per-key Compose snapshot. */
	private fun setLivePose(next: Map<ParameterId, Float>) {
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
	val activeMotionName: String? get() = motionPlayer.activeName

	/**
	 * Plays [name] on the preview from its start. Cubism plays the exported motion, forced over whatever is
	 * playing; the software clock plays the same tracks, and drives the preview until Cubism is up.
	 */
	fun triggerMotion(name: String) {
		motionEditor.playing = false
		updateState { it.copy(animationEnabled = true) }
		ensureSdkSessionLoaded()
		val current = _state.value
		current.activeWorkspace.canvases.filter { it.mode == CanvasMode.PREVIEW && it.id !in current.activeWorkspace.hiddenModules }.forEach {
            sdkSession.startMotion(name, priority = MOTION_PRIORITY_FORCE, viewId = canvasRenderKey(it.id))
        }
		if (name.equals("Idle", ignoreCase = true)) {
			motionPlayer.stop()
			elapsed = 0.0
		} else {
			motionPlayer.start(name, current.rigEdits.skeleton, current.rigEdits.motionClips)
		}
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
			motionPlayer.stop()
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

		val current = _state.value
		val inPreview = current.previewLive
		val isMeshOnly = current.meshOnly
		val anim = inPreview && current.animationEnabled && !isMeshOnly
		val tracking = inPreview && current.mouseTrackingEnabled && !isMeshOnly && current.activeWorkspace.pose?.authoringPose != true
		if (anim) elapsed += dt

		// 0. The animation editor's own playback poses the paused preview.
		advanceMotionEditor(dt)

		// 1. Advance the triggered one-shot; a paused preview holds it where it is.
		val motion = if (anim) motionPlayer.advance(dt) else emptyMap()

		// 2. Eye Blink (Periodic + Triggered)
		val hasBlink = anim && current.motionBlink
		val periodicBlink = if (hasBlink) blinkAt(elapsed % 4.6) else 1f
		val blink = minOf(
			periodicBlink,
			motion[StandardParameters.EYE_L_OPEN] ?: 1f,
			motion[StandardParameters.EYE_R_OPEN] ?: 1f,
		)

		// 3. Idle Motion (Head & Body Sway, Mouse Tracking)
		val hasIdle = anim && current.motionIdle
		val idleX = if (hasIdle) (sin(elapsed * 0.47) * 0.12).toFloat() else 0f
		val idleY = if (hasIdle) (sin(elapsed * 0.31 + 1.1) * 0.08).toFloat() else 0f
		val targetX = if (pointerActive && tracking) pointerX else idleX
		val targetY = if (pointerActive && tracking) pointerY else idleY
		val response = (dt * 7.5f).coerceAtMost(1f)
		followX += (targetX - followX) * response
		followY += (targetY - followY) * response

		if (!pointerActive && kotlin.math.abs(followX - targetX) < 0.001f) followX = targetX
		if (!pointerActive && kotlin.math.abs(followY - targetY) < 0.001f) followY = targetY

		val model = current.previewModel
		if (model != null && inPreview && (anim || tracking)) {
			val liveParams = if (isMeshOnly) {
				model.rig.puppet.parameters.associate { it.id to it.default }
			} else computeLiveParameters(
				model = model,
				current = current,
				blink = blink,
				motion = motion,
			).let { inputs ->
				// 4. Physics reads the posed inputs and writes its outputs over them, as Cubism evaluates it.
				if (anim) inputs + stepSoftwarePhysics(PhysicsClock.PLAYING, current, model, inputs, dt) else inputs
			}
			latestLiveParameters = liveParams
			if (current.sdkStatus != "ready") {
				updateState { latest ->
					if (!latest.previewLive) latest
					else {
						val mergedValues = parameterValuesAfterSoftwareFrame(latest, liveParams, pointerActive)
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
		}
		// 5. Paused, physics still runs, on the pose the user sets: a slider or the pointer's look swings it.
		stepPausedPhysics(current, model, inPreview && !anim && current.generatePhysics && !isMeshOnly, tracking, dt)
		// 6. The live simulation follows whichever pose the preview now shows.
		if (inPreview) stepSimulationPreview(current, model, dt)
	}

	/** What the software physics is stepping for; switching starts it from rest, as a fresh Cubism model would. */
	private enum class PhysicsClock { NONE, PLAYING, PAUSED }
	private var physicsClock = PhysicsClock.NONE

	private fun stepSoftwarePhysics(clock: PhysicsClock, state: PSD2LiveState, model: RigPreviewModel,
		inputs: Map<ParameterId, Float>, dt: Float): Map<ParameterId, Float> {
		if (clock != physicsClock) {
			softwarePhysics.reset()
			physicsClock = clock
		}
		return softwarePhysics.step(state, model, inputs, dt)
	}

	/**
	 * The physics outputs a paused preview shows over the edit pose. Cubism's update would also advance the
	 * paused motion, so the preview runs physics here, on the engine checked against the SDK frame by frame,
	 * and hands the outputs to the renderer with the pose.
	 */
	private var pausedPhysics: Map<ParameterId, Float> = emptyMap()
	private var pausedPhysicsStillFor = 0f
	/** True once the paused physics has come to rest; the preview then stops asking for frames. */
	@Volatile var pausedPhysicsSettled = true
		private set

	private fun stepPausedPhysics(current: PSD2LiveState, model: RigPreviewModel?, on: Boolean, tracking: Boolean, dt: Float) {
		if (!on || model == null || current.activeWorkspace.pose?.authoringPose == true) {
			if (physicsClock == PhysicsClock.PAUSED) physicsClock = PhysicsClock.NONE
			// The software preview let go of the swing: back to the edit pose, unless the pointer holds a look.
			if (pausedPhysics.isNotEmpty() && current.sdkStatus != "ready" && !pointerActive) {
				setLivePose(emptyMap())
				updateState { latest -> if (latest.previewParameterValues == latest.parameterValues) latest else latest.copy(previewParameterValues = latest.parameterValues) }
			}
			pausedPhysics = emptyMap()
			pausedPhysicsSettled = true
			return
		}
		val panel = current.previewPanelState()
		val pointer = if (tracking) canvasPointers[canvasRenderKey(panel.previewControlCanvas().id, CanvasMode.PREVIEW)] else null
		val pose = pausedPointerPose(parameterScrubPose(current, panel.parameterValues), pointer?.first ?: 0f, -(pointer?.second ?: 0f))
		val out = stepSoftwarePhysics(PhysicsClock.PAUSED, current, model, pose, dt).filterKeys { it !in panel.lockedParameters }
		val moved = out.any { (id, value) -> kotlin.math.abs(value - (pausedPhysics[id] ?: Float.NaN)) > PAUSED_PHYSICS_REST || pausedPhysics[id] == null }
		pausedPhysicsStillFor = if (moved) 0f else pausedPhysicsStillFor + dt
		pausedPhysicsSettled = pausedPhysicsStillFor >= PAUSED_PHYSICS_REST_SECONDS
		pausedPhysics = out
		if (current.sdkStatus != "ready") {
			val shown = pose + out
			setLivePose((if (pointer != null) pose.filterKeys { it in POINTER_POSE_PARAMETERS } else emptyMap()) + out)
			updateState { latest -> if (!latest.previewLive || latest.previewParameterValues == shown) latest else latest.copy(previewParameterValues = shown) }
		}
	}

	fun computeLiveParameters(
		model: RigPreviewModel,
		current: PSD2LiveState = _state.value,
		blink: Float = blinkAt(elapsed % 4.6),
		motion: Map<ParameterId, Float> = emptyMap(),
	): Map<ParameterId, Float> {
		if (current.meshOnly) {
			return model.rig.puppet.parameters.associate { it.id to it.default }
		}

		val hasIdle = current.animationEnabled && current.motionIdle

		val mouthPhase = elapsed % 5.8
		val mouthOpen = if (mouthPhase in 1.25..2.45 && current.animationEnabled && hasIdle) {
			sin((mouthPhase - 1.25) / 1.20 * PI).toFloat().coerceAtLeast(0f)
		} else 0f

		// The idle the export writes, body parameters and skeleton poses alike; pointer follow adds on top.
		// An edited idle plays its clip; its blink joins the periodic one rather than replacing the eyes.
		val idleOverride = MotionClips.overrideOf(current.rigEdits.motionClips, "Idle")
		val idle = when {
			!hasIdle -> emptyMap()
			idleOverride != null -> MotionClips.sampleAll(idleOverride, elapsed, loop = true)
			else -> io.github.psd2live.core.SkeletonMotions.liveIdle(model.config.rigEdits.skeleton, elapsed)
		}
		// A playing motion replaces the idle on what it drives, as Cubism's forced motion does; the pointer
		// follow still adds on top, like Cubism's look updater.
		fun idleOf(id: ParameterId) = motion[id] ?: idle[id] ?: 0f

		val isTracking = pointerActive && current.mouseTrackingEnabled && !current.meshOnly
		val headAngleX = if (hasIdle || isTracking) followX * 38f else 0f
		val headAngleY = if (hasIdle || isTracking) -followY * 24f else 0f
		val bodyAngleX = if (hasIdle || isTracking) followX * 4f else 0f
		val bodyAngleY = if (hasIdle || isTracking) -followY * 2f else 0f
		val eyeBallX = if (hasIdle || isTracking) followX.coerceIn(-1f, 1f) else 0f
		val eyeBallY = if (hasIdle || isTracking) (-followY).coerceIn(-1f, 1f) else 0f

		val base = mapOf(
			StandardParameters.ANGLE_X to (headAngleX + idleOf(StandardParameters.ANGLE_X)),
			StandardParameters.ANGLE_Y to (headAngleY + idleOf(StandardParameters.ANGLE_Y)),
			StandardParameters.ANGLE_Z to idleOf(StandardParameters.ANGLE_Z),
			StandardParameters.BODY_X to (bodyAngleX + idleOf(StandardParameters.BODY_X)),
			StandardParameters.BODY_Y to (bodyAngleY + idleOf(StandardParameters.BODY_Y)),
			StandardParameters.BODY_Z to idleOf(StandardParameters.BODY_Z),
			StandardParameters.EYE_BALL_X to eyeBallX,
			StandardParameters.EYE_BALL_Y to eyeBallY,
			StandardParameters.EYE_L_OPEN to minOf(blink, idle[StandardParameters.EYE_L_OPEN] ?: 1f),
			StandardParameters.EYE_R_OPEN to minOf(blink, idle[StandardParameters.EYE_R_OPEN] ?: 1f),
			StandardParameters.MOUTH_FORM to if (current.animationEnabled && hasIdle) sin(elapsed * 0.41).toFloat() * 0.18f else 0f,
			StandardParameters.MOUTH_OPEN to mouthOpen,
			StandardParameters.BREATH to idleOf(StandardParameters.BREATH),
		)
		val available = model.rig.puppet.parameters.mapTo(HashSet()) { it.id }
		return base + idle.filterKeys { it !in base && it in available } + motion.filterKeys { it !in base && it in available }
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
        viewId: String = "",
	) {
		val snapshot = _state.value
		val keyPrefix = "${snapshot.projectOpenGeneration}/${snapshot.activeWorkspace.id}/"
		val canvas = if (viewId.startsWith(keyPrefix)) {
			snapshot.activeWorkspace.canvases.firstOrNull {
				it.mode == CanvasMode.PREVIEW && "${it.id}/PREVIEW" == viewId.removePrefix(keyPrefix)
			}
		} else null
		if (viewId.isNotEmpty() && canvas == null) return
		val presentation = if (canvas == null || canvas.id == snapshot.activeCanvas.id)
			CanvasPresentation.capture(snapshot) else canvas.presentation
		if (snapshot.previewModel == null) return
		// The canvas the panels follow drives the clock, once per frame it asks for.
		val drivesClock = canvas == null || canvas.id == snapshot.previewControlCanvas().id
		if (drivesClock) {
			lastPumpTickNanos = System.nanoTime()
			tickMotion()
		}
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
		).let { if (!isAnim && snapshot.activeWorkspace.pose?.authoringPose != true && pausedPhysics.isNotEmpty()) it + pausedPhysics else it }
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
				pointerX = if (tracking) canvasPointers[viewId]?.first ?: 0f else 0f,
				pointerY = if (tracking) -(canvasPointers[viewId]?.second ?: 0f) else 0f,
				animationEnabled = isAnim,
				parameterOverrides = previewValues,
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
        canvasEditors.clear()
	}

	internal fun setStateForTest(state: PSD2LiveState) {
		replaceState(state)
	}

	private companion object {
		const val SDK_PARAMETER_PUBLISH_INTERVAL_NANOS = 100_000_000L
		/** Without a pump frame for this long, the fallback loop runs the clock. */
		const val PUMP_IDLE_NANOS = 100_000_000L
		/** A paused physics output moving less than this per step is at rest. */
		const val PAUSED_PHYSICS_REST = 1e-4f
		/** How long paused physics stays still before the preview stops rendering it. */
		const val PAUSED_PHYSICS_REST_SECONDS = 0.5f
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
 * The pose a paused preview shows under the pointer at ([x], [y]): Cubism's look offsets added to [values] the
 * way the renderer applies them, so paused physics reads what is on screen.
 */
internal fun pausedPointerPose(values: Map<ParameterId, Float>, x: Float, y: Float): Map<ParameterId, Float> {
	if (x == 0f && y == 0f) return values
	val pose = values.toMutableMap()
	for (binding in io.github.psd2live.core.CUBISM_POINTER_TRACKING_BINDINGS) {
		val id = ParameterId(binding.parameterId)
		pose[id] = (values[id] ?: 0f) + x * binding.xScale + y * binding.yScale
	}
	return pose
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

	val standardIds = StandardParameters.all.map { it.id }.toSet()
	val overrides = parameterValues.filterKeys { it in lockedParameters || it !in standardIds }.toMutableMap()

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
			if (id !in lockedParameters) {
				overrides[id] = liveParams[id] ?: 0f
			}
		}
	}

	// 2. Blink motion disabled:
	// Silences Native SDK eye blinking; keeps eyes fully open (1.0f).
	if (!state.motionBlink) {
		if (StandardParameters.EYE_L_OPEN !in lockedParameters) {
			overrides[StandardParameters.EYE_L_OPEN] = liveParams[StandardParameters.EYE_L_OPEN] ?: 1.0f
		}
		if (StandardParameters.EYE_R_OPEN !in lockedParameters) {
			overrides[StandardParameters.EYE_R_OPEN] = liveParams[StandardParameters.EYE_R_OPEN] ?: 1.0f
		}
	}

	// 3. A switched-off preset holds its parameter at rest, unless one of the user's groups drives it.
	val physicsActive = state.generatePhysics && !state.meshOnly
	val userDriven = if (physicsActive) state.rigEdits.physicsEdits.filter { it.id !in state.rigEdits.disabledPhysicsIds }
		.flatMapTo(HashSet()) { it.outputParameters } else emptySet()
	for ((on, id) in listOf(state.physicsFrontHair to StandardParameters.HAIR_FRONT, state.physicsBackHair to StandardParameters.HAIR_BACK,
		state.physicsEyeJelly to StandardParameters.EYE_BALL_FORM)) {
		if ((!physicsActive || !on) && id.raw !in userDriven && id !in lockedParameters) overrides[id] = 0f
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
): Boolean = state.previewLive &&
	(frameAnimationEnabled == (state.animationEnabled && !state.meshOnly))
