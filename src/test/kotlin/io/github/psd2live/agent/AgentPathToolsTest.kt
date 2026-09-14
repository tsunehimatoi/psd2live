package io.github.psd2live.agent

import io.github.psd2live.core.Bounds
import io.github.psd2live.core.RigAuthoringJournal
import io.github.psd2live.ui.CanvasViewport
import io.github.psd2live.ui.RigInformationOverlay
import kotlinx.serialization.json.*
import org.umamo.runtime.model.*
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.test.*

class AgentPathToolsTest {

    private fun createTestModel(): PuppetModel {
        // A simple 100x100 quad mesh with 4 vertices and 2 triangles
        // 0: (0, 0), 1: (100, 0), 2: (0, 100), 3: (100, 100)
        // Triangles: (0, 1, 3) and (0, 3, 2)
        val positions = floatArrayOf(0f, 0f, 100f, 0f, 0f, 100f, 100f, 100f)
        val indices = intArrayOf(0, 1, 3, 0, 3, 2)
        val mesh = DrawableMesh(positions, positions.copyOf(), indices)
        val param = Parameter(ParameterId("ParamHair"), "Hair Param", -1f, 1f, 0f)
        val drawable = Drawable(
            id = DrawableId("hair_front"),
            name = "Hair Front",
            parentDeformerId = null,
            blendMode = BlendMode.Normal,
            maskedBy = emptyList(),
            mesh = mesh,
            geometryGrid = KeyformGrid(
                axes = listOf(KeyformAxis(param.id, floatArrayOf(-1f, 0f, 1f))),
                cells = listOf(
                    KeyformCell(intArrayOf(0), MeshDeltaForm(FloatArray(8))),
                    KeyformCell(intArrayOf(1), MeshDeltaForm(FloatArray(8))),
                    KeyformCell(intArrayOf(2), MeshDeltaForm(FloatArray(8))),
                )
            )
        )
        return PuppetModel(
            parameters = listOf(param),
            parts = emptyList(),
            deformers = emptyList(),
            drawables = listOf(drawable),
            rootChildren = listOf(OrgChild.Drawable(drawable.id)),
            rootPartId = null,
            deformPaths = emptyList()
        )
    }

    @Test
    fun pointAutoBindingBindsMeshCoordinates() {
        val model = createTestModel()
        val mesh = model.drawables.single().mesh!!

        val rawPoints = buildJsonArray {
            add(buildJsonArray { add(JsonPrimitive(20f)); add(JsonPrimitive(20f)) })
            add(buildJsonArray { add(JsonPrimitive(80f)); add(JsonPrimitive(80f)); add(JsonPrimitive(true)) })
        }

        val bound = AgentPathTools.parsePoints(rawPoints, mesh.positions, mesh.indices)
        assertEquals(2, bound.size)

        assertFalse(bound[0].corner)
        assertTrue(bound[1].corner)

        val (x0, y0) = bound[0].position(mesh.positions)
        assertEquals(20f, x0, 1e-4f)
        assertEquals(20f, y0, 1e-4f)

        val (x1, y1) = bound[1].position(mesh.positions)
        assertEquals(80f, x1, 1e-4f)
        assertEquals(80f, y1, 1e-4f)
    }

