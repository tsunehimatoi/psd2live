package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.serialization.json.*
import org.umamo.render.eval.drawableSpaceMapping
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * `layer_transform`: moves, scales or rotates a layer as a whole - any layer, imported or from the PSD, at the root or
 * under any deformer, bound or not.
 *
 * The layer's pixels, its texture tile and its meshes' texture coordinates stay as they are; what changes is where its
 * meshes sit (one Select-mode `canvas_geometry` per mesh, at rest, recorded on the authored rig like any mesh edit) and
 * the layer's [LayerTransform], which says where the canvas shows its pixels for painting. Generation never reads the
 * transform, so this is an edit of the user's rig, not a regeneration.
 */
internal object WorkspaceLayerTransform {
    const val OP = "layer_transform"
    val supported = setOf(OP)

    fun apply(document: WorkspaceDocument, model: RigPreviewModel, request: JsonObject): WorkspaceDocument {
        val layerId = request.getValue("layer_id").jsonPrimitive.content
        val delta = transform(request)
        if (delta.isIdentity) return document
        val layer = document.source.layers.singleOrNull { it.id.raw == layerId } ?: throw IllegalArgumentException("Layer not found: $layerId")
        val puppet = model.rig.puppet
        val meshes = puppet.drawables.filter { model.rig.layerIdByDrawableId[it.id.raw] == layerId && it.mesh != null }
        require(meshes.isNotEmpty()) { "Layer $layerId has no mesh to transform" }
        val edits = meshes.map { drawable -> moved(puppet, drawable.id.raw, delta) }
        val transformed = (WorkspaceSourceLayer.copyOf(layer, layer.order) as WorkspaceSourceLayer)
            .copy(layerTransform = delta.after(layer.transform).takeUnless { it.isIdentity })
        val source = WorkspaceSourceArt(document.source.widthPx, document.source.heightPx,
            document.source.layers.map { if (it.id.raw == layerId) transformed else it }, document.source.groups)
        return WorkspaceDocumentEdits.journal(document.copy(source = source), model, JsonArray(edits))
    }

    /** The Select-mode edit that takes [id]'s rest geometry through [delta] on the canvas. */
    private fun moved(puppet: org.umamo.runtime.model.PuppetModel, id: String, delta: LayerTransform): JsonObject {
        val geometry = RigGeometryTools.geometry(puppet, "mesh", id, emptyMap())
        val mapping = requireNotNull(drawableSpaceMapping(puppet, emptyMap(), org.umamo.runtime.model.DrawableId(id))) { "Mesh $id has no parent space" }
        // World space is canvas with y up.
        val world = mapping.localToWorld(geometry.points)
        val target = FloatArray(world.size)
        for (i in 0 until world.size / 2) {
            val x = world[i * 2]; val y = -world[i * 2 + 1]
            target[i * 2] = delta.x(x, y); target[i * 2 + 1] = -delta.y(x, y)
        }
        val all = (0 until world.size / 2).toSet()
        val local = mapping.worldToLocalLinearized(target, geometry.points, geometry.points, all)
        val reached = mapping.localToWorld(local)
        require(local.all(Float::isFinite) && reached.indices.all { abs(reached[it] - target[it]) < 0.05f }) {
            "Mesh $id cannot reach the transformed position under its parent"
        }
        val key = canvasDeformationCoordinate(puppet, geometry.axes, emptyMap(), emptyList())
        return buildJsonObject {
            put("op", "canvas_geometry"); put("kind", "mesh"); put("id", id)
            put("key", JsonObject(key.mapValues { JsonPrimitive(it.value) }))
            put("preserve_image", false)
            put("points", JsonArray(local.map(::JsonPrimitive)))
        }
    }

    /**
     * The request's canvas transform: `matrix` [a, b, c, d, e, f], or `translate` [x, y], `scale` [sx, sy] (or one number)
     * and `rotate` (degrees, clockwise on screen) about `pivot` [x, y] (default the origin), applied scale, rotate, translate.
     */
    fun transform(request: JsonObject): LayerTransform {
        request["matrix"]?.let { matrix -> return LayerTransform.of(matrix.jsonArray.map { it.jsonPrimitive.float }) }
        fun pair(name: String, default: Float): Pair<Float, Float> = when (val value = request[name]) {
            null, JsonNull -> default to default
            is JsonArray -> { require(value.size == 2) { "$name takes [x, y]" }; value[0].jsonPrimitive.float to value[1].jsonPrimitive.float }
            else -> value.jsonPrimitive.float.let { it to it }
        }
        val (tx, ty) = pair("translate", 0f)
        val (sx, sy) = pair("scale", 1f)
        val (px, py) = request["pivot"]?.let { pair("pivot", 0f) } ?: (0f to 0f)
        val radians = Math.toRadians((request["rotate"]?.jsonPrimitive?.float ?: 0f).toDouble())
        val cos = cos(radians).toFloat(); val sin = sin(radians).toFloat()
        // About the pivot: p' = R·S·(p - pivot) + pivot + t. Canvas y points down, so a positive angle turns clockwise.
        val a = cos * sx; val b = sin * sx; val c = -sin * sy; val d = cos * sy
        // + 0f turns a negative zero (sin 0 negated) into zero, so equal transforms compare equal.
        return LayerTransform(a + 0f, b + 0f, c + 0f, d + 0f, px - (a * px + c * py) + tx + 0f, py - (b * px + d * py) + ty + 0f)
    }
}
