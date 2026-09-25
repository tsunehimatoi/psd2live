package io.github.psd2live.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.umamo.edit.withParameterCreated
import org.umamo.format.moc3.Moc3
import org.umamo.interop.moc3.export.Moc3Export
import org.umamo.interop.moc3.import.Moc3Import
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.ParameterKind
import org.umamo.runtime.model.PuppetModel
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BlendShapeAuthoringTest {
    private val meshId = DrawableId("mesh")
    private val blend = ParameterId("ParamBlend")

    private fun model(): PuppetModel {
        val drawable = Drawable(
            meshId, "Mesh", null, BlendMode.Normal, emptyList(),
            DrawableMesh(floatArrayOf(0f, 0f, 10f, 0f, 0f, 10f), floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f), intArrayOf(0, 1, 2)),
            null,
        )
        return PuppetModel(
            parameters = emptyList(),
            parts = emptyList(),
            deformers = emptyList(),
            drawables = listOf(drawable),
            rootChildren = emptyList(),
            rootPartId = null,
        ).withParameterCreated(blend, "Blend", ParameterKind.BLEND_SHAPE)
    }

    @Test fun captureAddsTheDeltaAndNeutralStaysAtRest() {
        val created = model()
        val parameter = created.parameters.single()
        assertEquals(ParameterKind.BLEND_SHAPE, parameter.kind)
        assertEquals(listOf(0f, 1f), parameter.keys)
        val posed = applyKeyformSet(
            created,
            RigKeyformSetEdit(
                RigTargetRef(RigTargetKind.ART_MESH, meshId.raw),
                mapOf(blend.raw to 1f),
                RigKeyformGeometryEdit(positionDeltas = listOf(0f, 0f, 10f, 0f, 4f, 6f)),
            ),
        )
        val binding = posed.drawables.single().blendShapes.single()
        assertEquals(0, binding.neutralIndex)
        assertEquals(null, binding.forms[0])
        assertEquals(4f, binding.forms[1]!!.positionDeltas[4])
        val evaluator = CpuDeformationEvaluator()
        val rest = evaluator.evaluate(posed, mapOf(blend to 0f)).worldPositions.getValue(meshId)
        val full = evaluator.evaluate(posed, mapOf(blend to 1f)).worldPositions.getValue(meshId)
        assertTrue(abs(rest[4] - 0f) < 1e-3f)
        assertTrue(abs(full[4] - 4f) < 1e-3f || abs(full[4] + 4f) < 1e-3f)
        val halfway = evaluator.evaluate(posed, mapOf(blend to 0.5f)).worldPositions.getValue(meshId)
        val span = full[4] - rest[4]
        assertTrue(abs((halfway[4] - rest[4]) - span * 0.5f) < 1e-3f)
    }

    @Test fun neutralKeyCannotMove() {
        val created = model()
        val edit = buildJsonObject {
            put("op", JsonPrimitive("parameter_keys"))
            put("parameter", JsonPrimitive(blend.raw))
            put("action", JsonPrimitive("move"))
            put("from", JsonPrimitive(0f))
            put("values", JsonArray(listOf(JsonPrimitive(0.2f))))
        }
        assertFailsWith<IllegalArgumentException> { RigAuthoringJournal.apply(created, edit) }
    }

    @Test fun exportRoundTripKeepsTheBlend() {
        val posed = applyKeyformSet(
            model(),
            RigKeyformSetEdit(
                RigTargetRef(RigTargetKind.ART_MESH, meshId.raw),
                mapOf(blend.raw to 1f),
                RigKeyformGeometryEdit(positionDeltas = listOf(0f, 0f, 10f, 0f, 4f, 6f)),
            ),
        )
        val lowered = Moc3Export.toMocDocument(posed)
        val bytes = Moc3.write(lowered.document)
        val imported = Moc3Import.fromMocDocument(Moc3.read(bytes), null)
        assertEquals(ParameterKind.BLEND_SHAPE, imported.parameters.single { it.id == blend }.kind)
        val evaluator = CpuDeformationEvaluator()
        val before = evaluator.evaluate(posed, mapOf(blend to 1f)).worldPositions.getValue(meshId)
        val afterId = imported.drawables.single().id
        val after = evaluator.evaluate(imported, mapOf(blend to 1f)).worldPositions.getValue(afterId)
        for (index in before.indices) assertTrue(abs(before[index] - after[index]) < 1e-2f, "index $index ${before[index]} vs ${after[index]}")
    }
}