    @Test
    fun compilePathPutInJournalAndInspect() {
        val model = createTestModel()

        val putCommand = buildJsonObject {
            put("op", "path_put")
            put("id", "path_hair_center")
            put("target", "mesh:hair_front")
            put("width", 30f)
            put("hardness", 0.5f)
            putJsonArray("points") {
                add(buildJsonArray { add(JsonPrimitive(50f)); add(JsonPrimitive(10f)) })
                add(buildJsonArray { add(JsonPrimitive(50f)); add(JsonPrimitive(50f)) })
                add(buildJsonArray { add(JsonPrimitive(50f)); add(JsonPrimitive(90f)) })
            }
        }

        val (updatedModel, compiledList) = RigAuthoringJournal.compile(model, buildJsonArray { add(putCommand) })
        assertEquals(1, compiledList.size)
        assertEquals("path_put", compiledList.single().getValue("op").jsonPrimitive.content)

        val storedPath = updatedModel.deformPaths.single()
        assertEquals("path_hair_center", storedPath.id)
        assertEquals(DrawableId("hair_front"), storedPath.drawableId)
        assertEquals(3, storedPath.points.size)
        assertEquals(30f, storedPath.width)

        // Inspect through AgentPathTools
        val inspection = AgentPathTools.inspect(updatedModel, buildJsonObject {
            put("target", "mesh:hair_front")
        })
        val paths = inspection.getValue("paths").jsonArray
        assertEquals(1, paths.size)
        val pathObj = paths.single().jsonObject
        assertEquals("path_hair_center", pathObj.getValue("id").jsonPrimitive.content)
        assertEquals(3, pathObj.getValue("pointCount").jsonPrimitive.int)

        val inspectedPoints = pathObj.getValue("points").jsonArray
        assertEquals(3, inspectedPoints.size)
        assertEquals(50f, inspectedPoints[0].jsonObject.getValue("x").jsonPrimitive.float, 1e-3f)
        assertEquals(10f, inspectedPoints[0].jsonObject.getValue("y").jsonPrimitive.float, 1e-3f)
    }

    @Test
    fun previewAndCompilePathDeformation() {
        val baseModel = createTestModel()

        // First install the path
        val putCommand = buildJsonObject {
            put("op", "path_put")
            put("id", "path_1")
            put("target", "mesh:hair_front")
            put("width", 40f)
            putJsonArray("points") {
                add(buildJsonArray { add(JsonPrimitive(50f)); add(JsonPrimitive(10f)) })
                add(buildJsonArray { add(JsonPrimitive(50f)); add(JsonPrimitive(90f)) })
            }
        }
        val (modelWithPath, _) = RigAuthoringJournal.compile(baseModel, buildJsonArray { add(putCommand) })

        // Preview moving the tip from (50, 90) to (80, 90)
        val preview = AgentPathTools.preview(modelWithPath, buildJsonObject {
            put("target", "mesh:hair_front")
            put("path_id", "path_1")
            putJsonArray("moved_points") {
                add(buildJsonArray { add(JsonPrimitive(50f)); add(JsonPrimitive(10f)) })
                add(buildJsonArray { add(JsonPrimitive(80f)); add(JsonPrimitive(90f)) })
            }
        })
        assertTrue(preview.getValue("vertexCount").jsonPrimitive.int > 0)
        assertTrue(preview.getValue("maxDisplacement").jsonPrimitive.double > 0.0)
        assertTrue(preview.getValue("avgDisplacement").jsonPrimitive.double > 0.0)

        // Compile path_deform into keyform set
        val deformCommand = buildJsonObject {
            put("op", "path_deform")
            put("target", "mesh:hair_front")
            put("path_id", "path_1")
            putJsonObject("key") { put("ParamHair", 1f) }
            putJsonArray("moved_points") {
                add(buildJsonArray { add(JsonPrimitive(50f)); add(JsonPrimitive(10f)) })
                add(buildJsonArray { add(JsonPrimitive(80f)); add(JsonPrimitive(90f)) })
            }
        }

        val (deformedModel, compiledOps) = RigAuthoringJournal.compile(modelWithPath, buildJsonArray { add(deformCommand) })
        assertEquals(1, compiledOps.size)
        val compiledSet = compiledOps.single()
        assertEquals("set", compiledSet.getValue("op").jsonPrimitive.content)

        val deltas = compiledSet.getValue("geometry").jsonObject.getValue("positionDeltas").jsonArray
        assertEquals(8, deltas.size)
        // Root at (0, 0) should have smaller displacement than bottom right at (100, 100)
        val rootDx = deltas[0].jsonPrimitive.float
        val bottomDx = deltas[6].jsonPrimitive.float
        assertTrue(kotlin.math.abs(bottomDx) > kotlin.math.abs(rootDx))

        // Check keyform exists on deformedModel
        val grid = deformedModel.drawables.single().geometryGrid!!
        val keyCell = grid.cells.firstOrNull { it.coordinate[0] == 2 } // key for 1.0f
        assertNotNull(keyCell)
        assertTrue(keyCell.form.positionDeltas.any { it != 0f })
    }

