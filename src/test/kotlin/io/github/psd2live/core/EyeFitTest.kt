package io.github.psd2live.core

import io.github.psd2live.core.fit.BiharmonicField
import io.github.psd2live.core.fit.EyeFit
import io.github.psd2live.core.fit.EyeFitting
import io.github.psd2live.core.fit.EyeRig
import io.github.psd2live.core.fit.EyelidModel
import io.github.psd2live.core.fit.ThinPlateSpline
import io.github.psd2live.project.WorkspaceSourceLayer
import org.umamo.format.art.ChannelMask
import org.umamo.format.art.LayerBlend
import org.umamo.format.art.LayerBounds
import org.umamo.format.art.LayerId
import org.umamo.format.art.LayerRaster
import org.umamo.format.art.SourceLayerKind
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The feature fit of the eyes and what it is built of: the spline, the field, the lid model and the eye's rig. */
class EyeFitTest {
	@Test fun theSplineCarriesEveryPointExactlyAndKeepsAnAffineMapAffine() {
		val from = listOf(0f to 0f, 1f to 0f, 0f to 1f, 1f to 1f, 0.5f to 0.4f)
		val to = listOf(2f to 1f, 4f to 1.5f, 1.5f to 3f, 3.6f to 3.2f, 2.9f to 2.1f)
		val tps = ThinPlateSpline(from, to)
		for ((a, b) in from.zip(to)) {
			val (x, y) = tps.map(a.first, a.second)
			assertTrue(abs(x - b.first) < 1e-3f && abs(y - b.second) < 1e-3f, "$a maps to ($x, $y), not $b")
		}
		val affine = ThinPlateSpline(from, from.map { (x, y) -> (2f * x + 1f) to (y - 0.5f * x) })
		val (x, y) = affine.map(0.3f, 0.8f)
		assertTrue(abs(x - 1.6f) < 1e-3f && abs(y - 0.65f) < 1e-3f, "an affine map bends: ($x, $y)")
	}

	@Test fun theFieldHoldsItsFixedNodesAndEasesToNothingAtTheBorder() {
		val probe = BiharmonicField(21, 21, 0f, 0f, 1f, 1f, emptyMap())
		val centre = probe.nearest(0.5f, 0.5f)
		val field = BiharmonicField(21, 21, 0f, 0f, 1f, 1f, mapOf(centre to (0f to 1f)))
		assertEquals(1f, field.at(0.5f, 0.5f).second, 1e-4f)
		val near = field.at(0.5f, 0.6f).second; val far = field.at(0.5f, 0.9f).second
		assertTrue(near in far..1f && far >= -0.05f && field.at(0.5f, 0.98f).second < far, "the field does not fall off smoothly: near $near, far $far")
		assertTrue(abs(field.at(0.5f, 0.6f).second - field.at(0.5f, 0.4f).second) < 1e-3f, "the field is not symmetric")
	}

	@Test fun theLidModelKeepsAnUpwardArcAboveTheCornersAndTurnsItBelow() {
		val model = EyelidModel()
		val open = model.angleFor(-0.4f)
		val shut = model.angleFor(0.15f)
		assertEquals(-0.4f, model.edge(0.5f, open), 1e-3f)
		assertEquals(0f, model.edge(0f, open), 1e-4f, "the corners stay on the corners' line")
		assertTrue(model.edge(0.25f, open) > model.edge(0.5f, open), "an open lid arcs up")
		assertTrue(model.edge(0.25f, shut) < model.edge(0.5f, shut), "a shut lid arcs down")
	}

	private fun ellipseEye(): ClassifiedLayer {
		val width = 80; val height = 60
		val rgba = ByteArray(width * height * 4)
		for (y in 0 until height) for (x in 0 until width) {
			val dx = (x + 0.5f - 40f) / 30f; val dy = (y + 0.5f - 30f) / 14f
			if (dx * dx + dy * dy <= 1f) { val o = (y * width + x) * 4; rgba[o] = -1; rgba[o + 1] = -1; rgba[o + 2] = -1; rgba[o + 3] = -1 }
		}
		val source = WorkspaceSourceLayer(LayerId("white_l"), "white_l", "", SourceLayerKind.Raster, true, 0, LayerBounds(100, 50, width, height), 1f,
			false, LayerBlend.Normal, ChannelMask.ALL, LayerRaster(width, height, rgba), null, null, false)
		val bounds = Bounds(110f, 66f, 170f, 94f)
		return ClassifiedLayer(source, LayerSemantic(SemanticTag.EYEWHITE, Side.LEFT, normalizedName = "white", confidence = 1f), bounds, 140f, 80f, 1300)
	}

	@Test fun anEyeWhitesOutlineGivesItsCornersAndLidsAndTheRigClosesThem() {
		val result = EyeFitting.fit(listOf(ellipseEye()), faceCenterX = 60f) { x, y -> x to y }
		val fit = assertNotNull(result.fits[Side.LEFT], "the eye was not read: ${result.reasons}")
		assertTrue(abs(fit.originX - 110.5f) < 2f && abs(fit.width - 59f) < 3f, "inner corner ${fit.originX}, width ${fit.width}")
		val mid = EyeFit.SAMPLES / 2
		assertTrue(abs(fit.upper[mid] + 14f / fit.width) < 0.05f && abs(fit.lower[mid] - 14f / fit.width) < 0.05f,
			"lid edges ${fit.upper[mid]} / ${fit.lower[mid]}")

		val rig = EyeRig(fit, 0.25f, 1f, 0.6f)
		for (mesh in EyeRig.EyeMesh.entries) assertEquals(0f to 0f, rig.delta(mesh, 140f, 70f, 1f), "$mesh moves when open")
		val heights = rig.keys.map { rig.upper(it)[mid] }
		assertTrue(heights.zipWithNext().all { (a, b) -> a >= b }, "the lid does not come down steadily: $heights")
		val crease = rig.upper(rig.crease)
		assertTrue(abs(crease[mid]) < 0.02f, "at the crease the lid's middle is on the corners' line: ${crease[mid]}")
		assertTrue(rig.upper(0.4f).let { it[mid / 2] > it[mid] }, "a lowered lid keeps its upward arc")
		val closed = rig.upper(0f)
		val shut = rig.lower(0f)
		assertTrue((1 until EyeFit.SAMPLES - 1).all { closed[it] < shut[it] && shut[it] - closed[it] < 0.2f * rig.opening },
			"the closed lid lies just above the lower lid, which barely moves")
		val top = 68f + rig.delta(EyeRig.EyeMesh.WHITE, 140f, 68f, 0f).second
		val bottom = 92f + rig.delta(EyeRig.EyeMesh.WHITE, 140f, 92f, 0f).second
		assertTrue(abs(top - bottom) < 0.5f, "the white folds into the closed lid: its top at $top, its bottom at $bottom")
	}
}
