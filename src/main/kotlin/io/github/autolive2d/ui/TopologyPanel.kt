package io.github.autolive2d.ui

import io.github.autolive2d.core.RigPreviewModel
import io.github.autolive2d.i18n.tr
import org.umamo.render.eval.DeformedGeometry
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseEvent
import javax.swing.JPanel

class TopologyPanel(
	private val selection: ComponentSelectionModel,
) : JPanel() {
	private val camera = CanvasCamera(::repaint)

	var visibleLayerIds: Set<String>? = null
		set(value) {
			field = value
			repaint()
		}

	var previewModel: RigPreviewModel? = null
		set(value) {
			val sourceChanged = field?.analysis?.source !== value?.analysis?.source
			field = value
			geometry = value?.let { RigCanvasSupport.evaluate(it) }
			drawableBounds = geometry?.let(RigCanvasSupport::boundsByDrawable).orEmpty()
			if (sourceChanged) camera.reset()
			repaint()
		}

	private var geometry: DeformedGeometry? = null
	private var drawableBounds = emptyMap<String, io.github.autolive2d.core.Bounds>()

	init {
		preferredSize = Dimension(620, 620)
		minimumSize = Dimension(320, 320)
		background = Color(43, 45, 48)
		selection.addListener { repaint() }
		camera.install(this, { previewModel }, ::selectAt)
	}

	override fun paintComponent(graphics: Graphics) {
		super.paintComponent(graphics)
		val g = graphics.create() as Graphics2D
		try {
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
			RigCanvasSupport.paintChecker(g, width, height)
			val model = previewModel ?: return paintEmpty(g)
			val currentGeometry = geometry ?: return
			val viewport = camera.viewport(model, width, height)
			RigCanvasSupport.paintCanvasBoundary(g, viewport)
			RigCanvasSupport.paintTexturedRig(g, model, currentGeometry, viewport, 0.26f, visibleLayerIds)
			for (drawable in model.rig.puppet.drawables) {
				val mesh = drawable.mesh ?: continue
				val positions = currentGeometry.worldPositions[drawable.id] ?: continue
				val layerId = model.rig.layerIdByDrawableId[drawable.id.raw] ?: continue
				if (visibleLayerIds != null && layerId !in checkNotNull(visibleLayerIds)) continue
				val selected = selection.selectedLayerId == layerId
				val color = ComponentPalette.strong(layerId)
				g.color = if (selected) color.brighter() else color
				g.stroke = BasicStroke(if (selected) 2.1f else 0.85f)
				for (offset in mesh.indices.indices step 3) {
					val a = mesh.indices[offset]
					val b = mesh.indices[offset + 1]
					val c = mesh.indices[offset + 2]
					drawEdge(g, positions, a, b, viewport)
					drawEdge(g, positions, b, c, viewport)
					drawEdge(g, positions, c, a, viewport)
				}
			}
			paintLegend(g, model)
		} finally {
			g.dispose()
		}
	}

	private fun selectAt(event: MouseEvent) {
		val model = previewModel ?: return
		val viewport = camera.viewport(model, width, height)
		selection.select(
			RigCanvasSupport.hitLayer(
				model,
				drawableBounds,
				viewport.canvasX(event.x),
				viewport.canvasY(event.y),
				visibleLayerIds,
			),
		)
	}

	private fun drawEdge(
		g: Graphics2D,
		positions: FloatArray,
		from: Int,
		to: Int,
		viewport: CanvasViewport,
	) {
		g.drawLine(
			viewport.x(positions[from * 2]).toInt(),
			viewport.yFromWorld(positions[from * 2 + 1]).toInt(),
			viewport.x(positions[to * 2]).toInt(),
			viewport.yFromWorld(positions[to * 2 + 1]).toInt(),
		)
	}

	private fun paintLegend(g: Graphics2D, model: RigPreviewModel) {
		val vertexCount = model.rig.puppet.drawables.sumOf { it.mesh?.vertexCount ?: 0 }
		val triangleCount = model.rig.puppet.drawables.sumOf { it.mesh?.triangleCount ?: 0 }
		val text = tr("canvas.topology.stats", model.rig.puppet.drawables.size, vertexCount, triangleCount, camera.zoomPercent)
		g.color = Color(20, 22, 25, 190)
		g.fillRoundRect(14, height - 38, g.fontMetrics.stringWidth(text) + 22, 25, 12, 12)
		g.color = Color(215, 222, 231)
		g.drawString(text, 25, height - 21)
	}

	private fun paintEmpty(graphics: Graphics) {
		graphics.color = Color(180, 184, 190)
		val text = tr("canvas.topology.empty")
		val metrics = graphics.fontMetrics
		graphics.drawString(text, (width - metrics.stringWidth(text)) / 2, height / 2)
	}
}
