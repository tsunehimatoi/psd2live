package io.github.psd2live.core

import io.github.psd2live.format.compile.RasterResample
import io.github.psd2live.i18n.tr
import io.github.psd2live.project.ArrangedTile
import io.github.psd2live.project.AtlasArrangement
import io.github.psd2live.project.TextureFootprint
import io.github.psd2live.project.TextureOverride
import io.github.psd2live.project.TexturePin
import org.umamo.format.art.SourceLayer
import java.awt.image.BufferedImage
import java.lang.ref.SoftReference
import java.nio.file.Path
import java.util.Locale
import javax.imageio.ImageIO

/**
 * Where layer rasters go on the atlas pages, and the pages themselves.
 *
 * A document with a stored arrangement ([AtlasArrangement]) keeps it: every listed tile stays at its spot
 * and fit, a tile arranged by its meshes writes only the cells its meshes use, and only tiles without a free
 * stored spot are placed into free space ([AtlasArrange]). [arrange] finds such an arrangement once, on request.
 *
 * Without one the layout is the canonical one - a deterministic multi-page rectangle pack of every textured
 * layer ([RectPack]) - so a document's atlas is a function of the document alone: a fresh build, a reopen, an
 * undo, a paint commit and an export all put the same tiles in the same spots. What is incremental is the pages. Given the atlas a commit starts from (`previous`),
 * a page none of whose tiles changed - same pixels, same spot, none removed - is that atlas's page itself; a
 * changed page is composed again and remembers which rows differ from the page it replaces, so its preview
 * PNG re-encodes only those strips ([AtlasPagePng]). A paint that keeps its layer's size thus redraws one
 * tile in place; one that resizes it reflows the pack, which may move other tiles and costs a page
 * composition plus the strips they cross, but never a layout that depends on the editing history.
 *
 * Tile sizes follow the atlas budget ([AtlasBudget]) and the layers' texture overrides ([TextureOverride]):
 * a tile holds `texture raster pixels x upscale x density x fit` texture pixels, where the density is the
 * layer's override (1 by default) and fit (at most 1) is one factor for every unlocked layer, the largest
 * multiple of 1/[FIT_STEPS] at which the pack fits the budget's page count. Locked layers keep fit 1 and
 * pinned layers keep their page position. With every override at its default and the layout fitting at
 * fit 1, tiles are exactly `raster x upscale` and the layout is the one pages always had.
 *
 * Pages are also cached by recipe (page size and each tile's rectangle and pixel digest), so a rebuild that
 * lands on the same tiles - a reopen, a history checkout back to earlier pixels - gets the very same
 * [AtlasPage], whose lazily encoded PNGs keep their identity too: the preview's bundle fingerprint skips
 * re-hashing them and the native preview uploads only the pages that really changed.
 */
internal object AtlasLayout {
    /**
     * One textured layer to place. [baseWidth] x [baseHeight] is its tile at fit 1 - texture raster pixels times
     * the upscale and the layer's density; [canvasWidth] x [canvasHeight] its canvas-resolution extent times the
     * upscale, which is what a page grows to hold.
     */
    private class Item(
        val layer: ClassifiedLayer,
        val texture: SourceLayer,
        val baseWidth: Double,
        val baseHeight: Double,
        val canvasWidth: Int,
        val canvasHeight: Int,
        val locked: Boolean,
        val pin: TexturePin?,
    ) {
        val id: String get() = layer.source.id.raw
        val baseW: Int = sizeOf(baseWidth)
        val baseH: Int = sizeOf(baseHeight)

        /** The tile at [fit]; a locked tile keeps fit 1 while [honour]ing locks. */
        fun width(fit: Double, honour: Boolean): Int = if ((honour && locked) || fit == 1.0) baseW else sizeOf(baseWidth * fit)
        fun height(fit: Double, honour: Boolean): Int = if ((honour && locked) || fit == 1.0) baseH else sizeOf(baseHeight * fit)

        private fun sizeOf(value: Double): Int = Math.round(value).coerceIn(1L, 1L shl 20).toInt()
    }

    /** Fit is searched in steps of 1/[FIT_STEPS], so equal inputs always give the same fit. */
    private const val FIT_STEPS = 4096

    /** No page-count limit, for callers that only name a page size. */
    const val UNLIMITED_PAGES: Int = Int.MAX_VALUE

    /** The canonical layout of [layers] under [config]'s atlas budget, texture overrides and upscale. */
    fun pack(
        layers: List<ClassifiedLayer>,
        config: PipelineConfig,
        progress: ProgressListener = ProgressListener { _, _ -> },
        previous: PackedAtlas? = null,
    ): PackedAtlas = pack(layers, config.effectiveAtlasBudget(), config.textureOverrides, config.textureUpscale, progress, previous,
        config.atlasArrangement)

