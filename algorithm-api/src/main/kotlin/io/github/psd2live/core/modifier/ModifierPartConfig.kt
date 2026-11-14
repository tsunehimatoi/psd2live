package io.github.psd2live.core.modifier

import kotlinx.serialization.json.*

/** Shared interpretation of persisted part settings, including older UI spellings. */
object ModifierPartConfig {
    /** Insertion order is the execution order inside each part. */
    val features: Map<String, Map<String, String>> = mapOf(
        "eye" to linkedMapOf("wide" to "eye.wide", "smile" to "eye.smile", "emotion" to "eye.emotion",
            "gazeLid" to "eye.gaze-lid", "highlight" to "eye.highlight", "lidFade" to "eye.lid-fade",
            "squash" to "eye.squash", "aftershock" to "eye.aftershock"),
        "brow" to linkedMapOf("shape" to "brow.shape", "spring" to "brow.spring", "blink" to "brow.blink", "lidCoupling" to "brow.lid-coupling"),
        "mouth" to linkedMapOf("outline" to "mouth.outline", "vowel" to "mouth.vowel", "capture" to "mouth.capture",
            "tongue" to "mouth.tongue", "jaw" to "mouth.jaw", "cheekLid" to "mouth.cheek-lid",
            "depth" to "mouth.interior-depth", "spring" to "mouth.spring"),
        "face" to linkedMapOf("depthTurn" to "face.depth-turn", "neck" to "face.neck"),
        "body" to linkedMapOf("bust" to "body.bust", "arms" to "body.arms"),
    )

    const val LID_COUPLING = "lidCoupling"

    /** The canonical key wins even when explicitly false. Reading never rewrites history. */
    fun enabled(config: JsonObject, key: String): Boolean =
        (config[key] ?: if (key == LID_COUPLING) config["lid"] else null)
            ?.jsonPrimitive?.booleanOrNull == true

    fun withFlag(config: JsonObject, key: String, enabled: Boolean): JsonObject =
        JsonObject((if (key == LID_COUPLING) config - "lid" else config) + (key to JsonPrimitive(enabled)))

    /** Reconstitute a child without borrowing the parent's output aliases or editable values. */
    fun child(
        part: RigModifierInstance,
        type: String,
        version: Int = 1,
        upstream: List<ModifierUpstreamCapability> = emptyList(),
        captured: JsonObject? = null,
    ): RigModifierInstance {
        val payload = part.config["subConfigs"]?.jsonObject?.get(type)?.jsonObject
            ?: captured ?: JsonObject(emptyMap())
        require(payload["version"]?.jsonPrimitive?.int?.let { it == 1 } != false) {
            "${part.id}: unsupported $type child facade version"
        }
        val flag = features[part.type]?.entries?.singleOrNull { it.value == type }?.key
        val suffix = if (part.type == "mouth") flag ?: type else type
        val config = buildJsonObject {
            put("scaffold", part.config.getValue("scaffold"))
            put("base", part.id)
            part.config["physicsEnabled"]?.let { put("physicsEnabled", it) }
            if (type == "mouth.outline") part.config["color"]?.let { put("color", it) }
            val dependencies = linkedMapOf<String, JsonElement>()
            val required = when (type) {
                "brow.lid-coupling", "mouth.cheek-lid" -> listOf("eye/fitted")
                "eye.highlight", "mouth.interior-depth", "face.neck", "face.depth-turn" -> listOf("face/legacy")
                "body.bust", "body.arms" -> listOf("body/legacy", "face/legacy")
                "mouth.tongue" -> listOf("mouth.capture")
                "mouth.spring" -> listOf("mouth.jaw")
                else -> emptyList()
            }
            for (capability in required) {
                upstream.lastOrNull { it.capability == capability }?.let { dependencies[capability] = JsonPrimitive(it.instanceId) }
                if (capability == "body/legacy" && part.type == "body" || capability == "face/legacy" && part.type == "face")
                    dependencies[capability] = JsonPrimitive(part.id)
            }
            (part.config["dependencies"] as? JsonObject)?.forEach { (key, value) -> if (key in required) dependencies[key] = value }
            (payload["dependencies"] as? JsonObject)?.let { dependencies.putAll(it) }
            if (dependencies.isNotEmpty()) put("dependencies", JsonObject(dependencies))
            payload.forEach { (key, value) ->
                if (key !in setOf("id", "version", "targets", "outputAliases", "dependencies")) put(key, value)
            }
        }
        return RigModifierInstance(
            id = payload["id"]?.jsonPrimitive?.content ?: "${part.id}:$suffix", type = type,
            version = payload["version"]?.jsonPrimitive?.int ?: version,
            targets = payload["targets"]?.jsonArray?.map { Json.decodeFromJsonElement(ModifierTarget.serializer(), it) } ?: part.targets,
            config = config,
            outputAliases = payload["outputAliases"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }.orEmpty(),
        )
    }
}
