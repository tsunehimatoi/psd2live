package io.github.psd2live.core.sim

import org.umamo.render.eval.drawableLocalPosed
import org.umamo.render.eval.drawableSpaceMapping
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The targets' keyform spaces: residuals in local coordinates, and the default-pose Jacobian that
 * turns them into px so modes are measured the same way everywhere on the body.
 */
class SimSpace(val model: PuppetModel, scene: SimScene) {
    val offsets: Map<DrawableId, Int> = scene.offsets
    val vertexCounts: Map<DrawableId, Int> = scene.vertexCounts
    val meshes: List<DrawableId> = offsets.keys.toList()
    val size = scene.state.count * 2
    val counts: Map<String, Int> = meshes.associate { it.raw to vertexCounts.getValue(it) }
    /** Where the body rests at the default pose, world space (x, y per particle). */
    val rest: FloatArray = scene.state.positions()
    /** The edges along the body's grain, as particle pairs, and their rest lengths: what a strand's length is read on. */
    val grain: List<Pair<Int, Int>>
    val grainRest: FloatArray
    /** Per particle: d world / d local at the default pose, column-major (xx, yx, xy, yy). */
    private val jacobian = FloatArray(scene.state.count * 4)
    private val inverse = FloatArray(scene.state.count * 4)

    init {
        val stretch = scene.solver.stretch
        val along = (0 until stretch.size).filter { scene.edgeAlong[it] > 0.5f }
        grain = along.map { stretch.a[it] to stretch.b[it] }
        grainRest = FloatArray(along.size) { stretch.rest[along[it]] }
        for (id in meshes) {
            val offset = offsets.getValue(id)
            val mapping = drawableSpaceMapping(model, emptyMap(), id)
            val local = drawableLocalPosed(model, emptyMap(), id)
            val count = vertexCounts.getValue(id)
            for (v in 0 until count) { val i = (offset + v) * 4; jacobian[i] = 1f; jacobian[i + 3] = 1f }
            if (mapping == null || local == null) continue
            var extent = 0f
            for (k in local.indices step 2) extent = maxOf(extent, abs(local[k] - local[0]), abs(local[k + 1] - local[1]))
            val eps = (extent * 1e-3f).coerceAtLeast(1e-6f)
            val base = mapping.localToWorld(local)
            val dx = mapping.localToWorld(FloatArray(local.size) { if (it % 2 == 0) local[it] + eps else local[it] })
            val dy = mapping.localToWorld(FloatArray(local.size) { if (it % 2 == 1) local[it] + eps else local[it] })
            for (v in 0 until count) {
                val i = (offset + v) * 4
                jacobian[i] = (dx[v * 2] - base[v * 2]) / eps; jacobian[i + 1] = (dx[v * 2 + 1] - base[v * 2 + 1]) / eps
                jacobian[i + 2] = (dy[v * 2] - base[v * 2]) / eps; jacobian[i + 3] = (dy[v * 2 + 1] - base[v * 2 + 1]) / eps
            }
        }
        for (p in 0 until scene.state.count) {
            val i = p * 4
            val det = jacobian[i] * jacobian[i + 3] - jacobian[i + 2] * jacobian[i + 1]
            if (abs(det) < 1e-12f) { inverse[i] = 1f; inverse[i + 3] = 1f; continue }
            inverse[i] = jacobian[i + 3] / det; inverse[i + 1] = -jacobian[i + 1] / det
            inverse[i + 2] = -jacobian[i + 2] / det; inverse[i + 3] = jacobian[i] / det
        }
    }

    /** Local deltas in px at the default pose. */
    fun toPx(local: FloatArray) = FloatArray(size) { k ->
        val p = k / 2; val i = p * 4; val x = local[p * 2]; val y = local[p * 2 + 1]
        if (k % 2 == 0) jacobian[i] * x + jacobian[i + 2] * y else jacobian[i + 1] * x + jacobian[i + 3] * y
    }

    /** The largest single-vertex distance of [local] in px. */
    fun motionPx(local: FloatArray): Float = SimBaker.maxDistance(toPx(local))

    /** Where [scene] has the body at [pose], minus where the rig has it, in local coordinates. */
    fun residual(scene: SimScene, pose: Map<ParameterId, Float>): FloatArray? {
        val out = FloatArray(size)
        val s = scene.state
        for (id in meshes) {
            val offset = offsets.getValue(id)
            val count = vertexCounts.getValue(id)
            val mapping = drawableSpaceMapping(model, pose, id) ?: return null
            val seed = drawableLocalPosed(model, pose, id) ?: return null
            val all = (0 until count).toSet()
            val simulated = FloatArray(count * 2) { if (it % 2 == 0) s.x[offset + it / 2] else s.y[offset + it / 2] }
            val rigged = FloatArray(count * 2) { if (it % 2 == 0) s.goalX[offset + it / 2] else s.goalY[offset + it / 2] }
            val a = mapping.worldToLocal(simulated, seed, all)
            val b = mapping.worldToLocal(rigged, seed, all)
            for (k in 0 until count * 2) out[offset * 2 + k] = a[k] - b[k]
        }
        return out
    }

    /**
     * Where [scene] has the body minus where its goals are, turned back by the body's [angle] and taken into
     * local coordinates through the default pose's Jacobian: the residual of a body whose rig is moved rigidly
     * (turned by [angle] about its root, carried), where the rig's pose is the default one turned and carried.
     */
    fun residualRigid(scene: SimScene, angle: Float): FloatArray {
        val out = FloatArray(size)
        val s = scene.state
        val c = cos(-angle); val sn = sin(-angle)
        for (p in 0 until s.count) {
            val wx = s.x[p] - s.goalX[p]; val wy = s.y[p] - s.goalY[p]
            val dx = c * wx - sn * wy; val dy = sn * wx + c * wy
            val i = p * 4
            out[p * 2] = inverse[i] * dx + inverse[i + 2] * dy
            out[p * 2 + 1] = inverse[i + 1] * dx + inverse[i + 3] * dy
        }
        return out
    }

    /** Poses the rig, lets [scene] settle there and returns what is left against the rig. */
    fun settle(scene: SimScene, pose: Map<ParameterId, Float>, dt: Float): FloatArray {
        val s = scene.state
        for (i in 0 until s.count) s.damping[i] = maxOf(s.damping[i], 8f)
        scene.reset(model, pose)
        repeat((SETTLE_SECONDS / dt).toInt()) { scene.drive(model, pose, dt) }
        return residual(scene, pose) ?: FloatArray(size)
    }

    /** [r] minus [axis]'s offsets at [value]. */
    fun subtract(r: FloatArray, axis: SimBakedAxis, value: Float) {
        for (id in meshes) {
            val offsets = axis.at(id.raw, value) ?: continue
            val offset = this.offsets.getValue(id) * 2
            for (k in offsets.indices) r[offset + k] -= offsets[k]
        }
    }

    /** Per-key arrays over every particle split into per-mesh arrays. */
    fun split(perKey: List<FloatArray>): Map<String, List<FloatArray>> = meshes.associate { id ->
        val offset = offsets.getValue(id) * 2
        val count = vertexCounts.getValue(id) * 2
        id.raw to perKey.map { it.copyOfRange(offset, offset + count) }
    }

    /** [axis]'s offsets at [value] over every particle. */
    fun join(axis: SimBakedAxis, value: Float): FloatArray {
        val out = FloatArray(size)
        for (id in meshes) axis.at(id.raw, value)?.copyInto(out, offsets.getValue(id) * 2)
        return out
    }

    private companion object {
        const val SETTLE_SECONDS = 2.5f
    }
}
