package io.github.psd2live.ui

import io.github.psd2live.core.Bounds
import io.github.psd2live.core.RigPreviewModel
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.render.eval.DeformedGeometry
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.geom.Area
import java.awt.geom.Path2D
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

internal data class CanvasViewport(
	val scale: Double,
	val offsetX: Double,
	val offsetY: Double,
	val canvasWidth: Float,
	val canvasHeight: Float,
) {
	fun x(canvasX: Float): Double = offsetX + canvasX * scale
	fun yFromWorld(worldY: Float): Double = offsetY - worldY * scale
	fun canvasX(screenX: Int): Float = ((screenX - offsetX) / scale).toFloat()
	fun canvasY(screenY: Int): Float = ((screenY - offsetY) / scale).toFloat()
}

/** Normalized viewport passed to the native Cubism renderer. */
internal data class CubismViewport(val scale: Float, val offsetX: Float, val offsetY: Float)

internal object RigCanvasSupport {
	private val evaluator = CpuDeformationEvaluator()

	fun evaluate(model: RigPreviewModel, parameters: Map<ParameterId, Float> = emptyMap()): DeformedGeometry =
		evaluator.evaluate(model.rig.puppet, parameters)

	fun paintChecker(g: Graphics2D, width: Int, height: Int) {
		val cell = 14
		for (row in 0..height / cell) for (column in 0..width / cell) {
			g.color = if ((row + column) and 1 == 0) Color(61, 64, 70) else Color(54, 57, 63)
			g.fillRect(
				column * cell,
				row * cell,
				minOf(cell, width - column * cell),
				minOf(cell, height - row * cell),
			)
		}
	}

	fun paintCanvasBoundary(g: Graphics2D, viewport: CanvasViewport) {
		g.color = Color(198, 205, 216, 105)
		g.stroke = BasicStroke(1f)
		g.drawRect(
			viewport.offsetX.toInt(),
			viewport.offsetY.toInt(),
			(viewport.canvasWidth * viewport.scale).toInt().coerceAtLeast(1),
			(viewport.canvasHeight * viewport.scale).toInt().coerceAtLeast(1),
		)
	}

