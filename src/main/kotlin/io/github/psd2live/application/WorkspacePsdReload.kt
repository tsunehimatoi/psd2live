package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.i18n.tr
import io.github.psd2live.project.*
import org.umamo.format.art.SourceArt

/** Refresh external art without replacing the project or regenerating unaffected authored geometry. */
internal object WorkspacePsdReload {
    private val pipeline = PSD2LivePipeline()

    fun prepare(document: WorkspaceDocument, model: RigPreviewModel, original: SourceArt, incoming: SourceArt,
                checkpoint: () -> Unit = {}): WorkspaceDocument {
        require(document.rigEdits.importedCmo3 == null) { tr("psdReload.sourceRequired") }
        require(original.widthPx == incoming.widthPx && original.heightPx == incoming.heightPx &&
            document.source.widthPx == original.widthPx && document.source.heightPx == original.heightPx) { tr("psdReload.canvasChanged") }
        val previous = original.layers.associateBy { it.id.raw }
        val refreshed = incoming.layers.associateBy { it.id.raw }
        // Folders are organisation only: they may be added, removed or renamed, and layers may move between them.
        require(previous.size == original.layers.size && refreshed.size == incoming.layers.size &&
            refreshed.keys.containsAll(previous.keys)) { tr("psdReload.layersChanged") }
        // Validate every layer before preparing any replacement, including hidden and empty layers.
        incoming.layers.forEach { next ->
            checkpoint()
            val old = previous[next.id.raw] ?: document.source.layers.singleOrNull { it.id == next.id } ?: return@forEach
            // Its rectangle may change (art that grows past its old edge); the layer itself must stay the same one.
            require(old.name == next.name &&
                old.opacity == next.opacity && old.blend == next.blend && old.clipped == next.clipped &&
                old.channelMask == next.channelMask && old.kind == next.kind) { tr("psdReload.layersChanged") }
        }
        var candidate = document
        val work = object : WorkspaceRasterWork {
            override fun checkpoint() = checkpoint.invoke()
            override fun progress(fraction: Float, message: String) = checkpoint()
        }
        incoming.layers.forEach { layer ->
            checkpoint()
            val current = candidate.source.layers.singleOrNull { it.id == layer.id }
            // Add external layers after existing-layer edits, so their Layout preview never enters a mesh migration.
            if (layer.id.raw !in previous && current == null) return@forEach
            if (current == null || layer.id.raw in candidate.deletedLayerIds) {
                // A local split/deletion is retained when its external source has not changed.
                require(previous[layer.id.raw]?.raster?.rgba?.contentEquals(layer.raster.rgba) == true) {
                    tr("psdReload.layerUnavailable", layer.name)
                }
            } else if (layer.raster.width > 0 && layer.raster.height > 0 && (current.bounds != layer.bounds ||
                current.raster.width != layer.raster.width || current.raster.height != layer.raster.height ||
                    !current.raster.rgba.contentEquals(layer.raster.rgba))) {
                if (current.bounds != layer.bounds || current.raster.width != layer.raster.width || current.raster.height != layer.raster.height) {
                    // A new rectangle: an image replacement would stretch the pixels over the old one, so paint them over
                    // the new one and rebuild the mesh from them, migrating its forms, weights, paths and glue.
                    val working = pipeline.buildPreview(candidate.source, candidate.config(), ProgressListener { _, _ -> checkpoint() })
                    val painted = WorkspaceRasterEdits.prepare(candidate, working, WorkspacePaintRaster(layer.id.raw, layer.raster,
                        rebuildMesh = true, rect = LayerCanvasRect.of(layer.bounds)), work)
                    // Painting crops to the visible pixels; keep the PSD's own raster and rectangle so the next reload matches.
                    candidate = painted.copy(source = WorkspaceSourceArt(painted.source.widthPx, painted.source.heightPx,
                        painted.source.layers.map { if (it.id != layer.id) it else (WorkspaceSourceLayer.copyOf(it, it.order) as WorkspaceSourceLayer)
                            .copy(bounds = layer.bounds, raster = layer.raster, rect = null) }, painted.source.groups))
                    return@forEach
                }
                val replaced = WorkspaceTextureEdits.apply(candidate, model,
                    WorkspaceTextureEdit.ReplaceImage(layer.id.raw, WorkspaceTextureImage.Raster(layer.raster)), checkpoint)
                val nextSource = model.analysis.source.let { source -> WorkspaceSourceArt(source.widthPx, source.heightPx,
                    source.layers.map { if (it.id == layer.id) replaced.source.layers.single { next -> next.id == layer.id } else it }, source.groups) }
                val uncovered = MeshCoverage.uncovered(model.copy(analysis = model.analysis.copy(source = nextSource)), layer.id.raw)
                if (uncovered != null && uncovered > 0f && layer.id.raw !in WorkspaceLayoutPreview.pendingIds(candidate)) {
                    // Reuse paint's topology migration: meshes, forms, weights, paths and glue stay bound.
                    val working = pipeline.buildPreview(replaced.source, replaced.config(), ProgressListener { _, _ -> checkpoint() })
                    candidate = WorkspaceRasterEdits.prepare(replaced, working, WorkspacePaintRaster(layer.id.raw, layer.raster,
                        rebuildMesh = true, preserveSourceRaster = true, rect = current.canvasRect()), work)
                } else candidate = replaced
            }
        }
        val edited = candidate
        val pending = candidate.layout.generation.pendingLayerIds.toMutableSet()
        var layers = candidate.source.layers
        var visibility = candidate.layerVisibility
        // The PSD's folders replace the old ones; folders added inside psd2live are kept. A folder's visibility hides its
        // layers, as Photoshop shows them; the rig only knows layer visibility.
        val external = original.groups.mapTo(HashSet()) { it.path } + incoming.groups.map { it.path }
        val groups = incoming.groups.map { WorkspaceSourceGroup(it.path, it.name, it.visible, it.opacity, it.clipped, it.blend, it.passThrough) } +
            candidate.source.groups.filter { it.path !in external }
        incoming.layers.forEach { layer ->
            checkpoint()
            val current = layers.singleOrNull { it.id == layer.id }
            val isShown = shown(layer, incoming.groups)
            if (layer.id.raw !in previous && current == null) {
                layers = layers + WorkspaceSourceLayer.copyOf(layer, layer.order)
                pending += layer.id.raw
                visibility = visibility + (layer.id.raw to isShown)
            } else if (current != null && layer.id.raw !in candidate.deletedLayerIds) {
                if (current.visible != layer.visible || current.groupPath != layer.groupPath) layers = layers.map { if (it.id != layer.id) it else
                    (WorkspaceSourceLayer.copyOf(it, it.order) as WorkspaceSourceLayer).copy(visible = layer.visible, groupPath = layer.groupPath) }
                if (shown(current, candidate.source.groups) != isShown) visibility = visibility + (layer.id.raw to isShown)
            }
            // A formerly empty/hidden source layer needs its first mesh even if its visibility did not change.
            if (isShown && current != null && layer.id.raw !in candidate.deletedLayerIds &&
                model.rig.puppet.drawables.none { it.mesh != null && model.rig.layerIdByDrawableId[it.id.raw] == layer.id.raw } &&
                (3 until layer.raster.rgba.size step 4).any { layer.raster.rgba[it].toInt() != 0 }) pending += layer.id.raw
        }
        if (layers !== candidate.source.layers || groups != candidate.source.groups || visibility != candidate.layerVisibility ||
            pending != candidate.layout.generation.pendingLayerIds) {
            candidate = candidate.copy(source = WorkspaceSourceArt(candidate.source.widthPx, candidate.source.heightPx, layers, groups),
                layerVisibility = visibility, layout = candidate.layout.copy(generation = candidate.layout.generation.copy(pendingLayerIds = pending)))
        }
        // Added layers change the generation (pending layers). Its merge would be checkpointed where the old journal ends,
        // before this reload's mesh migrations, from the meshes they rebuild: they would then replay off their baseline.
        // Checkpoint it after them, as a reload of the changed layers followed by one of the added layers would.
        if (edited.rigEdits.authoringJournal.size > document.rigEdits.authoringJournal.size) {
            val config = candidate.config()
            val migrated = pipeline.buildPreview(edited.source, edited.config(), ProgressListener { _, _ -> checkpoint() })
            if (RigGenerationChange.changed(migrated, config)) RigRegenerationCheckpoint.checkpointed(pipeline, migrated, config,
                candidate.source, checkpoint, currentSource = true)?.let { candidate = candidate.copy(rigEdits = it.rigEdits) }
        }
        return WorkspaceMaterialLinks.reconcile(document, candidate)
    }

    private fun shown(layer: org.umamo.format.art.SourceLayer, groups: List<org.umamo.format.art.SourceGroup>) = layer.visible &&
        groups.none { !it.visible && (layer.groupPath == it.path || layer.groupPath.startsWith(it.path + "/")) }
}
