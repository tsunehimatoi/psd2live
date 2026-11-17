package org.umamo.render.eval

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DrawableSpaceMappingPrecisionTest {
    @Test fun absoluteProjectionPrecisionDoesNotScaleWithCanvasExtent() {
        for (extent in listOf(100f, 10_000f, 100_000f)) {
            val mapping = DrawableSpaceMapping(WarpWorld(floatArrayOf(
                0f, 0f, extent, 0f, 0f, extent, extent, extent), 1, 1, true, 0f))
            val seed = floatArrayOf(.5f, .5f, .25f, .25f)
            val rest = mapping.localToWorld(seed)
            val target = rest.copyOf().also { it[0] += .25f; it[1] -= .25f }
            val local = mapping.worldToLocalLinearized(target, seed, rest, setOf(0), worldTolerance = .01f)
            val reached = mapping.localToWorld(local)
            assertTrue(abs(reached[0] - target[0]) <= .02f)
            assertTrue(abs(reached[1] - target[1]) <= .02f)
            assertContentEquals(seed.copyOfRange(2, 4), local.copyOfRange(2, 4))
            if (extent == 100_000f) {
                val coarse = mapping.localToWorld(mapping.worldToLocalLinearized(target, seed, rest, setOf(0)))
                assertTrue(abs(coarse[0] - target[0]) > .05f,
                    "The fixture must expose the mismatch between relative inverse and absolute projection tolerances")
            }
        }
    }

    @Test fun unreachableDegenerateCagesKeepFiniteSeedsAndInvalidTolerancesAreRejected() {
        val mapping = DrawableSpaceMapping(WarpWorld(FloatArray(8), 1, 1, true, 0f))
        val seed = floatArrayOf(.5f, .5f)
        val target = floatArrayOf(10f, 10f)
        assertContentEquals(seed, mapping.worldToLocalLinearized(target, seed, FloatArray(2), setOf(0), worldTolerance = .01f))
        for (invalid in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> {
                mapping.worldToLocalLinearized(target, seed, FloatArray(2), setOf(0), worldTolerance = invalid)
            }
        }
    }
}
