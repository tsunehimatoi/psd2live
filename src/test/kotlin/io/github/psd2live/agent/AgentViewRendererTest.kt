package io.github.psd2live.agent

import io.github.psd2live.core.Bounds
import io.github.psd2live.core.PSD2LivePipeline
import io.github.psd2live.core.PipelineConfig
import org.umamo.format.art.LayerBlend
import org.umamo.format.art.LayerBounds
import org.umamo.format.art.LayerId
import org.umamo.format.art.LayerRaster
import org.umamo.format.art.SourceLayer
import org.umamo.format.art.SourceArt
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AgentViewRendererTest {
	@Test
	fun `isolated view preserves transparent pixels and stable metadata`() {
		val layer = layer(
			rgba = byteArrayOf(
				255.toByte(), 10, 20, 255.toByte(),
				0, 0, 0, 0,
				30, 40, 50, 128.toByte(),
				1, 2, 3, 255.toByte(),
			),
		)

		val view = AgentViewRenderer.isolatedLayer(
			layer,
			canvasWidth = 320,
			canvasHeight = 160,
			revisionId = "revision-1",
			background = AgentViewBackground.TRANSPARENT,
			output = AgentViewOutputSpec(targetLongEdge = 128),
		)
		val decoded = ImageIO.read(ByteArrayInputStream(view.png))

		assertEquals("revision-1", view.revisionId)
		assertEquals(listOf("front-hair"), view.objectIds)
		assertEquals(128, decoded.width)
		assertEquals(128, decoded.height)
		assertEquals(0, decoded.getRGB(96, 16) ushr 24 and 0xff)
		assertTrue((decoded.getRGB(16, 96) ushr 24 and 0xff) in 112..136)
		assertEquals(2f / 128f, view.spatial.canvasUnitsPerPixelX)
		assertEquals(0f, view.spatial.viewRect.left)
		assertTrue(view.sha256.matches(Regex("[0-9a-f]{64}")))
	}

	@Test
	fun `context view focuses a layer and records canvas coordinates`() {
		val composite = BufferedImage(320, 160, BufferedImage.TYPE_INT_ARGB).also { image ->
			val graphics = image.createGraphics()
			try {
				graphics.color = Color.DARK_GRAY
				graphics.fillRect(0, 0, image.width, image.height)
			} finally {
				graphics.dispose()
			}
		}
		val layer = layer(left = 20, top = 10)

		val view = AgentViewRenderer.context(
			composite,
			layer,
			Bounds(20f, 10f, 22f, 12f),
			"revision-2",
			objectScale = 0.5f,
			aspectRatio = 1f,
			background = AgentViewBackground.TRANSPARENT,
			output = AgentViewOutputSpec(targetLongEdge = 128),
		)

		assertEquals(128, view.renderedWidth)
		assertEquals(128, view.renderedHeight)
		assertEquals(19f, view.canvasRect.left)
		assertEquals(9f, view.canvasRect.top)
		assertEquals(23f, view.canvasRect.right)
		assertEquals(13f, view.canvasRect.bottom)
		assertEquals(4f / 128f, view.spatial.canvasUnitsPerPixelX)
	}

	@Test
	fun `png byte budget lowers resolution without changing canvas placement`() {
		var value = 0x12345678
		val rgba = ByteArray(256 * 256 * 4)
		for (offset in rgba.indices step 4) {
			value = value * 1664525 + 1013904223
			rgba[offset] = (value ushr 16).toByte()
			rgba[offset + 1] = (value ushr 8).toByte()
			rgba[offset + 2] = value.toByte()
			rgba[offset + 3] = 255.toByte()
		}
		val noisyLayer = layer(rgba = rgba, width = 256, height = 256)

		val view = AgentViewRenderer.isolatedLayer(
			noisyLayer,
			canvasWidth = 2048,
			canvasHeight = 2048,
			revisionId = "revision-budget",
			background = AgentViewBackground.TRANSPARENT,
			output = AgentViewOutputSpec(targetLongEdge = 1024, maxBytes = 64 * 1024),
		)

		assertTrue(view.png.size <= 64 * 1024)
		assertTrue(view.renderedWidth < 1024)
		assertEquals(Bounds(0f, 0f, 256f, 256f), view.spatial.viewRect)
		assertEquals(256f / view.renderedWidth, view.spatial.canvasUnitsPerPixelX)
	}

	@Test
	fun `parameterized model view records pose composition and annotations`() {
		val sourceLayer = object : SourceLayer {
			override val id = LayerId("hair-front")
			override val name = "front hair"
			override val groupPath = "head/hair"
			override val order = 0
			override val bounds = LayerBounds(24, 12, 64, 96)
			override val opacity = 1f
			override val clipped = false
			override val blend = LayerBlend.Normal
			override val raster = LayerRaster(64, 96, ByteArray(64 * 96 * 4).also { pixels ->
				for (offset in pixels.indices step 4) {
					pixels[offset] = 180.toByte()
					pixels[offset + 1] = 70
					pixels[offset + 2] = 40
					pixels[offset + 3] = 255.toByte()
				}
			})
		}
		val source = object : SourceArt {
			override val layers = listOf(sourceLayer)
			override val widthPx = 128
			override val heightPx = 128
		}
		val model = PSD2LivePipeline().buildPreview(
			source,
			PipelineConfig(atlasSize = 256, meshSpacing = 16, exportCmo3 = false, exportMoc3 = false, exportJson = false),
		)
        val mesh = model.rig.puppet.drawables.first()
        val warpEdit = io.github.psd2live.core.RigWarpEdit("independent-lock", "Lock", mesh.parentDeformerId!!.raw, listOf(mesh.id.raw))
        val editedPuppet = warpEdit.applyTo(model.rig.puppet)
        for (angle in listOf(-45f, 0f, 45f)) {
            val pose = mapOf(org.umamo.runtime.model.ParameterId("ParamAngleX") to angle)
            val before = org.umamo.render.eval.CpuDeformationEvaluator().evaluate(model.rig.puppet, pose).worldPositions.getValue(mesh.id)
            val after = org.umamo.render.eval.CpuDeformationEvaluator().evaluate(editedPuppet, pose).worldPositions.getValue(mesh.id)
            assertEquals(before.size, after.size)
            before.indices.forEach { i -> assertEquals(before[i], after[i], 0.001f) }
        }
        kotlin.test.assertFailsWith<IllegalArgumentException> { warpEdit.applyTo(editedPuppet) }
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            warpEdit.copy(id = "another", parentId = "independent-lock").applyTo(model.rig.puppet.copy(deformers = editedPuppet.deformers))
        }
		val parameters = model.rig.puppet.parameters.associate { it.id.raw to it.default } + ("ParamAngleX" to 10f)

		val view = AgentViewRenderer.modelComposite(
			model = model,
			revisionId = "revision-posed",
			parameters = parameters,
			includeLayerIds = setOf("hair-front"),
			annotateLayerIds = setOf("hair-front"),
			frame = AgentViewFrame.FocusLayers(setOf("hair-front"), objectScale = 0.5f, aspectRatio = 1f),
			background = AgentViewBackground.TRANSPARENT,
			output = AgentViewOutputSpec(targetLongEdge = 128),
		)
		val decoded = ImageIO.read(ByteArrayInputStream(view.png))

		assertEquals(10f, view.appliedParameters["ParamAngleX"])
		assertEquals(listOf("hair-front"), view.includedLayerIds)
		assertEquals(listOf("hair-front"), view.annotatedLayerIds)
		assertEquals(128, decoded.width)
		assertEquals(listOf("hair-front"), view.spatial.focusLayerIds)
		assertEquals(0.5f, view.spatial.objectScale)
		assertEquals(view.spatial.viewRect.width / decoded.width, view.spatial.canvasUnitsPerPixelX)
		assertTrue((0 until decoded.height).any { y -> (0 until decoded.width).any { x -> decoded.getRGB(x, y) ushr 24 != 0 } })

		val extreme = AgentViewRenderer.modelComposite(
			model = model,
			revisionId = "revision-extreme",
			parameters = parameters + ("ParamAngleX" to 60f),
			includeLayerIds = setOf("hair-front"),
			annotateLayerIds = emptySet(),
			frame = AgentViewFrame.CanvasRect(Bounds(20f, 10f, 100f, 110f)),
			background = AgentViewBackground.TRANSPARENT,
			output = AgentViewOutputSpec(targetLongEdge = 128),
		)
		assertEquals(60f, extreme.appliedParameters["ParamAngleX"])
		assertEquals(AgentParameterRangeDiagnostic("ParamAngleX", 60f, -45f, 45f), extreme.outOfRangeParameters.single())

		val samePixelsElsewhere = AgentViewRenderer.modelComposite(
			model = model,
			revisionId = "revision-spatial-id",
			parameters = parameters,
			includeLayerIds = emptySet(),
			annotateLayerIds = emptySet(),
			frame = AgentViewFrame.CanvasRect(Bounds(0f, 0f, 40f, 40f)),
			background = AgentViewBackground.TRANSPARENT,
			output = AgentViewOutputSpec(targetLongEdge = 128),
		)
		val samePixelsDifferentRect = AgentViewRenderer.modelComposite(
			model = model,
			revisionId = "revision-spatial-id",
			parameters = parameters,
			includeLayerIds = emptySet(),
			annotateLayerIds = emptySet(),
			frame = AgentViewFrame.CanvasRect(Bounds(40f, 40f, 80f, 80f)),
			background = AgentViewBackground.TRANSPARENT,
			output = AgentViewOutputSpec(targetLongEdge = 128),
		)
		assertEquals(samePixelsElsewhere.sha256, samePixelsDifferentRect.sha256)
		assertNotEquals(samePixelsElsewhere.viewId, samePixelsDifferentRect.viewId)
	}

	private fun layer(
		left: Int = 0,
		top: Int = 0,
		rgba: ByteArray = ByteArray(16) { if (it % 4 == 3) 255.toByte() else 120 },
		width: Int = 2,
		height: Int = 2,
	) = object : SourceLayer {
		override val id = LayerId("front-hair")
		override val name = "front hair"
		override val groupPath = "head/hair"
		override val order = 0
		override val bounds = LayerBounds(left, top, width, height)
		override val opacity = 1f
		override val clipped = false
		override val blend = LayerBlend.Normal
		override val raster = LayerRaster(width, height, rgba)
	}
}
