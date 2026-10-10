package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.serialization.json.*
import org.umamo.format.FileKind
import org.umamo.format.FormatRegistry
import org.umamo.format.art.LayerBounds
import org.umamo.format.art.LayerRaster
import org.umamo.format.art.SourceArt
import org.umamo.format.art.SourceLayer
import org.umamo.format.raster.RasterCodec
import java.nio.ByteBuffer
import java.nio.file.Path
import kotlin.math.ceil
import kotlin.math.floor
import io.github.psd2live.core.legacy.RigGenerationFrames

/** How a replacement raster of another aspect ratio lies on the layer's unchanged canvas rectangle. */
enum class WorkspaceImageFit { STRETCH, CONTAIN }

/** Where a replacement image comes from. A file is read only by the single command, after its state check. */
sealed interface WorkspaceTextureImage {
    /** An absolute local PNG, WebP, TIFF or BMP file of at most 64 MiB and 16 megapixels. */
    data class File(val path: Path) : WorkspaceTextureImage
    /** Encoded PNG bytes, as `png_base64` carries them. */
    class Png(val bytes: ByteArray) : WorkspaceTextureImage
    /** Already decoded straight RGBA pixels. */
    class Raster(val raster: LayerRaster) : WorkspaceTextureImage
}

/** One texture edit. Each is a pure document candidate; single commands and atomic batches share it. */
sealed interface WorkspaceTextureEdit {
    val operation: String

    /** Moves or resizes [layerId] to [rect] (canvas units, fractional allowed); its raster is stretched over it. */
    data class SetCanvasRect(val layerId: String, val rect: LayerCanvasRect) : WorkspaceTextureEdit {
        override val operation get() = "layer_set_canvas_rect"
    }

    /**
     * Replaces [layerId]'s pixels with [image] at any resolution, keeping its canvas rectangle. The rig keeps its
     * generation input unless [rebuildMesh] regenerates the layer's mesh from the new pixels.
     */
    data class ReplaceImage(val layerId: String, val image: WorkspaceTextureImage, val fit: WorkspaceImageFit = WorkspaceImageFit.STRETCH,
                            val rebuildMesh: Boolean = false) : WorkspaceTextureEdit {
        override val operation get() = "layer_replace_image"
    }

    /** Sets the texture density (texture pixels per raster pixel; null resets to 1) and, when given, the lock. */
    data class SetPixelDensity(val layerIds: List<String>, val density: Float?, val lock: Boolean? = null) : WorkspaceTextureEdit {
        override val operation get() = "layer_set_pixel_density"
    }

    /** Pins [layerId]'s tile at [pin] on the atlas, or releases it to the automatic layout when null. */
    data class SetTile(val layerId: String, val pin: TexturePin?) : WorkspaceTextureEdit {
        override val operation get() = "atlas_set_tile"
    }

    /**
     * Changes the atlas budget; omitted values keep the current effective budget's. [auto] true arranges the
     * atlas on every build again (dropping the stored arrangement); false keeps the layout it has now.
     */
    data class SetBudget(val pageSize: Int? = null, val maxPages: Int? = null, val padding: Int? = null, val auto: Boolean? = null) : WorkspaceTextureEdit {
        override val operation get() = "atlas_set_budget"
    }

    /**
     * Arranges the atlas once and keeps the result: by the meshes' footprints when [byMesh], else by tile
     * rectangles; only [layerIds] move when given. Overrides of layers that no longer exist are dropped.
     */
    data class Pack(val byMesh: Boolean = true, val layerIds: List<String>? = null) : WorkspaceTextureEdit {
        override val operation get() = "atlas_pack"
    }
}

/** A committed texture edit and the atlas the new state packs into. */
data class WorkspaceTextureResult(
    val mutation: WorkspaceMutationResult,
    val layerIds: List<String>,
    val atlasFit: Float,
)

/** One layer's atlas tile; positions and sizes are texture pixels. */
data class WorkspaceAtlasTile(
    val layerId: String,
    val page: Int,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    /** Texture pixels per raster pixel on each axis: density times fit. */
    val scaleX: Float,
    val scaleY: Float,
    /** The layer's density override, 1 by default. */
    val density: Float,
    val locked: Boolean,
    /** A pin of the automatic layout holds the tile in place. */
    val pinned: Boolean,
    /** The stored arrangement placed the tile by its meshes' footprint, which may reach into other tiles' rectangles. */
    val shaped: Boolean = false,
    /** Degrees the tile turns about its centre; [x]..[x] + [width] is its upright rectangle. */
    val rotation: Float = 0f,
)

data class WorkspaceAtlasPage(val index: Int, val width: Int, val height: Int, val tileCount: Int, val occupancy: Float)

data class WorkspaceAtlasSnapshot(
    val budget: AtlasBudget,
    val pages: List<WorkspaceAtlasPage>,
    /** The common scale of every unlocked tile; 1 unless the budget forced textures smaller. */
    val fit: Float,
    val notices: List<String>,
    val tiles: List<WorkspaceAtlasTile>,
    /** Whether the atlas arranges itself on every build; false when it keeps a stored arrangement. */
    val auto: Boolean = true,
)

/** One layer's committed texture: where it sits on the canvas, its pixels, its override and its tile. */
data class WorkspaceLayerTexture(
    val layerId: String,
    val name: String,
    val canvasRect: LayerCanvasRect,
    val bounds: LayerBounds,
    val rasterWidth: Int,
    val rasterHeight: Int,
    /** Raster pixels per canvas unit on each axis. */
    val nativeDensityX: Float,
    val nativeDensityY: Float,
    val override: TextureOverride,
    val deleted: Boolean,
    /** Null when the layer packs no tile (deleted or fully transparent). */
    val tile: WorkspaceAtlasTile?,
    val fit: Float,
)

