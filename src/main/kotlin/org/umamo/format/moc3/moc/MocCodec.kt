package org.umamo.format.moc3.moc

import org.umamo.format.moc3.io.LittleEndianReader
import org.umamo.format.moc3.io.LittleEndianWriter

/**
 * Reads and writes the `.moc3` binary container.
 *
 * A `.moc3` is a 64-byte header, a section-offset table at `0x40`, then the sections the table
 * points at (each 16-byte-or-coarser aligned, the data region beginning at
 * [Sections.DATA_SECTION_BEGIN]). The exporter appends sections sequentially and pads the file
 * to a multiple of 64, so the sections partition `[firstOffset, EOF)` in table order. We keep
 * every section verbatim, so [write] reproduces an unedited file byte-for-byte.
 *
 * @see <a href="https://docs.umamo.org/format/MOC3.md">MOC3.md</a>
 */
public object MocCodec {
	private const val HEADER_SIZE: Int = 64
	private val MAGIC: ByteArray = byteArrayOf(0x4D, 0x4F, 0x43, 0x33) // "MOC3"

	/**
	 * True if [bytes] starts with the `MOC3` magic and is at least header-sized.
	 *
	 * @param ByteArray bytes Candidate file contents.
	 * @return Boolean Whether this looks like a `.moc3`.
	 */
	public fun isMoc3(bytes: ByteArray): Boolean =
		// MOC3 header: magic "MOC3" @ +0x00
		bytes.size >= HEADER_SIZE && bytes[0] == MAGIC[0] && bytes[1] == MAGIC[1] && bytes[2] == MAGIC[2] && bytes[3] == MAGIC[3]

	/**
	 * Parses a `.moc3` from raw bytes.
	 *
	 * @param ByteArray bytes The file contents.
	 * @return MocModel The parsed header, offset table, and section bytes.
	 * @throws IllegalArgumentException if the magic, version, or structure is invalid.
	 */
	public fun read(bytes: ByteArray): MocModel {
		require(isMoc3(bytes)) { "Not a moc3: bad magic or too small" }
		val versionByte = bytes[4].toInt() and 0xFF // MOC3 header: version @ +0x04
		require(versionByte in 1..MocVersion.LATEST) { "Unsupported moc3 version byte: $versionByte" }

		// MOC3 offset table @ +0x40: dense run of u32 file offsets, terminated by zero padding.
		// (Editor output never has interior zero offsets; a 0 marks the start of the pad to the data
		// region.)
		val reader = LittleEndianReader(bytes)
		reader.seek(0x40)
		val offsets = ArrayList<Int>()
		while (reader.position + 4 <= bytes.size) {
			val value = reader.readU32()
			if (value == 0L) {
				break
			}
			offsets.add(value.toInt())
		}
		require(offsets.isNotEmpty()) { "moc3 has no sections" }

		val sectionCount = offsets.size
		val sections =
			Array(sectionCount) { sectionIndex ->
				val start = offsets[sectionIndex]
				val end = if (sectionIndex + 1 < sectionCount) offsets[sectionIndex + 1] else bytes.size
				require(start in HEADER_SIZE..bytes.size && end in start..bytes.size) {
					"section $sectionIndex offset out of range: [$start, $end) in ${bytes.size}"
				}
				bytes.copyOfRange(start, end)
			}
		return MocModel(bytes.copyOfRange(0, HEADER_SIZE), offsets.toIntArray(), sections)
	}

	/**
	 * Serializes a [MocModel] back to `.moc3` bytes.
	 * Byte-identical to the source for an unedited model.
	 *
	 * @param MocModel model The model to write.
	 * @return ByteArray The complete `.moc3` file bytes.
	 */
	public fun write(model: MocModel): ByteArray {
		val firstOffset = model.offsets[0]
		val tableEnd = HEADER_SIZE + model.offsets.size * 4
		require(firstOffset >= tableEnd) { "Offset table ($tableEnd B) overruns the first section ($firstOffset)" }

		val total = firstOffset + model.rawSections.sumOf { it.size }
		val writer = LittleEndianWriter(total)
		writer.writeBytes(model.header) // 64-byte header (magic, version, endian flag, reserved)
		for (offset in model.offsets) writer.writeInt32(offset)
		writer.zeroPad(firstOffset - writer.position) // pad header+table region up to the data region
		for (section in model.rawSections) writer.writeBytes(section)
		return writer.toByteArray()
	}
}