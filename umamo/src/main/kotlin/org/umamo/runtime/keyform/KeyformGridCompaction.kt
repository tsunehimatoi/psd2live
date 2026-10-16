package org.umamo.runtime.keyform

import org.umamo.runtime.eval.EPS_KEY
import org.umamo.runtime.eval.EPS_SPAN
import org.umamo.runtime.model.ChannelValueKind
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import kotlin.math.abs

/*
 * Exact-only keyform-grid compaction: drop every axis a track does not actually vary along, and every
 * interior key in the middle of a plateau - bit-equal to the keys on either side.
 *
 * This is what turns an imported Cubism rig into a sparse Umamo one.  Cubism keys every channel on every
 * cell of one bundled grid, so after the per-channel fan-out a typical drawable's opacity track is the
 * same value repeated across the whole grid; compacting it to a constant retires the track entirely and
 * the owner's static field takes over.
 *
 * EXACT, never an epsilon.  A key that is merely close to its interpolated neighbours is a value the
 * rigger chose, and silently replacing it would alter their rig - which the fidelity contract forbids.
 *
 * NOT EVEN AN EXACT BLEND.  A key bit-equal to the blend of sloped neighbours still carries information:
 * a pose within EPS_KEY of a key snaps onto it (as Cubism Core does), so the authored track holds the key's
 * value flat across that window, and without the key it would keep sloping - by up to the slope times
 * EPS_KEY.  Only a key whose neighbours hold the same value can go.
 *
 * The drop set is VERIFIED by replaying refinedToUnion's insertions and re-keeping any key whose restored
 * slice is not bit-identical (refinedToUnion re-inserts a key as the blend of its neighbours, which can
 * round off even between equal values, and a key too close to a neighbour is not re-inserted at all):
 * compaction and refinement are mutual inverses by construction.
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
 * parameter.  An interior key is redundant when its whole slice is bit-equal to the slices on either side,
 * so the track is flat across it and the evaluator produces the same value without it.  Endpoint keys are never dropped: the blend test
 * needs both neighbours, and dropping an endpoint would narrow the axis's span and change when the track
 * falls out of range.
 *
 * A [valueKind] of FLAG restricts compaction to whole axes.  A flag snaps to the floor cell rather than
 * blending, and flag tracks are small enough that the conservatism costs nothing.
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
	// Axes first: dropping one shrinks every later pass, and it can expose a key that is now on a plateau.
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
 * endpoints - a different IEEE-754 rounding sequence that matches only algebraically.  A dropped key can
 * also fail to come back at all: refinedToUnion skips a key within EPS_KEY of a neighbour, or one that
 * would leave a span under EPS_SPAN.  So the greedy result is verified by replaying refinedToUnion's own
 * insertions and re-keeping every key of a cell that did not restore bit-identically, until the round
 * trip verifies.
 *
 * Everything runs over one dense array indexed like the grid, without copying the grid or rebuilding a
 * lookup per candidate: a greedy pass and a replay each visit every cell a constant number of times per
 * axis, so a round is linear in the cell count.
 *
 * Terminates: a cell whose keys all survived is carried verbatim, so a mismatched cell always has a
 * dropped key, and every round moves at least one key from dropped to kept.  Cells are compared by their
 * key VALUES - a skipped insertion leaves the restored grid a key short, and comparing by linear index
 * then lines up the wrong cells and blames only kept keys, which once looped forever.
 *
 * A grid with two axes on one parameter, or keys not strictly ascending, keeps every key: refinedToUnion
 * would not restore it the way this replay does.
 *
 * @param FormInterpolator interpolator The blend and exact-equality tests.
 * @return KeyformGrid The reduced grid whose refinement restores this grid bit-for-bit.
 */
