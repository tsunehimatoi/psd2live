package io.github.psd2live.core

import java.awt.geom.Area
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MeshAlphaCoverageTest {
    @Test
    fun faintBorderAroundOpaqueArtStaysInsideTheMesh() {
        val pixels = raster { x, y -> when {
            x in 8..23 && y in 8..23 -> 255
            x in 7..24 && y in 7..24 -> 1
            else -> 0
        } }
        assertCovered(pixels)
        // Off, the mesh follows the alpha cutoff as before and leaves the faint border out.
        val cutoff = assertNotNull(AdaptiveMeshGenerator.generate(32, 32, pixels, 8, MeshSettings(outerMargin = 0f, maxEdgeDistance = 6f)))
        assertTrue(RasterMeshCoverage.uncovered(32, 32, pixels,
            listOf(org.umamo.runtime.model.DrawableMesh(cutoff.positions, cutoff.positions, cutoff.indices)))!! > 0f)
    }

    @Test
    fun faintOnlyArtStillGetsAnAdaptiveMesh() {
        assertCovered(raster { x, y -> if (x in 8..23 && y in 8..23) 4 else 0 })
    }

    @Test
    fun isolatedSinglePixelAndSmallTransparentHoleArePreserved() {
        val pixels = raster { x, y -> when {
            x == 3 && y == 3 -> 255
            x == 16 && y == 16 -> 0
            x in 10..23 && y in 10..23 -> 255
            else -> 0
        } }
        val area = assertCovered(pixels)
        assertFalse(area.contains(16.5, 16.5), "The source hole must remain a hole")
    }

    @Test
    fun smoothingCannotCutTheCornersOfOpaquePixels() {
        assertCovered(raster { x, y -> if (x in 5..26 && y in 5..26 && x + y < 39) 255 else 0 }, margin = 0f)
    }

    @Test
    fun exactContoursStillRespectTheRequestedEdgeSpacing() {
        val pixels = raster { x, y -> if (x in 5..26 && y in 5..26 && x + y < 39) 255 else 0 }
        val mesh = assertNotNull(AdaptiveMeshGenerator.generate(32, 32, pixels, 8,
            MeshSettings(outerMargin = 0f, maxEdgeDistance = 6f), coverAllAlpha = true))
        for (loop in mesh.boundaryLoops) for (i in loop.indices) {
            val a = loop[i] * 2; val b = loop[(i + 1) % loop.size] * 2
            assertTrue(kotlin.math.hypot(mesh.positions[a] - mesh.positions[b],
                mesh.positions[a + 1] - mesh.positions[b + 1]) <= 6.001f, "Exact contour has an overlong edge")
        }
    }

    @Test
    fun safeRecoveryFollowsDiagonalArtInsteadOfEveryPixelStair() {
        val pixels = raster { x, y -> if (x in 5..26 && y in 5..26 && x + y < 39) 255 else 0 }
        val mesh = assertNotNull(AdaptiveMeshGenerator.generate(32, 32, pixels, 8,
            MeshSettings(outerMargin = 0f, maxEdgeDistance = 6f), coverAllAlpha = true))
        assertCovered(pixels, margin = 0f, candidate = mesh)
        val borders = mesh.boundaryLoops
        assertTrue(borders.sumOf { it.size } <= 24, "Recovery retains a vertex at every pixel stair")
        assertTrue(borders.any { loop -> loop.indices.any { i ->
            val a = loop[i] * 2; val b = loop[(i + 1) % loop.size] * 2
            kotlin.math.abs(mesh.positions[a] - mesh.positions[b]) > 0.1f &&
                kotlin.math.abs(mesh.positions[a + 1] - mesh.positions[b + 1]) > 0.1f
        } }, "The diagonal outline must have diagonal edges")
    }

    @Test
    fun conservativeRecoveryKeepsTheDeepNotchOfTheDrawing() {
        val pixels = raster { x, y -> if (x in 5..26 && y in 5..26 && !(x in 14..18 && y < 21)) 255 else 0 }
        val area = assertCovered(pixels, margin = 0f)
        assertFalse(area.contains(16.5, 12.5), "Simplifying pixel stairs must not fill a deep notch")
    }

    @Test
    fun recoveryKeepsCloseIslandsSeparateWhileSimplifyingStairs() {
        val pixels = raster { x, y -> if (x in 5..26 && y in 5..26 && (x - y in 0..2 || x - y in 4..6)) 255 else 0 }
        val mesh = assertNotNull(AdaptiveMeshGenerator.generate(32, 32, pixels, 8,
            MeshSettings(outerMargin = 0f, maxEdgeDistance = 6f), coverAllAlpha = true))
        assertCovered(pixels, margin = 0f, candidate = mesh)
        assertEquals(2, mesh.boundaryLoops.size, "Separate diagonal strips must keep independent contours")
        assertTrue(mesh.boundaryLoops.sumOf { it.size } < 80, "Nearby islands retain every pixel stair")
    }

    @Test
    fun reducedTraceStillCoversEntireSourcePixels() {
        assertCovered(raster { x, y -> if (x in 7..24 && y in 7..24 && x + y < 38) 1 else 0 }, unitScale = 4f)
    }

    @Test
    fun transparentHolesMeetingAtOneCornerKeepTheirCentresEmpty() {
        val area = assertCovered(raster { x, y -> when {
            (x == 15 && y == 15) || (x == 16 && y == 16) -> 0
            x in 5..26 && y in 5..26 -> 255
            else -> 0
        } }, margin = 0f)
        assertFalse(area.contains(15.5, 15.5))
        assertFalse(area.contains(16.5, 16.5))
    }

    @Test
    fun faintOnlyLayerGetsAMeshAndFootprintInThePipeline() {
        val pixels = raster { x, y -> when {
            x in 8..23 && y in 8..23 -> 255
            x == 2 && y == 2 -> 1
            else -> 0
        } }
        val faintOnly = raster { x, y -> if (x in 8..23 && y in 8..23) 1 else 0 }
        val mainLayer = io.github.psd2live.document.WorkspaceSourceLayer(
            id = org.umamo.format.art.LayerId("face"), name = "face", groupPath = "",
            kind = org.umamo.format.art.SourceLayerKind.Raster, visible = true,
            order = 1, bounds = org.umamo.format.art.LayerBounds(0, 0, 32, 32), opacity = 1f,
            clipped = false, blend = org.umamo.format.art.LayerBlend.Normal,
            channelMask = org.umamo.format.art.ChannelMask.ALL,
            raster = org.umamo.format.art.LayerRaster(32, 32, pixels),
            sourceAssetId = null, sourceSpatialReferenceId = null, derived = false,
        )
        val softLayer = mainLayer.copy(
            id = org.umamo.format.art.LayerId("glow"), name = "glow", order = 0,
            raster = org.umamo.format.art.LayerRaster(32, 32, faintOnly),
        )
        val art = io.github.psd2live.document.WorkspaceSourceArt(32, 32, listOf(mainLayer, softLayer), emptyList())
        val off = PipelineConfig(meshOuterMargin = 0f, meshOnly = true, alphaThreshold = 8, atlasSize = 128, generatePhysics = false, exportMoc3 = false)
        val on = off.copy(meshAlphaCoverage = true)
        val preview = PSD2LivePipeline().buildPreview(art, on)
        assertTrue(preview.rig.puppet.drawables.any { it.mesh != null && preview.rig.layerIdByDrawableId[it.id.raw] == "glow" },
            "Full coverage skipped a layer with alpha = 1")

        val singleArt = io.github.psd2live.document.WorkspaceSourceArt(32, 32, listOf(mainLayer), emptyList())
        val analysis = CharacterAnalyzer.analyze(singleArt, off)
        val cache = PreviewMeshCache()
        val legacy = RigSourceMeasurements.meshFramedAnalysis(analysis, off, cache).layers.single().bounds
        val warm = RigSourceMeasurements.meshFramedAnalysis(analysis, on, cache).layers.single().bounds
        val cold = RigSourceMeasurements.meshFramedAnalysis(analysis, on, null).layers.single().bounds
        kotlin.test.assertNotEquals(legacy, cold)
        assertEquals(cold, warm)
    }

    private fun raster(alpha: (Int, Int) -> Int) = ByteArray(32 * 32 * 4).also { rgba ->
        for (y in 0 until 32) for (x in 0 until 32) rgba[(y * 32 + x) * 4 + 3] = alpha(x, y).toByte()
    }

    private fun assertCovered(pixels: ByteArray, margin: Float = 1f, unitScale: Float = 1f,
                              candidate: AdaptiveMeshGenerator.Result? = null): Area {
        val mesh = candidate ?: assertNotNull(AdaptiveMeshGenerator.generate(32, 32, pixels, 8,
            MeshSettings(outerMargin = margin, maxEdgeDistance = 6f, interiorDensity = 18f), unitScale, coverAllAlpha = true))
        val area = Area()
        for (t in mesh.indices.indices step 3) {
            val triangle = Path2D.Double()
            for (k in 0..2) {
                val v = mesh.indices[t + k] * 2
                if (k == 0) triangle.moveTo(mesh.positions[v].toDouble(), mesh.positions[v + 1].toDouble())
                else triangle.lineTo(mesh.positions[v].toDouble(), mesh.positions[v + 1].toDouble())
            }
            triangle.closePath()
            area.add(Area(triangle))
        }
        val painted = Area()
        for (y in 0 until 32) for (x in 0 until 32) if ((pixels[(y * 32 + x) * 4 + 3].toInt() and 255) > 0)
            painted.add(Area(Rectangle2D.Double(x.toDouble(), y.toDouble(), 1.0, 1.0)))
        painted.subtract(area)
        val remaining = painted.bounds2D
        assertTrue(painted.isEmpty || remaining.width * remaining.height < 1e-9,
            "Mesh cuts alpha>0 pixel squares: $remaining")
        return area
    }
}