    /**
     * The canonical layout of [layers] on pages of [requestedSize] with no page-count limit and no overrides.
     * [previous] - the atlas a commit starts from - lends every page whose recipe did not change, and the
     * strips of the rows that did not, to the new pages.
     */
    fun pack(
        layers: List<ClassifiedLayer>,
        requestedSize: Int,
        padding: Int,
        upscale: TextureUpscaleConfig = TextureUpscaleConfig(),
        progress: ProgressListener = ProgressListener { _, _ -> },
        previous: PackedAtlas? = null,
    ): PackedAtlas = pack(layers, unlimited(requestedSize, padding), emptyMap(), upscale, progress, previous)

    fun pack(
        layers: List<ClassifiedLayer>,
        budget: AtlasBudget,
        overrides: Map<String, TextureOverride>,
        upscale: TextureUpscaleConfig = TextureUpscaleConfig(),
        progress: ProgressListener = ProgressListener { _, _ -> },
        previous: PackedAtlas? = null,
        arrangement: AtlasArrangement? = null,
    ): PackedAtlas = packWithTextures(layers, budget, overrides, upscale, progress, previous, arrangement) { l, c -> TextureUpscale.prepare(l, c, progress) }

    internal fun packWithTextures(
        layers: List<ClassifiedLayer>, requestedSize: Int, padding: Int,
        upscale: TextureUpscaleConfig,
        progress: ProgressListener = ProgressListener { _, _ -> },
        previous: PackedAtlas? = null,
        prepareTextures: (List<ClassifiedLayer>, TextureUpscaleConfig) -> Map<String, Path>,
    ): PackedAtlas = packWithTextures(layers, unlimited(requestedSize, padding), emptyMap(), upscale, progress, previous, prepareTextures = prepareTextures)

    private fun unlimited(requestedSize: Int, padding: Int) = AtlasBudget(requestedSize.coerceAtLeast(1), UNLIMITED_PAGES, padding.coerceAtLeast(0))

    internal fun packWithTextures(
        layers: List<ClassifiedLayer>,
        budget: AtlasBudget,
        overrides: Map<String, TextureOverride>,
        upscale: TextureUpscaleConfig,
        progress: ProgressListener = ProgressListener { _, _ -> },
        previous: PackedAtlas? = null,
        arrangement: AtlasArrangement? = null,
        prepareTextures: (List<ClassifiedLayer>, TextureUpscaleConfig) -> Map<String, Path>,
    ): PackedAtlas {
        val safePadding = budget.padding.coerceIn(0, 32)
        val items = items(layers, upscale.scale, overrides)
        val pageSize = pageSize(items, budget.pageSize, safePadding)
        val solved = if (arrangement != null) kept(items, arrangement, pageSize, safePadding, budget.maxPages)
            else solve(items, pageSize, safePadding, budget.maxPages)
        val placements = solved.placements
        val masks = solved.masks
        val pageCount = solved.pageCount
        // Fail before inference or large allocations; encoded PNGs and render copies cost extra memory.
        require(upscale.scale == 1 || pageCount.toLong() * pageSize * pageSize * 4 <= 512L * 1024 * 1024) {
            "Upscaled atlas exceeds 512 MiB of raw pixels. Reduce texture scale or atlas size."
        }
        progress.update(tr("progress.atlas"), 0.96)
        if (upscale.scale != 1) {
            return PackedAtlas(upscaledPages(items, placements, masks, pageSize, pageCount, budget.padding, prepareTextures(items.map { it.layer }, upscale)),
                placements, solved.fit, solved.notices, solved.footprints, arrangement != null)
        }
        val byPage = items.groupBy { placements.getValue(it.id).page }
        // Pages compose independently, in parallel; the list keeps page order. Workers watch the caller's interrupt.
        val caller = Thread.currentThread()
        val compose = java.util.stream.IntStream.range(0, pageCount).mapToObj { index ->
            if (caller.isInterrupted) throw InterruptedException()
            val tiles = byPage[index].orEmpty().map { Tile(it.texture, placements.getValue(it.id), masks[it.id]) }
            page(pageSize, tiles, previous?.pages?.getOrNull(index), caller)
        }
        val pages: List<AtlasPage> = (if (pageCount > 1) compose.parallel() else compose).toList()
        if (caller.isInterrupted) throw InterruptedException()
        return PackedAtlas(pages, placements, solved.fit, solved.notices, solved.footprints, arrangement != null)
    }

    /** One tile to draw on a page: its texture, its rectangle and, with a footprint, the only cells it may write. */
    private class Tile(val texture: SourceLayer, val at: AtlasPlacement, val mask: AtlasArrange.Shape?)

    private fun request(item: Item, fit: Double, stored: ArrangedTile?, footprint: TextureFootprint?, honour: Boolean = true): AtlasArrange.Request =
        AtlasArrange.Request(item.id, item.layer.source.name, item.width(fit, honour), item.height(fit, honour),
            item.texture.raster.width, item.texture.raster.height, stored, footprint)

