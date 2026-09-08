package org.umamo.format.moc3.moc

/**
 * Per-drawable constant flags (`SEC_DRAW_CFLAG`, section index 42), a `u8` bitmask. These match the
 * public runtime API (`csmConstantDrawableFlags`); the low bits are blend mode, then masking.
 *
 * @see <a href="https://docs.umamo.org/format/MOC3.md">MOC3.md</a>
 */
public object ConstantFlag {
	/** Additive blending. */
	public const val BLEND_ADDITIVE: Int = 1 shl 0

	/** Multiplicative blending. */
	public const val BLEND_MULTIPLICATIVE: Int = 1 shl 1

	/** The mesh is double-sided. */
	public const val IS_DOUBLE_SIDED: Int = 1 shl 2

	/** The clipping mask is inverted. */
	public const val IS_INVERTED_MASK: Int = 1 shl 3
}

/**
 * Per-drawable dynamic flags (`csmDynamicDrawableFlags`), a `u8` bitmask of per-frame state. Not
 * stored in the file (computed by the runtime), recorded here for completeness of the public model.
 */
public object DynamicFlag {
	public const val IS_VISIBLE: Int = 1 shl 0
	public const val VISIBILITY_DID_CHANGE: Int = 1 shl 1
	public const val OPACITY_DID_CHANGE: Int = 1 shl 2
	public const val DRAW_ORDER_DID_CHANGE: Int = 1 shl 3
	public const val RENDER_ORDER_DID_CHANGE: Int = 1 shl 4
	public const val VERTEX_POSITIONS_DID_CHANGE: Int = 1 shl 5
	public const val BLEND_COLOR_DID_CHANGE: Int = 1 shl 6
}

/**
 * Parameter kind (`SEC_PARAM_TYPE`, section index 114; present from MOC3 v4 / `Parameter.Types`).
 *
 * @see <a href="https://docs.umamo.org/format/MOC3.md">MOC3.md</a>
 */
public enum class ParameterType(public val number: Int) {
	/** A normal scalar parameter. */
	NORMAL(0),

	/** A blend-shape parameter (drives additive keyform deltas). */
	BLEND_SHAPE(1),
	;

	public companion object {
		/**
		 * Resolves a stored type number to a [ParameterType].
		 *
		 * @param Int number The i32 type code.
		 * @return ParameterType The matching kind.
		 */
		public fun fromNumber(number: Int): ParameterType =
			entries.firstOrNull { it.number == number }
				?: throw IllegalArgumentException("unknown parameter type: $number")
	}
}