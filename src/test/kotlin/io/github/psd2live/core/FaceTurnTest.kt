package io.github.psd2live.core

import io.github.psd2live.core.fit.FaceLandmarks
import io.github.psd2live.core.fit.FaceTurn
import io.github.psd2live.core.fit.Landmark
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue

class FaceTurnTest {
	/**
	 * A face 260 wide and 300 tall about (0, 0), y down, its outline in 240 points clockwise from the right: round, or
	 * [square] with flat sides and a flat chin meeting in corners. Its points sit on the outline where the reading would put them.
	 */
	private fun face(square: Boolean): FaceLandmarks {
		val n = 240
		val contour = (0 until n).map { k ->
			val a = 2 * PI * k / n
			val c = cos(a); val s = sin(a)
			if (!square) (130 * c).toFloat() to (150 * s + if (s > 0) 30 * s * s else 0.0).toFloat()
			else {
				// A superellipse: flat sides and bottom, round corners.
				val e = 0.35
				(130 * Math.signum(c) * Math.pow(abs(c), e)).toFloat() to (150 * Math.signum(s) * Math.pow(abs(s), e)).toFloat()
			}
		}
		fun on(angle: Double) = contour[((angle / 360.0 * n).toInt() % n + n) % n]
		val points = ArrayList<Landmark>()
		fun add(id: String, p: Pair<Float, Float>) { points += Landmark(id, id.substringBeforeLast('.').substringBeforeLast('.'), p.first, p.second) }
		add("face.chin", on(90.0)); add("face.top", on(270.0))
		// The canvas's right is the character's left.
		add("face.jaw.L", on(if (square) 45.0 else 50.0)); add("face.jaw.R", on(if (square) 135.0 else 130.0))
		add("face.cheekJaw.L", on(25.0)); add("face.cheekJaw.R", on(155.0))
		add("face.cheekbone.L", on(5.0)); add("face.cheekbone.R", on(175.0))
		add("face.eyeLine.L", on(355.0)); add("face.eyeLine.R", on(185.0))
		add("face.temple.L", on(320.0)); add("face.temple.R", on(220.0))
		add("eye.L.inner", 20f to -10f); add("eye.L.outer", 80f to -12f)
		add("eye.R.inner", -20f to -10f); add("eye.R.outer", -80f to -12f)
		add("face.browCentre", 0f to -50f); add("nose.tip", 0f to 40f); add("mouth.upperMid", 0f to 85f); add("face.chinCentre", 0f to 120f)
		return FaceLandmarks(points, contour, emptyMap())
	}

	private fun FaceLandmarks.at(id: String) = this[id]!!.let { it.x to it.y }

	@Test fun turnedTheSideTurningAwaySweepsInStraightAndTheOtherHolds() {
		val marks = face(square = false)
		val map = FaceTurn(marks).map(30f, 0f)
		// Turning toward the canvas's right: the character's right side (R, canvas left) turns away.
		fun dx(id: String) = map(marks.at(id).first, marks.at(id).second).first - marks.at(id).first
		assertTrue(dx("face.cheekbone.R") > dx("face.cheekbone.L") * 1.8f, "the side turning away goes further: ${dx("face.cheekbone.R")} / ${dx("face.cheekbone.L")}")
		assertTrue(dx("face.chin") > dx("face.jaw.R") && dx("face.chin") > dx("face.jaw.L"), "the chin swings past both jaw angles")
		assertTrue(dx("nose.tip") > dx("face.browCentre"), "the nose leads")
		// From the jaw angle to the temple the side turning away is one straight line.
		val jaw = map(marks.at("face.jaw.R").first, marks.at("face.jaw.R").second); val temple = map(marks.at("face.temple.R").first, marks.at("face.temple.R").second)
		val cheek = map(marks.at("face.cheekbone.R").first, marks.at("face.cheekbone.R").second)
		val off = abs((temple.first - jaw.first) * (cheek.second - jaw.second) - (temple.second - jaw.second) * (cheek.first - jaw.first)) /
			hypot(temple.first - jaw.first, temple.second - jaw.second)
		assertTrue(off < 4f, "the cheek lies on the line from the jaw angle to the temple: $off off it")
		// A pure turn does not roll: the eye line stays level.
		val l = map(80f, -12f); val r = map(-80f, -12f)
		assertTrue(abs(l.second - r.second) < 1.5f, "the eyes stay level: ${l.second} / ${r.second}")
	}

	@Test fun lookingUpTheChinFlattensAndLookingDownTheForeheadDoes() {
		val marks = face(square = false)
		val up = FaceTurn(marks).map(0f, 30f); val down = FaceTurn(marks).map(0f, -30f)
		fun dy(m: (Float, Float) -> Pair<Float, Float>, id: String) = m(marks.at(id).first, marks.at(id).second).second - marks.at(id).second
		assertTrue(dy(up, "face.chin") < dy(up, "face.jaw.L") - 5f, "looking up the chin rises past the jaw angles")
		assertTrue(dy(down, "face.top") > dy(down, "face.temple.L") + 5f, "looking down the forehead's top drops past its temples")
		assertTrue(dy(down, "face.chin") > dy(down, "face.jaw.L"), "looking down the jaw's sides rise past the chin")
		assertTrue(dy(down, "mouth.upperMid") < dy(down, "face.browCentre"), "looking down the lower face drops less: it shortens")
	}

	@Test fun aCornerPoseRollsTheFarSideDownLookingUpAndUpLookingDown() {
		val marks = face(square = false)
		fun tilt(m: (Float, Float) -> Pair<Float, Float>): Float { val l = m(80f, -12f); val r = m(-80f, -12f); return l.second - r.second }
		// Turned toward the canvas's right the far side is the character's left (canvas right): y grows downward.
		assertTrue(tilt(FaceTurn(marks).map(30f, 30f)) > 2f, "turned and looking up, the far side drops")
		assertTrue(tilt(FaceTurn(marks).map(30f, -30f)) < -2f, "turned and looking down, the far side rises")
	}

	@Test fun aSquareJawKeepsItsCornerTurned() {
		val marks = face(square = true)
		val map = FaceTurn(marks).map(30f, 0f)
		// The far jaw (the character's left) stays a corner: the outline still bends sharply there.
		val n = marks.contour.size
		val i = marks.contour.indices.minBy { hypot(marks.contour[it].first - marks["face.jaw.L"]!!.x, marks.contour[it].second - marks["face.jaw.L"]!!.y) }
		fun bend(points: List<Pair<Float, Float>>): Double {
			val a = points[(i - 8 + n) % n]; val b = points[i]; val c = points[(i + 8) % n]
			var d = abs(atan2((c.second - b.second).toDouble(), (c.first - b.first).toDouble()) - atan2((b.second - a.second).toDouble(), (b.first - a.first).toDouble()))
			if (d > PI) d = 2 * PI - d
			return d
		}
		val turned = marks.contour.map { map(it.first, it.second) }
		assertTrue(bend(turned) > bend(marks.contour) * 0.7, "the far jaw keeps its corner: ${bend(turned)} of ${bend(marks.contour)}")
	}
}
