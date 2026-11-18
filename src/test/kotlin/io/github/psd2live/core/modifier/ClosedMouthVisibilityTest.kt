package io.github.psd2live.core.modifier

import kotlinx.serialization.json.*
import kotlin.test.*

class ClosedMouthVisibilityTest {
    @Test fun ordinaryChannelKeyOperationsDefineTheCurve() {
        val default = ClosedMouthVisibility.Default
        assertEquals(.5f, default.sample(.025f), .00001f)
        assertEquals(0f, default.sample(.05f))
        assertEquals(0f, default.sample(1f))
        val curve = default.move(.05f, .25f).insert(.2f).setOpacity(.2f, 1f)
        assertEquals(1f, curve.sample(.2f))
        assertEquals(.5f, curve.sample(.225f), .00001f)
        assertEquals(0f, curve.sample(.25f))
        assertEquals(curve, curve.insert(.5f).remove(.5f))
        assertEquals(curve, ClosedMouthVisibility.fromConfig(buildJsonObject { put(ClosedMouthVisibility.CONFIG, curve.toJson()) }))
        assertNull(ClosedMouthVisibility.fromConfig(JsonObject(emptyMap())))
        assertFailsWith<IllegalArgumentException> { curve.move(.2f, .25f) }
        assertFailsWith<IllegalArgumentException> { curve.setOpacity(.2f, 1.1f) }
        assertFailsWith<IllegalArgumentException> { default.remove(.05f).remove(0f) }
        assertFailsWith<IllegalArgumentException> { ClosedMouthVisibility(listOf(ClosedMouthVisibility.Point(0f, 1f), ClosedMouthVisibility.Point(Float.NaN, 0f))) }
    }
}
