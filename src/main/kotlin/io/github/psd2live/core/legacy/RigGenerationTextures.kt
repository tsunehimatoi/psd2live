package io.github.psd2live.core.legacy

import io.github.psd2live.core.*

import kotlinx.serialization.json.*
import org.umamo.format.art.SourceArt
import org.umamo.format.art.LayerBounds
import org.umamo.format.art.LayerRaster
import java.util.Base64

/** Retired derived assets remain reconstructible for earlier creation and mesh journals. */
internal object RigGenerationTextures {
    fun recipes(overlay: RigEditOverlay): Map<String, JsonObject> = overlay.authoringJournal.lastOrNull {
        it["op"]?.jsonPrimitive?.contentOrNull == RigGenerationJournal.OP
    }?.get("textures")?.jsonObject?.mapValues { (_, value) ->
        if (value is JsonArray) buildJsonObject { put("recipe", value); put("layers", JsonArray(emptyList())) } else value.jsonObject
    }.orEmpty()

    fun preserve(overlay: RigEditOverlay, config: PipelineConfig, active: Collection<ClassifiedLayer>,
                 previous: Map<String, JsonObject> = recipes(overlay)): Map<String, JsonObject> {
        if (!config.mouthOutlineEnabled || config.meshOnly) return previous
        val owners = active.mapNotNull { (it.source as? MouthLipLayer)?.ownerId }.distinct().filter { owner ->
            active.any { it.source.id.raw == owner && it.semantic.tag in setOf(SemanticTag.MOUTH, SemanticTag.MOUTH_OPEN) }
        }
        if (owners.isEmpty()) return previous
        val baseline = RigGenerationBaseline.preserve(RigEditOverlay.Empty, config)
        val mesh = MeshGenerationBaseline.preserve(baseline, config)
        val recipe = JsonArray(mesh.authoringJournal)
        return previous + owners.associateWith { owner -> buildJsonObject {
            put("recipe", recipe)
            put("layers", JsonArray(active.mapNotNull { layer -> (layer.source as? MouthLipLayer)?.takeIf { it.ownerId == owner }?.let { lip ->
                buildJsonObject {
                    put("side", lip.side); put("bounds", buildJsonArray { add(lip.bounds.left); add(lip.bounds.top); add(lip.bounds.width); add(lip.bounds.height) })
                    put("width", lip.raster.width); put("height", lip.raster.height)
                    put("rgba", Base64.getEncoder().encodeToString(lip.raster.rgba))
                }
            } }))
        } }
    }

    fun layers(source: SourceArt, config: PipelineConfig): List<ClassifiedLayer> {
        val saved = recipes(config.rigEdits).flatMap { (owner, asset) ->
            val sourceLayer = source.layers.singleOrNull { it.id.raw == owner }
            if (sourceLayer == null) emptyList() else {
                val recipe = asset.getValue("recipe").jsonArray
                val restored = MeshGenerationBaseline.restore(config.copy(deletedLayerIds = emptySet(),
                    rigEdits = config.rigEdits.copy(authoringJournal = recipe.map { it.jsonObject })))
                val layers = asset.getValue("layers").jsonArray
                if (layers.isEmpty()) MouthLipLayers.prepare(CharacterAnalyzer.analyze(source, restored), restored).layers.filter {
                    (it.source as? MouthLipLayer)?.ownerId == owner
                } else layers.map { element ->
                    val layer = element.jsonObject; val side = layer.getValue("side").jsonPrimitive.int
                    val bounds = layer.getValue("bounds").jsonArray.map { it.jsonPrimitive.int }
                    val width = layer.getValue("width").jsonPrimitive.int; val height = layer.getValue("height").jsonPrimitive.int
                    val pixels = Base64.getDecoder().decode(layer.getValue("rgba").jsonPrimitive.content)
                    require(side in 0..1 && bounds.size == 4 && width > 0 && height > 0 && width.toLong() * height * 4 == pixels.size.toLong()) { "Invalid persisted lip artwork" }
                    val visible = (3 until sourceLayer.raster.rgba.size step 4).any { (sourceLayer.raster.rgba[it].toInt() and 255) > config.alphaThreshold }
                    val lip = MouthLipLayer(owner, side, sourceLayer, LayerRaster(width, height, if (visible) pixels else ByteArray(pixels.size)), LayerBounds(bounds[0], bounds[1], bounds[2], bounds[3]))
                    CharacterAnalyzer.classify(lip, restored.copy(layerOverrides = restored.layerOverrides +
                        (lip.id.raw to LayerClassificationOverride(tag = SemanticTag.MOUTH))))
                }
            }
        }
        val hasArtwork = source.layers.any { layer -> (3 until layer.raster.rgba.size step 4).any { (layer.raster.rgba[it].toInt() and 255) > config.alphaThreshold } }
        val live = if (hasArtwork) MouthLipLayers.prepare(CharacterAnalyzer.analyze(source, config), config).layers.filter { it.source is MouthLipLayer } else emptyList()
        return (saved + live).associateBy { it.source.id.raw }.values.toList()
    }
}
