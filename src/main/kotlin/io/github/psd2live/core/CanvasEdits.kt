package io.github.psd2live.core

import kotlinx.serialization.json.*
import org.umamo.edit.MergeTarget
import org.umamo.edit.MeshRefinementOps
import org.umamo.edit.MeshElement
import org.umamo.edit.MeshTopology
import org.umamo.edit.MeshTopologyEdit
import org.umamo.edit.MeshTopologyOps
import org.umamo.edit.VertexSource
import org.umamo.edit.withDeformerPart
import org.umamo.edit.withMeshTopologyEdit
import org.umamo.render.eval.warpApply
import org.umamo.render.eval.warpInverseToleranceSquared
import org.umamo.render.eval.warpRemapPoint
import org.umamo.render.eval.warpRemapPoints
import org.umamo.runtime.model.*
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.PI

/**
 * A copy of this mesh with every vertex shifted by [shift], and each vertex's UV shifted by the same
 * amount in UV space.
 *
 * This is what keeps editing from changing the picture: a vertex carries the texel it samples along with
 * it, so the triangle keeps covering the same image wherever the vertex is dragged to. The UV side of the
 * shift comes from [RigGeometryTools.uvAffine], the drawable's own position-to-UV map, which every
 * generated mesh satisfies exactly - so the render is not merely close to the original, it is the same
 * mapping evaluated at moved points.
 *
 * A mesh that cannot pin an affine down (degenerate, or hand-built by something else) falls back to
 * leaving the UVs alone, which is the old behaviour rather than a wrong guess.
 */
internal fun DrawableMesh.movedBy(shift: FloatArray): DrawableMesh {
    require(shift.size == positions.size) { "A vertex shift must match the mesh" }
    val moved = FloatArray(positions.size) { positions[it] + shift[it] }
    val phi = RigGeometryTools.uvAffine(positions, uvs) ?: return DrawableMesh(moved, uvs, indices)
    val movedUvs = FloatArray(uvs.size)
    for (i in moved.indices step 2) {
        movedUvs[i] = phi[0] * moved[i] + phi[1] * moved[i + 1] + phi[4]
        movedUvs[i + 1] = phi[2] * moved[i] + phi[3] * moved[i + 1] + phi[5]
    }
    return DrawableMesh(moved, movedUvs, indices)
}

