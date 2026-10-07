package io.github.psd2live.core

import io.github.psd2live.project.WorkspaceSourceArt
import io.github.psd2live.project.WorkspaceSourceLayer
import io.github.psd2live.i18n.tr
import kotlinx.serialization.json.*
import org.umamo.format.art.LayerId
import org.umamo.format.art.LayerRaster
import org.umamo.format.art.SourceArt
import org.umamo.runtime.model.*
import java.util.UUID

/** Two independently paintable slices of the same rig, welded vertex for vertex. */
internal object DepthSplit {
    const val OP = "canvas_depth_split"

    data class Result(val preview: RigPreviewModel, val frontLayerId: String)
    data class Preparation(val source: SourceArt, val config: PipelineConfig, val frontLayerId: String)

    fun frontLayerIds(config: PipelineConfig): Set<String> = frontLayerIds(config.rigEdits)

    fun frontLayerIds(overlay: RigEditOverlay): Set<String> = overlay.authoringJournal.mapNotNullTo(LinkedHashSet()) {
        when (it["op"]?.jsonPrimitive?.contentOrNull) {
            OP -> it["layer_id"]?.jsonPrimitive?.contentOrNull
            ArtPrimitiveJournal.OP -> (it["depth"] as? JsonObject)?.get("front")?.jsonPrimitive?.contentOrNull
            else -> null
        }
    }

    /** A front slice keeps its topology when painted: the Glue welds it to the back vertex for vertex. */
    fun isFrontLayer(preview: RigPreviewModel?, layerId: String?): Boolean =
        layerId != null && preview != null && layerId in frontLayerIds(preview.config.rigEdits)

    /** The two slices of a materialized depth split, as new source layers, and the document settings they need. */
    data class Slices(val back: WorkspaceSourceLayer, val front: WorkspaceSourceLayer, val sourceLayerId: String,
                      val record: JsonObject, val drawOrderOverrides: Map<String, Float>, val classification: LayerClassificationOverride,
                      val parent: String?, val visible: Boolean, val simulations: RigEditOverlay,
                      /** For a version 2 record: the authored rig, the slices staged in it (back, front), and their Glues. */
                      val authored: PuppetModel? = null, val staged: PuppetModel? = null, val sliceIds: List<DrawableId> = emptyList(),
                      val replacedGlues: List<List<Glue>> = emptyList(), val weld: Glue? = null,
                      val coverage: org.umamo.format.art.LayerBounds? = null, val neutral: Bounds? = null, val source: DrawableId? = null)

