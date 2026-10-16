package org.umamo.runtime.keyform

import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.function.ThrowingSupplier
import org.umamo.runtime.model.ChannelValue
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.ParameterId
import java.time.Duration
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
}
