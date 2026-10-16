package io.github.psd2live.core.interior

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * RGB contrast at two scales, with alpha-normalized smoothing. These are editable texture-edge
 * suggestions, NOT an estimate of depth or a claim that a printed line is a crease. Deliberately
 * returns TOPOLOGY guides only. Transparent RGB has no influence on the result.
 */
internal object InteriorStructureAnalysis {
    data class Settings(
        /** Minimum RGB-vector gradient (channels normalized to 0..1). */
        val contrast: Float = .08f,
        /** Smoothing radius in source pixels. Analysis is authoring-only and never changes the raster. */
        val scale: Int = 2,
        val minLength: Float = 8f,
        val maxGuides: Int = 32,
    ) {
        init {
            require(contrast.isFinite() && contrast in .001f..1f && scale in 1..32)
            require(minLength.isFinite() && minLength > 0 && maxGuides in 1..64)
        }
    }

    fun suggest(raster: InteriorRaster, settings: Settings = Settings()): List<InteriorGuide> {
        val w = raster.width; val h = raster.height; val size = w * h
        if (w < 3 || h < 3) return emptyList()
        val best = DoubleArray(size)
        val direction = DoubleArray(size)
        for (radius in listOf(settings.scale, settings.scale * 2)) {
            // Integral channels give the same alpha-weighted box mean at each radius. The
            // coverage gate excludes silhouette edges instead of mistaking them for internal folds.
            val stride = w + 1
            val integral = Array(5) { DoubleArray(stride * (h + 1)) }
            for (y in 0 until h) {
                val sum = DoubleArray(5)
                for (x in 0 until w) {
                    val alpha = raster.channel(x, y, 3) / 255.0
                    for (c in 0..4) {
                        sum[c] += when (c) { 4 -> if (alpha > 0) 1.0 else 0.0; 3 -> alpha; else -> alpha * raster.channel(x, y, c) / 255.0 }
                        integral[c][(y + 1) * stride + x + 1] = integral[c][y * stride + x + 1] + sum[c]
                    }
                }
            }
            val channels = Array(3) { DoubleArray(size) }
            val coverage = DoubleArray(size)
            for (y in 0 until h) for (x in 0 until w) {
                val x0 = max(0, x - radius); val x1 = min(w, x + radius + 1)
                val y0 = max(0, y - radius); val y1 = min(h, y + radius + 1)
                fun sum(c: Int): Double {
                    val a = integral[c]
                    return a[y1 * stride + x1] - a[y0 * stride + x1] - a[y1 * stride + x0] + a[y0 * stride + x0]
                }
                val mass = sum(3)
                // Coverage concerns the support, not opacity: uniform translucent cloth still has
                // meaningful texture. Alpha remains the weight in the normalized color means.
                coverage[y * w + x] = sum(4) / ((x1 - x0) * (y1 - y0))
                if (mass > 0) for (c in 0..2) channels[c][y * w + x] = sum(c) / mass
            }
            for (y in 1 until h - 1) for (x in 1 until w - 1) {
                val i = y * w + x
                if (coverage[i] < .95 || coverage[i - 1] < .95 || coverage[i + 1] < .95 ||
                    coverage[i - w] < .95 || coverage[i + w] < .95) continue
                var xx = 0.0; var xy = 0.0; var yy = 0.0
                for (c in channels) {
                    val dx = (c[i + 1] - c[i - 1]) * .5
                    val dy = (c[i + w] - c[i - w]) * .5
                    xx += dx * dx; xy += dx * dy; yy += dy * dy
                }
                // Principal direction of the RGB structure tensor also detects isoluminant colors.
                val magnitude = sqrt((xx + yy + hypot(xx - yy, 2 * xy)) * .5)
                if (magnitude > best[i]) {
                    best[i] = magnitude; direction[i] = .5 * atan2(2 * xy, xx - yy)
                }
            }
        }
        // Fuse responses BEFORE thinning. Unioning independently thinned scales creates parallel
        // adjacent ridges; tracing mistakes the resulting two-pixel strip for a forest of junctions.
        fun sample(x: Double, y: Double): Double {
            val ix = x.toInt().coerceIn(0, w - 2); val iy = y.toInt().coerceIn(0, h - 2)
            val u = x - ix; val v = y - iy
            return (best[iy * w + ix] * (1 - u) + best[iy * w + ix + 1] * u) * (1 - v) +
                (best[(iy + 1) * w + ix] * (1 - u) + best[(iy + 1) * w + ix + 1] * u) * v
        }
        val active = BooleanArray(size)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x; val value = best[i]
            val dx = cos(direction[i]); val dy = sin(direction[i])
            val before = sample(x - dx, y - dy); val after = sample(x + dx, y + dy)
            // Strict on one side, with roundoff tolerance, chooses one side of a flat ridge.
            active[i] = value >= settings.contrast && value > before + 1e-12 && value >= after - 1e-12
        }
        fun neighbours(i: Int): List<Int> {
            val x = i % w; val y = i / w
            val result = ArrayList<Int>(8)
            for (dy in -1..1) for (dx in -1..1) {
                if ((dx == 0 && dy == 0) || x + dx !in 0 until w || y + dy !in 0 until h) continue
                val j = (y + dy) * w + x + dx
                if (!active[j]) continue
                // Avoid diagonal shortcuts around an existing orthogonal connection.
                if (dx != 0 && dy != 0 && (active[y * w + x + dx] || active[(y + dy) * w + x])) continue
                result += j
            }
            return result
        }
        val adjacent = Array(size) { if (active[it]) neighbours(it) else emptyList() }
        val used = HashSet<Long>()
        fun edge(a: Int, b: Int) = (min(a, b).toLong() shl 32) or max(a, b).toLong()
        val paths = ArrayList<Pair<Double, List<Int>>>()
        fun walk(start: Int, next: Int) {
            if (!used.add(edge(start, next))) return
            val path = arrayListOf(start, next)
            var previous = start; var current = next
            while (adjacent[current].size == 2) {
                val after = adjacent[current].first { it != previous }
                if (!used.add(edge(current, after))) break
                path += after; previous = current; current = after
                if (current == start) break
            }
            val length = path.zipWithNext().sumOf { (a, b) -> hypot((a % w - b % w).toDouble(), (a / w - b / w).toDouble()) }
            if (length >= settings.minLength) paths += length to path
        }
        for (i in 0 until size) if (active[i] && adjacent[i].size != 2) for (j in adjacent[i]) walk(i, j)
        for (i in 0 until size) if (active[i]) for (j in adjacent[i]) walk(i, j) // closed paths
        return paths.sortedByDescending { it.first }.flatMap { (_, path) ->
            // Keep bends, and split long paths into overlapping chunks instead of truncating them.
            val reduced = simplify(path.map { InteriorPoint((it % w).toFloat(), (it / w).toFloat()) }, .7)
            reduced.windowed(256, 255, partialWindows = true).filter { it.size >= 2 }
        }.take(settings.maxGuides).mapIndexed { index, controls ->
            InteriorGuide("edge-$index", controls, radius = max(2f, settings.scale * 4f))
        }
    }

    private fun simplify(points: List<InteriorPoint>, tolerance: Double): List<InteriorPoint> {
        val kept = BooleanArray(points.size)
        kept[0] = true; kept[points.lastIndex] = true
        val ranges = ArrayDeque<Pair<Int, Int>>()
        ranges += 0 to points.lastIndex
        while (ranges.isNotEmpty()) {
            val (a, b) = ranges.removeLast()
            var best = tolerance * tolerance; var split = -1
            for (i in a + 1 until b) {
                val distance = io.github.psd2live.core.mesh.distanceSquaredToSegment(points[i].geometry(), points[a].geometry(), points[b].geometry())
                if (distance > best) { best = distance; split = i }
            }
            if (split >= 0) { kept[split] = true; ranges += a to split; ranges += split to b }
        }
        return points.filterIndexed { i, _ -> kept[i] }
    }
}
