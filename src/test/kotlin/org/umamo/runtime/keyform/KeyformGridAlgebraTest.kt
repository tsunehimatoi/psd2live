package org.umamo.runtime.keyform

import org.umamo.runtime.eval.EPS_SPAN
import org.umamo.runtime.eval.gridCorners
import org.umamo.runtime.model.ChannelValue
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins the keyform authoring algebra: seeding an axis spans the parameter range and leaves the entity
 * looking unchanged, key insertion fills a whole slice by interpolation, removal collapses rather than
 * leaving a one-key axis that would hide the entity, and capture always lands on an exact cell.
 *
 * The refusals matter as much as the successes - a key too close to an existing one, or a span the
 * evaluator resolves as a step, produces a grid that cannot be evaluated as authored.
 */
class KeyformGridAlgebraTest {
	private val angleX = ParameterId("ParamAngleX")
	private val angleY = ParameterId("ParamAngleY")

	private fun parameter(id: ParameterId, min: Float = -1f, default: Float = 0f, max: Float = 1f): Parameter =
		Parameter(id, id.raw, min = min, max = max, default = default)

	private fun scalar(value: Float): ChannelValue = ChannelValue.Scalar(value)

	private fun scalarOf(cell: KeyformCell<ChannelValue>): Float = (cell.form as ChannelValue.Scalar).value

	/** A one-axis track on angleX with the given keys, each cell holding the matching value. */
	private fun track(keys: FloatArray, values: FloatArray): KeyformGrid<ChannelValue> =
		KeyformGrid(
			listOf(KeyformAxis(angleX, keys)),
			values.mapIndexed { keyIndex, value -> KeyformCell(intArrayOf(keyIndex), scalar(value)) },
		)

	/** The track's value at [poseValue], read through the real evaluator rather than a reimplementation. */
	private fun sample(grid: KeyformGrid<ChannelValue>, poseValue: Float, otherValue: Float = 0f): Float? {
		val corners = gridCorners(grid) { parameterId -> if (parameterId == angleX) poseValue else otherValue } ?: return null
		val formByIndex = grid.cells.associate { cell -> grid.linearIndexOf(cell.coordinate) to cell.form }
		var total = 0f
		for (corner in corners) {
			total += corner.weight * ((formByIndex[corner.linearIndex] as? ChannelValue.Scalar)?.value ?: 0f)
		}
		return total
	}

	/** Seeding a fresh axis spans min..max so the entity never falls out of range inside the slider. */
	@Test
	fun seedingSpansTheParameterRange() {
		val seeded = null.withAxisSeeded(parameter(angleX, min = -30f, default = 0f, max = 30f), scalar(0.5f))
		assertNotNull(seeded)
		assertEquals(listOf(-30f, 0f, 30f), seeded.axes.single().keys.toList())
		assertEquals(listOf(0.5f, 0.5f, 0.5f), seeded.cells.map { cell -> scalarOf(cell) }, "every seeded key holds the current value")
	}

	/** A default that coincides with an endpoint collapses to two keys rather than a degenerate span. */
	@Test
	fun seedingDeduplicatesACoincidentDefault() {
		val seeded = null.withAxisSeeded(parameter(angleX, min = 0f, default = 0f, max = 1f), scalar(1f))
		assertEquals(listOf(0f, 1f), assertNotNull(seeded).axes.single().keys.toList())
	}

	/** A parameter that cannot yield two distinct keys is refused rather than seeded into a hiding axis. */
	@Test
	fun seedingRefusesADegenerateRange() {
		assertNull(null.withAxisSeeded(parameter(angleX, min = 0f, default = 0f, max = 0f), scalar(1f)))
	}