    @Test
    fun deletePathRemovesItFromModel() {
        val baseModel = createTestModel()
        val putCommand = buildJsonObject {
            put("op", "path_put")
            put("id", "path_to_delete")
            put("target", "mesh:hair_front")
            putJsonArray("points") {
                add(buildJsonArray { add(JsonPrimitive(10f)); add(JsonPrimitive(10f)) })
                add(buildJsonArray { add(JsonPrimitive(90f)); add(JsonPrimitive(90f)) })
            }
        }
        val (modelWithPath, _) = RigAuthoringJournal.compile(baseModel, buildJsonArray { add(putCommand) })
        assertEquals(1, modelWithPath.deformPaths.size)

        val deleteCommand = buildJsonObject {
            put("op", "path_delete")
            put("id", "path_to_delete")
        }
        val (modelAfterDelete, _) = RigAuthoringJournal.compile(modelWithPath, buildJsonArray { add(deleteCommand) })
        assertEquals(0, modelAfterDelete.deformPaths.size)
    }

    @Test
    fun commandCreationAndFullExecutionFlow() {
        var currentModel = createTestModel()

        // 1. Create Put Command from Agent arguments
        val putArgs = buildJsonObject {
            put("mode", "put")
            put("state", "head_0")
            put("target", "mesh:hair_front")
            put("id", "agent_path_1")
            put("width", 35f)
            putJsonArray("points") {
                add(buildJsonArray { add(JsonPrimitive(30f)); add(JsonPrimitive(30f)) })
                add(buildJsonArray { add(JsonPrimitive(70f)); add(JsonPrimitive(70f)) })
            }
        }
        val (putPathId, putCmd) = AgentPathTools.createPutCommand(currentModel, putArgs)
        assertEquals("agent_path_1", putPathId)
        assertEquals("path_put", putCmd.getValue("op").jsonPrimitive.content)

        val (modelWithCreatedPath, _) = RigAuthoringJournal.compile(currentModel, buildJsonArray { add(putCmd) })
        currentModel = modelWithCreatedPath
        assertEquals(1, currentModel.deformPaths.size)

        // 2. Inspect
        val inspectResult = AgentPathTools.inspect(currentModel, buildJsonObject {
            put("target", "mesh:hair_front")
        })
        val inspectedPaths = inspectResult.getValue("paths").jsonArray
        assertEquals(1, inspectedPaths.size)
        assertEquals("agent_path_1", inspectedPaths[0].jsonObject.getValue("id").jsonPrimitive.content)

        // 3. Preview
        val previewArgs = buildJsonObject {
            put("target", "mesh:hair_front")
            put("path_id", "agent_path_1")
            putJsonArray("moved_points") {
                add(buildJsonArray { add(JsonPrimitive(30f)); add(JsonPrimitive(30f)) })
                add(buildJsonArray { add(JsonPrimitive(90f)); add(JsonPrimitive(90f)) })
            }
        }
        val preview = AgentPathTools.preview(currentModel, previewArgs)
        assertTrue(preview.getValue("maxDisplacement").jsonPrimitive.double > 0)

        // 4. Create Deform Command
        val deformArgs = buildJsonObject {
            put("mode", "deform")
            put("state", "head_1")
            put("target", "mesh:hair_front")
            put("path_id", "agent_path_1")
            putJsonObject("key") { put("ParamHair", 1f) }
            putJsonArray("moved_points") {
                add(buildJsonArray { add(JsonPrimitive(30f)); add(JsonPrimitive(30f)) })
                add(buildJsonArray { add(JsonPrimitive(90f)); add(JsonPrimitive(90f)) })
            }
        }
        val deformCmd = AgentPathTools.createDeformCommand(deformArgs)
        assertEquals("path_deform", deformCmd.getValue("op").jsonPrimitive.content)

        val (deformedModel, _) = RigAuthoringJournal.compile(currentModel, buildJsonArray { add(deformCmd) })
        currentModel = deformedModel
        val keyCell = currentModel.drawables.single().geometryGrid!!.cells.single { it.coordinate[0] == 2 }
        assertTrue(keyCell.form.positionDeltas.any { it != 0f })

        // 5. Create Delete Command
        val deleteArgs = buildJsonObject {
            put("mode", "delete")
            put("state", "head_2")
            put("path_id", "agent_path_1")
        }
        val (delPathId, delCmd) = AgentPathTools.createDeleteCommand(deleteArgs)
        assertEquals("agent_path_1", delPathId)
        assertEquals("path_delete", delCmd.getValue("op").jsonPrimitive.content)

        val (finalModel, _) = RigAuthoringJournal.compile(currentModel, buildJsonArray { add(delCmd) })
        assertEquals(0, finalModel.deformPaths.size)
    }

