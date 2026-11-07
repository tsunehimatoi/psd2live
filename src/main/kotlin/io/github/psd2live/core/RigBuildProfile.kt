package io.github.psd2live.core

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Wall time spent in each stage of a rig build, summed over every build while [recording] is on. Off - the
 * default - a stage costs one volatile read. Only the measurement tools turn it on.
 */
internal object RigBuildProfile {
	@Volatile var recording: Boolean = false
	private val nanos = ConcurrentHashMap<String, AtomicLong>()
	private val calls = ConcurrentHashMap<String, AtomicLong>()
	private val modifierConsumer = ThreadLocal<String>()
	private const val LEGACY_TEMPLATE = "modifier legacy template: "

	inline fun <T> stage(name: String, block: () -> T): T {
		if (!recording) return block()
		val start = System.nanoTime()
		try { return block() } finally { add(name, System.nanoTime() - start) }
	}

	/** Attribute synchronous compatibility templates to the modifier that actually requests them. */
	inline fun <T> modifierStage(phase: String, type: String, block: () -> T): T {
		if (!recording) return block()
		val previous = modifierConsumer.get()
		val name = "modifier $phase: $type"
		modifierConsumer.set(name)
		try { return stage(name, block) } finally {
			if (previous == null) modifierConsumer.remove() else modifierConsumer.set(previous)
		}
	}

    /** Opt-in import measurements include the actual executing thread, without building labels when disabled. */
    inline fun <T> threadStage(name: String, block: () -> T): T {
        if (!recording) return block()
        return stage("$name [${Thread.currentThread().name}]", block)
    }

	fun add(name: String, elapsed: Long) {
		record(name, elapsed)
		if (name.startsWith(LEGACY_TEMPLATE)) modifierConsumer.get()?.let { consumer ->
			record("modifier template consumer: $consumer -> ${name.removePrefix(LEGACY_TEMPLATE)}", elapsed)
		}
	}

	private fun record(name: String, elapsed: Long) {
		nanos.computeIfAbsent(name) { AtomicLong() }.addAndGet(elapsed)
		calls.computeIfAbsent(name) { AtomicLong() }.incrementAndGet()
	}

	fun count(name: String, amount: Long = 1) = if (recording) calls.computeIfAbsent(name) { AtomicLong() }.addAndGet(amount) else 0L

	fun reset() { nanos.clear(); calls.clear() }

	/** Milliseconds per stage and call counts, in first-recorded order of their names. */
	fun snapshot(): Map<String, Pair<Double, Long>> =
		nanos.keys.union(calls.keys).sorted().associateWith { (nanos[it]?.get() ?: 0L) / 1e6 to (calls[it]?.get() ?: 0L) }
}
