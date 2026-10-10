package io.github.psd2live.application

import io.github.psd2live.core.PSD2LivePipeline
import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.core.ProgressListener
import io.github.psd2live.core.RigGenerationMigration
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

/** Sees how far a preview build is, from 0 to 1, where no workspace job reports it - such as installing an opened project. */
internal class WorkspacePreviewProgress(val update: (Float) -> Unit) : kotlin.coroutines.AbstractCoroutineContextElement(Key) {
    companion object Key : kotlin.coroutines.CoroutineContext.Key<WorkspacePreviewProgress>
}

/** Rebuild and ordered replay are application work; adapters only project the resulting model. */
internal class WorkspacePreviewBuilder {
    private val pipeline = PSD2LivePipeline()

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
                if (pipeline.materializable(config) && pipeline.materializable(current.config)) {
                    if (!config.rigEdits.continues(current.config.rigEdits)) document
                    else RigRegenerationCheckpoint.checkpointed(pipeline, current, config, document.source,
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
            // unless the current model updates more cheaply: entries added to its journal act on its authored rig, and
            // with its base at hand any other edit of the overlay replays from the replay checkpoints.
            val cheaper = fast && (current.sources.baseKnown || config.rigEdits.extends(current.config.rigEdits))
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
            // The journal's checkpoint is the authored rig itself: build from it, re-bound when the atlas moved.
            if (revision != null && !cheaper) config.rigEdits.authoredFromCheckpoint()?.let { (authored, bindingKey) ->
                pipeline.materializedPreview(document.source, config, authored, bindingKey, progress, current?.atlas, rebind = true)
                    ?.let { model ->
                        MaterializedRigStore.remember(revision, config.rigEdits, model.sources) { model.sources.bindingKey }
                        return@runInterruptible model
                    }
            }
            val model = when {
                fast -> pipeline.updateRigEdits(current, config)
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
