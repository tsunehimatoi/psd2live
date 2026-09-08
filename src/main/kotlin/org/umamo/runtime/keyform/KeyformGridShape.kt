package org.umamo.runtime.keyform

import org.umamo.runtime.eval.EPS_KEY
import org.umamo.runtime.model.ChannelGrids
import org.umamo.runtime.model.ChannelValue
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.ParameterId
import kotlin.math.abs

/*
 * Pure shape queries and shape edits over a KeyformGrid: strides, the linear cell index, density
 * checks, and axis removal.  Nothing here interpolates a form and nothing here evaluates a pose - the
 * multilinear blend stays in runtime.eval, which this package must never duplicate.
 *
 * This lives in :runtime rather than :edit because the CMO3 / MOC3 importers need it and :runtime
 * cannot depend on :edit.
 */

/**
 * The grid's linear cell extent: the product of every axis's key count.
 *
 * Zero for a grid carrying an empty axis, which is a malformed grid rather than a rest cell; the
 * delta-texture sizing in :render floors its own copy at 1 because a texture cannot be zero-wide.
 *
 * @return Int The number of cells a dense grid of this shape holds.
 */
val KeyformGrid<*>.cellCount: Int
	get() = axes.fold(1) { count, axis -> count * axis.keys.size }

/**
 * Whether this grid holds exactly one cell per coordinate in its shape.
 *
 * A CMO3 grid can arrive SPARSE - Cmo3Import drops cells whose form guid does not resolve - and a
 * sparse grid blends toward zero rather than erroring.  Compaction and refine both refuse a sparse
 * grid rather than inventing the missing cells, so they ask this first.
 *
 * @return Boolean True when the grid is dense and every cell index is in range and distinct.
 */
val KeyformGrid<*>.isDense: Boolean
	get() {
		val expectedCellCount = cellCount
		if (expectedCellCount <= 0 || cells.size != expectedCellCount) {
			return false
		}
		val seenIndices = HashSet<Int>(cells.size)
		for (cell in cells) {
			if (cell.coordinate.size != axes.size) {
				return false
			}
			val linearIndex = linearIndexOf(cell.coordinate)
			if (linearIndex < 0 || linearIndex >= expectedCellCount || !seenIndices.add(linearIndex)) {
				return false
			}
		}
		return true
	}

/**
 * The index of the axis keying on [parameterId], or -1 when this grid has no such axis.
 *
 * @param ParameterId parameterId The parameter to look for.
 * @return Int The axis index, or -1 when absent.
 */
fun KeyformGrid<*>.axisIndexOf(parameterId: ParameterId): Int = axes.indexOfFirst { axis -> axis.parameterId == parameterId }

/**
 * This grid with the axis for [parameterId] removed, collapsing that dimension to the key nearest
 * [keepKeyValue]. Returns this same instance when the grid has no such axis (so a caller can skip
 * untouched entities by identity), or null when removing the axis leaves no axes at all (the entity
 * becomes unkeyed). The surviving cells are those whose coordinate on the dropped axis is the kept key
 * index, each re-projected to the N-1 coordinate; the form payloads are carried through untouched.
 *
 * The kept slice is the NEAREST key, not an interpolated one, so collapsing an axis whose kept value
 * falls between keys discards the interpolated pose rather than baking it.
 *
 * @param ParameterId parameterId The axis parameter to remove.
 * @param Float keepKeyValue The parameter value whose nearest key slice survives (the deleted default).
 * @return KeyformGrid The collapsed grid, this if the axis was absent, or null if no axes remain.
 */
fun <TForm> KeyformGrid<TForm>.withAxisCollapsed(parameterId: ParameterId, keepKeyValue: Float): KeyformGrid<TForm>? {
	val axisIndex = axisIndexOf(parameterId)
	if (axisIndex < 0) {
		return this
	}
	val newAxes = axes.filterIndexed { index, _ -> index != axisIndex }
	if (newAxes.isEmpty()) {
		return null
	}
	val keepIndex = nearestKeyIndex(axes[axisIndex].keys, keepKeyValue)
	val newCells =
		cells
			.filter { cell -> cell.coordinate[axisIndex] == keepIndex }
			.map { cell -> KeyformCell(cell.coordinate.withElementRemoved(axisIndex), cell.form) }
	return KeyformGrid(newAxes, newCells)
}

/**
 * The index of the key on [parameterId]'s axis that [value] sits exactly on, or -1.
 *
 * "Exactly" means within the evaluator's own EPS_KEY snap tolerance, so this agrees with what is actually
 * being rendered: if the pose resolves to a key, this finds it.  Authoring ops use it to decide whether an
 * insert lands on an existing key (overwrite) or between two (reshape).
 *
 * @param ParameterId parameterId The axis to look on.
 * @param Float value The pose value.
 * @return Int The key index, or -1 when the axis is absent or no key is in range.
 */