/** A read of one committed version; later commits and reloads cannot mix into it. */
class WorkspaceTextureView internal constructor(private val capture: WorkspaceCapture<RigPreviewModel>) {
    val projectId: String get() = capture.projectId
    val state: String get() = capture.state
    val revision: String get() = capture.revision
    val historyNodeId: String get() = capture.historyHead

    private val model get() = capture.model
    private val document get() = capture.document

    private fun tile(id: String): WorkspaceAtlasTile? {
        val placement = model.atlas.placementByLayerId[id] ?: return null
        val override = document.textureOverrides[id] ?: TextureOverride()
        return WorkspaceAtlasTile(id, placement.page, placement.x, placement.y, placement.width, placement.height,
            placement.scaleX, placement.scaleY, override.density ?: 1f, override.lock, override.pin != null, id in model.atlas.footprints,
            placement.rotation)
    }

    fun layer(layerId: String): WorkspaceLayerTexture {
        WorkspaceArtPrimitives.requireCurrent(document.rigEdits, layerId)
        val layer = document.source.layers.firstOrNull { it.id.raw == layerId }
            ?: model.analysis.layers.firstOrNull { it.source.id.raw == layerId }?.source?.textureLayer
            ?: throw IllegalArgumentException("Layer not found: $layerId")
        // Where the canvas shows the layer, after it was moved or scaled as a whole; its pixels per canvas unit follow.
        val shown = layer.displayedRect() ?: layer.canvasRect()
        val space = LayerSpace(shown.left, shown.top, shown.width, shown.height, layer.raster.width, layer.raster.height)
        return WorkspaceLayerTexture(layerId, layer.name, shown, layer.bounds, layer.raster.width, layer.raster.height,
            space.scaleX, space.scaleY, document.textureOverrides[layerId] ?: TextureOverride(), layerId in document.deletedLayerIds,
            tile(layerId), model.atlas.fit)
    }

    fun atlas(): WorkspaceAtlasSnapshot {
        val tiles = model.atlas.placementByLayerId.keys.sorted().mapNotNull(::tile)
        val pages = model.atlas.pages.mapIndexed { index, page ->
            val onPage = tiles.filter { it.page == index }
            val area = page.image.width.toLong() * page.image.height
            WorkspaceAtlasPage(index, page.image.width, page.image.height, onPage.size,
                if (area == 0L) 0f else (onPage.sumOf { it.width.toLong() * it.height } / area.toDouble()).toFloat().coerceIn(0f, 1f))
        }
        val overflow = WorkspaceAtlasFootprints.overflowing(model)
        val notices = if (overflow.isEmpty()) emptyList() else listOf("Meshes of " + overflow.joinToString() +
            " reach beyond the footprint they were arranged by; arrange the atlas again so their tiles keep clear of their neighbours.")
        return WorkspaceAtlasSnapshot(document.config().effectiveAtlasBudget(), pages, model.atlas.fit, notices, tiles, !model.atlas.arranged)
    }

    /** The page's canonical PNG, byte for byte what exports write. */
    fun pagePng(page: Int): ByteArray {
        require(page in model.atlas.pages.indices) { "Atlas page $page does not exist; the atlas has ${model.atlas.pages.size} page(s)" }
        return model.atlas.pages[page].png
    }

    private val tileRasters: Map<String, LayerRaster> by lazy {
        model.analysis.layers.associate { it.source.id.raw to it.source.textureLayer.raster }
    }

    /**
     * The raster [layerId]'s tile shows, resampled to the tile's size; null when it packs none. The pages hold it
     * as it is unless [upscaled].
     */
    fun tileRaster(layerId: String): LayerRaster? = tileRasters[layerId]?.takeIf { layerId in model.atlas.placementByLayerId }

    /** Whether the pages hold upscaled textures rather than the layers' own rasters. */
    val upscaled: Boolean get() = model.config.textureUpscale.scale != 1

    /** The mesh footprint [layerId]'s tile was arranged by, or null when it owns its whole rectangle. */
    fun footprint(layerId: String): TextureFootprint? = model.atlas.footprints[layerId]

    private val meshFootprints: Map<String, TextureFootprint> by lazy { WorkspaceAtlasFootprints.of(model) + model.atlas.footprints }

    /**
     * The cells [layerId]'s meshes use on its tile: the footprint it was arranged by, else its meshes' own. Tiles
     * collide only where these overlap, as the commit keeps them ([AtlasArrange.keep]); null for a tile no mesh samples.
     */
    fun meshFootprint(layerId: String): TextureFootprint? = meshFootprints[layerId]

    /** The layer ids of the tiles on [page]. */
    fun pageLayers(page: Int): List<String> = model.atlas.placementByLayerId.filterValues { it.page == page }.keys.sorted()

    /** [tile] at its spot, size and turn as the placement rule sees it ([WorkspaceAtlasPlacements]). */
    internal fun placed(tile: WorkspaceAtlasTile): WorkspaceAtlasPlacements.Placed {
        val raster = tileRasters[tile.layerId]
        return WorkspaceAtlasPlacements.Placed(tile.layerId, tile.page, tile.x, tile.y, tile.width, tile.height, tile.rotation,
            raster?.width ?: tile.width, raster?.height ?: tile.height, meshFootprint(tile.layerId))
    }