    /**
     * A depth split whose slices are both document-owned primitives: the source mesh and its layer are superseded,
     * the back takes the source's place (its Glues, masks and simulation targets), the front follows the back
     * through a directional Glue, and both carry the source's authored state at this point.
     */
    fun materialize(current: RigPreviewModel, config: PipelineConfig, sourceId: String, middleIds: List<String>,
                    backId: String, backDrawableId: String, frontId: String, frontDrawableId: String, glueId: String,
                    names: List<String>?, checkpoint: () -> Unit = {}): Slices {
        checkpoint()
        require(middleIds.isNotEmpty() && sourceId !in middleIds)
        require(middleIds.distinct().size == middleIds.size) { "Middle mesh IDs must be unique" }
        val source = requireNotNull(current.rig.puppet.drawables.firstOrNull { it.id.raw == sourceId }) { "Source mesh not found: $sourceId" }
        require(source.mesh != null) { "Depth source must be a mesh" }
        val middles = middleIds.map { id ->
            requireNotNull(current.rig.puppet.drawables.firstOrNull { it.id.raw == id }) { "Middle mesh not found: $id" }
                .also { require(it.mesh != null) { "Middle objects must be meshes" } }
        }
        val layerId = requireNotNull(current.rig.layerIdByDrawableId[sourceId]) { "Source mesh has no source layer" }
        val layer = requireNotNull(current.analysis.layers.firstOrNull { it.source.id.raw == layerId }) { "Source layer not found: $layerId" }
        val takenLayers = current.analysis.source.layers.map { it.id.raw } + current.analysis.layers.map { it.source.id.raw } +
            config.rigEdits.splitDrawableIds.keys + ArtPrimitiveJournal.ownedLayers(config.rigEdits) + ArtPrimitiveJournal.supersededLayers(config.rigEdits)
        val takenMeshes = current.rig.puppet.drawables.map { it.id.raw } + current.rig.puppet.deformers.map { it.id.raw } +
            current.baseRig.puppet.drawables.map { it.id.raw } + config.rigEdits.splitDrawableIds.values +
            ArtPrimitiveJournal.replacementDrawables(config.rigEdits).keys
        require(backId.isNotBlank() && backId !in takenLayers) { "Back layer ID already exists or is empty" }
        require(frontId.isNotBlank() && frontId !in takenLayers && frontId != backId) { "Front layer ID already exists or is empty" }
        require(backDrawableId.isNotBlank() && backDrawableId !in takenMeshes) { "Back mesh ID already exists or is empty" }
        require(frontDrawableId.isNotBlank() && frontDrawableId !in takenMeshes && frontDrawableId != backDrawableId) { "Front mesh ID already exists or is empty" }
        require(glueId.isNotBlank() && current.rig.puppet.glues.none { it.id == glueId } &&
            config.rigEdits.authoringJournal.none { it["glue_id"]?.jsonPrimitive?.contentOrNull == glueId }) { "Glue ID already exists or is empty" }
        val pieceNames = names?.map(String::trim) ?: listOf("${source.name} (back)", "${source.name} (front)")
        require(pieceNames.size == 2 && pieceNames.all { it.isNotBlank() } && pieceNames.distinct().size == 2) { "Name the back and front slices once" }
        val (backName, frontName) = pieceNames
        fun slice(id: String, name: String): WorkspaceSourceLayer {
            val original = WorkspaceSourceLayer.copyOf(layer.source, layer.source.order) as WorkspaceSourceLayer
            val pixels = ByteArray(original.raster.rgba.size)
            for (offset in pixels.indices step 4096) {
                checkpoint()
                original.raster.rgba.copyInto(pixels, offset, offset, minOf(offset + 4096, pixels.size))
            }
            return original.copy(id = LayerId(id), name = name, raster = LayerRaster(original.raster.width, original.raster.height, pixels),
                clipped = false, derived = false, sourceAssetId = null, sourceSpatialReferenceId = null)
        }
        val back = slice(backId, backName)
        val front = slice(frontId, frontName)
        val middleOrders = middles.associate { drawable ->
            val middleLayerId = current.rig.layerIdByDrawableId[drawable.id.raw]
            drawable.id.raw to (config.drawOrderOverrides[middleLayerId]
                ?: config.drawOrderOverrides[drawable.id.raw] ?: drawable.drawOrder)
        }
        val low = middleOrders.values.min()
        val high = middleOrders.values.max()
        // Make room at the draw-order limits while preserving the middle objects' relative order.
        val placedOrders = middleOrders.mapValues { (_, order) ->
            when {
                low >= 1f && high <= 999f -> order
                low == high -> order.coerceIn(1f, 999f)
                else -> 1f + (order - low) / (high - low) * 998f
            }
        }
        val backOrder = placedOrders.values.min() - 1f
        val frontOrder = placedOrders.values.max() + 1f
        // The authored state, before swings and simulations write their keyforms: those follow the back slice.
        val authored = config.rigEdits.authored(current.baseRig)
        val original = authored.drawables.single { it.id == source.id }
        val mesh = requireNotNull(original.mesh)
        val canvas = RasterMeshJournal.TextureCoordinates(authored, original).toCanvas(mesh.uvs)
        fun atOrder(drawable: Drawable, order: Float): Drawable = drawable.copy(drawOrder = order,
            channelGrids = ChannelGrids(drawable.channelGrids.gridsByChannel - FormChannel.DRAW_ORDER),
            blendShapes = drawable.blendShapes.map { binding -> binding.copy(forms = binding.forms.map { form ->
                form?.let { MeshForm(it.positionDeltas, order, it.opacity, it.multiplyColor, it.screenColor) }
            }) })
        val backMesh = atOrder(original.copy(id = DrawableId(backDrawableId), name = backName,
            mesh = DrawableMesh(mesh.positions.copyOf(), canvas, mesh.indices.copyOf())), backOrder)
        val frontMesh = atOrder(original.copy(id = DrawableId(frontDrawableId), name = frontName,
            mesh = DrawableMesh(mesh.positions.copyOf(), canvas.copyOf(), mesh.indices.copyOf())), frontOrder)
        val slices = listOf(backMesh.id, frontMesh.id)
        fun children(old: List<OrgChild>) = old.flatMap { if (it == OrgChild.Drawable(source.id)) slices.map { id -> OrgChild.Drawable(id) } else listOf(it) }
        val staged = authored.copy(drawables = authored.drawables + backMesh + frontMesh,
            parts = authored.parts.map { it.copy(children = children(it.children)) },
            deformPaths = authored.deformPaths + authored.deformPaths.filter { it.drawableId == source.id }.flatMap { path ->
                listOf(path.copy(drawableId = backMesh.id), path.copy(id = "$glueId/${path.id}", drawableId = frontMesh.id))
            },
            vertexGroups = authored.vertexGroups + authored.vertexGroups.filter { it.drawableId == source.id }.flatMap { group ->
                listOf(group.copy(drawableId = backMesh.id, weights = group.weights.copyOf()), group.copy(drawableId = frontMesh.id, weights = group.weights.copyOf()))
            })
        val textureSource = PuppetSourceAtlas.SOURCE_ID_RAW
        val neutral = current.rig.sourceBoundsByDrawableId[sourceId] ?: ArtPrimitiveJournal.canvasBounds(canvas)
        val coverage = ArtPrimitiveJournal.coverage(layer.source.bounds, canvas)
        val primitives = listOf(backMesh to backId, frontMesh to frontId).map { (drawable, id) ->
            ArtPrimitiveJournal.encodePrimitive(staged, drawable, id, textureSource, coverage, neutral)
        }
        // The back is the source for every Glue that touched it; the front follows the back exactly.
        val replaced = authored.glues.filter { it.meshA == source.id || it.meshB == source.id }.map { glue ->
            listOf(glue.copy(meshA = if (glue.meshA == source.id) backMesh.id else glue.meshA,
                meshB = if (glue.meshB == source.id) backMesh.id else glue.meshB))
        }
        val weld = Glue(backMesh.id, frontMesh.id, (0 until mesh.vertexCount).map { GluePair(it, it, 0f, 1f) }, id = glueId)
        val record = JsonObject(ArtPrimitiveJournal.encode("depth", textureSource, listOf(source.id), listOf(layerId),
            mapOf(source.id to slices), primitives, replaced, listOf(weld), masks = mapOf(source.id to listOf(backMesh.id))) +
            ("depth" to buildJsonObject { put("back", backId); put("front", frontId); put("glue_id", glueId) }))
        checkpoint()
        val orders = current.rig.puppet.drawables.associate { it.id.raw to it.drawOrder } +
            config.drawOrderOverrides + placedOrders.mapKeys { (id, _) ->
                current.rig.layerIdByDrawableId[id] ?: id
            } + mapOf(backId to backOrder, frontId to frontOrder)
        val inherited = config.layerOverrides[layerId] ?: layer.semantic.let {
            LayerClassificationOverride(it.type, it.tag, it.side, it.parameter, it.switchId)
        }
        // The back takes the source's simulation targets, baked offsets and Glue roles.
        val identity = SourcePartitionGeometry.Plan(listOf(SourcePartitionGeometry.Piece(mesh,
            List(mesh.vertexCount) { org.umamo.edit.VertexSource.FromOld(it) }, IntArray(mesh.vertexCount) { it })), IntArray(mesh.vertexCount))
        val simulations = SourcePartitionJournal.migrateSimulations(config.rigEdits, authored, source.id.raw, listOf(backDrawableId), identity)
        return Slices(back, front, layerId, record, orders, inherited, source.parentDeformerId?.raw, source.isVisible, simulations,
            authored, staged, slices, replaced, weld, coverage, neutral, source.id)
    }

