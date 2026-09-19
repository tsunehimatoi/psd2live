package io.github.psd2live.core

import kotlinx.serialization.json.*
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Freely placed root warps must receive **model-space** bounds (parent-local at the root).
 * Camera-world Y (already negated) written straight into the lattice still looks like identity
 * (UV remapping cancels a uniform rectangle), then the cage is upside-down the moment it is edited.
 *
 * UI placement now stores parent-local directly and commits those fields as-is — same contract as
 * create-from-selection / createWarpFromBounds.
 */
class FreeWarpPlacementBoundsTest {
    private fun mesh() = Drawable(
        DrawableId("mesh"), "Mesh", null, BlendMode.Normal, emptyList(),
        DrawableMesh(
            floatArrayOf(10f, 30f, 90f, 30f, 90f, 90f, 10f, 90f),
            floatArrayOf(0f, 0f, 1f, 0f, 1f, 0f, 1f, 1f),
            intArrayOf(0, 1, 2, 0, 2, 3),
        ),
        null,
    )

    private fun source() = PuppetModel(
        emptyList(), emptyList(), emptyList(),
        listOf(mesh()), listOf(OrgChild.Drawable(DrawableId("mesh"))), null,
    )

    private fun create(boundsY: Float, boundsH: Float): PuppetModel {
        val cmd = buildJsonObject {
            put("op", "canvas_create_warp")
            put("id", "warp"); put("name", "Warp")
            put("rows", 1); put("columns", 1)
            put("meshes", JsonArray(listOf(JsonPrimitive("mesh"))))
            put("bounds", buildJsonObject {
                put("x", 5f); put("y", boundsY); put("w", 90f); put("h", boundsH)
            })
        }
        return CanvasEdits.apply(source(), cmd)
    }

    private fun latticeY(model: PuppetModel, row: Int, col: Int): Float {
        val warp = model.deformers.single() as Deformer.Warp
        val pts = warp.geometryGrid!!.cells.single().form.controlPoints
        val i = (row * (warp.columns + 1) + col) * 2
        return pts[i + 1]
    }

    @Test
    fun rootWarpWithModelSpaceBoundsPreservesMeshWorldPositions() {
        val created = create(25f, 70f)
        val evaluator = CpuDeformationEvaluator()
        val before = evaluator.evaluate(source(), emptyMap()).worldPositions.getValue(DrawableId("mesh"))
        val after = evaluator.evaluate(created, emptyMap()).worldPositions.getValue(DrawableId("mesh"))
        before.indices.forEach { assertEquals(before[it], after[it], 0.05f, "component=$it") }
    }

    @Test
    fun modelSpaceLatticeHasRow0BelowRow1() {
        val created = create(25f, 70f)
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
        val created = create(padded[1], padded[3])
        assertEquals(padded[1], latticeY(created, 0, 0), 1e-3f)
        assertEquals(padded[1] + padded[3], latticeY(created, 1, 0), 1e-3f)
    }

    @Test
    fun rawCameraWorldBoundsBuildUpsideDownLattice() {
        val created = create(-95f, 70f)
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
}
