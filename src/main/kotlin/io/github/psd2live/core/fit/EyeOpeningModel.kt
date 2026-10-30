package io.github.psd2live.core.fit

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.InflaterInputStream

/**
 * A widened eye's displacement curves, in its own frame: each lid moves across and down, with both lids sharing
 * their corner motion. The constant term lifts the whole line; the higher orders change its slope and arch. The
 * drawn eye is the baseline, so neither an off-centre peak nor a straight segment is replaced by a standard arc.
 *
 * Across displacements are in eye widths, down displacements in the drawn opening's height. The upper lid's middle
 * rises by one opening at unit strength. Strength multiplies the curves and is zero at the drawn pose. Coefficients
 * are a small binary polynomial model, evaluated by Clenshaw recurrence rather than sampled pose tables.
 */
internal class EyeOpeningModel private constructor(private val curves: Array<FloatArray>) {

	fun at(u: Float, strength: Float): FloatArray {
		val x = 2f * u.coerceIn(0f, 1f) - 1f
		return FloatArray(curves.size) { index ->
			val c = curves[index]
			var b1 = 0f; var b2 = 0f
			for (j in c.lastIndex downTo 1) {
				val b = c[j] + 2f * x * b1 - b2
				b2 = b1; b1 = b
			}
			(c[0] + x * b1 - b2) * strength
		}
	}

	companion object {
		val DEFAULT: EyeOpeningModel by lazy {
			val input = checkNotNull(EyeOpeningModel::class.java.getResourceAsStream("/rig/eye-opening.bin"))
			input.use {
				check(it.readNBytes(4).contentEquals(byteArrayOf(69, 79, 80, 78))) { "Invalid eye opening model" }
				val bytes = InflaterInputStream(it).use { compressed -> compressed.readBytes() }
				val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
				check(buffer.short.toInt() == 1) { "Unsupported eye opening model" }
				val degree = buffer.get().toInt() and 0xff
				val fields = buffer.get().toInt() and 0xff
				check(degree in 1..8 && fields == 4 && buffer.remaining() == fields * (degree + 1) * 4)
				val curves = Array(fields) { FloatArray(degree + 1) { buffer.float.also { v -> check(v.isFinite()) } } }
				EyeOpeningModel(curves)
			}
		}
	}
}
