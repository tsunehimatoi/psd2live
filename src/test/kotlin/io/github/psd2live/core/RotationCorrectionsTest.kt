package io.github.psd2live.core

import kotlinx.serialization.json.*
import org.umamo.runtime.model.*
import kotlin.test.*

class RotationCorrectionsTest {
    private fun command(key: Map<String, Float> = emptyMap(), id: String = "child") = buildJsonObject {
        put("op", "set"); put("target", "rotation:$id")
        put("key", JsonObject(key.mapValues { JsonPrimitive(it.value) }))
        putJsonObject("geometry") { put("originX", 4f); put("originY", 2f); put("angle", 15f); put("scale", 1.8f) }
    }
    @Test fun rotationCorrectionComposesAnglesAndPositiveScaleRatherThanVertexOffsets() {
        val base = deletionFixture().let { model -> model.copy(deformers = model.deformers.map {
            if (it.id.raw == "child") (it as Deformer.Rotation).copy(geometryGrid = KeyformGrid(
                listOf(KeyformAxis(ParameterId("p"), floatArrayOf(0f))),
                listOf(KeyformCell(intArrayOf(0), RotationPivotForm(3f, 2f, 5f, 1.2f))))) else it
        }) }
        val key = mapOf("p" to 0f)
        val record = RotationCorrections.capture(base, "child", key, command(key), "modifier:rotation")
        val current = base.copy(deformers = base.deformers.map { if (it.id.raw == "child") (it as Deformer.Rotation).copy(
            geometryGrid = KeyformGrid(listOf(KeyformAxis(ParameterId("p"), floatArrayOf(0f))), listOf(KeyformCell(intArrayOf(0), RotationPivotForm(6f, 4f, 25f, 2f))))) else it })
        val outcome = GeneratedOverrides.apply(current, record)
        val form = (outcome.model.deformers.single { it.id.raw == "child" } as Deformer.Rotation).geometryGrid!!.cells.single().form
        assertEquals(7f, form.originX); assertEquals(4f, form.originY); assertEquals(35f, form.angle)
        assertEquals(3f, form.scale, .00001f)
        assertTrue(outcome.issues.isEmpty())
        val detached = current.copy(deformers = current.deformers.map { if (it.id.raw == "child") (it as Deformer.Rotation).copy(parent = null) else it })
        assertEquals(GeneratedOverrideOrphanReason.SHAPE_MISMATCH, GeneratedOverrides.apply(detached, record).issues.single().reason)
    }
    @Test fun intermediateRotationKeyIsPreparedAndSurvivesRecordReplay() {
        val base = deletionFixture()
        val record = RotationCorrections.capture(base, "parent", mapOf("p" to .35f), command(mapOf("p" to .35f), "parent"),
            "modifier:rotation", absolute = true)
        assertTrue(record.getValue("insert_keys").jsonPrimitive.boolean)
        val result = GeneratedOverrides.apply(base, Json.parseToJsonElement(record.toString()).jsonObject)
        assertTrue(result.issues.isEmpty())
        val grid = (result.model.deformers.first { it.id.raw == "parent" } as Deformer.Rotation).geometryGrid!!
        assertContentEquals(floatArrayOf(-1f, .35f, 1f), grid.axes.single().keys)
        assertEquals(15f, grid.cells.first { it.coordinate[0] == 1 }.form.angle)
    }
}
