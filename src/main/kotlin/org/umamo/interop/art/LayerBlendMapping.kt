package org.umamo.interop.art

import org.umamo.format.art.LayerBlend
import org.umamo.runtime.model.BlendMode

/**
 * How one source-art blend lands on the model's blend modes.
 *
 * Three outcomes, kept distinct because the rigger's remedy differs: an exact mapping needs no word,
 * an approximation is worth a notice so an artist who leaned on the exact formula knows, and an
 * unsupported blend falls back to Normal with a notice so the change is never silent.
 */
sealed interface LayerBlendMapping {
	/** The blend mode the model gets, Normal for an unsupported one. */
	val blendMode: BlendMode

	/** The model has this blend under the same formula. */
	data class Exact(override val blendMode: BlendMode) : LayerBlendMapping

	/** The model's nearest blend, not the same formula. */
	data class Approximate(override val blendMode: BlendMode) : LayerBlendMapping

	/** No model blend comes close; the drawable renders Normal. */
	data object Unsupported : LayerBlendMapping {
		override val blendMode: BlendMode get() = BlendMode.Normal
	}
}

/**
 * Maps a source-art blend onto the model's blend modes.
 *
 * The model's set is Cubism 5.3's (see [BlendMode]), which covers most of the PSD / Clip Studio /
 * Krita vocabulary one to one.  Glow Dodge is Clip Studio's clamped Color Dodge and lands there as an
 * approximation; the HSL modes the model lacks (Saturation, Luminosity), the two comparison modes
 * (Darker / Lighter Color), and the arithmetic ones (Subtract, Divide, Vivid / Pin Light, Hard Mix,
 * Difference, Exclusion) have no model equivalent at all.
 *
 * @param LayerBlend blend The source layer's blend.
 * @return LayerBlendMapping Where it lands.
 */
fun mapLayerBlend(blend: LayerBlend): LayerBlendMapping =
	when (blend) {
		LayerBlend.Normal -> LayerBlendMapping.Exact(BlendMode.Normal)
		LayerBlend.Darken -> LayerBlendMapping.Exact(BlendMode.Darken)
		LayerBlend.Multiply -> LayerBlendMapping.Exact(BlendMode.Multiply)
		LayerBlend.ColorBurn -> LayerBlendMapping.Exact(BlendMode.ColorBurn)
		LayerBlend.LinearBurn -> LayerBlendMapping.Exact(BlendMode.LinearBurn)
		LayerBlend.Lighten -> LayerBlendMapping.Exact(BlendMode.Lighten)
		LayerBlend.Screen -> LayerBlendMapping.Exact(BlendMode.Screen)
		LayerBlend.ColorDodge -> LayerBlendMapping.Exact(BlendMode.ColorDodge)
		LayerBlend.GlowDodge -> LayerBlendMapping.Approximate(BlendMode.ColorDodge)
		LayerBlend.Add -> LayerBlendMapping.Exact(BlendMode.Additive)
		LayerBlend.AddGlow -> LayerBlendMapping.Exact(BlendMode.AdditiveGlow)
		LayerBlend.Overlay -> LayerBlendMapping.Exact(BlendMode.Overlay)
		LayerBlend.SoftLight -> LayerBlendMapping.Exact(BlendMode.SoftLight)
		LayerBlend.HardLight -> LayerBlendMapping.Exact(BlendMode.HardLight)
		LayerBlend.LinearLight -> LayerBlendMapping.Exact(BlendMode.LinearLight)
		LayerBlend.Hue -> LayerBlendMapping.Exact(BlendMode.Hue)
		LayerBlend.Color -> LayerBlendMapping.Exact(BlendMode.Color)
		LayerBlend.Subtract,
		LayerBlend.DarkerColor,
		LayerBlend.LighterColor,
		LayerBlend.VividLight,
		LayerBlend.PinLight,
		LayerBlend.HardMix,
		LayerBlend.Difference,
		LayerBlend.Exclusion,
		LayerBlend.Saturation,
		LayerBlend.Luminosity,
		LayerBlend.Divide,
		-> LayerBlendMapping.Unsupported
	}