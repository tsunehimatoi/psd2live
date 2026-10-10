package io.github.psd2live.ui.state

import androidx.compose.ui.geometry.Offset
import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.project.canvasRect
import io.github.psd2live.project.transform
import io.github.psd2live.ui.BoundingHandle
import io.github.psd2live.ui.CanvasTool
import io.github.psd2live.ui.EditHierarchyMode
import io.github.psd2live.ui.transformHandleAt
import kotlinx.coroutines.CompletableDeferred
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
                assertEquals(io.github.psd2live.project.LayerTransform(1f, 0f, 0f, 1f, transform.e, transform.f), transform, "a plain drag is a plain move")
                assertNull(editor.error)
                assertEquals(nodes + 2, backend.history().nodes.size, "the drag is one history node")

                // Painting it paints where it now shows.
                val session = assertNotNull(editor.startPaintSession(id))
                assertEquals(transform, session.frame)
                assertEquals(session.originX + 1f, session.shownCorners[0], 1e-3f)
                editor.discardPaintSession()

                // Turned a quarter about its top-left corner, it still paints: a stroke lands on the layer's own pixel under the
                // pointer, not on the canvas pixel there.
                val turned = CompletableDeferred<String?>()
                vm.saveDocumentEdits(assertNotNull(vm.currentWorkspaceState()), "turn", listOf(
                    io.github.psd2live.application.WorkspaceDocumentOperation(io.github.psd2live.application.WorkspaceLayerTransform.OP,
                        buildJsonObject { put("layer_id", id); put("rotate", 90); putJsonArray("pivot") { add(5); add(4) } }))) { turned.complete(it) }
                assertNull(turned.await())
                until { vm.state.value.analysis?.source?.layers?.single { it.id.raw == id }?.transform?.isAxisAligned == false && !editor.busy }
                val quarter = vm.state.value.analysis!!.source.layers.single { it.id.raw == id }.transform
                editor.setHierarchyMode(EditHierarchyMode.PAINT)
                editor.tool = CanvasTool.PAINT_PENCIL
                editor.paintPencilSize = 1f
                editor.paintColor = androidx.compose.ui.graphics.Color.Green
                val painting = assertNotNull(editor.startPaintSession(id))
                assertEquals(quarter, painting.frame)
                assertNull(editor.error)
                val corners = painting.shownCorners
                assertNotEquals(corners[1], corners[3], 0.5f, "the raster is shown turned")
                // Frame pixel (6, 5), shown elsewhere once turned.
                val shown = Offset(quarter.x(6.5f, 5.5f) * 20f, quarter.y(6.5f, 5.5f) * 20f)
                assertTrue(editor.press(shown, viewport, shift = false, alt = false))
                editor.release()
                assertEquals(0xff, painting.sample(6, 5) ushr 24 and 0xff, "the layer's pixel under the pointer is painted")
                val canvasX = (shown.x / 20f).toInt(); val canvasY = (shown.y / 20f).toInt()
                assertEquals(0x00ff00, painting.sample(6, 5) and 0xffffff)
                assertTrue(canvasX != 6 || canvasY != 5)
                assertNotEquals(0x00ff00, painting.sample(canvasX, canvasY) and 0xffffff, "the canvas pixel there is not")

                // Saved with a new mesh, the mesh is laid over the layer's pixels where they show: turned with it.
                editor.commitPaintSession(rebuildMesh = true)
                until { editor.paintSession.let { it != null && it !== painting } && !vm.state.value.workspaceEditBusy }
                assertNull(vm.state.value.errorMessage)
                val saved = vm.state.value.analysis!!.source.layers.single { it.id.raw == id }
                assertEquals(quarter, saved.transform, "a repaint keeps where the layer shows")
                val rect = saved.canvasRect()
                val rebuilt = assertNotNull(vm.state.value.previewModel)
                val mesh = rebuilt.rig.puppet.drawables.single { rebuilt.rig.layerIdByDrawableId[it.id.raw] == id }
                val world = org.umamo.render.eval.CpuDeformationEvaluator().evaluate(rebuilt.rig.puppet, emptyMap()).worldPositions.getValue(mesh.id)
                val back = quarter.inverse()
                for (i in 0 until world.size / 2) {
                    val x = world[i * 2]; val y = -world[i * 2 + 1]
                    val fx = back.x(x, y); val fy = back.y(x, y)
                    assertTrue(fx in rect.left - 1.5f..rect.right + 1.5f && fy in rect.top - 1.5f..rect.top + rect.height + 1.5f,
                        "vertex $i at $x, $y is $fx, $fy in the layer's frame, outside $rect")
                }
                editor.discardPaintSession()

                // Shown twice as large, each of its pixels covers two canvas units: a brush keeps its size on screen,
                // so it takes half the pixels, and the tip it paints with is the ring the cursor shows.
                editor.tool = CanvasTool.PAINT_BRUSH
                editor.paintBrushSize = 16f
                val onCanvas = editor.paintTip().radius * editor.paintShownScale
                editor.discardPaintSession()
                suspend fun scaled(by: Float) {
                    val expected = io.github.psd2live.project.LayerTransform(by, 0f, 0f, by, 0f, 0f).after(
                        vm.state.value.analysis!!.source.layers.single { it.id.raw == id }.transform)
                    val done = CompletableDeferred<String?>()
                    vm.saveDocumentEdits(assertNotNull(vm.currentWorkspaceState()), "scale", listOf(
                        io.github.psd2live.application.WorkspaceDocumentOperation(io.github.psd2live.application.WorkspaceLayerTransform.OP,
                            buildJsonObject { put("layer_id", id); put("scale", by) }))) { done.complete(it) }
                    assertNull(done.await())
                    until { vm.state.value.analysis?.source?.layers?.single { it.id.raw == id }?.transform == expected && !editor.busy }
                }
                scaled(2f)
                assertNotNull(editor.startPaintSession(id))
                assertEquals(0.5f, editor.paintPixelsPerShownUnit, 1e-3f)
                assertEquals(8f, editor.paintBrushSize, 1e-3f, "half the pixels for the same size on screen")
                assertEquals(onCanvas, editor.paintTip().radius * editor.paintShownScale, 1e-3f, "the ring the stroke paints")
                assertEquals(4f, editor.paintTip().radius, 1e-3f, "the tip in the layer's frame is its own pixels")
                editor.discardPaintSession()
                // Shown at a quarter of that, its pixels are denser than the canvas: the brush takes more of them.
                scaled(0.25f)
                assertNotNull(editor.startPaintSession(id))
                assertEquals(2f, editor.paintPixelsPerShownUnit, 1e-3f)
                assertEquals(32f, editor.paintBrushSize, 1e-3f)
                assertEquals(onCanvas, editor.paintTip().radius * editor.paintShownScale, 1e-3f)
                editor.discardPaintSession()
            }
        }
    }
}
