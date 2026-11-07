package io.github.psd2live.core.mesh.structure

import io.github.psd2live.core.AdaptiveMeshGenerator
import io.github.psd2live.core.MeshEdgeMode
import io.github.psd2live.core.MeshSettings
import io.github.psd2live.core.mesh.*
import org.umamo.format.art.analyzeAlpha
import org.umamo.format.art.simplified
import kotlin.math.*
import kotlin.test.*

/** A rounded panel of cloth with one drawn crease, meshed as the generator meshes layers, then rebuilt. */
class StructureMesherTest {
	private val w = 220; private val h = 200

	private fun crease(): List<Point> = (0..40).map { i -> val t = i / 40.0; Point(60 + 100 * t, 60 + 70 * t - 30 * sin(PI * t)) }

	private fun raster(): ByteArray {
		val rgba = ByteArray(w * h * 4)
		val path = crease()
		for (y in 0 until h) for (x in 0 until w) {
			val i = (y * w + x) * 4
			val inside = (x in 20 until 200 && y in 20 until 180) &&
				hypot(max(0.0, abs(x - 110.0) - 70), max(0.0, abs(y - 100.0) - 60)) < 20
			if (!inside) continue
			val p = Point(x + .5, y + .5)
			var best = Double.POSITIVE_INFINITY; var at = 0.0
			for (k in 0 until path.size - 1) {
				val d = sqrt(distanceSquaredToSegment(p, path[k], path[k + 1]))
				if (d < best) { best = d; at = (k + .5) / (path.size - 1) }
			}
			val ink = ((.4 + 1.6 * sin(PI * at)) + .5 - best).coerceIn(0.0, 1.0) * sin(PI * at).pow(.5)
			val shade = (216 - (216 - 74) * ink).roundToInt()
			rgba[i] = shade.toByte(); rgba[i + 1] = (shade - 16).toByte(); rgba[i + 2] = (shade - 32).toByte(); rgba[i + 3] = -1
		}
		return rgba
	}

	private class Case(val old: AdaptiveMeshGenerator.Result, val rim: PreservedRim, val mesh: StructureMesh)

	@Test
	fun contourPrecisionsMatchSeparateTracesForIslandsAndHoles() {
		val rgba = raster()
		for (y in 75..100) for (x in 80..110) rgba[(y * w + x) * 4 + 3] = 0
		for (y in 5..9) for (x in 5..10) rgba[(y * w + x) * 4 + 3] = -1
		val rings = assertNotNull(analyzeAlpha(w, h, rgba, contourEpsilon = 0f)).contours
		assertEquals(3, rings.size)
		assertEquals(1, rings.count { it.isHole })
		for (epsilon in listOf(0.35f, 1f)) {
			val expected = assertNotNull(analyzeAlpha(w, h, rgba, contourEpsilon = epsilon)).contours
			for ((index, ring) in rings.withIndex()) {
				val simplified = ring.simplified(epsilon)
				assertEquals(expected[index].isHole, simplified.isHole)
				assertContentEquals(expected[index].points, simplified.points)
			}
		}
	}

	private fun build(mode: MeshEdgeMode, rows: Int, settings: StructureMeshSettings = StructureMeshSettings()): Case {
		val rgba = raster()
		val old = assertNotNull(AdaptiveMeshGenerator.generate(w, h, rgba, 8, MeshSettings(edgeMode = mode, edgeWidth = 10f,
			maxEdgeDistance = 10f, interiorDensity = 24f)))
		val rim = PreservedRim.of(old.positions, old.indices, rows)
		val analysis = TextureStructure.analyze(w, h, rgba, settings.structure, rim.domain)
		return Case(old, rim, StructureMesher.mesh(rim, analysis, settings))
	}

	private fun edges(triangles: IntArray): Map<Edge, Int> {
		val counts = HashMap<Edge, Int>()
		for (t in 0 until triangles.size / 3) for (k in 0..2) counts.merge(edgeOf(triangles[t * 3 + k], triangles[t * 3 + (k + 1) % 3]), 1, Int::plus)
		return counts
	}

