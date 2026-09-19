package io.github.psd2live.core

import kotlinx.serialization.json.*
import org.umamo.runtime.model.*
import kotlin.test.Test
import kotlin.test.assertEquals

/** Tip-rotate must not rewrite stored scale (that resizes every child under a Warp parent). */
class RotationTipKeepScaleTest {
    @Test
    fun tipRotateWithKeepScalePreservesScale() {
        val id = DeformerId("rot")
        val rotation = Deformer.Rotation(
            id, "Rot", null, null, 0f,
            KeyformGrid(
                emptyList(),
                listOf(KeyformCell(intArrayOf(), RotationPivotForm(0f, 0f, 0f, 2f))),
            ),
        )
        val model = PuppetModel(
            emptyList(), emptyList(), listOf(rotation), emptyList(), emptyList(), null,
        )
        // Arm length 200 with reference length 100 → scale 2. Rotate 90° with same length.
        val cmd = buildJsonObject {
            put("op", "canvas_geometry")
            put("kind", "rotation"); put("id", "rot")
            put("key", JsonObject(emptyMap()))
            put("keep_scale", true)
            put("points", JsonArray(listOf(0f, 0f, 0f, 200f).map(::JsonPrimitive)))
        }
        val next = CanvasEdits.apply(model, cmd)
        val form = (next.deformers.single() as Deformer.Rotation).geometryGrid!!.cells.single().form
        assertEquals(2f, form.scale, 1e-4f)
        assertEquals(90f, form.angle, 1e-3f)
    }

    @Test
    fun altScaleWithoutKeepScaleUpdatesScale() {
        val id = DeformerId("rot")
        val rotation = Deformer.Rotation(
            id, "Rot", null, null, 0f,
            KeyformGrid(
                emptyList(),
                listOf(KeyformCell(intArrayOf(), RotationPivotForm(0f, 0f, 0f, 1f))),
            ),
        )
        val model = PuppetModel(
            emptyList(), emptyList(), listOf(rotation), emptyList(), emptyList(), null,
        )
        val cmd = buildJsonObject {
            put("op", "canvas_geometry")
            put("kind", "rotation"); put("id", "rot")
            put("key", JsonObject(emptyMap()))
            put("points", JsonArray(listOf(0f, 0f, 200f, 0f).map(::JsonPrimitive)))
        }
        val next = CanvasEdits.apply(model, cmd)
        val form = (next.deformers.single() as Deformer.Rotation).geometryGrid!!.cells.single().form
        assertEquals(2f, form.scale, 1e-4f)
    }
}
