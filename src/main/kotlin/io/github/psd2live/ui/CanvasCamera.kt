package io.github.psd2live.ui

import io.github.psd2live.core.RigPreviewModel
import java.awt.Cursor
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import javax.swing.JComponent
import javax.swing.SwingUtilities
import kotlin.math.pow

/** Infinite 2D workbench camera modelled after live2dConverter's preview controls. */
internal class CanvasCamera(
	private val repaint: () -> Unit,
) {
	private var zoom = 1.0
	private var panX = 0.0
	private var panY = 0.0

	val zoomPercent: Int get() = (zoom * 100.0).toInt()

	data class CubismViewport(val scale: Float, val offsetX: Float, val offsetY: Float)

	fun reset() {
		zoom = 1.0
		panX = 0.0
		panY = 0.0
		repaint()
	}

	fun viewport(model: RigPreviewModel, width: Int, height: Int, margin: Int = 34): CanvasViewport {
		val canvasWidth = model.analysis.source.widthPx.toFloat().coerceAtLeast(1f)
		val canvasHeight = model.analysis.source.heightPx.toFloat().coerceAtLeast(1f)
		val availableWidth = (width - margin * 2).coerceAtLeast(1)
		val availableHeight = (height - margin * 2).coerceAtLeast(1)
		val fitScale = minOf(availableWidth / canvasWidth.toDouble(), availableHeight / canvasHeight.toDouble())
		val scale = fitScale * zoom
		return CanvasViewport(
			scale,
			(width - canvasWidth * scale) * 0.5 + panX,
			(height - canvasHeight * scale) * 0.5 + panY,
			canvasWidth,
			canvasHeight,
		)
	}

	/** Converts this pixel-space workbench camera to the SDK renderer's normalized viewport. */
	fun cubismViewport(model: RigPreviewModel, width: Int, height: Int): CubismViewport {
		val safeWidth = width.coerceAtLeast(1).toDouble()
		val safeHeight = height.coerceAtLeast(1).toDouble()
		val viewportAspect = safeWidth / safeHeight
		val canvasWidth = model.analysis.source.widthPx.coerceAtLeast(1).toDouble()
		val canvasHeight = model.analysis.source.heightPx.coerceAtLeast(1).toDouble()
		val modelAspect = canvasWidth / canvasHeight
		val baseScaleX = if (viewportAspect >= 1.0) 1.0 / viewportAspect else 1.0
		val baseScaleY = if (viewportAspect >= 1.0) 1.0 else viewportAspect
		val fitScale = if (modelAspect > viewportAspect) {
			1.0 / (baseScaleX * modelAspect) * 0.95
		} else {
			1.0 / baseScaleY * 0.95
		}
		return CubismViewport(
			(fitScale * zoom).toFloat(),
			(panX / (safeWidth * 0.5)).toFloat(),
			(-panY / (safeHeight * 0.5)).toFloat(),
		)
	}

	fun zoomAt(model: RigPreviewModel, width: Int, height: Int, mouseX: Int, mouseY: Int, wheel: Double) {
		if (wheel == 0.0) return
		val oldViewport = viewport(model, width, height)
		val canvasX = (mouseX - oldViewport.offsetX) / oldViewport.scale
		val canvasY = (mouseY - oldViewport.offsetY) / oldViewport.scale
		val nextZoom = (zoom * 1.15.pow(-wheel)).coerceIn(0.05, 64.0)
		if (nextZoom == zoom) return
		zoom = nextZoom
		val centered = viewport(model, width, height)
		panX += mouseX - (centered.offsetX + canvasX * centered.scale)
		panY += mouseY - (centered.offsetY + canvasY * centered.scale)
		repaint()
	}

	fun panBy(dx: Int, dy: Int) {
		panX += dx
		panY += dy
		repaint()
	}

	fun install(
		component: JComponent,
		model: () -> RigPreviewModel?,
		onClick: ((MouseEvent) -> Unit)? = null,
		onPointerMove: ((MouseEvent) -> Unit)? = null,
		onPointerExit: (() -> Unit)? = null,
	) {
		component.isFocusable = true
		val handler = object : MouseAdapter() {
			private var dragging = false
			private var moved = false
			private var lastX = 0
			private var lastY = 0
			private var pressedX = 0
			private var pressedY = 0

			override fun mousePressed(event: MouseEvent) {
				component.requestFocusInWindow()
				if (SwingUtilities.isLeftMouseButton(event) || SwingUtilities.isMiddleMouseButton(event)) {
					dragging = true
					moved = false
					lastX = event.x
					lastY = event.y
					pressedX = event.x
					pressedY = event.y
					component.cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
				}
			}

			override fun mouseDragged(event: MouseEvent) {
				if (!dragging) return
				val dx = event.x - lastX
				val dy = event.y - lastY
				if (dx != 0 || dy != 0) {
					panBy(dx, dy)
					val totalX = event.x - pressedX
					val totalY = event.y - pressedY
					moved = moved || totalX * totalX + totalY * totalY > 9
					lastX = event.x
					lastY = event.y
				}
			}

			override fun mouseReleased(event: MouseEvent) {
				if (!dragging) return
				dragging = false
				component.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
				if (!moved && SwingUtilities.isLeftMouseButton(event)) onClick?.invoke(event)
			}

			override fun mouseMoved(event: MouseEvent) {
				onPointerMove?.invoke(event)
			}

			override fun mouseEntered(event: MouseEvent) {
				component.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
				onPointerMove?.invoke(event)
			}

			override fun mouseExited(event: MouseEvent) {
				if (!dragging) onPointerExit?.invoke()
			}

			override fun mouseWheelMoved(event: MouseWheelEvent) {
				val current = model() ?: return
				zoomAt(current, component.width, component.height, event.x, event.y, event.preciseWheelRotation)
				event.consume()
			}
		}
		component.addMouseListener(handler)
		component.addMouseMotionListener(handler)
		component.addMouseWheelListener(handler)
		component.addKeyListener(object : KeyAdapter() {
			override fun keyPressed(event: KeyEvent) {
				if (event.keyCode == KeyEvent.VK_F || event.keyCode == KeyEvent.VK_HOME || event.keyCode == KeyEvent.VK_0) {
					reset()
					event.consume()
				}
			}
		})
	}
}
