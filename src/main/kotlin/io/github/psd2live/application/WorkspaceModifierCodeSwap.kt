package io.github.psd2live.application

import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.core.modifier.*
import io.github.psd2live.project.MutationAuthor
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeoutException
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal object WorkspaceModifierCodeSwap {
    private const val PLAN_TTL_MILLIS = 5L * 60L * 1000L
    private val hmacKey = ByteArray(32).also { SecureRandom().nextBytes(it) }

    private data class StoredPlan(
        val planId: String,
        val projectId: String,
        val state: String,
        val artifactId: String,
        val buildDigest: String,
        val instanceIds: List<String>,
        val replayedInstanceIds: List<String>,
        val targetReference: ModifierPluginReference,
        val targetVersions: Map<String, Int>,
        val changes: JsonArray,
        val expiresAt: Long,
    )

    private data class IdempotencyEntry(
        val fingerprint: String,
        val result: JsonObject,
    )

    private val plans = ConcurrentHashMap<String, StoredPlan>()
    private val idempotency = ConcurrentHashMap<String, IdempotencyEntry>()

    fun plan(capture: WorkspaceCapture<RigPreviewModel>, artifactId: String, instanceIds: List<String>): JsonObject {
        val rawStack = capture.document.rigEdits.modifierStack
            ?: throw PluginDevException(PluginDevErrorCodes.PLUGIN_API_INCOMPATIBLE, "Project does not use an explicit modifier stack")
        val stack = RigModifierStack.fromJson(rawStack)
        return planInternal(capture.projectId, capture.state, stack, artifactId, instanceIds)
    }

    fun plan(workspace: WorkspaceBackend, artifactId: String, instanceIds: List<String>): JsonObject {
        val queries = workspace.captureQueries()
        if (queries is WorkspaceReadSession) {
            return plan(queries.captureSnapshot(), artifactId, instanceIds)
        }
        val snapshot = queries.snapshot()
        val projectId = snapshot.projectId
            ?: throw PluginDevException(PluginDevErrorCodes.STATE_CONFLICT, "No project is loaded")
        val stack = RigModifierStack.fromJson(queries.modifierStack())
        return planInternal(projectId, snapshot.state, stack, artifactId, instanceIds)
    }

    private fun planInternal(
        projectId: String,
        state: String,
        stack: RigModifierStack,
        artifactId: String,
        instanceIds: List<String>,
    ): JsonObject {
        val artifact = PluginDevArtifactStore.get(artifactId)
            ?: throw PluginDevException(PluginDevErrorCodes.ARTIFACT_MISSING, "Dev artifact not found or expired: $artifactId")
        val targetRef = artifact.packages.firstOrNull()
            ?: throw PluginDevException(PluginDevErrorCodes.ARTIFACT_MISSING, "Dev artifact has no package reference: $artifactId")

        val blockers = ArrayList<PluginDevDiagnostic>()
        val warnings = ArrayList<PluginDevDiagnostic>()
        val changes = ArrayList<JsonObject>()
        val dependencyChanges = ArrayList<JsonObject>()
        val targetVersions = LinkedHashMap<String, Int>()

        if (instanceIds.isEmpty()) {
            blockers += PluginDevDiagnostic(
                code = PluginDevErrorCodes.PLUGIN_API_INCOMPATIBLE,
                severity = "error",
                message = "Select at least one modifier instance to update",
            )
        }

        val selectedInstances = instanceIds.mapNotNull { id ->
            val found = stack.instances.firstOrNull { it.id == id }
            if (found == null) {
                blockers += PluginDevDiagnostic(
                    code = PluginDevErrorCodes.PLUGIN_API_INCOMPATIBLE,
                    severity = "error",
                    message = "Unknown modifier instance: $id",
                    instanceIds = listOf(id),
                )
            }
            found
        }

        // Prefix guard: appliedCapabilities are baked into ordinary content and cannot be rebound in-place.
        val frozenCapabilities = stack.appliedCapabilities.filter { cap ->
            cap.plugin?.id == artifact.manifest.id || artifact.manifest.algorithms.any { it.type == cap.type }
        }
        if (frozenCapabilities.isNotEmpty()) {
            blockers += PluginDevDiagnostic(
                code = PluginDevErrorCodes.PLUGIN_API_INCOMPATIBLE,
                severity = "error",
                message = "Cannot update plugin already baked into appliedCapabilities; checkout history before apply first",
                instanceIds = frozenCapabilities.mapNotNull { it.id },
                packageIds = listOf(artifact.manifest.id),
            )
        }

        // Dependency closure check against host store + artifact overlay.
        val overlayEntry = InstalledModifierPlugin(artifact.manifest, targetRef, artifact.directory, enabled = true)
        val depResolution = ModifierPluginStore.shared.resolution(targetRef, listOf(overlayEntry))
        if (!depResolution.available) {
            blockers += PluginDevDiagnostic(
                code = PluginDevErrorCodes.PLUGIN_DEPENDENCY_INCOMPATIBLE,
                severity = "error",
                message = depResolution.issues.joinToString("; ").ifBlank { "Plugin dependencies are unavailable" },
                packageIds = listOf(artifact.manifest.id),
            )
        }

        for (instance in selectedInstances) {
            val algorithm = artifact.manifest.algorithms.firstOrNull { it.type == instance.type }
            if (algorithm == null) {
                blockers += PluginDevDiagnostic(
                    code = PluginDevErrorCodes.PLUGIN_API_INCOMPATIBLE,
                    severity = "error",
                    message = "Artifact ${artifact.manifest.id} does not provide algorithm type ${instance.type}",
                    instanceIds = listOf(instance.id),
                    packageIds = listOf(artifact.manifest.id),
                )
                continue
            }
            if (algorithm.version != instance.version) {
                blockers += PluginDevDiagnostic(
                    code = PluginDevErrorCodes.PLUGIN_API_INCOMPATIBLE,
                    severity = "error",
                    message = "Algorithm contract version mismatch for ${instance.id}: expected ${instance.version}, got ${algorithm.version}",
                    instanceIds = listOf(instance.id),
                    packageIds = listOf(artifact.manifest.id),
                )
                continue
            }
            if (instance.targets.any { it.kind !in algorithm.targets }) {
                blockers += PluginDevDiagnostic(
                    code = PluginDevErrorCodes.PLUGIN_API_INCOMPATIBLE,
                    severity = "error",
                    message = "Artifact algorithm ${algorithm.type} does not support all targets of ${instance.id}",
                    instanceIds = listOf(instance.id),
                    packageIds = listOf(artifact.manifest.id),
                )
                continue
            }
            val configError = runCatching { ModifierPluginSchema.validate(instance.config, algorithm.configSchema) }.exceptionOrNull()
            if (configError != null) {
                blockers += PluginDevDiagnostic(
                    code = PluginDevErrorCodes.PLUGIN_CONFIG_INCOMPATIBLE,
                    severity = "error",
                    message = configError.message ?: "Existing instance config is incompatible with new configSchema",
                    instanceIds = listOf(instance.id),
                    packageIds = listOf(artifact.manifest.id),
                )
                continue
            }
            targetVersions[instance.id] = algorithm.version
            changes += buildJsonObject {
                put("instance_id", instance.id)
                put("type", instance.type)
                put("from_plugin", instance.plugin?.let { Json.encodeToJsonElement(ModifierPluginReference.serializer(), it) } ?: JsonNull)
                put("to_plugin", Json.encodeToJsonElement(ModifierPluginReference.serializer(), targetRef))
                put("from_version", instance.version)
                put("to_version", algorithm.version)
            }
        }

        // Compute downstream instances that will be re-evaluated after the earliest rebound instance.
        val selectedSet = selectedInstances.mapTo(HashSet()) { it.id }
        val firstChangedIndex = stack.instances.indexOfFirst { it.id in selectedSet }
        val replayedIds = if (firstChangedIndex >= 0) {
            stack.instances.drop(firstChangedIndex + 1)
                .filter { it.enabled && it.id !in selectedSet }
                .map { it.id }
        } else emptyList()

        // Check if any unselected instance uses the same plugin ID and warn.
        stack.instances.filter { it.id !in selectedSet && it.plugin?.id == artifact.manifest.id }.forEach { sibling ->
            warnings += PluginDevDiagnostic(
                code = "unselected_sibling_instance",
                severity = "warning",
                message = "Instance ${sibling.id} uses the same plugin ${artifact.manifest.id} and will keep its previous revision",
                instanceIds = listOf(sibling.id),
                packageIds = listOf(artifact.manifest.id),
            )
        }

        val canApply = blockers.isEmpty() && changes.isNotEmpty()
        val expiresAt = System.currentTimeMillis() + PLAN_TTL_MILLIS
        val planId = UUID.randomUUID().toString()
        val planToken: String? = if (canApply) {
            val stored = StoredPlan(
                planId = planId,
                projectId = projectId,
                state = state,
                artifactId = artifactId,
                buildDigest = artifact.buildDigest,
                instanceIds = selectedInstances.map { it.id },
                replayedInstanceIds = replayedIds,
                targetReference = targetRef,
                targetVersions = targetVersions,
                changes = JsonArray(changes),
                expiresAt = expiresAt,
            )
            if (plans.size >= 256) {
                val now = System.currentTimeMillis()
                plans.entries.removeIf { it.value.expiresAt <= now }
            }
            plans[planId] = stored
            signToken(planId, expiresAt)
        } else null

        return buildJsonObject {
            put("plan_token", planToken?.let(::JsonPrimitive) ?: JsonNull)
            put("expires_at", expiresAt)
            put("state", state)
            put("changes", JsonArray(changes))
            put("dependency_changes", JsonArray(dependencyChanges))
            put("replayed_instance_ids", JsonArray(replayedIds.map(::JsonPrimitive)))
            put("blockers", JsonArray(blockers.map { Json.encodeToJsonElement(PluginDevDiagnostic.serializer(), it) }))
            put("warnings", JsonArray(warnings.map { Json.encodeToJsonElement(PluginDevDiagnostic.serializer(), it) }))
            put("can_apply", canApply)
        }
    }

    suspend fun apply(workspace: WorkspaceBackend, state: String, planToken: String, requestId: String): JsonObject {
        require(requestId.isNotBlank()) { "request_id must not be blank" }
        val (planId, _) = verifyToken(planToken)
        val plan = plans[planId]
            ?: throw PluginDevException(PluginDevErrorCodes.PLAN_EXPIRED, "Code swap plan not found or expired")
        if (System.currentTimeMillis() > plan.expiresAt) {
            plans.remove(planId)
            throw PluginDevException(PluginDevErrorCodes.PLAN_EXPIRED, "Code swap plan has expired")
        }

        val idempotencyKey = "${plan.projectId}:$requestId"
        val fingerprint = "$state:${plan.artifactId}:${plan.buildDigest}:${plan.instanceIds.joinToString(",")}"
        idempotency[idempotencyKey]?.let { existing ->
            if (existing.fingerprint == fingerprint) return existing.result
            throw PluginDevException(PluginDevErrorCodes.REQUEST_ID_REUSED, "request_id '$requestId' was already used for a different operation")
        }

        val currentSnapshot = workspace.snapshot()
        val currentState = workspace.stateToken() ?: currentSnapshot.state
        if (currentState != state || plan.state != state || currentSnapshot.projectId != plan.projectId) {
            throw PluginDevException(
                PluginDevErrorCodes.STATE_CONFLICT,
                "Workspace state changed since plan was created (expected $state, current $currentState)",
            )
        }

        val artifact = PluginDevArtifactStore.get(plan.artifactId)
            ?: throw PluginDevException(PluginDevErrorCodes.ARTIFACT_MISSING, "Dev artifact not found: ${plan.artifactId}")
        if (artifact.buildDigest != plan.buildDigest) {
            throw PluginDevException(PluginDevErrorCodes.ARTIFACT_CHANGED, "Dev artifact changed since plan was created")
        }

        PluginDevArtifactStore.acquireLease(plan.artifactId)
        try {
            val generation = ModifierPluginWorkers.nextExecutionGeneration()
            val edits = plan.instanceIds.map { instanceId ->
                WorkspaceDocumentOperation(
                    "modifier_rebind",
                    buildJsonObject {
                        put("id", instanceId)
                        put("plugin", Json.encodeToJsonElement(ModifierPluginReference.serializer(), plan.targetReference))
                        put("version", plan.targetVersions.getValue(instanceId))
                    },
                )
            }
            val summary = "Update plugin code ${artifact.manifest.id}@${artifact.manifest.version}"
            val mutation = try {
                withContext(ModifierPluginWorkers.generationContext(generation)) {
                    workspace.applyDocumentEdits(state, summary, edits, MutationAuthor.AGENT)
                }
            } catch (conflict: WorkspaceConflict) {
                throw PluginDevException(PluginDevErrorCodes.STATE_CONFLICT, conflict.message ?: "Workspace state conflict", cause = conflict)
            } catch (timeout: TimeoutException) {
                throw PluginDevException(PluginDevErrorCodes.PLUGIN_WORKER_TIMEOUT, timeout.message ?: "Plugin worker timed out", cause = timeout)
            } catch (failure: Throwable) {
                if (failure is PluginDevException) throw failure
                throw PluginDevException(
                    PluginDevErrorCodes.PLUGIN_WORKER_FAILED,
                    failure.message ?: "Plugin worker evaluation failed during code swap",
                    cause = failure,
                )
            }

            if (mutation.applied) {
                PluginDevArtifactStore.adopt(plan.artifactId, plan.projectId)
            }

            val result = buildJsonObject {
                put("applied", mutation.applied)
                put("project_id", mutation.projectId ?: plan.projectId)
                put("state_before", state)
                put("state", mutation.state)
                put("revision", mutation.revisionId)
                put("history_node_id", mutation.historyNodeId)
                put("changes", plan.changes)
                putJsonObject("observation") {
                    put("execution_generation", generation)
                    put("replayed_instance_ids", JsonArray(plan.replayedInstanceIds.map(::JsonPrimitive)))
                    put("state_before", state)
                    put("state", mutation.state)
                    put("revision", mutation.revisionId)
                }
                putJsonObject("presentation") {
                    put("summary", summary)
                    put("affected_object_ids", JsonArray(mutation.affectedObjectIds.map(::JsonPrimitive)))
                }
            }
            if (idempotency.size >= 512) idempotency.clear()
            idempotency[idempotencyKey] = IdempotencyEntry(fingerprint, result)
            return result
        } finally {
            PluginDevArtifactStore.releaseLease(plan.artifactId)
        }
    }

    private fun signToken(planId: String, expiresAt: Long): String {
        val payload = "$planId:$expiresAt"
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(hmacKey, "HmacSHA256")) }
        val sig = Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload.toByteArray(Charsets.UTF_8)))
        return "$payload.$sig"
    }

    private fun verifyToken(token: String): Pair<String, Long> {
        val parts = token.split('.')
        if (parts.size != 2) throw PluginDevException(PluginDevErrorCodes.PLAN_EXPIRED, "Invalid plan_token format")
        val payload = parts[0]
        val sig = parts[1]
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(hmacKey, "HmacSHA256")) }
        val expectedSig = Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload.toByteArray(Charsets.UTF_8)))
        if (sig != expectedSig) throw PluginDevException(PluginDevErrorCodes.PLAN_EXPIRED, "Invalid plan_token signature")
        val fields = payload.split(':')
        if (fields.size != 2) throw PluginDevException(PluginDevErrorCodes.PLAN_EXPIRED, "Invalid plan_token payload")
        val expiresAt = fields[1].toLongOrNull() ?: throw PluginDevException(PluginDevErrorCodes.PLAN_EXPIRED, "Invalid plan_token expiry")
        return fields[0] to expiresAt
    }
}
