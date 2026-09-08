package org.umamo.interop.moc3.export

import org.umamo.runtime.keyform.FormInterpolator
import org.umamo.runtime.model.ChannelGrids
import org.umamo.runtime.model.ChannelValue
import org.umamo.runtime.model.ColorRgb
import org.umamo.runtime.model.FormChannel

/*
 * The channel-set helpers every object lowering shares: which render channels an object's grid should
 * bundle at the target version, the static fallbacks behind them, and the narrowing that keeps a
 * bundle to exactly those channels.
 */

/**
 * The render channels an object's grid should bundle, gated on whether the version carries colors.
 *
 * @param Boolean colorsEnabled Whether the target version has color tables.
 * @return Array<FormChannel> The channels to bundle.
 */
internal fun renderChannels(colorsEnabled: Boolean): Array<FormChannel> =
	if (colorsEnabled) {
		arrayOf(FormChannel.OPACITY, FormChannel.MULTIPLY_COLOR, FormChannel.SCREEN_COLOR)
	} else {
		arrayOf(FormChannel.OPACITY)
	}

/**
 * The static fallbacks for [renderChannels].
 *
 * Colors are omitted entirely below moc 4 rather than defaulted, so the bundle never manufactures a
 * color cell the version has nowhere to store.
 *
 * @param Float    opacity       The owner's static opacity.
 * @param ColorRgb multiplyColor The owner's static multiply color.
 * @param ColorRgb screenColor   The owner's static screen color.
 * @param Boolean  colorsEnabled Whether the target version has color tables.
 * @return Map The statics per channel.
 */
internal fun renderStatics(
	opacity: Float,
	multiplyColor: ColorRgb,
	screenColor: ColorRgb,
	colorsEnabled: Boolean,
): Map<FormChannel, ChannelValue> =
	if (colorsEnabled) {
		mapOf(
			FormChannel.OPACITY to ChannelValue.Scalar(opacity),
			FormChannel.MULTIPLY_COLOR to ChannelValue.Color(multiplyColor),
			FormChannel.SCREEN_COLOR to ChannelValue.Color(screenColor),
		)
	} else {
		mapOf(FormChannel.OPACITY to ChannelValue.Scalar(opacity))
	}

/** A geometry interpolator for owners that have no geometry at all (parts, glues). */
internal object UnitInterpolator : FormInterpolator<Unit> {
	override fun interpolate(lower: Unit, upper: Unit, fraction: Float) = Unit

	override fun isExactlyEqual(left: Unit, right: Unit): Boolean = true
}

/**
 * This channel set restricted to [channels] - the tracks the owner's moc block can actually store.
 *
 * A runtime entity may carry tracks a given moc version has no field for (a color track on a v3
 * export), and bundling those in would widen the grid with axes nothing reads.
 *
 * @param FormChannel channels The channels to keep.
 * @return ChannelGrids The restricted set.
 */
internal fun ChannelGrids.onlyChannels(
	vararg channels: FormChannel,
): ChannelGrids {
	val keep = channels.toSet()
	return ChannelGrids(
		gridsByChannel.filterKeys { channel -> channel in keep },
	)
}