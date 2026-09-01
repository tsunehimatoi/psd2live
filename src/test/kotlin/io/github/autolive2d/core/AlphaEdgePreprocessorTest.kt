package io.github.autolive2d.core

import org.umamo.format.art.analyzeAlpha
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AlphaEdgePreprocessorTest {
	@Test
	fun `feather tail and isolated translucent dust do not become outer islands`() {
		val width = 128
		val height = 88
		val rgba = ByteArray(width * height * 4)
		for (y in 10 until 78) for (x in 20 until 108) {
			val distanceX = max(0, max(30 - x, x - 97))
			val distanceY = max(0, max(20 - y, y - 67))
			val distance = max(distanceX, distanceY)
			val alpha = if (distance == 0) 255 else (112 - distance * 11).coerceAtLeast(0)
			rgba[(y * width + x) * 4 + 3] = alpha.toByte()
		}
		for ((x, y) in listOf(4 to 5, 116 to 8, 8 to 80, 120 to 76)) {
			rgba[(y * width + x) * 4 + 3] = 42
		}

		val hardened = assertNotNull(AlphaEdgePreprocessor.process(width, height, rgba, 8))
		assertTrue(hardened.hardThreshold >= 100, "opaque artwork should use a real hard-edge level")
		val analysis = assertNotNull(analyzeAlpha(width, height, hardened.rgba, 1, contourEpsilon = 0f))
		assertEquals(1, analysis.contours.count { !it.isHole })
		assertTrue(analysis.opaqueBounds.left >= 28 && analysis.opaqueBounds.top >= 18)
		assertTrue(analysis.opaqueBounds.width <= 72 && analysis.opaqueBounds.height <= 52)
	}

	@Test
	fun `relative threshold retains intentionally translucent artwork`() {
		val width = 48
		val height = 40
		val rgba = ByteArray(width * height * 4)
		for (y in 8 until 32) for (x in 9 until 39) rgba[(y * width + x) * 4 + 3] = 72

		val hardened = assertNotNull(AlphaEdgePreprocessor.process(width, height, rgba, 4))
		assertTrue(hardened.hardThreshold in 25..40)
		assertTrue(hardened.opaquePixels > 500)
	}
}
