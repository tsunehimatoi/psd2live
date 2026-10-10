package io.github.psd2live.core

import kotlinx.serialization.json.*
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Place-then-confirm commits parent-local bounds directly (screen → [local] → bounds), never a camera-world AABB. At the root that is model space;
 * under a Warp parent it is UV.
 *
 * Camera-world Y (already negated) written straight into a root lattice still looks like identity (UV remapping
 * cancels a uniform rectangle), then the cage is upside-down the moment it is edited.
 */
class PlacementParentLocalBoundsTest {
    private fun rootMesh() = Drawable(
        DrawableId("mesh"), "Mesh", null, BlendMode.Normal, emptyList(),
        DrawableMesh(
            floatArrayOf(10f, 30f, 90f, 30f, 90f, 90f, 10f, 90f),
            floatArrayOf(0f, 0f, 1f, 0f, 1f, 0f, 1f, 1f),
            intArrayOf(0, 1, 2, 0, 2, 3),
        ),
        null,
    )

    private fun meshUnderWarp() = Drawable(
        DrawableId("mesh"), "Mesh", DeformerId("parent"), BlendMode.Normal, emptyList(),
        // UV positions covering most of the parent cage.
        DrawableMesh(
            floatArrayOf(0.1f, 0.1f, 0.9f, 0.1f, 0.9f, 0.9f, 0.1f, 0.9f),
            floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f),
            intArrayOf(0, 1, 2, 0, 2, 3),
        ),
        null,
    )

    private fun parentWarp() = Deformer.Warp(
        DeformerId("parent"), "Parent", null, null, 1, 1, true,
        KeyformGrid(
            emptyList(),
            listOf(
                KeyformCell(
                    intArrayOf(),
                    WarpLatticeForm(floatArrayOf(0f, 0f, 100f, 0f, 0f, 100f, 100f, 100f)),
                ),
            ),
        ),
    )

    private fun rootSource() = PuppetModel(
        emptyList(), emptyList(), emptyList(),
        listOf(rootMesh()), listOf(OrgChild.Drawable(DrawableId("mesh"))), null,
    )

    private fun nestedSource() = PuppetModel(
        emptyList(), emptyList(), listOf(parentWarp()),
        listOf(meshUnderWarp()), listOf(OrgChild.Drawable(DrawableId("mesh"))), null,
    )

    /** A 1x1 warp `id` over the mesh with bounds `x, y, w, h`, at the root or beside the mesh under its parent. */
    private fun create(source: PuppetModel, id: String, x: Float, y: Float, w: Float, h: Float): PuppetModel {
        val cmd = buildJsonObject {
            put("op", "canvas_create_warp")
            put("id", id); put("name", id)
            put("rows", 1); put("columns", 1)
            if (source.deformers.isNotEmpty()) put("add_to", "parent_of_selected")
            put("meshes", JsonArray(listOf(JsonPrimitive("mesh"))))
            put("bounds", buildJsonObject {
                put("x", x); put("y", y); put("w", w); put("h", h)
            })
        }
        return CanvasEdits.apply(source, cmd)
    }

    private fun createAtRoot(boundsY: Float, boundsH: Float) = create(rootSource(), "warp", 5f, boundsY, 90f, boundsH)

    private fun latticeY(model: PuppetModel, row: Int, col: Int): Float {
        val warp = model.deformers.single() as Deformer.Warp
        val pts = warp.geometryGrid!!.cells.single().form.controlPoints
        val i = (row * (warp.columns + 1) + col) * 2
        return pts[i + 1]
    }

    private fun assertSameMeshWorld(source: PuppetModel, created: PuppetModel) {
        val evaluator = CpuDeformationEvaluator()
        val before = evaluator.evaluate(source, emptyMap()).worldPositions.getValue(DrawableId("mesh"))
        val after = evaluator.evaluate(created, emptyMap()).worldPositions.getValue(DrawableId("mesh"))
        before.indices.forEach { assertEquals(before[it], after[it], 0.05f, "component=$it") }
    }

    @Test
    fun rootWarpWithModelSpaceBoundsPreservesMeshWorldPositions() {
        assertSameMeshWorld(rootSource(), createAtRoot(25f, 70f))
    }

    @Test
    fun modelSpaceLatticeHasRow0BelowRow1() {
        val created = createAtRoot(25f, 70f)
        assertEquals(25f, latticeY(created, 0, 0), 1e-4f)
        assertEquals(95f, latticeY(created, 1, 0), 1e-4f)
        assertTrue(latticeY(created, 0, 0) < latticeY(created, 1, 0))
    }

    @Test
    fun selectionStylePaddedBoundsMatchCreateFromSelection() {
        // Mesh y∈[30,90] → 5% pad → y=27, h=66 — the non-UI selection_bounds formula.
        val positions = floatArrayOf(10f, 30f, 90f, 30f, 90f, 90f, 10f, 90f)
        val b = RigGeometryTools.bounds(positions)
        val padded = floatArrayOf(
            b[0] - b[2] * 0.05f,
            b[1] - b[3] * 0.05f,
            b[2] * 1.1f,
            b[3] * 1.1f,
        )
        val created = createAtRoot(padded[1], padded[3])
        assertEquals(padded[1], latticeY(created, 0, 0), 1e-3f)
        assertEquals(padded[1] + padded[3], latticeY(created, 1, 0), 1e-3f)
    }

    @Test
    fun rawCameraWorldBoundsBuildUpsideDownLattice() {
        val created = createAtRoot(-95f, 70f)
        // Row 0 sits at camera-min (= model TOP). Editing that row moves the visual top the wrong way.
        assertEquals(-95f, latticeY(created, 0, 0), 1e-4f)
        assertEquals(-25f, latticeY(created, 1, 0), 1e-4f)
        assertTrue(
            latticeY(created, 0, 0) < latticeY(created, 1, 0),
            "numeric Y still increases with row, but the range is the negated model box",
        )
        assertTrue(latticeY(created, 1, 0) < 0f)
        assertTrue(latticeY(created, 0, 0) < -50f)
    }

    @Test
    fun parentLocalUvBoundsDoNotShrinkToCenter() {
        // Selection-style padded UV box — what beginPlacement writes.
        val created = create(nestedSource(), "child", 0.06f, 0.06f, 0.88f, 0.88f)
        val child = created.deformers.single { it.id.raw == "child" } as Deformer.Warp
        val pts = child.geometryGrid!!.cells.single().form.controlPoints
        val xs = pts.filterIndexed { i, _ -> i % 2 == 0 }
        val ys = pts.filterIndexed { i, _ -> i % 2 == 1 }
        assertEquals(0.06f, xs.min(), 1e-4f)
        assertEquals(0.06f, ys.min(), 1e-4f)
        assertEquals(0.94f, xs.max(), 1e-4f)
        assertEquals(0.94f, ys.max(), 1e-4f)
        assertTrue(xs.max() - xs.min() > 0.8f, "bounds shrunk toward center")
        assertTrue(ys.max() - ys.min() > 0.8f, "bounds shrunk toward center")
    }

    @Test
    fun parentLocalUvBoundsPreserveMeshWorldPositions() {
        assertSameMeshWorld(nestedSource(), create(nestedSource(), "child", 0.1f, 0.1f, 0.8f, 0.8f))
    }
}
