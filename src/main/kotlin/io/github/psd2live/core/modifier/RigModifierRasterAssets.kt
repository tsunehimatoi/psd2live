package io.github.psd2live.core.modifier

import io.github.psd2live.core.*
import io.github.psd2live.project.WorkspaceSourceLayer
import io.github.psd2live.format.model.Bytes
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.format.model.TexturePage
import io.github.psd2live.format.model.TileArt
import io.github.psd2live.targets.cubism.PuppetIr
import org.umamo.runtime.model.AtlasPage
import org.umamo.runtime.model.DeformPath
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.withDerivedRenderRoot
import kotlinx.serialization.json.jsonObject
import org.umamo.format.art.*

/** Restore independent raster assets before rebinding the authored checkpoint onto the live atlas. */
internal object RigModifierRasterAssets {
    fun restored(config: PipelineConfig): List<ClassifiedLayer> {
        val overlay = config.rigEdits
        if (overlay.checkpointIndex < 0) return emptyList()
        val checkpoint = overlay.authoringJournal[overlay.checkpointIndex]
        if ("modifier_rasters" !in checkpoint.getValue("authored").jsonObject.getValue("header").jsonObject) return emptyList()
        return layers(RigCheckpoint.decode(checkpoint).authored.rig, config)
    }

    fun layers(rig: BuiltRig, config: PipelineConfig): List<ClassifiedLayer> {
        val sources = rig.puppet.sources.associateBy { it.id }
        return rig.modifierRasterAssets.mapNotNull { art ->
            val tile = requireNotNull(rig.puppet.atlas.tileById[org.umamo.runtime.model.AtlasTileId(art.tile)]) {
                "Stored modifier raster has no tile: ${art.tile}"
            }
            val reference = requireNotNull(tile.source) { "Stored modifier tile has no source: ${art.tile}" }
            val source = sources.getValue(reference.sourceId).layers.single { it.key == reference.layerKey }
            if (source.key in config.deletedLayerIds) return@mapNotNull null
            require(art.width == tile.width && art.height == tile.height &&
                art.width.toLong() * art.height * 4 == art.rgba.size.toLong()) { "Invalid stored modifier raster: ${art.tile}" }
            val layer = WorkspaceSourceLayer(LayerId(source.key), source.name, source.groupPath, SourceLayerKind.Raster,
                source.visible, Int.MAX_VALUE, LayerBounds(source.left, source.top, source.width, source.height), 1f, false,
                LayerBlend.Normal, ChannelMask.ALL, LayerRaster(art.width, art.height, art.rgba.shared()), null, null, true)
            CharacterAnalyzer.classify(layer, config)
        }
    }

    data class Ribbon(val owner: DrawableId, val drawable: Drawable, val asset: ClassifiedLayer, val paths: List<DeformPath>)

    fun attach(rig: RigIR, model: PuppetModel, ribbons: List<Ribbon>, analysis: PipelineAnalysis, config: PipelineConfig): RigIR {
        if (ribbons.isEmpty()) return rig
        val assetLayers = ribbons.map { it.asset }
        val packed = append(PackedAtlas(emptyList(), emptyMap()), assetLayers, config)
        val offset = model.atlas.pages.size
        val placements = packed.placementByLayerId.mapValues { (_, placement) -> placement.copy(page = placement.page + offset) }
        val (additional, sources) = PuppetSourceAtlas.build(analysis.copy(layers = assetLayers), placements)
        val extra = sources.single()
        require(extra.layers.none { layer -> model.sources.any { source -> source.id == extra.id && source.layers.any { it.key == layer.key } } }) {
            "Modifier raster collides with existing artwork"
        }
        val mergedSources = if (model.sources.none { it.id == extra.id }) model.sources + extra else model.sources.map {
            if (it.id == extra.id) it.copy(layers = it.layers + extra.layers) else it
        }
        val meshes = ribbons.map { it.drawable.copy(texturePage = placements.getValue(it.asset.source.id.raw).page) }
        require((model.drawables + meshes).map { it.id }.distinct().size == model.drawables.size + meshes.size) {
            "Generated raster mesh ID collision"
        }
        val byOwner = ribbons.groupBy { it.owner }
        fun children(children: List<OrgChild>) = children.flatMap { child ->
            val attachments = if (child is OrgChild.Drawable) byOwner[child.id].orEmpty() else emptyList()
            attachments.map { OrgChild.Drawable(it.drawable.id) } + child
        }
        val updated = model.copy(
            atlas = model.atlas.copy(pages = model.atlas.pages + packed.pages.map { AtlasPage(it.image.width, it.image.height) },
                tiles = model.atlas.tiles + additional.tiles),
            sources = mergedSources, drawables = model.drawables + meshes,
            parts = model.parts.map { it.copy(children = children(it.children)) }, rootChildren = children(model.rootChildren),
            deformPaths = model.deformPaths + ribbons.flatMap { it.paths },
        ).withDerivedRenderRoot()
        val pages = rig.textures.pages + packed.pages.map { TexturePage(it.image.width, it.image.height, Bytes.wrap(it.png)) }
        val bindings = rig.textures.bindings + meshes.associate { it.id.raw to it.texturePage }
        val art = ribbons.map { ribbon ->
            val raster = ribbon.asset.source.raster
            TileArt(requireNotNull(ribbon.drawable.atlasTileId).raw, raster.width, raster.height, Bytes.wrap(raster.rgba))
        }
        val textures = PuppetIr.toIr(updated, pages, bindings).textures.copy(tileArt = rig.textures.tileArt + art)
        return rig.withPuppet(updated).copy(textures = textures)
    }

    /** Appending dedicated pages keeps upstream UVs and page bindings unchanged. */
    fun append(base: PackedAtlas, assets: List<ClassifiedLayer>, config: PipelineConfig): PackedAtlas {
        if (assets.isEmpty()) return base
        require(assets.map { it.source.id.raw }.distinct().size == assets.size &&
            assets.none { it.source.id.raw in base.placementByLayerId }) { "Modifier raster collides with existing artwork" }
        val budget = config.effectiveAtlasBudget()
        var next = base
        // Per-asset pages keep earlier placements unchanged when another modifier adds or removes art.
        for (asset in assets) {
            val packed = AtlasLayout.pack(listOf(asset), budget.pageSize, budget.padding)
            val placements = packed.placementByLayerId.mapValues { (_, placement) -> placement.copy(page = placement.page + next.pages.size) }
            next = next.copy(pages = next.pages + packed.pages, placementByLayerId = next.placementByLayerId + placements)
        }
        return next
    }
}
