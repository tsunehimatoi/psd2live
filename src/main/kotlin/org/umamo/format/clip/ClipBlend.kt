package org.umamo.format.clip

import org.umamo.format.art.LayerBlend

/**
 * Maps a CLIP `Layer.LayerComposite` blend-mode code to the neutral [LayerBlend].
 *
 * EN: The complete enum was reverse-engineered from LayerBlendModes.clip (one layer per mode, named
 *     after the Clip Studio Paint UI label).  Codes are contiguous 0..26, then 36 for Divide; the
 *     27..35 gap is unused for leaf layers.  Code 30 was observed only on folders in one sample
 *     and is the folder pass-through composite, not a leaf blend mode - it is not represented here
 *     (folders are not emitted as layers).  Truly unknown codes degrade to Normal.
 * JA: LayerComposite コードを中立 LayerBlend へ写像する.  LayerBlendModes.clip から全列挙を判明.
 */
object ClipBlend {
	/**
	 * Returns the neutral blend mode for a LayerComposite code, defaulting unknown codes to Normal.
	 *
	 * @param Long compositeCode The raw Layer.LayerComposite value.
	 * @return LayerBlend The mapped blend mode (Normal for any unrecognized code).
	 */
	fun fromCompositeCode(compositeCode: Long): LayerBlend =
		// CLIP: Layer.LayerComposite - verified code table (see LayerBlendModes.clip / docs/CLIP.md §3).
		when (compositeCode) {
			0L -> LayerBlend.Normal
			1L -> LayerBlend.Darken
			2L -> LayerBlend.Multiply
			3L -> LayerBlend.ColorBurn
			4L -> LayerBlend.LinearBurn
			5L -> LayerBlend.Subtract
			6L -> LayerBlend.DarkerColor
			7L -> LayerBlend.Lighten
			8L -> LayerBlend.Screen
			9L -> LayerBlend.ColorDodge
			10L -> LayerBlend.GlowDodge
			11L -> LayerBlend.Add
			12L -> LayerBlend.AddGlow
			13L -> LayerBlend.LighterColor
			14L -> LayerBlend.Overlay
			15L -> LayerBlend.SoftLight
			16L -> LayerBlend.HardLight
			17L -> LayerBlend.VividLight
			18L -> LayerBlend.LinearLight
			19L -> LayerBlend.PinLight
			20L -> LayerBlend.HardMix
			21L -> LayerBlend.Difference
			22L -> LayerBlend.Exclusion
			23L -> LayerBlend.Hue
			24L -> LayerBlend.Saturation
			25L -> LayerBlend.Color
			26L -> LayerBlend.Luminosity // CLIP: CSP UI label "Brightness".
			36L -> LayerBlend.Divide
			else -> LayerBlend.Normal // includes folder code 30 (pass-through) and the unused 27..35 gap.
		}
}