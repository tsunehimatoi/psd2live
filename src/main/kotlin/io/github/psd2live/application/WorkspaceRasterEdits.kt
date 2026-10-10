package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.serialization.json.*
import org.umamo.format.art.LayerRaster
import java.awt.image.BufferedImage

/**
 * Captured layer pixels, rather than a live paint session or a mutable preview: [raster] stretched over the canvas
 * rectangle [rect] at the layer's own density, or - when [rect] is null - a raster of the whole canvas at one pixel
 * per canvas unit.
 */
data class WorkspacePaintRaster(val layerId: String, val raster: LayerRaster,
                                val rebuildMesh: Boolean, val preserveSourceRaster: Boolean = false,
                                val rect: LayerCanvasRect? = null) {
    init {
        require(raster.width > 0 && raster.height > 0 && raster.width.toLong() * raster.height <= 16_777_216)
        require(raster.rgba.size.toLong() == raster.width.toLong() * raster.height * 4)
    }

    internal val pixels: PaintPixels get() = PaintPixels(raster, rect)

    companion object {
        /**
         * [image] of [space] cropped to its non-transparent pixels within [region] (all of it when null) at the
         * layer's own density: only the area that can hold the layer is read, never a copy of the whole canvas.
         */
        internal fun capture(layerId: String, image: BufferedImage, space: PaintSpace, region: java.awt.Rectangle?,
                             rebuildMesh: Boolean, preserveSourceRaster: Boolean = false,
                             checkpoint: () -> Unit = {}): WorkspacePaintRaster {
            val painted = space.crop(image, region, checkpoint)
            return WorkspacePaintRaster(layerId, painted.raster, rebuildMesh, preserveSourceRaster,
                painted.rect ?: LayerCanvasRect.of(painted.bounds))
        }

        /** The whole canvas-sized [image], one pixel per canvas unit. */
        fun capture(layerId: String, image: BufferedImage, rebuildMesh: Boolean, preserveSourceRaster: Boolean = false,
                    checkpoint: () -> Unit = {}): WorkspacePaintRaster {
            require(image.width.toLong() * image.height <= 16_777_216) { "Painting requires a canvas of at most 16 megapixels" }
            val rgba = ByteArray(Math.multiplyExact(Math.multiplyExact(image.width, image.height), 4))
            val row = IntArray(image.width)
            for (y in 0 until image.height) {
                checkpoint()
                image.getRGB(0, y, image.width, 1, row, 0, image.width)
                for (x in 0 until image.width) {
                    val pixel = row[x]; val index = (y * image.width + x) * 4
                    rgba[index] = (pixel ushr 16).toByte(); rgba[index + 1] = (pixel ushr 8).toByte()
                    rgba[index + 2] = pixel.toByte(); rgba[index + 3] = (pixel ushr 24).toByte()
                }
            }
            return WorkspacePaintRaster(layerId, LayerRaster(image.width, image.height, rgba), rebuildMesh, preserveSourceRaster)
        }
    }
}

/** GUI pixels and public raster gestures prepare the same durable candidate before rebuild/CAS. */
internal object WorkspaceRasterEdits {
    /** Shared so repeated paint commits reuse its content-addressed mesh cache. */
    private val pipeline = PSD2LivePipeline()
    fun paint(document: WorkspaceDocument, model: RigPreviewModel, arguments: JsonObject,
              work: WorkspaceRasterWork = WorkspaceRasterWork.Direct): WorkspaceDocument {
        val (painting, touched) = document.paintLayerImage(arguments, work)
        val region = painting.space.layerArea().let { area -> touched?.let { if (area.isEmpty) it else area.union(it) } ?: area }
        return prepare(document, model, WorkspacePaintRaster.capture(arguments.getValue("layer_id").jsonPrimitive.content,
            painting.image, painting.space, region, arguments["rebuild_mesh"]?.jsonPrimitive?.boolean ?: false,
            checkpoint = work::checkpoint), work)
    }

