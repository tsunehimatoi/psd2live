package org.umamo.format

import org.umamo.format.psd.PsdThumbnail
import org.umamo.format.raster.RasterImage
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PsdThumbnailTest {
	@Test fun packedCompositeIsReadWithoutLayers() {
		val image = assertNotNull(PsdThumbnail.read(psd(4, 2, rle = true) { x, _ -> if (x < 2) Color.RED else Color.BLUE }.inputStream()))
		assertEquals(4 to 2, image.width to image.height)
		assertEquals(Triple(255, 0, 0), image.rgb(0, 1))
		assertEquals(Triple(0, 0, 255), image.rgb(3, 0))
	}

	@Test fun largeCompositeIsFittedWithinTheSize() {
		val image = assertNotNull(PsdThumbnail.read(psd(600, 300, rle = false) { x, _ -> if (x < 300) Color.GREEN else Color.WHITE }.inputStream(), 100))
		assertEquals(100 to 50, image.width to image.height)
		assertEquals(Triple(0, 255, 0), image.rgb(10, 25))
		assertEquals(Triple(255, 255, 255), image.rgb(90, 25))
	}

	@Test fun embeddedThumbnailWinsOverTheComposite() {
		val jpeg = BufferedImage(16, 8, BufferedImage.TYPE_INT_RGB).apply {
			createGraphics().run { color = Color(0, 200, 0); fillRect(0, 0, 16, 8); dispose() }
		}.let { ByteArrayOutputStream().also { out -> ImageIO.write(it, "jpg", out) }.toByteArray() }
		val image = assertNotNull(PsdThumbnail.read(psd(4, 2, rle = false, thumbnail = jpeg) { _, _ -> Color.RED }.inputStream()))
		assertEquals(16 to 8, image.width to image.height)
		val (r, g, _) = image.rgb(8, 4)
		assertTrue(g > 150 && r < 60, "expected the green JPEG, got r=$r g=$g")
	}

	@Test fun preserveAlphaSkipsOpaqueJpegAndDecodesAlpha() {
		val jpeg = BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB).apply {
			createGraphics().run { color = Color(255, 255, 255); fillRect(0, 0, 8, 8); dispose() }
		}.let { ByteArrayOutputStream().also { out -> ImageIO.write(it, "jpg", out) }.toByteArray() }
		val bytes = psd(4, 2, rle = false, thumbnail = jpeg, channels = 4) { x, _ ->
			if (x < 2) Color(255, 0, 0, 255) else Color(255, 255, 255, 0)
		}
		val image = assertNotNull(PsdThumbnail.read(bytes.inputStream(), preserveAlpha = true))
		assertEquals(4 to 2, image.width to image.height)
		assertEquals(255, image.rgba[(0 * 4 + 0) * 4 + 3].toInt() and 255)
		assertEquals(0, image.rgba[(0 * 4 + 3) * 4 + 3].toInt() and 255)
	}

	@Test fun otherFilesHaveNoThumbnail() {
		assertNull(PsdThumbnail.read(byteArrayOf(1, 2, 3, 4).inputStream()))
		assertNull(PsdThumbnail.read(psd(4, 2, rle = true) { _, _ -> Color.RED }.copyOf(40).inputStream()))
	}

	private fun RasterImage.rgb(x: Int, y: Int): Triple<Int, Int, Int> {
		val at = (y * width + x) * 4
		return Triple(rgba[at].toInt() and 255, rgba[at + 1].toInt() and 255, rgba[at + 2].toInt() and 255)
	}

	/** An RGB(A) PSD with no layers: [thumbnail] as resource 1036 when given, then the composite, raw or PackBits. */
	private fun psd(width: Int, height: Int, rle: Boolean, thumbnail: ByteArray? = null, channels: Int = 3, pixel: (Int, Int) -> Color): ByteArray {
		val out = ByteArrayOutputStream()
		DataOutputStream(out).run {
			writeBytes("8BPS"); writeShort(1); write(ByteArray(6)); writeShort(channels); writeInt(height); writeInt(width); writeShort(8); writeShort(3)
			writeInt(0)
			val resources = ByteArrayOutputStream().also { stream ->
				if (thumbnail != null) DataOutputStream(stream).run {
					writeBytes("8BIM"); writeShort(1036); writeShort(0)
					val size = 28 + thumbnail.size
					writeInt(size); write(ByteArray(28)); write(thumbnail)
					if (size % 2 == 1) writeByte(0)
				}
			}.toByteArray()
			writeInt(resources.size); write(resources)
			writeInt(0)
			writeShort(if (rle) 1 else 0)
			val planes = (0 until channels).map { channel ->
				(0 until height).map { y ->
					ByteArray(width) { x -> pixel(x, y).let { listOf(it.red, it.green, it.blue, it.alpha)[channel] }.toByte() }
				}
			}
			if (rle) {
				// One literal run per row of at most 128 bytes; enough for these widths split in chunks.
				val packed = planes.map { rows -> rows.map(::literalRuns) }
				packed.forEach { rows -> rows.forEach { writeShort(it.size) } }
				packed.forEach { rows -> rows.forEach(::write) }
			} else planes.forEach { rows -> rows.forEach(::write) }
		}
		return out.toByteArray()
	}

	private fun literalRuns(row: ByteArray): ByteArray = ByteArrayOutputStream().also { out ->
		row.toList().chunked(128).forEach { chunk ->
			out.write(chunk.size - 1)
			out.write(chunk.toByteArray())
		}
	}.toByteArray()
}
