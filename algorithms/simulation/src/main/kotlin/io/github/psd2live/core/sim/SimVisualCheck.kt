package io.github.psd2live.core.sim

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * One motion played by the reference simulation and by the baked model, frame by frame: per particle (x, y
 * interleaved) the simulated and baked offsets from the rig in px, turned into the body's frame (so a tilted head
 * does not count as a swing), the world positions both put the particles at, the mode parameters (-1..1 of their
 * range), and whether the inputs moved since the frame before.
 */
class SimVisualRun(
    val simulated: List<FloatArray>,
    val baked: List<FloatArray>,
    val simulatedWorld: List<FloatArray>,
    val bakedWorld: List<FloatArray>,
    val modes: List<FloatArray>,
    val moving: BooleanArray,
)

/**
 * What a viewer sees of the baked motion against the simulation's, on the tips (the tenth of particles that move
 * most, at least five): the swing's direction and size, how far it lags, how it settles after a move, how much
 * jerkier it is, how often a mode stalls at its end, how much the strands shorten, and how far off the body hangs
 * once still. R² and the 95th-percentile frame error are kept for comparison with the old readout.
 */
class SimVisualCheck(
    /** |cos| between the main directions the tips swing in, simulated and baked. */
    val direction: Float,
    /** Whether the baked swing goes the simulation's way along it (correlation > 0). */
    val sameSign: Boolean,
    /** RMS of the baked swing along the simulation's main direction against the simulation's. */
    val amplitude: Float,
    /** The 95th percentile of the farthest tip's travel, baked against simulated. */
    val reach: Float,
    /** How far the baked swing lags the simulation's (ms; negative leads), by cross-correlation. */
    val lagMs: Float,
    /** After the inputs stop: baked settle time against simulated (mean over stops that move), and NaN without any. */
    val settle: Float,
    /** After the inputs stop: baked swings past the resting point minus simulated ones, on average. */
    val overshoot: Float,
    /** sqrt of the third-difference energy, baked against simulated. */
    val jitter: Float,
    /** The mode parameters' roughness: third-difference against first-difference energy, square-rooted. */
    val roughness: Float,
    /** Share of frames a mode parameter sits at ±1. */
    val clipped: Float,
    /** Share of frames a mode sits at ±1 while the simulated tips still move fast: the baked body stalls. */
    val stalled: Float,
    /** 95th percentile over tip grain edges and frames of how much shorter the baked edge is than the simulated, of its rest length. */
    val shortening: Float,
    /** At the end of each hold of a second or more: the 95th-percentile particle distance between baked and simulated, px. */
    val staticPx: Float,
    /** The same against the simulation's own 95th-percentile offset there. */
    val staticShare: Float,
    val r2: Float,
    val p95Px: Float,
    /** How far the simulated tips travel: the 95th percentile of the farthest one's offset, px. */
    val motionPx: Float,
) {
    fun toMap() = linkedMapOf("dir" to direction, "sign" to (if (sameSign) 1f else -1f), "amp" to amplitude, "reach" to reach, "lag_ms" to lagMs,
        "settle" to settle, "overshoot" to overshoot, "jitter" to jitter, "rough" to roughness, "clipped" to clipped, "stalled" to stalled,
        "shorten" to shortening, "static_px" to staticPx, "static_share" to staticShare, "r2" to r2, "p95_px" to p95Px, "sim_px" to motionPx)

    /** Rounded to what a reader tells apart; a measure the motion gave no occasion for (NaN) is left out. */
    fun toJson() = buildJsonObject {
        for ((key, value) in toMap()) if (!value.isNaN()) put(key, Math.round(value * 1000.0) / 1000.0)
    }

    override fun equals(other: Any?) = other is SimVisualCheck && other.toJson() == toJson()
    override fun hashCode() = toJson().hashCode()

    companion object {
        fun fromJson(o: JsonObject): SimVisualCheck {
            fun f(key: String) = o[key]?.jsonPrimitive?.floatOrNull ?: Float.NaN
            return SimVisualCheck(f("dir"), f("sign") >= 0f, f("amp"), f("reach"), f("lag_ms"), f("settle"), f("overshoot"), f("jitter"), f("rough"),
                f("clipped"), f("stalled"), f("shorten"), f("static_px"), f("static_share"), f("r2"), f("p95_px"), f("sim_px"))
        }

        fun measure(run: SimVisualRun, edges: List<Pair<Int, Int>>, rest: FloatArray, fps: Int): SimVisualCheck {
            val frames = run.simulated.size
            val particles = run.simulated.first().size / 2
            // The tips: the particles that move most in the simulation.
            val energy = DoubleArray(particles)
            for (row in run.simulated) for (p in 0 until particles) energy[p] += (row[p * 2] * row[p * 2] + row[p * 2 + 1] * row[p * 2 + 1]).toDouble()
            val tips = (0 until particles).sortedByDescending { energy[it] }.take(maxOf(5, particles / 10)).toIntArray()
            fun over(row: FloatArray) = FloatArray(tips.size * 2) { row[tips[it / 2] * 2 + it % 2] }
            val s = run.simulated.map(::over)
            val b = run.baked.map(::over)
            val e1 = principal(s)
            val eb = principal(b)
            val s1 = FloatArray(frames) { dot(s[it], e1) }
            val b1 = FloatArray(frames) { dot(b[it], e1) }
            val direction = abs(dot(e1, eb))
            val sameSign = correlation(s1, b1, 0) >= 0.0
            val amplitude = (rms(b1) / rms(s1).coerceAtLeast(1e-6)).toFloat()
            fun farthest(rows: List<FloatArray>) = rows.map { row -> (0 until tips.size).maxOf { hypot(row[it * 2], row[it * 2 + 1]) } }
            val reach = percentile(farthest(b), 0.95f) / percentile(farthest(s), 0.95f).coerceAtLeast(1e-3f)

            // Lag: the shift of the baked swing that matches the simulated one best, refined between frames.
            val window = 30
            val scores = DoubleArray(window * 2 + 1) { correlation(s1, b1, it - window) }
            val best = scores.indices.maxBy { scores[it] }
            var lag = (best - window).toDouble()
            if (best in 1 until scores.size - 1) {
                val a = scores[best - 1]; val c = scores[best + 1]; val m = scores[best]
                val curve = a - 2 * m + c
                if (curve < 0) lag += 0.5 * (a - c) / curve
            }

            // Stops: runs of half a second or more with the inputs still.
            val stops = ArrayList<IntRange>()
            var start = -1
            for (f in 0..frames) {
                val still = f < frames && !run.moving[f]
                if (still && start < 0) start = f
                if (!still && start >= 0) { if (f - start >= fps / 2) stops += start until f; start = -1 }
            }
            val settles = ArrayList<Float>(); val overshoots = ArrayList<Float>()
            for (stop in stops) {
                val (sTime, sCount, sPeak) = settling(s1, stop)
                if (sPeak < 2f) continue
                val (bTime, bCount, _) = settling(b1, stop)
                settles += (bTime + 1f) / (sTime + 1f)
                overshoots += (bCount - sCount).toFloat()
            }

            val jitter = sqrt(jerk(run.baked) / jerk(run.simulated).coerceAtLeast(1e-12)).toFloat()
            val roughness = if (run.modes.isEmpty() || run.modes.first().isEmpty()) 0f else {
                var third = 0.0; var first = 0.0
                for (f in 1 until frames) for (k in run.modes[f].indices) { val d = (run.modes[f][k] - run.modes[f - 1][k]).toDouble(); first += d * d }
                for (f in 3 until frames) for (k in run.modes[f].indices) {
                    val d = (run.modes[f][k] - 3 * run.modes[f - 1][k] + 3 * run.modes[f - 2][k] - run.modes[f - 3][k]).toDouble(); third += d * d
                }
                sqrt(third / first.coerceAtLeast(1e-12)).toFloat()
            }
            val clippedFrames = BooleanArray(frames) { f -> run.modes[f].any { abs(it) >= 0.999f } }
            val speeds = FloatArray(frames) { f -> if (f == 0) 0f else (0 until tips.size).maxOf { hypot(s[f][it * 2] - s[f - 1][it * 2], s[f][it * 2 + 1] - s[f - 1][it * 2 + 1]) } }
            val fast = percentile(speeds.toList(), 0.95f) * 0.2f
            val clipped = clippedFrames.count { it }.toFloat() / frames
            val stalled = (0 until frames).count { clippedFrames[it] && speeds[it] > fast }.toFloat() / frames

            // Strand length along the grain at the tips, baked against simulated.
            val tipSet = tips.toHashSet()
            val tipEdges = edges.withIndex().filter { (_, e) -> e.first in tipSet || e.second in tipSet }
            val shortenings = ArrayList<Float>(tipEdges.size * frames / 2)
            for (f in 0 until frames step 2) for ((k, e) in tipEdges) {
                fun length(world: FloatArray) = hypot(world[e.first * 2] - world[e.second * 2], world[e.first * 2 + 1] - world[e.second * 2 + 1])
                shortenings += (length(run.simulatedWorld[f]) - length(run.bakedWorld[f])) / rest[k].coerceAtLeast(1e-3f)
            }

            // How the body hangs at the end of each hold of a second or more.
            val statics = ArrayList<Float>(); val shares = ArrayList<Float>()
            for (stop in stops) {
                if (stop.last - stop.first + 1 < fps) continue
                val f = stop.last
                val off = (0 until particles).map { p -> hypot(run.simulated[f][p * 2] - run.baked[f][p * 2], run.simulated[f][p * 2 + 1] - run.baked[f][p * 2 + 1]) }
                val own = (0 until particles).map { p -> hypot(run.simulated[f][p * 2], run.simulated[f][p * 2 + 1]) }
                val px = percentile(off, 0.95f)
                statics += px; shares += px / percentile(own, 0.95f).coerceAtLeast(1f)
            }

            var missed = 0.0; var total = 0.0
            val errors = run.simulated.indices.map { f ->
                var most = 0f
                for (p in 0 until particles) {
                    val dx = run.simulated[f][p * 2] - run.baked[f][p * 2]; val dy = run.simulated[f][p * 2 + 1] - run.baked[f][p * 2 + 1]
                    missed += (dx * dx + dy * dy).toDouble()
                    total += (run.simulated[f][p * 2] * run.simulated[f][p * 2] + run.simulated[f][p * 2 + 1] * run.simulated[f][p * 2 + 1]).toDouble()
                    most = maxOf(most, hypot(dx, dy))
                }
                most
            }
            return SimVisualCheck(direction, sameSign, amplitude, reach, (lag * 1000.0 / fps).toFloat(),
                if (settles.isEmpty()) Float.NaN else settles.average().toFloat(), if (overshoots.isEmpty()) Float.NaN else overshoots.average().toFloat(),
                jitter, roughness, clipped, stalled, if (shortenings.isEmpty()) 0f else percentile(shortenings, 0.95f),
                if (statics.isEmpty()) Float.NaN else statics.max(), if (shares.isEmpty()) Float.NaN else shares.max(),
                (1.0 - missed / total.coerceAtLeast(1e-12)).toFloat(), percentile(errors, 0.95f), percentile(farthest(s), 0.95f))
        }

        /**
         * Over [stop]: when [x] last leaves the band around where it ends (frames after the stop starts), how many
         * times it swings across that point outside the band, and the largest distance from it.
         */
        private fun settling(x: FloatArray, stop: IntRange): Triple<Float, Int, Float> {
            val end = (maxOf(stop.first, stop.last - 9)..stop.last).map { x[it] }.average().toFloat()
            val peak = stop.maxOf { abs(x[it] - end) }
            val band = maxOf(0.1f * peak, 1f)
            var last = stop.first
            var crossings = 0
            var side = 0
            for (f in stop) {
                val d = x[f] - end
                if (abs(d) > band) {
                    last = f
                    val now = if (d > 0) 1 else -1
                    if (side != 0 && now != side) crossings++
                    side = now
                }
            }
            return Triple((last - stop.first).toFloat(), crossings, peak)
        }

        /** The unit direction of most energy in [rows] (uncentered), by power iteration. */
        private fun principal(rows: List<FloatArray>): FloatArray {
            val size = rows.first().size
            var v = FloatArray(size) { if (it == 0) 1f else 0.1f / (it + 1) }
            repeat(60) {
                val next = FloatArray(size)
                for (row in rows) { val d = dot(row, v); if (d != 0f) for (i in 0 until size) next[i] += d * row[i] }
                val length = sqrt(dot(next, next))
                if (length < 1e-12f) return v
                for (i in 0 until size) next[i] /= length
                v = next
            }
            return v
        }

        /** Pearson correlation of [a] and [b] shifted later by [lag] frames, over the frames both cover. */
        private fun correlation(a: FloatArray, b: FloatArray, lag: Int): Double {
            var sab = 0.0; var saa = 0.0; var sbb = 0.0; var sa = 0.0; var sb = 0.0; var n = 0
            for (f in a.indices) {
                val g = f + lag
                if (g !in b.indices) continue
                val x = a[f].toDouble(); val y = b[g].toDouble()
                sab += x * y; saa += x * x; sbb += y * y; sa += x; sb += y; n++
            }
            if (n < 2) return 0.0
            val cov = sab - sa * sb / n; val va = saa - sa * sa / n; val vb = sbb - sb * sb / n
            return if (va <= 1e-12 || vb <= 1e-12) 0.0 else cov / sqrt(va * vb)
        }

        private fun jerk(rows: List<FloatArray>): Double {
            var sum = 0.0
            for (f in 3 until rows.size) {
                val a = rows[f]; val b = rows[f - 1]; val c = rows[f - 2]; val d = rows[f - 3]
                for (i in a.indices) { val j = (a[i] - 3 * b[i] + 3 * c[i] - d[i]).toDouble(); sum += j * j }
            }
            return sum
        }

        private fun rms(x: FloatArray) = sqrt(x.sumOf { (it * it).toDouble() } / x.size.coerceAtLeast(1))

        private fun dot(a: FloatArray, b: FloatArray): Float { var s = 0f; for (i in a.indices) s += a[i] * b[i]; return s }

        fun percentile(values: List<Float>, q: Float): Float {
            if (values.isEmpty()) return 0f
            val sorted = values.sorted()
            return sorted[((sorted.size - 1) * q).toInt().coerceIn(0, sorted.size - 1)]
        }
    }
}
