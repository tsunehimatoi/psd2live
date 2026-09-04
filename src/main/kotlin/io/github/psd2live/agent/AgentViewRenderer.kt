package io.github.psd2live.agent

import io.github.psd2live.core.Bounds
import io.github.psd2live.core.PreviewRenderer
import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.ui.CanvasViewport
import io.github.psd2live.ui.RigCanvasSupport
import org.umamo.format.art.SourceLayer
import org.umamo.runtime.model.ParameterId
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

object AgentViewRenderer {
	fun modelComposite(
		model: RigPreviewModel,
		revisionId: String,
		parameters: Map<String, Float>,
		includeLayerIds: Set<String>,
		annotateLayerIds: Set<String>,
		frame: AgentViewFrame,
		background: AgentViewBackground,
		output: AgentViewOutputSpec,
	): AgentRenderedView {
		validateOutput(output)
		val knownLayerIds = model.rig.layerIdByDrawableId.values.toSet()
		val unknownIncluded = includeLayerIds - knownLayerIds
		require(unknownIncluded.isEmpty()) { "Unknown included layer IDs: ${unknownIncluded.sorted().joinToString()}" }
		val unknownAnnotated = annotateLayerIds - knownLayerIds
		require(unknownAnnotated.isEmpty()) { "Unknown annotation layer IDs: ${unknownAnnotated.sorted().joinToString()}" }

		val typedParameters = parameters.mapKeys { ParameterId(it.key) }
		val outOfRangeParameters = model.rig.puppet.parameters.mapNotNull { parameter ->
			val value = parameters[parameter.id.raw] ?: return@mapNotNull null
			if (value < parameter.min || value > parameter.max) {
				AgentParameterRangeDiagnostic(parameter.id.raw, value, parameter.min, parameter.max)
			} else {
				null
			}
		}
		val geometry = RigCanvasSupport.evaluate(model, typedParameters)
		val drawableBounds = RigCanvasSupport.boundsByDrawable(geometry)
		val boundsByLayer = boundsByLayer(model, drawableBounds)
		val resolvedFrame = resolveFrame(frame, boundsByLayer)
		val canvasWidth = model.analysis.source.widthPx.coerceAtLeast(1).toFloat()
		val canvasHeight = model.analysis.source.heightPx.coerceAtLeast(1).toFloat()
		val target = targetRaster(resolvedFrame.viewRect, output.targetLongEdge, canvasWidth, canvasHeight)
		val image = when (background) {
			AgentViewBackground.TRANSPARENT -> BufferedImage(target.width, target.height, BufferedImage.TYPE_INT_ARGB)
			AgentViewBackground.CHECKERBOARD -> checkerboard(target.width, target.height)
		}
		val graphics = image.createGraphics()
		try {
			graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
			RigCanvasSupport.paintTexturedRig(
				graphics,
				model,
				geometry,
				target.viewport,
				visibleLayerIds = includeLayerIds,
			)
			paintLayerAnnotations(graphics, model, drawableBounds, target.viewport, annotateLayerIds, image.width, image.height)
		} finally {
			graphics.dispose()
		}

		return encodedView(
			image = image,
			output = output,
			revisionId = revisionId,
			kind = "model-composite-png",
			objectIds = (includeLayerIds + annotateLayerIds).sorted(),
			canvasWidth = canvasWidth,
			canvasHeight = canvasHeight,
			requestedCanvasRect = resolvedFrame.viewRect,
			canvasRect = target.viewRect,
			focusRect = resolvedFrame.focusRect,
			focusLayerIds = resolvedFrame.focusLayerIds,
			objectScale = resolvedFrame.objectScale,
			appliedParameters = parameters.toSortedMap(),
			outOfRangeParameters = outOfRangeParameters,
			includedLayerIds = includeLayerIds.sorted(),
			annotatedLayerIds = annotateLayerIds.sorted(),
		)
	}

