package io.github.psd2live.core

import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.*

/** A parent's control points are insufficient evidence that nested rotations and blend shapes keep their artwork. */
internal object WarpChildPreservation {
    fun verify(before: PuppetModel, after: PuppetModel, root: DeformerId, key: Map<String, Float>, toleranceSquared: Float) {
        val descendants = linkedSetOf(root)
        while (true) {
            val added = before.deformers.filter { it.parent in descendants }.map { it.id }.filterNot { it in descendants }
            if (added.isEmpty()) break
            descendants += added
        }
        val drawables = before.drawables.filter { it.parentDeformerId in descendants && it.mesh != null }
        if (drawables.isEmpty()) return
        val values = linkedMapOf<String, MutableSet<Float>>()
        val parameters = before.parameters.associateBy { it.id.raw }
        fun axis(id: String, keys: List<Float>) {
            if (id !in key) values.getOrPut(id) { sortedSetOf() }.addAll(keys)
        }
        fun grid(axes: List<KeyformAxis>) = axes.forEach { axis(it.parameterId.raw, it.keys.toList()) }
        fun blends(bindings: List<BlendShapeBinding<*>>) = bindings.forEach { binding ->
            axis(binding.parameterId.raw, binding.keys.toList())
            binding.limits.forEach { limit -> axis(limit.parameterId.raw, limit.points.map { it.value }) }
            (listOf(binding.parameterId) + binding.limits.map { it.parameterId }).forEach { id ->
                parameters[id.raw]?.let { axis(id.raw, listOf(it.min, it.default, it.max)) }
            }
        }
        for (model in listOf(before, after)) {
            model.deformers.filter { it.id in descendants && it.id != root }.forEach { deformer ->
                when (deformer) {
                    is Deformer.Warp -> { grid(deformer.geometryGrid?.axes.orEmpty()); blends(deformer.blendShapes) }
                    is Deformer.Rotation -> { grid(deformer.geometryGrid?.axes.orEmpty()); blends(deformer.blendShapes) }
                }
            }
            model.drawables.filter { item -> drawables.any { it.id == item.id } }.forEach {
                grid(it.geometryGrid?.axes.orEmpty()); blends(it.blendShapes)
            }
        }
        val poses = values.entries.fold(listOf(key)) { poses, (id, keys) ->
            val ordered = keys.sorted()
            val samples = (ordered + ordered.zipWithNext { a, b -> (a + b) * .5f }).sorted()
            require(poses.size.toLong() * samples.size <= 16384) { "Keeping nested child appearance requires too many poses; simplify the child parameter grid first" }
            poses.flatMap { pose -> samples.map { pose + (id to it) } }
        }
        val evaluator = CpuDeformationEvaluator()
        for (pose in poses) {
            val parameters = pose.mapKeys { ParameterId(it.key) }
            val original = evaluator.evaluate(before, parameters).worldPositions
            val result = evaluator.evaluate(after, parameters).worldPositions
            for (drawable in drawables) {
                val points = original.getValue(drawable.id)
                val adjusted = result.getValue(drawable.id)
                require(points.size == adjusted.size && points.indices.step(2).all { i ->
                    val dx = points[i] - adjusted[i]; val dy = points[i + 1] - adjusted[i + 1]
                    dx.isFinite() && dy.isFinite() && dx * dx + dy * dy <= toleranceSquared
                }) { "Cannot preserve nested child ${drawable.id.raw} under this warp edit; reduce the deformation or edit without keeping child appearance" }
            }
        }
    }
}
