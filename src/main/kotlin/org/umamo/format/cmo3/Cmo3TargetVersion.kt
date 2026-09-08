package org.umamo.format.cmo3

/**
 * The Cubism SDK version ladder that `CModelSource.targetVersionNo` denotes - the "Model target
 * version selection" the official editor stores per document.
 *
 * The on-disk value is an era-specific LITERAL per version, not one formula: the editor keeps
 * the encoding scheme that was current when each SDK shipped (3.x = major*1000 + minor*10,
 * 4.0 = major*100000, 4.2+ = major*1_000_000 + minor*10_000).  A single formula is wrong by
 * observation - it would predict 3030000 for 3.3 where the editor writes 3030.
 * Corpus evidence:
 *
 *   Cubism 3.0 -> 3000     (modelD - older editors)
 *   Cubism 3.3 -> 3030     (ModelWith[out]OffscreenSDK3.3)
 *   Cubism 4.0 -> 400000   (EricaTamamo, ModelWithoutOffscreenSDK4.0)
 *   Cubism 4.2 -> 4020000  (ModelWithoutOffscreenSDK4.2)
 *   Cubism 5.0 -> 5000000  (modelB, modelC)
 *   Cubism 5.3 -> 5030000  (modelA, ModelWithOffscreen family)
 *   SDK(N/A)   -> 9000000  (ModelWithoutOffscreenSDKNA) - see [LATEST_VERSION_NO]
 *
 * This type is pure CMO3 format knowledge: it knows nothing about editor gating policy or any
 * runtime target - that mapping lives above `:format`.
 */
enum class Cmo3TargetVersion(val major: Int, val minor: Int, val versionNo: Int) {
	// CMO3: CModelSource field targetVersionNo - one entry per observed literal (table above).
	V30(3, 0, 3_000),
	V33(3, 3, 3_030),
	V40(4, 0, 400_000),
	V42(4, 2, 4_020_000),
	V50(5, 0, 5_000_000),
	V53(5, 3, 5_030_000),
	;

	/**
	 * The value this version would take under the modern 4.2+ scheme, accepted as a tolerant-read
	 * alias because a future editor revision normalizing old projects would most plausibly emit it.
	 */
	private val modernVersionNo: Int
		get() = major * 1_000_000 + minor * 10_000

	companion object {
		/**
		 * The value the editor writes for the "SDK(N/A)/Latest Cubism" selection - no version
		 * ceiling at all.  CMO3: CModelSource field targetVersionNo.  Deliberately not an enum
		 * entry: it names the ABSENCE of a target version, so [fromVersionNo] returns null for it
		 * and callers persist it through this constant.
		 */
		const val LATEST_VERSION_NO: Int = 9_000_000

		/**
		 * Decodes a raw `targetVersionNo` into a known Cubism SDK target version.
		 *
		 * Matches the observed per-era literals first, then the modern-scheme equivalents as a
		 * tolerant-read fallback.  Everything else is null: the field being absent, unknown values,
		 * and [LATEST_VERSION_NO] (a target-less document has no target version).
		 *
		 * @param Int? versionNo The raw field value, or null when the document carries none.
		 * @return Cmo3TargetVersion? The matching version, or null for no/unknown version.
		 */
		fun fromVersionNo(versionNo: Int?): Cmo3TargetVersion? {
			// CMO3: CModelSource field targetVersionNo.
			if (versionNo == null) {
				return null
			}
			return entries.firstOrNull { it.versionNo == versionNo }
				?: entries.firstOrNull { it.modernVersionNo == versionNo }
		}
	}
}