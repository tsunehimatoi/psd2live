package io.github.autolive2d.core

import org.umamo.format.art.ChannelMask
import org.umamo.format.art.LayerBlend
import org.umamo.format.art.LayerBounds
import org.umamo.format.art.LayerId
import org.umamo.format.art.LayerRaster
import org.umamo.format.art.SourceLayer
import org.umamo.format.art.SourceLayerKind
import java.util.ArrayDeque

/** Anime2.5DRig/Stretchy-style 8-connected split for merged bilateral layers. */
object ComponentSplitter {
	private val splitTags = setOf(
		SemanticTag.IRIDES,
		SemanticTag.EYEBROW,
		SemanticTag.EYEWHITE,
		SemanticTag.EYELASH,
		SemanticTag.EYE_CLOSE,
	)

	private data class Component(
		val pixels: IntArray,
		val minX: Int,
		val minY: Int,
		val maxX: Int,
		val maxY: Int,
		val centroidX: Float,
	)

	fun split(layer: ClassifiedLayer, faceCenterX: Float, alphaThreshold: Int): List<ClassifiedLayer> {
		if (layer.semantic.side != Side.NONE || layer.semantic.tag !in splitTags) return listOf(layer)
		val source = layer.source
		val width = source.raster.width
		val height = source.raster.height
		if (width <= 1 || height <= 1) return listOf(layer)
		val visited = BooleanArray(width * height)
		val components = mutableListOf<Component>()
		val rgba = source.raster.rgba
		for (seed in visited.indices) {
			if (visited[seed] || alphaAt(rgba, seed) < alphaThreshold) continue
			val queue = ArrayDeque<Int>()
			val pixels = mutableListOf<Int>()
			queue.add(seed)
			visited[seed] = true
			var minX = width
			var minY = height
			var maxX = 0
			var maxY = 0
			var sumX = 0L
			while (queue.isNotEmpty()) {
				val index = queue.removeFirst()
				pixels += index
				val x = index % width
				val y = index / width
				minX = minOf(minX, x)
				minY = minOf(minY, y)
				maxX = maxOf(maxX, x)
				maxY = maxOf(maxY, y)
				sumX += x
				for (dy in -1..1) for (dx in -1..1) {
					if (dx == 0 && dy == 0) continue
					val nx = x + dx
					val ny = y + dy
					if (nx !in 0 until width || ny !in 0 until height) continue
					val neighbor = ny * width + nx
					if (!visited[neighbor] && alphaAt(rgba, neighbor) >= alphaThreshold) {
						visited[neighbor] = true
						queue.add(neighbor)
					}
				}
			}
			if (pixels.size >= 12) components += Component(pixels.toIntArray(), minX, minY, maxX, maxY, sumX.toFloat() / pixels.size)
		}
		val largest = components.sortedByDescending { it.pixels.size }.take(2)
		if (largest.size < 2) return listOf(layer)
		val firstCanvasX = source.bounds.left + largest[0].centroidX
		val secondCanvasX = source.bounds.left + largest[1].centroidX
		if ((firstCanvasX < faceCenterX) == (secondCanvasX < faceCenterX)) return listOf(layer)
		return largest.sortedBy { it.centroidX }.map { component ->
			// Viewer-left is the character's right; viewer-right is the character's left.
			val side = if (source.bounds.left + component.centroidX < faceCenterX) Side.RIGHT else Side.LEFT
			val virtual = extract(source, component, side)
			LayerClassifier.classify(virtual, alphaThreshold).copy(
				semantic = layer.semantic.copy(side = side, normalizedName = "${layer.semantic.tag.canonicalName}-${if (side == Side.LEFT) "l" else "r"}"),
			)
		}
	}

	private fun extract(source: SourceLayer, component: Component, side: Side): SourceLayer {
		val width = component.maxX - component.minX + 1
		val height = component.maxY - component.minY + 1
		val rgba = ByteArray(width * height * 4)
		for (sourceIndex in component.pixels) {
			val sourceX = sourceIndex % source.raster.width
			val sourceY = sourceIndex / source.raster.width
			val targetIndex = ((sourceY - component.minY) * width + sourceX - component.minX) * 4
			source.raster.rgba.copyInto(rgba, targetIndex, sourceIndex * 4, sourceIndex * 4 + 4)
		}
		val suffix = if (side == Side.LEFT) "l" else "r"
		return VirtualLayer(
			id = LayerId("${source.id.raw}:$suffix"),
			name = "${source.name}-$suffix",
			groupPath = source.groupPath,
			kind = source.kind,
			visible = source.visible,
			order = source.order,
			bounds = LayerBounds(source.bounds.left + component.minX, source.bounds.top + component.minY, width, height),
			opacity = source.opacity,
			clipped = source.clipped,
			blend = source.blend,
			channelMask = source.channelMask,
			raster = LayerRaster(width, height, rgba),
		)
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

