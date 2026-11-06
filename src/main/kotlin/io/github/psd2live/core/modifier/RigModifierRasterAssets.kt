package io.github.psd2live.core.modifier

import io.github.psd2live.core.*
import io.github.psd2live.project.WorkspaceSourceLayer
import io.github.psd2live.format.model.Bytes
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.format.model.TexturePage
import io.github.psd2live.format.model.TileArt
import io.github.psd2live.targets.cubism.PuppetIr
import org.umamo.runtime.model.DeformPath
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
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

    fun attach(rig: RigIR, model: PuppetModel, ribbons: List<Ribbon>, config: PipelineConfig): RigIR {
        if (ribbons.isEmpty()) return rig
        val generated = PuppetIr.toIr(model.copy(drawables = ribbons.map { it.drawable }, deformPaths = ribbons.flatMap { it.paths }))
        val meshes = generated.meshes.associateBy { it.id }
        val owners = rig.meshes.associateBy { it.id }
        val outputs = ribbons.map { ribbon ->
            val source = ribbon.asset.source
            ModifierRasterOutput(owners.getValue(ribbon.owner.raw), meshes.getValue(ribbon.drawable.id.raw),
                io.github.psd2live.format.model.SourceLayer(source.id.raw, source.name, source.groupPath,
                    source.bounds.left, source.bounds.top, source.bounds.width, source.bounds.height, source.visible),
                ModifierAlgorithmResource(source.raster.rgba), generated.authoring.paths.filter { it.mesh == ribbon.drawable.id.raw },
                ModifierRasterOutput.UvSpace.PAGE)
        }
        return attach(rig, outputs, config)
    }

    /** The same atomic resource boundary is used for generated and compatibility artwork. */
    fun attach(rig: RigIR, outputs: List<ModifierRasterOutput>, config: PipelineConfig, checkpoint: () -> Unit = {}): RigIR {
        if (outputs.isEmpty()) return rig
        ModifierRasterOutput.validate(rig, outputs, checkpoint)
        val sourceId = PuppetSourceAtlas.SOURCE_ID_RAW
        val existing = rig.authoring.sources.singleOrNull { it.id == sourceId }
        require(outputs.none { output -> existing?.layers?.any { it.key == output.layer.key } == true }) { "Modifier raster collides with existing artwork" }
        val tileIds = rig.textures.tiles.mapTo(HashSet()) { it.id }
        require(outputs.all { tileIds.add(PuppetSourceAtlas.tileIdFor(it.layer.key).raw) }) { "Modifier raster tile collision" }
        val assets = outputs.map { output ->
            checkpoint()
            val source = output.layer
            CharacterAnalyzer.classify(WorkspaceSourceLayer(LayerId(source.key), source.name, source.groupPath, SourceLayerKind.Raster,
                source.visible, Int.MAX_VALUE, LayerBounds(source.left, source.top, source.width, source.height), 1f, false,
                LayerBlend.Normal, ChannelMask.ALL, LayerRaster(source.width, source.height, output.pixels.read()), null, null, true), config)
        }
        val packed = append(PackedAtlas(emptyList(), emptyMap()), assets, config)
        checkpoint()
        val offset = rig.textures.pages.size
        val meshes = outputs.map { output ->
            checkpoint()
            val placement = packed.placementByLayerId.getValue(output.layer.key)
            val geometry = requireNotNull(output.mesh.geometry)
            val uvs = if (output.uvSpace == ModifierRasterOutput.UvSpace.PAGE) geometry.uvs else {
                val page = packed.pages[placement.page].image
                val values = geometry.uvs.toArray()
                for (i in values.indices step 2) {
                    val pixel = placement.toPage(values[i], values[i + 1])
                    values[i] = pixel[0] / page.width
                    values[i + 1] = pixel[1] / page.height
                }
                io.github.psd2live.format.model.Floats.wrap(values)
            }
            output.mesh.copy(page = placement.page + offset, tile = PuppetSourceAtlas.tileIdFor(output.layer.key).raw,
                geometry = geometry.copy(uvs = uvs))
        }
        val byOwner = outputs.groupBy { it.owner.id }
        fun children(input: List<io.github.psd2live.format.model.ChildRef>) = input.flatMap { child ->
            val added = if (child is io.github.psd2live.format.model.ChildRef.MeshRef) byOwner[child.id].orEmpty() else emptyList()
            added.map { io.github.psd2live.format.model.ChildRef.MeshRef(it.mesh.id) } + child
        }
        val source = existing?.copy(layers = existing.layers + outputs.map { it.layer }) ?: io.github.psd2live.format.model.SourceFile(
            sourceId, "artwork.psd", null, "psd", outputs.map { it.layer })
        val sources = if (existing == null) rig.authoring.sources + source else rig.authoring.sources.map { if (it.id == sourceId) source else it }
        val tiles = outputs.map { output ->
            val p = packed.placementByLayerId.getValue(output.layer.key)
            val origin = p.toPage(0f, 0f)
            io.github.psd2live.format.model.TextureTile(PuppetSourceAtlas.tileIdFor(output.layer.key).raw, output.layer.name,
                output.layer.width, output.layer.height,
                io.github.psd2live.format.model.TilePlacement(p.page + offset, origin[0], origin[1], p.scaleX, p.scaleY, p.rotation),
                io.github.psd2live.format.model.SourceRef(sourceId, output.layer.key, true))
        }
        val textures = rig.textures.copy(
            pages = rig.textures.pages + packed.pages.map { TexturePage(it.image.width, it.image.height, Bytes.wrap(it.png)) },
            tilePages = rig.textures.tilePages + packed.pages.map { io.github.psd2live.format.model.PageSize(it.image.width, it.image.height) },
            tiles = rig.textures.tiles + tiles,
            bindings = rig.textures.bindings + meshes.associate { it.id to it.page },
            tileArt = rig.textures.tileArt + outputs.map { TileArt(PuppetSourceAtlas.tileIdFor(it.layer.key).raw, it.layer.width, it.layer.height, Bytes.wrap(it.pixels.read())) })
        val changes = ModifierModelChanges(meshes = ModifierObjectChanges(added = meshes),
            parts = ModifierObjectChanges(updated = rig.parts.mapNotNull { part ->
                val updated = part.copy(children = children(part.children))
                if (updated == part) null else ModifierObjectUpdate(part, updated)
            }),
            rootChildren = ModifierFieldChange(rig.rootChildren, children(rig.rootChildren)),
            authoring = ModifierFieldChange(rig.authoring, rig.authoring.copy(sources = sources, paths = rig.authoring.paths + outputs.flatMap { it.paths })),
            textures = ModifierFieldChange(rig.textures, textures))
        val candidate = changes.apply(rig, checkpoint)
        return candidate.withPuppet(PuppetIr.toPuppet(candidate).withDerivedRenderRoot()).copy(textures = textures).also { checkpoint() }
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
