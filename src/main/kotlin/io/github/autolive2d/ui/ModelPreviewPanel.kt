package io.github.autolive2d.ui

import io.github.autolive2d.core.RigPreviewModel
import io.github.autolive2d.core.StandardParameters
import org.umamo.runtime.model.ParameterId
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseEvent
import javax.swing.JPanel
import javax.swing.Timer
import kotlin.math.PI
import kotlin.math.sin

class ModelPreviewPanel : JPanel() {
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
			if (sourceChanged) camera.reset()
			resetMotion()
			repaint()
		}

	private val timer = Timer(33) {
		advanceMotion()
		repaint()
	}
	private var pointerActive = false
	private var pointerX = 0f
	private var pointerY = 0f
	private var followX = 0f
	private var followY = 0f
	private var previousFollowX = 0f
	private var frontHair = 0f
	private var frontHairVelocity = 0f
	private var backHair = 0f
	private var backHairVelocity = 0f
	private var elapsed = 0.0
	private var lastTick = System.nanoTime()

	init {
		preferredSize = Dimension(620, 620)
		minimumSize = Dimension(320, 320)
		background = Color(43, 45, 48)
		camera.install(
			this,
			{ previewModel },
			onPointerMove = ::updatePointer,
			onPointerExit = { pointerActive = false },
		)
	}

	override fun addNotify() {
		super.addNotify()
		lastTick = System.nanoTime()
		timer.start()
	}

	override fun removeNotify() {
		timer.stop()
		super.removeNotify()
	}

	override fun paintComponent(graphics: Graphics) {
		super.paintComponent(graphics)
		val g = graphics.create() as Graphics2D
		try {
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
			RigCanvasSupport.paintChecker(g, width, height)
			val model = previewModel ?: return paintEmpty(g)
			val viewport = camera.viewport(model, width, height)
			RigCanvasSupport.paintCanvasBoundary(g, viewport)
			val geometry = RigCanvasSupport.evaluate(model, currentParameters(model))
			RigCanvasSupport.paintTexturedRig(g, model, geometry, viewport, visibleLayerIds = visibleLayerIds)
			paintStatus(g, model)
		} catch (failure: RuntimeException) {
			g.color = Color(235, 117, 117)
			g.drawString("预览渲染失败：${failure.message ?: failure.javaClass.simpleName}", 18, 28)
		} finally {
			g.dispose()
		}
	}

	private fun updatePointer(event: MouseEvent) {
		pointerActive = true
		pointerX = ((event.x - width * 0.5f) / (width * 0.5f).coerceAtLeast(1f)).coerceIn(-1f, 1f)
		pointerY = ((event.y - height * 0.5f) / (height * 0.5f).coerceAtLeast(1f)).coerceIn(-1f, 1f)
	}

	private fun currentParameters(model: RigPreviewModel): Map<ParameterId, Float> {
		val phase = elapsed % 4.6
		val blink = if (phase in 4.18..4.46) {
			(1.0 - sin((phase - 4.18) / 0.28 * PI)).toFloat().coerceIn(0f, 1f)
		} else 1f
		return mapOf(
			StandardParameters.ANGLE_X to followX * 38f,
			StandardParameters.ANGLE_Y to -followY * 24f,
			StandardParameters.ANGLE_Z to followX * -4f,
			StandardParameters.BODY_X to (followX * 4f + sin(elapsed * 0.72).toFloat() * 1.2f),
			StandardParameters.BODY_Y to -followY * 2f,
			StandardParameters.BODY_Z to sin(elapsed * 0.92).toFloat() * 2.2f,
			StandardParameters.EYE_BALL_X to followX.coerceIn(-1f, 1f),
			StandardParameters.EYE_BALL_Y to (-followY).coerceIn(-1f, 1f),
			StandardParameters.EYE_L_OPEN to blink,
			StandardParameters.EYE_R_OPEN to blink,
			StandardParameters.BREATH to ((sin(elapsed * 1.45) + 1.0) * 0.5).toFloat(),
			StandardParameters.HAIR_FRONT to if (model.config.generatePhysics) frontHair.coerceIn(-1f, 1f) else 0f,
			StandardParameters.HAIR_BACK to if (model.config.generatePhysics) backHair.coerceIn(-1f, 1f) else 0f,
		)
	}

	private fun advanceMotion() {
		val now = System.nanoTime()
		val dt = ((now - lastTick) / 1_000_000_000.0).coerceIn(0.001, 0.08).toFloat()
		lastTick = now
		elapsed += dt
		val idleX = (sin(elapsed * 0.47) * 0.12).toFloat()
		val idleY = (sin(elapsed * 0.31 + 1.1) * 0.08).toFloat()
		val targetX = if (pointerActive) pointerX else idleX
		val targetY = if (pointerActive) pointerY else idleY
		val response = (dt * 7.5f).coerceAtMost(1f)
		previousFollowX = followX
		followX += (targetX - followX) * response
		followY += (targetY - followY) * response
		val headVelocity = ((followX - previousFollowX) / dt).coerceIn(-5f, 5f)
		val hairTarget = (-followX * 0.42f - headVelocity * 0.085f).coerceIn(-1f, 1f)
		frontHairVelocity += ((hairTarget - frontHair) * 22f - frontHairVelocity * 7.2f) * dt
		backHairVelocity += ((hairTarget - backHair) * 10f - backHairVelocity * 4.2f) * dt
		frontHair += frontHairVelocity * dt
		backHair += backHairVelocity * dt
	}

	private fun paintStatus(g: Graphics2D, model: RigPreviewModel) {
		val text = if (model.config.generatePhysics) {
			"鼠标跟踪  ·  物理模拟  ·  ${camera.zoomPercent}%"
		} else {
			"鼠标跟踪  ·  物理已关闭  ·  ${camera.zoomPercent}%"
		}
		g.font = Font(Font.SANS_SERIF, Font.PLAIN, 12)
		val metrics = g.fontMetrics
		val pillWidth = metrics.stringWidth(text) + 22
		g.color = Color(20, 22, 25, 188)
		g.fillRoundRect(14, height - 38, pillWidth, 25, 12, 12)
		g.color = Color(215, 222, 231)
		g.drawString(text, 25, height - 21)
	}

	private fun paintEmpty(graphics: Graphics) {
		graphics.color = Color(180, 184, 190)
		val text = "选择或拖入 See-Through PSD"
		val metrics = graphics.fontMetrics
		graphics.drawString(text, (width - metrics.stringWidth(text)) / 2, height / 2)
	}

	private fun resetMotion() {
		pointerActive = false
		followX = 0f
		followY = 0f
		previousFollowX = 0f
		frontHair = 0f
		frontHairVelocity = 0f
		backHair = 0f
		backHairVelocity = 0f
		elapsed = 0.0
		lastTick = System.nanoTime()
	}
}
