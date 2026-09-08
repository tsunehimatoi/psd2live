// Replaces java.awt.image.WritableRaster in the ported VP8L decoder (TwelveMonkeys imageio-webp,
// BSD-3-Clause).  A plain RGBA8888 byte buffer with (x, y) access and child views; see CREDITS.md.

package org.umamo.format.webp

/**
 * A 2D RGBA8888 pixel buffer with (x, y) get/set and sub-region child views, standing in for the AWT
 * WritableRaster the TwelveMonkeys VP8L decoder threads throughout.  Band order is R, G, B, A (bands
 * 0..3); a child view shares the parent's backing [data] and row stride, so writes land in place.
 */
internal class WebpRaster private constructor(
	val data: ByteArray,
	private val offsetX: Int,
	private val offsetY: Int,
	val width: Int,
	val height: Int,
	private val rowStridePixels: Int,
) {
	private fun base(x: Int, y: Int): Int = ((offsetY + y) * rowStridePixels + offsetX + x) * 4

	/**
	 * Copies pixel (x, y)'s four bytes into [out].
	 *
	 * @param Int x         Column.
	 * @param Int y         Row.
	 * @param ByteArray out A length-4 destination (R, G, B, A).
	 */
	fun getPixel(x: Int, y: Int, out: ByteArray) {
		val index = base(x, y)
		out[0] = data[index]
		out[1] = data[index + 1]
		out[2] = data[index + 2]
		out[3] = data[index + 3]
	}

	/**
	 * Writes [src]'s four bytes into pixel (x, y).
	 *
	 * @param Int x         Column.
	 * @param Int y         Row.
	 * @param ByteArray src A length-4 source (R, G, B, A).
	 */
	fun setPixel(x: Int, y: Int, src: ByteArray) {
		val index = base(x, y)
		data[index] = src[0]
		data[index + 1] = src[1]
		data[index + 2] = src[2]
		data[index + 3] = src[3]
	}

	/**
	 * Returns one band (0=R, 1=G, 2=B, 3=A) of pixel (x, y) as an unsigned int.
	 *
	 * @param Int x    Column.
	 * @param Int y    Row.
	 * @param Int band The band index.
	 * @return Int The band value in 0..255.
	 */
	fun getSample(x: Int, y: Int, band: Int): Int = data[base(x, y) + band].toInt() and 0xFF

	/**
	 * Returns a child view sharing this buffer, offset by (x, y) with its own logical size.
	 *
	 * @param Int x Child origin column within this raster.
	 * @param Int y Child origin row within this raster.
	 * @param Int childWidth  Child width.
	 * @param Int childHeight Child height.
	 * @return WebpRaster The child view.
	 */
	fun child(x: Int, y: Int, childWidth: Int, childHeight: Int): WebpRaster =
		WebpRaster(data, offsetX + x, offsetY + y, childWidth, childHeight, rowStridePixels)

	companion object {
		/**
		 * Allocates a fresh RGBA raster of the given size.
		 *
		 * @param Int width  Width.
		 * @param Int height Height.
		 * @return WebpRaster The new raster.
		 */
		fun create(width: Int, height: Int): WebpRaster = WebpRaster(ByteArray(width * height * 4), 0, 0, width, height, width)

		/**
		 * Wraps an existing RGBA byte buffer as a raster (used for the color-indexing color table).
		 *
		 * @param ByteArray data The RGBA bytes (row-major, tightly packed).
		 * @param Int width      Width.
		 * @param Int height     Height.
		 * @return WebpRaster The raster over [data].
		 */
		fun over(data: ByteArray, width: Int, height: Int): WebpRaster = WebpRaster(data, 0, 0, width, height, width)
	}
}