package io.github.autolive2d.ui

import io.github.autolive2d.core.Bounds
import io.github.autolive2d.core.PipelineAnalysis
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JPanel
import javax.swing.Scrollable
import kotlin.math.min

class ModelPreviewPanel : JPanel(), Scrollable {
	var analysis: PipelineAnalysis? = null
		set(value) {
			field = value
			repaint()
		}

	init {
		preferredSize = Dimension(520, 620)
		background = Color(43, 45, 48)
	}

	override fun getPreferredScrollableViewportSize(): Dimension = Dimension(520, 620)
	override fun getScrollableUnitIncrement(visibleRect: java.awt.Rectangle, orientation: Int, direction: Int): Int = 24
	override fun getScrollableBlockIncrement(visibleRect: java.awt.Rectangle, orientation: Int, direction: Int): Int = 120
	override fun getScrollableTracksViewportWidth(): Boolean = true
	override fun getScrollableTracksViewportHeight(): Boolean = true

	override fun paintComponent(graphics: Graphics) {
		super.paintComponent(graphics)
		val current = analysis ?: return paintEmpty(graphics)
		val g = graphics.create() as Graphics2D
		try {
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
			val margin = 24
			val scale = min((width - margin * 2).toDouble() / current.preview.width, (height - margin * 2).toDouble() / current.preview.height).coerceAtMost(1.0)
			val drawWidth = (current.preview.width * scale).toInt()
			val drawHeight = (current.preview.height * scale).toInt()
			val x = (width - drawWidth) / 2
			val y = (height - drawHeight) / 2
			paintChecker(g, x, y, drawWidth, drawHeight)
			g.drawImage(current.preview, x, y, drawWidth, drawHeight, null)
			fun drawBounds(bounds: Bounds, color: Color, label: String) {
				g.color = color
				g.stroke = BasicStroke(1.5f)
				val bx = x + (bounds.left * scale).toInt()
				val by = y + (bounds.top * scale).toInt()
				val bw = (bounds.width * scale).toInt()
				val bh = (bounds.height * scale).toInt()
				g.drawRect(bx, by, bw, bh)
				g.drawString(label, bx + 3, maxOf(12, by - 3))
			}
			drawBounds(current.anchors.face, Color(74, 208, 255), "Face")
			drawBounds(current.anchors.body, Color(255, 184, 72), "Body")
		} finally { g.dispose() }
	}

	private fun paintEmpty(graphics: Graphics) {
		graphics.color = Color(180, 180, 180)
		val text = "选择或拖入 See-Through PSD"
		val metrics = graphics.fontMetrics
		graphics.drawString(text, (width - metrics.stringWidth(text)) / 2, height / 2)
	}

	private fun paintChecker(g: Graphics2D, x: Int, y: Int, width: Int, height: Int) {
		val cell = 12
		for (row in 0..height / cell) for (column in 0..width / cell) {
			g.color = if ((row + column) and 1 == 0) Color(215, 215, 215) else Color(245, 245, 245)
			g.fillRect(x + column * cell, y + row * cell, minOf(cell, width - column * cell), minOf(cell, height - row * cell))
		}
	}
}
