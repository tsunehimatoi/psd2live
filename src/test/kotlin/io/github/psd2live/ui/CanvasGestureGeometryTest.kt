package io.github.psd2live.ui

import androidx.compose.ui.geometry.Offset
import kotlin.math.*
import kotlin.test.*

class CanvasGestureGeometryTest {
    @Test fun edgeProjectionClampsAndHandlesCollapsedEdges() {
        assertEquals(.5f, CanvasGestureGeometry.project(Offset(5f, 3f), Offset.Zero, Offset(10f, 0f)))
        assertEquals(1f, CanvasGestureGeometry.project(Offset(15f, 3f), Offset.Zero, Offset(10f, 0f)))
        assertEquals(0f, CanvasGestureGeometry.project(Offset(5f, 3f), Offset.Zero, Offset.Zero))
    }
    @Test fun rotationPreservesRadiusAndSnapsAngle() {
        val origin = Offset(20f, -3f)
        val tip = CanvasGestureGeometry.direction(origin, origin + Offset(70f, 42f), 100f, true) - origin
        assertEquals(100f, tip.getDistance(), .001f)
        assertEquals(30f, atan2(tip.y, tip.x) * 180f / PI.toFloat(), .001f)
    }
}
