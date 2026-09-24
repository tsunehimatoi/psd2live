package io.github.psd2live.ui.state

import androidx.compose.runtime.Immutable
import org.umamo.runtime.model.ParameterId

/** View-local interaction and preview pose. Model edits stay on PSD2LiveState. */
@Immutable
data class CanvasPresentation(
    val selectedLayerId: String? = null,
    val selectedLayerIds: Set<String> = emptySet(),
    val selectedDeformerId: String? = null,
    val hoveredLayerId: String? = null,
    val hoveredDeformerId: String? = null,
    val layerVisibility: Map<String, Boolean> = emptyMap(),
    val deformerVisibility: Map<String, Boolean> = emptyMap(),
    val isolatedLayerId: String? = null,
    val isolationSnapshot: Map<String, Boolean>? = null,
    val parameterValues: Map<ParameterId, Float> = emptyMap(),
    val lockedParameters: Set<ParameterId> = emptySet(),
    val previewParameterValues: Map<ParameterId, Float> = emptyMap(),
    val animationEnabled: Boolean = false,
    val mouseTrackingEnabled: Boolean = true,
) {
    fun applyTo(state: PSD2LiveState): PSD2LiveState = state.copy(
        selectedLayerId = selectedLayerId,
        selectedLayerIds = selectedLayerIds,
        selectedDeformerId = selectedDeformerId,
        hoveredLayerId = hoveredLayerId,
        hoveredDeformerId = hoveredDeformerId,
        layerVisibility = layerVisibility,
        deformerVisibility = deformerVisibility,
        isolatedLayerId = isolatedLayerId,
        isolationSnapshot = isolationSnapshot,
        parameterValues = parameterValues,
        lockedParameters = lockedParameters,
        previewParameterValues = previewParameterValues,
        animationEnabled = animationEnabled,
        mouseTrackingEnabled = mouseTrackingEnabled,
    )

    companion object {
        fun capture(state: PSD2LiveState) = CanvasPresentation(
            selectedLayerId = state.selectedLayerId,
            selectedLayerIds = state.selectedLayerIds,
            selectedDeformerId = state.selectedDeformerId,
            hoveredLayerId = state.hoveredLayerId,
            hoveredDeformerId = state.hoveredDeformerId,
            layerVisibility = state.layerVisibility,
            deformerVisibility = state.deformerVisibility,
            isolatedLayerId = state.isolatedLayerId,
            isolationSnapshot = state.isolationSnapshot,
            parameterValues = state.parameterValues,
            lockedParameters = state.lockedParameters,
            previewParameterValues = state.previewParameterValues,
            animationEnabled = state.animationEnabled,
            mouseTrackingEnabled = state.mouseTrackingEnabled,
        )
    }
}

/** Panels use the active canvas projection; canvas rendering always requests its explicit owner. */
fun PSD2LiveState.forCanvas(
    canvasId: String,
    workspaceId: String = activeWorkspace.id,
    mode: CanvasMode? = null,
): PSD2LiveState {
    if (workspaceId == activeWorkspace.id && canvasId == activeCanvas.id &&
        (mode == null || mode == activeCanvas.mode)) return this
    val workspace = workspaces.firstOrNull { it.id == workspaceId } ?: return this
    val canvas = workspace.canvases.firstOrNull { it.id == canvasId } ?: return this
    val targetMode = mode ?: canvas.mode
    val presentation = canvas.session(targetMode).presentation
    val projected = copy(activeWorkspaceId = workspaceId).updateWorkspace(workspaceId) {
        it.copy(activeCanvasId = canvasId, canvases = it.canvases.map { pane ->
            if (pane.id == canvasId) pane.copy(mode = targetMode) else pane
        })
    }
    return presentation.applyTo(projected)
}

/** The preview controlled by playback panels, or this canvas's latent preview session. */
fun PSD2LiveState.previewControlCanvas(): CanvasWindowState =
    activeCanvas.takeIf { it.mode == CanvasMode.PREVIEW }
        ?: activeWorkspace.canvases.firstOrNull {
            it.mode == CanvasMode.PREVIEW && it.id !in activeWorkspace.hiddenModules
        }
        ?: activeCanvas

/** Playback panels follow a preview session even while an edit canvas has keyboard focus. */
fun PSD2LiveState.previewPanelState(): PSD2LiveState {
    val canvas = previewControlCanvas()
    return forCanvas(canvas.id, mode = CanvasMode.PREVIEW)
}

/**
 * Drops selections, hovers and parameter poses that name objects the current puppet no longer has.
 * Every canvas and both of its modes are cleaned, so one canvas deleting a deformer cannot leave
 * another canvas asking geometry for an id that will fail a bare `require`.
 */
