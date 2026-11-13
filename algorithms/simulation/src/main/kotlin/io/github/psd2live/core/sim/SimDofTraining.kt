package io.github.psd2live.core.sim

import io.github.psd2live.core.PhysicsEngine
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sin

/**
 * Step 2 of a bake input by input ([SimBaker.Method.DOF]). Instead of one motion over every input at once, each input
 * that moves the body is driven on its own through a short designed motion - a step to each end of its span and
 * back, each held while the body settles, and a sweep from 0.25 to 3 Hz - so no input's answer is taken for
 * another's and each gets the weight that moves the body as it moves it; the held-out motion then moves them
 * together, as a model is moved.
 *
 * Which inputs take part, and in which group, is read off the rig ([SimKinematics]): an input that moves no goal
 * by [SimKinematics.MIN_MOTION_PX] is left out, and one goes to the up-and-down group when it moves the body's root
 * more up and down than sideways (or, reshaping it, its goals), so the two groups' keys never answer the same
 * motion twice.
 */
internal object SimDofTraining {
    /** The pieces each input is driven through: a step to each end of its span and back, and a sweep. */
    private enum class Drive { STEPS, SWEEP }
    private const val RAMP = 0.15f
    private const val HOLD_MIN = 0.4f
    private const val HOLD_MAX = 1.6f
    /** A hold ends once the fastest particle has stayed below this (px/s, or this share of the body's size per second) for [SETTLED]. */
    private const val SETTLE_SPEED = 3f
    private const val SETTLE_SHARE = 0.01f
    private const val SETTLED = 0.25f
    /** The sweep's rates (Hz) and how fast it climbs through them (e-folds per second). */
    private const val CHIRP_FROM = 0.25
    private const val CHIRP_TO = 3.0
    private const val CHIRP_RATE = 0.5
    /** Above this rate the sweep's reach shrinks as 1/f, so it moves the input no faster than a full swing at this rate. */
    private const val CHIRP_KNEE = 0.8
    /** The held-out motion's length as a share of [SimMotionLibrary.heldOut]'s. */
    private const val HELD_OUT = 0.6f
    /** How much more or less than another one piece may count: each input's motion alike, however far it moves the body. */
    private const val BALANCE = 100.0

    /**
     * The inputs of the sideways and of the up-and-down group; either empty when nothing drives it. An input in
     * [forced] takes part however little it moves the body.
     */
    fun groups(analysis: SimKinematics.Analysis, vertical: Boolean?, forced: Set<String> = emptySet()): Pair<List<SimKinematics.Input>, List<SimKinematics.Input>> {
        val length = analysis.length
        fun upAndDown(input: SimKinematics.Input) =
            if (input.rigid) abs(input.slope(SimKinematics.Y)) > abs(input.slope(SimKinematics.X)) + abs(input.slope(SimKinematics.TURN)) * length
            else !input.sideways
        val (up, side) = analysis.inputs.filter { it.moves || it.id in forced }.partition(::upAndDown)
        return side to (if (vertical == false) emptyList() else up)
    }

