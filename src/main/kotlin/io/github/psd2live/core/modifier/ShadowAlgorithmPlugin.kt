package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.shadow.CastShadowAlgorithm
import io.github.psd2live.algorithms.shadow.CastShadowConfig
import io.github.psd2live.algorithms.shadow.FormShadowAlgorithm
import io.github.psd2live.algorithms.shadow.FormShadowConfig
import io.github.psd2live.algorithms.shadow.ShadowWorldAlgorithm
import io.github.psd2live.algorithms.shadow.ShadowWorldConfig
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Bundled in-process shadow algorithm entrypoints. */
internal object ShadowAlgorithmPlugin {
    const val TYPE_WORLD = "shadow.world"
    const val TYPE_CAST = "shadow.cast"
    const val TYPE_FORM = "shadow.form"

    private val baseWrites = setOf("mesh", "part", "deformer", "hierarchy", "parameter", "textures", "authoring")
    private val composable = setOf("mesh", "part", "deformer", "hierarchy")

    private fun rasterContext(source: RigModifierSource?, instance: RigModifierInstance): ModifierAlgorithmContext {
        if (source == null) return ModifierAlgorithmContext.Empty
        val layersById = source.analysis.layers.associateBy { it.source.id.raw }
        val resources = LinkedHashMap<String, ModifierAlgorithmResource>()
        val meta = buildJsonObject {
            for (target in instance.targets) {
                if (target.kind != "mesh") continue
                val layerId = source.layersByMesh[target.id] ?: target.id
                val layer = layersById[layerId] ?: continue
                val raster = layer.source.raster
                if (raster.width <= 0 || raster.height <= 0) continue
                put(target.id, buildJsonObject {
                    put("w", raster.width)
                    put("h", raster.height)
                })
                resources["shadow.rgba:${target.id}"] = ModifierAlgorithmResource(raster.rgba)
            }
        }
        return if (resources.isEmpty()) ModifierAlgorithmContext.Empty
        else ModifierAlgorithmContext(
            values = buildJsonObject { put("shadow.rasters", meta) },
            resources = resources,
        )
    }

    val worldDefinition = RigModifierDefinition(
        type = TYPE_WORLD,
        version = 1,
        validate = { _, instance -> ShadowWorldConfig.fromJson(instance.config) },
        composableKinds = emptySet(),
        changeWrites = emptySet(),
        plugin = ShadowWorldAlgorithm(),
    )

    fun definitions(source: RigModifierSource?): List<RigModifierDefinition> = listOf(
        worldDefinition,
        RigModifierDefinition(
            type = TYPE_CAST,
            version = 1,
            validate = { _, instance -> CastShadowConfig.fromJson(instance.config) },
            composableKinds = composable,
            changeWrites = baseWrites,
            authorContext = { rasterContext(source, it) },
            plugin = CastShadowAlgorithm(),
        ),
        RigModifierDefinition(
            type = TYPE_FORM,
            version = 1,
            validate = { _, instance -> FormShadowConfig.fromJson(instance.config) },
            composableKinds = composable,
            changeWrites = baseWrites,
            authorContext = { rasterContext(source, it) },
            plugin = FormShadowAlgorithm(),
        ),
    )
}
