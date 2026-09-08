package org.umamo.format.moc3.moc

import org.umamo.format.FormatVersion

/**
 * The `.moc3` format version, stored as a single byte at file offset `0x04`.
 *
 * The byte (1..6) gates which sections are present; each value corresponds to a Cubism editor
 * release.  Newer versions only add sections at higher table indices, so a reader for a high
 * version transparently reads lower ones.
 *
 * @see <a href="https://docs.umamo.org/format/MOC3.md">MOC3.md §version gating</a>
 */
public enum class MocVersion(public val byteValue: Int, public val editorRange: String) : FormatVersion {
	/** MOC3 v1 - Cubism 3.0-3.2 (base section set). */
	V30(1, "3.0-3.2"),

	/** MOC3 v2 - Cubism 3.3. */
	V33(2, "3.3"),

	/** MOC3 v3 - Cubism 4.0. */
	V40(3, "4.0"),

	/** MOC3 v4 - Cubism 4.2 (+ warp/rotation deformer color, `Parameter.Types`, art-mesh/warp blend shapes). */
	V42(4, "4.2"),

	/** MOC3 v5 - Cubism 5.0 (+ per-form color rows; blend shapes on parts and rotation deformers). */
	V50(5, "5.0"),

	/** MOC3 v6 - Cubism 5.3 (+ offscreen rendering). */
	V53(6, "5.3"),
	;

	/** Short token combining the version byte and the editor releases that write it. */
	override val label: String get() = "moc3 v$byteValue / Cubism $editorRange"

	/**
	 * Human-readable support summary.
	 *
	 * @return String The summary sentence.
	 */
	override fun describe(): String = "Supported: $label"

	public companion object {
		/** The highest version byte understood here (matches the runtime core's latest). */
		public const val LATEST: Int = 6

		/**
		 * Resolves a raw version byte to a [MocVersion].
		 *
		 * @param Int byte The version byte from file offset 0x04.
		 * @return MocVersion The matching version.
		 * @throws IllegalArgumentException if the byte is outside 1..6.
		 */
		public fun fromByte(byte: Int): MocVersion =
			entries.firstOrNull { it.byteValue == byte }
				?: throw IllegalArgumentException("unsupported moc3 version byte: $byte")
	}
}