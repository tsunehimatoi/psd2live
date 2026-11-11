package io.github.psd2live.core

import io.github.psd2live.algorithms.binding.DepthTurnInputs
import io.github.psd2live.algorithms.binding.DepthTurnMotion
import io.github.psd2live.core.fit.TurnStyle
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

    private fun ear(id: String, side: Side, role: PartRole?, left: Int, top: Int, width: Int, height: Int,
                    alpha: Int = 255): ClassifiedLayer {
        val rgba = ByteArray(width * height * 4)
        for (i in 3 until rgba.size step 4) rgba[i] = alpha.toByte()
        val source = WorkspaceSourceLayer(LayerId(id), id, "", SourceLayerKind.Raster, true, 0,
            LayerBounds(left, top, width, height), 1f, false, LayerBlend.Normal, ChannelMask.ALL,
            LayerRaster(width, height, rgba), null, null, true)
        val bounds = Bounds(left.toFloat(), top.toFloat(), (left + width).toFloat(), (top + height).toFloat())
        return ClassifiedLayer(source, LayerSemantic(SemanticTag.EARS, side, normalizedName = id, confidence = 1f, role = role),
            bounds, bounds.centerX, bounds.centerY, if (alpha > 0) width * height else 0)
    }

    @Test fun layeredAnimalEarUsesUnionGeometryAndProducesValidDepthTurnInputs() {
        val layers = listOf(
            ear("ear cat ear front_L", Side.LEFT, PartRole.ANIMAL, 60, 10, 4, 3),
            ear("ear cat ear fur_L", Side.LEFT, PartRole.ANIMAL, 62, 11, 4, 2),
            ear("ear cat ear back_L", Side.LEFT, PartRole.ANIMAL, 59, 8, 2, 5))
        fun read(parts: List<ClassifiedLayer>) = FaceLandmarking.read(parts, null, emptyMap(), null, 50f) { x, y ->
            (x * 2f - 100f) to (y * 3f + 4f)
        }
        val marks = read(layers)
        assertEquals(4, marks.points.size)
        assertEquals(4, marks.points.map { it.id }.toSet().size)
        // The bottom three rows contain 5, 7, 7 distinct pixels. Overlap must not weight the base twice.
        val baseX = ((59..63).sum() + 2 * (59..65).sum()) / 19f + .5f
        val expected = mapOf(
            "ear.L.animal.base" to ((baseX * 2f - 100f) to 41.5f),
            "ear.L.animal.tip" to (19f to 29.5f),
            "ear.L.animal.inner" to (19f to 29.5f),
            "ear.L.animal.outer" to (31f to 38.5f))
        assertEquals(expected, marks.points.associate { it.id to (it.x to it.y) })
        assertEquals(marks.points, read(layers.reversed()).points)
        assertEquals(marks.points, read(layers + layers[0]).points)
        val frame = Bounds(-100f, -100f, 100f, 100f)
        val inputs = DepthTurnInputs(
            head = frame, face = frame, container = DepthTurnMotion.Container(0f, 0f, 1f, 1f, 1f, 1f),
            centerY = 0f, radiusX = 100f, radiusY = 100f, chinY = 100f,
            shellTurn = 1f, shellDepth = 1f, style = TurnStyle.Setting(), landmarks = marks,
            fittedEyes = emptySet(), fittedBrows = emptySet(), frames = emptyMap(), layers = emptyMap(),
            leanDown = 0f, leanUp = 0f)
        assertEquals(inputs.encode().values, DepthTurnInputs.decode(inputs.encode()).encode().values)
    }

    @Test fun layeredEarsKeepSidesAndKindsSeparateAndResolveUnspecifiedSides() {
        val layers = listOf(
            ear("regular left upper", Side.LEFT, null, 60, 20, 2, 2),
            ear("regular left lower", Side.NONE, null, 61, 22, 3, 2),
            ear("regular right upper", Side.NONE, null, 36, 20, 2, 2),
            ear("regular right lower", Side.RIGHT, null, 37, 22, 3, 2),
            ear("animal left", Side.LEFT, PartRole.ANIMAL, 60, 5, 2, 2),
            ear("animal right", Side.RIGHT, PartRole.ANIMAL, 38, 5, 2, 2))
        val marks = FaceLandmarking.read(layers, null, emptyMap(), null, 50f) { x, y -> x to y }
        assertEquals(16, marks.points.size)
        assertEquals(16, marks.points.map { it.id }.toSet().size)
        assertEquals(setOf("ear.L", "ear.R", "ear.L.animal", "ear.R.animal"), marks.points.map { it.part }.toSet())
        val regular = marks.points.filter { !it.part.endsWith("animal") }.associate { it.id to (it.x to it.y) }
        assertEquals(mapOf(
            "ear.L.root" to (60.5f to 20.5f), "ear.L.outer" to (63.5f to 22.5f),
            "ear.L.top" to (60.5f to 20.5f), "ear.L.lobe" to (61.5f to 23.5f),
            "ear.R.root" to (39.5f to 22.5f), "ear.R.outer" to (36.5f to 20.5f),
            "ear.R.top" to (36.5f to 20.5f), "ear.R.lobe" to (37.5f to 23.5f)), regular)
    }

    @Test fun emptyAndBelowThresholdEarLayersDoNotChangeSingleEarLandmarks() {
        // Explicit sides override position, even if the drawing lies across the face centre.
        val single = ear("ear", Side.RIGHT, null, 60, 20, 2, 2)
        fun read(layers: List<ClassifiedLayer>) = FaceLandmarking.read(layers, null, emptyMap(), null, 50f) { x, y -> x to y }
        val marks = read(listOf(single))
        assertEquals(mapOf(
            "ear.R.root" to (60.5f to 20.5f), "ear.R.outer" to (61.5f to 20.5f),
            "ear.R.top" to (60.5f to 20.5f), "ear.R.lobe" to (60.5f to 21.5f)),
            marks.points.associate { it.id to (it.x to it.y) })
        assertEquals(marks.points, read(listOf(single,
            ear("transparent", Side.RIGHT, null, 1, 1, 3, 3, alpha = 0),
            ear("faint", Side.RIGHT, null, 1, 1, 3, 3, alpha = 95),
            ear("faint animal", Side.LEFT, PartRole.ANIMAL, 1, 1, 3, 3, alpha = 95))).points)
        assertEquals(emptyList(), read(listOf(ear("faint", Side.LEFT, null, 1, 1, 3, 3, alpha = 95))).points)
    }

    @Test fun earPositionHeuristicStillGroupsUntaggedAnimalLayers() {
        val eyes = listOf(40, 60).map { left ->
            ear("eye $left", Side.NONE, null, left, 50, 2, 2).let {
                it.copy(semantic = it.semantic.copy(tag = SemanticTag.EYEWHITE))
            }
        }
        val ears = listOf(
            ear("upper ear", Side.NONE, null, 60, 10, 2, 2),
            ear("lower ear", Side.NONE, null, 61, 12, 2, 2))
        val marks = FaceLandmarking.read(eyes + ears, null, emptyMap(), null, 50f) { x, y -> x to y }
        assertEquals(setOf("ear.L.animal.base", "ear.L.animal.tip", "ear.L.animal.inner", "ear.L.animal.outer"),
            marks.points.map { it.id }.toSet())
    }

}
