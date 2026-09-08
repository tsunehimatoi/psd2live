package org.umamo.runtime.keyform

import org.umamo.runtime.model.ChannelGrids
import org.umamo.runtime.model.ChannelValue
import org.umamo.runtime.model.ChannelValueKind
import org.umamo.runtime.model.ColorRgb
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.Glue
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins exact-only compaction and, most importantly, that compaction and refinement are MUTUAL INVERSES
 * bit-for-bit.
 *
 * That property is the whole basis for compacting an imported rig: a key is dropped only when the stored
 * form is bit-equal to the blend of its neighbours, and refinement restores it by evaluating the very same
 * FormInterpolator expression on the very same inputs, so IEEE-754 determinism does the rest.  It is a
 * theorem about shared code, not about tolerance - which is why this file asserts equality exactly, while
 * KeyformGridAlgebraTest's arbitrary key insertion may only assert to a bound.
 */
class KeyformCompactionTest {
	private val angleX = ParameterId("ParamAngleX")
	private val angleY = ParameterId("ParamAngleY")

	private fun scalar(value: Float): ChannelValue = ChannelValue.Scalar(value)

	/** A one-axis scalar track on angleX. */
	private fun track(keys: FloatArray, values: FloatArray): KeyformGrid<ChannelValue> =
		KeyformGrid(
			listOf(KeyformAxis(angleX, keys)),
			values.mapIndexed { keyIndex, value -> KeyformCell(intArrayOf(keyIndex), scalar(value)) },
		)

	/** Every cell's value in linear-index order, so two grids of the same shape compare directly. */
	private fun valuesInIndexOrder(grid: KeyformGrid<ChannelValue>): List<ChannelValue> =
		(0 until grid.cellCount).map { linearIndex ->
			grid.cells.first { cell -> grid.linearIndexOf(cell.coordinate) == linearIndex }.form
		}

	/** A track holding one value everywhere collapses to that constant, freeing its owner's static field. */
	@Test
	fun aConstantTrackCollapsesToItsValue() {
		val result = track(floatArrayOf(-1f, 0f, 1f), floatArrayOf(7f, 7f, 7f)).compacted(ChannelValueInterpolator)
		assertEquals(scalar(7f), assertIs<CompactionResult.Constant<ChannelValue>>(result).form)
	}

	/** A perfectly linear track keeps only its endpoints - the interior keys carry no information. */
	@Test
	fun aLinearTrackKeepsOnlyItsEndpoints() {
		val result = track(floatArrayOf(0f, 5f, 10f), floatArrayOf(0f, 50f, 100f)).compacted(ChannelValueInterpolator)
		val reduced = assertIs<CompactionResult.Reduced<ChannelValue>>(result).grid
		assertEquals(listOf(0f, 10f), reduced.axes.single().keys.toList())
	}

	/** A key that genuinely bends the curve survives, however slightly it differs from the blend. */
	@Test
	fun aBendingKeySurvives() {
		val result = track(floatArrayOf(0f, 5f, 10f), floatArrayOf(0f, 50.0001f, 100f)).compacted(ChannelValueInterpolator)
		val reduced = assertIs<CompactionResult.Reduced<ChannelValue>>(result).grid
		assertEquals(listOf(0f, 5f, 10f), reduced.axes.single().keys.toList(), "an exact-only test never rounds a rigger's value away")
	}

	/** An axis the track does not respond to is dropped entirely, even when another axis still varies. */
	@Test
	fun aConstantAxisIsDropped() {
		val axes = listOf(KeyformAxis(angleX, floatArrayOf(0f, 10f)), KeyformAxis(angleY, floatArrayOf(0f, 1f)))
		// Varies along angleX, identical along angleY.
		val cells =
			listOf(
				KeyformCell(intArrayOf(0, 0), scalar(0f)),
				KeyformCell(intArrayOf(1, 0), scalar(100f)),
				KeyformCell(intArrayOf(0, 1), scalar(0f)),
				KeyformCell(intArrayOf(1, 1), scalar(100f)),
			)
		val result = KeyformGrid(axes, cells).compacted(ChannelValueInterpolator)
		val reduced = assertIs<CompactionResult.Reduced<ChannelValue>>(result).grid
		assertEquals(listOf(angleX), reduced.axes.map { axis -> axis.parameterId })
		assertEquals(listOf(scalar(0f), scalar(100f)), valuesInIndexOrder(reduced))
	}

