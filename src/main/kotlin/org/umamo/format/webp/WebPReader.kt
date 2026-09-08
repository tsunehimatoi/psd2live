package org.umamo.format.webp

import org.umamo.format.FileKind
import org.umamo.format.raster.RasterImage
import org.umamo.format.raster.ReadOnlyRasterCodec

/**
 * Pure-Kotlin WebP reader (desktop JVM and Android), read only.
 *
 * Decodes lossless VP8L WebP (directly or inside a VP8X extended container) to the neutral
 * straight-alpha RGBA8888 top-first [RasterImage] - VP8L pixels are natively RGBA, so no
 * conversion is needed.  Lossy VP8, animation, and VP8L encode are out of scope and rejected with
 * a clear message.  Ported from TwelveMonkeys imageio-webp's lossless decoder over a ByteArray, so
 * it decodes identically on desktop and Android (no javax.imageio).  Stays read-only for now (a
 * VP8L writer would make it a read+write codec, taking the bare name WebP like Cmo3 / Moc3).
 */
public object WebPReader : ReadOnlyRasterCodec {
	override val kind: FileKind = FileKind.WebP

	/**
	 * True if [candidateBytes] is a RIFF container tagged "WEBP".
	 *
	 * @param ByteArray candidateBytes Candidate file contents.
	 * @return Boolean Whether the leading bytes are the WebP magic.
	 */
	override fun matches(candidateBytes: ByteArray): Boolean {
		// WebP: 'RIFF' @ +0x00, 4-byte file size, then the 'WEBP' form type @ +0x08.
		if (candidateBytes.size < 12) {
			return false
		}
		val riff =
			candidateBytes[0] == 0x52.toByte() && candidateBytes[1] == 0x49.toByte() && candidateBytes[2] == 0x46.toByte() && candidateBytes[3] == 0x46.toByte()
		val webp =
			candidateBytes[8] == 0x57.toByte() && candidateBytes[9] == 0x45.toByte() && candidateBytes[10] == 0x42.toByte() && candidateBytes[11] == 0x50.toByte()
		return riff && webp
	}

	/**
	 * Decodes a lossless VP8L `.webp` into a straight-alpha RGBA8888 [RasterImage], top row first.
	 *
	 * @param ByteArray bytes The complete `.webp` file.
	 * @return RasterImage The decoded image.
	 */
	override fun read(bytes: ByteArray): RasterImage {
		require(matches(bytes)) { "Not a WebP (bad signature)" }
		val header = parseVp8lHeader(bytes)
		val raster = WebpRaster.create(header.width, header.height)
		Vp8lDecoder(WebpLsbBitReader(bytes, header.bitStreamStart)).decode(raster, header.width, header.height)
		return RasterImage(header.width, header.height, raster.data)
	}
}