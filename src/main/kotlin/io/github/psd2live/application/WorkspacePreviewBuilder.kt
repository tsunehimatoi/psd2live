package io.github.psd2live.application

import io.github.psd2live.core.PSD2LivePipeline
import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.core.ProgressListener
import io.github.psd2live.core.RigRegenerationCheckpoint
import io.github.psd2live.core.VertexGroupJournal
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.umamo.render.restMeshesToCanvasSpace
import io.github.psd2live.project.MaterializedRigStore
import io.github.psd2live.project.WorkspaceDocument
import io.github.psd2live.project.WorkspaceRevisions
import io.github.psd2live.project.config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import io.github.psd2live.core.legacy.RigGenerationMigration

/** Sees how far a preview build is, from 0 to 1, where no workspace job reports it - such as installing an opened project. */
internal class WorkspacePreviewProgress(val update: (Float) -> Unit) : kotlin.coroutines.AbstractCoroutineContextElement(Key) {
    companion object Key : kotlin.coroutines.CoroutineContext.Key<WorkspacePreviewProgress>
}

/** Rebuild and ordered replay are application work; adapters only project the resulting model. */
internal class WorkspacePreviewBuilder {
    private val pipeline = PSD2LivePipeline()

    private companion object {
        /** The binding key an imported model's checkpoints carry: its rig is bound to the import's atlas. */
        const val IMPORTED_BINDING = "cmo3-import"
    }

    private suspend fun progress(start: Float, end: Float): ProgressListener {
        val context = currentCoroutineContext()
        return ProgressListener { message, fraction ->
            context.ensureActive()
            context[WorkspacePreviewProgress]?.update(fraction.toFloat().coerceIn(0f, 1f))
            if (context[WorkspaceGenerationJobExecution] != null || context[WorkspacePartitionJobExecution] != null || context[WorkspaceGenerationUpdateJobExecution] != null || context[WorkspaceWarpJobExecution] != null || context[WorkspaceWarpControlJobExecution] != null || context[WorkspaceLayerJobExecution] != null)
                context[WorkspaceJobContext]?.progress(start + (end - start) * fraction.toFloat().coerceIn(0f, 1f), message)
        }
    }

    /** Persist the generation baseline and ordered topology migration together with the settings. */
    suspend fun normalizeMeshEdits(document: WorkspaceDocument, current: RigPreviewModel): WorkspaceDocument {
        val progress = progress(0.3f, 0.6f)
        return runInterruptible(Dispatchers.Default) {
            val decoded = document.config()
            val config = if ("drawOrderOverrides" in document.settings) decoded
                else decoded.copy(drawOrderOverrides = current.config.drawOrderOverrides)
            // An imported model has no generated rig to merge onto: its groups follow the skeleton's new vertices here.
            if (config.rigEdits.importedCmo3 != null && current.config.rigEdits.skeleton != config.rigEdits.skeleton &&
                (current.rig.puppet.vertexGroups.isNotEmpty() || config.rigEdits.authoringJournal.any {
                    it["op"]?.jsonPrimitive?.contentOrNull in setOf(VertexGroupJournal.PUT, VertexGroupJournal.DELETE)
                })) {
                // Skeleton refinement changes generated vertex indices before the journal replays.
                // Replay topology first, then carry the final painted groups onto the rebuilt mesh.
                val journal = config.rigEdits.authoringJournal.filterNot {
                    it["op"]?.jsonPrimitive?.contentOrNull in setOf(VertexGroupJournal.PUT, VertexGroupJournal.DELETE)
                }
                val overlay = config.rigEdits.copy(authoringJournal = journal)
                val rebuilt = io.github.psd2live.core.RigBuildProfile.stage("normalize: build without vertex groups") {
                    pipeline.buildPreview(document.source, config.copy(rigEdits = overlay), progress) }
                // Bone binding can change a mesh's parent space. Resample in a common neutral canvas
                // space so the weights stay on the artwork instead of moving with that local frame.
                val previous = restMeshesToCanvasSpace(current.rig.puppet)
                val replacement = restMeshesToCanvasSpace(rebuilt.rig.puppet)
                val groups = previous.vertexGroups.map { group ->
                    val oldMesh = requireNotNull(previous.drawables.single { it.id == group.drawableId }.mesh)
                    val newMesh = requireNotNull(replacement.drawables.single { it.id == group.drawableId }.mesh)
                    VertexGroupJournal.resample(group, oldMesh, newMesh)
                }
                return@runInterruptible document.copy(rigEdits = overlay.copy(authoringJournal = journal + groups.map(VertexGroupJournal::encode)))
            }
            if (RigGenerationMigration.changed(current, config)) {
                // A generated model merges what the generators make now onto the user's rig and checkpoints the result
                // ([RigRegenerationCheckpoint]). Only an imported model, which has no generated rig to merge onto, records
                // the migration in its journal.
                // The merge starts from the rig at the end of [current]'s journal, so the edit may only add entries after it:
                // replaying rewritten ones on the new generation is what the merge replaces.
                if (pipeline.materializable(config) && pipeline.materializable(current.config)) {
                    require(config.rigEdits.continues(current.config.rigEdits)) {
                        "A change to how the rig is generated cannot also rewrite earlier rig edits; commit them separately"
                    }
                    RigRegenerationCheckpoint.checkpointed(pipeline, current, config, document.source,
                        { progress.update("Merging regenerated rig", 0.5) }, currentSource = true)
                        ?.let { document.copy(rigEdits = it.rigEdits) } ?: document
                } else {
                    val prepared = RigGenerationMigration.prepare(pipeline, current, config, document.source, progress)
                    document.copy(rigEdits = prepared.rigEdits, generationSource = prepared.generationSource, meshSource = prepared.meshSource)
                }
            }
            // Source replacement without a generation transition uses its specific source command.
            else if ((current.analysis.source !== document.source && current.analysis.source != document.source) ||
                current.config.layerOverrides != config.layerOverrides) document
            else {
                val changed = pipeline.meshSettingsChangedDrawableIds(current, config)
                if (changed.isEmpty()) document else {
                    val prepared = pipeline.rebuildPreview(current, config, progress).config
                    document.copy(rigEdits = prepared.rigEdits, generationSource = prepared.generationSource,
                        meshSource = prepared.meshSource)
                }
            }
        }
    }

