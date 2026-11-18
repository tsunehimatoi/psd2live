package io.github.psd2live.core.sim

import kotlin.math.abs
import kotlin.math.sqrt

/** Projects unsafe joint keyform poses onto local area constraints in the authored shape basis. */
internal class DirectBlendGeometry(
    private val scene: SimScene,
    private val rest: FloatArray,
    private val checkpoint: () -> Unit,
) {
    class Result(val offsets: List<List<FloatArray>>, val safe: Boolean)

    private class Term(val offset: FloatArray, val weight: Float, val mobility: Float)
    private class Pose(val terms: List<Term>) {
        val norm = terms.sumOf { (it.weight * it.weight * it.mobility).toDouble() }.toFloat()
    }
    private class Interval(val axis: Int, val low: Float, val high: Float)

    private val triangles = scene.solver.triangles
    private val area = FloatArray(triangles.size) { t ->
        signedArea(rest, triangles.a[t], triangles.b[t], triangles.c[t])
    }
    private val positioned = rest.copyOf()
    private val origin = rest.copyOf()
    private val deltaU = FloatArray(rest.size)
    private val deltaV = FloatArray(rest.size)
    private val minimum = DirectBlendArea.Minimum()
    private val welds = IntArray(rest.size / 2) { it }.also { parent ->
        fun root(vertex: Int): Int {
            var i = vertex
            while (parent[i] != i) i = parent[i]
            return i
        }
        val links = scene.solver.welds
        for (e in 0 until links.size) {
            if (links.weightA[e] <= 0f && links.weightB[e] <= 0f) continue
            val a = root(links.a[e])
            val b = root(links.b[e])
            parent[maxOf(a, b)] = minOf(a, b)
        }
        for (i in parent.indices) parent[i] = root(i)
    }
    private val peers = welds.indices.groupBy { welds[it] }.mapValues { (_, vertices) -> vertices.toIntArray() }
    private val movable = BooleanArray(welds.size) { true }.also { free ->
        for (vertex in welds.indices) if (scene.solver.pinWeight[vertex] >= 0.5f) free[welds[vertex]] = false
    }

    fun project(
        keys: List<FloatArray>,
        axes: List<List<FloatArray>>,
        reach: FloatArray,
        mobility: FloatArray = FloatArray(axes.size) { 1f },
    ): Result {
        val active = axes.indices.filter { reach[it] >= 0.10f }
        // Keep capsule contacts on the existing boundary checks. Area-gradient moves need not
        // point outwards at an obstacle that is attached to another deformer.
        if (scene.state.colliders.isNotEmpty()) return Result(axes, false)
        if (axes.isEmpty() || triangles.size == 0) return Result(axes, true)
        val result = axes.map { axis -> axis.map(FloatArray::copyOf) }
        val bounds = FloatArray(axes.size) { (reach[it] * 1.12f).coerceAtMost(1f) }
        val poses = samples(keys, result, active, bounds, mobility)
        val intervals = active.associateWith { axis ->
            val bound = bounds[axis]
            val selection = listOf(-bound) + keys[axis].filter { it > -bound && it < bound } + bound
            selection.zipWithNext { low, high -> Interval(axis, low, high) }
        }
        for (pass in 0..5) {
            val adjust = pass < 5
            val sampled = sweep(poses, adjust)
            val paired = sweepPairs(keys, result, intervals.values.toList(), mobility, adjust)
            if (sampled && paired) return Result(result, true)
        }
        return Result(result, false)
    }

    private fun samples(
        keys: List<FloatArray>,
        axes: List<List<FloatArray>>,
        active: List<Int>,
        bounds: FloatArray,
        mobility: FloatArray,
    ): List<Pose> = buildList {
        // Solo keyforms are also constraints once we start correcting joint poses.
        for (axis in axes.indices) {
            val positions = keys[axis]
            for (i in positions.indices) {
                if (positions[i] != 0f) add(Pose(terms(positions, axes[axis], positions[i], mobility[axis])))
                if (i > 0) add(Pose(terms(positions, axes[axis], (positions[i - 1] + positions[i]) * 0.5f, mobility[axis])))
            }
        }
        if (active.size > 2) {
            val shared = 0.86f * sqrt(2f / active.size)
            val signs = active.associateWith { axis ->
                val edge = bounds[axis] * shared
                listOf(terms(keys[axis], axes[axis], -edge, mobility[axis]), terms(keys[axis], axes[axis], edge, mobility[axis]))
            }
            for (bits in 0 until (1 shl active.size)) {
                add(Pose(active.flatMapIndexed { i, axis -> signs.getValue(axis)[bits shr i and 1] }))
            }
        }
    }

    private fun terms(keys: FloatArray, axis: List<FloatArray>, value: Float, mobility: Float): List<Term> {
        val upper = keys.indexOfFirst { it >= value }.coerceIn(1, keys.lastIndex)
        val lower = upper - 1
        val fraction = (value - keys[lower]) / (keys[upper] - keys[lower])
        return buildList {
            if (keys[lower] != 0f && fraction < 1f) add(Term(axis[lower], 1f - fraction, mobility))
            if (keys[upper] != 0f && fraction > 0f) add(Term(axis[upper], fraction, mobility))
        }
    }

    private fun sweepPairs(
        keys: List<FloatArray>,
        axes: List<List<FloatArray>>,
        intervals: List<List<Interval>>,
        mobility: FloatArray,
        adjust: Boolean,
    ): Boolean {
        var safe = true
        for (i in intervals.indices) for (j in i + 1 until intervals.size) {
            for (u in intervals[i]) for (v in intervals[j]) {
                checkpoint()
                fun at(interval: Interval, value: Float) = terms(keys[interval.axis], axes[interval.axis], value, mobility[interval.axis])
                val a = at(u, u.low)
                val b = at(v, v.low)
                rest.copyInto(origin)
                add(origin, a)
                add(origin, b)
                deltaU.fill(0f)
                deltaV.fill(0f)
                add(deltaU, at(u, u.high))
                add(deltaU, a, -1f)
                add(deltaV, at(v, v.high))
                add(deltaV, b, -1f)
                for (triangle in area.indices) {
                    val neutral = area[triangle]
                    if (abs(neutral) < 0.01f) continue
                    val first = triangles.a[triangle] * 2
                    val second = triangles.b[triangle] * 2
                    val third = triangles.c[triangle] * 2
                    val c00 = cross(origin, origin, first, second, third) / neutral
                    val c10 = (cross(origin, deltaU, first, second, third) + cross(deltaU, origin, first, second, third)) / neutral
                    val c01 = (cross(origin, deltaV, first, second, third) + cross(deltaV, origin, first, second, third)) / neutral
                    val c20 = cross(deltaU, deltaU, first, second, third) / neutral
                    val c11 = (cross(deltaU, deltaV, first, second, third) + cross(deltaV, deltaU, first, second, third)) / neutral
                    val c02 = cross(deltaV, deltaV, first, second, third) / neutral
                    val cornerU = c00 + c10 + c20
                    val cornerV = c00 + c01 + c02
                    val lowerBound = minOf(minOf(c00, cornerU), minOf(cornerV, cornerU + c01 + c11 + c02)) -
                        0.25f * (maxOf(0f, c20) + maxOf(0f, c02))
                    if (lowerBound >= 0.24f) continue
                    DirectBlendArea.minimum(c00, c10, c01, c20, c11, c02, minimum)
                    if (minimum.value >= 0.24f) continue
                    safe = false
                    if (!adjust) continue
                    val pose = Pose(at(u, u.low + (u.high - u.low) * minimum.u) + at(v, v.low + (v.high - v.low) * minimum.v))
                    if (pose.norm < 1e-8f) continue
                    setVertex(first, pose)
                    setVertex(second, pose)
                    setVertex(third, pose)
                    val current = signedArea(positioned, first / 2, second / 2, third / 2)
                    if (current / neutral < 0.24f) projectTriangle(pose, first / 2, second / 2, third / 2, 0.32f * neutral - current)
                }
            }
        }
        return safe
    }

    private fun setVertex(i: Int, pose: Pose) {
        var x = rest[i]
        var y = rest[i + 1]
        for (term in pose.terms) {
            x += term.offset[i] * term.weight
            y += term.offset[i + 1] * term.weight
        }
        positioned[i] = x
        positioned[i + 1] = y
    }

    private fun add(position: FloatArray, terms: List<Term>, gain: Float = 1f) {
        for (term in terms) {
            val weight = term.weight * gain
            for (i in position.indices) position[i] += term.offset[i] * weight
        }
    }

    private fun cross(a: FloatArray, b: FloatArray, i: Int, j: Int, k: Int) =
        (a[j] - a[i]) * (b[k + 1] - b[i + 1]) - (a[j + 1] - a[i + 1]) * (b[k] - b[i])

    private fun sweep(poses: List<Pose>, adjust: Boolean): Boolean {
        var safe = true
        for (pose in poses) {
            checkpoint()
            if (pose.norm < 1e-8f) continue
            rest.copyInto(positioned)
            add(positioned, pose.terms)
            for (t in area.indices) {
                val neutral = area[t]
                if (abs(neutral) < 0.01f) continue
                val a = triangles.a[t]
                val b = triangles.b[t]
                val c = triangles.c[t]
                val current = signedArea(positioned, a, b, c)
                if (current / neutral >= 0.24f) continue
                safe = false
                if (adjust) projectTriangle(pose, a, b, c, neutral * 0.32f - current)
            }
        }
        return safe
    }

    private fun projectTriangle(pose: Pose, a: Int, b: Int, c: Int, deficit: Float) {
        val ra = welds[a]
        val rb = welds[b]
        val rc = welds[c]
        if (ra == rb || ra == rc || rb == rc) return
        // The signed-area gradient is perpendicular to each vertex's opposite edge.
        val ax = positioned[b * 2 + 1] - positioned[c * 2 + 1]
        val ay = positioned[c * 2] - positioned[b * 2]
        val bx = positioned[c * 2 + 1] - positioned[a * 2 + 1]
        val by = positioned[a * 2] - positioned[c * 2]
        val cx = -ax - bx
        val cy = -ay - by
        val aw = if (movable[ra]) 1f / peers.getValue(ra).size else 0f
        val bw = if (movable[rb]) 1f / peers.getValue(rb).size else 0f
        val cw = if (movable[rc]) 1f / peers.getValue(rc).size else 0f
        val denominator = aw * (ax * ax + ay * ay) + bw * (bx * bx + by * by) + cw * (cx * cx + cy * cy)
        if (denominator < 1e-6f) return
        val step = deficit / denominator
        shift(pose, ra, step * aw * ax, step * aw * ay)
        shift(pose, rb, step * bw * bx, step * bw * by)
        shift(pose, rc, step * cw * cx, step * cw * cy)
    }

    private fun shift(pose: Pose, root: Int, dx: Float, dy: Float) {
        if (dx == 0f && dy == 0f) return
        for (vertex in peers.getValue(root)) {
            val i = vertex * 2
            positioned[i] += dx
            positioned[i + 1] += dy
            for (term in pose.terms) {
                val scale = term.weight * term.mobility / pose.norm
                term.offset[i] += dx * scale
                term.offset[i + 1] += dy * scale
            }
        }
    }

    private fun signedArea(p: FloatArray, a: Int, b: Int, c: Int) =
        (p[b * 2] - p[a * 2]) * (p[c * 2 + 1] - p[a * 2 + 1]) -
            (p[b * 2 + 1] - p[a * 2 + 1]) * (p[c * 2] - p[a * 2])
}
