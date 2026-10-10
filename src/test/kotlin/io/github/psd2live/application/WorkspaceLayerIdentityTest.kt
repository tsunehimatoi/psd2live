package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.umamo.format.art.*
import kotlin.test.*

/** A layer's meshes keep their IDs whatever the layer is classified as, so a reclassification never leaves a ghost. */
class WorkspaceLayerIdentityTest {
    private val builder = WorkspacePreviewBuilder()

    private fun ellipse(id: String, name: String, order: Int, cx: Int, cy: Int, rx: Int, ry: Int): WorkspaceSourceLayer {
        val left = cx - rx; val top = cy - ry; val width = rx * 2; val height = ry * 2
        val rgba = ByteArray(width * height * 4)
        for (y in 0 until height) for (x in 0 until width) {
            val dx = (x + 0.5f - rx) / rx; val dy = (y + 0.5f - ry) / ry
            if (dx * dx + dy * dy > 1f) continue
            val i = (y * width + x) * 4
            rgba[i] = (40 + order * 20).toByte(); rgba[i + 1] = 90; rgba[i + 2] = 120; rgba[i + 3] = -1
        }
        return WorkspaceSourceLayer(LayerId(id), name, "", SourceLayerKind.Raster, true, order, LayerBounds(left, top, width, height),
            1f, false, LayerBlend.Normal, ChannelMask.ALL, LayerRaster(width, height, rgba), null, null, false)
    }

    @Test fun reclassifyingALayerKeepsItsMeshAndAddsNoGhost() = runBlocking<Unit> {
        val layers = listOf(
            ellipse("body", "body", 0, 100, 230, 50, 60),
            ellipse("face", "face", 1, 100, 110, 50, 60),
            ellipse("patch", "patch", 2, 130, 200, 10, 10),
            ellipse("patch2", "patch 2", 3, 70, 200, 10, 10),
        )
        val config = PipelineConfig(atlasSize = 512, meshSpacing = 16, generatePhysics = false, exportMoc3 = false)
        val document = WorkspaceDocument(WorkspaceSourceArt(200, 300, layers, emptyList()), emptyMap(), emptySet(), emptyMap(),
            emptyMap(), RigEditOverlay.Empty, WorkspaceSettingsCodec.encode(config))
        val runtime = WorkspaceRuntime<RigPreviewModel>({ builder.build(it) }, rebuildFrom = { d, previous -> builder.build(d, previous) })
        runtime.install(runtime.state.value.state, "identity", document, builder.build(document))
        val before = runtime.capture()
        fun ids(capture: WorkspaceCapture<RigPreviewModel>) = capture.model.rig.layerIdByDrawableId.entries.groupBy({ it.value }, { it.key })
        // An edit on the patch's mesh, so the user's rig differs from the generated one there.
        val patch = ids(before).getValue("patch").single()
        val edited = WorkspaceDocumentCommands(runtime).execute(before.projectId, before.state, "Opacity", listOf(WorkspaceDocumentOperation(
            "object_edit_appearance", buildJsonObject { putJsonArray("edits") { add(buildJsonObject {
                put("action", "static"); put("kind", "mesh"); put("id", patch); put("opacity", 0.5)
            }) } })), MutationAuthor.USER).capture
        val reclassified = WorkspaceDocumentCommands(runtime).execute(edited.projectId, edited.state, "Classify", listOf(WorkspaceDocumentOperation(
            "layer_classify", buildJsonObject { put("layer_id", "patch"); put("type", "preset"); put("role", "headwear") })), MutationAuthor.USER).capture
        val after = ids(reclassified)
        assertEquals(listOf(patch), after.getValue("patch"), "the patch keeps its one mesh: ${after}")
        assertEquals(ids(before).getValue("patch2"), after.getValue("patch2"), "its sibling keeps its ID")
        assertEquals(0.5f, reclassified.model.rig.puppet.drawables.single { it.id.raw == patch }.opacity, "the user's edit stays on it")
        assertEquals(before.model.rig.puppet.drawables.size, reclassified.model.rig.puppet.drawables.size, "no ghost mesh")
    }

    @Test fun deletingALayerDoesNotRenumberItsSiblings() = runBlocking<Unit> {
        val layers = listOf(
            ellipse("body", "body", 0, 100, 230, 50, 60),
            ellipse("face", "face", 1, 100, 110, 50, 60),
            ellipse("patch", "patch", 2, 130, 200, 10, 10),
            ellipse("patch2", "patch 2", 3, 70, 200, 10, 10),
        )
        val config = PipelineConfig(atlasSize = 512, meshSpacing = 16, generatePhysics = false, exportMoc3 = false)
        val document = WorkspaceDocument(WorkspaceSourceArt(200, 300, layers, emptyList()), emptyMap(), emptySet(), emptyMap(),
            emptyMap(), RigEditOverlay.Empty, WorkspaceSettingsCodec.encode(config))
        val runtime = WorkspaceRuntime<RigPreviewModel>({ builder.build(it) }, rebuildFrom = { d, previous -> builder.build(d, previous) })
        runtime.install(runtime.state.value.state, "identity", document, builder.build(document))
        val before = runtime.capture()
        fun ids(capture: WorkspaceCapture<RigPreviewModel>) = capture.model.rig.layerIdByDrawableId.entries.groupBy({ it.value }, { it.key })
        val sibling = ids(before).getValue("patch2")
        val deleted = WorkspaceLayerCommands(runtime).execute(before.projectId, before.state, WorkspaceDocumentOperation(WorkspaceLayerEdits.DELETE,
            buildJsonObject { put("layer_id", "patch") }), "Delete", MutationAuthor.USER).commit.capture
        assertEquals(sibling, ids(deleted).getValue("patch2"))
        assertEquals(before.model.rig.puppet.drawables.size - 1, deleted.model.rig.puppet.drawables.size)
    }
}
