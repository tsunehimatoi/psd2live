package io.github.psd2live.tools

import io.github.psd2live.util.PresentationCamera
import kotlin.test.*

class WindowPixelAuditTest {
    private val camera = PresentationCamera(64, 64, 1f, 0f, 0f)

    @Test fun verifiesTranslatedViewportAndRejectsPreviousRevisionPixels() {
        fun pixel(ordinal: Int, x: Int, y: Int) = WindowPixelAudit.color(ordinal,
            (if (x >= 132) 1 else 0) + (if (y >= 82) 2 else 0))
        assertEquals(4, WindowPixelAudit.verify(2, camera, 100f, 50f, 400, 300) { x, y -> pixel(2, x, y) }.size)
        assertFailsWith<IllegalStateException> {
            WindowPixelAudit.verify(2, camera, 100f, 50f, 400, 300) { x, y -> pixel(1, x, y) }
        }
    }

    @Test fun rejectsClippedPatchesBeforeReadingOutsideTheWindow() {
        assertFailsWith<IllegalStateException> {
            WindowPixelAudit.verify(1, camera, -20f, 0f, 64, 64) { _, _ -> throw AssertionError("Must reject clipping first") }
        }
        assertFailsWith<IllegalStateException> {
            WindowPixelAudit.verify(1, camera.copy(offsetX = 3f), 0f, 0f, 400, 300) { _, _ -> throw AssertionError("Must reject viewport clipping first") }
        }
    }

    @Test fun rejectsOverlayOrWrongViewportCoordinates() {
        assertFailsWith<IllegalStateException> {
            WindowPixelAudit.verify(1, camera, 100f, 50f, 400, 300) { _, _ -> 0xff222222.toInt() }
        }
    }
}
