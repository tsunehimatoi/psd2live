package io.github.psd2live.core

import io.github.psd2live.core.fit.FaceLandmarking
import io.github.psd2live.project.WorkspaceSourceLayer
import org.umamo.format.art.*
import kotlin.test.Test
import kotlin.test.assertEquals

class FaceLandmarkingTest {
    @Test fun sparseHairColumnsKeepTheirTipsGapsAndCanvasPositions() {
        val width = 36
        val height = 12
        fun raster(secondTip: Int): LayerRaster {
            val pixels = ByteArray(width * height * 4)
            for (x in 1..34) {
                if (x in setOf(9, 17)) continue
                val bottom = when (x) { 8 -> 10; 24 -> secondTip; else -> 3 }
                for (y in 0..bottom) pixels[(y * width + x) * 4 + 3] = -1
            }
            return LayerRaster(width, height, pixels)
        }
        fun points(raster: LayerRaster, left: Int): Map<String, Pair<Float, Float>> {
            val source = WorkspaceSourceLayer(LayerId("hair"), "Hair", "", SourceLayerKind.Raster, true,
                0, LayerBounds(left, 0, width, height), 1f, false, LayerBlend.Normal, ChannelMask.ALL, raster, null, null, true)
            val layer = ClassifiedLayer(source, LayerSemantic(SemanticTag.FRONT_HAIR, normalizedName = "hair", confidence = 1f),
                Bounds(left.toFloat(), 0f, (left + width).toFloat(), height.toFloat()), left + 17f, 3f, 1)
            return FaceLandmarking.read(listOf(layer), null, emptyMap(), null, left + 17f) { x, y -> x to y }
                .points.associate { it.id.substringAfterLast('.') to (it.x to it.y) }
        }
        val original = raster(11)
        val expected = mapOf("top" to (16.5f to .5f), "outerA" to (1.5f to 3.5f), "outerB" to (34.5f to 3.5f),
            "tip0" to (8.5f to 10.5f), "tip1" to (24.5f to 11.5f), "gap0" to (10.5f to 3.5f))
        assertEquals(expected, points(original, 0))
        assertEquals(expected.mapValues { (_, point) -> (point.first + 20f) to point.second }, points(original, 20))
        assertEquals(expected - setOf("tip1", "gap0"), points(raster(3), 0))
    }
}