	fun paintTexturedRig(
		g: Graphics2D,
		model: RigPreviewModel,
		geometry: DeformedGeometry,
		viewport: CanvasViewport,
		alpha: Float = 1f,
		visibleLayerIds: Set<String>? = null,
		drawOrderOverrides: Map<String, Float> = emptyMap(),
		dimUnselected: Boolean = false,
		highlightedLayerIds: Set<String>? = null,
		dimmedAlphaMultiplier: Float = 0.22f,
	) {
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
		g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
		val drawables = model.rig.puppet.drawables
			.filter { geometry.worldPositions.containsKey(it.id) && it.mesh != null }
			.sortedBy {
				val layerId = model.rig.layerIdByDrawableId[it.id.raw]
				drawOrderOverrides[layerId] ?: drawOrderOverrides[it.id.raw] ?: geometry.drawOrder[it.id] ?: it.drawOrder
			}
		val drawableById = model.rig.puppet.drawables.associateBy { it.id }
		val originalClip = g.clip
		val originalComposite = g.composite
		for (drawable in drawables) {
			val layerId = model.rig.layerIdByDrawableId[drawable.id.raw]
			if (visibleLayerIds != null && layerId != null && layerId !in visibleLayerIds) continue
			if (visibleLayerIds != null && layerId == null && drawable.id.raw !in visibleLayerIds && !drawable.isVisible) continue
			val isHighlighted = highlightedLayerIds == null || (layerId != null && layerId in highlightedLayerIds) || drawable.id.raw in highlightedLayerIds
			val effectiveAlpha = if (dimUnselected && !isHighlighted) alpha * dimmedAlphaMultiplier else alpha
			val mesh = drawable.mesh ?: continue
			val positions = geometry.worldPositions[drawable.id] ?: continue
			val pageIndex = model.rig.pageByDrawableId[drawable.id.raw] ?: drawable.texturePage
			val atlas = model.atlas.pages.getOrNull(pageIndex)?.image ?: continue
			val opacity = ((geometry.opacity[drawable.id] ?: drawable.opacity) * effectiveAlpha).coerceIn(0f, 1f)
			if (opacity <= 0.001f) continue
			val maskClip = drawable.maskedBy
				.takeIf { it.isNotEmpty() && !drawable.invertMask }
				?.let { maskIds -> buildMaskArea(maskIds, drawableById, geometry, viewport) }
			g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, opacity)
			for (offset in mesh.indices.indices step 3) {
				val ia = mesh.indices[offset]
				val ib = mesh.indices[offset + 1]
				val ic = mesh.indices[offset + 2]
				val source = doubleArrayOf(
					mesh.uvs[ia * 2].toDouble() * atlas.width, mesh.uvs[ia * 2 + 1].toDouble() * atlas.height,
					mesh.uvs[ib * 2].toDouble() * atlas.width, mesh.uvs[ib * 2 + 1].toDouble() * atlas.height,
					mesh.uvs[ic * 2].toDouble() * atlas.width, mesh.uvs[ic * 2 + 1].toDouble() * atlas.height,
				)
				val target = doubleArrayOf(
					viewport.x(positions[ia * 2]), viewport.yFromWorld(positions[ia * 2 + 1]),
					viewport.x(positions[ib * 2]), viewport.yFromWorld(positions[ib * 2 + 1]),
					viewport.x(positions[ic * 2]), viewport.yFromWorld(positions[ic * 2 + 1]),
				)
				val transform = triangleTransform(source, target) ?: continue
				val triangle = Path2D.Double().apply {
					moveTo(target[0], target[1])
					lineTo(target[2], target[3])
					lineTo(target[4], target[5])
					closePath()
				}
				g.clip = originalClip
				maskClip?.let(g::clip)
				g.clip(triangle)
				g.drawImage(atlas, transform, null)
			}
		}
		g.clip = originalClip
		g.composite = originalComposite
	}

	private fun buildMaskArea(
		maskIds: List<org.umamo.runtime.model.DrawableId>,
		drawableById: Map<org.umamo.runtime.model.DrawableId, org.umamo.runtime.model.Drawable>,
		geometry: DeformedGeometry,
		viewport: CanvasViewport,
	): Area? {
		val area = Area()
		for (maskId in maskIds) {
			val mask = drawableById[maskId] ?: continue
			if (!mask.isVisible) continue
			val mesh = mask.mesh ?: continue
			val positions = geometry.worldPositions[maskId] ?: continue
			for (offset in mesh.indices.indices step 3) {
				val a = mesh.indices[offset] * 2
				val b = mesh.indices[offset + 1] * 2
				val c = mesh.indices[offset + 2] * 2
				area.add(Area(Path2D.Double().apply {
					moveTo(viewport.x(positions[a]), viewport.yFromWorld(positions[a + 1]))
					lineTo(viewport.x(positions[b]), viewport.yFromWorld(positions[b + 1]))
					lineTo(viewport.x(positions[c]), viewport.yFromWorld(positions[c + 1]))
					closePath()
				}))
			}
		}
		return area.takeUnless { it.isEmpty }
	}

	fun boundsByDrawable(geometry: DeformedGeometry): Map<String, Bounds> = buildMap {
		for ((id, positions) in geometry.worldPositions) positionsBounds(positions)?.let { put(id.raw, it) }
	}

	fun boundsByDeformer(model: RigPreviewModel, drawableBounds: Map<String, Bounds>): Map<String, Bounds> {
		val deformerById = model.rig.puppet.deformers.associateBy { it.id }
		val result = linkedMapOf<String, Bounds>()
		for (drawable in model.rig.puppet.drawables) {
			val bounds = drawableBounds[drawable.id.raw] ?: continue
			var parent = drawable.parentDeformerId
			while (parent != null) {
				result[parent.raw] = result[parent.raw]?.union(bounds) ?: bounds
				parent = deformerById[parent]?.parent
			}
		}
		return result
	}

	fun paintBounds(
		g: Graphics2D,
		bounds: Bounds,
		viewport: CanvasViewport,
		color: Color,
		stroke: Float = 1.2f,
	) {
		g.color = color
		g.stroke = BasicStroke(stroke)
		val x = viewport.x(bounds.left)
		val y = viewport.offsetY + bounds.top * viewport.scale
		g.drawRect(
			x.toInt(),
			y.toInt(),
			(bounds.width * viewport.scale).toInt().coerceAtLeast(1),
			(bounds.height * viewport.scale).toInt().coerceAtLeast(1),
		)
	}

	/**
	 * A warp's corner mark as a fraction of the canvas's shorter side.
	 *
	 * Measured against the canvas rather than in screen pixels so the mark belongs to the artwork: it is
	 * the same size on the same rig whatever the zoom, the tab or the window, and it grows and shrinks
	 * with the deformer it sits on. Being a fraction of the canvas rather than a fixed count of its units
	 * also keeps it the same *visual* size across documents authored at different resolutions.
	 */
	private const val DEFORMER_CORNER_LEG_RATIO = 0.012f

	/**
	 * How much larger each mark sharing a corner is than the one inside it, as a fraction of the base leg.
	 *
	 * Every mark keeps the same two edges and the same corner and differs only in length, so they read as
	 * one corner marked out at several scales rather than as several marks that happen to be near each
	 * other.
	 */
	private const val DEFORMER_CORNER_NEST = 0.7f

	/**
	 * A warp's outline for its corner mark: where its lattice actually is, how many cells across it is,
	 * and how far down the deformer chain it sits.
	 */
	internal class DeformerOutline(val id: String, val world: FloatArray, val columns: Int, val depth: Int)

	/**
	 * The outlines to mark, given each warp's lattice from a probe.
	 *
	 * Shared so the painter and the pick describe the same deformers the same way — [worlds] is keyed by
	 * deformer id, and anything missing from it simply gets no mark rather than a misplaced one.
	 */
	fun deformerOutlines(puppet: PuppetModel, worlds: Map<String, FloatArray>): List<DeformerOutline> =
		puppet.deformers.filterIsInstance<Deformer.Warp>().mapNotNull { warp ->
			val world = worlds[warp.id.raw] ?: return@mapNotNull null
			DeformerOutline(warp.id.raw, world, warp.columns, deformerDepth(puppet, warp.id.raw))
		}

	/** How far down the deformer chain [id] sits. The nesting sizes marks by it: the outermost is largest. */
	private fun deformerDepth(puppet: PuppetModel, id: String): Int {
		var depth = 0
		var cursor = puppet.deformers.firstOrNull { it.id.raw == id }?.parent
		// Bounded by what has been walked: a malformed rig with a cycle would otherwise spin here, on
		// every pointer move.
		val walked = mutableSetOf(id)
		while (cursor != null && walked.add(cursor.raw)) {
			depth++
			cursor = puppet.deformers.firstOrNull { it.id == cursor }?.parent
		}
		return depth
	}

	/**
	 * The corner mark of each of [outlines], by deformer id: the triangle each deformer owns, with
	 * everything that will be painted over it already cut away.
	 *
	 * The triangle is built out of the deformer's own corner rather than out of the screen's axes: its
	 * legs run along the two lattice edges that actually meet there, so a lattice that is turned or
	 * sheared carries its mark with it, and the leg is a length in canvas units projected last, so the
	 * mark is part of the artwork rather than an ornament of the viewport.
	 *
	 * Marks sharing a corner are drawn one inside the next rather than pushed apart — pushing them apart
	 * moved a mark off the corner it belonged to, and the corner is the whole point. The outermost
	 * deformer takes the largest, so the nesting reads the way the hierarchy does, and each keeps its own
	 * colour to say which is which.
	 *
	 * What comes back is the *visible* part of each: the smaller marks are subtracted out of the larger
	 * ones. Painting the whole triangle and letting the inner ones cover it does not work, because the
	 * fill is translucent — the covered part still shows through, so a mark lighting up under the pointer
	 * tinted its entire triangle rather than the band of it that can actually be seen. Cutting it away is
	 * also what makes the pick exact with no ordering rule to it: the bands do not overlap, so whichever
	 * one contains the pointer is the one it is on.
	 */
	fun deformerCorners(outlines: List<DeformerOutline>, viewport: CanvasViewport): Map<String, Area> {
		if (outlines.isEmpty()) return emptyMap()
		val baseLeg = min(viewport.canvasWidth, viewport.canvasHeight) * DEFORMER_CORNER_LEG_RATIO
		val result = linkedMapOf<String, Area>()
		for (group in outlines.groupBy { viewport.x(it.world[0]).toInt() to viewport.yFromWorld(it.world[1]).toInt() }.values) {
			// Outermost first. Size runs the other way — the deepest deformer in the group is the mark
			// inside all the others, so it is the one that gets the base leg and stays whole, and each
			// one further out is a step larger and is cut by everything inside it. The list has to run
			// largest to smallest for the subtraction below to read straight.
			val ordered = group.sortedBy { it.depth }
			val marks = ordered.mapIndexedNotNull { index, outline ->
				val leg = baseLeg * (1f + DEFORMER_CORNER_NEST * (ordered.size - 1 - index))
				cornerTriangle(outline, leg, viewport)?.let { outline.id to Area(it) }
			}
			marks.forEachIndexed { index, (id, mark) ->
				for (inner in index + 1 until marks.size) mark.subtract(marks[inner].second)
				result[id] = mark
			}
		}
		return result
	}

	/**
	 * One outline's corner triangle: its first control point, with the legs running along the two lattice
	 * edges that meet there, in screen space.
	 */
	private fun cornerTriangle(outline: DeformerOutline, leg: Float, viewport: CanvasViewport): java.awt.Polygon? {
		val world = outline.world
		val nextRow = (outline.columns + 1) * 2
		if (world.size <= nextRow + 1) return null
		val x0 = world[0]; val y0 = world[1]
		val alongX = world[2] - x0; val alongY = world[3] - y0
		val downX = world[nextRow] - x0; val downY = world[nextRow + 1] - y0
		val alongLength = hypot(alongX, alongY)
		val downLength = hypot(downX, downY)
		// A degenerate edge has no direction to take, so it has no mark either rather than a garbage one.
		if (alongLength < 1e-4f || downLength < 1e-4f) return null
		val legAlongX = alongX / alongLength * leg; val legAlongY = alongY / alongLength * leg
		val legDownX = downX / downLength * leg; val legDownY = downY / downLength * leg
		return java.awt.Polygon(
			intArrayOf(viewport.x(x0).toInt(), viewport.x(x0 + legAlongX).toInt(), viewport.x(x0 + legDownX).toInt()),
			intArrayOf(
				viewport.yFromWorld(y0).toInt(),
				viewport.yFromWorld(y0 + legAlongY).toInt(),
				viewport.yFromWorld(y0 + legDownY).toInt(),
			),
			3,
		)
	}

	/**
	 * Fills [corner] with the deformer's own colour.
	 *
	 * Semi-transparent on purpose: the triangle sits over the artwork and has to mark the corner without
	 * hiding what is under it. Dimming takes it further down, but not all the way — past a certain
	 * faintness it stops reading as something to grab, and being grabbed is the whole of its job.
	 */
	fun paintDeformerCorner(g: Graphics2D, corner: Area, accent: Color, dimmed: Boolean) {
		g.color = Color(accent.red, accent.green, accent.blue, if (dimmed) 120 else 200)
		g.fill(corner)
	}

	fun paintSelectionBounds(
		g: Graphics2D,
		bounds: Bounds,
		viewport: CanvasViewport,
		color: Color,
		stroke: Float = 1.6f,
		isDashed: Boolean = false,
		cornerBracketLength: Int = 10,
	) {
		val x = viewport.x(bounds.left).toInt()
		val y = (viewport.offsetY + bounds.top * viewport.scale).toInt()
		val w = (bounds.width * viewport.scale).toInt().coerceAtLeast(2)
		val h = (bounds.height * viewport.scale).toInt().coerceAtLeast(2)

		g.color = color
		if (isDashed) {
			g.stroke = BasicStroke(stroke, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, floatArrayOf(4f, 4f), 0f)
			g.drawRect(x, y, w, h)
		} else {
			g.stroke = BasicStroke(stroke)
			g.drawRect(x, y, w, h)
			val cl = minOf(cornerBracketLength, w / 3, h / 3)
			if (cl > 2) {
				g.stroke = BasicStroke(stroke * 1.5f)
				// Top-left
				g.drawLine(x, y, x + cl, y)
				g.drawLine(x, y, x, y + cl)
				// Top-right
				g.drawLine(x + w, y, x + w - cl, y)
				g.drawLine(x + w, y, x + w, y + cl)
				// Bottom-left
				g.drawLine(x, y + h, x + cl, y + h)
				g.drawLine(x, y + h, x, y + h - cl)
				// Bottom-right
				g.drawLine(x + w, y + h, x + w - cl, y + h)
				g.drawLine(x + w, y + h, x + w, y + h - cl)
			}
		}
	}

	/**
	 * The value the texture channel sorts this drawable by: the override, then the evaluated order,
	 * then the authored one — the same chain [SkiaRigPainter] draws with.
	 *
	 * Shared rather than written twice so "what is drawn on top is what gets picked" holds by
	 * construction. A draw-order override the picker ignored would leave the pointer selecting a part
	 * the artist can plainly see is behind another.
	 */
	fun displayOrder(
		model: RigPreviewModel,
		drawable: org.umamo.runtime.model.Drawable,
		geometry: DeformedGeometry? = null,
		drawOrderOverrides: Map<String, Float> = emptyMap(),
	): Float = drawOrderOverrides[model.rig.layerIdByDrawableId[drawable.id.raw]]
		?: drawOrderOverrides[drawable.id.raw]
		?: geometry?.drawOrder?.get(drawable.id)
		?: drawable.drawOrder

	/**
	 * The layers under a point, front-most first.
	 *
	 * The order is the render order, not a guess at importance: the part drawn last is the one the
	 * pointer is visibly on, so that is the one a click takes. Clicking again walks back through the
	 * stack (see [nextLayer]), which is how a part buried under another is still reachable.
	 */
	fun hitLayers(
		model: RigPreviewModel,
		drawableBounds: Map<String, Bounds>,
		canvasX: Float,
		canvasY: Float,
		visibleLayerIds: Set<String>? = null,
		geometry: DeformedGeometry? = null,
		drawOrderOverrides: Map<String, Float> = emptyMap(),
	): List<String> {
		val candidates = model.rig.puppet.drawables.asSequence()
			.mapNotNull { drawable ->
				val bounds = drawableBounds[drawable.id.raw] ?: return@mapNotNull null
				if (canvasX !in bounds.left..bounds.right || canvasY !in bounds.top..bounds.bottom) return@mapNotNull null
				val layerId = model.rig.layerIdByDrawableId[drawable.id.raw] ?: return@mapNotNull null
				if (visibleLayerIds != null && layerId !in visibleLayerIds) return@mapNotNull null

				if (geometry != null) {
					val mesh = drawable.mesh
					val positions = geometry.worldPositions[drawable.id]
					if (mesh != null && positions != null && !isPointInMesh(canvasX, canvasY, positions, mesh.indices)) {
						return@mapNotNull null
					}
				}

				layerId to displayOrder(model, drawable, geometry, drawOrderOverrides)
			}
			.sortedByDescending { it.second }
			.map { it.first }
			.distinct()
			.toList()

		if (candidates.isEmpty() && geometry != null) {
			return hitLayers(model, drawableBounds, canvasX, canvasY, visibleLayerIds, geometry = null, drawOrderOverrides = drawOrderOverrides)
		}
		return candidates
	}

	fun hitLayer(
		model: RigPreviewModel,
		drawableBounds: Map<String, Bounds>,
		canvasX: Float,
		canvasY: Float,
		visibleLayerIds: Set<String>? = null,
		currentSelectedLayerId: String? = null,
		geometry: DeformedGeometry? = null,
		drawOrderOverrides: Map<String, Float> = emptyMap(),
	): String? = nextLayer(
		hitLayers(model, drawableBounds, canvasX, canvasY, visibleLayerIds, geometry, drawOrderOverrides),
		currentSelectedLayerId,
	)

	/**
	 * One step through a click-through stack: the layer after [current], wrapping, or the first when
	 * [current] is not in the stack at all. Shared by the preview tab's click-to-select and the canvas
	 * editor's select tool so the two cannot drift into different click orders.
	 */
	fun nextLayer(candidates: List<String>, current: String?): String? {
		if (candidates.isEmpty()) return null
		val index = candidates.indexOf(current)
		return if (index == -1) candidates.first() else candidates[(index + 1) % candidates.size]
	}

	private fun isPointInMesh(
		canvasX: Float,
		canvasY: Float,
		positions: FloatArray,
		indices: IntArray,
	): Boolean {
		for (offset in indices.indices step 3) {
			val a = indices[offset]
			val b = indices[offset + 1]
			val c = indices[offset + 2]
			if (a * 2 + 1 >= positions.size || b * 2 + 1 >= positions.size || c * 2 + 1 >= positions.size) continue
			val x0 = positions[a * 2]
			val y0 = -positions[a * 2 + 1]
			val x1 = positions[b * 2]
			val y1 = -positions[b * 2 + 1]
			val x2 = positions[c * 2]
			val y2 = -positions[c * 2 + 1]
			if (isPointInTriangle(canvasX, canvasY, x0, y0, x1, y1, x2, y2)) {
				return true
			}
		}
		return false
	}

	private fun isPointInTriangle(
		px: Float, py: Float,
		x0: Float, y0: Float,
		x1: Float, y1: Float,
		x2: Float, y2: Float,
	): Boolean {
		val cross0 = (x1 - x0) * (py - y0) - (y1 - y0) * (px - x0)
		val cross1 = (x2 - x1) * (py - y1) - (y2 - y1) * (px - x1)
		val cross2 = (x0 - x2) * (py - y2) - (y0 - y2) * (px - x2)
		val hasNeg = (cross0 < -1e-4f) || (cross1 < -1e-4f) || (cross2 < -1e-4f)
		val hasPos = (cross0 > 1e-4f) || (cross1 > 1e-4f) || (cross2 > 1e-4f)
		return !(hasNeg && hasPos)
	}

	private fun positionsBounds(positions: FloatArray): Bounds? {
		if (positions.size < 2) return null
		var left = Float.POSITIVE_INFINITY
		var top = Float.POSITIVE_INFINITY
		var right = Float.NEGATIVE_INFINITY
		var bottom = Float.NEGATIVE_INFINITY
		for (index in positions.indices step 2) {
			val x = positions[index]
			val y = -positions[index + 1]
			left = minOf(left, x)
			top = minOf(top, y)
			right = maxOf(right, x)
			bottom = maxOf(bottom, y)
		}
		return Bounds(left, top, right, bottom)
	}

	private fun triangleTransform(source: DoubleArray, target: DoubleArray): AffineTransform? {
		val sx0 = source[0]
		val sy0 = source[1]
		val sx1 = source[2]
		val sy1 = source[3]
		val sx2 = source[4]
		val sy2 = source[5]
		val denominator = sx0 * (sy1 - sy2) + sx1 * (sy2 - sy0) + sx2 * (sy0 - sy1)
		if (abs(denominator) < 1e-8) return null
		fun coefficient(v0: Double, v1: Double, v2: Double): DoubleArray {
			val x = (v0 * (sy1 - sy2) + v1 * (sy2 - sy0) + v2 * (sy0 - sy1)) / denominator
			val y = (v0 * (sx2 - sx1) + v1 * (sx0 - sx2) + v2 * (sx1 - sx0)) / denominator
			val translation = (
				v0 * (sx1 * sy2 - sx2 * sy1) +
					v1 * (sx2 * sy0 - sx0 * sy2) +
					v2 * (sx0 * sy1 - sx1 * sy0)
				) / denominator
			return doubleArrayOf(x, y, translation)
		}
		val x = coefficient(target[0], target[2], target[4])
		val y = coefficient(target[1], target[3], target[5])
		return AffineTransform(x[0], y[0], x[1], y[1], x[2], y[2])
	}
}
