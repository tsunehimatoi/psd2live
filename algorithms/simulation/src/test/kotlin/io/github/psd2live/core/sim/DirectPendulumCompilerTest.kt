package io.github.psd2live.core.sim

import io.github.psd2live.core.PhysicsEngine
import io.github.psd2live.core.PhysicsInput
import io.github.psd2live.core.PhysicsKernel
import io.github.psd2live.core.PhysicsSourceType
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DirectPendulumCompilerTest {
    @Test
    fun inverseRealizesTheRequestedPeriodAndDampingAtSeveralPhysicsRates() {
        val frequency = 1.4f
        val damping = 0.19f
        for (fps in listOf(24, 60, 120)) {
            val dt = 1.0 / fps
            val segment = DirectPendulumCompiler.segment(frequency, damping, dt)
            val t = dt * 30.0 * segment.delay
            val gain = 1.0 / (1.0 + segment.acceleration * t * t / segment.length)
            val radius = sqrt(gain * segment.mobility)
            val theta = acos((gain * (1.0 + segment.mobility)) / (2.0 * radius))
            val decay = -ln(radius) / dt
            val damped = theta / dt
            val natural = sqrt(decay * decay + damped * damped)
            assertEquals(frequency.toDouble(), natural / (2 * PI), 1e-5)
            assertEquals(damping.toDouble(), decay / natural, 1e-5)
            assertEquals(0.0, DirectPendulumCompiler.translationGain(segment, 0.0, dt))
            assertTrue(DirectPendulumCompiler.translationGain(segment, frequency.toDouble(), dt) > 1.0)
        }
    }

    @Test
    fun finiteRampPeakMatchesTheIndependentDiscreteRecurrence() {
        for (fps in listOf(24, 60, 120)) {
            val dt = 1.0 / fps
            val segment = DirectPendulumCompiler.segment(1.4f, 0.19f, dt)
            val delay = dt * 30.0 * segment.delay
            val g = 1.0 / (1.0 + segment.acceleration * delay * delay / segment.length)
            val m = segment.mobility.toDouble()
            for (frames in listOf(1, 9, 37)) {
                var before = 0.0
                var previous = 0.0
                var oldInput = 0.0
                var previousInput = 0.0
                var measured = 0.0
                repeat(fps * 8) { n ->
                    val input = ((n + 1.0) / frames).coerceAtMost(1.0)
                    val output = g * ((1.0 + m) * previous - m * before +
                        input - (1.0 + m) * previousInput + m * oldInput)
                    measured = maxOf(measured, abs(output))
                    before = previous
                    previous = output
                    oldInput = previousInput
                    previousInput = input
                }
                val peak = DirectPendulumCompiler.rampPeak(segment, frames, dt)
                assertTrue(peak >= measured - 1e-8)
                assertEquals(measured, peak, 0.002)
            }
        }
    }

    @Test
    fun horizontalVerticalAndRotationalInputsDriveSeparateLaggingKeys() {
        val sideways = input("Side", 18f, 0f, 0f)
        val roll = input("Roll", 34f, 0f, 0.2f)
        val lift = input("Lift", 0f, 12f, 0f)
        val still = input("NoMotion", 0f, 0f, 0f)
        val inputs = listOf(sideways, roll, lift, still)
        val edit = RigSimEdit("skirt", "Skirt", SimKind.CLOTH, listOf("Mesh"),
            inputs = inputs.map { PhysicsInput(it.id) })
        val modes = listOf(
            DirectPhysicsMode("Sway", DirectMotionKind.SWAY, 1.1f, 0.2f),
            DirectPhysicsMode("Turn", DirectMotionKind.TURN, 0.9f, 0.18f),
            DirectPhysicsMode(SimGenerator.verticalParameterId(edit), DirectMotionKind.COMPRESSION, 2f, 0.25f),
        )
        assertTrue(modes.all { DirectPendulumCompiler.supported(edit, it.kind, inputs, 180f) })
        val groups = DirectPendulumCompiler.compile(edit, modes, inputs, 60, {}, bodyLength = 180f)
        assertEquals(listOf("Side", "Roll", "Lift"), groups.map { it.inputs.single().parameter })
        assertTrue(groups.all { it.inputs.single().type == PhysicsSourceType.X && it.inputs.single().reflect })
        assertEquals(listOf("PhysicsSim_skirt", "PhysicsSim_skirt_2", "PhysicsSim_skirt_y"), groups.map { it.id })
        assertTrue(groups.all { it.outputs.single().reflect && it.outputs.single().scale > 0f })
        val range = PhysicsEngine.Range(-1f, 1f, 0f)
        val outputRange = PhysicsEngine.Range(-SimGenerator.MODE_RANGE, SimGenerator.MODE_RANGE, 0f)
        val kernel = PhysicsKernel(groups, inputs.associate { it.id to range } +
            modes.associate { it.parameter to outputRange })
        val values = kernel.defaults.copyOf()
        val output = FloatArray(groups.size)
        val dt = 1f / 60f
        kernel.step(values, dt, output)
        values[kernel.indexOf("Lift")] = 0.4f
        kernel.step(values, dt, output)
        kernel.step(values, dt, output)
        assertTrue(abs(output[kernel.outputs.indexOf("Sway")]) < 1e-7f)
        assertTrue(abs(output[kernel.outputs.indexOf("Turn")]) < 1e-7f)
        assertTrue(output[kernel.outputs.indexOf(SimGenerator.verticalParameterId(edit))] < 0f)
    }

    @Test
    fun clothTurnRespondsToTheMeasuredTiltWithoutAmplifyingASidewaysWarp() {
        val warp = input("Warp", 18f, 0f, 0.13f, reshape = 15f)
        val smallRoll = input("Roll", 2f, 0f, 0.005f)
        val largeRoll = input("Roll", 25f, 0f, 0.27f)
        val edit = RigSimEdit("skirt", "Skirt", SimKind.CLOTH, listOf("Mesh"), inputs = listOf(
            PhysicsInput(warp.id, type = PhysicsSourceType.X),
            PhysicsInput(smallRoll.id, type = PhysicsSourceType.ANGLE),
        ))
        val mode = DirectPhysicsMode("Turn", DirectMotionKind.TURN, 1.1f, 0.2f)
        val drive = mutableListOf<Float>()
        fun group(input: SimKinematics.Input) =
            DirectPendulumCompiler.compile(edit, listOf(mode), listOf(warp, input), 60, {}, bodyLength = 180f,
                onDriveScale = { parameter, amount ->
                    assertEquals(mode.parameter, parameter)
                    drive += amount
                }).single()
        val weak = group(smallRoll)
        val strong = group(largeRoll)
        assertEquals(listOf("Roll"), weak.inputs.map { it.parameter })
        assertEquals(listOf("Roll"), strong.inputs.map { it.parameter })
        assertEquals(0.005f / 0.27f, weak.outputs.single().scale / strong.outputs.single().scale, 1e-4f)
        assertEquals(0.005f / 0.27f, drive[0] / drive[1], 1e-4f)
    }

    @Test
    fun hairRollHasARestoringTiltAndAnIndependentDelayedTail() {
        val roll = input("Roll", 9f, 0f, 0.2f)
        val side = input("Side", 20f, 0f, 0f)
        val inputs = listOf(roll, side)
        val edit = RigSimEdit("hair", "Hair", SimKind.HAIR, listOf("Mesh"), inputs = listOf(
            PhysicsInput(roll.id, type = PhysicsSourceType.ANGLE), PhysicsInput(side.id, type = PhysicsSourceType.X),
        ))
        val modes = listOf(
            DirectPhysicsMode("Sway", DirectMotionKind.SWAY, 1.5f, 0.18f),
            DirectPhysicsMode("RollLean", DirectMotionKind.TURN, 1.5f, 0.18f),
            DirectPhysicsMode("Tips", DirectMotionKind.FOLLOW_THROUGH, 1.5f, 0.18f),
        )
        val groups = DirectPendulumCompiler.compile(edit, modes, inputs, 60, {}, bodyLength = 180f)
        val (sway, turn, tail) = groups
        assertEquals(listOf("Roll"), turn.inputs.map { it.parameter })
        assertEquals(PhysicsSourceType.ANGLE, turn.inputs.single().type)
        assertTrue(!turn.inputs.single().reflect)
        assertEquals(listOf("Side"), sway.inputs.map { it.parameter })
        assertTrue(!DirectPendulumCompiler.supported(edit, DirectMotionKind.SWAY, listOf(roll), 180f))
        assertTrue(DirectPendulumCompiler.supported(edit, DirectMotionKind.TURN, listOf(roll), 180f))
        assertTrue(tail.inputs.any { it.parameter == roll.id && it.type == PhysicsSourceType.X })
        assertTrue(tail.segments.single().acceleration < sway.segments.single().acceleration)
        val range = PhysicsEngine.Range(-1f, 1f, 0f)
        val outputRange = PhysicsEngine.Range(-SimGenerator.MODE_RANGE, SimGenerator.MODE_RANGE, 0f)
        val kernel = PhysicsKernel(groups, inputs.associate { it.id to range } +
            modes.associate { it.parameter to outputRange })
        val values = kernel.defaults.copyOf()
        val output = FloatArray(groups.size)
        val dt = 1f / 60f
        repeat(4) { kernel.step(values, dt, output) }
        values[kernel.indexOf(roll.id)] = 0.5f
        var peak = 0f
        var tipPeak = 0f
        repeat(300) {
            kernel.step(values, dt, output)
            peak = maxOf(peak, abs(output[kernel.outputs.indexOf("RollLean")]))
            tipPeak = maxOf(tipPeak, abs(output[kernel.outputs.indexOf("Tips")]))
            assertEquals(0f, output[kernel.outputs.indexOf("Sway")], 1e-7f)
        }
        assertTrue(output[kernel.outputs.indexOf("RollLean")] < -1f)
        assertTrue(tipPeak > 0.5f)
        assertTrue(abs(output[kernel.outputs.indexOf("Tips")]) < 0.5f)
        assertTrue(peak < SimGenerator.MODE_RANGE * 0.90f)
        values[kernel.indexOf(roll.id)] = 0f
        repeat(300) { kernel.step(values, dt, output) }
        assertTrue(output.all { abs(it) < 0.5f })

        values[kernel.indexOf(side.id)] = 0.5f
        var swayPeak = 0f
        repeat(60) {
            kernel.step(values, dt, output)
            swayPeak = maxOf(swayPeak, abs(output[kernel.outputs.indexOf("Sway")]))
            assertTrue(abs(output[kernel.outputs.indexOf("RollLean")]) < 0.5f)
        }
        assertTrue(swayPeak > 0.5f)
    }

    @Test
    fun closedTiltEnvelopeMatchesTheIndependentDiscreteRecurrence() {
        for (fps in listOf(24, 60, 120)) {
            val dt = 1.0 / fps
            val segment = DirectPendulumCompiler.segment(1.4f, 0.19f, dt)
            val delay = dt * 30.0 * segment.delay
            val q = segment.acceleration * delay * delay / segment.length
            val g = 1.0 / (1.0 + q)
            val m = segment.mobility.toDouble()
            var before = 0.0
            var previous = 0.0
            var previousInput = 0.0
            var measured = 0.0
            repeat(fps * 8) {
                val output = g * ((1.0 + m) * previous - m * before +
                    q + (1.0 - previousInput) / PhysicsEngine.AIR_RESISTANCE)
                measured = maxOf(measured, abs(output))
                before = previous
                previous = output
                previousInput = 1.0
            }
            val envelope = DirectPendulumCompiler.tiltPeak(segment, dt)
            assertTrue(envelope >= measured - 1e-8)
            assertEquals(measured, envelope, 0.015)
        }
    }

    private fun input(id: String, x: Float, y: Float, turn: Float, reshape: Float = 0f): SimKinematics.Input =
        SimKinematics.Input(
            Parameter(ParameterId(id), id, -1f, 1f, 0f),
            -1f, 0f, 1f, floatArrayOf(-1f, 0f, 1f),
            arrayOf(floatArrayOf(-x, -y, -turn), floatArrayOf(0f, 0f, 0f), floatArrayOf(x, y, turn)),
            maxOf(abs(x), abs(y), abs(turn) * 180f), reshape, abs(x) >= abs(y),
        )
}
