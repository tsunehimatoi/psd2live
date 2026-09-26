package io.github.psd2live.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.WarpLatticeForm
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SwingDeformerTest {
	private val rows = 6
	private val columns = 2

	/** A [width]×[height] lattice with its top-left at the origin; row 0 is the top edge. */
	private fun lattice(width: Float, height: Float, rows: Int = this.rows, columns: Int = this.columns) =
		FloatArray((rows + 1) * (columns + 1) * 2).also { p ->
			var i = 0
			for (r in 0..rows) for (c in 0..columns) { p[i++] = width * c / columns; p[i++] = height * r / rows }
		}

	private fun shape(kind: SwingKind, fulcrum: SwingFulcrum, magnitude: Float = 0.3f, segments: Int = 1, flip: Boolean = false,
		lift: Float = 0f, softness: Float = 0.5f, zoom: Float = 0f) =
		SwingDeformer.Shape(kind, fulcrum, flip, magnitude, lift, softness, zoom, segments)

	private fun point(p: FloatArray, r: Int, c: Int) = p[(r * (columns + 1) + c) * 2] to p[(r * (columns + 1) + c) * 2 + 1]

	/** Length of the center column polyline. */
	private fun centerLength(p: FloatArray): Float =
		(1..rows).sumOf { r -> val a = point(p, r - 1, 1); val b = point(p, r, 1); hypot(b.first - a.first, b.second - a.second).toDouble() }.toFloat()

	@Test
	fun zeroIsIdentityAndThePinnedEdgeNeverMoves() {
		val rest = lattice(100f, 300f)
		val same = SwingDeformer.deform(rest, rows, columns, 1f, 1f, shape(SwingKind.LATERAL, SwingFulcrum.TOP), floatArrayOf(0f))
		assertTrue(same.contentEquals(rest))
		for (kind in SwingKind.entries) for (value in listOf(-1f, 1f)) {
			val swung = SwingDeformer.deform(rest, rows, columns, 1f, 1f, shape(kind, SwingFulcrum.TOP), floatArrayOf(value))
			for (c in 0..columns) assertEquals(point(rest, 0, c), point(swung, 0, c))
		}
	}

	@Test
	fun lateralSwingBendsWithItsLengthKeptSoTheTipRises() {
		val rest = lattice(100f, 300f)
		val swung = SwingDeformer.deform(rest, rows, columns, 1f, 1f, shape(SwingKind.LATERAL, SwingFulcrum.TOP, 0.3f), floatArrayOf(1f))
		val tip = point(swung, rows, 1)
		assertEquals(50f + 0.3f * 300f, tip.first, 3f, "tip travels the magnitude toward the right edge")
		assertTrue(tip.second < 300f - 5f, "a bent strand of the same length ends higher: ${tip.second}")
		assertEquals(300f, centerLength(swung), 3f)
		val left = SwingDeformer.deform(rest, rows, columns, 1f, 1f, shape(SwingKind.LATERAL, SwingFulcrum.TOP, 0.3f), floatArrayOf(-1f))
		assertEquals(50f - 0.3f * 300f, point(left, rows, 1).first, 3f)
	}

	@Test
	fun flipMirrorsTheSwing() {
		val rest = lattice(100f, 300f)
		val flipped = SwingDeformer.deform(rest, rows, columns, 1f, 1f, shape(SwingKind.LATERAL, SwingFulcrum.TOP, flip = true), floatArrayOf(1f))
		val negative = SwingDeformer.deform(rest, rows, columns, 1f, 1f, shape(SwingKind.LATERAL, SwingFulcrum.TOP), floatArrayOf(-1f))
		for (i in rest.indices) assertEquals(negative[i], flipped[i], 1e-4f)
	}

	@Test
	fun softerStrandsKeepTheRootStill() {
		val rest = lattice(100f, 300f)
		fun upper(softness: Float) = point(SwingDeformer.deform(rest, rows, columns, 1f, 1f,
			shape(SwingKind.LATERAL, SwingFulcrum.TOP, softness = softness), floatArrayOf(1f)), 2, 1).first - 50f
		assertTrue(upper(1f) < upper(0f), "stiff root at softness 1: ${upper(1f)} vs ${upper(0f)}")
	}

	@Test
	fun verticalSwingUnderATopPivotStretchesAndNarrows() {
		val rest = lattice(100f, 300f)
		val down = SwingDeformer.deform(rest, rows, columns, 1f, 1f, shape(SwingKind.VERTICAL, SwingFulcrum.TOP, 0.1f), floatArrayOf(1f))
		assertEquals(330f, point(down, rows, 1).second, 1f, "tip drops by the magnitude")
		assertEquals(50f, point(down, rows, 1).first, 1e-3f, "no sideways travel")
		val width = point(down, rows - 1, 2).first - point(down, rows - 1, 0).first
		assertTrue(width < 100f, "stretched tip narrows: $width")
		val up = SwingDeformer.deform(rest, rows, columns, 1f, 1f, shape(SwingKind.VERTICAL, SwingFulcrum.TOP, 0.1f), floatArrayOf(-1f))
		assertEquals(270f, point(up, rows, 1).second, 1f)
		assertTrue(point(up, rows - 1, 2).first - point(up, rows - 1, 0).first > 100f, "compressed tip widens")
	}

	@Test
	fun verticalSwingUnderASidePivotBendsUpAndDown() {
		val rest = lattice(300f, 100f)
		val swung = SwingDeformer.deform(rest, rows, columns, 1f, 1f, shape(SwingKind.VERTICAL, SwingFulcrum.LEFT, 0.3f), floatArrayOf(1f))
		// The right column is the tip; +1 moves it toward the bottom edge.
		val tip = (0..rows).map { point(swung, it, columns).second }.average().toFloat()
		assertEquals(50f + 0.3f * 300f, tip, 4f)
		for (r in 0..rows) assertEquals(point(rest, r, 0), point(swung, r, 0))
	}

	@Test
	fun bendsAreRoundInProportionalSpace() {
		// A 0..1 UV lattice under a parent three times taller than wide.
		val rest = lattice(1f, 1f)
		val swung = SwingDeformer.deform(rest, rows, columns, 100f, 300f, shape(SwingKind.LATERAL, SwingFulcrum.TOP, 0.3f), floatArrayOf(1f))
		val scaled = FloatArray(swung.size) { swung[it] * if (it % 2 == 0) 100f else 300f }
		assertEquals(300f, centerLength(scaled), 3f)
		assertEquals(50f + 90f, point(scaled, rows, 1).first, 3f)
	}

	@Test
	fun laterSegmentsOnlyTurnWhatHangsBelowThem() {
		val rest = lattice(100f, 300f)
		val s = shape(SwingKind.LATERAL, SwingFulcrum.TOP, segments = 2)
		val lower = SwingDeformer.deform(rest, rows, columns, 1f, 1f, s, floatArrayOf(0f, 1f))
		for (r in 0..rows / 2) assertEquals(point(rest, r, 1).first, point(lower, r, 1).first, 1e-3f)
		assertTrue(point(lower, rows, 1).first > 60f)
		val both = SwingDeformer.deform(rest, rows, columns, 1f, 1f, s, floatArrayOf(1f, 1f))
		assertEquals(50f + 0.3f * 300f, point(both, rows, 1).first, 4f, "every segment at 1 reaches the magnitude")
	}

	private val angleX = Parameter(ParameterId("ParamAngleX"), "Angle X", -30f, 30f, 0f)
	private val angleY = Parameter(ParameterId("ParamAngleY"), "Angle Y", -30f, 30f, 0f)

	/** A 2-key angle-keyed Warp at the root, 100 wide and 300 tall. */
	private fun model(): PuppetModel {
		val rest = lattice(100f, 300f)
		val turned = FloatArray(rest.size) { if (it % 2 == 0) rest[it] + 20f else rest[it] }
		val grid = KeyformGrid(listOf(KeyformAxis(angleX.id, floatArrayOf(-30f, 30f))),
			listOf(KeyformCell(intArrayOf(0), WarpLatticeForm(rest)), KeyformCell(intArrayOf(1), WarpLatticeForm(turned))))
		val warp = Deformer.Warp(DeformerId("WarpTail"), "Tail", null, null, rows, columns, true, grid)
		return PuppetModel(listOf(angleX, angleY), emptyList(), listOf(warp), emptyList(), emptyList(), null, worldOriginX = 500f)
	}

	private fun swing(kind: SwingKind = SwingKind.LATERAL, parameters: List<String> = listOf("ParamSwingTail")) =
		RigSwingEdit("tail", "Tail swing", kind, listOf("WarpTail"), parameters)

	@Test
	fun generatorAddsItsAxisOverTheExistingKeysAndCreatesTheParameter() {
		val (swung, issues) = SwingGenerator.applyOne(model(), swing())
		assertTrue(issues.isEmpty(), issues.toString())
		val parameter = swung.parameters.single { it.id.raw == "ParamSwingTail" }
		assertEquals(-1f to 1f, parameter.min to parameter.max)
		val grid = (swung.deformers.single() as Deformer.Warp).geometryGrid!!
		assertEquals(listOf("ParamAngleX", "ParamSwingTail"), grid.axes.map { it.parameterId.raw })
		assertEquals(6, grid.cells.size)
		// The turned angle key keeps its own offset at the neutral swing key.
		val turnedNeutral = grid.cells.single { it.coordinate.contentEquals(intArrayOf(1, 1)) }.form.controlPoints
		assertEquals(20f, turnedNeutral[0], 1e-4f)
		// Replaying the same swing replaces its axis instead of stacking another one.
		val again = SwingGenerator.apply(swung, listOf(swing()))
		assertEquals(6, (again.deformers.single() as Deformer.Warp).geometryGrid!!.cells.size)
	}

	@Test
	fun generatorReportsMissingTargetsWithoutFailing() {
		val missing = swing().copy(targets = listOf("Nope"))
		val base = model()
		val (result, issues) = SwingGenerator.applyOne(base, missing)
		assertEquals(1, issues.size)
		assertTrue(base.deformers.single() === result.deformers.single())
	}

	@Test
	fun autoPivotHangsTallTargetsFromTheTop() {
		assertEquals(SwingFulcrum.TOP, SwingGenerator.measure(model(), "WarpTail")!!.first)
		assertEquals(300f, SwingGenerator.measure(model(), "WarpTail")!!.second, 1e-3f)
	}

	@Test
	fun swingPhysicsFollowsTheKindAndDrivesEverySegment() {
		val available = setOf("ParamAngleX", "ParamAngleY", "ParamAngleZ", "ParamBodyAngleY", "ParamSwingA", "ParamSwingB")
		val lateral = PhysicsGenerator.swingRules(listOf(swing(parameters = listOf("ParamSwingA"))), available).single()
		assertEquals(listOf("ParamAngleX", "ParamAngleZ"), lateral.inputs.map { it.parameter })
		val vertical = PhysicsGenerator.swingRules(listOf(swing(SwingKind.VERTICAL, listOf("ParamSwingA", "ParamSwingB"))), available).single()
		assertEquals(listOf("ParamAngleY", "ParamBodyAngleY", "ParamAngleZ"), vertical.inputs.map { it.parameter })
		assertEquals(PhysicsGenerator.InputType.X, vertical.inputs.first().type)
		assertEquals(listOf(1, 2), vertical.outputs.map { it.vertexIndex })
		assertEquals(3, vertical.vertices.size)

		val json = Json.parseToJsonElement(PhysicsGenerator.generate(false, false, false, available,
			swings = listOf(swing(SwingKind.VERTICAL, listOf("ParamSwingA", "ParamSwingB"))))!!).jsonObject
		assertEquals(2, json.getValue("Meta").jsonObject.getValue("TotalOutputCount").jsonPrimitive.int)
		val outputs = json.getValue("PhysicsSettings").jsonArray.single().jsonObject.getValue("Output").jsonArray
		assertEquals(listOf(1, 2), outputs.map { it.jsonObject.getValue("VertexIndex").jsonPrimitive.int })

		// A custom group on one of the outputs replaces the whole swing pendulum.
		val custom = RigPhysicsEdit("Mine", "Mine", "ParamAngleX", "ParamSwingB")
		assertEquals(listOf("Mine"), PhysicsGenerator.mergeCustomRules(listOf(vertical), listOf(custom), available).map { it.id })
	}

	/** A body Warp over a 500px canvas with one hanging strip mesh under it. */
	private fun meshModel(): PuppetModel {
		val body = Deformer.Warp(DeformerId("Body"), "Body", null, null, 1, 1, true,
			KeyformGrid(emptyList(), listOf(KeyformCell(intArrayOf(), WarpLatticeForm(floatArrayOf(0f, 0f, 500f, 0f, 0f, 500f, 500f, 500f))))))
		val positions = floatArrayOf(0.4f, 0.2f, 0.5f, 0.2f, 0.4f, 0.8f, 0.5f, 0.8f)
		val strip = Drawable(DrawableId("Strip"), "Strip", body.id, BlendMode.Normal, emptyList(),
			DrawableMesh(positions, positions.copyOf(), intArrayOf(0, 1, 3, 0, 3, 2)), null)
		return PuppetModel(listOf(angleX), emptyList(), listOf(body), listOf(strip), listOf(OrgChild.Drawable(strip.id)), null, worldOriginX = 250f)
	}

	@Test
	fun meshTargetsAreWrappedAndBakingKeepsTheForms() {
		val base = meshModel()
		val put = SwingAuthoring.put(RigEditOverlay.Empty, base, RigSwingEdit("strip", "Strip", SwingKind.LATERAL, listOf("Strip"), listOf("ParamSwingStrip")),
			estimatePhysics = true)
		assertEquals(1, put.authoringJournal.size)
		val swing = put.swingEdits.single()
		assertEquals(listOf("Warp_Swing_strip"), swing.targets)
		// Sized from the 300px-tall strip wrap (+10%): 330/30 = 11.
		assertEquals(11f, swing.physics!!.length, 0.1f)
		val swung = put.applyTo(base)
		assertEquals(DeformerId("Warp_Swing_strip"), swung.drawables.single().parentDeformerId)
		val grid = (swung.deformers.single { it.id.raw == "Warp_Swing_strip" } as Deformer.Warp).geometryGrid!!
		assertEquals(listOf("ParamSwingStrip"), grid.axes.map { it.parameterId.raw })

		val baked = SwingAuthoring.bake(put, swung, "strip")
		assertTrue(baked.swingEdits.single().baked)
		val replayed = (baked.applyTo(base).deformers.single { it.id.raw == "Warp_Swing_strip" } as Deformer.Warp).geometryGrid!!
		assertEquals(grid.axes.map { it.parameterId }, replayed.axes.map { it.parameterId })
		for (cell in grid.cells) {
			val other = replayed.cells.single { it.coordinate.contentEquals(cell.coordinate) }
			for (i in cell.form.controlPoints.indices) assertEquals(cell.form.controlPoints[i], other.form.controlPoints[i], 1e-4f)
		}

		val removed = SwingAuthoring.remove(put, "strip").applyTo(base)
		assertTrue(removed.parameters.none { it.id.raw == "ParamSwingStrip" })
	}

	@Test
	fun canvasHandlesDragBackToTheSettingsThatPlacedThem() {
		val base = swing().copy(magnitude = 0.3f, lift = 0.05f, softness = 0.3f, zoom = 0.2f, fulcrum = SwingFulcrum.TOP)
		val gizmo = SwingGizmo.of(model(), base)!!
		val handles = gizmo.handles()
		// At Angle X 0 the lattice sits halfway to its +20px key: rest tip (60, 300); +1 swings it right.
		assertTrue(handles.getValue(SwingGizmo.Handle.TIP).first > 120f)
		val tip = gizmo.drag(SwingGizmo.Handle.TIP, handles.getValue(SwingGizmo.Handle.TIP))
		assertEquals(0.3f, tip.magnitude, 0.01f); assertEquals(0.05f, tip.lift, 0.01f); assertTrue(!tip.flip)
		val crossed = gizmo.drag(SwingGizmo.Handle.TIP, 60f - 0.4f * 300f to 280f)
		assertTrue(crossed.flip); assertEquals(0.4f, crossed.magnitude, 0.01f)

		val soft = SwingGizmo.of(model(), base.copy(softness = 0.9f))!!
		assertEquals(0.3f, soft.drag(SwingGizmo.Handle.MID, handles.getValue(SwingGizmo.Handle.MID)).softness, 0.03f)
		val narrow = SwingGizmo.of(model(), base.copy(zoom = -0.3f))!!
		assertEquals(0.2f, narrow.drag(SwingGizmo.Handle.CORNER_END, handles.getValue(SwingGizmo.Handle.CORNER_END)).zoom, 0.03f)

		assertEquals(SwingFulcrum.LEFT, gizmo.drag(SwingGizmo.Handle.PIVOT_LEFT, 0f to 0f).fulcrum)
		assertEquals(listOf(10f to 0f, 60f to 0f, 110f to 0f), gizmo.pinnedEdge)
	}

	@Test
	fun handlesFollowThePoseOnScreenInCanvasPixels() {
		// A 100×300 child Warp in the UV space of a parent that slides 20px right at Angle X 30.
		val restParent = floatArrayOf(0f, 0f, 100f, 0f, 0f, 300f, 100f, 300f)
		val turned = FloatArray(8) { if (it % 2 == 0) restParent[it] + 20f else restParent[it] }
		val parent = Deformer.Warp(DeformerId("Parent"), "Parent", null, null, 1, 1, true, KeyformGrid(
			listOf(KeyformAxis(angleX.id, floatArrayOf(0f, 30f))),
			listOf(KeyformCell(intArrayOf(0), WarpLatticeForm(restParent)), KeyformCell(intArrayOf(1), WarpLatticeForm(turned)))))
		val child = Deformer.Warp(DeformerId("WarpTail"), "Tail", parent.id, null, rows, columns, true,
			KeyformGrid(emptyList(), listOf(KeyformCell(intArrayOf(), WarpLatticeForm(lattice(1f, 1f))))))
		val model = PuppetModel(listOf(angleX), emptyList(), listOf(parent, child), emptyList(), emptyList(), null, worldOriginX = 500f)
		val edit = swing().copy(fulcrum = SwingFulcrum.TOP)
		val still = SwingGizmo.of(model, edit)!!
		val posed = SwingGizmo.of(model, edit, values = mapOf(angleX.id to 30f))!!
		assertEquals(listOf(0f to 0f, 50f to 0f, 100f to 0f), still.pinnedEdge)
		assertEquals(listOf(20f to 0f, 70f to 0f, 120f to 0f), posed.pinnedEdge)
		// Canvas pixels are Y down: the tip hangs below the pinned edge.
		assertTrue(still.handles().getValue(SwingGizmo.Handle.TIP).second > 200f)
		// Dragging in the posed frame lands where the pointer is.
		val tip = posed.handles().getValue(SwingGizmo.Handle.TIP)
		assertEquals(edit.magnitude, posed.drag(SwingGizmo.Handle.TIP, tip).magnitude, 0.01f)
	}

	@Test
	fun aStretchTipDragsAlongTheAxis() {
		val gizmo = SwingGizmo.of(model(), swing(SwingKind.VERTICAL).copy(fulcrum = SwingFulcrum.TOP, magnitude = 0.1f))!!
		assertEquals(330f, gizmo.handles().getValue(SwingGizmo.Handle.TIP).second, 1f)
		val longer = gizmo.drag(SwingGizmo.Handle.TIP, 50f to 360f)
		assertEquals(0.2f, longer.magnitude, 0.01f); assertTrue(!longer.flip)
		assertTrue(gizmo.drag(SwingGizmo.Handle.TIP, 50f to 270f).flip)
	}

	@Test
	fun swingEditsRoundTripThroughJson() {
		val edit = RigSwingEdit("s", "Swing", SwingKind.VERTICAL, listOf("A", "B"), listOf("P1", "P2"), SwingFulcrum.LEFT, true,
			0.2f, -0.1f, 0.3f, 0.05f, SwingPreset.CLOTH, SwingPhysics(8f, 0.8f, 1.1f, 0.9f, 1.2f), baked = true)
		assertEquals(edit, RigSwingEdit.fromJson(edit.toJson()))
		assertEquals(null, RigSwingEdit.fromJson(Json.parseToJsonElement(
			"""{"id":"s","kind":"lateral","targets":["A"],"parameters":["P"],"physics":null}""").jsonObject).physics)
		assertTrue(abs(RigSwingEdit.fromJson(Json.parseToJsonElement(
			"""{"id":"s","kind":"LATERAL","targets":["A"],"parameters":["P"]}""").jsonObject).magnitude - 0.22f) < 1e-6f)
	}
}