	/** Binding a second parameter appends its axis and replicates the grid, so nothing moves. */
	@Test
	fun seedingASecondAxisAppendsAndReplicates() {
		val existing = track(floatArrayOf(-1f, 1f), floatArrayOf(10f, 20f))
		val seeded = assertNotNull(existing.withAxisSeeded(parameter(angleY), scalar(99f)))
		assertEquals(listOf(angleX, angleY), seeded.axes.map { axis -> axis.parameterId }, "the new axis is appended, not spliced")
		assertEquals(6, seeded.cells.size)
		assertTrue(seeded.isDense)
		// The pre-existing motion is unchanged at every angleY key, which is what makes binding non-destructive.
		for (poseY in listOf(-1f, 0f, 1f)) {
			assertEquals(15f, sample(seeded, poseValue = 0f, otherValue = poseY))
		}
	}

	/**
	 * Inserting a key leaves the evaluated curve alone - it adds a sample, not a shape change.
	 *
	 * Asserted to a tolerance, NOT bit-equality, and deliberately so: re-associating one linear blend into
	 * two consecutive ones is exact in the reals but lands a few ULP apart in float (pose 6 evaluates
	 * 60.000004 before the insert and 60.0 after).  The bit-exact claim in this layer belongs only to
	 * compaction and refinement being mutual inverses, which evaluate the SAME expression on the SAME
	 * inputs - see KeyformCompactionTest.  Asserting bit-equality here would be claiming something the
	 * arithmetic does not support.
	 */
	@Test
	fun insertingAKeyPreservesTheCurve() {
		val before = track(floatArrayOf(0f, 10f), floatArrayOf(0f, 100f))
		val after = before.withKeyInserted(angleX, 2.5f, ChannelValueInterpolator)
		assertEquals(listOf(0f, 2.5f, 10f), after.axes.single().keys.toList())
		assertEquals(25f, scalarOf(after.cells.first { cell -> cell.coordinate.single() == 1 }))
		for (poseValue in listOf(0f, 1f, 2.5f, 6f, 10f)) {
			val beforeValue = assertNotNull(sample(before, poseValue))
			val afterValue = assertNotNull(sample(after, poseValue))
			assertEquals(beforeValue, afterValue, absoluteTolerance = 1e-3f, message = "pose $poseValue is unchanged")
		}
	}

	/** Inserting on a multi-axis grid adds a whole slice, one cell per key of every other axis. */
	@Test
	fun insertingAddsAWholeSliceNotOneCell() {
		val axes = listOf(KeyformAxis(angleX, floatArrayOf(0f, 10f)), KeyformAxis(angleY, floatArrayOf(0f, 1f)))
		val cells =
			listOf(
				KeyformCell(intArrayOf(0, 0), scalar(0f)),
				KeyformCell(intArrayOf(1, 0), scalar(100f)),
				KeyformCell(intArrayOf(0, 1), scalar(0f)),
				KeyformCell(intArrayOf(1, 1), scalar(200f)),
			)
		val after = KeyformGrid(axes, cells).withKeyInserted(angleX, 5f, ChannelValueInterpolator)
		assertEquals(6, after.cells.size, "a 2x2 grid gains a column, not a single cell")
		assertTrue(after.isDense)
		val insertedSlice = after.cells.filter { cell -> cell.coordinate[0] == 1 }.sortedBy { cell -> cell.coordinate[1] }
		assertEquals(listOf(50f, 100f), insertedSlice.map { cell -> scalarOf(cell) }, "each new cell blends its own row")
	}

	/** A key within the evaluator's snap tolerance of an existing one is refused, not stacked beside it. */
	@Test
	fun insertingRefusesAKeyInsideTheSnapTolerance() {
		val before = track(floatArrayOf(0f, 10f), floatArrayOf(0f, 100f))
		assertSame(before, before.withKeyInserted(angleX, 0.0005f, ChannelValueInterpolator))
	}

	/** A key that would leave a span the evaluator resolves as a step is refused. */
	@Test
	fun insertingRefusesASubResolutionSpan() {
		val before = track(floatArrayOf(0f, 10f), floatArrayOf(0f, 100f))
		assertSame(before, before.withKeyInserted(angleX, 0.0012f, ChannelValueInterpolator))
	}