private fun <TForm> KeyformGrid<TForm>.withVerifiedInteriorKeyDrops(interpolator: FormInterpolator<TForm>): KeyformGrid<TForm> {
	if (axes.distinctBy { axis -> axis.parameterId }.size != axes.size || axes.any { axis -> !axis.keys.isStrictlyAscending() }) {
		return this
	}
	val dense = DenseKeyforms(this)
	val mustKeep = axes.map { axis -> BooleanArray(axis.keys.size) }
	while (true) {
		val kept = dense.greedyKeptKeys(interpolator, mustKeep)
		if (kept.all { mask -> mask.all { it } }) {
			return this
		}
		val restored = dense.restoredForms(kept, interpolator)
		when (dense.keepMismatchedKeys(restored, kept, mustKeep, interpolator)) {
			Verification.Restored -> return withOnlyKeptKeys(kept)
			Verification.Retry -> continue
			Verification.Stuck -> return this
		}
	}
}

/** The outcome of one verification round. */
private enum class Verification {
	/** Every cell restored bit-exact. */
	Restored,

	/** Some cell did not; its dropped keys are now kept. */
	Retry,

	/** Some cell did not, yet every key it touches was already kept - cannot happen, but must never loop. */
	Stuck,
}

/**
 * A dense grid's forms in one array by linear index, with its keys and strides - what the drop passes
 * walk instead of copying the grid.
 *
 * @param KeyformGrid grid A dense grid.
 */
private class DenseKeyforms<TForm>(grid: KeyformGrid<TForm>) {
	val keys: List<FloatArray> = grid.axes.map { axis -> axis.keys }
	val strides: IntArray = grid.strides()
	private val forms: Array<Any?> = arrayOfNulls(grid.cells.size)

	init {
		for (cell in grid.cells) {
			forms[grid.linearIndexOf(cell.coordinate)] = cell.form
		}
	}

	/**
	 * The keys the greedy pass keeps, per axis: axis by axis, each interior key not in [mustKeep] drops
	 * when its whole slice is bit-equal to both the last kept key below and the next key above.
	 *
	 * The slice spans the keys earlier axes kept and every key of later ones - the cells left in the grid
	 * by the time the pass reaches that axis.
	 *
	 * @param FormInterpolator interpolator The blend and exact-equality tests.
	 * @param List mustKeep Per axis, the keys a verification round proved must stay.
	 * @return List Per axis, true for each kept key.
	 */
	fun greedyKeptKeys(interpolator: FormInterpolator<TForm>, mustKeep: List<BooleanArray>): List<BooleanArray> {
		val kept = keys.map { axisKeys -> BooleanArray(axisKeys.size) { true } }
		for (axisIndex in keys.indices) {
			val axisKeys = keys[axisIndex]
			if (axisKeys.size < 3) {
				continue
			}
			val starts = lineStarts(axisIndex, List(keys.size) { otherAxis -> if (otherAxis < axisIndex) kept[otherAxis] else null })
			var lowerKeyIndex = 0
			for (keyIndex in 1 until axisKeys.size - 1) {
				if (!mustKeep[axisIndex][keyIndex] && isPlateau(axisIndex, starts, lowerKeyIndex, keyIndex, interpolator)) {
					kept[axisIndex][keyIndex] = false
				} else {
					lowerKeyIndex = keyIndex
				}
			}
		}
		return kept
	}