    fun build(pipeline: PSD2LivePipeline, current: RigPreviewModel, config: PipelineConfig,
              sourceId: String, middleId: String): Result =
        build(pipeline, current, config, sourceId, listOf(middleId))

    fun build(pipeline: PSD2LivePipeline, current: RigPreviewModel, config: PipelineConfig,
              sourceId: String, middleIds: List<String>): Result {
        val prepared = prepare(current, config, sourceId, middleIds,
            names = listOf(tr("editor.depthSplit.backName", current.rig.puppet.drawables.single { it.id.raw == sourceId }.name),
                tr("editor.depthSplit.frontName", current.rig.puppet.drawables.single { it.id.raw == sourceId }.name)))
        return Result(pipeline.buildPreviewAfterLayerSplit(current, prepared.source, prepared.config), prepared.frontLayerId)
    }

    /** Pure preparation; production commands rebuild and commit the returned durable candidate. */
    fun prepare(current: RigPreviewModel, config: PipelineConfig, sourceId: String, middleIds: List<String>,
                frontId: String = "depth:${UUID.randomUUID()}", frontDrawableId: String = "ArtMeshDepth_${UUID.randomUUID()}",
                glueId: String = "GlueDepth_${UUID.randomUUID()}", names: List<String>? = null,
                checkpoint: () -> Unit = {}): Preparation {
        checkpoint()
        require(middleIds.isNotEmpty() && sourceId !in middleIds)
        require(middleIds.distinct().size == middleIds.size) { "Middle mesh IDs must be unique" }
        val source = requireNotNull(current.rig.puppet.drawables.firstOrNull { it.id.raw == sourceId }) { "Source mesh not found: $sourceId" }
        require(source.mesh != null) { "Depth source must be a mesh" }
        val middles = middleIds.distinct().map { id ->
            requireNotNull(current.rig.puppet.drawables.firstOrNull { it.id.raw == id }) { "Middle mesh not found: $id" }
                .also { require(it.mesh != null) { "Middle objects must be meshes" } }
        }
        val layerId = requireNotNull(current.rig.layerIdByDrawableId[sourceId]) { "Source mesh has no source layer" }
        val layer = requireNotNull(current.analysis.layers.firstOrNull { it.source.id.raw == layerId }) { "Source layer not found: $layerId" }
        require(frontId.isNotBlank() && current.analysis.source.layers.none { it.id.raw == frontId } &&
            current.analysis.layers.none { it.source.id.raw == frontId } && frontId !in config.rigEdits.splitDrawableIds) { "Front layer ID already exists or is empty" }
        require(frontDrawableId.isNotBlank() && current.rig.puppet.drawables.none { it.id.raw == frontDrawableId } &&
            current.rig.puppet.deformers.none { it.id.raw == frontDrawableId } && frontDrawableId !in config.rigEdits.splitDrawableIds.values) { "Front mesh ID already exists or is empty" }
        require(glueId.isNotBlank() && current.rig.puppet.glues.none { it.id == glueId } &&
            config.rigEdits.authoringJournal.none { it["glue_id"]?.jsonPrimitive?.contentOrNull == glueId }) { "Glue ID already exists or is empty" }
        val pieceNames = names?.map(String::trim) ?: listOf("${source.name} (back)", "${source.name} (front)")
        require(pieceNames.size == 2 && pieceNames.all { it.isNotBlank() } && pieceNames.distinct().size == 2) { "Name the back and front slices once" }
        val (backName, frontName) = pieceNames
        val original = WorkspaceSourceLayer.copyOf(layer.source, layer.source.order) as WorkspaceSourceLayer
        val pixels = ByteArray(original.raster.rgba.size)
        for (offset in pixels.indices step 4096) {
            checkpoint()
            original.raster.rgba.copyInto(pixels, offset, offset, minOf(offset + 4096, pixels.size))
        }
        val front = original.copy(id = LayerId(frontId), name = frontName,
            order = (current.analysis.source.layers.maxOfOrNull { it.order } ?: 0) + 1,
            raster = LayerRaster(original.raster.width, original.raster.height, pixels),
            clipped = false, derived = true, sourceAssetId = null, sourceSpatialReferenceId = null)
        val art = WorkspaceSourceArt(current.analysis.source.widthPx, current.analysis.source.heightPx,
            current.analysis.source.layers + front, current.analysis.source.groups)
        val middleOrders = middles.associate { drawable ->
            val middleLayerId = current.rig.layerIdByDrawableId[drawable.id.raw]
            drawable.id.raw to (config.drawOrderOverrides[middleLayerId]
                ?: config.drawOrderOverrides[drawable.id.raw] ?: drawable.drawOrder)
        }
        val low = middleOrders.values.min()
        val high = middleOrders.values.max()
        // Make room at the draw-order limits while preserving the middle objects' relative order.
        val placedOrders = middleOrders.mapValues { (_, order) ->
            when {
                low >= 1f && high <= 999f -> order
                low == high -> order.coerceIn(1f, 999f)
                else -> 1f + (order - low) / (high - low) * 998f
            }
        }
        val backOrder = placedOrders.values.min() - 1f
        val frontOrder = placedOrders.values.max() + 1f
        val command = buildJsonObject {
            put("op", OP); put("id", frontDrawableId); put("source", sourceId)
            put("layer_id", frontId); put("back_name", backName); put("front_name", frontName)
            put("back_order", backOrder); put("front_order", frontOrder)
            put("glue_id", glueId)
            if (config.rigEdits.importedCmo3 != null) put("texture_source_id", Cmo3ModelImport.PAINT_SOURCE_ID)
        }
        val inherited = config.layerOverrides[layerId] ?: layer.semantic.let {
            LayerClassificationOverride(it.type, it.tag, it.side, it.parameter, it.switchId)
        }
        // Freeze the pre-copy frames and identities. Adding art must never renumber existing meshes
        // or change the coordinate systems against which their authored keyforms were written.
        val ids = current.rig.layerIdByDrawableId.entries.associate { it.value to it.key } + (frontId to frontDrawableId)
        val orders = current.rig.puppet.drawables.associate { it.id.raw to it.drawOrder } +
            config.drawOrderOverrides + placedOrders.mapKeys { (id, _) ->
                current.rig.layerIdByDrawableId[id] ?: id
            } + mapOf(layerId to backOrder, frontId to frontOrder)
        val updated = config.copy(
            layerOverrides = config.layerOverrides + (frontId to inherited),
            // Journal-created parents do not exist during base generation. Replay clones the
            // source's actual parent; the placeholder needs its original generation frame.
            parentOverrides = config.parentOverrides + (frontId to current.baseRig.puppet.drawables
                .firstOrNull { it.id == source.id }?.parentDeformerId?.raw),
            layerVisibility = config.layerVisibility + (frontId to source.isVisible),
            meshOverrides = config.meshOverrides + listOfNotNull(config.meshOverrides[layerId]?.let { frontId to it }).toMap(),
            drawOrderOverrides = orders,
            rigEdits = config.rigEdits.copy(
                splitBaselineLayerIds = config.rigEdits.splitBaselineLayerIds.ifEmpty {
                    current.analysis.source.layers.filterNot {
                        !RigLayerDeletion.deferred(config) && it.id.raw in config.deletedLayerIds
                    }.mapTo(LinkedHashSet()) { it.id.raw }
                },
                splitDrawableIds = config.rigEdits.splitDrawableIds + ids,
                importedLayerIds = if (config.rigEdits.importedCmo3 == null) config.rigEdits.importedLayerIds else
                    config.rigEdits.importedLayerIds + (frontDrawableId to frontId),
                authoringJournal = config.rigEdits.authoringJournal + command,
            ),
        )
        checkpoint()
        return Preparation(art, updated, frontId)
    }

