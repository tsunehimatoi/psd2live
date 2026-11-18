package io.github.psd2live.core.sim

import java.util.Locale
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class SimBakedAxisSerializationTest {
    @Test fun fastSerializationMatchesTheSixSignificantDigitFormat() {
        val random = Random(6837509)
        val edgeCases = listOf(-0f, 0f, Float.MIN_VALUE, -Float.MAX_VALUE, 0.9999995f, 1.005005e-9f,
            2.989763e10f, -5.708158e10f, 3.048644e10f, 128.99995f)
        val values = edgeCases + List(16_000) { Float.fromBits(random.nextInt()) }.filter(Float::isFinite)
        val key = values.toFloatArray()
        val source = SimBakedAxis("Sway", floatArrayOf(-1f, 1f), mapOf("mesh" to listOf(key, key)))
        val actual = SimBakedAxis.fromJson(source.toJson()).offsets.getValue("mesh").first()
        for ((index, value) in values.withIndex()) {
            val expected = String.format(Locale.ROOT, "%.6g", value).toFloat()
            assertEquals(expected.toRawBits(), actual[index].toRawBits(), "Value at $index: $value")
        }
    }
}
