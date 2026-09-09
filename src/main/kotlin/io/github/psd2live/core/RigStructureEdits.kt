package io.github.psd2live.core

import kotlinx.serialization.json.*
import org.umamo.edit.*
import org.umamo.runtime.model.*

/** Ordered document edits. Stored verbatim in history and replayed after rig construction. */
internal object RigStructureEdits {
    fun apply(model: PuppetModel, edits: List<JsonObject>): PuppetModel = edits.fold(model, ::applyOne)

    private fun applyOne(model: PuppetModel, edit: JsonObject): PuppetModel {
        val action = edit.string("action")
        if(action == "create_warp") return RigWarpEdit.fromJson(JsonObject(edit - "action")).applyTo(model)
        val kind = edit.string("kind")
        val id = edit.string("id")
        require(kind in setOf("mesh", "warp", "rotation", "part")) { "Expected mesh, warp, rotation or part" }
        val allowed = when (action) {
            "rename" -> setOf("name")
            "visibility" -> setOf("visible")
            "move" -> setOf("parent_id", "before_id", "before_kind", "space")
            "bind" -> setOf("parent_id", "space")
            else -> error("Unknown structure action: $action")
        }
        require((edit.keys - allowed - setOf("action", "kind", "id")).isEmpty()) { "Unexpected field for $action" }
        val mesh = model.drawables.singleOrNull { it.id.raw == id }
        val part = model.parts.singleOrNull { it.id.raw == id }
        val deformer = model.deformers.singleOrNull { it.id.raw == id }
        require(when(kind) {
            "mesh" -> mesh != null; "part" -> part != null
            "warp" -> deformer is Deformer.Warp; else -> deformer is Deformer.Rotation
        }) { "Object not found: $kind $id" }
        fun parent(): String? {
            require("parent_id" in edit) { "Specify parent_id; null means root" }
            return edit["parent_id"]?.jsonPrimitive?.contentOrNull
        }
        return when(action) {
            "rename" -> {
                val name = edit.string("name").trim()
                when(kind) {
                    "mesh" -> model.withDrawableName(mesh!!.id, name)
                    "part" -> model.withPartName(part!!.id, name)
                    else -> model.withDeformerName(deformer!!.id, name)
                }
            }
            "visibility" -> {
                val visible = edit.getValue("visible").jsonPrimitive.boolean
                require(kind in setOf("mesh", "part")) { "Visibility belongs to mesh or part" }
                if(kind == "mesh") model.copy(drawables = model.drawables.map { if(it.id == mesh!!.id) it.copy(isVisible = visible) else it })
                else model.copy(parts = model.parts.map { if(it.id == part!!.id) it.copy(isVisible = visible) else it })
            }
            "bind" -> {
                require(kind == "mesh") { "bind changes a mesh's deformer; use move for deformers" }
                require(edit["space"]?.jsonPrimitive?.content == "local") { "Specify space=local: retains local geometry and keys, changes inherited appearance" }
                val p = parent()?.let(::DeformerId)
                require(p == null || model.deformers.any { it.id == p }) { "Parent deformer not found" }
                model.copy(drawables = model.drawables.map { if(it.id == mesh!!.id) it.copy(parentDeformerId = p) else it }).withDerivedRenderRoot()
            }
            else -> {
                val p = parent()
                val before = edit["before_id"]?.jsonPrimitive?.contentOrNull
                require(before != id) { "Cannot insert an object before itself" }
                if(kind in setOf("warp", "rotation")) {
                    require(edit["space"]?.jsonPrimitive?.content == "local") { "Specify space=local: inherited appearance changes" }
                    val parentId = p?.let(::DeformerId)
                    require(parentId == null || model.deformers.any { it.id == parentId }) { "Parent deformer not found" }
                    require(parentId == null || parentId !in model.deformerSelfAndDescendants(deformer!!.id)) { "Deformer cycle" }
                    require(before == null || model.deformers.any { it.id.raw == before && it.parent == parentId }) { "before_id must be a destination sibling" }
                    model.withDeformerMoved(deformer!!.id, parentId, before?.let(::DeformerId)).withDerivedRenderRoot()
                } else {
                    require("space" !in edit) { "Organizational moves do not change deformation space" }
                    val parentId = p?.let(::PartId)
                    require(parentId == null || model.parts.any { it.id == parentId }) { "Parent part not found" }
                    require(kind != "part" || parentId == null || parentId !in model.partSelfAndDescendants(part!!.id)) { "Part cycle" }
                    val child = if(kind == "mesh") OrgChild.Drawable(mesh!!.id) else OrgChild.Part(part!!.id)
                    val siblings = parentId?.let { model.partById.getValue(it).children } ?: model.rootChildren
                    val next = before?.let { b ->
                        when(edit.string("before_kind")) {
                            "mesh" -> OrgChild.Drawable(DrawableId(b))
                            "part" -> OrgChild.Part(PartId(b))
                            else -> error("before_kind must be mesh or part")
                        }.also { require(it in siblings) { "before_id must be a destination sibling" } }
                    }
                    model.withOrgChildMoved(child, parentId, next)
                }
            }
        }
    }

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content.also {
        require(it.isNotBlank() && it.none(Char::isISOControl)) { "$key must be nonblank text" }
    }
}
