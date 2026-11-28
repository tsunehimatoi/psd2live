package io.github.psd2live.application

import io.github.psd2live.core.RigInformationOverlay

import io.github.psd2live.project.WorkspaceParameterRangeDiagnostic
import io.github.psd2live.project.WorkspaceRenderedView
import io.github.psd2live.project.WorkspaceViewBackground
import io.github.psd2live.project.WorkspaceViewFrame
import io.github.psd2live.project.WorkspaceViewOutputSpec
import io.github.psd2live.project.WorkspaceViewSpatialMetadata

import io.github.psd2live.core.Bounds
import io.github.psd2live.core.CanvasDensity
import io.github.psd2live.core.LayerSpace
import io.github.psd2live.core.textureLayer
import io.github.psd2live.project.WorkspaceViewReferenceMode
import io.github.psd2live.core.PreviewRenderer
import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.core.CanvasViewport
import io.github.psd2live.core.RigCanvasSupport
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

/** The reference image as a view draws it: its pixels over [rect] on the canvas, turned [rotation] degrees about its centre. */
class ViewReferenceImage(val image: BufferedImage, val rect: io.github.psd2live.project.LayerCanvasRect,
                                  val mode: io.github.psd2live.project.WorkspaceViewReferenceMode, val opacity: Float, val rotation: Float = 0f)

/** A material item a view outlines: its pixels over [rect] on the canvas. */
class ViewOutline(val id: String, val image: BufferedImage, val rect: io.github.psd2live.project.LayerCanvasRect)

object WorkspaceViewRenderer {
	private val OUTLINE_COLORS = listOf(Color(0x00, 0xE5, 0xFF), Color(0xFF, 0x3D, 0xC8), Color(0xFF, 0xD6, 0x00), Color(0x64, 0xFF, 0x5A),
		Color(0xFF, 0x7A, 0x1A), Color(0x9C, 0x7B, 0xFF))
	private const val GRID_MIN_PIXELS = 14.0
	private val HEAT_LOW = Color(0x3D, 0x7B, 0xE0)
	private val HEAT_MID = Color(0x4C, 0xB8, 0x6A)
	private val HEAT_HIGH = Color(0xE5, 0x68, 0x3B)

	/** The heatmap colour of [texelsPerUnit]: blue at a quarter texel per canvas unit or less, green at one, orange at four or more. */
	fun heatColor(texelsPerUnit: Float): Color {
		val heat = if (!(texelsPerUnit > 0f)) -1f else (kotlin.math.ln(texelsPerUnit.toDouble()) / kotlin.math.ln(2.0) / 2.0).toFloat().coerceIn(-1f, 1f)
		val (from, to, t) = if (heat < 0f) Triple(HEAT_MID, HEAT_LOW, -heat) else Triple(HEAT_MID, HEAT_HIGH, heat)
		fun mix(a: Int, b: Int) = (a + (b - a) * t).roundToInt().coerceIn(0, 255)
		return Color(mix(from.red, to.red), mix(from.green, to.green), mix(from.blue, to.blue))
	}

	/** A layer's texture pixels per canvas unit: its own raster over its canvas rectangle, the mean of both axes. */
	fun texelsPerUnit(layer: SourceLayer): Float {
		val space = LayerSpace.of(layer.textureLayer)
		return (space.scaleX + space.scaleY) / 2f
	}

