package org.umamo.runtime.keyform

import org.umamo.runtime.eval.EPS_SPAN
import org.umamo.runtime.model.ChannelValueKind
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.ParameterId

/*
 * Exact-only keyform-grid compaction: drop every axis a track does not actually vary along, and every
 * interior key that is bit-equal to the blend of its neighbours.
 *
 * This is what turns an imported Cubism rig into a sparse Umamo one.  Cubism keys every channel on every
 * cell of one bundled grid, so after the per-channel fan-out a typical drawable's opacity track is the
 * same value repeated across the whole grid; compacting it to a constant retires the track entirely and
 * the owner's static field takes over.
 *
 * EXACT, never an epsilon.  A key that is merely close to its interpolated neighbours is a value the
 * rigger chose, and silently replacing it would alter their rig - which the fidelity contract forbids.
 * The greedy drop test compares against the same FormInterpolator expression refinedToUnion re-inserts
 * with, and the drop set is then VERIFIED by running refinedToUnion itself and re-keeping any key whose
 * restored slice is not bit-identical (two adjacent drops restore through a different rounding sequence
 * than the one that justified them): compaction and refinement are mutual inverses by construction.
 *
 * OUT-OF-SPAN SAFETY.  A pose outside any axis's key span makes the whole track fall back to its owner's
 * static, so an axis whose keys do not bracket the parameter's range acts as a GATE - dropping it (or
 * lifting the track to a constant that overwrites the static) would change what those poses render.  The
 * caller supplies the axis-spans-range predicate; a non-spanning axis is never dropped and a track whose
 * last axis is non-spanning is never lifted.
 */

/**
 * The outcome of compacting one track.
 *
 * @param TForm The cell payload type.
 */
sealed interface CompactionResult<TForm> {
	/**
	 * The track never varies - it holds [form] at every pose, so it needs no grid at all and the value
	 * belongs in its owner's static field.
	 */
	class Constant<TForm>(val form: TForm) : CompactionResult<TForm>

	/** The track still varies; [grid] is it, with the redundant axes and keys removed. */
	class Reduced<TForm>(val grid: KeyformGrid<TForm>) : CompactionResult<TForm>
}

/**
 * This grid with every redundant axis and interior key removed, or the constant it collapses to.
 *
 * An axis is redundant when every cell is bit-equal along it - the track simply does not respond to that
 * parameter.  An interior key is redundant when its whole slice is bit-equal to the blend of the slices on
 * either side, so the evaluator would produce it anyway.  Endpoint keys are never dropped: the blend test
 * needs both neighbours, and dropping an endpoint would narrow the axis's span and change when the track
 * falls out of range.
 *
 * A [valueKind] of FLAG restricts compaction to whole axes.  A flag snaps to the floor cell rather than
 * blending, so "equal to the blend of its neighbours" is not the right test for an interior flag key, and
 * flag tracks are small enough that the conservatism costs nothing.
 *
 * A sparse grid is returned unchanged as [CompactionResult.Reduced] - the missing cells cannot be tested,
 * and inventing them would be a repair this layer has no business making.
 *
 * @param FormInterpolator interpolator The blend and exact-equality tests; must be the same one
 *   [refinedToUnion] will later re-insert with.
 * @param ChannelValueKind? valueKind The track's value kind when it is a channel track, or null for a
 *   geometry grid (which blends like a scalar).
 * @param Function axisSpansRange Whether an axis's keys bracket its parameter's whole range.  A
 *   non-spanning axis gates the track's out-of-span fallback to the owner's static, so it is never
 *   dropped and never lifted; channel compaction MUST pass the model-derived predicate (the permissive
 *   default exists for shape-only tests and single-grid callers with no parameter table in reach).
 * @return CompactionResult The constant the track collapses to, or the reduced grid.
 */
fun <TForm> KeyformGrid<TForm>.compacted(
	interpolator: FormInterpolator<TForm>,
	valueKind: ChannelValueKind? = null,
	axisSpansRange: (KeyformAxis) -> Boolean = { true },
): CompactionResult<TForm> {
	if (!isDense) {
		return CompactionResult.Reduced(this)
	}
	val allowsInteriorKeyDrop = valueKind != ChannelValueKind.FLAG
	var working = this
	// Axes first: dropping one shrinks every later pass, and it can expose a key that is now interpolable.
	// The last axis is left for the constant check below, which has the form to hand back to the owner.
	var axisIndex = 0
	while (axisIndex < working.axes.size) {
		if (working.axes.size > 1 && axisSpansRange(working.axes[axisIndex]) && working.isConstantAlong(axisIndex, interpolator)) {
			working = working.withAxisDropped(axisIndex)
		} else {
			axisIndex++
		}
	}
	// A single remaining axis that the track does not vary along leaves one constant form for the owner -
	// but only when the axis spans its range, else out-of-span poses must keep falling to the static.
	if (working.axes.size == 1 && axisSpansRange(working.axes[0]) && working.isConstantAlong(0, interpolator)) {
		val soleForm = working.cells.firstOrNull()?.form ?: return CompactionResult.Reduced(working)
		return CompactionResult.Constant(soleForm)
	}
	if (working.axes.isEmpty()) {
		val soleForm = working.cells.firstOrNull()?.form ?: return CompactionResult.Reduced(working)
		return CompactionResult.Constant(soleForm)
	}
	if (!allowsInteriorKeyDrop) {
		return CompactionResult.Reduced(working)
	}
	return CompactionResult.Reduced(working.withVerifiedInteriorKeyDrops(interpolator))
}

