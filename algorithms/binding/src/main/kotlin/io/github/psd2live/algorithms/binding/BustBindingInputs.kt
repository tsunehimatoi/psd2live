package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.ModifierAlgorithmContext
import io.github.psd2live.core.modifier.ModifierAlgorithmResource
import io.github.psd2live.format.compile.RigIrBinary
import io.github.psd2live.format.model.Authoring
import io.github.psd2live.format.model.Canvas
import io.github.psd2live.format.model.Physics
import io.github.psd2live.format.model.PhysicsGroup
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.format.model.Textures
import kotlinx.serialization.json.*

/** Rig-space torso measurements and unbound source vertices. Scaffold measurements define the native parent frame. */
data class BustBindingInputs(
    val torso: Torso,
    val layers: Map<String, Layer>,
    val targets: Map<String, String>,
    val frames: JsonObject,
    val branches: JsonObject,
    val sourceMeshes: ModifierAlgorithmResource,
    val textureSpace: TextureSpace,
    val names: Names,
    val physics: List<PhysicsGroup>,
    val overrides: List<PhysicsGroup> = emptyList(),
    val applies: Boolean = true,
    val previousBounce: Map<String, Float> = emptyMap(),
) {
    data class Torso(val centerX: Float, val shoulderY: Float, val waistY: Float, val halfWidth: Float) {
        init {
            require(listOf(centerX, shoulderY, waistY, halfWidth).all(Float::isFinite) && waistY >= shoulderY && halfWidth > 0f)
        }
    }

    data class Layer(val sourceId: String, val rigid: Boolean, val tile: String)
    data class Names(val y: String, val x: String, val folder: String)
    data class TextureSpace(val textures: Textures, val authoring: Authoring)

    init {
        require(layers.size <= 20_000 && targets.size <= 20_000 && targets.values.all(layers::containsKey))
        require(previousBounce.keys.all(targets::containsKey) && previousBounce.values.all { it in 0f..200f })
        require((layers.keys + layers.values.map { it.sourceId } + targets.keys).all { it.isNotBlank() && it.length <= 512 })
        require(textureSpace.textures.tileArt.isEmpty() && textureSpace.textures.pages.isEmpty() &&
            textureSpace.authoring.paths.isEmpty() && textureSpace.authoring.vertexGroups.isEmpty())
        require(listOf(names.y, names.x, names.folder).all { it.isNotBlank() && it.length <= 512 })
        val slots = setOf("PhysicsBustY", "PhysicsBustX")
        require(listOf(physics, overrides).all { groups -> groups.map { it.id }.toSet().let { it.size == groups.size && slots.containsAll(it) } })
    }

    fun encode() = ModifierAlgorithmContext(buildJsonObject {
        put("schema", SCHEMA)
        put("applies", applies)
        put("torso", JsonArray(listOf(torso.centerX, torso.shoulderY, torso.waistY, torso.halfWidth).map(::JsonPrimitive)))
        putJsonObject("layers") {
            for ((id, layer) in layers) putJsonObject(id) {
                put("source", layer.sourceId)
                put("rigid", layer.rigid)
                put("tile", layer.tile)
            }
        }
        put("targets", JsonObject(targets.mapValues { JsonPrimitive(it.value) }))
        put("previous_bounce", JsonObject(previousBounce.mapValues { JsonPrimitive(it.value) }))
        put("frames", frames)
        put("branches", branches)
        put("names", JsonArray(listOf(names.y, names.x, names.folder).map(::JsonPrimitive)))
    }, mapOf("source_meshes" to sourceMeshes,
        "texture_space" to ModifierAlgorithmResource(RigIrBinary.encode(
            RigIR(Canvas(0f, 0f), emptyList(), textures = textureSpace.textures, authoring = textureSpace.authoring))),
        "physics_defaults" to physicsResource(physics), "physics_overrides" to physicsResource(overrides)))

    companion object {
        const val SCHEMA = "body-bust/1"

        private fun physicsResource(groups: List<PhysicsGroup>) =
            ModifierAlgorithmResource(RigIrBinary.encode(RigIR(Canvas(0f, 0f), emptyList(), physics = Physics(groups))))

        fun decode(context: ModifierAlgorithmContext): BustBindingInputs {
            val values = context.values
            require(values.keys == setOf("schema", "applies", "torso", "layers", "targets", "frames", "branches", "names", "previous_bounce") &&
                values["schema"] == JsonPrimitive(SCHEMA) &&
                context.resources.keys == setOf("source_meshes", "texture_space", "physics_defaults", "physics_overrides")) { "Invalid chest binding inputs" }
            val texture = RigIrBinary.decode(context.resources.getValue("texture_space").read())
            require(texture == RigIR(Canvas(0f, 0f), emptyList(), textures = texture.textures, authoring = texture.authoring))
            fun groups(key: String): List<PhysicsGroup> {
                val model = RigIrBinary.decode(context.resources.getValue(key).read())
                require(model == RigIR(Canvas(0f, 0f), emptyList(), physics = Physics(model.physics.groups)))
                return model.physics.groups
            }
            val measurements = values.getValue("torso").jsonArray
            val names = values.getValue("names").jsonArray
            require(measurements.size == 4 && names.size == 3)
            fun measurement(index: Int) = measurements[index].jsonPrimitive.also { require(!it.isString) }.float
            fun string(value: JsonElement) = value.jsonPrimitive.also { require(it.isString) }.content
            val layers = values.getValue("layers").jsonObject.mapValues { (_, value) ->
                val row = value.jsonObject
                require(row.keys == setOf("source", "rigid", "tile"))
                Layer(string(row.getValue("source")), row.getValue("rigid").jsonPrimitive.also { require(!it.isString) }.boolean,
                    string(row.getValue("tile")))
            }
            return BustBindingInputs(Torso(measurement(0), measurement(1), measurement(2), measurement(3)), layers,
                values.getValue("targets").jsonObject.mapValues { string(it.value) },
                values.getValue("frames").jsonObject, values.getValue("branches").jsonObject,
                context.resources.getValue("source_meshes"), TextureSpace(texture.textures, texture.authoring),
                Names(string(names[0]), string(names[1]), string(names[2])),
                groups("physics_defaults"), groups("physics_overrides"),
                values.getValue("applies").jsonPrimitive.also { require(!it.isString) }.boolean,
                values.getValue("previous_bounce").jsonObject.mapValues { it.value.jsonPrimitive.also { number -> require(!number.isString) }.float })
        }
    }
}
