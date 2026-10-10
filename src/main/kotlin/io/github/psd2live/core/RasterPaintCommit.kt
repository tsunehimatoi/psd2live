package io.github.psd2live.core

import io.github.psd2live.project.LayerCanvasRect
import io.github.psd2live.project.WorkspaceSourceArt
import io.github.psd2live.project.WorkspaceSourceLayer
import io.github.psd2live.project.storedCanvasRect
import io.github.psd2live.project.transform
import kotlinx.serialization.json.*
import org.umamo.edit.withDrawablesDeleted
import org.umamo.format.art.LayerBounds
import org.umamo.format.art.LayerRaster
import org.umamo.format.art.SourceLayer
import org.umamo.runtime.model.*
import java.awt.image.BufferedImage
import kotlin.math.abs

/**
 * The painted layer's box in canvas pixels, as the float box the rig math works in. The two are
 * easy to confuse: a [Bounds] holds edges, a [LayerBounds] holds a width and a height.
 */
private fun LayerBounds.toBounds(): Bounds =
	Bounds(left.toFloat(), top.toFloat(), (left + width).toFloat(), (top + height).toFloat())

/**
 * Re-addresses a mesh's texture coordinates from one slice of the atlas to another, through canvas
 * units: that round trip keeps a drawable on the same pixels after its layer was re-cropped.
 */
private fun remapUvs(mesh: DrawableMesh, from: LayerTexture, to: LayerTexture): FloatArray = from.remap(mesh.uvs, to)

/** Shared raster/atlas/mesh preparation. Adapters own gestures, prompts and durable submission. */
internal object RasterPaintCommit {
    /** A painted layer's new pixels as a commit stores them, and the source holding them. */
    class Painted(val bounds: LayerBounds, val rect: LayerCanvasRect?, val raster: LayerRaster, val source: WorkspaceSourceArt)

    /** [image] (the whole canvas, one pixel per canvas unit) as painted pixels, cropped to what it holds. */
    fun canvasPixels(image: BufferedImage, checkpoint: () -> Unit = {}): PaintPixels {
        val cropped = PaintSpace.canvas(image.width, image.height).crop(image, checkpoint = checkpoint)
        return PaintPixels(cropped.raster, cropped.rect ?: LayerCanvasRect.of(cropped.bounds))
    }

    /** Canvas-image form of [paintedSource]. */
    fun paintedSource(currentPreview: RigPreviewModel, layerId: String, image: BufferedImage,
                      preserveSourceRaster: Boolean = false, checkpoint: () -> Unit = {}): Painted {
        requireCanvas(currentPreview, image)
        return paintedSource(currentPreview, layerId, canvasPixels(image, checkpoint), preserveSourceRaster, checkpoint)
    }

    private fun requireCanvas(preview: RigPreviewModel, image: BufferedImage) =
        require(image.width == preview.analysis.source.widthPx && image.height == preview.analysis.source.heightPx) {
            "Paint image dimensions must match the source canvas"
        }

    /**
     * [pixels] cropped into [layerId]'s new pixels at their own density, and the source with them in place: all a
     * repaint that keeps every mesh commits. Every other layer keeps its own raster.
     */
    fun paintedSource(currentPreview: RigPreviewModel, layerId: String, pixels: PaintPixels,
                      preserveSourceRaster: Boolean = false, checkpoint: () -> Unit = {}): Painted {
        checkpoint()
        require(sourceLayerFor(currentPreview, currentPreview.analysis, layerId) != null) { "Paint layer not found: $layerId" }
        val currentAnalysis = currentPreview.analysis
        val painted = crop(currentPreview, layerId, pixels, preserveSourceRaster, checkpoint)
        val targetClassified = classifiedLayerFor(currentPreview, currentAnalysis, layerId)
        val targetSourceLayerId = targetClassified?.source?.id?.raw ?: layerId.substringBefore(':').substringBeforeLast('-')
        val updatedSrcLayers = currentAnalysis.source.layers.map { sl ->
            if (sl.id.raw == targetSourceLayerId || sl.id.raw == layerId || sl.id.raw == targetClassified?.source?.id?.raw) {
                withPixels(sl, painted)
            } else {
                if (sl is WorkspaceSourceLayer) sl else WorkspaceSourceLayer.copyOf(sl, sl.order)
            }
        }
        return Painted(painted.bounds, painted.rect, painted.raster, WorkspaceSourceArt(
            widthPx = currentAnalysis.source.widthPx,
            heightPx = currentAnalysis.source.heightPx,
            layers = updatedSrcLayers,
            groups = currentAnalysis.source.groups,
        ))
    }

