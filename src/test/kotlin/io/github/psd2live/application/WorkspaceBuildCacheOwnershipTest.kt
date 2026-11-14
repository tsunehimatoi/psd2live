package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.core.modifier.*
import io.github.psd2live.project.WorkspaceDocument
import io.github.psd2live.scenario.Characters
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.*

class WorkspaceBuildCacheOwnershipTest {
    @Test fun runtimeCloseReleasesOnlyItsOwnerAndPreservesCapturedModels() = runBlocking {
        val directory = Files.createTempDirectory("workspace-cache-owner-")
        val preferences = Preferences.userRoot().node("psd2live-tests/owner/${UUID.randomUUID()}")
        val store = ModifierPluginStore(directory.resolve("installed"), preferences)
        val firstOwner = ModifierBuildCacheOwner(store)
        val secondOwner = ModifierBuildCacheOwner(store)
        try {
            val entry = store.install(createSyntheticPluginZip(directory, "shared.one", "shared.one/op", 1).toString())
            val document = Characters.head(physics = false)
            val base = WorkspacePreviewBuilder().build(document)
            val firstModel = base.copy().also { it.modifierBuildOwner = firstOwner }
            val secondModel = base.copy().also { it.modifierBuildOwner = secondOwner }
            val first = WorkspaceRuntime<RigPreviewModel>({ firstModel })
            val second = WorkspaceRuntime<RigPreviewModel>({ secondModel })
            first.install(first.state.value.state, "first", document, firstModel)
            second.install(second.state.value.state, "second", document, secondModel)
            firstOwner.meshCache()
            secondOwner.meshCache()
            val input = syntheticWorkerRig()
            val firstSession = firstOwner.workerSession()
            val secondSession = secondOwner.workerSession()
            val instance = RigModifierInstance("test", "shared.one/op", 1,
                listOf(ModifierTarget("mesh", "m1")), plugin = entry.reference)
            fun evaluate(owner: ModifierBuildCacheOwner) {
                val registry = RigModifierRegistry(ModifierPluginCatalog.definitions(store, workerSession = owner.workerSession()))
                assertEquals(.25f, registry.definition(instance).plugin.evaluate(input, instance) {}.meshes.single().opacity)
            }
            evaluate(firstOwner)
            evaluate(secondOwner)
            val pid = ModifierPluginWorkers.cacheSnapshots(store).single().pid
            val captured = first.capture()
            // Replacing an open project releases the previous owner, even before Host.close.
            first.install(captured.state, "replacement", document, base.copy())
            firstSession.awaitReleased()
            assertTrue(firstOwner.isClosed)
            assertFalse(firstOwner.retainsMeshCache)
            assertFalse(secondOwner.isClosed)
            assertSame(base.rig, captured.model.rig, "Captured immutable rig must survive workspace closure")
            evaluate(secondOwner)
            assertEquals(pid, ModifierPluginWorkers.cacheSnapshots(store).single().pid)
            second.releaseBuildCaches()
            secondSession.awaitReleased()
            ModifierPluginWorkers.awaitRetirements(store)
            assertSame(secondModel, second.capture().model, "Host shutdown can release caches without invalidating reads")
            assertTrue(ModifierPluginWorkers.cacheSnapshots(store).isEmpty())
        } finally {
            firstOwner.close(); secondOwner.close()
            preferences.removeNode()
            ModifierPluginStore.deleteTree(directory)
        }
    }

    @Test fun slowOrFailedProcessRetirementDoesNotBlockPublicationAndCanRetry() = runBlocking {
        val directory = Files.createTempDirectory("workspace-retirement-")
        val preferences = Preferences.userRoot().node("psd2live-tests/retirement/${UUID.randomUUID()}")
        val store = ModifierPluginStore(directory.resolve("installed"), preferences)
        val started = java.util.concurrent.CountDownLatch(1)
        val resume = java.util.concurrent.CountDownLatch(1)
        val failOnce = java.util.concurrent.atomic.AtomicBoolean(true)
        val queue = WorkerRetirementQueue(beforeAttempt = {
            started.countDown()
            check(resume.await(10, java.util.concurrent.TimeUnit.SECONDS))
            if (failOnce.getAndSet(false)) throw java.io.IOException("simulated process exit failure")
        })
        val owner = ModifierBuildCacheOwner(store, queue)
        val session = owner.workerSession()
        try {
            val entry = store.install(createSyntheticPluginZip(directory, "shared.one", "shared.one/op", 1).toString())
            val document = Characters.head(physics = false)
            val base = WorkspacePreviewBuilder().build(document)
            val model = base.copy().also { it.modifierBuildOwner = owner }
            val runtime = WorkspaceRuntime<RigPreviewModel>({ base })
            runtime.install(runtime.state.value.state, "first", document, model)
            val instance = RigModifierInstance("test", "shared.one/op", 1,
                listOf(ModifierTarget("mesh", "m1")), plugin = entry.reference)
            ModifierPluginWorkers.evaluate(entry, entry.manifest.algorithms.single(), syntheticWorkerRig(), instance, {}, store, session = session)
            val pid = ModifierPluginWorkers.cacheSnapshots(store).single().pid
            val captured = runtime.capture()
            java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor().use { executor ->
                val installed = executor.submit<WorkspaceCapture<RigPreviewModel>> {
                    runtime.install(captured.state, "replacement", document, base)
                }.get(5, java.util.concurrent.TimeUnit.SECONDS)
                assertTrue(started.await(10, java.util.concurrent.TimeUnit.SECONDS))
                assertEquals(installed, executor.submit<WorkspaceCapture<RigPreviewModel>> { runtime.capture() }
                    .get(5, java.util.concurrent.TimeUnit.SECONDS))
                assertSame(model.rig, captured.model.rig)
                assertTrue(ProcessHandle.of(pid).orElseThrow().isAlive, "Disposal is deliberately held")
                resume.countDown()
                session.awaitReleased()
                assertFailsWith<java.util.concurrent.ExecutionException> { queue.await(store.root) }
                val failure = queue.snapshots(store.root).single()
                assertEquals("failed", failure.state)
                assertTrue(failure.failure!!.contains("simulated process exit failure"))
                assertEquals("replacement", runtime.capture().projectId)
                queue.retryFailed(store.root)
                queue.await(store.root)
                assertTrue(queue.snapshots(store.root).isEmpty())
                assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
            }
        } finally {
            resume.countDown()
            failOnce.set(false)
            owner.close(); session.awaitReleased()
            queue.retryFailed(store.root); queue.await(store.root)
            preferences.removeNode()
            ModifierPluginStore.deleteTree(directory)
        }
    }
}