/** Replayable canvas operations. The preview and persisted history use this same reducer. */
internal object CanvasEdits {
    fun apply(model: PuppetModel, edit: JsonObject): PuppetModel {
        val id = edit.getValue("id").jsonPrimitive.content
        return when (edit.getValue("op").jsonPrimitive.content) {
            "canvas_create_rotation" -> {
                require(model.deformers.none { it.id.raw == id })
                val addTo = edit["add_to"]?.jsonPrimitive?.contentOrNull ?: "parent_of_selected"
                val origin = edit["origin"]?.jsonArray
                val originX = origin?.get(0)?.jsonPrimitive?.float ?: 0f
                val originY = origin?.get(1)?.jsonPrimitive?.float ?: 0f
                val baseAngle = edit["angle"]?.jsonPrimitive?.float ?: 0f
                val handleLength = edit["handle_length"]?.jsonPrimitive?.float
                require(handleLength == null || (handleLength.isFinite() && handleLength > 1e-6f))
                require(listOf(originX, originY, baseAngle).all(Float::isFinite))

                when (addTo) {
                    "parent_of_deformer" -> {
                        val childId = edit.getValue("deformer_id").jsonPrimitive.content.let(::DeformerId)
                        val child = model.deformers.singleOrNull { it.id == childId }
                            ?: error("Deformer not found: ${childId.raw}")
                        val partId = edit["part_id"]?.jsonPrimitive?.contentOrNull?.let(::PartId) ?: child.partId
                        require(partId == null || model.parts.any { it.id == partId }) { "Part not found: $partId" }
                        val rotation = Deformer.Rotation(
                            DeformerId(id),
                            edit.getValue("name").jsonPrimitive.content,
                            child.parent,
                            partId,
                            baseAngle,
                            KeyformGrid(emptyList(), listOf(KeyformCell(intArrayOf(), RotationPivotForm(originX, originY, 0f, 1f)))),
                            handleLength = handleLength,
                        )
                        if (edit["preservePose"]?.jsonPrimitive?.booleanOrNull == true) {
                            RotationCreationSpace(originX, originY, baseAngle)
                                .wrap(model, rotation, remountDeformers = setOf(childId), remountMeshes = emptySet())
                        } else {
                            model.copy(
                                deformers = listOf(rotation) + model.deformers.map { d ->
                                    if (d.id != childId) d else when (d) {
                                        is Deformer.Warp -> d.copy(parent = rotation.id)
                                        is Deformer.Rotation -> d.copy(parent = rotation.id)
                                    }
                                },
                            ).withDerivedRenderRoot()
                        }
                    }
                    else -> {
                        // parent_of_selected: insert above the selected meshes (shared parent required).
                        val selected = edit.getValue("meshes").jsonArray.map { it.jsonPrimitive.content }.toSet()
                        val drawables = model.drawables.filter { it.id.raw in selected }
                        require(drawables.isNotEmpty() && drawables.size == selected.size)
                        require(drawables.map { it.parentDeformerId }.distinct().size == 1) {
                            "Select meshes with the same parent deformer"
                        }
                        val meshParent = drawables.first().parentDeformerId
                        val partId = edit["part_id"]?.jsonPrimitive?.contentOrNull?.let(::PartId)
                            ?: meshParent?.let { p -> model.deformers.firstOrNull { it.id == p }?.partId }
                            ?: model.partByDrawable()[drawables.first().id]
                        require(partId == null || model.parts.any { it.id == partId }) { "Part not found: $partId" }
                        val rotation = Deformer.Rotation(
                            DeformerId(id),
                            edit.getValue("name").jsonPrimitive.content,
                            meshParent,
                            partId,
                            baseAngle,
                            KeyformGrid(emptyList(), listOf(KeyformCell(intArrayOf(), RotationPivotForm(originX, originY, 0f, 1f)))),
                            handleLength = handleLength,
                        )
                        if (edit["preservePose"]?.jsonPrimitive?.booleanOrNull == true) {
                            RotationCreationSpace(originX, originY, baseAngle)
                                .wrap(model, rotation, remountDeformers = emptySet(), remountMeshes = selected)
                        } else {
                            model.copy(
                                deformers = listOf(rotation) + model.deformers,
                                drawables = model.drawables.map {
                                    if (it.id.raw in selected) it.copy(parentDeformerId = rotation.id) else it
                                },
                            ).withDerivedRenderRoot()
                        }
                    }
                }
            }
            "canvas_create_warp" -> {
                require(model.deformers.none { it.id.raw==id })
                val ids=edit.getValue("meshes").jsonArray.map { it.jsonPrimitive.content }.toSet()
                val drawables=model.drawables.filter { it.id.raw in ids }
                require(drawables.size==ids.size && drawables.all { it.mesh!=null })
                val addTo = edit["add_to"]?.jsonPrimitive?.contentOrNull ?: "parent_of_selected"
                val rows = edit["rows"]?.jsonPrimitive?.int ?: 5
                val cols = edit["columns"]?.jsonPrimitive?.int ?: 5
                require(rows in 1..32 && cols in 1..32) { "Warp divisions must be between 1 and 32" }

                if (addTo == "child_of_deformer" && ids.isEmpty()) {
                    val parentId = edit.getValue("parent_id").jsonPrimitive.content.let(::DeformerId)
                    val parent = model.deformers.singleOrNull { it.id == parentId }
                        ?: error("Parent deformer not found: ${parentId.raw}")
                    val partId = edit["part_id"]?.jsonPrimitive?.contentOrNull?.let(::PartId) ?: parent.partId
                    require(partId == null || model.parts.any { it.id == partId }) { "Part not found: $partId" }
                    val customBounds = edit["bounds"]?.jsonObject
                    val (x, y, w, h) = if (customBounds != null) {
                        listOf(
                            customBounds.getValue("x").jsonPrimitive.float,
                            customBounds.getValue("y").jsonPrimitive.float,
                            customBounds.getValue("w").jsonPrimitive.float,
                            customBounds.getValue("h").jsonPrimitive.float,
                        )
                    } else when (parent) {
                        is Deformer.Warp -> {
                            val pts = parent.geometryGrid?.cells?.firstOrNull()?.form?.controlPoints
                                ?: floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
                            val xs = pts.filterIndexed { i, _ -> i % 2 == 0 }
                            val ys = pts.filterIndexed { i, _ -> i % 2 == 1 }
                            listOf(xs.min(), ys.min(), (xs.max() - xs.min()).coerceAtLeast(1e-3f), (ys.max() - ys.min()).coerceAtLeast(1e-3f))
                        }
                        is Deformer.Rotation -> listOf(-50f, -50f, 100f, 100f)
                    }
                    require(listOf(x, y, w, h).all(Float::isFinite) && w > 1e-6f && h > 1e-6f)
                    val points = (0..rows).flatMap { r -> (0..cols).flatMap { c -> listOf(x+c*w/cols, y+r*h/rows) } }.toFloatArray()
                    val warp = Deformer.Warp(DeformerId(id), edit.getValue("name").jsonPrimitive.content, parentId, partId, rows, cols, true,
                        KeyformGrid(emptyList(), listOf(KeyformCell(intArrayOf(), WarpLatticeForm(points)))))
                    return model.copy(deformers = model.deformers + warp).withDerivedRenderRoot()
                }

                if (addTo == "parent_of_deformer") {
                    val childId = edit.getValue("deformer_id").jsonPrimitive.content.let(::DeformerId)
                    val child = model.deformers.singleOrNull { it.id == childId }
                        ?: error("Deformer not found: ${childId.raw}")
                    val partId = edit["part_id"]?.jsonPrimitive?.contentOrNull?.let(::PartId) ?: child.partId
                    require(partId == null || model.parts.any { it.id == partId }) { "Part not found: $partId" }
                    val customBounds = edit["bounds"]?.jsonObject
                    val (x, y, w, h) = if (customBounds != null) {
                        listOf(
                            customBounds.getValue("x").jsonPrimitive.float,
                            customBounds.getValue("y").jsonPrimitive.float,
                            customBounds.getValue("w").jsonPrimitive.float,
                            customBounds.getValue("h").jsonPrimitive.float,
                        )
                    } else {
                        when (child) {
                            is Deformer.Warp -> {
                                val pts = child.geometryGrid?.cells?.firstOrNull()?.form?.controlPoints
                                    ?: floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
                                val xs = pts.filterIndexed { i, _ -> i % 2 == 0 }
                                val ys = pts.filterIndexed { i, _ -> i % 2 == 1 }
                                val minX = xs.min(); val maxX = xs.max(); val minY = ys.min(); val maxY = ys.max()
                                val bw = (maxX - minX).coerceAtLeast(1e-3f); val bh = (maxY - minY).coerceAtLeast(1e-3f)
                                listOf(minX - bw * 0.05f, minY - bh * 0.05f, bw * 1.1f, bh * 1.1f)
                            }
                            is Deformer.Rotation -> {
                                val ox = child.geometryGrid?.cells?.firstOrNull()?.form?.originX ?: 0f
                                val oy = child.geometryGrid?.cells?.firstOrNull()?.form?.originY ?: 0f
                                listOf(ox - 50f, oy - 50f, 100f, 100f)
                            }
                        }
                    }
                    require(listOf(x, y, w, h).all(Float::isFinite) && w > 1e-6f && h > 1e-6f)
                    val points = (0..rows).flatMap { r -> (0..cols).flatMap { c -> listOf(x+c*w/cols, y+r*h/rows) } }.toFloatArray()
                    val warp = Deformer.Warp(DeformerId(id), edit.getValue("name").jsonPrimitive.content, child.parent, partId, rows, cols, true,
                        KeyformGrid(emptyList(), listOf(KeyformCell(intArrayOf(), WarpLatticeForm(points)))))
                    val remapped = model.deformers.map { d ->
                        if (d.id != childId) d else when (d) {
                            is Deformer.Warp -> {
                                val pts = d.geometryGrid?.cells?.firstOrNull()?.form?.controlPoints ?: return@map d.copy(parent = warp.id)
                                val norm = FloatArray(pts.size) { j -> if (j % 2 == 0) (pts[j] - x) / w else (pts[j] - y) / h }
                                d.copy(
                                    parent = warp.id,
                                    geometryGrid = d.geometryGrid.let { grid ->
                                        KeyformGrid(grid.axes, grid.cells.map { cell ->
                                            KeyformCell(cell.coordinate, WarpLatticeForm(FloatArray(cell.form.controlPoints.size) { j ->
                                                if (j % 2 == 0) (cell.form.controlPoints[j] - x) / w else (cell.form.controlPoints[j] - y) / h
                                            }))
                                        })
                                    } ?: KeyformGrid(emptyList(), listOf(KeyformCell(intArrayOf(), WarpLatticeForm(norm)))),
                                )
                            }
                            is Deformer.Rotation -> {
                                val ox = d.geometryGrid?.cells?.firstOrNull()?.form?.originX ?: 0f
                                val oy = d.geometryGrid?.cells?.firstOrNull()?.form?.originY ?: 0f
                                d.copy(
                                    parent = warp.id,
                                    geometryGrid = KeyformGrid(emptyList(), listOf(KeyformCell(intArrayOf(),
                                        RotationPivotForm((ox - x) / w, (oy - y) / h, 0f, 1f)))),
                                )
                            }
                        }
                    }
                    return model.copy(deformers = remapped + warp).withDerivedRenderRoot()
                }

                require(ids.isNotEmpty()) { "Select at least one mesh" }
                require(drawables.map { it.parentDeformerId }.distinct().size==1) { "Select meshes with the same parent deformer" }

                val meshParent = drawables.first().parentDeformerId
                val parent = when (addTo) {
                    "specify_parent" -> edit["parent_id"]?.jsonPrimitive?.contentOrNull?.let(::DeformerId).also {
                        require(it == meshParent) { "Specified parent must match the selection's current parent" }
                    } ?: meshParent
                    else -> meshParent
                }
                val partId = edit["part_id"]?.jsonPrimitive?.contentOrNull?.let(::PartId)
                    ?: (parent?.let { p -> model.deformers.firstOrNull { it.id == p }?.partId })
                    ?: model.partByDrawable()[drawables.first().id]
                require(partId == null || model.parts.any { it.id == partId }) { "Part not found: $partId" }

                val customBounds = edit["bounds"]?.jsonObject
                // Honor an explicit placement box. Parent-Warp auto-alignment (RigWarpEdit) only runs when
                // the artist did not place a rectangle — otherwise the result diverges from the ghost.
                if (customBounds == null && parent != null && model.deformers.any { it.id == parent && it is Deformer.Warp }) {
                    val result = RigWarpEdit(id, edit.getValue("name").jsonPrimitive.content, parent.raw, ids.toList(), rows, cols).applyTo(model)
                    return if (partId != null && result.deformers.any { it.id.raw == id && it.partId != partId }) {
                        result.withDeformerPart(DeformerId(id), partId)
                    } else result
                }

                val strategy = edit["size_strategy"]?.jsonPrimitive?.contentOrNull ?: "selection_bounds"
                val (x, y, w, h) = if (customBounds != null) {
                    listOf(
                        customBounds.getValue("x").jsonPrimitive.float,
                        customBounds.getValue("y").jsonPrimitive.float,
                        customBounds.getValue("w").jsonPrimitive.float,
                        customBounds.getValue("h").jsonPrimitive.float,
                    )
                } else {
                    val basePositions = drawables.flatMap { d -> d.mesh!!.positions.toList() }.toFloatArray()
                    val baseBounds = RigGeometryTools.bounds(basePositions)
                    when (strategy) {
                        "keyform_envelope" -> {
                            val all = drawables.flatMap { d ->
                                val mesh = d.mesh!!
                                listOf(mesh.positions.toList()) + d.geometryGrid?.cells.orEmpty().map { cell ->
                                    mesh.positions.indices.map { mesh.positions[it] + cell.form.positionDeltas[it] }
                                }
                            }.flatten().toFloatArray()
                            val bounds = RigGeometryTools.bounds(all)
                            listOf(bounds[0] - bounds[2] * 0.05f, bounds[1] - bounds[3] * 0.05f, bounds[2] * 1.1f, bounds[3] * 1.1f)
                        }
                        "center_align" -> listOf(baseBounds[0], baseBounds[1], baseBounds[2], baseBounds[3])
                        else -> listOf(baseBounds[0] - baseBounds[2] * 0.05f, baseBounds[1] - baseBounds[3] * 0.05f, baseBounds[2] * 1.1f, baseBounds[3] * 1.1f)
                    }
                }
                require(listOf(x, y, w, h).all(Float::isFinite) && w > 1e-6f && h > 1e-6f) { "Warp bounds must have positive width and height" }
                val points = (0..rows).flatMap { r -> (0..cols).flatMap { c -> listOf(x+c*w/cols, y+r*h/rows) } }.toFloatArray()
                val warp = Deformer.Warp(DeformerId(id), edit.getValue("name").jsonPrimitive.content, parent, partId, rows, cols, true, KeyformGrid(emptyList(), listOf(KeyformCell(intArrayOf(), WarpLatticeForm(points)))))
                model.copy(deformers=model.deformers+warp,drawables=model.drawables.map { d -> if(d.id.raw !in ids)d else {
                    val mesh=d.mesh!!
                    d.copy(parentDeformerId=warp.id,mesh=DrawableMesh(FloatArray(mesh.positions.size) { j -> if(j%2==0)(mesh.positions[j]-x)/w else (mesh.positions[j]-y)/h },mesh.uvs,mesh.indices),
                        geometryGrid=d.geometryGrid?.let { grid -> KeyformGrid(grid.axes,grid.cells.map { cell -> KeyformCell(cell.coordinate,MeshDeltaForm(FloatArray(cell.form.positionDeltas.size) { j -> cell.form.positionDeltas[j]/if(j%2==0)w else h })) }) },
                        blendShapes=d.blendShapes.map { binding -> binding.copy(forms=binding.forms.map { form -> form?.let { MeshForm(FloatArray(it.positionDeltas.size) { j -> it.positionDeltas[j]/if(j%2==0)w else h },it.drawOrder,it.opacity,it.multiplyColor,it.screenColor) } }) })
                } }).withDerivedRenderRoot()
            }
            "canvas_geometry" -> {
                val kind = edit.getValue("kind").jsonPrimitive.content
                val points = edit.getValue("points").jsonArray.map { it.jsonPrimitive.float }.toFloatArray()
                require(points.isNotEmpty() && points.size % 2 == 0 && points.all(Float::isFinite))
                val key = edit.getValue("key").jsonObject.mapValues { it.value.jsonPrimitive.float }
                val pose = edit["pose"]?.jsonObject?.mapValues { it.value.jsonPrimitive.float } ?: key
                val geometry = RigGeometryTools.geometry(model, kind, id, if (pose.isEmpty()) key else pose)
                val blendEdit = model.parameters.any { parameter ->
                    parameter.kind == org.umamo.runtime.model.ParameterKind.BLEND_SHAPE &&
                        abs((key[parameter.id.raw] ?: 0f)) >= org.umamo.runtime.eval.EPS_KEY
                }
                require(points.size == geometry.points.size)
                if (key.isNotEmpty()) require(geometry.axes.all { it.parameterId.raw in key }) { "Include every bound parameter axis" }
                if(kind == "rotation") {
                    val rotation=model.deformers.single { it.id.raw==id } as Deformer.Rotation
                    require(points.size==4)
                    val length=RigGeometryTools.rotationHandleLength(model, rotation)
                    val dx=points[2]-points[0]; val dy=points[3]-points[1]
                    val prevLen=kotlin.math.hypot(
                        geometry.points[2]-geometry.points[0],
                        geometry.points[3]-geometry.points[1],
                    )
                    val prevScale=(prevLen/length).coerceAtLeast(1e-5f)
                    // Tip rotate must not rewrite scale (that resizes every child). Alt-scale omits keep_scale.
                    val scale=if(edit["keep_scale"]?.jsonPrimitive?.booleanOrNull==true) prevScale
                    else (kotlin.math.hypot(dx,dy)/length).coerceAtLeast(1e-5f)
                    require(scale>1e-5f) { "Rotation scale must be positive" }
                    val wrapped=kotlin.math.atan2(dy,dx)*180f/kotlin.math.PI.toFloat()-rotation.baseAngle
                    val reference = geometry.rotationAngle ?: 0f
                    val angle = wrapped + 360f * kotlin.math.round((reference - wrapped) / 360f)
                    val blend = if (blendEdit) null else RigGeometryTools.rotationBlendOffset(model, id, pose)
                    val form=RotationPivotForm(
                        points[0] - (blend?.originX ?: 0f),
                        points[1] - (blend?.originY ?: 0f),
                        angle - (blend?.angle ?: 0f),
                        scale - (blend?.scale ?: 0f),
                    )
                    if(key.isEmpty())model.copy(deformers=model.deformers.map { if(it.id==rotation.id)rotation.copy(geometryGrid=KeyformGrid(emptyList(),listOf(KeyformCell(intArrayOf(),form)))) else it })
                    else applyKeyformSet(model,RigKeyformSetEdit(RigTargetRef(RigTargetKind.ROTATION_DEFORMER,id),key,RigKeyformGeometryEdit(originX=form.originX,originY=form.originY,angle=form.angle,scale=form.scale)))
                } else if (kind == "warp") {
                    val oldWarp = model.deformers.firstOrNull { it.id.raw == id } as? Deformer.Warp
                    val oldPoints = if (key.isNotEmpty()) RigGeometryTools.geometry(model, "warp", id, if (pose.isEmpty()) key else pose).points
                        else (oldWarp?.geometryGrid?.cells?.singleOrNull()?.form?.controlPoints ?: RigGeometryTools.geometry(model, "warp", id, emptyMap()).points)
                    val warpPoints = if (blendEdit) points else {
                        val offset = RigGeometryTools.blendOffset(model, "warp", id, pose)
                        FloatArray(points.size) { index -> points[index] - offset.getOrElse(index) { 0f } }
                    }
                    val updated = if (key.isNotEmpty()) {
                        applyKeyformSet(model, RigKeyformSetEdit(RigTargetRef(RigTargetKind.WARP_DEFORMER, id), key, RigKeyformGeometryEdit(controlPoints = warpPoints.toList())))
                    } else {
                        val grid = oldWarp?.geometryGrid
                        val newGrid = if (grid != null && grid.axes.isNotEmpty()) {
                            val shift = FloatArray(points.size) { points[it] - oldPoints[it] }
                            KeyformGrid(grid.axes, grid.cells.map { cell ->
                                val cp = cell.form.controlPoints
                                KeyformCell(cell.coordinate, WarpLatticeForm(FloatArray(cp.size) { j -> cp[j] + shift[j] }))
                            })
                        } else {
                            KeyformGrid(emptyList(), listOf(KeyformCell(intArrayOf(), WarpLatticeForm(points))))
                        }
                        model.copy(deformers = model.deformers.map {
                            if (it.id.raw == id && it is Deformer.Warp) it.copy(geometryGrid = newGrid) else it
                        })
                    }
                    val preserveChildren = edit["preserve_children"]?.jsonPrimitive?.booleanOrNull == true ||
                        edit["preserve_image"]?.jsonPrimitive?.booleanOrNull == true
                    if (oldWarp != null && preserveChildren) {
                        preserveWarpChildren(updated, oldWarp, oldPoints, points, key)
                    } else {
                        updated
                    }
                } else if (key.isNotEmpty()) {
                    val offset = if (blendEdit) FloatArray(0) else RigGeometryTools.blendOffset(model, "mesh", id, pose)
                    applyKeyformSet(model, RigKeyformSetEdit(RigTargetRef(RigTargetKind.fromString(kind), id), key,
                        RigKeyformGeometryEdit(positionDeltas = points.indices.map { points[it] - geometry.base[it] - offset.getOrElse(it) { 0f } })))
                } else {
                    // The mesh moves by how far its DISPLAYED geometry moved, not to the displayed points.
                    // Assigning the displayed points to the rest mesh would apply a keyed default delta a
                    // second time and make the geometry jump out from under the pointer; taking the shift
                    // keeps the edit under the cursor whatever the drawable's default keyform holds.
                    val pose = edit["pose"]?.jsonObject?.mapValues { it.value.jsonPrimitive.float }.orEmpty()
                    val displayed = RigGeometryTools.geometry(model, "mesh", id, pose).points
                    val shift = FloatArray(points.size) { points[it] - displayed[it] }
                    model.copy(drawables = model.drawables.map {
                        if (it.id.raw != id) it else it.mesh?.let { mesh ->
                            // Missing intent preserves replay of journals written before modes were separated.
                            val preserveImage = edit["preserve_image"]?.jsonPrimitive?.booleanOrNull ?: true
                            val moved = if (preserveImage) mesh.movedBy(shift) else DrawableMesh(
                                FloatArray(mesh.positions.size) { j -> mesh.positions[j] + shift[j] },
                                mesh.uvs, mesh.indices,
                            )
                            it.copy(mesh = moved, geometryGrid = it.geometryGrid)
                        } ?: it
                    })
                }
            }
            "canvas_topology" -> {
                val drawable = model.drawables.single { it.id.raw == id }
                val mesh = requireNotNull(drawable.mesh)
                val selected = edit.getValue("vertices").jsonArray.map { it.jsonPrimitive.int }.toSet()
                // Not `isNotEmpty`: the knife carries its anchors instead of a selection, and an
                // action that needs a selection answers null from CanvasTopology.build anyway.
                require(selected.all { it in 0 until mesh.vertexCount })
                val action = edit.getValue("action").jsonPrimitive.content
                // The op itself lives in CanvasTopology so the editor can ask it the same question - see
                // there for why the editor needs to know what an op makes before it is committed.
                val anchors = CanvasTopology.parseAnchors(edit["anchors"]?.jsonArray)
                val edges = edit["edges"]?.jsonArray?.map { pair ->
                    val values = pair.jsonArray
                    MeshElement.Edge.of(values[0].jsonPrimitive.int, values[1].jsonPrimitive.int)
                }?.toSet().orEmpty()
                val result = requireNotNull(CanvasTopology.build(mesh, action, selected, anchors, edges)) {
                    "The $action operation does not apply to this selection"
                }.edit
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
                require(meshA != meshB) { "Select two different meshes" }
                require(model.glues.none { it.id == id }) { "Glue ID already exists" }
                val parameters = edit["pose"]?.jsonObject?.map { ParameterId(it.key) to it.value.jsonPrimitive.float }?.toMap().orEmpty()
                val positions = org.umamo.render.eval.CpuDeformationEvaluator().evaluate(model, parameters).worldPositions
                val a = requireNotNull(positions[meshA]) { "First mesh is not visible at this pose" }
                val b = requireNotNull(positions[meshB]) { "Second mesh is not visible at this pose" }
                val distance = edit["distance"]?.jsonPrimitive?.float ?: 40f
                require(distance.isFinite() && distance > 0f) { "Glue distance must be positive" }
                val usedB = mutableSetOf<Int>()
                val pairs = (0 until a.size / 2).mapNotNull { i ->
                    var nearest = -1
                    var best = distance
                    for (j in 0 until b.size / 2) {
                        if (j in usedB) continue
                        val d = kotlin.math.hypot(a[i * 2] - b[j * 2], a[i * 2 + 1] - b[j * 2 + 1])
                        if (d <= best) { nearest = j; best = d }
                    }
                    if (nearest < 0) null else {
                        usedB += nearest
                        GluePair(i, nearest, 0.5f, 0.5f)
                    }
                }
                require(pairs.isNotEmpty()) { "No nearby vertices to glue; increase the matching distance or move the meshes closer" }
                val glue = Glue(meshA, meshB, pairs, intensity = 1f, id = id)
                model.copy(glues = model.glues + glue)
            }
            else -> error("Unknown canvas operation")
        }
    }

