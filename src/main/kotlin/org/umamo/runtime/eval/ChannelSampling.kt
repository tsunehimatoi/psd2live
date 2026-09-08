package org.umamo.runtime.eval

import org.umamo.runtime.model.ChannelGrids
import org.umamo.runtime.model.ChannelValue
import org.umamo.runtime.model.ColorRgb
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.ParameterId

/*
 * Pose sampling for the per-channel keyform tracks, over the same multilinear corner selection the
 * geometry blend uses.
 *
 * Each channel resolves its OWN corners from its OWN grid rather than borrowing geometry's.  That is not
 * an optimization left on the table - after import compaction a channel track keeps only the axes it
 * actually varies along, so its cell linear indices no longer line up with geometry's and a borrowed
 * corner set would read the wrong cells.  Before compaction the two share axes, so the corners come out
 * identical in identical order and the sums are bit-for-bit what the bundled blend produced.
 *
 * The static fallback applies when the owner has NO track for a channel, or when the track's pose is out
 * of range.  Out of range NEVER hides: hiding is the geometry grid's decision alone, so keying opacity on
 * a narrow parameter cannot make the art vanish at the ends of an unrelated slider.
 *
 * Every read takes an optional OVERRIDE, which wins over both the track and the static.  That is how a
 * value the user has typed but not yet keyed reaches the viewport: the pending edit is not in the document
 * (it is transient session state), so it arrives beside the pose rather than inside the model.
 */

/**
 * The blended value of a SCALAR channel at the given pose, or [staticValue] when it is untracked or the
 * pose falls outside the track's key range.
 *
 * A cell missing from a sparse track contributes nothing rather than falling back, matching how the
 * bundled blend has always treated an unresolvable cell.
 *
 * @param FormChannel channel     The scalar channel to sample.
 * @param Float       staticValue The owner's static value for this channel.
 * @param Function    paramValue  Current value for a given parameter id.
 * @return Float The blended value, or [staticValue].
 */
fun ChannelGrids.scalarAt(
	channel: FormChannel,
	staticValue: Float,
	paramValue: (ParameterId) -> Float,
	override: ChannelValue? = null,
): Float =
	// One body with scalarOrNull, so the renderer's opacity read and the sparse draw-order read can never
	// disagree about the blend arithmetic - two separately maintained copies would drift as one gets
	// tuned and the other forgotten.
	scalarOrNull(channel, paramValue, override) ?: staticValue

/**
 * The blended value of a COLOR channel at the given pose, or [staticValue] when untracked or out of range.
 *
 * @param FormChannel channel     The color channel to sample.
 * @param ColorRgb    staticValue The owner's static color for this channel.
 * @param Function    paramValue  Current value for a given parameter id.
 * @return ColorRgb The blended color, or [staticValue].
 */
fun ChannelGrids.colorAt(
	channel: FormChannel,
	staticValue: ColorRgb,
	paramValue: (ParameterId) -> Float,
	override: ChannelValue? = null,
): ColorRgb {
	(override as? ChannelValue.Color)?.let { pending -> return pending.color }
	val grid = this[channel] ?: return staticValue
	val corners = gridCorners(grid, paramValue) ?: return staticValue
	val cells = cellsByLinearIndex(grid)
	var red = 0f
	var green = 0f
	var blue = 0f
	for (corner in corners) {
		val color = (cells[corner.linearIndex]?.form as? ChannelValue.Color)?.color ?: continue
		red += corner.weight * color.red
		green += corner.weight * color.green
		blue += corner.weight * color.blue
	}
	return ColorRgb(red, green, blue)
}

/**
 * The value of a FLAG channel at the given pose, or [staticValue] when untracked or out of range.
 *
 * A flag snaps to the FLOOR cell instead of blending - [gridCorners] builds its first corner by taking the
 * lower key on every axis, so corner 0 is the floor cell, which is the same cell the bundled rotation
 * blend has always read its reflection flags from.
 *
 * @param FormChannel channel     The flag channel to sample.
 * @param Boolean     staticValue The owner's static flag for this channel.
 * @param Function    paramValue  Current value for a given parameter id.
 * @return Boolean The floor cell's flag, or [staticValue].
 */
fun ChannelGrids.flagAt(
	channel: FormChannel,
	staticValue: Boolean,
	paramValue: (ParameterId) -> Float,
	override: ChannelValue? = null,
): Boolean {
	(override as? ChannelValue.Flag)?.let { pending -> return pending.flag }
	val grid = this[channel] ?: return staticValue
	val corners = gridCorners(grid, paramValue) ?: return staticValue
	val floorCorner = corners.firstOrNull() ?: return staticValue
	val cells = cellsByLinearIndex(grid)
	return (cells[floorCorner.linearIndex]?.form as? ChannelValue.Flag)?.flag ?: staticValue
}

/**
 * The blended value of a SCALAR channel, or null when the owner does not track it or the pose is out of
 * the track's range.
 *
 * The distinction from [scalarAt] matters where absence is itself information - the per-pose part
 * draw-order map is sparse on purpose, so a part with no animated order stays out of it and the renderer
 * keeps its static slot rather than being handed a value that merely equals the static.
 *
 * @param FormChannel channel    The scalar channel to sample.
 * @param Function    paramValue Current value for a given parameter id.
 * @param ChannelValue? override A pending unkeyed edit that wins over the track and the static.
 * @return Float? The blended value, or null when untracked or out of range.
 */
fun ChannelGrids.scalarOrNull(
	channel: FormChannel,
	paramValue: (ParameterId) -> Float,
	override: ChannelValue? = null,
): Float? {
	(override as? ChannelValue.Scalar)?.let { pending -> return pending.value }
	val grid = this[channel] ?: return null
	val corners = gridCorners(grid, paramValue) ?: return null
	val cells = cellsByLinearIndex(grid)
	var total = 0f
	for (corner in corners) {
		val value = (cells[corner.linearIndex]?.form as? ChannelValue.Scalar)?.value ?: continue
		total += corner.weight * value
	}
	return total
}

/**
 * Every axis of every channel track, in channel order.
 *
 * The panel's effective-parameter walk and key-mark scan must union geometry's axes with every channel
 * track's, or an opacity-only track becomes invisible to the parameter list.  This is the one flatten
 * every walk shares; a walk that inlined its own copy instead would silently drift out of sync the
 * moment a channel-only axis (e.g. a glue intensity track) is added.
 *
 * @return List<KeyformAxis> The axes of every track.
 */
fun ChannelGrids.allAxes(): List<KeyformAxis> = gridsByChannel.values.flatMap { grid -> grid.axes }