	/** Colors compact per component, so an identity tint across a whole grid becomes one constant. */
	@Test
	fun aConstantColorTrackCollapses() {
		val grid =
			KeyformGrid(
				listOf(KeyformAxis(angleX, floatArrayOf(0f, 1f, 2f))),
				List(3) { keyIndex -> KeyformCell<ChannelValue>(intArrayOf(keyIndex), ChannelValue.Color(ColorRgb.MultiplyIdentity)) },
			)
		val result = grid.compacted(ChannelValueInterpolator, ChannelValueKind.COLOR)
		assertEquals(ChannelValue.Color(ColorRgb.MultiplyIdentity), assertIs<CompactionResult.Constant<ChannelValue>>(result).form)
	}

	/**
	 * A flag track drops constant AXES but never an interior key: a flag snaps to the floor cell instead
	 * of blending, so "equal to the blend of its neighbours" is not a sound drop test for one.
	 */
	@Test
	fun aFlagTrackKeepsItsInteriorKeys() {
		val grid =
			KeyformGrid(
				listOf(KeyformAxis(angleX, floatArrayOf(0f, 5f, 10f))),
				listOf<KeyformCell<ChannelValue>>(
					KeyformCell(intArrayOf(0), ChannelValue.Flag(false)),
					KeyformCell(intArrayOf(1), ChannelValue.Flag(false)),
					KeyformCell(intArrayOf(2), ChannelValue.Flag(true)),
				),
			)
		val reduced = assertIs<CompactionResult.Reduced<ChannelValue>>(grid.compacted(ChannelValueInterpolator, ChannelValueKind.FLAG)).grid
		assertEquals(listOf(0f, 5f, 10f), reduced.axes.single().keys.toList())
	}

	/**
	 * Two ADJACENT interpolable keys are the trap the greedy pass falls into: the first key's drop is
	 * justified against a neighbour that itself drops next, and refinement then rebuilds the first from
	 * the surviving endpoints - a different IEEE-754 rounding sequence.  The values below are a concrete
	 * float32 case where that direct rebuild differs by an ULP (found by exhaustive search), so this test
	 * fails against an unverified greedy drop pass.
	 */
	@Test
	fun adjacentInterpolableKeysStayMutualInverses() {
		val v0 = scalar(0.102f)
		val v3 = scalar(0.1014f)
		// Constructed to make the greedy pass drop key 1 (against 0 and 3) and then key 3 (against 0 and 4).
		val v2 = ChannelValueInterpolator.interpolate(v0, v3, 0.75f)
		val v1 = ChannelValueInterpolator.interpolate(v0, v2, 1f / 3f)
		val original =
			KeyformGrid(
				listOf(KeyformAxis(angleX, floatArrayOf(0f, 1f, 3f, 4f))),
				listOf(v0, v1, v2, v3).mapIndexed { keyIndex, value -> KeyformCell(intArrayOf(keyIndex), value) },
			)

		val reduced = assertIs<CompactionResult.Reduced<ChannelValue>>(original.compacted(ChannelValueInterpolator)).grid
		val restored =
			reduced.refinedToUnion(
				mapOf(angleX to original.axes.single().keys),
				emptyMap(),
				ChannelValueInterpolator,
			)

		assertEquals(listOf(0f, 1f, 3f, 4f), restored.axes.single().keys.toList())
		assertEquals(valuesInIndexOrder(original), valuesInIndexOrder(restored), "every dropped key restores bit-for-bit")
	}

	/**
	 * An axis whose keys do not bracket the parameter's range gates the track's out-of-span fallback to
	 * the owner's static, so it is never dropped and its track is never lifted to a constant.
	 */
	@Test
	fun aNonSpanningConstantAxisIsNeverDroppedOrLifted() {
		val spans: (KeyformAxis) -> Boolean = { axis -> axis.keys.first() <= -1f && axis.keys.last() >= 1f }

		// Constant along a NON-spanning sole axis: kept as-is, because poses below 0.5 read the static.
		val gated = track(floatArrayOf(0.5f, 1f), floatArrayOf(0.4f, 0.4f))
		val gatedResult = gated.compacted(ChannelValueInterpolator, axisSpansRange = spans)
		assertSame(gated, assertIs<CompactionResult.Reduced<ChannelValue>>(gatedResult).grid)

		// The same constant on a spanning axis lifts as before.
		val spanning = track(floatArrayOf(-1f, 1f), floatArrayOf(0.4f, 0.4f))
		val liftedResult = spanning.compacted(ChannelValueInterpolator, axisSpansRange = spans)
		assertEquals(scalar(0.4f), assertIs<CompactionResult.Constant<ChannelValue>>(liftedResult).form)

		// Mixed: the spanning constant axis drops, the non-spanning one survives as the gate.
		val axes = listOf(KeyformAxis(angleX, floatArrayOf(-1f, 1f)), KeyformAxis(angleY, floatArrayOf(0.5f, 1f)))
		val cells =
			listOf(
				KeyformCell(intArrayOf(0, 0), scalar(0.4f)),
				KeyformCell(intArrayOf(1, 0), scalar(0.4f)),
				KeyformCell(intArrayOf(0, 1), scalar(0.4f)),
				KeyformCell(intArrayOf(1, 1), scalar(0.4f)),
			)
		val mixedResult = KeyformGrid(axes, cells).compacted(ChannelValueInterpolator, axisSpansRange = spans)
		val mixedReduced = assertIs<CompactionResult.Reduced<ChannelValue>>(mixedResult).grid
		assertEquals(listOf(angleY), mixedReduced.axes.map { axis -> axis.parameterId }, "only the gate axis survives")
	}

