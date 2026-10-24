package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.serialization.json.*
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterKind

/** A request is a pure candidate edit; it has no paths, jobs, UI projection or history effects. */
data class WorkspaceDocumentOperation(val operation: String, val request: JsonObject)

interface WorkspaceDocumentPort {
    suspend fun applyDocumentEdits(state: String, summary: String, edits: List<WorkspaceDocumentOperation>,
                                  author: MutationAuthor): WorkspaceMutationResult
    suspend fun previewDocumentEdits(state: String, edits: List<WorkspaceDocumentOperation>): JsonObject
    /** [WorkspaceDocumentCommands.previewRegeneration] on the current workspace. */
    suspend fun previewRegeneration(state: String, edits: List<WorkspaceDocumentOperation>): JsonObject
}

internal object WorkspaceDocumentEdits {
    val supported: Set<String> = setOf("settings_update", "layer_classify", "layer_mesh_update",
        "parameter_create", "parameter_update", "parameter_delete", "rig_deform", "keyform_apply",
        "rig_edit_structure", "object_edit_appearance", "vertex_group_update", WorkspaceDrawOrderEdits.OP, WorkspaceRasterEdits.REBUILD_MESH, WorkspaceStructureMeshEdits.OP) + WorkspaceLayerTransform.supported +
        WorkspaceRasterCommands.supported + WorkspaceLayerEdits.supported + WorkspaceAssetLayerEdits.supported + WorkspacePartitionCommands.supported + WorkspaceGenerationUpdate.supported + WorkspaceWarpEdits.supported + WorkspaceWarpControlEdits.supported +
        setOf("auto", "put", "enable", "bone", "move", "bind", "remove", "delete").map { "skeleton_$it" } +
        setOf("put", "delete", "seed_builtin", "set_key", "delete_key", "remove_curve", "pose", "move_keys", "delete_keys", "paste_keys", "replace_keys", "preset", "create", "duplicate", "rename", "properties", "close_loop", "set_event", "delete_event").map { "motion_$it" } +
        setOf("warp", "rotation", "glue", "topology").map { "canvas_$it" } +
        setOf("put", "delete", "deform").map { "path_$it" } +
        setOf("swing_put", "swing_delete", "interior_put", "interior_delete") + io.github.psd2live.core.modifier.ModifierAuthoring.supported + io.github.psd2live.core.sim.SimApply.OP + WorkspacePhysicsEdits.batchable + WorkspaceSimulationEdits.supported + WorkspaceCanvasWeightEdits.supported + WorkspaceCanvasDeformEdits.supported + WorkspaceTextureEdits.supported +
        WorkspaceLayoutEdits.supported + WorkspaceMaterialEdits.supported + WorkspaceHitAreaEdits.supported + WorkspaceSidecarEdits.supported + WorkspaceObjectEdits.supported +
        WorkspaceStackCommands.supported

    /**
     * [operation] on the complete editing document. Layout generation state only gates automatic semantic rigging;
     * every mesh, including one placed since the last generation, accepts the ordinary authoring commands.
     */
    fun apply(operation: WorkspaceDocumentOperation, document: WorkspaceDocument, model: RigPreviewModel,
              simulationWork: WorkspaceSimulationWork = WorkspaceSimulationWork.Direct,
              physicsWork: WorkspacePhysicsWork = WorkspacePhysicsWork.Direct,
              rasterWork: WorkspaceRasterWork = WorkspaceRasterWork.Direct,
              assetResources: WorkspaceAssetWorkflow? = null): WorkspaceDocument =
        // A layer an edit gave pixels of its own no longer shows its material.
        WorkspaceReferenceMeshes.ensure(WorkspaceMaterialLinks.reconcile(document,
            applyGenerated(operation, document, model, simulationWork, physicsWork, rasterWork, assetResources)), model)

