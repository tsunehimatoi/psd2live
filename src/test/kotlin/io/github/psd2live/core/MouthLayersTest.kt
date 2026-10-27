package io.github.psd2live.core

import io.github.psd2live.core.fit.FittedMouth
import io.github.psd2live.core.fit.MouthFit
import io.github.psd2live.core.fit.MouthFitting
import io.github.psd2live.core.fit.MouthOptions
import io.github.psd2live.core.fit.MouthRig
import io.github.psd2live.core.fit.MouthRig.Follow
import io.github.psd2live.project.WorkspaceSourceLayer
import org.umamo.format.art.ChannelMask
import org.umamo.format.art.LayerBlend
import org.umamo.format.art.LayerBounds
import org.umamo.format.art.LayerId
import org.umamo.format.art.LayerRaster
import org.umamo.format.art.SourceLayerKind
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A mouth drawn in layers: each layer follows the lips its own way, and the names that say which layer is which. */
class MouthLayersTest {
	/** A drawn open mouth a unit wide on the canvas's level, opening 0.4 at its middle as an oval, its lip lines 0.02 thick. */
	private fun rig(inside: Float = 0.5f): MouthRig {
		val n = MouthFit.SAMPLES
		val upper = List(n) { k -> val u = MouthFit.u(k); -0.15f * 2f * sqrt(u * (1f - u)) }
		val lower = List(n) { k -> val u = MouthFit.u(k); 0.25f * 2f * sqrt(u * (1f - u)) }
		return MouthRig(MouthFit(0f, 0f, 1f, 0f, 1f, upper, lower), MouthOptions(inside = inside), lineWidth = 0.02f)
	}

	private val shut get() = rig().pose(0f, 0f)

	@Test fun theDrawnPoseLeavesEveryLayerWhereItIs() {
		val rig = rig()
		val drawn = rig.pose(0f, 1f)
		for (follow in Follow.entries) for ((u, v) in listOf(0.5f to -0.17f, 0.3f to 0.05f, 0.5f to 0.27f, 0.5f to -0.4f, -0.1f to 0f)) {
			val (du, dv) = rig.moveAs(follow, u, v, drawn)
			assertTrue(abs(du) < 1e-4f && abs(dv) < 1e-4f, "$follow moves ($u, $v) by ($du, $dv) at the drawing")
		}
	}

	@Test fun shutTheTwoLipLinesMeetInOneAndKeepTheirThickness() {
		val rig = rig()
		// The upper line's band just inside the upper edge, the lower line's just inside the lower edge.
		fun at(follow: Follow, v: Float) = v + rig.moveAs(follow, 0.5f, v, shut).second
		val upperTop = at(Follow.UPPER_LINE, -0.15f); val upperBottom = at(Follow.UPPER_LINE, -0.13f)
		val lowerTop = at(Follow.LOWER_LINE, 0.23f); val lowerBottom = at(Follow.LOWER_LINE, 0.25f)
		assertEquals(0.02f, upperBottom - upperTop, 1e-4f, "the upper line keeps its thickness")
		assertEquals(0.02f, lowerBottom - lowerTop, 1e-4f, "the lower line keeps its thickness")
		assertTrue(abs((upperTop + upperBottom) - (lowerTop + lowerBottom)) / 2f < 0.02f,
			"the lines meet: upper $upperTop..$upperBottom, lower $lowerTop..$lowerBottom")
	}

	@Test fun atRestTheLinesLieWhereTheyMeetSoTheHeadTurnsThemAsOne() {
		val rig = rig()
		// The bottom of the upper line and the top of the lower one, drawn apart, rest together.
		val upper = rig.atRest(floatArrayOf(0.5f, -0.13f), Follow.UPPER_LINE)
		val lower = rig.atRest(floatArrayOf(0.5f, 0.23f), Follow.LOWER_LINE)
		assertEquals(0.5f, upper[0], 1e-4f)
		// Met, the upper line's bottom lies a line's width below the lower line's top.
		assertEquals(0.02f, upper[1] - lower[1], 0.01f, "rest lines: upper ends ${upper[1]}, lower starts ${lower[1]}")
	}

