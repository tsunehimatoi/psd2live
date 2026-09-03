package io.github.psd2live.core

import org.umamo.format.art.SourceArt
import org.umamo.format.art.isEffectivelyVisible
import java.awt.AlphaComposite
import java.awt.RenderingHints
import java.awt.image.BufferedImage

object PreviewRenderer {
	fun composite(source: SourceArt): BufferedImage {
		val canvas = BufferedImage(source.widthPx, source.heightPx, BufferedImage.TYPE_INT_ARGB)
		val graphics = canvas.createGraphics()
		graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
		try {
			for (layer in source.layers) {
				if (!source.isEffectivelyVisible(layer) || layer.opacity <= 0f) continue
				graphics.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, layer.opacity.coerceIn(0f, 1f))
				graphics.drawImage(rasterImage(layer.raster.width, layer.raster.height, layer.raster.rgba), layer.bounds.left, layer.bounds.top, null)
			}
		} finally {
			graphics.dispose()
		}
		return canvas
	}

	fun rasterImage(width: Int, height: Int, rgba: ByteArray): BufferedImage {
		val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
		val argb = IntArray(width * height)
		for (index in argb.indices) {
			val offset = index * 4
			val red = rgba[offset].toInt() and 0xff
			val green = rgba[offset + 1].toInt() and 0xff
			val blue = rgba[offset + 2].toInt() and 0xff
			val alpha = rgba[offset + 3].toInt() and 0xff
			argb[index] = (alpha shl 24) or (red shl 16) or (green shl 8) or blue
		}
		image.setRGB(0, 0, width, height, argb, 0, width)
		return image
	}
}