    /** [layerId] showing [raster] over its unchanged rectangle, as `layer_replace_image` does. */
    fun replaceImage(document: WorkspaceDocument, model: RigPreviewModel, layerId: String, raster: org.umamo.format.art.LayerRaster,
                     fit: WorkspaceImageFit = WorkspaceImageFit.STRETCH): WorkspaceDocument =
        WorkspaceTextureEdits.apply(document, model, WorkspaceTextureEdit.ReplaceImage(layerId, WorkspaceTextureImage.Raster(raster), fit, false))

    private fun applyGenerated(operation: WorkspaceDocumentOperation, document: WorkspaceDocument, model: RigPreviewModel,
              simulationWork: WorkspaceSimulationWork, physicsWork: WorkspacePhysicsWork, rasterWork: WorkspaceRasterWork,
              assetResources: WorkspaceAssetWorkflow?): WorkspaceDocument {
        val request = operation.request
        WorkspaceArtPrimitives.requireCurrentReferences(document.rigEdits, request)
        // Every document operation, not only journal edits: a copy over the bangs is made anew on each build.
        refuseCopies(model, request)
        return when (operation.operation) {
            // One command for every stack entry and component, routed to the entry's own.
            in WorkspaceStackCommands.supported ->
                applyGenerated(WorkspaceStackCommands.route(operation, document), document, model, simulationWork, physicsWork, rasterWork, assetResources)
            WorkspaceDrawOrderEdits.OP -> WorkspaceDrawOrderEdits.apply(document, model, request)
            WorkspaceLayerTransform.OP -> WorkspaceLayerTransform.apply(document, model, request)
            WorkspaceRasterEdits.REBUILD_MESH -> WorkspaceRasterEdits.rebuildMesh(document, model, request.text("layer_id"), rasterWork)
            WorkspaceStructureMeshEdits.OP -> WorkspaceStructureMeshEdits.apply(document, model, request, rasterWork)
            in WorkspaceTextureEdits.supported -> WorkspaceTextureEdits.apply(operation, document, model, rasterWork::checkpoint)
            in WorkspaceLayoutEdits.supported -> WorkspaceLayoutEdits.apply(operation, document, model)
            in WorkspaceMaterialEdits.supported -> WorkspaceMaterialEdits.apply(operation, document, model, rasterWork)
            in WorkspaceCanvasWeightEdits.supported -> WorkspaceCanvasWeightEdits.apply(operation, document, model)
            in WorkspaceCanvasDeformEdits.supported -> WorkspaceCanvasDeformEdits.apply(operation, document, model)
            in WorkspaceWarpEdits.supported -> WorkspaceWarpEdits.apply(document, model, request)
            in WorkspaceWarpControlEdits.supported -> WorkspaceWarpControlEdits.apply(operation, document, model)
            in WorkspaceDepthSplitEdits.supported -> WorkspaceDepthSplitEdits.apply(operation, document, model, rasterWork)
            in WorkspacePartitionEdits.supported -> WorkspacePartitionEdits.apply(operation, document, model, rasterWork)
            in WorkspaceGenerationUpdate.supported -> WorkspaceGenerationUpdate.apply(operation, document, model, rasterWork)
            in WorkspaceLayerEdits.supported -> WorkspaceLayerEdits.apply(document, model, operation)
            in WorkspaceAssetLayerEdits.supported -> WorkspaceAssetLayerEdits.apply(document, model, operation, assetResources, rasterWork::checkpoint)
            in WorkspaceSimulationEdits.supported -> WorkspaceSimulationEdits.apply(operation, document, model, simulationWork).document
            in WorkspaceHitAreaEdits.supported -> WorkspaceHitAreaEdits.apply(operation, document, model)
            in WorkspaceSidecarEdits.supported -> WorkspaceSidecarEdits.apply(operation, document, model)
            in WorkspaceObjectEdits.supported -> WorkspaceObjectEdits.apply(operation, document, model)
            "settings_update" -> settings(document, model, request.getValue("changes").jsonObject)
            "layer_mesh_update" -> mesh(document, model, request.text("layer_id"), request["changes"]?.jsonObject,
                request["reset"]?.jsonPrimitive?.boolean ?: false)
            "layer_classify" -> {
                require(listOf("type", "role", "side", "parameter", "switch_id").any { it in request }) { "Provide at least one classification field" }
                val current = WorkspaceLayoutEdits.classification(document, model, request.text("layer_id"))
                classify(document, model, request.text("layer_id"), LayerClassificationOverride(
                    request["type"]?.jsonPrimitive?.content?.uppercase()?.let(LayerType::valueOf) ?: current.type,
                    request["role"]?.jsonPrimitive?.content?.uppercase()?.let(SemanticTag::valueOf) ?: current.tag,
                    request["side"]?.jsonPrimitive?.content?.uppercase()?.let(Side::valueOf) ?: current.side,
                    request["parameter"]?.jsonPrimitive?.content ?: current.parameter,
                    request["switch_id"]?.jsonPrimitive?.int ?: current.switchId))
            }
            "parameter_create", "parameter_update", "parameter_delete" -> parameter(document, model,
                operation.operation.removePrefix("parameter_"), request)
            "rig_deform" -> journal(document, model, JsonArray(request.getValue("changes").jsonArray.map {
                JsonObject(it.jsonObject + ("op" to JsonPrimitive("deform"))) }))
            "keyform_apply" -> journal(document, model, request.getValue("changes").jsonArray)
            "rig_edit_structure", "object_edit_appearance" -> {
                val edits = request.getValue("edits").jsonArray
                if (operation.operation == "rig_edit_structure") require(edits.none {
                    it.jsonObject["kind"]?.jsonPrimitive?.content == "parameter" &&
                        it.jsonObject["action"]?.jsonPrimitive?.content in setOf("create", "update", "delete")
                }) { "Use parameter operations for parameter definitions" }
                journal(document, model, buildJsonArray { add(buildJsonObject { put("op", "structure"); put("edits", edits) }) })
            }
            "vertex_group_update" -> {
                val command = if (request["delete"]?.jsonPrimitive?.boolean == true) buildJsonObject {
                    put("op", "vertex_group_delete"); put("target", request.text("target")); put("name", request.text("name"))
                } else {
                    require("rule" in request) { "rule is required unless delete=true" }
                    JsonObject(request - "delete" + ("op" to JsonPrimitive("vertex_group_rule")))
                }
                journal(document, model, JsonArray(listOf(command)))
            }
            "interior_put" -> document.copy(rigEdits=io.github.psd2live.core.interior.NativeInteriorAuthoring.put(model,document.rigEdits,request))
            "interior_delete" -> document.copy(rigEdits=io.github.psd2live.core.interior.InteriorLifecycle.delete(model, document.rigEdits, request.text("target")))
            io.github.psd2live.core.sim.SimApply.OP ->
                document.copy(rigEdits = io.github.psd2live.core.sim.SimApply.apply(document.rigEdits, model.authoredPuppet(document.rigEdits), request.text("id")))
            in io.github.psd2live.core.modifier.ModifierAuthoring.supported ->
                document.copy(rigEdits = io.github.psd2live.core.modifier.ModifierAuthoring.apply(operation.operation, model, document.rigEdits, request))
            "swing_put" -> SwingAuthoring.request(request).let { swing(document, model.rig.puppet, it.edit, it.estimatePhysics) }
            "swing_delete" -> removeSwing(document, model.rig.puppet, request.text("id"), request["bake"]?.jsonPrimitive?.boolean ?: false,
                model.authored.rig.puppet)
            in WorkspacePhysicsEdits.batchable -> WorkspacePhysicsEdits.apply(operation, document, model, physicsWork)
            "path_put", "path_delete", "path_deform" -> {
                val command = when (operation.operation) {
                    "path_put" -> WorkspacePathEdits.createPutCommand(model.rig.puppet, request).second
                    "path_delete" -> WorkspacePathEdits.createDeleteCommand(request).second
                    else -> WorkspacePathEdits.createDeformCommand(request)
                }
                journal(document, model, JsonArray(listOf(command)))
            }
            else -> when {
                operation.operation.startsWith("canvas_") -> {
                    val command = WorkspaceCanvasCommands.create(model.rig.puppet, operation.operation.removePrefix("canvas_"), request)
                    // A mesh already as Delaunay as it can be is no change, not a refusal.
                    if (operation.operation == "canvas_topology" && request["action"]?.jsonPrimitive?.content == "retriangulate" &&
                        !WorkspaceMeshTopology.changes(model.rig.puppet, command)) document
                    else journal(document, model, JsonArray(listOf(command)))
                }
                operation.operation.startsWith("source_paint_") -> paint(document, model,
                    JsonObject(request + ("mode" to JsonPrimitive(operation.operation.removePrefix("source_paint_")))), rasterWork)
                operation.operation.startsWith("skeleton_") -> skeleton(document, model,
                    JsonObject(request + ("mode" to JsonPrimitive(operation.operation.removePrefix("skeleton_")))))
                operation.operation == "motion_preset" -> WorkspaceSkeletonMotionEdits.preset(document, request)
                operation.operation.startsWith("motion_") -> motion(document, model,
                    JsonObject(request + ("mode" to JsonPrimitive(operation.operation.removePrefix("motion_")))))
                else -> throw IllegalArgumentException("Operation cannot edit a document draft: ${operation.operation}")
            }
        }
    }

