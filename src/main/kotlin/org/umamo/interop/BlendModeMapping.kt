package org.umamo.interop

import org.umamo.format.cmo3.model.gen.AlphaComposition
import org.umamo.format.cmo3.model.gen.ColorComposition
import org.umamo.format.moc3.moc.ConstantFlag
import org.umamo.runtime.model.AlphaBlendMode
import org.umamo.runtime.model.BlendMode

/*
 * The blend-mode bijections shared by both importers, per the extraction tables in
 * docs/plan/offscreen-support.md: CMO3 serializes enum tokens (ColorComposition /
 * AlphaComposition), MOC3 packs ints (sections 153/157, colorMode or (alphaMode shl 8)).
 * The bare legacy tokens (NORMAL/ADD/MULTIPLY) are the pre-5.3 modes in every era; each
 * 5.3 mode has its own token and int.
 */

/**
 * Maps a CMO3 ColorComposition token to the runtime color blend mode.
 *
 * @param String? name The serialized enum constant name (e.g. "MULTIPLY_R2"), or null when absent.
 * @return BlendMode The runtime mode; Normal for null or an unknown token.
 */
internal fun colorBlendOfToken(name: String?): BlendMode =
	// CMO3: ColorComposition constants, matched by name (the generated enum is observed-only).
	when (name) {
		"NORMAL" -> BlendMode.Normal
		"ADD" -> BlendMode.AdditivePremultiplied
		"MULTIPLY" -> BlendMode.MultiplyPremultiplied
		"ADD_R2_TSL" -> BlendMode.Additive
		"ADD_R2" -> BlendMode.AdditiveGlow
		"DARKEN" -> BlendMode.Darken
		"MULTIPLY_R2" -> BlendMode.Multiply
		"COLORBURN_TSL" -> BlendMode.ColorBurn
		"LINEARBURN_TSL" -> BlendMode.LinearBurn
		"LIGHTEN" -> BlendMode.Lighten
		"SCREEN" -> BlendMode.Screen
		"COLORDODGE_TSL" -> BlendMode.ColorDodge
		"OVERLAY" -> BlendMode.Overlay
		"SOFTLIGHT" -> BlendMode.SoftLight
		"HARDLIGHT" -> BlendMode.HardLight
		"LINEARLIGHT_TSL" -> BlendMode.LinearLight
		"HSL_HUE" -> BlendMode.Hue
		"HSL_COLOR" -> BlendMode.Color
		else -> BlendMode.Normal
	}

/**
 * Maps a runtime color blend mode to its CMO3 ColorComposition constant - the export inverse of
 * [colorBlendOfToken].
 *
 * @param BlendMode mode The runtime mode.
 * @return ColorComposition The serialized enum constant.
 */
internal fun colorCompositionOf(mode: BlendMode): ColorComposition =
	// CMO3: ColorComposition constants (see colorBlendOfToken's forward table).
	when (mode) {
		BlendMode.Normal -> ColorComposition.NORMAL
		BlendMode.AdditivePremultiplied -> ColorComposition.ADD
		BlendMode.MultiplyPremultiplied -> ColorComposition.MULTIPLY
		BlendMode.Additive -> ColorComposition.ADD_R2_TSL
		BlendMode.AdditiveGlow -> ColorComposition.ADD_R2
		BlendMode.Darken -> ColorComposition.DARKEN
		BlendMode.Multiply -> ColorComposition.MULTIPLY_R2
		BlendMode.ColorBurn -> ColorComposition.COLORBURN_TSL
		BlendMode.LinearBurn -> ColorComposition.LINEARBURN_TSL
		BlendMode.Lighten -> ColorComposition.LIGHTEN
		BlendMode.Screen -> ColorComposition.SCREEN
		BlendMode.ColorDodge -> ColorComposition.COLORDODGE_TSL
		BlendMode.Overlay -> ColorComposition.OVERLAY
		BlendMode.SoftLight -> ColorComposition.SOFTLIGHT
		BlendMode.HardLight -> ColorComposition.HARDLIGHT
		BlendMode.LinearLight -> ColorComposition.LINEARLIGHT_TSL
		BlendMode.Hue -> ColorComposition.HSL_HUE
		BlendMode.Color -> ColorComposition.HSL_COLOR
	}