	fun modelComposite(
		model: RigPreviewModel,
		revisionId: String,
		parameters: Map<String, Float>,
		includeLayerIds: Set<String>,
		annotateLayerIds: Set<String>,
		frame: WorkspaceViewFrame,
		background: WorkspaceViewBackground,
		output: WorkspaceViewOutputSpec,
		annotateDeformerIds: Set<String> = emptySet(),
		annotatePathIds: Set<String> = emptySet(),
		annotatePathWidth: Boolean = false,
		annotatePathHardness: Boolean = false,
		annotatePathRadius: Boolean = false,
		pointIndices: Boolean = false,
		worldPositions: Map<org.umamo.runtime.model.DrawableId, FloatArray> = emptyMap(),
		reference: ViewReferenceImage? = null,
		density: Boolean = false,
		grid: Float? = null,
		outlines: List<ViewOutline> = emptyList(),
		/** Reference layers pinned to another keyform than this pose's ([io.github.psd2live.project.ReferencePins]): left out. */
		hiddenReferenceLayerIds: Set<String> = emptySet(),
	): WorkspaceRenderedView {
		require(grid == null || (grid.isFinite() && grid > 0f)) { "grid is a positive step in canvas units" }
		require(outlines.size <= 24) { "Outline at most 24 materials per View" }
		validateOutput(output)
		require(annotateDeformerIds.size <= 16) { "Select at most 16 deformers per View" }
		require(annotatePathIds.size <= 32) { "Select at most 32 deform paths per View" }
		val knownLayerIds = model.rig.layerIdByDrawableId.values.toSet()
		val unknownIncluded = includeLayerIds - knownLayerIds
		require(unknownIncluded.isEmpty()) { "Unknown included layer IDs: ${unknownIncluded.sorted().joinToString()}" }
		val unknownAnnotated = annotateLayerIds - knownLayerIds
		require(unknownAnnotated.isEmpty()) { "Unknown annotation layer IDs: ${unknownAnnotated.sorted().joinToString()}" }
		// References pinned to another keyform stay out of this pose, quads and those in the model alike.
		@Suppress("NAME_SHADOWING") val includeLayerIds = includeLayerIds - hiddenReferenceLayerIds

		val typedParameters = parameters.mapKeys { ParameterId(it.key) }
		val outOfRangeParameters = model.rig.puppet.parameters.mapNotNull { parameter ->
			val value = parameters[parameter.id.raw] ?: return@mapNotNull null
			if (value < parameter.min || value > parameter.max) {
				WorkspaceParameterRangeDiagnostic(parameter.id.raw, value, parameter.min, parameter.max)
			} else {
				null
			}
		}
		val evaluated = RigCanvasSupport.evaluateExact(model, typedParameters)
		worldPositions.forEach { (id, vertices) ->
			val drawable = requireNotNull(model.rig.puppet.drawables.firstOrNull { it.id == id }) { "Unknown simulation mesh: ${id.raw}" }
			require(vertices.size == requireNotNull(drawable.mesh).positions.size && vertices.all(Float::isFinite)) { "Invalid simulated mesh coordinates: ${id.raw}" }
		}
		val geometry = if (worldPositions.isEmpty()) evaluated else evaluated.withWorldPositions(
			evaluated.worldPositions + worldPositions.filterKeys { it in evaluated.worldPositions })
		val drawableBounds = RigCanvasSupport.boundsByDrawable(geometry)
		val boundsByLayer = boundsByLayer(model, drawableBounds)
		val resolvedFrame = resolveFrame(frame, boundsByLayer)
		val canvasWidth = model.analysis.source.widthPx.coerceAtLeast(1).toFloat()
		val canvasHeight = model.analysis.source.heightPx.coerceAtLeast(1).toFloat()
		val target = targetRaster(resolvedFrame.viewRect, output.targetLongEdge, canvasWidth, canvasHeight)
		val image = when (background) {
			WorkspaceViewBackground.TRANSPARENT -> BufferedImage(target.width, target.height, BufferedImage.TYPE_INT_ARGB)
			WorkspaceViewBackground.CHECKERBOARD -> checkerboard(target.width, target.height)
		}
		var matchedPathIds = emptyList<String>()
		val densities = if (!density) emptyMap() else model.analysis.layers.filter { it.source.id.raw in includeLayerIds }
			.associate { it.source.id.raw to texelsPerUnit(it.source) }
		var difference: Float? = null
		if (reference?.mode == WorkspaceViewReferenceMode.UNDER) paintReference(image, reference, target.viewport)
		// Reference layers lie under the model, as edit canvases draw them by default; a difference compares the model alone.
		if (reference?.mode != WorkspaceViewReferenceMode.DIFFERENCE) paintReferenceLayers(image, model, geometry, target.viewport, includeLayerIds - hiddenReferenceLayerIds)
		val graphics = image.createGraphics()
		try {
			graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
			if (reference?.mode == WorkspaceViewReferenceMode.DIFFERENCE) {
				val rendered = BufferedImage(target.width, target.height, BufferedImage.TYPE_INT_ARGB)
				val g = rendered.createGraphics()
				try { RigCanvasSupport.paintTexturedRig(g, model, geometry, target.viewport, visibleLayerIds = includeLayerIds) } finally { g.dispose() }
				val referenceImage = BufferedImage(target.width, target.height, BufferedImage.TYPE_INT_ARGB)
				paintReference(referenceImage, ViewReferenceImage(reference.image, reference.rect, reference.mode, 1f), target.viewport)
				difference = paintDifference(image, rendered, referenceImage)
			} else {
				RigCanvasSupport.paintTexturedRig(
					graphics,
					model,
					geometry,
					target.viewport,
					visibleLayerIds = includeLayerIds,
				)
			}
			if (density) paintDensity(graphics, model, geometry, target.viewport, densities)
			paintLayerAnnotations(graphics, model, drawableBounds, target.viewport, annotateLayerIds, image.width, image.height)
			io.github.psd2live.core.RigInformationOverlay.paint(graphics, model.rig.puppet, typedParameters,
				target.viewport, annotateDeformerIds, pointIndices = pointIndices)
			matchedPathIds = io.github.psd2live.core.RigInformationOverlay.paintDeformPaths(
				graphics, model.rig.puppet, geometry,
				target.viewport, annotatePathIds,
				pointIndices = pointIndices,
				showWidth = annotatePathWidth,
				showHardness = annotatePathHardness,
				showRadius = annotatePathRadius,
			)
		} finally {
			graphics.dispose()
		}
		if (reference?.mode == WorkspaceViewReferenceMode.OVER) paintReference(image, reference, target.viewport)
		outlines.forEachIndexed { index, outline -> paintOutline(image, outline, target.viewport, OUTLINE_COLORS[index % OUTLINE_COLORS.size]) }
		val gridStep = grid?.let { paintGrid(image, target.viewport, it) }

		return encodedView(
			image = image,
			output = output,
			revisionId = revisionId,
			kind = "model-composite-png",
			objectIds = (includeLayerIds + annotateLayerIds + annotateDeformerIds + matchedPathIds).sorted(),
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
			identityExtra = listOf(annotateDeformerIds.sorted(), matchedPathIds.sorted(), listOf(annotatePathWidth, annotatePathHardness,
				annotatePathRadius, pointIndices, reference?.mode, reference?.opacity, density, gridStep, outlines.map { it.id })).joinToString("|"),
		).copy(
			annotatedDeformerIds = annotateDeformerIds.sorted(),
			annotatedPathIds = matchedPathIds.sorted(),
			annotatedPathWidth = annotatePathWidth || annotatePathRadius,
			annotatedPathHardness = annotatePathHardness || annotatePathRadius,
			annotatedPathRadius = annotatePathRadius,
			pointIndices = pointIndices,
			referenceMode = reference?.mode,
			referenceDifference = difference,
			texelDensity = densities.toSortedMap(),
			gridStep = gridStep,
			outlinedMaterialIds = outlines.map { it.id },
		)
	}

