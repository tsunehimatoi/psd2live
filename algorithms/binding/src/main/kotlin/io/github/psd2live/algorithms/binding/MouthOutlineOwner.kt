package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.ModifierSourceBounds
import org.umamo.runtime.model.DeformPath
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import java.util.UUID

/** Source-space fill geometry and the two paths attached to its shared contour columns. */
object MouthOutlineOwner {
    fun mesh(
        contour: MouthContour.Contour,
        frame: ModifierSourceBounds,
        sourceLeft: Float,
        sourceTop: Float,
        width: Float,
        height: Float,
        toCanvas: (Float, Float) -> Pair<Float, Float>,
    ): DrawableMesh {
        val points = contour.positions
        val positions = FloatArray(points.size)
        val uvs = FloatArray(points.size)
        for (i in points.indices step 2) {
            val x = points[i]
            val y = points[i + 1]
            positions[i] = (x - frame.left) / (frame.right - frame.left).coerceAtLeast(1e-4f)
            positions[i + 1] = (y - frame.top) / (frame.bottom - frame.top).coerceAtLeast(1e-4f)
            val (canvasX, canvasY) = toCanvas(x, y)
            uvs[i] = (canvasX - sourceLeft).coerceIn(0f, width)
            uvs[i + 1] = (canvasY - sourceTop).coerceIn(0f, height)
        }
        return DrawableMesh(positions, uvs, contour.triangles)
    }

    fun paths(owner: DrawableId, mesh: DrawableMesh): List<DeformPath> {
        val lastColumn = MouthContour.DEFAULT_SEGMENTS
        if (mesh.vertexCount < (lastColumn + 1) * 3) return emptyList()
        val columns = (0..8).map { (it * lastColumn + 4) / 8 }
        val paths = listOf("upper" to 0, "lower" to 2).map { (role, row) ->
            val points = columns.mapNotNull { column ->
                val i = (column * 3 + row) * 2
                try {
                    BindingPaths.bind(mesh.positions, mesh.indices, mesh.positions[i], mesh.positions[i + 1],
                        corner = column == 0 || column == lastColumn)
                } catch (_: IllegalArgumentException) {
                    null
                }
            }
            if (points.size < 2) return emptyList()
            DeformPath(pathId(owner, role), owner, points, DeformPath.DEFAULT_WIDTH, DeformPath.DEFAULT_HARDNESS,
                closed = false, editLevel = 2)
        }
        return paths
    }

    fun pathId(owner: DrawableId, role: String): String =
        UUID.nameUUIDFromBytes("psd2live:generated-path:${owner.raw}:$role".encodeToByteArray()).toString()
}