/**
 * This grid with every interior key dropped whose restoration by [refinedToUnion] is bit-exact.
 *
 * The greedy pass validates each drop against the CURRENT neighbours, but a neighbour used to justify a
 * drop can itself drop later, and refinedToUnion then re-blends the first key from the surviving
 * endpoints - a different IEEE-754 rounding sequence that matches only algebraically.  So the greedy
 * result is verified by running the ACTUAL inverse and re-keeping every key involved in a slice that did
 * not restore bit-identically, until the round trip verifies.
 *
 * Terminates: a mismatched cell always involves at least one dropped key (a cell whose keys all survived
 * is carried verbatim), so every iteration moves at least one key from dropped to kept.
 *
 * @param FormInterpolator interpolator The blend and exact-equality tests.
 * @return KeyformGrid The reduced grid whose refinement restores this grid bit-for-bit.
 */
private fun <TForm> KeyformGrid<TForm>.withVerifiedInteriorKeyDrops(interpolator: FormInterpolator<TForm>): KeyformGrid<TForm> {
	val mustKeep = HashMap<ParameterId, MutableSet<Float>>()
	while (true) {
		val reduced = withGreedyInteriorKeyDrops(interpolator, mustKeep)
		if (reduced === this) {
			return this
		}
		val unionKeysByParameter = axes.associate { axis -> axis.parameterId to axis.keys }
		val restored = reduced.refinedToUnion(unionKeysByParameter, emptyMap(), interpolator)
		val offending = mismatchedKeyValues(restored, interpolator)
		if (offending.isEmpty()) {
			return reduced
		}
		for ((parameterId, keyValues) in offending) {
			mustKeep.getOrPut(parameterId) { HashSet() }.addAll(keyValues)
		}
	}
}

/**
 * The original greedy interior-key drop pass, skipping any key value listed in [mustKeep].
 *
 * @param FormInterpolator interpolator The blend and exact-equality tests.
 * @param Map mustKeep Key values (per parameter) that a prior verification round proved non-restorable.
 * @return KeyformGrid The reduced grid, or this same instance when nothing dropped.
 */
private fun <TForm> KeyformGrid<TForm>.withGreedyInteriorKeyDrops(
	interpolator: FormInterpolator<TForm>,
	mustKeep: Map<ParameterId, Set<Float>>,
): KeyformGrid<TForm> {
	var working = this
	for (targetAxisIndex in working.axes.indices) {
		var keyIndex = 1
		while (keyIndex < working.axes[targetAxisIndex].keys.size - 1) {
			val axis = working.axes[targetAxisIndex]
			val keptExplicitly = mustKeep[axis.parameterId]?.contains(axis.keys[keyIndex]) == true
			if (!keptExplicitly && working.isKeyInterpolable(targetAxisIndex, keyIndex, interpolator)) {
				working = working.withKeyIndexDropped(targetAxisIndex, keyIndex)
			} else {
				keyIndex++
			}
		}
	}
	return working
}

/**
 * The key values (per parameter) touched by any cell of this grid whose counterpart in [restored] is not
 * bit-identical.
 *
 * Conservative on purpose: every coordinate of a mismatched cell is reported, including keys that were
 * never dropped - re-keeping a kept key is a no-op, and the dropped key among them is the one that makes
 * the next verification round converge.
 *
 * @param KeyformGrid restored This grid's shape rebuilt from the reduced grid by [refinedToUnion].
 * @param FormInterpolator interpolator Supplies the exact-equality test.
 * @return Map The offending key values per parameter; empty when the round trip is bit-exact.
 */
private fun <TForm> KeyformGrid<TForm>.mismatchedKeyValues(
	restored: KeyformGrid<TForm>,
	interpolator: FormInterpolator<TForm>,
): Map<ParameterId, Set<Float>> {
	val offending = HashMap<ParameterId, MutableSet<Float>>()
	val restoredByIndex = restored.cells.associate { cell -> restored.linearIndexOf(cell.coordinate) to cell.form }
	for (cell in cells) {
		val counterpart = restoredByIndex[linearIndexOf(cell.coordinate)]
		if (counterpart != null && interpolator.isExactlyEqual(counterpart, cell.form)) {
			continue
		}
		for (axisIndex in axes.indices) {
			val axis = axes[axisIndex]
			offending.getOrPut(axis.parameterId) { HashSet() }.add(axis.keys[cell.coordinate[axisIndex]])
		}
	}
	return offending
}

/**
 * Whether every cell of this grid is bit-equal to its counterpart at key index 0 along [axisIndex].
 *
 * @param Int axisIndex The axis to test.
 * @param FormInterpolator interpolator Supplies the exact-equality test.
 * @return Boolean True when the track does not vary along that axis.
 */
