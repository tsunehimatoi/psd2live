package io.github.psd2live.core.modifier

/**
 * Optional synchronous stage observer owned by the embedding host. This API neither aggregates
 * samples nor retains algorithm inputs/results. Worker processes leave it disabled by default.
 */
object ModifierAlgorithmStages {
    @Volatile var observer: ((name: String, elapsedNanos: Long) -> Unit)? = null

    /** Disabled stages only read [observer]; observer failures never replace algorithm outcomes. */
    inline fun <T> stage(name: String, block: () -> T): T {
        val sink = observer ?: return block()
        val start = System.nanoTime()
        try { return block() } finally { notify(sink, name, System.nanoTime() - start) }
    }

    @PublishedApi internal fun notify(sink: (String, Long) -> Unit, name: String, elapsed: Long) {
        try { sink(name, elapsed) } catch (_: Throwable) {
            // Instrumentation must preserve values, failures and cancellation from the algorithm.
        }
    }
}
