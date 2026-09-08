package org.umamo.format.cmo3.caff

/**
 * One logical file inside a CAFF archive. [content] is the fully-decoded payload
 * (de-obfuscated and decompressed); compression/obfuscation describe how it is stored on disk.
 *
 * EN: Holds decoded bytes rather than a file offset.
 * JA: オフセットではなくデコード済みバイトを保持する。
 *
 * @see <a href="https://docs.umamo.org/format/CMO3.md">CMO3.md §1 FILE TABLE</a>
 */
public class CaffEntry(
	public val path: String,
	public val tag: String,
	public val content: ByteArray,
	public val compression: CompressOption = CompressOption.RAW,
	public val obfuscated: Boolean = true,
)

/**
 * The CAFF preview thumbnail. Most cmo3 files carry [present] == false (NO_PREVIEW).
 *
 * @see <a href="https://docs.umamo.org/format/CMO3.md">CMO3.md §1 PREVIEW</a>
 */
public class CaffPreview(
	public val imageType: ImageType,
	public val colorType: ColorType,
	public val width: Int,
	public val height: Int,
	/** The preview PNG bytes, or null when [imageType] is NO_PREVIEW. */
	public val png: ByteArray?,
) {
	public val present: Boolean get() = imageType == ImageType.PNG && png != null

	public companion object {
		/** A NO_PREVIEW placeholder (what real cmo3 files contain). */
		public fun none(): CaffPreview =
			CaffPreview(ImageType.NO_PREVIEW, ColorType.NO_PREVIEW, 0, 0, null)
	}
}

/**
 * An in-memory CAFF archive: header metadata, optional preview, and the ordered file entries.
 *
 * EN: Entry order is significant - the model serializer assigns file paths positionally, and
 *     consumers map them by order (e.g. the i-th CImageResource ↔ the i-th imageFileBuf*).
 *
 * @see <a href="https://docs.umamo.org/format/CMO3.md">CMO3.md §1 Container: CAFF</a>
 */
public class CaffArchive(
	/** 4-char format identifier; "----" for cmo3. */
	public val formatIdentifier: String = DEFAULT_FORMAT_IDENTIFIER,
	/** 3-element format version; {0,0,0} for the sample. */
	public val formatVersion: IntArray = intArrayOf(0, 0, 0),
	/** 3-element archive version; {0,0,0} for the sample. */
	public val archiveVersion: IntArray = intArrayOf(0, 0, 0),
	/** XOR key; 0 => not obfuscated. */
	public val obfuscateKey: Int = 0,
	public val preview: CaffPreview = CaffPreview.none(),
	public val entries: List<CaffEntry> = emptyList(),
) {
	/** Returns the first entry carrying [tag], or null. */
	public fun firstByTag(tag: String): CaffEntry? = entries.firstOrNull { it.tag == tag }

	/** Returns all entries carrying [tag], in archive order. */
	public fun allByTag(tag: String): List<CaffEntry> = entries.filter { it.tag == tag }

	/** Returns the first entry with an exact [path], or null. */
	public fun byPath(path: String): CaffEntry? = entries.firstOrNull { it.path == path }

	/** Returns a copy of this archive with a different entry list (other metadata unchanged). */
	public fun withEntries(entries: List<CaffEntry>): CaffArchive =
		CaffArchive(formatIdentifier, formatVersion, archiveVersion, obfuscateKey, preview, entries)

	public companion object {
		public const val MAGIC: String = "CAFF"
		public const val DEFAULT_FORMAT_IDENTIFIER: String = "----"

		/** Archive tag for the serialized model document (main.xml). */
		public const val TAG_MAIN_XML: String = "main_xml"

		/** Archive tag for the preview image entry. */
		public const val TAG_PREVIEW: String = "preview"
	}
}