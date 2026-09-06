package io.github.psd2live.core

import org.umamo.format.art.ChannelMask
import org.umamo.format.art.LayerBlend
import org.umamo.format.art.LayerBounds
import org.umamo.format.art.LayerId
import org.umamo.format.art.LayerRaster
import org.umamo.format.art.SourceLayer
import org.umamo.format.art.SourceLayerKind
import java.util.ArrayDeque

/**
 * Mesh-based bilateral layer splitter.
 *
 * Automatically detects when a layer's generated adaptive mesh consists of two disconnected
 * mesh components (e.g. left and right eyes, eyebrows, hair locks, limbs, shoes) and splits
 * them into distinct layers named with `-l` and `-r`.
 */
object ComponentSplitter {
	private val nonSplittableTags = setOf(
		SemanticTag.FACE,
		SemanticTag.MOUTH,
		SemanticTag.MOUTH_OPEN,
		SemanticTag.MOUTH_CLOSE,
		SemanticTag.TOOTH_T,
		SemanticTag.TOOTH_B,
		SemanticTag.TONGUE,
	)

	fun split(
		layer: ClassifiedLayer,
		meshSpacing: Float = 64f,
		alphaThreshold: Int = 8,
	): List<ClassifiedLayer> = splitInternal(layer, meshSpacing, alphaThreshold)

	fun split(
		layer: ClassifiedLayer,
		faceCenterX: Float,
		alphaThreshold: Int,
		meshSpacing: Float = 64f,
	): List<ClassifiedLayer> = splitInternal(layer, meshSpacing, alphaThreshold)

	private fun splitInternal(
		layer: ClassifiedLayer,
		meshSpacing: Float,
		alphaThreshold: Int,
	): List<ClassifiedLayer> {
		if (layer.semantic.side != Side.NONE || layer.semantic.tag in nonSplittableTags) return listOf(layer)
		val source = layer.source
		val width = source.raster.width
		val height = source.raster.height
		if (width <= 1 || height <= 1 || layer.opaquePixels <= 0) return listOf(layer)

		val semanticDensity = when (layer.semantic.tag) {
			SemanticTag.FACE, SemanticTag.FRONT_HAIR, SemanticTag.BACK_HAIR, SemanticTag.TOPWEAR -> 0.65f
			SemanticTag.IRIDES, SemanticTag.EYELASH, SemanticTag.EYEWHITE, SemanticTag.EYEBROW,
			SemanticTag.MOUTH, SemanticTag.MOUTH_OPEN, SemanticTag.MOUTH_CLOSE,
			SemanticTag.TOOTH_T, SemanticTag.TOOTH_B, SemanticTag.TONGUE -> 0.45f
			else -> 1f
		}
		val effectiveSpacing = kotlin.math.max(6f, meshSpacing * semanticDensity)

		val mesh = AdaptiveMeshGenerator.generate(
			width = width,
			height = height,
			rgba = source.raster.rgba,
			alphaThreshold = alphaThreshold,
			spacing = effectiveSpacing,
		) ?: return listOf(layer)

		val positions = mesh.positions
		val indices = mesh.indices
		val vertexCount = positions.size / 2
		val triangleCount = indices.size / 3
		if (vertexCount < 6 || triangleCount < 2) return listOf(layer)

		val parent = IntArray(vertexCount) { it }
		fun find(i: Int): Int {
			var r = i
			while (r != parent[r]) r = parent[r]
			var curr = i
			while (curr != r) {
				val next = parent[curr]
				parent[curr] = r
				curr = next
			}
			return r
		}
		fun union(i: Int, j: Int) {
			val ri = find(i)
			val rj = find(j)
			if (ri != rj) parent[ri] = rj
		}

		for (t in 0 until indices.size step 3) {
			union(indices[t], indices[t + 1])
			union(indices[t + 1], indices[t + 2])
		}

		val componentVertices = mutableMapOf<Int, MutableList<Int>>()
		for (v in 0 until vertexCount) {
			componentVertices.getOrPut(find(v)) { mutableListOf() }.add(v)
		}

		val validComponents = componentVertices.values.filter { it.size >= 3 }
		val targetComponents = if (validComponents.size == 2) {
			validComponents
		} else if (validComponents.size > 2) {
			val sorted = validComponents.sortedByDescending { it.size }
			val thirdSize = sorted[2].size
			if (thirdSize < sorted[1].size * 0.08 && (sorted[0].size + sorted[1].size).toDouble() / vertexCount > 0.90) {
				listOf(sorted[0], sorted[1])
			} else {
				return listOf(layer)
			}
		} else {
			return listOf(layer)
		}

		fun centroid(verts: List<Int>): Pair<Float, Float> {
			var sumX = 0.0
			var sumY = 0.0
			for (v in verts) {
				sumX += source.bounds.left + positions[v * 2]
				sumY += source.bounds.top + positions[v * 2 + 1]
			}
			return (sumX / verts.size).toFloat() to (sumY / verts.size).toFloat()
		}

		val compA = targetComponents[0]
		val compB = targetComponents[1]
		val (cAx, _) = centroid(compA)
		val (cBx, _) = centroid(compB)

		val (leftVerts, rightVerts) = if (cAx <= cBx) {
			compA to compB
		} else {
			compB to compA
		}

		var lMinX = Float.POSITIVE_INFINITY
		var lMaxX = Float.NEGATIVE_INFINITY
		for (v in leftVerts) {
			val x = positions[v * 2]
			if (x < lMinX) lMinX = x
			if (x > lMaxX) lMaxX = x
		}
		var rMinX = Float.POSITIVE_INFINITY
		var rMaxX = Float.NEGATIVE_INFINITY
		for (v in rightVerts) {
			val x = positions[v * 2]
			if (x < rMinX) rMinX = x
			if (x > rMaxX) rMaxX = x
		}

		fun distSq(px: Float, py: Float, verts: List<Int>): Float {
			var minD = Float.POSITIVE_INFINITY
			for (v in verts) {
				val dx = positions[v * 2] - px
				val dy = positions[v * 2 + 1] - py
				val d = dx * dx + dy * dy
				if (d < minD) minD = d
			}
			return minD
		}

		val hasHorizontalGap = lMaxX < rMinX
		val splitX = (lMaxX + rMinX) * 0.5f

		val leftPixels = mutableListOf<Int>()
		val rightPixels = mutableListOf<Int>()
		val rgba = source.raster.rgba

		for (y in 0 until height) {
			for (x in 0 until width) {
				val pixelIndex = y * width + x
				if (alphaAt(rgba, pixelIndex) < alphaThreshold) continue
				val belongsToLeft = if (hasHorizontalGap) {
					x < splitX
				} else {
					distSq(x + 0.5f, y + 0.5f, leftVerts) <= distSq(x + 0.5f, y + 0.5f, rightVerts)
				}
				if (belongsToLeft) {
					leftPixels += pixelIndex
				} else {
					rightPixels += pixelIndex
				}
			}
		}

		if (leftPixels.isEmpty() || rightPixels.isEmpty()) return listOf(layer)

		// Viewer-left is character's right (-r); viewer-right is character's left (-l)
		val pieceR = extractLayer(source, leftPixels, Side.RIGHT, "r", alphaThreshold, layer.semantic)
		val pieceL = extractLayer(source, rightPixels, Side.LEFT, "l", alphaThreshold, layer.semantic)
		return listOf(pieceR, pieceL)
	}

