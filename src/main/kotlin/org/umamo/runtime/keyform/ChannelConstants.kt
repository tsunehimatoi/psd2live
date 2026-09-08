package org.umamo.runtime.keyform

import org.umamo.runtime.model.ChannelValue
import org.umamo.runtime.model.ColorRgb
import org.umamo.runtime.model.FormChannel

/*
 * Readers over a map of lifted channel constants - the values that channel tracks collapsed to, waiting
 * to be written into their owner's static fields.  Shared by the post-import compaction pass
 * (ChannelCompaction) and the parameter-delete collapse (ParameterCrudEdits in :edit), so the two paths
 * that lift a track into a static cannot drift apart.
 */

/**
 * The lifted constant for [channel] as a float, or [fallback] when the channel did not collapse.
 *
 * @param FormChannel channel The channel to read.
 * @param Float fallback The owner's current static value.
 * @return Float The lifted scalar, or [fallback].
 */
fun Map<FormChannel, ChannelValue>.scalarOr(channel: FormChannel, fallback: Float): Float =
	(this[channel] as? ChannelValue.Scalar)?.value ?: fallback

/**
 * The lifted constant for [channel] as a color, or [fallback] when the channel did not collapse.
 *
 * @param FormChannel channel The channel to read.
 * @param ColorRgb fallback The owner's current static value.
 * @return ColorRgb The lifted color, or [fallback].
 */
fun Map<FormChannel, ChannelValue>.colorOr(channel: FormChannel, fallback: ColorRgb): ColorRgb =
	(this[channel] as? ChannelValue.Color)?.color ?: fallback

/**
 * The lifted constant for [channel] as a flag, or [fallback] when the channel did not collapse.
 *
 * @param FormChannel channel The channel to read.
 * @param Boolean fallback The owner's current static value.
 * @return Boolean The lifted flag, or [fallback].
 */
fun Map<FormChannel, ChannelValue>.flagOr(channel: FormChannel, fallback: Boolean): Boolean =
	(this[channel] as? ChannelValue.Flag)?.flag ?: fallback

/**
 * The lifted DRAW_ORDER constant as an exact Int, or null when absent or fractional.
 *
 * A part-like owner stores its draw order in an Int slot, so a fractional constant cannot lift - rounding
 * it could reorder two siblings the track kept distinct.  Callers pair this with [hasFractionalDrawOrder]
 * to keep such a value as a track instead.
 *
 * @return Int? The exactly-integral lifted draw order, or null.
 */
fun Map<FormChannel, ChannelValue>.integralDrawOrderOrNull(): Int? {
	val lifted = (this[FormChannel.DRAW_ORDER] as? ChannelValue.Scalar) ?: return null
	return lifted.value.toInt().takeIf { candidate -> candidate.toFloat() == lifted.value }
}

/**
 * Whether a DRAW_ORDER constant was lifted but is fractional and so must stay a track rather than round
 * into an Int static slot.
 *
 * @return Boolean True when DRAW_ORDER collapsed to a non-integral value.
 */
fun Map<FormChannel, ChannelValue>.hasFractionalDrawOrder(): Boolean =
	this[FormChannel.DRAW_ORDER] is ChannelValue.Scalar && integralDrawOrderOrNull() == null