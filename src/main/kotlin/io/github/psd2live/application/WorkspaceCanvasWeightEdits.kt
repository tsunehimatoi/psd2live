package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.core.modifier.*
import io.github.psd2live.project.WorkspaceDocument
import kotlinx.serialization.json.*
import org.umamo.edit.MeshTopology
import org.umamo.runtime.model.*

/** Resolve canvas requests against each candidate, then persist materialized replay records. */
internal object WorkspaceCanvasWeightEdits {
    val supported = setOf("canvas_glue_edit", "vertex_group_paint")

    fun apply(operation: WorkspaceDocumentOperation, document: WorkspaceDocument, model: RigPreviewModel): WorkspaceDocument {
        val changes = commands(model.rig.puppet, operation)
        if (operation.operation != "vertex_group_paint" || document.rigEdits.modifierStack == null)
            return WorkspaceDocumentEdits.journal(document, model, changes)
        return recordPaint(document, model, changes, operation.request["auto_bake"]?.jsonPrimitive?.boolean == true)
    }

    /** An applied modifier can produce both the mesh and its groups. Writing ahead of that
     * modifier invalidates its input contract. Record the paint on its output instead. */
    private fun recordPaint(document: WorkspaceDocument, model: RigPreviewModel, changes: JsonArray, autoBake: Boolean): WorkspaceDocument {
        val writes = RigAuthoringJournal.compile(model.rig.puppet, changes).second
        if (writes.isEmpty()) return document
        val evaluated = requireNotNull(model.modifierEvaluation)
        var stack = evaluated.stack
        val ordinary = mutableListOf<JsonObject>()
        val pending = stack.pendingWeightEdits.toMutableMap()
        for (command in writes) {
            val mesh = command.getValue("target").jsonPrimitive.content.removePrefix("mesh:")
            val target = ModifierTarget("mesh", mesh)
            // Prefer the last applied stage which sees this target. Even an identity change to
            // its weights before that stage can violate an opaque materialized input contract.
            val name = command.getValue("name").jsonPrimitive.content
            val owner = stack.instances.indices.lastOrNull { index ->
                val instance = stack.instances[index]
                if (evaluated.entries[index].status != ModifierStatus.ACTIVE) return@lastOrNull false
                val applied = stack.appliedNodes[instance.id]
                if (applied != null && (target in instance.targets || applied.reads == null)) return@lastOrNull true
                if (instance.type == "simulation") return@lastOrNull false // automatic groups yield to authored weights
                val before = if (index == 0) evaluated.authored else evaluated.outputs[index - 1]
                val after = evaluated.outputs[index]
                val old = before.meshes.firstOrNull { it.id == mesh }?.geometry
                val new = after.meshes.firstOrNull { it.id == mesh }?.geometry
                old?.indices != new?.indices || old?.uvs != new?.uvs ||
                    before.authoring.vertexGroups.firstOrNull { it.mesh == mesh && it.name == name } !=
                    after.authoring.vertexGroups.firstOrNull { it.mesh == mesh && it.name == name }
            } ?: -1
            if (owner < 0) ordinary += command else {
                val id = stack.instances[owner].id
                stack = stack.withOverrides(id, stack.overrides[id].orEmpty() + command)
            }
            // The simulation fingerprint includes authoring data, including weights on other
            // meshes. Later simulations also read the parameters/physics from earlier ones.
            for (index in owner + 1 until stack.instances.size) {
                val instance = stack.instances[index]
                if (!instance.enabled || instance.type != "simulation" || instance.id in stack.appliedNodes) continue
                if (autoBake) {
                    pending.remove(instance.id)
                    continue
                }
                val saved = pending[instance.id].orEmpty()
                if (saved.any { it.mesh == mesh && it.name == name }) continue
                val input = if (index == 0) evaluated.authored else evaluated.outputs[index - 1]
                val original = requireNotNull(BakedWeightInput.restore(input.authoring.vertexGroups, saved)) {
                    "Simulation weight group order changed; bake again before painting"
                }
                pending[instance.id] = saved + BakedWeightInput.capture(original, mesh, name)
            }
        }
        val edited = if (ordinary.isEmpty()) document else WorkspaceDocumentEdits.journal(document, model, JsonArray(ordinary))
        return edited.copy(rigEdits = edited.rigEdits.copy(modifierStack = stack.copy(pendingWeightEdits = pending).toJson()))
    }

    fun commands(model: PuppetModel, operation: WorkspaceDocumentOperation): JsonArray {
        validateOperationSchema(operation.request, WorkspaceCanvasWeightSchemas.request(operation.operation))
        return when (operation.operation) {
            "canvas_glue_edit" -> JsonArray(listOf(glue(model, operation.request)))
            "vertex_group_paint" -> weights(model, operation.request.getValue("edit").jsonObject)
            else -> error("Unsupported canvas weight operation: ${operation.operation}")
        }
    }

    private fun mesh(model: PuppetModel, raw: String): DrawableId {
        val id = DrawableId(raw.removePrefix("mesh:"))
        require(model.drawables.any { it.id == id && it.mesh != null }) { "Mesh not found: $raw" }
        return id
    }

    private fun pose(model: PuppetModel, request: JsonObject): Map<String, Float> {
        val result = request["pose"]?.jsonObject.orEmpty().mapValues { it.value.jsonPrimitive.float }
        val parameters = model.parameters.associateBy { it.id.raw }
        require(result.all { (id, value) -> parameters[id]?.let { value.isFinite() && value in it.min..it.max } == true }) {
            "Pose contains an unknown or out-of-range parameter"
        }
        return result
    }

