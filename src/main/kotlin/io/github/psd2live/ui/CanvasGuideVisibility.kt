package io.github.psd2live.ui

import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.ui.state.PSD2LiveState
import org.umamo.runtime.model.Deformer

/** Guide visibility is derived from the receiving mode's session, never from an editor instance. */
internal fun visibleCanvasGuideIds(preview: RigPreviewModel, state: PSD2LiveState, warp: Boolean): Set<String> {
    val deformers = preview.rig.puppet.deformers
    val ids = deformers.filter {
        if (warp) it is Deformer.Warp else it is Deformer.Rotation
    }.map { it.id.raw }.toSet()
    if (ids.isEmpty() || !(if (warp) state.showWarp else state.showRotation) || state.selectedLayerId != null) {
        return emptySet()
    }

    val selected = state.selectedDeformerId
    val base = if (selected == null) {
        if (state.filterSelectedOnly) emptySet() else ids
    } else {
        val under = mutableSetOf(selected)
        var grew = true
        while (grew) {
            grew = false
            for (deformer in deformers) {
                if (deformer.id.raw in under) continue
                val parent = state.parentOverrides[deformer.id.raw] ?: deformer.parent?.raw
                if (parent in under) {
                    under.add(deformer.id.raw)
                    grew = true
                }
            }
        }
        under.filterTo(mutableSetOf()) { it in ids }
    }
    val hovered = state.hoveredDeformerId?.takeIf { it in ids }
    return (if (hovered != null) base + hovered else base)
        .filterTo(mutableSetOf()) { state.isDeformerVisible(it) }
}
