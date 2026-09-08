package org.umamo.runtime.eval

import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshDeltaForm
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.RotationPivotForm
import org.umamo.runtime.model.WarpLatticeForm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins the pure grid-sampling layer: bindBracket's range/snap contract, gridCorners' multilinear
 * corner selection (including the 16-corner budget), and the pose-sampling helpers the renderer's
 * evaluator and the MOC3 import both call (meshGridDefaultDeltas, warpControlPointsAt,
 * rotationFormAt).
 *
 * The grid ALGEBRA (seeding, insertion, compaction) is pinned in runtime.keyform; this file pins the
 * read side that every other feature blends through.
 */
class KeyformGridSamplingTest {
	private val angleX = ParameterId("ParamAngleX")
	private val angleY = ParameterId("ParamAngleY")

	/** A pose lambda over explicit per-parameter values, defaulting unmentioned parameters to zero. */
	private fun pose(vararg values: Pair<ParameterId, Float>): (ParameterId) -> Float {
		val byId = values.toMap()
		return { parameterId -> byId[parameterId] ?: 0f }
	}

	/** An axis-only grid (gridCorners reads no cells), each axis holding keys 0..count-1. */
	private fun axisOnlyGrid(vararg axes: Pair<ParameterId, FloatArray>): KeyformGrid<WarpLatticeForm> =
		KeyformGrid(axes.map { (parameterId, keys) -> KeyformAxis(parameterId, keys) }, emptyList())

	/** A value inside the bracket snaps by fraction; assertEquals on floats pins it exactly. */
	@Test
	fun bindBracketBetweenKeysReturnsLowerIndexAndFraction() {
		val bracket = assertNotNull(bindBracket(floatArrayOf(0f, 1f, 2f), 0.25f))
		assertEquals(0, bracket.index)
		assertEquals(0.25f, bracket.fraction)
	}

	/** A value landing exactly on an interior key brackets that key with zero fraction. */
	@Test
	fun bindBracketExactKeyHitSnapsWithZeroFraction() {
		assertEquals(AxisBracket(1, 0f), bindBracket(floatArrayOf(0f, 1f, 2f), 1f))
	}

	/** Out of range hides the entity: below the first key and at/above last-key + EPS_KEY are null. */
	@Test
	fun bindBracketOutOfRangeReturnsNull() {
		val keys = floatArrayOf(0f, 1f, 2f)
		assertNull(bindBracket(keys, -0.5f))
		assertNull(bindBracket(keys, 2.5f))
		assertNull(bindBracket(keys, 2f + EPS_KEY))
	}

	/** Within EPS_KEY of a key the value snaps onto it; just outside it interpolates. */
	@Test
	fun bindBracketSnapsOnlyWithinEpsilonOfAKey() {
		val keys = floatArrayOf(0f, 1f, 2f)
		assertEquals(AxisBracket(1, 0f), bindBracket(keys, 1f + 0.0005f), "inside EPS_KEY snaps")
		val outside = assertNotNull(bindBracket(keys, 1f + 0.002f))
		assertEquals(1, outside.index)
		assertTrue(outside.fraction > 0f, "outside EPS_KEY interpolates")
	}

	/** The snap window extends past the LAST key too, so a slider resting a hair over stays keyed. */
	@Test
	fun bindBracketSnapsJustAboveTheLastKey() {
		assertEquals(AxisBracket(2, 0f), bindBracket(floatArrayOf(0f, 1f, 2f), 2.0005f))
	}

	/** A single-key axis brackets only within its snap window and hides everywhere else. */
	@Test
	fun bindBracketSingleKeyAxisSnapsOrHides() {
		assertEquals(AxisBracket(0, 0f), bindBracket(floatArrayOf(0.5f), 0.5f))
		assertNull(bindBracket(floatArrayOf(0.5f), 0.6f))
	}

	/** An axis-less (empty-keys) bracket is the zero bracket - the entity never hides on it. */
	@Test
	fun bindBracketEmptyKeysReturnsZeroBracket() {
		assertEquals(AxisBracket(0, 0f), bindBracket(floatArrayOf(), 12f))
	}

	/** One fractional axis doubles the corner set; the two weights are the complementary fractions. */
	@Test
	fun gridCornersOneFractionalAxisYieldsTwoWeightedCells() {
		val grid = axisOnlyGrid(angleX to floatArrayOf(0f, 1f))
		val corners = assertNotNull(gridCorners(grid, pose(angleX to 0.25f)))
		assertEquals(mapOf(0 to 0.75f, 1 to 0.25f), corners.associate { corner -> corner.linearIndex to corner.weight })
	}

