package io.github.psd2live.core

import kotlin.test.Test
import kotlin.test.assertContentEquals

class AlphaEdgePreprocessorTest {
    @Test fun retainsFineStrokesAndRejectsTransparentGapsAndFeatherTails() {
        assertMask(7, 1, 8, intArrayOf(0, 15, 255, 0, 255, 15, 0), 1, 2, 4, 5)
        assertMask(6, 1, 5, intArrayOf(0, 15, 255, 110, 30, 0), 2, 3)
        assertMask(1, 3, 200, intArrayOf(0, 255, 0), 1)
        assertMask(1, 1, 1, intArrayOf(1), 0)
        assertMask(2, 2, 1, intArrayOf(0, 0, 0, 0))
    }

    private fun assertMask(width: Int, height: Int, threshold: Int, alpha: IntArray, vararg opaque: Int) {
        val source = ByteArray(alpha.size * 4) { index ->
            if (index % 4 == 3) alpha[index / 4].toByte() else 0x7f
        }
        val before = source.copyOf()
        val expected = ByteArray(source.size)
        for (index in opaque) expected[index * 4 + 3] = -1
        assertContentEquals(expected, AlphaEdgePreprocessor.geometryMask(width, height, source, threshold))
        assertContentEquals(before, source)
    }
}
