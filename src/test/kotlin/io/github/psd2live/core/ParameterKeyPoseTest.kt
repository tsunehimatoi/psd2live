package io.github.psd2live.core

import org.umamo.runtime.model.*
import kotlin.test.*

class ParameterKeyPoseTest {
    private val p = ParameterId("mouth")
    private val q = ParameterId("form")
    private fun axis(vararg keys: Float, id: ParameterId = p) = KeyformAxis(id, keys)

    @Test fun tracksSnapOnceToNearestKeyRegardlessOfOrder() {
        val axes = listOf(axis(0f, .15f, 1f), axis(0f, .5f, 1f))
        for (ordered in listOf(axes, axes.reversed())) {
            val fromPointTwo = nearestKeyPose(ordered, mapOf(p to .2f), emptyMap())
            assertEquals(mapOf(p to .15f), fromPointTwo)
            assertTrue(nearestKeyPose(ordered, fromPointTwo, emptyMap()).isEmpty())

            val fromPointFourFive = nearestKeyPose(ordered, mapOf(p to .45f), emptyMap())
            assertEquals(mapOf(p to .5f), fromPointFourFive)
            assertTrue(nearestKeyPose(ordered, fromPointFourFive, emptyMap()).isEmpty())
        }
    }

    @Test fun disjointTracksSnapToNearestKeyWithoutBouncing() {
        val axes = listOf(axis(0f, 1f), axis(.15f, .5f))
        val next = nearestKeyPose(axes, mapOf(p to .2f), emptyMap())
        assertEquals(mapOf(p to .15f), next)
        assertTrue(nearestKeyPose(axes, next, emptyMap()).isEmpty())
    }

    @Test fun multipleParametersEachSnapToNearestKey() {
        val axes = listOf(axis(0f, .15f, 1f), axis(0f, .5f, 1f), axis(-1f, 0f, 1f, id = q))
        val next = nearestKeyPose(axes, mapOf(p to .42f, q to -.6f), emptyMap())
        assertEquals(mapOf(p to .5f, q to -1f), next)
        assertTrue(nearestKeyPose(axes, next, emptyMap()).isEmpty())
    }

    @Test fun alreadyOnKeyDoesNotMoveAndTiesChooseLowerValue() {
        assertTrue(nearestKeyPose(listOf(axis(0f, 1f)), mapOf(p to 1f), emptyMap()).isEmpty())
        assertEquals(mapOf(p to 0f), nearestKeyPose(listOf(axis(0f, 1f)), mapOf(p to .5f), emptyMap()))
    }
}
