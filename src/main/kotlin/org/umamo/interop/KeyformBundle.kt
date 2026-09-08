package org.umamo.interop

import org.umamo.runtime.eval.EPS_KEY
import org.umamo.runtime.keyform.ChannelValueInterpolator
import org.umamo.runtime.keyform.FormInterpolator
import org.umamo.runtime.keyform.refinedToUnion
import org.umamo.runtime.model.ChannelGrids
import org.umamo.runtime.model.ChannelValue
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.ParameterId

/*
 * Re-bundles a runtime entity's SPLIT keyform representation - one geometry grid plus independent
 * per-channel tracks - back into the single dense grid every source format stores.
 *
 * The runtime deliberately keeps geometry and each render channel on their own axes so a rigger
 * can key opacity without touching the mesh.  Both CMO3 and MOC3 store one grid per object
 * instead, with every cell carrying every value, so an export has to union the axes and
 * re-interpolate each part onto the result.  That inversion is exactly `refinedToUnion`'s
 * contract, and doing it identically for both formats is the point of this file: two
 * independent copies would drift the moment one gained a clamp or a tolerance the other lacked,
 * and only a differential oracle would ever notice.
 *
 * @see <a href="https://docs.umamo.org/format/CMO3.md">CMO3.md §6 Export</a>
 * @see <a href="https://docs.umamo.org/format/MOC3.md">MOC3.md §8 Export</a>
 */

/** What to do with a channel whose keyed span does not cover the bundled union axes. */
enum class OutOfSpanPolicy {
	/**
	 * Reject the whole owner: the export cannot represent it and says so.
	 *
	 * CMO3's choice - a partially-representable owner would silently change the rig's shape, and CMO3
	 * can simply decline to write that object's grid.
	 */
	RejectOwner,

	/**
	 * Drop the offending channel to its static value and bundle the rest, naming what was demoted.
	 *
	 * MOC3's choice, and not a preference: every MOC3 object MUST carry at least one keyform, so
	 * rejecting the owner would mean emitting nothing for it and producing a file the runtime cannot
	 * load.  Losing one channel's animation is the strictly smaller loss.
	 */
	DemoteChannel,
}

/** One re-bundled cell: its coordinate, per-parameter key values, geometry, and channel values. */
class KeyformBundleCell(
	val coordinate: IntArray,
	val values: Map<ParameterId, Float>,
	val geometry: Any?,
	val channels: Map<FormChannel, ChannelValue>,
)

/**
 * A dense bundled grid: the union axes and every cell of their cross product.
 *
 * @property List axes            The union axes, geometry axes first.
 * @property List cells           Every cell, in first-axis-fastest order.
 * @property List demotedChannels Channels dropped to their static under [OutOfSpanPolicy.DemoteChannel].
 */
class KeyformBundle(
	val axes: List<KeyformAxis>,
	val cells: List<KeyformBundleCell>,
	val demotedChannels: List<FormChannel> = emptyList(),
)

/** The outcome of a bundling attempt. */
sealed interface KeyformBundleResult {
	/** The entity bundled; [bundle] may still name demoted channels. */
	class Bundled(val bundle: KeyformBundle) : KeyformBundleResult

	/**
	 * The split state cannot be expressed as one grid, and the caller must report it.
	 *
	 * @property KeyformBundleRejection rejection Which bundling rule the keyforms broke.
	 */
	data class Unrepresentable(val rejection: KeyformBundleRejection) : KeyformBundleResult
}

/**
 * Why a bundling attempt failed, for the caller to lower into an export notice.
 *
 * The bundler owns this vocabulary rather than [ExportNoticeReason] because the rule that was broken
 * is a fact about bundling, not about any one format - the CMO3 lowering wraps it in
 * [ExportNoticeReason.KeyformCannotBundle], and a future format would wrap the same cases its own way.
 * Format-neutral for the same reason: the "one grid per object" clause a reader sees is supplied by
 * the wrapping notice, not baked in here.
 */
sealed interface KeyformBundleRejection {
	/** Geometry keys fall outside the union span, and geometry is never demotable. */
	data object KeysOutsideGeometrySpan : KeyformBundleRejection

	/** Channels key but there is no geometry to hang them on, where the caller requires geometry. */
	data object ChannelKeysWithoutGeometry : KeyformBundleRejection

