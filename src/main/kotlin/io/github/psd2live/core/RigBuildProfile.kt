package io.github.psd2live.core

import io.github.psd2live.core.modifier.ModifierAlgorithmStages
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext

/**
 * Wall time spent in each stage of a rig build, summed over every build while [recording] is on. Off - the
 * default - a stage costs one volatile read. Only the measurement tools turn it on.
 */
internal object RigBuildProfile {
	@Volatile var recording: Boolean = false
		set(value) {
			field = value
			ModifierAlgorithmStages.observer = if (value) { name, elapsed ->
				if (recording) add("algorithm stage: $name", elapsed)
			} else null
		}
	private val nanos = ConcurrentHashMap<String, AtomicLong>()
	private val calls = ConcurrentHashMap<String, AtomicLong>()
	private val modifierConsumer = ThreadLocal<String>()
	private val operation = ThreadLocal<OperationRecording>()
	private const val LEGACY_TEMPLATE = "modifier legacy template: "

	data class Operation(val task: String, val startRevision: String, val candidate: String,
	                     val scenario: String, val meshes: Int, val vertices: Long)
	data class Memory(val heapUsed: Long, val heapCommitted: Long, val nonHeapUsed: Long)
	data class Measurement<T>(val value: T, val operation: Operation, val elapsedMillis: Double,
	                          val stages: Map<String, Pair<Double, Long>>, val gauges: Map<String, Long>,
	                          val before: Memory, val after: Memory)
	private class OperationRecording {
		val nanos = ConcurrentHashMap<String, AtomicLong>()
		val counts = ConcurrentHashMap<String, AtomicLong>()
		val gauges = ConcurrentHashMap<String, Long>()
	}

	/** Tools own the returned sample; the profiler never retains operation histories or model results. */
	fun <T> measure(identity: Operation, block: () -> T): Measurement<T> {
		check(recording) { "Enable RigBuildProfile before measuring an operation" }
		val previous = operation.get()
		val sample = OperationRecording()
		val before = memory()
		val start = System.nanoTime()
		operation.set(sample)
		try {
			val value = block()
			return Measurement(value, identity, (System.nanoTime() - start) / 1e6,
				sample.nanos.keys.union(sample.counts.keys).sorted().associateWith {
					(sample.nanos[it]?.get() ?: 0L) / 1e6 to (sample.counts[it]?.get() ?: 0L)
				}, sample.gauges.toSortedMap(), before, memory())
		} finally { if (previous == null) operation.remove() else operation.set(previous) }
	}

	/** Application tools cross Dispatchers.Default during builds; keep the same sample on those threads. */
	suspend fun <T> measureSuspending(identity: Operation, block: suspend () -> T): Measurement<T> {
		check(recording)
		val sample = OperationRecording()
		val before = memory()
		val start = System.nanoTime()
		return withContext(operation.asContextElement(sample)) {
			val value = block()
			Measurement(value, identity, (System.nanoTime() - start) / 1e6,
				sample.nanos.keys.union(sample.counts.keys).sorted().associateWith {
					(sample.nanos[it]?.get() ?: 0L) / 1e6 to (sample.counts[it]?.get() ?: 0L)
				}, sample.gauges.toSortedMap(), before, memory())
		}
	}

	/** Explicit propagation to a worker executor, without inheriting context in long-lived pool threads. */
	fun <T> propagate(block: () -> T): () -> T {
		val captured = operation.get()
		return {
			val previous = operation.get()
			if (captured == null) operation.remove() else operation.set(captured)
			try { block() } finally { if (previous == null) operation.remove() else operation.set(previous) }
		}
	}

	fun gauge(name: String, value: Long) { if (recording) operation.get()?.gauges?.put(name, value) }
	fun memory(): Memory = java.lang.management.ManagementFactory.getMemoryMXBean().let {
		Memory(it.heapMemoryUsage.used, it.heapMemoryUsage.committed, it.nonHeapMemoryUsage.used)
	}

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
		operation.get()?.let {
			it.nanos.computeIfAbsent(name) { AtomicLong() }.addAndGet(elapsed)
			it.counts.computeIfAbsent(name) { AtomicLong() }.incrementAndGet()
		}
	}

	fun count(name: String, amount: Long = 1): Long {
		if (!recording) return 0L
		operation.get()?.counts?.computeIfAbsent(name) { AtomicLong() }?.addAndGet(amount)
		return calls.computeIfAbsent(name) { AtomicLong() }.addAndGet(amount)
	}

	fun reset() { nanos.clear(); calls.clear() }

	/** Milliseconds per stage and call counts, sorted by stage name. */
	fun snapshot(): Map<String, Pair<Double, Long>> =
		nanos.keys.union(calls.keys).sorted().associateWith { (nanos[it]?.get() ?: 0L) / 1e6 to (calls[it]?.get() ?: 0L) }
}
