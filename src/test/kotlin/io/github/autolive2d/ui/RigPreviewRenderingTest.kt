package io.github.autolive2d.ui

import io.github.autolive2d.core.AutoLive2DPipeline
import io.github.autolive2d.core.PipelineConfig
import io.github.autolive2d.core.RigBuilder
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RigPreviewRenderingTest {
	@Test
	fun `generated rig paints into the dynamic preview`() {
		val sample = Path.of("..", "Anime2.5DRig", "sample.psd").toAbsolutePath().normalize()
		if (!Files.isRegularFile(sample)) return
		val model = AutoLive2DPipeline().buildPreview(
			sample,
			PipelineConfig(atlasSize = 1024, meshSpacing = 128),
		)
		val panel = ModelPreviewPanel().apply {
			setSize(520, 620)
			previewModel = model
		}
		val image = BufferedImage(panel.width, panel.height, BufferedImage.TYPE_INT_ARGB)
		image.createGraphics().use { panel.paint(it) }
		val background = Color(43, 45, 48).rgb and 0x00ffffff
		assertTrue((image.getRGB(1, 1) and 0x00ffffff) != background, "checkerboard must fill the entire viewport")
		var changed = 0
		for (y in 0 until image.height step 8) for (x in 0 until image.width step 8) {
			if ((image.getRGB(x, y) and 0x00ffffff) != background) changed++
		}
		assertTrue(changed > 80, "dynamic preview should contain the textured rig and viewport")

		val camera = CanvasCamera {}
		val before = camera.viewport(model, 520, 620)
		val mouseX = 137
		val mouseY = 219
		val canvasX = before.canvasX(mouseX)
		val canvasY = before.canvasY(mouseY)
		camera.zoomAt(model, 520, 620, mouseX, mouseY, -1.0)
		val after = camera.viewport(model, 520, 620)
		assertEquals(canvasX, after.canvasX(mouseX), 1e-3f, "wheel zoom must retain the point under the mouse")
		assertEquals(canvasY, after.canvasY(mouseY), 1e-3f, "wheel zoom must retain the point under the mouse")

		val table = LayerTableModel().apply { setAnalysis(model.analysis) }
		assertTrue(table.rowCount > 1)
		assertEquals(Boolean::class.javaObjectType, table.getColumnClass(0))
		table.setVisibilityForRows(intArrayOf(0), false)
		assertTrue(!table.isVisibleAt(0))
		table.isolateOrRestore(1)
		assertEquals(setOf(table.layerAt(1)!!.source.id.raw), table.visibleLayerIds)
		table.isolateOrRestore(1)
		assertTrue(!table.isVisibleAt(0), "Alt-eye restore must recover the previous visibility state")

		val hiddenLayerId = model.analysis.layers.first().source.id.raw
		val hiddenRig = RigBuilder.build(
			model.analysis,
			model.atlas,
			model.config.copy(layerVisibility = mapOf(hiddenLayerId to false)),
		)
		val hiddenDrawables = hiddenRig.layerIdByDrawableId.filterValues { it == hiddenLayerId }.keys
		assertTrue(hiddenDrawables.isNotEmpty())
		assertTrue(hiddenRig.puppet.drawables.filter { it.id.raw in hiddenDrawables }.all { !it.isVisible })
	}

	private inline fun <T : java.awt.Graphics> T.use(block: (T) -> Unit) {
		try { block(this) } finally { dispose() }
	}
}