    /** Replays at the exact point the copy was made, after earlier mesh edits, before later ones. */
    fun apply(model: PuppetModel, command: JsonObject): PuppetModel {
        val source = model.drawables.firstOrNull { it.id.raw == command.getValue("source").jsonPrimitive.content }
            ?: return model // The rear layer may have been deleted subsequently.
        val mesh = source.mesh ?: return model
        val id = DrawableId(command.getValue("id").jsonPrimitive.content)
        val tileId = PuppetSourceAtlas.tileIdFor(command.getValue("layer_id").jsonPrimitive.content,
            command["texture_source_id"]?.jsonPrimitive?.content ?: PuppetSourceAtlas.SOURCE_ID_RAW)
        val fromTile = model.atlas.tiles.firstOrNull { it.id == source.atlasTileId } ?: return model
        val toTile = model.atlas.tiles.firstOrNull { it.id == tileId } ?: return model
        val from = fromTile.placement ?: return model
        val to = toTile.placement ?: return model
        val clone = source.copy(atlasTileId = tileId, texturePage = to.pageIndex)
        val sourceTexture = RasterMeshJournal.TextureCoordinates(model, source)
        val canvas = if (command["texture_source_id"] != null && sourceTexture.sourceId.raw != Cmo3ModelImport.PAINT_SOURCE_ID)
            mesh.positions.copyOf() else sourceTexture.toCanvas(mesh.uvs)
        val uvs = RasterMeshJournal.TextureCoordinates(model, clone).toUvs(canvas)
        fun atOrder(drawable: Drawable, order: Float): Drawable = drawable.copy(drawOrder = order,
            channelGrids = ChannelGrids(drawable.channelGrids.gridsByChannel - FormChannel.DRAW_ORDER),
            blendShapes = drawable.blendShapes.map { binding -> binding.copy(forms = binding.forms.map { form ->
                form?.let { MeshForm(it.positionDeltas, order, it.opacity, it.multiplyColor, it.screenColor) }
            }) })
        val prior = model.drawables.firstOrNull { it.id == id }
        val front = atOrder(source.copy(id = id, name = command.getValue("front_name").jsonPrimitive.content,
            mesh = DrawableMesh(mesh.positions.copyOf(), uvs, mesh.indices.copyOf()),
            atlasTileId = tileId, texturePage = to.pageIndex, textureSourceId = null,
            isVisible = prior?.isVisible ?: source.isVisible,
        ), command.getValue("front_order").jsonPrimitive.float)
        val back = atOrder(source.copy(name = command.getValue("back_name").jsonPrimitive.content),
            command.getValue("back_order").jsonPrimitive.float)
        // A=rear remains the original rig. B=front follows it completely, including deformation
        // produced later by simulation and pre-existing glues; averaging would move the original.
        val glueId = command.getValue("glue_id").jsonPrimitive.content
        val glue = Glue(source.id, id, (0 until mesh.vertexCount).map { GluePair(it, it, 0f, 1f) }, id = glueId)
        fun children(old: List<OrgChild>): List<OrgChild> = old.filterNot { it == OrgChild.Drawable(id) }.flatMap {
            if (it == OrgChild.Drawable(source.id)) listOf(it, OrgChild.Drawable(id)) else listOf(it)
        }
        return model.copy(
            drawables = model.drawables.filterNot { it.id == id }.map { if (it.id == source.id) back else it } + front,
            parts = model.parts.map { it.copy(children = children(it.children)) },
            rootChildren = children(model.rootChildren),
            glues = model.glues.filterNot { it.id == glueId } + glue,
            deformPaths = model.deformPaths.filterNot { it.drawableId == id } + model.deformPaths
                .filter { it.drawableId == source.id }.map { it.copy(id = "$glueId/${it.id}", drawableId = id) },
            vertexGroups = model.vertexGroups.filterNot { it.drawableId == id } + model.vertexGroups
                .filter { it.drawableId == source.id }.map { it.copy(drawableId = id, weights = it.weights.copyOf()) },
        ).withDerivedRenderRoot()
    }
}
