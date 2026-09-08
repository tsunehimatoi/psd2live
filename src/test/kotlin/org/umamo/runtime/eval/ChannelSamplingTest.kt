package org.umamo.runtime.eval

import org.umamo.runtime.model.ChannelGrids
import org.umamo.runtime.model.ChannelValue
import org.umamo.runtime.model.ColorRgb
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.ParameterId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the per-channel pose sampling: the static fallback (untracked, or out of range - which NEVER
 * hides), the override precedence (a pending unkeyed edit wins over both track and static), the
 * flag channel's floor-cell snap, and allAxes' flatten across every track.
 */
class ChannelSamplingTest {
	private val angleX = ParameterId("ParamAngleX")
	private val angleY = ParameterId("ParamAngleY")

	/** A one-axis track on the given parameter, each cell holding the matching value. */
	private fun track(parameterId: ParameterId, keys: FloatArray, values: List<ChannelValue>): KeyformGrid<ChannelValue> =
		KeyformGrid(
			listOf(KeyformAxis(parameterId, keys)),
			values.mapIndexed { keyIndex, value -> KeyformCell(intArrayOf(keyIndex), value) },
		)

	/** A pose lambda reading angleX only. */
	private fun poseX(value: Float): (ParameterId) -> Float = { parameterId -> if (parameterId == angleX) value else 0f }

	private fun scalarTrack(vararg values: Float): KeyformGrid<ChannelValue> =
		track(angleX, FloatArray(values.size) { keyIndex -> keyIndex.toFloat() }, values.map { value -> ChannelValue.Scalar(value) })

	/** An untracked channel reads the static, and a tracked one blends its own grid at the pose. */
	@Test
	fun scalarAtFallsBackToStaticAndBlendsWhenTracked() {
		val grids = ChannelGrids(mapOf(FormChannel.OPACITY to scalarTrack(0f, 1f)))
		assertEquals(0.25f, grids.scalarAt(FormChannel.DRAW_ORDER, 0.25f, poseX(0.5f)), "untracked channel reads the static")
		assertEquals(1f, grids.scalarAt(FormChannel.OPACITY, 0.25f, poseX(1f)), "on-key pose reads the cell")
		assertEquals(0.5f, grids.scalarAt(FormChannel.OPACITY, 0.25f, poseX(0.5f)), "between keys blends")
	}

	/** Out of the track's range falls back to the static - keying opacity never hides the art. */
	@Test
	fun scalarAtOutOfRangeFallsBackToStatic() {
		val grids = ChannelGrids(mapOf(FormChannel.OPACITY to scalarTrack(0f, 1f)))
		assertEquals(0.25f, grids.scalarAt(FormChannel.OPACITY, 0.25f, poseX(5f)))
	}

	/** A pending scalar override wins over both the track and the static. */
	@Test
	fun scalarAtOverrideWinsOverTrackAndStatic() {
		val grids = ChannelGrids(mapOf(FormChannel.OPACITY to scalarTrack(0f, 1f)))
		val override = ChannelValue.Scalar(0.7f)
		assertEquals(0.7f, grids.scalarAt(FormChannel.OPACITY, 0.25f, poseX(1f), override), "override beats the track")
		assertEquals(0.7f, grids.scalarAt(FormChannel.DRAW_ORDER, 0.25f, poseX(1f), override), "override beats the static")
	}

