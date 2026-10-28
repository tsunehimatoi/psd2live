package io.github.psd2live.core

import io.github.psd2live.core.fit.EyeFit
import io.github.psd2live.core.fit.FeatureTurn
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.test.Test
import kotlin.test.assertTrue

class FeatureTurnTest {
	/** The character's left eye, on the canvas's right: inner corner at (20, 0), outer at (80, 0), lids 12 above and below, iris 24 wide. */
	private val left = EyeFit(Side.LEFT, 20f, 0f, 1f, 0f, 60f,
		List(EyeFit.SAMPLES) { k -> val u = EyeFit.u(k); -0.2f * 4f * u * (1f - u) },
		List(EyeFit.SAMPLES) { k -> val u = EyeFit.u(k); 0.2f * 4f * u * (1f - u) }, null, Bounds(38f, -12f, 62f, 12f))
	private val still: (Float, Float) -> Pair<Float, Float> = { x, y -> x to y }

	private fun width(s: Float, from: Float, to: Float) =
		FeatureTurn.eye(left, still, s, 0f, 0f, 20f + 60f * to, 0f).first - FeatureTurn.eye(left, still, s, 0f, 0f, 20f + 60f * from, 0f).first

	@Test fun aTurnedEyeSqueezesAcrossNotAsAWhole() {
		// Turning toward the canvas's right this eye is the far one; toward the left, the near one.
		val farInner = width(1f, 0f, 0.25f) / 15f; val farOuter = width(1f, 0.75f, 1f) / 15f
		assertTrue(farOuter < farInner - 0.15f, "the far eye tightens most at its outer end: $farOuter against $farInner")
		val nearInner = width(-1f, 0f, 0.25f) / 15f; val nearOuter = width(-1f, 0.75f, 1f) / 15f
		assertTrue(nearOuter > 1f && nearInner < 1f, "the near eye opens at its outer end and tightens at its inner: $nearOuter, $nearInner")
	}

	@Test fun anEyesTopLeansIntoTheTurn() {
		val top = FeatureTurn.eye(left, still, 1f, 0f, 0f, 50f, -12f).first - 50f
		val bottom = FeatureTurn.eye(left, still, 1f, 0f, 0f, 50f, 12f).first - 50f
		assertTrue(top > bottom + 3f, "the top goes further toward the turn than the bottom: $top / $bottom")
	}

	@Test fun theIrisHoldsItsShapeAsTheOpeningNarrows() {
		fun irisWidth(s: Float) = FeatureTurn.iris(left, still, s, 0f, 0f, 62f, 0f).first - FeatureTurn.iris(left, still, s, 0f, 0f, 38f, 0f).first
		val opening = width(1f, 0f, 1f) / 60f
		val iris = irisWidth(1f) / 24f
		assertTrue(iris > opening + 0.1f, "the far iris narrows less than its opening: $iris against $opening")
	}

	@Test fun lookingUpBendsTheEyesLineAndLookingDownClosesItFromTheTop() {
		fun tilt(t: Float): Double { val a = FeatureTurn.eye(left, still, 0f, t, 0f, 20f, 0f); val b = FeatureTurn.eye(left, still, 0f, t, 0f, 80f, 0f)
			return Math.toDegrees(atan2((b.second - a.second).toDouble(), (b.first - a.first).toDouble())) }
		assertTrue(tilt(1f) > 2.0, "looking up the outer corner drops (canvas y down): ${tilt(1f)}")
		assertTrue(tilt(-1f) < -2.0, "looking down it rises: ${tilt(-1f)}")
		// Against the eye's middle line, which the bend moves too.
		val middle = FeatureTurn.eye(left, still, 0f, -1f, 0f, 50f, 0f).second
		val topDrop = (FeatureTurn.eye(left, still, 0f, -1f, 0f, 50f, -12f).second - middle) - -12f
		val bottomRise = 12f - (FeatureTurn.eye(left, still, 0f, -1f, 0f, 50f, 12f).second - middle)
		assertTrue(topDrop > bottomRise * 2f, "looking down the opening closes from its top: $topDrop against $bottomRise")
		// A pure look does not roll the iris.
		val a = FeatureTurn.iris(left, still, 0f, 1f, 0f, 38f, 0f); val b = FeatureTurn.iris(left, still, 0f, 1f, 0f, 62f, 0f)
		assertTrue(abs(a.second - b.second) < 1.5f, "the iris stays level looking up: ${a.second} / ${b.second}")
	}
}
