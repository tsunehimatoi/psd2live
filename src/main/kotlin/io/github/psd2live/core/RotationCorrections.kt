package io.github.psd2live.core

import kotlinx.serialization.json.*
import org.umamo.runtime.model.*
import kotlin.math.abs

/** Rotation geometry shares edit routing; angles are offsets and positive scales compose as ratios. */
internal object RotationCorrections {
    private fun rotation(model: PuppetModel, id: String) = model.deformers.firstOrNull { it.id.raw == id } as? Deformer.Rotation
    private fun cell(model: PuppetModel, id: String, key: Map<String, Float>): KeyformCell<RotationPivotForm>? {
        val grid = rotation(model, id)?.geometryGrid ?: return null
        if (grid.axes.map { it.parameterId.raw }.toSet() != key.keys) return null
        return grid.cells.firstOrNull { cell -> grid.axes.indices.all { axis ->
            abs(grid.axes[axis].keys[cell.coordinate[axis]] - key.getValue(grid.axes[axis].parameterId.raw)) < org.umamo.runtime.eval.EPS_KEY
        } }
    }
    private fun values(form: RotationPivotForm) = listOf(form.originX, form.originY, form.angle, form.scale)

    fun capture(model: PuppetModel, id: String, key: Map<String, Float>, command: JsonObject, generator: String,
                absolute: Boolean = false): JsonObject {
        val keyed = GeneratedOverrides.withGeometryKeys(model, "rotation", id, key)
        val base = requireNotNull(cell(keyed, id, key)) { "Rotation has no editable keyform" }
        val edited = RigAuthoringJournal.apply(keyed, command)
        val result = requireNotNull(cell(edited, id, key))
        return buildJsonObject {
            put("op", GeneratedOverrides.OP); put("generator", generator); put("target", "rotation:$id")
            put("key", JsonObject(key.mapValues { JsonPrimitive(it.value) }))
            put("base", JsonArray(values(base.form).map(::JsonPrimitive)))
            put("points", JsonArray(values(result.form).map(::JsonPrimitive)))
            put("merge_mode", if (absolute) "absolute" else "delta")
            put("geometry_binding", GeneratedOverrides.geometryBinding(model, "rotation", id))
            if (keyed !== model) put("insert_keys", true)
        }
    }

    fun apply(model: PuppetModel, command: JsonObject): GeneratedOverrides.Outcome {
        val target = command.getValue("target").jsonPrimitive.content
        val id = target.substringAfter(':')
        val generator = command.getValue("generator").jsonPrimitive.content
        val key = command.getValue("key").jsonObject.mapValues { it.value.jsonPrimitive.float }
        val base = command.getValue("base").jsonArray.map { it.jsonPrimitive.float }
        val points = command.getValue("points").jsonArray.map { it.jsonPrimitive.float }
        require(base.size == 4 && points.size == 4 && (base + points).all(Float::isFinite) && base[3] > 0f && points[3] > 0f)
        fun orphan(reason: GeneratedOverrideOrphanReason) = GeneratedOverrides.Outcome(model, listOf(GeneratedOverrideIssue(
            GeneratedOverrideIssueKind.ORPHANED, generator, target, key, 4, 4, reason)))
        if (command["geometry_binding"]?.jsonPrimitive?.content != GeneratedOverrides.geometryBinding(model, "rotation", id))
            return orphan(GeneratedOverrideOrphanReason.SHAPE_MISMATCH)
        val keyed = if (command["insert_keys"]?.jsonPrimitive?.booleanOrNull == true)
            GeneratedOverrides.withGeometryKeys(model, "rotation", id, key) else model
        val existing = cell(keyed, id, key) ?: return orphan(GeneratedOverrideOrphanReason.MISSING_KEYFORM)
        val current = values(existing.form)
        val mode = command["merge_mode"]?.jsonPrimitive?.content ?: "absolute"
        require(mode in setOf("absolute", "delta"))
        val conflicts = current.indices.count { abs(points[it] - base[it]) > 1e-4f && abs(current[it] - base[it]) > 1e-4f && abs(current[it] - points[it]) > 1e-4f }
        val result = current.indices.map { i -> if (mode == "delta") {
            if (i == 3) current[i] * (points[i] / base[i]) else current[i] + points[i] - base[i]
        } else if (abs(points[i] - base[i]) > 1e-4f) points[i] else current[i] }
        require(result.all(Float::isFinite) && result[3] > 0f) { "Invalid corrected rotation" }
        val rotation = rotation(keyed, id)!!
        val grid = rotation.geometryGrid!!
        val next = keyed.withReplacedGeometryGrid(KeyformOwner.Deformer(rotation.id), KeyformGrid(grid.axes, grid.cells.map {
            if (it === existing) KeyformCell(it.coordinate, RotationPivotForm(result[0], result[1], result[2], result[3])) else it
        }))
        val issues = if (mode == "absolute" && conflicts > 0) listOf(GeneratedOverrideIssue(
            GeneratedOverrideIssueKind.CONFLICT, generator, target, key, conflicts, 4)) else emptyList()
        return GeneratedOverrides.Outcome(next, issues)
    }
}