/**
 * Maps a CMO3 AlphaComposition token to the runtime alpha blend mode.
 *
 * @param String? name The serialized enum constant name (e.g. "DISJOINT"), or null when absent
 *                     (pre-5.3 sources carry no alpha mode).
 * @return AlphaBlendMode The runtime mode; Over for null or an unknown token.
 */
internal fun alphaBlendOfToken(name: String?): AlphaBlendMode =
	// CMO3: AlphaComposition constants, matched by name.
	when (name) {
		"OVER" -> AlphaBlendMode.Over
		"ATOP" -> AlphaBlendMode.Atop
		"OUT" -> AlphaBlendMode.Out
		"CONJOINT" -> AlphaBlendMode.Conjoint
		"DISJOINT" -> AlphaBlendMode.Disjoint
		else -> AlphaBlendMode.Over
	}

/**
 * Maps a runtime alpha blend mode to its CMO3 AlphaComposition constant - the export inverse of
 * [alphaBlendOfToken].
 *
 * @param AlphaBlendMode mode The runtime mode.
 * @return AlphaComposition The serialized enum constant.
 */
internal fun alphaCompositionOf(mode: AlphaBlendMode): AlphaComposition =
	// CMO3: AlphaComposition constants (see alphaBlendOfToken's forward table).
	when (mode) {
		AlphaBlendMode.Over -> AlphaComposition.OVER
		AlphaBlendMode.Atop -> AlphaComposition.ATOP
		AlphaBlendMode.Out -> AlphaComposition.OUT
		AlphaBlendMode.Conjoint -> AlphaComposition.CONJOINT
		AlphaBlendMode.Disjoint -> AlphaComposition.DISJOINT
	}

/**
 * Unpacks the color half of a MOC3 packed blend int (sections 153/157).
 *
 * @param Int packed The stored int, colorMode or (alphaMode shl 8).
 * @return BlendMode The runtime mode; Normal for an unknown colorMode.
 */
internal fun colorBlendOfPacked(packed: Int): BlendMode =
	// MOC3 v6 §5.6 s153/s157: colorMode 0-17 in editor-dropdown order (legacy first).
	when (packed and 0xFF) {
		0 -> BlendMode.Normal
		1 -> BlendMode.AdditivePremultiplied
		2 -> BlendMode.MultiplyPremultiplied
		3 -> BlendMode.Additive
		4 -> BlendMode.AdditiveGlow
		5 -> BlendMode.Darken
		6 -> BlendMode.Multiply
		7 -> BlendMode.ColorBurn
		8 -> BlendMode.LinearBurn
		9 -> BlendMode.Lighten
		10 -> BlendMode.Screen
		11 -> BlendMode.ColorDodge
		12 -> BlendMode.Overlay
		13 -> BlendMode.SoftLight
		14 -> BlendMode.HardLight
		15 -> BlendMode.LinearLight
		16 -> BlendMode.Hue
		17 -> BlendMode.Color
		else -> BlendMode.Normal
	}

/**
 * Unpacks the alpha half of a MOC3 packed blend int (sections 153/157).
 *
 * @param Int packed The stored int, colorMode or (alphaMode shl 8).
 * @return AlphaBlendMode The runtime mode; Over for an unknown alphaMode.
 */
internal fun alphaBlendOfPacked(packed: Int): AlphaBlendMode =
	// MOC3 v6 §5.6 s153/s157: alphaMode 0-4 in editor-dropdown order.
	when ((packed shr 8) and 0xFF) {
		0 -> AlphaBlendMode.Over
		1 -> AlphaBlendMode.Atop
		2 -> AlphaBlendMode.Out
		3 -> AlphaBlendMode.Conjoint
		4 -> AlphaBlendMode.Disjoint
		else -> AlphaBlendMode.Over
	}

/**
 * The LEGACY 2-bit constant-flag encoding of [mode] - the only blend a moc below v6 can express.
 *
 * The pre-5.3 fixed-function set is just Normal / Add / Multiply, so every 5.3+ mode has to fall back
 * to its nearest premultiplied ancestor: the 5.3 additive family reads as Add and the multiply family
 * as Multiply, and anything with no fixed-function equivalent reads as Normal.  A v6 export writes the
 * packed extended blend instead and leaves these bits at Normal, so this is only reached when the
 * target version genuinely cannot carry the authored mode.
 *
 * @param BlendMode mode The runtime blend mode.
 * @return Int The constant-flag bits (0 for Normal).
 */
