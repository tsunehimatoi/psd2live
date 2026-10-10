package io.github.psd2live.core

import io.github.psd2live.project.*
import org.umamo.format.art.*
import org.umamo.format.psd.PsdReader
import org.umamo.format.psd.PsdWriter
import kotlin.test.*

class PsdExportDensityTest {
    /** A 4x4 canvas rectangle at (2, 2) holding an 8x8 raster: its first three columns opaque red, the rest transparent black. */
    private val dense = run {
        val bounds = LayerBounds(2, 2, 4, 4)
        val rgba = ByteArray(8 * 8 * 4)
        for (y in 0 until 8) for (x in 0 until 3) {
            val o = (y * 8 + x) * 4
            rgba[o] = -1; rgba[o + 3] = -1
        }
        WorkspaceSourceLayer(LayerId("dense"), "Dense", "", SourceLayerKind.Raster, true, 0, bounds, 1f, false,
            LayerBlend.Normal, ChannelMask.ALL, LayerRaster(8, 8, rgba), null, null, false)
    }

    @Test fun aDenseLayerIsFittedToItsCanvasRectangle() {
        val layer = PsdReader.read(PsdWriter.write(10, 10, listOf(dense), fitToBounds = true)).layers.single()
        assertEquals(LayerBounds(2, 2, 4, 4), layer.bounds)
        assertEquals(4, layer.raster.width)
        val rgba = layer.raster.rgba
        assertEquals(255, rgba[0].toInt() and 0xFF)
        assertEquals(255, rgba[3].toInt() and 0xFF)
        // The second pixel covers one red and one transparent black column: half covered, and still pure red.
        assertEquals(255, rgba[4].toInt() and 0xFF)
        assertEquals(128, rgba[7].toInt() and 0xFF)
        assertEquals(0, rgba[(2 * 4) + 3].toInt() and 0xFF)
    }

    @Test fun atTheLayersOwnDensityItsPixelsAreKept() {
        val layer = PsdReader.read(PsdWriter.write(10, 10, listOf(dense), scale = 2, fitToBounds = true)).layers.single()
        assertEquals(LayerBounds(4, 4, 8, 8), layer.bounds)
        assertContentEquals(dense.raster.rgba, layer.raster.rgba)
    }

    @Test fun withoutFittingTheRasterIsWrittenAsStored() {
        val layer = PsdReader.read(PsdWriter.write(10, 10, listOf(dense))).layers.single()
        assertContentEquals(dense.raster.rgba, layer.raster.rgba)
    }
}
