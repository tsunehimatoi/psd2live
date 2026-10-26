package io.github.psd2live.core.fit

import io.github.psd2live.core.Bounds
import org.umamo.runtime.model.BlendShapeBinding
import org.umamo.runtime.model.MeshForm
import org.umamo.runtime.model.ParameterId

/**
 * The chest's bounce (`body.bust`): a soft round region of the upper torso - centred a third of the way from the
 * shoulders to the waist, most of the torso's width across - lifts and settles on Bust Y and sways on Bust X, fading
 * to nothing at its edge so the clothes around it stay put. The torso runs from [shoulderY] to [waistY] about
 * [centerX], [halfWidth] to each side, in the rig's space; [amount] scales the bounce.
 */
class BustRig(private val centerX: Float, private val shoulderY: Float, private val waistY: Float, private val halfWidth: Float,
              private val amount: Float = 1f) {
	private val length = (waistY - shoulderY).coerceAtLeast(1f)
	private val cy = shoulderY + length * 0.33f
	private val rx = halfWidth * 0.75f
	private val ry = length * 0.24f

	private fun weight(x: Float, y: Float): Float {
		val dx = (x - centerX) / rx; val dy = (y - cy) / ry
		val d = (dx * dx + dy * dy).coerceAtMost(1f)
		return (1f - d).let { it * it }
	}

	/** A mesh's bounce shapes; [positions] in the rig's space, deltas over the parent [frame]. */
	fun shapes(positions: FloatArray, frame: Bounds, bustY: ParameterId, bustX: ParameterId): List<BlendShapeBinding<MeshForm>> {
		if ((0 until positions.size / 2).none { weight(positions[it * 2], positions[it * 2 + 1]) > 0f }) return emptyList()
		fun form(change: (Float, Float, Float) -> Pair<Float, Float>) = MeshForm(FloatArray(positions.size).also { out ->
			for (i in 0 until positions.size / 2) {
				val x = positions[i * 2]; val y = positions[i * 2 + 1]
				val (dx, dy) = change(x, y, weight(x, y))
				out[i * 2] = dx / frame.width.coerceAtLeast(1e-4f); out[i * 2 + 1] = dy / frame.height.coerceAtLeast(1e-4f)
			}
		}, opacity = Float.NaN)
		val lift = length * 0.035f * amount
		val sway = halfWidth * 0.06f * amount
		return listOf(
			// Lifted, the chest also gathers a little toward its middle; settling, it spreads.
			BlendShapeBinding(bustY, floatArrayOf(-1f, 0f, 1f), 1, listOf(
				form { x, _, w -> (x - centerX) * 0.03f * amount * w to lift * w }, null,
				form { x, _, w -> -(x - centerX) * 0.03f * amount * w to -lift * w })),
			BlendShapeBinding(bustX, floatArrayOf(-1f, 0f, 1f), 1, listOf(form { _, _, w -> -sway * w to 0f }, null, form { _, _, w -> sway * w to 0f })),
		)
	}
}
