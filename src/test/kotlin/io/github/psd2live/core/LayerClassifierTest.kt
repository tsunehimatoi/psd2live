package io.github.psd2live.core

import org.umamo.format.art.LayerBlend
import org.umamo.format.art.LayerBounds
import org.umamo.format.art.LayerId
import org.umamo.format.art.LayerRaster
import org.umamo.format.art.SourceLayer
import org.umamo.format.art.SourceArt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LayerClassifierTest {
	@Test
	fun `recognizes See-Through names aliases and side suffixes`() {
		assertEquals(SemanticTag.MOUTH_OPEN, LayerClassifier.classify("mouth_open-r").tag)
		assertEquals(Side.RIGHT, LayerClassifier.classify("mouth_open-r").side)
		assertEquals(SemanticTag.EYEWHITE, LayerClassifier.classify("眼白_左").tag)
		assertEquals(Side.LEFT, LayerClassifier.classify("眼白_左").side)
		assertEquals(SemanticTag.BACK_HAIR, LayerClassifier.classify("後ろ髪").tag)
		assertEquals(SemanticTag.FRONT_HAIR, LayerClassifier.classify("刘海 copy 2").tag)
	}

	@Test
	fun `splits a connected-component pair into character left and right`() {
		val width = 18
		val height = 6
		val pixels = ByteArray(width * height * 4)
		fun fill(left: Int, right: Int) {
			for (y in 1..4) for (x in left..right) pixels[(y * width + x) * 4 + 3] = 0xff.toByte()
		}
		fill(1, 5)
		fill(12, 16)
		val original = LayerClassifier.classify(layer("irides", width, height, pixels), 8)
		val split = ComponentSplitter.split(original, 9f, 8)
		assertEquals(listOf(Side.RIGHT, Side.LEFT), split.map { it.semantic.side })
		assertEquals(2, split.size)
	}

	@Test
	fun `manual semantic override is applied before component splitting`() {
		val width = 24
		val height = 12
		val pixels = ByteArray(width * height * 4)
		for (y in 3..8) {
			for (x in 2..6) pixels[(y * width + x) * 4 + 3] = 0xff.toByte()
			for (x in 17..21) pixels[(y * width + x) * 4 + 3] = 0xff.toByte()
		}
		val sourceLayer = layer("mystery", width, height, pixels)
		val source = object : SourceArt {
			override val layers = listOf(sourceLayer)
			override val widthPx = width
			override val heightPx = height
		}
		val analysis = CharacterAnalyzer.analyze(
			source,
			PipelineConfig(
				layerOverrides = mapOf("mystery" to LayerClassificationOverride(SemanticTag.EYEBROW, Side.NONE)),
			),
		)
		assertEquals(2, analysis.layers.size)
		assertTrue(analysis.layers.all { it.semantic.tag == SemanticTag.EYEBROW })
		assertEquals(setOf(Side.LEFT, Side.RIGHT), analysis.layers.map { it.semantic.side }.toSet())
	}

	private fun layer(name: String, width: Int, height: Int, rgba: ByteArray) = object : SourceLayer {
		override val id = LayerId(name)
		override val name = name
		override val groupPath = ""
		override val order = 0
		override val bounds = LayerBounds(0, 0, width, height)
		override val opacity = 1f
		override val clipped = false
		override val blend = LayerBlend.Normal
		override val raster = LayerRaster(width, height, rgba)
	}
}
