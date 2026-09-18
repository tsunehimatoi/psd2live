package io.github.psd2live.ui

import androidx.compose.ui.geometry.Offset
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BrushDeformTest {

    private fun assertClose(expected: Offset, actual: Offset, tolerance: Float = 1e-3f) {
        assertTrue(
            abs(expected.x - actual.x) <= tolerance && abs(expected.y - actual.y) <= tolerance,
            "expected $expected, got $actual"
        )
    }

    @Test
    fun testBrushWeightDisplacementProportionality() {
        val pressCenter = Offset(200f, 200f)
        val radius = 100f
        val hardness = 0.4f
        val strength = 1.0f

        val initialPoints = listOf(
            Offset(200f, 200f), // Center (weight = 1.0)
            Offset(230f, 200f), // Inside core (dist 30 <= 40, weight = 1.0)
            Offset(270f, 200f), // In falloff (dist 70)
            Offset(350f, 200f), // Outside radius (dist 150 > 100, weight = 0.0)
        )

        // Compute weights at press time
        val weights = initialPoints.map { p ->
            computeBrushWeight(p, pressCenter, pressCenter, radius, hardness, BrushShape.CIRCLE) * strength
        }

        assertEquals(1.0f, weights[0], 1e-3f)
        assertEquals(1.0f, weights[1], 1e-3f)
        assertTrue(weights[2] in 0.01f..0.99f, "weight[2] should be in falloff range: ${weights[2]}")
        assertEquals(0.0f, weights[3], 1e-4f)

        // Simulate dragging to pos = (250, 220) -> totalDelta = (50, 20)
        val currentPos = Offset(250f, 220f)
        val totalDelta = currentPos - pressCenter

        val deformedPoints = initialPoints.mapIndexed { i, p0 ->
            p0 + totalDelta * weights[i]
        }

        // Center point moves 1:1 with drag
        assertClose(initialPoints[0] + totalDelta, deformedPoints[0])
        // Core point moves 1:1 with drag
        assertClose(initialPoints[1] + totalDelta, deformedPoints[1])
        // Falloff point moves proportional to weight
        assertClose(initialPoints[2] + totalDelta * weights[2], deformedPoints[2])
        // Outside point remains completely stationary
        assertClose(initialPoints[3], deformedPoints[3])
    }

    @Test
    fun testReturnToStartRestoresOriginalMeshWithoutDrift() {
        val pressCenter = Offset(150f, 150f)
        val radius = 80f
        val hardness = 0.3f
        val strength = 0.8f

        val initialPoints = listOf(
            Offset(150f, 150f),
            Offset(170f, 160f),
            Offset(190f, 180f),
            Offset(300f, 300f)
        )

        val weights = initialPoints.map { p ->
            computeBrushWeight(p, pressCenter, pressCenter, radius, hardness, BrushShape.CIRCLE) * strength
        }

        // 1. Drag far away to (300, 400)
        val dragFarPos = Offset(300f, 400f)
        val deltaFar = dragFarPos - pressCenter
        val pointsFar = initialPoints.mapIndexed { i, p0 -> p0 + deltaFar * weights[i] }
        assertTrue((pointsFar[0] - initialPoints[0]).getDistance() > 100f)

        // 2. Drag back to press center (150, 150)
        val returnPos = pressCenter
        val deltaReturn = returnPos - pressCenter
        val restoredPoints = initialPoints.mapIndexed { i, p0 -> p0 + deltaReturn * weights[i] }

        // All points must exactly match initial positions with 0 drift
        for (i in initialPoints.indices) {
            assertClose(initialPoints[i], restoredPoints[i], 1e-4f)
        }
    }

    @Test
    fun testNonAffectedVerticesHaveZeroWeightAndStayStationary() {
        val pressCenter = Offset(50f, 50f)
        val radius = 30f
        val hardness = 0.5f

        val farPoints = listOf(
            Offset(200f, 200f),
            Offset(500f, 100f),
            Offset(80f, 90f) // dist = hypot(30, 40) = 50 > 30
        )

        for (p0 in farPoints) {
            val w = computeBrushWeight(p0, pressCenter, pressCenter, radius, hardness, BrushShape.CIRCLE)
            assertEquals(0.0f, w, 1e-4f)
            val delta = Offset(100f, -50f)
            val deformed = p0 + delta * w
            assertClose(p0, deformed, 1e-4f)
        }
    }

    @Test
    fun testSelectedVerticesFilterRespectsSelection() {
        val pressCenter = Offset(100f, 100f)
        val radius = 50f
        val hardness = 0.5f
        val strength = 1.0f

        val points = listOf(
            Offset(100f, 100f), // index 0: center
            Offset(110f, 100f), // index 1: inside
        )

        // Suppose user only selected index 1
        val selectedVertices = setOf(1)
        val weights = points.mapIndexed { i, p ->
            if (selectedVertices.isNotEmpty() && i !in selectedVertices) 0f
            else computeBrushWeight(p, pressCenter, pressCenter, radius, hardness, BrushShape.CIRCLE) * strength
        }

        assertEquals(0.0f, weights[0], 1e-4f, "Unselected vertex 0 must have 0 weight")
        assertEquals(1.0f, weights[1], 1e-3f, "Selected vertex 1 must have full weight")
    }
}