    /** Document-only view of [WorkspaceSettingsIntent]; draft commands also apply its authored-pose releases. */
    fun settings(document: WorkspaceDocument, model: RigPreviewModel, changes: JsonObject): WorkspaceDocument =
        document.copy(settings = WorkspaceSettingsIntent.parse(document, model, changes).settings)

    /**
     * [document] with layer [id] classified as [classification]. A generated layer keeps the classification the rig was
     * generated with until the next generation: the new one waits in [WorkspaceGeneration.pendingClassifications]. A
     * layer the generators have not read yet takes it at once.
     */
    fun classify(document: WorkspaceDocument, model: RigPreviewModel, id: String, classification: LayerClassificationOverride): WorkspaceDocument {
        require(classification.switchId >= 0) { "switch_id must be nonnegative" }
        val layer = model.analysis.layers.firstOrNull { it.source.id.raw == id } ?: throw IllegalArgumentException("Layer not found: $id")
        require(id !in document.deletedLayerIds) { "Layer is deleted: $id" }
        fun matches(current: LayerSemantic) = current.type == classification.type && current.tag == classification.tag &&
            current.side == classification.side && current.parameter == classification.parameter && current.switchId == classification.switchId
        val generation = document.layout.generation
        if (!generation.generated || id in WorkspaceLayoutPreview.pendingIds(document)) {
            return if (matches(layer.semantic) && id !in generation.pendingClassifications) document
                else document.copy(layerOverrides = document.layerOverrides + (id to classification))
        }
        val waiting = if (matches(layer.semantic)) generation.pendingClassifications - id else generation.pendingClassifications + (id to classification)
        return if (waiting == generation.pendingClassifications) document
            else document.copy(layout = document.layout.copy(generation = generation.copy(pendingClassifications = waiting)))
    }

