package io.github.psd2live.core

import kotlinx.serialization.json.*

/** Drops mesh edits whose vertex references belong to the mesh that was replaced. */
internal object MeshRebuildEdits {
    fun reset(
        overlay: RigEditOverlay,
        drawableId: String,
        previousVertexCount: Int,
        vertexCount: Int,
        meshSettingsChanged: Boolean = false,
    ): RigEditOverlay {
        val meshTarget = "mesh:$drawableId"
        val topologyChanged = meshSettingsChanged || previousVertexCount != vertexCount
        val journal = overlay.authoringJournal.mapNotNull { command ->
            when (command["op"]?.jsonPrimitive?.content) {
                "canvas_topology" -> if (command["id"]?.jsonPrimitive?.content == drawableId) null else command
                "canvas_geometry" -> {
                    val isTargetMesh = command["kind"]?.jsonPrimitive?.content == "mesh" &&
                        command["id"]?.jsonPrimitive?.content == drawableId
                    val pointsMatch = command["points"]?.jsonArray?.size == vertexCount * 2
                    val isBaseMove = command["key"]?.jsonObject.isNullOrEmpty()
                    if (isTargetMesh && (meshSettingsChanged || isBaseMove || !pointsMatch)) null else command
                }
                "set" -> {
                    val geometry = command["geometry"]?.jsonObject
                    val positions = geometry?.get("positionDeltas")?.jsonArray
                    if (command["target"]?.jsonPrimitive?.content == meshTarget &&
                        positions != null && positions.size != vertexCount * 2
                    ) {
                        if (command["channels"] == null) null else buildJsonObject {
                            command.forEach { (key, value) -> if (key != "geometry") put(key, value) }
                        }
                    } else command
                }
                "copy" -> {
                    val source = command["target"]?.jsonPrimitive?.content
                    val destination = command["destination"]?.jsonPrimitive?.content ?: source
                    val touchesMesh = source == meshTarget || destination == meshTarget
                    val channels = command["channels"]?.jsonArray
                    val copiesGeometry = channels == null || channels.any {
                        it.jsonPrimitive.content.equals("geometry", ignoreCase = true)
                    }
                    if (topologyChanged && touchesMesh && copiesGeometry) {
                        val retainedChannels = channels?.map { it.jsonPrimitive.content }?.filterNot {
                            it.equals("geometry", ignoreCase = true)
                        } ?: listOf("opacity", "draw_order", "multiply_color", "screen_color", "flip_x", "flip_y")
                        if (retainedChannels.isEmpty()) null else buildJsonObject {
                            command.forEach { (key, value) -> if (key != "channels") put(key, value) }
                            put("channels", JsonArray(retainedChannels.map { JsonPrimitive(it) }))
                        }
                    } else command
                }
                else -> command
            }
        }
        val keyformSets = overlay.keyformSetEdits.mapNotNull { edit ->
            val positions = edit.geometry?.positionDeltas
            if (edit.target.kind == RigTargetKind.ART_MESH && edit.target.id == drawableId &&
                positions != null && positions.size != vertexCount * 2
            ) {
                if (edit.channels == null) null else edit.copy(geometry = null)
            } else edit
        }
        val keyformCopies = overlay.keyformCopyEdits.mapNotNull { edit ->
            val copiesGeometry = edit.channels == null || edit.channels.any { it.equals("geometry", ignoreCase = true) }
            val touchesMesh =
                (edit.sourceTarget.kind == RigTargetKind.ART_MESH && edit.sourceTarget.id == drawableId) ||
                    (edit.destinationTarget.kind == RigTargetKind.ART_MESH && edit.destinationTarget.id == drawableId)
            if (topologyChanged && copiesGeometry && touchesMesh) {
                val retainedChannels = edit.channels?.filterNot { it.equals("geometry", ignoreCase = true) }
                    ?: listOf("opacity", "draw_order", "multiply_color", "screen_color", "flip_x", "flip_y")
                if (retainedChannels.isEmpty()) null else edit.copy(channels = retainedChannels)
            } else edit
        }
        return overlay.copy(
            keyformSetEdits = keyformSets,
            keyformCopyEdits = keyformCopies,
            authoringJournal = journal,
        )
    }
}
