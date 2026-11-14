package io.github.psd2live.core.modifier

import io.github.psd2live.format.model.*
import java.nio.file.Files
import java.util.UUID
import java.util.prefs.Preferences
import java.util.concurrent.*
import kotlin.test.*

class ModifierWorkerCacheTest {
    private fun fixture(protocol: Int = 2, block: (ModifierPluginStore, InstalledModifierPlugin) -> Unit) {
        val root = Files.createTempDirectory("worker-cache-test-")
        val preferences = Preferences.userRoot().node("psd2live-test/worker-cache/${UUID.randomUUID()}")
        val store = ModifierPluginStore(root.resolve("installed"), preferences)
        var entry: InstalledModifierPlugin? = null
        try {
            ModifierPluginWorkers.geometryBudget(store, 2048)
            entry = store.install(createSyntheticPluginZip(root, "shared.one", "shared.one/op", 1, protocol).toString())
            block(store, entry)
        } finally {
            entry?.let { ModifierPluginWorkers.stop(it.reference) }
            preferences.removeNode()
            ModifierPluginStore.deleteTree(root)
        }
    }

    private fun instance(entry: InstalledModifierPlugin) = RigModifierInstance("edit", "shared.one/op", 1,
        listOf(ModifierTarget("mesh", "m1")), plugin = entry.reference)

    @Test fun bothWireProtocolsBoundStrongGeometryReferencesAndResetSafely() {
        for (protocol in listOf(1, 2)) fixture(protocol) { store, entry ->
            ModifierPluginWorkers.openSession(store).use { session ->
                val source = syntheticWorkerRig()
                val mesh = source.meshes.single()
                repeat(24) { edit ->
                    val input = source.copy(meshes = listOf(mesh.copy(geometry = mesh.geometry!!.copy(
                        positions = Floats.values(0f, 0f, 1f + edit * .01f, 0f, 0f, 1f)))))
                    val result = ModifierPluginWorkers.evaluate(entry, entry.manifest.algorithms.single(), input,
                        instance(entry), {}, store, session = session)
                    assertEquals(.25f, result.meshes.single().opacity)
                    assertEquals(input.meshes.single().geometry, result.meshes.single().geometry)
                    val memory = ModifierPluginWorkers.cacheSnapshots(store).single()
                    assertTrue(memory.geometry.retainedBytes in 1..2048, "protocol $protocol: $memory")
                }
                val before = ModifierPluginWorkers.cacheSnapshots(store).single()
                assertTrue(before.geometry.evictions > 0, "actual worker geometry cache did not evict")
                ModifierPluginWorkers.releaseCaches(store)
                val cleared = ModifierPluginWorkers.cacheSnapshots(store).single()
                assertEquals(0L, cleared.geometry.retainedBytes)
                assertEquals(0L, cleared.protocolBlobBytes)
                assertEquals(0, cleared.readEntries)
                val result = ModifierPluginWorkers.evaluate(entry, entry.manifest.algorithms.single(), source,
                    instance(entry), {}, store, session = session)
                assertEquals(.25f, result.meshes.single().opacity)
                assertEquals(before.pid, ModifierPluginWorkers.cacheSnapshots(store).single().pid,
                    "cache release should reset the protocol without restarting the worker")
                session.close()
                session.awaitReleased()
            }
            ModifierPluginWorkers.awaitRetirements(store)
            assertTrue(ModifierPluginWorkers.cacheSnapshots(store).isEmpty(), "last owner must release process and caches")
        }
    }

