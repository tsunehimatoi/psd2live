package io.github.psd2live.core.modifier

import io.github.psd2live.application.WorkspaceBackend
import io.github.psd2live.application.WorkspaceDocumentOperation
import io.github.psd2live.application.WorkspaceModifierCodeSwap
import io.github.psd2live.application.WorkspaceQueries
import io.github.psd2live.format.model.*
import io.github.psd2live.project.WorkspaceMutationResult
import io.github.psd2live.project.WorkspaceProjectSnapshot
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.prefs.Preferences

class PluginDevSwapAndIsolationTest {
    private val json = Json { encodeDefaults = true }

    private fun writeStagedPlugin(
        dir: Path,
        id: String,
        version: String,
        algorithmType: String,
        algorithmVersion: Int = 1,
        configSchema: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("strength") { put("type", "number") }
            }
        },
        defaults: JsonObject = buildJsonObject { put("strength", 1.0) },
    ) {
        Files.createDirectories(dir)
        val manifestObj = java.util.jar.Manifest().apply {
            mainAttributes[java.util.jar.Attributes.Name.MANIFEST_VERSION] = "1.0"
            mainAttributes[java.util.jar.Attributes.Name.MAIN_CLASS] = TestProtocol2Worker::class.java.name
            mainAttributes[java.util.jar.Attributes.Name.CLASS_PATH] = System.getProperty("java.class.path")
                .split(java.io.File.pathSeparator)
                .filter { it.isNotBlank() }
                .joinToString(" ") { Path.of(it).toUri().toASCIIString() }
        }
        val jarBytes = java.io.ByteArrayOutputStream().apply {
            java.util.jar.JarOutputStream(this).use { jar ->
                jar.putNextEntry(java.util.jar.JarEntry("META-INF/MANIFEST.MF").apply { time = 0 })
                manifestObj.write(jar)
                jar.closeEntry()
            }
        }.toByteArray()
        Files.write(dir.resolve("worker.jar"), jarBytes)
        val jarHash = ModifierPluginStore.hash(jarBytes)
        val manifest = ModifierPluginManifest(
            protocol = 2,
            id = id,
            name = id,
            version = version,
            commands = mapOf("jvm" to listOf("\$JAVA", "-jar", "worker.jar")),
            files = mapOf("worker.jar" to jarHash),
            algorithms = listOf(
                ModifierPluginAlgorithm(
                    type = algorithmType,
                    version = algorithmVersion,
                    name = algorithmType,
                    targets = setOf("mesh"),
                    reads = setOf("mesh"),
                    writes = setOf("mesh"),
                    configSchema = configSchema,
                    defaults = defaults,
                )
            ),
        )
        Files.writeString(dir.resolve("plugin.json"), json.encodeToString(manifest))
    }

    @Test
    fun `artifact store isolates candidate TTL and preserves adopted archives`(@TempDir tempDir: Path) {
        val devStore = PluginDevArtifactStore.Store(tempDir.resolve("dev-artifacts"))
        val pluginStore = ModifierPluginStore(
            tempDir.resolve("plugins"),
            Preferences.userRoot().node("io.github.psd2live.test.${System.nanoTime()}"),
            devArtifactResolver = devStore::resolve,
        )
        val revBefore = pluginStore.revision.value

        val stagedA = tempDir.resolve("staged-a")
        writeStagedPlugin(stagedA, "custom.test", "1.0.0+dev.1", "custom.test/op")
        val artA = devStore.seal(stagedA, "src-1", ttlMillis = 50L)
        assertEquals(PluginDevRetention.CANDIDATE, artA.retention)
        assertNotNull(artA.expiresAt)

        // Dev artifacts must never bump ModifierPluginStore.revision or enter defaultPackages().
        assertEquals(revBefore, pluginStore.revision.value)
        assertTrue(pluginStore.defaultPackages().none { it.reference.id == "custom.test" })
        assertTrue(pluginStore.packages().none { it.reference.id == "custom.test" })

        // Exact reference lookup and catalog availability work.
        val refA = artA.packages.single()
        assertNotNull(pluginStore.findRunnable(refA))
        assertTrue(ModifierPluginCatalog.available(
            RigModifierInstance("m1", "custom.test/op", 1, listOf(ModifierTarget("mesh", "mesh0")), plugin = refA),
            pluginStore,
        ))

        // Leased candidate survives pruning; unleased expired candidate is pruned.
        devStore.acquireLease(artA.artifactId)
        val future = (artA.expiresAt ?: System.currentTimeMillis()) + 1_000L
        assertEquals(0, devStore.pruneExpiredCandidates(future))
        devStore.releaseLease(artA.artifactId)

        // Adopt A -> expiresAt becomes null and survives pruning forever.
        val adoptedA = devStore.adopt(artA.artifactId, "proj-1")
        assertEquals(PluginDevRetention.ADOPTED, adoptedA.retention)
        assertNull(adoptedA.expiresAt)
        assertEquals(0, devStore.pruneExpiredCandidates(future + 100_000L))
        assertNotNull(devStore.get(artA.artifactId))

        // Unadopted candidate B expires and is pruned.
        val stagedB = tempDir.resolve("staged-b")
        writeStagedPlugin(stagedB, "custom.test", "1.0.0+dev.2", "custom.test/op", defaults = buildJsonObject { put("strength", 2.0) })
        val artB = devStore.seal(stagedB, "src-1", ttlMillis = 10L)
        val pruned = devStore.pruneExpiredCandidates((artB.expiresAt ?: System.currentTimeMillis()) + 1_000L)
        assertEquals(1, pruned)
        assertNull(devStore.get(artB.artifactId))
        assertNotNull(devStore.get(artA.artifactId))
    }

    @Test
    fun `worker executionGeneration isolates candidate worker processes`(@TempDir tempDir: Path) {
        val devStore = PluginDevArtifactStore.Store(tempDir.resolve("dev-artifacts"))
        val pluginStore = ModifierPluginStore(
            tempDir.resolve("plugins"),
            Preferences.userRoot().node("io.github.psd2live.test.${System.nanoTime()}"),
            devArtifactResolver = devStore::resolve,
        )
        val staged = tempDir.resolve("staged")
        writeStagedPlugin(staged, "shared.one", "1.0.0+dev.1", "shared.one/op")
        val art = devStore.seal(staged, "src-gen")
        val ref = art.packages.single()
        val entry = requireNotNull(pluginStore.findRunnable(ref))
        val algo = entry.manifest.algorithms.single()
        val rig = syntheticWorkerRig()
        val instance = RigModifierInstance("edit", "shared.one/op", 1, listOf(ModifierTarget("mesh", "m1")), plugin = ref)

        val gen1 = ModifierPluginWorkers.nextExecutionGeneration()
        val gen2 = ModifierPluginWorkers.nextExecutionGeneration()
        assertNotEquals(gen1, gen2)

        ModifierPluginWorkers.openSession(pluginStore, executionGeneration = gen1).use { s1 ->
            ModifierPluginWorkers.openSession(pluginStore, executionGeneration = gen2).use { s2 ->
                val out1 = ModifierPluginWorkers.evaluate(entry, algo, rig, instance, {}, pluginStore, session = s1)
                val out2 = ModifierPluginWorkers.evaluate(entry, algo, rig, instance, {}, pluginStore, session = s2)
                assertEquals(0.25f, out1.meshes.single().opacity)
                assertEquals(0.25f, out2.meshes.single().opacity)
                val snapshots = ModifierPluginWorkers.cacheSnapshots(pluginStore)
                assertEquals(2, snapshots.map { it.pid }.distinct().size)
            }
        }
        ModifierPluginWorkers.awaitRetirements(pluginStore)
    }

    @Test
    fun `stack rebind preserves identity config overrides and order while update forbids plugin change`() {
        val refA = ModifierPluginReference("custom.test", "1.0.0+dev.1", "a".repeat(64))
        val refB = ModifierPluginReference("custom.test", "1.0.0+dev.2", "b".repeat(64))
        val overrideCmd = buildJsonObject { put("op", "structure") }
        val stack = RigModifierStack()
            .add("custom.test/op", 1, listOf(ModifierTarget("mesh", "m0")), buildJsonObject { put("strength", 0.5) }, plugin = refA)
            .add("swing", 1, listOf(ModifierTarget("mesh", "m1")))
            .withOverrides("m1", listOf(overrideCmd))

        // update must still forbid changing plugin
        assertThrows(IllegalArgumentException::class.java) {
            stack.update("m1") { it.copy(plugin = refB) }
        }

        // rebind preserves everything except plugin/version
        val rebound = stack.rebind("m1", refB, 1)
        assertEquals(listOf("m1", "m2"), rebound.instances.map { it.id })
        val m1 = rebound.instances[0]
        assertEquals(refB, m1.plugin)
        assertEquals(buildJsonObject { put("strength", 0.5) }, m1.config)
        assertEquals(listOf(ModifierTarget("mesh", "m0")), m1.targets)
        assertEquals(listOf(overrideCmd), rebound.overrides["m1"])
    }

    @Test
    fun `code swap plan and apply validate compatibility, idempotency, state conflict, and appliedCapabilities`(@TempDir tempDir: Path) {
        val stagedA = tempDir.resolve("staged-a")
        val stagedB = tempDir.resolve("staged-b")
        val stagedBadConfig = tempDir.resolve("staged-bad-cfg")
        writeStagedPlugin(stagedA, "custom.swap", "1.0.0+dev.1", "custom.swap/op")
        writeStagedPlugin(stagedB, "custom.swap", "1.0.0+dev.2", "custom.swap/op", defaults = buildJsonObject { put("strength", 2.0) })
        writeStagedPlugin(
            stagedBadConfig,
            "custom.swap",
            "1.0.0+dev.3",
            "custom.swap/op",
            configSchema = buildJsonObject {
                put("type", "object")
                put("required", JsonArray(listOf(JsonPrimitive("requiredNewField"))))
                putJsonObject("properties") {
                    putJsonObject("requiredNewField") { put("type", "string") }
                }
            },
            defaults = buildJsonObject { put("requiredNewField", "ok") },
        )

        val artA = PluginDevArtifactStore.seal(stagedA, "src-swap")
        val artB = PluginDevArtifactStore.seal(stagedB, "src-swap")
        val artBadCfg = PluginDevArtifactStore.seal(stagedBadConfig, "src-swap")
        val refA = artA.packages.single()

        val initialStack = RigModifierStack()
            .add("custom.swap/op", 1, listOf(ModifierTarget("mesh", "mesh0")), buildJsonObject { put("strength", 1.5) }, plugin = refA)
            .add("swing", 1, listOf(ModifierTarget("mesh", "mesh1")))

        var activeStack = initialStack
        var currentState = "state-1"
        val appliedEdits = ArrayList<List<WorkspaceDocumentOperation>>()

        fun makeSnapshot() = WorkspaceProjectSnapshot(
            projectId = "proj-swap",
            revisionId = "rev-1",
            historyHeadNodeId = "node-1",
            loaded = true,
            inputName = "test.psd",
            canvasWidth = 100,
            canvasHeight = 100,
            busy = false,
            status = "ready",
            selectedLayerId = null,
            layers = emptyList(),
            parameters = emptyList(),
            state = currentState,
        )

        val queries = java.lang.reflect.Proxy.newProxyInstance(
            WorkspaceQueries::class.java.classLoader,
            arrayOf(WorkspaceQueries::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "snapshot" -> makeSnapshot()
                "modifierStack" -> activeStack.toJson()
                "stateToken" -> currentState
                else -> error("Unexpected queries call: ${method.name}")
            }
        } as WorkspaceQueries

        val backend = java.lang.reflect.Proxy.newProxyInstance(
            WorkspaceBackend::class.java.classLoader,
            arrayOf(WorkspaceBackend::class.java),
        ) { _, method, args ->
            when (method.name) {
                "stateToken" -> currentState
                "snapshot" -> makeSnapshot()
                "captureQueries" -> queries
                "applyDocumentEdits" -> {
                    @Suppress("UNCHECKED_CAST")
                    val edits = args[2] as List<WorkspaceDocumentOperation>
                    appliedEdits += edits
                    currentState = "state-2"
                    WorkspaceMutationResult("node-2", "rev-2", emptyList(), args[1] as String, applied = true, state = "state-2", projectId = "proj-swap")
                }
                else -> error("Unexpected call: ${method.name}")
            }
        } as WorkspaceBackend

        // Incompatible config schema blocks plan
        val badPlan = WorkspaceModifierCodeSwap.plan(backend, artBadCfg.artifactId, listOf("m1"))
        assertFalse(badPlan.getValue("can_apply").jsonPrimitive.boolean)
        assertEquals(
            PluginDevErrorCodes.PLUGIN_CONFIG_INCOMPATIBLE,
            badPlan.getValue("blockers").jsonArray.single().jsonObject.getValue("code").jsonPrimitive.content,
        )

        // Applied capability prefix guard blocks plan
        activeStack = initialStack.copy(appliedCapabilities = listOf(initialStack.instances.first().capability()))
        val frozenPlan = WorkspaceModifierCodeSwap.plan(backend, artB.artifactId, listOf("m1"))
        assertFalse(frozenPlan.getValue("can_apply").jsonPrimitive.boolean)
        assertEquals(
            PluginDevErrorCodes.PLUGIN_API_INCOMPATIBLE,
            frozenPlan.getValue("blockers").jsonArray.single().jsonObject.getValue("code").jsonPrimitive.content,
        )

        // Valid plan A -> B
        activeStack = initialStack
        val validPlan = WorkspaceModifierCodeSwap.plan(backend, artB.artifactId, listOf("m1"))
        assertTrue(validPlan.getValue("can_apply").jsonPrimitive.boolean)
        val token = validPlan.getValue("plan_token").jsonPrimitive.content
        assertTrue(token.isNotBlank())
        assertEquals(listOf("m2"), validPlan.getValue("replayed_instance_ids").jsonArray.map { it.jsonPrimitive.content })

        runBlocking {
            val res1 = WorkspaceModifierCodeSwap.apply(backend, "state-1", token, "req-1")
            assertTrue(res1.getValue("applied").jsonPrimitive.boolean)
            assertEquals("state-1", res1.getValue("state_before").jsonPrimitive.content)
            assertEquals("state-2", res1.getValue("state").jsonPrimitive.content)
            assertEquals("node-2", res1.getValue("history_node_id").jsonPrimitive.content)
            assertEquals(1, appliedEdits.size)
            assertEquals(PluginDevRetention.ADOPTED, PluginDevArtifactStore.get(artB.artifactId)?.retention)

            // Idempotent replay with same request_id returns cached result without re-applying
            val resReplay = WorkspaceModifierCodeSwap.apply(backend, "state-1", token, "req-1")
            assertEquals(res1, resReplay)
            assertEquals(1, appliedEdits.size)

            // Different request_id with stale state throws state_conflict
            val conflict = assertThrows(PluginDevException::class.java) {
                runBlocking { WorkspaceModifierCodeSwap.apply(backend, "state-1", token, "req-2") }
            }
            assertEquals(PluginDevErrorCodes.STATE_CONFLICT, conflict.code)
        }
    }
}
