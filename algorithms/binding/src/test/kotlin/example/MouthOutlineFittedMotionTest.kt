package example

import io.github.psd2live.algorithms.binding.MouthColumn
import io.github.psd2live.algorithms.binding.MouthOutlineFittedMotion
import io.github.psd2live.algorithms.binding.MouthOutlineFittedMotion.Input
import io.github.psd2live.algorithms.binding.MouthOutlineFittedMotion.PathPose
import io.github.psd2live.algorithms.binding.MouthOutlineFittedMotion.RestChannels
import io.github.psd2live.algorithms.binding.MouthOutlineFittedMotion.ShapePose
import io.github.psd2live.algorithms.binding.MouthOutlineStroke
import io.github.psd2live.core.modifier.ModifierSourceBounds
import org.umamo.runtime.model.BlendShapeBinding
import org.umamo.runtime.model.BlendWeightLimit
import org.umamo.runtime.model.BlendWeightLimitPoint
import org.umamo.runtime.model.ColorRgb
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.ParameterId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class MouthOutlineFittedMotionTest {
    private val columns = List(25) { i -> (i + 3f).let { x -> MouthColumn(x to 2f, x to 6f) } }
    private val frame = ModifierSourceBounds(0f, 0f, 32f, 16f)
    private val motion = MouthOutlineFittedMotion(columns, 0, 1.5f, frame)
    private val channels = RestChannels(.8f, .2f, 300f, ColorRgb.MultiplyIdentity, ColorRgb.ScreenIdentity)

    @Test
    fun `fitted samples produce ribbon keyforms with capture limits and opacity`() {
        val open = ParameterId("ParamMouthOpenY")
        val vowel = ParameterId("ParamMouthA")
        val rest = PathPose(motion.sourcePath)
        val posed = PathPose(motion.sourcePath.map { (x, y) -> x to (y + x * .125f) })
        val axes = listOf(KeyformAxis(open, floatArrayOf(0f, 1f)))
        val geometry = KeyformGrid(axes, listOf(KeyformCell(intArrayOf(1), posed), KeyformCell(intArrayOf(0), rest)))
        val limits = listOf(BlendWeightLimit(open, listOf(BlendWeightLimitPoint(0f, 1f), BlendWeightLimitPoint(1f, 0f))))
        val shapes = listOf(BlendShapeBinding(vowel, floatArrayOf(-1f, 0f, 1f), 1,
            listOf(ShapePose(rest), null, ShapePose(posed, .5f)), limits))

        val result = motion.evaluate(Input(geometry, shapes), channels)

        val stroke = MouthOutlineStroke(columns, 0, 1.5f)
        val neutral = stroke.parentPositions(frame)
        val expected = stroke.delta(frame, neutral) { x, y -> x to (y + x * .125f) }
        assertContentEquals(intArrayOf(1), result.geometry.cells.first().coordinate)
        assertContentEquals(expected, result.geometry.cells.first().form.positionDeltas)
        assertContentEquals(FloatArray(neutral.size), result.geometry.cells.last().form.positionDeltas)
        val blend = result.shapes.single()
        assertEquals(limits, blend.limits)
        assertEquals(.2f, blend.forms[0]?.opacity)
        assertNull(blend.forms[1])
        assertEquals(.4f, blend.forms[2]?.opacity)
        assertContentEquals(expected, blend.forms[2]?.positionDeltas)
    }

    @Test
    fun `incomplete centerline samples cannot produce a grid`() {
        val truncated = PathPose(motion.sourcePath.dropLast(1))
        val geometry = KeyformGrid(emptyList(), listOf(KeyformCell(intArrayOf(), truncated)))
        assertFailsWith<IllegalArgumentException> { motion.geometry(geometry) }
    }
}
