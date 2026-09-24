package io.github.psd2live.core

import java.nio.file.Path
import kotlinx.serialization.json.JsonObject
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt
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

    @Test fun fillParametersSurviveProjectStateRoundTripPerAlgorithm() {
        val custom = MeshFillParameters(
            poisson = PoissonFillParameters(edgeRatio = 3f, gradation = 0.5f, jitter = 0.1f),
            fractal = LatticeFillParameters(edgeRatio = 1.5f, gradation = 3f, angle = 30f),
            paving = PavingFillParameters(maxRows = 4),
        )
        val state = io.github.psd2live.ui.state.PSD2LiveState(
            meshFillParameters = custom,
            meshOverrides = mapOf("part" to MeshSettings(fillParameters = custom.copy(quadtree = LatticeFillParameters(angle = 45f)))),
        )
        val codec = io.github.psd2live.project.WorkspaceStateCodec
        val restored = codec.decode(codec.encode(state))
        assertEquals(custom, restored.meshFillParameters)
        assertEquals(state.meshOverrides, restored.meshOverrides)
        // Older projects have no fill groups; partial groups keep the other defaults.
        assertEquals(MeshFillParameters(), codec.decode(JsonObject(codec.encode(state).filterKeys { it != "meshFillParameters" }))
            .meshFillParameters)
        val merged = codec.mergeFillParameters(custom, kotlinx.serialization.json.buildJsonObject {
            put("paving", kotlinx.serialization.json.buildJsonObject { put("maxRows", kotlinx.serialization.json.JsonPrimitive(8)) })
        })
        assertEquals(custom.copy(paving = custom.paving.copy(maxRows = 8)), merged)
        assertTrue(runCatching { codec.mergeFillParameters(custom, kotlinx.serialization.json.buildJsonObject {
            put("fractal", kotlinx.serialization.json.buildJsonObject { put("edgeRatio", kotlinx.serialization.json.JsonPrimitive(9)) })
        }) }.isFailure, "out-of-range fill parameters must be rejected")
    }

    @Test fun gradedFillsDoNotRepeatTheContourRow() {
        val shapes = listOf(ellipse(320, 240, 140.0, 100.0), rectangle(320, 240, 20, 20, 300, 220))
        for ((width, height, rgba) in shapes) for (algorithm in MeshFillAlgorithm.entries - MeshFillAlgorithm.SIMPLE_TRIANGLES)
            for (mode in listOf(MeshEdgeMode.SINGLE, MeshEdgeMode.TRIPLE)) {
                val mesh = assertNotNull(AdaptiveMeshGenerator.generate(width, height, rgba, 8,
                    MeshSettings(maxEdgeDistance = 12f, interiorDensity = 40f, edgeMode = mode, fillAlgorithm = algorithm)))
                val rim = mesh.innerLoops.ifEmpty { mesh.boundaryLoops }
                val structural = (mesh.boundaryLoops + mesh.middleLoops + mesh.innerLoops).flatMapTo(hashSetOf()) { it.toList() }
                val spacing = rim.flatMap { loop -> loop.indices.map { dist(point(mesh, loop[it]), point(mesh, loop[(it + 1) % loop.size])) } }
                    .sorted().let { it[it.size / 2] }
                val interior = (0 until mesh.positions.size / 2).filter { it !in structural }
                assertTrue(interior.isNotEmpty(), "$algorithm $mode left the interior empty")
                for (v in interior) {
                    val depth = sqrt(rim.minOf { distanceSquaredToPolygon(point(mesh, v), loopPoints(mesh, it)) })
                    assertTrue(depth >= spacing * 0.9, "$algorithm $mode placed ${point(mesh, v)} ${"%.1f".format(depth)} px " +
                        "inside a contour spaced ${"%.1f".format(spacing)} px, a second contour row")
                }
            }
    }

    @Test fun fillParametersControlOnlyTheirOwnAlgorithm() {
        val (width, height, rgba) = rectangle(640, 440, 20, 20, 620, 420)
        fun mesh(algorithm: MeshFillAlgorithm, parameters: MeshFillParameters) = assertNotNull(AdaptiveMeshGenerator.generate(
            width, height, rgba, 8, MeshSettings(maxEdgeDistance = 12f, interiorDensity = 60f,
                fillAlgorithm = algorithm, fillParameters = parameters)))
        fun interior(m: AdaptiveMeshGenerator.Result) = m.positions.size / 2 - m.boundaryLoops.sumOf { it.size }
        val base = MeshFillParameters()
        fun ratio(p: MeshFillParameters, r: Float) = MeshFillParameters(
            poisson = p.poisson.copy(edgeRatio = r), quadtree = p.quadtree.copy(edgeRatio = r),
            fractal = p.fractal.copy(edgeRatio = r), paving = p.paving.copy(edgeRatio = r))
        for (algorithm in MeshFillAlgorithm.entries - MeshFillAlgorithm.SIMPLE_TRIANGLES) {
            val fine = interior(mesh(algorithm, ratio(base, 1f)))
            val coarse = interior(mesh(algorithm, ratio(base, 3f)))
            assertTrue(coarse < fine * 0.9, "$algorithm: edge transition 3 kept $coarse interior points vs $fine at 1")
        }
        val fractal = mesh(MeshFillAlgorithm.TRIANGLE_FRACTAL, base).positions.toList()
        assertEquals(fractal, mesh(MeshFillAlgorithm.TRIANGLE_FRACTAL, base.copy(
            poisson = PoissonFillParameters(jitter = 0f), quadtree = LatticeFillParameters(angle = 45f),
            paving = PavingFillParameters(maxRows = 2))).positions.toList(), "other groups must not affect the fractal fill")
        assertTrue(fractal != mesh(MeshFillAlgorithm.TRIANGLE_FRACTAL, base.copy(fractal = base.fractal.copy(angle = 30f)))
            .positions.toList(), "grid angle must rotate the fractal lattice")
        assertTrue(mesh(MeshFillAlgorithm.CONTOUR_PAVING, base).positions.toList() != mesh(MeshFillAlgorithm.CONTOUR_PAVING,
			base.copy(paving = base.paving.copy(maxRows = 0))).positions.toList(), "max rows must change the paving")
        assertTrue(mesh(MeshFillAlgorithm.GRADED_POISSON, base).positions.toList() != mesh(MeshFillAlgorithm.GRADED_POISSON,
            base.copy(poisson = base.poisson.copy(jitter = 0f))).positions.toList(), "randomness must change the Poisson samples")
    }

    @Test fun allFillAlgorithmsKeepValidConcaveContour() {
        val width = 240
        val height = 180
        val rgba = ByteArray(width * height * 4)
        for (y in 10 until 170) for (x in 10 until 230) {
            if (x in 80 until 140 && y in 10 until 90) continue // concave notch
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
            assertNoBoundaryChords(suppressed, algorithm.name)
            layouts += normal.positions.toList()
        }
        assertEquals(layouts.size, layouts.distinct().size, "fill selection must change the mesh")
    }

    @Test fun edgeModesPlaceRowsAtRequestedOffsets() {
        val (width, height, rgba) = rectangle(180, 120, 20, 20, 160, 100)
        val meshes = MeshEdgeMode.entries.associateWith { mode ->
            val mesh = assertNotNull(AdaptiveMeshGenerator.generate(width, height, rgba, 8,
                MeshSettings(edgeMode = mode, edgeWidth = 8f, outerMargin = 2f,
                    maxEdgeDistance = 12f, interiorDensity = 32f)), mode.name)
            assertEquals(1, mesh.boundaryLoops.size, mode.name)
            assertEquals(if (mode == MeshEdgeMode.SINGLE) 0 else 1, mesh.innerLoops.size, mode.name)
            assertEquals(if (mode == MeshEdgeMode.TRIPLE) 1 else 0, mesh.middleLoops.size, mode.name)
            assertManifold(mesh)
            mesh
        }
        fun topY(mesh: AdaptiveMeshGenerator.Result, loop: IntArray): Double = loop.map { point(mesh, it) }
            .filter { (x, y) -> x in 70.0..110.0 && y < 50.0 }.map { it.second }.average()
        // The TRIPLE middle row is the fitted guide itself; every other row is measured from it.
        val triple = meshes.getValue(MeshEdgeMode.TRIPLE)
        val guide = topY(triple, triple.middleLoops.single())
        val tripleOuter = topY(triple, triple.boundaryLoops.single())
        val tripleInner = topY(triple, triple.innerLoops.single())
        assertTrue(abs(guide - tripleOuter - 8.0) < 1.0 && abs(tripleInner - guide - 8.0) < 1.0,
            "triple rows at $tripleOuter / $guide / $tripleInner")
        val double = meshes.getValue(MeshEdgeMode.DOUBLE)
        val doubleOuter = topY(double, double.boundaryLoops.single())
        val doubleInner = topY(double, double.innerLoops.single())
        assertTrue(abs(guide - doubleOuter - 4.0) < 1.0 && abs(doubleInner - guide - 4.0) < 1.0,
            "double rows at $doubleOuter / $doubleInner around guide $guide")
        val single = meshes.getValue(MeshEdgeMode.SINGLE)
        val singleOuter = topY(single, single.boundaryLoops.single())
        assertTrue(abs(guide - singleOuter - 2.0) < 1.0, "single row at $singleOuter around guide $guide")
    }

    @Test fun edgeBandsFollowFixedStaggeredTemplates() {
        for ((name, shape) in listOf("rectangle" to rectangle(180, 120, 20, 20, 160, 100), "disk" to disk(200, 70.0))) {
            val (width, height, rgba) = shape
            for (mode in listOf(MeshEdgeMode.DOUBLE, MeshEdgeMode.TRIPLE)) {
                val mesh = assertNotNull(AdaptiveMeshGenerator.generate(width, height, rgba, 8,
                    MeshSettings(edgeMode = mode, edgeWidth = 8f, maxEdgeDistance = 12f, interiorDensity = 32f)))
                assertManifold(mesh)
                val faces = mesh.indices.toList().chunked(3).map { it.toSet() }.toSet()
                val outer = mesh.boundaryLoops.single()
                // Aligned row A over staggered row B: (A_i, A_i+1, B_i) and (A_i+1, B_i+1, B_i).
                val staggered = if (mode == MeshEdgeMode.DOUBLE) mesh.innerLoops.single() else mesh.middleLoops.single()
                assertEquals(outer.size, staggered.size, "$name/$mode")
                val n = outer.size
                for (i in 0 until n) {
                    val j = (i + 1) % n
                    assertTrue(setOf(outer[i], outer[j], staggered[i]) in faces, "$name/$mode lacks /\\ at $i")
                    assertTrue(setOf(outer[j], staggered[j], staggered[i]) in faces, "$name/$mode lacks \\/ at $i")
                }
                if (mode == MeshEdgeMode.TRIPLE) {
                    // Staggered B over aligned C: (B_i, B_i+1, C_i+1) and (B_i, C_i+1, C_i).
                    val inner = mesh.innerLoops.single()
                    assertEquals(n, inner.size)
                    for (i in 0 until n) {
                        val j = (i + 1) % n
                        assertTrue(setOf(staggered[i], staggered[j], inner[j]) in faces, "$name lacks \\/ at $i")
                        assertTrue(setOf(staggered[i], inner[j], inner[i]) in faces, "$name lacks /\\ at $i")
                    }
                }
            }
        }
    }

    @Test fun edgeBandWidthStaysUniformOnCurves() {
        val (width, height, rgba) = disk(220, 80.0)
        for (mode in listOf(MeshEdgeMode.DOUBLE, MeshEdgeMode.TRIPLE)) {
            val mesh = assertNotNull(AdaptiveMeshGenerator.generate(width, height, rgba, 8,
                MeshSettings(edgeMode = mode, edgeWidth = 10f, maxEdgeDistance = 12f, interiorDensity = 32f)))
            val rows = listOf(mesh.boundaryLoops.single()) + mesh.middleLoops + mesh.innerLoops
            val expected = 10.0
            for (r in 0 until rows.lastIndex) {
                val from = rows[r]
                val to = loopPoints(mesh, rows[r + 1])
                for (id in from) {
                    val gap = sqrt(distanceSquaredToPolygon(point(mesh, id), to))
                    assertTrue(abs(gap - expected) <= expected * 0.15,
                        "$mode row $r gap $gap deviates from $expected")
                }
            }
        }
    }

    @Test fun wideBandsKeepTheGuideOnTheCurveInATightCrop() {
        val (width, height, rgba) = ellipse(280, 200, 140.0, 100.0)
        val contour = List(720) { i ->
            val t = Math.PI * 2 * i / 720
            140.0 + 140.0 * kotlin.math.cos(t) to 100.0 + 100.0 * kotlin.math.sin(t)
        }
        for (edgeWidth in listOf(8f, 24f)) {
            val mesh = assertNotNull(AdaptiveMeshGenerator.generate(width, height, rgba, 8,
                MeshSettings(edgeMode = MeshEdgeMode.TRIPLE, edgeWidth = edgeWidth, maxEdgeDistance = 12f, interiorDensity = 32f)))
            val middle = mesh.middleLoops.single()
            val inner = loopPoints(mesh, mesh.innerLoops.single())
            for (id in middle) {
                val p = point(mesh, id)
                val drift = sqrt(distanceSquaredToPolygon(p, contour))
                assertTrue(drift <= 5.0, "width $edgeWidth: middle row drifted $drift px off the curve at $p")
                val gap = sqrt(distanceSquaredToPolygon(p, inner))
                assertTrue(abs(gap - edgeWidth) <= edgeWidth * 0.15, "width $edgeWidth: inner gap $gap at $p")
            }
        }
    }

    @Test fun tightlyCroppedTripleBandKeepsFullWidthTriangles() {
        val (width, height, rgba) = rectangle(120, 80, 0, 0, 120, 80)
        val edgeWidth = 8f
        val mesh = assertNotNull(AdaptiveMeshGenerator.generate(width, height, rgba, 8,
            MeshSettings(edgeMode = MeshEdgeMode.TRIPLE, edgeWidth = edgeWidth, maxEdgeDistance = 12f, interiorDensity = 32f)))
        assertEquals(1, mesh.innerLoops.size, "a tightly cropped layer must keep its edge band")
        assertManifold(mesh)
        // Like manually placed vertices, the outer row overhangs the raster instead of being clamped to it.
        val outer = loopPoints(mesh, mesh.boundaryLoops.single())
        assertTrue(outer.minOf { it.first } < -edgeWidth * 0.75 && outer.maxOf { it.first } > width + edgeWidth * 0.75,
            "outer row should overhang the raster by about one band width")
        val middle = loopPoints(mesh, mesh.middleLoops.single())
        val rim = listOf(0.0 to 0.0, width.toDouble() to 0.0, width.toDouble() to height.toDouble(), 0.0 to height.toDouble())
        assertTrue(middle.all { sqrt(distanceSquaredToPolygon(it, rim)) <= 5.0 }, "middle row must follow the silhouette")
        val band = (mesh.boundaryLoops + mesh.middleLoops).flatMap { it.toList() }.toSet()
        for (face in mesh.indices.toList().chunked(3)) {
            if (face.none { it in band }) continue
            val (a, b, c) = face.map { point(mesh, it) }
            val longest = maxOf(dist(a, b), dist(b, c), dist(c, a))
            val height2 = abs((b.first - a.first) * (c.second - a.second) - (b.second - a.second) * (c.first - a.first)) / longest
            assertTrue(height2 >= edgeWidth * 0.2, "band triangle ${listOf(a, b, c)} is a sliver (height $height2)")
        }
    }

    @Test fun structuredFillsAvoidFansAndSlivers() {
        val (width, height, rgba) = ellipse(320, 240, 140.0, 100.0)
        for (algorithm in STRUCTURED) {
            val mesh = assertNotNull(AdaptiveMeshGenerator.generate(width, height, rgba, 8,
                MeshSettings(maxEdgeDistance = 12f, interiorDensity = 40f, fillAlgorithm = algorithm)), algorithm.name)
            assertManifold(mesh)
            val boundary = mesh.boundaryLoops.flatMap { it.toList() }.toSet()
            val neighbors = HashMap<Int, MutableSet<Int>>()
            for (face in mesh.indices.toList().chunked(3)) for (v in face) neighbors.getOrPut(v) { mutableSetOf() } += face
            val (worst, set) = neighbors.filterKeys { it !in boundary }.maxBy { (_, set) -> set.count { it in boundary } }
            val worstFan = set.count { it in boundary }
            // A 2:1 first row meets three contour vertices per interior vertex, up to five where the contour curves.
            assertTrue(worstFan <= 5, "$algorithm: interior vertex ${point(mesh, worst)} connects to " +
                "${set.filter { it in boundary }.map { point(mesh, it) }}")
            val angles = mesh.indices.toList().chunked(3).map { face ->
                val (a, b, c) = face.map { point(mesh, it) }
                minimumAngleDegrees(a, b, c)
            }
            val good = angles.count { it >= 20.0 }.toDouble() / angles.size
            assertTrue(good >= 0.95, "$algorithm: only ${"%.1f".format(good * 100)}% of triangles reach 20 degrees")
        }
    }

    @Test fun interiorDensityVisiblyControlsStructuredFills() {
        val (width, height, rgba) = rectangle(640, 440, 20, 20, 620, 420)
        val counts = STRUCTURED.associateWith { algorithm ->
            fun interior(density: Float): Int {
                val mesh = assertNotNull(AdaptiveMeshGenerator.generate(width, height, rgba, 8,
                    MeshSettings(maxEdgeDistance = 12f, interiorDensity = density, fillAlgorithm = algorithm)))
                return mesh.positions.size / 2 - mesh.boundaryLoops.sumOf { it.size }
            }
            interior(20f) to interior(40f)
        }
        // The graded rings from the fixed contour spacing cost the same at both densities; the bulk quarters.
        for ((algorithm, pair) in counts) {
            val (dense, sparse) = pair
            assertTrue(dense >= sparse * 1.5, "$algorithm: density 20 gave $dense interior points vs $sparse at 40 (all: $counts)")
        }
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
        for (part in parts) for (algorithm in MeshFillAlgorithm.entries) for (mode in MeshEdgeMode.entries) {
            val raster = part.source.raster
            for (suppress in listOf(false, true)) {
                val mesh = assertNotNull(AdaptiveMeshGenerator.generate(raster.width, raster.height,
                    raster.rgba, 8, MeshSettings(maxEdgeDistance = 12f, interiorDensity = 40f,
                        edgeMode = mode, fillAlgorithm = algorithm, suppressBoundaryDiagonals = suppress)))
                assertEquals(1, mesh.boundaryLoops.size, part.source.name)
                if (mode != MeshEdgeMode.SINGLE) assertEquals(1, mesh.innerLoops.size,
                    "${part.source.name} with $mode lost its edge band")
                if (mode == MeshEdgeMode.TRIPLE) assertEquals(1, mesh.middleLoops.size,
                    "${part.source.name} lost its middle contour row")
                assertTrue(mesh.boundaryLoops.single().size <= 40,
                    "${part.source.name} with $algorithm/$mode and suppression=$suppress fell back to a jagged raster contour")
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

    private fun assertNoBoundaryChords(mesh: AdaptiveMeshGenerator.Result, label: String = "mesh") {
        val boundary = mesh.boundaryLoops.flatMapTo(hashSetOf()) { it.toList() }
        val neighbors = mesh.boundaryLoops.flatMapTo(hashSetOf()) { loop ->
            loop.indices.map { index -> edge(loop[index], loop[(index + 1) % loop.size]) }
        }
        for (index in mesh.indices.indices step 3) {
            for (side in 0..2) {
                val a = mesh.indices[index + side]
                val b = mesh.indices[index + (side + 1) % 3]
                assertTrue(a !in boundary || b !in boundary || edge(a, b) in neighbors,
                    "$label has a nonadjacent contour chord: $a-$b at ${point(mesh, a)} - ${point(mesh, b)}")
            }
        }
    }

    private fun assertManifold(mesh: AdaptiveMeshGenerator.Result) {
        val boundaryEdges = mesh.boundaryLoops.flatMapTo(hashSetOf()) { loop ->
            loop.indices.map { i -> edge(loop[i], loop[(i + 1) % loop.size]) }
        }
        val uses = mutableMapOf<Pair<Int, Int>, Int>()
        for (i in mesh.indices.indices step 3) for (side in 0..2) {
            val a = mesh.indices[i + side]
            val b = mesh.indices[i + (side + 1) % 3]
            val key = edge(a, b)
            uses[key] = (uses[key] ?: 0) + 1
        }
        assertTrue(uses.all { (edge, count) -> count == if (edge in boundaryEdges) 1 else 2 })
    }

    private fun edge(a: Int, b: Int) = minOf(a, b) to maxOf(a, b)

    private data class Raster(val width: Int, val height: Int, val rgba: ByteArray)

    private fun raster(width: Int, height: Int, inside: (Int, Int) -> Boolean): Raster {
        val rgba = ByteArray(width * height * 4)
        for (y in 0 until height) for (x in 0 until width) if (inside(x, y)) rgba[(y * width + x) * 4 + 3] = -1
        return Raster(width, height, rgba)
    }

    private fun rectangle(width: Int, height: Int, x0: Int, y0: Int, x1: Int, y1: Int) =
        raster(width, height) { x, y -> x in x0 until x1 && y in y0 until y1 }

    private fun disk(size: Int, radius: Double) = ellipse(size, size, radius, radius)

    private fun ellipse(width: Int, height: Int, rx: Double, ry: Double) = raster(width, height) { x, y ->
        val dx = (x + 0.5 - width / 2.0) / rx
        val dy = (y + 0.5 - height / 2.0) / ry
        dx * dx + dy * dy <= 1.0
    }

    private fun point(mesh: AdaptiveMeshGenerator.Result, id: Int) =
        mesh.positions[id * 2].toDouble() to mesh.positions[id * 2 + 1].toDouble()

    private fun loopPoints(mesh: AdaptiveMeshGenerator.Result, loop: IntArray) = loop.map { point(mesh, it) }

    private fun dist(a: Pair<Double, Double>, b: Pair<Double, Double>) = hypot(a.first - b.first, a.second - b.second)

    private fun distanceSquaredToPolygon(p: Pair<Double, Double>, loop: List<Pair<Double, Double>>): Double =
        loop.indices.minOf { i ->
            val a = loop[i]
            val b = loop[(i + 1) % loop.size]
            val dx = b.first - a.first
            val dy = b.second - a.second
            val lengthSquared = dx * dx + dy * dy
            val t = if (lengthSquared == 0.0) 0.0
                else (((p.first - a.first) * dx + (p.second - a.second) * dy) / lengthSquared).coerceIn(0.0, 1.0)
            val x = a.first + dx * t - p.first
            val y = a.second + dy * t - p.second
            x * x + y * y
        }

    private fun minimumAngleDegrees(a: Pair<Double, Double>, b: Pair<Double, Double>, c: Pair<Double, Double>): Double {
        fun angle(p: Pair<Double, Double>, q: Pair<Double, Double>, r: Pair<Double, Double>): Double {
            val ux = q.first - p.first; val uy = q.second - p.second
            val vx = r.first - p.first; val vy = r.second - p.second
            return Math.toDegrees(atan2(abs(ux * vy - uy * vx), ux * vx + uy * vy))
        }
        return minOf(angle(a, b, c), angle(b, c, a), angle(c, a, b))
    }

    private companion object {
        val STRUCTURED = listOf(MeshFillAlgorithm.ADAPTIVE_QUADTREE, MeshFillAlgorithm.TRIANGLE_FRACTAL,
            MeshFillAlgorithm.CONTOUR_PAVING)
    }
}
