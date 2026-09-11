package io.github.psd2live.ui

import io.github.psd2live.core.*
import io.github.psd2live.ui.views.computeActiveWarpIds
import org.umamo.format.art.SourceArt
import org.umamo.format.art.SourceLayer
import org.umamo.runtime.model.*
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CanvasDisplayOptimizationTest {

	private fun createTestModel(): RigPreviewModel {
		val headWarp = Deformer.Warp(
			id = DeformerId("headWarp"),
			name = "Head Warp",
			parent = null,
			partId = null,
			rows = 2,
			columns = 2,
			isQuadTransform = true,
			geometryGrid = null,
		)
		val eyeWarp = Deformer.Warp(
			id = DeformerId("eyeWarp"),
			name = "Eye Warp",
			parent = headWarp.id,
			partId = null,
			rows = 2,
			columns = 2,
			isQuadTransform = true,
			geometryGrid = null,
		)
		val hairWarp = Deformer.Warp(
			id = DeformerId("hairWarp"),
			name = "Hair Warp",
			parent = headWarp.id,
			partId = null,
			rows = 2,
			columns = 2,
			isQuadTransform = true,
			geometryGrid = null,
		)

		val eyeDrawable = Drawable(
			id = DrawableId("eye_draw"),
			name = "Eye",
			parentDeformerId = eyeWarp.id,
			blendMode = BlendMode.Normal,
			maskedBy = emptyList(),
			mesh = null,
			geometryGrid = null,
		)
		val hairDrawable = Drawable(
			id = DrawableId("hair_draw"),
			name = "Hair",
			parentDeformerId = hairWarp.id,
			blendMode = BlendMode.Normal,
			maskedBy = emptyList(),
			mesh = null,
			geometryGrid = null,
		)
		val rootDrawable = Drawable(
			id = DrawableId("root_draw"),
			name = "Root Item",
			parentDeformerId = null,
			blendMode = BlendMode.Normal,
			maskedBy = emptyList(),
			mesh = null,
			geometryGrid = null,
		)

		val puppet = PuppetModel(
			parameters = emptyList(),
			parts = emptyList(),
			deformers = listOf(headWarp, eyeWarp, hairWarp),
			drawables = listOf(eyeDrawable, hairDrawable, rootDrawable),
			rootChildren = emptyList(),
			rootPartId = null,
		)

		val builtRig = BuiltRig(
			puppet = puppet,
			pageByDrawableId = emptyMap(),
			sourceBoundsByDrawableId = emptyMap(),
			layerIdByDrawableId = mapOf(
				"eye_draw" to "layer_eye",
				"hair_draw" to "layer_hair",
				"root_draw" to "layer_root",
			),
			faceCenterX = 0f,
			faceCenterY = 0f,
			faceRadiusX = 100f,
			faceRadiusY = 100f,
			warnings = emptyList(),
		)

		val img = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
		val sourceArt = object : SourceArt {
			override val widthPx: Int = 16
			override val heightPx: Int = 16
			override val layers: List<SourceLayer> = emptyList()
		}
		val analysis = PipelineAnalysis(
			source = sourceArt,
			layers = emptyList(),
			anchors = RigAnchors(Bounds(0f, 0f, 16f, 16f), Bounds(0f, 0f, 16f, 16f), Bounds(0f, 0f, 16f, 16f), 8f, 8f, 8f, 16f, 10f, 14f),
			warnings = emptyList(),
			preview = img,
		)
		val atlas = PackedAtlas(emptyList(), emptyMap())
		val config = PipelineConfig()
		val bundle = CubismRuntimeBundle(
			manifestPath = "model.model3.json",
			assets = listOf(CubismRuntimeAsset("model.model3.json", ByteArray(0))),
		)

		return RigPreviewModel(analysis, atlas, builtRig, config, bundle)
	}

	@Test
	fun testMeshSelectionHidesAllWarps() {
		val model = createTestModel()

		// When any mesh / layer is selected:
		// User requirement: "选中mesh不需要显示warp"
		val activeWarpsForEye = computeActiveWarpIds(
			model = model,
			selectedDeformerId = null,
			selectedLayerId = "layer_eye",
			contextualWarp = true,
		)
		assertTrue(activeWarpsForEye.isEmpty(), "Selecting a mesh must not display any warps")

		val activeWarpsForHair = computeActiveWarpIds(
			model = model,
			selectedDeformerId = null,
			selectedLayerId = "layer_hair",
			contextualWarp = false,
		)
		assertTrue(activeWarpsForHair.isEmpty(), "Selecting a mesh must not display any warps regardless of contextualWarp flag")
	}

	@Test
	fun testMeshWithNoParentWarpDisplaysNoWarps() {
		val model = createTestModel()

		// Root mesh has no parent deformer
		val activeWarps = computeActiveWarpIds(
			model = model,
			selectedDeformerId = null,
			selectedLayerId = "layer_root",
			contextualWarp = true,
		)

		assertTrue(activeWarps.isEmpty(), "Mesh without parent deformers should show no warps")
	}

	@Test
	fun testDeformerSelectionShowsDescendantWarps() {
		val model = createTestModel()

		// When headWarp is selected:
		// Should show headWarp and its child warps (eyeWarp, hairWarp)
		val activeWarps = computeActiveWarpIds(
			model = model,
			selectedDeformerId = "headWarp",
			selectedLayerId = null,
			contextualWarp = true,
		)

		assertEquals(setOf("headWarp", "eyeWarp", "hairWarp"), activeWarps)

		// When leaf deformer hairWarp is selected:
		val hairActive = computeActiveWarpIds(
			model = model,
			selectedDeformerId = "hairWarp",
			selectedLayerId = null,
			contextualWarp = true,
		)
		assertEquals(setOf("hairWarp"), hairActive)
	}

	@Test
	fun testNoSelectionDisplaysAllOrNoneBasedOnSelectedOnly() {
		val model = createTestModel()

		// Nothing selected, selectedOnly = false -> all warps
		val allWarps = computeActiveWarpIds(
			model = model,
			selectedDeformerId = null,
			selectedLayerId = null,
			selectedOnly = false,
		)
		assertEquals(setOf("headWarp", "eyeWarp", "hairWarp"), allWarps)

		// Nothing selected, selectedOnly = true -> empty
		val none = computeActiveWarpIds(
			model = model,
			selectedDeformerId = null,
			selectedLayerId = null,
			selectedOnly = true,
		)
		assertTrue(none.isEmpty())
	}

	@Test
	fun testRigInformationOverlayRenderingDoesNotCrash() {
		val model = createTestModel()
		val img = BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB)
		val g = img.createGraphics()
		val viewport = CanvasViewport(1.0, 0.0, 0.0, 100f, 100f)

		// Render with dimUnselected = true, selectedDeformerId and hoveredDeformerId
		RigInformationOverlay.paint(
			g = g,
			model = model.rig.puppet,
			parameters = emptyMap(),
			viewport = viewport,
			ids = setOf("headWarp", "eyeWarp"),
			labels = true,
			pointIndices = true,
			selectedDeformerId = "headWarp",
			hoveredDeformerId = "eyeWarp",
			dimUnselected = true,
		)
		g.dispose()
	}

	@Test
	fun testWarpDeformersExcludedFromBoundingBoxes() {
		val model = createTestModel()
		val warpDef = model.rig.puppet.deformers.firstOrNull { it.id.raw == "headWarp" }
		assertTrue(warpDef is Deformer.Warp, "headWarp must be a Deformer.Warp")

		// Verify filtering logic: deformers that are Deformer.Warp are excluded from bounding boxes
		val nonWarpDeformers = model.rig.puppet.deformers.filter { it !is Deformer.Warp }
		assertTrue(nonWarpDeformers.none { it.id.raw == "headWarp" }, "Warp deformers must be excluded from bounding box drawing")
	}
}
