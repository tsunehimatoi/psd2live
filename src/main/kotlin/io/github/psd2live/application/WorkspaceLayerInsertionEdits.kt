package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.serialization.json.*
import org.umamo.format.art.LayerRaster
import org.umamo.runtime.model.*

/** Source additions keep the preceding geometry input, rig frames and object identities. */
internal object WorkspaceLayerInsertionEdits {
    fun freeze(document: WorkspaceDocument, model: RigPreviewModel) = document.copy(
        rigEdits = RigLayerDeletion.preserve(model, document.config()),
        generationSource = document.generationSource ?: document.source)

    fun identities(document: WorkspaceDocument, model: RigPreviewModel): WorkspaceDocument {
        val config = document.config()
        val analysis = RigGenerationSource.prepare(RigGenerationSource.analyze(document.source, config), config).geometry
        val known = document.rigEdits.splitDrawableIds.mapValues { DrawableId(it.value) } +
            model.baseRig.layerIdByDrawableId.entries.associate { it.value to DrawableId(it.key) } +
            model.rig.layerIdByDrawableId.entries.associate { it.value to DrawableId(it.key) }
        val fixed = RigBuilder.assignSplitDrawableIds(analysis, known).mapValues { it.value.raw }
        return document.copy(rigEdits = document.rigEdits.copy(splitDrawableIds = document.rigEdits.splitDrawableIds + fixed))
    }

    /** Generated root geometry is converted through the actual parent's neutral transform. */
    fun materialize(document: WorkspaceDocument, current: RigPreviewModel, ids: Set<String>, parent: String?,
                            checkCancelled: () -> Unit): WorkspaceDocument {
        checkCancelled()
        val source = WorkspaceSourceArt(document.source.widthPx, document.source.heightPx,
            document.source.layers.filter { it.id.raw in ids }, document.source.groups)
        val config = document.config().copy(meshOnly = true, generateDeformers = false, generatePhysics = false,
            parentOverrides = emptyMap(), generationSource = null, meshSource = null, deletedLayerIds = emptySet(),
            rigEdits = RigEditOverlay.Empty.copy(splitDrawableIds = document.rigEdits.splitDrawableIds.filterKeys { it in ids }))
        val generated = PSD2LivePipeline().buildPreview(source, config, ProgressListener { _, _ -> checkCancelled() })
        val parentId = parent?.let(::DeformerId)
        val rig = RasterMeshPlacement.underParents(generated.rig, current.rig.puppet, { parentId }, checkCancelled)
        val records = rig.puppet.drawables.map { drawable ->
            checkCancelled()
            RasterMeshCreation.encode(rig, drawable.id).let { command ->
                if (document.rigEdits.importedCmo3 == null) command else JsonObject(command +
                    ("source_id" to JsonPrimitive(Cmo3ModelImport.PAINT_SOURCE_ID)))
            }
        }
        val baseline = requireNotNull(document.generationSource)
        // These meshes replay from their creation record, rather than being generated a second time.
        val blanks = source.layers.map { layer ->
            (WorkspaceSourceLayer.copyOf(layer, layer.order) as WorkspaceSourceLayer).copy(raster = LayerRaster(1, 1, ByteArray(4)))
        }
        val byId = records.associateBy { it.getValue("id").jsonPrimitive.content }
        val replaced = mutableSetOf<String>()
        val journal = document.rigEdits.authoringJournal.map { entry ->
            val id = entry["id"]?.jsonPrimitive?.content
            if (entry["op"]?.jsonPrimitive?.content == RasterMeshCreation.OP && id in byId) {
                require(replaced.add(requireNotNull(id))) { "Duplicate mesh creation record" }
                byId.getValue(id)
            } else entry
        } + records.filterNot { it.getValue("id").jsonPrimitive.content in replaced }
        // A layer whose unpainted pixels a paint pinned holds them no longer; a blank it already holds stays as it is.
        val held = baseline.layers.map { layer ->
            if (layer.id.raw !in ids || layer.raster.let { it.width == 1 && it.height == 1 && it.rgba.all { byte -> byte == 0.toByte() } }) layer
            else (WorkspaceSourceLayer.copyOf(layer, layer.order) as WorkspaceSourceLayer).copy(raster = LayerRaster(1, 1, ByteArray(4)))
        }
        return document.copy(generationSource = WorkspaceSourceArt(baseline.widthPx, baseline.heightPx, held + blanks.filterNot { layer -> baseline.layers.any { it.id == layer.id } }, baseline.groups),
            rigEdits = document.rigEdits.copy(authoringJournal = journal))
    }
}