    /**
     * Where [placements] - tiles by layer at the spots, sizes and turns an edit would give them - would go wrong
     * against every other tile as committed: past the page, or meeting other tiles' meshes.
     */
    internal fun conflicts(placements: Map<String, WorkspaceAtlasTile>): List<WorkspaceAtlasPlacements.Conflict> {
        val atlas = atlas()
        val pageSize = atlas.pages.firstOrNull()?.width ?: atlas.budget.pageSize
        val live = atlas.tiles.filter { it.layerId !in document.deletedLayerIds }
        return WorkspaceAtlasPlacements.conflicts(placements.values.map(::placed), live.map(::placed), atlas.budget.padding, pageSize, atlas.budget.maxPages)
    }
}

/**
 * A tile edit that would push tiles off the spots the arrangement gives them - past the page, or where their meshes
 * meet another tile's - so the commit would place them somewhere the request never asked for. Refused, as the
 * atlas view refuses the same gesture; [layerIds] are the tiles that would be pushed: the edited tile, or the
 * neighbour a grown or moved tile would take the room of.
 */
internal class WorkspaceTileCollision(val layerIds: List<String>) : IllegalArgumentException(
    "This placement would push tiles ${layerIds.joinToString()} off their spots (past the page, or where tiles' meshes meet); " +
        "nothing was changed. Check placements with atlas_check_placement first")

/**
 * Whether tiles may stand where an edit would put them: the one rule the atlas view tests while dragging, the
 * atlas_check_placement query answers and the commit keeps ([AtlasArrange.keep]) - a tile's turned box stays on its
 * page, and two tiles on a page meet where their meshes' cells, grown by the padding, share a cell (two upright
 * tiles without footprints by their rectangles and the padding).
 */
internal object WorkspaceAtlasPlacements {
    /** A tile as the rule sees it: its upright rectangle and turn on a page, its raster and the cells its meshes use. */
    class Placed(val layerId: String, val page: Int, val x: Int, val y: Int, val width: Int, val height: Int, val rotation: Float,
                 val rasterWidth: Int, val rasterHeight: Int, val footprint: TextureFootprint?)

    /** Where [layerId] would go wrong: [outsidePage], and the tiles whose meshes it would meet. */
    class Conflict(val layerId: String, val outsidePage: Boolean, val overlaps: List<String>)

    fun shape(tile: Placed): AtlasArrange.Shape =
        AtlasArrange.shape(tile.x, tile.y, tile.width, tile.height, tile.rasterWidth, tile.rasterHeight, tile.footprint, tile.rotation)

    fun outside(tile: Placed, pageSize: Int, maxPages: Int): Boolean {
        if (tile.page !in 0 until maxPages) return true
        val box = TileTurn.bounds(tile.x.toFloat(), tile.y.toFloat(), tile.width.toFloat(), tile.height.toFloat(), tile.rotation)
        return box[0] < -1e-3f || box[1] < -1e-3f || box[2] > pageSize + 1e-3f || box[3] > pageSize + 1e-3f
    }

    fun meet(a: Placed, b: Placed, padding: Int, shapeOf: (Placed) -> AtlasArrange.Shape = ::shape): Boolean {
        if (a.page != b.page || a.layerId == b.layerId) return false
        // The commit's own pairwise rule; the shapes are only built when the boxes come close.
        val near = TileTurn.bounds(a.x.toFloat(), a.y.toFloat(), a.width.toFloat(), a.height.toFloat(), a.rotation).let { ab ->
            TileTurn.bounds(b.x.toFloat(), b.y.toFloat(), b.width.toFloat(), b.height.toFloat(), b.rotation).let { bb ->
                ab[0] < bb[2] + padding && bb[0] < ab[2] + padding && ab[1] < bb[3] + padding && bb[1] < ab[3] + padding
            }
        }
        if (!near) return false
        return AtlasArrange.meet(a.x, a.y, a.width, a.height, a.rotation, a.footprint == null, shapeOf(a),
            b.x, b.y, b.width, b.height, b.rotation, b.footprint == null, shapeOf(b), padding)
    }

    /** Each of [moved] against [standing] and the other moved tiles. */
    fun conflicts(moved: List<Placed>, standing: List<Placed>, padding: Int, pageSize: Int, maxPages: Int): List<Conflict> {
        val movedIds = moved.mapTo(HashSet()) { it.layerId }
        val others = standing.filter { it.layerId !in movedIds } + moved
        val shapes = HashMap<String, AtlasArrange.Shape>()
        fun shapeOf(tile: Placed) = shapes.getOrPut(tile.layerId + "@" + tile.page + "," + tile.x + "," + tile.y + "," + tile.width + "," +
            tile.height + "," + tile.rotation) { shape(tile) }
        return moved.map { tile ->
            Conflict(tile.layerId, outside(tile, pageSize, maxPages), others.filter { meet(tile, it, padding, ::shapeOf) }.map { it.layerId }.sorted())
        }
    }
}

/** Pure texture candidates: no files, jobs, projection or history. */
internal object WorkspaceTextureEdits {
    val supported = setOf("layer_set_canvas_rect", "layer_replace_image", "layer_set_pixel_density", "atlas_set_tile",
        "atlas_set_budget", "atlas_pack")