    /** [layer] (its texture behind a canvas-resolution view) holding [painted]'s pixels and placement. */
    private fun withPixels(layer: SourceLayer, painted: PaintedLayer): WorkspaceSourceLayer {
        val texture = layer.textureLayer
        val base = texture as? WorkspaceSourceLayer ?: WorkspaceSourceLayer.copyOf(texture, texture.order) as WorkspaceSourceLayer
        return base.copy(bounds = painted.bounds, raster = painted.raster, rect = painted.rect)
    }

    private fun crop(currentPreview: RigPreviewModel, layerId: String, pixels: PaintPixels, preserveSourceRaster: Boolean,
                     checkpoint: () -> Unit): PaintedLayer {
        val currentAnalysis = currentPreview.analysis
        val docW = currentAnalysis.source.widthPx
        val docH = currentAnalysis.source.heightPx
        // A hierarchy rebuild changes only the mesh. Keep the exact saved pixels and bounds instead
        // of running the paint-commit crop step over an untouched raster.
        val existingLayer = sourceLayerFor(currentPreview, currentAnalysis, layerId)?.textureLayer
        return if (!preserveSourceRaster && DepthSplit.isFrontLayer(currentPreview, layerId) && existingLayer != null) {
            // Erasing changes alpha only. Cropping would let vertices outside the smaller tile
            // sample neighbouring art, and rebuilding would discard the original rig and glue.
            PaintedLayer(existingLayer.bounds, existingLayer.storedCanvasRect,
                PaintSpace.resampleInto(existingLayer, pixels, docW, docH, checkpoint))
        } else if (preserveSourceRaster && existingLayer != null) {
            PaintedLayer(existingLayer.bounds, existingLayer.storedCanvasRect, existingLayer.raster)
        } else {
            PaintSpace.crop(pixels, docW, docH, checkpoint)
        }
    }

    /** Canvas-image form of [prepare]. */
    fun prepare(pipeline: PSD2LivePipeline, currentPreview: RigPreviewModel, layerId: String,
                image: BufferedImage, rebuildMesh: Boolean, preserveSourceRaster: Boolean = false,
                checkpoint: () -> Unit = {}, progress: ProgressListener = ProgressListener { _, _ -> },
                runtimeBundle: Boolean = true): RigPreviewModel {
        requireCanvas(currentPreview, image)
        return prepare(pipeline, currentPreview, layerId, canvasPixels(image, checkpoint), rebuildMesh, preserveSourceRaster,
            checkpoint, progress, runtimeBundle)
    }

