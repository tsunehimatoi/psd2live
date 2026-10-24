package io.github.psd2live.core.modifier

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Kinds the stack's tests run on: the product ships none this plain, but the stack does not care what a kind does. */
internal object TestKinds {
	/** Moves, turns and scales about a pivot: `pivot + R·S·(p − pivot) + offset`, a pure function of the shape before it. */
	object Transform : ModifierKind {
		override val id = "transform"
		override val category = ModifierCategory.CORRECTION
		override val settings = listOf(
			ModifierSetting("offset", 2, floatArrayOf(0f, 0f), description = "Translation [x, y] in canvas pixels."),
			ModifierSetting("rotation", 1, floatArrayOf(0f), -3600f, 3600f, "Degrees about the pivot."),
			ModifierSetting("scale", 2, floatArrayOf(1f, 1f), -16f, 16f, "Scale about the pivot."),
			ModifierSetting("pivot", 2, floatArrayOf(0f, 0f), description = "Pivot [x, y]."),
		)

		override fun defaults(bounds: FloatArray) = mapOf("pivot" to floatArrayOf((bounds[0] + bounds[2]) / 2, (bounds[1] + bounds[3]) / 2))

		override fun deform(context: ModifierContext, values: Map<String, FloatArray>, options: Map<String, String>): FloatArray {
			val positions = context.before()
			val offset = values.getValue("offset")
			val angle = values.getValue("rotation")[0] * PI / 180
			val scale = values.getValue("scale")
			val pivot = values.getValue("pivot")
			val c = cos(angle); val s = sin(angle)
			return FloatArray(positions.size).also { out ->
				for (i in 0 until positions.size / 2) {
					val x = (positions[i * 2] - pivot[0]) * scale[0].toDouble()
					val y = (positions[i * 2 + 1] - pivot[1]) * scale[1].toDouble()
					out[i * 2] = (pivot[0] + c * x - s * y + offset[0]).toFloat()
					out[i * 2 + 1] = (pivot[1] + s * x + c * y + offset[1]).toFloat()
				}
			}
		}
	}

	fun register() = ModifierKinds.register(Transform)
}
