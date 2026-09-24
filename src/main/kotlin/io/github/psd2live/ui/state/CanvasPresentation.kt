package io.github.psd2live.ui.state

import androidx.compose.runtime.Immutable
import org.umamo.runtime.model.ParameterId

/** View-local interaction and preview pose. Model edits stay on PSD2LiveState. */
@Immutable
data class CanvasPresentation(
    val selectedLayerId: String? = null,
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