    fun prepare(pipeline: PSD2LivePipeline, currentPreview: RigPreviewModel, layerId: String,
                pixels: PaintPixels, rebuildMesh: Boolean, preserveSourceRaster: Boolean = false,
                checkpoint: () -> Unit = {}, progress: ProgressListener = ProgressListener { _, _ -> },
                runtimeBundle: Boolean = true): RigPreviewModel {
        progress.update("Cropping painted source", 0.0)
        val painted = paintedSource(currentPreview, layerId, pixels, preserveSourceRaster, checkpoint)
        val newBounds = painted.bounds
        val newRaster = painted.raster
        val newPixels = PaintedLayer(painted.bounds, painted.rect, painted.raster)
        val updatedSourceArt = painted.source
        val rebuild = rebuildMesh && !DepthSplit.isFrontLayer(currentPreview, layerId)
        // A layer moved as a whole shows its frame elsewhere on the canvas: the rebuilt mesh goes where it shows.
        val shownAs = sourceLayerFor(currentPreview, currentPreview.analysis, layerId)?.transform ?: io.github.psd2live.project.LayerTransform.IDENTITY
        val currentAnalysis = currentPreview.analysis
        // The frames the live rig was built on. Rebuilt from the previous analysis on purpose: the
        // commit preserves every deformer, so a mesh rebuilt against frames moved by the new paint
        // would no longer line up with the parent deformer it hangs under.
        val geometryAnalysis = if (currentPreview.config.generationSource == null) currentAnalysis
            else RigGenerationSource.prepare(currentAnalysis, currentPreview.config).geometry
        // Only a rebuilt or newly created mesh needs the frames; a plain repaint keeps every mesh.
        val rigContext by lazy { RigBuilder.rigContext(geometryAnalysis, currentPreview.config, pipeline.meshCache) }

        // 2. Identify target Drawable, ClassifiedLayer, and SourceLayer
        val targetLid = layerId
        val targetDrawable = currentPreview.rig.puppet.drawables.firstOrNull {
            it.id.raw == targetLid || currentPreview.rig.layerIdByDrawableId[it.id.raw] == targetLid
        }
        val targetClassified = classifiedLayerFor(currentPreview, currentAnalysis, targetLid)
        val oldLayer = sourceLayerFor(currentPreview, currentAnalysis, targetLid)

        // 3. Update Classified Layers

        val updatedClassifiedLayers = currentAnalysis.layers.map { cl ->
            checkpoint()
            if (cl.source.id.raw == (targetClassified?.source?.id?.raw ?: targetLid)) {
                // Analysis reads a dense layer at canvas resolution; the view keeps the painted raster as its texture.
                val updatedSource = CanvasDensity.canvasLayer(withPixels(cl.source, newPixels))
                val updatedFloatBounds = newBounds.toBounds()
                cl.copy(
                    source = updatedSource,
                    bounds = updatedFloatBounds,
                    opaquePixels = newBounds.width * newBounds.height,
                    centroidX = newBounds.left + newBounds.width * 0.5f,
                    centroidY = newBounds.top + newBounds.height * 0.5f,
                )
            } else cl
        }

        val updatedAnalysis = currentAnalysis.copy(
            source = updatedSourceArt,
            layers = updatedClassifiedLayers,
        )

        // 4. Repack texture atlas with updated layer raster
        val refreshedAnalysis = MouthLipLayers.prepare(updatedAnalysis, currentPreview.config)
        // The ribbons are generated from the mouth layer, so a repaint would normally regenerate them
        // too. Keeping the existing mesh means keeping the ribbons' own pixels as well: a regenerated
        // ribbon follows a contour the kept mesh no longer has, and its coordinates would fall outside
        // the slice it was packed into.
        val effectiveAnalysis = if (rebuild) refreshedAnalysis else refreshedAnalysis.copy(
            layers = refreshedAnalysis.layers.map { layer ->
                if (layer.source is MouthLipLayer) {
                    currentAnalysis.layers.firstOrNull { it.source.id.raw == layer.source.id.raw } ?: layer
                } else {
                    layer
                }
            },
        )
        val newAtlas = AtlasLayout.pack(
            effectiveAnalysis.layers,
            currentPreview.config,
            ProgressListener { stage, fraction -> checkpoint(); progress.update(stage, 0.15 + 0.35 * fraction) },
            currentPreview.atlas,
        )
        progress.update("Preparing painted mesh", 0.55)
        val oldAtlas = currentPreview.atlas
        val newLayer = sourceLayerFor(currentPreview, effectiveAnalysis, targetLid)

        fun findPlacement(atlas: PackedAtlas, drawableId: String, layerId: String?): io.github.psd2live.core.AtlasPlacement? {
            if (layerId != null && atlas.placementByLayerId.containsKey(layerId)) {
                return atlas.placementByLayerId[layerId]
            }
            if (atlas.placementByLayerId.containsKey(drawableId)) {
                return atlas.placementByLayerId[drawableId]
            }
            val mappedId = currentPreview.rig.layerIdByDrawableId[drawableId]
            if (mappedId != null && atlas.placementByLayerId.containsKey(mappedId)) {
                return atlas.placementByLayerId[mappedId]
            }
            val baseId = (layerId ?: drawableId).substringBefore(':').substringBeforeLast('-')
            if (atlas.placementByLayerId.containsKey(baseId)) {
                return atlas.placementByLayerId[baseId]
            }
            return null
        }

        /** One layer's slice of [atlas], or null when it holds none: its raster over its canvas rectangle. An
         *  unknown [layer] reads as the canvas origin, which leaves the texture coordinates translated but unscaled. */
        fun sliceOf(atlas: PackedAtlas, drawableId: String, layerId: String, layer: SourceLayer?): LayerTexture? {
            val placement = findPlacement(atlas, drawableId, layerId) ?: return null
            val page = atlas.pages.getOrNull(placement.page)
            val space = layer?.textureLayer?.let(LayerSpace::of) ?: LayerSpace(0f, 0f, 0f, 0f, 0, 0)
            return LayerTexture.packed(space, placement, page?.image?.width ?: placement.width, page?.image?.height ?: placement.height)
        }

        /** The mesh a rebuild replaces, described so the frame its parent deformer expects can be
         *  recovered from the geometry itself - the only source left for an imported or hand-made rig. */
        fun replacedMesh(mesh: DrawableMesh, atlas: PackedAtlas, drawableId: String, layerId: String, layer: SourceLayer?): RigBuilder.ReplacedMesh {
            return RigBuilder.ReplacedMesh(mesh = mesh, texture = sliceOf(atlas, drawableId, layerId, layer))
        }

        val targetPlacement = findPlacement(newAtlas, targetDrawable?.id?.raw ?: targetLid, targetClassified?.source?.id?.raw ?: targetLid)
            ?: newAtlas.placementByLayerId[targetLid]
            ?: newAtlas.placementByLayerId.values.firstOrNull()
            ?: io.github.psd2live.core.AtlasPlacement(0, 0, 0, newBounds.width, newBounds.height)
        val targetPage = newAtlas.pages.getOrNull(targetPlacement.page)
        val targetPageWidth = targetPage?.image?.width ?: currentPreview.config.atlasSize
        val targetPageHeight = targetPage?.image?.height ?: currentPreview.config.atlasSize

        val regeneratedLips = RigBuilder.generatedMouthLips(effectiveAnalysis)
        val finalTargetClassified = effectiveAnalysis.layers.firstOrNull { it.source.id.raw == (targetClassified?.source?.id?.raw ?: targetLid) }
            ?: updatedClassifiedLayers.firstOrNull { it.source.id.raw == (targetClassified?.source?.id?.raw ?: targetLid) }
            ?: targetClassified

        // 5. Update drawables (preserving deformers, hierarchy, keyforms, and rigging)
        val updatedPageByDrawableId = currentPreview.rig.pageByDrawableId.toMutableMap()
        val updatedSourceBounds = currentPreview.rig.sourceBoundsByDrawableId.toMutableMap()

        val droppedDrawables = mutableSetOf<DrawableId>()
        val rebuiltLips = mutableMapOf<String, RigBuilder.MouthLip>()
        val rebuiltDrawableIds = mutableSetOf<DrawableId>()
        val updatedDrawables = currentPreview.rig.puppet.drawables.mapNotNull { drawable ->
            checkpoint()
            val layerId = currentPreview.rig.layerIdByDrawableId[drawable.id.raw] ?: drawable.id.raw
            val isTarget = (targetDrawable != null && drawable.id == targetDrawable.id) ||
                           drawable.id.raw == targetLid ||
                           layerId == targetLid ||
                           layerId == targetClassified?.source?.id?.raw ||
                           drawable.id.raw == targetClassified?.source?.id?.raw

            if (isTarget) {
                updatedPageByDrawableId[drawable.id.raw] = targetPlacement.page

                if (rebuild || drawable.mesh == null) {
                    rebuiltDrawableIds += drawable.id
                    // The neutral-pose reference follows the mesh, not the texture: a kept mesh keeps
                    // describing the area it covers even when new pixels were painted beyond it.
                    updatedSourceBounds[drawable.id.raw] =
                        newBounds.toBounds()

                    val targetClassifiedLayer = finalTargetClassified
                        ?: targetClassified
                        ?: error("Target classified layer not found for paint commit: $targetLid")

                    // The rig keeps its deformers, so the new mesh has to be normalized against the
                    // frames those deformers were built on - the context of the analysis the rig came
                    // from - and not against frames derived from the freshly painted bounds, which
                    // would rescale the drawable against every sibling that kept the old frames.
                    // Those frames only describe a parent the generator laid out and nobody moved since. A
                    // bone's rotation or stance warp, an edited lattice or an imported deformer is described
                    // by its own neutral transform alone, so every rebuilt vertex goes where its texel lies
                    // on the canvas, through that transform; the frame result stays only where the parent
                    // cannot place it.
                    val puppet = currentPreview.rig.puppet
                    val rebuilt = RigBuilder.rebuildDrawableMesh(
                        layer = targetClassifiedLayer,
                        context = rigContext,
                        placement = targetPlacement,
                        pageWidth = targetPageWidth,
                        pageHeight = targetPageHeight,
                        config = currentPreview.config,
                        parentId = drawable.parentDeformerId,
                        owner = drawable,
                        atlas = newAtlas,
                        generatedLips = regeneratedLips,
                        previous = drawable.mesh?.let { replacedMesh(it, oldAtlas, drawable.id.raw, layerId, oldLayer ?: newLayer) },
                    )
                    for (lip in rebuilt.mouthLips) {
                        val unplaced = requireNotNull(lip.drawable.mesh)
                        val placed = lip.canvas?.let { RasterMeshPlacement.underParent(puppet, lip.drawable, RasterMeshPlacement.shown(it, shownAs)) }
                        rebuiltLips[lip.drawable.id.raw] = if (placed == null) lip else RigBuilder.MouthLip(
                            lip.drawable.copy(mesh = DrawableMesh(placed, unplaced.uvs, unplaced.indices)),
                            lip.ownerId, lip.layer, lip.path, lip.neutralBounds, lip.canvas)
                    }

                    val placed = RasterMeshPlacement.underParent(puppet, drawable, RasterMeshPlacement.shown(rebuilt.canvas, shownAs))
                    // The generated grid moves the frame's mesh; it fits the placed one only where the frame
                    // is the parent's actual space.
                    val framed = placed == null || placed.indices.all { abs(placed[it] - rebuilt.mesh.positions[it]) <= 1e-3f }
                    drawable.copy(
                        mesh = DrawableMesh(placed ?: rebuilt.mesh.positions, rebuilt.mesh.uvs, rebuilt.mesh.indices),
                        texturePage = targetPlacement.page,
                        geometryGrid = drawable.geometryGrid ?: rebuilt.geometryGrid.takeIf { framed },
                    )
                } else {
                    val oldMesh = requireNotNull(drawable.mesh)
                    val oldSlice = sliceOf(oldAtlas, drawable.id.raw, layerId, oldLayer ?: newLayer)
                    val newSlice = sliceOf(newAtlas, drawable.id.raw, layerId, newLayer)
                    if (oldSlice != null && newSlice != null) {
                        drawable.copy(
                            mesh = DrawableMesh(oldMesh.positions, remapUvs(oldMesh, oldSlice, newSlice), oldMesh.indices),
                            texturePage = targetPlacement.page,
                        )
                    } else {
                        drawable.copy(texturePage = targetPlacement.page)
                    }
                }
            } else {
                val oldSlice = sliceOf(oldAtlas, drawable.id.raw, layerId, sourceLayerFor(currentPreview, currentAnalysis, layerId))
                val newSlice = sliceOf(newAtlas, drawable.id.raw, layerId, sourceLayerFor(currentPreview, effectiveAnalysis, layerId))
                val oldMesh = drawable.mesh
                when {
                    // The repack has no slice for this drawable any more. That is what a generated layer
                    // does when the layer it follows loses the shape it was built from - an erased mouth
                    // takes its lip ribbons with it - and keeping the drawable would leave it sampling
                    // whatever the repack happened to place at its old texture coordinates, which reads
                    // as the art tearing apart instead of disappearing.
                    oldMesh != null && oldSlice != null && newSlice == null -> {
                        droppedDrawables += drawable.id
                        null
                    }
                    oldMesh != null && oldSlice != null && newSlice != null -> {
                        updatedPageByDrawableId[drawable.id.raw] = newSlice.page
                        drawable.copy(
                            mesh = DrawableMesh(oldMesh.positions, remapUvs(oldMesh, oldSlice, newSlice), oldMesh.indices),
                            texturePage = newSlice.page,
                        )
                    }
                    else -> drawable
                }
            }
        }

        // 6. Update puppet and rig (deformers, hierarchy, parameters preserved 100%). Deleting a drawable
        // goes through the model's own delete so the org tree, clip masks, glues and the derived render
        // order all stop referring to it.
        for (id in droppedDrawables) {
            updatedPageByDrawableId.remove(id.raw)
            updatedSourceBounds.remove(id.raw)
        }
        // Ribbons are swapped in by id, whichever side of their owner they sit on, and a ribbon the rig
        // never had - a mouth painted back after its own erase - joins the part its own mouth is in.
        var drawablesAfterRepack = updatedDrawables.map { drawable ->
            val lip = rebuiltLips[drawable.id.raw]
            if (lip != null) {
                rebuiltDrawableIds += drawable.id
                updatedPageByDrawableId[drawable.id.raw] = lip.drawable.texturePage
                updatedSourceBounds[drawable.id.raw] = lip.neutralBounds
            }
            if (lip == null) drawable else lip.drawable.copy(
                drawOrder = drawable.drawOrder,
                blendMode = drawable.blendMode,
                isVisible = drawable.isVisible,
                maskedBy = drawable.maskedBy,
                geometryGrid = drawable.geometryGrid,
                channelGrids = drawable.channelGrids,
                blendShapes = drawable.blendShapes,
            )
        }
        val addedLips = rebuiltLips.values.filter { lip -> drawablesAfterRepack.none { it.id == lip.drawable.id } }
        for (lip in addedLips) {
            updatedPageByDrawableId[lip.drawable.id.raw] = lip.drawable.texturePage
            updatedSourceBounds[lip.drawable.id.raw] = lip.neutralBounds
        }
        if (addedLips.isNotEmpty()) drawablesAfterRepack = drawablesAfterRepack + addedLips.map { it.drawable }
        val partsAfterRepack = if (addedLips.isEmpty()) {
            currentPreview.rig.puppet.parts
        } else {
            val addedByOwner = addedLips.groupBy { it.ownerId }
            currentPreview.rig.puppet.parts.map { part ->
                val added = part.children.filterIsInstance<OrgChild.Drawable>()
                    .flatMap { addedByOwner[it.id].orEmpty() }
                if (added.isEmpty()) part else part.copy(children = part.children + added.map { OrgChild.Drawable(it.drawable.id) })
            }
        }
        val (repackedPuppetAtlas, repackedSources) = PuppetSourceAtlas.build(effectiveAnalysis, newAtlas,
            sourceIdRaw = if (currentPreview.config.rigEdits.importedCmo3 == null) PuppetSourceAtlas.SOURCE_ID_RAW
                else Cmo3ModelImport.PAINT_SOURCE_ID)
        val updatedPuppet = currentPreview.rig.puppet
            .let { puppet -> if (droppedDrawables.isEmpty()) puppet else puppet.withDrawablesDeleted(droppedDrawables) }
            .copy(
                drawables = drawablesAfterRepack,
                atlas = repackedPuppetAtlas,
                sources = repackedSources,
                parts = partsAfterRepack,
                deformPaths = currentPreview.rig.puppet.deformPaths
                    .filterNot { it.drawableId in droppedDrawables } +
                    addedLips.mapNotNull { it.path },
            )
            .let { puppet -> if (addedLips.isEmpty()) puppet else puppet.withDerivedRenderRoot() }
            .let { puppet ->
                var migrated = currentPreview.rig.puppet
                for (id in rebuiltDrawableIds) {
                    checkpoint()
                    val oldMesh = migrated.drawables.firstOrNull { it.id == id }?.mesh ?: continue
                    val newMesh = puppet.drawables.firstOrNull { it.id == id }?.mesh ?: continue
                    migrated = RasterMeshJournal.apply(migrated, id, RasterMeshJournal.prepare(oldMesh, newMesh))
                }
                val liveIds = puppet.drawables.mapTo(HashSet()) { it.id }
                puppet.copy(
                    drawables = puppet.drawables.map { drawable ->
                        val carried = migrated.drawables.firstOrNull { it.id == drawable.id }
                        if (drawable.id !in rebuiltDrawableIds || carried?.mesh == null) drawable else drawable.copy(
                            geometryGrid = carried.geometryGrid, blendShapes = carried.blendShapes,
                        )
                    },
                    glues = migrated.glues.filter { it.meshA in liveIds && it.meshB in liveIds },
                    deformPaths = puppet.deformPaths.map { path ->
                        if (path.drawableId !in rebuiltDrawableIds) path
                        else migrated.deformPaths.firstOrNull { it.id == path.id } ?: path
                    },
                    vertexGroups = puppet.vertexGroups.map { group ->
                        migrated.vertexGroups.firstOrNull { it.drawableId == group.drawableId && it.name == group.name } ?: group
                    },
                )
            }
        var updatedRig = currentPreview.rig.copy(
            puppet = updatedPuppet,
            pageByDrawableId = updatedPageByDrawableId,
            sourceBoundsByDrawableId = updatedSourceBounds,
            layerIdByDrawableId = currentPreview.rig.layerIdByDrawableId +
                addedLips.associate { it.drawable.id.raw to it.layer.source.id.raw },
        )

        // A transparent source layer has no drawable yet. Generate its first mesh in the old
        // rig's frames, then carry only its new objects into the otherwise preserved puppet.
        if (targetDrawable == null && finalTargetClassified != null &&
            (0 until newRaster.width * newRaster.height).any { (newRaster.rgba[it * 4 + 3].toInt() and 255) > currentPreview.config.alphaThreshold }) {
            val stable = RigBuilder.assignSplitDrawableIds(effectiveAnalysis,
                currentPreview.rig.layerIdByDrawableId.entries.associate { it.value to DrawableId(it.key) })
            val generated = RigBuilder.buildPreservingDeformers(effectiveAnalysis, newAtlas, currentPreview.config, null,
                geometryAnalysis, currentPreview.config, stable)
            checkpoint()
            val ids = generated.layerIdByDrawableId.filterValues { layer ->
                layer == finalTargetClassified.source.id.raw || effectiveAnalysis.layers.any { candidate ->
                    val lip = candidate.source as? MouthLipLayer
                    lip?.id?.raw == layer && lip.ownerId == finalTargetClassified.source.id.raw
                }
            }.keys
            val born = generated.puppet.drawables.filter { it.id.raw in ids && updatedRig.puppet.drawables.none { old -> old.id == it.id } }
            val bornIds = born.mapTo(HashSet()) { it.id }
            val children = bornIds.mapTo(HashSet()) { OrgChild.Drawable(it) }
            val current = updatedRig.puppet
            val additions = generated.puppet.parts.filter { part -> current.parts.none { it.id == part.id } }
                .map { it.copy(children = it.children.filter { child -> child in children }) }
            val parts = current.parts.map { part ->
                val added = generated.puppet.parts.firstOrNull { it.id == part.id }?.children.orEmpty().filter { it in children }
                part.copy(children = part.children + added)
            } + additions
            val axes = born.flatMap { it.geometryGrid?.axes.orEmpty() + it.channelGrids.gridsByChannel.values.flatMap { grid -> grid.axes } }
                .mapTo(HashSet()) { it.parameterId }
            val parameters = generated.puppet.parameters.filter { it.id in axes && current.parameters.none { old -> old.id == it.id } }
            updatedRig = updatedRig.copy(puppet = current.copy(drawables = current.drawables + born, parts = parts,
                rootChildren = current.rootChildren + generated.puppet.rootChildren.filter { it in children } + additions.map { OrgChild.Part(it.id) },
                parameters = current.parameters + parameters, parameterTree = current.parameterTree + parameters.map { ParameterNode.Param(it.id) },
                deformPaths = current.deformPaths + generated.puppet.deformPaths.filter { it.drawableId in bornIds }).withDerivedRenderRoot(),
                pageByDrawableId = updatedRig.pageByDrawableId + generated.pageByDrawableId.filterKeys { it in ids },
                sourceBoundsByDrawableId = updatedRig.sourceBoundsByDrawableId + generated.sourceBoundsByDrawableId.filterKeys { it in ids },
                layerIdByDrawableId = updatedRig.layerIdByDrawableId + generated.layerIdByDrawableId.filterKeys { it in ids })
        }

        progress.update("Preparing painted runtime", 0.85)
        checkpoint()
        // A caller that only reads the candidate's rig and source - the document commit, whose rebuild compiles
        // its own bundle - skips this one; the result then carries the previous bundle.
        val runtimeBundle = if (!runtimeBundle) currentPreview.runtimeBundle else pipeline.buildRuntimeBundle(
            "psd2live-preview",
            effectiveAnalysis,
            newAtlas,
            updatedRig,
            currentPreview.config,
        ).first

        val committedConfig = if (rebuild && targetDrawable != null) {
            val rebuiltMesh = updatedRig.puppet.drawables
                .firstOrNull { it.id == targetDrawable.id }?.mesh
            val meshVertexCount = rebuiltMesh?.positions?.size?.div(2)
            val baseEdits = if (preserveSourceRaster && meshVertexCount != null) {
                MeshRebuildEdits.reset(
                    currentPreview.config.rigEdits,
                    targetDrawable.id.raw,
                    targetDrawable.mesh?.vertexCount ?: 0,
                    meshVertexCount,
                )
            } else currentPreview.config.rigEdits
            val previousBasePaths = currentPreview.baseRig.puppet.deformPaths
                .filter { it.drawableId == targetDrawable.id }
            val survivingPaths = currentPreview.rig.puppet.deformPaths
                .filter { it.drawableId == targetDrawable.id }
                .mapNotNull { path -> updatedPuppet.deformPaths.firstOrNull { it.id == path.id } }
            currentPreview.config.copy(
                rigEdits = DeformPathJournal.replaceMeshPaths(
                    baseEdits,
                    targetDrawable.id.raw,
                    previousBasePaths,
                    survivingPaths,
                ).let { edits ->
                    VertexGroupJournal.replaceMeshGroups(
                        edits,
                        targetDrawable.id.raw,
                        updatedPuppet.vertexGroups.filter { it.drawableId == targetDrawable.id },
                    )
                },
            )
        } else currentPreview.config

        val finalPreview = currentPreview.copy(
            analysis = updatedAnalysis,
            atlas = newAtlas,
            rig = updatedRig,
            config = committedConfig,
            sources = PreviewRigSources.of(currentPreview.baseRig.copy(puppet = updatedPuppet, pageByDrawableId = updatedPageByDrawableId, sourceBoundsByDrawableId = updatedSourceBounds)),
            runtimeBundle = runtimeBundle,
            // The transient base is bound to the repacked atlas.
            generationAtlas = null,
        )

        checkpoint()
        progress.update("Prepared painted mesh", 1.0)
        return finalPreview
    }

    /**
     * Every id a paint target can be named by: the rig maps generated drawables back to their layer,
     * and generated mouth lips carry a suffix on top of it.
     */
    private fun paintTargetIds(preview: RigPreviewModel?, layerId: String): List<String> = buildList {
        add(layerId)
        preview?.rig?.layerIdByDrawableId?.get(layerId)?.let { add(it) }
        layerId.substringBefore(':').substringBeforeLast('-').let { if (it !in this) add(it) }
    }

    private fun classifiedLayerFor(preview: RigPreviewModel, analysis: PipelineAnalysis, layerId: String): ClassifiedLayer? =
        paintTargetIds(preview, layerId).firstNotNullOfOrNull { id ->
            analysis.layers.firstOrNull { it.source.id.raw == id }
        }

    fun sourceLayerFor(preview: RigPreviewModel?, analysis: PipelineAnalysis, layerId: String): SourceLayer? =
        paintTargetIds(preview, layerId).firstNotNullOfOrNull { id ->
            analysis.layers.firstOrNull { it.source.id.raw == id }?.source
                ?: analysis.source.layers.firstOrNull { it.id.raw == id }
        }

}
