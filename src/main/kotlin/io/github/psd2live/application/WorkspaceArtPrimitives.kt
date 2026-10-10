package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.serialization.json.*
import org.umamo.runtime.model.DrawableId
import java.util.UUID

/** Document side of materialized splits: the parts replace the original layer, and old references name the parts. */
internal object WorkspaceArtPrimitives {
    /** One part of a split as its record declares it: its mesh, layer, the canvas rectangle its texture covers and class. */
    class Part(val id: DrawableId, val layer: String, val sourceBounds: org.umamo.format.art.LayerBounds,
               val classification: LayerClassificationOverride)

    /**
     * A split of [original] on [model]: the document [document] gives - the split's source, settings and moved bindings,
     * without its record - with the split's `art_primitive` record and a checkpoint after it ([RigRegenerationCheckpoint.split]).
     *
     * The parts of a generated original are generated in its place: the record is version 2, a declaration of what the
     * generators build - each part's rest mesh in canvas units as the generated original's vertices place it, its layer
     * and classification - and the user's changes to the original carry onto the parts through the merge. The parts of
     * an original the generators do not make (a mesh the journal created) stay out of generation: [userRecord], the
     * version 1 record, declares them, and they are the user's. Neither record is replayed; the checkpoint holds the
     * result. [document] is told which of the two the split writes. [split] makes the parts from a rig's own original.
     */
    fun materialize(document: (generated: Boolean) -> WorkspaceDocument, model: RigPreviewModel, origin: String, original: DrawableId,
                    originalLayer: String, parts: List<Part>, split: RigRegenerationCheckpoint.SplitParts,
                    replace: Map<DrawableId, List<DrawableId>>, masks: Map<DrawableId, List<DrawableId>>, extra: JsonObject,
                    userRecord: JsonObject, work: WorkspaceRasterWork): WorkspaceDocument {
        work.progress(0.78f, "Splitting the generated rig")
        val generated = model.baseRig.resolvedPuppet()
        val generatedParts = if (generated.drawables.none { it.id == original }) null else split.split(generated, user = false)
        work.checkpoint()
        val authoredParts = requireNotNull(split.split(model.authored.rig.puppet, user = true)) { "The split original is not in the rig: ${original.raw}" }
        val record = if (generatedParts == null) userRecord else JsonObject(ArtPrimitiveJournal.encode(origin, PuppetSourceAtlas.SOURCE_ID_RAW,
            listOf(original), listOf(originalLayer), replace, parts.map { declaration(generatedParts, authoredParts, it) }, emptyList(), emptyList(),
            masks, version = ArtPrimitiveV2.VERSION_V2) + extra)
        val placed = document(generatedParts != null)
        val candidate = placed.copy(rigEdits = placed.rigEdits.copy(authoringJournal = placed.rigEdits.authoringJournal + record))
        val decoded = candidate.config()
        val config = if ("drawOrderOverrides" in candidate.settings) decoded else decoded.copy(drawOrderOverrides = model.config.drawOrderOverrides)
        work.progress(0.8f, "Merging the split into the rig")
        val bounds = parts.associate { part ->
            part.id to ArtPrimitiveJournal.canvasBounds(requireNotNull(authoredParts.drawables.single { it.id == part.id }.mesh).uvs)
        }
        val next = RigRegenerationCheckpoint.split(PSD2LivePipeline(), model, config, candidate.source, original, split,
            parts.associate { it.id to it.layer }, bounds, work::checkpoint)
        return candidate.copy(rigEdits = next.rigEdits)
    }