    const val MIN_DENSITY = 1f / 64f
    const val MAX_DENSITY = 16f
    private const val MAX_PIXELS = 16L * 1024 * 1024
    private const val MAX_PNG_BYTES = 64 * 1024 * 1024
    private val pageSizes = (8..14).map { 1 shl it }

    fun apply(operation: WorkspaceDocumentOperation, document: WorkspaceDocument, model: RigPreviewModel,
              checkCancelled: () -> Unit = {}): WorkspaceDocument = apply(document, model, parse(operation), checkCancelled)

    fun parse(operation: WorkspaceDocumentOperation): WorkspaceTextureEdit {
        val request = operation.request
        fun text(key: String) = request.getValue(key).jsonPrimitive.content
        fun int(key: String) = request[key]?.jsonPrimitive?.int
        return when (operation.operation) {
            "layer_set_canvas_rect" -> {
                val rect = request.getValue("rect").jsonObject
                fun value(key: String) = rect.getValue(key).jsonPrimitive.float
                WorkspaceTextureEdit.SetCanvasRect(text("layer_id"), LayerCanvasRect(value("left"), value("top"), value("width"), value("height")))
            }
            "layer_replace_image" -> {
                val path = request["path"]?.jsonPrimitive?.content
                val png = request["png_base64"]?.jsonPrimitive?.content
                require((path == null) != (png == null)) { "Give exactly one of path or png_base64" }
                val image = if (path != null) WorkspaceTextureImage.File(Path.of(path).also {
                    require(it.isAbsolute) { "Provide an absolute local path" }
                }) else WorkspaceTextureImage.Png(decodePngBase64(requireNotNull(png)))
                WorkspaceTextureEdit.ReplaceImage(text("layer_id"), image,
                    request["fit"]?.jsonPrimitive?.content?.let { WorkspaceImageFit.valueOf(it.uppercase()) } ?: WorkspaceImageFit.STRETCH,
                    request["rebuild_mesh"]?.jsonPrimitive?.boolean ?: false)
            }
            "layer_set_pixel_density" -> WorkspaceTextureEdit.SetPixelDensity(
                request.getValue("layer_ids").jsonArray.map { it.jsonPrimitive.content },
                request.getValue("density").let { if (it is JsonNull) null else it.jsonPrimitive.float },
                request["lock"]?.jsonPrimitive?.boolean)
            "atlas_set_tile" -> WorkspaceTextureEdit.SetTile(text("layer_id"), request.getValue("pin").let { pin ->
                if (pin is JsonNull) null else pin.jsonObject.let {
                    TexturePin(it.getValue("page").jsonPrimitive.int, it.getValue("x").jsonPrimitive.int, it.getValue("y").jsonPrimitive.int,
                        normalizedRotation(it["rotation"]?.jsonPrimitive?.float ?: 0f))
                }
            })
            "atlas_set_budget" -> WorkspaceTextureEdit.SetBudget(int("page_size"), int("max_pages"), int("padding"), request["auto"]?.jsonPrimitive?.boolean)
            "atlas_pack" -> WorkspaceTextureEdit.Pack((request["shape"]?.jsonPrimitive?.content ?: "mesh") == "mesh",
                request["layer_ids"]?.jsonArray?.map { it.jsonPrimitive.content })
            else -> throw IllegalArgumentException("Not a texture operation: ${operation.operation}")
        }
    }

    fun apply(document: WorkspaceDocument, model: RigPreviewModel, edit: WorkspaceTextureEdit, checkCancelled: () -> Unit = {}): WorkspaceDocument {
        checkCancelled()
        return when (edit) {
            is WorkspaceTextureEdit.SetCanvasRect -> canvasRect(document, model, edit)
            is WorkspaceTextureEdit.ReplaceImage -> replace(document, model, edit, checkCancelled)
            is WorkspaceTextureEdit.SetPixelDensity -> density(document, model, edit)
            is WorkspaceTextureEdit.SetTile -> tile(document, model, edit)
            is WorkspaceTextureEdit.SetBudget -> budget(document, model, edit)
            is WorkspaceTextureEdit.Pack -> pack(document, model, edit)
        }
    }

    /**
     * Refuses a candidate whose build would push a tile off the spot its arrangement gives it, where that tile kept
     * its spot before - the commit's own placement rule, so a request lands exactly where it asked or not at all.
     */
    fun requireKept(before: WorkspaceDocument, beforeModel: RigPreviewModel, after: WorkspaceDocument, afterModel: RigPreviewModel) {
        fun displaced(document: WorkspaceDocument, model: RigPreviewModel): Set<String> {
            val arrangement = AtlasArrangementCodec.decode(document.settings) ?: return emptySet()
            return arrangement.tiles.filter { (id, tile) ->
                val at = model.atlas.placementByLayerId[id] ?: return@filter false
                at.page != tile.page || at.x != tile.x || at.y != tile.y || at.rotation != tile.rotation
            }.keys
        }
        val pushed = displaced(after, afterModel) - displaced(before, beforeModel)
        if (pushed.isNotEmpty()) throw WorkspaceTileCollision(pushed.sorted())
    }

    /** Edits whose results must keep every tile on its spot; budget, packing and pixel edits lay tiles out anew by design. */
    val placementEdits = setOf("atlas_set_tile", "layer_set_pixel_density")
    val relayoutEdits = setOf("atlas_set_budget", "atlas_pack", "layer_replace_image")

