package example

import io.github.psd2live.algorithms.binding.*
import io.github.psd2live.core.modifier.*
import kotlin.math.abs
import kotlin.test.*

/** Real source fitting with no product layer types or generation callbacks. */
class EyeSourceInputsTest {
    @Test fun capturedPixelsProduceTheSameEyeAfterContextTransport() {
        val width = 80; val height = 60
        val pixels = ByteArray(width * height * 4)
        var opaque = 0
        for (y in 0 until height) for (x in 0 until width) {
            val dx = (x + .5f - 40f) / 30f; val dy = (y + .5f - 30f) / 14f
            if (dx * dx + dy * dy <= 1f) { pixels[(y * width + x) * 4 + 3] = -1; opaque++ }
        }
        val layer = ModifierSourceLayer("white", "EYEWHITE", "LEFT", left = 100f, top = 50f,
            width = width, height = height, rgba = "white-pixels", bounds = ModifierSourceBounds(110f, 66f, 170f, 94f),
            centroidX = 140f, centroidY = 80f, opaquePixels = opaque)
        val input = ModifierSourceInputs(listOf(layer), mapOf(layer.rgba to ModifierAlgorithmResource(pixels)), mapOf("eye" to "white"))
        val original = EyeFitting.fit(input, 60f)
        val restored = EyeFitting.fit(ModifierSourceInputs.from(input.context()), 60f)
        assertEquals(original.fits, restored.fits)
        assertEquals(original.reasons, restored.reasons)
        val fit = assertNotNull(restored.fits[Side.LEFT])
        assertTrue(abs(fit.originX - 110.5f) < 2f && abs(fit.width - 59f) < 3f)
        assertEquals(setOf("white"), fit.openingLayers)
        val rig = EyeRig(fit, .25f, 1f, .6f)
        assertEquals(0f to 0f, rig.delta(EyeRig.EyeMesh.WHITE, 140f, 70f, 1f))
    }
}
