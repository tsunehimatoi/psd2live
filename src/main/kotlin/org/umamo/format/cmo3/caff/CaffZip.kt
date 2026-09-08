package org.umamo.format.cmo3.caff

import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import okio.Buffer
import org.umamo.format.binary.Crc32
import org.umamo.format.binary.deflateRawDeflate
import org.umamo.format.binary.inflateRawDeflate
import kotlin.time.Clock

/**
 * The single-entry zip framing CAFF wraps FAST/SMALL blobs in.
 *
 * A CAFF compressed blob is a PARTIAL zip stream holding one entry named "contents": a local file
 * header, a raw DEFLATE payload, a data descriptor, and then the end of the blob.  It carries no
 * central directory and no EOCD — measured on the corpus, not assumed (CMO3.md §1 has the byte
 * layout and the evidence).  So this is not `ZipOutputStream` output and cannot be produced by it:
 * that class always appends both records on close.  The framing is pure byte manipulation instead.
 *
 * @see <a href="https://docs.umamo.org/format/CMO3.md">CMO3.md §1 Compression</a>
 */
internal object CaffZip {
	// CMO3: the compressed blob is a single-entry zip stream; the entry is always named "contents".
	private val ENTRY_NAME = "contents".encodeToByteArray()

	// ZIP: the record signatures this framing uses (APPNOTE.TXT 4.3.7 / 4.3.9), little-endian on disk.
	private const val LOCAL_HEADER_SIGNATURE = 0x04034B50
	private const val DATA_DESCRIPTOR_SIGNATURE = 0x08074B50

	// ZIP: the local header is 30 bytes before name+extra.
	private const val LOCAL_HEADER_SIZE = 30

	// ZIP: the data descriptor closing the blob is 16 bytes — signature, crc32, csize, usize.
	private const val DATA_DESCRIPTOR_SIZE = 16

	// ZIP: version 2.0 — the minimum that understands DEFLATE, and what Java writes.
	private const val VERSION = 20

	// ZIP: general-purpose flags. Bit 3 (0x08) says the CRC and sizes follow in a data descriptor
	// rather than the local header; bit 11 (0x0800) declares the name UTF-8. The editor sets both
	// (measured: 0x0808), so a real .cmo3 carries zeroed sizes up front — a reader that trusts them
	// reads nothing.
	private const val FLAGS = 0x0808

	// ZIP: compression method 8 = DEFLATE.
	private const val METHOD_DEFLATE = 8

	// MS-DOS timestamp bounds. The date packs year-1980 into 7 bits, so the epoch is 1980 and the
	// ceiling is 2107; a clock outside that range is clamped rather than allowed to wrap into a
	// different year.
	private const val DOS_EPOCH_YEAR = 1980
	private const val DOS_MAXIMUM_YEAR = 2107

	// MS-DOS 1980-01-01 00:00 — the clamp floor, and what an out-of-range clock falls back to.
	private const val DOS_EPOCH_DATE = 0x0021
	private const val DOS_EPOCH_TIME = 0x0000

	/**
	 * Inflates a single-entry CAFF zip stream back to the raw payload.
	 *
	 * Reads only what the framing guarantees: the local header's name/extra lengths locate the
	 * payload, and the DEFLATE stream ends itself.  The sizes in the local header are ignored because
	 * the editor zeroes them (flag bit 3); the real ones are in the trailing data descriptor, which is
	 * data the inflater simply stops before.
	 *
	 * @param ByteArray zipStream The full zip stream bytes (already de-obfuscated).
	 * @return ByteArray The decompressed "contents" payload.
	 */
	fun unzipSingle(zipStream: ByteArray): ByteArray {
		require(zipStream.size >= LOCAL_HEADER_SIZE) { "Empty CAFF zip stream" }
		require(readU32(zipStream, 0) == LOCAL_HEADER_SIGNATURE.toLong()) { "CAFF blob is not a zip local file header" }
		// ZIP: local header @ +0x1A name length, @ +0x1C extra length; the payload follows both.
		val nameLength = readU16(zipStream, 26)
		val extraLength = readU16(zipStream, 28)
		val payloadStart = LOCAL_HEADER_SIZE + nameLength + extraLength
		require(payloadStart <= zipStream.size) { "Truncated CAFF zip local header" }
		return inflateRawDeflate(zipStream, payloadStart, zipStream.size - payloadStart, declaredSize(zipStream))
	}

