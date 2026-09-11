package io.github.psd2live.core

import io.github.psd2live.i18n.tr
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import javax.imageio.ImageIO

/** Deterministic multi-page shelf packer; geometry stays in source units. */
object AtlasPacker {
    private data class Item(val layer: ClassifiedLayer, val width: Int, val height: Int)

    fun pack(
        layers: List<ClassifiedLayer>,
        requestedSize: Int,
        padding: Int,
        upscale: TextureUpscaleConfig = TextureUpscaleConfig(),
        progress: ProgressListener = ProgressListener { _, _ -> },
    ): PackedAtlas =
        packWithTextures(layers, requestedSize, padding, upscale, progress) { l, c -> TextureUpscale.prepare(l, c, progress) }

    internal fun packWithTextures(layers: List<ClassifiedLayer>, requestedSize: Int, padding: Int,
        upscale: TextureUpscaleConfig,
        progress: ProgressListener = ProgressListener { _, _ -> },
        prepareTextures: (List<ClassifiedLayer>, TextureUpscaleConfig) -> Map<String, Path>,
    ): PackedAtlas {
        require(requestedSize in 256..16384) { tr("error.atlasSize") }
        require(padding in 0..32)
        val items = layers.filter { it.source.raster.width > 0 && it.source.raster.height > 0 && it.opaquePixels > 0 }
            .map { Item(it, Math.multiplyExact(it.source.raster.width, upscale.scale), Math.multiplyExact(it.source.raster.height, upscale.scale)) }
            .sortedWith(compareByDescending<Item> { it.height }.thenByDescending { it.width }.thenBy { it.layer.source.id.raw })
        val largest = items.maxOfOrNull { maxOf(it.width, it.height) + padding * 2 } ?: requestedSize
        val pageSize = maxOf(requestedSize, nextPowerOfTwo(largest)).coerceAtMost(16384)
        require(largest <= pageSize) { tr("error.layerTooLarge") }
        val placements = linkedMapOf<String, AtlasPlacement>()
        var x = padding; var y = padding; var rowHeight = 0; var pageIndex = 0
        for (item in items) {
            if (x + item.width + padding > pageSize) { x = padding; y += rowHeight + padding; rowHeight = 0 }
            if (y + item.height + padding > pageSize) { pageIndex++; x = padding; y = padding; rowHeight = 0 }
            placements[item.layer.source.id.raw] = AtlasPlacement(pageIndex, x, y, item.width, item.height, upscale.scale)
            x += item.width + padding * 2
            rowHeight = maxOf(rowHeight, item.height)
        }
        // Fail before inference or large allocations; encoded PNGs and render copies cost extra memory.
        require(upscale.scale == 1 || (pageIndex + 1L) * pageSize * pageSize * 4 <= 512L * 1024 * 1024) {
            "Upscaled atlas exceeds 512 MiB of raw pixels. Reduce texture scale or atlas size."
        }
        val textures = if (upscale.scale == 1) emptyMap() else prepareTextures(items.map { it.layer }, upscale)
        progress.update(tr("progress.atlas"), 0.96)
        val pages = (0..pageIndex).map { index ->
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val page = BufferedImage(pageSize, pageSize, BufferedImage.TYPE_INT_ARGB)
            for (item in items) {
                val placement = placements.getValue(item.layer.source.id.raw)
                if (placement.page != index) continue
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                val image = if (upscale.scale == 1) PreviewRenderer.rasterImage(item.width, item.height, item.layer.source.raster.rgba)
                    else requireNotNull(ImageIO.read(textures.getValue(item.layer.source.id.raw).toFile())) { "Invalid upscaled PNG" }
                require(image.width == item.width && image.height == item.height && image.colorModel.hasAlpha()) { "Upscaled RGBA texture size mismatch" }
                // Direct pixel copy retains RGB even when alpha is zero (Graphics may discard it).
                val pixels = image.getRGB(0, 0, item.width, item.height, null, 0, item.width)
                page.setRGB(placement.x, placement.y, item.width, item.height, pixels, 0, item.width)
                if (upscale.scale > 1 && padding > 0) {
                    for (dy in -padding until item.height + padding) for (dx in -padding until item.width + padding) {
                        if (dx in 0 until item.width && dy in 0 until item.height) continue
                        val tx = placement.x + dx; val ty = placement.y + dy
                        if (tx in 0 until pageSize && ty in 0 until pageSize) {
                            page.setRGB(tx, ty, image.getRGB(dx.coerceIn(0, item.width - 1), dy.coerceIn(0, item.height - 1)) and 0x00ffffff)
                        }
                    }
                }
            }
            val output = ByteArrayOutputStream()
            check(ImageIO.write(page, "png", output)) { tr("error.pngEncoder") }
            AtlasPage(page, output.toByteArray())
        }
        return PackedAtlas(pages, placements)
    }

    private fun nextPowerOfTwo(value: Int): Int {
        var result = 1
        while (result < value && result < 16384) result = result shl 1
        return result
    }
}