	/**
	 * The forms [refinedToUnion] rebuilds from the grid holding only the [kept] keys, by linear index of
	 * this grid; null where it leaves a cell out.
	 *
	 * Replays its insertions exactly: axis by axis, each dropped key in ascending order, blended from the
	 * nearest present key on either side with the same fraction expression - and skipped, as it skips
	 * one, when the key is within EPS_KEY of a neighbour or a span would fall under EPS_SPAN.
	 *
	 * @param List kept Per axis, true for each kept key.
	 * @param FormInterpolator interpolator The blend.
	 * @return Array The restored forms.
	 */
	fun restoredForms(kept: List<BooleanArray>, interpolator: FormInterpolator<TForm>): Array<Any?> {
		val restored = arrayOfNulls<Any?>(forms.size)
		forEachCell { linearIndex, coordinate ->
			if (coordinate.indices.all { axisIndex -> kept[axisIndex][coordinate[axisIndex]] }) {
				restored[linearIndex] = forms[linearIndex]
			}
		}
		val present = kept.map { mask -> mask.copyOf() }
		for (axisIndex in keys.indices) {
			val axisKeys = keys[axisIndex]
			val axisPresent = present[axisIndex]
			if (axisPresent.all { it }) {
				continue
			}
			val starts = lineStarts(axisIndex, present)
			val stride = strides[axisIndex]
			for (keyIndex in axisKeys.indices) {
				if (axisPresent[keyIndex]) {
					continue
				}
				val keyValue = axisKeys[keyIndex]
				// Endpoints are never dropped, so a present key always lies on both sides.
				val lowerKeyIndex = (keyIndex - 1 downTo 0).first { index -> axisPresent[index] }
				val upperKeyIndex = (keyIndex + 1 until axisKeys.size).first { index -> axisPresent[index] }
				val lowerKey = axisKeys[lowerKeyIndex]
				val upperKey = axisKeys[upperKeyIndex]
				// keyIndexAt's snap, then spansStayResolvable: on ascending keys only the nearest two can trip either.
				if (abs(lowerKey - keyValue) < EPS_KEY || abs(upperKey - keyValue) < EPS_KEY) {
					continue
				}
				if (keyValue - lowerKey < EPS_SPAN || upperKey - keyValue < EPS_SPAN) {
					continue
				}
				val span = upperKey - lowerKey
				val fraction = if (span >= EPS_SPAN) (keyValue - lowerKey) / span else 0f
				for (start in starts) {
					val lowerForm = restored.formAt(start + lowerKeyIndex * stride) ?: continue
					val upperForm = restored.formAt(start + upperKeyIndex * stride) ?: continue
					restored[start + keyIndex * stride] = interpolator.interpolate(lowerForm, upperForm, fraction)
				}
				axisPresent[keyIndex] = true
			}
		}
		return restored
	}

	/**
	 * Marks in [mustKeep] every key of each cell whose [restored] form is missing or not bit-identical.
	 *
	 * Conservative on purpose: every key of a mismatched cell is marked, including kept ones - the next
	 * greedy pass then measures their neighbours against them as they stand.
	 *
	 * @param Array restored The forms [restoredForms] rebuilt.
	 * @param List kept Per axis, the keys this round kept.
	 * @param List mustKeep Per axis, the keys that must stay; updated in place.
	 * @param FormInterpolator interpolator Supplies the exact-equality test.
	 * @return Verification Whether the round trip verified, or how to go on.
	 */
	fun keepMismatchedKeys(
		restored: Array<Any?>,
		kept: List<BooleanArray>,
		mustKeep: List<BooleanArray>,
		interpolator: FormInterpolator<TForm>,
	): Verification {
		var mismatched = false
		var keptADroppedKey = false
		forEachCell { linearIndex, coordinate ->
			val restoredForm = restored.formAt(linearIndex)
			if (restoredForm == null || !interpolator.isExactlyEqual(restoredForm, forms.formAt(linearIndex)!!)) {
				mismatched = true
				for (axisIndex in coordinate.indices) {
					val keyIndex = coordinate[axisIndex]
					if (!mustKeep[axisIndex][keyIndex]) {
						mustKeep[axisIndex][keyIndex] = true
						keptADroppedKey = keptADroppedKey || !kept[axisIndex][keyIndex]
					}
				}
			}
		}
		return when {
			!mismatched -> Verification.Restored
			keptADroppedKey -> Verification.Retry
			else -> Verification.Stuck
		}
	}

	/**
	 * Whether every cell at [keyIndex] on [axisIndex], over the lines from [starts], is bit-equal to both
	 * its counterparts at [lowerKeyIndex] and the next key up, and to their blend.
	 *
	 * Equal to the blend alone is not enough: a pose within EPS_KEY of the key snaps onto it, holding the
	 * value flat where the blend without the key would still slope (see the file note).  The blend test
	 * stays because refinement re-inserts the key as that blend, which can round off even between equal
	 * values; a key it would not restore is better left to stay here than found by verification, which
	 * keeps every key of a mismatched cell.
	 */
	private fun isPlateau(
		axisIndex: Int,
		starts: IntArray,
		lowerKeyIndex: Int,
		keyIndex: Int,
		interpolator: FormInterpolator<TForm>,
	): Boolean {
		val axisKeys = keys[axisIndex]
		val upperKeyIndex = keyIndex + 1
		val span = axisKeys[upperKeyIndex] - axisKeys[lowerKeyIndex]
		if (span < EPS_SPAN) {
			return false
		}
		val fraction = (axisKeys[keyIndex] - axisKeys[lowerKeyIndex]) / span
		val stride = strides[axisIndex]
		for (start in starts) {
			val form = forms.formAt(start + keyIndex * stride)!!
			val lowerForm = forms.formAt(start + lowerKeyIndex * stride)!!
			val upperForm = forms.formAt(start + upperKeyIndex * stride)!!
			if (!interpolator.isExactlyEqual(lowerForm, form) ||
				!interpolator.isExactlyEqual(upperForm, form) ||
				!interpolator.isExactlyEqual(interpolator.interpolate(lowerForm, upperForm, fraction), form)
			) {
				return false
			}
		}
		return true
	}

