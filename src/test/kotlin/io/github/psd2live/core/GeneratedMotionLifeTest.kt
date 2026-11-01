package io.github.psd2live.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeneratedMotionLifeTest {
	@Test fun aNodDipsFasterThanItRisesAndTheBodyFollows() {
		val tracks = MotionGenerator.nodTracks(1)
		val head = tracks.single { it.parameterId == "ParamAngleY" }
		val body = tracks.single { it.parameterId == "ParamBodyAngleY" }
		assertEquals(MotionInterpolation.BEZIER, head.keys.first().interpolation)
		val dip = head.keys.minBy { it.value }
		val anticipate = head.keys.filter { it.time < dip.time }.maxBy { it.value }
		val rise = head.keys.filter { it.time > dip.time }.maxBy { it.value }
		assertTrue(anticipate.value > 2f, "the head looks up before it nods")
		assertTrue(dip.value < -15f, "the nod dips")
		assertTrue(rise.value > 3f, "the head overshoots on the way up")
		assertTrue(dip.time - anticipate.time < rise.time - dip.time, "the dip is quicker than the rise")
		assertTrue(body.keys.minBy { it.value }.time > dip.time, "the body follows the head")
		assertTrue(tracks.single { it.parameterId == "ParamEyeLOpen" }.keys.minBy { it.value }.value < 0.7f, "the eyes squint into the dip")
	}

	@Test fun aShakeEasesNarrowsAndDoesNotMetronome() {
		val head = MotionGenerator.shakeTracks(2).single { it.parameterId == "ParamAngleX" }
		assertEquals(MotionInterpolation.BEZIER, head.keys[1].interpolation)
		val swings = head.keys.filter { abs(it.value) > 10f }
		assertTrue(swings.size >= 3)
		assertTrue(abs(swings.first().value) > abs(swings.last().value), "later swings are narrower")
		val gaps = swings.zipWithNext { a, b -> b.time - a.time }
		assertTrue(gaps.zipWithNext().any { (a, b) -> abs(a - b) > 0.02f }, "the turns are not the same length")
	}

	@Test fun theIdleHeadDoesNotRepeatOneBreath() {
		val head = MotionSynth.idle(null, emptySet()).single { it.parameterId == "ParamAngleY" }
		val early = MotionClips.sample(head, 1.15f)
		val later = MotionClips.sample(head, 5.4f)
		assertTrue(abs(early - later) > 0.4f, "two inhales do not pose the head the same way")
		assertTrue(abs(early) > 1f && abs(later) > 0.5f, "the head actually moves")
	}
}
