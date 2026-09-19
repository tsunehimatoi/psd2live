package io.github.psd2live.ui

import java.awt.GraphicsEnvironment
import kotlin.test.Test
import kotlin.test.assertNotNull

class CanvasCursorsTest {
    @Test
    fun cursorsInitializeWithoutException() {
        if (!GraphicsEnvironment.isHeadless()) {
            assertNotNull(CanvasCursors.moveCross)
            assertNotNull(CanvasCursors.rotate)
        }
    }
}

