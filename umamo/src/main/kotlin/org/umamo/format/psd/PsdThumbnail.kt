package org.umamo.format.psd

import org.umamo.format.jpeg.JpegReader
import org.umamo.format.raster.RasterImage
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream

/**
 * A small picture of a PSD or PSB, read without parsing its layers: the thumbnail Photoshop keeps among the image
 * resources (1036, or the BGR 1033 of Photoshop 4), else the merged composite subsampled while it streams by. Only
 * 8-bit RGB and grayscale composites are sampled; anything else, or a file with neither, yields null.
 */
public object PsdThumbnail {
	private const val RESOURCE_THUMBNAIL = 1036
	private const val RESOURCE_THUMBNAIL_BGR = 1033
	/** A resource block larger than this is not worth holding to find a thumbnail in. */
	private const val MAX_RESOURCES = 64 shl 20

	/**
	 * Reads the picture from [input], positioned at the file's first byte, fitted within [maxSize] on its longer side.
	 *
	 * @param InputStream input The file, from its start; read only as far as the picture needs.
	 * @param Int maxSize The longer side of the result, at least 1.
	 * @param Boolean preserveAlpha When true and the composite carries an alpha channel, skip the opaque JPEG thumbnail and decode alpha.
	 * @return RasterImage? The picture, top row first; null when the file has none this reads.
	 */
	public fun read(input: InputStream, maxSize: Int = 256, preserveAlpha: Boolean = false): RasterImage? = runCatching {
		require(maxSize > 0) { "a thumbnail needs a positive size, not $maxSize" }
		val data = DataInputStream(input)
		val header = ByteArray(26).also(data::readFully)
		if (String(header, 0, 4, Charsets.US_ASCII) != "8BPS") return null
		val version = u16(header, 4)
		if (version != 1 && version != 2) return null
		val channels = u16(header, 12)
		val height = u32(header, 14).toInt()
		val width = u32(header, 18).toInt()
		val depth = u16(header, 22)
		val mode = u16(header, 24)
		val colors = when (mode) {
			MODE_GRAYSCALE -> 1
			MODE_RGB -> 3
			else -> null
		}
		val hasCompositeAlpha = preserveAlpha && depth == 8 && colors != null && channels > colors
		skipFully(data, data.readInt().toLong() and 0xFFFFFFFFL)
		val resourcesLength = data.readInt().toLong() and 0xFFFFFFFFL
		if (hasCompositeAlpha || resourcesLength > MAX_RESOURCES) {
			skipFully(data, resourcesLength)
		} else {
			val resources = ByteArray(resourcesLength.toInt()).also(data::readFully)
			embedded(resources)?.let { return@runCatching it.fittedWithin(maxSize) }
		}
		val layersLength = if (version == 2) data.readLong() else data.readInt().toLong() and 0xFFFFFFFFL
		skipFully(data, layersLength)
		if (depth != 8 || width <= 0 || height <= 0 || colors == null) return null
		if (channels < colors) return null
		composite(data, width, height, channels, colors, version == 2, maxSize, preserveAlpha)
	}.getOrNull()

	private const val MODE_GRAYSCALE = 1
	private const val MODE_RGB = 3

	/** The JPEG thumbnail among [resources], decoded. */
	private fun embedded(resources: ByteArray): RasterImage? {
		var at = 0
		while (at + 12 <= resources.size) {
			if (String(resources, at, 4, Charsets.US_ASCII) != "8BIM") return null
			val id = u16(resources, at + 4)
			val nameLength = resources[at + 6].toInt() and 0xFF
			// The Pascal name with its length byte is padded to an even size.
			val sizeAt = at + 6 + ((nameLength + 2) and 1.inv())
			if (sizeAt + 4 > resources.size) return null
			val size = u32(resources, sizeAt).toInt()
			val dataAt = sizeAt + 4
			if (size < 0 || dataAt + size > resources.size) return null
			if ((id == RESOURCE_THUMBNAIL || id == RESOURCE_THUMBNAIL_BGR) && size > 28) {
				// Format, width, height, row bytes, total and compressed sizes, depth and planes precede the JFIF.
				val jpeg = resources.copyOfRange(dataAt + 28, dataAt + size)
				val image = runCatching { JpegReader.read(jpeg) }.getOrNull()
				if (image != null) return if (id == RESOURCE_THUMBNAIL_BGR) image.swappedRedBlue() else image
			}
			at = dataAt + size + (size and 1)
		}
		return null
	}

