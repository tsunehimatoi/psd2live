package io.github.psd2live.application

import io.github.psd2live.core.TextureUpscaleConfig
import io.github.psd2live.project.WorkspaceSettingsCodec
import kotlinx.serialization.json.*

/** Nested patches preserve omitted generation inputs; invalid changes never produce a draft. */
internal fun mergeProjectSettings(current: JsonObject, changes: JsonObject): JsonObject {
    require(changes.isNotEmpty()) { "Provide at least one setting" }
    val nested = mutableMapOf<String, JsonElement>()
    changes["textureUpscale"]?.let { update ->
        val base = current["textureUpscale"] as? JsonObject
            ?: Json.encodeToJsonElement(TextureUpscaleConfig()).jsonObject
        nested["textureUpscale"] = JsonObject(base + update.jsonObject)
    }
    changes["meshFillParameters"]?.let { update ->
        nested["meshFillParameters"] = WorkspaceSettingsCodec.encodeFillParameters(
            WorkspaceSettingsCodec.mergeFillParameters(WorkspaceSettingsCodec.decodeFillParameters(current["meshFillParameters"]), update.jsonObject))
    }
    changes["rigTuning"]?.let { update ->
        nested["rigTuning"] = WorkspaceSettingsCodec.encodeRigTuning(
            WorkspaceSettingsCodec.mergeRigTuning(WorkspaceSettingsCodec.decodeRigTuning(current["rigTuning"] ?: current["bodyTuning"]), update.jsonObject))
    }
    val merged = JsonObject(current + changes + nested)
    validateProjectSettings(merged, changes)
    // A settings value has a domain type. Integer spelling of a float (2 vs 2.0) must not
    // create another edit when a desktop projection or save serializes that same value.
    val canonical = WorkspaceSettingsCodec.encode(WorkspaceSettingsCodec.decode(merged))
    // No wrap is stored as an absent key, so turning it off restores the settings text of before.
    val absent = if ("meshWrap" in changes && "meshWrap" !in canonical) setOf("meshWrap") else emptySet()
    return JsonObject(merged + canonical.filterKeys { it in changes } - absent)
}

private val ranges = mapOf(
    "atlasSize" to (256.0..16384.0), "meshSpacing" to (16.0..128.0),
    "meshOuterMargin" to (0.0..32.0), "meshEdgeWidth" to (0.5..32.0),
    "meshMaxEdgeDistance" to (6.0..128.0), "meshInteriorDensity" to (6.0..128.0),
    "texturePadding" to (0.0..32.0), "alphaThreshold" to (0.0..255.0),
    "headStrength" to (0.0..4.0), "bodyStrength" to (0.0..4.0),
    "mouthThickness" to (0.5..8.0), "exportPixelsPerUnit" to (1.0..1000000.0), "seeThroughOpacity" to (0.0..1.0),
    "meshWrap" to (0.0..io.github.psd2live.core.MeshWrap.range.endInclusive.toDouble()),
)
private val integers = setOf("atlasSize", "meshSpacing", "texturePadding", "alphaThreshold")
private val booleans = setOf(
    "meshSuppressBoundaryDiagonals", "meshOnly", "generateDeformers", "featureDisplacementEnabled", "mouthOutlineEnabled",
    "seeThroughBrows", "seeThroughEyes", "generatePhysics", "physicsFrontHair", "physicsBackHair", "physicsEyeJelly",
    "exportMotions", "motionBasic", "motionIdle", "motionBlink", "motionNod", "motionShake", "motionSkeleton",
    "exportCmo3", "exportMoc3", "exportJson", "exportCan3", "exportHiddenParts", "exportHiddenDrawables",
    "exportGuideImageParts", "exportIncludePhysics", "exportIncludeUserData", "exportIncludeDisplayInfo",
)
internal val workspaceProjectSettingKeys = ranges.keys + booleans +
    setOf("textureUpscale", "mouthShape", "runtimeTarget", "meshEdgeMode", "meshUnits", "meshTrace", "meshFillParameters", "rigTuning", "meshFillAlgorithm", "mouthCurve", "mouthColor",
        "disabledComponents")

internal fun validateProjectSettings(merged: JsonObject, changes: JsonObject) {
    require(changes.keys.all { it in workspaceProjectSettingKeys }) {
        "Unknown project setting"
    }
    changes.forEach { (key, value) ->
        when {
            key == "exportPixelsPerUnit" && value == JsonNull -> Unit
            key in ranges -> {
                val numeric = value.jsonPrimitive.doubleOrNull
                require(numeric != null && numeric.isFinite() && numeric in ranges.getValue(key)) { "$key is outside its UI range" }
                require(key !in integers || numeric % 1.0 == 0.0) { "$key must be an integer" }
            }
            key in booleans -> require(value.jsonPrimitive.booleanOrNull != null) { "$key must be boolean" }
            key == "mouthColor" -> require(value == JsonNull || value.jsonPrimitive.intOrNull?.let { it in 0..0xFFFFFF } == true) { "mouthColor must be RGB or null" }
            key == "mouthCurve" -> require(WorkspaceSettingsCodec.decodeMouthCurve(value) != null) { "Invalid mouth curve" }
            key == "meshFillAlgorithm" -> require(io.github.psd2live.core.MeshFillAlgorithm.entries.any { it.name == value.jsonPrimitive.content }) { "Unknown mesh fill algorithm" }
            key == "mouthShape" -> require(value.jsonPrimitive.content in setOf("flat", "smile", "w", "custom")) { "Unknown mouth shape" }
            key == "meshEdgeMode" -> require(io.github.psd2live.core.MeshEdgeMode.entries.any { it.name == value.jsonPrimitive.content }) { "Unknown mesh edge mode" }
            key == "meshUnits" -> require(io.github.psd2live.core.MeshUnits.entries.any { it.name == value.jsonPrimitive.content }) { "Unknown mesh units" }
            key == "meshTrace" -> require(io.github.psd2live.core.MeshTrace.entries.any { it.name == value.jsonPrimitive.content }) { "Unknown mesh trace" }
            key == "disabledComponents" -> require(value.jsonArray.all { c ->
                io.github.psd2live.core.RigComponents.switchable.any { it.id == c.jsonPrimitive.content } }) { "Unknown component" }
            key == "runtimeTarget" -> require(org.umamo.runtime.model.RuntimeTarget.entries.any { it.name == value.jsonPrimitive.content }) { "Unknown runtime target" }
        }
    }
    merged["textureUpscale"]?.let {
        kotlinx.serialization.json.Json.decodeFromJsonElement<TextureUpscaleConfig>(it)
    }
}