    fun mesh(document: WorkspaceDocument, model: RigPreviewModel, id: String, changes: JsonObject?, reset: Boolean): WorkspaceDocument {
        require(reset || changes?.isNotEmpty() == true) { "Provide layer mesh changes or reset" }
        require(document.source.layers.any { it.id.raw == id } && id !in document.deletedLayerIds) { "Layer not found: $id" }
        if (reset) return document.copy(meshOverrides = document.meshOverrides - id)
        val change = requireNotNull(changes)
        val allowed = setOf("outerMargin", "edgeMode", "edgeWidth", "maxEdgeDistance", "interiorDensity", "fillAlgorithm", "suppressBoundaryDiagonals", "fillParameters", "wrap")
        require(change.keys.all { it in allowed }) { "Unknown layer mesh setting" }
        val base = document.meshOverrides[id] ?: document.config().defaultMeshSettings(model.analysis.layers.firstOrNull { it.source.id.raw == id }?.semantic?.tag)
        fun number(key: String, fallback: Float, range: ClosedFloatingPointRange<Float>) = change[key]?.jsonPrimitive?.float
            ?.also { require(it.isFinite() && it in range) { "$key is outside its UI range" } } ?: fallback
        val settings = MeshSettings(number("outerMargin", base.outerMargin, 0f..32f),
            change["edgeMode"]?.jsonPrimitive?.content?.let(MeshEdgeMode::valueOf) ?: base.edgeMode,
            number("edgeWidth", base.edgeWidth, 0.5f..32f), number("maxEdgeDistance", base.maxEdgeDistance, 6f..128f),
            number("interiorDensity", base.interiorDensity, 6f..128f),
            change["fillAlgorithm"]?.jsonPrimitive?.content?.let(MeshFillAlgorithm::valueOf) ?: base.fillAlgorithm,
            change["suppressBoundaryDiagonals"]?.jsonPrimitive?.boolean ?: base.suppressBoundaryDiagonals,
            change["fillParameters"]?.jsonObject?.let { WorkspaceSettingsCodec.mergeFillParameters(base.fillParameters, it) } ?: base.fillParameters,
            number("wrap", base.wrap, io.github.psd2live.core.MeshWrap.range))
        return if (settings == base) document else document.copy(meshOverrides = document.meshOverrides + (id to settings))
    }

