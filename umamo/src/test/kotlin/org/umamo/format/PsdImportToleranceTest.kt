package org.umamo.format

import org.umamo.format.psd.PsdReader
import org.umamo.format.psd.PsdWriter
import org.umamo.format.psd.PsdLayerRecords
import org.umamo.format.art.SourceLayer
import org.umamo.format.art.LayerId
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.concurrent.CancellationException
import java.util.zip.DeflaterOutputStream
import kotlin.test.*

class PsdImportToleranceTest {
    private fun bytes(block: DataOutputStream.() -> Unit) = ByteArrayOutputStream().also {
        DataOutputStream(it).use(block)
    }.toByteArray()

    private fun flattened(compression: Int, depth: Int = 8, emptyInfo: Boolean = false): ByteArray = bytes {
        writeBytes("8BPS"); writeShort(1); write(ByteArray(6)); writeShort(3)
        writeInt(1); writeInt(2); writeShort(depth); writeShort(3)
        writeInt(0); writeInt(0)
        if (emptyInfo) { writeInt(4); writeInt(0) } else writeInt(0)
        writeShort(compression)
        val samples = if (depth == 16) byteArrayOf(10, 0, 20, 0, 30, 0, 40, 0, 50, 0, 60, 0)
            else if (compression == 3) byteArrayOf(10, 10, 30, 10, 50, 10) else byteArrayOf(10, 20, 30, 40, 50, 60)
        when (compression) {
            0 -> write(samples)
            1 -> {
                repeat(3) { writeShort(3) }
                repeat(3) { writeByte(1); write(samples, it * 2, 2) }
            }
            else -> write(ByteArrayOutputStream().also { out -> DeflaterOutputStream(out).use { it.write(samples) } }.toByteArray())
        }
    }

    @Test fun flattenedRawRleZipAndPredictionPreservePixels() {
        for (compression in 0..3) for (emptyInfo in listOf(false, true)) {
            val art = PsdReader.read(flattened(compression, emptyInfo = emptyInfo))
            assertEquals(2, art.widthPx)
            assertContentEquals(byteArrayOf(10, 30, 50, -1, 20, 40, 60, -1), art.layers.single().raster.rgba)
            assertTrue(art.warnings.any { "merged image" in it })
        }
    }

    @Test fun flattenedSixteenBitKeepsHighBytes() {
        for (compression in listOf(0, 2)) {
            val art = PsdReader.read(flattened(compression, 16))
            assertContentEquals(byteArrayOf(10, 30, 50, -1, 20, 40, 60, -1), art.layers.single().raster.rgba)
        }
    }

    @Test fun cancellationIsNotConvertedIntoAnEmptyImport() {
        try {
            Thread.currentThread().interrupt()
            assertFailsWith<CancellationException> { PsdReader.read(flattened(0)) }
        } finally { Thread.interrupted() }
    }

    @Test fun invalidCanvasFailsBeforeAllocatingPixels() {
        val data = flattened(0)
        for (i in 18..21) data[i] = -1
        assertFailsWith<IllegalArgumentException> { PsdReader.read(data) }
    }

    @Test fun oneBrokenLayerDoesNotDiscardHealthyLayers() {
        val layer = PsdReader.read(flattened(0)).layers.single()
        val second = object : SourceLayer by layer {
            override val id = LayerId("second")
            override val name = "second"
        }
        val data = PsdWriter.write(2, 1, listOf(layer, second))
        val record = PsdLayerRecords.parse(data).records.first { it.name == "second" }
        data[record.channelDataOffset] = 0
        data[record.channelDataOffset + 1] = 99
        val art = PsdReader.read(data)
        assertEquals(2, art.layers.size)
        assertContentEquals(layer.raster.rgba, art.layers.first { it.name == layer.name }.raster.rgba)
        assertEquals(0, art.layers.first { it.name == "second" }.raster.rgba.size)
        assertTrue(art.warnings.any { "second" in it && "could not be decoded" in it })
    }

    @Test fun repeatedSiblingFoldersKeepSeparateIdentities() {
        val layer = PsdReader.read(flattened(0)).layers.single()
        val layers = listOf("A", "B", "A").mapIndexed { index, path -> object : SourceLayer by layer {
            override val id = LayerId("layer$index")
            override val groupPath = path
        } }
        val art = PsdReader.read(PsdWriter.write(2, 1, layers))
        assertEquals(3, art.groups.size)
        assertEquals(3, art.groups.map { it.path }.distinct().size)
        assertNotEquals(art.layers.first().groupPath, art.layers.last().groupPath)
        assertTrue(art.layers.all { layer -> art.groups.any { it.path == layer.groupPath } })
    }
}