internal fun legacyBlendFlagOf(mode: BlendMode): Int =
	when (nearestLegacyBlendMode(mode)) {
		BlendMode.AdditivePremultiplied -> ConstantFlag.BLEND_ADDITIVE
		BlendMode.MultiplyPremultiplied -> ConstantFlag.BLEND_MULTIPLICATIVE
		else -> 0
	}

/**
 * The pre-5.3 mode [mode] degrades to when the target version cannot carry it.
 *
 * The same nearest-ancestor rule [legacyBlendFlagOf] encodes, as a runtime mode rather than as flag
 * bits, so a version downgrade rewrites the MODEL to what the file will actually say instead of
 * leaving the two to disagree about what "additive" means.
 *
 * @param BlendMode mode The authored blend mode.
 * @return BlendMode The nearest mode a pre-5.3 runtime understands.
 */
internal fun nearestLegacyBlendMode(mode: BlendMode): BlendMode =
	when (mode) {
		BlendMode.AdditivePremultiplied, BlendMode.Additive, BlendMode.AdditiveGlow ->
			BlendMode.AdditivePremultiplied
		BlendMode.MultiplyPremultiplied, BlendMode.Multiply -> BlendMode.MultiplyPremultiplied
		else -> BlendMode.Normal
	}

/**
 * The constant-flag bits of [mode] when the pair names the mode EXACTLY, else 0.
 *
 * Unlike [legacyBlendFlagOf] this never approximates: only the two premultiplied modes have a legacy
 * bit that means the same thing, so Additive (non-premultiplied), AdditiveGlow, and Multiply all read
 * as Normal here.  That is what a moc 6 offscreen's flag byte contains - every corpus offscreen sets
 * the bit for colorMode 1 or 2 and clears it for every other mode - because on v6 the packed section
 * is authoritative and these bits merely restate the two modes an old reader could have understood.
 *
 * Approximating here would state a mode the file does not, which is the failure the two names exist to
 * keep apart: [legacyBlendFlagOf] is for a DOWNGRADE (the packed section is gone and the nearest
 * ancestor is the best available), this is for an ECHO (the packed section is right there).
 *
 * @param BlendMode mode The runtime blend mode.
 * @return Int The constant-flag bits (0 unless the mode is exactly additive- or multiply-premultiplied).
 */
internal fun exactLegacyBlendFlagOf(mode: BlendMode): Int =
	when (mode) {
		BlendMode.AdditivePremultiplied -> ConstantFlag.BLEND_ADDITIVE
		BlendMode.MultiplyPremultiplied -> ConstantFlag.BLEND_MULTIPLICATIVE
		else -> 0
	}

/**
 * Packs [color] and [alpha] into the MOC3 v6 blend int (sections 153 / 157).
 *
 * The exact inverse of [colorBlendOfPacked] and [alphaBlendOfPacked], written as the same ordinal
 * tables read backwards so a mode added to one direction is a compile error in the other rather than a
 * silently unpackable value.
 *
 * @param BlendMode      color The runtime color blend mode.
 * @param AlphaBlendMode alpha The runtime alpha blend mode.
 * @return Int The stored int, `colorMode or (alphaMode shl 8)`.
 */
internal fun packedBlendOf(color: BlendMode, alpha: AlphaBlendMode): Int {
	val colorMode =
		when (color) {
			BlendMode.Normal -> 0
			BlendMode.AdditivePremultiplied -> 1
			BlendMode.MultiplyPremultiplied -> 2
			BlendMode.Additive -> 3
			BlendMode.AdditiveGlow -> 4
			BlendMode.Darken -> 5
			BlendMode.Multiply -> 6
			BlendMode.ColorBurn -> 7
			BlendMode.LinearBurn -> 8
			BlendMode.Lighten -> 9
			BlendMode.Screen -> 10
			BlendMode.ColorDodge -> 11
			BlendMode.Overlay -> 12
			BlendMode.SoftLight -> 13
			BlendMode.HardLight -> 14
			BlendMode.LinearLight -> 15
			BlendMode.Hue -> 16
			BlendMode.Color -> 17
		}
	val alphaMode =
		when (alpha) {
			AlphaBlendMode.Over -> 0
			AlphaBlendMode.Atop -> 1
			AlphaBlendMode.Out -> 2
			AlphaBlendMode.Conjoint -> 3
			AlphaBlendMode.Disjoint -> 4
		}
	return colorMode or (alphaMode shl 8)
}