    /**
     * Each group's training: every input of either driven through its pieces on copies of the calibrated body
     * ([body]), each group's inputs together through the held-out motion, and the model's own motions to train on
     * ([SimBaker.Options.trainingMotions]), each in the group whose inputs it moves most. [progress] runs 0..1.
     */
    fun train(model: PuppetModel, groups: List<List<SimKinematics.Input>>, space: SimSpace, statics: List<SimBakedAxis>,
              body: () -> SimScene, check: () -> Unit, dt: Float, options: SimBaker.Options, progress: (Float) -> Unit): List<SimBaker.Training?> {
        val fps = options.fps
        /** Per input it moves, its value each frame; which group it trains when only one ([group] -1 for any input's own); how much it counts. */
        class Piece(val values: Map<SimKinematics.Input, FloatArray>, val residuals: List<FloatArray>, val group: Int = -1, val weight: Double = 1.0)
        val jobs = groups.flatten().distinctBy { it.id }.flatMap { input -> Drive.entries.map { input to it } }
        // A motion of the model's trains the group whose inputs it moves most, by how far it takes them over their spans.
        val motions = options.trainingMotions.mapNotNull { motion ->
            val reach = groups.map { group ->
                group.sumOf { input ->
                    val values = motion.values[input.id] ?: return@sumOf 0.0
                    (input.motion * values.maxOf { abs(it - input.rest) } / input.reach.coerceAtLeast(1e-6f)).toDouble()
                }
            }
            val g = reach.indices.maxByOrNull { reach[it] }?.takeIf { reach[it] > 0.0 } ?: return@mapNotNull null
            g to motion
        }
        val total = jobs.size + motions.size + groups.count { it.isNotEmpty() }
        val done = AtomicInteger()

        /** One input driven through [drive], its value each frame as an offset from rest. */
        fun piece(input: SimKinematics.Input, drive: Drive): Piece {
            val scene = body().also { it.reset(model, emptyMap()) }
            val s = scene.state
            val values = ArrayList<Float>(); val residuals = ArrayList<FloatArray>()
            val lastX = s.x.copyOf(); val lastY = s.y.copyOf()
            val settle = maxOf(SETTLE_SPEED, SETTLE_SHARE * extent(s))
            /** One frame with the input [v] from rest; returns the fastest particle's speed, px/s. */
            fun frame(v: Float): Float {
                if (values.size % 30 == 0) check()
                val pose = mapOf(input.parameter.id to input.rest + v)
                scene.drive(model, pose, dt)
                // An input that moves the body rigidly moves its keyform space with it: the rest pose's, turned.
                val r = if (input.rigid) space.residualRigid(scene, s.frameAngle) else space.residual(scene, pose) ?: FloatArray(space.size)
                for (axis in statics) pose[ParameterId(axis.parameter)]?.let { space.subtract(r, axis, it) }
                residuals += r
                values += v
                var fastest = 0f
                for (i in 0 until s.count) { fastest = maxOf(fastest, hypot(s.x[i] - lastX[i], s.y[i] - lastY[i])); lastX[i] = s.x[i]; lastY[i] = s.y[i] }
                return fastest / dt
            }
            fun ramp(to: Float) {
                val from = values.lastOrNull() ?: 0f
                val frames = (RAMP * fps).toInt().coerceAtLeast(1)
                for (f in 1..frames) { val t = f.toFloat() / frames; frame(from + (to - from) * t * t * (3f - 2f * t)) }
            }
            fun hold() {
                val v = values.lastOrNull() ?: 0f
                var calm = 0
                val least = (HOLD_MIN * fps).toInt(); val most = (HOLD_MAX * fps * options.duration).toInt().coerceAtLeast(least + 1)
                val settled = (SETTLED * fps).toInt()
                for (f in 0 until most) {
                    calm = if (frame(v) < settle) calm + 1 else 0
                    if (f >= least && calm >= settled) break
                }
            }
            val high = input.high - input.rest; val low = input.rest - input.low
            /** A logarithmic sweep from [from] to [to] Hz, faded in and out, its reach shrinking above the knee. */
            fun sweep(from: Double, to: Double) {
                val k = ln(to / from)
                val seconds = k / CHIRP_RATE * options.duration
                val frames = (seconds * fps).toInt().coerceAtLeast(2)
                for (f in 0 until frames) {
                    val t = f.toDouble() / fps
                    val hz = from * exp(k * t / seconds)
                    val wave = sin(2 * PI * from * seconds / k * (exp(k * t / seconds) - 1))
                    val fade = minOf(1.0, t / 0.3, (seconds - t) / 0.3).coerceAtLeast(0.0)
                    frame(((if (wave > 0) high else low) * minOf(1.0, CHIRP_KNEE / hz) * wave * fade).toFloat())
                }
                hold()
            }
            when (drive) {
                Drive.STEPS -> for (to in floatArrayOf(high, 0f, -low, 0f)) { ramp(to); hold() }
                Drive.SWEEP -> sweep(CHIRP_FROM, CHIRP_TO)
            }
            progress(done.incrementAndGet().toFloat() / total)
            return Piece(mapOf(input to FloatArray(values.size) { input.rest + values[it] }), residuals)
        }

        /** [motion] played on group [g]'s inputs, the others at rest. */
        fun motionPiece(g: Int, motion: SimBaker.TrainingMotion): Piece {
            val moved = groups[g].filter { it.id in motion.values }
            val values = moved.associateWith { input ->
                val p = input.parameter
                motion.values.getValue(input.id).let { track -> FloatArray(track.size) { track[it].coerceIn(p.min, p.max) } }
            }
            val scene = body().also { it.reset(model, emptyMap()) }
            val residuals = List(values.values.firstOrNull()?.size ?: 0) { f ->
                if (f % 30 == 0) check()
                val pose = values.entries.associate { (input, track) -> input.parameter.id to track[f] }
                scene.drive(model, pose, dt)
                val r = space.residual(scene, pose) ?: FloatArray(space.size)
                for (axis in statics) pose[ParameterId(axis.parameter)]?.let { space.subtract(r, axis, it) }
                r
            }
            progress(done.incrementAndGet().toFloat() / total)
            return Piece(values, residuals, g, motion.weight.toDouble())
        }

        /** A group's inputs moved together as a model is moved, within their training spans. */
        fun heldOut(group: List<SimKinematics.Input>): Pair<List<FloatArray>, List<FloatArray>> {
            val library = SimMotionLibrary.heldOut(group.size, fps, options.duration * HELD_OUT)
            val track = group.mapIndexed { i, input ->
                FloatArray(library[i].size) { f -> val u = library[i][f]; if (u >= 0f) input.rest + u * (input.high - input.rest) else input.rest + u * (input.rest - input.low) }
            }
            val scene = body().also { it.reset(model, emptyMap()) }
            val residuals = List(track.firstOrNull()?.size ?: 0) { f ->
                if (f % 30 == 0) check()
                val pose = group.indices.associate { group[it].parameter.id to track[it][f] }
                scene.drive(model, pose, dt)
                val r = space.residual(scene, pose) ?: FloatArray(space.size)
                for (axis in statics) pose[ParameterId(axis.parameter)]?.let { space.subtract(r, axis, it) }
                r
            }
            progress(done.incrementAndGet().toFloat() / total)
            return track to residuals
        }

        val heldOuts = arrayOfNulls<Pair<List<FloatArray>, List<FloatArray>>>(groups.size)
        val work: List<() -> Piece?> = jobs.map { (input, drive) -> { piece(input, drive) } } + motions.map { (g, motion) -> { motionPiece(g, motion) } } +
            groups.indices.filter { groups[it].isNotEmpty() }.map { g -> { heldOuts[g] = heldOut(groups[g]); null } }
        val pieces = (if (options.parallel) work.parallelStream() else work.stream()).map { it() }.toList().filterNotNull()

        return groups.mapIndexed { g, inputs ->
            if (inputs.isEmpty()) return@mapIndexed null
            val (heldOutTrack, heldOutResiduals) = requireNotNull(heldOuts[g])
            val own = pieces.filter { piece -> if (piece.group >= 0) piece.group == g else piece.values.keys.any { it in inputs } }
            // Per input its value over every piece of the group: the pieces that move it drive it, the others leave it at rest.
            val frames = own.sumOf { it.residuals.size }
            val track = inputs.map { input ->
                val out = FloatArray(frames) { input.rest }; var at = 0
                for (piece in own) {
                    piece.values[input]?.copyInto(out, at)
                    at += piece.residuals.size
                }
                out
            }
            SimBaker.Training(inputs.map { it.id }, PhysicsEngine.ranges(model.parameters), own.map { it.residuals }, track,
                heldOutTrack, heldOutResiduals, balance = BALANCE, pieceWeights = own.map { it.weight }.takeIf { weights -> weights.any { it != 1.0 } })
        }
    }

    /** The body's size: the larger side of its bounding box, px. */
    private fun extent(s: SimState): Float {
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (i in 0 until s.count) { minX = minOf(minX, s.x[i]); maxX = maxOf(maxX, s.x[i]); minY = minOf(minY, s.y[i]); maxY = maxOf(maxY, s.y[i]) }
        return maxOf(maxX - minX, maxY - minY, 0f)
    }
}
