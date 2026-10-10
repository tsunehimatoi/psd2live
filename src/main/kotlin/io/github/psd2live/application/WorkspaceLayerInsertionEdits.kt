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

    /**
     * The mesh ID each of [ids] gets: the one its mesh already has, else a fresh `ArtMeshImage…` no rig, journal record or
     * other layer holds. A one-layer build alone would name every image the same.
     */
    private fun meshIds(document: WorkspaceDocument, current: RigPreviewModel, ids: Set<String>): Map<String, String> {
        val existing = current.rig.layerIdByDrawableId.entries.filter { it.value in ids }.groupBy({ it.value }, { it.key })
        val taken = HashSet<String>()
        current.rig.puppet.drawables.mapTo(taken) { it.id.raw }
        current.authored.rig.puppet.drawables.mapTo(taken) { it.id.raw }
        document.rigEdits.authoringJournal.filter { it["op"]?.jsonPrimitive?.content == RasterMeshCreation.OP }
            .mapTo(taken) { it.getValue("id").jsonPrimitive.content }
        taken += document.rigEdits.splitDrawableIds.values
        return ids.associateWith { layer ->
            document.rigEdits.splitDrawableIds[layer] ?: existing[layer]?.singleOrNull()
                ?: StableIds.fresh("ArtMeshImage") { it in taken }.also { taken += it }
        }
    }

    /** Generated root geometry is converted through the actual parent's neutral transform. */
    fun materialize(document: WorkspaceDocument, current: RigPreviewModel, ids: Set<String>, parent: String?,
                            checkCancelled: () -> Unit): WorkspaceDocument {
        checkCancelled()
        val source = WorkspaceSourceArt(document.source.widthPx, document.source.heightPx,
            document.source.layers.filter { it.id.raw in ids }, document.source.groups)
        val config = document.config().copy(meshOnly = true, generateDeformers = false, generatePhysics = false,
            parentOverrides = emptyMap(), generationSource = null, meshSource = null, deletedLayerIds = emptySet(),
            rigEdits = RigEditOverlay.Empty.copy(splitDrawableIds = meshIds(document, current, ids)))
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
        val byId = records.associateBy { it.getValue("id").jsonPrimitive.content }
        val replaced = mutableSetOf<String>()
        // Replay starts at the checkpoint, so only a creation record after it is rewritten. A mesh the authored rig already
        // holds - its creation before the checkpoint, or generated from the layer and checkpointed since - gets a record
        // that replaces it: a rewrite before the checkpoint would never replay, and a second creation would collide.
        val checkpoint = document.rigEdits.checkpointIndex
        val journal = document.rigEdits.authoringJournal.mapIndexed { index, entry ->
            val id = entry["id"]?.jsonPrimitive?.content
            if (index > checkpoint && entry["op"]?.jsonPrimitive?.content == RasterMeshCreation.OP && id in byId) {
                require(replaced.add(requireNotNull(id))) { "Duplicate mesh creation record" }
                byId.getValue(id).let { if (RasterMeshCreation.replaces(entry)) RasterMeshCreation.replacing(it) else it }
            } else entry
        } + records.filterNot { it.getValue("id").jsonPrimitive.content in replaced }.map { record ->
            val id = record.getValue("id").jsonPrimitive.content
            if (current.authored.rig.puppet.drawables.any { it.id.raw == id }) RasterMeshCreation.replacing(record) else record
        }
        // The generators never mesh a layer a creation record owns ([RigGenerationSource.createdCoverage]); no placeholder is stored.
        return document.copy(rigEdits = document.rigEdits.copy(authoringJournal = journal))
    }
}
