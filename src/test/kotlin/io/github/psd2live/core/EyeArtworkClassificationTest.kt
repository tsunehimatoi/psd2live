package io.github.psd2live.core

import io.github.psd2live.core.fit.EyeArtworkFitting
import io.github.psd2live.core.fit.EyeFit
import io.github.psd2live.core.fit.EyePrimitive
import io.github.psd2live.core.fit.EyeRegion
import io.github.psd2live.algorithms.binding.EyeRig
import io.github.psd2live.core.fit.EyeOpeningAssociation
import io.github.psd2live.core.fit.EyeModules
import io.github.psd2live.core.fit.EyeOptions
import io.github.psd2live.core.fit.EyeFitting
import io.github.psd2live.core.fit.FittedEyes
import io.github.psd2live.core.recipe.EyePartOverride
import io.github.psd2live.project.WorkspaceSourceLayer
import org.umamo.format.art.*
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EyeArtworkClassificationTest {
	private val eye = EyeFit(Side.LEFT, 20f, 45f, 1f, 0f, 100f,
		List(EyeFit.SAMPLES) { -0.2f * sin(PI * EyeFit.u(it)).toFloat() },
		List(EyeFit.SAMPLES) { 0.12f * sin(PI * EyeFit.u(it)).toFloat() }, null, null)

	private fun layer(id: String, role: PartRole? = null, w: Int = 150, h: Int = 90, draw: (Float, Float) -> Boolean): ClassifiedLayer {
		val rgba = ByteArray(w * h * 4)
		var count = 0; var sx = 0f; var sy = 0f
		for (y in 0 until h) for (x in 0 until w) {
			if (!draw(x + 0.5f, y + 0.5f)) continue
			rgba[(y * w + x) * 4 + 3] = -1; count++; sx += x + 0.5f; sy += y + 0.5f
		}
		val source = WorkspaceSourceLayer(LayerId(id), id, "eye", SourceLayerKind.Raster, true, 0,
			LayerBounds(0, 0, w, h), 1f, false, LayerBlend.Normal, ChannelMask.ALL, LayerRaster(w, h, rgba), null, null, false)
		return ClassifiedLayer(source, LayerSemantic(SemanticTag.EYELASH, Side.LEFT, normalizedName = id, confidence = 1f, role = role),
			Bounds(0f, 0f, w.toFloat(), h.toFloat()), sx / count, sy / count, count)
	}
	private fun upper(x: Float) = 45f - 20f * sin(PI * ((x - 20f) / 100f)).toFloat()
	private fun lower(x: Float) = 45f + 12f * sin(PI * ((x - 20f) / 100f)).toFloat()
	private fun main() = layer("main") { x, y -> x in 20f..120f && abs(y - upper(x)) <= 2f }
	private fun tuft(x: Float, y: Float, at: Float): Boolean =
		x in at - 2f..at + 2f && y in upper(at) - 13f..upper(at) + 1f
	private fun fit(layers: List<ClassifiedLayer>) = EyeArtworkFitting.fit(layers, mapOf(Side.LEFT to eye)) { x, y -> x to y }.getValue(Side.LEFT)

	@Test fun separateNumberedLayersAndMultipleTuftsOnOneLayerRetainEveryMaterialPiece() {
		val locations = listOf(35f, 68f, 103f)
		val split = fit(listOf(main()) + locations.mapIndexed { i, at -> layer("lash_${i + 1}") { x, y -> tuft(x, y, at) } })
		val joined = fit(listOf(main(), layer("tufts") { x, y -> locations.any { tuft(x, y, it) } }))
		assertEquals(3, split.components.count { it.layerId.startsWith("lash_") })
		assertEquals(3, joined.components.count { it.layerId == "tufts" })
		val a = split.components.filter { it.layerId.startsWith("lash_") }.sortedBy { it.centre.x }
		val b = joined.components.filter { it.layerId == "tufts" }.sortedBy { it.centre.x }
		for ((p, q) in a.zip(b)) {
			assertEquals(p.contact.centre.x, q.contact.centre.x, 0.02f)
			assertEquals(p.region, q.region)
		}
	}

	@Test fun aForkedPieceRetainsMultipleVisibleTipsAndAnExplicitLowerHint() {
		val fork = layer("fork") { x, y ->
			val root = upper(70f)
			(y in root - 3f..root + 1f && x in 68f..72f) ||
				(y in root - 17f..root - 2f && (abs(x - (70f + (root - y) * 0.5f)) < 1.5f || abs(x - (70f - (root - y) * 0.5f)) < 1.5f))
		}
		val lower = layer("lower", PartRole.LOWER) { x, y -> x in 25f..115f && abs(y - lower(x)) < 1.5f }
		val art = fit(listOf(main(), fork, lower))
		assertTrue(art.components.single { it.layerId == "fork" }.branches.size >= 2)
		assertEquals(EyeRegion.LOWER, art.components.single { it.layerId == "lower" }.region)
		assertTrue(art.upper.observed.count { it } > art.upper.observed.size / 2)
	}

	@Test fun changingTheSourceCannotSilentlyReuseAComponentsIdentity() {
		val before = fit(listOf(main(), layer("tuft") { x, y -> tuft(x, y, 65f) }))
		val after = fit(listOf(main(), layer("tuft") { x, y -> tuft(x, y, 72f) }))
		assertTrue(before.components.single { it.layerId == "tuft" }.id != after.components.single { it.layerId == "tuft" }.id)
		assertEquals(before.components.single { it.layerId == "main" }.id, after.components.single { it.layerId == "main" }.id)
	}

	@Test fun eyelidMaterialIsNotReclassifiedAsATuft() {
		val lid = layer("lid", PartRole.LID) { x, y -> x in 35f..100f && abs(y - (upper(x) - 10f)) < 1.5f }
		val part = fit(listOf(main(), lid)).components.single { it.layerId == "lid" }
		assertEquals(EyePrimitive.SKIN, part.primitive)
		assertTrue(part.branches.isEmpty())
	}

	@Test fun observationReductionCannotMergeSeparateSourceStrands() {
		val strands = layer("strands", w = 600) { x, y -> (x in 64f..65f || x in 66f..67f) && y in 10f..25f }
		assertEquals(2, fit(listOf(main(), strands)).components.count { it.layerId == "strands" })
	}

	@Test fun anIsolatedVerticalPieceDoesNotInventAStrokeOrAContact() {
		val isolated = layer("isolated") { x, y -> x in 68f..71f && y in 8f..16f }
		val art = fit(listOf(isolated))
		assertTrue(art.upper.observed.none { it })
		assertTrue(art.components.single().contacts.isEmpty())
		assertEquals(EyePrimitive.AUTO, art.components.single().primitive)
	}

	@Test fun explicitSideCannotFallBackToTheOppositeEye() {
		val art = EyeArtworkFitting.fit(listOf(main()), mapOf(Side.RIGHT to eye.copy(side = Side.RIGHT))) { x, y -> x to y }
		assertTrue(art.getValue(Side.RIGHT).components.isEmpty())
	}

	@Test fun sourceBoundCorrectionsAreAppliedAndStaleOnesAreRetainedWithoutApplying() {
		val layers = listOf(main(), layer("tuft") { x, y -> tuft(x, y, 65f) })
		val original = fit(layers).components.single { it.layerId == "tuft" }
		fun corrected(stamp: String) = EyeArtworkFitting.fit(layers, mapOf(Side.LEFT to eye),
			mapOf("tuft" to mapOf(original.id to EyePartOverride(stamp, region = EyeRegion.LOWER)))) { x, y -> x to y }
		assertEquals(EyeRegion.LOWER, corrected(original.sourceStamp).getValue(Side.LEFT).components.single { it.layerId == "tuft" }.region)
		assertEquals(original.region, corrected("old-source").getValue(Side.LEFT).components.single { it.layerId == "tuft" }.region)
	}

	@Test fun anUpperStrokesDescendingSideIsNotALowerStroke() {
		val wrapped = layer("wrapped") { x, y ->
			(x in 20f..120f && abs(y - upper(x)) <= 2f) ||
				(x in 110f..115f && y in upper(x)..upper(x) + 18f)
		}
		val art = fit(listOf(wrapped))
		assertTrue(art.components.single().parts.all { it.region == EyeRegion.UPPER })
	}

	@Test fun aSingleConnectedOutlineCanCarryBothUpperAndLowerMaterial() {
		val outline = layer("outline") { x,y -> x in 20f..120f &&
			(abs(y-upper(x))<=2f || abs(y-lower(x))<=2f) }
		val art=fit(listOf(outline))
		assertEquals(setOf(EyeRegion.UPPER,EyeRegion.LOWER),art.components.single().parts.map { it.region }.toSet())
	}
	@Test fun aCombinedEyeMeshKeysEachSidesVerticesIndependently() {
		val left = EyeRig(eye, 0.25f, 1f, 0.6f)
		val right = EyeRig(eye.copy(side = Side.RIGHT, originX = -20f, axisX = -1f), 0.25f, 1f, 0.6f)
		val eyes = FittedEyes(EyeFitting.Result(emptyMap(), emptyMap()), linkedMapOf(Side.LEFT to left, Side.RIGHT to right), EyeOptions())
		val grid = requireNotNull(EyeModules.pairedLidGrid(EyeRig.EyeMesh.UPPER_LASH, "both", floatArrayOf(70f, 25f, -70f, 25f),
			Bounds(-200f, 0f, 200f, 100f), eyes))
		val closeLeft = grid.cells.single { it.coordinate.contentEquals(intArrayOf(0, right.keys.lastIndex)) }.form.positionDeltas
		assertTrue(closeLeft[1] != 0f)
		assertEquals(0f, closeLeft[2]); assertEquals(0f, closeLeft[3])
		val closeRight = grid.cells.single { it.coordinate.contentEquals(intArrayOf(left.keys.lastIndex, 0)) }.form.positionDeltas
		assertTrue(closeRight[3] != 0f)
		assertEquals(0f, closeRight[0]); assertEquals(0f, closeRight[1])
	}

	@Test fun anIrisUsesItsCompatibleOpeningAndNumericSuffixesDoNotChooseAlternatives() {
		fun opening(id: String, variant: Int?, type: LayerType = LayerType.PRESET, switch: Int = 0) = main().let { c ->
			c.copy(source = (c.source as WorkspaceSourceLayer).copy(id = LayerId(id)),
				semantic = c.semantic.copy(tag = SemanticTag.EYEWHITE, variant = variant, type = type, parameter = "expression", switchId = switch))
		}
		val near = opening("near", 1)
		val far = opening("far", 2).copy(bounds = Bounds(200f, 20f, 300f, 60f), centroidX = 250f)
		val iris = main().copy(semantic = main().semantic.copy(tag = SemanticTag.IRIDES, variant = 2))
		assertEquals(listOf(near), EyeOpeningAssociation.select(iris, listOf(far, near)))
		val switched = iris.copy(semantic = iris.semantic.copy(type = LayerType.SWITCH, parameter = "expression", switchId = 2))
		val match = opening("match", null, LayerType.SWITCH, 2)
		assertEquals(listOf(match), EyeOpeningAssociation.select(switched, listOf(near, match)))
	}
}
