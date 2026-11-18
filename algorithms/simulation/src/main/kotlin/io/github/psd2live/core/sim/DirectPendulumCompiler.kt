package io.github.psd2live.core.sim

import io.github.psd2live.core.PhysicsEngine
import io.github.psd2live.core.PhysicsInput
import io.github.psd2live.core.PhysicsNormalization
import io.github.psd2live.core.PhysicsOutput
import io.github.psd2live.core.PhysicsSegment
import io.github.psd2live.core.PhysicsSourceType
import io.github.psd2live.core.RigPhysicsEdit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Compiles independent inertial deformation channels to Cubism pendulums using their discrete transfer function. */
internal object DirectPendulumCompiler {
    private const val MIN_TRAVEL = 0.5f
    private const val NOMINAL_LENGTH = 10f
    private const val TRANSLATION_INPUT_WEIGHT = 80f
    private const val ANGLE_INPUT_WEIGHT = 15f
    // The Cubism snap threshold is proportional to positionMax. Use a small normalization
    // interval with proportionally stronger links to keep the same physical carrier travel.
    private const val POSITION_RANGE = 1.875f
    private const val OUTPUT_HEADROOM = 0.86f
    private val normalization = PhysicsNormalization(
        positionMin = -POSITION_RANGE, positionMax = POSITION_RANGE, angleMin = -30f, angleMax = 30f,
    )

    private class Drive(val id: String, val travel: Float)

    fun supported(kind: DirectMotionKind, inputs: List<SimKinematics.Input>, bodyLength: Float): Boolean =
        inputs.any { abs(travel(kind, it, bodyLength)) >= MIN_TRAVEL }

    fun compile(
        edit: RigSimEdit,
        modes: List<DirectPhysicsMode>,
        inputs: List<SimKinematics.Input>,
        physicsFps: Int,
        checkpoint: () -> Unit,
        bodyLength: Float = 0f,
    ): List<RigPhysicsEdit> {
        val fps = if (physicsFps > 0) physicsFps else 60
        val step = 1.0 / fps
        val base = SimGenerator.physicsId(edit)
        val angularInputs = if (modes.any { it.kind == DirectMotionKind.TURN }) inputs.filter { input ->
            edit.inputs.any { it.parameter == input.id && it.type == PhysicsSourceType.ANGLE } &&
                abs(travel(DirectMotionKind.TURN, input, bodyLength)) >= MIN_TRAVEL
        }.mapTo(hashSetOf()) { it.id } else emptySet()
        var side = 0
        return modes.mapNotNull { mode ->
            checkpoint()
            val lean = edit.kind == SimKind.HAIR && mode.kind == DirectMotionKind.TURN && angularInputs.isNotEmpty()
            val drives = inputs.mapNotNull drive@{ input ->
                if (lean && input.id !in angularInputs) return@drive null
                val turnCarrier = input.id in angularInputs &&
                    (mode.kind == DirectMotionKind.SWAY || mode.kind == DirectMotionKind.FOLLOW_THROUGH)
                val travel = if (turnCarrier) {
                    if (edit.kind != SimKind.HAIR) return@drive null
                    // Rotation also accelerates the hanging length sideways. The dedicated turn keeps the
                    // lean about the root; a portion of the angular lever arm drives loose follow-through.
                    val contribution = if (mode.kind == DirectMotionKind.FOLLOW_THROUGH) 0.46f else 0.09f
                    travel(DirectMotionKind.TURN, input, bodyLength) * contribution
                } else travel(mode.kind, input, bodyLength)
                when {
                    abs(travel) >= MIN_TRAVEL -> Drive(input.id, travel)
                    input.id in edit.forcedInputs && forcedChannel(mode.kind, input, edit) ->
                        Drive(input.id, if (travel < 0f) -MIN_TRAVEL else MIN_TRAVEL)
                    else -> null
                }
            }.sortedByDescending { abs(it.travel) }.take(RigPhysicsEdit.MAX_LINKS)
            if (drives.isEmpty()) return@mapNotNull null
            val amount = drives.sumOf { abs(it.travel).toDouble() }
            val type = if (lean) PhysicsSourceType.ANGLE else PhysicsSourceType.X
            // Gravity restores hair toward vertical as the head is held in a tilt. Cloth turn,
            // sway and compression use X as an inertial carrier and settle back at held inputs.
            val weightBudget = if (lean) ANGLE_INPUT_WEIGHT else TRANSLATION_INPUT_WEIGHT
            val links = drives.map { d -> PhysicsInput(d.id, (abs(d.travel) / amount * weightBudget).toFloat(),
                type, reflect = (d.travel > 0f) != lean) }
            val frequencyScale = when (mode.kind) {
                DirectMotionKind.FOLLOW_THROUGH -> 0.74f
                DirectMotionKind.COMPRESSION -> 1.24f
                DirectMotionKind.TURN -> if (lean) 1f else 1.15f
                DirectMotionKind.SWAY -> if (edit.kind == SimKind.HAIR) 1.08f else 1f
            }
            val dampingScale = when (mode.kind) {
                DirectMotionKind.FOLLOW_THROUGH -> 0.78f
                DirectMotionKind.COMPRESSION -> 0.93f
                DirectMotionKind.TURN -> 0.88f
                DirectMotionKind.SWAY -> 1f
            }
            val frequency = mode.frequencyHz * frequencyScale
            val segment = segment(frequency, mode.dampingRatio * dampingScale, step)
            val rangeScale = scale(segment, frequency, step, links, edit.kind, mode.kind)
            val id = if (mode.parameter == SimGenerator.verticalParameterId(edit)) SimGenerator.verticalPhysicsId(edit) else {
                side++
                if (side == 1) base else "${base}_$side"
            }
            RigPhysicsEdit(id, edit.name, inputs = links,
                // Reflect the angle so the deformation lags behind a positive carrier advance.
                outputs = listOf(PhysicsOutput(mode.parameter, scale = rangeScale, reflect = true)),
                segments = listOf(segment), normalization = normalization)
        }
    }

