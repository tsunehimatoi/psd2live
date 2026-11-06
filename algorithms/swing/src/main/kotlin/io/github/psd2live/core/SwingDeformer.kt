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
 *
 * A bend normally turns each cross-section with the centerline, so a wide lattice swings like one board.
 * [Shape.parallel] keeps the cross-sections level instead: toward 1 every column hangs from its own spot on
 * the pinned edge and sways alongside the others, which is how hair with several strands in one Warp moves.
 *
 * All of this happens in the swing rectangle: as wide as the pinned edge and as long as its midpoint is from
 * the tip edge's, moved and turned about its pinned edge's midpoint by [Shape.placement] for art that hangs
 * at a slant or from somewhere other than the lattice edge.
 * Turned, its pinned edge is no longer a lattice edge; what lies behind it stays put.
 */
object SwingDeformer {
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
        /** 0 turns the cross-sections with the bend; 1 keeps them level, so the tip edge sways without tilting. */
        val parallel: Float = 0f,
        val placement: Placement = Placement(),
    ) {
        init { require(fulcrum != SwingFulcrum.AUTO && segments in 1..RigSwingEdit.MAX_SEGMENTS) }

        /** Whether the tip moves across the pinned axis, which bends; otherwise it stretches. */
        val bends: Boolean get() = (fulcrum == SwingFulcrum.TOP || fulcrum == SwingFulcrum.BOTTOM) == (kind == SwingKind.LATERAL)
    }

    /**
     * Where the swing rectangle sits against the lattice's own: its pinned edge's midpoint moved [along] lengths
     * toward the tip and [across] widths toward the pinned edge's last lattice point, then turned [tilt] degrees
     * about that point. Relative units keep it on the same spot of the art in every keyform of the Warp.
     */
    data class Placement(val tilt: Float = 0f, val along: Float = 0f, val across: Float = 0f) {
        init {
            require(listOf(tilt, along, across).all(Float::isFinite)) { "Swing placement must be finite" }
            require(abs(tilt) <= RigSwingEdit.MAX_TILT && abs(along) <= RigSwingEdit.MAX_OFFSET && abs(across) <= RigSwingEdit.MAX_OFFSET) {
                "Swing placement out of range"
            }
        }
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
            val theta = tipAngle(shape.magnitude, n, power)
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
                // The cross-section turns only as far as the parallel setting lets it.
                val a = (angle[j] + (angle[j + 1] - angle[j]) * t) * (1f - shape.parallel)
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

    /** Applies each motion in turn, stretches before bends, so a bounce lengthens the strand that then bends. */
    fun compose(shapes: List<Shape>, points: FloatArray, move: (Int, FloatArray) -> FloatArray): FloatArray {
        var out = points
        for (m in shapes.indices.sortedBy { if (shapes[it].bends) 1 else 0 }) out = move(m, out)
        return out
    }

    private fun weightSumAt(s: Float, n: Int, power: Float): Float {
        var sum = 0f
        for (k in 0 until n) sum += ((s - k.toFloat() / n) * n).coerceIn(0f, 1f).pow(power)
        return sum / n
    }

    private data class TipKey(val magnitude: Float, val segments: Int, val power: Float)
    private val tipAngles = java.util.concurrent.ConcurrentHashMap<TipKey, Float>()

    /**
     * [solveTipAngle] for these settings, remembered: every cell of every pose shares it, and a canvas drag
     * poses the lattice many times over with the same few settings.
     */
    private fun tipAngle(magnitude: Float, segments: Int, power: Float): Float {
        if (tipAngles.size > 4096) tipAngles.clear()
        return tipAngles.getOrPut(TipKey(magnitude, segments, power)) { solveTipAngle(magnitude) { s -> weightSumAt(s, segments, power) } }
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

    /**
     * A point of the rest swing rectangle of [lattice] in its local units: [s] runs from the pinned edge (0) to
     * the tip edge (1), [t] across from -0.5 to 0.5, positive toward the pinned edge's last lattice point.
     * The canvas draws the rectangle and places its handles with it.
     */
    fun rectPoint(lattice: FloatArray, rows: Int, columns: Int, sx: Float, sy: Float, fulcrum: SwingFulcrum, placement: Placement,
        s: Float, t: Float = 0f): Pair<Float, Float> {
        val a = axis(lattice, rows, columns, sx, sy, fulcrum, placement)
        return (a.rootX + a.ex * a.length * s + a.px * a.width * t) / sx to (a.rootY + a.ey * a.length * s + a.py * a.width * t) / sy
    }

    private class Axis(
        val rootX: Float, val rootY: Float,
        val ex: Float, val ey: Float,
        /** Unit perpendicular to the axis, toward the pinned edge's last lattice point. */
        val px: Float, val py: Float,
        val length: Float,
        val width: Float,
    )

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

    private fun mid(points: FloatArray, indices: List<Int>, sx: Float, sy: Float): Pair<Float, Float> {
        var x = 0f; var y = 0f
        for (i in indices) { x += points[i * 2] * sx; y += points[i * 2 + 1] * sy }
        return x / indices.size to y / indices.size
    }
    private fun row(r: Int, columns: Int) = (0..columns).map { r * (columns + 1) + it }
    private fun column(c: Int, rows: Int, columns: Int) = (0..rows).map { it * (columns + 1) + c }

    private fun axis(points: FloatArray, rows: Int, columns: Int, sx: Float, sy: Float, fulcrum: SwingFulcrum, placement: Placement): Axis {
        val (pinned, opposite) = when (fulcrum) {
            SwingFulcrum.TOP -> row(0, columns) to row(rows, columns)
            SwingFulcrum.BOTTOM -> row(rows, columns) to row(0, columns)
            SwingFulcrum.LEFT -> column(0, rows, columns) to column(columns, rows, columns)
            SwingFulcrum.RIGHT -> column(columns, rows, columns) to column(0, rows, columns)
            SwingFulcrum.AUTO -> error("Resolve AUTO first")
        }
        val root = mid(points, pinned, sx, sy); val tip = mid(points, opposite, sx, sy)
        val length = hypot(tip.first - root.first, tip.second - root.second).coerceAtLeast(1e-6f)
        val e0x = (tip.first - root.first) / length; val e0y = (tip.second - root.second) / length
        val dx = (points[pinned.last() * 2] - points[pinned.first() * 2]) * sx
        val dy = (points[pinned.last() * 2 + 1] - points[pinned.first() * 2 + 1]) * sy
        // The perpendicular on the side of the edge's last point; a mirrored parent mirrors the turn with it.
        val side = if (-e0y * dx + e0x * dy < 0f) -1f else 1f
        val width = hypot(dx, dy).coerceAtLeast(1e-6f)
        // Moved in the unturned frame, so turning it keeps the pinned midpoint where it was put.
        val rootX = root.first + e0x * length * placement.along - side * e0y * width * placement.across
        val rootY = root.second + e0y * length * placement.along + side * e0x * width * placement.across
        val radians = Math.toRadians(placement.tilt.toDouble()).toFloat()
        val ex = e0x * cos(radians) - side * e0y * sin(radians)
        val ey = e0y * cos(radians) + side * e0x * sin(radians)
        return Axis(rootX, rootY, ex, ey, -side * ey, side * ex, length, width)
    }

    private fun frame(points: FloatArray, rows: Int, columns: Int, sx: Float, sy: Float, shape: Shape): Frame {
        val a = axis(points, rows, columns, sx, sy, shape.fulcrum, shape.placement)
        // Positive values move the tip toward the lattice's right edge (left/right) or bottom edge (up/down).
        val (motionFrom, motionTo) = if (shape.kind == SwingKind.LATERAL) mid(points, column(0, rows, columns), sx, sy) to mid(points, column(columns, rows, columns), sx, sy)
            else mid(points, row(0, columns), sx, sy) to mid(points, row(rows, columns), sx, sy)
        val mx = motionTo.first - motionFrom.first; val my = motionTo.second - motionFrom.second
        var nx = -a.ey; var ny = a.ex
        if (nx * mx + ny * my < 0f) { nx = -nx; ny = -ny }
        val stretchSign = if (a.ex * mx + a.ey * my < 0f) -1f else 1f
        return Frame(a.rootX, a.rootY, a.ex, a.ey, nx, ny, a.length, stretchSign)
    }
}
