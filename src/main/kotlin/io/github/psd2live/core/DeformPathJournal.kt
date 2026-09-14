package io.github.psd2live.core

import kotlinx.serialization.json.*
import org.umamo.runtime.model.*

internal object DeformPathJournal {
    fun encode(path: DeformPath): JsonObject = buildJsonObject {
        put("op", "path_put"); put("id", path.id); put("target", "mesh:${path.drawableId.raw}")
        put("width", path.width); put("hardness", path.hardness); put("closed", path.closed); put("level", path.editLevel)
        put("points", JsonArray(path.points.map { p -> buildJsonObject {
            put("a",p.a);put("b",p.b);put("c",p.c);put("wa",p.wa);put("wb",p.wb);put("wc",p.wc);put("corner",p.corner)
        } }))
    }

    fun apply(model: PuppetModel, command: JsonObject): PuppetModel {
        val id=command.getValue("id").jsonPrimitive.content
        if(command.getValue("op").jsonPrimitive.content=="path_delete") {
            require(model.deformPaths.any { it.id==id }) { "Path not found: $id" }
            return model.copy(deformPaths=model.deformPaths.filterNot { it.id==id })
        }
        val target=RigAuthoringJournal.target(command.getValue("target").jsonPrimitive.content)
        require(target.kind==RigTargetKind.ART_MESH) { "Deform paths require an ArtMesh" }
        val mesh=requireNotNull(model.drawables.single { it.id.raw==target.id }.mesh)
        val path=DeformPath(id,DrawableId(target.id),command.getValue("points").jsonArray.map { e ->
            val p=e.jsonObject
            DeformPathPoint(p.getValue("a").jsonPrimitive.int,p.getValue("b").jsonPrimitive.int,p.getValue("c").jsonPrimitive.int,
                p.getValue("wa").jsonPrimitive.float,p.getValue("wb").jsonPrimitive.float,p.getValue("wc").jsonPrimitive.float,
                p["corner"]?.jsonPrimitive?.boolean ?: false)
        },command.getValue("width").jsonPrimitive.float,command.getValue("hardness").jsonPrimitive.float,
            command["closed"]?.jsonPrimitive?.boolean ?: false,command["level"]?.jsonPrimitive?.int ?: 2)
        for(p in path.points) {
            p.position(mesh.positions)
            require(mesh.indices.indices.step(3).any { i -> mesh.indices[i]==p.a && mesh.indices[i+1]==p.b && mesh.indices[i+2]==p.c }) {
                "Path must bind to an existing mesh triangle"
            }
        }
        require(model.deformPaths.none { it.id==id && it.drawableId!=path.drawableId }) { "Path belongs to another mesh" }
        return model.copy(deformPaths=model.deformPaths.filterNot { it.id==id }+path)
    }
}
