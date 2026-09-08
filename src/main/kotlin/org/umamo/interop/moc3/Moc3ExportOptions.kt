package org.umamo.interop.moc3

/**
 * What a MOC3 export includes, chosen at export time rather than stored on the document.
 *
 * The defaults here are the API's compatibility contract, not the export dialog's: an options-less
 * call behaves exactly as the lowering always has - hidden objects carried with their flag clear,
 * guide subtrees dropped, every sidecar emitted, and the canvas scale resolved by
 * [org.umamo.interop.moc3.export.Moc3Export.mocPixelsPerUnitFor].  The dialog seeds its own
 * first-launch defaults in the UI layer, and those match the official editor's bake instead
 * (hidden objects dropped), so the two default sets are deliberately not the same thing.
 *
 * One class carries both the lowering's flags and the bundle's sidecar toggles, so an export
 * handler threads a single value end to end.  A future texture-resolution scale is one more
 * defaulted property here.
 *
 * @property Boolean exportHiddenParts     Write hidden part subtrees, flag clear, instead of
 *                                         dropping them the way the official bake does.
 * @property Boolean exportHiddenDrawables Write drawables whose own flag is hidden, flag clear,
 *                                         instead of dropping them.
 * @property Boolean exportGuideImageParts Write guide-image (sketch) subtrees instead of dropping
 *                                         them.
 * @property Boolean includePhysics        Carry a retained physics3.json through the bundle.
 * @property Boolean includeUserData       Carry a retained userdata3.json through the bundle.
 * @property Boolean includeDisplayInfo    Synthesize the cdi3.json display-info sidecar.
 * @property Float?  pixelsPerUnitOverride The bake scale to write, or null to resolve it from the
 *                                         model (the recorded scale, else the canvas width).
 */
data class Moc3ExportOptions(
	val exportHiddenParts: Boolean = true,
	val exportHiddenDrawables: Boolean = true,
	val exportGuideImageParts: Boolean = false,
	val includePhysics: Boolean = true,
	val includeUserData: Boolean = true,
	val includeDisplayInfo: Boolean = true,
	val pixelsPerUnitOverride: Float? = null,
) {
	companion object {
		/** The options-less behavior: exactly what the lowering did before options existed. */
		val Default: Moc3ExportOptions = Moc3ExportOptions()
	}
}