	/** Two fractional axes fold to four bilinear corners whose weights multiply and sum to one. */
	@Test
	fun gridCornersTwoFractionalAxesYieldFourBilinearCorners() {
		val grid = axisOnlyGrid(angleX to floatArrayOf(0f, 1f), angleY to floatArrayOf(0f, 1f))
		val corners = assertNotNull(gridCorners(grid, pose(angleX to 0.5f, angleY to 0.25f)))
		val weightByIndex = corners.associate { corner -> corner.linearIndex to corner.weight }
		assertEquals(mapOf(0 to 0.375f, 1 to 0.375f, 2 to 0.125f, 3 to 0.125f), weightByIndex)
		assertEquals(1f, corners.map { corner -> corner.weight }.sum())
	}

	/** A pose exactly on every key resolves to the single matching cell at full weight. */
	@Test
	fun gridCornersOnKeyPoseYieldsOneFullWeightCell() {
		val grid = axisOnlyGrid(angleX to floatArrayOf(0f, 1f), angleY to floatArrayOf(0f, 1f))
		val corners = assertNotNull(gridCorners(grid, pose(angleX to 1f, angleY to 0f)))
		assertEquals(listOf(WeightedCell(1, 1f)), corners)
	}

	/** Any single controlling axis out of range hides the whole entity. */
	@Test
	fun gridCornersOutOfRangeAxisReturnsNull() {
		val grid = axisOnlyGrid(angleX to floatArrayOf(0f, 1f), angleY to floatArrayOf(0f, 1f))
		assertNull(gridCorners(grid, pose(angleX to 0.5f, angleY to 2f)))
	}

	/**
	 * Past the 16-corner budget an axis snaps to its lower key instead of splitting: five fractional
	 * two-key axes yield 16 corners (four split), with the fifth axis pinned at key 0 - every linear
	 * index stays below the fifth axis's stride.
	 */
	@Test
	fun gridCornersFifthFractionalAxisSnapsWithinCornerBudget() {
		val parameterIds = (1..5).map { axisNumber -> ParameterId("ParamAxis$axisNumber") }
		val grid =
			KeyformGrid<WarpLatticeForm>(
				parameterIds.map { parameterId -> KeyformAxis(parameterId, floatArrayOf(0f, 1f)) },
				emptyList(),
			)
		val corners = assertNotNull(gridCorners(grid) { 0.5f })
		assertEquals(16, corners.size)
		val fifthAxisStride = 16
		assertTrue(corners.all { corner -> corner.linearIndex < fifthAxisStride }, "the snapped axis contributes only its lower key")
		assertEquals(1f, corners.map { corner -> corner.weight }.sum())
	}

	/** The exposed cell index is the grid's own cached one, and it round-trips every coordinate. */
	@Test
	fun cellsByLinearIndexDelegatesToTheGridAndRoundTripsCoordinates() {
		val cells =
			listOf(
				KeyformCell(intArrayOf(0, 0), WarpLatticeForm(floatArrayOf(0f))),
				KeyformCell(intArrayOf(1, 0), WarpLatticeForm(floatArrayOf(1f))),
				KeyformCell(intArrayOf(2, 0), WarpLatticeForm(floatArrayOf(2f))),
				KeyformCell(intArrayOf(0, 1), WarpLatticeForm(floatArrayOf(3f))),
				KeyformCell(intArrayOf(1, 1), WarpLatticeForm(floatArrayOf(4f))),
				KeyformCell(intArrayOf(2, 1), WarpLatticeForm(floatArrayOf(5f))),
			)
		val grid =
			KeyformGrid(
				listOf(KeyformAxis(angleX, floatArrayOf(0f, 1f, 2f)), KeyformAxis(angleY, floatArrayOf(0f, 1f))),
				cells,
			)
		val byIndex = cellsByLinearIndex(grid)
		assertSame(grid.cellsByLinearIndex, byIndex)
		for (cell in cells) {
			assertSame(cell, byIndex[grid.linearIndexOf(cell.coordinate)])
		}
	}

	/** A drawable with the given one-axis geometry grid on angleX; the mesh itself is never read. */
	private fun gridded(grid: KeyformGrid<MeshDeltaForm>?): Drawable =
		Drawable(
			id = DrawableId("d1"),
			name = "d1",
			parentDeformerId = null,
			blendMode = BlendMode.Normal,
			maskedBy = emptyList(),
			mesh = null,
			geometryGrid = grid,
		)

	/** An ungridded drawable has no delta reference. */
	@Test
	fun meshGridDefaultDeltasUngriddedDrawableReturnsNull() {
		assertNull(meshGridDefaultDeltas(gridded(null)) { 0f })
	}

	/** A default pose outside the grid's range has no reference either (the reference is then zero). */
	@Test
	fun meshGridDefaultDeltasOutOfRangeDefaultReturnsNull() {
		val grid =
			KeyformGrid(
				listOf(KeyformAxis(angleX, floatArrayOf(0f, 1f))),
				listOf(
					KeyformCell(intArrayOf(0), MeshDeltaForm(floatArrayOf(0f, 0f))),
					KeyformCell(intArrayOf(1), MeshDeltaForm(floatArrayOf(2f, 4f))),
				),
			)
		assertNull(meshGridDefaultDeltas(gridded(grid)) { 5f })
	}

