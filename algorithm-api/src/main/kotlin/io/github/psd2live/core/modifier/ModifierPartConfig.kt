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
}
