package io.github.psd2live.core

import io.github.psd2live.ui.EditHierarchyMode
import io.github.psd2live.ui.canvasGeometryCommand
import org.umamo.render.eval.CpuDeformationEvaluator
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
        assertEquals(.2f, next.mesh.uvs[0], .0001f)
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

    private fun warpModel(keyed: Boolean): PuppetModel {
        val warpLattice = floatArrayOf(0f, 0f, 100f, 0f, 0f, 100f, 100f, 100f)
        val warpGrid = if (!keyed) {
            KeyformGrid(emptyList(), listOf(KeyformCell(intArrayOf(), WarpLatticeForm(warpLattice))))
        } else {
            KeyformGrid(
                listOf(KeyformAxis(parameter, floatArrayOf(0f, 1f))),
                listOf(
                    KeyformCell(intArrayOf(0), WarpLatticeForm(warpLattice)),
                    KeyformCell(intArrayOf(1), WarpLatticeForm(warpLattice.mapIndexed { idx, v -> if (idx == 0) v + 20f else v }.toFloatArray())),
                ),
            )
        }
        val warp = Deformer.Warp(
            id = DeformerId("warp"),
            name = "Warp",
            parent = null,
            partId = null,
            rows = 1,
            columns = 1,
            isQuadTransform = true,
            geometryGrid = warpGrid,
        )
        val mesh = DrawableMesh(
            floatArrayOf(0.2f, 0.2f, 0.8f, 0.2f, 0.5f, 0.8f),
            floatArrayOf(0f, 0f, 1f, 0f, 0.5f, 1f),
            intArrayOf(0, 1, 2),
        )
        val drawable = Drawable(DrawableId("mesh"), "Mesh", warp.id, BlendMode.Normal, emptyList(), mesh, null)
        return PuppetModel(
            parameters = listOf(Parameter(parameter, "Pose", 0f, 1f, 0f)),
            parts = emptyList(),
            deformers = listOf(warp),
            drawables = listOf(drawable),
            rootChildren = listOf(OrgChild.Drawable(drawable.id)),
            rootPartId = null,
        )
    }

    @Test fun warpEditInDeformModeDeformsChildMesh() {
        val source = warpModel(false)
        val evaluator = CpuDeformationEvaluator()
        val before = evaluator.evaluate(source, emptyMap()).worldPositions.getValue(DrawableId("mesh"))

        val newWarpPoints = floatArrayOf(-30f, -20f, 100f, 0f, 0f, 100f, 100f, 100f)
        val command = canvasGeometryCommand(EditHierarchyMode.DEFORM, "warp", "warp", emptyMap(), newWarpPoints, ctrl = false)
        val next = CanvasEdits.apply(source, command)

        val after = evaluator.evaluate(next, emptyMap()).worldPositions.getValue(DrawableId("mesh"))
        var hasDifference = false
        for (i in before.indices) {
            if (kotlin.math.abs(before[i] - after[i]) > 0.1f) {
                hasDifference = true
                break
            }
        }
        assertTrue(hasDifference, "Child mesh should be deformed in normal DEFORM mode")
    }

    @Test fun warpEditInEditModePreservesChildMeshWorldPositions() {
        val source = warpModel(false)
        val evaluator = CpuDeformationEvaluator()
        val before = evaluator.evaluate(source, emptyMap()).worldPositions.getValue(DrawableId("mesh"))

        val newWarpPoints = floatArrayOf(-30f, -20f, 110f, 10f, -10f, 120f, 105f, 95f)
        val command = canvasGeometryCommand(EditHierarchyMode.EDIT, "warp", "warp", emptyMap(), newWarpPoints)
        val next = CanvasEdits.apply(source, command)

        val nextWarp = next.deformers.single { it.id.raw == "warp" } as Deformer.Warp
        assertContentEquals(newWarpPoints, nextWarp.geometryGrid!!.cells.single().form.controlPoints)

        val after = evaluator.evaluate(next, emptyMap()).worldPositions.getValue(DrawableId("mesh"))
        for (i in before.indices) {
            assertEquals(before[i], after[i], 0.05f, "Mismatch at vertex coord $i")
        }
    }

    @Test fun warpEditInDeformModeWithCtrlPreservesChildMeshWorldPositions() {
        val source = warpModel(false)
        val evaluator = CpuDeformationEvaluator()
        val before = evaluator.evaluate(source, emptyMap()).worldPositions.getValue(DrawableId("mesh"))

        val newWarpPoints = floatArrayOf(-30f, -20f, 110f, 10f, -10f, 120f, 105f, 95f)
        val command = canvasGeometryCommand(EditHierarchyMode.DEFORM, "warp", "warp", emptyMap(), newWarpPoints, ctrl = true)
        val next = CanvasEdits.apply(source, command)

        val nextWarp = next.deformers.single { it.id.raw == "warp" } as Deformer.Warp
        assertContentEquals(newWarpPoints, nextWarp.geometryGrid!!.cells.single().form.controlPoints)

        val after = evaluator.evaluate(next, emptyMap()).worldPositions.getValue(DrawableId("mesh"))
        for (i in before.indices) {
            assertEquals(before[i], after[i], 0.05f, "Mismatch at vertex coord $i with Ctrl")
        }
    }

    @Test fun warpEditInEditModePreservesChildRotationDeformer() {
        val warpLattice = floatArrayOf(0f, 0f, 100f, 0f, 0f, 100f, 100f, 100f)
        val warp = Deformer.Warp(
            id = DeformerId("warp"),
            name = "Warp",
            parent = null,
            partId = null,
            rows = 1,
            columns = 1,
            isQuadTransform = true,
            geometryGrid = KeyformGrid(emptyList(), listOf(KeyformCell(intArrayOf(), WarpLatticeForm(warpLattice)))),
        )
        val rotation = Deformer.Rotation(
            id = DeformerId("rot"),
            name = "Rot",
            parent = warp.id,
            partId = null,
            baseAngle = 0f,
            geometryGrid = KeyformGrid(emptyList(), listOf(KeyformCell(intArrayOf(), RotationPivotForm(0.5f, 0.5f, 15f, 1f)))),
        )
        val mesh = DrawableMesh(
            floatArrayOf(10f, 10f, 30f, 10f, 20f, 30f),
            floatArrayOf(0f, 0f, 1f, 0f, 0.5f, 1f),
            intArrayOf(0, 1, 2),
        )
        val drawable = Drawable(DrawableId("mesh"), "Mesh", rotation.id, BlendMode.Normal, emptyList(), mesh, null)
        val source = PuppetModel(
            parameters = emptyList(),
            parts = emptyList(),
            deformers = listOf(warp, rotation),
            drawables = listOf(drawable),
            rootChildren = listOf(OrgChild.Drawable(drawable.id)),
            rootPartId = null,
        )

        val evaluator = CpuDeformationEvaluator()
        val before = evaluator.evaluate(source, emptyMap()).worldPositions.getValue(DrawableId("mesh"))

        val newWarpPoints = floatArrayOf(-20f, -10f, 120f, 15f, 10f, 110f, 90f, 95f)
        val command = canvasGeometryCommand(EditHierarchyMode.EDIT, "warp", "warp", emptyMap(), newWarpPoints)
        val next = CanvasEdits.apply(source, command)

        val after = evaluator.evaluate(next, emptyMap()).worldPositions.getValue(DrawableId("mesh"))
        for (i in before.indices) {
            assertEquals(before[i], after[i], 0.05f, "Rotation child mesh world coord $i mismatch")
        }
    }

    @Test fun warpEditInEditModePreservesChildWarpDeformer() {
        val parentLattice = floatArrayOf(0f, 0f, 100f, 0f, 0f, 100f, 100f, 100f)
        val parentWarp = Deformer.Warp(
            id = DeformerId("parent_warp"),
            name = "Parent Warp",
            parent = null,
            partId = null,
            rows = 1,
            columns = 1,
            isQuadTransform = true,
            geometryGrid = KeyformGrid(emptyList(), listOf(KeyformCell(intArrayOf(), WarpLatticeForm(parentLattice)))),
        )
        val childLattice = floatArrayOf(0.2f, 0.2f, 0.8f, 0.2f, 0.2f, 0.8f, 0.8f, 0.8f)
        val childWarp = Deformer.Warp(
            id = DeformerId("child_warp"),
            name = "Child Warp",
            parent = parentWarp.id,
            partId = null,
            rows = 1,
            columns = 1,
            isQuadTransform = true,
            geometryGrid = KeyformGrid(emptyList(), listOf(KeyformCell(intArrayOf(), WarpLatticeForm(childLattice)))),
        )
        val mesh = DrawableMesh(
            floatArrayOf(0.2f, 0.2f, 0.8f, 0.2f, 0.5f, 0.8f),
            floatArrayOf(0f, 0f, 1f, 0f, 0.5f, 1f),
            intArrayOf(0, 1, 2),
        )
        val drawable = Drawable(DrawableId("mesh"), "Mesh", childWarp.id, BlendMode.Normal, emptyList(), mesh, null)
        val source = PuppetModel(
            parameters = emptyList(),
            parts = emptyList(),
            deformers = listOf(parentWarp, childWarp),
            drawables = listOf(drawable),
            rootChildren = listOf(OrgChild.Drawable(drawable.id)),
            rootPartId = null,
        )

        val evaluator = CpuDeformationEvaluator()
        val before = evaluator.evaluate(source, emptyMap()).worldPositions.getValue(DrawableId("mesh"))

        val newParentPoints = floatArrayOf(-25f, -15f, 115f, 10f, -5f, 105f, 110f, 95f)
        val command = canvasGeometryCommand(EditHierarchyMode.EDIT, "warp", "parent_warp", emptyMap(), newParentPoints)
        val next = CanvasEdits.apply(source, command)

        val after = evaluator.evaluate(next, emptyMap()).worldPositions.getValue(DrawableId("mesh"))
        for (i in before.indices) {
            assertEquals(before[i], after[i], 0.05f, "Child warp mesh world coord $i mismatch")
        }
    }
}