	/**
	 * The uncompressed size the blob's trailing data descriptor declares, else [Int.MAX_VALUE].
	 *
	 * This is the only output bound the format offers.  The CAFF file table's storedSize delimits the
	 * INPUT — compressed bytes — and says nothing about how far they inflate: the corpus blobs expand
	 * 3.5x to 6.7x, but DEFLATE reaches ~1000:1, so a crafted entry could otherwise balloon toward
	 * gigabytes in memory before anyone notices.  A blob ends at its data descriptor (CMO3.md §1 has
	 * the measured layout and the per-sample numbers), and that record's usize field is exactly the
	 * bound wanted.
	 *
	 * Treated as a cap, never a promise: a descriptor that lies truncates only its own entry, and
	 * anything unrecognizable falls back to unbounded rather than corrupting an otherwise valid read.
	 *
	 * @param ByteArray zipStream The full blob.
	 * @return Int The declared uncompressed size, or Int.MAX_VALUE when no usable descriptor is found.
	 */
	private fun declaredSize(zipStream: ByteArray): Int {
		if (zipStream.size < LOCAL_HEADER_SIZE + DATA_DESCRIPTOR_SIZE) {
			return Int.MAX_VALUE
		}
		val descriptorStart = zipStream.size - DATA_DESCRIPTOR_SIZE
		if (readU32(zipStream, descriptorStart) != DATA_DESCRIPTOR_SIGNATURE.toLong()) {
			return Int.MAX_VALUE
		}
		// ZIP: data descriptor @ +0x0C uncompressed size. Zero is what the LOCAL header carries, so a
		// zero here reads as "not really a descriptor"; past Int range needs zip64 and cannot be a
		// ByteArray length anyway. Both fall back rather than truncate.
		val declared = readU32(zipStream, descriptorStart + 12)
		return if (declared in 1..Int.MAX_VALUE.toLong()) declared.toInt() else Int.MAX_VALUE
	}

	/**
	 * Wraps [contents] in a single-entry ("contents") zip stream at the given level.
	 *
	 * @param ByteArray contents  The raw payload to compress.
	 * @param Int       level     DEFLATE level (CompressOption.zipLevel).
	 * @param Int dosDateTime     The MS-DOS modification stamp: date in the high 16 bits, time in the
	 *                            low 16.  Defaults to the local wall clock, which is what the editor
	 *                            writes; injectable so tests can assert on the emitted bytes.
	 * @return ByteArray The zip stream bytes (before any obfuscation).
	 */
	fun zipSingle(contents: ByteArray, level: Int, dosDateTime: Int = currentDosDateTime()): ByteArray {
		val deflated = deflateRawDeflate(contents, level)
		val checksum = Crc32().also { it.update(contents) }.value.toInt()
		val out = Buffer()

		// ZIP: local file header (APPNOTE.TXT 4.3.7).
		// https://pkware.cachefly.net/webdocs/casestudies/APPNOTE.TXT
		out.writeIntLe(LOCAL_HEADER_SIGNATURE)
		out.writeShortLe(VERSION)
		out.writeShortLe(FLAGS)
		out.writeShortLe(METHOD_DEFLATE)
		// ZIP: local header @ +0x0A time, @ +0x0C date. Both little-endian 16-bit, in LOCAL time.
		out.writeShortLe(dosDateTime and 0xFFFF)
		out.writeShortLe((dosDateTime ushr 16) and 0xFFFF)
		// Zeroed here and carried in the data descriptor instead — that is what flag bit 3 means.
		out.writeIntLe(0)
		out.writeIntLe(0)
		out.writeIntLe(0)
		out.writeShortLe(ENTRY_NAME.size)
		out.writeShortLe(0)
		out.write(ENTRY_NAME)

		out.write(deflated)

		// ZIP: data descriptor (APPNOTE.TXT 4.3.9) — the real CRC and sizes.
		out.writeIntLe(DATA_DESCRIPTOR_SIGNATURE)
		out.writeIntLe(checksum)
		out.writeIntLe(deflated.size)
		out.writeIntLe(contents.size)

		// Ends here — no central directory, no end-of-central-directory record. That is not an
		// omission, it is what a CAFF blob measurably is: de-obfuscating the corpus sample finds one
		// PK\x03\x04 and one PK\x07\x08, and zero of either record (CMO3.md §1). Re-emitting without
		// them reproduces the original's exact file size, which is the same fact from the other side.
		// Writing them would append 76 bytes the format does not carry, growing the file on each save
		// — and is why this cannot be ZipOutputStream, which emits both unconditionally on close.
		return out.readByteArray()
	}

