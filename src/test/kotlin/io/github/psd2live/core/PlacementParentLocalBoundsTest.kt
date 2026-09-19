package io.github.psd2live.core

import kotlinx.serialization.json.*
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Place-then-confirm commits parent-local bounds directly. Under a Warp parent that means UV —
 * the same contract as createWarpFromBounds (screen → [local] → bounds), never a camera-world AABB.
 */
class PlacementParentLocalBoundsTest {
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

    private fun source() = PuppetModel(
        emptyList(), emptyList(), listOf(parentWarp()),
        listOf(meshUnderWarp()), listOf(OrgChild.Drawable(DrawableId("mesh"))), null,
    )

    @Test
    fun parentLocalUvBoundsDoNotShrinkToCenter() {
        // Selection-style padded UV box — what beginPlacement / createWarpFromBounds write.
        val cmd = buildJsonObject {
            put("op", "canvas_create_warp")
            put("id", "child"); put("name", "Child")
            put("rows", 1); put("columns", 1)
            put("add_to", "parent_of_selected")
            put("meshes", JsonArray(listOf(JsonPrimitive("mesh"))))
            put("bounds", buildJsonObject {
                put("x", 0.06f); put("y", 0.06f); put("w", 0.88f); put("h", 0.88f)
            })
        }
        val created = CanvasEdits.apply(source(), cmd)
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
        val cmd = buildJsonObject {
            put("op", "canvas_create_warp")
            put("id", "child"); put("name", "Child")
            put("rows", 1); put("columns", 1)
            put("add_to", "parent_of_selected")
            put("meshes", JsonArray(listOf(JsonPrimitive("mesh"))))
            put("bounds", buildJsonObject {
                put("x", 0.1f); put("y", 0.1f); put("w", 0.8f); put("h", 0.8f)
            })
        }
        val evaluator = CpuDeformationEvaluator()
        val before = evaluator.evaluate(source(), emptyMap()).worldPositions.getValue(DrawableId("mesh"))
        val after = evaluator.evaluate(CanvasEdits.apply(source(), cmd), emptyMap())
            .worldPositions.getValue(DrawableId("mesh"))
        before.indices.forEach { assertEquals(before[it], after[it], 0.05f, "component=$it") }
    }
}
