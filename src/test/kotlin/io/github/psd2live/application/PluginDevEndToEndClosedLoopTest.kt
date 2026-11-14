package io.github.psd2live.application

import io.github.psd2live.core.modifier.ModifierPluginReference
import io.github.psd2live.core.modifier.ModifierPluginStore
import io.github.psd2live.core.modifier.PluginDevArtifactStore
import io.github.psd2live.core.modifier.PluginDevContracts
import io.github.psd2live.core.modifier.PluginDevRetention
import io.github.psd2live.project.MutationAuthor
import io.github.psd2live.scenario.Characters
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end closed-loop test for custom plugin development:
 * contract -> scaffold -> register -> build A -> add to synthetic project -> verify PuppetModel A
 * -> edit to B -> build B -> modifier_code_plan/apply -> verify PuppetModel B
 * -> undo (reverts to A) -> redo (restores B)
 * -> plugin_dev_detach + delete source dir -> save .psd2live -> reopen in fresh WorkspaceHost -> verify B replays.
 */
class PluginDevEndToEndClosedLoopTest {
    @TempDir
    lateinit var temp: Path

    private val ctx = WorkspaceOperationContext(MutationAuthor.USER)
    private var reqSeq = 0

    private suspend fun callOp(
        operations: WorkspaceOperations,
        id: String,
        request: JsonObject = JsonObject(emptyMap()),
        host: WorkspaceHost<*>? = null,
        timeoutMs: Long = 180_000L,
    ): JsonObject {
        val def = operations.registry.definition(id)
        val extra = linkedMapOf<String, kotlinx.serialization.json.JsonElement>()
        if (def.kind != WorkspaceOperationKind.QUERY || def.jobBacked) {
            if ("request_id" !in request) {
                extra["request_id"] = JsonPrimitive("e2e-${++reqSeq}")
            }
            if (def.workspaceBound && host != null) {
                if ("project_id" !in request) {
                    extra["project_id"] = host.snapshot().projectId?.let(::JsonPrimitive) ?: JsonNull
                }
                if ("state" !in request) {
                    extra["state"] = JsonPrimitive(host.snapshot().state)
                }
            }
        }
        val payload = if (extra.isEmpty()) request else JsonObject(request + extra)
        val out = operations.registry.invoke(id, payload, ctx)
        if (!def.jobBacked) return out.data
        val jobId = out.data.getValue("id").jsonPrimitive.content
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val remaining = (deadline - System.currentTimeMillis()).coerceIn(1_000L, 30_000L)
            val snap = operations.registry.invoke(
                "job_wait",
                buildJsonObject {
                    put("id", jobId)
                    put("timeout_ms", remaining)
                },
                ctx,
            ).data
            when (val status = snap.getValue("status").jsonPrimitive.content) {
                "completed" -> return snap.getValue("result").jsonObject
                "failed", "cancelled" -> error("$id job $jobId $status: $snap")
                else -> if (System.currentTimeMillis() >= deadline) error("$id job $jobId timed out: $snap")
            }
        }
    }

    private fun workerSource(
        packageName: String,
        algorithmType: String,
        opacityMultiplier: Float,
        drawOrderDelta: Float,
    ): String = """
        package $packageName

        import io.github.psd2live.algorithm.worker.AlgorithmWorker
        import io.github.psd2live.core.modifier.ChangingModifierAlgorithmPlugin
        import io.github.psd2live.core.modifier.ModifierAlgorithmContext
        import io.github.psd2live.core.modifier.ModifierModelChanges
        import io.github.psd2live.core.modifier.ModifierObjectChanges
        import io.github.psd2live.core.modifier.ModifierObjectUpdate
        import io.github.psd2live.core.modifier.RigModifierInstance
        import io.github.psd2live.format.model.RigIR
        import kotlinx.serialization.json.floatOrNull
        import kotlinx.serialization.json.jsonPrimitive

        object PluginWorker {
            @JvmStatic
            fun main(args: Array<String>) {
                val plugin = object : ChangingModifierAlgorithmPlugin {
                    override fun changes(
                        input: RigIR,
                        instance: RigModifierInstance,
                        context: ModifierAlgorithmContext,
                        checkpoint: () -> Unit,
                    ): ModifierModelChanges {
                        val factor = instance.config["factor"]?.jsonPrimitive?.floatOrNull ?: 0.5f
                        val targetIds = instance.targets.mapTo(HashSet()) { it.id }
                        val updates = input.meshes.filter { it.id in targetIds }.map { mesh ->
                            checkpoint()
                            ModifierObjectUpdate(
                                mesh,
                                mesh.copy(
                                    opacity = (mesh.opacity * factor * ${opacityMultiplier}f).coerceIn(0f, 1f),
                                    drawOrder = mesh.drawOrder + ${drawOrderDelta}f,
                                ),
                            )
                        }
                        return ModifierModelChanges(meshes = ModifierObjectChanges(updated = updates))
                    }
                }
                AlgorithmWorker.run(mapOf(("$algorithmType" to 1) to plugin))
            }
        }
    """.trimIndent() + "\n"

    @Test
    fun `custom plugin closed loop survives build swap undo redo detach delete and archive reopen`() = runBlocking {
        val uniqueSuffix = System.nanoTime().toString()
        val pluginId = "custom.e2e_fx_$uniqueSuffix"
        val algorithmType = "$pluginId/custom_tint"
        val packageName = "custom.e2e_fx_$uniqueSuffix"
        val pluginDir = temp.resolve("plugin-src")
        val filesDir = Files.createDirectories(temp.resolve("files"))
        val archivePath = temp.resolve("closed-loop.psd2live")

        var registeredSourceId: String? = null
        var operationsRef: WorkspaceOperations? = null
        var expectedBOpacity = 0f
        var expectedBDrawOrder = 0f
        try {
            HeadlessPresentation().use { presentation ->
                WorkspaceHost(presentation, temp.resolve("store-1")).use { host ->
                    presentation.attach(host)
                    WorkspaceOperations(host).use { operations ->
                        operationsRef = operations

                        // 1. plugin_dev_contract -> plugin_dev_scaffold -> plugin_dev_register
                        val contract = callOp(
                            operations,
                            "plugin_dev_contract",
                            buildJsonObject { put("include_example", true) },
                        )
                        assertEquals(PluginDevContracts.HOST_API_VERSION, contract.getValue("host_api_version").jsonPrimitive.content)
                        assertTrue(contract.getValue("targets").jsonArray.any { it.jsonPrimitive.content == "mesh" })
                        assertNotNull(contract["minimal_example"]?.jsonObject?.get("worker_source"))

                        val scaffolded = callOp(
                            operations,
                            "plugin_dev_scaffold",
                            buildJsonObject {
                                put("target_dir", pluginDir.toString())
                                put("plugin_id", pluginId)
                                put("name", "E2E Custom FX")
                                put("algorithm_type", algorithmType)
                                put("algorithm_name", "Custom Opacity & DrawOrder")
                                put("base_version", "0.1.0")
                                putJsonArray("targets") { add("mesh") }
                                put("package_name", packageName)
                            },
                        )
                        assertEquals(pluginId, scaffolded.getValue("plugin_id").jsonPrimitive.content)
                        val workerFile = pluginDir.resolve("src/main/kotlin/${packageName.replace('.', '/')}/PluginWorker.kt")
                        assertTrue(Files.isRegularFile(workerFile))

                        val registered = callOp(
                            operations,
                            "plugin_dev_register",
                            buildJsonObject {
                                put("source", pluginDir.toString())
                                put("mode", "managed_build")
                            },
                        )
                        val sourceId = registered.getValue("source_id").jsonPrimitive.content
                        registeredSourceId = sourceId

                        // 2. Implement Version A (not in built-in presets, reading declarative config `factor`) -> build A
                        Files.writeString(workerFile, workerSource(packageName, algorithmType, opacityMultiplier = 0.5f, drawOrderDelta = 11f))
                        val buildA = callOp(
                            operations,
                            "plugin_dev_build",
                            buildJsonObject { put("source_id", sourceId) },
                        )
                        assertFalse(buildA.getValue("unchanged").jsonPrimitive.boolean)
                        val artifactA = buildA.getValue("artifact").jsonObject
                        val artifactAId = artifactA.getValue("artifact_id").jsonPrimitive.content
                        val refAJson = artifactA.getValue("packages").jsonArray.single().jsonObject
                        val refA = Json.decodeFromJsonElement(ModifierPluginReference.serializer(), refAJson)
                        assertEquals(pluginId, refA.id)

                        // 3. Open a minimal synthetic WorkspaceRuntime project with a mesh -> modifier_add with artifact A
                        suspend fun call(id: String, request: JsonObject = JsonObject(emptyMap())) =
                            callOp(operations, id, request, host = host)
                        suspend fun state(): String =
                            call("workspace_inspect", buildJsonObject { put("scope", "project") })
                                .getValue("state").jsonPrimitive.content

                        call(
                            "project_create",
                            buildJsonObject {
                                put("state", state())
                                put("width", 64)
                                put("height", 64)
                                put("discard_unsaved", true)
                            },
                        )
                        call(
                            "material_import",
                            buildJsonObject {
                                put("state", state())
                                putJsonArray("paths") { add(Characters.disc(filesDir, size = 32).toString()) }
                            },
                        )
                        val materialId = call("material_list")
                            .getValue("items").jsonArray.single().jsonObject.getValue("id").jsonPrimitive.content
                        call(
                            "layer_place",
                            buildJsonObject {
                                put("state", state())
                                put("material_id", materialId)
                                put("layer_id", "disc")
                            },
                        )
                        call("rig_generate", buildJsonObject { put("state", state()) })

                        val stackBefore = call("modifier_stack")
                        if (stackBefore.getValue("legacy").jsonPrimitive.boolean) {
                            call("modifier_convert", buildJsonObject { put("state", state()) })
                        }

                        val meshBefore = host.currentPuppet()!!.drawables.single()
                        val baseOpacity = meshBefore.opacity
                        val baseDrawOrder = meshBefore.drawOrder

                        val instanceId = "e2e_custom_inst"
                        call(
                            "modifier_add",
                            buildJsonObject {
                                put("state", state())
                                put("id", instanceId)
                                put("type", algorithmType)
                                put("version", 1)
                                put("plugin", refAJson)
                                putJsonArray("targets") {
                                    addJsonObject {
                                        put("kind", "mesh")
                                        put("id", meshBefore.id.raw)
                                    }
                                }
                                putJsonObject("config") {
                                    put("factor", 0.4)
                                }
                            },
                        )

                        val meshAfterA = host.currentPuppet()!!.drawables.single { it.id == meshBefore.id }
                        assertEquals(baseOpacity * 0.4f * 0.5f, meshAfterA.opacity, 1e-4f, "Worker A opacity should apply")
                        assertEquals(baseDrawOrder + 11f, meshAfterA.drawOrder, 1e-3f, "Worker A drawOrder should apply")
                        val historyNodeA = requireNotNull(host.currentHistoryHead())

                        // 4. Edit PluginWorker.kt to Version B -> plugin_dev_build -> artifact B
                        Files.writeString(workerFile, workerSource(packageName, algorithmType, opacityMultiplier = 1.5f, drawOrderDelta = 27f))
                        val buildB = call(
                            "plugin_dev_build",
                            buildJsonObject { put("source_id", sourceId) },
                        )
                        assertFalse(buildB.getValue("unchanged").jsonPrimitive.boolean)
                        val artifactB = buildB.getValue("artifact").jsonObject
                        val artifactBId = artifactB.getValue("artifact_id").jsonPrimitive.content
                        assertNotEquals(artifactAId, artifactBId)
                        val refBJson = artifactB.getValue("packages").jsonArray.single().jsonObject
                        val refB = Json.decodeFromJsonElement(ModifierPluginReference.serializer(), refBJson)
                        assertNotEquals(refA.digest, refB.digest)

                        // 5. modifier_code_plan -> modifier_code_apply -> verify evaluated PuppetModel reflects B
                        val plan = call(
                            "modifier_code_plan",
                            buildJsonObject {
                                put("artifact_id", artifactBId)
                                putJsonArray("instance_ids") { add(instanceId) }
                            },
                        )
                        assertTrue(plan.getValue("can_apply").jsonPrimitive.boolean, "Plan should be applicable: $plan")
                        val planToken = plan.getValue("plan_token").jsonPrimitive.content

                        val applied = call(
                            "modifier_code_apply",
                            buildJsonObject {
                                put("state", state())
                                put("plan_token", planToken)
                                put("request_id", "e2e-swap-a-to-b-$uniqueSuffix")
                            },
                        )
                        assertTrue(applied.getValue("applied").jsonPrimitive.boolean)
                        val historyNodeB = requireNotNull(host.currentHistoryHead())
                        assertNotEquals(historyNodeA, historyNodeB)

                        val meshAfterB = host.currentPuppet()!!.drawables.single { it.id == meshBefore.id }
                        expectedBOpacity = baseOpacity * 0.4f * 1.5f
                        expectedBDrawOrder = baseDrawOrder + 27f
                        assertEquals(expectedBOpacity, meshAfterB.opacity, 1e-4f, "Worker B opacity should apply")
                        assertEquals(expectedBDrawOrder, meshAfterB.drawOrder, 1e-3f, "Worker B drawOrder should apply")

                        // 6. undo -> verify PuppetModel reverts to A; redo -> verify B
                        host.checkoutHistory(historyNodeA, MutationAuthor.USER)
                        val meshAfterUndo = host.currentPuppet()!!.drawables.single { it.id == meshBefore.id }
                        assertEquals(baseOpacity * 0.4f * 0.5f, meshAfterUndo.opacity, 1e-4f, "Undo should revert to Worker A opacity")
                        assertEquals(baseDrawOrder + 11f, meshAfterUndo.drawOrder, 1e-3f, "Undo should revert to Worker A drawOrder")

                        host.checkoutHistory(historyNodeB, MutationAuthor.USER)
                        val meshAfterRedo = host.currentPuppet()!!.drawables.single { it.id == meshBefore.id }
                        assertEquals(baseOpacity * 0.4f * 1.5f, meshAfterRedo.opacity, 1e-4f, "Redo should restore Worker B opacity")
                        assertEquals(baseDrawOrder + 27f, meshAfterRedo.drawOrder, 1e-3f, "Redo should restore Worker B drawOrder")

                        // 7. plugin_dev_detach + delete scaffolded source directory -> save .psd2live archive
                        val detached = call(
                            "plugin_dev_detach",
                            buildJsonObject { put("source_id", sourceId) },
                        )
                        assertTrue(detached.getValue("detached").jsonPrimitive.boolean)
                        registeredSourceId = null

                        ModifierPluginStore.deleteTree(pluginDir)
                        assertFalse(Files.exists(pluginDir), "Scaffolded source dir should be deleted")

                        val adoptedB = PluginDevArtifactStore.get(artifactBId)
                        assertNotNull(adoptedB)
                        assertEquals(PluginDevRetention.ADOPTED, adoptedB.retention)
                        assertNull(adoptedB.expiresAt)
                        assertTrue(ModifierPluginStore.shared.defaultPackages().none { it.reference.id == pluginId })

                        call(
                            "project_save_as",
                            buildJsonObject {
                                put("state", state())
                                put("path", archivePath.toString())
                            },
                        )
                        assertTrue(Files.isRegularFile(archivePath))
                    }
                }
            }

            // Reopen in a fresh WorkspaceHost / WorkspaceRuntime and verify B still replays from the adopted archive
            HeadlessPresentation().use { freshPresentation ->
                WorkspaceHost(freshPresentation, temp.resolve("store-2")).use { freshHost ->
                    freshPresentation.attach(freshHost)
                    WorkspaceOperations(freshHost).use { freshOps ->
                        val initialState = callOp(freshOps, "workspace_inspect", buildJsonObject { put("scope", "project") }, host = freshHost)
                            .getValue("state").jsonPrimitive.content
                        callOp(
                            freshOps,
                            "project_open",
                            buildJsonObject {
                                put("state", initialState)
                                put("path", archivePath.toString())
                                put("discard_unsaved", true)
                            },
                            host = freshHost,
                        )

                        val reopenedMesh = freshHost.currentPuppet()!!.drawables.single()
                        assertEquals(expectedBOpacity, reopenedMesh.opacity, 1e-4f, "Reopened archive should replay Worker B opacity")
                        assertEquals(expectedBDrawOrder, reopenedMesh.drawOrder, 1e-3f, "Reopened archive should replay Worker B drawOrder")
                    }
                }
            }
        } finally {
            val ops = operationsRef
            val sourceId = registeredSourceId
            if (ops != null && sourceId != null) {
                runCatching {
                    callOp(ops, "plugin_dev_detach", buildJsonObject { put("source_id", sourceId) })
                }
            }
        }
    }
}