	/**
	 * The model-level pass derives the spanning gate from the parameter table: a glue keyed constant 0.4
	 * on keys [0.5, 1.0] of a -1..1 parameter welds at the 1.0 static fallback below 0.5, so lifting the
	 * constant would change those poses.  The track must survive and the static must keep its value; the
	 * same constant on a spanning axis lifts as before.
	 */
	@Test
	fun modelCompactionKeepsTheOutOfSpanStaticFallback() {
		val gatedTrack =
			KeyformGrid(
				listOf(KeyformAxis(angleX, floatArrayOf(0.5f, 1f))),
				listOf<KeyformCell<ChannelValue>>(
					KeyformCell(intArrayOf(0), scalar(0.4f)),
					KeyformCell(intArrayOf(1), scalar(0.4f)),
				),
			)
		val spanningTrack =
			KeyformGrid(
				listOf(KeyformAxis(angleX, floatArrayOf(-1f, 1f))),
				listOf<KeyformCell<ChannelValue>>(
					KeyformCell(intArrayOf(0), scalar(0.4f)),
					KeyformCell(intArrayOf(1), scalar(0.4f)),
				),
			)
		val model =
			PuppetModel(
				parameters = listOf(Parameter(angleX, angleX.raw, min = -1f, max = 1f, default = 0f)),
				parts = emptyList(),
				deformers = emptyList(),
				drawables = emptyList(),
				rootChildren = emptyList(),
				rootPartId = null,
				glues =
					listOf(
						Glue(DrawableId("a"), DrawableId("b"), emptyList(), ChannelGrids(mapOf(FormChannel.GLUE_INTENSITY to gatedTrack))),
						Glue(DrawableId("c"), DrawableId("d"), emptyList(), ChannelGrids(mapOf(FormChannel.GLUE_INTENSITY to spanningTrack))),
					),
			)

		val compacted = model.withChannelsCompacted()

		val gated = compacted.glues.first { glue -> glue.meshA == DrawableId("a") }
		assertSame(gatedTrack, gated.channelGrids[FormChannel.GLUE_INTENSITY], "the non-spanning track survives untouched")
		assertEquals(1f, gated.intensity, "the full-weld fallback below the span keeps its static")

		val lifted = compacted.glues.first { glue -> glue.meshA == DrawableId("c") }
		assertEquals(null, lifted.channelGrids[FormChannel.GLUE_INTENSITY], "the spanning constant still lifts")
		assertEquals(0.4f, lifted.intensity)
	}

	/**
	 * A compacted glue keeps its authored id.
	 *
	 * Compaction runs on the MOC3 import path itself, so a glue whose intensity track lifts passes
	 * through here before any export sees it.  The id exists purely so a round trip writes back the name
	 * the model was authored with, which a rewrite that dropped it would silently defeat - the export
	 * would synthesize `Glue_ArtMeshNN_ArtMeshMM_` and nothing would report a loss.
	 */
	@Test
	fun aCompactedGlueKeepsItsAuthoredId() {
		val spanningTrack =
			KeyformGrid(
				listOf(KeyformAxis(angleX, floatArrayOf(-1f, 1f))),
				listOf<KeyformCell<ChannelValue>>(
					KeyformCell(intArrayOf(0), scalar(0.4f)),
					KeyformCell(intArrayOf(1), scalar(0.4f)),
				),
			)
		val model =
			PuppetModel(
				parameters = listOf(Parameter(angleX, angleX.raw, min = -1f, max = 1f, default = 0f)),
				parts = emptyList(),
				deformers = emptyList(),
				drawables = emptyList(),
				rootChildren = emptyList(),
				rootPartId = null,
				glues =
					listOf(
						Glue(
							DrawableId("a"),
							DrawableId("b"),
							emptyList(),
							ChannelGrids(mapOf(FormChannel.GLUE_INTENSITY to spanningTrack)),
							id = "Glue__ArtMesh48__ArtMesh49",
						),
					),
			)

		val compacted = model.withChannelsCompacted()

		// The glue IS rewritten here - its track lifts to a static - so this is the rewrite that must
		// carry the id, not a pass-through that trivially would.
		assertEquals(0.4f, compacted.glues.single().intensity, "the spanning constant lifted")
		assertEquals("Glue__ArtMesh48__ArtMesh49", compacted.glues.single().id)
	}

