package org.umamo.runtime.model

/*
 * The per-channel keyform tracks: an entity's animatable non-geometry properties, each keyed on its own
 * axes at its own key positions rather than riding one bundled keyform cell.
 *
 * Cubism's keyform cell is all-or-nothing - CArtMeshForm carries positions, draw order, opacity, and the
 * multiply/screen tints together, so keying any one of them keys all of them.  Splitting them lets a
 * rigger key opacity on one parameter without touching geometry, which is the whole point: the bundled
 * shape cannot express it at all.  ONE enum plus ONE map rather than a map per value type, because every
 * generic walk over an entity's channels - the parameter-delete scrub, compaction, the export refine,
 * the dope-sheet track list - needs to iterate them uniformly, and a flag channel fits no scalar/color
 * split.
 */

/** The kind of value a [FormChannel] stores, and therefore how it interpolates between keys. */
enum class ChannelValueKind {
	/** Blends linearly. */
	SCALAR,

	/** Blends linearly per component. */
	COLOR,

	/** Snaps to the floor cell - a boolean has no meaningful midpoint. */
	FLAG,
}

/**
 * One animatable non-geometry channel of a keyable entity.
 *
 * Every format citation for a keyformed scalar or color lives here, now that the bundled form classes
 * have retired to being import intermediates.  Adding a channel later is one constant, not a new field
 * on every owner.
 */
enum class FormChannel(val valueKind: ChannelValueKind) {
	// CMO3: CArtMeshForm field drawOrder / CPartForm field drawOrder; MOC3 §5.6 keyform draw order.
	DRAW_ORDER(ChannelValueKind.SCALAR),

	// CMO3: CArtMeshForm / CPartForm / ACDeformerForm field opacity; MOC3 §5.6 keyform opacity.
	OPACITY(ChannelValueKind.SCALAR),

	// CMO3: CArtMeshForm / CPartForm / ACDeformerForm field multiplyColor; MOC3 color-table rows 108-113.
	MULTIPLY_COLOR(ChannelValueKind.COLOR),

	// CMO3: CArtMeshForm / CPartForm / ACDeformerForm field screenColor; MOC3 color-table rows 108-113.
	SCREEN_COLOR(ChannelValueKind.COLOR),

	// CMO3: CRotationDeformerForm field isReflectX; MOC3 §5.6 s66 rotation reflect x.  The Umamo C++
	// Runtime snaps this per grid cell into the affine's flipX, so it is NOT interpolable.
	FLIP_X(ChannelValueKind.FLAG),

	// CMO3: CRotationDeformerForm field isReflectY; MOC3 §5.6 s67 rotation reflect y.
	FLIP_Y(ChannelValueKind.FLAG),

	// CMO3: CGlueForm field intensity; MOC3 §5.6 s100 glue intensities.
	GLUE_INTENSITY(ChannelValueKind.SCALAR),
}

/**
 * The value stored in one cell of a channel track.
 *
 * Sealed so interpolation is an exhaustive `when` rather than a strategy object: adding a fourth value
 * kind becomes a compile error at every site that has to handle it.
 */
sealed interface ChannelValue {
	/** The channel's [ChannelValueKind], so a generic walk can check a value against its channel. */
	val valueKind: ChannelValueKind

	/** A linearly blended scalar (opacity, draw order, glue intensity). */
	data class Scalar(val value: Float) : ChannelValue {
		override val valueKind: ChannelValueKind get() = ChannelValueKind.SCALAR
	}

	/** A per-component blended color (the multiply / screen tints). */
	data class Color(val color: ColorRgb) : ChannelValue {
		override val valueKind: ChannelValueKind get() = ChannelValueKind.COLOR
	}

	/** A boolean that snaps to the floor cell (the rotation deformer's reflection flags). */
	data class Flag(val flag: Boolean) : ChannelValue {
		override val valueKind: ChannelValueKind get() = ChannelValueKind.FLAG
	}
}

/**
 * One owner's non-geometry channel tracks, keyed by channel.
 *
 * A plain class with referential equality, matching [KeyformGrid], so the renderer's identity-based
 * model diff keeps behaving exactly as it does today - a data class here would make an unrelated
 * deep compare run on every frame's reconcile.  A channel with no entry falls back to the owner's
 * static field for that property, which after import compaction is the overwhelmingly common case;
 * [Empty] is shared so it allocates nothing.
 */
class ChannelGrids(val gridsByChannel: Map<FormChannel, KeyformGrid<ChannelValue>>) {
	/** True when this owner has no channel tracks at all (every property reads its static value). */
	val isEmpty: Boolean get() = gridsByChannel.isEmpty()

	/**
	 * The track for [channel], or null when this owner does not key it.
	 *
	 * @param FormChannel channel The channel to look up.
	 * @return KeyformGrid? The channel's track, or null when unkeyed.
	 */
	operator fun get(channel: FormChannel): KeyformGrid<ChannelValue>? = gridsByChannel[channel]

	/**
	 * Whether this owner's [channel] track holds any value other than [identity].
	 *
	 * A track's mere PRESENCE is not use.  A MOC3 import fans every channel out of one bundled grid, so a
	 * drawable that was never tinted still gets a color track of pure identity cells - and compaction
	 * deliberately leaves it alone when its axis does not bracket the parameter's range, since an
	 * out-of-span pose has to fall back to the static.  Counting that as "the document uses color"
	 * makes an export warn about losing a tint that does not exist.
	 *
	 * @param FormChannel channel  The channel to inspect.
	 * @param ChannelValue identity The channel's neutral value.
	 * @return Boolean True when some cell differs from the identity.
	 */
	fun varies(channel: FormChannel, identity: ChannelValue): Boolean =
		this[channel]?.cells?.any { cell -> cell.form != identity } ?: false

	companion object {
		/** The shared no-tracks instance - every property falls back to its owner's static value. */
		val Empty: ChannelGrids = ChannelGrids(emptyMap())
	}
}