package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.WorkspaceDocument
import kotlinx.serialization.json.*
import org.umamo.runtime.model.PuppetModel

/** Both public strokes and completed GUI sessions materialize against the isolated document candidate. */
internal object WorkspaceCanvasDeformEdits {
    val supported = setOf("canvas_deform_stroke")
    fun apply(operation: WorkspaceDocumentOperation, document: WorkspaceDocument, model: RigPreviewModel): WorkspaceDocument {
        val stage = operation.request[GeometryEditStages.FIELD]?.jsonPrimitive?.content ?: "auto"
        val source = GeometryEditStages.resolve(model, stage).puppet
        val edits = JsonArray(commands(source, operation).map { GeometryEditStages.withStage(it.jsonObject, stage) })
        return if (edits.isEmpty()) document else WorkspaceDocumentEdits.journal(document, model, edits)
    }

    fun commands(model: PuppetModel, operation: WorkspaceDocumentOperation): JsonArray {
        require(operation.operation in supported)
        validateOperationSchema(operation.request, WorkspaceCanvasDeformSchemas.request)
        val (request, samples) = decode(operation.request.getValue("stroke").jsonObject)
        val session = CanvasDeformStroke.begin(model, request, samples.first())
        for (sample in samples.drop(1)) session.step(sample)
        return JsonArray(session.commands.map { GeometryEditIntent.withMode(it.jsonObject, GeometryEditIntent.mode(operation.request)) })
    }

    private fun decode(value: JsonObject): Pair<CanvasDeformStroke.Request, List<CanvasDeformStroke.Sample>> {
        fun coordinates(field: String, objectValue: JsonObject = value) = objectValue[field]?.jsonObject.orEmpty().mapValues { it.value.jsonPrimitive.float }
        val targets = value.getValue("targets").jsonArray.map { raw ->
            val target = raw.jsonObject
            val handle = target.getValue("target").jsonPrimitive.content
            CanvasDeformStroke.Target(handle.substringBefore(':'), handle.substringAfter(':'), coordinates("key", target),
                target["vertices"]?.jsonArray?.map { it.jsonPrimitive.int }?.toSet())
        }
        val tip = CanvasBrushTip(value.getValue("radius").jsonPrimitive.float, value["hardness"]?.jsonPrimitive?.float ?: 0.5f,
            value["shape"]?.jsonPrimitive?.content?.uppercase()?.let(CanvasBrushShape::valueOf) ?: CanvasBrushShape.CIRCLE,
            value["angle"]?.jsonPrimitive?.float ?: 0f, value["aspect"]?.jsonPrimitive?.float ?: 1f,
            value["falloff"]?.jsonPrimitive?.content?.uppercase()?.let(CanvasBrushFalloff::valueOf) ?: CanvasBrushFalloff.SMOOTH)
        val request = CanvasDeformStroke.Request(CanvasDeformStroke.Action.valueOf(value.getValue("action").jsonPrimitive.content.uppercase()),
            CanvasDeformStroke.Mode.valueOf(value.getValue("mode").jsonPrimitive.content.uppercase()), targets, coordinates("pose"), tip,
            value["strength"]?.jsonPrimitive?.float ?: 1f, value["connected_only"]?.jsonPrimitive?.boolean ?: false,
            value["shrink"]?.jsonPrimitive?.boolean ?: false)
        val samples = value.getValue("samples").jsonArray.map { raw ->
            val sample = raw.jsonObject; val point = sample.getValue("point").jsonArray
            CanvasDeformStroke.Sample(CanvasBrushPoint(point[0].jsonPrimitive.float, point[1].jsonPrimitive.float),
                sample["smooth"]?.jsonPrimitive?.boolean ?: false, sample["preserve_children"]?.jsonPrimitive?.boolean ?: false)
        }
        return request to samples
    }

    /** Encode the captured gesture, never its transient geometry or a caller-provided journal record. */
    fun operation(session: CanvasDeformStroke.Session, correctionMode: String = "delta", editStage: String = "auto"): WorkspaceDocumentOperation =
        operation(session.request, session.capturedSamples()).let { it.copy(request = JsonObject(it.request + (GeometryEditIntent.FIELD to JsonPrimitive(correctionMode)) + (GeometryEditStages.FIELD to JsonPrimitive(editStage)))) }
    fun operation(request: CanvasDeformStroke.Request, samples: List<CanvasDeformStroke.Sample>) =
        WorkspaceDocumentOperation("canvas_deform_stroke", buildJsonObject { put("stroke", buildJsonObject {
            put("action", request.action.name.lowercase()); put("mode", request.mode.name.lowercase())
            putJsonArray("targets") { request.targets.forEach { target -> add(buildJsonObject {
                put("target", target.handle); put("key", JsonObject(target.key.mapValues { JsonPrimitive(it.value) }))
                target.vertices?.let { put("vertices", JsonArray(it.sorted().map(::JsonPrimitive))) }
            }) } }
            put("pose", JsonObject(request.pose.mapValues { JsonPrimitive(it.value) }))
            put("radius", request.tip.radius); put("hardness", request.tip.hardness); put("strength", request.strength)
            put("shape", request.tip.shape.name.lowercase()); put("angle", request.tip.angleDeg); put("aspect", request.tip.aspect)
            put("falloff", request.tip.falloff.name.lowercase()); put("connected_only", request.connected)
            if (request.action == CanvasDeformStroke.Action.INFLATE) put("shrink", request.shrink)
            putJsonArray("samples") { samples.forEach { sample -> add(buildJsonObject {
                putJsonArray("point") { add(sample.point.x); add(sample.point.y) }; put("preserve_children", sample.preserveChildren)
                if (request.action == CanvasDeformStroke.Action.BRUSH) put("smooth", sample.smooth)
            }) } }
        }) })
}