	/**
	 * The local wall clock now, as an MS-DOS date/time pair.
	 *
	 * Local, not UTC: a DOS timestamp has no zone, and the editor writes local time — matching it is
	 * the point of stamping a clock here at all.
	 *
	 * @return Int The stamp: date in the high 16 bits, time in the low 16.
	 */
	private fun currentDosDateTime(): Int {
		val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
		return dosDateTimeOf(now.year, now.month.number, now.day, now.hour, now.minute, now.second)
	}

	/**
	 * Packs a local date and time into the MS-DOS format ZIP local headers use.
	 *
	 * Date: year-1980 in bits 15..9, month in 8..5, day in 4..0.  Time: hour in 15..11, minute in
	 * 10..5, and seconds HALVED into 4..0 — which is why a DOS stamp has two-second resolution.
	 *
	 * A year outside 1980..2107 cannot be expressed in the 7 bits available, so it clamps to the epoch
	 * rather than wrapping into a wrong-but-plausible year.
	 *
	 * @param Int year   Local year.
	 * @param Int month  Local month, 1..12.
	 * @param Int day    Local day of month, 1..31.
	 * @param Int hour   Local hour, 0..23.
	 * @param Int minute Local minute, 0..59.
	 * @param Int second Local second, 0..59.
	 * @return Int The stamp: date in the high 16 bits, time in the low 16.
	 */
	internal fun dosDateTimeOf(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): Int {
		if (year < DOS_EPOCH_YEAR || year > DOS_MAXIMUM_YEAR) {
			return (DOS_EPOCH_DATE shl 16) or DOS_EPOCH_TIME
		}
		val date = ((year - DOS_EPOCH_YEAR) shl 9) or (month shl 5) or day
		val time = (hour shl 11) or (minute shl 5) or (second / 2)
		return (date shl 16) or time
	}

	/**
	 * Reads a little-endian unsigned 16-bit value.
	 *
	 * @param ByteArray bytes The buffer.
	 * @param Int at          Offset of the low byte.
	 * @return Int The value in 0..65535.
	 */
	private fun readU16(bytes: ByteArray, at: Int): Int = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

	/**
	 * Reads a little-endian unsigned 32-bit value as a Long (so signatures compare unsigned).
	 *
	 * @param ByteArray bytes The buffer.
	 * @param Int at          Offset of the low byte.
	 * @return Long The value in 0..4294967295.
	 */
	private fun readU32(bytes: ByteArray, at: Int): Long =
		(bytes[at].toLong() and 0xFF) or
			((bytes[at + 1].toLong() and 0xFF) shl 8) or
			((bytes[at + 2].toLong() and 0xFF) shl 16) or
			((bytes[at + 3].toLong() and 0xFF) shl 24)
}