	@Test fun teethMoveWholeAndStayPutWhileTheLipsPartOffThem() {
		val rig = rig()
		// A smile curves the lips; the teeth move whole, every column alike, at their drawn width.
		val smile = rig.pose(1f, 0.5f)
		val moves = listOf(0.2f, 0.5f, 0.8f).flatMap { u -> listOf(-0.14f, -0.06f).map { v -> rig.moveAs(Follow.UPPER_TEETH, u, v, smile) } }
		assertTrue(moves.maxOf { it.second } - moves.minOf { it.second } < 1e-5f, "the upper teeth bend or squash in a smile: $moves")
		assertTrue(moves.all { abs(it.first) < 1e-5f }, "the teeth keep their drawn width: $moves")
		// Over the first half of the opening the teeth stay where they sit shut; the opening uncovers them.
		for (open in listOf(0.05f, 0.25f, 0.45f)) assertEquals(rig.moveAs(Follow.UPPER_TEETH, 0.5f, -0.1f, shut).second,
			rig.moveAs(Follow.UPPER_TEETH, 0.5f, -0.1f, rig.pose(0f, open)).second, 1e-4f, "the upper teeth move at $open")
		// Never lower under the upper lip than drawn: no gap above the teeth.
		for (pose in listOf(shut, rig.pose(1f, 0.5f), rig.pose(1f, 0.25f), rig.pose(0f, 0.3f), rig.pose(-1f, 1f)))
			assertTrue(rig.moveAs(Follow.UPPER_TEETH, 0.5f, -0.1f, pose).second <= rig.edges(pose, 0.5f).first - MouthFit.sample(rig.fit.upper, 0.5f) + 1e-5f,
				"a gap opens above the teeth in $pose")
		val lowerLip = rig.moveAs(Follow.LOWER_LINE, 0.5f, 0.24f, shut).second
		val lowerTeeth = rig.moveAs(Follow.LOWER_TEETH, 0.5f, 0.2f, shut).second
		assertTrue(lowerTeeth < 0f && abs(lowerTeeth) < abs(lowerLip), "the lower teeth rise less than the lower lip, which covers them: $lowerTeeth against $lowerLip")
		// A pout keeps the teeth's width: the opening narrows over them instead.
		val pout = rig.pose(-1f, 1f)
		val left = rig.moveAs(Follow.UPPER_TEETH, 0.2f, -0.1f, pout).first; val right = rig.moveAs(Follow.UPPER_TEETH, 0.8f, -0.1f, pout).first
		assertEquals(left, right, 1e-5f, "a pout squeezes the teeth: $left against $right")
		// Rigs that widen the teeth in a smile do it evenly.
		val wide = MouthRig(rig.fit, MouthOptions(teethWiden = 0.2f), lineWidth = 0.02f)
		val l = wide.moveAs(Follow.UPPER_TEETH, 0.2f, -0.1f, wide.pose(1f, 1f)).first; val r = wide.moveAs(Follow.UPPER_TEETH, 0.8f, -0.1f, wide.pose(1f, 1f)).first
		assertTrue(l < 0f && abs(l + r) < 1e-5f, "a widening smile spreads the teeth evenly: $l, $r")
	}