    const val REBUILD_MESH = "layer_mesh_rebuild"

    /**
     * Rebuilds [layerId]'s meshes from its pixels as they are now - the one way, besides a paint or image replacement
     * that asks for it, a layer's mesh is made again. Keyforms, paths, weights and glue migrate onto the new topology;
     * a layer moved as a whole keeps its place.
     */
    fun rebuildMesh(document: WorkspaceDocument, model: RigPreviewModel, layerId: String,
                    work: WorkspaceRasterWork = WorkspaceRasterWork.Direct): WorkspaceDocument {
        val id = RasterPaintCommit.sourceLayerFor(model, model.analysis, layerId)?.id?.raw
            ?: throw IllegalArgumentException("Layer not found: $layerId")
        val layer = document.source.layers.singleOrNull { it.id.raw == id && id !in document.deletedLayerIds }
            ?: throw IllegalArgumentException("Layer not found: $layerId")
        val own = PaintSpace.of(layer, document.source.widthPx, document.source.heightPx).cropLayer(layer, work::checkpoint)
        require((0 until own.raster.width * own.raster.height).any { (own.raster.rgba[it * 4 + 3].toInt() and 255) > model.config.alphaThreshold }) {
            "Layer $layerId has no visible pixels to mesh"
        }
        return prepare(document, model, WorkspacePaintRaster(layerId, own.raster, rebuildMesh = true,
            rect = own.rect ?: LayerCanvasRect.of(own.bounds)), work)
    }

