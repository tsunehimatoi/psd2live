package io.github.psd2live.core.sim

import kotlin.math.PI
import kotlin.math.sin

/**
 * The motion a bake is made for, the way a model is actually moved: a head dragged between poses, shaken
 * and nodded, turned and stopped, swaying idly, all over the inputs' full ranges. Each piece starts and
 * ends at rest and holds one -1..1 array per input (0 the default, ±1 the ends of its range).
 *
 * The training and the held-out motion come from the same kinds of movement with different seeds, so the
 * held-out motion judges the bake on motion like - but not - what it was fitted to.
 */
internal object SimMotionLibrary {
    /**
     * The training motion for [count] inputs at [fps], every duration scaled by [duration]: each input
     * stepped alone (so each can be told apart), then dragging, shaking, flinging through the body's
     * resonance, idling and all of them mixed.
     */
    fun training(count: Int, fps: Int, duration: Float = 1f): List<List<FloatArray>> {
        if (count == 0) return emptyList()
        val clock = Clock(fps, duration)
        val pieces = ArrayList<List<FloatArray>>()
        for (i in 0 until count) pieces += clock.piece(count) { values ->
            // A quick turn to each end, held while the body swings out and settles.
            for (to in floatArrayOf(1f, 0f, -1f, 0f)) { clock.ramp(values, i, to, 0.15f); clock.hold(values, 0.9f) }
        }
        pieces += clock.piece(count) { values -> clock.drag(values, java.util.Random(13L), 8f) }
        pieces += clock.piece(count) { values -> clock.shake(values, java.util.Random(17L), 6f) }
        pieces += clock.piece(count) { values -> clock.swing(values, 8f) }
        pieces += clock.piece(count) { values -> clock.idle(values, java.util.Random(19L), 5f) }
        pieces += clock.piece(count) { values ->
            clock.drag(values, java.util.Random(23L), 3f); clock.shake(values, java.util.Random(29L), 3f); clock.drag(values, java.util.Random(31L), 3f)
        }
        return pieces
    }

    /** Held-out motion: dragging, shaking and idling as one piece, from other seeds. */
    fun heldOut(count: Int, fps: Int, duration: Float = 1f): List<FloatArray> {
        if (count == 0) return emptyList()
        val clock = Clock(fps, duration)
        return clock.piece(count) { values ->
            clock.drag(values, java.util.Random(101L), 4f)
            clock.shake(values, java.util.Random(103L), 3f)
            clock.idle(values, java.util.Random(107L), 3f)
        }
    }

    /** Frames of each piece as it is built; [piece] ends every piece back at rest. */
    private class Clock(val fps: Int, val duration: Float) {
        private var frames = ArrayList<FloatArray>()

        fun frames(seconds: Float) = (seconds * duration * fps).toInt().coerceAtLeast(1)

        fun piece(count: Int, build: (FloatArray) -> Unit): List<FloatArray> {
            frames = ArrayList()
            val values = FloatArray(count)
            build(values)
            // Back to rest together, then held while the body settles.
            val from = values.copyOf(); val n = frames(0.25f)
            for (f in 1..n) { val t = f.toFloat() / n; val e = 1f - t * t * (3f - 2f * t); for (i in 0 until count) values[i] = from[i] * e; emit(values) }
            hold(values, 1.2f)
            return List(count) { i -> FloatArray(frames.size) { frames[it][i] } }
        }

        fun emit(values: FloatArray) { frames += values.copyOf() }

        fun hold(values: FloatArray, seconds: Float) = repeat(frames(seconds)) { emit(values) }

        /** Eases input [i] to [to] over [seconds]. */
        fun ramp(values: FloatArray, i: Int, to: Float, seconds: Float) {
            val from = values[i]; val n = frames(seconds)
            for (f in 1..n) { val t = f.toFloat() / n; values[i] = from + (to - from) * t * t * (3f - 2f * t); emit(values) }
        }

