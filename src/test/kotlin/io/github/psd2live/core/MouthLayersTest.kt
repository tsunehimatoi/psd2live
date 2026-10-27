package io.github.psd2live.core

import io.github.psd2live.core.fit.MouthFit
import io.github.psd2live.core.fit.MouthOptions
import io.github.psd2live.core.fit.MouthRig
import io.github.psd2live.core.fit.MouthRig.Follow
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
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

	@Test fun teethHangFromTheirLipUnsquashedLeavingTheOpeningToHideThem() {
		val rig = rig()
		// A smile flattens and curves the upper lip: each column of the upper teeth keeps to it, at its own height.
		val smile = rig.pose(1f, 0.5f)
		for (u in listOf(0.2f, 0.5f, 0.8f)) {
			val top = rig.moveAs(Follow.UPPER_TEETH, u, -0.14f, smile).second; val bottom = rig.moveAs(Follow.UPPER_TEETH, u, -0.06f, smile).second
			assertEquals(top, bottom, 1e-5f, "the upper teeth are squashed at $u")
			val lip = rig.moveAs(Follow.UPPER_LINE, u, MouthFit.sample(rig.fit.upper, u), smile).second
			assertTrue(top <= lip + 1e-5f, "a gap opens between the upper lip and its teeth at $u: teeth $top, lip $lip")
		}
		val smileLeft = rig.moveAs(Follow.UPPER_TEETH, 0.2f, -0.1f, smile).first; val smileRight = rig.moveAs(Follow.UPPER_TEETH, 0.8f, -0.1f, smile).first
		assertTrue(smileLeft < 0f && smileRight > 0f, "a smile's teeth spread toward its corners: $smileLeft, $smileRight")
		val lip = rig.moveAs(Follow.LOWER_LINE, 0.5f, 0.24f, shut).second
		val teeth = rig.moveAs(Follow.LOWER_TEETH, 0.5f, 0.2f, shut).second
		assertTrue(teeth < 0f && abs(teeth) < abs(lip), "the lower teeth rise less than the lower lip, which covers them: $teeth against $lip")
		// A pout keeps the teeth's width: the opening narrows over them instead.
		val pout = rig.pose(-1f, 1f)
		val left = rig.moveAs(Follow.UPPER_TEETH, 0.2f, -0.1f, pout).first; val right = rig.moveAs(Follow.UPPER_TEETH, 0.8f, -0.1f, pout).first
		assertEquals(left, right, 1e-5f, "a pout squeezes the teeth: $left against $right")
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
}
