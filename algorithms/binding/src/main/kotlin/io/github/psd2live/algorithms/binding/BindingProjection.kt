package io.github.psd2live.algorithms.binding

import org.umamo.edit.withParametersDeleted
import org.umamo.runtime.keyform.*
import org.umamo.runtime.model.*

/** Projects a generated binding onto selected parameters without application or document services. */
object BindingProjection {
    /**
     * Removed axes are sampled at their defaults, overridden by captured [sourceDefaults] when the
     * source artwork represents a different pose. Unkeyed geometry retains its sampled rest form;
     * channels, blend shapes, parameter folders and links use the engine's ordered deletion rules.
     */
    fun retain(input: PuppetModel, kept: Set<ParameterId>, sourceDefaults: Map<ParameterId, Float> = emptyMap(),
               checkpoint: () -> Unit = {}): PuppetModel {
        checkpoint()
        require(sourceDefaults.values.all(Float::isFinite)) { "Non-finite binding source default" }
        var model = input.copy(parameters = input.parameters.map { parameter ->
            val value = sourceDefaults[parameter.id]
            if (value != null && parameter.id !in kept) parameter.copy(default = value.coerceIn(parameter.min, parameter.max))
            else parameter
        })
        val defaults = model.parameters.associate { it.id to it.default }
        val forms = model.deformers.associate { deformer ->
            checkpoint()
            deformer.id to when (deformer) {
                is Deformer.Warp -> deformer.copy(geometryGrid = restrict(deformer.geometryGrid, kept, defaults, WarpLatticeInterpolator, checkpoint))
                is Deformer.Rotation -> deformer.copy(geometryGrid = restrict(deformer.geometryGrid, kept, defaults, RotationPivotInterpolator, checkpoint))
            }
        }
        val meshes = model.drawables.associate {
            checkpoint()
            it.id to restrict(it.geometryGrid, kept, defaults, MeshDeltaInterpolator, checkpoint)
        }
        model = model.withParametersDeleted(model.parameters.filter { it.id !in kept }.map { it.id }, checkpoint)
        return model.copy(drawables = model.drawables.map { it.copy(geometryGrid = meshes[it.id]) },
            deformers = model.deformers.map { deformer -> when (deformer) {
                is Deformer.Warp -> deformer.copy(geometryGrid = (forms.getValue(deformer.id) as Deformer.Warp).geometryGrid)
                is Deformer.Rotation -> deformer.copy(geometryGrid = (forms.getValue(deformer.id) as Deformer.Rotation).geometryGrid)
            } })
    }

    private fun <F> restrict(grid: KeyformGrid<F>?, kept: Set<ParameterId>, defaults: Map<ParameterId, Float>,
                             interpolator: FormInterpolator<F>, checkpoint: () -> Unit): KeyformGrid<F>? {
        var current = grid ?: return null
        while (current.axes.any { it.parameterId !in kept }) {
            checkpoint()
            val axis = current.axes.first { it.parameterId !in kept }
            val value = (defaults[axis.parameterId] ?: 0f).coerceIn(axis.keys.first(), axis.keys.last())
            current = current.withKeyInserted(axis.parameterId, value, interpolator)
            if (current.axes.size == 1) {
                val index = current.axes.single().keys.indices.minBy { kotlin.math.abs(current.axes.single().keys[it] - value) }
                val form = current.cells.single { it.coordinate.single() == index }.form
                return KeyformGrid(emptyList(), listOf(KeyformCell(IntArray(0), form)))
            }
            current = requireNotNull(current.withAxisCollapsed(axis.parameterId, value))
        }
        return current
    }
}