    /**
     * [part]'s version 2 primitive: what the generators build it from. Its rest mesh is the generated original's part
     * ([generated]) placed on the canvas through the generated deformers, so the generated part is the generated
     * original's and the user's changes stay the merge's to carry. Per-vertex data of the user's ([authored]: Glues of
     * its own, vertex groups, paths) pins the topology: the skeleton keeps its vertices.
     */
    private fun declaration(generated: org.umamo.runtime.model.PuppetModel, authored: org.umamo.runtime.model.PuppetModel, part: Part): JsonObject {
        val drawable = generated.drawables.single { it.id == part.id }
        val mesh = requireNotNull(drawable.mesh) { "A split part needs a mesh" }
        RasterMeshJournal.validateMesh(mesh)
        val resting = generated.copy(drawables = generated.drawables.map { if (it.id == part.id) it.copy(geometryGrid = null, blendShapes = emptyList()) else it })
        val world = requireNotNull(org.umamo.render.eval.drawableSpaceMapping(resting, emptyMap(), part.id)) {
            "Split part parent cannot be evaluated: ${part.id.raw}"
        }.localToWorld(mesh.positions)
        val canvas = FloatArray(world.size) { if (it % 2 == 0) world[it] else -world[it] }
        val glues = generated.glues.toHashSet()
        val pinned = authored.glues.any { (it.meshA == part.id || it.meshB == part.id) && it !in glues } ||
            authored.vertexGroups.any { it.drawableId == part.id } || authored.deformPaths.any { it.drawableId == part.id }
        val neutral = ArtPrimitiveJournal.canvasBounds(mesh.uvs)
        val source = part.sourceBounds
        fun floats(values: FloatArray) = JsonArray(values.map(::JsonPrimitive))
        return buildJsonObject {
            put(ArtPrimitiveV2.ID, part.id.raw); put(ArtPrimitiveV2.LAYER_ID, part.layer); put(ArtPrimitiveV2.SOURCE_ID, PuppetSourceAtlas.SOURCE_ID_RAW)
            put(ArtPrimitiveV2.SOURCE_BOUNDS, floats(floatArrayOf(source.left.toFloat(), source.top.toFloat(),
                (source.left + source.width).toFloat(), (source.top + source.height).toFloat())))
            put(ArtPrimitiveV2.NEUTRAL_BOUNDS, floats(floatArrayOf(neutral.left, neutral.top, neutral.right, neutral.bottom)))
            put(ArtPrimitiveV2.NAME, authored.drawables.single { it.id == part.id }.name)
            put(ArtPrimitiveV2.CLASSIFICATION, ArtPrimitiveV2.encodeClassification(part.classification))
            put(ArtPrimitiveV2.PARENT, JsonNull)
            putJsonObject(ArtPrimitiveV2.MESH) {
                put(ArtPrimitiveV2.CANVAS_POSITIONS, floats(canvas))
                put(ArtPrimitiveV2.TRIANGLES, JsonArray(mesh.indices.map(::JsonPrimitive)))
                put(ArtPrimitiveV2.CANVAS_UVS, floats(mesh.uvs))
            }
            put(ArtPrimitiveV2.FIXED_TOPOLOGY, pinned)
            put(ArtPrimitiveV2.FROZEN_AXES, JsonArray(emptyList()))
            put(ArtPrimitiveV2.AUTHORED, JsonObject(emptyMap()))
        }
    }

    /** Rejects a reference to a layer or mesh a split removed, naming what replaced it. */
    fun requireCurrent(overlay: RigEditOverlay, id: String) {
        val layers = ArtPrimitiveJournal.replacementLayers(overlay)[id]
        if (layers != null) throw IllegalArgumentException("Layer $id was split and no longer exists; use its parts: ${layers.joinToString()}")
        val meshes = ArtPrimitiveJournal.replacementDrawables(overlay)[id]
        if (meshes != null) throw IllegalArgumentException("Mesh $id was split and no longer exists; use its parts: ${meshes.joinToString()}")
    }

    /** Superseded layer and mesh ids, each with the current ids that replaced it. */
    fun supersededBy(overlay: RigEditOverlay): Map<String, List<String>> =
        ArtPrimitiveJournal.replacementDrawables(overlay) + ArtPrimitiveJournal.replacementLayers(overlay)

    /** Request fields that name an existing layer or object; new ids and display names are not checked. */
    private val referenceFields = setOf("layer_id", "layer_ids", "target", "targets", "destination", "source_id", "middle_ids",
        "mesh_a", "mesh_b", "meshes", "mesh_id", "mesh_ids", "drawable_id", "drawable_ids", "object_id", "object_ids")

    /** Every reference field in [request] naming a superseded layer or mesh, alone or as `kind:id`. */
    fun requireCurrentReferences(overlay: RigEditOverlay, request: JsonObject) {
        val superseded = supersededBy(overlay)
        if (superseded.isEmpty()) return
        fun visit(value: JsonElement, reference: Boolean) {
            when (value) {
                is JsonObject -> value.forEach { (key, child) -> visit(child, key in referenceFields) }
                is JsonArray -> value.forEach { visit(it, reference) }
                is JsonPrimitive -> if (reference && value.isString) {
                    val text = value.content
                    val id = if (text in superseded) text else text.substringAfter(':', "").takeIf { it in superseded }
                    if (id != null) requireCurrent(overlay, id)
                }
                else -> Unit
            }
        }
        visit(request, false)
    }