    /** The layers whose texture [edit] changed between [before] and [after]. */
    fun changedLayers(edit: WorkspaceTextureEdit, before: WorkspaceDocument, after: WorkspaceDocument): List<String> = when (edit) {
        is WorkspaceTextureEdit.SetCanvasRect -> listOf(edit.layerId).filter { before.source !== after.source }
        is WorkspaceTextureEdit.ReplaceImage -> listOf(edit.layerId).filter { before.source !== after.source }
        else -> {
            val a = AtlasArrangementCodec.decode(before.settings)?.tiles.orEmpty(); val b = AtlasArrangementCodec.decode(after.settings)?.tiles.orEmpty()
            val ids = (before.textureOverrides.keys + after.textureOverrides.keys).filter { before.textureOverrides[it] != after.textureOverrides[it] } +
                (a.keys + b.keys).filter { a[it] != b[it] }
            // Tiles a move or density edit only settles where they already show are not changes.
            when (edit) {
                is WorkspaceTextureEdit.SetTile -> listOf(edit.layerId).filter { it in ids }
                is WorkspaceTextureEdit.SetPixelDensity -> ids.distinct().filter { it in edit.layerIds }.sorted()
                else -> ids.distinct().sorted()
            }
        }
    }

    /** A source layer the document holds and has not deleted. */
    private fun sourceLayer(document: WorkspaceDocument, id: String): SourceLayer {
        WorkspaceArtPrimitives.requireCurrent(document.rigEdits, id)
        val layer = document.source.layers.singleOrNull { it.id.raw == id }
            ?: throw IllegalArgumentException("Source layer not found: $id")
        require(id !in document.deletedLayerIds) { "Layer is deleted: $id" }
        return layer
    }

    /** Ids that own an atlas tile or a texture: source layers and the analysed layers derived from them. */
    private fun requireTextureLayer(document: WorkspaceDocument, model: RigPreviewModel, id: String) {
        WorkspaceArtPrimitives.requireCurrent(document.rigEdits, id)
        require(document.source.layers.any { it.id.raw == id } || model.analysis.layers.any { it.source.id.raw == id }) { "Layer not found: $id" }
        require(id !in document.deletedLayerIds) { "Layer is deleted: $id" }
    }

    /**
     * Moves and scales [edit]'s layer so the canvas shows its pixels over the rectangle: a [WorkspaceLayerTransform] from
     * where they show now. The pixels, the texture and the generation input stay; the layer's meshes move.
     */
    private fun canvasRect(document: WorkspaceDocument, model: RigPreviewModel, edit: WorkspaceTextureEdit.SetCanvasRect): WorkspaceDocument {
        val layer = sourceLayer(document, edit.layerId)
        val rect = edit.rect
        require(rect.width > 0f && rect.height > 0f) { "Canvas rectangle needs a positive width and height" }
        val shown = requireNotNull(layer.displayedRect()) { "The layer is turned; move it with layer_transform" }
        require(shown.width > 0f && shown.height > 0f) { "The layer has no area to scale" }
        val sx = rect.width / shown.width; val sy = rect.height / shown.height
        if (sx == 1f && sy == 1f && rect.left == shown.left && rect.top == shown.top) return document
        val delta = LayerTransform(sx, 0f, 0f, sy, rect.left - shown.left * sx, rect.top - shown.top * sy)
        return WorkspaceLayerTransform.apply(document, model, buildJsonObject {
            put("layer_id", edit.layerId); put("matrix", JsonArray(delta.toList().map(::JsonPrimitive)))
        })
    }

    private fun replace(document: WorkspaceDocument, model: RigPreviewModel, edit: WorkspaceTextureEdit.ReplaceImage,
                        checkCancelled: () -> Unit): WorkspaceDocument {
        val layer = sourceLayer(document, edit.layerId)
        require(document.rigEdits.importedCmo3 == null) { "Replacing the image of an imported CMO3 layer is not supported" }
        val raster = when (val image = edit.image) {
            is WorkspaceTextureImage.Raster -> image.raster
            is WorkspaceTextureImage.Png -> decodePng(image.bytes, checkCancelled)
            is WorkspaceTextureImage.File -> throw IllegalArgumentException(
                "A file path is read only by a single layer_replace_image; in a batch give png_base64")
        }
        checkCancelled()
        val fit = if (edit.fit == WorkspaceImageFit.CONTAIN) LayerImageReplace.Fit.CONTAIN else LayerImageReplace.Fit.STRETCH
        val replaced = LayerImageReplace.replace(document, edit.layerId, raster, fit)
        val laid = replaced.source.layers.single { it.id.raw == edit.layerId }
        val samePixels = laid.raster.width == layer.raster.width && laid.raster.height == layer.raster.height &&
            laid.raster.rgba.contentEquals(layer.raster.rgba)
        if (!edit.rebuildMesh) return if (samePixels) document else replaced
        requireUnbound(document, model, edit.layerId, "Rebuilding the mesh of a replaced layer")
        // The new pixels also become this layer's generation input; other layers keep theirs.
        val generation = document.generationSource?.let { swap(it, edit.layerId) { laid } }
        val mesh = swap(document.meshSource, edit.layerId) { laid }
        return replaced.copy(generationSource = generation, meshSource = mesh)
    }

