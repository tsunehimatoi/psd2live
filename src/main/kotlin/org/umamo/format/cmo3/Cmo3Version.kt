package org.umamo.format.cmo3

import org.umamo.format.FormatVersion

/**
 * Known CMO3 schema generations Umamo supports.
 *
 * Modelled as a `sealed interface` so the compiler forces every `when` over versions to be
 * exhaustive: adding a generation without handling it everywhere becomes a compile error, not a
 * silent runtime gap.
 *
 * 対応する CMO3 スキーマ世代。sealed interface により `when` の網羅性をコンパイラが強制する。
 */
sealed interface Cmo3Version : FormatVersion {
	// `data object` = a singleton with generated equals/hashCode/toString. There is exactly one
	// of each version, so an object (not a class) is the right modelling.
	data object V3 : Cmo3Version {
		override val label = "cmo3 / Cubism 3.x"
	}

	data object V4 : Cmo3Version {
		override val label = "cmo3 / Cubism 4.x"
	}

	data object V5 : Cmo3Version {
		override val label = "cmo3 / Cubism 5.x"
	}

	/**
	 * Human-readable summary. The `when` has no `else` branch on purpose - the sealed hierarchy makes
	 * it exhaustive, so a future `V6` won't compile until handled here.
	 *
	 * @return String The summary sentence.
	 */
	override fun describe(): String =
		when (this) {
			V3 -> "Supported: $label"
			V4 -> "Supported: $label"
			V5 -> "Supported: $label"
		}

	companion object {
		/**
		 * Maps a `<root fileFormatVersion="...">` attribute value to a known generation, or null.
		 *
		 * The attribute is a packed decimal whose leading digit is the Cubism major generation
		 * (corpus: 401010001 and 400050002 are 4.x, 501030000 is 5.x) - CMO3.md §3 Document shape.
		 *
		 * @param String raw The attribute text.
		 * @return Cmo3Version? The matching generation, or null for garbage or an unknown generation.
		 */
		fun fromFileFormatVersion(raw: String): Cmo3Version? =
			when (raw.trim().toLongOrNull()?.div(100_000_000L)) {
				3L -> V3
				4L -> V4
				5L -> V5
				else -> null
			}
	}
}