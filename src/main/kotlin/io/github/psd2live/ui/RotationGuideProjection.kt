package io.github.psd2live.ui

import androidx.compose.ui.geometry.Offset
import org.umamo.render.eval.DrawableSpaceMapping
import kotlin.math.abs

/** Shared rigid frame for creation, selection, overview and pointer inversion. */
internal class RotationGuideProjection(
    private val origin: Offset,
    mapping: DrawableSpaceMapping,
    projectWorld: (Float, Float) -> Offset,
) {
    // Geometry encodes angle zero as +X; Cubism's editor needle points along -Y.
    // Apply that quarter-turn in the parent frame, before camera projection. Keeping
    // it in this shared basis also gives pointer inversion the exact inverse turn.
    private val basis = mapping.rotationFrame(origin.x, origin.y)
        .localToWorld(floatArrayOf(0f, 0f, 0f, -1f, 1f, 0f))
    private val pivot = projectWorld(basis[0], basis[1])
    private val xAxis = projectWorld(basis[2], basis[3]) - pivot
    private val yAxis = projectWorld(basis[4], basis[5]) - pivot

    fun toScreen(point: Offset): Offset {
        val local = point - origin
        return pivot + xAxis * local.x + yAxis * local.y
    }

    fun toLocal(point: Offset): Offset {
        val screen = point - pivot
        val determinant = xAxis.x * yAxis.y - xAxis.y * yAxis.x
        if (abs(determinant) <= 1e-8f) return origin
        return origin + Offset(
            (screen.x * yAxis.y - screen.y * yAxis.x) / determinant,
            (screen.y * xAxis.x - screen.x * xAxis.y) / determinant,
        )
    }
}
