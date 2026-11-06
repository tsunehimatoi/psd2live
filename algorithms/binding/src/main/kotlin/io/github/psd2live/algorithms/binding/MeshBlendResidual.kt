package io.github.psd2live.algorithms.binding

import org.umamo.edit.VertexSource
import org.umamo.render.eval.drawableSpaceMapping
import org.umamo.runtime.eval.colorAt
import org.umamo.runtime.eval.meshGridDefaultDeltas
import org.umamo.runtime.eval.scalarAt
import org.umamo.runtime.model.*

/** Merge independent morph contributions in the actual neutral frame, retaining native weight-limit curves. */
object MeshBlendResidual {
    private data class Key(val parameter: ParameterId, val keys: List<Float>, val neutral: Int, val limits: List<BlendWeightLimit>)
    private fun key(binding: BlendShapeBinding<MeshForm>) = Key(binding.parameterId, binding.keys.toList(), binding.neutralIndex, binding.limits)

    private class Reference(val model: PuppetModel, val mesh: Drawable) {
        private val defaults = model.parameters.associate { it.id to it.default }
        val deltas = meshGridDefaultDeltas(mesh) { defaults[it] ?: 0f } ?: FloatArray(requireNotNull(mesh.mesh).positions.size)
        val positions = requireNotNull(mesh.mesh).positions.let { points -> FloatArray(points.size) { points[it] + deltas[it] } }
        val order = mesh.channelGrids.scalarAt(FormChannel.DRAW_ORDER, mesh.drawOrder, paramValue = { defaults[it] ?: 0f })
        val opacity = mesh.channelGrids.scalarAt(FormChannel.OPACITY, mesh.opacity, paramValue = { defaults[it] ?: 0f })
        val multiply = mesh.channelGrids.colorAt(FormChannel.MULTIPLY_COLOR, mesh.multiplyColor, paramValue = { defaults[it] ?: 0f })
        val screen = mesh.channelGrids.colorAt(FormChannel.SCREEN_COLOR, mesh.screenColor, paramValue = { defaults[it] ?: 0f })
        val mapping by lazy { requireNotNull(drawableSpaceMapping(model, emptyMap(), mesh.id)) { "A blend shape's neutral parent cannot be evaluated" } }
    }

    fun merge(before: PuppetModel, after: PuppetModel, previous: PuppetModel, desired: PuppetModel, id: DrawableId,
              previousSources: List<VertexSource>, desiredSources: List<VertexSource>, checkpoint: () -> Unit): List<BlendShapeBinding<MeshForm>> {
        val actual = before.drawables.single { it.id == id }
        val old = previous.drawables.single { it.id == id }
        val next = desired.drawables.single { it.id == id }
        if (actual.blendShapes.isEmpty() && old.blendShapes.isEmpty() && next.blendShapes.isEmpty()) return emptyList()
        val a = Reference(before, actual); val b = Reference(previous, old); val c = Reference(desired, next)
        val result = Reference(after, after.drawables.single { it.id == id })
        val live = actual.blendShapes.groupBy(::key); val prior = old.blendShapes.groupBy(::key); val following = next.blendShapes.groupBy(::key)
        val keys = live.keys + prior.keys + following.keys
        val indices = (0 until result.positions.size / 2).toSet()
        val neutralWorld by lazy { result.mapping.localToWorld(result.positions) }
        fun projected(reference: Reference, form: MeshForm, sources: List<VertexSource>): FloatArray {
            checkpoint()
            val mesh = requireNotNull(reference.mesh.mesh)
            require(form.positionDeltas.size == mesh.positions.size) { "A generated blend shape has stale topology" }
            val moved = reference.mapping.localToWorld(FloatArray(mesh.positions.size) { mesh.positions[it] + form.positionDeltas[it] })
            val base = reference.mapping.localToWorld(reference.positions)
            val worldDelta = BindingVertices.transfer(FloatArray(base.size) { moved[it] - base[it] }, sources)
            val shifted = FloatArray(neutralWorld.size) { neutralWorld[it] + worldDelta[it] }
            val local = result.mapping.worldToLocalLinearized(shifted, result.positions, shifted, indices)
            require(local.all(Float::isFinite)) { "A generated blend shape cannot be mapped into its authored parent" }
            val reached = result.mapping.localToWorld(local)
            require(reached.indices.all { kotlin.math.abs(reached[it] - shifted[it]) <= .05f }) { "A generated blend shape cannot invert its authored neutral frame" }
            return FloatArray(local.size) { local[it] - result.positions[it] }
        }
        fun difference(value: Float, reference: Float) = if (value.isFinite()) value - reference else 0f
        return keys.mapNotNull { key ->
            checkpoint()
            require(key.keys.size >= 2 && key.neutral in key.keys.indices) { "A blend shape needs its neutral key" }
            val owned = live[key].orEmpty(); val earlier = prior[key].orEmpty(); val added = following[key].orEmpty()
            val forms = key.keys.indices.map { index ->
                if (index == key.neutral || (owned + earlier + added).all { it.forms[index] == null }) null else {
                    val delta = FloatArray(result.positions.size)
                    var order = 0f; var opacity = 0f
                    val multiply = FloatArray(3); val screen = FloatArray(3)
                    fun add(bindings: List<BlendShapeBinding<MeshForm>>, reference: Reference, sign: Float, sources: List<VertexSource>?) {
                        for (binding in bindings) {
                            val form = binding.forms[index] ?: continue
                            val values = if (sources == null) FloatArray(delta.size) { form.positionDeltas[it] - reference.deltas[it] }
                                else projected(reference, form, sources)
                            for (i in delta.indices) delta[i] += sign * values[i]
                            order += sign * difference(form.drawOrder, reference.order)
                            opacity += sign * difference(form.opacity, reference.opacity)
                            fun color(to: FloatArray, value: ColorRgb, ref: ColorRgb) {
                                to[0] += sign * difference(value.red, ref.red); to[1] += sign * difference(value.green, ref.green); to[2] += sign * difference(value.blue, ref.blue)
                            }
                            color(multiply, form.multiplyColor, reference.multiply); color(screen, form.screenColor, reference.screen)
                        }
                    }
                    add(owned, a, 1f, null); add(earlier, b, -1f, previousSources); add(added, c, 1f, desiredSources)
                    fun color(base: ColorRgb, delta: FloatArray) = ColorRgb(base.red + delta[0], base.green + delta[1], base.blue + delta[2])
                    MeshForm(FloatArray(delta.size) { result.deltas[it] + delta[it] }, result.order + order, result.opacity + opacity,
                        color(result.multiply, multiply), color(result.screen, screen))
                }
            }
            if (forms.all { it == null }) null else BlendShapeBinding(key.parameter, key.keys.toFloatArray(), key.neutral, forms, key.limits)
        }
    }
}
