package example

import io.github.psd2live.algorithms.binding.MouthColumn
import io.github.psd2live.algorithms.binding.MouthOutlineRaster
import io.github.psd2live.algorithms.binding.MouthOutlineStroke
import io.github.psd2live.core.modifier.ModifierAlgorithmResource
import io.github.psd2live.core.modifier.ModifierSourceBounds
import io.github.psd2live.core.modifier.ModifierSourceInputs
import io.github.psd2live.core.modifier.ModifierSourceLayer
import io.github.psd2live.core.modifier.ModifierSourceTransform
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MouthOutlineStrokeTest {
    @Test
    fun `traced mouth pixels produce upper and lower artwork without model input`() {
        val layer = ModifierSourceLayer("mouth", "MOUTH_OPEN", "CENTER", left = 3f, top = 2f,
            width = 24, height = 4, rgba = "pixels", bounds = ModifierSourceBounds(3f, 2f, 27f, 6f),
            centroidX = 15f, centroidY = 4f, opaquePixels = 96)
        val pixel = byteArrayOf(0x51, 0x37, 0x2a, -1)
        val bytes = ByteArray(24 * 4 * 4) { pixel[it % 4] }
        val sources = ModifierSourceInputs(listOf(layer), mapOf("pixels" to ModifierAlgorithmResource(bytes)))
        val positions = floatArrayOf(3f, 2f, 27f, 2f, 27f, 6f, 3f, 6f)
        val triangles = intArrayOf(0, 1, 2, 0, 2, 3)
        val transform = ModifierSourceTransform(tx = 10f, ty = 20f)
        val outlines = MouthOutlineRaster.fromSource(sources.raster(layer), positions, triangles, transform, 64, 1.5f)

        assertEquals(2, outlines.size)
        outlines.forEach { art ->
            assertEquals(art.width * art.height * 4, art.rgba.size)
            val solid = (3 until art.rgba.size step 4).first { (art.rgba[it].toInt() and 255) == 255 }
            assertContentEquals(pixel, art.rgba.copyOfRange(solid - 3, solid + 1))
        }
    }

    @Test
    fun `posed ribbon preserves topology and samples its own artwork`() {
        val columns = List(25) { i ->
            val x = i + 3f
            MouthColumn(x to 2f, x to 6f)
        }
        val thickness = 1.5f
        val toCanvas = ModifierSourceTransform(tx = 10f, ty = 20f)
        val art = MouthOutlineRaster.render(columns, 0, 0x51372a, thickness, toCanvas::map)
        val stroke = MouthOutlineStroke(columns, 0, thickness)
        val frame = ModifierSourceBounds(0f, 0f, 32f, 16f)
        val rest = stroke.parentPositions(frame)
        val indices = stroke.indices()
        val uv = stroke.texturePixels(art, toCanvas)

        assertEquals(rest.size, uv.size)
        assertTrue(indices.all { it in 0 until rest.size / 2 })
        assertContentEquals(FloatArray(rest.size), stroke.delta(frame, rest) { x, y -> x to y })
        val shifted = stroke.delta(frame, rest) { x, y -> x + 2f to y }
        for (i in shifted.indices step 2) {
            assertEquals(2f / 32f, shifted[i], 1e-6f)
            assertEquals(0f, shifted[i + 1], 1e-6f)
            assertTrue(uv[i] in 0f..art.width.toFloat() && uv[i + 1] in 0f..art.height.toFloat())
        }
        val handles = stroke.handles(frame)
        assertEquals(9, handles.size)
        assertEquals(listOf(0, 8), handles.indices.filter { handles[it].corner })
    }
}