	/** Beyond the span, Extend replicates the end slice - constant extrapolation, never a projected trend. */
	@Test
	fun insertingBeyondTheSpanExtrapolatesFlat() {
		val before = track(floatArrayOf(0f, 10f), floatArrayOf(0f, 100f))
		val after = before.withKeyInserted(angleX, 20f, ChannelValueInterpolator, OutOfSpanKeyPolicy.Extend)
		assertEquals(listOf(0f, 10f, 20f), after.axes.single().keys.toList())
		assertEquals(100f, scalarOf(after.cells.first { cell -> cell.coordinate.single() == 2 }), "the end value is held, not projected to 200")
		assertSame(before, before.withKeyInserted(angleX, 20f, ChannelValueInterpolator, OutOfSpanKeyPolicy.Reject))
	}

	/** Removing an interior key leaves the axis intact and re-projects the coordinates above it. */
	@Test
	fun removingAnInteriorKeyKeepsTheAxis() {
		val before = track(floatArrayOf(0f, 5f, 10f), floatArrayOf(0f, 40f, 100f))
		val after = assertNotNull(before.withKeyRemoved(angleX, keyIndex = 1))
		assertEquals(listOf(0f, 10f), after.axes.single().keys.toList())
		assertEquals(listOf(0f, 100f), after.cells.map { cell -> scalarOf(cell) })
	}

	/**
	 * Removing down to one key collapses the axis instead of leaving a stub, because a single-key axis
	 * resolves only within EPS_KEY of that key and hides the entity everywhere else.
	 */
	@Test
	fun removingBelowTwoKeysCollapsesTheAxis() {
		val before = track(floatArrayOf(0f, 10f), floatArrayOf(7f, 100f))
		// Sole axis, so the collapse leaves the entity unkeyed entirely.
		assertNull(before.withKeyRemoved(angleX, keyIndex = 1))

		val twoAxis = assertNotNull(before.withAxisSeeded(parameter(angleY), scalar(0f)))
		val collapsed = assertNotNull(twoAxis.withKeyRemoved(angleX, keyIndex = 1))
		assertEquals(listOf(angleY), collapsed.axes.map { axis -> axis.parameterId }, "angleX collapsed rather than keeping one key")
	}

	/** Capture inserts a key at the pose, writes exactly one cell, and leaves every other pose alone. */
	@Test
	fun captureWritesOneCellAtThePose() {
		val before = track(floatArrayOf(0f, 10f), floatArrayOf(0f, 100f))
		val after = before.withFormCaptured({ 4f }, scalar(80f), ChannelValueInterpolator)
		assertEquals(listOf(0f, 4f, 10f), after.axes.single().keys.toList())
		assertEquals(80f, assertNotNull(sample(after, 4f)), "the captured value lands exactly on the pose")
		assertEquals(0f, assertNotNull(sample(after, 0f)), "the endpoints are untouched")
		assertEquals(100f, assertNotNull(sample(after, 10f)))
	}

	/** Capturing at an existing key overwrites that cell without reshaping the axis. */
	@Test
	fun captureOnAnExistingKeyDoesNotReshape() {
		val before = track(floatArrayOf(0f, 10f), floatArrayOf(0f, 100f))
		val after = before.withFormCaptured({ 10f }, scalar(55f), ChannelValueInterpolator)
		assertEquals(listOf(0f, 10f), after.axes.single().keys.toList())
		assertEquals(55f, assertNotNull(sample(after, 10f)))
	}