	@Test fun aSmileShowsTheUpperTeethAtMostDownToTheMiddleOfItsOpening() {
		val rig = rig()
		// A row reaching 0.25 below the drawn upper edge at the middle, more than half of most smiles' opening.
		val depth = 0.25f
		for (open in listOf(0.1f, 0.3f, 0.5f, 0.8f)) {
			val smile = rig.pose(1f, open)
			val (top, bottom) = rig.edges(smile, 0.5f)
			val teethBottom = MouthFit.sample(rig.fit.upper, 0.5f) + depth + rig.teeth(0.5f, smile, upper = true, depth = depth).second
			assertTrue(teethBottom <= top + 0.5f * (bottom - top) + 1e-4f, "at $open the teeth reach $teethBottom in an opening $top..$bottom")
		}
		// The drawn pose keeps its teeth.
		assertEquals(0f, rig.teeth(0.5f, rig.pose(0f, 1f), upper = true, depth = depth).second, 1e-5f)
	}

	@Test fun theOpeningsEdgeStaysUnderItsLineInEveryPose() {
		val rig = rig()
		for (pose in listOf(shut, rig.pose(1f, 0.5f), rig.pose(-1f, 1f), rig.pose(0f, 0.25f), rig.pose(1f, 1f)))
			for (u in listOf(0.05f, 0.15f, 0.3f, 0.5f, 0.7f, 0.85f, 0.95f)) {
				// Half a line inside each drawn edge: the line's middle, where the opening's edge is drawn.
				val top = MouthFit.sample(rig.fit.upper, u) + 0.01f; val bottom = MouthFit.sample(rig.fit.lower, u) - 0.01f
				for ((v, line) in listOf(top to Follow.UPPER_LINE, bottom to Follow.LOWER_LINE)) {
					val opening = rig.moveAs(Follow.OPENING, u, v, pose); val lip = rig.moveAs(line, u, v, pose)
					assertTrue(abs(opening.first - lip.first) < 0.004f && abs(opening.second - lip.second) < 0.004f,
						"at $u in $pose the opening's edge moves $opening, its line $lip")
				}
			}
	}

	@Test fun theLinesEndsAtACornerMoveTogetherInARoundMouth() {
		val rig = rig()
		// A point where the two ends overlap, just inside a corner: both lines carry it to the same place.
		val round = rig.pose(-1f, 1f)
		val upper = rig.moveAs(Follow.UPPER_LINE, 0.01f, -0.005f, round); val lower = rig.moveAs(Follow.LOWER_LINE, 0.01f, -0.005f, round)
		assertTrue(abs(upper.first - lower.first) < 0.004f && abs(upper.second - lower.second) < 0.004f, "the ends part: $upper against $lower")
	}

	@Test fun linesDrawnAsOneAtTheCornersStayOneShut() {
		val rig = rig()
		val upper = rig.moveAs(Follow.UPPER_LINE, 0f, 0f, shut); val lower = rig.moveAs(Follow.LOWER_LINE, 0f, 0f, shut)
		assertEquals(upper.second, lower.second, 1e-5f, "the corner's one stroke parts into two")
	}

	@Test fun aLipsSkinMarginRidesLessTheFurtherOutItLies() {
		val rig = rig()
		val near = rig.moveAs(Follow.LOWER_LIP, 0.5f, 0.27f, shut).second
		val far = rig.moveAs(Follow.LOWER_LIP, 0.5f, 0.8f, shut).second
		assertTrue(near < 0f && far < 0f && abs(far) < abs(near) * 0.5f, "the margin rides $far, the lip $near")
	}

	@Test fun theInsideKeepsItsShareOfTheOpeningBehindTheLips() {
		val tight = rig(inside = 0f).moveAs(Follow.INSIDE, 0.5f, 0.2f, shut).second
		val half = rig(inside = 0.5f).moveAs(Follow.INSIDE, 0.5f, 0.2f, shut).second
		val kept = rig(inside = 1f).moveAs(Follow.INSIDE, 0.5f, 0.2f, shut).second
		assertEquals(tight * 0.5f, half, 1e-5f)
		assertEquals(0f, kept, 1e-5f)
		// By a corner, where the lips are thin, it closes whole.
		val corner = rig(inside = 1f).moveAs(Follow.INSIDE, 0f, 0.01f, shut).second
		assertEquals(rig(inside = 0f).moveAs(Follow.INSIDE, 0f, 0.01f, shut).second, corner, 1e-5f)
	}