	private fun extractLayer(
		source: SourceLayer,
		pixels: List<Int>,
		side: Side,
		suffix: String,
		alphaThreshold: Int,
		originalSemantic: LayerSemantic,
	): ClassifiedLayer {
		val width = source.raster.width
		val minX = pixels.minOf { it % width }
		val maxX = pixels.maxOf { it % width }
		val minY = pixels.minOf { it / width }
		val maxY = pixels.maxOf { it / width }
		val targetW = maxX - minX + 1
		val targetH = maxY - minY + 1
		val rgba = ByteArray(targetW * targetH * 4)
		for (idx in pixels) {
			val px = idx % width
			val py = idx / width
			val targetIdx = ((py - minY) * targetW + (px - minX)) * 4
			source.raster.rgba.copyInto(rgba, targetIdx, idx * 4, idx * 4 + 4)
		}
		val virtual = VirtualLayer(
			id = LayerId("${source.id.raw}:$suffix"),
			name = "${source.name}-$suffix",
			groupPath = source.groupPath,
			kind = source.kind,
			visible = source.visible,
			order = source.order,
			bounds = LayerBounds(source.bounds.left + minX, source.bounds.top + minY, targetW, targetH),
			opacity = source.opacity,
			clipped = source.clipped,
			blend = source.blend,
			channelMask = source.channelMask,
			raster = LayerRaster(targetW, targetH, rgba),
		)
		val normName = if (originalSemantic.tag != SemanticTag.UNKNOWN) {
			"${originalSemantic.tag.canonicalName}-$suffix"
		} else {
			"${originalSemantic.normalizedName}-$suffix"
		}
		return LayerClassifier.classify(virtual, alphaThreshold).let { classified ->
			classified.copy(
				semantic = classified.semantic.copy(
					tag = originalSemantic.tag,
					side = side,
					normalizedName = normName,
				),
			)
		}
	}

	private fun alphaAt(rgba: ByteArray, pixel: Int): Int = rgba[pixel * 4 + 3].toInt() and 0xff

	private data class VirtualLayer(
		override val id: LayerId,
		override val name: String,
		override val groupPath: String,
		override val kind: SourceLayerKind,
		override val visible: Boolean,
		override val order: Int,
		override val bounds: LayerBounds,
		override val opacity: Float,
		override val clipped: Boolean,
		override val blend: LayerBlend,
		override val channelMask: ChannelMask,
		override val raster: LayerRaster,
	) : SourceLayer
}

