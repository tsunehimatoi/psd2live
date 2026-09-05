package io.github.psd2live.project

import io.github.psd2live.agent.*
import io.github.psd2live.core.*
import io.github.psd2live.ui.state.*
import kotlinx.coroutines.*
import org.umamo.format.art.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.*

class ProjectSessionTest {
    private class Fixture : AutoCloseable {
        val root = Files.createTempDirectory("psd2live-project-")
        val vm = PSD2LiveViewModel()
        val workspace = ViewModelAgentWorkspace(vm, root.resolve("cache"))
        val id = UUID.randomUUID().toString()
        init {
            val sourceFile = Files.write(root.resolve("original.psd"), byteArrayOf(56, 66, 80, 83))
            val pixels = ByteArray(16 * 16 * 4) { if (it % 4 == 3) -1 else 96 }
            val layer = WorkspaceSourceLayer(LayerId("body"), "Body", "", SourceLayerKind.Raster, true, 0,
                LayerBounds(0, 0, 16, 16), 1f, false, LayerBlend.Normal, ChannelMask.ALL,
                LayerRaster(16, 16, pixels), null, null, false)
            val state = PSD2LiveState(projectId = id, inputPath = sourceFile.toString(), loadedInputPath = sourceFile.toString(),
                meshOnly = true, atlasSize = 256, animationEnabled = false, mouseTrackingEnabled = false)
            val preview = PSD2LivePipeline().buildPreview(WorkspaceSourceArt(16, 16, listOf(layer), emptyList()), state.buildConfig())
            vm.installProjectState(state.copy(analysis = preview.analysis, previewModel = preview))
            vm.attachAgentWorkspace(workspace)
        }
        override fun close() {
            vm.close()
            workspace.close()
            ProjectArchive.deleteTemporaryDirectory(root)
        }
    }

    @Test fun `save creates nodes even unchanged and portable reopen restores every branch config`() = runBlocking {
        Fixture().use { f ->
            val session = ProjectSession(f.vm)
            val target = f.root.resolve("model.psd2live")
            val initial = f.workspace.history().headNodeId
            val first = session.save(f.workspace, target)
            assertEquals(2, f.workspace.history().nodes.size)
            assertFalse(f.vm.state.value.projectDirty)
            val second = session.save(f.workspace, target)
            assertNotEquals(first, second)
            assertEquals(3, f.workspace.history().nodes.size)
            f.vm.setMeshSpacing(96)
            assertEquals(4, f.workspace.history().nodes.size)
            val configNode = f.workspace.history().headNodeId
            f.workspace.checkoutHistory(initial)
            assertEquals(64, f.vm.state.value.meshSpacing)
            f.vm.setMeshSpacing(32)
            f.vm.editHistoryAnnotation(configNode, "alternate", "keep", true)
            session.save(f.workspace, target)
            val saved = f.workspace.history()
            Files.delete(Path.of(f.vm.state.value.loadedInputPath!!))
            session.open(f.workspace, target)
            assertEquals(f.id, f.vm.state.value.projectId)
            assertEquals(saved, f.workspace.history())
            assertEquals(32, f.vm.state.value.meshSpacing)
            assertTrue(f.vm.state.value.historyAnnotations.getValue(configNode).hidden)
            f.workspace.checkoutHistory(configNode)
            assertEquals(96, f.vm.state.value.meshSpacing)
            assertEquals(96, f.vm.state.value.previewModel!!.config.meshSpacing)
        }
    }

    @Test fun `save failure keeps previous file and checkpoint and reports dirty`() = runBlocking {
        Fixture().use { f ->
            val target = f.root.resolve("model.psd2live")
            ProjectSession(f.vm).save(f.workspace, target)
            val oldBytes = Files.readAllBytes(target)
            val oldCount = f.workspace.history().nodes.size
            val session = ProjectSession(f.vm) { _, _, _ -> throw java.io.IOException("disk full") }
            assertFailsWith<java.io.IOException> { session.save(f.workspace, target) }
            assertEquals(oldCount + 1, f.workspace.history().nodes.size)
            assertContentEquals(oldBytes, Files.readAllBytes(target))
            assertTrue(f.vm.state.value.projectDirty)
            assertEquals("disk full", f.vm.state.value.projectSaveError)
            assertFalse(f.vm.state.value.projectSaving)
        }
    }

    @Test fun `editing while archive writes leaves new work dirty and saved snapshot consistent`() = runBlocking {
        Fixture().use { f ->
            val target = f.root.resolve("model.psd2live")
            val session = ProjectSession(f.vm) { root, file, id ->
                assertTrue(f.vm.state.value.projectSaving)
                assertEquals(2, f.workspace.history().nodes.size) // visible before any archive bytes are written
                f.vm.editHistoryAnnotation(f.workspace.history().headNodeId, "new edit", "during save", false)
                ProjectArchive.write(root, file, id)
            }
            session.save(f.workspace, target)
            assertTrue(f.vm.state.value.projectDirty)
            ProjectSession(f.vm).open(f.workspace, target)
            assertTrue(f.vm.state.value.historyAnnotations.isEmpty())
        }
    }

    @Test fun `MCP modifications commit once reject stale heads and preserve keyform edits`() = runBlocking {
        Fixture().use { f ->
            val root = f.workspace.history().headNodeId
            val create = f.workspace.createParameter(AgentCreateParameterRequest("Custom", root, "Custom"))
            assertEquals(2, f.workspace.history().nodes.size)
            assertFails { f.workspace.updateParameter(AgentUpdateParameterRequest("Custom", root, name = "stale")) }
            assertEquals(2, f.workspace.history().nodes.size)
            val update = f.workspace.updateParameter(AgentUpdateParameterRequest("Custom", create.historyNodeId, name = "changed"))
            assertEquals(3, f.workspace.history().nodes.size)
            val drawable = f.vm.state.value.previewModel!!.rig.puppet.drawables.first().id.raw
            val key = f.workspace.setKeyform(AgentKeyformSetRequest(update.historyNodeId, AgentKeyformTargetRef("drawable", drawable), mapOf("Custom" to 1f), channels = AgentKeyformChannels(opacity = .5f)))
            assertEquals(4, f.workspace.history().nodes.size)
            assertNotEquals(update.revisionId, key.revisionId)
            f.workspace.checkpoint("explicit checkpoint")
            assertEquals(5, f.workspace.history().nodes.size)
            val session = ProjectSession(f.vm)
            val target = f.root.resolve("model.psd2live")
            session.save(f.workspace, target)
            session.open(f.workspace, target)
            assertEquals(1, f.vm.state.value.rigEdits.keyformSetEdits.size)
            assertEquals("changed", f.vm.state.value.previewModel!!.rig.puppet.parameters.first { it.id.raw == "Custom" }.name)
        }
    }
}