	/** Color sampling mirrors the scalar precedence, blending per component. */
	@Test
	fun colorAtBlendsPerComponentWithSamePrecedence() {
		val colorTrack =
			track(
				angleX,
				floatArrayOf(0f, 1f),
				listOf(ChannelValue.Color(ColorRgb(0f, 0f, 1f)), ChannelValue.Color(ColorRgb(1f, 0f, 0f))),
			)
		val grids = ChannelGrids(mapOf(FormChannel.MULTIPLY_COLOR to colorTrack))
		val staticColor = ColorRgb(0.2f, 0.4f, 0.6f)
		assertEquals(staticColor, grids.colorAt(FormChannel.SCREEN_COLOR, staticColor, poseX(0.5f)), "untracked channel reads the static")
		assertEquals(ColorRgb(0.5f, 0f, 0.5f), grids.colorAt(FormChannel.MULTIPLY_COLOR, staticColor, poseX(0.5f)))
		assertEquals(staticColor, grids.colorAt(FormChannel.MULTIPLY_COLOR, staticColor, poseX(5f)), "out of range falls back")
		val override = ChannelValue.Color(ColorRgb(0.9f, 0.9f, 0.9f))
		assertEquals(override.color, grids.colorAt(FormChannel.MULTIPLY_COLOR, staticColor, poseX(0.5f), override))
	}

	/** A flag snaps to the FLOOR cell between keys rather than blending a boolean. */
	@Test
	fun flagAtSnapsToTheFloorCell() {
		val flagTrack = track(angleX, floatArrayOf(0f, 1f), listOf(ChannelValue.Flag(false), ChannelValue.Flag(true)))
		val grids = ChannelGrids(mapOf(FormChannel.FLIP_X to flagTrack))
		assertFalse(grids.flagAt(FormChannel.FLIP_X, true, poseX(0.5f)), "midway reads the lower key's flag")
		assertTrue(grids.flagAt(FormChannel.FLIP_X, false, poseX(1f)), "on the upper key reads it exactly")
		assertTrue(grids.flagAt(FormChannel.FLIP_Y, true, poseX(0.5f)), "untracked channel reads the static")
		assertTrue(grids.flagAt(FormChannel.FLIP_X, true, poseX(5f)), "out of range falls back")
		assertTrue(grids.flagAt(FormChannel.FLIP_X, false, poseX(0.5f), ChannelValue.Flag(true)), "override wins")
	}

	/** scalarOrNull reports absence itself: untracked and out-of-range are null, override still wins. */
	@Test
	fun scalarOrNullDistinguishesAbsenceFromValue() {
		val grids = ChannelGrids(mapOf(FormChannel.OPACITY to scalarTrack(0f, 1f)))
		assertNull(grids.scalarOrNull(FormChannel.DRAW_ORDER, poseX(0.5f)), "untracked channel is absent")
		assertNull(grids.scalarOrNull(FormChannel.OPACITY, poseX(5f)), "out of range is absent")
		assertEquals(0.5f, grids.scalarOrNull(FormChannel.OPACITY, poseX(0.5f)))
		assertEquals(0.7f, grids.scalarOrNull(FormChannel.DRAW_ORDER, poseX(0.5f), ChannelValue.Scalar(0.7f)))
	}

	/**
	 * allAxes is a FLATTEN in channel order, not a dedupe: two tracks keying the same parameter each
	 * contribute their own axis, because each track brackets its own keys.
	 */
	@Test
	fun allAxesFlattensEveryTrackAxisInChannelOrder() {
		val opacityTrack = scalarTrack(0f, 1f)
		val drawOrderTrack = track(angleY, floatArrayOf(-1f, 1f), listOf(ChannelValue.Scalar(400f), ChannelValue.Scalar(600f)))
		val glueTrack = track(angleX, floatArrayOf(0f, 2f), listOf(ChannelValue.Scalar(0f), ChannelValue.Scalar(1f)))
		val grids =
			ChannelGrids(
				linkedMapOf(
					FormChannel.OPACITY to opacityTrack,
					FormChannel.DRAW_ORDER to drawOrderTrack,
					FormChannel.GLUE_INTENSITY to glueTrack,
				),
			)
		val axes = grids.allAxes()
		assertEquals(listOf(angleX, angleY, angleX), axes.map { axis -> axis.parameterId })
		assertEquals(emptyList(), ChannelGrids.Empty.allAxes())
	}
}