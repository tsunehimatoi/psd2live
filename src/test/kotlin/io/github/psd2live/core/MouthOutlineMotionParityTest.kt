package io.github.psd2live.core

import io.github.psd2live.algorithms.binding.MouthOutlineMotion
import io.github.psd2live.core.modifier.ModifierSourceBounds
import kotlin.test.Test
import kotlin.test.assertEquals

class MouthOutlineMotionParityTest {
    @Test fun pluginMotionPreservesLegacyPointValuesAcrossCurvesAndTuning() {
        val aperture = Bounds(31.3f, 19.2f, 94.7f, 38.4f)
        for (shape in MouthCurve.presets + "custom") {
            val curve = MouthCurve.preset("w").move(3, .53f, -.13f)
            val effective = if (shape == "custom") curve else MouthCurve.preset(shape)
            for (tuning in listOf(RigTuning(), RigTuning(mouthClosedNarrow = 23f, mouthSmile = 18f, mouthSeam = 63f))) {
                val motion = MouthOutlineMotion(ModifierSourceBounds(aperture.left, aperture.top, aperture.right, aperture.bottom),
                    effective.points.map { it.x to it.y }, MouthOutlineMotion.Tuning(tuning.mouthClosedNarrow,
                        tuning.mouthFormWiden, tuning.mouthSeam, tuning.mouthSmileBase, tuning.mouthSmile))
                for (exact in listOf(false, true)) for (form in listOf(-1f, .23f, 1f)) for (open in listOf(0f, .4f, 1f)) {
                    for (i in 0..10) {
                        val x = aperture.left - 4f + i * 7f
                        val y = aperture.top + i * 2.3f
                        assertEquals(RigBuilder.mouthWholePoint(x, y, aperture, form, open, shape, exact, curve, tuning),
                            motion.point(x, y, form, open, exact))
                    }
                }
            }
        }
    }
}