    private fun density(document: WorkspaceDocument, model: RigPreviewModel, edit: WorkspaceTextureEdit.SetPixelDensity): WorkspaceDocument {
        require(edit.layerIds.size in 1..128 && edit.layerIds.distinct().size == edit.layerIds.size) { "Give 1..128 unique layer ids" }
        edit.density?.let { require(it.isFinite() && it in MIN_DENSITY..MAX_DENSITY) { "Density must lie in $MIN_DENSITY..$MAX_DENSITY" } }
        edit.layerIds.forEach { requireTextureLayer(document, model, it) }
        val density = edit.density?.takeUnless { it == 1f }
        val overrides = document.textureOverrides.toMutableMap()
        for (id in edit.layerIds) {
            val current = overrides[id] ?: TextureOverride()
            put(overrides, id, current.copy(density = density, lock = edit.lock ?: current.lock))
        }
        val changed = withOverrides(document, overrides)
        if (changed === document) return document
        return AtlasArrangementCodec.decode(document.settings)?.let { withArrangement(changed, settled(it, model)) } ?: changed
    }

    /**
     * [stored] with every tile of [model] at the spot it shows now. A tile without a stored spot that holds - a new
     * layer, a split part, one a resize displaced - is otherwise placed into free space again on every build, so it
     * would jump into the room a moved or shrunk tile leaves. Recording its spot keeps it there.
     */
    private fun settled(stored: AtlasArrangement, model: RigPreviewModel): AtlasArrangement {
        if (!model.atlas.arranged) return stored
        val tiles = stored.tiles.toMutableMap()
        for ((id, at) in model.atlas.placementByLayerId) {
            val kept = tiles[id]
            if (kept == null || kept.page != at.page || kept.x != at.x || kept.y != at.y || kept.rotation != at.rotation)
                tiles[id] = ArrangedTile(at.page, at.x, at.y, model.atlas.footprints[id], at.rotation)
        }
        return meshed(stored.copy(tiles = tiles), model)
    }

    /**
     * [arrangement] with every tile that has no footprint given its meshes' one, so tiles overlap only where their
     * meshes do - by the cells the meshes use, never by their rectangles - and each writes only those cells, as
     * the atlas view's drag tests ([WorkspaceTextureView.meshFootprint]) with the very same footprints.
     */
    private fun meshed(arrangement: AtlasArrangement, model: RigPreviewModel): AtlasArrangement {
        if (arrangement.tiles.values.all { it.footprint != null }) return arrangement
        val footprints = WorkspaceAtlasFootprints.of(model)
        return arrangement.copy(tiles = arrangement.tiles.mapValues { (id, tile) ->
            if (tile.footprint != null) tile else tile.copy(footprint = footprints[id])
        })
    }

    /**
     * Moves [edit]'s tile to its spot, or releases it to free space. The spot is stored in the atlas arrangement;
     * an automatically arranged atlas is first kept as it is, so the move does not reflow the other tiles. With
     * no arrangement, releasing clears a pin set by an earlier build.
     */
    private fun tile(document: WorkspaceDocument, model: RigPreviewModel, edit: WorkspaceTextureEdit.SetTile): WorkspaceDocument {
        requireTextureLayer(document, model, edit.layerId)
        edit.pin?.let { pin ->
            require(edit.layerId in model.atlas.placementByLayerId) { "Layer ${edit.layerId} has no atlas tile to pin" }
            val budget = document.config().effectiveAtlasBudget()
            require(pin.page < budget.maxPages) { "Page ${pin.page} is outside the budget of ${budget.maxPages} page(s)" }
            val pageSize = model.atlas.pages.firstOrNull()?.image?.width ?: budget.pageSize
            require(pin.x < pageSize && pin.y < pageSize) { "Pin lies outside the ${pageSize}px page" }
        }
        val stored = AtlasArrangementCodec.decode(document.settings)
        val overrides = document.textureOverrides.toMutableMap()
        overrides[edit.layerId]?.let { put(overrides, edit.layerId, it.copy(pin = null)) }
        val cleared = withOverrides(document, overrides)
        if (stored == null && edit.pin == null) return cleared
        val arrangement = stored?.let { settled(it, model) } ?: meshed(AtlasLayout.frozen(model.atlas), model)
        val tiles = arrangement.tiles.toMutableMap()
        val pin = edit.pin
        if (pin == null) tiles.remove(edit.layerId)
        else tiles[edit.layerId] = ArrangedTile(pin.page, pin.x, pin.y, tiles[edit.layerId]?.footprint ?: model.atlas.footprints[edit.layerId],
            normalizedRotation(pin.rotation))
        return withArrangement(cleared, arrangement.copy(tiles = tiles))
    }

    /** Changes the budget and, with [WorkspaceTextureEdit.SetBudget.auto], whether the atlas arranges itself. */
    private fun budget(document: WorkspaceDocument, model: RigPreviewModel, edit: WorkspaceTextureEdit.SetBudget): WorkspaceDocument {
        require(edit.pageSize != null || edit.maxPages != null || edit.padding != null || edit.auto != null) { "Give page_size, max_pages, padding or auto" }
        edit.pageSize?.let { require(it in pageSizes) { "page_size must be a power of two in 256..16384" } }
        edit.maxPages?.let { require(it in 1..64) { "max_pages must lie in 1..64" } }
        edit.padding?.let { require(it in 0..32) { "padding must lie in 0..32" } }
        val stored = WorkspaceSettingsCodec.decodeAtlasBudget(document.settings)
        val current = stored ?: WorkspaceSettingsCodec.atlasBudget(document.settings)
        val next = AtlasBudget(edit.pageSize ?: current.pageSize, edit.maxPages ?: current.maxPages, edit.padding ?: current.padding)
        val budgeted = if (next == current) document
            else document.copy(settings = JsonObject(document.settings + (WorkspaceSettingsCodec.ATLAS to WorkspaceSettingsCodec.encodeAtlasBudget(next))))
        val arranged = AtlasArrangementCodec.decode(document.settings)
        return when (edit.auto) {
            true -> withArrangement(budgeted, null)
            // Turning the automatic arrangement off keeps the layout the atlas has now.
            false -> if (arranged != null) budgeted else withArrangement(budgeted, AtlasLayout.frozen(model.atlas))
            null -> budgeted
        }
    }

