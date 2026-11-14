package io.github.psd2live.core.modifier

import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Process exit and filesystem cleanup never run in a workspace publication or worker-pool lock. */
internal class WorkerRetirementQueue(
    private val executor: Executor = Executors.newVirtualThreadPerTaskExecutor(),
    private val beforeAttempt: () -> Unit = {},
) {
    data class Key(val storeRoot: Path, val pid: Long)
    data class Snapshot(val pid: Long, val attempts: Int, val state: String, val failure: String?)
    private class Entry(val key: Key, val close: () -> Unit, val forceStop: () -> Unit) {
        var attempts = 0
        var state = "pending"
        var failure: Throwable? = null
        var completion = CompletableFuture<Unit>()
    }
    private val entries = ConcurrentHashMap<Key, Entry>()

    fun enqueue(key: Key, forceStop: () -> Unit = {}, close: () -> Unit) {
        val entry = Entry(key, close, forceStop)
        check(entries.putIfAbsent(key, entry) == null) { "Worker already queued for retirement" }
        schedule(entry)
    }

    private fun schedule(entry: Entry) {
        synchronized(entry) { entry.attempts++; entry.state = "pending"; entry.failure = null; entry.completion = CompletableFuture() }
        try {
            executor.execute {
                synchronized(entry) { entry.state = "running" }
                try {
                    beforeAttempt()
                    entry.close()
                    entries.remove(entry.key, entry)
                    entry.completion.complete(Unit)
                } catch (failure: Throwable) { failed(entry, failure) }
            }
        } catch (failure: Throwable) { failed(entry, failure) }
    }

    private fun failed(entry: Entry, failure: Throwable) = synchronized(entry) {
        entry.state = "failed"
        entry.failure = failure
        entry.completion.completeExceptionally(failure)
    }

    fun snapshots(root: Path): List<Snapshot> = entries.values.filter { it.key.storeRoot == root }.map { entry ->
        synchronized(entry) { Snapshot(entry.key.pid, entry.attempts, entry.state,
            entry.failure?.let { "${it.javaClass.simpleName}: ${it.message}" }) }
    }

    fun retryFailed(root: Path) {
        entries.values.filter { it.key.storeRoot == root }.forEach { entry ->
            synchronized(entry) { if (entry.state == "failed") schedule(entry) }
        }
    }

    /** Tools/tests may explicitly wait for disposal. Workspace close/publication never calls this. */
    fun await(root: Path, timeoutMillis: Long = 10_000) {
        val pending = entries.values.filter { it.key.storeRoot == root }.map { synchronized(it) { it.completion } }
        CompletableFuture.allOf(*pending.toTypedArray()).get(timeoutMillis, TimeUnit.MILLISECONDS)
    }

    /**
     * The JVM hook also owns entries already removed from the live pool. Independent daemon platform
     * threads bypass a stalled retirement executor; the hook keeps the JVM alive only for this deadline.
     * Force-stop runs before potentially slow resource cleanup. Remaining entries are explicit diagnostics.
     */
    fun shutdown(timeoutMillis: Long): List<Snapshot> {
        require(timeoutMillis > 0)
        val pending = entries.values.toList()
        val attempts = pending.map { entry ->
            val completion = CompletableFuture<Unit>()
            Thread.ofPlatform().daemon(true).name("plugin-shutdown-${entry.key.pid}").start {
                try {
                    entry.forceStop()
                    entry.close()
                    entries.remove(entry.key, entry)
                    completion.complete(Unit)
                } catch (failure: Throwable) {
                    failed(entry, failure)
                    completion.completeExceptionally(failure)
                }
            }
            completion
        }
        runCatching { CompletableFuture.allOf(*attempts.toTypedArray()).get(timeoutMillis, TimeUnit.MILLISECONDS) }
        return pending.filter { entries[it.key] === it }.map { entry -> synchronized(entry) {
            Snapshot(entry.key.pid, entry.attempts, entry.state,
                entry.failure?.let { "${it.javaClass.simpleName}: ${it.message}" } ?: "Shutdown cleanup did not complete within ${timeoutMillis}ms")
        } }
    }
}
