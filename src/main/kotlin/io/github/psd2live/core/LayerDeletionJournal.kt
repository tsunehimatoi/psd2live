package io.github.psd2live.core

import kotlinx.serialization.json.*
import org.umamo.edit.withDrawablesDeleted
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PuppetModel

/**
 * `layer_delete`: a layer's meshes leave the authored rig, with every glue, mask, path and weight that refers to them.
 * The layer itself leaves the document in the same edit; the build checkpoints the authored rig right before this entry,
 * so nothing before it (a creation record that would need the layer's artwork) replays again. Meshes already gone - a
 * regeneration that stopped generating a deleted generation input dropped them - are skipped.
 *
 * Record: `{op, layer_id, meshes: [drawable id]}`.
 */
internal object LayerDeletionJournal {
    const val OP = "layer_delete"

    fun encode(layerId: String, meshes: Collection<String>): JsonObject = buildJsonObject {
        put("op", OP); put("layer_id", layerId); put("meshes", JsonArray(meshes.sorted().map(::JsonPrimitive)))
    }

    fun meshes(command: JsonObject): List<String> = command.getValue("meshes").jsonArray.map { it.jsonPrimitive.content }

    fun replay(model: PuppetModel, command: JsonObject): PuppetModel {
        val ids = meshes(command).map(::DrawableId).filterTo(HashSet()) { id -> model.drawables.any { it.id == id } }
        if (ids.isEmpty()) return model
        val removed = model.withDrawablesDeleted(ids)
        return removed.copy(deformPaths = removed.deformPaths.filterNot { it.drawableId in ids },
            vertexGroups = removed.vertexGroups.filterNot { it.drawableId in ids })
    }
}
