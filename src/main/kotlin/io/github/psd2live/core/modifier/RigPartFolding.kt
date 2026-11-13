package io.github.psd2live.core.modifier

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Non-destructive runtime adapter that folds legacy same-scaffold part+submodule instances
 * (e.g. `eye/fitted` + `eye.wide` + `eye.smile`) into a unified part plugin invocation (`eye` / `brow`)
 * without rewriting saved project files.
 */
internal object RigPartFolding {
    data class FoldedPart(
        val folded: RigModifierInstance,
        val sourceIds: Set<String>,
        val subConfigs: JsonObject,
    )

    private val eyeFlags = mapOf(
        "eye.wide" to "wide",
        "eye.smile" to "smile",
        "eye.emotion" to "emotion",
        "eye.gaze-lid" to "gazeLid",
        "eye.highlight" to "highlight",
        "eye.lid-fade" to "lidFade",
        "eye.squash" to "squash",
        "eye.aftershock" to "aftershock",
    )

    private val browFlags = mapOf(
        "brow.shape" to "shape",
        "brow.spring" to "spring",
        "brow.blink" to "blink",
        "brow.lid-coupling" to "lidCoupling",
    )

    private val faceFlags = mapOf(
        "face.depth-turn" to "depthTurn",
        "face.neck" to "neck",
    )

    private val bodyFlags = mapOf(
        "body.bust" to "bust",
        "body.arms" to "arms",
    )

    fun foldEye(instances: List<RigModifierInstance>): List<FoldedPart> =
        foldArea(instances, fittedType = "eye/fitted", legacyType = "eye/legacy", partType = "eye", flagByType = eyeFlags)

    fun foldBrow(instances: List<RigModifierInstance>): List<FoldedPart> =
        foldArea(instances, fittedType = "brow/fitted", legacyType = "brow/legacy", partType = "brow", flagByType = browFlags)

    fun foldFace(instances: List<RigModifierInstance>): List<FoldedPart> =
        foldArea(instances, fittedType = "face/fitted", legacyType = "face/legacy", partType = "face", flagByType = faceFlags,
            matchByDependencies = true)

    fun foldBody(instances: List<RigModifierInstance>): List<FoldedPart> =
        foldArea(instances, fittedType = "body/legacy", legacyType = "body/legacy", partType = "body", flagByType = bodyFlags,
            defaultMode = "legacy", matchByDependencies = true)

    fun foldHair(instances: List<RigModifierInstance>): List<FoldedPart> =
        foldArea(instances, fittedType = "hair/chains", legacyType = "hair/legacy", partType = "hair", flagByType = emptyMap(),
            fittedMode = "chains")

    private fun foldArea(
        instances: List<RigModifierInstance>,
        fittedType: String,
        legacyType: String,
        partType: String,
        flagByType: Map<String, String>,
        fittedMode: String = "fitted",
        defaultMode: String? = null,
        matchByDependencies: Boolean = false,
    ): List<FoldedPart> {
        val enabled = instances.filter { it.enabled }
        val bases = enabled.filter { it.type == fittedType || it.type == legacyType }.distinctBy { it.id }
        return bases.map { base ->
            val mode = defaultMode ?: if (base.type == fittedType) fittedMode else "legacy"
            val scaffold = base.config["scaffold"]
            val children = enabled.filter { child ->
                child.type in flagByType &&
                    child.config["scaffold"] == scaffold &&
                    (child.config["base"]?.jsonPrimitive?.content == base.id ||
                        (matchByDependencies && child.config["dependencies"]?.let { deps ->
                            deps.toString().contains("\"${base.id}\"")
                        } == true))
            }
            val subConfigs = buildJsonObject {
                for (child in children) {
                    putJsonObject(child.type) {
                        put("targets", Json.encodeToJsonElement(child.targets))
                        child.config["values"]?.let { put("values", it) }
                        child.config["tuning"]?.let { put("tuning", it) }
                        child.config["physics"]?.let { put("physics", it) }
                        child.config["physicsEnabled"]?.let { put("physicsEnabled", it) }
                        child.config["dependencies"]?.let { put("dependencies", it) }
                        child.config["dip"]?.let { put("dip", it) }
                    }
                }
            }
            val foldedConfig = buildJsonObject {
                scaffold?.let { put("scaffold", it) }
                put("mode", mode)
                base.config["tuning"]?.let { put("tuning", it) }
                base.config["values"]?.let { put("values", it) }
                for (child in children) {
                    put(flagByType.getValue(child.type), true)
                }
            }
            val folded = RigModifierInstance(
                id = base.id,
                type = partType,
                version = 1,
                targets = base.targets,
                config = foldedConfig,
                outputAliases = base.outputAliases,
            )
            FoldedPart(
                folded = folded,
                sourceIds = setOf(base.id) + children.mapTo(HashSet()) { it.id },
                subConfigs = subConfigs,
            )
        }
    }
}
