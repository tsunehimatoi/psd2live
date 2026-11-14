package io.github.psd2live.application

import kotlinx.coroutines.*
import kotlin.test.*

class WorkspacePersistenceCoordinatorTest {
    @Test fun cancellationSettlesEveryCoalescedReceiptAndFlushIgnoresOtherDestinations() = runBlocking<Unit> {
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) = Unit
        }
        val writes = WorkspacePersistenceCoordinator(dispatcher)
        val first = writes.submit("recovery", "1", coalesce = true) {}
        val last = writes.submit("recovery", "2", coalesce = true) {}
        writes.submit("observation", "1") {}
        writes.flush { it == "absent" }
        writes.cancel()
        assertFailsWith<CancellationException> { first.await() }
        assertFailsWith<CancellationException> { last.await() }
        assertFailsWith<IllegalStateException> { writes.submit("recovery", "3") {} }
    }

    @Test fun anUnrelatedSuccessCannotEraseAFailedDestinationAndRetryAcknowledgesItsVersion() = runBlocking {
        val writes = WorkspacePersistenceCoordinator(Dispatchers.Unconfined)
        try {
            val failed = writes.submit("history", "1") { throw java.io.IOException("Disk failure") }
            assertFailsWith<java.io.IOException> { failed.await() }
            writes.submit("observation", "1") {}.await()
            assertEquals(setOf("history"), writes.status.value.failures.keys)
            assertFailsWith<java.io.IOException> { writes.flush() }
            val retried = writes.submit("history", "2") {}
            assertEquals("2", retried.await())
            writes.flush()
            assertTrue(writes.status.value.failures.isEmpty())
        } finally { writes.finish(); writes.cancel() }
    }

    @Test fun queuedCompleteSnapshotsShareAnAcknowledgementWithoutMixingDestinations() = runBlocking {
        val dispatcher = object : CoroutineDispatcher() {
            val tasks = ArrayDeque<Runnable>()
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { tasks += block }
            fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
        }
        val writes = WorkspacePersistenceCoordinator(dispatcher)
        val output = mutableListOf<String>()
        val first = writes.submit("project-a", "1", coalesce = true) { output += "a:1" }
        val last = writes.submit("project-a", "2", coalesce = true) { output += "a:2" }
        val other = writes.submit("project-b", "1", coalesce = true) { output += "b:1" }
        dispatcher.drain()
        assertEquals(listOf("a:2", "b:1"), output)
        assertEquals("2", first.await())
        assertEquals("2", last.await())
        assertEquals("1", other.await())
        val finish = async(start = CoroutineStart.UNDISPATCHED) { writes.finish() }
        dispatcher.drain()
        finish.await()
        assertFailsWith<IllegalStateException> { writes.submit("project-a", "3") {} }
        writes.cancel()
    }
    @Test fun anOlderAcknowledgementNeverMakesTheCurrentRevisionSaved() = runBlocking {
        val writes = WorkspacePersistenceCoordinator(Dispatchers.Unconfined)
        try {
            var second: WorkspacePersistenceReceipt? = null
            val first = writes.submit("recovery", "1") {
                second = writes.submit("recovery", "2") {
                    val status = writes.status.value
                    assertEquals("1", status.destinations.getValue("recovery").completedVersion)
                    assertEquals(WorkspacePersistencePhase.SAVING, status.projectState("p", "2", "recovery").phase)
                }
            }
            assertEquals("1", first.await())
            assertEquals("2", second!!.await())
            assertEquals(WorkspacePersistencePhase.SAVED, writes.status.value.projectState("p", "2", "recovery").phase)
            assertEquals(WorkspacePersistencePhase.MEMORY, writes.status.value.projectState("p", "3", "recovery").phase)
        } finally { writes.finish(); writes.cancel() }
    }

    @Test fun failureAndRetryAreDestinationScopedAndRetryClearsOnlyAfterAcknowledgement() = runBlocking {
        val writes = WorkspacePersistenceCoordinator(Dispatchers.Unconfined)
        try {
            val failed = writes.submit("a", "1") { throw java.io.IOException("full") }
            assertFailsWith<java.io.IOException> { failed.await() }
            writes.submit("b", "1") {}.await()
            assertEquals(WorkspacePersistencePhase.FAILED, writes.status.value.projectState("p", "1", "a").phase)
            assertEquals(WorkspacePersistencePhase.MEMORY, writes.status.value.projectState("q", "1", "absent").phase)
            writes.submit("a", "1") {
                assertEquals(WorkspacePersistencePhase.SAVING, writes.status.value.projectState("p", "1", "a").phase)
            }.await()
            assertEquals(WorkspacePersistencePhase.SAVED, writes.status.value.projectState("p", "1", "a").phase)
        } finally { writes.finish(); writes.cancel() }
    }

}
