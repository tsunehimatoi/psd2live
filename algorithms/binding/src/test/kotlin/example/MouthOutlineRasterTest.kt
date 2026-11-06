package example

import io.github.psd2live.algorithms.binding.MouthColumn
import io.github.psd2live.algorithms.binding.MouthOutlineRaster
import kotlin.test.*

class MouthOutlineRasterTest {
    private val columns = List(9) { i ->
        val opening = if (i == 0 || i == 8) 0f else 4f
        MouthColumn(i * 4f to -opening, i * 4f to opening)
    }

    @Test fun translatedContoursKeepTheSamePixelsAndReportCanvasOrigin() {
        val plain = MouthOutlineRaster.render(columns, 0, 0x51372a, 2f, { x, y -> x to y })
        val moved = MouthOutlineRaster.render(columns, 0, 0x51372a, 2f, { x, y -> x + 20f to y - 10f })
        assertEquals(plain.left + 20, moved.left)
        assertEquals(plain.top - 10, moved.top)
        assertEquals(plain.width, moved.width)
        assertEquals(plain.height, moved.height)
        assertContentEquals(plain.rgba, moved.rgba)
        assertTrue(plain.opaquePixels > 1)
        assertEquals(plain.opaquePixels, plain.rgba.indices.count { it % 4 == 3 && plain.rgba[it] != 0.toByte() })
    }

    @Test fun perimeterSamplingIgnoresInteriorAndTransparentColors() {
        val rgba = ByteArray(7 * 7 * 4) { 0xff.toByte() }
        for (y in 0..6) for (x in 0..6) {
            val i = (y * 7 + x) * 4
            if (x == 0 || x == 6 || y == 0 || y == 6) rgba[i + 3] = 0
            else if (x == 1 || x == 5 || y == 1 || y == 5) {
                rgba[i] = 0x51; rgba[i + 1] = 0x37; rgba[i + 2] = 0x2a
            }
        }
        assertEquals(0x51372a, MouthOutlineRaster.perimeterColor(7, 7, rgba, 1))
        assertEquals(0, MouthOutlineRaster.perimeterColor(0, 0, byteArrayOf(), 1))
    }

    @Test fun captureCanBeCancelledBeforePublishingPixels() {
        var checkpoints = 0
        assertFailsWith<InterruptedException> {
            MouthOutlineRaster.render(columns, 1, 0, 2f, { x, y -> x to y }) {
                if (++checkpoints == 6) throw InterruptedException()
            }
        }
        assertEquals(6, checkpoints)
    }
}
