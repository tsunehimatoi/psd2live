package io.github.psd2live.ui

import androidx.compose.ui.geometry.Offset
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Brush shape types supported by the canvas deformer brushes (BRUSH, SMOOTH, INFLATE).
 */
enum class BrushShape(val labelKey: String) {
    CIRCLE("editor.brushShape.circle"),
    LINE("editor.brushShape.line"),
    RECTANGLE("editor.brushShape.rectangle"),
}

/**
 * How a deform brush's pull fades from its core to its rim, the same eight profiles Blender's
 * proportional editing offers. Hardness still sets the solid core; the profile shapes what lies past it.
 */
enum class BrushFalloff(val labelKey: String) {
    SMOOTH("editor.falloff.smooth"),
    SPHERE("editor.falloff.sphere"),
    ROOT("editor.falloff.root"),
    INVERSE_SQUARE("editor.falloff.inverseSquare"),
    SHARP("editor.falloff.sharp"),
    LINEAR("editor.falloff.linear"),
    CONSTANT("editor.falloff.constant"),
    RANDOM("editor.falloff.random");

    /** The weight at [t], 1 at the core's edge and 0 at the rim; [seed] picks a point's share under [RANDOM]. */
    fun weight(t: Float, seed: Int = 0): Float {
        val x = t.coerceIn(0f, 1f)
        return when (this) {
            SMOOTH -> x * x * (3f - 2f * x)
            SPHERE -> sqrt(x * (2f - x))
            ROOT -> sqrt(x)
            INVERSE_SQUARE -> x * (2f - x)
            SHARP -> x * x
            LINEAR -> x
            CONSTANT -> if (x > 0f) 1f else 0f
            RANDOM -> x * pointNoise(seed)
        }
    }
}

/** A stable 0..1 value per [seed], so a random falloff does not flicker from one step to the next. */
internal fun pointNoise(seed: Int): Float {
    var h = seed * -0x61c88647
    h = h xor (h ushr 16)
    h *= 0x45d9f3b
    h = h xor (h ushr 16)
    return (h ushr 8) / 16777216f
}

/** Everything that decides one point's weight under the tip, in screen pixels. */
internal data class BrushTip(
    val radius: Float,
    val hardness: Float,
    val shape: BrushShape,
    val angleDeg: Float,
    val aspect: Float,
    val falloff: BrushFalloff,
) {
    fun weight(p: Offset, from: Offset, to: Offset, seed: Int = 0): Float =
        computeBrushWeight(p, from, to, radius, hardness, shape, angleDeg, aspect, falloff, seed)

    fun reaches(p: Offset, from: Offset, to: Offset): Boolean =
        computeBrushWeight(p, from, to, radius, hardness, shape, angleDeg, aspect) > 0f
}

/**
 * One mesh as the brush sees it: its points on screen, which of them share an edge (only needed for the
 * connected-only walk), and its triangles - null for a warp lattice. [salt] keeps two meshes' random
 * falloffs apart.
 */
internal class BrushSurface(
    val points: List<Offset>,
    val neighbors: List<IntArray>?,
    val triangles: IntArray?,
    val salt: Int,
)

/**
 * The weight of every point of every surface under the stroke segment [from] -> [to]. With [connected]
 * only what is joined by edges to the part under the pointer is reached, like Blender's "Connected Only":
 * a circle then measures its distance along the mesh, so the lower lip stays put while the upper one is
 * brushed; the line and rectangle keep their own shape and only drop the parts they cannot walk to.
 */
internal fun brushWeights(surfaces: List<BrushSurface>, from: Offset, to: Offset, tip: BrushTip, connected: Boolean): List<FloatArray> {
    if (!connected) return surfaces.map { s -> FloatArray(s.points.size) { i -> tip.weight(s.points[i], from, to, s.salt * 31 + i) } }
    val starts = surfaces.map { s ->
        val found = HashSet<Int>()
        val tri = s.triangles
        if (tri != null) for (f in 0 until tri.size / 3) {
            val a = tri[f * 3]; val b = tri[f * 3 + 1]; val c = tri[f * 3 + 2]
            if (a >= s.points.size || b >= s.points.size || c >= s.points.size) continue
            if (insideTriangle(to, s.points[a], s.points[b], s.points[c])) { found += a; found += b; found += c }
        }
        found
    }
    // Off the art, the walk starts from the one point nearest the pointer, whichever mesh it is on.
    if (starts.all { it.isEmpty() }) {
        var best = Float.MAX_VALUE
        var bestAt = -1
        var bestIndex = -1
        surfaces.forEachIndexed { at, s ->
            s.points.forEachIndexed { i, p ->
                val d = distanceToSegment(p, from, to)
                if (d < best) { best = d; bestAt = at; bestIndex = i }
            }
        }
        if (bestAt >= 0 && best <= tip.radius) starts[bestAt] += bestIndex
    }
    return surfaces.mapIndexed { at, s -> walkBrush(s, starts[at], from, to, tip) }
}

