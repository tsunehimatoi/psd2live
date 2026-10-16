package io.github.psd2live.core.interior

import io.github.psd2live.core.mesh.*
import io.github.psd2live.core.AdaptiveMeshGenerator
import io.github.psd2live.core.MeshSettings
import kotlin.math.abs
import kotlin.test.*

/** Small synthetic contracts. No sample downloads, camera, GPU or authored character coordinates. */
class InteriorSurfaceTest {
    private fun grid(cells: Int = 12, step: Float = 8f, hole: Boolean = false): InteriorMesh {
        val points = ArrayList<Float>()
        val indices = ArrayList<Int>()
        val ids = HashMap<Pair<Int, Int>, Int>()
        fun id(x: Int, y: Int) = ids.getOrPut(x to y) {
            val i = points.size / 2; points += x * step; points += y * step; i
        }
        for (y in 0 until cells) for (x in 0 until cells) {
            if (hole && x in 2..3 && y in 2..3) continue
            val a = id(x, y); val b = id(x + 1, y); val c = id(x + 1, y + 1); val d = id(x, y + 1)
            indices += listOf(a, b, c, a, c, d)
        }
        return InteriorMesh(points.toFloatArray(), indices.toIntArray())
    }
    private fun raster(size: Int = 96, hole: Boolean = false) = InteriorRaster(size, size, ByteArray(size * size * 4) { i ->
        if (i % 4 == 3 && !(hole && i / 4 % size in 32..47 && i / 4 / size in 32..47)) 255.toByte() else 0
    })
    private fun guide(id: String, vararg p: Float) = InteriorGuide(id, p.asList().chunked(2).map { InteriorPoint(it[0], it[1]) })
    private fun area(mesh: InteriorMesh) = mesh.triangles.sumOf { abs(cross(mesh.points[it.a], mesh.points[it.b], mesh.points[it.c])) / 2 }

    @Test fun upperMaterialHasNoHiddenPinOrRootFadeAndContourStillOwnsTheOutline() {
        val mesh = grid(); val pixels = raster()
        val free = InteriorSurface.compile(mesh, pixels, InteriorSurfaceSettings(contour = 0f)).evaluate(.8f, 0f).rawDisplacements()
        val top = mesh.points.indexOfFirst { it.x == 48.0 && it.y == 0.0 }
        val upper = mesh.points.indexOfFirst { it.x == 48.0 && it.y == 8.0 }
        val middle = mesh.points.indexOfFirst { it.x == 48.0 && it.y == 48.0 }
        assertTrue(abs(free[top * 2]) > .1, "top interior texture must respond when contour is released")
        assertEquals(free[middle * 2], free[top * 2], 1e-10, "no fixed root strip")
        assertEquals(free[middle * 2], free[upper * 2], 1e-10, "no residual 18-percent ramp")
        val retained = InteriorSurface.compile(mesh, pixels).evaluate(.8f, .7f).positions()
        val source = mesh.positions()
        for (edge in mesh.boundaryEdges) for (id in listOf(edge.low, edge.high)) {
            assertEquals(source[id * 2], retained[id * 2])
            assertEquals(source[id * 2 + 1], retained[id * 2 + 1])
        }
    }

    @Test fun guideCutsBecomeActualEdgesAndKeepTheOriginalBoundary() {
        val mesh = grid(4)
        val before = mesh.positions()
        val guides = listOf(guide("a", 5f, 8f, 23f, 27f), guide("b", 5f, 25f, 26f, 6f))
        val result = InteriorStructureMesh.refine(mesh, guides)
        assertEquals(mesh.boundaryEdges, result.mesh.boundaryEdges)
        assertContentEquals(before, result.mesh.positions().take(before.size).toFloatArray())
        assertEquals(area(mesh), area(result.mesh), .001)
        val edges = result.mesh.triangles.flatMap(::triangleEdges).toSet()
        for (paths in result.paths.values) for (path in paths) {
            assertTrue(path.size >= 2)
            assertTrue(path.toList().zipWithNext().all { (a, b) -> edgeOf(a, b) in edges })
        }
        val again = InteriorStructureMesh.refine(mesh, guides)
        assertContentEquals(result.mesh.positions(), again.mesh.positions())
        assertContentEquals(result.mesh.indices(), again.mesh.indices())
        assertContentEquals(before, mesh.positions())
    }

