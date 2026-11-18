package io.github.psd2live.ui.state

/**
 * Content that requires Save/Discard. Presentation (camera, focus, selection, filters, panels,
 * view options and playback controls) is still captured in archives, but never requires saving.
 * Kept as immutable values rather than serializing the entire presentation on every preview frame.
 */
internal typealias ProjectSaveIdentity = io.github.psd2live.application.WorkspaceDurableIdentity

internal object WorkspaceDurableProjection {
        fun of(state: PSD2LiveState) = ProjectSaveIdentity(
            state.analysis?.source, state.rawConfig(), state.outputPath,
            state.parameterSnapshots, state.historyAnnotations, state.projectAuxiliaryVersion,
            state.workspaces.map { workspace ->
                val pose = if (workspace.id == state.activeWorkspace.id) WorkspacePose.capture(state)
                    else workspace.pose ?: WorkspacePose.capture(workspace.activeCanvas.presentation)
                io.github.psd2live.application.WorkspaceDurableIdentity.Workspace(workspace.id, workspace.name, workspace.preset.name, pose.parameterValues, pose.lockedParameters,
                    workspace.canvases.associate { canvas -> canvas.id to listOf(
                        canvas.editSession.presentation.canvasVisibility(), canvas.previewSession.presentation.canvasVisibility()) })
            },
            externalPsdPath = state.externalPsdPath,
        )

    /** Compare inputs without building workspace maps or serializing presentation-only state. */
    fun unchanged(before: PSD2LiveState, after: PSD2LiveState): Boolean {
        if (before.analysis?.source != after.analysis?.source || before.rawConfig() != after.rawConfig() ||
            before.outputPath != after.outputPath || before.externalPsdPath != after.externalPsdPath || before.parameterSnapshots != after.parameterSnapshots ||
            before.historyAnnotations != after.historyAnnotations || before.projectAuxiliaryVersion != after.projectAuxiliaryVersion ||
            before.workspaces.size != after.workspaces.size) return false
        if (before.activeWorkspaceId == after.activeWorkspaceId && before.workspaces === after.workspaces && before.parameterValues === after.parameterValues &&
            before.lockedParameters === after.lockedParameters) return true
        for (index in before.workspaces.indices) {
            val left = before.workspaces[index]; val right = after.workspaces[index]
            if (left.id != right.id || left.name != right.name || left.preset != right.preset || left.canvases.size != right.canvases.size) return false
            val leftValues = if (left.id == before.activeWorkspace.id) before.parameterValues else left.pose?.parameterValues ?: left.activeCanvas.presentation.parameterValues
            val rightValues = if (right.id == after.activeWorkspace.id) after.parameterValues else right.pose?.parameterValues ?: right.activeCanvas.presentation.parameterValues
            val leftLocks = if (left.id == before.activeWorkspace.id) before.lockedParameters else left.pose?.lockedParameters ?: left.activeCanvas.presentation.lockedParameters
            val rightLocks = if (right.id == after.activeWorkspace.id) after.lockedParameters else right.pose?.lockedParameters ?: right.activeCanvas.presentation.lockedParameters
            if (leftValues != rightValues || leftLocks != rightLocks) return false
            for (canvas in left.canvases) {
                val next = right.canvases.firstOrNull { it.id == canvas.id } ?: return false
                for (mode in CanvasMode.entries) {
                    val old = canvas.session(mode).presentation; val value = next.session(mode).presentation
                    if (old.layerVisibility != value.layerVisibility || old.deformerVisibility != value.deformerVisibility ||
                        old.isolatedLayerId != value.isolatedLayerId || old.isolationSnapshot != value.isolationSnapshot) return false
                }
            }
        }
        return true
    }
}
