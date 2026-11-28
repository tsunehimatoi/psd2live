package io.github.psd2live.application

import io.github.psd2live.core.PipelineConfig
import io.github.psd2live.core.RigEditOverlay
import io.github.psd2live.core.modifier.RigModifierStack
import io.github.psd2live.project.WorkspaceDocument
import io.github.psd2live.project.WorkspaceSettingsCodec
import io.github.psd2live.project.WorkspaceSourceArt
import io.github.psd2live.project.WorkspaceSourceLayer
import java.awt.Color
import java.awt.Polygon
import java.awt.RenderingHints
import java.awt.Shape
import java.awt.geom.Ellipse2D
import java.awt.image.BufferedImage
import org.umamo.format.art.*
import org.umamo.runtime.model.RuntimeTarget

internal fun shadowPortrait(scale: Int = 1): WorkspaceDocument {
    val skin = 0xffcfb1
    val hair = 0x3b3455
    val art = listOf(
        Triple("Left eye", 0x493b59, Ellipse2D.Double(75.0, 109.0, 25.0, 12.0)),
        Triple("Right eye", 0x493b59, Ellipse2D.Double(139.0, 109.0, 25.0, 12.0)),
        Triple("Mouth", 0xa65261, Ellipse2D.Double(108.0, 151.0, 26.0, 5.0)),
        Triple("Bangs left", hair, Polygon(intArrayOf(49, 66, 134, 100, 83, 59), intArrayOf(98, 40, 33, 87, 101, 82), 6)),
        Triple("Bangs right", hair, Polygon(intArrayOf(96, 126, 170, 199, 185, 144, 126), intArrayOf(81, 34, 36, 105, 107, 78, 99), 7)),
        Triple("Side hair", 0x514367, Polygon(intArrayOf(169, 193, 204, 198, 175, 161), intArrayOf(65, 72, 145, 185, 159, 108), 6)),
        Triple("Face", skin, Ellipse2D.Double(51.0, 51.0, 138.0, 129.0)),
        Triple("Neck", 0xf0bc9b, Ellipse2D.Double(94.0, 152.0, 52.0, 69.0)),
        Triple("Body", 0x7190ac, Ellipse2D.Double(43.0, 202.0, 154.0, 78.0)),
    )
    val layers = art.mapIndexed { order, (name, rgb, shape) -> portraitLayer(name, rgb, shape, order, scale) }
    val edits = RigEditOverlay(modifierStack = RigModifierStack().toJson())
    val settings = WorkspaceSettingsCodec.encode(PipelineConfig(atlasSize = 512, meshSpacing = 12 * scale,
        runtimeTarget = RuntimeTarget.Cubism53, rigEdits = edits))
    return WorkspaceDocument(WorkspaceSourceArt(240 * scale, 280 * scale, layers, emptyList()),
        emptyMap(), emptySet(), emptyMap(), emptyMap(), edits, settings)
}

private fun portraitLayer(name: String, rgb: Int, shape: Shape, order: Int, scale: Int): WorkspaceSourceLayer {
    val b = shape.bounds
    val image = BufferedImage(b.width * scale, b.height * scale, BufferedImage.TYPE_INT_ARGB)
    val graphics = image.createGraphics()
    try {
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        graphics.scale(scale.toDouble(), scale.toDouble())
        graphics.translate(-b.x, -b.y)
        graphics.color = Color(rgb)
        graphics.fill(shape)
    } finally {
        graphics.dispose()
    }
    val pixels = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
    val rgba = ByteArray(pixels.size * 4)
    pixels.forEachIndexed { i, argb ->
        rgba[4 * i] = (argb ushr 16).toByte()
        rgba[4 * i + 1] = (argb ushr 8).toByte()
        rgba[4 * i + 2] = argb.toByte()
        rgba[4 * i + 3] = (argb ushr 24).toByte()
    }
    return WorkspaceSourceLayer(LayerId("portrait-$order"), name, "", SourceLayerKind.Raster, true, order,
        LayerBounds(b.x * scale, b.y * scale, image.width, image.height), 1f, false, LayerBlend.Normal,
        ChannelMask.ALL, LayerRaster(image.width, image.height, rgba), null, null, false)
}
