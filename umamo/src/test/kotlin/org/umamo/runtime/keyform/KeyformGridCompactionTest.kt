package org.umamo.runtime.keyform

import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.function.ThrowingSupplier
import org.umamo.runtime.eval.gridCorners
import org.umamo.runtime.model.ChannelValue
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.ParameterId
import java.time.Duration
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Interior-key compaction stays the exact inverse of refinement and finishes in time linear in the grid.
 */
class KeyformGridCompactionTest {
	private val angleX = ParameterId("ParamAngleX")
	private val angleY = ParameterId("ParamAngleY")

	/** A dense scalar grid over [axes] whose value at each coordinate is [value]. */
	private fun grid(axes: List<KeyformAxis>, value: (IntArray) -> Float): KeyformGrid<ChannelValue> {
		val cells = ArrayList<KeyformCell<ChannelValue>>()
		val coordinate = IntArray(axes.size)
		while (true) {
			cells += KeyformCell(coordinate.copyOf(), ChannelValue.Scalar(value(coordinate)))
			var axisIndex = 0
			while (axisIndex < axes.size && ++coordinate[axisIndex] == axes[axisIndex].keys.size) {
				coordinate[axisIndex] = 0
				axisIndex++
			}
			if (axisIndex == axes.size) {
				return KeyformGrid(axes, cells)
			}
		}
	}

	/** Asserts that refining [reduced] back onto [original]'s keys restores every cell bit-for-bit. */
	private fun assertRefinesBack(original: KeyformGrid<ChannelValue>, reduced: KeyformGrid<ChannelValue>) {
		val restored = reduced.refinedToUnion(original.axes.associate { axis -> axis.parameterId to axis.keys }, emptyMap(), ChannelValueInterpolator)
		assertEquals(original.cells.size, restored.cells.size)
		for (cell in original.cells) {
			val counterpart = assertNotNull(restored.cellsByLinearIndex[original.linearIndexOf(cell.coordinate)])
			assertEquals(cell.form, counterpart.form)
		}
	}

	/**
	 * Refinement does not re-insert a key closer than EPS_KEY to a neighbour, so dropping one leaves the
	 * restored grid a key short.  Verification once compared such a grid by linear index, blamed only kept
	 * keys and looped forever; the key must simply stay.
	 */
	@Test
	fun aKeyTooCloseToItsNeighbourToRestoreIsKept() {
		// Constant on an axis that does not span its range, so the axis stays and only interior keys can go.
		val axis = KeyformAxis(angleX, floatArrayOf(0.5f, 2f, 2.999f, 3f, 4f, 4.999f, 5f, 8f))
		val original = grid(listOf(axis)) { 810f }
		val result =
			assertTimeoutPreemptively(Duration.ofSeconds(5), ThrowingSupplier {
				original.compacted(ChannelValueInterpolator, axisSpansRange = { false })
			})
		val reduced = assertIs<CompactionResult.Reduced<ChannelValue>>(result).grid
		assertRefinesBack(original, reduced)
		assertTrue(reduced.axes.single().keys.size < axis.keys.size, "the keys refinement can restore still drop")
	}

	/** Bends and plateaus over two axes: every key that drops refines back exactly. */
	@Test
	fun droppedKeysRefineBackBitExact() {
		val axes =
			listOf(
				KeyformAxis(angleX, FloatArray(40) { keyIndex -> keyIndex * 0.75f }),
				KeyformAxis(angleY, FloatArray(30) { keyIndex -> keyIndex * 1.5f - 20f }),
			)
		val original = grid(axes) { coordinate -> (coordinate[0] / 7) * 3.1f + (coordinate[1] / 4) * 0.37f + if (coordinate[0] % 11 == 5) 0.01f else 0f }
		val reduced = assertIs<CompactionResult.Reduced<ChannelValue>>(original.compacted(ChannelValueInterpolator)).grid
		assertRefinesBack(original, reduced)
		assertTrue(reduced.cells.size < original.cells.size, "plateaus compact")
	}