    fun parameter(document: WorkspaceDocument, model: RigPreviewModel, mode: String, request: JsonObject): WorkspaceDocument {
        val id = request.text("parameter_id").trim()
        val current = model.rig.puppet.parameters.firstOrNull { it.id.raw == id }
        if (mode == "delete") {
            io.github.psd2live.core.RigRefusal.found(current != null, "parameter:$id") { "Parameter not found: $id" }
            // A generator's own parameter goes with the generator; once the user has taken it in, it is theirs to delete.
            if (id in GeneratedParameterAdoption.generated(model.rig.puppet, model.authored.rig.puppet))
                throw io.github.psd2live.core.RigRefusal(io.github.psd2live.core.RigRefusal.Code.OWNED_BY_GENERATOR, "$id belongs to a swing or simulation; remove that instead", "parameter:$id")
            return appendParameterCommand(document, model, mode, id).withMotionClips { MotionClips.withoutParameter(it, id) }
                .let { it.copy(rigEdits = ModelSidecars.withoutParameter(it.rigEdits, id)) }
        }
        if (mode == "create") {
            require(Regex("[A-Za-z][A-Za-z0-9_]{0,63}").matches(id)) { "New parameter ID must be 1-64 ASCII letters, digits, or underscores and start with a letter" }
            io.github.psd2live.core.RigRefusal.free(current == null, "parameter:$id") { "Parameter already exists: $id" }
        } else {
            require(mode == "update") { "Unknown parameter edit: $mode" }
            io.github.psd2live.core.RigRefusal.found(current != null, "parameter:$id") { "Parameter not found: $id" }
            require(listOf("name", "min", "max", "default", "kind", "repeat").any { it in request }) { "parameter_update requires at least one editable field" }
        }
        val kind = request["kind"]?.jsonPrimitive?.content?.let { raw ->
            runCatching { ParameterKind.valueOf(raw.trim().uppercase()) }.getOrElse { throw IllegalArgumentException("Parameter kind must be normal or blend_shape") }
        } ?: current?.kind ?: ParameterKind.NORMAL
        val edit = RigParameterEdit(id, request["name"]?.jsonPrimitive?.content?.trim() ?: requireNotNull(current).name,
            request["min"]?.jsonPrimitive?.float ?: current?.min ?: if (kind == ParameterKind.BLEND_SHAPE) 0f else -1f,
            request["max"]?.jsonPrimitive?.float ?: current?.max ?: 1f,
            request["default"]?.jsonPrimitive?.float ?: current?.default ?: 0f, kind,
            request["repeat"]?.jsonPrimitive?.boolean ?: current?.repeat ?: false,
            created = mode == "create" || document.rigEdits.parameterEdits.any { it.id == id && it.created })
        if (mode == "update" && current != null && current.name == edit.name && current.min == edit.min &&
            current.max == edit.max && current.default == edit.default && current.kind == edit.kind && current.repeat == edit.repeat) return document
        return appendParameterCommand(document, model, mode, id, edit).withMotionClips { MotionClips.withParameterRange(it, id, edit.min, edit.max, edit.repeat) }
            .let { it.copy(rigEdits = ModelSidecars.withParameterRange(it.rigEdits, id, edit.min, edit.max)) }
    }

