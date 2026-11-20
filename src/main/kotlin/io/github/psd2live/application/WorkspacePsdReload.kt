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
            val old = previous[layer.id.raw]
            if (current == null || layer.id.raw in candidate.deletedLayerIds) {
                // A local split/deletion is retained when its external source has not changed.
                require(old != null && samePixels(old, layer)) {
                    tr("psdReload.layerUnavailable", layer.name)
                }
                return@forEach
            }
            // A layer unchanged in the external PSD keeps any local painting or texture replacement in the project.
            if (old != null && old.bounds == layer.bounds && samePixels(old, layer)) return@forEach
            if (layer.raster.width <= 0 || layer.raster.height <= 0) {
                if (hasPaintedPixels(current.raster)) {
                    val width = current.raster.width.coerceAtLeast(1)
                    val height = current.raster.height.coerceAtLeast(1)
                    val empty = org.umamo.format.art.LayerRaster(width, height, ByteArray(width * height * 4))
                    candidate = WorkspaceTextureEdits.apply(candidate, workingModel(candidate, model),
                        WorkspaceTextureEdit.ReplaceImage(layer.id.raw, WorkspaceTextureImage.Raster(empty)), checkpoint)
                }
            } else if (current.bounds != layer.bounds ||
                current.raster.width != layer.raster.width || current.raster.height != layer.raster.height ||
                !current.raster.rgba.contentEquals(layer.raster.rgba)) {
                val hasMesh = model.rig.puppet.drawables.any { it.mesh != null && model.rig.layerIdByDrawableId[it.id.raw] == layer.id.raw }
                if (current.bounds != layer.bounds || current.raster.width != layer.raster.width || current.raster.height != layer.raster.height) {
                    if (!hasMesh || layer.id.raw in WorkspaceLayoutPreview.pendingIds(candidate)) {
                        val trace = io.github.psd2live.project.WorkspaceSettingsCodec.decode(candidate.settings).meshTrace
                        val pinned = RigGenerationSource.pinned(candidate.generationSource, candidate.source, current, candidate.rigEdits, trace)
                        candidate = candidate.copy(
                            source = WorkspaceSourceArt(candidate.source.widthPx, candidate.source.heightPx,
                                candidate.source.layers.map { if (it.id != layer.id) it else (WorkspaceSourceLayer.copyOf(it, it.order) as WorkspaceSourceLayer)
                                    .copy(bounds = layer.bounds, raster = layer.raster, rect = null) }, candidate.source.groups),
                            generationSource = pinned,
                        )
                        return@forEach
                    }
                    // A new rectangle: an image replacement would stretch the pixels over the old one, so paint them over
                    // the new one and rebuild the mesh from them, migrating its forms, weights, paths and glue.
                    val working = workingModel(candidate, model)
                    val painted = WorkspaceRasterEdits.prepare(candidate, working, WorkspacePaintRaster(layer.id.raw, layer.raster,
                        rebuildMesh = true, rect = LayerCanvasRect.of(layer.bounds)), work)
                    // Painting crops to the visible pixels; keep the PSD's own raster and rectangle so the next reload matches.
                    candidate = painted.copy(source = WorkspaceSourceArt(painted.source.widthPx, painted.source.heightPx,
                        painted.source.layers.map { if (it.id != layer.id) it else (WorkspaceSourceLayer.copyOf(it, it.order) as WorkspaceSourceLayer)
                            .copy(bounds = layer.bounds, raster = layer.raster, rect = null) }, painted.source.groups))
                    return@forEach
                }
                val working = workingModel(candidate, model)
                val replaced = WorkspaceTextureEdits.apply(candidate, working,
                    WorkspaceTextureEdit.ReplaceImage(layer.id.raw, WorkspaceTextureImage.Raster(layer.raster)), checkpoint)
                val nextSource = working.analysis.source.let { source -> WorkspaceSourceArt(source.widthPx, source.heightPx,
                    source.layers.map { if (it.id == layer.id) replaced.source.layers.single { next -> next.id == layer.id } else it }, source.groups) }
                val uncovered = MeshCoverage.uncovered(working.copy(analysis = working.analysis.copy(source = nextSource)), layer.id.raw)
                if (uncovered != null && uncovered > 0f && layer.id.raw !in WorkspaceLayoutPreview.pendingIds(candidate)) {
                    // Reuse paint's topology migration: meshes, forms, weights, paths and glue stay bound.
                    val afterReplace = workingModel(replaced, model)
                    candidate = WorkspaceRasterEdits.prepare(replaced, afterReplace, WorkspacePaintRaster(layer.id.raw, layer.raster,
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
        var addedAny = false
        var orderChanged = false
        incoming.layers.forEach { layer ->
            checkpoint()
            val current = layers.singleOrNull { it.id == layer.id }
            val isShown = shown(layer, incoming.groups)
            if (layer.id.raw !in previous && current == null) {
                layers = layers + WorkspaceSourceLayer.copyOf(layer, layer.order)
                pending += layer.id.raw
                visibility = visibility + (layer.id.raw to isShown)
                addedAny = true
            } else if (current != null && layer.id.raw !in candidate.deletedLayerIds) {
                if (current.order != layer.order) orderChanged = true
                if (current.visible != layer.visible || current.groupPath != layer.groupPath || current.order != layer.order) {
                    layers = layers.map { if (it.id != layer.id) it else
                        (WorkspaceSourceLayer.copyOf(it, layer.order) as WorkspaceSourceLayer).copy(visible = layer.visible, groupPath = layer.groupPath) }
                }
                if (shown(current, candidate.source.groups) != isShown) visibility = visibility + (layer.id.raw to isShown)
            }
            // A formerly empty/hidden source layer needs its first mesh even if its visibility did not change.
            if (isShown && current != null && layer.id.raw !in candidate.deletedLayerIds &&
                model.rig.puppet.drawables.none { it.mesh != null && model.rig.layerIdByDrawableId[it.id.raw] == layer.id.raw } &&
                hasPaintedPixels(layer.raster)) pending += layer.id.raw
        }
        if (addedAny || orderChanged) {
            layers = syncLayerOrder(layers, incoming, candidate.rigEdits)
        }
        if (layers !== candidate.source.layers || groups != candidate.source.groups || visibility != candidate.layerVisibility ||
            pending != candidate.layout.generation.pendingLayerIds) {
            candidate = candidate.copy(source = WorkspaceSourceArt(candidate.source.widthPx, candidate.source.heightPx, layers, groups),
                layerVisibility = visibility, layout = candidate.layout.copy(generation = candidate.layout.generation.copy(pendingLayerIds = pending)))
        }
        // Added layers change the generation (pending layers). Its merge would be checkpointed where the old journal ends,
        // before this reload's mesh migrations, from the meshes they rebuild: they would then replay off their baseline.
        // Checkpoint it after them, as a reload of the changed layers followed by one of the added layers would.
        if (edited.rigEdits.authoringJournal.size > document.rigEdits.authoringJournal.size &&
            pending != edited.layout.generation.pendingLayerIds) {
            val config = candidate.config()
            val migrated = pipeline.buildPreview(edited.source, edited.config(), ProgressListener { _, _ -> checkpoint() })
            if (RigGenerationChange.changed(migrated, config)) RigRegenerationCheckpoint.checkpointed(pipeline, migrated, config,
                candidate.source, checkpoint, currentSource = true)?.let { candidate = candidate.copy(rigEdits = it.rigEdits) }
        }
        return WorkspaceMaterialLinks.reconcile(document, candidate)
    }

    private fun samePixels(a: org.umamo.format.art.SourceLayer, b: org.umamo.format.art.SourceLayer) =
        a.raster.width == b.raster.width && a.raster.height == b.raster.height && a.raster.rgba.contentEquals(b.raster.rgba)

    private fun hasPaintedPixels(raster: org.umamo.format.art.LayerRaster) =
        (3 until raster.rgba.size step 4).any { raster.rgba[it].toInt() != 0 }

    private fun workingModel(document: WorkspaceDocument, base: RigPreviewModel): RigPreviewModel {
        if (document.source === base.analysis.source && document.rigEdits === base.config.rigEdits &&
            document.generationSource === base.config.generationSource && document.meshSource === base.config.meshSource) return base
        val byId = document.source.layers.associateBy { it.id }
        val layers = base.analysis.layers.map { cl ->
            val next = byId[cl.source.id] ?: return@map cl
            if (next === cl.source) cl else cl.copy(source = CanvasDensity.canvasLayer(next))
        }
        val addedJournal = document.rigEdits.authoringJournal.drop(base.config.rigEdits.authoringJournal.size)
        val puppet = addedJournal.fold(base.rig.puppet) { acc, cmd -> RigAuthoringJournal.apply(acc, cmd) }
        return base.copy(
            analysis = base.analysis.copy(source = document.source, layers = layers),
            rig = if (puppet === base.rig.puppet) base.rig else base.rig.copy(puppet = puppet),
            config = document.config(),
        )
    }

    private fun syncLayerOrder(
        layers: List<org.umamo.format.art.SourceLayer>,
        incoming: SourceArt,
        overlay: RigEditOverlay,
    ): List<org.umamo.format.art.SourceLayer> {
        val byId = layers.associateBy { it.id.raw }
        val incomingIds = incoming.layers.mapTo(HashSet()) { it.id.raw }
        if (layers.all { it.id.raw in incomingIds }) {
            return incoming.layers.mapNotNull { byId[it.id.raw] }
        }
        val replacements = ArtPrimitiveJournal.replacementLayers(overlay)
        fun expand(id: String): List<org.umamo.format.art.SourceLayer> {
            byId[id]?.let { return listOf(it) }
            return replacements[id]?.flatMap(::expand).orEmpty()
        }
        val placed = LinkedHashSet<String>()
        val ordered = mutableListOf<org.umamo.format.art.SourceLayer>()
        for (layer in incoming.layers) {
            for (item in expand(layer.id.raw)) {
                if (placed.add(item.id.raw)) ordered += item
            }
        }
        for (layer in layers) {
            if (placed.add(layer.id.raw)) ordered += layer
        }
        return ordered.mapIndexed { index, layer ->
            val nextOrder = ordered.size - 1 - index
            if (layer.order == nextOrder) layer else WorkspaceSourceLayer.copyOf(layer, nextOrder)
        }
    }

    private fun shown(layer: org.umamo.format.art.SourceLayer, groups: List<org.umamo.format.art.SourceGroup>) = layer.visible &&
        groups.none { !it.visible && (layer.groupPath == it.path || layer.groupPath.startsWith(it.path + "/")) }
}
