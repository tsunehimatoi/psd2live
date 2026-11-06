package example

import io.github.psd2live.algorithms.binding.*
import org.umamo.runtime.model.ParameterId
import kotlin.test.*

class BindingMotionKernelsTest {
    @Test fun headAndChestKeepNeutralPoseAndRigidAttachments() {
        val turn = HeadTurn(50f, 50f, 40f, 45f)
        val neutral = turn.delta(45f, 40f, HeadShell.SKIN, 0f, 0f)
        assertEquals(0f, neutral.first, 0f)
        assertEquals(0f, neutral.second, 0f)
        val points = floatArrayOf(45f, 40f, 55f, 40f)
        val frame = Bounds(0f, 0f, 100f, 100f)
        val shapes = turn.shapes(points, frame, HeadShell.RIGID, ParameterId("x"), ParameterId("y"))
        for (shape in shapes) for (form in shape.forms.filterNotNull()) {
            assertEquals(form.positionDeltas[0], form.positionDeltas[2])
            assertEquals(form.positionDeltas[1], form.positionDeltas[3])
        }
        val chest = BustRig(50f, 0f, 100f, 40f)
        val pinned = chest.shapes(floatArrayOf(45f, 33f, 55f, 33f), frame,
            ParameterId("bustY"), ParameterId("bustX"), rigid = true)
        assertEquals(2, pinned.size)
        pinned.flatMap { it.forms.filterNotNull() }.forEach {
            assertEquals(it.positionDeltas[0], it.positionDeltas[2])
            assertEquals(it.positionDeltas[1], it.positionDeltas[3])
        }
        assertTrue(chest.shapes(floatArrayOf(500f, 500f), frame, ParameterId("y"), ParameterId("x")).isEmpty())
    }

    @Test fun interpolationPreservesAffineMapsAndFixedNodes() {
        val points = listOf(0f to 0f, 1f to 0f, 0f to 1f, 1f to 1f)
        val spline = ThinPlateSpline(points, points.map { (x, y) -> (2f * x + 3f) to (y - x) })
        val mapped = spline.map(.25f, .75f)
        assertEquals(3.5f, mapped.first, 1e-5f)
        assertEquals(.5f, mapped.second, 1e-5f)
        val field = BiharmonicField(7, 7, 0f, 0f, 1f, 1f, mapOf(24 to (0f to 1f)))
        assertEquals(0f to 1f, field.at(.5f, .5f))
        assertEquals(0f to 0f, field.at(2f, 2f))
    }

    @Test fun allKernelsExposeCancellationToTheirCaller() {
        val cancel = { throw InterruptedException() }
        val frame = Bounds(0f, 0f, 100f, 100f)
        assertFailsWith<InterruptedException> {
            HeadTurn(50f, 50f, 40f, 45f, checkpoint = cancel).shapes(floatArrayOf(50f, 50f), frame,
                HeadShell.SKIN, ParameterId("x"), ParameterId("y"))
        }
        assertFailsWith<InterruptedException> {
            BustRig(50f, 0f, 100f, 40f, checkpoint = cancel).shapes(floatArrayOf(50f, 33f), frame,
                ParameterId("y"), ParameterId("x"))
        }
        assertFailsWith<InterruptedException> {
            ThinPlateSpline(listOf(0f to 0f, 1f to 0f, 0f to 1f), listOf(0f to 0f, 1f to 0f, 0f to 1f), checkpoint = cancel)
        }
        assertFailsWith<InterruptedException> { BiharmonicField(7, 7, 0f, 0f, 1f, 1f, emptyMap(), cancel) }
    }
}
