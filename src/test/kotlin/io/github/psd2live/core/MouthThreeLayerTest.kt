package io.github.psd2live.core

import io.github.psd2live.core.fit.MouthThreeLayerModel
import kotlin.math.abs
import kotlin.test.*

class MouthThreeLayerTest {
    private val model = MouthThreeLayerModel.DEFAULT

    @Test fun normalizedContoursAreFiniteSymmetricAndHaveNoClosedGap() {
        for (open in listOf(0f, 0.25f, 0.5f, 1f)) for (form in listOf(-1f, 0f, 1f)) {
            val p = model.evaluateNormalized(openY = open, form = form)
            assertEquals(model.stations * 4, p.size)
            assertTrue(p.all { it.isFinite() })
            for (i in 0 until model.stations) {
                val j = model.stations - 1 - i
                for (offset in listOf(0, model.stations * 2)) {
                    assertEquals(-p[offset + j * 2], p[offset + i * 2], 2e-4f)
                    assertEquals(p[offset + j * 2 + 1], p[offset + i * 2 + 1], 2e-4f)
                }
            }
        }
        val rest = model.evaluateNormalized()
        val upper = rest.copyOfRange(0, model.stations * 2)
        val lower = rest.copyOfRange(model.stations * 2, rest.size)
        val neutral = model.transferToTargetLocal(rest, upper, lower, true)
        val shut = model.transferToTargetLocal(model.evaluateNormalized(openY = 0f), upper, lower, true)
        for (i in upper.indices) {
            assertEquals(upper[i], neutral.upperLocal[i], 1e-5f)
            assertEquals(lower[i], neutral.lowerLocal[i], 1e-5f)
            assertEquals(shut.upperLocal[i], shut.lowerLocal[i], 1e-5f)
        }
    }

    @Test fun jawCarryDisappearsAsTheMouthOpens() {
        assertEquals(1f, MouthThreeLayerModel.jawCarryWeight(0f))
        assertEquals(0.17f, MouthThreeLayerModel.jawCarryWeight(0.25f), 1e-6f)
        assertEquals(0f, MouthThreeLayerModel.jawCarryWeight(0.55f))
        assertContentEquals(model.evaluateNormalized(openY = 1f), model.evaluateNormalized(openY = 1f, jaw = 1f))
    }

    @Test fun unilateralControlsReflectAcrossTheMouth() {
        for (open in listOf(0f, 0.3f, 1f)) {
            val l = model.evaluateNormalized(openY = open, cornerLUp = 1f)
            val r = model.evaluateNormalized(openY = open, cornerRUp = 1f)
            for (offset in listOf(0, model.stations * 2)) for (i in 0 until model.stations) {
                val j = model.stations - 1 - i
                assertEquals(-l[offset + i * 2], r[offset + j * 2], 2e-4f)
                assertEquals(l[offset + i * 2 + 1], r[offset + j * 2 + 1], 2e-4f)
            }
        }
    }

    @Test fun frenetFramePreservesStrokeThickness() {
        val curve = FloatArray(130) { i -> if (i % 2 == 0) i / 2f else (i / 2f) * 0.5f }
        val (t, n) = MouthThreeLayerModel.computeFrenet(curve, 65)
        for (i in t.indices step 2) {
            assertEquals(1f, t[i]*t[i] + t[i+1]*t[i+1], 1e-6f)
            assertEquals(1f, n[i]*n[i] + n[i+1]*n[i+1], 1e-6f)
            assertTrue(abs(t[i]*n[i] + t[i+1]*n[i+1]) < 1e-6f)
        }
    }
}
