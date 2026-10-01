package io.github.psd2live.agent

import io.github.psd2live.core.Bounds
import io.github.psd2live.core.LayerClassificationOverride
import io.github.psd2live.core.LayerType
import io.github.psd2live.core.SemanticTag
import io.github.psd2live.core.BoneRole
import io.github.psd2live.core.SkeletonBone
import io.github.psd2live.core.SkeletonSpec
import io.github.psd2live.core.MotionClip
import io.github.psd2live.core.MotionClips
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class AuthoringParityTest {
    private val connection = java.lang.reflect.Proxy.newProxyInstance(
        ClientConnection::class.java.classLoader,
        arrayOf(ClientConnection::class.java),
    ) { _, method, _ -> if (method.name == "getSessionId") "test" else error("Unexpected MCP client call") } as ClientConnection
    private class Workspace : AgentWorkspace {
        var classified: Pair<String, LayerClassificationOverride>? = null
        var armature: SkeletonSpec? = null
        var clips: List<MotionClip> = emptyList()
        var dryRunCalls = 0
        override fun skeletonSpec() = armature
        override fun proposeSkeleton() = SkeletonSpec(bones = listOf(
            SkeletonBone("body", "Body", null, BoneRole.LOWER_BODY, headX = 0f, headY = 0f, tailX = 0f, tailY = 10f)))
        override suspend fun editSkeleton(state: String, request: JsonObject): AgentWorkspaceMutationResult {
            assertEquals("head", state)
            armature = AgentSkeletonMotionEdits.skeleton(armature, request, ::proposeSkeleton)
            return AgentWorkspaceMutationResult("next", "revision-next", summary = "skeleton")
        }
        override fun motionClips() = clips
        override suspend fun editMotion(state: String, request: JsonObject): AgentWorkspaceMutationResult {
            assertEquals("head", state)
            val ranges = mapOf("ParamArmLA" to (-90f..150f))
            clips = AgentSkeletonMotionEdits.motion(clips, request, ranges, armature)
            return AgentWorkspaceMutationResult("next", "revision-next", summary = "motion")
        }
        override fun snapshot() = AgentProjectSnapshot(
            projectId = "test", revisionId = "revision", historyHeadNodeId = "head", loaded = true,
            inputName = "test", canvasWidth = 32, canvasHeight = 32, busy = false, status = "ready",
            selectedLayerId = null, parameters = emptyList(),
            layers = listOf(AgentLayerSnapshot(
                id = "hair", sourceName = "hair", rasterWidth = 16, rasterHeight = 16,
                groupPath = "", order = 0, semanticTag = "front_hair", side = "left",
                confidence = 1f, bounds = Bounds(0f, 0f, 16f, 16f),
                opaqueBounds = Bounds(0f, 0f, 16f, 16f), visible = true, deleted = false,
            )),
        )
        override suspend fun classifyLayer(layerId: String, classification: LayerClassificationOverride, expectedHead: String): AgentWorkspaceMutationResult {
            assertEquals("head", expectedHead)
            classified = layerId to classification
            return AgentWorkspaceMutationResult("next", "revision-next", listOf(layerId), "classified")
        }
        override suspend fun dryRunRig(state: String, edits: JsonArray, author: MutationAuthor): AgentRigDryRunResult {
            assertEquals("head", state)
            assertEquals(MutationAuthor.AGENT, author)
            assertEquals(1, edits.size)
            dryRunCalls++
            val safety = buildJsonObject {
                put("safe", true)
                putJsonArray("affectedTargets") { add("mesh:m") }
                putJsonObject("affectedCoordinates") { putJsonArray("mesh:m") { add(buildJsonObject { put("P", 0f) }) } }
                putJsonArray("violations") { }
            }
            return AgentRigDryRunResult("head", "revision", "candidate", true, true, true, 1, safety)
        }
        override suspend fun renderLayer(layerId: String, background: AgentViewBackground, output: AgentViewOutputSpec): AgentRenderedView = error("unused")
        override suspend fun renderContext(layerId: String, objectScale: Float, aspectRatio: Float, background: AgentViewBackground, output: AgentViewOutputSpec): AgentRenderedView = error("unused")
        override suspend fun renderModel(request: AgentModelViewRequest): AgentRenderedView = error("unused")
    }

    @Test fun classificationMergesOmittedFieldsAndRejectsInvalidRequests() = runBlocking {
        val workspace = Workspace()
        val server = createAgentMcpServer(workspace)
        assertEquals(26, server.tools.size)
        val layer = server.tools.getValue("layer")
        val result = layer.handler.invoke(connection, CallToolRequest(CallToolRequestParams("layer", buildJsonObject {
            put("state", "head"); put("layer_id", "hair"); put("type", "switch")
            put("parameter", "expression"); put("switch_id", 2)
        })))
        assertFalse(result.isError == true)
        assertEquals("next", result.structuredContent?.get("state")?.jsonPrimitive?.content)
        assertEquals("hair", workspace.classified?.first)
        assertEquals(LayerType.SWITCH, workspace.classified?.second?.type)
        assertEquals(SemanticTag.FRONT_HAIR, workspace.classified?.second?.tag)
        assertEquals("expression", workspace.classified?.second?.parameter)
        assertEquals(2, workspace.classified?.second?.switchId)

        workspace.classified = null
        val invalid = layer.handler.invoke(connection, CallToolRequest(CallToolRequestParams("layer", buildJsonObject {
            put("state", "head"); put("layer_id", "hair"); put("switch_id", -1)
        })))
        assertTrue(invalid.isError == true)
        assertNull(workspace.classified)
    }

    @Test fun publicSurfaceIncludesParityRoutes() {
        val server = createAgentMcpServer(Workspace())
        assertTrue(server.tools.keys.containsAll(listOf("layer", "layer_mesh", "paint", "preview", "structure", "canvas", "settings", "parameter", "export", "export_psd", "swing", "skeleton", "motion", "simulation", "model_preset", "vertex_group")))
        assertTrue(server.tools.getValue("asset").tool.inputSchema.properties.toString().contains("psd"))
        assertFalse("parameter_delete" in server.tools.keys)
        assertTrue(server.tools.getValue("parameter").tool.inputSchema.properties.toString().contains("delete"))
        assertTrue(server.tools.getValue("deform").tool.inputSchema.properties.toString().contains("dry_run"))
    }

    @Test fun publicDeformDryRunReturnsDiscoverableGateEvidence() = runBlocking {
        val workspace = Workspace()
        val tool = createAgentMcpServer(workspace).tools.getValue("deform")
        val result = tool.handler.invoke(connection, CallToolRequest(CallToolRequestParams("deform", buildJsonObject {
            put("state", "head"); put("dry_run", true)
            putJsonArray("changes") { add(buildJsonObject {
                put("target", "mesh:m"); putJsonObject("key") { put("P", 0f) }
                putJsonArray("operations") { add(buildJsonObject {
                    put("type", "translate"); putJsonArray("delta") { add(0.1f); add(0f) }
                }) }
            }) }
        })))
        assertFalse(result.isError == true)
        assertEquals(1, workspace.dryRunCalls)
        assertEquals(true, result.structuredContent?.get("dryRun")?.jsonPrimitive?.boolean)
        assertEquals(true, result.structuredContent?.get("acceptedByGeometryGate")?.jsonPrimitive?.boolean)
        assertEquals("head", result.structuredContent?.get("currentState")?.jsonPrimitive?.content)
        assertEquals(1, result.structuredContent?.get("compiledCommandCount")?.jsonPrimitive?.int)
        assertEquals("mesh:m", result.structuredContent?.get("affectedTargets")?.jsonArray?.single()?.jsonPrimitive?.content)
    }

    @Test fun skeletonAndMotionRoutesExposePersistentEdits() = runBlocking {
        val workspace = Workspace()
        val server = createAgentMcpServer(workspace)
        suspend fun call(name: String, request: JsonObject) = server.tools.getValue(name).handler.invoke(connection,
            CallToolRequest(CallToolRequestParams(name, buildJsonObject { put("request", request) })))
        val created = call("skeleton", buildJsonObject { put("mode", "auto"); put("state", "head") })
        assertFalse(created.isError == true)
        assertEquals("next", created.structuredContent?.get("state")?.jsonPrimitive?.content)
        val read = call("skeleton", buildJsonObject { put("mode", "get") })
        assertEquals("body", read.structuredContent?.get("spec")?.jsonObject?.get("bones")?.jsonArray?.single()?.jsonObject?.get("id")?.jsonPrimitive?.content)
        workspace.armature = workspace.armature!!.copy(
            bones = workspace.armature!!.bones.map { it.copy(connected = false, parameterOverride = "ParamBodyCopy", mirrorId = "partner") },
            symmetryAxisX = 16f,
            savedPoses = mapOf("Rest" to mapOf("ParamBodyCopy" to 0f)),
            ikTargets = mapOf("body" to io.github.psd2live.core.SkeletonIkTarget(0f, 10f)),
            manualWeights = mapOf("mesh" to io.github.psd2live.core.SkeletonWeightMap(
                listOf(0f, 0f), emptyList(), listOf(mapOf("body" to 1f)))),
        )
        val authored = workspace.armature!!
        val roundTrippedSkeleton = call("skeleton", buildJsonObject {
            put("mode", "put"); put("state", "head"); put("spec", authored.toJson())
        })
        assertFalse(roundTrippedSkeleton.isError == true)
        assertEquals(authored, workspace.armature)
        val written = call("motion", buildJsonObject {
            put("mode", "put"); put("state", "head")
            put("clip", buildJsonObject { put("id", "custom"); put("name", "Custom") })
        })
        assertFalse(written.isError == true)
        val clip = call("motion", buildJsonObject { put("mode", "get"); put("id", "custom") })
        assertEquals("Custom", clip.structuredContent?.get("clip")?.jsonObject?.get("name")?.jsonPrimitive?.content)
        assertEquals(MotionClips.toJson(workspace.clips.single()), clip.structuredContent?.get("clip"))
        val roundTrippedClip = call("motion", buildJsonObject {
            put("mode", "put"); put("state", "head"); put("clip", clip.structuredContent!!.getValue("clip"))
        })
        assertFalse(roundTrippedClip.isError == true)
        val sampled = call("motion", buildJsonObject { put("mode", "sample"); put("id", "custom"); put("time", 0.5) })
        assertFalse(sampled.isError == true)
        assertTrue(sampled.structuredContent?.get("values")?.jsonObject?.isEmpty() == true)
    }
}