    /** Mesh ids for new parts, named as the generator names layers and distinct from every id in use. */
    fun allocate(document: WorkspaceDocument, model: RigPreviewModel, layers: List<WorkspaceSourceLayer>): List<String> {
        val reserved = LinkedHashSet<String>()
        for (puppet in listOf(model.rig.puppet, model.baseRig.puppet)) {
            puppet.drawables.forEach { reserved += it.id.raw }; puppet.deformers.forEach { reserved += it.id.raw }
        }
        reserved += document.rigEdits.splitDrawableIds.values
        ArtPrimitiveJournal.commands(document.rigEdits).forEach { command ->
            ArtPrimitiveJournal.primitives(command).forEach { reserved += it.getValue("id").jsonPrimitive.content }
            command.getValue("supersedes").jsonArray.forEach { reserved += it.jsonPrimitive.content }
        }
        val art = WorkspaceSourceArt(document.source.widthPx, document.source.heightPx, layers, document.source.groups)
        val analysis = CharacterAnalyzer.analyze(art, document.config())
        val existing = reserved.withIndex().associate { (index, id) -> "\u0000reserved:$index" to DrawableId(id) }
        val assigned = RigBuilder.assignSplitDrawableIds(analysis, existing)
        return layers.map { layer ->
            assigned[layer.id.raw]?.raw ?: run {
                val base = "ArtMeshPart" + UUID.nameUUIDFromBytes(layer.id.raw.toByteArray(Charsets.UTF_8)).toString().replace("-", "")
                var candidate = base; var suffix = 2
                while (candidate in reserved) candidate = "$base${suffix++}"
                candidate
            }.also { reserved += it }
        }
    }

    /**
     * [document] with the source layer [id] replaced by [parts] at its place in the stack. The parts inherit the
     * original's classification, visibility, parent, mesh settings and draw order override; the original's own
     * settings stay, because the frozen generation input still builds it for the journal before the split.
     */
    fun replaceLayer(document: WorkspaceDocument, model: RigPreviewModel, id: String, parts: List<WorkspaceSourceLayer>,
                     sides: List<Side>, explicitParentOnly: Boolean = false): WorkspaceDocument {
        val original = document.source.layers.firstOrNull { it.id.raw == id }
            ?: model.analysis.layers.first { it.source.id.raw == id }.source
        val classification = document.layerOverrides[id] ?: model.analysis.layers.firstOrNull { it.source.id.raw == id }?.semantic?.let {
            LayerClassificationOverride(it.type, it.tag, it.side, it.parameter, it.switchId)
        } ?: LayerClassificationOverride()
        val ids = parts.map { it.id.raw }
        val parent = if (id in document.parentOverrides) document.parentOverrides[id] else model.rig.puppet.drawables.firstOrNull {
            it.id.raw == id || model.rig.layerIdByDrawableId[it.id.raw] == id
        }?.parentDeformerId?.raw
        val owner = if (document.source.layers.any { it.id.raw == id }) id else document.source.layers
            .map { it.id.raw }.filter { id.startsWith("$it:") }.maxByOrNull(String::length)
        val stacked = document.source.layers.flatMap { if (it.id.raw == owner) listOf(it) + parts else listOf(it) }
            .let { if (owner == null) it + parts else it }
        // Number the stack with the original in it, so the parts take its place and every other layer keeps its rank.
        val layers = stacked.mapIndexed { index, layer -> WorkspaceSourceLayer.copyOf(layer, stacked.size - index) }
            .filterNot { it.id.raw == id }
        val drawOrders = document.settings["drawOrderOverrides"]?.jsonObject.orEmpty() + ids.mapNotNull { next ->
            document.settings["drawOrderOverrides"]?.jsonObject?.get(id)?.let { next to it }
        }.toMap()
        return document.copy(source = WorkspaceSourceArt(document.source.widthPx, document.source.heightPx, layers, document.source.groups),
            layerOverrides = document.layerOverrides + ids.mapIndexed { index, next ->
                next to classification.copy(side = sides[index].takeUnless { it == Side.NONE } ?: classification.side)
            },
            layerVisibility = document.layerVisibility + ids.associateWith { document.layerVisibility[id] ?: original.visible },
            // Version 2 parts are generated: pinning them under the original's generated parent would keep them out
            // of pair warps, so only an explicit override of the original carries over.
            parentOverrides = if (!explicitParentOnly) document.parentOverrides + ids.associateWith { parent }
                else if (id in document.parentOverrides) document.parentOverrides + ids.associateWith { document.parentOverrides[id] }
                else document.parentOverrides,
            meshOverrides = document.meshOverrides + ids.mapNotNull { next -> document.meshOverrides[id]?.let { next to it } }.toMap(),
            settings = if (drawOrders.isEmpty()) document.settings else JsonObject(document.settings + ("drawOrderOverrides" to JsonObject(drawOrders))))
    }
}
