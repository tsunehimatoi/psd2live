// TIFF header + IFD parsing, modelled on TwelveMonkeys imageio-metadata TIFFReader (BSD-3-Clause,
// Copyright (c) 2009 Harald Kuhr) but reimplemented over a ByteArray.  See CREDITS.md.

package org.umamo.format.tiff

import org.umamo.format.binary.ByteReader

/**
 * The parsed fields of one TIFF image file directory (IFD): each numeric tag mapped to its values as
 * a [LongArray], plus the raw bytes of UNDEFINED-typed tags (JPEGTables carries an abbreviated JPEG
 * stream that way).  Other non-numeric fields (ASCII, RATIONAL, …) the baseline reader does not need
 * are dropped during parsing.
 */
internal class TiffDirectory(
	private val values: Map<Int, LongArray>,
	private val rawValues: Map<Int, ByteArray>,
	val littleEndian: Boolean,
) {
	/**
	 * All values of [tag], or null when the tag is absent.
	 *
	 * @param Int tag The TIFF tag id.
	 * @return LongArray? The values, or null.
	 */
	fun longs(tag: Int): LongArray? = values[tag]

	/**
	 * The raw bytes of an UNDEFINED-typed [tag], or null when absent or otherwise typed.
	 *
	 * @param Int tag The TIFF tag id.
	 * @return ByteArray? The raw field bytes, or null.
	 */
	fun bytes(tag: Int): ByteArray? = rawValues[tag]

	/**
	 * All values of [tag] as an IntArray, or null when absent.
	 *
	 * @param Int tag The TIFF tag id.
	 * @return IntArray? The values, or null.
	 */
	fun ints(tag: Int): IntArray? = values[tag]?.let { source -> IntArray(source.size) { source[it].toInt() } }

	/**
	 * The first value of [tag] as an Int, or [default] when absent.
	 *
	 * @param Int tag     The TIFF tag id.
	 * @param Int default The fallback value.
	 * @return Int The first value or the default.
	 */
	fun int(tag: Int, default: Int): Int = values[tag]?.firstOrNull()?.toInt() ?: default

	/**
	 * Whether [tag] is present.
	 *
	 * @param Int tag The TIFF tag id.
	 * @return Boolean True if the directory carries the tag.
	 */
	fun has(tag: Int): Boolean = values.containsKey(tag) || rawValues.containsKey(tag)
}

/**
 * Parses the TIFF header and the first IFD into a [TiffDirectory].
 *
 * @param ByteArray bytes The complete `.tiff` file.
 * @return TiffDirectory The first directory's numeric fields.
 */
internal fun parseFirstDirectory(bytes: ByteArray): TiffDirectory {
	require(bytes.size >= 8) { "TIFF too short for a header" }
	// TIFF: byte-order mark @ +0x00 - 'II' little-endian, 'MM' big-endian.
	val littleEndian =
		when {
			bytes[0] == 0x49.toByte() && bytes[1] == 0x49.toByte() -> true
			bytes[0] == 0x4D.toByte() && bytes[1] == 0x4D.toByte() -> false
			else -> throw IllegalArgumentException("Not a TIFF (bad byte-order mark)")
		}
	val reader = ByteReader(bytes, littleEndian = littleEndian)
	// TIFF: magic @ +0x02 - 42 (classic).  43 is BigTIFF, out of scope.
	val magic = reader.u16(2)
	require(magic == TiffConstants.TIFF_MAGIC) {
		if (magic == TiffConstants.BIGTIFF_MAGIC) "BigTIFF is not supported" else "Unsupported TIFF magic $magic"
	}
	val firstIfdOffset = reader.u32AsInt(4)
	return readDirectory(reader, firstIfdOffset)
}

/**
 * Reads the IFD at [offset]: an entry count then that many 12-byte entries.
 *
 * @param ByteReader reader The file reader (byte order already set).
 * @param Int offset        Offset of the directory.
 * @return TiffDirectory The parsed directory.
 */
