package io.github.psd2live.core

import io.github.psd2live.core.fit.EyeFit
import io.github.psd2live.core.fit.EyeModules
import io.github.psd2live.core.fit.EyeOptions
import io.github.psd2live.core.fit.EyeRig
import io.github.psd2live.core.recipe.RigModules
import org.umamo.runtime.eval.scalarAt
import org.umamo.runtime.model.ChannelGrids
import org.umamo.runtime.model.FormChannel
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EyeOpeningTest {
	private fun eye(flat: Boolean = false, mirrored: Boolean = false, lift: Float = 0.35f): EyeRig {
		val upper = List(EyeFit.SAMPLES) { k ->
			val u = EyeFit.u(k)
			0.05f * u - (if (flat) 0.08f else 0.25f) * sin(PI * u).toFloat()
		}
		val lower = List(EyeFit.SAMPLES) { k -> val u = EyeFit.u(k); 0.05f * u + 0.15f * sin(PI * u).toFloat() }
		return EyeRig(EyeFit(if (mirrored) Side.RIGHT else Side.LEFT, 0f, 0f, if (mirrored) -1f else 1f, 0f, 100f,
			upper, lower, null, null), 0.05f, 1f, 0.6f, wideLift = lift)
	}

	@Test fun theDefaultExpansionIsModestAndZeroStrengthKeepsEveryEdgeStill() {
		val options = EyeOptions(wide = true)
		assertEquals(RigModules.EYE_WIDE.knob("lift")!!.default / 100f, options.wideLift)
		for (flat in listOf(false, true)) {
			val eye = eye(flat, lift = options.wideLift)
			val wide = eye.pose(2f)
			val k = EyeFit.SAMPLES / 2
			val height = (wide.lower[k] - wide.upper[k]) / eye.opening
			assertTrue(height in 1.05f..1.2f, "default widening must not turn the eye into a stare: $height")
			val still = eye(flat, lift = 0f)
			assertEquals(still.fit.upper, still.pose(2f).upper)
			assertEquals(still.fit.lower, still.pose(2f).lower)
		}
	}

	@Test fun wideningDoesNotShrinkOrShiftTheIrisAndKeepsItsJellyMotion() {
		val eye = eye()
		val iris = Bounds(20f, -18f, 70f, 12f)
		val positions = floatArrayOf(20f, -18f, 70f, -18f, 70f, 12f, 20f, 12f)
		val frame = Bounds(0f, -50f, 100f, 50f)
		val jelly = { x: Float, y: Float, v: Float -> (x + 2f * v) to (y - v) }
		assertNull(EyeModules.irisGrid(Side.LEFT, positions, frame, iris, eye, EyeOptions(wide = true), jelly))
		val grid = assertNotNull(EyeModules.irisGrid(Side.LEFT, positions, frame, iris, eye,
			EyeOptions(wide = true, squash = true), jelly))
		val openAxis = grid.axes[1].keys
		for (j in 0..2) {
			fun deltas(open: Float) = grid.cells.single { it.coordinate.contentEquals(intArrayOf(j, openAxis.indexOfFirst { key -> key == open })) }.form.positionDeltas
			assertContentEquals(deltas(1f), deltas(2f), "widening changed iris size, anchor, or jelly at $j")
		}
		assertTrue(grid.cells.any { it.coordinate[0] != 1 && it.form.positionDeltas.any { d -> abs(d) > 0.001f } })
	}

	@Test fun theDrawnEyeDoesNotMoveAndTheWholeUpperLineLiftsBeyondIt() {
		for (flat in listOf(false, true)) {
			val eye = eye(flat)
			for (kind in EyeRig.EyeMesh.entries) {
				val p = eye.fit.toCanvas(0.3f, -0.1f)
				assertEquals(0f to 0f, eye.delta(kind, p.first, p.second, 1f))
			}
			val wide = eye.pose(2f)
			val mid = EyeFit.SAMPLES / 2
			val lift = eye.fit.upper[mid] - wide.upper[mid]
			assertEquals(eye.opening * 0.35f, lift, 1e-5f)
			for (k in listOf(0, EyeFit.SAMPLES - 1)) {
				assertTrue(eye.fit.upper[k] - wide.upper[k] > lift * 0.4f, "the corner must share the lift")
				assertEquals(wide.upper[k] - eye.fit.upper[k], wide.lower[k] - eye.fit.lower[k], 1e-5f)
				assertEquals(wide.upperDx!![k], wide.lowerDx!![k], 1e-5f, "both edges share the corner")
			}
		}
	}

	@Test fun aCombinedLinerHasNoJumpWhereItsWingWasAnchored() {
		val eye = eye(); val pose = eye.pose(2f)
		for (u in listOf(0.12f, 0.88f)) for (t in listOf(0f, 0.5f, 1f)) {
			fun moved(q: Float): Pair<Float, Float> {
				val top = EyeFit.sample(eye.fit.upper, q); val lower = EyeFit.sample(eye.fit.lower, q)
				val p = eye.fit.toCanvas(q, top + (lower - top) * t)
				return eye.move(EyeRig.EyeMesh.UPPER_LASH, p.first, p.second, pose)
			}
			val a = moved(u - 1e-5f); val b = moved(u + 1e-5f)
			assertTrue(abs(a.first - b.first) + abs(a.second - b.second) < 0.02f, "liner discontinuity at $u, $t")
		}
	}

	@Test fun theWhiteAndUpperLashShareTheirEdgeAndTheLashKeepsItsThickness() {
		val eye = eye(); val wide = eye.pose(2f)
		for (u in listOf(0.01f, 0.03f, 0.15f, 0.3f, 0.5f, 0.7f, 0.85f, 0.97f, 0.99f)) {
			val top = EyeFit.sample(eye.fit.upper, u); val edge = eye.fit.toCanvas(u, top)
			val white = eye.move(EyeRig.EyeMesh.WHITE, edge.first, edge.second, wide)
			val lash = eye.move(EyeRig.EyeMesh.UPPER_LASH, edge.first, edge.second, wide)
			assertEquals(white.first, lash.first, 1e-5f); assertEquals(white.second, lash.second, 1e-5f)
			val lower = eye.fit.toCanvas(u, EyeFit.sample(eye.fit.lower, u))
			val whiteLower = eye.move(EyeRig.EyeMesh.WHITE, lower.first, lower.second, wide)
			val lashLower = eye.move(EyeRig.EyeMesh.UPPER_LASH, lower.first, lower.second, wide)
			val separateLower = eye.move(EyeRig.EyeMesh.LOWER_LASH, lower.first, lower.second, wide)
			assertEquals(whiteLower.first, lashLower.first, 1e-4f); assertEquals(whiteLower.second, lashLower.second, 1e-4f)
			assertEquals(whiteLower.first, separateLower.first, 1e-4f); assertEquals(whiteLower.second, separateLower.second, 1e-4f)
			val a = eye.fit.toCanvas(u, top - 0.06f); val b = eye.fit.toCanvas(u, top - 0.01f)
			val da = eye.move(EyeRig.EyeMesh.UPPER_LASH, a.first, a.second, wide)
			val db = eye.move(EyeRig.EyeMesh.UPPER_LASH, b.first, b.second, wide)
			assertEquals(b.second - a.second, (b.second + db.second) - (a.second + da.second), 1e-4f)
		}
	}

	@Test fun theWidenedOpeningStaysOrderedAndMirrorsInTheEyesOwnFrame() {
		val a = eye(lift = 0.8f); val b = eye(mirrored = true, lift = 0.8f)
		for (step in 0..100) {
			val open = 1f + step / 100f; val p = a.pose(open)
			for (k in 1 until EyeFit.SAMPLES - 1) {
				assertTrue(p.upper[k] < p.lower[k], "eyelids crossed at $open/$k")
				if (p.upperDx != null) assertTrue(EyeFit.u(k) + p.upperDx[k] > EyeFit.u(k - 1) + p.upperDx[k - 1])
			}
			val pa = a.fit.toCanvas(0.2f, -0.15f); val pb = b.fit.toCanvas(0.2f, -0.15f)
			val da = a.delta(EyeRig.EyeMesh.UPPER_LASH, pa.first, pa.second, open)
			val db = b.delta(EyeRig.EyeMesh.UPPER_LASH, pb.first, pb.second, open)
			assertEquals(-da.first, db.first, 1e-4f); assertEquals(da.second, db.second, 1e-4f)
		}
	}

	@Test fun aThickWingBelowTheOuterCornerDoesNotFoldWhileWidening() {
		val eye = eye(lift = 0.8f)
		val wing = listOf(0.93f to 0.09f, 1.04f to 0.06f, 0.99f to 0.124f).map { eye.fit.toCanvas(it.first, it.second) }
		fun area(points: List<Pair<Float, Float>>): Float =
			(points[1].first - points[0].first) * (points[2].second - points[0].second) -
				(points[1].second - points[0].second) * (points[2].first - points[0].first)
		val rest = area(wing)
		for (step in 0..100) {
			val pose = eye.pose(1f + step / 100f)
			val moved = wing.map { p -> eye.move(EyeRig.EyeMesh.UPPER_LASH, p.first, p.second, pose).let { d ->
				p.first + d.first to p.second + d.second } }
			assertTrue(area(moved) / rest > 0.1f, "the lower wing folded at step $step")
		}
	}

	@Test fun theClosedDrawingCannotReappearAboveTheDrawnOpening() {
		for (swap in listOf(false, true)) {
			val track = EyeModules.drawingOpacity(Side.LEFT, true, swap, 2f)
			val grids = ChannelGrids(mapOf(FormChannel.OPACITY to track))
			for (v in listOf(1f, 1.002f, 1.25f, 1.5f, 2f)) {
				assertEquals(0f, grids.scalarAt(FormChannel.OPACITY, 1f, { v }), 1e-6f)
			}
			assertEquals(1f, grids.scalarAt(FormChannel.OPACITY, 1f, { 0f }), 1e-6f)
		}
		val eye = eye()
		val options = EyeOptions(wide = true, lidFade = true)
		val track = EyeModules.opacity(SemanticTag.EYELASH, PartRole.LID, Side.LEFT, eye, options)!!
		val grids = ChannelGrids(mapOf(FormChannel.OPACITY to track))
		assertEquals(1f, grids.scalarAt(FormChannel.OPACITY, 0.2f, { 2f }), 1e-6f, "wide eye must not fall back to static opacity")
	}
}