    /**
     * Motions keep playing within the axes they drive: a narrowed or deleted axis takes their keys with it, and the
     * expression entries on it likewise ([ModelSidecars]).
     */
    private fun WorkspaceDocument.withMotionClips(change: (List<MotionClip>) -> List<MotionClip>): WorkspaceDocument {
        val clips = change(rigEdits.motionClips)
        return if (clips == rigEdits.motionClips) this else copy(rigEdits = rigEdits.copy(motionClips = clips))
    }

    /**
     * Definitions must replay in authoring order: deleting an axis happens after its authored forms. A generated
     * parameter it changes is taken into the document first ([GeneratedParameterAdoption]).
     */
    private fun appendParameterCommand(document: WorkspaceDocument, model: RigPreviewModel, action: String, id: String,
                                       edit: RigParameterEdit? = null): WorkspaceDocument {
        val command = buildJsonObject {
            put("op", "structure")
            putJsonArray("edits") { add(buildJsonObject {
                put("action", action); put("kind", "parameter"); put("id", id)
                edit?.let {
                    put("name", it.name); put("min", it.min); put("max", it.max); put("default", it.default)
                    put("parameter_kind", it.kind.name); put("repeat", it.repeat)
                }
            }) }
        }
        val journal = GeneratedParameterAdoption.adopted(model.rig.puppet, model.authored.rig.puppet, listOf(command))
        return document.copy(rigEdits = document.rigEdits.copy(authoringJournal = document.rigEdits.authoringJournal + journal))
    }

    fun journal(document: WorkspaceDocument, model: RigPreviewModel, edits: JsonArray): WorkspaceDocument {
        refuseCopies(model, edits)
        // Recorded on the shown rig, they replay on the authored one, before the generators.
        val journal = JournalRecording.record(model.rig.puppet, model.authored.rig.puppet, document.rigEdits, edits, model.primitiveSkins)
        // A deleted Warp leaves the swings that moved it in the same step.
        val next = document.copy(rigEdits = SwingAuthoring.withoutTargets(document.rigEdits.copy(
            authoringJournal = document.rigEdits.authoringJournal + journal), SwingAuthoring.deletedDeformers(journal)))
        return rebakeInteriors(next, model, journal)
    }

    /**
     * Edits that change a mesh carrying an interior effect - its topology or its rest shape, by any command that names
     * the mesh - bake the effect again on the edited mesh in the same step, as the structure rebuild does, so the point
     * tools work on interior meshes as on any other. Only effects that matched their mesh before the edit and no longer
     * match it after are baked; one already out of date stays as it was. A bake the new mesh cannot take (its area
     * checks, or another refusal the bake raises) does not refuse the edit: the effect keeps its recipe and reports the
     * mesh mismatch until it is baked again. Cancellation and programming errors are never swallowed.
     */
    private fun rebakeInteriors(document: WorkspaceDocument, model: RigPreviewModel, journal: List<JsonObject>): WorkspaceDocument {
        val effects = document.rigEdits.interiorEffects
        if (effects.isEmpty() || journal.isEmpty()) return document
        // A cheap filter first: only an effect whose mesh the batch names can have changed, so drags, weights and
        // deformer edits elsewhere never pay for the authored replay below.
        val named = journal.map { it.toString() }
        val candidates = effects.filter { effect -> named.any { "\"${effect.target}\"" in it || "\"mesh:${effect.target}\"" in it } }
        if (candidates.isEmpty()) return document
        val before = model.authored.rig.puppet
        fun stamp(puppet: org.umamo.runtime.model.PuppetModel, target: String) =
            puppet.drawables.firstOrNull { it.id.raw == target }?.mesh?.let(io.github.psd2live.core.interior.InteriorMeshStamp::of)
        val current = candidates.filter { stamp(before, it.target) == it.meshStamp }
        if (current.isEmpty()) return document
        val authored = model.authoredPuppet(document.rigEdits)
        var overlay = document.rigEdits
        for (effect in current) {
            val stamped = stamp(authored, effect.target) ?: continue
            if (stamped == effect.meshStamp) continue
            overlay = try {
                io.github.psd2live.core.interior.NativeInteriorAuthoring.put(model, overlay, buildJsonObject { put("target", effect.target) }, authored)
            } catch (cancelled: java.util.concurrent.CancellationException) {
                throw cancelled
            } catch (refused: IllegalArgumentException) {
                // The bake's own refusals (require / check / error): its area limits, a texture it cannot read.
                overlay
            } catch (refused: IllegalStateException) {
                overlay
            }
        }
        return document.copy(rigEdits = overlay)
    }

