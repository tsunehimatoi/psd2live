package org.umamo.runtime.model

/**
 * A straight-alpha-free RGB color channel triple, 0..1 per channel - the multiply/screen color
 * payload of a layer composite. Channels are the editor hex value / 255 as float32 (CMO3
 * `CFloatColor` red/green/blue; MOC3 color-table rows 108-113 carry the identical floats). The
 * source formats' alpha attribute is always 1.0 in every observed sample, so it is not modeled.
 *
 * 乗算色／スクリーン色の RGB 値（0..1）。
 */
data class ColorRgb(
	val red: Float,
	val green: Float,
	val blue: Float,
) {
	companion object {
		/** The multiply-color identity (#FFFFFF - multiplying by it changes nothing). */
		val MultiplyIdentity: ColorRgb = ColorRgb(1f, 1f, 1f)

		/** The screen-color identity (#000000 - screening with it changes nothing). */
		val ScreenIdentity: ColorRgb = ColorRgb(0f, 0f, 0f)
	}

	/**
	 * This color with every channel clamped into 0..1.
	 *
	 * Additive accumulation (blend-shape color deltas, the deformer screen composition) can leave the
	 * range at the ends of a keyed span, and the clamp belongs AFTER the whole sum rather than per
	 * term - clamping each contribution would bias a record whose neighbours pull the other way.
	 *
	 * @return ColorRgb The clamped color.
	 */
	fun coerceToUnit(): ColorRgb =
		ColorRgb(red.coerceIn(0f, 1f), green.coerceIn(0f, 1f), blue.coerceIn(0f, 1f))
}