	/**
	 * Samples the merged image, planar by channel, raw or PackBits by row, into the fitted size: each target pixel
	 * takes the source pixel at its centre, so only one row per target row is kept from each channel.
	 */
	private fun composite(
		data: DataInputStream, width: Int, height: Int, channels: Int, colors: Int, large: Boolean, maxSize: Int, preserveAlpha: Boolean,
	): RasterImage? {
		val scale = minOf(1.0, maxSize.toDouble() / maxOf(width, height))
		val outWidth = (width * scale).toInt().coerceAtLeast(1)
		val outHeight = (height * scale).toInt().coerceAtLeast(1)
		val sourceX = IntArray(outWidth) { ((it + 0.5) / scale).toInt().coerceAtMost(width - 1) }
		// Target row for each source row, or -1 for rows no target pixel lands on.
		val targetRow = IntArray(height) { -1 }
		for (y in 0 until outHeight) targetRow[((y + 0.5) / scale).toInt().coerceAtMost(height - 1)] = y
		val rgba = ByteArray(outWidth * outHeight * 4)
		for (index in 3 until rgba.size step 4) rgba[index] = 0xFF.toByte()
		val compression = data.readUnsignedShort()
		if (compression != 0 && compression != 1) return null
		val rows = height.toLong() * channels
		val rowBytes = if (compression == 1) {
			IntArray(rows.toInt()) { if (large) data.readInt() else data.readUnsignedShort() }
		} else null
		val row = ByteArray(width)
		val decodeAlpha = preserveAlpha && channels > colors
		val readChannels = if (decodeAlpha) colors + 1 else colors
		for (channel in 0 until readChannels) {
			for (y in 0 until height) {
				val stored = rowBytes?.get(channel * height + y) ?: width
				val packed = ByteArray(stored).also(data::readFully)
				val target = targetRow[y]
				if (target < 0) continue
				if (rowBytes == null) packed.copyInto(row) else unpackRow(packed, row)
				val base = target * outWidth * 4
				for (x in 0 until outWidth) {
					val value = row[sourceX[x]]
					when {
						channel == colors -> rgba[base + x * 4 + 3] = value
						colors == 1 -> {
							rgba[base + x * 4] = value; rgba[base + x * 4 + 1] = value; rgba[base + x * 4 + 2] = value
						}
						else -> rgba[base + x * 4 + channel] = value
					}
				}
			}
		}
		if (decodeAlpha) {
			for (index in rgba.indices step 4) {
				val a = rgba[index + 3].toInt() and 0xFF
				if (a == 0) {
					rgba[index] = 0; rgba[index + 1] = 0; rgba[index + 2] = 0
				} else if (a < 255) {
					// Photoshop composites transparent pixels against white; un-matte to avoid bright halos.
					for (c in 0..2) {
						val matted = rgba[index + c].toInt() and 0xFF
						rgba[index + c] = (((matted * 255 - 255 * (255 - a)) / a).coerceIn(0, 255)).toByte()
					}
				}
			}
		}
		return RasterImage(outWidth, outHeight, rgba)
	}

	/** PackBits: a count n below 128 copies n + 1 bytes, above 128 repeats the next byte 257 - n times, 128 is a no-op. */
	private fun unpackRow(packed: ByteArray, row: ByteArray) {
		var from = 0
		var to = 0
		while (from < packed.size && to < row.size) {
			val count = packed[from++].toInt()
			when {
				count >= 0 -> {
					val run = minOf(count + 1, row.size - to, packed.size - from)
					packed.copyInto(row, to, from, from + run)
					from += count + 1
					to += run
				}
				count != -128 -> {
					if (from >= packed.size) return
					val value = packed[from++]
					val run = minOf(1 - count, row.size - to)
					row.fill(value, to, to + run)
					to += run
				}
			}
		}
	}

	private fun RasterImage.fittedWithin(maxSize: Int): RasterImage {
		if (width <= maxSize && height <= maxSize) return this
		val scale = maxSize.toDouble() / maxOf(width, height)
		val outWidth = (width * scale).toInt().coerceAtLeast(1)
		val outHeight = (height * scale).toInt().coerceAtLeast(1)
		val out = ByteArray(outWidth * outHeight * 4)
		for (y in 0 until outHeight) {
			val sy = ((y + 0.5) / scale).toInt().coerceAtMost(height - 1)
			for (x in 0 until outWidth) {
				val sx = ((x + 0.5) / scale).toInt().coerceAtMost(width - 1)
				rgba.copyInto(out, (y * outWidth + x) * 4, (sy * width + sx) * 4, (sy * width + sx) * 4 + 4)
			}
		}
		return RasterImage(outWidth, outHeight, out)
	}

	private fun RasterImage.swappedRedBlue(): RasterImage {
		val out = rgba.copyOf()
		for (at in out.indices step 4) {
			out[at] = rgba[at + 2]
			out[at + 2] = rgba[at]
		}
		return RasterImage(width, height, out)
	}

	private fun skipFully(data: DataInputStream, count: Long) {
		var left = count
		while (left > 0) {
			val skipped = data.skip(left)
			if (skipped <= 0) {
				if (data.read() < 0) throw EOFException("PSD ends inside a section")
				left--
			} else left -= skipped
		}
	}

	private fun u16(bytes: ByteArray, at: Int): Int = ((bytes[at].toInt() and 0xFF) shl 8) or (bytes[at + 1].toInt() and 0xFF)

	private fun u32(bytes: ByteArray, at: Int): Long = (u16(bytes, at).toLong() shl 16) or u16(bytes, at + 2).toLong()
}