    @Test
    fun previewGeneratesDiagnosticImageAndSupportsDisabling() {
        val baseModel = createTestModel()
        val putCommand = buildJsonObject {
            put("op", "path_put")
            put("id", "path_preview_test")
            put("target", "mesh:hair_front")
            put("width", 35f)
            putJsonArray("points") {
                add(buildJsonArray { add(JsonPrimitive(30f)); add(JsonPrimitive(10f)) })
                add(buildJsonArray { add(JsonPrimitive(70f)); add(JsonPrimitive(90f)) })
            }
        }
        val (modelWithPath, _) = RigAuthoringJournal.compile(baseModel, buildJsonArray { add(putCommand) })

        // 1. Default render = true generates a valid 640x640 preview image
        val previewWithImage = AgentPathTools.preview(modelWithPath, buildJsonObject {
            put("target", "mesh:hair_front")
            put("path_id", "path_preview_test")
            putJsonArray("moved_points") {
                add(buildJsonArray { add(JsonPrimitive(30f)); add(JsonPrimitive(10f)) })
                add(buildJsonArray { add(JsonPrimitive(90f)); add(JsonPrimitive(90f)) })
            }
        })
        val base64 = previewWithImage["previewImage"]?.jsonPrimitive?.contentOrNull
        assertNotNull(base64)
        val imageBytes = Base64.getDecoder().decode(base64)
        val image = ImageIO.read(ByteArrayInputStream(imageBytes))
        assertNotNull(image)
        assertEquals(640, image.width)
        assertEquals(640, image.height)

        // 2. render = false skips preview image generation
        val previewWithoutImage = AgentPathTools.preview(modelWithPath, buildJsonObject {
            put("target", "mesh:hair_front")
            put("path_id", "path_preview_test")
            put("render", false)
            putJsonArray("moved_points") {
                add(buildJsonArray { add(JsonPrimitive(30f)); add(JsonPrimitive(10f)) })
                add(buildJsonArray { add(JsonPrimitive(90f)); add(JsonPrimitive(90f)) })
            }
        })
        assertNull(previewWithoutImage["previewImage"])

        // 3. custom width and hardness in preview
        val previewCustom = AgentPathTools.preview(modelWithPath, buildJsonObject {
            put("target", "mesh:hair_front")
            put("path_id", "path_preview_test")
            put("width", 60f)
            put("hardness", 0.8f)
            put("show_width", true)
            put("show_hardness", false)
            putJsonArray("moved_points") {
                add(buildJsonArray { add(JsonPrimitive(30f)); add(JsonPrimitive(10f)) })
                add(buildJsonArray { add(JsonPrimitive(90f)); add(JsonPrimitive(90f)) })
            }
        })
        assertEquals(60f, previewCustom["width"]?.jsonPrimitive?.float)
        assertEquals(0.8f, previewCustom["hardness"]?.jsonPrimitive?.float)
        assertTrue(previewCustom["showWidth"]?.jsonPrimitive?.boolean == true)
        assertFalse(previewCustom["showHardness"]?.jsonPrimitive?.boolean == true)
        assertNotNull(previewCustom["previewImage"])
    }

