package org.umamo.runtime.keyform

import org.umamo.runtime.eval.cellsByLinearIndex
import org.umamo.runtime.model.GlueForm
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.ParameterId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins the keyform-grid shape layer: the stride folding (which must agree with the evaluator's own,
 * since the resulting linear index is the shared contract between a weighted corner, a stored cell,
 * and the GPU delta texture's column), the coordinate round trip, the density check that compaction
 * and refine gate on, and withAxisCollapsed's coordinate re-projection.
 */
class KeyformGridShapeTest {
	private val angleX = ParameterId("ParamAngleX")
	private val angleY = ParameterId("ParamAngleY")
	private val angleZ = ParameterId("ParamAngleZ")

	/**
	 * Unfolds a linear index back into a per-axis coordinate - the inverse of linearIndexOf, kept as a
	 * fixture helper (production code only ever folds).
	 */
	private fun KeyformGrid<*>.coordinateOf(linearIndex: Int): IntArray {
		val coordinate = IntArray(axes.size)
		var remaining = linearIndex
		for (axisIndex in axes.indices) {
			val keyCount = axes[axisIndex].keys.size
			if (keyCount <= 0) {
				continue
			}
			coordinate[axisIndex] = remaining % keyCount
			remaining /= keyCount
		}
		return coordinate
	}

	/**
	 * A dense grid over the given axis key counts, each cell carrying its own linear index as its
	 * payload so a re-shape can be traced by value.
	 */
	private fun denseGrid(vararg keyCounts: Int): KeyformGrid<GlueForm> {
		val parameterIds = listOf(angleX, angleY, angleZ)
		val axes =
			keyCounts.mapIndexed { axisIndex, keyCount ->
				KeyformAxis(parameterIds[axisIndex], FloatArray(keyCount) { keyIndex -> keyIndex.toFloat() })
			}
		val grid = KeyformGrid(axes, emptyList<KeyformCell<GlueForm>>())
		val cells =
			(0 until grid.cellCount).map { linearIndex ->
				KeyformCell(grid.coordinateOf(linearIndex), GlueForm(linearIndex.toFloat()))
			}
		return KeyformGrid(axes, cells)
	}

	/** cellCount is the product of the axis key counts, and 1 for an axis-less grid. */
	@Test
	fun cellCountIsTheAxisProduct() {
		assertEquals(6, denseGrid(3, 2).cellCount)
		assertEquals(24, denseGrid(4, 3, 2).cellCount)
		assertEquals(1, KeyformGrid(emptyList(), listOf(KeyformCell(intArrayOf(), GlueForm(1f)))).cellCount)
	}

	/**
	 * The stride folding matches the evaluator's cellsByLinearIndex exactly. Both now share
	 * KeyformGrid's own members, so this pins the contract rather than guarding a duplicate.
	 */
	@Test
	fun linearIndexMatchesTheEvaluatorFolding() {
		for (grid in listOf(denseGrid(3, 2), denseGrid(4, 3, 2), denseGrid(5))) {
			val evaluatorIndexed = cellsByLinearIndex(grid)
			for (cell in grid.cells) {
				val shapeIndex = grid.linearIndexOf(cell.coordinate)
				assertSame(cell, evaluatorIndexed[shapeIndex], "cell ${cell.coordinate.toList()} folds to the same index")
			}
		}
	}

	/** coordinateOf inverts linearIndexOf for every cell in the grid's shape. */
	@Test
	fun coordinateRoundTripsThroughLinearIndex() {
		val grid = denseGrid(4, 3, 2)
		for (linearIndex in 0 until grid.cellCount) {
			assertEquals(linearIndex, grid.linearIndexOf(grid.coordinateOf(linearIndex)))
		}
	}

	/** Axis 0 varies fastest, matching the evaluator's stride accumulation order. */
	@Test
	fun axisZeroVariesFastest() {
		val grid = denseGrid(3, 2)
		assertEquals(listOf(1, 3), grid.strides().toList())
		assertEquals(listOf(1, 0), grid.coordinateOf(1).toList(), "the second cell steps along axis 0")
	}

	/** A grid with one cell per coordinate is dense; a grid missing a cell is not. */
	@Test
	fun densityDetectsAMissingCell() {
		val dense = denseGrid(3, 2)
		assertTrue(dense.isDense)
		val sparse = KeyformGrid(dense.axes, dense.cells.drop(1))
		assertFalse(sparse.isDense, "a dropped cell makes the grid sparse")
		val duplicated = KeyformGrid(dense.axes, dense.cells.dropLast(1) + dense.cells.first())
		assertFalse(duplicated.isDense, "a duplicated coordinate makes the grid sparse")
	}

	/** axisIndexOf finds an axis by parameter and reports -1 when the grid has none. */
	@Test
	fun axisIndexOfLocatesTheAxis() {
		val grid = denseGrid(3, 2)
		assertEquals(0, grid.axisIndexOf(angleX))
		assertEquals(1, grid.axisIndexOf(angleY))
		assertEquals(-1, grid.axisIndexOf(angleZ))
	}

	/** withAxisCollapsed drops the named axis and re-projects each surviving cell's coordinate. */
	@Test
	fun withAxisCollapsedReprojectsCoordinates() {
		val axes = listOf(KeyformAxis(angleX, floatArrayOf(0f, 1f)), KeyformAxis(angleY, floatArrayOf(0f, 1f)))
		val cells =
			listOf(
				KeyformCell(intArrayOf(0, 0), GlueForm(0f)),
				KeyformCell(intArrayOf(0, 1), GlueForm(1f)),
				KeyformCell(intArrayOf(1, 0), GlueForm(2f)),
				KeyformCell(intArrayOf(1, 1), GlueForm(3f)),
			)
		// Collapse angleX keeping the value-0 slice (key index 0): surviving cells are (0,0) and (0,1).
		val collapsed = KeyformGrid(axes, cells).withAxisCollapsed(angleX, keepKeyValue = 0f)!!
		assertEquals(listOf(angleY), collapsed.axes.map { axis -> axis.parameterId })
		assertEquals(listOf(listOf(0), listOf(1)), collapsed.cells.map { cell -> cell.coordinate.toList() })
		assertEquals(listOf(0f, 1f), collapsed.cells.map { cell -> cell.form.intensity })
	}

	/** The kept slice is the NEAREST key, so a value between keys snaps rather than interpolating. */
	@Test
	fun withAxisCollapsedSnapsToTheNearestKey() {
		val grid = denseGrid(3, 2)
		// angleX keys are 0, 1, 2; 1.6 is nearest key index 2, whose cells are linear indices 2 and 5.
		val collapsed = grid.withAxisCollapsed(angleX, keepKeyValue = 1.6f)!!
		assertEquals(listOf(2f, 5f), collapsed.cells.map { cell -> cell.form.intensity })
	}

	/** withAxisCollapsed returns the same instance when the grid has no such axis. */
	@Test
	fun withAxisCollapsedAbsentAxisReturnsSame() {
		val grid = denseGrid(3, 2)
		assertSame(grid, grid.withAxisCollapsed(angleZ, keepKeyValue = 0f))
	}

	/** Collapsing the only axis leaves nothing to key on, so the grid becomes null (the entity unkeyed). */
	@Test
	fun withAxisCollapsedSoleAxisBecomesNull() {
		assertNull(denseGrid(3).withAxisCollapsed(angleX, keepKeyValue = 0f))
	}
}