    /**
     * Arranges the atlas once and stores the result ([AtlasLayout.arrange]): by the meshes' footprints, so
     * tiles nest wherever their meshes leave room, or by their rectangles. Only [WorkspaceTextureEdit.Pack.layerIds]
     * move when given. Pins of the automatic layout are dropped, as are overrides of layers that no longer exist.
     */
    private fun pack(document: WorkspaceDocument, model: RigPreviewModel, edit: WorkspaceTextureEdit.Pack): WorkspaceDocument {
        edit.layerIds?.let { ids ->
            require(ids.isNotEmpty() && ids.distinct().size == ids.size) { "Give unique layer ids to arrange" }
            ids.forEach { require(it in model.atlas.placementByLayerId) { "Layer $it has no atlas tile" } }
        }
        val known = document.source.layers.mapTo(HashSet()) { it.id.raw } + model.analysis.layers.map { it.source.id.raw }
        val overrides = document.textureOverrides.filterKeys { it in known }.mapValues { (_, value) -> value.copy(pin = null) }
            .filterValues { !it.isDefault }
        val cleaned = withOverrides(document, overrides.toMutableMap())
        val footprints = if (edit.byMesh) WorkspaceAtlasFootprints.of(model) else emptyMap()
        val arrangement = AtlasLayout.arrange(model.analysis.layers, cleaned.config(), footprints, model.atlas, edit.layerIds?.toSet())
            ?: throw IllegalArgumentException("The textures do not fit the atlas budget even at the smallest scale; raise the page size or page count")
        return withArrangement(cleaned, arrangement)
    }

    private fun withArrangement(document: WorkspaceDocument, arrangement: AtlasArrangement?): WorkspaceDocument {
        if (arrangement == AtlasArrangementCodec.decode(document.settings)) return document
        return document.copy(settings = AtlasArrangementCodec.with(document.settings, arrangement))
    }

    private fun put(overrides: MutableMap<String, TextureOverride>, id: String, value: TextureOverride) {
        if (value.isDefault) overrides.remove(id) else overrides[id] = value
    }

    private fun withOverrides(document: WorkspaceDocument, overrides: Map<String, TextureOverride>): WorkspaceDocument =
        if (overrides == document.storedTextureOverrides) document else document.copy(textureOverrides = overrides.toMap())

    private fun swap(source: SourceArt?, id: String, transform: (SourceLayer) -> SourceLayer): SourceArt? {
        if (source == null || source.layers.none { it.id.raw == id }) return source
        return WorkspaceSourceArt(source.widthPx, source.heightPx, source.layers.map { if (it.id.raw == id) transform(it) else it }, source.groups)
    }

    /** Journal records that only fix identities or generation rules; they place no geometry of their own. */
    private val identityRecords = setOf(MeshGenerationBaseline.OP, RigGenerationBaseline.OP, RigGenerationFrames.OP, "rig_generation_transition",
        "rig_generation_scaffold", RigLayerDeletion.OP, "rig_mesh_activation", "layer_draw_order")
    /** Records that hold a layer's mesh geometry themselves, so the generator no longer places it. */
    private val materializedRecords = setOf(ArtPrimitiveJournal.OP, RasterMeshCreation.OP, "canvas_mesh_rebuild", "canvas_source_partition", "canvas_depth_split")

    /**
     * Rejects moving or regenerating a layer whose meshes carry authored geometry or bindings: those are stored in
     * the mesh's old coordinates and would not follow. Generated keyforms, masks and glue are regenerated and follow.
     */
    private fun requireUnbound(document: WorkspaceDocument, model: RigPreviewModel, id: String, action: String) {
        require(document.rigEdits.importedCmo3 == null) { "$action is not supported for imported CMO3 models" }
        val meshes = model.rig.puppet.drawables.filter { model.rig.layerIdByDrawableId[it.id.raw] == id }.mapTo(HashSet()) { it.id.raw }
        fun references(value: JsonElement): Boolean = when (value) {
            is JsonObject -> value.any { (key, nested) -> key !in setOf("name", "user_data") && references(nested) }
            is JsonArray -> value.any(::references)
            is JsonPrimitive -> value.isString && (value.content == id || value.content in meshes || value.content.substringAfter(':') in meshes)
        }
        val edits = document.rigEdits
        for (entry in edits.authoringJournal) {
            val op = entry["op"]?.jsonPrimitive?.contentOrNull
            if (op in identityRecords || !references(entry)) continue
            require(op !in materializedRecords) {
                "$action is not supported for layer $id: its mesh geometry is materialized by a $op record (split, created or rebuilt mesh). " +
                    "Move it as a mesh (canvas_geometry), or history_checkout before that record"
            }
            throw IllegalArgumentException("$action is not supported for layer $id: authored edits ($op) are bound to its meshes. " +
                "Move it before authoring, or undo those edits first")
        }
        require(edits.structureEdits.none(::references) && edits.warpEdits.none { w -> w.meshIds.any { it in meshes } } &&
            edits.keyformSetEdits.none { it.target.id in meshes } && edits.keyformCopyEdits.none { it.destinationTarget.id in meshes } &&
            edits.keyformDeleteEdits.none { it.target.id in meshes }) { "$action is not supported for layer $id: it has authored Warp or keyform edits" }
        require(edits.skeleton?.let { skeleton ->
            skeleton.bones.any { bone -> bone.drawableIds.any { it in meshes } } || skeleton.manualWeights.keys.any { it in meshes }
        } != true && edits.swingEdits.none { edit -> edit.targets.any { it == id || it in meshes } } &&
            edits.simEdits.none { edit -> edit.targets.any { it == id || it in meshes } }) { "$action is not supported for layer $id: it has skeleton, swing or simulation bindings" }
    }

