package io.github.psd2live.core

import org.umamo.format.art.LayerBounds
import org.umamo.format.art.LayerRaster
import org.umamo.format.art.SourceArt
import org.umamo.format.art.SourceLayer
import org.umamo.runtime.model.DrawableMesh
import kotlinx.serialization.json.*
import java.awt.image.BufferedImage
import io.github.psd2live.core.legacy.RigGenerationTextures

/** Rebuild geometry from its saved input while packing the currently painted pixels. */
internal object RigGenerationSource {
    data class Analyses(val geometry: PipelineAnalysis, val textures: PipelineAnalysis)

    fun analyze(source: SourceArt, config: PipelineConfig): PipelineAnalysis = AnalysisMemo.get(source, config.generationSource, config, "source") {
        val input = config.generationSource?.let { geometrySource(source, it, config.rigEdits) } ?: source
        // The analysis stands for the current art, so it composites that, never the generation input.
        val analysis = CharacterAnalyzer.analyze(input, RigLayerDeletion.generationConfig(config)) { PreviewRenderer.composite(source) }
        if (input === source) analysis else analysis.copy(source = source)
    }

    /**
     * The last few analyses, by their inputs: the very source and generation source objects, and the configuration
     * without its texture layout. A texture atlas edit (a moved tile, a density, a lock, an arrangement) changes only
     * the layout, which no analysis reads, so its rebuild takes the analyses of the version it starts from instead of
     * classifying every layer again - as umamo moves a tile without rebuilding the puppet.
     */
    private object AnalysisMemo {
        private class Key(val source: SourceArt, val reference: SourceArt?, val config: PipelineConfig, val stage: String) {
            override fun equals(other: Any?) = other is Key && source === other.source && reference === other.reference &&
                stage == other.stage && config == other.config
            override fun hashCode() = (System.identityHashCode(source) * 31 + System.identityHashCode(reference)) * 31 + stage.hashCode()
        }
        private val entries = object : LinkedHashMap<Key, PipelineAnalysis>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, PipelineAnalysis>?) = size > 4
        }

        fun get(source: SourceArt, reference: SourceArt?, config: PipelineConfig, stage: String, analyze: () -> PipelineAnalysis): PipelineAnalysis {
            val key = Key(source, reference, config.copy(textureOverrides = emptyMap(), atlasArrangement = null), stage)
            synchronized(entries) { entries[key] }?.let { return it }
            return analyze().also { synchronized(entries) { entries[key] = it } }
        }
    }

    fun prepare(input: PipelineAnalysis, config: PipelineConfig, textureConfig: PipelineConfig = config): Analyses {
        val reference = config.generationSource ?: return RigBuildProfile.stage("prepare: mouth lips") {
            MouthLipLayers.prepare(PrimitiveResolution.of(config.rigEdits).let { if (it.active) currentParts(input, it, config) else input }, config)
        }.let { Analyses(it, it) }
        val resolution = PrimitiveResolution.of(config.rigEdits)
        val geometryAnalysis = RigBuildProfile.stage("prepare: geometry analyze") {
            // Generation reads no composite: the geometry analysis carries the current art's, as the texture one does.
            AnalysisMemo.get(input.source, reference, config, "geometry") {
                CharacterAnalyzer.analyze(geometrySource(input.source, reference, config.rigEdits), config) { input.preview }
            }
        }
            .let { if (resolution.active) resolvedAnalysis(it, resolution, config) else it }
        val geometry = RigBuildProfile.stage("prepare: geometry mouth lips") { MouthLipLayers.prepare(geometryAnalysis, config) }
        val current = input.source.layers.associateBy { it.id.raw }
        val creationCoverage = config.rigEdits.authoringJournal.filter { it["op"]?.jsonPrimitive?.contentOrNull == RasterMeshCreation.OP }
            .associate { command ->
                command.getValue("layer_id").jsonPrimitive.content to RasterMeshCreation.sourceBounds(command)
            }
        val meshSources = (config.meshSource ?: reference).layers.associateBy { it.id.raw }
        val lipInputs = geometry.copy(layers = geometry.layers.filter { it.source !is MouthLipLayer }.map { layer ->
            meshSources[layer.source.id.raw]?.let { source -> CharacterAnalyzer.classify(source, textureConfig) } ?: layer
        })
        val owned = ArtPrimitiveJournal.ownedLayers(config.rigEdits)
        val superseded = ArtPrimitiveJournal.supersededLayers(config.rigEdits)
        val primitiveCoverage = ArtPrimitiveJournal.coverage(config.rigEdits)
        fun gone(id: String) = id !in current && superseded.any { id == it || id.startsWith("$it:") }
        val generated = RigBuildProfile.stage("prepare: texture lips") { (if (RigGenerationBaseline.present(config.rigEdits))
            RigGenerationTextures.layers(config.meshSource ?: input.source, textureConfig) else
            MouthLipLayers.prepare(lipInputs, textureConfig).layers.filter { it.source is MouthLipLayer }) }
            // Primitive layers are never generated, so they own no ribbons; a superseded mouth keeps its ribbons.
            .filterNot { layer -> (layer.source as? MouthLipLayer)?.ownerId?.let { it in owned } == true }
            .associateBy { it.source.id.raw }
        val texturesStart = System.nanoTime()
        val textures = geometry.copy(source = input.source, preview = input.preview, layers = geometry.layers.map { layer ->
            // Keeping a generated ribbon's mesh also keeps its generated raster and contour.
            val lip = layer.source as? MouthLipLayer
            if (lip != null) {
                val owner = current[lip.ownerId] ?: lip
                val visible = visible(owner.raster)
                val replacement = if (visible) generated[lip.id.raw]?.source else null
                val source = replacement ?: object : SourceLayer by lip {
                    override val raster = LayerRaster(lip.raster.width, lip.raster.height, ByteArray(lip.raster.rgba.size))
                }
                val covered = padded(source, lip.bounds)
                layer.copy(source = MouthLipLayer(lip.ownerId, lip.side, owner, covered.raster, covered.bounds))
            } else if (gone(layer.source.id.raw)) {
                // Superseded by an art primitive: only journal entries before that record address it.
                layer.copy(source = ArtPrimitiveJournal.placeholder(layer.source), opaquePixels = 1)
            } else {
                val artwork = current[layer.source.id.raw] ?: current.entries
                    .filter { layer.source.id.raw.startsWith("${it.key}:") }.maxByOrNull { it.key.length }?.value
                    ?.let { parent -> object : SourceLayer by parent {
                        override val id = layer.source.id
                        override val name = layer.source.name
                    } } ?: error("Generation layer has no current artwork")
                val classified = resolution.classify(CharacterAnalyzer.classify(artwork, textureConfig), textureConfig)
                val covered = padded(artwork, layer.source.bounds).let { source -> creationCoverage[artwork.id.raw]?.let { padded(source, it) } ?: source }
                layer.copy(source = covered, semantic = classified.semantic, bounds = classified.bounds,
                    centroidX = classified.centroidX, centroidY = classified.centroidY,
                    opaquePixels = classified.opaquePixels.coerceAtLeast(1))
            }
        } + generated.values.filter { generatedLayer -> geometry.layers.none { it.source.id == generatedLayer.source.id } }.map { layer ->
            val lip = layer.source as MouthLipLayer
            val owner = current.getValue(lip.ownerId)
            val visible = visible(owner.raster)
            val source = if (visible) lip else MouthLipLayer(lip.ownerId, lip.side, owner,
                LayerRaster(lip.raster.width, lip.raster.height, ByteArray(lip.raster.rgba.size)), lip.bounds)
            val covered = creationCoverage[lip.id.raw]?.let { padded(source, it) } ?: source
            layer.copy(source = MouthLipLayer(lip.ownerId, lip.side, owner, covered.raster, covered.bounds), opaquePixels = layer.opaquePixels.coerceAtLeast(1))
        } + primitiveCoverage.filterNot { (id, _) -> resolution.active && geometry.layers.any { it.source.id.raw == id } }.mapNotNull { (id, coverage) ->
            // Primitive layers are textured, never generated. A superseded one keeps a transparent tile.
            val artwork = current[id]?.let { padded(it, coverage) } ?: if (id in superseded) ArtPrimitiveJournal.placeholder(id, id, coverage) else null
            artwork?.let { CharacterAnalyzer.classify(it, textureConfig).let { classified -> classified.copy(opaquePixels = classified.opaquePixels.coerceAtLeast(1)) } }
        })
        if (RigBuildProfile.recording) RigBuildProfile.add("prepare: texture layers", System.nanoTime() - texturesStart)
        return Analyses(geometry, textures)
    }

    internal fun partitionCoverage(overlay: RigEditOverlay): Map<String, LayerBounds> = SourcePartitionJournal.commands(overlay)
        .flatMap(SourcePartitionJournal::pieces).associate { piece ->
            val canvas = piece.getValue("texture_canvas").jsonArray.map { it.jsonPrimitive.float }
            require(canvas.size >= 6 && canvas.size % 2 == 0 && canvas.all(Float::isFinite)) { "Invalid partition texture coverage" }
            val xs = canvas.indices.step(2).map { canvas[it] }; val ys = canvas.indices.step(2).map { canvas[it + 1] }
            val left = kotlin.math.floor(xs.min().toDouble()).toInt(); val top = kotlin.math.floor(ys.min().toDouble()).toInt()
            // A valid mesh can sample a single pixel along one texture axis.
            val right = maxOf(left + 1, kotlin.math.ceil(xs.max().toDouble()).toInt())
            val bottom = maxOf(top + 1, kotlin.math.ceil(ys.max().toDouble()).toInt())
            piece.getValue("layer_id").jsonPrimitive.content to LayerBounds(left, top, right - left, bottom - top)
        }

    /**
     * The document's generation input once [layer] - its pixels before an edit changes them - is pinned in it.
     * A layer added after the input was frozen generates from its current pixels ([geometrySource]), so a
     * repaint or image replacement would silently regenerate its mesh and leave a mesh migration recorded
     * against the old one without a base to replay on. Pinning its previous pixels keeps that base: a repaint
     * keeps the geometry and a rebuild migrates from it. A layer already in the input keeps its entry, and the
     * layers whose geometry other records own (depth-split fronts, art primitive layers, partition pieces)
     * keep their own rules. Without a saved input the whole [current] source becomes it, as before.
     *
     * The layer is pinned as its [trace] meshes it, over its integer bounds: the saved input keeps a layer's bounds
     * and raster but not its float rectangle, so a denser raster or a fractional rectangle would otherwise generate
     * a slightly different mesh than the one it has. The canvas trace reads it at one pixel per canvas unit; the
     * texture trace reads its texture at its own density ([CanvasDensity.alignedRaster]).
     */
    internal fun pinned(reference: SourceArt?, current: SourceArt, layer: SourceLayer, overlay: RigEditOverlay, trace: MeshTrace): SourceArt {
        reference ?: return current
        val id = layer.id.raw
        if (reference.layers.any { it.id.raw == id } || id in DepthSplit.frontLayerIds(overlay) ||
            id in ArtPrimitiveJournal.ownedLayers(overlay) || id in ArtPrimitiveJournal.supersededLayers(overlay) ||
            PrimitiveResolution.of(overlay).isPartLayer(id) || id in partitionCoverage(overlay)) return reference
        return io.github.psd2live.project.WorkspaceSourceArt(reference.widthPx, reference.heightPx,
            reference.layers + if (trace == MeshTrace.TEXTURE && CanvasDensity.dense(layer))
                (io.github.psd2live.project.WorkspaceSourceLayer.copyOf(layer, layer.order) as io.github.psd2live.project.WorkspaceSourceLayer)
                    .copy(raster = CanvasDensity.alignedRaster(layer), rect = null)
            else io.github.psd2live.project.WorkspaceSourceLayer.copyOf(CanvasDensity.canvasLayer(layer), layer.order),
            reference.groups)
    }

    internal fun geometrySource(current: SourceArt, reference: SourceArt, overlay: RigEditOverlay = RigEditOverlay.Empty): SourceArt {
        require(reference.widthPx == current.widthPx && reference.heightPx == current.heightPx) {
            "Generation source dimensions must match the current canvas"
        }
        val previous = reference.layers.associateBy { it.id.raw }
        val partitions = partitionCoverage(overlay)
        val resolution = PrimitiveResolution.of(overlay)
        // Version 2 parts generate like any layer (Rule A); only version 1 parts stay out of the generation.
        val owned = if (resolution.active) resolution.legacyOwnedLayers else ArtPrimitiveJournal.ownedLayers(overlay)
        val superseded = ArtPrimitiveJournal.supersededLayers(overlay)
        val currentById = current.layers.associateBy { it.id.raw }
        // A superseded layer still generates, in the slot its replacements hold, so the frames and identities
        // the journal was written against stay the same up to the record that removes it.
        val restored = reference.layers.filter { it.id.raw in superseded && it.id.raw !in owned && it.id.raw !in currentById }.map { layer ->
            val order = ArtPrimitiveJournal.anchorOrder(overlay, layer.id.raw, currentById) ?: layer.order
            object : SourceLayer by layer { override val order = order }
        }
        // Current metadata and newly added layers remain authoritative; existing raster shapes stay fixed.
        val generation = object : SourceArt by current {
            override val layers = (if (owned.isEmpty() && restored.isEmpty()) current.layers else
                current.layers.filterNot { it.id.raw in owned } + restored).map { layer ->
                if (layer.id.raw in superseded && layer.id.raw !in currentById) return@map layer
                partitions[layer.id.raw]?.let { coverage ->
                    val old = previous[layer.id.raw]?.bounds ?: layer.bounds
                    val left = minOf(old.left, coverage.left); val top = minOf(old.top, coverage.top)
                    val right = maxOf(old.left + old.width, coverage.left + coverage.width)
                    val bottom = maxOf(old.top + old.height, coverage.top + coverage.height)
                    // The ordered partition journal owns this geometry. Keep its texture extent
                    // without adding derived pixels to the durable original generation source.
                    return@map object : SourceLayer by layer {
                        override val bounds = LayerBounds(left, top, right - left, bottom - top)
                        override val raster = LayerRaster(1, 1, ByteArray(4))
                    }
                }
                previous[layer.id.raw]?.let { old -> object : SourceLayer by layer {
                    override val bounds = old.bounds
                    override val raster = old.raster
                } } ?: layer
            }
        }
        return if (resolution.active) pinnedSource(generation, resolution, overlay, currentById) else generation
    }

    /**
     * [generation] with the parts of version 2 records: a part's current pixels padded to the rectangle its mesh
     * samples (painting a part moves its footprint, never its pinned mesh), and a transparent stand-in at that
     * rectangle for a part a later record superseded, which only its own record's replay shows.
     */
    private fun pinnedSource(generation: SourceArt, resolution: PrimitiveResolution, overlay: RigEditOverlay,
                             current: Map<String, SourceLayer>): SourceArt {
        val present = generation.layers.mapTo(HashSet()) { it.id.raw }
        val missing = resolution.parts.filter { it.layerId !in present }.distinctBy { it.layerId }.map { part ->
            val stand = ArtPrimitiveJournal.placeholder(part.layerId, part.name, part.sourceBounds)
            val order = ArtPrimitiveJournal.anchorOrder(overlay, part.layerId, current) ?: 0
            object : SourceLayer by stand { override val order = order }
        }
        return object : SourceArt by generation {
            override val layers = generation.layers.map { layer ->
                resolution.partByLayer[layer.id.raw]?.let { padded(layer, it.sourceBounds) } ?: layer
            } + missing
        }
    }

    /**
     * [input] - the current art, without a saved generation input - with its version 2 parts padded to the
     * rectangle their meshes sample and resolved like [resolvedAnalysis]. Superseded layers are gone from the art,
     * so the base builds no passenger for them.
     */
    private fun currentParts(input: PipelineAnalysis, resolution: PrimitiveResolution, config: PipelineConfig): PipelineAnalysis =
        resolvedAnalysis(input.copy(layers = input.layers.map { layer ->
            resolution.partByLayer[layer.source.id.raw]?.let { layer.copy(source = padded(layer.source, it.sourceBounds)) } ?: layer
        }), resolution, config)

    /**
     * The geometry analysis of a document with version 2 records: parts classified from their layer override
     * (the record's classification otherwise) and never empty, and anchors read off the resolved set only.
     */
    private fun resolvedAnalysis(analysis: PipelineAnalysis, resolution: PrimitiveResolution, config: PipelineConfig): PipelineAnalysis {
        val layers = analysis.layers.map { layer ->
            if (!resolution.isPartLayer(layer.source.id.raw)) layer
            else resolution.classify(layer, config).let { it.copy(opaquePixels = it.opaquePixels.coerceAtLeast(1)) }
        }
        val resolved = resolution.aggregates(layers).filter { it.opaquePixels > 0 }
        return analysis.copy(layers = layers, anchors = analysis.calibration?.anchors
            ?: if (resolved.isEmpty()) analysis.anchors else CharacterAnalyzer.anchorsFor(resolved))
    }

    fun repack(rig: BuiltRig, geometry: PipelineAnalysis, from: PackedAtlas,
               textures: PipelineAnalysis, to: PackedAtlas): BuiltRig {
        val oldLayers = geometry.layers.associateBy { it.source.id.raw }
        val newLayers = textures.layers.associateBy { it.source.id.raw }
        val pages = rig.pageByDrawableId.toMutableMap()
        val drawables = rig.puppet.drawables.map { drawable ->
            val mesh = drawable.mesh ?: return@map drawable
            val layerId = rig.layerIdByDrawableId.getValue(drawable.id.raw)
            val old = requireNotNull(from.placementByLayerId[layerId]) { "Generation atlas layer is missing" }
            val next = requireNotNull(to.placementByLayerId[layerId]) { "Current atlas layer is missing" }
            val oldBounds = oldLayers.getValue(layerId).source.bounds
            val newBounds = newLayers.getValue(layerId).source.bounds
            // A layer whose raster is not its bounds at one pixel per canvas unit maps through its own space;
            // every other one keeps the bounds-sized slice it always had.
            fun slice(atlas: PackedAtlas, placement: AtlasPlacement, layer: ClassifiedLayer): LayerTexture {
                val page = atlas.pages[placement.page]
                return if (layer.source is CanvasDensityLayer || CanvasDensity.dense(layer.source))
                    LayerTexture.packed(layer, placement, page.image.width, page.image.height)
                else LayerTexture.packed(layer.source.bounds, placement, page.image.width, page.image.height)
            }
            val before = slice(from, old, oldLayers.getValue(layerId))
            val after = slice(to, next, newLayers.getValue(layerId))
            val sameAddress = old == next && oldBounds == newBounds &&
                from.pages[old.page].image.width == to.pages[next.page].image.width &&
                from.pages[old.page].image.height == to.pages[next.page].image.height
            val uvs = if (sameAddress) mesh.uvs else before.remap(mesh.uvs, after)
            pages[drawable.id.raw] = next.page
            drawable.copy(mesh = DrawableMesh(mesh.positions, uvs, mesh.indices), texturePage = next.page)
        }
        val (atlas, sources) = PuppetSourceAtlas.build(textures, to)
        // Parked version 2 parts keep canvas-unit texture coordinates; only the page they sample follows the layout.
        val skins = rig.primitiveSkins
        val parked = if (skins.partLayers.isEmpty()) skins else PrimitiveSkins(skins.drawables.mapValues { (id, drawable) ->
            skins.partLayers[id]?.let { layer -> to.placementByLayerId[layer]?.let { drawable.copy(texturePage = it.page) } } ?: drawable
        }, skins.glues, skins.paths, skins.generatedAxes, skins.stubs, skins.generatedMasks, skins.partSlots, skins.ownership,
            skins.deferredParents, skins.partLayers, skins.neutralBounds, skins.replaces, skins.resolved)
        return rig.copy(puppet = rig.puppet.copy(drawables = drawables, atlas = atlas, sources = sources), pageByDrawableId = pages,
            primitiveSkins = parked)
    }

    /**
     * [current] - a layer whose raster is denser or sparser than its canvas rectangle - grown to [bounds] at the
     * same density: the raster is laid over its rectangle inside a transparent raster covering [bounds], so the
     * padded layer keeps its texels and its pixels stay where they were on the canvas.
     */
    private fun paddedAtDensity(current: SourceLayer, bounds: LayerBounds): LayerRaster {
        val space = LayerSpace.of(current)
        val width = Math.round(bounds.width * space.scaleX.toDouble()).toInt().coerceAtLeast(1)
        val height = Math.round(bounds.height * space.scaleY.toDouble()).toInt().coerceAtLeast(1)
        require(width.toLong() * height <= 64L * 1024 * 1024) { "Padded layer exceeds 64 megapixels" }
        // Output pixel i covers canvas bounds.left + i / (width / bounds.width); in raster pixels of [current]
        // that starts at (bounds.left - space.left) * scaleX and is scaleX * bounds.width / width wide.
        val stepX = space.scaleX.toDouble() * bounds.width / width
        val stepY = space.scaleY.toDouble() * bounds.height / height
        val rgba = io.github.psd2live.format.compile.RasterResample.resample(current.raster.rgba, current.raster.width, current.raster.height,
            width, height, (bounds.left - space.left) * space.scaleX.toDouble(), stepX, (bounds.top - space.top) * space.scaleY.toDouble(), stepY)
        return LayerRaster(width, height, rgba)
    }

    /** Whether any pixel of [raster] is not fully transparent, by raster array: a rebuild scans only repainted mouths. */
    private fun visible(raster: LayerRaster): Boolean = visibility[raster.rgba] ?: run {
        val rgba = raster.rgba
        var found = false
        var index = 3
        val end = raster.width * raster.height * 4
        while (index < end) { if (rgba[index] != 0.toByte()) { found = true; break }; index += 4 }
        found.also { visibility[rgba] = it }
    }

    private val visibility = java.util.Collections.synchronizedMap(java.util.WeakHashMap<ByteArray, Boolean>())

    /** Transparent coverage prevents a kept mesh from sampling another layer after a tighter crop. */
    internal fun padded(current: SourceLayer, previous: LayerBounds): SourceLayer {
        val left = minOf(current.bounds.left, previous.left)
        val top = minOf(current.bounds.top, previous.top)
        val right = maxOf(current.bounds.left + current.bounds.width, previous.left + previous.width)
        val bottom = maxOf(current.bounds.top + current.bounds.height, previous.top + previous.height)
        val bounds = LayerBounds(left, top, right - left, bottom - top)
        if (bounds == current.bounds) return current
        val raster = Paddings.get(current, bounds)
        return object : SourceLayer by current {
            override val bounds = bounds
            override val raster = raster
        }
    }

    /**
     * Padded rasters by the raster they pad. Rasters keep their identity across rebuilds, so a rebuild pads only the
     * layers whose pixels changed and hands every other one the very array it had before - which the memos keyed by
     * raster identity downstream (classification, canvas proxies, tile digests) then hit as well.
     */
    private object Paddings {
        private data class Key(val bounds: LayerBounds, val space: LayerSpace?, val target: LayerBounds)
        private val entries = java.util.Collections.synchronizedMap(java.util.WeakHashMap<ByteArray, List<Pair<Key, LayerRaster>>>())

        fun get(current: SourceLayer, bounds: LayerBounds): LayerRaster {
            val dense = CanvasDensity.dense(current)
            val key = Key(current.bounds, if (dense) LayerSpace.of(current) else null, bounds)
            val rgba = current.raster.rgba
            entries[rgba]?.firstOrNull { it.first == key }?.let { return it.second }
            val padded = if (dense) paddedAtDensity(current, bounds) else paddedAtCanvas(current, bounds)
            synchronized(entries) {
                entries[rgba]?.firstOrNull { it.first == key }?.let { return it.second }
                entries[rgba] = (listOf(key to padded) + entries[rgba].orEmpty()).take(4)
            }
            return padded
        }
    }

    /**
     * [current]'s raster laid over a transparent [bounds]-sized raster at its canvas spot, byte for byte what drawing
     * it there with Java2D gives ([legacyPadded]): a raster covering its bounds one to one is copied row by row, its
     * partly transparent pixels through the table of that draw ([SourceOver]). Anything else is drawn as before.
     */
    private fun paddedAtCanvas(current: SourceLayer, bounds: LayerBounds): LayerRaster {
        val source = current.raster
        val table = SourceOver.table
        if (table == null || source.width != current.bounds.width || source.height != current.bounds.height || source.width <= 0 || source.height <= 0)
            return legacyPadded(current, bounds)
        val rgba = ByteArray(Math.multiplyExact(Math.multiplyExact(bounds.width, bounds.height), 4))
        val dx = current.bounds.left - bounds.left; val dy = current.bounds.top - bounds.top
        val input = source.rgba
        for (y in 0 until source.height) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            var from = y * source.width * 4
            var to = ((y + dy) * bounds.width + dx) * 4
            for (x in 0 until source.width) {
                val alpha = input[from + 3].toInt() and 255
                if (alpha == 255) {
                    rgba[to] = input[from]; rgba[to + 1] = input[from + 1]; rgba[to + 2] = input[from + 2]; rgba[to + 3] = -1
                } else if (alpha != 0) {
                    val row = alpha shl 8
                    rgba[to] = table[row or (input[from].toInt() and 255)]
                    rgba[to + 1] = table[row or (input[from + 1].toInt() and 255)]
                    rgba[to + 2] = table[row or (input[from + 2].toInt() and 255)]
                    rgba[to + 3] = alpha.toByte()
                }
                from += 4; to += 4
            }
        }
        return LayerRaster(bounds.width, bounds.height, rgba)
    }

    /**
     * What Java2D's source-over draw gives a colour channel of value v at alpha a over a transparent pixel, at index
     * a * 256 + v: premultiplied and divided again, so faint pixels lose low bits (and alpha 0 keeps the transparent
     * pixel). Read off Java2D itself once; null - and [legacyPadded] used - should it ever not be one per-channel
     * function of (a, v) that keeps alpha.
     */
    internal object SourceOver {
        val table: ByteArray? by lazy {
            val source = BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB)
            for (a in 0 until 256) for (v in 0 until 256) source.setRGB(v, a, (a shl 24) or (v shl 16) or ((255 - v) shl 8) or v)
            val drawn = BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB)
            val graphics = drawn.createGraphics()
            try { graphics.drawImage(source, 0, 0, 256, 256, null) } finally { graphics.dispose() }
            val table = ByteArray(256 * 256)
            for (a in 0 until 256) for (v in 0 until 256) {
                val pixel = drawn.getRGB(v, a)
                if (a == 0) { if (pixel != 0) return@lazy null; continue }
                if ((pixel ushr 24) != a || (pixel ushr 16 and 255) != (pixel and 255)) return@lazy null
                if (a == 255 && (pixel and 255) != v) return@lazy null
                table[(a shl 8) or v] = pixel.toByte()
            }
            // Green carried 255 - v: the same function must give it.
            for (a in 1 until 256) for (v in 0 until 256)
                if ((drawn.getRGB(v, a) ushr 8 and 255) != (table[(a shl 8) or (255 - v)].toInt() and 255)) return@lazy null
            table
        }
    }

    /** The padding as it was always made: the raster drawn into the larger image with Java2D. */
    internal fun legacyPadded(current: SourceLayer, bounds: LayerBounds): LayerRaster {
        val left = bounds.left; val top = bounds.top
        val raster = BufferedImage(current.raster.width, current.raster.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until raster.height) for (x in 0 until raster.width) {
            val offset = (y * raster.width + x) * 4
            val rgba = current.raster.rgba
            raster.setRGB(x, y, ((rgba[offset + 3].toInt() and 255) shl 24) or
                ((rgba[offset].toInt() and 255) shl 16) or ((rgba[offset + 1].toInt() and 255) shl 8) or
                (rgba[offset + 2].toInt() and 255))
        }
        val image = BufferedImage(bounds.width, bounds.height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try { graphics.drawImage(raster, current.bounds.left - left, current.bounds.top - top,
            current.bounds.width, current.bounds.height, null) } finally { graphics.dispose() }
        val rgba = ByteArray(Math.multiplyExact(Math.multiplyExact(bounds.width, bounds.height), 4))
        for (y in 0 until bounds.height) for (x in 0 until bounds.width) {
            val pixel = image.getRGB(x, y)
            val offset = (y * bounds.width + x) * 4
            rgba[offset] = (pixel ushr 16).toByte(); rgba[offset + 1] = (pixel ushr 8).toByte()
            rgba[offset + 2] = pixel.toByte(); rgba[offset + 3] = (pixel ushr 24).toByte()
        }
        return LayerRaster(bounds.width, bounds.height, rgba)
    }
}
