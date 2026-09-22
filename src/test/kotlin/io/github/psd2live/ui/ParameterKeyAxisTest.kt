package io.github.psd2live.ui

import io.github.psd2live.ui.views.formatAxisValue
import io.github.psd2live.ui.views.parameterKeyDestination
import org.umamo.runtime.eval.EPS_SPAN
import kotlin.test.*

class ParameterKeyAxisTest {
    @Test fun dragClampsToRangeAndDoesNotCrossNeighbours() {
        val keys = listOf(-1f, 0f, 1f)
        assertEquals(-1f, parameterKeyDestination(keys, -1f, -4f, -1f, 1f))
        assertEquals(1f, parameterKeyDestination(keys, 1f, 4f, -1f, 1f))
        assertTrue(parameterKeyDestination(keys, 0f, 1f, -1f, 1f) <= 1f - EPS_SPAN)
        assertTrue(parameterKeyDestination(keys, 0f, -1f, -1f, 1f) >= -1f + EPS_SPAN)
        assertEquals(0.3f, parameterKeyDestination(keys, 0f, 0.3f, -1f, 1f))
    }
    @Test fun labelsTrimBinaryNoiseWithoutChangingIntegers() {
        assertEquals("0", formatAxisValue(0f))
        assertEquals("1", formatAxisValue(1f))
        assertEquals("-1", formatAxisValue(-1f))
        assertEquals("0.3", formatAxisValue(0.30000004f))
        assertEquals("—", formatAxisValue(Float.NaN))
    }
    @Test fun staleSelectionAndInvalidCoordinatesAreNoOps() {
        assertEquals(0f, parameterKeyDestination(listOf(-1f, 1f), 0f, 0.5f, -1f, 1f))
        assertEquals(0f, parameterKeyDestination(listOf(-1f, 0f, 1f), 0f, Float.NaN, -1f, 1f))
        assertEquals(0f, parameterKeyDestination(listOf(-1f, 0f, 1f), 0f, 0.5f, 1f, -1f))
    }
}
