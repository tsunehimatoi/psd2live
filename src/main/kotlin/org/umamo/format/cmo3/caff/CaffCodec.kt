package org.umamo.format.cmo3.caff

import org.umamo.format.cmo3.io.BinaryReader
import org.umamo.format.cmo3.io.BinaryWriter

/**
 * Reads and writes the CAFF container. Stateless; operates on whole byte arrays.
 *
 * EN: Layout is header (unobfuscated) -> preview block (unobfuscated) -> file table (obfuscated)
 *     -> concatenated blobs -> guard bytes 'b''c'. See CMO3.md §1 for the exact byte map.
 * JA: ヘッダ → プレビュー → ファイルテーブル → ブロブ群 → ガードバイト。詳細は CMO3.md §1。
 *
 * @see <a href="https://docs.umamo.org/format/CMO3.md">CMO3.md §1 Container: CAFF</a>
 */
public object CaffCodec {
	private const val HEADER_AND_PREVIEW_BYTES = 54 // header(26) + preview(28); blobs/table follow
	private const val GUARD_BYTE_0 = 0x62 // 'b'
	private const val GUARD_BYTE_1 = 0x63 // 'c'

	private class EntryMeta(
		val path: String,
		val tag: String,
		val offset: Int,
		val storedSize: Int,
		val obfuscated: Boolean,
		val compression: CompressOption,
	)

	private class HeaderAndTable(
		val archiveVersion: IntArray,
		val formatIdentifier: String,
		val formatVersion: IntArray,
		val obfuscateKey: Int,
		val previewImageType: ImageType,
		val previewColorType: ColorType,
		val previewWidth: Int,
		val previewHeight: Int,
		val previewStartPos: Long,
		val previewFileSize: Int,
		val metas: List<EntryMeta>,
	)

	/**
	 * True if [candidateBytes] starts with the unobfuscated `CAFF` magic.
	 *
	 * The magic sits at +0x00 and is read with XOR key 0 (cleartext), so a leading-bytes compare is
	 * a sound sniff without parsing the container - CMO3.md §1.
	 *
	 * @param ByteArray candidateBytes Candidate file contents.
	 * @return Boolean Whether this looks like a CAFF container (e.g. a `.cmo3`).
	 */
	public fun isCaff(candidateBytes: ByteArray): Boolean {
		val magic = CaffArchive.MAGIC.encodeToByteArray() // CAFF: magic @ +0x00, unobfuscated
		if (candidateBytes.size < magic.size) {
			return false
		}
		for (magicIndex in magic.indices) {
			if (candidateBytes[magicIndex] != magic[magicIndex]) {
				return false
			}
		}
		return true
	}

	/**
	 * Parses the header, preview block, and file table, leaving every blob undecoded.
	 *
	 * @param BinaryReader reader A reader positioned at the start of the file.
	 * @return HeaderAndTable The parsed container metadata and entry table.
	 */
	private fun readHeaderAndTable(reader: BinaryReader): HeaderAndTable {
		// --- HEADER (not obfuscated) - CMO3.md §1 ---
		val magic = reader.readBytes(4, 0).decodeToString()
		require(magic == CaffArchive.MAGIC) { "not a CAFF file (magic=$magic)" }
		val archiveVersion = IntArray(3) { reader.readU8(0) }
		val formatIdentifier = reader.readBytes(4, 0).decodeToString()
		val formatVersion = IntArray(3) { reader.readU8(0) }
		val obfuscateKey = reader.readInt32(0)
		reader.skip(8)

		// --- PREVIEW (not obfuscated) - CMO3.md §1 ---
		val previewImageType = ImageType.fromTypeNo(reader.readU8(0))
		val previewColorType = ColorType.fromTypeNo(reader.readU8(0))
		reader.skip(2)
		val previewWidth = reader.readInt16(0).toInt()
		val previewHeight = reader.readInt16(0).toInt()
		val previewStartPos = reader.readInt64(0) and 0xFFFFFFFFL
		val previewFileSize = reader.readInt32(0)
		reader.skip(8)

		// --- FILE TABLE (obfuscated with obfuscateKey) - CMO3.md §1 ---
		val count = reader.readInt32(obfuscateKey)
		val metas = ArrayList<EntryMeta>(count)
		for (entryIndex in 0 until count) {
			val path = reader.readString(obfuscateKey)
			val tag = reader.readString(obfuscateKey)
			// CMO3: startPos high word is a writer artifact; the real offset is the low 32 bits.
			val startPos = reader.readInt64(obfuscateKey) and 0xFFFFFFFFL
			val fileSize = reader.readInt32(obfuscateKey)
			val obfuscated = reader.readBool(obfuscateKey)
			val compression = CompressOption.fromSerializeNo(reader.readU8(obfuscateKey))
			reader.skip(8)
			metas.add(EntryMeta(path, tag, startPos.toInt(), fileSize, obfuscated, compression))
		}

		return HeaderAndTable(
			archiveVersion,
			formatIdentifier,
			formatVersion,
			obfuscateKey,
			previewImageType,
			previewColorType,
			previewWidth,
			previewHeight,
			previewStartPos,
			previewFileSize,
			metas,
		)
	}

