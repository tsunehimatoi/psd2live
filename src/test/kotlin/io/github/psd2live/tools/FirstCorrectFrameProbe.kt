package io.github.psd2live.tools

/** Tool-only receipt validation. Publication/build completion alone cannot complete a sample. */
internal class FirstCorrectFrameProbe(
    val task: String,
    val revision: String,
    val candidate: String,
    private val model: Any,
    private val capturedAt: Long,
    private val expectedPixels: List<Int>,
) {
    private var completed = false

    fun complete(renderedRevision: String, renderedCandidate: String, renderedModel: Any,
                 readbackPixels: List<Int>, readbackCompletedAt: Long): Long {
        check(!completed) { "A capture can only complete once" }
        require(renderedRevision == revision && renderedCandidate == candidate && renderedModel === model) {
            "Readback belongs to another captured revision or candidate"
        }
        require(readbackCompletedAt >= capturedAt) { "Readback precedes capture" }
        require(readbackPixels.size == expectedPixels.size && expectedPixels.isNotEmpty()) { "Incomplete pixel evidence" }
        expectedPixels.zip(readbackPixels).forEachIndexed { index, (expected, actual) ->
            require((0..3).all { shift ->
                kotlin.math.abs((expected ushr (shift * 8) and 255) - (actual ushr (shift * 8) and 255)) <= 2
            }) { "Incorrect frame at probe $index: expected ${expected.toUInt().toString(16)}, got ${actual.toUInt().toString(16)}" }
        }
        completed = true
        return readbackCompletedAt - capturedAt
    }
}

internal data class FirstFrameDistribution(val samples: Int, val medianMs: Double, val p95Ms: Double, val maxMs: Double)

/** Nearest-rank P95; all successful samples, including slow outliers, remain in the distribution. */
internal fun firstFrameDistribution(nanos: List<Long>): FirstFrameDistribution {
    require(nanos.size >= 30) { "At least 30 samples are required per scenario and cache mode" }
    require(nanos.all { it >= 0 })
    val sorted = nanos.sorted().map { it / 1e6 }
    val mid = sorted.size / 2
    return FirstFrameDistribution(sorted.size,
        if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2 else sorted[mid],
        sorted[kotlin.math.ceil(sorted.size * .95).toInt() - 1], sorted.last())
}
