package io.github.psd2live.core.interior

import io.github.psd2live.core.AdaptiveMeshGenerator
import io.github.psd2live.core.mesh.*
import kotlin.math.abs
import kotlin.math.max

/**
 * Cuts the existing triangulation along editable guides. Existing vertices and every boundary edge
 * retain their indices/coordinates. No contour retracing, cross-hole bridge or Delaunay flip can erase
 * a guide. This is an authoring operation, never a per-frame operation. New vertices mean that callers
 * MUST NOT apply it to an already keyed/skinned mesh without an explicit attribute-transfer step.
 */
internal object InteriorStructureMesh {
    data class Result(val mesh: InteriorMesh, val paths: Map<String, List<IntArray>>)
    private data class GuidePath(val id: String, val points: List<InteriorPoint>)

    fun refine(source: InteriorMesh, guides: List<InteriorGuide>, maxVertices: Int = 12_000): Result {
        require(guides.size <= 64 && guides.map { it.id }.distinct().size == guides.size) { "Too many or duplicate interior guides" }
        require(maxVertices >= source.vertexCount) { "Interior vertex budget is smaller than the source mesh" }
        val paths = guides.map { g -> GuidePath(g.id, if (g.purpose == InteriorGuidePurpose.FOLD)
            InteriorSurfaceCompiler.guideSamples(g) else g.points) }
        val points = source.points.toMutableList()
        var triangles = source.triangles.toMutableList()
        val span = max(points.maxOf { it.x } - points.minOf { it.x }, points.maxOf { it.y } - points.minOf { it.y })
        val tolerance = max(span * 1e-7, 1e-8)
        val tolerance2 = tolerance * tolerance
        fun same(a: Point, b: Point) = distanceSquared(a, b) <= tolerance2
        fun find(p: Point) = points.indexOfFirst { same(it, p) }
        fun append(p: Point): Int {
            val found = find(p)
            if (found >= 0) return found
            require(points.size < maxVertices) { "Interior mesh vertex budget exceeded" }
            points += snap(p)
            return points.lastIndex
        }
        fun onSegment(p: Point, a: Point, b: Point): Boolean = distanceSquaredToSegment(p, a, b) <= tolerance2
        fun oriented(a: Int, b: Int, c: Int, sign: Double): Triangle =
            if (cross(points[a], points[b], points[c]) * sign > 0) Triangle(a, b, c) else Triangle(a, c, b)

        // Insert endpoints first. A later segment then enters/leaves every crossed triangle through
        // complete edges; cutting the supporting line cannot extend beyond the finite guide segment.
        fun insert(p: Point): Int {
            val existing = find(p)
            if (existing >= 0) return existing
            val edge = triangles.asSequence().flatMap { triangleEdges(it).asSequence() }.distinct()
                .firstOrNull { onSegment(p, points[it.low], points[it.high]) }
            val affected = triangles.indices.filter { i ->
                if (edge != null) edge in triangleEdges(triangles[i]) else {
                    val t = triangles[i]
                    pointInTriangleInclusive(p, points[t.a], points[t.b], points[t.c])
                }
            }
            require(affected.isNotEmpty()) { "Interior guide endpoint lies outside the mesh" }
            // Do not subdivide a boundary: its original vertices AND original edges are retained.
            require(edge !in source.boundaryEdges) { "Interior guide endpoint touches the boundary" }
            val id = append(p)
            val additions = affected.flatMap { i ->
                val t = triangles[i]
                val sign = cross(points[t.a], points[t.b], points[t.c])
                if (edge != null) {
                    val other = oppositeVertex(t, edge)
                    listOf(oriented(edge.low, id, other, sign), oriented(id, edge.high, other, sign))
                } else listOf(oriented(t.a, t.b, id, sign), oriented(t.b, t.c, id, sign), oriented(t.c, t.a, id, sign))
            }
            for (i in affected.asReversed()) triangles.removeAt(i)
            triangles.addAll(additions)
            return id
        }

        for (guide in paths) for (p in guide.points) insert(p.geometry())
        for (guide in paths) {
            for ((from, to) in guide.points.zipWithNext()) {
                val a = points[find(from.geometry())]; val b = points[find(to.geometry())]
                val length = distance(a, b)
                require(length > tolerance) { "Interior guide segment is too short at mesh precision" }
                val crossed = linkedMapOf<Edge, Int>()
                val replacements = ArrayList<Triangle>()
                fun signed(p: Point) = cross(a, b, p) / length
                fun intersection(e: Edge): Int = crossed.getOrPut(e) {
                    val p = points[e.low]; val q = points[e.high]
                    val u = signed(p) / (signed(p) - signed(q))
                    append(lerp(p, q, u))
                }
                for (triangle in triangles) {
                    val ids = listOf(triangle.a, triangle.b, triangle.c)
                    val sides = ids.map { signed(points[it]) }
                    if (sides.min() >= -tolerance || sides.max() <= tolerance) {
                        replacements += triangle; continue
                    }
                    // Intersections of the finite segment with this face. The midpoint must be on
                    // the guide: do not cut triangles beyond either endpoint or in a separate island.
                    val hits = ArrayList<Point>()
                    for (j in 0..2) {
                        val k = (j + 1) % 3
                        if (abs(sides[j]) <= tolerance) hits += points[ids[j]]
                        if (sides[j] * sides[k] < -tolerance2) {
                            hits += lerp(points[ids[j]], points[ids[k]], sides[j] / (sides[j] - sides[k]))
                        }
                    }
                    val mid = Point(hits.sumOf { it.x } / hits.size, hits.sumOf { it.y } / hits.size)
                    val progress = ((mid.x - a.x) * (b.x - a.x) + (mid.y - a.y) * (b.y - a.y)) / (length * length)
                    if (progress <= 0 || progress >= 1) { replacements += triangle; continue }
                    val sign = cross(points[triangle.a], points[triangle.b], points[triangle.c])
                    for (side in listOf(-1, 1)) {
                        val polygon = ArrayList<Int>()
                        for (j in 0..2) {
                            val k = (j + 1) % 3
                            if (side * sides[j] >= -tolerance) polygon += ids[j]
                            if (sides[j] * sides[k] < -tolerance2) polygon += intersection(edgeOf(ids[j], ids[k]))
                        }
                        val unique = polygon.distinct()
                        for (j in 1 until unique.size - 1) replacements += oriented(unique[0], unique[j], unique[j + 1], sign)
                    }
                }
                triangles = replacements
                val line = points.indices.filter { onSegment(points[it], a, b) }.sortedBy {
                    (points[it].x - a.x) * (b.x - a.x) + (points[it].y - a.y) * (b.y - a.y)
                }
                val edges = triangles.flatMap(::triangleEdges).toHashSet()
                require(line.zipWithNext().all { (u, v) -> edgeOf(u, v) in edges }) {
                    "Interior guide crosses a hole, boundary or disconnected island"
                }
            }
        }
        val constraints = source.boundaryEdges.toMutableSet()
        for (guide in paths) for ((from, to) in guide.points.zipWithNext()) {
            val a = from.geometry(); val b = to.geometry()
            val line = points.indices.filter { onSegment(points[it], a, b) }.sortedBy {
                (points[it].x - a.x) * (b.x - a.x) + (points[it].y - a.y) * (b.y - a.y)
            }
            for ((u, v) in line.zipWithNext()) constraints += edgeOf(u, v)
        }
        // Cutting a triangle can leave very thin fans. Reuse the production triangulator's
        // connectivity relaxation, with EVERY guide edge protected, before compiling shape weights.
        if (guides.isNotEmpty()) AdaptiveMeshGenerator.relaxToConstrainedDelaunay(points, triangles, constraints)
        val result = InteriorMesh(FloatArray(points.size * 2) { i ->
            (if (i % 2 == 0) points[i / 2].x else points[i / 2].y).toFloat()
        }, triangles.flatMap { listOf(it.a, it.b, it.c) }.toIntArray())
        require(result.boundaryEdges == source.boundaryEdges) { "Interior guides must not change the boundary" }
        // Crossings introduced by later guides also split earlier guide edges. Rebuild their paths
        // from final topology, so a path never reports an edge that has since been subdivided.
        val edges = result.triangles.flatMap(::triangleEdges).toHashSet()
        val finalPaths = paths.associate { guide -> guide.id to guide.points.zipWithNext().map { (from, to) ->
            val a = from.geometry(); val b = to.geometry()
            val line = points.indices.filter { onSegment(points[it], a, b) }.sortedBy {
                (points[it].x - a.x) * (b.x - a.x) + (points[it].y - a.y) * (b.y - a.y)
            }
            require(line.zipWithNext().all { (u, v) -> edgeOf(u, v) in edges }) { "Interior guide constraint was lost" }
            line.toIntArray()
        } }
        return Result(result, finalPaths)
    }
}
