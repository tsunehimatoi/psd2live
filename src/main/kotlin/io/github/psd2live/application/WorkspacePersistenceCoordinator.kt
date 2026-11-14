package io.github.psd2live.application

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A receipt resolves only when this write (or its complete replacement) has reached its destination. */
internal class WorkspacePersistenceReceipt internal constructor(
    val destination: Any,
    val requestedVersion: String,
    internal val completion: CompletableDeferred<String> = CompletableDeferred(),
) {
    suspend fun await(): String = completion.await()
}

internal data class WorkspacePersistenceFailure(val version: String, val cause: Exception)
internal data class WorkspacePersistenceDestinationStatus(
    val requestedVersion: String,
    val completedVersion: String? = null,
    val pending: Int = 0,
)
internal data class WorkspacePersistenceStatus(
    val pending: Int = 0,
    val failures: Map<Any, WorkspacePersistenceFailure> = emptyMap(),
    val destinations: Map<Any, WorkspacePersistenceDestinationStatus> = emptyMap(),
)

/** FIFO publication, complete-snapshot coalescing and destination-specific failure acknowledgements. */
internal class WorkspacePersistenceCoordinator(dispatcher: CoroutineDispatcher = Dispatchers.IO) {
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val queue = Channel<Entry>(Channel.UNLIMITED)
    private class Entry(val destination: Any, var version: String, var write: () -> Unit,
                        val receipts: MutableList<WorkspacePersistenceReceipt>)
    private val waiting = mutableMapOf<Any, Entry>()
    private val latest = mutableMapOf<Any, WorkspacePersistenceReceipt>()
    private val outstanding = mutableSetOf<WorkspacePersistenceReceipt>()
    private val mutableStatus = MutableStateFlow(WorkspacePersistenceStatus())
    val status = mutableStatus.asStateFlow()
    private var accepting = true
    private val worker = scope.launch {
        for (entry in queue) {
            val work = synchronized(lock) {
                if (waiting[entry.destination] === entry) waiting.remove(entry.destination)
                Triple(entry.version, entry.write, entry.receipts.toList())
            }
            var failure: Exception? = null
            try { work.second() }
            catch (caught: Exception) { failure = caught }
            synchronized(lock) {
                val before = mutableStatus.value
                val failures = if (failure == null) before.failures - entry.destination
                    else before.failures + (entry.destination to WorkspacePersistenceFailure(work.first, failure))
                val destination = before.destinations.getValue(entry.destination)
                mutableStatus.value = before.copy(pending = before.pending - 1, failures = failures,
                    destinations = before.destinations + (entry.destination to destination.copy(
                        pending = destination.pending - 1,
                        completedVersion = if (failure == null) work.first else destination.completedVersion)))
                outstanding.removeAll(work.third.toSet())
            }
            work.third.forEach { receipt ->
                if (failure == null) receipt.completion.complete(work.first)
                else receipt.completion.completeExceptionally(failure)
            }
        }
    }

    fun submit(destination: Any, version: String, coalesce: Boolean = false, write: () -> Unit): WorkspacePersistenceReceipt = synchronized(lock) {
        check(accepting) { "Persistence coordinator is closed" }
        val receipt = WorkspacePersistenceReceipt(destination, version)
        outstanding += receipt
        val previous = if (coalesce) waiting[destination] else null
        val before = mutableStatus.value
        val destinationStatus = before.destinations[destination]
        mutableStatus.value = before.copy(
            pending = before.pending + if (previous == null) 1 else 0,
            destinations = before.destinations + (destination to WorkspacePersistenceDestinationStatus(
                requestedVersion = version, completedVersion = destinationStatus?.completedVersion,
                pending = (destinationStatus?.pending ?: 0) + if (previous == null) 1 else 0)))
        if (previous != null) {
            previous.version = version
            previous.write = write
            previous.receipts += receipt
        } else {
            val entry = Entry(destination, version, write, mutableListOf(receipt))
            if (coalesce) waiting[destination] = entry
            check(queue.trySend(entry).isSuccess)
        }
        latest[destination] = receipt
        receipt
    }

    /** Freeze the requested receipts: later submissions cannot make an earlier flush claim their success. */
    suspend fun flush(include: (Any) -> Boolean = { true }) {
        val receipts = synchronized(lock) { latest.filterKeys(include).values.toList() }
        var failure: Exception? = null
        for (receipt in receipts) {
            try { receipt.await() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (caught: Exception) { if (failure == null) failure = caught else failure.addSuppressed(caught) }
        }
        failure?.let { throw it }
    }

    /** Stops accepting writes and drains already accepted work, including failures. */
    suspend fun finish() {
        synchronized(lock) { accepting = false; queue.close() }
        worker.join()
    }

    fun cancel() {
        synchronized(lock) {
            accepting = false
            queue.cancel()
            outstanding.forEach {
                it.completion.cancel(CancellationException("Persistence stopped before acknowledgement"))
            }
            outstanding.clear()
            waiting.clear()
        }
        scope.cancel()
    }
}
