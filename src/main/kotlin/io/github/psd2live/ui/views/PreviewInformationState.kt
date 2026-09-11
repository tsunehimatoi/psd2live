package io.github.psd2live.ui.views

import io.github.psd2live.core.CubismSdkFrame
import io.github.psd2live.core.RigPreviewModel
import org.umamo.runtime.model.*

internal fun informationPreviewPose(values: Map<ParameterId, Float>, frame: CubismSdkFrame?, animated: Boolean): Map<ParameterId, Float> =
    if (animated && frame?.animationEnabled == true) frame.parameters else values

/**
 * Computes active Warp IDs to display on canvas.
 * - When a Mesh (Layer) is selected:
 *   If contextualWarp is true or selectedOnly is true:
 *   Only displays the ancestor Warps that directly/indirectly deform this mesh.
 *   If the mesh has no ancestor warps, returns emptySet() so the canvas is clean.
 * - When a Deformer is selected:
 *   Displays that deformer (if it's a Warp) and/or its descendant Warps.
 * - When nothing is selected:
 *   If selectedOnly is true: returns emptySet().
 *   Else: returns all Warps in the model.
 */
internal fun computeActiveWarpIds(
    model: RigPreviewModel,
    selectedDeformerId: String?,
    selectedLayerId: String?,
    parentOverrides: Map<String, String?> = emptyMap(),
    selectedOnly: Boolean = false,
    contextualWarp: Boolean = true,
): Set<String> {
    val puppet = model.rig.puppet
    val allWarps = puppet.deformers.filterIsInstance<Deformer.Warp>().map { it.id.raw }.toSet()
    if (allWarps.isEmpty()) return emptySet()

    // 1. Mesh / Layer selected: do not show warps when a mesh is selected
    if (selectedLayerId != null) {
        return emptySet()
    }

    // 2. Deformer selected
    if (selectedDeformerId != null) {
        val deformerById = puppet.deformers.associateBy { it.id.raw }
        val result = mutableSetOf<String>()
        if (selectedDeformerId in allWarps) {
            result.add(selectedDeformerId)
        }
        // Collect all descendant warps of this deformer
        val targetDeformers = mutableSetOf(selectedDeformerId)
        var changed = true
        while (changed) {
            changed = false
            for (def in puppet.deformers) {
                val defId = def.id.raw
                if (defId !in targetDeformers) {
                    val p = parentOverrides[defId] ?: def.parent?.raw
                    if (p in targetDeformers) {
                        targetDeformers.add(defId)
                        if (defId in allWarps) {
                            result.add(defId)
                        }
                        changed = true
                    }
                }
            }
        }
        return result
    }

    // 3. Nothing selected
    return if (selectedOnly) emptySet() else allWarps
}

/** Keep legacy signature for backwards compatibility if needed */
internal fun informationWarpIds(model: PuppetModel, selected: String?, selectedOnly: Boolean): Set<String> {
    val warps = model.deformers.filterIsInstance<Deformer.Warp>()
    if (!selectedOnly || selected == null) return warps.map { it.id.raw }.toSet()
    if (warps.any { it.id.raw == selected }) return setOf(selected)
    val parents = model.deformers.associate { it.id.raw to it.parent?.raw }
    return warps.filter { warp ->
        var parent = warp.parent?.raw
        val seen = mutableSetOf<String>()
        while (parent != null && parent != selected && seen.add(parent)) parent = parents[parent]
        parent == selected
    }.map { it.id.raw }.toSet()
}
