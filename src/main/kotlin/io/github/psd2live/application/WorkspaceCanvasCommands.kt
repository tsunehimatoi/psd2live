package io.github.psd2live.application

import io.github.psd2live.core.SkeletonRig
import io.github.psd2live.core.StableIds
import kotlinx.serialization.json.*
import org.umamo.render.eval.buildDeformerWorlds
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel

/** Materialize canvas requests against the model belonging to this candidate document. */
internal object WorkspaceCanvasCommands {
    fun create(model: PuppetModel, mode: String, request: JsonObject): JsonObject {
        val op = when (mode) {
            "warp" -> "canvas_create_warp"
            "rotation" -> "canvas_create_rotation"
            "glue" -> "canvas_create_glue"
            "topology" -> "canvas_topology"
            else -> throw IllegalArgumentException("Unknown canvas mode: $mode")
        }
        val id = request["id"]?.jsonPrimitive?.content
            ?: StableIds.of("Agent${mode.replaceFirstChar(Char::uppercase)}_", request) { id ->
                model.deformers.any { it.id.raw == id } || model.glues.any { it.id == id } }
        return buildJsonObject {
            put("op", op); put("id", id)
            request.forEach { (key, value) -> if (key !in setOf("mode", "state", "project_id", "request_id", "id")) put(key, value) }
            // A new rotation keeps every mesh where it shows, as the canvas makes it; only journals written before
            // this replay the old remount, which moved meshes by the pivot.
            if (mode == "rotation" && "preservePose" !in request) put("preservePose", true)
            if (mode == "rotation") request["origin"]?.let { put("origin", parentLocalOrigin(model, request, it.jsonArray)) }
            if (mode == "glue") {
                fun mesh(key: String): String {
                    val raw = request.getValue(key).jsonPrimitive.content.trim()
                    require(raw.isNotEmpty()) { "Glue requires two different meshes" }
                    return model.drawables.firstOrNull { it.id.raw == raw && it.mesh != null }?.id?.raw
                        ?: throw IllegalArgumentException("Mesh not found: $raw. Glue requires two different art-mesh ids from inspect.")
                }
                val meshA = mesh("mesh_a"); val meshB = mesh("mesh_b")
                require(meshA != meshB) { "Glue requires two different meshes" }
                put("mesh_a", meshA); put("mesh_b", meshB)
                if (request["replace"]?.jsonPrimitive?.boolean == true) {
                    model.glues.firstOrNull { glue ->
                        (glue.meshA.raw == meshA && glue.meshB.raw == meshB) ||
                            (glue.meshA.raw == meshB && glue.meshB.raw == meshA)
                    }?.id?.let { put("id", it) }
                }
            }
        }
    }

    /**
     * A rotation's pivot, given in canvas pixels as agents see the model, in the space the journal stores it: the
     * local space of the deformer the rotation will hang from (a warp's lattice, a rotation's frame), at rest. A pivot
     * left in canvas units under a warp would sit hundreds of lattice widths outside it.
     */
    private fun parentLocalOrigin(model: PuppetModel, request: JsonObject, origin: JsonArray): JsonArray {
        require(origin.size == 2) { "Rotation origin must be [x, y]" }
        val x = origin[0].jsonPrimitive.float; val y = origin[1].jsonPrimitive.float
        val parent = if (request["add_to"]?.jsonPrimitive?.contentOrNull == "parent_of_deformer")
            model.deformers.firstOrNull { it.id.raw == request["deformer_id"]?.jsonPrimitive?.contentOrNull }?.parent
        else request["meshes"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.contentOrNull
            ?.let { mesh -> model.drawables.firstOrNull { it.id.raw == mesh }?.parentDeformerId }
        val defaults: (ParameterId) -> Float = { id -> model.parameters.firstOrNull { it.id == id }?.default ?: 0f }
        val world = parent?.let { buildDeformerWorlds(model.deformers, defaults, defaults)[it] } ?: return origin
        val local = SkeletonRig.inverse(world, x, y)
        return JsonArray(local.map(::JsonPrimitive))
    }
}
