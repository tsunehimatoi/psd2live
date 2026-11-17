package io.github.psd2live.algorithms.binding

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.*

/** A shared piecewise-affine skin cage around the mouth opening. The opening is a hole, not a skin strip. */
class MouthSkinField(upper: FloatArray, lower: FloatArray, bounds: Bounds) {
    private val count = upper.size / 2
    private val samples = (0 until count).map { true to it } + (count - 2 downTo 1).map { false to it }
    private val sectors = samples.size
    private val rings = 6
    private val mid = (count / 2) * 2
    private val centerX = (upper[mid] + lower[mid]) * 0.5f
    private val centerY = (upper[mid + 1] + lower[mid + 1]) * 0.5f
    private val outer = bounds.expanded(0.05f)
    private val rest = FloatArray((rings + 1) * sectors * 2)
    private val triangles: IntArray
    private val areas: FloatArray
    private data class Location(val a: Int, val b: Int, val c: Int, val wa: Float, val wb: Float, val wc: Float)
    private val locations = ConcurrentHashMap<Triple<Float, Float, Boolean?>, Location>()

    init {
        for ((s, sample) in samples.withIndex()) {
            val curve = if (sample.first) upper else lower
            val x = curve[sample.second * 2]; val y = curve[sample.second * 2 + 1]
            val dx = x - centerX; val dy = y - centerY
            val reachX = if (dx > 0f) (outer.right - centerX) / dx else if (dx < 0f) (outer.left - centerX) / dx else Float.POSITIVE_INFINITY
            val reachY = if (dy > 0f) (outer.bottom - centerY) / dy else if (dy < 0f) (outer.top - centerY) / dy else Float.POSITIVE_INFINITY
            // Coincident centre samples collapse their radial ring without 0 * infinity.
            val reach = min(reachX, reachY).coerceAtLeast(1f).let { if (it.isFinite()) it else 1f }
            for (r in 0..rings) {
                val t = r.toFloat() / rings
                val i = (r * sectors + s) * 2
                rest[i] = x + t * (centerX + dx * reach - x)
                rest[i + 1] = y + t * (centerY + dy * reach - y)
            }
        }
        triangles = buildList {
            for (r in 0 until rings) for (s in 0 until sectors) {
                val n = (s + 1) % sectors
                val a = r * sectors + s; val b = r * sectors + n
                val c = (r + 1) * sectors + s; val d = (r + 1) * sectors + n
                addAll(listOf(a, b, c, b, d, c))
            }
        }.toIntArray()
        areas = FloatArray(triangles.size / 3) { t -> area(rest, triangles[t * 3], triangles[t * 3 + 1], triangles[t * 3 + 2]) }
    }

    inner class Posed(private val positions: FloatArray, private val center: Pair<Float, Float>) {
        fun displacement(x: Float, y: Float, upper: Boolean? = null): Pair<Float, Float>? {
            val l = locations.computeIfAbsent(Triple(x, y, upper)) { locate(x, y, upper) }
            if (l.a < 0) return null
            fun coordinate(axis: Int): Float {
                fun at(i: Int) = if (i == positions.size / 2) {
                    if (axis == 0) center.first - centerX else center.second - centerY
                } else positions[i * 2 + axis] - rest[i * 2 + axis]
                return l.wa * at(l.a) + l.wb * at(l.b) + l.wc * at(l.c)
            }
            return coordinate(0) to coordinate(1)
        }
    }

    fun pose(upper: FloatArray, lower: FloatArray, outside: (Float, Float) -> Pair<Float, Float>): Posed {
        val p = rest.copyOf()
        for ((s, sample) in samples.withIndex()) {
            val curve = if (sample.first) upper else lower
            val dx = curve[sample.second * 2] - rest[s * 2]
            val dy = curve[sample.second * 2 + 1] - rest[s * 2 + 1]
            val edge = (rings * sectors + s) * 2
            val far = outside(rest[edge], rest[edge + 1])
            for (r in 0..rings) {
                val t = r.toFloat() / rings
                val i = (r * sectors + s) * 2
                p[i] += (1f - t) * dx + t * far.first
                p[i + 1] += (1f - t) * dy + t * far.second
            }
        }
        // The boundary stays exact; displacement smoothing and area constraints adjust only the intervening skin.
        val wanted = p.copyOf()
        val scratch = FloatArray(p.size)
        for (iteration in 0 until 120) {
            var corrections = 0
            if (iteration in 1..59) {
                for (r in 1 until rings) for (s in 0 until sectors) {
                    val idx = (r * sectors + s) * 2
                    val prevS = (r * sectors + (s + sectors - 1) % sectors) * 2
                    val nextS = (r * sectors + (s + 1) % sectors) * 2
                    val prevR = ((r - 1) * sectors + s) * 2
                    val nextR = ((r + 1) * sectors + s) * 2
                    for (axis in 0..1) {
                        val avgD = 0.25f * (
                            (p[prevS + axis] - rest[prevS + axis]) +
                            (p[nextS + axis] - rest[nextS + axis]) +
                            (p[prevR + axis] - rest[prevR + axis]) +
                            (p[nextR + axis] - rest[nextR + axis])
                        )
                        scratch[idx + axis] = p[idx + axis] + 0.15f * ((rest[idx + axis] + avgD) - p[idx + axis]) +
                            0.02f * (wanted[idx + axis] - p[idx + axis])
                    }
                }
                System.arraycopy(scratch, sectors * 2, p, sectors * 2, (rings - 1) * sectors * 2)
            }
            for (t in areas.indices) {
                val a = triangles[t * 3]; val b = triangles[t * 3 + 1]; val c = triangles[t * 3 + 2]
                val sign = if (areas[t] < 0f) -1f else 1f
                val deficit = abs(areas[t]) * 0.05f - sign * area(p, a, b, c)
                if (deficit <= 1e-6f) continue
                corrections++
                val ids = intArrayOf(a, b, c)
                val gx = floatArrayOf(p[b * 2 + 1] - p[c * 2 + 1], p[c * 2 + 1] - p[a * 2 + 1], p[a * 2 + 1] - p[b * 2 + 1])
                val gy = floatArrayOf(p[c * 2] - p[b * 2], p[a * 2] - p[c * 2], p[b * 2] - p[a * 2])
                var norm = 0f
                for (k in 0..2) if (ids[k] / sectors in 1 until rings) norm += gx[k] * gx[k] + gy[k] * gy[k]
                if (norm <= 1e-12f) continue
                for (k in 0..2) if (ids[k] / sectors in 1 until rings) {
                    p[ids[k] * 2] += sign * deficit * gx[k] / norm
                    p[ids[k] * 2 + 1] += sign * deficit * gy[k] / norm
                }
            }
            if (corrections == 0) break
        }
        return Posed(p, (upper[mid] + lower[mid]) * 0.5f to (upper[mid + 1] + lower[mid + 1]) * 0.5f)
    }