    private fun preserveWarpChildren(
        model: PuppetModel,
        oldWarp: Deformer.Warp,
        cpOld: FloatArray,
        cpNew: FloatArray,
        key: Map<String, Float>,
    ): PuppetModel {
        val cols = oldWarp.columns
        val rows = oldWarp.rows
        val bilinear = oldWarp.isQuadTransform
        val tolSq = warpInverseToleranceSquared(cpNew)
        val scratch = FloatArray(2)

        val updatedDrawables = model.drawables.map { d ->
            if (d.parentDeformerId != oldWarp.id || d.mesh == null) d else {
                val mesh = d.mesh
                if (key.isEmpty()) {
                    val newPositions = warpRemapPoints(cpOld, cpNew, cols, rows, bilinear, mesh.positions, scratch, tolSq)
                    val newGrid = d.geometryGrid?.let { grid ->
                        KeyformGrid(grid.axes, grid.cells.map { cell ->
                            val deltas = cell.form.positionDeltas
                            val newDeltas = FloatArray(deltas.size)
                            for (i in deltas.indices step 2) {
                                val uKeyed = mesh.positions[i] + deltas[i]
                                val vKeyed = mesh.positions[i + 1] + deltas[i + 1]
                                val (nu, nv) = warpRemapPoint(cpOld, cpNew, cols, rows, bilinear, uKeyed, vKeyed, scratch, tolSq)
                                newDeltas[i] = nu - newPositions[i]
                                newDeltas[i + 1] = nv - newPositions[i + 1]
                            }
                            KeyformCell(cell.coordinate, MeshDeltaForm(newDeltas))
                        })
                    }
                    val newBlendShapes = d.blendShapes.map { binding ->
                        binding.copy(forms = binding.forms.map { form ->
                            form?.let { f ->
                                val deltas = f.positionDeltas
                                val newDeltas = FloatArray(deltas.size)
                                for (i in deltas.indices step 2) {
                                    val uBlend = mesh.positions[i] + deltas[i]
                                    val vBlend = mesh.positions[i + 1] + deltas[i + 1]
                                    val (nu, nv) = warpRemapPoint(cpOld, cpNew, cols, rows, bilinear, uBlend, vBlend, scratch, tolSq)
                                    newDeltas[i] = nu - newPositions[i]
                                    newDeltas[i + 1] = nv - newPositions[i + 1]
                                }
                                MeshForm(newDeltas, f.drawOrder, f.opacity, f.multiplyColor, f.screenColor)
                            }
                        })
                    }
                    d.copy(mesh = DrawableMesh(newPositions, mesh.uvs, mesh.indices), geometryGrid = newGrid, blendShapes = newBlendShapes)
                } else {
                    val grid = d.geometryGrid
                    if (grid != null && grid.axes.isNotEmpty()) {
                        val axisMap = grid.axes.mapIndexed { idx, axis -> axis.parameterId.raw to idx }.toMap()
                        val targetCoord = if (axisMap.keys.all { it in key }) {
                            IntArray(grid.axes.size) { axisIndex ->
                                val axis = grid.axes[axisIndex]
                                val paramVal = key[axis.parameterId.raw] ?: 0f
                                axis.keys.indexOfFirst { abs(it - paramVal) < 1e-4f }
                            }
                        } else null

                        if (targetCoord != null && targetCoord.all { it >= 0 }) {
                            val newCells = grid.cells.map { cell ->
                                if (!cell.coordinate.contentEquals(targetCoord)) cell else {
                                    val deltas = cell.form.positionDeltas
                                    val newDeltas = FloatArray(deltas.size)
                                    for (i in deltas.indices step 2) {
                                        val uKeyed = mesh.positions[i] + deltas[i]
                                        val vKeyed = mesh.positions[i + 1] + deltas[i + 1]
                                        val (nu, nv) = warpRemapPoint(cpOld, cpNew, cols, rows, bilinear, uKeyed, vKeyed, scratch, tolSq)
                                        newDeltas[i] = nu - mesh.positions[i]
                                        newDeltas[i + 1] = nv - mesh.positions[i + 1]
                                    }
                                    KeyformCell(cell.coordinate, MeshDeltaForm(newDeltas))
                                }
                            }
                            d.copy(geometryGrid = KeyformGrid(grid.axes, newCells))
                        } else d
                    } else d
                }
            }
        }

        val updatedDeformers = model.deformers.map { def ->
            if (def.parent != oldWarp.id) def else when (def) {
                is Deformer.Rotation -> {
                    val newGrid = def.geometryGrid?.let { grid ->
                        KeyformGrid(grid.axes, grid.cells.map { cell ->
                            val shouldRemap = if (key.isEmpty()) true else {
                                if (grid.axes.isEmpty()) false else {
                                    val axisMap = grid.axes.mapIndexed { idx, axis -> axis.parameterId.raw to idx }.toMap()
                                    axisMap.keys.all { it in key && abs(grid.axes[axisMap[it]!!].keys[cell.coordinate[axisMap[it]!!]] - (key[it] ?: 0f)) < 1e-4f }
                                }
                            }
                            if (!shouldRemap) cell else {
                                val (nu, nv) = warpRemapPoint(cpOld, cpNew, cols, rows, bilinear, cell.form.originX, cell.form.originY, scratch, tolSq)
                                val disp = -0.1f
                                warpApply(cpOld, cols, rows, bilinear, cell.form.originX, cell.form.originY, scratch, 0)
                                val oxOld = scratch[0]; val oyOld = scratch[1]
                                warpApply(cpOld, cols, rows, bilinear, cell.form.originX, cell.form.originY + disp, scratch, 0)
                                val aOld = atan2(scratch[1] - oyOld, scratch[0] - oxOld)

                                warpApply(cpNew, cols, rows, bilinear, nu, nv, scratch, 0)
                                val oxNew = scratch[0]; val oyNew = scratch[1]
                                warpApply(cpNew, cols, rows, bilinear, nu, nv + disp, scratch, 0)
                                val aNew = atan2(scratch[1] - oyNew, scratch[0] - oxNew)

                                var deltaAngle = (aNew - aOld) * 180f / PI.toFloat()
                                while (deltaAngle > 180f) deltaAngle -= 360f
                                while (deltaAngle < -180f) deltaAngle += 360f

                                KeyformCell(cell.coordinate, RotationPivotForm(nu, nv, cell.form.angle - deltaAngle, cell.form.scale))
                            }
                        })
                    }
                    def.copy(geometryGrid = newGrid)
                }
                is Deformer.Warp -> {
                    val newGrid = def.geometryGrid?.let { grid ->
                        KeyformGrid(grid.axes, grid.cells.map { cell ->
                            val shouldRemap = if (key.isEmpty()) true else {
                                if (grid.axes.isEmpty()) false else {
                                    val axisMap = grid.axes.mapIndexed { idx, axis -> axis.parameterId.raw to idx }.toMap()
                                    axisMap.keys.all { it in key && abs(grid.axes[axisMap[it]!!].keys[cell.coordinate[axisMap[it]!!]] - (key[it] ?: 0f)) < 1e-4f }
                                }
                            }
                            if (!shouldRemap) cell else {
                                val newCp = warpRemapPoints(cpOld, cpNew, cols, rows, bilinear, cell.form.controlPoints, scratch, tolSq)
                                KeyformCell(cell.coordinate, WarpLatticeForm(newCp))
                            }
                        })
                    }
                    def.copy(geometryGrid = newGrid)
                }
            }
        }

        return model.copy(drawables = updatedDrawables, deformers = updatedDeformers)
    }
}
