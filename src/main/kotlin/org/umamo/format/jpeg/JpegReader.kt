package org.umamo.format.jpeg

import org.umamo.format.FileKind
import org.umamo.format.raster.RasterImage
import org.umamo.format.raster.ReadOnlyRasterCodec

/**
 * Pure-Kotlin JPEG reader (desktop JVM and Android), read only.
 *
 * Decodes sequential (SOF0 / SOF1) and progressive (SOF2) Huffman JPEG, 8-bit, grayscale or
 * 3-component color, with restart intervals and any sampling factors, to the neutral straight-alpha
 * RGBA8888 top-first [RasterImage] (alpha is always opaque — JPEG carries no alpha channel).
 * Progressive support matters in practice, not as a nicety: it is what most of the web serves, so the
 * common art sources (Twitter/X, Pixiv, Photoshop "Save for Web") emit it by default.
 *
 * Lossless, arithmetic-coded, differential, 12-bit, and CMYK/YCCK frames are rejected with a clear
 * message.  Uses no javax.imageio, so it decodes identically on both targets.
 */
public object JpegReader : ReadOnlyRasterCodec {
	override val kind: FileKind = FileKind.Jpeg

	/**
	 * True if [candidateBytes] opens with the JPEG SOI + marker prefix.
	 *
	 * @param ByteArray candidateBytes Candidate file contents.
	 * @return Boolean Whether the leading bytes are the JPEG magic.
	 */
	override fun matches(candidateBytes: ByteArray): Boolean =
		// JPEG (JFIF/EXIF): SOI marker 0xFFD8 followed by a further 0xFF marker byte.
		candidateBytes.size >= 3 &&
			candidateBytes[0] == 0xFF.toByte() &&
			candidateBytes[1] == 0xD8.toByte() &&
			candidateBytes[2] == 0xFF.toByte()

	/**
	 * Decodes a `.jpg` into an opaque straight-alpha RGBA8888 [RasterImage], top row first.
	 *
	 * @param ByteArray bytes The complete `.jpg` file.
	 * @return RasterImage The decoded image.
	 */
	override fun read(bytes: ByteArray): RasterImage {
		require(matches(bytes)) { "Not a JPEG (bad signature)" }
		val decoded = decodeJpeg(bytes)
		return RasterImage(decoded.width, decoded.height, expandToRgba(decoded))
	}

	/**
	 * Expands decoded grayscale or RGB samples to opaque RGBA8888.
	 *
	 * @param JpegImage decoded The decoded JPEG.
	 * @return ByteArray The RGBA8888 pixels.
	 */
	private fun expandToRgba(decoded: JpegImage): ByteArray {
		val pixelCount = decoded.width * decoded.height
		val rgba = ByteArray(pixelCount * 4)
		for (pixelIndex in 0 until pixelCount) {
			val destination = pixelIndex * 4
			if (decoded.componentCount == 1) {
				val gray = decoded.samples[pixelIndex]
				rgba[destination] = gray
				rgba[destination + 1] = gray
				rgba[destination + 2] = gray
			} else {
				val source = pixelIndex * 3
				rgba[destination] = decoded.samples[source]
				rgba[destination + 1] = decoded.samples[source + 1]
				rgba[destination + 2] = decoded.samples[source + 2]
			}
			rgba[destination + 3] = 0xFF.toByte()
		}
		return rgba
	}
}