package io.github.psd2live.application

import io.github.psd2live.core.RigInformationOverlay

import io.github.psd2live.core.RigGeometryTools
import io.github.psd2live.core.RigEditOverlay
import io.github.psd2live.core.RigBezierJournal
import kotlinx.serialization.json.*
import org.umamo.runtime.model.*

internal object WorkspaceRigGeometry {
    fun pose(a: JsonObject): Map<String, Float> = a["coordinate"]?.jsonObject?.mapValues { it.value.jsonPrimitive.float }.orEmpty()
    fun kind(a: JsonObject) = a.getValue("target").jsonObject.getValue("kind").jsonPrimitive.content
    fun id(a: JsonObject) = a.getValue("target").jsonObject.getValue("id").jsonPrimitive.content

    fun inspect(model: PuppetModel, a: JsonObject, revision: String, overlay: RigEditOverlay = RigEditOverlay.Empty): JsonObject {
        val kind=kind(a); val id=id(a); val pose=pose(a)
        val detail=a["detail"]?.jsonPrimitive?.content ?: "summary"
        require(detail in setOf("summary","points"))
        val source=RigGeometryTools.geometry(model,kind,id,pose)
        val preview=a["operations"]?.jsonArray
        val g=if(preview==null) source else source.copy(points=RigGeometryTools.transform(source,preview,
            a["selection"]?.jsonObject ?: JsonObject(emptyMap()), a["range"]?.jsonObject ?: JsonObject(emptyMap())))
        val reference=if(preview==null) RigGeometryTools.geometry(model,kind,id,emptyMap()).points else source.points
        val triangles=if(kind=="warp") io.github.psd2live.core.quality.RigGeometryDiagnostics.lattice(g.rows!!,g.columns!!)
            else model.drawables.single { it.id.raw==id }.mesh!!.indices
        val offset=a["offset"]?.jsonPrimitive?.int ?: 0
        val limit=a["limit"]?.jsonPrimitive?.int ?: 64
        val controls = if(kind == "warp" && preview == null) RigBezierJournal.read(model, overlay, id, pose) else null
        val controlCount = controls?.let { it.state.anchors.size + it.state.handles.size } ?: 0
        require(offset in 0..maxOf(g.points.size/2, controlCount) && limit in 1..256)
        val space=a["space"]?.jsonPrimitive?.content ?: "local"
        require(space in setOf("local","canvas"))
        require(preview==null || space=="local") { "Preview points are parent-local" }
        return buildJsonObject {
            put("revisionId",revision);put("id",id);put("kind",kind);put("name",g.name);g.parent?.let { put("parentId",it) }
            put("coordinateSpace",if(space=="local") "parent_local_x_right_y_down" else "canvas_x_right_y_down")
            put("coordinate",buildJsonObject { (model.parameters.associate { it.id.raw to it.default }+pose).forEach { (k,v)->put(k,v) } })
            put("geometryRepresentation", if(kind=="warp") "sampled_warp_lattice" else "triangle_mesh")
            putJsonObject("nativeBezier") {
                put("available",false)
                put("reason","Runtime stores sampled positions, not native Bezier anchors or handles. Editor subdivision metadata does not supply control geometry.")
            }
            controls?.let { editable -> putJsonObject("editorBezier") {
                put("available", true); put("persisted", editable.persisted)
                put("representation", "journal_controls_with_sampled_residual")
                put("rows", editable.state.bezierRows); put("columns", editable.state.bezierCols)
                put("coordinateSpace", "parent_local_x_right_y_down")
                put("anchorCount", editable.state.anchors.size); put("handleCount", editable.state.handles.size)
                if(detail == "points") {
                    val encoded = RigBezierJournal.encode(editable)
                    val entries = encoded.getValue("anchors").jsonArray.map { value -> buildJsonObject { put("kind", "anchor"); put("value", value) } } +
                        encoded.getValue("handles").jsonArray.map { value -> buildJsonObject { put("kind", "handle"); put("value", value) } }
                    val end = minOf(offset + limit, entries.size)
                    put("controls", JsonArray(entries.drop(offset).take(limit)))
                    if(end < entries.size) put("nextOffset", end)
                }
            } }
            put("recommendedEditTool","deform")
            put("previewOnly",preview!=null)
            put("diagnosticReference",if(preview==null) "parameter_defaults" else "input_pose_before_operations")
            put("diagnostics",io.github.psd2live.core.quality.RigGeometryDiagnostics.compare(reference,g.points,triangles))
            put("pointCount",g.points.size/2);put("keyformCount",g.keyCount)
            g.rows?.let { put("rows",it) };g.columns?.let { put("columns",it) }
            putJsonArray("axes") { g.axes.forEach { axis -> add(buildJsonObject { put("id",axis.parameterId.raw);put("keys",JsonArray(axis.keys.map(::JsonPrimitive))) }) } }
            put("cost",buildJsonObject { put("localPointScalars",g.points.size);put("allKeyformScalars",g.points.size*g.keyCount) })
            put("localBounds",JsonArray(RigGeometryTools.bounds(g.points).map(::JsonPrimitive)))
            if(detail=="points") {
                val points=if(space=="local")g.points else {
                    val typed=pose.mapKeys { ParameterId(it.key) }
                    val world=if(kind=="warp") io.github.psd2live.core.RigInformationOverlay.warpPoints(model,typed,setOf(id)).getValue(id)
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
