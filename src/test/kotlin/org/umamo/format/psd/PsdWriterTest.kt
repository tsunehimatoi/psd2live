package org.umamo.format.psd

import org.umamo.format.art.LayerBlend
import org.umamo.format.art.LayerBounds
import org.umamo.format.art.LayerId
import org.umamo.format.art.LayerRaster
import org.umamo.format.art.SourceArt
import org.umamo.format.art.SourceGroup
import org.umamo.format.art.SourceLayer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PsdWriterTest {

	private data class TestSourceLayer(
		override val id: LayerId,
		override val name: String,
		override val groupPath: String,
		override val order: Int,
		override val bounds: LayerBounds,
		override val opacity: Float,
		override val clipped: Boolean,
		override val blend: LayerBlend,
		override val visible: Boolean,
		override val raster: LayerRaster,
	) : SourceLayer

	private data class TestSourceGroup(
		override val path: String,
		override val name: String,
		override val visible: Boolean = true,
		override val opacity: Float = 1f,
		override val clipped: Boolean = false,
		override val blend: LayerBlend = LayerBlend.Normal,
		override val passThrough: Boolean = true,
	) : SourceGroup

	private data class TestSourceArt(
		override val widthPx: Int,
		override val heightPx: Int,
		override val layers: List<SourceLayer>,
		override val groups: List<SourceGroup>,
	) : SourceArt

	@Test
	fun testPackBitsEncoding() {
		// Empty
		assertEquals(0, PsdWriter.packBits(ByteArray(0)).size)

		// Run of repeated bytes
		val run = ByteArray(10) { 42 }
		val encodedRun = PsdWriter.packBits(run)
		// Expect control byte (1 - 10 = -9 = 0xF7), then 42
		assertEquals(2, encodedRun.size)
		assertEquals((-9).toByte(), encodedRun[0])
		assertEquals(42.toByte(), encodedRun[1])

		// Literals
		val literals = byteArrayOf(1, 2, 3, 4, 5)
		val encodedLiterals = PsdWriter.packBits(literals)
		// Expect control byte (5 - 1 = 4), then 1, 2, 3, 4, 5
		assertEquals(6, encodedLiterals.size)
		assertEquals(4.toByte(), encodedLiterals[0])
		assertContentEquals(literals, encodedLiterals.copyOfRange(1, 6))

		// Mixed
		val mixed = byteArrayOf(1, 2, 55, 55, 55, 55, 3)
		val encodedMixed = PsdWriter.packBits(mixed)
		assertTrue(encodedMixed.isNotEmpty())
	}

	@Test
	fun testSyntheticRoundTrip() {
		val w = 4
		val h = 4

		fun makeRgba(r: Int, g: Int, b: Int, a: Int): ByteArray {
			val arr = ByteArray(w * h * 4)
			for (i in 0 until w * h) {
				arr[i * 4] = r.toByte()
				arr[i * 4 + 1] = g.toByte()
				arr[i * 4 + 2] = b.toByte()
				arr[i * 4 + 3] = a.toByte()
			}
			return arr
		}

		val layer1 = TestSourceLayer(
			id = LayerId("lyid:101"),
			name = "Background_Base",
			groupPath = "Character",
			order = 0,
			bounds = LayerBounds(left = 0, top = 0, width = w, height = h),
			opacity = 1.0f,
			clipped = false,
			blend = LayerBlend.Normal,
			visible = true,
			raster = LayerRaster(w, h, makeRgba(255, 0, 0, 255)),
		)

		val layer2 = TestSourceLayer(
			id = LayerId("lyid:102"),
			name = "Face_Skin",
			groupPath = "Character/Head",
			order = 1,
			bounds = LayerBounds(left = 1, top = 1, width = 2, height = 2),
			opacity = 0.8f,
			clipped = true,
			blend = LayerBlend.Multiply,
			visible = true,
			raster = LayerRaster(2, 2, byteArrayOf(
				10, 20, 30, 200.toByte(),
				40, 50, 60, 210.toByte(),
				70, 80, 90, 220.toByte(),
				100.toByte(), 110.toByte(), 120.toByte(), 230.toByte(),
			)),
		)

		val layer3 = TestSourceLayer(
			id = LayerId("lyid:103"),
			name = "Eye_Highlight",
			groupPath = "Character/Head",
			order = 2,
			bounds = LayerBounds(left = 2, top = 2, width = 2, height = 1),
			opacity = 0.5f,
			clipped = false,
			blend = LayerBlend.Screen,
			visible = false,
			raster = LayerRaster(2, 1, byteArrayOf(
				200.toByte(), 210.toByte(), 220.toByte(), 255.toByte(),
				230.toByte(), 240.toByte(), 250.toByte(), 255.toByte(),
			)),
		)

		val groups = listOf(
			TestSourceGroup(path = "Character", name = "Character", passThrough = true),
			TestSourceGroup(path = "Character/Head", name = "Head", passThrough = false, blend = LayerBlend.Normal),
		)

		val art = TestSourceArt(
			widthPx = 10,
			heightPx = 10,
			layers = listOf(layer1, layer2, layer3),
			groups = groups,
		)

		val bytes = PsdWriter.write(art)
		assertTrue(bytes.size > 100, "PSD output should have substantial size")

		// Read back with PsdReader
		val parsed = PsdReader.read(bytes)

		assertEquals(10, parsed.widthPx)
		assertEquals(10, parsed.heightPx)
		assertEquals(3, parsed.layers.size)

		// Layer 1
		val r1 = parsed.layers[0]
		assertEquals("Background_Base", r1.name)
		assertEquals("Character", r1.groupPath)
		assertEquals(0, r1.bounds.left)
		assertEquals(0, r1.bounds.top)
		assertEquals(w, r1.bounds.width)
		assertEquals(h, r1.bounds.height)
		assertTrue(kotlin.math.abs(r1.opacity - 1.0f) < 0.01f)
		assertEquals(false, r1.clipped)
		assertEquals(LayerBlend.Normal, r1.blend)
		assertEquals(true, r1.visible)
		assertContentEquals(layer1.raster.rgba, r1.raster.rgba)

		// Layer 2
		val r2 = parsed.layers[1]
		assertEquals("Face_Skin", r2.name)
		assertEquals("Character/Head", r2.groupPath)
		assertEquals(1, r2.bounds.left)
		assertEquals(1, r2.bounds.top)
		assertEquals(2, r2.bounds.width)
		assertEquals(2, r2.bounds.height)
		assertTrue(kotlin.math.abs(r2.opacity - 0.8f) < 0.01f)
		assertEquals(true, r2.clipped)
		assertEquals(LayerBlend.Multiply, r2.blend)
		assertEquals(true, r2.visible)
		assertContentEquals(layer2.raster.rgba, r2.raster.rgba)

		// Layer 3
		val r3 = parsed.layers[2]
		assertEquals("Eye_Highlight", r3.name)
		assertEquals("Character/Head", r3.groupPath)
		assertEquals(2, r3.bounds.left)
		assertEquals(2, r3.bounds.top)
		assertEquals(2, r3.bounds.width)
		assertEquals(1, r3.bounds.height)
		assertTrue(kotlin.math.abs(r3.opacity - 0.5f) < 0.01f)
		assertEquals(false, r3.clipped)
		assertEquals(LayerBlend.Screen, r3.blend)
		assertEquals(false, r3.visible)
		assertContentEquals(layer3.raster.rgba, r3.raster.rgba)

		// Groups
		assertTrue(parsed.groups.any { it.path == "Character" && it.name == "Character" })
		assertTrue(parsed.groups.any { it.path == "Character/Head" && it.name == "Head" })
	}

	@Test
	fun testUpscaledPsdExport() {
		val layer = TestSourceLayer(
			id = LayerId("lyid:1"),
			name = "TestLayer",
			groupPath = "",
			order = 0,
			bounds = LayerBounds(left = 10, top = 20, width = 4, height = 4),
			opacity = 1.0f,
			clipped = false,
			blend = LayerBlend.Normal,
			visible = true,
			raster = LayerRaster(4, 4, ByteArray(4 * 4 * 4) { 100.toByte() }),
		)
		val art = TestSourceArt(widthPx = 100, heightPx = 80, layers = listOf(layer), groups = emptyList())

		val upscaledBytes = PsdWriter.write(
			width = art.widthPx,
			height = art.heightPx,
			layers = art.layers,
			groups = art.groups,
			scale = 2,
		)

		val parsed = PsdReader.read(upscaledBytes)
		assertEquals(200, parsed.widthPx)
		assertEquals(160, parsed.heightPx)
		assertEquals(1, parsed.layers.size)
		val upscaledLayer = parsed.layers[0]
		assertEquals(20, upscaledLayer.bounds.left)
		assertEquals(40, upscaledLayer.bounds.top)
		assertEquals(8, upscaledLayer.bounds.width)
		assertEquals(8, upscaledLayer.bounds.height)
	}

	@Test
	fun testPipelineRoundTripWithGeneratedLayers() {
		val config = io.github.psd2live.core.PipelineConfig(mouthOutlineEnabled = true)
		fun makeLayer(name: String, left: Int, top: Int, width: Int, height: Int, order: Int) = TestSourceLayer(
			id = LayerId(name),
			name = name,
			groupPath = "",
			order = order,
			bounds = LayerBounds(left, top, width, height),
			opacity = 1f,
			clipped = false,
			blend = LayerBlend.Normal,
			visible = true,
			raster = LayerRaster(width, height, ByteArray(width * height * 4) { index ->
				when (index % 4) { 0 -> 200.toByte(); 1 -> 80; 2 -> 120; else -> 255.toByte() }
			}),
		)

		val source = TestSourceArt(
			widthPx = 200,
			heightPx = 240,
			layers = listOf(
				makeLayer("face", 50, 30, 90, 100, 3),
				makeLayer("mouth", 80, 95, 30, 12, 0),
				makeLayer("topwear", 35, 130, 130, 80, 4),
				makeLayer("eyebrow", 65, 50, 18, 4, 1),
			),
			groups = emptyList(),
		)

		val analysis = io.github.psd2live.core.MouthLipLayers.prepare(
			io.github.psd2live.core.CharacterAnalyzer.analyze(source, config),
			config,
		)
		assertTrue(analysis.layers.any { it.source is io.github.psd2live.core.MouthLipLayer })

		val exportedBytes = PsdWriter.write(
			width = analysis.source.widthPx,
			height = analysis.source.heightPx,
			layers = analysis.layers.map { it.source },
			groups = analysis.source.groups,
		)
		assertTrue(exportedBytes.isNotEmpty())

		val reRead = PsdReader.read(exportedBytes)
		assertEquals(200, reRead.widthPx)
		assertEquals(240, reRead.heightPx)
		assertEquals(analysis.layers.size, reRead.layers.size)

		val reAnalysis = io.github.psd2live.core.CharacterAnalyzer.analyze(reRead, config)
		assertTrue(reAnalysis.layers.isNotEmpty())
	}
}