    /** The request fields that name a mesh to act on: the split's reference fields, an edited object's id, its masks and parent. */
    private val copyReferenceFields = WorkspaceArtPrimitives.referenceFields + setOf("id", "ids", "masked_by", "mask_ids", "source", "sources", "target", "targets")

    /**
     * Refuses [edits] whose reference fields ([copyReferenceFields]) name a copy over the bangs the shown rig holds - as
     * an id, or as `<kind>:<id>` - with [WorkspaceGeneratedCopyEdit]: the authored rig has no such mesh, and the finished
     * one would make it anew. Names, search text and other free strings are not references.
     */
    fun refuseCopies(model: RigPreviewModel, edits: JsonElement) {
        val copies = model.rig.seeThroughCopies
        if (copies.isEmpty()) return
        fun visit(element: JsonElement, reference: Boolean) {
            when (element) {
                is JsonObject -> element.forEach { (key, child) -> visit(child, key in copyReferenceFields) }
                is JsonArray -> element.forEach { visit(it, reference) }
                is JsonPrimitive -> if (reference && element.isString) {
                    val text = element.content
                    val id = if (text in copies) text else text.substringAfter(':', "").takeIf { it in copies }
                    if (id != null) throw WorkspaceGeneratedCopyEdit(id, copies.getValue(id))
                }
                else -> Unit
            }
        }
        visit(edits, false)
    }

    fun paint(document: WorkspaceDocument, model: RigPreviewModel, request: JsonObject,
              work: WorkspaceRasterWork = WorkspaceRasterWork.Direct): WorkspaceDocument {
        return WorkspaceRasterEdits.paint(document, model, request, work)
    }

    fun skeleton(document: WorkspaceDocument, model: RigPreviewModel, request: JsonObject): WorkspaceDocument {
        // Deleting drops the armature itself; auto or put can build a new one afterwards.
        if (request.text("mode") == "delete") {
            requireNotNull(document.rigEdits.skeleton) { "No skeleton to delete" }
            return document.copy(rigEdits = document.rigEdits.copy(skeleton = null))
        }
        // The automatic armature binds only what the bake can: meshes under the IDs the generated rig gives them.
        val spec = WorkspaceSkeletonMotionEdits.skeleton(document.rigEdits.skeleton, request) {
            SkeletonAutoBuilder.build(model.copy(config = model.config.copy(rigEdits = document.rigEdits)))
        }
        SkeletonDraftEdits.validated(spec, model.rig.puppet.drawables.mapTo(HashSet()) { it.id.raw })
        WorkspaceObjectEdits.refuseUnbakedBindings(spec, document.rigEdits.skeleton, document.rigEdits, model.rig)
        return document.copy(rigEdits = document.rigEdits.copy(skeleton = spec))
    }