    @Test fun holesAndSeparateIslandsAreNotBridgedByGuides() {
        val hole = grid(6, hole = true)
        val result = InteriorStructureMesh.refine(hole, listOf(guide("valid", 4f, 4f, 40f, 10f)))
        assertEquals(area(hole), area(result.mesh), .001)
        assertEquals(hole.boundaryEdges, result.mesh.boundaryEdges)
        val before = hole.positions()
        assertFailsWith<IllegalArgumentException> {
            InteriorStructureMesh.refine(hole, listOf(guide("through-hole", 4f, 23f, 45f, 23f)))
        }
        assertContentEquals(before, hole.positions())
        val two = InteriorMesh(floatArrayOf(0f, 0f, 8f, 0f, 0f, 8f, 20f, 0f, 28f, 0f, 20f, 8f), intArrayOf(0, 1, 2, 3, 4, 5))
        assertFailsWith<IllegalArgumentException> { InteriorStructureMesh.refine(two, listOf(guide("gap", 2f, 2f, 22f, 2f))) }
    }

    @Test fun emptyGuidesAndInvalidGuidesNeverMutateSource() {
        val mesh = grid(2)
        val result = InteriorStructureMesh.refine(mesh, emptyList())
        assertContentEquals(mesh.positions(), result.mesh.positions())
        assertContentEquals(mesh.indices(), result.mesh.indices())
        assertFailsWith<IllegalArgumentException> { InteriorStructureMesh.refine(mesh, listOf(guide("outside", -4f, 2f, 7f, 6f))) }
        assertFailsWith<IllegalArgumentException> { InteriorStructureMesh.refine(mesh, listOf(guide("budget", 3f, 4f, 7f, 6f)), mesh.vertexCount) }
        assertFailsWith<IllegalArgumentException> { InteriorMesh(floatArrayOf(0f, 0f, 1f, 1f, 2f, 2f), intArrayOf(0, 1, 2)) }
    }

    @Test fun guidesRefineTheProductionAlphaMeshWithoutRetracingItsOutline() {
        val pixels = raster(hole = true)
        val original = assertNotNull(AdaptiveMeshGenerator.generate(96, 96, pixels.rgba(), 1,
            MeshSettings(outerMargin = 1f, maxEdgeDistance = 10f, interiorDensity = 14f)))
        val mesh = InteriorMesh(original.positions, original.indices)
        val result = InteriorStructureMesh.refine(mesh, listOf(guide("seam", 14f, 14f, 20f, 42f, 14f, 78f)))
        assertEquals(mesh.boundaryEdges, result.mesh.boundaryEdges)
        assertEquals(area(mesh), area(result.mesh), .01)
        for (loop in original.boundaryLoops) for (id in loop) {
            assertEquals(original.positions[id * 2], result.mesh.positions()[id * 2])
            assertEquals(original.positions[id * 2 + 1], result.mesh.positions()[id * 2 + 1])
        }
        val plan = InteriorSurface.compile(result.mesh, pixels)
        assertTrue(plan.evaluate(.8f, -.5f).movableVertices > 0)
    }

    @Test fun imageCuesDetectChromaticEdgesButNeverAutomaticallyMakeFolds() {
        val size = 48
        // Red and green chosen to have approximately equal Rec.709 luminance.
        val pixels = ByteArray(size * size * 4) { i ->
            val x = i / 4 % size
            when (i % 4) { 0 -> if (x < size / 2) 255.toByte() else 0; 1 -> if (x >= size / 2) 76 else 0; 3 -> 255.toByte(); else -> 0 }
        }
        val cues = InteriorStructureAnalysis.suggest(InteriorRaster(size, size, pixels), InteriorStructureAnalysis.Settings(scale = 1))
        assertTrue(cues.isNotEmpty(), "a color edge must not disappear in a luminance-only detector")
        assertTrue(cues.all { it.purpose == InteriorGuidePurpose.TOPOLOGY })
        assertTrue(cues.any { it.points.all { p -> abs(p.x - size / 2f) < 3 } && abs(it.points.last().y - it.points.first().y) > 24 })
        val refined = InteriorStructureMesh.refine(grid(6), cues)
        assertTrue(refined.paths.values.all { paths -> paths.isNotEmpty() && paths.all { it.size >= 2 } })
        assertEquals(cues, InteriorStructureAnalysis.suggest(InteriorRaster(size, size, pixels), InteriorStructureAnalysis.Settings(scale = 1)))
        val translucent = pixels.copyOf()
        for (i in 3 until translucent.size step 4) translucent[i] = 64
        assertEquals(cues, InteriorStructureAnalysis.suggest(InteriorRaster(size, size, translucent), InteriorStructureAnalysis.Settings(scale = 1)),
            "a uniformly translucent material must retain the same structure suggestions")
    }

