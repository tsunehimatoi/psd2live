package io.github.psd2live.core

import org.umamo.runtime.model.*
import kotlin.test.*

class ParameterKeyPoseTest {
    private val p = ParameterId("mouth")
    private fun axis(vararg keys: Float) = KeyformAxis(p, keys)

    @Test fun sharedTargetsSnapOnceToTheNearestCommonKeyRegardlessOfOrder() {
        val axes = listOf(axis(0f, .15f, 1f), axis(0f, .5f, 1f))
        for (ordered in listOf(axes, axes.reversed())) {
            val next = nearestKeyPose(ordered, mapOf(p to .2f), emptyMap())
            assertEquals(mapOf(p to 0f), next)
            assertTrue(nearestKeyPose(ordered, next, emptyMap()).isEmpty())
        }
    }

    @Test fun disjointTracksRefuseWithoutChoosingOneTargetsPose() {
        assertFailsWith<IllegalArgumentException> {
            nearestKeyPose(listOf(axis(0f, 1f), axis(.15f, .5f)), mapOf(p to .2f), emptyMap())
        }
    }

    @Test fun alreadySharedKeyDoesNotMoveAndTiesChooseLowerValue() {
        assertTrue(nearestKeyPose(listOf(axis(0f, 1f)), mapOf(p to 1f), emptyMap()).isEmpty())
        assertEquals(mapOf(p to 0f), nearestKeyPose(listOf(axis(0f, 1f)), mapOf(p to .5f), emptyMap()))
    }
}
