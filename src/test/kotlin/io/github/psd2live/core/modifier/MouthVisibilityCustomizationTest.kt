package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.BindingAxes
import org.umamo.runtime.eval.scalarAt
import org.umamo.runtime.model.*
import kotlin.test.*

class MouthVisibilityCustomizationTest {
    private val open = ClosedMouthVisibility.PARAMETER
    private val press = ParameterId("Press")
    private val curve = ClosedMouthVisibility.Default.move(.05f, .25f).insert(.2f).setOpacity(.2f, 1f)
    private val defaults = mapOf(open to 0f, press to 0f)

    private fun drawable(closed: Boolean, pressed: Boolean = false): Drawable {
        val alpha = if (closed) .4f else .65f
        val axes = ClosedMouthVisibility.Default.grid().axes +
            if (pressed) listOf(KeyformAxis(press, floatArrayOf(0f, 1f))) else emptyList()
        val grid = KeyformGrid(axes, BindingAxes.coordinates(axes).map { at ->
            val pose = BindingAxes.key(axes, at)
            val c = ClosedMouthVisibility.Default.sample(pose.getValue(open))
            val value = if (pose[press] == 1f) { if (closed) alpha else 0f } else if (closed) c else 1f - c
            KeyformCell<ChannelValue>(at, ChannelValue.Scalar(value))
        })
        val neutral = if (closed) 1f else 0f
        val opening = if (closed) 0f else alpha
        return Drawable(DrawableId("art"), "Art", null, BlendMode.Normal, emptyList(), null, null,
            ChannelGrids(mapOf(FormChannel.OPACITY to grid)), opacity = alpha,
            blendShapes = listOf(BlendShapeBinding(ParameterId("Vowel"), floatArrayOf(0f, .5f, 1f), 0,
                listOf(null, MeshForm(floatArrayOf(1f, 2f), opacity = neutral), MeshForm(floatArrayOf(3f, 4f), opacity = opening)))))
    }

    private fun alpha(drawable: Drawable, value: Float, pressed: Float = 0f) =
        drawable.channelGrids.scalarAt(FormChannel.OPACITY, drawable.opacity, { if (it == open) value else pressed })

    @Test fun keepsCaptureOpacityAxesAndOriginalAlpha() {
        for (closed in listOf(true, false)) {
            val before = drawable(closed, pressed = true)
            val after = MouthVisibilityCustomization.customize(before, curve, closed, before.opacity, defaults)
            assertEquals(listOf(open, press), after.channelGrids[FormChannel.OPACITY]!!.axes.map { it.parameterId })
            for (value in listOf(0f, .05f, .2f, .225f, .25f, .5f, 1f)) {
                val visible = if (closed) curve.sample(value) else 1f - curve.sample(value)
                val forced = if (closed) before.opacity else 0f
                assertEquals(before.opacity * visible, alpha(after, value), .0001f)
                assertEquals(forced, alpha(after, value, 1f), .0001f)
                assertEquals((before.opacity * visible + forced) / 2, alpha(after, value, .5f), .0001f)
            }
        }
    }

    @Test fun rebasesBlendOpacityWhenTheNeutralVisibilityChangesWithoutTouchingGeometry() {
        for (closed in listOf(true, false)) {
            val before = drawable(closed)
            val after = MouthVisibilityCustomization.customize(before, curve.setOpacity(0f, .5f), closed, before.opacity, defaults)
            val forms = after.blendShapes.single().forms
            assertEquals(before.opacity / 2, forms[1]!!.opacity)
            assertEquals(if (closed) 0f else before.opacity, forms[2]!!.opacity)
            before.blendShapes.single().forms.zip(forms).forEach { (a, b) ->
                if (a != null) assertContentEquals(a.positionDeltas, b!!.positionDeltas)
            }
        }
    }

    @Test fun movingEndpointsHoldsOpacityOutsideTheAuthoredInterval() {
        val trimmed = ClosedMouthVisibility(listOf(ClosedMouthVisibility.Point(.2f, .8f), ClosedMouthVisibility.Point(.3f, .1f)))
        for (closed in listOf(true, false)) {
            val before = drawable(closed)
            val after = MouthVisibilityCustomization.customize(before, trimmed, closed, before.opacity, defaults)
            for ((value, expected) in listOf(0f to .8f, .2f to .8f, .25f to .45f, .3f to .1f, 1f to .1f)) {
                assertEquals(expected, trimmed.sample(value), .0001f)
                assertEquals(before.opacity * if (closed) expected else 1f - expected, alpha(after, value), .0001f)
            }
        }
    }
}
