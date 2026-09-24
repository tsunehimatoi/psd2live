package io.github.psd2live.agent

import io.github.psd2live.core.LayerClassificationOverride
import io.github.psd2live.core.LayerType
import io.github.psd2live.core.SemanticTag
import io.github.psd2live.core.Side
import io.github.psd2live.ui.state.PSD2LiveViewModel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.nio.file.Path
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.*

class LayerClassificationIntegrationTest {
    @TempDir lateinit var temp: Path

    @Test fun classificationRebuildsRigAndCanBeRestored() = runBlocking {
        val png = temp.resolve("art.png")
        val image = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
        for (y in 2..13) for (x in 2..13) image.setRGB(x, y, 0xffff3366.toInt())
        ImageIO.write(image, "png", png.toFile())

        PSD2LiveViewModel().use { viewModel ->
            ViewModelAgentWorkspace(viewModel, temp.resolve("store")).use { workspace ->
                viewModel.attachAgentWorkspace(workspace)
                val created = workspace.createArtwork(buildJsonObject {
                    put("width", 16); put("height", 16)
                    putJsonArray("layers") { add(buildJsonObject {
                        put("path", png.toString()); put("name", "decoration"); put("role", "objects")
                    }) }
                })
                val id = created.affectedLayerIds.single()
                val painted = workspace.paintSource(buildJsonObject {
                    put("state", created.historyNodeId); put("layer_id", id); put("mode", "brush")
                    putJsonArray("points") { add(buildJsonArray { add(0); add(0) }) }
                    putJsonArray("color") { listOf(0, 255, 0, 255).forEach { add(it) } }
                    put("radius", 1.0)
                })
                assertTrue(painted.applied)
                assertEquals(0f, workspace.snapshot().layers.single().opaqueBounds.left)
                val paintedPixel = viewModel.state.value.analysis!!.source.layers.single().raster.rgba
                assertEquals(0, paintedPixel[0].toInt() and 255)
                assertEquals(255, paintedPixel[1].toInt() and 255)
                assertTrue((paintedPixel[3].toInt() and 255) > 0)
                workspace.checkoutHistory(created.historyNodeId, MutationAuthor.AGENT)
                assertEquals(2f, workspace.snapshot().layers.single().opaqueBounds.left)
                val classified = workspace.classifyLayer(id, LayerClassificationOverride(
                    LayerType.TOGGLE, SemanticTag.OBJECTS, Side.NONE, "ParamDecoration", 0,
                ), created.historyNodeId)
                assertTrue(classified.applied)
                assertEquals("toggle", workspace.snapshot().layers.single().classificationType)
                assertEquals("ParamDecoration", workspace.snapshot().layers.single().parameterBinding)
                assertTrue(workspace.snapshot().parameters.any { it.id == "ParamDecoration" })
                val session = workspace.setPreviewSession(buildJsonObject {
                    put("state", classified.historyNodeId); put("mode", "set")
                    putJsonObject("values") { put("ParamDecoration", 1.0) }
                    putJsonObject("locks") { put("ParamDecoration", true) }
                })
                assertEquals(1f, session.getValue("values").jsonObject.getValue("ParamDecoration").jsonPrimitive.float)
                assertTrue(session.getValue("locked").jsonArray.any { it.jsonPrimitive.content == "ParamDecoration" })

                val restored = workspace.checkoutHistory(created.historyNodeId, MutationAuthor.AGENT)
                assertEquals("preset", workspace.snapshot().layers.single().classificationType)
                assertFalse(workspace.snapshot().parameters.any { it.id == "ParamDecoration" })
                assertNotEquals(classified.historyNodeId, restored.historyNodeId)

                val configured = workspace.updateProjectSettings(restored.historyNodeId, buildJsonObject {
                    put("headStrength", 2.0); put("atlasSize", 512)
                })
                assertEquals(2.0f, workspace.projectSettings().getValue("headStrength").jsonPrimitive.float)
                val meshed = workspace.setLayerMeshSettings(configured.historyNodeId, id, buildJsonObject {
                    put("outerMargin", 3.0); put("innerMarginEnabled", true)
                }, reset = false)
                assertEquals(3f, viewModel.state.value.meshOverrides.getValue(id).outerMargin)
                val output = workspace.exportModel(meshed.historyNodeId, temp.resolve("export").toString())
                assertTrue(output.getValue("files").jsonArray.isNotEmpty())
                assertTrue(output.getValue("files").jsonArray.all { file ->
                    Files.isRegularFile(Path.of(file.jsonObject.getValue("path").jsonPrimitive.content))
                })
                val psd = temp.resolve("roundtrip.psd")
                val psdResult = workspace.exportPsd(meshed.historyNodeId, psd.toString(), 1, true)
                assertEquals(Files.size(psd).toInt(), psdResult.getValue("bytes").jsonPrimitive.int)
                PSD2LiveViewModel().use { importedViewModel ->
                    ViewModelAgentWorkspace(importedViewModel, temp.resolve("import-store")).use { importedWorkspace ->
                        importedViewModel.attachAgentWorkspace(importedWorkspace)
                        val imported = importedWorkspace.importPsd(psd.toString())
                        assertTrue(imported.affectedLayerIds.isNotEmpty())
                        assertEquals("roundtrip.psd", importedWorkspace.snapshot().inputName)
                        val split = importedWorkspace.splitArtwork(buildJsonObject {
                            put("state", imported.historyNodeId); put("layer_id", imported.affectedLayerIds.first())
                            putJsonArray("polygon") {
                                listOf(0 to 0, 8 to 0, 8 to 16, 0 to 16).forEach { (x, y) ->
                                    add(buildJsonArray { add(x); add(y) })
                                }
                            }
                            putJsonArray("names") { add("left"); add("right") }
                        }, MutationAuthor.USER)
                        assertEquals(2, split.affectedLayerIds.size)
                        assertEquals("user", importedWorkspace.history().nodes.first { it.id == split.historyNodeId }.actor)
                        val clear = importedWorkspace.paintSource(buildJsonObject {
                            put("state", split.historyNodeId)
                            put("layer_id", split.affectedLayerIds.first())
                            put("mode", "clear")
                        })
                        assertTrue(clear.applied)
                        assertTrue(importedWorkspace.snapshot().layers.first { it.id == split.affectedLayerIds.first() }.deleted)
                        importedWorkspace.checkoutHistory(split.historyNodeId, MutationAuthor.USER)
                        assertFalse(importedWorkspace.snapshot().layers.first { it.id == split.affectedLayerIds.first() }.deleted)
                    }
                }
            }
        }
    }
}