    fun motion(document: WorkspaceDocument, model: RigPreviewModel, request: JsonObject): WorkspaceDocument {
        val parameters = model.rig.puppet.parameters
        val ranges = parameters.associate { it.id.raw to (it.min..it.max) }
        return document.copy(rigEdits = document.rigEdits.copy(motionClips = WorkspaceSkeletonMotionEdits.motion(
            document.rigEdits.motionClips, request, ranges, document.rigEdits.skeleton, document.rigEdits.motionPresets,
            MotionLoops.keyLimits(parameters), parameters, document.rigEdits.idRenames.parameters)))
    }

    fun swing(document: WorkspaceDocument, puppet: org.umamo.runtime.model.PuppetModel, edit: RigSwingEdit, estimatePhysics: Boolean): WorkspaceDocument {
        val overlay = SwingAuthoring.put(document.rigEdits, puppet, edit, estimatePhysics)
        val physics = overlay.swingEdits.single { it.id == edit.id }.hasPhysics
        return document.copy(rigEdits = overlay, settings = if (!physics) document.settings else
            JsonObject(document.settings + ("generatePhysics" to JsonPrimitive(true))))
    }

    fun removeSwing(document: WorkspaceDocument, puppet: org.umamo.runtime.model.PuppetModel, id: String, bake: Boolean,
                    authored: org.umamo.runtime.model.PuppetModel? = null): WorkspaceDocument =
        document.copy(rigEdits = if (bake) SwingAuthoring.bake(document.rigEdits, puppet, id, authored)
            else SwingAuthoring.remove(document.rigEdits, id))

    fun physicsCatalog(document: WorkspaceDocument, model: RigPreviewModel): List<PhysicsGroup> =
        PhysicsCatalog.groups(model.analysis, document.config(), model.rig.puppet.parameters.mapTo(HashSet()) { it.id.raw }, model.rig.puppet)

    fun physics(document: WorkspaceDocument, model: RigPreviewModel, arguments: JsonObject): WorkspaceDocument {
        val id = arguments.text("id")
        val request = PhysicsAuthoring.request(physicsCatalog(document, model), arguments,
            model.rig.puppet.parameters.mapTo(HashSet()) { it.id.raw })
        var overlay = document.rigEdits
        val settings = document.settings.toMutableMap()
        request.edit?.let { overlay = PhysicsAuthoring.put(overlay, it, request.generated) }
        request.enabled?.let { enabled ->
            val flag = mapOf(PhysicsGenerator.FRONT_HAIR_ID to "physicsFrontHair",
                PhysicsGenerator.BACK_HAIR_ID to "physicsBackHair", PhysicsGenerator.EYE_JELLY_ID to "physicsEyeJelly")[id]
            if (flag != null) settings[flag] = JsonPrimitive(enabled)
            else overlay = PhysicsAuthoring.setEnabled(overlay, id, enabled)
        }
        if (request.edit != null || request.enabled == true) settings["generatePhysics"] = JsonPrimitive(true)
        return document.copy(rigEdits = overlay, settings = JsonObject(settings))
    }

    fun removePhysics(document: WorkspaceDocument, model: RigPreviewModel, id: String): WorkspaceDocument {
        require(id.isNotBlank()) { "Physics group ID is required" }
        val group = physicsCatalog(document, model).firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("Physics group not found: $id")
        require(document.rigEdits.physicsEdits.any { it.id == id }) {
            "$id is generated; physics_put enabled=false turns it off, or edit its source (swing, skeleton, preset setting)"
        }
        return document.copy(rigEdits = PhysicsAuthoring.remove(document.rigEdits, id, group.generated != null))
    }

    fun configurePhysics(document: WorkspaceDocument, model: RigPreviewModel, order: List<String>?, fps: Int?): WorkspaceDocument {
        require(order != null || fps != null) { "Give order or fps" }
        var overlay = document.rigEdits
        order?.let { overlay = PhysicsAuthoring.order(overlay, physicsCatalog(document, model), it) }
        fps?.let { overlay = PhysicsAuthoring.setFps(overlay, it) }
        return document.copy(rigEdits = overlay)
    }

    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
}
