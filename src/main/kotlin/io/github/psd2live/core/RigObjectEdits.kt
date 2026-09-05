package io.github.psd2live.core

import kotlinx.serialization.json.*
import org.umamo.runtime.model.*

/** An identity child warp preserves every existing mesh keyform and the inherited deformation. */
data class RigWarpEdit(val id: String, val name: String, val parentId: String,
    val meshIds: List<String>, val rows: Int = 4, val columns: Int = 4) {
    init {
        require(listOf(id, name, parentId).all { it.isNotBlank() && it.none(Char::isISOControl) })
        require(id != parentId && rows in 1..32 && columns in 1..32)
        require(meshIds.isNotEmpty() && meshIds.distinct().size == meshIds.size)
    }

    fun applyTo(model: PuppetModel): PuppetModel {
        require(model.deformers.none { it.id.raw == id }) { "Deformer ID already exists: $id" }
        val parent = model.deformers.singleOrNull { it.id.raw == parentId } as? Deformer.Warp
            ?: error("Parent must be an existing Warp; inspect object_get for the mesh parent")
        for (meshId in meshIds) {
            val mesh = model.drawables.singleOrNull { it.id.raw == meshId }
                ?: error("Mesh not found: $meshId")
            require(mesh.parentDeformerId == parent.id) { "All meshes must share parent $parentId; moving across coordinate spaces is unsupported" }
        }
        // The renderer bakes parent deformation at child lattice nodes. Align knots with the
        // parent (and its triangle diagonals), otherwise even an identity child resamples motion.
        require(parent.rows > 0 && parent.columns > 0)
        val refinement = maxOf(1, (rows + parent.rows - 1) / parent.rows, (columns + parent.columns - 1) / parent.columns)
        val effectiveRows = parent.rows * refinement
        val effectiveColumns = parent.columns * refinement
        require(effectiveRows <= 64 && effectiveColumns <= 64) { "Parent-aligned Warp lattice exceeds 64 divisions" }
        val points = FloatArray((effectiveRows + 1) * (effectiveColumns + 1) * 2)
        var i = 0
        for (r in 0..effectiveRows) for (c in 0..effectiveColumns) {
            points[i++] = c.toFloat() / effectiveColumns
            points[i++] = r.toFloat() / effectiveRows
        }
        val warp = Deformer.Warp(DeformerId(id), name, parent.id, parent.partId, effectiveRows, effectiveColumns, parent.isQuadTransform,
            KeyformGrid(emptyList(), listOf(KeyformCell(intArrayOf(), WarpLatticeForm(points)))))
        return model.copy(deformers = model.deformers + warp,
            drawables = model.drawables.map { if (it.id.raw in meshIds) it.copy(parentDeformerId = warp.id) else it })
            .withDerivedRenderRoot()
    }

    fun toJson() = buildJsonObject {
        put("id", id); put("name", name); put("parent_id", parentId)
        put("rows", rows); put("columns", columns)
        putJsonArray("mesh_ids") { meshIds.forEach { add(JsonPrimitive(it)) } }
    }
    companion object {
        fun fromJson(o: JsonObject) = RigWarpEdit(o.text("id"), o.text("name"), o.text("parent_id"),
            requireNotNull(o["mesh_ids"]) { "mesh_ids is required" }.jsonArray.map { it.jsonPrimitive.content },
            o["rows"]?.jsonPrimitive?.int ?: 4, o["columns"]?.jsonPrimitive?.int ?: 4)
    }
}

/** Independently named, adjustable two-particle pendulum, driving an explicit Cubism parameter. */
data class RigPhysicsEdit(val id: String, val name: String, val inputParameter: String,
    val outputParameter: String, val length: Float = 10f, val mobility: Float = 0.8f,
    val delay: Float = 0.8f, val acceleration: Float = 1f, val outputScale: Float = 1f) {
    init {
        require(listOf(id, name, inputParameter, outputParameter).all { it.isNotBlank() && it.none(Char::isISOControl) })
        require(inputParameter != outputParameter) { "Physics input and output must differ" }
        require(length.isFinite() && length > 0f && mobility.isFinite() && mobility in 0f..1f)
        require(delay.isFinite() && delay > 0f && acceleration.isFinite() && acceleration >= 0f && outputScale.isFinite())
    }
    internal fun rule() = PhysicsGenerator.PhysicsRule(id, name, outputParameter, outputScale, 1,
        listOf(PhysicsGenerator.InputRule(inputParameter, 100f, PhysicsGenerator.InputType.ANGLE)),
        listOf(PhysicsGenerator.VertexRule(0f, 1f, 1f, 1f, 0f),
            PhysicsGenerator.VertexRule(length, mobility, delay, acceleration, length)),
        -10f, 0f, 10f, -30f, 0f, 30f)
    fun validate(parameterIds: Set<String>) {
        require(inputParameter in parameterIds && outputParameter in parameterIds) { "Physics input/output parameter does not exist" }
    }
    fun toJson() = buildJsonObject {
        put("id", id); put("name", name); put("input_parameter", inputParameter); put("output_parameter", outputParameter)
        put("length", length); put("mobility", mobility); put("delay", delay); put("acceleration", acceleration); put("output_scale", outputScale)
    }
    companion object {
        fun fromJson(o: JsonObject) = RigPhysicsEdit(o.text("id"), o.text("name"), o.text("input_parameter"),
            o.text("output_parameter"), o.number("length", 10f), o.number("mobility", .8f),
            o.number("delay", .8f), o.number("acceleration", 1f), o.number("output_scale", 1f))
    }
}

private fun JsonObject.text(key: String) = requireNotNull(get(key)?.jsonPrimitive?.contentOrNull) { "$key is required" }
private fun JsonObject.number(key: String, fallback: Float) = get(key)?.jsonPrimitive?.float ?: fallback
