package io.github.psd2live.core.modifier

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** Source canvas pixels, y down. These are not parent-deformer or atlas coordinates. */
@Serializable
data class ModifierSourceBounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    init {
        require(listOf(left, top, right, bottom).all { it.isFinite() } && right >= left && bottom >= top)
    }
}

/** Affine source-canvas to model-canvas transform. Parent-deformer transforms belong to upstream geometry. */
@Serializable
data class ModifierSourceTransform(
    val xx: Float = 1f, val xy: Float = 0f, val tx: Float = 0f,
    val yx: Float = 0f, val yy: Float = 1f, val ty: Float = 0f,
) {
    init {
        require(listOf(xx, xy, tx, yx, yy, ty).all { it.isFinite() })
        require((xx.toDouble() * yy - xy.toDouble() * yx) != 0.0) { "Singular source transform" }
    }
    fun map(x: Float, y: Float): Pair<Float, Float> = (xx * x + xy * y + tx) to (yx * x + yy * y + ty)
}

/** Packed straight RGBA8, row-major, no padding; semantic tokens are versioned by the capturing schema. */
@Serializable
data class ModifierSourceLayer(
    val id: String, val tag: String, val side: String, val role: String? = null,
    val left: Float, val top: Float, val width: Int, val height: Int, val rgba: String,
    val bounds: ModifierSourceBounds, val centroidX: Float, val centroidY: Float, val opaquePixels: Int,
) {
    init {
        require(id.isNotBlank() && rgba.isNotBlank() && id.length <= 256 && rgba.length <= 256)
        require(width >= 0 && height >= 0 && width.toLong() * height <= 16L * 1024 * 1024)
        require(listOf(left, top, centroidX, centroidY).all { it.isFinite() })
        require(opaquePixels >= 0 && opaquePixels.toLong() <= width.toLong() * height)
    }
}

/** Immutable source observations captured before evaluation. No filesystem paths or host callbacks. */
class ModifierSourceInputs(layers: List<ModifierSourceLayer>, resources: Map<String, ModifierAlgorithmResource>,
                           targets: Map<String, String> = emptyMap(),
                           val toModel: ModifierSourceTransform = ModifierSourceTransform()) {
    val layers: List<ModifierSourceLayer> = java.util.Collections.unmodifiableList(layers.toList())
    val targets: Map<String, String> = java.util.Collections.unmodifiableMap(targets.toMap())
    private val resources = resources.toMap()
    private val byId = this.layers.associateBy { it.id }
    init {
        require(this.layers.size <= 10000 && byId.size == this.layers.size) { "Duplicate source layers" }
        require(this.targets.size <= 10000 && this.targets.all { (id, source) -> id.isNotBlank() && id.length <= 256 && source in byId }) { "Invalid target source reference" }
        require(this.resources.keys == this.layers.mapTo(HashSet()) { it.rgba }) { "Missing or unused source raster" }
        this.layers.forEach { layer ->
            require(this.resources.getValue(layer.rgba).size.toLong() == layer.width.toLong() * layer.height * 4) { "Invalid source raster size: ${layer.id}" }
        }
        // Apply the same aggregate bounds as transported author resources.
        ModifierAlgorithmContext(resources = this.resources)
    }

    private val rasters by lazy { this.layers.associate { it.id to Raster(it.width, it.height, this.resources.getValue(it.rgba)) } }

    fun layerForTarget(id: String): ModifierSourceLayer = byId.getValue(targets.getValue(id))

    fun raster(layer: ModifierSourceLayer): Raster {
        require(byId[layer.id] == layer) { "Layer does not belong to captured sources" }
        return rasters.getValue(layer.id)
    }

    class Raster internal constructor(val width: Int, val height: Int, private val resource: ModifierAlgorithmResource) {
        val digest get() = resource.digest
        fun alpha(x: Int, y: Int): Int = if (x !in 0 until width || y !in 0 until height) 0
            else resource.unsignedByte((y * width + x) * 4 + 3)
        fun rgba(): ByteArray = resource.read()
    }

    fun context(): ModifierAlgorithmContext = ModifierAlgorithmContext(buildJsonObject {
        put("schema", SCHEMA)
        put("layers", Json.encodeToJsonElement(layers))
        put("targets", Json.encodeToJsonElement(targets))
        put("to_model", Json.encodeToJsonElement(toModel))
    }, resources)

    companion object {
        const val SCHEMA = "source_layers/1"
        fun from(context: ModifierAlgorithmContext): ModifierSourceInputs {
            require(context.values.keys == setOf("schema", "layers", "targets", "to_model") &&
                context.values.getValue("schema").jsonPrimitive.content == SCHEMA) { "Unsupported source input schema" }
            return ModifierSourceInputs(Json.decodeFromJsonElement(context.values.getValue("layers")), context.resources,
                Json.decodeFromJsonElement(context.values.getValue("targets")),
                Json.decodeFromJsonElement(context.values.getValue("to_model")))
        }
    }
}