    private fun glue(model: PuppetModel, request: JsonObject): JsonObject {
        val a = mesh(model, request.getValue("mesh_a").jsonPrimitive.content)
        val b = mesh(model, request.getValue("mesh_b").jsonPrimitive.content)
        require(a != b) { "Glue requires two different meshes" }
        fun hits(field: String, id: DrawableId): Set<Int> {
            val values = request[field]?.jsonArray.orEmpty().map { it.jsonPrimitive.int }
            val count = model.drawables.single { it.id == id }.mesh!!.vertexCount
            require(values.all { it in 0 until count }) { "$field contains an invalid vertex index" }
            return values.toSet()
        }
        val hitsA = hits("hits_a", a); val hitsB = hits("hits_b", b)
        val action = request.getValue("action").jsonPrimitive.content
        require(action == "remerge" || hitsA.isNotEmpty() || hitsB.isNotEmpty()) { "This glue action needs selected vertices" }
        val requested = request["id"]?.jsonPrimitive?.content
        require(requested == null || model.glues.none { it.id == requested &&
            !((it.meshA == a && it.meshB == b) || (it.meshA == b && it.meshB == a)) }) {
            "Glue ID belongs to a different mesh pair"
        }
        val id = requested ?: generateSequence(0) { it + 1 }.map { "Glue_${a.raw}_${b.raw}_$it" }
            .first { candidate -> model.glues.none { it.id == candidate } }
        return buildJsonObject {
            put("op", "canvas_glue_edit"); put("id", id); put("action", action)
            put("mesh_a", a.raw); put("mesh_b", b.raw)
            put("hits_a", JsonArray(hitsA.sorted().map(::JsonPrimitive))); put("hits_b", JsonArray(hitsB.sorted().map(::JsonPrimitive)))
            put("distance", request["distance"] ?: JsonPrimitive(40f))
            put("weight_mode", request["weight_mode"] ?: JsonPrimitive("balance"))
            put("delta", request["delta"] ?: JsonPrimitive(0.35f))
        }
    }

    private fun weights(model: PuppetModel, request: JsonObject): JsonArray {
        val targets = request.getValue("targets").jsonArray.map { mesh(model, it.jsonPrimitive.content) }
        require(targets.distinct().size == targets.size) { "Weight targets must be unique" }
        val kind = VertexGroupKind.parse(request.getValue("kind").jsonPrimitive.content)
        val name = request["name"]?.jsonPrimitive?.content
        val action = request.getValue("action").jsonPrimitive.content
        val pose = pose(model, request)
        val connected = request["connected_only"]?.jsonPrimitive?.boolean ?: false
        val surfaces = CanvasWeightAuthoring.surfaces(model, targets, pose, connected)
        val reaches = surfaces.map { FloatArray(it.points.size) }
        fun point(value: JsonElement): CanvasBrushPoint {
            val numbers = value.jsonArray.map { it.jsonPrimitive.float }
            require(numbers.size == 2 && numbers.all(Float::isFinite))
            return CanvasBrushPoint(numbers[0], numbers[1])
        }
        if (action == "brush") {
            val tip = CanvasBrushTip(request.getValue("radius").jsonPrimitive.float,
                request["hardness"]?.jsonPrimitive?.float ?: 0.5f,
                request["shape"]?.jsonPrimitive?.content?.uppercase()?.let(CanvasBrushShape::valueOf) ?: CanvasBrushShape.CIRCLE,
                request["angle"]?.jsonPrimitive?.float ?: 0f, request["aspect"]?.jsonPrimitive?.float ?: 1f,
                request["falloff"]?.jsonPrimitive?.content?.uppercase()?.let(CanvasBrushFalloff::valueOf) ?: CanvasBrushFalloff.SMOOTH)
            require(tip.angleDeg.isFinite()) { "Brush angle must be finite" }
            val segments = CanvasWeightStroke.segments(request)
            for ((from, to) in segments) {
                canvasWeightCheckpoint()
                val current = canvasBrushWeights(surfaces, from, to, tip, connected)
                for (at in reaches.indices) for (i in reaches[at].indices) {
                    if (i % 512 == 0) canvasWeightCheckpoint()
                    reaches[at][i] = maxOf(reaches[at][i], current[at][i])
                }
            }
        } else if (action == "gradient") {
            val from = point(request.getValue("from")); val to = point(request.getValue("to"))
            for (at in surfaces.indices) CanvasWeightAuthoring.gradient(surfaces[at].points, from, to).copyInto(reaches[at])
        }
        val mode = request["mode"]?.jsonPrimitive?.content?.uppercase()?.let(CanvasWeightAuthoring.Mode::valueOf)
            ?: CanvasWeightAuthoring.Mode.ADD
        val strength = request["strength"]?.jsonPrimitive?.float ?: 1f
        return buildJsonArray {
            for ((at, id) in targets.withIndex()) {
                canvasWeightCheckpoint()
                val existing = CanvasWeightAuthoring.group(model, id, kind, name)
                if (action == "invert" && existing == null) continue
                require(existing == null || existing.kind == kind) { "Group name belongs to a different weight kind" }
                val base = existing?.weights ?: FloatArray(reaches[at].size)
                val weights = if (action == "invert") CanvasWeightAuthoring.invert(base) else CanvasWeightAuthoring.apply(base,
                    reaches[at], mode, strength, if (mode == CanvasWeightAuthoring.Mode.SMOOTH)
                        MeshTopology.buildVertexAdjacency(base.size, model.drawables.single { it.id == id }.mesh!!.indices) else null)
                // A dab that reaches nothing never invents an empty group, matching the canvas.
                if (existing == null && weights.none { it > 0f }) continue
                add(VertexGroupJournal.encode(VertexGroup(CanvasWeightAuthoring.name(model, id, kind, name), id, kind, weights)))
            }
        }
    }
}