    private fun locate(x: Float, y: Float, upper: Boolean?): Location {
        fun triangle(a: Int, b: Int, c: Int, center: Boolean = false): Location? {
            val ax = rest[a * 2]; val ay = rest[a * 2 + 1]
            val bx = rest[b * 2]; val by = rest[b * 2 + 1]
            val cx = if (center) centerX else rest[c * 2]; val cy = if (center) centerY else rest[c * 2 + 1]
            val den = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)
            if (abs(den) < 1e-10f) return null
            val wb = ((x - ax) * (cy - ay) - (y - ay) * (cx - ax)) / den
            val wc = ((bx - ax) * (y - ay) - (by - ay) * (x - ax)) / den
            val wa = 1f - wb - wc
            return if (min(wa, min(wb, wc)) >= -1e-5f) Location(a, b, c, wa, wb, wc) else null
        }
        if (x in outer.left..outer.right && y in outer.top..outer.bottom) {
            val leftCornerX = min(rest[0], rest[(count - 1) * 2])
            val rightCornerX = max(rest[0], rest[(count - 1) * 2])
            val insideCorners = upper != null && x > leftCornerX && x < rightCornerX
            if (insideCorners) {
                val sectorRange = if (upper == true) 0 until count - 1 else count - 1 until sectors
                for (r in 0 until rings) for (s in sectorRange) {
                    val base = (r * sectors + s) * 6
                    triangle(triangles[base], triangles[base + 1], triangles[base + 2])?.let { return it }
                    triangle(triangles[base + 3], triangles[base + 4], triangles[base + 5])?.let { return it }
                }
            } else {
                for (t in triangles.indices step 3) triangle(triangles[t], triangles[t + 1], triangles[t + 2])?.let { return it }
            }
            if (upper != null) {
                var nearest = -1
                var distance = Float.POSITIVE_INFINITY
                val range = if (upper) 0 until count - 1 else count - 1 until sectors
                for (s in range) {
                    val b = (s + 1) % sectors
                    val ax = rest[s * 2]; val ay = rest[s * 2 + 1]
                    val vx = rest[b * 2] - ax; val vy = rest[b * 2 + 1] - ay
                    val t = (((x - ax) * vx + (y - ay) * vy) / (vx * vx + vy * vy).coerceAtLeast(1e-10f)).coerceIn(0f, 1f)
                    val d = (x - ax - t * vx).pow(2) + (y - ay - t * vy).pow(2)
                    if (d < distance) { distance = d; nearest = s }
                }
                if (nearest >= 0) {
                    val a = nearest; val b = (a + 1) % sectors; val c = sectors + a
                    // Lip paint can extend into the aperture, outside the skin cage.
                    // Extrapolating a thin outer-ring triangle through this hole amplifies
                    // closure motion and folds that paint. Carry it with its own nearest
                    // lip boundary instead, preserving its rest offset and bounded motion.
                    val vx = rest[b * 2] - rest[a * 2]
                    val vy = rest[b * 2 + 1] - rest[a * 2 + 1]
                    val t = (((x - rest[a * 2]) * vx + (y - rest[a * 2 + 1]) * vy) /
                        (vx * vx + vy * vy).coerceAtLeast(1e-10f)).coerceIn(0f, 1f)
                    return Location(a, b, c, 1f - t, t, 0f)
                }
            }
            for (s in 0 until sectors) triangle(s, (s + 1) % sectors, rest.size / 2, true)?.let { return it }
        }
        return Location(-1, -1, -1, 0f, 0f, 0f)
    }

    private fun area(p: FloatArray, a: Int, b: Int, c: Int) =
        (p[b * 2] - p[a * 2]) * (p[c * 2 + 1] - p[a * 2 + 1]) - (p[b * 2 + 1] - p[a * 2 + 1]) * (p[c * 2] - p[a * 2])
}
