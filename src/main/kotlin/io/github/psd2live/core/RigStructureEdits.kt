package io.github.psd2live.core

import kotlinx.serialization.json.*
import org.umamo.edit.withDeformerDeleted
import org.umamo.edit.withDeformerMoved
import org.umamo.edit.withDeformerMultiplyColor
import org.umamo.edit.withDeformerName
import org.umamo.edit.withDeformerOpacity
import org.umamo.edit.withDeformerPart
import org.umamo.edit.withDeformerQuadTransform
import org.umamo.edit.withDeformerScreenColor
import org.umamo.edit.withDeformerSelectable
import org.umamo.edit.withDrawableBlendMode
import org.umamo.edit.withDrawableCulling
import org.umamo.edit.withDrawableDrawOrder
import org.umamo.edit.withDrawableInvertMask
import org.umamo.edit.withDrawableMaskedBy
import org.umamo.edit.withDrawableMultiplyColor
import org.umamo.edit.withDrawableName
import org.umamo.edit.withDrawableOpacity
import org.umamo.edit.withDrawableScreenColor
import org.umamo.edit.withDrawableSelectable
import org.umamo.edit.withOrgChildMoved
import org.umamo.edit.withPartName
import org.umamo.edit.withPartSelectable
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
            "static" -> setOf("opacity", "draw_order", "multiply_color", "screen_color", "blend_mode", "masked_by", "invert_mask", "culling", "selectable", "quad")
            // A deformer's organizational part. Distinct from `move`, which walks the deformer parent
            // chain: this is only the Parts-panel membership, and meshes use `move` instead because they
            // are org children in a way a deformer is not.
            "part" -> setOf("part_id")
            "delete" -> emptySet()
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
            "delete" -> {
                require(kind in setOf("warp", "rotation")) { "Only a deformer can be deleted via structure delete" }
                model.withDeformerDeleted(deformer!!.id)
            }
            "rename" -> {
                val name = edit.string("name").trim()
                when(kind) {
                    "mesh" -> model.withDrawableName(mesh!!.id, name)
                    "part" -> model.withPartName(part!!.id, name)
                    else -> model.withDeformerName(deformer!!.id, name)
                }
            }
            // The values a channel track falls back to when it keys nothing. Writing one never touches the
            // track, so this is a plain overwrite of the model field — the same edit the inspector's own
            // helpers made directly, now recorded in the document so it survives a rebuild and a reload.
            "static" -> {
                var next = model
                edit["opacity"]?.jsonPrimitive?.floatOrNull?.let { value ->
                    require(kind != "part") { "A part has no opacity of its own" }
                    next = if (kind == "mesh") next.withDrawableOpacity(mesh!!.id, value)
                           else next.withDeformerOpacity(deformer!!.id, value)
                }
                edit["draw_order"]?.jsonPrimitive?.floatOrNull?.let { value ->
                    require(kind == "mesh") { "Draw order belongs to a mesh; parts order through move" }
                    next = next.withDrawableDrawOrder(mesh!!.id, value)
                }
                edit["multiply_color"]?.jsonArray?.let { value ->
                    val color = value.toColorRgb("multiply_color")
                    next = if (kind == "mesh") next.withDrawableMultiplyColor(mesh!!.id, color)
                           else next.withDeformerMultiplyColor(deformer!!.id, color)
                }
                edit["screen_color"]?.jsonArray?.let { value ->
                    val color = value.toColorRgb("screen_color")
                    next = if (kind == "mesh") next.withDrawableScreenColor(mesh!!.id, color)
                           else next.withDeformerScreenColor(deformer!!.id, color)
                }
                edit["blend_mode"]?.jsonPrimitive?.contentOrNull?.let { value ->
                    require(kind == "mesh") { "Blend mode belongs to a mesh" }
                    val mode = BlendMode.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                        ?: throw IllegalArgumentException("Unknown blend mode: $value")
                    next = next.withDrawableBlendMode(mesh!!.id, mode)
                }
                edit["masked_by"]?.jsonArray?.let { value ->
                    require(kind == "mesh") { "Only a mesh is masked" }
                    next = next.withDrawableMaskedBy(mesh!!.id, value.map { DrawableId(it.jsonPrimitive.content) })
                }
                edit["invert_mask"]?.jsonPrimitive?.booleanOrNull?.let { value ->
                    require(kind == "mesh") { "Only a mesh is masked" }
                    next = next.withDrawableInvertMask(mesh!!.id, value)
                }
                edit["culling"]?.jsonPrimitive?.booleanOrNull?.let { value ->
                    require(kind == "mesh") { "Culling belongs to a mesh" }
                    next = next.withDrawableCulling(mesh!!.id, value)
                }
                edit["selectable"]?.jsonPrimitive?.booleanOrNull?.let { value ->
                    next = when (kind) {
                        "mesh" -> next.withDrawableSelectable(mesh!!.id, value)
                        "part" -> next.withPartSelectable(part!!.id, value)
                        else -> next.withDeformerSelectable(deformer!!.id, value)
                    }
                }
                edit["quad"]?.jsonPrimitive?.booleanOrNull?.let { value ->
                    require(kind == "warp") { "Quad transform belongs to a warp" }
                    next = next.withDeformerQuadTransform(deformer!!.id, value)
                }
                next
            }
            "part" -> {
                require(kind in setOf("warp", "rotation")) { "A mesh moves through the org tree, not into a part" }
                val partId = edit["part_id"]?.jsonPrimitive?.contentOrNull?.let(::PartId)
                require(partId == null || model.parts.any { it.id == partId }) { "Part not found: $partId" }
                model.withDeformerPart(deformer!!.id, partId)
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

    private fun JsonArray.toColorRgb(key: String): ColorRgb {
        require(size == 3) { "$key must be [r, g, b]" }
        val channels = map { it.jsonPrimitive.float }
        require(channels.all { it in 0f..1f }) { "$key channels must be within 0..1" }
        return ColorRgb(channels[0], channels[1], channels[2])
    }
}