    /**
     * [config] - which continues [current]'s edits - with [current]'s authored rig checkpointed where [current]'s journal
     * ends, before the entries [config] adds. The checkpoint stores the generated rig the last one stored (the generation
     * has not changed since, or a regeneration would have checkpointed), else [current]'s base, so a later "update
     * generation" has the generation to compare against.
     */
    private fun materialized(current: RigPreviewModel, config: io.github.psd2live.core.PipelineConfig): io.github.psd2live.core.PipelineConfig {
        val before = current.config.rigEdits
        val authored = current.authored
        // An imported model's rig is bound to the import's own atlas, which no binding key describes: its checkpoint is only
        // replayed on that base (never built from alone), and stores no generation - its base is the import, not regenerated.
        if (!pipeline.materializable(current.config)) {
            val record = io.github.psd2live.core.RigCheckpoint.encode(authored, IMPORTED_BINDING)
            val journal = ArrayList(config.rigEdits.authoringJournal).apply { add(before.authoringJournal.size, record) }
            return config.copy(rigEdits = config.rigEdits.copy(authoringJournal = journal))
        }
        val bindingKey = current.sources.bindingKey ?: pipeline.bindingKey(current.analysis.source, current.config)
        val stored = before.checkpointIndex.takeIf { it >= 0 }?.let { before.authoringJournal[it]["generated"] as? kotlinx.serialization.json.JsonObject }
        val record = if (stored != null) kotlinx.serialization.json.JsonObject(
                io.github.psd2live.core.RigCheckpoint.encode(authored, bindingKey) + ("generated" to stored))
            else io.github.psd2live.core.RigCheckpoint.encode(authored, bindingKey,
                generated = io.github.psd2live.core.AuthoredRig(current.baseRig.copy(puppet = current.baseRig.resolvedPuppet()), emptyList()))
        val journal = ArrayList(config.rigEdits.authoringJournal).apply { add(before.authoringJournal.size, record) }
        return config.copy(rigEdits = config.rigEdits.copy(authoringJournal = journal))
    }

    /**
     * Whether [config] - an edit of [current]'s document - checkpoints [current]'s authored rig before its entries
     * ([io.github.psd2live.core.RigEditOverlay.checkpointsBeforeEntries]): it continues [current]'s journal, adds no
     * checkpoint of its own, and adds entries (any continuing edit, while the journal replays legacy records).
     */
    private fun checkpoints(current: RigPreviewModel, config: io.github.psd2live.core.PipelineConfig): Boolean {
        val before = current.config.rigEdits
        if (pipeline.materializable(current.config) != pipeline.materializable(config) || !before.checkpointsBeforeEntries ||
            !config.rigEdits.continues(before)) return false
        if (config.rigEdits.checkpointIndex >= before.authoringJournal.size) return false
        return before.replaysLegacy || config.rigEdits.authoringJournal.size > before.authoringJournal.size
    }