    private fun forcedChannel(kind: DirectMotionKind, input: SimKinematics.Input, edit: RigSimEdit): Boolean {
        val link = edit.inputs.firstOrNull { it.parameter == input.id }
        return when {
            input.id in RigSimEdit.VERTICAL_INPUTS -> kind == DirectMotionKind.COMPRESSION
            link?.type == PhysicsSourceType.ANGLE -> kind == DirectMotionKind.TURN
            else -> kind == DirectMotionKind.SWAY || kind == DirectMotionKind.FOLLOW_THROUGH
        }
    }

    private fun travel(kind: DirectMotionKind, input: SimKinematics.Input, bodyLength: Float): Float {
        if (!input.moves) return 0f
        fun acrossRange(axis: Int): Float {
            val slope = input.slope(axis) * input.halfRange
            if (abs(slope) > 1e-4f) return slope
            val positive = input.root(input.high, axis)
            val negative = -input.root(input.low, axis)
            return if (abs(positive) >= abs(negative)) positive else negative
        }
        val x = acrossRange(SimKinematics.X)
        val y = acrossRange(SimKinematics.Y)
        val angle = acrossRange(SimKinematics.TURN)
        val turn = angle * maxOf(1f, bodyLength, input.motion)
        val lateral = abs(x)
        val vertical = abs(y)
        val rotation = abs(turn)
        // A held warping input can move the cloth without carrying its pins. In that case retain its
        // signed root component when measurable; the direction of the net warp disambiguates X/Y.
        fun withReshape(value: Float, alongInput: Boolean): Float {
            if (input.rigid || !alongInput) return value
            val sign = if (value < 0f) -1f else 1f
            return maxOf(abs(value), input.reshape) * sign
        }
        fun sway() = if (lateral >= maxOf(vertical, rotation) * 0.42f ||
            !input.rigid && input.sideways && rotation < input.motion * 0.8f) withReshape(x, input.sideways) else 0f
        fun turning() = if (abs(angle) >= 0.002f && rotation >= maxOf(lateral, vertical) * 0.26f) turn else 0f
        return when (kind) {
            DirectMotionKind.SWAY -> sway()
            DirectMotionKind.TURN -> turning()
            DirectMotionKind.COMPRESSION ->
                if (vertical >= maxOf(lateral, rotation) * 0.48f ||
                    !input.rigid && !input.sideways && rotation < input.motion * 0.8f) withReshape(y, !input.sideways) else 0f
            DirectMotionKind.FOLLOW_THROUGH -> sway().takeIf { abs(it) >= MIN_TRAVEL } ?: turning()
        }
    }