	/** A sparse grid is refused by every reshaping op rather than being silently repaired. */
	@Test
	fun sparseGridsAreRefused() {
		val dense = track(floatArrayOf(0f, 5f, 10f), floatArrayOf(0f, 40f, 100f))
		val sparse = KeyformGrid(dense.axes, dense.cells.drop(1))
		assertSame(sparse, sparse.withKeyInserted(angleX, 2f, ChannelValueInterpolator))
		assertSame(sparse, sparse.withKeyRemoved(angleX, keyIndex = 1))
		assertSame(sparse, sparse.withFormCaptured({ 2f }, scalar(1f), ChannelValueInterpolator))
	}

	/** Moving a key changes only WHERE it applies; the value it holds rides along. */
	@Test
	fun movingAKeyCarriesItsValue() {
		val before = track(floatArrayOf(0f, 5f, 10f), floatArrayOf(0f, 42f, 100f))
		val after = before.withKeyMoved(angleX, keyIndex = 1, newValue = 8f)
		assertEquals(listOf(0f, 8f, 10f), after.axes.single().keys.toList())
		assertEquals(42f, assertNotNull(sample(after, 8f)), "the moved key still holds what it held")
	}

	/**
	 * A key may CROSS its neighbours; the axis re-sorts and the cells permute to match.
	 *
	 * The permutation is the whole cost of allowing it.  A cell's coordinate on an axis is a key ORDINAL
	 * and the evaluator requires an ascending axis, so re-sorting the keys alone would still satisfy
	 * isDense while silently applying every form at the wrong position.
	 */
	@Test
	fun movingAcrossANeighbourPermutesTheCells() {
		val before = track(floatArrayOf(0f, 5f, 10f), floatArrayOf(0f, 42f, 100f))
		val crossed = before.withKeyMoved(angleX, keyIndex = 1, newValue = 20f)

		assertEquals(listOf(0f, 10f, 20f), crossed.axes.single().keys.toList(), "the axis re-sorts")
		assertTrue(crossed.isDense, "and stays dense")
		assertEquals(42f, assertNotNull(sample(crossed, 20f)), "the moved key still holds what it held")
		assertEquals(100f, assertNotNull(sample(crossed, 10f)), "the key it passed still holds what it held")
		assertEquals(0f, assertNotNull(sample(crossed, 0f)), "and the untouched key is untouched")
	}

	/** Crossing works downward too, and the whole run of passed keys shifts up by one ordinal. */
	@Test
	fun movingDownAcrossSeveralNeighbours() {
		val before = track(floatArrayOf(0f, 5f, 10f, 15f), floatArrayOf(0f, 42f, 100f, 7f))
		val crossed = before.withKeyMoved(angleX, keyIndex = 3, newValue = 2f)

		assertEquals(listOf(0f, 2f, 5f, 10f), crossed.axes.single().keys.toList())
		assertTrue(crossed.isDense)
		assertEquals(7f, assertNotNull(sample(crossed, 2f)), "the dragged key kept its value across three crossings")
		assertEquals(42f, assertNotNull(sample(crossed, 5f)))
		assertEquals(100f, assertNotNull(sample(crossed, 10f)))
	}

	/**
	 * A destination that would collide with another key is nudged just far enough to stay resolvable.
	 *
	 * Not a spacing preference: two keys closer than EPS_SPAN make the evaluator read fraction 0 across the
	 * gap, so the nearer one becomes unreachable and the blend degenerates into a step.
	 */
	@Test
	fun aCollidingDestinationStaysResolvable() {
		val before = track(floatArrayOf(0f, 5f, 10f), floatArrayOf(0f, 42f, 100f))
		val landed = before.withKeyMoved(angleX, keyIndex = 0, newValue = 5f)
		val keys = landed.axes.single().keys.toList()

		assertTrue(keys[1] - keys[0] >= EPS_SPAN, "the two keys stay at least a resolvable span apart")
		assertEquals(42f, assertNotNull(sample(landed, 5f)), "the key it landed beside is still reachable")
	}