	/**
	 * Slices, de-obfuscates, and decompresses one entry's blob.
	 *
	 * @param BinaryReader reader       The reader over the full file bytes.
	 * @param EntryMeta    meta         The entry's file-table record.
	 * @param Int          obfuscateKey The archive's XOR key.
	 * @return CaffEntry The decoded entry.
	 */
	private fun decodeEntry(reader: BinaryReader, meta: EntryMeta, obfuscateKey: Int): CaffEntry {
		reader.seek(meta.offset)
		val stored = reader.readBytes(meta.storedSize, if (meta.obfuscated) obfuscateKey else 0)
		val content = if (meta.compression.isCompressed) CaffZip.unzipSingle(stored) else stored
		return CaffEntry(meta.path, meta.tag, content, meta.compression, meta.obfuscated)
	}

	/**
	 * Parses a CAFF archive from its raw bytes, decoding every entry's payload.
	 *
	 * @param ByteArray bytes The full .cmo3 (or other CAFF) file contents.
	 * @return CaffArchive The decoded archive.
	 */
	public fun read(bytes: ByteArray): CaffArchive {
		val reader = BinaryReader(bytes)
		val parsed = readHeaderAndTable(reader)

		// --- BLOBS: slice, de-obfuscate, decompress ---
		val entries = parsed.metas.map { meta -> decodeEntry(reader, meta, parsed.obfuscateKey) }

		// Preview blob (RAW, not obfuscated)
		val preview =
			if (parsed.previewImageType == ImageType.PNG && parsed.previewFileSize > 0) {
				reader.seek(parsed.previewStartPos.toInt())
				val png = reader.readBytes(parsed.previewFileSize, 0)
				CaffPreview(parsed.previewImageType, parsed.previewColorType, parsed.previewWidth, parsed.previewHeight, png)
			} else {
				CaffPreview(parsed.previewImageType, parsed.previewColorType, parsed.previewWidth, parsed.previewHeight, null)
			}

		return CaffArchive(
			parsed.formatIdentifier,
			parsed.formatVersion,
			parsed.archiveVersion,
			parsed.obfuscateKey,
			preview,
			entries,
		)
	}

	/**
	 * Decodes only the first entry carrying [tag], leaving every other blob untouched.
	 *
	 * A cheap probe next to [read]: the header and file table are parsed (CMO3.md §1 FILE TABLE), but
	 * just the matching blob is de-obfuscated and decompressed - a version sniff over a `.cmo3` skips
	 * inflating the embedded layer PNGs this way.
	 *
	 * @param ByteArray bytes The full .cmo3 (or other CAFF) file contents.
	 * @param String    tag   The archive tag to look up (e.g. [CaffArchive.TAG_MAIN_XML]).
	 * @return CaffEntry? The decoded entry, or null when no entry carries the tag.
	 */
	public fun readFirstEntryByTag(bytes: ByteArray, tag: String): CaffEntry? {
		val reader = BinaryReader(bytes)
		val parsed = readHeaderAndTable(reader)
		val meta = parsed.metas.firstOrNull { it.tag == tag } ?: return null
		return decodeEntry(reader, meta, parsed.obfuscateKey)
	}

