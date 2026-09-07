package io.github.psd2live.project

import io.github.psd2live.agent.AgentWorkspaceDocument
import io.github.psd2live.agent.AgentWorkspaceStore
import io.github.psd2live.core.MeshSettings
import io.github.psd2live.core.RigEditOverlay
import io.github.psd2live.history.WorkspaceHistoryTree
import io.github.psd2live.ui.state.PSD2LiveState
import kotlinx.serialization.json.buildJsonObject
import org.umamo.format.art.SourceArt
import org.umamo.format.art.SourceLayer
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MeshSettingsPersistenceTest {

    @Test
    fun `mesh settings and overrides persist in project codec and settings`() {
        // Defaults on empty json
        val defaultDecoded = WorkspaceStateCodec.decode(buildJsonObject {})
        assertEquals(1.0f, defaultDecoded.meshOuterMargin)
        assertEquals(10.0f, defaultDecoded.meshInnerMargin)
        assertEquals(6.0f, defaultDecoded.meshMaxEdgeDistance)
        assertEquals(40.0f, defaultDecoded.meshInteriorDensity)
        assertTrue(defaultDecoded.meshOverrides.isEmpty())

        // Custom global settings + overrides
        val customOverrides = mapOf(
            "layer_face" to MeshSettings(
                outerMargin = 3.5f,
                innerMarginEnabled = true,
                innerMargin = 1.8f,
                maxEdgeDistance = 24.0f,
                interiorDensity = 32.0f,
            ),
            "layer_hair" to MeshSettings(
                outerMargin = 1.5f,
                innerMarginEnabled = false,
                innerMargin = 2.0f,
                maxEdgeDistance = 64.0f,
                interiorDensity = 50.0f,
            ),
        )

        val state = PSD2LiveState(
            meshOuterMargin = 4.0f,
            meshInnerMargin = 3.0f,
            meshMaxEdgeDistance = 36.0f,
            meshInteriorDensity = 40.0f,
            meshOverrides = customOverrides,
        )

        for (json in listOf(WorkspaceStateCodec.encode(state), WorkspaceStateCodec.settings(state))) {
            val restored = WorkspaceStateCodec.decode(json)
            assertEquals(4.0f, restored.meshOuterMargin)
            assertEquals(3.0f, restored.meshInnerMargin)
            assertEquals(36.0f, restored.meshMaxEdgeDistance)
            assertEquals(40.0f, restored.meshInteriorDensity)
            assertEquals(2, restored.meshOverrides.size)
            assertEquals(customOverrides["layer_face"], restored.meshOverrides["layer_face"])
            assertEquals(customOverrides["layer_hair"], restored.meshOverrides["layer_hair"])

            val config = restored.buildConfig()
            assertEquals(4.0f, config.meshOuterMargin)
            assertEquals(3.0f, config.meshInnerMargin)
            assertEquals(36.0f, config.meshMaxEdgeDistance)
            assertEquals(40.0f, config.meshInteriorDensity)
            assertEquals(customOverrides, config.meshOverrides)
        }
    }

    @Test
    fun `agent workspace store persists meshOverrides in document json and history tree`() {
        val root = Files.createTempDirectory("agent-mesh-persistence")
        try {
            val source = object : SourceArt {
                override val widthPx = 100
                override val heightPx = 100
                override val layers = emptyList<SourceLayer>()
            }
            val original = AgentWorkspaceDocument(source, emptyMap(), emptySet(), emptyMap(), emptyMap(), RigEditOverlay.Empty)
            val overrides = mapOf(
                "part_1" to MeshSettings(
                    outerMargin = 5.0f,
                    innerMarginEnabled = true,
                    innerMargin = 2.5f,
                    maxEdgeDistance = 20.0f,
                    interiorDensity = 30.0f,
                )
            )
            val tree = WorkspaceHistoryTree(original, "r0", "h0")
            val originalHead = tree.head().node.id
            val committed = tree.commit(originalHead, original.copy(meshOverrides = overrides), "r1", "h1", "Mesh overrides")

            AgentWorkspaceStore(root).persistHistory("project", tree.state())
            val restored = assertNotNull(AgentWorkspaceStore(root).loadHistory("project"))

            assertEquals(overrides, restored.head().snapshot.meshOverrides)
            assertEquals(emptyMap(), restored.checkout(originalHead).snapshot.meshOverrides)
            assertEquals(overrides, restored.checkout(committed.node.id).snapshot.meshOverrides)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `default mesh settings assigns dual line only to face by default`() {
        val state = PSD2LiveState(
            meshOuterMargin = 2.5f,
            meshInnerMargin = 1.5f,
            meshMaxEdgeDistance = 30.0f,
            meshInteriorDensity = 40.0f,
        )

        // Non-face default
        val nonFaceSettings = state.getDefaultMeshSettings("some_non_face_layer")
        assertFalse(nonFaceSettings.innerMarginEnabled, "Non-face layers must have innerMarginEnabled=false by default")
        assertEquals(2.5f, nonFaceSettings.outerMargin)
        assertEquals(1.5f, nonFaceSettings.innerMargin)

        // With override
        val stateWithOverride = state.copy(
            meshOverrides = mapOf(
                "custom_layer" to MeshSettings(
                    outerMargin = 6.0f,
                    innerMarginEnabled = true,
                    innerMargin = 4.0f,
                    maxEdgeDistance = 16.0f,
                    interiorDensity = 24.0f,
                )
            )
        )
        val effective = stateWithOverride.getEffectiveMeshSettings("custom_layer")
        assertTrue(effective.innerMarginEnabled)
        assertEquals(6.0f, effective.outerMargin)
        assertEquals(4.0f, effective.innerMargin)
    }
}