	fun isolatedLayer(
		layer: SourceLayer,
		canvasWidth: Int,
		canvasHeight: Int,
		revisionId: String,
		background: AgentViewBackground,
		output: AgentViewOutputSpec,
	): AgentRenderedView {
		validateOutput(output)
		val foreground = PreviewRenderer.rasterImage(layer.raster.width, layer.raster.height, layer.raster.rgba)
		val canvasRect = Bounds(
			layer.bounds.left.toFloat(),
			layer.bounds.top.toFloat(),
			(layer.bounds.left + layer.bounds.width).toFloat(),
			(layer.bounds.top + layer.bounds.height).toFloat(),
		)
		requireMatchingAspect(foreground.width, foreground.height, canvasRect)
		val size = targetPixelSize(canvasRect, output.targetLongEdge)
		val image = when (background) {
			AgentViewBackground.TRANSPARENT -> BufferedImage(size.first, size.second, BufferedImage.TYPE_INT_ARGB)
			AgentViewBackground.CHECKERBOARD -> checkerboard(size.first, size.second)
		}
		val graphics = image.createGraphics()
		try {
			graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
			graphics.drawImage(foreground, 0, 0, image.width, image.height, null)
		} finally {
			graphics.dispose()
		}
		return encodedView(
			image = image,
			output = output,
			revisionId = revisionId,
			kind = "layer-composite-png-${background.name.lowercase()}",
			objectIds = listOf(layer.id.raw),
			canvasWidth = canvasWidth.toFloat(),
			canvasHeight = canvasHeight.toFloat(),
			requestedCanvasRect = canvasRect,
			canvasRect = canvasRect,
			focusRect = canvasRect,
			focusLayerIds = listOf(layer.id.raw),
			objectScale = 1f,
		)
	}

	fun context(
		composite: BufferedImage,
		layer: SourceLayer,
		focusRect: Bounds,
		revisionId: String,
		objectScale: Float,
		aspectRatio: Float,
		background: AgentViewBackground,
		output: AgentViewOutputSpec,
	): AgentRenderedView {
		validateOutput(output)
		val viewRect = focusViewRect(focusRect, objectScale, aspectRatio)
		val target = targetRaster(viewRect, output.targetLongEdge, composite.width.toFloat(), composite.height.toFloat())
		val image = when (background) {
			AgentViewBackground.TRANSPARENT -> BufferedImage(target.width, target.height, BufferedImage.TYPE_INT_ARGB)
			AgentViewBackground.CHECKERBOARD -> checkerboard(target.width, target.height)
		}
		val graphics = image.createGraphics()
		try {
			graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
			val transform = AffineTransform(
				target.viewport.scale,
				0.0,
				0.0,
				target.viewport.scale,
				target.viewport.offsetX,
				target.viewport.offsetY,
			)
			graphics.drawImage(composite, transform, null)
			graphics.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.58f)
			val layerTransform = AffineTransform(transform).apply {
				translate(layer.bounds.left.toDouble(), layer.bounds.top.toDouble())
			}
			graphics.drawImage(tintedLayer(layer, Color(0x00, 0xD8, 0xFF)), layerTransform, null)
			graphics.composite = AlphaComposite.SrcOver
			graphics.color = Color(0x00, 0xD8, 0xFF, 230)
			graphics.stroke = BasicStroke(maxOf(2f, image.width / 900f))
			val boxX = target.viewport.x(focusRect.left).roundToInt()
			val boxY = (target.viewport.offsetY + focusRect.top * target.viewport.scale).roundToInt()
			graphics.drawRect(
				boxX,
				boxY,
				maxOf(1, (focusRect.width * target.viewport.scale).roundToInt() - 1),
				maxOf(1, (focusRect.height * target.viewport.scale).roundToInt() - 1),
			)
			graphics.font = graphics.font.deriveFont(maxOf(14f, image.width / 72f))
			val labelY = (boxY - 6).coerceAtLeast(graphics.font.size)
			val labelX = boxX.coerceIn(0, (image.width - 1).coerceAtLeast(0))
			graphics.color = Color(0, 0, 0, 190)
			val labelWidth = (graphics.fontMetrics.stringWidth(layer.name) + 10).coerceAtMost((image.width - labelX).coerceAtLeast(1))
			graphics.fillRect(labelX, labelY - graphics.font.size, labelWidth, graphics.font.size + 4)
			graphics.color = Color.WHITE
			graphics.drawString(layer.name, labelX + 5, labelY)
		} finally {
			graphics.dispose()
		}

