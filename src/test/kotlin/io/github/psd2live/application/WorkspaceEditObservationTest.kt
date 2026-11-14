package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.core.modifier.ModifierAlgorithmStages
import io.github.psd2live.project.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class WorkspaceEditObservationTest {
    @TempDir lateinit var temp: Path
    private fun document() = SyntheticProject(temp.resolve("source"), 1, 8, 1).tree.head().snapshot
    private fun edit(value: Boolean) = WorkspaceDocumentEdit<String> { doc, _ -> doc.copy(layerVisibility = mapOf("layer-0" to value)) }
    private fun runtime(build: suspend (WorkspaceDocument) -> String = { "built" }) = WorkspaceRuntime(build).also {
        it.install(it.state.value.state, "project", document(), "initial")
    }

    @Test fun noSubscriberKeepsBuildUnmeasuredAndPublicationUnstamped() = runBlocking<Unit> {
        assertFalse(RigBuildProfile.recording)
        val runtime = runtime {
            assertFalse(RigBuildProfile.recording)
            assertNull(WorkspaceEditObservation.current())
            "built"
        }
        val before = runtime.capture()
        val result = runtime.execute(before.projectId, before.state, "visibility", MutationAuthor.USER, listOf(edit(false)))
        assertTrue(result.applied)
        assertNull(result.capture.observation)
    }

    @Test fun oneEditCorrelatesMultipleBuildsWithoutGlobalProfilePollutionOrHistoryChanges() = runBlocking<Unit> {
        val events = mutableListOf<WorkspaceEditObservation.Event>()
        val profile = RigBuildProfile.snapshot()
        val runtime = runtime {
            runInterruptible(Dispatchers.Default) {
                RigBuildProfile.count("observed build")
                ModifierAlgorithmStages.stage("observed algorithm") { }
            }
            "built"
        }
        val before = runtime.capture()
        val initialNodes = runtime.history().selections.size
        WorkspaceEditObservation.subscribe(events::add).use {
            val result = runtime.execute(before.projectId, before.state, "batch", MutationAuthor.USER,
                listOf(edit(false), edit(true)), taskId = "job-test")
            val stamp = assertNotNull(result.capture.observation)
            assertEquals(result.capture.state, stamp.state)
            assertEquals(result.capture.revision, stamp.revision)
            assertEquals(before.revision, stamp.edit.startRevision)
            assertEquals(before.loadingIdentity, stamp.edit.loadingIdentity)
            assertEquals("job-test", stamp.edit.taskId)
            assertEquals(2, stamp.build!!.ordinal)
            assertTrue(stamp.publishedAtNanos >= stamp.edit.requestedAtNanos)
            assertEquals(initialNodes + 1, runtime.history().selections.size)
            assertEquals(WorkspaceRevisions.of(result.capture.document), result.capture.revision)
            assertFalse(result.capture.document.toString().contains("WorkspacePublishedObservation"))
        }
        assertEquals(1, events.map { it.editId }.distinct().size)
        assertEquals(listOf(WorkspaceEditObservation.Phase.STARTED, WorkspaceEditObservation.Phase.BUILT,
            WorkspaceEditObservation.Phase.BUILT, WorkspaceEditObservation.Phase.PUBLISHED), events.map { it.phase })
        val builds = events.mapNotNull { it.build }
        assertEquals(listOf(1, 2), builds.map { it.operation.correlation!!.ordinal })
        builds.forEach {
            assertEquals(1L, it.stages.getValue("observed build").second)
            assertEquals(1L, it.stages.getValue("algorithm stage: observed algorithm").second)
            assertTrue(it.finishedAtNanos >= it.startedAtNanos)
        }
        assertEquals(profile, RigBuildProfile.snapshot())
        assertFalse(RigBuildProfile.recording)
    }

    @Test fun noChangeDoesNotPublishOrFabricateAFrameCorrelation() = runBlocking<Unit> {
        val events = mutableListOf<WorkspaceEditObservation.Event>()
        val runtime = runtime { error("No-op must not build") }
        val before = runtime.capture()
        WorkspaceEditObservation.subscribe(events::add).use {
            val result = runtime.execute(before.projectId, before.state, "noop", MutationAuthor.USER,
                listOf(WorkspaceDocumentEdit { doc, _ -> doc }))
            assertFalse(result.applied)
            assertSame(before, result.capture)
        }
        assertEquals(WorkspaceEditObservation.Phase.NO_CHANGE, events.last().phase)
        assertNull(events.last().publication)
        assertTrue(events.none { it.build != null })
    }

    @Test fun buildFailureAndCancellationRetainPartialStagesWithoutPublishing() = runBlocking<Unit> {
        for (cancel in listOf(false, true)) {
            val events = mutableListOf<WorkspaceEditObservation.Event>()
            val runtime = WorkspaceRuntime<String>({
                RigBuildProfile.count("partial")
                if (cancel) throw CancellationException("cancelled build") else error("failed build")
            })
            val before = runtime.install(runtime.state.value.state, "project", document(), "initial")
            WorkspaceEditObservation.subscribe(events::add).use {
                val error = runCatching { runtime.execute(before.projectId, before.state, "edit", MutationAuthor.USER, listOf(edit(false))) }.exceptionOrNull()
                assertNotNull(error)
            }
            assertSame(before, runtime.capture())
            assertEquals(if (cancel) WorkspaceEditObservation.Phase.CANCELLED else WorkspaceEditObservation.Phase.FAILED, events.last().phase)
            assertEquals(1L, events.single { it.build != null }.build!!.stages.getValue("partial").second)
            assertNull(events.last().publication)
        }
    }

    @Test fun staleCasDoesNotStampTheWinningPublication() = runBlocking<Unit> {
        val events = mutableListOf<WorkspaceEditObservation.Event>()
        lateinit var runtime: WorkspaceRuntime<String>
        runtime = runtime {
            val before = runtime.capture()
            runtime.updateAuxiliary(before.projectId, before.state, buildJsonObject { put("winner", true) })
            "stale"
        }
        val before = runtime.capture()
        WorkspaceEditObservation.subscribe(events::add).use {
            assertFailsWith<WorkspaceConflict> { runtime.execute(before.projectId, before.state, "edit", MutationAuthor.USER, listOf(edit(false))) }
        }
        assertEquals(WorkspaceEditObservation.Phase.CONFLICT, events.last().phase)
        assertNull(runtime.capture().observation)
        assertEquals(before.historyHead, runtime.capture().historyHead)
    }

    @Test fun subscriberRunsAfterPublicationLockAndCannotBreakTheCommit() = runBlocking<Unit> {
        val runtime = runtime()
        val before = runtime.capture()
        val observed = mutableListOf<String>()
        val errors = mutableListOf<Throwable>()
        Executors.newSingleThreadExecutor().use { executor ->
            WorkspaceEditObservation.subscribe {
                runCatching { observed += executor.submit<String> { runtime.withCapture { it.state } }.get(2, TimeUnit.SECONDS) }
                    .exceptionOrNull()?.let(errors::add)
                error("diagnostic failure")
            }.use {
                val result = runtime.execute(before.projectId, before.state, "edit", MutationAuthor.USER, listOf(edit(false)))
                assertTrue(result.applied)
                assertTrue(observed.all { it == result.capture.state })
            }
        }
        assertTrue(observed.isNotEmpty())
        assertTrue(errors.isEmpty(), errors.toString())
    }

    @Test fun cancellationAfterCasKeepsPublishedOutcomeAndLaterStatesClearTheStamp() = runBlocking<Unit> {
        val runtime = runtime()
        val before = runtime.capture()
        val events = mutableListOf<WorkspaceEditObservation.Event>()
        val job = Job()
        WorkspaceEditObservation.subscribe(events::add).use {
            runCatching { withContext(job) {
                runtime.execute(before.projectId, before.state, "edit", MutationAuthor.USER, listOf(edit(false)),
                    beforeCommit = { _, _, _ -> job.cancel() })
            } }
        }
        val published = runtime.capture()
        assertNotNull(published.observation)
        assertEquals(WorkspaceEditObservation.Phase.PUBLISHED, events.last().phase)
        assertEquals(published.state, events.last().publication!!.state)
        val next = runtime.updateAuxiliary(published.projectId, published.state, buildJsonObject { put("pose", 1) })
        assertNull(next.observation)
    }

    @Test fun commandEntryPublishesAdoptedRevisionAndNestedScopesShareOneTrace() = runBlocking<Unit> {
        val doc = document()
        val builder = WorkspacePreviewBuilder()
        val initial = builder.build(doc)
        val checkpoint = RigCheckpoint.encode(initial.authored, initial.sources.bindingKey!!)
        val runtime = WorkspaceRuntime<RigPreviewModel>({ candidate -> initial.copy(config = initial.config.copy(
            rigEdits = candidate.rigEdits.copy(authoringJournal = candidate.rigEdits.authoringJournal + checkpoint))) })
        val before = runtime.install(runtime.state.value.state, "project", doc, initial)
        val events = mutableListOf<WorkspaceEditObservation.Event>()
        WorkspaceEditObservation.subscribe(events::add).use {
            WorkspaceEditObservation.observe(before.state, "outer") {
                WorkspaceDocumentCommands(runtime).executeCandidate(before.projectId, before.state, "inner", MutationAuthor.USER,
                    mutation = { input, _ -> input.copy(layerVisibility = mapOf("layer-0" to false)) })
            }
        }
        val published = runtime.capture()
        val stamp = assertNotNull(published.observation)
        assertEquals("outer", stamp.edit.operation)
        assertNotEquals(stamp.build!!.candidateRevision, stamp.revision)
        assertEquals(WorkspaceRevisions.of(published.document), stamp.revision)
        assertEquals(published.revision, stamp.revision)
        assertEquals(1, events.count { it.phase == WorkspaceEditObservation.Phase.PUBLISHED })
    }

    @Test fun auxiliaryOnlyEditPublishesWithoutFabricatingABuild() = runBlocking<Unit> {
        val runtime = runtime { error("Auxiliary-only edit must not build") }
        val before = runtime.capture()
        val events = mutableListOf<WorkspaceEditObservation.Event>()
        WorkspaceEditObservation.subscribe(events::add).use {
            val result = runtime.executeDraft(before.projectId, before.state, "auxiliary", MutationAuthor.USER,
                listOf(WorkspaceDraftEdit { draft, _ -> draft.copy(auxiliary = buildJsonObject { put("pose", 1) }) }))
            val stamp = assertNotNull(result.capture.observation)
            assertNull(stamp.build)
            assertEquals(before.revision, stamp.revision)
            assertNotEquals(before.state, stamp.state)
        }
        assertEquals(listOf(WorkspaceEditObservation.Phase.STARTED, WorkspaceEditObservation.Phase.PUBLISHED), events.map { it.phase })
    }

    @Test fun publicHostEntryCorrelatesRealEditAndReleasesEditMutexBeforeCallbacks() = runBlocking<Unit> {
        val source = SyntheticProject(temp.resolve("host-source"), 1, 8, 1)
        HeadlessPresentation().use { presentation -> WorkspaceHost(presentation, temp.resolve("host-store")).use { host ->
            presentation.attach(host)
            host.importPsd(source.source.toString(), false, null, null)
            val state = requireNotNull(host.stateToken())
            val id = host.snapshot().layers.first().id
            val edits = listOf(WorkspaceDocumentOperation("layer_classify", buildJsonObject {
                put("layer_id", id); put("side", "right")
            }))
            val events = mutableListOf<WorkspaceEditObservation.Event>()
            val errors = mutableListOf<Throwable>()
            var reentered = false
            Executors.newSingleThreadExecutor().use { executor ->
                lateinit var subscription: AutoCloseable
                subscription = WorkspaceEditObservation.subscribe { event ->
                    events += event
                    if (event.phase == WorkspaceEditObservation.Phase.PUBLISHED) {
                        subscription.close()
                        runCatching {
                            executor.submit<Unit> { runBlocking {
                                withTimeout(2_000) {
                                    host.applyDocumentEdits(requireNotNull(host.stateToken()), "callback noop",
                                        listOf(WorkspaceDocumentOperation("layer_restore", JsonObject(emptyMap()))), MutationAuthor.USER)
                                    reentered = true
                                }
                            } }.get(3, TimeUnit.SECONDS)
                        }.exceptionOrNull()?.let(errors::add)
                    }
                }
                subscription.use {
                    val result = host.applyDocumentEdits(state, "classify layer", edits, MutationAuthor.USER)
                    val stamp = assertNotNull(events.last().publication)
                    assertEquals(result.state, stamp.state)
                    assertEquals(state, stamp.edit.startState)
                    assertEquals("classify layer", stamp.edit.operation)
                    assertEquals(1, stamp.build!!.ordinal)
                }
            }
            assertTrue(errors.isEmpty(), errors.toString())
            assertTrue(reentered)
            assertEquals(1, events.map { it.editId }.distinct().size)
        } }
    }
}
