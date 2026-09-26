package io.github.psd2live.ui

import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MotionTimelineGeometryTest {
    @Test fun viewportMapsTimeAndZoomsAroundTheAnchor() {
        val viewport = TimelineViewport(startTime = 0f, pxPerSecond = 100f)
        assertEquals(150f, viewport.x(1.5f))
        assertEquals(1.5f, viewport.time(150f))
        val zoomed = viewport.zoomed(2f, anchorX = 150f)
        assertEquals(1.5f, zoomed.time(150f), 1e-4f)
        assertEquals(200f, zoomed.pxPerSecond)
    }

    @Test fun fitShowsTheWholeDuration() {
        val viewport = TimelineViewport.fit(duration = 4f, width = 432f, margin = 16f)
        assertEquals(16f, viewport.x(0f), 1e-3f)
        assertEquals(416f, viewport.x(4f), 1e-3f)
    }

    @Test fun rulerStepsKeepLabelsApart() {
        assertEquals(0.5f, MotionTimelineGeometry.majorStep(pxPerSecond = 150f, minSpacing = 64f))
        assertEquals(listOf(0f, 0.5f, 1f), MotionTimelineGeometry.ticks(-0.2f, 1.2f, 0.5f))
    }

    @Test fun snapAndHitTesting() {
        assertEquals(0.5f, MotionTimelineGeometry.snap(0.51f, fps = 30f, snap = true), 1e-4f)
        assertEquals(0.51f, MotionTimelineGeometry.snap(0.51f, fps = 30f, snap = false))
        val points = listOf("a" to Offset(10f, 10f), "b" to Offset(30f, 10f))
        assertEquals("b", MotionTimelineGeometry.hit(points, Offset(27f, 12f), radius = 6f))
        assertNull(MotionTimelineGeometry.hit(points, Offset(20f, 30f), radius = 6f))
        assertEquals(setOf("a"), MotionTimelineGeometry.boxSelect(points, Offset(0f, 0f), Offset(20f, 20f)))
    }

    @Test fun valuesNormalizeAgainstTheParameterRange() {
        assertEquals(0.75f, MotionTimelineGeometry.normalize(15f, -30f..30f))
        assertEquals(15f, MotionTimelineGeometry.denormalize(0.75f, -30f..30f))
        val values = ValueViewport(0f, 1f)
        assertEquals(0.25f, values.normalized(values.y(0.25f, 10f, 200f), 10f, 200f), 1e-5f)
    }
}
