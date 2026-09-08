package org.umamo.format.cmo3.caff

/**
 * Preview image encoding, as stored in the CAFF header preview block.
 *
 * EN: typeNo is the on-disk byte value.
 *
 * @see <a href="https://docs.umamo.org/format/CMO3.md">CMO3.md §1 PREVIEW</a>
 */
public enum class ImageType(public val typeNo: Int) {
	NOT_INIT(0),
	PNG(1),
	NO_PREVIEW(127),
	;

	public companion object {
		/**
		 * Resolves a preview ImageType from its on-disk byte.
		 *
		 * @param Int typeNo The stored byte value.
		 * @return ImageType The matching constant.
		 */
		public fun fromTypeNo(typeNo: Int): ImageType =
			entries.firstOrNull { it.typeNo == typeNo }
				?: throw IllegalArgumentException("unknown ImageType $typeNo")
	}
}

/**
 * Preview pixel layout, as stored in the CAFF header preview block.
 *
 * EN: Preview pixel layout.
 *
 * @see <a href="https://docs.umamo.org/format/CMO3.md">CMO3.md §1 PREVIEW</a>
 */
public enum class ColorType(public val typeNo: Int) {
	NOT_INIT(0),
	ARGB(1),
	RGB(2),
	NO_PREVIEW(127),
	;

	public companion object {
		/**
		 * Resolves a preview ColorType from its on-disk byte.
		 *
		 * @param Int typeNo The stored byte value.
		 * @return ColorType The matching constant.
		 */
		public fun fromTypeNo(typeNo: Int): ColorType =
			entries.firstOrNull { it.typeNo == typeNo }
				?: throw IllegalArgumentException("unknown ColorType $typeNo")
	}
}

/**
 * Per-entry storage mode. serializeNo is the on-disk byte; zipLevel is the java.util.zip level
 * used when (re)compressing FAST/SMALL entries.
 *
 * EN: RAW = stored as-is (already-compressed PNGs);
 *     FAST/SMALL = a single-entry ("contents") Java Zip stream.
 *
 * @see <a href="https://docs.umamo.org/format/CMO3.md">CMO3.md §1 Compression</a>
 */
public enum class CompressOption(public val serializeNo: Int, public val zipLevel: Int) {
	RAW(16, 0),
	FAST(33, 1),
	SMALL(37, 5),
	;

	public val isCompressed: Boolean get() = this != RAW

	public companion object {
		/**
		 * Resolves a CompressOption from its on-disk serializeNo byte.
		 *
		 * @param Int serializeNo The stored byte value.
		 * @return CompressOption The matching constant.
		 */
		public fun fromSerializeNo(serializeNo: Int): CompressOption =
			entries.firstOrNull { it.serializeNo == serializeNo }
				?: throw IllegalArgumentException("unknown CompressOption $serializeNo")
	}
}