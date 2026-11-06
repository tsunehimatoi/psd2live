package io.github.psd2live.core

import org.umamo.runtime.model.*
import kotlinx.serialization.json.*

/** Pure mesh wrapping shared by editor commands and the standalone algorithm. */
object SwingWrapping {
    data class Wrapped(val model: PuppetModel, val commands: List<JsonObject>, val targets: List<String>)

    /** [model] with [edit]'s mesh targets wrapped, the wrap commands, and the targets as Warps. */
    fun wrap(model: PuppetModel, edit: RigSwingEdit): Wrapped {
        val deformerTargets = mutableListOf<String>()
        val meshes = mutableListOf<Drawable>()
        for (target in edit.targets) {
            when (val deformer = model.deformers.firstOrNull { it.id.raw == target }) {
                is Deformer.Warp -> deformerTargets += target
                is Deformer.Rotation -> error("Swing needs a Warp or a mesh, not rotation $target")
                null -> meshes += requireNotNull(model.drawables.firstOrNull { it.id.raw == target }) { "Swing target not found: $target" }
            }
        }
        var current = model
        val commands = mutableListOf<JsonObject>()
        val created = mutableListOf<String>()
        for ((_, group) in meshes.groupBy { it.parentDeformerId }) {
            val points = group.flatMap { d ->
                val mesh = requireNotNull(d.mesh) { "Mesh ${d.id.raw} has no geometry" }
                listOf(mesh.positions) + d.geometryGrid?.cells.orEmpty().map { cell ->
                    FloatArray(mesh.positions.size) { mesh.positions[it] + cell.form.positionDeltas[it] }
                }
            }
            val bounds = bounds(points)
            val tall = bounds[3] >= bounds[2]
            var id = "Warp_Swing_${edit.id}"
            var n = 2
            while (current.deformers.any { it.id.raw == id }) id = "Warp_Swing_${edit.id}_${n++}"
            val command = buildJsonObject {
                put("op", "canvas_create_warp"); put("id", id); put("name", edit.name)
                put("rows", if (tall) 6 else 3); put("columns", if (tall) 3 else 6)
                put("add_to", "parent_of_selected")
                putJsonArray("meshes") { group.forEach { add(it.id.raw) } }
                // Explicit bounds keep the lattice tight on the art, so its top edge is the art's top.
                putJsonObject("bounds") {
                    put("x", bounds[0] - bounds[2] * 0.05f); put("y", bounds[1] - bounds[3] * 0.05f)
                    put("w", bounds[2] * 1.1f); put("h", bounds[3] * 1.1f)
                }
            }
            current = createWarp(current, command)
            commands += command
            created += id
        }
        return Wrapped(current, commands, (deformerTargets + created).distinct())
    }

    private fun createWarp(model: PuppetModel, edit: JsonObject): PuppetModel {
        val id = edit.getValue("id").jsonPrimitive.content
        require(model.deformers.none { it.id.raw == id })
        val ids = edit.getValue("meshes").jsonArray.map { it.jsonPrimitive.content }.toSet()
        val drawables = model.drawables.filter { it.id.raw in ids }
        require(drawables.size == ids.size && drawables.isNotEmpty() && drawables.all { it.mesh != null })
        require(drawables.map { it.parentDeformerId }.distinct().size == 1)
        val parent = drawables.first().parentDeformerId
        val partId = parent?.let { p -> model.deformers.firstOrNull { it.id == p }?.partId }
            ?: model.partByDrawable()[drawables.first().id]
        require(partId == null || model.parts.any { it.id == partId })
        val rows = edit.getValue("rows").jsonPrimitive.int
        val cols = edit.getValue("columns").jsonPrimitive.int
        val box = edit.getValue("bounds").jsonObject
        val x = box.getValue("x").jsonPrimitive.float
        val y = box.getValue("y").jsonPrimitive.float
        val w = box.getValue("w").jsonPrimitive.float
        val h = box.getValue("h").jsonPrimitive.float
        require(listOf(x, y, w, h).all(Float::isFinite) && w > 1e-6f && h > 1e-6f)
                val points = (0..rows).flatMap { r -> (0..cols).flatMap { c -> listOf(x+c*w/cols, y+r*h/rows) } }.toFloatArray()
                val warp = Deformer.Warp(DeformerId(id), edit.getValue("name").jsonPrimitive.content, parent, partId, rows, cols, true, KeyformGrid(emptyList(), listOf(KeyformCell(intArrayOf(), WarpLatticeForm(points)))))
                return model.copy(deformers=model.deformers+warp,drawables=model.drawables.map { d -> if(d.id.raw !in ids)d else {
                    val mesh=d.mesh!!
                    d.copy(parentDeformerId=warp.id,mesh=DrawableMesh(FloatArray(mesh.positions.size) { j -> if(j%2==0)(mesh.positions[j]-x)/w else (mesh.positions[j]-y)/h },mesh.uvs,mesh.indices),
                        geometryGrid=d.geometryGrid?.let { grid -> KeyformGrid(grid.axes,grid.cells.map { cell -> KeyformCell(cell.coordinate,MeshDeltaForm(FloatArray(cell.form.positionDeltas.size) { j -> cell.form.positionDeltas[j]/if(j%2==0)w else h })) }) },
                        blendShapes=d.blendShapes.map { binding -> binding.copy(forms=binding.forms.map { form -> form?.let { MeshForm(FloatArray(it.positionDeltas.size) { j -> it.positionDeltas[j]/if(j%2==0)w else h },it.drawOrder,it.opacity,it.multiplyColor,it.screenColor) } }) })
                } }).withDerivedRenderRoot()
    }

    private fun bounds(arrays: List<FloatArray>): FloatArray {
        var minX = Float.POSITIVE_INFINITY; var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY; var maxY = Float.NEGATIVE_INFINITY
        arrays.forEach { points ->
            require(points.size % 2 == 0 && points.all(Float::isFinite))
            for (i in points.indices step 2) {
                minX = minOf(minX, points[i]); maxX = maxOf(maxX, points[i])
                minY = minOf(minY, points[i + 1]); maxY = maxOf(maxY, points[i + 1])
            }
        }
        require(minX.isFinite() && minY.isFinite())
        return floatArrayOf(minX, minY, (maxX - minX).coerceAtLeast(1e-6f), (maxY - minY).coerceAtLeast(1e-6f))
    }
}