	/**
	 * A key dropped exactly onto the LAST key is nudged inward, never past it.
	 *
	 * Outward would put it beyond the axis's own span - and so beyond the parameter's range, where the
	 * evaluator brackets nothing and every entity keyed on it disappears.  That is the difference between
	 * a sub-pixel nudge and a vanishing rig.
	 */
	@Test
	fun aCollisionAtAnEndpointNudgesInward() {
		val before = track(floatArrayOf(-30f, 0f, 30f), floatArrayOf(0f, 42f, 100f))
		val onTheLast = before.withKeyMoved(angleX, keyIndex = 1, newValue = 30f)
		val highest = onTheLast.axes.single().keys.max()
		assertEquals(30f, highest, "the axis did not grow past where it already reached")

		val onTheFirst = before.withKeyMoved(angleX, keyIndex = 1, newValue = -30f)
		assertEquals(-30f, onTheFirst.axes.single().keys.min(), "and not below it either")
	}

	/** The mover's new ordinal is reported, so whatever is holding that key can follow it. */
	@Test
	fun theNewOrdinalIsReported() {
		val before = track(floatArrayOf(0f, 5f, 10f), floatArrayOf(0f, 42f, 100f))
		assertEquals(2, before.keyIndexAfterMove(angleX, keyIndex = 1, newValue = 20f), "crossed to the end")
		assertEquals(0, before.keyIndexAfterMove(angleX, keyIndex = 2, newValue = -5f), "crossed to the start")
		assertEquals(1, before.keyIndexAfterMove(angleX, keyIndex = 1, newValue = 6f), "stayed put")
	}

	/** An endpoint has one wall only, so a move can still widen the axis - that is real authoring. */
	@Test
	fun movingAnEndpointResizesTheSpan() {
		val before = track(floatArrayOf(0f, 5f, 10f), floatArrayOf(0f, 42f, 100f))
		val widened = before.withKeyMoved(angleX, keyIndex = 2, newValue = 30f)
		assertEquals(listOf(0f, 5f, 30f), widened.axes.single().keys.toList())
	}

	/** A move to where the key already sits, or on a missing axis, records nothing. */
	@Test
	fun aPointlessMoveIsARefusal() {
		val before = track(floatArrayOf(0f, 5f, 10f), floatArrayOf(0f, 42f, 100f))
		assertSame(before, before.withKeyMoved(angleX, keyIndex = 1, newValue = 5f))
		assertSame(before, before.withKeyMoved(angleY, keyIndex = 0, newValue = 1f))
	}

	/**
	 * The collision nudge never lands ON another key, even where the only gap is one it created itself.
	 *
	 * The nudge is measured against the NEAREST key, so on a crowded axis "nearest + EPS_SPAN" can be
	 * exactly where the next key along already sits - and two keys closer than EPS_SPAN is the
	 * unresolvable span the nudge exists to prevent (bindBracket gives such a span fraction 0, so the
	 * upper key can never be reached).  A move that cannot be made resolvable is refused instead.
	 */
	@Test
	fun theCollisionNudgeNeverLandsOnAnotherKey() {
		val crowded = track(floatArrayOf(0f, EPS_SPAN, 10f), floatArrayOf(0f, 1f, 2f))
		// Aimed just above the key at 0: the nearest is 0, so the nudge goes to 0 + EPS_SPAN - which is
		// exactly the second key.
		assertNull(
			crowded.keyDestinationFor(angleX, keyIndex = 2, newValue = EPS_SPAN / 3f),
			"nudging onto an existing key is refused rather than authored",
		)
		assertSame(crowded, crowded.withKeyMoved(angleX, keyIndex = 2, newValue = EPS_SPAN / 3f), "so the move is a no-op")

		// The same axis still accepts a destination with room on both sides.
		val moved = crowded.withKeyMoved(angleX, keyIndex = 2, newValue = 5f)
		assertEquals(listOf(0f, EPS_SPAN, 5f), moved.axes.single().keys.toList())
	}
}