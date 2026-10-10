package io.github.psd2live.ui.state

import io.github.psd2live.project.MutationAuthor
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*

class WorkspaceProjectPersistenceTest {
    @TempDir lateinit var temp: Path

    private suspend fun create(workspace: DesktopWorkspace): String {
        val png = temp.resolve("generated.png")
        val image = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
        for (y in 2..13) for (x in 2..13) image.setRGB(x, y, 0xffe07040.toInt())
        ImageIO.write(image, "png", png.toFile())
        return workspace.createArtwork(buildJsonObject {
            put("width", 16); put("height", 16)
            putJsonArray("layers") { add(buildJsonObject {
                put("path", png.toString()); put("name", "Artwork"); put("role", "objects")
            }) }
        }).historyNodeId
    }

    @Test fun generatedArtworkSavesAndReopensWithItsHistoryAndRaster() = runBlocking {
        PSD2LiveViewModel().use { viewModel ->
            DesktopWorkspace(viewModel, temp.resolve("store")).use { workspace ->
                viewModel.attachWorkspace(workspace)
                val root = create(workspace)
                val layerId = workspace.snapshot().layers.single().id
                val original = viewModel.state.value.analysis!!.source.layers.single().raster.rgba.copyOf()
                val edited = workspace.paintSource(buildJsonObject {
                    put("state", workspace.snapshot().state); put("layer_id", layerId); put("mode", "brush")
                    putJsonArray("points") { add(buildJsonArray { add(4); add(4) }) }
                    put("radius", 2); putJsonArray("color") { add(10); add(200); add(30); add(255) }
                })
                val raster = viewModel.state.value.analysis!!.source.layers.single().raster.rgba.copyOf()
                assertFalse(original.contentEquals(raster), "The fixture must contain a real pixel edit")
                val archive = temp.resolve("generated.psd2live")
                viewModel.saveProjectNow(archive)
                assertTrue(Files.isRegularFile(archive))
                assertFalse(viewModel.state.value.projectDirty)
                val session = ProjectController(viewModel)
                session.open(workspace, archive)
                assertEquals(edited.historyNodeId, workspace.snapshot().historyHeadNodeId)
                assertContentEquals(raster, viewModel.state.value.analysis!!.source.layers.single().raster.rgba)
                workspace.checkoutHistory(root, MutationAuthor.USER)
                assertEquals(root, workspace.snapshot().historyHeadNodeId)
                assertContentEquals(original, viewModel.state.value.analysis!!.source.layers.single().raster.rgba)
            }
        }
    }

    /** A run that ends without the save-or-discard prompt (a crash, a kill) leaves its edits to be offered back. */
    @Test fun unsavedEditsOfAKilledSessionAreOfferedBackRestoredAndForgottenOnceSaved() = runBlocking {
        val store = temp.resolve("recovery-store")
        lateinit var edited: String
        PSD2LiveViewModel().use { viewModel ->
            DesktopWorkspace(viewModel, store).use { workspace ->
                viewModel.attachWorkspace(workspace)
                assertNull(viewModel.recoveryOffer.value)
                create(workspace)
                val layerId = workspace.snapshot().layers.single().id
                edited = workspace.paintSource(buildJsonObject {
                    put("state", workspace.snapshot().state); put("layer_id", layerId); put("mode", "brush")
                    putJsonArray("points") { add(buildJsonArray { add(4); add(4) }) }
                    put("radius", 2); putJsonArray("color") { add(10); add(200); add(30); add(255) }
                }).historyNodeId
            }
        }
        PSD2LiveViewModel().use { viewModel ->
            DesktopWorkspace(viewModel, store).use { workspace ->
                viewModel.attachWorkspace(workspace)
                val offer = assertNotNull(viewModel.recoveryOffer.value, "the killed session's edits are offered")
                workspace.restoreSession(offer)
                assertEquals(edited, workspace.snapshot().historyHeadNodeId)
                assertTrue(viewModel.state.value.projectDirty, "restored edits stay unsaved until saved")
                viewModel.saveProjectNow(temp.resolve("restored.psd2live"))
                assertFalse(viewModel.state.value.projectDirty)
            }
        }
        assertNull(SessionRecovery(store).pending(), "a saved project leaves nothing to recover")
    }

    @Test fun explicitPreviewDoesNotRecordKeysWhenGuiAutoKeyIsEnabled() = runBlocking {
        PSD2LiveViewModel().use { viewModel ->
            DesktopWorkspace(viewModel, temp.resolve("preview-store")).use { workspace ->
                viewModel.attachWorkspace(workspace)
                create(workspace)
                viewModel.createMotionClip()
                kotlinx.coroutines.withTimeout(10000) { viewModel.state.first { !it.workspaceEditBusy } }
                assertNull(viewModel.state.value.errorMessage)
                viewModel.setMotionAutoKey(true)
                val before = workspace.snapshot()
                val edits = viewModel.state.value.rigEdits
                val history = workspace.history()
                val parameter = before.parameters.first { it.min < it.max }
                workspace.setPreviewSession(buildJsonObject {
                    put("state", before.state); put("mode", "set")
                    putJsonObject("values") { put(parameter.id, parameter.max) }
                })
                assertEquals(edits, viewModel.state.value.rigEdits)
                assertEquals(history, workspace.history())
                assertEquals(parameter.max, workspace.previewSession().getValue("values").jsonObject.getValue(parameter.id).jsonPrimitive.float)
            }
        }
    }

    @Test fun lateSaveCannotOverwriteThePathOrDirtyStateOfAReopenedProject() = runBlocking {
        PSD2LiveViewModel().use { viewModel ->
            DesktopWorkspace(viewModel, temp.resolve("late-save-store")).use { workspace ->
                viewModel.attachWorkspace(workspace)
                create(workspace)
                val currentFile = temp.resolve("current.psd2live")
                val savedHead = viewModel.saveProjectNow(currentFile)
                val captured = viewModel.state.value
                viewModel.projectSaveStarted()
                ProjectController(viewModel).open(workspace, currentFile)
                val reopened = workspace.snapshot()
                val parameter = reopened.parameters.first { it.min < it.max }
                workspace.setPreviewSession(buildJsonObject {
                    put("state", reopened.state); put("mode", "set")
                    putJsonObject("values") { put(parameter.id, parameter.max) }
                })
                assertTrue(viewModel.state.value.projectDirty)
                val generation = viewModel.state.value.projectOpenGeneration
                viewModel.projectSaveFinished(temp.resolve("older-save.psd2live"), savedHead, captured)
                assertEquals(generation, viewModel.state.value.projectOpenGeneration)
                assertEquals(currentFile.toAbsolutePath().normalize().toString(), viewModel.state.value.projectFile)
                assertTrue(viewModel.state.value.projectDirty)
                assertFalse(viewModel.state.value.projectSaving)
                viewModel.projectSaveStarted()
                viewModel.projectSaveFailed(IllegalStateException("Old save failed"), captured)
                assertNull(viewModel.state.value.projectSaveError)
                assertFalse(viewModel.state.value.projectSaving)
            }
        }
    }
}