    @Test fun closingOneSessionPreservesOtherSessionAndActiveFrame() = fixture { store, entry ->
        val first = ModifierPluginWorkers.openSession(store)
        val second = ModifierPluginWorkers.openSession(store)
        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val input = syntheticWorkerRig()
            ModifierPluginWorkers.evaluate(entry, entry.manifest.algorithms.single(), input, instance(entry), {}, store, session = second)
            val pid = ModifierPluginWorkers.cacheSnapshots(store).single().pid
            val active = CountDownLatch(1)
            val resume = CountDownLatch(1)
            val request = executor.submit<RigIR> {
                var checkpoints = 0
                ModifierPluginWorkers.evaluate(entry, entry.manifest.algorithms.single(), input, instance(entry), {
                    if (++checkpoints == 2) { active.countDown(); check(resume.await(10, TimeUnit.SECONDS)) }
                }, store, session = first)
            }
            try {
                assertTrue(active.await(10, TimeUnit.SECONDS))
                ModifierPluginWorkers.releaseCaches(store)
                first.close()
                assertFalse(request.isDone)
                assertEquals(pid, ModifierPluginWorkers.cacheSnapshots(store).single().pid)
                resume.countDown()
                assertEquals(.25f, request.get(10, TimeUnit.SECONDS).meshes.single().opacity)
                assertEquals(0L, ModifierPluginWorkers.cacheSnapshots(store).single().geometry.retainedBytes)
                val result = ModifierPluginWorkers.evaluate(entry, entry.manifest.algorithms.single(), input,
                    instance(entry), {}, store, session = second)
                assertEquals(.25f, result.meshes.single().opacity)
                assertEquals(pid, ModifierPluginWorkers.cacheSnapshots(store).single().pid)
                assertFailsWith<IllegalStateException> {
                    ModifierPluginWorkers.evaluate(entry, entry.manifest.algorithms.single(), input,
                        instance(entry), {}, store, session = first)
                }
            } finally { resume.countDown(); first.close(); second.close(); first.awaitReleased(); second.awaitReleased() }
        }
        ModifierPluginWorkers.awaitRetirements(store)
        assertTrue(ModifierPluginWorkers.cacheSnapshots(store).isEmpty())
    }

    @Test fun cancelledQueuedOwnerDoesNotCloseActiveOwnersWorker() = fixture { store, entry ->
        ModifierPluginWorkers.openSession(store).use { first ->
            ModifierPluginWorkers.openSession(store).use { queued ->
                Executors.newVirtualThreadPerTaskExecutor().use { executor ->
                    val active = CountDownLatch(1)
                    val resume = CountDownLatch(1)
                    val input = syntheticWorkerRig()
                    val request = executor.submit<RigIR> {
                        var checkpoints = 0
                        ModifierPluginWorkers.evaluate(entry, entry.manifest.algorithms.single(), input, instance(entry), {
                            if (++checkpoints == 2) { active.countDown(); check(resume.await(10, TimeUnit.SECONDS)) }
                        }, store, session = first)
                    }
                    try {
                        assertTrue(active.await(10, TimeUnit.SECONDS))
                        var checks = 0
                        assertFailsWith<CancellationException> {
                            ModifierPluginWorkers.evaluate(entry, entry.manifest.algorithms.single(), input, instance(entry), {
                                if (++checks == 2) throw CancellationException("queued cancellation")
                            }, store, session = queued)
                        }
                        queued.close()
                        assertFalse(request.isDone)
                        resume.countDown()
                        assertEquals(.25f, request.get(10, TimeUnit.SECONDS).meshes.single().opacity)
                    } finally { resume.countDown() }
                }
                queued.close()
                queued.awaitReleased()
            }
            first.close()
            first.awaitReleased()
        }
        ModifierPluginWorkers.awaitRetirements(store)
        assertTrue(ModifierPluginWorkers.cacheSnapshots(store).isEmpty())
    }

    @Test fun lastSessionCloseDefersExitUntilActiveRequestFinishes() = fixture { store, entry ->
        val owner = ModifierPluginWorkers.openSession(store)
        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val active = CountDownLatch(1)
            val resume = CountDownLatch(1)
            val request = executor.submit<RigIR> {
                var checks = 0
                ModifierPluginWorkers.evaluate(entry, entry.manifest.algorithms.single(), syntheticWorkerRig(), instance(entry), {
                    if (++checks == 2) { active.countDown(); check(resume.await(10, TimeUnit.SECONDS)) }
                }, store, session = owner)
            }
            try {
                assertTrue(active.await(10, TimeUnit.SECONDS))
                owner.close()
                owner.awaitReleased()
                assertFalse(request.isDone)
                assertEquals(1, ModifierPluginWorkers.cacheSnapshots(store).size)
                resume.countDown()
                assertEquals(.25f, request.get(10, TimeUnit.SECONDS).meshes.single().opacity)
                ModifierPluginWorkers.awaitRetirements(store)
                assertTrue(ModifierPluginWorkers.cacheSnapshots(store).isEmpty())
            } finally { resume.countDown(); owner.close() }
        }
    }
}