internal fun pruneCanvasSessions(state: PSD2LiveState): PSD2LiveState {
    val preview = state.previewModel ?: return state
    val puppet = preview.rig.puppet
    val layerIds = preview.analysis.layers.mapTo(HashSet()) { it.source.id.raw }
    val deformerIds = puppet.deformers.mapTo(HashSet()) { it.id.raw }
    val parameterIds = puppet.parameters.mapTo(HashSet()) { it.id }
    fun CanvasPresentation.pruned(): CanvasPresentation {
        val selectedLayer = selectedLayerId?.takeIf { it in layerIds }
        val selectedLayers = selectedLayerIds.filterTo(LinkedHashSet()) { it in layerIds }
        val selectedDeformer = selectedDeformerId?.takeIf { it in deformerIds }
        val hoveredLayer = hoveredLayerId?.takeIf { it in layerIds }
        val hoveredDeformer = hoveredDeformerId?.takeIf { it in deformerIds }
        val isolated = isolatedLayerId?.takeIf { it in layerIds }
        val snapshot = if (isolated == null) null else isolationSnapshot
        val parameters = if (parameterValues.keys.all { it in parameterIds }) parameterValues
            else parameterValues.filterKeys { it in parameterIds }
        val locked = if (lockedParameters.all { it in parameterIds }) lockedParameters
            else lockedParameters.filterTo(HashSet()) { it in parameterIds }
        val livePreview = if (previewParameterValues.keys.all { it in parameterIds }) previewParameterValues
            else previewParameterValues.filterKeys { it in parameterIds }
        if (selectedLayer == selectedLayerId && selectedLayers == selectedLayerIds && selectedDeformer == selectedDeformerId &&
            hoveredLayer == hoveredLayerId && hoveredDeformer == hoveredDeformerId &&
            isolated == isolatedLayerId && snapshot === isolationSnapshot &&
            parameters === parameterValues && locked === lockedParameters &&
            livePreview === previewParameterValues) return this
        return copy(
            selectedLayerId = selectedLayer,
            selectedLayerIds = selectedLayers,
            selectedDeformerId = selectedDeformer,
            hoveredLayerId = hoveredLayer,
            hoveredDeformerId = hoveredDeformer,
            isolatedLayerId = isolated,
            isolationSnapshot = snapshot,
            parameterValues = parameters,
            lockedParameters = locked,
            previewParameterValues = livePreview,
        )
    }
    val workspaces = state.workspaces.map { workspace ->
        var canvasChanged = false
        val canvases = workspace.canvases.map { canvas ->
            val edit = canvas.editSession.presentation.pruned()
            val previewPresentation = canvas.previewSession.presentation.pruned()
            if (edit === canvas.editSession.presentation && previewPresentation === canvas.previewSession.presentation) canvas
            else {
                canvasChanged = true
                canvas.copy(
                    editSession = if (edit === canvas.editSession.presentation) canvas.editSession
                        else canvas.editSession.copy(presentation = edit),
                    previewSession = if (previewPresentation === canvas.previewSession.presentation) canvas.previewSession
                        else canvas.previewSession.copy(presentation = previewPresentation),
                )
            }
        }
        if (!canvasChanged) workspace else workspace.copy(canvases = canvases)
    }
    val pruned = if (workspaces == state.workspaces) state else state.copy(workspaces = workspaces)
    val active = pruned.activeCanvas.presentation
    return if (CanvasPresentation.capture(pruned) == active) pruned else active.applyTo(pruned)
}

/** Keep the panel projection and its owning canvas in the same atomic state update. */
internal fun reconcileCanvasPresentation(previous: PSD2LiveState, next: PSD2LiveState): PSD2LiveState {
    if (previous === next) return next
    val sameOwner = previous.activeWorkspace.id == next.activeWorkspace.id &&
        previous.activeCanvas.id == next.activeCanvas.id && previous.activeCanvas.mode == next.activeCanvas.mode
    if (sameOwner || previous.projectOpenGeneration != next.projectOpenGeneration) {
        val presentation = if (previous.activeCanvas.presentation != next.activeCanvas.presentation &&
            CanvasPresentation.capture(previous) == CanvasPresentation.capture(next)) next.activeCanvas.presentation
        else CanvasPresentation.capture(next)
        if (next.activeCanvas.presentation == presentation && CanvasPresentation.capture(next) == presentation) return next
        return presentation.applyTo(next.updateCanvas(next.activeCanvas.id) {
            it.updateSession { session -> session.copy(presentation = presentation) }
        })
    }
    val saved = next.updateWorkspace(previous.activeWorkspace.id) { workspace ->
        workspace.copy(canvases = workspace.canvases.map {
            if (it.id == previous.activeCanvas.id) it.updateSession(previous.activeCanvas.mode) { session ->
                session.copy(presentation = CanvasPresentation.capture(previous))
            } else it
        })
    }
    return saved.activeCanvas.presentation.applyTo(saved)
}