private fun <TForm> KeyformGrid<TForm>.isConstantAlong(axisIndex: Int, interpolator: FormInterpolator<TForm>): Boolean {
	val keyCount = axes[axisIndex].keys.size
	if (keyCount <= 1) {
		return true
	}
	val formByIndex = cells.associate { cell -> linearIndexOf(cell.coordinate) to cell.form }
	for (cell in cells) {
		if (cell.coordinate[axisIndex] == 0) {
			continue
		}
		val baseCoordinate = cell.coordinate.copyOf().also { it[axisIndex] = 0 }
		val baseForm = formByIndex[linearIndexOf(baseCoordinate)] ?: return false
		if (!interpolator.isExactlyEqual(baseForm, cell.form)) {
			return false
		}
	}
	return true
}

/**
 * Whether the whole slice at [keyIndex] on [axisIndex] is bit-equal to the blend of its neighbours - so
 * the evaluator would reproduce it exactly without the key being stored.
 *
 * @param Int axisIndex The axis the key sits on.
 * @param Int keyIndex The interior key's index.
 * @param FormInterpolator interpolator The blend and exact-equality tests.
 * @return Boolean True when the key carries no information.
 */
private fun <TForm> KeyformGrid<TForm>.isKeyInterpolable(
	axisIndex: Int,
	keyIndex: Int,
	interpolator: FormInterpolator<TForm>,
): Boolean {
	val keys = axes[axisIndex].keys
	val span = keys[keyIndex + 1] - keys[keyIndex - 1]
	if (span < EPS_SPAN) {
		return false
	}
	val fraction = (keys[keyIndex] - keys[keyIndex - 1]) / span
	val formByIndex = cells.associate { cell -> linearIndexOf(cell.coordinate) to cell.form }
	for (cell in cells) {
		if (cell.coordinate[axisIndex] != keyIndex) {
			continue
		}
		val lowerCoordinate = cell.coordinate.copyOf().also { it[axisIndex] = keyIndex - 1 }
		val upperCoordinate = cell.coordinate.copyOf().also { it[axisIndex] = keyIndex + 1 }
		val lowerForm = formByIndex[linearIndexOf(lowerCoordinate)] ?: return false
		val upperForm = formByIndex[linearIndexOf(upperCoordinate)] ?: return false
		if (!interpolator.isExactlyEqual(interpolator.interpolate(lowerForm, upperForm, fraction), cell.form)) {
			return false
		}
	}
	return true
}

/**
 * This grid with [axisIndex] removed, keeping the key-index-0 slice.
 *
 * Only ever called on an axis this track is constant along, so which slice survives is immaterial - unlike
 * [withAxisCollapsed], which must pick a slice by parameter value because it is discarding real motion.
 *
 * @param Int axisIndex The axis to drop.
 * @return KeyformGrid The grid one dimension smaller.
 */
private fun <TForm> KeyformGrid<TForm>.withAxisDropped(axisIndex: Int): KeyformGrid<TForm> {
	val newAxes = axes.filterIndexed { index, _ -> index != axisIndex }
	val newCells =
		cells
			.filter { cell -> cell.coordinate[axisIndex] == 0 }
			.map { cell ->
				val reduced = IntArray(cell.coordinate.size - 1)
				var writeIndex = 0
				for (readIndex in cell.coordinate.indices) {
					if (readIndex != axisIndex) {
						reduced[writeIndex] = cell.coordinate[readIndex]
						writeIndex++
					}
				}
				KeyformCell(reduced, cell.form)
			}
	return KeyformGrid(newAxes, newCells)
}

/**
 * This grid with key [keyIndex] dropped from [axisIndex], re-projecting the coordinates above it.
 *
 * The index-addressed counterpart of [withKeyRemoved], without that function's collapse guard: compaction
 * only ever drops interior keys, so at least the two endpoints always remain.
 *
 * @param Int axisIndex The axis to drop a key from.
 * @param Int keyIndex The key index to drop.
 * @return KeyformGrid The grid with the key removed.
 */
private fun <TForm> KeyformGrid<TForm>.withKeyIndexDropped(axisIndex: Int, keyIndex: Int): KeyformGrid<TForm> {
	val oldKeys = axes[axisIndex].keys
	val newKeys = FloatArray(oldKeys.size - 1)
	oldKeys.copyInto(newKeys, destinationOffset = 0, startIndex = 0, endIndex = keyIndex)
	oldKeys.copyInto(newKeys, destinationOffset = keyIndex, startIndex = keyIndex + 1, endIndex = oldKeys.size)
	val newAxes = axes.toMutableList()
	newAxes[axisIndex] = KeyformAxis(axes[axisIndex].parameterId, newKeys)
	val newCells =
		cells.mapNotNull { cell ->
			val keyOnAxis = cell.coordinate[axisIndex]
			when {
				keyOnAxis == keyIndex -> null
				keyOnAxis > keyIndex -> KeyformCell(cell.coordinate.copyOf().also { it[axisIndex] = keyOnAxis - 1 }, cell.form)
				else -> cell
			}
		}
	return KeyformGrid(newAxes.toList(), newCells)
}