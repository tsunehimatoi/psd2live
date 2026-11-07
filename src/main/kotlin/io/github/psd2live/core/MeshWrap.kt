package io.github.psd2live.core

import org.umamo.format.art.BinaryAlphaMask
import kotlin.math.ceil
import kotlin.math.max

/**
 * Wrap topology: a morphological closing of a layer's working mask before its outline is traced.
 *
 * Dilating the mask by a disc of radius r and eroding the result by the same disc fills every gap, notch and
 * hole a disc of radius r cannot enter, and keeps every painted pixel: a row of lashes or strand tips closer
 * than 2r becomes one fan-shaped envelope whose outline runs along their tips, while a stroke wider apart or
 * longer than r keeps its full length inside it. The closing is a superset of the mask, so nothing painted
 * falls outside the mesh.
 *
 * Both steps threshold an exact Euclidean distance transform (Felzenszwalb and Huttenlocher's lower envelope of
 * parabolas), linear in the pixels whatever the radius. The raster is padded by the radius so the border does
 * not erode what touches it.
 */
internal object MeshWrap {
	/** UI and command range of [MeshSettings.wrap], in mesh units. */
	val range = 0f..64f

	private const val INF = Int.MAX_VALUE / 4

	/** The radius, in working pixels, a [wrap] of mesh units closes at with [unit] working pixels per mesh unit. */
	fun radius(wrap: Float, unit: Double): Double =
		if (!wrap.isFinite() || wrap <= 0f) 0.0 else wrap.coerceAtMost(range.endInclusive) * unit / 2.0

	/**
	 * Closes [source] at [radius] working pixels in place. Painted pixels stay opaque.
	 * Returns false when nothing changed.
	 */
	fun close(source: BinaryAlphaMask, radius: Double): Boolean {
		val width = source.width
		val height = source.height
		if (!(radius >= 0.5) || width <= 0 || height <= 0) return false
		val pad = ceil(radius).toInt() + 1
		val w = width + pad * 2
		val h = height + pad * 2
		val mask = BooleanArray(w * h)
		var any = false
		for (y in 0 until height) {
			val row = (y + pad) * w + pad
			for (x in 0 until width) if (source.isOpaque(x, y)) { mask[row + x] = true; any = true }
		}
		if (!any) return false
		val limit = radius * radius
		val toMask = squaredDistance(w, h, mask)
		// The dilation's complement: everything farther than the radius from paint.
		val outside = BooleanArray(w * h) { toMask[it] > limit }
		val toOutside = squaredDistance(w, h, outside)
		var changed = false
		for (y in 0 until height) {
			val row = (y + pad) * w + pad
			for (x in 0 until width) {
				if (!source.isOpaque(x, y) && toOutside[row + x] > limit) { source.add(x, y); changed = true }
			}
		}
		return changed
	}

	/** Squared Euclidean distance from every pixel to the nearest [feature] pixel; [INF] when there is none. */
	internal fun squaredDistance(width: Int, height: Int, feature: BooleanArray): IntArray {
		val out = IntArray(width * height)
		// Down each column: the distance to the nearest feature in it.
		for (x in 0 until width) {
			var last = -1
			for (y in 0 until height) {
				val i = y * width + x
				if (feature[i]) last = y
				out[i] = if (last < 0) INF else y - last
			}
			last = -1
			for (y in height - 1 downTo 0) {
				val i = y * width + x
				if (feature[i]) last = y
				if (last >= 0 && last - y < out[i]) out[i] = last - y
			}
			for (y in 0 until height) {
				val i = y * width + x
				val d = out[i]
				out[i] = if (d >= INF) INF else d * d
			}
		}
		// Along each row: the lower envelope of the parabolas the column distances root.
		val f = IntArray(width)
		val v = IntArray(width)
		val z = DoubleArray(width + 1)
		for (y in 0 until height) {
			val row = y * width
			for (x in 0 until width) f[x] = out[row + x]
			var k = -1
			for (q in 0 until width) {
				if (f[q] >= INF) continue
				if (k < 0) { k = 0; v[0] = q; z[0] = Double.NEGATIVE_INFINITY; z[1] = Double.POSITIVE_INFINITY; continue }
				var s = intersection(f, v[k], q)
				while (s <= z[k]) {
					k--
					if (k < 0) break
					s = intersection(f, v[k], q)
				}
				if (k < 0) { k = 0; v[0] = q; z[0] = Double.NEGATIVE_INFINITY }
				else { k++; v[k] = q; z[k] = s }
				z[k + 1] = Double.POSITIVE_INFINITY
			}
			if (k < 0) continue
			var j = 0
			for (q in 0 until width) {
				while (z[j + 1] < q) j++
				val dx = q - v[j]
				out[row + q] = max(0, dx * dx + f[v[j]]).coerceAtMost(INF)
			}
		}
		return out
	}

	private fun intersection(f: IntArray, p: Int, q: Int): Double =
		((f[q].toDouble() + q.toDouble() * q) - (f[p].toDouble() + p.toDouble() * p)) / (2.0 * (q - p))
}