    /** A PNG of at most 64 MiB and 16 megapixels, checked from its header before decoding. */
    fun decodePng(bytes: ByteArray, checkCancelled: () -> Unit = {}): LayerRaster {
        require(bytes.size in 1..MAX_PNG_BYTES) { "PNG must hold 1 byte to 64 MiB" }
        val codec = FormatRegistry.detect(bytes, "image.png") as? RasterCodec
        require(codec?.kind == FileKind.Png && bytes.size >= 24 && bytes.copyOfRange(12, 16).decodeToString() == "IHDR") { "png_base64 is not a PNG image" }
        val width = ByteBuffer.wrap(bytes).getInt(16).toLong(); val height = ByteBuffer.wrap(bytes).getInt(20).toLong()
        require(width > 0 && height > 0 && width * height <= MAX_PIXELS) { "Image exceeds 16 megapixels" }
        checkCancelled()
        val image = requireNotNull(codec).read(bytes)
        checkCancelled()
        return LayerRaster(image.width, image.height, image.rgba)
    }

    /** A decoded image file of any supported transparent format. */
    fun decodeFile(path: Path, checkCancelled: () -> Unit): LayerRaster {
        val image = LayerImport.decodeRasterFile(path.toFile(), checkCancelled)
        return LayerRaster(image.width, image.height, image.rgba)
    }
}

/** The cells of each tile's raster its meshes cover, from the meshes' texture coordinates on the current atlas. */
internal object WorkspaceAtlasFootprints {
    /** A footprint holds at most this many cells across, so a dense raster stays a small record. */
    private const val MAX_CELLS = 192

    private class Covered(val rasterWidth: Int, val rasterHeight: Int, val triangles: MutableList<FloatArray> = ArrayList())

    /** Every textured mesh's triangles in its layer's raster pixels, by layer. */
    private fun covered(model: RigPreviewModel): Map<String, Covered> {
        val atlas = model.atlas
        val out = LinkedHashMap<String, Covered>()
        for (drawable in model.rig.puppet.drawables) {
            val mesh = drawable.mesh ?: continue
            val layerId = model.rig.layerIdByDrawableId[drawable.id.raw] ?: continue
            val at = atlas.placementByLayerId[layerId] ?: continue
            val page = atlas.pages.getOrNull(at.page)?.image ?: continue
            val covered = out.getOrPut(layerId) {
                Covered(Math.round(at.width / at.scaleX).coerceAtLeast(1), Math.round(at.height / at.scaleY).coerceAtLeast(1))
            }
            val uv = mesh.uvs; val indices = mesh.indices
            for (t in 0 until indices.size - 2 step 3) covered.triangles += FloatArray(6) { k ->
                val vertex = indices[t + k / 2]
                // Through the tile's turn: the raster point each texture coordinate samples.
                at.toRaster(uv[vertex * 2] * page.width, uv[vertex * 2 + 1] * page.height)[k % 2]
            }
        }
        return out
    }

    private fun cell(width: Int, height: Int) = maxOf(AtlasArrange.CELL, (maxOf(width, height) + MAX_CELLS - 1) / MAX_CELLS)

    /**
     * Each tile's footprint: the cells its meshes cover, with any art outside them added as a stored arrangement
     * adds it on every build ([AtlasArrange.covering]), so what is arranged and dragged here is what is kept.
     */
    fun of(model: RigPreviewModel): Map<String, TextureFootprint> {
        val rasters = model.analysis.layers.associate { it.source.id.raw to it.source.textureLayer.raster }
        return covered(model).mapNotNull { (id, covered) ->
            val meshes = AtlasArrange.footprint(covered.rasterWidth, covered.rasterHeight, cell(covered.rasterWidth, covered.rasterHeight), covered.triangles)
                ?: return@mapNotNull null
            val raster = rasters[id] ?: return@mapNotNull id to meshes
            AtlasArrange.covering(meshes, raster)?.let { id to it }
        }.toMap()
    }

    /** Names of layers whose meshes now cover cells outside the footprint their tile was arranged by. */
    fun overflowing(model: RigPreviewModel): List<String> {
        if (model.atlas.footprints.isEmpty()) return emptyList()
        val names = model.analysis.layers.associate { it.source.id.raw to it.source.name }
        return covered(model).mapNotNull { (id, covered) ->
            val stored = model.atlas.footprints[id] ?: return@mapNotNull null
            val now = AtlasArrange.footprint(covered.rasterWidth, covered.rasterHeight, stored.cell, covered.triangles) ?: return@mapNotNull null
            if (now.columns != stored.columns || now.rows != stored.rows) return@mapNotNull names[id] ?: id
            val outside = now.bits.clone() as java.util.BitSet
            outside.andNot(stored.bits)
            if (outside.isEmpty) null else names[id] ?: id
        }.sorted()
    }
}
