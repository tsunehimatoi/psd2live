package io.github.psd2live.targets.raster

import io.github.psd2live.format.compile.RasterImage

/**
 * The palette every frame of a GIF shares: index 0 is transparent and the other 255 colors are cut from the
 * frames' own opaque pixels (median cut over a 5-bit-per-channel histogram), so a navy uniform or a skin tone
 * gets colors of its own instead of the nearest corner of a fixed cube. One palette for the whole clip keeps a
 * still area from flickering between frames. Deterministic: the same frames give the same palette.
 */
internal class GifPalette private constructor(
	val reds: IntArray, val greens: IntArray, val blues: IntArray,
	/** The palette index nearest each 5-bit color, so mapping a pixel is one lookup. */
	private val nearest: ByteArray,
) {
	fun indexOf(r: Int, g: Int, b: Int): Int = nearest[(r shr 3 shl 10) or (g shr 3 shl 5) or (b shr 3)].toInt() and 255

	/**
	 * [frame] as palette indices, one byte per pixel; pixels under half opacity take index 0. With [dither] the
	 * rounding error spreads to the neighbours (Floyd-Steinberg), which turns banding in gradients into grain.
	 */
	fun map(frame: RasterImage, dither: Boolean): ByteArray {
		val width = frame.width
		val out = ByteArray(frame.argb.size)
		var errors = FloatArray((width + 2) * 3)
		var next = FloatArray((width + 2) * 3)
		for (y in 0 until frame.height) {
			for (x in 0 until width) {
				val i = y * width + x
				val argb = frame.argb[i]
				if ((argb ushr 24) < 128) continue
				val e = (x + 1) * 3
				val r = (((argb shr 16) and 255) + errors[e]).toInt().coerceIn(0, 255)
				val g = (((argb shr 8) and 255) + errors[e + 1]).toInt().coerceIn(0, 255)
				val b = ((argb and 255) + errors[e + 2]).toInt().coerceIn(0, 255)
				val index = indexOf(r, g, b)
				out[i] = index.toByte()
				if (!dither) continue
				val dr = (r - reds[index]).toFloat()
				val dg = (g - greens[index]).toFloat()
				val db = (b - blues[index]).toFloat()
				fun spread(row: FloatArray, at: Int, weight: Float) {
					row[at] += dr * weight; row[at + 1] += dg * weight; row[at + 2] += db * weight
				}
				spread(errors, e + 3, 7f / 16f)
				spread(next, e - 3, 3f / 16f)
				spread(next, e, 5f / 16f)
				spread(next, e + 3, 1f / 16f)
			}
			val done = errors
			errors = next
			next = done.also { it.fill(0f) }
		}
		return out
	}

	companion object {
		private const val BINS = 1 shl 15
		/** Enough samples for a stable palette without reading every pixel of a long, large clip. */
		private const val MAX_SAMPLES = 4_000_000L

		fun of(frames: List<RasterImage>): GifPalette {
			val counts = LongArray(BINS)
			// The exact channel sums per bin, so a palette color is the mean of real pixels, not of bin centers.
			val sums = LongArray(BINS * 3)
			val total = frames.sumOf { it.argb.size.toLong() }
			val step = maxOf(1L, total / MAX_SAMPLES).toInt()
			for (frame in frames) {
				var i = 0
				while (i < frame.argb.size) {
					val argb = frame.argb[i]
					if ((argb ushr 24) >= 128) {
						val bin = bin(argb)
						counts[bin]++
						sums[bin * 3] += ((argb shr 16) and 255).toLong()
						sums[bin * 3 + 1] += ((argb shr 8) and 255).toLong()
						sums[bin * 3 + 2] += (argb and 255).toLong()
					}
					i += step
				}
			}
			val colors = medianCut(counts, sums, 255)
			val reds = IntArray(256); val greens = IntArray(256); val blues = IntArray(256)
			colors.forEachIndexed { k, color ->
				reds[k + 1] = (color shr 16) and 255; greens[k + 1] = (color shr 8) and 255; blues[k + 1] = color and 255
			}
			val used = colors.size
			val nearest = ByteArray(BINS) { bin ->
				val r = center(bin shr 10); val g = center((bin shr 5) and 31); val b = center(bin and 31)
				var best = 1
				var bestDistance = Int.MAX_VALUE
				for (k in 1..maxOf(1, used)) {
					val dr = r - reds[k]; val dg = g - greens[k]; val db = b - blues[k]
					// Weighted toward green, the channel the eye resolves best.
					val distance = 2 * dr * dr + 4 * dg * dg + 3 * db * db
					if (distance < bestDistance) { bestDistance = distance; best = k }
				}
				best.toByte()
			}
			return GifPalette(reds, greens, blues, nearest)
		}

		private fun bin(argb: Int): Int =
			(((argb shr 16) and 255) shr 3 shl 10) or (((argb shr 8) and 255) shr 3 shl 5) or ((argb and 255) shr 3)

		private fun center(level: Int) = (level shl 3) + 4

		/** Splits the occupied bins into at most [limit] boxes and returns the mean color of each box's pixels. */
		private fun medianCut(counts: LongArray, sums: LongArray, limit: Int): List<Int> {
			val occupied = (0 until BINS).filter { counts[it] > 0 }.toIntArray()
			if (occupied.isEmpty()) return emptyList()
			val boxes = mutableListOf(occupied)
			while (boxes.size < limit) {
				// The box spanning the widest channel range, weighted by how many pixels it holds, splits next.
				var chosen = -1
				var score = 0.0
				for ((k, box) in boxes.withIndex()) {
					if (box.size < 2) continue
					val pixels = box.sumOf { counts[it] }.toDouble()
					val s = (0 until 3).maxOf { range(box, it) } * Math.sqrt(pixels)
					if (s > score) { score = s; chosen = k }
				}
				if (chosen < 0) break
				val box = boxes.removeAt(chosen)
				val axis = (0 until 3).maxBy { range(box, it) }
				val sorted = box.sortedBy { channel(it, axis) }
				val half = sorted.sumOf { counts[it] } / 2
				var running = 0L
				var cut = 1
				for ((k, bin) in sorted.withIndex()) {
					running += counts[bin]
					if (running >= half) { cut = (k + 1).coerceIn(1, sorted.size - 1); break }
				}
				boxes += sorted.subList(0, cut).toIntArray()
				boxes += sorted.subList(cut, sorted.size).toIntArray()
			}
			return boxes.map { box ->
				var r = 0L; var g = 0L; var b = 0L; var n = 0L
				for (bin in box) { r += sums[bin * 3]; g += sums[bin * 3 + 1]; b += sums[bin * 3 + 2]; n += counts[bin] }
				(Math.round(r.toDouble() / n).toInt() shl 16) or (Math.round(g.toDouble() / n).toInt() shl 8) or Math.round(b.toDouble() / n).toInt()
			}
		}

		private fun channel(bin: Int, axis: Int) = when (axis) { 0 -> bin shr 10; 1 -> (bin shr 5) and 31; else -> bin and 31 }

		private fun range(box: IntArray, axis: Int): Int {
			var low = 31; var high = 0
			for (bin in box) { val v = channel(bin, axis); if (v < low) low = v; if (v > high) high = v }
			return high - low
		}
	}
}