	/** The reference is the grid form blended at the default pose, sized like a cell's delta array. */
	@Test
	fun meshGridDefaultDeltasBlendsAtTheDefaultPose() {
		val grid =
			KeyformGrid(
				listOf(KeyformAxis(angleX, floatArrayOf(0f, 1f))),
				listOf(
					KeyformCell(intArrayOf(0), MeshDeltaForm(floatArrayOf(0f, 0f))),
					KeyformCell(intArrayOf(1), MeshDeltaForm(floatArrayOf(2f, 4f))),
				),
			)
		val deltas = assertNotNull(meshGridDefaultDeltas(gridded(grid)) { 0.5f })
		assertEquals(listOf(1f, 2f), deltas.toList())
	}

	/** A one-axis warp grid with the given control points per key. */
	private fun warpGrid(vararg controlPointsPerKey: FloatArray): KeyformGrid<WarpLatticeForm> =
		KeyformGrid(
			listOf(KeyformAxis(angleX, FloatArray(controlPointsPerKey.size) { keyIndex -> keyIndex.toFloat() })),
			controlPointsPerKey.mapIndexed { keyIndex, points -> KeyformCell(intArrayOf(keyIndex), WarpLatticeForm(points)) },
		)

	/** An unkeyed warp has no lattice at all. */
	@Test
	fun warpControlPointsAtNullGridReturnsNull() {
		assertNull(warpControlPointsAt(null, pose()))
	}

	/** On a key the lattice is that cell's points exactly; between keys it blends componentwise. */
	@Test
	fun warpControlPointsAtBlendsBetweenKeys() {
		val grid = warpGrid(floatArrayOf(0f, 10f), floatArrayOf(4f, 30f))
		assertEquals(listOf(0f, 10f), assertNotNull(warpControlPointsAt(grid, pose(angleX to 0f))).toList())
		assertEquals(listOf(2f, 20f), assertNotNull(warpControlPointsAt(grid, pose(angleX to 0.5f))).toList())
	}

	/** Out of range or no resolvable cell both yield null rather than a partial lattice. */
	@Test
	fun warpControlPointsAtOutOfRangeOrCellLessReturnsNull() {
		val grid = warpGrid(floatArrayOf(0f), floatArrayOf(4f))
		assertNull(warpControlPointsAt(grid, pose(angleX to 9f)))
		val cellLess = KeyformGrid<WarpLatticeForm>(listOf(KeyformAxis(angleX, floatArrayOf(0f, 1f))), emptyList())
		assertNull(warpControlPointsAt(cellLess, pose(angleX to 0.5f)))
	}

	/** A one-axis rotation grid with the given pivot forms per key. */
	private fun rotationGrid(vararg formsPerKey: RotationPivotForm): KeyformGrid<RotationPivotForm> =
		KeyformGrid(
			listOf(KeyformAxis(angleX, FloatArray(formsPerKey.size) { keyIndex -> keyIndex.toFloat() })),
			formsPerKey.mapIndexed { keyIndex, form -> KeyformCell(intArrayOf(keyIndex), form) },
		)

	/** An unkeyed rotation has no transform. */
	@Test
	fun rotationFormAtNullGridReturnsNull() {
		assertNull(rotationFormAt(null, pose()))
	}

	/** On a key the transform is that cell's exactly; between keys every component lerps. */
	@Test
	fun rotationFormAtBlendsBetweenKeys() {
		val grid =
			rotationGrid(
				RotationPivotForm(originX = 0f, originY = 10f, angle = 0f, scale = 1f),
				RotationPivotForm(originX = 4f, originY = 30f, angle = 90f, scale = 3f),
			)
		val atKey = assertNotNull(rotationFormAt(grid, pose(angleX to 1f)))
		assertEquals(4f, atKey.originX)
		assertEquals(90f, atKey.angle)
		val midway = assertNotNull(rotationFormAt(grid, pose(angleX to 0.5f)))
		assertEquals(2f, midway.originX)
		assertEquals(20f, midway.originY)
		assertEquals(45f, midway.angle)
		assertEquals(2f, midway.scale)
	}

	/** Out of range or no resolvable cell both yield null rather than an identity transform. */
	@Test
	fun rotationFormAtOutOfRangeOrCellLessReturnsNull() {
		val grid = rotationGrid(RotationPivotForm(0f, 0f, 0f, 1f))
		assertNull(rotationFormAt(grid, pose(angleX to 9f)))
		val cellLess = KeyformGrid<RotationPivotForm>(listOf(KeyformAxis(angleX, floatArrayOf(0f, 1f))), emptyList())
		assertNull(rotationFormAt(cellLess, pose(angleX to 0.5f)))
	}
}