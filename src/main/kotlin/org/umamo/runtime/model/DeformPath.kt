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
    val safeWidth: Float get() = width.coerceAtLeast(0f)
    val safeHardness: Float get() = hardness.coerceIn(0f, 1f)

    init {
        require(id.isNotBlank())
        require(points.size in 2..128)
        require(width.isFinite())
        require(hardness.isFinite())
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
        require(listOf(wa, wb, wc).all(Float::isFinite))
        require(kotlin.math.abs(wa + wb + wc - 1f) < 0.001f)
    }

    fun position(vertices: FloatArray): Pair<Float, Float> {
        if (vertices.isEmpty()) return Pair(0f, 0f)
        val maxIndex = vertices.size / 2 - 1
        val safeA = a.coerceAtLeast(0).coerceAtMost(maxIndex)
        val safeB = b.coerceAtLeast(0).coerceAtMost(maxIndex)
        val safeC = c.coerceAtLeast(0).coerceAtMost(maxIndex)
        return Pair(
            vertices[safeA * 2] * wa + vertices[safeB * 2] * wb + vertices[safeC * 2] * wc,
            vertices[safeA * 2 + 1] * wa + vertices[safeB * 2 + 1] * wb + vertices[safeC * 2 + 1] * wc
        )
    }
}