    /** [item]'s footprint as it holds for its raster now ([AtlasArrange.covering]); null when it no longer does. */
    private fun covering(item: Item, footprint: TextureFootprint?): TextureFootprint? =
        footprint?.let { AtlasArrange.covering(it, item.texture.raster) }

    /**
     * [item]'s request at a stored arrangement's [fit] - or, when a page cannot hold it there (a dense raster
     * placed or imported after the arrangement was stored, say), at the largest step that it can, lock or not.
     */
    private fun keptRequest(item: Item, fit: Double, stored: ArrangedTile?, pageSize: Int, padding: Int): AtlasArrange.Request {
        val room = pageSize - padding * 2
        val footprint = covering(item, stored?.footprint)
        if (maxOf(item.width(fit, true), item.height(fit, true)) <= room) return request(item, fit, stored, footprint)
        val step = Math.floor(room / maxOf(item.baseWidth, item.baseHeight) * AtlasArrangement.FIT_STEPS).toInt().coerceIn(1, AtlasArrangement.FIT_STEPS)
        return request(item, minOf(fit, step.toDouble() / AtlasArrangement.FIT_STEPS), stored, footprint, honour = false)
    }

    private fun placement(item: Item, request: AtlasArrange.Request, spot: AtlasArrange.Spot) = AtlasPlacement(spot.page, spot.x, spot.y,
        request.width, request.height, request.width.toFloat() / item.texture.raster.width, request.height.toFloat() / item.texture.raster.height,
        spot.rotation)

    /**
     * A stored arrangement's layout: its fit for every unlocked tile, its spots for the tiles that still hold
     * there, free space for the rest ([AtlasArrange.keep]), each by its footprint where it has one. A footprint holds
     * with the art painted outside it since added, and not at all once its raster was resized
     * ([AtlasArrange.covering]): a tile painted past its footprint claims those cells, so a neighbour nested
     * there moves instead of the two writing the same cells.
     */
    private fun kept(items: List<Item>, arrangement: AtlasArrangement, pageSize: Int, padding: Int, maxPages: Int): Solved {
        val fit = arrangement.fit
        val requests = items.map { item -> keptRequest(item, fit, arrangement.tiles[item.id], pageSize, padding) }
        val kept = AtlasArrange.keep(requests, pageSize, padding, maxPages)
        val placements = LinkedHashMap<String, AtlasPlacement>()
        val masks = HashMap<String, AtlasArrange.Shape>()
        val footprints = HashMap<String, TextureFootprint>()
        for ((item, request) in items.zip(requests)) {
            val spot = kept.spots.getValue(item.id)
            placements[item.id] = placement(item, request, spot)
            // A tile that lost its spot was searched a new one by the same footprint, so it may nest there too and
            // must write only its cells there as well, never its whole rectangle over the neighbour it nests by.
            if (request.footprint != null) { masks[item.id] = kept.shapes.getValue(item.id); footprints[item.id] = request.footprint }
        }
        val notices = ArrayList<String>()
        // Tiles new to the arrangement or that lost their stored spot fill free space quietly.
        if (kept.pageCount > maxPages) notices += "Textures need ${kept.pageCount} atlas pages, more than the budget of $maxPages."
        if (arrangement.fitStep < AtlasArrangement.FIT_STEPS)
            notices += "Textures are scaled to ${String.format(Locale.ROOT, "%.1f", fit * 100)}% to fit the atlas budget of $maxPages page(s) of ${pageSize}px."
        return Solved(placements, kept.pageCount, fit.toFloat(), notices, masks, footprints)
    }

