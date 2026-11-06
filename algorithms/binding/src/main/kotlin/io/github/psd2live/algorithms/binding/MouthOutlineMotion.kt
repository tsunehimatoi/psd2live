package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.ModifierSourceBounds
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshDeltaForm
import org.umamo.runtime.model.ParameterId
import kotlin.math.abs
import kotlin.math.pow

/** Legacy mouth point motion in aligned canvas pixels. The fill and lip ribbon can use the same law. */
class MouthOutlineMotion(
    private val aperture: ModifierSourceBounds,
    curve: List<Pair<Float, Float>>,
    private val tuning: Tuning = Tuning(),
) {
    private val curve = curve.toList()

    init {
        require(this.curve.size == 7 && this.curve.first().first == 0f && this.curve.last().first == 1f)
        require(this.curve.all { (x, y) -> x.isFinite() && y.isFinite() && y in -0.5f..0.5f })
        require(this.curve.zipWithNext().all { (a, b) -> b.first - a.first >= 0.005f })
    }

    data class Tuning(
        val closedNarrow: Float = 8f,
        val formWiden: Float = 7f,
        val seam: Float = 48f,
        val smileBase: Float = 1.8f,
        val smile: Float = 10.5f,
    )

    fun point(x: Float, y: Float, formValue: Float, openValue: Float, exactClose: Boolean): Pair<Float, Float> {
        val open = openValue.coerceIn(0f, 1f)
        val easedOpen = open * open * (3f - 2f * open)
        val form = formValue.coerceIn(-1f, 1f)
        val width = aperture.right - aperture.left
        val height = aperture.bottom - aperture.top
        val centerX = (aperture.left + aperture.right) * 0.5f
        val halfWidth = (width * 0.5f).coerceAtLeast(1e-4f)
        val normalizedX = ((x - centerX) / halfWidth).coerceIn(-1.25f, 1.25f)
        val narrow = tuning.closedNarrow / 100f
        val horizontalScale = 1f - narrow + easedOpen * narrow + form * tuning.formWiden / 100f
        val targetX = centerX + (x - centerX) * horizontalScale
        val seamY = aperture.top + height * tuning.seam / 100f
        val closedScale = if (exactClose) 0f else (1.25f / height.coerceAtLeast(1f)).coerceIn(0.018f, 0.12f)
        val verticalScale = closedScale + easedOpen * (1f - closedScale)
        val cornerWeight = abs(normalizedX).toDouble().pow(1.55).toFloat().coerceAtMost(1.35f)
        val expressionY = -form * height * (tuning.smileBase + cornerWeight * tuning.smile) / 100f * (0.72f + easedOpen * 0.28f)
        val presetY = curveHeight((normalizedX + 1f) * 0.5f) * height * (1f - easedOpen)
        return targetX to (seamY + (y - seamY) * verticalScale + expressionY + presetY)
    }

    fun ribbonGrid(
        stroke: MouthOutlineStroke,
        frame: ModifierSourceBounds,
        mouthForm: ParameterId,
        mouthOpen: ParameterId,
        checkpoint: () -> Unit = {},
    ): KeyformGrid<MeshDeltaForm> {
        val formKeys = floatArrayOf(-1f, 0f, 1f)
        val openKeys = floatArrayOf(0f, 0.5f, 1f)
        val rest = stroke.parentPositions(frame)
        val cells = buildList {
            for ((f, form) in formKeys.withIndex()) for ((o, open) in openKeys.withIndex()) {
                checkpoint()
                val delta = stroke.delta(frame, rest) { x, y -> point(x, y, form, open, exactClose = true) }
                add(KeyformCell(intArrayOf(f, o), MeshDeltaForm(delta)))
            }
        }
        return KeyformGrid(listOf(KeyformAxis(mouthForm, formKeys), KeyformAxis(mouthOpen, openKeys)), cells)
    }

    private fun curveHeight(x: Float): Float {
        val target = x.coerceIn(0f, 1f)
        val start = if (target <= curve[3].first) 0 else 3
        fun component(t: Float, horizontal: Boolean): Float {
            val u = 1f - t
            fun value(i: Int) = curve[start + i].let { if (horizontal) it.first else it.second }
            return u * u * u * value(0) + 3f * u * u * t * value(1) +
                3f * u * t * t * value(2) + t * t * t * value(3)
        }
        var low = 0f
        var high = 1f
        repeat(20) {
            val t = (low + high) * 0.5f
            if (component(t, true) < target) low = t else high = t
        }
        return component((low + high) * 0.5f, false)
    }
}
