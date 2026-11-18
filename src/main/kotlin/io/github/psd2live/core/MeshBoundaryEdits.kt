package io.github.psd2live.core

import io.github.psd2live.core.mesh.ConstrainedTriangulator
import io.github.psd2live.core.mesh.Point
import io.github.psd2live.core.mesh.cross
import io.github.psd2live.core.mesh.pointOnSegment
import org.umamo.edit.*
import org.umamo.runtime.model.DrawableMesh
import kotlin.math.abs
import kotlin.math.sign

/** Repairs involving open rims. The ordinary cut and closed-hole fill operate inside one triangulated surface. */
internal object MeshBoundaryEdits {
    private fun points(mesh: DrawableMesh) = List(mesh.vertexCount) { i ->
        Point(mesh.positions[2 * i].toDouble(), mesh.positions[2 * i + 1].toDouble())
    }

    private fun area(p: List<Point>, face: List<Int>) = cross(p[face[0]], p[face[1]], p[face[2]])

    /** Separating-axis test for two triangles. Adjacent faces may share their boundary. */
    private fun overlaps(p: List<Point>, a: List<Int>, b: List<Int>): Boolean {
        fun separated(face: List<Int>, other: List<Int>): Boolean {
            val orientation = area(p, face)
            return face.indices.any { i ->
                val start = p[face[i]]
                val end = p[face[(i + 1) % 3]]
                other.all { cross(start, end, p[it]) * sign(orientation) <= abs(orientation) * 1e-12 }
            }
        }
        return !separated(a, b) && !separated(b, a)
    }

    /** An edge across empty space needs faces. Stitch one exposed edge from each island with two triangles. */
    fun connect(mesh: DrawableMesh, a: Int, b: Int): TopologyOpResult? {
        if (a !in 0 until mesh.vertexCount || b !in 0 until mesh.vertexCount || a == b) return null
        val adjacent = MeshTopology.buildVertexAdjacency(mesh.vertexCount, mesh.indices)
        if (b in MeshTopology.connectedVertices(adjacent, a)) return MeshTopologyOps.connectVertices(mesh, a, b)
        val uses = MeshTopology.edgeUses(mesh.indices)
        val faces = mesh.indices.toList().chunked(3)
        val p = points(mesh)
        fun rim(v: Int) = adjacent[v].filter { uses[MeshElement.Edge.of(it, v)]?.size == 1 }
        val rimA = rim(a)
        val rimB = rim(b)
        if (rimA.size != 2 || rimB.size != 2) return null
        var best: List<List<Int>>? = null
        var bestLength = Double.POSITIVE_INFINITY
        for (u in rimA) for (v in rimB) {
            val winding = sign(area(p, faces[uses.getValue(MeshElement.Edge.of(a, u)).single()]))
            if (winding == 0.0 || winding != sign(area(p, faces[uses.getValue(MeshElement.Edge.of(b, v)).single()]))) continue
            // The picked edge can be a side or the diagonal of the strip.
            val candidates = listOf(
                listOf(listOf(a, u, b), listOf(u, v, b)),
                listOf(listOf(a, u, v), listOf(a, v, b)),
                listOf(listOf(a, u, b), listOf(a, b, v)),
            )
            for (pair in candidates) {
                if (pair.any { area(p, it) == 0.0 } || overlaps(p, pair[0], pair[1])) continue
                // A third vertex right on a new edge would leave a T-junction, even without
                // positive-area overlap between triangles.
                if (pair.any { face -> face.indices.any { i ->
                        val x = face[i]
                        val y = face[(i + 1) % 3]
                        MeshElement.Edge.of(x, y) !in uses && p.indices.any { v ->
                            v != x && v != y && pointOnSegment(p[v], p[x], p[y])
                        }
                    } }) continue
                if (pair.any { face -> faces.any { overlaps(p, face, it) } }) continue
                val length = pair.sumOf { face -> face.indices.sumOf { i ->
                    val x = p[face[i]]
                    val y = p[face[(i + 1) % 3]]
                    kotlin.math.hypot(x.x - y.x, x.y - y.y)
                } }
                if (length < bestLength) {
                    bestLength = length
                    best = pair.map { if (sign(area(p, it)) == winding) it else it.reversed() }
                }
            }
        }
        val bridge = best ?: return null
        val result = DrawableMesh(mesh.positions, mesh.uvs, mesh.indices + bridge.flatten().toIntArray())
        if (!MeshManualOps.isLegal(result) && MeshManualOps.isLegal(mesh)) return null
        return TopologyOpResult(MeshTopologyEdit(result, List(mesh.vertexCount) { VertexSource.FromOld(it) }),
            setOf(MeshElement.Vertex(a), MeshElement.Vertex(b), MeshElement.Face(mesh.triangleCount), MeshElement.Face(mesh.triangleCount + 1)))
    }