	/**
	 * A channel keys outside its track span, under a policy that rejects the owner rather than
	 * demoting the channel.
	 *
	 * @property FormChannel channel The offending channel.
	 */
	data class KeysOutsideChannelSpan(val channel: FormChannel) : KeyformBundleRejection
}

/**
 * Re-bundles a split representation onto union axes.
 *
 * The axis ORDER follows the geometry grid first, which is the order the import's fan-out preserved -
 * so a round trip through split-then-bundled keeps the source's own axis order rather than inventing
 * one.  Cells are emitted first-axis-fastest, matching the mixed radix both formats index with.
 *
 * @param KeyformGrid?     geometryGrid    The geometry grid, or null for a channel-only owner.
 * @param FormInterpolator geometryBlend   The geometry interpolator (unused when there is no geometry).
 * @param ChannelGrids     channels        The owner's channel tracks.
 * @param Map              statics         Fallback value per relevant channel.
 * @param Boolean          requireGeometry Whether a geometry-less bundle is an error (warp/rotation).
 * @param OutOfSpanPolicy  outOfSpanPolicy What to do with a channel whose span misses the union.
 * @return KeyformBundleResult The bundle - possibly with empty axes when fully unkeyed - or a rejection.
 */
fun <TGeometry> buildKeyformBundle(
	geometryGrid: KeyformGrid<TGeometry>?,
	geometryBlend: FormInterpolator<TGeometry>,
	channels: ChannelGrids,
	statics: Map<FormChannel, ChannelValue>,
	requireGeometry: Boolean,
	outOfSpanPolicy: OutOfSpanPolicy,
): KeyformBundleResult {
	// Union keys per parameter, geometry axes first so the bundled axis order follows the
	// geometry grid (the order the import's fan-out preserved).
	val unionKeys = LinkedHashMap<ParameterId, FloatArray>()

	fun mergeAxis(axis: KeyformAxis) {
		val existing = unionKeys[axis.parameterId]
		unionKeys[axis.parameterId] =
			if (existing == null) axis.keys.copyOf() else mergedKeys(existing, axis.keys)
	}
	geometryGrid?.axes?.forEach(::mergeAxis)
	for (grid in channels.gridsByChannel.values) {
		grid.axes.forEach(::mergeAxis)
	}
	if (unionKeys.isEmpty()) {
		// No parameter keys anywhere.  An axis-less grid (one static cell) is a real shape in both
		// formats and keeps its single cell; a grid-less, channel-less owner is fully unkeyed (empty).
		//
		// AXIS-LESS IS NOT VALUE-LESS.  A channel track can be a single cell with no axes - an authored
		// constant that was never lifted into the owner's static - and that value must win over the
		// static, which in that state still holds its untouched constructor default.  Falling straight
		// back to the statics here would silently replace every such value.
		val channelValues =
			buildMap {
				for ((channel, staticValue) in statics) {
					put(channel, channels.gridsByChannel[channel]?.cells?.firstOrNull()?.form ?: staticValue)
				}
			}
		val staticCell = geometryGrid?.cells?.firstOrNull()
		if (staticCell != null) {
			return KeyformBundleResult.Bundled(
				KeyformBundle(
					emptyList(),
					listOf(KeyformBundleCell(IntArray(0), emptyMap(), staticCell.form, channelValues)),
				),
			)
		}
		// A geometry-less owner whose channels are all axis-less still has one cell's worth of values;
		// emitting nothing would make the caller fall back to the same statics this just corrected.
		if (channels.gridsByChannel.isNotEmpty()) {
			return KeyformBundleResult.Bundled(
				KeyformBundle(emptyList(), listOf(KeyformBundleCell(IntArray(0), emptyMap(), null, channelValues))),
			)
		}
		return KeyformBundleResult.Bundled(KeyformBundle(emptyList(), emptyList()))
	}

	// Refine geometry over its own axes only (an empty ranges map suppresses the append path); every
	// axis must then carry exactly the union keys - a span that does not cover them means an
	// out-of-span key the bundle cannot represent without inventing values.
	var axes: List<KeyformAxis>
	var geometryByCoordinate: Map<List<Int>, TGeometry>
	if (geometryGrid != null) {
		val refined = geometryGrid.refinedToUnion(unionKeys, emptyMap(), geometryBlend)
		for (axis in refined.axes) {
			if (!axis.keys.contentEquals(unionKeys.getValue(axis.parameterId))) {
				// Geometry is never demotable - it is the thing being exported.
				return KeyformBundleResult.Unrepresentable(KeyformBundleRejection.KeysOutsideGeometrySpan)
			}
		}
		axes = refined.axes
		geometryByCoordinate = refined.cells.associate { cell -> cell.coordinate.toList() to cell.form }
		// Replicate across parameters only channels key (the value is constant along them).
		for ((parameterId, keys) in unionKeys) {
			if (axes.none { it.parameterId == parameterId }) {
				axes = axes + KeyformAxis(parameterId, keys)
				geometryByCoordinate =
					buildMap {
						for (keyIndex in keys.indices) {
							for ((coordinate, form) in geometryByCoordinate) {
								put(coordinate + keyIndex, form)
							}
						}
					}
			}
		}
	} else {
		if (requireGeometry) {
			return KeyformBundleResult.Unrepresentable(KeyformBundleRejection.ChannelKeysWithoutGeometry)
		}
		axes = unionKeys.map { (parameterId, keys) -> KeyformAxis(parameterId, keys) }
		geometryByCoordinate = emptyMap()
	}

	// Refine each channel over its own axes; a final-coordinate lookup then projects onto the
	// channel's axis subset (the channel is constant along axes it does not key).
	val channelLookups = HashMap<FormChannel, (IntArray) -> ChannelValue?>()
	val demotedChannels = ArrayList<FormChannel>()
	val finalAxisPosition = axes.mapIndexed { position, axis -> axis.parameterId to position }.toMap()
	for ((channel, track) in channels.gridsByChannel) {
		val refined = track.refinedToUnion(unionKeys, emptyMap(), ChannelValueInterpolator)
		val outOfSpan =
			refined.axes.any { axis -> !axis.keys.contentEquals(unionKeys.getValue(axis.parameterId)) }
		if (outOfSpan) {
			if (outOfSpanPolicy == OutOfSpanPolicy.RejectOwner) {
				return KeyformBundleResult.Unrepresentable(
					KeyformBundleRejection.KeysOutsideChannelSpan(channel),
				)
			}
			// Demoted: no lookup is registered, so every cell falls back to the channel's static below.
			demotedChannels.add(channel)
			continue
		}
		val subPositions =
			refined.axes.map { axis -> finalAxisPosition.getValue(axis.parameterId) }.toIntArray()
		channelLookups[channel] =
			{ coordinate ->
				val subCoordinate = IntArray(subPositions.size) { axisIndex -> coordinate[subPositions[axisIndex]] }
				refined.cellsByLinearIndex[refined.linearIndexOf(subCoordinate)]?.form
			}
	}

	// Assemble every cell of the dense final grid.
	val keyCounts = axes.map { it.keys.size }
	val totalCells = keyCounts.fold(1) { product, count -> product * count }
	val cells = ArrayList<KeyformBundleCell>(totalCells)
	val coordinate = IntArray(axes.size)
	for (cellOrdinal in 0 until totalCells) {
		var remainder = cellOrdinal
		for (axisIndex in axes.indices) {
			coordinate[axisIndex] = remainder % keyCounts[axisIndex]
			remainder /= keyCounts[axisIndex]
		}
		val cellCoordinate = coordinate.copyOf()
		val values =
			buildMap {
				for (axisIndex in axes.indices) {
					put(axes[axisIndex].parameterId, axes[axisIndex].keys[cellCoordinate[axisIndex]])
				}
			}
		val channelValues =
			buildMap {
				for ((channel, staticValue) in statics) {
					put(channel, channelLookups[channel]?.invoke(cellCoordinate) ?: staticValue)
				}
			}
		cells.add(
			KeyformBundleCell(cellCoordinate, values, geometryByCoordinate[cellCoordinate.toList()], channelValues),
		)
	}
	return KeyformBundleResult.Bundled(KeyformBundle(axes, cells, demotedChannels))
}

/**
 * The two key arrays merged ascending with evaluator-tolerance duplicates dropped.
 *
 * @param FloatArray first  One axis's keys.
 * @param FloatArray second The other's.
 * @return FloatArray The merged ascending keys.
 */
private fun mergedKeys(first: FloatArray, second: FloatArray): FloatArray {
	val sorted = (first + second).sortedArray()
	val kept = ArrayList<Float>(sorted.size)
	for (candidate in sorted) {
		// The evaluator's own key-snap tolerance: two keys it cannot tell apart stay one key.
		if (kept.isEmpty() || candidate - kept.last() >= EPS_KEY) {
			kept.add(candidate)
		}
	}
	return kept.toFloatArray()
}