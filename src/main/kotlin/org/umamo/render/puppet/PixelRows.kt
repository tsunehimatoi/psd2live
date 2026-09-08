package org.umamo.render.puppet

/**
 * Reverses an RGBA8888 image's row order into a fresh buffer.
 *
 * The bridge between a bottom-up framebuffer read-back and the top-first convention every consumer
 * here uses (`org.umamo.format.raster.RasterImage`, and the layered readers' pixel form).  OpenGL's
 * `glReadPixels` returns the bottom row first, so its device's read-back flips through this; a
 * top-left-origin backend (Metal) does not.  Which way up a backend reads is therefore a fact confined
 * to that backend, and no caller of `readPixels` ever asks.
 *
 * Row-wise, not per-pixel: the channel order is already what the caller wants, so only the row
 * ordering changes.
 *
 * @param ByteArray rgba   The source pixels, RGBA8888, tightly packed, `width * height * 4` bytes.
 * @param Int       width  The image width in pixels.
 * @param Int       height The image height in pixels.
 * @return ByteArray A new buffer with the row order reversed.
 */
internal fun flipRowsVertically(rgba: ByteArray, width: Int, height: Int): ByteArray {
	val rowBytes = width * 4
	val flipped = ByteArray(rgba.size)
	for (rowIndex in 0 until height) {
		val sourceStart = rowIndex * rowBytes
		val destinationStart = (height - 1 - rowIndex) * rowBytes
		rgba.copyInto(flipped, destinationStart, sourceStart, sourceStart + rowBytes)
	}
	return flipped
}