package io.github.psd2live.core

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Sway geometry for one Warp lattice. The pinned edge stays exact; everything else is measured by `s`,
 * the distance from the pinned edge over the edge-to-tip length.
 *
 * When the tip moves across the pinned axis (left/right under a top pivot, up/down under a side pivot)
 * the centerline bends with its length preserved, so a swinging tip also rises. When it moves along
 * that axis (up/down under a top pivot) the strand stretches and narrows like a bouncing weight.
 *
 * Segment k of n bends only past `k/n`, so a later segment turns what hangs below it, like the relative
 * angle a multi-vertex pendulum outputs for that vertex.
 */
internal object SwingDeformer {
    data class Shape(
        val kind: SwingKind,
        /** Resolved; never [SwingFulcrum.AUTO]. */
        val fulcrum: SwingFulcrum,
        val flip: Boolean,
        val magnitude: Float,
        val lift: Float,
        val softness: Float,
        val zoom: Float,
        val segments: Int,
    ) {
        init { require(fulcrum != SwingFulcrum.AUTO && segments in 1..RigSwingEdit.MAX_SEGMENTS) }

        /** Whether the tip moves across the pinned axis, which bends; otherwise it stretches. */
        val bends: Boolean get() = (fulcrum == SwingFulcrum.TOP || fulcrum == SwingFulcrum.BOTTOM) == (kind == SwingKind.LATERAL)
    }

    private const val SAMPLES = 256

    /**
     * Returns the lattice [points] swung by segment [values] (each -1..1). [sx]/[sy] turn local units into
     * proportional ones, so a bend under a non-square parent Warp is still round.
     */
    fun deform(points: FloatArray, rows: Int, columns: Int, sx: Float, sy: Float, shape: Shape, values: FloatArray): FloatArray =
        transform(points, rows, columns, sx, sy, shape, values, points)

    /**
     * Swings arbitrary local [targets] (interleaved x,y) in the frame of the [lattice]; the canvas handles
     * use this for centerline samples that fall between lattice rows.
     */
    fun transform(lattice: FloatArray, rows: Int, columns: Int, sx: Float, sy: Float, shape: Shape, values: FloatArray,
        targets: FloatArray): FloatArray {
        require(lattice.size == (rows + 1) * (columns + 1) * 2 && values.size == shape.segments && targets.size % 2 == 0)
        require(sx > 0f && sy > 0f && sx.isFinite() && sy.isFinite())
        val points = targets
        val sign = if (shape.flip) -1f else 1f
        val v = FloatArray(values.size) { values[it] * sign }
        if (v.all { it == 0f }) return points.copyOf()
        val frame = frame(lattice, rows, columns, sx, sy, shape)
        val power = 1f + 3f * shape.softness
        val n = shape.segments
        // Sum of the segment weights at s, normalized so every segment at 1 reaches 1 at the tip.
        fun weight(s: Float, k: Int): Float = ((s - k.toFloat() / n) * n).coerceIn(0f, 1f).pow(power)
        fun drive(s: Float): Float { var sum = 0f; for (k in 0 until n) sum += v[k] * weight(s, k); return sum / n }

        val out = points.copyOf()
        val count = points.size / 2
        val along = FloatArray(count); val across = FloatArray(count)
        var maxS = 1f
        for (i in 0 until count) {
            val x = points[i * 2] * sx - frame.rootX; val y = points[i * 2 + 1] * sy - frame.rootY
            along[i] = x * frame.ex + y * frame.ey
            across[i] = x * frame.nx + y * frame.ny
            maxS = maxOf(maxS, along[i] / frame.length)
        }
        val length = frame.length
        if (shape.bends) {
            // Solved with every segment at 1, so a full swing reaches the magnitude however it is split.
            val theta = solveTipAngle(shape.magnitude) { s -> weightSumAt(s, n, power) }
            // Centerline in units of length: tangent angle θ(s) = θ*·drive(s), integrated with the midpoint rule.
            val ds = maxS / SAMPLES
            val cx = FloatArray(SAMPLES + 1); val cy = FloatArray(SAMPLES + 1); val angle = FloatArray(SAMPLES + 1)
            for (j in 0..SAMPLES) angle[j] = theta * drive(j * ds)
            for (j in 1..SAMPLES) {
                val mid = theta * drive((j - 0.5f) * ds)
                cx[j] = cx[j - 1] + ds * cos(mid); cy[j] = cy[j - 1] + ds * sin(mid)
            }
            for (i in 0 until count) {
                val s = along[i] / length
                if (s <= 0f) continue
                val f = (s / ds).coerceAtMost(SAMPLES.toFloat())
                val j = f.toInt().coerceAtMost(SAMPLES - 1); val t = f - j
                val a = angle[j] + (angle[j + 1] - angle[j]) * t
                val px = cx[j] + (cx[j + 1] - cx[j]) * t
                val py = cy[j] + (cy[j + 1] - cy[j]) * t
                val d = drive(s)
                val b = across[i] * (1f + shape.zoom * abs(d))
                val newAlong = px * length - b * sin(a) - shape.lift * length * d * d
                val newAcross = py * length + b * cos(a)
                write(out, i, frame, newAlong, newAcross, sx, sy)
            }
        } else {
            val h = 1f / SAMPLES
            for (i in 0 until count) {
                val s = along[i] / length
                if (s <= 0f) continue
                val displacement = frame.stretchSign * shape.magnitude * drive(s)
                val strain = frame.stretchSign * shape.magnitude * (drive(s + h) - drive((s - h).coerceAtLeast(0f))) / (s + h - (s - h).coerceAtLeast(0f))
                val narrow = 1f / sqrt((1f + strain).coerceAtLeast(0.2f))
                val b = across[i] * narrow * (1f + shape.zoom * abs(drive(s)))
                write(out, i, frame, along[i] + displacement * length, b, sx, sy)
            }
        }
        return out
    }

