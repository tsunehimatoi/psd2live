package io.github.psd2live.core

import java.nio.file.Path
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AdaptiveMeshTopologyTest {
    @Test fun topologyOptionsSurviveProjectStateRoundTrip() {
        val state = io.github.psd2live.ui.state.PSD2LiveState(
            meshFillAlgorithm = MeshFillAlgorithm.ADAPTIVE_QUADTREE,
            meshSuppressBoundaryDiagonals = true,
            meshOverrides = mapOf("part" to MeshSettings(fillAlgorithm = MeshFillAlgorithm.SIMPLE_TRIANGLES,
                suppressBoundaryDiagonals = true)),
        )
        val restored = io.github.psd2live.project.WorkspaceStateCodec.decode(
            io.github.psd2live.project.WorkspaceStateCodec.encode(state))
        assertEquals(MeshFillAlgorithm.ADAPTIVE_QUADTREE, restored.meshFillAlgorithm)
        assertEquals(true, restored.meshSuppressBoundaryDiagonals)
        assertEquals(state.meshOverrides, restored.meshOverrides)

        val legacy = JsonObject(io.github.psd2live.project.WorkspaceStateCodec.encode(state)
            .filterKeys { it != "meshFillAlgorithm" })
        assertEquals(MeshFillAlgorithm.GRADED_POISSON,
            io.github.psd2live.project.WorkspaceStateCodec.decode(legacy).meshFillAlgorithm)
    }

    @Test fun allFillAlgorithmsKeepValidConcaveContour() {
        val width = 120
        val height = 90
        val rgba = ByteArray(width * height * 4)
        for (y in 10 until 80) for (x in 10 until 110) {
            if (x in 40 until 70 && y in 10 until 45) continue // concave notch
            rgba[(y * width + x) * 4 + 3] = -1
        }
        fun generate(algorithm: MeshFillAlgorithm, suppress: Boolean) = assertNotNull(AdaptiveMeshGenerator.generate(
            width, height, rgba, 8, MeshSettings(maxEdgeDistance = 9f, interiorDensity = 42f,
                fillAlgorithm = algorithm, suppressBoundaryDiagonals = suppress),
        ))
        val layouts = mutableListOf<List<Float>>()
        for (algorithm in MeshFillAlgorithm.entries) {
            val normal = generate(algorithm, false)
            val suppressed = generate(algorithm, true)
            assertTrue(normal.indices.isNotEmpty(), algorithm.name)
            assertEquals(normal.boundaryLoops.map { it.size }, suppressed.boundaryLoops.map { it.size }, algorithm.name)
            assertNoBoundaryChords(suppressed)
            layouts += normal.positions.toList()
        }
        assertEquals(layouts.size, layouts.distinct().size, "fill selection must change the mesh")
    }

    @Test fun thinRibbonRemainsValidWhenChordSuppressionIsRequested() {
        val width = 110
        val height = 8
        val rgba = ByteArray(width * height * 4)
        for (y in 3..4) for (x in 5 until 105) rgba[(y * width + x) * 4 + 3] = -1
        val mesh = assertNotNull(AdaptiveMeshGenerator.generate(width, height, rgba, 8,
            MeshSettings(maxEdgeDistance = 12f, interiorDensity = 40f,
                suppressBoundaryDiagonals = true)))
        assertEquals(1, mesh.boundaryLoops.size)
        assertTrue(mesh.indices.isNotEmpty())
        assertTrue(mesh.boundaryLoops.single().size < 90, "ribbon must not fall back to pixel stairs")
    }

    @Test fun closeFootwearStaysSplitWithSparseInterior() {
        val analysis = PSD2LivePipeline().inspect(Path.of("examples/ds/psd-input/ds.psd"))
        val original = analysis.layers.single { it.source.name == "footwear" }
        val parts = ComponentSplitter.split(original)
        assertEquals(2, parts.size)
        for (part in parts) for (algorithm in MeshFillAlgorithm.entries) {
            val raster = part.source.raster
            for (suppress in listOf(false, true)) {
                val mesh = assertNotNull(AdaptiveMeshGenerator.generate(raster.width, raster.height,
                    raster.rgba, 8, MeshSettings(maxEdgeDistance = 12f, interiorDensity = 40f,
                        fillAlgorithm = algorithm, suppressBoundaryDiagonals = suppress)))
                assertEquals(1, mesh.boundaryLoops.size, part.source.name)
                assertTrue(mesh.boundaryLoops.single().size <= 40,
                    "${part.source.name} with $algorithm and suppression=$suppress fell back to a jagged raster contour")
            }
        }
    }

    @Test fun editorSplitKeepsBothFootwearCorePixels() {
        val pipeline = PSD2LivePipeline()
        val analysis = pipeline.inspect(Path.of("examples/ds/psd-input/ds.psd"))
        val original = analysis.layers.single { it.source.name == "footwear" }
        val expected = ComponentSplitter.split(original).map { it.source }
        for (algorithm in MeshFillAlgorithm.entries) {
            val preview = pipeline.buildPreview(analysis, PipelineConfig(meshOnly = true,
                generateDeformers = false, meshFillAlgorithm = algorithm))
            val drawable = preview.rig.puppet.drawables.single { it.name == "footwear" }
            val placement = preview.atlas.placementByLayerId.getValue(original.source.id.raw)
            val page = preview.atlas.pages[placement.page].image
            val plan = assertNotNull(MeshComponentSplit.detect(assertNotNull(drawable.mesh), original.source,
                placement, page.width, page.height))
            val pieces = plan.pieces(listOf("footwear-r", "footwear-l"))
            assertEquals(2, pieces.size)
            val source = original.source
            for (y in 0 until source.raster.height) for (x in 0 until source.raster.width) {
                val alpha = source.raster.rgba[(y * source.raster.width + x) * 4 + 3].toInt() and 0xff
                if (alpha < 8) continue
                val canvasX = source.bounds.left + x
                val canvasY = source.bounds.top + y
                val owner = pieces.indices.single { alphaAt(pieces[it], canvasX, canvasY) > 0 }
                val reference = expected.indices.single { alphaAt(expected[it], canvasX, canvasY) > 0 }
                assertEquals(reference, owner, "$algorithm: wrong footwear owns ($canvasX, $canvasY)")
            }
        }
    }

    private fun alphaAt(layer: org.umamo.format.art.SourceLayer, canvasX: Int, canvasY: Int): Int {
        val x = canvasX - layer.bounds.left
        val y = canvasY - layer.bounds.top
        if (x !in 0 until layer.raster.width || y !in 0 until layer.raster.height) return 0
        return layer.raster.rgba[(y * layer.raster.width + x) * 4 + 3].toInt() and 0xff
    }

    private fun assertNoBoundaryChords(mesh: AdaptiveMeshGenerator.Result) {
        val boundary = mesh.boundaryLoops.flatMapTo(hashSetOf()) { it.toList() }
        val neighbors = mesh.boundaryLoops.flatMapTo(hashSetOf()) { loop ->
            loop.indices.map { index -> edge(loop[index], loop[(index + 1) % loop.size]) }
        }
        for (index in mesh.indices.indices step 3) {
            for (side in 0..2) {
                val a = mesh.indices[index + side]
                val b = mesh.indices[index + (side + 1) % 3]
                assertTrue(a !in boundary || b !in boundary || edge(a, b) in neighbors,
                    "mesh has a nonadjacent contour chord: $a-$b")
            }
        }
    }

    private fun edge(a: Int, b: Int) = minOf(a, b) to maxOf(a, b)
}