    /**
     * In a straight hanging single segment the coefficients of the undriven recurrence are
     * G(1 + mobility), -G mobility; G = 1 / (1 + acceleration * (30 h delay)^2 / length).
     * Match a conjugate pole pair (radius r, angle theta) by setting G = 2 r cos(theta) - r^2.
     * Project modes requiring mobility above one to the closest attainable radius at that frequency.
     */
    internal fun segment(frequencyHz: Float, dampingRatio: Float, step: Double): PhysicsSegment {
        val damping = dampingRatio.coerceIn(0.055f, 0.9f).toDouble()
        val angular = 2.0 * PI * frequencyHz.coerceIn(0.18f, 8f)
        val theta = (angular * sqrt(1.0 - damping * damping) * step).coerceAtMost(0.68)
        val cosine = cos(theta)
        val radius = exp(-damping * angular * step).coerceIn(0.1, cosine - 1e-5)
        val gain = 2.0 * radius * cosine - radius * radius
        val delay = 1f
        return PhysicsSegment(NOMINAL_LENGTH, (radius * radius / gain).toFloat(), delay,
            ((1.0 / gain - 1.0) * NOMINAL_LENGTH / ((30.0 * step) * (30.0 * step))).toFloat())
    }

    private fun scale(
        segment: PhysicsSegment,
        frequencyHz: Float,
        step: Double,
        links: List<PhysicsInput>,
        kind: SimKind,
        motion: DirectMotionKind,
    ): Float {
        val lean = links.first().type == PhysicsSourceType.ANGLE
        val response = RootResponse(segment, step, lean)
        val frequencies = doubleArrayOf(0.45, 0.75, 1.25, 1.8, 2.5,
            frequencyHz * 0.87, frequencyHz.toDouble(), frequencyHz * 1.12)
        val steady = frequencies.maxOf { f ->
            response.gain(f.coerceIn(0.0, 0.48 / step), step) * (1.8 / f).coerceAtMost(1.0)
        }
        // A quick gesture is a finite movement, with less buildup than steady excitation at resonance.
        // Use the closed-form response of that movement. Reserve room for sustained inputs as well.
        val attack = if (kind == SimKind.HAIR) 0.13 else 0.16
        val sustained = when {
            motion == DirectMotionKind.SWAY && kind == SimKind.CLOTH -> 0.48
            motion == DirectMotionKind.SWAY -> 0.50
            motion == DirectMotionKind.FOLLOW_THROUGH -> 0.58
            kind == SimKind.HAIR -> 0.55
            else -> 0.34
        }
        val transient = if (lean) response.tiltPeak() else response.rampPeak((attack / step).roundToInt().coerceAtLeast(1))
        val peak = maxOf(transient, steady * sustained)
        val weightBudget = if (lean) ANGLE_INPUT_WEIGHT else TRANSLATION_INPUT_WEIGHT
        val effectiveWeight = maxOf(sqrt(links.sumOf { it.weight.toDouble() * it.weight }), weightBudget * 0.83)
        val carrier = if (lean) normalization.angleMax * (PI / 180.0) else normalization.positionMax / segment.length.toDouble()
        val normalizedTravel = carrier * effectiveWeight / 100.0
        return ((SimGenerator.MODE_RANGE * OUTPUT_HEADROOM) / (peak * normalizedTravel).coerceAtLeast(0.03)).toFloat()
    }

    internal fun translationGain(segment: PhysicsSegment, frequency: Double, step: Double): Double =
        RootResponse(segment, step).gain(frequency, step)

    internal fun rampPeak(segment: PhysicsSegment, frameCount: Int, step: Double): Double =
        RootResponse(segment, step).rampPeak(frameCount)

    internal fun tiltPeak(segment: PhysicsSegment, step: Double): Double =
        RootResponse(segment, step, lean = true).tiltPeak()

