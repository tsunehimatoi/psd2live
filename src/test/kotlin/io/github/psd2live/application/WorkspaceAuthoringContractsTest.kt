package io.github.psd2live.application

import io.github.psd2live.core.*
import kotlinx.serialization.json.*
import kotlin.test.*

class WorkspaceAuthoringContractsTest {
    @Test fun everyPublicOperationDeclaresAnExecutableOutputContract() {
        WorkspaceOperations(object : WorkspaceBackendStub() {}).use { operations ->
            val definitions = operations.registry.definitions()
            definitions.forEach { definition ->
                checkOperationSchema(definition.responseEnvelope())
            }
            assertEquals(189, definitions.size)
            assertEquals(70, definitions.count { it.jobBacked })
            assertEquals(86, definitions.count { it.batchable })
        }
    }

    @Test fun compactEditsPreserveOmittedSuccessAndExplicitNoopWithoutAcceptingUnknownFields() {
        val schema = WorkspaceAuthoringResultSchemas.forOperation("settings_update")!!
        val identity = buildJsonObject { put("project_id", "project"); put("state", "load:1:0"); put("history_node_id", "head") }
        validateOperationSchema(identity, schema)
        validateOperationSchema(JsonObject(identity + ("applied" to JsonPrimitive(false))), schema)
        validateOperationSchema(JsonObject(identity + ("changed" to JsonArray(listOf(JsonPrimitive("warp:body"))))), schema)
        for (invalid in listOf(JsonObject(identity + ("applied" to JsonPrimitive(true))),
            JsonObject(identity + ("changed" to JsonArray(emptyList()))), JsonObject(identity + ("unexpected" to JsonPrimitive(1))))) {
            assertFailsWith<WorkspaceValidationException> { validateOperationSchema(invalid, schema) }
        }
    }

    @Test fun skeletonContractsCoverAuthoredOptionalConnectionsWeightsTargetsAndSavedPoses() {
        val bone = SkeletonBone("body", "Body", null, BoneRole.LOWER_BODY, headX = 0f, headY = 0f, tailX = 0f, tailY = 10f,
            connected = false, parameterOverride = "ParamBody", mirrorId = "other", blendWidth = 2f)
        val spec = SkeletonSpec(bones = listOf(bone), symmetryAxisX = 0f, savedPoses = mapOf("Rest" to mapOf("ParamBody" to 0f)),
            ikTargets = mapOf("body" to SkeletonIkTarget(0f, 10f)),
            manualWeights = mapOf("mesh" to SkeletonWeightMap(listOf(0f, 0f), emptyList(), listOf(mapOf("body" to 1f)))))
        val data = buildJsonObject { put("state", "load:1:0"); put("spec", spec.toJson()) }
        val schema = WorkspaceAnimationResultSchemas.forOperation("skeleton_get")!!
        validateOperationSchema(data, schema)
        validateOperationSchema(buildJsonObject { put("state", "unloaded") }, schema)
        val invalidBone = JsonObject(bone.toJson() + ("ik" to buildJsonObject { put("chainLength", "wrong") }))
        val invalid = JsonObject(data + ("spec" to JsonObject(spec.toJson() + ("bones" to JsonArray(listOf(invalidBone))))))
        assertFailsWith<WorkspaceValidationException> { validateOperationSchema(invalid, schema) }
    }

    @Test fun motionContractsKeepOptionalInterpolationAndBezierHandlesFromActualSerialization() {
        val clip = MotionClip("move", "Move", curves = listOf(MotionCurve("ParamAngle", listOf(
            MotionKey(0f, -1f), MotionKey(1f, 1f, MotionInterpolation.LINEAR, MotionHandle(0.5f, 2f), MotionHandle(0.2f, -1f))))))
        val data = buildJsonObject { put("state", "load:1:0"); put("clip", MotionClips.toJson(clip)) }
        val schema = WorkspaceAnimationResultSchemas.forOperation("motion_get")!!
        validateOperationSchema(data, schema)
        val invalidKey = buildJsonObject { put("time", 1); put("value", 0); put("out", JsonArray(listOf(JsonPrimitive(0.5)))) }
        val invalidClip = JsonObject(clip.let(MotionClips::toJson) + ("curves" to JsonArray(listOf(buildJsonObject {
            put("parameter", "ParamAngle"); put("keys", JsonArray(listOf(invalidKey)))
        }))))
        assertFailsWith<WorkspaceValidationException> { validateOperationSchema(JsonObject(data + ("clip" to invalidClip)), schema) }
    }
}