fun KeyformGrid<*>.keyIndexAt(parameterId: ParameterId, value: Float): Int {
	val axisIndex = axisIndexOf(parameterId)
	if (axisIndex < 0) {
		return -1
	}
	return axes[axisIndex].keys.indexOfFirst { key -> abs(key - value) < EPS_KEY }
}

/**
 * The result of collapsing an axis out of a whole owner's channel tracks: the surviving tracks, plus the
 * value each fully-collapsed track held on its kept slice.
 *
 * The lifted value is what the owner's static field must take over so the collapse preserves the neutral
 * look - dropping a track without lifting it would snap the channel back to whatever the static happened
 * to hold (typically the import-time value), visibly changing the rig.
 *
 * @property ChannelGrids channelGrids The scrubbed tracks.
 * @property Map lifted Per fully-collapsed channel, the value its kept slice held.
 */
class CollapsedChannels(
	val channelGrids: ChannelGrids,
	val lifted: Map<FormChannel, ChannelValue>,
)

/**
 * Every one of this owner's channel tracks with the axis for [parameterId] collapsed out, lifting the
 * kept slice's value of any track that drops entirely.
 *
 * The whole-owner counterpart of [withAxisCollapsed], so deleting a parameter scrubs every track in one
 * walk rather than needing a hand-written case per channel - a hand-rolled enumeration is exactly the kind
 * of list a channel like a glue's intensity grid could quietly be left off.  A track left with no axes is
 * dropped, and the value of its kept slice is returned
 * in [CollapsedChannels.lifted] for the caller to write into the owner's static.  A sparse grid missing
 * its kept cell lifts nothing (the static keeps its current value).  Returns this same grids instance
 * (with an empty lifted map) when no track referenced the parameter.
 *
 * @param ParameterId parameterId The axis parameter to remove from every track.
 * @param Float keepKeyValue The parameter value whose nearest key slice survives.
 * @return CollapsedChannels The scrubbed tracks plus the lifted slice values.
 */
fun ChannelGrids.withAxisCollapsedLifting(parameterId: ParameterId, keepKeyValue: Float): CollapsedChannels {
	var changed = false
	val scrubbed = LinkedHashMap<FormChannel, KeyformGrid<ChannelValue>>(gridsByChannel.size)
	val lifted = LinkedHashMap<FormChannel, ChannelValue>()
	for ((channel, grid) in gridsByChannel) {
		val collapsed = grid.withAxisCollapsed(parameterId, keepKeyValue)
		if (collapsed === grid) {
			scrubbed[channel] = grid
			continue
		}
		changed = true
		if (collapsed != null) {
			scrubbed[channel] = collapsed
			continue
		}
		val axisIndex = grid.axisIndexOf(parameterId)
		val keepIndex = nearestKeyIndex(grid.axes[axisIndex].keys, keepKeyValue)
		val keptCell = grid.cells.firstOrNull { cell -> cell.coordinate.getOrNull(axisIndex) == keepIndex }
		if (keptCell != null) {
			lifted[channel] = keptCell.form
		}
	}
	return if (changed) CollapsedChannels(ChannelGrids(scrubbed), lifted) else CollapsedChannels(this, emptyMap())
}

/**
 * The index of the key in [keys] nearest [value] (0 for an empty axis, defensively).
 *
 * @param FloatArray keys The axis's key positions.
 * @param Float value The parameter value to match.
 * @return Int The nearest key's index.
 */
private fun nearestKeyIndex(keys: FloatArray, value: Float): Int {
	if (keys.isEmpty()) {
		return 0
	}
	var bestIndex = 0
	var bestDistance = abs(keys[0] - value)
	for (keyIndex in 1 until keys.size) {
		val distance = abs(keys[keyIndex] - value)
		if (distance < bestDistance) {
			bestDistance = distance
			bestIndex = keyIndex
		}
	}
	return bestIndex
}

/**
 * A copy of this coordinate array with the element at [removeIndex] dropped (length shrinks by one).
 *
 * @param Int removeIndex The element index to drop.
 * @return IntArray The shortened coordinate.
 */
private fun IntArray.withElementRemoved(removeIndex: Int): IntArray {
	val result = IntArray(size - 1)
	var writeIndex = 0
	for (readIndex in indices) {
		if (readIndex != removeIndex) {
			result[writeIndex] = this[readIndex]
			writeIndex++
		}
	}
	return result
}