	/**
	 * A key on a slope stays even when it is exactly the blend of its neighbours: a pose within EPS_KEY of it
	 * snaps onto it, which a blend across the gap would not.
	 */
	@Test
	fun aKeyOnASlopeStays() {
		val axis = KeyformAxis(angleX, floatArrayOf(0f, 0.15f, 0.25f, 0.5f, 1f))
		val original = grid(listOf(axis)) { coordinate -> floatArrayOf(3f, 4.5f, 6f, 6f, 6f)[coordinate[0]] }
		val reduced = assertIs<CompactionResult.Reduced<ChannelValue>>(original.compacted(ChannelValueInterpolator)).grid
		assertEquals(listOf(0f, 0.15f, 0.25f, 1f), reduced.axes.single().keys.toList(), "only the plateau key drops")
		val nearKey = mapOf(angleX to 0.1505f)
		assertEquals(original.valueAt(nearKey), reduced.valueAt(nearKey))
	}

	/**
	 * A grid far larger than any rig carries.  Copying the grid and rebuilding a lookup per candidate key
	 * made this quadratic - minutes - where a linear pass takes milliseconds.
	 */
	@Test
	fun aLargeGridCompactsInLinearTime() {
		val axes =
			listOf(
				KeyformAxis(angleX, FloatArray(20_000) { keyIndex -> keyIndex.toFloat() }),
				KeyformAxis(angleY, FloatArray(3) { keyIndex -> keyIndex.toFloat() }),
			)
		// Zero runs, which blend back exactly, between spikes every 50 keys: three keys in each 50 must stay.
		val original = grid(axes) { coordinate -> if (coordinate[0] % 50 == 0) 1f + coordinate[1] else 0f }
		val result = assertTimeoutPreemptively(Duration.ofSeconds(5), ThrowingSupplier { original.compacted(ChannelValueInterpolator) })
		val reduced = assertIs<CompactionResult.Reduced<ChannelValue>>(result).grid
		assertTrue(reduced.axes[0].keys.size < axes[0].keys.size / 10, "plateaus compact")
		assertEquals(reduced.axes.fold(1) { count, axis -> count * axis.keys.size }, reduced.cells.size)
	}

	/** The track's multilinear value at [pose]. */
	private fun KeyformGrid<ChannelValue>.valueAt(pose: Map<ParameterId, Float>): Float {
		val corners = assertNotNull(gridCorners(this) { parameterId -> pose.getValue(parameterId) })
		return corners.sumOf { corner -> (cellsByLinearIndex.getValue(corner.linearIndex).form as ChannelValue.Scalar).value.toDouble() * corner.weight }.toFloat()
	}

	/**
	 * Cubism Core blends every fractional axis.  A 16-corner cap once snapped the fifth one to its lower key,
	 * so dropping four constant axes let a draw-order track that snapped at 400 blend to about 442 instead.
	 */
	@Test
	fun droppingConstantAxesKeepsTheValueWithFiveFractionalAxes() {
		val parameters = List(5) { axisIndex -> ParameterId("Param$axisIndex") }
		val axes =
			listOf(
				KeyformAxis(parameters[0], floatArrayOf(-1f, 0f, 1f)),
				KeyformAxis(parameters[1], floatArrayOf(-1f, 0f, 1f)),
				KeyformAxis(parameters[2], floatArrayOf(0f, 1f)),
				KeyformAxis(parameters[3], floatArrayOf(0f, 1f, 2f, 3f)),
				KeyformAxis(parameters[4], floatArrayOf(0f, 1f)),
			)
		val pose = parameters.zip(listOf(0.3f, -0.4f, 0.45f, 1.55f, 0.85f)).toMap()
		val original = grid(axes) { coordinate -> if (coordinate[4] == 0) 400f else 450f }
		val reduced = assertIs<CompactionResult.Reduced<ChannelValue>>(original.compacted(ChannelValueInterpolator)).grid
		assertEquals(listOf(parameters[4]), reduced.axes.map { axis -> axis.parameterId })
		assertEquals(442.5f, original.valueAt(pose), 1e-3f)
		assertEquals(original.valueAt(pose), reduced.valueAt(pose), 1e-4f)
		// Varying along every axis, all five blend: the value is the sum of each axis's own interpolation.
		val separable = grid(axes) { coordinate -> coordinate.indices.sumOf { axisIndex -> axes[axisIndex].keys[coordinate[axisIndex]].toDouble() * (axisIndex + 1) }.toFloat() }
		val expected = pose.values.withIndex().sumOf { (axisIndex, value) -> value.toDouble() * (axisIndex + 1) }.toFloat()
		assertTrue(abs(separable.valueAt(pose) - expected) < 1e-4f, "every fractional axis blends")
	}
}