	@Test fun aMouthReachingPastItsDrawingOpensOnIntoAShout() {
		val shout = MouthRig(rig().fit, MouthOptions(reach = 1.6f))
		assertEquals(1.6f, shout.openKeys.last())
		val drawn = shout.edges(shout.pose(0f, 1f), 0.5f); val wide = shout.edges(shout.pose(0f, 1.6f), 0.5f)
		assertTrue(wide.first < drawn.first && wide.second - drawn.second > drawn.first - wide.first,
			"past the drawing the lower lip drops further than the upper one rises: $drawn to $wide")
		assertEquals(MouthRig.OPEN_KEYS.toList(), rig().openKeys.toList(), "without reach the keys stay")
	}

	@Test fun lipLayersAreNamedApartFromTheMouthTheyBelongTo() {
		for ((name, role) in listOf("上唇" to PartRole.UPPER_LIP, "下唇" to PartRole.LOWER_LIP, "上唇线" to PartRole.UPPER_LINE, "下唇線" to PartRole.LOWER_LINE,
			"upper lip" to PartRole.UPPER_LIP, "lower lip line" to PartRole.LOWER_LINE, "윗입술" to PartRole.UPPER_LIP)) {
			assertEquals(SemanticTag.MOUTH, LayerClassifier.classify(name).tag, name)
			assertEquals(role, PartRoles.fromName(name, "", SemanticTag.MOUTH), name)
		}
		assertEquals(null, PartRoles.fromName("口腔", "", SemanticTag.MOUTH))
	}

	private fun drawn(id: String, x0: Int, y0: Int, x1: Int, y1: Int, role: PartRole? = null, clipped: Boolean = false): ClassifiedLayer {
		val width = 64; val height = 64
		val rgba = ByteArray(width * height * 4)
		for (y in y0 until y1) for (x in x0 until x1) { val o = (y * width + x) * 4; rgba[o] = 90; rgba[o + 3] = -1 }
		val source = WorkspaceSourceLayer(LayerId(id), id, "", SourceLayerKind.Raster, true, 0, LayerBounds(0, 0, width, height), 1f,
			clipped, LayerBlend.Normal, ChannelMask.ALL, LayerRaster(width, height, rgba), null, null, false)
		val bounds = Bounds(x0.toFloat(), y0.toFloat(), x1.toFloat(), y1.toFloat())
		return ClassifiedLayer(source, LayerSemantic(SemanticTag.MOUTH, normalizedName = id, confidence = 1f, role = role), bounds,
			bounds.centerX, bounds.centerY, (x1 - x0) * (y1 - y0))
	}

	@Test fun aBaseClippedOntoTheOpeningIsNotItsShapeAndHardlyMoves() {
		val opening = drawn("opening", 10, 20, 54, 40)
		val upper = drawn("upper line", 10, 19, 54, 21, PartRole.UPPER_LINE); val lower = drawn("lower line", 10, 39, 54, 41, PartRole.LOWER_LINE)
		// Drawn larger than any opening, clipped onto it: the largest mouth layer, but not the mouth's shape.
		val base = drawn("base", 2, 8, 62, 60, clipped = true)
		val layers = listOf(opening, upper, lower, base)
		val result = MouthFitting.fit(layers, 1f to 0f) { x, y -> x to y }
		val fit = assertNotNull(result.fit, result.reason)
		assertTrue(abs(fit.width - 44f) < 3f, "the opening's width is read, not the base's: ${fit.width}")
		val mouth = FittedMouth(result, MouthRig(fit, MouthOptions()), layers = layers)
		assertEquals(Follow.DEEP, mouth.follow(base))
		assertEquals(Follow.OPENING, mouth.follow(opening))
	}
}