    /**
     * A compact arrangement of [layers] under [config]'s budget, to store once. Tiles with a mesh footprint in
     * [footprints] are placed by their meshes, the others by their rectangles, largest first, at the largest fit
     * (to 8/[FIT_STEPS]) at which all fit within the budget's pages. With [only], every other tile
     * keeps its spot and footprint in [current] and the current fit holds. Null when nothing fits.
     */
    fun arrange(layers: List<ClassifiedLayer>, config: PipelineConfig, footprints: Map<String, TextureFootprint>,
                current: PackedAtlas?, only: Set<String>? = null): AtlasArrangement? {
        val budget = config.effectiveAtlasBudget()
        val padding = budget.padding.coerceIn(0, 32)
        val items = items(layers, config.textureUpscale.scale, config.textureOverrides)
        val pageSize = pageSize(items, budget.pageSize, padding)
        val fixedItems = if (only == null || current == null) emptyList() else items.filter { it.id !in only && it.id in current.placementByLayerId }
        val moving = items.filter { it !in fixedItems }
        val footprintOf = moving.associate { it.id to covering(it, footprints[it.id]) }
        fun attempt(step: Int): AtlasArrangement? {
            val fit = step.toDouble() / AtlasArrangement.FIT_STEPS
            val fixed = fixedItems.map { item ->
                val at = current!!.placementByLayerId.getValue(item.id)
                Triple(item, AtlasArrange.Spot(at.page, at.x, at.y, at.rotation),
                    AtlasArrange.shape(at.x, at.y, at.width, at.height, item.texture.raster.width, item.texture.raster.height, current.footprints[item.id], at.rotation))
            }
            val requests = moving.map { request(it, fit, null, footprintOf[it.id]) }
            val spots = AtlasArrange.arrange(requests, fixed.map { it.second to it.third }, pageSize, padding, budget.maxPages) ?: return null
            val tiles = HashMap<String, ArrangedTile>()
            for ((item, spot, _) in fixed) tiles[item.id] = ArrangedTile(spot.page, spot.x, spot.y, current!!.footprints[item.id], spot.rotation)
            for (request in requests) spots.getValue(request.id).let { tiles[request.id] = ArrangedTile(it.page, it.x, it.y, request.footprint) }
            return AtlasArrangement(step, tiles)
        }
        if (fixedItems.isNotEmpty()) {
            val step = Math.round(current!!.fit * AtlasArrangement.FIT_STEPS).coerceIn(1, AtlasArrangement.FIT_STEPS)
            return attempt(step)
        }
        if (moving.none { footprintOf[it.id] != null }) rectangles(items, pageSize, padding, budget.maxPages)?.let { return it }
        attempt(AtlasArrangement.FIT_STEPS)?.let { return it }
        // Coarse steps of 64 first, then the step between the best and the next is narrowed down to 8.
        var low = 1; var high = AtlasArrangement.FIT_STEPS / 64 - 1
        var best: AtlasArrangement? = null
        while (low <= high) {
            val middle = (low + high) ushr 1
            val tried = attempt(middle * 64)
            if (tried != null) { best = tried; low = middle + 1 } else high = middle - 1
        }
        var fine = 64
        while (fine > 8) {
            fine /= 2
            val step = (best?.fitStep ?: 0) + fine
            if (step < AtlasArrangement.FIT_STEPS) attempt(step)?.let { best = it }
        }
        return best
    }

    /**
     * The automatic layout of [items] as an arrangement: by rectangles, at the finest fit. Null when it needs more
     * pages than the budget or drops a lock, which the cell search below then handles.
     */
    private fun rectangles(items: List<Item>, pageSize: Int, padding: Int, maxPages: Int): AtlasArrangement? {
        val result = solve(items, pageSize, padding, maxPages)
        if (result.pageCount > maxPages) return null
        val step = Math.round(result.fit * AtlasArrangement.FIT_STEPS).coerceIn(1, AtlasArrangement.FIT_STEPS)
        val fit = step.toDouble() / AtlasArrangement.FIT_STEPS
        val tiles = HashMap<String, ArrangedTile>()
        for (item in items) {
            val at = result.placements.getValue(item.id)
            if (at.width != item.width(fit, true) || at.height != item.height(fit, true)) return null
            tiles[item.id] = ArrangedTile(at.page, at.x, at.y, null)
        }
        return AtlasArrangement(step, tiles)
    }

    /** [atlas]'s layout as a stored arrangement, to keep it exactly as it is. */
    fun frozen(atlas: PackedAtlas): AtlasArrangement {
        val step = Math.round(atlas.fit * AtlasArrangement.FIT_STEPS).coerceIn(1, AtlasArrangement.FIT_STEPS)
        return AtlasArrangement(step, atlas.placementByLayerId.mapValues { (id, at) -> ArrangedTile(at.page, at.x, at.y, atlas.footprints[id], at.rotation) })
    }

    private fun items(layers: List<ClassifiedLayer>, scale: Int, overrides: Map<String, TextureOverride>): List<Item> =
        layers.filter { it.source.raster.width > 0 && it.source.raster.height > 0 && it.opaquePixels > 0 }
            .map { layer ->
                val texture = layer.source.textureLayer
                val override = overrides[layer.source.id.raw]
                val density = override?.density?.toDouble() ?: 1.0
                // A dense texture's canvas extent is its bounds; any other raster is its own canvas extent, as always.
                val canvas = if (CanvasDensity.dense(texture)) texture.bounds.width to texture.bounds.height
                    else layer.source.raster.width to layer.source.raster.height
                Item(layer, texture,
                    Math.multiplyExact(texture.raster.width, scale) * density, Math.multiplyExact(texture.raster.height, scale) * density,
                    Math.multiplyExact(canvas.first, scale), Math.multiplyExact(canvas.second, scale),
                    override?.lock == true, override?.pin)
            }
            .sortedWith(compareByDescending<Item> { it.baseH }.thenByDescending { it.baseW }.thenBy { it.id })

    /**
     * The page side: the budget's, grown to the next power of two that holds the largest layer at canvas
     * resolution (as pages always have), at most 16384. Denser tiles that do not fit are scaled by the fit.
     */
    private fun pageSize(items: List<Item>, requestedSize: Int, safePadding: Int): Int {
        val safeSize = requestedSize.coerceIn(256, 16384)
        val largest = items.maxOfOrNull { maxOf(it.canvasWidth, it.canvasHeight) + safePadding * 2 } ?: safeSize
        return maxOf(safeSize, nextPowerOfTwo(largest)).coerceAtMost(16384)
    }