		return encodedView(
			image = image,
			output = output,
			revisionId = revisionId,
			kind = "context-composite-png",
			objectIds = listOf(layer.id.raw),
			canvasWidth = composite.width.toFloat(),
			canvasHeight = composite.height.toFloat(),
			requestedCanvasRect = viewRect,
			canvasRect = target.viewRect,
			focusRect = focusRect,
			focusLayerIds = listOf(layer.id.raw),
			objectScale = objectScale,
		)
	}

	private fun checkerboard(width: Int, height: Int): BufferedImage {
		val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
		val graphics = image.createGraphics()
		try {
			val size = 16
			for (y in 0 until height step size) {
				for (x in 0 until width step size) {
					graphics.color = if ((x / size + y / size) % 2 == 0) Color(0xDD, 0xDD, 0xDD) else Color.WHITE
					graphics.fillRect(x, y, size, size)
				}
			}
		} finally {
			graphics.dispose()
		}
		return image
	}

	private fun paintLayerAnnotations(
		graphics: java.awt.Graphics2D,
		model: RigPreviewModel,
		drawableBounds: Map<String, Bounds>,
		viewport: CanvasViewport,
		layerIds: Set<String>,
		imageWidth: Int,
		imageHeight: Int,
	) {
		if (layerIds.isEmpty()) return
		val boundsByLayer = boundsByLayer(model, drawableBounds).filterKeys { it in layerIds }
		for ((index, entry) in boundsByLayer.entries.withIndex()) {
			val (layerId, bounds) = entry
			val color = ANNOTATION_COLORS[index % ANNOTATION_COLORS.size]
			RigCanvasSupport.paintBounds(graphics, bounds, viewport, color, 2.4f)
			graphics.font = graphics.font.deriveFont(maxOf(14f, imageWidth / 72f))
			val x = viewport.x(bounds.left).roundToInt().coerceIn(0, (imageWidth - 1).coerceAtLeast(0))
			val top = (viewport.offsetY + bounds.top * viewport.scale).roundToInt()
			val baseline = (top - 5).coerceIn(graphics.font.size, (imageHeight - 1).coerceAtLeast(graphics.font.size))
			val labelWidth = (graphics.fontMetrics.stringWidth(layerId) + 10).coerceAtMost((imageWidth - x).coerceAtLeast(1))
			graphics.color = Color(0, 0, 0, 205)
			graphics.fillRect(x, baseline - graphics.font.size, labelWidth, graphics.font.size + 4)
			graphics.color = color
			graphics.drawString(layerId, x + 5, baseline)
		}
	}

	private fun tintedLayer(layer: SourceLayer, tint: Color): BufferedImage {
		val source = PreviewRenderer.rasterImage(layer.raster.width, layer.raster.height, layer.raster.rgba)
		val tinted = BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_ARGB)
		for (y in 0 until source.height) {
			for (x in 0 until source.width) {
				val alpha = source.getRGB(x, y) ushr 24 and 0xff
				if (alpha > 0) tinted.setRGB(x, y, (alpha shl 24) or (tint.rgb and 0x00ffffff))
			}
		}
		return tinted
	}

	private fun encodedView(
		image: BufferedImage,
		output: AgentViewOutputSpec,
		revisionId: String,
		kind: String,
		objectIds: List<String>,
		canvasWidth: Float,
		canvasHeight: Float,
		requestedCanvasRect: Bounds,
		canvasRect: Bounds,
		focusRect: Bounds? = null,
		focusLayerIds: List<String> = emptyList(),
		objectScale: Float? = null,
		appliedParameters: Map<String, Float> = emptyMap(),
		outOfRangeParameters: List<AgentParameterRangeDiagnostic> = emptyList(),
		includedLayerIds: List<String> = emptyList(),
		annotatedLayerIds: List<String> = emptyList(),
	): AgentRenderedView {
		val (rendered, png) = encodeWithinBudget(image, output.maxBytes)
		val scale = rendered.width.toFloat() / image.width.toFloat()
		val digest = MessageDigest.getInstance("SHA-256").digest(png).toHex()
		val spatial = AgentViewSpatialMetadata(
			pixelWidth = rendered.width,
			pixelHeight = rendered.height,
			canvasWidth = canvasWidth,
			canvasHeight = canvasHeight,
			requestedViewRect = requestedCanvasRect,
			viewRect = canvasRect,
			focusRect = focusRect,
			focusLayerIds = focusLayerIds,
			objectScale = objectScale,
			canvasUnitsPerPixelX = canvasRect.width / rendered.width.toFloat(),
			canvasUnitsPerPixelY = canvasRect.height / rendered.height.toFloat(),
		)
		val identity = buildString {
			append(revisionId).append('|').append(kind).append('|').append(digest)
			append('|').append(rendered.width).append('x').append(rendered.height)
			append('|').append(canvasWidth).append('x').append(canvasHeight)
			append('|').append(canvasRect.left).append(',').append(canvasRect.top)
			append(',').append(canvasRect.right).append(',').append(canvasRect.bottom)
			append('|').append(requestedCanvasRect.left).append(',').append(requestedCanvasRect.top)
			append(',').append(requestedCanvasRect.right).append(',').append(requestedCanvasRect.bottom)
			append('|').append(focusLayerIds.sorted().joinToString(","))
			append('|').append(objectScale)
		}
		val viewDigest = MessageDigest.getInstance("SHA-256").digest(identity.encodeToByteArray()).toHex()
		return AgentRenderedView(
			viewId = "view-${viewDigest.take(16)}",
			revisionId = revisionId,
			kind = kind,
			objectIds = objectIds,
			png = png,
			originalWidth = image.width,
			originalHeight = image.height,
			renderedWidth = rendered.width,
			renderedHeight = rendered.height,
			canvasRect = canvasRect,
			scale = scale,
			sha256 = digest,
			spatial = spatial,
			appliedParameters = appliedParameters,
			outOfRangeParameters = outOfRangeParameters,
			includedLayerIds = includedLayerIds,
			annotatedLayerIds = annotatedLayerIds,
		)
	}

	private fun encodeWithinBudget(source: BufferedImage, maxBytes: Int): Pair<BufferedImage, ByteArray> {
		var image = source
		var png = encodePng(image)
		while (png.size > maxBytes && maxOf(image.width, image.height) > 64) {
			val budgetRatio = sqrt(maxBytes.toDouble() / png.size.toDouble()).coerceIn(0.25, 0.9)
			val nextScale = (budgetRatio * 0.94).toFloat()
			val next = resize(image, nextScale)
			if (next.width == image.width && next.height == image.height) break
			image = next
			png = encodePng(image)
		}
		require(png.size <= maxBytes) { "PNG cannot satisfy max_bytes=$maxBytes without dropping below 64 px" }
		return image to png
	}

	private fun encodePng(image: BufferedImage): ByteArray = ByteArrayOutputStream().use { bytes ->
		check(ImageIO.write(image, "png", bytes)) { "PNG writer is unavailable" }
		bytes.toByteArray()
	}

	private data class ResolvedFrame(
		val viewRect: Bounds,
		val focusRect: Bounds? = null,
		val focusLayerIds: List<String> = emptyList(),
		val objectScale: Float? = null,
	)

	private data class TargetRaster(
		val width: Int,
		val height: Int,
		val viewRect: Bounds,
		val viewport: CanvasViewport,
	)

	private fun resolveFrame(frame: AgentViewFrame, boundsByLayer: Map<String, Bounds>): ResolvedFrame =
		when (frame) {
			is AgentViewFrame.CanvasRect -> {
				validateRect(frame.rect)
				ResolvedFrame(frame.rect)
			}

			is AgentViewFrame.FocusLayers -> {
				require(frame.layerIds.isNotEmpty()) { "focus_layers requires at least one layer ID" }
				val missing = frame.layerIds - boundsByLayer.keys
				require(missing.isEmpty()) { "Focus layers have no evaluated geometry: ${missing.sorted().joinToString()}" }
				val focus = frame.layerIds.map(boundsByLayer::getValue).reduce(Bounds::union)
				ResolvedFrame(
					viewRect = focusViewRect(focus, frame.objectScale, frame.aspectRatio),
					focusRect = focus,
					focusLayerIds = frame.layerIds.sorted(),
					objectScale = frame.objectScale,
				)
			}
		}

	private fun focusViewRect(focus: Bounds, objectScale: Float, aspectRatio: Float): Bounds {
		validateRect(focus)
		require(objectScale.isFinite() && objectScale in 0.05f..4f) { "object_scale must be between 0.05 and 4" }
		require(aspectRatio.isFinite() && aspectRatio in 0.1f..10f) { "aspect_ratio must be between 0.1 and 10" }
		var width = focus.width.coerceAtLeast(1f)
		var height = focus.height.coerceAtLeast(1f)
		if (width / height < aspectRatio) width = height * aspectRatio else height = width / aspectRatio
		width /= objectScale
		height /= objectScale
		return Bounds(
			focus.centerX - width * 0.5f,
			focus.centerY - height * 0.5f,
			focus.centerX + width * 0.5f,
			focus.centerY + height * 0.5f,
		)
	}

	private fun targetRaster(
		requestedRect: Bounds,
		targetLongEdge: Int,
		canvasWidth: Float,
		canvasHeight: Float,
	): TargetRaster {
		validateRect(requestedRect)
		val scale = targetLongEdge.toDouble() / maxOf(requestedRect.width, requestedRect.height).toDouble()
		val width = maxOf(1, (requestedRect.width * scale).roundToInt())
		val height = maxOf(1, (requestedRect.height * scale).roundToInt())
		// One uniform transform is used in both directions. Expand by at most half a raster pixel so
		// integer output dimensions never introduce a hidden X/Y stretch.
		val effectiveWidth = (width / scale).toFloat()
		val effectiveHeight = (height / scale).toFloat()
		val viewRect = Bounds(
			requestedRect.centerX - effectiveWidth * 0.5f,
			requestedRect.centerY - effectiveHeight * 0.5f,
			requestedRect.centerX + effectiveWidth * 0.5f,
			requestedRect.centerY + effectiveHeight * 0.5f,
		)
		return TargetRaster(
			width = width,
			height = height,
			viewRect = viewRect,
			viewport = CanvasViewport(
				scale = scale,
				offsetX = -viewRect.left * scale,
				offsetY = -viewRect.top * scale,
				canvasWidth = canvasWidth,
				canvasHeight = canvasHeight,
			),
		)
	}

	private fun targetPixelSize(rect: Bounds, targetLongEdge: Int): Pair<Int, Int> {
		validateRect(rect)
		val scale = targetLongEdge.toFloat() / maxOf(rect.width, rect.height)
		return maxOf(1, (rect.width * scale).roundToInt()) to maxOf(1, (rect.height * scale).roundToInt())
	}

	private fun boundsByLayer(model: RigPreviewModel, drawableBounds: Map<String, Bounds>): Map<String, Bounds> {
		val result = linkedMapOf<String, Bounds>()
		for ((drawableId, layerId) in model.rig.layerIdByDrawableId) {
			val bounds = drawableBounds[drawableId] ?: continue
			result[layerId] = result[layerId]?.union(bounds) ?: bounds
		}
		return result
	}

	private fun validateOutput(output: AgentViewOutputSpec) {
		require(output.targetLongEdge in 128..4096) { "target_long_edge must be between 128 and 4096" }
		require(output.maxBytes in 64 * 1024..16 * 1024 * 1024) { "max_bytes must be between 65536 and 16777216" }
	}

	private fun validateRect(rect: Bounds) {
		require(
			listOf(rect.left, rect.top, rect.right, rect.bottom).all(Float::isFinite) && rect.width > 0f && rect.height > 0f,
		) { "Canvas rectangle must contain finite coordinates and have positive width and height" }
	}

	private fun requireMatchingAspect(pixelWidth: Int, pixelHeight: Int, canvasRect: Bounds) {
		val pixelAspect = pixelWidth.toFloat() / pixelHeight.coerceAtLeast(1).toFloat()
		val canvasAspect = canvasRect.width / canvasRect.height
		val relativeError = abs(pixelAspect - canvasAspect) / canvasAspect.coerceAtLeast(1e-6f)
		require(relativeError <= 0.01f) {
			"Layer raster aspect $pixelAspect does not match canvas placement aspect $canvasAspect"
		}
	}

	private fun resize(source: BufferedImage, scale: Float): BufferedImage {
		val width = maxOf(1, (source.width * scale).roundToInt())
		val height = maxOf(1, (source.height * scale).roundToInt())
		val target = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
		val graphics = target.createGraphics()
		try {
			graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
			graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
			graphics.drawImage(source, 0, 0, width, height, null)
		} finally {
			graphics.dispose()
		}
		return target
	}

	private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

	private val ANNOTATION_COLORS = listOf(
		Color(0x00, 0xD8, 0xFF),
		Color(0xFF, 0xC1, 0x07),
		Color(0xFF, 0x5C, 0x93),
		Color(0x7C, 0xFF, 0x6B),
	)
}