    fun prepare(document: WorkspaceDocument, model: RigPreviewModel, request: WorkspacePaintRaster,
                work: WorkspaceRasterWork = WorkspaceRasterWork.Direct): WorkspaceDocument {
        work.progress(0.25f, "Preparing painted pixels")
        val canvasWidth = document.source.widthPx; val canvasHeight = document.source.heightPx
        if (request.rect == null) require(request.raster.width == canvasWidth && request.raster.height == canvasHeight) {
            "Paint image dimensions must match the source canvas"
        }
        val id = RasterPaintCommit.sourceLayerFor(model, model.analysis, request.layerId)?.id?.raw
            ?: throw IllegalArgumentException("Paint layer not found: ${request.layerId}")
        val layer = document.source.layers.singleOrNull { it.id.raw == id && id !in document.deletedLayerIds }
            ?: throw IllegalArgumentException("Paint target must be a source artwork layer: $id")
        // Both sides as an untouched paint session would capture them: cropped to their pixels, at their density.
        val painted = PaintSpace.crop(request.pixels, canvasWidth, canvasHeight, work::checkpoint)
        // A depth-split front layer keeps its raster size and reads every pixel it covers, so it is handed the
        // whole capture; anything else is handed the crop.
        val pixels = if (DepthSplit.isFrontLayer(model, id)) request.pixels
            else PaintPixels(painted.raster, painted.rect ?: LayerCanvasRect.of(painted.bounds))
        val samePixels = painted.samePixels(PaintSpace.of(layer, canvasWidth, canvasHeight).cropLayer(layer, work::checkpoint))
        val missingMesh = model.rig.puppet.drawables.none { drawable -> drawable.mesh != null && model.rig.layerIdByDrawableId[drawable.id.raw] == id }
        val visiblePixels = (0 until painted.raster.width * painted.raster.height).any {
            if (it % painted.raster.width == 0) work.checkpoint()
            (painted.raster.rgba[it * 4 + 3].toInt() and 255) > model.config.alphaThreshold
        }
        val rebuild = visiblePixels && (request.rebuildMesh || missingMesh) && !DepthSplit.isFrontLayer(model, id)
        if (samePixels && !rebuild) return document
        // The generation input with this layer's unpainted pixels: a layer added after the input was frozen
        // would otherwise generate from the painted ones, regenerating a kept mesh or the base a rebuild migrates.
        val generation = if (document.rigEdits.importedCmo3 != null) document.generationSource ?: document.source
            else RigGenerationSource.pinned(document.generationSource, document.source, layer, document.rigEdits,
                WorkspaceSettingsCodec.decode(document.settings).meshTrace)
        if (!rebuild && document.rigEdits.importedCmo3 == null) {
            // A repaint that keeps every mesh commits pixels only: no journal record, no mesh input. The new rig,
            // atlas and bundle are the rebuild's, which reads the frozen generation input below, so geometry
            // stays and only the textures follow the pixels.
            val repainted = RasterPaintCommit.paintedSource(model, id, pixels, request.preserveSourceRaster, work::checkpoint)
            work.progress(1f, "Prepared painted document")
            return document.copy(source = repainted.source, generationSource = generation)
        }
        val working = if (document.rigEdits.importedCmo3 == null) model else Cmo3ModelImport.paintingPreview(pipeline, document.source,
            document.config().copy(generationSource = document.generationSource ?: document.source))
        val prepared = RasterPaintCommit.prepare(pipeline, working, id, pixels, rebuild,
            request.preserveSourceRaster || samePixels, work::checkpoint,
            ProgressListener { stage, fraction -> work.progress(0.35f + 0.45f * fraction.toFloat(), stage) }, runtimeBundle = false)
        val beforeIds = model.rig.puppet.drawables.mapTo(HashSet()) { it.id }
        val migrations = if (!rebuild) emptyList() else prepared.rig.puppet.drawables.mapNotNull { drawable ->
            work.checkpoint()
            val previous = model.rig.puppet.drawables.singleOrNull { it.id == drawable.id }?.mesh ?: return@mapNotNull null
            val replacement = drawable.mesh ?: return@mapNotNull null
            if (previous.positions.contentEquals(replacement.positions) && previous.indices.contentEquals(replacement.indices)) null
            else RasterMeshJournal.encode(model.rig.puppet, drawable.id, replacement, prepared.rig.puppet,
                prepared.rig.sourceBoundsByDrawableId[drawable.id.raw])
        }
        val creations = prepared.rig.puppet.drawables.filter { it.id !in beforeIds }.map { work.checkpoint(); RasterMeshCreation.encode(prepared.rig, it.id) }
        // Creation needs the candidate texture inventory, with only the preceding committed objects.
        val inventory = working.rig.puppet.copy(atlas = prepared.rig.puppet.atlas, sources = prepared.rig.puppet.sources)
        val records = migrations + creations
        val journal = if (records.isEmpty()) emptyList() else RigAuthoringJournal.compile(inventory, JsonArray(records)).second
        work.progress(1f, "Prepared painted document")
        val source = if (samePixels) document.source else prepared.analysis.source
        val meshInputs = document.meshSource ?: generation
        val savedMeshInput = meshInputs.layers.singleOrNull { it.id == layer.id }
        val previousMeshInput = savedMeshInput ?: layer
        val currentMeshInput = source.layers.single { it.id == layer.id }
        val changedMeshInput = rebuild && (savedMeshInput == null || previousMeshInput.bounds != currentMeshInput.bounds ||
            previousMeshInput.storedCanvasRect != currentMeshInput.storedCanvasRect ||
            previousMeshInput.raster.width != currentMeshInput.raster.width || previousMeshInput.raster.height != currentMeshInput.raster.height ||
            !previousMeshInput.raster.rgba.contentEquals(currentMeshInput.raster.rgba))
        val meshSource = if (!rebuild) document.meshSource else WorkspaceSourceArt(source.widthPx, source.heightPx,
            meshInputs.layers.map { old -> if (old.id == layer.id) currentMeshInput else old } +
                listOfNotNull(currentMeshInput.takeIf { savedMeshInput == null }), source.groups)
        if (samePixels && journal.isEmpty() && !changedMeshInput) return document
        return document.copy(source = source, generationSource = generation,
            meshSource = meshSource, rigEdits = document.rigEdits.copy(authoringJournal = document.rigEdits.authoringJournal + journal))
    }
}
