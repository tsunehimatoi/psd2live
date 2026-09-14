package org.umamo.runtime.model

/** Editor-only handles attached to triangles. Motion is baked into ordinary ArtMesh keyforms. */
data class DeformPath(
    val id: String,
    val drawableId: DrawableId,
    val points: List<DeformPathPoint>,
    val width: Float = 0.1f,
    val hardness: Float = 0.5f,
    val closed: Boolean = false,
    val editLevel: Int = 2,
) {
    init {
        require(id.isNotBlank())
        require(points.size in 2..128)
        require(width.isFinite() && width > 0f)
        require(hardness.isFinite() && hardness in 0f..1f)
        require(editLevel in 2..3)
        require(!closed || points.size >= 3)
    }
}

data class DeformPathPoint(
    val a: Int, val b: Int, val c: Int,
    val wa: Float, val wb: Float, val wc: Float,
    val corner: Boolean = false,
) {
    init {
        require(a >= 0 && b >= 0 && c >= 0)
        require(listOf(wa, wb, wc).all(Float::isFinite))
        require(kotlin.math.abs(wa + wb + wc - 1f) < 0.001f)
    }

    fun position(vertices: FloatArray): Pair<Float, Float> {
        require(maxOf(a, b, c) < vertices.size / 2) { "Deform path topology has changed" }
        return Pair(vertices[a*2]*wa + vertices[b*2]*wb + vertices[c*2]*wc,
            vertices[a*2+1]*wa + vertices[b*2+1]*wb + vertices[c*2+1]*wc)
    }
}
