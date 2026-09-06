package io.github.psd2live.agent

import io.github.psd2live.core.RigGeometryTools
import kotlinx.serialization.json.*
import org.umamo.runtime.model.*

internal object AgentRigGeometry {
    fun pose(a: JsonObject): Map<String, Float> = a["coordinate"]?.jsonObject?.mapValues { it.value.jsonPrimitive.float }.orEmpty()
    fun kind(a: JsonObject) = a.getValue("target").jsonObject.getValue("kind").jsonPrimitive.content
    fun id(a: JsonObject) = a.getValue("target").jsonObject.getValue("id").jsonPrimitive.content

    fun inspect(model: PuppetModel, a: JsonObject, revision: String): JsonObject {
        val kind=kind(a); val id=id(a); val pose=pose(a)
        val detail=a["detail"]?.jsonPrimitive?.content ?: "summary"
        require(detail in setOf("summary","points"))
        val g=RigGeometryTools.geometry(model,kind,id,pose)
        val offset=a["offset"]?.jsonPrimitive?.int ?: 0
        val limit=a["limit"]?.jsonPrimitive?.int ?: 64
        require(offset in 0..g.points.size/2 && limit in 1..256)
        val space=a["space"]?.jsonPrimitive?.content ?: "local"
        require(space in setOf("local","canvas"))
        return buildJsonObject {
            put("revisionId",revision);put("id",id);put("kind",kind);put("name",g.name);g.parent?.let { put("parentId",it) }
            put("coordinateSpace",if(space=="local") "parent_local_x_right_y_down" else "canvas_x_right_y_down")
            put("coordinate",buildJsonObject { (model.parameters.associate { it.id.raw to it.default }+pose).forEach { (k,v)->put(k,v) } })
            put("geometryRepresentation", if(kind=="warp") "sampled_warp_lattice" else "triangle_mesh")
            putJsonObject("nativeBezier") {
                put("available",false)
                put("reason","Runtime stores sampled positions, not native Bezier anchors or handles. Editor subdivision metadata does not supply control geometry.")
            }
            put("recommendedEditTool","rig_transform")
            put("pointCount",g.points.size/2);put("keyformCount",g.keyCount)
            g.rows?.let { put("rows",it) };g.columns?.let { put("columns",it) }
            putJsonArray("axes") { g.axes.forEach { axis -> add(buildJsonObject { put("id",axis.parameterId.raw);put("keys",JsonArray(axis.keys.map(::JsonPrimitive))) }) } }
            put("cost",buildJsonObject { put("localPointScalars",g.points.size);put("allKeyformScalars",g.points.size*g.keyCount) })
            put("localBounds",JsonArray(RigGeometryTools.bounds(g.points).map(::JsonPrimitive)))
            if(detail=="points") {
                val points=if(space=="local")g.points else {
                    val typed=pose.mapKeys { ParameterId(it.key) }
                    val world=if(kind=="warp") io.github.psd2live.ui.RigInformationOverlay.warpPoints(model,typed,setOf(id)).getValue(id)
                        else org.umamo.render.eval.CpuDeformationEvaluator().evaluate(model,typed).worldPositions[DrawableId(id)] ?: error("Mesh hidden at pose")
                    world.copyOf().also { p -> for(i in 1 until p.size step 2)p[i] = -p[i] }
                }
                val end=minOf(offset+limit,points.size/2)
                putJsonArray("points") { for(i in offset until end)add(buildJsonArray { add(JsonPrimitive(i));add(JsonPrimitive(points[i*2]));add(JsonPrimitive(points[i*2+1])) }) }
                if(end<points.size/2)put("nextOffset",end)
            }

        }
    }
}
