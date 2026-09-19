package io.github.psd2live.core

import io.github.psd2live.ui.EditHierarchyMode
import io.github.psd2live.ui.canvasGeometryCommand
import org.umamo.runtime.model.*
import kotlin.test.*

class CanvasDeformationModeTest {
    private val parameter = ParameterId("pose")
    private fun model(keyed: Boolean): PuppetModel {
        val mesh = DrawableMesh(floatArrayOf(0f, 0f, 100f, 0f, 0f, 100f),
            floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f), intArrayOf(0, 1, 2))
        val grid = if (!keyed) null else KeyformGrid(
            listOf(KeyformAxis(parameter, floatArrayOf(0f, 1f))),
            listOf(KeyformCell(intArrayOf(0), MeshDeltaForm(FloatArray(6))),
                KeyformCell(intArrayOf(1), MeshDeltaForm(FloatArray(6) { 10f }))))
        val drawable = Drawable(DrawableId("mesh"), "Mesh", null, BlendMode.Normal, emptyList(), mesh, grid)
        return PuppetModel(listOf(Parameter(parameter, "Pose", 0f, 1f, 0f)), emptyList(), emptyList(),
            listOf(drawable), listOf(OrgChild.Drawable(drawable.id)), null)
    }

    @Test fun unboundDeformationMovesTextureWithVertices() {
        val source = model(false)
        val points = floatArrayOf(20f, 0f, 100f, 0f, 0f, 100f)
        for (mode in listOf(EditHierarchyMode.DEFORM, EditHierarchyMode.SELECT)) {
            val command = canvasGeometryCommand(mode, "mesh", "mesh", emptyMap(), points)
            val next = CanvasEdits.apply(source, command).drawables.single().mesh!!
            assertContentEquals(points, next.positions)
            assertContentEquals(source.drawables.single().mesh!!.uvs, next.uvs)
        }
    }

    @Test fun keyedDeformationChangesOnlyAddressedPose() {
        val source = model(true)
        val key = mapOf("pose" to 1f)
        val points = RigGeometryTools.geometry(source, "mesh", "mesh", key).points.copyOf()
        points[0] += 20f
        val command = canvasGeometryCommand(EditHierarchyMode.DEFORM, "mesh", "mesh", key, points)
        val next = CanvasEdits.apply(source, command)
        assertContentEquals(source.drawables.single().mesh!!.positions, next.drawables.single().mesh!!.positions)
        assertContentEquals(source.drawables.single().mesh!!.uvs, next.drawables.single().mesh!!.uvs)
        assertContentEquals(points, RigGeometryTools.geometry(next, "mesh", "mesh", key).points)
        assertContentEquals(RigGeometryTools.geometry(source, "mesh", "mesh", mapOf("pose" to 0f)).points,
            RigGeometryTools.geometry(next, "mesh", "mesh", mapOf("pose" to 0f)).points)
        assertContentEquals(points, RigGeometryTools.geometry(CanvasEdits.apply(source, command), "mesh", "mesh", key).points)
    }

    @Test fun structuralEditingMovesUvsAndPreservesKeyforms() {
        val source = model(true)
        val points = floatArrayOf(20f, 0f, 100f, 0f, 0f, 100f)
        val command = canvasGeometryCommand(EditHierarchyMode.EDIT, "mesh", "mesh", mapOf("pose" to 0f), points)
        val next = CanvasEdits.apply(source, command).drawables.single()
        assertContentEquals(points, next.mesh!!.positions)
        assertEquals(.2f, next.mesh!!.uvs[0], .0001f)
        assertSame(source.drawables.single().geometryGrid, next.geometryGrid)
    }
    @Test fun structuralEditingAtNonDefaultPoseAppliesOnlyPointerDisplacement() {
        val source = model(true)
        val key = mapOf("pose" to 1f)
        val points = RigGeometryTools.geometry(source, "mesh", "mesh", key).points.copyOf()
        points[0] += 20f
        val command = canvasGeometryCommand(EditHierarchyMode.EDIT, "mesh", "mesh", key, points)
        val next = CanvasEdits.apply(source, command)
        assertContentEquals(points, RigGeometryTools.geometry(next, "mesh", "mesh", key).points)
        assertEquals(0f, next.drawables.single().mesh!!.positions[1])
        assertEquals(.2f, next.drawables.single().mesh!!.uvs[0], .0001f)
    }

    @Test fun movingDisplayedMeshOntoRestPositionsIsARealEdit() {
        val source = model(true).let { it.copy(parameters = it.parameters.map { p -> p.copy(default = 1f) }) }
        val rest = source.drawables.single().mesh!!.positions
        val command = canvasGeometryCommand(EditHierarchyMode.EDIT, "mesh", "mesh", emptyMap(), rest)
        assertFalse(RigCommandDelta.isNoOp(source, command))
    }

    @Test fun unchangedDisplayedMeshDoesNotCreateHistory() {
        val source = model(true).let { it.copy(parameters = it.parameters.map { p -> p.copy(default = 1f) }) }
        val points = RigGeometryTools.geometry(source, "mesh", "mesh", emptyMap()).points
        val command = canvasGeometryCommand(EditHierarchyMode.EDIT, "mesh", "mesh", emptyMap(), points)
        assertTrue(RigCommandDelta.isNoOp(source, command))
    }

}
