package io.github.psd2live.application

import io.github.psd2live.project.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.io.TempDir
import org.umamo.format.art.SourceArt
import org.umamo.format.art.SourceLayer
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class WorkspaceRuntimeConcurrencyTest {
    @TempDir lateinit var directory: Path

    @Test fun dependencyRefreshPreservesDocumentHistoryAndDirtyState() = runBlocking {
        val document = SyntheticProject(directory, 1, 8, 1).tree.head().snapshot
        val runtime = WorkspaceRuntime<String>({ "model" })
        val initial = runtime.install(runtime.state.value.state, "project", document, "before")
        val history = runtime.history()
        val refreshed = runtime.refreshModel(initial.state, { "after" })
        assertEquals("after", refreshed.model)
        assertSame(document, refreshed.document)
        assertEquals(initial.revision, refreshed.revision)
        assertEquals(initial.dirty, refreshed.dirty)
        assertEquals(history, runtime.history())
        assertNotEquals(initial.state, refreshed.state)
        assertFailsWith<WorkspaceConflict> { runtime.refreshModel(initial.state, { "stale" }) }
        assertEquals("after", runtime.capture().model)
    }

    @Test fun readsDuringProjectionObserveThePreviousCompletePublicationWithoutWaitingForTheCommitLock() = runBlocking {
        val document = SyntheticProject(directory, 1, 8, 1).tree.head().snapshot
        val runtime = WorkspaceRuntime<String>({ "model" })
        val initial = runtime.install(runtime.state.value.state, "project", document, "model")
        val previous = runtime.read()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val reader = Executors.newSingleThreadExecutor()
        val commit = async(Dispatchers.Default) {
            runtime.commitPrepared(initial.projectId, initial.state, "Candidate", MutationAuthor.USER,
                document.copy(layerVisibility = mapOf("layer-0" to false)), "candidate") { _, _, _ ->
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertSame(previous, reader.submit<WorkspaceReadCapture<String>> { runtime.read() }.get(1, TimeUnit.SECONDS))
        } finally { release.countDown(); reader.shutdownNow() }
        val published = commit.await().capture
        val next = runtime.read()
        assertSame(published, next.runtime.capture)
        assertEquals(published.historyHead, next.history?.headNodeId)
        assertEquals(initial.historyHead, previous.history?.headNodeId)
        runtime.close(published.state, discardUnsaved = true)
        assertNull(runtime.read().runtime.capture)
        assertNull(runtime.read().history)
    }

    @Test fun candidateHashingLeavesReadsUnblockedAndAConcurrentChangeRejectsTheOldCandidate() = runBlocking {
        val document = SyntheticProject(directory, 1, 8, 1).tree.head().snapshot
        val runtime = WorkspaceRuntime<String>({ "model" })
        val initial = runtime.install(runtime.state.value.state, "project", document, "model")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val source = object : SourceArt by document.source {
            override val layers: List<SourceLayer> get() {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                return document.source.layers
            }
        }
        val reader = Executors.newSingleThreadExecutor()
        val result = async(Dispatchers.Default) {
            runCatching { runtime.commitPrepared(initial.projectId, initial.state, "Candidate", MutationAuthor.USER,
                document.copy(source = source, layerVisibility = mapOf("layer-0" to false)), "candidate") }
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertEquals(initial.state, reader.submit<String> { runtime.read().runtime.state }.get(1, TimeUnit.SECONDS))
            runtime.updateAuxiliary(initial.projectId, initial.state, buildJsonObject { put("snapshot", "later edit") })
        } finally { release.countDown(); reader.shutdownNow() }
        assertIs<WorkspaceConflict>(result.await().exceptionOrNull())
        assertEquals(initial.historyHead, runtime.capture().historyHead)
        assertEquals("model", runtime.capture().model)
    }
}
