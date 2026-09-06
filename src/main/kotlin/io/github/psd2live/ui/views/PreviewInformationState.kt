package io.github.psd2live.ui.views

import io.github.psd2live.core.CubismSdkFrame
import org.umamo.runtime.model.*

internal fun informationPreviewPose(values: Map<ParameterId, Float>, frame: CubismSdkFrame?, animated: Boolean): Map<ParameterId, Float> =
    if(animated && frame?.animationEnabled == true) frame.parameters else values

/** Selecting a rotation displays its descendant Warp geometry, not an empty information layer. */
internal fun informationWarpIds(model: PuppetModel, selected: String?, selectedOnly: Boolean): Set<String> {
    val warps=model.deformers.filterIsInstance<Deformer.Warp>()
    if(!selectedOnly || selected==null)return warps.map { it.id.raw }.toSet()
    if(warps.any { it.id.raw==selected })return setOf(selected)
    val parents=model.deformers.associate { it.id.raw to it.parent?.raw }
    return warps.filter { warp ->
        var parent=warp.parent?.raw
        val seen=mutableSetOf<String>()
        while(parent!=null && parent!=selected && seen.add(parent))parent=parents[parent]
        parent==selected
    }.map { it.id.raw }.toSet()
}
