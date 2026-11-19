package io.github.psd2live.core

import org.umamo.runtime.eval.EPS_KEY
import org.umamo.runtime.eval.EPS_SPAN
import kotlinx.serialization.json.*
import org.umamo.runtime.model.*
import kotlin.math.abs

/** Geometry editing addresses sampled cells, independently of opacity and the parameter panel's stops. */
internal object GeometryKeyforms {
    fun axes(model: PuppetModel, kind: String, id: String): List<KeyformAxis>? = when (kind) {
        "mesh" -> model.drawables.firstOrNull { it.id.raw == id && it.mesh != null }?.let { it.geometryGrid?.axes.orEmpty() }
        "warp" -> (model.deformers.firstOrNull { it.id.raw == id } as? Deformer.Warp)?.geometryGrid?.axes
        else -> null
    }

    /** Explicit stops can be sculpted even before this object's geometry has a cell at that value. */
    fun editingAxes(model: PuppetModel, kind: String, id: String): List<KeyformAxis> = axes(model, kind, id).orEmpty().map { axis ->
        val keys = (axis.keys.toList() + ParameterKeyEdits.pointValues(model, axis.parameterId)).sorted()
            .fold(ArrayList<Float>()) { result, key ->
                if (result.isEmpty() || abs(result.last() - key) >= EPS_KEY) result += key
                result
            }
        KeyformAxis(axis.parameterId, keys.toFloatArray())
    }

    /** A closed drawing can have an opacity track before it has any geometry keyed to Mouth Open.
     * Replicate its current forms at the parameter stops using the ordinary geometry-track edit. */
    fun bindMissingAxes(model: PuppetModel, kind: String, id: String, key: Map<String, Float>): PuppetModel {
        val axes = axes(model, kind, id)?.map { it.parameterId.raw } ?: return model
        if (!key.keys.containsAll(axes)) return model
        var current = model
        for (name in key.keys - axes.toSet()) {
            val parameter = model.parameters.singleOrNull { it.id.raw == name && it.kind == ParameterKind.NORMAL } ?: return model
            val stops = (ParameterKeyEdits.pointValues(model, parameter.id) + parameter.default).sorted()
            // Keep the initial axis spanning the range. Subsequent keys use the same limits and
            // interpolation as keys inserted from the editor.
            val ends = listOf(parameter.min, parameter.max)
            val interior = stops.filter { it - parameter.min >= EPS_SPAN && parameter.max - it >= EPS_SPAN }
                .fold(mutableListOf<Float>()) { keys, value ->
                    if (keys.isEmpty() || value - keys.last() >= EPS_SPAN) keys.add(value)
                    keys
                }
            for (values in listOf(ends) + interior.chunked(128)) {
                current = ParameterKeyEdits.apply(current, buildJsonObject {
                    put("target", "$kind:$id"); put("track", "geometry"); put("parameter", name)
                    put("action", "add"); put("values", JsonArray(values.map(::JsonPrimitive)))
                })
            }
        }
        return current
    }
}