	/** A sparse grid is passed straight through - its missing cells cannot be tested, so nothing is dropped. */
	@Test
	fun aSparseGridIsUntouched() {
		val dense = track(floatArrayOf(0f, 5f, 10f), floatArrayOf(0f, 50f, 100f))
		val sparse = KeyformGrid(dense.axes, dense.cells.drop(1))
		assertSame(sparse, assertIs<CompactionResult.Reduced<ChannelValue>>(sparse.compacted(ChannelValueInterpolator)).grid)
	}

	/**
	 * THE invariant: compact, then refine back onto the original key set, and every stored value is
	 * bit-identical to what it was.  This is the property an importer's compaction pass and an exporter's
	 * union bake both rest on.
	 */
	@Test
	fun compactionAndRefinementAreMutualInverses() {
		val originals =
			listOf(
				// Exactly linear - loses its interior key to compaction, so refinement has to rebuild it.
				track(floatArrayOf(0f, 5f, 10f), floatArrayOf(0f, 50f, 100f)),
				// Irregular key spacing, so the rebuilt fraction is not a tidy one half.
				track(floatArrayOf(-30f, -7f, 30f), floatArrayOf(1f, (1f + 59f * (23f / 60f)), 60f)),
				// Constant, which compacts all the way out and must replicate back.
				track(floatArrayOf(-1f, 0f, 1f), floatArrayOf(0.25f, 0.25f, 0.25f)),
				// Genuinely bending, so nothing is dropped and refinement is a no-op.
				track(floatArrayOf(0f, 5f, 10f), floatArrayOf(0f, 90f, 100f)),
			)
		for (original in originals) {
			val originalKeys = original.axes.single().keys
			val compacted = original.compacted(ChannelValueInterpolator)
			val restored =
				when (compacted) {
					is CompactionResult.Constant ->
						KeyformGrid(
							listOf(KeyformAxis(angleX, floatArrayOf(originalKeys.first(), originalKeys.last()))),
							listOf(KeyformCell(intArrayOf(0), compacted.form), KeyformCell(intArrayOf(1), compacted.form)),
						).refinedToUnion(mapOf(angleX to originalKeys), emptyMap(), ChannelValueInterpolator)

					is CompactionResult.Reduced ->
						compacted.grid.refinedToUnion(mapOf(angleX to originalKeys), emptyMap(), ChannelValueInterpolator)
				}
			assertEquals(originalKeys.toList(), restored.axes.single().keys.toList(), "every original key is restored")
			assertEquals(valuesInIndexOrder(original), valuesInIndexOrder(restored), "restored bit-for-bit")
		}
	}

	/**
	 * Refinement is span-clamped: a union key outside a track's own key span is NOT inserted.  Widening the
	 * span would resurrect a track its author keyed to fall out of range - the toggle-part pattern - which
	 * is a change to the rig, not a change in precision.
	 */
	@Test
	fun refinementNeverWidensAnExistingSpan() {
		val narrow = track(floatArrayOf(-1f, 1f), floatArrayOf(0f, 100f))
		val refined = narrow.refinedToUnion(mapOf(angleX to floatArrayOf(-30f, 0f, 30f)), emptyMap(), ChannelValueInterpolator)
		assertEquals(listOf(-1f, 0f, 1f), refined.axes.single().keys.toList(), "only the interior union key is taken")
	}

	/**
	 * A parameter the track does not key at all gets an appended axis spanning the parameter's own range -
	 * safe precisely because the track is constant along it, so the widening carries no motion.
	 */
	@Test
	fun refinementAppendsAWholeAxisForAnUnkeyedParameter() {
		val single = track(floatArrayOf(0f, 10f), floatArrayOf(0f, 100f))
		val refined =
			single.refinedToUnion(
				mapOf(angleY to floatArrayOf(0f)),
				mapOf(angleY to -1f..1f),
				ChannelValueInterpolator,
			)
		assertEquals(listOf(angleX, angleY), refined.axes.map { axis -> axis.parameterId }, "the new axis is appended")
		assertEquals(listOf(-1f, 0f, 1f), refined.axes[1].keys.toList())
		assertTrue(refined.isDense)
		assertEquals(6, refined.cells.size)
	}
}