    @Test fun transparentRgbAndSilhouetteAreNotInternalStructure() {
        val size = 40
        fun image(noise: Boolean) = ByteArray(size * size * 4) { i ->
            val x = i / 4 % size; val y = i / 4 / size
            val visible = x in 8..31 && y in 8..31
            if (i % 4 == 3) { if (visible) 255.toByte() else 0 } else if (visible) 150.toByte() else if (noise) (i * 37).toByte() else 0
        }
        val a = InteriorStructureAnalysis.suggest(InteriorRaster(size, size, image(false)))
        val b = InteriorStructureAnalysis.suggest(InteriorRaster(size, size, image(true)))
        assertEquals(a, b)
        assertTrue(a.isEmpty())
    }

    @Test fun neutralAndDisabledAmountsAreExactAndPlansOwnTheirInputs() {
        val mesh = grid(); val pixels = raster()
        val plan = InteriorSurface.compile(mesh, pixels)
        val before = mesh.positions()
        assertContentEquals(before, plan.evaluate(0f, 0f).positions())
        val returned = plan.evaluate(0f, 0f).positions(); returned.fill(999f)
        assertContentEquals(before, plan.evaluate(0f, 0f).positions())
        for (settings in listOf(InteriorSurfaceSettings(horizontal = 0f, vertical = 0f), InteriorSurfaceSettings(bulge = 0f), InteriorSurfaceSettings(press = 0f))) {
            assertContentEquals(before, InteriorSurface.compile(mesh, pixels, settings).evaluate(.8f, -.7f).positions())
        }
    }

    @Test fun xAndYMoveTextureInTheirOwnDirectionWithoutMovingContour() {
        val mesh = grid(); val before = mesh.positions()
        val plan = InteriorSurface.compile(mesh, raster())
        val x = plan.evaluate(1f, 0f).positions(); val y = plan.evaluate(0f, 1f).positions()
        var movedX = 0; var movedY = 0
        for (i in 0 until mesh.vertexCount) {
            assertEquals(before[i * 2 + 1], x[i * 2 + 1])
            assertEquals(before[i * 2], y[i * 2])
            if (x[i * 2] > before[i * 2] + .01f) movedX++
            if (y[i * 2 + 1] > before[i * 2 + 1] + .01f) movedY++
        }
        assertTrue(movedX > 12 && movedY > 12, "both directions need a readable interior effect")
        for (edge in mesh.boundaryEdges) for (i in listOf(edge.low, edge.high)) for (axis in 0..1) {
            assertEquals(before[i * 2 + axis], x[i * 2 + axis]); assertEquals(before[i * 2 + axis], y[i * 2 + axis])
        }
        val free = InteriorSurface.compile(mesh, raster(), InteriorSurfaceSettings(contour = 0f)).evaluate(1f, 1f).positions()
        assertTrue(mesh.boundaryEdges.any { e -> listOf(e.low, e.high).any { free[it * 2] != before[it * 2] || free[it * 2 + 1] != before[it * 2 + 1] } })
    }

    @Test fun alphaSupportEdgesIncludingHolesArePinnedEvenInsideTriangles() {
        val mesh = grid()
        val raster = raster(hole = true)
        val before = mesh.positions()
        val after = InteriorSurface.compile(mesh, raster).evaluate(-1f, 1f).positions()
        // The alpha-hole boundary at 32..47 lies inside an otherwise full rectangular mesh.
        // All neighbouring triangles are fixed, not merely vertices of the mesh's outer contour.
        for (i in 0 until mesh.vertexCount) {
            val p = mesh.points[i]
            if (p.x in 24.0..56.0 && p.y in 24.0..56.0) {
                assertEquals(before[i * 2], after[i * 2]); assertEquals(before[i * 2 + 1], after[i * 2 + 1])
            }
        }
    }

