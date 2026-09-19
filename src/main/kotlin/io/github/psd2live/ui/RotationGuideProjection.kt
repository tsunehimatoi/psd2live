package io.github.psd2live.ui

import androidx.compose.ui.geometry.Offset
import kotlin.math.abs

/** Editor-only projection: inherit the parent's direction, but use one scale for every angle. */
internal class RotationGuideProjection(
    private val origin: Offset,
    project: (Offset) -> Offset,
) {
    private val pivot = project(origin)
    private val step = 0.001f
    private val xAxis = (project(origin + Offset(step, 0f)) - project(origin - Offset(step, 0f))) / (2f * step)
    private val yAxis = (project(origin + Offset(0f, step)) - project(origin - Offset(0f, step))) / (2f * step)
    private val scale = ((xAxis.getDistance() + yAxis.getDistance()) * 0.5f).coerceAtLeast(1e-6f)

    fun toScreen(point: Offset): Offset {
        val local = point - origin
        val direction = xAxis * local.x + yAxis * local.y
        val length = direction.getDistance()
        return if (length > 1e-6f) pivot + direction * (local.getDistance() * scale / length) else pivot
    }

    fun toLocal(point: Offset): Offset {
        val screen = point - pivot
        val determinant = xAxis.x * yAxis.y - xAxis.y * yAxis.x
        if (abs(determinant) <= 1e-8f) return origin
        val direction = Offset(
            (screen.x * yAxis.y - screen.y * yAxis.x) / determinant,
            (screen.y * xAxis.x - screen.x * xAxis.y) / determinant,
        )
        val length = direction.getDistance()
        return if (length > 1e-8f) origin + direction * (screen.getDistance() / scale / length) else origin
    }
}
