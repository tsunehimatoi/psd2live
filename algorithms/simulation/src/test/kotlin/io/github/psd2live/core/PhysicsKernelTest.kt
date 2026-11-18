package io.github.psd2live.core

import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PhysicsKernelTest {
    @Test fun multipleSegmentsMatchTheEngineAcrossInterpolatedStepsAndResets() {
        val ranges = listOf("tilt", "drag", "middle", "tip").associateWith { PhysicsEngine.Range(-30f, 30f, 0f) }
        val rule = RigPhysicsEdit(
            "chain", "Chain",
            inputs = listOf(PhysicsInput("tilt"), PhysicsInput("drag", type = PhysicsSourceType.X)),
            outputs = listOf(PhysicsOutput("middle", 2, 3f), PhysicsOutput("tip", 3, 2f)),
            segments = List(3) { PhysicsSegment(10f - it, .8f, .9f, 1.2f) },
        )
        val engine = PhysicsEngine(listOf(rule), ranges)
        val kernel = PhysicsKernel(listOf(rule), ranges)
        val out = FloatArray(kernel.outputs.size)
        repeat(360) { frame ->
            if (frame % 120 == 0) {
                engine.reset()
                kernel.reset()
            }
            val pose = mapOf("tilt" to 20f * sin(frame * .07f), "drag" to 16f * sin(frame * .11f))
            val values = FloatArray(kernel.parameters.size) { pose[kernel.parameters[it]] ?: kernel.defaults[it] }
            val dt = if (frame % 3 == 0) 1f / 120f else 1f / 45f
            val expected = engine.step(pose, dt)
            assertTrue(kernel.step(values, dt, out))
            for (i in out.indices) assertEquals(expected.getValue(kernel.outputs[i]).toRawBits(), out[i].toRawBits(), "frame $frame")
        }
    }
}