	/**
	 * The linear index of every cell at key index 0 on [axisIndex] whose key on each other axis is
	 * allowed - the starts of the lines along [axisIndex] through the cells a pass looks at.
	 *
	 * @param Int axisIndex The axis the lines run along.
	 * @param List allowed Per axis, the key indices allowed (true), or null for all of them.
	 * @return IntArray The line starts.
	 */
	private fun lineStarts(axisIndex: Int, allowed: List<BooleanArray?>): IntArray {
		var starts = intArrayOf(0)
		for (otherAxis in keys.indices) {
			if (otherAxis == axisIndex) {
				continue
			}
			val mask = allowed[otherAxis]
			val keyIndices = keys[otherAxis].indices.filter { keyIndex -> mask == null || mask[keyIndex] }
			val next = IntArray(starts.size * keyIndices.size)
			var writeIndex = 0
			for (keyIndex in keyIndices) {
				val offset = keyIndex * strides[otherAxis]
				for (start in starts) {
					next[writeIndex++] = start + offset
				}
			}
			starts = next
		}
		return starts
	}

	/** Calls [action] with every cell's linear index and coordinate, in linear order (axis 0 fastest). */
	private inline fun forEachCell(action: (Int, IntArray) -> Unit) {
		val coordinate = IntArray(keys.size)
		for (linearIndex in forms.indices) {
			action(linearIndex, coordinate)
			var axisIndex = 0
			while (axisIndex < coordinate.size) {
				coordinate[axisIndex]++
				if (coordinate[axisIndex] < keys[axisIndex].size) {
					break
				}
				coordinate[axisIndex] = 0
				axisIndex++
			}
		}
	}

	/** The form at [linearIndex] of a form array, or null where none is held. */
	@Suppress("UNCHECKED_CAST")
	private fun Array<Any?>.formAt(linearIndex: Int): TForm? = this[linearIndex] as TForm?
}

/** Whether every key is below the next one (false for a NaN key). */
private fun FloatArray.isStrictlyAscending(): Boolean = (0 until size - 1).all { index -> this[index] < this[index + 1] }

/**
 * This grid holding only the [kept] keys, its cells in their original order with re-projected coordinates.
 *
 * @param List kept Per axis, true for each kept key; every endpoint is kept.
 * @return KeyformGrid The reduced grid.
 */
private fun <TForm> KeyformGrid<TForm>.withOnlyKeptKeys(kept: List<BooleanArray>): KeyformGrid<TForm> {
	val newKeyIndex =
		kept.map { mask ->
			var nextIndex = 0
			IntArray(mask.size) { keyIndex -> if (mask[keyIndex]) nextIndex++ else -1 }
		}
	val newAxes =
		axes.mapIndexed { axisIndex, axis ->
			KeyformAxis(axis.parameterId, axis.keys.filterIndexed { keyIndex, _ -> kept[axisIndex][keyIndex] }.toFloatArray())
		}
	val newCells =
		cells.mapNotNull { cell ->
			val coordinate = IntArray(cell.coordinate.size) { axisIndex -> newKeyIndex[axisIndex][cell.coordinate[axisIndex]] }
			when {
				coordinate.any { keyIndex -> keyIndex < 0 } -> null
				coordinate.contentEquals(cell.coordinate) -> cell
				else -> KeyformCell(coordinate, cell.form)
			}
		}
	return KeyformGrid(newAxes, newCells)
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