private fun walkBrush(s: BrushSurface, starts: Set<Int>, from: Offset, to: Offset, tip: BrushTip): FloatArray {
    val weights = FloatArray(s.points.size)
    val neighbors = s.neighbors ?: return weights
    if (starts.isEmpty()) return weights
    if (tip.shape == BrushShape.CIRCLE) {
        // Distance along the edges, seeded with each start's straight distance to the pointer.
        val distance = FloatArray(s.points.size) { Float.MAX_VALUE }
        val queue = PriorityQueue<Pair<Float, Int>>(compareBy { it.first })
        for (i in starts) {
            val d = distanceToSegment(s.points[i], from, to)
            if (d <= tip.radius && d < distance[i]) { distance[i] = d; queue += d to i }
        }
        while (queue.isNotEmpty()) {
            val (d, i) = queue.poll()
            if (d > distance[i]) continue
            for (n in neighbors[i]) {
                if (n >= s.points.size) continue
                val next = d + (s.points[n] - s.points[i]).getDistance()
                if (next <= tip.radius && next < distance[n]) { distance[n] = next; queue += next to n }
            }
        }
        for (i in weights.indices) {
            if (distance[i] <= tip.radius) weights[i] = brushWeight(distance[i], tip.radius, tip.hardness, tip.falloff, s.salt * 31 + i)
        }
        return weights
    }
    val reached = BooleanArray(s.points.size)
    val stack = ArrayDeque<Int>()
    fun visit(i: Int) {
        // Reach is tested apart from the weight: a random falloff may land on zero inside the tip, and the
        // walk must still pass through that point.
        if (reached[i] || !tip.reaches(s.points[i], from, to)) return
        reached[i] = true
        weights[i] = tip.weight(s.points[i], from, to, s.salt * 31 + i)
        stack += i
    }
    starts.forEach(::visit)
    while (stack.isNotEmpty()) neighbors[stack.removeLast()].forEach { if (it < s.points.size) visit(it) }
    return weights
}

private fun insideTriangle(p: Offset, a: Offset, b: Offset, c: Offset): Boolean {
    fun side(u: Offset, v: Offset) = (v.x - u.x) * (p.y - u.y) - (v.y - u.y) * (p.x - u.x)
    val d1 = side(a, b); val d2 = side(b, c); val d3 = side(c, a)
    val negative = d1 < 0f || d2 < 0f || d3 < 0f
    val positive = d1 > 0f || d2 > 0f || d3 > 0f
    return !(negative && positive)
}

/**
 * Returns true if [p] is within the influence perimeter of the brush along the stroke segment [from] -> [to].
 */
internal fun isPointInBrush(
    p: Offset,
    from: Offset,
    to: Offset,
    radius: Float,
    shape: BrushShape,
    angleDeg: Float = 0f,
    aspect: Float = 1f,
    hardness: Float = 0f,
): Boolean {
    return computeBrushWeight(p, from, to, radius, hardness, shape, angleDeg, aspect) > 0f
}

/**
 * Computes the falloff weight [0.0..1.0] for point [p] given brush shape, orientation, radius, and hardness.
 * Uses realistic brush core-and-Euclidean-falloff dynamics matching the standard circular brush.
 */
internal fun computeBrushWeight(
    p: Offset,
    from: Offset,
    to: Offset,
    radius: Float,
    hardness: Float,
    shape: BrushShape,
    angleDeg: Float = 0f,
    aspect: Float = 1f,
    falloff: BrushFalloff = BrushFalloff.SMOOTH,
    seed: Int = 0,
): Float {
    if (radius <= 0.01f) return 0f

    return when (shape) {
        BrushShape.CIRCLE -> {
            val d = distanceToSegment(p, from, to)
            if (d > radius) 0f else brushWeight(d, radius, hardness, falloff, seed)
        }

        BrushShape.LINE -> {
            // Infinite line passing through the stroke with orientation angleDeg.
            // Normal unit vector perpendicular to the line: (-sinA, cosA).
            val rad = Math.toRadians(angleDeg.toDouble())
            val cosA = cos(rad).toFloat()
            val sinA = sin(rad).toFloat()

            val signedTo = -(p.x - to.x) * sinA + (p.y - to.y) * cosA
            val signedFrom = -(p.x - from.x) * sinA + (p.y - from.y) * cosA

            // If point is between the two swept lines, distance is 0; otherwise min distance to either line
            val d = if (signedTo * signedFrom <= 0f) 0f else min(abs(signedTo), abs(signedFrom))

            if (d > radius) 0f else brushWeight(d, radius, hardness, falloff, seed)
        }

        BrushShape.RECTANGLE -> {
            // Real brush hardness effect:
            // Solid rectangular core of dimensions [coreW, coreH] with 100% influence,
            // surrounded by an isotropic Euclidean band of width [band] fading along the chosen falloff.
            val rad = Math.toRadians(angleDeg.toDouble())
            val cosA = cos(rad).toFloat()
            val sinA = sin(rad).toFloat()

            val h = hardness.coerceIn(0f, 0.95f)
            val halfW = radius.coerceAtLeast(1f)
            val halfH = (radius * aspect.coerceIn(0.1f, 10f)).coerceAtLeast(1f)

            val coreW = halfW * h
            val coreH = halfH * h
            val band = (halfW * (1f - h)).coerceAtLeast(0.001f)

            // Euclidean distance to the rectangular core boundary
            fun distToCore(center: Offset): Float {
                val delta = p - center
                val lx = abs(delta.x * cosA + delta.y * sinA)
                val ly = abs(-delta.x * sinA + delta.y * cosA)
                val dx = max(lx - coreW, 0f)
                val dy = max(ly - coreH, 0f)
                return sqrt(dx * dx + dy * dy)
            }

            val dTo = distToCore(to)
            val dFrom = distToCore(from)
            val dMid = distToCore((from + to) * 0.5f)
            val d = min(dTo, min(dFrom, dMid))

            if (d > band) 0f else falloff.weight(1f - (d / band).coerceIn(0f, 1f), seed)
        }
    }
}
