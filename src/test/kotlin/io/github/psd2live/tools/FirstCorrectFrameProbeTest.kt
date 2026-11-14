package io.github.psd2live.tools

import kotlin.test.*

class FirstCorrectFrameProbeTest {
    private val model = Any()
    private val pixels = listOf(0xff123456.toInt(), 0, 0xffaabbcc.toInt())
    private fun probe() = FirstCorrectFrameProbe("task", "revision", "candidate", model, 100, pixels)

    @Test fun staleRevisionsCandidatesAndEqualButDifferentModelsCannotComplete() {
        assertFailsWith<IllegalArgumentException> { probe().complete("old", "candidate", model, pixels, 200) }
        assertFailsWith<IllegalArgumentException> { probe().complete("revision", "old", model, pixels, 200) }
        assertFailsWith<IllegalArgumentException> { probe().complete("revision", "candidate", Any(), pixels, 200) }
    }

    @Test fun publicationWithoutPixelsAndStaleOrPartialReadbackCannotComplete() {
        assertFailsWith<IllegalArgumentException> { probe().complete("revision", "candidate", model, emptyList(), 200) }
        assertFailsWith<IllegalArgumentException> { probe().complete("revision", "candidate", model, pixels.reversed(), 200) }
        assertFailsWith<IllegalArgumentException> { probe().complete("revision", "candidate", model, pixels.dropLast(1), 200) }
        assertFailsWith<IllegalArgumentException> { probe().complete("revision", "candidate", model, pixels, 99) }
    }

    @Test fun matchingReadbackCompletesExactlyOnceAtReadbackTime() {
        val probe = probe()
        assertEquals(175L, probe.complete("revision", "candidate", model, pixels, 275))
        assertFailsWith<IllegalStateException> { probe.complete("revision", "candidate", model, pixels, 300) }
    }

    @Test fun distributionsRejectUndersamplingAndKeepOutliers() {
        assertFailsWith<IllegalArgumentException> { firstFrameDistribution(List(29) { 0L }) }
        val distribution = firstFrameDistribution((1L..29L).map { it * 1_000_000 } + 1_000_000_000L)
        assertEquals(30, distribution.samples)
        assertEquals(15.5, distribution.medianMs)
        assertEquals(29.0, distribution.p95Ms)
        assertEquals(1000.0, distribution.maxMs)
    }
}