	/** The edge of [outline]'s opaque pixels where they sit on the canvas, traced in [color], with its id at its corner. */
	private fun paintOutline(image: BufferedImage, outline: ViewOutline, viewport: CanvasViewport, color: Color) {
		val left = viewport.x(outline.rect.left); val top = viewport.offsetY + outline.rect.top * viewport.scale
		val width = outline.rect.width * viewport.scale; val height = outline.rect.height * viewport.scale
		val x0 = kotlin.math.floor(left).toInt().coerceIn(0, image.width); val y0 = kotlin.math.floor(top).toInt().coerceIn(0, image.height)
		val x1 = kotlin.math.ceil(left + width).toInt().coerceIn(0, image.width); val y1 = kotlin.math.ceil(top + height).toInt().coerceIn(0, image.height)
		if (x1 <= x0 || y1 <= y0) return
		// Each view pixel is inside when the item's raster is opaque at its centre.
		val w = x1 - x0; val h = y1 - y0
		val inside = BooleanArray(w * h)
		val sx = outline.image.width / width; val sy = outline.image.height / height
		for (y in 0 until h) for (x in 0 until w) {
			val u = kotlin.math.floor((x0 + x + 0.5 - left) * sx).toInt(); val v = kotlin.math.floor((y0 + y + 0.5 - top) * sy).toInt()
			if (u in 0 until outline.image.width && v in 0 until outline.image.height) inside[y * w + x] = (outline.image.getRGB(u, v) ushr 24) >= 128
		}
		val rgb = (0xFF shl 24) or (color.rgb and 0xFFFFFF)
		for (y in 0 until h) for (x in 0 until w) {
			if (!inside[y * w + x]) continue
			val edge = x == 0 || y == 0 || x == w - 1 || y == h - 1 || !inside[y * w + x - 1] || !inside[y * w + x + 1] || !inside[(y - 1) * w + x] || !inside[(y + 1) * w + x]
			if (edge) image.setRGB(x0 + x, y0 + y, rgb)
		}
		val g = image.createGraphics()
		try {
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
			g.font = java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, 11)
			label(g, outline.id, left.toInt().coerceIn(0, image.width - 1) + 2, (top.toInt() - 3).coerceIn(12, image.height - 1), color)
		} finally { g.dispose() }
	}

	/**
	 * Grid lines every [requested] canvas units - coarsened by 2, 5, 10... until they are apart enough to read - each
	 * fifth line stronger and labelled with its canvas coordinate along the top and left. Returns the step drawn.
	 */
	private fun paintGrid(image: BufferedImage, viewport: CanvasViewport, requested: Float): Float {
		val step = generateSequence(1.0) { it * 10.0 }.flatMap { sequenceOf(it, it * 2.0, it * 5.0) }
			.map { requested * it }.first { it * viewport.scale >= GRID_MIN_PIXELS }
		val g = image.createGraphics()
		try {
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
			g.font = java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.PLAIN, 10)
			val metrics = g.fontMetrics
			val left = viewport.canvasX(0f); val right = viewport.canvasX(image.width.toFloat())
			val top = viewport.canvasY(0f); val bottom = viewport.canvasY(image.height.toFloat())
			fun lines(from: Float, to: Float) = kotlin.math.ceil(from / step).toLong()..kotlin.math.floor(to / step).toLong()
			fun at(index: Long) = viewport.x((index * step).toFloat()).roundToInt()
			fun atY(index: Long) = (viewport.offsetY + index * step * viewport.scale).roundToInt()
			fun stroke(index: Long) = if (index % 5L == 0L) Color(0, 0, 0, 110) to Color(255, 255, 255, 140) else Color(0, 0, 0, 55) to Color(255, 255, 255, 70)
			for (index in lines(left, right)) {
				val (dark, light) = stroke(index)
				g.color = dark; g.drawLine(at(index), 0, at(index), image.height); g.color = light; g.drawLine(at(index) + 1, 0, at(index) + 1, image.height)
			}
			for (index in lines(top, bottom)) {
				val (dark, light) = stroke(index)
				g.color = dark; g.drawLine(0, atY(index), image.width, atY(index)); g.color = light; g.drawLine(0, atY(index) + 1, image.width, atY(index) + 1)
			}
			// Labels on the stronger lines, or on fewer when those crowd.
			val labelEvery = generateSequence(5L) { it * 2L }.first { it * step * viewport.scale >= metrics.stringWidth(number(right.toDouble())) + 8 }
			for (index in lines(left, right)) if (index % labelEvery == 0L) label(g, number(index * step), at(index) + 3, metrics.ascent + 2, Color.WHITE)
			for (index in lines(top, bottom)) if (index % labelEvery == 0L) label(g, number(index * step), 3, atY(index) + metrics.ascent + 3, Color.WHITE)
		} finally { g.dispose() }
		return step.toFloat()
	}

	private fun number(value: Double): String =
		if (value == kotlin.math.floor(value)) value.toLong().toString() else "%.2f".format(java.util.Locale.ROOT, value)

	/** [text] on a dark plate with its baseline at [x], [y]. */
	private fun label(g: java.awt.Graphics2D, text: String, x: Int, y: Int, color: Color) {
		val metrics = g.fontMetrics
		g.color = Color(0, 0, 0, 170)
		g.fillRect(x - 2, y - metrics.ascent - 1, metrics.stringWidth(text) + 4, metrics.ascent + metrics.descent + 2)
		g.color = color
		g.drawString(text, x, y)
	}

	/** [model]'s reference layers in [included], back to front, each image over where [geometry] puts its quad, at its opacity. */
	private fun paintReferenceLayers(image: BufferedImage, model: RigPreviewModel, geometry: org.umamo.render.eval.DeformedGeometry,
	                                 viewport: CanvasViewport, included: Set<String>) {
		val ids = model.referenceLayerIds.filter { it in included }.toSet()
		if (ids.isEmpty()) return
		val quadOf = model.rig.puppet.drawables.associateBy { model.rig.ownLayerIdByDrawableId[it.id.raw] }
		val g = image.createGraphics()
		try {
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
			for (layer in model.analysis.source.layers.filter { it.id.raw in ids }.sortedByDescending { it.order }) {
				val quad = quadOf[layer.id.raw] ?: continue
				val world = geometry.worldPositions[quad.id] ?: continue
				val raster = layer.raster
				if (raster.width <= 0 || raster.height <= 0) continue
				val pixels = BufferedImage(raster.width, raster.height, BufferedImage.TYPE_INT_ARGB)
				val argb = IntArray(raster.width * raster.height) { i ->
					val o = i * 4
					((raster.rgba[o + 3].toInt() and 0xFF) shl 24) or ((raster.rgba[o].toInt() and 0xFF) shl 16) or
						((raster.rgba[o + 1].toInt() and 0xFF) shl 8) or (raster.rgba[o + 2].toInt() and 0xFF)
				}
				pixels.setRGB(0, 0, raster.width, raster.height, argb, 0, raster.width)
				fun sx(i: Int) = viewport.x(world[i * 2])
				fun sy(i: Int) = viewport.yFromWorld(world[i * 2 + 1])
				val w = raster.width.toDouble(); val h = raster.height.toDouble()
				g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (geometry.opacity[quad.id] ?: quad.opacity).coerceIn(0f, 1f))
				g.drawImage(pixels, AffineTransform((sx(1) - sx(0)) / w, (sy(1) - sy(0)) / w, (sx(3) - sx(0)) / h, (sy(3) - sy(0)) / h, sx(0), sy(0)), null)
			}
		} finally { g.dispose() }
	}

	/** [reference] drawn over [image] at its opacity, where it sits on the canvas. */
	private fun paintReference(image: BufferedImage, reference: ViewReferenceImage, viewport: CanvasViewport) {
		val g = image.createGraphics()
		try {
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
			g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, reference.opacity.coerceIn(0f, 1f))
			val left = viewport.x(reference.rect.left); val top = viewport.offsetY + reference.rect.top * viewport.scale
			val transform = AffineTransform(reference.rect.width * viewport.scale / reference.image.width, 0.0, 0.0,
				reference.rect.height * viewport.scale / reference.image.height, left, top)
			if (reference.rotation != 0f) transform.preConcatenate(AffineTransform.getRotateInstance(Math.toRadians(reference.rotation.toDouble()),
				left + reference.rect.width * viewport.scale / 2.0, top + reference.rect.height * viewport.scale / 2.0))
			g.drawImage(reference.image, transform, null)
		} finally { g.dispose() }
	}

	/**
	 * The per-channel difference of [rendered] and [reference], each over mid grey, into [out] (black where they match);
	 * returns the mean difference over pixels either covers.
	 */
	internal fun paintDifference(out: BufferedImage, rendered: BufferedImage, reference: BufferedImage): Float {
		var sum = 0.0; var count = 0
		fun over(c: Int, alpha: Float, shift: Int) = ((c shr shift) and 0xFF) * alpha + 128f * (1f - alpha)
		for (y in 0 until out.height) for (x in 0 until out.width) {
			val a = rendered.getRGB(x, y); val b = reference.getRGB(x, y)
			val aa = (a ushr 24) / 255f; val ba = (b ushr 24) / 255f
			if (aa <= 0f && ba <= 0f) { out.setRGB(x, y, 0xFF shl 24); continue }
			val dr = abs(over(a, aa, 16) - over(b, ba, 16)); val dg = abs(over(a, aa, 8) - over(b, ba, 8)); val db = abs(over(a, aa, 0) - over(b, ba, 0))
			sum += (dr + dg + db) / (3.0 * 255.0); count++
			out.setRGB(x, y, (0xFF shl 24) or (dr.roundToInt().coerceIn(0, 255) shl 16) or (dg.roundToInt().coerceIn(0, 255) shl 8) or db.roundToInt().coerceIn(0, 255))
		}
		return if (count == 0) 0f else (sum / count).toFloat()
	}

	/** Each drawn layer's mesh filled with the heat colour of its density, over the art. */
	private fun paintDensity(g: java.awt.Graphics2D, model: RigPreviewModel, geometry: org.umamo.render.eval.DeformedGeometry,
	                         viewport: CanvasViewport, densities: Map<String, Float>) {
		val saved = g.composite
		g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_ATOP, 0.6f)
		for (drawable in model.rig.puppet.drawables) {
			val layerId = model.rig.layerIdByDrawableId[drawable.id.raw] ?: continue
			val value = densities[layerId] ?: continue
			val mesh = drawable.mesh ?: continue
			val positions = geometry.worldPositions[drawable.id] ?: continue
			val path = java.awt.geom.Path2D.Double()
			for (offset in mesh.indices.indices step 3) {
				val i = mesh.indices[offset]; val j = mesh.indices[offset + 1]; val k = mesh.indices[offset + 2]
				path.moveTo(viewport.x(positions[i * 2]), viewport.yFromWorld(positions[i * 2 + 1]))
				path.lineTo(viewport.x(positions[j * 2]), viewport.yFromWorld(positions[j * 2 + 1]))
				path.lineTo(viewport.x(positions[k * 2]), viewport.yFromWorld(positions[k * 2 + 1]))
				path.closePath()
			}
			g.color = heatColor(value)
			g.fill(path)
		}
		g.composite = saved
	}

	fun isolatedLayer(
		layer: SourceLayer,
		canvasWidth: Int,
		canvasHeight: Int,
		revisionId: String,
		background: WorkspaceViewBackground,
		output: WorkspaceViewOutputSpec,
	): WorkspaceRenderedView {
		validateOutput(output)
		val foreground = PreviewRenderer.rasterImage(layer.raster.width, layer.raster.height, layer.raster.rgba)
		val canvasRect = if (!CanvasDensity.dense(layer)) Bounds(
			layer.bounds.left.toFloat(),
			layer.bounds.top.toFloat(),
			(layer.bounds.left + layer.bounds.width).toFloat(),
			(layer.bounds.top + layer.bounds.height).toFloat(),
		) else LayerSpace.of(layer).canvasBounds()
		if (!CanvasDensity.dense(layer)) requireMatchingAspect(foreground.width, foreground.height, canvasRect)
		val size = targetPixelSize(canvasRect, output.targetLongEdge)
		val image = when (background) {
			WorkspaceViewBackground.TRANSPARENT -> BufferedImage(size.first, size.second, BufferedImage.TYPE_INT_ARGB)
			WorkspaceViewBackground.CHECKERBOARD -> checkerboard(size.first, size.second)
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
		background: WorkspaceViewBackground,
		output: WorkspaceViewOutputSpec,
	): WorkspaceRenderedView {
		validateOutput(output)
		val viewRect = focusViewRect(focusRect, objectScale, aspectRatio)
		val target = targetRaster(viewRect, output.targetLongEdge, composite.width.toFloat(), composite.height.toFloat())
		val image = when (background) {
			WorkspaceViewBackground.TRANSPARENT -> BufferedImage(target.width, target.height, BufferedImage.TYPE_INT_ARGB)
			WorkspaceViewBackground.CHECKERBOARD -> checkerboard(target.width, target.height)
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
				if (!CanvasDensity.dense(layer)) translate(layer.bounds.left.toDouble(), layer.bounds.top.toDouble()) else {
					// A raster denser or sparser than its canvas rectangle is stretched over it.
					val space = LayerSpace.of(layer)
					translate(space.left.toDouble(), space.top.toDouble())
					scale((space.width / layer.raster.width).toDouble(), (space.height / layer.raster.height).toDouble())
				}
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

	internal fun checkerboard(width: Int, height: Int): BufferedImage {
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
		output: WorkspaceViewOutputSpec,
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
		outOfRangeParameters: List<WorkspaceParameterRangeDiagnostic> = emptyList(),
		includedLayerIds: List<String> = emptyList(),
		annotatedLayerIds: List<String> = emptyList(),
		/** Whatever else the stored view record holds that the pixels may not show. */
		identityExtra: String = "",
	): WorkspaceRenderedView {
		val (rendered, png) = encodeWithinBudget(image, output.maxBytes)
		val scale = rendered.width.toFloat() / image.width.toFloat()
		val digest = MessageDigest.getInstance("SHA-256").digest(png).toHex()
		val spatial = WorkspaceViewSpatialMetadata(
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
			// Two requests can draw the same pixels - a parameter nothing reads - yet record different views.
			append('|').append(appliedParameters.toSortedMap().entries.joinToString(",") { "${it.key}=${it.value}" })
			append('|').append(includedLayerIds.sorted().joinToString(",")).append('|').append(annotatedLayerIds.sorted().joinToString(","))
			append('|').append(identityExtra)
		}
		val viewDigest = MessageDigest.getInstance("SHA-256").digest(identity.encodeToByteArray()).toHex()
		return WorkspaceRenderedView(
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

	internal fun encodeWithinBudget(source: BufferedImage, maxBytes: Int): Pair<BufferedImage, ByteArray> {
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

	private fun resolveFrame(frame: WorkspaceViewFrame, boundsByLayer: Map<String, Bounds>): ResolvedFrame =
		when (frame) {
			is WorkspaceViewFrame.CanvasRect -> {
				validateRect(frame.rect)
				ResolvedFrame(frame.rect)
			}

			is WorkspaceViewFrame.FocusLayers -> {
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

	private fun validateOutput(output: WorkspaceViewOutputSpec) {
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