private fun readDirectory(reader: ByteReader, offset: Int): TiffDirectory {
	require(offset in 0..reader.bytes.size - 2) { "TIFF IFD offset out of range" }
	val entryCount = reader.u16(offset)
	val values = HashMap<Int, LongArray>(entryCount)
	val rawValues = HashMap<Int, ByteArray>()
	var entryOffset = offset + 2
	for (entryIndex in 0 until entryCount) {
		if (entryOffset + 12 > reader.bytes.size) {
			break
		}
		// TIFF: entry = tag(u16) | type(u16) | count(u32) | value-or-offset(4).
		val tag = reader.u16(entryOffset)
		val type = reader.u16(entryOffset + 2)
		val valueCount = reader.u32AsInt(entryOffset + 4)
		val numericValues = readNumericValues(reader, type, valueCount, entryOffset + 8)
		if (numericValues != null) {
			values[tag] = numericValues
		}
		val undefinedValues = readUndefinedValues(reader, type, valueCount, entryOffset + 8)
		if (undefinedValues != null) {
			rawValues[tag] = undefinedValues
		}
		entryOffset += 12
	}
	return TiffDirectory(values, rawValues, reader.littleEndian)
}

/**
 * Reads the raw bytes of an UNDEFINED-typed field (TIFF type 7), which the spec defines as an opaque
 * byte string.  Returns null for every other type.
 *
 * @param ByteReader reader    The file reader.
 * @param Int type             The TIFF field type code.
 * @param Int count            The value count (bytes, for UNDEFINED).
 * @param Int valueFieldOffset Offset of the entry's 4-byte value/offset field.
 * @return ByteArray? The field bytes, or null.
 */
private fun readUndefinedValues(reader: ByteReader, type: Int, count: Int, valueFieldOffset: Int): ByteArray? {
	if (type != TiffConstants.TYPE_UNDEFINED || count <= 0) {
		return null
	}
	// Up to four bytes are stored inline in the value field; longer strings live at the offset it holds.
	val base = if (count <= 4) valueFieldOffset else reader.u32AsInt(valueFieldOffset)
	if (base < 0 || base.toLong() + count > reader.bytes.size) {
		return null
	}
	return reader.bytes.copyOfRange(base, base + count)
}

/**
 * Reads [count] values of a numeric TIFF [type] starting at the entry's value field (inline if the
 * total fits in four bytes, else at the offset the value field points to).  Returns null for
 * non-numeric types (ASCII/RATIONAL/…), which the baseline reader ignores.
 *
 * @param ByteReader reader       The file reader.
 * @param Int type                The TIFF field type code.
 * @param Int count               The value count.
 * @param Int valueFieldOffset    Offset of the entry's 4-byte value/offset field.
 * @return LongArray? The values, or null when the type is not numeric.
 */
private fun readNumericValues(reader: ByteReader, type: Int, count: Int, valueFieldOffset: Int): LongArray? {
	val typeLength = if (type in TiffConstants.TYPE_LENGTHS.indices) TiffConstants.TYPE_LENGTHS[type] else -1
	val numeric =
		type == TiffConstants.TYPE_BYTE ||
			type == TiffConstants.TYPE_SHORT ||
			type == TiffConstants.TYPE_LONG ||
			type == TiffConstants.TYPE_SBYTE ||
			type == TiffConstants.TYPE_SSHORT ||
			type == TiffConstants.TYPE_SLONG
	if (!numeric || typeLength <= 0 || count < 0) {
		return null
	}
	val totalBytes = count.toLong() * typeLength
	val base = if (totalBytes <= 4) valueFieldOffset else reader.u32AsInt(valueFieldOffset)
	if (base < 0 || base + totalBytes > reader.bytes.size) {
		return null
	}
	val out = LongArray(count)
	var cursor = base
	for (valueIndex in 0 until count) {
		out[valueIndex] =
			when (type) {
				TiffConstants.TYPE_BYTE -> reader.u8(cursor).toLong()
				TiffConstants.TYPE_SBYTE -> reader.bytes[cursor].toLong()
				TiffConstants.TYPE_SHORT -> reader.u16(cursor).toLong()
				TiffConstants.TYPE_SSHORT -> reader.u16(cursor).toShort().toLong()
				TiffConstants.TYPE_LONG -> reader.u32(cursor)
				TiffConstants.TYPE_SLONG -> reader.u32(cursor).toInt().toLong()
				else -> 0L
			}
		cursor += typeLength
	}
	return out
}