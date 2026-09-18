package io.github.psd2live.core

import kotlinx.serialization.json.*
import org.umamo.edit.*
import org.umamo.runtime.model.*

/** Replayable canvas operations. The preview and persisted history use this same reducer. */
internal object CanvasEdits {
    fun apply(model: PuppetModel, edit: JsonObject): PuppetModel {
        val id = edit.getValue("id").jsonPrimitive.content
        return when (edit.getValue("op").jsonPrimitive.content) {
            "canvas_create_rotation" -> {
                require(model.deformers.none { it.id.raw==id })
                val selected=edit.getValue("meshes").jsonArray.map { it.jsonPrimitive.content }.toSet()
                val drawables=model.drawables.filter { it.id.raw in selected }
                require(drawables.isNotEmpty() && drawables.size==selected.size)
                val byId=model.deformers.associateBy { it.id }
                val roots=drawables.mapNotNull { d ->
                    var parent=d.parentDeformerId
                    val visited=mutableSetOf<DeformerId>()
                    while(parent!=null && byId[parent]?.parent!=null) { require(visited.add(parent)) { "Deformer cycle" };parent=byId[parent]?.parent }
                    parent
                }.toSet()
                // Wrap whole root branches: inserting an affine inside a warped UV frame would
                // discard the parent's non-linear shape. A root identity preserves every pose.
                val origin = edit["origin"]?.jsonArray
                val originX = origin?.get(0)?.jsonPrimitive?.float ?: 0f
                val originY = origin?.get(1)?.jsonPrimitive?.float ?: 0f
                val baseAngle = edit["angle"]?.jsonPrimitive?.float ?: 0f
                val rotation=Deformer.Rotation(DeformerId(id),edit.getValue("name").jsonPrimitive.content,null,null,baseAngle,
                    KeyformGrid(emptyList(),listOf(KeyformCell(intArrayOf(),RotationPivotForm(originX,originY,0f,1f)))))
                model.copy(deformers=listOf(rotation)+model.deformers.map { d -> if(d.id !in roots)d else when(d) {
                    is Deformer.Warp -> d.copy(parent=rotation.id)
                    is Deformer.Rotation -> d.copy(parent=rotation.id)
                } },drawables=model.drawables.map { if(it.id.raw in selected && it.parentDeformerId==null)it.copy(parentDeformerId=rotation.id) else it }).withDerivedRenderRoot()
            }
            "canvas_create_warp" -> {
                val ids=edit.getValue("meshes").jsonArray.map { it.jsonPrimitive.content }.toSet()
                val drawables=model.drawables.filter { it.id.raw in ids }
                require(ids.isNotEmpty() && drawables.size==ids.size && drawables.all { it.mesh!=null })
                require(drawables.map { it.parentDeformerId }.distinct().size==1) { "Select meshes with the same parent deformer" }
                require(model.deformers.none { it.id.raw==id })
                val parent=drawables.first().parentDeformerId
                if(model.deformers.any { it.id==parent && it is Deformer.Warp }) {
                    RigWarpEdit(id,edit.getValue("name").jsonPrimitive.content,parent!!.raw,ids.toList(),4,4).applyTo(model)
                } else {
                    val all=drawables.flatMap { d ->
                        val mesh=d.mesh!!
                        listOf(mesh.positions.toList()) + d.geometryGrid?.cells.orEmpty().map { cell -> mesh.positions.indices.map { mesh.positions[it]+cell.form.positionDeltas[it] } }
                    }.flatten().toFloatArray()
                    val bounds = RigGeometryTools.bounds(all)
                    val rows = edit["rows"]?.jsonPrimitive?.int ?: 4
                    val cols = edit["columns"]?.jsonPrimitive?.int ?: 4
                    val customBounds = edit["bounds"]?.jsonObject
                    val x = customBounds?.get("x")?.jsonPrimitive?.float ?: (bounds[0]-bounds[2]*0.05f)
                    val y = customBounds?.get("y")?.jsonPrimitive?.float ?: (bounds[1]-bounds[3]*0.05f)
                    val w = customBounds?.get("w")?.jsonPrimitive?.float ?: (bounds[2]*1.1f)
                    val h = customBounds?.get("h")?.jsonPrimitive?.float ?: (bounds[3]*1.1f)
                    val points = (0..rows).flatMap { r -> (0..cols).flatMap { c -> listOf(x+c*w/cols, y+r*h/rows) } }.toFloatArray()
                    val warp = Deformer.Warp(DeformerId(id), edit.getValue("name").jsonPrimitive.content, parent, null, rows, cols, true, KeyformGrid(emptyList(), listOf(KeyformCell(intArrayOf(), WarpLatticeForm(points)))))
                    model.copy(deformers=model.deformers+warp,drawables=model.drawables.map { d -> if(d.id.raw !in ids)d else {
                        val mesh=d.mesh!!
                        d.copy(parentDeformerId=warp.id,mesh=DrawableMesh(FloatArray(mesh.positions.size) { j -> if(j%2==0)(mesh.positions[j]-x)/w else (mesh.positions[j]-y)/h },mesh.uvs,mesh.indices),
                            geometryGrid=d.geometryGrid?.let { grid -> KeyformGrid(grid.axes,grid.cells.map { cell -> KeyformCell(cell.coordinate,MeshDeltaForm(FloatArray(cell.form.positionDeltas.size) { j -> cell.form.positionDeltas[j]/if(j%2==0)w else h })) }) },
                            blendShapes=d.blendShapes.map { binding -> binding.copy(forms=binding.forms.map { form -> form?.let { MeshForm(FloatArray(it.positionDeltas.size) { j -> it.positionDeltas[j]/if(j%2==0)w else h },it.drawOrder,it.opacity,it.multiplyColor,it.screenColor) } }) })
                    } }).withDerivedRenderRoot()
                }
            }
            "canvas_geometry" -> {
                val kind = edit.getValue("kind").jsonPrimitive.content
                val points = edit.getValue("points").jsonArray.map { it.jsonPrimitive.float }.toFloatArray()
                require(points.isNotEmpty() && points.size % 2 == 0 && points.all(Float::isFinite))
                val key = edit.getValue("key").jsonObject.mapValues { it.value.jsonPrimitive.float }
                val geometry = RigGeometryTools.geometry(model, kind, id, key)
                require(points.size == geometry.points.size)
                require(geometry.axes.all { it.parameterId.raw in key }) { "Include every bound parameter axis" }
                if(kind == "rotation") {
                    val rotation=model.deformers.single { it.id.raw==id } as Deformer.Rotation
                    require(points.size==4)
                    val length=if(model.deformers.any { it.id==rotation.parent && it is Deformer.Warp })0.2f else 100f
                    val dx=points[2]-points[0]; val dy=points[3]-points[1]
                    val scale=kotlin.math.hypot(dx,dy)/length
                    require(scale>1e-5f) { "Rotation scale must be positive" }
                    val angle=kotlin.math.atan2(dy,dx)*180f/kotlin.math.PI.toFloat()-rotation.baseAngle
                    val form=RotationPivotForm(points[0],points[1],angle,scale)
                    if(key.isEmpty())model.copy(deformers=model.deformers.map { if(it.id==rotation.id)rotation.copy(geometryGrid=KeyformGrid(emptyList(),listOf(KeyformCell(intArrayOf(),form)))) else it })
                    else applyKeyformSet(model,RigKeyformSetEdit(RigTargetRef(RigTargetKind.ROTATION_DEFORMER,id),key,RigKeyformGeometryEdit(originX=form.originX,originY=form.originY,angle=form.angle,scale=form.scale)))
                } else if (key.isNotEmpty()) {
                    applyKeyformSet(model, RigKeyformSetEdit(RigTargetRef(RigTargetKind.fromString(kind), id), key,
                        if (kind == "warp") RigKeyformGeometryEdit(controlPoints = points.toList())
                        else RigKeyformGeometryEdit(positionDeltas = points.indices.map { points[it] - geometry.base[it] })))
                } else if (kind == "warp") {
                    model.copy(deformers = model.deformers.map {
                        if (it.id.raw == id && it is Deformer.Warp) it.copy(geometryGrid = KeyformGrid(emptyList(), listOf(KeyformCell(intArrayOf(), WarpLatticeForm(points))))) else it
                    })
                } else model.copy(drawables = model.drawables.map {
                    if (it.id.raw == id) it.copy(mesh = it.mesh!!.let { m -> DrawableMesh(points, m.uvs, m.indices) }, geometryGrid = it.geometryGrid) else it
                })
            }
            "canvas_topology" -> {
                val drawable = model.drawables.single { it.id.raw == id }
                val mesh = requireNotNull(drawable.mesh)
                val selected = edit.getValue("vertices").jsonArray.map { it.jsonPrimitive.int }.toSet()
                require(selected.isNotEmpty() && selected.all { it in 0 until mesh.vertexCount })
                val action = edit.getValue("action").jsonPrimitive.content
                val result = when (action) {
                    "merge" -> requireNotNull(MeshTopologyOps.mergeVertices(mesh, selected.sorted(), MergeTarget.AtCenter)) { "Select at least two vertices" }.edit
                    "connect" -> { require(selected.size == 2); requireNotNull(MeshTopologyOps.connectVertices(mesh, selected.first(), selected.last())) { "These vertices cannot be connected" }.edit }
                    "delete" -> {
                        val keep = (0 until mesh.vertexCount).filter { it !in selected }
                        require(keep.size >= 3) { "A mesh needs at least three vertices" }
                        val remap = keep.withIndex().associate { it.value to it.index }
                        val triangles = mesh.indices.toList().chunked(3).filter { t -> t.none { it in selected } }.flatten().map { remap.getValue(it) }.toIntArray()
                        require(triangles.isNotEmpty()) { "Deletion would remove every triangle" }
                        MeshTopologyEdit(DrawableMesh(keep.flatMap { listOf(mesh.positions[it*2], mesh.positions[it*2+1]) }.toFloatArray(),
                            keep.flatMap { listOf(mesh.uvs[it*2], mesh.uvs[it*2+1]) }.toFloatArray(), triangles), keep.map(VertexSource::FromOld))
                    }
                    "split" -> {
                        val edges = MeshTopology.uniqueEdges(mesh.indices).filter { it.endpointLow in selected && it.endpointHigh in selected }
                        require(edges.isNotEmpty()) { "Select both ends of an edge" }
                        // Split a single edge, including both adjacent triangles; all keyforms interpolate with it.
                        val edge = edges.first(); val a = edge.endpointLow; val b = edge.endpointHigh; val n = mesh.vertexCount
                        val triangles = mesh.indices.toList().chunked(3).flatMap { t ->
                            if (a !in t || b !in t) t else {
                                val start = (0..2).first { (t[it] == a && t[(it+1)%3] == b) || (t[it] == b && t[(it+1)%3] == a) }
                                val x=t[start]; val y=t[(start+1)%3]; val z=t[(start+2)%3]
                                listOf(x,n,z,n,y,z)
                            }
                        }.toIntArray()
                        MeshTopologyEdit(DrawableMesh(mesh.positions + floatArrayOf((mesh.positions[a*2]+mesh.positions[b*2])/2, (mesh.positions[a*2+1]+mesh.positions[b*2+1])/2),
                            mesh.uvs + floatArrayOf((mesh.uvs[a*2]+mesh.uvs[b*2])/2, (mesh.uvs[a*2+1]+mesh.uvs[b*2+1])/2), triangles),
                            (0 until n).map { VertexSource.FromOld(it) } + VertexSource.LerpOf(a,b,0.5f))
                    }
                    else -> error("Unknown topology operation")
                }
                var next = model.withMeshTopologyEdit(drawable.id, result)
                // Path anchors carry triangle indices. Rebind them before the old topology disappears.
                val paths = model.deformPaths.filter { it.drawableId == drawable.id }.associate { path ->
                    val positions = DeformPathTools.positions(path, mesh.positions)
                    path.id to path.copy(points = positions.mapIndexed { i, p -> DeformPathTools.bind(result.newMesh.positions, result.newMesh.indices, p.first, p.second, path.points[i].corner) })
                }
                next = next.copy(deformPaths = next.deformPaths.map { paths[it.id] ?: it })
                next
            }
            "canvas_create_glue" -> {
                val meshA = DrawableId(edit.getValue("mesh_a").jsonPrimitive.content)
                val meshB = DrawableId(edit.getValue("mesh_b").jsonPrimitive.content)
                val dA = model.drawables.firstOrNull { it.id == meshA } ?: return model
                val dB = model.drawables.firstOrNull { it.id == meshB } ?: return model
                val mA = dA.mesh ?: return model
                val mB = dB.mesh ?: return model
                val pairs = mutableListOf<GluePair>()
                for (i in 0 until mA.vertexCount) {
                    val ax = mA.positions[i * 2]
                    val ay = mA.positions[i * 2 + 1]
                    var bestDist = Float.MAX_VALUE
                    var bestJ = -1
                    for (j in 0 until mB.vertexCount) {
                        val bx = mB.positions[j * 2]
                        val by = mB.positions[j * 2 + 1]
                        val d = kotlin.math.hypot(ax - bx, ay - by)
                        if (d < bestDist) {
                            bestDist = d
                            bestJ = j
                        }
                    }
                    if (bestJ >= 0 && bestDist < 40f) {
                        pairs.add(GluePair(i, bestJ, 0.5f, 0.5f))
                    }
                }
                val glue = Glue(meshA, meshB, pairs, intensity = 1f, id = "Glue_${java.util.UUID.randomUUID()}")
                model.copy(glues = model.glues + glue)
            }
            else -> error("Unknown canvas operation")
        }
    }
}
