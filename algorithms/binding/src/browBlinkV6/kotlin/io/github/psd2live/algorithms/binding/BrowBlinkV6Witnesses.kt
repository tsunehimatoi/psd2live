package io.github.psd2live.algorithms.binding

import io.github.psd2live.format.model.KeyAxis
import io.github.psd2live.format.model.Parameter

/** Validate ordinary eye axes without assuming a lowered carrier retains every source-mesh knot. */
object BrowBlinkV6Witnesses {
    fun check(parameter: Parameter, meshAxes: List<KeyAxis>, carrierAxes: List<KeyAxis>) {
        val meshes = meshAxes.filter { it.parameter == parameter.id }
        val carriers = carrierAxes.filter { it.parameter == parameter.id }
        fun bounded(axis: KeyAxis): Boolean {
            val keys = axis.keys.toArray()
            return keys.size >= 2 && keys.all { it.isFinite() } && keys.first() == parameter.min &&
                keys.last() == parameter.max && keys.toList().zipWithNext().all { (a, b) -> a < b }
        }
        // Source eye grids retain their open/default knot. BlendShapeLowering.mergedAxes instead
        // builds new carrier axes from binding keys, limit knots and parameter endpoints. A lid8
        // coupling carrier can therefore key eye-open through a limit without a key at default;
        // evaluation interpolates that pose. Do not synthesize a knot or alter the live witness.
        require(!parameter.blend && (meshes.isNotEmpty() || carriers.isNotEmpty()) &&
            meshes.all { bounded(it) && it.keys.toArray().any { key -> key == parameter.default } } && carriers.all(::bounded)) {
            "Brow blink v6 requires an unedited ordinary eye witness: ${parameter.id}; " +
                "mesh keys=${meshes.map { it.keys.toArray().toList() }}; carrier keys=${carriers.map { it.keys.toArray().toList() }}"
        }
    }
}