	/**
	 * Serializes an archive back into CAFF bytes. Offsets are computed in one pass (no seek-back
	 * fixup) and written as clean 64-bit values (high word 0).
	 *
	 * @param CaffArchive archive The archive to encode.
	 * @return ByteArray The complete CAFF file bytes.
	 */
	public fun write(archive: CaffArchive): ByteArray {
		val key = archive.obfuscateKey

		// Pre-encode each blob (compress, but XOR happens at write time via writeBytes).
		val storedBlobs =
			archive.entries.map { entry ->
				if (entry.compression.isCompressed) {
					CaffZip.zipSingle(entry.content, entry.compression.zipLevel)
				} else {
					entry.content
				}
			}

		// Compute the table size so we know where blobs start, then assign sequential offsets.
		var tableBytes = 4 // count
		for (entry in archive.entries) {
			tableBytes += varIntSize(entry.path.encodeToByteArray().size) + entry.path.encodeToByteArray().size
			tableBytes += varIntSize(entry.tag.encodeToByteArray().size) + entry.tag.encodeToByteArray().size
			tableBytes += 8 + 4 + 1 + 1 + 8 // startPos, fileSize, isObfuscated, compress, skip(8)
		}
		val blobStart = HEADER_AND_PREVIEW_BYTES + tableBytes
		val offsets = IntArray(archive.entries.size)
		var cursor = blobStart
		for (index in storedBlobs.indices) {
			offsets[index] = cursor
			cursor += storedBlobs[index].size
		}

		val writer = BinaryWriter(cursor + 2)

		// --- HEADER ---
		writer.writeBytes(CaffArchive.MAGIC.encodeToByteArray(), 0)
		for (versionPart in 0 until 3) writer.writeU8(archive.archiveVersion[versionPart], 0)
		writer.writeBytes(padFour(archive.formatIdentifier), 0)
		for (versionPart in 0 until 3) writer.writeU8(archive.formatVersion[versionPart], 0)
		writer.writeInt32(key, 0)
		writer.skip(8)

		// --- PREVIEW ---
		val previewEntryIndex = archive.entries.indexOfFirst { it.tag == CaffArchive.TAG_PREVIEW }
		if (archive.preview.present && previewEntryIndex >= 0) {
			writer.writeU8(ImageType.PNG.typeNo, 0)
			writer.writeU8(archive.preview.colorType.typeNo, 0)
			writer.skip(2)
			writer.writeInt16(archive.preview.width, 0)
			writer.writeInt16(archive.preview.height, 0)
			writer.writeInt64(offsets[previewEntryIndex].toLong(), 0)
			writer.writeInt32(storedBlobs[previewEntryIndex].size, 0)
			writer.skip(8)
		} else {
			writer.writeU8(ImageType.NO_PREVIEW.typeNo, 0)
			writer.writeU8(ColorType.NO_PREVIEW.typeNo, 0)
			writer.skip(2)
			writer.writeInt16(0, 0)
			writer.writeInt16(0, 0)
			writer.writeInt64(0, 0)
			writer.writeInt32(0, 0)
			writer.skip(8)
		}

		// --- FILE TABLE ---
		writer.writeInt32(archive.entries.size, key)
		for (index in archive.entries.indices) {
			val entry = archive.entries[index]
			writer.writeString(entry.path, key)
			writer.writeString(entry.tag, key)
			writer.writeInt64(offsets[index].toLong(), key)
			writer.writeInt32(storedBlobs[index].size, key)
			writer.writeBool(entry.obfuscated, key)
			writer.writeU8(entry.compression.serializeNo, key)
			writer.skip(8)
		}

		// --- BLOBS ---
		for (index in archive.entries.indices) {
			val entry = archive.entries[index]
			writer.writeBytes(storedBlobs[index], if (entry.obfuscated) key else 0)
		}

		// --- GUARD BYTES (not obfuscated) ---
		writer.writeU8(GUARD_BYTE_0, 0)
		writer.writeU8(GUARD_BYTE_1, 0)

		return writer.toByteArray()
	}

	private fun varIntSize(value: Int): Int =
		when {
			value < 0x80 -> 1
			value < 0x4000 -> 2
			value < 0x200000 -> 3
			value < 0x10000000 -> 4
			else -> throw IllegalArgumentException("varint too large: $value")
		}

	private fun padFour(identifier: String): ByteArray {
		val bytes = identifier.encodeToByteArray()
		require(bytes.size == 4) { "format identifier must be 4 bytes: '$identifier'" }
		return bytes
	}
}