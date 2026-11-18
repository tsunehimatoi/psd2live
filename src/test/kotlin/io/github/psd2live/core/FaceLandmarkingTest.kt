package io.github.psd2live.core

import io.github.psd2live.algorithms.binding.DepthTurnInputs
import io.github.psd2live.algorithms.binding.DepthTurnMotion
import io.github.psd2live.core.fit.BrowFit
import io.github.psd2live.core.fit.EyeFit
import io.github.psd2live.core.fit.EyeFitting
import io.github.psd2live.core.fit.TurnStyle
import io.github.psd2live.core.fit.FaceLandmarking
import io.github.psd2live.project.WorkspaceSourceLayer
import org.umamo.format.art.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FaceLandmarkingTest {
    @Test fun interiorLidAndBrowLandmarksKeepTheirFittedCoordinates() {
        val eye = EyeFit(Side.LEFT, 100f, 20f, 1f, 0f, 40f,
            List(17) { -minOf(it, 16 - it) / 40f }, List(17) { minOf(it, 16 - it) / 40f }, null, null)
        val eyes = EyeFitting.Result(mapOf(Side.LEFT to eye), emptyMap())
        val brow = BrowFit(Side.LEFT, 92f, 10f, 1f, 0f, 32f, List(17) { -minOf(it, 16 - it) / 32f })
        val marks = FaceLandmarking.read(emptyList(), eyes, mapOf(Side.LEFT to brow), null, 50f) { _, _ ->
            error("Landmarks from the fits are already aligned")
        }
        fun position(id: String) = marks[id]?.let { it.x to it.y }
        assertEquals(110f to 24f, position("eye.L.lower25"))
        assertEquals(120f to 28f, position("eye.L.lower50"))
        assertEquals(130f to 24f, position("eye.L.lower75"))
        assertEquals(120f to 12f, position("eye.L.upper50"))
        assertEquals(100f to 6f, position("brow.L.center25"))
        assertEquals(108f to 2f, position("brow.L.center50"))
        assertEquals(116f to 6f, position("brow.L.center75"))
        assertEquals(null, position("eye.R.lower50"))
        assertEquals(null, position("brow.R.center50"))
    }

    private fun lidStroke(xs: IntRange, baseline: Int, peak: Int): List<Pair<Int, Int>> =
        xs.map { x -> x to baseline - (3 - kotlin.math.abs(x - peak)).coerceAtLeast(0) }

    private fun lid(id: String, side: Side, ink: List<Pair<Int, Int>>, group: String = "", order: Int = 0): ClassifiedLayer {
        val left = ink.minOf { it.first }
        val top = ink.minOf { it.second }
        val width = ink.maxOf { it.first } - left + 1
        val height = ink.maxOf { it.second } - top + 1
        val rgba = ByteArray(width * height * 4)
        for ((x, y) in ink) rgba[((y - top) * width + x - left) * 4 + 3] = -1
        val source = WorkspaceSourceLayer(LayerId(id), id, group, SourceLayerKind.Raster, true, order,
            LayerBounds(left, top, width, height), 1f, false, LayerBlend.Normal, ChannelMask.ALL,
            LayerRaster(width, height, rgba), null, null, true)
        val bounds = Bounds(left.toFloat(), top.toFloat(), (left + width).toFloat(), (top + height).toFloat())
        return ClassifiedLayer(source, LayerSemantic(SemanticTag.EYELASH, side, normalizedName = id, confidence = 1f, role = PartRole.LID),
            bounds, ink.map { it.first + .5f }.average().toFloat(), ink.map { it.second + .5f }.average().toFloat(), ink.size)
    }

    private fun lidLandmarks(layers: List<ClassifiedLayer>): Map<String, Pair<Float, Float>> {
        fun eye(side: Side, inner: Float, outward: Float) = EyeFit(side, inner, 39f, outward, 0f, 32f,
            List(17) { -minOf(it, 16 - it) / 32f }, List(17) { minOf(it, 16 - it) / 32f }, null, null)
        val eyes = EyeFitting.Result(mapOf(Side.RIGHT to eye(Side.RIGHT, 45f, -1f), Side.LEFT to eye(Side.LEFT, 55f, 1f)), emptyMap())
        val marks = FaceLandmarking.read(layers, eyes, emptyMap(), null, 50f) { x, y -> x to y }
        return listOf("L", "R").flatMap { side -> listOf("lidInner", "lidOuter", "lidTop").map { "eye.$side.$it" } }
            .associateWith { id -> assertNotNull(marks[id], id).let { it.x to it.y } }
    }

    @Test fun aPairedLidLayerUsesOnlyTheInkOnEachEyesSide() {
        val westernLid = lidStroke(18..42, 31, 26)
        val easternLid = lidStroke(58..82, 25, 70)
        val both = lid("double eyelid", Side.NONE, westernLid + easternLid)
        assertEquals(mapOf(
            "eye.R.lidInner" to (42.5f to 31.5f), "eye.R.lidOuter" to (18.5f to 31.5f), "eye.R.lidTop" to (26.5f to 28.5f),
            "eye.L.lidInner" to (58.5f to 25.5f), "eye.L.lidOuter" to (82.5f to 25.5f), "eye.L.lidTop" to (70.5f to 22.5f),
        ), lidLandmarks(listOf(both)))
    }

    @Test fun aThroughHairLidOverlayCannotReplaceThePrimaryLids() {
        val right = lid("double eyelid-r", Side.RIGHT, lidStroke(23..39, 32, 29), order = 2)
        val left = lid("double eyelid-l", Side.LEFT, lidStroke(63..79, 30, 68), order = 1)
        // The overlay has more ink on both sides and lies above the primary eye artwork.
        val cover = lid("double eyelid overlay", Side.NONE, lidStroke(16..44, 25, 26) + lidStroke(56..85, 23, 73),
            group = "body/眼眉透发")
        val expected = mapOf(
            "eye.R.lidInner" to (39.5f to 32.5f), "eye.R.lidOuter" to (23.5f to 32.5f), "eye.R.lidTop" to (29.5f to 29.5f),
            "eye.L.lidInner" to (63.5f to 30.5f), "eye.L.lidOuter" to (79.5f to 30.5f), "eye.L.lidTop" to (68.5f to 27.5f),
        )
        assertEquals(expected, lidLandmarks(listOf(right, left)))
        assertEquals(expected, lidLandmarks(listOf(cover, right, left)))
    }

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

    @Test fun foreheadCoverageUsesOpaqueReachOutsideEachVisibleSkinEdge() {
        fun part(id: String, tag: SemanticTag, left: Int, top: Int, width: Int, height: Int, alpha: Int = 255): ClassifiedLayer {
            val base = ear(id, Side.NONE, null, left, top, width, height, alpha)
            return base.copy(semantic = base.semantic.copy(tag = tag))
        }
        val face = part("face", SemanticTag.FACE, 20, 0, 100, 115)
        val eyes = listOf(part("eye1", SemanticTag.EYEWHITE, 38, 54, 12, 8), part("eye2", SemanticTag.EYEWHITE, 90, 54, 12, 8))
        val hair = part("hair", SemanticTag.FRONT_HAIR, 12, 0, 121, 54)
        val emptyOutline = part("transparent hair", SemanticTag.FRONT_HAIR, 0, 0, 160, 54, alpha = 0)
        fun measure(cover: List<ClassifiedLayer>) =
            FaceLandmarking.read(listOf(face) + eyes, null, emptyMap(), null, 70f, cover) { x, y -> x to y }.measures
        val measured = measure(listOf(hair, emptyOutline))
        assertTrue(requireNotNull(measured["face.upperCover.R"]) in .07f.. .10f)
        assertTrue(requireNotNull(measured["face.upperCover.L"]) in .12f.. .15f)
        assertEquals(null, measure(listOf(emptyOutline))["face.upperCover.R"])
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

    @Test fun sideEarAttachmentUsesTheObservedFaceEdgeOnBothSides() {
        val face = ear("skin", Side.NONE, null, 40, 10, 20, 40).let { layer ->
            layer.copy(semantic = layer.semantic.copy(tag = SemanticTag.FACE))
        }
        val left = ear("left", Side.LEFT, null, 57, 20, 8, 7)
        val right = ear("right", Side.RIGHT, null, 35, 20, 8, 7)
        fun read(vararg ears: ClassifiedLayer) = FaceLandmarking.read(listOf(face) + ears, null, emptyMap(), null, 50f) { x, y ->
            x - 50f to y + 10f
        }
        fun position(id: String) = read(left, right)[id]?.let { it.x to it.y }
        assertEquals(10f to 33.5f, position("ear.L.attachment"))
        assertEquals(-10f to 33.5f, position("ear.R.attachment"))
        assertEquals(7.5f to 30.5f, position("ear.L.root"))
        assertEquals(null, read(ear("detached", Side.LEFT, null, 60, 20, 6, 7))["ear.L.attachment"])
        assertEquals(null, read(ear("hidden", Side.LEFT, null, 53, 20, 6, 7))["ear.L.attachment"])
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
