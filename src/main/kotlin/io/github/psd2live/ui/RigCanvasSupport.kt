package io.github.psd2live.ui

import io.github.psd2live.core.Bounds
import io.github.psd2live.core.RigPreviewModel
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.render.eval.DeformedGeometry
import org.umamo.runtime.model.ParameterId
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.geom.Area
import java.awt.geom.Path2D
import kotlin.math.abs

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
	) {
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
		g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
		val drawables = model.rig.puppet.drawables
			.filter { geometry.worldPositions.containsKey(it.id) && it.mesh != null }
			.sortedBy { geometry.drawOrder[it.id] ?: it.drawOrder }
		val drawableById = model.rig.puppet.drawables.associateBy { it.id }
		val originalClip = g.clip
		val originalComposite = g.composite
		for (drawable in drawables) {
			val layerId = model.rig.layerIdByDrawableId[drawable.id.raw]
			if (visibleLayerIds != null && layerId !in visibleLayerIds) continue
			val mesh = drawable.mesh ?: continue
			val positions = geometry.worldPositions[drawable.id] ?: continue
			val pageIndex = model.rig.pageByDrawableId[drawable.id.raw] ?: drawable.texturePage
			val atlas = model.atlas.pages.getOrNull(pageIndex)?.image ?: continue
			val opacity = ((geometry.opacity[drawable.id] ?: drawable.opacity) * alpha).coerceIn(0f, 1f)
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

	fun hitLayers(
		model: RigPreviewModel,
		drawableBounds: Map<String, Bounds>,
		canvasX: Float,
		canvasY: Float,
		visibleLayerIds: Set<String>? = null,
		geometry: DeformedGeometry? = null,
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

				Triple(layerId, bounds.width * bounds.height, drawable.drawOrder)
			}
			.sortedWith(compareBy<Triple<String, Float, Float>> { it.second }.thenByDescending { it.third })
			.map { it.first }
			.distinct()
			.toList()

		if (candidates.isEmpty() && geometry != null) {
			return hitLayers(model, drawableBounds, canvasX, canvasY, visibleLayerIds, geometry = null)
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
	): String? {
		val candidates = hitLayers(model, drawableBounds, canvasX, canvasY, visibleLayerIds, geometry)
		if (candidates.isEmpty()) return null
		val currentIndex = if (currentSelectedLayerId != null) candidates.indexOf(currentSelectedLayerId) else -1
		return if (currentIndex == -1) candidates.first() else candidates[(currentIndex + 1) % candidates.size]
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
