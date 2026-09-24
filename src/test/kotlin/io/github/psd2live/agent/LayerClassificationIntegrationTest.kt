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
                val classified = workspace.classifyLayer(id, LayerClassificationOverride(
                    LayerType.TOGGLE, SemanticTag.OBJECTS, Side.NONE, "ParamDecoration", 0,
                ), created.historyNodeId)
                assertTrue(classified.applied)
                assertEquals("toggle", workspace.snapshot().layers.single().classificationType)
                assertEquals("ParamDecoration", workspace.snapshot().layers.single().parameterBinding)
                assertTrue(workspace.snapshot().parameters.any { it.id == "ParamDecoration" })

                val restored = workspace.checkoutHistory(created.historyNodeId, MutationAuthor.AGENT)
                assertEquals("preset", workspace.snapshot().layers.single().classificationType)
                assertFalse(workspace.snapshot().parameters.any { it.id == "ParamDecoration" })
                assertNotEquals(classified.historyNodeId, restored.historyNodeId)

                val configured = workspace.updateProjectSettings(restored.historyNodeId, buildJsonObject {
                    put("headStrength", 2.0); put("atlasSize", 512)
                })
                assertEquals(2.0f, workspace.projectSettings().getValue("headStrength").jsonPrimitive.float)
                val output = workspace.exportModel(configured.historyNodeId, temp.resolve("export").toString())
                assertTrue(output.getValue("files").jsonArray.isNotEmpty())
                assertTrue(output.getValue("files").jsonArray.all { file ->
                    Files.isRegularFile(Path.of(file.jsonObject.getValue("path").jsonPrimitive.content))
                })
            }
        }
    }
}
