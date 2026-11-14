package io.github.psd2live.core.modifier

import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.*
import kotlin.test.*

class ModifierWorkerResourceFilesTest {
    private fun fixture(block: (Path) -> Unit) {
        val directory = Files.createTempDirectory("worker-resource-files-test-")
        try { block(directory) } finally { ModifierPluginStore.deleteTree(directory) }
    }

    @Test fun requestCleanupCannotDeleteEntriesCapturedByDirectoryDisposal() = fixture { directory ->
        val enumerated = CountDownLatch(1)
        val delete = CountDownLatch(1)
        val clearing = CountDownLatch(1)
        val files = ModifierWorkerResourceFiles(directory) { root ->
            val entries = Files.walk(root).use { it.sorted(Comparator.reverseOrder()).toList() }
            enumerated.countDown()
            check(delete.await(10, TimeUnit.SECONDS))
            entries.forEach { Files.delete(it) }
        }
        files.write("resource", byteArrayOf(1))
        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val disposal = executor.submit { files.close() }
            var cleanup: Future<*>? = null
            try {
                assertTrue(enumerated.await(10, TimeUnit.SECONDS))
                val pending = executor.submit { clearing.countDown(); files.clear() }
                cleanup = pending
                assertTrue(clearing.await(10, TimeUnit.SECONDS))
                assertFailsWith<TimeoutException> { pending.get(200, TimeUnit.MILLISECONDS) }
                delete.countDown()
                disposal.get(10, TimeUnit.SECONDS)
                pending.get(10, TimeUnit.SECONDS)
                assertFalse(Files.exists(directory))
            } finally {
                delete.countDown()
                disposal.get(10, TimeUnit.SECONDS)
                cleanup?.get(10, TimeUnit.SECONDS)
            }
        }
    }

    @Test fun closedResourcesRejectLateWritesAndCleanupRemainsIdempotent() = fixture { directory ->
        val files = ModifierWorkerResourceFiles(directory)
        assertTrue(files.write("resource", byteArrayOf(1)))
        assertFalse(files.write("resource", byteArrayOf(1)))
        files.close()
        files.clear()
        files.close()
        assertFailsWith<IllegalStateException> { files.write("late", byteArrayOf(2)) }
        assertFalse(Files.exists(directory))
    }

    @Test fun filesystemErrorsArePropagatedAndDisposalCanRetry() = fixture { directory ->
        val failure = AccessDeniedException(directory.toString())
        var attempts = 0
        val files = ModifierWorkerResourceFiles(directory) {
            if (++attempts == 1) throw failure
            ModifierPluginStore.deleteTree(it)
        }
        files.write("resource", byteArrayOf(1))
        assertSame(failure, assertFailsWith<AccessDeniedException> { files.close() })
        assertTrue(Files.exists(directory.resolve("resource")))
        assertFailsWith<IllegalStateException> { files.write("late", byteArrayOf(2)) }
        files.close()
        assertEquals(2, attempts)
        assertFalse(Files.exists(directory))
    }
}
