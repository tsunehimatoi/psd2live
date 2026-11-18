package io.github.psd2live.core

import io.github.psd2live.core.fit.FaceLandmarks
import io.github.psd2live.core.fit.FaceTurn
import io.github.psd2live.core.fit.FeatureTurn
import io.github.psd2live.core.fit.HairTurn
import io.github.psd2live.core.fit.Landmark
import io.github.psd2live.core.fit.TurnStyle
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FaceTurnTest {
	/** A round face 260 wide and 330 tall about (0, 0), y down, symmetric, with its eyes, brows and mouth. */
	private fun face(): FaceLandmarks {
		val n = 240
		val contour = (0 until n).map { k ->
			val a = 2 * PI * k / n
			val c = cos(a); val s = sin(a)
			(130 * c).toFloat() to (150 * s + if (s > 0) 30 * s * s else 0.0).toFloat()
		}
		val points = ArrayList<Landmark>()
		fun add(id: String, x: Float, y: Float) { points += Landmark(id, id.substringBeforeLast('.'), x, y) }
		for ((side, sign) in listOf("L" to 1f, "R" to -1f)) {
			add("eye.$side.inner", 20f * sign, -10f); add("eye.$side.outer", 80f * sign, -12f)
			add("eye.$side.apex", 45f * sign, -40f); add("eye.$side.lowLid", 50f * sign, 15f); add("eye.$side.lashTail", 88f * sign, -30f)
			add("eye.$side.irisTop", 50f * sign, -35f); add("eye.$side.irisBottom", 50f * sign, 12f)
			add("eye.$side.irisInner", 35f * sign, -12f); add("eye.$side.irisOuter", 65f * sign, -12f)
			add("brow.$side.head", 22f * sign, -62f); add("brow.$side.peak", 55f * sign, -72f); add("brow.$side.tail", 85f * sign, -64f)
		}
		add("mouth.cornerA", -20f, 100f); add("mouth.cornerB", 20f, 100f); add("mouth.upperMid", 0f, 96f); add("mouth.lowerMid", 0f, 106f)
		return FaceLandmarks(points, contour, emptyMap())
	}

	private fun mirror(f: (Float, Float) -> Pair<Float, Float>): (Float, Float) -> Pair<Float, Float> = { x, y -> f(-x, y).let { -it.first to it.second } }

	private fun openMouthFace(height: Float = 32f): FaceLandmarks {
		val base = face()
		val points = base.points.filterNot { it.part == "mouth" } + listOf(
			Landmark("mouth.cornerA", "mouth", -28f, 100f),
			Landmark("mouth.cornerB", "mouth", 28f, 100f),
			Landmark("mouth.upperMid", "mouth", 0f, 100f - height / 2f),
			Landmark("mouth.lowerMid", "mouth", 0f, 100f + height / 2f),
			Landmark("face.chin", "face", 0f, 180f),
		)
		return FaceLandmarks(points, base.contour, base.measures)
	}

	private fun mouth(turn: FaceTurn, marks: FaceLandmarks, yaw: Float, pitch: Float) =
		assertNotNull(FeatureTurn.mouth(marks, turn, turn.pose(yaw, pitch)))

	private fun assertOriginalMouth(marks: FaceLandmarks, turn: FaceTurn, yaw: Float, pitch: Float) {
		val pose = turn.pose(yaw, pitch)
		val rule = mouth(turn, marks, yaw, pitch)
		val points = marks.points.filter { it.part == "mouth" }
		val centerX = (points.minOf { it.x } + points.maxOf { it.x }) / 2f
		val centerY = (points.minOf { it.y } + points.maxOf { it.y }) / 2f
		val halfWidth = (points.maxOf { it.x } - points.minOf { it.x }) / 2f
		val moves = pose.moves
		val sx = pose.sx
		val (x, y) = rule(centerX, centerY)
		assertEquals(centerX + pose.slide.first + moves["mouth_lead"] * sx * turn.unit, x, 1e-4f)
		assertEquals(centerY + pose.slide.second + (moves["mouth_dy"] + moves["mouth_curve"]) * turn.unit, y, 1e-4f)
		val left = rule(centerX - halfWidth, centerY)
		val right = rule(centerX + halfWidth, centerY)
		assertEquals(2f * halfWidth * (1f + moves["mA00"]), right.first - left.first, 1e-4f)
		assertEquals(2f * halfWidth * moves["mA10"] * sx, right.second - left.second, 1e-4f)
		val upper = rule(centerX, centerY - 10f)
		val lower = rule(centerX, centerY + 10f)
		assertEquals(20f * moves["mA01"] * sx, lower.first - upper.first, 1e-4f)
		assertEquals(20f * (1f + moves["mA11"]), lower.second - upper.second, 1e-4f)
	}

	@Test fun theStyleTableHoldsEveryMoveAtEveryEnd() {
		val style = TurnStyle.DEFAULT
		assertTrue(style.names.size > 100 && "slide_x" in style.names && "far_eA00" in style.names && "c23_y" in style.names)
		assertEquals(TurnStyle.HAIR_FIELDS.size * style.hairKnots.size * TurnStyle.HAIR_TERMS, style.hairNames.size)
		val ends = style.ends(TurnStyle.Setting())
		assertEquals(0f, style.at(ends, 0f, 0f)["slide_x"])
	}

	@Test fun atRestNothingMoves() {
		val turn = FaceTurn(face())
		assertTrue(turn.usable)
		val (x, y) = turn.map(0f, 0f)(37f, 51f)
		assertEquals(37f, x); assertEquals(51f, y)
	}

	@Test fun turnedTheMiddleSlidesTowardTheTurnAndTheFarEyeNarrows() {
		val marks = face(); val turn = FaceTurn(marks)
		val pose = turn.pose(45f, 0f)
		assertTrue(pose.slide.first > 0f, "the middle goes toward the turn: ${pose.slide}")
		val (nx, _) = pose.field(0f, 40f)
		assertTrue(nx > 0f, "the nose goes with it")
		fun width(side: String): Float {
			val eye = FeatureTurn.Eye.of(marks, side)!!
			val f = FeatureTurn.eye(eye, turn, pose).eye
			val sign = if (side == "L") 1f else -1f
			return abs(f(80f * sign, -12f).first - f(20f * sign, -10f).first)
		}
		// Turning toward +x, the character's left eye (on +x) is the far one.
		assertTrue(width("L") < width("R") * 0.85f, "the far eye narrows more than the near: ${width("L")} / ${width("R")}")
	}

	@Test fun aTurnEitherWayIsTheOtherMirrored() {
		val marks = face(); val turn = FaceTurn(marks)
		val right = turn.map(30f, 15f); val left = mirror(turn.map(-30f, 15f))
		for ((x, y) in listOf(0f to 0f, 60f to -20f, -90f to 80f, 100f to -100f)) {
			val a = right(x, y); val b = left(x, y)
			assertTrue(abs(a.first - b.first) < 2f && abs(a.second - b.second) < 2f, "($x, $y): $a / $b")
		}
		val eyeA = FeatureTurn.eye(FeatureTurn.Eye.of(marks, "L")!!, turn, turn.pose(30f, 0f)).eye
		val eyeB = mirror(FeatureTurn.eye(FeatureTurn.Eye.of(marks, "R")!!, turn, turn.pose(-30f, 0f)).eye)
		val a = eyeA(80f, -12f); val b = eyeB(80f, -12f)
		assertTrue(abs(a.first - b.first) < 1f && abs(a.second - b.second) < 1f, "the far eye either way: $a / $b")
	}

	@Test fun defaultOpenMouthReshapesWithStableCornersInPureYaw() {
		val marks = openMouthFace()
		val turn = FaceTurn(marks)
		val right = mouth(turn, marks, 45f, 0f)
		val mirroredLeft = mirror(mouth(turn, marks, -45f, 0f))
		val cornerA = right(-28f, 100f)
		val cornerB = right(28f, 100f)
		val upper = right(0f, 84f)
		val lower = right(0f, 116f)
		val width = cornerB.first - cornerA.first
		assertTrue(width in 40f..55f, "the turned opening narrows: $width")
		assertEquals(cornerA.second, cornerB.second, .3f, "track the authored corners")
		assertTrue(abs(lower.first - upper.first) in .5f..9f, "the lips retain distinct movements: $upper / $lower")
		for ((x, y) in listOf(-28f to 100f, 28f to 100f, 0f to 84f, 0f to 116f)) {
			val a = right(x, y)
			val b = mirroredLeft(x, y)
			assertEquals(a.first, b.first, .5f)
			assertEquals(a.second, b.second, .5f)
		}
	}

	@Test fun openMouthKeepsPurePitchAndThePitchContributionOfDiagonalPoses() {
		val marks = openMouthFace()
		val turn = FaceTurn(marks)
		for (pitch in listOf(-30f, 30f)) assertOriginalMouth(marks, turn, 0f, pitch)
		val side = mouth(turn, marks, 45f, 0f)
		val yawMoves = turn.pose(45f, 0f).moves
		fun width(rule: (Float, Float) -> Pair<Float, Float>) = rule(28f, 100f).first - rule(-28f, 100f).first
		fun height(rule: (Float, Float) -> Pair<Float, Float>) = rule(0f, 116f).second - rule(0f, 84f).second
		for (pitch in listOf(-30f, -15f, 15f, 30f)) {
			val diagonal = mouth(turn, marks, 45f, pitch)
			val moves = turn.pose(45f, pitch).moves
			assertEquals(56f * (moves["mA00"] - yawMoves["mA00"]), width(diagonal) - width(side), .05f)
			assertEquals(32f * (moves["mA11"] - yawMoves["mA11"]), height(diagonal) - height(side), .05f)
		}
	}

	@Test fun openMouthFallsBackForMissingLandmarksOrNamedStyles() {
		val complete = openMouthFace()
		for (missing in listOf("mouth.lowerMid", "face.chin")) {
			val incomplete = FaceLandmarks(complete.points.filterNot { it.id == missing }, complete.contour, complete.measures)
			assertOriginalMouth(incomplete, FaceTurn(incomplete), 45f, 0f)
		}
		val closed = openMouthFace(height = 0f)
		assertOriginalMouth(closed, FaceTurn(closed), 45f, 0f)
		assertOriginalMouth(complete, FaceTurn(complete, TurnStyle.Setting(preset = "soft")), 45f, 15f)
		val onePoint = FaceLandmarks(complete.points.filter { it.part != "mouth" || it.id == "mouth.cornerA" },
			complete.contour, complete.measures)
		val turn = FaceTurn(onePoint)
		assertNull(FeatureTurn.mouth(onePoint, turn, turn.pose(45f, 0f)))
	}

	@Test fun openMouthYawRespectsTurnStrength() {
		val marks = openMouthFace()
		val zero = FaceTurn(marks, TurnStyle.Setting(turn = 0f))
		val rule = mouth(zero, marks, 45f, 0f)
		for (point in marks.points.filter { it.part == "mouth" }) {
			val (x, y) = rule(point.x, point.y)
			assertEquals(point.x, x, 1e-4f)
			assertEquals(point.y, y, 1e-4f)
		}
		fun width(strength: Float): Float {
			val turn = FaceTurn(marks, TurnStyle.Setting(turn = strength))
			val shape = mouth(turn, marks, 45f, 0f)
			return shape(28f, 100f).first - shape(-28f, 100f).first
		}
		val half = width(.5f)
		assertTrue(half > width(1f) && half < 56f, "the opening narrows less at half strength: $half")
	}

	@Test fun defaultMouthYawRespondsToDifferentOpeningProportions() {
		fun upperOffset(height: Float): Float {
			val marks = openMouthFace(height)
			val turned = mouth(FaceTurn(marks), marks, 45f, 0f)
			val left = turned(-28f, 100f).first
			val right = turned(28f, 100f).first
			val upper = turned(0f, 100f - height / 2f).first
			return (upper - (left + right) / 2f) / (right - left)
		}
		assertTrue(abs(upperOffset(14f) - upperOffset(32f)) > .003f,
			"shallow and tall openings receive distinct lip movement")
	}

	@Test fun eachMoveKeepsItsOwnTiming() {
		val style = TurnStyle.DEFAULT; val ends = style.ends(TurnStyle.Setting())
		val end = style.at(ends, 1f, 0f); val half = style.at(ends, 0.5f, 0f)
		// The eyes close up late in the turn; the middle goes about evenly.
		assertTrue(abs(half["eye_gap"]) < abs(end["eye_gap"]) * 0.4f, "${half["eye_gap"]} / ${end["eye_gap"]}")
		assertTrue(abs(half["slide_x"] / end["slide_x"] - 0.5f) < 0.15f, "${half["slide_x"]} / ${end["slide_x"]}")
	}

	@Test fun theSettingScalesAndShapesTheTurn() {
		val marks = face()
		val base = FaceTurn(marks).pose(45f, 0f).slide.first
		val half = FaceTurn(marks, TurnStyle.Setting(turn = 0.5f)).pose(45f, 0f).slide.first
		assertTrue(abs(half - base / 2f) < 1e-3f)
		val style = TurnStyle.DEFAULT
		val held = style.at(style.ends(TurnStyle.Setting(sliders = mapOf("gaze_hold" to 1f))), 1f, 0f)["far_iris_u"]
		val loose = style.at(style.ends(TurnStyle.Setting(sliders = mapOf("gaze_hold" to -1f))), 1f, 0f)["far_iris_u"]
		assertTrue(held != loose, "the gaze slider moves the iris in its opening")
	}

	@Test fun everyPresetIsAWholeStyleOfItsOwn() {
		val style = TurnStyle.DEFAULT
		val base = style.at(style.ends(TurnStyle.Setting()), 1f, 0f)
		val seen = HashSet<List<Float>>()
		for (preset in TurnStyle.PRESETS) {
			val ends = style.ends(TurnStyle.Setting(preset = preset))
			val m = style.at(ends, 1f, 0f)
			val key = listOf(m["slide_x"], m["eye_gap"], m["far_eA00"], m["far_iris_u"])
			assertTrue(key != listOf(base["slide_x"], base["eye_gap"], base["far_eA00"], base["far_iris_u"]), "$preset differs from the default")
			assertTrue(seen.add(key), "$preset differs from the others")
			assertTrue(m["slide_x"] > 0f, "$preset turns toward the turn")
			assertTrue(style.hairAt(ends, 1f, 0f)["front_x_0"] != 0f, "$preset has hair fields")
		}
		val gaze = io.github.psd2live.core.recipe.RigModules.turnStyle(PipelineConfig(recipe = io.github.psd2live.core.recipe.RigRecipe(true,
			modules = mapOf("face.depth-turn" to io.github.psd2live.core.recipe.ModuleChoice(values = mapOf("style" to 4f))))))
		assertEquals("nod", gaze.preset)
		assertEquals(null, io.github.psd2live.core.recipe.RigModules.turnStyle(PipelineConfig()).preset)
	}

	@Test fun aFringeOverTheForeheadMovesSmoothlyDownTheHead() {
		val turn = FaceTurn(face()); val pose = turn.pose(45f, 0f)
		// A column of fringe from the top of the head down past the eyes: its move changes little from row to row.
		val ys = (0..30).map { -260f + it * 10f }
		val column = FloatArray(ys.size * 2) { if (it % 2 == 0) 0f else ys[it / 2] }
		val moved = HairTurn.place(column, true, turn, pose)
		val dx = ys.indices.map { moved[it * 2] }
		for (i in 1 until dx.size) assertTrue(abs(dx[i] - dx[i - 1]) < 0.02f * turn.unit, "row $i: ${dx[i - 1]} -> ${dx[i]}")
		assertTrue(dx.last() > dx.first(), "it goes further over the face than over the crown")
	}

	@Test fun hairBeforeTheFaceGoesWithTheTurnAndHangsLessBelowTheChin() {
		val turn = FaceTurn(face()); val pose = turn.pose(45f, 0f)
		// A fringe over the forehead and a lock hanging from beside the face to well below the chin.
		val fringe = floatArrayOf(-60f, -150f, 0f, -150f, 60f, -150f, -60f, -80f, 0f, -80f, 60f, -80f)
		val moved = HairTurn.place(fringe, true, turn, pose)
		for (i in 0 until 6) assertTrue(moved[i * 2] > fringe[i * 2], "the fringe goes with the face: $i")
		val lock = floatArrayOf(-150f, 0f, -140f, 0f, -150f, 400f, -140f, 400f)
		val hung = HairTurn.place(lock, true, turn, pose)
		assertTrue(abs(hung[4] - lock[4]) < abs(hung[0] - lock[0]) + 1f, "what hangs low goes less")
	}
}
