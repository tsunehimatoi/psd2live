package io.github.psd2live.core.sim

import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * How each input moves a simulated body, read off the rig alone at a few values over the input's training span: how
 * it carries and turns the body's root (its pinned particles as one, by Procrustes about their rest centroid, or
 * the whole goal field when nothing is pinned), and how much it reshapes the body besides. A body answers only the
 * motion of its root when the rig moves it rigidly, so inputs that do drive it through a few shared channels; one
 * that reshapes it (a head turn's parallax, a lean) is a channel of its own, and one that hardly moves it none.
 */
internal object SimKinematics {
    /** Samples over each side of an input's span. */
    private const val SIDE = 4
    /** Below this much motion (px) an input does not move the body. */
    const val MIN_MOTION_PX = 1f
    /** An input reshapes the body when the goals end up farther than this share of its motion from where carrying the root alone puts them. */
    private const val RESHAPE_SHARE = 0.2f
    private const val RESHAPE_PX = 1f

    class Input(
        val parameter: Parameter,
        val low: Float,
        val rest: Float,
        val high: Float,
        /** The sampled values, ascending, and the root's travel (px, x and y) and turn (rad) at each. */
        private val values: FloatArray,
        private val roots: Array<FloatArray>,
        /** The largest distance (px) the input moves any goal over its span. */
        val motion: Float,
        /** How far (px, 95th percentile) the goals end up from where carrying the root alone would put them, at the worst value. */
        val reshape: Float,
        /** Whether the goals move more sideways than up and down. */
        val sideways: Boolean,
    ) {
        val id: String get() = parameter.id.raw
        val moves: Boolean get() = motion >= MIN_MOTION_PX
        /** Whether carrying and turning the root is all the input does to the body. */
        val rigid: Boolean get() = reshape <= maxOf(RESHAPE_SHARE * motion, RESHAPE_PX)
        /** The farther side of the span from rest. */
        val reach: Float get() = maxOf(high - rest, rest - low)
        /** Half the parameter's whole range, which Cubism normalizes it over. */
        val halfRange: Float get() = (parameter.max - parameter.min) / 2f

        /** Root travel x, y (px) and turn (rad) per unit of the parameter, the least-squares line through rest. */
        fun slope(axis: Int): Float {
            var num = 0.0; var den = 0.0
            for (k in values.indices) { val u = (values[k] - rest).toDouble(); num += u * roots[k][axis]; den += u * u }
            return if (den == 0.0) 0f else (num / den).toFloat()
        }

        /** The root's travel x, y and turn at [value], linear between samples and held past the ends. */
        fun root(value: Float, axis: Int): Float {
            if (value <= values.first()) return roots.first()[axis]
            if (value >= values.last()) return roots.last()[axis]
            val j = (0 until values.size - 1).first { value <= values[it + 1] }
            val t = (value - values[j]) / (values[j + 1] - values[j])
            return roots[j][axis] + (roots[j + 1][axis] - roots[j][axis]) * t
        }
    }

    /** The inputs as the rig moves the body, the body's length from its root (px) and the root's rest centroid. */
    class Analysis(val inputs: List<Input>, val length: Float, val centerX: Float, val centerY: Float, val root: IntArray, val pinned: Boolean)

    const val X = 0
    const val Y = 1
    const val TURN = 2

    /** [inputs] on bodies of [model] from [body], each reset at the input's sampled values; inputs side by side when [parallel]. */
    fun analyze(model: PuppetModel, edit: RigSimEdit, body: () -> SimScene, inputs: List<Parameter>, parallel: Boolean = true): Analysis {
        val scene = body()
        val s = scene.state
        val n = s.count
        scene.reset(model, emptyMap())
        val pinned = (0 until n).filter { scene.solver.pinWeight[it] >= 0.5f }
        val useAnchors = pinned.size >= 2
        val root = (if (useAnchors) pinned else (0 until n).toList()).toIntArray()
        fun rootX(i: Int) = if (useAnchors) s.anchorX[i] else s.goalX[i]
        fun rootY(i: Int) = if (useAnchors) s.anchorY[i] else s.goalY[i]
        val restRoot = FloatArray(root.size * 2) { if (it % 2 == 0) rootX(root[it / 2]) else rootY(root[it / 2]) }
        val restGoal = FloatArray(n * 2) { if (it % 2 == 0) s.goalX[it / 2] else s.goalY[it / 2] }
        val cx0 = (root.indices.sumOf { restRoot[it * 2].toDouble() } / root.size).toFloat()
        val cy0 = (root.indices.sumOf { restRoot[it * 2 + 1].toDouble() } / root.size).toFloat()
        val length = (0 until n).maxOfOrNull { hypot(restGoal[it * 2] - cx0, restGoal[it * 2 + 1] - cy0) } ?: 0f

        fun analyzed(parameter: Parameter): Input {
            val scene = body()
            val s = scene.state
            fun rootX(i: Int) = if (useAnchors) s.anchorX[i] else s.goalX[i]
            fun rootY(i: Int) = if (useAnchors) s.anchorY[i] else s.goalY[i]
            val (low, rest, high) = SimBaker.trainingSpan(parameter, edit.inputRanges[parameter.id.raw])
            val values = (List(SIDE) { low + (rest - low) * it / SIDE } + rest + List(SIDE) { rest + (high - rest) * (it + 1) / SIDE })
                .distinct().toFloatArray()
            var motion = 0f; var reshape = 0f; var across = 0.0; var along = 0.0
            val roots = values.map { u ->
                if (u == rest) return@map floatArrayOf(0f, 0f, 0f)
                scene.reset(model, mapOf(parameter.id to u))
                var cx = 0.0; var cy = 0.0
                for (i in root) { cx += rootX(i); cy += rootY(i) }
                cx /= root.size; cy /= root.size
                var dot = 0.0; var cross = 0.0
                for ((k, i) in root.withIndex()) {
                    val ax = restRoot[k * 2] - cx0; val ay = restRoot[k * 2 + 1] - cy0
                    val bx = rootX(i) - cx; val by = rootY(i) - cy
                    dot += ax * bx + ay * by; cross += ax * by - ay * bx
                }
                val turn = if (dot == 0.0 && cross == 0.0) 0f else atan2(cross, dot).toFloat()
                val dx = (cx - cx0).toFloat(); val dy = (cy - cy0).toFloat()
                val c = cos(turn); val sn = sin(turn)
                val off = FloatArray(n)
                for (i in 0 until n) {
                    val rx = restGoal[i * 2] - cx0; val ry = restGoal[i * 2 + 1] - cy0
                    off[i] = hypot(s.goalX[i] - (cx0 + dx + c * rx - sn * ry), s.goalY[i] - (cy0 + dy + sn * rx + c * ry))
                    val mx = s.goalX[i] - restGoal[i * 2]; val my = s.goalY[i] - restGoal[i * 2 + 1]
                    motion = maxOf(motion, hypot(mx, my))
                    across += abs(mx); along += abs(my)
                }
                off.sort()
                reshape = maxOf(reshape, off[((n - 1) * 0.95f).toInt()])
                floatArrayOf(dx, dy, turn)
            }.toTypedArray()
            return Input(parameter, low, rest, high, values, roots, motion, reshape, across >= along)
        }
        val analyzed = if (parallel && inputs.size > 1) inputs.parallelStream().map(::analyzed).toList() else inputs.map(::analyzed)
        return Analysis(analyzed, length, cx0, cy0, root, useAnchors)
    }
}