    private class Solved(val placements: Map<String, AtlasPlacement>, val pageCount: Int, val fit: Float, val notices: List<String>,
                         val masks: Map<String, AtlasArrange.Shape> = emptyMap(), val footprints: Map<String, TextureFootprint> = emptyMap())

    private class Layout(val placements: Map<String, AtlasPlacement>, val pageCount: Int)

    /**
     * Places [items] within [maxPages] pages: at fit 1 when they fit, else at the largest fit (a multiple of
     * 1/[FIT_STEPS]) that does, applied to every unlocked tile alike. When locked and pinned tiles alone do
     * not fit, they lose their lock and pin and a notice says so; when nothing fits, pages are added.
     */
    private fun solve(items: List<Item>, pageSize: Int, padding: Int, maxPages: Int): Solved {
        // The layout reads only the tiles' sizes, locks and pins, so most commits find it here.
        val key = SolveKey(items.map { listOf(it.id, it.baseWidth, it.baseHeight, it.locked, it.pin) }, pageSize, padding, maxPages)
        synchronized(solved) { solved[key] }?.let { return it }
        return solveUncached(items, pageSize, padding, maxPages).also { synchronized(solved) { solved[key] = it } }
    }

    private data class SolveKey(val items: List<List<Any?>>, val pageSize: Int, val padding: Int, val maxPages: Int)

