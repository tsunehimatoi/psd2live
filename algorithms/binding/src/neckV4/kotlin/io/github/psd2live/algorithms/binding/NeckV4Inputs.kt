package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.*
import kotlinx.serialization.json.*

/** Source measurements only. The one resource is raw canvas positions, atlas UVs and triangles. */
internal class NeckV4Inputs(context: ModifierAlgorithmContext, checkpoint: () -> Unit) {
    val neck: NeckInputs
    val frames: ScaffoldFramesContext.Input
    val frameValues: JsonObject
    val torso: TorsoFrame
    val tuning: BodyMotionTuning
    val strength: Float
    val shell: HeadTurn?
    val providers: List<ModifierUpstreamCapability>
    val raw: Map<String, ScaffoldMesh.RawMesh>
    val materials: Map<String, Material>

    data class Material(val name: String, val opacity: Float)

    init {
        val values = context.values
        require(values.keys == setOf("schema", "neck", "frames", "body", "shell", "providers", "materials") &&
            values["schema"] == JsonPrimitive(NeckV4Algorithm.SCHEMA)) { "Invalid neck v4 source inputs" }
        neck = NeckInputs.decode(ModifierAlgorithmContext(values.getValue("neck").jsonObject, context.resources))
        frameValues = values.getValue("frames").jsonObject
        frames = ScaffoldFramesContext.decode(frameValues, checkpoint)
        val body = values.getValue("body").jsonObject
        require(body.keys == setOf("torso", "tuning", "strength"))
        torso = LegacyBodyInputs.torso(body.getValue("torso"))
        tuning = LegacyBodyInputs.tuning(body.getValue("tuning").jsonObject)
        strength = body.getValue("strength").jsonPrimitive.float.also { require(it.isFinite() && it in 0f..4f) }
        shell = values.getValue("shell").takeUnless { it == JsonNull }?.jsonArray?.let { array ->
            val numbers = array.map { it.jsonPrimitive.float.also { number -> require(number.isFinite()) } }
            require(numbers.size == 7 && numbers[2] > 0f && numbers[3] > 0f)
            HeadTurn(numbers[0], numbers[1], numbers[2], numbers[3], numbers[4], numbers[5], numbers[6], checkpoint)
        }
        providers = values.getValue("providers").jsonArray.map {
            Json.decodeFromJsonElement(ModifierUpstreamCapability.serializer(), it)
        }
        require(providers.all { it.instanceId != null && it.capability == it.type } &&
            providers.map { it.instanceId }.distinct().size == providers.size)
        materials = values.getValue("materials").jsonObject.mapValues { (_, value) ->
            val material = value.jsonObject
            require(material.keys == setOf("name", "opacity"))
            Material(material.getValue("name").jsonPrimitive.content,
                material.getValue("opacity").jsonPrimitive.float.also { require(it.isFinite() && it in 0f..1f) })
        }
        raw = ScaffoldMeshContext.decodeRawMeshes(context.resources.getValue(NeckInputs.SOURCE_MESHES), checkpoint)
        require(materials.keys == neck.targets.keys && raw.keys == neck.targets.values.map { it.sourceMesh }.toSet())
        require(neck.pivotX.toBits() == frames.head.canvasPivot.x.toBits() &&
            neck.pivotY.toBits() == frames.head.canvasPivot.y.toBits()) { "Neck v4 requires the canvas head pivot" }
        require(neck.targets.values.all { it.parent == ScaffoldHead.breathId.raw && it.frame == frames.body }) {
            "Neck v4 supports only the source body breath frame"
        }
    }

    fun branch(checkpoint: () -> Unit): List<org.umamo.runtime.model.Deformer> {
        val standing = frames.standing
        val legs = standing?.legs.orEmpty().map {
            BodyStance.Leg(Side.NONE, it.hip.x, it.hip.y, it.knee.x, it.knee.y, it.ankle.x, it.ankle.y, it.floorY)
        }
        val stance = BodyStance(frames.body, torso, legs, standing?.frame, strength, tuning)
        val neutral = ScaffoldFramesContext.build(frameValues, checkpoint).filter {
            it.id in setOf(ScaffoldBody.bodyId, ScaffoldBody.leanId, ScaffoldHead.breathId)
        }
        return LegacyBodyMotion.deformers(neutral, frames.body, stance, strength, frames.head.canvasPivot.y,
            checkpoint = checkpoint)
    }
}