        /** Every input jumps to a new pose every 0.3-1.2 s over 0.1-0.3 s, like a head dragged with the mouse. */
        fun drag(values: FloatArray, random: java.util.Random, seconds: Float) {
            val count = values.size
            val from = FloatArray(count); val to = FloatArray(count)
            val start = IntArray(count); val length = IntArray(count); val next = IntArray(count) { frames(random.nextFloat() * 0.3f) }
            val end = frames(seconds)
            for (f in 0 until end) {
                for (i in 0 until count) {
                    if (f == next[i]) {
                        from[i] = values[i]; to[i] = random.nextFloat() * 2f - 1f; start[i] = f
                        length[i] = frames(0.1f + random.nextFloat() * 0.2f)
                        next[i] = f + frames(0.3f + random.nextFloat() * 0.9f)
                    }
                    if (length[i] > 0) {
                        val t = ((f - start[i] + 1).toFloat() / length[i]).coerceIn(0f, 1f)
                        values[i] = from[i] + (to[i] - from[i]) * t * t * (3f - 2f * t)
                    }
                }
                emit(values)
            }
        }

        /** Every input swung side to side at 0.7-2 Hz and 0.5-1 of its range, fading in and out. */
        fun shake(values: FloatArray, random: java.util.Random, seconds: Float) {
            val count = values.size
            val hz = DoubleArray(count) { 0.7 + random.nextDouble() * 1.3 }
            val size = FloatArray(count) { 0.5f + random.nextFloat() * 0.5f }
            val phase = DoubleArray(count) { random.nextDouble() * 2 * PI }
            val base = values.copyOf()
            blend(values, base, seconds) { i, t -> size[i] * sin(2 * PI * hz[i] * t + phase[i]).toFloat() }
        }

        /**
         * Every input flung from end to end over 0.12 s, turning back ever sooner - from 0.6 to 2 swings a
         * second - so the body is driven through its resonance as hard as a model is ever shaken.
         */
        fun swing(values: FloatArray, seconds: Float) {
            val end = frames(seconds); val flip = frames(0.12f).toFloat()
            var side = 1f; var at = 0; var from = values.copyOf()
            var f = 0
            while (f < end) {
                val hz = 0.6f + 1.4f * f / end
                val half = frames(0.5f / hz)
                if (f - at >= half) { side = -side; at = f; from = values.copyOf() }
                val t = ((f - at + 1) / flip).coerceAtMost(1f).let { it * it * (3f - 2f * it) }
                for (i in values.indices) values[i] = from[i] + ((if (i % 2 == 0) side else -side) - from[i]) * t
                emit(values)
                f++
            }
        }

        /** Slow small swaying: two sines per input at 0.2-0.5 Hz within a third of the range. */
        fun idle(values: FloatArray, random: java.util.Random, seconds: Float) {
            val count = values.size
            val waves = List(count) { List(2) { Triple(0.2 + random.nextDouble() * 0.3, random.nextDouble() * 2 * PI, 0.1f + random.nextFloat() * 0.07f) } }
            val base = values.copyOf()
            blend(values, base, seconds) { i, t -> waves[i].sumOf { (hz, phase, a) -> a * sin(2 * PI * hz * t + phase) }.toFloat() }
        }

        /** [wave] around 0 for [seconds], faded in from [base] and out to 0 over the first and last 0.4 s. */
        private fun blend(values: FloatArray, base: FloatArray, seconds: Float, wave: (Int, Double) -> Float) {
            val n = frames(seconds); val fade = frames(0.4f).toFloat()
            for (f in 0 until n) {
                val t = f.toDouble() / fps / duration
                val into = (f / fade).coerceAtMost(1f).let { it * it * (3f - 2f * it) }
                val out = ((n - 1 - f) / fade).coerceAtMost(1f).let { it * it * (3f - 2f * it) }
                for (i in values.indices) values[i] = (base[i] * (1f - into) + wave(i, t) * into * out).coerceIn(-1f, 1f)
                emit(values)
            }
        }
    }
}
