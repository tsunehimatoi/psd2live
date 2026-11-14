package io.github.psd2live.core.modifier

import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

class WorkerRetirementQueueTest {
    @Test fun shutdownDisposesPendingAndFailedEntriesWithoutTheirExecutor() {
        val stalled = Executor { /* deliberately does not run normal disposal */ }
        val queue = WorkerRetirementQueue(stalled)
        val root = Path.of("retirement-test").toAbsolutePath()
        val forced = AtomicBoolean()
        val closed = AtomicBoolean()
        queue.enqueue(WorkerRetirementQueue.Key(root, 1), forceStop = { forced.set(true) }) { closed.set(true) }
        assertEquals("pending", queue.snapshots(root).single().state)
        assertTrue(queue.shutdown(5_000).isEmpty())
        assertTrue(forced.get() && closed.get())

        val failureQueue = WorkerRetirementQueue(Executor { it.run() })
        val failOnce = AtomicBoolean(true)
        failureQueue.enqueue(WorkerRetirementQueue.Key(root, 2)) {
            if (failOnce.getAndSet(false)) error("first disposal failed")
        }
        assertEquals("failed", failureQueue.snapshots(root).single().state)
        assertTrue(failureQueue.shutdown(5_000).isEmpty(), "Failed retirees must also be retried by JVM shutdown")
    }

    @Test fun shutdownDeadlineReportsUnreleasedResourcesAfterForceStop() {
        val queue = WorkerRetirementQueue(Executor { })
        val root = Path.of("retirement-test").toAbsolutePath()
        val forced = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val completed = CountDownLatch(1)
        queue.enqueue(WorkerRetirementQueue.Key(root, 3), forceStop = { forced.countDown() }) {
            try { check(resume.await(5, TimeUnit.SECONDS)) } finally { completed.countDown() }
        }
        try {
            val unresolved = queue.shutdown(50)
            assertTrue(forced.await(5, TimeUnit.SECONDS))
            assertEquals(3L, unresolved.single().pid)
            assertTrue(unresolved.single().failure!!.contains("did not complete"))
        } finally { resume.countDown(); assertTrue(completed.await(5, TimeUnit.SECONDS)) }
    }
}
