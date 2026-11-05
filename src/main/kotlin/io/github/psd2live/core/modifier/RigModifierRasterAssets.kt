package io.github.psd2live.core.modifier

import io.github.psd2live.core.*
import io.github.psd2live.project.WorkspaceSourceLayer
import kotlinx.serialization.json.jsonObject
import org.umamo.format.art.*

/** Restore independent raster assets before rebinding the authored checkpoint onto the live atlas. */
internal object RigModifierRasterAssets {
    fun restored(config: PipelineConfig): List<ClassifiedLayer> {
        val overlay = config.rigEdits
        if (overlay.checkpointIndex < 0) return emptyList()
        val checkpoint = overlay.authoringJournal[overlay.checkpointIndex]
        if ("modifier_rasters" !in checkpoint.getValue("authored").jsonObject.getValue("header").jsonObject) return emptyList()
        val rig = RigCheckpoint.decode(checkpoint).authored.rig
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
