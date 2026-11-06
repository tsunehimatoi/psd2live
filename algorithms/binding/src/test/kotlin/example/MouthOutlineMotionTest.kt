package example

import io.github.psd2live.algorithms.binding.MouthColumn
import io.github.psd2live.algorithms.binding.MouthOutlineMotion
import io.github.psd2live.algorithms.binding.MouthOutlineStroke
import io.github.psd2live.core.modifier.ModifierSourceBounds
import org.umamo.runtime.model.ParameterId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class MouthOutlineMotionTest {
    @Test
    fun `legacy outline shares one closed seam and preserves the fully open drawing`() {
        val frame = ModifierSourceBounds(0f, 0f, 32f, 16f)
        val motion = MouthOutlineMotion(frame, List(7) { i -> i / 6f to if (i in 1..5) .1f else 0f })
        val columns = List(25) { i ->
            val x = i + 3f
            MouthColumn(x to 2f, x to 6f)
        }
        val upper = MouthOutlineStroke(columns, 0, 1.5f)
        val lower = MouthOutlineStroke(columns, 1, 1.5f)
        val form = ParameterId("ParamMouthForm")
        val open = ParameterId("ParamMouthOpenY")
        val upperGrid = motion.ribbonGrid(upper, frame, form, open)
        val lowerGrid = motion.ribbonGrid(lower, frame, form, open)
        assertEquals(listOf(form, open), upperGrid.axes.map { it.parameterId })
        assertEquals(9, upperGrid.cells.size)
        val upperRest = upper.parentPositions(frame)
        val lowerRest = lower.parentPositions(frame)
        for (f in 0..2) {
            val upperDelta = upperGrid.cells[f * 3].form.positionDeltas
            val lowerDelta = lowerGrid.cells[f * 3].form.positionDeltas
            for (i in upperRest.indices) {
                assertEquals(upperRest[i] + upperDelta[i], lowerRest[i] + lowerDelta[i], 1e-6f)
            }
        }
        assertContentEquals(FloatArray(upperRest.size), upperGrid.cells[5].form.positionDeltas)
        assertNotEquals(motion.point(12f, 2f, 0f, 0f, exactClose = false),
            motion.point(12f, 6f, 0f, 0f, exactClose = false))
    }
}
