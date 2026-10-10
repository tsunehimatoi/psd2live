package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.serialization.json.*

/**
 * Depth slices. A generated model gets two new primitives, back and front, that replace the source mesh and its
 * layer; an imported CMO3 model keeps the legacy copy, which clones the source in journal order.
 */
internal object WorkspaceDepthSplitEdits {
    val supported = setOf("source_split_depth")

    fun apply(operation: WorkspaceDocumentOperation, document: WorkspaceDocument, model: io.github.psd2live.core.RigPreviewModel,
              work: WorkspaceRasterWork): WorkspaceDocument {
        val request = operation.request
        work.progress(0.05f, "Preparing depth slices")
        // Unnamed slices are named from the request, so a retry or a dry run and its commit agree (StableIds).
        val layers = document.source.layers.mapTo(HashSet()) { it.id.raw } + model.analysis.layers.map { it.source.id.raw }
        val meshes = model.rig.puppet.drawables.mapTo(HashSet()) { it.id.raw } + document.rigEdits.splitDrawableIds.values
        val generated = setOf("front_layer_id", "back_layer_id", "front_mesh_id", "back_mesh_id", "glue_id")
        fun named(field: String, prefix: String, taken: Set<String>) = request[field]?.jsonPrimitive?.content
            ?: StableIds.of(prefix, request, field, generated) { it in taken }
        val frontId = named("front_layer_id", "depth:", layers)
        require(document.source.layers.none { it.id.raw == frontId }) { "Front layer ID already exists: $frontId" }
        val decoded = document.config()
        val config = if ("drawOrderOverrides" in document.settings) decoded else decoded.copy(drawOrderOverrides = model.config.drawOrderOverrides)
        val sourceId = request.getValue("source_id").jsonPrimitive.content
        val middleIds = request.getValue("middle_ids").jsonArray.map { it.jsonPrimitive.content }
        val frontMeshId = named("front_mesh_id", "ArtMeshDepth_", meshes)
        val glueId = named("glue_id", "GlueDepth_", model.rig.puppet.glues.mapNotNullTo(HashSet()) { it.id })
        val names = request["names"]?.jsonArray?.map { it.jsonPrimitive.content }
        if (config.rigEdits.importedCmo3 == null) {
            val backId = named("back_layer_id", "depth-back:", layers)
            require(document.source.layers.none { it.id.raw == backId }) { "Back layer ID already exists: $backId" }
            val backMeshId = named("back_mesh_id", "ArtMeshDepthBack_", meshes)
            val slices = DepthSplit.materialize(model, config, sourceId, middleIds, backId, backMeshId, frontId, frontMeshId, glueId,
                names, work::checkpoint)
            work.progress(0.75f, "Preserving depth slice bindings")
            val frozen = WorkspaceLayerInsertionEdits.freeze(document, model)
            val placed = WorkspaceArtPrimitives.replaceLayer(frozen, model, slices.sourceLayerId, listOf(slices.front, slices.back),
                listOf(Side.NONE, Side.NONE))
            val overlay = MeshGenerationBaseline.preserve(frozen.rigEdits.copy(simEdits = slices.simulations.simEdits), model.config)
            val layers = listOf(slices.back.id.raw, slices.front.id.raw)
            fun documentWith(placed: WorkspaceDocument, rigEdits: RigEditOverlay, parents: Map<String, String?>) = placed.copy(
                layerOverrides = placed.layerOverrides + layers.associateWith { slices.classification },
                layerVisibility = placed.layerVisibility + layers.associateWith {
                    document.layerVisibility[slices.sourceLayerId] ?: slices.visible
                },
                parentOverrides = placed.parentOverrides + parents,
                settings = JsonObject(placed.settings + ("drawOrderOverrides" to JsonObject(slices.drawOrderOverrides.mapValues { JsonPrimitive(it.value) }))),
                rigEdits = rigEdits.copy(splitDrawableIds = rigEdits.splitDrawableIds + mapOf(slices.back.id.raw to backMeshId, slices.front.id.raw to frontMeshId)))
            val source = requireNotNull(slices.source)
            val parts = listOf(slices.back, slices.front).zip(slices.sliceIds).map { (layer, id) ->
                WorkspaceArtPrimitives.Part(id, layer.id.raw, requireNotNull(slices.coverage), slices.classification)
            }
            return WorkspaceArtPrimitives.materialize({ generated ->
                if (!generated) documentWith(placed, overlay, layers.associateWith { slices.parent }) else {
                    // Generated slices take the bones of the source; their parents are generated unless the source's was chosen.
                    val explicit = if (slices.sourceLayerId in model.config.parentOverrides)
                        layers.associateWith { model.config.parentOverrides[slices.sourceLayerId] } else emptyMap()
                    documentWith(WorkspaceArtPrimitives.replaceLayer(frozen, model, slices.sourceLayerId, listOf(slices.front, slices.back),
                        listOf(Side.NONE, Side.NONE), explicitParentOnly = true),
                        SourcePartitionJournal.migrateBones(overlay, source.raw, slices.sliceIds.take(1).map { it.raw }), explicit)
                }
            }, model, "depth", source, slices.sourceLayerId, parts, DepthSplit.splitParts(slices), mapOf(source to slices.sliceIds),
                mapOf(source to listOf(slices.sliceIds.first())), JsonObject(mapOf("depth" to slices.record.getValue("depth"))), slices.record, work)
        }
        val prepared = DepthSplit.prepare(model, config, sourceId, middleIds, frontId, frontMeshId, glueId, names, work::checkpoint)
        work.progress(0.75f, "Preserving depth slice bindings")
        val front = prepared.source.layers.single { it.id.raw == frontId }
        return document.copy(source = WorkspaceSourceArt(document.source.widthPx, document.source.heightPx,
            document.source.layers + front, document.source.groups), layerVisibility = prepared.config.layerVisibility,
            layerOverrides = prepared.config.layerOverrides, parentOverrides = prepared.config.parentOverrides,
            meshOverrides = prepared.config.meshOverrides, rigEdits = RigLayerDeletion.preserve(model, prepared.config),
            generationSource = if (prepared.config.rigEdits.importedCmo3 == null) document.generationSource else document.generationSource ?: document.source,
            settings = JsonObject(document.settings + ("drawOrderOverrides" to JsonObject(prepared.config.drawOrderOverrides.mapValues { JsonPrimitive(it.value) }))))
    }
}
