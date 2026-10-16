package io.github.psd2live.core.interior

import io.github.psd2live.core.mesh.Edge
import io.github.psd2live.core.mesh.Point
import io.github.psd2live.core.mesh.Triangle
import io.github.psd2live.core.mesh.cross
import io.github.psd2live.core.mesh.triangleEdges
import kotlin.math.abs

/** Source-raster coordinates. Neither atlas UVs, document placement nor canvas zoom belong here. */
internal data class InteriorPoint(val x: Float, val y: Float) {
    init { require(x.isFinite() && y.isFinite()) { "Interior points must be finite" } }
    internal fun geometry() = Point(x.toDouble(), y.toDouble())
}

/** Image edges suggest topology only. A user must explicitly mark a guide as a fold. */
internal enum class InteriorGuidePurpose { TOPOLOGY, FOLD }

internal data class InteriorGuide(
    val id: String,
    val points: List<InteriorPoint>,
    val purpose: InteriorGuidePurpose = InteriorGuidePurpose.TOPOLOGY,
    /** Fold support radius in source pixels, not screen pixels. */
    val radius: Float = 16f,
    val amount: Float = 1f,
    /** A longitudinal-only crease, as in the reference material binding. */
    val pitchOnly: Boolean = false,
) {
    init {
        require(id.isNotBlank() && id.none(Char::isISOControl)) { "Interior guide needs an ID" }
        require(points.size in 2..256 && points.zipWithNext().all { (a, b) -> a != b }) { "Interior guide needs distinct consecutive points" }
        require(radius.isFinite() && radius > 0 && amount.isFinite() && amount in 0f..2f) { "Invalid fold settings" }
    }
}

/** A private copy of the mesh: callers cannot invalidate a compiled plan by editing their arrays. */
internal class InteriorMesh(positions: FloatArray, indices: IntArray) {
    private val xy = positions.copyOf()
    private val faces = indices.copyOf()
    val vertexCount: Int get() = xy.size / 2
    fun positions() = xy.copyOf()
    fun indices() = faces.copyOf()
    internal val points: List<Point>
    internal val triangles: List<Triangle>
    internal val boundaryEdges: Set<Edge>

    init {
        require(xy.size >= 6 && xy.size % 2 == 0 && xy.all(Float::isFinite)) { "Invalid interior mesh coordinates" }
        require(faces.isNotEmpty() && faces.size % 3 == 0 && faces.all { it in 0 until vertexCount }) { "Invalid interior mesh indices" }
        points = List(vertexCount) { Point(xy[it * 2].toDouble(), xy[it * 2 + 1].toDouble()) }
        triangles = faces.asList().chunked(3).map { Triangle(it[0], it[1], it[2]) }
        require(triangles.all { abs(cross(points[it.a], points[it.b], points[it.c])) > 1e-12 }) { "Degenerate interior triangle" }
        require(triangles.map { listOf(it.a, it.b, it.c).sorted() }.distinct().size == triangles.size) { "Duplicate interior triangle" }
        val edges = triangles.flatMap(::triangleEdges).groupingBy { it }.eachCount()
        require(edges.values.all { it in 1..2 }) { "Non-manifold interior mesh" }
        require(faces.toSet().size == vertexCount) { "Unused interior vertex" }
        boundaryEdges = edges.filterValues { it == 1 }.keys
        require(boundaryEdges.isNotEmpty()) { "Interior mesh must have a boundary" }
    }
}

internal class InteriorRaster(val width: Int, val height: Int, rgba: ByteArray) {
    private val pixels = rgba.copyOf()
    init {
        require(width > 0 && height > 0 && width.toLong() * height * 4 == rgba.size.toLong()) { "Invalid interior raster" }
    }
    fun rgba() = pixels.copyOf()
    internal fun channel(x: Int, y: Int, c: Int): Int = pixels[(y * width + x) * 4 + c].toInt() and 255
    internal fun visible(x: Int, y: Int) = x in 0 until width && y in 0 until height && channel(x, y, 3) != 0
}
