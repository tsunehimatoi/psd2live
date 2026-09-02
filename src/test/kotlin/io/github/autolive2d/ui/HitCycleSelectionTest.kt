package io.github.autolive2d.ui

import io.github.autolive2d.core.Bounds
import io.github.autolive2d.core.BuiltRig
import io.github.autolive2d.core.CubismRuntimeAsset
import io.github.autolive2d.core.CubismRuntimeBundle
import io.github.autolive2d.core.PackedAtlas
import io.github.autolive2d.core.PipelineAnalysis
import io.github.autolive2d.core.PipelineConfig
import io.github.autolive2d.core.RigAnchors
import io.github.autolive2d.core.RigBuilder
import io.github.autolive2d.core.RigPreviewModel
import org.umamo.format.art.SourceArt
import org.umamo.format.art.SourceLayer
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.ChannelGrids
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.PuppetModel
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HitCycleSelectionTest {

	private fun testDrawable(
		id: String,
		name: String,
		drawOrder: Float = 0f,
		isVisible: Boolean = true,
		mesh: DrawableMesh? = null,
	): Drawable = Drawable(
		id = DrawableId(id),
		name = name,
		parentDeformerId = null,
		blendMode = BlendMode.Normal,
		maskedBy = emptyList(),
		mesh = mesh,
		geometryGrid = RigBuilder.zeroMeshGrid(mesh?.vertexCount ?: 0),
		channelGrids = ChannelGrids.Empty,
		drawOrder = drawOrder,
		opacity = 1f,
		isVisible = isVisible,
		texturePage = 0,
	)

	private fun createTestModel(drawables: List<Drawable>, layerMap: Map<String, String>): RigPreviewModel {
		val dummyImage = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
		val sourceArt = object : SourceArt {
			override val widthPx get() = 100
			override val heightPx get() = 100
			override val layers: List<SourceLayer> get() = emptyList()
		}
		val analysis = PipelineAnalysis(
			source = sourceArt,
			layers = emptyList(),
			anchors = RigAnchors(
				character = Bounds(0f, 0f, 100f, 100f),
				face = Bounds(10f, 10f, 60f, 60f),
				body = Bounds(0f, 0f, 100f, 100f),
				faceCenterX = 50f,
				faceCenterY = 50f,
				chinX = 50f,
				chinY = 60f,
				shoulderY = 65f,
				hipY = 90f,
			),
			warnings = emptyList(),
			preview = dummyImage,
		)
		val atlas = PackedAtlas(pages = emptyList(), placementByLayerId = emptyMap())
		val puppet = PuppetModel(
			parameters = emptyList(),
			parts = emptyList(),
			deformers = emptyList(),
			drawables = drawables,
			rootChildren = emptyList(),
			rootPartId = null,
			parameterLinks = emptyList(),
			parameterTree = emptyList(),
			canvasWidth = 100f,
			canvasHeight = 100f,
			worldOriginX = 50f,
			worldOriginY = -50f,
		)
		val rig = BuiltRig(
			puppet = puppet,
			pageByDrawableId = emptyMap(),
			sourceBoundsByDrawableId = emptyMap(),
			layerIdByDrawableId = layerMap,
			faceCenterX = 50f,
			faceCenterY = 50f,
			faceRadiusX = 25f,
			faceRadiusY = 25f,
			warnings = emptyList(),
		)
		val runtimeBundle = CubismRuntimeBundle(
			manifestPath = "test.model3.json",
			assets = listOf(CubismRuntimeAsset("test.model3.json", ByteArray(4))),
		)
		return RigPreviewModel(
			analysis = analysis,
			atlas = atlas,
			rig = rig,
			config = PipelineConfig(),
			runtimeBundle = runtimeBundle,
		)
	}

	@Test
	fun `hitLayers returns all overlapping layers ordered from top to bottom`() {
		val drawables = listOf(
			testDrawable("d_body", "Body", drawOrder = 100f),
			testDrawable("d_face", "Face", drawOrder = 200f),
			testDrawable("d_eye", "Eye", drawOrder = 300f),
		)
		val layerMap = mapOf(
			"d_body" to "layer_body",
			"d_face" to "layer_face",
			"d_eye" to "layer_eye",
		)
		val drawableBounds = mapOf(
			"d_body" to Bounds(0f, 0f, 100f, 100f), // Area 10000
			"d_face" to Bounds(10f, 10f, 60f, 60f),  // Area 2500
			"d_eye" to Bounds(20f, 20f, 40f, 40f),   // Area 400
		)
		val model = createTestModel(drawables, layerMap)

		val hits = RigCanvasSupport.hitLayers(
			model = model,
			drawableBounds = drawableBounds,
			canvasX = 30f,
			canvasY = 30f,
		)

		assertEquals(listOf("layer_eye", "layer_face", "layer_body"), hits)
	}

	@Test
	fun `cycle selection rotates through multiple obscured layers`() {
		val drawables = listOf(
			testDrawable("d_body", "Body", drawOrder = 100f),
			testDrawable("d_face", "Face", drawOrder = 200f),
			testDrawable("d_eye", "Eye", drawOrder = 300f),
		)
		val layerMap = mapOf(
			"d_body" to "layer_body",
			"d_face" to "layer_face",
			"d_eye" to "layer_eye",
		)
		val drawableBounds = mapOf(
			"d_body" to Bounds(0f, 0f, 100f, 100f),
			"d_face" to Bounds(10f, 10f, 60f, 60f),
			"d_eye" to Bounds(20f, 20f, 40f, 40f),
		)
		val model = createTestModel(drawables, layerMap)

		// 1. Initial click -> topmost (layer_eye)
		val sel1 = RigCanvasSupport.hitLayer(
			model = model,
			drawableBounds = drawableBounds,
			canvasX = 30f,
			canvasY = 30f,
			currentSelectedLayerId = null,
		)
		assertEquals("layer_eye", sel1)

		// 2. Click again on same location -> next layer (layer_face)
		val sel2 = RigCanvasSupport.hitLayer(
			model = model,
			drawableBounds = drawableBounds,
			canvasX = 30f,
			canvasY = 30f,
			currentSelectedLayerId = sel1,
		)
		assertEquals("layer_face", sel2)

		// 3. Click again -> next layer (layer_body)
		val sel3 = RigCanvasSupport.hitLayer(
			model = model,
			drawableBounds = drawableBounds,
			canvasX = 30f,
			canvasY = 30f,
			currentSelectedLayerId = sel2,
		)
		assertEquals("layer_body", sel3)

		// 4. Click again -> cycle back to top (layer_eye)
		val sel4 = RigCanvasSupport.hitLayer(
			model = model,
			drawableBounds = drawableBounds,
			canvasX = 30f,
			canvasY = 30f,
			currentSelectedLayerId = sel3,
		)
		assertEquals("layer_eye", sel4)
	}

	@Test
	fun `hidden layers are excluded from cycle selection`() {
		val drawables = listOf(
			testDrawable("d_body", "Body", drawOrder = 100f),
			testDrawable("d_face", "Face", drawOrder = 200f),
			testDrawable("d_eye", "Eye", drawOrder = 300f),
		)
		val layerMap = mapOf(
			"d_body" to "layer_body",
			"d_face" to "layer_face",
			"d_eye" to "layer_eye",
		)
		val drawableBounds = mapOf(
			"d_body" to Bounds(0f, 0f, 100f, 100f),
			"d_face" to Bounds(10f, 10f, 60f, 60f),
			"d_eye" to Bounds(20f, 20f, 40f, 40f),
		)
		val model = createTestModel(drawables, layerMap)
		val visible = setOf("layer_eye", "layer_body") // layer_face is hidden

		val sel1 = RigCanvasSupport.hitLayer(
			model = model,
			drawableBounds = drawableBounds,
			canvasX = 30f,
			canvasY = 30f,
			visibleLayerIds = visible,
			currentSelectedLayerId = "layer_eye",
		)
		// Should skip layer_face and go directly to layer_body
		assertEquals("layer_body", sel1)

		val sel2 = RigCanvasSupport.hitLayer(
			model = model,
			drawableBounds = drawableBounds,
			canvasX = 30f,
			canvasY = 30f,
			visibleLayerIds = visible,
			currentSelectedLayerId = "layer_body",
		)
		assertEquals("layer_eye", sel2)
	}

	@Test
	fun `clicking empty area returns null`() {
		val drawables = listOf(
			testDrawable("d_face", "Face", drawOrder = 200f),
		)
		val layerMap = mapOf("d_face" to "layer_face")
		val drawableBounds = mapOf("d_face" to Bounds(10f, 10f, 60f, 60f))
		val model = createTestModel(drawables, layerMap)

		val hit = RigCanvasSupport.hitLayer(
			model = model,
			drawableBounds = drawableBounds,
			canvasX = 200f,
			canvasY = 200f,
			currentSelectedLayerId = "layer_face",
		)
		assertNull(hit)
	}

	@Test
	fun `precise mesh hit testing accurately hits inside triangle and skips outside triangle within bounding box`() {
		val mesh = DrawableMesh(
			positions = floatArrayOf(0f, 0f, 20f, 0f, 0f, 20f),
			uvs = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f),
			indices = intArrayOf(0, 1, 2),
		)
		val drawables = listOf(
			testDrawable("d_tri", "Tri", drawOrder = 200f, mesh = mesh),
			testDrawable("d_bg", "Bg", drawOrder = 100f),
		)
		val layerMap = mapOf("d_tri" to "layer_tri", "d_bg" to "layer_bg")
		val drawableBounds = mapOf(
			"d_tri" to Bounds(0f, 0f, 20f, 20f),
			"d_bg" to Bounds(0f, 0f, 50f, 50f),
		)
		val geometry = org.umamo.render.eval.DeformedGeometry(
			worldPositions = mapOf(DrawableId("d_tri") to floatArrayOf(0f, 0f, 20f, 0f, 0f, -20f)),
			drawOrder = emptyMap(),
			opacity = emptyMap(),
		)
		val model = createTestModel(drawables, layerMap)

		// Point (5, 5) is inside the triangle (0,0)-(20,0)-(0,20)
		val hitInside = RigCanvasSupport.hitLayers(
			model = model,
			drawableBounds = drawableBounds,
			canvasX = 5f,
			canvasY = 5f,
			geometry = geometry,
		)
		assertEquals(listOf("layer_tri", "layer_bg"), hitInside)

		// Point (18, 18) is inside bounding box (0,0,20,20) of d_tri, but outside its triangle
		val hitOutside = RigCanvasSupport.hitLayers(
			model = model,
			drawableBounds = drawableBounds,
			canvasX = 18f,
			canvasY = 18f,
			geometry = geometry,
		)
		assertEquals(listOf("layer_bg"), hitOutside)
	}
}
