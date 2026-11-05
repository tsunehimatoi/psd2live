package io.github.psd2live.core.modifier

import org.umamo.edit.VertexSource
import org.umamo.runtime.eval.warpControlPointsAt
import org.umamo.runtime.model.*

/** Reuse the mesh morph transport for a Warp's control-point inventory in its parent's coordinate space. */
internal object RigModifierDeformerBlends {
    fun warp(current: PuppetModel, previous: PuppetModel, desired: PuppetModel, actual: Deformer.Warp, before: Deformer.Warp,
             next: Deformer.Warp, result: Deformer.Warp, scratch: DrawableId, checkpoint: () -> Unit): List<BlendShapeBinding<WarpForm>> {
        if (actual.blendShapes.isEmpty() && before.blendShapes.isEmpty() && next.blendShapes.isEmpty()) return emptyList()
        fun synthetic(model: PuppetModel, warp: Deformer.Warp): PuppetModel {
            require(model.drawables.none { it.id == scratch }) { "Generated Warp morph scratch ID collision" }
            val defaults = model.parameters.associate { it.id to it.default }
            val points = requireNotNull(warpControlPointsAt(warp.geometryGrid) { defaults[it] ?: 0f })
            fun deltas(values: FloatArray): FloatArray {
                require(values.size == points.size) { "A generated Warp morph has stale lattice dimensions" }
                return FloatArray(values.size) { values[it] - points[it] }
            }
            val mesh = Drawable(scratch, warp.name, warp.parent, BlendMode.Normal, emptyList(), DrawableMesh(points, FloatArray(points.size), IntArray(0)),
                geometryGrid = warp.geometryGrid?.let { grid -> KeyformGrid(grid.axes, grid.cells.map { KeyformCell(it.coordinate, MeshDeltaForm(deltas(it.form.controlPoints))) }) },
                channelGrids = warp.channelGrids, opacity = warp.opacity, multiplyColor = warp.multiplyColor, screenColor = warp.screenColor,
                blendShapes = warp.blendShapes.map { binding -> BlendShapeBinding(binding.parameterId, binding.keys, binding.neutralIndex, binding.forms.map { form -> form?.let {
                    MeshForm(deltas(it.controlPoints), opacity = it.opacity, multiplyColor = it.multiplyColor, screenColor = it.screenColor)
                } }, binding.limits) })
            return model.copy(drawables = model.drawables + mesh)
        }
        val authored = synthetic(current, actual)
        val completed = synthetic(current, result)
        val old = synthetic(previous, before); val following = synthetic(desired, next)
        val points = requireNotNull(completed.drawables.last().mesh).positions
        val sources = List(points.size / 2) { VertexSource.FromOld(it) }
        return RigModifierMeshBlends.merge(authored, completed, old, following, scratch, sources, sources, checkpoint).map { binding ->
            BlendShapeBinding(binding.parameterId, binding.keys, binding.neutralIndex, binding.forms.map { form -> form?.let {
                WarpForm(FloatArray(points.size) { i -> points[i] + it.positionDeltas[i] }, it.opacity, it.multiplyColor, it.screenColor)
            } }, binding.limits)
        }
    }
}