	@Test
	fun theOutlineAndEdgeBandStayAndTheCreaseGetsABand() {
		val case = build(MeshEdgeMode.DOUBLE, 1, StructureMeshSettings(bands = true))
		val mesh = case.mesh
		// Kept triangles come back with the same corners at the same places.
		val keptOf = HashMap<Int, Int>()
		mesh.kept.forEachIndexed { new, old -> if (old >= 0) keptOf[old] = new }
		val newTriangles = (0 until mesh.triangles.size / 3).map { t -> (0..2).map { mesh.triangles[t * 3 + it] }.toSet() }.toSet()
		assertTrue(case.rim.keptTriangles.isNotEmpty())
		for (t in case.rim.keptTriangles) {
			val corners = (0..2).map { keptOf.getValue(case.old.indices[t * 3 + it]) }
			assertTrue(corners.toSet() in newTriangles)
			for (k in 0..2) {
				val old = case.old.indices[t * 3 + k]
				assertEquals(Point(case.old.positions[old * 2].toDouble(), case.old.positions[old * 2 + 1].toDouble()), mesh.points[corners[k]])
			}
		}
		for (loop in case.old.boundaryLoops) loop.forEach { assertTrue(it in keptOf) }
		// The crease is followed by a banded row lying on the drawn stroke.
		val fold = mesh.lines.filter { it.line.kind == StructureKind.FOLD && it.banded }
		assertEquals(1, fold.size, "${mesh.lines.map { it.line.kind to it.banded }}")
		val row = fold.single().vertices
		assertTrue(row.size >= 6)
		val path = crease()
		val counts = edges(mesh.triangles)
		for (v in row) assertTrue(path.zipWithNext().minOf { (a, b) -> sqrt(distanceSquaredToSegment(mesh.points[v], a, b)) } < 1.5)
		for (i in 0 until row.size - 1) assertTrue(edgeOf(row[i], row[i + 1]) in counts)
		// Each inner station has a rung straight across the crease.
		for (i in 1 until row.size - 1) {
			val p = mesh.points[row[i]]
			val tx = mesh.points[row[i + 1]].x - mesh.points[row[i - 1]].x; val ty = mesh.points[row[i + 1]].y - mesh.points[row[i - 1]].y
			val tn = hypot(tx, ty)
			val across = counts.keys.filter { it.low == row[i] || it.high == row[i] }.count { e ->
				val q = mesh.points[if (e.low == row[i]) e.high else e.low]
				abs(((q.x - p.x) * tx + (q.y - p.y) * ty) / (tn * distance(p, q))) < sin(25 * PI / 180)
			}
			assertTrue(across >= 2, "station $i has $across rungs")
		}
		assertTrue(counts.values.all { it in 1..2 })
	}

	@Test
	fun byDefaultEveryNewVertexLiesOnALine() {
		val case = build(MeshEdgeMode.DOUBLE, 1)
		val onLines = case.mesh.lines.flatMap { it.vertices.toList() }.toSet()
		assertTrue(case.mesh.lines.any { it.line.kind == StructureKind.FOLD && !it.banded })
		case.mesh.kept.forEachIndexed { i, old -> if (old < 0) assertTrue(i in onLines, "vertex $i is off every line") }
		assertTrue(edges(case.mesh.triangles).values.all { it in 1..2 })
	}

	@Test
	fun singleRowMeshesAndPlainFillsStayManifoldAndDeterministic() {
		val first = build(MeshEdgeMode.SINGLE, 0)
		val second = build(MeshEdgeMode.SINGLE, 0)
		assertEquals(first.mesh.points, second.mesh.points)
		assertContentEquals(first.mesh.triangles, second.mesh.triangles)
		assertTrue(edges(first.mesh.triangles).values.all { it in 1..2 })
		val plain = build(MeshEdgeMode.TRIPLE, 2, StructureMeshSettings(structure = StructureSettings(kinds = emptySet()), fill = true))
		assertTrue(plain.mesh.lines.isEmpty())
		assertTrue(edges(plain.mesh.triangles).values.all { it in 1..2 })
	}

	@Test
	fun boundaryVerticesSpendTheBudgetOnceAcrossAllComponents() {
		for (components in 1..2) {
			val points = FloatArray(components * 50) { i ->
				val vertex = i / 2
				if (i % 2 == 0) (vertex % 5) * 10f + (vertex / 25) * 100f else (vertex % 25 / 5) * 10f
			}
			val triangles = buildList {
				for (part in 0 until components) for (r in 0..3) for (c in 0..3) {
					val a = part * 25 + r * 5 + c
					addAll(listOf(a, a + 1, a + 6, a, a + 6, a + 5))
				}
			}.toIntArray()
			val rim = PreservedRim.of(points, triangles, 0)
			val analysis = StructureAnalysis(141, 41, 1, emptyList())
			val expected = StructureMesher.mesh(rim, analysis)
			assertEquals(16 * components, expected.points.size)
			for (fill in listOf(false, true)) {
				val bounded = StructureMesher.mesh(rim, analysis, StructureMeshSettings(maxVertices = expected.points.size, fill = fill))
				assertEquals(expected.points, bounded.points)
				assertContentEquals(expected.triangles, bounded.triangles)
			}
			if (components == 2) assertFailsWith<StructureMesher.Failure> {
				StructureMesher.mesh(rim, analysis, StructureMeshSettings(maxVertices = expected.points.size - 1))
			}
		}
	}

}
