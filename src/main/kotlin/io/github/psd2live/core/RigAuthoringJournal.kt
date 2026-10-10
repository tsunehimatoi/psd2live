package io.github.psd2live.core

import kotlinx.serialization.json.*
import org.umamo.runtime.model.*
import io.github.psd2live.core.legacy.RigGenerationJournal
import io.github.psd2live.core.legacy.RigGenerationFrames
import io.github.psd2live.core.legacy.RigGenerationScaffold
import io.github.psd2live.core.legacy.RigMeshActivation
import io.github.psd2live.core.legacy.ArtPrimitiveReplay

/** Materialized edits: no point arrays cross the MCP boundary, but replay never reinterprets a brush. */
internal object RigAuthoringJournal {
    /** Replays [edit] on [model]; an `art_primitive` record places the parts in [skins] as the skeleton skinned them. */
    fun replay(model: PuppetModel, edit: JsonObject, skins: PrimitiveSkins = PrimitiveSkins.None): PuppetModel =
        if (edit["op"]?.jsonPrimitive?.contentOrNull == "structure")
            RigStructureEdits.replay(model, edit.getValue("edits").jsonArray.map { it.jsonObject })
        else apply(model, edit, skins)

    fun target(text: String): RigTargetRef {
        val pair = text.split(':', limit = 2)
        require(pair.size == 2 && pair[1].isNotBlank()) { "Use the kind:id reference returned by inspect" }
        if (RigTargetKind.fromString(pair[0]) == RigTargetKind.GLUE) {
            val meshes = pair[1].split(':', limit = 2)
            require(meshes.size == 2 && meshes.all { it.isNotBlank() }) { "An authored Glue ID requires model-aware resolution" }
            return RigTargetRef(RigTargetKind.GLUE, meshes[0], meshes[1])
        }
        return RigTargetRef(RigTargetKind.fromString(pair[0]), pair[1])
    }

    fun target(model: PuppetModel, text: String): RigTargetRef {
        val pair = text.split(':', limit = 2)
        require(pair.size == 2 && pair[1].isNotBlank()) { "Use the kind:id reference returned by inspect" }
        if (RigTargetKind.fromString(pair[0]) != RigTargetKind.GLUE) return target(text)
        val glue = model.glues.firstOrNull { it.id == pair[1] }
        if (glue != null) return RigTargetRef(RigTargetKind.GLUE, glue.meshA.raw, glue.meshB.raw, glueId = glue.id)
        return target(text).also { reference ->
            require(model.glues.any { it.meshA.raw == reference.id && it.meshB.raw == reference.secondaryId }) { "Glue not found: ${pair[1]}" }
        }
    }

    fun apply(model: PuppetModel, edit: JsonObject, skins: PrimitiveSkins = PrimitiveSkins.None): PuppetModel {
        validateGlueBindings(model, edit)
        return when (edit.getValue("op").jsonPrimitive.content) {
        RigLayerDeletion.OP -> RigLayerDeletion.replay(model, edit)
        // Replay starts after the last checkpoint; an earlier one is history and changes nothing here.
        RigCheckpoint.OP -> model
        GeneratedOverrides.OP -> GeneratedOverrides.apply(model, edit).model
        MeshGenerationBaseline.OP -> MeshGenerationBaseline.replay(model, edit)
        RigGenerationBaseline.OP -> RigGenerationBaseline.replay(model, edit)
        RigGenerationScaffold.OP -> RigGenerationScaffold.replay(model, edit)
        RigGenerationJournal.OP -> RigGenerationJournal.replay(model, edit)
        RigGenerationFrames.OP -> RigGenerationFrames.replay(model, edit)
        RigMeshActivation.OP -> RigMeshActivation.replay(model, edit)
        RigWarpTopology.OP -> RigWarpTopology.replay(model, edit)
        RigBezierJournal.OP -> RigBezierJournal.replay(model, edit)
        DepthSplit.OP -> DepthSplit.apply(model, edit)
        SourcePartitionJournal.OP -> SourcePartitionJournal.apply(model, edit)
        ArtPrimitiveJournal.OP -> ArtPrimitiveReplay.replay(model, edit, skins)
        RasterMeshJournal.OP -> RasterMeshJournal.replay(model, edit)
        RasterMeshCreation.OP -> RasterMeshCreation.replay(model, edit)
        LayerDeletionJournal.OP -> LayerDeletionJournal.replay(model, edit)
        "canvas_geometry", "canvas_topology", "canvas_create_warp", "canvas_create_rotation", "canvas_create_glue", "canvas_glue_edit" -> CanvasEdits.apply(model, edit)
        "path_put", "path_delete" -> DeformPathJournal.apply(model, edit)
        VertexGroupJournal.PUT, VertexGroupJournal.DELETE -> VertexGroupJournal.apply(model, edit)
        "set" -> applyKeyformSet(model, RigKeyformSetEdit(target(model, edit.text("target")), edit.coordinate("key"),
            edit["geometry"]?.jsonObject?.let { g -> RigKeyformGeometryEdit(
                controlPoints = g.floats("controlPoints"), positionDeltas = g.floats("positionDeltas"),
                originX = g.number("originX"), originY = g.number("originY"), angle = g.number("angle"), scale = g.number("scale")) },
            edit["channels"]?.jsonObject?.let { c -> RigKeyformChannelsEdit(
                opacity = c.number("opacity"), drawOrder = c.number("drawOrder"),
                multiplyColor = c.floats("multiplyColor"), screenColor = c.floats("screenColor"),
                glueIntensity = c.number("glueIntensity"), flipX = c["flipX"]?.jsonPrimitive?.boolean,
                flipY = c["flipY"]?.jsonPrimitive?.boolean) }))
        "copy" -> applyKeyformCopy(model, RigKeyformCopyEdit(target(model, edit.text("target")), edit.coordinate("from"),
            target(model, edit["destination"]?.jsonPrimitive?.content ?: edit.text("target")), edit.coordinate("key"),
            edit["channels"]?.jsonArray?.map { it.jsonPrimitive.content }))
        "delete" -> applyKeyformDelete(model, RigKeyformDeleteEdit(target(model, edit.text("target")), edit.text("parameter"),
            edit.number("value"), edit["channel"]?.jsonPrimitive?.content))
        "warp" -> RigWarpEdit.fromJson(edit.getValue("warp").jsonObject).applyTo(model)
        "parameter_keys" -> ParameterKeyEdits.apply(model, edit)
        "structure" -> RigStructureEdits.apply(model, edit.getValue("edits").jsonArray.map { it.jsonObject })
        else -> error("Unknown authoring journal operation")
        }
    }

