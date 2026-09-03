package io.github.psd2live.core

import io.github.psd2live.i18n.tr
import java.awt.AlphaComposite
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/** Deterministic multi-page shelf packer. Cropped PSD rasters remain at 1:1 scale. */
object AtlasPacker {
	private data class Item(val layer: ClassifiedLayer, val width: Int, val height: Int)
	private data class MutablePage(
		val image: BufferedImage,
		var x: Int,
		var y: Int,
		var rowHeight: Int,
	)

	fun pack(layers: List<ClassifiedLayer>, requestedSize: Int, padding: Int): PackedAtlas {
		require(requestedSize in 256..16384) { tr("error.atlasSize") }
		val items = layers
			.filter { it.source.raster.width > 0 && it.source.raster.height > 0 && it.opaquePixels > 0 }
			.map { Item(it, it.source.raster.width, it.source.raster.height) }
			.sortedWith(compareByDescending<Item> { it.height }.thenByDescending { it.width }.thenBy { it.layer.source.id.raw })
		val largest = items.maxOfOrNull { maxOf(it.width, it.height) + padding * 2 } ?: requestedSize
		val pageSize = maxOf(requestedSize, nextPowerOfTwo(largest)).coerceAtMost(16384)
		require(largest <= pageSize) { tr("error.layerTooLarge") }
		val pages = mutableListOf<MutablePage>()
		val placements = linkedMapOf<String, AtlasPlacement>()

		fun newPage(): MutablePage = MutablePage(BufferedImage(pageSize, pageSize, BufferedImage.TYPE_INT_ARGB), padding, padding, 0).also(pages::add)
		var page = newPage()
		for (item in items) {
			val packedWidth = item.width + padding
			val packedHeight = item.height + padding
			if (page.x + packedWidth > pageSize) {
				page.x = padding
				page.y += page.rowHeight + padding
				page.rowHeight = 0
			}
			if (page.y + packedHeight > pageSize) page = newPage()
			val image = PreviewRenderer.rasterImage(item.width, item.height, item.layer.source.raster.rgba)
			page.image.createGraphics().use { graphics ->
				graphics.composite = AlphaComposite.Src
				graphics.drawImage(image, page.x, page.y, null)
			}
			placements[item.layer.source.id.raw] = AtlasPlacement(pages.lastIndex, page.x, page.y, item.width, item.height)
			page.x += packedWidth + padding
			page.rowHeight = maxOf(page.rowHeight, item.height)
		}
		val encoded = pages.map { mutable ->
			val output = ByteArrayOutputStream()
			check(ImageIO.write(mutable.image, "png", output)) { tr("error.pngEncoder") }
			AtlasPage(mutable.image, output.toByteArray())
		}
		return PackedAtlas(encoded, placements)
	}

	private fun nextPowerOfTwo(value: Int): Int {
		var result = 1
		while (result < value && result < 16384) result = result shl 1
		return result
	}

	private inline fun <T : java.awt.Graphics> T.use(block: (T) -> Unit) {
		try { block(this) } finally { dispose() }
	}
}