    /** Linearized single-pendulum angle under translation (in segment lengths) or gravity tilt (in radians). */
    private class RootResponse(segment: PhysicsSegment, step: Double, private val lean: Boolean = false) {
        private val mobility = segment.mobility.toDouble()
        private val delay = 30.0 * step * segment.delay
        private val gravity = segment.acceleration * delay * delay / segment.length
        private val normalizer = 1.0 / (1.0 + gravity)
        private val gravityDifference = 1.0 / PhysicsEngine.AIR_RESISTANCE
        private val radius get() = sqrt(b)
        private val angle get() = acos((a / (2.0 * radius)).coerceIn(-1.0, 1.0))
        private val a = normalizer * (1.0 + mobility)
        private val b = normalizer * mobility

        /**
         * With z^-1 = d, the numerator is G(1-d)(1-mobility*d) and the denominator is
         * 1-G(1+mobility)*d+G*mobility*d^2. The zero at DC removes extra sway from held poses.
         */
        fun gain(frequency: Double, step: Double): Double {
            val phase = 2.0 * PI * frequency * step
            val cosine = cos(phase)
            val sine = sin(phase)
            val numerator = normalizer * if (lean) {
                hypot(gravity + gravityDifference * (1.0 - cosine), gravityDifference * sine)
            } else {
                hypot(1.0 - cosine, sine) * hypot(1.0 - mobility * cosine, mobility * sine)
            }
            val denominator = hypot(1.0 - a * cosine + b * cos(2.0 * phase), a * sine - b * sin(2.0 * phase))
            return numerator / denominator
        }

        /** A held gravity tilt has unit DC gain. Its step envelope bounds repeated ramp impulses. */
        fun tiltPeak(): Double {
            val phase = angle
            val decay = ln(radius)
            val first = normalizer * (gravity + gravityDifference)
            val second = a * first + normalizer * gravity
            val real = first - 1.0
            val imaginary = -(second - 1.0 - real * radius * cos(phase)) / (radius * sin(phase))
            return peak(1.0, real, imaginary, phase, decay, Double.POSITIVE_INFINITY)
        }

        /**
         * A movement over [steps] samples is the average of delayed unit steps. For pole p, its step
         * response is Re(c p^n). Sum the geometric series during and after the movement and test
         * their analytic extrema. The work stays constant as the sample rate and duration change.
         */
        fun rampPeak(steps: Int): Double {
            val radius = sqrt(b)
            val angle = acos((a / (2.0 * radius)).coerceIn(-1.0, 1.0))
            val logRadius = ln(radius)
            val poleReal = radius * cos(angle)
            val poleImaginary = radius * sin(angle)
            val cReal = normalizer
            val cImaginary = -normalizer * (poleReal - mobility) / poleImaginary
            val remaining = 1.0 - poleReal
            val divisor = (remaining * remaining + poleImaginary * poleImaginary) * steps
            val baseReal = (cReal * remaining - cImaginary * poleImaginary) / divisor
            val baseImaginary = (cImaginary * remaining + cReal * poleImaginary) / divisor
            val endDecay = exp(logRadius * steps)
            val endReal = 1.0 - endDecay * cos(angle * steps)
            val endImaginary = -endDecay * sin(angle * steps)
            val tailReal = baseReal * endReal - baseImaginary * endImaginary
            val tailImaginary = baseImaginary * endReal + baseReal * endImaginary
            return maxOf(
                peak(baseReal, -baseReal, -baseImaginary, angle, logRadius, steps.toDouble()),
                peak(0.0, tailReal, tailImaginary, angle, logRadius, Double.POSITIVE_INFINITY),
            )
        }

        private fun peak(center: Double, real: Double, imaginary: Double, angle: Double, decay: Double, end: Double): Double {
            fun at(t: Double): Double =
                abs(center + exp(decay * t) * (real * cos(angle * t) - imaginary * sin(angle * t)))

            val phase = atan2(imaginary, real)
            val stationary = (atan(decay / angle) - phase) % PI
            val first = (if (stationary < 0.0) stationary + PI else stationary) / angle
            val halfTurn = PI / angle
            // Successive extrema alternate sides of the center with decreasing amplitude.
            return maxOf(at(0.0), at(first.coerceAtMost(end)), at((first + halfTurn).coerceAtMost(end)),
                if (end.isFinite()) at(end) else abs(center))
        }
    }
}