    private fun weightSumAt(s: Float, n: Int, power: Float): Float {
        var sum = 0f
        for (k in 0 until n) sum += ((s - k.toFloat() / n) * n).coerceIn(0f, 1f).pow(power)
        return sum / n
    }

    /** The tip angle θ* whose centerline ends [magnitude] lengths off the rest axis. */
    private fun solveTipAngle(magnitude: Float, drive: (Float) -> Float): Float {
        if (magnitude <= 0f) return 0f
        fun offset(theta: Float): Float {
            val steps = 128; var sum = 0f
            for (j in 0 until steps) sum += sin(theta * drive((j + 0.5f) / steps))
            return sum / steps
        }
        // Offset rises, peaks, then falls as the strand curls back; search only the rising part.
        var low = 0f; var high = 0f; var best = 0f; var bestOffset = 0f
        var theta = 0f
        while (theta <= 3.2f) {
            val o = offset(theta)
            if (o >= magnitude) { high = theta; break }
            if (o > bestOffset) { bestOffset = o; best = theta }
            low = theta
            theta += 0.02f
        }
        if (high == 0f) return best
        repeat(24) {
            val mid = (low + high) / 2f
            if (offset(mid) < magnitude) low = mid else high = mid
        }
        return (low + high) / 2f
    }

    private class Frame(
        val rootX: Float, val rootY: Float,
        val ex: Float, val ey: Float,
        val nx: Float, val ny: Float,
        val length: Float,
        /** +1 when stretching moves the tip the positive way of the kind's screen direction. */
        val stretchSign: Float,
    )

    private fun write(out: FloatArray, i: Int, f: Frame, along: Float, across: Float, sx: Float, sy: Float) {
        out[i * 2] = (f.rootX + along * f.ex + across * f.nx) / sx
        out[i * 2 + 1] = (f.rootY + along * f.ey + across * f.ny) / sy
    }

    private fun frame(points: FloatArray, rows: Int, columns: Int, sx: Float, sy: Float, shape: Shape): Frame {
        fun rowMid(r: Int): Pair<Float, Float> {
            var x = 0f; var y = 0f
            for (c in 0..columns) { val i = (r * (columns + 1) + c) * 2; x += points[i] * sx; y += points[i + 1] * sy }
            return x / (columns + 1) to y / (columns + 1)
        }
        fun columnMid(c: Int): Pair<Float, Float> {
            var x = 0f; var y = 0f
            for (r in 0..rows) { val i = (r * (columns + 1) + c) * 2; x += points[i] * sx; y += points[i + 1] * sy }
            return x / (rows + 1) to y / (rows + 1)
        }
        val (root, tip) = when (shape.fulcrum) {
            SwingFulcrum.TOP -> rowMid(0) to rowMid(rows)
            SwingFulcrum.BOTTOM -> rowMid(rows) to rowMid(0)
            SwingFulcrum.LEFT -> columnMid(0) to columnMid(columns)
            SwingFulcrum.RIGHT -> columnMid(columns) to columnMid(0)
            SwingFulcrum.AUTO -> error("Resolve AUTO first")
        }
        // Positive values move the tip toward the lattice's right edge (left/right) or bottom edge (up/down).
        val (motionFrom, motionTo) = if (shape.kind == SwingKind.LATERAL) columnMid(0) to columnMid(columns) else rowMid(0) to rowMid(rows)
        val mx = motionTo.first - motionFrom.first; val my = motionTo.second - motionFrom.second
        val length = hypot(tip.first - root.first, tip.second - root.second).coerceAtLeast(1e-6f)
        val ex = (tip.first - root.first) / length; val ey = (tip.second - root.second) / length
        var nx = -ey; var ny = ex
        if (nx * mx + ny * my < 0f) { nx = -nx; ny = -ny }
        val stretchSign = if (ex * mx + ey * my < 0f) -1f else 1f
        return Frame(root.first, root.second, ex, ey, nx, ny, length, stretchSign)
    }
}
