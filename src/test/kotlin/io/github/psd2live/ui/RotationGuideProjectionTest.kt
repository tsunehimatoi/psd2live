package io.github.psd2live.ui

import androidx.compose.ui.geometry.Offset
import org.umamo.render.eval.*
import org.umamo.runtime.model.*
import kotlin.math.*
import kotlin.test.Test
import kotlin.test.assertEquals

class RotationGuideProjectionTest {
    private fun assertPoint(expected: Offset, actual: Offset) {
        assertEquals(expected.x, actual.x, 0.03f)
        assertEquals(expected.y, actual.y, 0.03f)
    }

    @Test
    fun warpSizeDoesNotMultiplyCanvasHandleLengthOrDistortAngles() {
        val parent = WarpWorld(floatArrayOf(0f, 0f, 2000f, 0f, 0f, 500f, 2000f, 500f), 1, 1, true, 1f)
        val origin = Offset(0.5f, 0.5f)
        val projection = RotationGuideProjection(origin, DrawableSpaceMapping(parent)) { x, y -> Offset(x, -y) }
        val pivot = Offset(1000f, 250f)
        assertPoint(pivot, projection.toScreen(origin))
        for (degrees in listOf(0f, 45f, 90f, 180f, -90f)) {
            val radians = degrees * PI.toFloat() / 180f
            val arm = Offset(cos(radians), sin(radians)) * 100f
            val screenArm = Offset(arm.y, -arm.x)
            assertPoint(pivot + screenArm, projection.toScreen(origin + arm))
            assertPoint(origin + arm, projection.toLocal(pivot + screenArm))
        }
    }

    @Test
    fun initialAndSelectedDirectionsMatchRuntimeThroughParentFrames() {
        val parents = listOf(
            null,
            RotationWorld(rotationXform(37f, 2f, false, false, 10f, 20f), 2f),
            RotationWorld(rotationXform(-25f, 1.5f, true, true, 0f, 0f), 1.5f),
            WarpWorld(floatArrayOf(0f, 0f, 900f, 50f, 180f, 400f, 1200f, 650f), 1, 1, true, 2f),
        )
        for (parent in parents) {
            val origin = Offset(0.5f, 0.5f)
            val projection = RotationGuideProjection(origin, DrawableSpaceMapping(parent)) { x, y ->
                Offset(30f + x * 1.75f, 50f - y * 1.75f)
            }
            for (angle in listOf(0f, 30f, -90f, 170f)) {
                val baseAngle = 23f
                val rotation = Deformer.Rotation(
                    DeformerId("child"), "", null, null, baseAngle,
                    KeyformGrid(emptyList(), listOf(KeyformCell(intArrayOf(), RotationPivotForm(origin.x, origin.y, angle, 1.2f)))),
                )
                val world = buildRotationWorld(rotation, { 0f }, { 0f }, parent, emptyMap())!!
                val expected = FloatArray(2)
                world.apply(0f, -100f, expected, 0)
                val radians = (baseAngle + angle) * PI.toFloat() / 180f
                val endpoint = origin + Offset(cos(radians), sin(radians)) * 120f
                val screen = Offset(30f + expected[0] * 1.75f, 50f + expected[1] * 1.75f)
                assertPoint(screen, projection.toScreen(endpoint))
                assertPoint(endpoint, projection.toLocal(screen))
            }
        }
    }

    @Test
    fun cubismZeroPointsUpAndPositiveQuarterTurnPointsRight() {
        val origin = Offset(20f, 30f)
        val projection = RotationGuideProjection(origin, DrawableSpaceMapping(null)) { x, y -> Offset(x, -y) }
        // Explicit editor expectations, independent of the runtime transform implementation.
        assertPoint(Offset(20f, -70f), projection.toScreen(origin + Offset(100f, 0f)))
        assertPoint(Offset(120f, 30f), projection.toScreen(origin + Offset(0f, 100f)))
        assertPoint(Offset(20f, 130f), projection.toScreen(origin + Offset(-100f, 0f)))
        assertPoint(Offset(-80f, 30f), projection.toScreen(origin + Offset(0f, -100f)))
        assertPoint(origin + Offset(100f, 0f), projection.toLocal(Offset(20f, -70f)))
        assertPoint(origin + Offset(0f, 100f), projection.toLocal(Offset(120f, 30f)))
    }
}
