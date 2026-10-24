package io.github.psd2live.application

import kotlinx.serialization.json.JsonObject

/** A settings gesture carries patches, never a copy of the authored rig or its journal. */
internal object WorkspaceSettingsDraft {
    fun presetDefaults(): JsonObject {
        val keys = setOf("headStrength", "bodyStrength", "rigTuning", "disabledComponents", "featureDisplacementEnabled",
            "mouthOutlineEnabled", "mouthShape", "mouthCurve", "mouthColor", "mouthThickness",
            "seeThroughBrows", "seeThroughEyes", "seeThroughOpacity", "exportMotions",
            "motionBasic", "motionIdle", "motionBlink", "motionNod", "motionShake", "motionSkeleton",
            "generatePhysics", "physicsFrontHair", "physicsBackHair", "physicsEyeJelly")
        return JsonObject(io.github.psd2live.project.WorkspaceSettingsCodec.encode(io.github.psd2live.core.PipelineConfig()).filterKeys { it in keys })
    }

    // These switches can release an authored pose. Keep their transitions, including off then on.
    private val switches = setOf("meshOnly", "motionBasic", "motionIdle", "motionBlink", "generatePhysics",
        "motionNod", "motionShake", "motionSkeleton", "mouthOutlineEnabled", "featureDisplacementEnabled")

    /** Continuous samples of values become their final patch between ordered switch transitions. */
    fun coalesce(changes: List<JsonObject>): List<JsonObject> = buildList {
        var pending = JsonObject(emptyMap())
        fun flush() { if (pending.isNotEmpty()) add(pending); pending = JsonObject(emptyMap()) }
        for (change in changes) {
            if (change.keys.any { it in switches }) { flush(); add(change) }
            else pending = merge(pending, change)
        }
        flush()
    }

    private fun merge(before: JsonObject, after: JsonObject): JsonObject = JsonObject(before + after.mapValues { (key, value) ->
        val previous = before[key]
        if (previous is JsonObject && value is JsonObject) merge(previous, value) else value
    })
}
