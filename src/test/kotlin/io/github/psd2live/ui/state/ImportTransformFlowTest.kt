package io.github.psd2live.ui.state

import androidx.compose.ui.geometry.Offset
import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.project.transform
import io.github.psd2live.ui.BoundingHandle
import io.github.psd2live.ui.CanvasTool
import io.github.psd2live.ui.EditHierarchyMode
import io.github.psd2live.ui.transformHandleAt
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*

/**
 * The GUI's own path for the bow tie: an image dropped in is committed with its mesh and selected for the transform
 * tool; dragging it commits a layer_transform (the layer remembers where its pixels show), and a paint session on it
 * paints where it now is.
 */
class ImportTransformFlowTest {
    @TempDir lateinit var temporary: Path

    private suspend fun until(condition: () -> Boolean) = withTimeout(60_000) { while (!condition()) delay(20) }

    @Test fun anImportIsSelectedForTheTransformToolAndADragMovesTheWholeLayer() = runBlocking<Unit> {
        fun png(name: String, size: Int, argb: Int) = temporary.resolve(name).also { path ->
            val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until size) for (x in 0 until size) image.setRGB(x, y, argb)
            ImageIO.write(image, "png", path.toFile())
        }
        val body = png("body.png", 16, 0xff7799bb.toInt())
        val bow = png("bow.png", 8, 0xffcc3355.toInt())
        PSD2LiveViewModel().use { vm ->
            vm.setStateForTest(vm.state.value.copy(meshOnly = true, atlasSize = 256, generatePhysics = false, exportMoc3 = false))
            DesktopWorkspace(vm, temporary.resolve("store")).use { backend ->
                vm.attachWorkspace(backend)
                backend.createArtwork(buildJsonObject { put("width", 16); put("height", 16); putJsonArray("layers") { add(buildJsonObject {
                    put("path", body.toString()); put("name", "body"); put("role", "topwear")
                }) } })
                val nodes = backend.history().nodes.size
                vm.importLayersFromFiles(listOf(bow.toFile()), null, "root")
                until { vm.state.value.analysis?.source?.layers?.any { it.name == "bow" } == true && vm.state.value.selectedLayerId != null &&
                    vm.state.value.analysis?.source?.layers?.any { it.id.raw == vm.state.value.selectedLayerId && it.name == "bow" } == true }
                assertEquals(nodes + 1, backend.history().nodes.size, "the import is one history node")
                val id = assertNotNull(vm.state.value.selectedLayerId)
                val editor = vm.canvasEditorFor(vm.state.value.activeCanvas.id)
                until { editor.tool == CanvasTool.TRANSFORM }
                assertEquals(EditHierarchyMode.SELECT, editor.hierarchyMode)
                val model: RigPreviewModel = assertNotNull(vm.state.value.previewModel)
                assertEquals(1, model.rig.puppet.drawables.count { model.rig.layerIdByDrawableId[it.id.raw] == id }, "the import has its mesh")

                // Drag its body one canvas unit to the right.
                val viewport = io.github.psd2live.core.CanvasViewport(20.0, 0.0, 0.0, 16f, 16f)
                val frame = assertNotNull(editor.transformFrame(viewport))
                val start = Offset(frame.bounds.centerX + frame.bounds.width / 4f, frame.bounds.centerY)
                assertEquals(BoundingHandle.BODY, transformHandleAt(start, frame))
                assertTrue(editor.press(start, viewport, shift = false, alt = false))
                editor.move(start + Offset(20f, 0f), viewport, shift = false)
                editor.release()
                until { vm.state.value.analysis?.source?.layers?.single { it.id.raw == id }?.transform?.isIdentity == false && !editor.busy }
                val transform = vm.state.value.analysis!!.source.layers.single { it.id.raw == id }.transform
                assertEquals(1f, transform.e, 1e-3f); assertEquals(0f, transform.f, 1e-3f)
                assertEquals(1f, transform.a, 1e-3f); assertEquals(1f, transform.d, 1e-3f)
                assertNull(editor.error)
                assertEquals(nodes + 2, backend.history().nodes.size, "the drag is one history node")

                // Painting it paints where it now shows.
                val session = assertNotNull(editor.startPaintSession(id))
                assertEquals(transform, session.frame)
                assertEquals(session.originX + 1f, session.shownLeft, 1e-3f)
                editor.discardPaintSession()
            }
        }
    }
}
