package io.github.psd2live.core

import io.github.psd2live.i18n.tr
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.BitSet
import java.util.stream.IntStream
import java.util.zip.Adler32
import java.util.zip.CRC32
import java.util.zip.Deflater
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageOutputStream

/**
 * PNG encodings of an atlas page.
 *
 * [canonical] is ImageIO's, what exports write. The editor's runtime bundle instead uses a strip encoding:
 * the page's rows are filtered and deflated in strips of [STRIP_ROWS], each strip on its own (a fresh
 * compressor, ending on a full flush), and each strip becomes one IDAT chunk. The chunks of a zlib stream
 * may be split anywhere, and independent deflate segments that each end byte-aligned on a non-final block
 * concatenate into one valid stream; the stream's Adler-32 is combined from the strips'. So a page that
 * differs from an earlier page only in a few rows re-encodes only those strips, and a whole page encodes
 * its strips in parallel. The pixels decode exactly as the canonical PNG's do - both are lossless RGBA8.
 */
internal object AtlasPagePng {
	const val STRIP_ROWS = 16
	private val SIGNATURE = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10)

	/** One encoded strip: its IDAT chunk (length, type, data, CRC), the Adler-32 and length of its filtered rows. */
	class Strip(val chunk: ByteArray, val adler: Int, val rawLength: Long)

	class Strips(val width: Int, val height: Int, val strips: List<Strip>)

	fun canonical(image: BufferedImage): ByteArray {
		val output = ByteArrayOutputStream()
		// The destination is already in memory. Avoid ImageIO's default disk cache for stream output.
		MemoryCacheImageOutputStream(output).use { stream ->
			check(ImageIO.write(image, "png", stream)) { tr("error.pngEncoder") }
		}
		return output.toByteArray().also { png -> synchronized(imagesByPng) { imagesByPng[png] = java.lang.ref.WeakReference(image) } }
	}

	/**
	 * The image each canonical encoding was made from, by the encoding's identity (a ByteArray hashes by
	 * identity) and only while the encoding lives, so an export renderer handed the PNG can read the pixels
	 * instead of decoding it. Neither is kept alive by the entry: it serves only while the page holds both.
	 */
	private val imagesByPng = java.util.WeakHashMap<ByteArray, java.lang.ref.WeakReference<BufferedImage>>()

	/**
	 * The ARGB pixels (row by row, not to be modified) [png] decodes to, when it is a live canonical encoding of
	 * a straight-alpha image (whose pixels the PNG holds exactly).
	 */
	fun pixelsOf(png: ByteArray): IntArray? =
		synchronized(imagesByPng) { imagesByPng[png]?.get() }?.takeIf { it.type == BufferedImage.TYPE_INT_ARGB }?.let(::argb)

	/** Strips of [image]; those of [reuse] (a page of the same size) are kept wherever no row in [dirtyRows] falls. */
	fun strips(image: BufferedImage, reuse: Strips?, dirtyRows: BitSet?): Strips {
		val width = image.width; val height = image.height
		val count = (height + STRIP_ROWS - 1) / STRIP_ROWS
		val pixels = argb(image)
		val result = arrayOfNulls<Strip>(count)
		val encode = IntStream.range(0, count).filter { index ->
			val start = index * STRIP_ROWS; val end = minOf(height, start + STRIP_ROWS)
			val clean = reuse != null && reuse.width == width && reuse.height == height && dirtyRows != null &&
				dirtyRows.nextSetBit(start).let { it < 0 || it >= end }
			if (clean) result[index] = reuse!!.strips[index]
			!clean
		}.toArray()
		if (Thread.currentThread().isInterrupted) throw InterruptedException()
		val work = IntStream.of(*encode)
		(if (encode.size > 2) work.parallel() else work).forEach { index ->
			val start = index * STRIP_ROWS
			result[index] = strip(pixels, width, start, minOf(height, start + STRIP_ROWS))
		}
		return Strips(width, height, result.map { requireNotNull(it) })
	}

	/** The PNG file of [strips]. */
	fun assemble(width: Int, height: Int, strips: Strips): ByteArray {
		require(strips.width == width && strips.height == height)
		val header = ByteBuffer.allocate(13).putInt(width).putInt(height).put(8).put(6).put(0).put(0).put(0).array()
		var adler = 1
		for (strip in strips.strips) adler = combineAdler(adler, strip.adler, strip.rawLength)
		// The zlib header leads; an empty final stored block and the checksum close the stream.
		val zlibHeader = chunk("IDAT", byteArrayOf(0x78, 0x01))
		val trailer = chunk("IDAT", ByteBuffer.allocate(9).put(byteArrayOf(0x01, 0x00, 0x00, 0xff.toByte(), 0xff.toByte())).putInt(adler).array())
		val ihdr = chunk("IHDR", header)
		val iend = chunk("IEND", ByteArray(0))
		val size = SIGNATURE.size + ihdr.size + zlibHeader.size + strips.strips.sumOf { it.chunk.size } + trailer.size + iend.size
		val out = ByteBuffer.allocate(size)
		out.put(SIGNATURE).put(ihdr).put(zlibHeader)
		for (strip in strips.strips) out.put(strip.chunk)
		out.put(trailer).put(iend)
		return out.array()
	}

	private fun argb(image: BufferedImage): IntArray {
		val buffer = image.raster.dataBuffer
		if (image.type == BufferedImage.TYPE_INT_ARGB && buffer is DataBufferInt && buffer.numBanks == 1 &&
			image.raster.sampleModelTranslateX == 0 && image.raster.sampleModelTranslateY == 0 && buffer.data.size == image.width * image.height) {
			return buffer.data
		}
		return image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
	}

	/** Rows [start] until [end], each with the Sub filter, deflated on their own. */
	private fun strip(pixels: IntArray, width: Int, start: Int, end: Int): Strip {
		val stride = width * 4 + 1
		val raw = ByteArray(stride * (end - start))
		for (y in start until end) {
			var offset = (y - start) * stride
			raw[offset++] = 1
			var previous = 0
			val row = y * width
			for (x in 0 until width) {
				val pixel = pixels[row + x]
				val r = pixel ushr 16 and 0xff; val g = pixel ushr 8 and 0xff; val b = pixel and 0xff; val a = pixel ushr 24
				raw[offset] = (r - (previous ushr 16 and 0xff)).toByte()
				raw[offset + 1] = (g - (previous ushr 8 and 0xff)).toByte()
				raw[offset + 2] = (b - (previous and 0xff)).toByte()
				raw[offset + 3] = (a - (previous ushr 24)).toByte()
				offset += 4
				previous = pixel
			}
		}
		val adler = Adler32().apply { update(raw) }.value.toInt()
		// A reset deflater writes what a new one would; making one per strip cost about a third of the strip.
		val deflater = deflaters.get().apply { reset() }
		deflater.setInput(raw)
		val output = ByteArrayOutputStream(raw.size / 8 + 64)
		val buffer = buffers.get()
		while (true) {
			val written = deflater.deflate(buffer, 0, buffer.size, Deflater.FULL_FLUSH)
			output.write(buffer, 0, written)
			// A full flush is complete once it no longer fills the buffer.
			if (written < buffer.size) break
		}
		return Strip(chunk("IDAT", output.toByteArray()), adler, raw.size.toLong())
	}

	private val deflaters = ThreadLocal.withInitial { Deflater(1, true) }
	private val buffers = ThreadLocal.withInitial { ByteArray(64 * 1024) }

	private fun chunk(type: String, data: ByteArray): ByteArray {
		val typeBytes = type.encodeToByteArray()
		val crc = CRC32().apply { update(typeBytes); update(data) }.value.toInt()
		return ByteBuffer.allocate(12 + data.size).putInt(data.size).put(typeBytes).put(data).putInt(crc).array()
	}

	/** zlib's adler32_combine: the checksum of two byte runs from each one's and the second's length. */
	internal fun combineAdler(first: Int, second: Int, secondLength: Long): Int {
		val base = 65521L
		val remainder = secondLength % base
		var sum1 = first.toLong() and 0xffff
		var sum2 = (remainder * sum1) % base
		sum1 += (second.toLong() and 0xffff) + base - 1
		sum2 += ((first.toLong() ushr 16) and 0xffff) + ((second.toLong() ushr 16) and 0xffff) + base - remainder
		if (sum1 >= base) sum1 -= base
		if (sum1 >= base) sum1 -= base
		if (sum2 >= base shl 1) sum2 -= base shl 1
		if (sum2 >= base) sum2 -= base
		return (sum1 or (sum2 shl 16)).toInt()
	}
}
