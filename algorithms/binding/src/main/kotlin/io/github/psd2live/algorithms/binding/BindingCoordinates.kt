package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.*
import io.github.psd2live.targets.cubism.PuppetIr
import org.umamo.render.eval.DrawableSpaceMapping
import org.umamo.render.eval.drawableSpaceMapping
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.ParameterId
import kotlin.math.abs

/** Captured parent-space mapping. Public points use canvas pixels, Y down, never renderer world coordinates. */
class BindingCoordinates private constructor(private val mapping: DrawableSpaceMapping, private val checkpoint: () -> Unit) {
    private fun check(points: FloatArray) {
        checkpoint()
        require(points.size % 2 == 0 && points.all(Float::isFinite)) { "Expected finite XY pairs" }
    }

    fun toCanvas(local: FloatArray): FloatArray {
        check(local)
        return mapping.localToWorld(local).also { points ->
            for (i in 1 until points.size step 2) points[i] = -points[i]
            check(points)
        }
    }

    fun fromCanvas(canvas: FloatArray, seed: FloatArray = FloatArray(canvas.size) { .5f }): FloatArray {
        check(canvas); check(seed)
        require(seed.size == canvas.size) { "Inverse seed size differs from points" }
        val world = canvas.copyOf().also { for (i in 1 until it.size step 2) it[i] = -it[i] }
        val local = mapping.worldToLocalLinearized(world, seed, world, (0 until canvas.size / 2).toSet())
        check(local)
        val reached = toCanvas(local)
        require(reached.indices.all { abs(reached[it] - canvas[it]) < .05f }) { "Parent cannot reproduce canvas points" }
        return local
    }

    companion object {
        fun forMesh(input: ModifierEvaluationInput, meshId: String, pose: Map<String, Float> = emptyMap(),
                    checkpoint: () -> Unit = {}): BindingCoordinates {
            checkpoint()
            require(pose.values.all(Float::isFinite)) { "Pose must be finite" }
            val mesh = requireNotNull(input.mesh(meshId)) { "Missing mesh: $meshId" }
            val parents = input.parentsOfMesh(meshId)
            val model = PuppetIr.toPuppet(frame(input, mesh, parents))
            val mapping = requireNotNull(drawableSpaceMapping(model, pose.mapKeys { ParameterId(it.key) }, DrawableId(meshId))) {
                "Parent cannot place mesh: $meshId"
            }
            checkpoint()
            return BindingCoordinates(mapping, checkpoint)
        }

        /** A complete geometry edit at the default pose, preserving authored channels and non-geometry metadata. */
        fun reparent(input: ModifierEvaluationInput, meshId: String, parent: String?,
                     checkpoint: () -> Unit = {}): ModifierModelChanges {
            checkpoint()
            val mesh = requireNotNull(input.mesh(meshId)) { "Missing mesh: $meshId" }
            val parents = LinkedHashMap<String, Deformer>()
            input.parentsOfMesh(meshId).forEach { parents[it.id] = it }
            val seen = HashSet<String>()
            var id = parent
            while (id != null) {
                checkpoint()
                require(seen.add(id)) { "Deformer cycle at $id" }
                val node = requireNotNull(input.deformer(id)) { "Missing parent deformer: $id" }
                parents[id] = node
                id = node.parent
            }
            if (mesh.parent == parent) return ModifierModelChanges(meshes = ModifierObjectChanges(reused = listOf(mesh)))
            val before = PuppetIr.toPuppet(frame(input, mesh, parents.values.toList()))
            val after = before.copy(drawables = before.drawables.map { it.copy(parentDeformerId = parent?.let { id -> org.umamo.runtime.model.DeformerId(id) }) })
            val carried = PuppetIr.toIr(BindingFrames.carriedToParent(before, after, DrawableId(meshId), checkpoint)).meshes.single()
            val updated = mesh.copy(parent = parent, geometry = carried.geometry, offsets = carried.offsets, shapes = carried.shapes)
            return ModifierModelChanges(meshes = ModifierObjectChanges(updated = listOf(ModifierObjectUpdate(mesh, updated))))
        }

        /** Geometry-only submodel: each parent and parameter read is recorded, unrelated meshes are never converted. */
        private fun frame(input: ModifierEvaluationInput, mesh: Mesh, parents: List<Deformer>): RigIR {
            val parameterIds = linkedSetOf<String>()
            fun grid(grid: KeyGrid<*>?) { grid?.axes?.forEach { parameterIds += it.parameter } }
            fun shapes(shapes: List<BlendBinding<*>>) { shapes.forEach { shape ->
                parameterIds += shape.parameter
                shape.limits.forEach { parameterIds += it.parameter }
            } }
            for (node in parents) {
                node.channels.values.forEach(::grid)
                when (node) {
                    is Deformer.Warp -> { grid(node.lattice); shapes(node.shapes) }
                    is Deformer.Rotation -> { grid(node.pivot); shapes(node.shapes) }
                }
            }
            val parameters = parameterIds.map { requireNotNull(input.parameter(it)) { "Missing parent parameter: $it" } }
            return RigIR(input.canvas, parameters, deformers = parents, meshes = listOf(mesh))
        }
    }
}