    @Test
    fun rigInformationOverlayPaintsDeformPaths() {
        val baseModel = createTestModel()
        val putCommand = buildJsonObject {
            put("op", "path_put")
            put("id", "path_overlay_1")
            put("target", "mesh:hair_front")
            put("width", 40f)
            putJsonArray("points") {
                add(buildJsonArray { add(JsonPrimitive(20f)); add(JsonPrimitive(20f)) })
                add(buildJsonArray { add(JsonPrimitive(50f)); add(JsonPrimitive(50f)); add(JsonPrimitive(true)) }) // corner
                add(buildJsonArray { add(JsonPrimitive(80f)); add(JsonPrimitive(80f)) })
            }
        }
        val (modelWithPath, _) = RigAuthoringJournal.compile(baseModel, buildJsonArray { add(putCommand) })

        val img = BufferedImage(300, 300, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        try {
            val viewport = CanvasViewport(
                scale = 2.0,
                offsetX = 10.0,
                offsetY = 200.0,
                canvasWidth = 100f,
                canvasHeight = 100f,
            )

            // Test specific path ID
            val renderedSpecific = RigInformationOverlay.paintDeformPaths(
                g, modelWithPath, null, viewport, setOf("path_overlay_1"),
                labels = true, pointIndices = true,
            )
            assertEquals(listOf("path_overlay_1"), renderedSpecific)

            // Test wildcard "*"
            val renderedAll = RigInformationOverlay.paintDeformPaths(
                g, modelWithPath, null, viewport, setOf("*"),
                labels = true, pointIndices = true,
            )
            assertEquals(listOf("path_overlay_1"), renderedAll)

            // Test showRadius = true
            val renderedWithRadius = RigInformationOverlay.paintDeformPaths(
                g, modelWithPath, null, viewport, setOf("path_overlay_1"),
                showRadius = true,
                labels = true, pointIndices = true,
            )
            assertEquals(listOf("path_overlay_1"), renderedWithRadius)

            // Test showWidth and showHardness independently
            val renderedWidth = RigInformationOverlay.paintDeformPaths(
                g, modelWithPath, null, viewport, setOf("path_overlay_1"),
                showWidth = true, showHardness = false,
                labels = true, pointIndices = true,
            )
            assertEquals(listOf("path_overlay_1"), renderedWidth)

            val renderedHardness = RigInformationOverlay.paintDeformPaths(
                g, modelWithPath, null, viewport, setOf("path_overlay_1"),
                showWidth = false, showHardness = true,
                labels = true, pointIndices = true,
            )
            assertEquals(listOf("path_overlay_1"), renderedHardness)

            // Test level filter "L2"
            val renderedLevel = RigInformationOverlay.paintDeformPaths(
                g, modelWithPath, null, viewport, setOf("L2"),
                labels = true, pointIndices = true,
            )
            assertEquals(listOf("path_overlay_1"), renderedLevel)

            // Test non-matching path ID
            val renderedEmpty = RigInformationOverlay.paintDeformPaths(
                g, modelWithPath, null, viewport, setOf("non_existent"),
            )
            assertTrue(renderedEmpty.isEmpty())
        } finally {
            g.dispose()
        }
    }

    @Test
    fun agentModelViewRequestSupportsPathWidthAndHardnessAnnotation() {
        val request = AgentModelViewRequest(
            annotatePathIds = setOf("path_overlay_1"),
            annotatePathWidth = true,
            annotatePathHardness = true,
            annotatePathRadius = false,
            frame = AgentViewFrame.CanvasRect(Bounds(0f, 0f, 100f, 100f)),
        )
        assertTrue(request.annotatePathWidth)
        assertTrue(request.annotatePathHardness)
        assertFalse(request.annotatePathRadius)
        assertEquals(setOf("path_overlay_1"), request.annotatePathIds)
    }
}