    @Test fun foldDirectionRetainsReferencePreferenceAndTopologyGuidesDoNotDeform() {
        val mesh = grid(); val raster = raster()
        val g = guide("crease", 32f, 12f, 45f, 40f, 38f, 84f).copy(purpose = InteriorGuidePurpose.FOLD, radius = 28f)
        val bare = InteriorSurface.compile(mesh, raster).evaluate(.7f, -.4f).positions()
        val topological = InteriorSurface.compile(mesh, raster, guides = listOf(g.copy(purpose = InteriorGuidePurpose.TOPOLOGY))).evaluate(.7f, -.4f).positions()
        assertContentEquals(bare, topological)
        val a = InteriorSurface.compile(mesh, raster, guides = listOf(g)).evaluate(.7f, -.4f).positions()
        val b = InteriorSurface.compile(mesh, raster, guides = listOf(g.copy(points = g.points.reversed()))).evaluate(.7f, -.4f).positions()
        assertTrue(a.indices.any { abs(a[it] - b[it]) > .0001f }, "the reference X response follows the authored normal")
        val y = InteriorSurface.compile(mesh, raster, guides = listOf(g)).evaluate(0f, -.4f).positions()
        val reverseY = InteriorSurface.compile(mesh, raster, guides = listOf(g.copy(points = g.points.reversed()))).evaluate(0f, -.4f).positions()
        for (i in y.indices) assertEquals(y[i], reverseY[i], .0001f)
        assertTrue(a.indices.any { abs(a[it] - bare[it]) > .05f }, "fold is visibly different from a topology-only guide")
    }

    @Test fun extremeAndCombinedPosesRemainFiniteAndDoNotInvertOrOverstretchTriangles() {
        val mesh = grid(10)
        val plan = InteriorSurface.compile(mesh, raster(80), InteriorSurfaceSettings(2f, 2f, 2f, 2f, 2f, 1f),
            listOf(guide("fold", 22f, 10f, 38f, 70f).copy(purpose = InteriorGuidePurpose.FOLD, radius = 28f, amount = 2f)))
        var constrained = false
        for (ix in -4..4) for (iy in -4..4) {
            val frame = plan.evaluate(ix / 4f, iy / 4f); val xy = frame.positions()
            if (frame.safetyGain < 1) constrained = true
            assertTrue(xy.all(Float::isFinite))
            for (t in mesh.triangles) {
                fun p(i: Int) = Point(xy[i * 2].toDouble(), xy[i * 2 + 1].toDouble())
                val ratio = cross(p(t.a), p(t.b), p(t.c)) / cross(mesh.points[t.a], mesh.points[t.b], mesh.points[t.c])
                assertTrue(ratio in .3999..2.4001, "area ratio $ratio at $ix,$iy")
            }
        }
        assertTrue(constrained, "the stress case must actually exercise the common area guard")
        assertFailsWith<IllegalArgumentException> { plan.evaluate(Float.NaN, 0f) }
    }

    @Test fun scalingRasterMeshAndGuideTogetherPreservesTheNormalizedEffect() {
        val mesh = grid(); val rgba = raster().rgba()
        val guide = guide("crease", 25f, 10f, 40f, 82f).copy(purpose = InteriorGuidePurpose.FOLD, radius = 24f)
        val a = InteriorSurface.compile(mesh, raster(), guides = listOf(guide)).evaluate(.6f, -.8f).positions()
        val factor = 2
        val scaled = InteriorMesh(mesh.positions().map { it * factor }.toFloatArray(), mesh.indices())
        val pixels = ByteArray(192 * 192 * 4) { i -> rgba[(((i / 4 / 192) / factor) * 96 + (i / 4 % 192) / factor) * 4 + i % 4] }
        val g = guide.copy(points = guide.points.map { InteriorPoint(it.x * factor, it.y * factor) }, radius = guide.radius * factor)
        val b = InteriorSurface.compile(scaled, InteriorRaster(192, 192, pixels), guides = listOf(g)).evaluate(.6f, -.8f).positions()
        for (i in a.indices) assertEquals(a[i], b[i] / factor, .0001f)
    }
}
