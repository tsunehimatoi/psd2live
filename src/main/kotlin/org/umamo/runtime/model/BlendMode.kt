package org.umamo.runtime.model

/**
 * How a drawable or a layer composite blends its color against what's already drawn. Mirrors
 * Cubism's color blend modes: the three legacy pre-5.3 modes (bare CMO3 tokens, fixed-function
 * blending) plus the fifteen 5.3 modes (shader compositing). An `enum` (not sealed) because the set
 * is fixed and each case is a plain value with no extra data.
 *
 * Each constant cites its CMO3 ColorComposition token and its MOC3 packed colorMode int
 * (sections 153/157, `colorMode or (alphaMode shl 8)`); the mapping lives in explicit functions
 * (BlendModeMapping), never in ordinals.
 *
 * 描画オブジェクト／オフスクリーン合成のカラーブレンドモード。Cubism のブレンドモードに対応。
 */
enum class BlendMode {
	/** Legacy Normal. CMO3 `NORMAL`; MOC3 colorMode 0 (constant-flags bits clear). */
	Normal,

	/** Add (Before 5.3), premultiplied fixed-function. CMO3 `ADD`; MOC3 colorMode 1 (constant-flags bit 0). */
	AdditivePremultiplied,

	/** Multiply (Before 5.3), premultiplied fixed-function. CMO3 `MULTIPLY`; MOC3 colorMode 2 (constant-flags bit 1). */
	MultiplyPremultiplied,

	/** Add (5.3+). CMO3 `ADD_R2_TSL`; MOC3 colorMode 3. */
	Additive,

	/** Add (Glow) (5.3). CMO3 `ADD_R2`; MOC3 colorMode 4. */
	AdditiveGlow,

	/** Darken (5.3). CMO3 `DARKEN`; MOC3 colorMode 5. */
	Darken,

	/** Multiply (5.3+). CMO3 `MULTIPLY_R2`; MOC3 colorMode 6. */
	Multiply,

	/** Color burn (5.3). CMO3 `COLORBURN_TSL`; MOC3 colorMode 7. */
	ColorBurn,

	/** Linear burn (5.3). CMO3 `LINEARBURN_TSL`; MOC3 colorMode 8. */
	LinearBurn,

	/** Lighten (5.3). CMO3 `LIGHTEN`; MOC3 colorMode 9. */
	Lighten,

	/** Screen (5.3). CMO3 `SCREEN`; MOC3 colorMode 10. */
	Screen,

	/** Color dodge (5.3). CMO3 `COLORDODGE_TSL`; MOC3 colorMode 11. */
	ColorDodge,

	/** Overlay (5.3). CMO3 `OVERLAY`; MOC3 colorMode 12. */
	Overlay,

	/** Soft light (5.3). CMO3 `SOFTLIGHT`; MOC3 colorMode 13. */
	SoftLight,

	/** Hard light (5.3). CMO3 `HARDLIGHT`; MOC3 colorMode 14. */
	HardLight,

	/** Linear light (5.3). CMO3 `LINEARLIGHT_TSL`; MOC3 colorMode 15. */
	LinearLight,

	/** Hue (5.3, HSL non-separable). CMO3 `HSL_HUE`; MOC3 colorMode 16. */
	Hue,

	/** Color (5.3, HSL non-separable). CMO3 `HSL_COLOR`; MOC3 colorMode 17. */
	Color,

	;

	/**
	 * True for the three pre-5.3 modes, whose semantics are plain fixed-function blending; every
	 * other mode requires the destination-sampling composite path.
	 */
	val isLegacy: Boolean get() = this == Normal || this == AdditivePremultiplied || this == MultiplyPremultiplied

	/**
	 * Whether this color blend mode ignores the Alpha blend setting.  Per the Cubism editor, the pre-5.3
	 * "Add (Before 5.3)" and "Multiply (Before 5.3)" modes composite in premultiplied format and disregard
	 * the Alpha blend selection.  Drives the composite math and the properties panel alpha-field visibility.
	 */
	val ignoresAlphaBlend: Boolean get() = this == AdditivePremultiplied || this == MultiplyPremultiplied
}

/**
 * How a drawable or a layer composite combines its ALPHA with the destination - Cubism 5.3's
 * Porter-Duff-style alpha blend list, orthogonal to [BlendMode]. Pre-5.3 sources have no alpha
 * mode; they behave as [Over].
 *
 * Each constant cites its CMO3 AlphaComposition token and MOC3 packed alphaMode int
 * (sections 153/157, bits 8-15).
 *
 * アルファブレンドモード（Cubism 5.3）。カラーブレンドとは独立。
 */
enum class AlphaBlendMode {
	/** Over (the default; pre-5.3 behavior). CMO3 `OVER`; MOC3 alphaMode 0. */
	Over,

	/** Atop. CMO3 `ATOP`; MOC3 alphaMode 1. */
	Atop,

	/** Out. CMO3 `OUT`; MOC3 alphaMode 2. */
	Out,

	/** Conjoint over. CMO3 `CONJOINT`; MOC3 alphaMode 3. */
	Conjoint,

	/** Disjoint over. CMO3 `DISJOINT`; MOC3 alphaMode 4. */
	Disjoint,
}