    /** A boundary vertex has an open fan. Its opposite edges give the complete rim in order, including
     * the two vertices of an ear whose deletion needs no fill at all. */
    fun deleteVertexFan(mesh: DrawableMesh, vertex: Int): TopologyOpResult? {
        if (mesh.vertexCount <= 3 || vertex !in 0 until mesh.vertexCount) return null
        val faces = mesh.indices.toList().chunked(3)
        val (fan, kept) = faces.partition { vertex in it }
        if (fan.isEmpty()) return null
        val next = mutableMapOf<Int, Int>()
        for (face in fan) {
            val i = face.indexOf(vertex)
            if (next.put(face[(i + 1) % 3], face[(i + 2) % 3]) != null) return null
        }
        val ends = next.keys - next.values.toSet()
        val start = if (ends.isEmpty()) next.keys.first() else ends.singleOrNull() ?: return null
        val rim = mutableListOf(start)
        while (true) rim += next.remove(rim.last()) ?: break
        if (next.isNotEmpty()) return null
        if (rim.last() == start) rim.removeAt(rim.lastIndex)
        if (rim.size < 2 || rim.distinct().size != rim.size) return null
        val p = points(mesh)
        val fill = if (rim.size == 2) emptyList() else {
            val local = rim.map(p::get)
            val span = maxOf(local.maxOf { it.x } - local.minOf { it.x }, local.maxOf { it.y } - local.minOf { it.y })
            if (span == 0.0) return null
            val normalized = local.map { Point((it.x - local.first().x) / span, (it.y - local.first().y) / span) }
            val tri = ConstrainedTriangulator.triangulate(normalized, listOf(rim.indices.toList().toIntArray()), emptyList()) ?: return null
            val winding = sign(area(p, fan.first()))
            tri.triangles.map { t ->
                val face = listOf(rim[t.a], rim[t.b], rim[t.c])
                if (sign(area(p, face)) == winding) face else face.reversed()
            }
        }
        if ((kept.isEmpty() && fill.isEmpty()) || fill.any { face -> kept.any { overlaps(p, face, it) } }) return null
        val vertices = (0 until mesh.vertexCount).filter { it != vertex }
        fun removePair(values: FloatArray) = FloatArray(vertices.size * 2) { i -> values.getOrElse(vertices[i / 2] * 2 + i % 2) { 0f } }
        val indices = (kept + fill).flatten().map { if (it > vertex) it - 1 else it }.toIntArray()
        val result = DrawableMesh(removePair(mesh.positions), if (mesh.uvs.isEmpty()) mesh.uvs else removePair(mesh.uvs), indices)
        if (!MeshManualOps.isLegal(result) && MeshManualOps.isLegal(mesh)) return null
        return TopologyOpResult(MeshTopologyEdit(result, vertices.map { VertexSource.FromOld(it) }),
            fill.indices.mapTo(linkedSetOf()) { MeshElement.Face(kept.size + it) })
    }
}
