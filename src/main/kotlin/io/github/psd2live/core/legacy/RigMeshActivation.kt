package io.github.psd2live.core.legacy

import io.github.psd2live.core.*

import kotlinx.serialization.json.*
import org.umamo.runtime.model.*

/** A guide with real artwork can gain topology without losing its existing document identity. */
internal object RigMeshActivation {
    const val OP = "rig_mesh_activation"

    fun encode(current: PuppetModel, generated: BuiltRig, id: DrawableId): JsonObject {
        val previous = current.drawables.single { it.id == id }
        require(previous.mesh == null) { "Mesh activation requires an unmeshed drawable" }
        require(generated.puppet.drawables.single { it.id == id }.parentDeformerId == previous.parentDeformerId) {
            "Activated mesh must be generated in the guide's actual parent frame"
        }
        val creation = RasterMeshCreation.encode(generated, id)
        val texture = RasterMeshJournal.TextureCoordinates(current, previous)
        return JsonObject(creation + buildJsonObject {
            put("op", OP); put("source_id", texture.sourceId.raw); put("source", texture.layer.key)
            put("expected_parent", previous.parentDeformerId?.raw?.let(::JsonPrimitive) ?: JsonNull)
        })
    }

    fun replay(input: PuppetModel, command: JsonObject): PuppetModel {
        val id = DrawableId(command.getValue("id").jsonPrimitive.content)
        val previous = input.drawables.singleOrNull { it.id == id } ?: error("Mesh activation drawable is missing")
        require(previous.mesh == null && previous.parentDeformerId?.raw == command.getValue("expected_parent").jsonPrimitive.contentOrNull) {
            "Mesh activation drawable has changed"
        }
        val creation = JsonObject(command - "expected_parent" + ("op" to JsonPrimitive(RasterMeshCreation.OP)))
        val staged = RasterMeshCreation.replay(input.copy(drawables = input.drawables.filterNot { it.id == id }), creation)
        val generated = staged.drawables.single { it.id == id }
        require(generated.parentDeformerId == previous.parentDeformerId) { "Activated mesh parent frame has changed" }
        val count = requireNotNull(generated.mesh).positions.size
        require(previous.geometryGrid?.cells.orEmpty().all { it.form.positionDeltas.size == count } &&
            previous.blendShapes.flatMap { it.forms.filterNotNull() }.all { it.positionDeltas.size == count }) {
            "Unmeshed drawable has geometry forms without a matching vertex inventory"
        }
        val replacement = previous.copy(mesh = generated.mesh, atlasTileId = generated.atlasTileId, texturePage = generated.texturePage,
            geometryGrid = previous.geometryGrid ?: generated.geometryGrid)
        // The temporary creation's organizational membership is only a decoding aid. The
        // original guide's slot, material, channel tracks and authored parent remain authoritative.
        return input.copy(parameters = staged.parameters, drawables = input.drawables.map { if (it.id == id) replacement else it },
            deformPaths = input.deformPaths + staged.deformPaths.filter { path -> input.deformPaths.none { it.id == path.id } })
    }
}