    private fun validateGlueBindings(model: PuppetModel, edit: JsonObject) {
        val op = edit["op"]?.jsonPrimitive?.contentOrNull
        if (op !in setOf("set", "copy", "delete", "parameter_keys")) return
        val references = listOfNotNull(edit["target"]?.jsonPrimitive?.contentOrNull, edit["destination"]?.jsonPrimitive?.contentOrNull)
        if (references.none { target(model, it).kind == RigTargetKind.GLUE }) return
        val parameters = listOf("key", "from").flatMap { field -> (edit[field] as? JsonObject)?.keys.orEmpty() } +
            listOfNotNull(edit["parameter"]?.jsonPrimitive?.contentOrNull)
        require(parameters.none { id -> model.parameters.any { it.id.raw == id && it.kind == ParameterKind.BLEND_SHAPE } }) {
            "Glue intensity supports ordinary parameter keyforms; blend shape binding is unavailable"
        }
    }

    /** Compile against the preceding edit's evaluated model; validate the whole batch before persisting. */
    fun compile(model: PuppetModel, commands: JsonArray): Pair<PuppetModel, List<JsonObject>> {
        require(commands.size in 1..128) { "Use 1..128 edits" }
        var current = model
        val journal = mutableListOf<JsonObject>()
        for (element in commands) {
            val command = element.jsonObject
            val op = command.text("op")
            val compiled = when (op) {
                "deform", "seed" -> {
                    val ref = target(current, command.text("target"))
                    val key = command.coordinate("key")
                    require(key.isNotEmpty()) { "Specify the exact destination key" }
                    val kind = command.text("target").substringBefore(':')
                    val geometry = RigGeometryTools.geometry(current, kind, ref.id, key)
                    require(geometry.axes.all { it.parameterId.raw in key }) { "KEY_INCOMPLETE: include all directly bound geometry axes" }
                    val points = if (op == "seed") geometry.points else RigGeometryTools.transform(geometry, command.getValue("operations").jsonArray,
                        command["selection"]?.jsonObject ?: JsonObject(emptyMap()))
                    buildJsonObject {
                        put("op", "set"); put("target", command.getValue("target")); put("key", command.getValue("key"))
                        putJsonObject("geometry") {
                            put(if (kind == "warp") "controlPoints" else "positionDeltas",
                                JsonArray(points.indices.map { JsonPrimitive(if (kind == "warp") points[it] else points[it] - geometry.base[it]) }))
                        }
                    }
                }
                "path_deform" -> {
                    val ref = target(current, command.text("target"))
                    require(ref.kind == RigTargetKind.ART_MESH) { "Path deform requires an ArtMesh target" }
                    val pathId = command.text("path_id")
                    val key = command.coordinate("key")
                    require(key.isNotEmpty()) { "Specify the exact destination key" }
                    val geometry = RigGeometryTools.geometry(current, "mesh", ref.id, key)
                    require(geometry.axes.all { it.parameterId.raw in key }) { "KEY_INCOMPLETE: include all directly bound geometry axes" }
                    val movedPoints = command.getValue("moved_points").jsonArray.map { elem ->
                        when (elem) {
                            is JsonArray -> elem[0].jsonPrimitive.float to elem[1].jsonPrimitive.float
                            is JsonObject -> elem.getValue("x").jsonPrimitive.float to elem.getValue("y").jsonPrimitive.float
                            else -> error("Moved point must be [x, y] or {x, y}")
                        }
                    }
                    val deformed = DeformPathTools.deform(geometry.base, current.deformPaths, pathId, movedPoints, org.umamo.render.eval.DeformPathMetrics.canvasScale(current, current.drawables.single { it.id.raw == ref.id }))
                    buildJsonObject {
                        put("op", "set"); put("target", command.getValue("target")); put("key", command.getValue("key"))
                        putJsonObject("geometry") {
                            put("positionDeltas", JsonArray(deformed.indices.map { JsonPrimitive(deformed[it] - geometry.base[it]) }))
                        }
                    }
                }
                "path_put" -> {
                    val rawPoints = command.getValue("points").jsonArray
                    val needsBinding = rawPoints.any { it is JsonArray || (it is JsonObject && "wa" !in it) }
                    val targetRef = target(current, command.text("target"))
                    require(targetRef.kind == RigTargetKind.ART_MESH) { "Deform paths require an ArtMesh" }
                    val drawable = current.drawables.singleOrNull { it.id.raw == targetRef.id } ?: error("Mesh not found: ${targetRef.id}")
                    val mesh = requireNotNull(drawable.mesh) { "Drawable has no mesh" }
                    val width = command["width"]?.jsonPrimitive?.float ?: org.umamo.runtime.model.DeformPath.DEFAULT_WIDTH
                    val hardness = command["hardness"]?.jsonPrimitive?.float ?: org.umamo.runtime.model.DeformPath.DEFAULT_HARDNESS
                    val closed = command["closed"]?.jsonPrimitive?.boolean ?: false
                    val level = command["level"]?.jsonPrimitive?.int ?: 2
                    val points = if (needsBinding) {
                        rawPoints.map { elem ->
                            val (x, y, corner) = when (elem) {
                                is JsonArray -> Triple(elem[0].jsonPrimitive.float, elem[1].jsonPrimitive.float, elem.getOrNull(2)?.jsonPrimitive?.boolean ?: false)
                                is JsonObject -> Triple(elem.getValue("x").jsonPrimitive.float, elem.getValue("y").jsonPrimitive.float, elem["corner"]?.jsonPrimitive?.boolean ?: false)
                                else -> error("Point must be [x, y] or {x, y}")
                            }
                            val bound = DeformPathTools.bind(mesh.positions, mesh.indices, x, y, corner)
                            buildJsonObject {
                                put("a", bound.a); put("b", bound.b); put("c", bound.c)
                                put("wa", bound.wa); put("wb", bound.wb); put("wc", bound.wc)
                                put("corner", bound.corner)
                            }
                        }
                    } else {
                        rawPoints.map { it.jsonObject }
                    }
                    buildJsonObject {
                        put("op", "path_put"); put("path_units", "cubism")
                        put("id", command.text("id"))
                        put("target", command.text("target"))
                        put("width", width)
                        put("hardness", hardness)
                        put("closed", closed)
                        put("level", level)
                        put("points", JsonArray(points))
                    }
                }
                VertexGroupJournal.RULE -> VertexGroupJournal.compileRule(current, command)
                // Absolute points from the producer become sparse deltas against the geometry shown here.
                "canvas_geometry" -> CanvasGeometryJournal.encode(current, command)
                GeneratedOverrides.OP, DepthSplit.OP, MeshGenerationBaseline.OP, RigGenerationBaseline.OP, RigGenerationScaffold.OP, RigGenerationJournal.OP, RigGenerationFrames.OP, RigMeshActivation.OP, RigWarpTopology.OP, RigBezierJournal.OP, SourcePartitionJournal.OP, ArtPrimitiveJournal.OP, RasterMeshJournal.OP, RasterMeshCreation.OP, LayerDeletionJournal.OP, "parameter_keys", "set", "copy", "delete", "warp", "structure", "path_delete", VertexGroupJournal.PUT, VertexGroupJournal.DELETE, "canvas_topology", "canvas_create_warp", "canvas_create_rotation" -> command
                // Glue pairs vertices where both meshes rest, as Cubism's glue: a weld does nothing at rest. Older records keep their pose.
                "canvas_create_glue", "canvas_glue_edit" -> JsonObject(command - "pose")
                else -> error("Unknown authoring operation: $op")
            }
            // Ask against the model *before* this command is applied: the question is whether the slot
            // already holds what the command writes, and applying first would make every command look
            // like a no-op. Dropping the ineffective ones here is what lets the workspace funnel's
            // change guard fire — see RigCommandDelta.
            // A dropped command must not reach the returned model either: replay of the journal never sees
            // it, and a canvas_geometry edit inside the no-op tolerance still re-derives the mesh's UVs, so
            // keeping it here would preview pixels the committed rig does not have.
            val applied = apply(current, compiled)
            if ((op != "parameter_keys" || applied !== current) && !RigCommandDelta.isNoOp(current, compiled)) {
                journal += compiled
                current = applied
            }
        }
        return current to journal
    }

    internal fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
    internal fun JsonObject.coordinate(key: String) = getValue(key).jsonObject.mapValues { it.value.jsonPrimitive.float }
    internal fun JsonObject.number(key: String) = get(key)?.jsonPrimitive?.float
    internal fun JsonObject.floats(key: String) = get(key)?.jsonArray?.map { it.jsonPrimitive.float }
}