    private val solved = object : LinkedHashMap<SolveKey, Solved>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<SolveKey, Solved>?) = size > 8
    }

    private fun solveUncached(items: List<Item>, pageSize: Int, padding: Int, maxPages: Int): Solved {
        layout(items, 1.0, pageSize, padding, maxPages, honour = true)?.let { return Solved(it.placements, it.pageCount, 1f, emptyList()) }
        val notices = ArrayList<String>()
        var honour = true
        if (items.any { it.locked || it.pin != null } && layout(items, 1.0 / FIT_STEPS, pageSize, padding, maxPages, honour = true) == null) {
            notices += "Locked or pinned textures (" + items.filter { it.locked || it.pin != null }.joinToString { it.layer.source.name } +
                ") do not fit the atlas budget of $maxPages page(s) of ${pageSize}px; they are scaled and placed with the other textures."
            honour = false
        }
        // The largest step that fits; packing is not strictly monotonic in the fit, but the search is deterministic.
        var low = 1; var high = FIT_STEPS - 1
        var best: Layout? = null
        var bestStep = 0
        while (low <= high) {
            val middle = (low + high) ushr 1
            val tried = layout(items, middle.toDouble() / FIT_STEPS, pageSize, padding, maxPages, honour)
            if (tried != null) { best = tried; bestStep = middle; low = middle + 1 } else high = middle - 1
        }
        if (best != null) {
            val fit = bestStep.toDouble() / FIT_STEPS
            notices += "Textures are scaled to ${String.format(Locale.ROOT, "%.1f", fit * 100)}% to fit the atlas budget of $maxPages page(s) of ${pageSize}px."
            return Solved(best.placements, best.pageCount, fit.toFloat(), notices)
        }
        val fallback = requireNotNull(layout(items, 1.0, pageSize, padding, UNLIMITED_PAGES, honour = false)) { tr("error.layerTooLarge") }
        notices += "Textures need ${fallback.pageCount} atlas pages, more than the budget of $maxPages."
        return Solved(fallback.placements, fallback.pageCount, 1f, notices)
    }

    /**
     * The layout of [items] at [fit], or null when it needs more than [maxPages] pages, a tile is larger than a
     * page, or (while [honour]ing locks and pins) a pin lies outside the budget or on another pinned tile.
     * Pinned tiles take their spot first. The others are packed by [RectPack] - each tile with [padding] on every
     * side, so neighbours keep twice that apart - in a few orders and with both heuristics; the layout with the
     * fewest pages and then the least used height on its last page wins.
     */
    private fun layout(items: List<Item>, fit: Double, pageSize: Int, padding: Int, maxPages: Int, honour: Boolean): Layout? {
        val pinned = HashMap<String, AtlasPlacement>()
        val obstacles = HashMap<Int, MutableList<IntArray>>()
        fun sized(item: Item, page: Int, x: Int, y: Int): AtlasPlacement {
            val width = item.width(fit, honour); val height = item.height(fit, honour)
            return AtlasPlacement(page, x, y, width, height, width.toFloat() / item.texture.raster.width, height.toFloat() / item.texture.raster.height)
        }
        fun clash(a: AtlasPlacement, b: AtlasPlacement): Boolean = a.page == b.page &&
            b.x < a.x + a.width + padding * 2 && a.x < b.x + b.width + padding * 2 && b.y < a.y + a.height + padding * 2 && a.y < b.y + b.height + padding * 2
        if (honour) for (item in items) {
            val pin = item.pin ?: continue
            val placed = sized(item, pin.page, pin.x, pin.y)
            if (pin.page >= maxPages || pin.x + placed.width + padding > pageSize || pin.y + placed.height + padding > pageSize) return null
            if (pinned.values.any { clash(it, placed) }) return null
            obstacles.getOrPut(pin.page) { ArrayList() } += intArrayOf(pin.x - padding, pin.y - padding, placed.width + padding * 2, placed.height + padding * 2)
            pinned[item.id] = placed
        }
        val free = items.filter { it.id !in pinned }
        val tiles = free.map { sized(it, 0, 0, 0) }
        val sizes = tiles.map { intArrayOf(it.width + padding * 2, it.height + padding * 2) }
        var best: RectPack.Packed? = null
        var bestOrder: IntArray? = null
        for (order in ORDERS) {
            val indices = free.indices.sortedWith(order(tiles)).toIntArray()
            val ordered = indices.map { sizes[it] }
            for (heuristic in RectPack.Heuristic.entries) {
                val packed = RectPack.pack(ordered, pageSize, maxPages, obstacles, heuristic) ?: continue
                val current = best
                if (current == null || packed.pageCount < current.pageCount ||
                    packed.pageCount == current.pageCount && packed.lastBottom < current.lastBottom) { best = packed; bestOrder = indices }
            }
        }
        val packed = best ?: return null
        val order = bestOrder!!
        val spots = HashMap<String, AtlasPlacement>()
        for ((k, index) in order.withIndex()) {
            val spot = packed.spots[k]
            spots[free[index].id] = tiles[index].copy(page = spot[0], x = spot[1] + padding, y = spot[2] + padding)
        }
        val placements = LinkedHashMap<String, AtlasPlacement>()
        for (item in items) placements[item.id] = pinned[item.id] ?: spots.getValue(item.id)
        val lastPinnedPage = pinned.values.maxOfOrNull { it.page } ?: -1
        return Layout(placements, maxOf(packed.pageCount, lastPinnedPage + 1))
    }

    /** The orders [layout] tries, largest first by area, by the longer side and by height; ties by size, then input order. */
    private val ORDERS: List<(List<AtlasPlacement>) -> Comparator<Int>> = listOf(
        { t -> compareByDescending<Int> { t[it].width.toLong() * t[it].height }.thenByDescending { t[it].height }.thenBy { it } },
        { t -> compareByDescending<Int> { maxOf(t[it].width, t[it].height) }.thenByDescending { minOf(t[it].width, t[it].height) }.thenBy { it } },
        { t -> compareByDescending<Int> { t[it].height }.thenByDescending { t[it].width }.thenBy { it } },
    )

    /** One tile of a 1:1 page: its rectangle, the digest of the raster drawn there and the cells it may write ("" for all). */
    internal data class TileKey(val x: Int, val y: Int, val width: Int, val height: Int, val digest: String, val mask: String = "",
                                val rotation: Float = 0f) {
        /** The page rows the tile writes: its rectangle's, or its turned box's. */
        val rows: IntRange get() = if (rotation == 0f) y until y + height else
            TileTurn.bounds(x.toFloat(), y.toFloat(), width.toFloat(), height.toFloat(), rotation).let { b -> kotlin.math.floor(b[1]).toInt() until kotlin.math.ceil(b[3]).toInt() }
    }

    /** All a 1:1 page's pixels depend on: its side and its tiles, top to bottom. */
    internal data class Recipe(val size: Int, val tiles: List<TileKey>)

    private val pageCache = object : LinkedHashMap<Recipe, SoftReference<AtlasPage>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Recipe, SoftReference<AtlasPage>>?) = size > PAGE_CACHE_CAPACITY
    }
    private const val PAGE_CACHE_CAPACITY = 8

    /**
     * One page of [tiles] (texture layers and where they go): [base] itself when it has the same recipe, else a
     * cached page of that recipe, else a newly composed one. A composed page differs from [base] - the page it
     * replaces - only in the rows of the tiles the two recipes do not share, so its preview encoding reuses
     * [base]'s other strips. A tile whose size is not its raster's is resampled ([RasterResample]); its digest
     * and size together still determine its pixels.
     */
    private fun page(size: Int, tiles: List<Tile>, base: AtlasPage?, caller: Thread): AtlasPage {
        val recipe = Recipe(size, tiles.map { tile ->
            TileKey(tile.at.x, tile.at.y, tile.at.width, tile.at.height, RasterDigest.of(tile.texture.raster.rgba), tile.mask?.key ?: "", tile.at.rotation)
        }.sortedWith(compareBy<TileKey> { it.y }.thenBy { it.x }))
        if (base?.recipe == recipe) return base
        synchronized(pageCache) { pageCache[recipe]?.get()?.let { return it } }
        val dirtyRows = base?.recipe?.takeIf { it.size == size }?.let { old ->
            val kept = old.tiles.toHashSet().apply { retainAll(recipe.tiles.toSet()) }
            java.util.BitSet().apply { for (tile in old.tiles + recipe.tiles) if (tile !in kept) tile.rows.let { set(maxOf(0, it.first), maxOf(0, it.last + 1)) } }
        }
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        // The page's own pixels (ARGB, as setRGB stores them on this image type), written directly.
        val data = (image.raster.dataBuffer as java.awt.image.DataBufferInt).data
        for (tile in tiles) {
            if (caller.isInterrupted) throw InterruptedException()
            val raster = tile.texture.raster; val at = tile.at
            val rgba = RasterResample.resize(raster.rgba, raster.width, raster.height, at.width, at.height)
            // Direct pixel copy retains RGB even when alpha is zero (Graphics may discard it).
            val pixels = argb(rgba, at.width * at.height)
            val mask = tile.mask
            if (at.rotation != 0f) writeTurned(data, size, size, at, pixels, mask)
            else if (mask == null) {
                // As setRGB would, refuse a rectangle off the page.
                if (at.x < 0 || at.y < 0 || at.x + at.width > size || at.y + at.height > size) throw ArrayIndexOutOfBoundsException("Coordinate out of bounds!")
                for (row in 0 until at.height) System.arraycopy(pixels, row * at.width, data, (at.y + row) * size + at.x, at.width)
            }
            else writeMasked(image, at, mask) { x, y, length, offset -> System.arraycopy(pixels, offset, data, y * size + x, length) }
        }
        val page = AtlasPage.composed(image, recipe, base, dirtyRows)
        synchronized(pageCache) { pageCache[recipe] = SoftReference(page) }
        return page
    }

    /**
     * The runs of [at] that [mask] owns, row by row, as (page x, page y, length, offset into the tile's pixels): a
     * tile arranged by its meshes writes only its own cells, so a neighbour reaching into its rectangle keeps its pixels.
     */
    private inline fun writeMasked(image: BufferedImage, at: AtlasPlacement, mask: AtlasArrange.Shape, write: (Int, Int, Int, Int) -> Unit) {
        for (y in at.y until minOf(at.y + at.height, image.height)) {
            val r = y / AtlasArrange.CELL - mask.row
            if (r !in 0 until mask.height) continue
            val run = mask.runs[r]
            for (k in run.indices step 2) {
                val from = maxOf(at.x, (mask.column + run[k]) * AtlasArrange.CELL)
                val to = minOf(at.x + at.width, (mask.column + run[k + 1]) * AtlasArrange.CELL, image.width)
                if (to > from) write(from, y, to - from, (y - at.y) * at.width + (from - at.x))
            }
        }
    }

    /**
     * A turned tile: every page pixel of its turned box that its cells ([mask]) own and its rectangle covers takes the
     * tile's pixel there, [pixels] (ARGB, the tile's size) sampled bilinearly through the turn, colours weighted by
     * alpha so transparent texels lend no colour.
     */
    private fun writeTurned(image: BufferedImage, at: AtlasPlacement, pixels: IntArray, mask: AtlasArrange.Shape?) {
        val buffer = image.raster.dataBuffer
        if (image.type == BufferedImage.TYPE_INT_ARGB && buffer is java.awt.image.DataBufferInt && buffer.numBanks == 1 &&
            buffer.data.size == image.width * image.height) return writeTurned(buffer.data, image.width, image.height, at, pixels, mask)
        val data = IntArray(image.width * image.height)
        image.getRGB(0, 0, image.width, image.height, data, 0, image.width)
        writeTurned(data, image.width, image.height, at, pixels, mask)
        image.setRGB(0, 0, image.width, image.height, data, 0, image.width)
    }

    /** [writeTurned] into [data], the ARGB pixels of a [pageWidth] x [pageHeight] page. */
    private fun writeTurned(data: IntArray, pageWidth: Int, pageHeight: Int, at: AtlasPlacement, pixels: IntArray, mask: AtlasArrange.Shape?) {
        val box = TileTurn.bounds(at.x.toFloat(), at.y.toFloat(), at.width.toFloat(), at.height.toFloat(), at.rotation)
        val x0 = maxOf(0, kotlin.math.floor(box[0]).toInt()); val x1 = minOf(pageWidth, kotlin.math.ceil(box[2]).toInt())
        val y0 = maxOf(0, kotlin.math.floor(box[1]).toInt()); val y1 = minOf(pageHeight, kotlin.math.ceil(box[3]).toInt())
        val w = at.width; val h = at.height
        fun owned(px: Int, py: Int): Boolean {
            if (mask == null) return true
            val r = py / AtlasArrange.CELL - mask.row
            if (r !in 0 until mask.height) return false
            val c = px / AtlasArrange.CELL - mask.column
            val run = mask.runs[r]
            for (k in run.indices step 2) if (c >= run[k] && c < run[k + 1]) return true
            return false
        }
        for (py in y0 until y1) for (px in x0 until x1) {
            if (!owned(px, py)) continue
            val local = TileTurn.toTile(at.x.toFloat(), at.y.toFloat(), w.toFloat(), h.toFloat(), at.rotation, px + 0.5f, py + 0.5f)
            val lx = local[0]; val ly = local[1]
            if (lx < 0f || ly < 0f || lx >= w || ly >= h) continue
            val fx = lx - 0.5f; val fy = ly - 0.5f
            val ix = kotlin.math.floor(fx).toInt(); val iy = kotlin.math.floor(fy).toInt()
            val tx = fx - ix; val ty = fy - iy
            var a = 0f; var r = 0f; var g = 0f; var b = 0f
            fun add(dx: Int, dy: Int, weight: Float) {
                if (weight == 0f) return
                val c = pixels[(iy + dy).coerceIn(0, h - 1) * w + (ix + dx).coerceIn(0, w - 1)]
                val alpha = (c ushr 24) / 255f * weight
                a += alpha; r += (c ushr 16 and 0xff) * alpha; g += (c ushr 8 and 0xff) * alpha; b += (c and 0xff) * alpha
            }
            add(0, 0, (1 - tx) * (1 - ty)); add(1, 0, tx * (1 - ty)); add(0, 1, (1 - tx) * ty); add(1, 1, tx * ty)
            if (a <= 0f) { data[py * pageWidth + px] = 0; continue }
            data[py * pageWidth + px] = ((Math.round(a * 255f).coerceIn(0, 255) shl 24) or (Math.round(r / a).coerceIn(0, 255) shl 16) or
                (Math.round(g / a).coerceIn(0, 255) shl 8) or Math.round(b / a).coerceIn(0, 255))
        }
    }

    private fun argb(rgba: ByteArray, count: Int): IntArray = IntArray(count) { index ->
        val offset = index * 4
        ((rgba[offset + 3].toInt() and 0xff) shl 24) or ((rgba[offset].toInt() and 0xff) shl 16) or
            ((rgba[offset + 1].toInt() and 0xff) shl 8) or (rgba[offset + 2].toInt() and 0xff)
    }

    /**
     * Upscaled pages read each tile from the inference output - resampled when the density or fit make the tile
     * another size - and extrude its edge into the padding.
     */
    private fun upscaledPages(items: List<Item>, placements: Map<String, AtlasPlacement>, masks: Map<String, AtlasArrange.Shape>, pageSize: Int, pageCount: Int,
                              padding: Int, textures: Map<String, Path>): List<AtlasPage> = (0 until pageCount).map { index ->
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        val page = BufferedImage(pageSize, pageSize, BufferedImage.TYPE_INT_ARGB)
        for (item in items) {
            val placement = placements.getValue(item.id)
            if (placement.page != index) continue
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val upscaled = requireNotNull(ImageIO.read(textures.getValue(item.id).toFile())) { "Invalid upscaled PNG" }
            val width = placement.width; val height = placement.height
            val image = if (upscaled.width == width && upscaled.height == height) upscaled else {
                val pixels = upscaled.getRGB(0, 0, upscaled.width, upscaled.height, null, 0, upscaled.width)
                val rgba = ByteArray(pixels.size * 4)
                for (i in pixels.indices) {
                    val p = pixels[i]
                    rgba[i * 4] = (p ushr 16).toByte(); rgba[i * 4 + 1] = (p ushr 8).toByte(); rgba[i * 4 + 2] = p.toByte(); rgba[i * 4 + 3] = (p ushr 24).toByte()
                }
                PreviewRenderer.rasterImage(width, height, RasterResample.resize(rgba, upscaled.width, upscaled.height, width, height))
            }
            require(image.colorModel.hasAlpha()) { "Upscaled RGBA texture size mismatch" }
            val pixels = image.getRGB(0, 0, width, height, null, 0, width)
            val mask = masks[item.id]
            if (placement.rotation != 0f) {
                // A turned tile writes through its turn; its edge is not extruded.
                writeTurned(page, placement, pixels, mask)
                continue
            }
            if (mask != null) {
                // A mesh-arranged tile writes only its own cells; its edge is not extruded over a neighbour.
                writeMasked(page, placement, mask) { x, y, length, offset -> page.setRGB(x, y, length, 1, pixels, offset, width) }
                continue
            }
            page.setRGB(placement.x, placement.y, width, height, pixels, 0, width)
            if (padding > 0) {
                for (dy in -padding until height + padding) for (dx in -padding until width + padding) {
                    if (dx in 0 until width && dy in 0 until height) continue
                    val tx = placement.x + dx; val ty = placement.y + dy
                    if (tx in 0 until pageSize && ty in 0 until pageSize) {
                        page.setRGB(tx, ty, image.getRGB(dx.coerceIn(0, width - 1), dy.coerceIn(0, height - 1)) and 0x00ffffff)
                    }
                }
            }
        }
        AtlasPage(page)
    }

    private fun nextPowerOfTwo(value: Int): Int {
        var result = 1
        while (result < value && result < 16384) result = result shl 1
        return result
    }
}