    suspend fun build(document: WorkspaceDocument, current: RigPreviewModel? = null,
                      legacyDrawOrders: Map<String, Float> = current?.config?.drawOrderOverrides.orEmpty()): RigPreviewModel {
        val progress = progress(0.6f, 0.85f)
        return runInterruptible(Dispatchers.Default) {
            val decoded = document.config()
            // Early v1 projects stored draw orders only in their saved presentation. Keep historical
            // documents and node IDs immutable; new documents carry this generation input explicitly.
            val config = if ("drawOrderOverrides" in document.settings) decoded
                else decoded.copy(drawOrderOverrides = legacyDrawOrders)
            val materializable = pipeline.materializable(config)
            val revision = if (materializable) WorkspaceRevisions.of(document) else null
            val fast = current != null && pipeline.canFastUpdateRig(current, document.source, config)
            // A revision built before - in this process or by whoever saved the archive - builds from its authored rig,
            // unless the current model updates more cheaply: entries added to its journal act on its authored rig, any other
            // edit of a checkpointed journal replays the entries after the checkpoint, and with its base at hand an edit of
            // a journal without one replays from the replay checkpoints.
            val cheaper = fast && (current.sources.baseKnown || config.rigEdits.checkpointIndex >= 0 || config.rigEdits.extends(current.config.rigEdits))
            // A stored rig is the revision's data: on an atlas this build packs differently it moves onto the new
            // tiles rather than giving way to a replay this build may not reproduce.
            if (revision != null && !cheaper) MaterializedRigStore.lookup(revision)?.let { stored ->
                pipeline.materializedPreview(document.source, config, stored.authored, stored.bindingKey, progress, current?.atlas, rebind = true)
                    ?.let { model -> return@runInterruptible model }
            }
            // The generated rig changed under the journal: merge onto the new one and checkpoint, instead of replaying old entries on it.
            // Before the journal's own checkpoint: that holds the rig of the old generation (a skeleton committed after it would not bake).
            if (current != null && !fast && revision != null && pipeline.materializable(current.config) &&
                config.rigEdits.continues(current.config.rigEdits)) {
                RigRegenerationCheckpoint.checkpointed(pipeline, current, config, document.source, { progress.update("Merging regenerated rig", 0.5) },
                    currentSource = RigGenerationMigration.changed(current, config))
                    ?.let { checkpointed ->
                        val model = pipeline.buildPreview(document.source, checkpointed, progress, current.atlas)
                        MaterializedRigStore.remember(WorkspaceRevisions.of(document.copy(rigEdits = checkpointed.rigEdits)), checkpointed.rigEdits,
                            model.sources) { model.sources.bindingKey }
                        return@runInterruptible model
                    }
            }
            // An edit adding entries to a journal without a checkpoint, or with many entries after it, checkpoints the authored
            // rig first, so builds of later edits replay only the entries after it; a journal still replaying records only
            // older builds wrote replays them this once.
            if (current != null && checkpoints(current, config)) {
                val materialized = materialized(current, config)
                val model = (if (fast) pipeline.updateRigEdits(current, materialized) else null)
                    ?: (if (revision != null) materialized.rigEdits.authoredFromCheckpoint()?.let { (authored, bindingKey) ->
                        pipeline.materializedPreview(document.source, materialized, authored, bindingKey, progress, current.atlas, rebind = true)
                    } else null)
                    ?: pipeline.buildPreview(document.source, materialized, progress, current.atlas)
                if (revision != null) MaterializedRigStore.remember(WorkspaceRevisions.of(document.copy(rigEdits = materialized.rigEdits)),
                    materialized.rigEdits, model.sources) { pipeline.bindingKey(document.source, materialized) }
                return@runInterruptible model
            }
            // The journal's checkpoint is the authored rig itself: build from it, re-bound when the atlas moved.
            if (revision != null && !cheaper) config.rigEdits.authoredFromCheckpoint()?.let { (authored, bindingKey) ->
                pipeline.materializedPreview(document.source, config, authored, bindingKey, progress, current?.atlas, rebind = true)
                    ?.let { model ->
                        MaterializedRigStore.remember(revision, config.rigEdits, model.sources) { model.sources.bindingKey }
                        return@runInterruptible model
                    }
            }
            val model = when {
                fast -> pipeline.updateRigEdits(current, config, stored = revision?.takeIf { !config.rigEdits.extends(current.config.rigEdits) }
                    ?.let(MaterializedRigStore::lookup)?.authored)
                current != null && (current.analysis.source === document.source || current.analysis.source == document.source) &&
                    current.config.copy(parentOverrides = config.parentOverrides, rigEdits = config.rigEdits,
                        drawOrderOverrides = config.drawOrderOverrides, hairSimulationFront = config.hairSimulationFront,
                        hairSimulationBack = config.hairSimulationBack) == config -> pipeline.rebuildPreview(current, config, progress)
                // A source edit (paint, image replace, their undo) rebuilds in full; the current atlas only lends
                // the pages and preview PNG strips its pixels did not change.
                else -> pipeline.buildPreview(document.source, config, progress, current?.atlas)
            }
            if (revision != null) MaterializedRigStore.remember(revision, config.rigEdits, model.sources) {
                pipeline.bindingKey(document.source, config)
            }
            model
        }
    }
}
